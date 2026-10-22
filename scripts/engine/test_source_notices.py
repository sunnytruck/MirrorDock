import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

from source_notices import collect_notices, NOTICES, MAX_NOTICE


class SourceNoticesTest(unittest.TestCase):
    def inner_archive(self, missing=(), symlink=None, oversized=None, unsafe=False):
        result = io.BytesIO()
        expected = {}
        with tarfile.open(fileobj=result, mode='w:xz') as archive:
            for filename in NOTICES:
                if filename in missing:
                    continue
                payload = (filename + '\nexact upstream notice bytes\r\n').encode('utf-8')
                member = tarfile.TarInfo('ffmpeg-9.0.2/' + filename)
                if filename == symlink:
                    member.type = tarfile.SYMTYPE
                    member.linkname = '/outside/notice'
                    archive.addfile(member)
                else:
                    if filename == oversized:
                        payload = b'x' * (MAX_NOTICE + 1)
                    member.size = len(payload)
                    archive.addfile(member, io.BytesIO(payload))
                    expected[filename] = payload
            if unsafe:
                member = tarfile.TarInfo('../outside')
                archive.addfile(member, io.BytesIO())
        return result.getvalue(), expected

    def source_fixture(self, root, **inner_options):
        archive = root / 'mingw-w64-ffmpeg-9.0.2-1.src.tar.zst'
        archive.write_bytes(b'outer source fixture')
        inner, payloads = self.inner_archive(**inner_options)
        original = 'mingw-w64-ffmpeg/ffmpeg-9.0.2.tar.xz'
        names = ['mingw-w64-ffmpeg/', 'mingw-w64-ffmpeg/PKGBUILD', original]
        return archive, inner, payloads, original, names

    def bsdtar(self, inner, original, names, regular=True):
        def execute(command, **options):
            self.assertEqual('bsdtar', command[0])
            self.assertTrue(options.get('check'))
            if command[1] == '-tf':
                self.assertEqual(3, len(command))
                return subprocess.CompletedProcess(command, 0, '\n'.join(names) + '\n', '')
            if command[1] == '-tvf':
                self.assertEqual(['--', original], command[3:])
                mode = '-' if regular else 'l'
                return subprocess.CompletedProcess(command, 0, mode + 'rw-r--r-- 0 user group 100 Jan 1 00:00 ' + original + '\n', '')
            if command[1] == '-xOf':
                self.assertEqual(['--', original], command[3:])
                options['stdout'].write(inner)
                return subprocess.CompletedProcess(command, 0, None, b'')
            self.fail('Unexpected command; only archive listing and member reading are permitted')
        return execute

    def test_preserves_exact_notices_and_complete_source_provenance(self):
        with tempfile.TemporaryDirectory() as value:
            root = Path(value)
            outer, inner, payloads, original, names = self.source_fixture(root)
            destination = root / 'notices'
            with patch('source_notices.subprocess.run', side_effect=self.bsdtar(inner, original, names)):
                count = collect_notices('mingw-w64-ffmpeg', '9.0.2-1', outer, destination, 'spdx:GPL-3.0-or-later;')
            self.assertEqual(5, count)
            for name, payload in payloads.items():
                self.assertEqual(payload, (destination / name).read_bytes())
            provenance = json.loads((destination / 'source-provenance.json').read_text(encoding='utf-8'))
            self.assertEqual('9.0.2-1', provenance['pkgver'])
            self.assertEqual(hashlib.sha256(outer.read_bytes()).hexdigest(), provenance['outer_archive']['sha256'])
            self.assertEqual(original, provenance['inner_archive']['member'])
            self.assertEqual(hashlib.sha256(inner).hexdigest(), provenance['inner_archive']['sha256'])
            for notice in provenance['notices']:
                self.assertEqual(hashlib.sha256(payloads[notice['filename']]).hexdigest(), notice['sha256'])

    def test_epoch_and_package_release_select_exact_upstream_version(self):
        with tempfile.TemporaryDirectory() as value:
            root = Path(value)
            outer, inner, payloads, original, names = self.source_fixture(root)
            with patch('source_notices.subprocess.run', side_effect=self.bsdtar(inner, original, names)):
                self.assertEqual(5, collect_notices('mingw-w64-ffmpeg', '1~9.0.2-1', outer, root / 'notices', 'spdx:GPL-3.0-or-later'))

    def test_non_msys2_colon_epoch_is_rejected(self):
        with tempfile.TemporaryDirectory() as value:
            root = Path(value)
            with patch('source_notices.subprocess.run') as execute:
                with self.assertRaises(RuntimeError):
                    collect_notices('mingw-w64-ffmpeg', '1:9.0.2-1', root / 'source', root / 'notices', 'spdx:GPL-3.0-or-later')
                execute.assert_not_called()

    def test_unknown_package_and_unaudited_license_fail_before_reading_archive(self):
        with tempfile.TemporaryDirectory() as value:
            root = Path(value)
            with patch('source_notices.subprocess.run') as execute:
                for package, license_name in [('mingw-w64-other', 'spdx:GPL-3.0-or-later'),
                                              ('mingw-w64-ffmpeg', 'spdx:LGPL-2.1-or-later'),
                                              ('mingw-w64-ffmpeg', 'spdx:GPL-3.0-or-later;unknown')]:
                    with self.subTest(package=package, license=license_name), self.assertRaises(RuntimeError):
                        collect_notices(package, '9.0.2-1', root / 'source', root / 'notices', license_name)
                execute.assert_not_called()

    def test_missing_and_ambiguous_original_source_are_rejected(self):
        with tempfile.TemporaryDirectory() as value:
            root = Path(value)
            outer, inner, payloads, original, names = self.source_fixture(root)
            variants = [[name for name in names if name != original], names + ['different/' + original.split('/')[-1]]]
            for members in variants:
                with self.subTest(names=members), patch('source_notices.subprocess.run', side_effect=self.bsdtar(inner, original, members)), self.assertRaises(RuntimeError):
                    collect_notices('mingw-w64-ffmpeg', '9.0.2-1', outer, root / 'notices', 'spdx:GPL-3.0-or-later')
            self.assertFalse((root / 'notices').exists())

    def test_original_source_must_be_a_regular_member(self):
        with tempfile.TemporaryDirectory() as value:
            root = Path(value)
            outer, inner, payloads, original, names = self.source_fixture(root)
            with patch('source_notices.subprocess.run', side_effect=self.bsdtar(inner, original, names, regular=False)), self.assertRaises(RuntimeError):
                collect_notices('mingw-w64-ffmpeg', '9.0.2-1', outer, root / 'notices', 'spdx:GPL-3.0-or-later')
            self.assertFalse((root / 'notices').exists())

    def test_missing_symlink_and_oversized_notices_fail_without_partial_outputs(self):
        for option in ({'missing': ['COPYING.GPLv3']}, {'symlink': 'LICENSE.md'}, {'oversized': 'LICENSE.md'}):
            with self.subTest(option=option), tempfile.TemporaryDirectory() as value:
                root = Path(value)
                outer, inner, payloads, original, names = self.source_fixture(root, **option)
                with patch('source_notices.subprocess.run', side_effect=self.bsdtar(inner, original, names)), self.assertRaises(RuntimeError):
                    collect_notices('mingw-w64-ffmpeg', '9.0.2-1', outer, root / 'notices', 'spdx:GPL-3.0-or-later')
                self.assertFalse((root / 'notices').exists())

    def test_unsafe_outer_and_inner_paths_are_rejected(self):
        for unsafe_inner in (False, True):
            with self.subTest(inner=unsafe_inner), tempfile.TemporaryDirectory() as value:
                root = Path(value)
                outer, inner, payloads, original, names = self.source_fixture(root, unsafe=unsafe_inner)
                if not unsafe_inner:
                    names.append('../outside')
                with patch('source_notices.subprocess.run', side_effect=self.bsdtar(inner, original, names)), self.assertRaises(RuntimeError):
                    collect_notices('mingw-w64-ffmpeg', '9.0.2-1', outer, root / 'notices', 'spdx:GPL-3.0-or-later')
                self.assertFalse((root / 'notices').exists())

    def test_repeatable_and_does_not_overwrite_conflicting_files(self):
        with tempfile.TemporaryDirectory() as value:
            root = Path(value)
            outer, inner, payloads, original, names = self.source_fixture(root)
            destination = root / 'notices'
            with patch('source_notices.subprocess.run', side_effect=self.bsdtar(inner, original, names)):
                self.assertEqual(5, collect_notices('mingw-w64-ffmpeg', '9.0.2-1', outer, destination, 'spdx:GPL-3.0-or-later'))
                self.assertEqual(5, collect_notices('mingw-w64-ffmpeg', '9.0.2-1', outer, destination, 'spdx:GPL-3.0-or-later'))
                (destination / 'LICENSE.md').write_bytes(b'pre-existing different notice')
                with self.assertRaises(RuntimeError):
                    collect_notices('mingw-w64-ffmpeg', '9.0.2-1', outer, destination, 'spdx:GPL-3.0-or-later')
                self.assertEqual(b'pre-existing different notice', (destination / 'LICENSE.md').read_bytes())


if __name__ == '__main__':
    unittest.main()
