package dev.veyra.source.youtube

import dev.kmpyt.extraction.ExtractedMedia
import dev.kmpyt.extraction.Representation
import dev.kmpyt.extraction.TrackRole
import dev.kmpyt.extraction.TransportKind
import dev.kmpyt.youtube.YouTubeFormatCapabilities
import dev.veyra.model.FailureCode
import dev.veyra.model.StreamTransport
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.ResolvePurpose
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

/** No source URL, expiry, display text or credentials are retained in this selection key. */
@Serializable
private data class AudioIdentity(
    val videoId: String,
    val formatId: String,
    val transport: TransportKind,
    val standalone: Boolean,
    val mimeType: String,
    val codecs: String?,
    val bitrate: Long?,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val language: String?,
    val audioTrackId: String?,
    val audioGroupId: String?,
    val sampleRate: Int?,
    val channels: Int?,
    val isDrc: Boolean?,
)

internal fun Representation.audioSelectionKey(videoId: String): String {
    // kmpYT may add a catalogue-disambiguation suffix when a progressive alternative
    // accompanies SABR. Only that documented suffix may vary between inspections.
    val identity = AudioIdentity(
        videoId = videoId,
        formatId = id.replace(Regex("#progressive(?:-[0-9]+)?$"), ""),
        transport = transport,
        standalone = segments.isEmpty() && initialization == null,
        mimeType = audioMime(),
        codecs = audioCodec(),
        bitrate = bitrate,
        sizeBytes = sizeBytes,
        durationMs = durationMs,
        language = audioLanguage,
        audioTrackId = audioTrackId,
        audioGroupId = audioGroupId,
        sampleRate = audioSampleRate,
        channels = audioChannels,
        isDrc = isDrc,
    )
    return "yt-audio-v1:" + Json.encodeToString(AudioIdentity.serializer(), identity).encodeUtf8().sha256().hex()
}

internal fun Representation.audioMime(): String = mimeType.substringBefore(';').trim().lowercase()

internal fun Representation.audioCodec(): String? = (codecs
    ?: Regex("codecs\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(mimeType)?.groupValues?.get(1))
    ?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

internal fun Representation.audioExtension(): String? = when (audioMime()) {
    "audio/mp4" -> "m4a"
    "audio/webm" -> "webm"
    "audio/ogg" -> "ogg"
    "audio/mpeg" -> "mp3"
    "audio/flac", "audio/x-flac" -> "flac"
    "audio/aac" -> "aac"
    else -> null
}

internal fun Representation.expiredSourceOnly(): Boolean =
    unavailableReason == "The supplied media URL has expired; a new authorized source response is required." &&
        expiresAtEpochMs != null && !url.isNullOrBlank()

/** Segmented inputs are stream-copied into MP4 by the actual native kmpYT processor. */
internal fun Representation.materializedAudioMime(): String =
    if (segments.isNotEmpty() || transport in setOf(TransportKind.HLS, TransportKind.DASH)) "audio/mp4" else audioMime()

internal fun Representation.materializedAudioExtension(): String? =
    if (materializedAudioMime() == "audio/mp4") "m4a" else audioExtension()

internal fun Representation.nativeAudioSupported(engine: EngineCapabilities, mime: String = audioMime()): Boolean {
    if (role != TrackRole.AUDIO) return false
    val codec = audioCodec() ?: return false
    if (mime !in setOf("audio/mp4", "audio/webm", "audio/ogg", "audio/mpeg", "audio/flac", "audio/x-flac", "audio/aac") || ',' in codec) return false
    // Generic "aac" does not promise HE-AAC, xHE-AAC or every MPEG-4 audio object
    // type on every OS version. Non-LC profiles need an explicit native capability.
    if (codec.startsWith("mp4a") && codec != "mp4a.40.2" &&
        codec !in setOf("mp4a.69", "mp4a.6b") && codec !in engine.codecs.map { it.lowercase() }) return false
    return engine.supports(StreamTransport.PROGRESSIVE, mime, codec)
}

internal fun selectYouTubeAudio(
    media: ExtractedMedia,
    engine: EngineCapabilities,
    requiredKey: String? = null,
    purpose: ResolvePurpose = ResolvePurpose.PLAYBACK,
    platformName: String = "JVM",
    supportsSabrPlayback: Boolean = false,
): Representation {
    if (requiredKey != null && !Regex("yt-audio-v1:[0-9a-f]{64}").matches(requiredKey)) {
        youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_INVALID_SELECTION")
    }
    if (StreamTransport.PROGRESSIVE !in engine.transports) {
        youtubeFailure(FailureCode.UNSUPPORTED_TRANSPORT, "YOUTUBE_PROGRESSIVE_AUDIO_UNSUPPORTED")
    }
    val allAudio = media.representations.filter { it.role == TrackRole.AUDIO }
    val offered = if (requiredKey != null) {
        allAudio.filter { it.audioSelectionKey(media.id) == requiredKey }.also {
            if (it.size != 1) youtubeFailure(FailureCode.RECOVERY_REQUIRED, "YOUTUBE_SELECTED_TRACK_CHANGED")
        }
    } else {
        // Do not switch to a dubbed/nondefault language merely because its codec is
        // easier to play. An unsupported actual default remains an explicit error.
        allAudio.filter { it.audioIsDefault == true }.ifEmpty { allAudio }
    }
    val supportedTransports = offered.filter {
        when (purpose) {
            ResolvePurpose.PLAYBACK -> it.segments.isEmpty() && it.initialization == null &&
                (it.transport == TransportKind.PROGRESSIVE || supportsSabrPlayback && it.transport == TransportKind.SABR && it.sabr != null)
            ResolvePurpose.DOWNLOAD -> it.transport in setOf(TransportKind.PROGRESSIVE, TransportKind.SABR, TransportKind.HLS, TransportKind.DASH)
        }
    }
    if (supportedTransports.isEmpty()) {
        if (allAudio.isNotEmpty() || media.representations.any { it.transport != TransportKind.PROGRESSIVE }) {
            youtubeFailure(FailureCode.UNSUPPORTED_TRANSPORT, "YOUTUBE_AUDIO_TRANSPORT_UNAVAILABLE")
        }
        youtubeFailure(FailureCode.UNSUPPORTED_SOURCE, "YOUTUBE_AUDIO_TRACK_UNAVAILABLE")
    }
    val supported = supportedTransports.filter { audio ->
        val mime = if (purpose == ResolvePurpose.DOWNLOAD) audio.materializedAudioMime() else audio.audioMime()
        audio.nativeAudioSupported(engine, mime) && (purpose != ResolvePurpose.DOWNLOAD ||
            YouTubeFormatCapabilities.downloadProblem(if (audio.expiredSourceOnly()) audio.copy(unavailableReason = null) else audio,
                null, platformName) == null)
    }
    if (supported.isEmpty()) youtubeFailure(FailureCode.UNSUPPORTED_CODEC, "YOUTUBE_NATIVE_AUDIO_UNSUPPORTED")
    val available = supported.filter { it.selectable || it.expiredSourceOnly() }
    if (available.isEmpty()) youtubeFailure(FailureCode.UNSUPPORTED_SOURCE, "YOUTUBE_AUDIO_SOURCE_UNAVAILABLE")
    return available.sortedWith(
        compareByDescending<Representation> { it.audioMime() == "audio/mp4" && it.audioCodec() == "mp4a.40.2" }
            .thenBy { when (it.transport) {
                TransportKind.PROGRESSIVE -> 0; TransportKind.SABR -> 1; TransportKind.DASH -> 2; TransportKind.HLS -> 3
                TransportKind.UNRESOLVED -> 4
            } }
            .thenBy { it.isDrc == true }
            .thenByDescending { it.bitrate ?: 0 }
            .thenBy { it.id },
    ).first()
}
