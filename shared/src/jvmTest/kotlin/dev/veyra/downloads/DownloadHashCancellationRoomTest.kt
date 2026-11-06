package dev.veyra.downloads

import dev.veyra.data.getOrThrow
import dev.veyra.model.DownloadStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSource
import okio.Source
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * D13: block actual Source.read/close after reading authored NON-AUDIO bytes from disk. Cancellation
 * is cooperative after the operation returns, NOT a claim that an OS disk read is forcibly interrupted.
 */
class DownloadHashCancellationRoomTest {
    @Test fun pauseDuringARealHashReadKeepsItsLeaseUntilSourceCloseAndResumesTheSameReceiptAndPath() {
        val blocking = BlockingHashReadFileSystem()
        downloadCase(blocking) {
            releaseOnClose += { blocking.releaseBoundary() }
            val first = store()
            val observer = store()
            val gateway = DownloadTestGateway().apply {
                transfer = { call -> call.complete().also { blocking.target = call.path } }
            }
            val gate = HashReadCancellationGate()
            val a = coordinator(first, gateway, gate = gate)
            val id = a.enqueue(item())
            a.start(); blocking.boundaryEntered.await()
            val part = gateway.transfers.single().path
            val bound = observer.flush().getOrThrow().downloads.getValue(id)
            assertEquals(DownloadStatus.VERIFYING, bound.status)
            assertNotNull(bound.executionBindingId)
            assertEquals(authoredRepresentation, bound.representationId)
            coroutineScope {
                val pausing = async(start = CoroutineStart.UNDISPATCHED) { a.pause(id) }
                val paused = eventually(observer) { it.downloads[id]?.status == DownloadStatus.PAUSED }
                gate.awaitCancellationRequested()
                assertEquals(bound.executionBindingId, paused.downloads[id]?.executionBindingId)
                assertEquals(bound.representationId, paused.downloads[id]?.representationId)
                assertEquals(part, paused.downloads[id]?.stagingPath)
                assertFalse(pausing.isCompleted, "Pause must await the blocked Source.read and its actual close")
                assertFalse(blocking.sourceClosed.isCompleted)
                assertEquals(0, validationCount.get(), "Cancellation during hash cannot reach the decoder")
                assertNull(files.tryLeasePaths(listOf(part)), "The cancelled but unreturned source still owns this generation")
                assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(part)))
                blocking.releaseBoundary()
                withTimeout(3_000) { pausing.await() }
            }
            assertTrue(blocking.sourceClosed.isCompleted)
            assertEquals(1, blocking.blockedSourceCloses.get())
            val released = observer.flush().getOrThrow().downloads.getValue(id)
            assertEquals(DownloadStatus.PAUSED, released.status)
            assertNull(released.leaseOwner)
            assertNull(released.activeAttemptId)
            assertEquals(bound.executionBindingId, released.executionBindingId)
            val lease = assertNotNull(files.tryLeasePaths(listOf(part))); lease.close()
            a.resume(id)
            eventually(first) { it.downloads[id]?.status == DownloadStatus.COMPLETE }
            assertEquals(listOf(part, part), gateway.transfers.map { it.path })
            assertEquals(1, gateway.queries.size)
            assertEquals(1, gateway.transfers.map { it.binding }.distinct().size)
            assertEquals(1, validationCount.get())
        }
    }

    @Test fun closeDuringARealCompletedHashReadWaitsForSourceUnwindAndReopenCanCheckWithoutAcquisition() =
        completedHashCloseWaitsFor(HashBoundary.DATA_READ)

    @Test fun cancelledEofReadCannotReachNativeValidationBeforeSourceUnwindAndOfflineReopen() =
        completedHashCloseWaitsFor(HashBoundary.EOF_READ)

    @Test fun cancelledSourceCloseCannotReachNativeValidationBeforeSourceUnwindAndOfflineReopen() =
        completedHashCloseWaitsFor(HashBoundary.SOURCE_CLOSE)

    private fun completedHashCloseWaitsFor(boundary: HashBoundary) {
        val blocking = BlockingHashReadFileSystem(boundary)
        downloadCase(blocking) {
            releaseOnClose += { blocking.releaseBoundary() }
            val first = store()
            val seeded = seedComplete(first)
            first.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow()
            val asset = seeded.record.asset!!
            blocking.target = asset.uri // Seed hashing has finished; block only the new session check.
            val gateway = DownloadTestGateway().apply { denyAcquisition() }
            val gate = HashReadCancellationGate()
            val a = coordinator(first, gateway, gate = gate)
            a.start(); blocking.boundaryEntered.await()
            coroutineScope {
                val closing = async(start = CoroutineStart.UNDISPATCHED) { a.close() }
                gate.awaitCancellationRequested()
                val retained = first.flush().getOrThrow().downloads.getValue(seeded.record.id)
                assertEquals(DownloadStatus.COMPLETE, retained.status)
                assertEquals(asset, retained.asset)
                assertFalse(closing.isCompleted, "Closing the host must await the physical hash Source.close")
                assertFalse(blocking.sourceClosed.isCompleted)
                assertEquals(0, validationCount.get())
                assertNull(files.tryLeasePaths(listOf(asset.uri)))
                assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(asset.uri)))
                blocking.releaseBoundary()
                withTimeout(3_000) { closing.await() }
            }
            assertTrue(blocking.sourceClosed.isCompleted)
            assertEquals(1, blocking.blockedSourceCloses.get())
            assertEquals(0, validationCount.get(), "Cancellation at $boundary must be observed before the native validator")
            val retained = first.flush().getOrThrow().downloads.getValue(seeded.record.id)
            assertEquals(DownloadStatus.COMPLETE, retained.status)
            assertEquals(asset, retained.asset)
            assertNull(retained.leaseOwner)
            first.close()
            val reopened = store()
            val b = coordinator(reopened, gateway)
            b.start()
            withTimeout(3_000) { b.presentation.first { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE } }
            assertEquals(1, validationCount.get())
            assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(asset.uri)))
            gateway.assertUnused()
        }
    }
}

/** Observe the actual coordinator operation Job; do not manufacture its cancellation in the test. */
private class HashReadCancellationGate : DownloadExecutionGate {
    private val firstOperation = CompletableDeferred<Job>()
    override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
        firstOperation.complete(currentCoroutineContext().job)
        return DownloadExecutionLease {}
    }
    suspend fun awaitCancellationRequested() = withTimeout(3_000) {
        val operation = firstOperation.await()
        while (!operation.isCancelled) delay(10)
        assertFalse(operation.isCompleted, "The cancelled job must remain alive while its disk source is blocked")
    }
}

private enum class HashBoundary { DATA_READ, EOF_READ, SOURCE_CLOSE }

private class BlockingHashReadFileSystem(private val boundary: HashBoundary = HashBoundary.DATA_READ) : ForwardingFileSystem(FileSystem.SYSTEM) {
    @Volatile var target: String? = null
    val boundaryEntered = CompletableDeferred<Unit>()
    val sourceClosed = CompletableDeferred<Unit>()
    val blockedSourceCloses = AtomicInteger()
    private val once = AtomicBoolean(true)
    private val release = CountDownLatch(1)
    fun releaseBoundary() { release.countDown() }

    override fun source(file: okio.Path): Source {
        val source = super.source(file)
        if (file.toString() != target) return source
        return object : ForwardingSource(source) {
            private var blocked = false
            override fun read(sink: Buffer, byteCount: Long): Long {
                val count = super.read(sink, byteCount)
                if (boundary == HashBoundary.DATA_READ && count > 0 || boundary == HashBoundary.EOF_READ && count == -1L) blockOnce()
                return count
            }
            override fun close() {
                try { if (boundary == HashBoundary.SOURCE_CLOSE) blockOnce() }
                finally {
                    try { super.close() }
                    finally {
                        if (blocked) {
                            blockedSourceCloses.incrementAndGet()
                            sourceClosed.complete(Unit)
                        }
                    }
                }
            }
            private fun blockOnce() {
                if (once.compareAndSet(true, false)) {
                    blocked = true
                    boundaryEntered.complete(Unit)
                    if (!release.await(20, TimeUnit.SECONDS)) throw IOException("Authored blocking hash $boundary timed out")
                }
            }
        }
    }
}
