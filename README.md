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
UxPlay は別途準備してください。[INSTALL.md](INSTALL.md) に公式情報に基づく構築方法を記載しています。
初回は「設定」で `C:\msys64\ucrt64\bin\uxplay.exe` を選択して保存します。
信頼できる UxPlay のみ指定してください。受信は管理者権限なしで実行します。

## iPhone との接続

1. PC と iPhone を同一 LAN に接続します（PC 有線 / iPhone Wi-Fi も可）。ゲストネットワークや AP 隔離は避けます。
2. MirrorDock の「受信開始」を押します。「接続待機中」はサービス広告開始ログの確認を示し、iPhone からの発見保証ではありません。
3. iPhone のコントロールセンター → 画面ミラーリング → **MirrorDock** を選択します。
4. 映像は UxPlay の別ウィンドウに表示されます。縦横回転、アスペクト比と黒帯、ウィンドウ拡縮は GStreamer が処理します。Windows の映像全画面切替は **Alt + Enter** です。
5. iPhone でミラーリングを停止すると待機状態へ戻ります。再度 MirrorDock を選択して再接続します。

受信機名・音声設定は次の受信開始時に反映します。音声だけで問題が起きる場合は停止 → 音声 OFF → 受信開始で映像のみを試してください。
映像ウィンドウを閉じた場合の挙動は UxPlay の版に依存します。「停止」はエンジンそのものを終了します。

## 実装

- 日本語ダーク UI、起動・停止・再試行、接続状態と正規化したエラー表示。
- 非同期のプロセス出力読取、サービス広告待ち（20秒の監視）、二重起動防止、終了コード検出、終了時の子プロセス停止。
- 受信機名、音声、自動開始、ログ、操作ウィンドウの位置・サイズ・最前面設定を保存。
- モニターが外れた場合、次回起動時に位置とサイズを利用可能な画面内に補正。
- Windows API/JNA による、エンジン PID に属する映像ウィンドウの最前面制御（1秒間隔で再適用）。
- 設定は `%LOCALAPPDATA%\MirrorDock\settings.properties`。Windows 以外は `~/.mirrordock`。
- ログは任意で同フォルダーの `MirrorDock.log` に状態種別と時刻だけを保存。1 MB で1世代にローテーション。
- 録画オプションなし。`UXPLAYRC` を空の専用設定に指定し、既存の `.uxplayrc` による録画や任意オプション追加を防止。
- シェルを経由せず引数配列で起動。ファイアウォールの自動変更やアプリから外部サーバーへの通信なし。

`MainApplication` / `MainController` / `AirPlayService` / `UxPlayService` / `WindowManager` / `SettingsService` / `LogService` に責務を分離しています。

## ビルドと配布

```powershell
.\gradlew.bat build installDist
.\build\install\MirrorDock\bin\MirrorDock.bat
.\gradlew.bat packageWindows
```

`packageWindows` は **Windows x64 上の JDK 21 + WiX 3.x（candle.exe / light.exe が PATH にあること）** が必要です。
`build/installer/` に EXE インストーラーを生成し、jpackage が Java ランタイムを同梱します。UxPlay / Bonjour / MSYS2 / GStreamer DLL は同梱しません。
`build/distributions/` の ZIP は JDK/JRE 21 の別途導入が必要です。Linux で生成した ZIP に Windows 用 JavaFX は入りません。Windows 配布物は Windows 上で再ビルドしてください。
`.github/workflows/windows.yml` で Windows ビルド・単体テスト・インストーラー生成を実行できます（GitHub Actions の実行自体は本作業では未実施）。

## 検証と未確認事項

クラウド Linux x64 で `build`、単体テスト11件（失敗0・スキップ0）、`installDist` を実行しました。
仮想画面で GUI 起動と UxPlay 未設定時のエラー表示を検証しました。テストのプロセス起動・停止には模擬エンジンを使用しています。実際の UxPlay 受信成功を示すものではありません。

テスト対象：設定の往復・不正値・不正ファイル、未設定/不存在パス、引数安全性、プロセスの起動/停止/再起動/二重起動防止/異常終了、接続/切断ログ処理、画面位置補正。

**Windows PC と iPhone はこのクラウドにないため、次は未確認です。リリース前に必ず実施してください。**

- [ ] Windows 11 x64 で JDK ビルド、GUI 起動、インストーラー生成・インストール・アンインストール。
- [ ] 実際の UxPlay 起動、DLL / GStreamer / mDNS の準備とサービス広告。
- [ ] iPhone の一覧に MirrorDock が表示され、映像と音声が出ること。
- [ ] 縦横回転、アスペクト比と黒帯、最大化・最小化・Alt+Enter 全画面。
- [ ] 切断後の再接続、LAN切断、長時間稼働、操作ウィンドウ終了時のエンジン停止。
- [ ] 音声デバイスがない場合の診断、音声 OFF での映像継続。
- [ ] 最前面の ON/OFF（操作・映像両ウィンドウ）、複数モニター・DPI・モニター取り外し後の起動。
- [ ] Firewall 拒否、Bonjour方式でのサービス停止、欠落 DLL、映像デコーダー不足の診断。

映像ウィンドウへの JavaFX 埋め込み、映像ウィンドウ自身の位置・サイズの保存、音声障害時のシームレスな自動復旧は未実装です。
音声障害時は音声 OFF で手動再起動します。操作ウィンドウの設定保存と映像ウィンドウの設定は別です。
接続状態は UxPlay の文字列ログに依存します。確認した上流版以外ではログが変わる可能性があります。
JavaFX は module path、アプリと JNA は classpath で起動します。GUI 起動・エラー表示・終了はクラウドの仮想画面で確認しました。Windows 実機での安定性確認は必要です。

公式資料の確認内容と診断は [INSTALL.md](INSTALL.md)、ライセンスは [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) を参照してください。
