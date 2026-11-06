#!/usr/bin/env python3
"""Prepare matching-host desktop resources, never a developer-PATH fallback.

Run through scripts/dev.sh. A verified source-built local-remux FFmpeg and, on
Windows/macOS, Veyra's media-session helper are placed on the classpath before
the production JAR is made. Plain compilation/unit tests do not build native
tools. This receipt is an integrity/build record, not a signature or a claim
that the complete application's redistribution obligations are satisfied.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import shutil
import stat
import struct
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "native"))
import owned_process

TARGETS = ("linux-x64", "windows-x64", "macos-x64", "macos-arm64")
MAX_FILE = 64 * 1024 * 1024
MAX_SESSION_HELPER = 16 * 1024 * 1024
MANIFEST = "veyra-native-bundle.json"
FFMPEG_NOTICES = ("COPYING.LGPLv2.1", "COPYING.LGPLv3", "COPYING.GPLv2", "COPYING.GPLv3", "LICENSE.md", "CREDITS")


def owned(path: Path, root: Path = ROOT) -> Path:
    value = path.resolve()
    if not value.is_relative_to(root.resolve()):
        raise ValueError("Out-of-repository native path rejected")
    return value


def work(path: Path, root: Path = ROOT) -> Path:
    value = owned(path, root)
    if not value.is_relative_to((root / ".work").resolve()):
        raise ValueError("Native outputs must stay in project .work")
    return value


def host_target(system: str, arch: str) -> str:
    arch = arch.lower()
    if system == "Linux" and arch in ("amd64", "x86_64"):
        return "linux-x64"
    if system == "Windows" and arch in ("amd64", "x86_64"):
        return "windows-x64"
    if system == "Darwin" and arch in ("amd64", "x86_64", "arm64", "aarch64"):
        return "macos-" + ("arm64" if arch in ("arm64", "aarch64") else "x64")
    raise ValueError("No matching-host desktop native profile")


def file_hash(path: Path, root: Path = ROOT) -> str:
    owned(path, root)
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAX_FILE:
        raise ValueError("Native input is not a bounded regular nonempty file")
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        for block in iter(lambda: source.read(65_536), b""):
            size += len(block)
            if size > MAX_FILE:
                raise ValueError("Native input grew beyond its bound")
            digest.update(block)
    return digest.hexdigest()


def binary_target(path: Path, target: str, root: Path = ROOT) -> None:
    """Check native architecture without executing unverified output."""
    file_hash(path, root)
    with path.open("rb") as source:
        header = source.read(64)
        if target == "linux-x64":
            valid = len(header) == 64 and header[:6] == b"\x7fELF\x02\x01" and struct.unpack_from("<H", header, 18)[0] == 62
        elif target == "windows-x64":
            valid = False
            if len(header) == 64 and header[:2] == b"MZ":
                offset = struct.unpack_from("<I", header, 60)[0]
                if 64 <= offset <= min(1024 * 1024, path.stat().st_size - 26):
                    source.seek(offset)
                    pe = source.read(26)
                    valid = pe[:6] == b"PE\0\0\x64\x86" and pe[24:26] == b"\x0b\x02"
        elif target in ("macos-x64", "macos-arm64"):
            machine = 0x01000007 if target.endswith("x64") else 0x0100000C
            valid = len(header) >= 32 and header[:4] == b"\xcf\xfa\xed\xfe" and struct.unpack_from("<I", header, 4)[0] == machine
        else:
            valid = False
    if not valid:
        raise ValueError("Built executable/helper architecture does not match the package")


def relative_name(value: str) -> str:
    if not isinstance(value, str) or not value or "\\" in value or ":" in value or any(ord(c) < 32 for c in value):
        raise ValueError("Unsafe native resource name")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in ("", ".", "..") for part in value.split("/")) or str(path) != value:
        raise ValueError("Unsafe native resource path")
    return value


def required_resources(target: str) -> set[str]:
    if target not in TARGETS:
        raise ValueError("Unknown native resource target")
    prefix = "dev/veyra/desktop/native/" + target + "/"
    executable = "ffmpeg.exe" if target.startswith("windows") else "ffmpeg"
    names = {executable, executable + ".sha256", "provenance.json", "licenses/Veyra-GPL-3.0-or-later.txt"}
    names.update("licenses/ffmpeg/" + name for name in FFMPEG_NOTICES)
    if target != "linux-x64":
        names.add("veyra-session.dll" if target.startswith("windows") else "libveyra-session.dylib")
    return {prefix + name for name in names}


def read_manifest(directory: Path, root: Path = ROOT) -> dict:
    work(directory, root)
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError("Native resource directory is absent or linked")
    receipt = directory / MANIFEST
    if receipt.is_symlink() or not receipt.is_file() or not 0 < receipt.stat().st_size <= 1024 * 1024:
        raise ValueError("Native resource inventory is missing or oversized")
    record = json.loads(receipt.read_text(encoding="utf-8"))
    if not isinstance(record, dict) or record.get("schema") != 1 or record.get("target") not in TARGETS:
        raise ValueError("Native resource inventory is not recognized")
    hashes = record.get("files")
    if not isinstance(hashes, dict) or set(hashes) != required_resources(record["target"]):
        raise ValueError("Native resource inventory is incomplete")
    expected = {MANIFEST}
    for name, digest in hashes.items():
        relative_name(name)
        if not isinstance(digest, str) or len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
            raise ValueError("Invalid native resource hash")
        if file_hash(directory / name, root) != digest:
            raise ValueError("Native resource changed; inspect before explicitly repairing")
        expected.add(name)
        expected.update(str(p) for p in PurePosixPath(name).parents if str(p) != ".")
    for entry in directory.rglob("*"):
        if entry.is_symlink() or entry.relative_to(directory).as_posix() not in expected:
            raise ValueError("Unmanaged entry in native resources; refusing cleanup")
    return record


def remove_verified_stage(directory: Path, record: dict, root: Path = ROOT) -> None:
    if read_manifest(directory, root) != record:
        raise ValueError("Old native resources changed during replacement")
    for name in record["files"]:
        (directory / name).unlink()
    (directory / MANIFEST).unlink()
    for path in sorted(directory.rglob("*"), key=lambda item: len(item.parts), reverse=True):
        path.rmdir()  # Unknown entries were refused above, never recursively deleted.
    directory.rmdir()


def recover_previous_stage(target: str, base: Path, root: Path = ROOT) -> None:
    """Reconcile an interrupted two-rename replacement without guessing ownership.

    Directory exchange is not portably atomic. The previous complete inventory
    is retained until the new one is installed; after interruption, restore that
    one verified backup or retire it only after verifying the installed stage.
    Ambiguous, partially removed or altered backups require explicit inspection.
    Never recursively delete an unrecognized staging directory.
    """
    work(base, root)
    if target not in TARGETS:
        raise ValueError("Unknown recovery target")
    if not base.exists():
        return
    backups = list(base.glob(".old-" + target + "-*"))
    if not backups:
        return
    if len(backups) != 1:
        raise ValueError("Ambiguous interrupted native stages require explicit inspection")
    previous = backups[0]
    old = read_manifest(previous, root)
    if old["target"] != target:
        raise ValueError("Interrupted native stage has a different target")
    destination = base / target
    if destination.exists() or destination.is_symlink():
        installed = read_manifest(destination, root)
        if installed["target"] != target:
            raise ValueError("Installed native stage has a different target")
        remove_verified_stage(previous, old, root)
    else:
        previous.rename(destination)


def stage_resources(target: str, sources: dict[str, Path], *, root: Path = ROOT) -> Path:
    if target not in TARGETS or set(sources) != required_resources(target):
        raise ValueError("Invalid native resource set")
    base = work(root / ".work/desktop-native/resources", root)
    destination = base / target
    hashes = {relative_name(name): file_hash(source, root) for name, source in sources.items()}
    if MANIFEST in hashes:
        raise ValueError("Reserved native resource name")
    record = {"schema": 1, "target": target, "files": hashes,
              "releaseReady": False, "remainingGates": ["full binary notices/source closure", "clean-machine execution", "physical audio"]}
    recover_previous_stage(target, base, root)
    old = None
    if destination.exists() or destination.is_symlink():
        old = read_manifest(destination, root)
        if old["target"] != target:
            raise ValueError("Wrong-target native resources require explicit inspection, not replacement")
        if old == record:
            return destination
    base.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=".stage-" + target + "-", dir=base))
    previous = None
    try:
        for name, source in sources.items():
            copied = stage / name
            copied.parent.mkdir(parents=True, exist_ok=True)
            with source.open("rb") as input_file, copied.open("xb") as output_file:
                size = 0
                while block := input_file.read(65_536):
                    size += len(block)
                    if size > MAX_FILE:
                        raise ValueError("Native input grew beyond the copy bound")
                    output_file.write(block)
            if file_hash(copied, root) != hashes[name]:
                raise ValueError("Native input changed while staging")
        (stage / MANIFEST).write_text(json.dumps(record, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        read_manifest(stage, root)
        if old is not None:
            # Preserve the previous complete stage until its replacement is in place.
            previous = Path(tempfile.mkdtemp(prefix=".old-" + target + "-", dir=base))
            previous.rmdir()
            if read_manifest(destination, root) != old:
                raise ValueError("Native resources changed during preparation")
            destination.rename(previous)
        try:
            stage.rename(destination)
        except OSError:
            if previous is not None:
                previous.rename(destination)
                previous = None
            raise
        if previous is not None:
            remove_verified_stage(previous, old, root)
        return destination
    finally:
        # This uniquely created unpublished staging directory contains only this call's copies.
        if stage.exists():
            shutil.rmtree(stage)


@contextmanager
def preparation_lock(base: Path):
    work(base).mkdir(parents=True, exist_ok=True)
    path = base / "prepare.lock"
    if path.is_symlink() or (path.exists() and (not path.is_file() or path.stat().st_nlink != 1)):
        raise ValueError("Linked/nonregular native preparation lock rejected")
    descriptor = os.open(path, os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0), 0o600)
    with os.fdopen(descriptor, "r+b") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
            raise ValueError("Native preparation lock is not exclusively owned")
        stream.seek(0, os.SEEK_END)
        if stream.tell() == 0:
            stream.write(b"0")
            stream.flush()
        stream.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == "nt":
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream, fcntl.LOCK_UN)


def run(command: list[str], directory: Path, log: Path, timeout: int, *, root: Path = ROOT) -> None:
    owned(directory, root)
    work(log, root)
    if log.is_symlink() or (log.exists() and (not log.is_file() or log.stat().st_nlink != 1)):
        raise ValueError("Linked/nonregular native build log rejected")
    env = dict(os.environ, LC_ALL="C", TZ="UTC")
    for key in ("CFLAGS", "CXXFLAGS", "CPPFLAGS", "LDFLAGS", "CC", "CXX", "CMAKE_TOOLCHAIN_FILE"):
        env.pop(key, None)
    # The owned runner validates the opened log and waits for the complete
    # trusted build-process tree, not only the immediate compiler/launcher.
    # Failure never grants permission to delete this work or its diagnostic log.
    owned_process.run(command, cwd=directory, env=env, timeout=timeout, log=log)


def session_helper(target: str) -> Path | None:
    if target == "linux-x64":
        return None  # Linux uses the real dbus-java MPRIS adapter, not a stub library.
    if shutil.which("cmake") is None:
        raise ValueError("Existing CMake3.24+ and matching-host compiler are required")
    directory = work(ROOT / ".work/native-session" / target)
    directory.mkdir(parents=True, exist_ok=True)
    args = ["cmake", "-S", str(ROOT / "native"), "-B", str(directory), "-DCMAKE_BUILD_TYPE=Release"]
    if target.startswith("macos-"):
        args += ["-DCMAKE_OSX_ARCHITECTURES=" + ("arm64" if target.endswith("arm64") else "x86_64"),
                 "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0"]
    else:
        # An existing VS/MSVC environment; no installation or global configuration.
        args += ["-A", "x64"]
    log = directory / "build.log"
    run(args, ROOT, log, 180)
    run(["cmake", "--build", str(directory), "--config", "Release", "--parallel", "1"], ROOT, log, 600)
    name = "veyra-session.dll" if target.startswith("windows") else "libveyra-session.dylib"
    output = directory / "output" / name
    binary_target(output, target)
    if output.stat().st_size > MAX_SESSION_HELPER:
        raise ValueError("Native session helper exceeds the application's16MiB loader limit")
    return output


def prepare(target: str) -> Path:
    if os.environ.get("VEYRA_SCOPED_ENV") != "1":
        raise ValueError("Use scripts/dev.sh for repository-owned homes/caches/temp")
    if target != host_target(platform.system(), platform.machine()):
        raise ValueError("Cross-labelled desktop native build rejected")
    with preparation_lock(ROOT / ".work/desktop-native"):
        log = work(ROOT / ".work/desktop-native/prepare.log")
        run([sys.executable, "-B", str(ROOT / "native/ffmpeg/build.py"), "--jobs", "1"], ROOT, log, 2700)
        name = "ffmpeg.exe" if target.startswith("windows") else "ffmpeg"
        output = work(ROOT / ".work/native-ffmpeg/output" / target)
        binary_target(output / name, target)
        digest = file_hash(output / name)
        if (output / (name + ".sha256")).read_bytes() != (digest + "\n").encode("ascii"):
            raise ValueError("Source-built FFmpeg digest sidecar does not match")
        prefix = "dev/veyra/desktop/native/" + target + "/"
        sources = {prefix + item: output / item for item in (name, name + ".sha256", "provenance.json")}
        # Complete native notices are retained verbatim; the source archive stays
        # beside the native output for the separately verified source distribution.
        for notice in (output / "licenses").iterdir():
            sources[prefix + "licenses/ffmpeg/" + notice.name] = notice
        helper = session_helper(target)
        if helper is not None:
            sources[prefix + helper.name] = helper
        sources[prefix + "licenses/Veyra-GPL-3.0-or-later.txt"] = ROOT / "LICENSE"
        return stage_resources(target, sources)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=TARGETS, required=True)
    parser.add_argument("--verify-resources", action="store_true", help="Offline inventory check only; no native execution/build")
    args = parser.parse_args()
    old = os.umask(0o077)
    try:
        if args.verify_resources:
            path = ROOT / ".work/desktop-native/resources" / args.target
            if read_manifest(path)["target"] != args.target:
                raise ValueError("Native resource target changed")
            print(path)
        else:
            print(prepare(args.target))
    finally:
        os.umask(old)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError, owned_process.OwnedProcessError) as failure:
        print("Desktop native preparation failed (" + type(failure).__name__ + "). No substitute runtime was used.", file=sys.stderr)
        if isinstance(failure, ValueError):
            print(str(failure), file=sys.stderr)
        elif isinstance(failure, owned_process.OwnedProcessError):
            print(str(failure) + " Preserve owned work/logs for inspection.", file=sys.stderr)
        raise SystemExit(1)
