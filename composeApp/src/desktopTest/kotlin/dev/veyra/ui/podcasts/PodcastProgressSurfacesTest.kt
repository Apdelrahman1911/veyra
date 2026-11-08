package dev.veyra.ui.podcasts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.veyra.model.AppSnapshot
import dev.veyra.model.LanguagePreference
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.PodcastShow
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.ui.design.AppStrings
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule

/** Existing podcast screens consume the canonical progress projection in EN/AR/RTL. */
@OptIn(ExperimentalTestApi::class)
class PodcastProgressSurfacesTest {
    @get:Rule val compose = createComposeRule()
    private val english = AppStrings(LanguagePreference.ENGLISH)
    private val arabic = AppStrings(LanguagePreference.ARABIC)
    private val show = PodcastShow("progress-show", "Authored progress show", "https://example.org/feed",
        episodeIds = listOf("progress-episode"))
    private val episode = MediaItem("progress-episode", "Authored progress episode", MediaKind.EPISODE,
        SourceRef(SourceProvider.RSS, "progress-episode", feedUrl = show.feedUrl), showId = show.id, durationMs = 180_000L)
    private val snapshot = AppSnapshot(media = mapOf(episode.id to episode), shows = mapOf(show.id to show))

    private fun progress(played: Boolean = false, recording: String = "aaaaaaaaaaa") = PodcastEpisodeProgress(
        mediaId = episode.id, positionMs = 45_000L, durationMs = 180_000L, played = played, updatedEpochMs = 100,
        bindingId = "binding-a", recordingId = recording, occurrenceId = "occurrence-a",
        source = PodcastProgressSource.CANONICAL, bindingValid = true,
    )

    private fun view(progress: PodcastEpisodeProgress, currentRecording: String? = "aaaaaaaaaaa") =
        PodcastProgressView(mapOf(episode.id to progress), currentEpisodeId = episode.id,
            currentRecordingId = currentRecording, currentOccurrenceId = "occurrence-a",
            currentBindingId = "binding-a", queueRevision = 1L,
            queuedTargets = mapOf(episode.id to listOf(PodcastResumeTarget(
                "occurrence-a", "binding-a", currentRecording ?: "aaaaaaaaaaa", queueRevision = 1L))))

    @Test
    fun matchedProgressShowsResumeAndInProgressInEnglish() {
        val row = snapshot.podcastEpisodeUiState(episode, progressView = view(progress()))
        compose.setContent {
            VeyraTheme(false, LanguagePreference.ENGLISH) {
                Box(Modifier.size(420.dp, 760.dp)) { PodcastEpisodeRow(row, {}) }
            }
        }
        compose.onNodeWithText(english.text(TextKey.InProgress)).assertIsDisplayed()
        compose.onNodeWithText(english.format(TextKey.ResumeAt, english.mediaTime(45_000L))).assertIsDisplayed()
        compose.onNodeWithTag("podcast-listening-progress-${episode.id}").assertIsDisplayed()
        compose.onNodeWithText(english.format(TextKey.PodcastProgressRecordingUnverified, english.mediaTime(45_000L)))
            .assertDoesNotExist()
    }

    @Test
    fun changedRecordingIsExplicitInArabicRtlAndDoesNotOfferResume() {
        val changed = progress(recording = "bbbbbbbbbbb")
        val changedView = view(changed, currentRecording = "aaaaaaaaaaa")
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                VeyraTheme(true, LanguagePreference.ARABIC) {
                    EpisodeDetailScreen(EpisodeDetailUiState(episode.id, snapshot, progressView = changedView), {})
                }
            }
        }
        compose.onNodeWithTag("podcast-episode-detail")
            .performScrollToNode(hasTestTag("podcast-progress-recording-notice-${episode.id}"))
        compose.onNodeWithTag("podcast-progress-recording-notice-${episode.id}")
            .assertTextEquals(arabic.format(TextKey.PodcastProgressRecordingChanged, arabic.mediaTime(45_000L)))
        compose.onNodeWithTag("podcast-progress-current").assertDoesNotExist()
        compose.onNodeWithTag("podcast-episode-detail").performScrollToNode(hasTestTag("podcast-detail-play"))
        compose.onNodeWithTag("podcast-detail-play").assertTextEquals(arabic.text(TextKey.Play))
        assertEquals(PodcastProgressRecording.CHANGED, changedView.recordingState(episode.id))
    }

    @Test
    fun rowResumeActionCarriesTheExactCanonicalOccurrenceAndRevision() {
        val actions = mutableListOf<PodcastAction>()
        val row = snapshot.podcastEpisodeUiState(episode, progressView = view(progress()))
        compose.setContent {
            VeyraTheme(false, LanguagePreference.ENGLISH) {
                Box(Modifier.size(420.dp, 760.dp)) { PodcastEpisodeRow(row, actions::add) }
            }
        }
        compose.onNodeWithTag("podcast-play-${episode.id}").performClick()
        compose.runOnIdle {
            assertEquals(listOf<PodcastAction>(PodcastAction.ResumeEpisode(
                episode.id, "binding-a", "occurrence-a", 1L)), actions)
        }
    }

    @Test
    fun existingHubContinueAndNewReadCanonicalProgress() {
        val view = view(progress())
        compose.setContent {
            VeyraTheme(false, LanguagePreference.ENGLISH) {
                Box(Modifier.size(420.dp, 1200.dp)) {
                    PodcastsScreen(PodcastsUiState(snapshot = snapshot, progressView = view), {})
                }
            }
        }
        compose.onNodeWithTag("podcasts-screen").performScrollToNode(hasTestTag("podcast-listening-progress-${episode.id}"))
        compose.onNodeWithText(english.format(TextKey.ResumeAt, english.mediaTime(45_000L))).assertIsDisplayed()
        compose.onNodeWithTag("podcast-listening-progress-${episode.id}").assertIsDisplayed()
    }

    @Test
    fun existingShowFilterReadsCanonicalProgressInArabic() {
        val view = view(progress())
        val showState = PodcastShowUiState(show.id, snapshot, progressView = view,
            options = PodcastListOptions(filter = PodcastEpisodeFilter.IN_PROGRESS))
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                VeyraTheme(true, LanguagePreference.ARABIC) {
                    Box(Modifier.size(420.dp, 1200.dp)) { PodcastShowScreen(showState, {}) }
                }
            }
        }
        compose.onNodeWithTag("podcast-show-screen")
            .performScrollToNode(hasTestTag("podcast-play-${episode.id}"))
        compose.onNodeWithTag("podcast-play-${episode.id}")
            .assertTextEquals(arabic.format(TextKey.ResumeAt, arabic.mediaTime(45_000L)))
    }

    @Test
    fun playedCanonicalRowAndShowFilterAreHonestInArabic() {
        val playedView = view(progress(played = true))
        val playedRow = snapshot.podcastEpisodeUiState(episode, progressView = playedView)
        val showState = PodcastShowUiState(show.id, snapshot, progressView = playedView,
            options = PodcastListOptions(filter = PodcastEpisodeFilter.PLAYED))
        assertEquals(listOf(episode.id), showState.visibleEpisodes().map { it.id })
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                VeyraTheme(true, LanguagePreference.ARABIC) {
                    Box(Modifier.size(420.dp, 760.dp)) { PodcastEpisodeRow(playedRow, {}) }
                }
            }
        }
        compose.onNodeWithText(arabic.text(TextKey.Played)).assertIsDisplayed()
        compose.onNodeWithTag("podcast-play-${episode.id}").assertTextEquals(arabic.text(TextKey.Replay))
        compose.onNodeWithTag("podcast-listening-progress-${episode.id}").assertDoesNotExist()
    }
}
