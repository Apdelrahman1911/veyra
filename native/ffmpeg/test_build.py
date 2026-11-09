#!/usr/bin/env python3
"""Authored source-scope/profile regressions; no compiler or network runs."""
from __future__ import annotations

import importlib.util
import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("veyra_ffmpeg_build", Path(__file__).with_name("build.py"))
BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD)


class ProductionFfmpegBuildTest(unittest.TestCase):
    def setUp(self):
        work = BUILD.writable(BUILD.ROOT / ".work/native-ffmpeg/tests")
        work.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="authored-", dir=work)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.work = self.root / ".work"
        self.work.mkdir()

    def test_inner_compile_failure_reports_the_actual_log_without_leaking_or_masking(self):
        log = self.work / "build.log"
        original = (b"authored.c:9: error: authored compiler discriminator\n"
                    b"error: https://example.invalid/?token=authored-private\n")
        log.write_bytes(original)
        failure = BUILD.owned_process.OwnedProcessError("command_failed", returncode=1,
                                                       cleanup_complete=True)
        console = io.StringIO()
        with patch.object(BUILD, "ROOT", self.root), \
                patch.object(BUILD.platform, "system", return_value="Linux"), \
                patch.object(BUILD.owned_process, "run", side_effect=failure), \
                contextlib.redirect_stderr(console):
            with self.assertRaises(BUILD.owned_process.OwnedProcessError) as caught:
                BUILD.run(["authored-not-executed"], self.work, {}, 10, log)
        self.assertIs(failure, caught.exception)
        record = json.loads((self.work / "verification/native-build-diagnostic.json").read_text())
        self.assertIn("authored compiler discriminator", "\n".join(record["lines"]))
        self.assertNotIn("authored-private", json.dumps(record) + console.getvalue())
        self.assertEqual(".work/build.log", record["log"])
        self.assertTrue(record["cleanupComplete"])
        self.assertEqual(original, log.read_bytes())

    def test_configure_failure_reports_the_explicit_compiler_probe_log(self):
        log = self.work / "build.log"
        log.write_text("authored failed configure summary\n")
        probe = self.work / "ffbuild/config.log"
        probe.parent.mkdir()
        probe.write_text("check_cc authored_probe\ntest_cc\nprobe.c:1: error: authored compiler-probe discriminator\n")
        failure = BUILD.owned_process.OwnedProcessError("command_failed", returncode=1,
                                                       cleanup_complete=True)
        with patch.object(BUILD, "ROOT", self.root), \
                patch.object(BUILD.platform, "system", return_value="Linux"), \
                patch.object(BUILD.owned_process, "run", side_effect=failure), \
                contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(BUILD.owned_process.OwnedProcessError) as caught:
                BUILD.run(["bash", "authored/configure"], self.work, {}, 10, log)
        self.assertIs(failure, caught.exception)
        record = json.loads((self.work / "verification/native-build-diagnostic.json").read_text())
        self.assertEqual(".work/ffbuild/config.log", record["log"])
        self.assertIn("authored compiler-probe discriminator", "\n".join(record["lines"]))

    def test_windows_commands_pin_path_resolution_before_createprocess(self):
        environment = {"PATH": "authored trusted tool path"}
        for name, resolved in (("bash", "C:/Program Files/Git/bin/bash.exe"),
                               ("make", "C:/existing/tools/make.exe")):
            with self.subTest(tool=name), patch.object(BUILD.platform, "system", return_value="Windows"), \
                    patch.object(BUILD.shutil, "which", return_value=resolved) as which, \
                    patch.object(BUILD.owned_process, "run") as run:
                BUILD.run([name, "authored argument with spaces"], self.work, environment, 10,
                          self.work / "resolved.log")
                which.assert_called_once_with(name, path=environment["PATH"])
                self.assertEqual([resolved, "authored argument with spaces"], run.call_args.args[0])
                self.assertIs(environment, run.call_args.kwargs["env"])

    def test_windows_missing_resolved_tool_never_launches_a_system_fallback(self):
        with patch.object(BUILD.platform, "system", return_value="Windows"), \
                patch.object(BUILD.shutil, "which", return_value=None), \
                patch.object(BUILD.owned_process, "run") as run:
            with self.assertRaisesRegex(ValueError, "Existing matching-host command"):
                BUILD.run(["bash", "authored/configure"], self.work, {}, 10, self.work / "missing.log")
            run.assert_not_called()

    def archive(self, entries):
        archive = self.work / "source.tar.gz"
        with tarfile.open(archive, "w:gz") as output:
            for name, kind, mode, body in entries:
                entry = tarfile.TarInfo(name)
                entry.type = kind
                entry.mode = mode
                entry.mtime = 1_700_000_000
                entry.size = len(body) if kind == tarfile.REGTYPE else 0
                if kind in (tarfile.SYMTYPE, tarfile.LNKTYPE):
                    entry.linkname = "../outside"
                output.addfile(entry, io.BytesIO(body) if kind == tarfile.REGTYPE else None)
        return archive

    def completed_output(self):
        """An authored, nonexecuted package; tests pin its own tiny archive explicitly."""
        archive = self.archive([(BUILD.SOURCE_ROOT + "/" + name, tarfile.REGTYPE, 0o600, ("authored " + name).encode())
                                for name in BUILD.LICENSE_FILES])
        for name, value in (("SOURCE_BYTES", archive.stat().st_size), ("SOURCE_SHA256", BUILD.sha256(archive))):
            patcher = patch.object(BUILD, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        base = self.work / "native-ffmpeg"
        output = base / "output/linux-x64"
        output.mkdir(parents=True)
        expected = BUILD.identity("linux-x64", BUILD.host_profile("Linux", "x86_64")[1], base)
        sources = output / "corresponding-source"
        sources.mkdir()
        shutil.copyfile(archive, sources / "FFmpeg-n8.0.1.tar.gz")
        shutil.copyfile(Path(BUILD.__file__), sources / "build.py")
        shutil.copyfile(Path(BUILD.__file__).with_name("README.md"), sources / "README.md")
        shutil.copyfile(BUILD.PROCESS_OWNER, sources / "owned_process.py")
        for filename in BUILD.CONFIGURATION_FILES:
            (sources / filename).write_bytes(b"authored generated config")
        licenses = output / "licenses"
        licenses.mkdir()
        for name in BUILD.LICENSE_FILES:
            (licenses / name).write_text("authored " + name)
        executable = output / "ffmpeg"
        executable.write_bytes(b"authored-not-an-executed-binary")
        executable.chmod(0o700)
        digest = BUILD.sha256(executable)
        BUILD.write_sidecar(output / "ffmpeg.sha256", digest)
        metadata = {**expected, "executable": "ffmpeg", "executableSha256": digest,
                    "executableBytes": executable.stat().st_size,
                    "fileSha256": {relative: BUILD.sha256(output / relative) for relative in BUILD.package_files("ffmpeg")}}
        (output / "provenance.json").write_text(json.dumps(metadata))
        return output, expected, metadata

    def test_profile_is_local_copy_not_network_codec_fallback(self):
        flags = set(BUILD.CONFIGURE)
        self.assertTrue({"--disable-network", "--disable-autodetect", "--disable-gpl", "--disable-nonfree",
                         "--disable-version3", "--disable-shared", "--enable-static", "--enable-ffmpeg",
                         "--enable-protocol=file,crypto,data", "--enable-muxer=mp4"}.issubset(flags))
        self.assertNotIn("--disable-avfilter", flags)
        self.assertFalse(any(flag.startswith(("--enable-decoder=", "--enable-encoder=", "--enable-lib")) for flag in flags))

    def test_windows_make_target_and_sidecar_are_platform_exact(self):
        self.assertEqual("ffmpeg.exe", BUILD.executable_name("windows-x64"))
        self.assertEqual("ffmpeg", BUILD.executable_name("macos-arm64"))
        self.assertEqual("ffmpeg", BUILD.executable_name("linux-x64"))
        path = self.work / "binary.sha256"
        with patch.object(Path, "write_text", side_effect=AssertionError("Text-mode newline translation is not allowed")):
            BUILD.write_sidecar(path, "a" * 64)
        self.assertEqual(b"a" * 64 + b"\n", path.read_bytes())

    def test_release_and_explicit_revision_do_not_depend_on_enclosing_git(self):
        source = self.work / "source"
        source.mkdir()
        release = source / "RELEASE"
        for body in (b"", b"8.0.1", b"8.0.1\r\n", b"8.0.10\n", b"7ac1b27\n"):
            with self.subTest(body=body):
                release.write_bytes(body)
                with self.assertRaisesRegex(ValueError, "RELEASE"):
                    BUILD.require_release(source)
        release.write_bytes(b"8.0.1\n")
        BUILD.require_release(source)
        (source / "VERSION").write_bytes(b"unrelated checkout")
        with self.assertRaisesRegex(ValueError, "VERSION"):
            BUILD.require_release(source)
        self.assertEqual(["make", "-j1", "REVISION=8.0.1", "ffmpeg"], BUILD.make_command("linux-x64", "1"))
        self.assertEqual(["make", "-j2", "REVISION=8.0.1", "ffmpeg.exe"], BUILD.make_command("windows-x64", "2"))
        with self.assertRaises(ValueError):
            BUILD.make_command("linux-x64", "99")
        expected = BUILD.identity("linux-x64", [], self.work)
        self.assertEqual("8.0.1", expected["makeRevision"])
        self.assertEqual(BUILD.sha256(release), expected["sourceReleaseSha256"])

    def test_build_environment_removes_ambient_make_and_revision_injection(self):
        forbidden = ("CFLAGS", "CPPFLAGS", "CXXFLAGS", "LDFLAGS", "CC", "CXX", "LD", "AR", "FFREPORT",
                     "MAKEFLAGS", "GNUMAKEFLAGS", "MFLAGS", "MAKEOVERRIDES", "MAKEFILES",
                     "REVISION", "EXTRA_VERSION", "revision", "git_hash")
        original = {**dict.fromkeys(forbidden, "authored override"), "PATH": "existing tools", "LIB": "existing SDK"}
        environment = BUILD.build_environment("macos-arm64", 1234, original)
        self.assertTrue(all(key not in environment for key in forbidden))
        self.assertTrue(all(key in original for key in forbidden), "Do not mutate the caller's environment")
        self.assertEqual("existing tools", environment["PATH"])
        self.assertEqual("existing SDK", environment["LIB"])
        self.assertEqual("13.0", environment["MACOSX_DEPLOYMENT_TARGET"])
        self.assertEqual("1234", environment["SOURCE_DATE_EPOCH"])

    def test_reported_version_requires_the_complete_pinned_token(self):
        reports = {"-L": "GNU Lesser General Public version 2.1", "-protocols": "Input:\ncrypto\ndata\nfile\n",
                   "-formats": "  E mp4", "-codecs": "", "-buildconf": "--disable-network --disable-gpl"}
        for version, accepted in (("8.0.1", True), ("n8.0.1", True), ("8.0.10", False),
                                  ("8.0.1-unrelated", False), ("7ac1b27", False)):
            with self.subTest(version=version):
                reports["-version"] = "ffmpeg version " + version + " Copyright"
                with patch.object(BUILD, "capture", side_effect=lambda command, *_: reports[command[-1]]):
                    if accepted:
                        BUILD.verify_features(self.work / "never-executed", self.work, {})
                    else:
                        with self.assertRaisesRegex(ValueError, "version"):
                            BUILD.verify_features(self.work / "never-executed", self.work, {})

    def test_host_profiles_do_not_claim_unsupported_architectures(self):
        self.assertEqual("linux-x64", BUILD.host_profile("Linux", "x86_64")[0])
        self.assertEqual("macos-x64", BUILD.host_profile("Darwin", "x86_64")[0])
        self.assertEqual("macos-arm64", BUILD.host_profile("Darwin", "arm64")[0])
        windows = BUILD.host_profile("Windows", "AMD64")
        self.assertEqual("windows-x64", windows[0])
        self.assertIn("--toolchain=msvc", windows[1])
        self.assertIn("--extra-cflags=-MT -guard:cf", windows[1])
        for host in (("Linux", "arm64"), ("Windows", "ARM64"), ("FreeBSD", "x86_64"), ("Darwin", "i386")):
            with self.subTest(host=host), self.assertRaises(ValueError):
                BUILD.host_profile(*host)

    def test_wrong_source_pin_is_refused_before_build(self):
        archive = self.archive([(BUILD.SOURCE_ROOT + "/configure", tarfile.REGTYPE, 0o755, b"authored")])
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            BUILD.require_source(archive)

    def test_all_archive_paths_are_prevalidated(self):
        for bad in ("/absolute", "../outside", BUILD.SOURCE_ROOT + "/../../outside",
                    BUILD.SOURCE_ROOT + "/x\\y", BUILD.SOURCE_ROOT + "/x:y", BUILD.SOURCE_ROOT + "/NUL.txt",
                    BUILD.SOURCE_ROOT + "/bad.", BUILD.SOURCE_ROOT + "/a//b", BUILD.SOURCE_ROOT + "/a/./b"):
            with self.subTest(bad=bad):
                archive = self.archive([(BUILD.SOURCE_ROOT + "/good", tarfile.REGTYPE, 0o600, b"authored"),
                                        (bad, tarfile.REGTYPE, 0o600, b"bad")])
                output = self.work / "extract"
                with self.assertRaises(ValueError):
                    BUILD.extract_source(archive, output, self.root)
                self.assertEqual([], list(output.iterdir()))
                output.rmdir()

    def test_links_special_privileged_and_duplicate_entries_are_rejected(self):
        first = (BUILD.SOURCE_ROOT + "/file", tarfile.REGTYPE, 0o600, b"ok")
        for kind, mode, name in ((tarfile.SYMTYPE, 0o777, "link"), (tarfile.LNKTYPE, 0o600, "hard"),
                                 (tarfile.FIFOTYPE, 0o600, "pipe"), (tarfile.REGTYPE, 0o4755, "privileged"),
                                 (tarfile.REGTYPE, 0o600, "file")):
            with self.subTest(kind=kind, mode=mode, name=name):
                archive = self.archive([first, (BUILD.SOURCE_ROOT + "/" + name, kind, mode, b"bad")])
                output = self.work / "extract"
                with self.assertRaises(ValueError):
                    BUILD.extract_source(archive, output, self.root)
                self.assertEqual([], list(output.iterdir()))
                output.rmdir()

    def test_source_extract_is_private_and_preserves_only_exec_permission(self):
        archive = self.archive([(BUILD.SOURCE_ROOT + "/configure", tarfile.REGTYPE, 0o755, b"authored"),
                                (BUILD.SOURCE_ROOT + "/COPYING", tarfile.REGTYPE, 0o644, b"license")])
        source, epoch = BUILD.extract_source(archive, self.work / "extract", self.root)
        self.assertEqual(1_700_000_000, epoch)
        self.assertEqual(b"authored", (source / "configure").read_bytes())
        self.assertEqual(b"license", (source / "COPYING").read_bytes())
        if os.name != "nt":
            self.assertEqual(0o700, (source / "configure").stat().st_mode & 0o777)
            self.assertEqual(0o600, (source / "COPYING").stat().st_mode & 0o777)

    def test_case_insensitive_and_expansion_bounds(self):
        archive = self.archive([(BUILD.SOURCE_ROOT + "/File", tarfile.REGTYPE, 0o600, b"ok"),
                                (BUILD.SOURCE_ROOT + "/file", tarfile.REGTYPE, 0o600, b"ok")])
        with self.assertRaises(ValueError):
            BUILD.extract_source(archive, self.work / "case", self.root, insensitive=True)
        with patch.object(BUILD, "MAX_SOURCE_BYTES", 3), self.assertRaises(ValueError):
            BUILD.extract_source(archive, self.work / "bytes", self.root)
        with patch.object(BUILD, "MAX_SOURCE_ENTRIES", 1), self.assertRaises(ValueError):
            BUILD.extract_source(archive, self.work / "count", self.root)

    def test_output_scope_and_empty_destination_are_enforced(self):
        archive = self.archive([(BUILD.SOURCE_ROOT + "/file", tarfile.REGTYPE, 0o600, b"ok")])
        with self.assertRaises(ValueError):
            BUILD.extract_source(archive, self.root / "outside-work", self.root)
        with self.assertRaises(ValueError):
            BUILD.owned(self.root.parent / "outside", self.root)
        destination = self.work / "existing"
        destination.mkdir()
        with self.assertRaises(FileExistsError):
            BUILD.extract_source(archive, destination, self.root)

    @unittest.skipIf(os.name == "nt", "Symlink scope regression needs available unprivileged symlinks")
    def test_escaping_ancestor_is_refused_before_directory_creation(self):
        archive = self.archive([(BUILD.SOURCE_ROOT + "/file", tarfile.REGTYPE, 0o600, b"ok")])
        (self.work / "escape").symlink_to(self.root.parent, target_is_directory=True)
        with self.assertRaises(ValueError):
            BUILD.extract_source(archive, self.work / "escape/unexpected", self.root)

    def test_redirects_and_unscoped_invocation_fail_closed(self):
        with self.assertRaises(ValueError):
            BUILD.NoRedirect().redirect_request(None, None, 302, "Found", {}, "http://example.invalid/")
        with patch.dict(os.environ, {}, clear=True), self.assertRaisesRegex(ValueError, "scripts/dev.sh"):
            BUILD.build(None, "1")

    def test_valid_output_reuse_does_not_run_compilers_network_or_executable(self):
        output, expected, _ = self.completed_output()
        self.assertEqual(output / "ffmpeg", BUILD.verify_output(output, expected, self.root))
        with patch.object(BUILD, "ROOT", self.root), patch.object(BUILD.platform, "system", return_value="Linux"), \
                patch.object(BUILD.platform, "machine", return_value="x86_64"), patch.object(BUILD.shutil, "which") as tools, \
                patch.object(BUILD, "download_source") as download, patch.object(BUILD.owned_process, "run") as run, \
                patch.dict(os.environ, {"VEYRA_SCOPED_ENV": "1"}):
            self.assertEqual(output / "ffmpeg", BUILD.build(None, "1"))
            self.assertEqual(output / "ffmpeg", BUILD.build(None, "1", verify_only=True))
            tools.assert_not_called()
            download.assert_not_called()
            run.assert_not_called()

    def test_stale_profile_or_recipe_fails_without_overwriting_output(self):
        output, expected, metadata = self.completed_output()
        original = (output / "ffmpeg").read_bytes()
        for key, changed in (("target", "windows-x64"), ("profile", "changed"), ("recipeSha256", "0" * 64),
                             ("recipeReadmeSha256", "0" * 64), ("processOwnerSha256", "0" * 64),
                             ("sourceCommit", "0" * 40), ("configure", []), ("makeRevision", "wrong"),
                             ("sourceReleaseSha256", "0" * 64)):
            with self.subTest(key=key):
                (output / "provenance.json").write_text(json.dumps({**metadata, key: changed}))
                with self.assertRaises(ValueError):
                    BUILD.verify_output(output, expected, self.root)
                self.assertEqual(original, (output / "ffmpeg").read_bytes())

    def test_changed_binary_sidecar_or_config_refuses_reuse(self):
        output, expected, _ = self.completed_output()
        for name in ("ffmpeg", "ffmpeg.sha256", "corresponding-source/config.h", "corresponding-source/build.py",
                     "corresponding-source/owned_process.py"):
            with self.subTest(name=name):
                path = output / name
                original = path.read_bytes()
                path.write_bytes(original + b"changed")
                with self.assertRaises(ValueError):
                    BUILD.verify_output(output, expected, self.root)
                self.assertTrue(path.read_bytes().endswith(b"changed"))
                path.write_bytes(original)

    def test_mutable_receipt_cannot_replace_official_notice_or_source_archive(self):
        output, expected, metadata = self.completed_output()
        for name in ("licenses/COPYING.LGPLv2.1", "corresponding-source/FFmpeg-n8.0.1.tar.gz"):
            with self.subTest(name=name):
                path = output / name
                original = path.read_bytes()
                path.write_bytes(original + b"changed")
                original_hash = metadata["fileSha256"][name]
                metadata["fileSha256"][name] = BUILD.sha256(path)
                (output / "provenance.json").write_text(json.dumps(metadata))
                with self.assertRaises(ValueError):
                    BUILD.verify_output(output, expected, self.root)
                path.write_bytes(original)
                metadata["fileSha256"][name] = original_hash

    def test_extra_output_entry_or_missing_file_fails_closed(self):
        output, expected, _ = self.completed_output()
        extra = output / "unexpected-library.so"
        extra.write_bytes(b"authored extra")
        with self.assertRaises(ValueError):
            BUILD.verify_output(output, expected, self.root)
        extra.unlink()
        (output / "corresponding-source/config.h").unlink()
        with self.assertRaises(ValueError):
            BUILD.verify_output(output, expected, self.root)

    def test_verify_only_requires_an_existing_complete_output(self):
        with self.assertRaises(ValueError):
            BUILD.verify_output(self.work / "missing", {}, self.root)

    def test_feature_report_uses_bounded_owned_capture(self):
        environment = {"AUTHORED": "fixture"}
        with patch.object(BUILD.owned_process, "capture", return_value=b"authored report") as capture:
            self.assertEqual("authored report", BUILD.capture(["authored-tool", "-version"], self.work, environment, 4))
        capture.assert_called_once_with(["authored-tool", "-version"], cwd=self.work, env=environment,
                                        timeout=4, max_output_bytes=1024 * 1024)

    def test_recipe_preserves_uncertain_owner_failure_and_log(self):
        log = self.work / "build.log"
        log.write_bytes(b"authored retained log")
        failure = BUILD.owned_process.OwnedProcessError("cleanup_failed", cleanup_complete=False)
        with patch.object(BUILD, "ROOT", self.root), \
                patch.object(BUILD.platform, "system", return_value="Linux"), \
                patch.object(BUILD.owned_process, "run", side_effect=failure) as run, \
                contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(BUILD.owned_process.OwnedProcessError) as raised:
                BUILD.run(["authored-tool"], self.work, {}, 5, log)
        self.assertIs(failure, raised.exception)
        self.assertFalse(raised.exception.cleanup_complete)
        run.assert_called_once_with(["authored-tool"], cwd=self.work, env={}, timeout=5, log=log)
        self.assertEqual(b"authored retained log", log.read_bytes())


if __name__ == "__main__":
    unittest.main()
