package dev.veyra.platform.background

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.veyra.app.MainActivity
import dev.veyra.app.VeyraApplication
import dev.veyra.downloads.DownloadExecutionLease
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real application gate, WorkManager database/worker and OS foreground notification, across
 * a real Activity onStop. Unlike AndroidDownloadExecutionGateTest, this never supplies worker ACKs.
 * Use a disposable, empty test installation: no transfer/source is faked or started by this test.
 * This is not a quota/force-stop/long-background test, media-download proof or physical-audio proof.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class AndroidWorkManagerLeaseTest {
    @Test(timeout = 180_000)
    fun realForegroundLeaseSurvivesActivityStopAndWorkerSucceedsAfterClose(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<VeyraApplication>()
        val manager = WorkManager.getInstance(app)
        val notifications = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val gate = app.downloadExecution
        val expirations = AtomicInteger()
        permitTestAppNotifications(app)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val container = withTimeout(30_000) { app.awaitContainer() }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertTrue("Requires an empty disposable download library; do not delete user records",
                container.store.state.value.downloads.isEmpty())
            assertTrue("Requires no saved finalizations or background transfers",
                container.store.state.value.downloadFinalizations.isEmpty())

            // Startup maintenance must have retired before identifying this test's worker. Never
            // cancel unique work globally, prune its history, replace WorkManager, or edit Room.
            val before = awaitStage("idle before acquisition") {
                manager.getWorkInfosForUniqueWork(AndroidDownloadExecutionGate.WORK_NAME).await().takeIf { work ->
                    gate.leaseCount.value == 0 && !container.downloads.hasPendingWork.value &&
                        work.all { it.state.isFinished } && notifications.downloadNotification() == null
                }
            }.map { it.id }.toSet()
            Log.i(TAG, "Idle baseline: no active worker, lease or foreground notification")

            var ownedLease: DownloadExecutionLease? = null
            var ownedWork: UUID? = null
            var primaryFailure: Throwable? = null
            try {
                withTimeout(25_000) {
                    // acquire returns only after VeyraDownloadWorker's real setForeground call
                    // and foregroundStarted ACK. Retain ownership inside the timeout boundary.
                    ownedLease = gate.acquire { expirations.incrementAndGet(); Unit }
                }
                assertEquals("The real worker must acknowledge exactly this lease", 1, gate.leaseCount.value)
                assertEquals(0, expirations.get())

                val running = awaitStage("acknowledged worker RUNNING and OS foreground notice") {
                    val newWork = manager.getWorkInfosForUniqueWork(AndroidDownloadExecutionGate.WORK_NAME)
                        .await().filter { it.id !in before }
                    assertTrue("Unrelated work appeared in the empty test installation", newWork.size <= 1)
                    val work = newWork.singleOrNull()
                    if (work != null) {
                        ownedWork = work.id // Keep identity for bounded failure-path cleanup too.
                        assertFalse("Worker ended while its acknowledged lease was held: ${work.state}",
                            work.state.isFinished)
                    }
                    work?.takeIf { it.state == WorkInfo.State.RUNNING && notifications.hasDownloadForegroundNotice() }
                }
                assertTrue(running.tags.contains(AndroidDownloadExecutionGate.WORK_NAME))
                Log.i(TAG, "Acquire ACK: real WorkManager RUNNING and OS foreground/ongoing flags observed")

                // ActivityScenario drives the actual Activity lifecycle on Main; the test thread
                // never blocks Main or calls app.onHidden/foregroundStarted directly.
                scenario.moveToState(Lifecycle.State.CREATED)
                assertEquals(Lifecycle.State.CREATED, scenario.state)
                Log.i(TAG, "Activity stopped; checking the same acknowledged worker while hidden")
                withTimeout(15_000) {
                    val hiddenAt = SystemClock.elapsedRealtime()
                    do {
                        assertEquals(Lifecycle.State.CREATED, scenario.state)
                        assertEquals(1, gate.leaseCount.value)
                        assertEquals(0, expirations.get())
                        val current = manager.getWorkInfoById(running.id).await()
                        assertNotNull("The held worker disappeared", current)
                        assertEquals("The same worker must stay RUNNING after onStop", WorkInfo.State.RUNNING, current!!.state)
                        assertTrue("OS foreground notification disappeared while the hidden lease was held",
                            notifications.hasDownloadForegroundNotice())
                        assertFalse("This lease-only test must not start a transfer", container.downloads.hasPendingWork.value)
                        delay(100)
                    } while (SystemClock.elapsedRealtime() - hiddenAt < 2_000)
                }

                val acknowledged = requireNotNull(ownedLease)
                withTimeout(5_000) { acknowledged.close(); acknowledged.close() }
                assertEquals("Duplicate close must release exactly once", 0, gate.leaseCount.value)
                Log.i(TAG, "Lease closed twice; waiting for this worker's success and foreground teardown")
                awaitStage("same worker SUCCEEDED and foreground notification removed") {
                    val current = manager.getWorkInfoById(running.id).await()
                    assertNotNull("The worker result disappeared", current)
                    assertFalse("Worker must succeed, not fail/cancel, after normal lease close",
                        current!!.state in setOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED))
                    current.takeIf { it.state == WorkInfo.State.SUCCEEDED && notifications.downloadNotification() == null }
                }
                assertEquals(0, expirations.get())
                assertEquals(0, gate.leaseCount.value)
                assertEquals(Lifecycle.State.CREATED, scenario.state)
                assertTrue(container.store.state.value.downloads.isEmpty())
                Log.i(TAG, "Verified SUCCEEDED, zero leases, no expiration and no download foreground notice")
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                // Close only our lease and Activity. Wait for our identified work to retire, but
                // never cancel another worker or close the process-wide application container.
                try {
                    withContext(NonCancellable) {
                        withTimeout(15_000) {
                            ownedLease?.close()
                            ownedWork?.let { id ->
                                awaitStage("owned worker retired during cleanup") {
                                    manager.getWorkInfoById(id).await()?.takeIf { it.state.isFinished }
                                }
                            }
                        }
                    }
                } catch (cleanupFailure: Throwable) {
                    val original = primaryFailure
                    if (original != null) original.addSuppressed(cleanupFailure) else throw cleanupFailure
                }
            }
        }
    }

    private fun permitTestAppNotifications(app: VeyraApplication) {
        // API31 needs no runtime grant. On API33+ explicitly grant this disposable app's declared
        // permission; do not silently skip the OS-notification assertions. Revocation would kill
        // the running instrumented app, so removal is left to test-installation teardown.
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        assertTrue("Notifications must be enabled for the disposable test app",
            NotificationManagerCompat.from(app).areNotificationsEnabled())
    }

    private fun NotificationManager.downloadNotification() = activeNotifications.singleOrNull {
        it.id == VeyraDownloadWorker.NOTIFICATION_ID && it.packageName == "dev.veyra.app"
    }?.notification

    private fun NotificationManager.hasDownloadForegroundNotice(): Boolean {
        val notification = downloadNotification() ?: return false
        val required = Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_ONGOING_EVENT
        return notification.flags and required == required &&
            (Build.VERSION.SDK_INT < 26 || notification.channelId == VeyraDownloadWorker.CHANNEL_ID)
    }

    private suspend fun <T : Any> awaitStage(label: String, timeoutMs: Long = 25_000, probe: suspend () -> T?): T {
        Log.i(TAG, "Awaiting: $label (bounded ${timeoutMs}ms)")
        return withTimeout(timeoutMs) {
            var observed = probe()
            while (observed == null) {
                delay(100)
                observed = probe()
            }
            observed
        }
    }

    private companion object { const val TAG = "VeyraWorkLeaseTest" }
}
