package dev.veyra.source.release

import dev.veyra.source.network.PublicDnsResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class MusicBrainzMetadataHttpTest {
    private val f = ReleaseMetadataFixtures
    private val json = headersOf("Content-Type", "application/json; charset=utf-8")
    private fun client(engine: MockEngine) = HttpClient(engine) { followRedirects = false; expectSuccess = false; install(HttpTimeout) }
    private val dns = PublicDnsResolver { listOf("93.184.216.34") }
    private fun request() = MusicBrainzMetadataRequest.Release(f.releaseId)

    @Test fun literalQueryEscapingAndUrlEncodingAreSeparateAndPreserveUnicode() {
        val query = ReleaseSearchQuery("  أمواج + release:\"wrong\" OR *:* & ? / \\ ", albumsOnly = true, officialOnly = true)
        val literal = literalReleaseQuery(query)
        assertTrue(literal.startsWith("release:\"  أمواج \\+"))
        assertTrue(literal.contains("\\\"wrong\\\""))
        assertTrue(literal.contains("\\*\\:\\*"))
        assertTrue(literal.endsWith(" AND primarytype:album AND status:official"))
        val url = Url(musicBrainzUrl(MusicBrainzMetadataRequest.Search(query, 25, 25)))
        assertEquals("https", url.protocol.name)
        assertEquals("musicbrainz.org", url.host)
        assertEquals(literal, url.parameters["query"])
        assertEquals("25", url.parameters["offset"])
        assertEquals("json", url.parameters["fmt"])
        assertNull(url.user)
    }

    @Test fun requestUsesOnlyFixedHttpsMetadataEndpointAndContactUserAgent() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https", request.url.protocol.name)
            assertEquals("musicbrainz.org", request.url.host)
            assertEquals("/ws/2/release/${f.releaseId}", request.url.encodedPath)
            assertEquals("artist-credits+labels+recordings+release-groups", request.url.parameters["inc"])
            assertEquals("Veyra/0.1-test (https://github.com/Apdelrahman1911/veyra)", request.headers["User-Agent"])
            assertEquals("identity", request.headers["Accept-Encoding"])
            for (name in listOf("Cookie", "Authorization", "Referer", "Range", "Origin")) assertNull(request.headers[name])
            respond(f.release().toString(), HttpStatusCode.OK, json)
        }
        val http = KtorMusicBrainzMetadataTransport(client(engine), dns, "0.1-test")
        try {
            var checks = 0
            val result = http.get(request()) { checks++ }
            assertEquals(200, result.status)
            assertEquals(2, checks)
            assertEquals(1, engine.requestHistory.size)
        } finally { http.close() }
    }

    @Test fun malformedIdsAndTitlesCannotReachDnsOrHttp() = runTest {
        var dnsCalls = 0
        val engine = MockEngine { error("No HTTP permitted") }
        val http = KtorMusicBrainzMetadataTransport(client(engine), PublicDnsResolver { dnsCalls++; emptyList() }, "test")
        try {
            assertEquals(ReleaseFailureCode.INVALID_RELEASE_ID, assertFailsWith<ReleaseMetadataException> {
                http.get(MusicBrainzMetadataRequest.Release("../../media")) {}
            }.code)
            assertEquals(ReleaseFailureCode.INVALID_QUERY, assertFailsWith<ReleaseMetadataException> {
                http.get(MusicBrainzMetadataRequest.Search(ReleaseSearchQuery("x".repeat(16_385)), 0, 25)) {}
            }.code)
            assertEquals(0, dnsCalls)
            assertTrue(engine.requestHistory.isEmpty())
        } finally { http.close() }
    }

    @Test fun anyPrivateDnsAnswerPreventsTheFixedOriginRequest() = runTest {
        val engine = MockEngine { error("No HTTP permitted") }
        val http = KtorMusicBrainzMetadataTransport(client(engine), PublicDnsResolver { listOf("93.184.216.34", "127.0.0.1") }, "test")
        try {
            assertEquals(ReleaseFailureCode.NON_PUBLIC_ORIGIN, assertFailsWith<ReleaseMetadataException> { http.get(request()) {} }.code)
            assertTrue(engine.requestHistory.isEmpty())
        } finally { http.close() }
    }

    @Test fun offlineChangeDuringDnsIsRecheckedBeforeHttp() = runTest {
        var offline = false
        val engine = MockEngine { error("No HTTP permitted") }
        val http = KtorMusicBrainzMetadataTransport(client(engine), PublicDnsResolver { offline = true; listOf("93.184.216.34") }, "test")
        try {
            assertEquals(ReleaseFailureCode.OFFLINE, assertFailsWith<ReleaseMetadataException> {
                http.get(request()) { if (offline) releaseFailure(ReleaseFailureCode.OFFLINE) }
            }.code)
            assertTrue(engine.requestHistory.isEmpty())
        } finally { http.close() }
    }

    @Test fun redirectsCannotBecomeAnAlternativeProviderOrMediaFetch() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Found, headersOf("Location", "https://example.com/audio")) }
        val http = KtorMusicBrainzMetadataTransport(client(engine), dns, "test")
        try {
            assertEquals(ReleaseFailureCode.REDIRECTED, assertFailsWith<ReleaseMetadataException> { http.get(request()) {} }.code)
            assertEquals(1, engine.requestHistory.size)
        } finally { http.close() }
    }

    @Test fun declaredAndStreamedResponseCapsAreBothEnforced() = runTest {
        for (declared in listOf(true, false)) {
            val headers = Headers.build {
                append("Content-Type", "application/json")
                if (declared) append("Content-Length", (MusicBrainzReleaseParser.MAX_RESPONSE_BYTES + 1).toString())
            }
            val engine = MockEngine { respond(if (declared) "{}" else "x".repeat(MusicBrainzReleaseParser.MAX_RESPONSE_BYTES + 1), HttpStatusCode.OK, headers) }
            val http = KtorMusicBrainzMetadataTransport(client(engine), dns, "test")
            try { assertEquals(ReleaseFailureCode.RESPONSE_LIMIT, assertFailsWith<ReleaseMetadataException> { http.get(request()) {} }.code) }
            finally { http.close() }
        }
    }

    @Test fun malformedUtf8AndNonJsonOrEncodedResponsesAreRejected() = runTest {
        val cases = listOf(
            Triple(ByteArray(2).apply { this[0] = 0xc3.toByte(); this[1] = 0x28 }, json, ReleaseFailureCode.INVALID_ENCODING),
            Triple("<html/>".encodeToByteArray(), headersOf("Content-Type", "text/html"), ReleaseFailureCode.INVALID_CONTENT_TYPE),
            Triple("{}".encodeToByteArray(), headersOf("Content-Type", "application/json; charset = iso-8859-1"), ReleaseFailureCode.INVALID_CONTENT_TYPE),
            Triple("{}".encodeToByteArray(), Headers.build { append("Content-Type", "application/json"); append("Content-Encoding", "gzip") }, ReleaseFailureCode.INVALID_ENCODING),
        )
        for ((body, headers, failure) in cases) {
            val engine = MockEngine { respond(ByteReadChannel(body), HttpStatusCode.OK, headers) }
            val http = KtorMusicBrainzMetadataTransport(client(engine), dns, "test")
            try { assertEquals(failure, assertFailsWith<ReleaseMetadataException> { http.get(request()) {} }.code) }
            finally { http.close() }
        }
    }

    @Test fun truncatedDeclaredResponseIsNotAcceptedAsCompleteJson() = runTest {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, Headers.build {
            append("Content-Type", "application/json"); append("Content-Length", "10")
        }) }
        val http = KtorMusicBrainzMetadataTransport(client(engine), dns, "test")
        try { assertEquals(ReleaseFailureCode.INVALID_RESPONSE, assertFailsWith<ReleaseMetadataException> { http.get(request()) {} }.code) }
        finally { http.close() }
    }

    @Test fun throttledBodiesAreNotExposedOrTreatedAsMetadata() = runTest {
        for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)) {
            for ((header, expected) in listOf("90000" to "90000", "9".repeat(300) to Long.MAX_VALUE.toString(),
                    "0".repeat(300) + "2" to "2")) {
                val engine = MockEngine { respond("provider-private-error-page", status, headersOf("Retry-After", header)) }
                val http = KtorMusicBrainzMetadataTransport(client(engine), dns, "test")
                try {
                    val result = http.get(request()) {}
                    assertEquals(status.value, result.status)
                    assertEquals(expected, result.retryAfter)
                    assertNull(result.body)
                    assertFalse(result.toString().contains("provider-private"))
                } finally { http.close() }
            }
        }
    }

    @Test fun retryAfterHonorsSecondsHttpDatesAndLongLimitsWithoutShortening() {
        val now = Instant.parse("2026-11-06T10:00:00Z").toEpochMilliseconds()
        assertEquals(30_000L, musicBrainzRetryAfterMs(null, now))
        assertEquals(30_000L, musicBrainzRetryAfterMs("invalid", now))
        assertEquals(0L, musicBrainzRetryAfterMs("0", now))
        assertEquals(90_000_000L, musicBrainzRetryAfterMs("90000", now))
        assertEquals(Long.MAX_VALUE, musicBrainzRetryAfterMs("999999999999999999999999999", now))
        assertEquals(Long.MAX_VALUE, musicBrainzRetryAfterMs("9".repeat(300), now))
        assertEquals(2_000L, musicBrainzRetryAfterMs("0".repeat(300) + "2", now))
        assertEquals(120_000L, musicBrainzRetryAfterMs("Fri, 06 Nov 2026 10:02:00 GMT", now))
        assertEquals(120_000L, musicBrainzRetryAfterMs("Friday, 06-Nov-26 10:02:00 GMT", now))
        assertEquals(120_000L, musicBrainzRetryAfterMs("Fri Nov  6 10:02:00 2026", now))
        assertEquals(0L, musicBrainzRetryAfterMs("Fri, 06 Nov 2026 09:59:59 GMT", now))
    }

    @Test fun cancellationPropagatesButOtherTransportMessagesAreSanitized() = runTest {
        val engine = MockEngine { error("Never called") }
        val cancelled = KtorMusicBrainzMetadataTransport(client(engine), PublicDnsResolver { throw CancellationException("authored cancellation") }, "test")
        try { assertFailsWith<CancellationException> { cancelled.get(request()) {} } }
        finally { cancelled.close() }
        val failedEngine = MockEngine { error("authored-private-transcript") }
        val failed = KtorMusicBrainzMetadataTransport(client(failedEngine), dns, "test")
        try {
            val failure = assertFailsWith<ReleaseMetadataException> { failed.get(request()) {} }
            assertEquals(ReleaseFailureCode.NETWORK, failure.code)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains("authored-private"))
        } finally { failed.close() }
    }
}
