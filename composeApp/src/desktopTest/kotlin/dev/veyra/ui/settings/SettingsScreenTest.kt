package dev.veyra.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.veyra.model.AppSettings
import dev.veyra.model.LanguagePreference
import dev.veyra.model.ThemeMode
import dev.veyra.playback.EngineCapabilities
import dev.veyra.ui.design.AppStrings
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings presentation/intent fixtures only. No Room, source, player, network or runtime installer
 * is constructed. Run through the approved repository-owned display/cache helper. Bounds and
 * semantics assertions are not native screen-reader, Arabic-shaping or audible-output evidence.
 */
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val english = AppStrings(LanguagePreference.ENGLISH)
    private val arabic = AppStrings(LanguagePreference.ARABIC)
    private val supported = EngineCapabilities(supportsCrossfade = true)

    @Composable
    private fun Frame(
        language: LanguagePreference = LanguagePreference.ENGLISH,
        dark: Boolean = false,
        fontScale: Float = 1f,
        width: Dp = 360.dp,
        content: @Composable () -> Unit,
    ) {
        CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
            VeyraTheme(darkTheme = dark, language = language) {
                Surface {
                    Box(Modifier.width(width).height(760.dp).testTag("settings-test-frame")) { content() }
                }
            }
        }
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag(tag))
    }

    @Test fun unsupportedCrossfadeDoesNotOfferEnableOrWriteAnythingOnComposition() {
        val actions = mutableListOf<SettingsAction>()
        compose.setContent { Frame {
            SettingsScreen(AppSettings(), supported.copy(supportsCrossfade = false), null, actions::add, {}, {})
        } }
        compose.onNodeWithTag("settings-crossfade").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("settings-crossfade-unavailable")
            .assertTextEquals(english.text(TextKey.CrossfadeUnsupported))
        listOf(2_000, 3_000, 4_000, 5_000).forEach { duration ->
            scrollTo("settings-crossfade-duration-$duration")
            compose.onNodeWithTag("settings-crossfade-duration-$duration").assertIsNotEnabled()
        }
        scrollTo("settings-crossfade-custom")
        compose.onNodeWithTag("settings-crossfade-custom").assertIsNotEnabled()
        scrollTo("settings-crossfade-apply")
        compose.onNodeWithTag("settings-crossfade-apply").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun unsupportedSavedCrossfadeCanBeTurnedOffButCannotBeReenabled() {
        val actions = mutableListOf<SettingsAction>()
        var settings by mutableStateOf(AppSettings(crossfadeEnabled = true))
        compose.setContent { Frame {
            SettingsScreen(settings, supported.copy(supportsCrossfade = false), null, { action ->
                actions += action
                settings = action.applyTo(settings)
            }, {}, {})
        } }
        compose.onNodeWithTag("settings-crossfade-unavailable")
            .assertTextEquals(english.text(TextKey.CrossfadeSavedUnsupported))
        compose.onNodeWithTag("settings-crossfade").assertIsOn().assertIsEnabled().performClick()
        compose.onNodeWithTag("settings-crossfade").assertIsOff().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf(SettingsAction.SetCrossfadeEnabled(false)), actions) }
    }

    @Test fun allFourPresetsEmitDurationOnlyAgainstTheLatestPersistedSettings() {
        val screenSnapshot = AppSettings(crossfadeDurationMs = 3_000)
        var latest = screenSnapshot.copy(language = LanguagePreference.ARABIC, offlineOnly = true,
            showSpeeds = mapOf("new-show" to 1.75f), downloadParallelism = 4)
        val initialLatest = latest
        val actions = mutableListOf<SettingsAction>()
        compose.setContent { Frame {
            SettingsScreen(screenSnapshot, supported, null, { action ->
                actions += action
                latest = action.applyTo(latest)
            }, {}, {})
        } }
        listOf(2_000L, 3_000L, 4_000L, 5_000L).forEach { duration ->
            scrollTo("settings-crossfade-duration-$duration")
            compose.onNodeWithTag("settings-crossfade-duration-$duration").assertIsEnabled().performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf(2_000L, 3_000L, 4_000L, 5_000L).map { SettingsAction.SetCrossfadeDuration(it) }, actions)
            assertEquals(initialLatest.copy(crossfadeDurationMs = 5_000), latest)
            assertEquals(false, latest.crossfadeEnabled, "Choosing a duration must not silently enable native playback")
        }
    }

    @Test fun arabicCustomDurationReportsLocalErrorAndAppliesExactMillisecondsOnDone() {
        val actions = mutableListOf<SettingsAction>()
        var settings by mutableStateOf(AppSettings())
        compose.setContent { Frame(language = LanguagePreference.ARABIC) {
            SettingsScreen(settings, supported, null, { action ->
                actions += action
                settings = action.applyTo(settings)
            }, {}, {})
        } }
        scrollTo("settings-crossfade-custom")
        compose.onNodeWithTag("settings-crossfade-custom").performTextReplacement("٣٠٫٠٠١")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, arabic.text(TextKey.CrossfadeDurationInvalid)))
        scrollTo("settings-crossfade-apply")
        compose.onNodeWithTag("settings-crossfade-apply").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }

        scrollTo("settings-crossfade-custom")
        compose.onNodeWithTag("settings-crossfade-custom").performTextReplacement("٣٫١٢٥").performImeAction()
        compose.runOnIdle {
            assertEquals(listOf(SettingsAction.SetCrossfadeDuration(3_125)), actions)
            assertEquals(3_125L, settings.crossfadeDurationMs)
        }
        scrollTo("settings-crossfade-apply")
        compose.onNodeWithTag("settings-crossfade-apply").assertIsNotEnabled()
    }

    @Test fun externallyPersistedDurationReplacesAnUnappliedDraft() {
        val actions = mutableListOf<SettingsAction>()
        var settings by mutableStateOf(AppSettings(crossfadeDurationMs = 3_000))
        compose.setContent { Frame { SettingsScreen(settings, supported, null, actions::add, {}, {}) } }
        scrollTo("settings-crossfade-custom")
        compose.onNodeWithTag("settings-crossfade-custom").performTextReplacement("6.25")
        compose.runOnIdle { settings = settings.copy(crossfadeDurationMs = 4_500) }
        compose.onNodeWithTag("settings-crossfade-custom").assertTextContains("4.5")
        scrollTo("settings-crossfade-apply")
        compose.onNodeWithTag("settings-crossfade-apply").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun podcastControlsRespectNativeSpeedRangeAndKeepCustomSkipChoices() {
        val actions = mutableListOf<SettingsAction>()
        val settings = AppSettings(speed = .9f, skipBackSeconds = 7, skipForwardSeconds = 19,
            showSpeeds = mapOf("saved-show" to 1.75f))
        compose.setContent { Frame {
            SettingsScreen(settings, supported.copy(minSpeed = .75f, maxSpeed = 1.25f), null, actions::add, {}, {})
        } }
        scrollTo("settings-podcast-speed-0.9")
        compose.onNodeWithTag("settings-podcast-speed-0.9").assertIsSelected()
        compose.onNodeWithTag("settings-podcast-speed-0.5").assertDoesNotExist()
        compose.onNodeWithTag("settings-podcast-speed-1.5").assertDoesNotExist()
        scrollTo("settings-podcast-speed-1.25")
        compose.onNodeWithTag("settings-podcast-speed-1.25").performClick()
        scrollTo("settings-SkipBackSetting-7")
        compose.onNodeWithTag("settings-SkipBackSetting-7").assertIsSelected().performClick()
        scrollTo("settings-SkipForwardSetting-19")
        compose.onNodeWithTag("settings-SkipForwardSetting-19").assertIsSelected().performClick()
        compose.runOnIdle {
            assertEquals(listOf(SettingsAction.SetPodcastSpeed(1.25f), SettingsAction.SetSkipBack(7),
                SettingsAction.SetSkipForward(19)), actions)
            assertEquals(mapOf("saved-show" to 1.75f), settings.showSpeeds)
        }
    }

    @Test fun textSizeHasNondragRelativeControlsAndHonestBoundaries() {
        val actions = mutableListOf<SettingsAction>()
        var settings by mutableStateOf(AppSettings(textScale = 1f))
        compose.setContent { Frame {
            SettingsScreen(settings, supported, null, { action ->
                actions += action
                settings = action.applyTo(settings)
            }, {}, {})
        } }
        scrollTo("settings-larger-text")
        compose.onNodeWithTag("settings-larger-text").performClick().performClick()
        compose.runOnIdle { assertEquals(1.1f, settings.textScale) }
        scrollTo("settings-text-200")
        compose.onNodeWithTag("settings-text-200").performClick().assertIsSelected()
        compose.onNodeWithTag("settings-larger-text").assertIsNotEnabled()
        compose.runOnIdle { settings = settings.copy(textScale = .75f) }
        scrollTo("settings-smaller-text")
        compose.onNodeWithTag("settings-smaller-text").assertIsNotEnabled()
        scrollTo("settings-text-100")
        compose.onNodeWithTag("settings-text-100").performClick().assertIsSelected()
        compose.runOnIdle {
            assertEquals(listOf(SettingsAction.StepTextScale(1), SettingsAction.StepTextScale(1),
                SettingsAction.SetTextScale(2f), SettingsAction.SetTextScale(1f)), actions)
        }
    }

    @Test fun languageThemeOfflineAndParallelismRemainIndependentSettingsIntents() {
        val actions = mutableListOf<SettingsAction>()
        compose.setContent { Frame {
            SettingsScreen(AppSettings(), supported, null, actions::add, {}, {})
        } }
        listOf("settings-language-ARABIC", "settings-theme-DARK", "settings-offline", "settings-parallelism-4")
            .forEach { tag -> scrollTo(tag); compose.onNodeWithTag(tag).performClick() }
        compose.runOnIdle {
            assertEquals(listOf(SettingsAction.SetLanguage(LanguagePreference.ARABIC),
                SettingsAction.SetTheme(ThemeMode.DARK), SettingsAction.SetOfflineOnly(true),
                SettingsAction.SetDownloadParallelism(4)), actions)
        }
    }

    @Test fun storageAndLicensesInvokeRealCallbacksWithoutChangingSettingsOrInventingUsage() {
        val actions = mutableListOf<SettingsAction>()
        var storage by mutableStateOf<Long?>(null)
        var manageCalls = 0
        var licenseCalls = 0
        compose.setContent { Frame {
            SettingsScreen(AppSettings(), supported, storage, actions::add, { manageCalls++ }, { licenseCalls++ })
        } }
        scrollTo("settings-storage-value")
        compose.onNodeWithTag("settings-storage-value").assertTextEquals(english.text(TextKey.StorageUsageUnavailable))
        compose.runOnIdle { storage = 4L * 1024 * 1024 }
        compose.onNodeWithTag("settings-storage-value")
            .assertTextEquals(english.format(TextKey.StorageUsed, english.byteSize(4L * 1024 * 1024)))
        scrollTo("settings-manage-storage")
        compose.onNodeWithTag("settings-manage-storage").performClick()
        scrollTo("settings-licenses")
        compose.onNodeWithTag("settings-licenses").performClick()
        compose.runOnIdle {
            assertEquals(1, manageCalls)
            assertEquals(1, licenseCalls)
            assertTrue(actions.isEmpty())
        }
    }

    @Test fun aFocusedSettingsButtonSupportsKeyboardActivation() {
        val actions = mutableListOf<SettingsAction>()
        compose.setContent { Frame { SettingsScreen(AppSettings(), supported, null, actions::add, {}, {}) } }
        val tag = "settings-crossfade-duration-4000"
        scrollTo(tag)
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }.assertIsFocused()
        compose.onNodeWithTag(tag).performKeyInput { keyDown(Key.Spacebar); keyUp(Key.Spacebar) }
        compose.runOnIdle { assertEquals(listOf(SettingsAction.SetCrossfadeDuration(4_000)), actions) }
    }

    @Test fun arabicTwoHundredPercentControlsStayInsideANarrowScrollableFrame() {
        var direction: LayoutDirection? = null
        compose.setContent {
            Frame(language = LanguagePreference.ARABIC, dark = true, fontScale = 2f, width = 320.dp) {
                val currentDirection = LocalLayoutDirection.current
                SideEffect { direction = currentDirection }
                SettingsScreen(AppSettings(language = LanguagePreference.ARABIC, textScale = 2f), supported, null, {}, {}, {})
            }
        }
        val tags = listOf("settings-crossfade", "settings-crossfade-duration-2000", "settings-crossfade-custom",
            "settings-crossfade-apply", "settings-language-ARABIC", "settings-theme-DARK", "settings-text-200",
            "settings-offline", "settings-parallelism-4", "settings-manage-storage", "settings-licenses")
        tags.forEach { tag ->
            scrollTo(tag)
            // Clipped semantics bounds would hide a control overflowing beyond the viewport.
            val frame = compose.onNodeWithTag("settings-test-frame").getUnclippedBoundsInRoot()
            val control = compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(control.left >= frame.left && control.right <= frame.right, "$tag must remain inside the narrow frame")
            assertTrue(control.width >= 48.dp && control.height >= 48.dp, "$tag keeps a minimum 48dp control target")
        }
        compose.runOnIdle { assertEquals(LayoutDirection.Rtl, direction) }
    }
}
