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
import dev.veyra.model.*
import dev.veyra.playback.*
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    private class NativeProbe : AudioEngine {
        override val capabilities = EngineCapabilities()
        private val callbacks = Channel<EngineEvent>(Channel.UNLIMITED)
        override val events: Flow<EngineEvent> = callbacks.receiveAsFlow()
        private val lock = Mutex()
        private var generation = 0L
        private var owned: PlaybackResource? = null
        private var rate = 1f
        val preparations = ConcurrentLinkedQueue<Preparation>()
        val prepared = Channel<Preparation>(Channel.UNLIMITED)
        val closeCalls = AtomicInteger()
        @Volatile var beforeStop: suspend (Long) -> Unit = {}

        override suspend fun prepare(source: ResolvedPlayable, startPositionMs: Long, generation: Long) = lock.withLock {
            if (generation < this.generation) { source.resource?.close(); return@withLock }
            owned?.close()
            owned = source.resource
            this.generation = generation
            val preparation = Preparation(source, startPositionMs, generation)
            preparations += preparation
            prepared.send(preparation)
            callbacks.send(EngineEvent.Ready(generation, source.durationMs, seekable = true))
        }
        override suspend fun play(generation: Long) = lock.withLock {
            if (generation == this.generation) callbacks.send(EngineEvent.StateChanged(generation, EnginePhase.PLAYING, true, rate))
        }
        override suspend fun pause(generation: Long) = lock.withLock {
            if (generation == this.generation) callbacks.send(EngineEvent.StateChanged(generation, EnginePhase.PAUSED, false, rate))
        }
        override suspend fun seek(positionMs: Long, seekSequence: Long, generation: Long) = lock.withLock {
            if (generation == this.generation) callbacks.send(EngineEvent.SeekCompleted(generation, seekSequence, positionMs))
        }
        override suspend fun setRate(speed: Float, generation: Long) = lock.withLock {
            if (generation == this.generation) rate = speed
        }
        override suspend fun stop(generation: Long) = lock.withLock {
            if (generation >= this.generation) {
                beforeStop(generation)
                this.generation = generation
                val previous = owned
                owned = null
                previous?.close()
            }
        }
        override suspend fun close() {
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
        override val engine = NativeProbe()
        val validations = ConcurrentLinkedQueue<OfflineAsset>()
        @Volatile var validateAsset: suspend (OfflineAsset) -> AssetValidation = { AssetValidation.Valid(12_345L) }
        override val validator = AssetValidator { asset -> validations += asset; validateAsset(asset) }
        override val systemLanguage = LanguagePreference.ENGLISH
        val youtubeRequests = ConcurrentLinkedQueue<ResearchRequest>()
        val youtubeCloseCalls = AtomicInteger()
        val runtimePrepareCalls = AtomicInteger()
        val runtimeCloseCalls = AtomicInteger()
        val externalUrls = ConcurrentLinkedQueue<String>()
        @Volatile var acquireDownload: suspend (suspend () -> Unit) -> DownloadExecutionLease = { DownloadExecutionLease {} }
        @Volatile var beforeRuntimeClose: suspend () -> Unit = {}
        override val downloadExecution = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease = acquireDownload(onExpiration)
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

    private class GatewayProbe(private val store: LibraryStore) : KmpMediaGateway {
        val queries = ConcurrentLinkedQueue<String>()
        val resolutions = ConcurrentLinkedQueue<Resolution>()
        val transfers = ConcurrentLinkedQueue<Transfer>()
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
    }

    private class Fixture(val directory: Path, productionGateway: Boolean) {
        val uncaught = ConcurrentLinkedQueue<Throwable>()
        private val lifetime = SupervisorJob()
        private val scope = CoroutineScope(lifetime + Dispatchers.Default + CoroutineExceptionHandler { _, failure -> uncaught += failure })
        val platform = PlatformProbe(directory)
        val http = HttpProbe()
        val gateway = GatewayProbe(platform.store)
        val app = AppContainer(platform, scope, http,
            if (productionGateway) YouTubeKmpMediaGateway(platform.youtube) else gateway)
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
        suspend fun play(item: MediaItem): Preparation {
            app.onMusicAction(MusicUiAction.Play(item))
            val preparation = platform.engine.prepared.receive()
            app.playback.state.first { it.currentItem?.id == item.id && it.isPlaying }
            return preparation
        }
    }

    private class Case(val directory: Path) {
        private val fixtures = mutableListOf<Fixture>()
        suspend fun newFixture(initialize: Boolean = true, productionGateway: Boolean = false): Fixture =
            Fixture(directory, productionGateway).also { fixtures += it; if (initialize) it.app.initialize() }
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
            assertEquals(binding, fixture.store.state.value.playbackBindings[binding.id])
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
        val saved = fixture.store.state.first { snapshot -> snapshot.downloads.values.any { it.status == DownloadStatus.COMPLETE } }
        val record = saved.downloads.values.single()
        val asset = assertNotNull(record.asset)
        assertEquals(binding.id, record.executionBindingId)
        assertEquals(binding.id, asset.executionBindingId)
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
        val offline = reopened.play(original)
        assertTrue(offline.source.isLocal)
        assertEquals(asset.uri, offline.source.uri)
        assertEquals(binding, offline.source.executionBinding)
        assertEquals(original, offline.source.item)
        assertTrue(reopened.gateway.queries.isEmpty())
        assertTrue(reopened.gateway.resolutions.isEmpty())
        assertTrue(reopened.gateway.transfers.isEmpty())
        assertTrue(reopened.platform.validations.isNotEmpty(), "Owned hash and fake native contract must both be checked")
        reopened.assertNoNetwork()
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

    private companion object {
        const val FIRST = "AAAAAAAAAAA"
        const val DISCOVERED_YOUTUBE = "BBBBBBBBBBB"
        const val REPRESENTATION = "authored-kmpyt-representation"
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
