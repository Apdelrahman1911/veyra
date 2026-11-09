#!/usr/bin/env python3
"""Native staging/build-wiring regressions; no compiler, media or network runs."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import struct
import shutil
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("veyra_bundle", Path(__file__).with_name("bundle.py"))
BUNDLE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUNDLE)


class NativeBundleTest(unittest.TestCase):
    def setUp(self):
        base = BUNDLE.work(BUNDLE.ROOT / ".work/desktop-native/tests")
        base.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix="authored-", dir=base)
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.inputs = self.root / ".work/inputs"
        self.inputs.mkdir(parents=True)

    def sources(self, target="linux-x64"):
        result = {}
        for index, name in enumerate(sorted(BUNDLE.required_resources(target))):
            path = self.inputs / str(index)
            path.write_text("authored-not-executed-" + name)
            result[name] = path
        return result

    def stage(self, target="linux-x64"):
        sources = self.sources(target)
        path = BUNDLE.stage_resources(target, sources, root=self.root)
        return path, sources

    def test_supported_targets_are_host_exact(self):
        for host, arch, expected in (("Linux", "x86_64", "linux-x64"), ("Windows", "AMD64", "windows-x64"),
                                     ("Darwin", "x86_64", "macos-x64"), ("Darwin", "aarch64", "macos-arm64")):
            self.assertEqual(expected, BUNDLE.host_target(host, arch))
        for value in (("Linux", "arm64"), ("Windows", "arm64"), ("Darwin", "ppc64"), ("FreeBSD", "amd64")):
            with self.assertRaises(ValueError):
                BUNDLE.host_target(*value)

    def test_known_binary_headers_and_wrong_arch_are_checked_without_execution(self):
        path = self.inputs / "binary"
        elf = bytearray(64)
        elf[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", elf, 18, 62)
        path.write_bytes(elf)
        BUNDLE.binary_target(path, "linux-x64", self.root)
        with self.assertRaises(ValueError):
            BUNDLE.binary_target(path, "macos-x64", self.root)
        for target, machine in (("macos-x64", 0x01000007), ("macos-arm64", 0x0100000C)):
            header = bytearray(64)
            header[:4] = b"\xcf\xfa\xed\xfe"
            struct.pack_into("<I", header, 4, machine)
            path.write_bytes(header)
            BUNDLE.binary_target(path, target, self.root)
        header = bytearray(90)
        header[:2] = b"MZ"
        struct.pack_into("<I", header, 60, 64)
        header[64:70] = b"PE\0\0\x64\x86"
        header[88:90] = b"\x0b\x02"
        path.write_bytes(header)
        BUNDLE.binary_target(path, "windows-x64", self.root)
        struct.pack_into("<I", header, 60, 0xFFFFFFFF)
        path.write_bytes(header)
        with self.assertRaises(ValueError):
            BUNDLE.binary_target(path, "windows-x64", self.root)

    def test_no_unscoped_or_cross_labelled_execution(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(BUNDLE, "run") as run:
            with self.assertRaises(ValueError):
                BUNDLE.prepare("linux-x64")
            run.assert_not_called()
        with patch.dict(os.environ, {"VEYRA_SCOPED_ENV": "1"}), patch.object(BUNDLE.platform, "system", return_value="Linux"), \
                patch.object(BUNDLE.platform, "machine", return_value="x86_64"), patch.object(BUNDLE, "run") as run:
            with self.assertRaises(ValueError):
                BUNDLE.prepare("windows-x64")
            run.assert_not_called()

    def test_staging_is_complete_and_repeat_is_nonexecuting_idempotent(self):
        directory, sources = self.stage()
        before = {str(p): p.stat().st_mtime_ns for p in directory.rglob("*")}
        self.assertEqual(set(sources), set(BUNDLE.read_manifest(directory, self.root)["files"]))
        with patch.object(BUNDLE.owned_process, "run") as run:
            self.assertEqual(directory, BUNDLE.stage_resources("linux-x64", sources, root=self.root))
            run.assert_not_called()
        self.assertEqual(before, {str(p): p.stat().st_mtime_ns for p in directory.rglob("*")})

    def test_gradle_created_empty_output_directory_is_replaced(self):
        destination = self.root / ".work/desktop-native/resources/linux-x64"
        destination.mkdir(parents=True)
        self.assertEqual([], list(destination.iterdir()))
        directory, sources = self.stage()
        self.assertEqual(destination, directory)
        self.assertEqual(set(sources), set(BUNDLE.read_manifest(directory, self.root)["files"]))

    def test_nonempty_unrecognized_output_directory_is_refused_and_preserved(self):
        destination = self.root / ".work/desktop-native/resources/linux-x64"
        destination.mkdir(parents=True)
        sentinel = destination / "owner-file"
        sentinel.write_text("preserve me")
        with self.assertRaises(ValueError):
            BUNDLE.stage_resources("linux-x64", self.sources(), root=self.root)
        self.assertEqual("preserve me", sentinel.read_text())
        self.assertFalse((destination / BUNDLE.MANIFEST).exists())

    @unittest.skipIf(os.name == "nt", "Requires unprivileged symlink support")
    def test_linked_output_destination_is_refused_not_removed(self):
        base = self.root / ".work/desktop-native/resources"
        base.mkdir(parents=True)
        outside = self.root / ".work/outside-destination"
        outside.mkdir()
        (base / "linux-x64").symlink_to(outside, target_is_directory=True)
        with self.assertRaises(ValueError):
            BUNDLE.stage_resources("linux-x64", self.sources(), root=self.root)
        self.assertTrue(outside.is_dir())

    def test_empty_output_placeholder_does_not_block_verified_backup_recovery(self):
        directory, sources = self.stage()
        original = BUNDLE.read_manifest(directory, self.root)
        previous = directory.with_name(".old-linux-x64-authored-interruption")
        directory.rename(previous)
        directory.mkdir()  # Gradle-created empty declared output.
        self.assertEqual(directory, BUNDLE.stage_resources("linux-x64", sources, root=self.root))
        self.assertFalse(previous.exists())
        self.assertEqual(original, BUNDLE.read_manifest(directory, self.root))

    def test_valid_stale_stage_is_replaced_and_only_old_generated_files_are_removed(self):
        directory, sources = self.stage()
        name = next(iter(sources))
        sources[name].write_bytes(b"new authored input")
        self.assertEqual(directory, BUNDLE.stage_resources("linux-x64", sources, root=self.root))
        self.assertEqual(b"new authored input", (directory / name).read_bytes())
        self.assertEqual(["linux-x64"], sorted(p.name for p in directory.parent.iterdir()))
        BUNDLE.read_manifest(directory, self.root)

    def test_interrupted_two_rename_gap_restores_one_verified_previous_stage(self):
        directory, sources = self.stage()
        original = BUNDLE.read_manifest(directory, self.root)
        previous = directory.with_name(".old-linux-x64-authored-interruption")
        directory.rename(previous)
        self.assertFalse(directory.exists())
        self.assertEqual(directory, BUNDLE.stage_resources("linux-x64", sources, root=self.root))
        self.assertFalse(previous.exists())
        self.assertEqual(original, BUNDLE.read_manifest(directory, self.root))

    def test_interrupted_installed_replacement_retires_only_verified_previous_stage(self):
        directory, sources = self.stage()
        previous = directory.with_name(".old-linux-x64-authored-interruption")
        shutil.copytree(directory, previous)
        self.assertEqual(directory, BUNDLE.stage_resources("linux-x64", sources, root=self.root))
        self.assertFalse(previous.exists())
        BUNDLE.read_manifest(directory, self.root)

    def test_corrupt_installed_or_previous_stages_are_preserved_during_recovery(self):
        directory, sources = self.stage()
        previous = directory.with_name(".old-linux-x64-authored-interruption")
        shutil.copytree(directory, previous)
        marker = directory / "caller-owned"
        marker.write_text("preserve installed data")
        with self.assertRaises(ValueError):
            BUNDLE.stage_resources("linux-x64", sources, root=self.root)
        self.assertEqual("preserve installed data", marker.read_text())
        BUNDLE.read_manifest(previous, self.root)
        marker.unlink()
        old_marker = previous / "caller-owned"
        old_marker.write_text("preserve previous data")
        with self.assertRaises(ValueError):
            BUNDLE.stage_resources("linux-x64", sources, root=self.root)
        self.assertEqual("preserve previous data", old_marker.read_text())
        BUNDLE.read_manifest(directory, self.root)

    def test_ambiguous_or_wrong_target_backups_never_guess_which_tree_to_remove(self):
        directory, sources = self.stage()
        previous = directory.with_name(".old-linux-x64-first")
        another = directory.with_name(".old-linux-x64-second")
        shutil.copytree(directory, previous)
        shutil.copytree(directory, another)
        with self.assertRaises(ValueError):
            BUNDLE.stage_resources("linux-x64", sources, root=self.root)
        for path in (directory, previous, another):
            BUNDLE.read_manifest(path, self.root)
        BUNDLE.remove_verified_stage(another, BUNDLE.read_manifest(another, self.root), self.root)
        BUNDLE.remove_verified_stage(previous, BUNDLE.read_manifest(previous, self.root), self.root)
        wrong, _ = self.stage("windows-x64")
        wrong.rename(previous)
        with self.assertRaises(ValueError):
            BUNDLE.stage_resources("linux-x64", sources, root=self.root)
        self.assertEqual("windows-x64", BUNDLE.read_manifest(previous, self.root)["target"])

    def test_incomplete_and_foreign_resources_never_stage(self):
        sources = self.sources()
        with self.assertRaises(ValueError):
            BUNDLE.stage_resources("linux-x64", {**sources, "../outside": next(iter(sources.values()))}, root=self.root)
        sources.pop(next(iter(sources)))
        with self.assertRaises(ValueError):
            BUNDLE.stage_resources("linux-x64", sources, root=self.root)
        self.assertFalse((self.root / ".work/desktop-native/resources").exists())

    def test_coherent_wrong_target_old_inventory_is_not_deleted(self):
        directory, _ = self.stage("windows-x64")
        wrong = directory.with_name("linux-x64")
        directory.rename(wrong)
        original = (wrong / BUNDLE.MANIFEST).read_bytes()
        with self.assertRaises(ValueError):
            BUNDLE.stage_resources("linux-x64", self.sources(), root=self.root)
        self.assertEqual(original, (wrong / BUNDLE.MANIFEST).read_bytes())

    @unittest.skipIf(os.name == "nt", "Requires unprivileged symlink support")
    def test_log_symlink_does_not_append_to_sentinel_or_run_command(self):
        sentinel = self.root / "caller-owned"
        sentinel.write_bytes(b"preserve sentinel")
        log = self.inputs / "build.log"
        log.symlink_to(sentinel)
        with patch.object(BUNDLE.owned_process, "run") as run, self.assertRaises(ValueError):
            BUNDLE.run(["never-run"], self.root, log, 1, root=self.root)
        run.assert_not_called()
        self.assertEqual(b"preserve sentinel", sentinel.read_bytes())

    def test_log_hardlink_is_rejected_before_append_or_command(self):
        sentinel = self.inputs / "owner-file"
        sentinel.write_bytes(b"preserve sentinel")
        log = self.inputs / "build.log"
        os.link(sentinel, log)
        with patch.object(BUNDLE.owned_process, "run") as run, self.assertRaises(ValueError):
            BUNDLE.run(["never-run"], self.root, log, 1, root=self.root)
        run.assert_not_called()
        self.assertEqual(b"preserve sentinel", sentinel.read_bytes())

    def test_native_build_uses_owned_runner_and_preserves_uncertain_work(self):
        log = self.inputs / "build.log"
        log.write_bytes(b"preserve diagnostic output")
        failure = BUNDLE.owned_process.OwnedProcessError("cleanup_failed", cleanup_complete=False)
        with patch.object(BUNDLE.owned_process, "run", side_effect=failure) as run:
            with self.assertRaises(BUNDLE.owned_process.OwnedProcessError) as raised:
                BUNDLE.run(["authored-tool", "arg"], self.root, log, 3, root=self.root)
        self.assertIs(failure, raised.exception)
        self.assertFalse(raised.exception.cleanup_complete)
        self.assertEqual((["authored-tool", "arg"],), run.call_args.args)
        self.assertEqual(self.root, run.call_args.kwargs["cwd"])
        self.assertEqual(log, run.call_args.kwargs["log"])
        self.assertEqual(3, run.call_args.kwargs["timeout"])
        self.assertEqual(b"preserve diagnostic output", log.read_bytes())

    def test_windows_native_run_prepares_only_the_child_environment(self):
        log = self.inputs / "build.log"
        process_environment = dict(os.environ)
        prepared = {"PATH": "authored-x64-msvc", "VEYRA_SCOPED_ENV": "1"}
        with patch.dict(os.environ, {"CC": "authored-fallback-compiler", "HOME": "authored-home"}):
            with patch.object(BUNDLE.platform, "system", return_value="Windows"), \
                    patch.object(BUNDLE.windows_toolchain, "child_environment",
                                 return_value=prepared) as helper, \
                    patch.object(BUNDLE.owned_process, "run") as run:
                BUNDLE.run(["authored-tool.exe", "arg with spaces"], self.root, log, 7, root=self.root)
            helper.assert_called_once()
            child = helper.call_args.args[0]
            self.assertNotIn("CC", child)
            self.assertNotIn("CXX", child)
            self.assertEqual("authored-home", child["HOME"])
            self.assertEqual(self.root, run.call_args.kwargs["cwd"])
            self.assertEqual(prepared, run.call_args.kwargs["env"])
            scratch = helper.call_args.kwargs["scratch"]
            self.assertTrue(scratch.is_relative_to(self.root / ".work"))
        self.assertEqual(process_environment, dict(os.environ))

        with patch.object(BUNDLE.platform, "system", return_value="Linux"), \
                patch.object(BUNDLE.windows_toolchain, "child_environment") as helper, \
                patch.object(BUNDLE.owned_process, "run") as run:
            BUNDLE.run(["authored-tool"], self.root, log, 7, root=self.root)
        helper.assert_not_called()
        self.assertEqual(os.environ.get("HOME"), run.call_args.kwargs["env"]["HOME"])

    def test_windows_toolchain_failure_stops_before_any_native_command(self):
        log = self.inputs / "build.log"
        with patch.object(BUNDLE.platform, "system", return_value="Windows"), \
                patch.object(BUNDLE.windows_toolchain, "child_environment",
                             side_effect=ValueError("Existing x64 MSVC compiler tools are required; no substitute compiler is used.")), \
                patch.object(BUNDLE.owned_process, "run") as run:
            with self.assertRaisesRegex(ValueError, "no substitute compiler is used"):
                BUNDLE.run(["authored-tool"], self.root, log, 7, root=self.root)
        run.assert_not_called()
        self.assertFalse(log.exists())

    def test_failed_owned_build_retains_bounded_safe_compiler_diagnostic(self):
        log = self.inputs / "build.log"
        original = (
            "authored setup\n"
            "/src/authored.c:7:3: error: use of undeclared identifier 'sentinel_error'\n"
            "  sentinel_error = 1;\n"
            "  ^\n"
            "CMake Error at CMakeLists.txt:4 (message):\n"
            "  authored cmake sentinel\n"
            "fatal error: Authorization: Bearer gho_authoredcredential\n"
            "error: fetch https://example.invalid/object?X-Amz-Signature=authoredsignature failed\n"
            + "".join("error: repeated authored diagnostic %d\n" % index for index in range(100))
        ).encode()
        log.write_bytes(original)
        failure = BUNDLE.owned_process.OwnedProcessError("command_failed", cleanup_complete=True, returncode=1)
        output = io.StringIO()
        with patch.object(BUNDLE.owned_process, "run", side_effect=failure), contextlib.redirect_stderr(output):
            with self.assertRaises(BUNDLE.owned_process.OwnedProcessError) as raised:
                BUNDLE.run(["authored-tool"], self.root, log, 3, root=self.root)
        self.assertIs(failure, raised.exception)
        self.assertEqual(original, log.read_bytes())
        record = json.loads((self.root / BUNDLE.DIAGNOSTIC).read_text())
        text = "\n".join(record["lines"])
        self.assertIn("sentinel_error", text)
        self.assertIn("authored cmake sentinel", text)
        self.assertNotIn("gho_authoredcredential", text)
        self.assertNotIn("authoredsignature", text)
        self.assertNotIn("Authorization", text)
        self.assertNotIn("authored setup", text)
        self.assertLessEqual(len(record["lines"]), BUNDLE.DIAGNOSTIC_MAX_LINES)
        self.assertLessEqual(len(text.encode()), BUNDLE.DIAGNOSTIC_MAX_BYTES)
        self.assertTrue(record["truncated"])
        self.assertGreaterEqual(record["suppressedLines"], 2)
        self.assertTrue(record["logAvailable"])
        console = output.getvalue()
        self.assertIn("sentinel_error", console)
        self.assertNotIn("gho_authoredcredential", console)
        self.assertNotIn("authoredsignature", console)
        self.assertNotIn("authored setup", console)
        self.assertIn("Preserved log:", console)

    def test_configure_early_exit_and_nested_reporter_metadata_are_distinguished(self):
        lines, suppressed, truncated = BUNDLE._extract_diagnostics(
            'Unknown option "--authored-invalid".\n'
            'Unsupported MSVC version (2013 or newer required)\n'
            'Unable to create temporary directory in authored-work.\n'
            'Native FFmpeg configure command failed.\n'
            'Native build diagnostic (bounded compiler/build output only):\n'
            '  No compiler/build error lines retained; inspect the build log.\n'
            '  Preserved log: .work/authored-error.log\n'
            '  Diagnostic record: .work/authored-error.json\n')
        self.assertEqual([
            'Unknown option "--authored-invalid".',
            'Unsupported MSVC version (2013 or newer required)',
            'Unable to create temporary directory in authored-work.',
            'Native FFmpeg configure command failed.',
        ], lines)
        self.assertEqual(0, suppressed)
        self.assertFalse(truncated)

    def test_configure_and_shell_failures_are_retained_without_raw_output(self):
        text = (
            "authored unrelated setup\n"
            "cl.exe is unable to create an executable file.\n"
            "C compiler test failed.\n"
            "bash: /authored/configure: No such file or directory\n"
            "bash: cl.exe: command not found\n"
            "bash: token=authored-private: command not found\n"
            "bash: https://example.invalid/authored-private: No such file or directory\n"
        )
        lines, suppressed, truncated = BUNDLE._extract_diagnostics(text)
        self.assertEqual(text.splitlines()[1:5], lines)
        self.assertEqual(2, suppressed)
        self.assertFalse(truncated)

    def test_ffmpeg_prerequisite_headline_and_message_are_retained_without_log_dump(self):
        text = (
            "authored unrelated setup\n"
            "Scoped production FFmpeg build failed (ValueError). No global install or substitute binary.\n"
            "Existing matching-host compiler/make/bash tools are required; nothing is installed\n"
            "authored unrelated detail\n"
            "ValueError: not a known prerequisite headline\n"
        )
        lines, suppressed, truncated = BUNDLE._extract_diagnostics(text)
        self.assertEqual([
            "Scoped production FFmpeg build failed (ValueError). No global install or substitute binary.",
            "Existing matching-host compiler/make/bash tools are required; nothing is installed",
        ], lines)
        self.assertEqual(0, suppressed)
        self.assertFalse(truncated)

        # Only the exact known-safe forms are retained; a URL appended to one is
        # not copied into the diagnostic at all.
        sensitive = ("Scoped production FFmpeg build failed (ValueError). No global install or substitute binary. "
                     "https://example.invalid/authored-secret")
        lines, suppressed, _ = BUNDLE._extract_diagnostics(sensitive)
        self.assertEqual([], lines)
        self.assertEqual(1, suppressed)

    def test_changed_or_unmanaged_stage_is_preserved_not_silently_cleaned(self):
        directory, sources = self.stage()
        unmanaged = directory / "owner-file"
        unmanaged.write_text("preserve me")
        with self.assertRaises(ValueError):
            BUNDLE.stage_resources("linux-x64", sources, root=self.root)
        self.assertEqual("preserve me", unmanaged.read_text())
        unmanaged.unlink()
        name = next(iter(sources))
        (directory / name).write_text("corrupted generated file")
        with self.assertRaises(ValueError):
            BUNDLE.stage_resources("linux-x64", sources, root=self.root)
        self.assertEqual("corrupted generated file", (directory / name).read_text())

    def test_bad_inventory_paths_and_changed_target_fail(self):
        directory, _ = self.stage()
        receipt = directory / BUNDLE.MANIFEST
        original = json.loads(receipt.read_text())
        for target in ("windows-x64", "not-a-target"):
            receipt.write_text(json.dumps({**original, "target": target}))
            with self.assertRaises(ValueError):
                BUNDLE.read_manifest(directory, self.root)
        receipt.write_text(json.dumps({**original, "files": {"../outside": "0" * 64}}))
        with self.assertRaises(ValueError):
            BUNDLE.read_manifest(directory, self.root)
        for value in ("/absolute", "a/../b", "a//b", "a/./b", "a\\b", "a:b", "a\nb"):
            with self.assertRaises(ValueError):
                BUNDLE.relative_name(value)

    @unittest.skipIf(os.name == "nt", "Requires unprivileged symlink support")
    def test_linked_input_and_escaping_output_are_refused_before_writes(self):
        sources = self.sources()
        name = next(iter(sources))
        link = self.inputs / "link"
        link.symlink_to(sources[name])
        with self.assertRaises(ValueError):
            BUNDLE.stage_resources("linux-x64", {**sources, name: link}, root=self.root)
        base = self.root / ".work/desktop-native"
        base.mkdir()
        (base / "resources").symlink_to(self.root.parent, target_is_directory=True)
        with self.assertRaises(ValueError):
            BUNDLE.stage_resources("linux-x64", sources, root=self.root)

    def test_outside_work_and_empty_or_oversized_file_are_rejected(self):
        with self.assertRaises(ValueError):
            BUNDLE.work(self.root / "not-work", self.root)
        path = self.inputs / "empty"
        path.touch()
        with self.assertRaises(ValueError):
            BUNDLE.file_hash(path, self.root)
        path.write_bytes(b"authored")
        with patch.object(BUNDLE, "MAX_FILE", 3), self.assertRaises(ValueError):
            BUNDLE.file_hash(path, self.root)

    def test_gradle_prepares_production_variants_before_resource_inputs_not_tests(self):
        source = (BUNDLE.ROOT / "composeApp/build.gradle.kts").read_text()
        self.assertIn("resources.srcDir(desktopNativeResources)", source)
        self.assertIn("mustRunAfter(prepareDesktopNativeResources)", source)
        self.assertIn("dependsOn(prepareDesktopNativeResources)", source)
        production = source.split("val desktopProductionTasks = setOf(", 1)[1].split(")", 1)[0]
        for task in ("run", "runRelease", "createDistributable", "createReleaseDistributable", "packageDeb", "packageDmg", "packageMsi"):
            self.assertIn('"' + task + '"', production)
        for task in ("desktopTest", "compileKotlinDesktop", "desktopJar", "packageUberJarForCurrentOS", "packageReleaseUberJarForCurrentOS"):
            self.assertNotIn('"' + task + '"', production)

    def test_uber_jars_fail_before_tasks_including_dry_run_instead_of_rewriting_native_archive(self):
        source = (BUNDLE.ROOT / "composeApp/build.gradle.kts").read_text()
        unsupported = source.split("val unsupportedDesktopUberJarTasks = setOf(", 1)[1].split(")", 1)[0]
        for task in ("packageUberJarForCurrentOS", "packageReleaseUberJarForCurrentOS"):
            self.assertIn('"' + task + '"', unsupported)
        guard = source.split("gradle.taskGraph.whenReady {", 1)[1].split("// Bound each test worker", 1)[0]
        self.assertIn("allTasks.any", guard)
        self.assertIn("it.project == project", guard)
        self.assertIn("it.name in unsupportedDesktopUberJarTasks", guard)
        self.assertIn("throw GradleException", guard)
        self.assertIn("createDistributable", guard)
        self.assertNotIn("doFirst", guard)
        self.assertNotIn("enabled = false", guard)


if __name__ == "__main__":
    unittest.main()
