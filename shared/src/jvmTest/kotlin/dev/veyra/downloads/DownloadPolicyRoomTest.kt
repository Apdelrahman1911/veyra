package dev.veyra.downloads

import dev.veyra.data.getOrThrow
import dev.veyra.model.*
import dev.veyra.source.execution.AcquisitionTransferProgress
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
    @Test fun rateLimitBackoffIsDurableBoundedAndRetainsOneRecordingReceipt() = executorSchema3Case {
        // EX30: all six outcomes are real typed runtime/canonical attempt finishes.
        val owner = owner()
        owner.gateway.transfer = { call -> call.write(); throw VeyraException(AppFailure(FailureCode.RATE_LIMITED, retryAfterMs = 3_500)) }
        val (id, selected) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        repeat(6) { failedIndex ->
            val status = if (failedIndex < 5) DownloadStatus.WAITING_NETWORK else DownloadStatus.FAILED
            val durable = awaitJob(owner, id) {
                it.job.status == status && it.job.leaseOwner == null && it.job.attemptCount == failedIndex + 1
            }
            assertEquals(FailureCode.RATE_LIMITED, durable.job.failure?.code)
            assertEquals(minOf(failedIndex + 1, 5), durable.job.automaticRetryCount)
            assertEquals(failedIndex + 1, owner.gateway.transfers.size)
            assertEquals(failedIndex + 1, durable.attempts.size)
            assertTrue(durable.attempts.all { it.finishedEpochMs != null })
            assertEquals(selected.context, durable.producerContext)
            if (failedIndex < 5) {
                val delayMs = DownloadCoordinator.retryDelayMs(failedIndex, 3_500)
                assertEquals(clock.epochMs() + delayMs, durable.job.nextRetryEpochMs)
                delay(100)
                assertEquals(failedIndex + 1, owner.gateway.transfers.size, "No retry before its durable deadline")
                advance(delayMs)
            }
        }
        advance(86_400_000)
        delay(160)
        assertEquals(6, owner.gateway.transfers.size)
        assertEquals(1, owner.selector.requests.size)
        assertEquals(1, owner.gateway.transfers.map { it.context }.distinct().size)
        assertEquals(1, owner.gateway.transfers.map { it.path }.distinct().size)
        val before = join(owner, id)
        val stage = owner.gateway.transfers.first().path
        owner.coordinator.cancel(id)
        val cleaned = awaitJob(owner, id) { !it.job.cleanupPending && it.job.stagingPath == null }
        assertFalse(Files.exists(Path.of(stage)))
        assertNull(cleaned.finalization)
        assertNull(cleaned.asset)
        assertEquals(before.job.producer, cleaned.job.producer)
        assertEquals(before.attempts, cleaned.attempts, "Cleanup preserves finished immutable history")
    }

    @Test fun realFileReadEnospcIsStorageFullNotNetworkRetry() =
        executorSchema3StorageFailure("ENOSPC authored no space", FailureCode.STORAGE_FULL)

    @Test fun realFileReadIoErrorIsStorageIoNotNetworkRetry() =
        executorSchema3StorageFailure("authored input/output error", FailureCode.STORAGE_IO)

    @Test fun gatewayProgrammerErrorsAreNotMislabelledAsFilesystemFailures() = executorSchema3Case {
        // EX28: an authored typed-gateway programming error is not a filesystem exception.
        val owner = owner()
        owner.gateway.transfer = { throw IllegalStateException("authored invariant failure") }
        val (id, selected) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val failed = awaitJob(owner, id) { it.job.status == DownloadStatus.FAILED && it.job.leaseOwner == null }
        assertEquals(FailureCode.INVALID_STATE, failed.job.failure?.code)
        assertEquals(0, failed.job.automaticRetryCount)
        assertNull(failed.asset)
        assertNull(failed.finalization)
        assertTrue(owner.validations.isEmpty())
        assertEquals(selected.context, failed.producerContext)
        assertEquals(1, failed.attempts.size)
        assertNotNull(failed.attempts.single().finishedEpochMs)
        assertEquals(1, owner.selector.requests.size)
    }

    @Test fun negativeProgressCannotReachValidationOrReady() = executorSchema3Case {
        // EX29: reject before decoder/publication/use/C0; removal leaves truthful history only.
        val owner = owner()
        owner.gateway.transfer = { call ->
            call.write(); call.progress(AcquisitionTransferProgress(-1, 100)); call.result()
        }
        val (id, selected) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val failed = awaitJob(owner, id) { it.job.status == DownloadStatus.FAILED && it.job.leaseOwner == null }
        assertEquals(FailureCode.CORRUPT_MEDIA, failed.job.failure?.code)
        assertTrue(owner.validations.isEmpty())
        assertNull(failed.finalization)
        assertNull(failed.asset)
        assertTrue(owner.store.acquisitionView().acquisition.assetUses.isEmpty())
        assertNull(owner.materializationEvidence.checks.value.successfulChecks[id])
        owner.coordinator.remove(id)
        val cleaned = awaitJob(owner, id) { !it.job.cleanupPending && it.job.stagingPath == null }
        assertEquals(DownloadStatus.CANCELLED, cleaned.job.status)
        assertEquals(selected.context, cleaned.producerContext)
        assertEquals(failed.attempts, cleaned.attempts)
        assertFalse(Files.exists(Path.of(owner.gateway.transfers.single().path)))
    }

    @Test fun transferRepresentationChangeCannotReachReady() = executorSchema3Case {
        // EX33: result representation must match this exact prepared context/key.
        val owner = owner()
        owner.gateway.transfer = { call ->
            call.write(); call.result(representation = call.prepared.representation.copy(representationId = "unexpected-representation"))
        }
        val (id, selected) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val failed = awaitJob(owner, id) { it.job.status == DownloadStatus.FAILED && it.job.leaseOwner == null }
        assertEquals(FailureCode.CORRUPT_MEDIA, failed.job.failure?.code)
        assertTrue(owner.validations.isEmpty())
        assertNull(failed.finalization)
        assertNull(failed.asset)
        assertTrue(owner.store.acquisitionView().acquisition.assetUses.isEmpty())
        assertNull(owner.materializationEvidence.checks.value.successfulChecks[id])
        assertEquals(selected.context, failed.producerContext)
        assertEquals(authoredRepresentation, failed.job.representationId)
    }

    @Test fun downloadChecksMaterializedFileNotAcquisitionTransport() = executorSchema3Case {
        // EX26: PreparedAcquisitionDownload has NO playback URI/transport/native player to invent.
        val owner = owner() // Default decoder capabilities allow progressive local MP4/AAC, not HLS.
        owner.gateway.prepare = { context, key -> executorPrepared(context, key,
            EXECUTOR_REPRESENTATION.copy(mimeType = "audio/mp4", codecs = "aac", extension = "m4a")) }
        val (id, selected) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val completed = complete(owner, id)
        val asset = assertNotNull(completed.asset)
        assertEquals("audio/mp4", asset.mimeType)
        assertEquals("aac", asset.codecs)
        assertTrue(asset.uri.endsWith(".m4a"))
        assertEquals(selected.context, owner.gateway.transfers.single().context)
        assertEquals(0, owner.gateway.playbackCalls.get())
        assertEquals(1, owner.validations.size)
        owner.legacy.assertUnused()
    }

    @Test fun unsupportedPlanDisposesUnhandedResourceAndNeverDownloads() = executorSchema3Case {
        // EX35: no obsolete PlaybackResource is fabricated. Real execution owners must all drain.
        val owner = owner()
        owner.gateway.prepare = { context, key -> executorPrepared(context, key,
            EXECUTOR_REPRESENTATION.copy(mimeType = "audio/unsupported", codecs = "unsupported")) }
        val (id, selected) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val failed = awaitJob(owner, id) { it.job.status == DownloadStatus.FAILED && it.job.leaseOwner == null }
        assertEquals(FailureCode.UNSUPPORTED_CODEC, failed.job.failure?.code)
        assertEquals(1, owner.gate.closes.get())
        assertEquals(0, owner.gateway.playbackCalls.get())
        assertTrue(owner.gateway.transfers.isEmpty())
        assertTrue(owner.validations.isEmpty())
        assertNull(failed.asset)
        assertNull(failed.finalization)
        assertNull(failed.activeAttempt)
        assertNotNull(failed.attempts.single().finishedEpochMs)
        assertEquals(selected.context, failed.producerContext)
        assertNotNull(files.tryLeasePaths(listOf(assertNotNull(failed.job.stagingPath)))).close()
        assertNotNull(files.tryTransferSlot(1)).close()
        idle(owner)
    }

    @Test fun twoRealRoomSchedulersRespectOneTwoAndFourGlobalSlotsAndLoweredLimit() {
        // EX34 REQUIRED PRODUCTION HOLD: lower the ACTIVE limit while transfers remain held.
        for (limit in listOf(1, 2, 4)) executorSchema3Case {
            val gateway = DownloadExecutorSchema3Gateway(events)
            val first = owner(gateway = gateway)
            val second = owner(gateway = gateway)
            parallelism(first, limit)
            val release = barrier()
            val inFlight = AtomicInteger()
            val maximum = AtomicInteger()
            val lowered = AtomicBoolean()
            val maximumNewAfterLowering = AtomicInteger()
            gateway.transfer = { call ->
                val count = inFlight.incrementAndGet()
                maximum.updateAndGet { maxOf(it, count) }
                if (lowered.get()) maximumNewAfterLowering.updateAndGet { maxOf(it, count) }
                try { release.stop(); call.complete() } finally { inFlight.decrementAndGet() }
            }
            // Independent canonical subjects AND C: not unsupported cross-subject file sharing.
            val ids = (1..7).map { index -> enqueue(first, item("parallel-$index"), "PARALLEL00$index").first }
            coroutineScope { awaitAll(async { first.coordinator.start(DownloadActivation.DURABLE_WORK) },
                async { second.coordinator.start(DownloadActivation.DURABLE_WORK) }) }
            awaitCondition { inFlight.get() == limit }
            assertEquals(limit, maximum.get())
            assertEquals(gateway.transfers.size, gateway.transfers.map { it.context.subject.id }.distinct().size)
            parallelism(first, 1)
            lowered.set(true)
            awaitCondition { inFlight.get() == 1 }
            delay(160)
            assertEquals(1, inFlight.get(), "Surplus active owners must unwind before releasing the shared test barrier")
            assertFalse(release.released.isCompleted)
            release.release()
            ids.forEach { id -> complete(first, id, evidenceOwners = listOf(first, second)) }
            assertTrue(maximumNewAfterLowering.get() <= 1)
            assertTrue(maximum.get() <= limit)
            assertEquals(7, first.store.acquisitionView().acquisition.physicalDownloads.size)
            assertEquals(7, first.selector.requests.size)
            assertTrue(second.selector.requests.isEmpty())
        }
    }

    @Test fun frozenClockAndRecreatedRecordCannotReuseAnOldFinalFilename() = executorSchema3Case {
        // EX27: schema3 keeps the same job tombstone; a NEW attempt, not wall time, owns its path.
        val owner = owner()
        val (id, selected) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val original = complete(owner, id)
        idle(owner)
        val old = assertNotNull(original.asset)
        val epoch = clock.epochMs()
        owner.coordinator.remove(id)
        val tombstone = awaitJob(owner, id) { !it.job.cleanupPending && it.asset == null && it.job.stagingPath == null }
        assertEquals(original.attempts, tombstone.attempts)
        assertEquals(original.job.producer, tombstone.job.producer)
        assertEquals(id, enqueue(owner, selected))
        assertEquals(DownloadBatchIntent.CANCELLED, join(owner, id).claims.single().intent)
        owner.coordinator.resume(id) // Re-enqueue never silently changes the preserved claim intent.
        val fresh = assertNotNull(complete(owner, id).asset)
        assertEquals(epoch, clock.epochMs())
        assertEquals(old.validatorGeneration, fresh.validatorGeneration)
        assertNotEquals(old.uri, fresh.uri)
        assertNotEquals(old.materializerAttemptId, fresh.materializerAttemptId)
        assertFalse(Files.exists(Path.of(old.uri)))
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(fresh.uri)))
        assertEquals(1, owner.selector.requests.size)
        assertTrue(join(owner, id).attempts.containsAll(original.attempts))
    }

}
