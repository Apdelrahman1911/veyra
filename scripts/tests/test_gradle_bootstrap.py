#!/usr/bin/env python3
"""Run the real scoped entrypoint with an authored, non-network wrapper."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASH = os.environ.get("VEYRA_TRUSTED_BASH") or shutil.which("bash") or "bash"


class GradleBootstrapTests(unittest.TestCase):
    def run_fixture(self, transient_failures=0, permanent=False, build_failure=False):
        temporary = tempfile.TemporaryDirectory(prefix="gradle-bootstrap-test-", dir=ROOT / ".work/tmp")
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        scripts = root / "scripts"
        scripts.mkdir()
        for name in ("env.sh", "gradle.sh"):
            shutil.copyfile(ROOT / "scripts" / name, scripts / name)
        (scripts / "bootstrap.sh").write_bytes(b"#!/usr/bin/env bash\nexit 0\n")
        (scripts / "bootstrap.sh").chmod(0o755)
        # Avoid actual delays; this executable exists only in the fixture PATH.
        tools = root / ".work/bin"
        tools.mkdir(parents=True)
        (tools / "sleep").write_bytes(b"#!/usr/bin/env bash\nexit 0\n")
        (tools / "sleep").chmod(0o755)
        (root / "gradlew").write_text('''#!/usr/bin/env bash
set -eu
if [ "$1" = --version ]; then
    count=0
    [ ! -f .work/version-count ] || read -r count < .work/version-count
    count=$((count+1)); printf '%s\\n' "$count" > .work/version-count
    if [ "$AUTHORED_PERMANENT" = 1 ]; then
        printf '%s\\n' 'Verification of Gradle distribution failed: checksum mismatch' >&2
        exit 19
    fi
    if [ "$count" -le "$AUTHORED_TRANSIENT_FAILURES" ]; then
        printf '%s\\n' 'java.io.IOException: Server returned HTTP response code: 504 for URL: https://github.com/gradle/gradle-distributions/releases/download/v8.13.0/gradle-8.13-bin.zip' >&2
        exit 18
    fi
    printf '%s\\n' 'Gradle 8.13'
    exit 0
fi
printf '%s\\n' "$@" >> .work/build-arguments
if [ "$AUTHORED_BUILD_FAILURE" = 1 ]; then
    printf '%s\\n' 'Server returned HTTP response code: 504 for URL: https://github.com/gradle/gradle-distributions/releases/download/v8.13.0/gradle-8.13-bin.zip' >&2
    exit 17
fi
exit 0
''', newline="\n")
        environment = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ.get("PATH", ""),
                           AUTHORED_TRANSIENT_FAILURES=str(transient_failures),
                           AUTHORED_PERMANENT=str(int(permanent)),
                           AUTHORED_BUILD_FAILURE=str(int(build_failure)))
        # The fixture has no production dependency or Gradle distribution.
        result = subprocess.run([BASH, str(scripts / "gradle.sh"), ":authored:verify"],
                                env=environment, cwd=root, text=True, capture_output=True, timeout=30)
        count = int((root / ".work/version-count").read_text())
        calls = root / ".work/build-arguments"
        arguments = calls.read_text().splitlines() if calls.exists() else []
        return result, count, arguments

    def test_ready_distribution_runs_requested_task_once(self):
        result, count, arguments = self.run_fixture()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, count)
        self.assertEqual(1, arguments.count(":authored:verify"))

    def test_transient_download_retries_only_bootstrap(self):
        result, count, arguments = self.run_fixture(transient_failures=2)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(3, count)
        self.assertEqual(1, arguments.count(":authored:verify"))

    def test_persistent_download_error_is_bounded_and_never_starts_task(self):
        result, count, arguments = self.run_fixture(transient_failures=4)
        self.assertEqual(18, result.returncode)
        self.assertEqual(3, count)
        self.assertEqual([], arguments)

    def test_checksum_failure_is_not_retried(self):
        result, count, arguments = self.run_fixture(permanent=True)
        self.assertEqual(19, result.returncode)
        self.assertEqual(1, count)
        self.assertEqual([], arguments)

    def test_task_failure_is_not_retried_even_with_matching_http_message(self):
        result, count, arguments = self.run_fixture(build_failure=True)
        self.assertEqual(17, result.returncode)
        self.assertEqual(1, count)
        self.assertEqual(1, arguments.count(":authored:verify"))


if __name__ == "__main__":
    unittest.main()
