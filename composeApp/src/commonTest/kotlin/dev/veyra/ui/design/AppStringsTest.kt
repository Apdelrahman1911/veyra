package dev.veyra.ui.design

import dev.veyra.model.AppFailure
import dev.veyra.model.DownloadStatus
import dev.veyra.model.FailureCode
import dev.veyra.model.LanguagePreference
import dev.veyra.model.SourceProvider
import dev.veyra.model.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppStringsTest {
    private val english = AppStrings(LanguagePreference.ENGLISH)
    private val arabic = AppStrings(LanguagePreference.ARABIC)
    private val placeholders = Regex("\\{([0-9]+)}")

    @Test fun everyKeyHasBilingualCopyAndTheSameContiguousPlaceholders() {
        TextKey.entries.forEach { key ->
            assertTrue(key.english.isNotBlank(), "$key English")
            assertTrue(key.arabic.isNotBlank(), "$key Arabic")
            val enIndices = placeholders.findAll(key.english).map { it.groupValues[1].toInt() }.sorted().toList()
            val arIndices = placeholders.findAll(key.arabic).map { it.groupValues[1].toInt() }.sorted().toList()
            assertEquals(enIndices, arIndices, "$key placeholders")
            assertEquals(enIndices.distinct().indices.toList(), enIndices.distinct(), "$key contiguous placeholders")
            listOf(english, arabic).forEach { strings ->
                val rendered = strings.format(key, *Array(enIndices.distinct().size) { "argument$it" })
                assertFalse(placeholders.containsMatchIn(rendered), "$key unresolved placeholder")
                if (key != TextKey.InformationUnavailable) {
                    assertTrue(rendered != strings.text(TextKey.InformationUnavailable), "$key unexpectedly fell back")
                }
            }
        }
    }

    @Test fun unknownKeysAndBadArgumentCountsNeverEchoUntrustedInputOrTemplates() {
        listOf(english, arabic).forEach { strings ->
            val fallback = strings.text(TextKey.InformationUnavailable)
            assertEquals(fallback, strings.textOrFallback("https://example.invalid/?token=private"))
            assertEquals(strings.text(TextKey.Unavailable), strings.textOrFallback("unknown_key", TextKey.Unavailable))
            assertEquals(strings.text(TextKey.Listen), strings.textOrFallback("Listen"))
            assertEquals(fallback, strings.format(TextKey.Version))
            assertEquals(fallback, strings.format(TextKey.Version, "1", "unexpected"))
            assertEquals(fallback, strings.format(TextKey.DownloadProgress, "5"))
            assertEquals(fallback, strings.format(TextKey.Listen, "unexpected"))
        }
    }

    @Test fun interpolationIsSinglePassAndInsertedMetadataCannotEscapeItsIsolate() {
        val argument = "Album {1}\u2069\u202E — الحلقة ١٢"
        val actual = english.format(TextKey.SourceAttribution, argument)
        assertEquals("Source: \u2068Album {1} — الحلقة ١٢\u2069", actual)
        assertFalse(actual.contains('\u202E'))
        assertEquals(
            "Downloaded \u2066{1}\u2069 of \u2068second\u2069",
            english.format(TextKey.DownloadProgress, "{1}", "second"),
        )
        assertEquals("الإصدار \u20661.2.3\u2069", arabic.format(TextKey.Version, "1.2.3"))
    }

    @Test fun displayProtectionPreservesCanonicalArabicAndJoiningControls() {
        val original = "مُحَمَّد\u200C\u200D \u061C١٢ — Studio 2026"
        val hostile = "\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069$original"
        assertEquals(original, displayMetadata(hostile))
        assertEquals("\u2068$original\u2069", isolateMetadata(hostile))
        assertEquals("\u2066$original\u2069", isolateLtr(hostile))
        assertTrue(hostile.endsWith(original), "Rendering must not mutate the original immutable value")
    }

    @Test fun arabicIntegerCategoriesFollowCldrIncludingNegativeAndLargeValues() {
        val examples = mapOf(
            0L to ArabicPluralCategory.Zero,
            1L to ArabicPluralCategory.One,
            -1L to ArabicPluralCategory.One,
            2L to ArabicPluralCategory.Two,
            -2L to ArabicPluralCategory.Two,
            3L to ArabicPluralCategory.Few,
            10L to ArabicPluralCategory.Few,
            11L to ArabicPluralCategory.Many,
            99L to ArabicPluralCategory.Many,
            100L to ArabicPluralCategory.Other,
            101L to ArabicPluralCategory.Other,
            102L to ArabicPluralCategory.Other,
            103L to ArabicPluralCategory.Few,
            -111L to ArabicPluralCategory.Many,
            Long.MIN_VALUE to ArabicPluralCategory.Few,
            Long.MAX_VALUE to ArabicPluralCategory.Few,
        )
        examples.forEach { (count, category) -> assertEquals(category, arabicPluralCategory(count), "$count") }
    }

    @Test fun quantitiesHaveArabicZeroSingularDualFewManyAndOtherForms() {
        val expected = mapOf(0 to "لا حلقات", 1 to "حلقة واحدة", 2 to "حلقتان", 3 to "3 حلقات", 11 to "11 حلقة", 102 to "102 حلقة")
        expected.forEach { (count, text) -> assertEquals(text, displayMetadata(arabic.quantity(QuantityKey.Episodes, count))) }
        assertEquals("1 track", displayMetadata(english.quantity(QuantityKey.Tracks, 1)))
        assertEquals("2 tracks", displayMetadata(english.quantity(QuantityKey.Tracks, 2)))
        assertEquals("حلقة واحدة", arabic.format(TextKey.EpisodeCount, "1"))
        QuantityKey.entries.forEach { key ->
            listOf(0, 1, 2, 3, 11, 102).forEach { count ->
                listOf(english, arabic).forEach { strings ->
                    val result = strings.quantity(key, count)
                    assertTrue(result.isNotBlank())
                    assertFalse(placeholders.containsMatchIn(result))
                }
            }
        }
    }

    @Test fun digitChoiceIsExplicitAndDoesNotReverseTimeOrMutateMetadata() {
        val indic = AppStrings(LanguagePreference.ARABIC, arabicIndicDigits = true)
        assertEquals("123", arabic.number(123))
        assertEquals("١٢٣", indic.number(123))
        assertEquals("-٩٢٢٣٣٧٢٠٣٦٨٥٤٧٧٥٨٠٨", indic.number(Long.MIN_VALUE))
        assertEquals("١١ حلقة", displayMetadata(indic.quantity(QuantityKey.Episodes, 11)))
        assertEquals("\u20661:01:01\u2069", arabic.mediaTime(3_661_000))
        assertEquals("\u2066١:٠١:٠١\u2069", indic.mediaTime(3_661_000))
        assertEquals("Original 2026", displayMetadata("Original 2026"))
    }

    @Test fun mediaTimeUsesActualDurationAndDistinguishesUnknownFromZero() {
        assertEquals(english.text(TextKey.UnknownDuration), english.mediaTime(null))
        assertEquals("\u20660:00\u2069", english.mediaTime(-100))
        assertEquals("\u20660:00\u2069", english.mediaTime(999))
        assertEquals("\u20660:59\u2069", english.mediaTime(59_999))
        assertEquals("\u20661:00\u2069", english.mediaTime(60_000))
        assertEquals("\u20662562047788015:12:55\u2069", english.mediaTime(Long.MAX_VALUE))
        assertEquals("Version \u20661:00\u2069", english.format(TextKey.Version, english.mediaTime(60_000)))
    }

    @Test fun spokenDurationsAreWordsRatherThanColonPunctuation() {
        assertEquals("1 hour, 1 minute, 1 second", displayMetadata(english.spokenDuration(3_661_000)))
        assertEquals("ساعة واحدة، دقيقة واحدة، ثانية واحدة", arabic.spokenDuration(3_661_000))
        assertEquals("0 seconds", displayMetadata(english.spokenDuration(0)))
        assertEquals("صفر ثانية", arabic.spokenDuration(0))
        assertEquals(arabic.text(TextKey.UnknownDuration), arabic.spokenDuration(null))
    }

    @Test fun podcastRecordingChangeNoticeKeepsHistoricalTimeSeparateFromStartingOver() {
        val indic = AppStrings(LanguagePreference.ARABIC, arabicIndicDigits = true)
        assertEquals(
            "Saved position: \u20661:32\u2069, from a previous recording. This recording will play from the beginning.",
            english.format(TextKey.PodcastProgressRecordingChanged, english.mediaTime(92_000)),
        )
        assertEquals(
            "موضع الاستماع المحفوظ: \u2066١:٣٢\u2069، من تسجيل سابق. سيبدأ تشغيل هذا التسجيل من البداية.",
            indic.format(TextKey.PodcastProgressRecordingChanged, indic.mediaTime(92_000)),
        )
    }

    @Test fun unverifiedPodcastRecordingNoticeKeepsResumeConditionalAndSupportsSpokenPosition() {
        assertEquals(
            "Saved position: \u20661:32\u2069. Playback resumes there only if the selected recording matches.",
            english.format(TextKey.PodcastProgressRecordingUnverified, english.mediaTime(92_000)),
        )
        assertEquals(
            "موضع الاستماع المحفوظ: \u20661:32\u2069. يُستأنف التشغيل من هذا الموضع فقط إذا تطابق التسجيل المختار مع التسجيل السابق.",
            arabic.format(TextKey.PodcastProgressRecordingUnverified, arabic.mediaTime(92_000)),
        )
        listOf(english, arabic).forEach { strings ->
            listOf(TextKey.PodcastProgressRecordingChanged, TextKey.PodcastProgressRecordingUnverified).forEach { key ->
                val spokenPosition = strings.spokenDuration(92_000)
                assertEquals(
                    strings.text(key).replace("{0}", displayMetadata(spokenPosition)),
                    displayMetadata(strings.format(key, spokenPosition)),
                )
                assertEquals(strings.text(TextKey.InformationUnavailable), strings.format(key))
                assertEquals(strings.text(TextKey.InformationUnavailable), strings.format(key, "1:32", "extra"))
            }
        }
    }

    @Test fun byteSizesUseHonestBinaryUnitsAndCannotOverflow() {
        val examples = mapOf(
            0L to "0 B",
            1_000L to "1000 B",
            1_023L to "1023 B",
            1_024L to "1 KiB",
            1_536L to "1.5 KiB",
            1_048_576L to "1 MiB",
            1_099_511_627_776L to "1 TiB",
            Long.MAX_VALUE to "8388608 TiB",
        )
        examples.forEach { (bytes, expected) -> assertEquals(expected, displayMetadata(english.byteSize(bytes)), "$bytes") }
        assertEquals(english.text(TextKey.Unknown), english.byteSize(-1))
        assertEquals("١٫٥ KiB", displayMetadata(AppStrings(LanguagePreference.ARABIC, true).byteSize(1_536)))
    }

    @Test fun everyTypedFailureIsLocalizedWithoutBackendDetails() {
        val privateDetail = "private-token https://example.invalid/?credential=do-not-render"
        FailureCode.entries.forEach { code ->
            listOf(english, arabic).forEach { strings ->
                val actual = strings.errorFor(AppFailure(code, privateDetail, retryAfterMs = 30_000))
                assertTrue(actual.isNotBlank(), code.name)
                assertFalse(actual.contains("private-token") || actual.contains("https://") || actual.contains("credential"), code.name)
                assertEquals(strings.errorFor(AppFailure(code)), actual, "Detail must not affect public copy")
            }
        }
        listOf(english, arabic).forEach { strings ->
            assertEquals(strings.text(TextKey.ForegroundRequired),
                strings.errorFor(AppFailure(FailureCode.BACKGROUND_RESTRICTED)))
        }
    }

    @Test fun unknownDownloadOwnerDrainRequiresFullAppRestartInBothLanguages() {
        val failure = AppFailure(
            FailureCode.BACKGROUND_RESTRICTED,
            detail = "download_owner_drain_failed_restart_required",
        )
        assertEquals(
            "Download recovery requires restarting Veyra. Quit the app completely, reopen it, then retry.",
            english.errorFor(failure),
        )
        assertEquals(
            "تتطلّب استعادة التنزيلات إعادة تشغيل ڤيرا. أغلق التطبيق بالكامل، ثم افتحه مجددًا وأعِد المحاولة.",
            arabic.errorFor(failure),
        )
        listOf(english, arabic).forEach { strings ->
            assertEquals(strings.text(TextKey.DownloadRecoveryRestartRequired), strings.errorFor(failure))
            assertEquals(strings.errorFor(failure), strings.errorFor(failure.copy(retryAfterMs = 30_000)))
            assertFalse(strings.errorFor(failure).contains(failure.detail!!))
        }
    }

    @Test fun downloadRestartCopyRequiresTheExactCodeAndCategoryWithoutEchoingUnknownDetails() {
        val trustedCode = "download_owner_drain_failed_restart_required"
        val unknownDetails = listOf(
            null, "", " $trustedCode", "$trustedCode ", trustedCode.uppercase(),
            "prefix_$trustedCode", "${trustedCode}_suffix", "$trustedCode\n",
            "$trustedCode https://example.invalid/?credential=do-not-render",
        )
        listOf(english, arabic).forEach { strings ->
            unknownDetails.forEach { detail ->
                assertEquals(
                    strings.text(TextKey.ForegroundRequired),
                    strings.errorFor(AppFailure(FailureCode.BACKGROUND_RESTRICTED, detail)),
                    "Only the exact allowlisted code may select restart copy",
                )
            }
            FailureCode.entries.filter { it != FailureCode.BACKGROUND_RESTRICTED }.forEach { code ->
                assertEquals(
                    strings.errorFor(AppFailure(code)),
                    strings.errorFor(AppFailure(code, trustedCode)),
                    "The same detail under another failure category must not change public copy",
                )
            }
        }
    }

    @Test fun allStatusAndPreferenceValuesHaveLocalizedLabels() {
        listOf(english, arabic).forEach { strings ->
            DownloadStatus.entries.forEach { assertTrue(strings.downloadStatus(it).isNotBlank()) }
            SourceProvider.entries.forEach { assertTrue(strings.sourceName(it).isNotBlank()) }
            LanguagePreference.entries.forEach { assertTrue(strings.languageName(it).isNotBlank()) }
            ThemeMode.entries.forEach { assertTrue(strings.themeName(it).isNotBlank()) }
        }
        assertTrue(arabic.isRtl)
        assertFalse(english.isRtl)
        assertEquals(LanguagePreference.ENGLISH, AppStrings(LanguagePreference.SYSTEM).language)
    }
}
