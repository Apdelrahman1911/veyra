package dev.veyra.downloads

import dev.veyra.model.AppFailure
import dev.veyra.model.DownloadBatchIntent
import dev.veyra.model.DownloadClaim
import dev.veyra.model.DownloadClaimOwner
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException

/** Demand only. No variant is an owner, file-removal, native handoff or Ready certificate. */
internal enum class DownloadClaimDemand {
    CONTINUE,
    PAUSE_AND_RETAIN,
    CANCEL_UNFINISHED_KEEP_COMPLETE,
    REMOVE_CANDIDATE,
    /** Missing typed members cannot erase a legacy/standalone owner or an uncertain admission. */
    UNOWNED_UNKNOWN,
}

internal data class DownloadClaimSummary(
    val demand: DownloadClaimDemand,
    val activeOwners: Int,
    val pausedOwners: Int,
    val cancelledRetainingOwners: Int,
    val cancelledNonretainingOwners: Int,
)

/**
 * Used on the canonical Room command's COMPLETE same-job claim join, not a UI's visible subset.
 * This projection never supplies commit authority: the Room owner must repeat the exact
 * revision/generation/claim checks when committing intent, and the existing executor separately
 * acknowledges native/file/cleanup owners and readers before removal. No claim is silently
 * filtered/deduplicated, and zero claims is not an orphan/removal generation.
 */
internal fun summarizeDownloadClaims(physicalJobId: String, claims: Collection<DownloadClaim>): DownloadClaimSummary {
    val stable = claims.toList()
    if (physicalJobId.isBlank() || stable.any {
            val ownerJob = when (val owner = it.owner) {
                is DownloadClaimOwner.Standalone -> owner.jobId
                is DownloadClaimOwner.Schema2Standalone -> owner.jobId
                is DownloadClaimOwner.BatchMember -> null
            }
            it.physicalJobId != physicalJobId || ownerJob?.let { job -> job != physicalJobId } == true
        } || stable.map { it.id }.toSet().size != stable.size ||
        stable.map { claim ->
            // Schema2 is historical provenance, not permission to add a second standalone
            // owner of the same physical job during migration/typed coexistence.
            when (val owner = claim.owner) {
                is DownloadClaimOwner.Schema2Standalone -> DownloadClaimOwner.Standalone(owner.jobId)
                else -> owner
            }
        }.toSet().size != stable.size) {
        throw VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "download_claim_join_invalid"))
    }
    val active = stable.count { it.intent == DownloadBatchIntent.ACTIVE }
    val paused = stable.count { it.intent == DownloadBatchIntent.PAUSED }
    val retaining = stable.count { it.intent == DownloadBatchIntent.CANCELLED && it.retainCompletedAsset }
    val nonretaining = stable.count { it.intent == DownloadBatchIntent.CANCELLED && !it.retainCompletedAsset }
    val demand = when {
        active > 0 -> DownloadClaimDemand.CONTINUE
        paused > 0 -> DownloadClaimDemand.PAUSE_AND_RETAIN
        retaining > 0 -> DownloadClaimDemand.CANCEL_UNFINISHED_KEEP_COMPLETE
        nonretaining > 0 -> DownloadClaimDemand.REMOVE_CANDIDATE
        else -> DownloadClaimDemand.UNOWNED_UNKNOWN
    }
    return DownloadClaimSummary(demand, active, paused, retaining, nonretaining)
}
