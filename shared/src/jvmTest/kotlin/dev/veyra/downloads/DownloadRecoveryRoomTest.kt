package dev.veyra.downloads

import dev.veyra.data.getOrThrow
import dev.veyra.model.*
import dev.veyra.playback.AcquisitionPlaybackResolver
import dev.veyra.data.PhysicalDownloadCommit
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.AssetValidator
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.execution.AppSourceResolver
import kotlinx.coroutines.*
import okio.FileSystem
import okio.ForwardingFileSystem
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** D03/D06/D09/D13/D15, using actual SQLite rollback and real files; NOT AUDIO or power-loss proof. */
class DownloadRecoveryRoomTest {
    @Test fun sqliteRollbackAfterAtomicRenameIsRecoveredFromDurableIntentOnReopenOffline() = executorSchema3Case {
        // Real SQLite rollback AFTER rename, followed by a distinct Room owner/process-proof registry.
        val a = owner()
        val attemptedCommits = AtomicInteger()
        faults(a.store).beforeCommit = { before, next ->
            if (next.state().physicalDownloads.values.any {
                it.status == DownloadStatus.COMPLETE && before.state().physicalFinalizations[it.id] != null
            }) {
                attemptedCommits.incrementAndGet()
                throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_rollback_after_rename"))
            }
        }
        val (id, selected) = enqueue(a)
        a.coordinator.start(DownloadActivation.DURABLE_WORK)
        val interrupted = awaitJob(a, id) {
            it.job.status == DownloadStatus.FAILED && it.job.leaseOwner == null && it.finalization != null
        }
        assertTrue(attemptedCommits.get() > 0)
        val intent = assertNotNull(interrupted.finalization)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(intent.finalPath)))
        assertFalse(Files.exists(Path.of(intent.stagingPath)))
        assertNull(interrupted.asset)
        assertTrue(a.store.acquisitionView().acquisition.assetUses.isEmpty())
        val materializerAttempt = interrupted.attempts.single { it.id == intent.attemptId }
        assertEquals(DownloadAttemptStatus.FAILED, materializerAttempt.status)
        a.coordinator.close()
        offline(a)
        closeStore(a.store)
        advance(5_100)
        val b = owner(store())
        b.coordinator.initializeScheduling()
        assertTrue(b.validations.isEmpty(), "Cold initialize is Room-only")
        assertTrue(b.materializationEvidence.checks.value.successfulChecks.isEmpty())
        b.coordinator.start(DownloadActivation.DURABLE_WORK)
        val recovered = complete(b, id)
        val asset = assertNotNull(recovered.asset)
        assertEquals(intent.finalPath, asset.uri)
        assertEquals(intent.assetId, asset.id)
        assertEquals(intent.producer, asset.producer)
        assertEquals(intent.attemptId, asset.materializerAttemptId)
        assertEquals(intent.ownershipGeneration, asset.ownershipGeneration)
        assertNull(recovered.finalization)
        assertEquals(materializerAttempt, recovered.attempts.single { it.id == intent.attemptId },
            "Recovery cannot relabel the retired FAILED materializer as the current successful validator")
        idle(b)
        val resolver = AcquisitionPlaybackResolver(b.store, b.gateway, files, b.localValidator, b.materializationEvidence)
        try {
            val local = resolver.resolve(selected.context, EngineCapabilities(), offlineOnly = true)
            resolver.requireAdmissible(local)
            assertTrue(local.source.isLocal)
            assertEquals(asset.uri, local.source.uri)
            assertEquals(selected.context, local.source.context)
            assertEquals(asset, local.validatedMaterialization)
            assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(local.source.uri)))
        } finally { resolver.close() }
        assertEquals(1, a.gateway.transfers.size)
        assertTrue(b.gateway.prepareCalls.isEmpty())
        assertTrue(b.gateway.transfers.isEmpty())
        assertEquals(0, b.gateway.playbackCalls.get())
        assertTrue(b.selector.requests.isEmpty())
        b.legacy.assertUnused()
    }

    @Test fun failedReturnAfterDurableIntentInsertionCanBeCancelledWithoutLostFileReferences() = executorSchema3Case {
        // Returned PREPARE fails only after its real canonical COMMIT; the actual writer stays held.
        val a = owner()
        val b = owner(store())
        val once = AtomicBoolean(true)
        a.repository.afterPrepare = { _, actual ->
            if (actual is PhysicalDownloadCommit.Committed && once.compareAndSet(true, false))
                throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_failure_after_intent_commit"))
        }
        val closing = barrier()
        a.gate.beforeClose = { withContext(NonCancellable) { closing.stop() } }
        val (id, selected) = enqueue(a)
        a.coordinator.start(DownloadActivation.DURABLE_WORK)
        closing.entered.await()
        b.coordinator.start(DownloadActivation.DURABLE_WORK)
        val interrupted = join(b, id)
        val intent = assertNotNull(interrupted.finalization)
        assertFalse(once.get())
        assertTrue(Files.exists(Path.of(intent.stagingPath)))
        assertFalse(Files.exists(Path.of(intent.finalPath)))
        assertEquals(selected.context, interrupted.producerContext)
        assertEquals(AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_unconfirmed"),
            assertFailsWith<VeyraException> { b.coordinator.cancel(id) }.failure)
        assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> { b.coordinator.resume(id) }.failure.code)
        assertTrue(join(b, id).job.cleanupPending)
        assertTrue(Files.exists(Path.of(intent.stagingPath)))
        assertNull(probeFiles.tryLeasePaths(listOf(intent.stagingPath, intent.finalPath)))
        closing.release()
        a.coordinator.close()
        assertTrue(a.coordinator.dependencyReleaseSafeAfterClose)
        advance(5_100)
        val cancelled = awaitJob(b, id) { it.job.status == DownloadStatus.CANCELLED && !it.job.cleanupPending }
        assertNull(cancelled.asset)
        assertNull(cancelled.finalization)
        assertNull(cancelled.job.leaseOwner)
        assertFalse(Files.exists(Path.of(intent.stagingPath)))
        assertFalse(Files.exists(Path.of(intent.finalPath)))
        assertEquals(intent.attemptId, cancelled.attempts.single().id)
        assertNotNull(cancelled.attempts.single().finishedEpochMs)
        assertEquals(1, a.gateway.transfers.size)
        assertTrue(b.gateway.transfers.isEmpty())
        assertTrue(b.selector.requests.isEmpty())
    }

    @Test fun removeAfterReadyCommitWaitsForThePublishingWriterToReleaseItsGeneration() = executorSchema3Case {
        val a = owner()
        val b = owner(store())
        val closing = barrier()
        a.gate.beforeClose = { withContext(NonCancellable) { closing.stop() } }
        val (id, _) = enqueue(a)
        a.coordinator.start(DownloadActivation.DURABLE_WORK)
        closing.entered.await()
        val ready = complete(a, id)
        val asset = assertNotNull(ready.asset)
        assertEquals(DownloadStatus.COMPLETE, ready.job.status)
        assertFalse(a.coordinator.dependencyReleaseSafeAfterClose)
        b.coordinator.start(DownloadActivation.DURABLE_WORK)
        // B has no durable foreign lease after publication. Its return accepts cleanup INTENT,
        // NOT A's physical/native release, global quiescence or a completed deletion receipt.
        b.coordinator.remove(id)
        assertTrue(join(b, id).job.cleanupPending)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(asset.uri)))
        assertNull(probeFiles.tryLeasePaths(listOf(asset.uri)))
        assertNull(probeFiles.tryTransferSlot(1))
        assertEquals(clock.epochMs() + 5_000, b.coordinator.schedulingPlan().nextLocalEpochMs,
            "Actual publication-lock contention has a bounded local recheck")
        closing.release()
        a.coordinator.close()
        assertTrue(a.coordinator.dependencyReleaseSafeAfterClose)
        advance(5_000)
        val removed = awaitJob(b, id) { !it.job.cleanupPending && it.asset == null && it.job.status == DownloadStatus.CANCELLED }
        assertNull(removed.finalization)
        assertFalse(Files.exists(Path.of(asset.uri)))
        assertEquals(ready.attempts, removed.attempts, "Removal preserves already committed materializer history")
        val state = b.store.acquisitionView().acquisition
        assertFalse(asset.id in state.materializations)
        assertEquals(asset.receipt(), state.materializationReceipts[asset.id],
            "Removal deletes live bytes/materialization but retains the immutable historical receipt")
        assertTrue(state.assetUses.any {
            it.materializationAssetId == asset.id && it.requestedBindingId == asset.producer.bindingId
        }, "Immutable historical receipt/use survives live-byte removal, but no materialization is Ready")
        assertEquals(1, a.gateway.transfers.size)
        assertTrue(b.gateway.transfers.isEmpty())
    }

    @Test fun partialDeletionKeepsJournalForPriorAssetAndIntentDestinationUntilReopenCompletesAll() {
        val blocked = AtomicBoolean(true)
        var target: String? = null
        val faultingFiles = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun onPathParameter(path: okio.Path, functionName: String, parameterName: String): okio.Path {
                if (functionName == "delete" && path.toString() == target && blocked.get()) throw IOException("authored sharing violation")
                return path
            }
        }
        downloadCase(faultingFiles) {
            val first = store()
            val seeded = seedFinalization(first, published = true)
            val oldPath = files.finalPath(seeded.item.id, 0, "audio/mpeg", "prior-failed-generation")
            Files.write(Path.of(oldPath.toString()), authoredBytes)
            val old = OfflineAsset("prior-failed-asset", seeded.item.id, oldPath.toString(), authoredBytes.size.toLong(),
                mimeType = "audio/mpeg", codecs = "mp3", sha256 = files.sha256(oldPath), executionBindingId = seeded.binding.id)
            first.update { snapshot -> snapshot.copy(downloads = snapshot.downloads + (seeded.record.id to seeded.record.copy(
                status = DownloadStatus.CANCELLED, userPaused = true, cleanupPending = true, removeWhenClean = true, asset = old))) }.getOrThrow()
            target = seeded.intent!!.finalPath
            val gateway = DownloadTestGateway().apply { denyAcquisition() }
            val a = coordinator(first, gateway)
            a.start()
            val failed = eventually(first) { it.downloads[seeded.record.id]?.failure?.code == FailureCode.STORAGE_IO }
            assertTrue(failed.downloads.getValue(seeded.record.id).cleanupPending)
            assertEquals(old, failed.downloads[seeded.record.id]?.asset, "Do not lose the prior failed generation reference")
            assertEquals(seeded.intent, failed.downloadFinalizations[seeded.record.id])
            assertTrue(Files.exists(Path.of(seeded.intent.finalPath)))
            val retryAt = clock.epochMs() + 5_000
            assertEquals(retryAt, failed.downloads[seeded.record.id]?.nextRetryEpochMs)
            a.resume(seeded.record.id)
            assertTrue(first.flush().getOrThrow().downloads.getValue(seeded.record.id).cleanupPending)
            a.close(); first.close()
            blocked.set(false)
            val reopened = store()
            val b = coordinator(reopened, gateway)
            b.initializeScheduling()
            assertEquals(retryAt, b.schedulingPlan().nextLocalEpochMs,
                "Reopening or a Resume rejected during cleanup cannot erase its durable failure backoff")
            clock.advance(5_000)
            b.start()
            val cleaned = eventually(reopened) { seeded.record.id !in it.downloads }
            assertTrue(cleaned.downloadAttempts.isEmpty())
            assertTrue(cleaned.downloadFinalizations.isEmpty())
            assertFalse(Files.exists(Path.of(seeded.intent.finalPath)))
            assertFalse(Files.exists(Path.of(old.uri)))
            assertFalse(Files.exists(Path.of(seeded.intent.stagingPath)))
            gateway.assertUnused()
        }
    }

    @Test fun removeWaitsForActualReleaseCallbackAndCanBeReenteredWithoutCommandLockInversion() = downloadCase {
        val first = store(); val second = store()
        val seeded = seedComplete(first)
        val released = barrier()
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gateway, beforeDelete = { released.stop() })
        a.start()
        coroutineScope {
            val removing = async { a.remove(seeded.record.id) }
            released.entered.await()
            assertTrue(second.flush().getOrThrow().downloads.getValue(seeded.record.id).cleanupPending)
            assertTrue(Files.exists(Path.of(seeded.record.asset!!.uri)))
            a.resume(seeded.record.id) // must return, not wait behind a native release callback
            a.pause(seeded.record.id) // removal intent is sticky and cannot be converted to resumable bytes
            assertFalse(removing.isCompleted)
            released.release()
            removing.await()
        }
        eventually(first) { seeded.record.id !in it.downloads }
        assertFalse(Files.exists(Path.of(seeded.record.asset!!.uri)))
        gateway.assertUnused()
    }

    @Test fun startupSameSizeCorruptionFailsDurablyAndRetryDeletesOldGenerationBeforeReacquiring() = downloadCase {
        val first = store()
        val seeded = seedComplete(first)
        val old = seeded.record.asset!!
        val damaged = authoredBytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        Files.write(Path.of(old.uri), damaged)
        val cleanup = barrier()
        val gateway = DownloadTestGateway()
        val a = coordinator(first, gateway, beforeDelete = { cleanup.stop() })
        a.start()
        eventually(first) { it.downloads[seeded.record.id]?.status == DownloadStatus.FAILED }
        assertEquals(0, validationCount.get(), "Hash corruption must be rejected before decoder validation")
        a.resume(seeded.record.id)
        cleanup.entered.await()
        assertTrue(first.flush().getOrThrow().downloads.getValue(seeded.record.id).cleanupPending)
        assertEquals(old, first.state.value.downloads[seeded.record.id]?.asset)
        assertTrue(Files.exists(Path.of(old.uri)))
        gateway.assertUnused()
        cleanup.release()
        val complete = eventually(first) { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        val repaired = complete.downloads.getValue(seeded.record.id).asset!!
        assertNotEquals(old.uri, repaired.uri)
        assertFalse(Files.exists(Path.of(old.uri)))
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(repaired.uri)))
        assertTrue(gateway.queries.isEmpty(), "Repair retains the bound recording instead of searching again")
        a.remove(seeded.record.id)
        eventually(first) { seeded.record.id !in it.downloads }
        assertFalse(Files.exists(Path.of(repaired.uri)))
    }

    @Test fun redownloadOfCorruptUnpublishedIntentCleansItInsteadOfReplayingForever() = downloadCase {
        val first = store()
        val seeded = seedFinalization(first, status = DownloadStatus.FAILED)
        Files.write(Path.of(seeded.intent!!.stagingPath), "corrupt".encodeToByteArray())
        first.update { it.copy(downloads = it.downloads + (seeded.record.id to seeded.record.copy(status = DownloadStatus.FAILED,
            failure = AppFailure(FailureCode.CORRUPT_MEDIA)))) }.getOrThrow()
        val gateway = DownloadTestGateway()
        val a = coordinator(first, gateway)
        a.enqueue(seeded.item)
        assertTrue(first.flush().getOrThrow().downloads.getValue(seeded.record.id).cleanupPending)
        a.start()
        val repaired = eventually(first) { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        assertFalse(Files.exists(Path.of(seeded.intent.stagingPath)))
        assertFalse(Files.exists(Path.of(seeded.intent.finalPath)))
        assertEquals(1, gateway.transfers.size)
        assertTrue(gateway.queries.isEmpty())
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(repaired.downloads.getValue(seeded.record.id).asset!!.uri)))
    }

    @Test fun pauseDuringNativeValidationRetainsBoundBytesAndResumeDoesNotSearchAgain() = executorSchema3Case {
        val a = owner()
        val observer = owner(store())
        val verifying = barrier()
        val validations = AtomicInteger()
        a.validation = {
            if (validations.incrementAndGet() == 1) withContext(NonCancellable) { verifying.stop() }
            AssetValidation.Valid(733)
        }
        val (id, selected) = enqueue(a)
        a.coordinator.start(DownloadActivation.DURABLE_WORK)
        verifying.entered.await()
        val original = join(observer, id)
        val part = a.gateway.transfers.single().path
        coroutineScope {
            val paused = async { a.coordinator.pause(id) }
            val saved = awaitJob(observer, id) { it.job.status == DownloadStatus.PAUSED }
            assertEquals(selected.context, saved.producerContext)
            assertEquals(authoredRepresentation, saved.job.representationId)
            assertEquals(original.job.activeAttemptId, saved.job.activeAttemptId)
            assertEquals(original.job.leaseOwner, saved.job.leaseOwner)
            assertFalse(paused.isCompleted)
            assertNull(probeFiles.tryLeasePaths(listOf(part)))
            assertNull(probeFiles.tryTransferSlot(1))
            verifying.release(); paused.await()
        }
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(part)))
        assertNull(join(a, id).activeAttempt)
        assertNotNull(probeFiles.tryLeasePaths(listOf(part))).close()
        a.coordinator.resume(id)
        complete(a, id)
        assertEquals(1, a.selector.requests.size)
        assertEquals(listOf(part, part), a.gateway.transfers.map { it.path })
        assertEquals(listOf(selected.context), a.gateway.transfers.map { it.context }.distinct())
        assertEquals(2, validations.get())
    }

}
