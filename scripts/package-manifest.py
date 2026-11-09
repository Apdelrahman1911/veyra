#!/usr/bin/env python3
"""Inventory newly generated packages with exact source/run/host provenance.

This is not a signature, installation test, legal closure or release acceptance.
Only packages created after the explicit build marker are accepted, so a stale
file cannot be presented as the current build.

The production path also verifies the legal index and every indexed text inside
the exact desktop application-image JAR consumed by the installer task. Without
--app-jar the result is explicitly labelled source-tree/unverified.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import stat
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
HEX40 = re.compile(r"[0-9a-f]{40}")
HEX64 = re.compile(r"[0-9a-f]{64}")
MAX_PACKAGES = 64
MAX_PACKAGE = 2 * 1024 * 1024 * 1024
LEGAL_RESOURCE_PREFIX = "composeResources/dev.veyra.generated.resources/files/legal/"
LEGAL_PLATFORMS = frozenset((
    "desktop-linux-x64", "desktop-windows-x64",
    "desktop-macos-arm64", "desktop-macos-x64",
))
MAX_LEGAL_FILE = 64 * 1024 * 1024
MAX_LEGAL_TOTAL = 256 * 1024 * 1024
MAX_LEGAL_FILES = 512
MAX_ZIP_ENTRIES = 200_000


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


def legal_name(value: str, label: str = "Embedded legal resource path") -> str:
    if (not isinstance(value, str) or not value or len(value) > 1024
            or any(ord(char) < 32 or ord(char) == 127 for char in value)):
        raise ManifestError(label + " contains unsafe bytes")
    path = PurePosixPath(value)
    if ("\\" in value or ":" in value or path.is_absolute()
            or any(part in ("", ".", "..") for part in path.parts)
            or str(path) != value or value.endswith("/")):
        raise ManifestError(label + " is not a canonical relative path: " + value)
    return value


def strict_json(data: bytes, label: str) -> dict:
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError(label + " contains a duplicate JSON key")
            result[key] = value
        return result

    def invalid_constant(_):
        raise ManifestError(label + " contains a nonfinite JSON value")

    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=unique,
                           parse_constant=invalid_constant)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError(label + " is not valid UTF-8 JSON") from error
    except RecursionError as error:
        raise ManifestError(label + " is nested too deeply") from error
    if not isinstance(value, dict):
        raise ManifestError(label + " must be a JSON object")
    return value


def owned_regular_file(root: Path, path: Path, label: str, maximum: int = MAX_PACKAGE) -> Path:
    candidate = (path if path.is_absolute() else root / path).absolute()
    resolved = candidate.resolve()
    if candidate.is_symlink() or not resolved.is_relative_to(root):
        raise ManifestError(label + " escapes the repository or is a symlink")
    for ancestor in (candidate, *candidate.parents):
        if ancestor == root:
            break
        if ancestor.is_symlink():
            raise ManifestError(label + " contains a symlink")
    if not resolved.is_file():
        raise ManifestError(label + " is missing or not a regular file")
    size = resolved.stat().st_size
    if size <= 0 or size > maximum:
        raise ManifestError(label + " is empty/oversized")
    return resolved


def read_legal_directory(root: Path, path: Path) -> dict[str, bytes]:
    candidate = (path if path.is_absolute() else root / path).absolute()
    resolved = candidate.resolve()
    if candidate.is_symlink() or not resolved.is_relative_to(root):
        raise ManifestError("Generated legal directory escapes the repository or is a symlink")
    for ancestor in (candidate, *candidate.parents):
        if ancestor == root:
            break
        if ancestor.is_symlink():
            raise ManifestError("Generated legal directory contains a symlink")
    if not resolved.is_dir():
        raise ManifestError("Generated legal directory is missing")
    files: dict[str, bytes] = {}
    total = 0
    for current, directories, names in os.walk(resolved, followlinks=False):
        base = Path(current)
        for name in sorted(directories):
            if (base / name).is_symlink():
                raise ManifestError("Generated legal directory contains a symlinked directory")
        for name in sorted(names):
            full = base / name
            relative = full.relative_to(resolved).as_posix()
            legal_name(relative, "Generated legal resource path")
            if full.is_symlink() or not full.is_file():
                raise ManifestError("Generated legal resources must be regular files")
            size = full.stat().st_size
            if size > MAX_LEGAL_FILE:
                raise ManifestError("Generated legal file is oversized: " + relative)
            data = full.read_bytes()
            if len(data) != size:
                raise ManifestError("Generated legal file changed while reading: " + relative)
            total += size
            if total > MAX_LEGAL_TOTAL or len(files) >= MAX_LEGAL_FILES:
                raise ManifestError("Generated legal resources exceed their bounded file/size limits")
            files[relative] = data
    if "index.json" not in files:
        raise ManifestError("Generated legal directory has no index.json")
    return files


def validate_generated_legal(files: dict[str, bytes], pin: str,
                             expected_platform: str | None) -> dict:
    index = strict_json(files["index.json"], "Embedded legal index")
    if type(index.get("schemaVersion")) is not int or index["schemaVersion"] != 1:
        raise ManifestError("Embedded legal index has an unsupported schema")
    if index.get("generator") != "scripts/package-notices.py":
        raise ManifestError("Embedded legal index was not produced by the reviewed generator")
    if index.get("testOnly") is not False:
        raise ManifestError("Embedded legal index is labelled test-only or omits testOnly=false")
    if index.get("binaryRedistributionReady") is not False:
        raise ManifestError("Embedded legal index must not claim binary redistribution readiness")
    source = index.get("source")
    if not isinstance(source, dict) or source.get("kmpytPin") != pin:
        raise ManifestError("Embedded legal index belongs to a different kmpYT pin")
    platforms = index.get("platforms")
    if not isinstance(platforms, list) or not platforms or any(not isinstance(item, str) for item in platforms):
        raise ManifestError("Embedded legal index has no inventoried platform")
    if expected_platform is not None and platforms != [expected_platform]:
        raise ManifestError("Embedded legal index does not bind exactly the expected desktop platform")
    components = index.get("components")
    if not isinstance(components, list) or not components:
        raise ManifestError("Embedded legal index has no production components (source-only index rejected)")
    if len(components) > 8192:
        raise ManifestError("Embedded legal index has an oversized component set")
    coverage = index.get("coverageIssues")
    if not isinstance(coverage, list) or any(not isinstance(item, str) for item in coverage):
        raise ManifestError("Embedded legal index has malformed coverage issues")
    documents = index.get("documents")
    if not isinstance(documents, list) or not documents or len(documents) > MAX_LEGAL_FILES:
        raise ManifestError("Embedded legal index has no retained documents")
    generated = index.get("generatedFiles")
    hashes = index.get("fileHashes")
    if (not isinstance(generated, list) or any(not isinstance(item, str) for item in generated)
            or len(generated) != len(set(generated)) or "index.json" not in generated
            or any(legal_name(item, "Indexed legal path") != item for item in generated)):
        raise ManifestError("Embedded legal index has malformed generatedFiles")
    if not isinstance(hashes, dict) or set(hashes) != set(generated) - {"index.json"}:
        raise ManifestError("Embedded legal fileHashes do not cover exactly the generated files")
    if set(files) != set(generated):
        missing = sorted(set(generated) - set(files))
        extra = sorted(set(files) - set(generated))
        raise ManifestError("Embedded legal resource set differs from its index; missing="
                            + ",".join(missing[:8]) + " extra=" + ",".join(extra[:8]))
    document_ids = set()
    for document in documents:
        if not isinstance(document, dict):
            raise ManifestError("Embedded legal document record is malformed")
        name = legal_name(document.get("path"), "Indexed legal document path")
        if name in document_ids:
            raise ManifestError("Duplicate embedded legal document record: " + name)
        document_ids.add(name)
        if name not in files or name not in hashes:
            raise ManifestError("Embedded legal document is not covered by file hashes: " + name)
        if digest_bytes(files[name]) != hashes[name]:
            raise ManifestError("Tampered embedded legal document: " + name)
        if document.get("sha256") != hashes[name] or type(document.get("bytes")) is not int \
                or document["bytes"] != len(files[name]):
            raise ManifestError("Embedded legal document metadata mismatch: " + name)
    for name, expected in hashes.items():
        if not isinstance(expected, str) or not HEX64.fullmatch(expected):
            raise ManifestError("Embedded legal hash is malformed: " + name)
        actual = digest_bytes(files[name])
        if actual != expected:
            raise ManifestError("Tampered embedded legal resource: " + name)
    return {
        "indexSha256": digest_bytes(files["index.json"]),
        "componentCount": len(components),
        "documentCount": len(documents),
        "coverageIssueCount": len(coverage),
        "platforms": sorted(platforms),
        "generatedFiles": sorted(generated),
    }


def digest_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def verify_embedded_legal(root: Path, app_jar: Path, pin: str,
                          expected_platform: str | None,
                          generated_directory: Path | None) -> dict:
    jar = owned_regular_file(root, app_jar, "Application JAR")
    files: dict[str, bytes] = {}
    directories: set[str] = set()
    seen: set[str] = set()
    case_seen: set[str] = set()
    total = 0
    try:
        with zipfile.ZipFile(jar) as archive:
            entries = archive.infolist()
            if len(entries) > MAX_ZIP_ENTRIES:
                raise ManifestError("Application JAR has an oversized entry set")
            for entry in entries:
                name = entry.orig_filename
                if not name.startswith(LEGAL_RESOURCE_PREFIX):
                    continue
                if name in seen:
                    raise ManifestError("Duplicate embedded legal JAR entry: " + name)
                seen.add(name)
                if name.casefold() in case_seen:
                    raise ManifestError("Case-colliding embedded legal JAR entry: " + name)
                case_seen.add(name.casefold())
                kind = stat.S_IFMT(entry.external_attr >> 16)
                if entry.flag_bits & 1:
                    raise ManifestError("Encrypted embedded legal JAR entry rejected")
                if entry.is_dir():
                    if kind not in (0, stat.S_IFDIR):
                        raise ManifestError("Embedded legal directory has a conflicting file type")
                    relative = name[len(LEGAL_RESOURCE_PREFIX):].rstrip("/")
                    if relative:
                        legal_name(relative, "Embedded legal directory path")
                    directories.add(relative)
                    continue
                if kind not in (0, stat.S_IFREG):
                    raise ManifestError("Linked/special embedded legal JAR entry rejected")
                relative = legal_name(name[len(LEGAL_RESOURCE_PREFIX):])
                if entry.file_size > MAX_LEGAL_FILE:
                    raise ManifestError("Embedded legal JAR entry is oversized: " + relative)
                with archive.open(entry) as stream:
                    data = stream.read(MAX_LEGAL_FILE + 1)
                if len(data) != entry.file_size or len(data) > MAX_LEGAL_FILE:
                    raise ManifestError("Embedded legal JAR entry changed or exceeded its bound: " + relative)
                total += len(data)
                if total > MAX_LEGAL_TOTAL or len(files) >= MAX_LEGAL_FILES:
                    raise ManifestError("Embedded legal resources exceed their bounded file/size limits")
                files[relative] = data
    except zipfile.BadZipFile as error:
        raise ManifestError("Application JAR is not a valid ZIP archive") from error
    if not files:
        raise ManifestError("Application JAR contains no embedded legal resources at the verified prefix")
    summary = validate_generated_legal(files, pin, expected_platform)
    expected_directories = {""}
    for name in files:
        for parent in PurePosixPath(name).parents:
            expected_directories.add("" if str(parent) == "." else str(parent))
    unexpected = sorted(directories - expected_directories)
    if unexpected:
        raise ManifestError("Unexpected/stale embedded legal directory entries: " + ", ".join(unexpected[:8]))
    source_matched = False
    if generated_directory is not None:
        generated = read_legal_directory(root, generated_directory)
        if generated != files:
            changed = sorted(set(generated) ^ set(files)) or sorted(
                name for name in set(generated) & set(files) if generated[name] != files[name])
            raise ManifestError("Embedded legal bytes differ from the generated directory: "
                                + ", ".join(changed[:8]))
        source_matched = True
    return {
        "verified": True,
        "verificationScope": ("desktop application-image JAR consumed by the installer task; "
                              "the .deb/.dmg/.msi container itself is not re-opened"),
        "resourcePrefix": LEGAL_RESOURCE_PREFIX,
        "appJarPath": jar.relative_to(root).as_posix(),
        "appJarBytes": jar.stat().st_size,
        "appJarSha256": digest(jar),
        "indexSha256": summary["indexSha256"],
        "platforms": summary["platforms"],
        "componentCount": summary["componentCount"],
        "documentCount": summary["documentCount"],
        "coverageIssueCount": summary["coverageIssueCount"],
        "generatedSourceMatched": source_matched,
        "files": [{"path": name, "bytes": len(data), "sha256": digest_bytes(data)}
                  for name, data in sorted(files.items())],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help=argparse.SUPPRESS)
    parser.add_argument("--base", type=Path, default=ROOT / "composeApp/build/compose/binaries/main",
                        help=argparse.SUPPRESS)
    parser.add_argument("--newer-than", type=Path, help="Accept only package files strictly newer than this build marker")
    parser.add_argument("--expected-source-sha", default="")
    parser.add_argument("--repository", default="")
    parser.add_argument("--task", default="")
    parser.add_argument("--app-jar", type=Path,
                        help="Exact desktop application-image JAR consumed by the installer task")
    parser.add_argument("--desktop-legal-dir", type=Path,
                        help="Generated legal directory to compare against the embedded JAR bytes")
    parser.add_argument("--expected-legal-platform", default="",
                        help="Require the embedded index to name exactly this desktop platform")
    parser.add_argument("--require-embedded-legal", action="store_true",
                        help="Fail unless --app-jar legal bytes are verified (production package path)")
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
    expected_platform = bounded_text(args.expected_legal_platform, "expected legal platform", 64)
    if expected_platform and expected_platform not in LEGAL_PLATFORMS:
        raise ManifestError("Unknown expected legal platform")
    if args.require_embedded_legal and args.app_jar is None:
        raise ManifestError("--require-embedded-legal needs the exact --app-jar")
    if expected_platform and args.app_jar is None:
        raise ManifestError("--expected-legal-platform needs --app-jar for embedded byte verification")
    if args.desktop_legal_dir is not None and args.app_jar is None:
        raise ManifestError("--desktop-legal-dir needs --app-jar for embedded byte verification")
    embedded = {
        "verified": False,
        "verificationScope": "not requested; no packaged JAR bytes were compared",
        "resourcePrefix": LEGAL_RESOURCE_PREFIX,
        "appJarPath": "",
        "appJarBytes": 0,
        "appJarSha256": "",
        "indexSha256": "",
        "platforms": [],
        "componentCount": 0,
        "documentCount": 0,
        "coverageIssueCount": 0,
        "generatedSourceMatched": False,
        "files": [],
    }
    if args.app_jar is not None:
        embedded = verify_embedded_legal(root, args.app_jar, pin, expected_platform or None,
                                         args.desktop_legal_dir)
    if args.require_embedded_legal and not embedded["verified"]:
        raise ManifestError("Production package path requires verified embedded legal bytes")
    source_legal = root / "composeApp/src/commonMain/composeResources/files/legal/index.json"
    source_legal_sha = exact_hex(digest(source_legal), "legal index sha", 64) \
        if source_legal.is_file() and not source_legal.is_symlink() else ""
    # legalIndexSha256 is the actual embedded index on the production path; the
    # source-tree preview stays separately auditable and is never presented as
    # the packaged bytes.
    legal_sha = embedded["indexSha256"] if embedded["verified"] else source_legal_sha
    legal_scope = "verified-embedded-app-jar" if embedded["verified"] else "source-tree-unverified"
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
            "embeddedLegalVerified": embedded["verified"],
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
            "legalIndexScope": legal_scope,
            "legalIndexSourceTreeSha256": source_legal_sha,
        },
        "embeddedLegal": embedded,
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
            "Embedded legal verification covers the desktop application-image JAR consumed by the installer task; "
            "the installer container itself is not re-opened, and native/JDK/source correspondence remains unverified.",
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
    except (ManifestError, OSError, ValueError, UnicodeError) as error:
        print("Package manifest failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
