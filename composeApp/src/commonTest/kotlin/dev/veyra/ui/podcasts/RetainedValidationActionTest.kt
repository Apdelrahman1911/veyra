package dev.veyra.ui.podcasts

import dev.veyra.model.DownloadStatus
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.downloads.DownloadSurfaceEntry
import dev.veyra.ui.downloads.DownloadSurfaceOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure action selection for retained FAILED rows; no source, file, Room or native access. */
class RetainedValidationActionTest {
    private val episode = MediaItem("episode", "Authored episode", MediaKind.EPISODE,
        SourceRef(SourceProvider.YOUTUBE, "AAAAAAAAAAA"), showId = "show", durationMs = 100_000L)

    private fun failed(origin: DownloadSurfaceOrigin = DownloadSurfaceOrigin.TYPED_CATALOGUE,
        bindingId: String? = "binding-r1", hasOwnedBytes: Boolean = true) = DownloadSurfaceEntry(
        id = "job-retained",
        origin = origin,
        mediaId = if (origin == DownloadSurfaceOrigin.TYPED_CATALOGUE) episode.id else null,
        status = DownloadStatus.FAILED,
        bytesDownloaded = 1_024,
        totalBytes = 1_024,
        hasOwnedBytes = hasOwnedBytes,
        bindingId = bindingId,
    )

    private fun state(entry: DownloadSurfaceEntry, offline: Boolean = false) = PodcastEpisodeUiState(
        episode = episode,
        typedDownload = entry,
        surfaceDownloaded = false,
        offlineOnly = offline,
    )

    @Test
    fun retainedTypedCatalogueFailureOffersExplicitValidateWithExactBinding() {
        val action = podcastDownloadAction(state(failed()))
        assertEquals(TextKey.ValidateDownload, action.label)
        assertEquals(PodcastAction.ValidateRetainedDownload("job-retained", "binding-r1"), action.action)
        assertTrue(action.enabled)
        assertFalse(action.requiresRemovalConfirmation)
        assertEquals(TextKey.RemoveDownload, podcastRetainedAssetRemovalAction(state(failed()))?.label)
    }

    @Test
    fun retainedNeutralFailureNeverFabricatesABindingOrValidateAction() {
        val neutral = failed(origin = DownloadSurfaceOrigin.TYPED_SOURCE, bindingId = null)
        val action = podcastDownloadAction(state(neutral))
        assertEquals(TextKey.RetainedValidationUnavailable, action.label)
        assertNull(action.action)
        assertFalse(action.enabled)
        assertEquals(TextKey.RemoveDownload, podcastRetainedAssetRemovalAction(state(neutral))?.label)
    }

    @Test
    fun noAssetFailureKeepsNetworkRetryAndRetainedFailureNeverBecomesResume() {
        val noAsset = failed(hasOwnedBytes = false, bindingId = null)
        val retry = podcastDownloadAction(state(noAsset))
        assertEquals(TextKey.Retry, retry.label)
        assertIs<PodcastAction.ResumeDownload>(retry.action)

        val retained = podcastDownloadAction(state(failed()))
        assertEquals(TextKey.ValidateDownload, retained.label)
        assertIs<PodcastAction.ValidateRetainedDownload>(retained.action)
    }
}
