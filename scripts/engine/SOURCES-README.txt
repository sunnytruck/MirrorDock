MirrorDock Engine — 対応ソース

このソースZIPは、同じGitHub Actions runのMirrorDock-Engine-Windows.zip
に対応します。runtimeを再配布する場合、正確に対応するソースと取得案内を
保持してください。別runのruntimeと組み合わせないでください。

計測対応版は、この変更を含むコミットの「Experimental Windows engine」
workflowが成功した場合に、そのrunの
「MirrorDock-Engine-Windows-and-Sources」artifactから取得します。
過去の成功runには計測パッチが含まれない場合があります。今回のCI成功と
実機動作は、各確認を行うまで未確認です。

上流と変更箇所

UxPlay-f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967.tar.gzは変更していない
上流固定コミットのtar原本です。build-scripts/にはMirrorDockの完全な
構築・パッケージ作成workflowを収録します。

MirrorDockが追加する任意の性能診断用Cモジュールとパッチスクリプトは
GPL-3.0-or-laterで提供します。上流のライセンス・著作権表示は保持します。
追加モジュールと適用スクリプトは次の場所にあります。

  build-scripts/engine/native/md_performance.c
  build-scripts/engine/native/md_performance.h
  build-scripts/engine/apply_metrics_patch.py

patched-source/には完全な変更後の次のファイルを収録します。

  renderers/md_performance.c
  renderers/md_performance.h
  renderers/video_renderer.c
  renderers/CMakeLists.txt
  uxplay.cpp
  MIRRORDOCK_PERFORMANCE_PATCH.json

MIRRORDOCK_PERFORMANCE_PATCH.jsonは上流コミット、変更元・変更後ファイルと
パッチスクリプトのハッシュを記録します。runtimeにも同じmanifestを
share/mirrordock/MIRRORDOCK_PERFORMANCE_PATCH.jsonとして収録します。
MDPERF1はMirrorDockの任意の性能計測プロトコルであり、AirPlayの認証・
暗号化を独自に置き換えるものではありません。

再構成手順

以下のコマンドは、このソースZIPを展開したディレクトリのMSYS2 UCRT64
ターミナルで実行します。ビルドにはCMake/Ninja/GCCと、このrunのworkflowに
記録したGStreamer・OpenSSL等の開発依存関係が別途必要です。各依存関係の
ソースとビルドrecipeもこのZIPに含めますが、コンパイラーやMSYS2本体を
含む完成済みの開発環境をこのZIPだけで提供するわけではありません。

A. ZIP内の原本と完全変更後ファイルから再構成（ソース取得の通信は不要）

  tar -xzf UxPlay-f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967.tar.gz
  cp -R patched-source/. UxPlay/
  cmake -S UxPlay -B UxPlay/build -G Ninja -DCMAKE_BUILD_TYPE=Release -DNO_X11_DEPS=ON -DUSE_DNS_SD=OFF
  cmake --build UxPlay/build --parallel 2

B. 上流の正確なGit checkoutにパッチスクリプトを適用する場合

AとBはどちらかを選びます。BはソースZIPの展開先にUxPlayディレクトリが
存在しない状態で行います。tar原本には.gitがないため、Git checkoutを
検証するスクリプトを直接適用できません。

  git clone https://github.com/FDH2/UxPlay.git UxPlay
  git -C UxPlay checkout --detach f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967
  python build-scripts/engine/apply_metrics_patch.py --source UxPlay
  cmake -S UxPlay -B UxPlay/build -G Ninja -DCMAKE_BUILD_TYPE=Release -DNO_X11_DEPS=ON -DUSE_DNS_SD=OFF
  cmake --build UxPlay/build --parallel 2

スクリプトは正確な上流revisionと未変更の入力を検証し、2回の適用を拒否
します。上記は変更済みUxPlayのビルド手順です。DLLの収集・通知・対応
ソース確認・runtime ZIP作成の全工程はbuild-scripts/engine.ymlと
build-scripts/engine/の元のスクリプトで確認してください。

MSYS2の対応ソースと通知

packages.tsvは各再配布MSYS2パッケージの正確な版、pkgbase、対応する公式
source archiveを記録します。file-packages.tsvは配布ファイルとパッケージの
対応、binary-metadata/は根拠となる.PKGINFO等を収録します。
msys2/のsource archiveは https://repo.msys2.org/mingw/sources/ から、通常の
TLS検証を有効にして取得します。SHA256をfiles.sha256に記録しますが、
source archiveの独立した配布元の署名を確認したという意味ではありません。
pacmanによるバイナリパッケージの署名検証は有効のままです。
各source archiveは原本のPKGBUILD、patchとupstream source inputsを保持します。
それぞれのビルドrecipeとライセンスに従ってください。

setup-msys2は設定後にpackage cacheを消去します。collectorは署名検証必須の
pacman経路で正確な導入済みバイナリ版だけを取得し、.PKGINFOを再検証します。
パッケージの新規導入や更新はしません。

一部のMSYS2バイナリパッケージはshare/licensesを提供しません。
build-scripts/engine/audited-source-notices.jsonは監査済みの
package/version/license/source archive SHA256/original archiveまたはGit commit/
各notice SHA256を固定します。PKGBUILD、Git hooks、archive内コードを実行せず、
原文通知をコピーします。FFmpegは正確な原本版と必須の5つのライセンス文書も
検証します。復元した通知のsource-provenance.jsonに原本と本文のハッシュを
記録します。未知または変更された組合せは失敗し、通知なしpackage全般を
例外扱いにはしません。

失敗するパッケージ作成を通すために、ソース・通知・検証を省略してはいけません。
正確なsource archiveを取得できない場合はアップロードせず失敗させます。

CIは実行ファイルの読込・helpとGStreamer pluginの有無等を確認します。
物理iPhone/WindowsでのAirPlay受信と性能計測は未確認です。

MSYS2公式autobuild実装はmakepkg-mingw --allsourceでremote source inputsを
含むarchiveを作成し、.src.tarをアップロードします。source build/upload
patternsはsource用.sig artifactを宣言していません。
https://github.com/msys2/msys2-autobuild/blob/main/msys2_autobuild/build.py
https://github.com/msys2/msys2-autobuild/blob/main/msys2_autobuild/queue.py

source archiveのTLS検証+記録したSHA256と、pacmanのバイナリ署名検証は
異なる保証です。どちらかで他方を代替したとは扱わないでください。
