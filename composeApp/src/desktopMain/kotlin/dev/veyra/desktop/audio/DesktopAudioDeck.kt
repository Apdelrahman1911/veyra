package dev.veyra.desktop.audio

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.StreamTransport
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.EngineEvent
import dev.veyra.playback.NativeReaderDrainResult
import dev.veyra.playback.NativePlaybackSource
import dev.veyra.playback.LocalMediaValidationInput
import kotlinx.coroutines.flow.Flow

/**
 * One native decoder, not another app audio/resource owner. The enclosing DesktopAudioEngine
 * owns central resources and may release them only after this deck's acknowledged close. The
 * standalone validator supplies resource-free local input. This explicit contract also permits
 * deterministic native-close failure tests without starting libmpv or pretending they are audio.
 */
internal interface DesktopAudioDeck {
    val capabilities: EngineCapabilities
    val events: Flow<EngineEvent>
    suspend fun prepare(source: DesktopNativeInput, startPositionMs: Long, generation: Long)
    suspend fun play(generation: Long)
    suspend fun pause(generation: Long)
    suspend fun seek(positionMs: Long, seekSequence: Long, generation: Long)
    suspend fun setRate(speed: Float, generation: Long)
    suspend fun stop(generation: Long)
    suspend fun setGain(gain: Float)
    suspend fun selectOutput(deviceId: String)
    suspend fun diagnostics(): Map<String, String?>
    suspend fun snapshot(): MpvDeckSnapshot
    suspend fun awaitRetiredReaders(expectedGeneration: Long): NativeReaderDrainResult
    suspend fun close()
}

/**
 * Decoder-only transport. No catalogue/subject/media/RSS ID, request receipt, selection or owned
 * PlaybackResource crosses this seam. The enclosing engine retains the actual central owner.
 * The optional loopback capability was obtained from that owner, not from URI-shape inference.
 */
internal class DesktopNativeInput private constructor(
    val uri: String,
    val transport: StreamTransport,
    val mimeType: String?,
    val codecs: String?,
    val headers: Map<String, String>,
    val expiresAtEpochMs: Long?,
    val durationMs: Long?,
    val seekableHint: Boolean?,
    val isLocal: Boolean,
    private val ownedEndpointUri: String?,
) {
    fun ownedLoopbackUriOrNull(): String? = ownedEndpointUri?.takeIf { !isLocal && it == uri }
    override fun toString() = "DesktopNativeInput(transport=$transport,isLocal=$isLocal,locator=redacted)"

    companion object {
        /** Call only after the outer AudioEngine has registered source ownership. */
        fun from(source: NativePlaybackSource): DesktopNativeInput {
            source.validateSelection()
            return DesktopNativeInput(source.uri, source.transport, source.mimeType, source.codecs,
                source.headers.toMap(), source.expiresAtEpochMs, source.durationMs, source.seekableHint,
                source.isLocal, source.ownedLoopbackUriOrNull())
        }

        /** The existing standalone validator owns this resource-free local decoder operation. */
        fun forLocalValidation(input: LocalMediaValidationInput): DesktopNativeInput = DesktopNativeInput(
            input.uri, StreamTransport.PROGRESSIVE, input.mimeType, input.codecs, emptyMap(), null,
            null, null, true, null,
        )
    }
}

/**
 * Native-owner-thread state used by MpvAudioDeck, not a default barrier implementation. A
 * loadfile/stop command acceptance does not prove the previous native reader released its file.
 * Once a loaded handle is reused/stopped, only acknowledged destruction clears that uncertainty.
 * DesktopAudioEngine deliberately gives each source a fresh handle instead of reusing one.
 */
internal class DesktopNativeReaderEpoch {
    var generation: Long = Long.MIN_VALUE
        private set
    private var everOwnedSource = false
    private var ownsSource = false
    private var retiredReader = false
    private var destroyed = false
    private var destructionAcknowledged = false

    fun adoptSource(nextGeneration: Long): Boolean {
        check(!destroyed)
        if (nextGeneration < generation) return false
        if (ownsSource) retiredReader = true
        generation = nextGeneration
        everOwnedSource = true
        ownsSource = true
        return true
    }

    /** Called only after native stop accepts this epoch; an exception must not establish it. */
    fun stopped(nextGeneration: Long) {
        check(!destroyed && nextGeneration >= generation)
        if (ownsSource) retiredReader = true
        ownsSource = false
        generation = nextGeneration
    }

    /** Called on the owner thread after mpv_terminate_destroy, before completing close waiters. */
    fun destroyed(acknowledged: Boolean) {
        destroyed = true
        destructionAcknowledged = acknowledged
        if (acknowledged) { ownsSource = false; retiredReader = false }
    }

    fun result(expectedGeneration: Long): NativeReaderDrainResult {
        if (expectedGeneration < 0L) return NativeReaderDrainResult.Superseded
        if (generation == Long.MIN_VALUE && expectedGeneration == 0L && !destroyed &&
            !everOwnedSource && !ownsSource && !retiredReader) generation = 0L
        if (generation != expectedGeneration) return NativeReaderDrainResult.Superseded
        if (retiredReader || (destroyed && !destructionAcknowledged)) {
            return NativeReaderDrainResult.Unacknowledged(
                AppFailure(FailureCode.RECOVERY_REQUIRED, "native_reader_release_unacknowledged"),
            )
        }
        // This synchronous inspection runs entirely on the native owner. After destruction,
        // close completion publishes its final, immutable state to an awaiting caller.
        return if (generation == expectedGeneration) NativeReaderDrainResult.Drained(expectedGeneration)
        else NativeReaderDrainResult.Superseded
    }
}
