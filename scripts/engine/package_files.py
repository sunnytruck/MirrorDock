"""Write the native-Python -> MSYS Bash inventory boundary with explicit LF bytes."""
from __future__ import annotations
import argparse
import json
from pathlib import Path


def write_prefix_list(inventory: Path, output: Path) -> int:
    data = json.loads(inventory.read_text(encoding="utf-8"))
    mapping = data.get("prefix_files")
    if not isinstance(mapping, dict) or not mapping:
        raise ValueError("Expected a nonempty prefix_files mapping")
    paths = list(mapping.values())
    for value in paths:
        if (not isinstance(value, str) or not value or
                any(ord(char) < 32 or ord(char) == 127 for char in value) or
                any(char in value for char in "\\:") or
                any(part in ("", ".", "..") for part in value.split("/"))):
            raise ValueError("Unsafe prefix inventory path")
    # print()/redirected sys.stdout on UCRT64 Python emits CRLF. Bash read
    # removes LF but preserves CR, changing the pacman -Qo lookup argument.
    # This file is a protocol, not console output; write LF explicitly.
    output.write_text("".join(value + "\n" for value in paths), encoding="utf-8", newline="\n")
    return len(paths)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inventory", type=Path)
    parser.add_argument("output", type=Path)
    arguments = parser.parse_args()
    write_prefix_list(arguments.inventory, arguments.output)
