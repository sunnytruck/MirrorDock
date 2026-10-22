#!/usr/bin/env python3
"""Recover audited notices from the exact corresponding-source archive.

This is deliberately not a generic permission to publish packages without
notices. No PKGBUILD is executed and no archive tree is extracted to disk.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import tempfile

NOTICES = ('LICENSE.md', 'COPYING.GPLv2', 'COPYING.GPLv3', 'COPYING.LGPLv2.1', 'COPYING.LGPLv3')
MAX_NOTICE = 1_000_000
MAX_INNER = 64_000_000
PROFILES_PATH = Path(__file__).with_name('audited-source-notices.json')


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as data:
        for block in iter(lambda: data.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def _safe_member(name: str) -> None:
    name = name.removesuffix('/')
    if (not name or name.startswith('/') or any(c in name for c in '\\:') or
            any(ord(c) < 32 or ord(c) == 127 for c in name) or
            any(part in ('', '.', '..') for part in name.split('/'))):
        raise RuntimeError('Unsafe corresponding-source archive member')


def _listing(archive: Path, member: str | None = None) -> list[str]:
    command = ['bsdtar', '-tvf' if member is not None else '-tf', str(archive)]
    if member is not None:
        command += ['--', member]
    result = subprocess.run(command, check=True, capture_output=True, text=True,
                            encoding='utf-8', timeout=60)
    if len(result.stdout) > 2_000_000:
        raise RuntimeError('Corresponding-source archive listing is too large')
    return result.stdout.splitlines()


def _profile(pkgbase: str, pkgver: str, source_archive: Path, declared_license: str) -> dict:
    declarations = [item for item in declared_license.split(';') if item]
    if pkgbase != 'mingw-w64-ffmpeg':
        profiles = json.loads(PROFILES_PATH.read_text(encoding='utf-8'))
        profile = profiles.get(pkgbase)
        if (profile is None or profile['pkgbase'] != pkgbase or profile['pkgver'] != pkgver or
                sorted(profile['declared_licenses']) != sorted(declarations)):
            raise RuntimeError('No audited source-notice fallback for this package/version/license')
        if _sha256_file(source_archive) != profile['outer_archive']['sha256']:
            raise RuntimeError('Corresponding source differs from the audited archive')
        return profile
    if declarations != ['spdx:GPL-3.0-or-later']:
        raise RuntimeError('No audited source-notice fallback for this package/license')
    version = pkgver
    if '~' in version:
        epoch, version = version.split('~', 1)
        if not epoch.isdecimal():
            raise RuntimeError('Invalid package epoch')
    match = re.fullmatch(r'([0-9]+(?:\.[0-9]+)+)-([0-9]+)', version)
    if match is None:
        raise RuntimeError('Unsupported FFmpeg package version')
    upstream = match.group(1)
    return {'inner_archive': {'basename': f'ffmpeg-{upstream}.tar.xz'},
            'notices': [{'member': f'ffmpeg-{upstream}/{filename}', 'filename': filename} for filename in NOTICES]}


def _tar_notices(source_archive: Path, profile: dict) -> tuple[dict[str, bytes], dict]:
    names = _listing(source_archive)
    for name in names:
        _safe_member(name)
    expected = profile['inner_archive']
    originals = [name for name in names if
                 (name == expected['member'] if 'member' in expected else PurePosixPath(name).name == expected['basename'])]
    if len(originals) != 1:
        raise RuntimeError('Expected one exact original source archive')
    original = originals[0]
    detail = _listing(source_archive, original)
    if len(detail) != 1 or not detail[0].startswith('-'):
        raise RuntimeError('Original source member is not a regular file')

    payloads: dict[str, bytes] = {}
    with tempfile.TemporaryFile() as inner:
        subprocess.run(['bsdtar', '-xOf', str(source_archive), '--', original], check=True,
                       stdout=inner, stderr=subprocess.PIPE, timeout=60)
        if inner.tell() > MAX_INNER:
            raise RuntimeError('Original source archive is too large')
        inner.seek(0)
        inner_digest = hashlib.sha256()
        for block in iter(lambda: inner.read(1024 * 1024), b''):
            inner_digest.update(block)
        if 'sha256' in expected and inner_digest.hexdigest() != expected['sha256']:
            raise RuntimeError('Original source differs from the audited archive')
        inner.seek(0)
        with tarfile.open(fileobj=inner, mode='r:*') as archive:
            members = {}
            for member in archive.getmembers():
                _safe_member(member.name)
                if member.name in members:
                    raise RuntimeError('Ambiguous original source member')
                members[member.name] = member
            for notice in profile['notices']:
                filename, member_name = notice['filename'], notice['member']
                member = members.get(member_name)
                if member is None or not member.isfile() or not 0 < member.size <= MAX_NOTICE:
                    raise RuntimeError(f'Missing or invalid required notice: {filename}')
                stream = archive.extractfile(member)
                if stream is None:
                    raise RuntimeError(f'Cannot read required notice: {filename}')
                with stream:
                    payload = stream.read(MAX_NOTICE + 1)
                if len(payload) != member.size or len(payload) > MAX_NOTICE:
                    raise RuntimeError(f'Invalid notice size: {filename}')
                payloads[filename] = payload
    return payloads, {'inner_archive': {'member': original, 'sha256': inner_digest.hexdigest()}}


def collect_notices(pkgbase: str, pkgver: str, source_archive: Path,
                    destination: Path, declared_license: str) -> int:
    source_archive, destination = Path(source_archive), Path(destination)
    # Reject unsupported packages before invoking archive readers.
    profile = _profile(pkgbase, pkgver, source_archive, declared_license)
    if not source_archive.is_file() or source_archive.is_symlink():
        raise RuntimeError('Corresponding-source archive is not a regular file')
    output_names = set()
    for notice in profile['notices']:
        filename = notice['filename']
        _safe_member(filename)
        if '/' in filename or filename.casefold() in output_names or filename == 'source-provenance.json':
            raise RuntimeError('Invalid or colliding audited notice filename')
        output_names.add(filename.casefold())
    if not output_names:
        raise RuntimeError('Audited notice list must not be empty')
    if 'git_archive' in profile:
        from git_source_notices import read_git_notices
        payloads = read_git_notices(source_archive, profile)
        source_record = {'git_archive': profile['git_archive']}
    else:
        payloads, source_record = _tar_notices(source_archive, profile)
    if set(payloads) != {notice['filename'] for notice in profile['notices']}:
        raise RuntimeError('Incomplete audited notices')
    notice_records = []
    for notice in profile['notices']:
        payload = payloads[notice['filename']]
        digest = hashlib.sha256(payload).hexdigest()
        if not 0 < len(payload) <= MAX_NOTICE or ('size' in notice and len(payload) != notice['size']) or ('sha256' in notice and digest != notice['sha256']):
            raise RuntimeError('Notice differs from the audited original')
        notice_records.append({'member': notice['member'], 'filename': notice['filename'],
                               'sha256': digest, 'size': len(payload)})

    provenance = {
        'pkgbase': pkgbase, 'pkgver': pkgver, 'declared_license': declared_license,
        'outer_archive': {'filename': source_archive.name, 'sha256': _sha256_file(source_archive)},
        **source_record,
        'notices': notice_records,
        'method': 'Audited original source notices; no PKGBUILD execution or arbitrary tree extraction',
    }
    payloads['source-provenance.json'] = (json.dumps(provenance, ensure_ascii=True, indent=2) + '\n').encode('utf-8')
    if destination.is_symlink():
        raise RuntimeError('Notice destination must not be a symbolic link')
    # Validate all source notices and existing outputs before writing any files.
    for filename, payload in payloads.items():
        target = destination / filename
        if target.exists() and (target.is_symlink() or not target.is_file() or target.read_bytes() != payload):
            raise RuntimeError('Conflicting existing notice file')
    destination.mkdir(parents=True, exist_ok=True)
    for filename, payload in payloads.items():
        target = destination / filename
        if not target.exists():
            with target.open('xb') as out:
                out.write(payload)
    return len(notice_records)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('pkgbase')
    parser.add_argument('pkgver')
    parser.add_argument('source_archive', type=Path)
    parser.add_argument('destination', type=Path)
    parser.add_argument('declared_license')
    args = parser.parse_args()
    collect_notices(args.pkgbase, args.pkgver, args.source_archive, args.destination, args.declared_license)
