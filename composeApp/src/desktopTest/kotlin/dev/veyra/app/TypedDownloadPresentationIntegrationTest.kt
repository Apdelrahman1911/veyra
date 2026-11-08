package dev.veyra.app

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import dev.veyra.data.getOrThrow
import dev.veyra.model.DownloadStatus
import dev.veyra.model.LanguagePreference
import dev.veyra.model.SourceProvider
import dev.veyra.playback.AssetValidation
import dev.veyra.ui.design.AppStrings
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraTheme
import dev.veyra.ui.downloads.DownloadSurfaceOrigin
import dev.veyra.ui.downloads.downloadSurface
import dev.veyra.ui.downloads.verifiedDownload
import dev.veyra.ui.music.LibraryScreen
import dev.veyra.ui.music.LibrarySection
import dev.veyra.ui.music.MusicUiAction
import dev.veyra.ui.music.MusicUiOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * Real AppContainer/Room/files cold-complete flow through the existing Downloads screen
 * projection. Source and native validation are authored fakes over NON-AUDIO bytes; this is
 * not playable-audio, device, or distribution proof.
 */
@OptIn(ExperimentalTestApi::class)
class TypedDownloadPresentationIntegrationTest {
    @get:Rule val compose = createComposeRule()

    private val strings = AppStrings(LanguagePreference.ENGLISH)

    @Test
    fun coldCompleteTypedJobIsCheckingOnTheExistingScreenAndBecomesReadyOnlyAfterThisProcessValidates() = runBlocking {
        withTimeout(120_000L) {
            val directory = TypedDownloadPresentationSupport.workDirectory()
            val item = TypedDownloadPresentationSupport.youtubeItem()
            var first: TypedDownloadPresentationSupport? = null
            var reopened: TypedDownloadPresentationSupport? = null
            val release = CompletableDeferred<Unit>()
            try {
                first = TypedDownloadPresentationSupport(directory)
                first.initialize()
                first.store.upsertCatalog(listOf(item)).getOrThrow()
                first.app.onMusicAction(MusicUiAction.Download(item))
                val completed = withTimeout(40_000L) {
                    first.app.downloads.acquisitionPresentation.first { presentation ->
                        presentation.view.acquisition.assetUses.isNotEmpty() &&
                            presentation.states.values.any { it.status == DownloadStatus.COMPLETE }
                    }
                }
                val job = completed.states.values.single { it.status == DownloadStatus.COMPLETE }
                val bindingId = assertNotNull(completed.view.acquisition.physicalDownloads.getValue(job.id)
                    .producer.bindingId)
                val asset = assertNotNull(job.assetId?.let(completed.view.acquisition.materializations::get))
                assertTrue(Files.isRegularFile(Path.of(asset.uri)))
                assertContentEquals(TypedDownloadPresentationSupport.NON_AUDIO, Files.readAllBytes(Path.of(asset.uri)))
                assertEquals(1, first.gateway.transfers.get())
                assertNotNull(completed.verifiedDownload(bindingId))
                assertTrue(first.platform.localValidationCalls.get() > 0)
                first.close()
                first = null

                val validating = CompletableDeferred<Unit>()
                reopened = TypedDownloadPresentationSupport(directory)
                reopened.platform.validateLocal = {
                    validating.complete(Unit)
                    release.await()
                    AssetValidation.Valid(12_345L)
                }
                reopened.initialize()
                validating.await()

                val cold = reopened.app.downloads.acquisitionPresentation.value
                val coldSurface = downloadSurface(reopened.app.library.value, cold)
                val coldEntry = assertNotNull(coldSurface.forMedia(item.id))
                assertEquals(job.id, coldEntry.id)
                assertTrue(coldEntry.status in setOf(DownloadStatus.VERIFYING, DownloadStatus.NEEDS_FOREGROUND))
                assertTrue(coldEntry.checking)
                assertFalse(coldEntry.ready)
                assertNull(cold.verifiedDownload(bindingId))
                assertFalse(job.id in reopened.app.library.value.downloads,
                    "Only the existing schema2 projection may contain snapshot DownloadRecords")

                var shownSnapshot by mutableStateOf(reopened.app.library.value)
                var shownSurface by mutableStateOf(coldSurface)
                compose.setContent {
                    VeyraTheme(false, LanguagePreference.ENGLISH) {
                        LibraryScreen(shownSnapshot, LibrarySection.DOWNLOADS, {},
                            Modifier.size(900.dp, 700.dp),
                            MusicUiOptions(downloadableProviders = setOf(SourceProvider.YOUTUBE),
                                downloads = shownSurface))
                    }
                }
                compose.onNodeWithTag("downloads-list").performScrollToNode(hasTestTag("download-${job.id}"))
                compose.onNodeWithText(strings.downloadStatus(coldEntry.status)).assertIsDisplayed()
                compose.onNodeWithText(strings.text(TextKey.ReadyOffline)).assertDoesNotExist()
                compose.onNodeWithText(strings.text(TextKey.Play)).assertDoesNotExist()
                assertTrue(reopened.app.playback.state.value.currentItem == null)
                assertTrue(Files.isRegularFile(Path.of(asset.uri)))

                release.complete(Unit)
                val ready = withTimeout(40_000L) {
                    reopened.app.downloads.acquisitionPresentation.first { presentation ->
                        presentation.verifiedDownload(bindingId) != null
                    }
                }
                val readySurface = downloadSurface(reopened.app.library.value, ready)
                val readyEntry = assertNotNull(readySurface.forMedia(item.id))
                assertTrue(readyEntry.ready)
                assertEquals(DownloadStatus.COMPLETE, readyEntry.status)
                assertEquals(bindingId.value, readyEntry.bindingId)
                assertEquals(asset.sizeBytes, readySurface.storageBytes())

                compose.runOnIdle {
                    shownSnapshot = reopened.app.library.value
                    shownSurface = readySurface
                }
                compose.onNodeWithTag("downloads-list").performScrollToNode(hasTestTag("download-${job.id}"))
                compose.onNodeWithText(strings.text(TextKey.Downloaded)).assertIsDisplayed()
                compose.onNodeWithText(strings.text(TextKey.Play)).assertIsDisplayed()
                compose.onNodeWithText(strings.text(TextKey.ReadyOffline)).assertDoesNotExist()

                assertContentEquals(TypedDownloadPresentationSupport.NON_AUDIO, Files.readAllBytes(Path.of(asset.uri)))
                assertTrue(reopened.platform.localValidationCalls.get() > 0)
                assertEquals(0, reopened.gateway.firstResults.get(), "Cold validation cannot query a source")
                assertEquals(0, reopened.gateway.preparations.get(), "Cold validation cannot prepare a transfer")
                assertEquals(0, reopened.gateway.transfers.get(), "Cold validation cannot transfer again")
                assertEquals(0, reopened.platform.youtubeRequests.get())
            } finally {
                release.complete(Unit)
                try { first?.close() } catch (_: Exception) {}
                try { reopened?.close() } catch (_: Exception) {}
            }
        }
    }

    @Test
    fun completedNeutralTypedDownloadProjectsHonestlyAndColdRevalidationUsesTheSameScreen() = runBlocking {
        withTimeout(180_000L) {
            val directory = TypedDownloadPresentationSupport.workDirectory()
            val title = "Captured neutral member — تسجيل من المصدر"
            var first: TypedDownloadPresentationSupport? = null
            var reopened: TypedDownloadPresentationSupport? = null
            val release = CompletableDeferred<Unit>()
            try {
                first = TypedDownloadPresentationSupport(directory)
                first.initialize()
                val queued = first.enqueueNeutralDownload(title)
                val completed = withTimeout(40_000L) {
                    first.app.downloads.acquisitionPresentation.first { presentation ->
                        presentation.states[queued.jobId]?.status == DownloadStatus.COMPLETE &&
                            presentation.view.acquisition.assetUses.any { it.requestedBindingId == queued.bindingId }
                    }
                }
                val job = completed.states.getValue(queued.jobId)
                val asset = assertNotNull(job.assetId?.let(completed.view.acquisition.materializations::get))
                assertContentEquals(TypedDownloadPresentationSupport.NON_AUDIO, Files.readAllBytes(Path.of(asset.uri)))
                assertEquals(1, first.gateway.transfers.get())

                val surface = downloadSurface(first.app.library.value, completed)
                val entry = assertNotNull(surface.entries.singleOrNull { it.id == queued.jobId })
                assertEquals(DownloadSurfaceOrigin.TYPED_SOURCE, entry.origin)
                assertNull(entry.mediaId)
                assertEquals(title, entry.displayTitle)
                assertEquals(DownloadStatus.COMPLETE, entry.status)
                assertTrue(entry.ready)
                assertNull(entry.bindingId)
                assertEquals(asset.sizeBytes, surface.storageBytes())
                assertTrue(surface.readyMediaIds.isEmpty())
                assertTrue(surface.catalogueMediaIds.isEmpty())

                val actions = mutableListOf<MusicUiAction>()
                var targetApp = first.app
                var shownSnapshot by mutableStateOf(first.app.library.value)
                var shownSurface by mutableStateOf(surface)
                compose.setContent {
                    VeyraTheme(false, LanguagePreference.ENGLISH) {
                        LibraryScreen(shownSnapshot, LibrarySection.DOWNLOADS, { action ->
                            actions += action
                            targetApp.onMusicAction(action)
                        }, Modifier.size(900.dp, 700.dp), MusicUiOptions(downloads = shownSurface))
                    }
                }
                compose.onNodeWithTag("downloads-list").performScrollToNode(hasTestTag("download-${queued.jobId}"))
                compose.onNodeWithText(title).assertIsDisplayed()
                compose.onNodeWithText(strings.text(TextKey.SourceRecording)).assertIsDisplayed()
                compose.onNodeWithText(strings.downloadStatus(DownloadStatus.COMPLETE)).assertIsDisplayed()
                compose.onNodeWithText(strings.text(TextKey.RemoveDownload)).assertIsDisplayed()
                compose.onNodeWithText(strings.text(TextKey.Play)).assertDoesNotExist()
                compose.onNodeWithText(strings.text(TextKey.ReadyOffline)).assertDoesNotExist()

                first.close()
                first = null

                val validating = CompletableDeferred<Unit>()
                reopened = TypedDownloadPresentationSupport(directory)
                reopened.platform.validateLocal = {
                    validating.complete(Unit)
                    release.await()
                    AssetValidation.Valid(12_345L)
                }
                reopened.initialize()
                validating.await()

                val cold = reopened.app.downloads.acquisitionPresentation.value
                val coldSurface = downloadSurface(reopened.app.library.value, cold)
                val coldEntry = assertNotNull(coldSurface.entries.singleOrNull { it.id == queued.jobId })
                assertTrue(coldEntry.status in setOf(DownloadStatus.VERIFYING, DownloadStatus.NEEDS_FOREGROUND))
                assertTrue(coldEntry.checking)
                assertFalse(coldEntry.ready)
                assertNull(coldEntry.bindingId)
                assertEquals(0L, coldSurface.storageBytes())
                targetApp = reopened.app
                compose.runOnIdle {
                    shownSnapshot = reopened.app.library.value
                    shownSurface = coldSurface
                }
                compose.onNodeWithTag("downloads-list").performScrollToNode(hasTestTag("download-${queued.jobId}"))
                compose.onNodeWithText(strings.downloadStatus(coldEntry.status)).assertIsDisplayed()
                compose.onNodeWithText(strings.text(TextKey.RemoveDownload)).assertIsDisplayed()
                compose.onNodeWithText(strings.text(TextKey.Play)).assertDoesNotExist()
                compose.onNodeWithText(strings.text(TextKey.ReadyOffline)).assertDoesNotExist()

                release.complete(Unit)
                val ready = withTimeout(40_000L) {
                    reopened.app.downloads.acquisitionPresentation.first { presentation ->
                        presentation.verifiedDownload(queued.bindingId) != null
                    }
                }
                val readySurface = downloadSurface(reopened.app.library.value, ready)
                val readyEntry = assertNotNull(readySurface.entries.singleOrNull { it.id == queued.jobId })
                assertEquals(DownloadStatus.COMPLETE, readyEntry.status)
                assertTrue(readyEntry.ready)
                assertNull(readyEntry.bindingId)
                assertEquals(asset.sizeBytes, readySurface.storageBytes())
                compose.runOnIdle {
                    shownSnapshot = reopened.app.library.value
                    shownSurface = readySurface
                }
                compose.onNodeWithTag("downloads-list").performScrollToNode(hasTestTag("download-${queued.jobId}"))
                compose.onNodeWithText(title).assertIsDisplayed()
                compose.onNodeWithText(strings.text(TextKey.SourceRecording)).assertIsDisplayed()
                compose.onNodeWithText(strings.downloadStatus(DownloadStatus.COMPLETE)).assertIsDisplayed()
                compose.onNodeWithText(strings.text(TextKey.RemoveDownload)).performClick()
                compose.onNodeWithTag("confirm-action").performClick()
                assertEquals(listOf<MusicUiAction>(MusicUiAction.RemoveDownload(queued.jobId)), actions)

                assertContentEquals(TypedDownloadPresentationSupport.NON_AUDIO, Files.readAllBytes(Path.of(asset.uri)))
                assertTrue(reopened.platform.localValidationCalls.get() > 0)
                assertEquals(0, reopened.gateway.firstResults.get(), "Cold validation cannot query a source")
                assertEquals(0, reopened.gateway.preparations.get(), "Cold validation cannot prepare a transfer")
                assertEquals(0, reopened.gateway.transfers.get(), "Cold validation cannot transfer again")
                assertEquals(0, reopened.platform.youtubeRequests.get())
            } finally {
                release.complete(Unit)
                try { first?.close() } catch (_: Exception) {}
                try { reopened?.close() } catch (_: Exception) {}
            }
        }
    }
}
