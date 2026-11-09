#!/usr/bin/env python3
"""Build the separately bundled, network-disabled FFmpeg remux executable.

Only pinned official source is used; no system install, downloaded compiler,
third-party binary bundle, or developer-PATH FFmpeg is a product fallback.
All writable directories must remain under this repository's .work directory.
Build inputs are reproducible; a bit-for-bit reproducible compiler/SDK output is
not claimed. Run through scripts/dev.sh on the matching existing host toolchain.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
from urllib.request import HTTPRedirectHandler, Request, build_opener

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "native"))
import owned_process

PROCESS_OWNER = Path(owned_process.__file__).resolve()
SOURCE_URL = "https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/refs/tags/n8.0.1"
SOURCE_SHA256 = "679aa13a19415d5ddab91e580084e3ab20c963c8240001e5cbb955a97bdd81b1"
SOURCE_BYTES = 16_408_559
SOURCE_COMMIT = "894da5ca7d742e4429ffb2af534fcda0103ef593"
SOURCE_TAG_OBJECT = "d22ecc4f6f3fca77b3e71b18641ceddb25973e97"
SOURCE_ROOT = "FFmpeg-n8.0.1"
SOURCE_VERSION = "8.0.1"
SOURCE_RELEASE = (SOURCE_VERSION + "\n").encode("ascii")
PROFILE = "veyra-local-remux-v1"
MAX_SOURCE_ENTRIES = 30_000
MAX_SOURCE_BYTES = 512 * 1024 * 1024
MAX_EXECUTABLE_BYTES = 64 * 1024 * 1024

# kmpYT downloads/validates bytes itself, then its JvmMediaProcessor invokes a
# local -c copy MP4 remux. This executable is NOT a media transport or decoder.
# Keep avfilter: the ffmpeg CLI requires it even for stream-copy operations.
# Internal parsers/bitstream filters provide the container-copy closure; no
# external codec implementation is autodetected or silently incorporated.
CONFIGURE = (
    "--disable-autodetect", "--disable-doc", "--disable-debug", "--disable-x86asm",
    "--disable-everything", "--disable-programs", "--enable-ffmpeg", "--enable-small",
    "--disable-shared", "--enable-static", "--disable-gpl", "--disable-nonfree", "--disable-version3",
    "--disable-network", "--enable-protocol=file,crypto,data",
    "--enable-demuxer=mov,matroska,mpegts,aac,mp3,ogg,flac,wav,ac3,eac3,h264,hevc,m4v,ivf,av1",
    "--enable-muxer=mp4", "--enable-parsers", "--enable-bsfs",
)
LICENSE_FILES = (
    "COPYING.LGPLv2.1", "COPYING.LGPLv3", "COPYING.GPLv2", "COPYING.GPLv3", "LICENSE.md", "CREDITS",
)
CONFIGURATION_FILES = ("config.h", "config_components.h", "ffbuild-config.mak")


def owned(path: Path, root: Path = ROOT) -> Path:
    """Resolve before creating anything; escaping pre-existing links fail closed."""
    resolved = path.resolve()
    if not resolved.is_relative_to(root.resolve()):
        raise ValueError("Refusing an out-of-repository build path")
    return resolved


def writable(path: Path, root: Path = ROOT) -> Path:
    resolved = owned(path, root)
    if not resolved.is_relative_to((root / ".work").resolve()):
        raise ValueError("Native build outputs must be within the project .work directory")
    return resolved


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def require_source(archive: Path) -> None:
    if archive.is_symlink() or not archive.is_file() or archive.stat().st_size != SOURCE_BYTES or sha256(archive) != SOURCE_SHA256:
        raise ValueError("Official FFmpeg source size/SHA-256 did not match the pinned archive")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("FFmpeg source download redirects are not permitted")


def download_source(destination: Path) -> None:
    destination = writable(destination)
    if destination.exists() or destination.is_symlink():
        require_source(destination)
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    fd, name = tempfile.mkstemp(prefix=".source-", suffix=".part", dir=destination.parent)
    partial = Path(name)
    try:
        request = Request(SOURCE_URL, headers={"User-Agent": "Veyra-native-source/1", "Accept-Encoding": "identity"})
        with os.fdopen(fd, "wb") as output, build_opener(NoRedirect()).open(request, timeout=60) as response:
            if response.status != 200 or response.geturl() != SOURCE_URL:
                raise ValueError("FFmpeg source download was refused")
            length = response.headers.get("Content-Length")
            if length is not None and (not length.isdigit() or int(length) != SOURCE_BYTES):
                raise ValueError("FFmpeg source response length changed")
            encoding = response.headers.get("Content-Encoding", "identity").lower()
            if encoding != "identity":
                raise ValueError("FFmpeg source response encoding changed")
            count = 0
            while block := response.read(65_536):
                count += len(block)
                if count > SOURCE_BYTES or time.monotonic() - started > 600:
                    raise ValueError("FFmpeg source response exceeds the pinned size/time bound")
                output.write(block)
        require_source(partial)
        partial.replace(destination)
    finally:
        partial.unlink(missing_ok=True)


def archive_name(name: str) -> PurePosixPath:
    if not name or len(name) > 4096 or name.startswith("/") or any(ord(c) < 32 or ord(c) == 127 or c in "\\:" for c in name):
        raise ValueError("Unsafe FFmpeg source archive path")
    parts = name.removesuffix("/").split("/")
    if not parts or parts[0] != SOURCE_ROOT or any(part in ("", ".", "..") or part.endswith((".", " ")) for part in parts):
        raise ValueError("Unsafe FFmpeg source archive component")
    # Host-insensitive collisions are checked separately for Windows/macOS.
    reserved = {"CON", "PRN", "AUX", "NUL"} | {f"{prefix}{n}" for prefix in ("COM", "LPT") for n in range(1, 10)}
    if any(part.split(".", 1)[0].upper() in reserved for part in parts):
        raise ValueError("Reserved FFmpeg source archive component")
    return PurePosixPath(*parts)


def extract_source(archive: Path, output: Path, root: Path = ROOT, *, insensitive: bool = False) -> tuple[Path, int]:
    """Validate the complete pinned archive before creating archive-derived paths."""
    output = writable(output, root)
    output.mkdir(parents=True, exist_ok=False)
    with tarfile.open(archive, "r:gz") as bundle:
        entries = []
        names = set()
        total = 0
        epoch = 0
        for item in bundle:
            if len(entries) >= MAX_SOURCE_ENTRIES:
                raise ValueError("FFmpeg source entry bound exceeded")
            name = str(archive_name(item.name))
            key = name.casefold() if insensitive else name
            if key in names or not (item.isdir() or item.isfile()) or item.mode & 0o7000 or item.issparse():
                raise ValueError("Duplicate/link/special/privileged FFmpeg source entry")
            if item.size < 0 or item.size > MAX_SOURCE_BYTES - total or (item.isdir() and item.size != 0):
                raise ValueError("FFmpeg source expansion bound exceeded")
            names.add(key)
            total += item.size
            epoch = max(epoch, int(item.mtime))
            owned(output / name, root)
            entries.append((item, name))
        for item, name in entries:
            destination = owned(output / name, root)
            if item.isdir():
                destination.mkdir(parents=True, exist_ok=True)
            else:
                destination.parent.mkdir(parents=True, exist_ok=True)
                source = bundle.extractfile(item)
                if source is None:
                    raise ValueError("Missing FFmpeg source archive body")
                with source, destination.open("xb") as target:
                    shutil.copyfileobj(source, target, length=65_536)
                if destination.stat().st_size != item.size:
                    raise ValueError("Truncated FFmpeg source archive body")
                destination.chmod(0o700 if item.mode & 0o111 else 0o600)
    return output / SOURCE_ROOT, epoch


def host_profile(system: str, architecture: str) -> tuple[str, list[str], tuple[str, ...]]:
    arch = architecture.lower()
    x64 = arch in ("x86_64", "amd64")
    arm64 = arch in ("aarch64", "arm64")
    if system == "Linux" and x64:
        return "linux-x64", ["--enable-pthreads", "--disable-w32threads",
            "--extra-cflags=-fstack-protector-strong -D_FORTIFY_SOURCE=2 -fPIE",
            "--extra-ldflags=-pie -Wl,-z,relro,-z,now -Wl,-z,noexecstack"], ("cc", "make", "bash")
    if system == "Darwin" and (x64 or arm64):
        return "macos-" + ("x64" if x64 else "arm64"), ["--enable-pthreads", "--disable-w32threads",
            "--cc=clang", "--extra-cflags=-fstack-protector-strong -mmacosx-version-min=13.0",
            "--extra-ldflags=-mmacosx-version-min=13.0 -Wl,-dead_strip"], ("clang", "make", "bash")
    if system == "Windows" and x64:
        # Run from an existing x64 MSVC developer environment plus POSIX make/bash.
        # configure owns the mslink/mswindres adapters; no MinGW DLL closure.
        return "windows-x64", ["--toolchain=msvc", "--target-os=win64", "--arch=x86_64",
            "--enable-w32threads", "--disable-pthreads", "--extra-cflags=-MT -guard:cf",
            "--extra-ldflags=-guard:cf -dynamicbase -nxcompat"], ("cl.exe", "lib.exe", "dumpbin.exe", "make", "bash")
    raise ValueError("No supported matching-host FFmpeg production profile")


def executable_name(target: str) -> str:
    # The official Makefile applies EXESUF to targets too, not just output names.
    return "ffmpeg.exe" if target.startswith("windows") else "ffmpeg"


def require_release(source: Path) -> None:
    release = source / "RELEASE"
    if release.is_symlink() or not release.is_file() or release.read_bytes() != SOURCE_RELEASE:
        raise ValueError("Extracted FFmpeg RELEASE differs from the pinned official release")
    # The pinned codeload archive has no VERSION override. Do not let an
    # unexpected file take precedence over the explicit make REVISION below.
    if (source / "VERSION").exists() or (source / "VERSION").is_symlink():
        raise ValueError("Unexpected FFmpeg source VERSION override")


def make_command(target: str, jobs: str) -> list[str]:
    if jobs not in ("1", "2"):
        raise ValueError("Native build jobs must be one or two")
    # Otherwise upstream version.sh finds the enclosing Veyra Git checkout,
    # because the official codeload archive does not include its own .git.
    return ["make", "-j" + jobs, "REVISION=" + SOURCE_VERSION, executable_name(target)]


def build_environment(target: str, epoch: int, inherited: dict[str, str]) -> dict[str, str]:
    env = dict(inherited, LC_ALL="C", LANG="C", TZ="UTC", SOURCE_DATE_EPOCH=str(epoch))
    if target.startswith("macos"):
        env["MACOSX_DEPLOYMENT_TARGET"] = "13.0"
    # Preserve SDK/toolchain discovery, not inherited compiler wrappers, make
    # injection, or the upstream version script's ambient shell variables.
    for name in ("CFLAGS", "CPPFLAGS", "CXXFLAGS", "LDFLAGS", "CC", "CXX", "LD", "AR", "FFREPORT",
                 "MAKEFLAGS", "GNUMAKEFLAGS", "MFLAGS", "MAKEOVERRIDES", "MAKEFILES",
                 "REVISION", "EXTRA_VERSION", "revision", "git_hash"):
        env.pop(name, None)
    return env


def write_sidecar(path: Path, digest: str) -> None:
    # write_text with default newline handling silently produces CRLF on Windows.
    path.write_bytes((digest + "\n").encode("ascii"))


def run(command: list[str], cwd: Path, env: dict[str, str], timeout: int, log: Path) -> None:
    invocation = env.get("VEYRA_NATIVE_DIAGNOSTIC_INVOCATION")
    if platform.system() == "Windows":
        # CreateProcess searches System32 before PATH for a bare executable.
        # Pin the already-resolved tool (especially Git Bash vs the WSL shim).
        executable = shutil.which(command[0], path=env.get("PATH"))
        if executable is None:
            raise ValueError("Existing matching-host command is required; nothing is installed")
        command = [executable, *command[1:]]
    try:
        owned_process.run(command, cwd=owned(cwd), env=env, timeout=timeout, log=writable(log))
    except owned_process.OwnedProcessError as failure:
        try:
            # Reuse the orchestrator's bounded credential-filtered reporter on
            # the actual configure/make log before the outer owner summarizes
            # this child. Reporting is optional and cannot mask the failure.
            from bundle import report_build_failure
            phase = "configure" if len(command) > 1 and Path(command[1]).name == "configure" else "build"
            print("Native FFmpeg " + phase + " command failed.", file=sys.stderr)
            report_build_failure(log, failure, ROOT, invocation=invocation)
            if len(command) > 1 and Path(command[1]).name == "configure":
                # FFmpeg keeps the compiler probe itself in this explicit
                # configure output, separate from the console build log.
                probe_log = cwd / "ffbuild/config.log"
                if probe_log.is_file():
                    report_build_failure(probe_log, failure, ROOT, invocation=invocation)
        except Exception as diagnostic_error:
            print("Bounded FFmpeg diagnostic failed (" + type(diagnostic_error).__name__ + ").", file=sys.stderr)
        raise


def capture(command: list[str], cwd: Path, env: dict[str, str], timeout: int = 20) -> str:
    result = owned_process.capture(command, cwd=owned(cwd), env=env, timeout=timeout,
                                   max_output_bytes=1024 * 1024)
    return result.decode("utf-8", errors="replace")


def verify_features(executable: Path, cwd: Path, env: dict[str, str]) -> dict[str, str]:
    reports = {kind: capture([str(executable), "-hide_banner", "-" + kind], cwd, env)
               for kind in ("version", "L", "protocols", "formats", "codecs", "buildconf")}
    if not re.match(r"ffmpeg version (?:n)?" + re.escape(SOURCE_VERSION) + r"(?:\s|$)", reports["version"]):
        raise ValueError("Unexpected built FFmpeg version")
    license_text = reports["L"]
    if "GNU Lesser General Public" not in license_text or "version 2.1" not in license_text:
        raise ValueError("Built FFmpeg license is not the intended LGPL-2.1-or-later profile")
    if "--disable-network" not in reports["buildconf"] or "--disable-gpl" not in reports["buildconf"]:
        raise ValueError("Built FFmpeg does not use the restricted production profile")
    names = {line.strip() for line in reports["protocols"].splitlines() if re.fullmatch(r"[a-z0-9_]+", line.strip())}
    if names != {"crypto", "data", "file"}:
        raise ValueError("Built FFmpeg exposes an unexpected media protocol")
    if not any("mp4" in line and "E" in line[:3] for line in reports["formats"].splitlines()):
        raise ValueError("Built FFmpeg cannot perform the required MP4 remux")
    return reports


def verify_remux(executable: Path, directory: Path, env: dict[str, str]) -> dict[str, object]:
    """Authored upstream fixture, exact kmpYT local-copy command; not decode proof."""
    fixtures = ROOT / "third_party/kmpYT/kmpyt/src/jvmTest/resources/research"
    audio, video = fixtures / "audio.m4a", fixtures / "video.mp4"
    if not audio.is_file() or not video.is_file() or not (fixtures / "LICENSE.txt").is_file():
        raise ValueError("Pinned authored kmpYT test fixtures/license are required for remux verification")
    output = directory / "authored-remux.mp4"
    log = directory / "remux.log"
    run([str(executable), "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
         "-protocol_whitelist", "file,crypto,data", "-i", str(video),
         "-protocol_whitelist", "file,crypto,data", "-i", str(audio),
         "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-movflags", "+faststart", "-f", "mp4", str(output)],
        directory, env, 30, log)
    if not output.is_file() or output.stat().st_size == 0:
        raise ValueError("Built FFmpeg produced no remuxed media")
    return {"kind": "authored-local-H264-AAC-stream-copy", "sha256": sha256(output), "bytes": output.stat().st_size,
            "decodedHere": False, "physicalAudioVerified": False,
            "inputSha256": {"audio.m4a": sha256(audio), "video.mp4": sha256(video)}}


def identity(target: str, host_flags: list[str], base: Path) -> dict[str, object]:
    return {
        "schema": 1, "component": "FFmpeg", "version": SOURCE_VERSION, "target": target, "profile": PROFILE,
        "origin": SOURCE_URL, "archiveSha256": SOURCE_SHA256, "archiveBytes": SOURCE_BYTES,
        "sourceCommit": SOURCE_COMMIT, "sourceTagObject": SOURCE_TAG_OBJECT,
        "license": "LGPL-2.1-or-later", "gplEnabled": False, "nonfreeEnabled": False,
        "builtFromOfficialSource": True, "networkEnabled": False, "intendedUse": "local stream-copy remux only",
        "configure": [*CONFIGURE, *host_flags, "--prefix=" + (base / "unused-prefix").as_posix()],
        "makeRevision": SOURCE_VERSION, "sourceReleaseSha256": hashlib.sha256(SOURCE_RELEASE).hexdigest(),
        "recipeSha256": sha256(Path(__file__)), "recipeReadmeSha256": sha256(Path(__file__).with_name("README.md")),
        "processOwnerSha256": sha256(PROCESS_OWNER),
    }


def package_files(name: str) -> set[str]:
    return {name, name + ".sha256", "corresponding-source/FFmpeg-n8.0.1.tar.gz",
            "corresponding-source/build.py", "corresponding-source/README.md",
            "corresponding-source/owned_process.py"} | {
                "licenses/" + filename for filename in LICENSE_FILES} | {
                "corresponding-source/" + filename for filename in CONFIGURATION_FILES}


def verify_output(output: Path, expected: dict[str, object], root: Path = ROOT) -> Path:
    """Offline/nonexecuting reuse check. Stale/corrupt outputs are never deleted."""
    if output.is_symlink() or not output.is_dir():
        raise ValueError("Verified native output is missing; run the source build first")
    output = writable(output, root)
    receipt = output / "provenance.json"
    if receipt.is_symlink() or not receipt.is_file() or not 0 < receipt.stat().st_size <= 1024 * 1024:
        raise ValueError("Native FFmpeg provenance is missing or invalid")
    try:
        metadata = json.loads(receipt.read_text(encoding="utf-8"))
    except (ValueError, UnicodeError):
        raise ValueError("Native FFmpeg provenance cannot be read") from None
    if not isinstance(metadata, dict) or any(metadata.get(key) != value for key, value in expected.items()):
        raise ValueError("Native FFmpeg source/profile/recipe changed; review output before an explicit rebuild")
    name = "ffmpeg.exe" if str(expected["target"]).startswith("windows") else "ffmpeg"
    files = package_files(name)
    hashes = metadata.get("fileSha256")
    if not isinstance(hashes, dict) or set(hashes) != files:
        raise ValueError("Native FFmpeg retained-file inventory changed")
    expected_paths = files | {"provenance.json", "licenses", "corresponding-source"}
    for path in output.rglob("*"):
        relative = path.relative_to(output).as_posix()
        if path.is_symlink() or relative not in expected_paths:
            raise ValueError("Unexpected entry in native FFmpeg output")
    for relative in files:
        path = output / relative
        if not path.is_file() or path.is_symlink() or sha256(path) != hashes[relative]:
            raise ValueError("Native FFmpeg retained file changed")
    executable = output / name
    digest = sha256(executable)
    if not 0 < executable.stat().st_size <= MAX_EXECUTABLE_BYTES or not os.access(executable, os.X_OK):
        raise ValueError("Native FFmpeg output is not an executable within its package bound")
    if metadata.get("executable") != name or metadata.get("executableSha256") != digest or metadata.get("executableBytes") != executable.stat().st_size:
        raise ValueError("Native FFmpeg binary does not match its provenance")
    if (output / (name + ".sha256")).read_bytes() != (digest + "\n").encode("ascii"):
        raise ValueError("Native FFmpeg binary SHA sidecar changed")
    archive = output / "corresponding-source/FFmpeg-n8.0.1.tar.gz"
    require_source(archive)
    if (hashes["corresponding-source/build.py"] != expected["recipeSha256"]
            or hashes["corresponding-source/README.md"] != expected["recipeReadmeSha256"]
            or hashes["corresponding-source/owned_process.py"] != expected["processOwnerSha256"]):
        raise ValueError("Retained FFmpeg build instructions changed")
    # Complete notice bytes are rooted in the pinned official archive, not only
    # mutable metadata. No archive-derived files are written during reuse.
    with tarfile.open(archive, "r:gz") as bundle:
        for filename in LICENSE_FILES:
            source = bundle.extractfile(SOURCE_ROOT + "/" + filename)
            if source is None:
                raise ValueError("Required FFmpeg source notice is absent")
            with source:
                digest = hashlib.sha256()
                for block in iter(lambda: source.read(65_536), b""):
                    digest.update(block)
            if digest.hexdigest() != hashes["licenses/" + filename]:
                raise ValueError("Retained FFmpeg notice differs from official source")
    return executable


def build(source_argument: Path | None, jobs: str, *, verify_only: bool = False) -> Path:
    if os.environ.get("VEYRA_SCOPED_ENV") != "1":
        raise ValueError("Use scripts/dev.sh for project-owned tool homes/caches/temp")
    if jobs not in ("1", "2"):
        raise ValueError("Native build jobs must be one or two")
    target, host_flags, required_tools = host_profile(platform.system(), platform.machine())
    base = writable(ROOT / ".work/native-ffmpeg")
    expected = identity(target, host_flags, base)
    output_parent = writable(base / "output")
    output = output_parent / target
    if source_argument is not None:
        require_source(owned(source_argument))
    if verify_only or output.exists() or output.is_symlink():
        return verify_output(output, expected)
    for tool in required_tools:
        if shutil.which(tool) is None:
            raise ValueError("Existing matching-host compiler/make/bash tools are required; nothing is installed")
    base.mkdir(parents=True, exist_ok=True)
    source_archive = owned(source_argument) if source_argument else base / "downloads/FFmpeg-n8.0.1.tar.gz"
    if source_argument is None:
        download_source(source_archive)
    require_source(source_archive)
    output_parent.mkdir(parents=True, exist_ok=True)
    # Failed build work/logs remain in this task-owned directory for diagnosis.
    work = Path(tempfile.mkdtemp(prefix="build-" + target + "-", dir=base))
    source, epoch = extract_source(source_archive, work / "source", insensitive=platform.system() in ("Windows", "Darwin"))
    require_release(source)
    build_directory = work / "build"
    build_directory.mkdir()
    env = build_environment(target, epoch, dict(os.environ))
    configure = expected["configure"]
    started = time.monotonic()
    log = work / "build.log"
    run(["bash", (source / "configure").as_posix(), *configure], build_directory, env, 600, log)
    name = executable_name(target)
    run(make_command(target, jobs), build_directory, env, 1800, log)
    executable = build_directory / name
    if not executable.is_file() or not 0 < executable.stat().st_size <= MAX_EXECUTABLE_BYTES:
        raise ValueError("Native FFmpeg executable is missing or exceeds the package bound")
    reports = verify_features(executable, build_directory, env)
    proof = verify_remux(executable, work, env)
    stage = Path(tempfile.mkdtemp(prefix=".package-" + target + "-", dir=output_parent))
    try:
        shutil.copy2(executable, stage / name)
        (stage / name).chmod(0o700)
        digest = sha256(stage / name)
        write_sidecar(stage / (name + ".sha256"), digest)
        licenses = stage / "licenses"
        licenses.mkdir()
        for filename in LICENSE_FILES:
            shutil.copyfile(source / filename, licenses / filename)
        if "GNU LESSER GENERAL PUBLIC LICENSE" not in (licenses / "COPYING.LGPLv2.1").read_text(encoding="utf-8"):
            raise ValueError("Complete official LGPL notice is missing")
        sources = stage / "corresponding-source"
        sources.mkdir()
        shutil.copyfile(source_archive, sources / "FFmpeg-n8.0.1.tar.gz")
        shutil.copyfile(Path(__file__), sources / "build.py")
        shutil.copyfile(Path(__file__).with_name("README.md"), sources / "README.md")
        shutil.copyfile(PROCESS_OWNER, sources / "owned_process.py")
        for filename in ("config.h", "config_components.h", "ffbuild/config.mak"):
            copied = sources / filename.replace("/", "-")
            shutil.copyfile(build_directory / filename, copied)
        metadata = {
            **expected, "jobs": int(jobs), "sourceDateEpoch": epoch,
            "buildSeconds": round(time.monotonic() - started, 3), "host": platform.platform(),
            "executable": name, "executableSha256": digest, "executableBytes": (stage / name).stat().st_size,
            "featureReports": reports, "remuxVerification": proof,
            "fileSha256": {relative: sha256(stage / relative) for relative in sorted(package_files(name))},
            "releaseReady": False, "remainingGates": ["packaged native decode", "target dependency inspection",
                "clean-machine packaged start", "signing where required", "full application notices/source closure"],
        }
        (stage / "provenance.json").write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        verify_output(stage, expected)
        stage.rename(output)
    finally:
        if stage.exists():
            shutil.rmtree(stage)
    return output / name


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, help="Existing whole-pinned official archive within Veyra (read only)")
    parser.add_argument("--jobs", choices=("1", "2"), default="1")
    parser.add_argument("--verify-output", action="store_true", help="Verify existing matching output offline; do not build/download/execute")
    args = parser.parse_args()
    # Every path and archive entry is still checked; a private umask is an
    # additional default, not a replacement for scope/integrity validation.
    old = os.umask(0o077)
    try:
        print(build(args.source, args.jobs, verify_only=args.verify_output))
    finally:
        os.umask(old)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError, tarfile.TarError, owned_process.OwnedProcessError) as error:
        # Do not print arbitrary network errors containing redirected capabilities.
        print("Scoped production FFmpeg build failed (" + type(error).__name__ + "). No global install or substitute binary.", file=sys.stderr)
        if isinstance(error, ValueError):
            print(str(error), file=sys.stderr)
        elif isinstance(error, owned_process.OwnedProcessError):
            print(str(error) + " Preserve owned work/logs for inspection.", file=sys.stderr)
        raise SystemExit(1)
