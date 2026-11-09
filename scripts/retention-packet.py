#!/usr/bin/env python3
"""Create and strictly verify a small nonproduction draft-Release retention packet.

The packet contains only authored non-audio, nonsecret bytes and provenance. It is
not an application package, source archive, signature or release acceptance claim.
No network, credential, cache or artifact-storage operation is performed here.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys

SCHEMA_VERSION = 1
KIND = "veyra-retention-canary"
MAX_FILE = 16 * 1024
MAX_TOTAL = 64 * 1024
HEX40 = re.compile(r"[0-9a-f]{40}")
HEX64 = re.compile(r"[0-9a-f]{64}")
RUN_ID = re.compile(r"[1-9][0-9]{0,19}")
FILES = ("payload.bin", "identity.json", "manifest.json", "SHA256SUMS")


class PacketError(ValueError):
    pass


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def safe_text(value: str, name: str, maximum: int = 256) -> str:
    if not isinstance(value, str) or not value or len(value) > maximum:
        raise PacketError(name + " is empty/oversized")
    if any(ord(char) < 32 or ord(char) == 127 for char in value):
        raise PacketError(name + " contains control/secret-bearing bytes")
    return value


def safe_path(value: str, name: str) -> str:
    text = safe_text(value, name, 512)
    path = Path(text)
    if path.is_absolute() or "\\" in text or ".." in path.parts or "." in path.parts:
        raise PacketError(name + " is not a safe relative path")
    return text


def output_directory(path: Path) -> Path:
    target = Path(os.path.abspath(path))
    for ancestor in (target, *target.parents):
        if ancestor.is_symlink():
            raise PacketError("symlink in packet output path")
    target.mkdir(parents=True, exist_ok=True)
    if not target.is_dir() or target.is_symlink():
        raise PacketError("packet output is not a regular directory")
    if any(target.iterdir()):
        raise PacketError("packet output directory must be empty")
    return target


def write_file(directory: Path, name: str, data: bytes) -> None:
    if len(data) > MAX_FILE:
        raise PacketError(name + " exceeds the per-file bound")
    if sum((directory / item).stat().st_size for item in FILES if (directory / item).is_file()) + len(data) > MAX_TOTAL:
        raise PacketError("packet exceeds the total bound")
    path = directory / name
    if path.exists() or path.is_symlink() or not path.resolve().is_relative_to(directory):
        raise PacketError("packet file already exists or escapes its directory")
    with path.open("xb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def manifest_bytes(identity: bytes, payload: bytes) -> bytes:
    value = {
        "schemaVersion": SCHEMA_VERSION,
        "kind": KIND,
        "nonproduction": True,
        "files": [
            {"name": "payload.bin", "bytes": len(payload), "sha256": digest(payload)},
            {"name": "identity.json", "bytes": len(identity), "sha256": digest(identity)},
        ],
    }
    value["totalBytes"] = sum(item["bytes"] for item in value["files"])
    if value["totalBytes"] > MAX_TOTAL:
        raise PacketError("packet manifest exceeds the total bound")
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")


def sums_bytes(files: dict[str, bytes]) -> bytes:
    return ("".join(f"{digest(files[name])}  {name}\n" for name in sorted(files))).encode("ascii")


def create(args: argparse.Namespace) -> int:
    commit = safe_text(args.commit_sha, "commit sha", 40)
    pin = safe_text(args.pin, "pin", 40)
    schema = safe_text(args.schema_sha256, "schema sha256", 64)
    run_id = safe_text(args.run_id, "run id", 20)
    attempt = safe_text(args.run_attempt, "run attempt", 20)
    if not HEX40.fullmatch(commit) or not HEX40.fullmatch(pin):
        raise PacketError("commit/pin must be lowercase 40-hex")
    if not HEX64.fullmatch(schema):
        raise PacketError("schema hash must be lowercase 64-hex")
    if not RUN_ID.fullmatch(run_id) or not RUN_ID.fullmatch(attempt):
        raise PacketError("run id/attempt must be positive decimal numbers")
    tag = safe_text(args.release_tag, "release tag", 200)
    if tag != f"veyra-draft-canary-{run_id}-{attempt}":
        raise PacketError("canary release tag must be the unique run id/attempt tag")
    generated = args.generated_at or datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    safe_text(generated, "generated time", 40)
    identity = {
        "schemaVersion": SCHEMA_VERSION,
        "kind": KIND,
        "nonproduction": True,
        "repository": safe_text(args.repository, "repository"),
        "commitSha": commit,
        "releaseTag": tag,
        "runId": run_id,
        "runAttempt": attempt,
        "workflowRef": safe_text(args.workflow_ref, "workflow ref", 512),
        "ref": safe_text(args.ref, "ref", 256),
        "kmpytPin": pin,
        "schemaSha256": schema,
        "hostLabel": safe_text(args.host_label, "host label", 128),
        "javaVersion": safe_text(args.java_version, "java version", 256),
        "gitVersion": safe_text(args.git_version, "git version", 256),
        "ghVersion": safe_text(args.gh_version, "gh version", 256),
        "generatedAtUtc": generated,
        "assetPolicy": {
            "draft": True,
            "prerelease": True,
            "nonproduction": True,
            "containsAudio": False,
            "containsCredentials": False,
            "maxFileBytes": MAX_FILE,
            "maxTotalBytes": MAX_TOTAL,
        },
    }
    identity_data = (json.dumps(identity, indent=2, sort_keys=True) + "\n").encode("utf-8")
    payload = (
        "Veyra nonproduction retention canary. No audio, credentials or release claim.\n"
        f"repository={identity['repository']}\ncommit={commit}\nrun={run_id}/{attempt}\n"
        f"tag={tag}\npin={pin}\n"
    ).encode("utf-8")
    if len(payload) > MAX_FILE:
        raise PacketError("canary payload exceeds bound")
    manifest = manifest_bytes(identity_data, payload)
    files = {"payload.bin": payload, "identity.json": identity_data, "manifest.json": manifest}
    sums = sums_bytes(files)
    if len(sums) > MAX_FILE:
        raise PacketError("SHA256SUMS exceeds bound")
    directory = output_directory(args.output)
    for name in ("payload.bin", "identity.json", "manifest.json"):
        write_file(directory, name, files[name])
    write_file(directory, "SHA256SUMS", sums)
    receipt = {
        "created": True,
        "directory": str(directory),
        "commitSha": commit,
        "releaseTag": tag,
        "runId": run_id,
        "runAttempt": attempt,
        "files": {name: {"bytes": len(data), "sha256": digest(data)} for name, data in sorted(files.items())},
        "totalBytes": sum(len(data) for data in files.values()) + len(sums),
        "manifestSha256": digest(manifest),
        "sha256SumsSha256": digest(sums),
    }
    print(json.dumps(receipt, indent=2, sort_keys=True))
    return 0


def read_bounded(path: Path, maximum: int = MAX_FILE) -> bytes:
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_size <= 0 or info.st_size > maximum:
        raise PacketError("packet file is not a bounded regular nonempty file: " + path.name)
    data = path.read_bytes()
    if len(data) > maximum:
        raise PacketError("packet file grew past its bound")
    return data


def load_json(data: bytes, name: str) -> dict:
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise PacketError("duplicate JSON key in " + name)
            result[key] = value
        return result
    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=pairs)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise PacketError("invalid JSON in " + name) from error
    if not isinstance(value, dict):
        raise PacketError(name + " must be an object")
    return value


def verify(args: argparse.Namespace) -> int:
    directory = Path(os.path.abspath(args.directory))
    if not directory.is_dir() or directory.is_symlink():
        raise PacketError("packet directory is not a regular directory")
    entries = sorted(path.name for path in directory.iterdir())
    if entries != sorted(FILES):
        raise PacketError("packet file set is not exact: " + ", ".join(entries))
    files = {name: read_bounded(directory / name) for name in FILES}
    if sum(len(data) for data in files.values()) > MAX_TOTAL:
        raise PacketError("packet exceeds total bound")
    manifest = load_json(files["manifest.json"], "manifest.json")
    identity = load_json(files["identity.json"], "identity.json")
    if manifest.get("schemaVersion") != SCHEMA_VERSION or manifest.get("kind") != KIND or manifest.get("nonproduction") is not True:
        raise PacketError("manifest schema/kind/nonproduction mismatch")
    records = manifest.get("files")
    if not isinstance(records, list) or len(records) != 2:
        raise PacketError("manifest must contain exactly payload and identity")
    by_name = {}
    for record in records:
        if not isinstance(record, dict) or set(record) != {"name", "bytes", "sha256"}:
            raise PacketError("manifest file record shape is invalid")
        name = record["name"]
        if name not in ("payload.bin", "identity.json") or name in by_name:
            raise PacketError("manifest file name is invalid/duplicated")
        if type(record["bytes"]) is not int or not 0 < record["bytes"] <= MAX_FILE or not HEX64.fullmatch(record["sha256"]):
            raise PacketError("manifest file record bounds/hash are invalid")
        if len(files[name]) != record["bytes"] or digest(files[name]) != record["sha256"]:
            raise PacketError("packet file does not match the manifest: " + name)
        by_name[name] = record
    total = sum(item["bytes"] for item in records)
    if manifest.get("totalBytes") != total or total > MAX_TOTAL:
        raise PacketError("manifest totalBytes is invalid")
    expected_sums = sums_bytes({name: files[name] for name in ("payload.bin", "identity.json", "manifest.json")})
    if files["SHA256SUMS"] != expected_sums:
        raise PacketError("SHA256SUMS does not exactly match the packet")
    expected = {
        "commitSha": args.expected_sha,
        "releaseTag": args.expected_tag,
        "runId": args.expected_run_id,
        "runAttempt": args.expected_run_attempt,
        "repository": args.expected_repository,
        "kmpytPin": args.expected_pin,
        "schemaSha256": args.expected_schema_sha256,
        "nonproduction": True,
        "kind": KIND,
        "schemaVersion": SCHEMA_VERSION,
    }
    for key, value in expected.items():
        if identity.get(key) != value:
            raise PacketError("identity field mismatch: " + key)
    if identity.get("assetPolicy") != {
        "draft": True, "prerelease": True, "nonproduction": True,
        "containsAudio": False, "containsCredentials": False,
        "maxFileBytes": MAX_FILE, "maxTotalBytes": MAX_TOTAL,
    }:
        raise PacketError("identity asset policy mismatch")
    receipt = {
        "verified": True,
        "directory": str(directory),
        "commitSha": args.expected_sha,
        "releaseTag": args.expected_tag,
        "runId": args.expected_run_id,
        "runAttempt": args.expected_run_attempt,
        "files": {name: {"bytes": len(files[name]), "sha256": digest(files[name])} for name in FILES},
        "manifestSha256": digest(files["manifest.json"]),
        "sha256SumsSha256": digest(files["SHA256SUMS"]),
        "totalBytes": sum(len(data) for data in files.values()),
    }
    print(json.dumps(receipt, indent=2, sort_keys=True))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    create_parser = sub.add_parser("create")
    create_parser.add_argument("--output", type=Path, required=True)
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
    create_parser.add_argument("--java-version", required=True)
    create_parser.add_argument("--git-version", required=True)
    create_parser.add_argument("--gh-version", required=True)
    create_parser.add_argument("--generated-at")
    create_parser.set_defaults(func=create)
    verify_parser = sub.add_parser("verify")
    verify_parser.add_argument("--directory", type=Path, required=True)
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
    except (PacketError, OSError) as error:
        print("Retention packet verification failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
