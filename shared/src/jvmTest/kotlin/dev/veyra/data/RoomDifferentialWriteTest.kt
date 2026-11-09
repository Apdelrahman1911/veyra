package dev.veyra.data

import androidx.sqlite.execSQL
import dev.veyra.model.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.*

/** Authored real-Room regressions; not executed by source authoring or the original benchmark. */
class RoomDifferentialWriteTest {
    @Test fun oneHistoryPayloadCheckpointDoesNotStageOrRewriteIts999Neighbours() = runBlocking {
        val directory = roomWorkDirectory("history-payload-delta")
        val store = createPlatformLibraryStore(directory.toString())
        val committed = try {
            seedSchema2(directory, historyFixture(1_000))
            val before = store.initialize().getOrThrow()
            audit(directory.resolve("veyra.db"), "playback_history", "NEW.id")
            val expected = before.copy(history = before.history.mapIndexed { index, row ->
                if (index == 0) row.copy(lastPositionMs = row.lastPositionMs + 1) else row
            })
            val actual = writerUpdate(store) { expected }
            assertEquals(expected.copy(revision = before.revision + 1), actual)
            assertEquals(actual, store.flush().getOrThrow())
            counts(directory, updates = 1, staged = 0, inserts = 0)
            withSql(directory.resolve("veyra.db")) { db ->
                assertEquals(0L, db.longValue("SELECT count(*) FROM authored_writes WHERE row_key <> 'history-delta-0'"))
            }
            actual
        } finally { store.close() }
        val reopened = createPlatformLibraryStore(directory.toString())
        try { assertEquals(committed, reopened.initialize().getOrThrow()) }
        finally { reopened.close() }
    }

    @Test fun playlistTitleChangeDoesNotStageOrderOrRewriteAnotherPlaylist() = runBlocking {
        val directory = roomWorkDirectory("playlist-payload-delta")
        val store = createPlatformLibraryStore(directory.toString())
        try {
            seedSchema2(directory, completeLibrary().let { it.copy(playlists = it.playlists + Playlist("playlist-2", "Untouched")) })
            val before = store.initialize().getOrThrow()
            audit(directory.resolve("veyra.db"), "playlists", "NEW.id")
            val actual = store.editCatalogLibrary { old -> old.copy(playlists = old.playlists.mapIndexed { index, row ->
                if (index == 0) row.copy(name = "Changed complete title") else row
            }) }.snapshot
            assertEquals(before.playlists[1], actual.playlists[1])
            counts(directory, updates = 1, staged = 0, inserts = 0)
            assertEquals(actual, store.flush().getOrThrow())
        } finally { store.close() }
    }

    @Test fun smallHistorySwapStagesOnlyTheTwoChangedUniquePositions() = runBlocking {
        val directory = roomWorkDirectory("history-order-delta")
        val store = createPlatformLibraryStore(directory.toString())
        try {
            seedSchema2(directory, historyFixture(5))
            val before = store.initialize().getOrThrow()
            audit(directory.resolve("veyra.db"), "playback_history", "NEW.id")
            val order = listOf(before.history[1], before.history[0]) + before.history.drop(2)
            val actual = writerUpdate(store) { it.copy(history = order) }
            assertEquals(order, actual.history)
            counts(directory, updates = 4, staged = 2, inserts = 0)
            withSql(directory.resolve("veyra.db")) { db ->
                assertEquals(0L, db.longValue("SELECT count(*) FROM authored_writes WHERE row_key NOT IN ('history-delta-0','history-delta-1')"))
            }
            assertEquals(actual, store.flush().getOrThrow())
        } finally { store.close() }
    }

    @Test fun musicQueueReorderPreservesBothUniqueOrdersAndNeverWritesPodcastRows() = runBlocking {
        val directory = roomWorkDirectory("queue-order-delta")
        val store = createPlatformLibraryStore(directory.toString())
        try {
            seedSchema2(directory, completeLibrary())
            val before = store.initialize().getOrThrow()
            audit(directory.resolve("veyra.db"), "queue_entries", "NEW.contextId || ':' || NEW.id")
            val queueBefore = store.acquisition.state.value.queues.getValue(QueueContext.MUSIC)
            val order = queueBefore.displayedOrder.reversed()
            store.acquisition.applyPlayback(dev.veyra.data.AcquisitionPlaybackCommand.EditQueue(
                dev.veyra.data.ExecutionQueueExpectation(QueueContext.MUSIC, queueBefore.editRevision, queueBefore.currentOccurrenceId),
                order, queueBefore.originalOrder, queueBefore.currentOccurrenceId, queueBefore.positionMs, queueBefore.speed,
                queueBefore.shuffle, queueBefore.repeat, outgoing = null))
            val actual = store.flush().getOrThrow()
            assertEquals(before.queue(QueueContext.PODCAST), actual.queue(QueueContext.PODCAST))
            assertEquals(before.queue(QueueContext.MUSIC).originalOrder, actual.queue(QueueContext.MUSIC).originalOrder)
            assertEquals(before.queue(QueueContext.MUSIC).currentEntryId, actual.queue(QueueContext.MUSIC).currentEntryId)
            counts(directory, updates = 4, staged = 2, inserts = 0)
            withSql(directory.resolve("veyra.db")) { db ->
                assertEquals(0L, db.longValue("SELECT count(*) FROM authored_writes WHERE row_key LIKE 'PODCAST:%'"))
                assertEquals(0L, db.longValue("SELECT count(*) FROM queue_entries WHERE position < 0 OR originalPosition < 0"))
            }
            assertEquals(actual, store.flush().getOrThrow())
        } finally { store.close() }
    }

    @Test fun mixedHistoryRemovalInsertionAndMovesRetainExactGraph() = runBlocking {
        val directory = roomWorkDirectory("history-mixed-delta")
        val store = createPlatformLibraryStore(directory.toString())
        try {
            seedSchema2(directory, historyFixture(5))
            val before = store.initialize().getOrThrow()
            audit(directory.resolve("veyra.db"), "playback_history", "NEW.id")
            val added = before.history[1].copy(id = "history-new", startedEpochMs = 10_000)
            val order = listOf(before.history[4], added, before.history[0], before.history[2])
            val actual = writerUpdate(store) { it.copy(history = order) }
            assertEquals(order, actual.history)
            counts(directory, updates = 6, staged = 3, inserts = 1)
            assertEquals(actual, store.flush().getOrThrow())
        } finally { store.close() }
    }

    @Test fun compositeArtistOrderStagesOnlyChangedTrackKeys() = runBlocking {
        val directory = roomWorkDirectory("artist-order-delta")
        val store = createPlatformLibraryStore(directory.toString())
        try {
            seedSchema2(directory, completeLibrary())
            store.initialize().getOrThrow()
            audit(directory.resolve("veyra.db"), "track_artists", "NEW.trackId || ':' || NEW.artistId")
            val actual = store.update { old ->
                val track = old.media.getValue("music-1")
                old.copy(media = old.media + (track.id to track.copy(artistIds = track.artistIds.reversed())))
            }.getOrThrow()
            counts(directory, updates = 4, staged = 2, inserts = 0)
            withSql(directory.resolve("veyra.db")) { db ->
                assertEquals(0L, db.longValue("SELECT count(*) FROM authored_writes WHERE row_key LIKE 'music-2:%'"))
            }
            assertEquals(actual, store.flush().getOrThrow())
        } finally { store.close() }
    }

    @Test fun ignoredPayloadUpdateStillFailsFullReadbackAndRollsBackUnrelatedChanges() = runBlocking {
        val directory = roomWorkDirectory("ignored-history-delta")
        val store = createPlatformLibraryStore(directory.toString())
        try {
            seedSchema2(directory, historyFixture(3))
            val before = store.initialize().getOrThrow()
            withSql(directory.resolve("veyra.db")) { db ->
                db.execSQL("CREATE TRIGGER ignore_history BEFORE UPDATE ON playback_history WHEN NEW.id='history-delta-0' BEGIN SELECT RAISE(IGNORE); END")
            }
            assertFailsWith<Exception> {
                writerUpdate(store) { old -> old.copy(
                    history = old.history.mapIndexed { index, row -> if (index == 0) row.copy(lastPositionMs = 12) else row },
                    settings = old.settings.copy(theme = ThemeMode.LIGHT),
                ) }
            }
            assertEquals(FailureCode.STORAGE_IO, store.lastFailure.value?.code)
            assertEquals(before, store.state.value)
            assertEquals(before, store.flush().getOrThrow())
        } finally { store.close() }
    }

    @Test fun abortAfterTargetedStagingRollsBackEveryNegativePositionAndRevision() = runBlocking {
        val directory = roomWorkDirectory("aborted-order-delta")
        val store = createPlatformLibraryStore(directory.toString())
        try {
            seedSchema2(directory, historyFixture(5))
            val before = store.initialize().getOrThrow()
            withSql(directory.resolve("veyra.db")) { db ->
                db.execSQL("CREATE TRIGGER abort_history BEFORE UPDATE ON playback_history WHEN OLD.position < 0 AND NEW.position >= 0 AND NEW.id='history-delta-0' BEGIN SELECT RAISE(ABORT, 'authored order rejection'); END")
            }
            assertFailsWith<Exception> { writerUpdate(store) { it.copy(history = it.history.reversed()) } }
            assertEquals(before, store.state.value)
            assertEquals(before, store.flush().getOrThrow())
            withSql(directory.resolve("veyra.db")) { db ->
                assertEquals(0L, db.longValue("SELECT count(*) FROM playback_history WHERE position < 0"))
                assertEquals(before.revision, db.longValue("SELECT revision FROM store_meta"))
            }
        } finally { store.close() }
    }

    @Test fun orderChangeLargerThanOneBindChunkStagesAllRowsBeforeAnyUpsert() = runBlocking {
        val directory = roomWorkDirectory("chunked-order-delta")
        val store = createPlatformLibraryStore(directory.toString())
        try {
            seedSchema2(directory, historyFixture(513))
            val before = store.initialize().getOrThrow()
            audit(directory.resolve("veyra.db"), "playback_history", "NEW.id")
            val actual = writerUpdate(store) { it.copy(history = it.history.reversed()) }
            assertEquals(before.history.reversed(), actual.history)
            // Odd midpoint retains its order; all other rows are staged then updated once.
            counts(directory, updates = 1_024, staged = 512, inserts = 0)
            assertEquals(actual, store.flush().getOrThrow())
        } finally { store.close() }
    }

    private fun historyFixture(count: Int): AppSnapshot = completeLibrary().let { base ->
        base.copy(history = List(count) { index -> base.history.first().copy(
            id = "history-delta-$index", startedEpochMs = 1_000L + index, lastPositionMs = 1,
        ) })
    }

    private fun audit(path: Path, table: String, rowKey: String) = withSql(path) { db ->
        // These identifiers are fixed authored test constants, never runtime/user SQL input.
        db.execSQL("CREATE TABLE authored_writes (operation TEXT NOT NULL, row_key TEXT NOT NULL, staged INTEGER NOT NULL)")
        db.execSQL("CREATE TRIGGER authored_update AFTER UPDATE ON $table BEGIN INSERT INTO authored_writes VALUES ('UPDATE', $rowKey, CASE WHEN NEW.position < 0 THEN 1 ELSE 0 END); END")
        db.execSQL("CREATE TRIGGER authored_insert AFTER INSERT ON $table BEGIN INSERT INTO authored_writes VALUES ('INSERT', $rowKey, 0); END")
    }

    private fun counts(directory: Path, updates: Long, staged: Long, inserts: Long) = withSql(directory.resolve("veyra.db")) { db ->
        assertEquals(updates, db.longValue("SELECT count(*) FROM authored_writes WHERE operation='UPDATE'"))
        assertEquals(staged, db.longValue("SELECT count(*) FROM authored_writes WHERE staged=1"))
        assertEquals(inserts, db.longValue("SELECT count(*) FROM authored_writes WHERE operation='INSERT'"))
        assertFalse(db.prepare("PRAGMA foreign_key_check").use { it.step() })
    }
}
