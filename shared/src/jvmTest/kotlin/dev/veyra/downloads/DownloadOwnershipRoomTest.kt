package dev.veyra.downloads

import dev.veyra.data.getOrThrow
import dev.veyra.model.*
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.AssetValidator
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

    @Test fun twoReplayersHaveOneDurableClaimAndOnePublisher() = downloadCase {
        val first = store()
        val second = store()
        val seeded = seedFinalization(first)
        val barrier = barrier()
        val validations = AtomicInteger()
        val validator = AssetValidator { validations.incrementAndGet(); barrier.stop(); AssetValidation.Valid(31_234) }
        val gatewayA = DownloadTestGateway().apply { denyAcquisition() }
        val gatewayB = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gatewayA, validator)
        val b = coordinator(second, gatewayB, validator)
        coroutineScope {
            val starting = async { a.start() }
            barrier.entered.await()
            val claim = first.flush().getOrThrow().downloads.getValue(seeded.record.id)
            assertNotNull(claim.leaseOwner)
            assertNotEquals("foreign-writer", claim.leaseOwner)
            b.start()
            b.close()
            assertEquals(claim.leaseOwner, first.flush().getOrThrow().downloads[claim.id]?.leaseOwner)
            assertEquals(1, validations.get())
            barrier.release()
            starting.await()
        }
        val durable = eventually(first) { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        assertEquals(DownloadAttemptStatus.SUCCEEDED, durable.downloadAttempts[seeded.attempt!!.id]?.status)
        assertFalse(seeded.record.id in durable.downloadFinalizations)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(seeded.intent!!.finalPath)))
        assertFalse(Files.exists(Path.of(seeded.intent.stagingPath)))
        assertEquals(1, validations.get())
        gatewayA.assertUnused(); gatewayB.assertUnused()
    }

    @Test fun removeDuringNoncancellableValidationCannotPublishOrResurrect() = downloadCase {
        val first = store()
        val second = store()
        val seeded = seedFinalization(first)
        val validation = barrier()
        val validator = AssetValidator {
            withContext(NonCancellable) { validation.stop() }
            AssetValidation.Valid(31_234)
        }
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gateway, validator)
        val b = coordinator(second, gateway)
        coroutineScope {
            val starting = async { a.start() }
            validation.entered.await()
            b.start()
            b.remove(seeded.record.id)
            b.resume(seeded.record.id)
            val cancelling = second.flush().getOrThrow().downloads.getValue(seeded.record.id)
            assertTrue(cancelling.cleanupPending && cancelling.removeWhenClean)
            assertTrue(Files.exists(Path.of(seeded.intent!!.stagingPath)), "A still owns validation's generation lock")
            assertFalse(Files.exists(Path.of(seeded.intent.finalPath)))
            validation.release()
            starting.await()
        }
        eventually(second) { seeded.record.id !in it.downloads }
        assertFalse(Files.exists(Path.of(seeded.intent!!.stagingPath)))
        assertFalse(Files.exists(Path.of(seeded.intent.finalPath)))
        gateway.assertUnused()
    }

    @Test fun changedOwnerAttemptAndIntentRejectStaleRecoveryAndFailureUpdates() = downloadCase {
        val first = store()
        val second = store()
        val old = seedFinalization(first)
        val validation = barrier()
        val a = coordinator(first, DownloadTestGateway().apply { denyAcquisition() }, AssetValidator {
            withContext(NonCancellable) { validation.stop() }
            AssetValidation.Invalid(AppFailure(FailureCode.CORRUPT_MEDIA, "old_validator_rejected"))
        })
        coroutineScope {
            val starting = async { a.start() }
            validation.entered.await()
            val path = files.prepareStaging(old.item.id, "new-intent")
            Files.write(Path.of(path.toString()), authoredBytes)
            val attempt = old.attempt!!.copy(id = "attempt-new", number = 2, stagingPath = path.toString(), validatorGeneration = 2)
            val intent = old.intent!!.copy(attemptId = attempt.id, stagingPath = path.toString(), validatorGeneration = 2,
                finalPath = files.finalPath(old.item.id, 2, "audio/mpeg", attempt.id).toString())
            val winner = old.record.copy(leaseOwner = "new-owner", leaseExpiresEpochMs = clock.epochMs() + 30_000,
                activeAttemptId = attempt.id, stagingPath = intent.stagingPath, attemptCount = 2, validatorGeneration = 2)
            second.update { it.copy(downloads = it.downloads + (winner.id to winner),
                downloadAttempts = it.downloadAttempts + (attempt.id to attempt),
                downloadFinalizations = it.downloadFinalizations + (winner.id to intent)) }.getOrThrow()
            validation.release()
            starting.await()
            a.close()
            val actual = second.flush().getOrThrow()
            assertEquals(winner, actual.downloads[winner.id])
            assertEquals(intent, actual.downloadFinalizations[winner.id])
            assertEquals(attempt, actual.downloadAttempts[attempt.id])
            assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(intent.stagingPath)))
            assertFalse(Files.exists(Path.of(intent.finalPath)))
            assertTrue(Files.exists(Path.of(old.intent.stagingPath)))
        }
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

    @Test fun staleEnqueueResumeAndPauseCannotDemoteOtherConnectionsCompletedAsset() = downloadCase {
        val first = store(); val second = store()
        second.initialize().getOrThrow() // deliberately initialized before the winning transaction
        val seeded = seedComplete(first)
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val b = coordinator(second, gateway)
        assertEquals(seeded.record.id, b.enqueue(seeded.item))
        b.resume(seeded.record.id)
        b.pause(seeded.record.id)
        val durable = first.flush().getOrThrow()
        assertEquals(seeded.record, durable.downloads[seeded.record.id])
        assertEquals(1, durable.downloads.size)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(seeded.record.asset!!.uri)))
        gateway.assertUnused()
    }

    @Test fun publishedFailedIntentIsRecoveredAfterForeignLeaseExpiresWithoutRestart() = downloadCase {
        val first = store(); val second = store()
        val seeded = seedFinalization(first, published = true, status = DownloadStatus.FAILED,
            leaseOwner = "foreign-finalizer", leaseUntil = clock.epochMs() + 1_000)
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val b = coordinator(second, gateway)
        b.start()
        assertEquals(seeded.record, second.flush().getOrThrow().downloads[seeded.record.id])
        clock.advance(1_100)
        val completed = eventually(second) { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        assertEquals(seeded.intent!!.finalPath, completed.downloads[seeded.record.id]?.asset?.uri)
        assertFalse(seeded.record.id in completed.downloadFinalizations)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(seeded.intent.finalPath)))
        gateway.assertUnused()
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
