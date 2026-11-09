#!/usr/bin/env -S python3 -B
"""Stdlib regression tests for the read-only design-resource audit.

Run with python3 -B. Every disposable file (including modified font copies) stays
under Veyra/.work; no font is installed and no network/build tool is invoked.
These tests do not render Arabic or establish native accessibility conformance.
"""
from __future__ import annotations

from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/verify-design-resources.py"
SPEC = importlib.util.spec_from_file_location("design_resource_audit", SCRIPT)
assert SPEC and SPEC.loader
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


def entry(key: str, english: str, arabic: str) -> str:
    return f"    {key}({json.dumps(english, ensure_ascii=False)}, {json.dumps(arabic, ensure_ascii=False)}),"


def catalogue(*entries: str) -> str:
    return "enum class TextKey(val english: String, val arabic: String) {\n" + "\n".join(entries) + "\n}\n"


VALID_CATALOGUE = catalogue(entry("Name", "Veyra", "ڤيرا"), entry("Count", "{0} items", "العناصر: {0}"))


class ProductCopyTests(unittest.TestCase):
    def test_podcast_historical_position_notices_distinguish_changed_and_unverified_recordings(self) -> None:
        entries = AUDIT.parse_catalog((ROOT / AUDIT.DESIGN / "AppStrings.kt").read_text(encoding="utf-8"))
        expected = {
            "PodcastProgressRecordingChanged": (
                "Saved position: {0}, from a previous recording. This recording will play from the beginning.",
                "موضع الاستماع المحفوظ: {0}، من تسجيل سابق. سيبدأ تشغيل هذا التسجيل من البداية.",
            ),
            "PodcastProgressRecordingUnverified": (
                "Saved position: {0}. Playback resumes there only if the selected recording matches.",
                "موضع الاستماع المحفوظ: {0}. يُستأنف التشغيل من هذا الموضع فقط إذا تطابق التسجيل المختار مع التسجيل السابق.",
            ),
        }
        for key, translations in expected.items():
            with self.subTest(key=key):
                self.assertEqual(translations, entries[key])
                for translation in translations:
                    self.assertEqual(["0"], AUDIT.PLACEHOLDER.findall(translation))


class CatalogueParserTests(unittest.TestCase):
    def test_bilingual_placeholders_may_reorder_but_preserve_multiplicity(self) -> None:
        source = catalogue(entry("Track", "{0} by {1}; {0}", "{1} — {0}؛ {0}"))
        self.assertEqual(AUDIT.parse_catalog(source)["Track"], ("{0} by {1}; {0}", "{1} — {0}؛ {0}"))

    def test_duplicate_key_is_rejected(self) -> None:
        with self.assertRaisesRegex(AUDIT.AuditError, "Duplicate TextKey"):
            AUDIT.parse_catalog(catalogue(entry("Name", "Veyra", "ڤيرا"), entry("Name", "Other", "آخر")))

    def test_missing_language_or_nonconstant_entry_is_rejected(self) -> None:
        for body in ('Name("Veyra"),', 'Name("Veyra", arabicValue),', 'Name("Veyra", "ڤيرا", "extra"),'):
            with self.subTest(body=body), self.assertRaisesRegex(AUDIT.AuditError, "bilingual constant"):
                AUDIT.parse_catalog(catalogue(body))

    def test_empty_or_missing_catalogue_is_rejected(self) -> None:
        for source in (catalogue(), "class SomethingElse {}"):
            with self.subTest(source=source), self.assertRaises(AUDIT.AuditError):
                AUDIT.parse_catalog(source)

    def test_blank_translation_is_rejected_in_either_language(self) -> None:
        for english, arabic in (("", "اسم"), ("Name", " \n")):
            with self.subTest(english=english), self.assertRaisesRegex(AUDIT.AuditError, "Empty translation"):
                AUDIT.parse_catalog(catalogue(entry("Name", english, arabic)))

    def test_placeholder_language_or_multiplicity_mismatch_is_rejected(self) -> None:
        for english, arabic in (("{0}", "اسم"), ("{0}", "{1}"), ("{0} {0}", "{0}")):
            with self.subTest(english=english, arabic=arabic), self.assertRaisesRegex(AUDIT.AuditError, "language mismatch"):
                AUDIT.parse_catalog(catalogue(entry("Value", english, arabic)))

    def test_noncontiguous_placeholders_are_rejected(self) -> None:
        for value in ("{1}", "{0} {2}", "{20}"):
            with self.subTest(value=value), self.assertRaisesRegex(AUDIT.AuditError, "Non-contiguous"):
                AUDIT.parse_catalog(catalogue(entry("Value", value, value)))

    def test_malformed_placeholder_braces_are_rejected(self) -> None:
        for value in ("{name}", "{ 0}", "{0", "0}", "{-1}", "{٠}"):
            with self.subTest(value=value), self.assertRaisesRegex(AUDIT.AuditError, "Malformed placeholder"):
                AUDIT.parse_catalog(catalogue(entry("Value", value, value)))

    def test_bidi_overrides_and_isolates_are_not_embedded_in_catalogue(self) -> None:
        for control in ("\u202a", "\u202e", "\u2066", "\u2068", "\u2069"):
            with self.subTest(codepoint=ord(control)), self.assertRaisesRegex(AUDIT.AuditError, "bidi formatting control"):
                AUDIT.parse_catalog(catalogue(entry("Name", "Name", "اسم" + control)))

    def test_kotlin_escapes_decode_without_corrupting_arabic(self) -> None:
        self.assertEqual(AUDIT.kotlin_literal(r'"a\n\t\"\u06A4 \\ \$literal"'), 'a\n\t"ڤ \\ $literal')

    def test_bad_kotlin_escapes_and_interpolation_are_rejected(self) -> None:
        for literal in (r'"\q"', r'"\u123Z"', '"$title"', '"${title}"', '"tail\\"'):
            with self.subTest(literal=literal), self.assertRaises(AUDIT.AuditError):
                AUDIT.kotlin_literal(literal)


class ContrastMathTests(unittest.TestCase):
    def test_known_ratios_and_symmetry(self) -> None:
        self.assertAlmostEqual(AUDIT.contrast("FF000000", "FFFFFFFF"), 21.0)
        self.assertAlmostEqual(AUDIT.contrast("FF808080", "FF808080"), 1.0)
        self.assertAlmostEqual(AUDIT.luminance("FF808080"), 0.21586050011389926)
        self.assertEqual(AUDIT.contrast("FF00696A", "FFF6F8F8"), AUDIT.contrast("FFF6F8F8", "FF00696A"))

    def test_alpha_or_non_argb_token_is_rejected(self) -> None:
        for value in ("80FFFFFF", "00FFFFFF", "FFFFFF", "FFFFFFFF00"):
            with self.subTest(value=value), self.assertRaisesRegex(AUDIT.AuditError, "opaque sRGB"):
                AUDIT.luminance(value)

    def test_selected_rows_and_focus_are_in_actual_contrast_contract(self) -> None:
        text, essential = AUDIT.contrast_contracts()
        self.assertEqual((len(text), len(essential)), (37, 15))
        for foreground in ("onSurface", "onSurfaceVariant", "primary"):
            self.assertIn((foreground, "primaryContainer", 4.5), text)
        self.assertIn(("onErrorContainer", "errorContainer", 4.5), text)
        self.assertIn(("outline", "errorContainer", 3.0), essential)
        self.assertFalse(any("outlineSubtle" in pair for pair in text + essential))


class FontBinaryTests(unittest.TestCase):
    def test_truncated_or_duplicate_sfnt_directory_is_rejected(self) -> None:
        duplicate = struct.pack(">IHHHH", 0x00010000, 2, 0, 0, 0) + struct.pack(">4sIII", b"cmap", 0, 44, 0) * 2
        for data in (b"", b"OTTO", b"\x00\x01\x00\x00\x00", duplicate):
            with self.subTest(data=data[:12]), self.assertRaises(AUDIT.AuditError):
                AUDIT.sfnt_tables(data)

    def test_unsigned_font_reads_are_bounded(self) -> None:
        for function, data, offset in ((AUDIT.u16, b"x", 0), (AUDIT.u32, b"abc", 0), (AUDIT.u16, b"abcd", -1)):
            with self.subTest(offset=offset, size=len(data)), self.assertRaises(AUDIT.AuditError):
                function(data, offset)

    def test_format_twelve_maps_supplementary_unicode_not_notdef(self) -> None:
        table = struct.pack(">HHIII", 12, 0, 28, 0, 1) + struct.pack(">III", 0x1F600, 0x1F601, 1)
        cmap = struct.pack(">HHHHI", 0, 1, 3, 10, 12) + table
        tables = {b"cmap": cmap, b"maxp": struct.pack(">IH", 0x00010000, 3)}
        self.assertEqual(AUDIT.unicode_glyphs(tables, {0x1F600, 0x1F601, 0x1F602}), {0x1F600, 0x1F601})
        tables[b"maxp"] = struct.pack(">IH", 0x00010000, 2)
        with self.assertRaisesRegex(AUDIT.AuditError, "missing glyph"):
            AUDIT.unicode_glyphs(tables, {0x1F601})

    def test_required_characters_include_joining_marks_digits_and_brand(self) -> None:
        required = AUDIT.required_characters({"Extra": ("é", "ڤيرا\u2068")})
        self.assertTrue({0x41, 0xE9, 0x64E, 0x660, 0x6F0, 0x6A4} <= required)
        self.assertNotIn(0x2068, required)


class ResourceFixtureTests(unittest.TestCase):
    def setUp(self) -> None:
        temporary_parent = ROOT / ".work/tmp"
        if not temporary_parent.resolve().is_relative_to(ROOT):
            raise RuntimeError("Refusing a test scratch directory outside Veyra")
        temporary_parent.mkdir(parents=True, exist_ok=True)
        directory = tempfile.TemporaryDirectory(prefix="design-audit-", dir=temporary_parent)
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name) / "repo with spaces"
        self.design = self.root / AUDIT.DESIGN
        self.resources = self.root / AUDIT.RESOURCES
        self.design.mkdir(parents=True)
        self.catalogue_path = self.design / "AppStrings.kt"
        self.catalogue_path.write_text(VALID_CATALOGUE, encoding="utf-8")
        self.theme_path = self.design / "VeyraTheme.kt"
        shutil.copy2(ROOT / AUDIT.DESIGN / "VeyraTheme.kt", self.theme_path)
        shutil.copytree(ROOT / AUDIT.RESOURCES, self.resources)
        self.manifest_path = self.resources / "files/licenses/font_manifest.json"
        self.manifest = json.loads(self.manifest_path.read_text(encoding="utf-8"))
        self.entries = AUDIT.parse_catalog(VALID_CATALOGUE)

    def save_manifest(self) -> None:
        self.manifest_path.write_text(json.dumps(self.manifest, ensure_ascii=False), encoding="utf-8")

    def write_source(self, source_set: str, source: str) -> None:
        path = self.root / "composeApp/src" / source_set / "kotlin/Probe.kt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding="utf-8")

    def replace_theme(self, old: str, new: str) -> None:
        source = self.theme_path.read_text(encoding="utf-8")
        self.assertIn(old, source)
        self.theme_path.write_text(source.replace(old, new, 1), encoding="utf-8")

    def audit_fonts(self) -> None:
        with redirect_stdout(io.StringIO()):
            AUDIT.audit_fonts(self.root, self.entries)

    def replace_resource_bytes(self, index: int, data: bytes) -> None:
        """Update the fixture hash too, so deeper structural checks really run."""
        manifest_entry = self.manifest["files"][index]
        (self.resources / manifest_entry["resource_path"]).write_bytes(data)
        manifest_entry.update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        self.save_manifest()

    def remove_layout_tag(self, table_tag: bytes, offset_field: int, record_tag: bytes) -> None:
        data = bytearray((self.resources / self.manifest["files"][0]["resource_path"]).read_bytes())
        for index in range(AUDIT.u16(data, 4)):
            tag, _checksum, offset, length = struct.unpack_from(">4sIII", data, 12 + index * 16)
            if tag != table_tag:
                continue
            table = data[offset:offset + length]
            list_offset = AUDIT.u16(table, offset_field)
            changed = False
            for record in range(AUDIT.u16(table, list_offset)):
                position = offset + list_offset + 2 + record * 6
                if data[position:position + 4] == record_tag:
                    data[position:position + 4] = b"zzzz"
                    changed = True
            # Feature tags can appear once per language system. Remove every
            # occurrence rather than leaving another legitimate duplicate.
            if changed:
                self.replace_resource_bytes(0, bytes(data))
                return
        self.fail(f"Fixture lacks expected {table_tag!r}/{record_tag!r}")

    def test_catalogue_reference_scan_rejects_missing_keys(self) -> None:
        self.write_source("commonMain", "val title = strings.text(TextKey.DoesNotExist)")
        with self.assertRaisesRegex(AUDIT.AuditError, "Missing TextKeys: DoesNotExist"):
            AUDIT.audit_catalog(self.root)

    def test_catalogue_scan_rejects_raw_templates_in_platform_main(self) -> None:
        self.write_source("desktopMain", "val title = strings.text ( TextKey.Count )")
        with self.assertRaisesRegex(AUDIT.AuditError, "Parameterized keys used with text"):
            AUDIT.audit_catalog(self.root)

    def test_template_inspection_in_tests_and_formatted_main_are_allowed(self) -> None:
        self.write_source("commonTest", "val template = strings.text(TextKey.Count)")
        self.write_source("commonMain", 'val title = strings.format(TextKey.Count, "2"); val name = strings.text(TextKey.Name)')
        with redirect_stdout(io.StringIO()):
            self.assertEqual(AUDIT.audit_catalog(self.root), self.entries)

    def test_both_current_palettes_pass_the_real_contract(self) -> None:
        output = io.StringIO()
        with redirect_stdout(output):
            AUDIT.audit_contrast(self.root)
        self.assertIn("PASS light contrast: 37 text pairs", output.getvalue())
        self.assertIn("PASS dark contrast: 37 text pairs", output.getvalue())

    def test_low_text_contrast_is_rejected(self) -> None:
        self.replace_theme("onSurface = Color(0xFF15252D)", "onSurface = Color(0xFFF6F8F8)")
        with self.assertRaisesRegex(AUDIT.AuditError, "Light onSurface/canvas"):
            AUDIT.audit_contrast(self.root)

    def test_low_essential_contrast_is_rejected(self) -> None:
        self.replace_theme("outline = Color(0xFF647D89)", "outline = Color(0xFFF6F8F8)")
        with self.assertRaisesRegex(AUDIT.AuditError, "Light outline/canvas"):
            AUDIT.audit_contrast(self.root)

    def test_alpha_is_rejected_even_on_decorative_token(self) -> None:
        self.replace_theme("outlineSubtle = Color(0xFFCDDADF)", "outlineSubtle = Color(0x80CDDADF)")
        with self.assertRaisesRegex(AUDIT.AuditError, "opaque sRGB"):
            AUDIT.audit_contrast(self.root)

    def test_missing_color_token_is_rejected(self) -> None:
        self.replace_theme("        warning = Color(0xFF785104),", "")
        with self.assertRaisesRegex(AUDIT.AuditError, "every token"):
            AUDIT.audit_contrast(self.root)

    def test_missing_palette_is_rejected(self) -> None:
        self.replace_theme("val Dark = VeyraColorTokens(", "val Other = VeyraColorTokens(")
        with self.assertRaisesRegex(AUDIT.AuditError, "Both explicit palettes"):
            AUDIT.audit_contrast(self.root)

    def test_real_bundled_fonts_and_unchanged_licence_pass(self) -> None:
        self.audit_fonts()

    def test_font_hash_mismatch_is_rejected(self) -> None:
        self.manifest["files"][0]["sha256"] = "0" * 64
        self.save_manifest()
        with self.assertRaisesRegex(AUDIT.AuditError, "SHA-256 mismatch"):
            self.audit_fonts()

    def test_font_size_mismatch_is_rejected(self) -> None:
        self.manifest["files"][0]["bytes"] += 1
        self.save_manifest()
        with self.assertRaisesRegex(AUDIT.AuditError, "size mismatch"):
            self.audit_fonts()

    def test_unpinned_font_revision_is_rejected(self) -> None:
        self.manifest["source_revision"] = "main"
        self.save_manifest()
        with self.assertRaisesRegex(AUDIT.AuditError, "complete Git revision"):
            self.audit_fonts()

    def test_unpinned_source_url_is_rejected(self) -> None:
        manifest_entry = self.manifest["files"][0]
        manifest_entry["source_url"] = manifest_entry["source_url"].replace(self.manifest["source_revision"], "main")
        self.save_manifest()
        with self.assertRaisesRegex(AUDIT.AuditError, "Unpinned/mismatched source URL"):
            self.audit_fonts()

    def test_modified_font_flag_requires_review(self) -> None:
        self.manifest["modified"] = True
        self.save_manifest()
        with self.assertRaisesRegex(AUDIT.AuditError, "modified/replaced font family"):
            self.audit_fonts()

    def test_resource_traversal_and_absolute_path_are_rejected(self) -> None:
        for relative in ("../outside.ttf", str(self.root / "outside.ttf")):
            with self.subTest(path=relative):
                self.manifest["files"][0]["resource_path"] = relative
                self.save_manifest()
                with self.assertRaisesRegex(AUDIT.AuditError, "escapes resources"):
                    self.audit_fonts()

    def test_symlink_escape_is_rejected_without_following_resource(self) -> None:
        outside = self.root / "outside.ttf"
        outside.write_bytes(b"fixture, not a font")
        link = self.resources / "font/escape.ttf"
        try:
            link.symlink_to(outside)
        except (OSError, NotImplementedError) as error:
            self.skipTest(f"Host does not permit a test symlink: {error}")
        self.manifest["files"][0]["resource_path"] = "font/escape.ttf"
        self.save_manifest()
        with self.assertRaisesRegex(AUDIT.AuditError, "escapes resources"):
            self.audit_fonts()
        self.assertEqual(outside.read_bytes(), b"fixture, not a font")

    def test_font_weight_metadata_mismatch_is_rejected(self) -> None:
        self.manifest["files"][0]["weight"] = 500
        self.save_manifest()
        with self.assertRaisesRegex(AUDIT.AuditError, "weight metadata mismatch"):
            self.audit_fonts()

    def test_duplicate_weight_is_rejected(self) -> None:
        self.manifest["files"][1]["weight"] = 400
        self.save_manifest()
        with self.assertRaisesRegex(AUDIT.AuditError, "unique Regular/Medium/SemiBold"):
            self.audit_fonts()

    def test_new_uncovered_catalogue_character_is_rejected(self) -> None:
        self.entries["Extra"] = ("\U0001f984", "ڤيرا")
        with self.assertRaisesRegex(AUDIT.AuditError, "lacks.*U\\+1F984"):
            self.audit_fonts()

    def test_missing_arabic_layout_script_is_rejected_even_with_matching_hash(self) -> None:
        self.remove_layout_tag(b"GPOS", 4, b"arab")
        with self.assertRaisesRegex(AUDIT.AuditError, "lacks Arabic GPOS script support"):
            self.audit_fonts()

    def test_missing_joining_feature_is_rejected_even_with_matching_hash(self) -> None:
        self.remove_layout_tag(b"GSUB", 6, b"init")
        with self.assertRaisesRegex(AUDIT.AuditError, "Arabic joining features"):
            self.audit_fonts()

    def test_missing_mark_feature_is_rejected_even_with_matching_hash(self) -> None:
        self.remove_layout_tag(b"GPOS", 6, b"mkmk")
        with self.assertRaisesRegex(AUDIT.AuditError, "mark positioning features"):
            self.audit_fonts()

    def test_truncated_licence_is_rejected_even_with_matching_hash(self) -> None:
        path = self.resources / self.manifest["files"][3]["resource_path"]
        self.replace_resource_bytes(3, path.read_bytes().split(b"DISCLAIMER")[0])
        with self.assertRaisesRegex(AUDIT.AuditError, "Incomplete OFL"):
            self.audit_fonts()

    def test_unmanifested_font_is_rejected(self) -> None:
        (self.resources / "font/unreviewed.ttf").write_bytes(b"unreviewed fixture")
        with self.assertRaisesRegex(AUDIT.AuditError, "Every bundled TTF"):
            self.audit_fonts()

    def test_theme_font_reference_mismatch_is_rejected(self) -> None:
        self.replace_theme("Res.font.ibm_plex_sans_arabic_regular", "Res.font.unreviewed")
        with self.assertRaisesRegex(AUDIT.AuditError, "Theme font references and manifest disagree"):
            self.audit_fonts()

    def run_cli(self) -> subprocess.CompletedProcess[str]:
        scripts = self.root / "scripts"
        scripts.mkdir(exist_ok=True)
        script = scripts / SCRIPT.name
        if not script.exists():
            shutil.copy2(SCRIPT, script)
        working_directory = self.root / "unrelated working directory"
        working_directory.mkdir(exist_ok=True)
        environment = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", PYTHONNOUSERSITE="1",
                           TMPDIR=str(working_directory), TMP=str(working_directory), TEMP=str(working_directory))
        return subprocess.run([sys.executable, "-B", str(script)], cwd=working_directory, env=environment,
                              encoding="utf-8", capture_output=True, timeout=20)

    def tree_fingerprint(self) -> dict[str, tuple[str, int, int]]:
        return {path.relative_to(self.root).as_posix():
                (hashlib.sha256(path.read_bytes()).hexdigest(), path.stat().st_mtime_ns, path.stat().st_size)
                for path in self.root.rglob("*") if path.is_file()}

    def test_cli_success_from_any_directory_and_read_only_repeat(self) -> None:
        first = self.run_cli()
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertIn("Static design audit PASSED", first.stdout)
        self.assertIn("Kotlin compilation, rendered Arabic and native accessibility remain separate gates", first.stdout)
        before = self.tree_fingerprint()
        second = self.run_cli()
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(before, self.tree_fingerprint())
        self.assertEqual(second.stderr, "")
        self.assertFalse(list(self.root.rglob("*.pyc")))

    def test_cli_aggregates_section_failures_and_returns_one(self) -> None:
        self.catalogue_path.write_text(catalogue(entry("Bad", "{0}", "اسم")), encoding="utf-8")
        self.replace_theme("onSurface = Color(0xFF15252D)", "onSurface = Color(0xFFF6F8F8)")
        self.manifest["source_revision"] = "main"
        self.save_manifest()
        result = self.run_cli()
        self.assertEqual(result.returncode, 1)
        for section in ("catalogue", "contrast", "fonts"):
            self.assertIn(f"FAIL {section}:", result.stderr)
        self.assertIn("FAILED (3 sections)", result.stderr)
        self.assertNotIn("Static design audit PASSED", result.stdout)
        self.assertNotIn("Traceback", result.stderr)

    def test_cli_missing_input_is_a_failure_not_a_traceback_or_synthetic_pass(self) -> None:
        self.catalogue_path.unlink()
        result = self.run_cli()
        self.assertEqual(result.returncode, 1)
        self.assertIn("FAIL catalogue:", result.stderr)
        self.assertNotIn("Static design audit PASSED", result.stdout)
        self.assertNotIn("Traceback", result.stderr)

    def test_cli_malformed_manifest_is_reported_without_traceback(self) -> None:
        self.manifest_path.write_text("{bad json", encoding="utf-8")
        result = self.run_cli()
        self.assertEqual(result.returncode, 1)
        self.assertIn("FAIL fonts:", result.stderr)
        self.assertNotIn("Traceback", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
