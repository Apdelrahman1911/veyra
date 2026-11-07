package dev.veyra.app

import dev.kmpyt.YouTubeClient
import dev.kmpyt.extraction.ResearchRequest
import dev.kmpyt.extraction.ResearchTransport
import dev.veyra.data.LibraryStore
import dev.veyra.data.createPlatformLibraryStore
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
import kotlinx.coroutines.flow.receiveAsFlow
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

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

    suspend fun close() {
        try { app.close() } finally { lifetime.cancelAndJoin() }
    }

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
