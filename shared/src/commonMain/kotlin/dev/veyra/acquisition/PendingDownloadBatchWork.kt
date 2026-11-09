package dev.veyra.acquisition

import dev.veyra.data.AcquisitionRepositoryState
import dev.veyra.model.*

/** Exact durable member intent, not a lease, execution capability or permission to query again. */
internal data class PendingDownloadBatchKey(
    val batchId: DownloadBatchId,
    val memberPosition: Int,
    val intentRevision: Long,
    val occurrenceId: ExecutionOccurrenceId,
    val requestId: AcquisitionRequestId,
) {
    init { require(memberPosition >= 0 && intentRevision >= 0) }
    override fun toString() = "PendingDownloadBatchKey(identity=redacted)"
}

internal enum class PendingDownloadBatchWorkKind { SELECT_READY, ATTACH_COMMITTED, ADMIT_BOUND }

/**
 * A scheduling hint only. SELECT_READY/ATTACH_COMMITTED call the existing resolveMember ONCE;
 * ADMIT_BOUND still requires the existing executor's full same-Room transactional revalidation.
 * Neither a known receipt nor an admission hint proves a physical download or Ready asset.
 */
internal data class PendingDownloadBatchWork(
    val key: PendingDownloadBatchKey,
    val kind: PendingDownloadBatchWorkKind,
    val bindingId: ExecutionBindingId? = null,
) {
    init { require((bindingId != null) == (kind == PendingDownloadBatchWorkKind.ADMIT_BOUND)) }
    val requiresNetwork: Boolean get() = kind == PendingDownloadBatchWorkKind.SELECT_READY
    override fun toString() = "PendingDownloadBatchWork(kind=$kind,identity=redacted)"
}

internal enum class PendingDownloadBatchBlockedReason { IN_PROGRESS, OUTCOME_UNKNOWN, INVALID_STATE }

internal data class PendingDownloadBatchBlocked(
    val key: PendingDownloadBatchKey,
    val reason: PendingDownloadBatchBlockedReason,
)

internal data class PendingDownloadBatchProjection(
    val work: List<PendingDownloadBatchWork>,
    /** Reinspect a live claim once at expiry; expiry is NEVER permission to repeat selection. */
    val nextLocalWakeEpochMs: Long?,
    val blocked: List<PendingDownloadBatchBlocked>,
)

/**
 * Pure projection of ONE detached canonical read. Safe during Room-only cold scheduling: no
 * coordinator, source, native gate, asset validator, coroutine or side effect is invoked here.
 * Ordering is stable by batch admission time/id then occurrence position; duplicate recordings
 * stay distinct members. Projecting multiple hints does not eagerly resolve any of them.
 *
 * The existing DownloadCoordinator alone chooses bounded work, registers its actual Job and
 * gates/joins its lifetime. It must freshly rejoin the key before each operation and suppress
 * retries of unchanged InProgress/OutcomeUnknown/Stale results. Do not build another loop here.
 */
internal fun projectPendingDownloadBatchWork(
    state: AcquisitionRepositoryState,
    nowEpochMs: Long,
): PendingDownloadBatchProjection {
    require(nowEpochMs >= 0)
    val work = mutableListOf<PendingDownloadBatchWork>()
    val blocked = mutableListOf<PendingDownloadBatchBlocked>()
    var nextWake: Long? = null
    val batches = state.batches.entries.sortedWith(
        compareBy<Map.Entry<DownloadBatchId, DownloadBatch>> { it.value.createdEpochMs }.thenBy { it.key.value },
    )
    for ((id, batch) in batches) {
        if (batch.intent != DownloadBatchIntent.ACTIVE) continue
        val rows = PendingBatchRows(id, batch, state)
        for (member in batch.members.sortedBy { it.position }) {
            val key = PendingDownloadBatchKey(id, member.position, batch.intentRevision, member.occurrenceId, member.requestId)
            val decision = rows.inspect(key, member, nowEpochMs)
            decision.work?.let(work::add)
            decision.blocked?.let(blocked::add)
            decision.wake?.let { at -> nextWake = nextWake?.let { minOf(it, at) } ?: at }
        }
    }
    return PendingDownloadBatchProjection(work.toList(), nextWake, blocked.distinct())
}

/** Old keys cannot cross pause/resume, removal, a replaced slot or a different captured request. */
internal fun rejoinPendingDownloadBatchWork(
    state: AcquisitionRepositoryState,
    key: PendingDownloadBatchKey,
    nowEpochMs: Long,
): PendingDownloadBatchWork? {
    require(nowEpochMs >= 0)
    val batch = state.batches[key.batchId] ?: return null
    if (batch.id != key.batchId || batch.intent != DownloadBatchIntent.ACTIVE || batch.intentRevision != key.intentRevision) return null
    val rows = PendingBatchRows(key.batchId, batch, state)
    val member = rows.membersByPosition[key.memberPosition]?.singleOrNull() ?: return null
    return rows.inspect(key, member, nowEpochMs).work
}

/**
 * A resolve return alone is not admission authority. Only its accepted exact attachment plus a
 * fresh matching canonical Single/occurrence/member join yields an ADMIT_BOUND hint. The caller
 * must still enter the existing executor CAS; this function never inspects/prepares/transfers.
 * InProgress, OutcomeUnknown, Stale, rejected attachments and child collections yield no hint.
 */
internal fun admissionAfterResolution(
    state: AcquisitionRepositoryState,
    key: PendingDownloadBatchKey,
    resolution: AcquisitionResolution,
    nowEpochMs: Long,
): PendingDownloadBatchWork? {
    require(nowEpochMs >= 0)
    val accepted = resolution as? AcquisitionResolution.Accepted ?: return null
    if (accepted.record.request.id != key.requestId || key.occurrenceId !in accepted.attachedOccurrences ||
        key.occurrenceId in accepted.rejectedAttachments || state.requests[key.requestId] != accepted.record) return null
    val outcome = accepted.record.outcome as? AcquisitionOutcome.Single ?: return null
    val current = rejoinPendingDownloadBatchWork(state, key, nowEpochMs) ?: return null
    return current.takeIf { it.kind == PendingDownloadBatchWorkKind.ADMIT_BOUND && it.bindingId == outcome.bindingId }
}

private data class PendingBatchDecision(
    val work: PendingDownloadBatchWork? = null,
    val blocked: PendingDownloadBatchBlocked? = null,
    val wake: Long? = null,
)

/** Index once per batch: projection does not repeatedly scan all members for every hint. */
private class PendingBatchRows(
    private val id: DownloadBatchId,
    private val batch: DownloadBatch,
    private val state: AcquisitionRepositoryState,
) {
    val membersByPosition = batch.members.groupBy { it.position }
    private val occurrenceCounts = batch.members.groupingBy { it.occurrenceId }.eachCount()
    private val scope = state.scopes[batch.scopeId]
    private val scopeMembers = scope?.members?.groupBy { it.position }.orEmpty()

    fun inspect(key: PendingDownloadBatchKey, member: DownloadBatchMember, now: Long): PendingBatchDecision {
        fun blocked(reason: PendingDownloadBatchBlockedReason, wake: Long? = null) = PendingBatchDecision(
            blocked = PendingDownloadBatchBlocked(key, reason), wake = wake,
        )
        fun invalid() = blocked(PendingDownloadBatchBlockedReason.INVALID_STATE)
        fun work(kind: PendingDownloadBatchWorkKind, bindingId: ExecutionBindingId? = null) =
            PendingBatchDecision(work = PendingDownloadBatchWork(key, kind, bindingId))

        if (batch.id != id || key.batchId != id || batch.intent != DownloadBatchIntent.ACTIVE ||
            batch.intentRevision != key.intentRevision || member.position != key.memberPosition ||
            member.occurrenceId != key.occurrenceId || member.requestId != key.requestId ||
            membersByPosition[member.position]?.singleOrNull() != member || occurrenceCounts[member.occurrenceId] != 1) return invalid()
        // Already attached claims belong to the existing physical scheduler/reconciler, not a
        // new selection/admission job. COMPLETE is not reinterpreted as a current-process proof.
        if (member.claimId != null) return PendingBatchDecision()
        val occurrence = state.occurrences[key.occurrenceId] ?: return invalid()
        val record = state.requests[key.requestId] ?: return invalid()
        val currentScope = scope ?: return invalid()
        val scopeMember = scopeMembers[member.scopeMemberPosition]?.singleOrNull() ?: return invalid()
        if (occurrence.id != key.occurrenceId || occurrence.origin != AcquisitionOrigin.TYPED ||
            occurrence.requestId != key.requestId || record.request.id != key.requestId ||
            currentScope.id != batch.scopeId || currentScope.controlsMode != occurrence.controlsMode ||
            occurrence.scopeMember != AdmissionScopeMemberRef(batch.scopeId, member.scopeMemberPosition) ||
            scopeMember.disposition != AdmissionDisposition.INCLUDED || scopeMember.requestId != key.requestId ||
            scopeMember.snapshotMember != occurrence.snapshotMember) return invalid()

        // Canonical request/occurrence evidence always wins over a stale projected member label.
        if (record.state == AcquisitionJobState.OUTCOME_UNKNOWN || occurrence.selectionState == OccurrenceSelectionState.OUTCOME_UNKNOWN)
            return blocked(PendingDownloadBatchBlockedReason.OUTCOME_UNKNOWN)
        return when (occurrence.selectionState) {
            OccurrenceSelectionState.PENDING -> {
                if (member.requiredBindingId != null) return invalid()
                when (record.state) {
                    AcquisitionJobState.READY -> if (record.lease == null && record.outcome == null)
                        work(PendingDownloadBatchWorkKind.SELECT_READY) else invalid()
                    AcquisitionJobState.COMMITTED -> if (record.outcome?.requestId == key.requestId)
                        work(PendingDownloadBatchWorkKind.ATTACH_COMMITTED) else invalid()
                    AcquisitionJobState.CLAIMED -> {
                        val lease = record.lease ?: return invalid()
                        if (lease.requestId != key.requestId || record.outcome != null) return invalid()
                        if (lease.expiresEpochMs > now) blocked(PendingDownloadBatchBlockedReason.IN_PROGRESS, lease.expiresEpochMs)
                        else blocked(PendingDownloadBatchBlockedReason.OUTCOME_UNKNOWN)
                    }
                    AcquisitionJobState.OUTCOME_UNKNOWN -> blocked(PendingDownloadBatchBlockedReason.OUTCOME_UNKNOWN)
                }
            }
            OccurrenceSelectionState.BOUND -> {
                val outcome = record.outcome as? AcquisitionOutcome.Single ?: return invalid()
                val binding = state.bindings[outcome.bindingId] ?: return invalid()
                if (record.state != AcquisitionJobState.COMMITTED || outcome.requestId != key.requestId ||
                    member.requiredBindingId != outcome.bindingId || occurrence.bindingId != outcome.bindingId ||
                    binding.id != outcome.bindingId || binding.origin != ExecutionBindingOrigin.TYPED ||
                    binding.subjectId != occurrence.subjectId || binding.firstReceiptId != outcome.receiptId) invalid()
                else work(PendingDownloadBatchWorkKind.ADMIT_BOUND, outcome.bindingId)
            }
            OccurrenceSelectionState.UNPLAYABLE, OccurrenceSelectionState.NEEDS_COLLECTION_ACTION -> PendingBatchDecision()
            OccurrenceSelectionState.OUTCOME_UNKNOWN -> blocked(PendingDownloadBatchBlockedReason.OUTCOME_UNKNOWN)
            OccurrenceSelectionState.LEGACY_UNBOUND -> invalid()
        }
    }
}
