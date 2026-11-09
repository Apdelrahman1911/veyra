package dev.veyra.playback

import dev.veyra.model.AppFailure
import dev.veyra.model.MediaItem
import dev.veyra.model.OfflineAsset
import dev.veyra.model.PlaybackBinding
import dev.veyra.model.StreamTransport
import dev.veyra.source.execution.AcquisitionPlaybackSource
import kotlinx.coroutines.flow.Flow

data class EngineCapabilities(
    val transports: Set<StreamTransport> = setOf(StreamTransport.PROGRESSIVE),
    val mimeTypes: Set<String> = setOf("audio/mpeg", "audio/mp4"),
    val codecs: Set<String> = setOf("mp3", "aac"),
    val minSpeed: Float = 0.5f,
    val maxSpeed: Float = 2f,
    val supportsRequestHeaders: Boolean = false,
    val supportsLocalFiles: Boolean = true,
    val supportsCrossfade: Boolean = false,
) {
    fun supports(transport: StreamTransport, mimeType: String? = null, codec: String? = null): Boolean {
        if (transport !in transports) return false
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
        if (mime != null && mimeTypes.isNotEmpty() && mime !in mimeTypes) return false
        val normalized = codec?.trim()?.lowercase()
        val family = when (normalized) {
            "mp4a.40.2", "mp4a.40.5", "mp4a.40.29", "mp4a.66", "mp4a.67", "mp4a.68" -> "aac"
            "mp4a.69", "mp4a.6b" -> "mp3"
            else -> normalized
        }
        return normalized == null || codecs.isEmpty() || normalized in codecs || family in codecs
    }
}

/**
 * An ephemeral, capability-bearing endpoint owned by central media execution, never a general
 * exception to public-URL policy. Bind IPv4 loopback only and use an unguessable per-resource path.
 */
class EphemeralPlaybackEndpoint(val uri: String) {
    init {
        val match = Regex("http://127\\.0\\.0\\.1:([0-9]{1,5})(/[^\\s#\\\\]*)").matchEntire(uri)
        require(match != null && match.groupValues[1].toInt() in 1..65_535 &&
            match.groupValues[2].substringBefore('?').length > 1) { "Invalid owned playback endpoint" }
    }

    override fun toString(): String = "EphemeralPlaybackEndpoint(<redacted>)"
}

/**
 * Central kmpYT execution owns this bridge/session, not the native player. close MUST be idempotent,
 * main-safe, and promptly cancel pending bridge readers. Native decks own the resource once their
 * prepare/preload method is invoked (including failure); the coordinator disposes unhanded plans.
 */
interface PlaybackResource {
    val localEndpoint: EphemeralPlaybackEndpoint? get() = null
    suspend fun close()
}

/** Ephemeral. Never serialize this object or log [uri]/[headers]. */
data class ResolvedPlayable(
    val mediaId: String,
    val uri: String,
    val transport: StreamTransport = StreamTransport.PROGRESSIVE,
    val mimeType: String? = null,
    val codecs: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val expiresAtEpochMs: Long? = null,
    val durationMs: Long? = null,
    val seekableHint: Boolean? = null,
    val isLocal: Boolean = false,
    val assetId: String? = null,
    val representationId: String? = null,
    val item: MediaItem? = null,
    val executionBinding: PlaybackBinding? = null,
    val resource: PlaybackResource? = null,
) {
    /** A remote acquisition stays remote/offline-blocked even if its owned bridge is on loopback. */
    fun ownedLoopbackUriOrNull(): String? = resource?.localEndpoint?.uri?.takeIf { !isLocal && it == uri }

    override fun toString(): String =
        "ResolvedPlayable(mediaId=$mediaId, transport=$transport, isLocal=$isLocal, uri=<redacted>, headers=<redacted>)"
}

enum class EnginePhase { IDLE, PREPARING, READY, PLAYING, PAUSED, BUFFERING, ENDED, ERROR }
enum class InterruptionKind { TRANSIENT_BEGAN, TRANSIENT_ENDED, PERMANENT, OUTPUT_REMOVED, MEDIA_SERVICES_RESET }
enum class CrossfadeCancelReason { CANCELLED, INTERRUPTED, OUTGOING_ENDED, INCOMING_STALLED, INCOMING_ENDED, ERROR }

/** Capture the originating native item/load token, never relabel a stale callback with the current token. */
sealed interface EngineEvent {
    val generation: Long

    data class Ready(
        override val generation: Long,
        val durationMs: Long? = null,
        val seekable: Boolean = true,
    ) : EngineEvent

    data class StateChanged(
        override val generation: Long,
        val phase: EnginePhase,
        val isPlaying: Boolean,
        val effectiveSpeed: Float = 1f,
    ) : EngineEvent

    data class Position(
        override val generation: Long,
        val positionMs: Long,
        val durationMs: Long? = null,
        val bufferedPositionMs: Long? = null,
        /** Last confirmed seek sequence; do not promote pre-seek samples to a pending seek's sequence. */
        val seekSequence: Long = 0,
    ) : EngineEvent

    data class SeekCompleted(
        override val generation: Long,
        val seekSequence: Long,
        val positionMs: Long,
        val success: Boolean = true,
    ) : EngineEvent

    data class Ended(override val generation: Long) : EngineEvent
    data class Error(override val generation: Long, val failure: AppFailure) : EngineEvent
    data class Interruption(
        override val generation: Long,
        val kind: InterruptionKind,
        val mayResume: Boolean = false,
    ) : EngineEvent

    /** The silent incoming deck is genuinely prepared; ordinary incoming callbacks remain private. */
    data class StageReady(
        override val generation: Long,
        val nextGeneration: Long,
        val durationMs: Long?,
        val seekable: Boolean,
    ) : EngineEvent

    data class StageFailed(
        override val generation: Long,
        val nextGeneration: Long,
        val failure: AppFailure,
    ) : EngineEvent

    data class CrossfadeStarted(
        override val generation: Long,
        val nextGeneration: Long,
        val durationMs: Long,
    ) : EngineEvent

    /** Native-confirmed overlap progress, not a UI animation guessing from track duration. */
    data class CrossfadeProgress(
        override val generation: Long,
        val nextGeneration: Long,
        val fraction: Float,
    ) : EngineEvent

    /**
     * Lossless handover proposal. [generation] is the outgoing load. Keep ownership/cancellation
     * on it until confirmCrossfade acknowledges the coordinator's durable queue commit; following
     * ordinary callbacks then use [nextGeneration]. Incoming audio is not prepared/restarted again.
     */
    data class CrossfadeCompleted(
        override val generation: Long,
        val nextGeneration: Long,
        val positionMs: Long,
        val durationMs: Long?,
        val seekable: Boolean,
        val effectiveSpeed: Float = 1f,
        val isPlaying: Boolean = true,
    ) : EngineEvent

    data class CrossfadeCancelled(
        override val generation: Long,
        val nextGeneration: Long,
        val reason: CrossfadeCancelReason,
    ) : EngineEvent
}

/**
 * A scoped file-release fence, not a playback/transport event. An unacknowledged retired reader
 * must veto deletion without pausing, replacing, or reporting an error for an unrelated active
 * or staged source. The failure contains a stable, sanitized code only, never native diagnostics.
 */
sealed interface NativeReaderDrainResult {
    /** Both native-owner epoch checks matched this exact controller epoch. */
    data class Drained(val expectedGeneration: Long) : NativeReaderDrainResult
    data object Superseded : NativeReaderDrainResult
    data class Unacknowledged(val failure: AppFailure) : NativeReaderDrainResult
}

/** One audio owner, no authoritative native playlist. All operations are main-safe. */
interface AudioEngine {
    val capabilities: EngineCapabilities
    /** End/error/seek/interruption events must be lossless. Position samples may be conflated. */
    val events: Flow<EngineEvent>
    /** Takes source.resource ownership on invocation, also on failure or a rejected generation. */
    suspend fun prepare(source: ResolvedPlayable, startPositionMs: Long, generation: Long)
    /**
     * Typed handoff. Register ownership BEFORE validating the selection or rejecting this source.
     * Use NativePlaybackSource.Acquired.validateSelection inside the same retained-owner path as
     * legacy admission. There is no default unsupported-close implementation: failed release must
     * remain strongly owned and veto awaitRetiredReaders, even on an intentionally unsupported fake.
     * Neutral input never becomes a synthetic MediaItem/mediaId/ResolvedPlayable.
     */
    suspend fun prepare(source: AcquisitionPlaybackSource, selection: PlaybackSelection, startPositionMs: Long, generation: Long)
    suspend fun play(generation: Long)
    suspend fun pause(generation: Long)
    suspend fun seek(positionMs: Long, seekSequence: Long, generation: Long)
    suspend fun setRate(speed: Float, generation: Long)
    /** Monotonic barrier: accept generation >= active; other commands require the exact active generation. */
    suspend fun stop(generation: Long)
    /**
     * Serialized with native ownership, after already-enqueued stop/cancel/promotion commands.
     * Await every retired native reader and resource/loader closure without changing any live
     * deck. Check the exact expected epoch before AND after those waits. Only a positively
     * pristine, never-source-owning engine may establish its initial controller epoch zero;
     * an empty engine after a failed/replaced load is not pristine. There is no default ACK.
     *
     * Failed retirement handles remain strongly owned; reader/resource-close-only failure is
     * a sticky release veto and must not enter a global Error/silence path. A distinct failure
     * to establish native transport silence may require transport-wide containment during
     * retirement; this fence itself never initiates unrelated transport changes.
     * Cancellation cannot be converted to Drained.
     * The controller still rechecks its generation and the target's current/staged exclusion
     * after this fence and its own resource fence; this result alone authorizes no deletion.
     */
    suspend fun awaitRetiredReaders(expectedGeneration: Long): NativeReaderDrainResult
    suspend fun close()
}

/**
 * One managed native transport/session with two decoder decks for music overlap. The controller
 * reserves distinct, never-reused load generations. Preloading must stay silent. Equal-power gains
 * use CrossfadeMix (cos/sin multiplied by fixed MUSIC_GAIN headroom), t in 0..1, starting only
 * when incoming audio can advance. Podcasts retain unity master gain and never overlap.
 *
 * Ordinary incoming callbacks are private until confirmCrossfade. Terminal/control events are
 * lossless; CrossfadeProgress may coalesce. Pause, seek, rate changes, stop and interruptions cancel
 * any staged/overlapping deck first. Cancellation restores outgoing relative envelope to one,
 * retaining its fixed music master gain, and NEVER resumes
 * it. stop(generation >= active) clears both decks, including a reserved higher nextGeneration.
 */
interface CrossfadeAudioEngine : AudioEngine {
    suspend fun preloadNext(source: ResolvedPlayable, startPositionMs: Long, nextGeneration: Long, generation: Long)
    /** Same transferred ownership and exact typed-selection check as prepare; explicit MUSIC only. */
    suspend fun preloadNext(source: AcquisitionPlaybackSource, selection: PlaybackSelection, startPositionMs: Long, nextGeneration: Long, generation: Long)
    suspend fun beginCrossfade(durationMs: Long, nextGeneration: Long, generation: Long)
    /** Acknowledge a CrossfadeCompleted event after the coordinator commits the new queue cursor. */
    suspend fun confirmCrossfade(nextGeneration: Long, generation: Long)
    suspend fun cancelNext(nextGeneration: Long, generation: Long)
}

sealed interface AssetValidation {
    data class Valid(val durationMs: Long? = null) : AssetValidation
    data class Invalid(val failure: AppFailure) : AssetValidation
}

/** Native decoder/header/duration validation is distinct from download byte/hash validation. */
fun interface AssetValidator {
    suspend fun validate(asset: OfflineAsset): AssetValidation
}
