package dev.veyra.data

import dev.veyra.model.*
import kotlinx.coroutines.flow.StateFlow

/** Detached consistent projection, never a serialized/blob database or a second persistence owner. */
data class AcquisitionRepositoryState(
    val libraryRevision: Long,
    val requests: Map<AcquisitionRequestId, AcquisitionRecord> = emptyMap(),
    val receipts: Map<FirstResultReceiptId, FirstResultReceipt> = emptyMap(),
    val recordings: Map<SourceRecordingId, SourceRecording> = emptyMap(),
    val subjects: Map<ExecutableSubjectId, ExecutableSubject> = emptyMap(),
    val bindings: Map<ExecutionBindingId, ExecutionBinding> = emptyMap(),
    val runs: Map<CollectionObservationId, CollectionRun> = emptyMap(),
    val snapshots: Map<CollectionSnapshotId, CollectionSnapshot> = emptyMap(),
    val scopes: Map<AdmissionScopeId, AdmissionScope> = emptyMap(),
    val occurrences: Map<ExecutionOccurrenceId, ExecutionOccurrence> = emptyMap(),
    val queues: Map<QueueContext, ExecutionQueue> = emptyMap(),
    val batches: Map<DownloadBatchId, DownloadBatch> = emptyMap(),
    val claims: Map<DownloadClaimId, DownloadClaim> = emptyMap(),
    val assetUses: Set<AssetUse> = emptySet(),
    val physicalDownloads: Map<String, PhysicalDownloadJob> = emptyMap(),
    val physicalAttempts: Map<String, PhysicalDownloadAttempt> = emptyMap(),
    val physicalFinalizations: Map<String, PhysicalDownloadFinalization> = emptyMap(),
    val materializations: Map<MaterializationAssetId, MaterializationAsset> = emptyMap(),
    val materializationReceipts: Map<MaterializationAssetId, MaterializationReceipt> = emptyMap(),
)

data class CaptureAcquisition(val request: AcquisitionRequest, val expectedCatalogRevision: Long? = null)
data class ClaimAcquisition(
    val requestId: AcquisitionRequestId,
    val token: AcquisitionAttemptToken,
    val owner: String,
    val nowEpochMs: Long,
    val leaseDurationMs: Long = 60_000,
) {
    init { require(nowEpochMs >= 0 && leaseDurationMs in 1_000..600_000 && owner.isNotBlank()) }
}
sealed interface AcquisitionClaimResult {
    data class Claimed(val request: AcquisitionRequest, val lease: AcquisitionLease) : AcquisitionClaimResult
    data class AlreadyCommitted(val record: AcquisitionRecord) : AcquisitionClaimResult
    data class Busy(val record: AcquisitionRecord) : AcquisitionClaimResult
    data class OutcomeUnknown(val record: AcquisitionRecord) : AcquisitionClaimResult
}

/** IDs are proposed, never source/provider authority. Existing same-C rows are reused atomically. */
data class ExecutionBindingAllocation(
    val bindingId: ExecutionBindingId,
    val newRecordingId: SourceRecordingId,
    val newNeutralSubjectId: ExecutableSubjectId,
)

/** Removed/stale queue slots may retain history but cannot receive a newly selected binding. */
data class OccurrenceAttachmentExpectation(
    val occurrenceId: ExecutionOccurrenceId,
    val requestId: AcquisitionRequestId,
    val context: QueueContext?,
    val expectedQueueRevision: Long?,
    val batchId: DownloadBatchId? = null,
    val expectedBatchIntentRevision: Long? = null,
)
data class CommitAcquisition(
    val lease: AcquisitionLease,
    val result: SourceAcquisitionResult,
    val receiptId: FirstResultReceiptId?,
    val bindingAllocation: ExecutionBindingAllocation?,
    val failureStage: AcquisitionFailureStage,
    val attachments: List<OccurrenceAttachmentExpectation>,
    val nowEpochMs: Long,
)
sealed interface AcquisitionCommitResult {
    data class Committed(
        val record: AcquisitionRecord,
        val attachedOccurrences: Set<ExecutionOccurrenceId>,
        val rejectedAttachments: Set<ExecutionOccurrenceId>,
    ) : AcquisitionCommitResult
    data class Stale(val record: AcquisitionRecord, val historicalReceiptRetained: Boolean) : AcquisitionCommitResult
}
data class MarkAcquisitionOutcomeUnknown(val lease: AcquisitionLease, val nowEpochMs: Long)

data class CollectionSource(val request: AcquisitionRequest, val receipt: FirstResultReceipt.Observed, val outcome: AcquisitionOutcome.Collection)
data class CollectionFrontier(val nextPageIndex: Int, val nextOrdinal: Int, val knownHoleCount: Int)
enum class CollectionRunStopReason { CURSOR_LOST, CURSOR_EXPIRED, CANCELLED, RETRY_EXHAUSTED }
data class CollectionRun(
    val id: CollectionObservationId,
    val parentRequestId: AcquisitionRequestId,
    val parentReceiptId: FirstResultReceiptId,
    val identity: CollectionIdentity,
    val scope: CollectionScope,
    val observation: CollectionObservation?,
    val frontier: CollectionFrontier,
    val extent: CollectionExtent,
    val retryableFailuresAtFrontier: Int,
    val stoppedReason: CollectionRunStopReason?,
    val startedEpochMs: Long,
    val updatedEpochMs: Long,
)
data class AppendCollectionPage(
    val parentReceiptId: FirstResultReceiptId,
    val data: CollectionPageData,
    val expectedFrontier: CollectionFrontier?,
    val failureAttemptId: String?,
    val receivedEpochMs: Long,
)
enum class CollectionAppendDisposition { COMMITTED_DELTA, RECORDED_FAILURE, IDENTICAL_REPLAY, STALE_FRONTIER }
data class CollectionAppendResult(val disposition: CollectionAppendDisposition, val run: CollectionRun)
data class SealCollectionSnapshot(
    val id: CollectionSnapshotId,
    val observationId: CollectionObservationId,
    val expectedFrontier: CollectionFrontier,
    val sealedEpochMs: Long,
    /** Null takes an immutable prefix cut while a live cursor may continue. */
    val stopRun: CollectionRunStopReason? = null,
)

enum class QueueAdmissionOperation { REPLACE, APPEND, INSERT_NEXT }
data class QueueAcquisitionAdmission(
    val context: QueueContext,
    val expectedQueueRevision: Long,
    val expectedCatalogRevision: Long?,
    val operation: QueueAdmissionOperation,
    val requests: List<AcquisitionRequest>,
    val scope: AdmissionScope?,
    val occurrences: List<ExecutionOccurrence>,
    val outgoingCheckpoint: OccurrenceCheckpoint?,
)
data class BatchAcquisitionAdmission(
    val expectedCatalogRevision: Long?,
    val requests: List<AcquisitionRequest>,
    val scope: AdmissionScope,
    val occurrences: List<ExecutionOccurrence>,
    val batch: DownloadBatch,
)
data class AttachAcquisitionOccurrence(val expected: OccurrenceAttachmentExpectation)

/** Fences only this batch's claims; physical stop/delete remains the existing executor's ACKed job. */
data class UpdateBatchClaims(
    val batchId: DownloadBatchId,
    val expectedIntentRevision: Long,
    val intent: DownloadBatchIntent,
    val retainCompletedAssets: Boolean,
    val nowEpochMs: Long,
)
data class AttachBatchDownloadClaim(
    val batchId: DownloadBatchId,
    val memberPosition: Int,
    val expectedIntentRevision: Long,
    val requiredBindingId: ExecutionBindingId,
    val claim: DownloadClaim,
)

/**
 * Canonical source-only transaction port. Architecture will implement this over the SAME
 * normalized Room owner/IMMEDIATE transaction and intended-graph readback after the measured
 * schema2 gate. No production in-memory fallback, arbitrary transform, SQL or source I/O here.
 *
 * Every returned/state value is detached. Implementations revalidate full captured metadata,
 * exact IDs/first ordinal/raw locator/scope/observed-member eligibility and all foreign keys
 * inside the transaction; a previously read state is never sufficient commit authority.
 * VeyraException reports typed admission/storage failures; cancellation is never an empty result.
 */
interface AcquisitionRepository : LibraryTransactionOwned {
    val state: StateFlow<AcquisitionRepositoryState>

    /** Room-only restore; no source query, asset validation, artwork or automatic activation. */
    suspend fun initialize(): AcquisitionRepositoryState
    suspend fun request(id: AcquisitionRequestId): AcquisitionRecord?

    /** Exact same ID/value may be idempotent; different value fails. Deep-freeze every relation. */
    suspend fun capture(command: CaptureAcquisition): AcquisitionRecord

    /** Claim READY only. An expired no-receipt claim becomes OUTCOME_UNKNOWN, never READY/requery. */
    suspend fun claim(command: ClaimAcquisition): AcquisitionClaimResult

    /**
     * The same live owner/token may return CLAIMED to READY only while its lease is unexpired,
     * no receipt/outcome exists, and that owner can prove it has NOT entered gateway.acquire.
     * This is a same-process pre-invocation cancellation/offline path, not crash recovery.
     * Never use after source entry, an uncertain exception, restart, expiration or lost ownership;
     * those paths retain/mark OUTCOME_UNKNOWN rather than making another hidden first query.
     * Returns false on a failed CAS without changing another owner's request/lease.
     */
    suspend fun releaseUnsentClaim(lease: AcquisitionLease, nowEpochMs: Long): Boolean

    /**
     * One atomic receipt/outcome/binding commit and pending-only attachment CAS. Receipt ID is
     * present iff the source result has a receipt; allocation only for Single. Same-C subject /
     * recording lookup-or-create is atomic; a parent aggregate Single is neutral, MEDIA is not.
     * A stale queue/batch expectation never changes a different slot; accepted request evidence
     * can remain historical. A stale lease result cannot inspect/prepare/transfer as accepted work.
     */
    suspend fun commit(command: CommitAcquisition): AcquisitionCommitResult
    suspend fun markOutcomeUnknown(command: MarkAcquisitionOutcomeUnknown): AcquisitionRecord

    /** Consistent read of frozen request + accepted collection receipt, not a source operation. */
    suspend fun collectionSource(id: AcquisitionRequestId): CollectionSource

    /**
     * First returned page creates its actual upstream observation-ID run (including initial
     * failure with no verified metadata). Failed attempts never advance the frontier or become
     * empty page commits. Exact replay is idempotent; differing replay/identity/ordinal fails.
     * No continuation object crosses this boundary. At most3 retryable failures per frontier.
     */
    suspend fun appendPage(command: AppendCollectionPage): CollectionAppendResult
    suspend fun sealSnapshot(command: SealCollectionSnapshot): CollectionSnapshot

    /** Capture scope/requests/occurrences + outgoing checkpoint/order/cursor in one transaction. */
    suspend fun admitQueue(command: QueueAcquisitionAdmission): AcquisitionRepositoryState
    suspend fun admitBatch(command: BatchAcquisitionAdmission): DownloadBatch
    suspend fun attachOccurrence(command: AttachAcquisitionOccurrence): Boolean

    /** Claims only: no new transfer engine, unacknowledged stop, file removal or invented Ready. */
    suspend fun updateBatchClaims(command: UpdateBatchClaims): DownloadBatch
    suspend fun attachBatchDownloadClaim(command: AttachBatchDownloadClaim): DownloadClaim
}
