"""Exercise the real Bash helper with isolated pacman/config/signature stubs."""
import io
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest

HELPER = Path(__file__).with_name('package_archive.sh').resolve()
PACKAGE = 'mingw-w64-ucrt-x86_64-aom'
VERSION = '3.15.2-1'


class PackageArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='package archive ')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root / 'stubs'
        self.bin.mkdir()
        self.cache = self.root / 'configured cache'
        self.cache.mkdir()
        self.download = self.root / 'download cache'
        self.seed = self.root / 'download-seed.pkg.tar.gz'
        self.log = self.root / 'commands.log'
        self.environment = dict(os.environ)
        self.environment.update({
            'PATH': str(self.bin) + os.pathsep + os.environ['PATH'],
            'MOCK_CACHE_DIRS': str(self.cache),
            'MOCK_PACKAGE': PACKAGE,
            'MOCK_VERSION': VERSION,
            'MOCK_REPO_VERSION': VERSION,
            'MOCK_SEED': str(self.seed),
            'MOCK_LOG': str(self.log),
            'MOCK_SIG_FAIL': '0',
            'MOCK_DOWNLOAD_FAIL': '0',
            'MOCK_POLICY': 'PackageRequired\nPackageTrustedOnly\nDatabaseOptional\nDatabaseTrustedOnly',
        })
        self.archive(self.seed)
        self.stub('pacman-conf', '''
if [[ "$1" == CacheDir ]]; then printf '%s\\n' "$MOCK_CACHE_DIRS"
elif [[ "$1" == --repo && "$2" == ucrt64 && "$3" == SigLevel ]]; then printf '%s\\n' "$MOCK_POLICY"
else printf 'Unexpected pacman-conf command\\n' >&2; exit 90; fi
''')
        self.stub('pacman', '''
printf 'pacman' >> "$MOCK_LOG"; printf ' <%s>' "$@" >> "$MOCK_LOG"; printf '\\n' >> "$MOCK_LOG"
if [[ "$1" == -Sp ]]; then
  printf 'ucrt64 %s %s\\n' "$MOCK_PACKAGE" "$MOCK_REPO_VERSION"
elif [[ "$1" == -Sw ]]; then
  [[ "$MOCK_DOWNLOAD_FAIL" == 0 && "$MOCK_SIG_FAIL" == 0 ]] || { printf 'PGP signature validation failed\\n' >&2; exit 42; }
  destination=''
  while (( $# )); do
    if [[ "$1" == --cachedir ]]; then destination=$2; shift; fi
    shift
  done
  [[ -n "$destination" ]] || exit 92
  mkdir -p "$destination"
  target="$destination/$MOCK_PACKAGE-$MOCK_REPO_VERSION-any.pkg.tar.gz"
  if [[ ! -f "$target" ]]; then cp "$MOCK_SEED" "$target"; fi
  printf 'download and required PGP validation complete\\n'
else printf 'Unexpected pacman command\\n' >&2; exit 91; fi
''')
        self.stub('pacman-key', '''
printf 'pacman-key' >> "$MOCK_LOG"; printf ' <%s>' "$@" >> "$MOCK_LOG"; printf '\\n' >> "$MOCK_LOG"
printf 'Unexpected standalone keyring access\\n' >&2
exit 43
''')
        self.stub('bsdtar', 'exec tar "$@"\n')

    def stub(self, name, body):
        file = self.bin / name
        file.write_text('#!/usr/bin/env bash\nset -euo pipefail\n' + body, encoding='utf-8', newline='\n')
        file.chmod(0o755)

    def archive(self, path, package=PACKAGE, version=VERSION, signature=False):
        path.parent.mkdir(parents=True, exist_ok=True)
        data = f'pkgname = {package}\npkgver = {version}\npkgbase = mingw-w64-aom\n'.encode()
        with tarfile.open(path, 'w:gz') as archive:
            entry = tarfile.TarInfo('.PKGINFO')
            entry.size = len(data)
            archive.addfile(entry, io.BytesIO(data))
        if signature:
            Path(str(path) + '.sig').write_bytes(b'test signature; stub verifier controls validity')
        return path

    def run_helper(self, version=VERSION):
        return subprocess.run([
            shutil.which('bash') or 'bash', '-c',
            'set -euo pipefail; source "$1"; obtain_package_archive "$2" "$3" "$4"',
            'package-archive-test', HELPER.as_posix(), PACKAGE, version, self.download.as_posix(),
        ], env=self.environment, capture_output=True, text=True, timeout=15)

    def commands(self):
        return self.log.read_text() if self.log.exists() else ''

    def test_reuses_trusted_exact_archive_from_configured_cache(self):
        file = self.archive(self.cache / f'{PACKAGE}-{VERSION}-any.pkg.tar.gz', signature=True)
        result = self.run_helper()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(result.stdout.strip().endswith(file.name))
        self.assertEqual(1, result.stdout.count('\n'))
        self.assertIn('download and required PGP validation', result.stderr)
        self.assertIn(' <-Sw>', self.commands())
        self.assertNotIn('pacman-key', self.commands())

    def test_empty_setup_action_cache_downloads_exact_version_without_install(self):
        result = self.run_helper()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.download / f'{PACKAGE}-{VERSION}-any.pkg.tar.gz').is_file())
        self.assertEqual(1, result.stdout.count('\n'))
        self.assertIn('download and required PGP validation', result.stderr)
        self.assertIn(' <-Sw> <--noconfirm> <--cachedir>', self.commands())
        self.assertNotIn(' <-S>', self.commands())
        self.assertNotIn(' <-Sy>', self.commands())

    def test_repository_version_mismatch_never_downloads_newer_package(self):
        self.environment['MOCK_REPO_VERSION'] = '3.16.0-1'
        result = self.run_helper()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('differs from installed', result.stderr)
        self.assertNotIn(' <-Sw>', self.commands())
        self.assertEqual('', result.stdout)

    def test_cached_sidecar_cannot_bypass_repository_version_check(self):
        self.archive(self.cache / f'{PACKAGE}-{VERSION}-any.pkg.tar.gz', signature=True)
        self.environment['MOCK_REPO_VERSION'] = '3.16.0-1'
        result = self.run_helper()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('differs from installed', result.stderr)
        self.assertNotIn(' <-Sw>', self.commands())
        self.assertNotIn('pacman-key', self.commands())
        self.assertEqual('', result.stdout)

    def test_duplicate_archives_in_distinct_caches_are_rejected(self):
        other = self.root / 'second cache'
        self.archive(self.cache / f'{PACKAGE}-{VERSION}-any.pkg.tar.gz', signature=True)
        self.archive(other / f'{PACKAGE}-{VERSION}-any.pkg.tar.gz', signature=True)
        self.environment['MOCK_CACHE_DIRS'] = str(self.cache) + '\n' + str(other)
        result = self.run_helper()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Multiple exact archives', result.stderr)
        self.assertEqual('', result.stdout)

    def test_cached_package_failing_trusted_database_signature_is_rejected_without_retry(self):
        self.archive(self.cache / f'{PACKAGE}-{VERSION}-any.pkg.tar.gz', signature=True)
        # Model libalpm rejecting the archive against the trusted sync DB signature.
        # An arbitrary detached sidecar is not authoritative when DB PGP data exists.
        self.environment['MOCK_SIG_FAIL'] = '1'
        result = self.run_helper()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('PGP signature validation failed', result.stderr)
        self.assertIn('Verified package download failed', result.stderr)
        self.assertEqual(1, self.commands().count(' <-Sw>'))
        self.assertNotIn('pacman-key', self.commands())
        self.assertEqual('', result.stdout)

    def test_download_signature_failure_is_fatal(self):
        self.environment['MOCK_DOWNLOAD_FAIL'] = '1'
        result = self.run_helper()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Verified package download failed', result.stderr)
        self.assertEqual('', result.stdout)

    def test_optional_or_untrusted_signature_policy_is_rejected(self):
        for policy in ('PackageOptional\nPackageTrustedOnly', 'PackageRequired\nPackageTrustAll', 'PackageNever'):
            with self.subTest(policy=policy):
                self.environment['MOCK_POLICY'] = policy
                result = self.run_helper()
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn(' <-Sw>', self.commands())
                self.assertEqual('', result.stdout)

    def test_same_name_archive_with_wrong_internal_version_is_rejected(self):
        self.archive(self.cache / f'{PACKAGE}-{VERSION}-any.pkg.tar.gz', version='3.15.1-1', signature=True)
        result = self.run_helper()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('metadata does not match installed', result.stderr)
        self.assertEqual('', result.stdout)

    def test_msys_epoch_tilde_is_preserved(self):
        version = '1~3.15.2-1'
        self.environment['MOCK_REPO_VERSION'] = version
        file = self.archive(self.cache / f'{PACKAGE}-{version}-any.pkg.tar.gz', version=version, signature=True)
        result = self.run_helper(version)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(result.stdout.strip().endswith(file.name))

    def test_cached_archive_without_sidecar_is_revalidated_by_required_pacman_policy(self):
        file = self.archive(self.cache / f'{PACKAGE}-{VERSION}-any.pkg.tar.gz')
        result = self.run_helper()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(result.stdout.strip().endswith(file.name))
        self.assertIn(' <-Sw>', self.commands())
        self.assertNotIn('pacman-key', self.commands())


if __name__ == '__main__':
    unittest.main()
