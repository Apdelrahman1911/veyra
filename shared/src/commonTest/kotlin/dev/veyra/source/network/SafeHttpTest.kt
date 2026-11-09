package dev.veyra.source.network

import dev.veyra.model.FailureCode
import dev.veyra.model.AppFailure
import dev.veyra.model.VeyraException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SafeHttpTest {
    private fun client(engine: MockEngine) = HttpClient(engine) { followRedirects = false; install(HttpTimeout) }
    private val publicDns = PublicDnsResolver { listOf("93.184.216.34") }

    @Test fun rejectsPrivateDnsBeforeAnyHttpRequest() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond("should not run") }
        val http = KtorSafeHttp(client(engine), PublicDnsResolver { listOf("93.184.216.34", "127.0.0.1") })
        try {
            val failure = assertFailsWith<VeyraException> { http.get(SafeHttpRequest("https://example.com/feed")) }
            assertEquals(FailureCode.UNSUPPORTED_SOURCE, failure.failure.code)
            assertEquals(0, calls)
        } finally { http.close() }
    }

    @Test fun redirectToPrivateHostIsRejectedAndCredentialsNeverForwarded() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Found, headersOf("Location", "https://127.0.0.1/secret")) }
        val http = KtorSafeHttp(client(engine), publicDns)
        try {
            assertFailsWith<VeyraException> { http.get(SafeHttpRequest("https://example.com/feed")) }
            assertEquals(1, engine.requestHistory.size)
            assertFailsWith<VeyraException> { http.get(SafeHttpRequest("https://example.com/feed", mapOf("Authorization" to "not-allowed"))) }
            assertEquals(1, engine.requestHistory.size)
        } finally { http.close() }
    }

    @Test fun preservesHeadAndDropsCrossOriginValidators() = runTest {
        val engine = MockEngine { request ->
            if (request.url.host == "example.com") respond("", HttpStatusCode.Found, headersOf("Location", "https://cdn.example.com/audio"))
            else {
                assertEquals(HttpMethod.Head, request.method)
                assertNull(request.headers["If-Range"])
                assertEquals("bytes=50-", request.headers["Range"])
                respond("", HttpStatusCode.OK)
            }
        }
        val http = KtorSafeHttp(client(engine), publicDns)
        try {
            val response = http.get(SafeHttpRequest("https://example.com/audio", mapOf("Range" to "bytes=50-", "If-Range" to "\"v1\""), SafeHttpMethod.HEAD))
            assertEquals(200, response.response.status)
            assertEquals(2, engine.requestHistory.size)
        } finally { http.close() }
    }

    @Test fun boundedMetadataDoesNotReadAnUnboundedBody() = runTest {
        val engine = MockEngine { respond("x".repeat(100)) }
        val http = KtorSafeHttp(client(engine), publicDns)
        try {
            val failure = assertFailsWith<VeyraException> { http.get(SafeHttpRequest("https://example.com/feed"), 32) }
            assertEquals(FailureCode.PARSE_FAILURE, failure.failure.code)
        } finally { http.close() }
    }

    @Test fun rateLimitIsTypedAndSubsequentCallsDoNotRetryUntilBackoff() = runTest {
        var now = 0L
        val engine = MockEngine { respond("", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "120")) }
        val http = KtorSafeHttp(client(engine), publicDns, nowMs = { now })
        try {
            repeat(2) {
                val failure = assertFailsWith<VeyraException> { http.get(SafeHttpRequest("https://example.com/feed")) }
                assertEquals(FailureCode.RATE_LIMITED, failure.failure.code)
                assertEquals(120_000L, failure.failure.retryAfterMs)
            }
            assertEquals(1, engine.requestHistory.size)
            now = 120_001
            assertFailsWith<VeyraException> { http.get(SafeHttpRequest("https://example.com/feed")) }
            assertEquals(2, engine.requestHistory.size)
        } finally { http.close() }
    }

    @Test fun streamingExposes416ForDownloaderInsteadOfPretendingCompletion() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.RequestedRangeNotSatisfiable) }
        val http = KtorSafeHttp(client(engine), publicDns)
        try {
            val status = http.stream(SafeHttpRequest("https://example.com/audio", mapOf("Range" to "bytes=999-"))) { meta, _ -> meta.status }
            assertEquals(416, status)
        } finally { http.close() }
    }

    @Test fun requestPolicyIsRecheckedBeforeRedirectDnsOrHttp() = runTest {
        var allowed = true
        val dnsHosts = mutableListOf<String>()
        val engine = MockEngine {
            allowed = false
            respond("", HttpStatusCode.Found, headersOf("Location", "https://other.example.com/image"))
        }
        val http = KtorSafeHttp(client(engine), PublicDnsResolver { dnsHosts += it; listOf("93.184.216.34") })
        try {
            assertEquals(FailureCode.OFFLINE_POLICY, assertFailsWith<VeyraException> {
                http.get(SafeHttpRequest("https://example.com/image", beforeRequest = {
                    if (!allowed) throw VeyraException(AppFailure(FailureCode.OFFLINE_POLICY))
                }))
            }.failure.code)
            assertEquals(listOf("example.com"), dnsHosts)
            assertEquals(1, engine.requestHistory.size)
        } finally { http.close() }
    }

    @Test fun policyChangeDuringDnsPreflightPreventsTheHttpRequest() = runTest {
        var allowed = true
        val engine = MockEngine { error("Policy must block before this request") }
        val http = KtorSafeHttp(client(engine), PublicDnsResolver { allowed = false; listOf("93.184.216.34") })
        try {
            assertEquals(FailureCode.OFFLINE_POLICY, assertFailsWith<VeyraException> {
                http.get(SafeHttpRequest("https://example.com/image", beforeRequest = {
                    if (!allowed) throw VeyraException(AppFailure(FailureCode.OFFLINE_POLICY))
                }))
            }.failure.code)
            assertTrue(engine.requestHistory.isEmpty())
        } finally { http.close() }
    }
}
