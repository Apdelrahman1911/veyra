package dev.veyra.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.execSQL
import dev.veyra.data.room.*
import dev.veyra.model.*
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

/** Real Room/bundled SQLite tests; no substitute snapshot backend is used here. */
class RoomLibraryStoreTest {
    @Test fun allNormalizedRelationsAndTwoOccurrenceQueuesSurviveColdReopen() = runBlocking {
        val directory = roomWorkDirectory("roundtrip")
        val store = createPlatformLibraryStore(directory.toString())
        val committed = try {
            val snapshot = store.update { completeLibrary() }.getOrThrow()
            assertEquals(1L, snapshot.revision)
            withSql(directory.resolve("veyra.db")) { db ->
                assertTrue(db.longValue("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'room_%'") >= 30)
                assertEquals(3L, db.longValue("SELECT count(*) FROM queue_entries WHERE contextId='MUSIC'"))
                assertEquals(2L, db.longValue("SELECT count(*) FROM track_artists WHERE trackId='music-1'"))
                assertEquals(1L, db.longValue("SELECT cleanupPending FROM downloads WHERE id='download-2'"))
                assertEquals(1L, db.longValue("SELECT removeWhenClean FROM downloads WHERE id='download-2'"))
                assertFalse(db.prepare("PRAGMA foreign_key_check").use { it.step() })
                assertEquals("ok", db.textValue("PRAGMA quick_check"))
            }
            snapshot
        } finally { store.close() }
        val reopened = createPlatformLibraryStore(directory.toString())
        try {
            assertEquals(committed, reopened.initialize().getOrThrow())
            assertEquals(StoreStatus.Ready(), reopened.status.value)
            Files.newInputStream(directory.resolve("veyra.db")).use {
                assertEquals("SQLite format 3\u0000", it.readNBytes(16).decodeToString())
            }
            assertFalse(Files.exists(directory.resolve("state.json")))
        } finally { reopened.close() }
    }

    @Test fun reorderSwapsUniquePositionsWithoutDroppingDuplicateOccurrencesOrCursors() = runBlocking {
        val directory = roomWorkDirectory("reorder")
        val store = createPlatformLibraryStore(directory.toString())
        try {
            store.update { completeLibrary() }.getOrThrow()
            val result = store.update { old ->
                val queue = old.queue(QueueContext.MUSIC)
                old.copy(
                    queues = old.queues + (QueueContext.MUSIC to queue.copy(entries = queue.entries.reversed(), originalOrder = queue.originalOrder.reversed())),
                    playlists = old.playlists.map { it.copy(entries = it.entries.reversed()) }, history = old.history.reversed(),
                    albums = old.albums.mapValues { (_, a) -> a.copy(artistIds = a.artistIds.reversed(), trackIds = a.trackIds.reversed()) },
                    media = old.media.mapValues { (_, item) -> item.copy(artistIds = item.artistIds.reversed()) },
                    shows = old.shows.mapValues { (_, s) -> s.copy(episodeIds = s.episodeIds.reversed()) },
                )
            }.getOrThrow()
            assertEquals("music-occurrence-2", result.queue(QueueContext.MUSIC).currentEntryId)
            assertEquals(2, result.queue(QueueContext.MUSIC).entries.count { it.mediaId == "music-1" })
            assertEquals(result, store.flush().getOrThrow())
            withSql(directory.resolve("veyra.db")) { db ->
                listOf("queue_entries", "playlist_entries", "playback_history", "album_artists", "album_tracks", "track_artists", "podcast_episodes").forEach {
                    assertEquals(0L, db.longValue("SELECT count(*) FROM $it WHERE position < 0"), it)
                }
            }
        } finally { store.close() }
    }

    @Test fun sqliteAbortRollsBackStagedOrdersAndAllOtherChangesWithoutPublishing() = runBlocking<Unit> {
        val directory = roomWorkDirectory("rollback")
        val store = createPlatformLibraryStore(directory.toString())
        try {
            val before = store.update { completeLibrary() }.getOrThrow()
            withSql(directory.resolve("veyra.db")) { db ->
                db.execSQL("CREATE TRIGGER fail_favorite BEFORE INSERT ON favorites WHEN NEW.mediaId='music-2' BEGIN SELECT RAISE(ABORT, 'authored failure'); END")
            }
            val failure = store.update { old ->
                val queue = old.queue(QueueContext.MUSIC)
                old.copy(favoriteIds = old.favoriteIds + "music-2", settings = old.settings.copy(theme = ThemeMode.LIGHT),
                    queues = old.queues + (QueueContext.MUSIC to queue.copy(entries = queue.entries.reversed())))
            }
            assertIs<StoreResult.Failure>(failure)
            assertEquals(before, store.state.value)
            assertEquals(before, store.flush().getOrThrow())
            withSql(directory.resolve("veyra.db")) { db ->
                assertEquals(0L, db.longValue("SELECT count(*) FROM queue_entries WHERE position < 0"))
                db.execSQL("DROP TRIGGER fail_favorite")
            }
            assertIs<StoreResult.Success>(store.toggleFavorite(before.media.getValue("music-2")))
        } finally { store.close() }
    }

    @Test fun independentRoomWritersReadLatestRevisionAndNeverLoseOtherFields() = runBlocking {
        val path = roomWorkDirectory("writers").resolve("library.db")
        val first = LibraryStore(testDatabase(path))
        val second = LibraryStore(testDatabase(path))
        try {
            val item = music()
            first.upsertCatalog(listOf(item)).getOrThrow()
            second.initialize().getOrThrow()
            coroutineScope {
                listOf(
                    async(Dispatchers.Default) { repeat(12) { first.update { old -> old.copy(navigation = old.navigation + ("favorite-$it" to "committed"), favoriteIds = setOf(item.id)) }.getOrThrow() } },
                    async(Dispatchers.Default) { repeat(12) { second.update { old -> old.copy(navigation = old.navigation + ("progress-$it" to "committed"), progress = old.progress + (item.id to MediaProgress(positionMs = it.toLong()))) }.getOrThrow() } },
                    async(Dispatchers.Default) { repeat(12) { first.update { old -> old.copy(navigation = old.navigation + ("setting-$it" to "committed"), settings = old.settings.copy(crossfadeDurationMs = 3000L + it)) }.getOrThrow() } },
                    async(Dispatchers.Default) { repeat(12) { second.update { old -> old.copy(navigation = old.navigation + ("download-$it" to "committed"), downloads = mapOf("download" to DownloadRecord("download", item.id, bytesDownloaded = it.toLong()))) }.getOrThrow() } },
                ).awaitAll()
            }
            val result = first.flush().getOrThrow()
            assertEquals(48, result.navigation.size)
            assertEquals(setOf(item.id), result.favoriteIds)
            assertEquals(11L, result.progress.getValue(item.id).positionMs)
            assertEquals(3011L, result.settings.crossfadeDurationMs)
            assertEquals(11L, result.downloads.getValue("download").bytesDownloaded)
            assertEquals(result, second.flush().getOrThrow())
        } finally { first.close(); second.close() }
    }

    @Test fun explicitStaleRevisionDoesNotInvokeTransformOrChangeCommittedRows() = runBlocking {
        val store = createPlatformLibraryStore(roomWorkDirectory("cas").toString())
        try {
            val empty = store.initialize().getOrThrow()
            val committed = store.upsertCatalog(listOf(music())).getOrThrow()
            var invoked = false
            val failure = store.update(expectedRevision = empty.revision) { invoked = true; it.copy(favoriteIds = setOf("music-1")) }
            assertIs<StoreResult.Failure>(failure)
            assertFalse(invoked)
            assertEquals(committed, store.flush().getOrThrow())
        } finally { store.close() }
    }

    @Test fun downloadUniquenessLeaseAndAttemptFinalizationAreTransactional() = runBlocking {
        val path = roomWorkDirectory("downloads").resolve("library.db")
        val db = testDatabase(path)
        val first = LibraryStore(db)
        val second = LibraryStore(testDatabase(path))
        try {
            first.upsertCatalog(listOf(music())).getOrThrow()
            first.update { it.copy(downloads = mapOf("download" to DownloadRecord("download", "music-1"))) }.getOrThrow()
            second.initialize().getOrThrow()
            val claims = coroutineScope { listOf(async { first.claimDownload("download", "A", 1000) }, async { second.claimDownload("download", "B", 1000) }).awaitAll() }
            assertEquals(1, claims.count { it != null })
            val claimed = claims.single { it != null }!!
            assertNull(first.claimDownload("download", "C", 1001))
            val successor = second.claimDownload("download", "C", 62_000)!!
            assertEquals("C", successor.leaseOwner)
            assertIs<StoreResult.Failure>(first.updateDownloadWithAttempt(claimed.copy(bytesDownloaded = 99), expectedLeaseOwner = claimed.leaseOwner))
            val attempt = DownloadAttempt("attempt", "download", 1, DownloadAttemptStatus.FINALIZING)
            val receipt = DownloadFinalization("download", "music-1", "stage.part", "final.m4a", "a".repeat(64), 500, "attempt")
            first.update { old -> old.copy(downloads = old.downloads + ("download" to old.downloads.getValue("download").copy(activeAttemptId = attempt.id, attemptCount = 1)),
                downloadAttempts = mapOf(attempt.id to attempt), downloadFinalizations = mapOf("download" to receipt)) }.getOrThrow()
            val beforeDuplicate = second.flush().getOrThrow()
            assertEquals(receipt, beforeDuplicate.downloadFinalizations["download"])
            // Room @Upsert can absorb a non-PK uniqueness conflict and UPDATE an absent new PK
            // (zero rows). A raw INSERT is required to prove the SQL UNIQUE constraint itself.
            val existing = db.libraryDao().readDownloads().single()
            val duplicateFailure = assertFailsWith<Exception> {
                db.useWriterConnection { connection -> connection.immediateTransaction {
                    val columns = connection.usePrepared("PRAGMA table_info(downloads)") { statement ->
                        buildList { while (statement.step()) add(statement.getText(1)) }
                    }
                    val names = columns.joinToString(", ") { "\"${it.replace("\"", "\"\"")}\"" }
                    val values = columns.joinToString(", ") { if (it == "id") "?" else "\"${it.replace("\"", "\"\"")}\"" }
                    connection.usePrepared("INSERT INTO downloads ($names) SELECT $values FROM downloads WHERE id = ?") { statement ->
                        statement.bindText(1, "duplicate")
                        statement.bindText(2, existing.id)
                        statement.step()
                    }
                } }
            }
            assertTrue(duplicateFailure.message.orEmpty().contains("UNIQUE constraint failed: downloads.mediaId"))
            assertEquals(listOf(existing), db.libraryDao().readDownloads())
            assertEquals(beforeDuplicate, first.flush().getOrThrow())
            // The public store rejects duplicate media ownership before any SQL row is touched.
            assertIs<StoreResult.Failure>(first.update { old -> old.copy(downloads = old.downloads +
                ("duplicate" to old.downloads.getValue("download").copy(id = "duplicate"))) })
            assertEquals(beforeDuplicate, first.flush().getOrThrow())
            val afterDuplicate = second.flush().getOrThrow()
            assertEquals(beforeDuplicate, afterDuplicate)
            assertEquals(attempt, afterDuplicate.downloadAttempts[attempt.id])
            assertEquals(receipt, afterDuplicate.downloadFinalizations["download"])
        } finally { first.close(); second.close() }
    }

    @Test fun cleanupIntentBlocksOrdinaryClaimAndSurvivesRestart() = runBlocking {
        val directory = roomWorkDirectory("cleanup")
        val first = createPlatformLibraryStore(directory.toString())
        try {
            first.upsertCatalog(listOf(music())).getOrThrow()
            first.update { it.copy(downloads = mapOf("download" to DownloadRecord("download", "music-1", cleanupPending = true, removeWhenClean = true))) }.getOrThrow()
            assertNull(first.claimDownload("download", "new-worker", 5000))
        } finally { first.close() }
        val second = createPlatformLibraryStore(directory.toString())
        try {
            val record = second.initialize().getOrThrow().downloads.getValue("download")
            assertTrue(record.cleanupPending && record.removeWhenClean)
            assertNull(second.claimDownload("download", "restart-worker", 500_000))
        } finally { second.close() }
    }

    @Test fun cachedArtworkIsIndependentOfCatalogRefreshAndFavoriteFollowPlaylistChanges() = runBlocking {
        val store = createPlatformLibraryStore(roomWorkDirectory("artwork").toString())
        try {
            val initial = completeLibrary()
            store.update { initial }.getOrThrow()
            val track = initial.media.getValue("music-1").copy(localArtworkPath = null)
            val episode = initial.media.getValue("episode-1").copy(localArtworkPath = null)
            val show = initial.shows.getValue("show-1").copy(localArtworkPath = null)
            store.upsertCatalog(listOf(track, episode), listOf(show)).getOrThrow()
            store.toggleFavorite(track).getOrThrow()
            store.toggleSavedEpisode(episode).getOrThrow()
            store.toggleFollow(show).getOrThrow()
            store.addToPlaylist("playlist-1", track).getOrThrow()
            val result = store.flush().getOrThrow()
            assertEquals(initial.media[track.id]?.localArtworkPath, result.media[track.id]?.localArtworkPath)
            assertEquals(initial.media[episode.id]?.localArtworkPath, result.media[episode.id]?.localArtworkPath)
            assertEquals(initial.shows[show.id]?.localArtworkPath, result.shows[show.id]?.localArtworkPath)
        } finally { store.close() }
    }
}
