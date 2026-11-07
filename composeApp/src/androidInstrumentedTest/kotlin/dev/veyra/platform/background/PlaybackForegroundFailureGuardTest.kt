@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.veyra.platform.background

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.os.Build
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import dev.veyra.platform.audio.QueueSessionPlayer
import dev.veyra.playback.PlaybackResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Injected concrete API failures, not a claim that Android itself rejected an actual service. */
@RunWith(AndroidJUnit4::class)
class PlaybackForegroundFailureGuardTest {
    @Test fun explicitPolicyRejectionRetiresBeforeSilencingAndReturnsOnlyASanitizedFailure(): Unit = onMain {
        val calls = mutableListOf<String>()
        var reported: AppFailure? = null
        val guard = PlaybackForegroundFailureGuard(
            retireLocal = { calls += "cancel-local-work" },
            detachOwner = { calls += "detach-owner"; true },
            silenceOwner = { calls += "silence-native"; reported = it },
            removeOwnedSession = { calls += "remove-session" },
            stopService = { calls += "stop-service" },
        )
        val raw = if (Build.VERSION.SDK_INT >= 31) ForegroundServiceStartNotAllowedException(PRIVATE_DETAIL)
            else SecurityException(PRIVATE_DETAIL)
        try { guard.requireForeground { throw raw }; fail("The rejected start was acknowledged") }
        catch (failure: VeyraException) {
            assertEquals(FailureCode.BACKGROUND_RESTRICTED, failure.failure.code)
            assertEquals("playback_foreground_service", failure.failure.detail)
            assertNull("Do not retain the OS's private cause", failure.cause)
            assertFalse(failure.toString().contains(PRIVATE_DETAIL))
            assertEquals(failure.failure, reported)
        }
        assertEquals(listOf("cancel-local-work", "detach-owner", "silence-native", "remove-session", "stop-service"), calls)
        assertTrue(guard.retired)
        guard.rejected(); guard.destroyed()
        assertFalse(guard.attempt { fail("Retired language/hold work must not run") })
        assertEquals(5, calls.size)
    }

    @Test fun staleServiceFailureCannotDetachSilenceOrRemoveTheSuccessorSession(): Unit = onMain {
        // These are unattached service identities only: no onCreate, service start or OS ACK.
        val gate = PlaybackForegroundGate(ApplicationProvider.getApplicationContext<Context>())
        val old = PlaybackService()
        val successor = PlaybackService()
        gate.attached(old)
        gate.attached(successor)
        val calls = mutableListOf<String>()
        val guard = PlaybackForegroundFailureGuard(
            retireLocal = { calls += "cancel-old-work" },
            detachOwner = { gate.detached(old) },
            silenceOwner = { fail("A stale callback silenced the successor") },
            removeOwnedSession = { fail("A stale callback removed the successor's Media3 listener") },
            stopService = { calls += "stop-old-service" },
        )
        assertFalse(guard.attempt { throw SecurityException(PRIVATE_DETAIL) })
        guard.rejected(); guard.destroyed()
        assertEquals(listOf("cancel-old-work", "stop-old-service"), calls)
        assertTrue("The successor must still own the real foreground gate", gate.detached(successor))
        assertFalse(gate.detached(old))
    }

    @Test fun notificationSecurityFailureIsHandledButUnrelatedErrorsAndCancellationAreNotSwallowed(): Unit = onMain {
        val reports = mutableListOf<AppFailure?>()
        val guard = PlaybackForegroundFailureGuard({}, { true }, { reports += it }, {}, {})
        for (unexpected in listOf(IllegalStateException("not an FGS policy error"),
            IllegalArgumentException("invalid notification programming contract"), CancellationException("cancelled refresh"))) {
            try { guard.attempt { throw unexpected }; fail("Unrelated failure was swallowed") }
            catch (failure: Exception) { assertSame(unexpected, failure) }
            assertFalse(guard.retired)
            assertTrue(reports.isEmpty())
        }
        assertFalse(guard.attempt { throw SecurityException(PRIVATE_DETAIL) })
        assertEquals(listOf(playbackForegroundFailure()), reports)
        assertTrue(guard.retired)
    }

    @Test @SdkSuppress(minSdkVersion = 31)
    fun frameworkPolicyFailuresAreSanitizedAndRetiredForegroundRequestsStillReject(): Unit = onMain {
        for (raw in listOf(SecurityException(PRIVATE_DETAIL), ForegroundServiceStartNotAllowedException(PRIVATE_DETAIL))) {
            var silenced = 0
            val guard = PlaybackForegroundFailureGuard({}, { true }, {
                assertEquals(playbackForegroundFailure(), it)
                silenced++
            }, {}, {})
            repeat(2) { attempt ->
                try {
                    guard.frameworkUpdate(startInForegroundRequired = true) {
                        assertEquals("A retired framework request executed notification work", 0, attempt)
                        throw raw
                    }
                    fail("Media3 must receive a recognizable refusal, not successful Unit")
                } catch (signal: ForegroundServiceStartNotAllowedException) {
                    assertNotSame(raw, signal)
                    assertEquals("playback_foreground_service", signal.message)
                    assertNull("Media3 logs this exception; never retain the OS's cause", signal.cause)
                    assertFalse(signal.toString().contains(PRIVATE_DETAIL))
                }
            }
            assertEquals(1, silenced)
            assertTrue(guard.retired)
        }
        // Normalizing policy failures must not relabel a programming error or cancellation.
        val guard = PlaybackForegroundFailureGuard({}, { true }, {}, {}, {})
        for (unexpected in listOf(IllegalStateException("programming error"), CancellationException("cancelled"))) {
            try { guard.frameworkUpdate(true) { throw unexpected }; fail("Unexpected error swallowed") }
            catch (error: Exception) { assertSame(unexpected, error) }
            assertFalse(guard.retired)
        }
    }

    @Test @SdkSuppress(minSdkVersion = 31)
    fun pinnedMedia3InternalBoundaryReturnsFalseForBothRecognizedPolicyFailures(): Unit = onMain {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val player = QueueSessionPlayer(scope, { PlaybackResult.Accepted })
        val session = MediaSession.Builder(context, player).setId("foreground-refusal-probe-${UUID.randomUUID()}").build()
        try {
            // Explicitly exercise the pinned library's real catch/boolean path. This context-only
            // service is not attached to Android, has no sessions added, and starts no OS service.
            val refresh = MediaSessionService::class.java.getDeclaredMethod(
                "onUpdateNotificationInternal", MediaSession::class.java, Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            for (raw in listOf(SecurityException(PRIVATE_DETAIL), ForegroundServiceStartNotAllowedException(PRIVATE_DETAIL))) {
                var silenced = false
                val guard = PlaybackForegroundFailureGuard({}, { true }, { silenced = true }, {}, {})
                val service = RefusalProbeService(context, guard, raw)
                try {
                    assertEquals("Media3 reported successful notification/Play admission", false,
                        refresh.invoke(service, session, true))
                    assertTrue(silenced)
                    assertTrue(guard.retired)
                } finally { service.onDestroy() }
            }
        } finally { session.release(); player.release(); scope.cancel() }
    }

    private class RefusalProbeService(
        context: Context,
        private val guard: PlaybackForegroundFailureGuard,
        private val refusal: Exception,
    ) : MediaSessionService() {
        init { attachBaseContext(context) }
        override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = null
        override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
            guard.frameworkUpdate(startInForegroundRequired) { throw refusal }
        }
    }

    private fun onMain(block: () -> Unit): Unit = runBlocking { withContext(Dispatchers.Main.immediate) { block() } }

    private companion object { const val PRIVATE_DETAIL = "authored-private-notification-content" }
}
