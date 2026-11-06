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
import dev.veyra.model.AppFailure
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
    private var attached = false

    override fun onCreate() {
        super.onCreate()
        updateNativeLabels()
        addSession(app.mediaSession.session)
        attached = true
        app.playbackForeground.attached(this)
        scope.launch { app.nativeLanguage.drop(1).collect { updateNativeLabels() } }
    }

    private fun updateNativeLabels() {
        val labels = app.nativeLabelsContext()
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = manager.getNotificationChannel(CHANNEL_ID)
                ?: NotificationChannel(CHANNEL_ID, labels.getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { setSound(null, null); enableVibration(false) }
            channel.name = labels.getString(R.string.playback_channel)
            manager.createNotificationChannel(channel)
        }
        setMediaNotificationProvider(PlaybackNotificationProvider(labels) { app.playback.value.activeContext })
        if (attached) {
            if (!app.playback.value.isPlaying && SystemClock.elapsedRealtime() < startupHoldUntil) {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, startupNotification())
            } else onUpdateNotification(app.mediaSession.session, app.playback.value.isPlaying ||
                (app.playback.value.desiredPlaying && app.playback.value.isBuffering))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = app.mediaSession.session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A playback-start request must satisfy Android's foreground deadline even while source
        // resolution or the native decoder is not yet playing. Media3 handles later media actions.
        if (intent?.action == ACTION_START) {
            try { ensurePlaybackForeground() }
            catch (_: VeyraException) { app.playbackForeground.failed(this); stopSelf(startId) }
        }
        super.onStartCommand(intent, flags, startId)
        // A killed process restores paused; never use sticky restart as an autoplay mechanism.
        return START_NOT_STICKY
    }

    internal fun ensurePlaybackForeground() {
        try {
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
        } catch (_: Exception) {
            startupHoldUntil = 0
            app.playbackForeground.failed(this)
            throw VeyraException(AppFailure(FailureCode.BACKGROUND_RESTRICTED, "playback_foreground_service"))
        }
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // Do not allow an initial paused session notification to demote the service between its
        // foreground ACK and the target-35+ audio-focus request. There is no indefinite hold.
        if (!startInForegroundRequired && !session.player.isPlaying && SystemClock.elapsedRealtime() < startupHoldUntil) return
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    private fun updateFromSession() {
        val session = app.mediaSession.session
        super.onUpdateNotification(session, app.playback.value.isPlaying ||
            (app.playback.value.desiredPlaying && app.playback.value.isBuffering))
        if (!app.playback.value.isPlaying && !app.playback.value.desiredPlaying) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH)
            else { @Suppress("DEPRECATION") stopForeground(false) }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val state = app.playback.value
        if (!state.isPlaying && !(state.desiredPlaying && state.isBuffering)) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        attached = false
        val lostOwner = app.playbackForeground.detached(this)
        scope.cancel()
        removeSession(app.mediaSession.session)
        // A service disappearing must not leave unowned background audio. Do not destroy the
        // application engine/session or enqueue an unscoped Pause against a later service.
        if (lostOwner) app.onPlaybackServiceLost()
        super.onDestroy()
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
