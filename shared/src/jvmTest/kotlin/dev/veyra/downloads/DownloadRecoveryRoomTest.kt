package dev.veyra.downloads

import dev.veyra.model.*
import dev.veyra.playback.AcquisitionPlaybackResolver
import dev.veyra.data.PhysicalDownloadCommit
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.EngineCapabilities
import kotlinx.coroutines.*
import okio.FileSystem
import okio.ForwardingFileSystem
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
        val cancelled = try {
            awaitJob(b, id) { it.job.status == DownloadStatus.CANCELLED && !it.job.cleanupPending }
        } catch (timeout: TimeoutCancellationException) {
            val observed = join(b, id).job
            throw AssertionError("Cleanup timed out: status=${observed.status}, pending=${observed.cleanupPending}, " +
                "maintenance=${observed.maintenanceKind}, generation=${observed.ownershipGeneration}, " +
                "owner=${observed.leaseOwner}, expires=${observed.leaseExpiresEpochMs}, now=${clock.epochMs()}, " +
                "failure=${observed.failure}, events=${events.takeLast(20)}", timeout)
        }
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
        val bDeleting = barrier()
        b.beforeDelete = { bDeleting.stop() }
        // A normal remove return must mean actual authoritative removal, not intent acceptance.
        coroutineScope {
            val removing = async(start = CoroutineStart.UNDISPATCHED) { runCatching { b.coordinator.remove(id) } }
            awaitCondition { join(b, id).job.cleanupPending }
            assertFalse(removing.isCompleted, "remove cannot return while the exact writer still owns the generation")
            assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(asset.uri)))
            assertNull(probeFiles.tryLeasePaths(listOf(asset.uri)))
            assertNull(probeFiles.tryTransferSlot(1))
            closing.release()
            a.coordinator.close()
            assertTrue(a.coordinator.dependencyReleaseSafeAfterClose)
            advance(5_000) // Actual ownership contention backoff, not an intent-only success.
            bDeleting.entered.await()
            assertFalse(removing.isCompleted, "remove must wait for the actual deletion, not cleanupPending")
            assertTrue(join(b, id).job.cleanupPending)
            assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(asset.uri)))
            bDeleting.release()
            removing.await().getOrThrow()
        }
        val removed = join(b, id)
        assertFalse(removed.job.cleanupPending)
        assertEquals(DownloadStatus.CANCELLED, removed.job.status)
        assertNull(removed.asset)
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

    @Test fun removeWhileHostSuspendedFailsTruthfullyAndSucceedsAfterForegroundResume() = executorSchema3Case {
        val owner = owner()
        val (id, _) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.USER_SESSION)
        val completed = complete(owner, id)
        val asset = assertNotNull(completed.asset)
        owner.coordinator.drainForBackground()
        val failure = assertFailsWith<VeyraException> { owner.coordinator.remove(id) }
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, failure.failure.code)
        assertEquals("download_owner_drain_unconfirmed", failure.failure.detail)
        val held = join(owner, id)
        assertTrue(held.job.cleanupPending)
        assertEquals(asset, held.asset)
        assertTrue(Files.exists(Path.of(asset.uri)))
        owner.coordinator.resumePending()
        owner.coordinator.remove(id)
        val removed = awaitJob(owner, id) { !it.job.cleanupPending && it.asset == null }
        assertEquals(DownloadStatus.CANCELLED, removed.job.status)
        assertFalse(Files.exists(Path.of(asset.uri)))
        assertEquals(completed.attempts, removed.attempts)
    }

    @Test fun removeOfActiveStagingWaitsForWriterUnwindAndDeletesOnlyTheActualStagingGeneration() = executorSchema3Case {
        val owner = owner()
        val entered = CompletableDeferred<Unit>()
        val unwinding = barrier()
        owner.gateway.transfer = { call ->
            call.write()
            entered.complete(Unit)
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { unwinding.stop() } }
        }
        val (id, _) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        entered.await()
        val stage = owner.gateway.transfers.single().path
        coroutineScope {
            val removing = async(start = CoroutineStart.UNDISPATCHED) { runCatching { owner.coordinator.remove(id) } }
            unwinding.entered.await()
            assertTrue(join(owner, id).job.cleanupPending)
            assertTrue(Files.exists(Path.of(stage)))
            assertFalse(removing.isCompleted, "An active staging writer must be joined, not acknowledged")
            unwinding.release()
            removing.await().getOrThrow()
        }
        val removed = awaitJob(owner, id) { !it.job.cleanupPending && it.asset == null }
        assertEquals(DownloadStatus.CANCELLED, removed.job.status)
        assertNull(removed.finalization)
        assertFalse(Files.exists(Path.of(stage)))
        assertEquals(DownloadAttemptStatus.PAUSED, removed.attempts.single().status)
        assertNotNull(removed.attempts.single().finishedEpochMs)
        assertTrue(owner.store.acquisitionView().acquisition.materializations.isEmpty())
    }

    @Test fun partialDeletionKeepsJournalForPriorAssetAndIntentDestinationUntilReopenCompletesAll() {
        // Typed history cannot legally carry a live asset AND a pending finalization on ONE job.
        // The legacy single-job shape is therefore split over two real canonical generations:
        // (A) a completed asset with a removal intent, and (B) an interrupted/publication intent
        // whose destination is created by the actual recovery rename. The same faulting FileSystem
        // blocks real deletes; every retained path/intent/asset row is asserted; only a cold owner
        // retries at the durable +5 s deadline.
        val blocked = AtomicReference<Set<String>>(emptySet())
        executorSchema3Case(AuthoredDeleteFault(blocked)) {
            val first = owner()
            val (assetId, _) = enqueue(first, item("partial-deletion-asset"), "ASSET000001")
            first.coordinator.start(DownloadActivation.USER_SESSION)
            val completed = complete(first, assetId)
            val priorAsset = assertNotNull(completed.asset)
            first.coordinator.pauseForBackground()
            val (intentId, _) = enqueue(first, item("partial-deletion-intent"), "INTENT00001")
            val intent = recoveryIntent(first, intentId, published = false)
            advance(1_100)
            val validating = barrier()
            first.validation = { withContext(NonCancellable) { validating.stop() }; AssetValidation.Valid(733) }
            first.coordinator.resumePending()
            validating.entered.await()
            // A second connection accepts removal intent but cannot ACK the first owner's live
            // recovery lease or its real path lease. No byte is fabricated as deleted.
            val second = owner(store())
            second.coordinator.start(DownloadActivation.DURABLE_WORK)
            val unacknowledged = assertFailsWith<VeyraException> { second.coordinator.remove(intentId) }
            assertEquals(FailureCode.BACKGROUND_RESTRICTED, unacknowledged.failure.code)
            val cancelling = join(second, intentId)
            assertTrue(cancelling.job.cleanupPending && cancelling.job.removeWhenClean)
            assertEquals(intent, cancelling.finalization)
            assertTrue(Files.exists(Path.of(intent.stagingPath)))
            assertFalse(Files.exists(Path.of(intent.finalPath)))
            // The prior completed asset has no live owner, so its real delete is attempted and
            // fails. The durable reservation, prior asset row and file remain.
            blocked.set(setOf(priorAsset.uri))
            val assetFailure = assertFailsWith<VeyraException> { second.coordinator.remove(assetId) }
            assertEquals(FailureCode.STORAGE_IO, assetFailure.failure.code)
            // The recovery rename creates the destination after this fault is armed. Its cleanup
            // must hit the same real delete and retain the journal rather than reporting success.
            blocked.set(setOf(priorAsset.uri, intent.finalPath))
            validating.release()
            val retainedIntent = awaitJob(second, intentId) {
                it.job.cleanupPending && it.job.leaseOwner == null && it.job.failure?.code == FailureCode.STORAGE_IO
            }
            first.coordinator.close()
            val failedAsset = join(second, assetId)
            assertTrue(failedAsset.job.cleanupPending)
            assertEquals(FailureCode.STORAGE_IO, failedAsset.job.failure?.code)
            assertEquals(priorAsset, failedAsset.asset, "Do not lose the prior completed generation")
            assertEquals(intent, retainedIntent.finalization, "Retain the intent destination journal")
            assertNull(retainedIntent.asset)
            assertTrue(Files.exists(Path.of(priorAsset.uri)))
            assertTrue(Files.exists(Path.of(intent.finalPath)))
            assertFalse(Files.exists(Path.of(intent.stagingPath)))
            val retryAt = assertNotNull(failedAsset.job.nextRetryEpochMs)
            assertEquals(clock.epochMs() + 5_000, retryAt)
            assertEquals(retryAt, retainedIntent.job.nextRetryEpochMs)
            assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> { second.coordinator.resume(assetId) }.failure.code,
                "Resume rejected during cleanup cannot erase the durable backoff")
            assertTrue(join(second, assetId).job.cleanupPending)
            second.coordinator.close()
            closeStore(first.store)
            closeStore(second.store)
            blocked.set(emptySet())
            val reopened = owner(store())
            reopened.coordinator.initializeScheduling()
            assertEquals(retryAt, reopened.coordinator.schedulingPlan().nextLocalEpochMs,
                "Reopening or a rejected resume cannot erase the durable cleanup backoff")
            advance(5_000)
            reopened.coordinator.start(DownloadActivation.DURABLE_WORK)
            val cleanedAsset = awaitJob(reopened, assetId) { !it.job.cleanupPending && it.asset == null }
            val cleanedIntent = awaitJob(reopened, intentId) {
                !it.job.cleanupPending && it.asset == null && it.finalization == null
            }
            assertEquals(DownloadStatus.CANCELLED, cleanedAsset.job.status)
            assertEquals(DownloadStatus.CANCELLED, cleanedIntent.job.status)
            assertEquals(completed.attempts, cleanedAsset.attempts, "Cleanup keeps immutable attempt history")
            assertNotNull(cleanedIntent.attempts.single().finishedEpochMs)
            assertFalse(Files.exists(Path.of(priorAsset.uri)))
            assertFalse(Files.exists(Path.of(intent.finalPath)))
            assertFalse(Files.exists(Path.of(intent.stagingPath)))
            val state = reopened.store.acquisitionView().acquisition
            assertTrue(state.materializations.isEmpty())
            assertEquals(priorAsset.receipt(), state.materializationReceipts[priorAsset.id],
                "Historical receipt survives live-byte removal")
            assertTrue(state.assetUses.any { it.materializationAssetId == priorAsset.id })
            assertTrue(reopened.gateway.prepareCalls.isEmpty()); assertTrue(reopened.gateway.transfers.isEmpty())
            assertTrue(reopened.selector.requests.isEmpty())
            reopened.legacy.assertUnused()
        }
    }

    @Test fun removeWaitsForActualReleaseCallbackAndCanBeReenteredWithoutCommandLockInversion() = executorSchema3Case {
        val first = owner()
        val (id, _) = enqueue(first)
        first.coordinator.start(DownloadActivation.USER_SESSION)
        val completed = complete(first, id)
        val asset = assertNotNull(completed.asset)
        val preparationsBefore = first.gateway.prepareCalls.size
        val transfersBefore = first.gateway.transfers.size
        val released = barrier()
        first.beforeDelete = { released.stop() }
        coroutineScope {
            val removing = async { first.coordinator.remove(id) }
            released.entered.await()
            val cancelling = join(first, id)
            assertTrue(cancelling.job.cleanupPending)
            assertEquals(asset, cancelling.asset)
            assertTrue(Files.exists(Path.of(asset.uri)))
            val removalClaim = first.store.acquisitionView().acquisition.claims.values.single()
            // Both commands must return promptly while the native release callback is held; the
            // sticky removal intent rejects them instead of waiting behind the callback.
            assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> { first.coordinator.resume(id) }.failure.code)
            assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> { first.coordinator.pause(id) }.failure.code)
            assertEquals(removalClaim, first.store.acquisitionView().acquisition.claims.values.single(),
                "Rejected resume/pause cannot mutate the removal claim")
            assertFalse(removing.isCompleted)
            released.release()
            removing.await()
        }
        val removed = awaitJob(first, id) { !it.job.cleanupPending && it.asset == null }
        assertEquals(DownloadStatus.CANCELLED, removed.job.status)
        assertNull(removed.finalization)
        assertEquals(completed.attempts, removed.attempts)
        assertFalse(Files.exists(Path.of(asset.uri)))
        assertTrue(first.store.acquisitionView().acquisition.materializations.isEmpty())
        assertEquals(asset.receipt(), first.store.acquisitionView().acquisition.materializationReceipts[asset.id])
        assertTrue(first.store.acquisitionView().acquisition.assetUses.any { it.materializationAssetId == asset.id })
        assertEquals(preparationsBefore, first.gateway.prepareCalls.size)
        assertEquals(transfersBefore, first.gateway.transfers.size)
        first.legacy.assertUnused()
    }

    @Test fun startupSameSizeCorruptionFailsDurablyAndRetryDeletesOldGenerationBeforeReacquiring() = executorSchema3Case {
        val first = owner()
        val (id, selected) = enqueue(first)
        first.coordinator.start(DownloadActivation.USER_SESSION)
        val completed = complete(first, id)
        val old = assertNotNull(completed.asset)
        first.coordinator.close()
        closeStore(first.store)
        val damaged = authoredBytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        Files.write(Path.of(old.uri), damaged)
        val second = owner(store())
        second.coordinator.start(DownloadActivation.USER_SESSION)
        val failed = awaitJob(second, id) { it.job.status == DownloadStatus.FAILED && it.job.leaseOwner == null }
        assertEquals(FailureCode.CORRUPT_MEDIA, failed.job.failure?.code)
        assertEquals(0, second.validations.size, "Hash corruption must be rejected before decoder validation")
        assertEquals(old, failed.asset)
        advance(5_000)
        val cleanup = barrier()
        second.beforeDelete = { cleanup.stop() }
        coroutineScope {
            val retry = async { second.coordinator.remove(id) }
            cleanup.entered.await()
            val retaining = join(second, id)
            assertTrue(retaining.job.cleanupPending)
            assertEquals(old, retaining.asset)
            assertTrue(Files.exists(Path.of(old.uri)))
            cleanup.release()
            retry.await()
        }
        awaitJob(second, id) { !it.job.cleanupPending && it.asset == null }
        assertFalse(Files.exists(Path.of(old.uri)),
            "Retry must delete the old generation BEFORE reacquiring it")
        second.beforeDelete = { }
        second.coordinator.enqueueAccepted(selected.record.request.id, selected.context.binding.id) { }
        second.coordinator.resume(id)
        val ready = complete(second, id)
        val replacement = assertNotNull(ready.asset)
        assertNotEquals(old.uri, replacement.uri)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(replacement.uri)))
        assertEquals(1, second.gateway.transfers.size)
        assertEquals(selected.context, second.gateway.transfers.single().context)
        assertTrue(second.selector.requests.isEmpty(),
            "Repair retains the bound recording instead of searching again")
        second.coordinator.remove(id)
        awaitJob(second, id) { !it.job.cleanupPending && it.asset == null }
        assertFalse(Files.exists(Path.of(replacement.uri)))
        second.legacy.assertUnused()
    }

    @Test fun redownloadOfCorruptUnpublishedIntentCleansItInsteadOfReplayingForever() = executorSchema3Case {
        val first = owner()
        val (id, selected) = enqueue(first)
        val intent = recoveryIntent(first, id)
        advance(1_100)
        Files.write(Path.of(intent.stagingPath), "corrupt".encodeToByteArray())
        first.coordinator.start(DownloadActivation.DURABLE_WORK)
        val failed = awaitJob(first, id) { it.job.status == DownloadStatus.FAILED && it.job.leaseOwner == null }
        assertEquals(FailureCode.CORRUPT_MEDIA, failed.job.failure?.code)
        assertEquals(intent, failed.finalization)
        assertNull(failed.asset)
        assertTrue(first.validations.isEmpty(), "Corrupt staged bytes must fail before native validation")
        advance(5_000) // Published bounded local recheck, not a retry-until-green loop.
        first.coordinator.remove(id)
        val cleaned = awaitJob(first, id) {
            !it.job.cleanupPending && it.asset == null && it.finalization == null
        }
        assertFalse(Files.exists(Path.of(intent.stagingPath)))
        assertFalse(Files.exists(Path.of(intent.finalPath)))
        assertEquals(DownloadAttemptStatus.PAUSED, cleaned.attempts.single().status)
        assertNotNull(cleaned.attempts.single().finishedEpochMs)
        first.coordinator.enqueueAccepted(selected.record.request.id, selected.context.binding.id) { }
        first.coordinator.resume(id)
        val ready = complete(first, id)
        assertEquals(1, first.gateway.transfers.size)
        assertEquals(selected.context, first.gateway.transfers.single().context)
        assertEquals(1, first.selector.requests.size, "Redownload must not issue a new title query")
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(assertNotNull(ready.asset).uri)))
        assertEquals(1, first.validations.size)
        first.legacy.assertUnused()
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

/**
 * Real filesystem wrapper for one authored sharing-violation delete. Every other filesystem
 * operation (including unblocked deletes) reaches the real system filesystem unchanged.
 */
private class AuthoredDeleteFault(private val blocked: AtomicReference<Set<String>>) : ForwardingFileSystem(FileSystem.SYSTEM) {
    override fun onPathParameter(path: okio.Path, functionName: String, parameterName: String): okio.Path {
        if (functionName == "delete" && path.toString() in blocked.get()) throw IOException("authored sharing violation")
        return path
    }
}
