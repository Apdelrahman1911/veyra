@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.platform.audio

import dev.veyra.model.MediaItem
import dev.veyra.model.QueueContext
import dev.veyra.model.RepeatMode
import dev.veyra.playback.PlaybackCommand
import dev.veyra.playback.PlaybackResult
import dev.veyra.playback.PlaybackState
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSThread
import platform.MediaPlayer.*
import platform.QuartzCore.CACurrentMediaTime
import platform.UIKit.UIImage

/** App-lifetime Now Playing/remote-command owner, independent of which native deck is audible. */
class IosNowPlayingBridge(
    private val scope: CoroutineScope,
    private val playback: StateFlow<PlaybackState>,
    private val dispatch: suspend (PlaybackCommand) -> PlaybackResult,
    private val skipBackSeconds: () -> Int = { 15 },
    private val skipForwardSeconds: () -> Int = { 30 },
    /** A trusted app-owned LOCAL path only; must never perform an artwork network request. */
    private val cachedArtworkPath: suspend (String) -> String? = { null },
) {
    private val commands = MPRemoteCommandCenter.sharedCommandCenter()
    private val nowPlaying = MPNowPlayingInfoCenter.defaultCenter()
    private val targets = mutableListOf<Pair<MPRemoteCommand, Any>>()
    private var observer: Job? = null
    private var artworkJob: Job? = null
    private var artworkKey: Pair<String?, Long>? = null
    private var artwork: MPMediaItemArtwork? = null
    private var lastMetadata: Metadata? = null
    private var publishedPosition = 0L
    private var publishedAt = 0.0
    private var lastPendingSeek: Long? = null
    private var lastCommands: CommandState? = null
    private var closed = false

    private data class Metadata(
        val entry: String?, val generation: Long, val item: MediaItem?, val duration: Long?,
        val playing: Boolean, val speed: Float, val effectiveSpeed: Float, val queueCount: Int, val queueIndex: Int,
    )
    private data class CommandState(
        val hasItem: Boolean, val canPlay: Boolean, val podcast: Boolean, val canNext: Boolean,
        val canPrevious: Boolean, val seekable: Boolean, val skipBack: Int, val skipForward: Int,
    )

    init {
        check(NSThread.isMainThread) { "Create the iOS remote-command owner on Main." }
        register(commands.playCommand) { state, _ -> if (canPlay(state)) PlaybackCommand.Resume else null }
        register(commands.pauseCommand) { state, _ -> if (state.currentItem != null) PlaybackCommand.Pause else null }
        register(commands.togglePlayPauseCommand) { state, _ -> if (canPlay(state)) PlaybackCommand.PlayPause else null }
        register(commands.stopCommand) { state, _ -> if (state.currentItem != null) PlaybackCommand.Stop else null }
        register(commands.nextTrackCommand) { state, _ -> if (state.activeContext == QueueContext.MUSIC && canNext(state)) PlaybackCommand.Next else null }
        register(commands.previousTrackCommand) { state, _ -> if (state.activeContext == QueueContext.MUSIC && canPrevious(state)) PlaybackCommand.Previous else null }
        register(commands.skipBackwardCommand) { state, _ -> if (state.seekable) PlaybackCommand.SkipBack else null }
        register(commands.skipForwardCommand) { state, _ -> if (state.seekable) PlaybackCommand.SkipForward else null }
        register(commands.changePlaybackPositionCommand) { state, event ->
            val seconds = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime
            if (state.seekable && seconds != null && seconds.isFinite() && seconds >= 0 && seconds < Long.MAX_VALUE / 1000.0)
                PlaybackCommand.Seek((seconds * 1000).toLong()) else null
        }
        commands.changePlaybackRateCommand.supportedPlaybackRates = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
        register(commands.changePlaybackRateCommand) { state, event ->
            val speed = (event as? MPChangePlaybackRateCommandEvent)?.playbackRate
            if (state.currentItem != null && speed != null && speed.isFinite() && speed in 0.5f..2f)
                PlaybackCommand.SetRate(speed) else null
        }
        // Do not leave unsupported long-press scan or playlist-changing commands advertised.
        commands.seekBackwardCommand.enabled = false
        commands.seekForwardCommand.enabled = false
        commands.changeRepeatModeCommand.enabled = false
        commands.changeShuffleModeCommand.enabled = false
        commands.ratingCommand.enabled = false
        commands.likeCommand.enabled = false
        commands.dislikeCommand.enabled = false
        commands.bookmarkCommand.enabled = false
        observer = scope.launch {
            playback.collect { state ->
                updateCommands(state)
                val key = state.currentEntryId to state.generation
                if (key != artworkKey) {
                    artworkKey = key
                    artwork = null
                    artworkJob?.cancel()
                    state.currentItem?.id?.let { mediaId ->
                        artworkJob = scope.launch {
                            val image = try {
                                withContext(Dispatchers.Default) {
                                    val path = cachedArtworkPath(mediaId)
                                    if (path != null && path.startsWith('/')) UIImage.imageWithContentsOfFile(path) else null
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                            if (!closed && artworkKey == key && image != null) {
                                artwork = MPMediaItemArtwork(boundsSize = image.size, requestHandler = { image })
                                publish(playback.value, force = true)
                            }
                        }
                    }
                    publish(state, force = true)
                } else publish(state)
            }
        }
    }

    private fun register(command: MPRemoteCommand, make: (PlaybackState, MPRemoteCommandEvent?) -> PlaybackCommand?) {
        val target = command.addTargetWithHandler { event ->
            if (closed) return@addTargetWithHandler MPRemoteCommandHandlerStatusCommandFailed
            val request = make(playback.value, event)
                ?: return@addTargetWithHandler MPRemoteCommandHandlerStatusNoActionableNowPlayingItem
            // The synchronous OS result acknowledges enqueueing, not successful audible playback.
            // The shared controller serializes and reports the real command result in its state.
            scope.launch { if (!closed) dispatch(request) }
            MPRemoteCommandHandlerStatusSuccess
        }
        targets += command to target
    }

    private fun updateCommands(state: PlaybackState) {
        val derived = CommandState(state.currentItem != null, canPlay(state),
            state.activeContext == QueueContext.PODCAST, canNext(state), canPrevious(state),
            state.seekable, skipBackSeconds().coerceAtLeast(1), skipForwardSeconds().coerceAtLeast(1))
        if (lastCommands == derived) return
        lastCommands = derived
        val hasItem = state.currentItem != null
        val podcast = state.activeContext == QueueContext.PODCAST
        commands.playCommand.enabled = canPlay(state)
        commands.pauseCommand.enabled = hasItem
        commands.togglePlayPauseCommand.enabled = canPlay(state)
        commands.stopCommand.enabled = hasItem
        commands.nextTrackCommand.enabled = !podcast && canNext(state)
        commands.previousTrackCommand.enabled = !podcast && canPrevious(state)
        commands.skipBackwardCommand.enabled = podcast && state.seekable
        commands.skipForwardCommand.enabled = podcast && state.seekable
        commands.skipBackwardCommand.preferredIntervals = listOf(derived.skipBack.toDouble())
        commands.skipForwardCommand.preferredIntervals = listOf(derived.skipForward.toDouble())
        commands.changePlaybackPositionCommand.enabled = hasItem && state.seekable
        commands.changePlaybackRateCommand.enabled = hasItem
    }

    private fun publish(state: PlaybackState, force: Boolean = false) {
        if (closed) return
        val item = state.currentItem
        if (item == null) {
            if (lastMetadata != null || force) nowPlaying.nowPlayingInfo = null
            lastMetadata = null
            return
        }
        val metadata = Metadata(state.currentEntryId, state.generation, item, state.durationMs,
            state.isPlaying, state.speed, state.effectiveSpeed, state.queue.entries.size, state.queue.currentIndex)
        val now = CACurrentMediaTime()
        val expected = publishedPosition + if (lastMetadata?.playing == true)
            ((now - publishedAt) * 1000 * (lastMetadata?.effectiveSpeed ?: 1f)).toLong() else 0
        val seekConfirmed = lastPendingSeek != null && state.pendingSeekMs == null
        lastPendingSeek = state.pendingSeekMs
        // iOS advances elapsed time itself using the actual published rate. Refresh changes,
        // confirmed seeks or drift, not a new dictionary for every 250ms native sample.
        if (!force && metadata == lastMetadata && !seekConfirmed && now - publishedAt < 5.0 &&
            abs(state.positionMs - expected) < 750 && (state.isPlaying || state.positionMs == publishedPosition)) return
        val info = mutableMapOf<Any?, Any>(
            MPMediaItemPropertyTitle to item.title,
            MPMediaItemPropertyArtist to (item.artist ?: item.creator),
            MPNowPlayingInfoPropertyElapsedPlaybackTime to state.positionMs / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate to if (state.isPlaying) state.effectiveSpeed.toDouble() else 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate to state.speed.toDouble(),
            MPNowPlayingInfoPropertyMediaType to MPNowPlayingInfoMediaTypeAudio,
            MPNowPlayingInfoPropertyIsLiveStream to false,
            MPNowPlayingInfoPropertyPlaybackQueueCount to state.queue.entries.size,
            MPNowPlayingInfoPropertyPlaybackQueueIndex to state.queue.currentIndex.coerceAtLeast(0),
        )
        item.album?.let { info[MPMediaItemPropertyAlbumTitle] = it }
        state.durationMs?.takeIf { it > 0 }?.let { info[MPMediaItemPropertyPlaybackDuration] = it / 1000.0 }
        artwork?.let { info[MPMediaItemPropertyArtwork] = it }
        nowPlaying.nowPlayingInfo = info
        lastMetadata = metadata
        publishedPosition = state.positionMs
        publishedAt = now
    }

    /** Main-only. Remove exactly our retained tokens, never all targets belonging to another owner. */
    fun close() {
        check(NSThread.isMainThread)
        if (closed) return
        closed = true
        observer?.cancel()
        artworkJob?.cancel()
        targets.forEach { (command, target) -> command.removeTarget(target); command.enabled = false }
        targets.clear()
        nowPlaying.nowPlayingInfo = null
    }
    private fun canPlay(state: PlaybackState) = state.currentItem != null || state.queue.entries.isNotEmpty()
    private fun canNext(state: PlaybackState) = state.queue.entries.isNotEmpty() &&
        (state.queue.currentIndex < state.queue.entries.lastIndex || state.queue.repeat == RepeatMode.ALL)
    private fun canPrevious(state: PlaybackState) = state.currentItem != null &&
        (state.positionMs > 0 || state.queue.currentIndex > 0 || state.queue.repeat == RepeatMode.ALL)
}
