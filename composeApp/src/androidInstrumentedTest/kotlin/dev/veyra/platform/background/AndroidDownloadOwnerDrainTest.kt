package dev.veyra.platform.background

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veyra.acquisition.AcquisitionCaptureFactory
import dev.veyra.acquisition.AcquisitionCoordinator
import dev.veyra.acquisition.DownloadBatchCoordinator
import dev.veyra.data.createAndroidLibraryStore
import dev.veyra.data.getOrThrow
import dev.veyra.downloads.DownloadActivation
import dev.veyra.downloads.DownloadCoordinator
import dev.veyra.downloads.DownloadExecutionGate
import dev.veyra.downloads.DownloadExecutionLease
import dev.veyra.downloads.DownloadFiles
import dev.veyra.downloads.DownloadIdleDecision
import dev.veyra.downloads.DownloadMaterializationEvidence
import dev.veyra.model.*
import dev.veyra.downloads.DownloadAcquisitionDependencies
import dev.veyra.playback.LocalMediaValidator
import dev.veyra.playback.SystemClock
import dev.veyra.source.execution.*
import dev.veyra.source.youtube.YouTubeTransferProgress
import dev.veyra.source.youtube.YouTubeTransferResult
import dev.veyra.playback.AssetValidator
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.PlaybackResource
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.source.ResolvePurpose
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Authored integration of the actual Android gate + shared coordinator + private real Room/files.
 * Uses current typed acquisition and physical-download commands over the same Room owner.
 * WorkManager/FGS ACKs, kmpYT and source-resource close are deliberate fakes; bytes are authored
 * NON-AUDIO. These tests do NOT prove OS scheduling, cold process/reboot, a real source or audio.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDownloadOwnerDrainTest {
    @Test fun failedWorkerCannotFinishWhileCancelledSourceResourceCloseIsStillBlocked() =
        exerciseDrain(UnwindPoint.SOURCE_RESOURCE)

    @Test fun zeroNativeLeasesCannotFinishWorkerBeforeSharedRoomAndFileOwnerUnwind() =
        exerciseDrain(UnwindPoint.AFTER_NATIVE_ALLOWANCE_CLOSE)

    @Test fun retainedAllowanceCloseFailureWithZeroNativeLeasesCannotAcknowledgeForegroundDrain() = runBlocking {
        val fixture = Fixture(UnwindPoint.AFTER_NATIVE_ALLOWANCE_CLOSE_THROW_ONCE)
        try {
            val id = withTimeout(10_000) { fixture.start() }
            assertEquals(1, fixture.nativeGate.leaseCount.value)
            // A user pause remains admissible and joins the transfer. Its fake allowance consumes
            // the actual native lease, then throws before shared can certify dependent cleanup.
            val pauseFailure = withTimeout(5_000) {
                runCatching { fixture.coordinator.pause(id) }.exceptionOrNull()
            } as VeyraException
            assertEquals(AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_unconfirmed"), pauseFailure.failure)
            val failure = AppFailure(FailureCode.BACKGROUND_RESTRICTED, RESTART_REQUIRED)
            assertEquals(0, fixture.nativeGate.leaseCount.value)
            assertEquals(1, fixture.allowanceCloses.get())
            assertTrue("A joined coroutine is not consumption of its failed close owner",
                fixture.coordinator.hasPendingCleanupOwners)
            assertEquals(failure, fixture.coordinator.lastFailure.value)
            assertEquals(failure, fixture.coordinator.schedulingPlan().blockedReason)
            val held = fixture.record(id)
            assertEquals(DownloadStatus.PAUSED, held.status)
            assertNotNull("No dependent Room release before successful allowance return", held.leaseOwner)
            assertNotNull(held.activeAttemptId)
            val idle = runCatching { fixture.coordinator.idleDecision() }.exceptionOrNull() as VeyraException
            assertEquals(failure, idle.failure)

            val ended = withTimeout(5_000) {
                runCatching { fixture.nativeGate.foregroundEnded(OLD_WORKER) }.exceptionOrNull()
            } as VeyraException
            assertEquals(failure, ended.failure)
            assertEquals(1, fixture.drains.get())
            assertFalse("Retained owner must never be reported as an independent drain ACK", fixture.drainReturned.isCompleted)
            assertTrue(fixture.coordinator.hasPendingCleanupOwners)
            for (operation in listOf<suspend () -> Unit>(
                { fixture.nativeGate.awaitForegroundReady() },
                { fixture.nativeGate.foregroundStarted(NEXT_WORKER); Unit },
            )) {
                val rejected = withTimeout(2_000) { runCatching { operation() }.exceptionOrNull() } as VeyraException
                assertEquals(failure, rejected.failure)
            }

            // An explicit host close retries the retained allowance exactly once. Successful
            // consumption releases dependent file/Room ownership, but never erases the first error.
            val closed = withTimeout(5_000) {
                runCatching { fixture.coordinator.close() }.exceptionOrNull()
            } as VeyraException
            assertEquals(failure, closed.failure)
            assertFalse(fixture.coordinator.hasPendingCleanupOwners)
            assertTrue(fixture.coordinator.dependencyReleaseSafeAfterClose)
            assertEquals(2, fixture.allowanceCloses.get())
            val released = fixture.record(id)
            assertEquals(DownloadStatus.PAUSED, released.status)
            assertNull(released.leaseOwner)
            assertNull(released.activeAttemptId)
            assertEquals(1, released.attemptCount)
            val repeatedClose = runCatching { fixture.coordinator.close() }.exceptionOrNull() as VeyraException
            assertEquals(failure, repeatedClose.failure)
            assertEquals("Already consumed stages must not run again", 2, fixture.allowanceCloses.get())
            val stillRestricted = runCatching { fixture.nativeGate.foregroundStarted(NEXT_WORKER) }
                .exceptionOrNull() as VeyraException
            assertEquals(failure, stillRestricted.failure)
            assertEquals(1, fixture.drains.get())
            assertEquals(1, fixture.transfers.get())
        } finally { fixture.close(allowKnownStickyFailure = true) }
    }

    private fun exerciseDrain(point: UnwindPoint) = runBlocking {
        val fixture = Fixture(point)
        try {
            val id = withTimeout(10_000) { fixture.start() }
            assertEquals(1, fixture.nativeGate.leaseCount.value)
            val ended = async { fixture.nativeGate.foregroundEnded(OLD_WORKER) }
            val reentry = async {
                fixture.drainEntered.await()
                fixture.nativeGate.foregroundStarted(NEXT_WORKER)
            }
            try {
                withTimeout(5_000) { fixture.unwindEntered.await(); fixture.drainEntered.await() }
                assertEquals(if (point == UnwindPoint.SOURCE_RESOURCE) 1 else 0, fixture.nativeGate.leaseCount.value)
                assertFalse("A cancellation callback is not a source/native owner join", ended.isCompleted)
                assertFalse("Foreground resume must await both shared and native drain ACK", reentry.isCompleted)
                assertFalse(fixture.drainReturned.isCompleted)
                assertEquals(DownloadIdleDecision.OwnedWork, fixture.coordinator.idleDecision())
                val during = fixture.record(id)
                assertNotNull("Room ownership must remain until actual unwind", during.leaseOwner)
                assertNotNull(during.activeAttemptId)
                val rejected = runCatching { fixture.coordinator.resumePending() }.exceptionOrNull() as VeyraException
                assertEquals(FailureCode.BACKGROUND_RESTRICTED, rejected.failure.code)
                ended.cancel() // Worker cancellation must not abandon an already accepted drain.
                assertNull(withTimeoutOrNull(100) { ended.join(); true })
                fixture.finishUnwind.complete(Unit)
                withTimeout(5_000) { ended.join(); assertTrue(reentry.await()); fixture.drainReturned.await() }

                val after = fixture.record(id)
                assertNull(after.leaseOwner)
                assertNull(after.activeAttemptId)
                assertEquals(DownloadStatus.NEEDS_FOREGROUND, after.status)
                assertEquals(1, after.attemptCount)
                assertEquals(0, fixture.nativeGate.leaseCount.value)
                assertEquals(1, fixture.transfers.get())
                assertTrue(fixture.coordinator.idleDecision() is DownloadIdleDecision.Deferred)
                assertTrue(fixture.nativeGate.retireIfIdle(NEXT_WORKER))
                fixture.nativeGate.foregroundEnded(OLD_WORKER) // Exact retired identity is inert.
                assertEquals(1, fixture.drains.get())
            } finally {
                fixture.finishUnwind.complete(Unit)
                ended.cancelAndJoin(); reentry.cancelAndJoin()
            }
        } finally { fixture.close() }
    }

    private enum class UnwindPoint {
        SOURCE_RESOURCE, AFTER_NATIVE_ALLOWANCE_CLOSE, AFTER_NATIVE_ALLOWANCE_CLOSE_THROW_ONCE,
    }

    private class Fixture(private val point: UnwindPoint) {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        // Android may expose its trusted cache directory through /data/user/0.
        // Resolve that OS alias before creating our owned fixture children, as production does.
        private val directory = File(context.cacheDir.canonicalFile, "download-owner-drain-${UUID.randomUUID()}")
            .apply { check(mkdirs()) }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val library = createAndroidLibraryStore(context, directory.absolutePath)
        private val files = DownloadFiles(File(directory, "owned-media").absolutePath)
        val unwindEntered = CompletableDeferred<Unit>()
        val finishUnwind = CompletableDeferred<Unit>()
        val drainEntered = CompletableDeferred<Unit>()
        val drainReturned = CompletableDeferred<Unit>()
        private val transferStarted = CompletableDeferred<Unit>()
        val transfers = AtomicInteger()
        val drains = AtomicInteger()
        val allowanceCloses = AtomicInteger()
        val nativeGate: AndroidDownloadExecutionGate = AndroidDownloadExecutionGate(
            ensureCoverage = {}, isWorkUnfinished = { true },
            drainOwners = {
                drains.incrementAndGet(); drainEntered.complete(Unit)
                coordinator.drainForBackground().also { drainReturned.complete(Unit) }
            },
        ) { error("This fixture supplies an explicit fake foreground ACK; no actual WorkManager enqueue") }
        private val executionGate: DownloadExecutionGate = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
                val native = nativeGate.acquire(onExpiration)
                return DownloadExecutionLease {
                    native.close()
                    val attempt = allowanceCloses.incrementAndGet()
                    if (point == UnwindPoint.AFTER_NATIVE_ALLOWANCE_CLOSE) stopUnwind()
                    if (point == UnwindPoint.AFTER_NATIVE_ALLOWANCE_CLOSE_THROW_ONCE && attempt == 1) {
                        throw AssertionError("authored private native close diagnostics")
                    }
                }
            }
        }
        private val validator = AssetValidator { error("Cancelled authored NON-AUDIO fixture must never validate") }
        private val gateway = object : KmpMediaGateway {
            override suspend fun firstResult(completeTitle: String): KmpFirstResult? =
                error("Typed admission must not use the legacy selector")
            override suspend fun resolve(binding: PlaybackBinding, capabilities: EngineCapabilities,
                purpose: ResolvePurpose): ResolvedPlayable = error("Typed download must not open a playback source")
            override suspend fun download(binding: PlaybackBinding, destinationPath: String, capabilities: EngineCapabilities,
                representationId: String?, onProgress: suspend (YouTubeTransferProgress) -> Unit): YouTubeTransferResult =
                error("Typed download must not use a legacy transfer")
        }
        private val selector = object : AcquisitionGateway {
            override suspend fun acquire(request: AcquisitionRequest): SourceAcquisitionResult {
                val url = "https://www.youtube.com/watch?v=$VIDEO"
                return restore(request, FirstResultObservation(request.id, request.completeTitle,
                    SourceEntry(AcquisitionEntryKind.VIDEO, VIDEO, "Authored resolved fixture", url), url))
            }
            override fun restore(request: AcquisitionRequest, receipt: FirstResultObservation): SourceAcquisitionResult {
                check(receipt.requestId == request.id && receipt.queryTitle == request.completeTitle && receipt.entry.ordinal == 0)
                return SourceAcquisitionResult.Single(request.id, receipt, SourceDisplayItem(VIDEO,
                    requireNotNull(receipt.entry.title), requireNotNull(receipt.canonicalUrl), AcquisitionEntryKind.VIDEO))
            }
            override suspend fun collection(request: AcquisitionRequest, selection: SourceAcquisitionResult.Collection,
                limits: CollectionLimits): SourceCollectionPage = error("No collection in this authored fixture")
            override suspend fun more(continuation: CollectionContinuation): SourceCollectionPage = error("No continuation")
            override suspend fun inspect(request: AcquisitionRequest, selection: SourceAcquisitionResult.Single): AcquisitionInspection =
                error("The download prepares its exact accepted recording without another selection")
        }
        private val typedGateway = object : AcquisitionExecutionGateway {
            override suspend fun preparePlayback(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
                requiredRepresentationId: String?, inspection: AcquisitionInspection?): AcquisitionPlaybackSource =
                error("A download never opens a native playback source")
            override suspend fun prepareDownload(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
                requiredRepresentationId: String?, inspection: AcquisitionInspection?): PreparedAcquisitionDownload =
                object : PreparedAcquisitionDownload {
                    override val context = context
                    override val requiredRepresentationId = requiredRepresentationId
                    override val representation = AcquisitionRepresentation("authored-non-audio", "audio/mpeg", "mp3", "mp3")
                }
            override suspend fun download(prepared: PreparedAcquisitionDownload, destinationPath: String,
                onProgress: suspend (AcquisitionTransferProgress) -> Unit): AcquisitionTransferResult {
                val source = object : PlaybackResource {
                    override suspend fun close() { if (point == UnwindPoint.SOURCE_RESOURCE) stopUnwind() }
                }
                try {
                    transfers.incrementAndGet()
                    val bytes = "Veyra authored owner-unwind fixture. NOT AUDIO. ليست مادة صوتية.".encodeToByteArray()
                    File(destinationPath).writeBytes(bytes)
                    onProgress(AcquisitionTransferProgress(bytes.size.toLong(), null))
                    transferStarted.complete(Unit)
                    awaitCancellation()
                } finally { withContext(NonCancellable) { source.close() } }
            }
        }
        private val clock = SystemClock()
        private val acquisition = AcquisitionCoordinator(library.acquisition, selector, AcquisitionCaptureFactory(clock), clock,
            offline = { library.state.value.settings.offlineOnly })
        private val batches = DownloadBatchCoordinator(library.acquisition, acquisition, clock)
        private val evidence = DownloadMaterializationEvidence(library.transactionOwner)
        private val dependencies = DownloadAcquisitionDependencies(library.acquisition, typedGateway,
            LocalMediaValidator { error("Cancelled authored NON-AUDIO fixture must never validate") },
            {}, {}, {}, acquisition, batches, evidence)
        val resolver = AppSourceResolver(library, files, validator, gateway, materializationEvidence = evidence)
        val coordinator: DownloadCoordinator = DownloadCoordinator(
            library, files, resolver, gateway, validator,
            EngineCapabilities(), scope, executionGate, pollingMs = 100, leaseDurationMs = 30_000, heartbeatMs = 1_000,
            acquisitionDependencies = dependencies,
        )

        suspend fun record(id: String): PhysicalDownloadJob =
            requireNotNull(library.acquisition.physicalDownload(id)).job

        suspend fun start(): String {
            check(nativeGate.foregroundStarted(OLD_WORKER))
            coordinator.start(DownloadActivation.DURABLE_WORK)
            val item = MediaItem("authored-owner-unwind", "Complete authored fixture — العنوان الكامل", MediaKind.MUSIC,
                SourceRef(SourceProvider.COMMONS, "authored-owner-unwind"))
            library.upsertCatalog(listOf(item)).getOrThrow()
            val captured = acquisition.capture(MediaCapture(item))
            val accepted = acquisition.resolve(captured.request.id).record
            check(accepted.state == AcquisitionJobState.COMMITTED)
            val binding = (accepted.outcome as AcquisitionOutcome.Single).bindingId
            val id = coordinator.enqueueAccepted(captured.request.id, binding) {
                check(library.acquisitionViews.value.acquisition.requests[captured.request.id]?.state ==
                    AcquisitionJobState.COMMITTED)
            }
            transferStarted.await()
            return id
        }

        private suspend fun stopUnwind() = withContext(NonCancellable) {
            unwindEntered.complete(Unit)
            finishUnwind.await()
        }

        suspend fun close(allowKnownStickyFailure: Boolean = false) = withContext(NonCancellable) {
            finishUnwind.complete(Unit)
            val closed = runCatching { withTimeout(10_000) { coordinator.close() } }
            // Even a test fixture must not destroy files/Room beneath a retained cleanup owner.
            // A failed retention assertion preserves its private directory for diagnosis instead.
            if (!coordinator.dependencyReleaseSafeAfterClose) {
                closed.getOrThrow()
                error("Authored coordinator close returned without dependency release safety")
            }
            try {
                closed.exceptionOrNull()?.let { error ->
                    if (!allowKnownStickyFailure || error !is VeyraException ||
                        error.failure != AppFailure(FailureCode.BACKGROUND_RESTRICTED, RESTART_REQUIRED)) throw error
                }
            } finally {
                try { library.close() }
                finally { scope.cancel(); directory.deleteRecursively() }
            }
        }
    }

    private companion object {
        const val OLD_WORKER = "authored-old-worker"
        const val NEXT_WORKER = "authored-next-worker"
        const val VIDEO = "AAAAAAAAAAA"
        const val RESTART_REQUIRED = "download_owner_drain_failed_restart_required"
    }
}
