package dev.veyra.desktop

import dev.veyra.desktop.runtime.DesktopHelperManifests
import dev.veyra.desktop.runtime.DesktopNativeRuntime
import dev.veyra.desktop.runtime.HelperFile
import dev.veyra.desktop.runtime.HelperManifest
import dev.veyra.desktop.runtime.SafeHelperArchive
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Authored archives only: these test installer security, not native helper execution. */
class DesktopHelperArchiveTest {
    @Test fun allPackagedInventoriesRetainTheVerifiedArchiveAndExecutablePins() {
        for (platform in listOf("linux-x64", "windows-x64", "macos-x64", "macos-arm64")) {
            for (component in listOf("node", "chromium")) {
                val manifest = DesktopHelperManifests.load(component, platform)
                assertEquals(platform, manifest.platform)
                assertTrue(manifest.entries.any { it.path == manifest.executable && it.executable })
                if (component == "node") assertEquals(2, manifest.entries.size, "Node closure is binary plus its complete combined license")
                if (platform.startsWith("macos") && component == "chromium") assertEquals(5, manifest.entries.count { it.link != null })
            }
        }
    }

    @Test fun boundedZipAndTarPreservePinnedFilesAndContainLateFrameworkLinks() = runBlocking {
        for (format in listOf("zip", "tar.gz")) withHelperWork { root ->
            val specs = mutableListOf(ArchiveFixtureEntry("bundle/bin/tool", "authored helper".encodeToByteArray(), 0x81ed))
            if (!System.getProperty("os.name").startsWith("Windows", true)) specs += ArchiveFixtureEntry("bundle/current", link = "bin")
            val fixture = helperArchive(root, format, specs)
            val stage = root.resolve("stage")
            SafeHelperArchive.extract(fixture.first, stage, fixture.second)
            assertEquals("authored helper", Files.readString(stage.resolve("bin/tool")))
            assertTrue(Files.isExecutable(stage.resolve("bin/tool")))
            if (specs.size > 1) {
                assertTrue(Files.isSymbolicLink(stage.resolve("current")))
                assertEquals(stage.resolve("bin/tool").toRealPath(), stage.resolve("current/tool").toRealPath())
            }
            SafeHelperArchive.verify(stage, fixture.second)
        }
    }

    @Test fun traversalDosDevicesPrivilegeBitsSpecialEntriesAndCollisionsNeverInstall() = runBlocking {
        val bad = listOf("../escaped", "/absolute", "bundle/../escaped", "bundle/C:/escaped", "bundle\\escape",
            "bundle/CON", "bundle/nul.txt", "bundle/path.", "bundle/path ", "bundle//file")
        for (name in bad) withHelperWork { root ->
            val fixture = helperArchive(root, "zip", listOf(ArchiveFixtureEntry(name)), buildInventory = false)
            assertFails { SafeHelperArchive.extract(fixture.first, root.resolve("stage"), fixture.second) }
            assertFalse(Files.exists(root.resolve("escaped"), NOFOLLOW_LINKS))
        }
        val cases = listOf(
            listOf(ArchiveFixtureEntry("bundle/tool", mode = 0x89ed)), // setuid
            listOf(ArchiveFixtureEntry("bundle/tool", mode = 0x21a4)), // character device
            listOf(ArchiveFixtureEntry("bundle/tool"), ArchiveFixtureEntry("bundle/TOOL")),
            listOf(ArchiveFixtureEntry("bundle/tool"), ArchiveFixtureEntry("bundle/tool")),
            listOf(ArchiveFixtureEntry("bundle/alias", link = "../../outside")),
            listOf(ArchiveFixtureEntry("bundle/alias", link = "/outside")),
        )
        for (entries in cases) withHelperWork { root ->
            val fixture = helperArchive(root, "zip", entries, buildInventory = false)
            assertFails { SafeHelperArchive.extract(fixture.first, root.resolve("stage"), fixture.second) }
        }
    }

    @Test fun limitsWrongHashesMissingFilesAndTamperedInstalledClosureFailClosed() = runBlocking {
        withHelperWork { root ->
            val fixture = helperArchive(root, "zip", listOf(ArchiveFixtureEntry("bundle/tool", "original".encodeToByteArray())))
            assertFails { SafeHelperArchive.extract(fixture.first, root.resolve("too-large"), fixture.second.copy(expandedBytes = 1)) }
            assertFails { SafeHelperArchive.extract(fixture.first, root.resolve("bad-hash"), fixture.second.copy(entries = fixture.second.entries.map { it.copy(sha256 = "0".repeat(64)) })) }
            assertFails { SafeHelperArchive.extract(fixture.first, root.resolve("missing"), fixture.second.copy(entries = fixture.second.entries + HelperFile("missing", 1, "0".repeat(64)))) }
            val installed = root.resolve("installed")
            SafeHelperArchive.extract(fixture.first, installed, fixture.second)
            Files.writeString(installed.resolve("extra-library.so"), "unexpected")
            assertFails { SafeHelperArchive.verify(installed, fixture.second) }
            Files.delete(installed.resolve("extra-library.so"))
            Files.writeString(installed.resolve("tool"), "modified") // same length, different digest
            assertFails { SafeHelperArchive.verify(installed, fixture.second) }
        }
    }

    @Test fun preexistingSymlinkAncestorsAndArchiveLinkChildrenAreNeverTraversed() = runBlocking {
        if (System.getProperty("os.name").startsWith("Windows", true)) return@runBlocking
        withHelperWork { root ->
            val fixture = helperArchive(root, "zip", listOf(ArchiveFixtureEntry("bundle/tool")))
            val outside = Files.createDirectory(root.resolve("outside"))
            Files.createSymbolicLink(root.resolve("linked-stage"), outside)
            assertFails { SafeHelperArchive.extract(fixture.first, root.resolve("linked-stage"), fixture.second) }
            assertFalse(Files.exists(outside.resolve("tool")))
            val nested = helperArchive(root, "zip", listOf(ArchiveFixtureEntry("bundle/alias", link = "real"),
                ArchiveFixtureEntry("bundle/alias/tool"), ArchiveFixtureEntry("bundle/real/another")))
            assertFails { SafeHelperArchive.extract(nested.first, root.resolve("nested"), nested.second) }
        }
    }
}

internal data class ArchiveFixtureEntry(val name: String, val bytes: ByteArray = "authored".encodeToByteArray(),
    val mode: Int = 0x81ed, val link: String? = null)

internal fun helperArchive(root: Path, format: String, entries: List<ArchiveFixtureEntry>, buildInventory: Boolean = true,
    component: String = "node"): Pair<Path, HelperManifest> {
    val archive = Files.createTempFile(root, "archive-", if (format == "zip") ".zip" else ".tar.gz")
    if (format == "zip") ZipArchiveOutputStream(archive).use { output ->
        for (value in entries) {
            val body = value.link?.encodeToByteArray() ?: value.bytes
            val entry = ZipArchiveEntry(value.name).apply { unixMode = if (value.link == null) value.mode else 0xa1ff; size = body.size.toLong() }
            output.putArchiveEntry(entry); output.write(body); output.closeArchiveEntry()
        }
    } else Files.newOutputStream(archive).use { file -> GZIPOutputStream(file).use { gzip -> TarArchiveOutputStream(gzip).use { output ->
        for (value in entries) {
            val entry = if (value.link != null) TarArchiveEntry(value.name, TarConstants.LF_SYMLINK).apply { linkName = value.link }
                else TarArchiveEntry(value.name, true).apply { size = value.bytes.size.toLong(); mode = value.mode and 0xfff }
            output.putArchiveEntry(entry)
            if (value.link == null) output.write(value.bytes)
            output.closeArchiveEntry()
        }
    } } }
    val inventory = if (buildInventory) entries.map { entry ->
        val name = entry.name.removePrefix("bundle/")
        if (entry.link == null) HelperFile(name, entry.bytes.size.toLong(), DesktopHelperManifests.hash(entry.bytes), executable = entry.mode and 0x49 != 0)
        else HelperFile(name, 0, link = entry.link)
    } else listOf(HelperFile("tool", 8, DesktopHelperManifests.hash("authored".encodeToByteArray()), executable = true))
    val expanded = entries.sumOf { if (it.link == null) it.bytes.size.toLong() else if (format == "zip") it.link.encodeToByteArray().size.toLong() else 0L }
    return archive to HelperManifest(DesktopNativeRuntime.platform(), component, "authored", "https://example.invalid/authored.zip",
        DesktopNativeRuntime.sha256(archive), Files.size(archive), expanded, "bundle", format,
        inventory.first { it.sha256 != null }.path, entries = inventory)
}

internal suspend fun withHelperWork(action: suspend (Path) -> Unit) {
    val repository = System.getenv("VEYRA_PROJECT_ROOT")?.let(Path::of)
        ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("settings.gradle.kts")) }
    val parent = repository.resolve(".work/desktop-audio/helper-tests")
    Files.createDirectories(parent)
    val root = Files.createTempDirectory(parent, "authored-")
    try { action(root) } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
}
