package dev.veyra.ui.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.veyra.model.AppFailure
import dev.veyra.model.AppSettings
import dev.veyra.model.AppSnapshot
import dev.veyra.model.DownloadRecord
import dev.veyra.model.DownloadStatus
import dev.veyra.model.FailureCode
import dev.veyra.model.FirstResultKind
import dev.veyra.model.LanguagePreference
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.OfflineAsset
import dev.veyra.model.PlaybackBinding
import dev.veyra.model.QueueContext
import dev.veyra.model.QueueEntry
import dev.veyra.model.QueueSnapshot
import dev.veyra.model.RepeatMode
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.playback.PlaybackPhase
import dev.veyra.playback.CrossfadePhase
import dev.veyra.playback.CrossfadeState
import dev.veyra.playback.PlaybackState
import dev.veyra.playback.SleepTimerMode
import dev.veyra.playback.SleepTimerState
import dev.veyra.source.MediaCollection
import dev.veyra.source.SourcePage
import dev.veyra.ui.design.AppStrings
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/** Authored UI fixtures exercise intent wiring. They are never a real-source/playback claim. */
@OptIn(ExperimentalTestApi::class)
class MusicSurfacesTest {
    @get:Rule val ui = createComposeRule()

    private val music = MediaItem("music", "Authored music fixture", MediaKind.MUSIC, SourceRef(SourceProvider.LOCAL, "music"), creator = "Veyra UI tests", durationMs = 600_000L)
    private val episode = MediaItem("episode", "حلقة تصميم — Authored episode", MediaKind.EPISODE, SourceRef(SourceProvider.RSS, "episode"), creator = "ناشر تجريبي", durationMs = 900_000L, showId = "show")
    private val strings = AppStrings(LanguagePreference.ENGLISH)

    @Test fun inspectingPodcastQueueDoesNotChangeAudibleMiniPlayer() {
        val musicQueue = QueueSnapshot(listOf(QueueEntry("music-now", music.id)), currentEntryId = "music-now")
        val podcastQueue = QueueSnapshot(listOf(QueueEntry("episode-now", episode.id)), currentEntryId = "episode-now")
        val snapshot = AppSnapshot(media = listOf(music, episode).associateBy { it.id }, queues = mapOf(QueueContext.MUSIC to musicQueue, QueueContext.PODCAST to podcastQueue))
        val playback = playing(music, musicQueue)
        val actions = mutableListOf<MusicUiAction>()
        var viewed by mutableStateOf(QueueContext.MUSIC)
        ui.setContent {
            VeyraTheme(false, LanguagePreference.ENGLISH) {
                Column(Modifier.size(900.dp, 660.dp)) {
                    QueuePanel(snapshot, playback.copy(queues = snapshot.queues), viewed, {
                        actions += it
                        if (it is MusicUiAction.ViewQueue) viewed = it.context
                    }, Modifier.weight(1f))
                    MiniPlayer(snapshot, playback, { actions += it })
                }
            }
        }
        ui.onNodeWithTag("view-queue-PODCAST").performClick()
        assertEquals(listOf<MusicUiAction>(MusicUiAction.ViewQueue(QueueContext.PODCAST)), actions)
        ui.onNode(hasText(music.title) and hasAnyAncestor(hasTestTag("mini-player")), useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithTag("queue-play-episode-now").performClick()
        assertEquals(MusicUiAction.PlayQueueEntry(QueueContext.PODCAST, "episode-now"), actions.last())
    }

    @Test fun duplicateQueueRowsMoveAndRemoveOnlyTheChosenOccurrenceAndClearRequiresConfirmation() {
        val queue = QueueSnapshot(listOf(QueueEntry("now", music.id), QueueEntry("copy-a", music.id), QueueEntry("copy-b", music.id)), "now", shuffle = true)
        val snapshot = AppSnapshot(media = mapOf(music.id to music), queues = mapOf(QueueContext.MUSIC to queue))
        val actions = mutableListOf<MusicUiAction>()
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) { QueuePanel(snapshot, playing(music, queue), QueueContext.MUSIC, { actions += it }, Modifier.size(900.dp, 660.dp), MusicUiOptions(undoableQueueContexts = setOf(QueueContext.MUSIC))) } }
        ui.onNodeWithTag("queue-panel").performScrollToNode(hasTestTag("queue-up-copy-b"))
        ui.onNodeWithTag("queue-up-copy-b").performClick()
        assertEquals(MusicUiAction.MoveQueueEntry(QueueContext.MUSIC, "copy-b", 1), actions.last())
        ui.onNodeWithTag("queue-panel").performScrollToNode(hasTestTag("queue-remove-copy-a"))
        ui.onNodeWithTag("queue-remove-copy-a").performClick()
        assertEquals(MusicUiAction.RemoveQueueEntry(QueueContext.MUSIC, "copy-a"), actions.last())
        ui.onNodeWithTag("queue-panel").performScrollToNode(hasTestTag("clear-upcoming"))
        ui.onNodeWithTag("clear-upcoming").performClick()
        assertEquals(2, actions.size)
        ui.onNodeWithTag("confirm-action").performClick()
        assertEquals(MusicUiAction.ClearQueue(QueueContext.MUSIC), actions.last())
        ui.onNodeWithTag("undo-queue").performClick()
        assertEquals(MusicUiAction.UndoQueue(QueueContext.MUSIC), actions.last())
    }

    @Test fun episodeControlsUseConfiguredTimeSkipsAndNeverInheritMusicRepeatOrShuffle() {
        val queue = QueueSnapshot(listOf(QueueEntry("episode-now", episode.id)), "episode-now")
        val snapshot = AppSnapshot(media = mapOf(episode.id to episode), settings = AppSettings(skipBackSeconds = 21, skipForwardSeconds = 37), queues = mapOf(QueueContext.PODCAST to queue))
        val actions = mutableListOf<MusicUiAction>()
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) { FullPlayer(snapshot, playing(episode, queue), { actions += it }, Modifier.size(1_000.dp, 700.dp)) } }
        ui.onNodeWithTag("player-shuffle").assertDoesNotExist()
        ui.onNodeWithTag("player-repeat").assertDoesNotExist()
        ui.onNodeWithTag("player-skip-back").assertIsDisplayed().performClick()
        ui.onNodeWithTag("player-skip-forward").performClick()
        assertEquals(listOf<MusicUiAction>(MusicUiAction.Skip(-21_000L, "episode-now", 0L), MusicUiAction.Skip(37_000L, "episode-now", 0L)), actions)
        ui.onNodeWithTag("player-sleep").performScrollTo().performClick()
        ui.onNodeWithTag("sleep-minutes").performTextReplacement("١٢")
        ui.onNodeWithTag("sleep-confirm").performClick()
        assertEquals(MusicUiAction.SetSleepMinutes(12), actions.last())
        ui.onNodeWithTag("player-speed").performScrollTo().performClick()
        ui.onNodeWithTag("speed-1.5").performClick()
        ui.onNodeWithTag("speed-confirm").performClick()
        assertEquals(MusicUiAction.SetSpeed(1.5f), actions.last())
    }

    @Test fun seekCommitsAnExpectedOccurrenceAndBufferingCanStillBePaused() {
        val queue = QueueSnapshot(listOf(QueueEntry("now", music.id)), "now")
        val playback = playing(music, queue).copy(phase = PlaybackPhase.BUFFERING, isPlaying = false, positionMs = 30_000L)
        val actions = mutableListOf<MusicUiAction>()
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) { FullPlayer(AppSnapshot(media = mapOf(music.id to music)), playback, { actions += it }, Modifier.size(1_000.dp, 700.dp)) } }
        ui.onNodeWithTag("player-seek").assertIsDisplayed().performSemanticsAction(SemanticsActions.SetProgress) { set -> set(.5f) }
        assertEquals(MusicUiAction.Seek(300_000L, "now", playback.generation), actions.last())
        ui.onNodeWithContentDescription(strings.text(TextKey.Pause)).assertIsEnabled().performClick()
        assertEquals(MusicUiAction.TogglePlayPause, actions.last())
        assertEquals(30_000L, playback.positionMs) // UI never manufactures an advancing native clock.
    }

    @Test fun seekHasAReal48DpTargetWithProgressKeyboardAndBothMouseAndTouchEdges() {
        val queue = QueueSnapshot(listOf(QueueEntry("now", music.id)), "now")
        var playback by mutableStateOf(playing(music, queue).copy(generation = 17L))
        var language by mutableStateOf(LanguagePreference.ENGLISH)
        val actions = mutableListOf<MusicUiAction>()
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                VeyraTheme(false, language) {
                    Box(Modifier.size(360.dp, 640.dp)) {
                        FullPlayer(AppSnapshot(media = mapOf(music.id to music)), playback, { action ->
                            actions += action
                            if (action is MusicUiAction.Seek) playback = playback.copy(positionMs = action.positionMs)
                        })
                    }
                }
            }
        }
        fun expectOneSeek(before: Int, range: LongRange) {
            ui.waitForIdle()
            assertEquals(before + 1, actions.size, "A single gesture must emit exactly one bound seek")
            val seek = assertIs<MusicUiAction.Seek>(actions.last())
            assertEquals("now", seek.expectedEntryId)
            assertEquals(17L, seek.expectedGeneration)
            assertTrue(seek.positionMs in range, "Seek ${seek.positionMs} belongs to $range")
        }
        listOf(LanguagePreference.ENGLISH, LanguagePreference.ARABIC).forEach { shownLanguage ->
            ui.runOnIdle { language = shownLanguage; playback = playback.copy(positionMs = 30_000L); actions.clear() }
            val slider = ui.onNodeWithTag("player-seek").assertIsDisplayed().assertIsEnabled()
            val bounds = slider.getUnclippedBoundsInRoot()
            val clipped = slider.fetchSemanticsNode().boundsInRoot
            val frame = ui.onNodeWithTag("full-player").getUnclippedBoundsInRoot()
            assertTrue(bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
            assertTrue(clipped.width + 1f >= (bounds.right - bounds.left).value &&
                clipped.height + 1f >= (bounds.bottom - bounds.top).value, "The target must not be clipped")
            assertTrue(bounds.left >= frame.left && bounds.right <= frame.right &&
                bounds.top >= frame.top && bounds.bottom <= frame.bottom)
            val progress = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(0f..1f, progress.range)
            assertEquals(0, progress.steps)
            slider.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(.5f)) }
            expectOneSeek(0, 300_000L..300_000L)
            assertEquals(.5f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current)
            slider.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }.assertIsFocused()
            slider.performKeyInput { pressKey(Key.DirectionRight) }
            expectOneSeek(1, 300_001L..600_000L)
            slider.performKeyInput { pressKey(Key.Spacebar) }
            ui.waitForIdle()
            assertEquals(2, actions.size, "Space on the slider is not a transport toggle")

            // These are actual pointer gestures at both vertical edges of the reported target,
            // not SetProgress calls on an enlarged test-only semantics rectangle.
            // Skiko click() does not establish hover; an unmatched exit() throws.
            // Disposal can still flush the click while that error unwinds; do not infer dropped input.
            slider.performMouseInput { click(Offset(width * .25f, .5f)) }
            expectOneSeek(2, 100_000L..200_000L)
            slider.performMouseInput { click(Offset(width * .75f, height - .5f)) }
            expectOneSeek(3, 400_000L..500_000L)
            slider.performTouchInput { click(Offset(width * .3f, .5f)) }
            expectOneSeek(4, 130_000L..230_000L)
            slider.performTouchInput { click(Offset(width * .7f, height - .5f)) }
            expectOneSeek(5, 370_000L..470_000L)
        }
    }

    @Test fun compactMiniKeepsBothRecordingIdentitiesAndAllControlsWithACompleteDetailRoute() {
        val requested = music.copy(title = "Authored requested catalog recording — عنوان كامل ".repeat(3).trim())
        val receipt = PlaybackBinding("mini-binding", requested.id, requested.title, FirstResultKind.VIDEO,
            "resolved-video", "Different resolved source recording — تسجيل مختلف ".repeat(3).trim(), selectedVideoId = "resolved-video")
        val queue = QueueSnapshot(listOf(QueueEntry("now", requested.id)), "now")
        val playback = playing(requested, queue).copy(executionBinding = receipt)
        val snapshot = AppSnapshot(media = mapOf(requested.id to requested))
        val actions = mutableListOf<MusicUiAction>()
        var compact by mutableStateOf(false)
        var opened by mutableStateOf(false)
        val arabic = AppStrings(LanguagePreference.ARABIC)
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.75f)) {
                VeyraTheme(true, LanguagePreference.ARABIC) {
                    Box(Modifier.size(616.dp, 700.dp)) {
                        if (opened) FullPlayer(snapshot, playback, actions::add)
                        else MiniPlayer(snapshot, playback, { action ->
                            actions += action
                            if (action == MusicUiAction.OpenPlayer) opened = true
                        }, compactHeight = compact)
                    }
                }
            }
        }
        val regularBounds = ui.onNodeWithTag("mini-player").getUnclippedBoundsInRoot()
        ui.runOnIdle { compact = true }
        val compactBounds = ui.onNodeWithTag("mini-player").getUnclippedBoundsInRoot()
        assertTrue(compactBounds.bottom - compactBounds.top < regularBounds.bottom - regularBounds.top,
            "Short-window metadata must actually release content height")
        listOf("mini-open-player", "mini-toggle", "mini-queue").forEach { tag ->
            val node = ui.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
            assertTrue(bounds.left >= compactBounds.left && bounds.right <= compactBounds.right &&
                bounds.top >= compactBounds.top && bounds.bottom <= compactBounds.bottom)
        }
        ui.onNode(hasText(requested.title) and hasAnyAncestor(hasTestTag("mini-player")), useUnmergedTree = true)
            .assertTextEquals(requested.title)
        ui.onNodeWithText(arabic.format(TextKey.ResolvedTitleValue, receipt.firstResultTitle), useUnmergedTree = true)
            .assertTextEquals(arabic.format(TextKey.ResolvedTitleValue, receipt.firstResultTitle))
        ui.onNodeWithTag("mini-open-player").performClick()
        ui.onNodeWithTag("full-player").assertIsDisplayed()
        ui.onNodeWithText(arabic.text(TextKey.ShowMore)).performScrollTo().performClick()
        listOf(receipt.queryTitle, receipt.firstResultTitle).forEach { title ->
            ui.onNode(hasText(title) and hasAnyAncestor(hasTestTag("playback-provenance")), useUnmergedTree = true)
                .performScrollTo().assertIsDisplayed().assertTextEquals(title)
        }
        assertEquals(listOf<MusicUiAction>(MusicUiAction.OpenPlayer), actions,
            "Disclosure alone never activates audio or resolves another recording")
    }

    @Test fun shortLibraryQueryScrollsIndependentlyOfLargeOptionRows() {
        val snapshot = AppSnapshot(media = mapOf(music.id to music), favoriteIds = setOf(music.id))
        val arabic = AppStrings(LanguagePreference.ARABIC)
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.75f)) {
                VeyraTheme(true, LanguagePreference.ARABIC) {
                    Box(Modifier.size(616.dp, 160.dp)) { LibraryScreen(snapshot, LibrarySection.FAVORITES, {}) }
                }
            }
        }
        ui.onNodeWithTag("library-list").performScrollToNode(hasTestTag("library-query"))
        val query = ui.onNodeWithTag("library-query").performScrollTo().assertIsDisplayed().assertIsEnabled()
        val bounds = query.getUnclippedBoundsInRoot()
        val clipped = query.fetchSemanticsNode().boundsInRoot
        val frame = ui.onNodeWithTag("library-list").getUnclippedBoundsInRoot()
        assertTrue(bounds.top >= frame.top && bounds.bottom <= frame.bottom)
        assertTrue(clipped.height + 1f >= (bounds.bottom - bounds.top).value,
            "The complete query target must fit the short content pane")
        query.performTextReplacement("Authored")
        ui.onNodeWithText(arabic.text(TextKey.SortByName)).performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("library-list").performScrollToNode(hasTestTag("play-${music.id}"))
        ui.onNodeWithTag("play-${music.id}").assertIsDisplayed()
    }

    @Test fun repeatOneHasAnExplicitVisibleLabelAndCyclesBackToOff() {
        val initial = QueueSnapshot(listOf(QueueEntry("now", music.id)), "now", repeat = RepeatMode.ALL)
        var playback by mutableStateOf(playing(music, initial))
        val actions = mutableListOf<MusicUiAction>()
        ui.setContent {
            VeyraTheme(false, LanguagePreference.ENGLISH) {
                FullPlayer(AppSnapshot(media = mapOf(music.id to music)), playback, {
                    actions += it
                    if (it is MusicUiAction.SetRepeat) playback = playback.copy(queues = mapOf(QueueContext.MUSIC to playback.queue.copy(repeat = it.mode)))
                }, Modifier.size(1_000.dp, 700.dp))
            }
        }
        ui.onNodeWithTag("player-repeat").performScrollTo().performClick()
        ui.onNodeWithText(strings.text(TextKey.RepeatOne)).assertIsDisplayed()
        assertEquals(MusicUiAction.SetRepeat(RepeatMode.ONE), actions.last())
        ui.onNodeWithTag("player-repeat").performClick()
        ui.onNodeWithText(strings.text(TextKey.RepeatOff)).assertIsDisplayed()
    }

    @Test fun sourceFailureDoesNotHideLocalSearchResultsOrEchoDiagnosticText() {
        val local = music.copy(title = "Local calm recording")
        val state = SearchUiState(query = "calm", searched = true, failure = AppFailure(FailureCode.PROVIDER_ACCESS_REQUIRED, detail = "internal diagnostic sentinel"))
        val snapshot = AppSnapshot(media = mapOf(local.id to local))
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) { SearchScreen(snapshot, state, {}, Modifier.size(900.dp, 700.dp)) } }
        ui.onNodeWithTag("play-${local.id}").assertIsDisplayed()
        ui.onNodeWithTag("search-screen").performScrollToNode(hasText(strings.text(TextKey.SourceRestricted)))
        ui.onNodeWithText(strings.text(TextKey.SourceRestricted)).assertIsDisplayed()
        ui.onAllNodesWithText("internal diagnostic sentinel", substring = true).assertCountEquals(0)
    }

    @Test fun requestedAndFirstResultTitlesStayDistinctAndOnlyConfirmedOverlapLooksActive() {
        val requested = music.copy(source = SourceRef(SourceProvider.COMMONS, "requested"))
        val next = music.copy(id = "incoming", title = "Incoming requested title")
        val queue = QueueSnapshot(listOf(QueueEntry("now", requested.id), QueueEntry("next", next.id)), "now")
        val receipt = PlaybackBinding("binding", requested.id, requested.title, FirstResultKind.VIDEO, "first-result", "Different first-result recording", selectedVideoId = "first-result")
        val incomingReceipt = PlaybackBinding("incoming-binding", next.id, next.title, FirstResultKind.VIDEO, "incoming-result", "Different incoming recording", selectedVideoId = "incoming-result")
        var playback by mutableStateOf(playing(requested, queue).copy(executionBinding = receipt, crossfade = CrossfadeState(CrossfadePhase.PREPARING, next, "next")))
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) { FullPlayer(AppSnapshot(media = listOf(requested, next).associateBy { it.id }), playback, {}, Modifier.size(1_000.dp, 700.dp)) } }
        ui.onNodeWithText(receipt.firstResultTitle).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText(strings.text(TextKey.SourceMismatchDetected)).performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("player-crossfade").performScrollTo()
        ui.onNodeWithText(strings.text(TextKey.CrossfadePreparing)).assertIsDisplayed()
        ui.onNodeWithTag("crossfade-progress").assertDoesNotExist()
        ui.runOnIdle { playback = playback.copy(crossfade = playback.crossfade!!.copy(phase = CrossfadePhase.OVERLAPPING, fraction = .35f, incomingExecutionBinding = incomingReceipt)) }
        ui.onNodeWithText(strings.text(TextKey.CrossfadeActive)).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText(strings.text(TextKey.IncomingAudioResolution)).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText(incomingReceipt.firstResultTitle).performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("crossfade-progress").performScrollTo().assertIsDisplayed()
        captureEvidence("player-en-light-confirmed-crossfade")
        assertEquals(requested, playback.currentItem)
    }

    @Test fun aNonFirstReceiptNeverClaimsToBeTheFirstOrderedResult() {
        val receipt = PlaybackBinding("invalid-ordinal", music.id, music.title, FirstResultKind.VIDEO,
            "later-video", "A later result", selectedVideoId = "later-video", selectionOrdinal = 2)
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) {
            PlaybackProvenanceCard(music, receipt, Modifier.size(700.dp, 500.dp))
        } }
        ui.onNodeWithText(strings.text(TextKey.InvalidState)).assertIsDisplayed()
        ui.onNodeWithText(strings.text(TextKey.FirstSearchResult)).assertDoesNotExist()
        ui.onNodeWithText(strings.text(TextKey.SourceMismatchDetected)).assertDoesNotExist()
        ui.onNodeWithText(strings.text(TextKey.SourceMatchUnverified)).assertIsDisplayed()
    }

    @Test fun pagedCollectionsNeverPretendTheFirstPageIsTheWholeAlbum() {
        val collection = MediaCollection("collection", "Real source collection fixture", SourceRef(SourceProvider.YOUTUBE, "collection"))
        var state by mutableStateOf(CollectionUiState(collection, SourcePage(items = listOf(music), nextPageToken = "next"), loaded = true))
        val actions = mutableListOf<MusicUiAction>()
        val snapshot = AppSnapshot(media = mapOf(music.id to music))
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) { CollectionDetailScreen(snapshot, state, { actions += it }, Modifier.size(900.dp, 700.dp)) } }
        ui.onNodeWithTag("collection-play").assertIsNotEnabled()
        ui.onNodeWithTag("play-${music.id}").performScrollTo().assertIsEnabled()
        ui.onNodeWithTag("collection-load-more").performScrollTo().performClick()
        assertEquals(listOf<MusicUiAction>(MusicUiAction.LoadMoreCollection), actions)
        ui.runOnIdle { state = state.copy(page = state.page.copy(nextPageToken = null)) }
        ui.onNodeWithTag("collection-detail").performScrollToNode(hasTestTag("collection-play"))
        ui.onNodeWithTag("collection-play").assertIsEnabled().performClick()
        assertEquals(MusicUiAction.PlayCollection(listOf(music)), actions.last())
    }

    @Test fun arabicLargeTextKeepsTransportLtrAndTimerVisibleWithKeyboardActivation() {
        val queue = QueueSnapshot(listOf(QueueEntry("episode-now", episode.id)), "episode-now")
        val playback = playing(episode, queue).copy(sleepTimer = SleepTimerState(SleepTimerMode.DURATION, remainingMs = 120_000L))
        val actions = mutableListOf<MusicUiAction>()
        lateinit var focusManager: FocusManager
        ui.setContent {
            val density = LocalDensity.current
            val fixtureFocus = LocalFocusManager.current
            SideEffect { focusManager = fixtureFocus }
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.75f)) {
                VeyraTheme(true, LanguagePreference.ARABIC) {
                    Box(Modifier.size(320.dp, 660.dp)) { FullPlayer(AppSnapshot(media = mapOf(episode.id to episode)), playback, { actions += it }) }
                }
            }
        }
        captureEvidence("player-ar-dark-large-top")
        ui.onNodeWithTag("player-toggle").assertIsDisplayed()
        val back = ui.onNodeWithTag("player-skip-back").fetchSemanticsNode().boundsInRoot
        val toggle = ui.onNodeWithTag("player-toggle").fetchSemanticsNode().boundsInRoot
        val forward = ui.onNodeWithTag("player-skip-forward").fetchSemanticsNode().boundsInRoot
        assertTrue(back.center.x < toggle.center.x && toggle.center.x < forward.center.x)
        ui.onNodeWithTag("player-toggle").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        ui.onNodeWithTag("player-toggle").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        assertEquals(MusicUiAction.TogglePlayPause, actions.last())
        ui.onNodeWithTag("player-timer").performScrollTo().assertIsDisplayed()
        // Keyboard activation is asserted above. A focused Material3 tooltip intentionally
        // persists until focus loss; clear it only for this resting-layout screenshot.
        ui.runOnIdle { focusManager.clearFocus(force = true) }
        captureEvidence("player-ar-dark-large-controls")
        ui.onNodeWithTag("cancel-sleep-timer").performClick()
        assertEquals(MusicUiAction.ClearSleepTimer, actions.last())
    }

    @Test fun verificationIsNotDisplayedAsReadyAndCancellationUsesTheDownloadId() {
        val download = DownloadRecord("download-id", music.id, DownloadStatus.VERIFYING, bytesDownloaded = 5_000L, totalBytes = 5_000L)
        val snapshot = AppSnapshot(media = mapOf(music.id to music), downloads = mapOf(download.id to download))
        val actions = mutableListOf<MusicUiAction>()
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) { LibraryScreen(snapshot, LibrarySection.DOWNLOADS, { actions += it }, Modifier.size(900.dp, 700.dp)) } }
        ui.onNodeWithText(strings.text(TextKey.DownloadVerifying)).assertIsDisplayed()
        ui.onAllNodesWithText(strings.text(TextKey.ReadyOffline)).assertCountEquals(0)
        ui.onNodeWithText(strings.text(TextKey.CancelDownload)).performClick()
        assertEquals(MusicUiAction.CancelDownload("download-id"), actions.last())
    }

    @Test fun recheckingDownloadRowsNeverOfferReadyPlaybackOrInactiveTransferControls() {
        val actions = mutableListOf<MusicUiAction>()
        var record by mutableStateOf(DownloadRecord("owned-file", music.id, DownloadStatus.VERIFYING,
            bytesDownloaded = 100, totalBytes = 100, asset = OfflineAsset("asset", music.id, "file:///owned/audio.m4a", 100)))
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) {
            LibraryScreen(AppSnapshot(media = mapOf(music.id to music), downloads = mapOf(record.id to record)),
                LibrarySection.DOWNLOADS, { actions += it }, Modifier.size(900.dp, 700.dp))
        } }
        listOf(DownloadStatus.VERIFYING, DownloadStatus.NEEDS_FOREGROUND).forEachIndexed { index, status ->
            ui.runOnIdle { record = record.copy(status = status) }
            ui.onNodeWithTag("downloads-list").performScrollToNode(hasTestTag("download-${record.id}"))
            ui.onNodeWithText(strings.downloadStatus(status)).assertIsDisplayed()
            listOf(TextKey.ReadyOffline, TextKey.Play, TextKey.PauseDownload, TextKey.ResumeDownload, TextKey.CancelDownload).forEach { key ->
                ui.onNodeWithText(strings.text(key)).assertDoesNotExist()
            }
            ui.onNodeWithText(strings.text(TextKey.RemoveDownload)).performClick()
            assertEquals(index, actions.size)
            ui.onNodeWithTag("cancel-action").performClick()
            assertEquals(index, actions.size)
            ui.onNodeWithText(strings.text(TextKey.RemoveDownload)).performClick()
            ui.onNodeWithTag("confirm-action").performClick()
            assertEquals(List<MusicUiAction>(index + 1) { MusicUiAction.RemoveDownload(record.id) }, actions)
        }
    }

    @Test fun recheckingMediaMenusKeepCheckingStatusAndRequireConfirmedRemoval() {
        val actions = mutableListOf<MusicUiAction>()
        var record by mutableStateOf(DownloadRecord("owned-file", music.id, DownloadStatus.VERIFYING,
            asset = OfflineAsset("asset", music.id, "file:///owned/audio.m4a", 100)))
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) {
            MusicMediaRow(music, AppSnapshot(media = mapOf(music.id to music), downloads = mapOf(record.id to record)),
                { actions += it }, Modifier.size(900.dp, 220.dp))
        } }
        listOf(DownloadStatus.VERIFYING, DownloadStatus.NEEDS_FOREGROUND).forEachIndexed { index, status ->
            ui.runOnIdle { record = record.copy(status = status) }
            ui.onNodeWithText(strings.downloadStatus(status)).assertIsDisplayed()
            ui.onNodeWithContentDescription(strings.format(TextKey.MoreActionsFor, music.title)).performClick()
            ui.onNodeWithTag("menu-download-status-${music.id}").assertIsNotEnabled().assertTextEquals(strings.downloadStatus(status))
            listOf(TextKey.PauseDownload, TextKey.ResumeDownload, TextKey.CancelDownload).forEach { key ->
                ui.onNodeWithText(strings.text(key)).assertDoesNotExist()
            }
            ui.onNodeWithText(strings.text(TextKey.RemoveDownload)).performClick()
            assertEquals(index, actions.size)
            ui.onNodeWithTag("confirm-action").performClick()
            assertEquals(List<MusicUiAction>(index + 1) { MusicUiAction.RemoveDownload(record.id) }, actions)
        }
    }

    private fun playing(item: MediaItem, queue: QueueSnapshot): PlaybackState {
        val context = if (item.kind == MediaKind.EPISODE) QueueContext.PODCAST else QueueContext.MUSIC
        return PlaybackState(initialized = true, activeContext = context, viewedContext = context, queues = mapOf(context to queue), currentItem = item, currentEntryId = queue.currentEntryId, phase = PlaybackPhase.PLAYING, desiredPlaying = true, isPlaying = true, positionMs = 30_000L, durationMs = item.durationMs, seekable = true)
    }

    private fun captureEvidence(name: String) {
        ui.onNodeWithTag("full-player").performMouseInput { moveTo(Offset(1f, 1f)); exit() }
        ui.mainClock.advanceTimeBy(300L)
        ui.waitForIdle()
        val image = ui.onNodeWithTag("full-player").captureToImage()
        val pixels = image.toPixelMap()
        val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) buffered.setRGB(x, y, pixels[x, y].toArgb())
        // Gradle's module working directory keeps all authored-fixture captures inside Veyra.
        val output = File("build/test-evidence/music/$name.png")
        output.parentFile.mkdirs()
        ImageIO.write(buffered, "png", output)
    }
}
