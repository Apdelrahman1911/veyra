package dev.veyra.source.podcast

import dev.veyra.model.*
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.network.KtorSafeHttp
import dev.veyra.source.network.PublicDnsResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class PodcastSourceTest {
    @Test fun directoryPreservesArabicPublisherAndDeduplicatesByFeedNotAppleId() {
        val response = """{"resultCount":3,"results":[
          {"collectionId":1,"collectionName":"فنجان","artistName":"ثمانية","feedUrl":"https://publisher.example.com/rss"},
          {"collectionId":2,"collectionName":"Duplicate directory entry","feedUrl":"https://publisher.example.com/rss"},
          {"collectionId":3,"collectionName":"Unsafe","feedUrl":"http://127.0.0.1/private"}]}"""
        val shows = PodcastSource.parseDirectory(response).shows
        assertEquals(1, shows.size)
        assertEquals("فنجان", shows.single().title)
        assertEquals("ثمانية", shows.single().publisher)
    }

    @Test fun malformedDirectoryDoesNotMasqueradeAsEmptySearchSuccess() {
        listOf("<html>blocked</html>", "{}", "{\"error\":\"blocked\"}").forEach {
            assertFailsWith<VeyraException> { PodcastSource.parseDirectory(it) }
        }
    }

    @Test fun directoryRetainsCompleteTitlePunctuationAndArabicSuffix() {
        val title = "A full publisher title <5> &copy; ".repeat(70) + " — النهاية المميزة"
        val json = buildJsonObject { putJsonArray("results") { addJsonObject {
            put("collectionName", title); put("feedUrl", "https://publisher.example.com/rss")
        } } }.toString()
        assertEquals(title, PodcastSource.parseDirectory(json).shows.single().title)
    }

    @Test fun oversizedDirectoryTitleIsAnExplicitFailureNotShortenedSearchInput() {
        val json = buildJsonObject { putJsonArray("results") { addJsonObject {
            put("collectionName", "x".repeat(MAX_DISCOVERY_TITLE_LENGTH + 1)); put("feedUrl", "https://publisher.example.com/rss")
        } } }.toString()
        assertEquals(FailureCode.PARSE_FAILURE, assertFailsWith<VeyraException> { PodcastSource.parseDirectory(json) }.failure.code)
    }

    @Test fun publisherEnclosureNeverBecomesPlaybackOrDownloadFallback() = runTest {
        var networkCalls = 0
        var dnsCalls = 0
        val engine = MockEngine { networkCalls++; respond("not allowed") }
        val http = KtorSafeHttp(HttpClient(engine) { followRedirects = false; install(HttpTimeout) },
            PublicDnsResolver { dnsCalls++; listOf("93.184.216.34") })
        val source = PodcastSource(http)
        val item = RssParser.parse(feed(), "https://publisher.example.com/rss").episodes.single()
        try {
            assertFalse(source.capabilities.resolve)
            assertFalse(source.capabilities.download)
            for (purpose in ResolvePurpose.entries) {
                val error = assertFailsWith<VeyraException> { source.resolve(item, EngineCapabilities(), purpose) }
                assertEquals(FailureCode.UNSUPPORTED_SOURCE, error.failure.code)
                assertEquals("discovery_only_kmpyt_first_result_required", error.failure.detail)
            }
            assertEquals(0, dnsCalls)
            assertEquals(0, networkCalls)
        } finally { source.close(); http.close() }
    }

    @Test fun feedRefreshUsesConditionalMetadataOnlyAndRetainsCompleteTitlesOn304() = runTest {
        var now = 1_000L
        val completeTitle = "Full episode title ".repeat(100) + "ending"
        var calls = 0
        val engine = MockEngine { request ->
            assertEquals("publisher.example.com", request.url.host)
            assertEquals("/rss", request.url.encodedPath)
            if (calls++ == 0) respond(feed(completeTitle), headers = headersOf("ETag", "\"feed-v1\""))
            else {
                assertEquals("\"feed-v1\"", request.headers["If-None-Match"])
                respond("", HttpStatusCode.NotModified)
            }
        }
        val http = KtorSafeHttp(HttpClient(engine) { followRedirects = false; install(HttpTimeout) },
            PublicDnsResolver { listOf("93.184.216.34") })
        val source = PodcastSource(http, nowMs = { now })
        try {
            val first = source.refreshFeed("https://publisher.example.com/rss")
            now = 2_000L
            val second = source.refreshFeed("https://publisher.example.com/rss", first.show, first.episodes)
            assertEquals(completeTitle, second.episodes.single().title)
            assertEquals(first.episodes, second.episodes)
            assertEquals(first.show.episodeIds, second.show.episodeIds)
            assertEquals(2_000L, second.show.lastRefreshEpochMs)
            assertEquals(2, calls)
        } finally { source.close(); http.close() }
    }

    @Test fun metadataCancellationDoesNotBecomeAnEmptyResultOrSourceFallback() = runTest {
        val engine = MockEngine { throw CancellationException("test cancellation") }
        val http = KtorSafeHttp(HttpClient(engine) { followRedirects = false; install(HttpTimeout) },
            PublicDnsResolver { listOf("93.184.216.34") })
        val source = PodcastSource(http)
        try {
            assertFailsWith<CancellationException> { source.refreshFeed("https://publisher.example.com/rss") }
        } finally { source.close(); http.close() }
    }

    @Test fun shortLivedCacheRetainsHistoryWithoutAddingItToCurrentFeedMembership() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond(feed(), headers = headersOf("ETag", "\"feed-v1\"")) }
        val http = KtorSafeHttp(HttpClient(engine) { followRedirects = false; install(HttpTimeout) },
            PublicDnsResolver { listOf("93.184.216.34") })
        val source = PodcastSource(http, nowMs = { 1_000 })
        try {
            val fresh = source.refreshFeed("https://publisher.example.com/rss")
            val archived = fresh.episodes.single().copy(id = "older", title = "Older complete title",
                source = fresh.episodes.single().source.copy(canonicalId = "older", episodeGuid = "older-guid"))
            val merged = source.refreshFeed(fresh.show.feedUrl, fresh.show, fresh.episodes + archived, force = false)
            assertEquals(fresh.show.episodeIds, merged.show.episodeIds)
            assertEquals(listOf(fresh.episodes.single().id, archived.id), merged.episodes.map { it.id })
            assertEquals(archived, merged.episodes.last())
            assertEquals(1, calls)
        } finally { source.close(); http.close() }
    }

    @Test fun shortFreshFeedChangesMembershipButFollowing304CannotRestoreArchivedMembership() = runTest {
        var calls = 0
        var now = 1_000L
        val engine = MockEngine { request ->
            when (calls++) {
                0 -> respond(feedEpisodes("recent", "archived"), headers = headersOf("ETag", "\"feed-v1\""))
                1 -> {
                    assertEquals("\"feed-v1\"", request.headers["If-None-Match"])
                    respond(feedEpisodes("new", "recent"), headers = headersOf("ETag", "\"feed-v2\""))
                }
                else -> {
                    assertEquals("\"feed-v2\"", request.headers["If-None-Match"])
                    respond("", HttpStatusCode.NotModified)
                }
            }
        }
        val http = KtorSafeHttp(HttpClient(engine) { followRedirects = false; install(HttpTimeout) },
            PublicDnsResolver { listOf("93.184.216.34") })
        val source = PodcastSource(http, nowMs = { now })
        try {
            val first = source.refreshFeed(FEED_URL)
            val archived = first.episodes.last()
            now = 2_000L
            val fresh = source.refreshFeed(FEED_URL, first.show, first.episodes)
            assertEquals(listOf("new", "recent").map { podcastEpisodeId(first.show.id, it) }, fresh.show.episodeIds)
            assertEquals(archived, fresh.episodes.last())
            assertEquals(3, fresh.episodes.size)
            now = 3_000L
            // Even stale caller metadata/membership must not overwrite the newer cached feed.
            val unchanged = source.refreshFeed(FEED_URL, first.show, first.episodes)
            assertEquals(fresh.show.episodeIds, unchanged.show.episodeIds)
            assertEquals(fresh.episodes, unchanged.episodes)
            assertEquals(3_000L, unchanged.show.lastRefreshEpochMs)
            assertEquals(3, calls)
        } finally { source.close(); http.close() }
    }

    @Test fun restartedSource304PreservesPersistedCurrentMembershipSeparatelyFromHistory() = runTest {
        val original = RssParser.parse(feedEpisodes("recent", "archived"), FEED_URL)
        val shortened = RssParser.parse(feedEpisodes("recent"), FEED_URL, original.show, original.episodes, 1_000)
        val persisted = shortened.show.copy(etag = "\"short-feed\"", lastModified = "Fri, 06 Nov 2026 00:00:00 GMT")
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            assertEquals(persisted.etag, request.headers["If-None-Match"])
            assertEquals(persisted.lastModified, request.headers["If-Modified-Since"])
            respond("", HttpStatusCode.NotModified)
        }
        val http = KtorSafeHttp(HttpClient(engine) { followRedirects = false; install(HttpTimeout) },
            PublicDnsResolver { listOf("93.184.216.34") })
        val source = PodcastSource(http, nowMs = { 2_000 })
        try {
            val restarted = source.refreshFeed(FEED_URL, persisted, shortened.episodes)
            assertEquals(shortened.show.episodeIds, restarted.show.episodeIds)
            assertEquals(listOf(original.episodes.first().id), restarted.show.episodeIds)
            assertEquals(shortened.episodes, restarted.episodes)
            assertEquals(2_000L, restarted.show.lastRefreshEpochMs)
            val cached = source.refreshFeed(FEED_URL, original.show, original.episodes, force = false)
            assertEquals(restarted.show.episodeIds, cached.show.episodeIds)
            assertEquals(restarted.episodes, cached.episodes)
            assertEquals(1, calls)
        } finally { source.close(); http.close() }
    }

    @Test fun emptyCurrentFeedRemainsEmptyOnRestart304WithoutErasingArchivedMetadata() = runTest {
        val original = RssParser.parse(feedEpisodes("archived"), FEED_URL)
        val empty = RssParser.parse(feedEpisodes(), FEED_URL, original.show, original.episodes, 1_000)
        val persisted = empty.show.copy(etag = "\"empty-feed\"")
        val engine = MockEngine { request ->
            assertEquals(persisted.etag, request.headers["If-None-Match"])
            respond("", HttpStatusCode.NotModified)
        }
        val http = KtorSafeHttp(HttpClient(engine) { followRedirects = false; install(HttpTimeout) },
            PublicDnsResolver { listOf("93.184.216.34") })
        val source = PodcastSource(http, nowMs = { 2_000 })
        try {
            val unchanged = source.refreshFeed(FEED_URL, persisted, empty.episodes)
            assertTrue(unchanged.show.episodeIds.isEmpty())
            assertEquals(original.episodes, unchanged.episodes)
            assertEquals(2_000L, unchanged.show.lastRefreshEpochMs)
        } finally { source.close(); http.close() }
    }

    @Test fun persistedHistoryCannotAuthorize304WhenCurrentMemberMetadataIsMissing() = runTest {
        val original = RssParser.parse(feedEpisodes("current", "archived"), FEED_URL)
        val persisted = original.show.copy(episodeIds = listOf(original.episodes.first().id), etag = "\"current-feed\"")
        val engine = MockEngine { request ->
            assertNull(request.headers["If-None-Match"])
            assertNull(request.headers["If-Modified-Since"])
            respond("", HttpStatusCode.NotModified)
        }
        val http = KtorSafeHttp(HttpClient(engine) { followRedirects = false; install(HttpTimeout) },
            PublicDnsResolver { listOf("93.184.216.34") })
        val source = PodcastSource(http)
        try {
            val failure = assertFailsWith<VeyraException> {
                source.refreshFeed(FEED_URL, persisted, listOf(original.episodes.last()))
            }.failure
            assertEquals(FailureCode.PARSE_FAILURE, failure.code)
            assertEquals("not_modified_without_cached_feed", failure.detail)
        } finally { source.close(); http.close() }
    }

    @Test fun callerHistoryOn304RetainsMetadataButNeverExpandsCachedCurrentMembership() = runTest {
        var calls = 0
        val engine = MockEngine { request ->
            if (calls++ == 0) respond(feed(), headers = headersOf("ETag", "\"feed-v1\""))
            else {
                assertEquals("\"feed-v1\"", request.headers["If-None-Match"])
                respond("", HttpStatusCode.NotModified)
            }
        }
        val http = KtorSafeHttp(HttpClient(engine) { followRedirects = false; install(HttpTimeout) },
            PublicDnsResolver { listOf("93.184.216.34") })
        val source = PodcastSource(http, nowMs = { 1_000 })
        try {
            val fresh = source.refreshFeed(FEED_URL)
            val archived = fresh.episodes.single().copy(id = "archived", title = "Retained complete title",
                source = fresh.episodes.single().source.copy(canonicalId = "archived", episodeGuid = "archived-guid"))
            val staleMembership = fresh.show.copy(episodeIds = fresh.show.episodeIds + archived.id)
            val unchanged = source.refreshFeed(FEED_URL, staleMembership, fresh.episodes + archived)
            assertEquals(fresh.show.episodeIds, unchanged.show.episodeIds)
            assertEquals(fresh.episodes + archived, unchanged.episodes)
            assertEquals(2, calls)
        } finally { source.close(); http.close() }
    }

    @Test fun response304WithoutStoredEpisodesMustNotPretendFeedWasLoaded() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.NotModified) }
        val http = KtorSafeHttp(HttpClient(engine) { followRedirects = false; install(HttpTimeout) },
            PublicDnsResolver { listOf("93.184.216.34") })
        val source = PodcastSource(http)
        try {
            assertEquals(FailureCode.PARSE_FAILURE, assertFailsWith<VeyraException> {
                source.refreshFeed("https://publisher.example.com/rss")
            }.failure.code)
        } finally { source.close(); http.close() }
    }

    private fun feed(title: String = "Full publisher episode title") = """<rss><channel><title>Publisher show</title>
        <item><title>$title</title><guid>publisher-guid</guid>
        <enclosure url="https://media.example.com/publisher.mp3" type="audio/mpeg"/></item></channel></rss>"""

    private fun feedEpisodes(vararg guids: String): String = "<rss><channel><title>Publisher show</title>" +
        guids.joinToString("") { guid -> "<item><title>Complete episode title $guid</title><guid>$guid</guid></item>" } +
        "</channel></rss>"

    private companion object { const val FEED_URL = "https://publisher.example.com/rss" }
}
