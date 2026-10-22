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
このエンジン配布は実験段階で、対応するCIが成功してArtifactsが生成されるまでは取得できません。
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
- シェルを経由せず引数配列で起動。ファイアウォールの自動変更や自動ダウンロードなし。利用者が押した配布ページのボタンだけがブラウザーを開きます。
- エンジンZIPのGUI導入、パストラバーサル・重複パス・展開サイズ・SHA-256の検査。
- 導入済みUxPlayの探索、ヘルプ起動診断、WindowsのDLL不足/形式不一致/初期化失敗の具体策。
- 読み取り専用のファイアウォール診断。ブロック/許可ルール・パブリック接続を案内し、実際の遮断とは断定しません。

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
`.github/workflows/windows.yml` の初版はWindows CI成功を利用者の画面で確認しました。今回の改訂後のCI結果は別途確認が必要です。
`.github/workflows/engine.yml` はUxPlayの固定SHAをWindowsでビルドし、DLL・プラグインと対応ソースを同じArtifactsにまとめます。CI内でバイナリパッケージの署名確認・対応ソース取得・DLL依存解決・プラグイン検査が失敗した場合は成果物を配布しません。初回のエンジンCI・実機受信は未確認です。
同梱の実現可能性とライセンス義務は [docs/ENGINE_DISTRIBUTION.md](docs/ENGINE_DISTRIBUTION.md) を参照してください。

## 検証と未確認事項

クラウド Linux x64 で `build`、単体テスト36件（失敗0・スキップ0）、`installDist` を実行しました。
仮想画面で GUI 起動と UxPlay 未設定時のエラー表示を検証しました。テストのプロセス起動・停止には模擬エンジンを使用しています。実際の UxPlay 受信成功を示すものではありません。

配布処理のPythonテストはLinuxで39件成功し、Windows専用のパス検証1件は未実行です。Windowsでの全件結果はCIで別途確認が必要です。再実行は `python -m unittest discover -s scripts/engine -p "test_*.py"` です。

テスト対象：従来の設定・プロセス・画面位置補正に加え、ZIPの導入/破損/危険なパス/重複/形式、DLLエラー分類、診断タイムアウト時の停止、ファイアウォール結果の慎重な解釈、ポータブル環境設定。診断プロセスも模擬エンジンを使い、Windows固有の実行自体は未確認です。

**Windows PC と iPhone はこのクラウドにないため、次は未確認です。リリース前に必ず実施してください。**

- [ ] 改訂版のWindows CI、エンジンCI、GUI起動、インストール・アンインストール（初版のアプリCI成功は確認済み）。
- [ ] CIエンジンZIPの取得・GUI導入・DLL/プラグインの検査、Windows診断の実行。
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
