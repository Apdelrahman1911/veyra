#!/usr/bin/env python3
"""Authored package-manifest provenance/keying regressions. No Gradle or package build."""
from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/package-manifest.py"
SHA = "a" * 40
PIN = "b" * 40


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

    def package(self, name: str = "Veyra_1.0.0_amd64.deb", data: bytes = b"authored package bytes\n",
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

    def test_stale_package_is_not_accepted_as_current_build(self):
        self.package(nanoseconds_after_marker=-1_000_000_000)
        result = self.run_manifest()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("newly generated", result.stderr)

    def test_stale_package_is_excluded_when_a_new_one_exists(self):
        self.package("old.deb", nanoseconds_after_marker=-1_000_000_000)
        current = self.package("current.deb")
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
        link = self.packages / "link.deb"
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
