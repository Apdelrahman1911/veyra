@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.veyra.platform.audio

import android.app.PendingIntent
import android.content.Context
import android.os.Looper
import android.os.Process
import androidx.media3.common.C
import androidx.media3.common.MediaItem as NativeMediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.veyra.app.nativeLanguageContext
import dev.veyra.model.LanguagePreference
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.QueueContext
import dev.veyra.model.RepeatMode
import dev.veyra.playback.PlaybackCommand
import dev.veyra.playback.PlaybackPhase
import dev.veyra.playback.PlaybackResult
import dev.veyra.playback.PlaybackState
import dev.veyra.playback.getOrThrow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

/** A read-only session projection of the shared queue, surviving native crossfade deck promotion. */
class AndroidMediaSessionBridge(
    context: Context,
    scope: CoroutineScope,
    playback: StateFlow<PlaybackState>,
    language: StateFlow<LanguagePreference>,
    dispatch: suspend (PlaybackCommand) -> PlaybackResult,
    activityIntent: PendingIntent,
    mediaLookup: (String) -> MediaItem? = { null },
    skipBackSeconds: () -> Int = { 15 },
    skipForwardSeconds: () -> Int = { 30 },
    /** App-owned cached bytes only. This callback must never initiate a network request. */
    cachedArtwork: suspend (String) -> ByteArray? = { null },
) {
    internal val facade = QueueSessionPlayer(scope, dispatch, mediaLookup, skipBackSeconds, skipForwardSeconds)
    val session: MediaSession = MediaSession.Builder(context, facade)
        .setSessionActivity(activityIntent)
        .setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (controller.uid != Process.myUid() && !controller.isTrusted && !session.isMediaNotificationController(controller)) {
                    return MediaSession.ConnectionResult.reject()
                }
                // Grant a stable SAFE superset. Capturing initial dynamic availability would
                // permanently deny seek/next to controllers connected before the queue loads.
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailablePlayerCommands(QueueSessionPlayer.SAFE_COMMANDS).build()
            }
        }).build()
    private var buttonContext: Pair<QueueContext, LanguagePreference>? = null
    private var artworkKey: Pair<String?, Long>? = null
    private var artworkJob: Job? = null
    private val observer = scope.launch {
        combine(playback, language) { state, selectedLanguage -> state to selectedLanguage }.collect { (state, selectedLanguage) ->
            facade.publish(state)
            val key = state.currentEntryId to state.generation
            if (key != artworkKey) {
                artworkKey = key
                artworkJob?.cancel()
                facade.publishArtwork(key, null)
                state.currentItem?.id?.let { mediaId ->
                    artworkJob = scope.launch {
                        val bytes = try { cachedArtwork(mediaId)?.takeIf { it.size <= 1_048_576 } }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                        if (artworkKey == key) facade.publishArtwork(key, bytes)
                    }
                }
            }
            val buttons = state.activeContext to selectedLanguage
            if (buttonContext != buttons) {
                buttonContext = buttons
                session.setMediaButtonPreferences(mediaTransportButtons(nativeLanguageContext(context, selectedLanguage), state.activeContext))
            }
        }
    }
    /** Main-thread only; service recreation must never release the application audio engine. */
    fun close() {
        observer.cancel()
        artworkJob?.cancel()
        session.release()
        facade.release()
    }
}

internal class QueueSessionPlayer(
    private val scope: CoroutineScope,
    private val dispatch: suspend (PlaybackCommand) -> PlaybackResult,
    private val mediaLookup: (String) -> MediaItem? = { null },
    private val skipBackSeconds: () -> Int = { 15 },
    private val skipForwardSeconds: () -> Int = { 30 },
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var snapshot = PlaybackState()
    private var artwork: ByteArray? = null
    private var artworkKey: Pair<String?, Long>? = null
    private val position = PositionSupplier { snapshot.positionMs }
    private val buffered = PositionSupplier { snapshot.bufferedPositionMs ?: snapshot.positionMs }
    private val totalBuffered = PositionSupplier { ((snapshot.bufferedPositionMs ?: snapshot.positionMs) - snapshot.positionMs).coerceAtLeast(0) }

    fun publish(state: PlaybackState) { snapshot = state; invalidateState() }
    fun publishArtwork(key: Pair<String?, Long>, bytes: ByteArray?) {
        if ((snapshot.currentEntryId to snapshot.generation) != key) return
        artworkKey = key
        artwork = bytes
        invalidateState()
    }

    override fun getState(): State {
        val state = snapshot
        val queue = state.queue
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP,
        )
        if (state.currentItem != null) commands.add(Player.COMMAND_SET_SPEED_AND_PITCH)
        if (state.seekable) commands.addAll(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD)
        if (queue.entries.isNotEmpty()) commands.add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
        if (queue.entries.isNotEmpty() && (queue.currentIndex < queue.entries.lastIndex || queue.repeat == RepeatMode.ALL)) {
            commands.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        if (queue.entries.isNotEmpty() && (queue.currentIndex > 0 || state.positionMs > 0 || queue.repeat == RepeatMode.ALL)) {
            commands.add(Player.COMMAND_SEEK_TO_PREVIOUS)
        }
        if (state.activeContext == QueueContext.MUSIC) commands.addAll(Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE)
        val hasCurrent = queue.currentIndex >= 0 && state.currentItem != null
        val playbackState = when {
            !hasCurrent -> Player.STATE_IDLE
            state.phase == PlaybackPhase.ERROR || state.phase == PlaybackPhase.IDLE -> Player.STATE_IDLE
            state.phase == PlaybackPhase.ENDED -> Player.STATE_ENDED
            state.isBuffering -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        val builder = State.Builder().setAvailableCommands(commands.build())
            .setPlaybackState(playbackState)
            .setPlayWhenReady(state.isPlaying || (state.desiredPlaying && state.isBuffering), Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setIsLoading(state.isBuffering)
            .setPlaybackParameters(PlaybackParameters(state.speed.coerceIn(0.5f, 2f)))
            .setContentPositionMs(position).setContentBufferedPositionMs(buffered).setTotalBufferedDurationMs(totalBuffered)
            .setRepeatMode(when (queue.repeat) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            })
            .setShuffleModeEnabled(queue.shuffle)
            .setSeekBackIncrementMs(skipBackSeconds().coerceAtLeast(1) * 1000L)
            .setSeekForwardIncrementMs(skipForwardSeconds().coerceAtLeast(1) * 1000L)
        if (state.phase == PlaybackPhase.ERROR) state.failure?.let {
            builder.setPlayerError(PlaybackException(it.code.name, null, PlaybackException.ERROR_CODE_UNSPECIFIED))
        }
        if (queue.entries.isNotEmpty()) {
            builder.setPlaylist(queue.entries.map { entry ->
                val item = mediaLookup(entry.mediaId) ?: state.currentItem?.takeIf { it.id == entry.mediaId }
                val current = entry.id == state.currentEntryId
                val metadata = MediaMetadata.Builder().setTitle(item?.title)
                    .setArtist(item?.artist ?: item?.creator).setAlbumTitle(item?.album)
                    .setMediaType(if (item?.kind == MediaKind.EPISODE) MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE else MediaMetadata.MEDIA_TYPE_MUSIC)
                if (current && artworkKey == (state.currentEntryId to state.generation)) artwork?.let {
                    metadata.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }
                MediaItemData.Builder(entry.id)
                    .setMediaItem(NativeMediaItem.Builder().setMediaId(entry.id).setMediaMetadata(metadata.build()).build())
                    .setDurationUs((if (current) state.durationMs else item?.durationMs)?.times(1000) ?: C.TIME_UNSET)
                    .setIsSeekable(current && state.seekable).build()
            }).setCurrentMediaItemIndex(queue.currentIndex.takeIf { it >= 0 } ?: 0)
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        command(if (playWhenReady) PlaybackCommand.Resume else PlaybackCommand.Pause)
    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleStop(): ListenableFuture<*> = command(PlaybackCommand.Stop)
    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> =
        if (playbackParameters.pitch != 1f) unsupported("Independent pitch changes are not supported")
        else command(PlaybackCommand.SetRate(playbackParameters.speed))
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> = command(PlaybackCommand.SetRepeat(
        when (repeatMode) { Player.REPEAT_MODE_ALL -> RepeatMode.ALL; Player.REPEAT_MODE_ONE -> RepeatMode.ONE; else -> RepeatMode.OFF }, snapshot.activeContext))
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> =
        command(PlaybackCommand.SetShuffle(shuffleModeEnabled, snapshot.activeContext))
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val request = when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> PlaybackCommand.Next
            // The common Previous policy may restart the current item; do not advertise the
            // separately defined PREVIOUS_MEDIA_ITEM command and silently change its semantics.
            Player.COMMAND_SEEK_TO_PREVIOUS -> PlaybackCommand.Previous
            Player.COMMAND_SEEK_BACK -> PlaybackCommand.SkipBack
            Player.COMMAND_SEEK_FORWARD -> PlaybackCommand.SkipForward
            Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> {
                val entry = snapshot.queue.entries.getOrNull(mediaItemIndex)
                    ?: return unsupported("Queue occurrence is unavailable")
                if (entry.id == snapshot.currentEntryId) PlaybackCommand.Seek(positionMs.takeIf { it != C.TIME_UNSET } ?: 0)
                else {
                    // PlayEntry deliberately resumes podcasts. A cross-item seek with an explicit
                    // offset cannot be fulfilled atomically by this contract; never ignore it.
                    if (positionMs != C.TIME_UNSET) return unsupported("Cross-item offsets are not supported")
                    PlaybackCommand.PlayEntry(snapshot.activeContext, entry.id)
                }
            }
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> PlaybackCommand.Seek(positionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0)
            else -> return unsupported("Seek command is unavailable")
        }
        return command(request)
    }
    private fun command(value: PlaybackCommand): ListenableFuture<*> = scope.future { dispatch(value).getOrThrow() }
    private fun unsupported(reason: String): ListenableFuture<Void> = Futures.immediateFailedFuture(UnsupportedOperationException(reason))

    companion object {
        // No SET_MEDIA_ITEM, CHANGE_MEDIA_ITEMS, volume or arbitrary-URI command can escape the controller.
        val SAFE_COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE,
        ).build()
    }
}
