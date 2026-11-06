package dev.veyra.source.youtube

import dev.kmpyt.YouTubeClient
import dev.kmpyt.YouTubeDownloadResult
import dev.kmpyt.YouTubeDownloader
import dev.kmpyt.YouTubePlaybackAdapter
import dev.kmpyt.YouTubePlaybackEndpoint
import dev.kmpyt.YouTubePlaybackResource
import dev.kmpyt.extraction.*
import dev.veyra.model.FailureCode
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.model.StreamTransport
import dev.veyra.model.VeyraException
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.ResolvePurpose
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real parser/planner + authored adapters. These tests do not claim native or live playback. */
class YouTubeMediaPurposeTest {
    private val id = "ABCDEFGHIJK"
    private val watchUrl = "https://www.youtube.com/watch?v=$id"
    private val mediaOrigin = "https://media.example"
    private val endpoint = "$mediaOrigin/sabr?sig=authored-secret"
    private val item = MediaItem("youtube:$id", "Authored audio", MediaKind.MUSIC,
        SourceRef(SourceProvider.YOUTUBE, id, watchUrl))
    private val engine = EngineCapabilities()
    private val config = """<script>ytcfg.set({"INNERTUBE_CONTEXT_CLIENT_NAME":1,"INNERTUBE_CONTEXT":{"client":{"clientName":"WEB","clientVersion":"authored"}}});</script>"""

    private fun player(streaming: JsonObject, sabr: Boolean = false) = buildJsonObject {
        putJsonObject("videoDetails") {
            put("videoId", id); put("title", "Authored audio"); put("author", "Authored uploader"); put("lengthSeconds", "3")
            putJsonObject("thumbnail") { putJsonArray("thumbnails") { add(buildJsonObject { put("url", "https://i.ytimg.com/vi/$id/default.jpg") }) } }
        }
        putJsonObject("playabilityStatus") { put("status", "OK") }
        put("streamingData", streaming)
        if (sabr) putJsonObject("playerConfig") { putJsonObject("mediaCommonConfig") {
            putJsonObject("mediaUstreamerRequestConfig") { put("videoPlaybackUstreamerConfig", "AQID") }
        } }
    }.toString()

    private fun sabrPlayer() = player(buildJsonObject {
        put("serverAbrStreamingUrl", endpoint)
        putJsonArray("adaptiveFormats") { add(buildJsonObject {
            put("itag", 140); put("lastModified", "123"); put("contentLength", "32")
            put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\""); put("audioChannels", 2); put("audioSampleRate", "44100")
            putJsonObject("audioTrack") { put("id", "en.0"); put("languageCode", "en"); put("audioIsDefault", true) }
        }) }
    }, sabr = true)

    private fun sabrTransport() = ResearchTransport { request ->
        when (request.stage) {
            "youtube-watch" -> {
                assertEquals("GET", request.method); assertEquals(watchUrl, request.url)
                ResearchResponse(request.url, 200, config + "<script>var ytInitialPlayerResponse=${sabrPlayer()};</script>")
            }
            "youtube-player-client" -> {
                assertEquals("POST", request.method)
                val url = Url(request.url)
                assertEquals("www.youtube.com", url.host); assertEquals("/youtubei/v1/player", url.encodedPath)
                val body = Json.parseToJsonElement(assertNotNull(request.body)).jsonObject
                assertEquals(id, body.getValue("videoId").jsonPrimitive.content)
                assertFalse("serviceIntegrityDimensions" in body)
                // Actual metadata only, never a fabricated progressive URL or media response.
                ResearchResponse(request.url, 200, sabrPlayer())
            }
            else -> error("Unplanned catalogue, source or media request")
        }
    }

    private class Resource(length: Long = 32) : YouTubePlaybackResource {
        override val endpoints = listOf(YouTubePlaybackEndpoint(TrackRole.AUDIO,
            "http://127.0.0.1:32123/${"a".repeat(64)}/audio", "audio/mp4", length))
        var closes = 0
        override suspend fun close() { closes++ }
    }

    @Test fun sabrPlaybackExposesOnlyTypedOwnedLoopbackAndClosesTheResourceExactlyOnce() = runTest {
        val native = Resource()
        var opens = 0
        var selected: SourcePlaybackRequest? = null
        var policy: SourcePlaybackAuthorization? = null
        val source = YouTubeMediaSource(YouTubeClient(sabrTransport(), playbackAdapter = YouTubePlaybackAdapter { request, authorization ->
            opens++; selected = request; policy = authorization
            authorization.requireResource(request, endpoint)
            native
        }))
        val resolved = source.resolve(item, engine, ResolvePurpose.PLAYBACK)
        val owned = assertNotNull(resolved.resource)
        assertEquals(1, opens)
        assertEquals(owned.localEndpoint?.uri, resolved.uri)
        assertEquals(resolved.uri, resolved.ownedLoopbackUriOrNull())
        assertFalse(resolved.isLocal)
        assertEquals(StreamTransport.PROGRESSIVE, resolved.transport)
        assertEquals("audio/mp4", resolved.mimeType)
        assertFalse(resolved.toString().contains("authored-secret"))
        assertFalse(owned.toString().contains("32123"))
        owned.close(); owned.close(); source.close()
        assertEquals(1, native.closes)
        assertEquals("YOUTUBE_SOURCE_CLOSED", assertFailsWith<VeyraException> {
            assertNotNull(policy).requireResource(assertNotNull(selected), endpoint)
        }.failure.detail)
    }

    @Test fun explicitSabrAuthorizationRejectsChangedRequestsOriginAndPrivateDestinations() = runTest {
        val source = YouTubeMediaSource(YouTubeClient(sabrTransport(), playbackAdapter = YouTubePlaybackAdapter { request, policy ->
            assertEquals("YOUTUBE_PLAYBACK_IDENTITY_CHANGED", assertFailsWith<VeyraException> {
                policy.requireResource(request.copy(), endpoint)
            }.failure.detail)
            assertEquals("YOUTUBE_PLAYBACK_ORIGIN_CHANGED", assertFailsWith<VeyraException> {
                policy.requireResource(request, "https://other.example/media")
            }.failure.detail)
            for (url in listOf("http://media.example/media", "https://127.0.0.1/media", "https://media.example:444/media")) {
                assertFailsWith<VeyraException> { policy.requireResource(request, url) }
            }
            policy.requireResource(request, "$mediaOrigin/sabr?sig=authored-actual-request&rn=1")
            Resource()
        }))
        try { source.resolve(item, engine, ResolvePurpose.PLAYBACK).resource?.close() }
        finally { source.close() }
    }

    @Test fun malformedNativeMetadataIsRejectedAndItsUnpublishedResourceIsClosed() = runTest {
        val native = Resource(length = 31)
        val source = YouTubeMediaSource(YouTubeClient(sabrTransport(), playbackAdapter = YouTubePlaybackAdapter { _, _ -> native }))
        try {
            assertFailsWith<VeyraException> { source.resolve(item, engine, ResolvePurpose.PLAYBACK) }
            assertEquals(1, native.closes)
        } finally { source.close() }
        assertEquals(1, native.closes)
    }

    @Test fun sabrDownloadPreparationNeverOpensPlaybackAndPreservesStandaloneOutput() = runTest {
        var transfers = 0
        val source = YouTubeMediaSource(YouTubeClient(sabrTransport(), downloader = YouTubeDownloader { plan, destination, _ ->
            transfers++; assertEquals(id, plan.mediaId); assertEquals(TransportKind.SABR, plan.primary.transport)
            assertEquals(endpoint, plan.primary.url); assertNull(plan.audio)
            YouTubeDownloadResult(destination, 32, "audio/mp4")
        }, playbackAdapter = YouTubePlaybackAdapter { _, _ -> error("DOWNLOAD must not open a native playback resource") }))
        try {
            val prepared = source.resolve(item, engine, ResolvePurpose.DOWNLOAD)
            assertEquals(StreamTransport.SABR, prepared.transport); assertEquals("audio/mp4", prepared.mimeType)
            assertNull(prepared.resource); assertNull(prepared.ownedLoopbackUriOrNull()); assertEquals(0, transfers)
            val result = source.download(item, "authored-stage.m4a", engine, prepared.representationId)
            assertEquals(32L, result.bytesWritten); assertEquals("m4a", result.extension); assertEquals(1, transfers)
        } finally { source.close() }
    }

    @Test fun sabrPlaybackWithoutAnAdapterIsAnExplicitUnsupportedTransportNotADirectMediaUri() = runTest {
        val source = YouTubeMediaSource(YouTubeClient(sabrTransport()))
        try {
            assertEquals(FailureCode.UNSUPPORTED_TRANSPORT, assertFailsWith<VeyraException> {
                source.resolve(item, engine, ResolvePurpose.PLAYBACK)
            }.failure.code)
        } finally { source.close() }
    }

    @Test fun hlsAndDashDownloadPlansExposeMaterializedAudioNotManifestOrInputByteSum() = runTest {
        for (platform in listOf("JVM", "Android", "iOS")) for (kind in listOf(TransportKind.HLS, TransportKind.DASH)) {
            val manifest = "$mediaOrigin/" + if (kind == TransportKind.HLS) "master.m3u8" else "manifest.mpd"
            val streaming = buildJsonObject { put(if (kind == TransportKind.HLS) "hlsManifestUrl" else "dashManifestUrl", manifest) }
            val master = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS="mp4a.40.2"
                audio.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=640x360,CODECS="avc1.4d401f,mp4a.40.2"
                never-selected-video.m3u8
            """.trimIndent()
            val child = "#EXTM3U\n#EXT-X-MAP:URI=\"audio.mp4\",BYTERANGE=\"16@0\"\n#EXTINF:3,\n#EXT-X-BYTERANGE:16@16\naudio.mp4\n#EXT-X-ENDLIST"
            val dash = """<MPD type="static" mediaPresentationDuration="PT3S"><Period><AdaptationSet mimeType="audio/mp4" codecs="mp4a.40.2" lang="en"><Representation id="aac" bandwidth="128000"><BaseURL>audio.mp4</BaseURL><SegmentList timescale="1" duration="3"><Initialization range="0-15"/><SegmentURL mediaRange="16-31"/></SegmentList></Representation></AdaptationSet></Period></MPD>"""
            var transfers = 0
            val requests = mutableListOf<String>()
            val source = YouTubeMediaSource(YouTubeClient(ResearchTransport { request ->
                requests += request.url
                ResearchResponse(request.url, 200, when (request.url) {
                    watchUrl -> "<script>var ytInitialPlayerResponse=${player(streaming)};</script>"
                    manifest -> if (kind == TransportKind.HLS) master else dash
                    "$mediaOrigin/audio.m3u8" -> { assertEquals(TransportKind.HLS, kind); child }
                    else -> error("Unselected video playlist or media bytes must not be fetched by planning")
                })
            }, downloader = YouTubeDownloader { plan, path, _ ->
                transfers++; assertEquals(id, plan.mediaId); assertEquals(kind, plan.primary.transport)
                assertEquals(TrackRole.AUDIO, plan.primary.role); assertNull(plan.audio)
                assertEquals(if (kind == TransportKind.DASH) "en" else null, plan.primary.audioLanguage)
                assertEquals("mp4a.40.2", plan.primary.codecs)
                assertEquals(32L, TransferPlanning.totalBytes(plan))
                assertTrue(plan.primary.segments.isNotEmpty())
                // Actual remux output is not constrained to the acquisition byte sum.
                YouTubeDownloadResult(path, 257, "audio/mp4")
            }, platformName = platform, playbackAdapter = YouTubePlaybackAdapter { _, _ -> error("No playback during DOWNLOAD") }))
            try {
                val prepared = source.resolve(item, engine, ResolvePurpose.DOWNLOAD)
                assertEquals(if (kind == TransportKind.HLS) StreamTransport.HLS else StreamTransport.DASH, prepared.transport)
                assertEquals("audio/mp4", prepared.mimeType); assertEquals("mp4a.40.2", prepared.codecs)
                assertNull(prepared.resource); assertEquals(0, transfers)
                val result = source.download(item, "authored-stage.m4a", engine, prepared.representationId)
                assertEquals(257L, result.bytesWritten); assertEquals("audio/mp4", result.mimeType); assertEquals("m4a", result.extension)
                assertEquals(1, transfers)
                assertEquals(if (kind == TransportKind.HLS) 6 else 4, requests.size)
            } finally { source.close() }
        }
    }

    @Test fun anExternalHlsAudioRenditionWithoutAParsedCodecIsNotAssumedToBeAac() = runTest {
        val manifest = "$mediaOrigin/master.m3u8"
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="sound",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="audio.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=640x360,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="sound"
            video.m3u8
        """.trimIndent()
        val requests = mutableListOf<String>()
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport { request ->
            requests += request.url
            ResearchResponse(request.url, 200, when (request.url) {
                watchUrl -> "<script>var ytInitialPlayerResponse=${player(buildJsonObject { put("hlsManifestUrl", manifest) })};</script>"
                manifest -> master
                else -> error("Unknown-codec renditions must fail before media playlist/download execution")
            })
        }, downloader = YouTubeDownloader { _, _, _ -> error("Never guess AAC from a generic audio/mp4 rendition") }))
        try {
            assertEquals(FailureCode.UNSUPPORTED_CODEC, assertFailsWith<VeyraException> {
                source.resolve(item, engine, ResolvePurpose.DOWNLOAD)
            }.failure.code)
            assertEquals(listOf(watchUrl, manifest), requests)
        } finally { source.close() }
    }
}
