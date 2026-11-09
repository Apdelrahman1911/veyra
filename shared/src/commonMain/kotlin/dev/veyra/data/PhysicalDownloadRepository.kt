package dev.veyra.data

import dev.veyra.model.*
import dev.veyra.source.execution.AcquisitionExecutionContext

/** Opaque process-local identity shared by LibraryStore and every facade on its ONE Room owner. */
class LibraryTransactionOwner internal constructor()

interface LibraryTransactionOwned {
    val transactionOwner: LibraryTransactionOwner
}

/** No caller-supplied claim/job graph is commit authority. These IDs select an exact rejoined row. */
data class BatchDownloadMemberExpectation(
    val batchId: DownloadBatchId,
    val memberPosition: Int,
    val intentRevision: Long,
    val occurrenceId: ExecutionOccurrenceId,
    val requestId: AcquisitionRequestId,
    val requestedBindingId: ExecutionBindingId,
    val previouslyAttachedClaimId: DownloadClaimId?,
)

data class AdmitBatchPhysicalDownload(
    val member: BatchDownloadMemberExpectation,
    val proposedDownloadId: String,
    val proposedClaimId: DownloadClaimId,
    val nowEpochMs: Long,
)

/** Standalone enqueue uses an already accepted Single; this command never performs a search. */
data class AdmitStandalonePhysicalDownload(
    val requestId: AcquisitionRequestId,
    val requiredBindingId: ExecutionBindingId,
    val proposedDownloadId: String,
    val proposedClaimId: DownloadClaimId,
    val nowEpochMs: Long,
)

/** Explicit already-saved schema2 selection. No typed request or fresh title query is invented. */
data class AdmitSchema2StandalonePhysicalDownload(
    val mediaId: String,
    val requiredBindingId: ExecutionBindingId,
    val proposedDownloadId: String,
    val proposedClaimId: DownloadClaimId,
    val nowEpochMs: Long,
)

data class Schema2StandalonePhysicalDownloadAdmission(
    val binding: PlaybackBinding,
    val claim: DownloadClaim,
    val physical: PhysicalDownloadJoin,
)

data class StandalonePhysicalDownloadAdmission(
    val requestedContext: AcquisitionExecutionContext,
    val claim: DownloadClaim,
    val physical: PhysicalDownloadJoin,
)

/** Per-owner intent CAS; it never stops a native writer or ACKs deletion. */
data class UpdateStandaloneDownloadIntent(
    val downloadId: String,
    val expectedProducer: PhysicalDownloadProducer,
    val expectedOwnershipGeneration: Long,
    val claimId: DownloadClaimId,
    val expectedClaimGeneration: Long,
    val expectedOwnerIntentRevision: Long,
    val intent: DownloadBatchIntent,
    val retainCompletedAsset: Boolean,
    val nowEpochMs: Long,
    /** Explicit retry only AFTER existing coverage/activation/foreground gates accepted it. */
    val allowRetryFailure: Boolean = false,
)

data class CommitStandaloneMaterializationUse(
    val downloadId: String,
    val requestedBindingId: ExecutionBindingId,
    val claimId: DownloadClaimId,
    val expectedClaimGeneration: Long,
    val expectedOwnershipGeneration: Long,
    val expectedAsset: MaterializationAsset,
    val compatibilityPolicyVersion: Int,
    val nowEpochMs: Long,
)

/**
 * Ephemeral, single-use proof issued ONLY by the existing executor while it holds a positive
 * zero-local/native/writer/reader drain plus exclusive file-lease barrier. Room never creates
 * one, lease expiry never creates one, and a public caller cannot replace it with a boolean.
 * The issuer must invalidate in finally BEFORE releasing that barrier. This is not persisted.
 */
class PhysicalDownloadDrainReceipt internal constructor(
    private val owner: LibraryTransactionOwner,
    private val downloadId: String,
    private val producer: PhysicalDownloadProducer,
    private val ownershipGeneration: Long,
    claims: List<DownloadClaim>,
    private val barrierHeld: () -> Boolean,
) {
    private val claims = claims.toList()
    private var active = true
    private var consumed = false

    internal fun consume(expectedOwner: LibraryTransactionOwner, job: PhysicalDownloadJob, actualClaims: List<DownloadClaim>) {
        require(active && !consumed && barrierHeld() && owner === expectedOwner) { "Physical drain barrier required" }
        require(job.id == downloadId && job.producer == producer && job.ownershipGeneration == ownershipGeneration)
        require(actualClaims.size == claims.size && actualClaims.toSet() == claims.toSet()) { "Physical claims changed after drain" }
        consumed = true
    }

    internal fun invalidate() { active = false }
    override fun toString() = "PhysicalDownloadDrainReceipt(ephemeral=true,identity=redacted)"
}

/** Fresh explicit capture/binding; unknown historical C and every old attempt remain unchanged. */
data class AdoptSchema2PhysicalDownload(
    val downloadId: String,
    val requestId: AcquisitionRequestId,
    val requiredBindingId: ExecutionBindingId,
    val expectedProducer: PhysicalDownloadProducer.Schema2,
    val expectedOwnershipGeneration: Long,
    val claimId: DownloadClaimId,
    val expectedClaimGeneration: Long,
    val expectedOwnerIntentRevision: Long,
    val drainReceipt: PhysicalDownloadDrainReceipt,
    val nowEpochMs: Long,
) {
    override fun toString() = "AdoptSchema2PhysicalDownload(identity=redacted,drain=redacted)"
}

/**
 * COMPLETE claim join from one transaction, never a visible UI subset. The producer context is
 * R1, requestedContext is R2; compatible reuse must not relabel job/attempt/finalization/asset.
 * Null producerContext is explicit legacy provenance, not permission to fabricate a typed join.
 */
data class PhysicalDownloadJoin(
    val libraryRevision: Long,
    val job: PhysicalDownloadJob,
    val activeAttempt: PhysicalDownloadAttempt?,
    val finalization: PhysicalDownloadFinalization?,
    val asset: MaterializationAsset?,
    val claims: List<DownloadClaim>,
    val producerContext: AcquisitionExecutionContext?,
    /** Every retained attempt, not just activeAttempt; cleanup cannot omit retired stage paths. */
    val attempts: List<PhysicalDownloadAttempt>,
    val cleanupReservation: PhysicalDownloadCleanupReservation? = null,
)

data class BatchPhysicalDownloadAdmission(
    val member: BatchDownloadMemberExpectation,
    val claim: DownloadClaim,
    val requestedContext: AcquisitionExecutionContext,
    val physical: PhysicalDownloadJoin,
)

/** Committed means positive intended-graph readback + commit, not a swallowed false/CAS error. */
sealed interface PhysicalDownloadCommit<out T> {
    data class Committed<T>(val libraryRevision: Long, val value: T) : PhysicalDownloadCommit<T>
    data object Stale : PhysicalDownloadCommit<Nothing>
}

data class ReconcilePhysicalDownloadClaim(
    val member: BatchDownloadMemberExpectation,
    val claimId: DownloadClaimId,
    val expectedClaimGeneration: Long,
    val nowEpochMs: Long,
)

/** Admission is only after existing executor coverage/offline/activation/slot gates. */
data class ClaimPhysicalDownloadAttempt(
    val downloadId: String,
    val expectedProducer: PhysicalDownloadProducer,
    val expectedOwnershipGeneration: Long,
    val attemptId: String,
    val owner: String,
    val nowEpochMs: Long,
    val leaseDurationMs: Long,
    val stagingPath: String,
    val backgroundTaskId: String? = null,
)

data class ClaimedPhysicalDownload(val fence: PhysicalDownloadFence, val physical: PhysicalDownloadJoin)

data class HeartbeatPhysicalDownload(
    val fence: PhysicalDownloadFence,
    val nowEpochMs: Long,
    val leaseDurationMs: Long,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
)

/** The first selected key is fixed for this physical generation; same-C/key renewal only. */
data class SelectPhysicalDownloadRepresentation(
    val fence: PhysicalDownloadFence,
    val representationId: String,
    val validatorGeneration: Long,
    val etag: String?,
    val lastModified: String?,
    val nowEpochMs: Long,
)

data class PreparePhysicalDownloadFinalization(
    val fence: PhysicalDownloadFence,
    val finalization: PhysicalDownloadFinalization,
    val nowEpochMs: Long,
)

/** Publish VERIFYING/VALIDATING before expensive local hash/native validation starts. */
data class BeginPhysicalDownloadValidation(val fence: PhysicalDownloadFence, val nowEpochMs: Long)

/** File/hash/native evidence is obtained by the owned executor, never by Room/network I/O. */
data class PublishPhysicalDownloadFinalization(
    val fence: PhysicalDownloadFence,
    val expectedFinalization: PhysicalDownloadFinalization,
    val asset: MaterializationAsset,
    val nowEpochMs: Long,
)

data class FinishPhysicalDownloadAttempt(
    val fence: PhysicalDownloadFence,
    val status: DownloadStatus,
    val attemptStatus: DownloadAttemptStatus,
    val failure: AppFailure?,
    val nextRetryEpochMs: Long?,
    val automaticRetryCount: Int,
    val nowEpochMs: Long,
)

/** Intent only. Neither a durable cancellation nor lease expiry is a physical-drain ACK. */
data class ReconcilePhysicalDownloadIntent(
    val downloadId: String,
    val expectedProducer: PhysicalDownloadProducer,
    val expectedOwnershipGeneration: Long,
    val expectedClaims: List<DownloadClaim>,
    val nowEpochMs: Long,
    /** True only after explicit foreground resume has passed the existing executor host gates. */
    val allowHostResume: Boolean = false,
)

data class ClaimPhysicalDownloadMaintenance(
    val downloadId: String,
    val expectedProducer: PhysicalDownloadProducer,
    val expectedOwnershipGeneration: Long,
    val kind: PhysicalDownloadMaintenanceKind,
    val owner: String,
    val nowEpochMs: Long,
    val leaseDurationMs: Long,
    /**
     * Explicit one-shot local validation of retained FAILED bytes. It may bypass only the local
     * validation recheck deadline; host/coverage/ownership/claim/cleanup gates stay authoritative.
     */
    val explicitLocalRevalidation: Boolean = false,
    val requestedBindingId: ExecutionBindingId? = null,
)

/**
 * Exact manual local-revalidation success. The pinned claim generation and asset are rechecked
 * in the finish transaction; [requestedBindingId] is the ONLY use granted. No sibling use is
 * inferred. This is not a transfer retry or a COMPLETE shortcut.
 */
data class RetainedValidationCompletion(
    val expectedClaim: DownloadClaim,
    val requestedBindingId: ExecutionBindingId,
    val expectedAsset: MaterializationAsset,
    val compatibilityPolicyVersion: Int,
)

data class FinishPhysicalDownloadMaintenance(
    val fence: PhysicalDownloadFence,
    val kind: PhysicalDownloadMaintenanceKind,
    val expectedClaims: List<DownloadClaim>,
    val cleanupCompleted: Boolean,
    val failure: AppFailure?,
    val nowEpochMs: Long,
    /** Host suspension is explicit; null preserves the current durable status. */
    val status: DownloadStatus? = null,
    /** Durable bounded local-maintenance backoff; never a first-result retry authorization. */
    val nextRetryEpochMs: Long? = null,
    /** Set ONLY after complete owned hash/native validation; clears failure/backoff atomically. */
    val retainedValidation: RetainedValidationCompletion? = null,
)

/**
 * Exact immutable asset and current claim generation are rejoined; same subject/C alone cannot
 * assert local validity. The executor calls this only AFTER owned current file/hash/native
 * validation. It retains that process-local validation and any readers separately from Room.
 */
data class CommitMaterializationUse(
    val member: BatchDownloadMemberExpectation,
    val claimId: DownloadClaimId,
    val expectedClaimGeneration: Long,
    val expectedOwnershipGeneration: Long,
    val expectedAsset: MaterializationAsset,
    val compatibilityPolicyVersion: Int,
    val nowEpochMs: Long,
)

/**
 * Implemented by the SAME canonical Room transaction owner as AcquisitionRepository/LibraryStore.
 * This is an extension of existing leased download commands, not an alternate queue/transfer
 * loop. A coordinator MUST require referential equality of transactionOwner before injection.
 * All commands read actual rows in an IMMEDIATE transaction, validate the full transition and
 * read back the full intended graph/revision before commit. No cached-state ownership authority.
 *
 * Atomic admission creates/reuses physical job + exact owner claim + batch member attachment in
 * one transaction. It compares subject AND accepted C even for COMPLETE; conflicting C fails.
 * All returned joins include every physical claim. Every update checks durable owner generation
 * and exact attempt/claim/intent generations. Exceptions remain exceptions, never Confirmed.
 * CLEANUP claim persists its kind/cleanupPending reservation and fences new admission, ACTIVE/
 * PAUSED revival, retaining an about-to-delete completed asset, sharing, and path changes until
 * positive fenced finish. Lease expiry does not remove this fence. The returned complete path
 * join includes all retired attempts. Caller must independently own/drain physical writers and
 * readers BEFORE deletion; checking expectedClaims only after deletion is never sufficient.
 * selectRepresentation moves RESOLVING to RUNNING; beginValidation moves RUNNING to VERIFYING
 * and its exact attempt to VALIDATING before local hash/native work. Publication/cleanup cannot
 * infer physical drain from these durable acknowledgements. There is
 * no source, native, hash, filesystem deletion, automatic activation, or fresh first query here.
 */
interface PhysicalDownloadRepository : LibraryTransactionOwned {
    suspend fun physicalDownload(id: String): PhysicalDownloadJoin?
    suspend fun admitStandalonePhysicalDownload(command: AdmitStandalonePhysicalDownload): PhysicalDownloadCommit<StandalonePhysicalDownloadAdmission>
    suspend fun admitSchema2StandalonePhysicalDownload(command: AdmitSchema2StandalonePhysicalDownload): PhysicalDownloadCommit<Schema2StandalonePhysicalDownloadAdmission>
    suspend fun updateStandaloneDownloadIntent(command: UpdateStandaloneDownloadIntent): PhysicalDownloadCommit<PhysicalDownloadJoin>
    suspend fun commitStandaloneMaterializationUse(command: CommitStandaloneMaterializationUse): PhysicalDownloadCommit<AssetUse>
    suspend fun adoptSchema2PhysicalDownload(command: AdoptSchema2PhysicalDownload): PhysicalDownloadCommit<StandalonePhysicalDownloadAdmission>
    suspend fun admitBatchPhysicalDownload(command: AdmitBatchPhysicalDownload): PhysicalDownloadCommit<BatchPhysicalDownloadAdmission>
    suspend fun retryBatchPhysicalDownload(command: RetryBatchPhysicalDownload): PhysicalDownloadCommit<BatchPhysicalDownloadAdmission>
    suspend fun reconcilePhysicalDownloadClaim(command: ReconcilePhysicalDownloadClaim): PhysicalDownloadCommit<PhysicalDownloadJoin>
    suspend fun claimPhysicalDownloadAttempt(command: ClaimPhysicalDownloadAttempt): PhysicalDownloadCommit<ClaimedPhysicalDownload>
    suspend fun heartbeatPhysicalDownload(command: HeartbeatPhysicalDownload): PhysicalDownloadCommit<PhysicalDownloadJoin>
    suspend fun selectPhysicalDownloadRepresentation(command: SelectPhysicalDownloadRepresentation): PhysicalDownloadCommit<PhysicalDownloadJoin>
    suspend fun beginPhysicalDownloadValidation(command: BeginPhysicalDownloadValidation): PhysicalDownloadCommit<PhysicalDownloadJoin>
    suspend fun preparePhysicalDownloadFinalization(command: PreparePhysicalDownloadFinalization): PhysicalDownloadCommit<PhysicalDownloadJoin>
    suspend fun publishPhysicalDownloadFinalization(command: PublishPhysicalDownloadFinalization): PhysicalDownloadCommit<PhysicalDownloadJoin>
    suspend fun finishPhysicalDownloadAttempt(command: FinishPhysicalDownloadAttempt): PhysicalDownloadCommit<PhysicalDownloadJoin>
    suspend fun reconcilePhysicalDownloadIntent(command: ReconcilePhysicalDownloadIntent): PhysicalDownloadCommit<PhysicalDownloadJoin>
    suspend fun claimPhysicalDownloadMaintenance(command: ClaimPhysicalDownloadMaintenance): PhysicalDownloadCommit<ClaimedPhysicalDownload>
    suspend fun finishPhysicalDownloadMaintenance(command: FinishPhysicalDownloadMaintenance): PhysicalDownloadCommit<PhysicalDownloadJoin>
    suspend fun commitMaterializationUse(command: CommitMaterializationUse): PhysicalDownloadCommit<AssetUse>
}

/** Production provides this combined facade; two arbitrary independent owners are not accepted. */
interface AcquisitionLibraryRepository : AcquisitionRepository, PhysicalDownloadRepository, PlaybackAcquisitionRepository
