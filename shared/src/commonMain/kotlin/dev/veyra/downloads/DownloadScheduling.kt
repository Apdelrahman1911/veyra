package dev.veyra.downloads

import dev.veyra.model.AppFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A due native worker does not request a scan of every historical completed asset. */
enum class DownloadActivation { DURABLE_WORK, USER_SESSION }

/** Eligibility for a NEW network claim, not a guarantee that a source request will succeed. */
enum class DownloadNetworkState { UNKNOWN, DISCONNECTED, CONNECTED }

/** Hosts without a connectivity adapter retain existing behavior; offlineOnly is still enforced. */
val alwaysConnectedDownloadNetwork: StateFlow<DownloadNetworkState> =
    MutableStateFlow(DownloadNetworkState.CONNECTED).asStateFlow()

/** Process-local equality token. Never persist it or infer a Room/global playback revision from it. */
data class DownloadSchedulingKey(val process: String, val revision: Long)

/**
 * Not-before timestamps, with independent LOCAL and CONNECTED-network lanes. A null timestamp
 * means no wake in that lane, not that all durable intent is complete. A disconnected network
 * retains its deadline, while explicit offline/paused/NeedsForeground policy is never auto-resumed.
 * No mutable download rows, source URLs, credentials or transfers belong in native wake input.
 */
data class DownloadWakePlan(
    val key: DownloadSchedulingKey,
    val nextLocalEpochMs: Long?,
    val nextNetworkEpochMs: Long?,
    val hasDueWork: Boolean,
    val blockedReason: AppFailure? = null,
)

/**
 * Opaque session-only instance. Public construction permits fake scheduling ports in native tests;
 * a real coordinator accepts ONLY its exact issued instance and private state/epoch checks, once.
 * Reconstructing, copying a key into another ticket, or replaying it never authorizes retirement.
 */
class DownloadIdleTicket(val key: DownloadSchedulingKey)

/**
 * Receipt from an independent host drain AFTER registered jobs joined AND their resource,
 * allowance, file/slot and Room cleanup stages returned. A terminated job or zero native leases
 * does not consume a separately retained failed-close owner. Such owners and any prior unknown
 * close failure forbid this receipt, even if an explicit later close finally consumes them.
 * A known typed Room checkpoint failure is retained separately; it is not an unknown exception/
 * cancellation with no drain receipt. This does not resume a rejected foreground action.
 * Existing Room finalizers may catch/report an Exception: their return means no owner is still
 * using Room, NOT that every lease-release CAS durably succeeded. This is not a Ready receipt.
 * Native must not clear an expiration barrier on a thrown error merely because leases are zero.
 */
data class DownloadBackgroundDrainAck(val checkpointFailure: AppFailure? = null)

sealed interface DownloadIdleDecision {
    /** Registered/pending-admission work or native/file/Room unwind still requires an owner. */
    data object OwnedWork : DownloadIdleDecision
    data class Deferred(val ticket: DownloadIdleTicket, val plan: DownloadWakePlan) : DownloadIdleDecision
}

/** Scheduling/ownership protocol only. The existing coordinator remains the sole transfer owner. */
interface DownloadScheduling {
    /** Hint only; clock crossings and another Room connection still require a bounded fresh read. */
    val schedulingChanges: StateFlow<Long>
    suspend fun schedulingPlan(): DownloadWakePlan
    suspend fun idleDecision(): DownloadIdleDecision

    /**
     * Publish/ACK native wakes BEFORE calling, outside shared locks. Native callers run the whole
     * function on their gate's confined dispatcher (Android Main.immediate). The callback is a
     * synchronous exact-worker + lease-epoch + zero-lease compare only: NO suspension, I/O,
     * WorkManager call, expiration callback or reentry into this coordinator. Shared admission is
     * serialized through the final fresh relevant-state/time/activity checks and this callback.
     * Invalid/replayed tickets return false without invoking it. Exceptions are not idle success.
     */
    suspend fun confirmIdle(ticket: DownloadIdleTicket, retire: () -> Boolean): Boolean
}
