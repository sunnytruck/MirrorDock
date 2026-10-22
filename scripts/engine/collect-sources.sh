#!/usr/bin/env bash
# MSYS2 UCRT64 only. Every redistributed prefix binary must have a known package,
# its matching source archive and license notices before publication. Binary package
# signatures remain enforced by pacman; source archives are transported over TLS
# and recorded by SHA256, without inventing a source-signature endpoint.
set -euo pipefail
export LC_ALL=C
runtime=$1
inventory=$2
sources=$3
upstream=$4
mkdir -p "$sources/msys2" "$sources/binary-metadata" "$sources/build-scripts"
python - "$inventory" <<'PY' > "$sources/prefix-files.txt"
import json,sys
for value in json.load(open(sys.argv[1]))['prefix_files'].values():
    print(value)
PY
printf 'relative_path\tpackage\n' > "$sources/file-packages.tsv"
while IFS= read -r original; do
  owner=$(pacman -Qqo "/ucrt64/$original")
  test -n "$owner"
  printf '%s\t%s\n' "$original" "$owner" >> "$sources/file-packages.tsv"
done < "$sources/prefix-files.txt"
tail -n +2 "$sources/file-packages.tsv" | cut -f2 | sort -u > "$sources/packages.txt"
printf 'package\tversion\tpkgbase\tsource_archive\tlicenses\n' > "$sources/packages.tsv"
while IFS= read -r name; do
  read -r installed_name installed_version < <(pacman -Q "$name")
  test "$installed_name" = "$name"
  # Pacman filenames omit a possible version epoch; .PKGINFO preserves it.
  filename_version=${installed_version#*:}
  mapfile -t candidates < <(find /var/cache/pacman/pkg -maxdepth 1 -type f -name "${name}-${filename_version}-*.pkg.tar.*" ! -name '*.sig')
  if [ "${#candidates[@]}" -ne 1 ]; then
    printf 'Expected one exact cached package for %s %s, found %s\n' "$name" "$installed_version" "${#candidates[@]}" >&2
    exit 1
  fi
  metadata="$sources/binary-metadata/$name.PKGINFO"
  bsdtar -xOf "${candidates[0]}" .PKGINFO > "$metadata"
  bsdtar -tf "${candidates[0]}" > "$sources/binary-metadata/$name.contents.txt"
  if grep -qx '.BUILDINFO' "$sources/binary-metadata/$name.contents.txt"; then
    bsdtar -xOf "${candidates[0]}" .BUILDINFO > "$sources/binary-metadata/$name.BUILDINFO"
  fi
  pkgname=$(awk -F ' = ' '$1=="pkgname" {print $2; exit}' "$metadata")
  pkgbase=$(awk -F ' = ' '$1=="pkgbase" {print $2; exit}' "$metadata")
  pkgver=$(awk -F ' = ' '$1=="pkgver" {print $2; exit}' "$metadata")
  test "$pkgname" = "$name"
  test "$pkgver" = "$installed_version"
  [[ "$pkgbase" =~ ^[a-zA-Z0-9_+.-]+$ ]]
  [[ "${pkgver#*:}" =~ ^[a-zA-Z0-9_+.-]+$ ]]
  archive="${pkgbase}-${pkgver#*:}.src.tar.zst"
  if [ ! -f "$sources/msys2/$archive" ]; then
    curl --fail --location --show-error --retry 2 "https://repo.msys2.org/mingw/sources/$archive" -o "$sources/msys2/$archive"
    bsdtar -tf "$sources/msys2/$archive" > "$sources/msys2/$archive.contents.txt"
    if ! grep -Eq '(^|/)PKGBUILD$' "$sources/msys2/$archive.contents.txt"; then
      echo "Source archive is missing PKGBUILD: $archive" >&2; exit 1
    fi
  fi
  licenses=$(awk -F ' = ' '$1=="license" {printf "%s;", $2}' "$metadata")
  test -n "$licenses"
  printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$pkgver" "$pkgbase" "$archive" "$licenses" >> "$sources/packages.tsv"
  # Preserve package-provided license and notice files exactly, without rewriting.
  license_count=0
  while IFS= read -r license; do
    case "$license" in
      /ucrt64/share/licenses/*)
        if [ -f "$license" ]; then
          relative=${license#/ucrt64/}
          mkdir -p "$runtime/$(dirname "$relative")"
          cp -p "$license" "$runtime/$relative"
          license_count=$((license_count + 1))
        fi
        ;;
    esac
  done < <(pacman -Qlq "$name")
  if [ "$license_count" -eq 0 ]; then
    echo "No package-provided license notices for $name; do not publish until reviewed." >&2
    exit 1
  fi
done < "$sources/packages.txt"
mkdir -p "$runtime/share/licenses/UxPlay"
cp "$upstream/LICENSE" "$runtime/share/licenses/UxPlay/LICENSE"
cp "$upstream/lib/llhttp/LICENSE-MIT" "$runtime/share/licenses/UxPlay/llhttp-LICENSE-MIT"
cp "$upstream/lib/playfair/LICENSE.md" "$runtime/share/licenses/UxPlay/playfair-LICENSE.md"
cp "$sources/packages.tsv" "$runtime/packages.tsv"
cp "$sources/file-packages.tsv" "$runtime/file-packages.tsv"
cp "$inventory" "$runtime/build-inventory.json"
git -C "$upstream" archive --format=tar --prefix=UxPlay/ f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967 | gzip -n > "$sources/UxPlay-f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967.tar.gz"
cp -r scripts/engine "$sources/build-scripts/engine"
cp .github/workflows/engine.yml "$sources/build-scripts/engine.yml"
cp scripts/engine/RUNTIME-README.txt "$runtime/README.txt"
cp scripts/engine/SOURCES-README.txt "$sources/README.txt"
# Include a deterministic integrity inventory for all corresponding-source files.
# This records exact source bytes; it is not a publisher signature.
python - "$sources" <<'PY'
from pathlib import Path
import hashlib,sys
sys.path.insert(0, 'scripts/engine')
from bundle import normalized_path
root=normalized_path(sys.argv[1])
lines=[]
for raw in sorted(root.rglob('*')):
    p=normalized_path(raw)
    if p.is_file() and p != root/'files.sha256':
        lines.append(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.relative_to(root).as_posix()+'\n')
(root/'files.sha256').write_text(''.join(lines),encoding='utf-8',newline='\n')
PY
