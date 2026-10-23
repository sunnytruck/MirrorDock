# MirrorDock

Windows 11 x64 向けの日本語 JavaFX AirPlay 受信ランチャーです。iPhone 専用アプリは不要です。
映像・音声・AirPlay 認証と暗号化は外部の **UxPlay** が処理します。MirrorDock は独自の AirPlay 実装を持ちません。

## 起動

開発には **JDK 21 x64（JREのみは不可）** が必要です。JavaFX 21.0.7 / Gradle Wrapper 8.14.3 を使用します。

```powershell
.\gradlew.bat clean build
.\gradlew.bat run
```

配布用インストーラーがある場合はインストール後、スタートメニューの MirrorDock を開きます。
初回は「受信エンジンの導入・診断」から、MirrorDock側のCIで作るエンジンZIPを導入します。利用者のMSYS2導入・ソースビルドは不要です。
[INSTALL.md](INSTALL.md) にダウンロード・GUI操作の手順と、既にUxPlayがある場合の設定方法を記載しています。
エンジンはMirrorDock側CIで作成した実験版です。対応ソースも同じArtifactsに含めて配布します。
信頼できる UxPlay のみ指定してください。受信は管理者権限なしで実行します。

## iPhone との接続

1. PC と iPhone を同一 LAN に接続します（PC 有線 / iPhone Wi-Fi も可）。ゲストネットワークや AP 隔離は避けます。
2. MirrorDock の「受信開始」を押します。「接続待機中」はサービス広告開始ログの確認を示し、iPhone からの発見保証ではありません。
3. iPhone のコントロールセンター → 画面ミラーリング → **MirrorDock** を選択します。
4. 映像は UxPlay の別ウィンドウに表示されます。縦横回転、アスペクト比と黒帯、ウィンドウ拡縮は GStreamer が処理します。Windows の映像全画面切替は **Alt + Enter** です。
5. iPhone でミラーリングを停止すると待機状態へ戻ります。再度 MirrorDock を選択して再接続します。

受信機名・音声・フレームレート上限の設定は次の受信開始時に反映します。音声だけで問題が起きる場合は停止 → 音声 OFF → 受信開始で映像のみを試してください。
映像ウィンドウを閉じた場合の挙動は UxPlay の版に依存します。「停止」はエンジンそのものを終了します。

## 30／60fpsと性能診断

「設定」のフレームレート上限で30／60を選択し、次回の「受信開始」で反映します。既定は30で、60ではUxPlayの`-fps 60`を使います。これはiPhoneへ案内する上限で、実測FPSや60fpsの受信保証とは別です。

iPhoneとの接続後、「性能診断・30／60fpsの比較」→「性能診断を開始」で約60秒の診断を行い、途中停止・JSON保存もできます。取得できた受信AU・デコード出力・sink処理のFPS、sink drop、処理間隔、実デコーダーを表示します。物理画面の表示FPS、TCP再送、数値の音ズレや端末間遅延などは「計測不可」とし、音ズレ・カクつき・体感遅延は選択式の手動観察を別に記録します。保存JSONには概ね1秒ごとの数値の時系列を最大64件含みます。

「Present詳細計測」は既定OFFです。ON時だけDXGI呼出前の通知回数・間隔を計測し、実際の表示回数・Present成功数とは区別します。描画経路の最適化条件が一時的に変わるため、まず通常診断で比較し、詳細ONは別条件にしてください。終了時に元の設定へ戻します。

**自動値には`MDPERF1`対応の新しいWindowsエンジンが必要です。以前のRun #8のエンジンでは自動値は計測不可です。** 今回の変更を含む成功したエンジンCIからZIPを取得し、GUIで再導入してください。映像・音声・鍵・端末情報・生ログは診断JSONに保存しません。

診断中だけウィンドウ制御を「状態変更時のみ」「毎秒再適用（従来方式）」「停止」で比較でき、終了時に元の方式へ戻します。通常は不要な`SetWindowPos`再適用を抑える方式です。[計測値の定義・限界・比較手順](docs/PERFORMANCE_DIAGNOSTICS.md)を確認してください。解像度・同期・バッファの既定値は維持し、Windows実機での安定60fps・低遅延・改善効果は未検証です。

## 実装

- 日本語ダーク UI、起動・停止・再試行、接続状態と正規化したエラー表示。
- 非同期のプロセス出力読取、サービス広告待ち（20秒の監視）、二重起動防止、終了コード検出、終了時の子プロセス停止。
- 受信機名、音声、30／60fps上限、自動開始、ログ、操作ウィンドウの位置・サイズ・最前面設定を保存。旧設定・不正なFPS値は30へ復帰。
- モニターが外れた場合、次回起動時に位置とサイズを利用可能な画面内に補正。
- Windows API/JNA による、エンジン PID に属する映像ウィンドウの最前面制御。通常は状態変化時だけ適用し、性能診断中に従来の毎秒再適用／停止と比較。
- 明示開始・最大約60秒のGUI性能診断、手動停止、固定項目だけのJSON保存。診断OFF時はnativeのフレームprobe・Present通知・サンプリングtimerを停止。
- 設定は `%LOCALAPPDATA%\MirrorDock\settings.properties`。Windows 以外は `~/.mirrordock`。
- ログは任意で同フォルダーの `MirrorDock.log` に状態種別と時刻だけを保存。1 MB で1世代にローテーション。
- 録画オプションなし。`UXPLAYRC` を空の専用設定に指定し、既存の `.uxplayrc` による録画や任意オプション追加を防止。
- シェルを経由せず引数配列で起動。ファイアウォールの自動変更や自動ダウンロードなし。利用者が押した配布ページのボタンだけがブラウザーを開きます。
- エンジンZIPのGUI導入、パストラバーサル・重複パス・展開サイズ・SHA-256の検査。
- 導入済みUxPlayの探索、ヘルプ起動診断、WindowsのDLL不足/形式不一致/初期化失敗の具体策。
- 読み取り専用のファイアウォール診断。ブロック/許可ルール・パブリック接続を案内し、実際の遮断とは断定しません。

`MainApplication` / `MainController` / `AirPlayService` / `UxPlayService` / `WindowManager` / `SettingsService` / `LogService` と、性能診断の`PerformancePane` / `PerformanceDiagnosticsService` / `PerformanceProtocol`に責務を分離しています。

## ビルドと配布

```powershell
.\gradlew.bat build installDist
.\build\install\MirrorDock\bin\MirrorDock.bat
.\gradlew.bat packageWindows
```

`packageWindows` は **Windows x64 上の JDK 21 + WiX 3.x（candle.exe / light.exe が PATH にあること）** が必要です。
`build/installer/` に EXE インストーラーを生成し、jpackage が Java ランタイムを同梱します。UxPlay / Bonjour / MSYS2 / GStreamer DLL は同梱しません。
`build/distributions/` の ZIP は JDK/JRE 21 の別途導入が必要です。Linux で生成した ZIP に Windows 用 JavaFX は入りません。Windows 配布物は Windows 上で再ビルドしてください。
1.1.0のWindowsビルド・テスト・インストーラー生成は[Windows CI（65242e3）](https://github.com/sunnytruck/MirrorDock/actions/runs/39877318366)で成功しています。[アプリのArtifacts](https://github.com/sunnytruck/MirrorDock/actions/runs/39877318366/artifacts/12469485697)を展開してEXEを実行します。旧版から更新する場合は、先にMirrorDockと受信エンジンを停止してください。既存1.0.0からのインストーラー更新と設定復元は実機での確認対象です。
`.github/workflows/engine.yml` はUxPlayの固定SHAへMirrorDockの計測パッチを適用してWindowsでビルドし、DLL・プラグインと対応ソースを同じArtifactsにまとめます。署名確認・対応ソース取得・DLL依存解決・プラグイン／計測拡張の検査が失敗した場合は成果物を配布しません。計測対応の[Engine Run #9（bff397d）](https://github.com/sunnytruck/MirrorDock/actions/runs/39876669997)が成功し、[エンジンと対応ソースのArtifacts](https://github.com/sunnytruck/MirrorDock/actions/runs/39876669997/artifacts/12469436892)を公開しています。[別CIによるダウンロード検証](https://github.com/sunnytruck/MirrorDock/actions/runs/39878253667)も成功しました。
同梱の実現可能性とライセンス義務は [docs/ENGINE_DISTRIBUTION.md](docs/ENGINE_DISTRIBUTION.md) を参照してください。

## 検証と未確認事項

今回のクラウド Linux x64 では `build`・`installDist`、Java単体テスト69件（失敗0・スキップ0）、Pythonテスト59件（Windows専用1件スキップ）、合成映像によるnative計測テストとGUI smokeが成功しました。Windowsのビルド・テストは対象コミットのCI結果も確認してください。合成映像の試験はWindows GPUやiPhoneの実機性能の確認ではありません。
仮想画面で GUI 起動と UxPlay 未設定時のエラー表示を検証しました。テストのプロセス起動・停止には模擬エンジンを使用しています。実際の UxPlay 受信成功を示すものではありません。

[Windows Engine Run #9](https://github.com/sunnytruck/MirrorDock/actions/runs/39876669997)ではPythonテスト59件が成功・スキップなしです。合成raw／H264映像によるnative診断、UxPlayビルド、DLL収集、対応ソース・ライセンス収集、隔離PATHでのhelp／plugin検査、502ファイルのエンジンZIP検証、Artifacts公開が成功しました。別CIで公開済みZIPをダウンロードし、runtime／対応ソースのSHA256・ZIP CRC・パッケージ対応表・変更後ソースmanifestの一致も確認しました。再実行は `python -m unittest discover -s scripts/engine -p "test_*.py"` です。Bash・Git・bsdtar（Linuxではlibarchive-tools）が必要です。エンジンCIではMSYS2がこれらを導入します。

テスト対象：従来の設定・プロセス・画面位置補正に加え、ZIPの導入/破損/危険なパス/重複/形式、DLLエラー分類、診断タイムアウト時の停止、ファイアウォール結果の慎重な解釈、ポータブル環境設定。Javaのプロセス診断は模擬エンジンを使います。実機のD3D11描画、音声デバイス、ファイアウォール経由の接続はCIの検証範囲外です。

2026-10-22に利用者からWindows実機での映像・音声受信成功と、断続的な映像の引っかかりの報告がありました。申告環境はCore i5-14400F・RTX 4060 Ti・280Hzです。FPS・欠落数・原因・改善効果はまだ実測していません。

**開発環境でのWindows PC・iPhoneを使った実機計測は未実施です。次は利用者報告と分けて確認し、リリース前に検証してください。**

- [ ] WindowsでのGUI起動、インストール・アンインストール（通常版とエンジンのWindows CI成功は確認済み）。
- [ ] CIエンジンZIPの取得・GUI導入・DLL/プラグインの検査、Windows診断の実行。
- [ ] 実際の UxPlay 起動、DLL / GStreamer / mDNS の準備とサービス広告。
- [ ] iPhone の一覧に MirrorDock が表示され、映像と音声が出ること。
- [ ] 縦横回転、アスペクト比と黒帯、最大化・最小化・Alt+Enter 全画面。
- [ ] 切断後の再接続、LAN切断、長時間稼働、操作ウィンドウ終了時のエンジン停止。
- [ ] 音声デバイスがない場合の診断、音声 OFF での映像継続。
- [ ] 最前面の ON/OFF（操作・映像両ウィンドウ）、複数モニター・DPI・モニター取り外し後の起動。
- [ ] 30／60fpsの実受信・デコード・sink／Present指標、60秒診断・途中停止・切断後の復元、JSON保存値の妥当性。
- [ ] ウィンドウ制御3方式、診断OFF／通常診断／Present詳細ONの負荷、音ズレ・表示遅延・カクつきの比較。物理表示FPS等の「計測不可」との区別。
- [ ] Firewall 拒否、Bonjour方式でのサービス停止、欠落 DLL、映像デコーダー不足の診断。

映像ウィンドウへの JavaFX 埋め込み、映像ウィンドウ自身の位置・サイズの保存、音声障害時のシームレスな自動復旧は未実装です。
音声障害時は音声 OFF で手動再起動します。操作ウィンドウの設定保存と映像ウィンドウの設定は別です。
接続状態は UxPlay の文字列ログに依存します。確認した上流版以外ではログが変わる可能性があります。
JavaFX は module path、アプリと JNA は classpath で起動します。GUI 起動・エラー表示・終了はクラウドの仮想画面で確認しました。Windows 実機での安定性確認は必要です。

公式資料の確認内容と診断は [INSTALL.md](INSTALL.md)、ライセンスは [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) を参照してください。
