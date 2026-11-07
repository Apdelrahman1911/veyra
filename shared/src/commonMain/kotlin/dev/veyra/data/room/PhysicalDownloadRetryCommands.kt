package dev.veyra.data.room

import dev.veyra.acquisition.afterExplicitMemberRetry
import dev.veyra.acquisition.isExplicitMemberRetryCandidate
import dev.veyra.data.*
import dev.veyra.model.*

/**
 * Rejoin every durable identity in the canonical transaction. Selection leases retained on a
 * COMMITTED request are provenance, not physical download leases; no first result is queried.
 * This is generic across genuine catalogue and observed-source batch members. A batch position
 * must NOT be substituted for its separate scopeMemberPosition when an observed scope has holes.
 */
internal fun AcquisitionGraph.applyRetryBatchPhysical(
    command: RetryBatchPhysicalDownload,
): AcquisitionMutation<PhysicalDownloadCommit<BatchPhysicalDownloadAdmission>> {
    val (batch, member) = batchMember(command.member) ?: return physicalStale()
    val state = state()
    val claim = state.claims[command.expectedClaim.id] ?: return physicalStale()
    val join = physicalJoin(claim.physicalJobId) ?: return physicalStale()
    val job = join.job
    if (claim != command.expectedClaim || job != command.expectedJob || member.claimId != claim.id ||
        batch.intent != DownloadBatchIntent.ACTIVE || claim.intent != DownloadBatchIntent.ACTIVE ||
        claim.owner != DownloadClaimOwner.BatchMember(batch.id, member.position) ||
        claim.ownerIntentRevision != batch.intentRevision || claim.requestedBindingId != member.requiredBindingId ||
        !isExplicitMemberRetryCandidate(job) || join.asset != null || join.finalization != null ||
        join.activeAttempt != null || join.cleanupReservation != null || join.attempts.any {
            it.finishedEpochMs == null || it.status in setOf(DownloadAttemptStatus.RUNNING,
                DownloadAttemptStatus.VALIDATING, DownloadAttemptStatus.FINALIZING)
        }) return physicalStale()
    val record = state.requests[member.requestId] ?: return physicalStale()
    val outcome = record.outcome as? AcquisitionOutcome.Single ?: return physicalStale()
    if (record.state != AcquisitionJobState.COMMITTED || record.request.id != member.requestId ||
        outcome.requestId != member.requestId || outcome.bindingId != command.member.requestedBindingId) return physicalStale()
    val context = state.executionContext(command.member.requestedBindingId)
    val scope = state.scopes[batch.scopeId] ?: return physicalStale()
    val included = scope.members.singleOrNull { it.position == member.scopeMemberPosition } ?: return physicalStale()
    val occurrence = state.occurrences[member.occurrenceId] ?: return physicalStale()
    if (context.request != record.request || context.receipt.id != outcome.receiptId ||
        job.producer.subjectId != context.subject.id || job.producer.recordingId != context.recording.id ||
        scope.id != batch.scopeId || scope.controlsMode != occurrence.controlsMode ||
        included.disposition != AdmissionDisposition.INCLUDED || included.requestId != member.requestId ||
        included.snapshotMember != occurrence.snapshotMember ||
        occurrence.scopeMember != AdmissionScopeMemberRef(scope.id, member.scopeMemberPosition) ||
        occurrence.requestId != member.requestId || occurrence.subjectId != context.subject.id ||
        occurrence.bindingId != context.binding.id || occurrence.selectionState != OccurrenceSelectionState.BOUND ||
        claim.generation == Long.MAX_VALUE ||
        command.nowEpochMs < maxOf(job.updatedEpochMs, claim.updatedEpochMs, batch.updatedEpochMs, context.receipt.observedEpochMs)) {
        return physicalStale()
    }
    val next = withJob(job.afterExplicitMemberRetry(command.nowEpochMs))
        .withClaim(claim.copy(generation = claim.generation + 1, updatedEpochMs = command.nowEpochMs))
        // Only presentation for owners of THIS shared physical job can change. No unrelated
        // job/member/claim, parent intent or immutable request/receipt/attempt is rewritten.
        .refreshBatchDownloadStates(job.id, command.nowEpochMs)
    return next.physicalCommitted { actual ->
        BatchPhysicalDownloadAdmission(command.member, actual.state().claims.getValue(claim.id),
            actual.state().executionContext(command.member.requestedBindingId), requireNotNull(actual.physicalJoin(job.id)))
    }
}
