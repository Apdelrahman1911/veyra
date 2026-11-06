#!/usr/bin/env python3
"""Generate/check offline notices from the actual pin and exact runtime inputs.

No network, Gradle invocation, global cache sweep, license inference, or claim of
binary redistribution readiness. A POM license URL is NOT complete license text.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LEGAL = Path("composeApp/src/commonMain/composeResources/files/legal")
GPL_SHA256 = "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986"
MIT_SHA256 = "2b48ffd17a0263667e7df9b64b277520776571b1ce73365134b7e92a6d114bcb"
MAX_DOCUMENT = 4 * 1024 * 1024
MAX_NOTICES = 128 * 1024 * 1024
CONFIGURATIONS = {
    "desktop-linux-x64": "desktopRuntimeClasspath",
    "desktop-windows-x64": "desktopRuntimeClasspath",
    "desktop-macos-x64": "desktopRuntimeClasspath",
    "desktop-macos-arm64": "desktopRuntimeClasspath",
    "android-release": "releaseRuntimeClasspath",
    "android-debug": "debugRuntimeClasspath",
    "ios-arm64": "iosArm64CompileKlibraries",
    "ios-simulator-arm64": "iosSimulatorArm64CompileKlibraries",
    "ios-simulator-x64": "iosX64CompileKlibraries",
}


class NoticeError(ValueError):
    pass


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def owned(root: Path, path: Path) -> Path:
    path = path.resolve()
    if not path.is_relative_to(root.resolve()):
        raise NoticeError("Out-of-repository notice input/output rejected")
    return path


def relative_name(value: str) -> str:
    path = PurePosixPath(value)
    if not isinstance(value, str) or not value or path.is_absolute() or "\\" in value or ":" in value or ".." in path.parts:
        raise NoticeError("Unsafe relative notice/artifact path")
    if str(path) in ("", "."):
        raise NoticeError("Empty notice/artifact path")
    return str(path)


def verified_file(root: Path, record: dict) -> Path:
    path = owned(root, root / relative_name(record.get("path", "")))
    size, sha = record.get("bytes"), record.get("sha256")
    if type(size) is not int or size < 0 or not isinstance(sha, str) or not re.fullmatch(r"[a-f0-9]{64}", sha):
        raise NoticeError("Missing/invalid exact artifact hash or byte size")
    if not path.is_file() or path.stat().st_size != size or file_digest(path) != sha:
        raise NoticeError("Stale/missing/changed inventory input: " + record["path"])
    return path


def read_json(path: Path) -> dict:
    if path.stat().st_size > 32 * 1024 * 1024:
        raise NoticeError("Oversized JSON audit input")
    result = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(result, dict):
        raise NoticeError("Audit JSON must be an object")
    return result


def git(directory: Path, *arguments: str) -> bytes:
    result = subprocess.run(["git", "-C", str(directory), *arguments], capture_output=True, check=False)
    if result.returncode:
        raise NoticeError("Required local Git provenance is unavailable (no remote operation attempted)")
    return result.stdout


def source_notices(root: Path, test_worktree: Path | None = None) -> tuple[dict[str, bytes], dict]:
    root = root.resolve()
    pin = (root / "gradle/kmpyt.commit").read_text(encoding="utf-8").strip()
    if not re.fullmatch(r"[a-f0-9]{40}", pin):
        raise NoticeError("Full immutable kmpYT pin required")
    upstream = owned(root, test_worktree or root / "third_party/kmpYT")
    revision = git(upstream, "rev-parse", "HEAD").decode("ascii").strip()
    if test_worktree is None and revision != pin:
        raise NoticeError("Actual kmpYT checkout differs from gradle/kmpyt.commit")
    source_files = {"LICENSE": root / "LICENSE", "NOTICE.md": root / "NOTICE.md",
                    "kmpYT-LICENSE": upstream / "LICENSE",
                    "kmpYT-THIRD_PARTY_NOTICES.md": upstream / "THIRD_PARTY_NOTICES.md"}
    files = {}
    for name, path in source_files.items():
        path = owned(root, path)
        if not path.is_file() or path.stat().st_size > MAX_DOCUMENT:
            raise NoticeError("Complete source notice missing: " + name)
        files[name] = path.read_bytes()
    if digest(files["LICENSE"]) != GPL_SHA256:
        raise NoticeError("Veyra GPL text differs from the complete reviewed license")
    if digest(files["kmpYT-LICENSE"]) != MIT_SHA256:
        raise NoticeError("kmpYT MIT text differs from the complete reviewed grant")
    for name, upstream_name in (("kmpYT-LICENSE", "LICENSE"), ("kmpYT-THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.md")):
        if git(upstream, "show", revision + ":" + upstream_name) != files[name]:
            raise NoticeError("kmpYT notice is not the exact committed file: " + upstream_name)
    source = {"kmpytPin": pin, "kmpytNoticeRevision": revision,
              "mode": "test-only-license-worktree" if test_worktree else "actual-pinned-checkout",
              "publicationVerified": False}
    return files, source


def is_test_component(group: str, module: str) -> bool:
    return (group == "junit" or group.startswith("org.junit") or group in ("org.opentest4j", "org.hamcrest")
            or group.startswith("androidx.test")
            or (group == "org.jetbrains.kotlin" and module.startswith("kotlin-test"))
            or (group == "org.jetbrains.kotlinx" and module.startswith("kotlinx-coroutines-test"))
            or (group == "io.ktor" and module.startswith("ktor-client-mock"))
            or (group in ("androidx.compose.ui", "org.jetbrains.compose.ui") and module.startswith("ui-test")))


def load_inventory(root: Path, path: Path, pin: str) -> dict:
    inventory = read_json(owned(root, path))
    if inventory.get("schemaVersion") != 1 or inventory.get("type") != "veyra-production-dependencies":
        raise NoticeError("Unsupported production inventory schema")
    platform = inventory.get("platform")
    if platform not in CONFIGURATIONS or inventory.get("configuration") != CONFIGURATIONS[platform]:
        raise NoticeError("Not an allowlisted production platform/configuration")
    if inventory.get("project") != ":composeApp" or inventory.get("resolved") is not True or inventory.get("testConfigurationIncluded") is not False:
        raise NoticeError("Only fully resolved application production configurations are accepted")
    if inventory.get("source", {}).get("kmpytPin") != pin:
        raise NoticeError("Inventory belongs to a different kmpYT pin")
    inputs = inventory["source"].get("buildInputs", [])
    if not inputs:
        raise NoticeError("Inventory build-input fingerprints are missing")
    for record in inputs:
        verified_file(root, record)
    components = inventory.get("components", [])
    if not components or len(components) > 8192:
        raise NoticeError("Empty/oversized production component set")
    seen = set()
    for item in components:
        if item.get("id") in seen:
            raise NoticeError("Duplicate component identifier")
        seen.add(item.get("id"))
        if item.get("kind") == "project":
            if not item.get("id", "").startswith("project:"):
                raise NoticeError("Invalid project component identifier")
        elif item.get("kind") == "module":
            parts = [item.get(key, "") for key in ("group", "module", "version")]
            if any(not re.fullmatch(r"[A-Za-z0-9_.+@-]+", part) for part in parts) or item["id"] != ":".join(parts):
                raise NoticeError("Invalid resolved module coordinates")
            if is_test_component(parts[0], parts[1]):
                raise NoticeError("Test-only dependency in production: " + item["id"])
        else:
            raise NoticeError("Opaque dependency requires explicit provenance")
    artifacts = inventory.get("artifacts", [])
    if not artifacts or len(artifacts) > 16384:
        raise NoticeError("Empty/oversized production artifact set")
    for record in artifacts:
        if record.get("component") not in seen:
            raise NoticeError("Artifact has no resolved component")
        verified_file(root, record)
    for record in inventory.get("poms", []):
        verified_file(root, record)
    return inventory


def parse_pom(data: bytes) -> dict:
    if len(data) > MAX_DOCUMENT or re.search(br"<!\s*(DOCTYPE|ENTITY)\b", data, re.IGNORECASE):
        raise NoticeError("Unsafe/oversized POM document")
    xml = ET.fromstring(data)
    if xml.tag.rsplit("}", 1)[-1] != "project":
        raise NoticeError("Not a Maven project POM")
    text = lambda node, name: (node.findtext("{*}" + name) or "").strip()
    parent = xml.find("{*}parent")
    parent_id = ":".join(text(parent, field) for field in ("groupId", "artifactId", "version")) if parent is not None else None
    terms = [{"name": text(item, "name"), "url": text(item, "url")}
             for item in xml.findall("{*}licenses/{*}license")]
    return {"parent": parent_id, "licenses": terms}


def pom_licenses(identifier: str, poms: dict, seen: tuple = ()) -> tuple[list[dict], str | None]:
    if identifier in seen or len(seen) >= 32:
        raise NoticeError("Cyclic/overdeep POM license inheritance")
    if identifier not in poms:
        return [], "missing POM " + identifier
    pom = poms[identifier]
    if pom["licenses"]:
        return [dict(item, sourcePom=identifier) for item in pom["licenses"]], None
    if pom["parent"]:
        if "${" in pom["parent"]:
            return [], "unresolved POM parent property " + identifier
        return pom_licenses(pom["parent"], poms, (*seen, identifier))
    return [], "no declared POM license " + identifier


def notice_role(name: str) -> str | None:
    path = PurePosixPath(name.lower())
    base = path.name
    if base in ("al2.0", "lgpl2.1") or re.match(r"^(licen[sc]e|copying)([._ -]|$)", base):
        return "license"
    if re.match(r"^(notice|copyright|authors|third[-_]party)([._ -]|$)", base):
        return "notice"
    if any(part in ("license", "licenses", "legal") for part in path.parts[:-1]) and path.suffix in (".txt", ".md", ".rst", ".html"):
        return "license"
    return None


def decode_notice(data: bytes) -> tuple[str, str]:
    if not data or len(data) > MAX_DOCUMENT:
        raise NoticeError("Empty/oversized legal text")
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        value, encoding = data.decode("utf-16"), "utf-16"
    else:
        try:
            value, encoding = data.decode("utf-8"), "utf-8"
        except UnicodeDecodeError:
            value, encoding = data.decode("iso-8859-1"), "iso-8859-1"
    if any(ord(char) < 32 and char not in "\t\n\r\f" for char in value):
        raise NoticeError("Binary/control bytes in legal text")
    return value, encoding


def archive_notices(path: Path) -> tuple[list[dict], bool]:
    documents = []
    native = False
    total = 0

    def visit(bundle: zipfile.ZipFile, prefix: str = "", depth: int = 0) -> None:
        nonlocal native, total
        entries = bundle.infolist()
        if len(entries) > 100_000 or sum(item.file_size for item in entries) > 1024 * 1024 * 1024:
            raise NoticeError("Oversized production archive index")
        names = set()
        for item in entries:
            name = relative_name(item.filename)
            if name in names:
                raise NoticeError("Duplicate production archive entry")
            names.add(name)
            if item.is_dir():
                continue
            if name.endswith(".class") and name.startswith(("junit/", "org/junit/", "org/opentest4j/")):
                raise NoticeError("Embedded JUnit/test classes in a production artifact")
            native = native or name.lower().endswith((".so", ".dll", ".dylib", ".node", ".exe"))
            role = notice_role(name)
            if role:
                if ((item.external_attr >> 16) & 0o170000) == 0o120000 or item.file_size > MAX_DOCUMENT:
                    raise NoticeError("Symlink/oversized archive legal text")
                total += item.file_size
                if total > MAX_NOTICES:
                    raise NoticeError("Archive legal text exceeds total limit")
                data = bundle.read(item)
                _, encoding = decode_notice(data)
                documents.append({"entry": prefix + name, "role": role, "bytes": data, "encoding": encoding})
            elif depth < 2 and (name == "classes.jar" or (name.startswith("libs/") and name.endswith(".jar"))):
                if item.file_size > 128 * 1024 * 1024:
                    raise NoticeError("Oversized nested AAR library")
                with zipfile.ZipFile(io.BytesIO(bundle.read(item))) as nested:
                    visit(nested, prefix + name + "!/", depth + 1)

    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as bundle:
            visit(bundle)
    return documents, native


def generate(root: Path, inventory_paths: list[Path], test_worktree: Path | None = None,
             supplements: list[Path] | None = None) -> tuple[dict[str, bytes], dict]:
    root = root.resolve()
    files, source = source_notices(root, test_worktree)
    inventories = [load_inventory(root, path, source["kmpytPin"]) for path in inventory_paths]
    platforms = [item["platform"] for item in inventories]
    if len(set(platforms)) != len(platforms):
        raise NoticeError("Supply at most one inventory for each platform")
    issues = set()
    if not inventories:
        issues.add("Exact production runtime inventory has not been supplied.")
    if test_worktree:
        issues.add("TEST ONLY: notices taken from a different worktree; never package this output.")
    documents: dict[str, dict] = {}
    components = []
    supplements_by_component: dict[str, list] = {}
    for path in supplements or []:
        manifest = read_json(owned(root, path))
        if manifest.get("schemaVersion") != 1:
            raise NoticeError("Unsupported reviewed-notice supplement schema")
        for identifier, records in manifest.get("components", {}).items():
            supplements_by_component.setdefault(identifier, []).extend(records)

    def add_document(data: bytes, origin: dict) -> str:
        _, encoding = decode_notice(data)
        sha = digest(data)
        if sha not in documents:
            name = "notices/" + sha + ".txt"
            files[name] = data
            documents[sha] = {"path": name, "sha256": sha, "bytes": len(data), "encoding": encoding, "origins": []}
        if origin not in documents[sha]["origins"]:
            documents[sha]["origins"].append(origin)
        if sum(item["bytes"] for item in documents.values()) > MAX_NOTICES:
            raise NoticeError("Generated legal resources exceed total limit")
        return sha

    # These authored/retained asset notices are not inferred from Gradle caches.
    for name in ("composeApp/src/commonMain/composeResources/files/licenses/ibm_plex_sans_arabic_ofl.txt",
                 "native/licenses/Mozilla-Public-License-2.0.txt", "native/licenses/ca-bundle-provenance.md"):
        path = owned(root, root / name)
        if path.is_file():
            add_document(path.read_bytes(), {"assetNotice": name, "role": "license" if not name.endswith("provenance.md") else "notice"})

    for inventory in sorted(inventories, key=lambda item: item["platform"]):
        platform = inventory["platform"]
        poms = {}
        for record in inventory.get("poms", []):
            if record["component"] in poms:
                raise NoticeError("Duplicate POM coordinate")
            poms[record["component"]] = parse_pom(verified_file(root, record).read_bytes())
        for component in sorted(inventory["components"], key=lambda item: item["id"]):
            if component["kind"] == "project":
                continue
            identifier = component["id"]
            artifacts = [item for item in inventory["artifacts"] if item["component"] == identifier]
            terms, problem = pom_licenses(identifier, poms)
            if problem and artifacts:
                issues.add(platform + ": " + problem)
            notice_ids = set()
            full_license = False
            native = False
            for artifact in artifacts:
                extracted, has_native = archive_notices(verified_file(root, artifact))
                native = native or has_native
                for item in extracted:
                    notice_ids.add(add_document(item["bytes"], {"component": identifier, "platform": platform,
                        "artifactSha256": artifact["sha256"], "entry": item["entry"], "role": item["role"]}))
                    full_license = full_license or item["role"] == "license"
            for record in supplements_by_component.get(identifier, []):
                path = verified_file(root, record)
                if record.get("role") not in ("license", "notice") or not record.get("provenance"):
                    raise NoticeError("Supplement requires an explicit role and reviewed provenance")
                notice_ids.add(add_document(path.read_bytes(), {"component": identifier, "platform": platform,
                    "supplement": record["path"], "provenance": record["provenance"], "role": record["role"]}))
                full_license = full_license or record["role"] == "license"
            if artifacts and not full_license:
                issues.add(platform + ": complete license text not yet retained for " + identifier + " (POM URL alone is insufficient)")
            if any("eclipse public" in item["name"].lower() or "epl-" in item["name"].lower() for item in terms):
                issues.add(platform + ": explicit GPL compatibility review required for " + identifier)
            components.append({"id": identifier, "platform": platform, "pomLicenses": terms,
                "artifactSha256": sorted(item["sha256"] for item in artifacts), "noticeSha256": sorted(notice_ids),
                "metadataOnly": not artifacts, "containsNativeCode": native,
                "licenseTextRetained": full_license, "legalCompatibilityVerified": False})

    report = {"schemaVersion": 1, "generator": "scripts/package-notices.py", "source": source,
              "testOnly": test_worktree is not None, "platforms": sorted(platforms),
              "noticeCoverageComplete": not issues, "coverageIssues": sorted(issues),
              "binaryRedistributionReady": False,
              "unverifiedReleaseGates": ["Exact final package closure (including JDK, native libraries and separately provisioned helpers).",
                  "Corresponding-source/relinking/build-material obligations for the actual distributed binaries.",
                  "Reviewed public source pins and per-platform distribution/signing verification."],
              "components": components, "documents": sorted(documents.values(), key=lambda item: item["sha256"])}
    for item in report["documents"]:
        item["origins"].sort(key=lambda value: json.dumps(value, sort_keys=True))
    heading = ["Veyra offline third-party notices", "", "Generated from the actual pinned source and only explicitly supplied production inventories.",
               "POM license pointers are metadata, not substitutes for complete terms.",
               "This file does NOT certify a binary release, native source closure, installation or physical audio.",
               "", "Inventoried platforms: " + (", ".join(sorted(platforms)) or "none yet"), "",
               "Open notice-coverage issues:", *(["- " + issue for issue in sorted(issues)] or ["- None detected by this bounded notice collector; legal review is still required."]), ""]
    for component in components:
        heading.extend([component["platform"] + " / " + component["id"],
                        "Declared POM terms: " + (json.dumps(component["pomLicenses"], ensure_ascii=False) if component["pomLicenses"] else "not available"), ""])
    for document in report["documents"]:
        value, _ = decode_notice(files[document["path"]])
        heading.extend(["=" * 72, "Original-text SHA256: " + document["sha256"],
                        "Origins: " + json.dumps(document["origins"], ensure_ascii=False, sort_keys=True), "", value, ""])
    files["THIRD_PARTY_NOTICES.txt"] = "\n".join(heading).encode("utf-8")
    files["INDEX.txt"] = ("Veyra offline legal documents\n\n"
        "LICENSE — complete Veyra GPL3 text; Veyra's own code is GPL-3.0-or-later.\n"
        "NOTICE.md — unmodified root software/source notice.\n"
        "kmpYT-LICENSE — complete reviewed MIT text from the recorded upstream revision.\n"
        "kmpYT-THIRD_PARTY_NOTICES.md — unmodified pinned upstream audit/fixture notice.\n"
        "THIRD_PARTY_NOTICES.txt — retained asset/dependency texts and explicit open coverage issues.\n"
        "index.json — exact hashes, production coordinates, provenance and nonclaims.\n\n"
        "Binary redistribution readiness is NOT established by this generator.\n").encode("utf-8")
    report["fileHashes"] = {name: digest(data) for name, data in sorted(files.items())}
    report["generatedFiles"] = sorted([*files, "index.json"])
    files["index.json"] = (json.dumps(report, indent=2, ensure_ascii=False, sort_keys=True) + "\n").encode("utf-8")
    return files, report


def sync_output(root: Path, output: Path, files: dict[str, bytes], check: bool) -> None:
    root = root.resolve()
    output = owned(root, output)
    if output != (root / LEGAL).resolve() and not output.is_relative_to((root / ".work").resolve()):
        raise NoticeError("Output must be the owned legal-resource directory or a repository .work test directory")
    actual = {}
    if output.exists():
        for path in output.rglob("*"):
            if path.is_symlink():
                raise NoticeError("Symlink in generated legal-resource directory")
            if path.is_file():
                actual[path.relative_to(output).as_posix()] = path.read_bytes()
    if check:
        if actual != files:
            names = sorted(set(actual) ^ set(files) | {name for name in set(actual) & set(files) if actual[name] != files[name]})
            raise NoticeError("Generated legal resources are stale/missing: " + ", ".join(names[:12]))
        return
    if actual:
        if "index.json" not in actual:
            raise NoticeError("Refusing to replace unmanaged legal files")
        previous = json.loads(actual["index.json"].decode("utf-8"))
        if set(actual) != set(previous.get("generatedFiles", [])):
            raise NoticeError("Unmanaged extra files in legal resources; refusing deletion")
        for name, sha in previous.get("fileHashes", {}).items():
            if digest(actual.get(name, b"")) != sha:
                raise NoticeError("Locally edited generated legal file; refusing to overwrite: " + name)
    temporary = owned(root, root / ".work/tmp")
    temporary.mkdir(parents=True, exist_ok=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="notices-generation-", dir=temporary) as temp:
        stage, backup = Path(temp) / "generated", Path(temp) / "previous"
        stage.mkdir()
        for name, data in files.items():
            target = stage / relative_name(name)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        if output.exists():
            output.replace(backup)
        try:
            stage.replace(output)
        except OSError:
            if backup.exists():
                backup.replace(output)
            raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, action="append", default=[])
    parser.add_argument("--supplement", type=Path, action="append", default=[])
    parser.add_argument("--output", type=Path, default=ROOT / LEGAL)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--require-notices-complete", action="store_true", help="Fail on notice gaps; NEVER certifies binary/source closure")
    parser.add_argument("--test-license-worktree", type=Path, help="Testing only; output must remain in .work and cannot satisfy completeness")
    args = parser.parse_args()
    output = owned(ROOT, args.output)
    if args.test_license_worktree and (not output.is_relative_to((ROOT / ".work").resolve()) or args.require_notices_complete):
        raise NoticeError("Test license-worktree override cannot populate production resources or satisfy a release gate")
    files, report = generate(ROOT, args.inventory, args.test_license_worktree, args.supplement)
    sync_output(ROOT, output, files, args.check)
    print(("Verified" if args.check else "Generated") + " offline notices: " + str(len(report["components"]))
          + " production components, " + str(len(report["documents"])) + " retained texts, "
          + str(len(report["coverageIssues"])) + " open coverage issues. Binary release NOT certified.")
    return 1 if args.require_notices_complete and not report["noticeCoverageComplete"] else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (NoticeError, OSError, KeyError, TypeError, json.JSONDecodeError, ET.ParseError, zipfile.BadZipFile) as error:
        print("Notice verification failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
