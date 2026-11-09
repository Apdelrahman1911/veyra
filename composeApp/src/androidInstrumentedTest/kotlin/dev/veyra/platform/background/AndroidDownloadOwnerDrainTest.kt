package dev.veyra.platform.background

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veyra.data.createAndroidLibraryStore
import dev.veyra.data.getOrThrow
import dev.veyra.downloads.DownloadActivation
import dev.veyra.downloads.DownloadCoordinator
import dev.veyra.downloads.DownloadExecutionGate
import dev.veyra.downloads.DownloadExecutionLease
import dev.veyra.downloads.DownloadFiles
import dev.veyra.downloads.DownloadIdleDecision
import dev.veyra.model.AppFailure
import dev.veyra.model.DownloadStatus
import dev.veyra.model.FailureCode
import dev.veyra.model.FirstResultKind
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.PlaybackBinding
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.model.VeyraException
import dev.veyra.playback.AssetValidator
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.PlaybackResource
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.execution.AppSourceResolver
import dev.veyra.source.execution.KmpFirstResult
import dev.veyra.source.execution.KmpMediaGateway
import dev.veyra.source.youtube.YouTubeTransferProgress
import dev.veyra.source.youtube.YouTubeTransferResult
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
 * WorkManager/FGS ACKs, kmpYT and native resource close are deliberate fakes; bytes are authored
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
            withTimeout(5_000) { fixture.coordinator.pause(id) }
            val failure = AppFailure(FailureCode.BACKGROUND_RESTRICTED, RESTART_REQUIRED)
            assertEquals(0, fixture.nativeGate.leaseCount.value)
            assertEquals(1, fixture.allowanceCloses.get())
            assertTrue("A joined coroutine is not consumption of its failed close owner",
                fixture.coordinator.hasPendingCleanupOwners)
            assertEquals(failure, fixture.coordinator.lastFailure.value)
            assertEquals(failure, fixture.coordinator.schedulingPlan().blockedReason)
            val held = fixture.library.flush().getOrThrow().downloads.getValue(id)
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
            val released = fixture.library.flush().getOrThrow().downloads.getValue(id)
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
                val during = fixture.library.flush().getOrThrow().downloads.getValue(id)
                assertNotNull("Room ownership must remain until actual unwind", during.leaseOwner)
                assertNotNull(during.activeAttemptId)
                val rejected = runCatching { fixture.coordinator.resumePending() }.exceptionOrNull() as VeyraException
                assertEquals(FailureCode.BACKGROUND_RESTRICTED, rejected.failure.code)
                ended.cancel() // Worker cancellation must not abandon an already accepted drain.
                assertNull(withTimeoutOrNull(100) { ended.join(); true })
                fixture.finishUnwind.complete(Unit)
                withTimeout(5_000) { ended.join(); assertTrue(reentry.await()); fixture.drainReturned.await() }

                val after = fixture.library.flush().getOrThrow().downloads.getValue(id)
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
        private val directory = File(context.cacheDir, "download-owner-drain-${UUID.randomUUID()}")
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
            override suspend fun firstResult(completeTitle: String) = KmpFirstResult(
                completeTitle, FirstResultKind.VIDEO, VIDEO, "Authored resolved fixture",
                "https://www.youtube.com/watch?v=$VIDEO",
            )

            override suspend fun resolve(binding: PlaybackBinding, capabilities: EngineCapabilities,
                purpose: ResolvePurpose): ResolvedPlayable {
                check(purpose == ResolvePurpose.DOWNLOAD)
                return ResolvedPlayable(binding.mediaId, "https://fixture.example.test/never-opened", mimeType = "audio/mpeg",
                    codecs = "mp3", representationId = "authored-non-audio", executionBinding = binding,
                    resource = object : PlaybackResource {
                        override suspend fun close() {
                            if (point == UnwindPoint.SOURCE_RESOURCE) stopUnwind()
                        }
                    })
            }

            override suspend fun download(binding: PlaybackBinding, destinationPath: String, capabilities: EngineCapabilities,
                representationId: String?, onProgress: suspend (YouTubeTransferProgress) -> Unit): YouTubeTransferResult {
                transfers.incrementAndGet()
                val bytes = "Veyra authored owner-unwind fixture. NOT AUDIO. ليست مادة صوتية.".encodeToByteArray()
                File(destinationPath).writeBytes(bytes)
                onProgress(YouTubeTransferProgress(bytes.size.toLong(), null))
                transferStarted.complete(Unit)
                awaitCancellation()
            }
        }
        val coordinator: DownloadCoordinator = DownloadCoordinator(
            library, files, AppSourceResolver(library, files, validator, gateway), gateway, validator,
            EngineCapabilities(), scope, executionGate, pollingMs = 100, leaseDurationMs = 30_000, heartbeatMs = 1_000,
        )

        suspend fun start(): String {
            check(nativeGate.foregroundStarted(OLD_WORKER))
            coordinator.start(DownloadActivation.DURABLE_WORK)
            val item = MediaItem("authored-owner-unwind", "Complete authored fixture — العنوان الكامل", MediaKind.MUSIC,
                SourceRef(SourceProvider.COMMONS, "authored-owner-unwind"))
            val id = coordinator.enqueue(item)
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
