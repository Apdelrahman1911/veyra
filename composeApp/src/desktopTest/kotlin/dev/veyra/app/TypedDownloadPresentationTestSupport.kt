package dev.veyra.app

import dev.kmpyt.YouTubeClient
import dev.kmpyt.extraction.ResearchRequest
import dev.kmpyt.extraction.ResearchTransport
import dev.veyra.acquisition.AcquisitionCaptureFactory
import dev.veyra.acquisition.AcquisitionAdmissionFactory
import dev.veyra.acquisition.AcquisitionCoordinator
import dev.veyra.acquisition.AcquisitionDownloadAdapter
import dev.veyra.acquisition.AcquisitionDownloadAssetCheck
import dev.veyra.acquisition.AcquisitionResolution
import dev.veyra.acquisition.DownloadBatchCoordinator
import dev.veyra.data.LibraryStore
import dev.veyra.data.createPlatformLibraryStore
import dev.veyra.downloads.DownloadAcquisitionPresentation
import dev.veyra.model.*
import dev.veyra.playback.*
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.execution.*
import dev.veyra.source.youtube.YouTubeMediaSource
import dev.veyra.source.youtube.YouTubeTransferProgress
import dev.veyra.source.youtube.YouTubeTransferResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

internal data class QueuedNeutralDownload(
    val jobId: String,
    val requestId: AcquisitionRequestId,
    val bindingId: ExecutionBindingId,
    val title: String,
)

/**
 * Purpose-specific real AppContainer/Room fixture for the typed download presentation test.
 * Authored source/native answers are explicit fakes over NON-AUDIO bytes; files/Room are real.
 */
internal class TypedDownloadPresentationSupport(val directory: Path) {
    val platform = PresentationPlatform(directory)
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
    val gateway = PresentationGateway()
    val app = AppContainer(platform, scope, gateway = gateway)
    val store: LibraryStore get() = platform.store

    suspend fun initialize() = app.initialize()

    /**
     * Drives the production capture/resolve/admit command facade over this fixture's ONE Room
     * owner and gateway. The test-only coordinator is not a product controller, source client,
     * Room owner or player; it supplies the neutral member request that no current screen action
     * can create by itself.
     */
    suspend fun enqueueNeutralDownload(title: String): QueuedNeutralDownload {
        val captures = AcquisitionCaptureFactory()
        val coordinator = AcquisitionCoordinator(store.acquisition, gateway, captures, SystemClock(), offline = { false })
        val show = PodcastShow("neutral-source-show", title, "https://example.org/authored-neutral-feed")
        val record = coordinator.capture(captures.show(show).metadata)
        val accepted = coordinator.resolve(record.request.id) as? AcquisitionResolution.Accepted
            ?: error("neutral request was not accepted")
        val single = accepted.record.outcome as? AcquisitionOutcome.Single
            ?: error("neutral request did not bind a single source recording")
        val jobId = app.downloads.enqueueAccepted(record.request.id, single.bindingId) {}
        return QueuedNeutralDownload(jobId, record.request.id, single.bindingId, title)
    }

    suspend fun close() {
        try { app.close() } finally { lifetime.cancelAndJoin() }
    }

    /**
     * Test-only canonical batch admission over the SAME Room owner and authored gateway. The
     * app's existing batch work runtime still selects/resolves/admits members and owns transfers.
     */
    private val testAcquisition = AcquisitionCoordinator(store.acquisition, gateway,
        AcquisitionCaptureFactory(), SystemClock(), offline = { false })
    private val testBatches = DownloadBatchCoordinator(store.acquisition, testAcquisition, SystemClock())
    private val testAdmissions = AcquisitionAdmissionFactory()

    suspend fun admitCatalogueBatch(item: MediaItem, count: Int = 1): List<DownloadBatch> {
        val request = AcquisitionCaptureFactory().media(item, store.state.value)
        return (0 until count).map {
            // The second admission may occur after the first batch advanced the library revision;
            // the exact same immutable request/capture is intentionally reused.
            testBatches.admit(testAdmissions.catalogLeavesPreservingCaptures(listOf(request),
                PlaybackControlsMode.MUSIC, store.state.value.revision))
        }
    }

    suspend fun awaitPresentation(predicate: (DownloadAcquisitionPresentation) -> Boolean): DownloadAcquisitionPresentation =
        withTimeout(40_000L) { app.downloads.acquisitionPresentation.first(predicate) }

    suspend fun failRetainedStandalone(jobId: String, bindingId: ExecutionBindingId,
        failure: AppFailure = AppFailure(FailureCode.CORRUPT_MEDIA, "authored_task_a_retained_negative")) {
        platform.validateLocal = { AssetValidation.Invalid(failure) }
        assertFailsWith<VeyraException> { app.downloads.validateStandaloneAssetUse(jobId, bindingId) }
        awaitPresentation { it.states[jobId]?.status == DownloadStatus.FAILED }
    }

    suspend fun failRetainedMember(batchId: DownloadBatchId, memberPosition: Int, intentRevision: Long,
        bindingId: ExecutionBindingId,
        failure: AppFailure = AppFailure(FailureCode.CORRUPT_MEDIA, "authored_task_a_retained_negative")) {
        val asset = assertNotNull(app.downloads.acquisitionPresentation.value.view.acquisition.physicalDownloads.values
            .singleOrNull { it.producer.bindingId == bindingId }?.assetId)
        platform.validateLocal = { AssetValidation.Invalid(failure) }
        val adapter = AcquisitionDownloadAdapter(store.acquisition, testAcquisition, app.downloads.acquisitionExecutor)
        val result = adapter.validateMemberAsset(batchId, memberPosition, intentRevision, asset)
        assertIs<AcquisitionDownloadAssetCheck.Unavailable>(result)
        awaitPresentation { it.states.values.any { job -> job.status == DownloadStatus.FAILED } }
    }

    suspend fun setBatchIntent(batchId: DownloadBatchId, expectedIntentRevision: Long,
        intent: DownloadBatchIntent, retainCompletedAssets: Boolean): DownloadBatch =
        testBatches.setIntent(batchId, expectedIntentRevision, intent, retainCompletedAssets)

    companion object {
        val NON_AUDIO = "Authored typed download presentation fixture. NOT AUDIO; native validation is deliberately fake."
            .encodeToByteArray()
        const val REPRESENTATION = "authored-typed-presentation-representation"
        const val FIRST = "AAAAAAAAAAA"

        fun youtubeItem(id: String = "typed-presentation-track",
            title: String = "Complete requested title — العنوان الكامل") = MediaItem(
            id, title, MediaKind.MUSIC,
            SourceRef(SourceProvider.YOUTUBE, FIRST, canonicalUrl = "https://www.youtube.com/watch?v=$FIRST"),
            durationMs = 999_999L,
            attribution = Attribution("Original publisher", "https://publisher.example.com/page",
                "Original metadata rights only"),
        )

        fun workDirectory(): Path {
            val root = System.getenv("VEYRA_PROJECT_ROOT")?.let(Path::of)
                ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                    .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            val parent = root.resolve(".work/typed-download-presentation-tests")
            Files.createDirectories(parent)
            return Files.createTempDirectory(parent, "room-")
        }
    }
}

internal class PresentationPlatform(directory: Path) : PlatformServices {
    override val dataDirectory = directory.toString()
    override val store: LibraryStore = createPlatformLibraryStore(directory.resolve("library").toString())
    override val engine = SilentPresentationEngine()
    override val validator = AssetValidator { AssetValidation.Valid(12_345L) }
    val localValidationCalls = AtomicInteger()
    @Volatile var validateLocal: suspend (LocalMediaValidationInput) -> AssetValidation =
        { AssetValidation.Valid(12_345L) }
    override val localValidator = LocalMediaValidator { input ->
        localValidationCalls.incrementAndGet()
        validateLocal(input)
    }
    override val systemLanguage = LanguagePreference.ENGLISH
    val youtubeRequests = AtomicInteger()
    override val youtube = YouTubeMediaSource(YouTubeClient(ResearchTransport { request: ResearchRequest ->
        youtubeRequests.incrementAndGet()
        error("No YouTube metadata request is authorized by this test")
    }, onClose = {}))

    override suspend fun openExternalUrl(url: String) {
        error("No external URL is authorized by this test")
    }
}

/** Never opens or claims real audio. The test does not invoke playback. */
internal class SilentPresentationEngine : AudioEngine {
    override val capabilities = EngineCapabilities()
    private val callbacks = Channel<EngineEvent>(Channel.UNLIMITED)
    override val events: Flow<EngineEvent> = callbacks.receiveAsFlow()
    override suspend fun prepare(source: ResolvedPlayable, startPositionMs: Long, generation: Long) {
        error("No legacy playback preparation is authorized")
    }
    override suspend fun prepare(source: AcquisitionPlaybackSource, selection: PlaybackSelection,
        startPositionMs: Long, generation: Long) {
        error("No typed playback preparation is authorized")
    }
    override suspend fun play(generation: Long) = Unit
    override suspend fun pause(generation: Long) = Unit
    override suspend fun seek(positionMs: Long, seekSequence: Long, generation: Long) = Unit
    override suspend fun setRate(speed: Float, generation: Long) = Unit
    override suspend fun stop(generation: Long) = Unit
    override suspend fun awaitRetiredReaders(expectedGeneration: Long): NativeReaderDrainResult =
        NativeReaderDrainResult.Drained(expectedGeneration)
    override suspend fun close() = Unit
}

/** Authored acquisition answers; the transfer writes NON_AUDIO bytes at the real owned path. */
internal class PresentationGateway : KmpMediaGateway, AcquisitionGateway, AcquisitionExecutionGateway {
    val firstResults = AtomicInteger()
    val preparations = AtomicInteger()
    val transfers = AtomicInteger()

    override suspend fun firstResult(completeTitle: String): KmpFirstResult? {
        firstResults.incrementAndGet()
        return KmpFirstResult(completeTitle, FirstResultKind.VIDEO, TypedDownloadPresentationSupport.FIRST,
            "Authored selected recording", "https://www.youtube.com/watch?v=${TypedDownloadPresentationSupport.FIRST}")
    }

    override suspend fun resolve(binding: PlaybackBinding, capabilities: EngineCapabilities,
        purpose: ResolvePurpose): ResolvedPlayable = error("No legacy playback resolution is authorized")

    override suspend fun download(binding: PlaybackBinding, destinationPath: String,
        capabilities: EngineCapabilities, representationId: String?,
        onProgress: suspend (YouTubeTransferProgress) -> Unit): YouTubeTransferResult =
        error("No legacy transfer is authorized")

    override suspend fun acquire(request: AcquisitionRequest): SourceAcquisitionResult {
        val entry = SourceEntry(AcquisitionEntryKind.VIDEO, TypedDownloadPresentationSupport.FIRST,
            "Authored selected recording", "https://www.youtube.com/watch?v=${TypedDownloadPresentationSupport.FIRST}",
            ordinal = 0)
        val receipt = FirstResultObservation(request.id, request.completeTitle, entry, entry.publicUrl)
        return SourceAcquisitionResult.Single(request.id, receipt,
            SourceDisplayItem(requireNotNull(entry.id), requireNotNull(entry.title), requireNotNull(entry.publicUrl),
                AcquisitionEntryKind.VIDEO))
    }

    override fun restore(request: AcquisitionRequest, receipt: FirstResultObservation): SourceAcquisitionResult {
        val entry = receipt.entry
        val id = entry.id
        val title = entry.title
        val publicUrl = entry.publicUrl
        return if (entry.kind == AcquisitionEntryKind.VIDEO && id != null && title != null &&
            publicUrl != null && receipt.requestId == request.id && receipt.queryTitle == request.completeTitle &&
            entry.ordinal == 0) SourceAcquisitionResult.Single(request.id, receipt,
            SourceDisplayItem(id, title, publicUrl, AcquisitionEntryKind.VIDEO))
        else SourceAcquisitionResult.Unplayable(request.id, receipt, AcquisitionProblem(AcquisitionProblemCode.INVALID_FIRST_RESULT))
    }

    override suspend fun collection(request: AcquisitionRequest, selection: SourceAcquisitionResult.Collection,
        limits: CollectionLimits): SourceCollectionPage =
        error("No collection traversal is authorized")

    override suspend fun more(continuation: CollectionContinuation): SourceCollectionPage =
        error("No collection traversal is authorized")

    override suspend fun inspect(request: AcquisitionRequest,
        selection: SourceAcquisitionResult.Single): AcquisitionInspection = object : AcquisitionInspection {
        override val selection = selection
    }

    override suspend fun preparePlayback(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
        requiredRepresentationId: String?, inspection: AcquisitionInspection?): AcquisitionPlaybackSource =
        error("No typed playback preparation is authorized")

    override suspend fun prepareDownload(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
        requiredRepresentationId: String?, inspection: AcquisitionInspection?): PreparedAcquisitionDownload {
        preparations.incrementAndGet()
        return Prepared(context, requiredRepresentationId)
    }

    override suspend fun download(prepared: PreparedAcquisitionDownload, destinationPath: String,
        onProgress: suspend (AcquisitionTransferProgress) -> Unit): AcquisitionTransferResult {
        transfers.incrementAndGet()
        val bytes = TypedDownloadPresentationSupport.NON_AUDIO
        Files.write(Path.of(destinationPath), bytes)
        onProgress(AcquisitionTransferProgress(bytes.size.toLong(), bytes.size.toLong()))
        return AcquisitionTransferResult(prepared.context, bytes.size.toLong(), prepared.representation)
    }

    private class Prepared(
        override val context: AcquisitionExecutionContext,
        override val requiredRepresentationId: String?,
    ) : PreparedAcquisitionDownload {
        override val representation = AcquisitionRepresentation(TypedDownloadPresentationSupport.REPRESENTATION,
            "audio/mpeg", "mp3", "mp3")
    }
}
