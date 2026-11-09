#!/usr/bin/env python3
"""Collect one bounded build-retention lane packet and strictly verify it.

This creates no release and performs no network operation. It copies only
explicitly allowlisted package/output files, sanitized JUnit XML, generated
legal/native inventory, package provenance and a diagnostic summary into a
small support archive plus a manifest. It never reads tool homes, build caches,
raw logs, credentials or the whole worktree.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import xml.etree.ElementTree as ET
import zipfile

SCHEMA_VERSION = 1
KIND = "veyra-build-retention-lane"
MAX_SUPPORT_FILE = 32 * 1024 * 1024
MAX_SUPPORT_TOTAL = 64 * 1024 * 1024
MAX_PACKAGE = 1_900_000_000
MAX_TOTAL = 4_000_000_000
MAX_XML_FILES = 2_000
MAX_LEGAL_FILES = 2_000
HEX40 = re.compile(r"[0-9a-f]{40}")
HEX64 = re.compile(r"[0-9a-f]{64}")
SECRET = re.compile(r"gho_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+|x-access-token|Authorization:|-----BEGIN [A-Z ]+PRIVATE KEY-----")


class LaneError(ValueError):
    pass


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_digest(path: Path, maximum: int) -> str:
    value = hashlib.sha256()
    total = 0
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            total += len(block)
            if total > maximum:
                raise LaneError("File exceeds its byte bound: " + path.name)
            value.update(block)
    return value.hexdigest()


def safe_text(value: str, name: str, maximum: int = 512) -> str:
    if not isinstance(value, str) or not value or len(value) > maximum:
        raise LaneError(name + " is empty/oversized")
    if any(ord(char) < 32 or ord(char) == 127 for char in value):
        raise LaneError(name + " contains control/secret-bearing bytes")
    return value


def safe_multiline(value: str, name: str, maximum: int = 512) -> str:
    if not isinstance(value, str) or not value or len(value) > maximum:
        raise LaneError(name + " is empty/oversized")
    if any((ord(char) < 32 and char not in "\n\t") or ord(char) == 127 for char in value):
        raise LaneError(name + " contains control/secret-bearing bytes")
    return value


def safe_name(value: str, name: str) -> str:
    text = safe_text(value, name, 256)
    if "/" in text or "\\" in text or text in (".", "..") or ":" in text:
        raise LaneError(name + " is not a simple asset name")
    return text


def exact_hex(value: str, name: str, length: int) -> str:
    if not (HEX40 if length == 40 else HEX64).fullmatch(value):
        raise LaneError(name + " must be lowercase " + str(length) + "-hex")
    return value


def checked_path(path: Path, root: Path, directory: bool = False) -> Path:
    absolute = Path(os.path.abspath(path))
    for ancestor in (absolute, *absolute.parents):
        if ancestor.is_symlink():
            raise LaneError("Symlinked lane input/ancestor rejected: " + str(path))
    resolved = absolute.resolve()
    if not resolved.is_relative_to(root.resolve()):
        raise LaneError("Escaping lane input rejected: " + str(path))
    if directory and (not resolved.is_dir() or resolved.is_symlink()):
        raise LaneError("Rejected non-directory lane input: " + str(path))
    if not directory and (not resolved.is_file() or resolved.is_symlink()):
        raise LaneError("Rejected nonregular lane input: " + str(path))
    return resolved


def owned_file(path: Path, root: Path, maximum: int) -> Path:
    resolved = checked_path(path, root)
    size = resolved.stat().st_size
    if size <= 0 or size > maximum:
        raise LaneError("Rejected empty/oversized lane input: " + str(path))
    return resolved


def read_support(path: Path, root: Path, maximum: int = MAX_SUPPORT_FILE, scan: bool = True) -> bytes:
    resolved = owned_file(path, root, maximum)
    data = resolved.read_bytes()
    if scan:
        try:
            text = data.decode("utf-8")
        except UnicodeError:
            raise LaneError("Support file is not UTF-8: " + path.name)
        if SECRET.search(text):
            raise LaneError("Credential-like material rejected from support file: " + path.name)
    return data


def output_directory(path: Path) -> Path:
    target = Path(os.path.abspath(path))
    for ancestor in (target, *target.parents):
        if ancestor.is_symlink():
            raise LaneError("symlink in lane output path")
    target.mkdir(parents=True, exist_ok=True)
    if not target.is_dir() or target.is_symlink() or any(target.iterdir()):
        raise LaneError("lane output directory must be empty")
    return target


def write_new(directory: Path, name: str, data: bytes, maximum: int = MAX_PACKAGE) -> None:
    if len(data) > maximum:
        raise LaneError("lane asset exceeds its bound: " + name)
    path = directory / name
    if path.exists() or path.is_symlink() or not path.resolve().is_relative_to(directory):
        raise LaneError("lane asset already exists or escapes output: " + name)
    with path.open("xb") as stream:
        stream.write(data)


def copy_new(directory: Path, name: str, source: Path, maximum: int = MAX_PACKAGE) -> int:
    path = directory / name
    if path.exists() or path.is_symlink() or not path.resolve().is_relative_to(directory):
        raise LaneError("lane asset already exists or escapes output: " + name)
    size = 0
    with source.open("rb") as input_stream, path.open("xb") as output_stream:
        for block in iter(lambda: input_stream.read(1024 * 1024), b""):
            size += len(block)
            if size > maximum:
                raise LaneError("lane asset grew past its bound: " + name)
            output_stream.write(block)
    return size


def zip_info(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    return info


def make_support_zip(entries: dict[str, bytes]) -> bytes:
    total = sum(len(data) for data in entries.values())
    if total > MAX_SUPPORT_TOTAL:
        raise LaneError("support archive exceeds total bound")
    import io
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name in sorted(entries):
            archive.writestr(zip_info(name), entries[name])
    data = output.getvalue()
    if len(data) > MAX_SUPPORT_TOTAL:
        raise LaneError("support archive exceeds compressed bound")
    return data


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1] if "}" in tag else tag


def sanitize_xml(data: bytes, name: str) -> tuple[bytes | None, dict, str | None]:
    try:
        root = ET.fromstring(data)
    except ET.ParseError:
        return None, {}, name + ": malformed XML excluded from retention"
    counts = {}
    for key in ("tests", "failures", "errors", "skipped"):
        try:
            counts[key] = int(root.attrib.get(key, 0))
        except (TypeError, ValueError):
            counts[key] = 0
    for parent in list(root.iter()):
        for child in list(parent):
            tag = local_name(child.tag)
            if tag in ("system-out", "system-err", "properties"):
                parent.remove(child)
            elif tag in ("failure", "error", "skipped"):
                child.text = None
                child.tail = None
                child.attrib.pop("message", None)
                for extra in list(child.attrib):
                    if extra != "type":
                        child.attrib.pop(extra, None)
    for element in root.iter():
        tag = local_name(element.tag)
        if tag == "testsuite":
            for key in list(element.attrib):
                if key not in ("name", "tests", "failures", "errors", "skipped", "time"):
                    element.attrib.pop(key, None)
        elif tag == "testcase":
            for key in list(element.attrib):
                if key not in ("classname", "name", "time"):
                    element.attrib.pop(key, None)
    return ET.tostring(root, encoding="utf-8", xml_declaration=True), counts, None


def package_zip(name: str, directory: Path, output_directory: Path, root: Path) -> int:
    files: list[tuple[Path, str, int]] = []
    total = 0
    for path in sorted(directory.rglob("*")):
        if path.is_symlink():
            raise LaneError("symlink rejected in package directory: " + str(path))
        if path.is_dir():
            continue
        if not path.is_file():
            raise LaneError("nonregular file rejected in package directory: " + str(path))
        relative = path.relative_to(directory).as_posix()
        if not relative or any(part in ("", ".", "..") for part in relative.split("/")):
            raise LaneError("unsafe package path: " + relative)
        size = path.stat().st_size
        if size > MAX_PACKAGE:
            raise LaneError("oversized file in package directory: " + relative)
        total += size
        if total > MAX_PACKAGE:
            raise LaneError("package directory exceeds its byte bound: " + name)
        files.append((path, relative, size))
    if not files:
        raise LaneError("package directory is empty: " + name)
    destination = output_directory / name
    if destination.exists() or destination.is_symlink():
        raise LaneError("package archive already exists: " + name)
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
        for path, relative, size in files:
            info = zip_info(relative)
            info.file_size = size
            with archive.open(info, "w") as target, path.open("rb") as source:
                shutil.copyfileobj(source, target, 1024 * 1024)
    if destination.stat().st_size <= 0 or destination.stat().st_size > MAX_PACKAGE:
        raise LaneError("package archive exceeds its byte bound: " + name)
    return destination.stat().st_size


def gather_support(args: argparse.Namespace, root: Path) -> tuple[dict[str, bytes], dict]:
    entries: dict[str, bytes] = {}
    summary = {"files": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0, "issues": []}
    for index, value in enumerate(args.xml_root):
        resolved = checked_path(Path(value), root, directory=True)
        for path in sorted(resolved.rglob("TEST-*.xml")):
            if summary["files"] >= MAX_XML_FILES:
                raise LaneError("Too many XML files")
            relative = path.relative_to(resolved).as_posix()
            sanitized, counts, issue = sanitize_xml(read_support(path, root, scan=False), value)
            if issue:
                summary["issues"].append(issue)
                continue
            if SECRET.search(sanitized.decode("utf-8", "replace")):
                raise LaneError("Credential-like material survived XML sanitization: " + path.name)
            summary["files"] += 1
            for key in ("tests", "failures", "errors", "skipped"):
                summary[key] += counts[key]
            entries[f"xml/{index}/{relative}"] = sanitized
    for index, value in enumerate(args.legal):
        legal = checked_path(Path(value), root, directory=True)
        index_bytes = read_support(legal / "index.json", root)
        legal_index = json.loads(index_bytes)
        hashes = legal_index.get("fileHashes") if isinstance(legal_index, dict) else None
        if not isinstance(hashes, dict) or not hashes or len(hashes) > MAX_LEGAL_FILES:
            raise LaneError("Legal index requires a bounded nonempty fileHashes map")
        entries[f"legal/{index}/index.json"] = index_bytes
        legal_total = sum(len(data) for data in entries.values())
        for name, expected in sorted(hashes.items()):
            # Generated legal outputs have a fixed set of root documents and
            # content-addressed notices. Never scan the directory or follow an
            # arbitrary index path into other repository files.
            if name not in ("INDEX.txt", "LICENSE", "NOTICE.md", "THIRD_PARTY_NOTICES.txt",
                            "kmpYT-LICENSE", "kmpYT-THIRD_PARTY_NOTICES.md") and not (
                    isinstance(name, str) and re.fullmatch(r"notices/[0-9a-f]{64}\.txt", name)):
                raise LaneError("Unsafe or unsupported indexed legal path")
            exact_hex(expected, "indexed legal SHA-256", 64)
            if legal_total >= MAX_SUPPORT_TOTAL:
                raise LaneError("Legal support files exceed the total bound")
            data = read_support(legal / name, legal,
                                min(MAX_SUPPORT_FILE, MAX_SUPPORT_TOTAL - legal_total))
            if digest(data) != expected:
                raise LaneError("Indexed legal file hash mismatch: " + name)
            entries[f"legal/{index}/{name}"] = data
            legal_total += len(data)
    if args.native_diagnostic:
        entries["native/build-diagnostic.json"] = read_support(
            Path(args.native_diagnostic), root, 64 * 1024)
    if args.inventory:
        path = Path(args.inventory)
        entries["inventory/" + safe_name(path.name, "inventory name")] = read_support(path, root)
    if args.packages_json:
        entries["packages.json"] = read_support(Path(args.packages_json), root, 4 * 1024 * 1024)
    return entries, summary


def manifest_packages(path: Path, root: Path, commit: str, pin: str, schema: str) -> list[Path]:
    resolved = checked_path(path, root)
    if resolved.stat().st_size > 4 * 1024 * 1024:
        raise LaneError("package manifest is oversized")
    value = json.loads(resolved.read_text())
    source = value.get("source") if isinstance(value, dict) else None
    if not isinstance(source, dict):
        raise LaneError("package manifest source block is missing")
    if source.get("commitSha") != commit or source.get("kmpytPin") != pin or source.get("schemaSha256") != schema:
        raise LaneError("package manifest source/pin/schema does not match the lane")
    marker = value.get("build", {}).get("marker") if isinstance(value.get("build"), dict) else None
    if marker in (None, "", "not-requested", "external-marker"):
        raise LaneError("package manifest does not prove a fresh build marker")
    records = value.get("packages")
    if not isinstance(records, list) or not records:
        raise LaneError("package manifest has no fresh packages")
    result = []
    for record in records:
        if not isinstance(record, dict):
            raise LaneError("package manifest record is invalid")
        relative = record.get("path")
        size = record.get("bytes")
        sha = record.get("sha256")
        if not isinstance(relative, str) or type(size) is not int or not 0 < size <= MAX_PACKAGE or not isinstance(sha, str) or not HEX64.fullmatch(sha):
            raise LaneError("package manifest record bounds/hash are invalid")
        source_path = owned_file(root / relative, root, MAX_PACKAGE)
        if source_path.stat().st_size != size or file_digest(source_path, MAX_PACKAGE) != sha:
            raise LaneError("package manifest path/hash does not match the fresh file: " + relative)
        result.append(source_path)
    return result

def create(args: argparse.Namespace) -> int:
    root = Path(os.path.abspath(args.source_root))
    repository = safe_text(args.repository, "repository")
    commit = exact_hex(args.commit_sha, "commit", 40)
    pin = exact_hex(args.pin, "pin", 40)
    schema = exact_hex(args.schema_sha256, "schema", 64)
    platform = safe_name(args.platform, "platform")
    run_id = safe_text(args.run_id, "run id", 20)
    attempt = safe_text(args.run_attempt, "run attempt", 20)
    tag = safe_text(args.release_tag, "release tag", 200)
    if tag != f"veyra-draft-{run_id}-{attempt}-{platform}":
        raise LaneError("lane tag must be the unique run/attempt/platform tag")
    if args.lane_outcome not in ("success", "failed"):
        raise LaneError("lane outcome must be success/failed")
    diagnostic = safe_multiline(args.diagnostic, "diagnostic", 16 * 1024)
    if SECRET.search(diagnostic):
        raise LaneError("credential-like material rejected from diagnostic summary")
    supports, test_summary = gather_support(args, root)
    generated = args.generated_at or datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    provenance = {
        "schemaVersion": SCHEMA_VERSION, "kind": KIND, "repository": repository,
        "commitSha": commit, "kmpytPin": pin, "schemaSha256": schema,
        "releaseTag": tag, "runId": run_id, "runAttempt": attempt,
        "workflowRef": safe_text(args.workflow_ref, "workflow ref", 512),
        "ref": safe_text(args.ref, "ref", 256), "platform": platform,
        "laneOutcome": args.lane_outcome, "hostLabel": safe_text(args.host_label, "host label", 128),
        "generatedAtUtc": generated,
        "releaseReady": False, "signed": False, "installed": False, "nonproductionDraft": True,
        "limits": [
            "Draft retention packet only; no release publication or acceptance.",
            "Package bytes are unsigned unless separately recorded; installation/signing/native verification is not established.",
            "Source-only legal index does not cover the actual binary runtime; supplied inventory remains authoritative.",
        ],
    }
    supports["provenance.json"] = (json.dumps(provenance, indent=2, sort_keys=True) + "\n").encode()
    supports["diagnostic.txt"] = (diagnostic.rstrip() + "\n").encode()
    support_zip = make_support_zip(supports)
    output = output_directory(args.output)
    files = [{"name": "support.zip", "role": "support", "bytes": len(support_zip), "sha256": digest(support_zip)}]
    write_new(output, "support.zip", support_zip, MAX_SUPPORT_TOTAL)
    packages = []
    if args.packages_json:
        for source in manifest_packages(Path(args.packages_json), root, commit, pin, schema):
            name = safe_name(source.name, "package name")
            if any(item["name"] == name for item in files):
                raise LaneError("duplicate package asset name: " + name)
            size = copy_new(output, name, source)
            files.append({"name": name, "role": "package", "bytes": size, "sha256": file_digest(output / name, MAX_PACKAGE)})
            packages.append(name)
    for path_value in args.package:
        source = owned_file(Path(path_value), root, MAX_PACKAGE)
        name = safe_name(source.name, "package name")
        if any(item["name"] == name for item in files):
            raise LaneError("duplicate package asset name: " + name)
        size = copy_new(output, name, source)
        files.append({"name": name, "role": "package", "bytes": size, "sha256": file_digest(output / name, MAX_PACKAGE)})
        packages.append(name)
    for value in args.directory:
        if "=" not in value:
            raise LaneError("--directory requires NAME=PATH")
        name, path_value = value.split("=", 1)
        name = safe_name(name, "directory asset name") + ".zip"
        source = checked_path(Path(path_value), root, directory=True)
        if any(item["name"] == name for item in files):
            raise LaneError("duplicate package asset name: " + name)
        size = package_zip(name, source, output, root)
        files.append({"name": name, "role": "package", "bytes": size, "sha256": file_digest(output / name, MAX_PACKAGE)})
        packages.append(name)
    if args.lane_outcome == "success" and not packages:
        raise LaneError("a successful lane requires at least one actual package/output asset")
    if sum(item["bytes"] for item in files) > MAX_TOTAL:
        raise LaneError("lane packet exceeds total bound")
    manifest = {
        "schemaVersion": SCHEMA_VERSION, "kind": KIND, "repository": repository,
        "commitSha": commit, "kmpytPin": pin, "schemaSha256": schema,
        "releaseTag": tag, "runId": run_id, "runAttempt": attempt,
        "platform": platform, "laneOutcome": args.lane_outcome,
        "hostLabel": safe_text(args.host_label, "host label", 128),
        "files": sorted(files, key=lambda item: item["name"]),
        "packages": sorted(packages), "testSummary": test_summary,
        "supportArchive": {"name": "support.zip", "bytes": len(support_zip), "sha256": digest(support_zip),
                           "entries": {name: {"bytes": len(data), "sha256": digest(data)} for name, data in sorted(supports.items())}},
        "limits": provenance["limits"],
    }
    manifest_data = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
    write_new(output, "manifest.json", manifest_data, MAX_SUPPORT_FILE)
    receipt = {"created": True, "directory": str(output), "platform": platform, "tag": tag,
               "laneOutcome": args.lane_outcome, "packages": packages,
               "files": {item["name"]: {"bytes": item["bytes"], "sha256": item["sha256"]} for item in files},
               "manifestSha256": digest(manifest_data), "totalBytes": sum(item["bytes"] for item in files) + len(manifest_data)}
    print(json.dumps(receipt, indent=2, sort_keys=True))
    return 0


def verify(args: argparse.Namespace) -> int:
    directory = Path(os.path.abspath(args.directory))
    for ancestor in (directory, *directory.parents):
        if ancestor.is_symlink():
            raise LaneError("symlink in packet directory path")
    if not directory.is_dir():
        raise LaneError("packet directory is not a regular directory")
    names = {path.name for path in directory.iterdir()}
    if "manifest.json" not in names:
        raise LaneError("manifest.json is missing")
    manifest_path = directory / "manifest.json"
    if manifest_path.is_symlink() or not manifest_path.is_file() or manifest_path.stat().st_size > MAX_SUPPORT_FILE:
        raise LaneError("manifest.json is invalid")
    manifest = json.loads(manifest_path.read_text())
    if manifest.get("schemaVersion") != SCHEMA_VERSION or manifest.get("kind") != KIND:
        raise LaneError("manifest schema/kind mismatch")
    expected = {
        "commitSha": args.expected_sha, "releaseTag": args.expected_tag,
        "runId": args.expected_run_id, "runAttempt": args.expected_run_attempt,
        "repository": args.expected_repository, "kmpytPin": args.expected_pin,
        "schemaSha256": args.expected_schema_sha256, "platform": args.expected_platform,
    }
    for key, value in expected.items():
        if manifest.get(key) != value:
            raise LaneError("identity mismatch: " + key)
    if manifest.get("laneOutcome") not in ("success", "failed"):
        raise LaneError("invalid lane outcome")
    files = manifest.get("files")
    if not isinstance(files, list) or not files:
        raise LaneError("manifest files are invalid")
    declared = {item["name"]: item for item in files if isinstance(item, dict) and isinstance(item.get("name"), str)}
    if len(declared) != len(files) or set(declared) | {"manifest.json"} != names:
        raise LaneError("asset set is not exact")
    total = 0
    for name, item in declared.items():
        path = directory / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size != item.get("bytes"):
            raise LaneError("asset size/type mismatch: " + name)
        if file_digest(path, MAX_PACKAGE) != item.get("sha256"):
            raise LaneError("asset hash mismatch: " + name)
        total += item["bytes"]
    if total + manifest_path.stat().st_size > MAX_TOTAL:
        raise LaneError("packet exceeds total bound")
    support = declared.get("support.zip")
    if not support or support.get("role") != "support":
        raise LaneError("support.zip is missing")
    with zipfile.ZipFile(directory / "support.zip", "r") as archive:
        infos = archive.infolist()
        if len(infos) > MAX_XML_FILES + 32 or len({info.filename for info in infos}) != len(infos):
            raise LaneError("support archive entry set is invalid")
        support_entries = manifest.get("supportArchive", {}).get("entries")
        if not isinstance(support_entries, dict) or set(support_entries) != {info.filename for info in infos}:
            raise LaneError("support archive entries do not match the manifest")
        if any(info.file_size > MAX_SUPPORT_FILE for info in infos) or sum(info.file_size for info in infos) > MAX_SUPPORT_TOTAL:
            raise LaneError("support archive expanded size exceeds its bound")
        for info in infos:
            if info.is_dir() or info.filename.startswith("/") or ".." in PurePosixPath(info.filename).parts or "\\" in info.filename:
                raise LaneError("unsafe support archive entry")
            data = archive.read(info)
            if len(data) > MAX_SUPPORT_FILE or digest(data) != support_entries[info.filename].get("sha256") or len(data) != support_entries[info.filename].get("bytes"):
                raise LaneError("support entry mismatch: " + info.filename)
            if SECRET.search(data.decode("utf-8", "replace")):
                raise LaneError("credential-like material in support entry")
        provenance = json.loads(archive.read("provenance.json"))
        for key, value in {**expected, "laneOutcome": manifest["laneOutcome"]}.items():
            if provenance.get(key) != value:
                raise LaneError("provenance mismatch: " + key)
        if not archive.read("diagnostic.txt").strip():
            raise LaneError("diagnostic summary is empty")
    packages = manifest.get("packages")
    if not isinstance(packages, list):
        raise LaneError("package list is invalid")
    if manifest["laneOutcome"] == "success" and not packages:
        raise LaneError("successful lane has no package asset")
    if not set(packages).issubset(set(declared)):
        raise LaneError("package list does not match assets")
    receipt = {"verified": True, "platform": manifest["platform"], "tag": manifest["releaseTag"],
               "laneOutcome": manifest["laneOutcome"], "packages": sorted(packages),
               "files": {name: {"bytes": item["bytes"], "sha256": item["sha256"]} for name, item in sorted(declared.items())},
               "totalBytes": total, "manifestSha256": digest(manifest_path.read_bytes())}
    print(json.dumps(receipt, indent=2, sort_keys=True))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    create_parser = sub.add_parser("create")
    create_parser.add_argument("--source-root", type=Path, default=Path.cwd())
    create_parser.add_argument("--output", type=Path, required=True)
    create_parser.add_argument("--platform", required=True)
    create_parser.add_argument("--lane-outcome", required=True)
    create_parser.add_argument("--diagnostic", required=True)
    create_parser.add_argument("--repository", required=True)
    create_parser.add_argument("--commit-sha", required=True)
    create_parser.add_argument("--run-id", required=True)
    create_parser.add_argument("--run-attempt", required=True)
    create_parser.add_argument("--release-tag", required=True)
    create_parser.add_argument("--workflow-ref", required=True)
    create_parser.add_argument("--ref", required=True)
    create_parser.add_argument("--pin", required=True)
    create_parser.add_argument("--schema-sha256", required=True)
    create_parser.add_argument("--host-label", required=True)
    create_parser.add_argument("--generated-at")
    create_parser.add_argument("--package", action="append", default=[])
    create_parser.add_argument("--directory", action="append", default=[])
    create_parser.add_argument("--xml-root", action="append", default=[])
    create_parser.add_argument("--legal", action="append", default=[])
    create_parser.add_argument("--inventory")
    create_parser.add_argument("--native-diagnostic")
    create_parser.add_argument("--packages-json")
    create_parser.set_defaults(func=create)
    verify_parser = sub.add_parser("verify")
    verify_parser.add_argument("--directory", type=Path, required=True)
    verify_parser.add_argument("--expected-platform", required=True)
    verify_parser.add_argument("--expected-sha", required=True)
    verify_parser.add_argument("--expected-tag", required=True)
    verify_parser.add_argument("--expected-run-id", required=True)
    verify_parser.add_argument("--expected-run-attempt", required=True)
    verify_parser.add_argument("--expected-repository", required=True)
    verify_parser.add_argument("--expected-pin", required=True)
    verify_parser.add_argument("--expected-schema-sha256", required=True)
    verify_parser.set_defaults(func=verify)
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (LaneError, OSError, ValueError, KeyError, TypeError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        print("Build retention packet failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
