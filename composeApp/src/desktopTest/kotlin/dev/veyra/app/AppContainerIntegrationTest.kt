package dev.veyra.app

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import dev.kmpyt.YouTubeClient
import dev.kmpyt.extraction.ResearchRequest
import dev.kmpyt.extraction.ResearchResponse
import dev.kmpyt.extraction.ResearchTransport
import dev.veyra.acquisition.AcquisitionCaptureFactory
import dev.veyra.acquisition.AcquisitionCoordinator
import dev.veyra.acquisition.AcquisitionResolution
import dev.veyra.data.AcquisitionPlaybackCommand
import dev.veyra.data.AdmitStandalonePhysicalDownload
import dev.veyra.data.AttachAcquisitionOccurrence
import dev.veyra.data.BeginPhysicalDownloadValidation
import dev.veyra.data.ClaimPhysicalDownloadAttempt
import dev.veyra.data.ExecutionQueueExpectation
import dev.veyra.data.FinishPhysicalDownloadAttempt
import dev.veyra.data.HeartbeatPhysicalDownload
import dev.veyra.data.LibraryStore
import dev.veyra.data.OccurrenceAttachmentExpectation
import dev.veyra.data.PhysicalDownloadCommit
import dev.veyra.data.PreparePhysicalDownloadFinalization
import dev.veyra.data.PublishPhysicalDownloadFinalization
import dev.veyra.data.QueueAcquisitionAdmission
import dev.veyra.data.QueueAdmissionOperation
import dev.veyra.data.SelectPhysicalDownloadRepresentation
import dev.veyra.data.StoreStatus
import dev.veyra.data.UpdateStandaloneDownloadIntent
import dev.veyra.data.createPlatformLibraryStore
import dev.veyra.data.executionContext
import dev.veyra.data.getOrThrow
import dev.veyra.downloads.DownloadExecutionGate
import dev.veyra.downloads.DownloadExecutionLease
import dev.veyra.downloads.DownloadIdleDecision
import dev.veyra.downloads.DownloadNetworkState
import dev.veyra.model.*
import dev.veyra.playback.*
import dev.veyra.source.execution.AcquisitionExecutionContext
import dev.veyra.source.execution.AcquisitionExecutionGateway
import dev.veyra.source.execution.AcquisitionGateway
import dev.veyra.source.execution.AcquisitionInspection
import dev.veyra.source.execution.AcquisitionPlaybackSource
import dev.veyra.source.execution.AcquisitionRepresentation
import dev.veyra.source.execution.AcquisitionTransferProgress
import dev.veyra.source.execution.AcquisitionTransferResult
import dev.veyra.source.execution.CollectionContinuation
import dev.veyra.source.execution.PreparedAcquisitionDownload
import dev.veyra.source.execution.SourceCollectionPage
import dev.veyra.source.BrowseKind
import dev.veyra.source.MediaCollection
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.execution.KmpFirstResult
import dev.veyra.source.execution.KmpMediaGateway
import dev.veyra.source.network.*
import dev.veyra.source.youtube.YouTubeKmpMediaGateway
import dev.veyra.source.youtube.YouTubeMediaSource
import dev.veyra.source.youtube.YouTubeTransferProgress
import dev.veyra.source.youtube.YouTubeTransferResult
import dev.veyra.ui.music.MusicUiAction
import dev.veyra.ui.music.SearchKind
import dev.veyra.ui.music.SearchScope
import dev.veyra.ui.podcasts.PodcastAction
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.decodeHex
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/**
 * Actual AppContainer/controller/resolver/download/artwork wiring and normalized Room/owned files.
 * Native playback/validation and media transfers are EXPLICIT FAKES, with authored NON-AUDIO bytes.
 * The default-gateway first-card case uses the actual kmpYT parser on authored HTML, not live data.
 * These tests prove composition/lifecycle/provenance, NOT real acquisition, decoding, process death,
 * network-isolated native playback, audible output, crossfade, or any physical-device release gate.
 */
class AppContainerIntegrationTest {
    private data class Preparation(val source: ResolvedPlayable, val positionMs: Long, val generation: Long)
    private data class Resolution(val binding: PlaybackBinding, val purpose: ResolvePurpose, val persisted: PlaybackBinding?)
    /**
     * One actual gateway transfer invocation. [completed] distinguishes a returned transfer from a
     * held/cancelled/failed invocation; the invocation itself must never disappear from counts.
     */
    private class Transfer(
        val binding: PlaybackBinding,
        val destination: String,
        val representationId: String?,
        val persisted: PlaybackBinding?,
        val progress: suspend (YouTubeTransferProgress) -> Unit,
    ) {
        @Volatile var completed = false
        @Volatile var failure: Throwable? = null
    }

    private class TestClock(private val initialEpochMs: Long = 1_000_000L) : Clock {
        private val now = AtomicLong(initialEpochMs)
        override fun epochMs(): Long = now.get()
        override fun monotonicMs(): Long = now.get() - initialEpochMs
        fun advance(milliseconds: Long) { require(milliseconds >= 0); now.addAndGet(milliseconds) }
    }

    private class Resource(private val onClose: suspend (Int) -> Unit = {}) : PlaybackResource {
        override val localEndpoint = EphemeralPlaybackEndpoint("http://127.0.0.1:34567/authored-owned-session/audio")
        val closeCalls = AtomicInteger()
        val closed = CompletableDeferred<Unit>()
        override suspend fun close() {
            onClose(closeCalls.incrementAndGet())
            closed.complete(Unit)
        }
    }

    /** Synthetic callbacks only: this engine never opens a URL/file or claims native decoding. */
    private class NativeProbe(private val store: LibraryStore) : AudioEngine {
        override val capabilities = EngineCapabilities()
        private val callbacks = Channel<EngineEvent>(Channel.UNLIMITED)
        override val events: Flow<EngineEvent> = callbacks.receiveAsFlow()
        private val lock = Mutex()
        private var generation = 0L
        private var owned: PlaybackResource? = null
        private var rate = 1f
        val calls = ConcurrentLinkedQueue<String>()
        val preparations = ConcurrentLinkedQueue<Preparation>()
        val prepared = Channel<Preparation>(Channel.UNLIMITED)
        val closeCalls = AtomicInteger()
        val seekRequests = ConcurrentLinkedQueue<Triple<Long, Long, Long>>()
        val seekStarted = Channel<Triple<Long, Long, Long>>(Channel.UNLIMITED)
        @Volatile var completeSeeks = true
        @Volatile var beforeStop: suspend (Long) -> Unit = {}

        override suspend fun prepare(source: ResolvedPlayable, startPositionMs: Long, generation: Long) = lock.withLock {
            calls += "prepare"
            if (generation < this.generation) { source.resource?.close(); return@withLock }
            owned?.close()
            owned = source.resource
            this.generation = generation
            val preparation = Preparation(source, startPositionMs, generation)
            preparations += preparation
            prepared.send(preparation)
            callbacks.send(EngineEvent.Ready(generation, source.durationMs, seekable = true))
        }
        override suspend fun prepare(source: AcquisitionPlaybackSource, selection: PlaybackSelection,
            startPositionMs: Long, generation: Long) = lock.withLock {
            calls += "prepare-typed"
            if (generation < this.generation) { source.resource?.close(); return@withLock }
            owned?.close()
            owned = source.resource
            this.generation = generation
            // Test-only observation projection of the typed identity. Production adapters keep
            // NativePlaybackSource discriminated and never manufacture catalogue IDs.
            val observed = ResolvedPlayable(
                mediaId = selection.catalogMedia?.id ?: selection.occurrenceId.value,
                uri = source.uri,
                transport = source.transport,
                mimeType = source.mimeType,
                codecs = source.codecs,
                headers = source.headers,
                expiresAtEpochMs = source.expiresAtEpochMs,
                durationMs = source.durationMs,
                seekableHint = source.seekableHint,
                isLocal = source.isLocal,
                representationId = source.representationId,
                item = selection.catalogMedia,
                executionBinding = observationBinding(selection),
                resource = source.resource,
            )
            val preparation = Preparation(observed, startPositionMs, generation)
            preparations += preparation
            prepared.send(preparation)
            callbacks.send(EngineEvent.Ready(generation, source.durationMs, seekable = true))
        }
        private fun observationBinding(selection: PlaybackSelection): PlaybackBinding? {
            val binding = selection.binding ?: return null
            val receipt = selection.receipt as? FirstResultReceipt.Observed ?: return null
            return PlaybackBinding(
                binding.id.value,
                selection.catalogMedia?.id ?: binding.subjectId.value,
                selection.request?.completeTitle ?: receipt.observation.queryTitle,
                FirstResultKind.VIDEO,
                receipt.observation.entry.id ?: binding.recordingId?.value.orEmpty(),
                receipt.observation.entry.title ?: "",
                receipt.observation.canonicalUrl,
                selection.executionContext?.recording?.canonicalId ?: receipt.observation.entry.id,
                resolvedEpochMs = receipt.observedEpochMs,
                selectionOrdinal = 0,
            )
        }

        override suspend fun awaitRetiredReaders(expectedGeneration: Long): NativeReaderDrainResult =
            NativeReaderDrainResult.Drained(expectedGeneration)
        override suspend fun play(generation: Long) = lock.withLock {
            calls += "play"
            if (generation == this.generation) callbacks.send(EngineEvent.StateChanged(generation, EnginePhase.PLAYING, true, rate))
        }
        override suspend fun pause(generation: Long) = lock.withLock {
            calls += "pause"
            if (generation == this.generation) callbacks.send(EngineEvent.StateChanged(generation, EnginePhase.PAUSED, false, rate))
        }
        override suspend fun seek(positionMs: Long, seekSequence: Long, generation: Long) = lock.withLock {
            calls += "seek"
            if (generation == this.generation) {
                val request = Triple(generation, seekSequence, positionMs)
                seekRequests += request
                seekStarted.send(request)
                if (completeSeeks) completeSeek(request)
            }
        }
        suspend fun completeSeek(request: Triple<Long, Long, Long>) {
            callbacks.send(EngineEvent.SeekCompleted(request.first, request.second, request.third))
        }
        suspend fun position(positionMs: Long, durationMs: Long = 12_345L) = lock.withLock {
            callbacks.send(EngineEvent.Position(generation, positionMs, durationMs))
        }
        override suspend fun setRate(speed: Float, generation: Long) = lock.withLock {
            calls += "setRate"
            if (generation == this.generation) rate = speed
        }
        override suspend fun stop(generation: Long) = lock.withLock {
            calls += "stop"
            if (generation >= this.generation) {
                beforeStop(generation)
                this.generation = generation
                val previous = owned
                owned = null
                previous?.close()
            }
        }
        override suspend fun close() {
            calls += "close"
            closeCalls.incrementAndGet()
            lock.withLock { val previous = owned; owned = null; previous?.close() }
            callbacks.close()
        }
    }

    private class HttpProbe : SafeHttp {
        val requests = ConcurrentLinkedQueue<HttpRequestData>()
        val dns = ConcurrentLinkedQueue<String>()
        val closeCalls = AtomicInteger()
        var reply: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
            error("This case did not authorize a metadata/image request")
        }
        private val engine = MockEngine { request -> requests += request; reply(request) }
        private val delegate = KtorSafeHttp(HttpClient(engine) { followRedirects = false; install(HttpTimeout) },
            PublicDnsResolver { host -> dns += host; listOf("93.184.216.34") })
        override suspend fun get(request: SafeHttpRequest, maxBytes: Int): SafeHttpBytes = delegate.get(request, maxBytes)
        override suspend fun <T> stream(request: SafeHttpRequest, consume: suspend (SafeHttpResponse, ByteReadChannel) -> T): T =
            delegate.stream(request, consume)
        override suspend fun validateUrl(url: String): String = delegate.validateUrl(url)
        override fun close() { closeCalls.incrementAndGet(); delegate.close() }
    }

    private class PlatformProbe(directory: Path) : PlatformServices {
        override val dataDirectory = directory.toString()
        override val store: LibraryStore = createPlatformLibraryStore(directory.resolve("library").toString())
        override val engine: NativeProbe = NativeProbe(store)
        val validations = ConcurrentLinkedQueue<OfflineAsset>()
        val localValidations = AtomicInteger()
        val localValidationInputs = ConcurrentLinkedQueue<LocalMediaValidationInput>()
        @Volatile var validateAsset: suspend (OfflineAsset) -> AssetValidation = { AssetValidation.Valid(12_345L) }
        override val validator = AssetValidator { asset -> validations += asset; validateAsset(asset) }
        @Volatile var validateLocal: suspend (LocalMediaValidationInput) -> AssetValidation = { AssetValidation.Valid(12_345L) }
        override val localValidator = LocalMediaValidator { input ->
            localValidations.incrementAndGet()
            localValidationInputs += input
            validateLocal(input)
        }
        override val systemLanguage = LanguagePreference.ENGLISH
        val youtubeRequests = ConcurrentLinkedQueue<ResearchRequest>()
        val youtubeCloseCalls = AtomicInteger()
        val runtimePrepareCalls = AtomicInteger()
        val runtimeCloseCalls = AtomicInteger()
        val externalUrls = ConcurrentLinkedQueue<String>()
        override val downloadNetworkState = MutableStateFlow(DownloadNetworkState.CONNECTED)
        val ensureCoverageCalls = AtomicInteger()
        val coverageAcknowledgements = AtomicInteger()
        val coverageAcknowledged = CompletableDeferred<Unit>()
        val downloadGateCalls = AtomicInteger()
        val gateCoverageAcknowledgements = ConcurrentLinkedQueue<Int>()
        @Volatile var ensureCoverage: suspend () -> Unit = {}
        @Volatile var acquireDownload: suspend (suspend () -> Unit) -> DownloadExecutionLease = { DownloadExecutionLease {} }
        @Volatile var beforeRuntimeClose: suspend () -> Unit = {}
        override suspend fun ensureDownloadScheduling() {
            ensureCoverageCalls.incrementAndGet()
            ensureCoverage()
            coverageAcknowledgements.incrementAndGet()
            coverageAcknowledged.complete(Unit)
        }
        override val downloadExecution = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
                downloadGateCalls.incrementAndGet()
                gateCoverageAcknowledgements += coverageAcknowledgements.get()
                return acquireDownload(onExpiration)
            }
        }
        var youtubeReply: suspend (ResearchRequest) -> ResearchResponse = { error("Unexpected YouTube metadata request") }
        override val youtube = YouTubeMediaSource(YouTubeClient(ResearchTransport { request ->
            youtubeRequests += request
            youtubeReply(request)
        }, onClose = { youtubeCloseCalls.incrementAndGet() }))
        override suspend fun prepareMediaRuntime() { runtimePrepareCalls.incrementAndGet() }
        override suspend fun closeMediaRuntime() { runtimeCloseCalls.incrementAndGet(); beforeRuntimeClose() }
        override suspend fun openExternalUrl(url: String) { externalUrls += url }
    }

    private class GatewayProbe(private val store: LibraryStore) :
        KmpMediaGateway, AcquisitionGateway, AcquisitionExecutionGateway {
        val queries = ConcurrentLinkedQueue<String>()
        val resolutions = ConcurrentLinkedQueue<Resolution>()
        val transfers = ConcurrentLinkedQueue<Transfer>()
        /** Actual typed transfer invocations (recorded at entry, before the fake source runs). */
        val typedTransfers = ConcurrentLinkedQueue<Pair<String, String>>()
        /** Typed transfer invocations that returned a successful result. */
        val typedCompletions = ConcurrentLinkedQueue<Pair<String, String>>()
        var onFirst: suspend (String) -> KmpFirstResult? = { title ->
            KmpFirstResult(title, FirstResultKind.VIDEO, FIRST, "Selected recording, not publisher metadata",
                "https://www.youtube.com/watch?v=$FIRST")
        }
        var onResolve: suspend (PlaybackBinding, EngineCapabilities, ResolvePurpose) -> ResolvedPlayable = { binding, _, _ -> plan(binding) }
        /**
         * Typed DOWNLOAD preparation reports representation metadata only. It is deliberately
         * a separate hook: the current prepared-download contract creates NO PlaybackResource
         * owner, so an authored resolve plan may never smuggle one in and have it dropped.
         */
        var onPrepareDownload: suspend (PlaybackBinding, EngineCapabilities) -> ResolvedPlayable = { binding, _ -> plan(binding) }
        var onDownload: suspend (Transfer) -> YouTubeTransferResult = { error("This case did not authorize a fake transfer") }
        override suspend fun firstResult(completeTitle: String): KmpFirstResult? {
            queries += completeTitle
            return onFirst(completeTitle)
        }
        override suspend fun resolve(binding: PlaybackBinding, capabilities: EngineCapabilities, purpose: ResolvePurpose): ResolvedPlayable {
            resolutions += Resolution(binding, purpose, store.state.value.playbackBindings[binding.id])
            return onResolve(binding, capabilities, purpose)
        }
        override suspend fun download(binding: PlaybackBinding, destinationPath: String, capabilities: EngineCapabilities,
            representationId: String?, onProgress: suspend (YouTubeTransferProgress) -> Unit): YouTubeTransferResult {
            val transfer = Transfer(binding, destinationPath, representationId, store.state.value.playbackBindings[binding.id], onProgress)
            transfers += transfer
            val result = try { onDownload(transfer) } catch (failure: Throwable) { transfer.failure = failure; throw failure }
            transfer.completed = true
            return result
        }

        @Volatile var beforeAcquire: suspend (AcquisitionRequest) -> Unit = { }

        override suspend fun acquire(request: AcquisitionRequest): SourceAcquisitionResult {
            beforeAcquire(request)
            queries += request.completeTitle
            val first = onFirst(request.completeTitle) ?: return SourceAcquisitionResult.Unplayable(request.id, null,
                AcquisitionProblem(AcquisitionProblemCode.NO_FIRST_RESULT))
            val entry = SourceEntry(
                kind = when (first.kind) {
                    FirstResultKind.VIDEO -> AcquisitionEntryKind.VIDEO
                    FirstResultKind.CHANNEL -> AcquisitionEntryKind.CHANNEL
                    FirstResultKind.PLAYLIST -> AcquisitionEntryKind.PLAYLIST
                    FirstResultKind.UNKNOWN -> null
                },
                id = first.id.takeIf { it.isNotBlank() }, title = first.title.takeIf { it.isNotBlank() },
                publicUrl = first.canonicalUrl, ordinal = 0,
            )
            val receipt = FirstResultObservation(request.id, request.completeTitle, entry, first.canonicalUrl)
            val id = entry.id
            val title = entry.title
            val publicUrl = entry.publicUrl
            return when {
                first.kind == FirstResultKind.VIDEO && first.failure == null && id != null && title != null &&
                    publicUrl != null -> SourceAcquisitionResult.Single(request.id, receipt,
                    SourceDisplayItem(id, title, publicUrl, AcquisitionEntryKind.VIDEO))
                first.failure?.code == FailureCode.UNSUPPORTED_SOURCE -> SourceAcquisitionResult.Unplayable(request.id, receipt,
                    AcquisitionProblem(AcquisitionProblemCode.FIRST_RESULT_UNAVAILABLE))
                first.kind == FirstResultKind.CHANNEL || first.kind == FirstResultKind.PLAYLIST ->
                    SourceAcquisitionResult.Unplayable(request.id, receipt,
                        AcquisitionProblem(AcquisitionProblemCode.FIRST_RESULT_UNSUPPORTED))
                else -> SourceAcquisitionResult.Unplayable(request.id, receipt,
                    AcquisitionProblem(AcquisitionProblemCode.INVALID_FIRST_RESULT))
            }
        }

        override fun restore(request: AcquisitionRequest, receipt: FirstResultObservation): SourceAcquisitionResult {
            val entry = receipt.entry
            if (receipt.requestId != request.id || receipt.queryTitle != request.completeTitle || entry.ordinal != 0) {
                throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "fixture_receipt_mismatch"))
            }
            val id = entry.id
            val title = entry.title
            val publicUrl = entry.publicUrl
            return if (entry.kind == AcquisitionEntryKind.VIDEO && entry.issue == null && id != null && title != null &&
                publicUrl != null) SourceAcquisitionResult.Single(request.id, receipt,
                SourceDisplayItem(id, title, publicUrl, AcquisitionEntryKind.VIDEO))
            else SourceAcquisitionResult.Unplayable(request.id, receipt,
                AcquisitionProblem(AcquisitionProblemCode.FIRST_RESULT_UNSUPPORTED))
        }

        override suspend fun collection(request: AcquisitionRequest, selection: SourceAcquisitionResult.Collection,
            limits: CollectionLimits): SourceCollectionPage =
            throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE, "fixture_collection_unsupported"))
        override suspend fun more(continuation: CollectionContinuation): SourceCollectionPage =
            throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE, "fixture_collection_unsupported"))
        override suspend fun inspect(request: AcquisitionRequest,
            selection: SourceAcquisitionResult.Single): AcquisitionInspection = object : AcquisitionInspection {
            override val selection = selection
        }

        /** Typed receipts are canonical; expose the same persisted evidence for legacy-style assertions. */
        private fun persistedReceipt(context: AcquisitionExecutionContext, binding: PlaybackBinding): PlaybackBinding? =
            store.state.value.playbackBindings[binding.id] ?: store.acquisitionViews.value.acquisition.bindings[ExecutionBindingId(binding.id)]
                ?.let { typed ->
                    val receipt = store.acquisitionViews.value.acquisition.receipts[typed.firstReceiptId] as? FirstResultReceipt.Observed
                        ?: return null
                    binding.copy(queryTitle = receipt.observation.queryTitle,
                        firstResultId = receipt.observation.entry.id ?: return null,
                        firstResultTitle = receipt.observation.entry.title ?: return null,
                        firstResultUrl = receipt.observation.canonicalUrl,
                        resolvedEpochMs = receipt.observedEpochMs)
                }

        override suspend fun preparePlayback(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
            requiredRepresentationId: String?, inspection: AcquisitionInspection?): AcquisitionPlaybackSource {
            val binding = fixtureBinding(context)
            val plan = onResolve(binding, capabilities, ResolvePurpose.PLAYBACK)
            resolutions += Resolution(binding, ResolvePurpose.PLAYBACK, persistedReceipt(context, binding))
            return AcquisitionPlaybackSource(context, plan.uri, plan.transport, plan.mimeType, plan.codecs,
                requiredRepresentationId ?: plan.representationId ?: "authored-aac", plan.headers,
                plan.expiresAtEpochMs, plan.durationMs, plan.seekableHint, plan.isLocal,
                materializationAssetId = null, resource = plan.resource)
        }

        override suspend fun prepareDownload(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
            requiredRepresentationId: String?, inspection: AcquisitionInspection?): PreparedAcquisitionDownload {
            val binding = fixtureBinding(context)
            val plan = onPrepareDownload(binding, capabilities)
            check(plan.resource == null) {
                "Typed download preparation has no PlaybackResource owner; the authored fake must not drop one"
            }
            resolutions += Resolution(binding, ResolvePurpose.DOWNLOAD, persistedReceipt(context, binding))
            val authored = plan.representationId
            // A required representation is a real capability fence, not a relabel of the authored
            // plan. If the fake plan does not provide it, preparation fails before any transfer.
            if (requiredRepresentationId != null && authored != null && authored != requiredRepresentationId) {
                throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "authored_download_representation_unavailable"))
            }
            val representation = AcquisitionRepresentation(requiredRepresentationId ?: authored ?: "authored-aac",
                plan.mimeType ?: "audio/mpeg", plan.codecs ?: "mp3", "mp3")
            return Prepared(this, context, requiredRepresentationId, representation, binding)
        }

        private class Prepared(
            private val issuer: GatewayProbe,
            override val context: AcquisitionExecutionContext,
            override val requiredRepresentationId: String?,
            override val representation: AcquisitionRepresentation,
            val binding: PlaybackBinding,
        ) : PreparedAcquisitionDownload {
            private val consumed = AtomicBoolean()
            /** Foreign/reused/mismatched capabilities fail before the source callback or any I/O. */
            fun consume(gateway: GatewayProbe) {
                if (issuer !== gateway) {
                    throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "prepared_download_foreign_issuer"))
                }
                if (!consumed.compareAndSet(false, true)) {
                    throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "prepared_download_reused"))
                }
                if (requiredRepresentationId != null && representation.representationId != requiredRepresentationId) {
                    throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "prepared_download_representation_mismatch"))
                }
                if (context.binding.id.value != binding.id) {
                    throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "prepared_download_context_mismatch"))
                }
            }
        }

        override suspend fun download(prepared: PreparedAcquisitionDownload, destinationPath: String,
            onProgress: suspend (AcquisitionTransferProgress) -> Unit): AcquisitionTransferResult {
            val owned = prepared as? Prepared ?: throw VeyraException(AppFailure(FailureCode.INVALID_STATE))
            owned.consume(this)
            val call = Transfer(owned.binding, destinationPath, owned.representation.representationId,
                persistedReceipt(owned.context, owned.binding)) { progress ->
                onProgress(AcquisitionTransferProgress(progress.bytesDownloaded, progress.totalBytes, progress.speedBytesPerSecond))
            }
            // The invocation boundary is counted before the fake source can block, cancel or fail.
            transfers += call
            typedTransfers += destinationPath to owned.representation.representationId
            val result = try { onDownload(call) } catch (failure: Throwable) { call.failure = failure; throw failure }
            call.completed = true
            typedCompletions += destinationPath to owned.representation.representationId
            return AcquisitionTransferResult(prepared.context, result.bytesWritten,
                AcquisitionRepresentation(result.representationId, result.mimeType, result.codecs, result.extension))
        }

        private fun fixtureBinding(context: AcquisitionExecutionContext): PlaybackBinding = PlaybackBinding(
            context.binding.id.value, context.subject.id.value, context.request.completeTitle,
            FirstResultKind.VIDEO, context.recording.canonicalId,
            context.receipt.observation.entry.title ?: "Authored first recording",
            context.recording.canonicalUrl, context.recording.canonicalId,
            resolvedEpochMs = context.receipt.observedEpochMs, selectionOrdinal = 0,
        )
    }

    private class Fixture(val directory: Path, productionGateway: Boolean, clock: Clock) {
        val uncaught = ConcurrentLinkedQueue<Throwable>()
        private val lifetime = SupervisorJob()
        private val scope = CoroutineScope(lifetime + Dispatchers.Default + CoroutineExceptionHandler { _, failure -> uncaught += failure })
        val platform = PlatformProbe(directory)
        val http = HttpProbe()
        val gateway = GatewayProbe(platform.store)
        val app = AppContainer(platform, scope, http,
            if (productionGateway) YouTubeKmpMediaGateway(platform.youtube) else gateway, clock)
        // Capture the actual composition-root job, not the fixture's unrelated parent lifetime.
        val appLifetime: Job = lifetime.children.single()
        val clockRef: Clock = clock
        val store get() = platform.store
        var expectedCloseFailure: AppFailure? = null
        suspend fun close() {
            try { app.close() }
            catch (failure: VeyraException) {
                val expected = expectedCloseFailure ?: throw failure
                assertEquals(expected, failure.failure, "Cleanup must preserve the already asserted sticky close failure")
            } finally { lifetime.cancelAndJoin() }
            assertTrue(uncaught.isEmpty(), "An app-lifetime coroutine failed: ${uncaught.map { it::class.simpleName }}")
        }
        fun assertNoNetwork() {
            assertTrue(http.requests.isEmpty(), "No metadata or artwork HTTP request was authorized")
            assertTrue(http.dns.isEmpty())
            assertTrue(platform.youtubeRequests.isEmpty())
            assertTrue(platform.externalUrls.isEmpty())
        }
        fun assertNoDownloadExecution() {
            assertEquals(0, platform.downloadGateCalls.get(), "Neither transfer nor maintenance may acquire native allowance")
            assertTrue(platform.gateCoverageAcknowledgements.isEmpty())
            assertTrue(platform.validations.isEmpty())
            assertEquals(0, platform.runtimePrepareCalls.get())
            assertTrue(gateway.queries.isEmpty())
            assertTrue(gateway.resolutions.isEmpty())
            assertTrue(gateway.transfers.isEmpty(), "No transfer may be invoked before a legal allowance")
            assertTrue(gateway.typedTransfers.isEmpty(), "No typed transfer may start before a legal allowance")
            assertTrue(gateway.typedCompletions.isEmpty())
            assertNoNetwork()
        }
        fun assertSchedulingOnly() {
            assertEquals(AppInitialization.Loading, app.initialization.value)
            assertFalse(app.playback.state.value.initialized)
            assertEquals(AppNavigation(), app.navigation.value)
            assertTrue(app.collections.value.isEmpty())
            assertTrue(platform.engine.calls.isEmpty(), "Scheduling bootstrap must not call the native engine")
            assertEquals(0, platform.ensureCoverageCalls.get())
            assertEquals(0, platform.coverageAcknowledgements.get())
            assertFalse(Files.exists(directory.resolve("media")))
            assertFalse(Files.exists(directory.resolve("artwork")))
            assertNoDownloadExecution()
        }
        suspend fun awaitDownloadIdle(): DownloadIdleDecision.Deferred = withTimeout(10_000L) {
            var decision = app.downloads.idleDecision()
            while (decision !is DownloadIdleDecision.Deferred) {
                delay(10)
                decision = app.downloads.idleDecision()
            }
            assertIs<DownloadIdleDecision.Deferred>(decision)
        }
        suspend fun play(item: MediaItem): Preparation {
            app.onMusicAction(MusicUiAction.Play(item))
            val preparation = platform.engine.prepared.receive()
            app.playback.state.first { it.currentItem?.id == item.id && it.isPlaying }
            return preparation
        }

        /**
         * Canonical occurrence-fenced replay of the restored queue position. A fresh Play(item)
         * would intentionally acquire a new occurrence; it is not the historical local asset's
         * exact binding and must not silently reuse another recording.
         */
        suspend fun resumeRestored(item: MediaItem): Preparation {
            assertEquals(item.id, app.playback.state.value.currentItem?.id)
            app.onMusicAction(MusicUiAction.TogglePlayPause)
            val preparation = try {
                withTimeout(15_000) { platform.engine.prepared.receive() }
            } catch (timeout: TimeoutCancellationException) {
                fail("restored playback did not prepare: phase=${app.playback.state.value.phase} " +
                    "failure=${app.playback.state.value.failure} notice=${app.notice.value?.failure} " +
                    "selection=${app.playback.state.value.currentSelection?.occurrence?.selectionState} " +
                    "binding=${app.playback.state.value.currentSelection?.bindingId}")
            }
            app.playback.state.first { it.currentItem?.id == item.id && it.isPlaying }
            return preparation
        }
        suspend fun assertDownloadCleanupRetainsDependencies(expectedEngineCloseCalls: Int = 1) {
            assertTrue(app.retainedForCleanup, "A failed close must keep the application graph process-owned")
            assertFalse(app.dependencyReleaseSafeAfterClose, "Absence of a successful positive ACK cannot release the host")
            assertTrue(app.downloads.hasPendingCleanupOwners)
            assertFalse(app.downloads.dependencyReleaseSafeAfterClose)
            assertTrue(appLifetime.isActive, "The app lifetime is a cleanup dependency, not merely the fixture parent")
            assertEquals(expectedEngineCloseCalls, platform.engine.closeCalls.get())
            assertEquals(0, platform.runtimeCloseCalls.get())
            assertEquals(0, platform.youtubeCloseCalls.get())
            assertEquals(0, http.closeCalls.get())
            assertIs<StoreStatus.Ready>(store.status.value)
            store.flush().getOrThrow() // Actual SQLite remains usable while a retained owner needs it.
        }
        fun assertDownloadCleanupDependenciesClosedOnce() {
            assertFalse(app.retainedForCleanup, "Consumed cleanup must release the process-lifetime pin")
            assertTrue(app.dependencyReleaseSafeAfterClose, "The positive receipt requires actual dependent-owner consumption")
            assertFalse(app.downloads.hasPendingCleanupOwners)
            assertTrue(app.downloads.dependencyReleaseSafeAfterClose)
            assertTrue(appLifetime.isCompleted)
            assertEquals(1, platform.engine.closeCalls.get())
            assertEquals(1, platform.runtimeCloseCalls.get())
            assertEquals(1, platform.youtubeCloseCalls.get())
            assertEquals(1, http.closeCalls.get())
            assertEquals(StoreStatus.Closed, store.status.value)
        }

        /** Detached historical fixture row prepared by actual production commands. */
        class Seeded(
            val media: MediaItem,
            val request: AcquisitionRequest,
            val bindingId: ExecutionBindingId,
            val context: AcquisitionExecutionContext,
        )

        private fun accept(value: PhysicalDownloadCommit<*>): Unit = when (value) {
            is PhysicalDownloadCommit.Committed -> Unit
            PhysicalDownloadCommit.Stale -> fail("Authored canonical command was rejected as stale")
        }

        /**
         * Authored source selection over the REAL canonical owner. The first-result answer,
         * transfer bytes and validator result are explicit fakes; capture/claim/commit/admission/
         * attempt/finalization/publication are the production Room commands with no snapshot seed.
         */
        suspend fun seedSelection(item: MediaItem, video: String = FIRST): Seeded {
            store.upsertCatalog(listOf(item)).getOrThrow()
            val catalog = store.acquisitionView().snapshot
            val current = catalog.media.getValue(item.id)
            val authored = GatewayProbe(store)
            authored.onFirst = { title -> KmpFirstResult(title, FirstResultKind.VIDEO, video,
                "Authored historical first recording", "https://www.youtube.com/watch?v=$video") }
            val coordinator = AcquisitionCoordinator(store.acquisition, authored,
                AcquisitionCaptureFactory(clockRef), clockRef, offline = { false })
            val captured = coordinator.capture(MediaCapture(current), catalog.revision)
            val accepted = assertIs<AcquisitionResolution.Accepted>(coordinator.resolve(captured.request.id))
            val single = assertIs<AcquisitionOutcome.Single>(accepted.record.outcome)
            val acquisition = store.acquisitionView().acquisition
            return Seeded(current, accepted.record.request, single.bindingId,
                acquisition.executionContext(single.bindingId))
        }

        /** Real queue admission + exact occurrence attachment; never a saved queue snapshot. */
        suspend fun seedQueue(seed: Seeded, mode: PlaybackControlsMode = PlaybackControlsMode.MUSIC,
            positionMs: Long = 0L, activate: Boolean = false): ExecutionOccurrence {
            val context = QueueContext.valueOf(mode.name)
            val queue = store.acquisitionView().acquisition.queues.getValue(context)
            val occurrence = ExecutionOccurrence(ExecutionOccurrenceId(newEntityId("occurrence")),
                AcquisitionOrigin.TYPED, seed.request.id, null, null, null, null, mode,
                OccurrenceSelectionState.PENDING, clockRef.epochMs())
            store.acquisition.admitQueue(QueueAcquisitionAdmission(context, queue.editRevision, null,
                QueueAdmissionOperation.APPEND, listOf(seed.request), null, listOf(occurrence), null))
            val pending = store.acquisitionView().acquisition.queues.getValue(context)
            assertTrue(store.acquisition.attachOccurrence(AttachAcquisitionOccurrence(
                OccurrenceAttachmentExpectation(occurrence.id, seed.request.id, context, pending.editRevision))))
            if (activate) {
                val current = store.acquisitionView().acquisition.queues.getValue(context)
                val bound = store.acquisitionView().acquisition.occurrences.getValue(occurrence.id)
                store.acquisition.applyPlayback(AcquisitionPlaybackCommand.Activate(
                    ExecutionQueueExpectation(context, current.editRevision, current.currentOccurrenceId),
                    occurrence.id, bound.bindingId, store.state.value.activeContext, 0L, current.speed,
                    false, clockRef.epochMs(), null))
            }
            val attached = store.acquisitionView().acquisition.queues.getValue(context)
            if (positionMs > 0L) {
                store.acquisition.applyPlayback(AcquisitionPlaybackCommand.EditQueue(
                    ExecutionQueueExpectation(context, attached.editRevision, attached.currentOccurrenceId),
                    attached.displayedOrder, attached.originalOrder, attached.currentOccurrenceId,
                    positionMs, attached.speed, attached.shuffle, attached.repeat, null))
            }
            return store.acquisitionView().acquisition.occurrences.getValue(occurrence.id)
        }

        suspend fun seedJob(seed: Seeded, now: Long, proposedId: String = newEntityId("download")): String {
            val admission = when (val result = store.acquisition.admitStandalonePhysicalDownload(
                AdmitStandalonePhysicalDownload(seed.request.id, seed.bindingId, proposedId,
                    DownloadClaimId(newEntityId("download-claim")), now))) {
                is PhysicalDownloadCommit.Committed -> result.value
                PhysicalDownloadCommit.Stale -> fail("Authored standalone admission was stale")
            }
            assertEquals(seed.context, admission.requestedContext)
            return admission.physical.job.id
        }

        suspend fun setIntent(id: String, intent: DownloadBatchIntent, retainCompletedAsset: Boolean, now: Long) {
            val join = assertNotNull(store.acquisition.physicalDownload(id))
            val claim = assertNotNull(join.claims.singleOrNull { it.owner is DownloadClaimOwner.Standalone })
            accept(store.acquisition.updateStandaloneDownloadIntent(UpdateStandaloneDownloadIntent(
                id, join.job.producer, join.job.ownershipGeneration, claim.id, claim.generation,
                claim.ownerIntentRevision, intent, retainCompletedAsset, now)))
        }

        private suspend fun claimAuthoredAttempt(seed: Seeded, id: String, now: Long): PhysicalDownloadFence {
            val join = assertNotNull(store.acquisition.physicalDownload(id))
            val attemptId = newEntityId("download-attempt")
            val staging = app.files.part(seed.media.id, attemptId).toString()
            val claimed = when (val result = store.acquisition.claimPhysicalDownloadAttempt(
                ClaimPhysicalDownloadAttempt(id, join.job.producer, join.job.ownershipGeneration,
                    attemptId, "authored-app-fixture", now, 60_000L, staging))) {
                is PhysicalDownloadCommit.Committed -> result.value
                PhysicalDownloadCommit.Stale -> fail("Authored attempt admission was stale")
            }
            Files.createDirectories(Path.of(staging).parent)
            Files.write(Path.of(staging), NON_AUDIO)
            return claimed.fence
        }

        /**
         * Historical attempt/lease state through the canonical finish command. Some fields are
         * authored because this is a retained-history seed, not a current transfer.
         */
        suspend fun seedAttemptJob(seed: Seeded, now: Long, proposedId: String,
            status: DownloadStatus = DownloadStatus.QUEUED,
            attemptStatus: DownloadAttemptStatus = DownloadAttemptStatus.PAUSED,
            failure: AppFailure? = null, nextRetryEpochMs: Long? = null, automaticRetryCount: Int = 0,
            intent: DownloadBatchIntent = DownloadBatchIntent.ACTIVE,
            retainCompletedAsset: Boolean = true): String {
            val id = seedJob(seed, now, proposedId)
            val fence = claimAuthoredAttempt(seed, id, now)
            accept(store.acquisition.finishPhysicalDownloadAttempt(FinishPhysicalDownloadAttempt(
                fence, status, attemptStatus, failure, nextRetryEpochMs, automaticRetryCount, now)))
            if (intent != DownloadBatchIntent.ACTIVE || !retainCompletedAsset) {
                setIntent(id, intent, retainCompletedAsset, now)
            }
            return id
        }

        /** Canonical publication of authored NON-AUDIO history; not a current-process validation. */
        suspend fun seedCompleteJob(seed: Seeded, now: Long,
            proposedId: String = newEntityId("download")): String {
            val id = seedJob(seed, now, proposedId)
            val fence = claimAuthoredAttempt(seed, id, now)
            val claimed = assertNotNull(store.acquisition.physicalDownload(id))
            val attemptId = requireNotNull(fence.attemptId)
            val staging = requireNotNull(claimed.job.stagingPath)
            val finalPath = app.files.finalPath(seed.media.id, 1, "audio/mpeg", attemptId).toString()
            accept(store.acquisition.selectPhysicalDownloadRepresentation(SelectPhysicalDownloadRepresentation(
                fence, REPRESENTATION, 1, null, null, now)))
            val bytes = NON_AUDIO
            accept(store.acquisition.heartbeatPhysicalDownload(HeartbeatPhysicalDownload(fence, now, 60_000L,
                bytes.size.toLong(), bytes.size.toLong())))
            accept(store.acquisition.beginPhysicalDownloadValidation(BeginPhysicalDownloadValidation(fence, now)))
            val digest = sha256Hex(bytes)
            val intent = PhysicalDownloadFinalization(id, fence.producer, staging, finalPath, digest,
                bytes.size.toLong(), attemptId, 12_345L, "audio/mpeg", "mp3", REPRESENTATION, 1,
                fence.ownershipGeneration, MaterializationAssetId(newEntityId("materialization")))
            accept(store.acquisition.preparePhysicalDownloadFinalization(
                PreparePhysicalDownloadFinalization(fence, intent, now)))
            Files.createDirectories(Path.of(finalPath).parent)
            Files.write(Path.of(finalPath), bytes)
            val asset = MaterializationAsset(requireNotNull(intent.assetId), fence.producer, finalPath,
                bytes.size.toLong(), mimeType = "audio/mpeg", codecs = "mp3", sha256 = digest,
                representationId = REPRESENTATION, validatorGeneration = 1, completedEpochMs = now,
                measuredDurationMs = 12_345L, materializerDownloadId = id, materializerAttemptId = attemptId,
                ownershipGeneration = fence.ownershipGeneration)
            accept(store.acquisition.publishPhysicalDownloadFinalization(
                PublishPhysicalDownloadFinalization(fence, intent, asset, now)))
            return id
        }

        suspend fun job(id: String): PhysicalDownloadJob =
            assertNotNull(store.acquisitionView().acquisition.physicalDownloads[id])

        suspend fun claim(id: String): DownloadClaim = assertNotNull(
            store.acquisitionView().acquisition.claims.values.singleOrNull { it.physicalJobId == id })

        suspend fun asset(id: String): MaterializationAsset? =
            job(id).assetId?.let { store.acquisitionView().acquisition.materializations[it] }

        /** Wait for the current-process validation projection, not a persisted Ready flag. */
        suspend fun awaitChecked(jobId: String, asset: MaterializationAsset) = withTimeout(15_000L) {
            app.downloads.acquisitionPresentation.first {
                it.successfulChecks[jobId]?.asset == asset
            }
        }

        fun deleteDirectory(path: Path) {
            if (!Files.exists(path)) return
            Files.walk(path).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private class Case(val directory: Path) {
        private val fixtures = mutableListOf<Fixture>()
        suspend fun newFixture(initialize: Boolean = true, productionGateway: Boolean = false, clock: Clock = SystemClock()): Fixture =
            Fixture(directory, productionGateway, clock).also { fixtures += it; if (initialize) it.app.initialize() }
        suspend fun close() {
            var primary: Throwable? = null
            for (fixture in fixtures.asReversed()) try { fixture.close() }
            catch (failure: Throwable) { if (primary == null) primary = failure else primary.addSuppressed(failure) }
            primary?.let { throw it }
        }
    }

    private fun checkCase(block: suspend Case.() -> Unit) = runBlocking {
        withTimeout(45_000L) {
            val case = Case(workDirectory())
            var primary: Throwable? = null
            try { case.block() } catch (failure: Throwable) { primary = failure; throw failure }
            finally {
                withContext(NonCancellable) {
                    try { case.close() }
                    catch (failure: Throwable) { primary?.addSuppressed(failure) ?: throw failure }
                }
            }
        }
    }

    @Test fun coldStartupUsesRoomWithoutImplicitDiscoveryRuntimePreparationOrAudio() = checkCase {
        val fixture = newFixture()
        fixture.app.initialize()
        assertEquals(AppInitialization.Ready, fixture.app.initialization.value)
        assertEquals(PlaybackPhase.IDLE, fixture.app.playback.state.value.phase)
        assertTrue(fixture.platform.engine.preparations.isEmpty())
        assertTrue(fixture.gateway.queries.isEmpty())
        assertEquals(0, fixture.platform.runtimePrepareCalls.get())
        fixture.assertNoNetwork()
        Files.newInputStream(directory.resolve("library/veyra.db")).use {
            assertEquals("SQLite format 3\u0000", it.readNBytes(16).decodeToString())
        }
    }

    @Test fun uiPlayPassesCompleteTitlesForRssCommonsAndAlreadyYoutubeItemsAndPersistsFirstReceipt() = checkCase {
        val fixture = newFixture()
        for (provider in listOf(SourceProvider.RSS, SourceProvider.COMMONS, SourceProvider.YOUTUBE)) {
            val original = item(provider, "full-$provider", "العنوان الكامل & complete title ".repeat(90) + " — final identifying suffix $provider")
            val preparation = fixture.play(original)
            val binding = assertNotNull(preparation.source.executionBinding)
            assertEquals(original, preparation.source.item)
            assertEquals(original.id, preparation.source.mediaId)
            assertEquals(original.title, binding.queryTitle)
            assertEquals(FIRST, binding.firstResultId)
            assertEquals(FIRST, binding.selectedVideoId)
            assertEquals(0, binding.selectionOrdinal)
            val canonical = fixture.store.acquisitionView().acquisition
            val typedBinding = assertNotNull(canonical.bindings[ExecutionBindingId(binding.id)])
            assertEquals(binding.id, typedBinding.id.value)
            val receipt = assertIs<FirstResultReceipt.Observed>(canonical.receipts.getValue(typedBinding.firstReceiptId))
            assertEquals(original.title, receipt.observation.queryTitle)
            assertEquals(FIRST, receipt.observation.entry.id)
            assertNotNull(canonical.recordings[assertNotNull(typedBinding.recordingId)])
            assertEquals(binding, fixture.gateway.resolutions.last().persisted, "Receipt must precede any acquisition")
            assertEquals(original, fixture.store.state.value.media[original.id])
            assertEquals(12_345L, preparation.source.durationMs)
            assertNotEquals(original.durationMs, preparation.source.durationMs)
        }
        assertEquals(3, fixture.gateway.queries.size)
        assertEquals(fixture.store.state.value.media.values.map { it.title }.toSet(), fixture.gateway.queries.toSet())
        assertTrue(fixture.gateway.transfers.isEmpty())
        assertTrue(fixture.platform.validations.isEmpty())
        fixture.assertNoNetwork()
    }

    @Test fun productionGatewayRejectsFirstChannelInsteadOfPlayingLaterVideoAndKeepsRawReceiptInRoom() = checkCase {
        val fixture = newFixture(productionGateway = true)
        val original = item(title = "Complete publisher title — العنوان الكامل")
        val channel = "UCabcdefghijklmnopqrstuv"
        fixture.platform.youtubeReply = { request -> ResearchResponse(request.url, 200,
            """<script>var ytInitialData={"contents":[{"channelRenderer":{"channelId":"$channel","title":{"simpleText":"First channel"}}},{"videoRenderer":{"videoId":"$FIRST","title":{"simpleText":"Later video must not play"}}}]};</script>""") }
        fixture.app.onMusicAction(MusicUiAction.Play(original))
        val state = fixture.app.playback.state.first { it.failure != null }
        // The typed selection stage durably commits an UNPLAYABLE occurrence; the controller's
        // truthful admission code is RECOVERY_REQUIRED, not the removed ambient resolver's
        // UNSUPPORTED_SOURCE. The raw ordinal-zero receipt is the durable evidence either way.
        assertEquals(FailureCode.RECOVERY_REQUIRED, state.failure?.code)
        assertEquals("acquisition_selection_unplayable", state.failure?.detail)
        val view = fixture.store.acquisitionView()
        val accepted = view.acquisition.requests.values.single { it.state == AcquisitionJobState.COMMITTED }
        assertEquals(original.title, accepted.request.completeTitle)
        assertEquals(original.title, (accepted.request.metadata as MediaCapture).media.title)
        val outcome = assertIs<AcquisitionOutcome.Unplayable>(accepted.outcome)
        assertEquals(AcquisitionProblemCode.FIRST_RESULT_UNSUPPORTED, outcome.problem.code)
        val receipt = assertIs<FirstResultReceipt.Observed>(
            view.acquisition.receipts.getValue(assertNotNull(outcome.receiptId)))
        assertEquals(original.title, receipt.observation.queryTitle)
        assertEquals(AcquisitionEntryKind.CHANNEL, receipt.observation.entry.kind)
        assertEquals(channel, receipt.observation.entry.id)
        assertEquals("First channel", receipt.observation.entry.title)
        assertEquals(0, receipt.observation.entry.ordinal)
        assertNull(receipt.observation.entry.issue)
        assertEquals("https://www.youtube.com/channel/$channel", receipt.observation.canonicalUrl)
        assertTrue(view.acquisition.bindings.isEmpty(), "A non-playable first card cannot mint an execution binding")
        assertTrue(view.acquisition.recordings.isEmpty(), "The later video must not become a recording")
        assertTrue(view.acquisition.occurrences.values.all { it.selectionState == OccurrenceSelectionState.UNPLAYABLE })
        assertTrue(view.snapshot.playbackBindings.isEmpty(), "No legacy compatibility binding may be invented")
        assertEquals(original.title, Url(fixture.platform.youtubeRequests.single().url).parameters["search_query"])
        assertTrue(fixture.platform.engine.preparations.isEmpty())
        assertEquals(0, fixture.platform.runtimePrepareCalls.get(), "A first non-playable receipt cannot start extraction")
        assertTrue(fixture.http.requests.isEmpty())
    }

    @Test fun cancellationAtOnlineScopeReturnDisposesUnhandedGatewayResourceExactlyOnce() = checkCase {
        val fixture = newFixture()
        val item = item()
        val resource = Resource()
        val preparationEntered = CompletableDeferred<Unit>()
        fixture.gateway.onResolve = { binding, _, purpose ->
            assertEquals(ResolvePurpose.PLAYBACK, purpose)
            // Deterministic prompt-cancellation boundary: the typed gateway returns a native
            // source but the enclosing policy/online scope cannot hand it to the resolver.
            preparationEntered.complete(Unit)
            currentCoroutineContext().job.cancel(CancellationException("authored_scope_return_cancellation"))
            plan(binding).copy(uri = resource.localEndpoint.uri, resource = resource)
        }
        fixture.app.onMusicAction(MusicUiAction.Play(item))
        preparationEntered.await()
        resource.closed.await()
        assertEquals(1, resource.closeCalls.get())
        assertTrue(fixture.platform.engine.preparations.isEmpty(), "The native engine never accepted this handoff")
        val view = fixture.store.acquisitionView()
        val accepted = view.acquisition.requests.values.single { it.state == AcquisitionJobState.COMMITTED }
        val single = assertIs<AcquisitionOutcome.Single>(accepted.outcome)
        assertEquals(item.title, accepted.request.completeTitle)
        assertEquals(1, view.acquisition.bindings.size, "Exactly the accepted execution binding is committed")
        assertEquals(1, fixture.gateway.resolutions.count { it.purpose == ResolvePurpose.PLAYBACK })
        assertEquals(single.bindingId.value, fixture.gateway.resolutions.single { it.purpose == ResolvePurpose.PLAYBACK }.binding.id)
        assertTrue(view.snapshot.playbackBindings.isEmpty(), "Typed execution does not fabricate a legacy binding row")
        fixture.app.close()
        assertEquals(1, resource.closeCalls.get(), "A successfully rejected resource must not be closed twice")
        fixture.assertNoNetwork()
    }

    @Test fun rejectedGatewayResourceIsDrainedAtFinalCloseButEarlierReleaseFailureStaysSanitizedAndSticky() = checkCase {
        val fixture = newFixture()
        val cleanupActive = ConcurrentLinkedQueue<Boolean>()
        val firstReleaseAttempted = CompletableDeferred<Unit>()
        val resource = Resource { attempt ->
            cleanupActive += currentCoroutineContext().isActive
            if (attempt == 1) {
                firstReleaseAttempted.complete(Unit)
                throw IllegalStateException("authored private https://media.example/close?sig=never-report-this")
            }
        }
        fixture.gateway.onResolve = { binding, _, purpose ->
            assertEquals(ResolvePurpose.PLAYBACK, purpose)
            currentCoroutineContext().job.cancel(CancellationException("authored_scope_return_cancellation"))
            plan(binding).copy(uri = resource.localEndpoint.uri, resource = resource)
        }
        fixture.app.onMusicAction(MusicUiAction.Play(item()))
        firstReleaseAttempted.await()
        assertEquals(1, resource.closeCalls.get())
        assertFalse(resource.closed.isCompleted, "A failed close must not count as released ownership")
        assertTrue(fixture.platform.engine.preparations.isEmpty(), "The native engine never accepted this handoff")

        val first = assertFailsWith<VeyraException> { fixture.app.close() }
        fixture.expectedCloseFailure = first.failure
        assertEquals(AppFailure(FailureCode.UNEXPECTED), first.failure)
        assertNull(first.cause)
        assertFalse(first.toString().contains("never-report-this"))
        resource.closed.await()
        assertEquals(2, resource.closeCalls.get(), "The app must retain and retry the rejected resource before releasing dependencies")
        assertEquals(listOf(true, true), cleanupActive.toList(), "Cancellation must not abort resource disposal")
        assertEquals(first.failure, assertFailsWith<VeyraException> { fixture.app.close() }.failure)
        assertEquals(2, resource.closeCalls.get(), "A successfully drained resource must not be closed again")
        assertEquals(1, fixture.platform.runtimeCloseCalls.get())
        assertEquals(1, fixture.platform.youtubeCloseCalls.get())
        assertEquals(1, fixture.platform.engine.closeCalls.get())
        assertEquals(1, fixture.http.closeCalls.get())
        assertEquals(StoreStatus.Closed, fixture.store.status.value)
        fixture.assertNoNetwork()
    }

    @Test fun persistentlyFailedRejectedResourceRemainsRetriableAfterFinalCloseWithoutRepeatingGlobalClosures() = checkCase {
        val fixture = newFixture()
        val allowRelease = AtomicBoolean()
        val firstReleaseAttempted = CompletableDeferred<Unit>()
        val resource = Resource {
            if (!allowRelease.get()) {
                firstReleaseAttempted.complete(Unit)
                throw IllegalStateException("authored private resource handle cannot close")
            }
        }
        fixture.gateway.onResolve = { binding, _, purpose ->
            assertEquals(ResolvePurpose.PLAYBACK, purpose)
            currentCoroutineContext().job.cancel(CancellationException("authored_scope_return_cancellation"))
            plan(binding).copy(uri = resource.localEndpoint.uri, resource = resource)
        }
        fixture.app.onMusicAction(MusicUiAction.Play(item()))
        firstReleaseAttempted.await()
        assertEquals(1, resource.closeCalls.get())
        val first = assertFailsWith<VeyraException> { fixture.app.close() }
        fixture.expectedCloseFailure = first.failure
        assertEquals(AppFailure(FailureCode.UNEXPECTED), first.failure)
        assertEquals(2, resource.closeCalls.get())
        assertFalse(resource.closed.isCompleted)
        assertEquals(first.failure, assertFailsWith<VeyraException> { fixture.app.close() }.failure)
        assertEquals(3, resource.closeCalls.get(), "Still-retained resource is retried, not forgotten")
        assertFalse(resource.closed.isCompleted)

        allowRelease.set(true)
        val recoveredDrain = assertFailsWith<VeyraException> { fixture.app.close() }
        assertEquals(first.failure, recoveredDrain.failure, "A later release cannot retroactively certify a clean shutdown")
        assertNull(recoveredDrain.cause)
        resource.closed.await()
        assertEquals(4, resource.closeCalls.get())
        assertEquals(first.failure, assertFailsWith<VeyraException> { fixture.app.close() }.failure)
        assertEquals(4, resource.closeCalls.get())
        assertEquals(1, fixture.platform.runtimeCloseCalls.get())
        assertEquals(1, fixture.platform.youtubeCloseCalls.get())
        assertEquals(1, fixture.platform.engine.closeCalls.get())
        assertEquals(1, fixture.http.closeCalls.get())
        assertEquals(StoreStatus.Closed, fixture.store.status.value)
        fixture.assertNoNetwork()
    }

    /**
     * App-owned typed resolver lifetime. The former external caller used the legacy
     * `AppContainer.resolver`, which cannot acquire a schema-3 binding (legacy snapshot binding
     * writes are intentionally rejected), so that entrypoint is no longer the app execution path.
     * The controller-owned resolve below is the current legal external/UI route.
     */
    @Test fun finalCloseCancelsAppOwnedTypedResolveAndAwaitsItsLateRejectedResourceBeforeClosingDependencies() = checkCase {
        val fixture = newFixture()
        val resolving = CompletableDeferred<Unit>()
        val resolveCancelled = CompletableDeferred<Unit>()
        val releasing = CompletableDeferred<Unit>()
        val permitRelease = CompletableDeferred<Unit>()
        val resource = Resource { attempt ->
            if (attempt == 1) {
                releasing.complete(Unit)
                permitRelease.await()
                throw IllegalStateException("authored late resource release failed once")
            }
        }
        fixture.gateway.onResolve = { binding, _, purpose ->
            assertEquals(ResolvePurpose.PLAYBACK, purpose)
            resolving.complete(Unit)
            try { awaitCancellation() }
            catch (_: CancellationException) {
                resolveCancelled.complete(Unit)
                plan(binding).copy(uri = resource.localEndpoint.uri, resource = resource)
            }
        }
        // The controller's resolver job is app-owned, but the policy operation registers the
        // SAME admitted-acquisition completion that shutdown awaits before dependent release.
        fixture.app.onMusicAction(MusicUiAction.Play(item()))
        resolving.await()
        coroutineScope {
            val closing = async { runCatching { fixture.app.close() } }
            try {
                releasing.await()
                assertTrue(resolveCancelled.isCompleted, "Final close must cancel the app-owned resolver")
                assertFalse(closing.isCompleted)
                assertFalse(resource.closed.isCompleted)
                assertEquals(0, fixture.platform.runtimeCloseCalls.get())
                assertEquals(0, fixture.platform.youtubeCloseCalls.get())
                assertEquals(0, fixture.http.closeCalls.get())
                assertNotEquals(StoreStatus.Closed, fixture.store.status.value)
                assertTrue(fixture.platform.engine.preparations.isEmpty())
            } finally { permitRelease.complete(Unit) }
            val failure = assertIs<VeyraException>(closing.await().exceptionOrNull())
            fixture.expectedCloseFailure = failure.failure
            assertEquals(AppFailure(FailureCode.UNEXPECTED), failure.failure)
            assertNull(failure.cause)
        }
        resource.closed.await()
        assertEquals(2, resource.closeCalls.get())
        assertEquals(fixture.expectedCloseFailure, assertFailsWith<VeyraException> { fixture.app.close() }.failure)
        assertEquals(2, resource.closeCalls.get())
        assertEquals(1, fixture.platform.runtimeCloseCalls.get())
        assertEquals(1, fixture.platform.youtubeCloseCalls.get())
        assertEquals(1, fixture.platform.engine.closeCalls.get())
        assertEquals(1, fixture.http.closeCalls.get())
        assertEquals(StoreStatus.Closed, fixture.store.status.value)
        fixture.assertNoNetwork()
    }

    @Test fun offlineToggleCancelsRunningGatewayCoroutineAndClosesLateResultBeforeNativeHandoff() = checkCase {
        val fixture = newFixture()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val resource = Resource()
        fixture.gateway.onResolve = { binding, _, purpose ->
            assertEquals(ResolvePurpose.PLAYBACK, purpose)
            entered.complete(Unit)
            try { awaitCancellation() }
            catch (_: CancellationException) {
                cancelled.complete(Unit)
                // A transport can finish a native resource concurrently with cancellation.
                // Returning it must not leak it across the policy/online scope barrier.
                plan(binding).copy(uri = resource.localEndpoint.uri, resource = resource)
            }
        }
        fixture.app.onMusicAction(MusicUiAction.Play(item()))
        entered.await()
        fixture.app.changeSettings { it.copy(offlineOnly = true) }
        fixture.store.state.first { it.settings.offlineOnly }
        cancelled.await()
        resource.closed.await()
        assertEquals(1, resource.closeCalls.get())
        fixture.app.playback.state.first { it.failure?.code == FailureCode.OFFLINE_POLICY }
        assertTrue(fixture.platform.engine.preparations.isEmpty())
        val view = fixture.store.acquisitionView()
        assertEquals(1, view.acquisition.requests.values.count { it.state == AcquisitionJobState.COMMITTED })
        assertEquals(1, view.acquisition.bindings.size)
        assertEquals(1, fixture.gateway.resolutions.count { it.purpose == ResolvePurpose.PLAYBACK })
        assertEquals(1, fixture.gateway.queries.size)
        fixture.assertNoNetwork()
    }

    @Test fun ownedLoopbackRemainsRemoteAndOfflineToggleStopsNativeOwnerWithoutDuplicateDisposal() = checkCase {
        val fixture = newFixture()
        val resource = Resource()
        fixture.gateway.onResolve = { binding, _, _ -> plan(binding).copy(uri = resource.localEndpoint.uri, resource = resource) }
        val preparation = fixture.play(item())
        assertSame(resource, preparation.source.resource)
        assertFalse(preparation.source.isLocal)
        assertNull(preparation.source.assetId)
        assertEquals(0, resource.closeCalls.get())
        fixture.app.changeSettings { it.copy(offlineOnly = true) }
        fixture.app.playback.state.first { !it.isPlaying && it.failure?.code == FailureCode.OFFLINE_POLICY }
        resource.closed.await()
        assertTrue(fixture.store.state.value.downloads.isEmpty())
        fixture.app.close()
        assertEquals(1, resource.closeCalls.get())
        assertEquals(1, fixture.platform.engine.closeCalls.get())
        assertEquals(1, fixture.gateway.queries.size)
        fixture.assertNoNetwork()
    }

    @Test fun uiPlayThenDownloadKeepsSameReceiptAndOwnedBytesSurviveOfflineRoomCloseReopen() = checkCase {
        val fixture = newFixture()
        val original = item(title = "Publisher's complete episode title — عنوان الحلقة")
        fixture.gateway.onDownload = { transfer ->
            Files.write(Path.of(transfer.destination), NON_AUDIO)
            transfer.progress(YouTubeTransferProgress(NON_AUDIO.size.toLong(), NON_AUDIO.size.toLong()))
            YouTubeTransferResult(NON_AUDIO.size.toLong(), "audio/mpeg", "mp3", "mp3", REPRESENTATION)
        }
        val preparation = fixture.play(original)
        val binding = assertNotNull(preparation.source.executionBinding)
        fixture.app.onPodcastAction(PodcastAction.Download(original.id))
        val view = fixture.store.acquisitionViews.first { view ->
            view.acquisition.physicalDownloads.values.any { it.status == DownloadStatus.COMPLETE && it.assetId != null }
        }
        val saved = view.snapshot
        val job = view.acquisition.physicalDownloads.values.single { it.status == DownloadStatus.COMPLETE }
        val asset = view.acquisition.materializations.getValue(assertNotNull(job.assetId))
        assertEquals(binding.id, job.producer.bindingId?.value)
        assertEquals(binding.id, asset.producer.bindingId?.value)
        assertEquals(original.id, (job.producer as PhysicalDownloadProducer.Typed).subjectId.value)
        assertEquals(binding, fixture.gateway.transfers.single().binding)
        assertEquals(binding, fixture.gateway.transfers.single().persisted)
        assertTrue(fixture.gateway.transfers.single().completed, "The already-transferred source must have returned")
        assertEquals(1, fixture.gateway.typedCompletions.size)
        assertEquals(listOf(original.title), fixture.gateway.queries.toList(), "Download must not fresh-search after Play")
        assertEquals(listOf(ResolvePurpose.PLAYBACK, ResolvePurpose.DOWNLOAD), fixture.gateway.resolutions.map { it.purpose })
        assertContentEquals(NON_AUDIO, Files.readAllBytes(Path.of(asset.uri)))
        assertTrue(Path.of(asset.uri).startsWith(directory.resolve("media")))
        assertEquals(MessageDigest.getInstance("SHA-256").digest(NON_AUDIO).joinToString("") { "%02x".format(it) }, asset.sha256)
        assertEquals(original, saved.media[original.id])
        fixture.app.changeSettings { it.copy(offlineOnly = true) }
        fixture.store.state.first { it.settings.offlineOnly }
        fixture.app.close()
        fixture.assertNoNetwork()

        // A new app/Room/native-fake lifetime in the SAME process, not process-death evidence.
        val reopened = newFixture()
        assertEquals(PlaybackPhase.PAUSED, reopened.app.playback.state.value.phase)
        assertTrue(reopened.platform.engine.preparations.isEmpty(), "Restore must not autoplay")
        // A cold process must finish its real session/maintenance recovery before local playback;
        // wait for the coordinator's actual idle ACK instead of racing a transient CHECK/validation.
        reopened.awaitDownloadIdle()
        val restoredState = withTimeout(20_000) {
            var current = reopened.store.acquisitionViews.value.acquisition
            while (current.physicalDownloads.values.none { it.id == job.id && it.status == DownloadStatus.COMPLETE &&
                    it.maintenanceKind == null && it.leaseOwner == null }) {
                delay(10)
                current = reopened.store.acquisitionViews.value.acquisition
            }
            current
        }
        val restoredSelection = assertNotNull(reopened.app.playback.state.value.currentSelection)
        val restoredBinding = assertNotNull(restoredSelection.bindingId)
        val restoredAsset = assertNotNull(restoredState.assetUses.singleOrNull {
            it.requestedBindingId == restoredBinding
        }?.let { restoredState.materializations[it.materializationAssetId] })
        val restoredJob = assertNotNull(restoredState.physicalDownloads.values.singleOrNull { it.assetId == restoredAsset.id })
        assertTrue(restoredState.claims.values.any { it.requestedBindingId == restoredBinding },
            "The completed physical claim must retain the exact requested binding; else local replay is not admissible")
        assertEquals(restoredAsset.producer, restoredJob.producer)
        assertEquals(DownloadStatus.COMPLETE, restoredJob.status)
        val restoredContext = assertNotNull(restoredSelection.executionContext)
        val restoredUse = assertNotNull(restoredState.assetUses.singleOrNull {
            it.requestedBindingId == restoredBinding && it.materializationAssetId == restoredAsset.id
        })
        val restoredReceipt = assertNotNull(restoredState.materializationReceipts[restoredAsset.id])
        val restoredClaim = assertNotNull(restoredState.claims.values.singleOrNull {
            it.physicalJobId == restoredJob.id && it.requestedBindingId == restoredBinding
        })
        assertEquals(1, restoredUse.compatibilityPolicyVersion)
        assertEquals(restoredContext.subject.id, restoredAsset.producer.subjectId)
        assertEquals(restoredContext.recording.id, restoredAsset.producer.recordingId)
        assertEquals(restoredAsset.producer, restoredReceipt.producer)
        assertEquals(restoredAsset.receipt(), restoredReceipt)
        assertEquals(OfflineAssetKind.FILE, restoredAsset.kind)
        assertFalse(restoredAsset.representationId.isNullOrBlank())
        assertEquals(restoredAsset.sha256, restoredReceipt.sha256)
        assertEquals(restoredAsset.representationId, restoredJob.representationId)
        assertEquals(restoredAsset.validatorGeneration, restoredJob.validatorGeneration)
        assertFalse(restoredJob.cleanupPending)
        assertNull(restoredJob.maintenanceKind)
        assertNull(restoredJob.leaseOwner)
        assertFalse(restoredJob.id in restoredState.physicalFinalizations)
        assertTrue(restoredClaim.intent != DownloadBatchIntent.CANCELLED || restoredClaim.retainCompletedAsset)
        val offline = reopened.resumeRestored(original)
        assertTrue(offline.source.isLocal)
        assertEquals(asset.uri, offline.source.uri)
        assertEquals(binding, offline.source.executionBinding)
        assertEquals(original, offline.source.item)
        assertTrue(reopened.gateway.queries.isEmpty())
        assertTrue(reopened.gateway.resolutions.isEmpty())
        assertTrue(reopened.gateway.transfers.isEmpty())
        assertTrue(reopened.platform.localValidations.get() > 0,
            "Owned hash and fake native contract must both be checked through the typed local validator")
        reopened.assertNoNetwork()
    }

    @Test fun podcastStartOverIsAtomicWithDelayedSeekAndRetainsTheOfflineRecording() = checkCase {
        val fixture = newFixture(initialize = false)
        val original = item(title = "Complete restart episode — إعادة الحلقة من بدايتها")
        val selection = fixture.seedSelection(original)
        fixture.seedQueue(selection, PlaybackControlsMode.PODCAST, activate = true)
        val jobId = fixture.seedCompleteJob(selection, fixture.clockRef.epochMs(), "podcast-restart-recording")
        val recording = assertNotNull(fixture.asset(jobId))
        fixture.app.initialize()
        fixture.awaitChecked(jobId, recording)
        assertEquals(PlaybackPhase.PAUSED, fixture.app.playback.state.value.phase)
        val offline = fixture.resumeRestored(original)
        assertTrue(offline.source.isLocal)
        assertEquals(recording.uri, offline.source.uri)
        assertEquals(selection.bindingId.value, offline.source.executionBinding?.id)
        fixture.platform.engine.position(4_500)
        fixture.app.playback.state.first { it.positionMs == 4_500L }
        fixture.app.playback.dispatch(PlaybackCommand.FlushProgress).getOrThrow()

        // A genuinely offline restart must not depend on a fresh source lookup. The verified owned
        // materialization stays playable while the policy forbids network resolution.
        fixture.app.changeSettings { it.copy(offlineOnly = true) }
        fixture.store.state.first { it.settings.offlineOnly }
        assertTrue(fixture.app.playback.state.value.isPlaying, "The owned local recording remains usable offline")
        val currentOccurrence = assertNotNull(fixture.app.playback.state.value.currentSelection?.occurrenceId)
        assertEquals(selection.bindingId, fixture.app.playback.state.value.currentSelection?.bindingId)

        fixture.platform.engine.completeSeeks = false
        fixture.app.playback.dispatch(PlaybackCommand.Seek(6_000)).getOrThrow()
        fixture.app.playback.state.first { it.pendingSeekMs == 6_000L }
        // The controller publishes seek intent before the native operation is admitted.
        // Await the actual fake-engine call, rather than relying on dispatcher timing.
        val pending = fixture.platform.engine.seekStarted.receive()
        assertEquals(offline.generation, pending.first)
        assertEquals(6_000L, pending.third)
        val queries = fixture.gateway.queries.toList()

        // Exercise the real UI-action adapter, controller, transactions and local resolver.
        // Native samples and authored NON-AUDIO validation remain deliberate fakes.
        fixture.app.onPodcastAction(PodcastAction.StartOver(original.id))
        val restarted = try {
            withTimeout(15_000) { fixture.platform.engine.prepared.receive() }
        } catch (timeout: TimeoutCancellationException) {
            fail("Start Over did not reuse the exact offline recording: phase=${fixture.app.playback.state.value.phase} " +
                "failure=${fixture.app.playback.state.value.failure} " +
                "selection=${fixture.app.playback.state.value.currentSelection?.occurrence?.selectionState} " +
                "binding=${fixture.app.playback.state.value.currentSelection?.bindingId} " +
                "queries=${fixture.gateway.queries} media=${fixture.store.acquisitionView().acquisition.requests.size}")
        }
        fixture.app.playback.state.first { it.generation == restarted.generation && it.isPlaying }
        assertTrue(restarted.generation > offline.generation)
        assertEquals(0L, restarted.positionMs)
        assertTrue(restarted.source.isLocal)
        assertEquals(recording.uri, restarted.source.uri)
        assertEquals(selection.bindingId.value, restarted.source.executionBinding?.id)
        assertEquals(currentOccurrence, fixture.app.playback.state.value.currentSelection?.occurrenceId,
            "Start Over must reactivate the exact current occurrence, not admit another one")
        assertEquals(selection.bindingId, fixture.app.playback.state.value.currentSelection?.bindingId)
        val canonical = fixture.store.acquisitionView().acquisition
        assertEquals(1, canonical.requests.size, "Offline restart must reuse the accepted request")
        assertEquals(1, canonical.bindings.size, "Offline restart must reuse the exact binding")
        assertEquals(0L, canonical.queues.getValue(QueueContext.PODCAST).positionMs)
        assertEquals(1, fixture.platform.engine.seekRequests.size, "Start Over must not seek the outgoing generation then lose that seek")
        // A typed checkpoint is a native observation, not a requested position. Supply the
        // restarted generation's first real sample at zero; the distinct duration is an
        // observable barrier proving the sample was processed before the durable flush.
        fixture.platform.engine.position(0L, durationMs = 12_346L)
        fixture.app.playback.state.first { it.positionMs == 0L && it.durationMs == 12_346L }
        fixture.app.playback.dispatch(PlaybackCommand.FlushProgress).getOrThrow()
        // Typed progress is the occurrence checkpoint/queue position, not the legacy
        // snapshot.progress compatibility map. Read it through the same-owner barrier.
        assertEquals(0L, fixture.store.acquisitionView().acquisition.queues.getValue(QueueContext.PODCAST).positionMs)
        val retainedJob = fixture.job(jobId)
        val retained = assertNotNull(fixture.asset(jobId))
        assertEquals(DownloadStatus.COMPLETE, retainedJob.status)
        assertEquals(recording, retained)
        assertEquals(selection.bindingId, retainedJob.producer.bindingId)
        assertContentEquals(NON_AUDIO, Files.readAllBytes(Path.of(retained.uri)))
        assertEquals(queries, fixture.gateway.queries.toList(), "Offline restart must not perform a new search")
        assertTrue(fixture.gateway.resolutions.isEmpty(), "Local restart must not resolve a remote plan")
        assertTrue(fixture.gateway.transfers.isEmpty(), "Local restart must not transfer or inspect a source")
        assertTrue(fixture.gateway.typedTransfers.isEmpty())

        fixture.platform.engine.completeSeek(pending)
        // Both samples traverse the same fake event channel. Position itself may be
        // conflated by the controller; the awaited FlushProgress below also fences the
        // earlier critical seek event before the final persisted-position assertion.
        fixture.platform.engine.position(1_000)
        fixture.app.playback.state.first { it.positionMs == 1_000L && it.pendingSeekMs == null }
        fixture.app.playback.dispatch(PlaybackCommand.FlushProgress).getOrThrow()
        assertEquals(1_000L, fixture.app.playback.state.value.positionMs)
        assertEquals(1_000L, fixture.store.acquisitionView().acquisition.queues.getValue(QueueContext.PODCAST).positionMs)
        fixture.app.close()
        val reopened = newFixture(clock = fixture.clockRef)
        assertEquals(1_000L, reopened.store.acquisitionView().acquisition.queues.getValue(QueueContext.PODCAST).positionMs)
        assertEquals(1_000L, reopened.app.playback.state.value.positionMs)
        assertEquals(PlaybackPhase.PAUSED, reopened.app.playback.state.value.phase)
        assertTrue(reopened.platform.engine.preparations.isEmpty())
        reopened.assertNoNetwork()
        fixture.assertNoNetwork()
    }

    /**
     * Control for the narrow Start Over repair: a different (non-current) episode must keep the
     * established fresh Play admission semantics and may not be silently attached to the current
     * episode's request/binding.
     */
    @Test fun podcastStartOverForANonCurrentEpisodeKeepsFreshAdmissionSemantics() = checkCase {
        val fixture = newFixture()
        val current = item(id = "start-over-current-episode", title = "Current episode — الحلقة الحالية")
        val first = fixture.play(current)
        val firstBinding = assertNotNull(first.source.executionBinding?.id)
        val other = item(id = "start-over-other-episode", title = "Other episode — حلقة أخرى")
        fixture.store.upsertCatalog(listOf(other)).getOrThrow()
        fixture.app.onPodcastAction(PodcastAction.StartOver(other.id))
        val restarted = try {
            withTimeout(15_000) { fixture.platform.engine.prepared.receive() }
        } catch (timeout: TimeoutCancellationException) {
            fail("Non-current Start Over did not fresh-admit: phase=${fixture.app.playback.state.value.phase} " +
                "failure=${fixture.app.playback.state.value.failure} queries=${fixture.gateway.queries}")
        }
        fixture.app.playback.state.first { it.currentItem?.id == other.id && it.isPlaying }
        val otherBinding = assertNotNull(restarted.source.executionBinding?.id)
        assertNotEquals(firstBinding, otherBinding)
        assertEquals(other, restarted.source.item)
        assertEquals(listOf(current.title, other.title), fixture.gateway.queries.toList(),
            "A non-current Start Over keeps the established fresh-admission semantics")
        val canonical = fixture.store.acquisitionView().acquisition
        assertEquals(2, canonical.requests.values.count { it.state == AcquisitionJobState.COMMITTED })
        assertEquals(2, canonical.bindings.size)
        assertEquals(other.id, fixture.app.playback.state.value.currentSelection?.catalogMedia?.id)
        fixture.assertNoNetwork()
    }

    /**
     * Stale/competing queue control for the Start Over chain. The playback-state selection is
     * advisory; the authoritative current occurrence can move outside the controller. Start Over
     * must neither reactivate the competing occurrence nor reuse the stale one. It falls back to
     * the established fresh admission for the requested episode, and a binding/queue CAS failure
     * after dispatch is never converted into a silent fallback.
     */
    @Test fun podcastStartOverWithACompetingQueueActivationDoesNotRestartTheWrongOccurrence() = checkCase {
        val fixture = newFixture(initialize = false)
        val original = item(id = "stale-start-original", title = "Original restart episode — الحلقة الأصلية")
        val competing = item(id = "stale-start-competing", title = "Competing episode — حلقة منافسة")
        val originalSelection = fixture.seedSelection(original)
        fixture.seedQueue(originalSelection, PlaybackControlsMode.PODCAST, activate = true)
        fixture.app.initialize()
        assertEquals(PlaybackPhase.PAUSED, fixture.app.playback.state.value.phase)

        // A competing canonical activation outside the playback owner. Both the advisory
        // playback state and the authoritative queue can now disagree about the current
        // occurrence; Start Over(original) must not prefer either stale binding.
        val competingSelection = fixture.seedSelection(competing)
        val competingOccurrence = fixture.seedQueue(competingSelection, PlaybackControlsMode.PODCAST, activate = true)
        fixture.app.onPodcastAction(PodcastAction.StartOver(original.id))
        val restarted = withTimeout(15_000) { fixture.platform.engine.prepared.receive() }
        fixture.app.playback.state.first { it.currentItem?.id == original.id && it.isPlaying }
        assertEquals(original, restarted.source.item)
        val freshBinding = assertNotNull(restarted.source.executionBinding?.id)
        assertNotEquals(originalSelection.bindingId.value, freshBinding)
        assertNotEquals(competingSelection.bindingId.value, freshBinding)
        assertEquals(listOf(original.title), fixture.gateway.queries.toList(),
            "The disagreement admits the requested episode freshly; never the competing occurrence")
        val canonical = fixture.store.acquisitionView().acquisition
        val currentId = assertNotNull(canonical.queues.getValue(QueueContext.PODCAST).currentOccurrenceId)
        assertNotEquals(competingOccurrence.id, currentId)
        val current = canonical.occurrences.getValue(currentId)
        assertEquals(original.id, current.subjectId?.value)
        assertEquals(original.title,
            canonical.requests.getValue(requireNotNull(current.requestId)).request.completeTitle)
        assertEquals(competing.id, canonical.occurrences.getValue(competingOccurrence.id).subjectId?.value,
            "The competing occurrence remains historical, never relabelled")
        assertTrue(fixture.gateway.resolutions.none { it.purpose == ResolvePurpose.DOWNLOAD })
        fixture.assertNoNetwork()
    }

    @Test fun offlineSearchCancelsPendingHttpAndPublishesFailureRatherThanAnEndlessSpinner() = checkCase {
        val fixture = newFixture()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        fixture.http.reply = { entered.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        fixture.app.onMusicAction(MusicUiAction.SearchChanged("authored podcast", SearchScope.EVERYWHERE, SearchKind.PODCASTS, SourceProvider.RSS))
        fixture.app.onMusicAction(MusicUiAction.RunSearch)
        entered.await()
        fixture.app.changeSettings { it.copy(offlineOnly = true) }
        cancelled.await()
        val state = fixture.app.search.first { !it.loading && it.failure?.code == FailureCode.OFFLINE_POLICY }
        assertTrue(state.searched)
        assertTrue(state.page.items.isEmpty())
        assertEquals(1, fixture.http.requests.size)
        assertTrue(fixture.gateway.queries.isEmpty())
        assertTrue(fixture.platform.engine.preparations.isEmpty())
    }

    @Test fun uiLibraryDoesNotAdvertiseRestartedRoomCompleteAsReadyBeforeThisProcessValidatesIt() = checkCase {
        val seed = newFixture(initialize = false)
        val original = item()
        val selection = seed.seedSelection(original)
        val jobId = seed.seedCompleteJob(selection, seed.clockRef.epochMs(), "restarted-complete-download")
        val asset = assertNotNull(seed.asset(jobId))
        seed.close()

        val reopened = newFixture(initialize = false)
        val validating = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        reopened.platform.validateLocal = { input ->
            assertEquals(asset.uri, input.uri)
            validating.complete(Unit)
            release.await()
            AssetValidation.Valid(12_345L)
        }
        coroutineScope {
            val observed = ConcurrentLinkedQueue<DownloadStatus>()
            val observing = launch(start = CoroutineStart.UNDISPATCHED) {
                reopened.app.downloads.acquisitionPresentation.collect { presentation ->
                    presentation.states[jobId]?.let { observed += it.status }
                }
            }
            try {
                reopened.app.initialize()
                validating.await()
                assertEquals(DownloadStatus.COMPLETE, reopened.store.acquisitionView()
                    .acquisition.physicalDownloads.getValue(jobId).status,
                    "The historical Room receipt remains intact while session-only validation is pending")
                assertEquals(DownloadStatus.VERIFYING, reopened.app.downloads.acquisitionPresentation
                    .value.states.getValue(jobId).status,
                    "The current-process projection must be honestly Checking, not Ready")
                assertFalse(DownloadStatus.COMPLETE in observed, "No projection may flash Ready from unchecked historical rows")
                assertTrue(reopened.app.library.value.downloads.isEmpty(),
                    "A typed physical job must not masquerade as a legacy Ready compatibility row")
                assertTrue(reopened.platform.engine.preparations.isEmpty())
                release.complete(Unit)
                withTimeout(15_000) {
                    reopened.app.downloads.acquisitionPresentation.first {
                        it.states[jobId]?.status == DownloadStatus.COMPLETE
                    }
                }
                assertEquals(DownloadStatus.COMPLETE,
                    reopened.store.acquisitionView().acquisition.physicalDownloads.getValue(jobId).status)
            } finally { release.complete(Unit); observing.cancelAndJoin() }
        }
        reopened.assertNoNetwork()
    }

    @Test fun queuedDownloadRetainsTheRecordingChosenAtIntentAfterAnotherFreshPlayOfSameItem() = checkCase {
        val fixture = newFixture()
        val original = item()
        fixture.store.upsertCatalog(listOf(original)).getOrThrow()
        val downloadEntered = CompletableDeferred<Unit>()
        val permitDownload = CompletableDeferred<Unit>()
        fixture.platform.acquireDownload = {
            downloadEntered.complete(Unit)
            permitDownload.await()
            DownloadExecutionLease {}
        }
        fixture.gateway.onDownload = { transfer -> writeFakeTransfer(transfer) }
        try {
            val first = assertNotNull(fixture.play(original).source.executionBinding)
            fixture.app.onMusicAction(MusicUiAction.Download(original))
            downloadEntered.await()
            fixture.gateway.onFirst = { title -> KmpFirstResult(title, FirstResultKind.VIDEO, DISCOVERED_YOUTUBE,
                "A different fresh first recording", "https://www.youtube.com/watch?v=$DISCOVERED_YOUTUBE") }
            val second = assertNotNull(fixture.play(original).source.executionBinding)
            assertNotEquals(first.id, second.id)
            assertNotEquals(first.selectedVideoId, second.selectedVideoId)
            permitDownload.complete(Unit)
            val view = withTimeout(20_000) {
                var current = fixture.store.acquisitionViews.value
                while (current.acquisition.physicalDownloads.values.none { it.status == DownloadStatus.COMPLETE && it.assetId != null }) {
                    delay(10)
                    current = fixture.store.acquisitionViews.value
                }
                current
            }
            val state = view.acquisition
            val job = state.physicalDownloads.values.single { it.status == DownloadStatus.COMPLETE }
            val asset = state.materializations.getValue(assertNotNull(job.assetId))
            assertEquals(first.id, job.producer.bindingId?.value)
            assertEquals(first.id, asset.producer.bindingId?.value)
            assertEquals(first.selectedVideoId, state.recordings.getValue(requireNotNull(job.producer.recordingId)).canonicalId)
            assertEquals(first, fixture.gateway.transfers.single().binding)
            assertEquals(first, fixture.gateway.transfers.single().persisted)
            assertTrue(fixture.gateway.transfers.single().completed)
            assertEquals(1, fixture.gateway.typedCompletions.size)
            assertEquals(second.id, fixture.app.playback.state.value.currentSelection?.bindingId?.value)
            assertEquals(listOf(original.title, original.title), fixture.gateway.queries.toList())
            assertEquals(first, fixture.gateway.resolutions.single { it.purpose == ResolvePurpose.DOWNLOAD }.binding)
            assertEquals(1, fixture.gateway.typedTransfers.size)
        } finally { permitDownload.complete(Unit) }
        fixture.assertNoNetwork()
    }

    @Test fun slowDownloadRemovalCleanupCannotBlockUiPauseOrPersistedOfflineChange() = checkCase {
        val fixture = newFixture()
        val playing = item(SourceProvider.COMMONS, "independent-playing-item")
        val downloadItem = item(SourceProvider.COMMONS, "slow-removal-download")
        fixture.store.upsertCatalog(listOf(playing, downloadItem)).getOrThrow()
        fixture.play(playing)
        val transferEntered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        fixture.gateway.onDownload = {
            transferEntered.complete(Unit)
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { cleanupEntered.complete(Unit); releaseCleanup.await() } }
        }
        fixture.app.onMusicAction(MusicUiAction.Download(downloadItem))
        transferEntered.await()
        val downloadId = fixture.store.acquisitionView().acquisition.physicalDownloads.values
            .single { it.producer.subjectId.value == downloadItem.id }.id
        try {
            fixture.app.onMusicAction(MusicUiAction.RemoveDownload(downloadId))
            cleanupEntered.await()
            assertTrue(fixture.store.acquisitionView().acquisition.physicalDownloads.getValue(downloadId).cleanupPending)
            fixture.app.onMusicAction(MusicUiAction.TogglePlayPause)
            withTimeout(10_000L) {
                fixture.app.playback.state.first { it.currentItem?.id == playing.id && !it.desiredPlaying && !it.isPlaying }
            }
            fixture.app.changeSettings { it.copy(offlineOnly = true) }
            withTimeout(10_000L) { fixture.store.state.first { it.settings.offlineOnly } }
            assertFalse(releaseCleanup.isCompleted, "Pause/Offline must finish before slow media cleanup is released")
        } finally { releaseCleanup.complete(Unit) }
        withTimeout(15_000L) {
            fixture.store.acquisitionViews.first { view ->
                val job = view.acquisition.physicalDownloads[downloadId]
                job == null || !job.cleanupPending && job.assetId == null && job.stagingPath == null
            }
        }
        assertTrue(fixture.store.state.value.downloads.isEmpty(), "Typed physical cleanup is not a legacy row deletion")
        assertEquals(2, fixture.gateway.queries.size, "Play plus the one admitted selection query; removal searches nothing")
        // The held transfer was actually invoked and then cancelled; a zero completion list alone
        // would not prove that no source execution started.
        assertEquals(1, fixture.gateway.transfers.size)
        assertFalse(fixture.gateway.transfers.single().completed)
        assertTrue(fixture.gateway.transfers.single().failure is CancellationException)
        assertEquals(1, fixture.gateway.typedTransfers.size)
        assertTrue(fixture.gateway.typedCompletions.isEmpty())
        fixture.assertNoNetwork()
    }

    @Test fun ownedFileRemovalWaitsForNativeStopButDoesNotBlockOfflineUiIntent() = checkCase {
        val fixture = newFixture(initialize = false)
        val original = item(title = "Owned local removal — إزالة الملف المحلي")
        val selection = fixture.seedSelection(original)
        fixture.seedQueue(selection, PlaybackControlsMode.PODCAST, activate = true)
        val jobId = fixture.seedCompleteJob(selection, fixture.clockRef.epochMs(), "owned-file-removal")
        val asset = assertNotNull(fixture.asset(jobId))
        fixture.app.initialize()
        fixture.awaitChecked(jobId, asset)
        assertEquals(PlaybackPhase.PAUSED, fixture.app.playback.state.value.phase)
        val offline = fixture.resumeRestored(original)
        assertTrue(offline.source.isLocal)
        assertEquals(asset.uri, offline.source.uri)
        assertEquals(selection.bindingId.value, offline.source.executionBinding?.id)
        val stopEntered = CompletableDeferred<Unit>()
        val releaseStop = CompletableDeferred<Unit>()
        fixture.platform.engine.beforeStop = { stopEntered.complete(Unit); releaseStop.await() }
        try {
            fixture.app.onPodcastAction(PodcastAction.RemoveDownload(jobId))
            stopEntered.await()
            assertTrue(Files.isRegularFile(Path.of(asset.uri)), "Durable intent is not native file-release acknowledgement")
            assertTrue(fixture.store.acquisitionView().acquisition.physicalDownloads.getValue(jobId).cleanupPending)
            fixture.app.changeSettings { it.copy(offlineOnly = true) }
            withTimeout(10_000L) { fixture.store.state.first { it.settings.offlineOnly } }
            assertTrue(Files.isRegularFile(Path.of(asset.uri)), "Offline settings may change while deletion safely waits")
            assertNull(fixture.store.acquisitionView().acquisition.physicalDownloads.getValue(jobId).failure)
        } finally {
            fixture.platform.engine.beforeStop = {}
            releaseStop.complete(Unit)
        }
        withTimeout(15_000L) {
            fixture.store.acquisitionViews.first { view ->
                val job = view.acquisition.physicalDownloads[jobId]
                job == null || !job.cleanupPending && job.assetId == null
            }
        }
        assertFalse(Files.exists(Path.of(asset.uri)))
        assertTrue(fixture.gateway.queries.isEmpty(), "Opening the completed local artifact cannot fresh-search")
        assertTrue(fixture.gateway.resolutions.isEmpty())
        fixture.assertNoNetwork()
    }

    @Test fun settingsNavigationAndOwnedArtworkRestoreOfflineWithoutAnyNetworkOrAutoplay() = checkCase {
        val fixture = newFixture()
        val show = PodcastShow("authored-show", "Authored show", "https://publisher.example.com/feed",
            artworkUrl = "https://images.example.com/show.png", lastRefreshEpochMs = 1L)
        val episode = item().copy(showId = show.id)
        val track = item(SourceProvider.COMMONS, "artwork-track").copy(artworkUrl = "https://images.example.com/cover.png")
        fixture.store.upsertCatalog(listOf(episode, track), listOf(show)).getOrThrow()
        fixture.http.reply = { respond(PNG, HttpStatusCode.OK, headersOf("Content-Type", "image/png")) }
        fixture.app.onMusicAction(MusicUiAction.OpenMedia(track))
        fixture.app.onPodcastAction(PodcastAction.OpenShow(show.id))
        val snapshot = fixture.store.state.first { it.media[track.id]?.localArtworkPath != null && it.shows[show.id]?.localArtworkPath != null }
        val trackPath = assertNotNull(snapshot.media[track.id]?.localArtworkPath)
        val showPath = assertNotNull(snapshot.shows[show.id]?.localArtworkPath)
        val settings = AppSettings(language = LanguagePreference.ARABIC, theme = ThemeMode.DARK, textScale = 1.3f,
            offlineOnly = true, speed = 1.25f, skipBackSeconds = 10, skipForwardSeconds = 45,
            showSpeeds = mapOf(show.id to 1.5f), crossfadeEnabled = true, crossfadeDurationMs = 5_000L, downloadParallelism = 4)
        fixture.app.changeSettings { settings }
        fixture.store.state.first { it.settings == settings }
        fixture.app.selectDestination(AppDestination.SETTINGS)
        fixture.app.close()
        assertEquals(2, fixture.http.requests.size)

        val reopened = newFixture()
        assertEquals(settings, reopened.store.state.value.settings)
        assertEquals(LanguagePreference.ARABIC, reopened.app.effectiveLanguage(settings.language))
        assertEquals(AppDestination.SETTINGS, reopened.app.navigation.value.destination)
        assertEquals(trackPath, reopened.app.cachedArtworkPath(track.id))
        assertEquals(showPath, reopened.app.cachedArtworkPath(episode.id), "Episode can use a verified local show cover")
        assertContentEquals(PNG, Files.readAllBytes(Path.of(trackPath)))
        assertContentEquals(PNG, Files.readAllBytes(Path.of(showPath)))
        assertTrue(reopened.platform.engine.preparations.isEmpty())
        reopened.app.onMusicAction(MusicUiAction.OpenMedia(track.copy(artworkUrl = "https://images.example.com/changed.png")))
        reopened.app.onPodcastAction(PodcastAction.OpenShow(show.id))
        // An actor barrier after these actions, not a timing sleep to hope work has finished.
        reopened.app.changeSettings { it.copy(skipForwardSeconds = 60) }
        reopened.store.state.first { it.settings.skipForwardSeconds == 60 }
        assertEquals(trackPath, reopened.app.cachedArtworkPath(track.id))
        assertEquals(showPath, reopened.app.cachedArtworkPath(episode.id))
        reopened.assertNoNetwork()
    }

    @Test fun closeCancelsPlaybackAndArtworkBeforeClosingSharedDependenciesExactlyOnce() = checkCase {
        val fixture = newFixture()
        val resolving = CompletableDeferred<Unit>(); val resolveCancelled = CompletableDeferred<Unit>()
        val requesting = CompletableDeferred<Unit>(); val requestCancelled = CompletableDeferred<Unit>()
        fixture.gateway.onResolve = { _, _, _ ->
            resolving.complete(Unit)
            try { awaitCancellation() } finally { resolveCancelled.complete(Unit) }
        }
        fixture.http.reply = {
            requesting.complete(Unit)
            try { awaitCancellation() } finally { requestCancelled.complete(Unit) }
        }
        val original = item().copy(artworkUrl = "https://images.example.com/pending.png")
        fixture.app.onMusicAction(MusicUiAction.Play(original))
        resolving.await(); requesting.await()
        fixture.app.close()
        resolveCancelled.await(); requestCancelled.await()
        fixture.app.close()
        assertEquals(1, fixture.platform.engine.closeCalls.get())
        assertEquals(1, fixture.platform.youtubeCloseCalls.get())
        assertEquals(1, fixture.platform.runtimeCloseCalls.get())
        assertEquals(1, fixture.http.closeCalls.get())
        assertEquals(StoreStatus.Closed, fixture.store.status.value)
        assertNull(fixture.store.state.value.media[original.id]?.localArtworkPath)
        assertTrue(fixture.platform.engine.preparations.isEmpty())
        Files.list(directory.resolve("artwork/.partial")).use { assertEquals(0L, it.count()) }
    }

    @Test fun failedOwnerCloseStillReleasesLaterOwnersAndRepeatedCloseKeepsSanitizedFailureWithoutRetry() = checkCase {
        val fixture = newFixture()
        fixture.platform.beforeRuntimeClose = { throw IllegalStateException("authored sensitive native diagnostic") }
        val first = assertFailsWith<VeyraException> { fixture.app.close() }
        assertEquals(AppFailure(FailureCode.UNEXPECTED), first.failure)
        assertNull(first.cause)
        assertFalse(first.toString().contains("sensitive native diagnostic"))
        fixture.expectedCloseFailure = first.failure
        val repeated = assertFailsWith<VeyraException> { fixture.app.close() }
        assertEquals(first.failure, repeated.failure)
        assertNull(repeated.cause)
        assertEquals(1, fixture.platform.engine.closeCalls.get())
        assertEquals(1, fixture.platform.runtimeCloseCalls.get())
        assertEquals(1, fixture.platform.youtubeCloseCalls.get())
        assertEquals(1, fixture.http.closeCalls.get())
        assertEquals(StoreStatus.Closed, fixture.store.status.value)
        fixture.assertNoNetwork()
    }

    @Test fun finalNavigationWriteFailureFromActualSqliteIsNotSwallowedAndStillClosesStore() = checkCase {
        val fixture = newFixture()
        val armed = AtomicInteger()
        fixture.platform.beforeRuntimeClose = {
            // App effects and the playback owner have already stopped. Only the final navigation
            // save will need these rows; do not replace Room with a broad fake/failing repository.
            fixture.store.update { it.copy(navigation = emptyMap()) }.getOrThrow()
            assertTrue(fixture.store.state.value.navigation.isEmpty())
            // The shared module intentionally keeps its driver an implementation dependency.
            // Instantiate that same real runtime driver without changing application dependencies.
            val driver = Class.forName("androidx.sqlite.driver.bundled.BundledSQLiteDriver")
                .getConstructor().newInstance() as SQLiteDriver
            driver.open(directory.resolve("library/veyra.db").toString()).use { connection ->
                connection.execSQL("PRAGMA busy_timeout = 5000")
                connection.execSQL("""CREATE TRIGGER authored_reject_final_navigation
                    BEFORE INSERT ON navigation_state
                    BEGIN SELECT RAISE(ABORT, 'authored final navigation failure'); END""")
            }
            armed.incrementAndGet()
        }
        val first = assertFailsWith<VeyraException> { fixture.app.close() }
        assertEquals(1, armed.get(), "The narrow SQLite fault must be installed before final save")
        assertEquals(FailureCode.STORAGE_IO, first.failure.code)
        assertNull(first.cause)
        assertFalse(first.toString().contains("authored final navigation failure"))
        fixture.expectedCloseFailure = first.failure
        assertEquals(first.failure, assertFailsWith<VeyraException> { fixture.app.close() }.failure)
        assertEquals(1, armed.get(), "A sticky failed close must not rerun any owner/fault hook")
        assertEquals(1, fixture.platform.engine.closeCalls.get())
        assertEquals(1, fixture.platform.runtimeCloseCalls.get())
        assertEquals(1, fixture.platform.youtubeCloseCalls.get())
        assertEquals(1, fixture.http.closeCalls.get())
        assertEquals(StoreStatus.Closed, fixture.store.status.value)
        assertTrue(fixture.store.state.value.navigation.isEmpty(), "An aborted Room write must not publish navigation")
        fixture.assertNoNetwork()
    }

    @Test fun coldSchedulingBootstrapPreservesRestorableCollectionQueueAndHistoricalAssetWithoutOpeningAUserSession() = checkCase {
        val seed = newFixture(initialize = false)
        val historical = item(SourceProvider.COMMONS, "scheduling-historical").copy(
            artworkUrl = "https://images.example.com/missing-authored-cover.png",
            localArtworkPath = directory.resolve("artwork/files/missing-authored-cover.png").toString())
        val pending = item(id = "scheduling-pending")
        val historicalSelection = seed.seedSelection(historical)
        seed.seedQueue(historicalSelection, PlaybackControlsMode.MUSIC, positionMs = 3_210)
        val completeJobId = seed.seedCompleteJob(historicalSelection, seed.clockRef.epochMs(), "scheduling-historical-download")
        val completeAsset = assertNotNull(seed.asset(completeJobId))
        val pendingSelection = seed.seedSelection(pending)
        val queuedJobId = seed.seedJob(pendingSelection, seed.clockRef.epochMs(), "scheduling-pending-download")
        val collection = MediaCollection("scheduling-restored-collection", "Complete restored collection — المجموعة",
            SourceRef(SourceProvider.YOUTUBE, "PLauthoredSchedulingCollection",
                "https://www.youtube.com/playlist?list=PLauthoredSchedulingCollection"), kind = BrowseKind.PLAYLIST)
        val navigation = AppNavigation(AppDestination.LIBRARY,
            details = listOf(AppDetail(DetailKind.COLLECTION, collection.id, collection)))
        seed.store.update { snapshot -> snapshot.copy(navigation = navigation.persisted()) }.getOrThrow()
        assertEquals(navigation, AppNavigation.restore(seed.store.state.value), "The collection fixture must genuinely be restorable")
        // The old fixture pointed at an ABSENT historical file without creating directories.
        // Publication above needed real staging/final paths; remove only those test-owned
        // directories so the cold bootstrap is proved not to recreate them.
        seed.deleteDirectory(directory.resolve("media"))
        val seededView = seed.store.acquisitionView()
        seed.assertSchedulingOnly()
        seed.close()

        val reopened = newFixture(initialize = false)
        assertIs<StoreStatus.Uninitialized>(reopened.store.status.value)
        repeat(2) { reopened.app.initializeScheduling() }
        assertIs<StoreStatus.Ready>(reopened.store.status.value)
        assertEquals(seededView, reopened.store.acquisitionView(), "Headless bootstrap must be a read, not library reconciliation")
        assertEquals(navigation, AppNavigation.restore(reopened.store.state.value))
        assertEquals(historical.localArtworkPath, reopened.store.state.value.media.getValue(historical.id).localArtworkPath)
        val restored = reopened.store.acquisitionView()
        assertEquals(DownloadStatus.COMPLETE, restored.acquisition.physicalDownloads.getValue(completeJobId).status)
        assertEquals(completeAsset.id, restored.acquisition.physicalDownloads.getValue(completeJobId).assetId)
        assertEquals(completeAsset, restored.acquisition.materializations.getValue(completeAsset.id))
        assertEquals(DownloadStatus.QUEUED, restored.acquisition.physicalDownloads.getValue(queuedJobId).status)
        assertEquals(3_210L, restored.acquisition.queues.getValue(QueueContext.MUSIC).positionMs)
        assertNotNull(restored.acquisition.queues.getValue(QueueContext.MUSIC).currentOccurrenceId)
        assertNull(reopened.app.cachedArtworkPath(historical.id), "An unverified stored path is not a trusted image")
        reopened.assertSchedulingOnly()
        Files.newInputStream(directory.resolve("library/veyra.db")).use {
            assertEquals("SQLite format 3\u0000", it.readNBytes(16).decodeToString())
        }
    }

    @Test fun localLibrarySettingsAndFakePlaybackAreReadyWhileCoverageAckIsPendingAndRemainReadyWhenItFails() = checkCase {
        val fixture = newFixture(initialize = false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val denied = AppFailure(FailureCode.BACKGROUND_RESTRICTED, "authored_coverage_unavailable")
        fixture.platform.ensureCoverage = { entered.complete(Unit); release.await(); throw VeyraException(denied) }
        coroutineScope {
            val initializing = async { fixture.app.initialize() }
            try {
                entered.await()
                assertEquals(AppInitialization.Ready, fixture.app.initialization.value)
                assertTrue(fixture.app.playback.state.value.initialized)
                assertFalse(initializing.isCompleted)
                assertEquals(FailureCode.BACKGROUND_RESTRICTED, fixture.app.downloadRestriction.value?.code)
                assertEquals(1, fixture.platform.ensureCoverageCalls.get())
                assertEquals(0, fixture.platform.coverageAcknowledgements.get())

                val original = item(SourceProvider.COMMONS, "usable-during-coverage")
                fixture.app.onMusicAction(MusicUiAction.CreatePlaylist("Usable local library — مكتبتي", original))
                fixture.store.state.first { snapshot -> snapshot.playlists.any { it.entries.any { entry -> entry.mediaId == original.id } } }
                fixture.app.changeSettings { it.copy(language = LanguagePreference.ARABIC, skipForwardSeconds = 45) }
                fixture.store.state.first { it.settings.language == LanguagePreference.ARABIC && it.settings.skipForwardSeconds == 45 }
                val preparation = fixture.play(original)
                assertEquals(original.id, preparation.source.mediaId)
                assertFalse(initializing.isCompleted, "Neither a local edit nor fake playback may depend on the coverage ACK")
                assertEquals(0, fixture.platform.downloadGateCalls.get())
                assertTrue(fixture.gateway.transfers.isEmpty())

                release.complete(Unit)
                initializing.await()
                assertEquals(AppInitialization.Ready, fixture.app.initialization.value)
                assertEquals(denied, fixture.app.downloadRestriction.value)
                assertEquals(denied, fixture.app.notice.value?.failure)
                assertTrue(fixture.app.playback.state.value.isPlaying)
                assertEquals(0, fixture.platform.coverageAcknowledgements.get())
                assertFalse(fixture.platform.coverageAcknowledged.isCompleted)
                assertEquals(0, fixture.platform.downloadGateCalls.get())
            } finally { release.complete(Unit) }
        }
        fixture.assertNoNetwork()
    }

    @Test fun uncoveredMusicCollectionPodcastAndResumeTapsRejectAtThePublicBoundaryEvenWithAHeldActorAndCannotReplayAfterCancel() = checkCase {
        val fixture = newFixture(initialize = false)
        val music = item(SourceProvider.COMMONS, "held-actor-paused-music")
        val podcast = item(id = "held-actor-paused-podcast")
        val newMusic = item(SourceProvider.COMMONS, "held-actor-new-music")
        val newPodcast = item(id = "held-actor-new-podcast")
        val members = listOf(item(SourceProvider.COMMONS, "held-actor-member-one"),
            item(SourceProvider.COMMONS, "held-actor-member-two"))
        val pausedMusic = fixture.seedSelection(music)
        val pausedPodcast = fixture.seedSelection(podcast)
        val now = fixture.clockRef.epochMs()
        val musicJob = fixture.seedAttemptJob(pausedMusic, now, "held-actor-paused-music-download",
            status = DownloadStatus.PAUSED, attemptStatus = DownloadAttemptStatus.PAUSED,
            intent = DownloadBatchIntent.PAUSED)
        val podcastJob = fixture.seedAttemptJob(pausedPodcast, now, "held-actor-paused-podcast-download",
            status = DownloadStatus.PAUSED, attemptStatus = DownloadAttemptStatus.PAUSED,
            intent = DownloadBatchIntent.PAUSED)
        fixture.deleteDirectory(directory.resolve("media"))
        fixture.store.upsertCatalog(listOf(newMusic, newPodcast) + members).getOrThrow()
        val coverageEntered = CompletableDeferred<Unit>()
        val permitCoverage = CompletableDeferred<Unit>()
        fixture.platform.ensureCoverage = { coverageEntered.complete(Unit); permitCoverage.await() }
        val actorEntered = CompletableDeferred<Unit>()
        val permitActor = CountDownLatch(1)
        val actorTimedOut = AtomicBoolean()
        coroutineScope {
            val initializing = async { fixture.app.initialize() }
            try {
                coverageEntered.await()
                fixture.app.changeSettings { settings ->
                    actorEntered.complete(Unit)
                    // This authored synchronous transform really holds the action actor AND
                    // Room's transaction. Always bounded/released; never await activation here.
                    if (!permitActor.await(15, TimeUnit.SECONDS)) actorTimedOut.set(true)
                    settings.copy(skipBackSeconds = 20)
                }
                actorEntered.await()
                val restriction = assertNotNull(fixture.app.downloadRestriction.value)
                fun rejectsAtTap(action: () -> Unit) {
                    val previousNotice = fixture.app.notice.value?.id
                    action()
                    // No suspension or actor fence between the public call and these assertions.
                    val rejected = assertNotNull(fixture.app.notice.value)
                    assertNotEquals(previousNotice, rejected.id, "The held actor cannot be responsible for rejecting this tap")
                    assertEquals(restriction, rejected.failure)
                }
                rejectsAtTap { fixture.app.onMusicAction(MusicUiAction.Download(newMusic)) }
                rejectsAtTap { fixture.app.onMusicAction(MusicUiAction.DownloadCollection(members)) }
                rejectsAtTap { fixture.app.onPodcastAction(PodcastAction.Download(newPodcast.id)) }
                rejectsAtTap { fixture.app.onMusicAction(MusicUiAction.ResumeDownload(musicJob)) }
                rejectsAtTap { fixture.app.onPodcastAction(PodcastAction.ResumeDownload(podcastJob)) }
                // Nonblocking inspection of the ALREADY-PUBLISHED view while the authored
                // transform still holds the action actor AND Room's write transaction. Do not use
                // acquisitionView()/job()/claim() here: they would wait for this writer.
                val held = fixture.store.acquisitionViews.value.acquisition
                assertEquals(DownloadStatus.PAUSED, held.physicalDownloads.getValue(musicJob).status)
                assertEquals(DownloadStatus.PAUSED, held.physicalDownloads.getValue(podcastJob).status)
                assertEquals(DownloadBatchIntent.PAUSED,
                    held.claims.values.single { it.physicalJobId == musicJob }.intent)
                assertEquals(DownloadBatchIntent.PAUSED,
                    held.claims.values.single { it.physicalJobId == podcastJob }.intent)
                fixture.assertNoDownloadExecution()

                // Cancel remains admissible AT THE PUBLIC BOUNDARY WHILE THE ACTOR IS HELD.
                // Commit it BEFORE accepting coverage so an older rejected resume/download cannot
                // wake up on the later successful ACK. The effects wait on the writer; the intents
                // are already accepted by submit/trySend and will commit as soon as it releases.
                fixture.app.onMusicAction(MusicUiAction.CancelDownload(musicJob))
                fixture.app.onPodcastAction(PodcastAction.CancelDownload(podcastJob))
                permitActor.countDown()
                fixture.store.state.first { it.settings.skipBackSeconds == 20 }
                fixture.store.acquisitionViews.first { view ->
                    listOf(musicJob, podcastJob).all { id ->
                        val job = view.acquisition.physicalDownloads[id]
                        job != null && job.status == DownloadStatus.CANCELLED && job.userPaused && job.cleanupPending
                    }
                }
                assertFalse(actorTimedOut.get())
                assertEquals(20, fixture.store.state.value.settings.skipBackSeconds)
                assertEquals(0, fixture.platform.coverageAcknowledgements.get())
                fixture.assertNoDownloadExecution()

                permitCoverage.complete(Unit)
                fixture.platform.coverageAcknowledged.await()
                initializing.await()
                assertNull(fixture.app.downloadRestriction.value)
                fixture.store.acquisitionViews.first { view ->
                    listOf(musicJob, podcastJob).all { id ->
                        view.acquisition.physicalDownloads[id]?.let { !it.cleanupPending && it.status == DownloadStatus.CANCELLED } == true
                    }
                }
                fixture.awaitDownloadIdle()
                // Fence the action consumer too, rather than merely sampling before it runs.
                fixture.app.changeSettings { it.copy(skipForwardSeconds = 60) }
                fixture.store.state.first { it.settings.skipForwardSeconds == 60 }
                val final = fixture.store.acquisitionView()
                assertEquals(DownloadStatus.CANCELLED, final.acquisition.physicalDownloads.getValue(musicJob).status)
                assertEquals(DownloadStatus.CANCELLED, final.acquisition.physicalDownloads.getValue(podcastJob).status)
                assertTrue(final.acquisition.physicalDownloads.getValue(musicJob).userPaused)
                assertTrue(final.acquisition.physicalDownloads.getValue(podcastJob).userPaused)
                assertEquals(DownloadBatchIntent.CANCELLED, final.acquisition.claims.getValue(fixture.claim(musicJob).id).intent)
                assertEquals(DownloadBatchIntent.CANCELLED, final.acquisition.claims.getValue(fixture.claim(podcastJob).id).intent)
                // Cancel (not Remove) stops unfinished work but retains any already-completed asset.
                assertTrue(final.acquisition.claims.getValue(fixture.claim(musicJob).id).retainCompletedAsset)
                assertTrue(final.acquisition.claims.getValue(fixture.claim(podcastJob).id).retainCompletedAsset)
                assertTrue(final.acquisition.physicalDownloads.values.all { it.leaseOwner == null })
                assertTrue(final.acquisition.physicalAttempts.values.all { it.finishedEpochMs != null })
                assertTrue(final.acquisition.physicalAttempts.values.none { it.status == DownloadAttemptStatus.RUNNING })
                assertTrue(final.snapshot.playbackBindings.isEmpty(), "Cancelled downloads do not fabricate legacy bindings")
                assertTrue(fixture.store.state.value.downloads.isEmpty())
                assertTrue(fixture.gateway.queries.isEmpty())
                assertTrue(fixture.gateway.resolutions.isEmpty())
                assertTrue(fixture.gateway.transfers.isEmpty())
                assertEquals(1, fixture.platform.ensureCoverageCalls.get())
                assertEquals(1, fixture.platform.coverageAcknowledgements.get())
                assertTrue(fixture.platform.gateCoverageAcknowledgements.isNotEmpty(), "Accepted coverage may now clean cancelled intent")
                assertTrue(fixture.platform.gateCoverageAcknowledgements.all { it == 1 })
                assertTrue(fixture.platform.validations.isEmpty())
                assertEquals(0, fixture.platform.runtimePrepareCalls.get())
                assertTrue(fixture.platform.engine.preparations.isEmpty())
            } finally {
                permitActor.countDown()
                permitCoverage.complete(Unit)
            }
        }
        fixture.assertNoNetwork()
    }

    @Test fun schedulingOnlyPauseCancelAndRemovePersistAcrossRoomReopenWithoutMediaDirectoriesOrNativeMaintenance() = checkCase {
        val clock = TestClock()
        val fixture = newFixture(initialize = false, clock = clock)
        val media = listOf(item(SourceProvider.COMMONS, "headless-pause"), item(id = "headless-cancel"),
            item(SourceProvider.COMMONS, "headless-remove"))
        val now = clock.epochMs()
        val pause = fixture.seedSelection(media[0])
        val cancel = fixture.seedSelection(media[1])
        val remove = fixture.seedSelection(media[2])
        val retryDeadline = now + 60_000
        val pauseJob = fixture.seedAttemptJob(pause, now, "headless-pause-download",
            nextRetryEpochMs = retryDeadline, automaticRetryCount = 2,
            intent = DownloadBatchIntent.PAUSED)
        val cancelJob = fixture.seedAttemptJob(cancel, now, "headless-cancel-download",
            nextRetryEpochMs = retryDeadline, automaticRetryCount = 2)
        val removeJob = fixture.seedAttemptJob(remove, now, "headless-remove-download",
            nextRetryEpochMs = retryDeadline, automaticRetryCount = 2)
        fixture.deleteDirectory(directory.resolve("media"))
        fixture.app.initializeScheduling()
        fixture.app.downloads.pause(pauseJob)
        fixture.app.downloads.cancel(cancelJob)
        fixture.app.downloads.remove(removeJob)
        fun assertIntent(view: dev.veyra.data.LibraryAcquisitionView) {
            val paused = view.acquisition.physicalDownloads.getValue(pauseJob)
            assertEquals(DownloadStatus.PAUSED, paused.status)
            assertTrue(paused.userPaused)
            assertEquals(DownloadBatchIntent.PAUSED,
                view.acquisition.claims.values.single { it.physicalJobId == pauseJob }.intent)
            assertEquals(2, paused.automaticRetryCount)
            assertEquals(retryDeadline, paused.nextRetryEpochMs,
                "Typed user intent is separate from the persisted scheduling deadline")
            val cancelled = view.acquisition.physicalDownloads.getValue(cancelJob)
            assertEquals(DownloadStatus.CANCELLED, cancelled.status)
            assertTrue(cancelled.userPaused)
            assertTrue(cancelled.cleanupPending)
            assertEquals(DownloadBatchIntent.CANCELLED,
                view.acquisition.claims.values.single { it.physicalJobId == cancelJob }.intent)
            assertEquals(2, cancelled.automaticRetryCount)
            val removed = view.acquisition.physicalDownloads.getValue(removeJob)
            assertEquals(DownloadStatus.CANCELLED, removed.status)
            assertTrue(removed.userPaused)
            assertTrue(removed.cleanupPending)
            assertTrue(removed.removeWhenClean)
            assertEquals(2, removed.automaticRetryCount)
        }
        assertIntent(fixture.store.acquisitionView())
        fixture.assertSchedulingOnly()
        fixture.close()

        val reopened = newFixture(initialize = false, clock = clock)
        reopened.app.initializeScheduling()
        assertIntent(reopened.store.acquisitionView())
        reopened.assertSchedulingOnly()
        assertFalse(Files.exists(directory.resolve("media")),
            "Remove intent remains durable until an activated native maintenance owner acknowledges cleanup")
    }

    @Test fun musicAndPodcastPauseCancelRemoveRemainUsableAfterCoverageFailureButPhysicalCleanupWaitsForExplicitActivation() = checkCase {
        val fixture = newFixture(initialize = false)
        val music = listOf("pause", "cancel", "remove").map { item(SourceProvider.COMMONS, "restricted-music-$it") }
        val podcasts = listOf("pause", "cancel", "remove").map { item(id = "restricted-podcast-$it") }
        val musicSelections = music.map { fixture.seedSelection(it) }
        val podcastSelections = podcasts.map { fixture.seedSelection(it) }
        val now = fixture.clockRef.epochMs()
        val musicPause = fixture.seedAttemptJob(musicSelections[0], now, "restricted-music-pause-download")
        val musicCancel = fixture.seedAttemptJob(musicSelections[1], now, "restricted-music-cancel-download")
        val musicRemove = fixture.seedCompleteJob(musicSelections[2], now, "restricted-music-remove-download")
        val podcastPause = fixture.seedAttemptJob(podcastSelections[0], now, "restricted-podcast-pause-download")
        val podcastCancel = fixture.seedAttemptJob(podcastSelections[1], now, "restricted-podcast-cancel-download")
        val podcastRemove = fixture.seedCompleteJob(podcastSelections[2], now, "restricted-podcast-remove-download")
        val removable = listOf(musicRemove, podcastRemove)
        val removeAssets = removable.associateWith { assertNotNull(fixture.asset(it)) }
        val denied = AppFailure(FailureCode.BACKGROUND_RESTRICTED, "authored_scheduler_install_failure")
        fixture.platform.ensureCoverage = { throw VeyraException(denied) }
        fixture.app.initialize()
        assertEquals(AppInitialization.Ready, fixture.app.initialization.value)
        assertEquals(denied, fixture.app.downloadRestriction.value)
        fixture.app.onMusicAction(MusicUiAction.PauseDownload(musicPause))
        fixture.app.onMusicAction(MusicUiAction.CancelDownload(musicCancel))
        fixture.app.onMusicAction(MusicUiAction.RemoveDownload(musicRemove))
        fixture.app.onPodcastAction(PodcastAction.PauseDownload(podcastPause))
        fixture.app.onPodcastAction(PodcastAction.CancelDownload(podcastCancel))
        fixture.app.onPodcastAction(PodcastAction.RemoveDownload(podcastRemove))
        val pausedIds = listOf(musicPause, podcastPause).toSet()
        val cancelledIds = listOf(musicCancel, podcastCancel).toSet()
        val removedIds = removable.toSet()
        val intent = fixture.store.acquisitionViews.first { view ->
            pausedIds.all { id -> view.acquisition.physicalDownloads[id]?.let { it.status == DownloadStatus.PAUSED && it.userPaused } == true } &&
                (cancelledIds + removedIds).all { id -> view.acquisition.physicalDownloads[id]?.let { job ->
                    job.status == DownloadStatus.CANCELLED && job.userPaused && job.cleanupPending &&
                        job.removeWhenClean == (id in removedIds)
                } == true }
        }.acquisition
        assertEquals(6, intent.physicalDownloads.size)
        removeAssets.forEach { (id, asset) ->
            assertEquals(asset, intent.materializations.getValue(assertNotNull(intent.physicalDownloads.getValue(id).assetId)))
            assertContentEquals(NON_AUDIO, Files.readAllBytes(Path.of(asset.uri)))
        }
        cancelledIds.forEach { assertNull(fixture.asset(it), "Cancelled unfinished jobs cannot own a published asset") }
        fixture.assertNoDownloadExecution()
        assertEquals(1, fixture.platform.ensureCoverageCalls.get())
        assertEquals(0, fixture.platform.coverageAcknowledgements.get())

        fixture.platform.ensureCoverage = {}
        assertTrue(fixture.app.activateUserDownloads())
        assertNull(fixture.app.downloadRestriction.value)
        fixture.store.acquisitionViews.first { view ->
            removedIds.none { id -> view.acquisition.physicalDownloads[id]?.assetId != null } &&
                removedIds.all { id -> view.acquisition.physicalDownloads[id]?.let { !it.cleanupPending && it.assetId == null } == true } &&
                cancelledIds.all { id -> view.acquisition.physicalDownloads[id]?.let { !it.cleanupPending && it.status == DownloadStatus.CANCELLED } == true }
        }
        fixture.awaitDownloadIdle()
        assertEquals(AppInitialization.Ready, fixture.app.initialization.value)
        val final = fixture.store.acquisitionView().acquisition
        assertTrue(pausedIds.all { final.physicalDownloads[it]?.let { job -> job.status == DownloadStatus.PAUSED && job.userPaused } == true })
        assertTrue(cancelledIds.all { final.physicalDownloads[it]?.status == DownloadStatus.CANCELLED })
        removeAssets.forEach { (id, asset) ->
            assertFalse(Files.exists(Path.of(asset.uri)))
            assertNull(final.materializations[asset.id])
            assertEquals(asset.receipt(), final.materializationReceipts[asset.id],
                "The immutable receipt survives live-file removal")
            val job = assertNotNull(final.physicalDownloads[id])
            assertNull(job.assetId)
            assertNull(job.stagingPath)
            assertFalse(job.cleanupPending)
        }
        cancelledIds.forEach { id ->
            val job = assertNotNull(final.physicalDownloads[id])
            assertNull(job.assetId)
            assertNull(job.stagingPath)
        }
        assertEquals(2, fixture.platform.ensureCoverageCalls.get())
        assertEquals(1, fixture.platform.coverageAcknowledgements.get())
        assertTrue(fixture.platform.gateCoverageAcknowledgements.isNotEmpty())
        assertTrue(fixture.platform.gateCoverageAcknowledgements.all { it == 1 })
        assertTrue(fixture.platform.validations.isEmpty(), "Deleting a cancelled asset is not an integrity validation")
        assertTrue(fixture.platform.engine.preparations.isEmpty())
        assertEquals(0, fixture.platform.runtimePrepareCalls.get())
        assertTrue(fixture.gateway.queries.isEmpty())
        assertTrue(fixture.gateway.resolutions.isEmpty())
        assertTrue(fixture.gateway.transfers.isEmpty())
        fixture.assertNoNetwork()
    }

    @Test fun acceptedCoveragePrecedesEveryGateAndTheExistingDurableQueueExecutesOnlyAfterThatAck() = checkCase {
        val seed = newFixture(initialize = false)
        val original = item(title = "Persisted complete requested title — طابور التنزيل المحفوظ")
        val selection = seed.seedSelection(original)
        val jobId = seed.seedJob(selection, seed.clockRef.epochMs(), "coverage-durable-download")
        seed.close()
        val fixture = newFixture(initialize = false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.platform.ensureCoverage = { entered.complete(Unit); release.await() }
        fixture.platform.acquireDownload = {
            assertEquals(1, fixture.platform.coverageAcknowledgements.get(),
                "Native allowance cannot precede durable coverage ACK")
            DownloadExecutionLease {}
        }
        fixture.gateway.onDownload = { transfer ->
            assertEquals(1, fixture.platform.coverageAcknowledgements.get())
            assertTrue(fixture.platform.downloadGateCalls.get() > 0)
            writeFakeTransfer(transfer)
        }
        coroutineScope {
            val initializing = async { fixture.app.initialize() }
            try {
                entered.await()
                assertEquals(AppInitialization.Ready, fixture.app.initialization.value)
                assertFalse(initializing.isCompleted)
                assertEquals(DownloadStatus.QUEUED, fixture.store.acquisitionView()
                    .acquisition.physicalDownloads.getValue(jobId).status)
                fixture.assertNoDownloadExecution()
                release.complete(Unit)
                fixture.platform.coverageAcknowledged.await()
                initializing.await()
            } finally { release.complete(Unit) }
        }
        val view = withTimeout(20_000) {
            var current = fixture.store.acquisitionViews.value
            while (current.acquisition.physicalDownloads.none { it.value.status == DownloadStatus.COMPLETE && it.value.assetId != null }) {
                delay(10)
                current = fixture.store.acquisitionViews.value
            }
            current
        }
        val state = view.acquisition
        val job = state.physicalDownloads.getValue(jobId)
        val asset = state.materializations.getValue(assertNotNull(job.assetId))
        val accepted = state.requests.values.single { it.state == AcquisitionJobState.COMMITTED }
        val single = assertIs<AcquisitionOutcome.Single>(accepted.outcome)
        fixture.awaitDownloadIdle()
        assertNull(fixture.app.downloadRestriction.value)
        assertEquals(original.title, accepted.request.completeTitle)
        assertEquals(original.title, single.let {
            (state.receipts.getValue(it.receiptId) as FirstResultReceipt.Observed).observation.queryTitle
        })
        assertEquals(selection.bindingId, job.producer.bindingId)
        assertEquals(selection.bindingId, asset.producer.bindingId)
        assertEquals(single.bindingId, job.producer.bindingId)
        assertEquals(selection.bindingId.value, fixture.gateway.transfers.single().binding.id)
        assertEquals(original.title, fixture.gateway.transfers.single().persisted?.queryTitle)
        assertTrue(fixture.gateway.transfers.single().completed)
        assertEquals(1, fixture.gateway.typedCompletions.size)
        assertContentEquals(NON_AUDIO, Files.readAllBytes(Path.of(asset.uri)))
        assertTrue(state.assetUses.any {
            it.requestedBindingId == selection.bindingId && it.materializationAssetId == asset.id
        })
        assertTrue(fixture.gateway.queries.isEmpty(), "Activated typed execution reuses the accepted receipt, never re-searches")
        assertEquals(1, fixture.gateway.typedTransfers.size)
        assertEquals(1, fixture.platform.ensureCoverageCalls.get())
        assertEquals(1, fixture.platform.coverageAcknowledgements.get())
        assertTrue(fixture.platform.gateCoverageAcknowledgements.isNotEmpty())
        assertTrue(fixture.platform.gateCoverageAcknowledgements.all { it == 1 })
        assertTrue(fixture.platform.engine.preparations.isEmpty())
        fixture.assertNoNetwork()
    }

    @Test fun explicitCoverageRetryPreservesUserPauseAndFutureBackoffUntilItsOriginalDeadline() = checkCase {
        val clock = TestClock()
        val fixture = newFixture(initialize = false, clock = clock)
        val pausedItem = item(SourceProvider.COMMONS, "retry-stays-user-paused")
        val futureItem = item(id = "retry-resumes-at-future-deadline")
        val deadline = clock.epochMs() + 60_000
        val pausedSelection = fixture.seedSelection(pausedItem)
        val futureSelection = fixture.seedSelection(futureItem)
        val now = clock.epochMs()
        val paused = fixture.seedAttemptJob(pausedSelection, now, "retry-paused-download",
            status = DownloadStatus.PAUSED, attemptStatus = DownloadAttemptStatus.PAUSED,
            nextRetryEpochMs = deadline + 30_000, automaticRetryCount = 2,
            intent = DownloadBatchIntent.PAUSED)
        // A real typed backoff is WAITING_NETWORK + the original deadline; NEEDS_FOREGROUND is
        // a host restriction, not an automatic retry slot. Explicit foreground activation must
        // not silently clear this independent future deadline.
        val future = fixture.seedAttemptJob(futureSelection, now, "retry-future-download",
            status = DownloadStatus.WAITING_NETWORK, attemptStatus = DownloadAttemptStatus.FAILED,
            failure = AppFailure(FailureCode.NETWORK_UNAVAILABLE), nextRetryEpochMs = deadline,
            automaticRetryCount = 3)
        val pausedBefore = fixture.job(paused)
        val futureBefore = fixture.job(future)
        assertEquals(deadline + 30_000, pausedBefore.nextRetryEpochMs)
        assertEquals(2, pausedBefore.automaticRetryCount)
        assertEquals(deadline, futureBefore.nextRetryEpochMs)
        assertEquals(3, futureBefore.automaticRetryCount)
        assertEquals(DownloadStatus.WAITING_NETWORK, futureBefore.status)
        fixture.gateway.onDownload = ::writeFakeTransfer
        fixture.platform.ensureCoverage = { throw VeyraException(AppFailure(FailureCode.BACKGROUND_RESTRICTED)) }
        fixture.app.initialize()
        assertEquals(AppInitialization.Ready, fixture.app.initialization.value)
        assertNotNull(fixture.app.downloadRestriction.value)
        fixture.assertNoDownloadExecution()
        fixture.app.initialize()
        assertEquals(1, fixture.platform.ensureCoverageCalls.get(), "An ordinary repeat initialize is not an explicit coverage retry")

        fixture.platform.ensureCoverage = {}
        assertTrue(fixture.app.activateUserDownloads())
        assertNull(fixture.app.downloadRestriction.value)
        val activated = fixture.store.acquisitionViews.first { view ->
            view.acquisition.physicalDownloads.getValue(future) == futureBefore
        }.acquisition
        assertEquals(pausedBefore, activated.physicalDownloads.getValue(paused), "Foreground activation must not reinterpret a user pause")
        assertEquals(DownloadBatchIntent.PAUSED,
            activated.claims.values.single { it.physicalJobId == paused }.intent)
        val deferred = fixture.awaitDownloadIdle()
        assertEquals(deadline, deferred.plan.nextNetworkEpochMs)
        assertNull(deferred.plan.nextLocalEpochMs)
        assertFalse(deferred.plan.hasDueWork)
        // Bounded negative observation across the real coordinator's 1 s poll. The
        // completion after advancing the injected clock below is the positive control.
        delay(1_100)
        fixture.assertNoDownloadExecution()
        clock.advance(59_999)
        assertEquals(deadline, fixture.app.downloads.schedulingPlan().nextNetworkEpochMs)
        assertFalse(fixture.app.downloads.schedulingPlan().hasDueWork)
        fixture.assertNoDownloadExecution()
        clock.advance(1)
        fixture.store.acquisitionViews.first { view ->
            view.acquisition.physicalDownloads.getValue(future).status == DownloadStatus.COMPLETE
        }
        fixture.awaitDownloadIdle()
        val final = fixture.store.acquisitionView().acquisition
        assertEquals(pausedBefore, final.physicalDownloads.getValue(paused))
        assertEquals(DownloadStatus.COMPLETE, final.physicalDownloads.getValue(future).status)
        assertEquals(futureSelection.bindingId.value, fixture.gateway.transfers.single().binding.id)
        assertEquals(futureItem.title, fixture.gateway.transfers.single().persisted?.queryTitle)
        assertTrue(fixture.gateway.transfers.single().completed)
        assertEquals(1, fixture.gateway.typedCompletions.size)
        assertTrue(fixture.gateway.queries.isEmpty(), "Activated typed execution reuses the accepted receipt, never re-searches")
        assertEquals(2, fixture.platform.ensureCoverageCalls.get())
        assertEquals(1, fixture.platform.coverageAcknowledgements.get())
        assertTrue(fixture.platform.gateCoverageAcknowledgements.all { it == 1 })
        assertTrue(fixture.platform.engine.preparations.isEmpty())
        fixture.assertNoNetwork()
    }

    @Test fun unknownAndDisconnectedPlatformNetworkPreventNewNetworkClaimsUntilConnected() = checkCase {
        val fixture = newFixture(initialize = false)
        fixture.platform.downloadNetworkState.value = DownloadNetworkState.UNKNOWN
        fixture.gateway.onDownload = { transfer ->
            assertEquals(DownloadNetworkState.CONNECTED, fixture.platform.downloadNetworkState.value)
            writeFakeTransfer(transfer)
        }
        fixture.app.initialize()
        assertNull(fixture.app.downloadRestriction.value, "Accepted coverage and connectivity are different eligibility checks")
        val original = item(SourceProvider.COMMONS, "connectivity-gated-download")
        fixture.store.upsertCatalog(listOf(original)).getOrThrow()
        fixture.app.onMusicAction(MusicUiAction.Download(original))
        val admitted = fixture.store.acquisitionViews.first { view ->
            view.acquisition.physicalDownloads.values.any { it.producer.subjectId.value == original.id }
        }.acquisition
        val job = admitted.physicalDownloads.values.single()
        val bindingId = assertNotNull(job.producer.bindingId)
        val receipt = admitted.receipts.getValue(admitted.bindings.getValue(bindingId).firstReceiptId) as FirstResultReceipt.Observed
        assertEquals(original.title, receipt.observation.queryTitle)
        for (network in listOf(DownloadNetworkState.UNKNOWN, DownloadNetworkState.DISCONNECTED)) {
            fixture.platform.downloadNetworkState.value = network
            val plan = fixture.app.downloads.schedulingPlan()
            assertEquals(0L, plan.nextNetworkEpochMs, "Disconnected intent retains its already-due network wake")
            assertNull(plan.nextLocalEpochMs)
            assertFalse(plan.hasDueWork)
            assertEquals(FailureCode.NETWORK_UNAVAILABLE, plan.blockedReason?.code)
            assertIs<DownloadIdleDecision.Deferred>(fixture.app.downloads.idleDecision())
            // A real poll interval, plus a fresh authoritative plan/Room read; not just an
            // immediate zero counter. CONNECTED completion below proves the worker was live.
            delay(1_100)
            val waiting = fixture.store.acquisitionView().acquisition
            assertEquals(DownloadStatus.QUEUED, waiting.physicalDownloads.getValue(job.id).status)
            assertTrue(waiting.physicalAttempts.isEmpty())
            assertTrue(fixture.store.state.value.downloads.isEmpty(), "Typed intent is not a legacy download row")
            assertTrue(fixture.store.state.value.playbackBindings.isEmpty(),
                "The typed receipt is not a legacy compatibility binding")
            assertEquals(1, fixture.gateway.queries.size, "Only the admitted selection queried; no execution search ran")
            assertTrue(fixture.gateway.transfers.isEmpty())
            assertEquals(0, fixture.platform.downloadGateCalls.get())
            assertTrue(fixture.platform.gateCoverageAcknowledgements.isEmpty())
            assertTrue(fixture.platform.engine.preparations.isEmpty())
            assertTrue(fixture.gateway.resolutions.isEmpty())
            fixture.assertNoNetwork()
        }
        fixture.platform.downloadNetworkState.value = DownloadNetworkState.CONNECTED
        fixture.store.acquisitionViews.first { view ->
            view.acquisition.physicalDownloads.getValue(job.id).status == DownloadStatus.COMPLETE
        }
        fixture.awaitDownloadIdle()
        assertEquals(listOf(original.title), fixture.gateway.queries.toList())
        assertEquals(bindingId.value, fixture.gateway.transfers.single().binding.id)
        assertEquals(original.title, fixture.gateway.transfers.single().persisted?.queryTitle)
        assertTrue(fixture.gateway.transfers.single().completed)
        assertEquals(1, fixture.gateway.typedCompletions.size)
        assertEquals(1, fixture.platform.ensureCoverageCalls.get())
        assertEquals(1, fixture.platform.coverageAcknowledgements.get())
        assertTrue(fixture.platform.gateCoverageAcknowledgements.isNotEmpty())
        assertTrue(fixture.platform.gateCoverageAcknowledgements.all { it == 1 })
        assertTrue(fixture.platform.engine.preparations.isEmpty())
        fixture.assertNoNetwork()
    }

    /**
     * The current typed prepared-download contract creates NO DOWNLOAD PlaybackResource; the
     * live owner handed to execution/cleanup is the native DownloadExecutionLease. The old
     * resource-lifetime assertions map to that exact dependent owner: the transfer and native
     * validation succeed and publish first, then the allowance close fails, every later
     * dependent stage (publication/path/slot/dependent clients) is withheld, and only an explicit
     * close retry may consume it. The transfer is never replayed.
     */
    @Test fun handedDownloadResourceFailureRetainsRoomRuntimeClientsAndLifetimeUntilExplicitCloseRetry() = checkCase {
        val fixture = newFixture()
        assertFalse(fixture.app.dependencyReleaseSafeAfterClose, "A never-closed application has no release receipt")
        val allowRelease = AtomicBoolean()
        val consumed = CompletableDeferred<Unit>()
        val allowanceCloses = AtomicInteger()
        fixture.platform.acquireDownload = {
            DownloadExecutionLease {
                allowanceCloses.incrementAndGet()
                if (!allowRelease.get()) throw IllegalStateException("authored private download owner was not consumed")
                consumed.complete(Unit)
            }
        }
        fixture.gateway.onDownload = ::writeFakeTransfer
        val original = item(SourceProvider.COMMONS, "retained-download-resource")
        fixture.store.upsertCatalog(listOf(original)).getOrThrow()
        try {
            fixture.app.onMusicAction(MusicUiAction.Download(original))
            fixture.app.downloads.lastFailure.first { it == DOWNLOAD_CLEANUP_FAILURE }
            val view = fixture.store.acquisitionView().acquisition
            val job = view.physicalDownloads.values.single()
            val asset = view.materializations.getValue(assertNotNull(job.assetId))
            assertEquals(DownloadStatus.COMPLETE, job.status)
            assertContentEquals(NON_AUDIO, Files.readAllBytes(Path.of(asset.uri)))
            assertEquals(1, allowanceCloses.get())
            assertEquals(1, fixture.gateway.transfers.size, "A cleanup retry may not replay the transfer")
            assertTrue(fixture.gateway.transfers.single().completed, "The consumed transfer itself must have returned")
            assertEquals(1, fixture.gateway.typedCompletions.size)
            assertEquals(1, fixture.platform.localValidations.get(), "Successful native validation is not repeated")
            assertFalse(consumed.isCompleted)
            assertEquals(DOWNLOAD_CLEANUP_FAILURE,
                assertFailsWith<VeyraException> { fixture.app.downloads.drainForBackground() }.failure)

            val first = assertFailsWith<VeyraException> { fixture.app.close() }
            fixture.expectedCloseFailure = first.failure
            assertEquals(DOWNLOAD_CLEANUP_FAILURE, first.failure)
            assertNull(first.cause)
            assertTrue(first.suppressedExceptions.isEmpty())
            assertFalse(first.toString().contains("private download owner"))
            assertEquals(2, allowanceCloses.get(), "This explicit close retries the already failed owner once")
            fixture.assertDownloadCleanupRetainsDependencies()
            assertEquals(asset, fixture.store.acquisitionView().acquisition.materializations[asset.id])
            assertEquals(listOf(original.title), fixture.gateway.queries.toList())
            assertEquals(1, fixture.gateway.typedTransfers.size, "Cleanup retries may not replay the transfer")

            allowRelease.set(true)
            val afterDrain = assertFailsWith<VeyraException> { fixture.app.close() }
            assertEquals(first.failure, afterDrain.failure, "Later consumption cannot certify the earlier failed close")
            consumed.await()
            assertEquals(3, allowanceCloses.get())
            assertEquals(1, fixture.gateway.typedTransfers.size)
            fixture.assertDownloadCleanupDependenciesClosedOnce()
            assertEquals(first.failure, assertFailsWith<VeyraException> { fixture.app.close() }.failure)
            assertEquals(3, allowanceCloses.get())
            assertEquals(1, fixture.platform.localValidations.get())
            fixture.assertDownloadCleanupDependenciesClosedOnce()
            fixture.assertNoNetwork()
        } finally { allowRelease.set(true) }
    }

    /** Maps the old successful resource stage to the native validation/transfer that must not repeat. */
    @Test fun throwingDownloadAllowanceRetainsDependenciesWithoutReclosingTheAlreadyConsumedResource() = checkCase {
        val fixture = newFixture()
        assertFalse(fixture.app.dependencyReleaseSafeAfterClose, "A never-closed application has no release receipt")
        val allowRelease = AtomicBoolean()
        val allowanceCloses = AtomicInteger()
        val consumed = CompletableDeferred<Unit>()
        fixture.platform.acquireDownload = {
            DownloadExecutionLease {
                allowanceCloses.incrementAndGet()
                if (!allowRelease.get()) throw AssertionError("authored private native allowance failure")
                consumed.complete(Unit)
            }
        }
        fixture.gateway.onDownload = ::writeFakeTransfer
        val original = item(SourceProvider.COMMONS, "retained-download-allowance")
        fixture.store.upsertCatalog(listOf(original)).getOrThrow()
        try {
            fixture.app.onMusicAction(MusicUiAction.Download(original))
            fixture.app.downloads.lastFailure.first { it == DOWNLOAD_CLEANUP_FAILURE }
            assertEquals(1, allowanceCloses.get())
            assertEquals(1, fixture.platform.localValidations.get(), "The successful earlier native stage must never be repeated")
            assertEquals(1, fixture.gateway.transfers.size)
            assertTrue(fixture.gateway.transfers.single().completed)
            assertEquals(1, fixture.gateway.typedCompletions.size)
            val first = assertFailsWith<VeyraException> { fixture.app.close() }
            fixture.expectedCloseFailure = first.failure
            assertEquals(DOWNLOAD_CLEANUP_FAILURE, first.failure)
            assertNull(first.cause)
            assertTrue(first.suppressedExceptions.isEmpty())
            assertFalse(first.toString().contains("private native allowance"))
            assertEquals(2, allowanceCloses.get())
            assertEquals(1, fixture.platform.localValidations.get())
            fixture.assertDownloadCleanupRetainsDependencies()

            allowRelease.set(true)
            assertEquals(first.failure, assertFailsWith<VeyraException> { fixture.app.close() }.failure)
            consumed.await()
            assertEquals(3, allowanceCloses.get())
            assertEquals(1, fixture.platform.localValidations.get(), "The successful earlier stage must never be repeated")
            fixture.assertDownloadCleanupDependenciesClosedOnce()
            assertEquals(first.failure, assertFailsWith<VeyraException> { fixture.app.close() }.failure)
            assertEquals(3, allowanceCloses.get())
            assertEquals(1, fixture.platform.localValidations.get())
            assertEquals(listOf(original.title), fixture.gateway.queries.toList())
            assertEquals(1, fixture.gateway.transfers.size)
            assertEquals(1, fixture.gateway.typedTransfers.size)
            fixture.assertDownloadCleanupDependenciesClosedOnce()
            fixture.assertNoNetwork()
        } finally { allowRelease.set(true) }
    }

    @Test fun cancelledAppCloseCallerCannotSkipRetainedDownloadCleanupOrItsDelayedDependencyDrain() = checkCase {
        val fixture = newFixture()
        assertFalse(fixture.app.dependencyReleaseSafeAfterClose, "A never-closed application has no release receipt")
        val retryEntered = CompletableDeferred<Unit>()
        val permitConsumption = CompletableDeferred<Unit>()
        val closeResult = CompletableDeferred<Throwable?>()
        val cleanupActive = ConcurrentLinkedQueue<Boolean>()
        val allowanceCloses = AtomicInteger()
        val consumed = CompletableDeferred<Unit>()
        fixture.platform.acquireDownload = {
            DownloadExecutionLease {
                cleanupActive += currentCoroutineContext().isActive
                val attempt = allowanceCloses.incrementAndGet()
                if (attempt == 1) throw CancellationException("authored close returned cancellation without consuming owner")
                retryEntered.complete(Unit)
                permitConsumption.await()
                assertTrue(currentCoroutineContext().isActive)
                assertFalse(fixture.app.dependencyReleaseSafeAfterClose, "A close still consuming its owner cannot publish an ACK")
                assertIs<StoreStatus.Ready>(fixture.store.status.value)
                assertEquals(0, fixture.platform.runtimeCloseCalls.get())
                assertEquals(0, fixture.platform.youtubeCloseCalls.get())
                assertEquals(0, fixture.http.closeCalls.get())
                consumed.complete(Unit)
            }
        }
        fixture.gateway.onDownload = ::writeFakeTransfer
        val original = item(SourceProvider.COMMONS, "cancelled-caller-download-cleanup")
        fixture.store.upsertCatalog(listOf(original)).getOrThrow()
        try {
            fixture.app.onMusicAction(MusicUiAction.Download(original))
            fixture.app.downloads.lastFailure.first { it == DOWNLOAD_CLEANUP_FAILURE }
            assertEquals(1, allowanceCloses.get())
            supervisorScope {
                val closing = launch { closeResult.complete(runCatching { fixture.app.close() }.exceptionOrNull()) }
                try {
                    retryEntered.await()
                    closing.cancel(CancellationException("authored external close caller cancelled"))
                    assertTrue(closing.isCancelled)
                    assertFalse(closing.isCompleted, "Cancellation cannot acknowledge a still-waiting native owner")
                    assertFalse(closeResult.isCompleted)
                    assertFalse(consumed.isCompleted)
                    assertEquals(2, allowanceCloses.get())
                    assertEquals(1, fixture.platform.localValidations.get())
                    fixture.assertDownloadCleanupRetainsDependencies(expectedEngineCloseCalls = 0)
                } finally { permitConsumption.complete(Unit) }
                closing.join()
            }
            val first = assertIs<VeyraException>(closeResult.await())
            fixture.expectedCloseFailure = first.failure
            assertEquals(DOWNLOAD_CLEANUP_FAILURE, first.failure)
            assertNull(first.cause)
            assertTrue(first.suppressedExceptions.isEmpty())
            assertFalse(first.toString().contains("authored close"))
            consumed.await()
            assertEquals(listOf(true, true), cleanupActive.toList())
            assertEquals(2, allowanceCloses.get())
            assertEquals(1, fixture.platform.localValidations.get(), "Consumed earlier stages are never repeated")
            assertTrue(fixture.gateway.transfers.single().completed)
            assertEquals(1, fixture.gateway.typedCompletions.size)
            fixture.assertDownloadCleanupDependenciesClosedOnce()
            assertEquals(first.failure, assertFailsWith<VeyraException> { fixture.app.close() }.failure)
            assertEquals(2, allowanceCloses.get())
            assertEquals(1, fixture.gateway.transfers.size)
            fixture.assertDownloadCleanupDependenciesClosedOnce()
            fixture.assertNoNetwork()
        } finally { permitConsumption.complete(Unit) }
    }

    @Test fun catalogueRootActionsUseCommittedSavedFramesAndUndoSurvivesUnrelatedScalarCommit() = checkCase {
        val fixture = newFixture()
        val track = item(SourceProvider.COMMONS, id = "catalog-action")
        fixture.store.upsertCatalog(listOf(track)).getOrThrow()
        fixture.app.onMusicAction(MusicUiAction.ToggleFavorite(track))
        withTimeout(5_000) { fixture.store.state.first { track.id in it.favoriteIds } }
        withTimeout(5_000) { fixture.app.library.first { track.id in it.favoriteIds } }

        // Every playlist mutation uses the same committed metadata/saved owner; duplicate
        // occurrence identity is preserved and is never reconstructed from a projected slice.
        fixture.app.onMusicAction(MusicUiAction.CreatePlaylist("Authored playlist — قائمة", track))
        val created = withTimeout(5_000) { fixture.store.state.first { snapshot -> snapshot.playlists.any { it.entries.any { entry -> entry.mediaId == track.id } } } }
        val playlist = created.playlists.single { it.entries.any { entry -> entry.mediaId == track.id } }
        fixture.app.onMusicAction(MusicUiAction.AddToPlaylist(playlist.id, track, allowDuplicate = true))
        val duplicated = withTimeout(5_000) { fixture.store.state.first { snapshot ->
            snapshot.playlists.single { it.id == playlist.id }.entries.count { it.mediaId == track.id } == 2
        } }
        val entries = duplicated.playlists.single { it.id == playlist.id }.entries
        assertEquals(2, entries.size)
        fixture.app.onMusicAction(MusicUiAction.RenamePlaylist(playlist.id, "Renamed playlist — قائمة"))
        fixture.app.onMusicAction(MusicUiAction.MovePlaylistEntry(playlist.id, entries.first().id, 1))
        withTimeout(5_000) { fixture.store.state.first { snapshot ->
            snapshot.playlists.single { it.id == playlist.id }.entries.last().id == entries.first().id
        } }
        fixture.app.onMusicAction(MusicUiAction.RemovePlaylistEntry(playlist.id, entries.last().id))
        withTimeout(5_000) { fixture.store.state.first { snapshot ->
            snapshot.playlists.single { it.id == playlist.id }.entries.size == 1
        } }

        // An unrelated same-owner scalar transaction advances the database but must not invalidate
        // the opaque saved-frame receipt.
        fixture.store.update { it.copy(settings = it.settings.copy(theme = ThemeMode.LIGHT)) }.getOrThrow()
        assertTrue(fixture.app.musicOptions.value.canUndoLibrary)
        fixture.app.onMusicAction(MusicUiAction.UndoLibrary)
        withTimeout(5_000) { fixture.store.state.first { snapshot ->
            snapshot.playlists.single { it.id == playlist.id }.entries.size == 2
        } }

        fixture.app.onMusicAction(MusicUiAction.DeletePlaylist(playlist.id))
        withTimeout(5_000) { fixture.store.state.first { snapshot -> snapshot.playlists.none { it.id == playlist.id } } }
        assertFalse(fixture.store.state.value.playlists.any { it.id == playlist.id })
        assertEquals(1, fixture.store.state.value.favoriteIds.count { it == track.id })
    }

    @Test fun freshMusicDownloadCapturesCompleteTitleSelectsRawFirstAndPersistsBeforeOwnedExecution() = checkCase {
        val fixture = newFixture()
        val track = item(SourceProvider.COMMONS, id = "fresh-download")
        fixture.store.upsertCatalog(listOf(track)).getOrThrow()
        val firstTitle = "Actual raw ordinal-zero recording"
        fixture.gateway.onFirst = { title ->
            KmpFirstResult(title, FirstResultKind.VIDEO, FIRST, firstTitle, "https://www.youtube.com/watch?v=$FIRST")
        }
        fixture.gateway.onDownload = { transfer -> writeFakeTransfer(transfer) }
        fixture.app.onMusicAction(MusicUiAction.Download(track))
        val view = try {
            withTimeout(15_000) {
                fixture.store.acquisitionViews.first { view ->
                    view.acquisition.physicalDownloads.values.any { it.status == DownloadStatus.COMPLETE && it.assetId != null }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            fail("download timeout notice=${fixture.app.notice.value} init=${fixture.app.initialization.value} " +
                "physical=${fixture.store.acquisitionViews.value.acquisition.physicalDownloads.values.map { it.status to it.failure }} " +
                "requests=${fixture.store.acquisitionViews.value.acquisition.requests} queries=${fixture.gateway.queries}")
        }
        val state = view.acquisition
        val physical = state.physicalDownloads.values.single { it.status == DownloadStatus.COMPLETE }
        val accepted = state.requests.values.single { it.state == AcquisitionJobState.COMMITTED }
        val single = assertIs<AcquisitionOutcome.Single>(accepted.outcome)
        assertEquals(track.title, accepted.request.completeTitle)
        assertEquals((accepted.request.metadata as MediaCapture).media.id, track.id)
        assertEquals(0, accepted.request.completeTitle.indexOf("Complete requested title"))
        assertEquals(single.bindingId, physical.producer.bindingId)
        assertEquals(single.receiptId, accepted.request.let { state.receipts[single.receiptId]?.id })
        assertEquals(listOf(track.title), fixture.gateway.queries.toList(), "Exactly one unchanged complete-title first-result query")
        assertEquals(1, fixture.gateway.typedTransfers.size, "The same accepted recording executes through the existing kmpYT plan/transfer")
        assertEquals(1, fixture.gateway.typedCompletions.size)
        val asset = state.materializations.getValue(assertNotNull(physical.assetId))
        assertTrue(state.assetUses.any { it.requestedBindingId == single.bindingId && it.materializationAssetId == asset.id })
        assertTrue(Files.exists(Path.of(asset.uri)))
        assertTrue(fixture.platform.youtubeRequests.isEmpty(), "No source-side search substituted the complete-title handoff")
        fixture.app.onMusicAction(MusicUiAction.RemoveDownload(physical.id))
        withTimeout(10_000) {
            fixture.store.acquisitionViews.first {
                val removed = it.acquisition.physicalDownloads[physical.id]
                removed == null || (removed.cleanupPending == false && removed.assetId == null && removed.stagingPath == null)
            }
        }
        assertFalse(Files.exists(Path.of(asset.uri)))
        assertFalse(view.snapshot.settings.offlineOnly)
    }

    @Test fun downloadTapFreezesTheDisplayedCompleteTitleAcrossAHeldAdmissionAndMetadataNavigationChanges() = checkCase {
        val fixture = newFixture()
        val displayed = item(SourceProvider.COMMONS, id = "tap-time-metadata",
            title = "Displayed complete title at tap — العنوان الظاهر عند اللمس")
        fixture.store.upsertCatalog(listOf(displayed)).getOrThrow()
        fixture.gateway.onDownload = { transfer -> writeFakeTransfer(transfer) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.gateway.beforeAcquire = { entered.complete(Unit); release.await() }
        try {
            fixture.app.onMusicAction(MusicUiAction.Download(displayed))
            entered.await()
            // The immutable capture is already committed and the raw first-result query is held
            // here. A catalogue metadata update and unrelated navigation must not rebase it.
            val changed = displayed.copy(title = "Changed catalogue title after tap — عنوان لاحق")
            fixture.store.upsertCatalog(listOf(changed)).getOrThrow()
            fixture.app.onMusicAction(MusicUiAction.OpenMedia(changed))
            release.complete(Unit)
            val view = withTimeout(20_000) {
                var current = fixture.store.acquisitionViews.value
                while (current.acquisition.physicalDownloads.values.none { it.status == DownloadStatus.COMPLETE && it.assetId != null }) {
                    delay(10)
                    current = fixture.store.acquisitionViews.value
                }
                current
            }
            val state = view.acquisition
            val accepted = state.requests.values.single { it.state == AcquisitionJobState.COMMITTED }
            val single = assertIs<AcquisitionOutcome.Single>(accepted.outcome)
            val media = (accepted.request.metadata as MediaCapture).media
            assertEquals(displayed.title, accepted.request.completeTitle,
                "The durable request must keep the complete title displayed at tap time")
            assertEquals(displayed, media, "Every captured displayed input must stay detached/unchanged")
            val receipt = assertIs<FirstResultReceipt.Observed>(state.receipts.getValue(single.receiptId))
            assertEquals(displayed.title, receipt.observation.queryTitle)
            assertEquals(0, receipt.observation.entry.ordinal)
            assertEquals(listOf(displayed.title), fixture.gateway.queries.toList(),
                "Exactly one unchanged displayed-complete-title query, never a changed-title search")
            val job = state.physicalDownloads.values.single { it.status == DownloadStatus.COMPLETE }
            assertEquals(single.bindingId, job.producer.bindingId)
            assertEquals(1, fixture.gateway.typedTransfers.size)
            assertEquals(1, fixture.gateway.typedCompletions.size)
            assertEquals(changed.title, fixture.store.state.value.media[displayed.id]?.title,
                "The catalogue may move on; the accepted immutable request does not")
        } finally { release.complete(Unit) }
        fixture.assertNoNetwork()
    }

    @Test fun staleDisplayedIdentityIsRejectedBeforeAnyCaptureOrSourceQuery() = checkCase {
        val fixture = newFixture()
        val current = item(SourceProvider.COMMONS, id = "stale-tap-identity")
        fixture.store.upsertCatalog(listOf(current)).getOrThrow()
        val stale = current.copy(
            title = "Stale displayed title — عنوان قديم",
            source = current.source.copy(canonicalId = "stale-canonical-id"),
        )
        fixture.app.onMusicAction(MusicUiAction.Download(stale))
        val notice = withTimeout(10_000) { fixture.app.notice.first { it?.failure != null } }
        assertEquals(AppFailure(FailureCode.INVALID_STATE, "download_action_stale"), notice?.failure)
        assertTrue(fixture.gateway.queries.isEmpty(), "A stale unadmitted displayed row must not search any title")
        assertTrue(fixture.gateway.transfers.isEmpty())
        assertTrue(fixture.gateway.typedTransfers.isEmpty())
        val state = fixture.store.acquisitionView().acquisition
        assertTrue(state.requests.isEmpty())
        assertTrue(state.physicalDownloads.isEmpty())
        assertTrue(state.bindings.isEmpty())
        assertTrue(fixture.store.state.value.playbackBindings.isEmpty())
        fixture.assertNoNetwork()
    }

    private companion object {
        const val FIRST = "AAAAAAAAAAA"
        const val DISCOVERED_YOUTUBE = "BBBBBBBBBBB"
        const val REPRESENTATION = "authored-kmpyt-representation"
        val DOWNLOAD_CLEANUP_FAILURE = AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_failed_restart_required")
        val NON_AUDIO = "Authored AppContainer integration fixture. NOT AUDIO; native validation is deliberately fake.".encodeToByteArray()
        val PNG = "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000b49444154789c6360000200000500017a5eab3f0000000049454e44ae426082"
            .decodeHex().toByteArray()

        fun item(provider: SourceProvider = SourceProvider.RSS, id: String = "authored-episode",
            title: String = "Complete requested title — العنوان الكامل") = MediaItem(
            id, title, if (provider == SourceProvider.RSS) MediaKind.EPISODE else MediaKind.MUSIC,
            SourceRef(provider, if (provider == SourceProvider.YOUTUBE) DISCOVERED_YOUTUBE else id,
                canonicalUrl = if (provider == SourceProvider.YOUTUBE) "https://www.youtube.com/watch?v=$DISCOVERED_YOUTUBE" else "https://publisher.example.com/page",
                enclosureUrl = if (provider == SourceProvider.RSS) "https://publisher.example.com/must-never-open.mp3" else null),
            durationMs = 999_999L,
            attribution = Attribution("Original publisher", "https://publisher.example.com/page", "Original metadata rights only"),
        )

        fun plan(binding: PlaybackBinding) = ResolvedPlayable(
            "youtube:${binding.selectedVideoId}", "https://media.example.com/authored-plan-only",
            mimeType = "audio/mpeg", codecs = "mp3", durationMs = 12_345L, representationId = REPRESENTATION,
            item = MediaItem("youtube:${binding.selectedVideoId}", "Actual selected recording", MediaKind.MUSIC,
                SourceRef(SourceProvider.YOUTUBE, requireNotNull(binding.selectedVideoId))),
        )

        fun bindingFor(item: MediaItem) = PlaybackBinding(
            "authored-binding-" + item.id, item.id, item.title, FirstResultKind.VIDEO, FIRST,
            "Authored historical first recording", "https://www.youtube.com/watch?v=$FIRST", selectedVideoId = FIRST)

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        suspend fun writeFakeTransfer(transfer: Transfer): YouTubeTransferResult {
            Files.write(Path.of(transfer.destination), NON_AUDIO)
            transfer.progress(YouTubeTransferProgress(NON_AUDIO.size.toLong(), NON_AUDIO.size.toLong()))
            return YouTubeTransferResult(NON_AUDIO.size.toLong(), "audio/mpeg", "mp3", "mp3", REPRESENTATION)
        }

        fun workDirectory(): Path {
            val root = System.getenv("VEYRA_PROJECT_ROOT")?.let(Path::of)
                ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                    .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            val parent = root.resolve(".work/app-container-integration-tests")
            Files.createDirectories(parent)
            return Files.createTempDirectory(parent, "room-")
        }
    }
}
