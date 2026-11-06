package dev.veyra.source.youtube

import dev.kmpyt.youtube.YouTubeBrowseEntryIssue
import dev.kmpyt.youtube.YouTubeBrowseKind
import dev.kmpyt.youtube.YouTubeBrowseText
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.FirstResultKind
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.PlaybackBinding
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.model.VeyraException
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.execution.KmpFirstResult
import dev.veyra.source.execution.KmpMediaGateway

/**
 * The production acquisition bridge for ALL discovery sources. [source] and this gateway share
 * one app-lifetime kmpYT client; the application closes the source once. Original podcast/music
 * semantics belong to AppSourceResolver. Only the immutable selected YouTube video is executed.
 */
class YouTubeKmpMediaGateway(private val source: YouTubeMediaSource) : KmpMediaGateway {
    override suspend fun firstResult(completeTitle: String): KmpFirstResult? {
        val page = source.firstSearchPage(completeTitle)
        if (page.query != completeTitle) youtubeFailure(FailureCode.PARSE_FAILURE, "YOUTUBE_QUERY_RECEIPT_MISMATCH")
        val pageFailure = youtubeBrowseNotice(page.notice)?.takeIf { it.blocking }?.failure
        val entry = page.firstEntry ?: run {
            pageFailure?.let { throw VeyraException(it) }
            // Older/changed library parsers may not silently turn a missing raw receipt into
            // the first surviving playable row. An empty result really must contain no cards.
            if (page.items.isNotEmpty()) youtubeFailure(FailureCode.PARSE_FAILURE, "YOUTUBE_FIRST_RECEIPT_MISSING")
            return null
        }
        if (entry.ordinal != 0) youtubeFailure(FailureCode.PARSE_FAILURE, "YOUTUBE_FIRST_ORDINAL_CHANGED")
        val id = entry.id.orEmpty()
        val title = entry.title.orEmpty()
        val rawKind = when (entry.kind) {
            YouTubeBrowseKind.VIDEO -> FirstResultKind.VIDEO
            YouTubeBrowseKind.CHANNEL -> FirstResultKind.CHANNEL
            YouTubeBrowseKind.PLAYLIST -> FirstResultKind.PLAYLIST
            null -> FirstResultKind.UNKNOWN
        }
        val validIdentity = when (rawKind) {
            FirstResultKind.VIDEO -> VIDEO_ID.matches(id)
            FirstResultKind.CHANNEL -> CHANNEL_ID.matches(id)
            FirstResultKind.PLAYLIST -> PLAYLIST_ID.matches(id)
            FirstResultKind.UNKNOWN -> false
        }
        val validMetadata = validIdentity && YouTubeBrowseText.isCompleteTitle(title) &&
            entry.issue !in setOf(YouTubeBrowseEntryIssue.INVALID_METADATA, YouTubeBrowseEntryIssue.TEXT_LIMIT, YouTubeBrowseEntryIssue.PARSE_LIMIT)
        val kind = rawKind.takeIf { validMetadata } ?: FirstResultKind.UNKNOWN
        val failure = when (entry.issue) {
            YouTubeBrowseEntryIssue.UNAVAILABLE -> AppFailure(FailureCode.UNSUPPORTED_SOURCE, "YOUTUBE_FIRST_RESULT_UNAVAILABLE")
            YouTubeBrowseEntryIssue.UNSUPPORTED -> AppFailure(FailureCode.UNSUPPORTED_SOURCE, "YOUTUBE_FIRST_RESULT_UNSUPPORTED")
            YouTubeBrowseEntryIssue.INVALID_METADATA -> AppFailure(FailureCode.PARSE_FAILURE, "YOUTUBE_FIRST_RESULT_INVALID")
            YouTubeBrowseEntryIssue.TEXT_LIMIT -> AppFailure(FailureCode.PARSE_FAILURE, "YOUTUBE_FIRST_RESULT_TEXT_LIMIT")
            YouTubeBrowseEntryIssue.PARSE_LIMIT -> AppFailure(FailureCode.PARSE_FAILURE, "YOUTUBE_FIRST_RESULT_PARSE_LIMIT")
            null -> if (validMetadata) null else AppFailure(FailureCode.PARSE_FAILURE, "YOUTUBE_FIRST_RESULT_INVALID")
        }
        return KmpFirstResult(
            queryTitle = completeTitle,
            kind = kind,
            id = id.takeIf { validIdentity }.orEmpty(),
            title = title.takeIf(YouTubeBrowseText::isCompleteTitle).orEmpty(),
            // Do not persist upstream's @handle/tab/redirect URL or any source capability.
            canonicalUrl = canonicalUrl(kind, id),
            failure = pageFailure ?: failure,
        )
    }

    override suspend fun resolve(binding: PlaybackBinding, capabilities: EngineCapabilities,
        purpose: ResolvePurpose): ResolvedPlayable = source.resolve(selectedItem(binding), capabilities, purpose)
        .copy(mediaId = binding.mediaId, executionBinding = binding)

    override suspend fun download(binding: PlaybackBinding, destinationPath: String,
        capabilities: EngineCapabilities, representationId: String?,
        onProgress: suspend (YouTubeTransferProgress) -> Unit): YouTubeTransferResult =
        source.download(selectedItem(binding), destinationPath, capabilities, representationId, onProgress)

    private fun selectedItem(binding: PlaybackBinding): MediaItem {
        if (binding.firstResultKind != FirstResultKind.VIDEO) {
            youtubeFailure(FailureCode.UNSUPPORTED_SOURCE, "YOUTUBE_FIRST_RESULT_NOT_VIDEO")
        }
        val videoId = binding.firstResultId
        if (binding.id.isBlank() || binding.mediaId.isBlank() || binding.selectionOrdinal != 0 ||
            !VIDEO_ID.matches(videoId) || binding.selectedVideoId != videoId ||
            !YouTubeBrowseText.isCompleteTitle(binding.queryTitle) || !YouTubeBrowseText.isCompleteTitle(binding.firstResultTitle) ||
            binding.firstResultUrl?.let { it != canonicalUrl(FirstResultKind.VIDEO, videoId) } == true) {
            youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_EXECUTION_BINDING_MISMATCH")
        }
        // This is the selected native media identity, NOT an overwrite of the discovery episode.
        return MediaItem(id = "youtube:$videoId", title = binding.firstResultTitle, kind = MediaKind.MUSIC,
            source = SourceRef(SourceProvider.YOUTUBE, videoId, canonicalUrl(FirstResultKind.VIDEO, videoId)))
    }

    private companion object {
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        val CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")
        val PLAYLIST_ID = Regex("[A-Za-z0-9_-]{2,200}")
        fun canonicalUrl(kind: FirstResultKind, id: String): String? = when (kind) {
            FirstResultKind.VIDEO -> "https://www.youtube.com/watch?v=$id"
            FirstResultKind.CHANNEL -> "https://www.youtube.com/channel/$id"
            FirstResultKind.PLAYLIST -> "https://www.youtube.com/playlist?list=$id"
            FirstResultKind.UNKNOWN -> null
        }
    }
}
