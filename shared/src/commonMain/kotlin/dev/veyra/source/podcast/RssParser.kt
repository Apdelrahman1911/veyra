package dev.veyra.source.podcast

import dev.veyra.model.*
import dev.veyra.source.network.PublicUrlPolicy
import dev.veyra.source.network.sourceDigest
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

data class PodcastFeed(
    val show: PodcastShow,
    val episodes: List<MediaItem>,
    val warnings: List<String> = emptyList(),
    /** Same GUID is identity, not proof of identical enclosure bytes. */
    val changedEnclosureIds: Set<String> = emptySet(),
    val copyright: String? = null,
)

/** Publisher metadata is untrusted. No external entity, markup, media bytes, or scripts execute. */
object RssParser {
    fun parse(
        xml: String,
        feedUrl: String,
        previousShow: PodcastShow? = null,
        previousEpisodes: List<MediaItem> = emptyList(),
        fetchedAtEpochMs: Long? = null,
    ): PodcastFeed {
        val canonicalFeed = PublicUrlPolicy.normalize(feedUrl)
        val root = BoundedXml.parse(xml)
        val atom = root.localName == "feed" && root.namespace == ATOM
        val channel = when {
            atom -> root
            root.localName == "rss" && root.namespace.isEmpty() -> root.child("channel") ?: malformed("missing_rss_channel")
            else -> malformed("unsupported_feed_format")
        }
        val title = completeDiscoveryTitle(channel.child("title")?.completeValue(MAX_DISCOVERY_TITLE_LENGTH).orEmpty())
        if (title.isBlank()) malformed("missing_feed_title")
        val showId = previousShow?.id ?: podcastShowId(canonicalFeed)
        val publisher = plainText(channel.child("author", ITUNES)?.value(1_000)
            ?: channel.child("creator", DC)?.value(1_000)
            ?: channel.child("author")?.child("name")?.value(1_000).orEmpty())
        val artwork = publicLink(canonicalFeed, channel.child("image", ITUNES)?.attributes?.get("href")
            ?: channel.child("image")?.child("url")?.value(8_192)
            ?: channel.child("logo")?.value(8_192))
        val copyright = plainText(channel.child(if (atom) "rights" else "copyright")?.value(2_000).orEmpty()).takeIf { it.isNotBlank() }
        val show = PodcastShow(
            id = showId, title = title, feedUrl = canonicalFeed, publisher = publisher,
            description = plainText(channel.child("description")?.value()
                ?: channel.child("summary", ITUNES)?.value() ?: channel.child("subtitle")?.value().orEmpty()),
            artworkUrl = artwork,
            websiteUrl = publicLink(canonicalFeed, if (atom) channel.children("link").firstOrNull {
                it.attributes["rel"] in setOf(null, "alternate")
            }?.attributes?.get("href") else channel.child("link")?.value(8_192)),
            language = channel.child("language")?.value(50)?.takeIf { it.isNotBlank() }
                ?: channel.attributes["xml:lang"]?.take(50),
            explicit = explicit(channel.child("explicit", ITUNES)?.value(20)),
            feedAliases = (previousShow?.feedAliases.orEmpty() + listOfNotNull(previousShow?.feedUrl, canonicalFeed)).toSet(),
            lastRefreshEpochMs = fetchedAtEpochMs,
            copyright = copyright,
            attribution = Attribution(publisher, canonicalFeed),
        )
        val warnings = linkedSetOf<String>()
        val items = channel.children(if (atom) "entry" else "item")
        if (items.size > 5_000) malformed("feed_item_limit")
        val parsed = items.mapNotNull { node ->
            val enclosure = if (atom) node.children("link").firstOrNull { it.attributes["rel"] == "enclosure" }
                else node.child("enclosure")
            val rawEnclosure = enclosure?.attributes?.get(if (atom) "href" else "url")
            val url = publicLink(canonicalFeed, rawEnclosure)
            // RSS is discovery metadata only. A missing/unsafe enclosure does not hide a titled,
            // GUID-identified episode: the central resolver must search its full title via kmpYT.
            if (url == null) warnings += "episode_without_safe_https_enclosure"
            val guid = node.child(if (atom) "id" else "guid")?.completeValue(16_384)?.takeIf { it.isNotBlank() } ?: url
            if (guid == null) { warnings += "episode_without_stable_identity"; return@mapNotNull null }
            val episodeTitle = completeDiscoveryTitle(node.child("title")?.completeValue(MAX_DISCOVERY_TITLE_LENGTH).orEmpty())
            if (episodeTitle.isBlank()) { warnings += "episode_without_title"; return@mapNotNull null }
            val id = podcastEpisodeId(showId, guid)
            val author = plainText(node.child("author", ITUNES)?.value(1_000)
                ?: node.child("creator", DC)?.value(1_000)
                ?: node.child("author")?.child("name")?.value(1_000) ?: publisher)
            val page = publicLink(canonicalFeed, if (atom) node.children("link").firstOrNull {
                it.attributes["rel"] in setOf(null, "alternate")
            }?.attributes?.get("href") else node.child("link")?.value(8_192))
            val licenseNode = node.child("license", PODCAST) ?: channel.child("license", PODCAST)
            val licenseUrl = publicLink(canonicalFeed, licenseNode?.attributes?.get("url"))
            val mime = normalizeAudioMime(enclosure?.attributes?.get("type"))
            MediaItem(
                id = id, title = episodeTitle, kind = MediaKind.EPISODE,
                source = SourceRef(SourceProvider.RSS, id, canonicalUrl = page, feedUrl = canonicalFeed,
                    episodeGuid = guid, enclosureUrl = url, mimeType = mime),
                creator = author, artworkUrl = publicLink(canonicalFeed, node.child("image", ITUNES)?.attributes?.get("href")) ?: artwork,
                durationMs = parsePodcastDuration(node.child("duration", ITUNES)?.value(100)), showId = showId,
                description = plainText(node.child("encoded", CONTENT)?.value() ?: node.child("description")?.value()
                    ?: node.child("summary", ITUNES)?.value() ?: node.child("summary")?.value().orEmpty()),
                publishedEpochMs = parsePodcastDate(node.child("pubDate")?.value(100)
                    ?: node.child("published")?.value(100) ?: node.child("updated")?.value(100)),
                explicit = explicit(node.child("explicit", ITUNES)?.value(20)) || show.explicit,
                episodeNumber = node.child("episode", ITUNES)?.value(20)?.toIntOrNull()?.takeIf { it >= 0 },
                seasonNumber = node.child("season", ITUNES)?.value(20)?.toIntOrNull()?.takeIf { it >= 0 },
                attribution = Attribution(author, page ?: canonicalFeed, licenseNode?.value(200)?.takeIf { it.isNotBlank() }, licenseUrl),
            )
        }.distinctBy { it.id }
        val old = previousEpisodes.associateBy { it.id }
        val changes = parsed.filter { item -> old[item.id]?.source?.enclosureUrl?.let { it != item.source.enclosureUrl } == true }.mapTo(mutableSetOf()) { it.id }
        // A short latest-only RSS must not erase old saved/followed episodes on refresh.
        val freshIds = parsed.mapTo(hashSetOf()) { it.id }
        val merged = parsed + previousEpisodes.filter { it.showId == showId && it.id !in freshIds }
        return PodcastFeed(show.copy(episodeIds = merged.map { it.id }), merged, warnings.toList(), changes, copyright)
    }

    private fun explicit(value: String?): Boolean = value?.trim()?.lowercase() in setOf("yes", "true", "explicit", "1")
}

fun podcastShowId(feedUrl: String): String = "rss-show-${sourceDigest(PublicUrlPolicy.normalize(feedUrl))}"
fun podcastEpisodeId(showId: String, guid: String): String = "rss-episode-${sourceDigest("${showId.length}:$showId:$guid")}"

internal fun publicLink(base: String, value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.let {
    try { PublicUrlPolicy.normalize(URLBuilder(base).takeFrom(it).buildString()) } catch (_: Exception) { null }
}

internal fun plainText(value: String): String = xmlEntities(value.take(50_000)
    .replace(Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("<[^>]*>"), " "), strict = false).replace(Regex("[\\t\\r\\n ]+"), " ").trim()

internal fun normalizeAudioMime(value: String?): String? = when (val mime = value?.substringBefore(';')?.trim()?.lowercase()) {
    "audio/mp3", "audio/x-mp3", "audio/mpeg3" -> "audio/mpeg"
    "audio/x-m4a", "audio/m4a" -> "audio/mp4"
    null, "" -> null
    else -> mime
}

fun parsePodcastDuration(value: String?): Long? {
    val parts = value?.trim()?.takeIf { it.length in 1..100 }?.split(':') ?: return null
    if (parts.size !in 1..3) return null
    var seconds = 0.0
    for (part in parts) {
        val number = part.toDoubleOrNull() ?: return null
        if (!number.isFinite() || number < 0) return null
        seconds = seconds * 60 + number
    }
    return (seconds * 1_000).takeIf { it > 0 && it <= 30.0 * 24 * 60 * 60 * 1_000 }?.toLong()
}

@OptIn(ExperimentalTime::class)
fun parsePodcastDate(value: String?): Long? {
    val text = value?.trim()?.takeIf { it.length <= 150 } ?: return null
    runCatching { Instant.parse(text).toEpochMilliseconds() }.getOrNull()?.let { return it }
    val match = Regex("(?:[A-Za-z]{3},?\\s+)?(\\d{1,2})\\s+([A-Za-z]{3})\\s+(\\d{2,4})\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s+([+-]\\d{4}|[A-Za-z]{1,5})").matchEntire(text) ?: return null
    val (day, monthName, yearText, hour, minute, second, zone) = match.destructured
    val month = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(monthName.lowercase()) + 1
    if (month == 0) return null
    val year = yearText.toInt().let { if (yearText.length == 2) if (it >= 70) 1900 + it else 2000 + it else it }
    val offset = when (zone.uppercase()) {
        "GMT", "UT", "UTC", "Z" -> "+00:00"
        "EST" -> "-05:00"; "EDT" -> "-04:00"; "CST" -> "-06:00"; "CDT" -> "-05:00"
        "MST" -> "-07:00"; "MDT" -> "-06:00"; "PST" -> "-08:00"; "PDT" -> "-07:00"
        else -> if (zone.length == 5 && zone.first() in "+-") zone.take(3) + ":" + zone.takeLast(2) else return null
    }
    return runCatching { Instant.parse("$year-${month.toString().padStart(2, '0')}-${day.padStart(2, '0')}T${hour.padStart(2, '0')}:$minute:${second.ifEmpty { "00" }}$offset").toEpochMilliseconds() }.getOrNull()
}

internal const val ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"
private const val ATOM = "http://www.w3.org/2005/Atom"
private const val DC = "http://purl.org/dc/elements/1.1/"
private const val CONTENT = "http://purl.org/rss/1.0/modules/content/"
private const val PODCAST = "https://podcastindex.org/namespace/1.0"
