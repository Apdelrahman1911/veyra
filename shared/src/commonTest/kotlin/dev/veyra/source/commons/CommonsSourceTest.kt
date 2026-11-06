package dev.veyra.source.commons

import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.network.KtorSafeHttp
import dev.veyra.source.network.PublicDnsResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class CommonsSourceTest {
    private val rights = "{{Own}} [[User:La Pianista|La Pianista]] ===Composition=== {{PD-old-100}} ===Performance=== {{self|Cc-zero}}"
    private fun response(license: String = rights, derivative: String? = "https://upload.wikimedia.org/wikipedia/commons/transcoded/test.mp3") = buildJsonObject {
        putJsonObject("query") { putJsonObject("pages") { putJsonObject("13393751") {
            put("pageid", 13393751); put("title", "File:Beethoven - 32 Variations in C Minor, WoO 80.ogg")
            putJsonArray("revisions") { addJsonObject { putJsonObject("slots") { putJsonObject("main") { put("*", license) } } } }
            putJsonArray("videoinfo") { addJsonObject {
                put("duration", 728.367); put("mime", "application/ogg")
                putJsonArray("derivatives") { if (derivative != null) addJsonObject { put("type", "audio/mpeg"); put("src", derivative) } }
            } }
        } } }
    }.toString()

    @Test fun retainsReviewedRecordingDiscoveryRightsWithoutClaimingAnAudioOrigin() {
        val record = CommonsSource.parseRecord(response(), "13393751")
        assertEquals("Beethoven - 32 Variations in C Minor, WoO 80", record.item.title)
        assertEquals(728_367L, record.item.durationMs)
        assertTrue(record.item.attribution!!.licenseName!!.contains("CC0"))
        assertNull(record.item.source.mimeType)
        assertNull(record.item.source.enclosureUrl)
    }

    @Test fun oldCompositionAloneIsNotPermissionForARecording() {
        listOf("{{PD-old-100}}", rights.replace("{{self|Cc-zero}}", "All rights reserved"), rights.replace("{{Own}}", "Unknown source")).forEach {
            val error = assertFailsWith<VeyraException> { CommonsSource.parseRecord(response(it), "13393751") }
            assertEquals(FailureCode.PERMISSION_DENIED, error.failure.code)
        }
    }

    @Test fun metadataDiscoveryDoesNotRequireOrRelayAnyDirectDerivative() {
        listOf(null, "https://evil.example.com/fake.mp3", "http://127.0.0.1/private").forEach { derivative ->
            val item = CommonsSource.parseRecord(response(derivative = derivative), "13393751").item
            assertNull(item.source.enclosureUrl)
            assertNull(item.source.mimeType)
            assertEquals("13393751", item.source.canonicalId)
        }
        assertEquals(FailureCode.PERMISSION_DENIED, assertFailsWith<VeyraException> {
            CommonsSource.parseRecord(response(), "999999")
        }.failure.code)
    }

    @Test fun commonsNeverProvidesADirectPlaybackOrDownloadFallback() = runTest {
        var networkCalls = 0
        var dnsCalls = 0
        val engine = MockEngine { networkCalls++; respond("must not acquire or refresh") }
        val http = KtorSafeHttp(HttpClient(engine) { followRedirects = false; install(HttpTimeout) },
            PublicDnsResolver { dnsCalls++; listOf("93.184.216.34") })
        val source = CommonsSource(http)
        val item = CommonsSource.parseRecord(response(), "13393751").item
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

    @Test fun realSearchIdsAreNotInventedFromQueryText() {
        assertEquals(listOf("12", "13393751"), CommonsSource.parseSearchIds("""{"query":{"search":[{"pageid":12},{"pageid":13393751},{"pageid":12}]}}"""))
        assertFailsWith<VeyraException> { CommonsSource.parseSearchIds("{\"error\":{}}") }
    }
}
