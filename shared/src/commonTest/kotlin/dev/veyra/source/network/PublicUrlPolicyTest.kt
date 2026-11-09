package dev.veyra.source.network

import dev.veyra.model.VeyraException
import kotlin.test.*

class PublicUrlPolicyTest {
    @Test fun publicHttpsIsNormalizedWithoutChangingResourceQuery() {
        assertEquals("https://example.com/audio.mp3?part=1", PublicUrlPolicy.normalize("https://Example.com/audio.mp3?part=1#position"))
    }

    @Test fun rejectsCredentialsPortsLocalAndAmbiguousOrigins() {
        listOf("http://example.com/a", "file:///etc/passwd", "https://user:secret@example.com/a",
            "https://example.com:8443/a", "https://127.0.0.1/a", "https://2130706433/a", "https://0x7f000001/a",
            "https://[::1]/a", "https://localhost/a", "https://metadata.google.internal/a", "https://router.local/a",
            "https://example.com/\r\nHeader:value", "https://example.com@localhost/a").forEach {
            assertFailsWith<VeyraException>(it) { PublicUrlPolicy.normalize(it) }
        }
    }

    @Test fun rejectsPrivateReservedMappedAndLinkLocalDnsAnswers() {
        listOf("0.0.0.0", "10.1.2.3", "127.0.0.2", "169.254.169.254", "172.16.1.1", "172.31.1.1",
            "192.168.1.1", "100.64.0.1", "192.0.2.3", "198.51.100.4", "203.0.113.1", "198.18.1.1", "224.0.0.1",
            "::", "::1", "::ffff:127.0.0.1", "::ffff:8.8.8.8", "fe80::1", "fc00::1", "2001:db8::1",
            "2002:7f00:1::", "2001::1", "fe80::1%en0", "not-an-ip").forEach { assertFalse(PublicUrlPolicy.isPublicAddress(it), it) }
        listOf("8.8.8.8", "93.184.216.34", "172.15.0.1", "2606:4700:4700::1111", "2001:4860:4860::8888").forEach {
            assertTrue(PublicUrlPolicy.isPublicAddress(it), it)
        }
    }

    @Test fun stableDigestMatchesSha256KnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sourceDigest(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sourceDigest("abc"))
    }
}
