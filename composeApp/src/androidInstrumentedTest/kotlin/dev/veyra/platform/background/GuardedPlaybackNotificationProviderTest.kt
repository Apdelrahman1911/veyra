@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.veyra.platform.background

import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.util.BitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dev.veyra.model.AppFailure
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.QueueContext
import dev.veyra.model.QueueEntry
import dev.veyra.model.QueueSnapshot
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.platform.audio.PlaybackNotificationProvider
import dev.veyra.platform.audio.QueueSessionPlayer
import dev.veyra.playback.PlaybackPhase
import dev.veyra.playback.PlaybackResult
import dev.veyra.playback.PlaybackState
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real default-provider notification construction and its posted bitmap-completion callback;
 * bitmap completion and API failures are injected. No notification is posted or service started.
 */
@RunWith(AndroidJUnit4::class)
class GuardedPlaybackNotificationProviderTest {
    @Test fun delayedArtworkFailureRetiresTheOwnerOutsideMedia3sServiceCatch(): Unit = onMain {
        NotificationFixture().use { fixture ->
            val calls = mutableListOf<String>()
            val failures = mutableListOf<AppFailure?>()
            val guard = PlaybackForegroundFailureGuard(
                { calls += "retire" }, { calls += "detach"; true },
                { calls += "silence"; failures += it }, { calls += "remove" }, { calls += "stop" },
            )
            val provider = GuardedPlaybackNotificationProvider(fixture.provider, guard) { true }
            var updates = 0
            fixture.create(provider) {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                updates++
                if (Build.VERSION.SDK_INT >= 31) throw ForegroundServiceStartNotAllowedException(PRIVATE_DETAIL)
                else throw SecurityException(PRIVATE_DETAIL)
            }
            assertEquals(0, updates) // The default provider has not completed the bitmap yet.
            fixture.completeArtwork()
            drainMainCallbacks()
            assertEquals(1, updates)
            assertEquals(listOf("retire", "detach", "silence", "remove", "stop"), calls)
            assertEquals(listOf(playbackForegroundFailure()), failures)
            assertTrue(guard.retired)
            guard.rejected(); guard.destroyed()
            assertEquals(5, calls.size)
        }
    }

    @Test fun delayedArtworkCannotPublishAfterRetirementLanguageReplacementOrServiceReplacement(): Unit = onMain {
        for (invalidation in listOf("retirement", "language", "service")) {
            NotificationFixture().use { fixture ->
                val gate = PlaybackForegroundGate(fixture.context)
                val old = PlaybackService()
                val successor = PlaybackService()
                gate.attached(old)
                var currentLanguage = true
                var updates = 0
                var silenced = 0
                val guard = PlaybackForegroundFailureGuard({}, { gate.detached(old) }, { silenced++ }, {}, {})
                val provider = GuardedPlaybackNotificationProvider(fixture.provider, guard) {
                    currentLanguage && gate.owns(old)
                }
                fixture.create(provider) { updates++ }
                when (invalidation) {
                    "retirement" -> guard.destroyed()
                    "language" -> currentLanguage = false
                    "service" -> gate.attached(successor)
                }
                fixture.completeArtwork()
                drainMainCallbacks()
                assertEquals("Stale $invalidation callback posted a notification", 0, updates)
                assertEquals(if (invalidation == "retirement") 1 else 0, silenced)
                if (invalidation == "service") {
                    assertTrue(gate.owns(successor))
                    assertFalse(guard.retired) // Identity, not just the retired bit, blocked it.
                }
            }
        }
    }

    @Test fun initialNotificationFailureStillPropagatesToTheFrameworkCaller(): Unit = onMain {
        NotificationFixture().use { fixture ->
            val raw = if (Build.VERSION.SDK_INT >= 31) ForegroundServiceStartNotAllowedException(PRIVATE_DETAIL)
                else SecurityException(PRIVATE_DETAIL)
            val delegate = object : MediaNotification.Provider by fixture.provider {
                override fun createNotification(
                    mediaSession: MediaSession,
                    mediaButtonPreferences: ImmutableList<CommandButton>,
                    actionFactory: MediaNotification.ActionFactory,
                    onNotificationChangedCallback: MediaNotification.Provider.Callback,
                ): MediaNotification = throw raw
            }
            val guard = PlaybackForegroundFailureGuard({}, { true }, { fail("Initial failure was swallowed") }, {}, {})
            val provider = GuardedPlaybackNotificationProvider(delegate, guard) { true }
            try { fixture.create(provider) {}; fail("Media3 must see initial creation failure") }
            catch (error: Exception) { assertSame(raw, error) }
            assertFalse(guard.retired)
        }
    }

    private class NotificationFixture : AutoCloseable {
        val context = ApplicationProvider.getApplicationContext<Context>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val player = QueueSessionPlayer(scope, { PlaybackResult.Accepted })
        private val bitmapFuture = SettableFuture.create<Bitmap>()
        private val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        private val pendingIntent = PendingIntent.getBroadcast(context, 0,
            Intent("dev.veyra.app.TEST_UNSENT_NOTIFICATION_${UUID.randomUUID()}").setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        private val session: MediaSession
        val provider = PlaybackNotificationProvider(context) { QueueContext.MUSIC }
        private val actions = object : MediaNotification.ActionFactory {
            override fun createMediaAction(session: MediaSession, icon: IconCompat, title: CharSequence, command: Int) =
                NotificationCompat.Action.Builder(icon, title, pendingIntent).build()
            override fun createCustomAction(session: MediaSession, icon: IconCompat, title: CharSequence, action: String, extras: Bundle) =
                NotificationCompat.Action.Builder(icon, title, pendingIntent).build()
            override fun createCustomActionFromCustomCommandButton(session: MediaSession, button: CommandButton): NotificationCompat.Action =
                error("This probe has no custom commands")
            override fun createMediaActionPendingIntent(session: MediaSession, command: Long): PendingIntent = pendingIntent
        }

        init {
            val item = MediaItem("notification-probe", "Authored metadata", MediaKind.MUSIC, SourceRef(SourceProvider.LOCAL, "probe"))
            val entry = QueueEntry("notification-occurrence", item.id)
            player.publish(PlaybackState(initialized = true, generation = 1,
                queues = mapOf(QueueContext.MUSIC to QueueSnapshot(entries = listOf(entry), currentEntryId = entry.id)),
                currentItem = item, currentEntryId = entry.id, phase = PlaybackPhase.PAUSED))
            player.publishArtwork(entry.id to 1L, byteArrayOf(1))
            // Completion is controlled, not a claimed image-decoder/network/OS-notification test.
            session = MediaSession.Builder(context, player).setId("notification-probe-${UUID.randomUUID()}")
                .setBitmapLoader(object : BitmapLoader {
                    override fun supportsMimeType(mimeType: String) = true
                    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = bitmapFuture
                    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = error("No URI may be loaded")
                }).build()
        }

        fun create(provider: MediaNotification.Provider, update: (MediaNotification) -> Unit): MediaNotification =
            provider.createNotification(session, ImmutableList.of(), actions, update)

        fun completeArtwork() { assertTrue(bitmapFuture.set(bitmap)) }

        override fun close() {
            bitmapFuture.cancel(false)
            session.release()
            player.release()
            scope.cancel()
            pendingIntent.cancel()
            bitmap.recycle()
        }
    }

    private suspend fun drainMainCallbacks() {
        val drained = CompletableDeferred<Unit>()
        Handler(Looper.getMainLooper()).post { drained.complete(Unit) }
        withTimeout(2_000) { drained.await() }
    }

    private fun onMain(block: suspend () -> Unit): Unit = runBlocking {
        withContext(Dispatchers.Main.immediate) { block() }
    }

    private companion object { const val PRIVATE_DETAIL = "authored-private-notification-content" }
}
