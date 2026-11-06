package dev.veyra.downloads

import androidx.room.Room
import dev.veyra.data.LibraryPersistence
import dev.veyra.data.LibraryStore
import dev.veyra.data.getOrThrow
import dev.veyra.data.room.RoomLibraryPersistence
import dev.veyra.data.room.VeyraDatabase
import dev.veyra.data.room.VeyraDatabaseConstructor
import dev.veyra.data.room.buildVeyraDatabase
import dev.veyra.model.*
import dev.veyra.playback.*
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.execution.AppSourceResolver
import dev.veyra.source.execution.KmpFirstResult
import dev.veyra.source.execution.KmpMediaGateway
import dev.veyra.source.youtube.YouTubeTransferProgress
import dev.veyra.source.youtube.YouTubeTransferResult
import kotlinx.coroutines.*
import okio.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * REAL Room/SQLite and actual owned files, with AUTHORED NON-AUDIO bytes and controllable fake
 * kmpYT/native-validator seams. These are scheduler/integrity tests, NOT source or audio proof.
 * Every fixture, subprocess output, cache and temporary directory is confined to this repository.
 */
internal val authoredBytes = "Veyra authored scheduler fixture. NOT AUDIO. ليست مادة صوتية.".encodeToByteArray()
internal const val authoredRepresentation = "authored-kmpyt-140"
internal const val authoredVideo = "AAAAAAAAAAA"

internal fun downloadTestDirectory(): Path {
    val root = System.getenv("VEYRA_PROJECT_ROOT")?.let(Path::of)
        ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
    val parent = root.resolve(".work/download-coordinator-tests")
    Files.createDirectories(parent)
    return Files.createTempDirectory(parent, "case-")
}

internal class DownloadTestClock : Clock {
    private val epoch = AtomicLong(10_000)
    private val elapsed = AtomicLong()
    override fun epochMs(): Long = epoch.get()
    override fun monotonicMs(): Long = elapsed.get()
    fun advance(ms: Long) { require(ms >= 0); epoch.addAndGet(ms); elapsed.addAndGet(ms) }
    fun setEpoch(ms: Long) { epoch.set(ms) }
}

internal class DownloadBarrier {
    val entered = CompletableDeferred<Unit>()
    val released = CompletableDeferred<Unit>()
    suspend fun stop() { entered.complete(Unit); released.await() }
    fun release() { released.complete(Unit) }
}

internal data class TransferCall(
    val binding: PlaybackBinding,
    val path: String,
    val representation: String?,
    val onProgress: suspend (YouTubeTransferProgress) -> Unit,
) {
    fun write(bytes: ByteArray = authoredBytes) { Files.write(Path.of(path), bytes) }
    fun result(bytes: ByteArray = authoredBytes, representationId: String = representation ?: authoredRepresentation) =
        YouTubeTransferResult(bytes.size.toLong(), "audio/mpeg", "mp3", "mp3", representationId)
    suspend fun complete(bytes: ByteArray = authoredBytes): YouTubeTransferResult {
        write(bytes)
        onProgress(YouTubeTransferProgress(bytes.size.toLong(), bytes.size.toLong()))
        return result(bytes)
    }
}

internal class DownloadTestGateway : KmpMediaGateway {
    val queries = CopyOnWriteArrayList<String>()
    val resolutions = CopyOnWriteArrayList<PlaybackBinding>()
    val transfers = CopyOnWriteArrayList<TransferCall>()
    var search: suspend (String) -> KmpFirstResult? = { title ->
        KmpFirstResult(title, FirstResultKind.VIDEO, authoredVideo, "Authored selected recording",
            "https://www.youtube.com/watch?v=$authoredVideo")
    }
    var plan: suspend (PlaybackBinding) -> ResolvedPlayable = { binding ->
        ResolvedPlayable(binding.mediaId, "https://fixture.example.test/non-audio", mimeType = "audio/mpeg",
            codecs = "mp3", representationId = authoredRepresentation, executionBinding = binding)
    }
    var transfer: suspend (TransferCall) -> YouTubeTransferResult = { it.complete() }
    override suspend fun firstResult(completeTitle: String): KmpFirstResult? {
        queries += completeTitle
        return search(completeTitle)
    }
    override suspend fun resolve(binding: PlaybackBinding, capabilities: EngineCapabilities, purpose: ResolvePurpose): ResolvedPlayable {
        check(purpose == ResolvePurpose.DOWNLOAD)
        resolutions += binding
        return plan(binding)
    }
    override suspend fun download(binding: PlaybackBinding, destinationPath: String, capabilities: EngineCapabilities,
        representationId: String?, onProgress: suspend (YouTubeTransferProgress) -> Unit): YouTubeTransferResult {
        val call = TransferCall(binding, destinationPath, representationId, onProgress)
        transfers += call
        return transfer(call)
    }
    fun denyAcquisition() {
        search = { error("Acquisition forbidden in replay/cleanup/offline fixture") }
        plan = { error("Gateway resolution forbidden") }
        transfer = { error("Transfer forbidden") }
    }
    fun assertUnused() {
        assertTrue(queries.isEmpty(), "No search may occur")
        assertTrue(resolutions.isEmpty(), "No gateway resolution may occur")
        assertTrue(transfers.isEmpty(), "No gateway transfer may occur")
    }
}

/** Faults wrap the REAL SQLite transaction, including rollback after a filesystem side effect. */
internal class DownloadTransactionFaults(private val room: LibraryPersistence) : LibraryPersistence by room {
    @Volatile var afterTransform: (AppSnapshot, AppSnapshot) -> Unit = { _, _ -> }
    @Volatile var afterCommit: suspend (AppSnapshot) -> Unit = {}
    override suspend fun transact(expectedRevision: Long?, transform: (AppSnapshot) -> AppSnapshot): AppSnapshot {
        val committed = room.transact(expectedRevision) { before ->
            transform(before).also { afterTransform(before, it) }
        }
        afterCommit(committed)
        return committed
    }
}

internal data class SeededDownload(val item: MediaItem, val binding: PlaybackBinding, val record: DownloadRecord,
    val attempt: DownloadAttempt? = null, val intent: DownloadFinalization? = null)

internal class DownloadFixture(fileSystem: FileSystem = FileSystem.SYSTEM) {
    val directory = downloadTestDirectory()
    val databasePath = directory.resolve("library.db")
    val files = DownloadFiles(directory.resolve("media").toString(), fileSystem)
    val clock = DownloadTestClock()
    val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val stores = CopyOnWriteArrayList<LibraryStore>()
    val coordinators = CopyOnWriteArrayList<DownloadCoordinator>()
    val barriers = CopyOnWriteArrayList<DownloadBarrier>()
    val releaseOnClose = CopyOnWriteArrayList<() -> Unit>()
    val validationCount = AtomicInteger()
    val validator = AssetValidator { validationCount.incrementAndGet(); AssetValidation.Valid(31_234) }

    init { files.initialize() }

    fun barrier() = DownloadBarrier().also { barriers += it }
    fun persistence(): DownloadTransactionFaults = DownloadTransactionFaults(RoomLibraryPersistence(buildVeyraDatabase(
        Room.databaseBuilder<VeyraDatabase>(databasePath.toString(), factory = VeyraDatabaseConstructor::initialize))))

    // The production factory deliberately has an exclusive DB-lifecycle lock. Bypass ONLY that
    // factory in these tests to exercise two real Room connections, never weaken the host lock.
    fun store(persistence: LibraryPersistence = persistence()) = LibraryStore(persistence).also { stores += it }
    fun coordinator(store: LibraryStore, gateway: DownloadTestGateway = DownloadTestGateway(),
        validator: AssetValidator = this.validator, gate: DownloadExecutionGate = ProcessLifetimeDownloadGate,
        files: DownloadFiles = this.files, beforeDelete: suspend (OfflineAsset) -> Unit = {},
        capabilities: EngineCapabilities = EngineCapabilities(), heartbeatMs: Long = 80): DownloadCoordinator =
        DownloadCoordinator(store, files, AppSourceResolver(store, files, validator, gateway, clock), gateway,
            validator, capabilities, parent, gate, clock, pollingMs = 40, leaseDurationMs = 1_000,
            heartbeatMs = heartbeatMs, beforePhysicalDelete = beforeDelete).also { coordinators += it }

    fun item(id: String = "item-1") = MediaItem(id, "Complete requested title $id — العنوان الكامل", MediaKind.MUSIC,
        SourceRef(SourceProvider.COMMONS, "original-$id", canonicalUrl = "https://commons.wikimedia.org/wiki/File:$id"),
        creator = "Authored discovery creator", durationMs = 999_999)

    fun binding(item: MediaItem, id: String = "binding-${item.id}", video: String = authoredVideo) =
        PlaybackBinding(id, item.id, item.title, FirstResultKind.VIDEO, video, "Authored selected recording",
            "https://www.youtube.com/watch?v=$video", video, resolvedEpochMs = clock.epochMs())

    suspend fun seedPending(store: LibraryStore, item: MediaItem = item(), status: DownloadStatus = DownloadStatus.QUEUED,
        bound: Boolean = false, bytes: ByteArray? = null, leaseOwner: String? = null, leaseUntil: Long? = null,
        userPaused: Boolean = false): SeededDownload {
        val binding = binding(item)
        val id = files.key(item.id)
        val path = bytes?.let { files.prepareStaging(item.id, "seed").also { p -> Files.write(Path.of(p.toString()), it) }.toString() }
        val attempt = leaseOwner?.let { DownloadAttempt("attempt-seed-${item.id}", id, 1,
            bytesDownloaded = bytes?.size?.toLong() ?: 0, stagingPath = path,
            representationId = if (bound) authoredRepresentation else null, executionBindingId = binding.id.takeIf { bound }) }
        val record = DownloadRecord(id, item.id, status, bytesDownloaded = bytes?.size?.toLong() ?: 0,
            stagingPath = path, executionBindingId = binding.id.takeIf { bound },
            representationId = authoredRepresentation.takeIf { bound }, attemptCount = attempt?.number ?: 0,
            activeAttemptId = attempt?.id, userPaused = userPaused, leaseOwner = leaseOwner, leaseExpiresEpochMs = leaseUntil,
            createdEpochMs = clock.epochMs(), updatedEpochMs = clock.epochMs())
        store.update { snapshot -> snapshot.copy(media = snapshot.media + (item.id to item),
            playbackBindings = if (bound) snapshot.playbackBindings + (binding.id to binding) else snapshot.playbackBindings,
            downloads = snapshot.downloads + (id to record),
            downloadAttempts = if (attempt == null) snapshot.downloadAttempts else snapshot.downloadAttempts + (attempt.id to attempt)) }.getOrThrow()
        return SeededDownload(item, binding, record, attempt)
    }

    suspend fun seedFinalization(store: LibraryStore, item: MediaItem = item(), published: Boolean = false,
        status: DownloadStatus = DownloadStatus.VERIFYING, leaseOwner: String? = null,
        leaseUntil: Long? = null, generation: Long = 1, bytes: ByteArray = authoredBytes): SeededDownload {
        val binding = binding(item)
        val id = files.key(item.id)
        val attemptId = "attempt-${item.id}-$generation"
        val part = files.prepareStaging(item.id, attemptId)
        Files.write(Path.of(part.toString()), bytes)
        val final = files.finalPath(item.id, generation, "audio/mpeg", attemptId)
        val hash = files.sha256(part)
        val attempt = DownloadAttempt(attemptId, id, generation.toInt(), DownloadAttemptStatus.FINALIZING,
            bytesDownloaded = bytes.size.toLong(), totalBytes = bytes.size.toLong(), stagingPath = part.toString(),
            representationId = authoredRepresentation, executionBindingId = binding.id, validatorGeneration = generation)
        val record = DownloadRecord(id, item.id, status, bytesDownloaded = bytes.size.toLong(), totalBytes = bytes.size.toLong(),
            stagingPath = part.toString(), representationId = authoredRepresentation, executionBindingId = binding.id,
            validatorGeneration = generation, attemptCount = attempt.number, activeAttemptId = attempt.id,
            leaseOwner = leaseOwner, leaseExpiresEpochMs = leaseUntil, createdEpochMs = clock.epochMs())
        val intent = DownloadFinalization(id, item.id, part.toString(), final.toString(), hash, bytes.size.toLong(),
            attempt.id, 31_234, "audio/mpeg", "mp3", binding.id, authoredRepresentation, generation)
        store.update { snapshot -> snapshot.copy(media = snapshot.media + (item.id to item),
            playbackBindings = snapshot.playbackBindings + (binding.id to binding), downloads = snapshot.downloads + (id to record),
            downloadAttempts = snapshot.downloadAttempts + (attempt.id to attempt),
            downloadFinalizations = snapshot.downloadFinalizations + (id to intent)) }.getOrThrow()
        if (published) files.atomicPublish(part.toString(), final.toString())
        return SeededDownload(item, binding, record, attempt, intent)
    }

    suspend fun seedComplete(store: LibraryStore, item: MediaItem = item()): SeededDownload {
        val seeded = seedFinalization(store, item, published = true)
        val intent = seeded.intent!!
        val asset = OfflineAsset("asset-${item.id}", item.id, intent.finalPath, intent.sizeBytes,
            mimeType = intent.mimeType, codecs = intent.codecs, sha256 = intent.sha256,
            representationId = intent.representationId, validatorGeneration = intent.validatorGeneration,
            completedEpochMs = clock.epochMs(), executionBindingId = seeded.binding.id, measuredDurationMs = 31_234)
        val record = seeded.record.copy(status = DownloadStatus.COMPLETE, asset = asset,
            stagingPath = null, activeAttemptId = null, leaseOwner = null, leaseExpiresEpochMs = null)
        store.update { snapshot -> snapshot.copy(downloads = snapshot.downloads + (record.id to record),
            downloadFinalizations = snapshot.downloadFinalizations - record.id,
            downloadAttempts = snapshot.downloadAttempts + (seeded.attempt!!.id to seeded.attempt.copy(status = DownloadAttemptStatus.SUCCEEDED))) }.getOrThrow()
        return seeded.copy(record = record, intent = null)
    }

    suspend fun eventually(store: LibraryStore, message: String = "Durable condition not reached",
        condition: (AppSnapshot) -> Boolean): AppSnapshot = withTimeout(15_000) {
        var snapshot = store.flush().getOrThrow()
        while (!condition(snapshot)) { delay(20); snapshot = store.flush().getOrThrow() }
        assertTrue(condition(snapshot), message)
        snapshot
    }

    suspend fun close() {
        barriers.forEach { it.release() }
        releaseOnClose.forEach { it() }
        coordinators.forEach { withTimeout(10_000) { it.close() } }
        parent.cancel()
        stores.forEach { it.close() }
    }

    fun assertSQLite() {
        Files.newInputStream(databasePath).use { assertEquals("SQLite format 3\u0000", it.readNBytes(16).decodeToString()) }
    }
}

internal fun downloadCase(fileSystem: FileSystem = FileSystem.SYSTEM, block: suspend DownloadFixture.() -> Unit) = runBlocking {
    val fixture = DownloadFixture(fileSystem)
    try { withTimeout(45_000) { fixture.block() }; fixture.assertSQLite() }
    finally { withContext(NonCancellable) { fixture.close() } }
}
