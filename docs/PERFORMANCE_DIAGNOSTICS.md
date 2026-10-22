# Windowsミラーリング映像のフレーム落ち調査

2026-10-22時点の調査。**原因は未特定で、改善効果の実機計測はまだ行っていません。** 今回は既定のFPS・解像度・同期・デコーダー・描画・バッファ設定を維持し、調査結果と計測手順だけを追加します。

利用者から、WindowsでiPhoneの映像・音声を受信できたこと、ときどきミラー映像だけが滑らかでなくなることの報告がありました。申告環境はCore i5-14400F、GeForce RTX 4060 Ti、280Hzモニターです。エンジンは導入済みですが、選択中の配布版・実デコーダー・実送信FPSはまだ照合していません。この報告を、開発環境での実機測定や全環境の動作保証とは扱いません。

## 1. 現在の処理と設定

調査対象はMirrorDock `980a54f`以降の同一実装、配布定義のUxPlay `f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967`、GStreamer 1.28.8です。別の実行ファイルを指定している場合は、その版で再確認が必要です。

通常のH.264画面ミラーリング経路は次のとおりです。JavaFXは映像フレームを処理せず、UxPlayの別ウィンドウを操作します。

```text
iPhone送出 → TCP受信・復号 → appsrc → queue → h264parse
 → decodebin → videoconvert → videoscale → d3d11videosink → DXGI/DWM/モニター
```

| 項目 | コード・公式実装で確認した内容 | 計測上の意味 |
| --- | --- | --- |
| MirrorDockの引数 | `-n <受信機名> -nh -d 1 -as autoaudiosink -vs d3d11videosink`。音声OFF時は`-as 0` | FPS・解像度・decoder・QoS・lateness・バッファ値はアプリ側で指定していない |
| 送信上限の案内 | UxPlay既定の`maxFPS=30`、表示広告は1920×1080・refreshRate=60 | 実FPSはiPhoneが決める。広告の60と実送信60fpsは別 |
| 280Hzとの関係 | 280÷30は約9.33、280÷60も約4.67で整数ではない | 固定リフレッシュ表示では、欠落がなくても表示間隔が不均一になり得る。VRR・DWM・実FPSの影響もあり、今回の原因とはまだ断定できない |
| メディア同期 | UxPlayは既定で`video_sync=true`。NTPから変換したPTSと`sink sync=true`を使用 | 同期時刻に間に合わないフレームを落とす経路がある |
| 描画 | Windowsでは`d3d11videosink`を明示指定 | Direct3D描画の指定であり、GPUデコードを保証しない |
| デコード | `decodebin`が選択。配布物は`libav`と`d3d11`を含む | `avdec_h264`か`d3d11h264dec`かを実動作で確認する。どちらを使用中とも未確定 |
| GPUデコーダーの候補 | GStreamerは対応GPUで`d3d11h264dec`を`avdec_h264`より高いrankで登録し得る | GPUデコーダーが存在しないと決めつけない。生成を試みたfactory名だけでも選択確定にならない |
| CPU/GPU間転送 | system-memory入力の場合、d3d11 sink側でコピー・GPUアップロードが発生する | `Video Decode`・`3D`・`Copy`とCPUを別々に観察する |
| queue | UxPlayは通常の`queue`を挿入。GStreamer既定は200 buffers／10MiB／1秒の先に達する上限、`leaky=no` | 「常に1秒遅延する」設定ではない。満杯なら通常はブロックし、上流側にも滞留し得る |
| appsrc | `is-live=true`、時間形式とcapsを設定。アプリ側ではqueue上限やleakyを追加指定しない | queueだけの調整で、受信から描画までの滞留を解決できるとは限らない |
| 現在の診断とログ | GUI診断は`-h`とFirewall確認。`MirrorDock.log`は時刻と状態名のみ | 稼働中のFPS・QoS・drop・PTS・選択デコーダーはGUIにも状態ログにも保存されない |
| 外部環境設定 | 子プロセスは親の環境変数を継承。`GST_DEBUG`・`GST_TRACERS`・`GST_PLUGIN_FEATURE_RANK`等は残る | 全環境を公開せず、関係する変数だけ設定の有無を確認する |

根拠：ローカルの[起動処理](../src/main/java/jp/mirrordock/UxPlayService.java)、[子プロセス環境](../src/main/java/jp/mirrordock/EngineEnvironment.java)、[配布プラグイン](../scripts/engine/bundle.py)、[ログ保存](../src/main/java/jp/mirrordock/LogService.java)、UxPlayの[既定広告値](https://github.com/FDH2/UxPlay/blob/f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967/lib/raop.c#L620)、[映像pipeline](https://github.com/FDH2/UxPlay/blob/f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967/renderers/video_renderer.c#L384)、[PTS処理](https://github.com/FDH2/UxPlay/blob/f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967/renderers/video_renderer.c#L642)。

## 2. d3d11videosinkとフレームドロップ

GStreamer 1.28.8では、d3d11 sinkが継承する`GstVideoSink`が初期化時に次を設定します。一般的な`GstBaseSink`だけの既定値を転用すると説明が変わる点に注意してください。

| 設定 | GstVideoSinkの初期値 | 意味・注意 |
| --- | --- | --- |
| `max-lateness` | 5,000,000ns（5ms） | フレームの終端時刻／duration等を加味する遅着判定の許容値。「全フレームが生成から5msで落ちる」という意味ではない |
| `processing-deadline` | 15,000,000ns（15ms） | live pipelineの処理予算としてlatency計算に加わる値。実デコード時間の測定値ではない |
| `qos` | `true` | downstreamの遅延をupstreamへ知らせる。decoder側の追い付き処理にも影響する |
| `sync` | UxPlayが`true`を指定 | GStreamerのメディア時計への同期。Windowsモニターの垂直同期／280Hz設定とは同じ設定ではない |
| `adapter` | `-1` | 既定のGPU選択。実際のadapter・GPUデコード利用は未計測 |
| `force-aspect-ratio` | `true` | アスペクト比維持 |

BaseSinkは、同期時計・有効PTS・duration等を使って遅着を判定し、条件に合えば`buffer late, dropping`とQoS情報を出します。decoderもdownstream QoSに応じてフレームを落とし、libavには期限超過時に非参照フレームを省く追い付き処理があります。**sinkのdropだけを数えても、decoderで落ちたフレームを含む全欠落数にはなりません。**

QoSの遅延値は送信・ネットワーク・時刻同期・デコード・スケジューリング・描画の影響を受けます。QoS発生だけで「Wi-Fiが原因」「GPUが遅い」とは判定できません。

根拠：[GstVideoSink初期化](https://github.com/GStreamer/gstreamer/blob/1.28.8/subprojects/gst-plugins-base/gst-libs/gst/video/gstvideosink.c#L169)、[BaseSink遅着判定](https://github.com/GStreamer/gstreamer/blob/1.28.8/subprojects/gstreamer/libs/gst/base/gstbasesink.c#L3095)、[decoderのQoS処理](https://github.com/GStreamer/gstreamer/blob/1.28.8/subprojects/gst-plugins-base/gst-libs/gst/video/gstvideodecoder.c#L3707)、[libavの追い付き処理](https://github.com/GStreamer/gstreamer/blob/1.28.8/subprojects/gst-libav/ext/libav/gstavviddec.c#L1860)、[D3D11のコピー経路](https://github.com/GStreamer/gstreamer/blob/1.28.8/subprojects/gst-plugins-bad/sys/d3d11/gstd3d11videosink.cpp#L1683)。

## 3. 最初の実機確認：設定を変えない

1. 設定画面の`uxplay.exe`の場所を確認する。配布版なら`bin`の親の`mirrordock-engine.properties`と`packages.tsv`で上流commit・GStreamer版を照合する。Run番号はダウンロード元で確認する。
2. Windowsの「ディスプレイの詳細設定」で利用中モニターの実設定Hzを、Windows／NVIDIAの該当設定でVRRの状態を確認する。モニターの定格280Hzと現在設定が同じとは限らない。そのうえで、同じ非DRMコンテンツ／一定のスクロール操作を60秒再現する。静止画だけではFPSを比較できない。ウィンドウサイズ・位置・表示モニター・音声・ネットワーク条件を固定する。
3. 引っかかった時刻と、音声も同時に途切れたか、約1秒周期か、ウィンドウ操作／回転直後かを記録する。画面・音声の録画は診断の必須条件にしない。
4. タスクマネージャーでCPU全体だけでなく論理プロセッサ単位、GPUの`Video Decode`・`3D`・`Copy`を見る。全体CPU使用率や3Dグラフだけでデコード負荷を判断しない。1秒程度のサンプリングでは短いスパイクを見逃すため、正常に見えても原因を除外しない。
5. 次節のログ付き試験と、ログなしの基準試験を比較する。**まだ`-fps 60`、`-vsync no`、解像度低下、バッファ拡大、GPU固定は行わない。**

共有する初期情報は、版、実行条件、引っかかった時刻、音声との同時発生、CPU/GPUの観察結果で足ります。生の環境変数一覧やUxPlay全文ログの公開は不要です。

## 4. 通常のGUI経路のまま、一時的にGStreamerログを取る

現在の「状態ログを保存」では性能診断ログは取れません。以下は利用者が必要時に行う診断例です。**Windowsでの手順自体は未実行です。** 既定の映像パラメーターを維持し、起動するMirrorDockとその子UxPlayへ、プロセス単位でGStreamer診断環境を渡します。恒久的な環境変数変更は行いません。

MirrorDockを終了してからWindows PowerShellを開き、`$app`を実際のMirrorDock.exeのパスへ置き換えます。

```powershell
$app = 'C:\実際のインストール先\MirrorDock.exe'
if (-not (Test-Path -LiteralPath $app -PathType Leaf)) {
    throw 'MirrorDock.exeの実際のパスを指定してください。'
}
$logPattern = Join-Path $env:TEMP ('MirrorDock-GStreamer-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-%p.log')
$names = @('GST_DEBUG', 'GST_DEBUG_FILE', 'GST_DEBUG_NO_COLOR')
$previous = @{}
foreach ($name in $names) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    $env:GST_DEBUG = '2,GST_ELEMENT_FACTORY:4,d3d11*:4'
    $env:GST_DEBUG_FILE = $logPattern
    $env:GST_DEBUG_NO_COLOR = '1'
    Start-Process -FilePath $app -Wait
}
finally {
    foreach ($name in $names) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
    Write-Host ('診断ログ（%pはプロセスID）: ' + $logPattern)
}
```

GUIで受信を開始し、基準と同じ操作を60秒行って、受信停止・アプリ終了します。GStreamerが`%p`をプロセスIDへ置き換え、UxPlayとplugin-scannerのファイルを分離します。タスクマネージャーの詳細で`uxplay.exe`のPIDを確認し、そのファイルを対象にします。これは起動情報・警告を調べる比較的低負荷のprofileです。factoryの生成を試みた記録だけでは、最終的に使われたdecoderを確定できません。

それで不足する場合だけ、同じ手順の`GST_DEBUG`の値を次へ置き換えて**10〜15秒程度**の詳細試験を行います。

```text
2,decodebin:5,GST_PADS:5,GST_CAPS:5,basesink:5,videodecoder:5,libav:5,queue:5,d3d11*:5,GST_QOS:5
```

- 成功したpad接続、caps交渉、継続して処理するchainを突合し、実デコーダーとメモリー形式を調べます。候補生成やcapsの候補一覧だけでは確定しません。
- 映像sink名`d3d11videosink_h264`等に対応する`buffer late, dropping`、`qos: rendered ... dropped ...`、decoder側の`Dropping frame due to QoS`、libavの`QOS: hurry up`を時刻付きで調べます。音声sinkのQoSとは区別します。
- ログファイルはGStreamerのメタデータ診断用です。映像・音声の保存オプションは追加しません。パス・caps・端末に関連する情報が含まれる可能性があるため、全文をそのまま公開せず必要部分を確認して共有します。自動送信はしません。
- 詳細ログはCPU・ディスクI/Oを増やし、症状を誘発し得ます。`GST_DEBUG_FILE`はJavaの標準出力読取への流入を減らしますが、計測負荷をゼロにしません。ログ付きだけで悪化した場合、その結果を通常動作の原因と断定しません。
- ログが空、必要な記録がない、drop行がない場合は「不明」です。「ドロップ0」や「GPUデコード成功」とは報告しません。

`gst-inspect-1.0 d3d11videosink`等は、同じ配布物・同じplugin検索環境での存在／既定値の確認には使えますが、**稼働中のUxPlay内部のsink統計を読むコマンドではありません。**

## 5. ネットワーク・デコード・描画の切り分け

この版の通常ミラー映像はTCPで、pipelineに`rtpjitterbuffer`はありません。UDP/RTP用のjitterbuffer設定を、そのままAirPlayミラー映像へ適用しません。TCPでは再送や順序待ちにより、平均帯域に余裕があっても映像到着が詰まる可能性があります。

映像と音声は別経路で処理されるため、音声が滑らかなことだけでは、映像の通信・デコード・描画の問題を除外できません。

低遅延のlive pipelineでqueueが短時間空になること自体は正常にも起こります。空／underrunだけでネットワーク異常と判定せず、再現時刻・入力間隔・対象TCP flow・後段処理と合わせます。

| 同じ時刻に比較する観測 | 次に調べる範囲 | その観測だけでは分からないこと |
| --- | --- | --- |
| 実映像TCP flowの再送／到着間隔の乱れ、appsrc投入間隔の乱れ、queueの枯渇 | LAN・Wi-Fi・送信側、受信threadのスケジューリング | 全PCのTCP再送カウンターだけではiPhoneのflowと特定できない。到着が遅い理由はネットワークだけではない |
| 入力が継続しているのにdecoder出力が遅れる、CPUの特定core／Video Decodeが混む | デコーダー、caps変換、CPU/GPUコピー、実行待ち | GPU全体使用率が低くても、短い処理待ちや別engineの負荷は除外できない |
| decoder出力は安定、sink遅着・GPU Copy/3D・DWM/Present待ちが重なる | 描画、GPU転送、ドライバー、ウィンドウ制御 | sink QoSだけでは描画側が根因と断定できない |
| 計測上の送出／処理間隔は安定し欠落増加がないが、見た目だけ不均一 | 30fps等の送信上限、280Hzとの表示cadence、DWM/VRR | 現状のログにdrop行がないだけでは、この条件を満たしたことにならない |

短いCPU/GPUの待ち時間は、必要に応じてMicrosoftのWPR/WPA・GPUView等で同じ時刻軸に載せる方法があります。GPUViewはADKの選択機能Windows Performance Toolkitに付属し、公式の`Log.cmd`による記録は管理者端末を必要とします。MirrorDock本体を常時管理者実行するという意味ではありません。まずGUIでの基準観察と限定ログを優先し、追加導入・権限が必要なトレースは後段にします。イベント時刻・待ち時間・flowの情報で調べ、メディアpayload全体の保存を初手にはしません。ETLにもプロセス名・パス・通信情報等が含まれ得るため、そのまま公開しません。

Windowsの`GetTcpStatistics`やPC全体のTCP送信再送数は全アプリ合算です。さらに、PC側の送信再送数だけでは、iPhone→PC映像の送信者側再送を観測・除外できません。増加だけでも、増加しないことだけでも、映像のネットワーク原因を判定しません。

UxPlayの`-d 1`はパケット単位のdebug出力を抑え、映像到着の`now/ntp/latency/ts`行も出しません。引数なしの`-d`で詳細を出す方法はありますが、端末・暗号関連の情報や大量ログが出るため通常の共有手順にはしません。その`latency`もNTP推定や受信threadの待ちを含み、純粋なWi-Fi遅延ではありません。

## 6. MirrorDock固有の比較候補

[MainController](../src/main/java/jp/mirrordock/MainController.java)は1秒間隔で[WindowManager.engineOnTop](../src/main/java/jp/mirrordock/WindowManager.java)を呼び、毎回`EnumWindows`と`SetWindowPos`を行います。**最前面OFFでも`HWND_NOTOPMOST`を毎秒適用するため、チェックをOFFにするだけではこの周期処理を除外できません。** DWM／Presentへ影響しているかは未計測です。

比較は次の順番で行います。

1. GUI経由の基準試験で、引っかかりと約1秒周期の一致を確認する。
2. 受信停止後MirrorDockを完全終了し、同じ`uxplay.exe`を直接起動する試験と比較する。二重起動しない。
3. 直接試験でも、受信機名、音声、sink、`-d 1`、空の`UXPLAYRC`、作業ディレクトリ、PATH、plugin/scanner/registry設定、関連する親環境、映像ウィンドウのサイズ・位置・モニターを一致させる。通常のユーザー`.uxplayrc`を読ませない。
4. GUI有無で差が出ても、直ちに最前面制御が原因とはしない。ウィンドウ操作・Java側ログ処理等を一項目ずつ計装して切り分ける。

現行GUIは受信中の性能計測や、この周期処理だけを停止するスイッチを持ちません。直接起動の条件を揃えられない場合は、比較結果を未確定として扱い、次の診断実装で対応します。

## 7. 改善候補と副作用：現時点では適用しない

| 候補 | 採用前に必要な根拠 | 副作用・評価点 |
| --- | --- | --- |
| ウィンドウ状態／対象が変わったときだけ最前面制御 | 周期的な引っかかりとの相関、直接起動との差 | 画質やmedia遅延を直接変えない。再接続・ウィンドウ再生成・全画面時の最前面復元を回帰確認する |
| `-fps 60` | 実送信が30fps等で、処理落ちとcadence差を区別できた | 上限の案内だけで送信60fpsを保証しない。帯域・デコード・GPU負荷が増え、280Hzとの整数比にもならない |
| 確認したGPU decoder／converterを選択 | active decoder・caps・コピー経路、GPU能力を確認 | 互換性、色・回転・解像度変更、コピー回数を評価。sinkをGPUにしただけでzero-copyにはならない |
| `max-lateness`を少量緩和 | 時折の遅着が確認でき、追加遅延を測定できた | 古いフレームを表示するため遅延許容が増える。無制限化を初期対応にしない |
| queue等の上限調整 | どの段階で溜まる／枯れるかを観測 | 拡大は遅延・メモリー増、縮小／leakyは欠落や圧縮フレーム依存関係への影響がある |
| `-vsync no`／QoS無効化 | 個別比較で同期処理との因果を確認できた | A/V同期、ペーシング、decoderの追い付き処理が変わる。映像の遅れが蓄積する可能性があり既定にしない |
| 一時的な表示Hz比較／別sink | cadence／描画との相関、対応sink同梱を確認 | デスクトップ全体や表示経路が変わる。D3D12等は現配布に含まれず単純置換できない。利用者の確認操作なしに変更しない |
| 解像度・画質低下 | decoder負荷などの根拠と、利用者が許容する条件 | 明確な画質低下を伴うため初手にしない |

sinkの追加propertyを`-vs "d3d11videosink ..."`へ渡すと、UxPlayが自動追加するAlt+Enter用propertyが入らなくなります。同期実験ではUxPlayが後段へ`sync=true/false`を追加することにも注意し、単にsink文字列へ`sync=false`を足した比較を有効と扱いません。現時点でこれらの引数は変更していません。

## 8. 次の診断実装で必要な観測

通常の動作を維持しつつ、利用者が明示的に開始した短時間の診断に限定します。

- 映像受信・appsrc投入・decoder出力・sink処理／dropを、段階別の時刻・数・処理間隔で観測する。TCP flowの観測とは時刻を合わせる。
- 実際のdecoder名、active caps／メモリー形式、GPU adapterを記録する。
- QoS messageのsource element・processed/dropped・jitterを構造化し、1秒程度の集計とフレーム間隔の分布を出す。送信／decoder／sink／実表示のFPSを混同しない。異なる段階のカウンターを単純加算せず、重複計上を避ける。
- sinkの`stats`はread-onlyのGObject値なのでエンジン内で取得する。連続する区間の`rendered`差分÷経過時間を使い、`average-rate`をそのまま描画FPSと扱わない。再生状態変更時のカウンターresetを区別する。
- `rendered`はsinkの処理数であり、物理モニターで表示されたフレーム数と同一と保証しない。必要な場合はDXGI/DWM側の観測と突合する。
- 診断なしとの比較、取得時間・ファイルサイズの上限、停止時の復元、端末情報の除去を用意する。映像・音声・鍵を保存しない。

現在の配布には`fpsdisplaysink`の`debugutilsbad`、`GST_TRACERS`用の`coretracers`を含めていません。環境変数を設定するだけで使えるとは案内しません。`-FPSdata`もiPhone側の報告で、Windowsの実描画FPSではありません。`GST_DEBUG_DUMP_DOT_DIR`だけでは、dumpを呼ばないアプリの稼働pipeline図を自動取得できません。これらは今後の計装候補であり、今回は未実装です。

## 公式資料

- [UxPlay：同期・遅着フレームの説明](https://github.com/FDH2/UxPlay/blob/f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967/README.md#L657)
- [UxPlay：-FPSdataと-fps](https://github.com/FDH2/UxPlay/blob/f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967/README.md#L1607)
- [UxPlay：Windowsのsinkとproperty指定](https://github.com/FDH2/UxPlay/blob/f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967/README.md#L1149)
- [UxPlay：TCP受信と到着ログ](https://github.com/FDH2/UxPlay/blob/f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967/lib/raop_rtp_mirror.c#L239)
- [GStreamer 1.28.8：D3D11 decoderの登録](https://github.com/GStreamer/gstreamer/blob/1.28.8/subprojects/gst-plugins-bad/sys/d3d11/plugin.cpp#L197)
- [GStreamer 1.28.8：D3D11/DXGI Present](https://github.com/GStreamer/gstreamer/blob/1.28.8/subprojects/gst-plugins-bad/sys/d3d11/gstd3d11window_win32.cpp#L1299)
- [GStreamer 1.28.8：queue既定値](https://github.com/GStreamer/gstreamer/blob/1.28.8/subprojects/gstreamer/plugins/elements/gstqueue.c#L135)
- [GStreamer 1.28.8：stats実装](https://github.com/GStreamer/gstreamer/blob/1.28.8/subprojects/gstreamer/libs/gst/base/gstbasesink.c#L5915)
- [GStreamer 1.28.8：debugログのPID別ファイル展開](https://github.com/GStreamer/gstreamer/blob/1.28.8/subprojects/gstreamer/gst/gstinfo.c#L524)
- [Microsoft：GPUViewの用途](https://github.com/MicrosoftDocs/windows-driver-docs/blob/staging/windows-driver-docs-pr/display/using-gpuview.md)
- [Microsoft：DWMのFlip QueueとVSync待ち](https://github.com/MicrosoftDocs/windows-driver-docs/blob/staging/windows-driver-docs-pr/display/gpuview-flip-queue.md)
- [Microsoft：GPUView導入](https://github.com/MicrosoftDocs/windows-driver-docs/blob/staging/windows-driver-docs-pr/display/installing-gpuview.md)・[記録時の権限と時間](https://github.com/MicrosoftDocs/windows-driver-docs/blob/staging/windows-driver-docs-pr/display/generating-a-trace-for-gpuview.md)
- [Microsoft：GetTcpStatisticsの対象](https://github.com/MicrosoftDocs/sdk-api/blob/docs/sdk-api-src/content/iphlpapi/nf-iphlpapi-gettcpstatistics.md)・[送信再送カウンターの定義](https://github.com/MicrosoftDocs/sdk-api/blob/docs/sdk-api-src/content/tcpmib/ns-tcpmib-mib_tcpstats_lh.md)

GStreamerの主要3ファイルは、公式タグと配布に対応するMSYS2ソース内原本のSHA-256一致を確認しました。Microsoft LearnのWPR/GPUViewページへの直接アクセスは403でしたが、上記のMicrosoft公式GitHub原本は確認しました。WPRの個別profile名・コマンドを未確認のまま指定せず、WDF向け資料のレジストリ変更やVerifier手順も適用しません。この文書はWindowsでのフレーム落ちの計測結果を示すものではありません。
