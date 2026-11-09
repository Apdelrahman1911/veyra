#!/usr/bin/env python3
"""Authored build-retention lane regressions. No network, Gradle or release action."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/build-retention.py"
SHA = "a" * 40
PIN = "b" * 40
SCHEMA = "c" * 64
RUN = "123"
ATTEMPT = "1"
PLATFORM = "desktop-linux-x64"
TAG = f"veyra-draft-{RUN}-{ATTEMPT}-{PLATFORM}"


class BuildRetentionTest(unittest.TestCase):
    def setUp(self):
        (ROOT / ".work/tmp").mkdir(parents=True, exist_ok=True)
        self.directory = Path(tempfile.mkdtemp(prefix="build-retention-", dir=ROOT / ".work/tmp"))
        self.root = self.directory / "repo"
        self.packet = self.directory / "packet"
        self.root.mkdir()
        (self.root / "gradle").mkdir()
        (self.root / "gradle/kmpyt.commit").write_text(PIN + "\n")
        self.schema = self.root / "shared/schemas/3.json"
        self.schema.parent.mkdir(parents=True)
        self.schema.write_text("{}\n")
        self.legal = self.root / "legal"
        self.legal.mkdir()
        (self.legal / "index.json").write_text('{"source":"authored"}\n')
        (self.legal / "THIRD_PARTY_NOTICES.txt").write_text("Authored notice\n")
        self.inventory = self.root / "inventory.json"
        self.inventory.write_text('{"authored":true}\n')
        self.packages_json = self.root / "packages.json"
        self.packages_json.write_text('{"packages":[]}\n')
        self.xml = self.root / "xml"
        self.xml.mkdir()
        (self.xml / "TEST-Authored.xml").write_text(
            '<testsuite tests="2" failures="0" errors="0" skipped="0"><testcase name="a"/></testsuite>\n')
        self.package = self.root / "build/Veyra_1.0.0.deb"
        self.package.parent.mkdir(parents=True)
        self.package.write_bytes(b"authored package bytes\n")
        self.packages_json.write_text(json.dumps({
            "source": {"commitSha": SHA, "kmpytPin": PIN, "schemaSha256": SCHEMA},
            "build": {"marker": "marker", "task": "packageDeb"},
            "packages": [{"path": self.package.relative_to(self.root).as_posix(),
                          "bytes": self.package.stat().st_size,
                          "sha256": hashlib.sha256(self.package.read_bytes()).hexdigest()}],
        }, sort_keys=True) + "\n")
        self.addCleanup(shutil.rmtree, self.directory, True)

    def create_args(self, *extra: str, lane_outcome: str = "success") -> list[str]:
        return [sys.executable, "-B", str(SCRIPT), "create",
            "--source-root", str(self.root), "--output", str(self.packet),
            "--platform", PLATFORM, "--lane-outcome", lane_outcome,
            "--diagnostic", "tests=success\npackage=success\n", "--repository", "Apdelrahman1911/veyra",
            "--commit-sha", SHA, "--run-id", RUN, "--run-attempt", ATTEMPT, "--release-tag", TAG,
            "--workflow-ref", "workflow@refs/heads/topic", "--ref", "refs/heads/topic",
            "--pin", PIN, "--schema-sha256", SCHEMA, "--host-label", "Authored-Host",
            "--xml-root", str(self.xml), "--legal", str(self.legal),
            "--inventory", str(self.inventory), *extra]

    def run_create(self, *extra: str, lane_outcome: str = "success", ok: bool = True):
        result = subprocess.run(self.create_args(*extra, lane_outcome=lane_outcome), cwd=ROOT,
                                text=True, capture_output=True)
        if ok:
            self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        else:
            self.assertNotEqual(0, result.returncode, result.stdout)
        return result

    def run_verify(self, *extra: str, ok: bool = True):
        result = subprocess.run([sys.executable, "-B", str(SCRIPT), "verify",
            "--directory", str(self.packet), "--expected-platform", PLATFORM,
            "--expected-sha", SHA, "--expected-tag", TAG, "--expected-run-id", RUN,
            "--expected-run-attempt", ATTEMPT, "--expected-repository", "Apdelrahman1911/veyra",
            "--expected-pin", PIN, "--expected-schema-sha256", SCHEMA, *extra],
            cwd=ROOT, text=True, capture_output=True)
        if ok:
            self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        else:
            self.assertNotEqual(0, result.returncode, result.stdout)
        return result

    def test_successful_lane_retains_package_support_and_counts(self):
        self.run_create("--package", str(self.package))
        self.run_verify()
        manifest = json.loads((self.packet / "manifest.json").read_text())
        self.assertEqual("success", manifest["laneOutcome"])
        self.assertEqual(["Veyra_1.0.0.deb"], manifest["packages"])
        self.assertEqual({"files": 1, "tests": 2, "failures": 0, "errors": 0, "skipped": 0, "issues": []},
                         manifest["testSummary"])
        with zipfile.ZipFile(self.packet / "support.zip") as archive:
            self.assertIn("provenance.json", archive.namelist())
            self.assertIn("diagnostic.txt", archive.namelist())
            self.assertIn("legal/0/index.json", archive.namelist())
            self.assertIn("inventory/inventory.json", archive.namelist())

    def test_manifest_bound_fresh_package_is_used(self):
        self.run_create("--packages-json", str(self.packages_json))
        self.run_verify()
        manifest = json.loads((self.packet / "manifest.json").read_text())
        self.assertEqual(["Veyra_1.0.0.deb"], manifest["packages"])
        with zipfile.ZipFile(self.packet / "support.zip") as archive:
            self.assertIn("packages.json", archive.namelist())

    def test_manifest_bound_package_hash_mismatch_is_rejected(self):
        value = json.loads(self.packages_json.read_text())
        value["packages"][0]["sha256"] = "d" * 64
        self.packages_json.write_text(json.dumps(value))
        self.run_create("--packages-json", str(self.packages_json), ok=False)

    def test_xml_system_and_failure_text_is_sanitized(self):
        sentinel_url = "https://signed.example.invalid/?token=gho_authoredsentinel"
        sentinel_token = "github_pat_authoredsentinel"
        (self.xml / "TEST-Authored.xml").write_text(
            '<testsuite tests="1" failures="1" errors="0" skipped="0" hostname="private-host">'
            f'<properties><property name="url" value="{sentinel_url}"/></properties>'
            f'<testcase classname="Authored.Test" name="keepsOutcome" time="0.1">'
            f'<failure type="AssertionError" message="{sentinel_token}">raw runtime {sentinel_url} {sentinel_token}</failure>'
            f'<system-out>raw stdout {sentinel_url} {sentinel_token}</system-out>'
            f'<system-err>raw stderr {sentinel_token}</system-err>'
            '</testcase></testsuite>\n')
        self.run_create("--package", str(self.package))
        self.run_verify()
        with zipfile.ZipFile(self.packet / "support.zip") as archive:
            name = next(item for item in archive.namelist() if item.startswith("xml/"))
            data = archive.read(name).decode()
        self.assertIn('type="AssertionError"', data)
        self.assertIn('classname="Authored.Test"', data)
        self.assertNotIn("system-out", data)
        self.assertNotIn("system-err", data)
        self.assertNotIn("properties", data)
        self.assertNotIn("raw runtime", data)
        self.assertNotIn(sentinel_url, data)
        self.assertNotIn(sentinel_token, data)
        self.assertNotIn("private-host", data)

    def test_failed_lane_can_retain_diagnostic_without_a_package(self):
        self.run_create(lane_outcome="failed")
        self.run_verify()
        manifest = json.loads((self.packet / "manifest.json").read_text())
        self.assertEqual("failed", manifest["laneOutcome"])
        self.assertEqual([], manifest["packages"])
        self.assertEqual({"manifest.json", "support.zip"}, {p.name for p in self.packet.iterdir()})

    def test_successful_lane_without_package_is_rejected(self):
        self.run_create(ok=False)

    def test_tampered_package_or_extra_file_is_rejected(self):
        self.run_create("--package", str(self.package))
        (self.packet / "Veyra_1.0.0.deb").write_bytes(b"tampered")
        self.run_verify(ok=False)

    def test_extra_file_is_rejected(self):
        self.run_create("--package", str(self.package))
        (self.packet / "extra.bin").write_bytes(b"extra")
        self.run_verify(ok=False)

    def test_symlinked_support_or_package_is_rejected(self):
        link = self.xml / "TEST-AuthoredLink.xml"
        try:
            link.symlink_to(self.xml / "TEST-Authored.xml")
        except OSError:
            self.skipTest("host does not permit symlink fixture")
        self.run_create("--package", str(self.package), ok=False)

    def test_credential_like_support_is_rejected(self):
        (self.legal / "index.json").write_text('{"token":"gho_authoredfixture"}\n')
        self.run_create("--package", str(self.package), ok=False)

    def test_directory_output_is_archived_without_following_symlinks(self):
        app = self.root / "Veyra.app"
        (app / "Contents").mkdir(parents=True)
        (app / "Contents/Info.plist").write_text("authored\n")
        self.run_create("--directory", "app=" + str(app), "--package", str(self.package))
        self.run_verify()
        manifest = json.loads((self.packet / "manifest.json").read_text())
        self.assertIn("app.zip", manifest["packages"])
        with zipfile.ZipFile(self.packet / "app.zip") as archive:
            self.assertEqual(["Contents/Info.plist"], archive.namelist())

    def test_identity_or_platform_mismatch_is_rejected(self):
        self.run_create("--package", str(self.package))
        self.run_verify("--expected-platform", "android", ok=False)

    def test_wrong_lane_tag_is_rejected_before_output(self):
        bad = self.create_args("--package", str(self.package))
        bad[bad.index("--release-tag") + 1] = "veyra-draft-123-2-desktop-linux-x64"
        result = subprocess.run(bad, cwd=ROOT, text=True, capture_output=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.packet.exists() and any(self.packet.iterdir()))


if __name__ == "__main__":
    unittest.main()
