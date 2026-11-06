#!/usr/bin/env python3
"""Record bounded, non-secret build metadata and actual JUnit test counts."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def command(args: list[str]) -> str:
    completed = subprocess.run(args, cwd=ROOT, text=True, capture_output=True)
    return completed.stdout.strip() if completed.returncode == 0 else "unavailable"


def collect_tests(module: str, phase: str, repository: Path = ROOT) -> dict[str, int]:
    counts = {"suites": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    tasks = {
        "desktop": {"shared": "jvmTest", "composeApp": "desktopTest"},
        "android": {"shared": "testDebugUnitTest", "composeApp": "testDebugUnitTest"},
    }
    base = repository / module / "build" / "test-results"
    if phase == "ios":
        return counts  # Native sources are compiled, NOT executed by initial CI.
    pattern = tasks[phase][module] + "/TEST-*.xml" if phase in tasks else "**/TEST-*.xml"
    for path in sorted(base.glob(pattern)):
        if not path.resolve().is_relative_to(repository.resolve()):
            raise ValueError("Out-of-repository JUnit result rejected")
        if path.stat().st_size > 20 * 1024 * 1024:
            raise ValueError(f"Oversized JUnit result rejected: {path.relative_to(repository)}")
        root = ET.parse(path).getroot()
        if root.tag not in ("testsuite", "testsuites"):
            raise ValueError("Unexpected JUnit document root")
        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
        for suite in suites:
            counts["suites"] += 1
            for key in ("tests", "failures", "errors", "skipped"):
                value = int(suite.attrib.get(key, "0"))
                if value < 0:
                    raise ValueError("Negative JUnit count rejected")
                counts[key] += value
    if counts["skipped"] > counts["tests"]:
        raise ValueError("JUnit skipped count exceeds total tests")
    return counts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", required=True, choices=("desktop", "android", "ios", "summary"))
    parser.add_argument("--metadata-only", action="store_true")
    parser.add_argument("--require-tests", nargs="*", choices=("shared", "composeApp"), default=[])
    args = parser.parse_args()
    output = ROOT / ".work" / "verification"
    output.mkdir(parents=True, exist_ok=True)
    metadata = {
        "recordedAtUtc": datetime.now(timezone.utc).isoformat(),
        "phase": args.phase,
        "commit": command(["git", "-c", f"safe.directory={ROOT}", "rev-parse", "HEAD"]),
        "worktreeDirty": command(["git", "-c", f"safe.directory={ROOT}", "status", "--porcelain"]) != "",
        "kmpytCommit": (ROOT / "gradle" / "kmpyt.commit").read_text().strip(),
        "os": platform.system(),
        "architecture": platform.machine(),
        "runnerImageVersion": os.environ.get("ImageVersion", "local/unavailable"),
        "runnerImageOS": os.environ.get("ImageOS", "local/unavailable"),
        "javaHome": os.environ.get("JAVA_HOME", "PATH-selected"),
        "xcodeDeveloperDirectory": os.environ.get("DEVELOPER_DIR", "not selected"),
        "workflowRun": os.environ.get("GITHUB_RUN_ID", "local"),
        "artifactUploadEnabled": False,
        "physicalDeviceVerification": "not performed by this script",
    }
    if args.metadata_only:
        (output / f"{args.phase}-environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
        print(json.dumps(metadata, indent=2))
        return 0
    tests = {module: collect_tests(module, args.phase) for module in ("shared", "composeApp")}
    metadata["tests"] = tests
    metadata["jobStatus"] = os.environ.get("VEYRA_JOB_STATUS", "command completed; see exit status")
    (output / f"{args.phase}-results.json").write_text(json.dumps(metadata, indent=2) + "\n")
    lines = [f"## Veyra — {args.phase}", "", f"Commit: `{metadata['commit']}`", "",
             "| Module | Tests | Failed | Errors | Skipped |", "|---|---:|---:|---:|---:|"]
    for module, counts in tests.items():
        lines.append(f"| {module} | {counts['tests']} | {counts['failures']} | {counts['errors']} | {counts['skipped']} |")
    lines += ["", "Counts describe discovered JUnit XML for this phase only; compiled native tests are not executed tests.",
              "No physical-device, audible-output, OS installation, signing or notarization claim.",
              "Physical audible output on every supported platform remains a mandatory, separate acceptance gate.",
              "No Actions artifacts/caches uploaded; repository-owned JSON/logs remain on the ephemeral runner.", ""]
    summary = "\n".join(lines)
    print(summary)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        # This is the explicitly requested Actions reporting channel, not a
        # tool cache or system configuration; GitHub manages its lifetime.
        with open(summary_path, "a", encoding="utf-8") as stream:
            stream.write(summary)
    for module in args.require_tests:
        counts = tests[module]
        if counts["tests"] <= counts["skipped"]:
            print(f"Required executed tests missing for {module}", file=sys.stderr)
            return 1
        if counts["failures"] or counts["errors"]:
            print(f"JUnit failures recorded for {module}", file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
