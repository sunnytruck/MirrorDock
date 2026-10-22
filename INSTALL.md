# インストール・診断

## 公式情報の確認

確認元：<https://github.com/FDH2/UxPlay> の README / uxplay.cpp。
確認した HEAD：`f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967`（1.74 Experimental）。
参照固定版：<https://github.com/FDH2/UxPlay/tree/f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967>

公式は Windows 10/11 x64 で MSYS2 MinGW ビルドに対応し、UCRT64 を推奨しています。
1.74 は実験版です。1.74 以降の既定 mDNS 実装は Bonjour 不要です。旧版および `USE_DNS_SD=1` での構築では Apple Bonjour SDK / Bonjour サービスが必要です。
本プロジェクトで Windows 上の構築・受信までは検証していません。公式のサポート記述と、このプロジェクトでの実機確認は区別してください。

使用する確認済みオプション：`-n <name>`、`-nh`、`-d 1`、`-as autoaudiosink` / `-as 0`、Windows の `-vs d3d11videosink`。
`-nh` はホスト名の付加を抑制します。`-d 1` は状態検出に必要なデバッグログを有効化しパケットデータ出力を抑制します。
ログには識別情報が含まれ得るため、アプリは生出力を表示・保存しません。既知の状態と診断のみ取り出します。
`UXPLAYRC` 環境変数による設定ファイル指定も公式ソースで確認しています。

## Windows の UxPlay 準備（利用者が一度実施）

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

- **実行ファイル未設定 / 不存在**：設定から信頼できる `uxplay.exe` を選択。相対パスや `.cmd` / `.bat` は受け付けません。
- **起動直後に終了 / DLL 不足**：同じ UCRT64 ターミナルで実行し、必要パッケージと実行ファイルの配置を確認。DLL 初期化失敗では Windows の終了コードだけが得られる場合があります。
- **一覧に現れない**：同一 LAN、プライベートネットワーク、ルーターのマルチキャスト/mDNS（UDP 5353）とAP隔離を確認。Bonjour方式ではサービスの稼働も確認。
- **Firewall**：Windows セキュリティ → ファイアウォールとネットワーク保護 → アプリの許可で、信頼できる UxPlay をプライベートネットワークに限定して許可します。必要性を確認した利用者自身が操作してください。アプリは変更しません。
- **接続後に映像が出ない**：GStreamer good/bad/libav、GPU ドライバー、d3d11videosink を確認。DRM保護されたコンテンツは UxPlay の制約により表示できないことがあります。
- **音声だけ失敗**：Windows の既定出力デバイスと GStreamer audio plugin を確認。音声を OFF にして再開できます。映像継続は実機で要検証です。
- **切断**：アプリが待機状態へ戻るか確認し、iPhone で選び直します。復帰しなければ停止→受信開始。
- **原因不明**：アプリは「原因を特定できません」と表示します。切り分けのため手動で UxPlay を実行する場合、出力には端末名・アドレス・鍵などが含まれ得るため、生ログを公開しないでください。

## クラウド開発環境

Linux のため Windows ネイティブ受信・iPhone LAN 接続・Windows インストーラーは検証できません。
`/workspace/tools/cloud-gradle build installDist` でビルドできます。これは環境専用の JDK / プロキシ / 信頼ストアを使う補助コマンドです。
仮想画面での起動確認には Xvfb と `-Dmirrordock.smoke=true` を使用しました。スモークモードは UxPlay 未設定時の GUI エラーを確認して自動終了するため、空の専用ユーザーディレクトリでのみ実行してください。
