package dev.veyra.source.youtube

import dev.kmpyt.YouTubeClient
import dev.kmpyt.extraction.ExtractionReport
import dev.kmpyt.youtube.*
import dev.veyra.model.*
import dev.veyra.source.execution.AcquisitionGateway
import dev.veyra.source.execution.AcquisitionInspection
import dev.veyra.source.execution.CollectionContinuation
import dev.veyra.source.execution.SourceCollectionPage
import io.ktor.http.Url

/**
 * D010 source observations, not persisted bindings or a second media executor. The application
 * owns [client] and closes it once; this adapter neither acquires a separate client nor closes it.
 * Requests are already detached/admitted by Veyra. Only their exact selection fields go upstream.
 */
class YouTubeAcquisitionGateway(private val client: YouTubeClient) : AcquisitionGateway {
    override suspend fun acquire(request: AcquisitionRequest): SourceAcquisitionResult = guarded {
        request.memberProblem()?.let { return@guarded SourceAcquisitionResult.Unplayable(request.id, null, it) }
        // A saved pending B is reconstructed with the PUBLIC member request. Never reload its
        // listing or manufacture an upstream member/result using an internal constructor/copy.
        client.acquire(request.toYouTube()).toSource(request)
    }

    override fun restore(request: AcquisitionRequest, receipt: FirstResultObservation): SourceAcquisitionResult = guarded {
        request.memberProblem()?.let { return@guarded SourceAcquisitionResult.Unplayable(request.id, receipt, it) }
        client.restoreAcquisition(request.toYouTube(), receipt.toYouTube()).toSource(request)
    }

    override suspend fun collection(
        request: AcquisitionRequest,
        selection: SourceAcquisitionResult.Collection,
        limits: CollectionLimits,
    ): SourceCollectionPage = guarded {
        val restored = checkedRestore(request, selection.receipt) as? YouTubeAcquisitionResult.Collection
            ?: invalidSelection()
        if (restored.toSource(request) != selection) invalidSelection()
        page(request, selection, client.collection(restored,
            YouTubeCollectionLimits(limits.maxPages, limits.maxMembers, limits.maxMembersPerPage)))
    }

    override suspend fun more(continuation: CollectionContinuation): SourceCollectionPage = guarded {
        val owned = continuation as? Continuation ?: invalidContinuation()
        if (owned.owner !== client) invalidContinuation()
        // Exactly one explicit use. Upstream owns expiration, successful consumption and its
        // retryable/terminal failure distinction. A failed attempt never becomes an empty delta.
        page(owned.request, owned.selection, client.moreCollection(owned.delegate), owned)
    }

    override suspend fun inspect(
        request: AcquisitionRequest,
        selection: SourceAcquisitionResult.Single,
    ): AcquisitionInspection = guarded {
        val restored = checkedRestore(request, selection.receipt) as? YouTubeAcquisitionResult.Single
            ?: invalidSelection()
        if (selection.requestId != request.id || !selection.recording.matches(restored.video)) invalidSelection()
        // The same receipt may describe a full-release recording or B -> C. Neither case
        // warrants another title search or a synthetic MUSIC/EPISODE MediaItem.
        YouTubeAcquisitionInspection(client, request, selection, client.inspect(restored))
    }

    private fun checkedRestore(request: AcquisitionRequest, receipt: FirstResultObservation): YouTubeAcquisitionResult {
        if (request.memberProblem() != null) invalidSelection()
        return client.restoreAcquisition(request.toYouTube(), receipt.toYouTube())
    }

    private fun page(
        request: AcquisitionRequest,
        selection: SourceAcquisitionResult.Collection,
        upstream: YouTubeCollectionPage,
        previous: Continuation? = null,
    ): SourceCollectionPage {
        if (upstream.selection.toSource(request) != selection) invalidSelection()
        val data = CollectionPageData(
            selection = selection,
            observationId = CollectionObservationId(upstream.observationId),
            observation = upstream.observation?.toSource(),
            disposition = when (upstream.disposition) {
                YouTubeCollectionPageDisposition.OBSERVED_DELTA -> CollectionPageDisposition.OBSERVED_DELTA
                YouTubeCollectionPageDisposition.FAILED_ATTEMPT -> CollectionPageDisposition.FAILED_ATTEMPT
            },
            pageIndex = upstream.pageIndex,
            startOrdinal = upstream.startOrdinal,
            members = upstream.members.map { CollectionMember(it.reference.toSource(), it.entry.toSource(), it.item?.toSource()) },
            extent = CollectionExtent(
                traversal = when (upstream.extent.traversal) {
                    YouTubeCollectionTraversal.MORE_AVAILABLE -> CollectionTraversal.MORE_AVAILABLE
                    YouTubeCollectionTraversal.OBSERVED_TO_END -> CollectionTraversal.OBSERVED_TO_END
                    YouTubeCollectionTraversal.INCOMPLETE -> CollectionTraversal.INCOMPLETE
                },
                observedMemberCount = upstream.extent.observedMemberCount,
                nonPlayableMemberCount = upstream.extent.nonPlayableMemberCount,
                problem = upstream.extent.problem?.toSource(),
            ),
        )
        val next = upstream.continuation?.let { cursor ->
            // Retain the same opaque capability on a retryable failure, not a new serializable
            // token or a fresh observation. Successful advancement yields a different cursor.
            previous?.takeIf { it.delegate === cursor } ?: Continuation(client, request, selection, cursor)
        }
        return SourceCollectionPage(data, next)
    }

    private class Continuation(
        val owner: YouTubeClient,
        val request: AcquisitionRequest,
        val selection: SourceAcquisitionResult.Collection,
        val delegate: YouTubeCollectionContinuation,
    ) : CollectionContinuation {
        override fun toString() = "YouTubeCollectionContinuation(context=redacted)"
    }

    private fun YouTubeAcquisitionResult.toSource(request: AcquisitionRequest): SourceAcquisitionResult {
        if (this.request != request.toYouTube()) invalidSelection()
        return when (this) {
            is YouTubeAcquisitionResult.Single -> SourceAcquisitionResult.Single(request.id, receipt.toSource(), video.toSource())
            is YouTubeAcquisitionResult.Collection -> SourceAcquisitionResult.Collection(request.id,
                receipt.toSource(), identity.toSource(), scope.toSource())
            is YouTubeAcquisitionResult.Unplayable -> SourceAcquisitionResult.Unplayable(request.id, receipt?.toSource(),
                AcquisitionProblem(when (problem.issue) {
                    YouTubeAcquisitionIssue.INVALID_REQUEST -> AcquisitionProblemCode.INVALID_REQUEST
                    YouTubeAcquisitionIssue.NO_FIRST_RESULT -> AcquisitionProblemCode.NO_FIRST_RESULT
                    YouTubeAcquisitionIssue.SOURCE_FAILURE -> AcquisitionProblemCode.SOURCE_FAILURE
                    YouTubeAcquisitionIssue.INVALID_FIRST_RESULT -> AcquisitionProblemCode.INVALID_FIRST_RESULT
                    YouTubeAcquisitionIssue.FIRST_RESULT_UNAVAILABLE -> AcquisitionProblemCode.FIRST_RESULT_UNAVAILABLE
                    YouTubeAcquisitionIssue.FIRST_RESULT_UNSUPPORTED -> AcquisitionProblemCode.FIRST_RESULT_UNSUPPORTED
                    YouTubeAcquisitionIssue.MEMBER_NOT_PLAYABLE -> AcquisitionProblemCode.MEMBER_NOT_PLAYABLE
                }, problem.sourceProblem?.toSource()))
        }
    }

    private fun AcquisitionRequest.toYouTube() = YouTubeAcquisitionRequest(
        requestId = id.value,
        kind = when (kind) {
            AcquisitionKind.MEDIA -> YouTubeAcquisitionKind.MEDIA
            AcquisitionKind.ALBUM -> YouTubeAcquisitionKind.ALBUM
            AcquisitionKind.RELEASE -> YouTubeAcquisitionKind.RELEASE
            AcquisitionKind.SHOW -> YouTubeAcquisitionKind.SHOW
            AcquisitionKind.SOURCE_COLLECTION -> YouTubeAcquisitionKind.SOURCE_COLLECTION
            AcquisitionKind.COLLECTION_MEMBER -> YouTubeAcquisitionKind.COLLECTION_MEMBER
        },
        completeTitle = completeTitle,
        metadataRevision = selectionMetadataRevision,
        memberReference = (metadata as? CollectionMemberCapture)?.member?.reference?.toYouTube(),
    )

    /**
     * Public COLLECTION_MEMBER requests intentionally carry only the reference/title. Veyra
     * still owns B's persisted raw entry, so reject holes and corrupt B/display links BEFORE
     * sending that request. This validates structure, not the historical truth of a snapshot.
     */
    private fun AcquisitionRequest.memberProblem(): AcquisitionProblem? {
        val member = (metadata as? CollectionMemberCapture)?.member ?: return null
        val reference = member.reference
        val identity = reference.identity
        val validIdentity = when (identity.kind) {
            AcquisitionEntryKind.PLAYLIST -> PLAYLIST_ID.matches(identity.id) &&
                identity.canonicalUrl == "$ORIGIN/playlist?list=${identity.id}" && reference.scope == CollectionScope.PLAYLIST_MEMBERS
            AcquisitionEntryKind.CHANNEL -> CHANNEL_ID.matches(identity.id) &&
                identity.canonicalUrl == "$ORIGIN/channel/${identity.id}" && reference.scope == CollectionScope.CHANNEL_VIDEOS
            AcquisitionEntryKind.VIDEO -> false
        }
        if (!validIdentity || reference.parentRequestId == id || reference.ordinal !in 0 until 5_000 ||
            member.entry.ordinal != reference.ordinal || !identifier(id.value) ||
            !identifier(reference.observationId.value) || !identifier(reference.parentRequestId.value) ||
            selectionMetadataRevision?.let { !identifier(it) } == true) {
            return AcquisitionProblem(AcquisitionProblemCode.INVALID_REQUEST)
        }
        val entry = member.entry
        val display = member.display
        if (entry.kind != AcquisitionEntryKind.VIDEO || entry.issue != null ||
            entry.id?.let(VIDEO_ID::matches) != true || entry.title?.let(YouTubeBrowseText::isCompleteTitle) != true ||
            entry.publicUrl != "$ORIGIN/watch?v=${entry.id}" || completeTitle != entry.title ||
            display == null || display.kind != entry.kind || display.id != entry.id ||
            display.title != entry.title || display.publicUrl != entry.publicUrl) {
            return AcquisitionProblem(AcquisitionProblemCode.MEMBER_NOT_PLAYABLE)
        }
        return null
    }

    private fun FirstResultObservation.toYouTube() = YouTubeFirstResultReceipt(
        requestId.value, queryTitle, entry.toYouTube(), canonicalUrl)
    private fun YouTubeFirstResultReceipt.toSource() = FirstResultObservation(
        AcquisitionRequestId(requestId), queryTitle, firstEntry.toSource(), canonicalUrl)

    private fun SourceEntry.toYouTube() = YouTubeBrowseEntry(kind?.toYouTube(), id, title, publicUrl, issue?.toYouTube(), ordinal)
    private fun YouTubeBrowseEntry.toSource() = SourceEntry(kind?.toSource(), id, title, url, issue?.toSource(), ordinal)
    private fun YouTubeBrowseItem.toSource() = SourceDisplayItem(id, title, url, kind.toSource(),
        stableArtwork(thumbnailUrl), subtitle, durationText)
    private fun SourceDisplayItem.matches(item: YouTubeBrowseItem) = kind == item.kind.toSource() &&
        id == item.id && title == item.title && publicUrl == item.url

    private fun CollectionIdentity.toYouTube() = YouTubeCollectionIdentity(kind.toYouTube(), id, canonicalUrl)
    private fun YouTubeCollectionIdentity.toSource() = CollectionIdentity(kind.toSource(), id, canonicalUrl)
    private fun CollectionMemberReference.toYouTube() = YouTubeCollectionMemberReference(
        observationId.value, parentRequestId.value, identity.toYouTube(), scope.toYouTube(), ordinal)
    private fun YouTubeCollectionMemberReference.toSource() = CollectionMemberReference(
        CollectionObservationId(observationId), AcquisitionRequestId(parentRequestId), identity.toSource(), scope.toSource(), ordinal)
    private fun YouTubeCollectionObservation.toSource() = CollectionObservation(
        CollectionObservationId(observationId), AcquisitionRequestId(parentRequestId), identity.toSource(), scope.toSource(), title,
        when (titleOrigin) {
            YouTubeCollectionTitleOrigin.PLAYLIST_METADATA -> CollectionTitleOrigin.PLAYLIST_METADATA
            YouTubeCollectionTitleOrigin.PLAYLIST_HEADER -> CollectionTitleOrigin.PLAYLIST_HEADER
            YouTubeCollectionTitleOrigin.CHANNEL_METADATA -> CollectionTitleOrigin.CHANNEL_METADATA
        },
        identityEvidence.map { evidence -> when (evidence) {
            YouTubeCollectionIdentityEvidence.PLAYLIST_LIST -> CollectionIdentityEvidence.PLAYLIST_LIST
            YouTubeCollectionIdentityEvidence.PLAYLIST_METADATA -> CollectionIdentityEvidence.PLAYLIST_METADATA
            YouTubeCollectionIdentityEvidence.PLAYLIST_HEADER -> CollectionIdentityEvidence.PLAYLIST_HEADER
            YouTubeCollectionIdentityEvidence.PLAYLIST_CANONICAL -> CollectionIdentityEvidence.PLAYLIST_CANONICAL
            YouTubeCollectionIdentityEvidence.CHANNEL_METADATA -> CollectionIdentityEvidence.CHANNEL_METADATA
        } }.toSet(),
        sourceUrl,
        when (consistency) { YouTubeCollectionConsistency.UNVERSIONED_OBSERVATION -> CollectionConsistency.UNVERSIONED_OBSERVATION },
    )

    private fun AcquisitionEntryKind.toYouTube() = when (this) {
        AcquisitionEntryKind.VIDEO -> YouTubeBrowseKind.VIDEO
        AcquisitionEntryKind.CHANNEL -> YouTubeBrowseKind.CHANNEL
        AcquisitionEntryKind.PLAYLIST -> YouTubeBrowseKind.PLAYLIST
    }
    private fun YouTubeBrowseKind.toSource() = when (this) {
        YouTubeBrowseKind.VIDEO -> AcquisitionEntryKind.VIDEO
        YouTubeBrowseKind.CHANNEL -> AcquisitionEntryKind.CHANNEL
        YouTubeBrowseKind.PLAYLIST -> AcquisitionEntryKind.PLAYLIST
    }
    private fun CollectionScope.toYouTube() = when (this) {
        CollectionScope.PLAYLIST_MEMBERS -> YouTubeCollectionScope.PLAYLIST_MEMBERS
        CollectionScope.CHANNEL_VIDEOS -> YouTubeCollectionScope.CHANNEL_VIDEOS
    }
    private fun YouTubeCollectionScope.toSource() = when (this) {
        YouTubeCollectionScope.PLAYLIST_MEMBERS -> CollectionScope.PLAYLIST_MEMBERS
        YouTubeCollectionScope.CHANNEL_VIDEOS -> CollectionScope.CHANNEL_VIDEOS
    }
    private fun SourceEntryIssue.toYouTube() = when (this) {
        SourceEntryIssue.UNAVAILABLE -> YouTubeBrowseEntryIssue.UNAVAILABLE
        SourceEntryIssue.UNSUPPORTED -> YouTubeBrowseEntryIssue.UNSUPPORTED
        SourceEntryIssue.INVALID_METADATA -> YouTubeBrowseEntryIssue.INVALID_METADATA
        SourceEntryIssue.TEXT_LIMIT -> YouTubeBrowseEntryIssue.TEXT_LIMIT
        SourceEntryIssue.PARSE_LIMIT -> YouTubeBrowseEntryIssue.PARSE_LIMIT
    }
    private fun YouTubeBrowseEntryIssue.toSource() = when (this) {
        YouTubeBrowseEntryIssue.UNAVAILABLE -> SourceEntryIssue.UNAVAILABLE
        YouTubeBrowseEntryIssue.UNSUPPORTED -> SourceEntryIssue.UNSUPPORTED
        YouTubeBrowseEntryIssue.INVALID_METADATA -> SourceEntryIssue.INVALID_METADATA
        YouTubeBrowseEntryIssue.TEXT_LIMIT -> SourceEntryIssue.TEXT_LIMIT
        YouTubeBrowseEntryIssue.PARSE_LIMIT -> SourceEntryIssue.PARSE_LIMIT
    }
    private fun YouTubeBrowseProblem.toSource() = SourceProblem(when (code) {
        YouTubeBrowseProblemCode.NETWORK -> SourceProblemCode.NETWORK
        YouTubeBrowseProblemCode.ACCESS_REQUIRED -> SourceProblemCode.ACCESS_REQUIRED
        YouTubeBrowseProblemCode.NOT_FOUND -> SourceProblemCode.NOT_FOUND
        YouTubeBrowseProblemCode.RATE_LIMITED -> SourceProblemCode.RATE_LIMITED
        YouTubeBrowseProblemCode.REDIRECTED -> SourceProblemCode.REDIRECTED
        YouTubeBrowseProblemCode.HTTP_ERROR -> SourceProblemCode.HTTP_ERROR
        YouTubeBrowseProblemCode.RESPONSE_LIMIT -> SourceProblemCode.RESPONSE_LIMIT
        YouTubeBrowseProblemCode.INVALID_RESPONSE -> SourceProblemCode.INVALID_RESPONSE
    }, httpStatus)
    private fun YouTubeCollectionProblem.toSource() = CollectionProblem(when (issue) {
        YouTubeCollectionIssue.INVALID_SELECTION -> CollectionProblemCode.INVALID_SELECTION
        YouTubeCollectionIssue.SOURCE_FAILURE -> CollectionProblemCode.SOURCE_FAILURE
        YouTubeCollectionIssue.INVALID_RESPONSE -> CollectionProblemCode.INVALID_RESPONSE
        YouTubeCollectionIssue.IDENTITY_UNVERIFIED -> CollectionProblemCode.IDENTITY_UNVERIFIED
        YouTubeCollectionIssue.IDENTITY_MISMATCH -> CollectionProblemCode.IDENTITY_MISMATCH
        YouTubeCollectionIssue.SCOPE_UNVERIFIED -> CollectionProblemCode.SCOPE_UNVERIFIED
        YouTubeCollectionIssue.TITLE_UNAVAILABLE -> CollectionProblemCode.TITLE_UNAVAILABLE
        YouTubeCollectionIssue.MEMBERSHIP_UNVERIFIED -> CollectionProblemCode.MEMBERSHIP_UNVERIFIED
        YouTubeCollectionIssue.PARSE_LIMIT -> CollectionProblemCode.PARSE_LIMIT
        YouTubeCollectionIssue.MEMBER_LIMIT -> CollectionProblemCode.MEMBER_LIMIT
        YouTubeCollectionIssue.PAGE_LIMIT -> CollectionProblemCode.PAGE_LIMIT
        YouTubeCollectionIssue.INVALID_CONTINUATION -> CollectionProblemCode.INVALID_CONTINUATION
        YouTubeCollectionIssue.CONTINUATION_UNAVAILABLE -> CollectionProblemCode.CONTINUATION_UNAVAILABLE
        YouTubeCollectionIssue.CONTINUATION_MISMATCH -> CollectionProblemCode.CONTINUATION_MISMATCH
        YouTubeCollectionIssue.REPEATED_CONTINUATION -> CollectionProblemCode.REPEATED_CONTINUATION
        YouTubeCollectionIssue.EXPIRED_CONTINUATION -> CollectionProblemCode.EXPIRED_CONTINUATION
        YouTubeCollectionIssue.CONSUMED_CONTINUATION -> CollectionProblemCode.CONSUMED_CONTINUATION
    }, sourceProblem?.toSource())

    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (failure: Exception) { failure.asYouTubeFailure() }

    private companion object {
        const val ORIGIN = "https://www.youtube.com"
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        val CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")
        val PLAYLIST_ID = Regex("[A-Za-z0-9_-]{2,200}")
        val ARTWORK_HOSTS = setOf("i.ytimg.com", "i1.ytimg.com", "i2.ytimg.com", "i3.ytimg.com", "i4.ytimg.com",
            "yt3.ggpht.com", "yt3.googleusercontent.com")
        fun identifier(value: String) = value.length <= 512 && YouTubeBrowseText.isCompleteTitle(value)
        fun invalidSelection(): Nothing = youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_ACQUISITION_SELECTION_MISMATCH")
        fun invalidContinuation(): Nothing = youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_COLLECTION_CONTINUATION_MISMATCH")

        /** Ancillary display art is optional. Never persist signed/tracking URLs as stable metadata. */
        fun stableArtwork(value: String?): String? {
            if (value == null || value.length > 2_000) return null
            val url = runCatching { Url(value) }.getOrNull() ?: return null
            return value.takeIf { url.protocol.name == "https" && url.port == 443 &&
                url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.parameters.isEmpty() &&
                url.fragment.isEmpty() && url.host.lowercase() in ARTWORK_HOSTS }
        }
    }
}

/** App-private, same-client report. Its public port exposes only the exact immutable selection. */
internal class YouTubeAcquisitionInspection internal constructor(
    internal val owner: YouTubeClient,
    internal val request: AcquisitionRequest,
    override val selection: SourceAcquisitionResult.Single,
    internal val report: ExtractionReport,
) : AcquisitionInspection {
    override fun toString() = "YouTubeAcquisitionInspection(report=redacted)"
}
