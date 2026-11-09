package dev.veyra.app

import dev.veyra.data.getOrThrow
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.PodcastShow
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.ui.podcasts.PodcastAction
import dev.veyra.ui.podcasts.PodcastProgressRecording
import dev.veyra.ui.podcasts.PodcastProgressView
import dev.veyra.ui.podcasts.podcastEpisodeUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Actual AppContainer/Room/controller path for canonical podcast progress. */
class PodcastProgressCompatibilityTest {
    private val show = PodcastShow("progress-show", "Authored progress show", "https://example.org/progress.xml",
        episodeIds = listOf("progress-episode"))
    private val episode = MediaItem("progress-episode", "Authored progress episode", MediaKind.EPISODE,
        SourceRef(SourceProvider.RSS, "progress-episode", feedUrl = show.feedUrl), showId = show.id)

    private suspend fun TypedDownloadPresentationSupport.seed() {
        store.upsertCatalog(listOf(episode), listOf(show)).getOrThrow()
    }

    private suspend fun TypedDownloadPresentationSupport.awaitProgress(
        played: Boolean,
    ): PodcastProgressView {
        var view: PodcastProgressView? = null
        withTimeout(20_000L) {
            while (view?.forEpisode(episode.id)?.played != played) {
                view = app.podcastProgress.value
                if (view?.forEpisode(episode.id)?.played != played) delay(10)
            }
        }
        return requireNotNull(view)
    }

    @Test
    fun canonicalMarkPlayedIsVisibleToTheExistingPodcastRowAfterTheDurableWrite() = runBlocking {
        withTimeout(90_000L) {
            val support = TypedDownloadPresentationSupport(TypedDownloadPresentationSupport.workDirectory())
            try {
                support.initialize()
                support.seed()
                support.app.onPodcastAction(PodcastAction.SetPlayed(episode.id, true))
                val hubView = withTimeout(20_000L) {
                    support.app.podcasts.first { it.progressView?.forEpisode(episode.id)?.played == true }
                        .progressView ?: error("main podcast state did not receive canonical progress")
                }
                val playedView = support.awaitProgress(played = true)
                assertEquals(playedView.forEpisode(episode.id)?.played, hubView.forEpisode(episode.id)?.played)
                val playedRow = support.app.library.value.podcastEpisodeUiState(episode, progressView = hubView)
                assertTrue(playedRow.played,
                    "The existing podcast row must consume the canonical played flag, not only snapshot.progress")
                assertEquals(PodcastProgressRecording.UNVERIFIED, playedRow.progressRecording)
                assertFalse(playedRow.canResume, "A played flag without a current native recording is not a resume grant")

                support.app.onPodcastAction(PodcastAction.SetPlayed(episode.id, false))
                val unplayedView = support.awaitProgress(played = false)
                val unplayedRow = support.app.library.value.podcastEpisodeUiState(episode, progressView = unplayedView)
                assertFalse(unplayedRow.played)
                assertFalse(unplayedRow.canResume)
            } finally {
                support.close()
            }
        }
    }

    @Test
    fun canonicalPlayedFlagSurvivesColdReopenAndRemainsVisibleWithoutAFabricatedResume() = runBlocking {
        withTimeout(120_000L) {
            val directory = TypedDownloadPresentationSupport.workDirectory()
            var first: TypedDownloadPresentationSupport? = null
            var reopened: TypedDownloadPresentationSupport? = null
            try {
                first = TypedDownloadPresentationSupport(directory)
                first.initialize()
                first.seed()
                first.app.onPodcastAction(PodcastAction.SetPlayed(episode.id, true))
                first.awaitProgress(played = true)
                first.close()
                first = null

                reopened = TypedDownloadPresentationSupport(directory)
                reopened.initialize()
                val view = withTimeout(30_000L) {
                    reopened.app.podcastProgress.first { it?.forEpisode(episode.id)?.played == true }
                        ?: error("canonical podcast progress was not published after reopen")
                }
                val row = reopened.app.library.value.podcastEpisodeUiState(episode, progressView = view)
                assertTrue(row.played)
                assertEquals(PodcastProgressRecording.UNVERIFIED, row.progressRecording)
                assertFalse(row.canResume)
                assertTrue(reopened.store.acquisition.playbackView().catalogProgress[episode.id]?.played == true)
            } finally {
                try { first?.close() } catch (_: Exception) {}
                try { reopened?.close() } catch (_: Exception) {}
            }
        }
    }
}
