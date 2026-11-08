package dev.veyra.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.InternalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.DesktopComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.veyra.model.AppFailure
import dev.veyra.model.AppSettings
import dev.veyra.model.AppSnapshot
import dev.veyra.model.DownloadStatus
import dev.veyra.model.ExecutionOccurrenceId
import dev.veyra.model.ExecutionQueue
import dev.veyra.model.FailureCode
import dev.veyra.model.FirstResultReceipt
import dev.veyra.model.LanguagePreference
import dev.veyra.model.OccurrenceSelectionState
import dev.veyra.model.QueueContext
import dev.veyra.model.RepeatMode
import dev.veyra.model.ThemeMode
import dev.veyra.playback.PlaybackSelection
import dev.veyra.ui.design.AppStrings
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.music.LibrarySection
import java.awt.image.BufferedImage
import java.awt.Component
import java.awt.event.KeyEvent as AwtKeyEvent
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Connected UI fixture tests: actual VeyraApp/AppContainer/Room, deterministic engine/gateway.
 * No live discovery, transfers, real media, native decoding or audible playback is asserted.
 * Run only through the approved display/cache guard, after the lead grants the heavy-build slot.
 */
@OptIn(ExperimentalTestApi::class)
class CoreShellFlowsTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val testName = TestName()
    private val english = AppStrings(LanguagePreference.ENGLISH)
    private var fixture: CoreShellTestFixture? = null
    private var mounted by mutableStateOf(true)
    private var viewport by mutableStateOf(DpSize(960.dp, 720.dp))
    private val music = CoreShellTestFixture.music
    private val episode = CoreShellTestFixture.episode
    private val playlist = CoreShellTestFixture.playlist
    private val evidenceRun = UUID.randomUUID().toString()
    private var evidenceSequence = 0
    private val keyDispatches = mutableListOf<String>()

    @After fun closeFixture() {
        val active = fixture ?: return
        try {
            ui.runOnIdle { mounted = false }
            ui.waitForIdle()
        } finally {
            active.close()
            fixture = null
        }
        assertEquals(0, active.unexpectedNetworkCalls.get(), "Authored UI fixtures must not reach catalog/DNS/HTTP")
        assertEquals(0, active.gateway.downloadCalls.get(), "No UI fixture may acquire media bytes")
        assertTrue(active.externalPages.isEmpty(), "No browser is opened by these fixtures")
    }

    @Test fun queueInspectionDoesNotActivateAudioAndExplicitOccurrenceSelectionUsesTheSinglePlayer() {
        val app = show()
        val savedMusic = canonicalQueue(app, QueueContext.MUSIC)
        assertEquals(2L, savedMusic.editRevision, "Seed uses exactly one replace admission and one queue edit")
        assertFalse(app.container.playback.state.value.desiredPlaying)
        assertTrue(app.engine.loads.isEmpty())

        ui.onNodeWithTag("mini-queue").performClick()
        await { app.container.navigation.value.queueOpen }
        ui.onNodeWithTag("view-queue-PODCAST").performClick()
        await { app.container.navigation.value.viewedQueue == QueueContext.PODCAST }
        assertEquals(QueueContext.MUSIC, app.container.playback.state.value.activeContext)
        assertEquals(savedMusic, canonicalQueue(app, QueueContext.MUSIC))
        assertTrue(app.engine.loads.isEmpty())
        assertTrue(app.gateway.queries.isEmpty())
        ui.onNode(hasText(music.title) and hasAnyAncestor(hasTestTag("mini-player")), useUnmergedTree = true).assertIsDisplayed()

        queueScrollTo("queue-play-episode-now")
        ui.onNodeWithTag("queue-play-episode-now").performClick()
        await { app.container.playback.state.value.currentEntryId == "episode-now" && app.container.playback.state.value.isPlaying }
        assertEquals(QueueContext.PODCAST, app.store.state.value.activeContext)
        assertEquals(savedMusic, canonicalQueue(app, QueueContext.MUSIC))
        assertEquals(listOf(episode.title), app.gateway.queries.toList())
        assertEquals(episode.id, app.engine.loads.single().mediaId)

        ui.onNodeWithTag("mini-open-player").performClick()
        await { app.container.navigation.value.playerOpen }
        ui.onNodeWithTag("player-title").assertTextEquals(episode.title)
        ui.onNodeWithTag("player-shuffle").assertDoesNotExist()
        ui.onNodeWithTag("player-repeat").assertDoesNotExist()
        listOf("player-seek", "player-toggle", "player-skip-back", "player-skip-forward").forEach(::assertFullyVisible)
        assertEquals(1, app.engine.loads.size, "Opening the player must not prepare a second engine/source")
        capture("fixture-shell-en-light-episode-player")
    }

    @Test fun occurrenceReorderDisablesShuffleAndConfirmedClearUndoRestoresOnlyUpcomingEdits() {
        val app = show()
        val podcastQueue = canonicalQueue(app, QueueContext.PODCAST)
        ui.onNodeWithTag("mini-queue").performClick()
        await { app.container.navigation.value.queueOpen }
        queueScrollTo("queue-up-music-copy")
        ui.onNodeWithTag("queue-up-music-copy").performClick()
        await { canonicalQueue(app, QueueContext.MUSIC).displayedOrder.map { it.value } ==
            listOf("music-now", "music-copy", "music-next") }
        val reordered = canonicalQueue(app, QueueContext.MUSIC)
        assertEquals(3L, reordered.editRevision)
        assertFalse(reordered.shuffle)
        assertEquals("music-now", reordered.currentOccurrenceId?.value)
        assertEquals(17_000L, reordered.positionMs)
        assertEquals(podcastQueue, canonicalQueue(app, QueueContext.PODCAST))

        queueScrollTo("clear-upcoming")
        ui.onNodeWithTag("clear-upcoming").performClick()
        assertEquals(queueFacts(reordered), queueFacts(canonicalQueue(app, QueueContext.MUSIC)),
            "Merely showing confirmation is non-destructive")
        ui.onNodeWithTag("confirm-action").performClick()
        await { canonicalQueue(app, QueueContext.MUSIC).displayedOrder.map { it.value } == listOf("music-now") }
        assertEquals("music-now", canonicalQueue(app, QueueContext.MUSIC).currentOccurrenceId?.value)
        assertEquals(17_000L, canonicalQueue(app, QueueContext.MUSIC).positionMs)
        queueScrollTo("undo-queue")
        ui.onNodeWithTag("undo-queue").performClick()
        await { queueFacts(canonicalQueue(app, QueueContext.MUSIC)) == queueFacts(reordered) }
        assertEquals(podcastQueue, canonicalQueue(app, QueueContext.PODCAST))
        assertTrue(app.engine.loads.isEmpty(), "Editing future occurrences must not start audio")
        capture("fixture-shell-en-light-occurrence-queue")
    }

    @Test fun detailsFavoritesAndDuplicatePlaylistMembershipAreConnectedButDoNotPlayOrDeleteDownloads() {
        val app = show(CoreShellTestFixture.snapshot(navigation = AppNavigation(destination = AppDestination.LIBRARY)))
        val canonical = app.store.acquisitionViews.value.acquisition
        val queues = canonical.queues
        val downloads = canonical.physicalDownloads
        val paused = downloads.getValue("ui-fixture-paused-download")
        assertEquals(DownloadStatus.PAUSED, paused.status)
        assertTrue(paused.userPaused)
        ui.onNodeWithContentDescription(english.format(TextKey.MoreActionsFor, music.title)).performClick()
        ui.onNodeWithText(english.text(TextKey.Details)).performClick()
        await { app.container.navigation.value.detail == AppDetail(DetailKind.TRACK, music.id) }
        assertTrue(app.engine.loads.isEmpty())
        trackScrollTo("track-favorite")
        ui.onNodeWithTag("track-favorite").performClick()
        await { music.id !in app.store.state.value.favoriteIds }
        trackScrollTo("track-playlist")
        ui.onNodeWithTag("track-playlist").performClick()
        ui.onNodeWithText(playlist.name).performClick()
        ui.onNodeWithText(english.text(TextKey.DuplicateTrack)).assertIsDisplayed()
        assertEquals(2, app.store.state.value.playlists.single().entries.size)
        ui.onNodeWithTag("confirm-action").performClick()
        await { app.store.state.value.playlists.single().entries.size == 3 }
        val duplicated = app.store.state.value.playlists.single()
        assertEquals(listOf("playlist-first", "playlist-copy"), duplicated.entries.take(2).map { it.id })

        ui.onNodeWithTag("track-back").performClick()
        await { app.container.navigation.value.detail == null }
        libraryScrollTo("library-PLAYLISTS")
        ui.onNodeWithTag("library-PLAYLISTS").performClick()
        await { app.container.navigation.value.librarySection == LibrarySection.PLAYLISTS }
        ui.onNodeWithText(playlist.name).performScrollTo().performClick()
        await { app.container.navigation.value.detail?.id == playlist.id }
        ui.onNodeWithTag("playlist-detail").performScrollToNode(hasTestTag("playlist-entry-playlist-copy"))
        ui.onNode(hasContentDescription(english.text(TextKey.RemoveFromPlaylist)) and
            hasAnyAncestor(hasTestTag("playlist-entry-playlist-copy"))).performScrollTo().performClick()
        await { app.store.state.value.playlists.single().entries.none { it.id == "playlist-copy" } }
        assertEquals(2, app.store.state.value.playlists.single().entries.size)
        assertEquals("playlist-first", app.store.state.value.playlists.single().entries.first().id)

        ui.onNodeWithText(english.text(TextKey.UndoLibraryEdit)).performScrollTo().performClick()
        await { app.store.state.value.playlists.single() == duplicated }
        val after = app.store.acquisitionViews.value.acquisition
        assertEquals(queues, after.queues)
        assertEquals(downloads, after.physicalDownloads)
        val pausedAfter = after.physicalDownloads.getValue("ui-fixture-paused-download")
        assertEquals(DownloadStatus.PAUSED, pausedAfter.status)
        assertTrue(pausedAfter.userPaused)
        assertTrue(app.engine.loads.isEmpty())
        assertTrue(app.gateway.queries.isEmpty())
    }

    @Test fun confirmedPlaylistShuffleHasOneUndoBackToTheOriginalOccurrenceQueue() {
        val navigation = AppNavigation(destination = AppDestination.LIBRARY,
            details = listOf(AppDetail(DetailKind.PLAYLIST, playlist.id)), viewedQueue = QueueContext.PODCAST)
        val app = show(CoreShellTestFixture.snapshot(navigation = navigation))
        val original = canonicalQueue(app, QueueContext.MUSIC)
        val podcast = canonicalQueue(app, QueueContext.PODCAST)
        ui.onNodeWithText(english.text(TextKey.Shuffle)).performClick()
        assertEquals(queueFacts(original), queueFacts(canonicalQueue(app, QueueContext.MUSIC)))
        assertTrue(app.gateway.queries.isEmpty())
        ui.onNodeWithTag("confirm-action").performClick()
        await { app.container.playback.state.value.isPlaying &&
            canonicalQueue(app, QueueContext.MUSIC).displayedOrder.size == 2 }
        assertTrue(canonicalQueue(app, QueueContext.MUSIC).shuffle)
        assertEquals(listOf(music.id, music.id),
            canonicalQueue(app, QueueContext.MUSIC).displayedOrder.map { occurrenceMediaId(app, it) })
        assertEquals(listOf(music.title), app.gateway.queries.toList())

        ui.onNodeWithText(english.text(TextKey.UndoQueueEdit)).performScrollTo().performClick()
        await { canonicalQueue(app, QueueContext.MUSIC).displayedOrder.map { it.value } ==
            original.displayedOrder.map { it.value } }
        assertEquals(queueFacts(original), queueFacts(canonicalQueue(app, QueueContext.MUSIC)))
        assertEquals(podcast, canonicalQueue(app, QueueContext.PODCAST))
        assertFalse(QueueContext.MUSIC in app.container.playback.state.value.undoableQueueContexts)
    }

    @Test fun backgroundSpaceTogglesButTypingSpacesAndNavigationShortcutsDoNotHijackText() {
        val app = show()
        // Ready requests the shell's background focus; no child control has focus yet.
        ui.onNodeWithTag("veyra-app").performKeyInput { pressKey(Key.Spacebar) }
        await { app.container.playback.state.value.isPlaying }
        assertEquals(1, app.engine.plays.size)
        shortcut(Key.K)
        await { app.container.navigation.value.destination == AppDestination.SEARCH }
        ui.onNodeWithTag("search-query").performClick().performTextReplacement("fixture")
        await { app.container.search.value.query == "fixture" }
        val playCount = app.engine.plays.size
        val pauseCount = app.engine.pauses.size
        ui.onNodeWithTag("search-query").assertIsFocused()
        sendKey(Key.Spacebar, typedCharacter = ' ')
        await { app.container.search.value.query == "fixture " }
        assertEquals(playCount, app.engine.plays.size)
        assertEquals(pauseCount, app.engine.pauses.size)
        assertTrue(app.container.playback.state.value.isPlaying)

        shortcut(Key.Comma)
        await { app.container.navigation.value.destination == AppDestination.SETTINGS }
        ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-text-scale"))
        ui.onNodeWithTag("settings-text-scale").performScrollTo().assertIsEnabled().focus()
        shortcut(Key.K)
        await { app.container.navigation.value.destination == AppDestination.SEARCH }
        ui.onNodeWithTag("search-query").assertTextContains("fixture ")
        assertEquals(pauseCount, app.engine.pauses.size)
    }

    @Test fun aFocusedPlayerSliderOwnsArrowKeysAndSpaceActivatesAButtonOnlyOnce() {
        val app = show()
        ui.onNodeWithTag("mini-toggle").performClick()
        await { app.container.playback.state.value.isPlaying }
        ui.onNodeWithTag("mini-open-player").performClick()
        await { app.container.navigation.value.playerOpen }
        val slider = ui.onNodeWithTag("player-seek").assertIsDisplayed().assertIsEnabled()
        slider.focus()
        val playingBefore = app.engine.plays.size
        val pausesBefore = app.engine.pauses.size
        slider.performKeyInput { pressKey(Key.Spacebar) }
        ui.waitForIdle()
        assertEquals(playingBefore, app.engine.plays.size)
        assertEquals(pausesBefore, app.engine.pauses.size)
        assertTrue(app.container.playback.state.value.isPlaying)
        val position = app.container.playback.state.value.positionMs
        val generation = app.container.playback.state.value.generation
        slider.performKeyInput { pressKey(Key.DirectionRight) }
        await { app.engine.seeks.isNotEmpty() && app.container.playback.state.value.pendingSeekMs == null }
        assertTrue(app.engine.seeks.last().positionMs > position)
        assertEquals(generation, app.engine.seeks.last().generation)
        assertEquals("music-now", app.container.playback.state.value.currentEntryId)

        ui.onNodeWithTag("player-toggle").assertIsDisplayed().focus().performKeyInput { pressKey(Key.Spacebar) }
        await { !app.container.playback.state.value.desiredPlaying && !app.container.playback.state.value.isPlaying }
        assertEquals(pausesBefore + 1, app.engine.pauses.size)
        assertEquals(playingBefore, app.engine.plays.size, "A control's Space must not also reach the global Play/Pause shortcut")
    }

    @Test fun escapeClosesADialogThenQueueThenPlayerWithoutChangingTheAudioOwner() {
        val app = show()
        ui.onNodeWithTag("mini-open-player").performClick()
        await { app.container.navigation.value.playerOpen }
        ui.onNodeWithTag("player-sleep").performScrollTo().performClick()
        ui.onNodeWithTag("sleep-minutes").assertIsDisplayed()
        val beforeEscape = safeKeyRoutingDiagnostics()
        capture("fixture-escape-sleep-dialog-before", target = ui.onNode(isDialog()),
            diagnostics = beforeEscape, settleHover = false)
        escape()
        try {
            ui.onNodeWithTag("sleep-minutes").assertDoesNotExist()
        } catch (failure: AssertionError) {
            captureFailure("fixture-escape-sleep-dialog-after", failure,
                "Before Escape:\n$beforeEscape\nAfter Escape:\n${safeKeyRoutingDiagnostics(failure)}",
                target = ui.onNode(isDialog()))
            throw failure
        }
        assertTrue(app.container.navigation.value.playerOpen)
        ui.onNodeWithTag("player-queue").performScrollTo().performClick()
        await { app.container.navigation.value.queueOpen }
        escapeWithRoutingEvidence("fixture-escape-queue") {
            await { !app.container.navigation.value.queueOpen }
            assertTrue(app.container.navigation.value.playerOpen)
            ui.onNodeWithTag("full-player").assertIsDisplayed()
        }
        escapeWithRoutingEvidence("fixture-escape-player") {
            await { !app.container.navigation.value.playerOpen }
            ui.onNodeWithTag("mini-player").assertIsDisplayed()
            assertEquals("music-now", app.container.playback.state.value.currentEntryId)
            assertTrue(app.engine.loads.isEmpty())
        }
    }

    @Test fun queueContextNavigationKeepsTheClickedChipFocusedWithoutStartingPlayback() {
        val app = show()
        val beforePlayback = app.container.playback.state.value
        val savedQueues = app.store.acquisitionViews.value.acquisition.queues
        assertFalse(beforePlayback.isPlaying)
        assertFalse(beforePlayback.desiredPlaying)

        fun assertAudioUnchanged() {
            val after = app.container.playback.state.value
            assertFalse(after.isPlaying, "Viewing a queue or pressing its focused chip must not play audio")
            assertFalse(after.desiredPlaying, "No deferred Play intent may be hidden behind a paused engine")
            assertEquals(beforePlayback.activeContext, after.activeContext)
            assertEquals(beforePlayback.currentEntryId, after.currentEntryId)
            assertEquals(selectionIdentity(beforePlayback.currentSelection), selectionIdentity(after.currentSelection))
            assertEquals(beforePlayback.executionBinding, after.executionBinding)
            assertEquals(beforePlayback.generation, after.generation)
            assertEquals(beforePlayback.positionMs, after.positionMs)
            assertEquals(savedQueues, app.store.acquisitionViews.value.acquisition.queues)
            assertTrue(app.engine.loads.isEmpty(), "Queue inspection must not prepare an audio source")
            assertTrue(app.engine.plays.isEmpty(), "A chip's Space must not become background Play/Pause")
            assertTrue(app.engine.pauses.isEmpty())
        }

        ui.onNodeWithTag("mini-open-player").performClick()
        await { app.container.navigation.value.playerOpen }
        ui.onNodeWithTag("player-queue").performScrollTo().performClick()
        await { app.container.navigation.value.queueOpen }
        val beforeQueueChange = safeKeyRoutingDiagnostics()
        try {
            // These header chips survive ViewQueue navigation. Use normal mouse clicks, never
            // a semantics RequestFocus, so an unconditional shell refocus is observable.
            val podcastChip = ui.onNodeWithTag("view-queue-PODCAST").assertIsDisplayed()
            podcastChip.performClick()
            await { app.container.navigation.value.viewedQueue == QueueContext.PODCAST &&
                app.container.playback.state.value.viewedContext == QueueContext.PODCAST }
            podcastChip.assertIsSelected().assertIsFocused()
            capture("fixture-queue-context-before-space", diagnostics = safeKeyRoutingDiagnostics(), settleHover = false)
            sendKey(Key.Spacebar)
            podcastChip.assertIsSelected().assertIsFocused()

            // The app action actor and playback actor must both process this later, different
            // selection before negative audio assertions. UI idle alone is not an actor barrier.
            val musicChip = ui.onNodeWithTag("view-queue-MUSIC").assertIsDisplayed()
            musicChip.performClick()
            await { app.container.navigation.value.viewedQueue == QueueContext.MUSIC &&
                app.container.playback.state.value.viewedContext == QueueContext.MUSIC }
            musicChip.assertIsSelected().assertIsFocused()
            assertAudioUnchanged()
        } catch (failure: Throwable) {
            if (failure !is AssertionError && failure !is Exception &&
                failure !is ComposeTimeoutException) throw failure
            captureFailure("fixture-queue-context-focus-failure", failure,
                "Before queue-context change:\n" + beforeQueueChange +
                    "\nAfter queue-context change/Space:\n" + safeKeyRoutingDiagnostics(failure))
            throw failure
        }
        capture("fixture-queue-context-after-space", diagnostics = safeKeyRoutingDiagnostics(), settleHover = false)
        escapeWithRoutingEvidence("fixture-queue-context-escape-queue") {
            await { !app.container.navigation.value.queueOpen }
            assertTrue(app.container.navigation.value.playerOpen)
            ui.onNodeWithTag("full-player").assertIsDisplayed()
        }
        escapeWithRoutingEvidence("fixture-queue-context-escape-player") {
            await { !app.container.navigation.value.playerOpen }
            ui.onNodeWithTag("mini-player").assertIsDisplayed()
            assertAudioUnchanged()
        }
    }

    @Test fun heldSpaceReleasedByAChildDoesNotSuppressTheNextBackgroundPress() {
        val app = show()
        // Observe the one real shell focus target without a new production tag or RequestFocus.
        val shell = ui.onNode(isFocusable() and hasAnyAncestor(hasTestTag("veyra-app")) and
            hasAnyDescendant(hasTestTag("nav-LISTEN")) and hasAnyDescendant(hasTestTag("mini-toggle")),
            useUnmergedTree = true)
        val listen = ui.onNodeWithTag("nav-LISTEN")
        var heldKey: Pair<Key, Boolean>? = null
        var originalFailure: Throwable? = null

        fun down(key: Key, shift: Boolean = false): Boolean {
            check(heldKey == null || heldKey == (key to shift))
            heldKey = key to shift
            return sendKeyPhase(key, KeyEventType.KeyDown, shift)
        }
        fun up(key: Key, shift: Boolean = false): Boolean {
            val consumed = sendKeyPhase(key, KeyEventType.KeyUp, shift)
            heldKey = null
            return consumed
        }
        fun diagnostics(failure: Throwable? = null) =
            "Playback: ${app.container.playback.state.value}\n" +
                "Fake-engine loads=${app.engine.loads.size}; plays=${app.engine.plays}; pauses=${app.engine.pauses}\n" +
                safeKeyRoutingDiagnostics(failure)
        fun returnToShell() {
            // Previous from the first rail item must really reach the focusable parent.
            // A sent Tab or selected destination alone is not evidence of shell focus.
            assertTrue(down(Key.Tab, shift = true))
            up(Key.Tab, shift = true)
            shell.assertIsFocused()
            listen.assertIsNotFocused()
        }

        val before = diagnostics()
        try {
            shell.assertIsFocused()
            assertFalse(app.container.playback.state.value.isPlaying)
            assertFalse(app.container.playback.state.value.desiredPlaying)
            assertTrue(app.engine.loads.isEmpty())
            assertTrue(app.engine.plays.isEmpty())
            capture("fixture-held-space-initial", diagnostics = before, settleHover = false)

            assertTrue(down(Key.Spacebar))
            await { app.container.playback.state.value.isPlaying && app.container.playback.state.value.desiredPlaying }
            val prepared = app.container.playback.state.value
            val loads = app.engine.loads.toList()
            val queries = app.gateway.queries.toList()
            val basePlays = app.engine.plays.size
            val basePauses = app.engine.pauses.size
            assertEquals(1, loads.size)
            assertEquals(1, basePlays)
            assertEquals(0, basePauses)

            fun assertAudio(plays: Int, pauses: Int, playing: Boolean) {
                val after = app.container.playback.state.value
                assertEquals(prepared.activeContext, after.activeContext)
                assertEquals(prepared.currentEntryId, after.currentEntryId)
                assertEquals(selectionIdentity(prepared.currentSelection), selectionIdentity(after.currentSelection))
                assertEquals(prepared.executionBinding, after.executionBinding)
                assertEquals(prepared.generation, after.generation)
                assertEquals(loads, app.engine.loads.toList(), "Focus transfer cannot prepare a second source/owner")
                assertEquals(queries, app.gateway.queries.toList(), "Focus transfer cannot perform a fresh resolution")
                assertEquals(plays, app.engine.plays.size)
                assertEquals(pauses, app.engine.pauses.size)
                assertTrue(app.engine.plays.all { it == prepared.generation })
                assertTrue(app.engine.pauses.all { it == prepared.generation })
                assertEquals(playing, after.desiredPlaying)
                assertEquals(playing, after.isPlaying)
            }

            // The no-repeat control first proves that a newly focused clickable does NOT
            // consume an unmatched UP. The second branch gives it an actual repeat DOWN.
            listOf(false, true).forEachIndexed { index, repeatAtChild ->
                val label = if (repeatAtChild) "child-repeat" else "unmatched-up"
                if (index > 0) {
                    listen.performClick()
                    await { app.container.navigation.value.destination == AppDestination.LISTEN }
                    listen.assertIsFocused()
                    shell.assertIsNotFocused()
                    returnToShell()
                    assertTrue(down(Key.Spacebar))
                    await { app.container.playback.state.value.isPlaying && app.container.playback.state.value.desiredPlaying }
                }
                shell.assertIsFocused()
                // Held repeats on the shell still deduplicate; the later actor barrier checks counts.
                assertTrue(down(Key.Spacebar))
                capture("fixture-held-space-$label-before-transfer", diagnostics = diagnostics(), settleHover = false)
                listen.performClick()
                listen.assertIsFocused()
                shell.assertIsNotFocused()
                if (repeatAtChild) assertTrue(down(Key.Spacebar))
                val releaseConsumed = up(Key.Spacebar)
                assertEquals(repeatAtChild, releaseConsumed,
                    "Only the child's own recorded DOWN may consume this Space UP")
                listen.assertIsFocused()
                shell.assertIsNotFocused()

                // Actual Ctrl+K submits OpenSearch behind any PlayPause on the SAME app actor.
                // Its distinct navigation acknowledgment is a causal barrier; a same-Listen
                // click or UI idle is not. The surviving rail item must retain actual focus.
                shortcut(Key.K)
                await { app.container.navigation.value.destination == AppDestination.SEARCH }
                listen.assertIsFocused()
                shell.assertIsNotFocused()
                assertAudio(basePlays + index, basePauses + index, playing = true)
                capture("fixture-held-space-$label-after-release", diagnostics = diagnostics(), settleHover = false)

                returnToShell()
                assertTrue(down(Key.Spacebar))
                await { !app.container.playback.state.value.desiredPlaying && !app.container.playback.state.value.isPlaying }
                up(Key.Spacebar)
                assertAudio(basePlays + index, basePauses + index + 1, playing = false)
                capture("fixture-held-space-$label-next-press", diagnostics = diagnostics(), settleHover = false)
            }

            // A normal paired tap after both transfers resumes exactly once, without another load.
            heldKey = Key.Spacebar to false
            sendKey(Key.Spacebar)
            heldKey = null
            await { app.container.playback.state.value.desiredPlaying && app.container.playback.state.value.isPlaying }
            assertAudio(basePlays + 2, basePauses + 2, playing = true)
            capture("fixture-held-space-final-resume", diagnostics = diagnostics(), settleHover = false)
        } catch (failure: Throwable) {
            originalFailure = failure
            if (failure is AssertionError || failure is Exception || failure is ComposeTimeoutException) {
                captureFailure("fixture-held-space-failure", failure,
                    "Before phased keys:\n$before\nAfter phased keys:\n${diagnostics(failure)}")
            }
            throw failure
        } finally {
            // Capture the failing state first, then release only our still-held synthetic key.
            // A cleanup failure must not replace the original timeout/assertion.
            heldKey?.let { (key, shift) ->
                try { sendKeyPhase(key, KeyEventType.KeyUp, shift) }
                catch (cleanupFailure: Throwable) {
                    val failure = originalFailure
                    if (failure == null) throw cleanupFailure else failure.addSuppressed(cleanupFailure)
                }
            }
        }
    }

    @Test fun narrowArabicLargeTextMirrorsNavigationButKeepsThePlayerTransportLtrAndReachable() {
        val app = show(CoreShellTestFixture.snapshot(settings = AppSettings(language = LanguagePreference.ARABIC,
            theme = ThemeMode.DARK, textScale = 1.75f), navigation = AppNavigation(destination = AppDestination.LIBRARY)), 320.dp, 760.dp)
        val arabic = AppStrings(LanguagePreference.ARABIC)
        val nav = listOf("LISTEN", "SEARCH", "PODCASTS", "LIBRARY").map { name ->
            assertFullyVisible("nav-$name")
            ui.onNodeWithTag("nav-$name").fetchSemanticsNode().boundsInRoot.center.x
        }
        assertTrue(nav.zipWithNext().all { (first, next) -> first > next }, "Arabic bottom navigation mirrors its destination order")
        assertLabelFits("nav-LIBRARY", arabic.text(TextKey.Library))
        assertFullyVisible("mini-toggle")
        assertFullyVisible("mini-queue")
        libraryScrollTo("library-query")
        assertFullyVisible("library-query")
        capture("fixture-shell-ar-dark-320dp-font175-library")

        ui.onNodeWithTag("mini-open-player").performClick()
        await { app.container.navigation.value.playerOpen }
        listOf("player-seek", "player-previous", "player-toggle", "player-next").forEach(::assertFullyVisible)
        val previous = ui.onNodeWithTag("player-previous").fetchSemanticsNode().boundsInRoot.center.x
        val toggle = ui.onNodeWithTag("player-toggle").fetchSemanticsNode().boundsInRoot.center.x
        val next = ui.onNodeWithTag("player-next").fetchSemanticsNode().boundsInRoot.center.x
        assertTrue(previous < toggle && toggle < next, "Transport is an LTR island within the RTL app shell")
        assertFullyVisible("close-player")
        capture("fixture-shell-ar-dark-320dp-font175-player-controls")
        ui.onNodeWithTag("player-queue").performScrollTo().performClick()
        await { app.container.navigation.value.queueOpen }
        queueScrollTo("view-queue-PODCAST")
        assertFullyVisible("view-queue-PODCAST")
        ui.onNodeWithTag("view-queue-PODCAST").performClick()
        await { app.container.navigation.value.viewedQueue == QueueContext.PODCAST }
        assertEquals(QueueContext.MUSIC, app.container.playback.state.value.activeContext)
        capture("fixture-shell-ar-dark-320dp-font175-queue-inspection")
    }

    @Test fun shortLandscapeKeepsLibraryInputAndScrollableRailReachableAboveTheMiniPlayer() {
        val app = show(CoreShellTestFixture.snapshot(settings = AppSettings(language = LanguagePreference.ARABIC,
            theme = ThemeMode.DARK, textScale = 1.75f), navigation = AppNavigation(destination = AppDestination.LIBRARY)), 720.dp, 360.dp)
        ui.onNodeWithTag("nav-LIBRARY").performScrollTo()
        assertFullyVisible("nav-LIBRARY")
        libraryScrollTo("library-query")
        assertFullyVisible("library-query")
        assertFullyVisible("mini-toggle")
        val navigation = ui.onNodeWithTag("nav-LIBRARY").fetchSemanticsNode().boundsInRoot
        val list = ui.onNodeWithTag("library-list").fetchSemanticsNode().boundsInRoot
        assertTrue(navigation.left >= list.right, "The short landscape RTL rail stays to the content's right")
        val query = ui.onNodeWithTag("library-query").fetchSemanticsNode().boundsInRoot
        val mini = ui.onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
        assertTrue(query.bottom <= mini.top + 1f, "Library controls do not hide underneath persistent transport")
        ui.onNodeWithTag("library-query").performTextReplacement("Second")
        libraryScrollTo("play-${CoreShellTestFixture.second.id}")
        ui.onNodeWithTag("play-${CoreShellTestFixture.second.id}").assertIsDisplayed()
        assertTrue(app.engine.loads.isEmpty())
        capture("fixture-shell-ar-dark-720x360-font175-library")

        // A real shell error indicator must not make the local library or persistent controls
        // unreachable. The status is authored here; no native tools or network are started.
        app.runtime.value = MediaRuntimeStatus(MediaRuntimePhase.ERROR,
            component = MediaRuntimeComponent.CHROMIUM, failure = AppFailure(FailureCode.UNSUPPORTED_SOURCE))
        ui.waitForIdle()
        assertFullyVisible("media-runtime-status")
        libraryScrollTo("library-query")
        assertFullyVisible("library-query")
        assertFullyVisible("mini-open-player")
        assertFullyVisible("mini-toggle")
        assertFullyVisible("mini-queue")
        val errorQuery = ui.onNodeWithTag("library-query").fetchSemanticsNode().boundsInRoot
        val errorMini = ui.onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
        assertTrue(errorQuery.bottom <= errorMini.top + 1f, "Runtime errors must not obscure library input behind the mini-player")
        capture("fixture-shell-ar-dark-720x360-font175-library-runtime-error")
    }

    @Test fun compactNavigationKeepsEverySelectedLabelUnclippedAt200PercentInBothLanguages() {
        val app = show(CoreShellTestFixture.snapshot(settings = AppSettings(language = LanguagePreference.ENGLISH,
            theme = ThemeMode.DARK, textScale = 2f)), 320.dp, 760.dp)
        val destinations = listOf(AppDestination.LISTEN to TextKey.Listen, AppDestination.SEARCH to TextKey.Search,
            AppDestination.PODCASTS to TextKey.Podcasts, AppDestination.LIBRARY to TextKey.Library)
        listOf(LanguagePreference.ENGLISH, LanguagePreference.ARABIC).forEach { language ->
            app.container.changeSettings { it.copy(language = language) }
            await { app.store.state.value.settings.language == language }
            val strings = AppStrings(language)
            // Room publishes before the UI's StateFlow collector necessarily renders the new
            // locale. Wait for the actual selected label AND mirrored layout before targeting
            // a pointer gesture; a database value alone is not a rendered-frame receipt.
            val selectedBeforeLocale = app.container.navigation.value.destination
            val selectedLabel = destinations.single { it.first == selectedBeforeLocale }.second
            try {
                ui.waitUntil(timeoutMillis = 10_000L) {
                    val labels = ui.onAllNodes(hasText(strings.text(selectedLabel)) and
                        hasAnyAncestor(hasTestTag("nav-${selectedBeforeLocale.name}")), useUnmergedTree = true)
                        .fetchSemanticsNodes()
                    val centers = destinations.map { (other, _) ->
                        ui.onNodeWithTag("nav-${other.name}").fetchSemanticsNode().boundsInRoot.center.x
                    }
                    labels.isNotEmpty() && centers.zipWithNext().all { (a, b) ->
                        if (language == LanguagePreference.ARABIC) a > b else a < b
                    }
                }
            } catch (failure: Throwable) {
                if (failure !is AssertionError && failure !is Exception) throw failure
                val assertion = AssertionError("Rendered navigation locale did not become $language " +
                    "with selected $selectedBeforeLocale label '${strings.text(selectedLabel)}'", failure)
                captureFailure("fixture-nav-${language.name.lowercase()}-locale-readiness", assertion,
                    safeKeyRoutingDiagnostics(assertion))
                throw assertion
            }
            destinations.forEach { (destination, label) ->
                val target = ui.onNodeWithTag("nav-${destination.name}")
                var beforeClick = "language=$language; target=$destination; expectedLabel='${strings.text(label)}'; " +
                    "target geometry not yet observed; navigation=${app.container.navigation.value}"
                try {
                    beforeClick = "language=$language; target=$destination; expectedLabel='${strings.text(label)}'; " +
                        "bounds=${target.getUnclippedBoundsInRoot()}; " +
                        "pointerCenter=${target.fetchSemanticsNode().boundsInRoot.center}; " +
                        "navigation=${app.container.navigation.value}"
                    target.performClick()
                    await { app.container.navigation.value.destination == destination }
                } catch (failure: Throwable) {
                    if (failure !is AssertionError && failure !is Exception) throw failure
                    val assertion = AssertionError("Navigation did not reach $destination after $beforeClick", failure)
                    captureFailure("fixture-nav-${language.name.lowercase()}-${destination.name.lowercase()}",
                        assertion, "$beforeClick\nAfter attempted pointer:\n${safeKeyRoutingDiagnostics(assertion)}")
                    throw assertion
                }
                ui.onNodeWithTag("nav-${destination.name}").assertIsSelected()
                destinations.forEach { (other, _) -> assertFullyVisible("nav-${other.name}") }
                assertLabelFits("nav-${destination.name}", strings.text(label))
                val centers = destinations.map { (other, _) -> ui.onNodeWithTag("nav-${other.name}").fetchSemanticsNode().boundsInRoot.center.x }
                assertTrue(centers.zipWithNext().all { (a, b) -> if (language == LanguagePreference.ARABIC) a > b else a < b })
                if (destination == AppDestination.PODCASTS || destination == AppDestination.LIBRARY) {
                    capture("fixture-shell-${if (language == LanguagePreference.ARABIC) "ar" else "en"}-dark-320dp-font200-nav-${destination.name.lowercase()}")
                }
            }
        }
        assertTrue(app.engine.loads.isEmpty(), "Destination inspection must not activate audio")
    }

    @Test fun resizingLongMetadataKeepsPauseAndSeekVisibleWhileCompleteProvenanceRemainsReadable() {
        val longTitle = "Authored requested recording — " + "عنوان كامل طويل للاختبار ".repeat(5) + "requested end"
        val longResolved = "A different authored first-result recording — " + "بيانات تسجيل مختلفة ".repeat(5) + "resolved end"
        val item = music.copy(title = longTitle, creator = "A deliberately long authored creator credit ".repeat(5))
        val initial = CoreShellTestFixture.snapshot().let { it.copy(media = it.media + (item.id to item)) }
        val app = show(initial, typedTitles = CoreShellTypedTitles(
            resolvedTitles = mapOf(longTitle to longResolved)))
        ui.onNodeWithTag("mini-open-player").performClick()
        await { app.container.navigation.value.playerOpen }
        val selected = app.container.playback.state.value.currentSelection
        val selectedReceipt = selected?.receipt as? FirstResultReceipt.Observed
        assertEquals(longTitle, selected?.request?.completeTitle)
        assertEquals(longResolved, selectedReceipt?.observation?.entry?.title)
        assertEquals("music-now", selected?.occurrenceId?.value)
        val essential = listOf("close-player", "player-seek", "player-previous", "player-toggle", "player-next")
        val savedQueues = app.store.acquisitionViews.value.acquisition.queues
        val savedPlayback = app.container.playback.state.value

        fun assertInitialIdentity(strings: AppStrings, maxLines: Int, direction: LayoutDirection, fontScale: Float) {
            fun preview(tag: String, text: String, firstMetadataOffset: Int = 0) {
                val node = ui.onNodeWithTag(tag, useUnmergedTree = true)
                var diagnostics = "$tag has not produced an initial identity layout."
                try {
                    node.assertIsDisplayed().assertTextEquals(text)
                    val bounds = node.getUnclippedBoundsInRoot()
                    val clipped = node.fetchSemanticsNode().boundsInRoot
                    val viewport = ui.onNodeWithTag("player-details").getUnclippedBoundsInRoot()
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    diagnostics = textLayoutDiagnostics(node, "player-details", layouts)
                    writeDiagnostic("fixture-player-initial-identity-$tag", diagnostics)
                    assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right &&
                        bounds.top >= viewport.top && bounds.bottom <= viewport.bottom,
                        "Initial identity must fit the actual scroll viewport without scrolling: $diagnostics")
                    val width = (bounds.right - bounds.left).value
                    val height = (bounds.bottom - bounds.top).value
                    assertTrue(clipped.width + 1f >= width && clipped.height + 1f >= height,
                        "The whole identity preview, not just some clipped pixels, must be visible: $diagnostics")
                    assertTrue(layouts.isNotEmpty(), "Native semantic text layout is required: $diagnostics")
                    layouts.forEach { layout ->
                        assertEquals(direction, layout.layoutInput.layoutDirection)
                        assertEquals(fontScale, layout.layoutInput.density.fontScale, "Do not reduce user text scaling")
                        assertTrue(layout.lineCount in 1..maxLines && !layout.didOverflowWidth,
                            "Identity preview must have bounded actual line geometry: $diagnostics")
                        assertTrue(layout.size.width <= width + 1f && layout.size.height <= height + 1f &&
                            layout.multiParagraph.width <= width + 1f && layout.multiParagraph.height <= height + 1f &&
                            (0 until layout.lineCount).all { line ->
                                layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= width + 1f &&
                                    layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= height + 1f
                            }, "Preview paragraph and displayed lines must fit the measured node: $diagnostics")
                        // Ellipsis is intentional for this preview only. Require some actual
                        // title text beyond the localized label; full receipt checks below
                        // still prohibit overflow and retain the entire immutable strings.
                        assertTrue(firstMetadataOffset >= 0 &&
                            layout.getLineEnd(layout.lineCount - 1, visibleEnd = true) >= firstMetadataOffset + 3,
                            "An identity cannot be an empty box or only a localized prefix: $diagnostics")
                    }
                } catch (failure: AssertionError) {
                    captureFailure("fixture-player-initial-identity-$tag", failure, diagnostics)
                    throw failure
                }
            }
            preview("player-title", item.title)
            val resolved = strings.format(TextKey.ResolvedTitleValue, longResolved)
            preview("player-resolved-title", resolved, resolved.indexOf(longResolved))
            val listing = ui.onNodeWithTag("player-title").getUnclippedBoundsInRoot()
            val resolution = ui.onNodeWithTag("player-resolved-title").getUnclippedBoundsInRoot()
            assertTrue(listing.bottom <= resolution.top, "Keep the listing and resolved recording distinct")
            essential.forEach(::assertFullyVisible) // Unchanged strict 48dp and unclipped target checks.
            val previous = ui.onNodeWithTag("player-previous").fetchSemanticsNode().boundsInRoot.center.x
            val toggle = ui.onNodeWithTag("player-toggle").fetchSemanticsNode().boundsInRoot.center.x
            val next = ui.onNodeWithTag("player-next").fetchSemanticsNode().boundsInRoot.center.x
            assertTrue(previous < toggle && toggle < next, "Media transport remains LTR in both languages")
        }
        essential.forEach(::assertFullyVisible)
        capture("fixture-player-en-light-960x720-long-metadata-initial")

        resize(720.dp, 360.dp)
        essential.forEach(::assertFullyVisible) // No scroll or prepositioning of the player.
        assertInitialIdentity(english, maxLines = 1, direction = LayoutDirection.Ltr, fontScale = 1f)
        capture("fixture-player-en-light-720x360-long-metadata-resized")

        app.container.changeSettings { it.copy(language = LanguagePreference.ARABIC, theme = ThemeMode.DARK, textScale = 2f) }
        await { app.store.state.value.settings.textScale == 2f }
        resize(320.dp, 760.dp)
        essential.forEach(::assertFullyVisible)
        val strings = AppStrings(LanguagePreference.ARABIC)
        assertInitialIdentity(strings, maxLines = 2, direction = LayoutDirection.Rtl, fontScale = 2f)
        capture("fixture-player-ar-dark-320x760-font200-long-metadata-resized")

        ui.onNodeWithText(strings.text(TextKey.ShowMore)).performScrollTo().performClick()
        listOf(longTitle, longResolved).forEach { title ->
            val node = ui.onNode(hasText(title) and hasAnyAncestor(hasTestTag("playback-provenance")), useUnmergedTree = true)
            node.performScrollTo().assertIsDisplayed().assertTextEquals(title)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val diagnostics = textLayoutDiagnostics(node, "playback-provenance", layouts)
            writeDiagnostic("fixture-expanded-receipt-layout", diagnostics)
            try {
                assertTrue(layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow },
                    "Expanded receipt must retain every line: $diagnostics")
            } catch (failure: AssertionError) {
                captureFailure("fixture-expanded-receipt-overflow", failure, diagnostics)
                throw failure
            }
            essential.forEach(::assertFullyVisible)
        }
        ui.onNodeWithText(strings.text(TextKey.FirstSearchResult)).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText(strings.format(TextKey.AudioSourceValue, strings.text(TextKey.YouTube))).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText(strings.text(TextKey.SourceMismatchDetected)).performScrollTo().assertIsDisplayed()
        essential.forEach(::assertFullyVisible)
        val afterReading = app.container.playback.state.value
        assertEquals("music-now", afterReading.currentEntryId)
        assertEquals(savedQueues, app.store.acquisitionViews.value.acquisition.queues,
            "Layout and disclosure preserve both saved queues")
        assertEquals(selectionIdentity(savedPlayback.currentSelection), selectionIdentity(afterReading.currentSelection))
        assertEquals(savedPlayback.executionBinding, afterReading.executionBinding)
        assertEquals(savedPlayback.activeContext, afterReading.activeContext)
        assertEquals(savedPlayback.positionMs, afterReading.positionMs)
        assertEquals(savedPlayback.desiredPlaying, afterReading.desiredPlaying)
        assertTrue(app.engine.loads.isEmpty(), "Layout changes and disclosure must not prepare another source")
        capture("fixture-player-ar-dark-320x760-font200-long-provenance-expanded")
    }

    @Test fun freshTypedLibraryPlayUsesTheCanonicalOccurrenceAndRendersInTheQueuePanel() {
        val second = CoreShellTestFixture.second
        val app = show(CoreShellTestFixture.snapshot(navigation = AppNavigation(destination = AppDestination.LIBRARY)))
        libraryScrollTo("play-${second.id}")
        ui.onNodeWithTag("play-${second.id}").assertIsDisplayed().performClick()
        await {
            val playback = app.container.playback.state.value
            playback.currentSelection?.catalogMedia?.id == second.id &&
                playback.currentSelection?.selectionState == OccurrenceSelectionState.BOUND &&
                playback.isPlaying
        }
        val queue = canonicalQueue(app, QueueContext.MUSIC)
        val occurrence = queue.displayedOrder.single()
        assertEquals(4L, queue.editRevision,
            "Fresh Play uses the replace admission and its explicit activation queue edit")
        assertEquals(listOf(second.title), app.gateway.queries.toList())
        assertEquals(1, app.gateway.prepareInvocations.get())
        assertEquals(1, app.gateway.prepareCompletions.get())
        assertEquals(0, app.gateway.prepareFailures.get())
        assertEquals(0, app.gateway.prepareCancellations.get())
        assertEquals(second.id, app.engine.loads.single().mediaId)
        assertTrue(app.store.state.value.queues.getValue(QueueContext.MUSIC).entries.isEmpty(),
            "Canonical occurrences must not be copied into the legacy queue projection")
        assertTrue(app.store.state.value.playbackBindings.isEmpty())
        ui.onNodeWithTag("mini-queue").performClick()
        await { app.container.navigation.value.queueOpen }
        queueScrollTo("queue-entry-${occurrence.value}")
        ui.onNodeWithTag("queue-entry-${occurrence.value}").assertIsDisplayed()
        capture("fixture-shell-en-light-fresh-typed-queue")
    }

    private fun show(initial: AppSnapshot = CoreShellTestFixture.snapshot(), width: Dp = 960.dp, height: Dp = 720.dp,
        typedTitles: CoreShellTypedTitles = CoreShellTypedTitles()): CoreShellTestFixture {
        val app = runBlocking { withTimeout(20_000L) { CoreShellTestFixture.create(initial, typedTitles) } }
        fixture = app
        viewport = DpSize(width, height)
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.size(viewport.width, viewport.height)) { if (mounted) VeyraApp(app.container) }
            }
        }
        ui.onNodeWithTag("veyra-app").assertIsDisplayed()
        ui.waitForIdle()
        assertViewport(width, height)
        return app
    }

    private fun canonicalQueue(app: CoreShellTestFixture, context: QueueContext): ExecutionQueue =
        app.store.acquisitionViews.value.acquisition.queues.getValue(context)

    private fun occurrenceMediaId(app: CoreShellTestFixture, occurrenceId: ExecutionOccurrenceId): String? =
        PlaybackSelection.project(app.store.acquisitionViews.value, occurrenceId).catalogMedia?.id

    private data class QueueFacts(
        val displayedOrder: List<String>,
        val originalOrder: List<String>,
        val currentOccurrenceId: String?,
        val positionMs: Long,
        val speed: Float,
        val shuffle: Boolean,
        val repeat: RepeatMode,
    )

    private fun queueFacts(queue: ExecutionQueue) = QueueFacts(
        displayedOrder = queue.displayedOrder.map { it.value },
        originalOrder = queue.originalOrder.map { it.value },
        currentOccurrenceId = queue.currentOccurrenceId?.value,
        positionMs = queue.positionMs,
        speed = queue.speed,
        shuffle = queue.shuffle,
        repeat = queue.repeat,
    )

    private data class SelectionIdentity(
        val occurrenceId: String?,
        val bindingId: String?,
        val subjectId: String?,
        val requestId: String?,
        val receiptId: String?,
    )

    private fun selectionIdentity(selection: PlaybackSelection?) = SelectionIdentity(
        occurrenceId = selection?.occurrenceId?.value,
        bindingId = selection?.bindingId?.value,
        subjectId = selection?.subjectId?.value,
        requestId = selection?.requestId?.value,
        receiptId = selection?.receiptId?.value,
    )

    private fun resize(width: Dp, height: Dp) {
        ui.runOnIdle { viewport = DpSize(width, height) }
        ui.waitForIdle()
        assertViewport(width, height)
    }

    private fun assertViewport(width: Dp, height: Dp) {
        val actual = ui.onNodeWithTag("veyra-app").getUnclippedBoundsInRoot()
        assertEquals(width, actual.right - actual.left, "The Compose fixture must use its claimed viewport width")
        assertEquals(height, actual.bottom - actual.top, "The Compose fixture must use its claimed viewport height")
    }

    private fun await(condition: () -> Boolean) {
        ui.waitUntil(timeoutMillis = 10_000L, condition = condition)
        ui.waitForIdle()
    }

    private fun queueScrollTo(tag: String) {
        ui.onNodeWithTag("queue-panel").performScrollToNode(hasTestTag(tag))
        ui.onNodeWithTag(tag).performScrollTo()
    }

    private fun libraryScrollTo(tag: String) {
        ui.onNodeWithTag("library-list").performScrollToNode(hasTestTag(tag))
        ui.onNodeWithTag(tag).performScrollTo()
    }

    private fun trackScrollTo(tag: String) {
        ui.onNodeWithTag("track-detail-content").performScrollToNode(hasTestTag(tag))
        ui.onNodeWithTag(tag).performScrollTo()
    }

    private fun SemanticsNodeInteraction.focus(): SemanticsNodeInteraction =
        performSemanticsAction(SemanticsActions.RequestFocus) { request -> request() }.assertIsFocused()

    private fun shortcut(key: Key) = sendKey(key, ctrl = true)

    /**
     * Use the pinned Compose scene ingress with explicit modifiers and a native-backed
     * synthetic typed event, as the desktop text field requires KEY_TYPED. Keep the
     * consumed result for diagnosing focus/modal routing; this is not an OS keyboard.
     */
    @OptIn(InternalComposeUiApi::class, InternalTestApi::class)
    private fun sendKey(key: Key, ctrl: Boolean = false, typedCharacter: Char? = null) {
        ui.waitForIdle()
        ui.runOnUiThread {
            val scene = (ui as DesktopComposeTestRule).scene
            fun dispatch(event: KeyEvent, phase: String) {
                val consumed = scene.sendKeyEvent(event)
                keyDispatches += "scene=${scene.javaClass.name}, key=$key, phase=$phase, ctrl=$ctrl, consumed=$consumed"
            }
            dispatch(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl), "down")
            typedCharacter?.let { character ->
                val native = AwtKeyEvent(object : Component() {}, AwtKeyEvent.KEY_TYPED,
                    0L, 0, AwtKeyEvent.VK_UNDEFINED, character)
                dispatch(KeyEvent(Key.Unknown, KeyEventType.Unknown, character.code, nativeEvent = native), "typed")
            }
            dispatch(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl), "up")
        }
        ui.waitForIdle()
    }

    /** One real scene phase, allowing ordinary focus transfer while the key stays held. */
    @OptIn(InternalComposeUiApi::class, InternalTestApi::class)
    private fun sendKeyPhase(key: Key, type: KeyEventType, shift: Boolean = false): Boolean {
        require(type == KeyEventType.KeyDown || type == KeyEventType.KeyUp)
        ui.waitForIdle()
        val consumed = ui.runOnUiThread {
            val scene = (ui as DesktopComposeTestRule).scene
            val handled = scene.sendKeyEvent(KeyEvent(key, type, isShiftPressed = shift))
            keyDispatches += "scene=${scene.javaClass.name}, key=$key, phase=$type, shift=$shift, consumed=$handled"
            handled
        }
        ui.waitForIdle()
        return consumed
    }

    private fun escape() = sendKey(Key.Escape)

    /** Observe real scene ingress; timeout/assertion failures keep their original exception. */
    private fun escapeWithRoutingEvidence(name: String, afterEscape: () -> Unit) {
        val beforeEscape = safeKeyRoutingDiagnostics()
        capture(name + "-before", diagnostics = beforeEscape, settleHover = false)
        try {
            escape()
            afterEscape()
        } catch (failure: Throwable) {
            if (failure !is AssertionError && failure !is Exception &&
                failure !is ComposeTimeoutException) throw failure
            captureFailure(name + "-failure", failure,
                "Before Escape:\n" + beforeEscape + "\nAfter Escape:\n" + safeKeyRoutingDiagnostics(failure))
            throw failure
        }
        capture(name + "-after", diagnostics = safeKeyRoutingDiagnostics(), settleHover = false)
    }

    /** Evidence collection must not replace the original acceptance failure. */
    private fun safeKeyRoutingDiagnostics(failure: Throwable? = null): String = try {
        keyRoutingDiagnostics()
    } catch (diagnosticFailure: Throwable) {
        if (diagnosticFailure !is AssertionError && diagnosticFailure !is Exception &&
            diagnosticFailure !is ComposeTimeoutException) throw diagnosticFailure
        failure?.addSuppressed(diagnosticFailure)
        "Key-routing diagnostics unavailable: ${diagnosticFailure.javaClass.name}"
    }

    private fun keyRoutingDiagnostics(): String = buildString {
        appendLine("Synthetic scene key dispatches: ${keyDispatches.joinToString("; ")}")
        appendLine(sceneLayerDiagnostics())
        appendLine("Navigation: ${fixture?.container?.navigation?.value}")
        appendLine("Focused nodes:\n${ui.onAllNodes(isFocused(), useUnmergedTree = true).printToString(maxDepth = 1)}")
        appendLine("Scene semantics roots, including separate modal/popup layers:\n" +
            ui.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = 8))
    }

    /** Read-only pinned-scene diagnostics; never inject focus or invoke a modal callback. */
    @OptIn(InternalComposeUiApi::class, InternalTestApi::class)
    private fun sceneLayerDiagnostics(): String {
        var diagnostic = "No scene-layer observation"
        ui.runOnUiThread {
            val scene = (ui as DesktopComposeTestRule).scene
            diagnostic = runCatching {
                fun field(instance: Any, name: String): Any? {
                    val declared = instance.javaClass.declaredFields.singleOrNull { it.name == name }
                        ?: throw NoSuchFieldException("${instance.javaClass.name}.$name")
                    declared.isAccessible = true
                    return declared.get(instance)
                }
                val layer = field(scene, "focusedLayer")
                val layers = field(scene, "layers")
                check(layers is Collection<*>) { "Expected layers Collection; observed ${layers?.javaClass?.name ?: "null"}" }
                "Scene=${scene.javaClass.name}; layerCount=${layers.size}; " +
                    "focusedLayer=${layer?.javaClass?.name ?: "null (field present)"}; " +
                    "focusable=${layer?.let { field(it, "focusable") }}; " +
                    "previewListener=${layer?.let { field(it, "onPreviewKeyEvent") }?.javaClass?.name}; " +
                    "keyListener=${layer?.let { field(it, "onKeyEvent") }?.javaClass?.name}"
            }.getOrElse { "Scene-layer inspection unavailable: ${it.javaClass.simpleName}: ${it.message}" }
        }
        return diagnostic
    }

    /** Fixed test density is one pixel per dp. Compare unclipped geometry with clipped semantics. */
    private fun assertFullyVisible(tag: String) {
        val node = ui.onNodeWithTag(tag)
        var diagnostics = "$tag has not yet produced geometry."
        try {
            val actual = node.getUnclippedBoundsInRoot()
            val clipped = node.fetchSemanticsNode().boundsInRoot
            val app = ui.onNodeWithTag("veyra-app").getUnclippedBoundsInRoot()
            val inViewport = actual.left >= app.left && actual.right <= app.right && actual.top >= app.top && actual.bottom <= app.bottom
            val actualWidth = actual.right - actual.left
            val actualHeight = actual.bottom - actual.top
            val largeEnough = actualWidth >= 48.dp && actualHeight >= 48.dp
            val unclipped = clipped.width + 1f >= actualWidth.value && clipped.height + 1f >= actualHeight.value
            diagnostics = "$tag: actual(dp)=$actual, clipped(px)=$clipped, viewport(dp)=$app; " +
                "inViewport=$inViewport, largeEnough=$largeEnough, unclipped=$unclipped\n${node.printToString(maxDepth = 2)}"
            node.assertIsDisplayed()
            assertTrue(inViewport, "$tag stays inside the app viewport: $diagnostics")
            assertTrue(largeEnough, "$tag keeps a 48dp control target: $diagnostics")
            assertTrue(unclipped, "$tag is not merely partially visible: $diagnostics")
        } catch (failure: AssertionError) {
            // Also capture fully hidden/missing controls, not just those that pass isDisplayed.
            captureFailure("fixture-clipping-${tag.lowercase()}", failure, diagnostics)
            throw failure
        }
    }

    private fun assertLabelFits(parentTag: String, label: String) {
        val child = ui.onNode(hasText(label) and hasAnyAncestor(hasTestTag(parentTag)), useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        child.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        val text = child.getUnclippedBoundsInRoot()
        val clipped = child.fetchSemanticsNode().boundsInRoot
        val parent = ui.onNodeWithTag(parentTag).getUnclippedBoundsInRoot()
        val noOverflow = layouts.none { it.hasVisualOverflow }
        val inTarget = text.left >= parent.left && text.right <= parent.right && text.top >= parent.top && text.bottom <= parent.bottom
        val unclipped = clipped.width + 1f >= (text.right - text.left).value && clipped.height + 1f >= (text.bottom - text.top).value
        val diagnostics = textLayoutDiagnostics(child, parentTag, layouts)
        writeDiagnostic("fixture-label-layout-${parentTag.lowercase()}", diagnostics)
        try {
            child.assertIsDisplayed()
            assertTrue(layouts.isNotEmpty(), "No text layout result: $diagnostics")
            assertTrue(noOverflow, "$parentTag label must wrap without visual overflow: $diagnostics")
            assertTrue(inTarget, "$parentTag label stays within its own navigation target: $diagnostics")
            assertTrue(unclipped, "$parentTag label is not clipped by an ancestor: $diagnostics")
            assertTrue(layouts.all { layout -> (0 until layout.lineCount).all { line ->
                layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f &&
                    !layout.isLineEllipsized(line)
            } }, "$parentTag laid-out lines stay inside the text node without ellipsis: $diagnostics")
        } catch (failure: AssertionError) {
            captureFailure("fixture-label-clipping-${parentTag.lowercase()}", failure, diagnostics)
            throw failure
        }
    }

    private fun textLayoutDiagnostics(node: SemanticsNodeInteraction, parentTag: String, layouts: List<TextLayoutResult>): String = buildString {
        appendLine("Text bounds(dp)=${node.getUnclippedBoundsInRoot()}, clipped(px)=${node.fetchSemanticsNode().boundsInRoot}")
        appendLine("Parent $parentTag bounds(dp)=${ui.onNodeWithTag(parentTag).getUnclippedBoundsInRoot()}")
        layouts.forEachIndexed { index, layout ->
            appendLine("Layout $index: text=${layout.layoutInput.text}; constraints=${layout.layoutInput.constraints}; " +
                "density=${layout.layoutInput.density}; direction=${layout.layoutInput.layoutDirection}; " +
                "fontSize=${layout.layoutInput.style.fontSize}; lineHeight=${layout.layoutInput.style.lineHeight}")
            appendLine("size=${layout.size}; paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}; " +
                "overflowWidth=${layout.didOverflowWidth}; overflowHeight=${layout.didOverflowHeight}; " +
                "maxLinesExceeded=${layout.multiParagraph.didExceedMaxLines}; lines=${layout.lineCount}")
            (0 until layout.lineCount).forEach { line ->
                appendLine("line $line: offsets=${layout.getLineStart(line)}..${layout.getLineEnd(line)}, " +
                    "left=${layout.getLineLeft(line)}, right=${layout.getLineRight(line)}, " +
                    "top=${layout.getLineTop(line)}, bottom=${layout.getLineBottom(line)}, " +
                    "baseline=${layout.getLineBaseline(line)}, ellipsized=${layout.isLineEllipsized(line)}")
            }
            if (layout.layoutInput.text.length <= 128) {
                appendLine("Character advance boxes (not raster glyph-ink bounds):")
                layout.layoutInput.text.text.indices.forEach { offset ->
                    appendLine("offset $offset U+${layout.layoutInput.text[offset].code.toString(16)}: ${layout.getBoundingBox(offset)}")
                }
            }
        }
    }

    private fun evidenceFiles(name: String): Pair<File, File> {
        require(name.startsWith("fixture-") && name.matches(Regex("[a-z0-9-]+")))
        val method = checkNotNull(testName.methodName).replace(Regex("[^a-zA-Z0-9_-]"), "-")
        require(method.isNotBlank())
        val directory = coreShellOwnedDirectory("composeApp/build/test-evidence/music/core-shell/$method/$evidenceRun")
        val stem = "${(++evidenceSequence).toString().padStart(3, '0')}-$name"
        fun ownedFile(extension: String): File = File(directory, "$stem.$extension").canonicalFile.also {
            require(it.toPath().startsWith(directory.toPath())) { "Refusing an escaping screenshot path" }
            check(!it.exists()) { "Refusing to overwrite another observation: ${it.name}" }
        }
        return ownedFile("png") to ownedFile("txt")
    }

    private fun evidenceHeader() = """
        AUTHORED UI FIXTURE — NOT LIVE SOURCE OR NATIVE PLAYBACK EVIDENCE
        Actual VeyraApp/AppContainer/Room with deterministic engine and gateway test doubles.
        No catalog sockets, media acquisition, native decoding, or audible output.
        Test: ${CoreShellFlowsTest::class.java.name}.${testName.methodName}
        Observation group: $evidenceRun; method/run/sequence paths never overwrite an earlier capture.
        Synthetic Compose scene: 1024 x 768; app-content viewport: $viewport at density 1.
        Resizing changes that content pane, not a native OS window or independent modal scene.
        Generation proves only that this test reached this observation; inspect the image separately.
    """.trimIndent() + "\n"

    private fun writeDiagnostic(name: String, diagnostics: String) {
        evidenceFiles(name).second.writeText(evidenceHeader() + diagnostics)
    }

    private fun captureFailure(name: String, failure: Throwable, diagnostics: String,
        target: SemanticsNodeInteraction = ui.onNodeWithTag("veyra-app")) {
        try {
            // Do not move the mouse or advance 300ms after failure: retain the failing frame,
            // including any transient popup that could explain focus/occlusion.
            capture(name, target, "$diagnostics\nOriginal failure: ${failure.javaClass.name}: ${failure.message}", settleHover = false)
        } catch (captureError: AssertionError) {
            failure.addSuppressed(captureError)
        } catch (captureError: ComposeTimeoutException) {
            // The selected Compose timeout extends Throwable directly, not Exception.
            failure.addSuppressed(captureError)
        } catch (captureError: Exception) {
            failure.addSuppressed(captureError)
        }
    }

    private fun capture(name: String, target: SemanticsNodeInteraction = ui.onNodeWithTag("veyra-app"),
        diagnostics: String = "", settleHover: Boolean = true) {
        val (png, receipt) = evidenceFiles(name)
        receipt.writeText(evidenceHeader() + diagnostics + "\n")
        if (settleHover) {
            // Resting screenshots exclude hover tooltips; failure/modal captures do not.
            ui.onNodeWithTag("veyra-app").performMouseInput { moveTo(Offset(1f, 1f)); exit() }
            ui.mainClock.advanceTimeBy(300L)
        }
        ui.waitForIdle()
        receipt.appendText("Captured target:\n${target.printToString(maxDepth = 0)}\n")
        val bitmap = target.captureToImage()
        val pixels = bitmap.toPixelMap()
        val output = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) output.setRGB(x, y, pixels[x, y].toArgb())
        check(ImageIO.write(output, "png", png))
        receipt.appendText("Capture: ${png.name} (${bitmap.width} x ${bitmap.height} pixels).\n")
    }
}
