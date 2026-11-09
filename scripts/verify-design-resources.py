#!/usr/bin/env -S python3 -B
"""Read-only, stdlib-only audit of Veyra's authored design resources.

Run from any directory: python3 -B scripts/verify-design-resources.py
Exit 0 means these static checks passed; 1 means a check or input failed.
This does not compile Kotlin, shape/render text, test screen readers, inspect
screenshots, or establish accessibility conformance. It makes no network calls
and writes no files. Font updates must include reviewed pinned provenance.
"""

from bisect import bisect_left
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import unicodedata


ROOT = Path(__file__).resolve().parents[1]
DESIGN = Path("composeApp/src/commonMain/kotlin/dev/veyra/ui/design")
RESOURCES = Path("composeApp/src/commonMain/composeResources")
PLACEHOLDER = re.compile(r"\{([0-9]+)}")
KOTLIN_STRING = r'"(?:[^"\\\n]|\\.)*"'
CATALOG_ENTRY = re.compile(
    rf"(?P<key>[A-Z][A-Za-z0-9_]*)\((?P<en>{KOTLIN_STRING}),\s*(?P<ar>{KOTLIN_STRING})\),\s*(?://.*)?"
)
SURFACES = ("canvas", "surface", "surfaceContainer", "surfaceContainerHighest")


class AuditError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise AuditError(message)


def kotlin_literal(literal):
    """Decode the ordinary constant string form used by the central catalogue."""
    result = []
    inner = literal[1:-1]
    index = 0
    escapes = {"t": "\t", "b": "\b", "n": "\n", "r": "\r", "'": "'", '"': '"', "\\": "\\", "$": "$"}
    while index < len(inner):
        char = inner[index]
        if char == "\\":
            index += 1
            require(index < len(inner), "Incomplete Kotlin string escape")
            escape = inner[index]
            if escape == "u":
                digits = inner[index + 1:index + 5]
                require(bool(re.fullmatch(r"[0-9A-Fa-f]{4}", digits)), "Invalid Unicode escape")
                result.append(chr(int(digits, 16)))
                index += 4
            else:
                require(escape in escapes, "Unsupported Kotlin string escape")
                result.append(escapes[escape])
        else:
            if char == "$" and index + 1 < len(inner):
                require(not (inner[index + 1].isalnum() or inner[index + 1] in "_{"), "Catalogue entries must not use Kotlin interpolation")
            result.append(char)
        index += 1
    return "".join(result)


def parse_catalog(source):
    match = re.search(r"enum class TextKey\b[^\{]*\{(.*?)^}", source, re.S | re.M)
    require(match is not None, "TextKey enum not found")
    entries = {}
    for offset, raw in enumerate(match.group(1).splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("//"):
            continue
        entry = CATALOG_ENTRY.fullmatch(line)
        require(entry is not None, f"TextKey body line {offset}: expected one bilingual constant entry")
        key = entry.group("key")
        require(key not in entries, f"Duplicate TextKey {key}")
        values = tuple(kotlin_literal(entry.group(language)) for language in ("en", "ar"))
        require(all(value.strip() for value in values), f"Empty translation for {key}")
        indices = [sorted(int(m.group(1)) for m in PLACEHOLDER.finditer(value)) for value in values]
        require(indices[0] == indices[1], f"Placeholder language mismatch for {key}")
        require(set(indices[0]) == set(range(len(set(indices[0])))), f"Non-contiguous placeholders for {key}")
        for value in values:
            remainder = PLACEHOLDER.sub("", value)
            require("{" not in remainder and "}" not in remainder, f"Malformed placeholder in {key}")
            require(not any("\u202a" <= c <= "\u202e" or "\u2066" <= c <= "\u2069" for c in value), f"Unexpected bidi formatting control in {key}")
        entries[key] = values
    require(entries, "Catalogue has no entries")
    return entries


def audit_catalog(root):
    entries = parse_catalog((root / DESIGN / "AppStrings.kt").read_text(encoding="utf-8"))
    missing = {}
    unformatted = []
    count = 0
    for path in sorted((root / "composeApp/src").rglob("*.kt")):
        source = path.read_text(encoding="utf-8")
        count += 1
        relative = str(path.relative_to(root))
        for key in re.findall(r"\bTextKey\.([A-Z][A-Za-z0-9_]*)", source):
            if key not in entries:
                missing.setdefault(key, set()).add(relative)
        # An ordinary text(key) call must not expose raw placeholders. Tests may
        # intentionally inspect a template, so this check covers Main sources.
        if any(part.endswith("Main") for part in path.parts):
            for key in re.findall(r"\btext\s*\(\s*TextKey\.([A-Z][A-Za-z0-9_]*)\s*\)", source):
                if key in entries and PLACEHOLDER.search(entries[key][0]):
                    unformatted.append(f"{key} in {relative}")
    require(not missing, "Missing TextKeys: " + "; ".join(f"{key} ({', '.join(sorted(paths))})" for key, paths in sorted(missing.items())))
    require(not unformatted, "Parameterized keys used with text(): " + "; ".join(unformatted))
    print(f"PASS catalogue: {len(entries)} bilingual keys; placeholders and references in {count} Kotlin source files")
    return entries


def luminance(argb):
    require(len(argb) == 8 and argb[:2].upper() == "FF", "Contrast contract requires opaque sRGB tokens")
    channels = [int(argb[i:i + 2], 16) / 255 for i in (2, 4, 6)]
    linear = [value / 12.92 if value <= .04045 else ((value + .055) / 1.055) ** 2.4 for value in channels]
    return sum(weight * value for weight, value in zip((.2126, .7152, .0722), linear))


def contrast(first, second):
    low, high = sorted((luminance(first), luminance(second)))
    return (high + .05) / (low + .05)


def contrast_contracts():
    text = [(foreground, background, 4.5)
            for foreground in ("onSurface", "onSurfaceVariant", "primary", "secondary", "error", "success", "warning")
            for background in SURFACES]
    text += [("on" + background[0].upper() + background[1:], background, 4.5)
             for background in ("primary", "primaryContainer", "secondary", "secondaryContainer", "error", "errorContainer")]
    # Current queue rows and the prominent icon button retain these foregrounds
    # on a selected primary-container surface.
    text += [(foreground, "primaryContainer", 4.5) for foreground in ("onSurface", "onSurfaceVariant", "primary")]
    essential = [(foreground, background, 3.0)
                 for foreground in ("outline", "primary")
                 for background in SURFACES + ("primaryContainer", "secondaryContainer", "errorContainer")]
    essential.append(("error", "errorContainer", 3.0))
    return text, essential


def audit_contrast(root):
    source = (root / DESIGN / "VeyraTheme.kt").read_text(encoding="utf-8")
    definition = re.search(r"data class VeyraColorTokens\((.*?)\n\)", source, re.S)
    require(definition is not None, "VeyraColorTokens definition not found")
    names = set(re.findall(r"val (\w+): Color", definition.group(1)))
    palettes = re.findall(r"val (Light|Dark) = VeyraColorTokens\((.*?)\n    \)", source, re.S)
    require({name for name, _ in palettes} == {"Light", "Dark"} and len(palettes) == 2, "Both explicit palettes are required")
    text, essential = contrast_contracts()
    for name, block in palettes:
        tokens = dict(re.findall(r"(\w+) = Color\(0x([0-9A-Fa-f]{8})\)", block))
        require(tokens.keys() == names, f"{name} palette must declare every token as opaque ARGB")
        for value in tokens.values():
            luminance(value)
        for foreground, background, threshold in text + essential:
            ratio = contrast(tokens[foreground], tokens[background])
            require(ratio >= threshold, f"{name} {foreground}/{background}: {ratio:.3f}:1, requires {threshold}:1")
        minimum_text = min(contrast(tokens[fg], tokens[bg]) for fg, bg, _ in text)
        minimum_essential = min(contrast(tokens[fg], tokens[bg]) for fg, bg, _ in essential)
        print(f"PASS {name.lower()} contrast: {len(text)} text pairs (min {minimum_text:.3f}:1), {len(essential)} essential/focus pairs (min {minimum_essential:.3f}:1)")
    print("NOTE outlineSubtle is decorative only; blended, disabled and rendered UI states are outside this static audit")


def u16(data, offset):
    require(0 <= offset and offset + 2 <= len(data), "Truncated font UInt16")
    return struct.unpack_from(">H", data, offset)[0]


def u32(data, offset):
    require(0 <= offset and offset + 4 <= len(data), "Truncated font UInt32")
    return struct.unpack_from(">I", data, offset)[0]


def sfnt_tables(data):
    require(data[:4] == b"\x00\x01\x00\x00", "Expected an ordinary TrueType font")
    count = u16(data, 4)
    require(count > 0 and 12 + count * 16 <= len(data), "Invalid sfnt table directory")
    tables = {}
    for index in range(count):
        tag, _checksum, offset, length = struct.unpack_from(">4sIII", data, 12 + index * 16)
        require(tag not in tables and offset + length <= len(data), "Invalid/duplicate sfnt table")
        tables[tag] = data[offset:offset + length]
    return tables


def unicode_glyphs(tables, required):
    cmap = tables[b"cmap"]
    require(u16(cmap, 0) == 0, "Unsupported cmap version")
    count = u16(cmap, 2)
    require(4 + count * 8 <= len(cmap), "Truncated cmap records")
    glyph_count = u16(tables[b"maxp"], 4)
    mapped = set()
    seen = set()
    for index in range(count):
        platform, encoding, offset = struct.unpack_from(">HHI", cmap, 4 + index * 8)
        if not (platform == 0 or platform == 3 and encoding in (1, 10)) or offset in seen:
            continue
        seen.add(offset)
        format_number = u16(cmap, offset)
        if format_number == 4:
            length = u16(cmap, offset + 2)
            require(offset + length <= len(cmap), "Truncated format-4 cmap")
            subtable = cmap[offset:offset + length]
            segment_bytes = u16(subtable, 6)
            require(segment_bytes > 0 and segment_bytes % 2 == 0, "Invalid cmap segment count")
            segments = segment_bytes // 2
            ends = [u16(subtable, 14 + 2 * i) for i in range(segments)]
            require(ends == sorted(ends), "Unordered cmap end codes")
            start_base = 16 + 2 * segments
            delta_base = start_base + 2 * segments
            range_base = delta_base + 2 * segments
            for codepoint in required:
                if codepoint > 0xFFFF:
                    continue
                segment = bisect_left(ends, codepoint)
                if segment == segments:
                    continue
                start = u16(subtable, start_base + 2 * segment)
                if codepoint < start:
                    continue
                delta = u16(subtable, delta_base + 2 * segment)
                range_offset = u16(subtable, range_base + 2 * segment)
                if range_offset:
                    location = range_base + 2 * segment + range_offset + 2 * (codepoint - start)
                    glyph = u16(subtable, location)
                    if glyph:
                        glyph = (glyph + delta) & 0xFFFF
                else:
                    glyph = (codepoint + delta) & 0xFFFF
                require(glyph < glyph_count, "cmap references a missing glyph")
                if glyph:
                    mapped.add(codepoint)
        elif format_number == 12:
            length = u32(cmap, offset + 4)
            require(offset + length <= len(cmap), "Truncated format-12 cmap")
            subtable = cmap[offset:offset + length]
            groups = u32(subtable, 12)
            require(16 + groups * 12 <= len(subtable), "Invalid cmap groups")
            for group in range(groups):
                start, end, first_glyph = struct.unpack_from(">III", subtable, 16 + group * 12)
                require(start <= end <= 0x10FFFF, "Invalid cmap Unicode range")
                for codepoint in required:
                    if start <= codepoint <= end:
                        glyph = first_glyph + codepoint - start
                        require(glyph < glyph_count, "cmap references a missing glyph")
                        if glyph:
                            mapped.add(codepoint)
    require(seen, "No Unicode cmap found")
    return mapped


def layout_tags(table, offset_field):
    offset = u16(table, offset_field)
    count = u16(table, offset)
    require(offset + 2 + count * 6 <= len(table), "Truncated OpenType layout records")
    return {table[offset + 2 + index * 6:offset + 6 + index * 6] for index in range(count)}


def required_characters(entries):
    # Latin UI, Arabic base letters/marks, both common Arabic digit sets, brand ڤ,
    # and actual resource punctuation. Formatting controls are shaped, not glyphs.
    required = set(range(0x20, 0x7F)) | set(range(0x621, 0x63B)) | set(range(0x640, 0x653))
    required |= set(range(0x660, 0x66A)) | set(range(0x6F0, 0x6FA)) | {0x6A4, 0x66B, 0x66C}
    required |= {ord(char) for values in entries.values() for value in values for char in value
                 if not unicodedata.category(char).startswith("C") and not char.isspace()}
    return required


def audit_fonts(root, entries):
    resources = (root / RESOURCES).resolve()
    manifest = json.loads((resources / "files/licenses/font_manifest.json").read_text(encoding="utf-8"))
    revision = manifest["source_revision"]
    require(bool(re.fullmatch(r"[0-9a-f]{40}", revision)), "Font source must be pinned to a complete Git revision")
    require(manifest["source_repository"] == "https://github.com/google/fonts", "Review unexpected font source repository")
    require(manifest["source_directory"] == "ofl/ibmplexsansarabic", "Review unexpected font source directory")
    require(manifest["family"] == "IBM Plex Sans Arabic" and manifest["modified"] is False, "Review modified/replaced font family")
    require(manifest["license"] == "SIL Open Font License 1.1", "Unexpected font licence")
    files = manifest["files"]
    require(isinstance(files, list) and len(files) == 4, "Expected three font weights and their exact OFL")
    required = required_characters(entries)
    weights = set()
    font_paths = set()
    resource_paths = set()
    found_license = False
    for entry in files:
        relative = entry["resource_path"]
        path = (resources / relative).resolve()
        require(not Path(relative).is_absolute() and ".." not in Path(relative).parts and path.is_relative_to(resources), "Font resource path escapes resources")
        require(relative not in resource_paths, "Duplicate font manifest resource")
        resource_paths.add(relative)
        original = entry["original_filename"]
        require(Path(original).name == original and original not in (".", ".."), "Invalid font source filename")
        expected_url = f"https://raw.githubusercontent.com/google/fonts/{revision}/ofl/ibmplexsansarabic/{original}"
        require(entry["source_url"] == expected_url, f"Unpinned/mismatched source URL for {relative}")
        data = path.read_bytes()
        require(entry["bytes"] == len(data), f"Font resource size mismatch: {relative}")
        require(hashlib.sha256(data).hexdigest() == entry["sha256"], f"Font resource SHA-256 mismatch: {relative}")
        if path.suffix == ".ttf":
            weight = entry["weight"]
            require(weight not in weights and weight in (400, 500, 600), "Expected unique Regular/Medium/SemiBold weights")
            weights.add(weight)
            font_paths.add(relative)
            tables = sfnt_tables(data)
            require(u16(tables[b"OS/2"], 4) == weight, f"Font weight metadata mismatch: {relative}")
            missing = required - unicode_glyphs(tables, required)
            require(not missing, f"{relative} lacks " + ", ".join(f"U+{cp:04X}" for cp in sorted(missing)))
            for tag in (b"GSUB", b"GPOS"):
                require(b"arab" in layout_tags(tables[tag], 4), f"{relative} lacks Arabic {tag.decode()} script support")
            require({b"init", b"medi", b"fina", b"rlig"} <= layout_tags(tables[b"GSUB"], 6), f"{relative} lacks expected Arabic joining features")
            require({b"mark", b"mkmk"} <= layout_tags(tables[b"GPOS"], 6), f"{relative} lacks expected mark positioning features")
            print(f"PASS font {weight}: pinned hash/size, {len(required)} Unicode codepoints, Arabic GSUB/GPOS feature presence")
        else:
            require(original == "OFL.txt" and not found_license, "Unexpected non-font resource in manifest")
            found_license = True
            license_text = data.decode("utf-8")
            require(manifest["copyright"] in license_text, "Font copyright/reserved name missing")
            require("SIL OPEN FONT LICENSE Version 1.1" in license_text and "DISCLAIMER" in license_text, "Incomplete OFL text")
    require(weights == {400, 500, 600} and found_license, "Incomplete bundled font licence/weights")
    actual_fonts = {path.relative_to(resources).as_posix() for path in (resources / "font").glob("*.ttf")}
    require(actual_fonts == font_paths, "Every bundled TTF must be represented by the pinned manifest")
    theme = (root / DESIGN / "VeyraTheme.kt").read_text(encoding="utf-8")
    requested_fonts = set(re.findall(r"Res\.font\.(\w+)", theme))
    require(requested_fonts == {Path(path).stem for path in font_paths}, "Theme font references and manifest disagree")
    print(f"PASS font provenance: Google Fonts {revision}; unchanged OFL included")


def main():
    failures = []
    entries = {}
    try:
        entries = audit_catalog(ROOT)
    except (AuditError, OSError, UnicodeError, KeyError, TypeError) as error:
        failures.append(f"catalogue: {error}")
    for name, action in (("contrast", lambda: audit_contrast(ROOT)), ("fonts", lambda: audit_fonts(ROOT, entries))):
        try:
            action()
        except (ValueError, OSError, KeyError, TypeError, struct.error) as error:
            failures.append(f"{name}: {error}")
    for failure in failures:
        print(f"FAIL {failure}", file=sys.stderr)
    if failures:
        print(f"Static design audit FAILED ({len(failures)} sections).", file=sys.stderr)
        return 1
    print("Static design audit PASSED. Kotlin compilation, rendered Arabic and native accessibility remain separate gates.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
