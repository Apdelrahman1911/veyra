#!/usr/bin/env python3
"""Authored Windows-toolchain bootstrap regressions; no Windows, compiler or network runs."""
from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "veyra_windows_toolchain", Path(__file__).with_name("windows_toolchain.py"))
TOOLCHAIN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOLCHAIN)

HOST_X64 = r"C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Tools\MSVC\14.44.35207\bin\Hostx64\x64"
HOST_X86 = HOST_X64.replace("Hostx64", "Hostx86")
GIT_BASH = r"C:\Program Files\Git\bin"
JDK_BIN = r"C:\hostedtoolcache\windows\Java_Temurin-Hotspot_jdk\21.0.8-9\x64\bin"


def authored_environment(**overrides):
    environment = {
        "VSCMD_VER": "17.14.35207.1",
        "VSCMD_ARG_TGT_ARCH": "x64",
        "VSCMD_ARG_HOST_ARCH": "x64",
        "VCToolsInstallDir": HOST_X64.rsplit("\\bin\\", 1)[0] + "\\",
        "INCLUDE": r"C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Tools\MSVC\14.44.35207\include",
        "LIB": r"C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Tools\MSVC\14.44.35207\lib\x64",
        "PATH": HOST_X64,
    }
    environment.update(overrides)
    return environment


def authored_which(name, *, path=None):
    if name not in TOOLCHAIN.MSVC_TOOLS or not path:
        return None
    entries = path.split(";")
    for entry in entries:
        if "hostx64" in entry.casefold():
            return entry + "\\" + name
    for entry in entries:
        if "hostx86" in entry.casefold():
            return entry + "\\" + name
    return None


class AuthoredCleanupFailure(RuntimeError):
    cleanup_complete = False


class AuthoredVisualStudio:
    """Authored command seam: records calls and returns bounded fake output."""

    def __init__(self, install=r"C:\Program Files\Microsoft Visual Studio\2022\Veyra Ünïcode"):
        self.install = install
        self.vswhere = r"C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe"
        self.vsdevcmd = install + r"\Common7\Tools\VsDevCmd.bat"
        self.commands: list[tuple[list[str], dict[str, str], float]] = []
        self.capture_environments: list[dict[str, str]] = []
        self.batch_contents: list[bytes] = []
        self.setup = authored_environment()
        self.vswhere_output: bytes | None = None
        self.has_vsdevcmd = True
        self.fail_capture_after: int | None = None
        self.failure: Exception = RuntimeError("authored-capture-secret")

    def is_file(self, value):
        folded = str(value).casefold()
        expected = {self.vswhere.casefold()}
        if self.has_vsdevcmd:
            expected.add(self.vsdevcmd.casefold())
        return folded in expected

    def capture(self, command, environment, timeout):
        self.commands.append((list(command), dict(environment), timeout))
        if self.fail_capture_after is not None and len(self.commands) > self.fail_capture_after:
            raise self.failure
        if str(command[0]).casefold() == self.vswhere.casefold():
            if self.vswhere_output is not None:
                return self.vswhere_output
            return (self.install + "\r\n").encode("utf-8")
        batch = Path(command[-1])
        self.batch_contents.append(batch.read_bytes())
        self.capture_environments.append(dict(environment))
        payload = "\r\n".join(key + "=" + value for key, value in self.setup.items()) + "\r\n"
        return payload.encode("utf-16-le")


class WindowsToolchainTest(unittest.TestCase):
    def setUp(self):
        base = Path(__file__).resolve().parents[1] / ".work/desktop-native/tests"
        base.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix="authored-toolchain-", dir=base)
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.scratch = self.root / "scratch with spaces" / "üñïcode"

    def inherited(self):
        return {
            "ProgramFiles(x86)": r"C:\Program Files (x86)",
            "ComSpec": r"C:\Windows\System32\cmd.exe",
            "HOME": str(self.root / "home"),
            "USERPROFILE": str(self.root / "home"),
            "TEMP": str(self.root / "temp"),
            "TMP": str(self.root / "temp"),
            "GRADLE_USER_HOME": str(self.root / "gradle"),
            "PIP_CACHE_DIR": str(self.root / "cache" / "pip"),
            "XDG_CACHE_HOME": str(self.root / "cache"),
            "PATH": r"C:\Windows\System32" + ";" + GIT_BASH + ";" + JDK_BIN,
            "VEYRA_TRUSTED_BASH": GIT_BASH + r"\bash.exe",
            "PATHEXT": ".COM;.EXE;.BAT;.CMD",
            "VEYRA_SCOPED_ENV": "1",
            "SECRET_TOKEN": "authored-not-a-transcript",
        }

    def fail_capture(self, *args, **kwargs):
        self.fail("No child capture is expected")

    def fail_is_file(self, *args, **kwargs):
        self.fail("No file probe is expected")

    def fail_which(self, *args, **kwargs):
        self.fail("No tool lookup is expected")

    def test_non_windows_and_existing_matching_x64_environments_are_not_changed(self):
        inherited = self.inherited()
        original = dict(inherited)
        non_windows = TOOLCHAIN.child_environment(
            inherited, scratch=self.scratch, windows=False, capture=self.fail_capture,
            is_file=self.fail_is_file, which=self.fail_which)
        self.assertEqual(original, inherited)
        self.assertEqual(inherited, non_windows)
        self.assertIsNot(inherited, non_windows)
        self.assertFalse(self.scratch.exists())

        matching = dict(inherited, PATH=HOST_X64 + ";" + GIT_BASH,
                        VSCMD_VER="17.14.35207.1",
                        VSCMD_ARG_TGT_ARCH="x64", VSCMD_ARG_HOST_ARCH="x64",
                        INCLUDE=r"C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Tools\MSVC\14.44.35207\include",
                        LIB=r"C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Tools\MSVC\14.44.35207\lib\x64")
        original = dict(matching)
        preserved = TOOLCHAIN.child_environment(
            matching, scratch=self.scratch, windows=True, capture=self.fail_capture,
            is_file=self.fail_is_file, which=authored_which)
        self.assertEqual(original, matching)
        self.assertEqual(matching, preserved)
        self.assertFalse(self.scratch.exists())

    def test_mismatched_x86_environment_is_reinitialized_for_x64(self):
        inherited = self.inherited()
        inherited.update(PATH=HOST_X86, VSCMD_VER="17.14.35207.1",
                         VSCMD_ARG_TGT_ARCH="x86", VSCMD_ARG_HOST_ARCH="x86")
        original = dict(inherited)
        fake = AuthoredVisualStudio()
        result = TOOLCHAIN.child_environment(
            inherited, scratch=self.scratch, windows=True, capture=fake.capture,
            is_file=fake.is_file, which=authored_which)
        self.assertEqual(original, inherited)
        self.assertEqual(2, len(fake.commands))
        self.assertEqual(TOOLCHAIN.VSDEVCMD_TIMEOUT_SECONDS, fake.commands[1][2])
        self.assertEqual("x64", result["VSCMD_ARG_TGT_ARCH"])
        self.assertEqual(GIT_BASH, result["PATH"].split(";")[0])
        self.assertEqual(HOST_X64, result["PATH"].split(";")[1])

    def test_compiler_bins_without_sdk_include_library_are_reinitialized(self):
        base = dict(self.inherited(), PATH=HOST_X64 + ";" + GIT_BASH,
                    VSCMD_VER="17.14.35207.1",
                    VSCMD_ARG_TGT_ARCH="x64", VSCMD_ARG_HOST_ARCH="x64",
                    INCLUDE=r"C:\authored\include", LIB=r"C:\authored\lib\x64")
        for missing in ("INCLUDE", "LIB"):
            with self.subTest(missing=missing):
                inherited = dict(base)
                inherited.pop(missing)
                fake = AuthoredVisualStudio()
                result = TOOLCHAIN.child_environment(
                    inherited, scratch=self.scratch, windows=True, capture=fake.capture,
                    is_file=fake.is_file, which=authored_which)
                self.assertEqual(2, len(fake.commands))
                self.assertEqual(fake.setup["INCLUDE"], result["INCLUDE"])
                self.assertEqual(fake.setup["LIB"], result["LIB"])

    def test_existing_vswhere_and_vsdevcmd_are_used_with_scoped_private_environment(self):
        inherited = self.inherited()
        original = dict(inherited)
        process_environment = dict(os.environ)
        fake = AuthoredVisualStudio()
        fake.setup = authored_environment(
            HOME=r"C:\Users\unscoped", TEMP=r"C:\Windows\Temp", TMP=r"C:\Windows\Temp",
            GRADLE_USER_HOME=r"C:\Users\unscoped\.gradle", PIP_CACHE_DIR=r"C:\Users\unscoped\pip",
            XDG_CACHE_HOME=r"C:\Users\unscoped\cache", VEYRA_SCOPED_ENV="0")
        result = TOOLCHAIN.child_environment(
            inherited, scratch=self.scratch, windows=True, capture=fake.capture,
            is_file=fake.is_file, which=authored_which)

        self.assertEqual(original, inherited)
        self.assertEqual(process_environment, dict(os.environ))
        self.assertEqual(2, len(fake.commands))

        locator = fake.commands[0][0]
        self.assertEqual(fake.vswhere, locator[0])
        self.assertEqual(["-latest", "-products", "*", "-requires", TOOLCHAIN.VSWHERE_COMPONENT,
                          "-property", "installationPath", "-utf8"], locator[1:])
        self.assertEqual(TOOLCHAIN.VSWHERE_TIMEOUT_SECONDS, fake.commands[0][2])

        command = fake.commands[1][0]
        self.assertEqual([inherited["ComSpec"], "/u", "/d", "/c", "call"], command[:-1])
        batch = Path(command[-1])
        self.assertTrue(batch.is_relative_to(self.scratch))
        self.assertIn(b'call "%VEYRA_VSDEVCMD_PATH%" -no_logo -arch=amd64 -host_arch=amd64 >nul',
                      fake.batch_contents[0])
        self.assertIn('"' + str(batch) + '"', subprocess.list2cmdline(command))
        self.assertEqual(fake.vsdevcmd, fake.capture_environments[0]["VEYRA_VSDEVCMD_PATH"])
        self.assertFalse(batch.exists())
        self.assertEqual([], list(self.scratch.iterdir()))

        self.assertEqual("x64", result["VSCMD_ARG_TGT_ARCH"])
        self.assertEqual("17.14.35207.1", result["VSCMD_VER"])
        self.assertIn("include", result["INCLUDE"])
        self.assertEqual(inherited["HOME"], result["HOME"])
        self.assertEqual(inherited["USERPROFILE"], result["USERPROFILE"])
        self.assertEqual(inherited["TEMP"], result["TEMP"])
        self.assertEqual(inherited["TMP"], result["TMP"])
        self.assertEqual(inherited["GRADLE_USER_HOME"], result["GRADLE_USER_HOME"])
        self.assertEqual(inherited["PIP_CACHE_DIR"], result["PIP_CACHE_DIR"])
        self.assertEqual(inherited["XDG_CACHE_HOME"], result["XDG_CACHE_HOME"])
        self.assertEqual("1", result["VEYRA_SCOPED_ENV"])
        self.assertEqual(inherited["VEYRA_TRUSTED_BASH"], result["VEYRA_TRUSTED_BASH"])
        self.assertNotIn("VEYRA_VSDEVCMD_PATH", result)
        entries = result["PATH"].split(";")
        self.assertEqual(GIT_BASH, entries[0])
        self.assertEqual(HOST_X64, entries[1])
        self.assertEqual(r"C:\Windows\System32", entries[2])
        self.assertIn(JDK_BIN, entries)
        self.assertEqual(1, sum(1 for entry in entries if entry.casefold() == GIT_BASH.casefold()))

    def test_default_capture_creates_cwd_before_the_first_locator_child(self):
        fake = AuthoredVisualStudio()
        self.assertFalse(self.scratch.exists())

        def capture(command, *, cwd, env, timeout, max_output_bytes):
            self.assertEqual(self.scratch, cwd)
            self.assertTrue(cwd.is_dir())
            self.assertEqual(TOOLCHAIN.CAPTURE_MAX_BYTES, max_output_bytes)
            return fake.capture(command, env, timeout)

        with patch("owned_process.capture", side_effect=capture) as owned_capture:
            result = TOOLCHAIN.child_environment(
                self.inherited(), scratch=self.scratch, windows=True,
                is_file=fake.is_file, which=authored_which)
        self.assertEqual(2, owned_capture.call_count)
        self.assertEqual(fake.vswhere, fake.commands[0][0][0])
        self.assertEqual(fake.setup["INCLUDE"], result["INCLUDE"])
        self.assertEqual([], list(self.scratch.iterdir()))

    def test_missing_locator_or_vsdevcmd_is_rejected_without_a_fallback(self):
        missing_locator = self.inherited()
        missing_locator.pop("ProgramFiles(x86)")
        fake = AuthoredVisualStudio()
        with self.assertRaisesRegex(ValueError, "vswhere.exe is required; no toolchain download or fallback is used"):
            TOOLCHAIN.child_environment(missing_locator, scratch=self.scratch, windows=True,
                                        capture=fake.capture, is_file=fake.is_file,
                                        which=authored_which)
        self.assertEqual([], fake.commands)
        self.assertFalse(self.scratch.exists())

        fake = AuthoredVisualStudio()
        fake.vswhere_output = b"\r\n"
        with self.assertRaisesRegex(ValueError, "No existing Visual Studio installation.*no toolchain download or fallback is used"):
            TOOLCHAIN.child_environment(self.inherited(), scratch=self.scratch, windows=True,
                                        capture=fake.capture, is_file=fake.is_file,
                                        which=authored_which)
        self.assertEqual(1, len(fake.commands))

        fake = AuthoredVisualStudio()
        fake.has_vsdevcmd = False
        with self.assertRaisesRegex(ValueError, "VsDevCmd.bat is required; no fallback is used"):
            TOOLCHAIN.child_environment(self.inherited(), scratch=self.scratch, windows=True,
                                        capture=fake.capture, is_file=fake.is_file,
                                        which=authored_which)
        self.assertEqual(1, len(fake.commands))
        self.assertFalse(self.scratch.exists())

    def test_wrong_target_arch_or_tool_architecture_is_rejected_truthfully(self):
        cases = (
            (authored_environment(VSCMD_ARG_TGT_ARCH="x86"), authored_which,
             "target is not x64; no substitute compiler is used"),
            (authored_environment(VSCMD_ARG_HOST_ARCH="arm64"), authored_which,
             "host is not x64; no substitute compiler is used"),
            (authored_environment(INCLUDE=""), authored_which,
             "include/library \\(SDK\\) environment is required; no substitute is used"),
            ({key: value for key, value in authored_environment().items() if key != "LIB"}, authored_which,
             "include/library \\(SDK\\) environment is required; no substitute is used"),
            (authored_environment(PATH=HOST_X86), authored_which,
             "x64 MSVC compiler tools are required; no substitute compiler is used"),
            (authored_environment(), lambda name, *, path=None: None,
             "x64 MSVC compiler tools are required; no substitute compiler is used"),
        )
        for setup, which, message in cases:
            with self.subTest(message=message):
                fake = AuthoredVisualStudio()
                fake.setup = setup
                with self.assertRaisesRegex(ValueError, message):
                    TOOLCHAIN.child_environment(self.inherited(), scratch=self.scratch, windows=True,
                                                capture=fake.capture, is_file=fake.is_file, which=which)
                self.assertFalse(any(self.scratch.glob("veyra-vsdevcmd-*.cmd")))

    def test_capture_failure_does_not_leak_output_and_removes_the_private_batch(self):
        process_environment = dict(os.environ)
        fake = AuthoredVisualStudio()
        fake.fail_capture_after = 1
        with self.assertRaises(ValueError) as raised:
            TOOLCHAIN.child_environment(self.inherited(), scratch=self.scratch, windows=True,
                                        capture=fake.capture, is_file=fake.is_file,
                                        which=authored_which)
        self.assertIn("could not be initialized; no fallback compiler is used", str(raised.exception))
        self.assertNotIn("authored-capture-secret", str(raised.exception))
        batch = Path(fake.commands[-1][0][-1])
        self.assertFalse(batch.exists())
        self.assertEqual(process_environment, dict(os.environ))

        fake = AuthoredVisualStudio()
        fake.fail_capture_after = 0
        with self.assertRaises(ValueError) as raised:
            TOOLCHAIN.child_environment(self.inherited(), scratch=self.scratch, windows=True,
                                        capture=fake.capture, is_file=fake.is_file,
                                        which=authored_which)
        self.assertIn("could not be queried; no fallback is used", str(raised.exception))
        self.assertNotIn("authored-capture-secret", str(raised.exception))
        self.assertEqual(process_environment, dict(os.environ))

        fake = AuthoredVisualStudio()
        fake.fail_capture_after = 1
        fake.failure = AuthoredCleanupFailure("authored-capture-secret")
        with self.assertRaises(ValueError) as raised:
            TOOLCHAIN.child_environment(self.inherited(), scratch=self.scratch, windows=True,
                                        capture=fake.capture, is_file=fake.is_file,
                                        which=authored_which)
        self.assertIn("cleanup was not proven; no native command was started", str(raised.exception))
        self.assertNotIn("authored-capture-secret", str(raised.exception))
        self.assertFalse(Path(fake.commands[-1][0][-1]).exists())
        self.assertEqual(process_environment, dict(os.environ))

    def test_environment_parser_handles_unicode_noise_and_utf16_bom(self):
        payload = ("authored noise\r\n"
                   "=C:=C:\\work\r\n"
                   "ProgramFiles(x86)=C:\\Program Files (x86)\r\n"
                   "UNICODE=C:\\工具\\路径\r\n"
                   "Path=C:\\one;C:\\two\r\n")
        parsed = TOOLCHAIN._parse_environment(payload.encode("utf-16-le"))
        self.assertEqual("C:\\Program Files (x86)", parsed["ProgramFiles(x86)"])
        self.assertEqual("C:\\工具\\路径", parsed["UNICODE"])
        self.assertEqual("C:\\one;C:\\two", parsed["Path"])
        self.assertNotIn("", parsed)
        with_bom = TOOLCHAIN._parse_environment(b"\xff\xfe" + payload.encode("utf-16-le"))
        self.assertEqual(parsed, with_bom)

    def test_bootstrap_has_no_download_or_global_environment_file_write(self):
        source = Path(TOOLCHAIN.__file__).read_text(encoding="utf-8")
        for forbidden in ("GITHUB_ENV", "GITHUB_PATH", "http://", "https://",
                          "urllib.request", "requests.", "apt-get", "choco", "pacman"):
            self.assertNotIn(forbidden, source)


if __name__ == "__main__":
    unittest.main()
