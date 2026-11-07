package dev.veyra.source.youtube

import dev.kmpyt.YouTubeClient
import dev.kmpyt.extraction.ResearchResponse
import dev.kmpyt.extraction.ResearchTransport
import dev.kmpyt.youtube.YouTubeBrowseEntry
import dev.kmpyt.youtube.YouTubeBrowseKind
import dev.veyra.acquisition.authoredRequest
import dev.veyra.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Authored parser/transport fixtures only; no live source, authentication or native claim. */
class YouTubeAcquisitionGatewayTest {
    private val videoId = "ABCDEFGHIJK"
    private val laterId = "LMNOPQRSTUV"
    private val origin = "https://www.youtube.com"

    private fun request() = authoredRequest("acquisition-page-fixture")

    private fun video(id: String = videoId, title: String = "Actual first video title", available: Boolean = true) =
        buildJsonObject {
            putJsonObject("videoRenderer") {
                put("videoId", id); put("title", title); put("isPlayable", available)
                putJsonObject("lengthText") { put("simpleText", "3:42") }
            }
        }

    private fun browse(vararg entries: JsonElement) = buildJsonObject { put("contents", JsonArray(entries.toList())) }.toString()

    private val blockingNotice = AppFailure(FailureCode.PROVIDER_ACCESS_REQUIRED, "YOUTUBE_ACCESS_REQUIRED")
    private val blockingEntry = YouTubeBrowseEntry(YouTubeBrowseKind.VIDEO, videoId,
        "Actual first video title", "$origin/watch?v=$videoId", ordinal = 0)

    @Test fun validFirstVideoOnABlockingPageIsUnplayableAndKeepsTheTruthfulRawReceipt() {
        val request = request()
        val result = youtubeAcquisitionResult(request, blockingEntry, blockingNotice)
        val blocked = assertIs<SourceAcquisitionResult.Unplayable>(result)
        assertEquals(request.id, blocked.requestId)
        assertEquals(AcquisitionProblemCode.FIRST_RESULT_UNAVAILABLE, blocked.problem.code)
        val receipt = assertNotNull(blocked.receipt)
        assertEquals(request.id, receipt.requestId)
        assertEquals(request.completeTitle, receipt.queryTitle)
        assertEquals(0, receipt.entry.ordinal)
        assertEquals(AcquisitionEntryKind.VIDEO, receipt.entry.kind)
        assertEquals(videoId, receipt.entry.id)
        assertEquals("Actual first video title", receipt.entry.title)
        assertEquals("$origin/watch?v=$videoId", receipt.canonicalUrl)
        assertNull(receipt.entry.issue, "The raw entry itself is valid; the PAGE refusal is authoritative")
    }

    @Test fun aNonBlockingPageWarningDoesNotDestroyAValidSupportedSelection() = runTest {
        var requests = 0
        // A valid leading video plus a later unavailable card produces the library's non-blocking
        // "Unavailable entries were omitted" page notice. It must not reject the valid selection.
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            requests++
            ResearchResponse(it.url, 200, browse(video(),
                video(id = laterId, title = "[Private video]", available = false)))
        }))
        try {
            val request = request()
            val result = source.acquisitionGateway.acquire(request)
            val single = assertIs<SourceAcquisitionResult.Single>(result)
            assertEquals(1, requests)
            assertEquals(videoId, single.recording.id)
            assertEquals(0, single.receipt.entry.ordinal)
            assertEquals("$origin/watch?v=$videoId", single.receipt.canonicalUrl)
        } finally { source.close() }
    }

    @Test fun aBlockingPageTransportRefusalNeverBecomesASingleOrASecondQuery() = runTest {
        var requests = 0
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            requests++
            ResearchResponse(it.url, 403, "private response must not escape")
        }))
        try {
            val failure = assertFailsWith<VeyraException> { source.acquisitionGateway.acquire(request()) }
            assertEquals(FailureCode.PROVIDER_ACCESS_REQUIRED, failure.failure.code)
            assertEquals(1, requests)
            assertNull(failure.cause)
        } finally { source.close() }
    }

    @Test fun blockedReceiptKeepsRawIdentityButTheProductionStructuralRestoreIsNeverReachedForIt() {
        val request = request()
        val result = assertIs<SourceAcquisitionResult.Unplayable>(youtubeAcquisitionResult(request, blockingEntry, blockingNotice))
        val receipt = assertNotNull(result.receipt)
        assertEquals(videoId, receipt.entry.id)
        assertEquals(AcquisitionProblemCode.FIRST_RESULT_UNAVAILABLE, result.problem.code)
        // The acquisition coordinator commits this as AcquisitionOutcome.Unplayable with no
        // binding; only a committed Single outcome is ever passed to gateway.restore() by
        // boundOccurrence. The coordinator regression proves that reopen cannot turn it into an
        // executable occurrence (AcquisitionCoordinatorTest).
    }
}
