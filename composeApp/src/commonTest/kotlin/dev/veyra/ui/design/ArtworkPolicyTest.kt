package dev.veyra.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtworkPolicyTest {
    private val remote = "https://example.invalid/cover.jpg"

    @Test fun offlineNeverPassesAUrlToTheImageLoader() {
        listOf(remote, "http://example.invalid/cover.jpg", "//example.invalid/cover.jpg").forEach {
            assertNull(artworkModel(it, offlineOnly = true, localPath = null))
            assertNull(artworkModel(remote, offlineOnly = true, localPath = it))
            assertNull(artworkModel(it, offlineOnly = true, localPath = null, remoteAllowed = true))
            assertNull(artworkModel(remote, offlineOnly = true, localPath = it, remoteAllowed = true))
        }
    }

    @Test fun productionDefaultsToOwnedArtworkEvenWhileOnline() {
        assertNull(artworkModel(remote, offlineOnly = false, localPath = null))
        assertNull(artworkModel(remote, offlineOnly = false, localPath = "smb://host/cover"))
        assertNull(artworkModel(remote, offlineOnly = false, localPath = remote))
        assertNull(artworkModel(remote, offlineOnly = false, localPath = "content://cloud/document/cover"))
    }

    @Test fun persistedFilesTakePriorityAndRemainAvailableOffline() {
        listOf(
            "/app/files/artwork/cover.jpg",
            "artwork/cover.jpg",
            "file:///app/files/artwork/cover.jpg",
            "file:/app/files/artwork/cover.jpg",
            "file://localhost/app/files/artwork/cover.jpg",
            "FILE://LOCALHOST/app/files/artwork/cover.jpg",
            "C:\\Veyra\\artwork\\cover.jpg",
            "C:/Veyra/artwork/cover.jpg",
            "file:///C:/Veyra/artwork/cover.jpg",
            "android.resource://dev.veyra.app/drawable/cover",
        ).forEach { path ->
            assertEquals(path, artworkModel(remote, offlineOnly = true, localPath = path), path)
            assertEquals(path, artworkModel(remote, offlineOnly = false, localPath = path), path)
        }
    }

    @Test fun networkFileAuthoritiesAndUncPathsAreNotLocalEvenWhenEncoded() {
        listOf(
            "file://remote-host/share/cover.jpg",
            "file://user@localhost/share/cover.jpg",
            "file://localhost:445/share/cover.jpg",
            "file:////remote-host/share/cover.jpg",
            "file:///%5C%5Cremote-host/share/cover.jpg",
            "file://localhost//remote-host/share/cover.jpg",
            "\\\\remote-host\\share\\cover.jpg",
            "//remote-host/share/cover.jpg",
            "/\\remote-host/share/cover.jpg",
            "%2F%2Fremote-host/share/cover.jpg",
            "%5C%5Cremote-host/share/cover.jpg",
        ).forEach { path ->
            assertNull(artworkModel(null, offlineOnly = true, localPath = path), path)
            assertNull(artworkModel(null, offlineOnly = false, localPath = path), path)
        }
    }

    @Test fun contentProvidersNeedRemotePermissionAndAreNeverAllowedOffline() {
        val provider = "content://example.cloud.documents/document/cover"
        assertNull(artworkModel(remote, offlineOnly = true, localPath = provider))
        assertNull(artworkModel(remote, offlineOnly = false, localPath = provider))
        assertNull(artworkModel(remote, offlineOnly = true, localPath = provider, remoteAllowed = true))
        assertEquals(provider, artworkModel(remote, offlineOnly = false, localPath = provider, remoteAllowed = true))
    }

    @Test fun unsupportedSchemesAndControlCharactersNeverBecomeLocalModels() {
        listOf("data:image/png;base64,AA", "ftp://host/cover", "smb://host/cover", "javascript:alert(1)", "/app/cover\u0000.jpg", "file:///app/%00cover.jpg").forEach {
            assertNull(artworkModel(null, offlineOnly = true, localPath = it))
        }
        assertNull(artworkModel("data:image/png;base64,AA", offlineOnly = false, localPath = null))
        assertNull(artworkModel(null, offlineOnly = false, localPath = "  "))
    }

    @Test fun isolatedTestsMustExplicitlyOptIntoOnlineArtwork() {
        assertEquals(remote, artworkModel("  $remote  ", offlineOnly = false, localPath = null, remoteAllowed = true))
        assertEquals(remote, artworkModel(remote, offlineOnly = false, localPath = "smb://host/cover", remoteAllowed = true))
        assertEquals("http://example.invalid/cover.jpg", artworkModel("http://example.invalid/cover.jpg", false, null, remoteAllowed = true))
    }
}
