# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 MirrorDock contributors.
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from apply_metrics_patch import apply, transform, PATCHES, UPSTREAM_COMMIT, MANIFEST


class MetricsPatchTest(unittest.TestCase):
    def fixture(self, source):
        files = {name: ''.join(before for before, _ in changes).encode() for name, changes in PATCHES.items()}
        for name, data in files.items():
            path = source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        return files

    def git(self, revision=UPSTREAM_COMMIT, dirty=''):
        def run(command, **options):
            if command[-2:] == ['rev-parse', 'HEAD']:
                return subprocess.CompletedProcess(command, 0, revision + '\n', '')
            self.assertEqual(['status', '--porcelain', '--untracked-files=no'], command[-3:])
            return subprocess.CompletedProcess(command, 0, dirty, '')
        return run

    def test_applies_once_and_records_exact_modified_sources(self):
        with tempfile.TemporaryDirectory() as value:
            root = Path(value)
            original = self.fixture(root)
            with patch('apply_metrics_patch.subprocess.run', side_effect=self.git()):
                record = apply(root)
                with self.assertRaises(RuntimeError):
                    apply(root)
            self.assertEqual('MDPERF1', record['protocol'])
            self.assertFalse(record['media_payload_recording'])
            self.assertEqual(record, json.loads((root / MANIFEST).read_text()))
            for name, data in original.items():
                self.assertEqual(hashlib.sha256(data).hexdigest(), record['inputs'][name])
            for name, digest in record['outputs'].items():
                self.assertEqual(hashlib.sha256((root / name).read_bytes()).hexdigest(), digest)
            self.assertIn('md_perf_control_start();', (root / 'uxplay.cpp').read_text())

    def test_different_revision_dirty_tree_and_bad_anchor_fail_before_writing(self):
        for failure in ('revision', 'dirty', 'missing', 'duplicate'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as value:
                root = Path(value)
                self.fixture(root)
                bad = root / 'uxplay.cpp'
                if failure == 'missing': bad.write_text('changed upstream source\n')
                if failure == 'duplicate': bad.write_bytes(bad.read_bytes() * 2)
                before = {str(Path(str(path)).relative_to(root)): path.read_bytes() for path in root.rglob('*') if path.is_file()}
                with patch('apply_metrics_patch.subprocess.run', side_effect=self.git(
                        revision='0' * 40 if failure == 'revision' else UPSTREAM_COMMIT,
                        dirty=' M uxplay.cpp\n' if failure == 'dirty' else '')):
                    with self.assertRaises(RuntimeError): apply(root)
                after = {str(Path(str(path)).relative_to(root)): path.read_bytes() for path in root.rglob('*') if path.is_file()}
                self.assertEqual(before, after)

    def test_crlf_inputs_produce_lf_patch_and_native_sources_are_required(self):
        files = {name: ''.join(before for before, _ in changes).replace('\n', '\r\n').encode()
                 for name, changes in PATCHES.items()}
        self.assertTrue(all(b'\r' not in data for data in transform(files).values()))
        with tempfile.TemporaryDirectory() as value:
            root = Path(value)
            self.fixture(root)
            with patch('apply_metrics_patch.NATIVE', root / 'missing-native'), patch(
                    'apply_metrics_patch.subprocess.run', side_effect=self.git()):
                with self.assertRaises(RuntimeError): apply(root)
            self.assertFalse((root / MANIFEST).exists())


if __name__ == '__main__':
    unittest.main()
