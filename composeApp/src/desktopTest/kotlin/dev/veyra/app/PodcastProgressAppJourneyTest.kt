package dev.veyra.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.veyra.data.getOrThrow
import dev.veyra.model.LanguagePreference
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.PodcastShow
import dev.veyra.model.QueueContext
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.ui.design.AppStrings
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraTheme
import dev.veyra.ui.music.MusicUiAction
import dev.veyra.ui.podcasts.PodcastAction
import dev.veyra.ui.podcasts.PodcastProgressRecording
import dev.veyra.ui.podcasts.podcastEpisodeUiState
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import dev.veyra.playback.PlaybackCommand
import dev.veyra.playback.getOrThrow as playbackResultGetOrThrow
import org.junit.Rule

/**
 * Real AppContainer/Room/controller journey through the existing VeyraApp show and episode
 * surfaces. Progress is canonical `MarkPlayed`; a missing show/detail progressView handoff would
 * leave these screens stale. Authored non-audio fixture; not device/native evidence.
 */
@OptIn(ExperimentalTestApi::class)
class PodcastProgressAppJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val strings = AppStrings(LanguagePreference.ENGLISH)
    private val show = PodcastShow("journey-show", "Journey show", "https://example.org/journey.xml",
        episodeIds = listOf("journey-episode"))
    private val episode = MediaItem("journey-episode", "Journey episode", MediaKind.EPISODE,
        SourceRef(SourceProvider.RSS, "journey-episode", feedUrl = show.feedUrl), showId = show.id)

    @Test
    fun canonicalResumeActivatesPodcastQueueWhileMusicIsActive() = runBlocking {
        withTimeout(180_000L) {
            val initial = CoreShellTestFixture.snapshot(navigation = AppNavigation(destination = AppDestination.PODCASTS,
                details = listOf(AppDetail(DetailKind.SHOW, CoreShellTestFixture.show.id))))
            val fixture = CoreShellTestFixture.create(initial)
            var mounted by mutableStateOf(true)
            try {
                compose.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                        Box(Modifier.size(900.dp, 720.dp)) { if (mounted) VeyraApp(fixture.container) }
                    }
                }
                compose.onNodeWithTag("podcast-show-screen").assertIsDisplayed()
                val episode = CoreShellTestFixture.episode
                fixture.container.onMusicAction(MusicUiAction.PlayQueueEntry(QueueContext.PODCAST, "episode-now"))
                withTimeout(20_000L) {
                    fixture.container.playback.state.first {
                        it.isPlaying && it.currentSelection?.catalogMedia?.id == episode.id
                    }
                }
                val podcastBinding = requireNotNull(fixture.container.playback.state.value.currentSelection?.bindingId)
                val podcastOccurrence = requireNotNull(fixture.container.playback.state.value.currentSelection?.occurrenceId)
                val generation = fixture.engine.loads.single().generation
                fixture.engine.sendPosition(generation, 45_000L, 900_000L)
                withTimeout(20_000L) { fixture.container.playback.state.first { it.positionMs == 45_000L } }
                fixture.container.playback.dispatch(PlaybackCommand.FlushProgress).playbackResultGetOrThrow()
                withTimeout(20_000L) {
                    fixture.container.podcastProgress.first { it?.forEpisode(episode.id)?.positionMs == 45_000L }
                }

                // A normal cross-media browsing state: MUSIC is the active native context while the
                // PODCAST queue still names the checkpointed occurrence.
                fixture.container.onMusicAction(MusicUiAction.PlayQueueEntry(QueueContext.MUSIC, "music-now"))
                withTimeout(20_000L) {
                    fixture.container.playback.state.first {
                        it.isPlaying && it.activeContext == QueueContext.MUSIC
                    }
                }
                val progressView = requireNotNull(fixture.container.podcastProgress.value)
                val row = fixture.container.store.state.value.podcastEpisodeUiState(episode, progressView = progressView)
                assertTrue(row.resumeActionable, "cross-context state must expose exact Resume: " +
                    "current=${progressView.currentEpisodeId}/${progressView.currentRecordingId} " +
                    "occ=${progressView.currentOccurrenceId} bind=${progressView.currentBindingId} " +
                    "rev=${progressView.queueRevision} can=${row.canResume} target=${row.resumeOccurrenceId}/${row.resumeBindingId}")
                val beforeLoads = fixture.engine.loads.size
                val beforeQueries = fixture.gateway.queries.toList()
                fixture.container.onPodcastAction(PodcastAction.OpenEpisode(episode.id))
                withTimeout(20_000L) { fixture.container.navigation.first { it.detail?.kind == DetailKind.EPISODE } }
                compose.onNodeWithTag("podcast-episode-detail").performScrollToNode(hasTestTag("podcast-detail-play"))
                compose.onNodeWithTag("podcast-detail-play").assertIsDisplayed().performClick()
                withTimeout(20_000L) {
                    while (fixture.engine.loads.drop(beforeLoads).none { it.startPositionMs == 45_000L }) delay(20)
                }
                val resumed = fixture.engine.loads.drop(beforeLoads).last()
                assertEquals(45_000L, resumed.startPositionMs)
                val state = fixture.container.playback.state.value
                assertEquals(QueueContext.PODCAST, state.activeContext)
                assertEquals(podcastBinding, state.currentSelection?.bindingId)
                assertEquals(podcastOccurrence, state.currentSelection?.occurrenceId)
                assertEquals(beforeQueries, fixture.gateway.queries.toList(),
                    "Cross-context exact resume must not create a new title query")
            } finally {
                mounted = false
                compose.waitForIdle()
                fixture.close()
            }
        }
    }

    @Test
    fun canonicalMarkPlayedReachesTheExistingShowAndEpisodeSurfaces() = runBlocking {
        withTimeout(120_000L) {
            val support = TypedDownloadPresentationSupport(TypedDownloadPresentationSupport.workDirectory())
            try {
                support.initialize()
                support.store.upsertCatalog(listOf(episode), listOf(show)).getOrThrow()
                support.app.onPodcastAction(PodcastAction.OpenShow(show.id))
                withTimeout(20_000L) { support.app.navigation.first { it.detail?.id == show.id && it.detail?.kind == DetailKind.SHOW } }

                compose.setContent {
                    VeyraTheme(false, LanguagePreference.ENGLISH) {
                        Box(Modifier.size(900.dp, 720.dp)) { VeyraApp(support.app) }
                    }
                }
                compose.onNodeWithTag("podcast-show-screen").assertIsDisplayed()
                compose.onNodeWithTag("podcast-show-screen").performScrollToNode(hasTestTag("podcast-episode-${episode.id}"))
                compose.onNodeWithTag("podcast-episode-${episode.id}").assertIsDisplayed()

                support.app.onPodcastAction(PodcastAction.SetPlayed(episode.id, true))
                withTimeout(20_000L) { support.app.podcastProgress.first { it?.forEpisode(episode.id)?.played == true } }
                compose.waitUntil(10_000L) {
                    compose.onAllNodes(hasText(strings.text(TextKey.Played)) and
                        hasAnyAncestor(hasTestTag("podcast-episode-${episode.id}")), useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }

                support.app.onPodcastAction(PodcastAction.OpenEpisode(episode.id))
                withTimeout(20_000L) { support.app.navigation.first { it.detail?.kind == DetailKind.EPISODE } }
                compose.onNodeWithTag("podcast-episode-detail").assertIsDisplayed()
                compose.onNodeWithText(strings.text(TextKey.MarkUnplayed)).assertIsDisplayed()

                support.app.onPodcastAction(PodcastAction.SetPlayed(episode.id, false))
                withTimeout(20_000L) { support.app.podcastProgress.first { it?.forEpisode(episode.id)?.played == false } }
                compose.waitUntil(10_000L) {
                    compose.onAllNodes(hasText(strings.text(TextKey.MarkPlayed))).fetchSemanticsNodes().isNotEmpty()
                }
            } finally {
                support.close()
            }
        }
    }

    @Test
    fun authoredControllerCheckpointProvidesCanonicalResumePositionToShowAndDetail() = runBlocking {
        withTimeout(120_000L) {
            val initial = CoreShellTestFixture.snapshot(navigation = AppNavigation(destination = AppDestination.PODCASTS,
                details = listOf(AppDetail(DetailKind.SHOW, CoreShellTestFixture.show.id))))
            val fixture = CoreShellTestFixture.create(initial)
            var mounted by mutableStateOf(true)
            try {
                compose.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                        Box(Modifier.size(900.dp, 720.dp)) { if (mounted) VeyraApp(fixture.container) }
                    }
                }
                compose.onNodeWithTag("podcast-show-screen").assertIsDisplayed()
                val episode = CoreShellTestFixture.episode
                compose.onNodeWithTag("podcast-show-screen")
                    .performScrollToNode(hasTestTag("podcast-episode-${episode.id}"))
                fixture.container.onMusicAction(MusicUiAction.PlayQueueEntry(QueueContext.PODCAST, "episode-now"))
                withTimeout(20_000L) {
                    fixture.container.playback.state.first {
                        it.isPlaying && it.currentSelection?.catalogMedia?.id == episode.id
                    }
                }
                val generation = fixture.engine.loads.single().generation
                fixture.engine.sendPosition(generation, 45_000L, 900_000L)
                withTimeout(20_000L) {
                    fixture.container.playback.state.first { it.positionMs == 45_000L }
                }
                fixture.container.playback.dispatch(PlaybackCommand.FlushProgress).playbackResultGetOrThrow()
                val progress = withTimeout(20_000L) {
                    fixture.container.podcastProgress.first {
                        it?.forEpisode(episode.id)?.positionMs == 45_000L
                    }!!
                }
                assertEquals(45_000L, progress.forEpisode(episode.id)?.positionMs)
                assertEquals(episode.id, progress.currentEpisodeId)
                val row = fixture.store.acquisitionView().snapshot.podcastEpisodeUiState(episode, progressView = progress)
                assertEquals(PodcastProgressRecording.MATCHED, row.progressRecording)
                assertTrue(row.canResume)

                fixture.container.onPodcastAction(PodcastAction.OpenEpisode(episode.id))
                withTimeout(20_000L) { fixture.container.navigation.first { it.detail?.kind == DetailKind.EPISODE } }
                compose.onNodeWithTag("podcast-episode-detail").assertIsDisplayed()
                compose.onNodeWithTag("podcast-episode-detail")
                    .performScrollToNode(hasTestTag("podcast-progress-current"))
                compose.onNodeWithText(strings.format(TextKey.ResumeAt, strings.mediaTime(45_000L)))
                    .assertIsDisplayed()
                val binding = requireNotNull(fixture.container.playback.state.value.currentSelection?.bindingId)
                val savedCheckpoint = fixture.container.store.acquisition.playbackView().checkpoints.values
                    .single { it.bindingId == binding }
                assertEquals(45_000L, savedCheckpoint.positionMs)
                val beforeResume = fixture.engine.loads.size
                val queriesBeforeResume = fixture.gateway.queries.toList()
                compose.onNodeWithTag("podcast-episode-detail").performScrollToNode(hasTestTag("podcast-detail-play"))
                compose.onNodeWithTag("podcast-detail-play").performClick()
                withTimeout(20_000L) { while (fixture.engine.loads.size <= beforeResume) delay(20) }
                assertEquals(45_000L, fixture.engine.loads.last().startPositionMs)
                assertEquals(binding, fixture.container.playback.state.value.currentSelection?.bindingId)
                assertEquals(queriesBeforeResume, fixture.gateway.queries.toList(),
                    "Exact resume must not create a fresh title query")
                Unit
            } finally {
                mounted = false
                compose.waitForIdle()
                fixture.close()
            }
        }
    }
}
