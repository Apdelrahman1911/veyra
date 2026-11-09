#!/usr/bin/env python3
"""Authored package-manifest provenance/keying regressions. No Gradle or package build."""
from __future__ import annotations

import hashlib
import io
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/package-manifest.py"
SHA = "a" * 40
PIN = "b" * 40
EXT = {"Linux": ".deb", "Darwin": ".dmg", "Windows": ".msi"}[platform.system()]
PREFIX = "composeResources/dev.veyra.generated.resources/files/legal/"
PLATFORM = "desktop-linux-x64"


def hexdigest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class PackageManifestTest(unittest.TestCase):
    def setUp(self):
        (ROOT / ".work/tmp").mkdir(parents=True, exist_ok=True)
        self.directory = Path(tempfile.mkdtemp(prefix="package-manifest-", dir=ROOT / ".work/tmp"))
        self.root = self.directory / "repo"
        self.base = self.root / "composeApp/build/compose/binaries/main"
        self.packages = self.base / "deb"
        self.packages.mkdir(parents=True)
        (self.root / "gradle").mkdir()
        (self.root / "gradle/kmpyt.commit").write_text(PIN + "\n")
        schema = self.root / "shared/schemas/dev.veyra.data.room.VeyraDatabase/3.json"
        schema.parent.mkdir(parents=True)
        schema.write_text("{}\n")
        legal = self.root / "composeApp/src/commonMain/composeResources/files/legal/index.json"
        legal.parent.mkdir(parents=True)
        legal.write_text("{}\n")
        self.marker = self.root / "marker"
        self.marker.write_text("build started\n")
        subprocess.run(["git", "-C", str(self.root), "init", "--quiet", "--object-format=sha1"], check=True,
                       capture_output=True)
        subprocess.run(["git", "-C", str(self.root), "-c", "user.name=Fixture",
                        "-c", "user.email=fixture@example.invalid", "add", "."], check=True, capture_output=True)
        subprocess.run(["git", "-C", str(self.root), "-c", "user.name=Fixture",
                        "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "fixture"],
                       check=True, capture_output=True)
        self.source_sha = subprocess.check_output(["git", "-C", str(self.root), "rev-parse", "HEAD"],
                                                  text=True).strip()
        self.addCleanup(shutil.rmtree, self.directory, True)

    def package(self, name: str = "Veyra_1.0.0_amd64" + EXT, data: bytes = b"authored package bytes\n",
                nanoseconds_after_marker: int = 1_000_000_000) -> Path:
        path = self.packages / name
        path.write_bytes(data)
        target = self.marker.stat().st_mtime_ns + nanoseconds_after_marker
        os.utime(path, ns=(target, target))
        return path

    def run_manifest(self, *extra: str, ok: bool = True):
        return subprocess.run(
            [sys.executable, "-B", str(SCRIPT),
             "--root", str(self.root), "--base", str(self.base),
             "--newer-than", str(self.marker),
             "--expected-source-sha", self.source_sha, "--repository", "Apdelrahman1911/veyra",
             "--task", "packageDeb", "--output", str(self.root / ".work/verification/packages.json"),
             *extra], cwd=ROOT, text=True, capture_output=True)

    def legal_fixture(self, platform_id: str = PLATFORM, components: bool = True) -> tuple[Path, Path, bytes]:
        license_text = b"Authored complete embedded license fixture.\n"
        notice_text = b"Authored embedded notice fixture.\n"
        notice_name = "notices/" + hexdigest(notice_text) + ".txt"
        files = {
            "INDEX.txt": b"Authored embedded index fixture.\n",
            "LICENSE": license_text,
            "NOTICE.md": b"Authored embedded source notice fixture.\n",
            "THIRD_PARTY_NOTICES.txt": b"Authored embedded combined notices fixture.\n",
            "kmpYT-LICENSE": b"Authored embedded upstream MIT fixture.\n",
            "kmpYT-THIRD_PARTY_NOTICES.md": b"Authored embedded upstream notice fixture.\n",
            notice_name: notice_text,
        }
        hashes = {name: hexdigest(data) for name, data in sorted(files.items())}
        documents = [
            {"path": "LICENSE", "bytes": len(license_text), "sha256": hexdigest(license_text)},
            {"path": notice_name, "bytes": len(notice_text), "sha256": hexdigest(notice_text)},
        ]
        index = {
            "schemaVersion": 1,
            "generator": "scripts/package-notices.py",
            "testOnly": False,
            "binaryRedistributionReady": False,
            "source": {"kmpytPin": PIN, "mode": "actual-pinned-checkout"},
            "platforms": [platform_id],
            "components": [{"id": "org.example:runtime:1.0", "platform": platform_id}]
            if components else [],
            "documents": documents,
            "coverageIssues": ["Authored open coverage fixture remains visible."],
            "fileHashes": hashes,
            "generatedFiles": sorted([*files, "index.json"]),
        }
        index_bytes = (json.dumps(index, indent=2, sort_keys=True) + "\n").encode()
        files["index.json"] = index_bytes
        generated = self.root / ".work/desktop-legal" / platform_id
        for name, data in files.items():
            target = generated / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        app_jar = self.base / "app/Veyra/lib/app/composeApp-desktop-1.0.0.jar"
        app_jar.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(app_jar, "w") as archive:
            for name, data in files.items():
                archive.writestr(PREFIX + name, data)
        return app_jar, generated, index_bytes

    def embedded_args(self, jar: Path, generated: Path) -> tuple[str, ...]:
        return ("--app-jar", str(jar), "--desktop-legal-dir", str(generated),
                "--expected-legal-platform", PLATFORM, "--require-embedded-legal")

    def test_new_package_is_bound_to_exact_source_pin_run_and_host(self):
        package = self.package()
        result = self.run_manifest()
        self.assertEqual(0, result.returncode, result.stderr)
        value = json.loads((self.root / ".work/verification/packages.json").read_text())
        self.assertEqual(self.source_sha, value["source"]["commitSha"])
        self.assertEqual(PIN, value["source"]["kmpytPin"])
        self.assertEqual(64, len(value["source"]["schemaSha256"]))
        self.assertEqual("Apdelrahman1911/veyra", value["repository"])
        self.assertEqual("packageDeb", value["build"]["task"])
        self.assertEqual("marker", value["build"]["marker"])
        self.assertEqual([package.relative_to(self.root).as_posix()], [item["path"] for item in value["packages"]])
        self.assertEqual(package.stat().st_size, value["packages"][0]["bytes"])
        self.assertFalse(value["packages"][0]["installationTested"])
        self.assertTrue(value["nonproductionProvenanceOnly"])

    def test_nonproduction_fixture_records_source_tree_scope_explicitly(self):
        package = self.package()
        result = self.run_manifest()
        self.assertEqual(0, result.returncode, result.stderr)
        value = json.loads((self.root / ".work/verification/packages.json").read_text())
        self.assertFalse(value["embeddedLegal"]["verified"])
        self.assertEqual("source-tree-unverified", value["source"]["legalIndexScope"])
        self.assertFalse(value["packages"][0]["embeddedLegalVerified"])
        self.assertEqual(package.relative_to(self.root).as_posix(), value["packages"][0]["path"])

    def test_source_preview_hash_preserves_crlf_bytes(self):
        self.package()
        source_preview = self.root / "composeApp/src/commonMain/composeResources/files/legal/index.json"
        source_preview.write_bytes(b"{}\r\n")
        result = self.run_manifest()
        self.assertEqual(0, result.returncode, result.stderr)
        value = json.loads((self.root / ".work/verification/packages.json").read_text())
        self.assertEqual(hexdigest(b"{}\r\n"), value["source"]["legalIndexSourceTreeSha256"])
        self.assertNotEqual(hexdigest(b"{}\n"), value["source"]["legalIndexSourceTreeSha256"])

    def test_embedded_legal_binds_actual_jar_bytes_and_generated_source(self):
        self.package()
        jar, generated, index_bytes = self.legal_fixture()
        result = self.run_manifest(*self.embedded_args(jar, generated))
        self.assertEqual(0, result.returncode, result.stderr)
        value = json.loads((self.root / ".work/verification/packages.json").read_text())
        embedded = value["embeddedLegal"]
        self.assertTrue(embedded["verified"])
        self.assertEqual("verified-embedded-app-jar", value["source"]["legalIndexScope"])
        self.assertEqual(hexdigest(index_bytes), value["source"]["legalIndexSha256"])
        source_preview = self.root / "composeApp/src/commonMain/composeResources/files/legal/index.json"
        self.assertEqual(hexdigest(source_preview.read_bytes()),
                         value["source"]["legalIndexSourceTreeSha256"])
        self.assertNotEqual(value["source"]["legalIndexSha256"],
                            value["source"]["legalIndexSourceTreeSha256"])
        self.assertEqual(hexdigest(index_bytes), embedded["indexSha256"])
        self.assertEqual(PREFIX, embedded["resourcePrefix"])
        self.assertEqual([PLATFORM], embedded["platforms"])
        self.assertEqual(1, embedded["componentCount"])
        self.assertTrue(embedded["generatedSourceMatched"])
        self.assertEqual(jar.relative_to(self.root).as_posix(), embedded["appJarPath"])
        self.assertTrue(value["packages"][0]["embeddedLegalVerified"])
        indexed = {item["path"]: item for item in embedded["files"]}
        self.assertTrue(any(name.startswith("notices/") for name in indexed))
        self.assertIn("index.json", indexed)
        self.assertEqual(len(indexed), len(json.loads(index_bytes)["generatedFiles"]))

    def test_tampered_embedded_notice_is_rejected(self):
        self.package()
        jar, generated, _ = self.legal_fixture()
        with zipfile.ZipFile(jar) as archive:
            entries = {name: archive.read(name) for name in archive.namelist()}
        entries[PREFIX + "LICENSE"] = b"Tampered embedded license bytes.\n"
        with zipfile.ZipFile(jar, "w") as archive:
            for name, data in entries.items():
                archive.writestr(name, data)
        result = self.run_manifest(*self.embedded_args(jar, generated))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Tampered embedded legal", result.stderr)

    def test_missing_and_stale_original_entries_are_rejected(self):
        self.package()
        jar, generated, _ = self.legal_fixture()
        with zipfile.ZipFile(jar) as archive:
            entries = {name: archive.read(name) for name in archive.namelist()}
        # Remove one generated document while keeping the jar's index bytes.
        entries.pop(PREFIX + "NOTICE.md")
        with zipfile.ZipFile(jar, "w") as archive:
            for name, data in entries.items():
                archive.writestr(name, data)
        result = self.run_manifest(*self.embedded_args(jar, generated))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Embedded legal resource set differs", result.stderr)

        entries[PREFIX + "notices/source-only-stale.txt"] = b"stale source-only fixture\n"
        with zipfile.ZipFile(jar, "w") as archive:
            for name, data in entries.items():
                archive.writestr(name, data)
        result = self.run_manifest(*self.embedded_args(jar, generated))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Embedded legal resource set differs", result.stderr)

    def test_duplicate_embedded_entry_is_rejected(self):
        self.package()
        jar, generated, _ = self.legal_fixture()
        with zipfile.ZipFile(jar) as archive:
            entries = {name: archive.read(name) for name in archive.namelist()}
        with zipfile.ZipFile(jar, "w") as archive:
            for name, data in entries.items():
                archive.writestr(name, data)
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                archive.writestr(PREFIX + "LICENSE", entries[PREFIX + "LICENSE"])
        result = self.run_manifest(*self.embedded_args(jar, generated))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Duplicate embedded legal JAR entry", result.stderr)

    def test_source_only_zero_component_index_is_rejected(self):
        self.package()
        jar, generated, _ = self.legal_fixture(components=False)
        result = self.run_manifest(*self.embedded_args(jar, generated))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("source-only index rejected", result.stderr)

    def test_embedded_index_must_match_generated_directory(self):
        self.package()
        jar, generated, _ = self.legal_fixture()
        index = json.loads((generated / "index.json").read_text())
        index["coverageIssues"].append("Directory-only change fixture.")
        generated.joinpath("index.json").write_text(json.dumps(index, indent=2, sort_keys=True) + "\n")
        result = self.run_manifest(*self.embedded_args(jar, generated))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("differ from the generated directory", result.stderr)

    def test_require_embedded_without_app_jar_is_rejected(self):
        self.package()
        result = self.run_manifest("--require-embedded-legal")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("needs the exact --app-jar", result.stderr)

    def test_outside_or_symlinked_app_jar_is_rejected(self):
        self.package()
        jar, generated, _ = self.legal_fixture()
        outside = self.directory / "outside.jar"
        outside.write_bytes(jar.read_bytes())
        result = self.run_manifest(*self.embedded_args(outside, generated))
        self.assertNotEqual(0, result.returncode)
        link = self.root / "linked.jar"
        try:
            link.symlink_to(jar)
        except OSError:
            self.skipTest("host does not permit symlink fixture")
        result = self.run_manifest(*self.embedded_args(link, generated))
        self.assertNotEqual(0, result.returncode)

    def test_embedded_path_traversal_entry_is_rejected(self):
        self.package()
        jar, generated, _ = self.legal_fixture()
        with zipfile.ZipFile(jar, "a") as archive:
            archive.writestr(PREFIX + "../escape.txt", b"escape fixture")
        result = self.run_manifest(*self.embedded_args(jar, generated))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("not a canonical relative path", result.stderr)

    def test_stale_embedded_legal_directory_is_rejected(self):
        self.package()
        jar, generated, _ = self.legal_fixture()
        with zipfile.ZipFile(jar, "a") as archive:
            archive.writestr(PREFIX + "source-only-stale/", b"")
        result = self.run_manifest(*self.embedded_args(jar, generated))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("stale embedded legal directory", result.stderr)

    def test_stale_package_is_not_accepted_as_current_build(self):
        self.package(nanoseconds_after_marker=-1_000_000_000)
        result = self.run_manifest()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("newly generated", result.stderr)

    def test_stale_package_is_excluded_when_a_new_one_exists(self):
        self.package("old" + EXT, nanoseconds_after_marker=-1_000_000_000)
        current = self.package("current" + EXT)
        result = self.run_manifest()
        self.assertEqual(0, result.returncode, result.stderr)
        value = json.loads((self.root / ".work/verification/packages.json").read_text())
        self.assertEqual([current.relative_to(self.root).as_posix()], [item["path"] for item in value["packages"]])

    def test_wrong_explicit_source_sha_is_rejected(self):
        self.package()
        result = self.run_manifest("--expected-source-sha", "c" * 40)
        self.assertNotEqual(0, result.returncode)

    def test_symlinked_package_is_rejected(self):
        target = self.package()
        link = self.packages / ("link" + EXT)
        try:
            link.symlink_to(target)
        except OSError:
            self.skipTest("host does not permit symlink fixture")
        result = self.run_manifest()
        self.assertNotEqual(0, result.returncode)

    def test_output_outside_repository_is_rejected(self):
        self.package()
        result = self.run_manifest("--output", str(self.directory / "outside.json"))
        self.assertNotEqual(0, result.returncode)

    def test_missing_marker_or_package_is_rejected(self):
        self.package()
        self.marker.unlink()
        result = self.run_manifest()
        self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
