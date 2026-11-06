package dev.veyra.source.podcast

import dev.veyra.model.*
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.source.*
import dev.veyra.source.network.*
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Real no-key directory and publisher RSS for discovery only; all audio execution goes via kmpYT. */
@OptIn(ExperimentalTime::class)
class PodcastSource(
    private val http: SafeHttp,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val ownsHttp: Boolean = false,
    private val directoryMinimumIntervalMs: Long = 3_000,
) : MediaSource {
    override val id: SourceProvider = SourceProvider.RSS
    override val capabilities = SourceCapabilities(search = true, browse = true, resolve = false, download = false, podcasts = true)
    private val directoryLock = Mutex()
    private val feedLock = Mutex()
    private val directoryCache = linkedMapOf<String, CachedPage>()
    private val feeds = linkedMapOf<String, PodcastFeed>()
    private val directoryShows = linkedMapOf<String, PodcastShow>()
    private var lastDirectoryRequest = Long.MIN_VALUE

    override suspend fun search(query: String, pageToken: String?): SourcePage {
        if (pageToken != null) sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "directory_pagination_unavailable")
        val term = query.trim()
        if (term.length !in 1..256 || term.any { it.code < 32 }) sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "invalid_search_query")
        val country = if (term.any { it.code in 0x0600..0x06ff }) "SA" else "US"
        val key = "$country:${term.lowercase()}"
        return directoryLock.withLock {
            directoryCache[key]?.takeIf { nowMs() - it.at < DIRECTORY_TTL }?.let { return@withLock it.page }
            if (lastDirectoryRequest != Long.MIN_VALUE) {
                val remaining = directoryMinimumIntervalMs - (nowMs() - lastDirectoryRequest)
                if (remaining > 0) delay(remaining.coerceAtMost(directoryMinimumIntervalMs))
            }
            lastDirectoryRequest = nowMs()
            val url = "https://itunes.apple.com/search?term=${term.encodeURLParameter()}&media=podcast&entity=podcast&country=$country&limit=30"
            val data = http.get(SafeHttpRequest(url, mapOf("Accept" to "application/json")), 2 * 1024 * 1024)
            val page = parseDirectory(data.text())
            if (directoryCache.size >= 32) directoryCache.remove(directoryCache.keys.first())
            directoryCache[key] = CachedPage(nowMs(), page)
            for (show in page.shows) directoryShows[show.id] = show
            while (directoryShows.size > 256) directoryShows.remove(directoryShows.keys.first())
            page
        }
    }

    override suspend fun browse(request: BrowseRequest): SourcePage = when (request.kind) {
        BrowseKind.HOME -> search(if (request.language?.startsWith("ar") == true) "فنجان" else "Hacker Public Radio")
        BrowseKind.SHOW -> {
            val locator = request.id ?: sourceFailure(FailureCode.NOT_FOUND, "podcast_show_required")
            val known = feedLock.withLock { feeds.values.firstOrNull { it.show.id == locator }?.show }
                ?: directoryLock.withLock { directoryShows[locator] }
            val feed = refreshFeed(known?.feedUrl ?: locator, known)
            SourcePage(items = feed.episodes, shows = listOf(feed.show), attribution = "Podcast metadata: publisher RSS. Audio: first kmpYT search result for the complete episode title.")
        }
        else -> sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "unsupported_podcast_browse")
    }

    /** Caller persists the returned metadata. Prior episodes survive short/truncated recent-only feeds. */
    suspend fun refreshFeed(
        feedUrl: String,
        previousShow: PodcastShow? = null,
        previousEpisodes: List<MediaItem> = emptyList(),
        force: Boolean = true,
    ): PodcastFeed = feedLock.withLock {
        val canonical = PublicUrlPolicy.normalize(feedUrl.trim())
        val cached = feeds.values.firstOrNull { canonical == it.show.feedUrl || canonical in it.show.feedAliases }
        val previous = if (cached != null) {
            // A caller can know older saved/followed episodes that this short-lived cache has never
            // seen. Fresh cached metadata wins for duplicates, but it must not erase that history.
            val retained = (cached.episodes + previousEpisodes.filter { it.showId == cached.show.id }).distinctBy { it.id }
            cached.copy(episodes = retained, show = cached.show.copy(
                episodeIds = retained.map { it.id },
                feedAliases = cached.show.feedAliases + previousShow?.takeIf { it.id == cached.show.id }?.feedAliases.orEmpty(),
            ))
        } else previousShow?.let { show ->
            val retained = previousEpisodes.filter { it.showId == show.id }.distinctBy { it.id }
            PodcastFeed(show.copy(episodeIds = (show.episodeIds + retained.map { it.id }).distinct()), retained, copyright = show.copyright)
        }
        if (!force && cached != null && nowMs() - (cached.show.lastRefreshEpochMs ?: 0) < FEED_TTL) {
            return@withLock remember(requireNotNull(previous))
        }
        val headers = mutableMapOf("Accept" to "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.8")
        val retainedIds = previous?.episodes?.mapTo(hashSetOf()) { it.id }.orEmpty()
        if (previous != null && previous.show.episodeIds.all { it in retainedIds }) {
            previous.show.etag?.let { headers["If-None-Match"] = it }
            previous.show.lastModified?.let { headers["If-Modified-Since"] = it }
        }
        val response = http.get(SafeHttpRequest(canonical, headers))
        val next = if (response.response.status == 304) {
            if ("If-None-Match" !in headers && "If-Modified-Since" !in headers) {
                sourceFailure(FailureCode.PARSE_FAILURE, "not_modified_without_cached_feed")
            }
            previous?.copy(show = previous.show.copy(
                lastRefreshEpochMs = nowMs(),
                feedAliases = previous.show.feedAliases + canonical + response.response.finalUrl,
                etag = response.response.header("etag") ?: previous.show.etag,
                lastModified = response.response.header("last-modified") ?: previous.show.lastModified,
            ))
                ?: sourceFailure(FailureCode.PARSE_FAILURE, "not_modified_without_cached_feed")
        } else {
            val text = try { response.text() } catch (_: Exception) { sourceFailure(FailureCode.PARSE_FAILURE, "invalid_feed_encoding") }
            val parsed = RssParser.parse(text, response.response.finalUrl, previous?.show, previous?.episodes.orEmpty(), nowMs())
            parsed.copy(show = parsed.show.copy(
                feedAliases = parsed.show.feedAliases + canonical,
                etag = response.response.header("etag"), lastModified = response.response.header("last-modified"),
            ))
        }
        remember(next)
    }

    override suspend fun details(ref: SourceRef): MediaItem {
        if (ref.provider != id) sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "wrong_podcast_provider")
        val cached = feedLock.withLock { feeds.values.flatMap { it.episodes }.firstOrNull { it.id == ref.canonicalId } }
        if (cached != null) return cached
        val feed = refreshFeed(ref.feedUrl ?: sourceFailure(FailureCode.NOT_FOUND, "missing_feed_locator"), force = false)
        return feed.episodes.firstOrNull { it.source.episodeGuid == ref.episodeGuid || it.id == ref.canonicalId }
            ?: sourceFailure(FailureCode.NOT_FOUND, "episode_no_longer_in_feed")
    }

    override suspend fun resolve(item: MediaItem, capabilities: EngineCapabilities, purpose: ResolvePurpose): ResolvedPlayable {
        if (item.source.provider != id) sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "wrong_podcast_provider")
        // No HEAD, ranged GET, feed refresh, or publisher fallback is permitted here, including
        // after an authentication/codec failure. Only the app's central kmpYT resolver can execute.
        sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "discovery_only_kmpyt_first_result_required")
    }

    override suspend fun close() { if (ownsHttp) http.close() }

    /** Called only while holding [feedLock]. */
    private fun remember(feed: PodcastFeed): PodcastFeed {
        if (feeds.size >= 8 && feed.show.id !in feeds) feeds.remove(feeds.keys.first())
        feeds[feed.show.id] = feed
        while (feeds.size > 1 && feeds.values.sumOf { value -> value.episodes.sumOf { it.title.length + it.description.length } } > 8_000_000) {
            feeds.remove(feeds.keys.first())
        }
        return feed
    }

    private data class CachedPage(val at: Long, val page: SourcePage)
    companion object {
        private const val DIRECTORY_TTL = 10 * 60_000L
        private const val FEED_TTL = 5 * 60_000L

        fun parseDirectory(json: String): SourcePage {
            val root = try { Json.parseToJsonElement(json).jsonObject } catch (_: Exception) { sourceFailure(FailureCode.PARSE_FAILURE, "invalid_directory_response") }
            val results = root["results"] as? JsonArray ?: sourceFailure(FailureCode.PARSE_FAILURE, "missing_directory_results")
            if (results.size > 100) sourceFailure(FailureCode.PARSE_FAILURE, "directory_result_limit")
            val shows = results.mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                fun text(name: String) = (entry[name] as? JsonPrimitive)?.contentOrNull
                val title = completeDiscoveryTitle(text("collectionName") ?: text("trackName") ?: "")
                val feed = text("feedUrl")?.let { try { PublicUrlPolicy.normalize(it) } catch (_: Exception) { null } }
                if (title.isBlank() || feed == null) return@mapNotNull null
                PodcastShow(
                    id = podcastShowId(feed), title = title, feedUrl = feed,
                    publisher = plainText(text("artistName").orEmpty()).take(1_000),
                    artworkUrl = publicLink(feed, text("artworkUrl600") ?: text("artworkUrl100")),
                    feedAliases = setOf(feed),
                    explicit = text("collectionExplicitness") == "explicit",
                    attribution = Attribution(text("artistName").orEmpty(), feed),
                )
            }.distinctBy { it.id }
            return SourcePage(shows = shows, attribution = "Podcast discovery metadata: Apple and publishers. Audio: first kmpYT search result for the complete episode title.")
        }
    }
}

typealias RssPodcastSource = PodcastSource
