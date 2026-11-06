package dev.veyra.ui.podcasts

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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
import dev.veyra.model.MediaProgress
import dev.veyra.model.OfflineAsset
import dev.veyra.model.PlaybackBinding
import dev.veyra.model.PodcastShow
import dev.veyra.model.QueueContext
import dev.veyra.model.QueueEntry
import dev.veyra.model.QueueSnapshot
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.ui.design.AppStrings
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Authored metadata fixtures exercise presentation and action routing only. No source, player,
 * repository or network client is constructed, and no fixture is presented as playable media.
 * Run with the repository's approved display/cache helper, not an unscoped Xvfb invocation.
 */
class PodcastScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val english = AppStrings(LanguagePreference.ENGLISH)
    private val arabic = AppStrings(LanguagePreference.ARABIC)
    private val show = PodcastShow("show", "Authored podcast", "https://example.org/feed.xml",
        publisher = "Fixture publisher", episodeIds = listOf("episode", "new", "started"))
    private val episode = MediaItem("episode", "An authored episode title", MediaKind.EPISODE,
        SourceRef(SourceProvider.RSS, "episode", feedUrl = show.feedUrl, episodeGuid = "episode"),
        showId = show.id, creator = show.publisher, durationMs = 180_000L)

    private fun snapshot(offline: Boolean = false, ready: Boolean = false, progressMs: Long = 0L) = AppSnapshot(
        media = mapOf(episode.id to episode),
        shows = mapOf(show.id to show),
        progress = mapOf(episode.id to MediaProgress(positionMs = progressMs, durationMs = 180_000L)),
        settings = AppSettings(offlineOnly = offline),
        downloads = if (!ready) emptyMap() else mapOf("download" to DownloadRecord("download", episode.id,
            DownloadStatus.COMPLETE, asset = OfflineAsset("asset", episode.id, "/owned/authored.mp3", 100L))),
    )

    @Composable
    private fun TestFrame(language: LanguagePreference = LanguagePreference.ENGLISH, dark: Boolean = false,
        fontScale: Float = 1f, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
            VeyraTheme(darkTheme = dark, language = language) {
                Surface {
                    Box(Modifier.width(360.dp).height(760.dp).testTag("podcast-test-frame")) { content() }
                }
            }
        }
    }

    private fun scrollDetailTo(tag: String) {
        compose.onNodeWithTag("podcast-episode-detail").performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun selectingAnEpisodeOnlyNavigatesAndPlaybackNeedsItsOwnAction() {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { TestFrame { PodcastEpisodeRow(snapshot().podcastEpisodeUiState(episode), actions::add) } }

        compose.onNodeWithTag("podcast-episode-episode").performClick()
        compose.runOnIdle { assertEquals(listOf(PodcastAction.OpenEpisode(episode.id)), actions) }
        compose.onNodeWithTag("podcast-play-episode").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(PodcastAction.OpenEpisode(episode.id), PodcastAction.PlayEpisode(episode.id)), actions)
        }
    }

    @Test
    fun selectingAShowDoesNotFollowOrPlayIt() {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { TestFrame { PodcastShowRow(show, followed = false, offlineOnly = true, onAction = actions::add) } }
        compose.onNodeWithTag("podcast-show-show").performClick()
        compose.runOnIdle { assertEquals(listOf(PodcastAction.OpenShow(show.id)), actions) }
    }

    @Test
    fun showPublisherActionNeverUsesAKnownFeedLocator() {
        val actions = mutableListOf<PodcastAction>()
        var shown by mutableStateOf(show.copy(websiteUrl = "${show.feedUrl}#publisher"))
        compose.setContent { TestFrame {
            PodcastShowScreen(PodcastShowUiState(show.id, AppSnapshot(shows = mapOf(show.id to shown))), actions::add)
        } }
        compose.onNodeWithText(english.text(TextKey.OpenPublisher)).assertDoesNotExist()

        val page = "https://example.org/podcast"
        compose.runOnIdle { shown = shown.copy(websiteUrl = page) }
        compose.onNodeWithTag("podcast-show-screen").performScrollToNode(hasText(english.text(TextKey.OpenPublisher)))
        compose.onNodeWithText(english.text(TextKey.OpenPublisher)).performClick()
        compose.runOnIdle { assertEquals(listOf(PodcastAction.OpenPublisher(page)), actions) }
    }

    @Test
    fun feedFormKeepsOwnerControlledDraftAndDoesNotFollowOnSubmit() {
        val actions = mutableListOf<PodcastAction>()
        var state by mutableStateOf(PodcastsUiState(addFeed = AddPodcastFeedUiState()))
        compose.setContent {
            TestFrame {
                PodcastsScreen(state, onAction = { action ->
                    actions += action
                    if (action is PodcastAction.FeedUrlChanged) state = state.copy(addFeed = state.addFeed!!.copy(url = action.url))
                })
            }
        }
        compose.onNodeWithTag("podcast-submit-feed").assertIsNotEnabled()
        compose.onNodeWithTag("podcast-feed-url").performTextInput(" https://example.org/feed.xml ")
        compose.onNodeWithTag("podcast-submit-feed").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(" https://example.org/feed.xml ", state.addFeed!!.url)
            assertEquals(listOf(PodcastAction.SubmitFeed("https://example.org/feed.xml")), actions.filterIsInstance<PodcastAction.SubmitFeed>())
            assertFalse(actions.any { it is PodcastAction.SetFollowed || it is PodcastAction.PlayEpisode })
        }
    }

    @Test
    fun invalidFeedDisplaysLocalizedCategoryNotBackendDetailAndKeepsInput() {
        val failure = AppFailure(FailureCode.PARSE_FAILURE, "PRIVATE_BACKEND_DETAIL")
        val state = PodcastsUiState(addFeed = AddPodcastFeedUiState("https://example.org/feed.xml", failure = failure))
        compose.setContent { TestFrame(language = LanguagePreference.ARABIC) { PodcastsScreen(state, {}) } }

        compose.onNodeWithTag("podcast-feed-error").assertTextEquals(arabic.errorFor(failure))
        compose.onNodeWithTag("podcast-feed-url")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, arabic.errorFor(failure)))
        compose.onNodeWithText("PRIVATE_BACKEND_DETAIL", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("podcast-feed-url").assertIsEnabled()
        compose.onNodeWithTag("podcast-submit-feed").assertIsEnabled()
    }

    @Test
    fun submittingFeedDisablesDuplicateSubmissionButCancellationIsAvailable() {
        val actions = mutableListOf<PodcastAction>()
        val state = PodcastsUiState(addFeed = AddPodcastFeedUiState("https://example.org/feed.xml", isSubmitting = true))
        compose.setContent { TestFrame { PodcastsScreen(state, actions::add) } }

        compose.onNodeWithTag("podcast-feed-url").assertIsNotEnabled()
        compose.onNodeWithTag("podcast-submit-feed").assertIsNotEnabled()
        compose.onNodeWithText(english.text(TextKey.Cancel)).performClick()
        compose.runOnIdle { assertEquals(listOf(PodcastAction.DismissAddFeed), actions) }
    }

    @Test
    fun offlineEpisodeKeepsSavePlayedAndQueueActionsButDisablesRemoteAudio() {
        val actions = mutableListOf<PodcastAction>()
        val snapshot = snapshot(offline = true, progressMs = 45_000L)
        compose.setContent { TestFrame { EpisodeDetailScreen(EpisodeDetailUiState(episode.id, snapshot), actions::add) } }

        scrollDetailTo("podcast-detail-play")
        compose.onNodeWithTag("podcast-detail-play").assertIsNotEnabled()
        scrollDetailTo("podcast-detail-download")
        compose.onNodeWithTag("podcast-detail-download").assertIsNotEnabled()
        scrollDetailTo("podcast-detail-save")
        compose.onNodeWithTag("podcast-detail-save").assertIsEnabled().performClick()
        scrollDetailTo("podcast-detail-mark-played")
        compose.onNodeWithTag("podcast-detail-mark-played").assertIsEnabled().performClick()
        compose.onNodeWithTag("podcast-episode-detail").performScrollToNode(hasText(english.text(TextKey.AddToEnd)))
        compose.onNodeWithText(english.text(TextKey.AddToEnd)).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(PodcastAction.SetSaved(episode.id, true), PodcastAction.SetPlayed(episode.id, true),
                PodcastAction.AddToEnd(episode.id)), actions)
            assertEquals(45_000L, snapshot.progress.getValue(episode.id).positionMs)
        }
    }

    @Test
    fun readyOfflineAudioCanPlayAndRemovalWaitsForExplicitConfirmation() {
        val actions = mutableListOf<PodcastAction>()
        val snapshot = snapshot(offline = true, ready = true, progressMs = 45_000L)
        compose.setContent { TestFrame { EpisodeDetailScreen(EpisodeDetailUiState(episode.id, snapshot), actions::add) } }

        scrollDetailTo("podcast-detail-play")
        compose.onNodeWithTag("podcast-detail-play").assertIsEnabled()
        scrollDetailTo("podcast-detail-download")
        compose.onNodeWithTag("podcast-detail-download").performClick()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithText(english.text(TextKey.Cancel)).performClick()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithTag("podcast-detail-download").performClick()
        compose.onNodeWithText(english.text(TextKey.Confirm)).performClick()
        compose.runOnIdle {
            assertEquals(listOf(PodcastAction.RemoveDownload("download")), actions)
            assertEquals(45_000L, snapshot.progress.getValue(episode.id).positionMs)
        }
    }

    @Test
    fun startOverDoesNotResetProgressUntilConfirmed() {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { TestFrame {
            EpisodeDetailScreen(EpisodeDetailUiState(episode.id, snapshot(progressMs = 45_000L)), actions::add)
        } }

        scrollDetailTo("podcast-detail-start-over")
        compose.onNodeWithTag("podcast-detail-start-over").performClick()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithText(english.text(TextKey.Cancel)).performClick()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithTag("podcast-detail-start-over").performClick()
        compose.onNodeWithText(english.text(TextKey.Confirm)).performClick()
        compose.runOnIdle { assertEquals(listOf(PodcastAction.StartOver(episode.id)), actions) }
    }

    @Test
    fun showFiltersAndSortAreLocalPreferencesNotQueueOrPlaybackCommands() {
        val started = episode.copy(id = "started", title = "Started episode", publishedEpochMs = 20L)
        val newEpisode = episode.copy(id = "new", title = "New episode", publishedEpochMs = 30L)
        val queue = QueueSnapshot(listOf(QueueEntry("queued", newEpisode.id)), currentEntryId = "queued")
        val snapshot = snapshot().copy(media = mapOf(started.id to started, newEpisode.id to newEpisode),
            progress = mapOf(started.id to MediaProgress(positionMs = 10_000L)),
            queues = mapOf(QueueContext.PODCAST to queue))
        val actions = mutableListOf<PodcastAction>()
        var state by mutableStateOf(PodcastShowUiState(show.id, snapshot))
        compose.setContent { TestFrame {
            PodcastShowScreen(state, onAction = { action ->
                actions += action
                if (action is PodcastAction.SetShowListOptions) state = state.copy(options = action.options)
            })
        } }

        compose.onNodeWithTag("podcast-show-screen").performScrollToNode(hasTestTag("podcast-filter-IN_PROGRESS"))
        compose.onNodeWithTag("podcast-filter-IN_PROGRESS").performClick().assertIsSelected()
        compose.onNodeWithTag("podcast-show-screen").performScrollToNode(hasTestTag("podcast-episode-started"))
        compose.onNodeWithTag("podcast-episode-started").assertIsDisplayed()
        compose.onNodeWithTag("podcast-episode-new").assertDoesNotExist()
        compose.onNodeWithTag("podcast-show-screen").performScrollToNode(hasTestTag("podcast-sort"))
        compose.onNodeWithTag("podcast-sort").performClick()
        compose.onNodeWithText(english.text(TextKey.OldestFirst)).performClick()
        compose.runOnIdle {
            assertEquals(PodcastEpisodeFilter.IN_PROGRESS, state.options.filter)
            assertEquals(PodcastEpisodeSort.OLDEST_FIRST, state.options.sort)
            assertEquals(listOf(started.id), state.visibleEpisodes().map { it.id })
            assertEquals(queue, state.snapshot.queue(QueueContext.PODCAST))
            assertTrue(actions.all { it is PodcastAction.SetShowListOptions })
        }
    }

    @Test
    fun refreshingAndFailedRefreshDoNotPretendTheUserIsCaughtUp() {
        var state by mutableStateOf(PodcastsUiState(isRefreshing = true))
        compose.setContent { TestFrame { PodcastsScreen(state, {}) } }
        compose.onNodeWithTag("podcast-loading").assertIsDisplayed()
        compose.onNodeWithTag("podcasts-empty").assertDoesNotExist()

        compose.runOnIdle {
            state = state.copy(snapshot = AppSnapshot(shows = mapOf(show.id to show), followedShowIds = setOf(show.id)))
        }
        compose.onNodeWithTag("podcasts-screen").performScrollToNode(hasText(english.text(TextKey.NewEpisodes)))
        compose.onNodeWithText(english.text(TextKey.NoNewEpisodes)).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(isRefreshing = false, failure = AppFailure(FailureCode.NETWORK_UNAVAILABLE)) }
        compose.onNodeWithTag("podcasts-screen").performScrollToNode(hasText(english.text(TextKey.NewEpisodes)))
        compose.onNodeWithText(english.text(TextKey.NoNewEpisodes)).assertDoesNotExist()
        compose.onNodeWithTag("podcasts-empty").assertDoesNotExist()
    }

    @Test
    fun detailDisclosesExactCurrentResultWithoutReplacingOriginalEpisodeIdentity() {
        val actions = mutableListOf<PodcastAction>()
        val current = PlaybackBinding("current", episode.id, episode.title, FirstResultKind.VIDEO,
            "video-current", "A different currently selected title", selectedVideoId = "video-current", resolvedEpochMs = 1L)
        val newest = current.copy(id = "newest", firstResultTitle = "A newer but inactive result", resolvedEpochMs = 2L)
        val state = EpisodeDetailUiState(episode.id, snapshot().copy(
            playbackBindings = mapOf(current.id to current, newest.id to newest)), currentBinding = current)
        compose.setContent { TestFrame { EpisodeDetailScreen(state, actions::add) } }

        compose.onNodeWithTag("podcast-episode-detail")
            .performScrollToNode(hasText(english.text(TextKey.CurrentAudioResolution)))
        compose.onNodeWithText(current.firstResultTitle).assertIsDisplayed()
        compose.onNodeWithText(newest.firstResultTitle).assertDoesNotExist()
        compose.onNodeWithText(english.text(TextKey.SourceMismatchDetected)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(episode, state.episode)
            assertTrue(actions.isEmpty())
        }
    }

    @Test
    fun arabicTwoHundredPercentRowKeepsFullWidthControlsInsideNarrowBounds() {
        val actions = mutableListOf<PodcastAction>()
        val longTitle = episode.copy(title = "حلقة عربية طويلة عن الاستماع والتقنية مع عنوان English مختلط")
        var direction: LayoutDirection? = null
        compose.setContent {
            TestFrame(language = LanguagePreference.ARABIC, dark = true, fontScale = 2f) {
                val currentDirection = LocalLayoutDirection.current
                SideEffect { direction = currentDirection }
                Box(Modifier.width(320.dp)) {
                    PodcastEpisodeRow(PodcastEpisodeUiState(longTitle, show,
                        progress = MediaProgress(positionMs = 45_000L, durationMs = 180_000L)), actions::add)
                }
            }
        }
        val row = compose.onNodeWithTag("podcast-episode-episode").fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithTag("podcast-play-episode").fetchSemanticsNode().boundsInRoot
        val more = compose.onNodeWithTag("podcast-more-episode").fetchSemanticsNode().boundsInRoot
        assertTrue(play.left >= row.left && play.right <= row.right)
        assertTrue(more.left >= row.left && more.right <= row.right)
        assertTrue(play.height >= 48f && more.height >= 48f && more.width >= 48f)
        assertTrue(more.right <= play.left, "RTL actions mirror their row, not their play icon or timecode")
        compose.onNodeWithTag("podcast-play-episode").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(LayoutDirection.Rtl, direction)
            assertEquals(listOf(PodcastAction.PlayEpisode(episode.id)), actions)
        }
    }
}
