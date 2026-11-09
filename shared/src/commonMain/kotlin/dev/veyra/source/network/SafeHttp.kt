package dev.veyra.source.network

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

enum class SafeHttpMethod { GET, HEAD }

/** Ephemeral request. Do not serialize or log source URLs or validators. */
class SafeHttpRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val method: SafeHttpMethod = SafeHttpMethod.GET,
    val timeoutMs: Long? = 30_000,
    /** Rechecked before DNS and before EACH request/redirect; e.g. persisted offline policy. */
    val beforeRequest: suspend () -> Unit = {},
) {
    override fun toString(): String = "SafeHttpRequest(method=$method)"
}

class SafeHttpResponse(
    val status: Int,
    /** Names are lowercase. Values remain opaque; do not publish raw headers. */
    val headers: Map<String, String>,
    val finalUrl: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]
    override fun toString(): String = "SafeHttpResponse(status=$status)"
}

class SafeHttpBytes(val response: SafeHttpResponse, val bytes: ByteArray) {
    fun text(): String = try { bytes.decodeToString(throwOnInvalidSequence = true) }
        catch (_: Exception) { sourceFailure(FailureCode.PARSE_FAILURE, "invalid_text_encoding") }
    override fun toString(): String = "SafeHttpBytes(status=${response.status},size=${bytes.size})"
}

/** The response/channel cannot outlive [stream]'s callback. Never buffer media with [get]. */
interface SafeHttp {
    suspend fun get(request: SafeHttpRequest, maxBytes: Int = DEFAULT_METADATA_LIMIT): SafeHttpBytes
    suspend fun <T> stream(
        request: SafeHttpRequest,
        consume: suspend (SafeHttpResponse, ByteReadChannel) -> T,
    ): T
    suspend fun validateUrl(url: String): String
    fun close()

    companion object { const val DEFAULT_METADATA_LIMIT: Int = 8 * 1024 * 1024 }
}

fun interface PublicDnsResolver { suspend fun resolve(host: String): List<String> }

internal expect fun platformHttpClient(): HttpClient
internal expect suspend fun platformResolveHost(host: String): List<String>

fun createSafeHttp(): SafeHttp = KtorSafeHttp(platformHttpClient(), PublicDnsResolver(::platformResolveHost))

/**
 * HTTPS + public-DNS preflight at every hop, bounded redirects, no credentials or cross-host
 * secrets, and no automatic retries. TLS verification remains enabled in each platform engine.
 *
 * DNS is checked before the engine connects. CIO/Android/Darwin and native players can resolve
 * again: this is NOT a claim of atomic DNS pinning or absolute DNS-rebinding protection.
 */
@OptIn(ExperimentalTime::class)
class KtorSafeHttp(
    private val client: HttpClient,
    private val dns: PublicDnsResolver,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val ownsClient: Boolean = true,
) : SafeHttp {
    private val rateLock = Mutex()
    private val blockedUntil = linkedMapOf<String, Long>()

    override suspend fun validateUrl(url: String): String {
        val normalized = PublicUrlPolicy.normalize(url)
        val addresses = try { dns.resolve(Url(normalized).host) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { sourceFailure(FailureCode.NETWORK_UNAVAILABLE, "dns_lookup_failed") }
        if (addresses.isEmpty()) sourceFailure(FailureCode.NETWORK_UNAVAILABLE, "dns_no_addresses")
        if (addresses.any { !PublicUrlPolicy.isPublicAddress(it) }) {
            sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "non_public_network_origin")
        }
        return normalized
    }

    override suspend fun get(request: SafeHttpRequest, maxBytes: Int): SafeHttpBytes {
        require(maxBytes in 1..16 * 1024 * 1024)
        return stream(request) { meta, channel ->
            requireHttpSuccess(meta, allowNotModified = true)
            if (meta.status == 304 || request.method == SafeHttpMethod.HEAD) {
                return@stream SafeHttpBytes(meta, ByteArray(0))
            }
            val encoding = meta.header("content-encoding")?.trim()?.lowercase()
            if (encoding != null && encoding != "identity") {
                sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "unsupported_content_encoding")
            }
            val length = meta.header("content-length")?.toLongOrNull()
            if (length != null && length > maxBytes) sourceFailure(FailureCode.PARSE_FAILURE, "metadata_too_large")
            val chunks = ArrayList<ByteArray>()
            var size = 0
            while (true) {
                val chunk = ByteArray(minOf(16 * 1024, maxBytes - size + 1))
                val count = channel.readAvailable(chunk, 0, chunk.size)
                if (count < 0) break
                if (count == 0) continue
                size += count
                if (size > maxBytes) sourceFailure(FailureCode.PARSE_FAILURE, "metadata_too_large")
                chunks += if (count == chunk.size) chunk else chunk.copyOf(count)
            }
            val bytes = ByteArray(size)
            var offset = 0
            for (chunk in chunks) { chunk.copyInto(bytes, offset); offset += chunk.size }
            SafeHttpBytes(meta, bytes)
        }
    }

    override suspend fun <T> stream(
        request: SafeHttpRequest,
        consume: suspend (SafeHttpResponse, ByteReadChannel) -> T,
    ): T {
        var requestHeaders = checkedHeaders(request.headers)
        require(request.timeoutMs == null || request.timeoutMs in 1..3_600_000)
        var current = request.url
        val visited = mutableSetOf<String>()
        try {
            repeat(6) { hop ->
                request.beforeRequest()
                current = validateUrl(current)
                if (!visited.add(current)) sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "redirect_cycle")
                val host = Url(current).host.lowercase()
                val waitMs = rateLock.withLock { (blockedUntil[host] ?: 0) - nowMs() }
                if (waitMs > 0) throw VeyraException(AppFailure(FailureCode.RATE_LIMITED, retryAfterMs = waitMs))
                request.beforeRequest()
                val attempt = client.prepareRequest(current) {
                    method = if (request.method == SafeHttpMethod.HEAD) HttpMethod.Head else HttpMethod.Get
                    headers.append("User-Agent", "Veyra/0.1 (public music and podcast client)")
                    headers.append("Accept-Encoding", "identity")
                    requestHeaders.forEach { (name, value) -> headers.append(name, value) }
                    timeout {
                        requestTimeoutMillis = request.timeoutMs ?: HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        connectTimeoutMillis = 10_000
                        socketTimeoutMillis = 20_000
                    }
                }.execute { response ->
                    val status = response.status.value
                    val headers = response.headers.entries().associate { it.key.lowercase() to it.value.joinToString(",") }
                    val meta = SafeHttpResponse(status, headers, current)
                    if (status == 429) {
                        val delayMs = ((headers["retry-after"]?.trim()?.toLongOrNull() ?: 60) * 1_000)
                            .coerceIn(1_000, 15 * 60_000)
                        rateLock.withLock {
                            if (blockedUntil.size >= 128) blockedUntil.remove(blockedUntil.keys.first())
                            blockedUntil[host] = nowMs() + delayMs
                        }
                        throw VeyraException(AppFailure(FailureCode.RATE_LIMITED, retryAfterMs = delayMs))
                    }
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        if (hop == 5) sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "too_many_redirects")
                        val location = headers["location"] ?: sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "redirect_without_location")
                        Attempt.Redirect(URLBuilder(current).takeFrom(location).buildString())
                    } else {
                        Attempt.Value(consume(meta, response.bodyAsChannel()))
                    }
                }
                when (attempt) {
                    is Attempt.Redirect -> {
                        val next = PublicUrlPolicy.normalize(attempt.url)
                        if (Url(current).host != Url(next).host) {
                            requestHeaders = requestHeaders.filterKeys { !it.startsWith("if-", true) }
                        }
                        current = next
                    }
                    is Attempt.Value -> return attempt.value
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: VeyraException) { throw failure }
        catch (_: Exception) { sourceFailure(FailureCode.NETWORK_UNAVAILABLE, "http_transport_failed") }
        sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "too_many_redirects")
    }

    override fun close() { if (ownsClient) client.close() }

    private sealed interface Attempt<out T> {
        data class Redirect(val url: String) : Attempt<Nothing>
        data class Value<T>(val value: T) : Attempt<T>
    }
}

fun requireHttpSuccess(response: SafeHttpResponse, allowNotModified: Boolean = false) {
    if (response.status in 200..299 || (allowNotModified && response.status == 304)) return
    sourceFailure(when (response.status) {
        401, 403 -> FailureCode.PROVIDER_ACCESS_REQUIRED
        404, 410 -> FailureCode.NOT_FOUND
        429 -> FailureCode.RATE_LIMITED
        else -> FailureCode.NETWORK_UNAVAILABLE
    }, "http_${response.status}")
}

internal fun checkedHeaders(headers: Map<String, String>): Map<String, String> {
    val allowed = setOf("accept", "accept-language", "range", "if-range", "if-none-match", "if-modified-since")
    if (headers.any { (name, value) -> name.lowercase() !in allowed || value.length > 2_048 || value.any { it.code < 32 || it.code == 127 } }) {
        sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "unsafe_request_headers")
    }
    val range = headers.entries.firstOrNull { it.key.equals("range", true) }?.value
    if (range != null && !Regex("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)").matches(range)) {
        sourceFailure(FailureCode.UNSUPPORTED_SOURCE, "unsupported_range")
    }
    return headers
}

internal fun sourceFailure(code: FailureCode, detail: String): Nothing = throw VeyraException(AppFailure(code, detail))
