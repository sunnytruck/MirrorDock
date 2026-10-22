#!/usr/bin/env python3
"""Read pinned notice blobs from an audited MSYS2 allsource bare Git archive.

The caller must verify the complete source archive against its audited SHA256
before calling this helper. Archived configuration, refs and hooks are never
used, and the Git commands cannot access a network or check out source files.
"""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile

MAX_LISTING = 2_000_000
MAX_ENTRIES = 20_000
MAX_OBJECTS = 4_096
MAX_OBJECT = 64_000_000
MAX_TOTAL = 256_000_000
MAX_NOTICE = 1_000_000
TIMEOUT = 60
OBJECT = re.compile(r'objects/(?:[0-9a-f]{2}/[0-9a-f]{38}|pack/pack-[0-9a-f]{40}\.(?:pack|idx))')
PACK_AUXILIARY = re.compile(r'objects/pack/pack-[0-9a-f]{40}\.(?:rev|bitmap)')


def _safe_name(name: str) -> None:
    if (not isinstance(name, str) or not name or name.startswith(('/', '-')) or
            any(c in name for c in '\\:') or
            any(ord(c) < 32 or ord(c) == 127 for c in name) or
            any(part in ('', '.', '..') for part in name.split('/'))):
        raise RuntimeError('Unsafe corresponding-source Git member')


def _run(command: list[str], *, env: dict[str, str], cwd: Path,
         input_data: bytes | None = None) -> bytes:
    try:
        return subprocess.run(command, input=input_data, capture_output=True,
                              check=True, env=env, cwd=cwd, timeout=TIMEOUT).stdout
    except (OSError, subprocess.SubprocessError) as error:
        raise RuntimeError('Cannot read pinned corresponding-source Git objects') from error


def _entries(archive: Path, env: dict[str, str], cwd: Path) -> list[tuple[str, str, int]]:
    listing = _run(['bsdtar', '-tf', str(archive)], env=env, cwd=cwd)
    details = _run(['bsdtar', '-tvf', str(archive)], env=env, cwd=cwd)
    if len(listing) > MAX_LISTING or len(details) > MAX_LISTING:
        raise RuntimeError('Corresponding-source Git listing is too large')
    try:
        names = listing.decode('utf-8').splitlines()
        rows = details.decode('utf-8').splitlines()
    except UnicodeDecodeError as error:
        raise RuntimeError('Invalid corresponding-source Git listing') from error
    if not names or len(names) > MAX_ENTRIES or len(names) != len(rows):
        raise RuntimeError('Ambiguous corresponding-source Git listing')
    entries = []
    seen = set()
    for original, row in zip(names, rows):
        name = original.removesuffix('/')
        _safe_name(name)
        if name in seen:
            raise RuntimeError('Duplicate corresponding-source Git member')
        seen.add(name)
        # libarchive's verbose format starts with the type/mode, followed by
        # link count, user, group and decimal size. Check its exact name too.
        fields = row.split()
        if (not row or row[0] not in ('-', 'd') or len(fields) < 9 or
                not row.endswith(' ' + original) or not fields[4].isdecimal()):
            raise RuntimeError('Non-regular or ambiguous corresponding-source Git member')
        entries.append((name, row[0], int(fields[4])))
    return entries


def _ignored_metadata(relative: str, kind: str) -> bool:
    if kind == 'd':
        return (relative in ('', 'hooks', 'info', 'objects', 'objects/info', 'objects/pack',
                             'refs', 'refs/heads', 'refs/tags', 'refs/remotes') or
                re.fullmatch(r'objects/[0-9a-f]{2}', relative) is not None or
                relative.startswith(('refs/heads/', 'refs/tags/', 'refs/remotes/')))
    return (relative in ('HEAD', 'config', 'description', 'packed-refs', 'info/exclude',
                         'objects/info/packs') or
            re.fullmatch(r'hooks/[a-z0-9-]+\.sample', relative) is not None or
            relative.startswith(('refs/heads/', 'refs/tags/', 'refs/remotes/')) or
            PACK_AUXILIARY.fullmatch(relative) is not None)


def read_git_notices(source_archive: Path, profile: dict) -> dict[str, bytes]:
    """Return only the exact audited notice blobs, without archived Git config."""
    source_archive = Path(source_archive)
    if source_archive.is_symlink() or not source_archive.is_file():
        raise RuntimeError('Corresponding-source Git archive is not a regular file')
    source_archive = source_archive.resolve()
    try:
        prefix = profile['git_archive']['prefix']
        commit = profile['git_archive']['commit']
        notices = profile['notices']
        _safe_name(prefix)
        if not isinstance(commit, str) or re.fullmatch('[0-9a-f]{40}', commit) is None:
            raise RuntimeError('Invalid pinned corresponding-source Git commit')
        if not isinstance(notices, list) or not 0 < len(notices) <= 64:
            raise RuntimeError('Invalid corresponding-source Git notice profile')
        outputs = set()
        members = set()
        for notice in notices:
            _safe_name(notice['member'])
            _safe_name(notice['filename'])
            if ('/' in notice['filename'] or notice['filename'] in outputs or
                    notice['member'] in members or
                    not isinstance(notice['size'], int) or isinstance(notice['size'], bool) or
                    not 0 < notice['size'] <= MAX_NOTICE or
                    re.fullmatch('[0-9a-f]{64}', notice['sha256']) is None):
                raise RuntimeError('Invalid corresponding-source Git notice profile')
            outputs.add(notice['filename'])
            members.add(notice['member'])
    except (KeyError, TypeError) as error:
        raise RuntimeError('Invalid corresponding-source Git notice profile') from error

    # Remove every inherited Git override, including object directories,
    # alternates, injected config and replacement refs. Do not change HOME.
    env = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
    env.update(GIT_CONFIG_NOSYSTEM='1', GIT_TERMINAL_PROMPT='0',
               GIT_ALLOW_PROTOCOL='', GIT_NO_LAZY_FETCH='1', LC_ALL='C')
    with tempfile.TemporaryDirectory(prefix='mirrordock-notice-git-') as temporary:
        root = Path(temporary)
        empty = root / 'empty-template'
        empty.mkdir()
        # A real empty file is portable across native Windows and MSYS Git;
        # os.devnull can be "nul" for Python but a different path for Git.
        empty_config = root / 'empty-gitconfig'
        empty_config.write_bytes(b'')
        env.update(GIT_CONFIG_SYSTEM=str(empty_config), GIT_CONFIG_GLOBAL=str(empty_config))
        gitdir = root / 'objects.git'
        git = ['git', '--no-replace-objects', '--literal-pathspecs',
               '-c', f'core.hooksPath={empty}', '-c', 'protocol.allow=never',
               '-c', 'core.commitGraph=false', f'--git-dir={gitdir}']
        _run(git + ['init', '--bare', f'--template={empty}', str(gitdir)], env=env, cwd=root)
        entries = _entries(source_archive, env, root)
        objects = []
        total = 0
        for name, kind, size in entries:
            if name == prefix:
                if kind != 'd':
                    raise RuntimeError('Corresponding-source Git prefix is not a directory')
                continue
            if not name.startswith(prefix + '/'):
                continue
            relative = name[len(prefix) + 1:]
            if OBJECT.fullmatch(relative) is not None:
                if kind != '-' or not 0 < size <= MAX_OBJECT:
                    raise RuntimeError('Invalid corresponding-source Git object size/type')
                total += size
                objects.append((name, relative, size))
            elif not _ignored_metadata(relative, kind):
                raise RuntimeError('Unknown corresponding-source Git member')
        if not objects or len(objects) > MAX_OBJECTS or total > MAX_TOTAL:
            raise RuntimeError('Missing or excessive corresponding-source Git objects')
        packs = {relative for _, relative, _ in objects if relative.endswith('.pack')}
        indexes = {relative for _, relative, _ in objects if relative.endswith('.idx')}
        if {name.removesuffix('.pack') for name in packs} != {name.removesuffix('.idx') for name in indexes}:
            raise RuntimeError('Corresponding-source Git pack/index mismatch')
        for name, relative, size in objects:
            target = gitdir / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open('xb') as output:
                try:
                    subprocess.run(['bsdtar', '-xOf', str(source_archive), '--', name],
                                   check=True, stdout=output, stderr=subprocess.PIPE,
                                   env=env, cwd=root, timeout=TIMEOUT)
                except (OSError, subprocess.SubprocessError) as error:
                    raise RuntimeError('Cannot read corresponding-source Git object') from error
            if target.stat().st_size != size:
                raise RuntimeError('Corresponding-source Git object size mismatch')
        if _run(git + ['cat-file', '-t', commit], env=env, cwd=root) != b'commit\n':
            raise RuntimeError('Pinned corresponding-source Git commit is unavailable')
        result = {}
        for notice in notices:
            member = notice['member']
            tree = _run(git + ['ls-tree', '--full-tree', '-z', commit, '--', member], env=env, cwd=root)
            try:
                mode_type_oid, found_name, end = re.split(b'[\t\0]', tree)
            except ValueError as error:
                raise RuntimeError('Missing or ambiguous corresponding-source Git notice') from error
            if (end or found_name != member.encode('utf-8') or
                    re.fullmatch(rb'100(?:644|755) blob [0-9a-f]{40}', mode_type_oid) is None):
                raise RuntimeError('Corresponding-source Git notice is not a regular blob')
            spec = commit + ':' + member
            size = _run(git + ['cat-file', '-s', spec], env=env, cwd=root)
            if size != str(notice['size']).encode('ascii') + b'\n':
                raise RuntimeError('Corresponding-source Git notice size mismatch')
            payload = _run(git + ['cat-file', 'blob', spec], env=env, cwd=root)
            if (len(payload) != notice['size'] or
                    hashlib.sha256(payload).hexdigest() != notice['sha256']):
                raise RuntimeError('Corresponding-source Git notice SHA256 mismatch')
            result[notice['filename']] = payload
        return result
