package dev.veyra.platform.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.veyra.app.R
import dev.veyra.app.VeyraApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Persistent OS execution wrapper. It never resolves sources or transfers any media bytes. */
class VeyraDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val app get() = applicationContext as VeyraApplication

    override suspend fun doWork(): Result = coroutineScope {
        val token = id.toString()
        val gate = app.downloadExecution
        var foreground = false
        var labelsJob: Job? = null
        try {
            try {
                setForeground(foregroundInfo())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                gate.failedStart(token)
                return@coroutineScope Result.failure()
            }
            foreground = gate.foregroundStarted(token)
            if (!foreground) return@coroutineScope Result.failure()
            labelsJob = launch {
                app.nativeLanguage.collect {
                    // Updating an existing notification does not acquire or replace an OS lease.
                    // If the OS rejects a label refresh, the successfully posted FGS notice remains.
                    try {
                        (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                            .notify(NOTIFICATION_ID, foregroundInfo().notification)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Nonessential refresh; keep the existing notice. */ }
                }
            }
            val coordinator = app.awaitContainer().downloads
            // This does not resume explicit user-paused records. There is only one coordinator.
            coordinator.resumePending()
            while (true) {
                combine(coordinator.hasPendingWork, gate.leaseCount) { pending, leases -> !pending && leases == 0 }
                    .first { it }
                // Let the eagerly-derived shared StateFlow settle after bootstrap/finalization.
                delay(1_000)
                if (!coordinator.hasPendingWork.value && gate.retireIfIdle(token)) break
            }
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@coroutineScope Result.failure() }
        finally {
            withContext(NonCancellable) {
                labelsJob?.cancelAndJoin()
                if (foreground) gate.foregroundEnded(token) else gate.failedStart(token)
            }
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val labels = app.nativeLabelsContext()
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = manager.getNotificationChannel(CHANNEL_ID)
                ?: NotificationChannel(CHANNEL_ID, labels.getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { setSound(null, null); enableVibration(false) }
            channel.name = labels.getString(R.string.download_channel)
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(labels, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(labels.getString(R.string.download_channel))
            .setContentText(labels.getString(R.string.download_active))
            .setContentIntent(app.activityIntent()).setOnlyAlertOnce(true).setOngoing(true).setSilent(true)
            .setProgress(0, 0, true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "veyra_downloads"
        const val NOTIFICATION_ID = 4102
    }
}
