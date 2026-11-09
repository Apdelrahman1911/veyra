package dev.veyra.source.youtube

import dev.kmpyt.YouTubeClient
import dev.kmpyt.YouTubePlaybackResource
import dev.kmpyt.extraction.*
import dev.kmpyt.youtube.YouTubeBrowseText
import dev.kmpyt.youtube.YouTubeFormatSelection
import dev.veyra.model.FailureCode
import dev.veyra.model.StreamTransport
import dev.veyra.model.VeyraException
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.EphemeralPlaybackEndpoint
import dev.veyra.playback.PlaybackResource
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.network.PublicUrlPolicy
import io.ktor.http.Url
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One report/audio execution body for the legacy and typed boundaries. No catalogue media,
 * requested title, queue, RSS identity or binding is manufactured here. The source owns the
 * one client; kmpYT still owns all planning, authorization, native transfer and range/resume I/O.
 */
internal class YouTubeAudioExecution(
    private val client: YouTubeClient,
    private val nowEpochMs: () -> Long,
    private val requireOpen: suspend () -> Unit,
) {
    /** The boundary supplies a direct SAME-C inspector, never a search or another source. */
    class Input(
        val videoId: String,
        val inspect: suspend () -> ExtractionReport,
        val initialReport: ExtractionReport? = null,
    ) {
        override fun toString() = "YouTubeAudioInput(identity=redacted,report=redacted)"
    }

    data class RepresentationMetadata(val key: String, val mimeType: String, val codecs: String, val extension: String)

    class ResolvedAudio(
        val media: ExtractedMedia,
        val uri: String,
        val transport: StreamTransport,
        val mimeType: String,
        val codecs: String?,
        val expiresAtEpochMs: Long?,
        val durationMs: Long?,
        val key: String,
        val resource: PlaybackResource?,
    ) {
        override fun toString() = "YouTubeResolvedAudio(source=redacted,resource=redacted)"
    }

    class Selection(
        val report: ExtractionReport,
        val media: ExtractedMedia,
        val audio: Representation,
        val key: String,
        val refreshed: Boolean,
    ) {
        override fun toString() = "YouTubeAudioSelection(report=redacted)"
    }

    class DownloadPlan(val selection: Selection, val plan: PlannedDownload) {
        val representation = RepresentationMetadata(selection.key, plan.primary.materializedAudioMime(),
            requireNotNull(plan.primary.audioCodec()), requireNotNull(plan.primary.materializedAudioExtension()))
        override fun toString() = "YouTubeAudioDownloadPlan(plan=redacted)"
    }

    /** Only a private boundary wrapper can expose this as a public prepared capability. */
    class PreparedDownload(
        private val owner: YouTubeAudioExecution,
        val input: Input,
        val capabilities: EngineCapabilities,
        private val initial: DownloadPlan,
    ) {
        val representation: RepresentationMetadata get() = initial.representation
        private val consumption = Mutex()
        private var consumed = false

        suspend fun consume(expectedOwner: YouTubeAudioExecution): DownloadPlan = withContext(NonCancellable) {
            consumption.withLock {
                if (owner !== expectedOwner || consumed) {
                    youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_PREPARED_DOWNLOAD_CONSUMED")
                }
                // Entry consumes even when cancellation was already requested. A failed/blank
                // destination or canceled attempt is not permission to reuse this capability.
                consumed = true
                initial
            }
        }

        override fun toString() = "YouTubePreparedAudioDownload(plan=redacted)"
    }

    /** Publication is nonsuspending and happens while an unhanded native owner is protected. */
    suspend fun <T> resolve(
        input: Input,
        capabilities: EngineCapabilities,
        purpose: ResolvePurpose,
        requiredKey: String? = null,
        publish: (ResolvedAudio) -> T,
    ): T {
        val engine = capabilities.detached()
        val download = if (purpose == ResolvePurpose.DOWNLOAD) downloadPlan(input, engine, requiredKey) else null
        val selected = download?.selection ?: select(input, engine, requiredKey, purpose)
        val playback = if (purpose == ResolvePurpose.PLAYBACK)
            client.playbackPlan(selected.report, YouTubeFormatSelection(selected.audio.id), selected.media.id) else null
        val plan = playback?.plan ?: requireNotNull(download).plan
        requirePublicAudio(plan, selected.media.id)
        var opened: YouTubePlaybackResource? = null
        try {
            val owned = if (playback != null && plan.primary.transport == TransportKind.SABR) {
                requireOpen()
                val resource = client.openPlayback(playback, SourcePlaybackAuthorization { request, resourceUrl ->
                    requireOpen()
                    if (request !== playback || request.plan.mediaId != selected.media.id) {
                        youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_PLAYBACK_IDENTITY_CHANGED")
                    }
                    PublicUrlPolicy.normalize(resourceUrl)
                    val initial = Url(requireNotNull(plan.primary.sabr).endpoint)
                    val actual = Url(resourceUrl)
                    fun googleVideo(host: String) = host == "googlevideo.com" || host.endsWith(".googlevideo.com")
                    if (actual.host != initial.host && !(googleVideo(actual.host) && googleVideo(initial.host))) {
                        youtubeFailure(FailureCode.UNSUPPORTED_SOURCE, "YOUTUBE_PLAYBACK_ORIGIN_CHANGED")
                    }
                    YouTubeDownloadPolicy.requireResource(resourceUrl, plan.inspectionUrl, plan.mediaId)
                })
                opened = resource
                val endpoint = resource.endpoints.singleOrNull { it.role == TrackRole.AUDIO }
                    ?: youtubeFailure(FailureCode.PARSE_FAILURE, "YOUTUBE_AUDIO_ENDPOINT_MISSING")
                object : PlaybackResource {
                    override val localEndpoint = EphemeralPlaybackEndpoint(endpoint.url)
                    override suspend fun close() {
                        try { resource.close() } catch (failure: Exception) { failure.asYouTubeFailure() }
                    }
                    override fun toString() = "YouTubePlaybackResource(<redacted>)"
                }
            } else null
            val result = ResolvedAudio(
                media = selected.media,
                uri = owned?.localEndpoint?.uri ?: requireNotNull(plan.primary.url),
                transport = if (owned != null) StreamTransport.PROGRESSIVE else when (plan.primary.transport) {
                    TransportKind.PROGRESSIVE -> StreamTransport.PROGRESSIVE
                    TransportKind.SABR -> StreamTransport.SABR
                    TransportKind.HLS -> StreamTransport.HLS
                    TransportKind.DASH -> StreamTransport.DASH
                    TransportKind.UNRESOLVED -> youtubeFailure(FailureCode.UNSUPPORTED_TRANSPORT, "YOUTUBE_AUDIO_TRANSPORT_UNAVAILABLE")
                },
                mimeType = if (purpose == ResolvePurpose.DOWNLOAD) plan.primary.materializedAudioMime() else plan.primary.audioMime(),
                codecs = plan.primary.audioCodec(),
                expiresAtEpochMs = plan.primary.expiresAtEpochMs ?: selected.audio.expiresAtEpochMs,
                durationMs = plan.primary.durationMs ?: selected.media.durationMs,
                key = selected.key,
                resource = owned,
            )
            requireOpen()
            currentCoroutineContext().ensureActive()
            val published = publish(result)
            currentCoroutineContext().ensureActive()
            return published
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { opened?.close() }
                catch (_: Throwable) {
                    // kmpYT registers before publication and retains sticky failed owners.
                    // This is NOT a release ACK: source/client close must still fail and the
                    // application must retain that owner. Preserve the primary cancellation.
                }
            }
            throw failure
        }
    }

    suspend fun prepareDownload(input: Input, capabilities: EngineCapabilities, requiredKey: String?): PreparedDownload {
        val engine = capabilities.detached()
        val planned = downloadPlan(input, engine, requiredKey)
        requireOpen()
        currentCoroutineContext().ensureActive()
        return PreparedDownload(this, input, engine, planned)
    }

    suspend fun download(
        prepared: PreparedDownload,
        destinationPath: String,
        onProgress: suspend (YouTubeTransferProgress) -> Unit,
    ): YouTubeTransferResult {
        var planned = prepared.consume(this)
        currentCoroutineContext().ensureActive()
        requireOpen()
        if (destinationPath.isBlank()) youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_INVALID_DESTINATION")
        while (true) {
            // A queued preparation may expire before transfer. This is the SAME total budget
            // used during preparation, not a new inspection/renewal allowance per method.
            if (planned.expired()) planned = renew(prepared, planned)
            if (planned.representation != prepared.representation) {
                youtubeFailure(FailureCode.RECOVERY_REQUIRED, "YOUTUBE_SELECTED_TRACK_CHANGED")
            }
            val plan = planned.plan
            requireOpen()
            currentCoroutineContext().ensureActive()
            try {
                val result = client.download(plan, destinationPath) { progress ->
                    if (progress.bytesDownloaded < 0 || progress.totalBytes?.let { it < progress.bytesDownloaded } == true) {
                        youtubeFailure(FailureCode.CORRUPT_MEDIA, "YOUTUBE_INVALID_TRANSFER_COUNTS")
                    }
                    onProgress(YouTubeTransferProgress(progress.bytesDownloaded, progress.totalBytes, progress.speedBytesPerSecond.coerceAtLeast(0)))
                }
                val exactCopy = plan.primary.segments.isEmpty() && plan.primary.initialization == null &&
                    plan.primary.transport in setOf(TransportKind.PROGRESSIVE, TransportKind.SABR)
                if (result.bytesWritten <= 0 || exactCopy && plan.primary.sizeBytes?.let { it != result.bytesWritten } == true ||
                    result.mimeType.substringBefore(';').trim().lowercase() != plan.primary.materializedAudioMime()) {
                    youtubeFailure(FailureCode.CORRUPT_MEDIA, "YOUTUBE_TRANSFER_IDENTITY_CHANGED")
                }
                currentCoroutineContext().ensureActive()
                return YouTubeTransferResult(result.bytesWritten, planned.representation.mimeType,
                    planned.representation.codecs, planned.representation.extension, planned.representation.key)
            } catch (failure: Exception) {
                if (!failure.isYouTubeExpiredLink() || planned.selection.refreshed) throw failure
                planned = renew(prepared, planned)
            }
        }
    }

    private suspend fun renew(prepared: PreparedDownload, previous: DownloadPlan): DownloadPlan {
        if (previous.selection.refreshed) youtubeFailure(FailureCode.RECOVERY_REQUIRED, "YOUTUBE_SOURCE_EXPIRED")
        return downloadPlan(prepared.input, prepared.capabilities, previous.selection.key, refreshed = true, initial = null)
    }

    private suspend fun downloadPlan(
        input: Input,
        engine: EngineCapabilities,
        requiredKey: String?,
        refreshed: Boolean = false,
        initial: ExtractionReport? = input.initialReport,
    ): DownloadPlan {
        var selected = select(input, engine, requiredKey, ResolvePurpose.DOWNLOAD, refreshed, initial)
        while (true) {
            try {
                val plan = client.plan(selected.report, YouTubeFormatSelection(selected.audio.id), selected.media.id)
                requirePublicAudio(plan, selected.media.id)
                if (!plan.primary.nativeAudioSupported(engine, plan.primary.materializedAudioMime()) ||
                    plan.primary.audioCodec() != selected.audio.audioCodec()) {
                    youtubeFailure(FailureCode.UNSUPPORTED_CODEC, "YOUTUBE_MATERIALIZED_AUDIO_UNSUPPORTED")
                }
                val result = DownloadPlan(selected, plan)
                if (!result.expired()) return result
                if (selected.refreshed) youtubeFailure(FailureCode.RECOVERY_REQUIRED, "YOUTUBE_SOURCE_EXPIRED")
            } catch (failure: Exception) {
                if (!failure.isYouTubeExpiredLink() || selected.refreshed) throw failure
            }
            selected = select(input, engine, selected.key, ResolvePurpose.DOWNLOAD, refreshed = true, initial = null)
        }
    }

    private suspend fun select(
        input: Input,
        engine: EngineCapabilities,
        requiredKey: String?,
        purpose: ResolvePurpose,
        refreshed: Boolean = false,
        initial: ExtractionReport? = input.initialReport,
    ): Selection {
        if (requiredKey != null && !Regex("yt-audio-v1:[0-9a-f]{64}").matches(requiredKey)) {
            youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_INVALID_SELECTION")
        }
        var key = requiredKey
        var consumedRefresh = refreshed
        var supplied = initial
        while (true) {
            requireOpen()
            currentCoroutineContext().ensureActive()
            val inspected = supplied ?: input.inspect()
            supplied = null
            inspected.sourceFailure()?.let { throw VeyraException(it) }
            requireMedia(inspected, input.videoId)
            // Expansion uses only source-declared catalogues through the same kmpYT client.
            val report = if (purpose == ResolvePurpose.DOWNLOAD) client.expandFormats(inspected, input.videoId) else inspected
            report.sourceFailure()?.let { throw VeyraException(it) }
            val media = requireMedia(report, input.videoId)
            if (media.representations.none { it.selectable || it.expiredSourceOnly() } &&
                report.diagnostics.any { it.code == "PLAYER_CONTEXT_RUNTIME_UNAVAILABLE" }) {
                youtubeFailure(FailureCode.RECOVERY_REQUIRED, "YOUTUBE_RUNTIME_UNAVAILABLE")
            }
            val audio = selectYouTubeAudio(media, engine, key, purpose, client.platformName, client.supportsSabrPlayback)
            val selectedKey = audio.audioSelectionKey(input.videoId)
            if (audio.expired()) {
                if (consumedRefresh) youtubeFailure(FailureCode.RECOVERY_REQUIRED, "YOUTUBE_SOURCE_EXPIRED")
                key = selectedKey
                consumedRefresh = true
                continue
            }
            return Selection(report, media, audio, selectedKey, consumedRefresh)
        }
    }

    fun requireMedia(report: ExtractionReport, videoId: String): ExtractedMedia {
        if (report.provider != ProviderId.YOUTUBE || YouTubeDownloadPolicy.videoId(report.input) != videoId) {
            youtubeFailure(FailureCode.PARSE_FAILURE, "YOUTUBE_IDENTITY_MISMATCH")
        }
        val media = report.media.singleOrNull { it.id == videoId && it.provider == ProviderId.YOUTUBE }
            ?: throw VeyraException(report.noMediaFailure())
        if (YouTubeDownloadPolicy.videoId(media.pageUrl) != videoId || !YouTubeBrowseText.isCompleteTitle(media.title)) {
            youtubeFailure(FailureCode.PARSE_FAILURE, "YOUTUBE_IDENTITY_MISMATCH")
        }
        return media
    }

    private fun requirePublicAudio(plan: PlannedDownload, videoId: String) {
        if (plan.mediaId != videoId || YouTubeDownloadPolicy.videoId(plan.inspectionUrl) != videoId) {
            youtubeFailure(FailureCode.PARSE_FAILURE, "YOUTUBE_IDENTITY_MISMATCH")
        }
        if (plan.audio != null || plan.primary.role != TrackRole.AUDIO || plan.requestHeaders.isNotEmpty() || plan.credentialRef != null) {
            youtubeFailure(FailureCode.UNSUPPORTED_SOURCE, "YOUTUBE_NONPUBLIC_AUDIO_PLAN")
        }
    }

    private fun DownloadPlan.expired() = selection.audio.expired() || plan.primary.expired()
    private fun Representation.expired() = expiredSourceOnly() || expiresAtEpochMs?.let {
        val now = nowEpochMs()
        it <= now || it - now <= MIN_VALIDITY_MS
    } == true

    private fun EngineCapabilities.detached() = copy(transports = transports.toSet(), mimeTypes = mimeTypes.toSet(), codecs = codecs.toSet())

    private companion object { const val MIN_VALIDITY_MS = 15_000L }
}
