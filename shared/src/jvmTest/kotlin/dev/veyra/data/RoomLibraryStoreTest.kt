package dev.veyra.data

import androidx.sqlite.execSQL
import dev.veyra.data.room.*
import dev.veyra.downloads.EXECUTOR_REPRESENTATION
import dev.veyra.downloads.authoredBytes
import dev.veyra.downloads.executorSchema3Case
import dev.veyra.model.*
import kotlinx.coroutines.*
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Real Room/bundled SQLite tests; no substitute snapshot backend is used here. */
class RoomLibraryStoreTest {
    @Test fun allNormalizedRelationsAndTwoOccurrenceQueuesSurviveColdReopen() = runBlocking {
        val directory = roomWorkDirectory("roundtrip")
        seedSchema2(directory, completeLibrary())
        val store = createPlatformLibraryStore(directory.toString())
        val committed = try {
            val snapshot = store.initialize().getOrThrow()
            assertEquals(0L, snapshot.revision)
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
            seedSchema2(directory, completeLibrary())
            store.initialize().getOrThrow()
            val result = writerUpdate(store) { old ->
                val queue = old.queue(QueueContext.MUSIC)
                old.copy(
                    queues = old.queues + (QueueContext.MUSIC to queue.copy(entries = queue.entries.reversed(), originalOrder = queue.originalOrder.reversed())),
                    playlists = old.playlists.map { it.copy(entries = it.entries.reversed()) }, history = old.history.reversed(),
                    albums = old.albums.mapValues { (_, a) -> a.copy(artistIds = a.artistIds.reversed(), trackIds = a.trackIds.reversed()) },
                    media = old.media.mapValues { (_, item) -> item.copy(artistIds = item.artistIds.reversed()) },
                    shows = old.shows.mapValues { (_, s) -> s.copy(episodeIds = s.episodeIds.reversed()) },
                )
            }
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
            seedSchema2(directory, completeLibrary())
            val before = store.initialize().getOrThrow()
            withSql(directory.resolve("veyra.db")) { db ->
                db.execSQL("CREATE TRIGGER fail_favorite BEFORE INSERT ON favorites WHEN NEW.mediaId='music-2' BEGIN SELECT RAISE(ABORT, 'authored failure'); END")
            }
            assertFailsWith<Exception> {
                writerUpdate(store) { old ->
                    val queue = old.queue(QueueContext.MUSIC)
                    old.copy(favoriteIds = old.favoriteIds + "music-2", settings = old.settings.copy(theme = ThemeMode.LIGHT),
                        queues = old.queues + (QueueContext.MUSIC to queue.copy(entries = queue.entries.reversed())))
                }
            }
            assertEquals(FailureCode.STORAGE_IO, store.lastFailure.value?.code)
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
        val directory = roomWorkDirectory("writers")
        seedSchema2(directory, completeLibrary())
        val path = directory.resolve("veyra.db")
        val first = LibraryStore(testDatabase(path))
        val second = LibraryStore(testDatabase(path))
        try {
            val item = first.initialize().getOrThrow().media.getValue("music-1")
            second.initialize().getOrThrow()
            coroutineScope {
                listOf(
                    async(Dispatchers.Default) { repeat(12) { first.editCatalogLibrary { old -> old.copy(favoriteIds = setOf(item.id)) } } },
                    async(Dispatchers.Default) { repeat(12) { second.update { old -> old.copy(navigation = old.navigation + ("progress-$it" to "committed")) }.getOrThrow() } },
                    async(Dispatchers.Default) { repeat(12) { first.update { old -> old.copy(navigation = old.navigation + ("setting-$it" to "committed"), settings = old.settings.copy(crossfadeDurationMs = 3000L + it)) }.getOrThrow() } },
                    async(Dispatchers.Default) { repeat(12) { second.update { old -> old.copy(navigation = old.navigation + ("download-$it" to "committed")) }.getOrThrow() } },
                ).awaitAll()
            }
            val result = first.flush().getOrThrow()
            assertEquals(38, result.navigation.size)
            assertEquals(setOf(item.id), result.favoriteIds)
            assertEquals(3011L, result.settings.crossfadeDurationMs)
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

    @Test fun downloadUniquenessLeaseAndAttemptFinalizationAreTransactional() = executorSchema3Case {
        val first = owner(store())
        val second = owner(store())
        val (downloadId, selection) = enqueue(first)
        val admitted = join(first, downloadId)
        assertEquals(1, admitted.claims.size)
        assertEquals(selection.context.subject.id, admitted.job.producer.subjectId)
        assertEquals(selection.context.binding.id, admitted.job.producer.bindingId)
        assertEquals(selection.context.recording.id, admitted.job.producer.recordingId)

        // Two independent real Room owners race the same canonical physical claim. Exactly one
        // generation CAS may become the durable owner; the other is a stale rejection.
        val claimedAt = clock.epochMs()
        val attemptA = newEntityId("claim-a")
        val attemptB = newEntityId("claim-b")
        val pathA = files.part(selection.item.id, attemptA).toString()
        val pathB = files.part(selection.item.id, attemptB).toString()
        val claims = coroutineScope {
            listOf(
                async(Dispatchers.Default) {
                    first.repository.claimPhysicalDownloadAttempt(ClaimPhysicalDownloadAttempt(downloadId,
                        admitted.job.producer, admitted.job.ownershipGeneration, attemptA, "A", claimedAt, 1_000, pathA))
                },
                async(Dispatchers.Default) {
                    second.repository.claimPhysicalDownloadAttempt(ClaimPhysicalDownloadAttempt(downloadId,
                        admitted.job.producer, admitted.job.ownershipGeneration, attemptB, "B", claimedAt, 1_000, pathB))
                },
            ).awaitAll()
        }
        val winner = claims.filterIsInstance<PhysicalDownloadCommit.Committed<ClaimedPhysicalDownload>>().single()
        assertIs<PhysicalDownloadCommit.Stale>(claims.single { it is PhysicalDownloadCommit.Stale })
        val claimed = winner.value
        assertEquals(1, join(first, downloadId).claims.size)
        assertEquals(claimed.fence.leaseOwner, join(first, downloadId).job.leaseOwner)

        // A live lease rejects a third owner before its documented deadline.
        val liveAttempt = newEntityId("claim-live")
        assertIs<PhysicalDownloadCommit.Stale>(second.repository.claimPhysicalDownloadAttempt(
            ClaimPhysicalDownloadAttempt(downloadId, admitted.job.producer, claimed.fence.ownershipGeneration,
                liveAttempt, "C", claimedAt + 1, 1_000, files.part(selection.item.id, liveAttempt).toString())))

        // Expiry releases only the durable intent; a successor can claim after reconciliation.
        advance(62_000 - clock.epochMs())
        val expiring = join(first, downloadId)
        assertEquals(claimedAt + 1_000, expiring.job.leaseExpiresEpochMs)
        assertTrue(expiring.job.leaseExpiresEpochMs!! <= clock.epochMs())
        val reconciled = assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(
            first.repository.reconcilePhysicalDownloadIntent(ReconcilePhysicalDownloadIntent(downloadId,
                expiring.job.producer, expiring.job.ownershipGeneration, expiring.claims, clock.epochMs()))).value
        assertNull(reconciled.job.leaseOwner)
        assertEquals(DownloadStatus.QUEUED, reconciled.job.status)
        assertTrue(reconciled.attempts.single { it.id == claimed.fence.attemptId }.finishedEpochMs != null)
        val successorAttempt = newEntityId("claim-c")
        val successorPath = files.prepareStagingPath(files.part(selection.item.id, successorAttempt).toString()).toString()
        val successor = assertIs<PhysicalDownloadCommit.Committed<ClaimedPhysicalDownload>>(
            second.repository.claimPhysicalDownloadAttempt(ClaimPhysicalDownloadAttempt(downloadId,
                reconciled.job.producer, reconciled.job.ownershipGeneration, successorAttempt, "C", clock.epochMs(),
                1_000, successorPath))).value
        assertEquals("C", successor.fence.leaseOwner)

        // The old owner's fence cannot heartbeat, finish, or otherwise update the successor.
        assertIs<PhysicalDownloadCommit.Stale>(first.repository.heartbeatPhysicalDownload(
            HeartbeatPhysicalDownload(claimed.fence, clock.epochMs() + 1, 1_000, 99, null)))
        assertIs<PhysicalDownloadCommit.Stale>(first.repository.finishPhysicalDownloadAttempt(
            FinishPhysicalDownloadAttempt(claimed.fence, DownloadStatus.QUEUED, DownloadAttemptStatus.PAUSED,
                null, null, 0, clock.epochMs() + 1)))
        assertEquals("C", join(first, downloadId).job.leaseOwner)

        // A real interrupted FINALIZING journal survives the rejected collisions below.
        Files.write(Path.of(successorPath), authoredBytes)
        val representation = EXECUTOR_REPRESENTATION.representationId
        assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(second.repository.selectPhysicalDownloadRepresentation(
            SelectPhysicalDownloadRepresentation(successor.fence, representation, 1, null, null, clock.epochMs())))
        assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(second.repository.heartbeatPhysicalDownload(
            HeartbeatPhysicalDownload(successor.fence, clock.epochMs(), 1_000, authoredBytes.size.toLong(), authoredBytes.size.toLong())))
        assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(second.repository.beginPhysicalDownloadValidation(
            BeginPhysicalDownloadValidation(successor.fence, clock.epochMs())))
        val intent = PhysicalDownloadFinalization(downloadId, admitted.job.producer, successorPath,
            files.finalPath(selection.item.id, 1, "audio/mpeg", successorAttempt).toString(),
            files.sha256(successorPath.toPath()), authoredBytes.size.toLong(), successorAttempt, 733, "audio/mpeg", "mp3",
            representation, 1, successor.fence.ownershipGeneration,
            MaterializationAssetId(newEntityId("authored-materialization")))
        assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(second.repository.preparePhysicalDownloadFinalization(
            PreparePhysicalDownloadFinalization(successor.fence, intent, clock.epochMs())))
        // A second genuine catalogue subject lets the raw row duplicate ONLY the owned mediaId;
        // otherwise the separate subjectId UNIQUE index would answer first and mask the exact
        // media-ownership constraint this test claims to exercise.
        first.store.upsertCatalog(listOf(music("duplicate-subject-media"))).getOrThrow()
        val beforeDuplicate = second.store.acquisitionView().acquisition
        assertEquals(DownloadAttemptStatus.FINALIZING, beforeDuplicate.physicalAttempts.getValue(successorAttempt).status)
        assertEquals(intent, beforeDuplicate.physicalFinalizations[downloadId])

        // Room @Upsert can absorb a non-PK uniqueness conflict and UPDATE an absent new PK
        // (zero rows). A raw INSERT is required to prove the SQL UNIQUE constraint itself.
        val existing = withSql(databasePath) { db ->
            db.prepare("SELECT id, mediaId FROM downloads").use { statement ->
                assertTrue(statement.step())
                val id = statement.getText(0)
                val mediaId = statement.getText(1)
                assertFalse(statement.step(), "Exactly one admitted physical download row")
                id to mediaId
            }
        }
        assertEquals(selection.item.id, existing.second)
        val duplicateFailure = assertFailsWith<Exception> {
            withSql(databasePath) { db ->
                db.execSQL("BEGIN IMMEDIATE")
                try {
                    val columns = db.prepare("PRAGMA table_info(downloads)").use { statement ->
                        buildList { while (statement.step()) add(statement.getText(1)) }
                    }
                    val names = columns.joinToString(", ") { "\"${it.replace("\"", "\"\"")}\"" }
                    val values = columns.joinToString(", ") {
                        when (it) {
                            "id" -> "?"
                            "subjectId" -> "'duplicate-subject-media'"
                            else -> "\"${it.replace("\"", "\"\"")}\""
                        }
                    }
                    db.prepare("INSERT INTO downloads ($names) SELECT $values FROM downloads WHERE id = ?").use { statement ->
                        statement.bindText(1, "duplicate")
                        statement.bindText(2, existing.first)
                        statement.step()
                    }
                    db.execSQL("COMMIT")
                } catch (failure: Throwable) {
                    db.execSQL("ROLLBACK")
                    throw failure
                }
            }
        }
        assertTrue(duplicateFailure.message.orEmpty().contains("UNIQUE constraint failed: downloads.mediaId"),
            "Expected the concrete downloads.mediaId UNIQUE failure, was ${duplicateFailure.message}")
        assertEquals(beforeDuplicate, second.store.acquisitionView().acquisition)
        withSql(databasePath) { db ->
            assertEquals(1L, db.longValue("SELECT count(*) FROM downloads"))
        }

        // A second accepted recording for the same owned media is rejected by canonical admission
        // before any row is written; the whole graph/revision and the journal remain intact.
        val conflictingSelection = select(second, selection.item, "BBBBBBBBBBB")
        val beforeConflict = second.store.acquisitionView().acquisition
        val conflict = assertFailsWith<VeyraException> { enqueue(second, conflictingSelection) }
        assertEquals(FailureCode.INVALID_STATE, conflict.failure.code)
        assertEquals("download_has_different_recording", conflict.failure.detail)
        val afterConflict = second.store.acquisitionView().acquisition
        assertEquals(beforeConflict, afterConflict)
        assertEquals(intent, afterConflict.physicalFinalizations.getValue(downloadId))
        assertEquals(DownloadAttemptStatus.FINALIZING, afterConflict.physicalAttempts.getValue(successorAttempt).status)
        assertEquals(1, afterConflict.physicalDownloads.size)
    }

    @Test fun cleanupIntentBlocksOrdinaryClaimAndSurvivesRestart() = executorSchema3Case {
        val firstOwner = owner(store())
        val (downloadId, selection) = enqueue(firstOwner)
        val admitted = join(firstOwner, downloadId)
        val cleanupAttempt = newEntityId("cleanup-owner")
        val claimed = assertIs<PhysicalDownloadCommit.Committed<ClaimedPhysicalDownload>>(
            firstOwner.repository.claimPhysicalDownloadAttempt(ClaimPhysicalDownloadAttempt(downloadId,
                admitted.job.producer, admitted.job.ownershipGeneration, cleanupAttempt, "initial-owner",
                clock.epochMs(), 1_000, files.part(selection.item.id, cleanupAttempt).toString()))).value
        advance(62_000 - clock.epochMs())
        val expiring = join(firstOwner, downloadId)
        val reconciled = assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(
            firstOwner.repository.reconcilePhysicalDownloadIntent(ReconcilePhysicalDownloadIntent(downloadId,
                expiring.job.producer, expiring.job.ownershipGeneration, expiring.claims, clock.epochMs()))).value
        val claim = reconciled.claims.single()
        assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(firstOwner.repository.updateStandaloneDownloadIntent(
            UpdateStandaloneDownloadIntent(downloadId, reconciled.job.producer, reconciled.job.ownershipGeneration,
                claim.id, claim.generation, claim.ownerIntentRevision, DownloadBatchIntent.CANCELLED,
                retainCompletedAsset = false, nowEpochMs = clock.epochMs())))
        val cleaning = firstOwner.store.acquisitionView().acquisition.physicalDownloads.getValue(downloadId)
        assertTrue(cleaning.cleanupPending && cleaning.removeWhenClean)
        assertEquals(DownloadStatus.CANCELLED, cleaning.status)
        val newWorkerAttempt = newEntityId("cleanup-new-worker")
        assertIs<PhysicalDownloadCommit.Stale>(firstOwner.repository.claimPhysicalDownloadAttempt(
            ClaimPhysicalDownloadAttempt(downloadId, cleaning.producer, cleaning.ownershipGeneration,
                newWorkerAttempt, "new-worker", clock.epochMs(), 1_000,
                files.part(selection.item.id, newWorkerAttempt).toString())))

        firstOwner.coordinator.close()
        closeStore(firstOwner.store)
        val reopenedOwner = owner(store())
        val reopenedJoin = join(reopenedOwner, downloadId)
        val reopened = reopenedJoin.job
        assertTrue(reopened.cleanupPending && reopened.removeWhenClean)
        assertEquals(DownloadStatus.CANCELLED, reopened.status)
        assertNull(reopened.leaseOwner)
        assertEquals(claimed.fence.attemptId, reopenedJoin.attempts.single().id)
        assertNotNull(reopenedJoin.attempts.single().finishedEpochMs)
        val restartWorkerAttempt = newEntityId("cleanup-restart-worker")
        assertIs<PhysicalDownloadCommit.Stale>(reopenedOwner.repository.claimPhysicalDownloadAttempt(
            ClaimPhysicalDownloadAttempt(downloadId, reopened.producer, reopened.ownershipGeneration,
                restartWorkerAttempt, "restart-worker", clock.epochMs() + 500_000, 1_000,
                files.part(selection.item.id, restartWorkerAttempt).toString())))
    }

    @Test fun cachedArtworkIsIndependentOfCatalogRefreshAndFavoriteFollowPlaylistChanges() = runBlocking {
        val directory = roomWorkDirectory("artwork")
        val initial = completeLibrary()
        seedSchema2(directory, initial)
        val store = createPlatformLibraryStore(directory.toString())
        try {
            store.initialize().getOrThrow()
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
