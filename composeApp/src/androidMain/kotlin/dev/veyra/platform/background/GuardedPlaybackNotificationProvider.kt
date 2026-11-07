@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.veyra.platform.background

import android.os.Looper
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/**
 * Media3 1.8's delayed artwork update calls its notification manager directly, outside the
 * MediaSessionService rejection handler. Guard that callback, not the initial notification:
 * initial failures must still reach Media3's synchronous onPlayRequested rejection path.
 */
internal class GuardedPlaybackNotificationProvider(
    private val delegate: MediaNotification.Provider,
    private val foregroundGuard: PlaybackForegroundFailureGuard,
    private val isCurrent: () -> Boolean,
) : MediaNotification.Provider by delegate {
    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        // DefaultMediaNotificationProvider dispatches its bitmap callback to this looper. The
        // manager then uses postOrRun(Main), so forwarding below also contains its OS API calls.
        check(mediaSession.player.applicationLooper == Looper.getMainLooper())
        return delegate.createNotification(mediaSession, mediaButtonPreferences, actionFactory) { notification ->
            check(Looper.myLooper() == Looper.getMainLooper())
            if (isCurrent()) foregroundGuard.attempt {
                onNotificationChangedCallback.onNotificationChanged(notification)
            }
        }
    }
}
