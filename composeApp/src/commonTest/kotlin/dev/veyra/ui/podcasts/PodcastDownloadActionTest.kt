package dev.veyra.ui.podcasts

import dev.veyra.model.DownloadRecord
import dev.veyra.model.DownloadStatus
import dev.veyra.model.LanguagePreference
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.MediaProgress
import dev.veyra.model.OfflineAsset
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.ui.design.AppStrings
import dev.veyra.ui.design.TextKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PodcastDownloadActionTest {
    private val item = MediaItem("episode", "Authored test episode", MediaKind.EPISODE,
        SourceRef(SourceProvider.RSS, "episode", enclosureUrl = "https://example.org/audio.mp3"))

    private fun state(status: DownloadStatus, offline: Boolean = false) = PodcastEpisodeUiState(
        item, download = DownloadRecord("download", item.id, status), offlineOnly = offline,
    )

    @Test
    fun runningDownloadsPauseWhilePendingAndVerifyingDownloadsCanCancel() {
        assertEquals(PodcastAction.PauseDownload("download"), podcastDownloadAction(state(DownloadStatus.RUNNING)).action)
        listOf(DownloadStatus.QUEUED, DownloadStatus.RESOLVING, DownloadStatus.WAITING_NETWORK,
            DownloadStatus.VERIFYING).forEach { status ->
            assertEquals(PodcastAction.CancelDownload("download"), podcastDownloadAction(state(status)).action)
            assertTrue(podcastDownloadAction(state(status, offline = true)).enabled)
            assertTrue(podcastCanCancelDownload(state(status).download!!))
            assertNull(podcastRetainedAssetRemovalAction(state(status)))
        }
    }

    @Test
    fun retriesAndResumesStayDistinctFromCreatingOrDeletingADownload() {
        listOf(DownloadStatus.PAUSED, DownloadStatus.NEEDS_FOREGROUND, DownloadStatus.FAILED).forEach { status ->
            assertEquals(PodcastAction.ResumeDownload("download"), podcastDownloadAction(state(status)).action)
            assertFalse(podcastDownloadAction(state(status, offline = true)).enabled)
        }
        assertEquals(TextKey.Retry, podcastDownloadAction(state(DownloadStatus.FAILED)).label)
        assertEquals(PodcastAction.Download(item.id), podcastDownloadAction(state(DownloadStatus.CANCELLED)).action)
        assertEquals(PodcastAction.Download(item.id), podcastDownloadAction(state(DownloadStatus.COMPLETE)).action)
    }

    @Test
    fun aReadyAssetOffersPrimaryRemovalAndItAlwaysRequiresConfirmation() {
        val incomplete = state(DownloadStatus.COMPLETE)
        assertFalse(podcastDownloadAction(incomplete).requiresRemovalConfirmation)
        val ready = incomplete.copy(download = incomplete.download!!.copy(
            asset = OfflineAsset("asset", item.id, "/owned/audio.mp3", 1_024L)))
        val action = podcastDownloadAction(ready.copy(offlineOnly = true))
        assertEquals(PodcastAction.RemoveDownload("download"), action.action)
        assertTrue(action.enabled)
        assertTrue(action.requiresRemovalConfirmation)
    }

    @Test
    fun existingAssetChecksOfferStatusAndConfirmedRemovalNeverNoOpTransferActionsOrReady() {
        listOf(DownloadStatus.VERIFYING, DownloadStatus.NEEDS_FOREGROUND).forEach { status ->
            listOf(false, true).forEach { offline ->
                val base = state(status, offline)
                val checking = base.copy(download = base.download!!.copy(
                    asset = OfflineAsset("asset", item.id, "/owned/audio.mp3", 1_024L)))
                val action = podcastDownloadAction(checking)
                assertEquals(if (status == DownloadStatus.VERIFYING) TextKey.DownloadVerifying else TextKey.DownloadNeedsForeground,
                    action.label)
                assertNull(action.action)
                assertFalse(action.enabled)
                assertFalse(podcastCanCancelDownload(checking.download!!))
                val removal = assertNotNull(podcastRetainedAssetRemovalAction(checking))
                assertEquals(TextKey.RemoveDownload, removal.label)
                assertEquals(PodcastAction.RemoveDownload("download"), removal.action)
                assertTrue(removal.enabled)
                assertTrue(removal.requiresRemovalConfirmation)
                assertFalse(checking.downloaded)
                assertEquals(!offline, checking.canPlay)
            }
        }
    }

    @Test
    fun activeFinalizationAndCleanupOrOtherPhasesDoNotInventACompletedAssetCheck() {
        listOf(DownloadStatus.VERIFYING, DownloadStatus.NEEDS_FOREGROUND).forEach { status ->
            val active = state(status)
            val activeDownload = active.download!!
            assertNull(podcastRetainedAssetRemovalAction(active))
            assertTrue(podcastCanCancelDownload(activeDownload))
            val cleaning = active.copy(download = activeDownload.copy(cleanupPending = true,
                asset = OfflineAsset("asset", item.id, "/owned/audio.mp3", 1_024L)))
            assertNull(podcastRetainedAssetRemovalAction(cleaning))
            assertEquals(podcastDownloadAction(active), podcastDownloadAction(cleaning))
        }
        DownloadStatus.entries.filter { it !in setOf(DownloadStatus.VERIFYING, DownloadStatus.NEEDS_FOREGROUND, DownloadStatus.FAILED) }
            .forEach { status ->
                val base = state(status)
                assertNull(podcastRetainedAssetRemovalAction(base.copy(download = base.download!!.copy(
                    asset = OfflineAsset("asset", item.id, "/owned/audio.mp3", 1_024L)))))
            }
    }

    @Test
    fun failedOwnedAssetCanBeRemovedOfflineWithoutEnablingRetryOrPretendingItIsReady() {
        listOf(false, true).forEach { offline ->
            val failed = state(DownloadStatus.FAILED, offline)
            val owned = failed.copy(download = failed.download!!.copy(
                asset = OfflineAsset("asset", item.id, "/owned/failed.mp3", 1_024L)))
            val retry = podcastDownloadAction(owned)
            assertEquals(TextKey.Retry, retry.label)
            assertEquals(PodcastAction.ResumeDownload("download"), retry.action)
            assertEquals(!offline, retry.enabled)
            assertFalse(retry.requiresRemovalConfirmation)
            val remove = assertNotNull(podcastRetainedAssetRemovalAction(owned))
            assertEquals(TextKey.RemoveDownload, remove.label)
            assertEquals(PodcastAction.RemoveDownload("download"), remove.action)
            assertTrue(remove.enabled)
            assertTrue(remove.requiresRemovalConfirmation)
            assertFalse(owned.downloaded)
            if (offline) assertFalse(owned.canPlay)
            assertFalse(podcastCanCancelDownload(owned.download!!))
        }
    }

    @Test
    fun failedWithoutOwnedBytesOrAlreadyCleaningDoesNotInventAnotherRemoval() {
        val failed = state(DownloadStatus.FAILED)
        assertNull(podcastRetainedAssetRemovalAction(failed))
        val cleaning = failed.copy(download = failed.download!!.copy(cleanupPending = true,
            asset = OfflineAsset("asset", item.id, "/owned/failed.mp3", 1_024L)))
        assertNull(podcastRetainedAssetRemovalAction(cleaning))
        assertEquals(podcastDownloadAction(failed), podcastDownloadAction(cleaning))
        assertFalse(podcastCanCancelDownload(cleaning.download!!))
    }

    @Test
    fun newDownloadsRequireAnOnlineResolutionTitleNotAnRssEnclosure() {
        val row = PodcastEpisodeUiState(item)
        assertTrue(podcastDownloadAction(row).enabled)
        assertFalse(podcastDownloadAction(row.copy(offlineOnly = true)).enabled)
        assertTrue(podcastDownloadAction(row.copy(episode = item.copy(source = item.source.copy(enclosureUrl = null)))).enabled)
        assertFalse(podcastDownloadAction(row.copy(episode = item.copy(title = " "))).enabled)
    }

    @Test
    fun playAndReplayCopyDoNotPromiseResumeWithoutARecordingMatchInBothLanguages() {
        listOf(LanguagePreference.ENGLISH, LanguagePreference.ARABIC).forEach { language ->
            val strings = AppStrings(language)
            val row = PodcastEpisodeUiState(item)
            assertEquals(strings.text(TextKey.Play), podcastPlayLabel(row, strings))
            val started = row.copy(progress = MediaProgress(positionMs = 45_000L, durationMs = 45_000L))
            assertEquals(strings.text(TextKey.Play), podcastPlayLabel(started, strings))
            assertEquals(TextKey.PodcastProgressRecordingUnverified, podcastHistoricalProgressNoticeKey(started))
            val markedButUnbound = started.copy(progress = started.progress!!.copy(played = true))
            assertTrue(markedButUnbound.played)
            assertEquals(45_000L, markedButUnbound.historicalPositionMs)
            assertEquals(strings.text(TextKey.Play), podcastPlayLabel(markedButUnbound, strings))
        }
    }
}
