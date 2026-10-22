#!/usr/bin/env bash
# Source this file from MSYS2 Bash. It never installs/upgrades packages or changes
# signature policy. Successful stdout contains only the exact verified archive.

_package_archive_error() {
  printf 'Package archive error: %s\n' "$*" >&2
  return 1
}

_package_archive_candidates() {
  local package=$1 version=$2 directory file canonical
  shift 2
  local -A seen=()
  for directory in "$@"; do
    [[ -d "$directory" ]] || continue
    for file in "$directory"/"$package-$version-"*.pkg.tar.*; do
      [[ -f "$file" ]] || continue
      case "$file" in
        *.pkg.tar.zst|*.pkg.tar.xz|*.pkg.tar.gz|*.pkg.tar.bz2) ;;
        *) continue ;;
      esac
      canonical=$(cd -P -- "${file%/*}" && printf '%s/%s\n' "$PWD" "${file##*/}") || return 1
      if [[ -z "${seen[$canonical]+present}" ]]; then
        seen[$canonical]=1
        printf '%s\n' "$canonical"
      fi
    done
  done
}

_package_archive_metadata_matches() {
  local archive=$1 package=$2 version=$3 metadata actual_name actual_version
  metadata=$(bsdtar -xOf "$archive" .PKGINFO) || return 1
  actual_name=$(awk -F ' = ' '$1=="pkgname" {print $2}' <<< "$metadata")
  actual_version=$(awk -F ' = ' '$1=="pkgver" {print $2}' <<< "$metadata")
  [[ "$actual_name" == "$package" && "$actual_version" == "$version" ]] || {
    _package_archive_error "Archive metadata does not match installed $package $version"
    return 1
  }
}

obtain_package_archive() {
  local package=$1 installed_version=$2 download_cache=$3
  local configured directory archive repo_data repository repo_name repo_version extra line policy
  local -a directories=() candidates=()
  [[ "$package" =~ ^[a-zA-Z0-9@+_.-]+$ ]] || { _package_archive_error 'Invalid package name'; return 1; }
  # MSYS2 uses '~' for epochs; preserve it exactly in filenames and metadata.
  [[ "$installed_version" =~ ^[a-zA-Z0-9+_.~-]+$ ]] || { _package_archive_error 'Invalid MSYS2 package version'; return 1; }
  [[ -n "$download_cache" && ! "$download_cache" =~ [[:cntrl:]] ]] || { _package_archive_error 'Invalid download cache'; return 1; }
  configured=$(pacman-conf CacheDir) || { _package_archive_error 'Cannot read configured CacheDir'; return 1; }
  while IFS= read -r directory; do
    [[ -n "$directory" && ! "$directory" =~ [[:cntrl:]] ]] || { _package_archive_error 'Invalid configured CacheDir'; return 1; }
    directories+=("$directory")
  done <<< "$configured"
  directories+=("$download_cache")
  # Capture status as well as output; process substitutions would hide errors.
  configured=$(_package_archive_candidates "$package" "$installed_version" "${directories[@]}") || return 1
  if [[ -n "$configured" ]]; then mapfile -t candidates <<< "$configured"; fi
  if (( ${#candidates[@]} > 1 )); then
    _package_archive_error "Multiple exact archives found for $package $installed_version"
    return 1
  fi

  # setup-msys2 deliberately clears the package cache after saving its own cache.
  # Re-fetch only if the current repository still has the exact installed version.
  # No -y, no install transaction, no guessed mirror/archive URL or newer version.
  repo_data=$(pacman -Sp --print-format '%r %n %v' -- "$package") || { _package_archive_error "Cannot resolve repository metadata for $package"; return 1; }
  local found=0
  while IFS= read -r line; do
    read -r repository repo_name repo_version extra <<< "$line"
    [[ "$repo_name" == "$package" ]] || continue
    ((found+=1))
    [[ -z "$extra" && "$repository" =~ ^[a-zA-Z0-9_+.-]+$ && "$repo_version" == "$installed_version" ]] || {
      _package_archive_error "Repository version differs from installed $package $installed_version; refusing a different source mapping"
      return 1
    }
    # Save the one matching repository; other transaction lines may be dependencies.
    local matched_repository=$repository
  done <<< "$repo_data"
  (( found == 1 )) || { _package_archive_error "Expected one repository match for $package $installed_version"; return 1; }
  repository=$matched_repository
  policy=$(pacman-conf --repo "$repository" SigLevel) || { _package_archive_error 'Cannot read repository signature policy'; return 1; }
  if [[ -z "$policy" ]]; then policy=$(pacman-conf SigLevel) || return 1; fi
  grep -qx 'PackageRequired' <<< "$policy" && grep -qx 'PackageTrustedOnly' <<< "$policy" || {
    _package_archive_error "Required trusted package signatures are not configured for $repository"
    return 1
  }
  if grep -Eq '^Package(Optional|Never|TrustAll)$' <<< "$policy"; then
    _package_archive_error "Unsafe package signature policy for $repository"
    return 1
  fi
  if (( ${#candidates[@]} == 1 )); then
    directory=${candidates[0]%/*}
  else
    mkdir -p -- "$download_cache" || return 1
    directory=$download_cache
  fi
  # libalpm checks package validity/signatures before the DOWNLOADONLY return.
  # Revalidate both cached and newly downloaded archives through this same path.
  # libalpm uses the sync database's PGP data and the effective required/trusted
  # policy. A separate pacman-key process is neither needed nor appropriate:
  # it can demand keyring permissions unavailable to a non-administrator runner.
  pacman -Sw --noconfirm --cachedir "$directory" -- "$repository/$package" >&2 || {
    _package_archive_error "Verified package download failed for $package $installed_version"
    return 1
  }
  configured=$(_package_archive_candidates "$package" "$installed_version" "${directories[@]}") || return 1
  candidates=()
  if [[ -n "$configured" ]]; then mapfile -t candidates <<< "$configured"; fi
  (( ${#candidates[@]} == 1 )) || { _package_archive_error "Expected one exact archive after verified download for $package $installed_version, found ${#candidates[@]}"; return 1; }
  archive=${candidates[0]}
  _package_archive_metadata_matches "$archive" "$package" "$installed_version" || return 1
  printf '%s\n' "$archive"
}
