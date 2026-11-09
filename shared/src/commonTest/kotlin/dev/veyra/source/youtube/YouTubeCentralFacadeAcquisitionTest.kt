package dev.veyra.source.youtube

import dev.kmpyt.YouTubeDownloadProgress
import dev.kmpyt.YouTubeDownloadResult
import dev.kmpyt.YouTubeDownloader
import dev.kmpyt.extraction.ResearchResponse
import dev.veyra.model.*
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.execution.*
import dev.veyra.source.youtube.AcquisitionExecutionFixtures as F
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Central facade delegation with authored transports, not app wiring/native/Room/live proof. */
class YouTubeCentralFacadeAcquisitionTest {
    private val engine = EngineCapabilities()

    @Test fun oneSuppliedKmpGatewayExposesAllSupportedPortsWithoutOpeningAnotherSource() = runTest {
        var closes = 0
        val fixture = AcquisitionExecutionFixture(onClose = { closes++ })
        try {
            val supplied: KmpMediaGateway = YouTubeKmpMediaGateway(fixture.source)
            assertSame<Any>(supplied, assertIs<AcquisitionGateway>(supplied))
            assertSame<Any>(supplied, assertIs<AcquisitionExecutionGateway>(supplied))
            assertSame<Any>(supplied, assertIs<LegacyPreparedDownloadGateway>(supplied))
            assertTrue(fixture.requests.isEmpty())
            assertEquals(0, closes)
        } finally { fixture.close() }
        assertEquals(1, closes)
    }

    @Test fun theFacadePreservesOneCompleteMemberQueryAndReusesTheExactInspectionForC() = runTest {
        var transfers = 0
        val progress = mutableListOf<AcquisitionTransferProgress>()
        val fixture = AcquisitionExecutionFixture(handler = { request, _ -> ResearchResponse(request.url, 200,
            when (Url(request.url).encodedPath) {
                "/results" -> F.search()
                "/watch" -> { assertEquals(F.WATCH, request.url); F.watch() }
                else -> error("No listing reload, second search or alternate source")
            }) }, downloader = YouTubeDownloader { plan, path, callback ->
            transfers++
            assertEquals(F.VIDEO_C, plan.mediaId)
            assertEquals(F.WATCH, plan.inspectionUrl)
            callback(YouTubeDownloadProgress(32, 32, 7))
            YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val supplied: KmpMediaGateway = YouTubeKmpMediaGateway(fixture.source)
            val selection = assertIs<AcquisitionGateway>(supplied)
            val execution = assertIs<AcquisitionExecutionGateway>(supplied)
            val request = F.request(AcquisitionKind.COLLECTION_MEMBER)
            val single = assertIs<SourceAcquisitionResult.Single>(selection.acquire(request))
            val restored = assertIs<SourceAcquisitionResult.Single>(selection.restore(request, single.receipt))
            assertEquals(single.receipt, restored.receipt)
            assertNotNull(single.recording.thumbnailUrl)
            assertNull(restored.recording.thumbnailUrl)
            val inspected = selection.inspect(request, single)
            assertSame(single, inspected.selection)
            val context = F.context(request, single.receipt)
            val playback = execution.preparePlayback(context, engine, inspection = inspected)
            val prepared = execution.prepareDownload(context, engine, playback.representationId, inspected)
            val result = execution.download(prepared, "authored-central-stage.m4a") { progress += it }
            assertSame(context, playback.context)
            assertSame(context, prepared.context)
            assertSame(context, result.context)
            assertEquals(playback.representationId, prepared.requiredRepresentationId)
            assertEquals(prepared.representation, result.representation)
            assertEquals(32L, result.bytesWritten)
            val savedB = (result.context.request.metadata as CollectionMemberCapture).member
            assertEquals(F.VIDEO_B, savedB.entry.id)
            assertEquals(2, savedB.reference.ordinal)
            assertEquals(F.VIDEO_C, result.context.recording.canonicalId)
            assertEquals(F.RETURNED_TITLE, result.context.receipt.observation.entry.title)
            assertEquals(F.TITLE, result.context.receipt.observation.queryTitle)
            assertIs<ExecutableSubject.SourceRecordingSubject>(playback.context.subject)
            assertNull(playback.resource)
            assertEquals(listOf("/results", "/watch"), fixture.paths())
            assertEquals(F.TITLE, Url(fixture.requests.first().url).parameters["search_query"])
            assertEquals(listOf(AcquisitionTransferProgress(32, 32, 7)), progress)
            assertEquals(1, transfers)
        } finally { fixture.close() }
    }

    @Test fun eachTypedKindForwardsItsOriginalRequestInsteadOfCoercingALegacyMediaItem() = runTest {
        val fixture = AcquisitionExecutionFixture(handler = { request, _ -> ResearchResponse(request.url, 200,
            when (Url(request.url).encodedPath) {
                "/results" -> F.search()
                "/watch" -> { assertEquals(F.WATCH, request.url); F.watch() }
                else -> error("No original catalogue tracks or RSS enclosures")
            }) })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            for (kind in AcquisitionKind.entries) {
                val request = F.request(kind)
                val single = assertIs<SourceAcquisitionResult.Single>(gateway.acquire(request))
                assertEquals(request.id, single.requestId)
                assertEquals(request.completeTitle, single.receipt.queryTitle)
                val context = F.context(request, single.receipt)
                val playback = gateway.preparePlayback(context, engine)
                assertSame(request, playback.context.request)
                assertEquals(kind, playback.context.request.kind)
                if (kind == AcquisitionKind.MEDIA) assertIs<ExecutableSubject.CatalogMedia>(playback.context.subject)
                else assertIs<ExecutableSubject.SourceRecordingSubject>(playback.context.subject)
                assertNull(playback.resource)
            }
            assertEquals(AcquisitionKind.entries.size, fixture.watches)
            assertEquals(AcquisitionKind.entries.flatMap { listOf("/results", "/watch") }, fixture.paths())
            assertTrue(fixture.requests.filter { Url(it.url).encodedPath == "/results" }.all {
                Url(it.url).parameters["search_query"] == F.TITLE
            })
        } finally { fixture.close() }
    }

    @Test fun pureRestoreAndAnUnavailableSavedMemberNeverEnterSourceIo() = runTest {
        val fixture = AcquisitionExecutionFixture(handler = { _, _ -> error("Pure restore/invalid B cannot use transport") })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val request = F.request(AcquisitionKind.RELEASE)
            val receipt = F.receipt(request)
            assertEquals(receipt, assertIs<SourceAcquisitionResult.Single>(gateway.restore(request, receipt)).receipt)
            val memberRequest = F.request(AcquisitionKind.COLLECTION_MEMBER)
            val metadata = memberRequest.metadata as CollectionMemberCapture
            val rejected = memberRequest.copy(metadata = metadata.copy(member = metadata.member.copy(
                entry = metadata.member.entry.copy(issue = SourceEntryIssue.UNAVAILABLE), display = null)))
            val result = assertIs<SourceAcquisitionResult.Unplayable>(gateway.acquire(rejected))
            assertEquals(rejected.id, result.requestId)
            assertEquals(AcquisitionProblemCode.MEMBER_NOT_PLAYABLE, result.problem.code)
            assertNull(result.receipt)
            assertTrue(fixture.requests.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun collectionLimitsHolesDuplicatesAndRetryableCursorIdentityPassThroughUnchanged() = runTest {
        var continuationCalls = 0
        val fixture = AcquisitionExecutionFixture(handler = { request, _ -> when (Url(request.url).encodedPath) {
            "/results" -> ResearchResponse(request.url, 200, playlistSearch())
            "/playlist" -> ResearchResponse(request.url, 200, playlistPage())
            "/youtubei/v1/browse" -> {
                continuationCalls++
                if (continuationCalls == 1) ResearchResponse(request.url, 503, "Authored temporary failure")
                else ResearchResponse(request.url, 200, appendPage())
            }
            else -> error("Only the selected collection can supply its members")
        } })
        try {
            val supplied: KmpMediaGateway = YouTubeKmpMediaGateway(fixture.source)
            val gateway = assertIs<AcquisitionGateway>(supplied)
            val request = F.request(AcquisitionKind.SHOW)
            val selected = assertIs<SourceAcquisitionResult.Collection>(gateway.acquire(request))
            assertEquals(F.PLAYLIST, selected.identity.id)
            val first = gateway.collection(request, selected)
            assertEquals(listOf(0, 1, 2), first.data.members.map { it.reference.ordinal })
            assertEquals(listOf(F.VIDEO_B, F.VIDEO_B, F.VIDEO_B), first.data.members.map { it.entry.id })
            assertNull(first.data.members[1].display)
            assertEquals(SourceEntryIssue.UNAVAILABLE, first.data.members[1].entry.issue)
            val cursor = assertNotNull(first.continuation)
            val failed = gateway.more(cursor)
            assertEquals(1, continuationCalls)
            assertSame(cursor, failed.continuation)
            assertEquals(CollectionPageDisposition.FAILED_ATTEMPT, failed.data.disposition)
            assertEquals(3, failed.data.startOrdinal)
            assertEquals(3, failed.data.extent.observedMemberCount)
            assertEquals(1, failed.data.extent.nonPlayableMemberCount)
            assertEquals(first.data.observationId, failed.data.observationId)
            assertTrue(failed.data.members.isEmpty())
            val next = gateway.more(assertNotNull(failed.continuation))
            assertEquals(2, continuationCalls)
            assertEquals(listOf(3), next.data.members.map { it.reference.ordinal })
            assertEquals(F.VIDEO_C, next.data.members.single().entry.id)
            assertEquals(4, next.data.extent.observedMemberCount)
            assertEquals(1, next.data.extent.nonPlayableMemberCount)
            assertEquals(CollectionTraversal.OBSERVED_TO_END, next.data.extent.traversal)
            assertEquals(first.data.observationId, next.data.observationId)
            assertNull(next.continuation)
            for (member in first.data.members + next.data.members) {
                assertEquals(request.id, member.reference.parentRequestId)
                assertEquals(selected.identity, member.reference.identity)
                assertEquals(selected.scope, member.reference.scope)
            }
            val consumed = gateway.more(cursor)
            assertEquals(CollectionProblemCode.CONSUMED_CONTINUATION, consumed.data.extent.problem?.code)
            assertEquals(2, continuationCalls)
            // Nondefault limits must not be dropped by the facade's delegation.
            val limited = gateway.collection(request, selected, CollectionLimits(maxPages = 1, maxMembers = 8, maxMembersPerPage = 8))
            assertEquals(CollectionTraversal.INCOMPLETE, limited.data.extent.traversal)
            assertEquals(CollectionProblemCode.PAGE_LIMIT, limited.data.extent.problem?.code)
            assertNull(limited.continuation)
            assertNotEquals(first.data.observationId, limited.data.observationId)
            assertEquals(listOf("/results", "/playlist", "/youtubei/v1/browse", "/youtubei/v1/browse", "/playlist"), fixture.paths())
            assertEquals(F.TITLE, Url(fixture.requests.first().url).parameters["search_query"])
        } finally { fixture.close() }
    }

    @Test fun foreignAndForgedInspectionAreForwardedForRejectionRatherThanDroppedForFreshIo() = runTest {
        val owner = AcquisitionExecutionFixture()
        val other = AcquisitionExecutionFixture(handler = { _, _ -> error("A foreign inspection cannot fall back to I/O") })
        try {
            val first = YouTubeKmpMediaGateway(owner.source)
            val second = YouTubeKmpMediaGateway(other.source)
            val context = F.context()
            val selected = assertIs<SourceAcquisitionResult.Single>(first.restore(context.request, context.receipt.observation))
            val inspected = first.inspect(context.request, selected)
            val forged = object : AcquisitionInspection {
                override val selection: SourceAcquisitionResult.Single get() = error("Do not read forged public getters")
            }
            for (invalid in listOf(inspected, forged)) {
                val failure = assertFailsWith<VeyraException> { second.prepareDownload(context, engine, inspection = invalid) }
                assertEquals("YOUTUBE_ACQUISITION_INSPECTION_MISMATCH", failure.failure.detail)
                assertNull(failure.cause)
            }
            assertEquals(1, owner.watches)
            assertTrue(other.requests.isEmpty())
        } finally { owner.close(); other.close() }
    }

    private fun wrap(key: String, value: JsonElement) = buildJsonObject { put(key, value) }
    private fun title(value: String) = wrap("simpleText", JsonPrimitive(value))
    private fun video(id: String = F.VIDEO_B, label: String = F.TITLE, playable: Boolean = true) =
        wrap("playlistVideoRenderer", buildJsonObject {
            put("videoId", id); put("title", title(label)); put("isPlayable", playable)
        })
    private fun token() = wrap("continuationItemRenderer", wrap("continuationEndpoint", buildJsonObject {
        put("commandMetadata", wrap("webCommandMetadata", buildJsonObject { put("apiUrl", "/youtubei/v1/browse") }))
        put("continuationCommand", buildJsonObject { put("token", "authored-central-cursor") })
    }))
    private fun html(value: JsonObject) =
        "<script>ytcfg.set({\"INNERTUBE_CONTEXT\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"2.fixture.version\",\"hl\":\"en\"}},\"INNERTUBE_CONTEXT_CLIENT_NAME\":1,\"INNERTUBE_API_KEY\":\"authored-key\"});</script>" +
            "<script>var ytInitialData=$value;</script>"
    private fun playlistSearch() = html(wrap("contents", JsonArray(listOf(wrap("playlistRenderer", buildJsonObject {
        put("playlistId", F.PLAYLIST); put("title", title("Actual first collection"))
    })))))
    private fun playlistPage(): String {
        val listing = wrap("playlistVideoListRenderer", buildJsonObject {
            put("playlistId", F.PLAYLIST); put("targetId", "central-playlist-target")
            put("contents", JsonArray(listOf(video(), video(label = "[Private video]", playable = false), video(), token())))
        })
        val section = wrap("sectionListRenderer", wrap("contents", JsonArray(listOf(
            wrap("itemSectionRenderer", wrap("contents", JsonArray(listOf(listing))))))))
        val tab = wrap("tabRenderer", buildJsonObject { put("selected", true); put("content", section) })
        return html(buildJsonObject {
            put("contents", wrap("twoColumnBrowseResultsRenderer", wrap("tabs", JsonArray(listOf(tab)))))
            put("metadata", wrap("playlistMetadataRenderer", buildJsonObject { put("title", "Actual complete source playlist") }))
        })
    }
    private fun appendPage() = wrap("onResponseReceivedActions", JsonArray(listOf(
        wrap("appendContinuationItemsAction", buildJsonObject {
            put("targetId", "central-playlist-target")
            put("continuationItems", JsonArray(listOf(video(F.VIDEO_C, "Next actual C"))))
        })
    ))).toString()
}
