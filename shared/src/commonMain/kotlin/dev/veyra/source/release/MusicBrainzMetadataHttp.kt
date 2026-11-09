package dev.veyra.source.release

import dev.veyra.source.network.PublicDnsResolver
import dev.veyra.source.network.PublicUrlPolicy
import dev.veyra.source.network.platformHttpClient
import dev.veyra.source.network.platformResolveHost
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Typed fixed-origin metadata requests; this interface cannot accept an artwork/media URL. */
sealed interface MusicBrainzMetadataRequest {
    data class Release(val mbid: String) : MusicBrainzMetadataRequest {
        override fun toString() = "MusicBrainzMetadataRequest.Release(metadata=redacted)"
    }
    data class Search(val query: ReleaseSearchQuery, val offset: Int, val limit: Int) : MusicBrainzMetadataRequest {
        override fun toString() = "MusicBrainzMetadataRequest.Search(offset=$offset,limit=$limit,query=redacted)"
    }
}

class MusicBrainzMetadataResponse(
    val status: Int,
    val body: String?,
    val etag: String? = null,
    val retryAfter: String? = null,
) {
    override fun toString() = "MusicBrainzMetadataResponse(status=$status,metadata=redacted)"
}

interface MusicBrainzMetadataTransport {
    suspend fun get(request: MusicBrainzMetadataRequest, beforeNetwork: suspend () -> Unit): MusicBrainzMetadataResponse
    fun close()
}

/** Dedicated no-auth/no-cookie platform client; no global/network/cache configuration is changed. */
fun createMusicBrainzMetadataTransport(applicationVersion: String): MusicBrainzMetadataTransport {
    require(validVersion(applicationVersion))
    return KtorMusicBrainzMetadataTransport(platformHttpClient(), PublicDnsResolver(::platformResolveHost), applicationVersion)
}

/**
 * The injected client must be dedicated, verified-TLS, with redirects/retries/cookies/auth disabled
 * (the production factory supplies that existing platform configuration). DNS preflight is not
 * atomic DNS pinning: the underlying engine can resolve again. No alternate host is ever selected.
 */
internal class KtorMusicBrainzMetadataTransport(
    private val client: HttpClient,
    private val dns: PublicDnsResolver,
    applicationVersion: String,
    private val ownsClient: Boolean = true,
) : MusicBrainzMetadataTransport {
    private val userAgent: String
    init {
        require(validVersion(applicationVersion))
        userAgent = "Veyra/$applicationVersion (https://github.com/Apdelrahman1911/veyra)"
    }

    override suspend fun get(request: MusicBrainzMetadataRequest, beforeNetwork: suspend () -> Unit): MusicBrainzMetadataResponse {
        val url = musicBrainzUrl(request)
        try {
            beforeNetwork()
            val addresses = dns.resolve("musicbrainz.org")
            if (addresses.isEmpty()) releaseFailure(ReleaseFailureCode.NETWORK)
            if (addresses.any { !PublicUrlPolicy.isPublicAddress(it) }) releaseFailure(ReleaseFailureCode.NON_PUBLIC_ORIGIN)
            beforeNetwork()
            return client.prepareGet(url) {
                headers.append("User-Agent", userAgent)
                headers.append("Accept", "application/json")
                headers.append("Accept-Encoding", "identity")
                timeout { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000; socketTimeoutMillis = 20_000 }
            }.execute { response ->
                // A caller-supplied test client must not hide a redirect from this policy either.
                if (response.call.request.url.toString() != url) releaseFailure(ReleaseFailureCode.REDIRECTED)
                val status = response.status.value
                if (status in 300..399) releaseFailure(ReleaseFailureCode.REDIRECTED, status)
                if (status != 200) return@execute MusicBrainzMetadataResponse(status, null,
                    retryAfter = boundedRetryAfter(response.headers["Retry-After"]))
                val contentType = response.headers["Content-Type"]?.lowercase()?.split(';')?.map { it.trim() }
                if (contentType == null || contentType.firstOrNull() != "application/json" || contentType.drop(1).any {
                        it.substringBefore('=').trim() == "charset" &&
                            ('=' !in it || it.substringAfter('=').trim().trim('"') !in setOf("utf-8", "utf8"))
                    }) releaseFailure(ReleaseFailureCode.INVALID_CONTENT_TYPE)
                val encoding = response.headers["Content-Encoding"]?.trim()?.lowercase()
                if (encoding != null && encoding != "identity") releaseFailure(ReleaseFailureCode.INVALID_ENCODING)
                val rawLength = response.headers["Content-Length"]
                val length = rawLength?.toLongOrNull()
                if (rawLength != null && (length == null || length < 0)) releaseFailure(ReleaseFailureCode.INVALID_RESPONSE)
                if (length != null && length > MusicBrainzReleaseParser.MAX_RESPONSE_BYTES) releaseFailure(ReleaseFailureCode.RESPONSE_LIMIT)
                val channel = response.bodyAsChannel()
                val chunks = ArrayList<ByteArray>()
                var count = 0
                while (true) {
                    val part = ByteArray(minOf(16 * 1024, MusicBrainzReleaseParser.MAX_RESPONSE_BYTES - count + 1))
                    val read = channel.readAvailable(part, 0, part.size)
                    if (read < 0) break
                    if (read == 0) continue
                    count += read
                    if (count > MusicBrainzReleaseParser.MAX_RESPONSE_BYTES) releaseFailure(ReleaseFailureCode.RESPONSE_LIMIT)
                    chunks += if (read == part.size) part else part.copyOf(read)
                }
                if (length != null && length != count.toLong()) releaseFailure(ReleaseFailureCode.INVALID_RESPONSE)
                val bytes = ByteArray(count)
                var offset = 0
                for (part in chunks) { part.copyInto(bytes, offset); offset += part.size }
                val body = try { bytes.decodeToString(throwOnInvalidSequence = true) }
                catch (_: Exception) { releaseFailure(ReleaseFailureCode.INVALID_ENCODING) }
                MusicBrainzMetadataResponse(status, body, MusicBrainzReleaseParser.safeEtag(response.headers["ETag"]))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ReleaseMetadataException) { throw failure }
        catch (_: Exception) { releaseFailure(ReleaseFailureCode.NETWORK) }
    }

    override fun close() { if (ownsClient) client.close() }
}

internal fun musicBrainzUrl(request: MusicBrainzMetadataRequest): String {
    val builder = URLBuilder("https://musicbrainz.org/ws/2/release")
    when (request) {
        is MusicBrainzMetadataRequest.Release -> {
            if (!RELEASE_MBID.matches(request.mbid)) releaseFailure(ReleaseFailureCode.INVALID_RELEASE_ID)
            builder.encodedPath = "/ws/2/release/${request.mbid.lowercase()}"
            builder.parameters.append("inc", "artist-credits+labels+recordings+release-groups")
        }
        is MusicBrainzMetadataRequest.Search -> {
            if (!validReleaseTitle(request.query.text) || request.offset !in 0 until 200 || request.limit !in 1..25 ||
                request.offset + request.limit > 200) releaseFailure(ReleaseFailureCode.INVALID_QUERY)
            builder.parameters.append("query", literalReleaseQuery(request.query))
            builder.parameters.append("limit", request.limit.toString())
            builder.parameters.append("offset", request.offset.toString())
        }
    }
    builder.parameters.append("fmt", "json")
    return builder.buildString()
}

/** Lucene quoting and URL parameter encoding are separate operations. Search is not exact match. */
internal fun literalReleaseQuery(query: ReleaseSearchQuery): String {
    if (!validReleaseTitle(query.text)) releaseFailure(ReleaseFailureCode.INVALID_QUERY)
    val escaped = buildString {
        for (char in query.text) {
            if (char in "+-&|!(){}[]^\"~*?:\\/") append('\\')
            append(char)
        }
    }
    return buildString {
        append("release:\""); append(escaped); append('"')
        if (query.albumsOnly) append(" AND primarytype:album")
        if (query.officialOnly) append(" AND status:official")
    }
}

private fun validVersion(value: String) = Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}").matches(value)

/** Long decimal headers saturate rather than becoming a much shorter fallback cooldown. */
private fun boundedRetryAfter(value: String?): String? {
    val text = value?.trim() ?: return null
    if (text.length <= 256) return text
    if (!text.all { it in '0'..'9' }) return null
    val significant = text.trimStart('0').ifEmpty { "0" }
    return significant.takeIf { it.length <= 256 } ?: Long.MAX_VALUE.toString()
}

/** Preserve long server cooldowns; never shorten one with a client-side maximum. */
@OptIn(ExperimentalTime::class)
internal fun musicBrainzRetryAfterMs(value: String?, nowEpochMs: Long): Long {
    val raw = boundedRetryAfter(value) ?: return 30_000
    if (raw.isNotEmpty() && raw.all { it in '0'..'9' }) {
        var seconds = 0L
        for (char in raw) {
            val digit = char - '0'
            if (seconds > (Long.MAX_VALUE / 1_000 - digit) / 10) return Long.MAX_VALUE
            seconds = seconds * 10 + digit
        }
        return seconds * 1_000
    }
    val imf = Regex("^[A-Za-z]{3}, ([0-9]{2}) ([A-Za-z]{3}) ([0-9]{4}) ([0-9]{2}:[0-9]{2}:[0-9]{2}) GMT$").matchEntire(raw)
    val obsolete = Regex("^[A-Za-z]+, ([0-9]{2})-([A-Za-z]{3})-([0-9]{2}) ([0-9]{2}:[0-9]{2}:[0-9]{2}) GMT$").matchEntire(raw)
    val asctime = Regex("^[A-Za-z]{3} ([A-Za-z]{3}) +([0-9]{1,2}) ([0-9]{2}:[0-9]{2}:[0-9]{2}) ([0-9]{4})$").matchEntire(raw)
    val day: String
    val monthName: String
    var year: String
    val time: String
    when {
        imf != null -> { day = imf.groupValues[1]; monthName = imf.groupValues[2]; year = imf.groupValues[3]; time = imf.groupValues[4] }
        obsolete != null -> {
            day = obsolete.groupValues[1]; monthName = obsolete.groupValues[2]; time = obsolete.groupValues[4]
            val currentYear = Instant.fromEpochMilliseconds(nowEpochMs).toString().take(4).toIntOrNull() ?: return 30_000
            var fullYear = currentYear / 100 * 100 + obsolete.groupValues[3].toInt()
            if (fullYear > currentYear + 50) fullYear -= 100
            year = fullYear.toString().padStart(4, '0')
        }
        asctime != null -> { day = asctime.groupValues[2]; monthName = asctime.groupValues[1]; year = asctime.groupValues[4]; time = asctime.groupValues[3] }
        else -> return 30_000
    }
    val month = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        .indexOfFirst { it.equals(monthName, true) }.takeIf { it >= 0 }?.plus(1) ?: return 30_000
    val target = try { Instant.parse("$year-${month.toString().padStart(2, '0')}-${day.padStart(2, '0')}T${time}Z").toEpochMilliseconds() }
    catch (_: Exception) { return 30_000 }
    return if (target <= nowEpochMs) 0 else if (nowEpochMs < 0 && target > Long.MAX_VALUE + nowEpochMs) Long.MAX_VALUE else target - nowEpochMs
}
