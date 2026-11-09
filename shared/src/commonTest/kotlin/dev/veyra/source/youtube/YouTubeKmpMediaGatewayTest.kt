package dev.veyra.source.youtube

import dev.kmpyt.YouTubeClient
import dev.kmpyt.YouTubeDownloadResult
import dev.kmpyt.YouTubeDownloader
import dev.kmpyt.extraction.ResearchRequest
import dev.kmpyt.extraction.ResearchResponse
import dev.kmpyt.extraction.ResearchTransport
import dev.veyra.model.*
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.ResolvePurpose
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Actual upstream parser/client with authored responses; no live-source or native decode claims. */
class YouTubeKmpMediaGatewayTest {
    private val firstId = "ABCDEFGHIJK"
    private val secondId = "LMNOPQRSTUV"
    private val channelId = "UCabcdefghijklmnopqrstuv"
    private val playlistId = "PLabcdefghijklmnopqrstuv"
    private val origin = "https://www.youtube.com"
    private val capabilities = EngineCapabilities()
    private fun binding() = PlaybackBinding("execution:authored", "episode:original-guid", "Complete publisher episode title",
        FirstResultKind.VIDEO, firstId, "Selected YouTube title", "$origin/watch?v=$firstId", firstId)

    private fun video(title: String = "Selected YouTube title", id: String = firstId, available: Boolean = true) = buildJsonObject {
        putJsonObject("videoRenderer") { put("videoId", id); put("title", title); put("isPlayable", available) }
    }

    private fun browse(vararg entries: JsonElement) = buildJsonObject { put("contents", JsonArray(entries.toList())) }.toString()
    private fun watch(status: String = "OK") = "<script>var ytInitialPlayerResponse=" + buildJsonObject {
        putJsonObject("videoDetails") { put("videoId", firstId); put("title", "Selected YouTube title"); put("lengthSeconds", "3") }
        putJsonObject("playabilityStatus") { put("status", status) }
        putJsonObject("streamingData") { put("adaptiveFormats", buildJsonArray { add(buildJsonObject {
            put("itag", 140); put("url", "https://media.example/videoplayback?sig=authored-secret")
            put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\""); put("contentLength", "32")
            put("bitrate", 128_000); put("audioSampleRate", "44100"); put("audioChannels", 2)
        }) }) }
    }.toString() + ";</script>"

    /** Missing watch metadata may invoke oEmbed, but every request must bind the same video. */
    private fun boundInspectionResponse(request: ResearchRequest): ResearchResponse {
        assertEquals("GET", request.method)
        val body = when (request.stage) {
            "youtube-watch" -> {
                assertEquals("$origin/watch?v=$firstId", request.url)
                watch()
            }
            "youtube-oembed" -> {
                val url = Url(request.url)
                assertEquals("$origin/oembed", request.url.substringBefore('?'))
                assertEquals(setOf("url", "format"), url.parameters.names())
                assertEquals(listOf("$origin/watch?v=$firstId"), url.parameters.getAll("url"))
                assertEquals(listOf("json"), url.parameters.getAll("format"))
                buildJsonObject {
                    put("type", "video"); put("title", "Selected YouTube title")
                    put("author_name", "Actual uploader")
                    put("thumbnail_url", "https://i.ytimg.com/vi/$firstId/hqdefault.jpg")
                }.toString()
            }
            else -> error("Bound execution must not search or inspect another source")
        }
        return ResearchResponse(request.url, 200, body)
    }

    @Test fun fullPublisherTitleIsSubmittedVerbatimToRealKmpytAndFirstVideoIsReturned() = runTest {
        val complete = " " + "عنوان الحلقة🎵 ".repeat(600) + " final words "
        val requests = mutableListOf<ResearchRequest>()
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            requests += it; ResearchResponse(it.url, 200, browse(video(), video(id = secondId)))
        }))
        try {
            val first = assertNotNull(YouTubeKmpMediaGateway(source).firstResult(complete))
            assertEquals(complete, first.queryTitle)
            assertEquals(complete, Url(requests.single().url).parameters["search_query"])
            assertEquals(firstId, first.id)
            assertEquals(FirstResultKind.VIDEO, first.kind)
            assertEquals("$origin/watch?v=$firstId", first.canonicalUrl)
            assertEquals(0, first.selectionOrdinal)
            assertNull(first.failure)
        } finally { source.close() }
    }

    @Test fun channelAndAlbumFirstResultsKeepCollectionIdentityAndNeverExecuteChildOrLaterVideo() = runTest {
        val channel = buildJsonObject { putJsonObject("channelRenderer") {
            put("channelId", channelId); put("title", "Official channel")
            putJsonObject("navigationEndpoint") { putJsonObject("browseEndpoint") { put("canonicalBaseUrl", "/@original_handle/videos") } }
        } }
        val album = buildJsonObject { putJsonObject("lockupViewModel") {
            put("contentId", playlistId); put("contentType", "LOCKUP_CONTENT_TYPE_PLAYLIST")
            putJsonObject("metadata") { putJsonObject("lockupMetadataViewModel") { put("title", "Actual album playlist") } }
        } }
        for ((card, kind, id) in listOf(Triple(channel, FirstResultKind.CHANNEL, channelId), Triple(album, FirstResultKind.PLAYLIST, playlistId))) {
            var requests = 0
            val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
                requests++; ResearchResponse(it.url, 200, browse(card, video()))
            }))
            try {
                val gateway = YouTubeKmpMediaGateway(source)
                val first = assertNotNull(gateway.firstResult("Complete collection title"))
                assertEquals(kind, first.kind); assertEquals(id, first.id)
                assertEquals(if (kind == FirstResultKind.CHANNEL) "$origin/channel/$id" else "$origin/playlist?list=$id", first.canonicalUrl)
                val receipt = binding().copy(firstResultKind = kind, firstResultId = id, selectedVideoId = null, firstResultUrl = first.canonicalUrl)
                assertEquals(FailureCode.UNSUPPORTED_SOURCE, assertFailsWith<VeyraException> {
                    gateway.resolve(receipt, capabilities, ResolvePurpose.PLAYBACK)
                }.failure.code)
                assertFailsWith<VeyraException> { gateway.download(receipt, "authored-stage", capabilities) }
                assertEquals(1, requests)
            } finally { source.close() }
        }
    }

    @Test fun unavailableFirstAndMalformedFirstAreReceiptsNotEmptyOrLaterResultFallback() = runTest {
        for ((card, expectedKind, failure) in listOf(
            Triple(video("[Private video]", available = false), FirstResultKind.VIDEO, FailureCode.UNSUPPORTED_SOURCE),
            Triple(video("x".repeat(16_385)), FirstResultKind.UNKNOWN, FailureCode.PARSE_FAILURE),
        )) {
            val source = YouTubeMediaSource(YouTubeClient(ResearchTransport { ResearchResponse(it.url, 200, browse(card, video(id = secondId))) }))
            try {
                val first = assertNotNull(YouTubeKmpMediaGateway(source).firstResult("Original complete title"))
                assertEquals(firstId, first.id)
                assertEquals(expectedKind, first.kind)
                assertEquals(failure, first.failure?.code)
                if (expectedKind == FirstResultKind.UNKNOWN) {
                    assertEquals("", first.title); assertNull(first.canonicalUrl)
                }
            } finally { source.close() }
        }
    }

    @Test fun unknownRawFirstCardCannotChooseTheSupportedVideoBehindIt() = runTest {
        val unknown = buildJsonObject { put("futureMediaRenderer", buildJsonObject { put("title", "Unknown actual first") }) }
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport { ResearchResponse(it.url, 200, browse(unknown, video())) }))
        try {
            val first = assertNotNull(YouTubeKmpMediaGateway(source).firstResult("Title"))
            assertEquals(FirstResultKind.UNKNOWN, first.kind); assertEquals("", first.id)
            assertNull(first.canonicalUrl); assertEquals(FailureCode.UNSUPPORTED_SOURCE, first.failure?.code)
        } finally { source.close() }
    }

    @Test fun emptySearchIsDistinctFromAuthorizationOrMalformedResponse() = runTest {
        val empty = YouTubeMediaSource(YouTubeClient(ResearchTransport { ResearchResponse(it.url, 200, browse()) }))
        try { assertNull(YouTubeKmpMediaGateway(empty).firstResult("Title")) } finally { empty.close() }
        val denied = YouTubeMediaSource(YouTubeClient(ResearchTransport { ResearchResponse(it.url, 403, "authored secret response") }))
        try {
            val error = assertFailsWith<VeyraException> { YouTubeKmpMediaGateway(denied).firstResult("Title") }
            assertEquals(FailureCode.PROVIDER_ACCESS_REQUIRED, error.failure.code)
            assertNull(error.cause); assertFalse(error.toString().contains("secret"))
        } finally { denied.close() }
        val malformed = YouTubeMediaSource(YouTubeClient(ResearchTransport { ResearchResponse(it.url, 200, "not readable JSON or initial HTML data") }))
        try { assertEquals(FailureCode.PARSE_FAILURE, assertFailsWith<VeyraException> { YouTubeKmpMediaGateway(malformed).firstResult("Title") }.failure.code) }
        finally { malformed.close() }
    }

    @Test fun originalEpisodeContextExecutesTheBoundNativeVideoWithoutAnotherSearch() = runTest {
        val requests = mutableListOf<ResearchRequest>()
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            requests += it; boundInspectionResponse(it)
        }))
        try {
            val selected = binding()
            val playable = YouTubeKmpMediaGateway(source).resolve(selected, capabilities, ResolvePurpose.PLAYBACK)
            assertEquals("episode:original-guid", playable.mediaId)
            assertEquals(selected, playable.executionBinding)
            assertEquals("youtube:$firstId", playable.item?.id)
            assertEquals(firstId, playable.item?.source?.canonicalId)
            assertEquals(SourceProvider.YOUTUBE, playable.item?.source?.provider)
            assertEquals(listOf("youtube-watch", "youtube-oembed"), requests.map { it.stage })
        } finally { source.close() }
    }

    @Test fun downloadUsesOnlyTheImmutableBoundVideoAndActualKmpytPlan() = runTest {
        val requests = mutableListOf<ResearchRequest>()
        var transfers = 0
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            requests += it; boundInspectionResponse(it)
        }, downloader = YouTubeDownloader { plan, destination, _ ->
            transfers++; assertEquals(firstId, plan.mediaId); assertEquals("$origin/watch?v=$firstId", plan.inspectionUrl)
            assertEquals("authored-stage.m4a", destination)
            YouTubeDownloadResult(destination, 32, "audio/mp4")
        }))
        try {
            val result = YouTubeKmpMediaGateway(source).download(binding(), "authored-stage.m4a", capabilities)
            assertEquals(32L, result.bytesWritten); assertEquals("m4a", result.extension)
            assertEquals(listOf("youtube-watch", "youtube-oembed"), requests.map { it.stage })
            assertEquals(1, transfers)
            assertFalse(result.toString().contains("authored-secret"))
        } finally { source.close() }
    }

    @Test fun inconsistentBindingOrOversizedQueryFailsBeforeAnyTransport() = runTest {
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport { error("Invalid binding must not reach network") }))
        val gateway = YouTubeKmpMediaGateway(source)
        try {
            for (invalid in listOf(binding().copy(selectedVideoId = secondId), binding().copy(selectionOrdinal = 1),
                binding().copy(firstResultUrl = "$origin/watch?v=$firstId&sig=private"), binding().copy(queryTitle = ""))) {
                assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> {
                    gateway.resolve(invalid, capabilities, ResolvePurpose.PLAYBACK)
                }.failure.code)
            }
            assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> { gateway.firstResult("x".repeat(16_385)) }.failure.code)
        } finally { source.close() }
    }
}
