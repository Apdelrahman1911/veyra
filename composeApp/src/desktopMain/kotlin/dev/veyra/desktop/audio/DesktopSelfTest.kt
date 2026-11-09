package dev.veyra.desktop.audio

import dev.veyra.desktop.runtime.DesktopRuntimeConfig
import dev.veyra.playback.EngineEvent
import dev.veyra.playback.EnginePhase
import dev.veyra.playback.ResolvedPlayable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Runs against the same engine used by the app, including native artifact integrity and bundled CA roots. */
object DesktopSelfTest {
    suspend fun run(fixture: Path, config: DesktopRuntimeConfig): String = coroutineScope {
        val operations = mutableListOf<String>()
        val engine = DesktopAudioEngine.create(config)
        val ready = CompletableDeferred<EngineEvent.Ready>()
        val seeks = ConcurrentHashMap<Long, CompletableDeferred<EngineEvent.SeekCompleted>>()
        val position = AtomicReference<EngineEvent.Position?>()
        val state = AtomicReference<EngineEvent.StateChanged?>()
        val error = AtomicReference<EngineEvent.Error?>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { event ->
                when (event) {
                    is EngineEvent.Ready -> ready.complete(event)
                    is EngineEvent.Position -> position.set(event)
                    is EngineEvent.StateChanged -> state.set(event)
                    is EngineEvent.SeekCompleted -> seeks[event.seekSequence]?.complete(event)
                    is EngineEvent.Error -> { error.set(event); ready.completeExceptionally(IllegalStateException(event.failure.code.name)) }
                    else -> Unit
                }
            }
        }
        suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(20_000) {
            while (!condition()) {
                check(error.get() == null) { "Native engine reported an error" }
                delay(25)
            }
        }
        suspend fun seek(target: Long, sequence: Long): EngineEvent.SeekCompleted {
            val answer = CompletableDeferred<EngineEvent.SeekCompleted>()
            seeks[sequence] = answer
            engine.seek(target, sequence, 1)
            return withTimeout(20_000) { answer.await() }.also {
                check(it.success && abs(it.positionMs - target) <= 1_000) { "Native seek did not converge" }
            }
        }
        try {
            engine.prepare(ResolvedPlayable("self-test-authored-audio", fixture.toUri().toString(), isLocal = true), 0, 1)
            val prepared = withTimeout(20_000) { ready.await() }
            check(prepared.seekable && (prepared.durationMs ?: 0) >= 4_000) { "Self-test requires a seekable audio fixture of at least four seconds" }
            operations += "prepare"
            engine.play(1)
            awaitCondition { (position.get()?.positionMs ?: 0) > 450 }
            operations += "play"
            engine.pause(1)
            awaitCondition { state.get()?.phase == EnginePhase.PAUSED }
            delay(180)
            val stopped = position.get()?.positionMs ?: 0
            delay(250)
            check(abs((position.get()?.positionMs ?: 0) - stopped) < 250) { "Pause did not hold native position" }
            operations += "pause"
            val target = minOf(6_000, prepared.durationMs!! / 2)
            val forward = seek(target, 1)
            operations += "seekForward"
            engine.setRate(1.5f, 1)
            engine.play(1)
            awaitCondition { (position.get()?.positionMs ?: 0) > forward.positionMs + 450 }
            check(abs((state.get()?.effectiveSpeed ?: 0f) - 1.5f) < .01f)
            operations += "rate"
            val back = seek(500, 2)
            awaitCondition { position.get()?.let { it.seekSequence == 2L && it.positionMs > back.positionMs + 300 && it.positionMs < 3_000 } == true }
            operations += "seekBack"
            val native = engine.diagnostics()
            check(native["tls-verify"] == "yes")
            check(!native["current-ao"].isNullOrBlank()) { "No initialized native audio output" }
            if (!config.silentTestOutput) check(native["current-ao"] != "null") { "Real-output self-test selected a silent sink" }
            engine.stop(2)
            operations += "stop"
            "{\"success\":true,\"silentAudio\":${config.silentTestOutput},\"physicalAudioVerified\":false,\"operations\":[${operations.joinToString(",") { quote(it) }}],\"native\":{\"mpvVersion\":${quote(native["mpv-version"])},\"ffmpegVersion\":${quote(native["ffmpeg-version"])},\"tlsVerify\":${quote(native["tls-verify"])},\"audioOutput\":${quote(native["current-ao"])}}}"
        } finally {
            collector.cancel()
            withContext(NonCancellable) { engine.close() }
        }
    }

    private fun quote(value: String?): String = value?.let { text ->
        "\"" + text.flatMap { c -> when (c) { '\\' -> "\\\\"; '"' -> "\\\""; '\n' -> "\\n"; '\r' -> "\\r"; else -> if (c.code < 32) " " else c.toString() }.toList() }.joinToString("") + "\""
    } ?: "null"
}
