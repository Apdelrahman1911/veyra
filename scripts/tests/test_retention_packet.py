#!/usr/bin/env python3
"""Authored nonproduction retention-packet regressions. No network or release action."""
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

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/retention-packet.py"
SHA = "a" * 40
PIN = "b" * 40
SCHEMA = "c" * 64
TAG = "veyra-draft-canary-123-1"


class RetentionPacketTest(unittest.TestCase):
    def setUp(self):
        (ROOT / ".work/tmp").mkdir(parents=True, exist_ok=True)
        self.directory = Path(tempfile.mkdtemp(prefix="retention-packet-", dir=ROOT / ".work/tmp"))
        self.packet = self.directory / "packet"
        self.addCleanup(shutil.rmtree, self.directory, True)

    def run_script(self, *arguments: str, ok: bool = True):
        result = subprocess.run([sys.executable, "-B", str(SCRIPT), *arguments],
                                cwd=ROOT, text=True, capture_output=True)
        if ok:
            self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        else:
            self.assertNotEqual(0, result.returncode, result.stdout)
        return result

    def create(self, tag: str = TAG, attempt: str = "1"):
        return self.run_script("create", "--output", str(self.packet),
            "--repository", "Apdelrahman1911/veyra", "--commit-sha", SHA,
            "--run-id", "123", "--run-attempt", attempt, "--release-tag", tag,
            "--workflow-ref", "Apdelrahman1911/veyra/.github/workflows/verify.yml@refs/heads/topic",
            "--ref", "refs/heads/topic", "--pin", PIN, "--schema-sha256", SCHEMA,
            "--host-label", "Ubuntu-24.04-X64", "--java-version", "openjdk 21.0.12",
            "--git-version", "git version 2.43.0", "--gh-version", "gh version 2.80.0",
            "--generated-at", "2026-11-09T00:00:00Z")

    def verify(self, *extra: str, ok: bool = True):
        return self.run_script("verify", "--directory", str(self.packet),
            "--expected-sha", SHA, "--expected-tag", TAG,
            "--expected-run-id", "123", "--expected-run-attempt", "1",
            "--expected-repository", "Apdelrahman1911/veyra", "--expected-pin", PIN,
            "--expected-schema-sha256", SCHEMA, *extra, ok=ok)

    def test_create_and_verify_round_trip_with_exact_bounded_manifest(self):
        created = json.loads(self.create().stdout)
        verified = json.loads(self.verify().stdout)
        self.assertTrue(created["created"])
        self.assertTrue(verified["verified"])
        self.assertEqual(SHA, verified["commitSha"])
        self.assertEqual(TAG, verified["releaseTag"])
        self.assertEqual({"payload.bin", "identity.json", "manifest.json", "SHA256SUMS"},
                         {path.name for path in self.packet.iterdir()})
        self.assertLessEqual(verified["totalBytes"], 64 * 1024)
        identity = json.loads((self.packet / "identity.json").read_text())
        self.assertEqual("veyra-retention-canary", identity["kind"])
        self.assertTrue(identity["nonproduction"])
        self.assertEqual(PIN, identity["kmpytPin"])
        self.assertEqual(SCHEMA, identity["schemaSha256"])
        self.assertFalse(identity["assetPolicy"]["containsAudio"])
        self.assertFalse(identity["assetPolicy"]["containsCredentials"])
        self.assertNotIn("token", (self.packet / "payload.bin").read_text().lower())

    def test_payload_tamper_is_rejected(self):
        self.create()
        (self.packet / "payload.bin").write_bytes(b"authored mutation not in the manifest")
        self.verify(ok=False)

    def test_exact_identity_mismatch_is_rejected(self):
        self.create()
        self.run_script("verify", "--directory", str(self.packet), "--expected-sha", "d" * 40,
            "--expected-tag", TAG, "--expected-run-id", "123", "--expected-run-attempt", "1",
            "--expected-repository", "Apdelrahman1911/veyra", "--expected-pin", PIN,
            "--expected-schema-sha256", SCHEMA, ok=False)

    def test_extra_or_missing_files_are_rejected(self):
        self.create()
        (self.packet / "extra.txt").write_text("authored extra\n")
        self.verify(ok=False)
        (self.packet / "extra.txt").unlink()
        (self.packet / "SHA256SUMS").unlink()
        self.verify(ok=False)

    def test_symlink_in_packet_is_rejected(self):
        self.create()
        (self.packet / "payload.bin").unlink()
        try:
            (self.packet / "payload.bin").symlink_to(self.packet / "identity.json")
        except OSError:
            self.skipTest("host does not permit symlink fixture")
        self.verify(ok=False)

    def test_duplicate_manifest_key_is_rejected(self):
        self.create()
        manifest = json.loads((self.packet / "manifest.json").read_text())
        text = json.dumps(manifest, indent=2, sort_keys=True)
        text = text.replace('"kind": "veyra-retention-canary",',
                            '"kind": "veyra-retention-canary", "kind": "veyra-retention-canary",')
        (self.packet / "manifest.json").write_text(text + "\n")
        lines = []
        for name in ("payload.bin", "identity.json", "manifest.json"):
            data = (self.packet / name).read_bytes()
            lines.append(hashlib.sha256(data).hexdigest() + "  " + name + "\n")
        (self.packet / "SHA256SUMS").write_text("".join(sorted(lines)))
        self.verify(ok=False)

    def test_wrong_canary_tag_is_rejected_before_writing(self):
        self.run_script("create", "--output", str(self.packet),
            "--repository", "Apdelrahman1911/veyra", "--commit-sha", SHA,
            "--run-id", "123", "--run-attempt", "1", "--release-tag", "veyra-draft-canary-123-2",
            "--workflow-ref", "ref", "--ref", "refs/heads/topic", "--pin", PIN,
            "--schema-sha256", SCHEMA, "--host-label", "Linux-X64",
            "--java-version", "21", "--git-version", "2", "--gh-version", "2", ok=False)
        self.assertFalse(self.packet.exists() and any(self.packet.iterdir()))


if __name__ == "__main__":
    unittest.main()
