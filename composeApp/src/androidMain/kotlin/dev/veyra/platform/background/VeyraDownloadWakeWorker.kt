package dev.veyra.platform.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.veyra.app.VeyraApplication
import dev.veyra.model.FailureCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Wake-only, no foreground/transfer/validation. WorkManager input is a hint, never durable intent. */
class VeyraDownloadWakeWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val identity = AndroidDownloadScheduler.wakeIdentity(inputData, id.toString())
            ?: return downloadSchedulingFailed("invalid_wake")
        return try {
            withTimeoutOrNull(SCHEDULING_WORK_TIMEOUT_MS) {
                val app = applicationContext as VeyraApplication
                val container = app.awaitSchedulingContainer()
                val publication = app.downloadScheduling.wake(container.downloads, identity)
                if (publication.retryCurrentWake) downloadSchedulingRetry(runAttemptCount) else Result.success()
            } ?: downloadSchedulingRetry(runAttemptCount)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { downloadSchedulingRetry(runAttemptCount) }
    }
}

/** Persistent bounded LOCAL repair; it never activates the coordinator or directly starts an FGS. */
class VeyraDownloadRepairWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!AndroidDownloadScheduler.validRepairInput(inputData)) return downloadSchedulingFailed("invalid_repair")
        return try {
            withTimeoutOrNull(SCHEDULING_WORK_TIMEOUT_MS) {
                val app = applicationContext as VeyraApplication
                val container = app.awaitSchedulingContainer()
                app.downloadScheduling.repair(container.downloads)
                Result.success()
            } ?: downloadSchedulingRetry(runAttemptCount)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { downloadSchedulingRetry(runAttemptCount) }
    }
}

// Cancellation-safe self-replacement may additionally drain its current bounded ten-second ACK.
private const val SCHEDULING_WORK_TIMEOUT_MS = 30_000L

/** Only wake/repair lanes use retry. A delayed foreground-chain leaf would strand new acquires. */
internal fun downloadSchedulingRetry(attempt: Int): ListenableWorker.Result =
    if (attempt < 3) ListenableWorker.Result.retry() else downloadSchedulingFailed("wake_publication")

/** Fixed, sanitized output; do not serialize exception text, source identifiers or download rows. */
internal fun downloadSchedulingFailed(stage: String): ListenableWorker.Result = ListenableWorker.Result.failure(
    workDataOf("failure_code" to FailureCode.BACKGROUND_RESTRICTED.name, "download_worker_stage" to stage),
)
