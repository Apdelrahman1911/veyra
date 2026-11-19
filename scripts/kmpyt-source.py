#!/usr/bin/env python3
"""Refresh or verify the tracked kmpYT snapshot without contacting Maven Central."""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / "third_party/kmpYT"
MANIFEST = VENDOR / "source-tree.json"
EXPORT = ("LICENSE", "THIRD_PARTY_NOTICES.md", "README.md", "kmpyt")
VERSION_TAG = re.compile(r"(?:source-)?v((?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*)){2})")
CATALOG_VERSION = re.compile(r'^kmpyt = "([^"]+)"$', re.MULTILINE)


def fingerprint(files):
    entries = {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}
    return hashlib.sha256(json.dumps(entries, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def snapshot():
    files = {}
    for name in EXPORT:
        path = VENDOR / name
        if path.is_symlink():
            raise ValueError(f"Linked kmpYT snapshot input {name}")
        if name != "kmpyt":
            files[name] = path.read_bytes()
            continue
        if not path.is_dir():
            raise ValueError("Missing checked-in kmpYT module")
        for directory, subdirectories, filenames in os.walk(path):
            parent = Path(directory)
            if parent == path and "build" in subdirectories:
                subdirectories.remove("build")
            for child in (*subdirectories, *filenames):
                if (parent / child).is_symlink():
                    raise ValueError("Linked input in checked-in kmpYT module")
            for name in filenames:
                file = parent / name
                files[file.relative_to(VENDOR).as_posix()] = file.read_bytes()
    return files


def catalog_version():
    # Xcode invokes bootstrap with its bundled Python, which can predate tomllib.
    versions = CATALOG_VERSION.findall((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))
    if len(versions) != 1:
        raise ValueError("Expected one kmpYT catalog version")
    return versions[0]


def check():
    receipt = json.loads(MANIFEST.read_bytes())
    revision = (ROOT / "gradle/kmpyt.commit").read_text(encoding="ascii").strip()
    if (receipt["revision"] != revision or not re.fullmatch("[0-9a-f]{40}", revision)
            or receipt["version"] != catalog_version()):
        raise ValueError("kmpYT version and commit must agree with the source snapshot")
    if receipt["treeSha256"] != fingerprint(snapshot()):
        raise ValueError("Checked-in kmpYT files differ from the recorded upstream snapshot")
    print(f"kmpYT {receipt['version']} from checked-in source at {revision}")


def refresh(tag):
    match = VERSION_TAG.fullmatch(tag)
    if not match:
        raise ValueError("Use a vMAJOR.MINOR.PATCH or source-vMAJOR.MINOR.PATCH upstream tag")
    if not MANIFEST.is_file() and any((VENDOR / name).exists() for name in EXPORT):
        raise ValueError("An existing kmpYT snapshot has no source receipt")
    current = snapshot() if MANIFEST.is_file() else {}
    if current and fingerprint(current) != json.loads(MANIFEST.read_bytes())["treeSha256"]:
        raise ValueError("Resolve edits in the old kmpYT snapshot before refreshing it")
    upstream = ROOT / ".work/kmpYT"
    git = ["git", "-C", str(upstream)]
    revision = subprocess.check_output(
        git + ["rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}"], text=True).strip()
    archive = subprocess.check_output(git + ["archive", "--format=tar", revision, "--", *EXPORT])
    files = {}
    with tarfile.open(fileobj=io.BytesIO(archive)) as source:
        for entry in source:
            path = PurePosixPath(entry.name)
            if path.is_absolute() or ".." in path.parts or path.parts[0] not in EXPORT:
                raise ValueError("Unexpected path in the upstream kmpYT archive")
            if entry.isdir():
                continue
            if not entry.isfile() or entry.name in files:
                raise ValueError("Expected distinct regular files in the upstream kmpYT archive")
            files[entry.name] = source.extractfile(entry).read()
    if not all(name in files for name in (*EXPORT[:3], "kmpyt/build.gradle.kts")):
        raise ValueError("The upstream tag does not contain the kmpYT module and notices")

    version = match[1]
    catalog = ROOT / "gradle/libs.versions.toml"
    previous = catalog.read_text(encoding="utf-8")
    content, count = CATALOG_VERSION.subn(f'kmpyt = "{version}"', previous)
    if count != 1:
        raise ValueError("Expected one kmpYT catalog version")
    for name in current.keys() - files.keys():
        (VENDOR / name).unlink()
    for name, data in files.items():
        path = VENDOR / name
        path.parent.mkdir(parents=True, exist_ok=True)
        if current.get(name) != data:
            path.write_bytes(data)
    receipt = {"revision": revision, "version": version, "treeSha256": fingerprint(files)}
    MANIFEST.write_bytes((json.dumps(receipt, indent=2) + "\n").encode("utf-8"))
    (ROOT / "gradle/kmpyt.commit").write_bytes((revision + "\n").encode("ascii"))
    catalog.write_bytes(content.encode("utf-8"))
    check()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check", "refresh"))
    parser.add_argument("tag", nargs="?")
    args = parser.parse_args()
    if (args.command == "refresh") != (args.tag is not None):
        parser.error("refresh requires a tag and check takes no tag")
    try:
        if args.command == "refresh":
            refresh(args.tag)
        else:
            check()
    except (KeyError, OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"kmpYT source: {error}\n")
