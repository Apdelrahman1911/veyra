#!/usr/bin/env python3
"""Reproduce checked-in helper file hashes from the audited, whole-pinned archives.

No downloads, archive extraction, or executable invocation. Inputs and outputs must stay
inside Veyra. The generated inventory is packaged with the application, never trusted
from its mutable data directory. Licensing closure is a separate release gate.
"""
import hashlib
import json
from pathlib import Path, PurePosixPath
import sys
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
INPUT = ROOT / ".work/desktop-audio/helper-research"
OUTPUT = ROOT / "composeApp/src/desktopMain/resources/dev/veyra/desktop/helpers"


def digest(stream):
    value = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        value.update(chunk)
    return value.hexdigest()


def safe_name(value):
    path = PurePosixPath(value.rstrip("/"))
    assert value and not path.is_absolute() and ".." not in path.parts
    assert "\\" not in value and ":" not in value and not any(ord(c) < 32 for c in value)
    return str(path)


def build(record, component):
    source = INPUT / record["archive"]
    assert source.stat().st_size == record["size"]
    with source.open("rb") as stream:
        assert digest(stream) == record["sha256"], "Archive integrity mismatch"
    platform = {"win-x64": "windows-x64", "darwin-x64": "macos-x64", "darwin-arm64": "macos-arm64",
                "linux": "linux-x64", "windows": "windows-x64"}.get(record["platform"], record["platform"])
    if component == "node":
        archive_root = record["archive"].removesuffix(".tar.gz").removesuffix(".zip")
        executable = "node.exe" if platform.startswith("windows") else "node"
        selection = {("node.exe" if platform.startswith("windows") else "bin/node"): executable, "LICENSE": "LICENSE"}
        version = "22.23.4"
    else:
        archive_root = {"linux-x64": "chrome-linux", "windows-x64": "chrome-win"}.get(platform, "chrome-mac")
        executable = {"linux-x64": "chrome", "windows-x64": "chrome.exe"}.get(platform, "Chromium.app/Contents/MacOS/Chromium")
        selection = {}
        version = record["revision"]
    entries = []
    seen = set()

    def entry(name, size, mode, directory, link, stream):
        name = safe_name(name)
        folded = name.casefold()
        assert folded not in seen, "Duplicate/case-colliding archive entry"
        seen.add(folded)
        assert name == archive_root or name.startswith(archive_root + "/")
        assert not mode & 0o7000, "Privileged archive mode"
        relative = name[len(archive_root):].lstrip("/")
        if directory or not relative or (selection and relative not in selection):
            return
        target = selection.get(relative, relative)
        if link is not None:
            assert len(link) <= 4096 and not PurePosixPath(link).is_absolute() and "\\" not in link and ":" not in link
            entries.append({"path": target, "size": 0, "link": link, "executable": False})
        else:
            assert stream is not None
            entries.append({"path": target, "size": size, "sha256": digest(stream), "executable": bool(mode & 0o111) or target.endswith(".exe")})

    if record["archive"].endswith(".zip"):
        with zipfile.ZipFile(source) as archive:
            for info in archive.infolist():
                mode = info.external_attr >> 16
                assert mode & 0o170000 in (0, 0o100000, 0o040000, 0o120000)
                link = archive.read(info).decode("utf-8") if mode & 0o170000 == 0o120000 else None
                with archive.open(info) as stream:
                    entry(info.filename, info.file_size, mode, info.is_dir(), link, stream)
    else:
        with tarfile.open(source, "r|gz") as archive:
            for info in archive:
                assert info.isfile() or info.isdir() or info.issym(), "Unsupported tar entry"
                stream = archive.extractfile(info) if info.isfile() else None
                entry(info.name, info.size, info.mode, info.isdir(), info.linkname if info.issym() else None, stream)
    if selection:
        assert {item["path"] for item in entries} == set(selection.values())
    result = dict(platform=platform, component=component, version=version, url=record["url"],
                  archiveSha256=record["sha256"], archiveBytes=record["size"],
                  expandedBytes=record["expandedBytes"], archiveRoot=archive_root,
                  format="zip" if record["archive"].endswith(".zip") else "tar.gz",
                  executable=executable, selection=selection, entries=sorted(entries, key=lambda item: item["path"]))
    return f"{component}-{platform}.json", (json.dumps(result, indent=2, ensure_ascii=False) + "\n").encode()


def main():
    assert OUTPUT.resolve().is_relative_to(ROOT)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    hashes = {}
    for component in ("node", "chromium"):
        for record in json.loads((INPUT / f"{component}-verified.json").read_text()):
            name, data = build(record, component)
            (OUTPUT / name).write_bytes(data)
            hashes[name] = hashlib.sha256(data).hexdigest()
            print(name, len(data), hashes[name], flush=True)
    (INPUT / "helper-manifest-hashes.json").write_text(json.dumps(hashes, indent=2) + "\n")


if __name__ == "__main__":
    main()
