package dev.veyra.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.veyra.model.AppSettings
import dev.veyra.model.LanguagePreference
import dev.veyra.model.QueueContext
import dev.veyra.model.ThemeMode
import dev.veyra.playback.PlaybackTarget
import dev.veyra.ui.design.AppStrings
import dev.veyra.ui.design.TextKey
import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Authored connected journey for a neutral source recording. Real VeyraApp/AppContainer/Room and
 * production typed commands; the gateway/engine answers are non-audio test doubles. It is not
 * live-source, native, audible-device or platform evidence.
 */
@OptIn(ExperimentalTestApi::class)
class TypedNeutralPlayerJourneyTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val testName = TestName()
    private var fixture: CoreShellTestFixture? = null
    private var mounted by mutableStateOf(true)
    private var viewport by mutableStateOf(DpSize(960.dp, 720.dp))
    private val show = CoreShellTestFixture.show
    private val resolvedTitle = CoreShellTypedTitles().resolvedTitle(show.title)
    private val evidenceRun = UUID.randomUUID().toString()

    @After fun closeFixture() {
        val active = fixture ?: return
        try {
            ui.runOnIdle { mounted = false }
            ui.waitForIdle()
        } finally {
            active.close()
            fixture = null
        }
        assertEquals(0, active.unexpectedNetworkCalls.get())
        assertEquals(0, active.gateway.downloadCalls.get())
        assertTrue(active.externalPages.isEmpty())
    }

    @Test fun neutralSourceRecordingUsesTheCanonicalPlayerProjectionAndPodcastControls() {
        val initial = CoreShellTestFixture.snapshot().copy(activeContext = QueueContext.PODCAST)
        val app = runBlocking { withTimeout(20_000L) { CoreShellTestFixture.create(initial, neutral = true) } }
        fixture = app
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.size(viewport.width, viewport.height)) { if (mounted) VeyraApp(app.container) }
            }
        }
        ui.onNodeWithTag("veyra-app").assertIsDisplayed()
        ui.waitForIdle()

        val beforeAudio = app.container.playback.state.value
        val selection = assertNotNull(beforeAudio.currentSelection)
        assertTrue(selection.target is PlaybackTarget.NeutralRecording)
        assertNull(selection.catalogMedia, "A neutral recording must not fabricate catalogue metadata")
        assertNull(beforeAudio.currentItem)
        assertEquals("neutral-now", selection.occurrenceId.value)
        assertEquals(show.title, selection.requested?.title)
        assertEquals(resolvedTitle, selection.executed?.title)
        // The persistent mini player uses the same canonical selection even without a MediaItem.
        ui.onNodeWithTag("mini-player").assertIsDisplayed()
        ui.onNode(hasText(show.title) and hasAnyAncestor(hasTestTag("mini-player")), useUnmergedTree = true)
            .assertIsDisplayed()

        ui.onNodeWithTag("mini-queue").performClick()
        await { app.container.navigation.value.queueOpen }
        ui.onNodeWithTag("queue-panel").performScrollToNode(hasTestTag("queue-entry-neutral-now"))
        ui.onNode(hasText(show.title) and hasAnyAncestor(hasTestTag("queue-entry-neutral-now")),
            useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithTag("queue-play-neutral-now").performClick()
        await { app.container.playback.state.value.isPlaying &&
            app.container.playback.state.value.currentEntryId == "neutral-now" }
        assertEquals(1, app.gateway.prepareInvocations.get())
        assertEquals(0, app.gateway.prepareFailures.get())
        assertTrue(app.gateway.queries.isEmpty(),
            "An already captured binding is played without a new latest-title query")
        val load = app.engine.loads.single() as CoreShellFixtureEngine.Load.Typed
        assertNull(load.mediaId, "The native load is keyed by occurrence identity, never a synthetic MediaItem")
        assertTrue(load.selection.target is PlaybackTarget.NeutralRecording)

        ui.onNodeWithTag("mini-open-player").performClick()
        await { app.container.navigation.value.playerOpen }
        ui.onNodeWithTag("player-title").assertIsDisplayed().performScrollTo().assertTextEquals(show.title)
        ui.onNode(hasText(resolvedTitle) and hasAnyAncestor(hasTestTag("playback-provenance")),
            useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        ui.onNode(hasText(show.title) and hasAnyAncestor(hasTestTag("playback-provenance")),
            useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        // Controls come from PlaybackControlsMode.PODCAST, not a fabricated episode/catalogue item.
        ui.onNodeWithTag("player-shuffle").assertDoesNotExist()
        ui.onNodeWithTag("player-repeat").assertDoesNotExist()
        ui.onNodeWithTag("player-save").assertDoesNotExist()
        listOf("player-skip-back", "player-toggle", "player-skip-forward").forEach(::assertFullyVisible)
        capture("neutral-source-recording-en-light-player")

        app.container.changeSettings {
            it.copy(language = LanguagePreference.ARABIC, theme = ThemeMode.DARK, textScale = 1.75f)
        }
        await { app.store.state.value.settings.textScale == 1.75f &&
            app.store.state.value.settings.language == LanguagePreference.ARABIC }
        resize(320.dp, 760.dp)
        val arabic = AppStrings(LanguagePreference.ARABIC)
        ui.onNodeWithTag("player-title").performScrollTo()
        listOf("player-skip-back", "player-toggle", "player-skip-forward").forEach(::assertFullyVisible)
        val back = ui.onNodeWithTag("player-skip-back").fetchSemanticsNode().boundsInRoot.center.x
        val toggle = ui.onNodeWithTag("player-toggle").fetchSemanticsNode().boundsInRoot.center.x
        val forward = ui.onNodeWithTag("player-skip-forward").fetchSemanticsNode().boundsInRoot.center.x
        assertTrue(back < toggle && toggle < forward, "Media transport stays LTR inside the RTL shell")
        ui.onNode(hasText(resolvedTitle) and hasAnyAncestor(hasTestTag("playback-provenance")),
            useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        assertTrue(ui.onNodeWithText(arabic.text(TextKey.CurrentAudioResolution)).fetchSemanticsNode().boundsInRoot.width > 0f)
        capture("neutral-source-recording-ar-dark-320-font175-player")

        val after = app.container.playback.state.value
        assertNull(after.currentItem)
        assertEquals(selection.occurrenceId, after.currentSelection?.occurrenceId)
        assertEquals(1, app.engine.loads.size, "Layout and locale changes must not prepare another source")
    }

    private fun resize(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
        ui.runOnIdle { viewport = DpSize(width, height) }
        ui.waitForIdle()
        val actual = ui.onNodeWithTag("veyra-app").getUnclippedBoundsInRoot()
        assertEquals(width, actual.right - actual.left)
        assertEquals(height, actual.bottom - actual.top)
    }

    private fun await(condition: () -> Boolean) {
        ui.waitUntil(timeoutMillis = 10_000L, condition = condition)
        ui.waitForIdle()
    }

    private fun assertFullyVisible(tag: String) {
        val node = ui.onNodeWithTag(tag).assertIsDisplayed()
        val actual = node.getUnclippedBoundsInRoot()
        val clipped = node.fetchSemanticsNode().boundsInRoot
        val app = ui.onNodeWithTag("veyra-app").getUnclippedBoundsInRoot()
        assertTrue(actual.left >= app.left && actual.right <= app.right &&
            actual.top >= app.top && actual.bottom <= app.bottom, "$tag stays inside the viewport")
        assertTrue(actual.right - actual.left >= 48.dp && actual.bottom - actual.top >= 48.dp,
            "$tag keeps a 48dp target")
        assertTrue(clipped.width + 1f >= (actual.right - actual.left).value &&
            clipped.height + 1f >= (actual.bottom - actual.top).value, "$tag is not clipped")
    }

    private fun capture(name: String) {
        val method = checkNotNull(testName.methodName).replace(Regex("[^a-zA-Z0-9_-]"), "-")
        val directory = coreShellOwnedDirectory("composeApp/build/test-evidence/music/core-shell-neutral/$method/$evidenceRun")
        val stem = "${(sequence++).toString().padStart(3, '0')}-$name"
        val png = File(directory, "$stem.png")
        ui.onNodeWithTag("full-player").performMouseInput { moveTo(Offset(1f, 1f)); exit() }
        ui.mainClock.advanceTimeBy(300L)
        ui.waitForIdle()
        val image = ui.onNodeWithTag("full-player").captureToImage()
        val pixels = image.toPixelMap()
        val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) buffered.setRGB(x, y, pixels[x, y].toArgb())
        check(ImageIO.write(buffered, "png", png))
        File(directory, "$stem.txt").writeText("""
            AUTHORED UI FIXTURE — NOT LIVE SOURCE OR NATIVE PLAYBACK EVIDENCE
            Actual VeyraApp/AppContainer/Room with a neutral canonical source recording.
            Deterministic gateway and engine doubles; no sockets, media bytes, decoding or audible output.
            Test: ${TypedNeutralPlayerJourneyTest::class.java.name}.$method
            Capture: ${png.name} (${image.width} x ${image.height} pixels)
        """.trimIndent() + "\n")
    }

    private var sequence = 0
}
