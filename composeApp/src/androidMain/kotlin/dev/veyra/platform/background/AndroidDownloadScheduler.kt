package dev.veyra.platform.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.veyra.downloads.DownloadScheduling
import dev.veyra.downloads.DownloadWakePlan
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class DownloadWakeKind(val workName: String) {
    LOCAL("veyra-download-wake-local-v1"),
    NETWORK("veyra-download-wake-network-v1"),
}

/** A running wake is NEVER future durable coverage for itself after it returns. */
internal data class DownloadWakeIdentity(val kind: DownloadWakeKind, val worker: String)
internal data class DownloadWakeRecord(
    val worker: String,
    val state: WorkInfo.State,
    val requestedEpochMs: Long?,
    val nextScheduleEpochMs: Long,
    val runAttemptCount: Int = 0,
)

/** Injected only for finite protocol tests; production operations all await WorkManager's ACK. */
internal interface DownloadWorkBackend {
    suspend fun installRepair()
    suspend fun wakes(kind: DownloadWakeKind): List<DownloadWakeRecord>
    suspend fun replaceWake(kind: DownloadWakeKind, epochMs: Long, nowEpochMs: Long)
}

internal data class DownloadPublication(val plan: DownloadWakePlan, val retryCurrentWake: Boolean = false)

/**
 * Native scheduling only. Room's normalized intent is the source of truth, not WorkManager input.
 * Neither this publisher nor the repair/wake workers call a source, hash a file, or own a transfer.
 * One always-installed LOCAL periodic repair closes the two-database commit/enqueue crash window;
 * it is not an exact alarm or a promise of execution within fifteen minutes under OS restrictions.
 */
class AndroidDownloadScheduler internal constructor(
    private val backend: DownloadWorkBackend,
    private val requestForeground: suspend () -> Unit,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val operationTimeoutMs: Long = 10_000,
    private val publicationTimeoutMs: Long = 20_000,
    private val convergenceRounds: Int = 4,
) {
    constructor(context: Context, gate: AndroidDownloadExecutionGate) : this(
        WorkManagerDownloadBackend(context.applicationContext), gate::requestForegroundWork,
    )

    private val coverageLock = Mutex()
    private val publicationLock = Mutex()
    private var coverageAcknowledged = false

    init { require(operationTimeoutMs > 0 && publicationTimeoutMs > 0 && convergenceRounds in 1..16) }

    /** No autonomous admission/activation may assume coverage before this returns successfully. */
    suspend fun ensureInstalled() = coverageLock.withLock {
        if (coverageAcknowledged) return@withLock
        try {
            bounded("download_repair_install_timeout") { backend.installRepair() }
            coverageAcknowledged = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw schedulingFailure("download_repair_install_failed") }
    }

    /** Periodic repair NEVER directly requests a foreground worker, even when a deadline is due. */
    internal suspend fun repair(scheduling: DownloadScheduling): DownloadPublication =
        publish(scheduling, PublicationOwner.REPAIR)

    /** A due one-time wake delegates only an immediate, durably enqueued foreground worker. */
    internal suspend fun wake(scheduling: DownloadScheduling, identity: DownloadWakeIdentity): DownloadPublication =
        publish(scheduling, PublicationOwner.WAKE, identity)

    /** Called while the original worker still owns its real foreground allowance, before retire. */
    internal suspend fun beforeRetirement(scheduling: DownloadScheduling): DownloadPublication =
        publish(scheduling, PublicationOwner.FOREGROUND)

    private enum class PublicationOwner { REPAIR, WAKE, FOREGROUND }

    private suspend fun publish(
        scheduling: DownloadScheduling,
        owner: PublicationOwner,
        identity: DownloadWakeIdentity? = null,
    ): DownloadPublication {
        try {
            return withTimeoutOrNull(publicationTimeoutMs) {
                publicationLock.withLock {
                    ensureInstalled()
                    var plan = bounded("download_plan_read_timeout") { scheduling.schedulingPlan() }
                    repeat(convergenceRounds) {
                        val now = nowEpochMs().coerceAtLeast(0)
                        var retryCurrentWake = false
                        // A real foreground owner covers due work while it runs. A wake must await
                        // enqueue/foreground ACK first; the periodic repair only publishes a wake.
                        val foregroundCoversDue = owner != PublicationOwner.REPAIR && plan.hasDueWork
                        if (foregroundCoversDue && owner == PublicationOwner.WAKE) {
                            bounded("download_foreground_enqueue_timeout") { requestForeground() }
                        }
                        for ((kind, deadline) in listOf(
                            DownloadWakeKind.LOCAL to plan.nextLocalEpochMs,
                            DownloadWakeKind.NETWORK to plan.nextNetworkEpochMs,
                        )) {
                            if (deadline == null) continue
                            if (deadline < 0) throw schedulingFailure("download_wake_invalid_deadline")
                            if (foregroundCoversDue && deadline <= now) continue
                            if (owner == PublicationOwner.WAKE && identity?.kind == kind && deadline <= now && !plan.hasDueWork) {
                                // Usually CONNECTED in WorkManager but UNKNOWN/disconnected in the
                                // app's new connectivity observation. Replacing ourselves at zero
                                // delay would hot-loop. Do not count RUNNING as future coverage or
                                // return Success: the bounded wake-only backoff + permanent repair
                                // handles this without bypassing network/pause/NeedsForeground policy.
                                retryCurrentWake = true
                                continue
                            }
                            val existing = bounded("download_wake_read_timeout") { backend.wakes(kind) }
                            if (existing.any { it.covers(deadline) && it.worker != identity?.worker }) continue
                            // REPLACE affects ONLY a wake lane. It may cancel this running wake,
                            // so finish its bounded enqueue ACK even if that cancellation arrives.
                            // Cancellation still propagates afterwards; no Success is manufactured.
                            withContext(NonCancellable) {
                                bounded("download_wake_enqueue_timeout") { backend.replaceWake(kind, deadline, now) }
                            }
                            currentCoroutineContext().ensureActive()
                        }
                        // WorkManager and Room have separate transactions. A stale publisher must
                        // not overwrite a newer/earlier relevant plan and then claim it is current.
                        // Compare the whole plan too: a clock crossing can change due eligibility.
                        val latest = bounded("download_plan_read_timeout") { scheduling.schedulingPlan() }
                        if (latest == plan) return@withLock DownloadPublication(latest, retryCurrentWake)
                        plan = latest
                    }
                    throw schedulingFailure("download_wake_plan_changed")
                }
            } ?: throw schedulingFailure("download_wake_publication_timeout")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: VeyraException) { throw error }
        catch (_: Exception) { throw schedulingFailure("download_wake_publication_failed") }
    }

    private fun DownloadWakeRecord.covers(deadline: Long): Boolean {
        if (state != WorkInfo.State.ENQUEUED || requestedEpochMs == null || requestedEpochMs > deadline) return false
        // The requested timestamp, not enqueue latency, identifies a never-run request. Retry
        // backoff may be later than a newly due/earlier plan and must not conceal that deadline.
        return runAttemptCount == 0 || nextScheduleEpochMs <= deadline
    }

    private data class Completed<T>(val value: T)
    private suspend fun <T> bounded(detail: String, action: suspend () -> T): T =
        (withTimeoutOrNull(operationTimeoutMs) { Completed(action()) }
            ?: throw schedulingFailure(detail)).value

    companion object {
        const val REPAIR_WORK_NAME = "veyra-download-repair-v1"
        internal const val PROTOCOL_VERSION = 1
        internal const val INPUT_VERSION = "download_wake_version"
        internal const val INPUT_KIND = "download_wake_kind"
        internal const val INPUT_EPOCH_MS = "download_wake_epoch_ms"
        internal const val DEADLINE_TAG = "veyra-download-wake-at:"
        internal const val WAKE_TAG = "veyra-download-wake-v1"
        internal const val REPAIR_KIND = "REPAIR"
        internal const val REPAIR_MINUTES = 15L

        internal fun wakeInput(kind: DownloadWakeKind, epochMs: Long): Data = workDataOf(
            INPUT_VERSION to PROTOCOL_VERSION, INPUT_KIND to kind.name, INPUT_EPOCH_MS to epochMs,
        )

        internal fun wakeIdentity(data: Data, worker: String): DownloadWakeIdentity? {
            if (data.getInt(INPUT_VERSION, -1) != PROTOCOL_VERSION || data.getLong(INPUT_EPOCH_MS, -1) < 0) return null
            val kind = DownloadWakeKind.entries.firstOrNull { it.name == data.getString(INPUT_KIND) } ?: return null
            return DownloadWakeIdentity(kind, worker)
        }

        internal fun repairInput(): Data = workDataOf(INPUT_VERSION to PROTOCOL_VERSION, INPUT_KIND to REPAIR_KIND)
        internal fun validRepairInput(data: Data): Boolean =
            data.getInt(INPUT_VERSION, -1) == PROTOCOL_VERSION && data.getString(INPUT_KIND) == REPAIR_KIND
    }
}

private class WorkManagerDownloadBackend(private val context: Context) : DownloadWorkBackend {
    private val manager get() = WorkManager.getInstance(context)

    override suspend fun installRepair(): Unit = withContext(Dispatchers.IO) {
        manager.enqueueUniquePeriodicWork(AndroidDownloadScheduler.REPAIR_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, downloadRepairRequest())
            .result.await()
        Unit
    }

    override suspend fun wakes(kind: DownloadWakeKind): List<DownloadWakeRecord> = withContext(Dispatchers.IO) {
        manager.getWorkInfosForUniqueWork(kind.workName).await().filter { work ->
            !work.state.isFinished && AndroidDownloadScheduler.WAKE_TAG in work.tags &&
                work.constraints.requiredNetworkType ==
                    (if (kind == DownloadWakeKind.NETWORK) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
        }.map { work ->
            val deadline = work.tags.singleOrNull { it.startsWith(AndroidDownloadScheduler.DEADLINE_TAG) }
                ?.removePrefix(AndroidDownloadScheduler.DEADLINE_TAG)?.toLongOrNull()?.takeIf { it >= 0 }
            DownloadWakeRecord(work.id.toString(), work.state, deadline, work.nextScheduleTimeMillis, work.runAttemptCount)
        }
    }

    override suspend fun replaceWake(kind: DownloadWakeKind, epochMs: Long, nowEpochMs: Long): Unit = withContext(Dispatchers.IO) {
        manager.enqueueUniqueWork(kind.workName, ExistingWorkPolicy.REPLACE, downloadWakeRequest(kind, epochMs, nowEpochMs)).result.await()
        Unit
    }
}

/** Factories are exposed internally to inspect the real WorkRequest specs without running workers. */
internal fun downloadRepairRequest(): PeriodicWorkRequest =
    PeriodicWorkRequestBuilder<VeyraDownloadRepairWorker>(AndroidDownloadScheduler.REPAIR_MINUTES, TimeUnit.MINUTES)
        .setInputData(AndroidDownloadScheduler.repairInput())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .addTag(AndroidDownloadScheduler.REPAIR_WORK_NAME)
        .build()

internal fun downloadWakeRequest(kind: DownloadWakeKind, epochMs: Long, nowEpochMs: Long): OneTimeWorkRequest {
    require(epochMs >= 0 && nowEpochMs >= 0)
    // Inputs contain only a version/kind and safe timestamp. Never serialize a media row,
    // source URL, cookies, access credentials, or the coordinator's process-local ticket.
    val delayMs = if (epochMs <= nowEpochMs) 0 else epochMs - nowEpochMs
    return OneTimeWorkRequestBuilder<VeyraDownloadWakeWorker>()
        .setInputData(AndroidDownloadScheduler.wakeInput(kind, epochMs))
        .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(
            if (kind == DownloadWakeKind.NETWORK) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED,
        ).build())
        .addTag(AndroidDownloadScheduler.WAKE_TAG)
        .addTag(kind.workName)
        .addTag(AndroidDownloadScheduler.DEADLINE_TAG + epochMs)
        .build()
}

internal fun schedulingFailure(detail: String) =
    VeyraException(AppFailure(FailureCode.BACKGROUND_RESTRICTED, detail))
