package dev.veyra.ui.podcasts

import dev.veyra.model.DownloadStatus
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.downloads.DownloadSurfaceEntry
import dev.veyra.ui.downloads.DownloadSurfaceOrigin
import dev.veyra.ui.downloads.RetainedValidationTarget
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
        bindingId: String? = "binding-r1", hasOwnedBytes: Boolean = true,
        target: RetainedValidationTarget? = if (origin == DownloadSurfaceOrigin.TYPED_CATALOGUE && bindingId != null)
            RetainedValidationTarget("job-retained", bindingId) else null) = DownloadSurfaceEntry(
        id = "job-retained",
        origin = origin,
        mediaId = if (origin == DownloadSurfaceOrigin.TYPED_CATALOGUE) episode.id else null,
        status = DownloadStatus.FAILED,
        bytesDownloaded = 1_024,
        totalBytes = 1_024,
        hasOwnedBytes = hasOwnedBytes,
        bindingId = bindingId,
        retainedValidation = target,
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
    fun retainedBatchMemberFailureCarriesExactMemberRevisionNotABindingOnlyGuess() {
        val target = RetainedValidationTarget("job-retained", "binding-r1", "batch-9", 3, 21)
        val action = podcastDownloadAction(state(failed(target = target)))
        assertEquals(TextKey.ValidateDownload, action.label)
        assertEquals(PodcastAction.ValidateRetainedDownload("job-retained", "binding-r1", "batch-9", 3, 21),
            action.action)
    }

    @Test
    fun retainedNeutralFailureOffersExactLocalValidateWithoutCatalogueAuthority() {
        val neutral = failed(origin = DownloadSurfaceOrigin.TYPED_SOURCE, bindingId = null,
            target = RetainedValidationTarget("job-retained", "neutral-binding"))
        val action = podcastDownloadAction(state(neutral))
        assertEquals(TextKey.ValidateDownload, action.label)
        assertEquals(PodcastAction.ValidateRetainedDownload("job-retained", "neutral-binding"), action.action)
        assertNull(neutral.mediaId)
        assertNull(neutral.bindingId)
    }

    @Test
    fun repeatedMemberFailureExposesExplicitChoicesInsteadOfSelectingOne() {
        val choices = listOf(
            RetainedValidationTarget("job-retained", "binding-r1", "batch-a", 0, 4),
            RetainedValidationTarget("job-retained", "binding-r1", "batch-b", 1, 7),
        )
        val entry = failed(target = null).copy(retainedValidationChoices = choices)
        val action = podcastDownloadAction(state(entry))
        assertNull(action.action)
        assertEquals(TextKey.ValidateDownload, action.label)
        assertEquals(listOf(0, 1), action.memberActions.map { it.memberPosition })
        assertEquals(listOf(TextKey.ValidateDownloadMember, TextKey.ValidateDownloadMember),
            action.memberActions.map { it.label })
        assertEquals(PodcastAction.ValidateRetainedDownload("job-retained", "binding-r1", "batch-b", 1, 7),
            action.memberActions[1].action)
        assertEquals(choices.map { it.batchId }, action.memberActions.map {
            (it.action as PodcastAction.ValidateRetainedDownload).batchId
        })
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
