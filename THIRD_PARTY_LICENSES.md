# 第三者ソフトウェアのライセンス

MirrorDock自身の著作権・公開ライセンスはリポジトリ所有者が決定してください。本ファイルは第三者ソフトウェアの通知と、受信エンジン配布時の確認事項です。配布構成の方針・根拠は[ENGINE_DISTRIBUTION.md](docs/ENGINE_DISTRIBUTION.md)を参照してください。

| Component | Version | License / notice | 配布範囲 |
| --- | --- | --- | --- |
| OpenJFX (base, graphics, controls) | 21.0.7 | GPL v2 with Classpath Exception、additional notices / assembly exception | MirrorDock本体 |
| JNA / JNA Platform | 5.17.0 | Apache-2.0 OR LGPL-2.1-or-later（本配布ではApache-2.0を選択） | MirrorDock本体 |
| OpenJDK runtime | パッケージ生成に使ったJDK 21のvendor/version | GPL v2 with Classpath Exception、同梱legal notices。vendor依存の通知も保持 | jpackage runtime |
| Gradle | 8.14.3 | Apache-2.0 | Wrapper / build tool。アプリ実行時には不要 |
| JUnit Jupiter / Platform | 5.12.2 / 1.12.2 | EPL-2.0 | テストのみ |
| UxPlay | 固定コミットf0cd2c3 / 1.74 Experimental | GPLv3。内部のLGPL/MIT等の通知も保持 | 別配布の実験用runtime ZIP。公式配布ではない |
| GStreamer / plugins / FFmpeg | runtimeのmanifestに記録 | DLL/pluginごとにLGPL/GPL等。MSYS2 FFmpegはGPL機能を有効にした構成 | 実験用runtime ZIPの対象ファイル |
| OpenSSL | runtimeに含める3.x | Apache-2.0と関連通知 | 実験用runtime ZIPの対象ファイル |
| libplist / MinGW runtime / その他の依存ライブラリ | runtimeのmanifestに記録 | 各packageのlicense、notice、例外条件を確認 | 実験用runtime ZIPの対象ファイル |
| Apple Bonjour SDK / service | 使用しない | 将来使う場合はAppleの条件を別途確認 | 同梱しない |
| WiX Toolset | 3.x | Microsoft Reciprocal License。WiXの配布例外も確認 | 外部のパッケージ生成ツール |

`licenses/`にはOpenJFXのlicense/exceptionとJNAのlicense通知・Apache-2.0本文を保存しています。OpenJFX LICENSEには第三者通知も含まれます。jpackageが保持するJDKのruntime legal directoryは削除しないでください。

## 実験用UxPlay runtimeの配布条件

MirrorDockの通常インストーラーにはUxPlay・GStreamer DLL・Bonjourを同梱しません。CIが作るruntime ZIPをGUIから導入する経路を追加しています。ソースビルドはCIで行い、利用者には要求しません。WindowsとiPhoneの実機検証は未実施です。

runtime ZIPを提供する場合は、対応ソースZIP・manifest・ライセンス通知を同じダウンロード場所で提供する方針です。GPLv3第6節dを想定し、正確なUxPlayソース、配布DLLに対応するMSYS2 source archive、PKGBUILD・patch、構築・配置スクリプトを取得できるようにします。必要なソースや通知が揃わない場合、配布を成功扱いにしません。バイナリだけを再配布する場合も、対応ソースの取得案内を失わせないでください。

GStreamer関連を一律LGPLと解釈しないでください。MSYS2のFFmpeg recipeは`--enable-gpl`で構築され、gst-plugins-badもGPL機能を有効にします。採用したバージョン・plugin・依存ライブラリの実際の条件を確認します。LGPL DLLの置換を妨げず、利用者によるデバッグ目的の変更・リバースエンジニアリングを一律に禁止しない構成を維持します。

UxPlay上流はOpenSSL 3.0未満の配布時GPL互換性に注意を促しています。runtimeには3.xを使用します。Bonjourは含めずUxPlay 1.74の内部mDNSを利用します。

同じ媒体への同梱だけでMirrorDock全体へGPLが必ず適用されるとは断定できません。独立した別プロセスという構成を維持し、密接な独自連携へ変更する場合は再評価してください。完全同梱インストーラーは今回未実施です。公開時のソース提供運用、通知、コード署名、実機での受信確認は引き続き必要です。

この記録はライセンス条件の確認と配布方針を説明するもので、あらゆる配布方法について法的な保証を与えるものではありません。特許・DRMなどの事項は著作権ライセンスとは別に検討します。

## 参照元

- https://github.com/openjdk/jfx21u
- https://github.com/java-native-access/jna/tree/5.17.0
- https://github.com/FDH2/UxPlay
- https://github.com/FDH2/UxPlay/blob/f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967/LICENSE
- https://github.com/GStreamer/gstreamer/blob/main/subprojects/gst-docs/markdown/application-development/appendix/licensing.md
- https://github.com/FFmpeg/FFmpeg/blob/master/LICENSE.md
- https://github.com/msys2/MINGW-packages/blob/master/mingw-w64-ffmpeg/PKGBUILD
- https://github.com/msys2/MINGW-packages/blob/master/mingw-w64-gst-plugins-bad/PKGBUILD
- https://github.com/msys2/msys2.github.io/blob/main/web/dev/package-licensing.md
- https://github.com/openssl/openssl/blob/master/LICENSE.txt
- https://openjdk.org/legal/gplv2+ce.html
- https://gradle.org/legal/licensing/
- https://junit.org/junit5/
