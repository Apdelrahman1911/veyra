package dev.veyra.ui.settings

import dev.veyra.model.AppSettings
import dev.veyra.model.LanguagePreference
import dev.veyra.model.ThemeMode
import dev.veyra.playback.EngineCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure settings contracts only; not native settings application or a rendered accessibility test. */
class SettingsActionTest {
    private val original = AppSettings(
        language = LanguagePreference.ARABIC, theme = ThemeMode.DARK, textScale = 1.2f,
        offlineOnly = true, speed = 1.4f, skipBackSeconds = 7, skipForwardSeconds = 19,
        showSpeeds = mapOf("show" to 1.75f), crossfadeEnabled = true, crossfadeDurationMs = 4_567,
        downloadParallelism = 4,
    )

    @Test fun everyActionChangesOnlyItsOwnField() {
        val examples = listOf(
            SettingsAction.SetLanguage(LanguagePreference.ENGLISH) to original.copy(language = LanguagePreference.ENGLISH),
            SettingsAction.SetTheme(ThemeMode.LIGHT) to original.copy(theme = ThemeMode.LIGHT),
            SettingsAction.SetTextScale(1.5f) to original.copy(textScale = 1.5f),
            SettingsAction.StepTextScale(1) to original.copy(textScale = 1.25f),
            SettingsAction.SetOfflineOnly(false) to original.copy(offlineOnly = false),
            SettingsAction.SetPodcastSpeed(1.25f) to original.copy(speed = 1.25f),
            SettingsAction.SetSkipBack(15) to original.copy(skipBackSeconds = 15),
            SettingsAction.SetSkipForward(30) to original.copy(skipForwardSeconds = 30),
            SettingsAction.SetCrossfadeEnabled(false) to original.copy(crossfadeEnabled = false),
            SettingsAction.SetCrossfadeDuration(3_000) to original.copy(crossfadeDurationMs = 3_000),
            SettingsAction.SetDownloadParallelism(2) to original.copy(downloadParallelism = 2),
        )
        examples.forEach { (action, expected) -> assertEquals(expected, action.applyTo(original), action.toString()) }
        assertEquals(mapOf("show" to 1.75f), original.showSpeeds, "No action mutates a captured settings object")
    }

    @Test fun staleScreenIntentMergesWithLatestPersistedSettings() {
        val pending = SettingsAction.SetCrossfadeDuration(5_000)
        val latest = original.copy(language = LanguagePreference.ENGLISH, offlineOnly = false,
            showSpeeds = original.showSpeeds + ("new-show" to .75f), downloadParallelism = 1)
        assertEquals(latest.copy(crossfadeDurationMs = 5_000), pending.applyTo(latest))
    }

    @Test fun rapidTextStepsOperateOnCurrentValueRatherThanACapturedSliderSnapshot() {
        val step = SettingsAction.StepTextScale(1)
        val first = step.applyTo(AppSettings(textScale = 1f))
        assertEquals(1.05f, first.textScale)
        assertEquals(1.1f, step.applyTo(first).textScale)
        assertEquals(2f, step.applyTo(AppSettings(textScale = 2f)).textScale)
        assertEquals(.75f, SettingsAction.StepTextScale(-1).applyTo(AppSettings(textScale = .75f)).textScale)
    }

    @Test fun invalidSettingsActionsAreRejectedInsteadOfSilentlyResettingOtherFields() {
        val invalid = listOf(
            SettingsAction.SetTextScale(Float.NaN), SettingsAction.SetTextScale(Float.POSITIVE_INFINITY),
            SettingsAction.SetTextScale(.74f), SettingsAction.SetTextScale(2.01f), SettingsAction.StepTextScale(0),
            SettingsAction.SetPodcastSpeed(.49f), SettingsAction.SetPodcastSpeed(2.01f), SettingsAction.SetPodcastSpeed(Float.NaN),
            SettingsAction.SetSkipBack(0), SettingsAction.SetSkipForward(301),
            SettingsAction.SetCrossfadeDuration(99), SettingsAction.SetCrossfadeDuration(30_001),
            SettingsAction.SetDownloadParallelism(0), SettingsAction.SetDownloadParallelism(5),
        )
        invalid.forEach { action -> assertFailsWith<IllegalArgumentException>(action.toString()) { action.applyTo(original) } }
    }

    @Test fun customDurationAcceptsBoundedExactDecimalValues() {
        val cases = mapOf("0.1" to 100L, "0.100" to 100L, "0.125" to 125L, ".5" to 500L,
            "2" to 2_000L, "03" to 3_000L, "30" to 30_000L, "30.000" to 30_000L, "1,25" to 1_250L)
        cases.forEach { (text, expected) -> assertEquals(expected, parseCrossfadeDurationMs(text), text) }
    }

    @Test fun customDurationAcceptsArabicDigitsAndDecimalSeparator() {
        val cases = mapOf("٣٫٥" to 3_500L, "۰٫۱۲۵" to 125L, " ٢.٠٥٠ " to 2_050L, "٣٠" to 30_000L)
        cases.forEach { (text, expected) -> assertEquals(expected, parseCrossfadeDurationMs(text), text) }
    }

    @Test fun malformedOutOfRangeAndAmbiguousDurationValuesAreRejected() {
        listOf("", " ", ".", "3.", "0", "0.099", "30.001", "31", "-1", "+2", "1e1", "NaN", "Infinity",
            "1.0000", "1٬000", "2 seconds", "3\u2069", "1".repeat(100)).forEach {
            assertNull(parseCrossfadeDurationMs(it), it)
        }
    }

    @Test fun customDurationRoundTripsWithoutFloatRoundingOrTranslatedMetadata() {
        listOf(100L, 101L, 125L, 999L, 1_000L, 1_234L, 3_456L, 29_999L, 30_000L).forEach { milliseconds ->
            assertEquals(milliseconds, parseCrossfadeDurationMs(crossfadeDurationText(milliseconds)))
        }
        assertEquals("3", crossfadeDurationText(3_000))
        assertEquals("2.05", crossfadeDurationText(2_050))
    }

    @Test fun speedChoicesRespectNativeRangeAndKeepASupportedCustomPreference() {
        val capabilities = EngineCapabilities(minSpeed = .75f, maxSpeed = 1.25f)
        assertEquals(listOf(.75f, 1f, 1.25f), supportedPodcastSpeeds(capabilities, 1.75f))
        assertEquals(listOf(.75f, .9f, 1f, 1.25f), supportedPodcastSpeeds(capabilities, .9f))
        assertTrue(supportedPodcastSpeeds(capabilities.copy(minSpeed = Float.NaN), 1f).isEmpty())
    }
}
