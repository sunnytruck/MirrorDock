# 30／60fps設定とGUI性能診断

2026-10-23更新。目標は安定した60fpsと低遅延ですが、**Windows・iPhone実機での性能計測、原因の特定、改善効果の確認は未実施です。** 60fpsの上限設定と、原因を調べるためのGUI診断を追加しました。解像度・画質・メディア同期・デコーダー選択・バッファの既定値は変更していません。

利用者から、WindowsでiPhoneの映像・音声を受信できたこと、ときどきミラー映像だけが滑らかでなくなることの報告があります。申告環境はCore i5-14400F、GeForce RTX 4060 Ti、280Hzモニターです。この報告を、開発環境での実機測定や全環境の動作保証とは扱いません。

## 1. 新しいアプリと診断対応エンジン

30／60fpsの切替はUxPlay公式の`-fps 30`／`-fps 60`を使用します。旧設定は30fpsを維持し、不正な保存値も30へ戻します。**この値はiPhoneへ案内する送信上限で、実際の受信FPSとは別です。** 60を指定しても送信60fpsを強制・保証しません。

GUI性能診断には、MirrorDock側の`MDPERF1`計測拡張を含む新しいWindowsエンジンが必要です。上流の固定コミットは引き続き`f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967`で、MirrorDockがそのソースへ計測用パッチを適用してビルドします。診断は親アプリと子エンジンの標準入力パイプで明示的に開始・停止し、外部サーバーへ情報を送りません。

**以前のRun #8のエンジンは`MDPERF1`非対応です。** 通常の映像・音声受信やFPS上限の指定に引き続き使用できますが、自動計測値は「計測不可」です。性能診断を使う場合は、[Experimental Windows engine](https://github.com/sunnytruck/MirrorDock/actions/workflows/engine.yml)から今回の変更を含む成功した実行を選び、新しい`MirrorDock-Engine-Windows.zip`をGUIから再導入してください。既存の導入ファイルは保持されます。新しいCIの成功・Artifacts公開と、実機の受信・性能検証は区別します。

診断OFF時には、計測専用のフレームprobe・Present通知・サンプリングtimerを動作させません。計測対応エンジンの制御readerは入力待ちになります。通常の診断ではPresent通知を有効化せず、その項目は「計測不可」です。追加チェックの「Present詳細計測」は既定OFFで、選んだ診断中だけ有効にし、終了時は元のproperty値へ戻します。診断ON時の負荷と、OFF時の実機での性能差は今後の確認対象です。

**Present詳細計測は観測によって描画経路を変えます。** GStreamerの`emit-present=true`では毎フレームの`ClearRenderTargetView`や`Present1`のdirty rectangle最適化の条件が変わるため、通常診断と同じ負荷とは扱いません。まず詳細OFFで30／60fps・ウィンドウ制御を比較し、必要な場合だけ詳細ONを別条件として測定します。診断全体がOFFのときの見た目とも比較してください。追加の計測による引っかかりを元の不具合と混同しないことが必要です。

## 2. GUIで30fpsと60fpsを比較する

1. 「受信エンジンの導入・診断」から診断対応エンジンZIPを導入します。「受信エンジンを診断」は従来どおりヘルプ起動・DLL・Firewallの確認で、性能計測の開始ボタンとは別です。
2. 「設定」の「フレームレート上限」で30または60を選びます。変更は保存され、**次回の受信開始時**に反映します。受信中に選び直しても、その接続の設定や実FPSを途中で変更しません。
3. 「受信開始」でiPhoneを接続し、映像が安定してから「性能診断・30／60fpsの比較」を開きます。「受信中の設定上限」が今回試す30／60と一致することを確認します。
4. 診断中のウィンドウ制御方式を選び、「Present詳細計測」はOFFのまま「性能診断を開始」を押します。受信は継続し、約60秒で自動停止します。「性能診断を停止」で途中停止もできます。受信停止・切断・アプリ終了でも計測を終了します。
5. 同じ非DRMコンテンツ／一定のスクロール操作で再現します。静止画だけでFPSを比較せず、ウィンドウのサイズ・位置、表示モニター、音声ON/OFF、ネットワーク、診断時間を揃えます。
6. 終了後、選択式の「音ズレ」「カクつき」「体感遅延」を必要に応じて入力し、「診断結果を保存…」からJSONを保存します。これは手動観察であり、数値のA/Vずれや端末間遅延を自動測定した値ではありません。
7. 受信を止め、30／60のもう一方を選んで再び受信開始し、同じ条件・同じ制御方式で診断します。30へ戻す場合も同じ操作です。

パネルには設定上限ごとの直近の終了結果を比較表示します。この比較表示は現在のアプリ内だけに保持し、保存は現在の完了診断1件ずつです。複数条件を残す場合は、各診断を別のJSONへ保存してください。音ズレ・遅延の主観評価も、各条件の診断後に選び直します。

Present直前の通知間隔が必要な場合は、同じ条件で「Present詳細計測」をONにした診断を追加します。詳細OFFの結果と分けて保存し、JSONの`detailed_present_enabled`で区別してください。30fpsと60fpsの比較では、このON/OFFも揃えます。

Windowsの「ディスプレイの詳細設定」で実設定Hzを、Windows／NVIDIAの該当設定でVRR状態を確認してください。定格280Hzと現在設定が同じとは限りません。固定280Hzでは280÷30も280÷60も整数でないため、欠落なしでも表示間隔が不均一になり得ます。モニターHzの比較は任意の手動試験とし、30／60の比較と同時に変更しません。アプリは表示HzやVRR設定を変更しません。

## 3. 計測値の定義と限界

新エンジンは、短時間の診断中だけparser・decoder・sinkを観測し、追加指定した場合だけPresent通知を観測します。GUIは概ね1秒ごとの最新区間と、比較可能な区間の平均を表示します。未取得・非対応・不正な値は推測で補わず「計測不可」にします。

| 表示 | 計測する位置・値 | 意味と注意 |
| --- | --- | --- |
| 受信FPS（解析後AU） | H.264/H.265 parserの出力でAU alignmentを確認した圧縮Access Unit数の差分÷時間 | TCPパケット数やネットワーク線上での純粋な到着FPSではない。parserまでの受信・復号・queue・処理待ちの影響を含む |
| デコード出力FPS | 実際にバッファを出したvideo decoderの出力数の差分÷時間 | デコード後の出力ペース。デコード処理そのものの所要時間ではない |
| sink描画FPS | GstBaseSinkの`rendered`差分÷時間 | sinkが処理したフレーム数。物理モニターで表示されたFPSではない |
| Present直前通知／秒 | `d3d11videosink::present`通知回数の差分÷時間。詳細計測ON時のみ | DXGI呼出前の通知で、再描画を含む場合がある。DXGI Presentの成功数・表示完了数・パネルの実表示FPSではない |
| sinkドロップ | GstBaseSinkの`dropped`の開始時からの差分 | 同一継続区間のsink側ドロップ。decoderで落ちたフレームを含む総欠落数ではない |
| 実際のデコーダー | 実際に出力が確認できたdecoderの許可されたfactory識別子 | factory生成を試みたログ名だけで選んだ推測値ではない。未取得・対応外識別子は計測不可 |
| 受信／デコード／Present間隔 P95・最大 | 各観測地点の単調時計による処理間隔 | P95は直近最大512間隔の窓、最大は同一継続区間の値。端末間遅延や純粋なWi-Fi遅延ではない |
| 実画面の表示FPS | 計測不可 | DWM・VRR・物理モニターの表示結果をこの診断だけでは取得しない |
| decoder側のドロップ総数 | 計測不可 | sink dropへ合算・推定しない |
| TCP再送・数値のネットワーク遅延 | 計測不可 | parser出力間隔から再送数や遅延を推定しない |
| 数値の音ズレ・端末間表示遅延 | 計測不可 | 選択式の手動観察と区別する |

pipelineの再生状態変更等でカウンターが戻った場合は継続区間を区別し、比較不能な差分を平均へ混ぜません。初回のpipeline検出も区間変更に含まれるため、区間変更だけで再生失敗と判断しません。P95・最大・sinkドロップも新しい区間の値になるため、切断やresetを含む結果を一つの連続した60秒の総計として扱わないでください。異なる段階のprocessed/dropを単純加算すると重複計上するため、段階別に読みます。

PLAYINGからの状態変更やflushを検出すると、その診断を途中終了します。1秒のサンプル間にリセットして再開する場合も、境界の値を無効化して混在を避けます。受信自体は停止しません。映像が再び安定してから、必要に応じて新しい診断を開始してください。

`stats.average-rate`はそのまま描画FPSに採用せず、`rendered`差分と経過時間から算出します。`gst-inspect-1.0`を別に実行しても、稼働中のUxPlay内部のsink統計は取得できません。

## 4. 毎秒のSetWindowPosを比較する

診断パネルでは次の方式を選べます。選択は次の性能診断中だけ適用し、診断終了時に元の方式へ戻します。比較条件を揃えるため、診断中は最前面設定の操作を止めます。

| 方式 | 動作 | 比較時の注意 |
| --- | --- | --- |
| 状態変更時のみ（`ON_CHANGE`） | 通常の既定方式。PID・対象ウィンドウ・最前面の要求／実状態等が変わった場合に適用 | 対象の列挙は続けるが、状態が同じときの`SetWindowPos`再適用を抑制する |
| 毎秒再適用・従来方式（`PERIODIC`） | 従来どおり1秒周期で最前面状態を再適用する | 最前面OFFでも`HWND_NOTOPMOST`を繰り返す経路を比較できる |
| 診断中のウィンドウ制御を停止（`SUSPENDED`） | 診断中は映像ウィンドウの列挙・最前面再適用を停止する | 停止時点の最前面状態を保持する。最前面を自動解除する操作ではない |

最初はPresent詳細OFFで、30fps・同じ音声・同じサイズで3方式を各約60秒ずつ比較し、その後60fpsでも比較します。各結果を保存し、sink drop、受信・デコードの間隔、手動観察を照合します。Present間隔を加える場合は詳細ONの別試験にします。計測負荷、内容の違い、切断・resetも確認してください。差が出ても、その一回だけで毎秒のウィンドウ操作が原因と断定しません。

`ON_CHANGE`への変更は画質や同期・バッファを直接変えませんが、再接続・新しいウィンドウ・全画面時の最前面復元はWindows実機での回帰確認が必要です。現時点でこの変更によってカクつきが改善したとは報告しません。

## 5. ネットワーク・デコード・描画の切り分け

通常のH.264画面ミラーリング経路は次のとおりです。JavaFXは映像フレームを処理しません。

```text
iPhone送出 → TCP受信・復号 → appsrc → queue → h264parse
 → decodebin → videoconvert → videoscale → d3d11videosink → DXGI/DWM/モニター
```

| 同じ時刻に比較する観測 | 次に調べる範囲 | この診断だけでは分からないこと |
| --- | --- | --- |
| 受信AUの間隔が乱れ、decoderやsinkのペースも落ちる | 送信側、TCP受信、復号、parserまでの処理待ち | parser出力は線上の到着ではない。ネットワークと受信threadのスケジューリングは区別できない |
| 受信AUは安定し、decoder出力が遅れる | decoder、処理待ち、caps変換・CPU/GPU間転送 | 出力間隔だけでは処理時間・CPU/GPU負荷やQoSの根因までは確定できない |
| decoder出力は安定し、sink dropやPresent間隔の乱れが増える | sink、GPU、DWM、ドライバー、ウィンドウ制御 | Present通知は表示完了ではなく、sink QoSだけでは描画側が根因と確定できない |
| 各段階のペースは安定し、sink dropが増えないが見た目が不均一 | 実送信FPS、モニターHz、DWM／VRR、未計測の段階 | decoder側dropや物理表示を取得していないため「全欠落なし」とは断定できない |

保存JSONの`series`には、経過時間・計測区間と段階別カウンター／区間FPSなどを概ね1秒ごと、直近最大64件まで記録します。平均値だけでなく、受信・デコード・sinkのペースが落ちた時刻が対応しているかを照合してください。カクつきを感じた診断経過秒も手元で確認すると比較しやすくなりますが、自由記述のメモや画面の記録はアプリの保存対象に含めません。この時間分解能で短い停止や通信の原因をすべて捕捉するものではありません。

映像はTCPで受信し、このpipelineに`rtpjitterbuffer`はありません。UDP/RTP用のjitterbuffer設定をそのまま適用しません。TCPでは再送・順序待ちにより、平均帯域に余裕があっても到着が詰まる可能性があります。音声は別経路なので、音声が滑らかでも映像の通信・デコード・描画を除外できません。live queueが一時的に空になること自体は正常にも起こります。

最初はGUI数値と手動観察を使い、不足する場合はタスクマネージャーの論理CPU、GPUの`Video Decode`・`3D`・`Copy`を別々に観察します。1秒程度の更新では短いスパイクを見逃すため、正常に見えても原因を除外しません。さらに必要なら、MicrosoftのWPR/WPA・GPUViewでCPU・GPU・DWMのイベントを同じ時刻軸で調べる方法があります。GPUViewはADKの選択機能Windows Performance Toolkitに付属し、公式の`Log.cmd`での記録は管理者端末を必要とします。MirrorDock本体の常時管理者実行は不要です。

Windowsの`GetTcpStatistics`やPC全体のTCP送信再送数は全アプリ合算です。PC側の送信再送数だけでは、iPhone→PC映像の送信側再送を観測・除外できません。増加だけでも、増加しないことだけでも、映像のネットワーク原因を判定しません。

## 6. 保存する情報と保存しない情報

「診断結果を保存…」は、明示的に選んだファイルへ完了した診断のJSONを保存します。自動保存・自動アップロードはしません。

保存対象は固定した項目だけです。

- 設定上限30／60、診断時間、完了理由、音声ON/OFF、ウィンドウ制御方式、Present詳細ON/OFF。
- 取得できた段階別FPS・sink drop・間隔のP95／最大、サンプル数、reset有無、許可したdecoder識別子。
- 概ね1秒ごとの数値の時系列を直近最大64件。診断開始からの相対経過時間、エンジンの計測経過時間・区間、段階別カウンター・区間指標。
- 選択式の音ズレ・カクつき・体感遅延の手動観察、計測値の定義。取れない項目は「計測不可」。

映像・音声・暗号鍵・端末名・アドレス・実行パス・環境変数一覧・生のUxPlay/GStreamer出力は保存しません。手動観察も自由入力ではなく固定した選択値です。状態ログの`MirrorDock.log`は従来どおり時刻と状態だけで、性能診断JSONとは別です。

旧エンジンや未対応の観測地点では、60秒の診断枠・比較条件・手動観察を保存できますが、自動値を仮の数で埋めません。「計測不可」はゼロでも不具合の証明でもありません。

## 7. 維持した設定と、今後の改善候補

今回追加したのは30／60上限の切替、明示的な短時間計測、ウィンドウ制御の比較です。メディア同期を無効化したり、解像度・画質を下げたり、バッファを大きくする変更はしていません。

| 設定・候補 | 現在の扱い | 副作用・評価点 |
| --- | --- | --- |
| `-fps 30`／`-fps 60` | GUIで切替、次回受信開始時に適用。既定30 | 実送信FPSを強制しない。60は帯域・デコード・GPU負荷を増やし得る |
| 変更時だけのウィンドウ制御 | 通常は`ON_CHANGE`、診断中に他方式と比較可能 | Windowsの再接続・ウィンドウ再生成・全画面で回帰確認が必要 |
| decoder／converter・GPU adapter固定 | 未変更。`decodebin`等の既定を維持 | 互換性、色、回転、コピー経路が変わるため実デコーダー確認後に評価する |
| `max-lateness`緩和 | 未変更 | 古いフレームを表示する許容が増え、遅延へ影響する。無制限化を初手にしない |
| queueの拡大／縮小・leaky化 | 未変更 | 拡大は滞留・メモリー増、縮小やdropは欠落・圧縮フレームの依存関係へ影響し得る |
| `-vsync no`／QoS無効化 | 未変更 | A/V同期・ペーシング・追い付き処理が変わり、映像の遅れが蓄積し得る |
| 表示Hz／別sink | 任意の手動比較。アプリは変更しない | デスクトップや表示経路も変わる。D3D12等は現配布に含まれず単純置換できない |
| 解像度・画質低下 | 未変更 | 明確な画質低下を伴うため、実測による根拠と利用者が許容する条件が必要 |

GStreamer 1.28.8のd3d11 sinkが継承する`GstVideoSink`は`max-lateness=5ms`、`processing-deadline=15ms`、`qos=true`を初期化します。UxPlayは`sink sync=true`とNTPから変換したPTSを使用します。5msはフレーム終端時刻／duration等を加味する遅着許容値で、「生成から5msで落ちる」という意味ではありません。GStreamer時計同期とWindowsモニターの垂直同期は同じ設定ではありません。

sinkとdecoderは別々のQoS/drop経路を持ち、CPUデコード後にGPUへコピーする場合もあります。`d3d11videosink`の指定だけでGPUデコードやzero-copyを保証しません。現配布のd3d11 pluginには対応GPU向けdecoderがあり得ますが、実際の出力を観測するまで使用中と断定しません。

## 8. 開発者向けの注意と未確認事項

- 計測はMirrorDock側で追加した`MDPERF1`プロトコルです。UxPlay公式の既存API・オプションと混同しないでください。対応パッチと構築スクリプトは対応ソースZIPにも含めます。
- pipelineやカウンターのreset境界を区別し、異なる段階のカウンターを単純加算しません。1秒ごとのFPSの単純平均ではなく、比較可能なフレーム差分と経過時間を集計します。
- `-FPSdata`はiPhone側の報告で、Windowsで描画したFPSではありません。通常の計測JSONへ生のplistを保存しません。
- 現配布には`fpsdisplaysink`の`debugutilsbad`、`GST_TRACERS`用の`coretracers`を含めていません。環境変数だけで使えるとは案内しません。
- `GST_DEBUG_DUMP_DOT_DIR`だけでは、dumpを呼ばないアプリの稼働pipeline図は自動取得できません。`stats=true`もread-onlyな構造体へ設定する指定なので使用しません。
- 子プロセスは親のGStreamer環境変数を継承します。比較時は`GST_DEBUG`・`GST_TRACERS`・`GST_PLUGIN_FEATURE_RANK`等の有無を確認し、全環境を公開しません。詳細debugや外部トレースは観測負荷・情報露出が増えるため、GUI診断と混同しません。
- 引数なし`-d`の映像`latency`値もNTP推定や受信threadの待ちを含み、純粋なWi-Fi遅延ではありません。鍵・端末情報等を含み得る全文debugを通常の共有手順にはしません。
- GUI診断の60秒継続／途中停止／切断後復元、実デコーダー値、各FPS・sink drop・間隔・時系列の妥当性、診断OFF／通常診断／Present詳細ONの負荷、30／60の音ズレ・遅延・カクつきはWindows・iPhone実機で未検証です。CIの成功を実機での改善成功へ読み替えません。

## 公式資料

今回の検証記録：[アプリ1.1.0のWindows CI](https://github.com/sunnytruck/MirrorDock/actions/runs/39877318366)、[計測対応エンジンRun #9](https://github.com/sunnytruck/MirrorDock/actions/runs/39876669997)、[公開Artifactsのダウンロード・整合性検証](https://github.com/sunnytruck/MirrorDock/actions/runs/39878253667)が成功しています。CIの合成映像試験は、実機での安定60fps・低遅延・カクつき改善の証拠ではありません。

- ローカル実装：[性能診断GUI](../src/main/java/jp/mirrordock/PerformancePane.java)、[診断の集計・保存](../src/main/java/jp/mirrordock/PerformanceDiagnosticsService.java)、[計測プロトコル](../src/main/java/jp/mirrordock/PerformanceProtocol.java)、[native計測](../scripts/engine/native/md_performance.c)、[起動処理](../src/main/java/jp/mirrordock/UxPlayService.java)、[ウィンドウ制御](../src/main/java/jp/mirrordock/WindowManager.java)。
- [UxPlay：-FPSdataと-fps](https://github.com/FDH2/UxPlay/blob/f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967/README.md#L1607)
- [UxPlay：同期・遅着フレームの説明](https://github.com/FDH2/UxPlay/blob/f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967/README.md#L657)
- [UxPlay：映像pipeline](https://github.com/FDH2/UxPlay/blob/f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967/renderers/video_renderer.c#L384)・[TCP受信](https://github.com/FDH2/UxPlay/blob/f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967/lib/raop_rtp_mirror.c#L239)
- [GStreamer 1.28.8：GstVideoSink初期化](https://github.com/GStreamer/gstreamer/blob/1.28.8/subprojects/gst-plugins-base/gst-libs/gst/video/gstvideosink.c#L169)
- [GStreamer 1.28.8：BaseSink遅着判定](https://github.com/GStreamer/gstreamer/blob/1.28.8/subprojects/gstreamer/libs/gst/base/gstbasesink.c#L3095)・[stats実装](https://github.com/GStreamer/gstreamer/blob/1.28.8/subprojects/gstreamer/libs/gst/base/gstbasesink.c#L5915)
- [GStreamer 1.28.8：decoderのQoS処理](https://github.com/GStreamer/gstreamer/blob/1.28.8/subprojects/gst-plugins-base/gst-libs/gst/video/gstvideodecoder.c#L3707)・[libav追い付き処理](https://github.com/GStreamer/gstreamer/blob/1.28.8/subprojects/gst-libav/ext/libav/gstavviddec.c#L1860)
- [GStreamer 1.28.8：D3D11 decoder登録](https://github.com/GStreamer/gstreamer/blob/1.28.8/subprojects/gst-plugins-bad/sys/d3d11/plugin.cpp#L197)・[D3D11コピー経路](https://github.com/GStreamer/gstreamer/blob/1.28.8/subprojects/gst-plugins-bad/sys/d3d11/gstd3d11videosink.cpp#L1683)・[DXGI Present](https://github.com/GStreamer/gstreamer/blob/1.28.8/subprojects/gst-plugins-bad/sys/d3d11/gstd3d11window_win32.cpp#L1299)
- [GStreamer 1.28.8：queue既定値](https://github.com/GStreamer/gstreamer/blob/1.28.8/subprojects/gstreamer/plugins/elements/gstqueue.c#L135)
- [Microsoft：GPUViewの用途](https://github.com/MicrosoftDocs/windows-driver-docs/blob/staging/windows-driver-docs-pr/display/using-gpuview.md)・[DWMのFlip QueueとVSync待ち](https://github.com/MicrosoftDocs/windows-driver-docs/blob/staging/windows-driver-docs-pr/display/gpuview-flip-queue.md)
- [Microsoft：GPUView導入](https://github.com/MicrosoftDocs/windows-driver-docs/blob/staging/windows-driver-docs-pr/display/installing-gpuview.md)・[記録時の権限と時間](https://github.com/MicrosoftDocs/windows-driver-docs/blob/staging/windows-driver-docs-pr/display/generating-a-trace-for-gpuview.md)
- [Microsoft：GetTcpStatisticsの対象](https://github.com/MicrosoftDocs/sdk-api/blob/docs/sdk-api-src/content/iphlpapi/nf-iphlpapi-gettcpstatistics.md)・[送信再送カウンターの定義](https://github.com/MicrosoftDocs/sdk-api/blob/docs/sdk-api-src/content/tcpmib/ns-tcpmib-mib_tcpstats_lh.md)

GStreamerの主要3ファイルは、初回調査で公式タグと配布に対応するMSYS2ソース内原本のSHA-256一致を確認しました。Microsoft Learnのページは調査環境から403でしたが、上記のMicrosoft公式GitHub原本を確認しています。これらは仕様の根拠であり、Windows実機の改善計測結果ではありません。
