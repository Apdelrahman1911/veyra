package dev.veyra.downloads

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.OfflineAsset
import dev.veyra.model.OfflineAssetKind
import dev.veyra.model.VeyraException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.Path.Companion.toPath

internal fun failureFromStorage(error: Exception): VeyraException {
    if (error is VeyraException) return error
    val message = error.message.orEmpty().lowercase()
    val code = if ("no space" in message || "enospc" in message || "disk full" in message)
        FailureCode.STORAGE_FULL else FailureCode.STORAGE_IO
    return VeyraException(AppFailure(code))
}

internal inline fun <T> storageOperation(block: () -> T): T = try { block() }
catch (cancelled: CancellationException) { throw cancelled }
catch (error: Exception) { throw failureFromStorage(error) }

/** Idempotent close; a best-effort release of one lock must never skip the remaining locks. */
internal interface DownloadFileLease { fun close() }

private fun closeDownloadLeases(leases: Iterable<DownloadFileLease>) {
    var failure: Exception? = null
    for (lease in leases) try { lease.close() }
    catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
    failure?.let { throw it }
}

/** Nonblocking OS advisory locks; contention is null, an actual I/O error is not contention. */
internal expect fun tryAcquireDownloadFileLease(path: String): DownloadFileLease?

internal class DownloadTransferSlot(val index: Int, private val lease: DownloadFileLease) : DownloadFileLease {
    override fun close() = lease.close()
}

/**
 * Owned storage and integrity only. No HTTP, ranges, source selection or competing transfer
 * journals: kmpYT owns every transfer and its private receipts inside each staging directory.
 * Room holds the finalization transaction intent before publication to the final directory.
 * Writers keep an OS generation lock until gateway/validator completion: Room lease expiry is
 * not a writer join. Stable lock files are never unlinked, which would break inode fencing.
 * The configured parent must be app-owned and not concurrently replaceable by an attacker.
 */
class DownloadFiles(directory: String, val fileSystem: FileSystem = FileSystem.SYSTEM) {
    val root: Path = directory.toPath(normalize = true)
    private val stagingRoot = root / ".partial"
    private val finalRoot = root / "files"
    private val lockRoot = root / ".locks"

    init {
        require(root.isAbsolute && root.parent != null) { "Owned media directory must be absolute and not a filesystem root" }
        require(!directory.startsWith("\\\\") && !directory.startsWith("//")) { "Network storage is not owned local storage" }
    }

    fun initialize() = storageOperation {
        checkRootAncestors()
        fileSystem.createDirectories(root)
        checkRootAncestors()
        listOf(stagingRoot, finalRoot, lockRoot).forEach { path ->
            checkNoSymlink(path)
            fileSystem.createDirectories(path)
            checkNoSymlink(path)
        }
    }

    fun key(mediaId: String): String = "media-${mediaId.encodeUtf8().sha256().hex()}"
    fun stagingDirectory(mediaId: String, generationKey: String = "legacy"): Path =
        stagingRoot / key(mediaId) / "generation-${generationKey.encodeUtf8().sha256().hex()}"
    fun part(mediaId: String, generationKey: String = "legacy"): Path = stagingDirectory(mediaId, generationKey) / "transfer.part"

    fun prepareStaging(mediaId: String, generationKey: String = "legacy"): Path =
        prepareStagingPath(part(mediaId, generationKey).toString())

    internal fun prepareStagingPath(value: String): Path = storageOperation {
        initialize()
        val path = stagingPart(value)
        fileSystem.createDirectories(path.parent!!)
        stagingPart(value)
    }

    fun finalPath(mediaId: String, generation: Long, mime: String?, generationKey: String? = null): Path {
        require(generation >= 0)
        val extension = when (mime?.substringBefore(';')?.trim()?.lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "video/mp4", "audio/x-m4a" -> "m4a"
            "audio/webm", "video/webm" -> "webm"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/wav", "audio/x-wav" -> "wav"
            else -> "audio"
        }
        val suffix = generationKey?.let { "-${it.encodeUtf8().sha256().hex()}" }.orEmpty()
        return finalRoot / "${key(mediaId)}-$generation$suffix.$extension"
    }

    /** Native paths only, no URI/UNC interpretation or traversal through an owned symlink. */
    fun ownedPath(uri: String): Path = storageOperation {
        checkRootAncestors()
        if (uri.startsWith("file:", ignoreCase = true) || uri.startsWith("\\\\") || uri.startsWith("//")) corrupt()
        val path = uri.toPath(normalize = true)
        val relative = runCatching { path.relativeTo(root) }.getOrNull()
        if (!path.isAbsolute || relative == null || ".." in relative.segments || relative.segments.isEmpty()) corrupt()
        var current: Path? = path
        while (current != null) {
            checkNoSymlink(current)
            if (current == root) break
            current = current.parent
        }
        if (current != root) corrupt()
        path
    }

    private fun checkRootAncestors() {
        var path: Path? = root
        while (path != null) { checkNoSymlink(path); path = path.parent }
    }

    private fun checkNoSymlink(path: Path) {
        if (fileSystem.metadataOrNull(path)?.symlinkTarget != null) corrupt()
    }

    private fun stagingPart(value: String): Path {
        val path = ownedPath(value)
        val segments = runCatching { path.relativeTo(stagingRoot).segments }.getOrNull() ?: corrupt()
        if (segments.firstOrNull()?.matches(MEDIA_DIRECTORY) != true || segments.lastOrNull() != "transfer.part" ||
            !(segments.size == 2 || segments.size == 3 && segments[1].matches(GENERATION_DIRECTORY))) corrupt()
        return path
    }

    private fun lockName(value: String): String {
        val path = ownedPath(value)
        val identity = if (path.parent == finalRoot) "asset/${path.name}" else {
            val part = stagingPart(value)
            "staging/${part.parent!!.relativeTo(stagingRoot)}"
        }
        return "path-${identity.encodeUtf8().sha256().hex()}"
    }

    /** Acquire in deterministic order, without waiting or leaving a partially acquired set. */
    internal fun tryLeasePaths(values: Collection<String>): DownloadFileLease? = storageOperation {
        initialize()
        val acquired = mutableListOf<DownloadFileLease>()
        try {
            for (name in values.map(::lockName).distinct().sorted()) {
                val lock = tryAcquireDownloadFileLease(ownedPath((lockRoot / name).toString()).toString())
                if (lock == null) { closeDownloadLeases(acquired.asReversed()); return@storageOperation null }
                acquired += lock
            }
            object : DownloadFileLease {
                override fun close() { closeDownloadLeases(acquired.asReversed()) }
            }
        } catch (error: Exception) {
            acquired.asReversed().forEach { runCatching { it.close() } }
            throw error
        }
    }

    /** Global slots include expired-but-live writers and retired high slots after a limit reduction. */
    internal fun tryTransferSlot(parallelism: Int): DownloadTransferSlot? = storageOperation {
        initialize()
        val limit = parallelism.coerceIn(1, 4)
        val admission = tryAcquireDownloadFileLease(ownedPath((lockRoot / "slot-admission").toString()).toString())
            ?: return@storageOperation null
        val probes = mutableListOf<DownloadFileLease>()
        var selected: DownloadTransferSlot? = null
        try {
            for (index in limit until 4) {
                val probe = tryAcquireDownloadFileLease(ownedPath((lockRoot / "slot-$index").toString()).toString())
                    ?: return@storageOperation null
                probes += probe
            }
            for (index in 0 until limit) {
                val lease = tryAcquireDownloadFileLease(ownedPath((lockRoot / "slot-$index").toString()).toString()) ?: continue
                selected = DownloadTransferSlot(index, lease)
                return@storageOperation selected
            }
            null
        } finally {
            try { closeDownloadLeases(probes.asReversed() + admission) }
            catch (error: Exception) {
                try { selected?.close() } catch (closeError: Exception) { error.addSuppressed(closeError) }
                throw error
            }
        }
    }

    /** Caller holds this exact generation lease. Never remove a newer attempt's directory. */
    internal fun resetStaging(stagingPath: String) = storageOperation {
        val path = stagingPart(stagingPath).parent!!
        if (!fileSystem.exists(path)) return@storageOperation
        if (path.name.matches(GENERATION_DIRECTORY)) fileSystem.deleteRecursively(path, mustExist = false)
        else {
            // A pre-release legacy directory can contain new nested generations. Preserve them.
            fileSystem.list(path).filterNot { it.name.matches(GENERATION_DIRECTORY) }.forEach {
                fileSystem.deleteRecursively(ownedPath(it.toString()), mustExist = false)
            }
        }
    }

    /** Fixture compatibility; production cleanup uses the durable exact staging path. */
    fun reset(mediaId: String) = resetStaging(part(mediaId).toString())

    fun deleteAsset(asset: OfflineAsset) = storageOperation {
        if (asset.kind != OfflineAssetKind.FILE) corrupt()
        deleteFinal(asset.uri)
    }

    internal fun deleteFinal(value: String) = storageOperation {
        val path = ownedPath(value)
        if (path.parent != finalRoot) corrupt()
        fileSystem.delete(path, mustExist = false)
    }

    fun atomicPublish(stagingPath: String, finalPath: String) = storageOperation {
        val from = stagingPart(stagingPath)
        val destination = ownedPath(finalPath)
        if (destination.parent != finalRoot) corrupt()
        fileSystem.openReadWrite(from, mustExist = true).use { it.flush() }
        fileSystem.atomicMove(from, destination)
    }

    suspend fun sha256(path: Path): String = withContext(Dispatchers.IO) {
        val safe = ownedPath(path.toString())
        val digest = storageOperation {
            HashingSource.sha256(fileSystem.source(safe)).use { hashing ->
                val buffer = Buffer()
                while (hashing.read(buffer, 64L * 1024) != -1L) {
                    currentCoroutineContext().ensureActive()
                    buffer.clear()
                }
                hashing.hash.hex()
            }
        }
        // EOF and Source.close can themselves block. A same-IO withContext return does not
        // replace this check: never hand a cancelled hash to the native validation stage.
        currentCoroutineContext().ensureActive()
        digest
    }

    suspend fun verifiedFile(asset: OfflineAsset): Path = withContext(Dispatchers.IO) {
        storageOperation {
            val path = ownedPath(asset.uri)
            val metadata = fileSystem.metadataOrNull(path)
            if (asset.kind != OfflineAssetKind.FILE || metadata == null || !metadata.isRegularFile ||
                metadata.size != asset.sizeBytes || asset.sizeBytes <= 0 ||
                asset.sha256?.matches(Regex("[0-9a-f]{64}")) != true || sha256(path) != asset.sha256) corrupt()
            path
        }
    }

    suspend fun bytesUsed(): Long = withContext(Dispatchers.IO) {
        storageOperation {
            checkRootAncestors()
            if (!fileSystem.exists(root)) 0L else fileSystem.listRecursively(root, followSymlinks = false)
                .map { ownedPath(it.toString()) }
                .mapNotNull { fileSystem.metadataOrNull(it)?.takeIf { meta -> meta.isRegularFile }?.size }
                .fold(0L) { total, bytes -> if (Long.MAX_VALUE - total < bytes) Long.MAX_VALUE else total + bytes }
        }
    }

    /** Enumeration only. Caller must check latest DB references and hold the generation lock. */
    internal fun generatedStagingParts(): List<String> = storageOperation {
        initialize()
        fileSystem.list(stagingRoot).filter { it.name.matches(MEDIA_DIRECTORY) }.flatMap { media ->
            val safe = ownedPath(media.toString())
            if (fileSystem.metadataOrNull(safe)?.isDirectory != true) emptyList() else buildList {
                val children = fileSystem.list(safe)
                if (children.any { !it.name.matches(GENERATION_DIRECTORY) }) add((safe / "transfer.part").toString())
                children.filter { it.name.matches(GENERATION_DIRECTORY) }.forEach { generation ->
                    if (fileSystem.metadataOrNull(ownedPath(generation.toString()))?.isDirectory == true)
                        add((generation / "transfer.part").toString())
                }
            }
        }
    }

    private fun corrupt(): Nothing = throw VeyraException(AppFailure(FailureCode.CORRUPT_MEDIA, "owned_file_integrity"))

    private companion object {
        val MEDIA_DIRECTORY = Regex("media-[0-9a-f]{64}")
        val GENERATION_DIRECTORY = Regex("generation-[0-9a-f]{64}")
    }
}
