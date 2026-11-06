package dev.veyra.downloads

import dev.veyra.data.getOrThrow
import dev.veyra.model.*
import dev.veyra.playback.PlaybackResource
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.source.youtube.YouTubeTransferProgress
import kotlinx.coroutines.*
import okio.FileSystem
import okio.ForwardingFileSystem
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** D12/D14 plus materialized-output contract; fake kmpYT plans never establish real transport/media support. */
class DownloadPolicyRoomTest {
    @Test fun rateLimitBackoffIsDurableBoundedAndRetainsOneRecordingReceipt() = downloadCase {
        val first = store()
        val gateway = DownloadTestGateway().apply {
            transfer = { call -> call.write(); throw VeyraException(AppFailure(FailureCode.RATE_LIMITED, retryAfterMs = 3_500)) }
        }
        val a = coordinator(first, gateway)
        val id = a.enqueue(item()); a.start()
        repeat(6) { failedIndex ->
            val expected = if (failedIndex < 5) DownloadStatus.WAITING_NETWORK else DownloadStatus.FAILED
            val durable = eventually(first) { it.downloads[id]?.let { r ->
                r.status == expected && r.leaseOwner == null && r.attemptCount == failedIndex + 1
            } == true }
            val record = durable.downloads.getValue(id)
            assertEquals(FailureCode.RATE_LIMITED, record.failure?.code)
            assertEquals(minOf(failedIndex + 1, 5), record.automaticRetryCount)
            assertEquals(failedIndex + 1, gateway.transfers.size)
            if (failedIndex < 5) {
                val delayMs = DownloadCoordinator.retryDelayMs(failedIndex, 3_500)
                assertEquals(clock.epochMs() + delayMs, record.nextRetryEpochMs)
                delay(100)
                assertEquals(failedIndex + 1, gateway.transfers.size, "No retry before its durable deadline")
                clock.advance(delayMs)
            }
        }
        clock.advance(86_400_000)
        delay(160)
        assertEquals(6, gateway.transfers.size)
        assertEquals(1, gateway.queries.size)
        assertEquals(1, gateway.transfers.map { it.binding.id }.distinct().size)
        assertEquals(1, gateway.transfers.map { it.path }.distinct().size)
        val stage = gateway.transfers.first().path
        a.cancel(id)
        eventually(first) { it.downloads[id]?.cleanupPending == false }
        assertFalse(Files.exists(Path.of(stage)))
        assertTrue(first.state.value.downloadFinalizations.isEmpty())
    }

    private fun storageFailure(message: String, expected: FailureCode) = downloadCase(object : ForwardingFileSystem(FileSystem.SYSTEM) {
        override fun onPathParameter(path: okio.Path, functionName: String, parameterName: String): okio.Path {
            if (functionName == "source" && path.name == "transfer.part") throw IOException(message)
            return path
        }
    }) {
        val first = store()
        val gateway = DownloadTestGateway()
        val a = coordinator(first, gateway)
        val id = a.enqueue(item()); a.start()
        val failed = eventually(first) { it.downloads[id]?.let { r -> r.status == DownloadStatus.FAILED && r.leaseOwner == null } == true }
        assertEquals(expected, failed.downloads[id]?.failure?.code)
        assertEquals(0, failed.downloads[id]?.automaticRetryCount)
        assertNull(failed.downloads[id]?.asset)
        assertTrue(failed.downloadFinalizations.isEmpty())
        assertEquals(0, validationCount.get())
        a.cancel(id)
        eventually(first) { it.downloads[id]?.cleanupPending == false }
        assertFalse(Files.exists(Path.of(gateway.transfers.single().path)))
    }

    @Test fun realFileReadEnospcIsStorageFullNotNetworkRetry() = storageFailure("ENOSPC authored no space", FailureCode.STORAGE_FULL)
    @Test fun realFileReadIoErrorIsStorageIoNotNetworkRetry() = storageFailure("authored input/output error", FailureCode.STORAGE_IO)

    @Test fun gatewayProgrammerErrorsAreNotMislabelledAsFilesystemFailures() = downloadCase {
        val first = store()
        val gateway = DownloadTestGateway().apply { transfer = { throw IllegalStateException("authored invariant failure") } }
        val a = coordinator(first, gateway)
        val id = a.enqueue(item()); a.start()
        val failed = eventually(first) { it.downloads[id]?.let { r -> r.status == DownloadStatus.FAILED && r.leaseOwner == null } == true }
        assertEquals(FailureCode.INVALID_STATE, failed.downloads[id]?.failure?.code)
        assertEquals(0, failed.downloads[id]?.automaticRetryCount)
        assertNull(failed.downloads[id]?.asset)
    }

    @Test fun negativeProgressCannotReachValidationOrReady() = downloadCase {
        val first = store()
        val gateway = DownloadTestGateway().apply {
            transfer = { call -> call.write(); call.onProgress(YouTubeTransferProgress(-1, 100)); call.result() }
        }
        val a = coordinator(first, gateway)
        val id = a.enqueue(item()); a.start()
        val failed = eventually(first) { it.downloads[id]?.let { r -> r.status == DownloadStatus.FAILED && r.leaseOwner == null } == true }
        assertEquals(FailureCode.CORRUPT_MEDIA, failed.downloads[id]?.failure?.code)
        assertEquals(0, validationCount.get())
        assertTrue(failed.downloadFinalizations.isEmpty())
        a.remove(id)
        eventually(first) { id !in it.downloads }
        assertFalse(Files.exists(Path.of(gateway.transfers.single().path)))
    }

    @Test fun transferRepresentationChangeCannotReachReady() = downloadCase {
        val first = store()
        val gateway = DownloadTestGateway().apply {
            transfer = { call -> call.write(); call.result(representationId = "unexpected-representation") }
        }
        val a = coordinator(first, gateway)
        val id = a.enqueue(item()); a.start()
        val failed = eventually(first) { it.downloads[id]?.let { r -> r.status == DownloadStatus.FAILED && r.leaseOwner == null } == true }
        assertEquals(FailureCode.CORRUPT_MEDIA, failed.downloads[id]?.failure?.code)
        assertEquals(0, validationCount.get())
        assertTrue(failed.downloadFinalizations.isEmpty())
        assertNull(failed.downloads[id]?.asset)
    }

    @Test fun downloadChecksMaterializedFileNotAcquisitionTransport() = downloadCase {
        val first = store()
        val gateway = DownloadTestGateway().apply {
            plan = { binding -> ResolvedPlayable(binding.mediaId, "https://fixture.example.test/not-a-real-manifest",
                transport = StreamTransport.HLS, mimeType = "audio/mp4", codecs = "aac",
                representationId = authoredRepresentation, executionBinding = binding) }
            transfer = { call -> call.write(); call.result().copy(mimeType = "audio/mp4", codecs = "aac", extension = "m4a") }
        }
        val a = coordinator(first, gateway) // default native capabilities contain PROGRESSIVE, not HLS
        val id = a.enqueue(item()); a.start()
        val complete = eventually(first) { it.downloads[id]?.status == DownloadStatus.COMPLETE }
        val asset = complete.downloads.getValue(id).asset!!
        assertEquals("audio/mp4", asset.mimeType)
        assertEquals("aac", asset.codecs)
        assertTrue(asset.uri.endsWith(".m4a"))
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(asset.uri)))
    }

    @Test fun unsupportedPlanDisposesUnhandedResourceAndNeverDownloads() = downloadCase {
        val first = store()
        val released = AtomicInteger()
        val gateway = DownloadTestGateway().apply {
            plan = { binding -> ResolvedPlayable(binding.mediaId, "https://fixture.example.test/not-audio",
                mimeType = "audio/unsupported", representationId = authoredRepresentation, executionBinding = binding,
                resource = object : PlaybackResource { override suspend fun close() { released.incrementAndGet() } }) }
        }
        val a = coordinator(first, gateway)
        val id = a.enqueue(item()); a.start()
        val failed = eventually(first) { it.downloads[id]?.let { r -> r.status == DownloadStatus.FAILED && r.leaseOwner == null } == true }
        assertEquals(FailureCode.UNSUPPORTED_CODEC, failed.downloads[id]?.failure?.code)
        assertEquals(1, released.get())
        assertTrue(gateway.transfers.isEmpty())
    }

    @Test fun twoRealRoomSchedulersRespectOneTwoAndFourGlobalSlotsAndLoweredLimit() {
        for (limit in listOf(1, 2, 4)) downloadCase {
            val first = store(); val second = store()
            first.update { it.copy(settings = it.settings.copy(downloadParallelism = limit)) }.getOrThrow()
            val release = barrier()
            val inFlight = AtomicInteger()
            val maximum = AtomicInteger()
            val lowered = AtomicBoolean()
            val maximumNewAfterLowering = AtomicInteger()
            val gateway = DownloadTestGateway().apply {
                transfer = { call ->
                    val count = inFlight.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, count) }
                    if (lowered.get()) maximumNewAfterLowering.updateAndGet { maxOf(it, count) }
                    try { release.stop(); call.complete() } finally { inFlight.decrementAndGet() }
                }
            }
            val a = coordinator(first, gateway); val b = coordinator(second, gateway)
            val ids = (1..7).map { a.enqueue(item("parallel-$it")) }
            coroutineScope { awaitAll(async { a.start() }, async { b.start() }) }
            eventually(first) { inFlight.get() == limit }
            assertEquals(limit, maximum.get())
            assertEquals(gateway.transfers.size, gateway.transfers.map { it.binding.mediaId }.distinct().size)
            first.update { it.copy(settings = it.settings.copy(downloadParallelism = 1)) }.getOrThrow()
            lowered.set(true)
            eventually(second) { inFlight.get() == 1 }
            delay(160)
            assertEquals(1, inFlight.get())
            release.release()
            eventually(first) { ids.all { id -> it.downloads[id]?.status == DownloadStatus.COMPLETE } }
            assertTrue(maximumNewAfterLowering.get() <= 1)
            assertTrue(maximum.get() <= limit)
            assertEquals(7, first.state.value.downloads.size)
        }
    }

    @Test fun frozenClockAndRecreatedRecordCannotReuseAnOldFinalFilename() = downloadCase {
        val first = store()
        val gateway = DownloadTestGateway()
        val a = coordinator(first, gateway)
        val item = item()
        val id = a.enqueue(item); a.start()
        val old = eventually(first) { it.downloads[id]?.status == DownloadStatus.COMPLETE }.downloads.getValue(id).asset!!
        a.remove(id)
        eventually(first) { id !in it.downloads }
        a.enqueue(item)
        val fresh = eventually(first) { it.downloads[id]?.status == DownloadStatus.COMPLETE }.downloads.getValue(id).asset!!
        assertEquals(old.validatorGeneration, fresh.validatorGeneration, "The authored epoch stayed frozen")
        assertNotEquals(old.uri, fresh.uri, "Random attempt generation, not wall time, owns the final filename")
        assertFalse(Files.exists(Path.of(old.uri)))
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(fresh.uri)))
    }
}
