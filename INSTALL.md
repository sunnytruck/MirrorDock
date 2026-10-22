# インストール・診断

## 公式情報の確認

確認元：<https://github.com/FDH2/UxPlay> の README / uxplay.cpp。
確認した HEAD：`f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967`（1.74 Experimental）。
参照固定版：<https://github.com/FDH2/UxPlay/tree/f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967>

公式は Windows 10/11 x64 で MSYS2 MinGW ビルドに対応し、UCRT64 を推奨しています。
1.74 は実験版です。1.74 以降の既定 mDNS 実装は Bonjour 不要です。旧版および `USE_DNS_SD=1` での構築では Apple Bonjour SDK / Bonjour サービスが必要です。
Windows CIでの構築・配布検証と、開発環境での実機計測、利用者からの受信成功報告は区別してください。

使用する確認済みオプション：`-n <name>`、`-nh`、`-d 1`、`-as autoaudiosink` / `-as 0`、Windows の `-vs d3d11videosink`。
`-nh` はホスト名の付加を抑制します。`-d 1` は状態検出に必要なデバッグログを有効化しパケットデータ出力を抑制します。
ログには識別情報が含まれ得るため、アプリは生出力を表示・保存しません。既知の状態と診断のみ取り出します。
`UXPLAYRC` 環境変数による設定ファイル指定も公式ソースで確認しています。

## 一般ユーザー向け：コマンド操作なしの導入

**利用者からWindows実機で映像・音声を受信できた報告がありますが、開発環境での実機計測は未実施です。実験版であり、動作を保証しません。** CIのビルド・配布検証と、実機の受信・性能検証は別です。

1. [ActionsのWindows package](https://github.com/sunnytruck/MirrorDock/actions/workflows/windows.yml) で、今回の変更を含む成功した実行を開きます。Artifactsの`MirrorDock-Windows`をダウンロード・展開し、EXEをインストールします。Javaは同梱されています。
2. [成功したRun #8のエンジンArtifacts](https://github.com/sunnytruck/MirrorDock/actions/runs/39751872066/artifacts/12414019562) をダウンロード・展開します。後続版は[Actionsのエンジン配布](https://github.com/sunnytruck/MirrorDock/actions/workflows/engine.yml)で成功した実行から取得してください。ソースコード用ZIPや上流のSource code ZIPではありません。
3. MirrorDockの「受信エンジンの導入・診断」→「エンジンZIPを導入」で、内側の`MirrorDock-Engine-Windows.zip`を選択します。GUIが検査・展開してパスを保存します。MSYS2やコンパイラーは不要です。
4. 「受信エンジンを診断」を押します。ヘルプ起動、Windowsの既知のDLLエラー、標準ファイアウォールのルールを確認します。成功してもiPhone接続を保証しません。
5. 「受信開始」。同じ家庭内LANのiPhoneで「画面ミラーリング」→「MirrorDock」を選択します。

対応ソースZIPとライセンス通知も同じArtifactsにあります。ソースは実行不要ですが、再配布時はランタイムと一緒に提供してください。
配布元はこのリポジトリのCIに限定してください。ZIP内チェックサムは破損検査であり、電子署名による出所確認ではありません。Windowsインストーラーやエンジンのコード署名は未設定です。保護機能を無効にする案内は行いません。

エンジンは`%LOCALAPPDATA%\MirrorDock\engine\versions\<ID>\`に配置します。ユーザー単位で導入し管理者権限は不要です。以前のエンジンと設定は上書きしません。不要な古いバージョンはアプリ停止後に利用者が削除できます。ZIPを導入するだけではエンジンを実行せず、「診断」または「受信開始」で選択した実行ファイルを起動します。

既に信頼できるUxPlayを導入済みなら「インストール済みを探す」または従来の「設定」→実行ファイル選択が使えます。受信中は導入と診断を無効にします。

## 調査結果とインストーラー一本化の判断

2026-10-22時点で確認した公式Releases（v1.72.1〜v1.73.7）の配布物はソースやLinux向け定義で、Windowsの実行バイナリは見つかりませんでした。公式CIにはWindowsビルドがあるものの、Windows用Artifactsを公開していません。MSYS2の公式MINGW-packagesでもUxPlayレシピを確認できませんでした。未確認の個人配布版は推奨しません。

技術的にはエンジンと依存物を一緒に配布できます。ただしGStreamerのプラグインやFFmpegにはGPL対象が含まれ、DLLをコピーするだけではライセンス義務を満たせません。正確な対応ソース・ビルドスクリプト・通知の提供と、Windows実機での確認が必要です。

今回は開発側CIがエンジンをビルドし、GUIからZIPを導入する最小手順にしました。アプリのEXEインストーラーにはまだエンジンを同梱していません。配布チェックと実機検証の後、同じ配置をインストーラーへ統合する段階です。詳細な根拠・義務・リスクは[ENGINE_DISTRIBUTION.md](docs/ENGINE_DISTRIBUTION.md)を参照してください。

## 開発者向け：公式手順による手動ビルド（通常利用では不要）

1. <https://www.msys2.org/> から MSYS2 を導入します。
2. MSYS2 ターミナルで `pacman -Syu` を実行し、指示があればターミナルを再起動して更新を完了します。
3. **UCRT64** ターミナルで次を実行します。

```sh
pacman -S --needed git mingw-w64-ucrt-x86_64-cmake mingw-w64-ucrt-x86_64-gcc mingw-w64-ucrt-x86_64-ninja mingw-w64-ucrt-x86_64-openssl mingw-w64-ucrt-x86_64-libplist mingw-w64-ucrt-x86_64-gstreamer mingw-w64-ucrt-x86_64-gst-plugins-base mingw-w64-ucrt-x86_64-gst-plugins-good mingw-w64-ucrt-x86_64-gst-plugins-bad mingw-w64-ucrt-x86_64-gst-libav
git clone https://github.com/FDH2/UxPlay.git
cd UxPlay
git checkout f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967
cmake -S . -B build -G Ninja
cmake --build build --parallel 2
cmake --install build --prefix /ucrt64
/ucrt64/bin/uxplay.exe -n MirrorDock -nh -vs d3d11videosink
```

上記は公式手順に基づく未実機検証の構築例です。実験版の利用方針に応じて安定版を選ぶ場合は、公式の該当版の依存関係とログ互換性を再確認してください。
Bonjour方式を選ぶ場合は、Apple公式の Bonjour SDK for Windows を導入し `cmake` に `-DUSE_DNS_SD=1` を追加してください。SDK が標準パス以外なら `BONJOUR_SDK_HOME` を設定します。

MirrorDock で `C:\msys64\ucrt64\bin\uxplay.exe` を指定します。DLL を単独で別の場所から寄せ集めず、同じ UCRT64 環境で管理してください。
GStreamer がプラグインを検出できない場合は、この UCRT64 の `bin` をユーザーの PATH に追加してアプリを再起動し、異なる MSVC / MINGW64 の DLL と混在していないか確認します。

## Windows 用アプリのビルド

JDK 21 x64 を導入し、`JAVA_HOME` をそのディレクトリに設定します。`java -version` と `javac -version` の両方が必要です。

```powershell
.\gradlew.bat build installDist
.\gradlew.bat run
```

インストーラーには WiX Toolset 3.x が別途必要です。`candle.exe` / `light.exe` を PATH に追加してから次を実行します。

```powershell
.\gradlew.bat packageWindows
```

出力：`build\installer\MirrorDock-1.0.0.exe`。ランタイムは jpackage が生成します。実際のファイル名は jpackage の出力で確認してください。
署名は未設定です。公開配布にはコード署名と第三者ライセンス・通知・ソース提供義務の確認が必要です。
UxPlay とその依存 DLL はインストーラーに含めません。

## 接続できない場合

接続でき、映像だけがときどき引っかかる場合は[性能調査・計測手順](docs/PERFORMANCE_DIAGNOSTICS.md)を参照してください。設定を決め打ちで変えず、同じ条件でFPS・QoS・デコード・描画・通信を切り分けます。現行の「受信エンジンを診断」と「状態ログ」は、稼働中の性能計測には対応していません。

- **実行ファイル未設定 / 不存在**：「エンジンZIPを導入」または設定から信頼できる `uxplay.exe` を選択。相対パスや `.cmd` / `.bat` は受け付けません。
- **起動直後に終了 / DLL 不足**：「受信エンジンを診断」で確認します。`0xC0000135`/Win32 126はDLL不足、`0xC000007B`/193は形式不一致、`0xC0000142`/1114は初期化失敗として案内します。ZIPを再取得して一式を再導入してください。手動MSYS2版は同一UCRT64環境を修復します。DLL単体サイトから追加しないでください。
- **一覧に現れない**：同一 LAN、プライベートネットワーク、ルーターのマルチキャスト/mDNS（UDP 5353）とAP隔離を確認。Bonjour方式ではサービスの稼働も確認。
- **Firewall**：診断ボタンは現在のプロファイルとUxPlayに一致する受信ルールを読み取ります。ブロックルールがあっても通信条件が一致するかは未検証です。許可ルールがない場合も遮断とは断定できません。読み取れない場合は不明と表示します。「Windowsのファイアウォール画面を開く」から Windows セキュリティ → ファイアウォールとネットワーク保護 → アプリの許可で、信頼できる UxPlay をプライベートネットワークに限定して許可します。必要性を確認した利用者自身が操作してください。アプリは変更しません。
- **接続後に映像が出ない**：GStreamer good/bad/libav、GPU ドライバー、d3d11videosink を確認。DRM保護されたコンテンツは UxPlay の制約により表示できないことがあります。
- **音声だけ失敗**：Windows の既定出力デバイスと GStreamer audio plugin を確認。音声を OFF にして再開できます。映像継続は実機で要検証です。
- **切断**：アプリが待機状態へ戻るか確認し、iPhone で選び直します。復帰しなければ停止→受信開始。
- **原因不明**：アプリは「原因を特定できません」と表示します。切り分けのため手動で UxPlay を実行する場合、出力には端末名・アドレス・鍵などが含まれ得るため、生ログを公開しないでください。

## クラウド開発環境

Linux のため Windows ネイティブ受信・iPhone LAN 接続・Windows インストーラーは検証できません。
`/workspace/tools/cloud-gradle build installDist` でビルドできます。これは環境専用の JDK / プロキシ / 信頼ストアを使う補助コマンドです。
仮想画面での起動確認には Xvfb と `-Dmirrordock.smoke=true` を使用しました。スモークモードは UxPlay 未設定時の GUI エラーを確認して自動終了するため、空の専用ユーザーディレクトリでのみ実行してください。
