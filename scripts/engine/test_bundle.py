import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from bundle import dependency_closure, finalize, imports_from_objdump, index_files, verify_archive, UPSTREAM_COMMIT

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
    def test_index_paths_are_interchangeable_with_constructed_paths(self):
        with tempfile.TemporaryDirectory() as value:
            directory=Path(value)/'DLL Folder';directory.mkdir()
            expected=directory/'A.DLL';expected.write_bytes(b'original')
            # Keep Path keys: this detects MSYS2's mixed-separator hashing bug.
            known={expected:b'original'}
            actual=index_files(directory)['a.dll']
            self.assertEqual(known[actual],actual.read_bytes())
    @unittest.skipUnless(os.name == 'nt', 'Windows filesystem aliases require Windows')
    def test_windows_separator_aliases_and_case_share_one_dependency(self):
        with tempfile.TemporaryDirectory() as value:
            root=Path(value);binary=root/'DLL Folder';system=root/'system';binary.mkdir();system.mkdir()
            app=root/'app.exe';dll=binary/'Mixed.DLL'
            app.touch();dll.touch()
            deps={app:['mIxEd.DlL'],dll:['MIXED.dll']};calls=[]
            def inspect(path):
                calls.append(path)
                return deps[path]
            aliases=[Path(str(app).replace('\\','/')),Path(str(app).replace('/','\\'))]
            files,excluded=dependency_closure(aliases,Path(str(binary).replace('\\','/')),Path(str(system).replace('/','\\')),inspect)
            self.assertEqual({app,dll},set(files));self.assertEqual(2,len(calls));self.assertEqual([],excluded)
    def test_relative_seeds_and_prefix_are_normalized_before_inspection(self):
        with tempfile.TemporaryDirectory() as value:
            root=Path(value);binary=root/'bin';system=root/'system';binary.mkdir();system.mkdir()
            app=root/'app.exe';dll=binary/'a.dll';app.touch();dll.touch()
            deps={app:['A.dll'],dll:[]}
            relative=lambda path:Path(os.path.relpath(path,Path.cwd()))
            files,_=dependency_closure([relative(app),relative(app)],relative(binary),relative(system),lambda path:deps[path])
            self.assertEqual({app,dll},set(files))
            self.assertTrue(all(path.is_absolute() for path in files))
    def test_case_colliding_dll_names_fail_instead_of_silently_overwriting(self):
        with tempfile.TemporaryDirectory() as value:
            root=Path(value);first=root/'a.dll';second=root/'A.DLL';first.touch();second.touch()
            # NTFS can opt into case sensitivity; simulate its two directory entries
            # also on ordinary case-insensitive Windows test directories.
            with patch.object(Path,'iterdir',return_value=iter((first,second))):
                with self.assertRaisesRegex(RuntimeError,'Ambiguous'):
                    index_files(root)
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
            for name in ('../bad.dll',r'..\bad.dll','C:bad.dll','good.dll:stream','', '.', '..'):
                with self.subTest(name=name), self.assertRaisesRegex(RuntimeError,'Unsafe'):
                    dependency_closure([app],binary,system,lambda p:[name])
    def test_finalize_hashes_every_file_and_zip_root(self):
        with tempfile.TemporaryDirectory() as value:
            root=Path(value);runtime=root/'runtime';(runtime/'bin').mkdir(parents=True)
            (runtime/'bin/uxplay.exe').write_bytes(b'binary')
            (runtime/'mirrordock-engine.properties').write_text('format=1\n')
            (runtime/'README.txt').write_text('notice')
            nested=runtime/'share/licenses/files.sha256';nested.parent.mkdir(parents=True);nested.write_text('upstream manifest')
            archive=root/'engine.zip';finalize(runtime,archive)
            lines=(runtime/'files.sha256').read_text().splitlines()
            self.assertEqual(4,len(lines))
            for line in lines:
                digest,relative=line.split('  ',1);self.assertEqual(digest,hashlib.sha256((runtime/relative).read_bytes()).hexdigest())
            with zipfile.ZipFile(archive) as z:self.assertEqual({'bin/uxplay.exe','mirrordock-engine.properties','README.txt','share/licenses/files.sha256','files.sha256'},set(z.namelist()))
    def runtime_archive(self, root):
        runtime=root/'runtime';(runtime/'bin').mkdir(parents=True)
        (runtime/'bin/uxplay.exe').write_bytes(b'MZ-test-binary')
        (runtime/'mirrordock-engine.properties').write_text(f'format=1\nplatform=windows-x64\nupstream.commit={UPSTREAM_COMMIT}\nexecutable=bin/uxplay.exe\n',encoding='utf-8')
        archive=root/'engine.zip';finalize(runtime,archive)
        return archive
    def test_actual_zip_is_reopened_and_verified(self):
        with tempfile.TemporaryDirectory() as value:
            self.assertEqual(2,verify_archive(self.runtime_archive(Path(value))))
    def test_zip_with_changed_payload_fails_manifest_verification(self):
        with tempfile.TemporaryDirectory() as value:
            root=Path(value);archive=self.runtime_archive(root);tampered=root/'tampered.zip'
            with zipfile.ZipFile(archive) as original,zipfile.ZipFile(tampered,'w') as changed:
                for name in original.namelist():
                    changed.writestr(name,b'changed' if name=='bin/uxplay.exe' else original.read(name))
            with self.assertRaisesRegex(RuntimeError,'checksum mismatch'):
                verify_archive(tampered)
    def test_zip_with_unlisted_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as value:
            archive=self.runtime_archive(Path(value))
            with zipfile.ZipFile(archive,'a') as changed:changed.writestr('bin/unlisted.dll',b'not in manifest')
            with self.assertRaisesRegex(RuntimeError,'contents do not match'):
                verify_archive(archive)
    def test_finalize_rejects_symlinks(self):
        with tempfile.TemporaryDirectory() as value:
            root=Path(value);runtime=root/'runtime';runtime.mkdir();outside=root/'outside';outside.write_bytes(b'secret')
            try:(runtime/'link').symlink_to(outside)
            except OSError:self.skipTest('symlinks unavailable')
            with self.assertRaisesRegex(RuntimeError,'symlink'):finalize(runtime,root/'engine.zip')

if __name__=='__main__':unittest.main()
