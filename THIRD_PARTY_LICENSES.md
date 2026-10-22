# Third-party licenses

MirrorDock の著作権・公開ライセンスはリポジトリ所有者が決定してください。本ファイルは第三者ソフトウェアの通知です。

| Component | Version | License / notice | Distribution |
| --- | --- | --- | --- |
| OpenJFX (base, graphics, controls) | 21.0.7 | GPL v2 with Classpath Exception; additional notices / assembly exception | Application distribution |
| JNA / JNA Platform | 5.17.0 | Apache-2.0 OR LGPL-2.1-or-later（本配布では Apache-2.0 を選択） | Application distribution |
| OpenJDK runtime | Packaging JDK 21 vendor/version | GPL v2 with Classpath Exception and bundled legal notices; vendor-dependent | jpackage runtime |
| Gradle | 8.14.3 | Apache-2.0 | Wrapper / build tool, not app runtime |
| JUnit Jupiter / Platform | 5.12.2 / 1.12.2 | EPL-2.0 | Test only |
| UxPlay | External; reference f0cd2c3 / 1.74 Experimental | GPL-3.0; consult upstream component notices | Not bundled |
| GStreamer, plugins, libav, OpenSSL, libplist, MSYS2 runtime libraries | External | Component-specific LGPL/GPL/other licenses; inspect exact builds | Not bundled |
| Apple Bonjour SDK / service | External / optional | Apple license and SDK terms | Not bundled |
| WiX Toolset | 3.x | Microsoft Reciprocal License; consult WiX distribution exceptions | External packaging tool |

`licenses/` includes upstream OpenJFX license/exception notices and JNA license notices / Apache-2.0 text. OpenJFX LICENSE includes its third-party notices. jpackage retains the JDK's runtime legal directory; do not remove it.

Sources:

- https://github.com/openjdk/jfx21u
- https://github.com/java-native-access/jna/tree/5.17.0
- https://github.com/FDH2/UxPlay
- https://openjdk.org/legal/gplv2+ce.html
- https://gradle.org/legal/licensing/
- https://junit.org/junit5/

UxPlay、GStreamer DLL、Bonjour を配布物へ追加する場合は、追加前にそれぞれの正確なバージョンのライセンスと通知、対応ソース提供、再配布条件を確認してください。本実装はこれらを自動ダウンロード・同梱しません。
