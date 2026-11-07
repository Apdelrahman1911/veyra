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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure authored projections: no file access, validation, source lookup or playback. */
class PodcastUnavailablePlaybackNoticeTest {
    private val episode = MediaItem("notice-episode", "Authored complete episode title — حلقة",
        MediaKind.EPISODE, SourceRef(SourceProvider.RSS, "notice-episode"))
    private val asset = OfflineAsset("notice-asset", episode.id, "/authored/notice-audio.mp3", 4_096L)

    private fun retained(status: DownloadStatus, offline: Boolean = true) = PodcastEpisodeUiState(
        episode = episode,
        download = DownloadRecord("notice-download", episode.id, status, bytesDownloaded = 4_096L,
            totalBytes = 4_096L, asset = asset),
        offlineOnly = offline,
    )

    @Test
    fun retainedFileBeingVerifiedExplainsOfflineUnavailability() {
        val state = retained(DownloadStatus.VERIFYING)
        assertEquals(TextKey.DownloadVerifying, podcastUnavailablePlaybackNotice(state))
        assertFalse(state.downloaded)
        assertFalse(state.canPlay)
        assertEquals(4_096L, state.download!!.bytesDownloaded)
        assertEquals(asset, state.download!!.asset)
    }

    @Test
    fun retainedFileNeedingForegroundExplainsOfflineUnavailability() {
        val state = retained(DownloadStatus.NEEDS_FOREGROUND)
        assertEquals(TextKey.DownloadNeedsForeground, podcastUnavailablePlaybackNotice(state))
        assertFalse(state.downloaded)
        assertFalse(state.canPlay)
        assertFalse(state.canResume)
    }

    @Test
    fun onlineResolutionEligibilitySuppressesUnavailableNoticeEvenWhileFileIsChecking() {
        listOf(DownloadStatus.VERIFYING, DownloadStatus.NEEDS_FOREGROUND).forEach { status ->
            val state = retained(status, offline = false)
            assertTrue(state.hasAudioSource) // The complete RSS title, not an enclosure URL.
            assertTrue(state.canPlay) // Eligibility to attempt central resolution, not Ready.
            assertFalse(state.downloaded)
            assertNull(podcastUnavailablePlaybackNotice(state))
        }
    }

    @Test
    fun checkingWithoutRetainedAssetOrDuringCleanupKeepsExistingFallback() {
        listOf(DownloadStatus.VERIFYING, DownloadStatus.NEEDS_FOREGROUND).forEach { status ->
            val state = retained(status)
            val record = state.download!!
            listOf(record.copy(asset = null), record.copy(cleanupPending = true)).forEach { download ->
                val unavailable = state.copy(download = download)
                assertEquals(TextKey.NotDownloaded, podcastUnavailablePlaybackNotice(unavailable))
                assertFalse(unavailable.downloaded)
                assertFalse(unavailable.canPlay)
                assertEquals(TextKey.EpisodeUnavailable,
                    podcastUnavailablePlaybackNotice(unavailable.copy(episode = episode.copy(title = " "))))
            }
        }
    }

    @Test
    fun otherPhasesDoNotInventARetainedFileCheck() {
        DownloadStatus.entries.filter { it !in setOf(DownloadStatus.VERIFYING,
            DownloadStatus.NEEDS_FOREGROUND, DownloadStatus.COMPLETE) }.forEach { status ->
            val state = retained(status)
            assertEquals(TextKey.NotDownloaded, podcastUnavailablePlaybackNotice(state), status.name)
            assertFalse(state.downloaded)
            assertFalse(state.canPlay)
        }
    }

    @Test
    fun committedProjectionAndMalformedCompleteRecordKeepExistingAdmission() {
        val complete = retained(DownloadStatus.COMPLETE)
        assertTrue(complete.downloaded)
        assertTrue(complete.canPlay)
        assertNull(podcastUnavailablePlaybackNotice(complete))
        listOf<OfflineAsset?>(null, asset.copy(sizeBytes = 0L), asset.copy(uri = " "),
            asset.copy(mediaId = "different-episode")).forEach { invalidAsset ->
            val invalid = complete.copy(download = complete.download!!.copy(asset = invalidAsset))
            assertFalse(invalid.downloaded)
            assertFalse(invalid.canPlay)
            assertEquals(TextKey.NotDownloaded, podcastUnavailablePlaybackNotice(invalid))
        }
    }

    @Test
    fun sourceMissingAndOfflineTitleEligibilityKeepExistingFallback() {
        val online = PodcastEpisodeUiState(episode)
        assertTrue(online.hasAudioSource)
        assertNull(podcastUnavailablePlaybackNotice(online))
        assertEquals(TextKey.NotDownloaded, podcastUnavailablePlaybackNotice(online.copy(offlineOnly = true)))
        listOf(episode.copy(title = " "),
            episode.copy(source = SourceRef(SourceProvider.LOCAL, "missing-local"))).forEach { missing ->
            listOf(false, true).forEach { offline ->
                val state = PodcastEpisodeUiState(missing, offlineOnly = offline)
                assertFalse(state.hasAudioSource)
                assertFalse(state.canPlay)
                assertEquals(TextKey.EpisodeUnavailable, podcastUnavailablePlaybackNotice(state))
            }
        }
    }

    @Test
    fun checkingKeysRemainLocalizedAndDoNotClaimMissingOrReadyInEitherLanguage() {
        listOf(LanguagePreference.ENGLISH, LanguagePreference.ARABIC).forEach { language ->
            val strings = AppStrings(language)
            listOf(DownloadStatus.VERIFYING to TextKey.DownloadVerifying,
                DownloadStatus.NEEDS_FOREGROUND to TextKey.DownloadNeedsForeground).forEach { (status, expected) ->
                val key = assertNotNull(podcastUnavailablePlaybackNotice(retained(status)))
                assertEquals(expected, key)
                val notice = strings.text(key)
                assertEquals(strings.downloadStatus(status), notice)
                assertTrue(notice.isNotBlank())
                assertNotEquals(strings.text(TextKey.NotDownloaded), notice)
                assertNotEquals(strings.text(TextKey.Downloaded), notice)
            }
        }
    }

    @Test
    fun noticeProjectionPreservesHistoricalProgressAndRetainedAssetActions() {
        val progress = MediaProgress(positionMs = 45_000L, durationMs = 180_000L,
            executionBindingId = "historical-authored-binding")
        listOf(DownloadStatus.VERIFYING, DownloadStatus.NEEDS_FOREGROUND).forEach { status ->
            val state = retained(status).copy(progress = progress)
            val key = assertNotNull(podcastUnavailablePlaybackNotice(state))
            assertEquals(progress, state.progress)
            assertEquals(45_000L, state.historicalPositionMs)
            assertEquals(0L, state.positionMs)
            assertFalse(state.canResume)
            assertFalse(state.played)
            assertEquals(PodcastProgressRecording.UNVERIFIED, state.progressRecording)
            assertEquals(TextKey.PodcastProgressRecordingUnverified, podcastHistoricalProgressNoticeKey(state))
            val primary = podcastDownloadAction(state)
            assertEquals(key, primary.label)
            assertFalse(primary.enabled)
            assertNull(primary.action)
            assertFalse(podcastCanCancelDownload(state.download!!))
            val removal = assertNotNull(podcastRetainedAssetRemovalAction(state))
            assertEquals(PodcastAction.RemoveDownload("notice-download"), removal.action)
            assertTrue(removal.enabled)
            assertTrue(removal.requiresRemovalConfirmation)
        }
    }
}
