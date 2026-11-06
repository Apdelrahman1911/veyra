@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.veyra.platform.audio

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import dev.veyra.app.R
import dev.veyra.model.QueueContext
import dev.veyra.platform.background.PlaybackService

/**
 * Media3 1.8's default legacy notification ignores player-command preferences and maps Previous
 * to PREVIOUS_MEDIA_ITEM. Our common Previous may restart the current track; podcasts skip time.
 * Override only button projection, leaving session actions/foreground ownership with Media3.
 */
internal class PlaybackNotificationProvider(
    private val labels: Context,
    private val activeContext: () -> QueueContext,
) : DefaultMediaNotificationProvider(labels, { PlaybackService.NOTIFICATION_ID }, PlaybackService.CHANNEL_ID, R.string.playback_channel) {
    init { setSmallIcon(R.drawable.ic_notification_audio) }

    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        showPauseButton: Boolean,
    ): ImmutableList<CommandButton> = ImmutableList.copyOf(
        playbackNotificationButtons(labels, activeContext(), playerCommands, showPauseButton),
    )
}

internal fun mediaTransportButtons(labels: Context, context: QueueContext): List<CommandButton> {
    val podcast = context == QueueContext.PODCAST
    return listOf(
        CommandButton.Builder(if (podcast) CommandButton.ICON_SKIP_BACK else CommandButton.ICON_PREVIOUS)
            .setDisplayName(labels.getString(if (podcast) R.string.playback_skip_back else R.string.playback_previous))
            .setPlayerCommand(if (podcast) Player.COMMAND_SEEK_BACK else Player.COMMAND_SEEK_TO_PREVIOUS)
            .setSlots(CommandButton.SLOT_BACK).build(),
        CommandButton.Builder(if (podcast) CommandButton.ICON_SKIP_FORWARD else CommandButton.ICON_NEXT)
            .setDisplayName(labels.getString(if (podcast) R.string.playback_skip_forward else R.string.playback_next))
            .setPlayerCommand(if (podcast) Player.COMMAND_SEEK_FORWARD else Player.COMMAND_SEEK_TO_NEXT)
            .setSlots(CommandButton.SLOT_FORWARD).build(),
    )
}

internal fun playbackNotificationButtons(
    labels: Context,
    context: QueueContext,
    availableCommands: Player.Commands,
    showPauseButton: Boolean,
): List<CommandButton> {
    val transport = mediaTransportButtons(labels, context)
    val playPause = CommandButton.Builder(if (showPauseButton) CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY)
        .setDisplayName(labels.getString(if (showPauseButton) R.string.playback_pause else R.string.playback_play))
        .setPlayerCommand(Player.COMMAND_PLAY_PAUSE).setSlots(CommandButton.SLOT_CENTRAL).build()
    return listOf(transport[0], playPause, transport[1]).filter { availableCommands.contains(it.playerCommand) }
}
