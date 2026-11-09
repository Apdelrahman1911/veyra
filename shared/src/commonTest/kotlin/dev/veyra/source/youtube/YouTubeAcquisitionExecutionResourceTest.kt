package dev.veyra.source.youtube

import dev.kmpyt.*
import dev.kmpyt.extraction.*
import dev.veyra.model.*
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.youtube.AcquisitionExecutionFixtures as F
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real public parser/planner and deliberately authored native adapters; no native/live proof. */
class YouTubeAcquisitionExecutionResourceTest {
    private val engine = EngineCapabilities()

    private class Resource(
        length: Long = 32,
        uri: String = F.LOOPBACK,
        private val failClose: Boolean = false,
    ) : YouTubePlaybackResource {
        override val endpoints = listOf(YouTubePlaybackEndpoint(TrackRole.AUDIO, uri, "audio/mp4", length))
        var closes = 0
        override suspend fun close() {
            closes++
            if (failClose) error("authored-secret native cleanup failure")
        }
    }

    @Test fun typedSabrPlaybackHandsOverAnOwnedRemoteLoopbackWithoutManufacturingACatalogMediaId() = runTest {
        val native = Resource()
        var opens = 0
        var selected: SourcePlaybackRequest? = null
        var policy: SourcePlaybackAuthorization? = null
        val fixture = sabrFixture(playback = YouTubePlaybackAdapter { request, authorization ->
            opens++; selected = request; policy = authorization
            authorization.requireResource(request, F.SABR_ENDPOINT)
            native
        })
        val context = F.context(F.request(AcquisitionKind.SHOW))
        val resolved = fixture.execution.preparePlayback(context, engine)
        val resource = assertNotNull(resolved.resource)
        assertEquals(context, resolved.context)
        assertIs<ExecutableSubject.SourceRecordingSubject>(resolved.context.subject)
        assertEquals(F.LOOPBACK, resolved.uri)
        assertEquals(resource.localEndpoint?.uri, resolved.ownedLoopbackUriOrNull())
        assertEquals(StreamTransport.PROGRESSIVE, resolved.transport)
        assertEquals("audio/mp4", resolved.mimeType)
        assertFalse(resolved.isLocal)
        assertNull(resolved.materializationAssetId)
        assertFalse(resolved.toString().contains("authored-secret"))
        assertFalse(resource.toString().contains("32123"))
        resource.close(); resource.close(); fixture.close()
        assertEquals(1, opens)
        assertEquals(1, native.closes)
        assertEquals("YOUTUBE_SOURCE_CLOSED", assertFailsWith<VeyraException> {
            assertNotNull(policy).requireResource(assertNotNull(selected), F.SABR_ENDPOINT)
        }.failure.detail)
    }

    @Test fun typedPlaybackAuthorizationIsExactRequestAnonymousPublicOriginAndSourceLifetimeBound() = runTest {
        val fixture = sabrFixture(playback = YouTubePlaybackAdapter { request, policy ->
            assertEquals("YOUTUBE_PLAYBACK_IDENTITY_CHANGED", assertFailsWith<VeyraException> {
                policy.requireResource(request.copy(), F.SABR_ENDPOINT)
            }.failure.detail)
            assertEquals("YOUTUBE_PLAYBACK_ORIGIN_CHANGED", assertFailsWith<VeyraException> {
                policy.requireResource(request, "https://other.example/media")
            }.failure.detail)
            for (url in listOf("http://media.example/media", "https://127.0.0.1/media", "https://media.example:444/media")) {
                assertFailsWith<VeyraException> { policy.requireResource(request, url) }
            }
            policy.requireResource(request, "${F.MEDIA_ORIGIN}/sabr?sig=authored-renewed-resource&rn=1")
            Resource()
        })
        try { fixture.execution.preparePlayback(F.context(), engine).resource?.close() }
        finally { fixture.close() }
    }

    @Test fun typedSabrDownloadPreparationOpensNoPlaybackResourceAndReusesTheSamePrivatePlan() = runTest {
        var opens = 0
        var transfers = 0
        val fixture = sabrFixture(playback = YouTubePlaybackAdapter { _, _ -> opens++; error("No playback for DOWNLOAD") },
            downloader = YouTubeDownloader { plan, path, _ ->
                transfers++
                assertEquals(F.VIDEO_C, plan.mediaId)
                assertEquals(TransportKind.SABR, plan.primary.transport)
                assertEquals(F.SABR_ENDPOINT, plan.primary.url)
                assertNull(plan.audio)
                YouTubeDownloadResult(path, 32, "audio/mp4")
            })
        try {
            val prepared = fixture.execution.prepareDownload(F.context(), engine)
            assertEquals("audio/mp4", prepared.representation.mimeType)
            assertEquals("m4a", prepared.representation.extension)
            assertEquals(0, opens)
            assertEquals(0, transfers)
            val requestsBefore = fixture.requests.toList()
            val result = fixture.execution.download(prepared, "authored-stage.m4a")
            assertEquals(prepared.representation, result.representation)
            assertEquals(32L, result.bytesWritten)
            assertEquals(requestsBefore, fixture.requests)
            assertEquals(1, transfers)
            assertEquals(0, opens)
        } finally { fixture.close() }
    }

    @Test fun typedHlsAndDashDownloadsMaterializeTheActualPlanWithoutRefetchingOrOpeningPlayback() = runTest {
        for (platform in listOf("JVM", "Android", "iOS")) for (kind in listOf(TransportKind.HLS, TransportKind.DASH)) {
            val manifest = F.MEDIA_ORIGIN + if (kind == TransportKind.HLS) "/master.m3u8" else "/manifest.mpd"
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
            val fixture = AcquisitionExecutionFixture(handler = { request, _ -> ResearchResponse(request.url, 200, when (request.url) {
                F.WATCH -> "<script>var ytInitialPlayerResponse=${F.player(streaming)};</script>"
                manifest -> if (kind == TransportKind.HLS) master else dash
                "${F.MEDIA_ORIGIN}/audio.m3u8" -> { assertEquals(TransportKind.HLS, kind); child }
                else -> error("An unselected video playlist or media bytes must not be requested")
            }) }, platformName = platform, downloader = YouTubeDownloader { plan, path, _ ->
                transfers++
                assertEquals(F.VIDEO_C, plan.mediaId)
                assertEquals(kind, plan.primary.transport)
                assertEquals(TrackRole.AUDIO, plan.primary.role)
                assertEquals("mp4a.40.2", plan.primary.audioCodec())
                assertEquals(if (kind == TransportKind.DASH) "en" else null, plan.primary.audioLanguage)
                assertEquals(32L, TransferPlanning.totalBytes(plan))
                assertTrue(plan.primary.segments.isNotEmpty())
                assertNull(plan.audio)
                // Remux output length is NOT constrained to the input segment byte sum.
                YouTubeDownloadResult(path, 257, "audio/mp4")
            }, playbackAdapter = YouTubePlaybackAdapter { _, _ -> error("DOWNLOAD must not open a playback bridge") })
            try {
                val prepared = fixture.execution.prepareDownload(F.context(), engine)
                assertEquals(0, transfers)
                val requestsBefore = fixture.requests.toList()
                assertEquals(if (kind == TransportKind.HLS) 3 else 2, requestsBefore.size)
                val result = fixture.execution.download(prepared, "authored-stage.m4a")
                assertEquals(requestsBefore, fixture.requests)
                assertEquals(257L, result.bytesWritten)
                assertEquals("audio/mp4", result.representation.mimeType)
                assertEquals("m4a", result.representation.extension)
                assertEquals(prepared.representation, result.representation)
                assertEquals(1, transfers)
            } finally { fixture.close() }
        }
    }

    @Test fun typedSabrPlaybackWithoutANativeAdapterIsUnsupportedRatherThanAnUnownedDirectUri() = runTest {
        val fixture = sabrFixture()
        try {
            val error = assertFailsWith<VeyraException> { fixture.execution.preparePlayback(F.context(), engine) }
            assertEquals(FailureCode.UNSUPPORTED_TRANSPORT, error.failure.code)
            assertEquals(1, fixture.watches)
        } finally { fixture.close() }
    }

    @Test fun malformedNativeTrackMetadataIsRejectedAndTheUnpublishedResourceIsClosed() = runTest {
        val native = Resource(length = 31)
        val fixture = sabrFixture(playback = YouTubePlaybackAdapter { _, _ -> native })
        try {
            assertFailsWith<VeyraException> { fixture.execution.preparePlayback(F.context(), engine) }
            assertEquals(1, native.closes)
        } finally { fixture.close() }
        assertEquals(1, native.closes)
    }

    @Test fun aNonLoopbackNativeEndpointCannotEscapeTheTypedBoundaryAndIsClosedBeforeFailure() = runTest {
        val native = Resource(uri = "https://unowned.example/audio")
        val fixture = sabrFixture(playback = YouTubePlaybackAdapter { _, _ -> native })
        try {
            assertFailsWith<VeyraException> { fixture.execution.preparePlayback(F.context(), engine) }
            assertEquals(1, native.closes)
        } finally { fixture.close() }
        assertEquals(1, native.closes)
    }

    @Test fun aHandedResourceFailedCloseIsSanitizedStickyAndCannotBecomeASourceCloseAck() = runTest {
        val native = Resource(failClose = true)
        var sourceCloses = 0
        val fixture = sabrFixture(playback = YouTubePlaybackAdapter { _, _ -> native }, onClose = { sourceCloses++ })
        val resolved = fixture.execution.preparePlayback(F.context(), engine)
        val resource = assertNotNull(resolved.resource)
        repeat(2) {
            val failure = assertFailsWith<VeyraException> { resource.close() }
            assertFalse(failure.toString().contains("authored-secret"))
            assertNull(failure.cause)
        }
        // The fake owns no OS resource. A real failed owner must remain pinned by the host.
        repeat(2) { assertFailsWith<IllegalStateException> { fixture.close() } }
        assertEquals(1, native.closes)
        assertEquals(1, sourceCloses)
    }

    @Test fun anUnhandedResourceFailedCleanupRemainsOwnedAfterTypedPublicationFails() = runTest {
        val native = Resource(uri = "https://unowned.example/audio", failClose = true)
        var sourceCloses = 0
        val fixture = sabrFixture(playback = YouTubePlaybackAdapter { _, _ -> native }, onClose = { sourceCloses++ })
        val failure = assertFailsWith<VeyraException> { fixture.execution.preparePlayback(F.context(), engine) }
        assertFalse(failure.toString().contains("authored-secret"))
        assertNull(failure.cause)
        assertEquals(1, native.closes)
        repeat(2) { assertFailsWith<IllegalStateException> { fixture.close() } }
        assertEquals(1, native.closes)
        assertEquals(1, sourceCloses)
    }

    @Test fun cancellationAfterNativeOpenClosesTheUndeliveredResourceBeforePropagating() = runTest {
        val native = Resource()
        var opens = 0
        val fixture = sabrFixture(playback = YouTubePlaybackAdapter { _, _ ->
            opens++
            currentCoroutineContext().cancel(CancellationException("authored post-open cancellation"))
            native
        })
        try {
            assertFailsWith<CancellationException> { fixture.execution.preparePlayback(F.context(), engine) }
            assertEquals(1, opens)
            assertEquals(1, native.closes)
        } finally { fixture.close() }
        assertEquals(1, native.closes)
    }

    @Test fun canceledUndeliveredResourceWithFailedCloseIsStillRetainedAndNeverAcknowledgedReleased() = runTest {
        val native = Resource(failClose = true)
        val fixture = sabrFixture(playback = YouTubePlaybackAdapter { _, _ ->
            currentCoroutineContext().cancel(CancellationException("authored post-open cancellation"))
            native
        })
        assertFailsWith<CancellationException> { fixture.execution.preparePlayback(F.context(), engine) }
        assertEquals(1, native.closes)
        repeat(2) { assertFailsWith<IllegalStateException> { fixture.close() } }
        assertEquals(1, native.closes)
    }

    private fun sabrFixture(
        playback: YouTubePlaybackAdapter? = null,
        downloader: YouTubeDownloader? = null,
        onClose: () -> Unit = {},
    ) = AcquisitionExecutionFixture(handler = { request, _ ->
        when (request.stage) {
            "youtube-watch" -> {
                assertEquals(F.WATCH, request.url)
                ResearchResponse(request.url, 200, F.CONFIG + "<script>var ytInitialPlayerResponse=${F.sabrPlayer()};</script>")
            }
            "youtube-player-client" -> {
                assertEquals("POST", request.method)
                assertEquals("www.youtube.com", Url(request.url).host)
                assertEquals("/youtubei/v1/player", Url(request.url).encodedPath)
                val body = Json.parseToJsonElement(assertNotNull(request.body)).jsonObject
                assertEquals(F.VIDEO_C, body.getValue("videoId").jsonPrimitive.content)
                assertFalse("serviceIntegrityDimensions" in body)
                ResearchResponse(request.url, 200, F.sabrPlayer())
            }
            else -> error("No first-result search, alternate source or media request")
        }
    }, downloader = downloader, playbackAdapter = playback, onClose = onClose)
}
