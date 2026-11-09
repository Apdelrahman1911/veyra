package dev.veyra.downloads

import dev.veyra.model.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * Deterministic integration discriminator for the settled-observation defect: the cancellation
 * of the first cleanup owner must not let the active removal caller fail while the controlled
 * foreground retry is still pending. Real Room/files/OS locks; authored NON-AUDIO bytes; the
 * native delete callback is a test barrier, not native/device/audio proof.
 */
class DownloadRemovalInterruptedRetryRoomTest {
    @Test fun interruptedRemovalStaysPendingThroughTheSecondRetryOwnerAndThenSucceeds() = executorSchema3Case {
        val setup = owner()
        val (id, _) = enqueue(setup)
        setup.coordinator.start(DownloadActivation.DURABLE_WORK)
        val completed = complete(setup, id)
        val asset = assertNotNull(completed.asset)
        setup.coordinator.close()
        assertTrue(setup.coordinator.dependencyReleaseSafeAfterClose)

        val entered = CompletableDeferred<Unit>()
        val unwinding = barrier()
        val retry = barrier()
        val releases = AtomicInteger()
        val a = owner(setup.store)
        a.beforeDelete = {
            when (releases.incrementAndGet()) {
                1 -> {
                    entered.complete(Unit)
                    try { awaitCancellation() }
                    finally { withContext(NonCancellable) { unwinding.stop() } }
                }
                2 -> retry.stop()
            }
        }
        a.coordinator.start(DownloadActivation.DURABLE_WORK)
        val removal = parent.async(start = CoroutineStart.UNDISPATCHED) { runCatching { a.coordinator.remove(id) } }
        entered.await()
        val expire: suspend () -> Unit = a.gate.expirations[0]
        withTimeout(3_000) { expire() }
        unwinding.entered.await()
        val blocked = join(a, id)
        assertTrue(blocked.job.cleanupPending && blocked.job.removeWhenClean)
        assertEquals(asset, blocked.asset)
        assertNotNull(blocked.job.leaseOwner, "The maintenance lease covers the noncancellable native unwind")
        assertEquals(0, a.gate.closes.get(), "The OS lease must cover actual noncancellable release unwind")
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(asset.uri)))

        // A queued OLD foreground epoch must not suspend the resumed host or close its allowance.
        a.coordinator.resumePending()
        withTimeout(3_000) { expire() }
        assertEquals(0, a.gate.closes.get())
        unwinding.release()

        val held = awaitJob(a, id) { it.job.status == DownloadStatus.NEEDS_FOREGROUND && it.job.leaseOwner == null }
        assertEquals(asset, held.asset)
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, held.job.failure?.code)
        assertFalse(removal.isCompleted,
            "The cancelled first owner was legitimately consumed; the active removal must keep observing the retry: " +
                if (removal.isCompleted) removal.await().exceptionOrNull() else "pending")

        advance(5_000)
        withTimeout(10_000) { retry.entered.await() }
        assertFalse(removal.isCompleted, "The actual second cleanup owner is held; intent alone is not success")
        assertTrue(join(a, id).job.cleanupPending)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(asset.uri)))
        assertNull(files.tryLeasePaths(listOf(asset.uri)), "The retry owner holds the exact native/path generation")
        retry.release()

        assertNull(removal.await().exceptionOrNull(), "Normal removal succeeds only after the actual retry deletion")
        val removed = awaitJob(a, id) { !it.job.cleanupPending && it.asset == null }
        assertEquals(DownloadStatus.CANCELLED, removed.job.status)
        assertEquals(2, releases.get())
        assertEquals(2, a.gate.closes.get())
        assertFalse(Files.exists(Path.of(asset.uri)))
        a.legacy.assertUnused()
        assertTrue(a.gateway.transfers.isEmpty())
        assertTrue(a.selector.requests.isEmpty())
        a.coordinator.close()
        assertTrue(a.coordinator.dependencyReleaseSafeAfterClose)
    }
}
