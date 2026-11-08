package dev.veyra.desktop.audio

import dev.veyra.data.AcquisitionRepositoryState
import dev.veyra.data.LibraryAcquisitionView
import dev.veyra.data.executionContext
import dev.veyra.model.AcquisitionKind
import dev.veyra.model.AcquisitionOutcome
import dev.veyra.model.AcquisitionRecord
import dev.veyra.model.AcquisitionRequest
import dev.veyra.model.AcquisitionRequestId
import dev.veyra.model.AcquisitionJobState
import dev.veyra.model.AppSnapshot
import dev.veyra.model.ExecutableSubject
import dev.veyra.model.ExecutableSubjectId
import dev.veyra.model.ExecutionBinding
import dev.veyra.model.ExecutionBindingId
import dev.veyra.model.ExecutionBindingOrigin
import dev.veyra.model.ExecutionOccurrence
import dev.veyra.model.ExecutionOccurrenceId
import dev.veyra.model.ExecutionQueue
import dev.veyra.model.FirstResultObservation
import dev.veyra.model.FirstResultReceipt
import dev.veyra.model.FirstResultReceiptId
import dev.veyra.model.MediaCapture
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.OccurrenceSelectionState
import dev.veyra.model.PlaybackControlsMode
import dev.veyra.model.PodcastShow
import dev.veyra.model.QueueContext
import dev.veyra.model.RepeatMode
import dev.veyra.model.ShowCapture
import dev.veyra.model.SourceEntry
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRecording
import dev.veyra.model.SourceRecordingId
import dev.veyra.model.SourceRef
import dev.veyra.model.StreamTransport
import dev.veyra.playback.CrossfadeMix
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.EngineEvent
import dev.veyra.playback.EnginePhase
import dev.veyra.playback.NativeReaderDrainResult
import dev.veyra.playback.PlaybackResource
import dev.veyra.playback.PlaybackSelection
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.source.execution.AcquisitionExecutionContext
import dev.veyra.source.execution.AcquisitionPlaybackSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Authored, inert desktop-native double. It answers only the explicit events/commands a test
 * supplies; it never decodes, fetches, opens libmpv or claims a real file descriptor release.
 * `destroyed` becomes true only after a successful `close()` return. `close()` deliberately
 * supports entered/release barriers so tests can hold native destruction deterministically.
 */
internal class AuthoredDesktopDeck(
    val name: String,
    var failPrepare: Boolean = false,
    var failPause: Boolean = false,
    var failClose: Boolean = false,
    var closeGate: CompletableDeferred<Unit>? = null,
    var readerDrainGate: CompletableDeferred<Unit>? = null,
    var readerDrain: ((Long) -> NativeReaderDrainResult)? = null,
    var eventsOnPrepare: (Long) -> List<EngineEvent> = { emptyList() },
) : DesktopAudioDeck {
    override val capabilities = EngineCapabilities()

    private val eventFlow = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override val events: Flow<EngineEvent> = eventFlow

    val prepareInputs = CopyOnWriteArrayList<DesktopNativeInput>()
    val prepareGenerations = CopyOnWriteArrayList<Long>()
    val playCalls = CopyOnWriteArrayList<Long>()
    val pauseCalls = CopyOnWriteArrayList<Long>()
    val seekCalls = CopyOnWriteArrayList<Pair<Long, Long>>()
    val rateCalls = CopyOnWriteArrayList<Float>()
    val stopCalls = CopyOnWriteArrayList<Long>()
    val gainCalls = CopyOnWriteArrayList<Float>()
    val outputCalls = CopyOnWriteArrayList<String>()
    val readerDrainCalls = CopyOnWriteArrayList<Long>()
    val closeCalls = AtomicInteger()
    val closeEntered = CompletableDeferred<Unit>()
    val pauseEntered = CompletableDeferred<Unit>()
    val readerDrainEntered = CompletableDeferred<Unit>()
    val closeContextActive = CopyOnWriteArrayList<Boolean>()

    @Volatile var destroyed = false
    @Volatile var snapshotState = MpvDeckSnapshot()

    override suspend fun prepare(source: DesktopNativeInput, startPositionMs: Long, generation: Long) {
        prepareInputs += source
        prepareGenerations += generation
        if (failPrepare) throw IllegalStateException("authored $name prepare failure")
        eventsOnPrepare(generation).forEach { eventFlow.emit(it) }
    }

    override suspend fun play(generation: Long) {
        playCalls += generation
    }

    override suspend fun pause(generation: Long) {
        pauseCalls += generation
        pauseEntered.complete(Unit)
        if (failPause) throw IllegalStateException("authored $name pause failure")
    }

    override suspend fun seek(positionMs: Long, seekSequence: Long, generation: Long) {
        seekCalls += positionMs to seekSequence
    }

    override suspend fun setRate(speed: Float, generation: Long) {
        rateCalls += speed
    }

    override suspend fun stop(generation: Long) {
        stopCalls += generation
    }

    override suspend fun setGain(gain: Float) {
        gainCalls += gain
    }

    override suspend fun selectOutput(deviceId: String) {
        outputCalls += deviceId
    }

    override suspend fun diagnostics(): Map<String, String?> = mapOf("fixture-deck" to name)

    override suspend fun snapshot(): MpvDeckSnapshot = snapshotState

    override suspend fun awaitRetiredReaders(expectedGeneration: Long): NativeReaderDrainResult {
        readerDrainCalls += expectedGeneration
        readerDrainEntered.complete(Unit)
        readerDrainGate?.await()
        return readerDrain?.invoke(expectedGeneration) ?: NativeReaderDrainResult.Drained(expectedGeneration)
    }

    override suspend fun close() {
        closeCalls.incrementAndGet()
        closeContextActive += currentCoroutineContext().isActive
        closeEntered.complete(Unit)
        closeGate?.await()
        if (failClose) throw IllegalStateException("authored $name native destroy failure")
        destroyed = true
    }

    fun release() {
        closeGate?.complete(Unit)
        readerDrainGate?.complete(Unit)
    }

    fun phase(phase: EnginePhase) {
        snapshotState = snapshotState.copy(phase = phase)
    }
}

/** One fake central-reader owner. It is not a fake DOWNLOAD resource and performs no I/O. */
internal class AuthoredPlaybackResource(
    val name: String,
    var failClose: Boolean = false,
    var closeGate: CompletableDeferred<Unit>? = null,
) : PlaybackResource {
    val closeCalls = AtomicInteger()
    val failedCloseCalls = AtomicInteger()
    val closeEntered = CompletableDeferred<Unit>()
    val closeSawNativeDestroy = CopyOnWriteArrayList<Boolean>()
    val closeContextActive = CopyOnWriteArrayList<Boolean>()
    @Volatile var closed = false
    var nativeDestroyObservation: (() -> Boolean)? = null

    override suspend fun close() {
        closeCalls.incrementAndGet()
        closeContextActive += currentCoroutineContext().isActive
        closeEntered.complete(Unit)
        closeGate?.await()
        nativeDestroyObservation?.let { closeSawNativeDestroy += it() }
        if (failClose) {
            failedCloseCalls.incrementAndGet()
            throw IllegalStateException("authored $name resource close failure")
        }
        closed = true
    }

    fun release() {
        closeGate?.complete(Unit)
    }
}

/** An authored typed graph joined only by the real pure projector. It is not Room provenance. */
internal data class AuthoredAcquiredSelection(
    val view: LibraryAcquisitionView,
    val occurrenceId: ExecutionOccurrenceId,
    val context: AcquisitionExecutionContext,
) {
    fun selection(): PlaybackSelection = PlaybackSelection.project(view, occurrenceId)

    fun source(
        uri: String = "https://media.example/authored-owned-source",
        resource: PlaybackResource? = null,
        representationId: String = "authored:representation",
    ) = AcquisitionPlaybackSource(
        context = context,
        uri = uri,
        transport = StreamTransport.PROGRESSIVE,
        mimeType = "audio/mp4",
        codecs = "mp4a.40.2",
        representationId = representationId,
        resource = resource,
    )

    fun native(resource: PlaybackResource? = null) =
        dev.veyra.playback.NativePlaybackSource.Acquired(source(resource = resource), selection())
}

internal object AuthoredAcquisition {
    /** Genuine captured catalogue identity; the native input still carries transport only. */
    fun catalogue(mode: PlaybackControlsMode = PlaybackControlsMode.MUSIC): AuthoredAcquiredSelection {
        val media = MediaItem(
            "catalog:authored-download-track",
            "Complete authored catalogue title — العنوان الكامل",
            MediaKind.MUSIC,
            SourceRef(SourceProvider.COMMONS, "authored-catalogue"),
        )
        val request = AcquisitionRequest(
            AcquisitionRequestId("authored-download-media-request"),
            media.title,
            MediaCapture(media),
            capturedEpochMs = 123_000,
            catalogRevision = 7,
        )
        return build(request, mode) { _, _, _ ->
            ExecutableSubject.CatalogMedia(ExecutableSubjectId(media.id), media.id)
        }
    }

    /** Neutral source recording: no catalogue MediaItem is invented for it. */
    fun neutralShow(mode: PlaybackControlsMode = PlaybackControlsMode.PODCAST): AuthoredAcquiredSelection {
        val show = PodcastShow(
            "rss:authored-neutral-show",
            "Complete authored neutral recording — حلقة محايدة",
            "https://podcast.example/authored-feed.xml",
            publisher = "Authored publisher",
            websiteUrl = "https://podcast.example/authored-show",
        )
        val request = AcquisitionRequest(
            AcquisitionRequestId("authored-download-show-request"),
            show.title,
            ShowCapture(show),
            capturedEpochMs = 123_000,
            catalogRevision = 7,
        )
        return build(request, mode) { request, receipt, recording ->
            assertEquals(AcquisitionKind.SHOW, request.kind)
            ExecutableSubject.SourceRecordingSubject(ExecutableSubjectId("authored:neutral:C"), recording.id)
        }
    }

    private fun build(
        request: AcquisitionRequest,
        mode: PlaybackControlsMode,
        subject: (AcquisitionRequest, FirstResultReceipt.Observed, SourceRecording) -> ExecutableSubject,
    ): AuthoredAcquiredSelection {
        val canonicalUrl = "https://www.youtube.com/watch?v=AUTHOREDC"
        val entry = SourceEntry(
            dev.veyra.model.AcquisitionEntryKind.VIDEO,
            "AUTHOREDC",
            "Actual authored first recording C — C",
            canonicalUrl,
            ordinal = 0,
        )
        val receipt = FirstResultReceipt.Observed(
            FirstResultReceiptId("authored:receipt:${request.id.value}"),
            FirstResultObservation(request.id, request.completeTitle, entry, canonicalUrl),
            observedEpochMs = 124_000,
        )
        val recording = SourceRecording(SourceRecordingId("authored:recording:C"), "AUTHOREDC", canonicalUrl)
        val executableSubject = subject(request, receipt, recording)
        val binding = ExecutionBinding(
            ExecutionBindingId("authored:binding:${request.id.value}"),
            executableSubject.id,
            receipt.id,
            recording.id,
            ExecutionBindingOrigin.TYPED,
        )
        val occurrence = ExecutionOccurrence(
            ExecutionOccurrenceId("authored:occurrence:${request.id.value}"),
            dev.veyra.model.AcquisitionOrigin.TYPED,
            request.id,
            null,
            null,
            executableSubject.id,
            binding.id,
            mode,
            OccurrenceSelectionState.BOUND,
            admittedEpochMs = 125_000,
        )
        val context = when (mode) {
            PlaybackControlsMode.MUSIC -> QueueContext.MUSIC
            PlaybackControlsMode.PODCAST -> QueueContext.PODCAST
        }
        val queue = ExecutionQueue(
            context, 8, listOf(occurrence.id), listOf(occurrence.id), occurrence.id, 0, 1f, false, RepeatMode.OFF,
        )
        val state = AcquisitionRepositoryState(
            libraryRevision = 41,
            requests = mapOf(request.id to AcquisitionRecord(
                request, AcquisitionJobState.COMMITTED,
                outcome = AcquisitionOutcome.Single(request.id, receipt.id, binding.id),
                updatedEpochMs = 124_000,
            )),
            receipts = mapOf(receipt.id to receipt),
            recordings = mapOf(recording.id to recording),
            subjects = mapOf(executableSubject.id to executableSubject),
            bindings = mapOf(binding.id to binding),
            occurrences = mapOf(occurrence.id to occurrence),
            queues = mapOf(context to queue),
        )
        val view = LibraryAcquisitionView(AppSnapshot(revision = state.libraryRevision), state)
        val selection = PlaybackSelection.project(view, occurrence.id)
        val expected = state.executionContext(binding.id)
        // The projection and the exact accepted join must agree before a native handoff is
        // considered authored-valid. This checks linkage, not historical source attestation.
        assertEquals(expected, selection.executionContext, "authored projector linkage drift")
        assertEquals(mode, selection.controlsMode)
        return AuthoredAcquiredSelection(view, occurrence.id, expected)
    }
}

internal fun authoredLegacySource(
    name: String,
    resource: PlaybackResource? = null,
    kind: MediaKind = MediaKind.MUSIC,
): ResolvedPlayable = ResolvedPlayable(
    mediaId = "authored:legacy:$name",
    uri = "https://media.example/authored-legacy-$name",
    transport = StreamTransport.PROGRESSIVE,
    mimeType = "audio/mp4",
    codecs = "mp4a.40.2",
    durationMs = 100_000,
    item = MediaItem(
        "authored:legacy:$name",
        "Authored legacy $name",
        kind,
        SourceRef(SourceProvider.COMMONS, "authored:legacy:$name"),
    ),
    resource = resource,
)

/**
 * Test harness around the REAL DesktopAudioEngine actor. Every fake deck/resource is tracked
 * so the finally block can release every explicit barrier before closing the real actor.
 */
internal class OwnershipEngine(
    val firstDeck: AuthoredDesktopDeck,
    override val coroutineContext: CoroutineContext,
) : CoroutineScope {
    private val replacements = ConcurrentLinkedQueue<AuthoredDesktopDeck>()
    private val decks = CopyOnWriteArrayList<AuthoredDesktopDeck>().apply { add(firstDeck) }
    private val resources = CopyOnWriteArrayList<AuthoredPlaybackResource>()

    val engine = DesktopAudioEngine.createForTest(firstDeck) {
        replacements.poll() ?: error("Authored fixture did not enqueue a replacement desktop deck")
    }

    fun deck(
        name: String,
        failPrepare: Boolean = false,
        failPause: Boolean = false,
        failClose: Boolean = false,
        closeGate: CompletableDeferred<Unit>? = null,
        readerDrainGate: CompletableDeferred<Unit>? = null,
        readerDrain: ((Long) -> NativeReaderDrainResult)? = null,
        eventsOnPrepare: (Long) -> List<EngineEvent> = { emptyList() },
    ): AuthoredDesktopDeck = AuthoredDesktopDeck(
        name, failPrepare, failPause, failClose, closeGate, readerDrainGate, readerDrain, eventsOnPrepare,
    ).also { decks += it }

    fun enqueueReplacement(vararg value: AuthoredDesktopDeck) {
        value.forEach(replacements::add)
    }

    fun resource(
        name: String,
        failClose: Boolean = false,
        closeGate: CompletableDeferred<Unit>? = null,
    ): AuthoredPlaybackResource = AuthoredPlaybackResource(name, failClose, closeGate).also { resources += it }

    fun legacy(name: String, resource: PlaybackResource? = null, kind: MediaKind = MediaKind.MUSIC) =
        authoredLegacySource(name, resource, kind)

    suspend fun finish() {
        decks.forEach(AuthoredDesktopDeck::release)
        resources.forEach(AuthoredPlaybackResource::release)
        runCatching { withContext(NonCancellable) { engine.close() } }
    }
}

internal fun ownershipTest(
    firstDeck: AuthoredDesktopDeck,
    block: suspend OwnershipEngine.() -> Unit,
): Unit = runBlocking {
    val harness = OwnershipEngine(firstDeck, this.coroutineContext)
    var primary: Throwable? = null
    try {
        withTimeout(30_000L) { harness.block() }
    } catch (error: Throwable) {
        primary = error
        throw error
    } finally {
        withContext(NonCancellable) {
            val teardown = runCatching { harness.finish() }.exceptionOrNull()
            if (teardown != null) {
                if (primary == null) throw teardown else primary.addSuppressed(teardown)
            }
        }
    }
}

/** Deterministic collection of real engine events; collection starts before the operation. */
internal class EngineEventLog(
    scope: kotlinx.coroutines.CoroutineScope,
    engine: DesktopAudioEngine,
) {
    private val channel = kotlinx.coroutines.channels.Channel<EngineEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    val all = CopyOnWriteArrayList<EngineEvent>()
    val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        engine.events.collect { event ->
            all += event
            channel.send(event)
        }
    }

    suspend fun next(predicate: (EngineEvent) -> Boolean): EngineEvent = kotlinx.coroutines.withTimeout(10_000L) {
        while (true) {
            val event = channel.receive()
            if (predicate(event)) return@withTimeout event
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    fun cancel() {
        job.cancel()
    }
}

/** Authored local file: real bytes, real size/hash validation, deliberately NOT audio media. */
internal class AuthoredValidationFile private constructor(
    val directory: Path,
    val path: Path,
    val bytes: ByteArray,
) {
    val size: Long get() = bytes.size.toLong()
    val sha256: String = digest(bytes)

    fun input(
        kind: dev.veyra.model.OfflineAssetKind = dev.veyra.model.OfflineAssetKind.FILE,
        sizeBytes: Long = size,
        sha: String? = sha256,
        uri: String = path.toUri().toString(),
        representationId: String? = "authored:validation:representation",
        generation: Long = 7,
    ) = dev.veyra.playback.LocalMediaValidationInput(
        uri, kind, sizeBytes, "audio/mp4", "mp4a.40.2", sha, representationId, generation,
    )

    fun close() {
        runCatching { Files.deleteIfExists(path) }
        runCatching { Files.deleteIfExists(directory) }
    }

    companion object {
        fun create(name: String = "authored-download-ownership"): AuthoredValidationFile {
            val root = projectRoot().resolve(".work/desktop-audio/download-ownership-tests")
            Files.createDirectories(root)
            val directory = Files.createTempDirectory(root, "$name-")
            val bytes = (
                "Veyra authored ownership fixture for $name.\n" +
                    "NOT AUDIO. The fake native deck supplies the authored Ready/Ended answer.\n" +
                    "Authoring date 2026-11-07.\n"
                ).encodeToByteArray()
            val path = directory.resolve("authored-non-audio.bin")
            Files.write(path, bytes)
            assertTrue(Files.isRegularFile(path) && Files.isReadable(path))
            return AuthoredValidationFile(directory, path, bytes)
        }

        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

internal fun projectRoot(): Path =
    System.getenv("VEYRA_PROJECT_ROOT")?.let(Path::of)
        ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }

internal fun musicMasterGain() = CrossfadeMix.MUSIC_GAIN
