package dev.veyra.desktop.runtime

import dev.veyra.desktop.runtime.DesktopHelperManifests.hex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream

/** Bounded extraction into an empty, private staging directory, never directly into live code.
 * No entry is allowed to traverse a link: contained links are created only after all files.
 * Every installed byte is checked against an app-packaged inventory of a pinned whole archive. */
internal object SafeHelperArchive {
    suspend fun extract(archive: Path, destination: Path, manifest: HelperManifest) {
        require(Files.isRegularFile(archive, NOFOLLOW_LINKS))
        DesktopProcessContext.privateDirectory(destination)
        Files.list(destination).use { require(!it.findAny().isPresent) { "Helper stage must be empty" } }
        val writer = Writer(destination, manifest)
        when (manifest.format) {
            "zip" -> ZipFile.builder().setPath(archive).get().use { zip ->
                val entries = zip.entries
                while (entries.hasMoreElements()) {
                    currentCoroutineContext().ensureActive()
                    val entry = entries.nextElement()
                    require(zip.canReadEntryData(entry) && entry.size >= 0) { "Unsupported helper ZIP entry" }
                    val kind = entry.unixMode and 0xf000
                    require(kind in setOf(0, 0x4000, 0x8000, 0xa000)) { "Special helper ZIP entry rejected" }
                    val link = if (entry.isUnixSymlink) {
                        require(entry.size in 1..4096)
                        zip.getInputStream(entry).use { it.readNBytes(4097) }.also { require(it.size.toLong() == entry.size) }.decodeToString(throwOnInvalidSequence = true)
                    } else null
                    zip.getInputStream(entry).use { input ->
                        writer.entry(entry.name, entry.size, entry.unixMode, entry.isDirectory, link, input)
                    }
                }
            }
            "tar.gz" -> Files.newInputStream(archive).use { input ->
                TarArchiveInputStream(GZIPInputStream(input, 65_536)).use { tar ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = tar.nextEntry ?: break
                        require(!entry.isSparse && (entry.isFile || entry.isDirectory || entry.isSymbolicLink)) {
                            "Special, sparse or hard-linked helper TAR entry rejected"
                        }
                        writer.entry(entry.name, entry.size, entry.mode, entry.isDirectory,
                            entry.linkName.takeIf { entry.isSymbolicLink }, tar)
                    }
                }
            }
            else -> error("Unsupported helper archive")
        }
        writer.finish()
        verify(destination, manifest)
    }

    suspend fun verify(root: Path, manifest: HelperManifest) {
        DesktopProcessContext.privateDirectory(root)
        require(Files.isDirectory(root, NOFOLLOW_LINKS))
        val expected = manifest.entries.associateBy { it.path }
        val allowed = expected.keys.toMutableSet()
        for (entry in manifest.entries) {
            val relative = relative(entry.path)
            var parent = relative.parent
            while (parent != null) { allowed += portable(parent); parent = parent.parent }
            val file = root.resolve(relative)
            assertParents(root, file.parent)
            currentCoroutineContext().ensureActive()
            if (entry.link != null) {
                require(Files.isSymbolicLink(file) && Files.readSymbolicLink(file).toString() == entry.link) { "Helper link was changed" }
                require(file.toRealPath().startsWith(root.toRealPath())) { "Helper link escapes its installation" }
            } else {
                require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) == entry.size) { "Helper file was changed" }
                require(hashFile(file) == entry.sha256) { "Helper file integrity check failed" }
                if (entry.executable) require(Files.isExecutable(file)) { "Helper is not executable" }
            }
        }
        // Extra code/data can change dynamic loader behavior even when every expected file hashes.
        Files.walk(root).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                currentCoroutineContext().ensureActive()
                val path = iterator.next()
                if (path == root) continue
                val name = portable(root.relativize(path))
                require(name in allowed) { "Unexpected helper installation entry" }
                if (name !in expected) require(Files.isDirectory(path, NOFOLLOW_LINKS))
            }
        }
    }

    suspend fun hashFile(file: Path): String = Files.newInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65_536)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().hex()
    }

    fun relative(name: String): Path {
        require(name.isNotEmpty() && name.length <= 4096 && !name.startsWith('/') && !name.endsWith('/')) { "Unsafe helper path" }
        require(name.none { it == '\\' || it == ':' || it.code < 32 || it.code == 127 }) { "Unsafe helper path" }
        val parts = name.split('/')
        require(parts.all { part ->
            part.isNotEmpty() && part != "." && part != ".." && !part.endsWith('.') && !part.endsWith(' ') &&
                part.substringBefore('.').uppercase(Locale.ROOT) !in reservedNames
        }) { "Unsafe helper path component" }
        return Path.of(name).also { require(!it.isAbsolute) }
    }

    private class Writer(val root: Path, val manifest: HelperManifest) {
        private val seen = mutableSetOf<String>()
        private val installed = mutableSetOf<String>()
        private val expected = manifest.entries.associateBy { it.path }
        private val links = mutableListOf<Pair<Path, String>>()
        private var declaredBytes = 0L

        suspend fun entry(rawName: String, size: Long, mode: Int, directory: Boolean, link: String?, input: InputStream) {
            val name = rawName.removeSuffix("/")
            val path = relative(name)
            require(seen.size < 20_000 && seen.add(name.lowercase(Locale.ROOT))) { "Duplicate/case-colliding helper entry" }
            require(mode and 0xe00 == 0) { "Privileged helper archive mode rejected" }
            require(size >= 0 && size <= manifest.expandedBytes - declaredBytes) { "Helper archive exceeds expanded byte bound" }
            declaredBytes += size
            require(name == manifest.archiveRoot || name.startsWith(manifest.archiveRoot + "/")) { "Unexpected helper archive root" }
            if (link != null) {
                require(link.length in 1..4096 && link.none { it == '\\' || it == ':' || it.code < 32 }) { "Unsafe helper link" }
                val target = Path.of(link)
                require(!target.isAbsolute && path.parent != null && path.parent.resolve(target).normalize().startsWith(Path.of(manifest.archiveRoot))) {
                    "Helper link escapes its archive"
                }
            }
            val within = name.removePrefix(manifest.archiveRoot).removePrefix("/")
            val mapped = if (manifest.selection.isEmpty()) within.takeIf { it.isNotEmpty() } else manifest.selection[within]
            if (directory) { require(size == 0L && link == null); return }
            val record = mapped?.let { expected[it] ?: error("Unexpected installed helper file") }
            if (mapped != null) require(installed.add(mapped)) { "Helper output collision" }
            val destination = mapped?.let { root.resolve(relative(it)) }
            if (destination != null) {
                assertParents(root, destination.parent)
                DesktopProcessContext.privateDirectory(destination.parent)
                require(!Files.exists(destination, NOFOLLOW_LINKS)) { "Helper output already exists" }
            }
            if (link != null) {
                if (record != null) {
                    require(record.link == link && record.size == 0L)
                    links += checkNotNull(destination) to link
                }
                // ZIP symlink bodies have a byte length; TAR link targets live in the header.
                drain(input, size, null, null)
                return
            }
            if (record != null) require(record.sha256 != null && record.size == size && record.link == null)
            val digest = record?.let { MessageDigest.getInstance("SHA-256") }
            if (destination == null) drain(input, size, null, null)
            else {
                Files.newOutputStream(destination, CREATE_NEW, WRITE).use { output -> drain(input, size, output, digest) }
                require(digest!!.digest().hex() == record!!.sha256) { "Extracted helper hash mismatch" }
                setPermissions(destination, record.executable)
            }
        }

        suspend fun finish() {
            require(declaredBytes == manifest.expandedBytes && installed == expected.keys) { "Incomplete helper archive" }
            for ((path, link) in links) {
                currentCoroutineContext().ensureActive()
                assertParents(root, path.parent)
                Files.createSymbolicLink(path, Path.of(link))
            }
        }
    }

    private suspend fun drain(input: InputStream, size: Long, output: OutputStream?, digest: MessageDigest?) {
        val buffer = ByteArray(65_536)
        var bytes = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            require(count.toLong() <= size - bytes) { "Expanded helper length mismatch" }
            bytes += count
            digest?.update(buffer, 0, count)
            output?.write(buffer, 0, count)
        }
        require(bytes == size) { "Truncated helper archive" }
    }

    internal fun setPermissions(path: Path, executable: Boolean) {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, if (executable) setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                else setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
    }

    private fun assertParents(root: Path, path: Path) {
        require(path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))
        var current: Path? = path
        while (current != null && current.startsWith(root)) {
            require(!Files.isSymbolicLink(current)) { "Helper path traverses a link" }
            if (Files.exists(current, NOFOLLOW_LINKS)) require(Files.isDirectory(current, NOFOLLOW_LINKS))
            current = current.parent
        }
    }

    private fun portable(path: Path): String = path.joinToString("/")
    private val reservedNames = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
}
