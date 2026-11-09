#!/usr/bin/env python3
"""Authored desktop legal-input binding regressions. No Gradle or package build."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


def load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


PACKAGE_LEGAL = load("desktop_package_legal", ROOT / "scripts/desktop-package-legal.py")
MANIFEST = load("package_manifest_legal", ROOT / "scripts/package-manifest.py")
GRADLE = (ROOT / "composeApp/build.gradle.kts").read_text()
PACKAGE_SH = (ROOT / "scripts/package.sh").read_text()


class DesktopPackageLegalTest(unittest.TestCase):
    def setUp(self):
        (ROOT / ".work/tmp").mkdir(parents=True, exist_ok=True)
        self.directory = Path(tempfile.mkdtemp(prefix="desktop-legal-", dir=ROOT / ".work/tmp"))
        self.addCleanup(shutil.rmtree, self.directory, True)

    def legal_directory(self, name: str = "legal") -> Path:
        path = self.directory / name
        path.mkdir()
        (path / "index.json").write_text("{}\n")
        (path / "notices").mkdir()
        (path / "notices/a.txt").write_text("authored fixture\n")
        return path

    def test_windows_installer_format_is_exe_and_legacy_msi_is_still_identifiable(self):
        self.assertEqual(".exe", MANIFEST.package_extension("Windows", "packageExe"))
        self.assertEqual(".msi", MANIFEST.package_extension("Windows", "packageMsi"))
        self.assertEqual(".deb", MANIFEST.package_extension("Linux", "packageDeb"))
        self.assertEqual(".dmg", MANIFEST.package_extension("Darwin", "packageDmg"))
        with self.assertRaises(MANIFEST.ManifestError):
            MANIFEST.package_extension("Unknown", "packageExe")

    def test_platform_mapping_is_explicit(self):
        self.assertEqual("desktop-linux-x64", PACKAGE_LEGAL.platform_name("Linux", "x86_64"))
        self.assertEqual("desktop-linux-x64", PACKAGE_LEGAL.platform_name("Linux", "AMD64"))
        with self.assertRaises(PACKAGE_LEGAL.DesktopLegalError):
            PACKAGE_LEGAL.platform_name("Linux", "aarch64")
        self.assertEqual("desktop-macos-arm64", PACKAGE_LEGAL.platform_name("Darwin", "arm64"))
        self.assertEqual("desktop-macos-x64", PACKAGE_LEGAL.platform_name("Darwin", "x86_64"))
        self.assertEqual("desktop-windows-x64", PACKAGE_LEGAL.platform_name("Windows", "AMD64"))
        with self.assertRaises(PACKAGE_LEGAL.DesktopLegalError):
            PACKAGE_LEGAL.platform_name("FreeBSD", "x86_64")

    def test_locates_the_single_known_app_image_jar_per_platform(self):
        layouts = {
            "desktop-linux-x64": "Veyra/lib/app",
            "desktop-windows-x64": "Veyra/app",
            "desktop-macos-arm64": "Veyra.app/Contents/app",
            "desktop-macos-x64": "Veyra.app/Contents/app",
        }
        for platform_id, relative in layouts.items():
            root = self.directory / platform_id
            jar = root / "composeApp/build/compose/binaries/main/app" / relative / "composeApp-desktop-1.0.0.jar"
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b"authored jar fixture")
            with self.subTest(platform=platform_id):
                self.assertEqual(jar, PACKAGE_LEGAL.locate_app_jar(platform_id, root))
        with self.assertRaisesRegex(PACKAGE_LEGAL.DesktopLegalError, "No composeApp"):
            PACKAGE_LEGAL.locate_app_jar("desktop-linux-x64", self.directory / "empty")

    def test_rejects_missing_multiple_or_symlinked_app_jars(self):
        root = self.directory / "multiple"
        directory = root / "composeApp/build/compose/binaries/main/app/Veyra/lib/app"
        directory.mkdir(parents=True)
        (directory / "composeApp-desktop-1.0.0.jar").write_bytes(b"one")
        (directory / "composeApp-desktop-1.0.1.jar").write_bytes(b"two")
        with self.assertRaisesRegex(PACKAGE_LEGAL.DesktopLegalError, "exactly one"):
            PACKAGE_LEGAL.locate_app_jar("desktop-linux-x64", root)
        (directory / "composeApp-desktop-1.0.1.jar").unlink()
        link = directory / "composeApp-desktop-link.jar"
        try:
            link.symlink_to(directory / "composeApp-desktop-1.0.0.jar")
        except OSError:
            self.skipTest("host does not permit symlink fixture")
        with self.assertRaisesRegex(PACKAGE_LEGAL.DesktopLegalError, "regular file"):
            PACKAGE_LEGAL.locate_app_jar("desktop-linux-x64", root)

    def test_validates_generated_legal_directory_bounds(self):
        root = self.directory / "repo"
        root.mkdir()
        legal = root / "legal"
        legal.mkdir()
        (legal / "index.json").write_text("{}\n")
        (legal / "notices").mkdir()
        (legal / "notices/a.txt").write_text("authored fixture\n")
        self.assertEqual(legal.resolve(), PACKAGE_LEGAL.validate_legal_directory(legal, root))
        missing = root / "missing"
        missing.mkdir()
        with self.assertRaisesRegex(PACKAGE_LEGAL.DesktopLegalError, "index.json"):
            PACKAGE_LEGAL.validate_legal_directory(missing, root)
        outside = self.directory / "outside-legal"
        outside.mkdir()
        (outside / "index.json").write_text("{}\n")
        with self.assertRaisesRegex(PACKAGE_LEGAL.DesktopLegalError, "escapes"):
            PACKAGE_LEGAL.validate_legal_directory(outside, root)

    def test_rejects_symlinked_legal_entries(self):
        legal = self.legal_directory()
        link = legal / "notices/link.txt"
        try:
            link.symlink_to(legal / "notices/a.txt")
        except OSError:
            self.skipTest("host does not permit symlink fixture")
        with self.assertRaisesRegex(PACKAGE_LEGAL.DesktopLegalError, "symlink"):
            PACKAGE_LEGAL.validate_legal_directory(legal, self.directory)

    def test_gradle_replacement_uses_the_verified_prefix_and_original_exclusion(self):
        self.assertEqual(MANIFEST.LEGAL_RESOURCE_PREFIX, PACKAGE_LEGAL.LEGAL_RESOURCE_PREFIX)
        prefix = MANIFEST.LEGAL_RESOURCE_PREFIX.rstrip("/")
        self.assertIn('providers.gradleProperty("veyra.desktopLegalDir")', GRADLE)
        self.assertIn('"composeResources/dev.veyra.generated.resources/files/legal"', GRADLE)
        self.assertIn('exclude("$desktopLegalResourcePrefix/**")', GRADLE)
        self.assertIn("into(desktopLegalResourcePrefix)", GRADLE)
        self.assertIn("inputs.dir(realLegal.toFile())", GRADLE)
        self.assertIn("desktopProcessResources", GRADLE)
        self.assertIn(prefix, GRADLE)

    def test_package_script_generates_and_requires_embedded_bytes(self):
        self.assertIn("desktop-package-legal.py platform", PACKAGE_SH)
        self.assertIn(":shared:jvmJar", PACKAGE_SH)
        self.assertIn(":composeApp:exportProductionInventory", PACKAGE_SH)
        self.assertIn("--supplement third_party/notices/desktop-reviewed.json", PACKAGE_SH)
        self.assertIn(':composeApp:createDistributable ":composeApp:$task"', PACKAGE_SH)
        self.assertIn("-Pveyra.desktopLegalDir=$legal", PACKAGE_SH)
        self.assertIn("--app-jar \"$app_jar\"", PACKAGE_SH)
        self.assertIn("--desktop-legal-dir \"$legal\"", PACKAGE_SH)
        self.assertIn("--require-embedded-legal", PACKAGE_SH)


if __name__ == "__main__":
    unittest.main()
