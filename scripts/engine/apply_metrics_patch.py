#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 MirrorDock contributors.
# This program is free software under GPL version 3 or any later version.
"""Apply MirrorDock's opt-in metadata telemetry to one exact UxPlay revision.

The unmodified upstream source plus this script and native/ reconstruct the
modified engine. A second application is rejected instead of patching twice.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess

UPSTREAM_COMMIT = 'f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967'
MANIFEST = 'MIRRORDOCK_PERFORMANCE_PATCH.json'
NATIVE = Path(__file__).with_name('native')
PATCHES = {
    'renderers/CMakeLists.txt': [
        ('             video_renderer.c\n', '             video_renderer.c\n             md_performance.c\n'),
    ],
    'renderers/video_renderer.c': [
        ('#include "video_renderer.h"\n', '#include "video_renderer.h"\n#include "md_performance.h"\n'),
        ('        renderer_type[i]->bus = gst_element_get_bus(renderer_type[i]->pipeline);\n',
         '        if (!hls_video && (!strcmp(renderer_type[i]->codec, h264) || !strcmp(renderer_type[i]->codec, h265)))\n            md_perf_register_pipeline(renderer_type[i]->pipeline);\n        renderer_type[i]->bus = gst_element_get_bus(renderer_type[i]->pipeline);\n'),
        ('static void video_renderer_destroy_instance(video_renderer_t *renderer) {\n    if (renderer) {\n',
         'static void video_renderer_destroy_instance(video_renderer_t *renderer) {\n    if (renderer) {\n        md_perf_unregister_pipeline(renderer->pipeline);\n'),
    ],
    'uxplay.cpp': [
        ('#include "renderers/video_renderer.h"\n', '#include "renderers/video_renderer.h"\n#include "renderers/md_performance.h"\n'),
        ('    g_main_loop_run(loop);\n', '    md_perf_control_start();\n    g_main_loop_run(loop);\n'),
        ('    printf("-d [n]    Enable debug logging; optional: n=1 to skip normal packet data\\n");\n',
         '    printf("MirrorDock performance diagnostics protocol: MDPERF1 (opt-in)\\n");\n    printf("-d [n]    Enable debug logging; optional: n=1 to skip normal packet data\\n");\n'),
    ],
}


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def transform(files: dict[str, bytes]) -> dict[str, bytes]:
    """Check every anchor before the caller writes any output."""
    result = {}
    for name, changes in PATCHES.items():
        original = files[name].decode('utf-8').replace('\r\n', '\n')
        text = original
        for before, after in changes:
            if text.count(before) != 1:
                raise RuntimeError(f'Expected exactly one pinned-source patch anchor in {name}')
            text = text.replace(before, after, 1)
        result[name] = text.encode('utf-8')
    return result


def apply(source: Path) -> dict:
    if source.is_symlink():
        raise RuntimeError('Expected a regular checked-out upstream source directory')
    source = source.resolve()
    if not source.is_dir():
        raise RuntimeError('Expected a regular checked-out upstream source directory')
    if (source / MANIFEST).exists():
        raise RuntimeError('MirrorDock telemetry patch has already been applied')
    revision = subprocess.run(['git', '-C', str(source), 'rev-parse', 'HEAD'], check=True,
                              capture_output=True, text=True).stdout.strip()
    if revision != UPSTREAM_COMMIT:
        raise RuntimeError('Refusing to patch a different upstream revision')
    dirty = subprocess.run(['git', '-C', str(source), 'status', '--porcelain', '--untracked-files=no'],
                           check=True, capture_output=True, text=True).stdout.strip()
    if dirty:
        raise RuntimeError('Refusing to patch modified tracked upstream files')
    originals = {}
    for name in PATCHES:
        path = source / name
        if not path.is_file() or path.is_symlink():
            raise RuntimeError('Pinned source input is not a regular file')
        originals[name] = path.read_bytes()
    outputs = transform(originals)
    for name in ('md_performance.c', 'md_performance.h'):
        origin = NATIVE / name
        target = source / 'renderers' / name
        if not origin.is_file() or origin.is_symlink() or target.exists() or target.is_symlink():
            raise RuntimeError('Unexpected native telemetry source or existing target')
        outputs['renderers/' + name] = origin.read_bytes()
    manifest = {
        'format': 1, 'protocol': 'MDPERF1', 'upstream_commit': UPSTREAM_COMMIT,
        'activation': 'MIRRORDOCK_PERF_CONTROL=1 and private stdin START command',
        'inputs': {name: digest(data) for name, data in sorted(originals.items())},
        'outputs': {name: digest(data) for name, data in sorted(outputs.items())},
        'patch_script_sha256': digest(Path(__file__).read_bytes()),
        'media_payload_recording': False,
    }
    # All version, cleanliness, anchor and destination checks finish first.
    for name, data in outputs.items():
        (source / name).write_bytes(data)
    (source / MANIFEST).write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n',
                                  encoding='utf-8', newline='\n')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    args = parser.parse_args()
    manifest = apply(args.source)
    print(f'MDPERF1 patch applied: upstream={manifest["upstream_commit"]} files={len(manifest["outputs"])}')
