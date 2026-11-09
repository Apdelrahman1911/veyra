package dev.veyra.platform.background

import dev.veyra.downloads.DownloadIdleDecision
import dev.veyra.downloads.DownloadScheduling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The finite idle handshake is factored out for fake-port tests, not a second download scheduler. */
internal class AndroidDownloadWorkerLifetime(
    private val gate: AndroidDownloadExecutionGate,
    private val scheduler: AndroidDownloadScheduler,
    private val hintTimeoutMs: Long = 1_000,
    private val maximumIdleConflicts: Int = 4,
) {
    init { require(hintTimeoutMs > 0 && maximumIdleConflicts > 0) }

    suspend fun retireWhenIdle(worker: String, scheduling: DownloadScheduling) {
        var idleConflicts = 0
        while (true) {
            when (val decision = scheduling.idleDecision()) {
                DownloadIdleDecision.OwnedWork -> {
                    idleConflicts = 0
                    // The decision may itself signal a due scheduler round. Sample AFTER that
                    // authoritative read, so its own hint cannot cause an immediate hot read loop.
                    awaitHint(scheduling, scheduling.schedulingChanges.value, gate.leaseCount.value)
                }
                is DownloadIdleDecision.Deferred -> {
                    val candidate = gate.idleCandidate(worker)
                    if (candidate == null) {
                        // A direct native lease is genuine ownership even if shared has no work.
                        if (gate.leaseCount.value > 0) idleConflicts = 0
                        else if (++idleConflicts >= maximumIdleConflicts) throw schedulingFailure("download_worker_not_owner")
                        awaitHint(scheduling, scheduling.schedulingChanges.value, gate.leaseCount.value)
                        continue
                    }
                    scheduler.beforeRetirement(scheduling)
                    // The entire shared confirmation executes on Main.immediate. After its final
                    // fresh Room/activity/time checks, the callback compares identity+epoch without
                    // dispatch/suspension. A boolean returned BEFORE dispatching Main is not enough.
                    if (withContext(Dispatchers.Main.immediate) {
                            scheduling.confirmIdle(decision.ticket) { gate.retire(candidate) }
                        }) return
                    // Repeated changing *idle* plans must not keep an empty FGS forever. Owned work
                    // resets this budget; otherwise fail terminally, with durable repair coverage.
                    if (++idleConflicts >= maximumIdleConflicts) throw schedulingFailure("download_idle_confirmation_changed")
                }
            }
        }
    }

    private suspend fun awaitHint(scheduling: DownloadScheduling, previous: Long, leases: Int) {
        // Hints reduce polling only. Timeout, cached false and zero leases NEVER authorize idle.
        withTimeoutOrNull(hintTimeoutMs) {
            combine(scheduling.schedulingChanges, gate.leaseCount) { change, count -> change != previous || count != leases }
                .first { it }
        }
    }
}
