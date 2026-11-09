#!/usr/bin/env python3
"""Static safety invariants for the manual retention canary workflow."""
from __future__ import annotations

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / ".github/workflows/verify.yml").read_text()

class RetentionWorkflowTest(unittest.TestCase):
    def test_canary_is_manual_and_public_only(self):
        self.assertIn("workflow_dispatch:", WORKFLOW)
        self.assertIn("retention_canary:", WORKFLOW)
        self.assertIn("github.event.repository.private == false", WORKFLOW)
        self.assertIn("inputs.retention_canary == true", WORKFLOW)
        self.assertNotIn("pull_request_target:", WORKFLOW)
        self.assertNotIn("self-hosted", WORKFLOW)

    def test_canary_uses_exact_sha_and_unique_run_tag(self):
        self.assertIn("veyra-draft-canary-${{ github.run_id }}-${{ github.run_attempt }}", WORKFLOW)
        self.assertIn('-f sha="$GITHUB_SHA"', WORKFLOW)
        self.assertIn('test "$(gh api "repos/$REPO/git/ref/tags/$TAG" --jq .object.sha)" = "$GITHUB_SHA"', WORKFLOW)
        self.assertIn("test \"$(git rev-parse HEAD)\" = \"$GITHUB_SHA\"", WORKFLOW)
        self.assertIn("c0a08090fe7dbd76e20216559b8184ad2cccde63", WORKFLOW)
        self.assertIn("aab30e43a35a431a25a7ecbb7ebccefd849811f688957054304b02d3caf6d367", WORKFLOW)

    def test_release_is_draft_prerelease_and_not_clobbered(self):
        self.assertIn("--draft", WORKFLOW)
        self.assertIn("--prerelease", WORKFLOW)
        self.assertNotIn("--clobber", WORKFLOW)
        self.assertNotIn("gh release edit", WORKFLOW)
        self.assertNotIn("gh release publish", WORKFLOW)
        self.assertIn("Refusing to reuse an existing release/tag", WORKFLOW)
        self.assertIn('if gh api "repos/$REPO/git/ref/tags/$TAG" >/dev/null 2>&1; then', WORKFLOW)
        self.assertNotIn("--jq .object.sha 2>/dev/null || true", WORKFLOW)
        self.assertIn('gh api --method POST "repos/$REPO/git/refs"', WORKFLOW)
        self.assertIn("--verify-tag", WORKFLOW)
        self.assertNotIn("--target \"$GITHUB_SHA\"", WORKFLOW)

    def test_no_artifact_or_cache_storage_and_no_paid_runner(self):
        self.assertNotIn("upload-artifact", WORKFLOW)
        self.assertNotIn("actions/cache", WORKFLOW)
        self.assertNotIn("self-hosted", WORKFLOW)
        self.assertNotIn("larger-runner", WORKFLOW)
        self.assertNotIn("macos-14", WORKFLOW)

    def test_permissions_are_least_privilege(self):
        canary = WORKFLOW.split("  retention-canary:", 1)[1].split("  retention-readback:", 1)[0]
        readback = WORKFLOW.split("  retention-readback:", 1)[1]
        self.assertIn("permissions:\n      contents: write", canary)
        self.assertIn("permissions:\n      contents: read", readback)
        self.assertNotIn("contents: write", readback)

    def test_readback_is_independent_and_hash_checks(self):
        readback = WORKFLOW.split("  retention-readback:", 1)[1]
        self.assertIn("gh release download", readback)
        self.assertIn("scripts/retention-packet.py verify", readback)
        self.assertIn("releases/$EXPECTED_RELEASE_ID/assets", readback)
        self.assertIn("releases/assets/$asset_id", readback)
        self.assertIn("sha256sum shared/schemas/dev.veyra.data.room.VeyraDatabase/3.json", readback)
        self.assertIn('release.get("draft") is not True', readback)
        self.assertIn("published_at", readback)
        self.assertNotIn("releases/tags/$TAG", WORKFLOW)
        self.assertIn('gh release view "$TAG" --repo "$REPO" --json databaseId', WORKFLOW)


if __name__ == "__main__":
    unittest.main()
