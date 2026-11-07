package dev.veyra.source.youtube

import dev.kmpyt.youtube.YouTubeBrowseEntryIssue
import dev.kmpyt.youtube.YouTubeBrowseKind
import dev.kmpyt.youtube.YouTubeBrowseText
import dev.veyra.model.*
import dev.veyra.source.execution.AcquisitionGateway
import dev.veyra.source.execution.AcquisitionInspection
import dev.veyra.source.execution.CollectionContinuation
import dev.veyra.source.execution.SourceCollectionPage

/**
 * Minimal schema-3 producer for the EXISTING catalogue MEDIA journey at the delivered kmpYT pin.
 * It performs exactly one unchanged complete-title search and consumes the actual raw ordinal-zero
 * entry. Aggregate collection execution remains explicitly unavailable; a non-video first entry is
 * an honest Unplayable receipt, never a later-order substitution. No second client or engine exists.
 */
class YouTubeAcquisitionGateway internal constructor(
    private val source: YouTubeMediaSource,
) : AcquisitionGateway {
    override suspend fun acquire(request: AcquisitionRequest): SourceAcquisitionResult = guarded {
        val page = source.firstSearchPage(request.completeTitle)
        if (page.query != request.completeTitle) failure(FailureCode.PARSE_FAILURE, "YOUTUBE_QUERY_RECEIPT_MISMATCH")
        val notice = youtubeBrowseNotice(page.notice)?.takeIf { it.blocking }?.failure
        val entry = page.firstEntry ?: run {
            notice?.let { throw VeyraException(it) }
            if (page.items.isNotEmpty()) failure(FailureCode.PARSE_FAILURE, "YOUTUBE_FIRST_RECEIPT_MISSING")
            return@guarded SourceAcquisitionResult.Unplayable(request.id, null,
                AcquisitionProblem(AcquisitionProblemCode.NO_FIRST_RESULT))
        }
        if (entry.ordinal != 0) failure(FailureCode.PARSE_FAILURE, "YOUTUBE_FIRST_ORDINAL_CHANGED")
        result(request, entry, notice)
    }

    override fun restore(request: AcquisitionRequest, receipt: FirstResultObservation): SourceAcquisitionResult {
        val entry = receipt.entry
        if (receipt.requestId != request.id || receipt.queryTitle != request.completeTitle || entry.ordinal != 0) invalid()
        if (entry.kind != AcquisitionEntryKind.VIDEO || entry.issue != null || entry.id?.let(VIDEO_ID::matches) != true ||
            entry.title?.let(YouTubeBrowseText::isCompleteTitle) != true ||
            entry.publicUrl != canonicalUrl(FirstResultKind.VIDEO, entry.id) ||
            receipt.canonicalUrl != entry.publicUrl) {
            return SourceAcquisitionResult.Unplayable(request.id, receipt,
                AcquisitionProblem(AcquisitionProblemCode.FIRST_RESULT_UNSUPPORTED))
        }
        return SourceAcquisitionResult.Single(request.id, receipt,
            SourceDisplayItem(entry.id, requireNotNull(entry.title), requireNotNull(entry.publicUrl), AcquisitionEntryKind.VIDEO))
    }

    /** Existing catalogue MEDIA only: aggregate album/show traversal is not silently fabricated. */
    override suspend fun collection(request: AcquisitionRequest, selection: SourceAcquisitionResult.Collection,
        limits: CollectionLimits): SourceCollectionPage =
        failure(FailureCode.UNSUPPORTED_SOURCE, "typed_collection_upstream_unavailable")

    override suspend fun more(continuation: CollectionContinuation): SourceCollectionPage =
        failure(FailureCode.UNSUPPORTED_SOURCE, "typed_collection_upstream_unavailable")

    /**
     * Direct inspection of this exact selected video, never a search of its returned title.
     * The report stays private to this same application-owned client/source lifetime.
     */
    override suspend fun inspect(request: AcquisitionRequest,
        selection: SourceAcquisitionResult.Single): AcquisitionInspection = guarded {
        val restored = restore(request, selection.receipt)
        if (restored !is SourceAcquisitionResult.Single || restored != selection) invalid()
        val report = source.inspectSelected(requireNotNull(selection.receipt.entry.id))
        YouTubeAcquisitionInspection(source, request, selection, report)
    }

    private suspend fun result(request: AcquisitionRequest, entry: dev.kmpyt.youtube.YouTubeBrowseEntry,
        pageFailure: AppFailure?): SourceAcquisitionResult {
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
        val validTitle = YouTubeBrowseText.isCompleteTitle(title)
        val entryIssue = when (entry.issue) {
            YouTubeBrowseEntryIssue.UNAVAILABLE -> SourceEntryIssue.UNAVAILABLE
            YouTubeBrowseEntryIssue.UNSUPPORTED -> SourceEntryIssue.UNSUPPORTED
            YouTubeBrowseEntryIssue.INVALID_METADATA -> SourceEntryIssue.INVALID_METADATA
            YouTubeBrowseEntryIssue.TEXT_LIMIT -> SourceEntryIssue.TEXT_LIMIT
            YouTubeBrowseEntryIssue.PARSE_LIMIT -> SourceEntryIssue.PARSE_LIMIT
            null -> null
        }
        val video = validIdentity && validTitle && rawKind == FirstResultKind.VIDEO && entryIssue == null
        val url = canonicalUrl(rawKind, id).takeIf { validIdentity }
        val sourceEntry = SourceEntry(
            kind = if (validIdentity) when (rawKind) {
                FirstResultKind.VIDEO -> AcquisitionEntryKind.VIDEO
                FirstResultKind.CHANNEL -> AcquisitionEntryKind.CHANNEL
                FirstResultKind.PLAYLIST -> AcquisitionEntryKind.PLAYLIST
                FirstResultKind.UNKNOWN -> null
            } else null,
            id = id.takeIf { validIdentity }, title = title.takeIf(YouTubeBrowseText::isCompleteTitle),
            publicUrl = url, issue = entryIssue, ordinal = 0,
        )
        val receipt = FirstResultObservation(request.id, request.completeTitle, sourceEntry, url)
        return when {
            video -> SourceAcquisitionResult.Single(request.id, receipt,
                SourceDisplayItem(id, title, requireNotNull(url), AcquisitionEntryKind.VIDEO))
            pageFailure != null || entryIssue == SourceEntryIssue.UNAVAILABLE ||
                entryIssue == SourceEntryIssue.UNSUPPORTED ->
                SourceAcquisitionResult.Unplayable(request.id, receipt,
                    AcquisitionProblem(AcquisitionProblemCode.FIRST_RESULT_UNAVAILABLE))
            rawKind == FirstResultKind.CHANNEL || rawKind == FirstResultKind.PLAYLIST ->
                SourceAcquisitionResult.Unplayable(request.id, receipt,
                    AcquisitionProblem(AcquisitionProblemCode.FIRST_RESULT_UNSUPPORTED))
            else -> SourceAcquisitionResult.Unplayable(request.id, receipt,
                AcquisitionProblem(AcquisitionProblemCode.INVALID_FIRST_RESULT))
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T =
        try { block() } catch (failure: VeyraException) { throw failure } catch (failure: Exception) {
            throw VeyraException(failure.asYouTubeFailure())
        }

    private fun invalid(): Nothing = failure(FailureCode.INVALID_STATE, "YOUTUBE_ACQUISITION_RECEIPT_MISMATCH")
    private fun failure(code: FailureCode, detail: String): Nothing = throw VeyraException(AppFailure(code, detail))

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

/** Same-client private report. No transport URI, header, plan or PlaybackResource is exposed. */
internal class YouTubeAcquisitionInspection(
    val owner: YouTubeMediaSource,
    val request: AcquisitionRequest,
    override val selection: SourceAcquisitionResult.Single,
    val report: dev.kmpyt.extraction.ExtractionReport,
) : AcquisitionInspection {
    override fun toString() = "YouTubeAcquisitionInspection(identity=redacted,report=redacted)"
}
