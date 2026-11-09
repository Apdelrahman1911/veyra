package dev.veyra.source.youtube

import dev.kmpyt.youtube.YouTubeBrowseEntryIssue
import dev.kmpyt.youtube.YouTubeBrowseKind
import dev.kmpyt.youtube.YouTubeBrowseText
import dev.veyra.model.AcquisitionRequest
import dev.veyra.model.AppFailure
import dev.veyra.model.CollectionLimits
import dev.veyra.model.FailureCode
import dev.veyra.model.FirstResultKind
import dev.veyra.model.FirstResultObservation
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.PlaybackBinding
import dev.veyra.model.SourceAcquisitionResult
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.model.VeyraException
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.execution.AcquisitionExecutionContext
import dev.veyra.source.execution.AcquisitionExecutionGateway
import dev.veyra.source.execution.AcquisitionGateway
import dev.veyra.source.execution.AcquisitionInspection
import dev.veyra.source.execution.AcquisitionPlaybackSource
import dev.veyra.source.execution.AcquisitionRepresentation
import dev.veyra.source.execution.AcquisitionTransferProgress
import dev.veyra.source.execution.AcquisitionTransferResult
import dev.veyra.source.execution.CollectionContinuation
import dev.veyra.source.execution.KmpFirstResult
import dev.veyra.source.execution.KmpMediaGateway
import dev.veyra.source.execution.LegacyPreparedDownloadGateway
import dev.veyra.source.execution.LegacyTransferResult
import dev.veyra.source.execution.PreparedAcquisitionDownload
import dev.veyra.source.execution.PreparedLegacyDownload
import dev.veyra.source.execution.SourceCollectionPage

/**
 * The production acquisition bridge for ALL discovery sources. [source] and this gateway share
 * one app-lifetime kmpYT client; the application closes the source once. Original podcast/music
 * semantics belong to AppSourceResolver. Only the immutable selected YouTube video is executed.
 */
class YouTubeKmpMediaGateway(private val source: YouTubeMediaSource) :
    KmpMediaGateway, LegacyPreparedDownloadGateway, AcquisitionGateway, AcquisitionExecutionGateway {
    // One supplied facade exposes every supported port over the same client/source lifetime.
    private val selection = source.acquisitionGateway
    private val execution = YouTubeAcquisitionExecutionGateway(source)

    override suspend fun acquire(request: AcquisitionRequest): SourceAcquisitionResult = selection.acquire(request)
    override fun restore(request: AcquisitionRequest, receipt: FirstResultObservation): SourceAcquisitionResult =
        selection.restore(request, receipt)
    override suspend fun collection(request: AcquisitionRequest, selection: SourceAcquisitionResult.Collection,
        limits: CollectionLimits): SourceCollectionPage = this.selection.collection(request, selection, limits)
    override suspend fun more(continuation: CollectionContinuation): SourceCollectionPage = selection.more(continuation)
    override suspend fun inspect(request: AcquisitionRequest,
        selection: SourceAcquisitionResult.Single): AcquisitionInspection = this.selection.inspect(request, selection)
    override suspend fun preparePlayback(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
        requiredRepresentationId: String?, inspection: AcquisitionInspection?): AcquisitionPlaybackSource =
        execution.preparePlayback(context, capabilities, requiredRepresentationId, inspection)
    override suspend fun prepareDownload(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
        requiredRepresentationId: String?, inspection: AcquisitionInspection?): PreparedAcquisitionDownload =
        execution.prepareDownload(context, capabilities, requiredRepresentationId, inspection)
    override suspend fun download(prepared: PreparedAcquisitionDownload, destinationPath: String,
        onProgress: suspend (AcquisitionTransferProgress) -> Unit): AcquisitionTransferResult =
        execution.download(prepared, destinationPath, onProgress)

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

    /**
     * Known schema2 receipt only; never invent a typed request or manufacture a MediaItem.
     * The retained private plan is the same accepted report/selection used at transfer time.
     */
    override suspend fun prepareDownload(binding: PlaybackBinding, capabilities: EngineCapabilities,
        requiredRepresentationId: String?): PreparedLegacyDownload = guardedDownload {
        val videoId = selectedVideo(binding)
        val input = YouTubeAudioExecution.Input(videoId, inspect = { source.inspectSelected(videoId) })
        PreparedLegacy(this, binding, requiredRepresentationId, source.audioExecution.prepareDownload(input, capabilities, requiredRepresentationId))
    }

    override suspend fun download(prepared: PreparedLegacyDownload, destinationPath: String,
        onProgress: suspend (AcquisitionTransferProgress) -> Unit): LegacyTransferResult = guardedDownload {
        // Reject a foreign/forged capability BEFORE reading caller-defined getters or I/O.
        val owned = prepared as? PreparedLegacy ?: invalidPrepared()
        if (owned.owner !== this) invalidPrepared()
        // The shared primitive consumes on entry, including cancellation/blank destinations,
        // and carries preparation's already-used same-C/key renewal budget into transfer.
        val result = source.audioExecution.download(owned.delegate, destinationPath) { progress ->
            onProgress(AcquisitionTransferProgress(progress.bytesDownloaded, progress.totalBytes, progress.speedBytesPerSecond))
        }
        val representation = AcquisitionRepresentation(result.representationId, result.mimeType, result.codecs, result.extension)
        if (representation != owned.representation) {
            youtubeFailure(FailureCode.CORRUPT_MEDIA, "YOUTUBE_TRANSFER_IDENTITY_CHANGED")
        }
        LegacyTransferResult(owned.binding, result.bytesWritten, representation)
    }

    private class PreparedLegacy(
        val owner: YouTubeKmpMediaGateway,
        override val binding: PlaybackBinding,
        override val requiredRepresentationId: String?,
        val delegate: YouTubeAudioExecution.PreparedDownload,
    ) : PreparedLegacyDownload {
        override val representation = delegate.representation.let {
            AcquisitionRepresentation(it.key, it.mimeType, it.codecs, it.extension)
        }
        override fun toString() = "YouTubePreparedLegacyDownload(binding=redacted,plan=redacted)"
    }

    private suspend fun <T> guardedDownload(block: suspend () -> T): T =
        try { block() } catch (failure: VeyraException) { throw failure } catch (failure: Exception) {
            throw VeyraException(failure.asYouTubeFailure(download = true))
        }

    private fun selectedItem(binding: PlaybackBinding): MediaItem {
        val videoId = selectedVideo(binding)
        // This is the selected native media identity, NOT an overwrite of the discovery episode.
        return MediaItem(id = "youtube:$videoId", title = binding.firstResultTitle, kind = MediaKind.MUSIC,
            source = SourceRef(SourceProvider.YOUTUBE, videoId, canonicalUrl(FirstResultKind.VIDEO, videoId)))
    }

    /** Shared structural validation; a public receipt is not proof of historical source I/O. */
    private fun selectedVideo(binding: PlaybackBinding): String {
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
        return videoId
    }

    private companion object {
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        val CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")
        val PLAYLIST_ID = Regex("[A-Za-z0-9_-]{2,200}")
        fun invalidPrepared(): Nothing = youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_PREPARED_DOWNLOAD_MISMATCH")
        fun canonicalUrl(kind: FirstResultKind, id: String): String? = when (kind) {
            FirstResultKind.VIDEO -> "https://www.youtube.com/watch?v=$id"
            FirstResultKind.CHANNEL -> "https://www.youtube.com/channel/$id"
            FirstResultKind.PLAYLIST -> "https://www.youtube.com/playlist?list=$id"
            FirstResultKind.UNKNOWN -> null
        }
    }
}
