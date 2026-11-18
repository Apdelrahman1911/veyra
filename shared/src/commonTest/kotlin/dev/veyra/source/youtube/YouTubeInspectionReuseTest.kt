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
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Authored watch responses only; counts inspections to prove single-use, bounded reuse. */
@OptIn(ExperimentalTime::class)
class YouTubeInspectionReuseTest {
    private val videoId = "ABCDEFGHIJK"
    private val item = MediaItem("youtube:$videoId", "Authored audio", MediaKind.MUSIC,
        SourceRef(SourceProvider.YOUTUBE, videoId, "https://www.youtube.com/watch?v=$videoId"))
    private val realNow = Clock.System.now().toEpochMilliseconds()

    private fun watch(expireSeconds: Long?, signature: String) = "<script>var ytInitialPlayerResponse=" + buildJsonObject {
        putJsonObject("videoDetails") { put("videoId", videoId); put("title", "Authored audio"); put("author", "Actual uploader"); put("lengthSeconds", "3") }
        putJsonObject("playabilityStatus") { put("status", "OK") }
        putJsonObject("streamingData") {
            put("adaptiveFormats", buildJsonArray {
                add(buildJsonObject {
                    put("itag", 140)
                    put("url", "https://media.example/videoplayback?id=authored&sig=$signature" + (expireSeconds?.let { "&expire=$it" } ?: ""))
                    put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\""); put("contentLength", "32"); put("bitrate", 128_000)
                })
            })
        }
    }.toString() + ";</script>"

    private fun source(expireSeconds: Long?, now: () -> Long = { Clock.System.now().toEpochMilliseconds() }): Pair<YouTubeMediaSource, () -> Int> {
        var watches = 0
        val source = YouTubeMediaSource(YouTubeClient(ResearchTransport {
            if (it.stage == "youtube-watch") watches++
            ResearchResponse(it.url, 200, watch(expireSeconds, "authored-$watches"))
        }), now)
        return source to { watches }
    }

    @Test fun aValidInspectionIsReusedExactlyOnce() = runTest {
        val (source, watches) = source(realNow / 1000 + 3_600)
        try {
            assertTrue(source.resolve(item, EngineCapabilities()).uri.contains("authored-1"))
            assertTrue(source.resolve(item, EngineCapabilities()).uri.contains("authored-1"))
            assertEquals(1, watches())
            assertTrue(source.resolve(item, EngineCapabilities()).uri.contains("authored-2"))
            assertEquals(2, watches())
        } finally { source.close() }
    }

    @Test fun unknownOrNearExpiryIsNeverReused() = runTest {
        for (expire in listOf(null, realNow / 1000 + 10 * 60)) {
            val (source, watches) = source(expire)
            try {
                source.resolve(item, EngineCapabilities()); source.resolve(item, EngineCapabilities())
                assertEquals(2, watches(), "expire=$expire")
            } finally { source.close() }
        }
    }

    @Test fun anOldInspectionIsNotReused() = runTest {
        var now = realNow
        val (source, watches) = source(realNow / 1000 + 4 * 3_600) { now }
        try {
            source.resolve(item, EngineCapabilities())
            now += YouTubeInspectionReuse.MAX_AGE_MS + 1
            source.resolve(item, EngineCapabilities())
            assertEquals(2, watches())
        } finally { source.close() }
    }

    @Test fun detailsLeaveTheirInspectionForTheNextPlay() = runTest {
        val (source, watches) = source(realNow / 1000 + 3_600)
        try {
            source.details(item.source)
            assertTrue(source.resolve(item, EngineCapabilities()).uri.contains("authored-1"))
            assertEquals(1, watches())
        } finally { source.close() }
    }

    @Test fun refreshAfterAnExpiredLinkAlwaysInspectsFresh() = runTest {
        val reports = mutableListOf<Boolean>()
        val inspect = reuseFirstInspection(alreadySupplied = false) { fresh -> reports += fresh; error("unused") }
        repeat(3) { runCatching { inspect() } }
        assertEquals(listOf(false, true, true), reports)
        reports.clear()
        val supplied = reuseFirstInspection(alreadySupplied = true) { fresh -> reports += fresh; error("unused") }
        runCatching { supplied() }
        assertEquals(listOf(true), reports)
    }
}
