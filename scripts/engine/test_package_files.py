import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from package_files import write_prefix_list


class PackageFilesTest(unittest.TestCase):
    def inventory(self, root, values):
        inventory = root / 'inventory.json'
        inventory.write_text(json.dumps({'prefix_files': {value: value for value in values}}, ensure_ascii=True), encoding='utf-8')
        return inventory

    def test_writes_utf8_lf_file_independently_of_windows_stdout_translation(self):
        with tempfile.TemporaryDirectory() as value:
            root = Path(value)
            paths = ['bin/avcodec-63.dll', 'lib/gstreamer-1.0/libgstlibav.dll', 'share/licenses/日本語/NOTICE']
            inventory = self.inventory(root, paths)
            output = root / 'prefix-files.txt'
            stdout_bytes = io.BytesIO()
            windows_stdout = io.TextIOWrapper(stdout_bytes, encoding='utf-8', newline='\r\n')
            try:
                with patch.object(sys, 'stdout', windows_stdout):
                    count = write_prefix_list(inventory, output)
                self.assertEqual(3, count)
                self.assertEqual(('\n'.join(paths) + '\n').encode('utf-8'), output.read_bytes())
                self.assertNotIn(b'\r', output.read_bytes())
            finally:
                windows_stdout.detach()

    def test_rejects_controls_and_non_relative_posix_paths(self):
        invalid = ['bin/avcodec-63.dll\r', 'bin/a\nb.dll', 'bin/a\tb.dll', 'bin/a\x00.dll',
                   'bin/a\x1f.dll', '/ucrt64/bin/a.dll', '../bin/a.dll', 'bin/../a.dll',
                   r'bin\a.dll', 'C:/bin/a.dll', 'bin/a.dll:stream', '', 'bin//a.dll', './bin/a.dll']
        with tempfile.TemporaryDirectory() as value:
            root = Path(value)
            for path in invalid:
                with self.subTest(path=repr(path)):
                    inventory = self.inventory(root, [path])
                    with self.assertRaises((ValueError, RuntimeError)):
                        write_prefix_list(inventory, root / 'prefix-files.txt')

    def test_rejects_invalid_inventory_schema_and_non_string_paths(self):
        with tempfile.TemporaryDirectory() as value:
            root = Path(value)
            inventory = root / 'inventory.json'
            for data in ({}, {'prefix_files': []}, {'prefix_files': {'a': 7}}, {'prefix_files': {'a': None}}):
                with self.subTest(data=data):
                    inventory.write_text(json.dumps(data), encoding='utf-8')
                    with self.assertRaises((ValueError, RuntimeError)):
                        write_prefix_list(inventory, root / 'prefix-files.txt')

    # Use the production shell caller. A native Python wrapper forces Windows
    # CRLF stdout even on Linux, so the original print/redirection bug reproduces.
    SHELL_FIXTURE = r'''
set -euo pipefail
python() {
  "$MD_TEST_PYTHON" -c '
import os, runpy, sys
sys.stdout.reconfigure(newline="\r\n")
args = sys.argv[1:]
sys.argv = args
if args[0] == "-":
    exec(compile(sys.stdin.read(), "<shell-stdin>", "exec"), {"__name__": "__main__"})
else:
    sys.path.insert(0, os.path.dirname(os.path.abspath(args[0])))
    runpy.run_path(args[0], run_name="__main__")
' "$@"
}
pacman() {
  printf '%s\0' "$@" >> "$MD_TEST_ARGUMENTS"
  if [ "$1" = '-Qqo' ]; then
    if [ "$2" = '/ucrt64/bin/avcodec-63.dll' ]; then
      printf '%s\n' 'mingw-w64-ucrt-x86_64-ffmpeg'
      return 0
    fi
    printf 'error: No package owns %q\n' "$2" >&2
    return 1
  fi
  if [ "$1" = '-Q' ]; then
    printf '%s\n' 'MD_TEST_STOP_AFTER_OWNERSHIP' >&2
    return 73
  fi
  printf '%s\n' 'MD_TEST_UNEXPECTED_PACMAN_CALL' >&2
  return 74
}
source "$1" "${@:2}"
'''

    def run_ownership_boundary(self, root, path):
        bash = shutil.which('bash')
        if bash is None:
            self.skipTest('Bash is required for the production ownership boundary')
        repository = Path(__file__).resolve().parents[2]
        runtime = root / 'runtime'
        sources = root / 'sources'
        upstream = root / 'upstream'
        inventory = self.inventory(root, [path])
        arguments = root / 'pacman-arguments.bin'
        environment = dict(os.environ)
        environment['MD_TEST_PYTHON'] = sys.executable
        environment['MD_TEST_ARGUMENTS'] = arguments.as_posix()
        command = [bash, '--noprofile', '--norc', '-c', self.SHELL_FIXTURE, 'ownership-test',
                   'scripts/engine/collect-sources.sh', runtime.as_posix(), inventory.as_posix(),
                   sources.as_posix(), upstream.as_posix()]
        result = subprocess.run(command, cwd=repository, env=environment, capture_output=True,
                                text=True, encoding='utf-8', timeout=30)
        return result, sources, arguments

    def test_real_shell_queries_exact_owner_when_native_stdout_uses_crlf(self):
        with tempfile.TemporaryDirectory() as value:
            result, sources, arguments = self.run_ownership_boundary(Path(value), 'bin/avcodec-63.dll')
            # The fixture deliberately stops at metadata collection, after the
            # real owner lookup and mapping have completed successfully.
            self.assertNotEqual(0, result.returncode)
            self.assertIn('MD_TEST_STOP_AFTER_OWNERSHIP', result.stderr)
            self.assertNotIn('No package owns', result.stderr)
            actual = arguments.read_bytes().split(b'\0')[:-1]
            self.assertEqual([b'-Qqo', b'/ucrt64/bin/avcodec-63.dll',
                              b'-Q', b'mingw-w64-ucrt-x86_64-ffmpeg'], actual)
            self.assertEqual(b'bin/avcodec-63.dll\n', (sources / 'prefix-files.txt').read_bytes())
            self.assertEqual(b'relative_path\tpackage\nbin/avcodec-63.dll\tmingw-w64-ucrt-x86_64-ffmpeg\n',
                             (sources / 'file-packages.tsv').read_bytes())

    def test_real_shell_does_not_allow_an_unknown_owner(self):
        with tempfile.TemporaryDirectory() as value:
            result, sources, arguments = self.run_ownership_boundary(Path(value), 'bin/unowned.dll')
            self.assertNotEqual(0, result.returncode)
            self.assertIn('No package owns', result.stderr)
            self.assertNotIn('MD_TEST_STOP_AFTER_OWNERSHIP', result.stderr)
            self.assertEqual([b'-Qqo', b'/ucrt64/bin/unowned.dll'], arguments.read_bytes().split(b'\0')[:-1])
            self.assertEqual(b'relative_path\tpackage\n', (sources / 'file-packages.tsv').read_bytes())
            self.assertFalse((sources / 'packages.tsv').exists())

    def test_real_shell_rejects_control_path_before_calling_pacman(self):
        with tempfile.TemporaryDirectory() as value:
            result, sources, arguments = self.run_ownership_boundary(Path(value), 'bin/avcodec-63.dll\r')
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(arguments.exists(), result.stderr)
            self.assertFalse((sources / 'packages.tsv').exists())


if __name__ == '__main__':
    unittest.main()
