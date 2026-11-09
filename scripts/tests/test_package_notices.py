#!/usr/bin/env python3
"""Offline notice regressions using only authored repository-owned fixtures."""
from __future__ import annotations

import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("package_notices", ROOT / "scripts/package-notices.py")
NOTICES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(NOTICES)
ID = "org.example:production:1.0"


def write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def archive(entries: list[tuple[str | zipfile.ZipInfo, bytes]]) -> bytes:
    output = io.BytesIO()
    with warnings.catch_warnings(), zipfile.ZipFile(output, "w") as bundle:
        warnings.simplefilter("ignore", UserWarning)
        for name, data in entries:
            bundle.writestr(name, data)
    return output.getvalue()


class PackageNoticeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.seed_directory = tempfile.TemporaryDirectory(prefix="notice-seed-", dir=ROOT / ".work/tmp")
        cls.seed = Path(cls.seed_directory.name)
        cls.mit = (ROOT / "third_party/kmpYT/LICENSE").read_bytes()
        write(cls.seed / "LICENSE", (ROOT / "LICENSE").read_bytes())
        write(cls.seed / "NOTICE.md", b"Authored source-notice fixture. No application verification claim.\n")
        for name in NOTICES.REQUIRED_BUILD_INPUTS:
            write(cls.seed / name, ("// Authored fingerprint fixture: " + name + "\n").encode())
        for name in ("composeApp/src/commonMain/composeResources/files/licenses/ibm_plex_sans_arabic_ofl.txt",
                     "native/licenses/Mozilla-Public-License-2.0.txt", "native/licenses/ca-bundle-provenance.md"):
            write(cls.seed / name, (ROOT / name).read_bytes())
        upstream = cls.seed / "third_party/kmpYT"
        write(upstream / "LICENSE", cls.mit)
        write(upstream / "THIRD_PARTY_NOTICES.md", b"Authored committed upstream notice fixture.\n")
        home = cls.seed / ".work/home"; home.mkdir(parents=True)
        environment = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        environment.update(HOME=str(home), USERPROFILE=str(home), GIT_CONFIG_NOSYSTEM="1",
                           GIT_CONFIG_GLOBAL=str(home / "gitconfig"), GIT_TERMINAL_PROMPT="0")
        command = ["git", "-C", str(upstream), "-c", "user.name=Authored Fixture",
                   "-c", "user.email=fixture@example.invalid", "-c", "commit.gpgsign=false",
                   "-c", "core.hooksPath=" + str(home / "no-hooks")]
        for arguments in (("init", "--quiet", "--object-format=sha1"), ("add", "."), ("commit", "--quiet", "-m", "Authored notice fixture")):
            subprocess.run(command + list(arguments), check=True, env=environment, capture_output=True)
        cls.pin = subprocess.check_output(command + ["rev-parse", "HEAD"], env=environment, text=True).strip()
        write(cls.seed / "gradle/kmpyt.commit", (cls.pin + "\n").encode())

    @classmethod
    def tearDownClass(cls):
        cls.seed_directory.cleanup()

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="notice-test-", dir=ROOT / ".work/tmp")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name) / "repo"
        shutil.copytree(self.seed, self.root)

    def record(self, path: Path, **fields) -> dict:
        data = path.read_bytes()
        return dict(path=path.relative_to(self.root).as_posix(), bytes=len(data), sha256=NOTICES.digest(data), **fields)

    def inventory(self, entries=None, component=None, pom=None) -> tuple[Path, dict]:
        component = component or {"id": ID, "kind": "module", "group": "org.example", "module": "production", "version": "1.0"}
        artifact = self.root / ".work/fixtures/runtime.jar"
        write(artifact, archive(entries if entries is not None else [("META-INF/LICENSE", self.mit)]))
        pom_path = self.root / ".work/fixtures/runtime.pom"
        write(pom_path, pom or b'<project xmlns="http://maven.apache.org/POM/4.0.0"><licenses><license><name>MIT</name><url>https://example.invalid/terms-pointer</url></license></licenses></project>')
        value = {"schemaVersion": 1, "type": "veyra-production-dependencies", "platform": "desktop-linux-x64",
                 "project": ":composeApp", "configuration": "desktopRuntimeClasspath", "resolved": True,
                 "testConfigurationIncluded": False, "source": {"kmpytPin": self.pin,
                     "buildInputs": [self.record(self.root / name) for name in sorted(NOTICES.REQUIRED_BUILD_INPUTS)]},
                 "components": [component], "artifacts": [self.record(artifact, component=component["id"])],
                 "poms": [self.record(pom_path, component=ID)]}
        path = self.root / ".work/fixtures/inventory.json"
        self.save(path, value)
        return path, value

    def save(self, path, value):
        write(path, (json.dumps(value, sort_keys=True) + "\n").encode())

    def supplement(self, reviewed=True):
        path = self.root / ".work/fixtures/reviewed-mit.txt"
        write(path, self.mit)
        record = self.record(path, role="license", completeTextVerified=reviewed,
                             provenance="Authored review fixture: exact full MIT text; not a real dependency audit.")
        manifest = self.root / ".work/fixtures/supplement.json"
        self.save(manifest, {"schemaVersion": 1, "components": {ID: [record]}})
        return manifest

    def generated(self):
        return NOTICES.generate(self.root, [])

    def helper_fixture_files(self):
        if (self.root / NOTICES.HELPER_LOADER).exists():
            return
        # These are inert, authored metadata/text fixtures, not downloaded
        # executables, original upstream receipts or an actual helper audit.
        for platform in sorted(NOTICES.HELPER_PLATFORMS):
            target = platform.removeprefix("desktop-")
            for component in ("node", "chromium"):
                version = "1.2.3" if component == "node" else "1234"
                if component == "node":
                    archive_target = {"windows-x64": "win-x64", "macos-x64": "darwin-x64",
                                      "macos-arm64": "darwin-arm64"}.get(target, target)
                    archive_root = "node-v" + version + "-" + archive_target
                    windows = target == "windows-x64"
                    executable, kind = ("node.exe", "zip") if windows else ("node", "tar.gz")
                    selection = {("node.exe" if windows else "bin/node"): executable, "LICENSE": "LICENSE"}
                    url = "https://nodejs.org/dist/v" + version + "/" + archive_root + "." + kind
                else:
                    bucket, archive_root, executable = {
                        "linux-x64": ("Linux_x64", "chrome-linux", "chrome"),
                        "windows-x64": ("Win_x64", "chrome-win", "chrome.exe"),
                        "macos-x64": ("Mac", "chrome-mac", "Chromium.app/Contents/MacOS/Chromium"),
                        "macos-arm64": ("Mac_Arm", "chrome-mac", "Chromium.app/Contents/MacOS/Chromium"),
                    }[target]
                    kind, selection = "zip", {}
                    url = "https://storage.googleapis.com/chromium-browser-snapshots/" + bucket + "/" + version + "/" + archive_root + ".zip"
                line_end = "\r\n" if target == "windows-x64" else "\n"
                document = ("Authored " + component + " full-text fixture; not an actual upstream notice." + line_end).encode()
                executable_bytes = b"Authored inert executable hash fixture; never written or run."
                entries = [{"path": executable, "size": len(executable_bytes), "sha256": NOTICES.digest(executable_bytes), "executable": True}]
                if component == "node":
                    entries.append({"path": "LICENSE", "size": len(document), "sha256": NOTICES.digest(document), "executable": False})
                manifest = {"platform": target, "component": component, "version": version, "url": url,
                    "archiveSha256": NOTICES.digest(("Authored archive identity: " + component + target).encode()),
                    "archiveBytes": 123, "expandedBytes": 4096, "archiveRoot": archive_root,
                    "format": kind, "executable": executable, "selection": selection, "entries": entries}
                self.save(self.root / NOTICES.HELPER_RESOURCES / (component + "-" + target + ".json"), manifest)
                write(self.root / ".work/fixtures/helpers" / (component + "-" + target + "-LICENSE"), document)
        for name in NOTICES.HELPER_SOURCE_INPUTS - {NOTICES.HELPER_LOADER}:
            write(self.root / name, ("// Authored installer source fingerprint: " + name + "\n").encode())
        self.refresh_helper_inputs()

    def refresh_helper_inputs(self):
        pairs = []
        for path in sorted((self.root / NOTICES.HELPER_RESOURCES).glob("*.json")):
            pairs.append('    "' + path.stem + '" to "' + NOTICES.digest(path.read_bytes()) + '",')
        write(self.root / NOTICES.HELPER_LOADER,
              ("// Authored non-executable source fixture.\nprivate val hashes = mapOf(\n" + "\n".join(pairs) + "\n)\n").encode())
        return [self.record(self.root / name) for name in sorted(NOTICES.HELPER_SOURCE_INPUTS)]

    def helper_inventory(self, platform="desktop-linux-x64"):
        self.helper_fixture_files()
        target = platform.removeprefix("desktop-")
        helpers = []
        for component in ("node", "chromium"):
            path = self.root / NOTICES.HELPER_RESOURCES / (component + "-" + target + ".json")
            manifest = json.loads(path.read_text())
            revision = ("1" if component == "node" else "2") * 40
            if component == "node":
                repository = "https://github.com/nodejs/node"
                evidence_data = {"ref": "refs/tags/v" + manifest["version"], "object": {"type": "commit", "sha": revision}}
                evidence_role = "node-tag-ref"
                evidence_url = "https://api.github.com/repos/nodejs/node/git/ref/tags/v" + manifest["version"]
                origin = {"kind": "installed-file", "path": "LICENSE"}
            else:
                repository = "https://chromium.googlesource.com/chromium/src"
                evidence_data = {"chromium_revision": manifest["version"], "got_revision": revision}
                evidence_role = "chromium-revisions"
                evidence_url = manifest["url"].rsplit("/", 1)[0] + "/REVISIONS"
                origin = {"kind": "source-file", "path": "LICENSE", "repository": repository, "revision": revision,
                          "url": repository + "/+/" + revision + "/LICENSE"}
            evidence_path = self.root / ".work/fixtures/helpers" / (component + "-" + target + "-revision.json")
            self.save(evidence_path, evidence_data)
            document = self.root / ".work/fixtures/helpers" / (component + "-" + target + "-LICENSE")
            helpers.append({"component": component, "target": target, "version": manifest["version"],
                "delivery": "downloaded-on-demand", "manifest": self.record(path),
                "archive": {"url": manifest["url"], "bytes": manifest["archiveBytes"], "sha256": manifest["archiveSha256"]},
                "installedFiles": {"selection": manifest["selection"], "entriesSha256": NOTICES.helper_entries_sha256(manifest["entries"]), "complete": True},
                "source": {"repository": repository, "revision": revision, "mappingReviewed": True,
                    "review": "Authored source-mapping fixture; no actual upstream verification.",
                    "evidence": [self.record(evidence_path, role=evidence_role, url=evidence_url)]},
                "documents": [self.record(document, role="license", completeTextVerified=True, origin=origin,
                    provenance="Authored complete-text fixture; no real dependency audit.")],
                "coverage": {"scope": "all-installed-files-and-transitive-components", "complete": True,
                    "review": "Authored full-set review fixture, not real Chromium or Node closure."}})
        value = {"schemaVersion": 1, "type": "veyra-provisioned-helper-notices", "platform": platform,
                 "helperSetComplete": True, "sourceInputs": self.refresh_helper_inputs(), "helpers": helpers}
        path = self.root / ".work/fixtures/helpers" / (platform + ".json")
        self.save(path, value)
        return path, value

    def refresh_helper_bindings(self, path, value):
        value["sourceInputs"] = self.refresh_helper_inputs()
        for item in value["helpers"]:
            file = self.root / item["manifest"]["path"]
            manifest = json.loads(file.read_text())
            item["manifest"] = self.record(file)
            item["archive"] = {"url": manifest["url"], "bytes": manifest["archiveBytes"], "sha256": manifest["archiveSha256"]}
            item["installedFiles"].update(selection=manifest["selection"], entriesSha256=NOTICES.helper_entries_sha256(manifest["entries"]))
        self.save(path, value)

    def test_actual_source_grants_are_byte_exact_and_source_only_coverage_stays_open(self):
        files, report = self.generated()
        for output, source in (("LICENSE", "LICENSE"), ("NOTICE.md", "NOTICE.md"),
                               ("kmpYT-LICENSE", "third_party/kmpYT/LICENSE"),
                               ("kmpYT-THIRD_PARTY_NOTICES.md", "third_party/kmpYT/THIRD_PARTY_NOTICES.md")):
            self.assertEqual(files[output], (self.root / source).read_bytes())
        self.assertFalse(report["noticeCoverageComplete"])
        self.assertFalse(report["binaryRedistributionReady"])
        self.assertEqual(len(report["coverageIssues"]), 1)
        self.assertEqual(len(report["documents"]), 3)

    def test_truncated_source_gpl_and_mit_are_rejected(self):
        for name in ("LICENSE", "third_party/kmpYT/LICENSE"):
            with self.subTest(name=name):
                path = self.root / name; original = path.read_bytes()
                path.write_bytes(original[:120])
                with self.assertRaises(NOTICES.NoticeError): self.generated()
                path.write_bytes(original)

    def test_actual_checkout_must_match_the_pin(self):
        write(self.root / "gradle/kmpyt.commit", b"a" * 40 + b"\n")
        with self.assertRaisesRegex(NOTICES.NoticeError, "checkout differs"): self.generated()

    def test_modified_uncommitted_upstream_notice_is_rejected(self):
        write(self.root / "third_party/kmpYT/THIRD_PARTY_NOTICES.md", b"Locally modified fixture.\n")
        with self.assertRaisesRegex(NOTICES.NoticeError, "exact committed"): self.generated()

    def test_full_pin_required(self):
        for value in (b"main", b"1234abcd", b"A" * 40, b"a" * 140):
            write(self.root / "gradle/kmpyt.commit", value)
            with self.subTest(value=value), self.assertRaises(NOTICES.NoticeError): self.generated()

    def test_relative_paths_reject_wrong_types_traversal_drives_and_controls(self):
        for value in (None, 7, [], "", ".", "/absolute", "../escape", "a/../b", "C:/drive", "a\\b", "a/./b", "a\0b", "a\nb"):
            with self.subTest(value=value), self.assertRaises(NOTICES.NoticeError): NOTICES.relative_name(value)

    def test_scoped_inventory_requires_all_critical_build_and_pin_fingerprints(self):
        path, value = self.inventory()
        for names in ([value["source"]["buildInputs"][0]], value["source"]["buildInputs"] + value["source"]["buildInputs"][:1]):
            modified = copy.deepcopy(value); modified["source"]["buildInputs"] = names; self.save(path, modified)
            with self.assertRaisesRegex(NOTICES.NoticeError, "Critical"): NOTICES.generate(self.root, [path])

    def test_changed_build_input_is_not_accepted_by_a_stale_inventory(self):
        path, _ = self.inventory()
        write(self.root / "composeApp/build.gradle.kts", b"Changed authored build input.\n")
        with self.assertRaisesRegex(NOTICES.NoticeError, "Stale/missing/changed"): NOTICES.generate(self.root, [path])

    def test_test_configuration_or_unresolved_inventory_cannot_claim_production(self):
        path, value = self.inventory()
        for key, invalid in (("configuration", "desktopTestRuntimeClasspath"), ("resolved", False),
                             ("testConfigurationIncluded", True), ("project", ":shared"), ("platform", "guessed-os")):
            with self.subTest(key=key):
                modified = copy.deepcopy(value); modified[key] = invalid; self.save(path, modified)
                with self.assertRaises(NOTICES.NoticeError): NOTICES.generate(self.root, [path])

    def test_junit_and_other_test_components_are_rejected(self):
        pairs = [("junit", "junit"), ("org.junit.jupiter", "junit-jupiter-api"), ("org.opentest4j", "opentest4j"),
                 ("org.hamcrest", "hamcrest"), ("androidx.test", "runner"), ("org.jetbrains.kotlin", "kotlin-test"),
                 ("org.jetbrains.kotlinx", "kotlinx-coroutines-test"), ("io.ktor", "ktor-client-mock"),
                 ("androidx.compose.ui", "ui-test-junit4"), ("org.jetbrains.compose.ui", "ui-test")]
        for group, module in pairs:
            item = {"id": group + ":" + module + ":1", "group": group, "module": module, "version": "1", "kind": "module"}
            path, _ = self.inventory(component=item)
            with self.subTest(id=item["id"]), self.assertRaisesRegex(NOTICES.NoticeError, "Test-only"):
                NOTICES.generate(self.root, [path])

    def test_changed_artifact_bytes_and_invalid_hash_are_rejected(self):
        path, value = self.inventory()
        write(self.root / value["artifacts"][0]["path"], b"Changed fixture")
        with self.assertRaises(NOTICES.NoticeError): NOTICES.generate(self.root, [path])
        value["artifacts"][0]["sha256"] = "not-a-hash"; self.save(path, value)
        with self.assertRaises(NOTICES.NoticeError): NOTICES.generate(self.root, [path])

    def test_duplicate_component_and_artifact_identifiers_are_rejected(self):
        path, value = self.inventory()
        for field in ("components", "artifacts"):
            modified = copy.deepcopy(value); modified[field].append(modified[field][0]); self.save(path, modified)
            with self.subTest(field=field), self.assertRaises(NOTICES.NoticeError): NOTICES.generate(self.root, [path])

    def test_inventory_types_empty_components_and_opaque_dependencies_fail_closed(self):
        path, value = self.inventory()
        for field, invalid in (("components", []), ("components", [None]), ("artifacts", {}), ("poms", [None]), ("source", [])):
            modified = copy.deepcopy(value); modified[field] = invalid; self.save(path, modified)
            with self.subTest(field=field), self.assertRaises(NOTICES.NoticeError): NOTICES.generate(self.root, [path])
        value["components"][0]["kind"] = "opaque"; self.save(path, value)
        with self.assertRaises(NOTICES.NoticeError): NOTICES.generate(self.root, [path])

    def test_filename_and_pom_url_do_not_prove_complete_license_text(self):
        path, _ = self.inventory(entries=[("META-INF/LICENSE", b"MIT license; see https://example.invalid/license\n")])
        files, report = NOTICES.generate(self.root, [path])
        self.assertTrue(report["components"][0]["licenseDocumentCandidateRetained"])
        self.assertFalse(report["components"][0]["licenseTextRetained"])
        self.assertFalse(report["noticeCoverageComplete"])
        self.assertIn(b"complete license text not yet reviewed", files["THIRD_PARTY_NOTICES.txt"])

    def test_absent_license_with_pom_url_leaves_explicit_gap(self):
        path, _ = self.inventory(entries=[("example/Production.class", b"Authored inert fixture")])
        _, report = NOTICES.generate(self.root, [path])
        self.assertFalse(report["components"][0]["licenseDocumentCandidateRetained"])
        self.assertFalse(report["noticeCoverageComplete"])

    def test_explicit_review_retains_full_original_text_without_claiming_binary_readiness(self):
        path, _ = self.inventory()
        supplement = self.supplement()
        files, report = NOTICES.generate(self.root, [path], supplements=[supplement])
        self.assertTrue(report["noticeCoverageComplete"])
        self.assertTrue(report["components"][0]["licenseTextRetained"])
        self.assertFalse(report["components"][0]["legalCompatibilityVerified"])
        self.assertFalse(report["binaryRedistributionReady"])
        sha = NOTICES.digest(self.mit)
        self.assertEqual(files["notices/" + sha + ".txt"], self.mit)
        self.assertEqual(len([item for item in report["documents"] if item["sha256"] == sha]), 1)
        self.assertEqual(len(next(item for item in report["documents"] if item["sha256"] == sha)["origins"]), 2)

    def test_unreviewed_supplement_does_not_close_coverage(self):
        path, _ = self.inventory()
        _, report = NOTICES.generate(self.root, [path], supplements=[self.supplement(reviewed=False)])
        self.assertFalse(report["noticeCoverageComplete"])

    def test_supplement_requires_exact_hash_role_and_explicit_provenance(self):
        path, _ = self.inventory(); supplement = self.supplement()
        value = json.loads(supplement.read_text())
        for key, invalid in (("sha256", "a" * 64), ("role", "guessed"), ("provenance", ""), ("completeTextVerified", "true")):
            changed = copy.deepcopy(value); changed["components"][ID][0][key] = invalid; self.save(supplement, changed)
            with self.subTest(key=key), self.assertRaises(NOTICES.NoticeError):
                NOTICES.generate(self.root, [path], supplements=[supplement])

    def test_pom_inheritance_is_recorded_but_not_confused_with_full_text(self):
        parent = NOTICES.parse_pom(b'<project><licenses><license><name>Apache-2.0</name><url>https://example.invalid/license</url></license></licenses></project>')
        child = NOTICES.parse_pom(b'<project><parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>1</version></parent></project>')
        terms, issue = NOTICES.pom_licenses(ID, {ID: child, "org.example:parent:1": parent})
        self.assertIsNone(issue); self.assertEqual(terms[0]["sourcePom"], "org.example:parent:1")

    def test_cyclic_overdeep_and_unresolved_pom_inheritance_cannot_pass(self):
        with self.assertRaisesRegex(NOTICES.NoticeError, "Cyclic"):
            NOTICES.pom_licenses("a", {"a": {"licenses": [], "parent": "b"}, "b": {"licenses": [], "parent": "a"}})
        chain = {str(i): {"licenses": [], "parent": str(i + 1)} for i in range(40)}
        with self.assertRaisesRegex(NOTICES.NoticeError, "overdeep"): NOTICES.pom_licenses("0", chain)
        _, issue = NOTICES.pom_licenses("a", {"a": {"licenses": [], "parent": "g:p:${revision}"}})
        self.assertIn("unresolved", issue)

    def test_dtd_external_entities_and_utf16_encoded_declarations_are_rejected(self):
        text = '<!DOCTYPE project [<!ENTITY secret SYSTEM "file:///private-fixture">]><project>&secret;</project>'
        for data in (text.encode(), text.encode("utf-16"), b'<!ENTITY test "value"><project/>'):
            with self.subTest(data=data[:20]), self.assertRaises(NOTICES.NoticeError): NOTICES.parse_pom(data)

    def test_archive_paths_links_special_files_and_duplicates_are_rejected(self):
        link = zipfile.ZipInfo("lib/link"); link.create_system = 3; link.external_attr = (stat.S_IFLNK | 0o777) << 16
        fifo = zipfile.ZipInfo("lib/fifo"); fifo.create_system = 3; fifo.external_attr = (stat.S_IFIFO | 0o600) << 16
        entries = [[(name, b"fixture")] for name in ("../../escape", "/absolute", "C:/drive", "a\\b", "./LICENSE")]
        entries += [[(link, b"../outside")], [(fifo, b"")], [("LICENSE", b"one"), ("LICENSE", b"two")]]
        target = self.root / ".work/fixtures/archive.jar"
        for index, items in enumerate(entries):
            write(target, archive(items))
            with self.subTest(index=index), self.assertRaises(NOTICES.NoticeError): NOTICES.archive_notices(target)

    def test_empty_maven_directory_mode_sentinel_does_not_hide_special_files(self):
        target = self.root / ".work/fixtures/archive.jar"
        marker = zipfile.ZipInfo("META-INF/maven/")
        marker.create_system = 3
        marker.external_attr = 0xFFFF0010
        write(target, archive([(marker, b""), ("META-INF/LICENSE", self.mit)]))
        NOTICES.archive_notices(target)
        for name, payload in (("META-INF/file", b""), ("META-INF/maven/", b"hidden")):
            invalid = zipfile.ZipInfo(name)
            invalid.create_system = 3
            invalid.external_attr = 0xFFFF0010
            write(target, archive([(invalid, payload)]))
            with self.subTest(name=name), self.assertRaises(NOTICES.NoticeError):
                NOTICES.archive_notices(target)

    def test_embedded_test_classes_in_regular_and_multi_release_jars_are_rejected(self):
        target = self.root / ".work/fixtures/archive.jar"
        for name in ("junit/Test.class", "org/junit/Test.class", "org/opentest4j/Test.class", "org/hamcrest/Test.class",
                     "kotlin/test/Test.class", "androidx/test/Test.class", "META-INF/versions/17/org/junit/Test.class"):
            write(target, archive([(name, b"Authored inert class fixture")]))
            with self.subTest(name=name), self.assertRaisesRegex(NOTICES.NoticeError, "JUnit/test"):
                NOTICES.archive_notices(target)

    def test_nested_aar_licenses_are_byte_exact_and_nested_test_classes_rejected(self):
        target = self.root / ".work/fixtures/archive.aar"
        write(target, archive([("classes.jar", archive([("META-INF/LICENSE", self.mit)]))]))
        documents, _ = NOTICES.archive_notices(target)
        self.assertEqual(documents[0]["bytes"], self.mit)
        self.assertEqual(documents[0]["entry"], "classes.jar!/META-INF/LICENSE")
        write(target, archive([("libs/extra.jar", archive([("org/junit/Test.class", b"inert")]))]))
        with self.assertRaises(NOTICES.NoticeError): NOTICES.archive_notices(target)

    def test_nested_archive_audit_depth_is_not_silently_skipped(self):
        data = archive([("LICENSE", self.mit)])
        for _ in range(3): data = archive([("classes.jar", data)])
        target = self.root / ".work/fixtures/deep.aar"; write(target, data)
        with self.assertRaisesRegex(NOTICES.NoticeError, "depth"): NOTICES.archive_notices(target)

    def test_archive_document_and_aggregate_byte_bounds(self):
        target = self.root / ".work/fixtures/large.jar"
        write(target, archive([("LICENSE", b"x" * 32)]))
        with patch.object(NOTICES, "MAX_DOCUMENT", 16), self.assertRaises(NOTICES.NoticeError): NOTICES.archive_notices(target)
        with patch.object(NOTICES, "MAX_NOTICES", 16), self.assertRaises(NOTICES.NoticeError): NOTICES.archive_notices(target)

    def test_native_code_detection_includes_versioned_shared_objects(self):
        target = self.root / ".work/fixtures/native.jar"
        write(target, archive([("native/libfixture.so.2", b"Authored inert fixture")]))
        self.assertTrue(NOTICES.archive_notices(target)[1])

    def test_local_project_artifacts_are_also_checked_for_embedded_test_classes(self):
        project = {"id": "project::shared", "kind": "project", "projectPath": ":shared"}
        path, _ = self.inventory(component=project, entries=[("org/junit/Test.class", b"inert")])
        with self.assertRaisesRegex(NOTICES.NoticeError, "JUnit/test"): NOTICES.generate(self.root, [path])

    def test_own_generated_legal_resources_are_not_recursively_collected(self):
        project = {"id": "project::shared", "kind": "project", "projectPath": ":shared"}
        path, _ = self.inventory(component=project, entries=[("composeResources/files/legal/LICENSE", self.mit)])
        _, report = NOTICES.generate(self.root, [path])
        self.assertEqual(len(report["documents"]), 3)
        self.assertEqual(report["sourceProjectArtifacts"][0]["sourceGrantResource"], "LICENSE")

    def test_nonregular_and_oversized_reads_are_rejected_before_open(self):
        target = self.root / ".work/fixtures/read"; write(target, b"123456789")
        with self.assertRaises(NOTICES.NoticeError): NOTICES.read_bounded(target, 8)
        info = type("Stat", (), {"st_mode": stat.S_IFIFO, "st_size": 0})()
        with patch.object(Path, "stat", return_value=info), patch.object(Path, "open", side_effect=AssertionError("must not open")):
            with self.assertRaises(NOTICES.NoticeError): NOTICES.read_bounded(target)

    def test_encoding_is_preserved_and_binary_legal_text_rejected(self):
        data = "Retained accented fixture: café\n".encode("iso-8859-1")
        text, encoding = NOTICES.decode_notice(data)
        self.assertIn("café", text); self.assertEqual(encoding, "iso-8859-1")
        for value in (b"", b"binary\0notice", b"control\x01text"):
            with self.assertRaises(NOTICES.NoticeError): NOTICES.decode_notice(value)

    def test_source_only_generation_is_deterministic_and_noop_preserves_files(self):
        files, _ = self.generated(); output = self.root / NOTICES.LEGAL
        NOTICES.sync_output(self.root, output, files, False)
        first = {path.relative_to(output).as_posix(): path.stat().st_mtime_ns for path in output.rglob("*") if path.is_file()}
        NOTICES.sync_output(self.root, output, files, False)
        NOTICES.sync_output(self.root, output, files, True)
        self.assertEqual(files, self.generated()[0])
        self.assertEqual(first, {path.relative_to(output).as_posix(): path.stat().st_mtime_ns for path in output.rglob("*") if path.is_file()})

    def test_changed_source_notice_can_regenerate_but_check_detects_staleness(self):
        files, _ = self.generated(); output = self.root / NOTICES.LEGAL
        NOTICES.sync_output(self.root, output, files, False)
        write(self.root / "NOTICE.md", b"Updated authored source notice.\n")
        changed, _ = self.generated()
        with self.assertRaisesRegex(NOTICES.NoticeError, "stale/missing"): NOTICES.sync_output(self.root, output, changed, True)
        NOTICES.sync_output(self.root, output, changed, False)
        NOTICES.sync_output(self.root, output, changed, True)

    def test_manual_file_edits_and_extra_files_or_empty_directories_are_preserved(self):
        files, _ = self.generated()
        for alteration in ("edit", "extra", "empty-dir"):
            output = self.root / ".work" / alteration
            NOTICES.sync_output(self.root, output, files, False)
            if alteration == "edit": write(output / "LICENSE", b"Locally edited fixture")
            elif alteration == "extra": write(output / "owner-file", b"Do not remove")
            else: (output / "owner-empty-directory").mkdir()
            before = sorted(path.relative_to(output).as_posix() for path in output.rglob("*"))
            with self.subTest(alteration=alteration), self.assertRaises(NOTICES.NoticeError): NOTICES.sync_output(self.root, output, files, False)
            self.assertEqual(before, sorted(path.relative_to(output).as_posix() for path in output.rglob("*")))

    def test_unmanaged_directory_or_forged_incomplete_index_cannot_be_overwritten(self):
        files, _ = self.generated(); output = self.root / ".work/unmanaged"
        write(output / "owner-file", b"Preserve me")
        with self.assertRaises(NOTICES.NoticeError): NOTICES.sync_output(self.root, output, files, False)
        for field, value in (("generator", "unknown"), ("schemaVersion", 2), ("fileHashes", {})):
            target = self.root / ".work" / field; NOTICES.sync_output(self.root, target, files, False)
            index = json.loads((target / "index.json").read_text()); index[field] = value; self.save(target / "index.json", index)
            with self.subTest(field=field), self.assertRaises(NOTICES.NoticeError): NOTICES.sync_output(self.root, target, files, False)

    def test_output_symlinks_and_staging_ancestors_are_rejected(self):
        files, _ = self.generated()
        actual = self.root / ".work/actual"; actual.mkdir(parents=True, exist_ok=True)
        link = self.root / ".work/link"
        try: link.symlink_to(actual, target_is_directory=True)
        except OSError as error: self.skipTest(str(error))
        for output in (link, link / "child", self.root / ".work", self.root / ".work/tmp"):
            with self.subTest(output=output.name), self.assertRaises(NOTICES.NoticeError): NOTICES.sync_output(self.root, output, files, False)
        self.assertEqual(list(actual.iterdir()), [])

    def test_symlink_within_generated_output_cannot_be_followed(self):
        files, _ = self.generated(); output = self.root / ".work/generated"
        NOTICES.sync_output(self.root, output, files, False)
        try: (output / "owner-link").symlink_to(self.root / "NOTICE.md")
        except OSError as error: self.skipTest(str(error))
        with self.assertRaises(NOTICES.NoticeError): NOTICES.sync_output(self.root, output, files, False)

    def test_test_worktree_override_cannot_populate_production_or_satisfy_completeness(self):
        for arguments in (("--test-license-worktree", str(self.root / "third_party/kmpYT")),
                          ("--test-license-worktree", str(self.root / "third_party/kmpYT"), "--output", str(self.root / ".work/test"), "--require-notices-complete")):
            with patch.object(NOTICES, "ROOT", self.root), patch.object(sys, "argv", ["package-notices.py", *arguments]):
                with self.assertRaisesRegex(NOTICES.NoticeError, "override cannot"): NOTICES.main()
        self.assertFalse((self.root / NOTICES.LEGAL).exists())

    def test_source_only_completeness_gate_returns_failure_not_synthetic_success(self):
        with patch.object(NOTICES, "ROOT", self.root), patch.object(sys, "argv", ["package-notices.py", "--require-notices-complete"]), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(NOTICES.main(), 1)
        report = json.loads((self.root / NOTICES.LEGAL / "index.json").read_text())
        self.assertFalse(report["noticeCoverageComplete"])
        self.assertFalse(report["binaryRedistributionReady"])

    def test_no_helper_arguments_keep_legacy_source_only_output_and_issues(self):
        files, report = self.generated()
        self.assertEqual(files, NOTICES.generate(self.root, [], helper_inventories=[])[0])
        self.assertEqual(report["coverageIssues"], ["Exact production runtime inventory has not been supplied."])
        self.assertNotIn("helpers", report)
        self.helper_inventory()
        self.assertEqual(files, self.generated()[0], "Unenrolled files must not affect source-only notices")

    def test_complete_authored_helper_enrollment_is_separate_from_maven_and_legal_closure(self):
        inventory, _ = self.inventory()
        helper, _ = self.helper_inventory()
        files, report = NOTICES.generate(self.root, [inventory], supplements=[self.supplement()], helper_inventories=[helper])
        self.assertTrue(report["noticeCoverageComplete"])
        self.assertTrue(report["helperInventoryCoverageComplete"])
        self.assertTrue(report["helperNoticeCoverageComplete"])
        self.assertEqual([item["id"] for item in report["components"]], [ID])
        self.assertEqual({item["component"] for item in report["helpers"]}, {"node", "chromium"})
        self.assertFalse(report["binaryRedistributionReady"])
        for item in report["helpers"]:
            self.assertEqual(item["delivery"], "downloaded-on-demand")
            self.assertTrue(item["noticeCoverageComplete"])
            self.assertFalse(item["legalCompatibilityVerified"])
            self.assertFalse(item["correspondingSourceVerified"])
            self.assertFalse(item["archiveReverifiedByCollector"])
            for record in item["documents"]:
                self.assertEqual(files["notices/" + record["sha256"] + ".txt"], (self.root / record["path"]).read_bytes())
        self.assertTrue(any("FFmpeg" in gate and "JDK" in gate for gate in report["unverifiedReleaseGates"]))

    def test_helper_only_inventory_does_not_close_missing_gradle_runtime_inventory(self):
        path, _ = self.helper_inventory()
        _, report = NOTICES.generate(self.root, [], helper_inventories=[path])
        self.assertTrue(report["helperNoticeCoverageComplete"])
        self.assertFalse(report["noticeCoverageComplete"])
        self.assertEqual(report["components"], [])
        self.assertIn("Exact production runtime inventory has not been supplied.", report["coverageIssues"])
        self.assertEqual(len(report["helperRuntimeInventoryIssues"]), 1)
        self.assertIn("desktop-linux-x64", report["helperRuntimeInventoryIssues"][0])

    def test_missing_required_helper_is_an_inventory_gap_even_if_caller_claims_complete(self):
        path, value = self.helper_inventory()
        value["helpers"] = value["helpers"][:1]; self.save(path, value)
        _, report = NOTICES.generate(self.root, [], helper_inventories=[path])
        self.assertFalse(report["helperInventoryCoverageComplete"])
        self.assertFalse(report["helperNoticeCoverageComplete"])
        self.assertTrue(any("missing required" in issue and "chromium" in issue for issue in report["helperInventoryIssues"]))
        self.assertEqual(report["helperNoticeIssues"], [])

    def test_empty_or_undeclared_helper_set_does_not_silently_pass(self):
        path, original = self.helper_inventory()
        for field, value in (("helperSetComplete", False), ("helpers", [])):
            changed = copy.deepcopy(original); changed[field] = value; self.save(path, changed)
            with self.subTest(field=field):
                _, report = NOTICES.generate(self.root, [], helper_inventories=[path])
                self.assertFalse(report["helperInventoryCoverageComplete"])

    def test_inventory_gaps_and_notice_gaps_are_distinct_from_source_legal_obligations(self):
        path, original = self.helper_inventory()
        for field in ("installedFiles", "coverage"):
            changed = copy.deepcopy(original); changed["helpers"][0][field]["complete"] = False; self.save(path, changed)
            with self.subTest(field=field):
                _, report = NOTICES.generate(self.root, [], helper_inventories=[path])
                self.assertEqual(bool(report["helperInventoryIssues"]), field == "installedFiles")
                self.assertEqual(bool(report["helperNoticeIssues"]), field == "coverage")
                self.assertFalse(report["helperNoticeCoverageComplete"])
                self.assertFalse(report["binaryRedistributionReady"])
                self.assertFalse(next(item for item in report["helpers"] if item["component"] == "node")["correspondingSourceVerified"])

    def test_helper_full_set_declaration_does_not_replace_every_original_text_review(self):
        path, original = self.helper_inventory()
        for alteration in ("empty", "unreviewed", "notice-only"):
            changed = copy.deepcopy(original)
            if alteration == "empty": changed["helpers"][0]["documents"] = []
            elif alteration == "unreviewed": changed["helpers"][0]["documents"][0]["completeTextVerified"] = False
            else: changed["helpers"][0]["documents"][0]["role"] = "notice"
            self.save(path, changed)
            with self.subTest(alteration=alteration):
                _, report = NOTICES.generate(self.root, [], helper_inventories=[path])
                self.assertTrue(report["helperInventoryCoverageComplete"])
                self.assertFalse(report["helperNoticeCoverageComplete"])
                self.assertTrue(report["helperNoticeIssues"])

    def test_helper_coverage_requires_typed_flags_scope_and_rationale(self):
        path, original = self.helper_inventory()
        cases = [("coverage", "complete", "true"), ("coverage", "scope", "root-license-only"),
                 ("coverage", "review", ""), ("installedFiles", "complete", 1)]
        for section, key, bad in cases:
            changed = copy.deepcopy(original); changed["helpers"][0][section][key] = bad; self.save(path, changed)
            with self.subTest(section=section, key=key), self.assertRaises(NOTICES.NoticeError):
                NOTICES.generate(self.root, [], helper_inventories=[path])
        for field in ("helperSetComplete", "schemaVersion"):
            changed = copy.deepcopy(original); changed[field] = 1 if field == "helperSetComplete" else True; self.save(path, changed)
            with self.subTest(field=field), self.assertRaises(NOTICES.NoticeError):
                NOTICES.generate(self.root, [], helper_inventories=[path])

    def test_unknown_fictitious_maven_and_duplicate_helpers_are_rejected(self):
        path, original = self.helper_inventory()
        for bad in ("ffmpeg", "org.example:node:1.2.3", [], None, "node"):
            changed = copy.deepcopy(original); changed["helpers"][1]["component"] = bad; self.save(path, changed)
            with self.subTest(bad=bad), self.assertRaises(NOTICES.NoticeError):
                NOTICES.generate(self.root, [], helper_inventories=[path])

    def test_unknown_duplicate_and_wrong_helper_targets_are_rejected(self):
        path, original = self.helper_inventory()
        with self.assertRaises(NOTICES.NoticeError): NOTICES.generate(self.root, [], helper_inventories=[path, path])
        for key, bad in (("platform", "android-release"), ("platform", []), ("type", "veyra-production-dependencies")):
            changed = copy.deepcopy(original); changed[key] = bad; self.save(path, changed)
            with self.subTest(key=key, bad=bad), self.assertRaises(NOTICES.NoticeError):
                NOTICES.generate(self.root, [], helper_inventories=[path])
        for key, bad in (("target", "windows-x64"), ("version", "2.0.0"), ("delivery", "bundled")):
            changed = copy.deepcopy(original); changed["helpers"][0][key] = bad; self.save(path, changed)
            with self.subTest(key=key), self.assertRaises(NOTICES.NoticeError):
                NOTICES.generate(self.root, [], helper_inventories=[path])

    def test_helpers_must_bind_actual_packaged_manifest_path_and_loader_pin(self):
        path, original = self.helper_inventory()
        copy_path = self.root / ".work/fixtures/copied-manifest.json"
        write(copy_path, (self.root / original["helpers"][0]["manifest"]["path"]).read_bytes())
        changed = copy.deepcopy(original); changed["helpers"][0]["manifest"] = self.record(copy_path); self.save(path, changed)
        with self.assertRaisesRegex(NOTICES.NoticeError, "actual packaged"): NOTICES.generate(self.root, [], helper_inventories=[path])
        changed = copy.deepcopy(original)
        manifest_path = self.root / changed["helpers"][0]["manifest"]["path"]
        write(manifest_path, manifest_path.read_bytes() + b"\n")
        changed["helpers"][0]["manifest"] = self.record(manifest_path); self.save(path, changed)
        with self.assertRaisesRegex(NOTICES.NoticeError, "loader pin"): NOTICES.generate(self.root, [], helper_inventories=[path])
        self.save(path, original)
        with self.assertRaisesRegex(NOTICES.NoticeError, "Stale/missing/changed"): NOTICES.generate(self.root, [], helper_inventories=[path])

    def test_helper_installer_source_fingerprints_are_complete_and_current(self):
        path, original = self.helper_inventory()
        for alteration in ("missing", "duplicate", "bad-type"):
            changed = copy.deepcopy(original)
            if alteration == "missing": changed["sourceInputs"].pop()
            elif alteration == "duplicate": changed["sourceInputs"][1] = changed["sourceInputs"][0]
            else: changed["sourceInputs"][0]["path"] = []
            self.save(path, changed)
            with self.subTest(alteration=alteration), self.assertRaises(NOTICES.NoticeError):
                NOTICES.generate(self.root, [], helper_inventories=[path])
        self.save(path, original)
        write(self.root / original["sourceInputs"][-1]["path"], b"Changed authored installer fixture.\n")
        with self.assertRaisesRegex(NOTICES.NoticeError, "Stale/missing/changed"):
            NOTICES.generate(self.root, [], helper_inventories=[path])

    def test_helper_archive_identity_and_installed_selection_cannot_be_guessed(self):
        path, original = self.helper_inventory()
        for section, key, bad in (("archive", "bytes", 124), ("archive", "sha256", "a" * 64),
                ("archive", "url", "https://example.invalid/archive"), ("installedFiles", "selection", {}),
                ("installedFiles", "entriesSha256", "b" * 64)):
            changed = copy.deepcopy(original); changed["helpers"][0][section][key] = bad; self.save(path, changed)
            with self.subTest(section=section, key=key), self.assertRaises(NOTICES.NoticeError):
                NOTICES.generate(self.root, [], helper_inventories=[path])

    def test_helper_manifest_malformed_entries_and_escaping_links_fail_before_notice_review(self):
        path, value = self.helper_inventory()
        manifest_path = self.root / value["helpers"][1]["manifest"]["path"]
        original = json.loads(manifest_path.read_text())
        cases = []
        for key, bad in (("path", "../escape"), ("path", "a//b"), ("path", "a/./b"), ("path", "a\0b"),
                         ("path", "CON.txt"), ("path", "dir./file"), ("path", "trailing "),
                         ("size", True), ("size", -1), ("sha256", "bad"), ("executable", 1)):
            changed = copy.deepcopy(original); changed["entries"][0][key] = bad; cases.append(changed)
        changed = copy.deepcopy(original); changed["entries"].append(dict(changed["entries"][0], path="CHROME")); cases.append(changed)
        changed = copy.deepcopy(original); changed["entries"].append({"path": "link", "size": 0, "link": "../outside", "executable": False}); cases.append(changed)
        changed = copy.deepcopy(original); changed["entries"][0]["executable"] = False; cases.append(changed)
        for index, changed in enumerate(cases):
            self.save(manifest_path, changed); self.refresh_helper_bindings(path, value)
            with self.subTest(index=index), self.assertRaises(NOTICES.NoticeError):
                NOTICES.generate(self.root, [], helper_inventories=[path])

    def test_changed_helper_installer_policy_requires_explicit_reaudit(self):
        path, value = self.helper_inventory()
        manifest_path = self.root / value["helpers"][0]["manifest"]["path"]
        original = json.loads(manifest_path.read_text())
        for key, bad in (("url", "https://example.invalid/node"), ("selection", {}), ("format", "zip"),
                         ("archiveRoot", "other-root"), ("platform", "windows-x64"), ("component", "other"),
                         ("unreviewedField", True)):
            changed = copy.deepcopy(original); changed[key] = bad; self.save(manifest_path, changed)
            self.refresh_helper_bindings(path, value)
            with self.subTest(key=key), self.assertRaises(NOTICES.NoticeError):
                NOTICES.generate(self.root, [], helper_inventories=[path])

    def test_helper_source_revision_requires_exact_reviewed_original_mapping(self):
        path, original = self.helper_inventory()
        for key, bad in (("revision", "main"), ("revision", "A" * 40), ("mappingReviewed", False),
                         ("mappingReviewed", "true"), ("repository", "https://example.invalid/node"), ("review", "")):
            changed = copy.deepcopy(original); changed["helpers"][0]["source"][key] = bad; self.save(path, changed)
            with self.subTest(key=key, bad=bad), self.assertRaises(NOTICES.NoticeError):
                NOTICES.generate(self.root, [], helper_inventories=[path])
        changed = copy.deepcopy(original); changed["helpers"][0]["source"]["revision"] = "f" * 40; self.save(path, changed)
        with self.assertRaisesRegex(NOTICES.NoticeError, "commit differs"):
            NOTICES.generate(self.root, [], helper_inventories=[path])

    def test_chromium_revisions_bind_both_snapshot_and_exact_source_commit(self):
        path, value = self.helper_inventory()
        source = value["helpers"][1]["source"]
        evidence = source["evidence"][0]
        evidence_path = self.root / evidence["path"]
        original = json.loads(evidence_path.read_text())
        for key, bad in (("chromium_revision", "1235"), ("got_revision", "f" * 40)):
            changed = copy.deepcopy(original); changed[key] = bad; self.save(evidence_path, changed)
            source["evidence"] = [self.record(evidence_path, role=evidence["role"], url=evidence["url"])]
            self.save(path, value)
            with self.subTest(key=key), self.assertRaisesRegex(NOTICES.NoticeError, "exact snapshot"):
                NOTICES.generate(self.root, [], helper_inventories=[path])

    def test_node_annotated_tag_requires_both_original_ref_and_peeled_commit_evidence(self):
        path, value = self.helper_inventory()
        source = value["helpers"][0]["source"]
        ref = source["evidence"][0]; ref_path = self.root / ref["path"]
        tag_sha = "3" * 40
        self.save(ref_path, {"ref": "refs/tags/v1.2.3", "object": {"type": "tag", "sha": tag_sha}})
        tag_path = self.root / ".work/fixtures/helpers/node-annotated-tag.json"
        tag = {"sha": tag_sha, "tag": "v1.2.3", "object": {"type": "commit", "sha": source["revision"]}}
        self.save(tag_path, tag)
        source["evidence"] = [self.record(ref_path, role=ref["role"], url=ref["url"]),
            self.record(tag_path, role="node-tag-object", url="https://api.github.com/repos/nodejs/node/git/tags/" + tag_sha)]
        self.save(path, value)
        self.assertTrue(NOTICES.generate(self.root, [], helper_inventories=[path])[1]["helperNoticeCoverageComplete"])
        for key, bad in (("sha", "4" * 40), ("tag", "v1.2.4"), ("object", {"type": "tag", "sha": source["revision"]}),
                         ("object", {"type": "commit", "sha": "f" * 40})):
            changed = copy.deepcopy(tag); changed[key] = bad; self.save(tag_path, changed)
            source["evidence"][1].update(self.record(tag_path)); self.save(path, value)
            with self.subTest(key=key), self.assertRaisesRegex(NOTICES.NoticeError, "does not peel"):
                NOTICES.generate(self.root, [], helper_inventories=[path])

    def test_helper_revision_evidence_hashes_urls_duplicates_and_absence_are_checked(self):
        path, original = self.helper_inventory()
        for alteration in ("hash", "url", "duplicate", "missing", "wrong-role"):
            changed = copy.deepcopy(original)
            evidence = changed["helpers"][0]["source"]["evidence"]
            if alteration == "hash": evidence[0]["sha256"] = "f" * 64
            elif alteration == "url": evidence[0]["url"] = "https://example.invalid/ref"
            elif alteration == "duplicate": evidence.append(copy.deepcopy(evidence[0]))
            elif alteration == "missing": evidence.clear()
            else: evidence[0]["role"] = "guessed"
            self.save(path, changed)
            with self.subTest(alteration=alteration), self.assertRaises(NOTICES.NoticeError):
                NOTICES.generate(self.root, [], helper_inventories=[path])

    def test_helper_original_source_document_binds_repository_revision_and_path(self):
        path, original = self.helper_inventory()
        for key, bad in (("revision", "main"), ("revision", "f" * 40), ("path", "OTHER-LICENSE"),
                         ("repository", "https://github.com/another/repository"), ("url", "https://example.invalid/" + "2" * 40 + "/LICENSE")):
            changed = copy.deepcopy(original); changed["helpers"][1]["documents"][0]["origin"][key] = bad; self.save(path, changed)
            with self.subTest(key=key), self.assertRaises(NOTICES.NoticeError):
                NOTICES.generate(self.root, [], helper_inventories=[path])

    def test_reviewed_dependency_source_document_can_record_its_own_immutable_revision(self):
        path, value = self.helper_inventory()
        document = value["helpers"][1]["documents"][0]
        revision = "5" * 40
        document["origin"] = {"kind": "source-file", "path": "legal/Full Terms.txt", "repository": "https://github.com/example/dependency",
            "revision": revision, "url": "https://raw.githubusercontent.com/example/dependency/" + revision + "/legal/Full%20Terms.txt"}
        document["provenance"] = "Authored dependency-set review fixture; a pointer is not actual DEPS/source closure."
        self.save(path, value)
        _, report = NOTICES.generate(self.root, [], helper_inventories=[path])
        chromium = next(item for item in report["helpers"] if item["component"] == "chromium")
        self.assertEqual(chromium["documents"][0]["origin"]["revision"], revision)
        self.assertFalse(chromium["correspondingSourceVerified"])

    def test_installed_license_document_must_match_selected_file_bytes_not_just_its_name(self):
        path, value = self.helper_inventory()
        document = value["helpers"][0]["documents"][0]
        file = self.root / document["path"]
        write(file, b"Different authored full text with the same filename.\n")
        document.update(self.record(file)); self.save(path, value)
        with self.assertRaisesRegex(NOTICES.NoticeError, "exact selected installed"):
            NOTICES.generate(self.root, [], helper_inventories=[path])

    def test_helper_document_hash_role_review_provenance_and_duplicates_are_checked(self):
        path, original = self.helper_inventory()
        for key, bad in (("sha256", "f" * 64), ("role", "pointer"), ("completeTextVerified", "true"), ("provenance", ""),
                         ("origin", {})):
            changed = copy.deepcopy(original); changed["helpers"][0]["documents"][0][key] = bad; self.save(path, changed)
            with self.subTest(key=key), self.assertRaises(NOTICES.NoticeError):
                NOTICES.generate(self.root, [], helper_inventories=[path])
        changed = copy.deepcopy(original); changed["helpers"][0]["documents"] *= 2; self.save(path, changed)
        with self.assertRaisesRegex(NOTICES.NoticeError, "Duplicate helper notice"):
            NOTICES.generate(self.root, [], helper_inventories=[path])

    def test_chromium_sample_credits_cannot_be_enrolled_as_complete_or_candidate_notices(self):
        path, value = self.helper_inventory()
        document = value["helpers"][1]["documents"][0]; file = self.root / document["path"]
        write(file, b"Authored placeholder-detection fixture: This is sample credits page. Set generate_about_credits=true.\n")
        document.update(self.record(file))
        for reviewed in (False, True):
            document["completeTextVerified"] = reviewed; self.save(path, value)
            with self.subTest(reviewed=reviewed), self.assertRaisesRegex(NOTICES.NoticeError, "sample credits"):
                NOTICES.generate(self.root, [], helper_inventories=[path])

    def test_helpers_preserve_windows_crlf_bytes_and_distinguish_target_manifest_hashes(self):
        linux, _ = self.helper_inventory()
        windows, _ = self.helper_inventory("desktop-windows-x64")
        files, report = NOTICES.generate(self.root, [], helper_inventories=[linux, windows])
        node = {item["target"]: item for item in report["helpers"] if item["component"] == "node"}
        linux_record, windows_record = node["linux-x64"]["documents"][0], node["windows-x64"]["documents"][0]
        unix, dos = files["notices/" + linux_record["sha256"] + ".txt"], files["notices/" + windows_record["sha256"] + ".txt"]
        self.assertNotEqual(linux_record["sha256"], windows_record["sha256"])
        self.assertNotEqual(node["linux-x64"]["manifest"]["sha256"], node["windows-x64"]["manifest"]["sha256"])
        self.assertEqual(unix, dos.replace(b"\r\n", b"\n"))

    def test_helper_mode_requires_each_supplied_desktop_runtime_target_not_only_one(self):
        inventory, value = self.inventory()
        value["platform"] = "desktop-windows-x64"; self.save(inventory, value)
        helper, _ = self.helper_inventory()
        _, report = NOTICES.generate(self.root, [inventory], supplements=[self.supplement()], helper_inventories=[helper])
        self.assertFalse(report["helperInventoryCoverageComplete"])
        self.assertTrue(any("desktop-windows-x64" in issue and "not been supplied" in issue for issue in report["helperInventoryIssues"]))
        for platform, extra_target in (("android-release", None), ("desktop-linux-x64", "desktop-windows-x64")):
            value["platform"] = platform; value["configuration"] = NOTICES.CONFIGURATIONS[platform]; self.save(inventory, value)
            helpers = [helper] + ([self.helper_inventory(extra_target)[0]] if extra_target else [])
            with self.subTest(platform=platform, extra_target=extra_target):
                _, report = NOTICES.generate(self.root, [inventory], supplements=[self.supplement()], helper_inventories=helpers)
                self.assertTrue(report["helperInventoryCoverageComplete"])
                self.assertTrue(report["helperNoticeCoverageComplete"])
                self.assertFalse(report["noticeCoverageComplete"], "A runtime inventory from another target cannot close this target's runtime gap")
                self.assertTrue(report["helperRuntimeInventoryIssues"])
                self.assertIn(extra_target or "desktop-linux-x64", report["helperRuntimeInventoryIssues"][0])
                self.assertTrue(all(issue in report["coverageIssues"] for issue in report["helperRuntimeInventoryIssues"]))

    def test_helper_enrollment_is_deterministic_for_all_four_target_argument_orders(self):
        paths = [self.helper_inventory(platform)[0] for platform in sorted(NOTICES.HELPER_PLATFORMS)]
        files, report = NOTICES.generate(self.root, [], helper_inventories=paths)
        self.assertEqual(len(report["helpers"]), 8)
        self.assertEqual(files, NOTICES.generate(self.root, [], helper_inventories=list(reversed(paths)))[0])

    def test_helper_inputs_reject_escape_noncanonical_paths_and_symlinks(self):
        path, original = self.helper_inventory()
        for bad in ("../outside", "/outside", "a//b", "a/./b", "a\\b", "C:/drive", "a\0b"):
            changed = copy.deepcopy(original); changed["helpers"][0]["documents"][0]["path"] = bad; self.save(path, changed)
            with self.subTest(bad=bad), self.assertRaises(NOTICES.NoticeError):
                NOTICES.generate(self.root, [], helper_inventories=[path])
        file = self.root / original["helpers"][0]["documents"][0]["path"]
        retained = file.with_suffix(".retained"); file.rename(retained)
        try: file.symlink_to(retained)
        except OSError as error: self.skipTest(str(error))
        self.save(path, original)
        with self.assertRaisesRegex(NOTICES.NoticeError, "Symlink"):
            NOTICES.generate(self.root, [], helper_inventories=[path])
        with self.assertRaises(NOTICES.NoticeError):
            NOTICES.load_helper_inventories(self.root, [self.root.parent / "outside.json"], [])

    def test_helper_duplicate_json_keys_and_nonfinite_numbers_are_rejected(self):
        for data in (b'{"schemaVersion":1,"schemaVersion":1}', b'{"nested":{"sha256":"a","sha256":"b"}}',
                     b'{"value":NaN}', b'{"value":Infinity}', b'{"value":1e9999}', b'{"value":-1e9999}', b"[]",
                     b'{"nested":' * 33 + b"0" + b"}" * 33, b'{"nested":' * 1500 + b"0" + b"}" * 1500):
            with self.subTest(data=data), self.assertRaises(NOTICES.NoticeError): NOTICES.helper_json(data)
        self.assertIsInstance(NOTICES.helper_json(b'{"nested":' * 32 + b"0" + b"}" * 32), dict)
        self.assertEqual(NOTICES.helper_json(b'{"finite":0.5}'), {"finite": 0.5})

    def test_helper_fingerprint_byte_limits_and_total_input_budget_are_bounded(self):
        file = self.root / ".work/fixtures/bounded-helper"; write(file, b"Authored bounded input")
        record = self.record(file)
        reader = NOTICES.HelperNoticeReader(self.root)
        with self.assertRaises(NOTICES.NoticeError): reader.file(record, maximum=3)
        with patch.object(NOTICES, "MAX_NOTICES", len(file.read_bytes())):
            reader = NOTICES.HelperNoticeReader(self.root)
            self.assertEqual(reader.file(record), file.read_bytes())
            with self.assertRaises(NOTICES.NoticeError): reader.file(record)
        record["bytes"] = True
        with self.assertRaises(NOTICES.NoticeError): NOTICES.HelperNoticeReader(self.root).file(record)

    def test_helper_provenance_urls_do_not_accept_credentials_queries_fragments_or_controls(self):
        for url in ("http://example.invalid/file", "https://user:secret@example.invalid/file",
                    "https://example.invalid/file?token=not-a-secret-fixture", "https://example.invalid/file#fragment",
                    "https://example.invalid:443/file", "https://example.invalid/\nfile", "https://[broken"):
            with self.subTest(url=url), self.assertRaises(NOTICES.NoticeError): NOTICES.helper_url(url)

    def test_helper_collection_never_opens_executable_archives_or_runs_downloaded_tools(self):
        path, _ = self.helper_inventory()
        with patch.object(NOTICES, "file_digest", side_effect=AssertionError("No helper archive hashing")), \
                patch.object(NOTICES.zipfile, "ZipFile", side_effect=AssertionError("No helper archive reading")), \
                patch.object(NOTICES.subprocess, "run", wraps=subprocess.run) as run:
            _, report = NOTICES.generate(self.root, [], helper_inventories=[path])
        self.assertTrue(report["helperNoticeCoverageComplete"])
        self.assertTrue(run.call_args_list)
        self.assertTrue(all(call.args[0][0] == "git" for call in run.call_args_list), "Only existing local source-grant Git reads run")

    def test_helper_cli_complete_gate_check_and_unreviewed_gap_use_same_explicit_inventory(self):
        inventory, _ = self.inventory()
        helper, value = self.helper_inventory()
        output = self.root / ".work/helper-cli"
        arguments = ["package-notices.py", "--inventory", str(inventory), "--supplement", str(self.supplement()),
                     "--helper-inventory", str(helper), "--require-notices-complete", "--output", str(output)]
        with patch.object(NOTICES, "ROOT", self.root), contextlib.redirect_stdout(io.StringIO()):
            with patch.object(sys, "argv", arguments): self.assertEqual(NOTICES.main(), 0)
            with patch.object(sys, "argv", [*arguments, "--check"]): self.assertEqual(NOTICES.main(), 0)
            value["helpers"][1]["coverage"]["complete"] = False; self.save(helper, value)
            with patch.object(sys, "argv", [*arguments, "--check"]), self.assertRaisesRegex(NOTICES.NoticeError, "stale/missing"):
                NOTICES.main()
            with patch.object(sys, "argv", arguments): self.assertEqual(NOTICES.main(), 1)
        report = json.loads((output / "index.json").read_text())
        self.assertFalse(report["noticeCoverageComplete"])
        self.assertTrue(report["helperInventoryCoverageComplete"])
        self.assertFalse(report["helperNoticeCoverageComplete"])
        self.assertFalse(report["binaryRedistributionReady"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
