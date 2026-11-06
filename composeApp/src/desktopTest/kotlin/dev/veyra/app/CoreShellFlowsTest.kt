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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.InternalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
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
import androidx.compose.ui.unit.dp
import dev.veyra.model.AppFailure
import dev.veyra.model.AppSettings
import dev.veyra.model.AppSnapshot
import dev.veyra.model.FailureCode
import dev.veyra.model.LanguagePreference
import dev.veyra.model.QueueContext
import dev.veyra.model.ThemeMode
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
        val savedMusic = app.store.state.value.queue(QueueContext.MUSIC)
        assertFalse(app.container.playback.state.value.desiredPlaying)
        assertTrue(app.engine.loads.isEmpty())

        ui.onNodeWithTag("mini-queue").performClick()
        await { app.container.navigation.value.queueOpen }
        ui.onNodeWithTag("view-queue-PODCAST").performClick()
        await { app.container.navigation.value.viewedQueue == QueueContext.PODCAST }
        assertEquals(QueueContext.MUSIC, app.container.playback.state.value.activeContext)
        assertEquals(savedMusic, app.store.state.value.queue(QueueContext.MUSIC))
        assertTrue(app.engine.loads.isEmpty())
        assertTrue(app.gateway.queries.isEmpty())
        ui.onNode(hasText(music.title) and hasAnyAncestor(hasTestTag("mini-player")), useUnmergedTree = true).assertIsDisplayed()

        queueScrollTo("queue-play-episode-now")
        ui.onNodeWithTag("queue-play-episode-now").performClick()
        await { app.container.playback.state.value.currentEntryId == "episode-now" && app.container.playback.state.value.isPlaying }
        assertEquals(QueueContext.PODCAST, app.store.state.value.activeContext)
        assertEquals(savedMusic, app.store.state.value.queue(QueueContext.MUSIC))
        assertEquals(listOf(episode.title), app.gateway.queries.toList())
        assertEquals(episode.id, app.engine.loads.single().source.mediaId)

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
        val podcastQueue = app.store.state.value.queue(QueueContext.PODCAST)
        ui.onNodeWithTag("mini-queue").performClick()
        await { app.container.navigation.value.queueOpen }
        queueScrollTo("queue-up-music-copy")
        ui.onNodeWithTag("queue-up-music-copy").performClick()
        await { app.store.state.value.queue(QueueContext.MUSIC).entries.map { it.id } == listOf("music-now", "music-copy", "music-next") }
        val reordered = app.store.state.value.queue(QueueContext.MUSIC)
        assertFalse(reordered.shuffle)
        assertEquals("music-now", reordered.currentEntryId)
        assertEquals(17_000L, reordered.positionMs)
        assertEquals(podcastQueue, app.store.state.value.queue(QueueContext.PODCAST))

        queueScrollTo("clear-upcoming")
        ui.onNodeWithTag("clear-upcoming").performClick()
        assertEquals(reordered, app.store.state.value.queue(QueueContext.MUSIC), "Merely showing confirmation is non-destructive")
        ui.onNodeWithTag("confirm-action").performClick()
        await { app.store.state.value.queue(QueueContext.MUSIC).entries.map { it.id } == listOf("music-now") }
        assertEquals("music-now", app.store.state.value.queue(QueueContext.MUSIC).currentEntryId)
        assertEquals(17_000L, app.store.state.value.queue(QueueContext.MUSIC).positionMs)
        queueScrollTo("undo-queue")
        ui.onNodeWithTag("undo-queue").performClick()
        await { app.store.state.value.queue(QueueContext.MUSIC) == reordered }
        assertEquals(podcastQueue, app.store.state.value.queue(QueueContext.PODCAST))
        assertTrue(app.engine.loads.isEmpty(), "Editing future occurrences must not start audio")
        capture("fixture-shell-en-light-occurrence-queue")
    }

    @Test fun detailsFavoritesAndDuplicatePlaylistMembershipAreConnectedButDoNotPlayOrDeleteDownloads() {
        val app = show(CoreShellTestFixture.snapshot(navigation = AppNavigation(destination = AppDestination.LIBRARY)))
        val queues = app.store.state.value.queues
        val downloads = app.store.state.value.downloads
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
        assertEquals(queues, app.store.state.value.queues)
        assertEquals(downloads, app.store.state.value.downloads)
        assertTrue(app.engine.loads.isEmpty())
        assertTrue(app.gateway.queries.isEmpty())
    }

    @Test fun confirmedPlaylistShuffleHasOneUndoBackToTheOriginalOccurrenceQueue() {
        val navigation = AppNavigation(destination = AppDestination.LIBRARY,
            details = listOf(AppDetail(DetailKind.PLAYLIST, playlist.id)), viewedQueue = QueueContext.PODCAST)
        val app = show(CoreShellTestFixture.snapshot(navigation = navigation))
        val original = app.store.state.value.queue(QueueContext.MUSIC)
        val podcast = app.store.state.value.queue(QueueContext.PODCAST)
        ui.onNodeWithText(english.text(TextKey.Shuffle)).performClick()
        assertEquals(original, app.store.state.value.queue(QueueContext.MUSIC))
        assertTrue(app.gateway.queries.isEmpty())
        ui.onNodeWithTag("confirm-action").performClick()
        await { app.container.playback.state.value.isPlaying && app.store.state.value.queue(QueueContext.MUSIC).entries.size == 2 }
        assertTrue(app.store.state.value.queue(QueueContext.MUSIC).shuffle)
        assertEquals(listOf(music.id, music.id), app.store.state.value.queue(QueueContext.MUSIC).entries.map { it.mediaId })
        assertEquals(listOf(music.title), app.gateway.queries.toList())

        ui.onNodeWithText(english.text(TextKey.UndoQueueEdit)).performScrollTo().performClick()
        await { app.store.state.value.queue(QueueContext.MUSIC).entries.map { it.id } == original.entries.map { it.id } }
        assertEquals(original, app.store.state.value.queue(QueueContext.MUSIC))
        assertEquals(podcast, app.store.state.value.queue(QueueContext.PODCAST))
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
        val beforeEscape = keyRoutingDiagnostics()
        capture("fixture-escape-sleep-dialog-before", target = ui.onNode(isDialog()),
            diagnostics = beforeEscape, settleHover = false)
        escape()
        try {
            ui.onNodeWithTag("sleep-minutes").assertDoesNotExist()
        } catch (failure: AssertionError) {
            captureFailure("fixture-escape-sleep-dialog-after", failure,
                "Before Escape:\n$beforeEscape\nAfter Escape:\n${keyRoutingDiagnostics()}",
                target = ui.onNode(isDialog()))
            throw failure
        }
        assertTrue(app.container.navigation.value.playerOpen)
        ui.onNodeWithTag("player-queue").performScrollTo().performClick()
        await { app.container.navigation.value.queueOpen }
        escape()
        await { !app.container.navigation.value.queueOpen }
        assertTrue(app.container.navigation.value.playerOpen)
        ui.onNodeWithTag("full-player").assertIsDisplayed()
        escape()
        await { !app.container.navigation.value.playerOpen }
        ui.onNodeWithTag("mini-player").assertIsDisplayed()
        assertEquals("music-now", app.container.playback.state.value.currentEntryId)
        assertTrue(app.engine.loads.isEmpty())
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
            destinations.forEach { (destination, label) ->
                ui.onNodeWithTag("nav-${destination.name}").performClick()
                await { app.container.navigation.value.destination == destination }
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
        val receipt = CoreShellTestFixture.musicReceipt.copy(queryTitle = longTitle, firstResultTitle = longResolved)
        val initial = CoreShellTestFixture.snapshot().let { it.copy(
            media = it.media + (item.id to item), playbackBindings = mapOf(receipt.id to receipt)) }
        val app = show(initial)
        ui.onNodeWithTag("mini-open-player").performClick()
        await { app.container.navigation.value.playerOpen }
        assertEquals(receipt, app.container.playback.state.value.executionBinding)
        val essential = listOf("close-player", "player-seek", "player-previous", "player-toggle", "player-next")
        essential.forEach(::assertFullyVisible)
        capture("fixture-player-en-light-960x720-long-metadata-initial")

        resize(720.dp, 360.dp)
        essential.forEach(::assertFullyVisible) // No scroll or prepositioning of the player.
        capture("fixture-player-en-light-720x360-long-metadata-resized")

        app.container.changeSettings { it.copy(language = LanguagePreference.ARABIC, theme = ThemeMode.DARK, textScale = 2f) }
        await { app.store.state.value.settings.textScale == 2f }
        resize(320.dp, 760.dp)
        essential.forEach(::assertFullyVisible)
        capture("fixture-player-ar-dark-320x760-font200-long-metadata-resized")

        val strings = AppStrings(LanguagePreference.ARABIC)
        ui.onNodeWithText(strings.text(TextKey.ShowMore)).performScrollTo().performClick()
        listOf(receipt.queryTitle, receipt.firstResultTitle).forEach { title ->
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
        assertEquals("music-now", app.container.playback.state.value.currentEntryId)
        assertTrue(app.engine.loads.isEmpty(), "Layout changes and disclosure must not prepare another source")
        capture("fixture-player-ar-dark-320x760-font200-long-provenance-expanded")
    }

    private fun show(initial: AppSnapshot = CoreShellTestFixture.snapshot(), width: Dp = 960.dp, height: Dp = 720.dp): CoreShellTestFixture {
        val app = runBlocking { withTimeout(20_000L) { CoreShellTestFixture.create(initial) } }
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

    private fun escape() = sendKey(Key.Escape)

    private fun keyRoutingDiagnostics(): String = buildString {
        appendLine("Synthetic scene key dispatches: ${keyDispatches.joinToString("; ")}")
        appendLine("Navigation: ${fixture?.container?.navigation?.value}")
        appendLine("Focused nodes:\n${ui.onAllNodes(isFocused(), useUnmergedTree = true).printToString(maxDepth = 1)}")
        appendLine("Scene semantics roots, including separate modal/popup layers:\n" +
            ui.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = 8))
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

    private fun captureFailure(name: String, failure: AssertionError, diagnostics: String,
        target: SemanticsNodeInteraction = ui.onNodeWithTag("veyra-app")) {
        try {
            // Do not move the mouse or advance 300ms after failure: retain the failing frame,
            // including any transient popup that could explain focus/occlusion.
            capture(name, target, "$diagnostics\nOriginal assertion: ${failure.message}", settleHover = false)
        } catch (captureError: AssertionError) {
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
