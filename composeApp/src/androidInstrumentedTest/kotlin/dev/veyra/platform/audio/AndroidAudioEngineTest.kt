@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.veyra.platform.audio

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veyra.app.MainActivity
import dev.veyra.model.FailureCode
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.model.VeyraException
import dev.veyra.playback.CrossfadeMix
import dev.veyra.playback.EngineEvent
import dev.veyra.playback.EnginePhase
import dev.veyra.playback.InterruptionKind
import dev.veyra.playback.PlaybackResource
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.platform.background.PlaybackForegroundFailureGuard
import dev.veyra.platform.background.playbackForegroundFailure
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Media3/local-decoder instrumentation contracts. Fixtures are authored sine tones, not a
 * replacement for the central live-source test. Emulator execution is NOT physical audible proof.
 */
@RunWith(AndroidJUnit4::class)
class AndroidAudioEngineTest {
    @Test fun localPlaybackPauseConfirmedSeekAndSeekToEnd() = nativeTest {
        engine.prepare(source("first"), 0, 1)
        await { it is EngineEvent.Ready && it.generation == 1L }
        engine.play(1)
        await { it is EngineEvent.Position && it.generation == 1L && it.positionMs >= 250 }
        engine.pause(1)
        val paused = onMain { engine.nativePlayer.currentPosition }
        delay(350)
        assertTrue(abs(onMain { engine.nativePlayer.currentPosition } - paused) < 80)
        engine.setRate(1.5f, 1)
        assertFalse(onMain { engine.nativePlayer.isPlaying })
        engine.seek(2_000, 1, 1)
        val seek = await { it is EngineEvent.SeekCompleted && it.seekSequence == 1L } as EngineEvent.SeekCompleted
        assertTrue(seek.success)
        assertTrue(abs(seek.positionMs - 2_000) < 150)
        engine.seek(8_000, 2, 1)
        await { it is EngineEvent.SeekCompleted && it.seekSequence == 2L }
        delay(300)
        assertFalse(observed.any { it is EngineEvent.Ended && it.generation == 1L })
        assertFalse(onMain { engine.nativeDecksPlaying.any { it } })
    }

    @Test fun nativeOverlapUsesHeadroomAndOldPauseCancelsUnacknowledgedHandover() = nativeTest {
        engine.prepare(source("outgoing"), 0, 10)
        await { it is EngineEvent.Ready && it.generation == 10L }
        engine.play(10)
        await { it is EngineEvent.Position && it.positionMs >= 250 }
        engine.preloadNext(source("incoming"), 0, 11, 10)
        await { it is EngineEvent.StageReady && it.nextGeneration == 11L }
        assertEquals(0f, onMain { engine.nativeDeckVolumes.minOrNull()!! }, 0f)
        engine.beginCrossfade(2_000, 11, 10)
        await { it is EngineEvent.CrossfadeProgress && it.fraction >= 0.2f }
        repeat(15) {
            onMain {
                assertTrue(engine.nativeDecksPlaying.all { it })
                val gains = engine.nativeDeckVolumes
                assertTrue(gains.all { gain -> gain in 0f..CrossfadeMix.MUSIC_GAIN })
                assertTrue(gains.sum() < 0.946f)
                assertEquals(CrossfadeMix.MUSIC_GAIN * CrossfadeMix.MUSIC_GAIN,
                    gains.sumOf { gain -> (gain * gain).toDouble() }.toFloat(), 0.0002f)
            }
            delay(20)
        }
        await { it is EngineEvent.CrossfadeCompleted && it.nextGeneration == 11L }
        assertFalse(observed.any { it.generation == 11L }) // proposal is still outgoing generation
        engine.pause(10)
        engine.confirmCrossfade(11, 10) // stale ACK cannot restart/orphan the incoming deck
        engine.play(11)
        delay(300)
        assertFalse(onMain { engine.nativeDecksPlaying.any { it } })
        assertFalse(observed.any { it.generation == 11L && it is EngineEvent.StateChanged && it.isPlaying })
    }

    @Test fun acknowledgedHandoverTransfersGenerationAndClosesOutgoingResource() = nativeTest {
        val outgoing = CountingResource()
        val incoming = CountingResource()
        engine.prepare(source("outgoing").copy(resource = outgoing), 0, 20)
        await { it is EngineEvent.Ready && it.generation == 20L }
        engine.play(20)
        await { it is EngineEvent.Position && it.positionMs >= 250 }
        engine.preloadNext(source("incoming").copy(resource = incoming), 0, 21, 20)
        await { it is EngineEvent.StageReady && it.nextGeneration == 21L }
        engine.beginCrossfade(500, 21, 20)
        await { it is EngineEvent.CrossfadeCompleted && it.nextGeneration == 21L }
        assertEquals(0, outgoing.closed.get())
        assertEquals(0, incoming.closed.get())
        engine.confirmCrossfade(21, 20)
        await { it is EngineEvent.Position && it.generation == 21L }
        engine.pause(20)
        assertTrue(onMain { engine.nativePlayer.isPlaying })
        engine.stop(22)
        assertEquals(1, outgoing.closed.get())
        assertEquals(1, incoming.closed.get())
        assertFalse(onMain { engine.nativeDecksPlaying.any { it } })
    }

    @Test fun staleAndInvalidSourcesReleaseResourcesAndPodcastsNeverOverlap() = nativeTest {
        engine.stop(30)
        val stale = CountingResource()
        engine.prepare(source("stale").copy(resource = stale), 0, 29)
        assertEquals(1, stale.closed.get())
        val missing = CountingResource()
        try {
            engine.prepare(source("missing").copy(uri = File(directory, "missing.wav").path, resource = missing), 0, 31)
            fail("Missing source was accepted")
        } catch (_: VeyraException) { }
        assertEquals(1, missing.closed.get())
        engine.prepare(source("episode", MediaKind.EPISODE), 0, 32)
        await { it is EngineEvent.Ready && it.generation == 32L }
        engine.play(32)
        await { it is EngineEvent.Position && it.generation == 32L && it.positionMs >= 250 }
        val rejected = CountingResource()
        engine.preloadNext(source("music").copy(resource = rejected), 0, 33, 32)
        engine.beginCrossfade(2_000, 33, 32)
        delay(300)
        assertEquals(1, rejected.closed.get())
        assertFalse(observed.any { it is EngineEvent.StageReady || it is EngineEvent.CrossfadeStarted })
        assertEquals(1, onMain { engine.nativeDecksPlaying.count { it } })
        assertEquals(1f, onMain { engine.nativePlayer.volume }, 0f)
    }

    @Test fun realTransientFocusLossSilencesBothDecksWithoutNativeAutoResume() = nativeTest {
        engine.prepare(source("outgoing"), 0, 40)
        await { it is EngineEvent.Ready && it.generation == 40L }
        engine.play(40)
        await { it is EngineEvent.Position && it.positionMs >= 250 }
        engine.preloadNext(source("incoming"), 0, 41, 40)
        await { it is EngineEvent.StageReady }
        engine.beginCrossfade(2_000, 41, 40)
        await { it is EngineEvent.CrossfadeProgress && it.fraction >= 0.2f }
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val listener = AudioManager.OnAudioFocusChangeListener { }
        val request = if (Build.VERSION.SDK_INT >= 26) AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener(listener).build() else null
        try {
            val acquired = onMain {
                if (Build.VERSION.SDK_INT >= 26) manager.requestAudioFocus(checkNotNull(request))
                else { @Suppress("DEPRECATION") manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) }
            }
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, acquired)
            await { it is EngineEvent.Interruption && it.kind == InterruptionKind.TRANSIENT_BEGAN }
            assertFalse(onMain { engine.nativeDecksPlaying.any { it } })
        } finally {
            onMain {
                if (Build.VERSION.SDK_INT >= 26) manager.abandonAudioFocusRequest(checkNotNull(request))
                else { @Suppress("DEPRECATION") manager.abandonAudioFocus(listener) }
            }
        }
        await { it is EngineEvent.Interruption && it.kind == InterruptionKind.TRANSIENT_ENDED }
        delay(250)
        assertFalse(onMain { engine.nativeDecksPlaying.any { it } })
    }

    @Test fun stopAndDuplicateStopWaitForOwnedResourceAfterImmediateSilence() = nativeTest {
        val resource = BlockingResource()
        engine.prepare(source("blocked-close").copy(resource = resource), 0, 50)
        await { it is EngineEvent.Ready && it.generation == 50L }
        engine.play(50)
        await { it is EngineEvent.Position && it.positionMs >= 250 }
        coroutineScope {
            val stopping = async { engine.stop(51) }
            var duplicate: kotlinx.coroutines.Deferred<Unit>? = null
            try {
                withTimeout(2_000) { resource.entered.await() }
                duplicate = async { engine.stop(51) }
                delay(50)
                assertFalse(stopping.isCompleted)
                assertFalse(checkNotNull(duplicate).isCompleted)
                assertFalse(onMain { engine.nativeDecksPlaying.any { it } })
            } finally { resource.finish.complete(Unit) }
            withTimeout(15_000) { stopping.await(); duplicate?.await() }
        }
        assertEquals(1, resource.closed.get())
    }

    @Test fun ordinaryCancellationRampsForSixtyMillisecondsWithoutRestartingOutgoing() = nativeTest {
        engine.prepare(source("outgoing"), 0, 60)
        await { it is EngineEvent.Ready && it.generation == 60L }
        engine.play(60)
        await { it is EngineEvent.Position && it.positionMs >= 250 }
        engine.preloadNext(source("incoming"), 0, 61, 60)
        await { it is EngineEvent.StageReady }
        engine.beginCrossfade(2_000, 61, 60)
        await { it is EngineEvent.CrossfadeProgress && it.fraction >= 0.3f }
        val before = SystemClock.elapsedRealtime()
        engine.cancelNext(61, 60)
        assertTrue(SystemClock.elapsedRealtime() - before >= 50)
        assertEquals(1, onMain { engine.nativeDecksPlaying.count { it } })
        assertEquals(CrossfadeMix.MUSIC_GAIN, onMain { engine.nativePlayer.volume }, 0.0001f)
        assertFalse(observed.any { it is EngineEvent.CrossfadeCompleted })
    }

    @Test fun restoreAtEndIsNotNaturalCompletionAndScrubBackCanPlay() = nativeTest {
        engine.prepare(source("restored"), 8_000, 70)
        await { it.generation == 70L && (it is EngineEvent.Ready || it is EngineEvent.StateChanged && it.phase == EnginePhase.ENDED) }
        engine.play(70)
        delay(300)
        assertFalse(observed.any { it is EngineEvent.Ended && it.generation == 70L })
        engine.seek(1_000, 1, 70)
        await { it is EngineEvent.SeekCompleted && it.seekSequence == 1L }
        engine.play(70)
        await { it is EngineEvent.Position && it.seekSequence == 1L && it.positionMs >= 1_300 }
    }

    @Test fun syntheticServiceLossSilencesImmediatelyAndRejectsAlreadyQueuedOutput() = nativeTest {
        engine.prepare(source("service-owned"), 0, 80)
        await { it is EngineEvent.Ready && it.generation == 80L }
        engine.play(80)
        await { it is EngineEvent.Position && it.positionMs >= 250 }
        onMain { engine.foregroundServiceLost() }
        await { it is EngineEvent.Interruption && it.generation == 80L && it.kind == InterruptionKind.PERMANENT }
        engine.play(80) // Simulate an old queued request before the controller consumes the event.
        val rejected = CountingResource()
        engine.preloadNext(source("stale-next").copy(resource = rejected), 0, 81, 80)
        delay(100)
        assertFalse(onMain { engine.nativeDecksPlaying.any { it } })
        assertEquals(1, rejected.closed.get())
        engine.pause(80) // Shared interruption acknowledgement.
        engine.play(80) // A later explicit user request may establish a fresh foreground lease.
        withTimeout(2_000) { while (!onMain { engine.nativePlayer.isPlaying }) delay(20) }
    }

    @Test fun serviceLossDuringForegroundAcknowledgementCannotRestartNativeOutput() {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        nativeTest(beforePlay = { entered.complete(Unit); finish.await() }) {
            engine.prepare(source("waiting-for-service"), 0, 90)
            await { it is EngineEvent.Ready && it.generation == 90L }
            coroutineScope {
                val starting = async { engine.play(90) }
                try {
                    withTimeout(2_000) { entered.await() }
                    onMain { engine.foregroundServiceLost() }
                } finally { finish.complete(Unit) }
                withTimeout(2_000) { starting.await() }
            }
            delay(100)
            assertFalse(onMain { engine.nativeDecksPlaying.any { it } })
        }
    }

    @Test fun injectedForegroundStartRejectionIsTypedAndDoesNotFalselyReleaseTheNativeSource() {
        lateinit var guard: PlaybackForegroundFailureGuard
        var foregroundRequests = 0
        nativeTest(beforePlay = {
            // A later request would succeed, as a newly created service could. The failed native
            // generation must not reach that allowance, even after an already-queued Pause.
            foregroundRequests++
            if (foregroundRequests == 1) withContext(Dispatchers.Main.immediate) {
                guard.requireForeground { throw SecurityException("authored-private-notification-detail") }
            }
        }) {
            val resource = BlockingResource()
            var serviceStops = 0
            guard = PlaybackForegroundFailureGuard({}, { true }, engine::foregroundServiceLost, {}, { serviceStops++ })
            try {
                engine.prepare(source("rejected-start").copy(resource = resource), 0, 95)
                await { it is EngineEvent.Ready && it.generation == 95L }
                try { engine.play(95); fail("Foreground rejection was acknowledged as successful Play") }
                catch (failure: VeyraException) {
                    assertEquals(FailureCode.BACKGROUND_RESTRICTED, failure.failure.code)
                    assertEquals(playbackForegroundFailure(), failure.failure)
                    assertNull(failure.cause)
                }
                val failure = await { it is EngineEvent.Error && it.generation == 95L } as EngineEvent.Error
                assertEquals(playbackForegroundFailure(), failure.failure)
                assertEquals(1, serviceStops)
                assertFalse(onMain { engine.nativeDecksPlaying.any { it } })
                assertEquals("Retirement does not pretend the native source was cleared", 1,
                    onMain { engine.nativePlayer.mediaItemCount })
                assertFalse("Retirement must not acknowledge resource release", resource.entered.isCompleted)
                assertFalse(observed.any { it is EngineEvent.StateChanged && it.generation == 95L && it.phase == EnginePhase.IDLE })
                engine.play(95) // An already-queued Play cannot retry a retired foreground owner.
                assertEquals(1, serviceStops)
                engine.pause(95)
                engine.play(95)
                assertEquals("A queued Pause incorrectly cleared the terminal policy failure", 1, foregroundRequests)
                assertFalse(onMain { engine.nativeDecksPlaying.any { it } })
                assertFalse(resource.entered.isCompleted)
                coroutineScope {
                    val stopping = async { engine.stop(96) }
                    try {
                        withTimeout(2_000) { resource.entered.await() }
                        assertFalse(stopping.isCompleted)
                        assertEquals(0, resource.closed.get())
                        assertFalse(onMain { engine.nativeDecksPlaying.any { it } })
                    } finally { resource.finish.complete(Unit) }
                    withTimeout(15_000) { stopping.await() }
                }
                assertEquals(1, resource.closed.get())
            } finally { resource.finish.complete(Unit) }
        }
    }

    @Test fun injectedNotificationRejectionSurvivesConfirmedHandover() = notificationRejectionDuringHandover(confirm = true)

    @Test fun injectedNotificationRejectionSurvivesCancelledHandover() = notificationRejectionDuringHandover(confirm = false)

    private fun notificationRejectionDuringHandover(confirm: Boolean) {
        var foregroundRequests = 0
        nativeTest(beforePlay = { foregroundRequests++ }) {
            val outgoing = CountingResource()
            val incoming = CountingResource()
            engine.prepare(source("policy-outgoing").copy(resource = outgoing), 0, 100)
            await { it is EngineEvent.Ready && it.generation == 100L }
            engine.play(100)
            await { it is EngineEvent.Position && it.generation == 100L && it.positionMs >= 250 }
            engine.preloadNext(source("policy-incoming").copy(resource = incoming), 0, 101, 100)
            await { it is EngineEvent.StageReady && it.nextGeneration == 101L }
            engine.beginCrossfade(500, 101, 100)
            await { it is EngineEvent.CrossfadeCompleted && it.nextGeneration == 101L }
            val guard = PlaybackForegroundFailureGuard({}, { true }, engine::foregroundServiceLost, {}, {})
            onMain {
                // Concrete policy exception, injected into the same boundary used by real refreshes.
                // This is native deck/error propagation evidence only after execution, not OS denial.
                val rejection = if (Build.VERSION.SDK_INT >= 31)
                    ForegroundServiceStartNotAllowedException("authored-private-notification-detail")
                else SecurityException("authored-private-notification-detail")
                assertFalse(guard.attempt { throw rejection })
                assertFalse(engine.nativeDecksPlaying.any { it })
            }
            delay(100)
            assertEquals(0, outgoing.closed.get())
            assertEquals(0, incoming.closed.get())
            assertFalse("The failure cannot guess which side of an unacknowledged handover won",
                observed.any { it is EngineEvent.Error && it.generation in 100L..101L })
            if (confirm) engine.confirmCrossfade(101, 100) else engine.cancelNext(101, 100)
            val selected = if (confirm) 101L else 100L
            val failure = await { it is EngineEvent.Error && it.generation == selected } as EngineEvent.Error
            assertEquals(playbackForegroundFailure(), failure.failure)
            assertEquals("The selected native source remains owned until real Stop drains it", 0,
                (if (confirm) incoming else outgoing).closed.get())
            assertEquals(1, onMain { engine.nativePlayer.mediaItemCount })
            engine.play(selected)
            engine.pause(selected)
            engine.play(selected)
            assertEquals("Selected-generation Pause must retain the terminal service-failure hold", 1, foregroundRequests)
            assertFalse(onMain { engine.nativeDecksPlaying.any { it } })
            assertFalse(observed.any { it is EngineEvent.StateChanged && it.generation == selected && it.phase == EnginePhase.IDLE })
            engine.stop(102)
            assertEquals(1, outgoing.closed.get())
            assertEquals(1, incoming.closed.get())
        }
    }

    private fun nativeTest(beforePlay: suspend () -> Unit = {}, block: suspend Harness.() -> Unit) {
        // Focus eligibility on target 35+ requires a top activity or an actual foreground service.
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val directory = File(context.cacheDir, "native-contract-${UUID.randomUUID()}").apply { mkdirs() }
                val file = authoredWave(File(directory, "tone.wav"))
                val engine = withContext(Dispatchers.Main.immediate) { AndroidAudioEngine(context, beforePlay) }
                val harness = Harness(context, directory, file, engine)
                val collector = launch { engine.events.collect { event -> harness.observed += event; harness.events.send(event) } }
                try { harness.block() }
                finally {
                    try { engine.close() }
                    finally { collector.cancelAndJoin(); directory.deleteRecursively() }
                }
            }
        }
    }

    private class Harness(val context: Context, val directory: File, val file: File, val engine: AndroidAudioEngine) {
        val observed = CopyOnWriteArrayList<EngineEvent>()
        val events = Channel<EngineEvent>(Channel.UNLIMITED)
        fun source(id: String, kind: MediaKind = MediaKind.MUSIC) = ResolvedPlayable(id, file.absolutePath,
            mimeType = "audio/wav", codecs = "pcm", isLocal = true,
            item = MediaItem(id, "Authored native test tone", kind, SourceRef(SourceProvider.LOCAL, id)))
        suspend fun await(predicate: (EngineEvent) -> Boolean): EngineEvent = withTimeout(15_000) {
            var event = events.receive()
            while (!predicate(event)) event = events.receive()
            event
        }
        suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }
    }

    private class CountingResource : PlaybackResource {
        val closed = AtomicInteger()
        override suspend fun close() { closed.incrementAndGet() }
    }
    private class BlockingResource : PlaybackResource {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val closed = AtomicInteger()
        override suspend fun close() { entered.complete(Unit); finish.await(); closed.incrementAndGet() }
    }
}

/** Original 8-second 440 Hz PCM tone at conservative amplitude; generated only inside test cache. */
internal fun authoredWave(file: File): File {
    val sampleRate = 44_100
    val frames = sampleRate * 8
    val dataBytes = frames * 2
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(dataBytes + 36).put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(dataBytes).array()
    FileOutputStream(file).use { output ->
        output.write(header)
        val chunk = ByteBuffer.allocate(8_192).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until frames) {
            chunk.putShort((sin(index * 2 * PI * 440 / sampleRate) * 6_000).toInt().toShort())
            if (!chunk.hasRemaining()) { output.write(chunk.array()); chunk.clear() }
        }
        if (chunk.position() > 0) output.write(chunk.array(), 0, chunk.position())
    }
    return file
}
