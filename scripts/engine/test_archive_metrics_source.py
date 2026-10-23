import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from archive_metrics_source import archive_patch, EXPECTED
from apply_metrics_patch import MANIFEST, PATCHES, UPSTREAM_COMMIT


class ArchiveMetricsSourceTest(unittest.TestCase):
    def setup_tree(self, root):
        source, runtime = root/'upstream', root/'runtime'
        source.mkdir(); runtime.mkdir()
        for name in EXPECTED:
            target = source/name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(('complete patched source for '+name).encode())
        manifest = {'format':1, 'protocol':'MDPERF1', 'upstream_commit':UPSTREAM_COMMIT,
            'inputs':{name:'0'*64 for name in PATCHES},
            'outputs':{name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in EXPECTED},
            'patch_script_sha256':hashlib.sha256(Path(__file__).with_name('apply_metrics_patch.py').read_bytes()).hexdigest(),
            'media_payload_recording':False}
        (source/MANIFEST).write_text(json.dumps(manifest), encoding='utf-8')
        (runtime/'mirrordock-engine.properties').write_text('format=1\n', encoding='utf-8')
        return source, runtime

    @patch('archive_metrics_source.subprocess.run')
    def test_complete_overlay_preserves_exact_patched_source_and_provenance(self, git):
        git.return_value.stdout = UPSTREAM_COMMIT+'\n'
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); source,runtime=self.setup_tree(root); destination=root/'overlay'
            archive_patch(source,destination,runtime)
            for name in EXPECTED|{MANIFEST}:
                self.assertEqual((source/name).read_bytes(),(destination/name).read_bytes())
            self.assertEqual((source/MANIFEST).read_bytes(),(runtime/'share/mirrordock'/MANIFEST).read_bytes())
            self.assertIn('metrics.protocol=MDPERF1\n',(runtime/'mirrordock-engine.properties').read_text())

    @patch('archive_metrics_source.subprocess.run')
    def test_changed_source_is_rejected_before_any_overlay_is_written(self, git):
        git.return_value.stdout=UPSTREAM_COMMIT
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); source,runtime=self.setup_tree(root); destination=root/'overlay'
            (source/'uxplay.cpp').write_text('unrecorded edit',encoding='utf-8')
            with self.assertRaisesRegex(RuntimeError,'does not match'):
                archive_patch(source,destination,runtime)
            self.assertFalse(destination.exists())
            self.assertNotIn('metrics.protocol',(runtime/'mirrordock-engine.properties').read_text())

    @patch('archive_metrics_source.subprocess.run')
    def test_manifest_cannot_smuggle_untracked_or_parent_files_into_sources(self, git):
        git.return_value.stdout=UPSTREAM_COMMIT
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); source,runtime=self.setup_tree(root)
            manifest=json.loads((source/MANIFEST).read_text())
            manifest['outputs']['../outside']='0'*64
            (source/MANIFEST).write_text(json.dumps(manifest),encoding='utf-8')
            with self.assertRaisesRegex(RuntimeError,'provenance'):
                archive_patch(source,root/'overlay',runtime)
            self.assertFalse((root/'overlay').exists())

    @patch('archive_metrics_source.subprocess.run')
    def test_wrong_upstream_revision_is_rejected(self, git):
        git.return_value.stdout='e'*40
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); source,runtime=self.setup_tree(root)
            with self.assertRaisesRegex(RuntimeError,'revision'):
                archive_patch(source,root/'overlay',runtime)

if __name__=='__main__':unittest.main()
