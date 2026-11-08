package dev.veyra.data

import androidx.sqlite.execSQL
import dev.veyra.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlin.test.*

/** Actual Room/SQLite write verification; no file, audio or source-acquisition success is implied. */
class RoomWriteVerificationTest {
    @Test fun ignoredSqlInsertRollsBackRevisionAndWholeGraphWithoutPublishingPartialRefresh() = runBlocking<Unit> {
        val directory = roomWorkDirectory("ignored-write")
        seedSchema2(directory, completeLibrary())
        val store = createPlatformLibraryStore(directory.toString())
        var observer: Job? = null
        try {
            val before = store.initialize().getOrThrow()
            val observed = mutableListOf<AppSnapshot>()
            observer = launch(start = CoroutineStart.UNDISPATCHED) { store.state.collect { observed += it } }
            withSql(directory.resolve("veyra.db")) { db ->
                db.execSQL("CREATE TRIGGER ignore_favorite BEFORE INSERT ON favorites WHEN NEW.mediaId='music-2' BEGIN SELECT RAISE(IGNORE); END")
            }
            val refreshed = before.copy(
                favoriteIds = before.favoriteIds + "music-2",
                media = before.media + ("music-1" to before.media.getValue("music-1").copy(title = "Refreshed complete discovery title")),
                shows = before.shows + ("show-1" to before.shows.getValue("show-1").copy(title = "Refreshed show title")),
                settings = before.settings.copy(theme = ThemeMode.LIGHT),
                queues = before.queues + (QueueContext.MUSIC to before.queue(QueueContext.MUSIC).let { it.copy(entries = it.entries.reversed()) }),
            )
            assertFailsWith<Exception> { writerUpdate(store) { refreshed } }
            assertEquals(FailureCode.STORAGE_IO, store.lastFailure.value?.code)
            assertEquals(before, store.state.value)
            assertEquals(before, store.flush().getOrThrow())
            yield()
            assertEquals(listOf(before), observed)
            withSql(directory.resolve("veyra.db")) { db ->
                assertEquals(before.revision, db.longValue("SELECT revision FROM store_meta WHERE id=0"))
                assertEquals(0L, db.longValue("SELECT count(*) FROM queue_entries WHERE position < 0"))
                db.execSQL("DROP TRIGGER ignore_favorite")
            }
            // A later genuine refresh succeeds once, without losing progress/artwork/downloads.
            val committed = writerUpdate(store) { refreshed }
            assertEquals(refreshed.copy(revision = before.revision + 1), committed)
            assertEquals(before.downloads, committed.downloads)
            assertEquals(before.progress, committed.progress)
            assertEquals(before.media.getValue("music-1").localArtworkPath, committed.media.getValue("music-1").localArtworkPath)
            assertEquals(committed, store.flush().getOrThrow())
        } finally {
            observer?.cancelAndJoin()
            store.close()
        }
    }

    @Test fun feedRenameBeforeNewIdentityInsertPreservesChildrenRegardlessOfMapOrderAndRestart() = runBlocking<Unit> {
        val directory = roomWorkDirectory("feed-reuse")
        seedSchema2(directory, completeLibrary())
        val store = createPlatformLibraryStore(directory.toString())
        val committed = try {
            val before = store.initialize().getOrThrow()
            val original = before.shows.getValue("show-1")
            val renamed = original.copy(feedUrl = "https://publisher.example.com/renamed-feed",
                feedAliases = original.feedAliases + original.feedUrl)
            val newcomer = PodcastShow("new-show", "New complete show title", original.feedUrl)
            val expected = before.copy(shows = linkedMapOf(newcomer.id to newcomer, renamed.id to renamed))
            val after = writerUpdate(store) { expected }
            assertEquals(expected.copy(revision = before.revision + 1), after)
            // New identity is deliberately first. Its UNIQUE conflict must not become a no-op
            // before the existing row moves, and no REPLACE may delete the old show's children.
            assertEquals(listOf("new-show", "show-1"), expected.shows.keys.toList())
            assertEquals(after, store.flush().getOrThrow())
            assertEquals(original.episodeIds, after.shows.getValue(original.id).episodeIds)
            assertEquals(before.media, after.media)
            assertEquals(before.downloads, after.downloads)
            assertEquals(before.followedShowIds, after.followedShowIds)
            assertEquals(before.settings.showSpeeds, after.settings.showSpeeds)
            withSql(directory.resolve("veyra.db")) { db ->
                assertEquals(2L, db.longValue("SELECT count(*) FROM podcasts"))
                assertEquals(2L, db.longValue("SELECT count(*) FROM episodes WHERE podcastId='show-1'"))
                assertEquals(2L, db.longValue("SELECT count(*) FROM podcast_episodes WHERE podcastId='show-1'"))
                assertEquals(1L, db.longValue("SELECT count(*) FROM followed_podcasts WHERE podcastId='show-1'"))
            }
            after
        } finally { store.close() }
        val reopened = createPlatformLibraryStore(directory.toString())
        try { assertEquals(committed, reopened.initialize().getOrThrow()) }
        finally { reopened.close() }
    }

    @Test fun cyclicUniqueFeedSwapFailsWithoutDroppingEpisodesOrAcknowledgingAnyChange() = runBlocking<Unit> {
        val directory = roomWorkDirectory("feed-swap")
        var store = createPlatformLibraryStore(directory.toString())
        val before = try {
            val fixture = completeLibrary()
            val another = PodcastShow("second-show", "Second complete show", "https://publisher.example.com/second-feed")
            seedSchema2(directory, fixture.copy(shows = fixture.shows + (another.id to another)))
            store = createPlatformLibraryStore(directory.toString())
            val before = store.initialize().getOrThrow()
            val first = before.shows.getValue("show-1")
            val failure = assertIs<StoreResult.Failure>(runCatching {
                writerUpdate(store) { old -> old.copy(
                    shows = linkedMapOf(first.id to first.copy(feedUrl = another.feedUrl), another.id to another.copy(feedUrl = first.feedUrl)),
                    media = old.media + ("music-1" to old.media.getValue("music-1").copy(title = "Must not be published")),
                    favoriteIds = old.favoriteIds + "music-2",
                ) }
            }.exceptionOrNull()?.let { StoreResult.Failure(failureOfForTest(it)) } ?: StoreResult.Success(before))
            assertIs<StoreResult.Failure>(failure)
            assertEquals(before, store.state.value)
            assertEquals(before, store.flush().getOrThrow())
            withSql(directory.resolve("veyra.db")) { db ->
                assertEquals(before.revision, db.longValue("SELECT revision FROM store_meta WHERE id=0"))
                assertEquals(2L, db.longValue("SELECT count(*) FROM episodes WHERE podcastId='show-1'"))
            }
            before
        } finally { store.close() }
        val reopened = createPlatformLibraryStore(directory.toString())
        try { assertEquals(before, reopened.initialize().getOrThrow()) }
        finally { reopened.close() }
    }

    @Test fun duplicateOfflineAssetIdentityIsRejectedWithoutLossyDeduplication() = runBlocking<Unit> {
        rejectsSharedOfflineAsset(duplicateId = true)
    }

    @Test fun duplicateOfflineAssetUriIsRejectedWithoutSharingFileOwnership() = runBlocking<Unit> {
        rejectsSharedOfflineAsset(duplicateId = false)
    }

    private suspend fun rejectsSharedOfflineAsset(duplicateId: Boolean) {
        val directory = roomWorkDirectory(if (duplicateId) "asset-id" else "asset-uri")
        val store = createPlatformLibraryStore(directory.toString())
        val expectedMessage = if (duplicateId) "Offline asset identities must be unique" else "Offline asset files must have one owner"
        val before = try {
            seedSchema2(directory, completeLibrary())
            val before = store.initialize().getOrThrow()
            val existingAsset = assertNotNull(before.downloads.getValue("download-1").asset)
            val episodeDownload = before.downloads.getValue("download-2")
            val collidingAsset = existingAsset.copy(
                id = if (duplicateId) existingAsset.id else "asset-2",
                mediaId = episodeDownload.mediaId,
                uri = if (duplicateId) "authored/offline/episode.m4a" else existingAsset.uri,
                executionBindingId = episodeDownload.executionBindingId,
            )
            val proposed = before.copy(downloads = before.downloads +
                (episodeDownload.id to episodeDownload.copy(asset = collidingAsset)), favoriteIds = before.favoriteIds + "music-2")
            val invalid = assertFailsWith<IllegalArgumentException> { validateLibrarySnapshot(proposed) }
            assertEquals(expectedMessage, invalid.message)

            // A genuine competing two-owner graph inside the SAME Room transaction. The raw-row
            // seam is intentional: the supported snapshot edit path rejects every physical asset
            // change before uniqueness is evaluated, so only this seam can prove that the actual
            // transactional guard (not an unrelated schema-3 veto) rejects the collision.
            val competing = assertFailsWith<IllegalArgumentException> {
                writerRowsUpdate(store) { rows ->
                    if (duplicateId) {
                        // Two current downloads claim the same offline asset identity.
                        rows.copy(downloads = rows.downloads.map { download ->
                            if (download.id == episodeDownload.id) download.copy(assetId = existingAsset.id) else download
                        })
                    } else {
                        // Two distinct asset ids claim the same owned file path.
                        val legacy = rows.assets.single { it.id == existingAsset.id }
                        rows.copy(
                            assets = rows.assets + legacy.copy(id = "asset-2", mediaId = episodeDownload.mediaId,
                                uri = existingAsset.uri, executionBindingId = episodeDownload.executionBindingId),
                            downloads = rows.downloads.map { download ->
                                if (download.id == episodeDownload.id) download.copy(assetId = "asset-2") else download
                            },
                        )
                    }
                }
            }
            assertEquals(expectedMessage, competing.message)
            assertEquals(before, store.state.value)
            assertEquals(before, store.flush().getOrThrow())
            withSql(directory.resolve("veyra.db")) { db ->
                assertEquals(1L, db.longValue("SELECT count(*) FROM offline_assets"))
                assertEquals(if (duplicateId) existingAsset.id else existingAsset.id,
                    db.textValue("SELECT assetId FROM downloads WHERE id='download-1'"))
                assertEquals(0L, db.longValue("SELECT count(*) FROM downloads WHERE id='download-2' AND assetId IS NOT NULL"))
                assertEquals(before.revision, db.longValue("SELECT revision FROM store_meta WHERE id=0"))
            }

            // The REAL SQL UNIQUE constraints are also exercised by an attempted duplicate row
            // in an explicit transaction, so ID and URI protection are independently proven.
            val duplicateFailure = assertFailsWith<Exception> {
                withSql(directory.resolve("veyra.db")) { db ->
                    db.execSQL("BEGIN IMMEDIATE")
                    try {
                        val columns = db.prepare("PRAGMA table_info(offline_assets)").use { statement ->
                            buildList { while (statement.step()) add(statement.getText(1)) }
                        }
                        val names = columns.joinToString(", ") { "\"${it.replace("\"", "\"\"")}\"" }
                        val chosen = columns.map { column ->
                            when {
                                // Duplicate PRIMARY identity with a distinct file path.
                                duplicateId && column == "id" -> "'${existingAsset.id}'"
                                duplicateId && column == "uri" -> "'authored/offline/duplicate-id.m4a'"
                                // Distinct identity that reuses the exact owned file path.
                                !duplicateId && column == "id" -> "'asset-2'"
                                !duplicateId && column == "uri" ->
                                    "(SELECT uri FROM offline_assets WHERE id='${existingAsset.id}')"
                                else -> "\"${column.replace("\"", "\"\"")}\""
                            }
                        }.joinToString(", ")
                        db.execSQL("INSERT INTO offline_assets ($names) SELECT $chosen FROM offline_assets WHERE id='${existingAsset.id}'")
                        db.execSQL("COMMIT")
                    } catch (failure: Throwable) {
                        db.execSQL("ROLLBACK")
                        throw failure
                    }
                }
            }
            assertTrue(duplicateFailure.message.orEmpty().contains(
                if (duplicateId) "UNIQUE constraint failed: offline_assets.id" else "UNIQUE constraint failed: offline_assets.uri"),
                "Expected the concrete ${if (duplicateId) "identity" else "path"} UNIQUE failure, was ${duplicateFailure.message}")
            withSql(directory.resolve("veyra.db")) { db ->
                assertEquals(1L, db.longValue("SELECT count(*) FROM offline_assets"))
                assertEquals(before.revision, db.longValue("SELECT revision FROM store_meta WHERE id=0"))
            }
            before
        } finally { store.close() }
        val reopened = createPlatformLibraryStore(directory.toString())
        try { assertEquals(before, reopened.initialize().getOrThrow()) }
        finally { reopened.close() }
    }
}
