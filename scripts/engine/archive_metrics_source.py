#!/usr/bin/env python3
"""Preserve the exact modified UxPlay files, failing closed on patch drift.

Together with the original upstream tarball this overlay is complete source for
rebuilding the modified engine without Git or network access to upstream.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
from apply_metrics_patch import MANIFEST, PATCHES, UPSTREAM_COMMIT

EXPECTED = set(PATCHES) | {'renderers/md_performance.c', 'renderers/md_performance.h'}


def archive_patch(source: Path, destination: Path, runtime: Path) -> None:
    source = source.resolve()
    manifest_file = source / MANIFEST
    if not manifest_file.is_file() or manifest_file.is_symlink():
        raise RuntimeError('Missing regular telemetry patch manifest')
    manifest = json.loads(manifest_file.read_text(encoding='utf-8'))
    if (manifest.get('format') != 1 or manifest.get('protocol') != 'MDPERF1' or
        manifest.get('upstream_commit') != UPSTREAM_COMMIT or
        set(manifest.get('outputs', {})) != EXPECTED or
        set(manifest.get('inputs', {})) != set(PATCHES) or
        manifest.get('media_payload_recording') is not False):
        raise RuntimeError('Unexpected telemetry patch provenance')
    revision = subprocess.run(['git', '-C', str(source), 'rev-parse', 'HEAD'],
                              check=True, capture_output=True, text=True).stdout.strip()
    if revision != UPSTREAM_COMMIT:
        raise RuntimeError('Wrong upstream revision for corresponding source')
    script_digest = hashlib.sha256(Path(__file__).with_name('apply_metrics_patch.py').read_bytes()).hexdigest()
    if manifest.get('patch_script_sha256') != script_digest:
        raise RuntimeError('Telemetry patch script changed after application')
    for name in sorted(EXPECTED):
        path = source / name
        if (not path.is_file() or path.is_symlink() or
            hashlib.sha256(path.read_bytes()).hexdigest() != manifest['outputs'][name]):
            raise RuntimeError(f'Modified source does not match patch manifest: {name}')
    if destination.exists():
        raise RuntimeError('Corresponding-source overlay destination already exists')
    destination.mkdir(parents=True)
    for name in sorted(EXPECTED | {MANIFEST}):
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source / name, target)
    runtime_manifest = runtime / 'share/mirrordock' / MANIFEST
    runtime_manifest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(manifest_file, runtime_manifest)
    properties = runtime / 'mirrordock-engine.properties'
    metadata = properties.read_text(encoding='utf-8')
    if any(line.startswith('metrics.') for line in metadata.splitlines()):
        raise RuntimeError('Telemetry metadata already exists')
    properties.write_text(metadata.rstrip('\n') + '\nmetrics.protocol=MDPERF1\n', encoding='utf-8', newline='\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('destination', type=Path)
    parser.add_argument('runtime', type=Path)
    args = parser.parse_args()
    archive_patch(args.source, args.destination, args.runtime)
    print('Modified source overlay and runtime patch provenance verified and preserved')
