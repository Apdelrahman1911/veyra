#!/usr/bin/env python3
"""Validate reporting rules with disposable repository-owned XML fixtures."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("verification_summary", ROOT / "scripts/verification-summary.py")
assert SPEC and SPEC.loader
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)


class VerificationReportingTests(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory(prefix="report-test-", dir=ROOT / ".work/tmp")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def write(self, module: str, task: str, text: str) -> None:
        path = self.root / module / "build/test-results" / task / "TEST-fixture.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def test_phase_does_not_count_another_targets_stale_xml(self) -> None:
        self.write("shared", "jvmTest", '<testsuite tests="3" failures="0" errors="0" skipped="1"/>')
        self.write("shared", "testDebugUnitTest", '<testsuite tests="25" failures="1"/>')
        desktop = REPORT.collect_tests("shared", "desktop", self.root)
        android = REPORT.collect_tests("shared", "android", self.root)
        self.assertEqual(desktop, {"suites": 1, "tests": 3, "failures": 0, "errors": 0, "skipped": 1})
        self.assertEqual(android["tests"], 25)
        self.assertEqual(android["failures"], 1)

    def test_missing_tests_are_zero_not_a_synthetic_pass(self) -> None:
        self.assertEqual(REPORT.collect_tests("composeApp", "desktop", self.root)["tests"], 0)

    def test_compiled_ios_sources_are_not_executed_test_counts(self) -> None:
        self.write("shared", "iosSimulatorArm64Test", '<testsuite tests="99"/>')
        self.assertEqual(REPORT.collect_tests("shared", "ios", self.root)["tests"], 0)

    def test_multiple_suites_aggregate_once(self) -> None:
        self.write("composeApp", "desktopTest", '<testsuites><testsuite tests="2"/><testsuite tests="3" errors="1"/></testsuites>')
        counts = REPORT.collect_tests("composeApp", "desktop", self.root)
        self.assertEqual((counts["suites"], counts["tests"], counts["errors"]), (2, 5, 1))

    def test_negative_or_impossible_counts_are_rejected(self) -> None:
        for text in ('<testsuite tests="-1"/>', '<testsuite tests="1" skipped="2"/>'):
            with self.subTest(xml=text):
                self.write("shared", "jvmTest", text)
                with self.assertRaises(ValueError):
                    REPORT.collect_tests("shared", "desktop", self.root)

    def test_non_junit_document_is_rejected(self) -> None:
        self.write("shared", "jvmTest", '<not-a-test tests="5"/>')
        with self.assertRaises(ValueError):
            REPORT.collect_tests("shared", "desktop", self.root)


if __name__ == "__main__":
    unittest.main(verbosity=2)
