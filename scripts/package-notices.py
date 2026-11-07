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
import math
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
from urllib.parse import quote, urlsplit
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LEGAL = Path("composeApp/src/commonMain/composeResources/files/legal")
GPL_SHA256 = "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986"
MIT_SHA256 = "2b48ffd17a0263667e7df9b64b277520776571b1ce73365134b7e92a6d114bcb"
MAX_DOCUMENT = 4 * 1024 * 1024
MAX_NOTICES = 128 * 1024 * 1024
MAX_ARTIFACT = 1024 * 1024 * 1024
MAX_JSON = 32 * 1024 * 1024
MAX_OUTPUT = 2 * MAX_NOTICES + 2 * MAX_JSON
REQUIRED_BUILD_INPUTS = frozenset((
    "settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradle/libs.versions.toml",
    "gradle/kmpyt.commit", "gradle/wrapper/gradle-wrapper.properties",
    "composeApp/build.gradle.kts", "shared/build.gradle.kts", "scripts/runtime-inventory.init.gradle",
    "third_party/kmpYT/settings.gradle.kts", "third_party/kmpYT/build.gradle.kts",
    "third_party/kmpYT/kmpyt/build.gradle.kts", "third_party/kmpYT/gradle.properties",
))
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
HELPER_COMPONENTS = frozenset(("node", "chromium"))
HELPER_PLATFORMS = frozenset(name for name in CONFIGURATIONS if name.startswith("desktop-"))
HELPER_RESOURCES = "composeApp/src/desktopMain/resources/dev/veyra/desktop/helpers"
HELPER_LOADER = "composeApp/src/desktopMain/kotlin/dev/veyra/desktop/runtime/DesktopHelperManifest.kt"
HELPER_SOURCE_INPUTS = frozenset((HELPER_LOADER,
    "composeApp/src/desktopMain/kotlin/dev/veyra/desktop/runtime/DesktopMediaRuntime.kt",
    "composeApp/src/desktopMain/kotlin/dev/veyra/desktop/runtime/SafeHelperArchive.kt"))
MAX_HELPER_MANIFEST = 1024 * 1024
CHROMIUM_SAMPLE_CREDITS_SHA256 = "c2a338c4f1f5f5e19d3020a25f8dc47e394ba4fed1ec2d8dc5b2a915170e02bc"


class NoticeError(ValueError):
    pass


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_digest(path: Path, maximum: int = MAX_ARTIFACT) -> str:
    value = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            size += len(block)
            if size > maximum:
                raise NoticeError("Input exceeds its declared byte bound while hashing")
            value.update(block)
    return value.hexdigest()


def read_bounded(path: Path, maximum: int = MAX_DOCUMENT) -> bytes:
    info = path.stat()
    if not stat.S_ISREG(info.st_mode) or info.st_size > maximum:
        raise NoticeError("Nonregular/oversized notice audit input")
    with path.open("rb") as stream:
        data = stream.read(maximum + 1)
    if len(data) > maximum:
        raise NoticeError("Notice audit input grew past its byte bound")
    return data


def owned(root: Path, path: Path) -> Path:
    path = path.resolve()
    if not path.is_relative_to(root.resolve()):
        raise NoticeError("Out-of-repository notice input/output rejected")
    return path


def relative_name(value: str) -> str:
    if not isinstance(value, str) or not value or any(ord(char) < 32 for char in value):
        raise NoticeError("Unsafe relative notice/artifact path")
    path = PurePosixPath(value)
    if path.is_absolute() or "\\" in value or ":" in value or ".." in path.parts or "." in value.split("/"):
        raise NoticeError("Unsafe relative notice/artifact path")
    if str(path) in ("", "."):
        raise NoticeError("Empty notice/artifact path")
    return str(path)


def verified_file(root: Path, record: dict) -> Path:
    if not isinstance(record, dict):
        raise NoticeError("Inventory file record must be an object")
    path = owned(root, root / relative_name(record.get("path", "")))
    size, sha = record.get("bytes"), record.get("sha256")
    if type(size) is not int or not 0 <= size <= MAX_ARTIFACT or not isinstance(sha, str) or not re.fullmatch(r"[a-f0-9]{64}", sha):
        raise NoticeError("Missing/invalid exact artifact hash or byte size")
    if not path.is_file() or path.stat().st_size != size or file_digest(path, size) != sha or path.stat().st_size != size:
        raise NoticeError("Stale/missing/changed inventory input: " + record["path"])
    return path


def read_json(path: Path) -> dict:
    result = json.loads(read_bounded(path, MAX_JSON).decode("utf-8"))
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
    pin = read_bounded(owned(root, root / "gradle/kmpyt.commit"), 128).decode("utf-8").strip()
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
        files[name] = read_bounded(path)
    if digest(files["LICENSE"]) != GPL_SHA256:
        raise NoticeError("Veyra GPL text differs from the complete reviewed license")
    if digest(files["kmpYT-LICENSE"]) != MIT_SHA256:
        raise NoticeError("kmpYT MIT text differs from the complete reviewed grant")
    for name, upstream_name in (("kmpYT-LICENSE", "LICENSE"), ("kmpYT-THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.md")):
        object_name = revision + ":" + upstream_name
        if int(git(upstream, "cat-file", "-s", object_name)) > MAX_DOCUMENT:
            raise NoticeError("Oversized committed source notice")
        if git(upstream, "show", object_name) != files[name]:
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
    source = inventory.get("source")
    if not isinstance(source, dict) or source.get("kmpytPin") != pin:
        raise NoticeError("Inventory belongs to a different kmpYT pin")
    inputs = source.get("buildInputs", [])
    if not isinstance(inputs, list) or not inputs or len(inputs) > 256 or any(not isinstance(item, dict) for item in inputs):
        raise NoticeError("Inventory build-input fingerprints are missing")
    input_names = [relative_name(item.get("path", "")) for item in inputs]
    if len(input_names) != len(set(input_names)) or not REQUIRED_BUILD_INPUTS.issubset(input_names):
        raise NoticeError("Critical production build/pin fingerprints are missing or duplicated")
    for record in inputs:
        verified_file(root, record)
    components = inventory.get("components", [])
    if not isinstance(components, list) or not components or len(components) > 8192 or any(not isinstance(item, dict) for item in components):
        raise NoticeError("Empty/oversized production component set")
    seen = set()
    for item in components:
        identifier = item.get("id")
        if not isinstance(identifier, str) or not identifier or len(identifier) > 512:
            raise NoticeError("Invalid component identifier")
        if identifier in seen:
            raise NoticeError("Duplicate component identifier")
        seen.add(item.get("id"))
        if item.get("kind") == "project":
            if not re.fullmatch(r"project:[A-Za-z0-9_:.@+-]+", identifier):
                raise NoticeError("Invalid project component identifier")
        elif item.get("kind") == "module":
            parts = [item.get(key, "") for key in ("group", "module", "version")]
            if any(not isinstance(part, str) or not re.fullmatch(r"[A-Za-z0-9_.+@-]+", part) for part in parts) or item["id"] != ":".join(parts):
                raise NoticeError("Invalid resolved module coordinates")
            if is_test_component(parts[0], parts[1]):
                raise NoticeError("Test-only dependency in production: " + item["id"])
        else:
            raise NoticeError("Opaque dependency requires explicit provenance")
    artifacts = inventory.get("artifacts", [])
    if not isinstance(artifacts, list) or not artifacts or len(artifacts) > 16384 or any(not isinstance(item, dict) for item in artifacts):
        raise NoticeError("Empty/oversized production artifact set")
    artifact_paths = set()
    for record in artifacts:
        if record.get("component") not in seen:
            raise NoticeError("Artifact has no resolved component")
        name = relative_name(record.get("path", ""))
        if name in artifact_paths:
            raise NoticeError("Duplicate production artifact path")
        artifact_paths.add(name)
        verified_file(root, record)
    poms = inventory.get("poms", [])
    if not isinstance(poms, list) or len(poms) > 16384 or any(not isinstance(item, dict) for item in poms):
        raise NoticeError("Invalid/oversized POM inventory")
    for record in poms:
        if not isinstance(record.get("component"), str) or not re.fullmatch(r"[A-Za-z0-9_.+@-]+:[A-Za-z0-9_.+@-]+:[A-Za-z0-9_.+@-]+", record["component"]):
            raise NoticeError("Invalid POM coordinates")
        if type(record.get("bytes")) is not int or not 0 < record["bytes"] <= MAX_DOCUMENT:
            raise NoticeError("Empty/oversized POM document")
        verified_file(root, record)
    return inventory


def parse_pom(data: bytes) -> dict:
    # Maven POMs are UTF-8 text here. Reject NUL-containing UTF-16/32 (including
    # encoded DTDs) rather than accidentally bypassing the declaration guard.
    if len(data) > MAX_DOCUMENT or b"\0" in data or re.search(br"<!\s*(DOCTYPE|ENTITY)\b", data, re.IGNORECASE):
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


def archive_notices(path: Path, collect_documents: bool = True) -> tuple[list[dict], bool]:
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
            kind = stat.S_IFMT(item.external_attr >> 16)
            if kind not in (0, stat.S_IFREG, stat.S_IFDIR) or item.flag_bits & 1:
                raise NoticeError("Linked/special/encrypted production archive entry")
            if item.is_dir():
                if kind not in (0, stat.S_IFDIR):
                    raise NoticeError("Archive directory has a conflicting file type")
                continue
            if kind == stat.S_IFDIR:
                raise NoticeError("Archive file has a conflicting directory type")
            class_name = re.sub(r"^META-INF/versions/[0-9]+/", "", name)
            if name.endswith(".class") and class_name.startswith(("junit/", "org/junit/", "org/opentest4j/", "org/hamcrest/", "kotlin/test/", "androidx/test/")):
                raise NoticeError("Embedded JUnit/test classes in a production artifact")
            native = native or re.search(r"(?:\.so(?:\.[0-9]+)*|\.dll|\.dylib|\.node|\.exe)$", name.lower()) is not None
            role = notice_role(name) if collect_documents else None
            if role:
                if item.file_size > MAX_DOCUMENT:
                    raise NoticeError("Oversized archive legal text")
                total += item.file_size
                if total > MAX_NOTICES:
                    raise NoticeError("Archive legal text exceeds total limit")
                with bundle.open(item) as stream:
                    data = stream.read(MAX_DOCUMENT + 1)
                _, encoding = decode_notice(data)
                documents.append({"entry": prefix + name, "role": role, "bytes": data, "encoding": encoding})
            elif name == "classes.jar" or (name.startswith("libs/") and name.endswith(".jar")):
                if depth >= 2:
                    raise NoticeError("Nested production archive exceeds audit depth")
                if item.file_size > 128 * 1024 * 1024:
                    raise NoticeError("Oversized nested AAR library")
                with bundle.open(item) as stream:
                    data = stream.read(128 * 1024 * 1024 + 1)
                if len(data) > 128 * 1024 * 1024:
                    raise NoticeError("Nested AAR library exceeds its read bound")
                with zipfile.ZipFile(io.BytesIO(data)) as nested:
                    visit(nested, prefix + name + "!/", depth + 1)

    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as bundle:
            visit(bundle)
    return documents, native


def helper_json(data: bytes) -> dict:
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise NoticeError("Duplicate helper JSON key")
            result[key] = value
        return result

    def invalid_constant(_):
        raise NoticeError("Nonfinite helper JSON value")

    def finite_float(value):
        number = float(value)
        if not math.isfinite(number):
            raise NoticeError("Nonfinite helper JSON value")
        return number

    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=unique,
                           parse_constant=invalid_constant, parse_float=finite_float)
    except RecursionError as error:
        raise NoticeError("Overdeep helper JSON structure") from error
    if not isinstance(value, dict):
        raise NoticeError("Helper audit JSON must be an object")
    pending = [(value, 0)]
    while pending:
        item, depth = pending.pop()
        if depth > 32:
            raise NoticeError("Overdeep helper JSON structure")
        if isinstance(item, dict):
            pending.extend((child, depth + 1) for child in item.values())
        elif isinstance(item, list):
            pending.extend((child, depth + 1) for child in item)
    return value


def helper_text(value, label: str, maximum: int = 4096) -> str:
    if (not isinstance(value, str) or not value.strip() or len(value) > maximum
            or any(ord(char) < 32 or ord(char) == 127 for char in value)):
        raise NoticeError("Missing/invalid helper " + label)
    return value


def helper_boolean(value, label: str) -> bool:
    if type(value) is not bool:
        raise NoticeError("Helper " + label + " must be an explicit boolean")
    return value


def helper_sha(value, label: str, length: int = 64) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{" + str(length) + "}", value):
        raise NoticeError("Missing/invalid exact helper " + label)
    return value


def helper_relative(value) -> str:
    name = relative_name(helper_text(value, "path"))
    if name != value or value.endswith("/") or "//" in value:
        raise NoticeError("Noncanonical helper input/installed path")
    return name


def helper_installed_path(value) -> str:
    name = helper_relative(value)
    reserved = {"CON", "PRN", "AUX", "NUL"} | {prefix + str(number) for prefix in ("COM", "LPT") for number in range(1, 10)}
    if any(part.endswith((".", " ")) or part.split(".", 1)[0].upper() in reserved for part in name.split("/")):
        raise NoticeError("Nonportable installed helper path")
    return name


def helper_url(value) -> str:
    value = helper_text(value, "public provenance URL", 2048)
    try:
        uri = urlsplit(value)
        port = uri.port
    except ValueError as error:
        raise NoticeError("Invalid helper public provenance URL") from error
    if (uri.scheme != "https" or not uri.hostname or uri.username is not None
            or uri.password is not None or port is not None or uri.fragment
            or uri.query not in ("", "format=TEXT") or any(char.isspace() for char in value)):
        raise NoticeError("Helper provenance requires a credential-free public HTTPS URL")
    return value


def helper_source_document_origin(origin: dict, path: str) -> dict:
    revision = helper_sha(origin.get("revision"), "document source revision", 40)
    repository, url = helper_url(origin.get("repository")), helper_url(origin.get("url"))
    uri = urlsplit(repository)
    relative = quote(path, safe="/")
    if uri.query or repository.endswith("/"):
        raise NoticeError("Noncanonical helper document repository")
    if uri.hostname == "github.com" and re.fullmatch(r"/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", uri.path):
        expected = {repository + "/" + action + "/" + revision + "/" + relative for action in ("blob", "raw")}
        expected.add("https://raw.githubusercontent.com" + uri.path + "/" + revision + "/" + relative)
    elif uri.hostname.endswith(".googlesource.com") and uri.path and "/+/" not in uri.path:
        expected = {repository + "/+/" + revision + "/" + relative + suffix for suffix in ("", "?format=TEXT")}
    else:
        raise NoticeError("Helper original source-document host/URL form requires explicit collector review")
    if url not in expected:
        raise NoticeError("Original helper document URL differs from its repository/revision/path")
    # Pointer equality is not a dependency-graph membership or legal audit.
    return {"kind": "source-file", "path": path, "repository": repository, "revision": revision, "url": url}


def helper_fingerprint(record: dict) -> dict:
    return {key: record[key] for key in ("path", "bytes", "sha256")}


class HelperNoticeReader:
    """Read only small original audit inputs, never helper archives/executables."""
    def __init__(self, root: Path):
        self.root = root
        self.total = 0

    def read(self, path: Path, maximum: int) -> bytes:
        absolute = Path(os.path.abspath(self.root / path))
        owned(self.root, absolute)
        for ancestor in (absolute, *absolute.parents):
            if ancestor == self.root:
                break
            if ancestor.is_symlink():
                raise NoticeError("Symlink in helper notice input")
        data = read_bounded(absolute, min(maximum, MAX_NOTICES - self.total))
        self.total += len(data)
        return data

    def file(self, record: dict, maximum: int = MAX_DOCUMENT) -> bytes:
        if not isinstance(record, dict):
            raise NoticeError("Helper file fingerprint must be an object")
        path = helper_relative(record.get("path"))
        size, sha = record.get("bytes"), helper_sha(record.get("sha256"), "input hash")
        if type(size) is not int or not 0 < size <= maximum:
            raise NoticeError("Empty/oversized helper audit input")
        data = self.read(Path(path), maximum)
        if len(data) != size or digest(data) != sha:
            raise NoticeError("Stale/missing/changed helper audit input: " + path)
        return data


def helper_entries_sha256(entries: list[dict]) -> str:
    """Portable review binding; the entire original manifest is hashed as well."""
    return digest(json.dumps(sorted(entries, key=lambda item: item["path"]),
        ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8"))


def helper_manifest_pins(loader: bytes) -> dict:
    # Deliberately accepts only the existing literal production table, not
    # arbitrary Kotlin expressions or a caller-selected fake manifest location.
    tables = re.findall(r"private\s+val\s+hashes\s*=\s*mapOf\(\s*((?:"
        r'"[a-z0-9-]+"\s+to\s+"[a-f0-9]{64}"\s*,\s*)+)\)', loader.decode("utf-8"))
    if len(tables) != 1:
        raise NoticeError("Literal production helper hash table requires source review")
    pairs = re.findall(r'"([a-z0-9-]+)"\s+to\s+"([a-f0-9]{64})"', tables[0])
    if len(dict(pairs)) != len(pairs):
        raise NoticeError("Duplicate production helper manifest pin")
    return dict(pairs)


def validate_helper_manifest(manifest: dict, component: str, target: str) -> None:
    if set(manifest) != {"platform", "component", "version", "url", "archiveSha256", "archiveBytes",
                         "expandedBytes", "archiveRoot", "format", "executable", "selection", "entries"}:
        raise NoticeError("Helper manifest fields differ from the reviewed runtime schema")
    if manifest.get("component") != component or manifest.get("platform") != target:
        raise NoticeError("Helper manifest component/target mismatch")
    version = helper_text(manifest.get("version"), "version", 64)
    if component == "node":
        if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
            raise NoticeError("Invalid exact Node release")
        archive_target = {"windows-x64": "win-x64", "macos-x64": "darwin-x64",
                          "macos-arm64": "darwin-arm64"}.get(target, target)
        root = "node-v" + version + "-" + archive_target
        windows = target == "windows-x64"
        archive_format = "zip" if windows else "tar.gz"
        executable = "node.exe" if windows else "node"
        selection = {("node.exe" if windows else "bin/node"): executable, "LICENSE": "LICENSE"}
        url = "https://nodejs.org/dist/v" + version + "/" + root + "." + archive_format
    else:
        if not re.fullmatch(r"[0-9]+", version):
            raise NoticeError("Invalid exact Chromium snapshot")
        bucket, root, executable = {
            "linux-x64": ("Linux_x64", "chrome-linux", "chrome"),
            "windows-x64": ("Win_x64", "chrome-win", "chrome.exe"),
            "macos-x64": ("Mac", "chrome-mac", "Chromium.app/Contents/MacOS/Chromium"),
            "macos-arm64": ("Mac_Arm", "chrome-mac", "Chromium.app/Contents/MacOS/Chromium"),
        }[target]
        archive_format, selection = "zip", {}
        url = "https://storage.googleapis.com/chromium-browser-snapshots/" + bucket + "/" + version + "/" + root + ".zip"
    for key, expected in (("url", url), ("archiveRoot", root), ("format", archive_format),
                          ("executable", executable), ("selection", selection)):
        if manifest.get(key) != expected:
            raise NoticeError("Helper manifest " + key + " differs from the reviewed installation policy")
    helper_sha(manifest.get("archiveSha256"), "archive hash")
    for key, maximum in (("archiveBytes", 512 * 1024 * 1024), ("expandedBytes", MAX_ARTIFACT)):
        if type(manifest.get(key)) is not int or not 0 < manifest[key] <= maximum:
            raise NoticeError("Invalid bounded helper archive size")
    entries = manifest.get("entries")
    if not isinstance(entries, list) or not 0 < len(entries) <= 20_000:
        raise NoticeError("Missing/oversized installed helper entry inventory")
    names = set()
    for entry in entries:
        if not isinstance(entry, dict):
            raise NoticeError("Installed helper entry must be an object")
        path = helper_installed_path(entry.get("path"))
        if path.casefold() in names:
            raise NoticeError("Duplicate/case-colliding installed helper entry")
        names.add(path.casefold())
        if type(entry.get("size")) is not int or not 0 <= entry["size"] <= MAX_ARTIFACT:
            raise NoticeError("Invalid installed helper entry size")
        helper_boolean(entry.get("executable"), "entry executable flag")
        if "link" in entry:
            if set(entry) != {"path", "size", "link", "executable"} or entry["size"] != 0 or entry["executable"]:
                raise NoticeError("Invalid installed helper link inventory")
            link = helper_text(entry["link"], "link")
            if PurePosixPath(link).is_absolute() or "\\" in link or ":" in link:
                raise NoticeError("Escaping installed helper link")
            parts = list(PurePosixPath(path).parent.parts)
            for part in link.split("/"):
                if part == "..":
                    if not parts:
                        raise NoticeError("Escaping installed helper link")
                    parts.pop()
                elif part not in ("", "."):
                    parts.append(part)
        else:
            if set(entry) != {"path", "size", "sha256", "executable"}:
                raise NoticeError("Invalid installed helper file inventory")
            helper_sha(entry.get("sha256"), "installed file hash")
    if (sum(item["size"] for item in entries) > manifest["expandedBytes"]
            or not any(item["path"] == executable and "sha256" in item and item["executable"] for item in entries)
            or selection and {item["path"] for item in entries} != set(selection.values())):
        raise NoticeError("Incomplete/inconsistent installed helper selection")


def helper_source(reader: HelperNoticeReader, value: dict, manifest: dict) -> dict:
    if not isinstance(value, dict):
        raise NoticeError("Exact helper source provenance is required")
    revision = helper_sha(value.get("revision"), "source revision", 40)
    component, version = manifest["component"], manifest["version"]
    repository = ("https://github.com/nodejs/node" if component == "node"
                  else "https://chromium.googlesource.com/chromium/src")
    if value.get("repository") != repository or helper_boolean(value.get("mappingReviewed"), "source mapping review") is not True:
        raise NoticeError("Helper source mapping must use the reviewed original repository")
    review = helper_text(value.get("review"), "source mapping review rationale")
    evidence = value.get("evidence")
    if not isinstance(evidence, list) or not 0 < len(evidence) <= 2:
        raise NoticeError("Original helper source revision evidence is required")
    parsed, records = {}, []
    for item in evidence:
        if not isinstance(item, dict) or item.get("role") not in ("chromium-revisions", "node-tag-ref", "node-tag-object"):
            raise NoticeError("Unsupported helper source revision evidence")
        role = item["role"]
        if role in parsed:
            raise NoticeError("Duplicate helper source revision evidence")
        parsed[role] = helper_json(reader.file(item))
        records.append(dict(helper_fingerprint(item), role=role, url=helper_url(item.get("url"))))
    urls = {item["role"]: item["url"] for item in records}
    if component == "chromium":
        data = parsed.get("chromium-revisions", {})
        if (set(parsed) != {"chromium-revisions"} or data.get("chromium_revision") != version
                or data.get("got_revision") != revision
                or urls["chromium-revisions"] != manifest["url"].rsplit("/", 1)[0] + "/REVISIONS"):
            raise NoticeError("Chromium source revision does not match the exact snapshot REVISIONS")
    else:
        ref = parsed.get("node-tag-ref", {})
        obj = ref.get("object", {})
        if (not isinstance(obj, dict) or ref.get("ref") != "refs/tags/v" + version
                or urls.get("node-tag-ref") != "https://api.github.com/repos/nodejs/node/git/ref/tags/v" + version):
            raise NoticeError("Node source revision does not match the original release tag reference")
        if obj.get("type") == "commit":
            if set(parsed) != {"node-tag-ref"} or obj.get("sha") != revision:
                raise NoticeError("Node release tag commit differs from the reviewed source revision")
        elif obj.get("type") == "tag":
            tag_sha = helper_sha(obj.get("sha"), "annotated tag object", 40)
            tag = parsed.get("node-tag-object", {})
            tagged = tag.get("object", {})
            if (set(parsed) != {"node-tag-ref", "node-tag-object"} or tag.get("tag") != "v" + version
                    or tag.get("sha") != tag_sha or not isinstance(tagged, dict)
                    or tagged.get("type") != "commit" or tagged.get("sha") != revision
                    or urls["node-tag-object"] != "https://api.github.com/repos/nodejs/node/git/tags/" + tag_sha):
                raise NoticeError("Node annotated release tag does not peel to the reviewed source revision")
        else:
            raise NoticeError("Node release tag lacks an exact commit/tag object")
    return {"repository": repository, "revision": revision, "mappingReviewed": True,
            "review": review, "evidence": sorted(records, key=lambda item: item["role"])}


def helper_documents(reader: HelperNoticeReader, records, manifest: dict) -> list[tuple[bytes, dict]]:
    if not isinstance(records, list) or len(records) > 8192:
        raise NoticeError("Invalid/oversized helper notice document set")
    result, seen = [], set()
    entries = {item["path"]: item for item in manifest["entries"]}
    for record in records:
        if not isinstance(record, dict):
            raise NoticeError("Helper notice document must be an object")
        data = reader.file(record)
        text, _ = decode_notice(data)
        if record["path"] in seen:
            raise NoticeError("Duplicate helper notice document path")
        seen.add(record["path"])
        if record.get("role") not in ("license", "notice"):
            raise NoticeError("Helper notice document requires an explicit legal-text role")
        reviewed = helper_boolean(record.get("completeTextVerified"), "complete-text review")
        provenance = helper_text(record.get("provenance"), "original-document provenance")
        origin = record.get("origin")
        if not isinstance(origin, dict):
            raise NoticeError("Original helper document origin is required")
        path = helper_relative(origin.get("path"))
        if origin.get("kind") == "installed-file":
            entry = entries.get(path, {})
            if entry.get("sha256") != record["sha256"] or entry.get("size") != record["bytes"]:
                raise NoticeError("Helper document is not the exact selected installed file")
            origin = {"kind": "installed-file", "path": path}
        elif origin.get("kind") == "source-file":
            origin = helper_source_document_origin(origin, path)
        else:
            raise NoticeError("Unsupported original helper document origin")
        if manifest["component"] == "chromium" and (digest(data) == CHROMIUM_SAMPLE_CREDITS_SHA256
                or len(data) < 16_384 and "This is sample credits page" in text and "generate_about_credits=true" in text):
            raise NoticeError("Chromium sample credits are not legal notices; keep them as gap evidence only")
        result.append((data, dict(helper_fingerprint(record), role=record["role"], completeTextVerified=reviewed,
                                 provenance=provenance, origin=origin)))
    return result


def load_helper_inventories(root: Path, paths: list[Path], runtime_platforms: list[str]) -> tuple[list, list, set, set]:
    reader = HelperNoticeReader(root)
    inventories, helpers, inventory_issues, notice_issues = [], [], set(), set()
    seen_platforms = set()
    if len(paths) > len(HELPER_PLATFORMS):
        raise NoticeError("At most one helper inventory per desktop target is allowed")
    for path in paths:
        data = reader.read(path, MAX_JSON)
        value = helper_json(data)
        if type(value.get("schemaVersion")) is not int or value["schemaVersion"] != 1 or value.get("type") != "veyra-provisioned-helper-notices":
            raise NoticeError("Unsupported provisioned-helper notice inventory schema")
        platform = value.get("platform")
        if not isinstance(platform, str) or platform not in HELPER_PLATFORMS or platform in seen_platforms:
            raise NoticeError("Unknown/duplicate helper inventory desktop target")
        seen_platforms.add(platform)
        complete = helper_boolean(value.get("helperSetComplete"), "set completeness")
        inputs = value.get("sourceInputs")
        if not isinstance(inputs, list) or len(inputs) != len(HELPER_SOURCE_INPUTS) or any(not isinstance(item, dict) for item in inputs):
            raise NoticeError("Exact helper installer source fingerprints are required")
        if any(not isinstance(item.get("path"), str) for item in inputs) or {item["path"] for item in inputs} != HELPER_SOURCE_INPUTS:
            raise NoticeError("Missing/duplicated helper installer source fingerprints")
        source_data = {item["path"]: reader.file(item) for item in inputs}
        pins = helper_manifest_pins(source_data[HELPER_LOADER])
        items = value.get("helpers")
        if not isinstance(items, list) or len(items) > len(HELPER_COMPONENTS):
            raise NoticeError("Invalid helper component set")
        seen = set()
        for item in items:
            if (not isinstance(item, dict) or not isinstance(item.get("component"), str)
                    or item["component"] not in HELPER_COMPONENTS or item["component"] in seen):
                raise NoticeError("Unknown/duplicate helper component (not Maven coordinates)")
            component = item["component"]
            seen.add(component)
            target, key = platform.removeprefix("desktop-"), component + "-" + platform.removeprefix("desktop-")
            fingerprint = item.get("manifest")
            if not isinstance(fingerprint, dict) or fingerprint.get("path") != HELPER_RESOURCES + "/" + key + ".json":
                raise NoticeError("Helper inventory must bind the actual packaged manifest path")
            manifest = helper_json(reader.file(fingerprint, MAX_HELPER_MANIFEST))
            if pins.get(key) != fingerprint["sha256"]:
                raise NoticeError("Helper manifest differs from its production loader pin")
            validate_helper_manifest(manifest, component, target)
            if item.get("target") != target or item.get("version") != manifest["version"] or item.get("delivery") != "downloaded-on-demand":
                raise NoticeError("Helper identity/version/target/delivery differs from actual provisioning")
            archive = {"url": manifest["url"], "sha256": manifest["archiveSha256"], "bytes": manifest["archiveBytes"]}
            if not isinstance(item.get("archive"), dict) or item["archive"] != archive or type(item["archive"].get("bytes")) is not int:
                raise NoticeError("Helper archive identity differs from the packaged manifest")
            installed = item.get("installedFiles")
            if (not isinstance(installed, dict) or installed.get("selection") != manifest["selection"]
                    or installed.get("entriesSha256") != helper_entries_sha256(manifest["entries"])):
                raise NoticeError("Helper installed-file selection/inventory binding differs")
            installed_complete = helper_boolean(installed.get("complete"), "installed-file inventory completeness")
            source = helper_source(reader, item.get("source"), manifest)
            documents = helper_documents(reader, item.get("documents"), manifest)
            coverage = item.get("coverage")
            if not isinstance(coverage, dict) or coverage.get("scope") != "all-installed-files-and-transitive-components":
                raise NoticeError("Helper notices require an explicit complete-set coverage scope")
            coverage_complete = helper_boolean(coverage.get("complete"), "notice-set completeness")
            review = helper_text(coverage.get("review"), "notice-set review rationale")
            prefix = platform + ": " + component + ": "
            if not installed_complete:
                inventory_issues.add(prefix + "installed-file inventory completeness has not been declared.")
            if not coverage_complete:
                notice_issues.add(prefix + "complete license/notice coverage of installed files and transitive components has not been declared.")
            documents_reviewed = bool(documents) and all(record["completeTextVerified"] for _, record in documents)
            license_reviewed = any(record["role"] == "license" and record["completeTextVerified"] for _, record in documents)
            if not documents_reviewed or not license_reviewed:
                notice_issues.add(prefix + "original complete license/notice documents have not all been explicitly reviewed.")
            helpers.append({"component": component, "platform": platform, "target": target, "version": manifest["version"],
                "delivery": "downloaded-on-demand", "manifest": helper_fingerprint(fingerprint), "archive": archive,
                "archiveReverifiedByCollector": False,
                "installedFiles": {"selection": manifest["selection"], "entriesSha256": installed["entriesSha256"],
                    "entryCount": len(manifest["entries"]), "complete": installed_complete},
                "source": source, "coverage": {"scope": coverage["scope"], "complete": coverage_complete, "review": review},
                "noticeCoverageComplete": installed_complete and coverage_complete and documents_reviewed and license_reviewed,
                "legalCompatibilityVerified": False, "correspondingSourceVerified": False, "_documents": documents})
        if not complete:
            inventory_issues.add(platform + ": provisioned-helper set completeness has not been declared.")
        for component in HELPER_COMPONENTS - seen:
            inventory_issues.add(platform + ": missing required provisioned-helper inventory for " + component + ".")
        inventories.append({"path": Path(os.path.abspath(root / path)).relative_to(root).as_posix(), "bytes": len(data), "sha256": digest(data),
            "platform": platform, "helperSetComplete": complete,
            "sourceInputs": sorted([helper_fingerprint(item) for item in inputs], key=lambda item: item["path"])})
    for platform in (set(runtime_platforms) & HELPER_PLATFORMS) - seen_platforms:
        inventory_issues.add(platform + ": exact provisioned-helper inventory has not been supplied.")
    return inventories, helpers, inventory_issues, notice_issues


def generate(root: Path, inventory_paths: list[Path], test_worktree: Path | None = None,
             supplements: list[Path] | None = None,
             helper_inventories: list[Path] | None = None) -> tuple[dict[str, bytes], dict]:
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
    project_artifacts = []
    supplements_by_component: dict[str, list] = {}
    for path in supplements or []:
        manifest = read_json(owned(root, path))
        entries = manifest.get("components")
        if manifest.get("schemaVersion") != 1 or not isinstance(entries, dict) or len(entries) > 8192:
            raise NoticeError("Unsupported reviewed-notice supplement schema")
        for identifier, records in entries.items():
            if not re.fullmatch(r"[A-Za-z0-9_.+@-]+:[A-Za-z0-9_.+@-]+:[A-Za-z0-9_.+@-]+", identifier):
                raise NoticeError("Invalid supplement component coordinates")
            if not isinstance(records, list) or len(records) > 128 or any(not isinstance(record, dict) for record in records):
                raise NoticeError("Invalid/oversized reviewed-notice record set")
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
            add_document(read_bounded(path), {"assetNotice": name, "role": "license" if not name.endswith("provenance.md") else "notice"})

    for inventory in sorted(inventories, key=lambda item: item["platform"]):
        platform = inventory["platform"]
        poms = {}
        for record in inventory.get("poms", []):
            if record["component"] in poms:
                raise NoticeError("Duplicate POM coordinate")
            poms[record["component"]] = parse_pom(read_bounded(verified_file(root, record)))
        for component in sorted(inventory["components"], key=lambda item: item["id"]):
            if component["kind"] == "project":
                # Still inspect local JAR/AARs for embedded test classes and
                # unsafe entries. Do not feed our own generated legal resources
                # back into themselves on every package/inventory cycle.
                grant = {"project::": "LICENSE", "project::composeApp": "LICENSE",
                         "project::shared": "LICENSE", "project::kmpYT:kmpyt": "kmpYT-LICENSE"}.get(component["id"])
                if grant is None:
                    issues.add(platform + ": source-project grant mapping needs review for " + component["id"])
                for artifact in inventory["artifacts"]:
                    if artifact["component"] == component["id"]:
                        _, has_native = archive_notices(verified_file(root, artifact), collect_documents=False)
                        project_artifacts.append({"component": component["id"], "platform": platform,
                            "sha256": artifact["sha256"], "sourceGrantResource": grant, "containsNativeCode": has_native})
                continue
            identifier = component["id"]
            artifacts = [item for item in inventory["artifacts"] if item["component"] == identifier]
            terms, problem = pom_licenses(identifier, poms)
            if problem and artifacts:
                issues.add(platform + ": " + problem)
            notice_ids = set()
            full_license = False
            license_candidate = False
            native = False
            for artifact in artifacts:
                extracted, has_native = archive_notices(verified_file(root, artifact))
                native = native or has_native
                for item in extracted:
                    notice_ids.add(add_document(item["bytes"], {"component": identifier, "platform": platform,
                        "artifactSha256": artifact["sha256"], "entry": item["entry"], "role": item["role"]}))
                    license_candidate = license_candidate or item["role"] == "license"
            for record in supplements_by_component.get(identifier, []):
                if type(record.get("bytes")) is not int or not 0 < record["bytes"] <= MAX_DOCUMENT:
                    raise NoticeError("Empty/oversized supplement document")
                path = verified_file(root, record)
                provenance = record.get("provenance")
                if record.get("role") not in ("license", "notice") or not isinstance(provenance, str) or not provenance.strip() or len(provenance) > 2048:
                    raise NoticeError("Supplement requires an explicit role and reviewed provenance")
                if "completeTextVerified" in record and type(record["completeTextVerified"]) is not bool:
                    raise NoticeError("Full-text review assertion must be a boolean")
                reviewed = record.get("completeTextVerified") is True and record["role"] == "license"
                notice_ids.add(add_document(read_bounded(path), {"component": identifier, "platform": platform,
                    "supplement": record["path"], "provenance": provenance, "role": record["role"], "completeTextVerified": reviewed}))
                license_candidate = license_candidate or record["role"] == "license"
                full_license = full_license or reviewed
            if artifacts and not full_license:
                issues.add(platform + ": complete license text not yet reviewed for " + identifier +
                           " (a filename/POM URL is insufficient; retain and explicitly review full terms)")
            if any("eclipse public" in item["name"].lower() or "epl-" in item["name"].lower() for item in terms):
                issues.add(platform + ": explicit GPL compatibility review required for " + identifier)
            components.append({"id": identifier, "platform": platform, "pomLicenses": terms,
                "artifactSha256": sorted(item["sha256"] for item in artifacts), "noticeSha256": sorted(notice_ids),
                "metadataOnly": not artifacts, "containsNativeCode": native,
                "licenseDocumentCandidateRetained": license_candidate,
                "licenseTextRetained": full_license, "legalCompatibilityVerified": False})

    helper_report = {}
    if helper_inventories:
        receipts, helpers, inventory_issues, notice_issues = load_helper_inventories(root, helper_inventories, platforms)
        runtime_issues = {item["platform"] + ": exact production runtime inventory has not been supplied for the enrolled helper target."
                          for item in receipts if item["platform"] not in platforms}
        issues.update(inventory_issues | notice_issues | runtime_issues)
        for helper in helpers:
            notice_ids, retained = set(), []
            for data, record in helper.pop("_documents"):
                notice_ids.add(add_document(data, {"helper": helper["component"], "platform": helper["platform"],
                    "manifestSha256": helper["manifest"]["sha256"], "archiveSha256": helper["archive"]["sha256"],
                    "sourceRevision": helper["source"]["revision"], "document": record}))
                retained.append(record)
            helper["documents"] = sorted(retained, key=lambda item: item["path"])
            helper["noticeSha256"] = sorted(notice_ids)
        helper_report = {"helperInventories": sorted(receipts, key=lambda item: item["platform"]),
            "helpers": sorted(helpers, key=lambda item: (item["platform"], item["component"])),
            "requiredProvisionedHelpers": sorted(HELPER_COMPONENTS),
            "helperInventoryCoverageComplete": not inventory_issues,
            "helperNoticeCoverageComplete": not inventory_issues and not notice_issues,
            "helperInventoryIssues": sorted(inventory_issues), "helperNoticeIssues": sorted(notice_issues),
            "helperRuntimeInventoryIssues": sorted(runtime_issues)}

    report = {"schemaVersion": 1, "generator": "scripts/package-notices.py", "source": source,
              "testOnly": test_worktree is not None, "platforms": sorted(platforms),
              "noticeCoverageComplete": not issues, "coverageIssues": sorted(issues),
              "binaryRedistributionReady": False,
              "unverifiedReleaseGates": ["Exact final package closure (including JDK, native libraries and separately provisioned helpers).",
                  "Corresponding-source/relinking/build-material obligations for the actual distributed binaries.",
                  "Reviewed public source pins and per-platform distribution/signing verification."],
              "components": components, "sourceProjectArtifacts": project_artifacts,
              "documents": sorted(documents.values(), key=lambda item: item["sha256"])}
    report.update(helper_report)
    if helper_report:
        report["unverifiedReleaseGates"].append(
            "Provisioned-helper notice declarations are review assertions, not legal/source closure; "
            "bundled FFmpeg and JDK inventories, terms and source/build obligations require separate verification.")
    for item in report["documents"]:
        item["origins"].sort(key=lambda value: json.dumps(value, sort_keys=True))
    heading = ["Veyra offline third-party notices", "", "Generated from the actual pinned source and only explicitly supplied production inventories.",
               "License filenames and POM pointers do not prove complete terms; explicit full-text review is required.",
               "This file does NOT certify a binary release, native source closure, installation or physical audio.",
               "", "Inventoried platforms: " + (", ".join(sorted(platforms)) or "none yet"), "",
               "Open notice-coverage issues:", *(["- " + issue for issue in sorted(issues)] or ["- None detected by this bounded notice collector; legal review is still required."]), ""]
    for helper in helper_report.get("helpers", []):
        heading.extend([helper["platform"] + " / provisioned helper " + helper["component"] + " " + helper["version"],
            "Delivery: downloaded on demand; the collector does not download or reverify the executable archive.",
            "Source revision: " + helper["source"]["revision"],
            "Notice-set completeness declaration: " + str(helper["coverage"]["complete"]).lower(),
            "This declaration is not legal compatibility or corresponding-source certification.", ""])
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
    if helper_report and (len(files["index.json"]) > MAX_JSON or sum(map(len, files.values())) > MAX_OUTPUT):
        raise NoticeError("Helper-enrolled generated legal resources exceed output bounds")
    return files, report


def output_path(root: Path, output: Path) -> Path:
    root = root.resolve()
    absolute = Path(os.path.abspath(output))
    if not absolute.is_relative_to(root):
        raise NoticeError("Out-of-repository notice output rejected")
    for ancestor in (absolute, *absolute.parents):
        if ancestor == root:
            break
        if ancestor.is_symlink():
            raise NoticeError("Symlink in legal output path")
    output = owned(root, output)
    if output != (root / LEGAL).resolve() and not output.is_relative_to((root / ".work").resolve()):
        raise NoticeError("Output must be the owned legal-resource directory or a repository .work test directory")
    if (root / ".work/tmp").is_relative_to(output):
        raise NoticeError("Output may not replace its staging directory or an ancestor")
    return output


def sync_output(root: Path, output: Path, files: dict[str, bytes], check: bool) -> None:
    root = root.resolve()
    output = output_path(root, output)
    actual = {}
    actual_directories = set()
    total = 0
    if output.exists() and not output.is_dir():
        raise NoticeError("Legal output is not a directory")
    if output.exists():
        for path in output.rglob("*"):
            if path.is_symlink():
                raise NoticeError("Symlink in generated legal-resource directory")
            name = path.relative_to(output).as_posix()
            if path.is_dir():
                actual_directories.add(name)
            else:
                maximum = MAX_JSON if name == "index.json" else MAX_OUTPUT - total
                actual[name] = read_bounded(path, maximum)
                total += len(actual[name])
                if total > MAX_OUTPUT:
                    raise NoticeError("Existing legal resource directory exceeds its byte bound")
    def parent_directories(names):
        return {str(parent) for name in names for parent in PurePosixPath(name).parents if str(parent) != "."}
    if check:
        if actual != files or actual_directories != parent_directories(files):
            names = sorted(set(actual) ^ set(files) | {name for name in set(actual) & set(files) if actual[name] != files[name]})
            raise NoticeError("Generated legal resources are stale/missing: " + ", ".join(names[:12]))
        return
    if actual:
        if "index.json" not in actual:
            raise NoticeError("Refusing to replace unmanaged legal files")
        previous = json.loads(actual["index.json"].decode("utf-8"))
        if not isinstance(previous, dict) or previous.get("schemaVersion") != 1 or previous.get("generator") != "scripts/package-notices.py":
            raise NoticeError("Unrecognized generated legal index; refusing replacement")
        generated = previous.get("generatedFiles")
        hashes = previous.get("fileHashes")
        if (not isinstance(generated, list) or any(not isinstance(name, str) for name in generated)
                or len(generated) != len(set(generated)) or "index.json" not in generated
                or any(relative_name(name) != name for name in generated)
                or not isinstance(hashes, dict) or set(hashes) != set(generated) - {"index.json"}
                or any(not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{64}", sha) for sha in hashes.values())):
            raise NoticeError("Incomplete/invalid generated legal index; refusing replacement")
        if set(actual) != set(generated) or actual_directories != parent_directories(generated):
            raise NoticeError("Unmanaged extra files in legal resources; refusing deletion")
        for name, sha in hashes.items():
            if digest(actual.get(name, b"")) != sha:
                raise NoticeError("Locally edited generated legal file; refusing to overwrite: " + name)
    elif actual_directories:
        raise NoticeError("Refusing to replace unmanaged empty legal directories")
    if actual == files:
        return
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
    parser.add_argument("--helper-inventory", type=Path, action="append", default=[],
                        help="Explicit on-demand Node/Chromium notice inventory; never Maven coordinates")
    parser.add_argument("--output", type=Path, default=ROOT / LEGAL)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--require-notices-complete", action="store_true", help="Fail on notice gaps; NEVER certifies binary/source closure")
    parser.add_argument("--test-license-worktree", type=Path, help="Testing only; output must remain in .work and cannot satisfy completeness")
    args = parser.parse_args()
    output = output_path(ROOT, args.output)
    if args.test_license_worktree and (not output.is_relative_to((ROOT / ".work").resolve()) or args.require_notices_complete):
        raise NoticeError("Test license-worktree override cannot populate production resources or satisfy a release gate")
    files, report = generate(ROOT, args.inventory, args.test_license_worktree, args.supplement, args.helper_inventory)
    sync_output(ROOT, output, files, args.check)
    print(("Verified" if args.check else "Generated") + " offline notices: " + str(len(report["components"]))
          + " production components, " + str(len(report["documents"])) + " retained texts, "
          + (str(len(report["helpers"])) + " provisioned helpers, " if args.helper_inventory else "")
          + str(len(report["coverageIssues"])) + " open coverage issues. Binary release NOT certified.")
    return 1 if args.require_notices_complete and not report["noticeCoverageComplete"] else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (NoticeError, OSError, KeyError, TypeError, UnicodeError, json.JSONDecodeError, ET.ParseError, zipfile.BadZipFile) as error:
        print("Notice verification failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
