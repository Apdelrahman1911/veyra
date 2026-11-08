package dev.veyra.ui.podcasts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
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
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
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
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Authored metadata fixtures exercise presentation and action routing only. No source, player,
 * repository or network client is constructed, and no fixture is presented as playable media.
 * Run with the repository's approved display/cache helper, not an unscoped Xvfb invocation.
 */
@OptIn(ExperimentalTestApi::class)
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

    private fun receipt(id: String, video: String) = PlaybackBinding(id, episode.id, episode.title,
        FirstResultKind.VIDEO, video, "Authored recording $id", selectedVideoId = video)

    private val historyReceipt = receipt("history", "abcdefghijk")

    private fun recordedSnapshot(target: PlaybackBinding? = null, offline: Boolean = true): AppSnapshot {
        val record = target?.let { DownloadRecord("download", episode.id, DownloadStatus.COMPLETE,
            executionBindingId = it.id, representationId = "authored-representation",
            asset = OfflineAsset("asset", episode.id, "/authored-fixture/not-a-media-file", 1_024L,
                executionBindingId = it.id, representationId = "authored-representation")) }
        return snapshot(offline = offline, progressMs = 45_000L).copy(
            progress = mapOf(episode.id to MediaProgress(positionMs = 45_000L, durationMs = 180_000L,
                executionBindingId = historyReceipt.id)),
            downloads = record?.let { mapOf(it.id to it) }.orEmpty(),
            playbackBindings = listOfNotNull(historyReceipt, target).associateBy { it.id },
        )
    }

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
        compose.runOnIdle { assertEquals(listOf<PodcastAction>(PodcastAction.OpenEpisode(episode.id)), actions) }
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
        compose.runOnIdle { assertEquals(listOf<PodcastAction>(PodcastAction.OpenShow(show.id)), actions) }
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
        compose.runOnIdle { assertEquals(listOf<PodcastAction>(PodcastAction.OpenPublisher(page)), actions) }
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
        compose.runOnIdle { assertEquals(listOf<PodcastAction>(PodcastAction.DismissAddFeed), actions) }
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
            assertEquals(listOf<PodcastAction>(PodcastAction.RemoveDownload("download")), actions)
            assertEquals(45_000L, snapshot.progress.getValue(episode.id).positionMs)
        }
    }

    @Test
    fun checkingExistingAssetMenuShowsStatusAndConfirmedRemovalWithoutNoOpCancelOrResume() {
        val actions = mutableListOf<PodcastAction>()
        val complete = snapshot(offline = true, ready = true).podcastEpisodeUiState(episode)
        val record = complete.download!!
        // These are coordinator presentation states, not new transfers or proof of decoded audio.
        var shown by mutableStateOf(complete.copy(download = record.copy(status = DownloadStatus.VERIFYING)))
        compose.setContent { TestFrame { PodcastEpisodeRow(shown, actions::add) } }

        listOf(DownloadStatus.VERIFYING, DownloadStatus.NEEDS_FOREGROUND).forEach { status ->
            compose.runOnIdle { shown = complete.copy(download = record.copy(status = status)); actions.clear() }
            compose.onNodeWithTag("podcast-play-episode").assertIsNotEnabled()
            compose.onNodeWithText(english.text(TextKey.Downloaded)).assertDoesNotExist()
            compose.onNodeWithTag("podcast-more-episode").performClick()
            compose.onNodeWithTag("podcast-menu-download").assertIsNotEnabled()
                .assertTextEquals(english.downloadStatus(status))
            compose.onNodeWithText(english.text(TextKey.CancelDownload)).assertDoesNotExist()
            compose.onNodeWithText(english.text(TextKey.ResumeDownload)).assertDoesNotExist()
            compose.onNodeWithTag("podcast-menu-remove-download").assertIsEnabled().performClick()
            compose.runOnIdle { assertTrue(actions.isEmpty()) }
            compose.onNodeWithText(english.text(TextKey.Cancel)).performClick()
            compose.runOnIdle { assertTrue(actions.isEmpty()) }
            compose.onNodeWithTag("podcast-more-episode").performClick()
            compose.onNodeWithTag("podcast-menu-remove-download").performClick()
            compose.onNodeWithText(english.text(TextKey.Confirm)).performClick()
            compose.runOnIdle { assertEquals(listOf<PodcastAction>(PodcastAction.RemoveDownload(record.id)), actions) }
        }
    }

    @Test
    fun arabicExistingAssetCheckDetailStaysUnavailableOfflineAndRemovalRequiresConfirmation() {
        val actions = mutableListOf<PodcastAction>()
        val complete = snapshot(offline = true, ready = true, progressMs = 45_000L)
        val record = complete.downloads.getValue("download")
        var shown by mutableStateOf(complete.copy(downloads = mapOf(record.id to record.copy(status = DownloadStatus.VERIFYING))))
        compose.setContent { TestFrame(language = LanguagePreference.ARABIC, dark = true) {
            EpisodeDetailScreen(EpisodeDetailUiState(episode.id, shown), actions::add)
        } }

        listOf(DownloadStatus.VERIFYING, DownloadStatus.NEEDS_FOREGROUND).forEach { status ->
            compose.runOnIdle {
                shown = complete.copy(downloads = mapOf(record.id to record.copy(status = status)))
                actions.clear()
            }
            scrollDetailTo("podcast-detail-play")
            compose.onNodeWithTag("podcast-detail-play").assertIsNotEnabled()
            scrollDetailTo("podcast-detail-download")
            compose.onNodeWithTag("podcast-detail-download").assertIsNotEnabled()
                .assertTextEquals(arabic.downloadStatus(status))
            compose.onNodeWithText(arabic.text(TextKey.CancelDownload)).assertDoesNotExist()
            compose.onNodeWithText(arabic.text(TextKey.ResumeDownload)).assertDoesNotExist()
            scrollDetailTo("podcast-detail-remove-download")
            compose.onNodeWithTag("podcast-detail-remove-download").assertIsEnabled().performClick()
            compose.runOnIdle { assertTrue(actions.isEmpty()) }
            compose.onNodeWithText(arabic.text(TextKey.Cancel)).performClick()
            compose.runOnIdle { assertTrue(actions.isEmpty()) }
            compose.onNodeWithTag("podcast-detail-remove-download").performClick()
            compose.onNodeWithText(arabic.text(TextKey.Confirm)).performClick()
            compose.runOnIdle {
                assertEquals(listOf<PodcastAction>(PodcastAction.RemoveDownload(record.id)), actions)
                assertEquals(45_000L, shown.progress.getValue(episode.id).positionMs)
                assertFalse(shown.podcastEpisodeUiState(episode).downloaded)
            }
        }
    }

    @Test
    fun failedOwnedAssetMenuKeepsRetrySeparateFromConfirmedOfflineRemoval() {
        val actions = mutableListOf<PodcastAction>()
        val complete = snapshot(offline = true, ready = true).podcastEpisodeUiState(episode)
        val record = complete.download!!.copy(status = DownloadStatus.FAILED,
            failure = AppFailure(FailureCode.CORRUPT_MEDIA))
        var shown by mutableStateOf(complete.copy(download = record))
        compose.setContent { TestFrame { PodcastEpisodeRow(shown, actions::add) } }

        listOf(true, false).forEach { offline ->
            compose.runOnIdle { shown = complete.copy(download = record, offlineOnly = offline); actions.clear() }
            if (offline) compose.onNodeWithTag("podcast-play-episode").assertIsNotEnabled()
            compose.onNodeWithText(english.text(TextKey.Downloaded)).assertDoesNotExist()
            compose.onNodeWithTag("podcast-more-episode").performClick()
            val retry = compose.onNodeWithTag("podcast-menu-download").assertTextEquals(english.text(TextKey.Retry))
            if (offline) retry.assertIsNotEnabled() else retry.assertIsEnabled()
            compose.onNodeWithText(english.text(TextKey.CancelDownload)).assertDoesNotExist()
            compose.onNodeWithText(english.text(TextKey.ResumeDownload)).assertDoesNotExist()
            compose.onNodeWithTag("podcast-menu-remove-download").assertIsEnabled().performClick()
            compose.runOnIdle { assertTrue(actions.isEmpty()) }
            compose.onNodeWithTag("podcast-action-dismiss").performClick()
            compose.runOnIdle { assertTrue(actions.isEmpty()) }
            compose.onNodeWithTag("podcast-more-episode").performClick()
            compose.onNodeWithTag("podcast-menu-remove-download").performClick()
            compose.onNodeWithTag("podcast-action-confirm").performClick()
            compose.runOnIdle { assertEquals(listOf<PodcastAction>(PodcastAction.RemoveDownload(record.id)), actions) }
            if (!offline) {
                compose.runOnIdle { actions.clear() }
                compose.onNodeWithTag("podcast-more-episode").performClick()
                compose.onNodeWithTag("podcast-menu-download").performClick()
                compose.runOnIdle { assertEquals(listOf<PodcastAction>(PodcastAction.ResumeDownload(record.id)), actions) }
                compose.onNodeWithTag("podcast-action-confirmation").assertDoesNotExist()
            }
        }
    }

    @Test
    fun arabicFailedOwnedAssetDetailOffersLocalRemovalWithoutTurningItIntoRetry() {
        val actions = mutableListOf<PodcastAction>()
        val complete = snapshot(offline = true, ready = true, progressMs = 45_000L)
        val record = complete.downloads.getValue("download").copy(status = DownloadStatus.FAILED,
            failure = AppFailure(FailureCode.CORRUPT_MEDIA))
        var shown by mutableStateOf(complete.copy(downloads = mapOf(record.id to record)))
        compose.setContent { TestFrame(language = LanguagePreference.ARABIC, dark = true) {
            EpisodeDetailScreen(EpisodeDetailUiState(episode.id, shown), actions::add)
        } }

        listOf(true, false).forEach { offline ->
            compose.runOnIdle { shown = shown.copy(settings = AppSettings(offlineOnly = offline)); actions.clear() }
            scrollDetailTo("podcast-detail-play")
            if (offline) compose.onNodeWithTag("podcast-detail-play").assertIsNotEnabled()
            scrollDetailTo("podcast-detail-download")
            val retry = compose.onNodeWithTag("podcast-detail-download").assertTextEquals(arabic.text(TextKey.Retry))
            if (offline) retry.assertIsNotEnabled() else retry.assertIsEnabled()
            compose.onNodeWithText(arabic.text(TextKey.CancelDownload)).assertDoesNotExist()
            scrollDetailTo("podcast-detail-remove-download")
            compose.onNodeWithTag("podcast-detail-remove-download").assertIsEnabled().performClick()
            compose.runOnIdle { assertTrue(actions.isEmpty()) }
            compose.onNodeWithTag("podcast-action-dismiss").performClick()
            compose.runOnIdle { assertTrue(actions.isEmpty()) }
            compose.onNodeWithTag("podcast-detail-remove-download").performClick()
            compose.onNodeWithTag("podcast-action-confirm").performClick()
            compose.runOnIdle {
                assertEquals(listOf<PodcastAction>(PodcastAction.RemoveDownload(record.id)), actions)
                assertEquals(45_000L, shown.progress.getValue(episode.id).positionMs)
                assertFalse(shown.podcastEpisodeUiState(episode).downloaded)
            }
            if (!offline) {
                compose.runOnIdle { actions.clear() }
                scrollDetailTo("podcast-detail-download")
                compose.onNodeWithTag("podcast-detail-download").performClick()
                compose.runOnIdle { assertEquals(listOf<PodcastAction>(PodcastAction.ResumeDownload(record.id)), actions) }
                compose.onNodeWithTag("podcast-action-confirmation").assertDoesNotExist()
            }
        }
    }

    @Test
    fun changedDownloadedRecordingRowAndDetailUsePlayAndDiscloseHistoricalPositionInBothLanguages() {
        val actions = mutableListOf<PodcastAction>()
        val target = receipt("download-B", "lmnopqrstuv")
        val original = recordedSnapshot(target)
        var detail by mutableStateOf(false)
        var language by mutableStateOf(LanguagePreference.ENGLISH)
        compose.setContent { TestFrame(language = language) {
            val dispatch: (PodcastAction) -> Unit = { action ->
                actions += action
                if (action is PodcastAction.OpenEpisode) detail = true
            }
            if (detail) EpisodeDetailScreen(EpisodeDetailUiState(episode.id, original), dispatch)
            else PodcastEpisodeRow(original.podcastEpisodeUiState(episode), dispatch)
        } }

        listOf(LanguagePreference.ENGLISH, LanguagePreference.ARABIC).forEach { locale ->
            val strings = AppStrings(locale)
            compose.runOnIdle { language = locale; detail = false; actions.clear() }
            compose.onNodeWithTag("podcast-play-episode").assertIsEnabled().assertTextEquals(strings.text(TextKey.Play))
            compose.onNodeWithTag("podcast-listening-progress-episode").assertDoesNotExist()
            compose.onNodeWithText(strings.format(TextKey.ResumeAt, strings.mediaTime(45_000L))).assertDoesNotExist()
            compose.onNodeWithTag("podcast-episode-episode").performClick()
            compose.runOnIdle {
                assertEquals(listOf<PodcastAction>(PodcastAction.OpenEpisode(episode.id)), actions)
                actions.clear()
            }
            scrollDetailTo("podcast-progress-recording-notice-episode")
            compose.onNodeWithTag("podcast-progress-recording-notice-episode")
                .assertTextEquals(strings.format(TextKey.PodcastProgressRecordingChanged, strings.mediaTime(45_000L)))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription,
                    listOf(strings.format(TextKey.PodcastProgressRecordingChanged, strings.spokenDuration(45_000L)))))
            compose.onNodeWithTag("podcast-progress-current").assertDoesNotExist()
            compose.onNodeWithTag("podcast-progress-remaining").assertDoesNotExist()
            compose.onNodeWithTag("podcast-listening-progress-episode").assertDoesNotExist()
            scrollDetailTo("podcast-detail-play")
            compose.onNodeWithTag("podcast-detail-play").assertIsEnabled().assertTextEquals(strings.text(TextKey.Play))
            compose.runOnIdle {
                assertTrue(actions.isEmpty())
                assertEquals(45_000L, original.progress.getValue(episode.id).positionMs)
                assertEquals(180_000L, original.progress.getValue(episode.id).durationMs)
                assertEquals(target, original.podcastEpisodeUiState(episode).nextPlayBinding)
            }
            compose.onNodeWithTag("podcast-detail-play").performClick()
            compose.runOnIdle { assertEquals(listOf<PodcastAction>(PodcastAction.PlayEpisode(episode.id)), actions) }
        }
    }

    @Test
    fun sameVideoNewLocalReceiptResumesItsMeasuredPositionDespiteDifferentCurrentDisclosure() {
        val actions = mutableListOf<PodcastAction>()
        val target = receipt("download-A", "abcdefghijk")
        val current = receipt("currently-streaming-B", "lmnopqrstuv")
        val correctedEpisode = episode.copy(title = "Corrected authored publisher title", durationMs = 30_000L)
        val original = recordedSnapshot(target).copy(media = mapOf(episode.id to correctedEpisode))
        compose.setContent { TestFrame {
            EpisodeDetailScreen(EpisodeDetailUiState(episode.id, original, currentBinding = current), actions::add)
        } }

        compose.onNodeWithTag("podcast-episode-detail").performScrollToNode(hasText(current.firstResultTitle))
        compose.onNodeWithText(current.firstResultTitle).assertIsDisplayed()
        scrollDetailTo("podcast-progress-resume")
        compose.onNodeWithTag("podcast-progress-resume")
            .assertTextEquals(english.format(TextKey.ResumeAt, english.mediaTime(45_000L)))
        scrollDetailTo("podcast-progress-remaining")
        compose.onNodeWithTag("podcast-progress-remaining")
            .assertTextEquals(english.format(TextKey.MediaRemaining, english.spokenDuration(135_000L)))
        compose.onNodeWithTag("podcast-progress-recording-notice-episode").assertDoesNotExist()
        scrollDetailTo("podcast-detail-play")
        compose.onNodeWithTag("podcast-detail-play").assertIsEnabled()
            .assertTextEquals(english.format(TextKey.ResumeAt, english.mediaTime(45_000L))).performClick()
        compose.runOnIdle {
            assertEquals(listOf<PodcastAction>(PodcastAction.ResumeEpisode(episode.id, target.id)), actions)
            assertEquals(target, original.podcastEpisodeUiState(correctedEpisode, current).nextPlayBinding)
            assertEquals(45_000L, original.progress.getValue(episode.id).positionMs)
        }
    }

    @Test
    fun onlineUnresolvedRecordingShowsConditionalHistoricalPositionNotGuaranteedResume() {
        val actions = mutableListOf<PodcastAction>()
        val original = recordedSnapshot(offline = false)
        compose.setContent { TestFrame(language = LanguagePreference.ARABIC) {
            EpisodeDetailScreen(EpisodeDetailUiState(episode.id, original, currentBinding = historyReceipt), actions::add)
        } }

        scrollDetailTo("podcast-progress-recording-notice-episode")
        compose.onNodeWithTag("podcast-progress-recording-notice-episode")
            .assertTextEquals(arabic.format(TextKey.PodcastProgressRecordingUnverified, arabic.mediaTime(45_000L)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription,
                listOf(arabic.format(TextKey.PodcastProgressRecordingUnverified, arabic.spokenDuration(45_000L)))))
        compose.onNodeWithTag("podcast-progress-current").assertDoesNotExist()
        compose.onNodeWithTag("podcast-progress-remaining").assertDoesNotExist()
        scrollDetailTo("podcast-detail-play")
        compose.onNodeWithTag("podcast-detail-play").assertIsEnabled().assertTextEquals(arabic.text(TextKey.Play))
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithTag("podcast-detail-play").performClick()
        compose.runOnIdle {
            assertEquals(listOf<PodcastAction>(PodcastAction.PlayEpisode(episode.id)), actions)
            assertEquals(historyReceipt.id, original.progress.getValue(episode.id).executionBindingId)
            assertEquals(45_000L, original.progress.getValue(episode.id).positionMs)
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
        compose.runOnIdle { assertEquals(listOf<PodcastAction>(PodcastAction.StartOver(episode.id)), actions) }
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
        val row = compose.onNodeWithTag("podcast-episode-episode").getUnclippedBoundsInRoot()
        val frame = compose.onNodeWithTag("podcast-test-frame").getUnclippedBoundsInRoot()
        val playNode = compose.onNodeWithTag("podcast-play-episode")
        val moreNode = compose.onNodeWithTag("podcast-more-episode")
        val play = playNode.getUnclippedBoundsInRoot()
        val more = moreNode.getUnclippedBoundsInRoot()
        val playClipped = playNode.fetchSemanticsNode().boundsInRoot
        val moreClipped = moreNode.fetchSemanticsNode().boundsInRoot
        val label = compose.onNode(hasText(arabic.text(TextKey.Play)) and
            hasAnyAncestor(hasTestTag("podcast-play-episode")), useUnmergedTree = true)
        val layouts = label.podcastTextLayouts()
        podcastFixtureFile("fixture-podcast-episode-row-ar-dark-320-font200-geometry.txt").writeText(
            "Authored inner-row fixture; default test scene, NOT a native 320px-wide window.\n" +
                "frame=$frame; row=$row; play=$play clipped=$playClipped; more=$more clipped=$moreClipped\n" +
                layouts.joinToString("\n") { it.podcastTextDiagnostics() } + "\n")
        writePodcastFixtureImage(compose.onNodeWithTag("podcast-episode-episode").captureToImage(),
            "fixture-podcast-episode-row-ar-dark-320-font200.png")
        assertTrue(row.left >= frame.left && row.right <= frame.right && row.top >= frame.top && row.bottom <= frame.bottom)
        assertTrue(play.left >= row.left && play.right <= row.right)
        assertTrue(more.left >= row.left && more.right <= row.right)
        assertTrue(play.top >= row.top && play.bottom <= row.bottom && more.top >= row.top && more.bottom <= row.bottom)
        assertTrue(play.bottom - play.top >= 48.dp && more.bottom - more.top >= 48.dp && more.right - more.left >= 48.dp)
        assertTrue(playClipped.width + 1f >= (play.right - play.left).value &&
            playClipped.height + 1f >= (play.bottom - play.top).value, "The play control must not merely have clipped in-bounds coordinates")
        assertTrue(moreClipped.width + 1f >= (more.right - more.left).value &&
            moreClipped.height + 1f >= (more.bottom - more.top).value, "The menu target must remain fully visible")
        assertTrue(layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow }, "The actual localized action label must not truncate")
        assertTrue(more.right <= play.left, "RTL actions mirror their row, not their play icon or timecode")
        compose.onNodeWithTag("podcast-play-episode").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(LayoutDirection.Rtl, direction)
            assertEquals(listOf<PodcastAction>(PodcastAction.PlayEpisode(episode.id)), actions)
        }
    }
}

/** Separate public-API scenes give captures their claimed viewport, not just a narrow inner Box. */
@OptIn(ExperimentalTestApi::class)
class PodcastViewportCaptureTest {
    @Test
    fun authoredPhoneEpisodeDetailsFitArabic200PercentAndEnglish100PercentViewports() {
        captureDetail(width = 320, height = 760, language = LanguagePreference.ARABIC, fontScale = 2f, dark = true)
        captureDetail(width = 320, height = 760, language = LanguagePreference.ENGLISH, fontScale = 1f, dark = false)
    }

    @Test
    fun authoredDesktopEpisodeDetailsFitArabic200PercentAndEnglish100PercentViewports() {
        captureDetail(width = 960, height = 720, language = LanguagePreference.ARABIC, fontScale = 2f, dark = true)
        captureDetail(width = 960, height = 720, language = LanguagePreference.ENGLISH, fontScale = 1f, dark = false)
    }

    private fun captureDetail(width: Int, height: Int, language: LanguagePreference, fontScale: Float, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height, testTimeout = 60.seconds) {
            val episode = MediaItem("capture-episode", "حلقة تجريبية طويلة عن الاستماع والتقنية — Authored episode fixture",
                MediaKind.EPISODE, SourceRef(SourceProvider.RSS, "capture-episode",
                    feedUrl = "https://example.org/authored-podcast.xml", episodeGuid = "capture-episode"),
                showId = "capture-show", creator = "ناشر تجريبي — Fixture publisher", durationMs = 180_000L,
                description = List(3) {
                    "ملاحظات مكتوبة للاختبار فقط؛ لا تسجيل صوتي ولا اتصال بالشبكة. Authored notes, not live media."
                }.joinToString(" "))
            val show = PodcastShow("capture-show", "بودكاست تجريبي — Authored podcast",
                "https://example.org/authored-podcast.xml", publisher = episode.creator, episodeIds = listOf(episode.id))
            val record = DownloadRecord("capture-download", episode.id, DownloadStatus.VERIFYING,
                bytesDownloaded = 4_096L, totalBytes = 4_096L,
                asset = OfflineAsset("capture-asset", episode.id, "/authored-fixture/not-a-media-file", 4_096L))
            val snapshot = AppSnapshot(media = mapOf(episode.id to episode), shows = mapOf(show.id to show),
                progress = mapOf(episode.id to MediaProgress(positionMs = 45_000L, durationMs = 180_000L)),
                settings = AppSettings(offlineOnly = true), downloads = mapOf(record.id to record))
            var shown by mutableStateOf(snapshot)
            val actions = mutableListOf<PodcastAction>()
            val strings = AppStrings(language)
            val locale = if (language == LanguagePreference.ARABIC) "ar" else "en"
            val theme = if (dark) "dark" else "light"
            val prefix = "fixture-podcast-episode-detail-$locale-$theme-${width}x${height}-font${(fontScale * 100).toInt()}"
            val geometry = mutableListOf("Authored UI fixture only; no source, native player, file validation or device proof.",
                "Configured ComposeScene=${width}x$height; density=1; fontScale=$fontScale; locale=$locale; theme=$theme")
            fun saveGeometry() = podcastFixtureFile("$prefix-geometry.txt")
                .writeText(geometry.joinToString("\n", postfix = "\n"))
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    VeyraTheme(darkTheme = dark, language = language) {
                        Surface(Modifier.fillMaxSize().testTag("podcast-capture-frame")) {
                            EpisodeDetailScreen(EpisodeDetailUiState(episode.id, shown), actions::add)
                        }
                    }
                }
            }
            waitForIdle()
            val frame = onNodeWithTag("podcast-capture-frame").assertIsDisplayed().getUnclippedBoundsInRoot()
            geometry += "Observed frame=$frame"
            saveGeometry()
            writePodcastFixtureImage(onNodeWithTag("podcast-capture-frame").captureToImage(), "$prefix-top.png")
            assertEquals(width.dp, frame.right - frame.left, "The full scene must have the claimed width")
            assertEquals(height.dp, frame.bottom - frame.top, "The full scene must have the claimed height")

            fun verifyText(text: String, parent: String = "podcast-episode-detail", insideControl: Boolean = false) {
                val node = onNode(hasText(text) and hasAnyAncestor(hasTestTag(parent)), useUnmergedTree = true)
                    .assertIsDisplayed()
                val layouts = node.podcastTextLayouts()
                val bounds = node.getUnclippedBoundsInRoot()
                val clipped = node.fetchSemanticsNode().boundsInRoot
                val parentBounds = onNodeWithTag(parent).getUnclippedBoundsInRoot()
                geometry += "Text in $parent: $text; unclipped=$bounds; clipped=$clipped"
                geometry += layouts.map { it.podcastTextDiagnostics() }
                saveGeometry()
                val noOverflow = layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow }
                // This fixture fixes density at 1. Paragraph/line coordinates must fit the
                // actual text node, not merely an unconstrained reconstructed paragraph.
                // Keep the strict overflow assertion too: this is not an RTL exception.
                val layoutFitsTextNode = layouts.isNotEmpty() && layouts.all { layout ->
                    val width = (bounds.right - bounds.left).value
                    val height = (bounds.bottom - bounds.top).value
                    layout.size.width <= width + 1f && layout.size.height <= height + 1f &&
                        layout.multiParagraph.width <= width + 1f && layout.multiParagraph.height <= height + 1f &&
                        (0 until layout.lineCount).all { line ->
                            !layout.isLineEllipsized(line) &&
                                layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= width + 1f &&
                                layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= height + 1f
                        }
                }
                val horizontalFit = bounds.left >= frame.left && bounds.right <= frame.right &&
                    clipped.width + 1f >= (bounds.right - bounds.left).value
                val fitsControl = !insideControl || bounds.left >= parentBounds.left && bounds.right <= parentBounds.right &&
                    bounds.top >= parentBounds.top && bounds.bottom <= parentBounds.bottom &&
                    clipped.height + 1f >= (bounds.bottom - bounds.top).value
                val captureError = if (!noOverflow || !layoutFitsTextNode || !horizontalFit || !fitsControl) runCatching {
                    val capture = if (parent.startsWith("podcast-action-")) "podcast-action-confirmation" else "podcast-capture-frame"
                    writePodcastFixtureImage(onNodeWithTag(capture).captureToImage(),
                        "$prefix-failing-text-${text.hashCode().toUInt().toString(16)}.png")
                }.exceptionOrNull() else null
                assertTrue(noOverflow, "Actual text must wrap without overflow: $text; $layouts; captureError=$captureError")
                assertTrue(layoutFitsTextNode, "Paragraph and non-ellipsized lines must fit the measured text node: $text; $bounds")
                assertTrue(horizontalFit, "Text must fit horizontally, not just have clipped in-bounds coordinates: $bounds / $frame")
                assertTrue(fitsControl, "$parent label must stay inside its fully visible control: $bounds / $parentBounds")
            }

            onNodeWithTag("podcast-episode-detail").performScrollToNode(hasText(episode.title))
            verifyText(episode.title)
            onNodeWithTag("podcast-episode-detail").performScrollToNode(hasText(episode.description))
            val notes = onNode(hasText(episode.description) and hasAnyAncestor(hasTestTag("podcast-episode-detail")),
                useUnmergedTree = true)
            val expanded = notes.podcastTextLayouts().any { it.hasVisualOverflow }
            if (expanded) {
                onNodeWithTag("podcast-episode-detail").performScrollToNode(hasText(strings.text(TextKey.ShowMore)))
                onNodeWithText(strings.text(TextKey.ShowMore)).performClick()
                onNodeWithTag("podcast-episode-detail").performScrollToNode(hasText(episode.description))
            }
            verifyText(episode.description)
            if (expanded) {
                // A long paragraph need not fit vertically all at once. Its full layout must be
                // intact and the final line must remain reachable above the expanded-note toggle.
                onNodeWithTag("podcast-episode-detail").performScrollToNode(hasText(strings.text(TextKey.ShowLess)))
                onNodeWithText(strings.text(TextKey.ShowLess)).assertIsDisplayed()
                val noteBounds = notes.getUnclippedBoundsInRoot()
                notes.podcastTextLayouts().forEach { layout ->
                    val last = layout.lineCount - 1
                    val top = noteBounds.top.value + layout.getLineTop(last)
                    val bottom = noteBounds.top.value + layout.getLineBottom(last)
                    geometry += "Expanded notes final line: top=$top; bottom=$bottom; fullBounds=$noteBounds"
                    saveGeometry()
                    assertTrue(top >= frame.top.value && bottom <= frame.bottom.value,
                        "The last line of taller scrollable notes must be reachable, not clipped forever")
                }
            }
            val unverifiedNotice = strings.format(TextKey.PodcastProgressRecordingUnverified, strings.mediaTime(45_000L))
            onNodeWithTag("podcast-episode-detail").performScrollToNode(hasText(unverifiedNotice))
            verifyText(unverifiedNotice)
            writePodcastFixtureImage(onNodeWithTag("podcast-capture-frame").captureToImage(), "$prefix-historical-unverified.png")

            fun verifyActionBounds(tag: String) {
                val control = onNodeWithTag(tag).assertIsDisplayed()
                val bounds = control.getUnclippedBoundsInRoot()
                val clipped = control.fetchSemanticsNode().boundsInRoot
                geometry += "$tag: unclipped=$bounds; clipped=$clipped"
                saveGeometry()
                val fits = bounds.left >= frame.left && bounds.right <= frame.right &&
                    bounds.top >= frame.top && bounds.bottom <= frame.bottom
                val fullyVisible = clipped.width + 1f >= (bounds.right - bounds.left).value &&
                    clipped.height + 1f >= (bounds.bottom - bounds.top).value
                val target = bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp
                if (!fits || !fullyVisible || !target) {
                    val capture = if (tag.startsWith("podcast-action-")) "podcast-action-confirmation" else "podcast-capture-frame"
                    writePodcastFixtureImage(onNodeWithTag(capture).captureToImage(), "$prefix-failing-$tag.png")
                }
                assertTrue(fits, "$tag must fit the whole viewport: $bounds / $frame")
                assertTrue(fullyVisible, "$tag must not pass through clipped bounds alone")
                assertTrue(target, "$tag action, not arbitrary text, must retain its 48dp target: $bounds")
            }

            val actionLabels = mapOf("podcast-detail-play" to TextKey.Play, "podcast-detail-download" to TextKey.DownloadVerifying,
                "podcast-detail-remove-download" to TextKey.RemoveDownload, "podcast-detail-save" to TextKey.SaveForLater,
                "podcast-detail-play-next" to TextKey.PlayNext, "podcast-detail-add-to-end" to TextKey.AddToEnd,
                "podcast-detail-mark-played" to TextKey.MarkPlayed, "podcast-detail-start-over" to TextKey.StartOver)
            val capturedControls = setOf("podcast-detail-play", "podcast-detail-download", "podcast-detail-remove-download")
            val disabledControls = setOf("podcast-detail-play", "podcast-detail-download", "podcast-detail-start-over")
            (capturedControls + listOf("podcast-detail-save", "podcast-detail-play-next", "podcast-detail-add-to-end",
                "podcast-detail-mark-played", "podcast-detail-start-over")).forEach { tag ->
                onNodeWithTag("podcast-episode-detail").performScrollToNode(hasTestTag(tag))
                val control = onNodeWithTag(tag).assertIsDisplayed()
                if (tag in disabledControls) control.assertIsNotEnabled() else control.assertIsEnabled()
                if (tag == "podcast-detail-download") control.assertTextEquals(strings.text(TextKey.DownloadVerifying))
                if (tag in capturedControls) {
                    writePodcastFixtureImage(onNodeWithTag("podcast-capture-frame").captureToImage(), "$prefix-$tag.png")
                }
                verifyActionBounds(tag)
                verifyText(strings.text(actionLabels.getValue(tag)), tag, insideControl = true)
            }
            runOnIdle {
                assertTrue(actions.isEmpty(), "Looking at an authored fixture must not dispatch listening/download actions")
                assertFalse(snapshot.podcastEpisodeUiState(episode).downloaded)
                assertFalse(snapshot.podcastEpisodeUiState(episode).canPlay)
            }
            onNodeWithTag("podcast-episode-detail").performScrollToNode(hasTestTag("podcast-detail-remove-download"))
            onNodeWithTag("podcast-detail-remove-download").performClick()
            runOnIdle { assertTrue(actions.isEmpty(), "Opening a confirmation is not removal") }
            writePodcastFixtureImage(onNodeWithTag("podcast-action-confirmation").captureToImage(), "$prefix-removal-dialog.png")
            listOf("podcast-action-confirm", "podcast-action-dismiss").forEach { tag ->
                onNodeWithTag(tag).assertIsEnabled()
                verifyActionBounds(tag)
                verifyText(strings.text(if (tag == "podcast-action-confirm") TextKey.Confirm else TextKey.Cancel),
                    tag, insideControl = true)
            }
            verifyText(strings.text(TextKey.RemoveDownload), "podcast-action-confirmation")
            verifyText(strings.text(TextKey.RemoveDownloadConfirm), "podcast-action-confirmation")
            onNodeWithTag("podcast-action-dismiss").performClick()
            runOnIdle { assertTrue(actions.isEmpty(), "Dismissing removal preserves the download") }

            val historical = PlaybackBinding("capture-history-A", episode.id, episode.title, FirstResultKind.VIDEO,
                "abcdefghijk", "تسجيل تجريبي سابق — Authored previous recording", selectedVideoId = "abcdefghijk")
            val sameRecording = historical.copy(id = "capture-download-A")
            val changedRecording = historical.copy(id = "capture-download-B", firstResultId = "lmnopqrstuv",
                selectedVideoId = "lmnopqrstuv", firstResultTitle = "تسجيل تجريبي مختلف — Authored changed recording")
            fun readyRecord(binding: PlaybackBinding) = record.copy(status = DownloadStatus.COMPLETE,
                executionBindingId = binding.id, representationId = "authored-representation",
                asset = record.asset!!.copy(executionBindingId = binding.id, representationId = "authored-representation"))
            val ready = snapshot.copy(progress = mapOf(episode.id to snapshot.progress.getValue(episode.id)
                .copy(executionBindingId = historical.id)),
                playbackBindings = listOf(historical, sameRecording, changedRecording).associateBy { it.id },
                downloads = mapOf(record.id to readyRecord(sameRecording)))
            runOnIdle { shown = ready }
            onNodeWithTag("podcast-episode-detail").performScrollToNode(hasTestTag("podcast-detail-play"))
            val resume = strings.format(TextKey.ResumeAt, strings.mediaTime(45_000L))
            onNodeWithTag("podcast-detail-play").assertIsEnabled().assertTextEquals(resume)
            writePodcastFixtureImage(onNodeWithTag("podcast-capture-frame").captureToImage(), "$prefix-matched-recording-resume.png")
            verifyActionBounds("podcast-detail-play")
            verifyText(resume, "podcast-detail-play", insideControl = true)
            onNodeWithTag("podcast-episode-detail").performScrollToNode(hasTestTag("podcast-progress-remaining"))
            verifyText(strings.format(TextKey.MediaRemaining, strings.spokenDuration(135_000L)))
            runOnIdle { shown = ready.copy(downloads = mapOf(record.id to readyRecord(changedRecording))) }
            val changedNotice = strings.format(TextKey.PodcastProgressRecordingChanged, strings.mediaTime(45_000L))
            onNodeWithTag("podcast-episode-detail").performScrollToNode(hasText(changedNotice))
            verifyText(changedNotice)
            writePodcastFixtureImage(onNodeWithTag("podcast-capture-frame").captureToImage(), "$prefix-changed-recording-history.png")
            onNodeWithTag("podcast-episode-detail").performScrollToNode(hasTestTag("podcast-detail-play"))
            onNodeWithTag("podcast-detail-play").assertIsEnabled().assertTextEquals(strings.text(TextKey.Play))
            verifyActionBounds("podcast-detail-play")
            verifyText(strings.text(TextKey.Play), "podcast-detail-play", insideControl = true)
            runOnIdle {
                assertTrue(actions.isEmpty(), "Fixture recording changes must never launch sources or dispatch audio actions")
                assertEquals(45_000L, shown.progress.getValue(episode.id).positionMs)
                assertFalse(shown.podcastEpisodeUiState(episode).canResume)
                assertEquals(0L, shown.podcastEpisodeUiState(episode).positionMs)
                assertEquals(null, shown.podcastEpisodeUiState(episode).progressDurationMs)
            }
            saveGeometry()
        }
}

private fun SemanticsNodeInteraction.podcastTextLayouts(): List<TextLayoutResult> {
    val layouts = mutableListOf<TextLayoutResult>()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    return layouts
}

private fun TextLayoutResult.podcastTextDiagnostics(): String = buildString {
    appendLine("Text layout: size=$size; paragraph=${multiParagraph.width}x${multiParagraph.height}; " +
        "constraints=${layoutInput.constraints}; widthOverflow=$didOverflowWidth; heightOverflow=$didOverflowHeight; " +
        "exceededLines=${multiParagraph.didExceedMaxLines}; lines=$lineCount")
    repeat(lineCount) { line ->
        appendLine("Line $line: left=${getLineLeft(line)} right=${getLineRight(line)} top=${getLineTop(line)} " +
            "bottom=${getLineBottom(line)} ellipsis=${isLineEllipsized(line)}")
    }
}

private fun podcastFixtureFile(name: String): File {
    require(name.startsWith("fixture-podcast-") && (name.endsWith(".png") || name.endsWith("-geometry.txt")) &&
        '/' !in name && '\\' !in name)
    val root = System.getenv("VEYRA_PROJECT_ROOT")?.let(::File)?.canonicalFile
        ?: generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "AGENTS.md").isFile }
        ?: error("Podcast fixtures require the Veyra repository context")
    require(File(root, "settings.gradle.kts").isFile && File(root, "AGENTS.md").isFile)
    val directory = File(root, "composeApp/build/test-evidence/podcasts").canonicalFile
    require(directory.toPath().startsWith(root.toPath()) && directory != root) {
        "Refusing an escaping podcast fixture directory"
    }
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create the owned podcast fixture evidence directory" }
    val output = File(directory, name)
    require(!Files.isSymbolicLink(output.toPath()) && output.canonicalFile.parentFile == directory) {
        "Refusing a linked or escaping podcast fixture output"
    }
    return output
}

private fun writePodcastFixtureImage(bitmap: ImageBitmap, name: String) {
    require(name.startsWith("fixture-podcast-") && name.endsWith(".png") && '/' !in name && '\\' !in name)
    val pixels = bitmap.toPixelMap()
    val image = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) image.setRGB(x, y, pixels[x, y].toArgb())
    check(ImageIO.write(image, "png", podcastFixtureFile(name))) { "PNG writer unavailable" }
}
