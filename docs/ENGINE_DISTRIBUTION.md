# Windows受信エンジンの配布方針

## 実現可能性と採用する構成

調査したUxPlay公式README・Releasesでは、一般ユーザー向けの公式Windowsインストーラーまたは依存DLL付きWindowsバイナリZIPを確認できませんでした。公式のWindows手順はMSYS2/UCRT64でのソースビルドです。出所・更新・依存関係を確認できない第三者の実行ファイルを、導入しやすさだけで推奨しません。

利用者にソースビルドを求めない代替として、MirrorDockのWindows CIで固定したUxPlayソースから受信エンジンを構築し、必要な依存ファイルを含む**実験用runtime ZIP**を作ります。利用者はMirrorDockのGUIでZIPを選んで導入できます。MirrorDock自体のインストーラーと受信エンジンは別の配布物です。

| 案 | 利用者の作業 | 評価 |
| --- | --- | --- |
| 公式MSYS2手順 | MSYS2導入・依存関係導入・ビルド | 既存の手動経路として残す。一般ユーザーの標準手順にはしない |
| MirrorDock CI製runtime ZIP | Artifactsを取得し、GUIでZIPを選択 | 今回の代替案。利用者のビルド作業は不要 |
| 受信エンジンをインストーラーへ完全同梱 | アプリのインストール | 技術的には検討可能。今回の実装では未実施 |
| 不明な第三者バイナリを自動取得 | 少ない | 出所とライセンス、完全性を確認できないため採用しない |

このruntimeは**UxPlay公式配布ではなく、MirrorDock側で構築する配布物**です。確認したUxPlayソースは`f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967`（1.74 Experimental）です。1.74の内部mDNSを利用し、Apple Bonjour SDK・Bonjourサービスは同梱しません。OpenSSLは3.xを使います。

Windows CIでのビルド・テスト・パッケージ作成の成功は、iPhoneからの検出・接続や映像・音声の動作成功を示しません。**Windows PCとiPhoneを使う実機検証は未実施であり、動作は保証しません。** 配布経路や構築成功の確認状況は、対象コミットのCI結果とREADME/INSTALLで確認してください。

## runtimeと対応ソースを一緒に配布する

UxPlayはGPLv3です。バイナリの再配布は禁止されていませんが、単に実行ファイルとDLLをコピーすれば条件を満たすわけではありません。今回の配布方針はGPLv3第6節dのネットワーク配布を想定し、次を同じダウンロード場所に置くことです。

- 受信に必要なファイルを含むruntime ZIP。
- そのruntimeに対応するソースZIP。UxPlayの正確なソース、各依存パッケージのソースアーカイブ、パッチ・PKGBUILD、独自の構築・配置スクリプトを含める。
- 配布ファイルと所属パッケージ、正確なバージョン、ライセンス、対応ソース、ハッシュを記録したmanifestと通知。
- 上流の著作権表示、LICENSE/COPYING/NOTICE、無保証の説明。変更した場合は変更内容も記録する。

GPLv3の「対応ソース」には、バイナリの生成・導入・実行・変更に必要なソースと、それらの作業を制御するスクリプトが含まれます。配布したDLLのバージョンやパッチを特定できない上流トップページへのリンクだけでは、この対応を説明できません。ソースの取得を利用者に強制する必要はありませんが、runtimeと同じ場所から追加費用なく取得できる案内を維持します。

CIは必要なソースや通知を取得できない場合に、配布を成功扱いにしない方針です。ビルド・パッケージ確認だけでなく、この対応関係も公開前の確認対象です。対応ソースの提供期間は配布方法に応じて異なります。今回の第6節dの方式と、物理配布時の第6節bの「3年間有効な書面による申し出」を混同しないでください。

GitHub ActionsのArtifactsには保存期間があります。Artifactsは実験用テスト配布に使い、一般公開リリースではruntimeと対応ソースの両方を維持できる配布場所へ移してください。バイナリだけを転載・ミラーし、対応ソースへの案内を失わせないでください。

## DLLごとのライセンス確認

GStreamerのcoreや多くのpluginはLGPLですが、**すべてのGStreamer関連DLLを一律にLGPLとして扱うことはできません**。MSYS2のFFmpeg recipeはGPL機能を有効にしたGPL-3.0-or-laterの構成です。gst-plugins-badのrecipeもGPL機能を有効にするため、実際に含めるpluginとその依存先を確認します。

| 対象 | 配布時に確認する内容 |
| --- | --- |
| UxPlay | GPLv3本文、著作権表示、正確な対応ソース、変更・ビルド手順 |
| GStreamer / plugins / FFmpeg | 実際に配るDLL・pluginのライセンス、ビルドオプション、依存関係と対応ソース。`--enable-nonfree`等の再配布不可構成を使わない |
| LGPLライブラリ | license/noticeと対応ソース提供、利用者によるDLL置換を妨げない配置。デバッグのための変更やリバースエンジニアリングを一律禁止しない |
| OpenSSL 3.x | Apache-2.0本文と関連通知。UxPlayが注意しているOpenSSL 3.0未満のGPL互換性問題を避ける |
| libplist・MinGW runtime・その他 | package metadataだけでなく、各パッケージが提供するlicense/noticeと例外条件。ライブラリとコマンドツールでライセンスが異なる場合にも注意 |
| Apple Bonjour | 今回は含めない。将来含める場合はAppleのSDK・再配布条件を別途確認 |

MSYS2は、パッケージのlicense metadataと、通常`<prefix>/share/licenses/<real-package-name>/`に置くライセンス本文の両方を提供します。配置の例外もあるため、対象パッケージごとに確認します。source archiveは公式リポジトリの`mingw/sources`に置かれ、一般に`{pkgbase}-{version}.src.tar.zst`という名前です。binary packageの環境付き名称と`pkgbase`を混同せず、実際に配るバージョンに対応するものを取得します。

Run #3の所有者判定失敗は、Windows版Pythonの標準出力のCRLFをBashが読み込み、パス末尾にCRが残ることで再現しました。中間ファイルを明示的にUTF-8/LFで生成し、制御文字を拒否します。[修正後のRun #4](https://github.com/sunnytruck/MirrorDock/actions/runs/39744662638)で、`bin/avcodec-63.dll`の所有者は`mingw-w64-ucrt-x86_64-ffmpeg`と確認しました。公式の9.0.2-1バイナリアーカイブでも、同DLLは通常ファイルです。

Run #4で判明した別の失敗は、`setup-msys2`がセットアップ終了時に`pacman -Scc`でキャッシュを消去することでした。収集処理は`pacman-conf CacheDir`を参照し、空の場合は導入済みと完全に同じ版だけを`pacman -Sw`で再取得します。信頼済み署名を必須とする設定と`.PKGINFO`を照合し、別版への置換やパッケージ更新はしません。MSYS2のepoch区切り`~`も版数に保持します。

公式FFmpeg 9.0.2-1バイナリパッケージには`share/licenses`の本文がないことを実物で確認しました。対応ソースの`ffmpeg-9.0.2.tar.xz`には`LICENSE.md`とGPL/LGPL本文4件が存在するため、このパッケージと`spdx:GPL-3.0-or-later`の組み合わせに限り、その5文書を原文のまま収集します。`source-provenance.json`に取得元・各SHA-256を記録します。未確認のパッケージにこの例外を適用せず、本文不足・曖昧な配置・リンクは失敗にします。

## 所有者検索の改行境界（Run #3の修正）

Run #3はUxPlayのリンク・DLL収集まで成功し、`pacman -Qqo`が`avcodec-63.dll`を見つけられず停止しました。元コードはUCRT64版Pythonの`print()`をリダイレクトして一覧を作り、Bashの`read -r`で読み取っていました。Windowsの標準テキスト出力がCRLFを生成するため、Bashの検索引数には末尾CRが残ります。

変更前の本物のスクリプトへWindows相当のstdout変換を適用すると、検索引数が`/ucrt64/bin/avcodec-63.dll`＋`0x0D`となり所有者取得に失敗することを再現しました。修正は生成側のUTF-8/LF固定ファイル出力と、受信側の制御文字拒否です。黙って末尾を削ったり、未知の所有者をFFmpegへ割り当てたりしません。実際の`pacman`が返す所有者をmanifestへ記録します。

MSYS2のFFmpeg recipeはUCRT64で`mingw-w64-ucrt-x86_64-ffmpeg`を生成します。FFmpeg 9系のlibavcodec majorは63で、MinGWの公式インストール規則は`bin/avcodec-63.dll`を通常ファイルとして配置します。今回の失敗をDLLの改名・シンボリックリンク・実際の未所有ファイルで説明する証拠はありません。修正後のCIでは実際の所有者を再確認し、取得できない場合は配布を止めます。

根拠：

- [Windows TextIOWrapperの改行変換](https://docs.python.org/3/library/io.html#io.TextIOWrapper)
- [CPythonのWindows標準出力初期化](https://github.com/python/cpython/blob/v3.14.0/Python/pylifecycle.c)
- [MSYS2 FFmpeg recipe](https://github.com/msys2/MINGW-packages/blob/master/mingw-w64-ffmpeg/PKGBUILD)
- [FFmpeg libavcodec major 63](https://github.com/FFmpeg/FFmpeg/blob/n9.0.2/libavcodec/version_major.h)
- [FFmpeg MinGW DLL名・配置規則](https://github.com/FFmpeg/FFmpeg/blob/n9.0.2/configure)

## 別プロセス構成と残るリスク

MirrorDockは標準CLIとログでUxPlayを別プロセスとして起動します。GPLv3は独立したプログラムを集めたaggregateを認めるため、同じZIPやインストーラーに置くだけでJavaアプリ全体へGPLが必ず適用されるとは断定できません。一方、別プロセスなら常に無関係になるとも断定できません。独立性を保ち、密接な独自IPCやライブラリへの直接リンクに変更する場合は再評価してください。MirrorDock自身の公開ライセンスはリポジトリ所有者が決める必要があります。

完全同梱インストーラーには、対応ソース・通知を一体で公開する運用、更新時のバージョン追跡、DLL配置、署名、インストールと削除、実機での受信確認が必要です。今回のruntime ZIP導入はその前段階であり、完全同梱インストーラーを提供したとは扱いません。

また、GPL等の著作権ライセンス条件を守ることと、特許・商標・DRMなどを含むすべての法的事項は別です。UxPlay自身もPlayFairライブラリに関する法的状態の不明点をREADMEに記載しています。この文書は確認した条件と実装方針の記録であり、あらゆる配布方法・地域・コンテンツについて適法性を保証するものではありません。

## 一次資料

- [UxPlay公式README・Windows手順・packager向けOpenSSL注意事項](https://github.com/FDH2/UxPlay/tree/f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967)
- [UxPlay Releases](https://github.com/FDH2/UxPlay/releases)
- [UxPlay GPLv3本文（対応ソース・aggregate・第6節）](https://github.com/FDH2/UxPlay/blob/f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967/LICENSE)
- [GStreamerのライセンス説明](https://github.com/GStreamer/gstreamer/blob/main/subprojects/gst-docs/markdown/application-development/appendix/licensing.md)
- [FFmpegのライセンス・ビルド構成の説明](https://github.com/FFmpeg/FFmpeg/blob/master/LICENSE.md)
- [MSYS2 FFmpeg recipe](https://github.com/msys2/MINGW-packages/blob/master/mingw-w64-ffmpeg/PKGBUILD)
- [MSYS2 gst-plugins-bad recipe](https://github.com/msys2/MINGW-packages/blob/master/mingw-w64-gst-plugins-bad/PKGBUILD)
- [MSYS2のライセンスmetadata・本文配置](https://github.com/msys2/msys2.github.io/blob/main/web/dev/package-licensing.md)
- [MSYS2のmatching source packagesと公式ミラー](https://github.com/msys2/msys2.github.io/blob/main/web/dev/mirrors.md)
- [MSYS2 source archive名の実装](https://github.com/msys2/msys2-autobuild/blob/ad916dd13f9167df613347630de2a6a8f481e271/msys2_autobuild/queue.py#L102)
- [MSYS2 source archive配布先の実装](https://github.com/msys2/msys2-autobuild/blob/ad916dd13f9167df613347630de2a6a8f481e271/msys2_autobuild/cmd_fetch_assets.py#L19)
- [OpenSSL Apache-2.0本文](https://github.com/openssl/openssl/blob/master/LICENSE.txt)
