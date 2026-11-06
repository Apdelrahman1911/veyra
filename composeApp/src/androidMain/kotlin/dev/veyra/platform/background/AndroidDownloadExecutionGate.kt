package dev.veyra.platform.background

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import dev.veyra.downloads.DownloadExecutionGate
import dev.veyra.downloads.DownloadExecutionLease
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
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
 */
class AndroidDownloadExecutionGate internal constructor(
    private val enqueue: suspend (OneTimeWorkRequest) -> Unit,
) : DownloadExecutionGate {
    constructor(context: Context) : this({ work ->
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work,
            ).result.await()
        }
    })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val leases = linkedMapOf<Long, Lease>()
    private var sequence = 0L
    private var foregroundWorker: String? = null
    private var scheduledWorker: String? = null
    private val expirations = mutableSetOf<Expiration>()
    private val mutableLeaseCount = MutableStateFlow(0)
    internal val leaseCount: StateFlow<Int> = mutableLeaseCount.asStateFlow()

    private class Lease(val id: Long, val onExpiration: suspend () -> Unit) {
        val ready = CompletableDeferred<Unit>()
        var released = false
        var expired = false
    }
    private class Expiration(val leases: List<Lease>) {
        val finished = CompletableDeferred<Unit>()
    }

    override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
        var created: Lease? = null
        try {
            val lease = withContext(Dispatchers.Main.immediate) {
                if (expirations.isNotEmpty()) throw restricted("download_allowance_retiring")
                Lease(++sequence, onExpiration).also {
                    created = it // Retain if prompt cancellation wins at withContext's return edge.
                    leases[it.id] = it
                    mutableLeaseCount.value = leases.size
                    if (foregroundWorker != null) it.ready.complete(Unit) else scheduleWorker()
                }
            }
            withTimeout(20_000) { lease.ready.await() }
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
        if (lease.released) return@withContext
        lease.released = true
        leases.remove(lease.id)
        mutableLeaseCount.value = leases.size
    }

    private fun scheduleWorker() {
        if (scheduledWorker != null || foregroundWorker != null) return
        val work = OneTimeWorkRequestBuilder<VeyraDownloadWorker>().addTag(WORK_NAME).build()
        scheduledWorker = work.id.toString()
        scope.launch {
            try {
                // KEEP loses acquisitions that arrive just as a prior worker exits. A successor
                // appended after atomic idle-claim survives that race, even across process death.
                enqueue(work)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failedStart(work.id.toString()) }
        }
    }

    /** Called only after WorkManager has confirmed its dataSync foreground service. */
    internal suspend fun foregroundStarted(worker: String): Boolean {
        awaitForegroundReady()
        return withContext(Dispatchers.Main.immediate) {
            if (foregroundWorker != null && foregroundWorker != worker) return@withContext false
            foregroundWorker = worker
            if (scheduledWorker == worker) scheduledWorker = null
            leases.values.forEach { if (!it.expired && !it.released) it.ready.complete(Unit) }
            true
        }
    }

    /** A later acquire either prevents this claim or schedules a durable successor, never vanishes. */
    internal suspend fun retireIfIdle(worker: String): Boolean = withContext(Dispatchers.Main.immediate) {
        if (foregroundWorker != worker || leases.isNotEmpty()) return@withContext false
        foregroundWorker = null
        true
    }

    internal suspend fun failedStart(worker: String): Unit = withContext(NonCancellable) {
        val expired = withContext(Dispatchers.Main.immediate) {
            if (scheduledWorker != worker && scheduledWorker != null) return@withContext null
            if (scheduledWorker == worker) scheduledWorker = null
            if (foregroundWorker != null) return@withContext null
            expireLeases()
        }
        finishExpiration(expired)
    }

    internal suspend fun foregroundEnded(worker: String): Unit = withContext(NonCancellable) {
        val expired = withContext(Dispatchers.Main.immediate) {
            if (foregroundWorker != worker) return@withContext null
            foregroundWorker = null
            expireLeases()
        }
        finishExpiration(expired)
    }

    private fun expireLeases(): Expiration? {
        val expired = leases.values.filter { !it.released && !it.expired }
        expired.forEach {
            it.expired = true
            it.ready.completeExceptionally(restricted("download_background_allowance_ended"))
        }
        return expired.takeIf { it.isNotEmpty() }?.let { Expiration(it).also { batch -> expirations += batch } }
    }

    private suspend fun finishExpiration(expired: Expiration?) = withContext(NonCancellable) {
        if (expired == null) return@withContext
        try {
            // This is the independent worker/enqueue owner, never a transfer's own coroutine.
            for (lease in expired.leases) {
                try { lease.onExpiration() }
                catch (_: Exception) { /* The root coordinator retains its typed recovery failure. */ }
                finally { release(lease) }
            }
        } finally {
            withContext(Dispatchers.Main.immediate) {
                expirations.remove(expired)
                expired.finished.complete(Unit)
            }
        }
    }

    private fun restricted(detail: String) = VeyraException(AppFailure(FailureCode.BACKGROUND_RESTRICTED, detail))

    companion object { const val WORK_NAME = "veyra-central-downloads" }
}
