package dev.veyra.playback

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.data.room.*
import dev.veyra.downloads.DownloadFiles
import dev.veyra.model.*
import dev.veyra.source.execution.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path

/** One authored gateway object implements selection AND execution; no HTTP/native work occurs. */
internal class PlaybackAcquisitionTestGateway(val selections: AcquisitionTestGateway = AcquisitionTestGateway()) :
    AcquisitionGateway by selections, AcquisitionExecutionGateway {
    data class Request(val context: AcquisitionExecutionContext, val requiredRepresentationId: String?)
    val preparations = mutableListOf<Request>()
    var prepare: suspend (AcquisitionExecutionContext, String?) -> AcquisitionPlaybackSource = { context, required -> source(context, required) }

    fun source(context: AcquisitionExecutionContext, required: String? = null, resource: PlaybackResource? = null) = AcquisitionPlaybackSource(
        context, resource?.localEndpoint?.uri ?: "https://media.example.invalid/authored-not-fetched",
        StreamTransport.PROGRESSIVE, "audio/mp4", "aac", required ?: "authored-aac", durationMs = 100_000,
        seekableHint = true, resource = resource,
    )

    override suspend fun preparePlayback(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
        requiredRepresentationId: String?, inspection: AcquisitionInspection?): AcquisitionPlaybackSource {
        preparations += Request(context, requiredRepresentationId)
        return prepare(context, requiredRepresentationId)
    }
    override suspend fun prepareDownload(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
        requiredRepresentationId: String?, inspection: AcquisitionInspection?): PreparedAcquisitionDownload =
        error("Playback fixture must not prepare downloads")
    override suspend fun download(prepared: PreparedAcquisitionDownload, destinationPath: String,
        onProgress: suspend (AcquisitionTransferProgress) -> Unit): AcquisitionTransferResult =
        error("Playback fixture must not transfer bytes")
}

/** Actual controller + canonical graph/store facade with deterministic source/native doubles. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AcquisitionPlaybackFixture(
    val testScope: TestScope,
    initial: AcquisitionGraph = commandGraph(),
    ownerScope: CoroutineScope = testScope,
) {
    val dispatcher = StandardTestDispatcher(testScope.testScheduler)
    val persistence = AcquisitionProjectionTestPersistence(initial)
    val store = LibraryStore(persistence, dispatcher)
    val engine = PlaybackTestEngine()
    val legacy = PlaybackTestResolver(store).apply {
        behavior = { _, _ -> error("Typed occurrence must not enter the legacy selection path") }
    }
    val clock = object : Clock {
        override fun epochMs(): Long = 123_000 + testScope.testScheduler.currentTime
        override fun monotonicMs(): Long = testScope.testScheduler.currentTime
    }
    private var requestSequence = initial.state().requests.keys.mapNotNull {
        it.value.removePrefix("authored-playback-").toIntOrNull()
    }.maxOrNull() ?: 0
    val captures = AcquisitionCaptureFactory(clock) { AcquisitionRequestId("authored-playback-${++requestSequence}") }
    val admissions = AcquisitionAdmissionFactory(captures, clock)
    val gateway = PlaybackAcquisitionTestGateway()
    val selection = AcquisitionCoordinator(store.acquisition, gateway, captures, clock, { store.state.value.settings.offlineOnly })
    val queues = QueueAcquisitionCoordinator(store.acquisition, selection)
    var validations = 0
    private val noFiles = object : ForwardingFileSystem(FileSystem.SYSTEM) {
        override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path =
            error("Authored controller tests may not perform filesystem I/O")
    }
    val files = DownloadFiles("/__veyra_authored_no_file_io__", noFiles)
    val resolver = AcquisitionPlaybackResolver(store, gateway, files, LocalMediaValidator {
        validations++
        error("Remote-only controller fixture must not invoke a local decoder")
    })
    val services = PlaybackAcquisitionServices(store.acquisition, selection, queues, resolver, captures, admissions)
    val controller = PlaybackController(store, engine, legacy, ownerScope, clock, dispatcher, acquisition = services)
    val state: PlaybackState get() = controller.state.value
    val durable: PlaybackAcquisitionView get() = persistence.graph.playbackView()
    val queue: ExecutionQueue get() = store.acquisition.state.value.queues.getValue(state.activeContext)

    suspend fun initialize() { controller.initialize().getOrThrow(); testScope.runCurrent() }
    suspend fun command(command: PlaybackCommand) { controller.dispatch(command).getOrThrow(); testScope.runCurrent() }
    suspend fun event(event: EngineEvent) { engine.emit(event); testScope.runCurrent() }
    suspend fun tick() { testScope.advanceTimeBy(250); testScope.runCurrent() }

    suspend fun neutral(mode: PlaybackControlsMode = PlaybackControlsMode.MUSIC, title: String = "Complete parent A — العنوان الكامل",
        operation: QueueAdmissionOperation = QueueAdmissionOperation.REPLACE, activate: Boolean = true): ExecutionOccurrenceId {
        store.initialize().getOrThrow()
        val metadata = if (mode == PlaybackControlsMode.PODCAST) ShowCapture(PodcastShow("authored-show", title, "https://example.invalid/feed"))
            else AlbumCapture(Album("authored-album", title))
        val request = captures.capture(metadata)
        val prepared = admissions.single(request, mode, null)
        val context = QueueContext.valueOf(mode.name)
        command(PlaybackCommand.AdmitAcquisition(prepared, context,
            store.acquisition.state.value.queues.getValue(context).editRevision, operation, activate))
        return prepared.occurrences.single().id
    }

    suspend fun catalog(items: List<MediaItem> = listOf(testTrack(), testTrack("second")), crossfade: Boolean = false) {
        store.update { it.copy(settings = it.settings.copy(crossfadeEnabled = crossfade)) }.getOrThrow()
        command(PlaybackCommand.Play(if (items.first().kind == MediaKind.EPISODE) QueueContext.PODCAST else QueueContext.MUSIC, items))
    }

    suspend fun close() { try { controller.close() } finally { store.close() } }
}
