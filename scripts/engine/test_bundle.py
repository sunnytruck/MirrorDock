import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from bundle import dependency_closure, finalize, imports_from_objdump

class BundleTest(unittest.TestCase):
    def test_parse_imports(self):
        self.assertEqual(["KERNEL32.dll", "libgstreamer-1.0-0.dll"], imports_from_objdump("x\n  DLL Name: KERNEL32.dll\n DLL Name: libgstreamer-1.0-0.dll\n"))
    def test_recursive_case_insensitive_closure_and_system_exclusion(self):
        with tempfile.TemporaryDirectory() as value:
            root=Path(value); binary=root/'bin'; system=root/'system';binary.mkdir();system.mkdir()
            app=root/'app.exe';a=binary/'a.dll';b=binary/'b.dll';kernel=system/'KERNEL32.dll'
            for p in (app,a,b,kernel):p.touch()
            deps={app:['A.DLL','kernel32.dll'],a:['b.dll'],b:['a.dll']}
            files,excluded=dependency_closure([app],binary,system,lambda p:deps[p])
            self.assertEqual({app,a,b},set(files));self.assertEqual(['kernel32.dll'],excluded)
    def test_virtual_system_api_contract_requires_successful_loader_resolution(self):
        with tempfile.TemporaryDirectory() as value:
            root=Path(value);binary=root/'bin';system=root/'system';binary.mkdir();system.mkdir();app=root/'app.exe';app.touch()
            name='api-ms-win-crt-runtime-l1-1-0.dll'
            files,excluded=dependency_closure([app],binary,system,lambda p:[name],lambda n:n==name)
            self.assertEqual([app],files);self.assertEqual([name],excluded)
            with self.assertRaisesRegex(RuntimeError,'Unresolved'):
                dependency_closure([app],binary,system,lambda p:[name],lambda n:False)
    def test_unrelated_import_cannot_be_whitelisted_as_virtual_system_api(self):
        with tempfile.TemporaryDirectory() as value:
            root=Path(value);binary=root/'bin';system=root/'system';binary.mkdir();system.mkdir();app=root/'app.exe';app.touch()
            with self.assertRaisesRegex(RuntimeError,'Unresolved'):
                dependency_closure([app],binary,system,lambda p:['random.dll'],lambda n:True)
    def test_unknown_dll_fails_closed(self):
        with tempfile.TemporaryDirectory() as value:
            root=Path(value);binary=root/'bin';system=root/'system';binary.mkdir();system.mkdir();app=root/'app.exe';app.touch()
            with self.assertRaisesRegex(RuntimeError,'Unresolved'):
                dependency_closure([app],binary,system,lambda p:['missing.dll'])
    def test_import_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as value:
            root=Path(value);binary=root/'bin';system=root/'system';binary.mkdir();system.mkdir();app=root/'app.exe';app.touch()
            with self.assertRaisesRegex(RuntimeError,'Unsafe'):
                dependency_closure([app],binary,system,lambda p:['../bad.dll'])
    def test_finalize_hashes_every_file_and_zip_root(self):
        with tempfile.TemporaryDirectory() as value:
            root=Path(value);runtime=root/'runtime';(runtime/'bin').mkdir(parents=True)
            (runtime/'bin/uxplay.exe').write_bytes(b'binary')
            (runtime/'mirrordock-engine.properties').write_text('format=1\n')
            (runtime/'README.txt').write_text('notice')
            archive=root/'engine.zip';finalize(runtime,archive)
            lines=(runtime/'files.sha256').read_text().splitlines()
            self.assertEqual(3,len(lines))
            for line in lines:
                digest,relative=line.split('  ',1);self.assertEqual(digest,hashlib.sha256((runtime/relative).read_bytes()).hexdigest())
            with zipfile.ZipFile(archive) as z:self.assertEqual({'bin/uxplay.exe','mirrordock-engine.properties','README.txt','files.sha256'},set(z.namelist()))
    def test_finalize_rejects_symlinks(self):
        with tempfile.TemporaryDirectory() as value:
            root=Path(value);runtime=root/'runtime';runtime.mkdir();outside=root/'outside';outside.write_bytes(b'secret')
            try:(runtime/'link').symlink_to(outside)
            except OSError:self.skipTest('symlinks unavailable')
            with self.assertRaisesRegex(RuntimeError,'symlink'):finalize(runtime,root/'engine.zip')

if __name__=='__main__':unittest.main()
