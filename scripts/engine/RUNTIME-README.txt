MirrorDock Engine for Windows x64 — 実験版・未署名

このruntimeは、UxPlay上流コミット
f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967（1.74 Experimental）に
MirrorDockの任意の性能診断用計測パッチを適用して構築したものです。
UxPlay公式のバイナリ配布ではありません。Windows PCとiPhoneを使う
実機検証は未実施です。CIの実行ファイル読込・help・plugin確認の成功は、
AirPlay検出、映像、音声、再接続、全画面や性能計測の動作を保証しません。

取得と導入

この変更を含むコミットの「Experimental Windows engine」workflowが
成功した場合、その実行の「MirrorDock-Engine-Windows-and-Sources」
artifactを取得してください。同じrunの次の2ファイルを保持します。

  MirrorDock-Engine-Windows.zip
  MirrorDock-Engine-Corresponding-Source.zip

古い成功runには計測対応の変更が含まれない場合があります。今回の
workflowの成功は、その実行結果を確認するまで未確認です。
MirrorDockのエンジン導入画面でMirrorDock-Engine-Windows.zipを選択します。
無関係なDLLをこのruntimeへコピーしないでください。ファイアウォールの
設定は自動変更しません。内蔵mDNSを使うためBonjourの別途導入は不要です。

変更とライセンス

UxPlayはGPLv3です。MirrorDockが加えたC製計測モジュールとパッチ
スクリプトはGPL-3.0-or-laterで提供します。任意の性能診断用の変更であり、
AirPlayの認証・暗号化を独自に置き換えるものではありません。
変更の記録はshare/mirrordock/MIRRORDOCK_PERFORMANCE_PATCH.jsonにあります。
上流コミット、変更元・変更後ファイルとスクリプトのハッシュを記録します。
metrics.protocol=MDPERF1はMirrorDockの計測対応版を識別する情報です。

各依存コンポーネントの正確な版はpackages.tsv、通知はshare/licensesに
記録します。MSYS2/GStreamer依存関係にはGPL/LGPL等の個別条件があります。
対応ソースZIPには上流の固定コミットのtar原本、各MSYS2 source archive、
binary metadata、パッチ/Cモジュール/構築スクリプト、完全な変更後ソースと
変更manifestを含めます。再配布時は対応ソースの取得案内と全通知も保持し、
この変更済みバイナリに対して上流原本だけを案内しないでください。

検証の範囲

MSYS2バイナリパッケージの署名はpacmanで検証します。source archiveは
TLSを検証するHTTPSで取得しSHA256を記録します。このハッシュは完全性の
記録であり、独立した配布元の署名ではありません。配布条件の確認は
このREADMEだけで代替できません。

files.sha256はZIP内の破損検出用であり、独立した配布元の署名ではありません。
信頼できるMirrorDockリポジトリの対象コミットのworkflowから取得し、
出所を確認してからネイティブ実行ファイルの実行を許可してください。
