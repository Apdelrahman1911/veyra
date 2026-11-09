package dev.veyra.source.commons

import dev.veyra.model.*
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.source.*
import dev.veyra.source.network.*
import dev.veyra.source.podcast.plainText
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A visibly separate, deliberately small reviewed music DISCOVERY catalog. Search is real Commons
 * search; rights metadata describes only the original recording, not the first kmpYT result used
 * by the central audio resolver. No Commons derivative is acquired for playback or downloads.
 */
@OptIn(ExperimentalTime::class)
class CommonsSource(
    private val http: SafeHttp,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val ownsHttp: Boolean = false,
) : MediaSource {
    override val id = SourceProvider.COMMONS
    override val capabilities = SourceCapabilities(search = true, browse = true, resolve = false, download = false)
    private val lock = Mutex()
    private val records = linkedMapOf<String, CachedRecord>()
    private val searches = linkedMapOf<String, CachedSearch>()

    override suspend fun search(query: String, pageToken: String?): SourcePage {
        if (pageToken != null) sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "open_music_pagination_unavailable")
        val term = query.trim()
        if (term.length !in 1..200 || term.any { it.code < 32 }) sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "invalid_search_query")
        val ids = lock.withLock {
            searches[term.lowercase()]?.takeIf { nowMs() - it.at < CACHE_TTL }?.let { return@withLock it.ids }
            val json = http.get(SafeHttpRequest("$API?action=query&list=search&srsearch=${"$term filetype:audio".encodeURLParameter()}&srnamespace=6&srlimit=30&format=json", ACCEPT), 2 * 1024 * 1024).text()
            val result = parseSearchIds(json).filter { it in REVIEWED }
            if (searches.size >= 32) searches.remove(searches.keys.first())
            searches[term.lowercase()] = CachedSearch(nowMs(), result)
            result
        }
        val items = ids.map { record(it).item }
        return SourcePage(items = items, attribution = ATTRIBUTION,
            notice = if (items.isEmpty()) AppFailure(FailureCode.NOT_FOUND, "no_reviewed_open_music_matches") else null)
    }

    override suspend fun browse(request: BrowseRequest): SourcePage {
        if (request.kind != BrowseKind.HOME) sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "unsupported_open_music_browse")
        return SourcePage(items = REVIEWED.keys.map { record(it).item }, attribution = ATTRIBUTION)
    }

    override suspend fun details(ref: SourceRef): MediaItem {
        if (ref.provider != id) sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "wrong_open_music_provider")
        return record(ref.canonicalId).item
    }

    override suspend fun resolve(item: MediaItem, capabilities: EngineCapabilities, purpose: ResolvePurpose): ResolvedPlayable {
        if (item.source.provider != id) sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "wrong_open_music_provider")
        sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "discovery_only_kmpyt_first_result_required")
    }

    override suspend fun close() { if (ownsHttp) http.close() }

    private suspend fun record(pageId: String): CommonsRecord = lock.withLock {
        if (pageId !in REVIEWED) sourceFailure(FailureCode.PERMISSION_DENIED, "recording_rights_not_reviewed")
        records[pageId]?.takeIf { nowMs() - it.at < CACHE_TTL }?.let { return@withLock it.record }
        val json = http.get(SafeHttpRequest("$API?action=query&prop=revisions%7Cvideoinfo&pageids=$pageId&rvprop=content&rvslots=main&viprop=size%7Cmime%7Cextmetadata&format=json", ACCEPT), 2 * 1024 * 1024).text()
        val value = parseRecord(json, pageId)
        records[pageId] = CachedRecord(nowMs(), value)
        value
    }

    private data class CachedRecord(val at: Long, val record: CommonsRecord)
    private data class CachedSearch(val at: Long, val ids: List<String>)

    companion object {
        private const val API = "https://commons.wikimedia.org/w/api.php"
        private const val CACHE_TTL = 10 * 60_000L
        private const val ATTRIBUTION = "Music discovery metadata: Wikimedia Commons. Audio: first kmpYT search result for the complete title; original recording rights do not cover that result."
        private val ACCEPT = mapOf("Accept" to "application/json")

        /** Add a recording only after reviewing both underlying-work and performance permissions. */
        val reviewedPageIds: Set<String> get() = REVIEWED.keys

        internal fun parseSearchIds(json: String): List<String> {
            val root = parseJson(json)
            val results = (root["query"] as? JsonObject)?.get("search") as? JsonArray
                ?: sourceFailure(FailureCode.PARSE_FAILURE, "invalid_commons_search")
            if (results.size > 100) sourceFailure(FailureCode.PARSE_FAILURE, "commons_result_limit")
            return results.mapNotNull { (it as? JsonObject)?.string("pageid") }.distinct()
        }

        internal fun parseRecord(json: String, expectedId: String): CommonsRecord {
            val reviewed = REVIEWED[expectedId] ?: sourceFailure(FailureCode.PERMISSION_DENIED, "recording_rights_not_reviewed")
            val pages = (parseJson(json)["query"] as? JsonObject)?.get("pages") as? JsonObject
                ?: sourceFailure(FailureCode.PARSE_FAILURE, "missing_commons_file")
            val page = pages[expectedId] as? JsonObject ?: sourceFailure(FailureCode.NOT_FOUND, "commons_file_missing")
            if (page.string("pageid") != expectedId || page.string("title") != reviewed.title || "missing" in page) {
                sourceFailure(FailureCode.PERMISSION_DENIED, "reviewed_file_identity_changed")
            }
            val revision = (page["revisions"] as? JsonArray)?.firstOrNull() as? JsonObject
            val content = (((revision?.get("slots") as? JsonObject)?.get("main") as? JsonObject)?.string("*"))
                ?: sourceFailure(FailureCode.PERMISSION_DENIED, "recording_license_unavailable")
            if (!reviewedLicenseStillPresent(content, reviewed.performer)) sourceFailure(FailureCode.PERMISSION_DENIED, "recording_license_changed")
            val info = (page["videoinfo"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: sourceFailure(FailureCode.PARSE_FAILURE, "commons_media_info_missing")
            val canonical = "https://commons.wikimedia.org/wiki/${reviewed.title.replace(' ', '_').encodeURLParameter()}"
            val duration = info.string("duration")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it < 30 * 24 * 3600 }
                ?.let { (it * 1_000).toLong() }
            val metadata = info["extmetadata"] as? JsonObject
            val description = ((metadata?.get("ImageDescription") as? JsonObject)?.string("value")).orEmpty()
            val creator = "Ludwig van Beethoven · ${reviewed.performer}"
            val item = MediaItem(
                id = "commons-$expectedId", title = reviewed.title.removePrefix("File:").substringBeforeLast('.'),
                kind = MediaKind.MUSIC,
                source = SourceRef(SourceProvider.COMMONS, expectedId, canonicalUrl = canonical),
                creator = creator, durationMs = duration, description = plainText(description),
                artist = creator,
                attribution = Attribution(creator, canonical, "CC0 1.0 performance; public-domain composition", "https://creativecommons.org/publicdomain/zero/1.0/"),
            )
            return CommonsRecord(item)
        }

        internal fun reviewedLicenseStillPresent(wikitext: String, performer: String): Boolean {
            if (wikitext.length > 200_000) return false
            val normalized = wikitext.lowercase().replace(Regex("\\s+"), "")
            val composition = normalized.substringAfter("===composition===", "").substringBefore("===performance===")
            val performance = normalized.substringAfter("===performance===", "").substringBefore("=={{assessment")
            return "{{own}}" in normalized && "[[user:${performer.lowercase().replace(" ", "")}|" in normalized &&
                "{{pd-old-100}}" in composition && "{{self|cc-zero}}" in performance
        }

        private fun parseJson(value: String): JsonObject {
            val result = try { Json.parseToJsonElement(value).jsonObject } catch (_: Exception) { sourceFailure(FailureCode.PARSE_FAILURE, "invalid_commons_response") }
            if (result["error"] != null) sourceFailure(FailureCode.PARSE_FAILURE, "commons_api_error")
            return result
        }
    }
}

internal data class CommonsRecord(val item: MediaItem)
private data class ReviewedRecording(val title: String, val performer: String)
private val REVIEWED = linkedMapOf(
    "13393751" to ReviewedRecording("File:Beethoven - 32 Variations in C Minor, WoO 80.ogg", "La Pianista"),
)

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
