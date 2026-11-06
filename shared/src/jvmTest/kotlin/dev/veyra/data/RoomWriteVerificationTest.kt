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
        val store = createPlatformLibraryStore(directory.toString())
        var observer: Job? = null
        try {
            val before = store.update { completeLibrary() }.getOrThrow()
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
            val failure = assertIs<StoreResult.Failure>(store.update { refreshed })
            assertEquals(FailureCode.STORAGE_IO, failure.failure.code)
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
            val committed = store.update { refreshed }.getOrThrow()
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
        val store = createPlatformLibraryStore(directory.toString())
        val committed = try {
            val before = store.update { completeLibrary() }.getOrThrow()
            val original = before.shows.getValue("show-1")
            val renamed = original.copy(feedUrl = "https://publisher.example.com/renamed-feed",
                feedAliases = original.feedAliases + original.feedUrl)
            val newcomer = PodcastShow("new-show", "New complete show title", original.feedUrl)
            val expected = before.copy(shows = linkedMapOf(newcomer.id to newcomer, renamed.id to renamed))
            val after = store.update { expected }.getOrThrow()
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
        val store = createPlatformLibraryStore(directory.toString())
        val before = try {
            val fixture = completeLibrary()
            val another = PodcastShow("second-show", "Second complete show", "https://publisher.example.com/second-feed")
            val before = store.update { fixture.copy(shows = fixture.shows + (another.id to another)) }.getOrThrow()
            val first = before.shows.getValue("show-1")
            val failure = store.update { old -> old.copy(
                shows = linkedMapOf(first.id to first.copy(feedUrl = another.feedUrl), another.id to another.copy(feedUrl = first.feedUrl)),
                media = old.media + ("music-1" to old.media.getValue("music-1").copy(title = "Must not be published")),
                favoriteIds = old.favoriteIds + "music-2",
            ) }
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
        val before = try {
            val before = store.update { completeLibrary() }.getOrThrow()
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
            assertEquals(if (duplicateId) "Offline asset identities must be unique" else "Offline asset files must have one owner", invalid.message)
            val failure = assertIs<StoreResult.Failure>(store.update { proposed })
            assertEquals(FailureCode.INVALID_STATE, failure.failure.code)
            assertEquals(before, store.state.value)
            assertEquals(before, store.flush().getOrThrow())
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
