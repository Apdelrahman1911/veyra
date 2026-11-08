package dev.veyra.app

import dev.kmpyt.YouTubeClient
import dev.kmpyt.extraction.ResearchRequest
import dev.kmpyt.extraction.ResearchTransport
import dev.veyra.data.createPlatformLibraryStore
import dev.veyra.data.getOrThrow
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.AppSettings
import dev.veyra.model.DownloadStatus
import dev.veyra.model.ExecutableSubject
import dev.veyra.model.ExecutionBindingId
import dev.veyra.model.LanguagePreference
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.PodcastShow
import dev.veyra.model.QueueContext
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.model.VeyraException
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.AssetValidator
import dev.veyra.playback.AudioEngine
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.EngineEvent
import dev.veyra.playback.EnginePhase
import dev.veyra.playback.LocalMediaValidationInput
import dev.veyra.playback.LocalMediaValidator
import dev.veyra.playback.NativePlaybackSource
import dev.veyra.playback.NativeReaderDrainResult
import dev.veyra.playback.PlaybackCommand
import dev.veyra.playback.PlaybackSelection
import dev.veyra.playback.PlaybackState
import dev.veyra.playback.getOrThrow as playbackResultGetOrThrow
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.source.execution.AcquisitionPlaybackSource
import dev.veyra.source.youtube.YouTubeMediaSource
import dev.veyra.ui.music.MusicUiAction
import dev.veyra.ui.podcasts.PodcastAction
import dev.veyra.ui.downloads.verifiedDownload
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real AppContainer/Room/files/controller saved-R1 playback. The source, transfer and native
 * answers are authored NON-AUDIO doubles; Room, files, the one controller and the local resolver
 * are real. The fixture never exposes a synthetic binding or source.
 */
class SavedRecordingPlaybackTest {
    @Test
    fun savedMusicR1PlaysOfflineWithoutFreshResolutionAndFreshR2StillFails() = runBlocking {
        withTimeout(180_000L) {
            val item = TypedDownloadPresentationSupport.youtubeItem("saved-music", "Saved complete music title")
            val fixture = SavedPlaybackFixture()
            try {
                fixture.initialize()
                fixture.store.upsertCatalog(listOf(item)).getOrThrow()
                val binding = fixture.downloadAndWait(item)
                fixture.app.changeSettings { it.copy(offlineOnly = true) }
                withTimeout(20_000L) { fixture.app.library.first { it.settings.offlineOnly } }
                val before = fixture.gatewayCounts
                val state = fixture.playSaved(MusicUiAction.PlaySaved(item, binding.value), binding)
                assertTrue(state.isPlaying)
                assertEquals(binding, state.currentSelection?.bindingId)
                assertEquals(item.id, state.currentSelection?.catalogMedia?.id)
                assertEquals(1, fixture.engine.loads.size)
                assertTrue(fixture.engine.loads.single().source.isLocal)
                assertEquals(before, fixture.gatewayCounts, "Saved R1 playback must not resolve/transfer again")

                // A saved playback creates/reuses exactly one exact occurrence for the R1 request.
                assertEquals(1, fixture.occurrencesFor(binding))
                val firstLoads = fixture.engine.loads.size
                fixture.playSaved(MusicUiAction.PlaySaved(item, binding.value), binding)
                withTimeout(20_000L) { while (fixture.engine.loads.size <= firstLoads) delay(20) }
                assertEquals(1, fixture.occurrencesFor(binding),
                    "The second saved tap must reuse the exact queued occurrence")
                assertEquals(firstLoads + 1, fixture.engine.loads.size)
                assertEquals(before, fixture.gatewayCounts)

                // Fresh online/offline Play still manufactures R2 and fails offline; it never
                // borrows the saved R1 asset.
                val loads = fixture.engine.loads.size
                val prepare = fixture.gateway.preparations.get()
                val transfers = fixture.gateway.transfers.get()
                fixture.app.onMusicAction(MusicUiAction.Play(item))
                val failure = withTimeout(20_000L) {
                    fixture.app.playback.state.first { it.failure != null }.failure
                }
                assertNotNull(failure)
                assertEquals(FailureCode.OFFLINE_POLICY, failure.code)
                assertEquals(loads, fixture.engine.loads.size)
                assertEquals(prepare, fixture.gateway.preparations.get())
                assertEquals(transfers, fixture.gateway.transfers.get())
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun savedPodcastR1UsesPodcastContextAndTheSameNoFreshResolutionFence() = runBlocking {
        withTimeout(180_000L) {
            val episode = MediaItem("saved-episode", "Saved complete episode title", MediaKind.EPISODE,
                SourceRef(SourceProvider.YOUTUBE, "BBBBBBBBBBB",
                    canonicalUrl = "https://www.youtube.com/watch?v=BBBBBBBBBBB"),
                creator = "Authored publisher", durationMs = 300_000L, showId = "saved-show")
            val show = PodcastShow("saved-show", "Saved show", "https://example.org/feed",
                episodeIds = listOf(episode.id))
            val fixture = SavedPlaybackFixture()
            try {
                fixture.initialize()
                fixture.store.upsertCatalog(listOf(episode), shows = listOf(show)).getOrThrow()
                val binding = fixture.downloadAndWait(episode)
                fixture.app.changeSettings { it.copy(offlineOnly = true) }
                withTimeout(20_000L) { fixture.app.library.first { it.settings.offlineOnly } }
                val before = fixture.gatewayCounts
                val state = fixture.playSaved(PodcastAction.PlaySavedEpisode(episode.id, binding.value), binding)
                assertTrue(state.isPlaying)
                assertEquals(binding, state.currentSelection?.bindingId)
                assertEquals(dev.veyra.model.PlaybackControlsMode.PODCAST, state.currentSelection?.controlsMode)
                assertEquals(1, fixture.engine.loads.size)
                assertTrue(fixture.engine.loads.single().source.isLocal)
                assertEquals(before, fixture.gatewayCounts)
                assertEquals(1, fixture.occurrencesFor(binding))
                val generation = fixture.engine.loads.single().generation
                fixture.engine.sendPosition(generation, 45_000L, 300_000L)
                withTimeout(20_000L) { fixture.app.playback.state.first { it.positionMs == 45_000L } }
                fixture.app.playback.dispatch(PlaybackCommand.FlushProgress).playbackResultGetOrThrow()
                withTimeout(20_000L) {
                    while (fixture.app.store.acquisition.playbackView().catalogProgress[episode.id]?.positionMs != 45_000L) {
                        delay(20)
                    }
                }
                val savedCheckpoint = fixture.app.store.acquisition.playbackView().checkpoints.values
                    .single { it.bindingId == binding }
                assertEquals(45_000L, savedCheckpoint.positionMs)
                val firstLoads = fixture.engine.loads.size
                fixture.playSaved(PodcastAction.PlaySavedEpisode(episode.id, binding.value), binding)
                withTimeout(20_000L) { while (fixture.engine.loads.size <= firstLoads) delay(20) }
                assertEquals(45_000L, fixture.engine.loads.last().startPositionMs)
                assertEquals(1, fixture.occurrencesFor(binding))
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun coldRestartRequiresCurrentProcessValidationBeforeSavedPlayback() = runBlocking {
        withTimeout(180_000L) {
            val item = TypedDownloadPresentationSupport.youtubeItem("cold-saved", "Cold saved title")
            val directory = TypedDownloadPresentationSupport.workDirectory()
            val first = SavedPlaybackFixture(directory)
            val binding: ExecutionBindingId
            try {
                first.initialize()
                first.store.upsertCatalog(listOf(item)).getOrThrow()
                binding = first.downloadAndWait(item)
            } finally {
                first.close()
            }

            val validating = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val second = SavedPlaybackFixture(directory)
            second.platform.validateLocal = {
                validating.complete(Unit)
                release.await()
                AssetValidation.Valid(12_345L)
            }
            try {
                second.initialize()
                withTimeout(20_000L) { validating.await() }
                val cold = second.app.downloads.acquisitionPresentation.value
                assertNull(cold.verifiedDownload(binding))
                second.app.onMusicAction(MusicUiAction.PlaySaved(item, binding.value))
                val failure = withTimeout(20_000L) {
                    second.app.notice.first { it?.failure != null }?.failure
                }
                assertNotNull(failure)
                assertEquals(FailureCode.INVALID_STATE, failure.code)
                assertEquals("saved_recording_not_verified", failure.detail)
                assertEquals(0, second.engine.loads.size, "Unverified cold bytes must not reach native playback")

                release.complete(Unit)
                val ready = withTimeout(60_000L) {
                    second.app.downloads.acquisitionPresentation.first { it.verifiedDownload(binding) != null }
                }
                assertNotNull(ready)
                val state = second.playSaved(MusicUiAction.PlaySaved(item, binding.value), binding)
                assertTrue(state.isPlaying)
                assertEquals(binding, state.currentSelection?.bindingId)
                assertTrue(second.engine.loads.single().source.isLocal)
            } finally {
                release.complete(Unit)
                second.close()
            }
        }
    }

    @Test
    fun preAdmissionRevocationOfSavedAuthorityRejectsBeforeQueueOrNativeMutation() = runBlocking {
        withTimeout(180_000L) {
            val item = TypedDownloadPresentationSupport.youtubeItem("saved-revoked", "Saved revoked title")
            val fixture = SavedPlaybackFixture()
            try {
                fixture.initialize()
                fixture.store.upsertCatalog(listOf(item)).getOrThrow()
                val binding = fixture.downloadAndWait(item)
                fixture.playSaved(MusicUiAction.PlaySaved(item, binding.value), binding)
                fixture.app.playback.dispatch(PlaybackCommand.Stop).playbackResultGetOrThrow()
                val stale = fixture.store.acquisitionView()
                val queue = stale.acquisition.queues.getValue(QueueContext.MUSIC)
                val occurrence = queue.displayedOrder.mapNotNull(stale.acquisition.occurrences::get)
                    .single { it.bindingId == binding }
                val assetId = stale.acquisition.assetUses
                    .single { it.requestedBindingId == binding }.materializationAssetId
                val asset = stale.acquisition.materializations.getValue(assetId)
                val job = stale.acquisition.physicalDownloads.values.single { it.assetId == asset.id }
                val beforeQueue = stale.acquisition.queues
                val beforeOccurrences = stale.acquisition.occurrences
                val beforeLoads = fixture.engine.loads.size
                val beforeGateway = fixture.gatewayCounts
                // Simulate the stale UI read surviving until after the exact authority is revoked.
                fixture.app.downloads.remove(job.id)
                // AssetUse lineage is immutable; current authority is revoked when the exact job/
                // materialization no longer verifies for this binding.
                withTimeout(60_000L) {
                    fixture.app.downloads.acquisitionPresentation.first { it.verifiedDownload(binding) == null }
                }
                val failure = assertFailsWith<VeyraException> {
                    fixture.app.playback.dispatch(PlaybackCommand.ResumeOccurrence(QueueContext.MUSIC,
                        queue.editRevision, occurrence.id, binding, localOnly = true)).playbackResultGetOrThrow()
                }
                assertEquals(FailureCode.INVALID_STATE, failure.failure.code)
                assertEquals(beforeQueue, fixture.store.acquisitionView().acquisition.queues)
                assertEquals(beforeOccurrences, fixture.store.acquisitionView().acquisition.occurrences)
                assertEquals(beforeLoads, fixture.engine.loads.size)
                assertEquals(beforeGateway, fixture.gatewayCounts,
                    "A revoked saved intent must never prepare/query/transfer online")
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun existingOccurrenceRejectsMismatchedAcceptedBindingAndStaleQueueRevisionWithoutMutation() = runBlocking {
        withTimeout(180_000L) {
            val first = TypedDownloadPresentationSupport.youtubeItem("saved-first", "Saved first title")
            val second = TypedDownloadPresentationSupport.youtubeItem("saved-second", "Saved second title")
            val fixture = SavedPlaybackFixture()
            try {
                fixture.initialize()
                fixture.store.upsertCatalog(listOf(first, second)).getOrThrow()
                val firstBinding = fixture.downloadAndWait(first)
                val secondBinding = fixture.downloadAndWait(second)
                assertTrue(firstBinding != secondBinding)
                fixture.playSaved(MusicUiAction.PlaySaved(first, firstBinding.value), firstBinding)
                fixture.app.playback.dispatch(PlaybackCommand.Stop).playbackResultGetOrThrow()
                val before = fixture.store.acquisitionView()
                val queue = before.acquisition.queues.getValue(QueueContext.MUSIC)
                val occurrence = queue.displayedOrder.mapNotNull(before.acquisition.occurrences::get)
                    .single { it.bindingId == firstBinding }
                val beforeLoads = fixture.engine.loads.size
                val beforeGateway = fixture.gatewayCounts
                val mismatch = assertFailsWith<VeyraException> {
                    fixture.app.playback.dispatch(PlaybackCommand.ResumeOccurrence(QueueContext.MUSIC,
                        queue.editRevision, occurrence.id, secondBinding, localOnly = true))
                        .playbackResultGetOrThrow()
                }
                assertEquals(FailureCode.INVALID_STATE, mismatch.failure.code)
                val staleRevision = assertFailsWith<VeyraException> {
                    fixture.app.playback.dispatch(PlaybackCommand.ResumeOccurrence(QueueContext.MUSIC,
                        queue.editRevision - 1, occurrence.id, firstBinding, localOnly = true))
                        .playbackResultGetOrThrow()
                }
                assertEquals(FailureCode.INVALID_STATE, staleRevision.failure.code)
                assertEquals(before.acquisition.queues, fixture.store.acquisitionView().acquisition.queues)
                assertEquals(before.acquisition.occurrences, fixture.store.acquisitionView().acquisition.occurrences)
                assertEquals(beforeLoads, fixture.engine.loads.size)
                assertEquals(beforeGateway, fixture.gatewayCounts)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun mismatchedOrStaleSavedBindingIsRejectedWithoutQueueStreamOrNativeSideEffects() = runBlocking {
        withTimeout(120_000L) {
            val item = TypedDownloadPresentationSupport.youtubeItem("saved-mismatch", "Saved mismatch title")
            val fixture = SavedPlaybackFixture()
            try {
                fixture.initialize()
                fixture.store.upsertCatalog(listOf(item)).getOrThrow()
                val binding = fixture.downloadAndWait(item)
                val beforeQueue = fixture.app.store.acquisitionView().acquisition.queues
                val beforeOccurrences = fixture.app.store.acquisitionView().acquisition.occurrences
                val beforeLoads = fixture.engine.loads.size
                fixture.app.onMusicAction(MusicUiAction.PlaySaved(item, "wrong-binding"))
                val failure = withTimeout(20_000L) {
                    fixture.app.notice.first { it?.failure != null }?.failure
                }
                assertNotNull(failure)
                assertEquals(FailureCode.INVALID_STATE, failure.code)
                assertEquals(beforeQueue, fixture.app.store.acquisitionView().acquisition.queues)
                assertEquals(beforeOccurrences, fixture.app.store.acquisitionView().acquisition.occurrences)
                assertEquals(beforeLoads, fixture.engine.loads.size)
            } finally {
                fixture.close()
            }
        }
    }
}

private data class GatewayCounts(val firstResults: Int, val preparations: Int, val transfers: Int)

/** Same real owner composition as the PR14 support, with a typed-local-capable authored engine. */
private class SavedPlaybackFixture(
    directory: Path = TypedDownloadPresentationSupport.workDirectory(),
) {
    val platform = SavedPlaybackPlatform(directory)
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + Dispatchers.Default +
        CoroutineExceptionHandler { _, _ -> })
    val app = AppContainer(platform, scope, gateway = platform.gateway)
    val store get() = app.store
    val engine get() = platform.engine
    val gateway get() = platform.gateway

    suspend fun initialize() = app.initialize()

    suspend fun downloadAndWait(item: MediaItem): ExecutionBindingId {
        app.onMusicAction(MusicUiAction.Download(item))
        val completed = withTimeout(60_000L) {
            app.downloads.acquisitionPresentation.first { presentation ->
                presentation.view.acquisition.assetUses.any { use ->
                    val subject = presentation.view.acquisition.bindings[use.requestedBindingId]
                        ?.subjectId?.let(presentation.view.acquisition.subjects::get)
                    subject is ExecutableSubject.CatalogMedia && subject.mediaId == item.id &&
                        presentation.states.values.any {
                            it.status == DownloadStatus.COMPLETE && it.producer.bindingId == use.requestedBindingId
                        }
                }
            }
        }
        val binding = completed.view.acquisition.assetUses.first { use ->
            val subject = completed.view.acquisition.bindings[use.requestedBindingId]
                ?.subjectId?.let(completed.view.acquisition.subjects::get)
            subject is ExecutableSubject.CatalogMedia && subject.mediaId == item.id
        }.requestedBindingId
        withTimeout(60_000L) {
            app.downloads.acquisitionPresentation.first { it.verifiedDownload(binding) != null }
        }
        return binding
    }

    suspend fun playSaved(action: MusicUiAction, binding: ExecutionBindingId): PlaybackState {
        app.onMusicAction(action)
        return awaitSaved(binding)
    }

    suspend fun playSaved(action: PodcastAction, binding: ExecutionBindingId): PlaybackState {
        app.onPodcastAction(action)
        return awaitSaved(binding)
    }

    private suspend fun awaitSaved(binding: ExecutionBindingId): PlaybackState = withTimeout(20_000L) {
        app.playback.state.first { it.isPlaying && it.currentSelection?.bindingId == binding }
    }

    suspend fun occurrencesFor(binding: ExecutionBindingId): Int {
        val view = app.store.acquisitionView()
        return listOf(dev.veyra.model.QueueContext.MUSIC, dev.veyra.model.QueueContext.PODCAST).sumOf { context ->
            view.acquisition.queues.getValue(context).displayedOrder.count { id ->
                view.acquisition.occurrences[id]?.bindingId == binding
            }
        }
    }

    val gatewayCounts: GatewayCounts get() = GatewayCounts(
        gateway.firstResults.get(), gateway.preparations.get(), gateway.transfers.get())
    suspend fun close() {
        try { app.close() } finally { lifetime.cancelAndJoin() }
    }
}

internal class SavedPlaybackPlatform(directory: Path) : PlatformServices {
    override val dataDirectory = directory.toString()
    override val store = createPlatformLibraryStore(directory.resolve("library").toString())
    override val engine = SavedPlaybackEngine()
    override val validator = AssetValidator { AssetValidation.Valid(12_345L) }
    val localValidationCalls = AtomicInteger()
    @Volatile var validateLocal: suspend (LocalMediaValidationInput) -> AssetValidation =
        { AssetValidation.Valid(12_345L) }
    override val localValidator = LocalMediaValidator { input ->
        localValidationCalls.incrementAndGet()
        validateLocal(input)
    }
    val gateway = PresentationGateway()
    override val systemLanguage = LanguagePreference.ENGLISH
    override val youtube = YouTubeMediaSource(YouTubeClient(ResearchTransport { _: ResearchRequest ->
        error("No YouTube metadata request is authorized by this saved-play test")
    }, onClose = {}))
    override suspend fun openExternalUrl(url: String) =
        error("No external URL is authorized by this saved-play test")
}

internal class SavedPlaybackEngine : AudioEngine {
    data class Load(
        val source: AcquisitionPlaybackSource,
        val selection: PlaybackSelection,
        val startPositionMs: Long,
        val generation: Long,
    )
    override val capabilities = EngineCapabilities()
    private val channel = Channel<EngineEvent>(Channel.UNLIMITED)
    override val events: Flow<EngineEvent> = channel.receiveAsFlow()
    val loads = CopyOnWriteArrayList<Load>()
    private var activeGeneration = 0L
    private var active: Load? = null
    private var positionMs = 0L
    private var durationMs: Long? = null
    private var seekSequence = 0L

    override suspend fun prepare(source: ResolvedPlayable, startPositionMs: Long, generation: Long) {
        error("Saved playback never uses the legacy source path")
    }

    override suspend fun prepare(source: AcquisitionPlaybackSource, selection: PlaybackSelection,
        startPositionMs: Long, generation: Long) {
        NativePlaybackSource.Acquired(source, selection).validateSelection()
        val load = Load(source, selection, startPositionMs.coerceAtLeast(0), generation)
        active = load
        activeGeneration = generation
        positionMs = load.startPositionMs
        durationMs = source.durationMs
        loads += load
        channel.send(EngineEvent.Ready(generation, durationMs, source.seekableHint ?: true))
    }

    override suspend fun play(generation: Long) {
        if (generation != activeGeneration || active == null) return
        channel.send(EngineEvent.StateChanged(generation, EnginePhase.PLAYING, true, 1f))
        channel.send(EngineEvent.Position(generation, positionMs, durationMs, seekSequence))
    }

    fun sendPosition(generation: Long, positionMs: Long, durationMs: Long?) {
        channel.trySend(EngineEvent.Position(generation, positionMs, durationMs, seekSequence = 0))
    }

    override suspend fun pause(generation: Long) {
        if (generation != activeGeneration || active == null) return
        channel.send(EngineEvent.StateChanged(generation, EnginePhase.PAUSED, false, 1f))
    }

    override suspend fun seek(positionMs: Long, seekSequence: Long, generation: Long) {
        if (generation != activeGeneration || active == null) return
        this.positionMs = positionMs.coerceAtLeast(0)
        this.seekSequence = seekSequence
        channel.send(EngineEvent.SeekCompleted(generation, seekSequence, this.positionMs))
    }

    override suspend fun setRate(speed: Float, generation: Long) = Unit

    override suspend fun stop(generation: Long) {
        if (generation < activeGeneration) return
        active = null
        activeGeneration = generation
        positionMs = 0
        durationMs = null
    }

    override suspend fun awaitRetiredReaders(expectedGeneration: Long): NativeReaderDrainResult =
        NativeReaderDrainResult.Drained(expectedGeneration)

    override suspend fun close() {
        active = null
        channel.close()
    }
}
