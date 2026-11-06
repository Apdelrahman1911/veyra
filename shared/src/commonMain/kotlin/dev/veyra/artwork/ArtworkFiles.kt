package dev.veyra.artwork

import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import dev.veyra.model.newEntityId
import kotlinx.coroutines.CancellationException
import okio.Buffer
import okio.BufferedSink
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.ForwardingSink
import okio.HashingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

/** Image-only owned storage. No source execution, audio files, download journals, or HTTP. */
class ArtworkFiles(directory: String, private val fileSystem: FileSystem = FileSystem.SYSTEM) {
    val root: Path = directory.toPath(normalize = true)
    private val stages = root / ".partial"
    private val images = root / "images"

    init {
        require(root.isAbsolute && root.parent != null && !directory.startsWith("\\\\") && !directory.startsWith("//") &&
            !directory.startsWith("file:", ignoreCase = true)) { "Owned artwork directory must be an absolute native path" }
    }

    internal fun initialize() = storage {
        // Check BEFORE mkdir, including existing parents above the configured root.
        checkAncestors(root)
        fileSystem.createDirectories(root)
        for (path in listOf(stages, images)) {
            checkAncestors(path)
            fileSystem.createDirectories(path)
        }
    }

    internal fun newStage(owner: String): Path = storage {
        initialize()
        checked(stages / "art-${digest(owner)}-${newEntityId("stage")}.part")
    }

    internal fun sink(stage: Path): BufferedSink = storage {
        require(stage.parent == stages)
        val underlying = fileSystem.sink(checked(stage), mustCreate = true)
        // Errors may first surface while a buffer is flushed or closed, well after sink creation.
        // Keep them typed so SafeHttp cannot misclassify disk-full as an HTTP transport failure.
        object : ForwardingSink(underlying) {
            override fun write(source: Buffer, byteCount: Long) = storage { underlying.write(source, byteCount) }
            override fun flush() = storage { underlying.flush() }
            override fun close() = storage { underlying.close() }
        }.buffer()
    }

    internal fun discard(stage: Path) = storage {
        require(stage.parent == stages && stage.name.startsWith("art-") && stage.name.endsWith(".part"))
        fileSystem.delete(checked(stage), mustExist = false)
    }

    /** Only a complete bounded image can be atomically published; Room links it afterwards. */
    internal fun publish(stage: Path, owner: String, sourceUrl: String, declaredMime: String): String = storage {
        require(stage.parent == stages)
        val inspected = inspect(checked(stage), declaredMime)
        val target = checked(images / "art-${digest(owner)}-${digest(sourceUrl)}-${inspected.sha256}.${inspected.image.extension}")
        if (fileSystem.exists(target)) {
            val existing = runCatching { verify(target.toString()) }.getOrNull()
            if (existing != null) { discard(stage); return@storage existing.path }
        }
        fileSystem.openReadWrite(stage, mustExist = true).use { it.flush() }
        fileSystem.atomicMove(stage, target)
        target.toString()
    }

    internal fun verify(value: String): VerifiedArtwork = storage {
        if (value.startsWith("file:", ignoreCase = true) || value.startsWith("\\\\") || value.startsWith("//")) invalidPath()
        val path = checked(value.toPath(normalize = true))
        if (path.parent != images || value != path.toString()) invalidPath()
        val name = FINAL_NAME.matchEntire(path.name) ?: invalidPath()
        val mime = when (name.groupValues[4]) { "jpg" -> "image/jpeg"; "png" -> "image/png"; else -> "image/webp" }
        val inspected = inspect(path, mime)
        if (inspected.sha256 != name.groupValues[3]) artworkFailure(FailureCode.CORRUPT_MEDIA, "artwork_hash_mismatch")
        VerifiedArtwork(path.toString(), name.groupValues[1], name.groupValues[2])
    }

    internal fun matchesOwner(artwork: VerifiedArtwork, owner: String): Boolean = artwork.ownerHash == digest(owner)
    internal fun matchesUrl(artwork: VerifiedArtwork, url: String): Boolean = artwork.urlHash == digest(url)

    private fun checked(path: Path): Path {
        val relative = runCatching { path.relativeTo(root) }.getOrNull()
        if (!path.isAbsolute || relative == null || relative.segments.isEmpty() || ".." in relative.segments) invalidPath()
        checkAncestors(path)
        return path
    }

    private fun checkAncestors(path: Path) {
        var current: Path? = path
        while (current != null) {
            val metadata = fileSystem.metadataOrNull(current)
            if (metadata?.symlinkTarget != null || (current != path && metadata != null && !metadata.isDirectory)) invalidPath()
            current = current.parent
        }
    }

    private data class Inspection(val image: ArtworkImage, val sha256: String)

    private fun inspect(path: Path, mime: String): Inspection {
        val metadata = fileSystem.metadataOrNull(path)
        val expectedSize = metadata?.size
        if (metadata == null || !metadata.isRegularFile || expectedSize == null || expectedSize !in 1..ArtworkImage.MAX_BYTES) {
            artworkFailure(FailureCode.CORRUPT_MEDIA, "artwork_file_missing_or_invalid")
        }
        val prefix = Buffer(); val tail = Buffer(); val buffer = Buffer()
        var size = 0L
        val digest = HashingSource.sha256(fileSystem.source(path)).use { source ->
            while (true) {
                val count = source.read(buffer, 16 * 1024L)
                if (count == -1L) break
                size += count
                if (size > ArtworkImage.MAX_BYTES) artworkFailure(FailureCode.PARSE_FAILURE, "artwork_size_limit")
                val keep = minOf(buffer.size, ArtworkImage.PREFIX_BYTES - prefix.size)
                if (keep > 0) buffer.copyTo(prefix, 0L, keep)
                tail.write(buffer, buffer.size)
                if (tail.size > 12) tail.skip(tail.size - 12)
            }
            source.hash.hex()
        }
        if (size != expectedSize) artworkFailure(FailureCode.CORRUPT_MEDIA, "artwork_file_changed")
        return Inspection(ArtworkImage.inspect(prefix.readByteArray(), tail.readByteArray(), size, mime), digest)
    }

    private fun digest(value: String): String = value.encodeUtf8().sha256().hex()
    private fun invalidPath(): Nothing = artworkFailure(FailureCode.CORRUPT_MEDIA, "artwork_owned_path_required")

    private inline fun <T> storage(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: VeyraException) { throw failure }
    catch (failure: Exception) {
        val text = failure.message.orEmpty().lowercase()
        artworkFailure(if ("no space" in text || "enospc" in text || "disk full" in text)
            FailureCode.STORAGE_FULL else FailureCode.STORAGE_IO, "artwork_storage_failed")
    }

    private companion object {
        val FINAL_NAME = Regex("art-([0-9a-f]{64})-([0-9a-f]{64})-([0-9a-f]{64})\\.(jpg|png|webp)")
    }
}

internal data class VerifiedArtwork(val path: String, val ownerHash: String, val urlHash: String)
