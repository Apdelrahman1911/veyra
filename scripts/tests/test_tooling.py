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
BASH = os.environ.get("VEYRA_TRUSTED_BASH") or shutil.which("bash") or "bash"


class ScopedToolingTests(unittest.TestCase):
    def test_shell_syntax(self) -> None:
        bash = BASH
        if sys.platform.startswith("win"):
            self.assertNotIn("system32", bash.lower(), "Windows shell resolution must not use the WSL shim")
        for path in TOOLS.glob("*.sh"):
            with self.subTest(script=path.name):
                subprocess.run([bash, "-n", str(path)], check=True)

    def test_scoped_bash_and_jdk_selection(self) -> None:
        bash = BASH
        result = subprocess.run(
            [bash, str(TOOLS / "dev.sh"), sys.executable, "-B", "-c",
             "import os,shutil; print(os.environ['VEYRA_TRUSTED_BASH']); "
             "print(os.environ['JAVA_HOME']); print(shutil.which('bash'))"],
            check=True, text=True, capture_output=True,
        )
        trusted, java_home, resolved_bash = result.stdout.splitlines()[:3]
        self.assertTrue(Path(trusted).is_file() or shutil.which(trusted))
        if sys.platform.startswith("win"):
            self.assertNotIn("system32", trusted.lower(), "trusted bash must be existing Git Bash, not WSL")
            self.assertEqual(Path(trusted).resolve(), Path(resolved_bash).resolve(),
                             "Native subprocess PATH must select the trusted Git Bash")
        self.assertTrue((Path(java_home) / "bin" / ("java.exe" if sys.platform.startswith("win") else "java")).is_file())

    def test_shell_entrypoints_are_executable(self) -> None:
        for path in TOOLS.glob("*.sh"):
            with self.subTest(script=path.name):
                result = subprocess.run(
                    ["git", "-C", str(ROOT), "ls-files", "-s", str(path.relative_to(ROOT))],
                    check=True, text=True, capture_output=True,
                )
                if not result.stdout.startswith("100755 "):
                    change = subprocess.run(
                        ["git", "-C", str(ROOT), "diff", "--summary", "--",
                         str(path.relative_to(ROOT))],
                        check=True, text=True, capture_output=True,
                    ).stdout
                    self.assertIn("mode change 100644 => 100755", change,
                                  path.name + " must be executable in the index or be an explicit staged-for-review mode diff")
                if not sys.platform.startswith("win"):
                    self.assertTrue(os.access(path, os.X_OK), path.name + " must have an executable mode")

    def test_desktop_tests_continue_after_shared_failure(self) -> None:
        ci = (TOOLS / "ci.sh").read_text()
        self.assertIn("--continue :shared:jvmTest :composeApp:desktopTest", ci)

    def test_jdk21_selection_is_shared_not_duplicated(self) -> None:
        self.assertIn("JAVA_HOME_21_X64", (TOOLS / "env.sh").read_text())
        self.assertNotIn("JAVA_HOME_21", (TOOLS / "ci.sh").read_text())

    def test_retention_asset_lists_are_canonical_for_windows_bash(self) -> None:
        script = (TOOLS / "build-retention.sh").read_text()
        self.assertIn("print(path.as_posix())", script)
        self.assertIn("value=\"${value%$'\\r'}\"", script)

    def test_windows_crlf_asset_lists_are_stripped(self) -> None:
        with tempfile.TemporaryDirectory(prefix="crlf-list-", dir=TMP) as directory:
            listing = Path(directory) / "assets.list"
            listing.write_bytes(b"first.bin\r\nsecond file.bin\r\n")
            snippet = 'while IFS= read -r value; do value="${value%$\'\\r\'}"; printf "<%s>\\n" "$value"; done < "$1"'
            result = subprocess.run([BASH, "-c", snippet, "_", listing.as_posix()],
                                    check=True, text=True, capture_output=True)
            self.assertEqual(result.stdout, "<first.bin>\n<second file.bin>\n")

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
            [BASH, str(TOOLS / "dev.sh"), sys.executable, "-B", "-c", command],
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
            [BASH, str(TOOLS / "dev.sh"), sys.executable, "-B", "-c",
             "import os; assert 'ANDROID_PREFS_ROOT' not in os.environ; "
             "assert 'ANDROID_SDK_HOME' not in os.environ; "
             "assert os.environ['ANDROID_USER_HOME'].endswith('/.work/android/user')"],
            env=environment, text=True, capture_output=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_arguments_with_spaces_survive_launcher(self) -> None:
        result = subprocess.run(
            [BASH, str(TOOLS / "dev.sh"), sys.executable, "-B", "-c",
             "import sys; print(repr(sys.argv[1:]))", "a b", "Arabic ڤيرا", "--literal"],
            check=True, text=True, capture_output=True,
        )
        self.assertEqual(result.stdout.strip(), repr(["a b", "Arabic ڤيرا", "--literal"]))

    @unittest.skipUnless(sys.platform.startswith("linux"), "Linux guarded display context")
    def test_owned_display_authentication_survives_nested_scoped_wrappers(self) -> None:
        with tempfile.TemporaryDirectory(prefix="display-env-test-", dir=TMP) as directory:
            root = Path(directory)
            scripts = root / "scripts"
            scripts.mkdir()
            for name in ("env.sh", "dev.sh"):
                shutil.copy2(TOOLS / name, scripts / name)
            runtime = root / ".work/runtime"
            runtime.mkdir(parents=True)
            authority = runtime / "xauth-fixture1234"
            authority.write_bytes(b"authored file-validation fixture, not an X11 credential")
            authority.chmod(0o600)
            environment = dict(os.environ, VEYRA_DISPLAY_AUTHORITY=str(authority),
                               XAUTHORITY=str(authority), DISPLAY="127.0.0.1:12345",
                               VEYRA_DISPLAY_MOUNT_NAMESPACE=os.readlink("/proc/self/ns/mnt"))
            result = subprocess.run(
                [BASH, str(scripts / "dev.sh"), BASH, str(scripts / "dev.sh"),
                 sys.executable, "-B", "-c", "import os;print(os.environ['XAUTHORITY'])"],
                env=environment, text=True, capture_output=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), str(authority))

    @unittest.skipUnless(sys.platform.startswith("linux"), "Linux guarded display context")
    def test_untrusted_or_stale_display_authentication_context_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="display-env-test-", dir=TMP) as directory:
            root = Path(directory)
            scripts = root / "scripts"
            scripts.mkdir()
            for name in ("env.sh", "dev.sh"):
                shutil.copy2(TOOLS / name, scripts / name)
            runtime = root / ".work/runtime"
            runtime.mkdir(parents=True)
            authority = runtime / "xauth-fixture1234"
            authority.write_bytes(b"authored file-validation fixture")
            authority.chmod(0o600)
            environment = dict(os.environ, VEYRA_DISPLAY_AUTHORITY=str(authority),
                               XAUTHORITY=str(authority), DISPLAY="127.0.0.1:12345",
                               VEYRA_DISPLAY_MOUNT_NAMESPACE=os.readlink("/proc/self/ns/mnt"))
            cases = [
                {"VEYRA_DISPLAY_MOUNT_NAMESPACE": "not-the-current-namespace"},
                {"XAUTHORITY": str(runtime / "other")},
                {"DISPLAY": ":12345"}, {"DISPLAY": "127.0.0.1:65535"},
                {"VEYRA_DISPLAY_AUTHORITY": str(root / "xauth-fixture1234"),
                 "XAUTHORITY": str(root / "xauth-fixture1234")},
            ]
            for changed in cases:
                with self.subTest(changed=sorted(changed)):
                    result = subprocess.run([BASH, str(scripts / "dev.sh"), "true"],
                                            env=dict(environment, **changed), text=True, capture_output=True)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("invalid owned display", result.stderr)
            for modification in ("permissions", "empty", "symlink", "hardlink"):
                with self.subTest(modification=modification):
                    authority.unlink()
                    authority.write_bytes(b"authored file-validation fixture")
                    authority.chmod(0o600)
                    if modification == "permissions":
                        authority.chmod(0o644)
                    elif modification == "empty":
                        authority.write_bytes(b"")
                    elif modification == "symlink":
                        target = runtime / "target"
                        target.write_bytes(b"fixture")
                        authority.unlink()
                        authority.symlink_to(target)
                    else:
                        os.link(authority, runtime / "second-link")
                    result = subprocess.run([BASH, str(scripts / "dev.sh"), "true"],
                                            env=environment, text=True, capture_output=True)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("invalid owned display", result.stderr)

    def test_unmarked_inherited_xauthority_is_not_preserved(self) -> None:
        environment = dict(os.environ, XAUTHORITY="/not-an-owned-display-cookie")
        environment.pop("VEYRA_DISPLAY_AUTHORITY", None)
        result = subprocess.run(
            [BASH, str(TOOLS / "dev.sh"), sys.executable, "-B", "-c",
             "import os; print(os.environ['XAUTHORITY'])"],
            env=environment, text=True, capture_output=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(Path(result.stdout.strip()).resolve(), ROOT / ".work/runtime/Xauthority")

    @unittest.skipUnless(sys.platform.startswith("linux"), "Linux namespace identity check")
    def test_namespace_mounts_refused_in_callers_namespace(self) -> None:
        namespace = os.readlink("/proc/self/ns/mnt")
        before = Path("/proc/self/mountinfo").read_bytes()
        result = subprocess.run(
            [BASH, str(TOOLS / "linux-namespace.sh"), "--inside", namespace,
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
                [BASH, str(scripts / "dev.sh"), "true"], text=True, capture_output=True,
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
            [BASH, str(TOOLS / "dev.sh"), sys.executable, "-B", "-c", probe],
            check=True, text=True, capture_output=True,
        )
        self.assertIn("parity: PASS", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
