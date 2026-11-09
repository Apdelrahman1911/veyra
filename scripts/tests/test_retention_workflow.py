#!/usr/bin/env python3
"""Static safety invariants for the manual retention canary workflow."""
from __future__ import annotations

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / ".github/workflows/verify.yml").read_text()
BUILD_RETENTION = (ROOT / "scripts/build-retention.sh").read_text()

class RetentionWorkflowTest(unittest.TestCase):
    def test_canary_is_manual_and_public_only(self):
        self.assertIn("workflow_dispatch:", WORKFLOW)
        self.assertIn("retention_canary:", WORKFLOW)
        self.assertIn("retain_builds:", WORKFLOW)
        self.assertIn("cancel-in-progress: ${{ !inputs.retention_canary && !inputs.retain_builds }}", WORKFLOW)
        self.assertIn("github.event.repository.private == false", WORKFLOW)
        self.assertIn("inputs.retention_canary == true", WORKFLOW)
        self.assertIn("inputs.retain_builds == true", WORKFLOW)
        self.assertNotIn("pull_request_target:", WORKFLOW)
        self.assertNotIn("self-hosted", WORKFLOW)
        self.assertNotIn("RUNNER_TEMP", WORKFLOW)
        self.assertNotIn("RUNNER_TEMP", BUILD_RETENTION)
        self.assertIn(".work/verification", WORKFLOW)

    def test_production_lanes_are_unique_per_run_attempt_and_platform(self):
        self.assertIn('tag="veyra-draft-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}-${platform}"', BUILD_RETENTION)
        self.assertIn("retention-plan", WORKFLOW)
        self.assertIn("production-retention-readback", WORKFLOW)
        self.assertIn("scripts/build-retention.sh", WORKFLOW)
        self.assertIn("scripts/build-retention.py verify", WORKFLOW)
        self.assertIn("desktop-linux-x64", WORKFLOW)
        self.assertIn("desktop-windows-x64", WORKFLOW)
        self.assertIn("desktop-macos-arm64", WORKFLOW)
        self.assertIn("desktop-macos-x64", WORKFLOW)
        self.assertIn("android-debug", WORKFLOW)
        self.assertIn("ios-simulator-arm64", WORKFLOW)
        self.assertIn("ios-simulator-x64", WORKFLOW)
        self.assertIn("ios-device-arm64", WORKFLOW)

    def test_production_build_and_retention_steps_are_separate(self):
        desktop = WORKFLOW.split("  desktop:", 1)[1].split("  android:", 1)[0]
        self.assertIn("id: desktop_tests", desktop)
        self.assertIn("id: desktop_package", desktop)
        self.assertIn("id: desktop_inventory", desktop)
        self.assertIn("id: retain", desktop)
        self.assertIn("continue-on-error: true", desktop)
        self.assertIn("if: ${{ always() }}", desktop)
        self.assertIn("always() && inputs.package_desktop == true", desktop)
        self.assertIn("always() && inputs.retain_builds == true", desktop)
        android = WORKFLOW.split("  android:", 1)[1].split("  ios:", 1)[0]
        self.assertIn("id: android_tests", android)
        self.assertIn("id: android_package", android)
        self.assertIn("always() && inputs.retain_builds == true", android)
        self.assertIn("id: retain", android)
        ios = WORKFLOW.split("  ios:", 1)[1].split("  # Nonproduction canary", 1)[0]
        self.assertIn("id: ios_tests", ios)
        self.assertIn("id: ios_build", ios)
        self.assertIn("always() && inputs.retain_builds == true", ios)
        self.assertIn("id: retain_simulator", ios)
        self.assertIn("id: retain_device", ios)

    def test_build_and_test_steps_do_not_receive_the_token(self):
        for name in ("Run bounded desktop tests", "Build desktop installer independently of test outcome",
                     "Build APK and execute JVM Android tests",
                     "Compile native tests and full unsigned simulator/device applications"):
            block = WORKFLOW.split("name: " + name, 1)[1].split("- name:", 1)[0]
            self.assertNotIn("GH_TOKEN", block)
            self.assertNotIn("github.token", block)
        for name in ("Retain this desktop lane", "Retain Android lane",
                     "Retain iOS simulator lane", "Retain iOS device arm64 lane"):
            block = WORKFLOW.split("name: " + name, 1)[1].split("- name:", 1)[0]
            self.assertIn("GH_TOKEN: ${{ github.token }}", block)

    def test_desktop_inventory_reuses_the_package_generated_exact_legal_inputs(self):
        desktop = WORKFLOW.split("  desktop:", 1)[1].split("  android:", 1)[0]
        self.assertIn("desktop-legal/$VEYRA_INVENTORY_PLATFORM", desktop)
        self.assertIn("--supplement third_party/notices/desktop-reviewed.json", desktop)
        self.assertEqual(2, desktop.count("--supplement third_party/notices/desktop-reviewed.json"))
        self.assertIn("--output \"$package_legal\" --check", desktop)
        self.assertIn("[ -f \"$package_legal/index.json\" ]", desktop)
        self.assertIn("embeddedLegal", desktop)
        self.assertIn("legal-inventory/$VEYRA_INVENTORY_PLATFORM", desktop)
        # Diagnostic fallback is allowed only in the explicit else branch.
        self.assertIn("          else\n            scripts/gradle.sh -I scripts/runtime-inventory.init.gradle", desktop)

    def test_desktop_retention_prefers_the_package_bound_legal_copy(self):
        desktop = WORKFLOW.split("  desktop:", 1)[1].split("  android:", 1)[0]
        retain = desktop.split("id: retain", 1)[1].split("Enforce desktop", 1)[0]
        self.assertIn('legal=".work/verification/desktop-legal/$VEYRA_INVENTORY_PLATFORM"', retain)
        self.assertIn('[ -f ".work/verification/legal-inventory/$VEYRA_INVENTORY_PLATFORM/index.json" ]', retain)
        self.assertIn('elif [ -f ".work/verification/desktop-legal/$VEYRA_INVENTORY_PLATFORM/index.json" ]', retain)
        self.assertIn('[ -n "$legal" ] && args+=(--legal "$legal")', retain)
        self.assertNotIn("--legal composeApp/src/commonMain", retain)
        self.assertIn("id: desktop_inventory", desktop)

    def test_production_readback_uses_release_id_not_tag_download(self):
        readback = WORKFLOW.split("name: Independent production lane readback", 1)[1]
        self.assertNotIn("gh release download", readback)
        self.assertIn("releases/$release_id/assets", readback)
        self.assertIn("releases/assets/$asset_id", readback)
        self.assertIn("expected-platform", readback)
        self.assertIn("published_at", readback)

    def test_canary_uses_exact_sha_and_unique_run_tag(self):
        self.assertIn("veyra-draft-canary-${{ github.run_id }}-${{ github.run_attempt }}", WORKFLOW)
        self.assertIn('-f sha="$GITHUB_SHA"', WORKFLOW)
        self.assertIn('test "$(gh api "repos/$REPO/git/ref/tags/$TAG" --jq .object.sha)" = "$GITHUB_SHA"', WORKFLOW)
        self.assertIn("test \"$(git rev-parse HEAD)\" = \"$GITHUB_SHA\"", WORKFLOW)
        self.assertIn("6805d4db8077650992d342e5395b1f0d7c80e984", WORKFLOW)
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
        self.assertIn("permissions:\n      contents: write", readback)
        self.assertIn("only reads", readback)
        # No job carries unrelated write scopes.
        self.assertNotIn("actions: write", WORKFLOW)
        self.assertNotIn("packages: write", WORKFLOW)
        self.assertNotIn("pull-requests: write", WORKFLOW)

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
