package dev.veyra.downloads

import dev.veyra.model.FailureCode
import dev.veyra.model.OfflineAssetKind
import dev.veyra.model.VeyraException
import dev.veyra.playback.LocalMediaValidationInput
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import java.nio.file.Files
import kotlin.test.*

class DownloadContainerRelocationTest {
    @Test fun historicalContainerReferencesResolveOnlyInsideTheCurrentMediaRoot() = relocationCase {
        assertEquals(files.ownedPath(current), files.ownedPath(historical))
    }

    @Test fun historicalCompletedFileStillRequiresItsExactSizeAndHash() = relocationCase {
        runBlocking {
            assertEquals(files.ownedPath(current), files.verifiedFile(input))
            assertEquals(FailureCode.CORRUPT_MEDIA,
                assertFailsWith<VeyraException> { files.verifiedFile(input.copy(sha256 = "0".repeat(64))) }.failure.code)
        }
    }

    @Test fun currentAndHistoricalNamesShareOneActualFileLease() = relocationCase {
        val lease = assertNotNull(files.tryLeasePaths(listOf(historical)))
        try { assertNull(files.tryLeasePaths(listOf(current))) }
        finally { lease.close() }
        assertNotNull(files.tryLeasePaths(listOf(current))).close()
    }

    @Test fun relocationCannotCrossApplicationNamespacesOrOwnedDirectories() = relocationCase {
        for (path in listOf(
            historical.replace("/Containers/Data/", "/Unrelated/Data/"),
            historical.replace("/Veyra/media/", "/AnotherApp/media/"),
            historical.replace(OLD_CONTAINER, "not-a-container"),
            historical.replace("/files/", "/../files/"),
            "file://$historical",
        )) assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> { files.ownedPath(path) }.failure.code)
    }

    @Test fun anUnrelatedFilesystemRootDoesNotOptIntoContainerRelocation() = relocationCase {
        val unrelated = DownloadFiles(directory.resolve("unrelated").toString())
        assertFailsWith<VeyraException> { unrelated.ownedPath(historical) }
    }

    @Test fun aRelocatedReferenceCannotFollowAnOwnedSymlink() = relocationCase {
        val outside = directory.resolve("outside.authored")
        Files.write(outside, bytes)
        Files.delete(java.nio.file.Path.of(current))
        Files.createSymbolicLink(java.nio.file.Path.of(current), outside)
        assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> { files.ownedPath(historical) }.failure.code)
    }

    private fun relocationCase(block: RelocationFixture.() -> Unit) {
        val fixture = RelocationFixture()
        try { fixture.block() }
        finally { fixture.directory.toFile().deleteRecursively() }
    }

    private class RelocationFixture {
        val directory = downloadTestDirectory()
        val files = DownloadFiles(directory.resolve("Containers/Data/Application/$CURRENT_CONTAINER/Library/Application Support/Veyra/media").toString())
        val bytes = "Authored container-relocation fixture, not audio".encodeToByteArray()
        val current = files.finalPath("authored-recording", 0, "audio/mpeg").toString()
        val historical = current.replace(CURRENT_CONTAINER, OLD_CONTAINER)
        val input = LocalMediaValidationInput(historical, OfflineAssetKind.FILE, bytes.size.toLong(),
            "audio/mpeg", "mp3", bytes.toByteString().sha256().hex(), "authored-representation", 0)

        init {
            files.initialize()
            Files.write(java.nio.file.Path.of(current), bytes)
        }
    }

    private companion object {
        const val CURRENT_CONTAINER = "11111111-1111-4111-8111-111111111111"
        const val OLD_CONTAINER = "22222222-2222-4222-8222-222222222222"
    }
}
