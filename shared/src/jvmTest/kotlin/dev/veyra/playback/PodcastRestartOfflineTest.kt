package dev.veyra.playback

import dev.veyra.acquisition.AcquisitionAdmissionFactory
import dev.veyra.acquisition.AcquisitionCaptureFactory
import dev.veyra.acquisition.QueueAcquisitionCoordinator
import dev.veyra.data.AcquisitionPlaybackCommand
import dev.veyra.data.ExecutionQueueExpectation
import dev.veyra.data.QueueAdmissionOperation
import dev.veyra.data.getOrThrow
import dev.veyra.downloads.DownloadAcquisitionRoomFixture
import dev.veyra.downloads.DownloadAcquisitionRoomOwner
import dev.veyra.downloads.DownloadActivation
import dev.veyra.downloads.acquisitionDownloadRoomCase
import dev.veyra.model.*
import dev.veyra.source.execution.AppSourceResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import kotlin.test.*

/**
 * Actual canonical typed MEDIA capture/receipt/queue/standalone download + real Room reopen and
 * local SHA validation. Source answers, transfer bytes and native decoder are explicit authored
 * fakes; bytes are NON-AUDIO. No real live-source, native-output or physical-device proof.
 */
class PodcastRestartOfflineTest {
    @Test fun offlineRestartKeepsBoundAssetAndZeroIntentAcrossRoomCloseAndReopen() = acquisitionDownloadRoomCase {
        val show = PodcastShow("offline-show", "Complete authored show", "https://publisher.example/feed")
        val item = MediaItem("offline-episode", "Complete authored episode title — العنوان الكامل",
            MediaKind.EPISODE, SourceRef(SourceProvider.RSS, "offline-guid",
                feedUrl = show.feedUrl, episodeGuid = "offline-guid"),
            showId = show.id, durationMs = 100_000)
        val first = owner()
        first.store.upsertCatalog(listOf(item), shows = listOf(show)).getOrThrow()
        val snapshot = first.store.acquisitionView().snapshot
        val request = AcquisitionCaptureFactory(clock).media(snapshot.media.getValue(item.id), snapshot)
        val accepted = accept(first, request)
        val bindingId = assertIs<AcquisitionOutcome.Single>(accepted.outcome).bindingId
        first.coordinator.enqueueAccepted(accepted.request.id, bindingId) { }
        first.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitState(first.store) { state ->
            state.physicalDownloads.values.singleOrNull()?.status == DownloadStatus.COMPLETE &&
                state.assetUses.any { it.requestedBindingId == bindingId }
        }
        idle(first)
        val completed = first.store.acquisitionView().acquisition
        val asset = completed.materializations.values.single()
        val binding = completed.bindings.getValue(bindingId)
        val physical = completed.physicalDownloads.values.single()
        assertEquals(physical.assetId, asset.id)
        assertEquals(physical.producer, asset.producer)
        assertEquals(0, first.selector.requests.size, "Typed accepted fixture does not invent a second search")
        assertEquals(1, first.validations.size)

        var opened = first.store
        var engine = PlaybackTestEngine().apply { autoSeek = false }
        var controller = playbackController(first, opened, engine)
        controller.initialize().getOrThrow()
        assertEquals(PlaybackPhase.IDLE, controller.state.value.phase)
        val prepared = AcquisitionAdmissionFactory(AcquisitionCaptureFactory(clock), clock)
            .single(request, PlaybackControlsMode.PODCAST, opened.acquisitionView().snapshot.revision)
        val musicQueue = opened.acquisitionView().acquisition.queues.getValue(QueueContext.PODCAST)
        controller.dispatch(PlaybackCommand.AdmitAcquisition(prepared, QueueContext.PODCAST,
            musicQueue.editRevision, QueueAdmissionOperation.REPLACE, activate = true,
            startOccurrenceId = prepared.occurrences.single().id)).getOrThrow()
        controller.awaitPlaying()
        val firstLoad = engine.loads.single()
        assertEquals(0L, firstLoad.startPositionMs)
        val acquired = assertNotNull(firstLoad.acquired)
        assertTrue(acquired.source.isLocal)
        assertEquals(asset.uri, acquired.source.uri)
        assertEquals(asset.id, acquired.source.materializationAssetId)
        assertEquals(binding, acquired.selection.binding)
        assertEquals(item.id, acquired.selection.catalogMedia?.id)
        assertEquals(2, first.validations.size, "Initial typed native play validates the owned file")
        assertEquals(bindingId, controller.state.value.currentSelection?.bindingId)

        engine.emit(EngineEvent.Position(controller.state.value.generation, 12_000, 100_000))
        controller.state.first { it.positionMs == 12_000L }
        controller.dispatch(PlaybackCommand.FlushProgress).getOrThrow()
        assertEquals(12_000L, opened.acquisitionView().acquisition.queues.getValue(QueueContext.PODCAST).positionMs)
        assertEquals(12_000L, opened.acquisition.playbackView().catalogProgress.getValue(item.id).positionMs)
        val firstHistory = opened.acquisition.playbackView().history.single()
        assertEquals(12_000L, firstHistory.lastPositionMs)
        controller.close()
        first.coordinator.close()
        opened.close()

        val second = owner(store())
        opened = second.store
        engine = PlaybackTestEngine().apply { autoSeek = false }
        controller = playbackController(second, opened, engine)
        controller.initialize().getOrThrow()
        assertEquals(PlaybackPhase.PAUSED, controller.state.value.phase)
        assertEquals(12_000L, controller.state.value.positionMs)
        assertFalse(controller.state.value.desiredPlaying)
        assertTrue(engine.loads.isEmpty())
        val queue = opened.acquisitionView().acquisition.queues.getValue(QueueContext.PODCAST)
        controller.dispatch(PlaybackCommand.PlayOccurrence(QueueContext.PODCAST, queue.editRevision,
            requireNotNull(queue.currentOccurrenceId), startFromBeginning = true)).getOrThrow()
        controller.awaitPlaying()
        engine.emit(EngineEvent.Position(controller.state.value.generation, 0, 100_000))
        controller.state.first { it.positionMs == 0L }
        controller.dispatch(PlaybackCommand.FlushProgress).getOrThrow()
        assertEquals(0L, engine.loads.single().startPositionMs)
        assertEquals(1, second.validations.size, "A reopened asset must be validated again in this process")
        assertEquals(0L, opened.acquisition.playbackView().catalogProgress.getValue(item.id).positionMs)
        assertEquals(bindingId.value, opened.acquisition.playbackView().catalogProgress.getValue(item.id).executionBindingId)
        assertFalse(opened.acquisition.playbackView().catalogProgress.getValue(item.id).played)
        assertEquals(listOf(12_000L, 0L), opened.acquisition.playbackView().history.map { it.lastPositionMs })
        assertTrue(engine.seeks.isEmpty())
        assertTrue(second.selector.requests.isEmpty(), "Offline restart must not search")
        assertTrue(second.gateway.preparations.isEmpty(), "Offline restart must not resolve remote audio")
        assertEquals(0, second.gateway.transfers.size)
        assertEquals(0, second.gate.acquires.get())
        Files.newInputStream(databasePath).use {
            assertEquals("SQLite format 3\u0000", it.readNBytes(16).decodeToString())
        }
    }

    private fun DownloadAcquisitionRoomFixture.playbackController(owner: DownloadAcquisitionRoomOwner,
        store: dev.veyra.data.LibraryStore, engine: PlaybackTestEngine): PlaybackController {
        val captures = AcquisitionCaptureFactory(clock)
        val acquisitions = AcquisitionAdmissionFactory(captures, clock)
        val resolver = AcquisitionPlaybackResolver(store, owner.gateway, files, owner.localValidator, owner.materializationEvidence)
        val services = PlaybackAcquisitionServices(store.acquisition, owner.acquisition,
            QueueAcquisitionCoordinator(store.acquisition, owner.acquisition), resolver, captures, acquisitions)
        val legacy = AppSourceResolver(store, files, AssetValidator { error("typed playback does not use the legacy asset validator") },
            owner.legacy, clock, owner.materializationEvidence)
        return PlaybackController(store, engine, legacy, parent, clock, dispatcher = Dispatchers.Default, acquisition = services)
    }

    private suspend fun PlaybackController.awaitPlaying() {
        val result = state.first { it.phase == PlaybackPhase.PLAYING || it.phase == PlaybackPhase.ERROR }
        assertEquals(PlaybackPhase.PLAYING, result.phase,
            "Authored native seam must be playing: ${result.failure?.code} / ${result.failure?.detail}")
    }
}
