@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.veyra.platform.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.veyra.app.R
import dev.veyra.app.VeyraApplication
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import dev.veyra.platform.audio.PlaybackNotificationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** OS lifetime/notification adapter only: all controls use the application's shared controller. */
class PlaybackService : MediaSessionService() {
    private val app get() = application as VeyraApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startupHoldUntil = 0L
    private var holdJob: Job? = null
    private var labelsJob: Job? = null
    private var notificationRevision = 0L
    private var attached = false
    private val foregroundGuard = PlaybackForegroundFailureGuard(
        retireLocal = {
            attached = false
            startupHoldUntil = 0
            holdJob?.cancel(); holdJob = null
            labelsJob?.cancel(); labelsJob = null
            clearListener()
        },
        detachOwner = { app.playbackForeground.detached(this) },
        silenceOwner = { failure -> app.onPlaybackServiceLost(failure) },
        removeOwnedSession = {
            // Media3 removeSession unconditionally clears the shared session's listener. Never
            // let a stale service callback remove a successor's attachment/listener.
            if (isSessionAdded(app.mediaSession.session)) removeSession(app.mediaSession.session)
        },
        stopService = { stopSelf() },
    )

    override fun onCreate() {
        super.onCreate()
        setListener(object : MediaSessionService.Listener {
            override fun onForegroundServiceStartNotAllowedException() { foregroundGuard.rejected() }
        })
        // Attach before any fallible notification setup so a pending Play receives its failure.
        app.playbackForeground.attached(this)
        if (!foregroundGuard.attempt { updateNativeLabels() }) return
        addSession(app.mediaSession.session)
        attached = true
        labelsJob = scope.launch {
            app.nativeLanguage.drop(1).collect { foregroundGuard.attempt { updateNativeLabels() } }
        }
    }

    private fun updateNativeLabels() {
        // Old language collectors must not overwrite the successor's shared channel/notice.
        if (!app.playbackForeground.owns(this)) { foregroundGuard.rejected(); return }
        val labels = app.nativeLabelsContext()
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = manager.getNotificationChannel(CHANNEL_ID)
                ?: NotificationChannel(CHANNEL_ID, labels.getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { setSound(null, null); enableVibration(false) }
            channel.name = labels.getString(R.string.playback_channel)
            manager.createNotificationChannel(channel)
        }
        val revision = ++notificationRevision
        setMediaNotificationProvider(GuardedPlaybackNotificationProvider(
            PlaybackNotificationProvider(labels) { app.playback.value.activeContext },
            foregroundGuard,
            // Provider replacement (language change) and service replacement are independent.
            // Neither an old translation nor a retired service may publish delayed artwork.
            isCurrent = { attached && revision == notificationRevision && app.playbackForeground.owns(this) },
        ))
        if (attached) {
            if (!app.playback.value.isPlaying && SystemClock.elapsedRealtime() < startupHoldUntil) {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, startupNotification())
            } else onUpdateNotification(app.mediaSession.session, app.playback.value.isPlaying ||
                (app.playback.value.desiredPlaying && app.playback.value.isBuffering))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (foregroundGuard.retired || !app.playbackForeground.owns(this)) null else app.mediaSession.session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A playback-start request must satisfy Android's foreground deadline even while source
        // resolution or the native decoder is not yet playing. Media3 handles later media actions.
        // Media3 can resolve an intent's session URI directly, bypassing onGetSession.
        if (!app.playbackForeground.owns(this)) foregroundGuard.rejected()
        if (foregroundGuard.retired) return START_NOT_STICKY
        if (intent?.action == ACTION_START) {
            try { ensurePlaybackForeground() }
            catch (failure: VeyraException) {
                if (failure.failure.code != FailureCode.BACKGROUND_RESTRICTED) throw failure
                // The guard already retired this identity, silenced native output and stopped us.
                // Do not dispatch Media3 commands after the foreground allowance was rejected.
                return START_NOT_STICKY
            }
        }
        super.onStartCommand(intent, flags, startId)
        // A killed process restores paused; never use sticky restart as an autoplay mechanism.
        return START_NOT_STICKY
    }

    internal fun ensurePlaybackForeground() {
        if (!app.playbackForeground.owns(this)) foregroundGuard.rejected()
        foregroundGuard.requireForeground {
            startupHoldUntil = SystemClock.elapsedRealtime() + 5_000
            val notification = startupNotification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIFICATION_ID, notification)
            app.playbackForeground.started(this) // This ACK is deliberately after startForeground.
            holdJob?.cancel()
            holdJob = scope.launch {
                delay(5_000)
                startupHoldUntil = 0
                if (attached) updateFromSession()
            }
        }
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (!app.playbackForeground.owns(this)) foregroundGuard.rejected()
        if (!attached && !foregroundGuard.retired) return // Only the synchronous addSession setup.
        foregroundGuard.frameworkUpdate(startInForegroundRequired) {
            // Do not demote the service between its foreground ACK and target-35+ audio focus.
            // This bounded hold never suppresses a requested foreground promotion/refusal.
            if (!startInForegroundRequired && !session.player.isPlaying && SystemClock.elapsedRealtime() < startupHoldUntil) return@frameworkUpdate
            // Keep Media3's internal false/rejected-Play result, but never log a private OS cause.
            // Manual hold/language refreshes catch the framework signal with their outer guard.
            super.onUpdateNotification(session, startInForegroundRequired)
        }
    }

    private fun updateFromSession() {
        foregroundGuard.attempt {
            val session = app.mediaSession.session
            onUpdateNotification(session, app.playback.value.isPlaying ||
                (app.playback.value.desiredPlaying && app.playback.value.isBuffering))
            if (!foregroundGuard.retired && !app.playback.value.isPlaying && !app.playback.value.desiredPlaying) {
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH)
                else { @Suppress("DEPRECATION") stopForeground(false) }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val state = app.playback.value
        if (!state.isPlaying && !(state.desiredPlaying && state.isBuffering)) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        try { foregroundGuard.destroyed() }
        finally { scope.cancel(); clearListener(); super.onDestroy() }
    }

    private fun startupNotification(): Notification {
        val labels = app.nativeLabelsContext()
        return NotificationCompat.Builder(labels, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_audio)
            .setContentTitle(app.playback.value.currentItem?.title ?: labels.getString(R.string.app_name))
            .setContentText(labels.getString(R.string.playback_preparing))
            .setContentIntent(app.activityIntent())
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true).setOngoing(true).setSilent(true).build()
    }

    companion object {
        const val ACTION_START = "dev.veyra.app.START_PLAYBACK_SERVICE"
        const val CHANNEL_ID = "veyra_playback"
        const val NOTIFICATION_ID = 4101
    }
}
