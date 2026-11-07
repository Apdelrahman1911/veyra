package dev.veyra.downloads

import dev.veyra.acquisition.AcquisitionDownloadMemberKey
import dev.veyra.data.LibraryTransactionOwned
import dev.veyra.data.LibraryTransactionOwner
import dev.veyra.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An actual primitive negative from an EXACT admitted local check. Opaque, process-local and
 * issuer-bound; never an inferred AppFailure classification, successful check, AssetUse, local
 * grant or command/native/lease-drain ACK. A late receipt can name C0 after C1 succeeded: UI
 * must compare the same owner's frozen target AND reference, never revoke C1 from this alone.
 * Resolver-only checks have no fabricated batch/member provenance. Explicit Validate supplies
 * its rejoined member and full claim; intent/navigation changes do not change that history.
 */
class DownloadMaterializationRejection internal constructor(
    private val issuer: Any,
    val downloadId: String,
    val asset: MaterializationAsset,
    val requestId: AcquisitionRequestId?,
    val requestedBindingId: ExecutionBindingId,
    val successfulCheck: DownloadMaterializationCheck?,
    val member: AcquisitionDownloadMemberKey?,
    val expectedClaim: DownloadClaim?,
) {
    internal fun issuedBy(candidate: Any): Boolean = issuer === candidate
    override fun toString() = "DownloadMaterializationRejection(processLocal=true,identity=redacted)"
}

/** Captured only AFTER the exact path lease and a fresh canonical rejoin; not a tap token. */
internal class DownloadMaterializationValidationAttempt internal constructor(
    private val issuer: Any,
    val downloadId: String,
    val asset: MaterializationAsset,
    val requestId: AcquisitionRequestId?,
    val requestedBindingId: ExecutionBindingId,
    val successfulCheck: DownloadMaterializationCheck?,
    val member: AcquisitionDownloadMemberKey?,
    val expectedClaim: DownloadClaim?,
) {
    internal fun issuedBy(candidate: Any): Boolean = issuer === candidate
    override fun toString() = "DownloadMaterializationValidationAttempt(processLocal=true,identity=redacted)"
}

/**
 * The application's ONE same-LibraryTransactionOwner evidence registry. Required injection in
 * both resolvers and the existing DownloadCoordinator; no hidden default owner or new scope,
 * scheduler, source client, Room write or native resource. Bounded synchronous CAS updates run
 * at the primitive rejection BEFORE throwing/cleanup, independent of actor ACK and navigation.
 */
class DownloadMaterializationEvidence(
    override val transactionOwner: LibraryTransactionOwner,
) : LibraryTransactionOwned {
    private val issuer = Any()
    private val state = MutableStateFlow(DownloadMaterializationChecks.Empty)
    internal val checks = state.asStateFlow()

    fun owns(rejection: DownloadMaterializationRejection): Boolean = rejection.issuedBy(issuer)

    internal fun admit(
        downloadId: String,
        asset: MaterializationAsset,
        requestedBindingId: ExecutionBindingId,
        requestId: AcquisitionRequestId?,
        member: AcquisitionDownloadMemberKey? = null,
        expectedClaim: DownloadClaim? = null,
    ): DownloadMaterializationValidationAttempt {
        require(downloadId.isNotBlank())
        require((member == null) == (expectedClaim == null))
        if (member != null && expectedClaim != null) {
            require(member.requestId == requestId && member.requestedBindingId == requestedBindingId)
            require(expectedClaim.physicalJobId == downloadId && expectedClaim.requestedBindingId == requestedBindingId)
            require(expectedClaim.owner == DownloadClaimOwner.BatchMember(member.batchId, member.memberPosition))
        }
        val successful = state.value.successfulChecks[downloadId]?.takeIf { it.asset == asset }
        return DownloadMaterializationValidationAttempt(issuer, downloadId, asset, requestId,
            requestedBindingId, successful, member, expectedClaim)
    }

    /** Called ONLY by the four physical-success sites, after their existing exact owner/use CAS. */
    internal fun recordPhysicalSuccess(downloadId: String, asset: MaterializationAsset) {
        while (true) {
            val before = state.value
            if (state.compareAndSet(before, before.recordSuccess(downloadId, asset))) return
        }
    }

    /**
     * Not an exception classifier. Wrong issuer/input cannot issue a receipt or change evidence.
     * Genuine negatives retain C0 even if already revoked; if C1 won, the receipt still names
     * the admitted C0 and the CAS leaves C1 valid. No durable sibling use is granted or removed.
     */
    internal fun reject(attempt: DownloadMaterializationValidationAttempt,
        negative: DownloadMaterializationNegative): DownloadMaterializationRejection? {
        if (!attempt.issuedBy(issuer) || negative.input != attempt.asset.validationInput()) return null
        while (true) {
            val before = state.value
            val after = before.rejected(attempt.downloadId, attempt.asset, attempt.successfulCheck)
            if (after === before || state.compareAndSet(before, after)) break
        }
        return DownloadMaterializationRejection(issuer, attempt.downloadId, attempt.asset, attempt.requestId,
            attempt.requestedBindingId, attempt.successfulCheck, attempt.member, attempt.expectedClaim)
    }

    /** Actual exact deletion only; a null reservation or another full asset cannot clear C1. */
    internal fun forgetDeleted(downloadId: String, asset: MaterializationAsset) {
        while (true) {
            val before = state.value
            val after = before.withoutExact(downloadId, asset)
            if (after === before || state.compareAndSet(before, after)) return
        }
    }
}
