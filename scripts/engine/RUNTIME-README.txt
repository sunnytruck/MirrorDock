MirrorDock Engine for Windows x64 - EXPERIMENTAL, UNSIGNED

This runtime is built by MirrorDock from the upstream UxPlay commit
f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967 (1.74 Experimental).
It is not an official UxPlay binary distribution and has not been verified
with a physical iPhone/Windows PC. Successful CI help/plugin checks do not
prove that AirPlay discovery, mirroring, sound, reconnection or fullscreen work.

Import MirrorDock-Engine-Windows.zip using MirrorDock's engine setup UI.
Do not copy unrelated DLLs into this runtime. Nothing changes firewall rules.
The built-in mDNS implementation avoids a separate Bonjour installation.
Keep the corresponding-source ZIP and all license notices with redistributions.

Components and their exact versions are recorded in packages.tsv.
Per-component notices are under share/licenses. UxPlay is GPL-3.0 and
MSYS2/GStreamer dependencies have their own GPL/LGPL/permissive terms.
The separate MirrorDock-Engine-Corresponding-Source.zip contains matching
UxPlay sources, matching MSYS2 source archives, binary metadata and build scripts.
MSYS2 binary signatures are verified by pacman during installation. Source
archives are fetched over verified HTTPS and recorded by SHA256; these hashes
are integrity records, not independent publisher signatures.
Do not treat this notice as a replacement for reviewing distribution obligations.

files.sha256 detects corruption within this ZIP. It is not an independent
publisher signature. Obtain this ZIP only from the trusted MirrorDock repository
and verify its provenance before allowing it to run native executable code.
