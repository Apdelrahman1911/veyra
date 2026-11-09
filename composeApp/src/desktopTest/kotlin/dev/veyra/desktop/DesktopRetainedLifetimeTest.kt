package dev.veyra.desktop

import dev.kmpyt.YouTubeClient
import dev.kmpyt.extraction.ResearchTransport
import dev.veyra.app.AppContainer
import dev.veyra.app.AppInitialization
import dev.veyra.app.PlatformServices
import dev.veyra.data.StoreStatus
import dev.veyra.data.createPlatformLibraryStore
import dev.veyra.data.getOrThrow
import dev.veyra.desktop.DesktopServiceResources.Slot
import dev.veyra.desktop.runtime.AlreadyRunningException
import dev.veyra.desktop.runtime.DesktopProcessContext
import dev.veyra.desktop.runtime.DesktopStartupJobs
import dev.veyra.downloads.DownloadExecutionGate
import dev.veyra.downloads.DownloadExecutionLease
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.FirstResultKind
import dev.veyra.model.LanguagePreference
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.PlaybackBinding
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.model.VeyraException
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.AssetValidator
import dev.veyra.playback.AudioEngine
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.EngineEvent
import dev.veyra.playback.PlaybackResource
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.execution.KmpFirstResult
import dev.veyra.source.execution.KmpMediaGateway
import dev.veyra.source.network.SafeHttp
import dev.veyra.source.network.SafeHttpBytes
import dev.veyra.source.network.SafeHttpRequest
import dev.veyra.source.network.SafeHttpResponse
import dev.veyra.source.youtube.YouTubeMediaSource
import dev.veyra.source.youtube.YouTubeTransferProgress
import dev.veyra.source.youtube.YouTubeTransferResult
import dev.veyra.ui.music.MusicUiAction
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Actual AppContainer/download ownership and Room, plus real repository-private process locks.
 * Audio/validation/transfer/OS allowance are explicit fakes; bytes are authored NON-AUDIO.
 * Host services use inert slot sentinels through the real createOwned/closeAcquiredResources path.
 * This is the generic accepted-owner contract, NOT a claim that normal YouTube DOWNLOAD plans
 * currently return a SABR PlaybackResource, or proof of native/audio/physical-device execution.
 */
class DesktopRetainedLifetimeTest {
    private class NoAudioEngine : AudioEngine {
        override val capabilities = EngineCapabilities()
        override val events = MutableSharedFlow<EngineEvent>()
        val closeCalls = AtomicInteger()
        val preparationCalls = AtomicInteger()
        override suspend fun prepare(source: ResolvedPlayable, startPositionMs: Long, generation: Long) {
            preparationCalls.incrementAndGet()
            error("This host-lifetime fixture did not authorize playback")
        }
        override suspend fun play(generation: Long) = Unit
        override suspend fun pause(generation: Long) = Unit
        override suspend fun seek(positionMs: Long, seekSequence: Long, generation: Long) = Unit
        override suspend fun setRate(speed: Float, generation: Long) = Unit
        override suspend fun stop(generation: Long) = Unit
        override suspend fun close() { closeCalls.incrementAndGet() }
    }

    private class NoHttp : SafeHttp {
        val requests = AtomicInteger()
        val closeCalls = AtomicInteger()
        private fun denied(): Nothing {
            requests.incrementAndGet()
            error("This host-lifetime fixture did not authorize HTTP or DNS")
        }
        override suspend fun get(request: SafeHttpRequest, maxBytes: Int): SafeHttpBytes = denied()
        override suspend fun <T> stream(request: SafeHttpRequest, consume: suspend (SafeHttpResponse, ByteReadChannel) -> T): T = denied()
        override suspend fun validateUrl(url: String): String = denied()
        override fun close() { closeCalls.incrementAndGet() }
    }

    private class Resource : PlaybackResource {
        val closeCalls = AtomicInteger()
        override suspend fun close() { closeCalls.incrementAndGet() }
    }

    private class PlatformProbe(directory: Path) : PlatformServices {
        override val dataDirectory = directory.toString()
        override val store = createPlatformLibraryStore(directory.resolve("library").toString())
        override val engine = NoAudioEngine()
        override val validator = AssetValidator { AssetValidation.Valid(1_000L) }
        override val systemLanguage = LanguagePreference.ENGLISH
        val youtubeRequests = AtomicInteger()
        val youtubeCloseCalls = AtomicInteger()
        override val youtube = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            youtubeRequests.incrementAndGet()
            error("This host-lifetime fixture did not authorize a source request")
        }, onClose = { youtubeCloseCalls.incrementAndGet() }))
        val runtimeCloseCalls = AtomicInteger()
        override suspend fun closeMediaRuntime() { runtimeCloseCalls.incrementAndGet() }
        override suspend fun openExternalUrl(url: String): Unit = error("No external URL is authorized")
        val allowanceAcquires = AtomicInteger()
        val allowanceCloses = AtomicInteger()
        val allowanceConsumed = AtomicBoolean()
        val permitAllowanceClose = AtomicBoolean()
        val cleanupActive = ConcurrentLinkedQueue<Boolean>()
        @Volatile var beforeAllowanceClose: suspend (Int) -> Unit = {}
        override val downloadExecution = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
                allowanceAcquires.incrementAndGet()
                return DownloadExecutionLease {
                    val attempt = allowanceCloses.incrementAndGet()
                    cleanupActive += currentCoroutineContext().isActive
                    beforeAllowanceClose(attempt)
                    if (!permitAllowanceClose.get()) throw IllegalStateException("authored private allowance was not consumed")
                    allowanceConsumed.set(true)
                }
            }
        }
    }

    private class GatewayProbe : KmpMediaGateway {
        val resource = Resource()
        val queries = AtomicInteger()
        val resolutions = AtomicInteger()
        val transfers = AtomicInteger()
        override suspend fun firstResult(completeTitle: String): KmpFirstResult {
            queries.incrementAndGet()
            assertEquals(ITEM.title, completeTitle)
            return KmpFirstResult(completeTitle, FirstResultKind.VIDEO, FIRST, "Authored selected recording",
                "https://www.youtube.com/watch?v=$FIRST")
        }
        override suspend fun resolve(binding: PlaybackBinding, capabilities: EngineCapabilities, purpose: ResolvePurpose): ResolvedPlayable {
            resolutions.incrementAndGet()
            assertEquals(ResolvePurpose.DOWNLOAD, purpose)
            return ResolvedPlayable("youtube:$FIRST", "https://media.example.com/authored-no-network",
                mimeType = "audio/mpeg", codecs = "mp3", durationMs = 1_000L, representationId = REPRESENTATION,
                item = MediaItem("youtube:$FIRST", "Authored selected recording", MediaKind.MUSIC,
                    SourceRef(SourceProvider.YOUTUBE, FIRST)), resource = resource)
        }
        override suspend fun download(binding: PlaybackBinding, destinationPath: String, capabilities: EngineCapabilities,
            representationId: String?, onProgress: suspend (YouTubeTransferProgress) -> Unit): YouTubeTransferResult {
            transfers.incrementAndGet()
            Files.write(Path.of(destinationPath), NON_AUDIO)
            onProgress(YouTubeTransferProgress(NON_AUDIO.size.toLong(), NON_AUDIO.size.toLong()))
            return YouTubeTransferResult(NON_AUDIO.size.toLong(), "audio/mpeg", "mp3", "mp3", REPRESENTATION)
        }
    }

    private class HostSlot(val slot: Slot) {
        val closeCalls = AtomicInteger()
        val consumed = AtomicBoolean()
    }

    private class Fixture(val directory: Path) {
        val context = DesktopProcessContext.acquire(directory)
        val host = DesktopLifetime(context)
        val hostJob = checkNotNull(host.scope.coroutineContext[Job])
        private val previousChildren = hostJob.children.toSet()
        val platform = PlatformProbe(directory)
        val http = NoHttp()
        val gateway = GatewayProbe()
        val app = AppContainer(platform, host.scope, http, gateway).also { host.container = it }
        // Capture the actual AppContainer child, not the host's callback SupervisorJob.
        val appJob = hostJob.children.single { it !in previousChildren }
        val slots = Slot.entries.associateWith(::HostSlot)
        val verificationCloses = AtomicInteger()

        suspend fun attachHostOwners() {
            DesktopPlatformServices.createOwned(context, silentAudio = true, onOwned = { host.services = it }) {
                for (slot in Slot.entries) acquire(slot, release = { value: HostSlot ->
                    value.closeCalls.incrementAndGet() // Count even an incorrectly early attempt.
                    assertTrue(app.dependencyReleaseSafeAfterClose)
                    assertFalse(app.retainedForCleanup)
                    value.consumed.set(true)
                }) { slots.getValue(slot) }
            }
            host.verification = AutoCloseable {
                verificationCloses.incrementAndGet()
                assertTrue(app.dependencyReleaseSafeAfterClose)
                assertFalse(app.retainedForCleanup)
            }
        }

        suspend fun failInitialAllowanceCleanup() {
            assertFalse(app.dependencyReleaseSafeAfterClose)
            assertFalse(app.retainedForCleanup, "An empty pin registry is not a positive release receipt")
            app.initialize()
            assertEquals(AppInitialization.Ready, app.initialization.value)
            app.onMusicAction(MusicUiAction.Download(ITEM))
            app.downloads.lastFailure.first { it == CLEANUP_FAILURE }
            val asset = assertNotNull(platform.store.state.value.downloads.getValue(app.files.key(ITEM.id)).asset)
            assertContentEquals(NON_AUDIO, Files.readAllBytes(Path.of(asset.uri)))
            assertEquals(1, gateway.resource.closeCalls.get())
            assertEquals(1, platform.allowanceCloses.get())
            assertFalse(platform.allowanceConsumed.get())
        }

        suspend fun assertRetained(engineCloses: Int = 1) {
            assertTrue(app.retainedForCleanup)
            assertFalse(app.dependencyReleaseSafeAfterClose)
            assertTrue(host.retainedForCleanup, "The host/context need their own strong process pin")
            assertTrue(hostJob.isActive)
            assertTrue(appJob.isActive)
            assertEquals(engineCloses, platform.engine.closeCalls.get())
            assertEquals(0, platform.runtimeCloseCalls.get())
            assertEquals(0, platform.youtubeCloseCalls.get())
            assertEquals(0, http.closeCalls.get())
            assertIs<StoreStatus.Ready>(platform.store.status.value)
            platform.store.flush().getOrThrow() // Actual Room still works while cleanup needs it.
            assertTrue(slots.values.all { it.closeCalls.get() == 0 && !it.consumed.get() })
            assertEquals(0, verificationCloses.get())
            assertFalse(host.submitMediaCallback { error("Retired callback must never run") })
            assertFailsWith<AlreadyRunningException> { DesktopProcessContext.acquire(directory).close() }
        }

        suspend fun assertReleased() {
            assertFalse(app.retainedForCleanup)
            assertTrue(app.dependencyReleaseSafeAfterClose)
            assertFalse(host.retainedForCleanup)
            assertTrue(hostJob.isCancelled)
            withTimeout(10_000L) { hostJob.join() }
            assertTrue(hostJob.isCompleted)
            assertTrue(appJob.isCompleted)
            assertEquals(1, platform.engine.closeCalls.get())
            assertEquals(1, platform.runtimeCloseCalls.get())
            assertEquals(1, platform.youtubeCloseCalls.get())
            assertEquals(1, http.closeCalls.get())
            assertEquals(StoreStatus.Closed, platform.store.status.value)
            assertTrue(slots.values.all { it.closeCalls.get() == 1 && it.consumed.get() })
            assertEquals(1, verificationCloses.get())
            assertEquals(0, http.requests.get())
            assertEquals(0, platform.youtubeRequests.get())
            assertEquals(0, platform.engine.preparationCalls.get())
            assertTrue(platform.cleanupActive.all { it })
        }

        suspend fun finish() {
            platform.permitAllowanceClose.set(true)
            // Only the deliberately latched cleanup classification is allowed during teardown.
            val error = runCatching { host.close() }.exceptionOrNull()
            if (error != null) assertEquals(CLEANUP_FAILURE, assertIs<VeyraException>(error).failure)
            assertReleased()
            DesktopProcessContext.acquire(directory).close()
        }
    }

    private fun checkCase(block: suspend Fixture.() -> Unit): Unit = runBlocking {
        val fixture = Fixture(workDirectory())
        var primary: Throwable? = null
        try {
            withTimeout(45_000L) {
                fixture.attachHostOwners()
                fixture.block()
            }
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            withContext(NonCancellable) {
                try { fixture.finish() }
                catch (error: Throwable) { primary?.addSuppressed(error) ?: throw error }
            }
        }
    }

    @Test fun retainedDownloadAllowancePinsHostGraphAndLockUntilExplicitRetryAcknowledgesConsumption() = checkCase {
        failInitialAllowanceCleanup()
        val startupEntered = CompletableDeferred<Unit>()
        val callbackEntered = CompletableDeferred<Unit>()
        val startupFinalizers = AtomicInteger()
        val callbackFinalizers = AtomicInteger()
        val startup = host.scope.launch {
            try { startupEntered.complete(Unit); awaitCancellation() }
            finally { startupFinalizers.incrementAndGet() }
        }
        assertTrue(host.startupJobs.admit(DesktopStartupJobs.Kind.MEDIA_SESSION, startup))
        assertTrue(host.submitMediaCallback {
            try { callbackEntered.complete(Unit); awaitCancellation() }
            finally { callbackFinalizers.incrementAndGet() }
        })
        startupEntered.await(); callbackEntered.await()
        val first = assertFailsWith<VeyraException> { host.close() }
        assertEquals(CLEANUP_FAILURE, first.failure)
        assertNull(first.cause)
        assertTrue(first.suppressedExceptions.isEmpty())
        assertEquals(2, platform.allowanceCloses.get())
        assertEquals(1, gateway.resource.closeCalls.get())
        assertRetained()
        assertTrue(startup.isCompleted)
        assertEquals(1, startupFinalizers.get())
        assertEquals(1, callbackFinalizers.get())
        val lateStartup = Job()
        try { assertFalse(host.startupJobs.admit(DesktopStartupJobs.Kind.PLATFORM, lateStartup)) }
        finally { lateStartup.cancel() }

        platform.permitAllowanceClose.set(true)
        assertSame(first, runCatching { host.close() }.exceptionOrNull(), "Consumption cannot erase the first failed outcome")
        assertReleased()
        assertEquals(3, platform.allowanceCloses.get())
        assertTrue(platform.allowanceConsumed.get())
        assertEquals(1, gateway.resource.closeCalls.get())
        assertEquals(1, gateway.queries.get())
        assertEquals(1, gateway.resolutions.get())
        assertEquals(1, gateway.transfers.get(), "Cleanup retries must never replay a transfer")
        DesktopProcessContext.acquire(directory).use {
            assertSame(first, runCatching { host.close() }.exceptionOrNull())
            assertFailsWith<AlreadyRunningException> { DesktopProcessContext.acquire(directory).close() }
        }
        assertReleased()
        assertEquals(3, platform.allowanceCloses.get())
        assertEquals(1, startupFinalizers.get())
        assertEquals(1, callbackFinalizers.get())
    }

    @Test fun concurrentAndCancelledCallersShareOneRetainedAttemptWithoutDuplicatingCleanup() = checkCase {
        failInitialAllowanceCleanup()
        val first = assertFailsWith<VeyraException> { host.close() }
        assertEquals(CLEANUP_FAILURE, first.failure)
        val retryEntered = CompletableDeferred<Unit>()
        val finishRetry = CompletableDeferred<Unit>()
        platform.beforeAllowanceClose = { attempt ->
            if (attempt == 3) { retryEntered.complete(Unit); finishRetry.await() }
        }
        try {
            supervisorScope {
                val leader = async(start = CoroutineStart.UNDISPATCHED) { runCatching { host.close() }.exceptionOrNull() }
                try {
                    retryEntered.await()
                    val other = async(start = CoroutineStart.UNDISPATCHED) { runCatching { host.close() }.exceptionOrNull() }
                    val cancelledOutcome = CompletableDeferred<Throwable?>()
                    val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
                        cancelledOutcome.complete(runCatching { host.close() }.exceptionOrNull())
                    }
                    cancelled.cancel(CancellationException("authored caller cancellation"))
                    assertFalse(leader.isCompleted)
                    assertFalse(other.isCompleted)
                    assertFalse(cancelled.isCompleted)
                    assertFalse(cancelledOutcome.isCompleted)
                    assertEquals(3, platform.allowanceCloses.get())
                    assertRetained()
                    // This attempt still fails. A mutex-only queue of explicit retries would
                    // incorrectly call the retained lease again for each already-waiting caller.
                    finishRetry.complete(Unit)
                    assertSame(first, leader.await())
                    assertSame(first, other.await())
                    cancelled.join()
                    assertSame(first, cancelledOutcome.await())
                    assertEquals(3, platform.allowanceCloses.get())
                    assertRetained()
                } finally { finishRetry.complete(Unit) }
            }
        } finally { finishRetry.complete(Unit) }
        platform.permitAllowanceClose.set(true)
        assertSame(first, runCatching { host.close() }.exceptionOrNull())
        assertReleased()
        assertEquals(4, platform.allowanceCloses.get())
        assertEquals(1, gateway.resource.closeCalls.get())
        assertEquals(1, gateway.transfers.get())
    }

    @Test fun cancellationCannotReleaseTheHostWhileFirstApplicationCloseStillAwaitsItsPositiveReceipt() = checkCase {
        failInitialAllowanceCleanup()
        val cleanupEntered = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        platform.beforeAllowanceClose = { attempt ->
            if (attempt == 2) { cleanupEntered.complete(Unit); finishCleanup.await() }
        }
        val outcome = CompletableDeferred<Throwable?>()
        try {
            supervisorScope {
                val closing = launch(start = CoroutineStart.UNDISPATCHED) {
                    outcome.complete(runCatching { host.close() }.exceptionOrNull())
                }
                try {
                    cleanupEntered.await()
                    closing.cancel(CancellationException("authored first close caller cancellation"))
                    assertFalse(closing.isCompleted)
                    assertFalse(outcome.isCompleted)
                    assertRetained(engineCloses = 0)
                    platform.permitAllowanceClose.set(true)
                    finishCleanup.complete(Unit)
                    closing.join()
                } finally { finishCleanup.complete(Unit) }
            }
        } finally { finishCleanup.complete(Unit) }
        val first = assertIs<VeyraException>(outcome.await())
        assertEquals(CLEANUP_FAILURE, first.failure)
        assertReleased()
        assertTrue(platform.allowanceConsumed.get())
        assertEquals(2, platform.allowanceCloses.get())
        assertEquals(1, gateway.resource.closeCalls.get())
        assertSame(first, runCatching { host.close() }.exceptionOrNull())
        assertEquals(2, platform.allowanceCloses.get())
    }

    @Test fun neverInitializedApplicationMustCloseAndAcknowledgeBeforeTheHostReleasesItsLock() = checkCase {
        assertEquals(AppInitialization.Loading, app.initialization.value)
        assertEquals(StoreStatus.Uninitialized, platform.store.status.value)
        assertFalse(app.retainedForCleanup)
        assertFalse(app.dependencyReleaseSafeAfterClose)
        host.close()
        assertReleased()
        assertEquals(0, platform.allowanceAcquires.get())
        assertEquals(0, gateway.queries.get())
        assertEquals(0, gateway.transfers.get())
        DesktopProcessContext.acquire(directory).close()
        host.close()
        assertReleased()
    }

    @Test fun partialStartupWithoutAnApplicationStillClosesPublishedServiceSlotsExactlyOnce(): Unit = runBlocking {
        val directory = workDirectory()
        val context = DesktopProcessContext.acquire(directory)
        val host = DesktopLifetime(context)
        val closedSlots = ConcurrentLinkedQueue<Slot>()
        val verificationCloses = AtomicInteger()
        try {
            DesktopPlatformServices.createOwned(context, silentAudio = true, onOwned = { host.services = it }) {
                for (slot in listOf(Slot.ENGINE, Slot.STORE)) {
                    acquire(slot, release = { value: Slot -> closedSlots.add(value); Unit }) { slot }
                }
            }
            host.verification = AutoCloseable { verificationCloses.incrementAndGet(); Unit }
            assertNull(host.container)
            assertFailsWith<AlreadyRunningException> { DesktopProcessContext.acquire(directory).close() }
            withTimeout(10_000L) { host.close(); checkNotNull(host.scope.coroutineContext[Job]).join() }
            assertFalse(host.retainedForCleanup)
            assertEquals(listOf(Slot.ENGINE, Slot.STORE), closedSlots.toList())
            assertEquals(1, verificationCloses.get())
            DesktopProcessContext.acquire(directory).close()
            host.close()
            assertEquals(2, closedSlots.size)
            assertEquals(1, verificationCloses.get())
        } finally { host.close(); checkNotNull(host.scope.coroutineContext[Job]).join() }
    }

    private companion object {
        const val FIRST = "AAAAAAAAAAA"
        const val REPRESENTATION = "authored-host-lifetime-representation"
        val CLEANUP_FAILURE = AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_failed_restart_required")
        val NON_AUDIO = "Authored host ownership fixture. NOT AUDIO; validator is deliberately fake.".encodeToByteArray()
        val ITEM = MediaItem("authored-host-retained-download", "Complete authored title — العنوان الكامل", MediaKind.MUSIC,
            SourceRef(SourceProvider.COMMONS, "authored-host-retained-download"))

        fun workDirectory(): Path {
            val root = Path.of(requireNotNull(System.getenv("VEYRA_PROJECT_ROOT")))
                .resolve(".work/desktop-audio/retained-lifetime-tests")
            Files.createDirectories(root)
            return Files.createTempDirectory(root, "authored-room-")
        }
    }
}
