package dev.veyra.downloads

import dev.veyra.data.HeartbeatPhysicalDownload
import dev.veyra.data.PhysicalDownloadCommit
import dev.veyra.model.AppFailure
import dev.veyra.model.DownloadStatus
import dev.veyra.model.FailureCode
import dev.veyra.model.PhysicalDownloadFence
import dev.veyra.model.VeyraException
import dev.veyra.source.execution.AcquisitionTransferProgress
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadFailureDiagnosticsIntegrationTest {
    @Test fun authoredPreparationStateGuardKeepsItsFailureAndRecordsTheLastCommittedOwner() = acquisitionDownloadRoomCase {
        val owner = owner()
        val id = enqueue(owner, acceptNeutral(owner))
        owner.gateway.prepareResult = { _, _ ->
            throw IllegalStateException("Authored private failure https://private.example/audio?token=do-not-log")
        }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val failed = awaitJob(owner, id) {
            it.job.status == DownloadStatus.FAILED && it.job.leaseOwner == null && it.job.activeAttemptId == null
        }
        assertEquals(AppFailure(FailureCode.INVALID_STATE, "download_executor_invalid_state"), failed.job.failure)
        val diagnostic = owner.coordinator.diagnosticEvents.value.single {
            it.stage == DownloadDiagnosticStage.SOURCE_PREPARATION && it.outcome == DownloadDiagnosticOutcome.FAILURE
        }
        assertEquals(DownloadDiagnosticErrorKind.STATE_GUARD, diagnostic.failure!!.kind)
        assertEquals(DownloadDiagnosticReason.EXECUTOR_GUARD, diagnostic.failure.reason)
        assertEquals(failed.attempts.single().ownershipGeneration, diagnostic.expectedOwnershipGeneration)
        assertEquals(failed.attempts.single().number, diagnostic.expectedAttemptNumber)
        val committed = assertNotNull(diagnostic.lastCommitted)
        assertEquals(DownloadStatus.RESOLVING, committed.status)
        assertTrue(committed.ownerMatches && committed.generationMatches && committed.attemptMatches)
        assertFalse(committed.representationPresent)
        assertNotNull(diagnostic.runSequence)
        assertNull(failed.asset)
        assertNull(failed.finalization)
        assertEquals(1, owner.repository.finishCalls.size)
        assertTrue(owner.gateway.transfers.isEmpty())
        assertTrue(owner.validations.isEmpty())
        assertFalse(owner.coordinator.diagnosticEvents.value.toString().contains("do-not-log"))
    }

    @Test fun sourceExpiryStaysActionableThroughTransferFailureAndExactlyOneAttemptSettlement() = acquisitionDownloadRoomCase {
        val owner = owner()
        val id = enqueue(owner, acceptNeutral(owner))
        val expired = AppFailure(FailureCode.RECOVERY_REQUIRED, "YOUTUBE_SOURCE_EXPIRED")
        owner.gateway.transfer = { _, _, _ -> throw VeyraException(expired) }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val failed = awaitJob(owner, id) {
            it.job.status == DownloadStatus.FAILED && it.job.leaseOwner == null && it.job.activeAttemptId == null
        }
        assertEquals(expired, failed.job.failure)
        assertEquals(expired, failed.attempts.single().failure)
        val diagnostic = owner.coordinator.diagnosticEvents.value.single {
            it.stage == DownloadDiagnosticStage.SOURCE_TRANSFER && it.outcome == DownloadDiagnosticOutcome.FAILURE
        }
        assertEquals(FailureCode.RECOVERY_REQUIRED, diagnostic.failure!!.code)
        assertEquals(DownloadDiagnosticReason.SOURCE_EXPIRED, diagnostic.failure.reason)
        assertEquals(DownloadDiagnosticErrorKind.TYPED, diagnostic.failure.kind)
        assertTrue(assertNotNull(diagnostic.lastCommitted).representationPresent)
        assertNull(failed.asset)
        assertNull(failed.finalization)
        assertEquals(1, owner.repository.finishCalls.size)
        assertEquals(1, owner.gateway.preparations.size)
        assertEquals(1, owner.gateway.transfers.size)
        assertTrue(owner.validations.isEmpty())
        awaitCondition { owner.coordinator.diagnosticEvents.value.any {
            it.stage == DownloadDiagnosticStage.ATTEMPT_SETTLEMENT && it.runSequence == diagnostic.runSequence
        } }
        val settlement = owner.coordinator.diagnosticEvents.value.single {
            it.stage == DownloadDiagnosticStage.ATTEMPT_SETTLEMENT && it.runSequence == diagnostic.runSequence
        }
        assertEquals(DownloadDiagnosticOutcome.SETTLED, settlement.outcome)
        assertEquals(FailureCode.RECOVERY_REQUIRED, settlement.failure!!.code)
        assertEquals(DownloadStatus.FAILED, settlement.cachedObservation?.status)
    }

    @Test fun invalidTransferCountsRemainIntegrityFailuresAndNeverReachNativeValidationOrPublication() = acquisitionDownloadRoomCase {
        val owner = owner()
        val id = enqueue(owner, acceptNeutral(owner))
        owner.gateway.transfer = { _, _, progress ->
            progress(AcquisitionTransferProgress(-1, 100))
            error("A rejected transfer must not continue")
        }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val failed = awaitJob(owner, id) {
            it.job.status == DownloadStatus.FAILED && it.job.leaseOwner == null && it.job.activeAttemptId == null
        }
        assertEquals(AppFailure(FailureCode.CORRUPT_MEDIA, "invalid_transfer_count"), failed.job.failure)
        val diagnostic = owner.coordinator.diagnosticEvents.value.single {
            it.stage == DownloadDiagnosticStage.SOURCE_TRANSFER && it.outcome == DownloadDiagnosticOutcome.FAILURE
        }
        assertEquals(FailureCode.CORRUPT_MEDIA, diagnostic.failure!!.code)
        assertEquals(DownloadDiagnosticReason.TRANSFER_INTEGRITY, diagnostic.failure.reason)
        assertEquals(1, owner.repository.finishCalls.size)
        assertTrue(owner.validations.isEmpty())
        assertNull(failed.asset)
        assertNull(failed.finalization)
    }

    @Test fun cancellationIsNotConvertedIntoAnInvalidStateFailure() = physicalRuntimeCase {
        gateway.prepareResult = { _, _ -> throw CancellationException("Authored cancellation must propagate") }
        val run = start()
        joined(run)
        assertEquals(DownloadStatus.QUEUED, currentJob().status)
        assertNull(currentJob().failure)
        assertFalse(host.diagnostics.entries.value.any { it.outcome == DownloadDiagnosticOutcome.FAILURE })
        assertTrue(runtime.epochs.allLocalOwnersConsumed())
        assertTrue(gateway.transfers.isEmpty())
        assertTrue(validated.isEmpty())
    }

    @Test fun progressResetStillRejectsAForeignLeaseBeforeChangingTheOriginalAttempt() = acquisitionDownloadRoomCase {
        val owner = owner()
        val id = enqueue(owner, acceptNeutral(owner))
        val transfer = owner.gateway.transfer
        owner.gateway.transfer = { prepared, path, progress ->
            val total = PHYSICAL_BYTES.size.toLong()
            progress(AcquisitionTransferProgress(total / 2, total))
            val before = join(owner, id)
            val attempt = assertNotNull(before.activeAttempt)
            val foreign = PhysicalDownloadFence(id, before.job.producer, before.job.ownershipGeneration,
                "authored-foreign-owner", attempt.id, attempt.number)
            assertIs<PhysicalDownloadCommit.Stale>(owner.repository.heartbeatPhysicalDownload(HeartbeatPhysicalDownload(
                foreign, clock.epochMs(), 5_000, 0, total, allowProgressReset = true)))
            val after = join(owner, id)
            assertEquals(total / 2, after.job.bytesDownloaded)
            assertEquals(before.job.ownershipGeneration, after.job.ownershipGeneration)
            assertEquals(before.job.leaseOwner, after.job.leaseOwner)
            assertEquals(before.job.activeAttemptId, after.job.activeAttemptId)
            assertEquals(before.job.representationId, after.job.representationId)
            transfer(prepared, path, progress)
        }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val complete = awaitJob(owner, id) { it.job.status == DownloadStatus.COMPLETE && it.job.leaseOwner == null }
        assertNull(complete.job.failure)
        assertEquals(1, complete.attempts.size)
        assertEquals(1, owner.validations.size)
        assertNotNull(complete.asset?.sha256)
        assertFalse(owner.coordinator.diagnosticEvents.value.any { it.outcome == DownloadDiagnosticOutcome.FAILURE })
    }
}
