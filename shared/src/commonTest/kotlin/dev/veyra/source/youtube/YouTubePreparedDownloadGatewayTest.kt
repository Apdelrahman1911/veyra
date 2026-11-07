package dev.veyra.source.youtube

import dev.kmpyt.YouTubeClient
import dev.kmpyt.YouTubeDownloadResult
import dev.kmpyt.YouTubeDownloader
import dev.kmpyt.YouTubePlaybackAdapter
import dev.kmpyt.extraction.ResearchRequest
import dev.kmpyt.extraction.ResearchResponse
import dev.kmpyt.extraction.ResearchTransport
import dev.veyra.model.*
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.execution.AcquisitionTransferProgress
import dev.veyra.source.execution.LegacyTransferResult
import dev.veyra.source.execution.PreparedLegacyDownload
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.test.*

/** Authored kmpYT protocol fixtures only; no live source, auth, native decode or audio claim. */
class YouTubePreparedDownloadGatewayTest {
    private val videoId = "ABCDEFGHIJK"
    private val origin = "https://www.youtube.com"
    private val capabilities = EngineCapabilities()

    private fun binding() = PlaybackBinding("execution:authored", "episode:original-guid",
        "Complete publisher episode title", FirstResultKind.VIDEO, videoId, "Selected YouTube title",
        "$origin/watch?v=$videoId", videoId, resolvedEpochMs = 1_000)

    private fun format(signature: String = "first", bitrate: Int = 128_000, expire: Long? = null): JsonObject = buildJsonObject {
        put("itag", 140)
        put("url", "https://media.example/videoplayback?sig=$signature" + (expire?.let { "&expire=$it" } ?: ""))
        put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
        put("contentLength", "32")
        put("bitrate", bitrate)
        put("audioSampleRate", "44100")
        put("audioChannels", 2)
    }

    private fun watch(format: JsonObject) = "<script>var ytInitialPlayerResponse=" + buildJsonObject {
        putJsonObject("videoDetails") {
            put("videoId", videoId); put("title", "Selected YouTube title"); put("lengthSeconds", "3")
            put("author", "Actual uploader")
            putJsonObject("thumbnail") {
                put("thumbnails", buildJsonArray { add(buildJsonObject {
                    put("url", "https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
                }) })
            }
        }
        putJsonObject("playabilityStatus") { put("status", "OK") }
        putJsonObject("streamingData") { put("adaptiveFormats", buildJsonArray { add(format) }) }
    }.toString() + ";</script>"

    private fun transport(requests: MutableList<ResearchRequest>, vararg formats: JsonObject): ResearchTransport {
        var watches = 0
        return ResearchTransport { request ->
            requests += request
            if (request.stage != "youtube-watch") error("Unexpected ${request.stage} request in prepared fixture")
            watches++
            ResearchResponse(request.url, 200, watch(if (watches > 1 && formats.size > 1) formats[1] else formats.first()))
        }
    }

    private fun source(requests: MutableList<ResearchRequest>, transfers: MutableList<String>,
        opens: () -> Unit = {}, vararg formats: JsonObject): YouTubeMediaSource {
        val transport = transport(requests, *formats)
        val client = YouTubeClient(transport, downloader = YouTubeDownloader { plan, destination, _ ->
            transfers += plan.primary.url.orEmpty()
            YouTubeDownloadResult(destination, 32, "audio/mp4")
        }, playbackAdapter = YouTubePlaybackAdapter { _, _ ->
            opens()
            error("Prepared DOWNLOAD must never open a native PlaybackResource")
        })
        return YouTubeMediaSource(client)
    }

    @Test fun preparedDownloadRetainsTheSameClientPlanAndNeverSplitsOrOpensPlayback() = runTest {
        val requests = mutableListOf<ResearchRequest>()
        val transfers = mutableListOf<String>()
        var opens = 0
        val source = source(requests, transfers, { opens++ }, format("first"), format("changed"))
        val gateway = YouTubeKmpMediaGateway(source)
        try {
            val prepared = gateway.prepareDownload(binding(), capabilities, requiredRepresentationId = null)
            assertNull(prepared.requiredRepresentationId, "A genuinely new first key is frozen, not reported as a caller key")
            assertEquals("audio/mp4", prepared.representation.mimeType)
            assertEquals(1, requests.size)
            val result: LegacyTransferResult = gateway.download(prepared, "authored-stage.m4a") { }
            assertEquals(32L, result.bytesWritten)
            assertEquals(prepared.representation.representationId, result.representation.representationId)
            // The actual transfer used the retained initial plan; no second search/re-inspection.
            assertEquals(1, transfers.size)
            assertTrue(transfers.single().contains("sig=first"))
            assertEquals(1, requests.size)
            assertEquals(0, opens, "No DOWNLOAD PlaybackResource/native player is created")
        } finally { source.close() }
    }

    @Test fun forgedPreparedCapabilityIsRejectedBeforeGetterOrTransport() = runTest {
        val requests = mutableListOf<ResearchRequest>()
        val transfers = mutableListOf<String>()
        val source = source(requests, transfers, {}, format("first"))
        val gateway = YouTubeKmpMediaGateway(source)
        var getters = 0
        val forged = object : PreparedLegacyDownload {
            override val binding: PlaybackBinding get() = run { getters++; error("foreign getter must not run") }
            override val requiredRepresentationId: String? get() = run { getters++; error("foreign getter must not run") }
            override val representation: dev.veyra.source.execution.AcquisitionRepresentation
                get() = run { getters++; error("foreign getter must not run") }
        }
        try {
            val failure = assertFailsWith<VeyraException> {
                gateway.download(forged, "authored-stage.m4a") { }
            }
            assertEquals(FailureCode.INVALID_STATE, failure.failure.code)
            assertEquals(0, getters)
            assertTrue(requests.isEmpty())
            assertTrue(transfers.isEmpty())
        } finally { source.close() }
    }

    @Test fun preparedCapabilityIsConsumedOnCancellationAndTerminalReuse() = runTest {
        val requests = mutableListOf<ResearchRequest>()
        val transfers = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        val source = YouTubeMediaSource(YouTubeClient(transport(requests, format("first")),
            downloader = YouTubeDownloader { _, _, _ ->
                started.complete(Unit)
                awaitCancellation()
            }))
        val gateway = YouTubeKmpMediaGateway(source)
        try {
            val prepared = gateway.prepareDownload(binding(), capabilities, null)
            val job = launch { gateway.download(prepared, "authored-stage.m4a") { } }
            started.await()
            job.cancelAndJoin()
            assertFailsWith<VeyraException> { gateway.download(prepared, "authored-stage.m4a") { } }
            // A separately admitted attempt must prepare a fresh capability, not reuse the consumed one.
            val again = gateway.prepareDownload(binding(), capabilities, null)
            assertNotSame(prepared, again)
        } finally { source.close() }
    }

    @Test fun simultaneousTransferConsumesOneCapabilityAndTheOtherFailsWithoutExtraWork() = runBlocking {
        val requests = mutableListOf<ResearchRequest>()
        val transferStarts = AtomicInteger()
        val transfers = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = YouTubeClient(transport(requests, format("first")),
            downloader = YouTubeDownloader { plan, destination, _ ->
                transferStarts.incrementAndGet()
                transfers += plan.primary.url.orEmpty()
                entered.complete(Unit)
                release.await()
                YouTubeDownloadResult(destination, 32, "audio/mp4")
            })
        val source = YouTubeMediaSource(client)
        val gateway = YouTubeKmpMediaGateway(source)
        try {
            val prepared = gateway.prepareDownload(binding(), capabilities, null)
            assertEquals(1, requests.size)
            val outcomes = coroutineScope {
                val first = async { runCatching { gateway.download(prepared, "authored-a.m4a") { } } }
                val second = async { runCatching { gateway.download(prepared, "authored-b.m4a") { } } }
                withTimeout(5_000) { entered.await() }
                withTimeout(2_000) { while (listOf(first, second).none { it.isCompleted }) delay(5) }
                release.complete(Unit)
                awaitAll(first, second)
            }
            assertEquals(1, outcomes.count { it.isSuccess })
            assertEquals(1, outcomes.count { it.isFailure })
            val refused = outcomes.single { it.isFailure }.exceptionOrNull()
            assertIs<VeyraException>(refused)
            assertEquals(AppFailure(FailureCode.INVALID_STATE, "YOUTUBE_PREPARED_DOWNLOAD_CONSUMED"),
                refused.failure)
            assertEquals(1, transferStarts.get(), "Exactly one transfer may consume the capability")
            assertEquals(1, transfers.size)
            assertEquals(1, requests.size, "No extra inspection/source work for the losing caller")
        } finally { source.close() }
    }

    @Test fun genuineCapabilityFromAnotherSourceIsRejectedWithoutConsumingOrUsingForeignWork() = runTest {
        val requestsA = mutableListOf<ResearchRequest>()
        val transfersA = mutableListOf<String>()
        val requestsB = mutableListOf<ResearchRequest>()
        val transfersB = mutableListOf<String>()
        val sourceA = source(requestsA, transfersA, {}, format("first"))
        val sourceB = source(requestsB, transfersB, {}, format("first"))
        val gatewayA = YouTubeKmpMediaGateway(sourceA)
        val gatewayB = YouTubeKmpMediaGateway(sourceB)
        try {
            val preparedA = gatewayA.prepareDownload(binding(), capabilities, null)
            assertEquals(1, requestsA.size)
            val rejected = assertFailsWith<VeyraException> {
                gatewayB.download(preparedA, "authored-stage.m4a") { }
            }
            assertEquals(AppFailure(FailureCode.INVALID_STATE, "YOUTUBE_PREPARED_DOWNLOAD_MISMATCH"), rejected.failure)
            assertTrue(requestsB.isEmpty(), "Foreign rejection must not inspect/search another source")
            assertTrue(transfersB.isEmpty(), "Foreign rejection must not transfer")
            // The foreign issuer's rejection did NOT consume the genuine capability.
            val result = gatewayA.download(preparedA, "authored-stage.m4a") { }
            assertEquals(32L, result.bytesWritten)
            assertEquals(1, transfersA.size)
            assertEquals(1, requestsA.size)
        } finally { sourceA.close(); sourceB.close() }
    }

    @Test fun nullableKeyIsNotAMatchKeyButTheFrozenRepresentationIsTheActualTransferPlan() = runTest {
        val requests = mutableListOf<ResearchRequest>()
        val transfers = mutableListOf<String>()
        val source = source(requests, transfers, {}, format("first"))
        val gateway = YouTubeKmpMediaGateway(source)
        try {
            val prepared = gateway.prepareDownload(binding(), capabilities, null)
            assertNull(prepared.requiredRepresentationId, "A genuinely new first key is frozen, not reported as a caller key")
            val frozen = prepared.representation
            assertTrue(frozen.representationId.startsWith("yt-audio-v1:"))
            assertEquals("audio/mp4", frozen.mimeType)
            val result = gateway.download(prepared, "authored-stage.m4a") { }
            assertEquals(32L, result.bytesWritten)
            assertEquals(prepared.binding, result.binding)
            assertEquals(frozen, result.representation,
                "The actual transfer result identity must equal the exact representation frozen at preparation")
            assertEquals(1, transfers.size)
            assertTrue(transfers.single().contains("sig=first"))
            assertEquals(1, requests.size)
        } finally { source.close() }
    }

    @Test fun explicitMatchingCallerKeyPreparesTheExactRepresentationAndNonmatchingKeyFailsClosed() = runTest {
        val requests = mutableListOf<ResearchRequest>()
        val transfers = mutableListOf<String>()
        val source = source(requests, transfers, {}, format("first"))
        val gateway = YouTubeKmpMediaGateway(source)
        try {
            val initial = gateway.prepareDownload(binding(), capabilities, null)
            val key = initial.representation.representationId
            val matching = gateway.prepareDownload(binding(), capabilities, key)
            assertEquals(key, matching.requiredRepresentationId)
            assertEquals(initial.representation, matching.representation)
            val result = gateway.download(matching, "authored-stage.m4a") { }
            assertEquals(matching.representation, result.representation)
            assertEquals(1, transfers.size)
            assertEquals(2, requests.size)

            val nonmatching = "yt-audio-v1:" + "0".repeat(64)
            val failure = assertFailsWith<VeyraException> {
                gateway.prepareDownload(binding(), capabilities, nonmatching)
            }
            assertEquals(FailureCode.RECOVERY_REQUIRED, failure.failure.code)
            assertEquals("YOUTUBE_SELECTED_TRACK_CHANGED", failure.failure.detail)
            assertEquals(3, requests.size)
            assertEquals(1, transfers.size, "A refused requested key must not transfer")
        } finally { source.close() }
    }

    @OptIn(ExperimentalTime::class)
    @Test fun initiallyExpiredPreparationSpendsTheOneTotalRenewalAndLaterExpiryCannotRenewAgain() = runTest {
        // The pinned parser evaluates the supplied URL expiry against the real inspection clock,
        // while the execution clock is injected. Keep both facts explicit and unambiguous.
        var now = Clock.System.now().toEpochMilliseconds()
        val expired = now / 1_000 - 10
        val stillValid = now / 1_000 + 3_600
        val requests = mutableListOf<ResearchRequest>()
        val transfers = mutableListOf<String>()
        // The first inspection offers only an expired format; preparation's single renewal returns
        // the SAME selected identity with a usable URL (only the expiry changes). That renewal is
        // then unavailable to the queued transfer.
        val source = YouTubeMediaSource(YouTubeClient(transport(requests,
            format("a", bitrate = 128_000, expire = expired),
            format("b", bitrate = 128_000, expire = stillValid)), downloader = YouTubeDownloader { plan, destination, _ ->
            transfers += plan.primary.url.orEmpty()
            YouTubeDownloadResult(destination, 32, "audio/mp4")
        }), nowEpochMs = { now })
        val gateway = YouTubeKmpMediaGateway(source)
        try {
            val prepared = gateway.prepareDownload(binding(), capabilities, null)
            assertEquals(2, requests.size, "Initial expiry spends exactly one inspection renewal")
            assertTrue(prepared.representation.representationId.startsWith("yt-audio-v1:"))
            now = stillValid * 1_000 + 60_000
            val failure = assertFailsWith<VeyraException> {
                gateway.download(prepared, "authored-stage.m4a") { }
            }
            assertEquals(FailureCode.RECOVERY_REQUIRED, failure.failure.code)
            assertEquals("YOUTUBE_SOURCE_EXPIRED", failure.failure.detail)
            assertEquals(2, requests.size, "No second renewal after preparation already spent the budget")
            assertTrue(transfers.isEmpty(), "Expired queued capability must not start a transfer")
            assertFailsWith<VeyraException> { gateway.download(prepared, "authored-stage.m4a") { } }
            assertEquals(2, requests.size)
            assertTrue(transfers.isEmpty())
        } finally { source.close() }
    }

    @OptIn(ExperimentalTime::class)
    @Test fun changedRenewedRepresentationIsRefusedWithAUsableUrlAndNoTransfer() = runTest {
        var now = Clock.System.now().toEpochMilliseconds()
        val usable = now / 1_000 + 3_600
        val requests = mutableListOf<ResearchRequest>()
        val transferAttempts = AtomicInteger()
        val source = YouTubeMediaSource(YouTubeClient(transport(requests,
            format("a", bitrate = 128_000, expire = usable),
            format("b", bitrate = 192_000, expire = usable)), downloader = YouTubeDownloader { _, _, _ ->
            transferAttempts.incrementAndGet()
            throw IllegalStateException("The media link is no longer available. Inspect the video again and retry.")
        }), nowEpochMs = { now })
        val gateway = YouTubeKmpMediaGateway(source)
        try {
            val prepared = gateway.prepareDownload(binding(), capabilities, null)
            assertEquals(1, requests.size)
            val failure = assertFailsWith<VeyraException> {
                gateway.download(prepared, "authored-stage.m4a") { }
            }
            assertEquals(FailureCode.RECOVERY_REQUIRED, failure.failure.code)
            assertEquals("YOUTUBE_SELECTED_TRACK_CHANGED", failure.failure.detail,
                "The renewed URL is usable; refusal is the exact same-key representation mismatch")
            assertEquals(1, transferAttempts.get())
            assertEquals(2, requests.size, "One total renewal lookup, never a second search")
            now = usable * 1_000 + 60_000
            assertFailsWith<VeyraException> { gateway.download(prepared, "authored-stage.m4a") { } }
            assertEquals(2, requests.size)
            assertEquals(1, transferAttempts.get(), "The consumed capability never transfers again")
        } finally { source.close() }
    }
}
