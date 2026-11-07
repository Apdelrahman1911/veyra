package dev.veyra.acquisition

import dev.veyra.data.LibraryTransactionOwned
import dev.veyra.downloads.DownloadMaterializationRejection
import dev.veyra.model.*

/** Immutable owner intent, not a physical execution lease. Every retry reads the current revision. */
data class AcquisitionDownloadMemberKey(
    val batchId: DownloadBatchId,
    val memberPosition: Int,
    val intentRevision: Long,
    val occurrenceId: ExecutionOccurrenceId,
    val requestId: AcquisitionRequestId,
    val requestedBindingId: ExecutionBindingId,
) {
    init { require(memberPosition >= 0 && intentRevision >= 0) }
}

/** The joined value is input for a SAME-Room revalidation, never caller-supplied commit authority. */
data class AdmitAcquisitionDownload(
    val member: AcquisitionDownloadMemberKey,
    val acquisition: BoundAcquisitionOccurrence,
    val previouslyAttachedClaimId: DownloadClaimId?,
)

/** Desired intent is already durable. This command does not assert that an old writer stopped. */
data class ReconcileAcquisitionDownload(
    val member: AcquisitionDownloadMemberKey,
    val expectedClaim: DownloadClaim,
)

/** An explicit exact asset generation, never "latest completed media" or an online fallback. */
data class ValidateAcquisitionDownloadAsset(
    val member: AcquisitionDownloadMemberKey,
    val expectedClaim: DownloadClaim,
    val materializationAssetId: MaterializationAssetId,
)

/** Detached projection of the existing physical job; not a replacement persistent job model. */
data class AcquisitionDownloadJobIdentity(
    val physicalJobId: String,
    val subjectId: ExecutableSubjectId,
    val producerBindingId: ExecutionBindingId,
    val recordingId: SourceRecordingId,
) {
    init { requireExecutorIdentifier(physicalJobId) }
}

/**
 * Executor-owned process/physical-ownership epoch plus the exact immutable attempt, if admitted.
 * The existing executor issues these values under its admission locks; the adapter never invents
 * them. An ownership epoch fences every acquiring/maintenance/cleanup owner of this job and all
 * publication callbacks admitted in it. A new physical admission must use a new epoch; retries
 * of one retained cleanup owner keep its old epoch. An idle job still needs an issued epoch.
 * Attempt absence, status, lease expiry and owner count are NOT quiescence acknowledgements.
 * None of this process-local receipt is serialized as historical source or Room authority.
 */
data class AcquisitionDownloadGeneration(
    val executorGeneration: String,
    val ownershipGeneration: String,
    val attemptId: String?,
    val attemptNumber: Int?,
) {
    init {
        requireExecutorIdentifier(executorGeneration)
        requireExecutorIdentifier(ownershipGeneration)
        require((attemptId == null) == (attemptNumber == null))
        attemptId?.let(::requireExecutorIdentifier)
        require(attemptNumber == null || attemptNumber >= 1)
    }
    override fun toString() = "AcquisitionDownloadGeneration(processLocal=true,identity=redacted)"
}

/**
 * Opaque same-executor receipt. Claim identity is never recycled; member.intentRevision fences
 * its exact owner generation separately from the physical job/attempt generation. A fence is an
 * observation of an admitted operation, not permission to mutate a future attempt or delete a
 * shared asset. Any later executor operation must recheck durable and physical ownership itself.
 */
class AcquisitionDownloadFence internal constructor(
    internal val issuer: AcquisitionDownloadExecutor,
    val member: AcquisitionDownloadMemberKey,
    val claim: DownloadClaim,
    val job: AcquisitionDownloadJobIdentity,
    val generation: AcquisitionDownloadGeneration,
) {
    override fun toString() = "AcquisitionDownloadFence(processLocal=true,identity=redacted)"
}

/** The job, claim and member link were committed together before this response was exposed. */
data class AcquisitionDownloadAdmission(
    val fence: AcquisitionDownloadFence,
    val committedLibraryRevision: Long,
) {
    init { require(committedLibraryRevision >= 0) }
}

/** Positive durable job/claim-finalizer CAS is independent of physical dependency lifetime. */
sealed interface AcquisitionDownloadDurableSettlement {
    data class Confirmed(val committedLibraryRevision: Long) : AcquisitionDownloadDurableSettlement {
        init { require(committedLibraryRevision >= 0) }
    }
    data object Stale : AcquisitionDownloadDurableSettlement
    data class Unconfirmed(val failure: AppFailure) : AcquisitionDownloadDurableSettlement
}

/**
 * Issued only after positively consuming ALL named epoch's executor-owned transfer, maintenance,
 * native resource, allowance and file-lease cleanup owners. It does not cover unrelated playback
 * readers or future epochs, prove a caught Room finalizer succeeded, or authorize global Remove.
 */
class AcquisitionDownloadQuiescence internal constructor(val fence: AcquisitionDownloadFence) {
    override fun toString() = "AcquisitionDownloadQuiescence(exactEpoch=true,identity=redacted)"
}

sealed interface AcquisitionDownloadPhysicalSettlement {
    /** Other active/retaining claims may still need work. No assertion that the job is idle. */
    data object NotRequested : AcquisitionDownloadPhysicalSettlement
    /** The executor retains every unconsumed owner, even when its coroutine has already ended. */
    data class Retained(val failure: AppFailure? = null) : AcquisitionDownloadPhysicalSettlement
    data class Quiescent(val acknowledgement: AcquisitionDownloadQuiescence) : AcquisitionDownloadPhysicalSettlement
}

/** No status here automatically changes the durable batch or means all its members are Ready. */
data class AcquisitionDownloadReconciliation(
    val fence: AcquisitionDownloadFence,
    val durable: AcquisitionDownloadDurableSettlement,
    val physical: AcquisitionDownloadPhysicalSettlement,
)

/**
 * Issued after exact current-process file/size/hash/representation/native validation AND the
 * compatible use relation's Room commit. The immutable materializer keeps its original R1;
 * use.requestedBindingId remains R2. This is not a local URI/native handoff or a restart waiver.
 * Before a later handoff the existing local resolver rechecks this exact generation, removal
 * fences and physical readers; a copied/persisted object cannot supply that authority.
 */
class AcquisitionDownloadValidatedUse internal constructor(
    val fence: AcquisitionDownloadFence,
    val use: AssetUse,
    val committedLibraryRevision: Long,
) {
    init { require(committedLibraryRevision >= 0) }
    override fun toString() = "AcquisitionDownloadValidatedUse(currentProcess=true,identity=redacted)"
}

sealed interface AcquisitionDownloadAssetCheck {
    /** Operation-only errors have no stamp; a stamp proves only the exact actual negative check. */
    data class Unavailable(
        val failure: AppFailure,
        val validationRejection: DownloadMaterializationRejection? = null,
    ) : AcquisitionDownloadAssetCheck
    data class Validated(val validation: AcquisitionDownloadValidatedUse) : AcquisitionDownloadAssetCheck
}

/**
 * Exact D010 extension port for the EXISTING leased DownloadCoordinator. It is not implemented
 * by another downloader, a MediaItem bridge, an in-memory repository or this package's adapter.
 * The currently frozen schema2 coordinator does not yet implement it: wiring is a required gate.
 *
 * Integration obligations for the existing executor + canonical schema3 Room owner:
 * - Expose that same owner's process-local token. A facade on another Room owner cannot be
 *   substituted even when its current state happens to contain equal IDs and values.
 * - Rejoin every request/receipt/binding/occurrence/scope/member/intent in one transaction; a
 *   previously read BoundAcquisitionOccurrence is not authority. Accept only the requested
 *   binding, including neutral source subjects, with no fresh firstResult/title search.
 * - Atomically lookup/create the existing subject-owned physical job, validate R1/R2 same-subject
 *   and same-C compatibility (including COMPLETE), create/reuse this unique member's claim, and
 *   attach it before returning or exposing work to a scheduler. Different C is a conflict. Do
 *   not call a stand-alone enqueue and then attachBatchDownloadClaim in a second transaction.
 * - Existing producer binding/attempt/finalization are never relabelled to a new claim's binding.
 *   Concurrent duplicate admission of the same exact member is idempotent. A nonnull previously
 *   attached claim ID must match; never create a second owner after cancellation/return loss.
 * - Respect existing scheduling activation/coverage/offline/native gates. Admission alone does
 *   not start maintenance/transfer. kmpYT remains the one transfer engine; use the accepted
 *   Single's exact receipt directly with its typed execution adapter, not a fake MUSIC/EPISODE.
 * - Reconciliation fences this exact claim generation; aggregate claims decide physical action.
 *   Other owners cannot be silently paused/cancelled/deleted. Callbacks from an old epoch cannot
 *   finalize a new one. Retain all unconsumed resources; never infer ACK from timeout/status.
 * - A successful dependency drain plus failed/caught Room CAS returns Quiescent + Unconfirmed,
 *   not Confirmed. A failed native close with successful Room CAS returns Confirmed + Retained.
 * - Asset validation is explicit, local-only and exact-generation. Check subject, both receipts,
 *   live file ownership/no cleanup, immutable materialization, hash/size/representation/validator
 *   identity and native support before committing AssetUse. No same-C/COMPLETE shortcut.
 * - Cancellation may race a durable admission; retain executor ownership and let Room-only
 *   restore discover its exact claim. Exceptions are sanitized VeyraException or cancellation;
 *   neither the adapter nor a retry loop compensates by cancelling a possibly shared job.
 *
 * These methods return versioned observations, never commands for arbitrary file deletion or
 * native handoff. initialize()/offline restore must not call any of them automatically.
 */
interface AcquisitionDownloadExecutor : LibraryTransactionOwned {
    suspend fun admitMember(command: AdmitAcquisitionDownload): AcquisitionDownloadAdmission
    /** Exact metadata retry; never an ACTIVE-to-ACTIVE batch intent or a physical-drain grant. */
    suspend fun retryMember(command: RetryAcquisitionDownload): AcquisitionDownloadRetryResult
    suspend fun reconcileClaim(command: ReconcileAcquisitionDownload): AcquisitionDownloadReconciliation
    suspend fun validateAssetUse(command: ValidateAcquisitionDownloadAsset): AcquisitionDownloadAssetCheck
}

private fun requireExecutorIdentifier(value: String) {
    require(value.length in 1..256 && value.isNotBlank() && value.none { it.code < 32 || it.code in 127..159 })
}
