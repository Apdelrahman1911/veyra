@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.veyra.platform.audio

import androidx.media3.common.MediaItem as NativeMediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.QueueContext
import dev.veyra.model.QueueEntry
import dev.veyra.model.QueueSnapshot
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.playback.PlaybackCommand
import dev.veyra.playback.PlaybackPhase
import dev.veyra.playback.PlaybackResult
import dev.veyra.playback.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueSessionPlayerTest {
    @Test fun resolutionWithoutCurrentMediaDoesNotPublishAnInvalidLoadingState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val item = MediaItem("authored", "Authored test", MediaKind.MUSIC, SourceRef(SourceProvider.LOCAL, "tone"))
        val queue = QueueSnapshot(entries = listOf(QueueEntry("occurrence", item.id)), currentEntryId = "occurrence")
        withContext(Dispatchers.Main.immediate) {
            val facade = QueueSessionPlayer(scope, { error("Publishing state must not dispatch playback") })
            try {
                // Exercise every phase before queue admission, during item resolution,
                // and after the selected item is available to the native session.
                for (phase in PlaybackPhase.entries) {
                    for (stage in 0..2) {
                        facade.publish(PlaybackState(
                            initialized = true,
                            queues = if (stage == 0) emptyMap() else mapOf(QueueContext.MUSIC to queue),
                            currentEntryId = if (stage == 0) null else "occurrence",
                            currentItem = if (stage == 2) item else null,
                            phase = phase,
                            desiredPlaying = true,
                        ))
                        val buffering = phase in setOf(PlaybackPhase.RESOLVING, PlaybackPhase.PREPARING, PlaybackPhase.BUFFERING)
                        val expected = when {
                            stage < 2 || phase in setOf(PlaybackPhase.IDLE, PlaybackPhase.ERROR) -> Player.STATE_IDLE
                            phase == PlaybackPhase.ENDED -> Player.STATE_ENDED
                            buffering -> Player.STATE_BUFFERING
                            else -> Player.STATE_READY
                        }
                        assertEquals("phase=$phase stage=$stage", expected, facade.playbackState)
                        assertEquals("phase=$phase stage=$stage", expected == Player.STATE_BUFFERING, facade.isLoading)
                    }
                }
            } finally { facade.release(); scope.cancel() }
        }
    }

    @Test fun occurrenceQueueIsReadOnlyAndEveryTransportDispatchesToSharedOwner() = runBlocking {
        val commands = Channel<PlaybackCommand>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val item = MediaItem("same-recording", "Authored test", MediaKind.MUSIC, SourceRef(SourceProvider.LOCAL, "tone"))
        val queue = QueueSnapshot(entries = listOf(QueueEntry("occurrence-1", item.id), QueueEntry("occurrence-2", item.id)),
            currentEntryId = "occurrence-1")
        val facade = withContext(Dispatchers.Main.immediate) {
            QueueSessionPlayer(scope, { commands.send(it); PlaybackResult.Accepted }, { item }).also {
                it.publish(PlaybackState(initialized = true, queues = mapOf(QueueContext.MUSIC to queue),
                    currentItem = item, currentEntryId = "occurrence-1", phase = PlaybackPhase.PAUSED,
                    positionMs = 12_345, durationMs = 80_000, seekable = true))
            }
        }
        try {
            withContext(Dispatchers.Main.immediate) {
                assertEquals(2, facade.mediaItemCount)
                assertEquals("occurrence-1", facade.getMediaItemAt(0).mediaId)
                assertEquals("occurrence-2", facade.getMediaItemAt(1).mediaId)
                assertEquals(12_345L, facade.currentPosition)
                assertFalse(facade.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS))
                assertFalse(facade.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM))
                assertFalse(facade.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
                facade.setMediaItem(NativeMediaItem.fromUri("https://example.com/unowned.mp3"))
                assertEquals(2, facade.mediaItemCount)
                facade.play()
            }
            assertEquals(PlaybackCommand.Resume, withTimeout(2_000) { commands.receive() })
            withContext(Dispatchers.Main.immediate) { facade.seekToNext() }
            assertEquals(PlaybackCommand.Next, withTimeout(2_000) { commands.receive() })
            withContext(Dispatchers.Main.immediate) { facade.seekTo(5_000) }
            assertEquals(PlaybackCommand.Seek(5_000, "occurrence-1", 0), withTimeout(2_000) { commands.receive() })
            withContext(Dispatchers.Main.immediate) {
                facade.seekTo(1, 9_000) // Never drop an unsupported cross-item offset.
                facade.playbackParameters = PlaybackParameters(1.25f, 0.8f)
            }
            assertTrue(commands.tryReceive().isFailure)
            withContext(Dispatchers.Main.immediate) { facade.seekToDefaultPosition(1) }
            assertEquals(PlaybackCommand.PlayEntry(QueueContext.MUSIC, "occurrence-2"), withTimeout(2_000) { commands.receive() })
            withContext(Dispatchers.Main.immediate) { facade.release() }
            assertTrue(commands.tryReceive().isFailure) // release is not an engine/queue Stop command
        } finally { withContext(Dispatchers.Main.immediate) { facade.release() }; scope.cancel() }
    }
}
