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

def index_files(directory: Path) -> dict[str, Path]:
    return {p.name.casefold(): p for p in directory.iterdir() if p.is_file()}

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
    pending = list(seeds)
    visited: dict[str, Path] = {}
    excluded: set[str] = set()
    while pending:
        binary = pending.pop()
        key = str(binary.resolve()).casefold()
        if key in visited:
            continue
        if not binary.is_file():
            raise RuntimeError(f"Required binary is missing: {binary.name}")
        visited[key] = binary
        for name in inspect(binary):
            if Path(name).name != name or "/" in name or "\\" in name:
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
    if source.is_symlink():
        raise RuntimeError(f"Symlink must not enter runtime: {source.name}")
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and target.read_bytes() != source.read_bytes():
        raise RuntimeError(f"Conflicting runtime files: {target.name}")
    shutil.copy2(source, target)

def assemble(prefix: Path, executable: Path, output: Path, system: Path, objdump: Path, inventory: Path) -> None:
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
            for source in sorted(resource.rglob("*")):
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
    paths = sorted((p for p in runtime.rglob("*") if p.is_file() and p != runtime / "files.sha256"), key=lambda p: p.relative_to(runtime).as_posix())
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
        for path in sorted(runtime.rglob("*")):
            if path.is_file():
                result.write(path, path.relative_to(runtime).as_posix())

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prefix", type=Path)
    parser.add_argument("--executable", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--system", type=Path)
    parser.add_argument("--objdump", type=Path)
    parser.add_argument("--inventory", type=Path)
    parser.add_argument("--archive", type=Path)
    args = parser.parse_args()
    if args.archive:
        finalize(args.output, args.archive)
    else:
        if any(value is None for value in (args.prefix, args.executable, args.system, args.objdump, args.inventory)):
            parser.error("Assembly requires prefix/executable/system/objdump/inventory")
        assemble(args.prefix, args.executable, args.output, args.system, args.objdump, args.inventory)
