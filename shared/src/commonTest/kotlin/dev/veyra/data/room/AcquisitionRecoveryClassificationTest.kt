package dev.veyra.data.room

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import kotlin.test.*

/** Pure transaction classifications, not physical cleanup/source execution/restart evidence. */
class AcquisitionRecoveryClassificationTest {
    @Test fun committedSingleWhoseAttachmentLostAPauseRaceReturnsToPendingLocalAttachmentOnResume() {
        val fixture = AcquisitionCommandFixture()
        val request = authoredRequest()
        val batch = fixture.catalogBatch(request)
        val paused = fixture.project { it.applyBatchIntent(UpdateBatchClaims(batch.id, 0, DownloadBatchIntent.PAUSED, true, 123_001)) }
        val record = fixture.accept(request, attachments = listOf(OccurrenceAttachmentExpectation(batch.members.single().occurrenceId,
            request.id, null, null, batch.id, 0)))
        val outcome = assertIs<AcquisitionOutcome.Single>(record.outcome)
        assertNull(fixture.graph.state().occurrences.getValue(batch.members.single().occurrenceId).bindingId)
        val resumed = fixture.project { it.applyBatchIntent(UpdateBatchClaims(batch.id, paused.intentRevision,
            DownloadBatchIntent.ACTIVE, true, 123_100)) }
        assertEquals(DownloadBatchMemberState.PENDING_SELECTION, resumed.members.single().state)
        assertTrue(fixture.project { it.applyAttachOccurrence(AttachAcquisitionOccurrence(OccurrenceAttachmentExpectation(
            resumed.members.single().occurrenceId, request.id, null, null, resumed.id, resumed.intentRevision))) })
        val bound = fixture.graph.state().batches.getValue(batch.id)
        assertEquals(outcome.bindingId, bound.members.single().requiredBindingId)
        assertEquals(DownloadBatchMemberState.WAITING_FOR_JOB, bound.members.single().state)
        assertEquals(1, fixture.graph.state().receipts.size)
        assertEquals(1, fixture.graph.state().requests.size)
    }

    @Test fun historicalOnlyStagingPathReservesCleanupOnceAndAcknowledgedRetainedHistoryDoesNotLoop() {
        val before = AcquisitionCommandFixture()
        val request = authoredRequest()
        val binding = (before.accept(request).outcome as AcquisitionOutcome.Single).bindingId
        val admitted = before.project { it.applyAdmitStandalone(AdmitStandalonePhysicalDownload(request.id, binding,
            "job", DownloadClaimId("claim"), 123_100)) }.projectedValue()
        val job = admitted.physical.job.copy(attemptCount = 1)
        val historical = PhysicalDownloadAttempt("retired-attempt", job.id, job.producer, 1, job.ownershipGeneration,
            status = DownloadAttemptStatus.CANCELLED, startedEpochMs = 123_100, finishedEpochMs = 123_110,
            stagingPath = "/authored/retired-only.part")
        val fixture = AcquisitionCommandFixture(before.graph.withJob(job).withAttempt(historical))
        val claim = admitted.claim
        fixture.project { it.applyStandaloneIntent(UpdateStandaloneDownloadIntent(job.id, job.producer, job.ownershipGeneration,
            claim.id, claim.generation, claim.ownerIntentRevision, DownloadBatchIntent.CANCELLED, false, 123_200)) }
        val reserved = fixture.graph.physicalJoin(job.id)!!
        assertNull(reserved.job.stagingPath)
        assertNull(reserved.asset)
        assertTrue(reserved.job.cleanupPending, "Historical-only directories are not empty cleanup")
        val cleanup = fixture.project { it.applyClaimMaintenance(ClaimPhysicalDownloadMaintenance(job.id, job.producer,
            reserved.job.ownershipGeneration, PhysicalDownloadMaintenanceKind.CLEANUP, "authored-cleanup-owner", 123_210, 1_000)) }.projectedValue()
        assertEquals(setOf(historical.stagingPath), cleanup.physical.cleanupReservation!!.stagingPaths)
        val finished = fixture.project { it.applyFinishMaintenance(FinishPhysicalDownloadMaintenance(cleanup.fence,
            PhysicalDownloadMaintenanceKind.CLEANUP, cleanup.physical.claims, true, null, 123_220)) }.projectedValue()
        assertFalse(finished.job.cleanupPending)
        assertTrue(finished.job.removeWhenClean)
        assertEquals(historical, finished.attempts.single())
        val reconciled = fixture.graph.reconcileJob(finished, 123_230).physicalJoin(job.id)!!
        assertFalse(reconciled.job.cleanupPending, "Retained historical path is not a perpetual deletion request")
        assertEquals(historical, reconciled.attempts.single())
    }
}
