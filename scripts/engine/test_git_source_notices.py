import copy
import hashlib
import io
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock

from bundle import normalized_path
from git_source_notices import read_git_notices


@unittest.skipUnless(shutil.which('git') and shutil.which('bsdtar'), 'git and bsdtar are required')
class GitSourceNoticesTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = normalized_path(self.temporary.name)
        self.repo = self.root / 'fixture.git'
        self.env = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
        config = self.root / 'empty-gitconfig'
        config.write_bytes(b'')
        self.env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=str(config),
                        GIT_CONFIG_SYSTEM=str(config), GIT_AUTHOR_NAME='Fixture',
                        GIT_AUTHOR_EMAIL='fixture@example.invalid', GIT_COMMITTER_NAME='Fixture',
                        GIT_COMMITTER_EMAIL='fixture@example.invalid')
        self.git('init', '--bare', str(self.repo))
        self.payload = b'Copyright fixture author\nExact license text.\n'
        blob = self.git('hash-object', '-w', '--stdin', input_data=self.payload).strip()
        tree = self.git('mktree', input_data=b'100644 blob ' + blob + b'\tCOPYING\n').strip()
        self.commit = self.git('commit-tree', tree.decode(), '-m', 'Notice fixture').decode().strip()
        self.prefix = 'mingw-w64-example/upstream'
        self.profile = {'git_archive': {'prefix': self.prefix, 'commit': self.commit}, 'notices': [
            {'member': 'COPYING', 'filename': 'COPYING',
             'sha256': hashlib.sha256(self.payload).hexdigest(), 'size': len(self.payload)}]}

    def git(self, *arguments, input_data=None):
        return subprocess.run(['git', '--no-replace-objects', f'--git-dir={self.repo}', *arguments],
                              input=input_data, capture_output=True, check=True, env=self.env).stdout

    def archive(self, *, extra=(), packed=False):
        if packed:
            self.git('update-ref', 'refs/heads/fixture', self.commit)
            self.git('repack', '-ad')
        archive = self.root / 'sources.tar.gz'
        with tarfile.open(archive, 'w:gz') as stream:
            # Older official allsource bare repositories keep this empty
            # metadata directory. Its contents remain forbidden.
            branches = tarfile.TarInfo(self.prefix + '/branches/')
            branches.type = tarfile.DIRTYPE
            stream.addfile(branches)
            for raw in sorted((self.repo / 'objects').rglob('*')):
                # Native MinGW Python rglob can return unparsed Windows
                # DirEntry paths under MSYSTEM; reparse before relative_to.
                path = normalized_path(raw)
                if path.is_file():
                    relative = path.relative_to(self.repo).as_posix()
                    stream.add(path, arcname=self.prefix + '/' + relative, recursive=False)
            # These deliberately hostile files must be ignored, never copied
            # or executed. Object reads must not use archived Git config.
            metadata = [('config', b'[core]\nrepositoryformatversion = 999\n'),
                        ('hooks/post-checkout.sample', b'exit 99\n'),
                        ('HEAD', b'ref: refs/heads/nonexistent\n')]
            for name, data in metadata:
                info = tarfile.TarInfo(self.prefix + '/' + name)
                info.size = len(data)
                stream.addfile(info, io.BytesIO(data))
            for info, data in extra:
                stream.addfile(info, io.BytesIO(data) if data is not None else None)
        return archive

    def entry(self, name, data=b'x', *, kind=tarfile.REGTYPE, linkname=''):
        info = tarfile.TarInfo(name)
        info.type = kind
        info.linkname = linkname
        info.size = len(data) if kind == tarfile.REGTYPE else 0
        return info, data if kind == tarfile.REGTYPE else None

    def test_loose_objects_read_exact_commit_without_config_hooks_or_inherited_git_environment(self):
        archive = self.archive()
        with mock.patch.dict(os.environ, {'GIT_OBJECT_DIRECTORY': str(self.root / 'missing'),
                                          'GIT_CONFIG_COUNT': '1', 'GIT_CONFIG_KEY_0': 'invalid',
                                          'GIT_CONFIG_VALUE_0': 'invalid'}):
            self.assertEqual({'COPYING': self.payload}, read_git_notices(archive, self.profile))

    def test_packed_objects_read_exact_commit(self):
        self.assertEqual({'COPYING': self.payload}, read_git_notices(self.archive(packed=True), self.profile))

    def test_missing_commit_rejected(self):
        profile = copy.deepcopy(self.profile)
        profile['git_archive']['commit'] = '0' * 40
        with self.assertRaises(RuntimeError):
            read_git_notices(self.archive(), profile)

    def test_notice_hash_and_size_must_match(self):
        archive = self.archive()
        for key, value in [('sha256', '0' * 64), ('size', len(self.payload) + 1)]:
            profile = copy.deepcopy(self.profile)
            profile['notices'][0][key] = value
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                read_git_notices(archive, profile)

    def test_notice_git_symlink_not_accepted_as_blob(self):
        blob = self.git('hash-object', '-w', '--stdin', input_data=self.payload).strip()
        tree = self.git('mktree', input_data=b'120000 blob ' + blob + b'\tCOPYING\n').strip()
        profile = copy.deepcopy(self.profile)
        profile['git_archive']['commit'] = self.git('commit-tree', tree.decode(), '-m', 'Symlink').decode().strip()
        with self.assertRaises(RuntimeError):
            read_git_notices(self.archive(), profile)

    def test_unsafe_duplicate_unknown_and_linked_archive_members_rejected(self):
        cases = [self.entry('../escape'), self.entry(self.prefix + '/objects/info/alternates'),
                 self.entry(self.prefix + '/branches/unreviewed'),
                 self.entry(self.prefix + '/objects/ab/invalid'),
                 self.entry(self.prefix + '/unknown-metadata'),
                 self.entry(self.prefix + '/config'),
                 self.entry(self.prefix + '/objects/' + 'ab/' + 'c' * 38,
                            kind=tarfile.SYMTYPE, linkname='/tmp/target'),
                 self.entry(self.prefix + '/objects/' + 'ab/' + 'd' * 38,
                            kind=tarfile.LNKTYPE, linkname=self.prefix + '/config')]
        for entry in cases:
            with self.subTest(member=entry[0].name), self.assertRaises(RuntimeError):
                read_git_notices(self.archive(extra=[entry]), self.profile)

    def test_object_size_and_total_limits_are_enforced(self):
        archive = self.archive()
        for limit in ['MAX_OBJECT', 'MAX_TOTAL', 'MAX_OBJECTS']:
            with self.subTest(limit=limit), mock.patch('git_source_notices.' + limit, 1), self.assertRaises(RuntimeError):
                read_git_notices(archive, self.profile)

    def test_missing_pack_index_rejected(self):
        archive = self.archive(packed=True)
        broken = self.root / 'missing-index.tar.gz'
        with tarfile.open(archive) as source, tarfile.open(broken, 'w:gz') as destination:
            for member in source:
                if not member.name.endswith('.idx'):
                    destination.addfile(member, source.extractfile(member))
        with self.assertRaises(RuntimeError):
            read_git_notices(broken, self.profile)

    def test_unsafe_profile_and_missing_notice_rejected(self):
        archive = self.archive()
        for field, value in [('member', '../COPYING'), ('filename', 'sub/COPYING'),
                             ('member', 'MISSING'), ('sha256', 'not-a-hash')]:
            profile = copy.deepcopy(self.profile)
            profile['notices'][0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(RuntimeError):
                read_git_notices(archive, profile)


if __name__ == '__main__':
    unittest.main()
