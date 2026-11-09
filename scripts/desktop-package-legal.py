#!/usr/bin/env python3
"""Bounded desktop legal-input and application-JAR path selection.

Used by scripts/package.sh before Gradle packaging and by package-manifest.py
callers. It never scans a global cache or an arbitrary package tree: only the
explicit generated legal directory and the known Compose application-image
directories for the current host are inspected.
"""
from __future__ import annotations

import argparse
from pathlib import Path, PurePosixPath
import platform
import stat
import sys

ROOT = Path(__file__).resolve().parents[1]
LEGAL_RESOURCE_PREFIX = "composeResources/dev.veyra.generated.resources/files/legal/"
MAX_LEGAL_FILE = 64 * 1024 * 1024
MAX_LEGAL_TOTAL = 256 * 1024 * 1024
MAX_LEGAL_FILES = 512
APP_IMAGE = Path("composeApp/build/compose/binaries/main/app")
APP_JAR_GLOB = "composeApp-desktop-*.jar"


class DesktopLegalError(ValueError):
    pass


def platform_name(system: str | None = None, machine: str | None = None) -> str:
    system = (system or platform.system()).lower()
    machine = (machine or platform.machine()).lower()
    architecture = {"amd64": "x64", "x86_64": "x64", "aarch64": "arm64", "arm64": "arm64"}.get(machine)
    if architecture is None:
        raise DesktopLegalError("Unsupported desktop architecture: " + machine)
    if system.startswith("linux"):
        if architecture != "x64":
            raise DesktopLegalError("Linux ARM64 is outside the required desktop targets")
        return "desktop-linux-x64"
    if system.startswith("darwin") or system.startswith("mac"):
        return "desktop-macos-" + architecture
    if system.startswith("win"):
        if architecture != "x64":
            raise DesktopLegalError("Windows ARM64 is outside the required desktop targets")
        return "desktop-windows-x64"
    raise DesktopLegalError("Unsupported desktop host: " + system)


def app_image_directories(platform_id: str, root: Path = ROOT) -> list[Path]:
    base = root / APP_IMAGE
    if platform_id == "desktop-linux-x64":
        return [base / "Veyra/lib/app"]
    if platform_id == "desktop-windows-x64":
        # jpackage uses app/; keep the Linux-style lib/app as an explicit
        # compatibility candidate in case the plugin layout changes.
        return [base / "Veyra/app", base / "Veyra/lib/app"]
    if platform_id in ("desktop-macos-arm64", "desktop-macos-x64"):
        return [base / "Veyra.app/Contents/app"]
    raise DesktopLegalError("Unsupported desktop platform: " + platform_id)


def _regular_file(path: Path, label: str) -> None:
    if path.is_symlink() or not path.is_file():
        raise DesktopLegalError(label + " is missing or not a regular file")


def locate_app_jar(platform_id: str, root: Path = ROOT) -> Path:
    matches: list[Path] = []
    for directory in app_image_directories(platform_id, root):
        if directory.is_symlink() or not directory.is_dir():
            continue
        for candidate in sorted(directory.glob(APP_JAR_GLOB)):
            _regular_file(candidate, "Application JAR")
            resolved = candidate.resolve()
            if not resolved.is_relative_to(root.resolve()):
                raise DesktopLegalError("Application JAR escapes the repository")
            matches.append(candidate)
    if not matches:
        raise DesktopLegalError(
            "No composeApp desktop application JAR in the generated app image for " + platform_id)
    if len(matches) != 1:
        raise DesktopLegalError("Expected exactly one application JAR, found " + str(len(matches)))
    return matches[0]


def validate_legal_directory(directory: Path, root: Path = ROOT) -> Path:
    candidate = (directory if directory.is_absolute() else root / directory).absolute()
    repo = root.resolve()
    resolved = candidate.resolve()
    if candidate.is_symlink() or not resolved.is_relative_to(repo):
        raise DesktopLegalError("Legal directory escapes the repository or is a symlink")
    for ancestor in (candidate, *candidate.parents):
        if ancestor in (root, root.absolute(), repo):
            break
        if ancestor.is_symlink():
            raise DesktopLegalError("Legal directory path contains a symlink")
    if not resolved.is_dir():
        raise DesktopLegalError("Legal directory is missing")
    if not resolved.joinpath("index.json").is_file():
        raise DesktopLegalError("Legal directory has no index.json")
    files = 0
    total = 0
    stack = [resolved]
    while stack:
        current = stack.pop()
        for entry in sorted(current.iterdir()):
            if entry.is_symlink():
                raise DesktopLegalError("Legal directory contains a symlink: " + entry.name)
            if entry.is_dir():
                stack.append(entry)
                continue
            if not stat.S_ISREG(entry.stat().st_mode):
                raise DesktopLegalError("Legal directory contains a nonregular file: " + entry.name)
            relative = PurePosixPath(entry.relative_to(resolved).as_posix())
            if relative.is_absolute() or any(part in ("", ".", "..") for part in relative.parts):
                raise DesktopLegalError("Legal directory contains an unsafe relative path")
            size = entry.stat().st_size
            files += 1
            total += size
            if files > MAX_LEGAL_FILES or size > MAX_LEGAL_FILE or total > MAX_LEGAL_TOTAL:
                raise DesktopLegalError("Legal directory exceeds its bounded file/size limits")
    return resolved


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("platform", help="Print the current host desktop platform id")
    locate = subparsers.add_parser("locate-app-jar", help="Print the exact generated app-image JAR")
    locate.add_argument("--platform", default="")
    validate = subparsers.add_parser("validate-legal-dir", help="Validate a generated legal directory")
    validate.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "platform":
        print(platform_name())
    elif args.command == "locate-app-jar":
        print(locate_app_jar(args.platform or platform_name()))
    else:
        print(validate_legal_directory(args.directory))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (DesktopLegalError, OSError, ValueError) as error:
        print("Desktop legal verification failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
