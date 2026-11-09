@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.platform.audio

import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.OfflineAsset
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.CrossfadeMix
import dev.veyra.playback.EngineEvent
import dev.veyra.playback.InterruptionKind
import dev.veyra.playback.PlaybackResource
import dev.veyra.playback.ResolvedPlayable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.AVFAudio.*
import platform.Foundation.*
import platform.QuartzCore.CACurrentMediaTime

/**
 * Executes real AVPlayer/AVAssetReader contracts on Apple test targets. The generated tone is NOT
 * compliant central-source evidence. Posted interruption notifications test handling, not a real
 * phone call, route removal, or physical audible output. Those remain separate hardware gates.
 */
class IosAudioEngineTest {
    @Test fun realLocalPlaybackPauseSeekAndNoNaturalEndFromScrubbing() = nativeTest {
        engine.prepare(source("tone"), 0, 1)
        await { it is EngineEvent.Ready && it.generation == 1L }
        engine.play(1)
        await { it is EngineEvent.Position && it.positionMs >= 250 }
        engine.pause(1)
        engine.setRate(1.5f, 1)
        delay(300)
        assertFalse(engine.nativeDecksPlaying.any { it })
        engine.seek(2_000, 1, 1)
        val seek = await { it is EngineEvent.SeekCompleted && it.seekSequence == 1L } as EngineEvent.SeekCompleted
        assertTrue(seek.success)
        assertTrue(abs(seek.positionMs - 2_000) < 150)
        engine.seek(8_000, 2, 1)
        await { it is EngineEvent.SeekCompleted && it.seekSequence == 2L }
        delay(300)
        assertFalse(observed.any { it is EngineEvent.Ended && it.generation == 1L })
        assertFalse(engine.nativeDecksPlaying.any { it })
    }

    @Test fun nativeOverlapAndPauseBeforeHandoverAck() = nativeTest {
        preparePair(10, 11)
        engine.beginCrossfade(2_000, 11, 10)
        await { it is EngineEvent.CrossfadeProgress && it.fraction >= 0.2f }
        repeat(15) {
            assertTrue(engine.nativeDecksPlaying.all { it })
            val gains = engine.nativeDeckVolumes
            assertTrue(gains.all { it in 0f..CrossfadeMix.MUSIC_GAIN })
            assertTrue(gains.sum() < 0.946f)
            assertTrue(abs(gains.sumOf { (it * it).toDouble() } - CrossfadeMix.MUSIC_GAIN * CrossfadeMix.MUSIC_GAIN) < 0.0002)
            delay(20)
        }
        await { it is EngineEvent.CrossfadeCompleted && it.nextGeneration == 11L }
        assertFalse(observed.any { it.generation == 11L })
        engine.pause(10)
        engine.confirmCrossfade(11, 10)
        engine.play(11)
        delay(250)
        assertFalse(engine.nativeDecksPlaying.any { it })
        assertFalse(observed.any { it is EngineEvent.StateChanged && it.generation == 11L && it.isPlaying })
    }

    @Test fun interruptionDuringAckWindowFollowsWinningGenerationAndDoesNotAutoResume() = nativeTest {
        preparePair(20, 21)
        engine.beginCrossfade(500, 21, 20)
        await { it is EngineEvent.CrossfadeCompleted }
        NSNotificationCenter.defaultCenter.postNotificationName(AVAudioSessionInterruptionNotification, `object` = null,
            userInfo = mapOf(AVAudioSessionInterruptionTypeKey to AVAudioSessionInterruptionTypeBegan))
        assertFalse(engine.nativeDecksPlaying.any { it })
        engine.confirmCrossfade(21, 20)
        await { it is EngineEvent.Interruption && it.generation == 21L && it.kind == InterruptionKind.TRANSIENT_BEGAN }
        NSNotificationCenter.defaultCenter.postNotificationName(AVAudioSessionInterruptionNotification, `object` = null,
            userInfo = mapOf(AVAudioSessionInterruptionTypeKey to AVAudioSessionInterruptionTypeEnded,
                AVAudioSessionInterruptionOptionKey to AVAudioSessionInterruptionOptionShouldResume))
        await { it is EngineEvent.Interruption && it.generation == 21L && it.kind == InterruptionKind.TRANSIENT_ENDED }
        delay(300)
        assertFalse(engine.nativeDecksPlaying.any { it })
    }

    @Test fun mediaServicesResetDuringHandoverReleasesBothResourcesAndNeedsFreshPrepare() = nativeTest {
        val outgoing = CountingResource()
        val incoming = CountingResource()
        engine.prepare(source("first").copy(resource = outgoing), 0, 30)
        await { it is EngineEvent.Ready && it.generation == 30L }
        engine.play(30)
        await { it is EngineEvent.Position && it.positionMs >= 250 }
        engine.preloadNext(source("second").copy(resource = incoming), 0, 31, 30)
        await { it is EngineEvent.StageReady && it.nextGeneration == 31L }
        engine.beginCrossfade(500, 31, 30)
        await { it is EngineEvent.CrossfadeCompleted }
        NSNotificationCenter.defaultCenter.postNotificationName(AVAudioSessionMediaServicesWereResetNotification, `object` = null)
        engine.confirmCrossfade(31, 30)
        await { it is EngineEvent.Interruption && it.generation == 31L && it.kind == InterruptionKind.MEDIA_SERVICES_RESET }
        engine.play(31)
        assertFalse(engine.nativeDecksPlaying.any { it })
        engine.stop(32) // joins resource disposal, including reset's already-detached players
        assertEquals(1, outgoing.closed)
        assertEquals(1, incoming.closed)
        engine.prepare(source("fresh"), 0, 33)
        await { it is EngineEvent.Ready && it.generation == 33L }
    }

    @Test fun staleSourcesAreDisposedAndPodcastsKeepUnityWithoutOverlap() = nativeTest {
        engine.stop(40)
        val stale = CountingResource()
        engine.prepare(source("stale").copy(resource = stale), 0, 39)
        assertEquals(1, stale.closed)
        engine.prepare(source("episode", MediaKind.EPISODE), 0, 41)
        await { it is EngineEvent.Ready && it.generation == 41L }
        engine.play(41)
        await { it is EngineEvent.Position && it.positionMs >= 250 }
        val rejected = CountingResource()
        engine.preloadNext(source("music").copy(resource = rejected), 0, 42, 41)
        engine.beginCrossfade(2_000, 42, 41)
        delay(300)
        assertEquals(1, rejected.closed)
        assertFalse(observed.any { it is EngineEvent.StageReady || it is EngineEvent.CrossfadeStarted })
        assertEquals(1, engine.nativeDecksPlaying.count { it })
        assertEquals(1f, engine.nativeDeckVolumes.first())
    }

    @Test fun stopAndDuplicateStopWaitForResourceButSilenceImmediately() = nativeTest {
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
                assertFalse(engine.nativeDecksPlaying.any { it })
            } finally { resource.finish.complete(Unit) }
            withTimeout(2_000) { stopping.await(); duplicate?.await() }
        }
        assertEquals(1, resource.closed)
    }

    @Test fun ordinaryCancellationRampsAndLeavesOnlyOutgoingAtFixedHeadroom() = nativeTest {
        preparePair(60, 61)
        engine.beginCrossfade(2_000, 61, 60)
        await { it is EngineEvent.CrossfadeProgress && it.fraction >= 0.3f }
        val began = CACurrentMediaTime()
        engine.cancelNext(61, 60)
        assertTrue(CACurrentMediaTime() - began >= 0.05)
        assertEquals(1, engine.nativeDecksPlaying.count { it })
        assertTrue(abs(engine.nativeDeckVolumes.first() - CrossfadeMix.MUSIC_GAIN) < 0.0001f)
        assertFalse(observed.any { it is EngineEvent.CrossfadeCompleted })
    }

    @Test fun restoreAtEndDoesNotCompleteEpisodeAndScrubBackPlays() = nativeTest {
        engine.prepare(source("restored", MediaKind.EPISODE), 8_000, 70)
        await { it is EngineEvent.Ready && it.generation == 70L }
        engine.play(70)
        delay(400)
        assertFalse(observed.any { it is EngineEvent.Ended && it.generation == 70L })
        engine.seek(1_000, 1, 70)
        await { it is EngineEvent.SeekCompleted && it.seekSequence == 1L }
        engine.play(70)
        await { it is EngineEvent.Position && it.seekSequence == 1L && it.positionMs >= 1_300 }
    }

    @Test fun assetReaderDecodesFixtureAndRejectsMissingMalformedWrongSizeAndOutsideRoot() = nativeTest {
        val validator = IosAssetValidator(directory)
        val bytes = authoredWaveBytes()
        val asset = OfflineAsset("asset", "tone", file, bytes.size.toLong(), mimeType = "audio/wav")
        assertTrue(validator.validate(asset) is AssetValidation.Valid)
        assertTrue(validator.validate(asset.copy(sizeBytes = bytes.size + 1L)) is AssetValidation.Invalid)
        assertTrue(validator.validate(asset.copy(uri = "$directory/missing.wav")) is AssetValidation.Invalid)
        writeBytes("$directory/broken.wav", ByteArray(512) { 0x42 })
        assertTrue(validator.validate(asset.copy(uri = "$directory/broken.wav", sizeBytes = 512)) is AssetValidation.Invalid)
        val other = "$directory/../outside-${NSUUID().UUIDString}.wav"
        try {
            writeBytes(other, bytes)
            assertTrue(validator.validate(asset.copy(uri = other)) is AssetValidation.Invalid)
        } finally { NSFileManager.defaultManager.removeItemAtPath(other, error = null) }
    }

    private fun nativeTest(block: suspend Harness.() -> Unit) {
        check(NSThread.isMainThread) { "Apple native contract tests must be invoked on Main." }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var finished = false
        var failure: Throwable? = null
        val directory = NSTemporaryDirectory().trimEnd('/') + "/veyra-native-${NSUUID().UUIDString}"
        check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
        val file = "$directory/tone.wav"
        writeBytes(file, authoredWaveBytes())
        scope.launch {
            var engine: IosAudioEngine? = null
            var collector: Job? = null
            try {
                val created = IosAudioEngine()
                engine = created
                val harness = Harness(directory, file, created)
                collector = launch { created.events.collect { event -> harness.observed += event; harness.events.send(event) } }
                harness.block()
            }
            catch (error: Throwable) { failure = error }
            finally {
                withContext(NonCancellable) {
                    try { engine?.close() }
                    catch (error: Throwable) { if (failure == null) failure = error }
                    finally { collector?.cancelAndJoin() }
                }
                finished = true
            }
        }
        // runBlocking on Main would prevent AVPlayer notifications and Darwin dispatch from
        // running. Pump the real NSRunLoop while the asynchronous native test owns the engine.
        val deadline = CACurrentMediaTime() + 35.0
        while (!finished && CACurrentMediaTime() < deadline) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
        }
        scope.cancel()
        val cleanupDeadline = CACurrentMediaTime() + 10.0
        while (!finished && CACurrentMediaTime() < cleanupDeadline) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
        }
        NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
        if (!finished) fail("Native audio test timed out; no successful playback is inferred")
        failure?.let { throw it }
    }

    private class Harness(val directory: String, val file: String, val engine: IosAudioEngine) {
        val observed = mutableListOf<EngineEvent>()
        val events = Channel<EngineEvent>(Channel.UNLIMITED)
        fun source(id: String, kind: MediaKind = MediaKind.MUSIC) = ResolvedPlayable(id, file,
            mimeType = "audio/wav", codecs = "pcm", isLocal = true,
            item = MediaItem(id, "Authored native test tone", kind, SourceRef(SourceProvider.LOCAL, id)))
        suspend fun await(predicate: (EngineEvent) -> Boolean): EngineEvent = withTimeout(15_000) {
            var event = events.receive()
            while (!predicate(event)) event = events.receive()
            event
        }
        suspend fun preparePair(generation: Long, nextGeneration: Long) {
            engine.prepare(source("outgoing"), 0, generation)
            await { it is EngineEvent.Ready && it.generation == generation }
            engine.play(generation)
            await { it is EngineEvent.Position && it.positionMs >= 250 }
            engine.preloadNext(source("incoming"), 0, nextGeneration, generation)
            await { it is EngineEvent.StageReady && it.nextGeneration == nextGeneration }
        }
    }

    private class CountingResource : PlaybackResource {
        var closed = 0
        override suspend fun close() { closed++ }
    }
    private class BlockingResource : PlaybackResource {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var closed = 0
        override suspend fun close() { entered.complete(Unit); finish.await(); closed++ }
    }
}

private fun writeBytes(path: String, bytes: ByteArray) {
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    check(data.writeToFile(path, atomically = true))
}

private fun authoredWaveBytes(): ByteArray {
    val rate = 44_100
    val samples = rate * 8
    val bytes = ByteArray(44 + samples * 2)
    fun word(offset: Int, value: Int, count: Int = 4) {
        repeat(count) { shift -> bytes[offset + shift] = (value ushr (shift * 8)).toByte() }
    }
    fun tag(offset: Int, text: String) { text.forEachIndexed { index, c -> bytes[offset + index] = c.code.toByte() } }
    tag(0, "RIFF"); word(4, bytes.size - 8); tag(8, "WAVEfmt "); word(16, 16)
    word(20, 1, 2); word(22, 1, 2); word(24, rate); word(28, rate * 2); word(32, 2, 2); word(34, 16, 2)
    tag(36, "data"); word(40, samples * 2)
    repeat(samples) { index -> word(44 + index * 2, (sin(index * 2 * PI * 440 / rate) * 6_000).toInt(), 2) }
    return bytes
}
