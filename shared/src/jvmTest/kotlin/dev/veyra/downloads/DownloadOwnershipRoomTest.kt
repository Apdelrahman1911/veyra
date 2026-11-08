package dev.veyra.downloads

import dev.veyra.model.*
import dev.veyra.playback.AssetValidation
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** D01–D08/D12/D13: real SQLite arbitration and filesystem fencing; fake acquisition, NOT AUDIO. */
class DownloadOwnershipRoomTest {
    @Test fun foreignFutureLeaseSurvivesStartBackgroundRestrictionAndClose() = executorSchema3Case {
        // EX20: foreign ownership is from ANOTHER actual typed executor, not a manufactured row.
        val a = owner(heartbeatMs = 30_000, leaseDurationMs = 60_000)
        val b = owner(store())
        val entered = CompletableDeferred<Unit>()
        a.gateway.transfer = { call -> call.write(); entered.complete(Unit); awaitCancellation() }
        val (id, selected) = enqueue(a)
        a.coordinator.start(DownloadActivation.DURABLE_WORK)
        entered.await()
        val foreign = join(a, id)
        assertNotNull(foreign.job.leaseOwner)
        assertNotNull(foreign.activeAttempt)
        assertEquals(selected.context, foreign.producerContext)
        b.coordinator.start(DownloadActivation.DURABLE_WORK)
        b.coordinator.pauseForBackground()
        b.coordinator.close()
        assertTrue(b.coordinator.dependencyReleaseSafeAfterClose)
        assertEquals(foreign, join(a, id))
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(assertNotNull(foreign.job.stagingPath))))
        assertTrue(b.selector.requests.isEmpty())
        assertTrue(b.gateway.prepareCalls.isEmpty())
        assertTrue(b.gateway.transfers.isEmpty())
        assertEquals(0, b.gate.acquires.get())
        assertNull(probeFiles.tryLeasePaths(listOf(assertNotNull(foreign.job.stagingPath))))
        b.legacy.assertUnused()
    }

    @Test fun twoReplayersHaveOneDurableClaimAndOnePublisher() = acquisitionDownloadRoomCase {
        // EX13: two genuine Room connections observe the SAME authored interrupted finalization.
        // Exactly one canonical maintenance claim is admitted and publishes; the other cannot.
        val first = store()
        val second = store()
        val a = owner(first)
        val b = owner(second)
        val seeded = prepareFinalizationSchema3(a)
        a.denyFinalizationSchema3Acquisition()
        b.denyFinalizationSchema3Acquisition()
        val validating = barrier()
        a.validation = { validating.stop(); AssetValidation.Valid(31_234) }
        a.coordinator.start(DownloadActivation.DURABLE_WORK)
        validating.entered.await()
        val claim = a.store.acquisitionView().acquisition.physicalDownloads.getValue(seeded.record.id)
        assertNotNull(claim.leaseOwner)
        assertEquals(PhysicalDownloadMaintenanceKind.RECOVER_FINALIZATION, claim.maintenanceKind)
        assertEquals(DownloadStatus.VERIFYING, claim.status)
        assertEquals(1, a.validations.size)
        b.coordinator.start(DownloadActivation.DURABLE_WORK)
        b.coordinator.close()
        assertEquals(claim.leaseOwner, a.store.acquisitionView().acquisition.physicalDownloads[claim.id]?.leaseOwner,
            "A second replayer cannot steal or clear the live canonical maintenance claim")
        assertEquals(1, a.validations.size)
        assertTrue(b.validations.isEmpty())
        validating.release()
        val durable = awaitState(a.store) { it.physicalDownloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        a.assertFinalizationSchema3Recovered(seeded)
        assertEquals(1, a.validations.size)
        assertTrue(b.validations.isEmpty())
        assertEquals(1, durable.materializations.size)
        assertEquals(1, durable.materializationReceipts.size)
        assertEquals(1, durable.assetUses.size)
        assertFalse(seeded.record.id in durable.physicalFinalizations)
        assertFalse(Files.exists(Path.of(seeded.intent.stagingPath)))
        assertEquals(DownloadAttemptStatus.PAUSED, durable.physicalAttempts.getValue(seeded.attempt.id).status,
            "Recovery preserves the retired materializer history; it cannot relabel it SUCCEEDED")
        a.assertFinalizationSchema3SourceUnused()
        b.assertFinalizationSchema3SourceUnused()
        a.legacy.assertUnused(); b.legacy.assertUnused()
    }

    @Test fun removeDuringNoncancellableValidationCannotPublishOrResurrect() = executorSchema3Case {
        // A second real connection commits removal intent while A is inside an uncancellable
        // decoder callback. It cannot ACK A's owner, but the irreversible reservation must fence
        // publication and the eventual cleanup must not resurrect anything.
        val a = owner()
        val (id, _) = enqueue(a)
        val intent = recoveryIntent(a, id)
        advance(1_100) // Expire only the authored crashed finalizer's canonical lease.
        val validating = barrier()
        a.validation = { withContext(NonCancellable) { validating.stop() }; AssetValidation.Valid(733) }
        a.coordinator.start(DownloadActivation.DURABLE_WORK)
        validating.entered.await()
        val b = owner(store())
        b.coordinator.start(DownloadActivation.DURABLE_WORK)
        val removal = assertFailsWith<VeyraException> { b.coordinator.remove(id) }
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, removal.failure.code)
        assertEquals("download_owner_drain_unconfirmed", removal.failure.detail)
        val cancelling = join(b, id)
        assertTrue(cancelling.job.cleanupPending && cancelling.job.removeWhenClean)
        assertEquals(intent, cancelling.finalization)
        assertTrue(Files.exists(Path.of(intent.stagingPath)), "A still owns the generation path lease")
        assertFalse(Files.exists(Path.of(intent.finalPath)))
        assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> { b.coordinator.resume(id) }.failure.code,
            "Removal intent is sticky; resume cannot convert it back to resumable bytes")
        validating.release()
        a.coordinator.close()
        val removed = awaitJob(b, id) { !it.job.cleanupPending && it.asset == null }
        assertEquals(DownloadStatus.CANCELLED, removed.job.status)
        assertNull(removed.finalization)
        assertEquals(DownloadAttemptStatus.PAUSED, removed.attempts.single().status)
        assertNotNull(removed.attempts.single().finishedEpochMs)
        assertFalse(Files.exists(Path.of(intent.stagingPath)))
        assertFalse(Files.exists(Path.of(intent.finalPath)))
        assertTrue(b.store.acquisitionView().acquisition.materializations.isEmpty())
        assertTrue(b.store.acquisitionView().acquisition.materializationReceipts.isEmpty())
        assertEquals(1, a.validations.size)
        assertTrue(b.validations.isEmpty())
        assertTrue(a.gateway.prepareCalls.isEmpty()); assertTrue(a.gateway.transfers.isEmpty())
        assertTrue(b.gateway.prepareCalls.isEmpty()); assertTrue(b.gateway.transfers.isEmpty())
        a.legacy.assertUnused(); b.legacy.assertUnused()
    }

    @Test fun changedOwnerAttemptAndIntentRejectStaleRecoveryAndFailureUpdates() = executorSchema3Case {
        // A concurrent legal claim-intent replacement is admitted while the old recovery owner is
        // inside an uncancellable validator call. The old finalizer must not rewrite that claim,
        // its finalization, the producer/attempt history, or rename/publish against it.
        val a = owner()
        val (id, _) = enqueue(a, item("changed-owner-history"), "OWNER000001")
        val intent = recoveryIntent(a, id)
        advance(1_100) // Expire only the authored crashed finalizer's canonical lease.
        val before = join(a, id)
        val oldProducer = before.job.producer
        val oldAttempt = assertNotNull(before.activeAttempt)
        val validating = barrier()
        a.validation = {
            withContext(NonCancellable) { validating.stop() }
            AssetValidation.Invalid(AppFailure(FailureCode.CORRUPT_MEDIA, "old_validator_rejected"))
        }
        a.coordinator.start(DownloadActivation.DURABLE_WORK)
        validating.entered.await()
        val b = owner(store())
        b.coordinator.start(DownloadActivation.DURABLE_WORK)
        val removal = assertFailsWith<VeyraException> { b.coordinator.remove(id) }
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, removal.failure.code)
        val replacementClaim = join(b, id).claims.single()
        assertEquals(DownloadBatchIntent.CANCELLED, replacementClaim.intent)
        assertFalse(replacementClaim.retainCompletedAsset)
        val cancelled = join(b, id)
        assertTrue(cancelled.job.cleanupPending && cancelled.job.removeWhenClean)
        assertEquals(intent, cancelled.finalization)
        assertEquals(oldProducer, cancelled.job.producer)
        assertTrue(Files.exists(Path.of(intent.stagingPath)))
        assertFalse(Files.exists(Path.of(intent.finalPath)))
        assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> { b.coordinator.resume(id) }.failure.code)
        val cleanup = barrier()
        // Whichever real connection claims cleanup must stop before deleting the retained bytes.
        a.gate.beforeAcquire = { cleanup.stop() }
        b.gate.beforeAcquire = { cleanup.stop() }
        validating.release()
        val settled = awaitJob(a, id) { it.job.leaseOwner == null && it.job.maintenanceKind == null }
        assertEquals(FailureCode.CORRUPT_MEDIA, settled.job.failure?.code)
        val afterOldFinish = join(b, id)
        assertEquals(replacementClaim, afterOldFinish.claims.single(),
            "The old finalizer cannot rewrite the replacement claim/generation/intent")
        assertEquals(intent, afterOldFinish.finalization,
            "The immutable finalization journal cannot be replaced by the stale owner")
        assertEquals(oldProducer, afterOldFinish.job.producer)
        assertEquals(intent.attemptId, afterOldFinish.finalization?.attemptId)
        assertEquals(oldAttempt.copy(status = DownloadAttemptStatus.PAUSED,
            finishedEpochMs = afterOldFinish.attempts.single { it.id == oldAttempt.id }.finishedEpochMs),
            afterOldFinish.attempts.single { it.id == oldAttempt.id },
            "The old claim retires its materializer history once; the stale finish cannot rewrite it")
        assertTrue(afterOldFinish.job.cleanupPending, "Removal intent stays durable")
        assertEquals(clock.epochMs() + 5_000, afterOldFinish.job.nextRetryEpochMs,
            "The old validator's primitive failure carries the real bounded local recheck")
        assertFalse(Files.exists(Path.of(intent.finalPath)))
        advance(5_000)
        cleanup.entered.await()
        assertTrue(Files.exists(Path.of(intent.stagingPath)),
            "Cleanup is gated before it may delete the retained generation bytes")
        cleanup.release()
        val cleaned = awaitJob(b, id) { !it.job.cleanupPending && it.asset == null }
        a.coordinator.close()
        assertEquals(DownloadStatus.CANCELLED, cleaned.job.status)
        assertNull(cleaned.finalization)
        assertNull(cleaned.job.failure)
        assertFalse(Files.exists(Path.of(intent.stagingPath)))
        assertFalse(Files.exists(Path.of(intent.finalPath)))
        assertTrue(b.store.acquisitionView().acquisition.materializations.isEmpty())
        assertTrue(b.store.acquisitionView().acquisition.materializationReceipts.isEmpty())
        assertEquals(1, a.validations.size)
        assertTrue(b.validations.isEmpty())
        assertTrue(a.gateway.prepareCalls.isEmpty()); assertTrue(a.gateway.transfers.isEmpty())
        assertTrue(b.gateway.prepareCalls.isEmpty()); assertTrue(b.gateway.transfers.isEmpty())
        a.legacy.assertUnused(); b.legacy.assertUnused()
    }

    @Test fun silentTransferHeartbeatRevokesParentAndCleanupWaitsForUnwind() = executorSchema3Case {
        // EX21: foreign stop accepts irreversible intent but cannot ACK the other live writer.
        val a = owner()
        val b = owner(store())
        val entered = CompletableDeferred<Unit>()
        val unwinding = barrier()
        a.gateway.transfer = { call ->
            call.write(); entered.complete(Unit)
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { unwinding.stop() } }
        }
        val (id, selected) = enqueue(a)
        a.coordinator.start(DownloadActivation.DURABLE_WORK)
        entered.await()
        b.coordinator.start(DownloadActivation.DURABLE_WORK)
        val original = join(a, id)
        val stage = a.gateway.transfers.single().path
        assertEquals(AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_unconfirmed"),
            assertFailsWith<VeyraException> { b.coordinator.cancel(id) }.failure)
        assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> { b.coordinator.resume(id) }.failure.code)
        unwinding.entered.await()
        val cancelling = join(b, id)
        assertTrue(cancelling.job.cleanupPending)
        assertEquals(DownloadBatchIntent.CANCELLED, cancelling.claims.single().intent)
        assertEquals(selected.context, cancelling.producerContext)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(stage)))
        assertNull(probeFiles.tryLeasePaths(listOf(stage)))
        assertEquals(1, a.gateway.transfers.size)
        assertTrue(b.gateway.transfers.isEmpty())
        unwinding.release()
        awaitCondition { a.gateway.transfers.single().operation.isCompleted }
        advance(5_100) // Actual local cleanup contention backoff, not an extra user retry.
        val durable = awaitJob(b, id) {
            it.job.status == DownloadStatus.CANCELLED && !it.job.cleanupPending && it.job.leaseOwner == null
        }
        assertNull(durable.activeAttempt)
        assertFalse(Files.exists(Path.of(stage)))
        assertEquals(original.activeAttempt?.id, durable.attempts.single().id)
        assertNotNull(durable.attempts.single().finishedEpochMs)
        assertEquals(DownloadAttemptStatus.PAUSED, durable.attempts.single().status,
            "Schema3 retains the real cancelled coroutine's PAUSED finish; cleanup cannot rewrite history")
        assertEquals(1, a.gateway.transfers.size + b.gateway.transfers.size)
        assertEquals(1, a.selector.requests.size)
        assertTrue(b.selector.requests.isEmpty())
        assertNull(durable.asset)
    }

    @Test fun expiredZombieCannotWriteNewGenerationOrDemoteWinningCompletion() = executorSchema3Case {
        // EX22: logical expiry permits a fresh, separately OS-locked generation, not old-path reuse.
        val a = owner()
        val b = owner(store())
        a.expectedCloseFailure = AppFailure(FailureCode.INVALID_STATE, "download_checkpoint_stale")
        parallelism(a, 2)
        val zombie = barrier()
        val oldReturned = CompletableDeferred<Unit>()
        a.gateway.transfer = { call ->
            call.write("old authored partial".encodeToByteArray())
            withContext(NonCancellable) {
                zombie.stop(); call.write("late authored old writer".encodeToByteArray()); oldReturned.complete(Unit)
            }
            call.result()
        }
        val (id, selected) = enqueue(a)
        a.coordinator.start(DownloadActivation.DURABLE_WORK)
        zombie.entered.await()
        val old = join(a, id)
        val oldPath = a.gateway.transfers.single().path
        b.coordinator.start(DownloadActivation.DURABLE_WORK)
        advance(1_100)
        val winner = complete(b, id)
        val asset = assertNotNull(winner.asset)
        val newPath = b.gateway.transfers.single().path
        assertNotEquals(oldPath, newPath)
        assertTrue(asset.ownershipGeneration > old.job.ownershipGeneration)
        assertEquals(selected.context, b.gateway.transfers.single().context)
        assertEquals(a.gateway.transfers.single().context, b.gateway.transfers.single().context)
        assertTrue(b.selector.requests.isEmpty())
        assertNull(probeFiles.tryLeasePaths(listOf(oldPath)))
        val retiredAttempt = winner.attempts.single { it.id == old.activeAttempt?.id }
        assertEquals(DownloadAttemptStatus.PAUSED, retiredAttempt.status)
        assertNotNull(retiredAttempt.finishedEpochMs)
        zombie.release(); oldReturned.await()
        awaitCondition { a.gateway.transfers.single().operation.isCompleted }
        val afterLate = join(b, id)
        assertEquals(asset, afterLate.asset)
        assertEquals(DownloadStatus.COMPLETE, afterLate.job.status)
        assertEquals(retiredAttempt, afterLate.attempts.single { it.id == retiredAttempt.id })
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(asset.uri)))
        advance(5_100)
        awaitCondition { !Files.exists(Path.of(oldPath)) }
        val durable = join(b, id)
        assertEquals(2, durable.attempts.size, "History stays immutable even after obsolete physical bytes are deleted")
        assertEquals(1, durable.attempts.count { it.status == DownloadAttemptStatus.SUCCEEDED })
        assertEquals(retiredAttempt, durable.attempts.single { it.id == retiredAttempt.id })
    }

    @Test fun expiryDoesNotExceedOnePhysicalTransferWhileZombieHoldsGlobalSlot() = executorSchema3Case {
        // EX23: database expiry cannot unlock a real OS slot; the new claim waits for its deadline.
        val a = owner()
        val b = owner(store())
        a.expectedCloseFailure = AppFailure(FailureCode.INVALID_STATE, "download_checkpoint_stale")
        parallelism(a, 1)
        val zombie = barrier()
        a.gateway.transfer = { call -> withContext(NonCancellable) { zombie.stop() }; call.complete() }
        val (id, _) = enqueue(a)
        a.coordinator.start(DownloadActivation.DURABLE_WORK)
        zombie.entered.await()
        b.coordinator.start(DownloadActivation.DURABLE_WORK)
        advance(1_100)
        awaitJob(b, id) { it.job.leaseOwner == null }
        delay(160)
        assertTrue(b.gateway.transfers.isEmpty())
        assertNull(probeFiles.tryTransferSlot(1))
        assertFalse(a.gateway.transfers.single().operation.isCompleted)
        zombie.release()
        awaitCondition { a.gateway.transfers.single().operation.isCompleted }
        advance(5_100)
        val completed = complete(b, id, evidenceOwners = listOf(a, b))
        assertEquals(DownloadStatus.COMPLETE, completed.job.status)
        assertEquals(1, b.store.acquisitionView().acquisition.physicalDownloads.size)
        assertEquals(2, a.gateway.transfers.size + b.gateway.transfers.size)
        assertEquals(1, a.selector.requests.size)
        assertTrue(b.selector.requests.isEmpty())
    }

    @Test fun staleEnqueueResumeAndPauseCannotDemoteOtherConnectionsCompletedAsset() = executorSchema3Case {
        // The completed receipt/asset belongs to the first real owner. A second Room connection
        // may re-admit the same accepted recording, but enqueue/resume/pause are metadata only and
        // cannot rewrite or demote the durable COMPLETE generation.
        val a = owner()
        val (id, selected) = enqueue(a)
        a.coordinator.start(DownloadActivation.USER_SESSION)
        val completed = complete(a, id)
        val asset = assertNotNull(completed.asset)
        val b = owner(store())
        assertEquals(id, b.coordinator.enqueue(item(), selected.context.binding.id.value),
            "A same-recording re-admission resolves to the one existing physical job")
        b.coordinator.resume(id)
        b.coordinator.pause(id)
        val durable = b.store.acquisitionView().acquisition
        assertEquals(1, durable.physicalDownloads.size)
        val job = durable.physicalDownloads.getValue(id)
        assertEquals(DownloadStatus.COMPLETE, job.status)
        assertEquals(asset.id, job.assetId)
        assertEquals(asset.producer, job.producer)
        assertEquals(completed.asset, durable.materializations[asset.id])
        assertEquals(1, durable.materializations.size)
        assertEquals(1, durable.assetUses.size)
        assertNull(durable.physicalFinalizations[id])
        assertFalse(job.cleanupPending)
        assertNull(job.leaseOwner)
        assertNull(job.activeAttemptId)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(asset.uri)))
        val claim = durable.claims.values.single()
        assertEquals(DownloadBatchIntent.PAUSED, claim.intent)
        assertTrue(claim.retainCompletedAsset)
        assertTrue(b.validations.isEmpty())
        assertTrue(b.gateway.prepareCalls.isEmpty()); assertTrue(b.gateway.transfers.isEmpty())
        assertTrue(b.selector.requests.isEmpty())
        assertEquals(0, b.gate.acquires.get())
        assertEquals(1, a.gateway.transfers.size)
        assertEquals(1, a.selector.requests.size)
        a.legacy.assertUnused(); b.legacy.assertUnused()
    }

    @Test fun publishedFailedIntentIsRecoveredAfterForeignLeaseExpiresWithoutRestart() = executorSchema3Case {
        // Canonical authored interrupted history: a foreign finalizer prepared and published its
        // intent, then its process lease survived while its OS locks were released. Another live
        // coordinator must wait for that exact lease, then publish from the existing bytes without
        // a restart, a new source query or a transfer.
        val a = owner()
        val (id, _) = enqueue(a)
        val intent = recoveryIntent(a, id, published = true)
        val b = owner(store())
        b.coordinator.start(DownloadActivation.DURABLE_WORK)
        val foreign = join(b, id)
        assertEquals(intent, foreign.finalization)
        assertNotNull(foreign.job.leaseOwner)
        assertNotNull(foreign.job.leaseExpiresEpochMs)
        assertEquals(DownloadStatus.VERIFYING, foreign.job.status)
        assertNull(foreign.asset)
        assertEquals(foreign.job.leaseExpiresEpochMs, b.coordinator.schedulingPlan().nextLocalEpochMs,
            "The unexpired foreign owner is an exact bounded recovery deadline, not an inference")
        assertTrue(b.validations.isEmpty())
        assertTrue(b.gateway.prepareCalls.isEmpty()); assertTrue(b.gateway.transfers.isEmpty())
        assertEquals(0, b.gate.acquires.get())
        assertTrue(b.selector.requests.isEmpty())
        advance(1_100)
        val completed = complete(b, id)
        val asset = assertNotNull(completed.asset)
        assertEquals(intent.finalPath, asset.uri)
        assertEquals(intent.assetId, asset.id)
        assertEquals(intent.attemptId, asset.materializerAttemptId)
        assertEquals(intent.ownershipGeneration, asset.ownershipGeneration)
        assertNull(completed.finalization)
        assertEquals(DownloadAttemptStatus.PAUSED, completed.attempts.single { it.id == intent.attemptId }.status)
        assertNotNull(completed.attempts.single { it.id == intent.attemptId }.finishedEpochMs)
        assertEquals(1, b.validations.size)
        assertTrue(b.gateway.prepareCalls.isEmpty()); assertTrue(b.gateway.transfers.isEmpty())
        assertTrue(b.selector.requests.isEmpty())
        b.legacy.assertUnused()
    }

    @Test fun unboundRetainedBytesNeverAcquireAFreshReceiptInTheirOldDirectory() = executorSchema3Case {
        // EX24: genuine exported2→3 migration, UNKNOWN until an explicit current-title selection.
        val legacy = unknownSchema2()
        val a = owner()
        val before = a.store.acquisitionView()
        val old = before.acquisition.physicalDownloads.getValue(legacy.jobId)
        val oldProducer = assertIs<PhysicalDownloadProducer.Schema2>(old.producer)
        assertNull(oldProducer.bindingId)
        assertNull(oldProducer.recordingId)
        val historicalClaim = before.acquisition.claims.values.single()
        assertNull(historicalClaim.requestedBindingId)
        assertEquals(legacy.path, old.stagingPath)
        assertTrue(before.acquisition.requests.isEmpty())
        assertTrue(before.acquisition.receipts.isEmpty())
        assertTrue(before.acquisition.bindings.isEmpty())
        a.coordinator.start(DownloadActivation.DURABLE_WORK)
        idle(a)
        assertTrue(a.selector.requests.isEmpty(), "Cold UNKNOWN history must never reselect today's title")
        assertTrue(a.gateway.prepareCalls.isEmpty())
        assertTrue(a.gateway.transfers.isEmpty())
        assertContentEquals(EXECUTOR_UNKNOWN_BYTES, Files.readAllBytes(Path.of(legacy.path)))
        val selected = select(a, before.snapshot.media.getValue(legacy.mediaId))
        val adoptionChecks = AtomicInteger()
        a.beforeAdoption = { producer ->
            assertEquals(oldProducer, producer)
            assertFalse(Files.exists(Path.of(legacy.path)))
            assertNotNull(probeFiles.tryLeasePaths(listOf(legacy.path))).close()
            adoptionChecks.incrementAndGet() // No player exists in this source/Room fixture.
        }
        assertEquals(legacy.jobId, a.coordinator.adoptSchema2(legacy.jobId, selected.record.request.id, selected.context.binding.id) {
            check(selected.current.get()) { "Authored original adoption intent revoked" }
            selected.admissionChecks.incrementAndGet()
        })
        val done = complete(a, legacy.jobId, originUse = false)
        assertEquals(1, adoptionChecks.get())
        assertTrue(selected.admissionChecks.get() > 0)
        assertNotEquals(legacy.path, a.gateway.transfers.single().path)
        assertNotEquals(Path.of(legacy.path).parent, Path.of(a.gateway.transfers.single().path).parent)
        assertFalse(Files.exists(Path.of(legacy.path)))
        assertEquals(listOf(selected.record.request), a.selector.requests)
        assertEquals(selected.context, done.producerContext)
        assertEquals(before.acquisition.physicalAttempts, emptyMap())
        val after = a.store.acquisitionView().acquisition
        assertEquals(historicalClaim.owner, after.claims.getValue(historicalClaim.id).owner)
        assertNull(after.claims.getValue(historicalClaim.id).requestedBindingId)
        assertTrue(after.assetUses.isEmpty(), "A null historical claim must not invent a requested new producer use")
        assertEquals(selected.context.binding.id, done.asset?.producer?.bindingId)
        a.legacy.assertUnused()
    }

    @Test fun explicitPlayReceiptPreventsASecondSearchAndDifferentRecordingReplacement() = executorSchema3Case {
        // EX25: selections are genuine accepted requests; download uses the already accepted Play R1.
        val a = owner()
        val item = item()
        val played = select(a, item)
        val other = select(a, item, "BBBBBBBBBBB")
        val queries = a.selector.requests.toList()
        val id = a.coordinator.enqueue(item, played.context.binding.id.value)
        a.coordinator.start(DownloadActivation.DURABLE_WORK)
        val completed = complete(a, id)
        assertEquals(played.context.binding.id, completed.asset?.producer?.bindingId)
        assertEquals(queries, a.selector.requests, "Download cannot search a selected title again")
        assertEquals(played.context, a.gateway.transfers.single().context)
        val beforeConflict = a.store.acquisitionView().acquisition
        assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> {
            a.coordinator.enqueue(item, other.context.binding.id.value)
        }.failure.code)
        assertEquals(completed, join(a, id))
        val afterConflict = a.store.acquisitionView().acquisition
        assertEquals(beforeConflict.physicalDownloads, afterConflict.physicalDownloads)
        assertEquals(beforeConflict.claims, afterConflict.claims)
        assertEquals(beforeConflict.materializations, afterConflict.materializations)
        assertEquals(beforeConflict.assetUses, afterConflict.assetUses)
        assertEquals(queries, a.selector.requests)
    }

}
