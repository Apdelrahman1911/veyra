package dev.veyra.downloads

import dev.veyra.acquisition.*
import dev.veyra.model.*

internal data class DownloadBatchWorkDeferral(
    val work: PendingDownloadBatchWork,
    val untilEpochMs: Long,
    val refusals: Int,
    val failure: AppFailure? = null,
)

/** Included in the existing coordinator's exact idle inputs, never a second durable queue. */
internal data class DownloadBatchWorkSchedulingInputs(
    val pending: PendingDownloadBatchProjection,
    val deferrals: Map<PendingDownloadBatchKey, DownloadBatchWorkDeferral>,
)

/** Extend the SAME two-lane wake plan with not-yet-physical batch members. No side effects. */
internal fun withPendingDownloadBatchWork(
    physical: DownloadWakePlan,
    inputs: DownloadBatchWorkSchedulingInputs,
    host: DownloadPhysicalHostState,
    now: Long,
): DownloadWakePlan {
    var local = physical.nextLocalEpochMs
    var network = physical.nextNetworkEpochMs
    var due = physical.hasDueWork
    var blocked = physical.blockedReason
    fun localWake(at: Long) {
        local = local?.let { minOf(it, at) } ?: at
        if (at <= now) due = true
    }
    fun networkWake(at: Long) {
        network = network?.let { minOf(it, at) } ?: at
        if (at <= now && host.network == DownloadNetworkState.CONNECTED) due = true
    }
    val pending = inputs.pending
    // Like physical planning, cold Room-only planning may expose a deadline without starting
    // the owner. Activation and native acquisition remain separate explicit host operations.
    if (host.closed || host.suspended || host.drainsInFlight != 0 || host.failure != null) {
        if (pending.work.isNotEmpty() || pending.nextLocalWakeEpochMs != null)
            blocked = blocked ?: host.failure ?: AppFailure(FailureCode.BACKGROUND_RESTRICTED)
        return physical.copy(blockedReason = blocked)
    }
    pending.nextLocalWakeEpochMs?.let(::localWake)
    for (work in pending.work) {
        val deferral = inputs.deferrals[work.key]?.takeIf { it.work == work }
        if (work.requiresNetwork && host.offlineOnly) {
            blocked = blocked ?: AppFailure(FailureCode.OFFLINE_POLICY)
        } else if (deferral != null && deferral.untilEpochMs > now) {
            // A refused slot/unchanged local CAS is local arbitration, not a reason to wake
            // continuously on a connected-network worker. It never authorizes another search.
            localWake(deferral.untilEpochMs)
            blocked = blocked ?: deferral.failure
        } else if (work.requiresNetwork) networkWake(0)
        else localWake(0)
    }
    if (blocked == null) {
        blocked = when {
            pending.blocked.any { it.reason == PendingDownloadBatchBlockedReason.OUTCOME_UNKNOWN } ->
                AppFailure(FailureCode.RECOVERY_REQUIRED, "download_selection_outcome_unknown")
            pending.blocked.any { it.reason == PendingDownloadBatchBlockedReason.INVALID_STATE } ->
                AppFailure(FailureCode.RECOVERY_REQUIRED, "download_selection_join_invalid")
            network != null && host.network != DownloadNetworkState.CONNECTED -> AppFailure(FailureCode.NETWORK_UNAVAILABLE)
            else -> null
        }
    }
    return physical.copy(nextLocalEpochMs = local, nextNetworkEpochMs = network, hasDueWork = due, blockedReason = blocked)
}
