package dev.veyra.app

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import dev.kmpyt.YouTubeClient
import dev.kmpyt.extraction.ResearchRequest
import dev.kmpyt.extraction.ResearchResponse
import dev.kmpyt.extraction.ResearchTransport
import dev.veyra.data.LibraryStore
import dev.veyra.data.StoreStatus
import dev.veyra.data.createPlatformLibraryStore
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
    private data class Transfer(
        val binding: PlaybackBinding,
        val destination: String,
        val representationId: String?,
        val persisted: PlaybackBinding?,
        val progress: suspend (YouTubeTransferProgress) -> Unit,
    )

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
        suspend fun position(positionMs: Long) = lock.withLock {
            callbacks.send(EngineEvent.Position(generation, positionMs, 12_345L))
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
        @Volatile var validateAsset: suspend (OfflineAsset) -> AssetValidation = { AssetValidation.Valid(12_345L) }
        override val validator = AssetValidator { asset -> validations += asset; validateAsset(asset) }
        override val localValidator = LocalMediaValidator { localValidations.incrementAndGet(); AssetValidation.Valid(12_345L) }
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
        val typedTransfers = ConcurrentLinkedQueue<Pair<String, String>>()
        var onFirst: suspend (String) -> KmpFirstResult? = { title ->
            KmpFirstResult(title, FirstResultKind.VIDEO, FIRST, "Selected recording, not publisher metadata",
                "https://www.youtube.com/watch?v=$FIRST")
        }
        var onResolve: suspend (PlaybackBinding, EngineCapabilities, ResolvePurpose) -> ResolvedPlayable = { binding, _, _ -> plan(binding) }
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
            return onDownload(transfer)
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
            val plan = onResolve(binding, capabilities, ResolvePurpose.DOWNLOAD)
            resolutions += Resolution(binding, ResolvePurpose.DOWNLOAD, persistedReceipt(context, binding))
            val representation = AcquisitionRepresentation(requiredRepresentationId ?: plan.representationId ?: "authored-aac",
                plan.mimeType ?: "audio/mpeg", plan.codecs ?: "mp3", "mp3")
            return Prepared(context, requiredRepresentationId, representation, binding)
        }

        private class Prepared(
            override val context: AcquisitionExecutionContext,
            override val requiredRepresentationId: String?,
            override val representation: AcquisitionRepresentation,
            val binding: PlaybackBinding,
        ) : PreparedAcquisitionDownload

        override suspend fun download(prepared: PreparedAcquisitionDownload, destinationPath: String,
            onProgress: suspend (AcquisitionTransferProgress) -> Unit): AcquisitionTransferResult {
            val owned = prepared as? Prepared ?: throw VeyraException(AppFailure(FailureCode.INVALID_STATE))
            val call = Transfer(owned.binding, destinationPath, owned.representation.representationId,
                persistedReceipt(owned.context, owned.binding)) { progress ->
                onProgress(AcquisitionTransferProgress(progress.bytesDownloaded, progress.totalBytes, progress.speedBytesPerSecond))
            }
            val result = onDownload(call)
            transfers += call
            typedTransfers += destinationPath to owned.representation.representationId
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
            assertTrue(gateway.transfers.isEmpty())
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
        assertEquals(FailureCode.UNSUPPORTED_SOURCE, state.failure?.code)
        val binding = fixture.store.state.value.playbackBindings.values.single()
        assertEquals(original.title, binding.queryTitle)
        assertEquals(FirstResultKind.CHANNEL, binding.firstResultKind)
        assertEquals(channel, binding.firstResultId)
        assertNull(binding.selectedVideoId)
        assertEquals(0, binding.selectionOrdinal)
        assertEquals(original.title, Url(fixture.platform.youtubeRequests.single().url).parameters["search_query"])
        assertTrue(fixture.platform.engine.preparations.isEmpty())
        assertEquals(0, fixture.platform.runtimePrepareCalls.get(), "A first non-playable receipt cannot start extraction")
        assertTrue(fixture.http.requests.isEmpty())
    }

    @Test fun cancellationAtOnlineScopeReturnDisposesUnhandedGatewayResourceExactlyOnce() = checkCase {
        val fixture = newFixture()
        val resource = Resource()
        fixture.gateway.onResolve = { binding, _, _ ->
            // Deterministic prompt-cancellation boundary: the gateway returns a value but the
            // enclosing online coroutineScope cannot hand that value to AppSourceResolver.
            currentCoroutineContext().job.cancel(CancellationException("authored_scope_return_cancellation"))
            plan(binding).copy(uri = resource.localEndpoint.uri, resource = resource)
        }
        assertFailsWith<CancellationException> { fixture.app.resolver.resolve(item(), fixture.platform.engine.capabilities) }
        resource.closed.await()
        assertEquals(1, resource.closeCalls.get())
        assertTrue(fixture.platform.engine.preparations.isEmpty())
        assertEquals(1, fixture.gateway.resolutions.size)
        assertEquals(1, fixture.store.state.value.playbackBindings.size)
        fixture.app.close()
        assertEquals(1, resource.closeCalls.get())
    }

    @Test fun rejectedGatewayResourceIsDrainedAtFinalCloseButEarlierReleaseFailureStaysSanitizedAndSticky() = checkCase {
        val fixture = newFixture()
        val cleanupActive = ConcurrentLinkedQueue<Boolean>()
        val resource = Resource { attempt ->
            cleanupActive += currentCoroutineContext().isActive
            if (attempt == 1) throw IllegalStateException("authored private https://media.example/close?sig=never-report-this")
        }
        fixture.gateway.onResolve = { binding, _, _ ->
            currentCoroutineContext().job.cancel(CancellationException("authored_scope_return_cancellation"))
            plan(binding).copy(uri = resource.localEndpoint.uri, resource = resource)
        }
        val cancellation = assertFailsWith<CancellationException> {
            fixture.app.resolver.resolve(item(), fixture.platform.engine.capabilities)
        }
        assertFalse(cancellation.suppressedExceptions.any { it.toString().contains("never-report-this") })
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
        val resource = Resource {
            if (!allowRelease.get()) throw IllegalStateException("authored private resource handle cannot close")
        }
        fixture.gateway.onResolve = { binding, _, _ ->
            currentCoroutineContext().job.cancel(CancellationException("authored_scope_return_cancellation"))
            plan(binding).copy(uri = resource.localEndpoint.uri, resource = resource)
        }
        assertFailsWith<CancellationException> { fixture.app.resolver.resolve(item(), fixture.platform.engine.capabilities) }
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

    @Test fun finalCloseCancelsExternalResolverAndAwaitsItsLateRejectedResourceBeforeClosingDependencies() = checkCase {
        val fixture = newFixture()
        val resolving = CompletableDeferred<Unit>()
        val releasing = CompletableDeferred<Unit>()
        val permitRelease = CompletableDeferred<Unit>()
        val resource = Resource { attempt ->
            if (attempt == 1) {
                releasing.complete(Unit)
                permitRelease.await()
                throw IllegalStateException("authored late resource release failed once")
            }
        }
        fixture.gateway.onResolve = { binding, _, _ ->
            resolving.complete(Unit)
            try { awaitCancellation() }
            catch (_: CancellationException) {
                plan(binding).copy(uri = resource.localEndpoint.uri, resource = resource)
            }
        }
        supervisorScope {
            // Deliberately external to the app's playback/download actor jobs. AppContainer's
            // admitted-acquisition completion barrier must include the policy-return cleanup.
            val resolution = async { fixture.app.resolver.resolve(item(), fixture.platform.engine.capabilities) }
            resolving.await()
            val closing = async { runCatching { fixture.app.close() } }
            try {
                releasing.await()
                assertFalse(closing.isCompleted)
                assertFalse(resource.closed.isCompleted)
                assertEquals(0, fixture.platform.runtimeCloseCalls.get())
                assertEquals(0, fixture.platform.youtubeCloseCalls.get())
                assertEquals(0, fixture.http.closeCalls.get())
                assertNotEquals(StoreStatus.Closed, fixture.store.status.value)
                assertTrue(fixture.platform.engine.preparations.isEmpty())
            } finally { permitRelease.complete(Unit) }
            assertFailsWith<CancellationException> { resolution.await() }
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
        fixture.gateway.onResolve = { binding, _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() }
            catch (_: CancellationException) {
                cancelled.complete(Unit)
                // A transport can finish a native resource concurrently with cancellation.
                // Returning it must not leak it across AppContainer's online scope barrier.
                plan(binding).copy(uri = resource.localEndpoint.uri, resource = resource)
            }
        }
        supervisorScope {
            val resolution = async { fixture.app.resolver.resolve(item(), fixture.platform.engine.capabilities) }
            entered.await()
            fixture.app.changeSettings { it.copy(offlineOnly = true) }
            fixture.store.state.first { it.settings.offlineOnly }
            cancelled.await()
            assertFailsWith<CancellationException> { resolution.await() }
        }
        resource.closed.await()
        assertEquals(1, resource.closeCalls.get())
        assertTrue(fixture.platform.engine.preparations.isEmpty())
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
        val fixture = newFixture()
        val original = item(title = "Complete restart episode — إعادة الحلقة من بدايتها")
        fixture.gateway.onDownload = ::writeFakeTransfer
        val first = fixture.play(original)
        val binding = assertNotNull(first.source.executionBinding)
        fixture.app.onPodcastAction(PodcastAction.Download(original.id))
        val downloaded = fixture.store.state.first { snapshot -> snapshot.downloads.values.any { it.status == DownloadStatus.COMPLETE } }
            .downloads.values.single()
        fixture.app.changeSettings { it.copy(offlineOnly = true) }
        fixture.store.state.first { it.settings.offlineOnly }
        val offline = fixture.play(original)
        assertTrue(offline.source.isLocal)
        fixture.platform.engine.position(4_500)
        fixture.app.playback.state.first { it.positionMs == 4_500L }
        fixture.app.playback.dispatch(PlaybackCommand.FlushProgress).getOrThrow()

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
        val restarted = fixture.platform.engine.prepared.receive()
        fixture.app.playback.state.first { it.generation == restarted.generation && it.isPlaying }
        assertTrue(restarted.generation > offline.generation)
        assertEquals(0L, restarted.positionMs)
        assertTrue(restarted.source.isLocal)
        assertEquals(binding, restarted.source.executionBinding)
        assertEquals(1, fixture.platform.engine.seekRequests.size, "Start Over must not seek the outgoing generation then lose that seek")
        fixture.app.playback.dispatch(PlaybackCommand.FlushProgress).getOrThrow()
        assertEquals(0L, fixture.store.state.value.progress[original.id]?.positionMs)
        val retained = fixture.store.state.value.downloads.getValue(downloaded.id)
        assertEquals(DownloadStatus.COMPLETE, retained.status)
        assertEquals(downloaded.mediaId, retained.mediaId)
        assertEquals(downloaded.executionBindingId, retained.executionBindingId)
        assertEquals(downloaded.asset, retained.asset)
        assertContentEquals(NON_AUDIO, Files.readAllBytes(Path.of(assertNotNull(retained.asset).uri)))
        assertEquals(queries, fixture.gateway.queries.toList(), "Offline restart must not perform a new search")

        fixture.platform.engine.completeSeek(pending)
        // Both samples traverse the same fake event channel. Position itself may be
        // conflated by the controller; the awaited FlushProgress below also fences the
        // earlier critical seek event before the final persisted-position assertion.
        fixture.platform.engine.position(1_000)
        fixture.app.playback.state.first { it.positionMs == 1_000L && it.pendingSeekMs == null }
        fixture.app.playback.dispatch(PlaybackCommand.FlushProgress).getOrThrow()
        assertEquals(1_000L, fixture.store.state.value.progress[original.id]?.positionMs)
        fixture.app.close()
        val reopened = newFixture()
        assertEquals(1_000L, reopened.store.state.value.progress[original.id]?.positionMs)
        assertEquals(PlaybackPhase.PAUSED, reopened.app.playback.state.value.phase)
        assertTrue(reopened.platform.engine.preparations.isEmpty())
        reopened.assertNoNetwork()
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
        val fixture = newFixture()
        val original = item()
        fixture.gateway.onDownload = { transfer -> writeFakeTransfer(transfer) }
        fixture.play(original)
        fixture.app.onPodcastAction(PodcastAction.Download(original.id))
        val persisted = fixture.store.state.first { state -> state.downloads.values.any { it.status == DownloadStatus.COMPLETE } }
        val id = persisted.downloads.values.single().id
        fixture.app.changeSettings { it.copy(offlineOnly = true) }
        fixture.store.state.first { it.settings.offlineOnly }
        fixture.app.close()

        val reopened = newFixture(initialize = false)
        val validating = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        reopened.platform.validateAsset = { validating.complete(Unit); release.await(); AssetValidation.Valid(12_345L) }
        coroutineScope {
            val observed = ConcurrentLinkedQueue<DownloadStatus>()
            val observing = launch(start = CoroutineStart.UNDISPATCHED) {
                reopened.app.library.collect { snapshot -> snapshot.downloads[id]?.let { observed += it.status } }
            }
            try {
                reopened.app.initialize()
                validating.await()
                assertEquals(DownloadStatus.COMPLETE, reopened.store.state.value.downloads.getValue(id).status,
                    "The historical Room receipt remains intact while session-only validation is pending")
                reopened.app.library.first { it.downloads[id]?.status == DownloadStatus.VERIFYING }
                assertFalse(DownloadStatus.COMPLETE in observed, "Core UI must never flash Ready from unchecked historical rows")
                assertTrue(reopened.platform.engine.preparations.isEmpty())
                release.complete(Unit)
                reopened.app.library.first { it.downloads[id]?.status == DownloadStatus.COMPLETE }
            } finally { release.complete(Unit); observing.cancelAndJoin() }
        }
        reopened.assertNoNetwork()
    }

    @Test fun queuedDownloadRetainsTheRecordingChosenAtIntentAfterAnotherFreshPlayOfSameItem() = checkCase {
        val fixture = newFixture()
        val original = item()
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
            val saved = fixture.store.state.first { snapshot -> snapshot.downloads.values.any { it.status == DownloadStatus.COMPLETE } }
            assertEquals(first.id, saved.downloads.values.single().executionBindingId)
            assertEquals(first.id, saved.downloads.values.single().asset?.executionBindingId)
            assertEquals(first, fixture.gateway.transfers.single().binding)
            assertEquals(second, fixture.app.playback.state.value.executionBinding)
            assertEquals(listOf(original.title, original.title), fixture.gateway.queries.toList())
            assertEquals(first, fixture.gateway.resolutions.single { it.purpose == ResolvePurpose.DOWNLOAD }.binding)
        } finally { permitDownload.complete(Unit) }
        fixture.assertNoNetwork()
    }

    @Test fun slowDownloadRemovalCleanupCannotBlockUiPauseOrPersistedOfflineChange() = checkCase {
        val fixture = newFixture()
        val playing = item(SourceProvider.COMMONS, "independent-playing-item")
        fixture.play(playing)
        val transferEntered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        fixture.gateway.onDownload = {
            transferEntered.complete(Unit)
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { cleanupEntered.complete(Unit); releaseCleanup.await() } }
        }
        fixture.app.onMusicAction(MusicUiAction.Download(item()))
        transferEntered.await()
        val downloadId = fixture.store.state.value.downloads.values.single().id
        try {
            fixture.app.onMusicAction(MusicUiAction.RemoveDownload(downloadId))
            cleanupEntered.await()
            assertTrue(fixture.store.state.value.downloads.getValue(downloadId).cleanupPending)
            fixture.app.onMusicAction(MusicUiAction.TogglePlayPause)
            withTimeout(10_000L) {
                fixture.app.playback.state.first { it.currentItem?.id == playing.id && !it.desiredPlaying && !it.isPlaying }
            }
            fixture.app.changeSettings { it.copy(offlineOnly = true) }
            withTimeout(10_000L) { fixture.store.state.first { it.settings.offlineOnly } }
            assertFalse(releaseCleanup.isCompleted, "Pause/Offline must finish before slow media cleanup is released")
        } finally { releaseCleanup.complete(Unit) }
        fixture.store.state.first { downloadId !in it.downloads }
        fixture.assertNoNetwork()
    }

    @Test fun ownedFileRemovalWaitsForNativeStopButDoesNotBlockOfflineUiIntent() = checkCase {
        val fixture = newFixture()
        val original = item()
        fixture.gateway.onDownload = { transfer -> writeFakeTransfer(transfer) }
        fixture.play(original)
        fixture.app.onPodcastAction(PodcastAction.Download(original.id))
        val snapshot = fixture.store.state.first { state -> state.downloads.values.any { it.status == DownloadStatus.COMPLETE } }
        val record = snapshot.downloads.values.single()
        val asset = assertNotNull(record.asset)
        assertTrue(fixture.play(original).source.isLocal)
        val stopEntered = CompletableDeferred<Unit>()
        val releaseStop = CompletableDeferred<Unit>()
        fixture.platform.engine.beforeStop = { stopEntered.complete(Unit); releaseStop.await() }
        try {
            fixture.app.onPodcastAction(PodcastAction.RemoveDownload(record.id))
            stopEntered.await()
            assertTrue(Files.isRegularFile(Path.of(asset.uri)), "Durable intent is not native file-release acknowledgement")
            assertTrue(fixture.store.state.value.downloads.getValue(record.id).cleanupPending)
            fixture.app.changeSettings { it.copy(offlineOnly = true) }
            withTimeout(10_000L) { fixture.store.state.first { it.settings.offlineOnly } }
            assertTrue(Files.isRegularFile(Path.of(asset.uri)), "Offline settings may change while deletion safely waits")
            assertNull(fixture.store.state.value.downloads.getValue(record.id).failure)
        } finally {
            fixture.platform.engine.beforeStop = {}
            releaseStop.complete(Unit)
        }
        fixture.store.state.first { record.id !in it.downloads }
        assertFalse(Files.exists(Path.of(asset.uri)))
        assertEquals(1, fixture.gateway.queries.size, "Opening a completed local artifact cannot fresh-search")
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
        val binding = bindingFor(historical)
        // This is historical metadata pointing at an ABSENT file, not a playable fixture.
        // Scheduling-only initialization may neither validate it nor clear its artwork path.
        val asset = OfflineAsset("historical-asset", historical.id,
            seed.app.files.finalPath(historical.id, 1, "audio/mpeg").toString(), NON_AUDIO.size.toLong(),
            mimeType = "audio/mpeg", representationId = REPRESENTATION, executionBindingId = binding.id)
        val complete = DownloadRecord(seed.app.files.key(historical.id), historical.id, DownloadStatus.COMPLETE,
            asset = asset, executionBindingId = binding.id, representationId = REPRESENTATION)
        val queued = DownloadRecord(seed.app.files.key(pending.id), pending.id)
        val occurrence = QueueEntry("scheduling-restored-occurrence", historical.id)
        val queue = QueueSnapshot(listOf(occurrence), occurrence.id, positionMs = 3_210,
            originalOrder = listOf(occurrence.id))
        val collection = MediaCollection("scheduling-restored-collection", "Complete restored collection — المجموعة",
            SourceRef(SourceProvider.YOUTUBE, "PLauthoredSchedulingCollection",
                "https://www.youtube.com/playlist?list=PLauthoredSchedulingCollection"), kind = BrowseKind.PLAYLIST)
        val navigation = AppNavigation(AppDestination.LIBRARY,
            details = listOf(AppDetail(DetailKind.COLLECTION, collection.id, collection)))
        val seeded = seed.store.update { snapshot -> snapshot.copy(
            media = mapOf(historical.id to historical, pending.id to pending),
            downloads = mapOf(complete.id to complete, queued.id to queued),
            playbackBindings = mapOf(binding.id to binding),
            queues = snapshot.queues + (QueueContext.MUSIC to queue),
            navigation = navigation.persisted(),
        ) }.getOrThrow()
        assertEquals(navigation, AppNavigation.restore(seeded), "The collection fixture must genuinely be restorable")
        seed.assertSchedulingOnly()
        seed.close()

        val reopened = newFixture(initialize = false)
        assertIs<StoreStatus.Uninitialized>(reopened.store.status.value)
        repeat(2) { reopened.app.initializeScheduling() }
        assertIs<StoreStatus.Ready>(reopened.store.status.value)
        assertEquals(seeded, reopened.store.flush().getOrThrow(), "Headless bootstrap must be a read, not library reconciliation")
        assertEquals(navigation, AppNavigation.restore(reopened.store.state.value))
        assertEquals(historical.localArtworkPath, reopened.store.state.value.media.getValue(historical.id).localArtworkPath)
        assertEquals(DownloadStatus.COMPLETE, reopened.store.state.value.downloads.getValue(complete.id).status)
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
        val paused = listOf(music, podcast).map { original ->
            DownloadRecord(fixture.app.files.key(original.id), original.id, DownloadStatus.PAUSED, userPaused = true)
        }
        fixture.store.update { it.copy(media = listOf(music, podcast, newPodcast).associateBy { original -> original.id },
            downloads = paused.associateBy { record -> record.id }) }.getOrThrow()
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
                rejectsAtTap { fixture.app.onMusicAction(MusicUiAction.ResumeDownload(paused[0].id)) }
                rejectsAtTap { fixture.app.onPodcastAction(PodcastAction.ResumeDownload(paused[1].id)) }
                assertEquals(paused.associateBy { it.id }, fixture.store.state.value.downloads)
                fixture.assertNoDownloadExecution()

                // Cancel remains admissible. Commit it BEFORE accepting coverage so an older
                // rejected resume/download cannot wake up on the later successful ACK.
                fixture.app.onMusicAction(MusicUiAction.CancelDownload(paused[0].id))
                fixture.app.onPodcastAction(PodcastAction.CancelDownload(paused[1].id))
                permitActor.countDown()
                fixture.store.state.first { snapshot -> paused.all { record ->
                    snapshot.downloads[record.id]?.let {
                        it.status == DownloadStatus.CANCELLED && it.userPaused && it.cleanupPending
                    } == true
                } }
                assertFalse(actorTimedOut.get())
                assertEquals(20, fixture.store.state.value.settings.skipBackSeconds)
                assertEquals(0, fixture.platform.coverageAcknowledgements.get())
                fixture.assertNoDownloadExecution()

                permitCoverage.complete(Unit)
                fixture.platform.coverageAcknowledged.await()
                initializing.await()
                assertNull(fixture.app.downloadRestriction.value)
                fixture.store.state.first { snapshot -> paused.all { record ->
                    snapshot.downloads[record.id]?.let { it.status == DownloadStatus.CANCELLED && !it.cleanupPending } == true
                } }
                fixture.awaitDownloadIdle()
                // Fence the action consumer too, rather than merely sampling before it runs.
                fixture.app.changeSettings { it.copy(skipForwardSeconds = 60) }
                fixture.store.state.first { it.settings.skipForwardSeconds == 60 }
                val final = fixture.store.flush().getOrThrow()
                assertEquals(paused.map { it.id }.toSet(), final.downloads.keys)
                assertTrue(final.downloads.values.all { it.status == DownloadStatus.CANCELLED && it.userPaused })
                assertTrue(final.downloadAttempts.isEmpty())
                assertTrue(final.playbackBindings.isEmpty())
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
        val records = media.map { original ->
            DownloadRecord(fixture.app.files.key(original.id), original.id,
                nextRetryEpochMs = clock.epochMs() + 60_000, automaticRetryCount = 2)
        }
        fixture.store.update { it.copy(media = media.associateBy { original -> original.id },
            downloads = records.associateBy { record -> record.id }) }.getOrThrow()
        fixture.app.initializeScheduling()
        fixture.app.downloads.pause(records[0].id)
        fixture.app.downloads.cancel(records[1].id)
        fixture.app.downloads.remove(records[2].id)
        val expected = listOf(
            records[0].copy(status = DownloadStatus.PAUSED, userPaused = true, nextRetryEpochMs = null, updatedEpochMs = clock.epochMs()),
            records[1].copy(status = DownloadStatus.CANCELLED, userPaused = true, cleanupPending = true,
                nextRetryEpochMs = null, updatedEpochMs = clock.epochMs()),
            records[2].copy(status = DownloadStatus.CANCELLED, userPaused = true, cleanupPending = true, removeWhenClean = true,
                nextRetryEpochMs = null, updatedEpochMs = clock.epochMs()),
        ).associateBy { it.id }
        assertEquals(expected, fixture.store.flush().getOrThrow().downloads)
        fixture.assertSchedulingOnly()
        fixture.close()

        val reopened = newFixture(initialize = false, clock = clock)
        reopened.app.initializeScheduling()
        assertEquals(expected, reopened.store.flush().getOrThrow().downloads,
            "Remove intent remains durable until an activated native maintenance owner acknowledges cleanup")
        reopened.assertSchedulingOnly()
    }

    @Test fun musicAndPodcastPauseCancelRemoveRemainUsableAfterCoverageFailureButPhysicalCleanupWaitsForExplicitActivation() = checkCase {
        val fixture = newFixture(initialize = false)
        val music = listOf("pause", "cancel", "remove").map { item(SourceProvider.COMMONS, "restricted-music-$it") }
        val podcasts = listOf("pause", "cancel", "remove").map { item(id = "restricted-podcast-$it") }
        val media = music + podcasts
        val removable = listOf(music[2], podcasts[2])
        val bindings = removable.map(::bindingFor)
        // Authored NON-AUDIO bytes establish actual deletion/retention, never native media validity.
        fixture.app.files.initialize()
        val assets = removable.associate { original ->
            val asset = OfflineAsset("owned-" + original.id, original.id,
                fixture.app.files.finalPath(original.id, 1, "audio/mpeg").toString(), NON_AUDIO.size.toLong(),
                mimeType = "audio/mpeg", representationId = REPRESENTATION, executionBindingId = bindingFor(original).id)
            Files.write(Path.of(asset.uri), NON_AUDIO)
            original.id to asset
        }
        val records = media.associate { original ->
            val asset = assets[original.id]
            val record = DownloadRecord(fixture.app.files.key(original.id), original.id,
                if (asset == null) DownloadStatus.QUEUED else DownloadStatus.COMPLETE, asset = asset,
                executionBindingId = asset?.executionBindingId, representationId = asset?.representationId)
            original.id to record
        }
        fixture.store.update { it.copy(media = media.associateBy { original -> original.id },
            downloads = records.values.associateBy { record -> record.id },
            playbackBindings = bindings.associateBy { binding -> binding.id }) }.getOrThrow()
        val denied = AppFailure(FailureCode.BACKGROUND_RESTRICTED, "authored_scheduler_install_failure")
        fixture.platform.ensureCoverage = { throw VeyraException(denied) }
        fixture.app.initialize()
        assertEquals(AppInitialization.Ready, fixture.app.initialization.value)
        assertEquals(denied, fixture.app.downloadRestriction.value)
        fixture.app.onMusicAction(MusicUiAction.PauseDownload(records.getValue(music[0].id).id))
        fixture.app.onMusicAction(MusicUiAction.CancelDownload(records.getValue(music[1].id).id))
        fixture.app.onMusicAction(MusicUiAction.RemoveDownload(records.getValue(music[2].id).id))
        fixture.app.onPodcastAction(PodcastAction.PauseDownload(records.getValue(podcasts[0].id).id))
        fixture.app.onPodcastAction(PodcastAction.CancelDownload(records.getValue(podcasts[1].id).id))
        fixture.app.onPodcastAction(PodcastAction.RemoveDownload(records.getValue(podcasts[2].id).id))
        val pausedIds = listOf(music[0], podcasts[0]).map { records.getValue(it.id).id }.toSet()
        val cancelledIds = listOf(music[1], podcasts[1]).map { records.getValue(it.id).id }.toSet()
        val removedIds = removable.map { records.getValue(it.id).id }.toSet()
        val intent = fixture.store.state.first { snapshot ->
            pausedIds.all { snapshot.downloads[it]?.let { row -> row.status == DownloadStatus.PAUSED && row.userPaused } == true } &&
                (cancelledIds + removedIds).all { id -> snapshot.downloads[id]?.let { row ->
                    row.status == DownloadStatus.CANCELLED && row.userPaused && row.cleanupPending && row.removeWhenClean == (id in removedIds)
                } == true }
        }
        assertEquals(records.size, intent.downloads.size)
        assets.forEach { (mediaId, asset) ->
            assertEquals(asset, intent.downloads.getValue(records.getValue(mediaId).id).asset)
            assertContentEquals(NON_AUDIO, Files.readAllBytes(Path.of(asset.uri)))
        }
        fixture.assertNoDownloadExecution()
        assertEquals(1, fixture.platform.ensureCoverageCalls.get())
        assertEquals(0, fixture.platform.coverageAcknowledgements.get())

        fixture.platform.ensureCoverage = {}
        assertTrue(fixture.app.activateUserDownloads())
        assertNull(fixture.app.downloadRestriction.value)
        fixture.store.state.first { snapshot ->
            removedIds.none { it in snapshot.downloads } &&
                cancelledIds.all { snapshot.downloads[it]?.let { row -> row.status == DownloadStatus.CANCELLED && !row.cleanupPending } == true }
        }
        fixture.awaitDownloadIdle()
        assertEquals(AppInitialization.Ready, fixture.app.initialization.value)
        assertTrue(pausedIds.all { fixture.store.state.value.downloads[it]?.let { row -> row.status == DownloadStatus.PAUSED && row.userPaused } == true })
        assets.values.forEach { assertFalse(Files.exists(Path.of(it.uri))) }
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
        val record = DownloadRecord(seed.app.files.key(original.id), original.id)
        seed.store.update { it.copy(media = mapOf(original.id to original), downloads = mapOf(record.id to record)) }.getOrThrow()
        seed.close()
        val fixture = newFixture(initialize = false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.platform.ensureCoverage = { entered.complete(Unit); release.await() }
        fixture.platform.acquireDownload = {
            assertEquals(1, fixture.platform.coverageAcknowledgements.get(), "Native allowance cannot precede durable coverage ACK")
            DownloadExecutionLease {}
        }
        fixture.gateway.onFirst = { title ->
            assertEquals(1, fixture.platform.coverageAcknowledgements.get())
            assertTrue(fixture.platform.downloadGateCalls.get() > 0)
            KmpFirstResult(title, FirstResultKind.VIDEO, FIRST, "Authored selected recording", "https://www.youtube.com/watch?v=$FIRST")
        }
        fixture.gateway.onDownload = ::writeFakeTransfer
        coroutineScope {
            val initializing = async { fixture.app.initialize() }
            try {
                entered.await()
                assertEquals(AppInitialization.Ready, fixture.app.initialization.value)
                assertFalse(initializing.isCompleted)
                assertEquals(record, fixture.store.state.value.downloads[record.id])
                fixture.assertNoDownloadExecution()
                release.complete(Unit)
                fixture.platform.coverageAcknowledged.await()
                initializing.await()
            } finally { release.complete(Unit) }
        }
        val saved = fixture.store.state.first { it.downloads[record.id]?.status == DownloadStatus.COMPLETE }
        val complete = saved.downloads.getValue(record.id)
        val asset = assertNotNull(complete.asset)
        val binding = saved.playbackBindings.getValue(assertNotNull(complete.executionBindingId))
        fixture.awaitDownloadIdle()
        assertNull(fixture.app.downloadRestriction.value)
        assertEquals(listOf(original.title), fixture.gateway.queries.toList())
        assertEquals(original.title, binding.queryTitle)
        assertEquals(FIRST, binding.selectedVideoId)
        assertEquals(binding, fixture.gateway.transfers.single().persisted)
        assertEquals(binding.id, asset.executionBindingId)
        assertContentEquals(NON_AUDIO, Files.readAllBytes(Path.of(asset.uri)))
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
        val paused = DownloadRecord(fixture.app.files.key(pausedItem.id), pausedItem.id, DownloadStatus.PAUSED,
            userPaused = true, automaticRetryCount = 2, nextRetryEpochMs = deadline + 30_000)
        val future = DownloadRecord(fixture.app.files.key(futureItem.id), futureItem.id, DownloadStatus.NEEDS_FOREGROUND,
            failure = AppFailure(FailureCode.BACKGROUND_RESTRICTED), automaticRetryCount = 3, nextRetryEpochMs = deadline)
        fixture.store.update { it.copy(media = listOf(pausedItem, futureItem).associateBy { original -> original.id },
            downloads = listOf(paused, future).associateBy { record -> record.id }) }.getOrThrow()
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
        val activated = fixture.store.flush().getOrThrow()
        assertEquals(paused, activated.downloads[paused.id], "Foreground activation must not reinterpret a user pause")
        val resumed = activated.downloads.getValue(future.id)
        assertEquals(DownloadStatus.QUEUED, resumed.status)
        assertFalse(resumed.userPaused)
        assertNull(resumed.failure)
        assertEquals(deadline, resumed.nextRetryEpochMs)
        assertEquals(3, resumed.automaticRetryCount)
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
        fixture.store.state.first { it.downloads[future.id]?.status == DownloadStatus.COMPLETE }
        fixture.awaitDownloadIdle()
        assertEquals(paused, fixture.store.state.value.downloads[paused.id])
        assertEquals(listOf(futureItem.title), fixture.gateway.queries.toList())
        assertEquals(futureItem.id, fixture.gateway.transfers.single().binding.mediaId)
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
        fixture.app.onMusicAction(MusicUiAction.Download(original))
        val id = fixture.app.files.key(original.id)
        fixture.store.state.first { id in it.downloads }
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
            val waiting = fixture.store.flush().getOrThrow()
            assertEquals(DownloadStatus.QUEUED, waiting.downloads.getValue(id).status)
            assertTrue(waiting.downloadAttempts.isEmpty())
            assertTrue(waiting.playbackBindings.isEmpty())
            fixture.assertNoDownloadExecution()
        }
        fixture.platform.downloadNetworkState.value = DownloadNetworkState.CONNECTED
        fixture.store.state.first { it.downloads[id]?.status == DownloadStatus.COMPLETE }
        fixture.awaitDownloadIdle()
        assertEquals(listOf(original.title), fixture.gateway.queries.toList())
        assertEquals(original.id, fixture.gateway.transfers.single().binding.mediaId)
        assertEquals(1, fixture.platform.ensureCoverageCalls.get())
        assertEquals(1, fixture.platform.coverageAcknowledgements.get())
        assertTrue(fixture.platform.gateCoverageAcknowledgements.isNotEmpty())
        assertTrue(fixture.platform.gateCoverageAcknowledgements.all { it == 1 })
        assertTrue(fixture.platform.engine.preparations.isEmpty())
        fixture.assertNoNetwork()
    }

    @Test fun handedDownloadResourceFailureRetainsRoomRuntimeClientsAndLifetimeUntilExplicitCloseRetry() = checkCase {
        val fixture = newFixture()
        assertFalse(fixture.app.dependencyReleaseSafeAfterClose, "A never-closed application has no release receipt")
        val allowRelease = AtomicBoolean()
        val allowanceCloses = AtomicInteger()
        val resource = Resource {
            if (!allowRelease.get()) throw IllegalStateException("authored private download owner was not consumed")
        }
        fixture.platform.acquireDownload = { DownloadExecutionLease { allowanceCloses.incrementAndGet(); Unit } }
        fixture.gateway.onResolve = { binding, _, purpose ->
            assertEquals(ResolvePurpose.DOWNLOAD, purpose)
            plan(binding).copy(resource = resource)
        }
        fixture.gateway.onDownload = ::writeFakeTransfer
        val original = item(SourceProvider.COMMONS, "retained-download-resource")
        try {
            fixture.app.onMusicAction(MusicUiAction.Download(original))
            fixture.app.downloads.lastFailure.first { it == DOWNLOAD_CLEANUP_FAILURE }
            val complete = fixture.store.state.value.downloads.getValue(fixture.app.files.key(original.id))
            val asset = assertNotNull(complete.asset)
            assertContentEquals(NON_AUDIO, Files.readAllBytes(Path.of(asset.uri)))
            assertEquals(1, resource.closeCalls.get())
            assertFalse(resource.closed.isCompleted)
            assertEquals(0, allowanceCloses.get(), "Allowance is dependent on successful resource consumption")
            assertEquals(DOWNLOAD_CLEANUP_FAILURE,
                assertFailsWith<VeyraException> { fixture.app.downloads.drainForBackground() }.failure)

            val first = assertFailsWith<VeyraException> { fixture.app.close() }
            fixture.expectedCloseFailure = first.failure
            assertEquals(DOWNLOAD_CLEANUP_FAILURE, first.failure)
            assertNull(first.cause)
            assertTrue(first.suppressedExceptions.isEmpty())
            assertFalse(first.toString().contains("private download owner"))
            assertEquals(2, resource.closeCalls.get(), "This explicit close retries the already failed owner once")
            assertEquals(0, allowanceCloses.get())
            fixture.assertDownloadCleanupRetainsDependencies()
            assertEquals(asset, fixture.store.state.value.downloads.getValue(complete.id).asset)
            assertEquals(listOf(original.title), fixture.gateway.queries.toList())
            assertEquals(1, fixture.gateway.transfers.size, "Cleanup retries may not replay the transfer")

            allowRelease.set(true)
            val afterDrain = assertFailsWith<VeyraException> { fixture.app.close() }
            assertEquals(first.failure, afterDrain.failure, "Later consumption cannot certify the earlier failed close")
            resource.closed.await()
            assertEquals(3, resource.closeCalls.get())
            assertEquals(1, allowanceCloses.get())
            fixture.assertDownloadCleanupDependenciesClosedOnce()
            assertEquals(first.failure, assertFailsWith<VeyraException> { fixture.app.close() }.failure)
            assertEquals(3, resource.closeCalls.get())
            assertEquals(1, allowanceCloses.get())
            fixture.assertDownloadCleanupDependenciesClosedOnce()
            fixture.assertNoNetwork()
        } finally { allowRelease.set(true) }
    }

    @Test fun throwingDownloadAllowanceRetainsDependenciesWithoutReclosingTheAlreadyConsumedResource() = checkCase {
        val fixture = newFixture()
        assertFalse(fixture.app.dependencyReleaseSafeAfterClose, "A never-closed application has no release receipt")
        val allowRelease = AtomicBoolean()
        val allowanceCloses = AtomicInteger()
        val resource = Resource()
        fixture.platform.acquireDownload = {
            DownloadExecutionLease {
                allowanceCloses.incrementAndGet()
                if (!allowRelease.get()) throw AssertionError("authored private native allowance failure")
            }
        }
        fixture.gateway.onResolve = { binding, _, purpose ->
            assertEquals(ResolvePurpose.DOWNLOAD, purpose)
            plan(binding).copy(resource = resource)
        }
        fixture.gateway.onDownload = ::writeFakeTransfer
        val original = item(SourceProvider.COMMONS, "retained-download-allowance")
        try {
            fixture.app.onMusicAction(MusicUiAction.Download(original))
            fixture.app.downloads.lastFailure.first { it == DOWNLOAD_CLEANUP_FAILURE }
            resource.closed.await()
            assertEquals(1, resource.closeCalls.get())
            assertEquals(1, allowanceCloses.get())
            val first = assertFailsWith<VeyraException> { fixture.app.close() }
            fixture.expectedCloseFailure = first.failure
            assertEquals(DOWNLOAD_CLEANUP_FAILURE, first.failure)
            assertNull(first.cause)
            assertTrue(first.suppressedExceptions.isEmpty())
            assertFalse(first.toString().contains("private native allowance"))
            assertEquals(2, allowanceCloses.get())
            assertEquals(1, resource.closeCalls.get(), "The successful earlier stage must never be repeated")
            fixture.assertDownloadCleanupRetainsDependencies()

            allowRelease.set(true)
            assertEquals(first.failure, assertFailsWith<VeyraException> { fixture.app.close() }.failure)
            assertEquals(3, allowanceCloses.get())
            assertEquals(1, resource.closeCalls.get())
            fixture.assertDownloadCleanupDependenciesClosedOnce()
            assertEquals(first.failure, assertFailsWith<VeyraException> { fixture.app.close() }.failure)
            assertEquals(3, allowanceCloses.get())
            assertEquals(1, resource.closeCalls.get())
            assertEquals(listOf(original.title), fixture.gateway.queries.toList())
            assertEquals(1, fixture.gateway.transfers.size)
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
        val resource = Resource { attempt ->
            cleanupActive += currentCoroutineContext().isActive
            if (attempt == 1) throw CancellationException("authored close returned cancellation without consuming owner")
            retryEntered.complete(Unit)
            permitConsumption.await()
            assertTrue(currentCoroutineContext().isActive)
            assertFalse(fixture.app.dependencyReleaseSafeAfterClose, "A close still consuming its owner cannot publish an ACK")
            assertIs<StoreStatus.Ready>(fixture.store.status.value)
            assertEquals(0, fixture.platform.runtimeCloseCalls.get())
            assertEquals(0, fixture.platform.youtubeCloseCalls.get())
            assertEquals(0, fixture.http.closeCalls.get())
        }
        fixture.platform.acquireDownload = { DownloadExecutionLease { allowanceCloses.incrementAndGet(); Unit } }
        fixture.gateway.onResolve = { binding, _, purpose ->
            assertEquals(ResolvePurpose.DOWNLOAD, purpose)
            plan(binding).copy(resource = resource)
        }
        fixture.gateway.onDownload = ::writeFakeTransfer
        val original = item(SourceProvider.COMMONS, "cancelled-caller-download-cleanup")
        try {
            fixture.app.onMusicAction(MusicUiAction.Download(original))
            fixture.app.downloads.lastFailure.first { it == DOWNLOAD_CLEANUP_FAILURE }
            supervisorScope {
                val closing = launch { closeResult.complete(runCatching { fixture.app.close() }.exceptionOrNull()) }
                try {
                    retryEntered.await()
                    closing.cancel(CancellationException("authored external close caller cancelled"))
                    assertTrue(closing.isCancelled)
                    assertFalse(closing.isCompleted, "Cancellation cannot acknowledge a still-waiting native owner")
                    assertFalse(closeResult.isCompleted)
                    assertFalse(resource.closed.isCompleted)
                    assertEquals(0, allowanceCloses.get())
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
            resource.closed.await()
            assertEquals(listOf(true, true), cleanupActive.toList())
            assertEquals(2, resource.closeCalls.get())
            assertEquals(1, allowanceCloses.get())
            fixture.assertDownloadCleanupDependenciesClosedOnce()
            assertEquals(first.failure, assertFailsWith<VeyraException> { fixture.app.close() }.failure)
            assertEquals(2, resource.closeCalls.get())
            assertEquals(1, allowanceCloses.get())
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
