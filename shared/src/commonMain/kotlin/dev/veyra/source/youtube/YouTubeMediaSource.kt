package dev.veyra.source.youtube

import dev.kmpyt.YouTubeClient
import dev.kmpyt.YouTubePlaybackResource
import dev.kmpyt.extraction.ExtractedMedia
import dev.kmpyt.extraction.ExtractionReport
import dev.kmpyt.extraction.ProviderId
import dev.kmpyt.extraction.Representation
import dev.kmpyt.extraction.SourcePlaybackAuthorization
import dev.kmpyt.extraction.TrackRole
import dev.kmpyt.extraction.TransportKind
import dev.kmpyt.extraction.YouTubeDownloadPolicy
import dev.kmpyt.youtube.YouTubeBrowseItem
import dev.kmpyt.youtube.YouTubeBrowseKind
import dev.kmpyt.youtube.YouTubeBrowsePage
import dev.kmpyt.youtube.YouTubeBrowseText
import dev.kmpyt.youtube.YouTubeFormatSelection
import dev.veyra.model.FailureCode
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.model.StreamTransport
import dev.veyra.model.VeyraException
import dev.veyra.model.newEntityId
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.EphemeralPlaybackEndpoint
import dev.veyra.playback.PlaybackResource
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.source.BrowseKind
import dev.veyra.source.BrowseRequest
import dev.veyra.source.MediaCollection
import dev.veyra.source.MediaSource
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.SourceCapabilities
import dev.veyra.source.SourcePage
import dev.veyra.source.network.PublicUrlPolicy
import io.ktor.http.Url
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

data class YouTubeTransferProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long? = null,
    val speedBytesPerSecond: Long = 0,
)

/** Transfer completion is not offline readiness: the caller must validate, hash and commit the file. */
data class YouTubeTransferResult(
    val bytesWritten: Long,
    val mimeType: String,
    val codecs: String,
    val extension: String,
    val representationId: String,
)

/**
 * Real anonymous kmpYT adapter. This object owns [client] until [close]. Signed
 * sources and browse continuation contexts live only in memory; durable items
 * contain canonical watch identities. No account or challenge bypass is attempted.
 *
 * Pagination returns only new occurrences, not the upstream accumulated page.
 * Cursors are single-use after a successful next page and expire after ten minutes.
 */
@OptIn(ExperimentalTime::class)
class YouTubeMediaSource(
    private val client: YouTubeClient,
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : MediaSource {
    override val id = SourceProvider.YOUTUBE
    override val capabilities = SourceCapabilities(search = true, browse = true, resolve = true, download = true)

    private val browseLock = Mutex()
    private val cursors = linkedMapOf<String, Cursor>()
    private var closed = false

    private data class Cursor(val route: String, val page: YouTubeBrowsePage, val createdAtMs: Long)
    private data class Selection(
        val report: ExtractionReport,
        val media: ExtractedMedia,
        val audio: Representation,
        val key: String,
        val refreshed: Boolean,
    )

    override suspend fun search(query: String, pageToken: String?): SourcePage = guarded {
        if (!YouTubeBrowseText.isCompleteTitle(query)) {
            youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_INVALID_QUERY")
        }
        browseLock.withLock { loadPage("search:$query", pageToken) { client.search(query) } }
    }

    /** Execution uses the upstream leading-entry receipt, never the split/filtered UI page. */
    internal suspend fun firstSearchPage(completeTitle: String): YouTubeBrowsePage = guarded {
        if (!YouTubeBrowseText.isCompleteTitle(completeTitle)) youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_INVALID_QUERY")
        client.search(completeTitle)
    }

    override suspend fun browse(request: BrowseRequest): SourcePage = guarded {
        val target = when (request.kind) {
            BrowseKind.HOME -> {
                if (request.id != null) youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_INVALID_BROWSE")
                null
            }
            BrowseKind.CHANNEL -> channelUrl(request.id)
            BrowseKind.PLAYLIST -> playlistUrl(request.id)
            BrowseKind.SHOW -> youtubeFailure(FailureCode.UNSUPPORTED_SOURCE, "YOUTUBE_NOT_A_PODCAST_SOURCE")
        }
        // Language is part of cursor identity, not an invented private client profile.
        val route = "browse:${request.kind}:$target:${request.language}"
        browseLock.withLock {
            loadPage(route, request.pageToken) {
                when (request.kind) {
                    BrowseKind.HOME -> client.home()
                    BrowseKind.CHANNEL -> client.channel(requireNotNull(target))
                    BrowseKind.PLAYLIST -> client.playlist(requireNotNull(target))
                    BrowseKind.SHOW -> error("Unsupported route was rejected above")
                }
            }
        }
    }

    override suspend fun details(ref: SourceRef): MediaItem = guarded {
        val videoId = requireVideo(ref)
        val report = client.inspect(watchUrl(videoId))
        // Public metadata may remain readable when playback requires authorization.
        // details never promises playability; resolve performs the access check.
        requireMedia(report, videoId).toMediaItem()
    }

    override suspend fun resolve(
        item: MediaItem,
        capabilities: EngineCapabilities,
        purpose: ResolvePurpose,
    ): ResolvedPlayable = guarded {
        val selected = inspectSelection(item, capabilities, purpose = purpose)
        val playback = if (purpose == ResolvePurpose.PLAYBACK)
            client.playbackPlan(selected.report, YouTubeFormatSelection(selected.audio.id), selected.media.id) else null
        val plan = playback?.plan ?: client.plan(selected.report, YouTubeFormatSelection(selected.audio.id), selected.media.id)
        if (plan.audio != null || plan.primary.role != TrackRole.AUDIO || plan.requestHeaders.isNotEmpty() || plan.credentialRef != null) {
            youtubeFailure(FailureCode.UNSUPPORTED_SOURCE, "YOUTUBE_NONPUBLIC_AUDIO_PLAN")
        }
        var resource: YouTubePlaybackResource? = null
        try {
            val owned = if (playback != null && plan.primary.transport == TransportKind.SABR) {
                val opened = client.openPlayback(playback, SourcePlaybackAuthorization { request, resourceUrl ->
                    // This is an explicit anonymous acquisition policy, NOT an
                    // integrity grant or an assumption that a denied video works.
                    browseLock.withLock { if (closed) youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_SOURCE_CLOSED") }
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
                resource = opened
                val endpoint = opened.endpoints.singleOrNull { it.role == TrackRole.AUDIO }
                    ?: youtubeFailure(FailureCode.PARSE_FAILURE, "YOUTUBE_AUDIO_ENDPOINT_MISSING")
                object : PlaybackResource {
                    override val localEndpoint = EphemeralPlaybackEndpoint(endpoint.url)
                    override suspend fun close() = opened.close()
                    override fun toString() = "YouTubePlaybackResource(<redacted>)"
                }
            } else null
            // DOWNLOAD is a preparation receipt, not a URI for a native player.
            // Its transport describes acquisition; MIME/codec describe the file
            // kmpYT will materialize. No media is transferred until download().
            ResolvedPlayable(
                mediaId = item.id,
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
                seekableHint = true,
                representationId = selected.key,
                item = selected.media.toMediaItem(),
                resource = owned,
            )
        } catch (failure: Throwable) {
            withContext(NonCancellable) { try { resource?.close() } catch (_: Throwable) { /* Preserve primary failure. */ } }
            throw failure
        }
    }

    /**
     * Delegate to the real native kmpYT transfer into a caller-owned private staging
     * destination. Preserve that destination and [representationId] across retries.
     * The caller must never mark a file Ready from this return value alone.
     *
     * A rejected/expired media link permits at most one fresh public inspection;
     * authorization is rechecked and a different selected track is never substituted.
     */
    suspend fun download(
        item: MediaItem,
        destinationPath: String,
        capabilities: EngineCapabilities = EngineCapabilities(),
        representationId: String? = null,
        onProgress: suspend (YouTubeTransferProgress) -> Unit = {},
    ): YouTubeTransferResult = guarded(download = true) {
        if (destinationPath.isBlank()) youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_INVALID_DESTINATION")
        var selected = inspectSelection(item, capabilities, representationId, purpose = ResolvePurpose.DOWNLOAD)
        while (true) {
            val plan = client.plan(selected.report, YouTubeFormatSelection(selected.audio.id), selected.media.id)
            if (plan.audio != null || plan.primary.role != TrackRole.AUDIO ||
                !plan.primary.nativeAudioSupported(capabilities, plan.primary.materializedAudioMime()) ||
                plan.primary.audioCodec() != selected.audio.audioCodec()) {
                youtubeFailure(FailureCode.UNSUPPORTED_CODEC, "YOUTUBE_MATERIALIZED_AUDIO_UNSUPPORTED")
            }
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
                return@guarded YouTubeTransferResult(
                    bytesWritten = result.bytesWritten,
                    mimeType = plan.primary.materializedAudioMime(),
                    codecs = requireNotNull(plan.primary.audioCodec()),
                    extension = requireNotNull(plan.primary.materializedAudioExtension()),
                    representationId = selected.key,
                )
            } catch (failure: Exception) {
                if (!failure.isYouTubeExpiredLink() || selected.refreshed) throw failure
                selected = inspectSelection(item, capabilities, selected.key, allowRefresh = false, purpose = ResolvePurpose.DOWNLOAD).copy(refreshed = true)
            }
        }
        @Suppress("UNREACHABLE_CODE") error("Transfer loop did not return")
    }

    override suspend fun close() {
        browseLock.withLock { closed = true; cursors.clear() }
        client.close()
    }

    private suspend fun inspectSelection(
        item: MediaItem,
        engine: EngineCapabilities,
        requiredKey: String? = null,
        allowRefresh: Boolean = true,
        purpose: ResolvePurpose = ResolvePurpose.PLAYBACK,
    ): Selection {
        val videoId = requireVideo(item.source)
        if (item.id != mediaId(videoId) || item.kind != MediaKind.MUSIC) {
            youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_MEDIA_IDENTITY_MISMATCH")
        }
        var selectionKey = requiredKey
        var refreshed = false
        while (true) {
            val inspected = client.inspect(watchUrl(videoId))
            inspected.sourceFailure()?.let { throw VeyraException(it) }
            requireMedia(inspected, videoId)
            // Expansion uses only actual source-declared catalogues through
            // kmpYT. RSS/Commons never become alternative media engines.
            val report = if (purpose == ResolvePurpose.DOWNLOAD) client.expandFormats(inspected, videoId) else inspected
            report.sourceFailure()?.let { throw VeyraException(it) }
            val media = requireMedia(report, videoId)
            if (media.representations.none { it.selectable || it.expiredSourceOnly() } &&
                report.diagnostics.any { it.code == "PLAYER_CONTEXT_RUNTIME_UNAVAILABLE" }) {
                youtubeFailure(FailureCode.RECOVERY_REQUIRED, "YOUTUBE_RUNTIME_UNAVAILABLE")
            }
            val audio = selectYouTubeAudio(media, engine, selectionKey, purpose, client.platformName, client.supportsSabrPlayback)
            val key = audio.audioSelectionKey(videoId)
            val expiry = audio.expiresAtEpochMs
            if (audio.expiredSourceOnly() || expiry != null && expiry - nowEpochMs() <= MIN_VALIDITY_MS) {
                if (!allowRefresh || refreshed) youtubeFailure(FailureCode.RECOVERY_REQUIRED, "YOUTUBE_SOURCE_EXPIRED")
                selectionKey = key
                refreshed = true
                continue
            }
            return Selection(report, media, audio, key, refreshed)
        }
    }

    private suspend fun loadPage(route: String, pageToken: String?, first: suspend () -> YouTubeBrowsePage): SourcePage {
        if (closed) youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_SOURCE_CLOSED")
        val now = nowEpochMs()
        cursors.entries.removeAll { now < it.value.createdAtMs || now - it.value.createdAtMs >= CURSOR_TTL_MS }
        val cursor = pageToken?.let { token ->
            cursors[token]?.takeIf { it.route == route }
                ?: youtubeFailure(FailureCode.RECOVERY_REQUIRED, "YOUTUBE_CURSOR_EXPIRED")
        }
        val previousCount = cursor?.page?.items?.size ?: 0
        val page = if (cursor == null) first() else client.more(cursor.page)
        if (page.items.size < previousCount || cursor != null && page.items.take(previousCount) != cursor.page.items) {
            youtubeFailure(FailureCode.PARSE_FAILURE, "YOUTUBE_PAGE_IDENTITY_CHANGED")
        }
        val notice = youtubeBrowseNotice(page.notice)
        if (cursor == null && page.items.isEmpty() && notice?.blocking == true) throw VeyraException(notice.failure)
        val delta = page.items.drop(previousCount)
        // A failed continuation does not consume the token: retained UI rows stay
        // intact, with an explicit retryable notice and the same opaque cursor.
        val nextToken = if (page.continuation != null && page.continuationContext != null) {
            if (cursor != null && page.continuation == cursor.page.continuation && notice?.blocking == true) pageToken
            else {
                pageToken?.let(cursors::remove)
                while (cursors.size >= MAX_CURSORS || cursors.values.sumOf { it.page.items.size } + page.items.size > MAX_CURSOR_ITEMS) {
                    cursors.remove(cursors.keys.firstOrNull() ?: break)
                }
                newEntityId("yt-page").also { cursors[it] = Cursor(route, page, now) }
            }
        } else { pageToken?.let(cursors::remove); null }
        return SourcePage(
            items = delta.filter { it.kind == YouTubeBrowseKind.VIDEO }.map { it.toMediaItem() },
            collections = delta.filter { it.kind != YouTubeBrowseKind.VIDEO }.map { it.toCollection() },
            nextPageToken = nextToken,
            attribution = "YouTube",
            notice = notice?.failure,
        )
    }

    private suspend fun <T> guarded(download: Boolean = false, block: suspend () -> T): T {
        browseLock.withLock { if (closed) youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_SOURCE_CLOSED") }
        return try { block() } catch (error: Exception) { error.asYouTubeFailure(download) }
    }

    private fun requireMedia(report: ExtractionReport, videoId: String): ExtractedMedia {
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

    private fun requireVideo(ref: SourceRef): String {
        if (ref.provider != SourceProvider.YOUTUBE || !VIDEO_ID.matches(ref.canonicalId) ||
            ref.canonicalUrl?.let { YouTubeDownloadPolicy.videoId(it) != ref.canonicalId } == true ||
            ref.feedUrl != null || ref.enclosureUrl != null || ref.localAssetId != null) {
            youtubeFailure(FailureCode.UNSUPPORTED_SOURCE, "YOUTUBE_INVALID_VIDEO")
        }
        return ref.canonicalId
    }

    private fun channelUrl(value: String?): String = when {
        value == null -> youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_INVALID_CHANNEL")
        CHANNEL_ID.matches(value) -> "https://www.youtube.com/channel/$value"
        value.startsWith('@') && value.length in 2..101 && value.none { it.code < 32 || it in "/?#" } -> value
        value.startsWith("https://") -> value // kmpYT validates/canonicalizes public channel URLs before transport.
        else -> youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_INVALID_CHANNEL")
    }

    private fun playlistUrl(value: String?): String = when {
        value == null -> youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_INVALID_PLAYLIST")
        Regex("[A-Za-z0-9_-]{2,150}").matches(value) -> "https://www.youtube.com/playlist?list=$value"
        value.startsWith("https://") -> value // kmpYT rejects unsupported origins, credentials and paths.
        else -> youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_INVALID_PLAYLIST")
    }

    private fun YouTubeBrowseItem.toMediaItem(): MediaItem {
        if (!VIDEO_ID.matches(id) || YouTubeDownloadPolicy.videoId(url) != id) {
            youtubeFailure(FailureCode.PARSE_FAILURE, "YOUTUBE_IDENTITY_MISMATCH")
        }
        return MediaItem(
            id = mediaId(id), title = title, kind = MediaKind.MUSIC, source = sourceRef(id),
            creator = subtitle.orEmpty(), artworkUrl = publicArtwork(thumbnailUrl), durationMs = durationText?.let(::parseDuration),
        )
    }

    private fun ExtractedMedia.toMediaItem() = MediaItem(
        id = mediaId(id), title = title, kind = MediaKind.MUSIC, source = sourceRef(id),
        creator = artist.orEmpty(), artworkUrl = publicArtwork(thumbnailUrl), durationMs = durationMs?.takeIf { it > 0 },
        // kmpYT's artist is the video author/uploader, not a declared musical artist.
    )

    private fun YouTubeBrowseItem.toCollection(): MediaCollection {
        val kind = if (this.kind == YouTubeBrowseKind.CHANNEL) BrowseKind.CHANNEL else BrowseKind.PLAYLIST
        val canonical = if (kind == BrowseKind.CHANNEL) "https://www.youtube.com/channel/$id" else "https://www.youtube.com/playlist?list=$id"
        return MediaCollection(
            id = "youtube:${kind.name.lowercase()}:$id", title = title,
            source = SourceRef(SourceProvider.YOUTUBE, id, canonical), artworkUrl = publicArtwork(thumbnailUrl),
            creator = subtitle.orEmpty(), kind = kind,
        )
    }

    private companion object {
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        val CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")
        const val CURSOR_TTL_MS = 10 * 60_000L
        const val MAX_CURSORS = 16
        const val MAX_CURSOR_ITEMS = 10_000
        const val MIN_VALIDITY_MS = 15_000L
        fun mediaId(videoId: String) = "youtube:$videoId"
        fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"
        fun sourceRef(videoId: String) = SourceRef(SourceProvider.YOUTUBE, videoId, watchUrl(videoId))

        fun publicArtwork(value: String?): String? = value?.takeIf {
            val url = runCatching { Url(it) }.getOrNull() ?: return@takeIf false
            // Only stable public YouTube artwork. Avoid persisting signed media or
            // arbitrary tracking/credential URLs returned in malformed metadata.
            url.protocol.name == "https" && url.user.isNullOrEmpty() && url.password.isNullOrEmpty() &&
                url.parameters.isEmpty() && url.fragment.isEmpty() &&
                setOf("i.ytimg.com", "i1.ytimg.com", "i2.ytimg.com", "i3.ytimg.com", "i4.ytimg.com", "yt3.ggpht.com", "yt3.googleusercontent.com").contains(url.host.lowercase())
        }

        fun parseDuration(value: String): Long? {
            val components = value.trim().split(':')
            if (components.size !in 2..3 || components.any { it.isBlank() || it.any { digit -> digit !in '0'..'9' } }) return null
            val numbers = components.map { it.toLongOrNull() ?: return null }
            if (numbers.drop(1).any { it !in 0..59 }) return null
            var seconds = 0L
            numbers.forEach { number ->
                if (seconds > (Long.MAX_VALUE / 1000 - number) / 60) return null
                seconds = seconds * 60 + number
            }
            return (seconds * 1000).takeIf { it > 0 }
        }
    }
}
