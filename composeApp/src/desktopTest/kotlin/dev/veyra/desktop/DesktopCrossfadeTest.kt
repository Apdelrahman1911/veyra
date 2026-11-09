package dev.veyra.desktop

import dev.veyra.desktop.audio.DesktopAudioEngine
import dev.veyra.desktop.runtime.DesktopRuntimeConfig
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.FailureCode
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.model.VeyraException
import dev.veyra.playback.CrossfadeMix
import dev.veyra.playback.EngineEvent
import dev.veyra.playback.PlaybackResource
import dev.veyra.playback.ResolvedPlayable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real bundled native decoders, deliberately NULL output; no physical listening claim. */
class DesktopCrossfadeTest {
    private fun config(): DesktopRuntimeConfig {
        val root = System.getenv("VEYRA_PROJECT_ROOT")?.let(Path::of)
            ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("settings.gradle.kts")) }
        // macOS rejects a second copy of the bundled libmpv (duplicate Swift/ObjC classes) when
        // one JVM loads it through two different test data directories. Share the already-used
        // native-test directory so every desktop test in this worker resolves one dylib path.
        val directory = root.resolve(".work/desktop-audio/kotlin-tests")
        Files.createDirectories(directory)
        javaClass.getResourceAsStream("/dev/veyra/desktop/tone.mp3")!!.use { Files.copy(it, directory.resolve("tone.mp3"), REPLACE_EXISTING) }
        return DesktopRuntimeConfig(directory, silentTestOutput = true)
    }

    private class Resource : PlaybackResource {
        val closed = AtomicBoolean()
        override suspend fun close() { closed.set(true) }
    }

    private fun source(config: DesktopRuntimeConfig, id: String, resource: PlaybackResource? = null, episode: Boolean = false) = ResolvedPlayable(
        id, config.dataDirectory.resolve("tone.mp3").toUri().toString(), isLocal = true,
        item = MediaItem(id, "Authored 440 Hz fixture", if (episode) MediaKind.EPISODE else MediaKind.MUSIC, SourceRef(SourceProvider.LOCAL, id)),
        resource = resource,
    )

    private class Events(scope: CoroutineScope, engine: DesktopAudioEngine) {
        val all = CopyOnWriteArrayList<EngineEvent>()
        private val channel = Channel<EngineEvent>(Channel.UNLIMITED)
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { all += it; channel.send(it) } }
        suspend fun next(predicate: (EngineEvent) -> Boolean): EngineEvent = withTimeout(20_000) {
            while (true) { val event = channel.receive(); if (predicate(event)) return@withTimeout event }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    @Test fun nativeOverlapWaitsForAcknowledgementAndKeepsIncomingCallbacksPrivate() = runBlocking {
        val config = config()
        val oldResource = Resource(); val nextResource = Resource()
        val engine = DesktopAudioEngine.create(config)
        val events = Events(this, engine)
        try {
            engine.prepare(source(config, "outgoing", oldResource), 0, 1)
            events.next { it is EngineEvent.Ready }
            engine.play(1)
            events.next { it is EngineEvent.Position && it.positionMs > 400 }
            engine.preloadNext(source(config, "incoming", nextResource), 0, 2, 1)
            events.next { it is EngineEvent.StageReady }
            val staged = engine.diagnostics()
            assertEquals("yes", staged["incoming-pause"])
            assertTrue((staged["incoming-volume"]?.toFloatOrNull() ?: 100f) < .001f)
            val before = staged["incoming-position-ms"]!!.toLong()
            delay(250)
            assertTrue(abs(engine.diagnostics()["incoming-position-ms"]!!.toLong() - before) < 100)
            engine.beginCrossfade(800, 2, 1)
            events.next { it is EngineEvent.CrossfadeStarted }
            val complete = assertIs<EngineEvent.CrossfadeCompleted>(events.next { it is EngineEvent.CrossfadeCompleted })
            assertTrue(complete.positionMs >= 700)
            assertFalse(events.all.any { it.generation == 2L }, "Incoming ordinary events escaped before ACK")
            assertFalse(oldResource.closed.get(), "Outgoing resource closed before ownership commit")
            val native = engine.diagnostics()
            assertTrue((native["volume"]!!.toFloat()) < .001f)
            assertTrue(abs(native["incoming-volume"]!!.toFloat() / 100 - CrossfadeMix.MUSIC_GAIN) < .001f)
            engine.confirmCrossfade(2, 1)
            events.next { it is EngineEvent.Position && it.generation == 2L && it.positionMs > complete.positionMs }
            assertTrue(oldResource.closed.get())
            assertFalse(nextResource.closed.get())
            assertEquals("1", engine.diagnostics()["managed-decks"])
        } finally {
            events.job.cancel()
            withContext(NonCancellable) { engine.close() }
        }
        assertTrue(nextResource.closed.get())
    }

    @Test fun pauseWinningPendingAckNeverLeavesTheIncomingDeckPlaying() = runBlocking {
        val config = config(); val resource = Resource()
        val engine = DesktopAudioEngine.create(config); val events = Events(this, engine)
        try {
            engine.prepare(source(config, "old"), 0, 10)
            events.next { it is EngineEvent.Ready }
            engine.play(10)
            events.next { it is EngineEvent.Position && it.positionMs > 300 }
            engine.preloadNext(source(config, "new", resource), 0, 11, 10)
            events.next { it is EngineEvent.StageReady }
            engine.beginCrossfade(300, 11, 10)
            events.next { it is EngineEvent.CrossfadeCompleted }
            engine.pause(10)
            engine.confirmCrossfade(11, 10)
            events.next { it is EngineEvent.CrossfadeCancelled }
            delay(150)
            val native = engine.diagnostics()
            assertEquals("1", native["managed-decks"])
            assertEquals("yes", native["pause"])
            assertTrue(resource.closed.get())
            assertFalse(events.all.any { it.generation == 11L })
        } finally { events.job.cancel(); withContext(NonCancellable) { engine.close() } }
    }

    @Test fun podcastsNeverStageAndRetainUnityGain() = runBlocking {
        val config = config(); val resource = Resource()
        val engine = DesktopAudioEngine.create(config); val events = Events(this, engine)
        try {
            engine.prepare(source(config, "podcast", episode = true), 0, 3)
            events.next { it is EngineEvent.Ready }
            assertEquals(100f, engine.diagnostics()["volume"]!!.toFloat())
            engine.preloadNext(source(config, "next-podcast", resource, episode = true), 0, 4, 3)
            events.next { it is EngineEvent.StageFailed }
            assertTrue(resource.closed.get())
            assertEquals("1", engine.diagnostics()["managed-decks"])
        } finally { events.job.cancel(); withContext(NonCancellable) { engine.close() } }
    }

    @Test fun rapidSeeksAndReplacementRejectStaleCompletionsAndCloseResources() = runBlocking {
        val config = config(); val old = Resource(); val stale = Resource()
        val engine = DesktopAudioEngine.create(config); val events = Events(this, engine)
        try {
            engine.prepare(source(config, "old", old), 0, 20)
            events.next { it is EngineEvent.Ready }
            engine.seek(2_000, 1, 20); engine.seek(4_000, 2, 20); engine.seek(8_000, 3, 20)
            val seek = assertIs<EngineEvent.SeekCompleted>(events.next { it is EngineEvent.SeekCompleted && it.seekSequence == 3L })
            assertTrue(seek.success && abs(seek.positionMs - 8_000) < 1_000)
            engine.prepare(source(config, "new"), 0, 21)
            events.next { it is EngineEvent.Ready && it.generation == 21L }
            assertTrue(old.closed.get())
            engine.prepare(source(config, "stale", stale), 0, 19)
            assertTrue(stale.closed.get())
            engine.play(21)
            events.next { it is EngineEvent.Position && it.generation == 21L && it.positionMs > 400 }
            assertFalse(events.all.any { it is EngineEvent.Ended && it.generation == 21L })
        } finally { events.job.cancel(); withContext(NonCancellable) { engine.close(); engine.close() } }
    }

    @Test fun failedRetirementCannotBeForgottenBySuccessfulUnrelatedStopOrShutdown(): Unit = runBlocking {
        val config = config()
        var failedCloseAttempts = 0
        val bad = object : PlaybackResource {
            override suspend fun close() { failedCloseAttempts++; error("Authored retired-resource failure") }
        }
        val next = Resource()
        val engine = DesktopAudioEngine.create(config)
        val events = Events(this, engine)
        try {
            engine.prepare(source(config, "failed-retirement", bad), 0, 30)
            events.next { it is EngineEvent.Ready }
            engine.play(30)
            events.next { it is EngineEvent.Position && it.positionMs > 300 }
            engine.preloadNext(source(config, "unrelated-current", next), 0, 31, 30)
            events.next { it is EngineEvent.StageReady }
            engine.beginCrossfade(300, 31, 30)
            events.next { it is EngineEvent.CrossfadeCompleted }
            assertEquals(FailureCode.RECOVERY_REQUIRED,
                assertFailsWith<VeyraException> { engine.confirmCrossfade(31, 30) }.failure.code)
            assertTrue(failedCloseAttempts > 0)
            assertEquals("yes", engine.diagnostics()["pause"], "Cleanup failure must silence the promoted deck")
            // Shared deletion uses stop as its native-file release fence. Successfully releasing
            // the unrelated current deck must NOT acknowledge the earlier retired file's release.
            assertEquals(FailureCode.RECOVERY_REQUIRED,
                assertFailsWith<VeyraException> { engine.stop(32) }.failure.code)
            assertTrue(next.closed.get(), "The unrelated deck should still be cleaned up")
            assertFailsWith<VeyraException> { engine.stop(33) }
        } finally {
            events.job.cancel()
            withContext(NonCancellable) {
                assertFailsWith<VeyraException> { engine.close() }
                assertFailsWith<VeyraException> { engine.close() }
            }
        }
    }

    @Test fun failedCancelledAndRejectedResourcesPoisonLaterReleaseAcknowledgements() = runBlocking {
        for (staged in listOf(true, false)) {
            val config = config(); val current = Resource()
            val bad = object : PlaybackResource { override suspend fun close(): Unit = error("Authored cleanup failure") }
            val engine = DesktopAudioEngine.create(config); val events = Events(this, engine)
            try {
                engine.prepare(source(config, "current", current), 0, 40)
                events.next { it is EngineEvent.Ready }
                if (staged) {
                    engine.preloadNext(source(config, "cancelled", bad), 0, 41, 40)
                    events.next { it is EngineEvent.StageReady }
                    assertFailsWith<VeyraException> { engine.cancelNext(41, 40) }
                } else assertFailsWith<VeyraException> { engine.prepare(source(config, "stale", bad), 0, 39) }
                assertFailsWith<VeyraException> { engine.stop(42) }
                assertTrue(current.closed.get())
                assertFailsWith<VeyraException> { engine.cancelNext(41, 42) }
            } finally {
                events.job.cancel()
                withContext(NonCancellable) { assertFailsWith<VeyraException> { engine.close() } }
            }
        }
    }

    @Test fun shutdownCleanupFailureIsNotReportedAsSuccessfulClose(): Unit = runBlocking {
        val config = config()
        val bad = object : PlaybackResource { override suspend fun close(): Unit = error("Authored shutdown failure") }
        val engine = DesktopAudioEngine.create(config); val events = Events(this, engine)
        try {
            engine.prepare(source(config, "shutdown", bad), 0, 50)
            events.next { it is EngineEvent.Ready }
        } finally {
            events.job.cancel()
            withContext(NonCancellable) {
                assertEquals(FailureCode.RECOVERY_REQUIRED,
                    assertFailsWith<VeyraException> { engine.close() }.failure.code)
                assertFailsWith<VeyraException> { engine.close() }
            }
        }
    }
}
