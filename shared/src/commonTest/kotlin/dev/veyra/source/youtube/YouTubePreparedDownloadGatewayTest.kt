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

    @OptIn(ExperimentalTime::class)
    @Test fun queuedExpiredPreparationSpendsAtMostOneTotalRenewalAndRefusesAChangedKey() = runTest {
        var now = 1_000L
        val realExpiry = kotlin.time.Clock.System.now().toEpochMilliseconds() / 1_000 + 3_600
        val requests = mutableListOf<ResearchRequest>()
        // First inspection selects format A; the one renewal returns a different representation.
        val source = YouTubeMediaSource(YouTubeClient(transport(requests,
            format("a", bitrate = 128_000, expire = realExpiry), format("b", bitrate = 192_000, expire = realExpiry))),
            nowEpochMs = { now })
        val gateway = YouTubeKmpMediaGateway(source)
        try {
            val prepared = gateway.prepareDownload(binding(), capabilities, null)
            now = realExpiry * 1_000 + 60_000
            val failure = assertFailsWith<VeyraException> {
                gateway.download(prepared, "authored-stage.m4a") { }
            }
            assertEquals(FailureCode.RECOVERY_REQUIRED, failure.failure.code)
            // Once preparation+transfer consumed the one budget, the capability is terminal.
            assertFailsWith<VeyraException> { gateway.download(prepared, "authored-stage.m4a") { } }
        } finally { source.close() }
    }

    @Test fun nullableInitialKeyIsNotPassedAsARepresentationMatchAfterSelection() = runTest {
        val requests = mutableListOf<ResearchRequest>()
        val transfers = mutableListOf<String>()
        val source = source(requests, transfers, {}, format("first"))
        val gateway = YouTubeKmpMediaGateway(source)
        try {
            val prepared = gateway.prepareDownload(binding(), capabilities, null)
            assertNull(prepared.requiredRepresentationId)
            assertTrue(prepared.representation.representationId.startsWith("yt-audio-v1:"))
            gateway.download(prepared, "authored-stage.m4a") { }
            assertEquals(prepared.representation.representationId, prepared.representation.representationId)
            assertEquals(1, transfers.size)
        } finally { source.close() }
    }
}
