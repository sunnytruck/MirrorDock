MirrorDock Engine - Corresponding Source

This source archive must accompany the matching MirrorDock-Engine-Windows.zip
from the same GitHub Actions run when redistributing that runtime.

UxPlay-f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967.tar.gz is the exact upstream
UxPlay source. build-scripts/ contains the complete MirrorDock build/packaging
workflow. packages.tsv maps every redistributed MSYS2 binary package to its
exact package version, pkgbase and corresponding official MSYS2 source archive.

msys2/ contains source archives fetched from https://repo.msys2.org/mingw/sources/
using normal TLS verification. Their SHA256 values are recorded in files.sha256;
no detached publisher signature for source archives has been established.
MSYS2 binary package signature verification remains enabled in pacman.
binary-metadata/
contains the package .PKGINFO files used to derive those exact source versions.
The source archives preserve their original PKGBUILD, patches and upstream
source inputs. Follow their own build recipes and license notices.

setup-msys2 clears its package cache after setup. The collector obtains only
the exact installed binary package version with pacman's required trusted
signature policy, and rechecks .PKGINFO; it never installs or upgrades packages.

Some MSYS2 binary packages lack share/licenses files. Audited combinations in
build-scripts/engine/audited-source-notices.json pin the package/version/license,
source archive SHA256, original archive or Git commit, and every notice SHA256.
Original texts are copied without executing PKGBUILD, Git hooks or archive code.
FFmpeg additionally validates its exact original version and all five required
license documents. source-provenance.json beside the recovered notices records
the archive/commit and notice hashes. Unknown or changed combinations fail;
there is no generic exception for packages without license notices.

No source archive or license may be omitted to make a failed packaging step pass.
If an exact source archive is unavailable, packaging must fail rather than upload.

CI tests only executable loading/help and GStreamer plugin availability.
Physical iPhone/Windows AirPlay receiving remains unverified.

Source provenance reference: MSYS2's official autobuild implementation creates
source archives with makepkg-mingw --allsource (including remote source inputs)
and uploads .src.tar archives; it does not declare a source .sig artifact in its
source build/upload patterns:
https://github.com/msys2/msys2-autobuild/blob/main/msys2_autobuild/build.py
https://github.com/msys2/msys2-autobuild/blob/main/msys2_autobuild/queue.py

Source archive TLS + recorded SHA256 is explicitly a different assurance from
the package-signature verification pacman applies when installing binaries.
