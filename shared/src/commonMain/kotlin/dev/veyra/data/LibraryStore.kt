package dev.veyra.data

import dev.veyra.data.room.RoomLibraryPersistence
import dev.veyra.data.room.VeyraDatabase
import dev.veyra.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface StoreStatus {
    data object Uninitialized : StoreStatus
    data class Ready(val recoveredFromBackup: Boolean = false) : StoreStatus
    data class Failed(val failure: AppFailure) : StoreStatus
    data object Closed : StoreStatus
}

sealed interface StoreResult {
    data class Success(val snapshot: AppSnapshot) : StoreResult
    data class Failure(val failure: AppFailure) : StoreResult
}

fun StoreResult.getOrThrow(): AppSnapshot = when (this) {
    is StoreResult.Success -> snapshot
    is StoreResult.Failure -> throw VeyraException(failure)
}

/**
 * App-lifetime Room repository facade. AppSnapshot is an immutable presentation projection only;
 * persistence is normalized relational rows. Every transform reads the latest committed database
 * revision INSIDE the write transaction, not a possibly stale screen snapshot.
 */
class LibraryStore internal constructor(
    private val persistence: LibraryPersistence,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(database: VeyraDatabase, dispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(RoomLibraryPersistence(database), dispatcher)

    private val mutex = Mutex()
    private val observerJob = SupervisorJob()
    private val observerScope = CoroutineScope(observerJob + dispatcher)
    private var observing = false
    private val mutableState = MutableStateFlow(AppSnapshot())
    private val mutableStatus = MutableStateFlow<StoreStatus>(StoreStatus.Uninitialized)
    private val mutableFailure = MutableStateFlow<AppFailure?>(null)
    val state: StateFlow<AppSnapshot> = mutableState.asStateFlow()
    val status: StateFlow<StoreStatus> = mutableStatus.asStateFlow()
    val lastFailure: StateFlow<AppFailure?> = mutableFailure.asStateFlow()

    suspend fun initialize(): StoreResult = withContext(dispatcher) { mutex.withLock { initializeLocked() } }

    suspend fun update(expectedRevision: Long? = null, transform: (AppSnapshot) -> AppSnapshot): StoreResult = withContext(dispatcher) {
        mutex.withLock {
            val loaded = initializeLocked()
            if (loaded is StoreResult.Failure) return@withLock loaded
            try {
                currentCoroutineContext().ensureActive()
                // Once accepted, commit + memory publication form one cancellation-safe operation.
                withContext(NonCancellable) {
                    val next = persistence.transact(expectedRevision, transform)
                    mutableState.value = next
                    mutableFailure.value = null
                    StoreResult.Success(next)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(classify(error)) }
        }
    }

    /** Serialized writer/read barrier; ordinary durability is SQLite WAL + synchronous FULL. */
    suspend fun flush(): StoreResult = withContext(dispatcher) {
        mutex.withLock {
            val loaded = initializeLocked()
            if (loaded is StoreResult.Failure) return@withLock loaded
            try {
                val latest = persistence.read()
                mutableState.value = latest
                StoreResult.Success(latest)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(classify(error)) }
        }
    }

    /** A verified SQLite backup, not a copy of a live WAL database file. */
    suspend fun backup(): StoreResult = withContext(dispatcher) {
        mutex.withLock {
            val loaded = initializeLocked()
            if (loaded is StoreResult.Failure) return@withLock loaded
            try { persistence.backup(); StoreResult.Success(mutableState.value) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(classify(error)) }
        }
    }

    /** Explicit recovery only; a corrupt/newer database is never silently replaced or reset. */
    suspend fun recoverFromBackup(): StoreResult = withContext(dispatcher) {
        mutex.withLock {
            if (mutableStatus.value !is StoreStatus.Failed) return@withLock fail(AppFailure(FailureCode.INVALID_STATE))
            try { publishOpened(persistence.recoverFromBackup()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(classify(error)) }
        }
    }

    suspend fun close() = withContext(NonCancellable + dispatcher) {
        observerJob.cancelAndJoin()
        mutex.withLock {
            if (mutableStatus.value == StoreStatus.Closed) return@withLock
            try { persistence.close() } finally { mutableStatus.value = StoreStatus.Closed }
        }
    }

    suspend fun recordPlaybackBinding(binding: PlaybackBinding): StoreResult = update { old ->
        require(old.media[binding.mediaId] != null)
        if (old.playbackBindings[binding.id] == binding) old
        else {
            require(binding.id !in old.playbackBindings)
            require(binding.queryTitle == old.media.getValue(binding.mediaId).title)
            old.copy(playbackBindings = old.playbackBindings + (binding.id to binding))
        }
    }

    suspend fun updateDownloadWithAttempt(
        record: DownloadRecord,
        attempt: DownloadAttempt? = null,
        expectedLeaseOwner: String? = null,
        expectedAttemptId: String? = null,
    ): StoreResult = update { old ->
        val current = old.downloads[record.id]
        if (expectedLeaseOwner != null) require(current?.leaseOwner == expectedLeaseOwner)
        if (expectedAttemptId != null) require(current?.activeAttemptId == expectedAttemptId)
        require(attempt == null || attempt.downloadId == record.id)
        old.copy(
            downloads = old.downloads + (record.id to record),
            downloadAttempts = if (attempt == null) old.downloadAttempts else old.downloadAttempts + (attempt.id to attempt),
        )
    }

    /** Durable lease arbitration; null means another worker/user pause/terminal state owns the item. */
    suspend fun claimDownload(id: String, leaseOwner: String, nowEpochMs: Long, leaseDurationMs: Long = 60_000): DownloadRecord? {
        require(leaseOwner.isNotBlank() && leaseDurationMs in 1_000..600_000 && nowEpochMs >= 0)
        var claimed: DownloadRecord? = null
        update { old ->
            val record = old.downloads[id] ?: return@update old
            if (record.cleanupPending || record.userPaused || record.status in setOf(DownloadStatus.COMPLETE, DownloadStatus.CANCELLED, DownloadStatus.FAILED) ||
                (record.nextRetryEpochMs ?: 0) > nowEpochMs ||
                (record.leaseOwner != null && (record.leaseExpiresEpochMs ?: 0) > nowEpochMs)) return@update old
            val next = record.copy(leaseOwner = leaseOwner, leaseExpiresEpochMs = nowEpochMs + leaseDurationMs, updatedEpochMs = nowEpochMs)
            claimed = next
            old.copy(downloads = old.downloads + (id to next))
        }.getOrThrow()
        return claimed
    }

    suspend fun upsertCatalog(items: List<MediaItem>, shows: List<PodcastShow> = emptyList(), artists: List<Artist> = emptyList(), albums: List<Album> = emptyList()): StoreResult = update { old ->
        old.copy(
            media = old.media + items.associate { it.id to it.withRetainedLocalArtwork(old.media[it.id]) },
            shows = old.shows + shows.associate { it.id to it.withRetainedLocalArtwork(old.shows[it.id]) },
            artists = old.artists + artists.associateBy { it.id },
            albums = old.albums + albums.associateBy { it.id },
        )
    }

    suspend fun toggleFavorite(item: MediaItem): StoreResult = update { old ->
        old.copy(
            media = old.media + (item.id to item.withRetainedLocalArtwork(old.media[item.id])),
            favoriteIds = if (item.id in old.favoriteIds) old.favoriteIds - item.id else old.favoriteIds + item.id,
        )
    }

    suspend fun toggleSavedEpisode(item: MediaItem): StoreResult = update { old ->
        require(item.kind == MediaKind.EPISODE)
        old.copy(
            media = old.media + (item.id to item.withRetainedLocalArtwork(old.media[item.id])),
            savedEpisodeIds = if (item.id in old.savedEpisodeIds) old.savedEpisodeIds - item.id else old.savedEpisodeIds + item.id,
        )
    }

    suspend fun toggleFollow(show: PodcastShow): StoreResult = update { old ->
        old.copy(
            shows = old.shows + (show.id to show.withRetainedLocalArtwork(old.shows[show.id])),
            followedShowIds = if (show.id in old.followedShowIds) old.followedShowIds - show.id else old.followedShowIds + show.id,
        )
    }

    suspend fun createPlaylist(name: String, id: String = newEntityId("playlist"), nowEpochMs: Long = 0): StoreResult = update { old ->
        require(name.isNotBlank())
        require(old.playlists.none { it.id == id })
        old.copy(playlists = old.playlists + Playlist(id, name.trim(), createdEpochMs = nowEpochMs, updatedEpochMs = nowEpochMs))
    }

    suspend fun renamePlaylist(id: String, name: String, nowEpochMs: Long = 0): StoreResult = update { old ->
        require(name.isNotBlank())
        require(old.playlists.any { it.id == id })
        old.copy(playlists = old.playlists.map { if (it.id == id) it.copy(name = name.trim(), updatedEpochMs = nowEpochMs) else it })
    }

    suspend fun deletePlaylist(id: String): StoreResult = update { old -> old.copy(playlists = old.playlists.filterNot { it.id == id }) }

    suspend fun addToPlaylist(playlistId: String, item: MediaItem, nowEpochMs: Long = 0): StoreResult = update { old ->
        require(old.playlists.any { it.id == playlistId })
        old.copy(
            media = old.media + (item.id to item.withRetainedLocalArtwork(old.media[item.id])),
            playlists = old.playlists.map {
                if (it.id == playlistId) it.copy(entries = it.entries + PlaylistEntry(newEntityId(), item.id), updatedEpochMs = nowEpochMs) else it
            },
        )
    }

    suspend fun removeFromPlaylist(playlistId: String, entryId: String): StoreResult = update { old ->
        old.copy(playlists = old.playlists.map { if (it.id == playlistId) it.copy(entries = it.entries.filterNot { entry -> entry.id == entryId }) else it })
    }

    suspend fun movePlaylistEntry(playlistId: String, entryId: String, toIndex: Int): StoreResult = update { old ->
        old.copy(playlists = old.playlists.map { playlist ->
            if (playlist.id != playlistId) playlist else {
                val entries = playlist.entries.toMutableList()
                val index = entries.indexOfFirst { it.id == entryId }
                require(index >= 0 && toIndex in entries.indices)
                entries.add(toIndex, entries.removeAt(index))
                playlist.copy(entries = entries)
            }
        })
    }

    suspend fun markPlayed(mediaId: String, played: Boolean, nowEpochMs: Long = 0): StoreResult = update { old ->
        require(old.media[mediaId]?.kind == MediaKind.EPISODE)
        val progress = (old.progress[mediaId] ?: MediaProgress()).copy(played = played, updatedEpochMs = nowEpochMs)
        old.copy(progress = old.progress + (mediaId to progress))
    }

    private suspend fun initializeLocked(): StoreResult {
        when (val current = mutableStatus.value) {
            is StoreStatus.Ready -> return StoreResult.Success(mutableState.value)
            is StoreStatus.Failed -> return StoreResult.Failure(current.failure)
            StoreStatus.Closed -> return StoreResult.Failure(AppFailure(FailureCode.STORAGE_IO, "Store is closed"))
            StoreStatus.Uninitialized -> Unit
        }
        return try { publishOpened(persistence.open()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val failure = if (error is VeyraException) error.failure else AppFailure(FailureCode.RECOVERY_REQUIRED, "Database could not be safely opened")
            mutableStatus.value = StoreStatus.Failed(failure)
            fail(failure)
        }
    }

    private fun publishOpened(opened: OpenedLibrary): StoreResult {
        validateLibrarySnapshot(opened.snapshot)
        mutableState.value = opened.snapshot
        mutableStatus.value = StoreStatus.Ready(opened.recoveredFromBackup)
        mutableFailure.value = null
        if (!observing) {
            observing = true
            observerScope.launch {
                try {
                    persistence.changes().collect {
                        mutex.withLock {
                            if (mutableStatus.value !is StoreStatus.Ready) return@withLock
                            val latest = persistence.read()
                            if (latest.revision > mutableState.value.revision) {
                                validateLibrarySnapshot(latest)
                                mutableState.value = latest
                            }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { mutableFailure.value = classify(error) }
            }
        }
        return StoreResult.Success(opened.snapshot)
    }

    private fun fail(failure: AppFailure): StoreResult.Failure {
        mutableFailure.value = failure
        return StoreResult.Failure(failure)
    }

    private fun classify(error: Exception): AppFailure = when (error) {
        is VeyraException -> error.failure
        is IllegalArgumentException -> AppFailure(FailureCode.INVALID_STATE)
        else -> AppFailure(FailureCode.STORAGE_IO)
    }
}
