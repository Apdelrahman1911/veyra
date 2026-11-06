package dev.veyra.desktop.audio

import com.sun.jna.Pointer
import dev.veyra.desktop.runtime.DesktopNativeRuntime
import dev.veyra.desktop.runtime.DesktopRuntimeConfig
import dev.veyra.desktop.runtime.PreparedDesktopRuntime
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.StreamTransport
import dev.veyra.model.VeyraException
import dev.veyra.playback.AudioEngine
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.EngineEvent
import dev.veyra.playback.EnginePhase
import dev.veyra.playback.InterruptionKind
import dev.veyra.playback.ResolvedPlayable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.roundToLong

/** One libmpv owner thread. No native calls run on Compose's/UI caller's thread. */
internal class MpvAudioDeck private constructor(
    private val native: MpvNative,
    private val runtime: PreparedDesktopRuntime,
    private val silentTestOutput: Boolean,
    private val validationMode: Boolean = false,
) : AudioEngine {
    override val capabilities = EngineCapabilities(
        transports = setOf(StreamTransport.PROGRESSIVE),
        mimeTypes = setOf("audio/mpeg", "audio/mp3", "audio/mp4", "audio/x-m4a", "audio/webm", "video/webm", "audio/ogg", "application/ogg", "audio/flac", "audio/x-flac", "audio/aac"),
        codecs = setOf("mp3", "aac", "opus", "vorbis", "flac", "alac"),
        supportsRequestHeaders = true,
    )

    // A single coordinator consumes this stream. Terminal/control events never use tryEmit/drop buffers.
    private val terminal = Channel<EngineEvent>(Channel.UNLIMITED)
    private val positions = Channel<EngineEvent>(Channel.CONFLATED)
    @OptIn(ExperimentalCoroutinesApi::class)
    override val events: Flow<EngineEvent> = merge(terminal.receiveAsFlow(), positions.receiveAsFlow())
    private val operations = ConcurrentLinkedQueue<Operation>()
    private val gate = Any()
    private var accepting = true
    private var running = true // owner thread only
    private val initialized = CompletableDeferred<Unit>()
    private val terminated = CompletableDeferred<Unit>()
    @Volatile private var wakeHandle: Pointer? = null
    private lateinit var handle: Pointer
    private var generationBarrier = Long.MIN_VALUE
    private var active: Session? = null
    private var nativePlayingEntry: Long? = null
    private val sessions = linkedMapOf<Long, Session>()
    private var lastSampleNanos = 0L
    private var selectedOutput = "auto"
    private val owner = Thread(::eventLoop, "Veyra-libmpv-owner").apply { isDaemon = true; start() }

    private class Session(val generation: Long, val source: ResolvedPlayable) {
        var entryId: Long? = null
        var ready = false
        var desiredPlaying = false
        var lastPhase: EnginePhase? = null
        var effectiveSpeed = 1f
        var durationMs: Long? = null
        var seekable = false
        var confirmedSeek = 0L
        var pendingSeek: Seek? = null
        var queuedSeek: Seek? = null
        var terminalDelivered = false
        var corruptWarning = false
        var openingDeadline = System.nanoTime() + 35_000_000_000L
    }
    private data class Seek(val positionMs: Long, val sequence: Long, var began: Boolean = false, var deadline: Long = System.nanoTime() + 15_000_000_000L)
    private data class Operation(val action: () -> Unit, val completion: CompletableDeferred<Unit>)

    override suspend fun prepare(source: ResolvedPlayable, startPositionMs: Long, generation: Long) = submit {
        if (generation < generationBarrier) return@submit
        generationBarrier = generation
        val input = sourceInput(source)
        require(source.transport == StreamTransport.PROGRESSIVE) { "Native decoder requires a progressive media resource" }
        val session = Session(generation, source)
        active = session
        set("pause", "yes")
        set("speed", if (validationMode) "100" else "1")
        // Never carry authentication/user-agent/referrer from a previous source.
        set("user-agent", "Veyra/1.0")
        set("referrer", "")
        val headers = source.headers.map { (key, value) ->
            require(key.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Invalid HTTP header" }
            require(value.length <= 16_384) { "HTTP header too large" }
            "$key: $value"
        }
        checkNative(native.stringList(handle, "http-header-fields", headers))
        publish(EngineEvent.StateChanged(generation, EnginePhase.PREPARING, false))
        val start = seconds(startPositionMs.coerceAtLeast(0))
        checkNative(native.command(handle, "loadfile", input, "replace", "-1", "start=$start"))
        val entry = get("playlist/0/id")?.toLongOrNull()
            ?: throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "native_load_identity_missing"))
        session.entryId = entry
        sessions[entry] = session
        // Superseded loads that never reached START_FILE may have no END_FILE event.
        if (sessions.size > 128) sessions.entries.removeIf { it.value !== active && it.value.generation < generation - 64 }
    }

    override suspend fun play(generation: Long) = submit {
        val session = current(generation) ?: return@submit
        session.desiredPlaying = true
        if (session.ready) set("pause", "no")
    }

    override suspend fun pause(generation: Long) = submit {
        val session = current(generation) ?: return@submit
        session.desiredPlaying = false
        set("pause", "yes")
        sample(session)
    }

    override suspend fun seek(positionMs: Long, seekSequence: Long, generation: Long) = submit {
        val session = current(generation) ?: return@submit
        if (!session.ready || !session.seekable || seekSequence <= maxOf(session.confirmedSeek, session.pendingSeek?.sequence ?: 0, session.queuedSeek?.sequence ?: 0)) {
            publish(EngineEvent.SeekCompleted(generation, seekSequence, position(), false)); return@submit
        }
        val target = session.durationMs?.let { positionMs.coerceIn(0, it) } ?: positionMs.coerceAtLeast(0)
        val seek = Seek(target, seekSequence)
        if (session.pendingSeek != null) {
            session.queuedSeek?.let { publish(EngineEvent.SeekCompleted(generation, it.sequence, position(), false)) }
            session.queuedSeek = seek
        } else startSeek(session, seek)
    }

    override suspend fun setRate(speed: Float, generation: Long) = submit {
        val session = current(generation) ?: return@submit
        require(speed.isFinite() && speed in capabilities.minSpeed..capabilities.maxSpeed)
        set("speed", speed.toString())
        session.effectiveSpeed = get("speed")?.toFloatOrNull() ?: speed
        session.lastPhase = null
        sample(session)
    }

    override suspend fun stop(generation: Long) = submit {
        if (generation < generationBarrier) return@submit
        generationBarrier = generation
        active = null
        checkNative(native.command(handle, "stop"))
        publish(EngineEvent.StateChanged(generation, EnginePhase.IDLE, false))
    }

    /** Desktop application gain, deliberately separate from the device's system volume. */
    suspend fun setGain(gain: Float) = submit {
        require(gain.isFinite() && gain in 0f..1f)
        set("volume", (gain * 100f).toString())
    }

    suspend fun selectOutput(deviceId: String) = submit {
        require(deviceId.isNotBlank() && deviceId.length <= 1024 && '\u0000' !in deviceId)
        set("audio-device", deviceId)
        selectedOutput = deviceId
    }

    suspend fun diagnostics(): Map<String, String?> {
        var result = emptyMap<String, String?>()
        submit { result = listOf("mpv-version", "ffmpeg-version", "tls-verify", "audio-pitch-correction", "current-ao", "audio-device", "audio-device-list", "volume", "pause").associateWith(::get) }
        return result // Contains no media URI, request header or native diagnostic log.
    }

    suspend fun snapshot(): MpvDeckSnapshot {
        var result = MpvDeckSnapshot()
        submit {
            val session = active
            if (session != null) {
                val phase = when {
                    session.terminalDelivered -> EnginePhase.ENDED
                    !session.ready -> EnginePhase.PREPARING
                    get("paused-for-cache") == "yes" -> EnginePhase.BUFFERING
                    get("pause") == "yes" -> EnginePhase.PAUSED
                    else -> EnginePhase.PLAYING
                }
                result = MpvDeckSnapshot(position(), session.durationMs, session.seekable, phase,
                    session.effectiveSpeed, session.desiredPlaying, session.terminalDelivered)
            }
        }
        return result
    }

    override suspend fun close() {
        synchronized(gate) {
            if (accepting) {
                accepting = false
                operations.add(Operation({ running = false }, CompletableDeferred()))
                wakeHandle?.let(native::mpv_wakeup)
            }
        }
        terminated.await()
    }

    private suspend fun submit(action: () -> Unit) {
        initialized.await()
        val operation = Operation(action, CompletableDeferred())
        synchronized(gate) {
            check(accepting) { "Audio engine is closed" }
            operations.add(operation)
            wakeHandle?.let(native::mpv_wakeup)
        }
        operation.completion.await()
    }

    private fun eventLoop() {
        try {
            handle = native.mpv_create() ?: error("Could not allocate audio engine")
            synchronized(gate) { wakeHandle = handle }
            val options = linkedMapOf(
                "config" to "no", "vo" to "null", "vid" to "no", "terminal" to "no",
                "input-default-bindings" to "no", "input-vo-keyboard" to "no", "idle" to "yes",
                "keep-open" to "no", "pause" to "yes", "tls-verify" to "yes",
                "tls-ca-file" to runtime.caBundle.toString(), "network-timeout" to "15",
                "audio-pitch-correction" to if (validationMode) "no" else "yes",
                "load-scripts" to "no", "autoload-files" to "no",
                "access-references" to "no", "load-unsafe-playlists" to "no",
            )
            if (silentTestOutput || validationMode) options["ao"] = "null"
            if (validationMode) options["ao-null-untimed"] = "yes"
            options.forEach { (name, value) ->
                val code = native.mpv_set_option_string(handle, name, value)
                // The audited runtime has scripting entirely compiled out, hence no load-scripts option.
                if (code == -5 && name == "load-scripts") return@forEach
                checkNative(code)
            }
            checkNative(native.mpv_initialize(handle))
            if (validationMode) checkNative(native.mpv_request_log_messages(handle, "warn"))
            check(get("tls-verify") == "yes") { "Native TLS verification is not enabled" }
            initialized.complete(Unit)
            while (running) {
                repeat(4) {
                    val operation = operations.poll() ?: return@repeat
                    try { operation.action(); operation.completion.complete(Unit) }
                    catch (error: Throwable) {
                        operation.completion.completeExceptionally(safeException(error))
                        active?.let { fail(it, failure(error)) }
                    }
                }
                if (!running) break
                dispatch(native.mpv_wait_event(handle, .025))
                val now = System.nanoTime()
                active?.let { session ->
                    if (!session.ready && !session.terminalDelivered && now > session.openingDeadline) {
                        fail(session, AppFailure(FailureCode.NETWORK_UNAVAILABLE, "media_open_timeout"))
                        native.command(handle, "stop")
                    }
                    session.pendingSeek?.let { if (now > it.deadline) finishSeek(session, success = false) }
                    if (now - lastSampleNanos >= 150_000_000L) {
                        sample(session); lastSampleNanos = now
                    }
                }
            }
        } catch (error: Throwable) {
            initialized.completeExceptionally(safeException(error))
            active?.let { fail(it, failure(error)) }
        } finally {
            synchronized(gate) { accepting = false; wakeHandle = null }
            var shutdownFailure: Throwable? = null
            try { if (::handle.isInitialized) native.mpv_terminate_destroy(handle) }
            catch (_: Throwable) {
                // Always finish waiters, but never acknowledge native-file release if teardown
                // failed. Raw native diagnostics can contain ephemeral source URLs.
                shutdownFailure = VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "native_audio_cleanup"))
            }
            while (true) {
                val operation = operations.poll() ?: break
                operation.completion.completeExceptionally(IllegalStateException("Audio engine is closed"))
            }
            terminal.close(); positions.close()
            if (shutdownFailure == null) terminated.complete(Unit) else terminated.completeExceptionally(shutdownFailure)
        }
    }

    private fun dispatch(event: Pointer) {
        val id = event.getInt(0)
        val data = event.getPointer(16)
        when (id) {
            2 -> if (validationMode && data != null) {
                // Validation uses its own one-file handle, never a user queue. Do not retain/log native text.
                val message = data.getPointer(16)?.getString(0, "UTF-8").orEmpty().lowercase(Locale.ROOT)
                if (listOf("incomplete file", "file ended prematurely", "invalid data found", "truncated", "corrupt").any(message::contains)) {
                    active?.corruptWarning = true
                }
            }
            6 -> nativePlayingEntry = data?.getLong(0) // START_FILE with the originating playlist entry ID.
            8 -> { // FILE_LOADED
                val session = sessions[nativePlayingEntry] ?: return
                if (session !== active || session.terminalDelivered) return
                session.ready = true
                session.durationMs = millis(get("duration"))
                session.seekable = get("seekable") == "yes"
                // A demuxed video-only file is not an audio asset.
                if (get("audio-codec-name").isNullOrBlank()) {
                    fail(session, AppFailure(FailureCode.UNSUPPORTED_CODEC, "no_decodable_audio_stream")); return
                }
                publish(EngineEvent.Ready(session.generation, session.durationMs, session.seekable))
                if (session.desiredPlaying) set("pause", "no")
                sample(session)
            }
            7 -> { // END_FILE: reason, error, playlist_entry_id. Never tag it with the latest generation.
                if (data == null) return
                val session = sessions.remove(data.getLong(8)) ?: return
                when (data.getInt(0)) {
                    0 -> if (validationMode && session.corruptWarning) {
                        fail(session, AppFailure(FailureCode.CORRUPT_MEDIA, "native_decoder_reported_incomplete_media"))
                    } else if (!session.terminalDelivered) {
                        session.terminalDelivered = true
                        publish(EngineEvent.Ended(session.generation))
                        publish(EngineEvent.StateChanged(session.generation, EnginePhase.ENDED, false, session.effectiveSpeed))
                    }
                    4 -> fail(session, nativeFailure(data.getInt(4), session.source.isLocal))
                }
            }
            20 -> sessions[nativePlayingEntry]?.takeIf { it === active }?.pendingSeek?.began = true
            21 -> sessions[nativePlayingEntry]?.takeIf { it === active }?.let { session ->
                session.pendingSeek?.takeIf { it.began }?.let { finishSeek(session, success = true) }
                sample(session)
            }
            24 -> active?.let { fail(it, AppFailure(FailureCode.INVALID_STATE, "native_event_queue_overflow")) }
        }
    }

    private fun startSeek(session: Session, seek: Seek) {
        session.pendingSeek = seek
        seek.deadline = System.nanoTime() + 15_000_000_000L
        if (native.command(handle, "seek", seconds(seek.positionMs), "absolute+exact") < 0) finishSeek(session, false)
    }

    private fun finishSeek(session: Session, success: Boolean) {
        val pending = session.pendingSeek ?: return
        val actual = position()
        val converged = success && abs(actual - pending.positionMs) <= 1_000
        if (converged) session.confirmedSeek = pending.sequence
        session.pendingSeek = null
        publish(EngineEvent.SeekCompleted(session.generation, pending.sequence, actual, converged))
        session.queuedSeek?.let { next -> session.queuedSeek = null; startSeek(session, next) }
    }

    private fun sample(session: Session) {
        if (!session.ready || session.terminalDelivered || session !== active || nativePlayingEntry != session.entryId) return
        val paused = get("pause") == "yes"
        val buffering = get("paused-for-cache") == "yes"
        val phase = when { buffering -> EnginePhase.BUFFERING; paused -> EnginePhase.PAUSED; else -> EnginePhase.PLAYING }
        session.effectiveSpeed = get("speed")?.toFloatOrNull() ?: session.effectiveSpeed
        if (phase != session.lastPhase) {
            publish(EngineEvent.StateChanged(session.generation, phase, phase == EnginePhase.PLAYING, session.effectiveSpeed))
            session.lastPhase = phase
        }
        val position = position()
        val cached = millis(get("demuxer-cache-duration"))?.let { position + it }
        session.durationMs = millis(get("duration")) ?: session.durationMs
        positions.trySend(EngineEvent.Position(session.generation, position, session.durationMs, cached, session.confirmedSeek))
        if (selectedOutput != "auto" && session.desiredPlaying) {
            val available = get("audio-device-list").orEmpty()
            // Device IDs originate from the native enumeration; JSON substring is only a loss signal.
            if (available.isNotEmpty() && !available.contains(selectedOutput)) {
                session.desiredPlaying = false
                set("pause", "yes")
                publish(EngineEvent.Interruption(session.generation, InterruptionKind.OUTPUT_REMOVED, false))
            }
        }
    }

    private fun current(generation: Long): Session? = active?.takeIf { it.generation == generation && generation == generationBarrier && !it.terminalDelivered }
    private fun position(): Long = millis(get("time-pos")) ?: 0
    private fun get(name: String): String? = native.string(handle, name)
    private fun set(name: String, value: String) = checkNative(native.mpv_set_property_string(handle, name, value))
    private fun publish(event: EngineEvent) { check(terminal.trySend(event).isSuccess) { "Audio event stream closed unexpectedly" } }
    private fun fail(session: Session, reason: AppFailure) {
        if (session.terminalDelivered) return
        session.terminalDelivered = true
        // A failed control/output must not leave native audio playing behind an ERROR projection.
        if (session === active) native.mpv_set_property_string(handle, "pause", "yes")
        publish(EngineEvent.Error(session.generation, reason))
        publish(EngineEvent.StateChanged(session.generation, EnginePhase.ERROR, false, session.effectiveSpeed))
    }
    private fun checkNative(code: Int) { if (code < 0) throw VeyraException(nativeFailure(code, false)) }
    private fun failure(error: Throwable): AppFailure = (error as? VeyraException)?.failure
        ?: AppFailure(if (error is IllegalArgumentException) FailureCode.INVALID_STATE else FailureCode.UNEXPECTED, "desktop_audio_operation_failed")
    private fun safeException(error: Throwable): VeyraException = VeyraException(failure(error))

    companion object {
        suspend fun create(config: DesktopRuntimeConfig): MpvAudioDeck = createInternal(config, false)

        internal suspend fun createInternal(config: DesktopRuntimeConfig, validation: Boolean): MpvAudioDeck {
            var deck: MpvAudioDeck? = null
            try {
                return withContext(Dispatchers.IO) {
                    val runtime = DesktopNativeRuntime.prepare(config)
                    MpvAudioDeck(MpvNative.load(runtime), runtime, config.silentTestOutput, validation).also { deck = it; it.initialized.await() }
                }
            } catch (error: Throwable) {
                withContext(kotlinx.coroutines.NonCancellable) { deck?.close() }
                throw error
            }
        }

        internal fun localPath(uri: String): Path {
            require('\u0000' !in uri)
            return if (uri.startsWith("file:", true)) Path.of(URI(uri)).toAbsolutePath().normalize()
                else Path.of(uri).toAbsolutePath().normalize()
        }

        internal fun sourceInput(source: ResolvedPlayable): String {
            require(source.uri.isNotBlank() && '\u0000' !in source.uri)
            if (source.isLocal) {
                val path = localPath(source.uri)
                if (!Files.isRegularFile(path) || !Files.isReadable(path)) throw VeyraException(AppFailure(FailureCode.NOT_FOUND, "local_audio_missing"))
                return path.toString()
            }
            val uri = URI(source.uri)
            if (uri.scheme.equals("http", true) && uri.host?.lowercase(Locale.ROOT) in setOf("localhost", "127.0.0.1", "[::1]", "::1") && source.ownedLoopbackUriOrNull() == null) {
                throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_TRANSPORT, "unowned_loopback_media_endpoint"))
            }
            if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null) {
                throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_TRANSPORT, "unsupported_native_media_uri"))
            }
            return source.uri
        }

        private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.3f", ms / 1000.0)
        private fun millis(seconds: String?): Long? = seconds?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { (it * 1000).roundToLong() }
        private fun nativeFailure(code: Int, local: Boolean): AppFailure = AppFailure(
            when (code) {
                -16, -17 -> FailureCode.UNSUPPORTED_CODEC
                -13 -> if (local) FailureCode.CORRUPT_MEDIA else FailureCode.NETWORK_UNAVAILABLE
                -14, -15 -> FailureCode.INVALID_STATE // Native audio/video output initialization failed.
                else -> FailureCode.UNEXPECTED
            }, "native_audio_error_$code",
        )
    }
}

internal data class MpvDeckSnapshot(
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val seekable: Boolean = false,
    val phase: EnginePhase = EnginePhase.IDLE,
    val effectiveSpeed: Float = 1f,
    val desiredPlaying: Boolean = false,
    val ended: Boolean = false,
)
