#!/usr/bin/env python3
"""Fast standard-library tests. All test writes stay in Veyra/.work."""
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
TOOLS = ROOT / "scripts"
TMP = ROOT / ".work" / "tmp"
TMP.mkdir(parents=True, exist_ok=True)


class ScopedToolingTests(unittest.TestCase):
    def test_shell_syntax(self) -> None:
        for path in TOOLS.glob("*.sh"):
            with self.subTest(script=path.name):
                subprocess.run(["bash", "-n", str(path)], check=True)

    def test_owned_directories_and_parent_environment(self) -> None:
        before = {key: os.environ.get(key) for key in ("HOME", "TMPDIR", "GRADLE_USER_HOME")}
        command = (
            "import json,os; "
            "names=['HOME','GRADLE_USER_HOME','KONAN_DATA_DIR','TMPDIR','TMP','TEMP',"
            "'XDG_CACHE_HOME','XDG_CONFIG_HOME','XDG_DATA_HOME','XDG_RUNTIME_DIR',"
            "'ANDROID_USER_HOME','ANDROID_AVD_HOME','GIT_CONFIG_GLOBAL']; "
            "print(json.dumps({n:os.environ[n] for n in names}))"
        )
        result = subprocess.run(
            ["bash", str(TOOLS / "dev.sh"), sys.executable, "-B", "-c", command],
            check=True, text=True, capture_output=True,
        )
        values = json.loads(result.stdout)
        for key, value in values.items():
            with self.subTest(variable=key):
                self.assertTrue(Path(value).resolve().is_relative_to(ROOT))
        self.assertEqual(values["TMPDIR"], values["TMP"])
        self.assertEqual(values["TMP"], values["TEMP"])
        self.assertEqual(before, {key: os.environ.get(key) for key in before})

    def test_deprecated_android_preference_overrides_are_removed(self) -> None:
        environment = dict(os.environ, ANDROID_PREFS_ROOT="/must-not-be-used", ANDROID_SDK_HOME="/also-unused")
        result = subprocess.run(
            ["bash", str(TOOLS / "dev.sh"), sys.executable, "-B", "-c",
             "import os; assert 'ANDROID_PREFS_ROOT' not in os.environ; "
             "assert 'ANDROID_SDK_HOME' not in os.environ; "
             "assert os.environ['ANDROID_USER_HOME'].endswith('/.work/android/user')"],
            env=environment, text=True, capture_output=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_arguments_with_spaces_survive_launcher(self) -> None:
        result = subprocess.run(
            ["bash", str(TOOLS / "dev.sh"), sys.executable, "-B", "-c",
             "import sys; print(repr(sys.argv[1:]))", "a b", "Arabic ڤيرا", "--literal"],
            check=True, text=True, capture_output=True,
        )
        self.assertEqual(result.stdout.strip(), repr(["a b", "Arabic ڤيرا", "--literal"]))

    @unittest.skipUnless(sys.platform.startswith("linux"), "Linux namespace identity check")
    def test_namespace_mounts_refused_in_callers_namespace(self) -> None:
        namespace = os.readlink("/proc/self/ns/mnt")
        before = Path("/proc/self/mountinfo").read_bytes()
        result = subprocess.run(
            ["bash", str(TOOLS / "linux-namespace.sh"), "--inside", namespace,
             str(os.getuid()), str(os.getgid()), "true"], text=True, capture_output=True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Refusing a mount", result.stderr)
        self.assertEqual(before, Path("/proc/self/mountinfo").read_bytes())

    def test_escaping_work_directory_is_rejected_before_writes(self) -> None:
        with tempfile.TemporaryDirectory(prefix="boundary-test-", dir=TMP) as directory:
            base = Path(directory)
            fixture = base / "repo with spaces"
            scripts = fixture / "scripts"
            scripts.mkdir(parents=True)
            shutil.copy2(TOOLS / "env.sh", scripts / "env.sh")
            shutil.copy2(TOOLS / "dev.sh", scripts / "dev.sh")
            outside = base / "outside-fixture"
            outside.mkdir()
            try:
                (fixture / ".work").symlink_to(outside, target_is_directory=True)
            except (OSError, NotImplementedError) as error:
                self.skipTest(f"This host cannot create a test directory symlink: {error}")
            result = subprocess.run(
                ["bash", str(scripts / "dev.sh"), "true"], text=True, capture_output=True,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("out-of-repository", result.stderr)
            self.assertEqual(list(outside.iterdir()), [])

    @unittest.skipUnless(shutil.which("java") and shutil.which("node"), "existing Java and Node required")
    def test_java_node_real_temporary_directories_match(self) -> None:
        probe = r'''
import os,pathlib,re,subprocess
j=subprocess.run(['java','-XshowSettings:properties','-version'],text=True,capture_output=True,check=True)
m=re.search(r'^\s*java\.io\.tmpdir\s*=\s*(.+)$', j.stderr, re.M)
assert m, 'Java did not report java.io.tmpdir'
n=subprocess.run(['node','-e','process.stdout.write(require("os").tmpdir())'],text=True,capture_output=True,check=True)
a=pathlib.Path(m.group(1).strip()).resolve()
b=pathlib.Path(n.stdout.strip()).resolve()
assert a==b, 'Java/Node temporary directories differ'
assert a.is_relative_to(pathlib.Path(os.environ['VEYRA_PROJECT_ROOT']).resolve())
print('Java/Node real temporary directory parity: PASS')
'''
        result = subprocess.run(
            ["bash", str(TOOLS / "dev.sh"), sys.executable, "-B", "-c", probe],
            check=True, text=True, capture_output=True,
        )
        self.assertIn("parity: PASS", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
