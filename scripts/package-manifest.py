#!/usr/bin/env python3
"""Inventory newly generated packages with exact source/run/host provenance.

This is not a signature, installation test, legal closure or release acceptance.
Only packages created after the explicit build marker are accepted, so a stale
file cannot be presented as the current build.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
HEX40 = re.compile(r"[0-9a-f]{40}")
HEX64 = re.compile(r"[0-9a-f]{64}")
MAX_PACKAGES = 64
MAX_PACKAGE = 2 * 1024 * 1024 * 1024


class ManifestError(ValueError):
    pass


def bounded_text(value: str | None, name: str, maximum: int = 512) -> str:
    if value is None:
        return ""
    if len(value) > maximum or any(ord(char) < 32 or ord(char) == 127 for char in value):
        raise ManifestError(name + " contains unsafe bytes")
    return value


def digest(path: Path) -> str:
    value = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            size += len(block)
            if size > MAX_PACKAGE:
                raise ManifestError("Package exceeds the per-file bound")
            value.update(block)
    return value.hexdigest()


def exact_hex(value: str, name: str, length: int) -> str:
    pattern = HEX40 if length == 40 else HEX64
    if not pattern.fullmatch(value):
        raise ManifestError(name + " must be lowercase " + str(length) + "-hex")
    return value


def git_value(root: Path, *arguments: str) -> str:
    try:
        return subprocess.check_output(["git", "-C", str(root), *arguments], text=True,
                                       stderr=subprocess.DEVNULL).strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help=argparse.SUPPRESS)
    parser.add_argument("--base", type=Path, default=ROOT / "composeApp/build/compose/binaries/main",
                        help=argparse.SUPPRESS)
    parser.add_argument("--newer-than", type=Path, help="Accept only package files strictly newer than this build marker")
    parser.add_argument("--expected-source-sha", default="")
    parser.add_argument("--repository", default="")
    parser.add_argument("--task", default="")
    parser.add_argument("--output", type=Path, default=ROOT / ".work/verification/packages.json")
    args = parser.parse_args()
    root = args.root.resolve()
    base = args.base.resolve()
    if not base.is_relative_to(root):
        raise ManifestError("Package base is outside the repository")
    marker = args.newer_than.resolve() if args.newer_than else None
    if marker is not None and (not marker.is_file() or marker.is_symlink()):
        raise ManifestError("--newer-than must be a regular build marker")
    extension = {"Linux": ".deb", "Darwin": ".dmg", "Windows": ".msi"}.get(platform.system())
    if extension is None:
        raise ManifestError("Unsupported package host")
    packages = []
    if base.exists():
        for path in sorted(base.glob(f"**/*{extension}")):
            resolved = path.resolve()
            if not resolved.is_relative_to(base) or path.is_symlink() or not path.is_file():
                raise ManifestError("Escaping/symlinked package path rejected")
            if marker is not None and not path.stat().st_mtime_ns > marker.stat().st_mtime_ns:
                continue
            if path.stat().st_size <= 0 or path.stat().st_size > MAX_PACKAGE:
                raise ManifestError("Package is empty/oversized")
            packages.append(path)
    if not packages:
        raise ManifestError(f"Expected newly generated {extension} package missing; stale/skipped tasks are not success")
    if len(packages) > MAX_PACKAGES:
        raise ManifestError("Too many packages for one manifest")
    source_sha = bounded_text(args.expected_source_sha, "source sha", 40) or git_value(root, "rev-parse", "HEAD")
    source_sha = exact_hex(source_sha, "source sha", 40)
    head = git_value(root, "rev-parse", "HEAD")
    if head and source_sha != head:
        raise ManifestError("Explicit source SHA does not match the checked-out HEAD")
    pin_path = root / "gradle/kmpyt.commit"
    pin = exact_hex(pin_path.read_text().strip() if pin_path.is_file() else "", "kmpyt pin", 40)
    schema_path = root / "shared/schemas/dev.veyra.data.room.VeyraDatabase/3.json"
    if not schema_path.is_file() or schema_path.is_symlink():
        raise ManifestError("Missing schema3 export")
    schema_sha = exact_hex(digest(schema_path), "schema sha", 64)
    legal_index = root / "composeApp/src/commonMain/composeResources/files/legal/index.json"
    legal_sha = exact_hex(digest(legal_index), "legal index sha", 64) if legal_index.is_file() and not legal_index.is_symlink() else ""
    repository = bounded_text(args.repository or os.environ.get("GITHUB_REPOSITORY"), "repository", 256)
    task = bounded_text(args.task, "task", 256)
    records = []
    for path in packages:
        records.append({
            "path": path.relative_to(root).as_posix(),
            "bytes": path.stat().st_size,
            "sha256": digest(path),
            "architecture": platform.machine(),
            "signing": "no production signing/notarization configured",
            "installationTested": False,
        })
    result = {
        "schemaVersion": 1,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "nonproductionProvenanceOnly": True,
        "repository": repository or "not-recorded",
        "source": {
            "commitSha": source_sha,
            "kmpytPin": pin,
            "schemaSha256": schema_sha,
            "legalIndexSha256": legal_sha,
        },
        "workflow": {
            "runId": bounded_text(os.environ.get("GITHUB_RUN_ID"), "run id", 32),
            "runAttempt": bounded_text(os.environ.get("GITHUB_RUN_ATTEMPT"), "run attempt", 32),
            "workflowRef": bounded_text(os.environ.get("GITHUB_WORKFLOW_REF"), "workflow ref"),
            "ref": bounded_text(os.environ.get("GITHUB_REF"), "ref"),
        },
        "host": {
            "system": platform.system(),
            "machine": platform.machine(),
            "runnerImage": bounded_text(os.environ.get("ImageOS"), "image os", 128),
            "runnerImageVersion": bounded_text(os.environ.get("ImageVersion"), "image version", 128),
            "javaVersion": bounded_text(os.environ.get("JAVA_HOME"), "java home", 512),
        },
        "build": {
            "task": task or "not-recorded",
            "marker": marker.relative_to(root).as_posix() if marker is not None and marker.is_relative_to(root) else ("external-marker" if marker else "not-requested"),
        },
        "packages": records,
        "limits": [
            "Package bytes are unsigned unless explicitly recorded otherwise.",
            "Installation, launch, native audio, signing/notarization, legal closure and distribution are not established.",
        ],
    }
    output = args.output
    if not output.resolve().is_relative_to(root):
        raise ManifestError("Manifest output must remain inside the repository")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ManifestError, OSError, ValueError) as error:
        print("Package manifest failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
