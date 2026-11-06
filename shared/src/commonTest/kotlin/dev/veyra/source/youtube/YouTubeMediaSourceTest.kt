package dev.veyra.source.youtube

import dev.kmpyt.YouTubeClient
import dev.kmpyt.YouTubeDownloadProgress
import dev.kmpyt.YouTubeDownloadResult
import dev.kmpyt.YouTubeDownloader
import dev.kmpyt.extraction.ResearchResponse
import dev.kmpyt.extraction.ResearchTransport
import dev.veyra.model.FailureCode
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.model.VeyraException
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.BrowseKind
import dev.veyra.source.BrowseRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Authored protocol-shape tests using the actual kmpYT parser; these are not live-source evidence. */
class YouTubeMediaSourceTest {
    private val videoId = "ABCDEFGHIJK"
    private val secondId = "LMNOPQRSTUV"
    private val channelId = "UCabcdefghijklmnopqrstuv"
    private val playlistId = "PLabcdefghijklmnopqrstuv"
    private val item = MediaItem("youtube:$videoId", "Authored audio", MediaKind.MUSIC,
        SourceRef(SourceProvider.YOUTUBE, videoId, "https://www.youtube.com/watch?v=$videoId"))

    private fun format(
        itag: Int = 140,
        mime: String = "audio/mp4",
        codec: String = "mp4a.40.2",
        signature: String = "authored-first",
        expire: Long? = null,
        language: String? = null,
    ): JsonObject = buildJsonObject {
        put("itag", itag)
        put("url", "https://media.example/videoplayback?id=authored&sig=$signature" + (expire?.let { "&expire=$it" } ?: ""))
        put("mimeType", "$mime; codecs=\"$codec\"")
        put("contentLength", "32")
        put("bitrate", 128_000)
        put("audioChannels", 2)
        put("audioSampleRate", "44100")
        if (language != null) putJsonObject("audioTrack") { put("id", "$language.0"); put("languageCode", language); put("audioIsDefault", true) }
    }

    private fun watch(
        formats: List<JsonObject> = listOf(format()),
        status: String = "OK",
        id: String = videoId,
    ): String = "<script>var ytInitialPlayerResponse=" + buildJsonObject {
        putJsonObject("videoDetails") {
            put("videoId", id); put("title", "Authored audio"); put("author", "Actual uploader"); put("lengthSeconds", "3")
            putJsonObject("thumbnail") {
                put("thumbnails", buildJsonArray { add(buildJsonObject { put("url", "https://i.ytimg.com/vi/$id/hqdefault.jpg") }) })
            }
        }
        putJsonObject("playabilityStatus") { put("status", status) }
        putJsonObject("streamingData") { put("adaptiveFormats", buildJsonArray { formats.forEach { add(it) } }) }
    }.toString() + ";</script>"

    private fun video(id: String = videoId) = """{"videoRenderer":{"videoId":"$id","title":{"simpleText":"Authored title"},"ownerText":{"simpleText":"Actual uploader"},"lengthText":{"simpleText":"3:42"},"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/$id/hqdefault.jpg"}]}}}"""
    private fun initial(contents: String) = """<script>var ytInitialData={"contents":[$contents]};</script>"""
    private fun token(value: String = "authored-continuation") = """{"continuationItemRenderer":{"continuationEndpoint":{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/browse"}},"continuationCommand":{"token":"$value"}}}}"""
    private fun continuation(contents: String) = """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[$contents]}}]}"""
    private val config = """<script>ytcfg.set({"INNERTUBE_CONTEXT":{"client":{"clientName":"WEB","clientVersion":"2.authored"}},"INNERTUBE_CONTEXT_CLIENT_NAME":1,"INNERTUBE_API_KEY":"authored-key","INNERTUBE_CLIENT_VERSION":"2.authored"});</script>"""

    @Test fun searchMapsCanonicalIdentityAndDoesNotInventMusicalArtistSemantics() = runTest {
        val channel = """{"channelRenderer":{"channelId":"$channelId","title":{"simpleText":"Authored channel"}}}"""
        val playlist = """{"playlistRenderer":{"playlistId":"$playlistId","title":{"simpleText":"Authored playlist"}}}"""
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            assertEquals("youtube-browse", it.stage)
            ResearchResponse(it.url, 200, initial("${video()},$channel,$playlist"))
        }))
        try {
            val page = source.search("authored")
            val result = page.items.single()
            assertEquals("youtube:$videoId", result.id)
            assertEquals(item.source, result.source)
            assertEquals(222_000L, result.durationMs)
            assertEquals("Actual uploader", result.creator)
            assertNull(result.artist)
            assertNull(result.album)
            assertEquals(listOf(BrowseKind.CHANNEL, BrowseKind.PLAYLIST), page.collections.map { it.kind })
            assertEquals("YouTube", page.attribution)
        } finally { source.close() }
    }

    @Test fun playlistPaginationReturnsNewOccurrencesWithoutDeduplicatingRepeats() = runTest {
        var posts = 0
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            ResearchResponse(it.url, 200, if (it.method == "GET") config + initial("${video()},${video()},${token()}")
            else { posts++; continuation("${video()},${video(secondId)}") })
        }))
        try {
            val first = source.browse(BrowseRequest(BrowseKind.PLAYLIST, playlistId))
            assertEquals(listOf(item.id, item.id), first.items.map { it.id })
            val cursor = assertNotNull(first.nextPageToken)
            assertFalse(cursor.contains("authored-continuation"))
            val next = source.browse(BrowseRequest(BrowseKind.PLAYLIST, playlistId, cursor))
            assertEquals(listOf(item.id, "youtube:$secondId"), next.items.map { it.id })
            assertNull(next.nextPageToken)
            assertEquals(1, posts)
            assertEquals("YOUTUBE_CURSOR_EXPIRED", assertFailsWith<VeyraException> {
                source.browse(BrowseRequest(BrowseKind.PLAYLIST, playlistId, cursor))
            }.failure.detail)
        } finally { source.close() }
    }

    @Test fun cursorsAreQueryBoundAndExpireWithoutFurtherNetworkRequests() = runTest {
        var time = 1_000L
        var requests = 0
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            requests++
            ResearchResponse(it.url, 200, config + initial("${video()},${token()}"))
        }), nowEpochMs = { time })
        try {
            val cursor = assertNotNull(source.search("first").nextPageToken)
            assertFailsWith<VeyraException> { source.search("another", cursor) }
            time += 10 * 60_000
            assertFailsWith<VeyraException> { source.search("first", cursor) }
            assertEquals(1, requests)
        } finally { source.close() }
    }

    @Test fun failedContinuationRetainsRetryCursorAndDoesNotRepeatExistingRows() = runTest {
        var posts = 0
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            if (it.method == "GET") ResearchResponse(it.url, 200, config + initial("${video()},${token()}"))
            else if (++posts == 1) ResearchResponse(it.url, 429, "private response must not escape")
            else ResearchResponse(it.url, 200, continuation(video(secondId)))
        }))
        try {
            val first = source.browse(BrowseRequest(BrowseKind.CHANNEL, channelId))
            val cursor = assertNotNull(first.nextPageToken)
            val denied = source.browse(BrowseRequest(BrowseKind.CHANNEL, channelId, cursor))
            assertTrue(denied.items.isEmpty())
            assertEquals(FailureCode.RATE_LIMITED, denied.notice?.code)
            assertEquals(cursor, denied.nextPageToken)
            val next = source.browse(BrowseRequest(BrowseKind.CHANNEL, channelId, cursor))
            assertEquals("youtube:$secondId", next.items.single().id)
        } finally { source.close() }
    }

    @Test fun emptyHomeIsNotMisreportedAsAuthorizationRefusal() = runTest {
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport { ResearchResponse(it.url, 200, initial("")) }))
        try {
            val page = source.browse()
            assertTrue(page.items.isEmpty())
            assertEquals("YOUTUBE_NO_PUBLIC_RECOMMENDATIONS", page.notice?.detail)
            assertEquals(FailureCode.NOT_FOUND, page.notice?.code)
        } finally { source.close() }
    }

    @Test fun initialBrowseAuthorizationAndNetworkFailuresAreTypedAndRedacted() = runTest {
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            ResearchResponse(it.url, 403, "signed-media=https://media.example?secret=never-log")
        }))
        try {
            val error = assertFailsWith<VeyraException> { source.search("authored") }
            assertEquals(FailureCode.PROVIDER_ACCESS_REQUIRED, error.failure.code)
            assertFalse(error.toString().contains("secret"))
            assertNull(error.cause)
        } finally { source.close() }
    }

    @Test fun loginRequiredStopsResolutionWithoutAlternateRequestsOrFakeSources() = runTest {
        var watches = 0
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            assertEquals("youtube-watch", it.stage)
            watches++
            ResearchResponse(it.url, 200, watch(emptyList(), "LOGIN_REQUIRED"))
        }))
        try {
            assertEquals("Actual uploader", source.details(item.source).creator)
            val error = assertFailsWith<VeyraException> { source.resolve(item, EngineCapabilities()) }
            assertEquals(FailureCode.PROVIDER_ACCESS_REQUIRED, error.failure.code)
            assertEquals(2, watches) // One explicit details request, then one resolve request.
        } finally { source.close() }
    }

    @Test fun realParserResolvesOfferedAudioAndNeverPersistsSignedUrls() = runTest {
        var requests = 0
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            assertEquals("youtube-watch", it.stage)
            requests++
            ResearchResponse(it.url, 200, watch(listOf(format(itag = 251, mime = "audio/webm", codec = "opus"), format(itag = 141))))
        }))
        try {
            val resolved = source.resolve(item, EngineCapabilities(mimeTypes = setOf("audio/mp4", "audio/webm"), codecs = setOf("aac", "opus")))
            assertEquals("audio/mp4", resolved.mimeType)
            assertEquals("mp4a.40.2", resolved.codecs)
            assertTrue(resolved.uri.contains("authored-first"))
            assertNull(resolved.expiresAtEpochMs)
            assertTrue(assertNotNull(resolved.representationId).startsWith("yt-audio-v1:"))
            assertFalse(resolved.toString().contains("authored-first"))
            val stored = Json.encodeToString(MediaItem.serializer(), assertNotNull(resolved.item))
            assertFalse(stored.contains("sig="))
            assertNull(resolved.item?.artist)
            assertEquals(1, requests)
        } finally { source.close() }
    }

    @Test fun rejectedVideoIdentityDoesNotLeakDifferentMetadataOrRequestMedia() = runTest {
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            ResearchResponse(it.url, if (it.stage == "youtube-oembed") 404 else 200, watch(id = secondId))
        }))
        try {
            assertEquals("YOUTUBE_IDENTITY_MISMATCH", assertFailsWith<VeyraException> {
                source.resolve(item, EngineCapabilities())
            }.failure.detail)
        } finally { source.close() }
    }

    @Test fun expiredSourceRefreshesOnceAndPreservesTheExactTrack() = runTest {
        var watches = 0
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            ResearchResponse(it.url, 200, watch(listOf(if (++watches == 1) format(expire = 1) else format(signature = "authored-refreshed"))))
        }))
        try {
            assertTrue(source.resolve(item, EngineCapabilities()).uri.contains("authored-refreshed"))
            assertEquals(2, watches)
        } finally { source.close() }
    }

    @Test fun expiredSourceNeverSubstitutesAnotherLanguageOnRefresh() = runTest {
        var watches = 0
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            ResearchResponse(it.url, 200, watch(listOf(if (++watches == 1) format(expire = 1, language = "en") else format(language = "ar"))))
        }))
        try {
            assertEquals("YOUTUBE_SELECTED_TRACK_CHANGED", assertFailsWith<VeyraException> {
                source.resolve(item, EngineCapabilities())
            }.failure.detail)
            assertEquals(2, watches)
        } finally { source.close() }
    }

    @Test fun repeatedExpiredSourceIsBoundedToTwoInspections() = runTest {
        var watches = 0
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            watches++
            ResearchResponse(it.url, 200, watch(listOf(format(expire = 1))))
        }))
        try {
            assertEquals("YOUTUBE_SOURCE_EXPIRED", assertFailsWith<VeyraException> {
                source.resolve(item, EngineCapabilities())
            }.failure.detail)
            assertEquals(2, watches)
        } finally { source.close() }
    }

    // Simulates the pinned JVM/Android adapter's private typed error; it never performs a live transfer.
    private class ExpiredResourceException : Exception("Authored rejected-link outcome")

    @Test fun downloadDelegatesToNativeClientAndReportsActualWebmExtensionWithBoundedRefresh() = runTest {
        var watches = 0
        var transfers = 0
        val progress = mutableListOf<YouTubeTransferProgress>()
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            watches++
            ResearchResponse(it.url, 200, watch(listOf(format(itag = 251, mime = "audio/webm", codec = "opus", signature = "authored-$watches"))))
        }, downloader = YouTubeDownloader { plan, path, report ->
            assertEquals("251", plan.primary.id)
            assertNull(plan.audio)
            if (++transfers == 1) throw ExpiredResourceException()
            report(YouTubeDownloadProgress(32, 32))
            YouTubeDownloadResult(path, 32, "audio/webm")
        }))
        try {
            val capabilities = EngineCapabilities(mimeTypes = setOf("audio/webm"), codecs = setOf("opus"))
            val selected = source.resolve(item, capabilities)
            val result = source.download(item, "authored-staging.webm", capabilities, selected.representationId) { progress += it }
            assertEquals(3, watches) // Explicit resolve, download inspection, one rejected-link refresh.
            assertEquals(2, transfers)
            assertEquals("webm", result.extension)
            assertEquals("audio/webm", result.mimeType)
            assertEquals(selected.representationId, result.representationId)
            assertEquals(YouTubeTransferProgress(32, 32), progress.single())
        } finally { source.close() }
    }

    @Test fun rejectedDownloadRechecksAuthorizationAndNeverAttemptsAnotherTransferAfterRefusal() = runTest {
        var watches = 0
        var transfers = 0
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            ResearchResponse(it.url, 200, if (++watches == 1) watch() else watch(emptyList(), "LOGIN_REQUIRED"))
        }, downloader = YouTubeDownloader { _, _, _ -> transfers++; throw ExpiredResourceException() }))
        try {
            assertEquals(FailureCode.PROVIDER_ACCESS_REQUIRED, assertFailsWith<VeyraException> {
                source.download(item, "authored-staging.m4a")
            }.failure.code)
            assertEquals(2, watches)
            assertEquals(1, transfers)
        } finally { source.close() }
    }

    @Test fun downloadHasOnlyOneTotalExpiryRefreshBudget() = runTest {
        var watches = 0
        var transfers = 0
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            ResearchResponse(it.url, 200, watch(listOf(if (++watches == 1) format(expire = 1) else format())))
        }, downloader = YouTubeDownloader { _, _, _ -> transfers++; throw ExpiredResourceException() }))
        try {
            assertEquals("YOUTUBE_SOURCE_EXPIRED", assertFailsWith<VeyraException> {
                source.download(item, "authored-staging.m4a")
            }.failure.detail)
            assertEquals(2, watches)
            assertEquals(1, transfers)
        } finally { source.close() }
    }

    @Test fun cancellationPropagatesWithoutRefreshIncludingCoroutineStacktraceRecovery() = runTest {
        val cancellation = CancellationException("authored cancellation")
        fun assertCancellation(actual: CancellationException) {
            // JVM coroutine stacktrace recovery may copy the exception across withContext,
            // retaining the original as its cause. Never unwrap or replace it in production.
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.take(8).any { it === cancellation })
            assertEquals(cancellation.message, actual.message)
        }
        var requests = 0
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport { requests++; throw cancellation }))
        try {
            assertCancellation(assertFailsWith<CancellationException> { source.resolve(item, EngineCapabilities()) })
            assertEquals(1, requests)
        } finally { source.close() }
        var transfers = 0
        val downloading = YouTubeMediaSource(YouTubeClient(ResearchTransport { ResearchResponse(it.url, 200, watch()) },
            downloader = YouTubeDownloader { _, _, _ -> transfers++; throw cancellation }))
        try {
            assertCancellation(assertFailsWith<CancellationException> { downloading.download(item, "authored-staging.m4a") })
            assertEquals(1, transfers)
        } finally { downloading.close() }
    }

    @Test fun invalidProviderAndClosedSourceFailWithoutNetwork() = runTest {
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport { error("No request is permitted") }))
        assertEquals(FailureCode.UNSUPPORTED_SOURCE, assertFailsWith<VeyraException> {
            source.resolve(item.copy(source = item.source.copy(provider = SourceProvider.RSS)), EngineCapabilities())
        }.failure.code)
        source.close()
        assertEquals("YOUTUBE_SOURCE_CLOSED", assertFailsWith<VeyraException> { source.search("authored") }.failure.detail)
    }
}
