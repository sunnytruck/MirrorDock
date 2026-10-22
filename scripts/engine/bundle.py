#!/usr/bin/env python3
"""Assemble a minimal UCRT64 runtime, failing closed on unresolved DLL imports."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

UPSTREAM_COMMIT = "f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967"
PLUGINS = (
    "app", "coreelements", "playback", "typefindfunctions", "videoconvertscale",
    "autodetect", "audioconvert", "audioresample", "audioparsers", "videoparsersbad",
    "libav", "d3d11", "wasapi", "jpeg", "volume", "level", "pango", "imagefreeze", "videofilter",
)
ELEMENTS = (
    "appsrc", "queue", "decodebin", "h264parse", "avdec_h264", "avdec_aac", "avdec_alac",
    "videoconvert", "audioconvert", "audioresample", "autoaudiosink", "wasapisink",
    "d3d11videosink", "jpegdec", "volume", "level", "textoverlay", "imagefreeze", "videoflip", "videoscale",
)

def imports_from_objdump(output: str) -> list[str]:
    return re.findall(r"^\s*DLL Name:\s*([^\s]+)\s*$", output, re.MULTILINE | re.IGNORECASE)

def normalized_path(value: str | Path) -> Path:
    # MSYS2 sets ntpath.sep to '/' under MSYSTEM. Python 3.14 iterdir()
    # preserves Windows DirEntry.path (which may contain '\\') without reparsing
    # it, so raw child Paths need not compare/hash like paths built with '/'.
    # Reparse every filesystem boundary. Keep symlinks intact for copy_file's
    # rejection; resolve() must not replace the source path being packaged.
    return Path(os.path.abspath(os.path.normpath(os.fspath(value))))

def path_identity(value: str | Path) -> str:
    text = os.fspath(normalized_path(value))
    return text.replace("\\", "/").casefold() if os.name == "nt" else text

def index_files(directory: Path) -> dict[str, Path]:
    result: dict[str, Path] = {}
    for raw in normalized_path(directory).iterdir():
        path = normalized_path(raw)
        if not path.is_file():
            continue
        key = path.name.casefold()
        if key in result:
            raise RuntimeError(f"Ambiguous case-insensitive DLL filename: {path.name}")
        result[key] = path
    return result

def resolves_api_set(name: str) -> bool:
    # API set contracts can be virtual. Ask the Windows loader rather than
    # assuming every api-ms/ext-ms import exists or must have a physical file.
    if os.name != "nt" or not name.casefold().startswith(("api-ms-win-", "ext-ms-win-")):
        return False
    import ctypes
    from ctypes import wintypes
    kernel = ctypes.WinDLL("kernel32", use_last_error=True, winmode=0x00000800)
    kernel.LoadLibraryExW.argtypes = [wintypes.LPCWSTR, wintypes.HANDLE, wintypes.DWORD]
    kernel.LoadLibraryExW.restype = wintypes.HMODULE
    kernel.FreeLibrary.argtypes = [wintypes.HMODULE]
    kernel.FreeLibrary.restype = wintypes.BOOL
    handle = kernel.LoadLibraryExW(name, None, 0x00000800)  # LOAD_LIBRARY_SEARCH_SYSTEM32
    if not handle:
        return False
    kernel.FreeLibrary(handle)
    return True

def dependency_closure(seeds: list[Path], prefix_bin: Path, system: Path, inspect, api_resolver=resolves_api_set) -> tuple[list[Path], list[str]]:
    available = index_files(prefix_bin)
    system_files = index_files(system)
    pending = [normalized_path(seed) for seed in seeds]
    visited: dict[str, Path] = {}
    excluded: set[str] = set()
    while pending:
        binary = pending.pop()
        key = path_identity(binary)
        if key in visited:
            continue
        if not binary.is_file():
            raise RuntimeError(f"Required binary is missing: {binary.name}")
        visited[key] = binary
        for name in inspect(binary):
            if not name or name in (".", "..") or Path(name).name != name or any(c in name for c in "/\\:"):
                raise RuntimeError("Unsafe DLL import name")
            dependency = available.get(name.casefold())
            if dependency is not None:
                pending.append(dependency)
            elif name.casefold() in system_files or (name.casefold().startswith(("api-ms-win-", "ext-ms-win-")) and api_resolver(name)):
                excluded.add(name)
            else:
                raise RuntimeError(f"Unresolved DLL import {name} required by {binary.name}")
    return sorted(visited.values(), key=lambda p: str(p).casefold()), sorted(excluded, key=str.casefold)

def copy_file(source: Path, target: Path) -> None:
    source, target = normalized_path(source), normalized_path(target)
    if source.is_symlink():
        raise RuntimeError(f"Symlink must not enter runtime: {source.name}")
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and target.read_bytes() != source.read_bytes():
        raise RuntimeError(f"Conflicting runtime files: {target.name}")
    shutil.copy2(source, target)

def assemble(prefix: Path, executable: Path, output: Path, system: Path, objdump: Path, inventory: Path) -> None:
    prefix, executable, output, system, objdump, inventory = map(normalized_path, (prefix, executable, output, system, objdump, inventory))
    if output.exists():
        raise RuntimeError("Output directory already exists; use a fresh output path")
    plugins = [prefix / "lib/gstreamer-1.0" / f"libgst{name}.dll" for name in PLUGINS]
    seeds = [executable, prefix / "bin/gst-inspect-1.0.exe", prefix / "libexec/gstreamer-1.0/gst-plugin-scanner.exe", *plugins]
    def inspect(binary):
        result = subprocess.run([str(objdump), "-p", str(binary)], check=True, capture_output=True, text=True)
        return imports_from_objdump(result.stdout)
    closure, excluded = dependency_closure(seeds, prefix / "bin", system, inspect)
    output.mkdir(parents=True)
    origins = {}
    for source in closure:
        if source.resolve() == executable.resolve():
            relative = Path("bin/uxplay.exe")
        else:
            relative = source.relative_to(prefix)
            origins[relative.as_posix()] = relative.as_posix()
        copy_file(source, output / relative)
    # Fontconfig/Pango may need their data files for UxPlay's cover-art text.
    for resource in (prefix / "etc/fonts", prefix / "share/fontconfig"):
        if resource.is_dir():
            for raw in sorted(resource.rglob("*")):
                source = normalized_path(raw)
                if source.is_file():
                    source.resolve().relative_to(prefix.resolve())
                    relative=source.relative_to(prefix)
                    target=output / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(source.read_bytes())
                    origins[relative.as_posix()]=relative.as_posix()
    (output / "mirrordock-engine.properties").write_text(
        f"format=1\nplatform=windows-x64\nupstream.commit={UPSTREAM_COMMIT}\nexecutable=bin/uxplay.exe\n", encoding="utf-8")
    (output / "system-dlls.json").write_text(json.dumps(excluded, indent=2) + "\n", encoding="utf-8")
    inventory.write_text(json.dumps({"prefix_files": origins, "required_elements": ELEMENTS}, indent=2) + "\n", encoding="utf-8")

def finalize(runtime: Path, archive: Path) -> None:
    runtime, archive = normalized_path(runtime), normalized_path(archive)
    paths = sorted((normalized_path(p) for p in runtime.rglob("*") if p.is_file() and normalized_path(p) != runtime / "files.sha256"), key=lambda p: p.relative_to(runtime).as_posix())
    lines = []
    for path in paths:
        if path.is_symlink():
            raise RuntimeError("Runtime symlinks are not allowed")
        relative = path.relative_to(runtime).as_posix()
        if any(c in relative for c in "\r\n"):
            raise RuntimeError("Invalid runtime filename")
        lines.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {relative}\n")
    (runtime / "files.sha256").write_text("".join(lines), encoding="utf-8", newline="\n")
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as result:
        for raw in sorted(runtime.rglob("*")):
            path = normalized_path(raw)
            if path.is_file():
                result.write(path, path.relative_to(runtime).as_posix())

def verify_archive(archive: Path) -> int:
    """Reopen the actual runtime ZIP; check names, metadata, CRC and every SHA256."""
    with zipfile.ZipFile(normalized_path(archive)) as bundle:
        entries = {}
        folded = set()
        for entry in bundle.infolist():
            name = entry.filename
            if entry.is_dir():
                continue
            if (not name or any(c in name for c in "\\:\r\n") or
                    any(part in ("", ".", "..") for part in name.split("/"))):
                raise RuntimeError("Unsafe runtime ZIP path")
            if name.casefold() in folded:
                raise RuntimeError("Duplicate runtime ZIP path")
            folded.add(name.casefold())
            entries[name] = entry
        if "files.sha256" not in entries or entries["files.sha256"].file_size > 2_000_000:
            raise RuntimeError("Missing or oversized runtime ZIP manifest")
        hashes = {}
        for line in bundle.read("files.sha256").decode("utf-8").splitlines():
            if not re.fullmatch(r"[0-9a-f]{64}  .+", line):
                raise RuntimeError("Invalid runtime ZIP manifest")
            digest, name = line.split("  ", 1)
            if name in hashes:
                raise RuntimeError("Duplicate runtime ZIP manifest entry")
            hashes[name] = digest
        if set(hashes) != set(entries) - {"files.sha256"}:
            raise RuntimeError("Runtime ZIP contents do not match manifest")
        for name, expected in hashes.items():
            digest = hashlib.sha256()
            with bundle.open(entries[name]) as data:
                for chunk in iter(lambda: data.read(1024 * 1024), b""):
                    digest.update(chunk)
            if digest.hexdigest() != expected:
                raise RuntimeError(f"Runtime ZIP checksum mismatch: {name}")
        if "bin/uxplay.exe" not in entries or "mirrordock-engine.properties" not in entries:
            raise RuntimeError("Runtime ZIP is missing engine metadata or executable")
        metadata = dict(line.split("=", 1) for line in bundle.read("mirrordock-engine.properties").decode("utf-8").splitlines() if "=" in line)
        expected = {"format": "1", "platform": "windows-x64", "upstream.commit": UPSTREAM_COMMIT, "executable": "bin/uxplay.exe"}
        if any(metadata.get(key) != value for key, value in expected.items()):
            raise RuntimeError("Unexpected runtime ZIP metadata")
        return len(hashes)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prefix", type=Path)
    parser.add_argument("--executable", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--system", type=Path)
    parser.add_argument("--objdump", type=Path)
    parser.add_argument("--inventory", type=Path)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--verify-archive", type=Path)
    args = parser.parse_args()
    if args.verify_archive:
        count = verify_archive(args.verify_archive)
        print(f"Runtime ZIP verified: {count} files, all checksums passed")
    elif args.archive:
        if args.output is None:
            parser.error("Archive creation requires --output")
        finalize(args.output, args.archive)
    else:
        if any(value is None for value in (args.prefix, args.executable, args.output, args.system, args.objdump, args.inventory)):
            parser.error("Assembly requires prefix/executable/output/system/objdump/inventory")
        assemble(args.prefix, args.executable, args.output, args.system, args.objdump, args.inventory)
