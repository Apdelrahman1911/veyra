package dev.veyra.platform.background

import android.content.Context
import android.os.Looper
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import dev.veyra.downloads.DownloadBackgroundDrainAck
import dev.veyra.downloads.DownloadExecutionGate
import dev.veyra.downloads.DownloadExecutionLease
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * WorkManager owns the OS allowance; the root coordinator remains the ONLY transfer owner.
 * No lease becomes ready before the worker's setForeground() actually succeeds.
 * Main-confined state makes worker-idle/new-acquire handoff atomic without holding a mutex over IO.
 *
 * [drainOwners] is an INDEPENDENT owner operation, never a transfer's expiration callback. It must
 * await the shared registered jobs' native/file/Room unwind even when checkpointing fails. Only
 * DownloadCoordinator.drainForBackground's typed ACK clears the barrier; its checkpointFailure is
 * already retained by shared state. An unknown exception/cancellation is NOT a drain ACK.
 */
class AndroidDownloadExecutionGate internal constructor(
    private val ensureCoverage: suspend () -> Unit,
    private val drainOwners: suspend () -> DownloadBackgroundDrainAck,
    private val isWorkUnfinished: suspend (UUID) -> Boolean,
    private val stateReadTimeoutMs: Long = 10_000,
    private val foregroundRequestTimeoutMs: Long = 20_000,
    private val enqueue: suspend (OneTimeWorkRequest) -> Unit,
) : DownloadExecutionGate {
    constructor(context: Context, ensureCoverage: suspend () -> Unit, drainOwners: suspend () -> DownloadBackgroundDrainAck) : this(
        ensureCoverage = ensureCoverage,
        drainOwners = drainOwners,
        isWorkUnfinished = { id ->
            WorkManager.getInstance(context.applicationContext).getWorkInfoById(id).await()?.state?.isFinished == false
        },
        enqueue = { work ->
            withContext(Dispatchers.IO) {
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work,
                ).result.await()
            }
        },
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val leases = linkedMapOf<Long, Lease>()
    private var sequence = 0L
    private var foregroundWorker: String? = null
    private var scheduledWorker: ScheduledWorker? = null
    // Reference equality fences even an acquire AND release between candidate and confirmation.
    // Unlike an incrementing counter, this cannot wrap into an old candidate's epoch.
    private var leaseEpoch = Any()
    private val expirations = mutableSetOf<Expiration>()
    private val mutableLeaseCount = MutableStateFlow(0)
    internal val leaseCount: StateFlow<Int> = mutableLeaseCount.asStateFlow()

    init { require(stateReadTimeoutMs > 0 && foregroundRequestTimeoutMs > 0) }

    private class Lease(val id: Long, val onExpiration: suspend () -> Unit) {
        val ready = CompletableDeferred<Unit>()
        var released = false
        var expired = false
    }
    private class Expiration(val worker: String, val leases: List<Lease>) {
        var finishing = false
        var failure: AppFailure? = null
        val finished = CompletableDeferred<DownloadBackgroundDrainAck>()
    }
    // The object itself is the immutable scheduled-request epoch; UUID alone is not a CAS token.
    private class ScheduledWorker(val work: OneTimeWorkRequest) {
        val token = work.id.toString()
        val enqueued = CompletableDeferred<Unit>()
    }
    private class ScheduledSelection(val scheduled: ScheduledWorker, val validateCachedAck: Boolean)

    internal class IdleCandidate internal constructor(internal val worker: String, internal val epoch: Any)

    override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
        var created: Lease? = null
        try {
            // The application also fences durable admission. Keep the native gate safe when a
            // host/native caller acquires it directly: a failed installation is never an ACK.
            ensureCoverage()
            val lease = withContext(Dispatchers.Main.immediate) {
                requireNoExpiration()
                check(sequence < Long.MAX_VALUE)
                Lease(++sequence, onExpiration).also {
                    created = it // Retain if prompt cancellation wins at withContext's return edge.
                    leases[it.id] = it
                    leaseEpoch = Any()
                    mutableLeaseCount.value = leases.size
                    if (foregroundWorker != null) it.ready.complete(Unit)
                }
            }
            withTimeout(foregroundRequestTimeoutMs) {
                if (!lease.ready.isCompleted) ensureForegroundScheduled()
                lease.ready.await()
            }
            return DownloadExecutionLease { release(lease) }
        } catch (_: TimeoutCancellationException) {
            created?.let { release(it) }
            throw restricted("download_worker_start_timeout")
        } catch (error: Throwable) {
            created?.let { release(it) }
            throw error
        }
    }

    /** A previous worker's global checkpoint/cancellation cannot race foreground resume. */
    suspend fun awaitForegroundReady(): Unit = withContext(Dispatchers.Main.immediate) {
        while (expirations.isNotEmpty()) expirations.toList().forEach { it.finished.await() }
    }

    private suspend fun release(lease: Lease): Unit = withContext(NonCancellable + Dispatchers.Main.immediate) {
        releaseOnMain(lease)
    }

    private fun releaseOnMain(lease: Lease) {
        if (lease.released) return
        lease.released = true
        leases.remove(lease.id)
        leaseEpoch = Any()
        mutableLeaseCount.value = leases.size
    }

    /**
     * Wake-only work delegates execution, not a synthetic transfer lease. Return only after a new
     * enqueue is durably acknowledged, a cached exact-ID ACK is freshly verified unfinished, or a
     * real foreground owner exists. WorkManager can cancel queued work without ever entering doWork;
     * the old completed in-memory ACK must not conceal that forever in this still-live process.
     */
    internal suspend fun requestForegroundWork() {
        try {
            withTimeout(foregroundRequestTimeoutMs) {
                ensureCoverage()
                awaitForegroundReady()
                ensureForegroundScheduled()
            }
        } catch (_: TimeoutCancellationException) {
            throw restricted("download_worker_enqueue_timeout")
        }
    }

    private suspend fun ensureForegroundScheduled() {
        repeat(MAX_SCHEDULE_CHECKS) {
            val selection = withContext(Dispatchers.Main.immediate) {
                requireNoExpiration()
                if (foregroundWorker != null) null
                else scheduledWorker?.let { ScheduledSelection(it, it.enqueued.isCompleted) }
                    ?: ScheduledSelection(scheduleWorker(), validateCachedAck = false)
            } ?: return
            val scheduled = selection.scheduled
            scheduled.enqueued.await()
            // A caller awaiting this newly completed operation has a fresh durable enqueue ACK.
            if (!selection.validateCachedAck) return
            val unfinished = try {
                Result.success(withTimeout(stateReadTimeoutMs) {
                    // Never do a WorkManager read/await on Main or under the shared idle locks.
                    withContext(Dispatchers.IO) { isWorkUnfinished(scheduled.work.id) }
                })
            } catch (_: TimeoutCancellationException) {
                Result.failure<Boolean>(restricted("download_worker_state_timeout"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Result.failure<Boolean>(restricted("download_worker_state_failed")) }
            val covered = withContext(Dispatchers.Main.immediate) {
                when {
                    // A real setForeground ACK wins even if an earlier state read arrives late.
                    foregroundWorker != null -> true
                    // Another caller/start already changed this exact request epoch. Re-read; do
                    // not expire or replace the unrelated successor on an old terminal response.
                    scheduledWorker !== scheduled -> false
                    unfinished.getOrThrow() -> true
                    else -> { scheduledWorker = null; false }
                }
            }
            if (covered) return
        }
        throw restricted("download_worker_schedule_changed")
    }

    /** Main-only, invoked only when no current foreground/scheduled owner exists. */
    private fun scheduleWorker(): ScheduledWorker {
        check(foregroundWorker == null && scheduledWorker == null)
        val work = OneTimeWorkRequestBuilder<VeyraDownloadWorker>().addTag(WORK_NAME).build()
        val scheduled = ScheduledWorker(work).also { scheduledWorker = it }
        scope.launch {
            try {
                // KEEP loses acquisitions that arrive just as a prior worker exits. A successor
                // appended after atomic idle-claim survives that race, even across process death.
                // This lane MUST contain only immediate work; neither delays nor Result.retry.
                withTimeout(ENQUEUE_ACK_TIMEOUT_MS) { enqueue(work) }
                scheduled.enqueued.complete(Unit)
            } catch (error: Exception) {
                scheduled.enqueued.completeExceptionally(restricted("download_worker_enqueue_failed"))
                try { failedStart(scheduled.token) }
                catch (_: VeyraException) {
                    // No drain ACK: failedStart retained a sticky, observable typed barrier. The
                    // original enqueue is already failed; do not crash Main with an unhandled job.
                }
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
            }
        }
        return scheduled
    }

    /** Called only after WorkManager has confirmed its dataSync foreground service. */
    internal suspend fun foregroundStarted(worker: String): Boolean = withContext(Dispatchers.Main.immediate) {
        // Stay on Main between the final barrier check and granting ownership. A dispatch gap
        // here would let a new expiration begin after awaitForegroundReady but before this CAS.
        awaitForegroundReady()
        if (foregroundWorker != null && foregroundWorker != worker) return@withContext false
        foregroundWorker = worker
        if (scheduledWorker?.token == worker) scheduledWorker = null
        leases.values.forEach { if (!it.expired && !it.released) it.ready.complete(Unit) }
        true
    }

    /** Capture BEFORE publication, then compare inside shared confirmIdle's non-suspending callback. */
    internal suspend fun idleCandidate(worker: String): IdleCandidate? = withContext(Dispatchers.Main.immediate) {
        candidateOnMain(worker)
    }

    private fun candidateOnMain(worker: String): IdleCandidate? =
        if (foregroundWorker == worker && leases.isEmpty() && expirations.isEmpty()) IdleCandidate(worker, leaseEpoch) else null

    /** Main-confined compare only: never enqueue, expire a lease, suspend, or call shared code here. */
    internal fun retire(candidate: IdleCandidate): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (foregroundWorker != candidate.worker || leaseEpoch !== candidate.epoch || leases.isNotEmpty() ||
            expirations.isNotEmpty()) return false
        foregroundWorker = null
        leaseEpoch = Any()
        return true
    }

    /** Gate-only test helper. Production retirement also requires the shared authoritative ticket. */
    internal suspend fun retireIfIdle(worker: String): Boolean = withContext(Dispatchers.Main.immediate) {
        candidateOnMain(worker)?.let(::retire) ?: false
    }

    internal suspend fun failedStart(worker: String): Unit = withContext(NonCancellable) {
        val expired = withContext(Dispatchers.Main.immediate) {
            expirations.firstOrNull { it.worker == worker }?.let { return@withContext it }
            val scheduled = scheduledWorker?.takeIf { it.token == worker } ?: return@withContext null
            scheduledWorker = null
            scheduled.enqueued.completeExceptionally(restricted("download_worker_start_failed"))
            if (foregroundWorker != null) return@withContext null
            expireLeases(worker)
        }
        finishExpiration(expired)
    }

    internal suspend fun foregroundEnded(worker: String): Unit = withContext(NonCancellable) {
        val expired = withContext(Dispatchers.Main.immediate) {
            if (foregroundWorker != worker)
                return@withContext expirations.firstOrNull { it.worker == worker }
            foregroundWorker = null
            leaseEpoch = Any()
            expireLeases(worker)
        }
        finishExpiration(expired)
    }

    private fun expireLeases(worker: String): Expiration {
        val expired = leases.values.filter { !it.released && !it.expired }
        expired.forEach {
            it.expired = true
            it.ready.completeExceptionally(restricted("download_background_allowance_ended"))
        }
        // Zero native leases does not imply zero shared owners: acquisition is registered before
        // acquire(), and allowance.close can precede the final shared Room/file unwind.
        return Expiration(worker, expired).also { expirations += it }
    }

    private suspend fun finishExpiration(expired: Expiration?) = withContext(NonCancellable + Dispatchers.Main.immediate) {
        if (expired == null) return@withContext
        val ownsDrain = withContext(Dispatchers.Main.immediate) {
            if (expired.finishing) false else { expired.finishing = true; true }
        }
        if (!ownsDrain) { expired.finished.await(); return@withContext }
        try {
            // This is the independent worker/enqueue owner, never a transfer's own coroutine.
            var callbackFailure: Throwable? = null
            for (lease in expired.leases) {
                try { lease.onExpiration() }
                catch (error: Throwable) {
                    // Even an unknown callback failure must not skip the independent owner join.
                    // Only a known typed shared failure may be covered by the later drain ACK.
                    if (error !is VeyraException && callbackFailure == null) callbackFailure = error
                }
            }
            // Expiration callbacks only request cancellation and deliberately cannot self-join.
            // The typed ACK is returned only after ALL registered native/file/Room owners join;
            // a known post-drain checkpointFailure remains visible in shared state. No exception
            // is converted to an ACK, and leaseCount zero cannot discharge this barrier instead.
            val acknowledgement: DownloadBackgroundDrainAck = drainOwners()
            callbackFailure?.let { throw it }
            finishAcknowledgedExpiration(expired, acknowledgement)
        } catch (_: Throwable) {
            val failure = AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_failed_restart_required")
            withContext(Dispatchers.Main.immediate) {
                expired.failure = failure
                expired.finished.completeExceptionally(VeyraException(failure))
                // Retain the exact expiration: foreground reentry must fail closed without a
                // native owner drain ACK, rather than resuming into possibly live old writers.
            }
            throw VeyraException(failure)
        }
    }

    private fun finishAcknowledgedExpiration(expired: Expiration, acknowledgement: DownloadBackgroundDrainAck) {
        // Already on Main after the ACK; no dispatch/suspension can permit foreground reentry
        // between shared acknowledgement and native barrier completion. checkpointFailure is a
        // known post-drain shared failure, not a successful worker result or an invented drain.
        check(Looper.myLooper() == Looper.getMainLooper())
        expired.leases.forEach(::releaseOnMain)
        expirations.remove(expired)
        expired.finished.complete(acknowledgement)
    }

    /** Main-only. A completed failed drain is a typed restriction, not an endless pending wait. */
    private fun requireNoExpiration() {
        expirations.firstNotNullOfOrNull { it.failure }?.let { throw VeyraException(it) }
        if (expirations.isNotEmpty()) throw restricted("download_allowance_retiring")
    }

    private fun restricted(detail: String) = VeyraException(AppFailure(FailureCode.BACKGROUND_RESTRICTED, detail))

    companion object {
        const val WORK_NAME = "veyra-central-downloads"
        private const val ENQUEUE_ACK_TIMEOUT_MS = 10_000L
        private const val MAX_SCHEDULE_CHECKS = 3
    }
}
