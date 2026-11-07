package dev.veyra.data

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.veyra.data.room.toRows
import androidx.sqlite.execSQL
import dev.veyra.data.room.VeyraDatabase
import dev.veyra.data.room.VeyraDatabaseConstructor
import dev.veyra.data.room.buildVeyraDatabase
import dev.veyra.model.*
import java.nio.file.Files
import java.nio.file.Path

internal fun projectRoot(): Path = System.getenv("VEYRA_PROJECT_ROOT")?.let(Path::of)
    ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

internal fun roomWorkDirectory(prefix: String): Path {
    val parent = projectRoot().resolve(".work/architecture-tests")
    Files.createDirectories(parent)
    return Files.createTempDirectory(parent, "$prefix-")
}

internal fun testDatabase(path: Path): VeyraDatabase = buildVeyraDatabase(
    Room.databaseBuilder<VeyraDatabase>(path.toString(), factory = VeyraDatabaseConstructor::initialize),
)

internal fun <T> withSql(path: Path, block: (SQLiteConnection) -> T): T =
    BundledSQLiteDriver().open(path.toString()).use { connection ->
        connection.execSQL("PRAGMA busy_timeout = 5000")
        connection.execSQL("PRAGMA foreign_keys = ON")
        block(connection)
    }

internal fun SQLiteConnection.longValue(sql: String): Long = prepare(sql).use { check(it.step()); it.getLong(0) }
internal fun SQLiteConnection.textValue(sql: String): String = prepare(sql).use { check(it.step()); it.getText(0) }

internal fun music(id: String = "music-1") = MediaItem(id, "Full track title $id — موسيقى", MediaKind.MUSIC,
    SourceRef(SourceProvider.COMMONS, id, canonicalUrl = "https://commons.wikimedia.org/wiki/File:$id"),
    creator = "Authored metadata creator", durationMs = 123_000)

internal fun binding(item: MediaItem, id: String = "binding-${item.id}", video: String = "AAAAAAAAAAA") = PlaybackBinding(
    id, item.id, item.title, FirstResultKind.VIDEO, video, "Authored FIRST result title",
    "https://www.youtube.com/watch?v=$video", video, resolvedEpochMs = 1234,
)

/** Authored relational metadata fixture; file/URI values are inert and no media playback is claimed. */
internal fun completeLibrary(): AppSnapshot {
    val artists = listOf(Artist("artist-1", "الفنان الأول"), Artist("artist-2", "Second artist"))
    val first = music().copy(artistIds = artists.map { it.id }, albumId = "album-1", trackNumber = 1,
        discNumber = 1, localArtworkPath = "authored/cache/track.jpg")
    val second = music("music-2").copy(artistIds = listOf("artist-2"), albumId = "album-1", trackNumber = 2, discNumber = 1)
    val episode = MediaItem("episode-1", "العنوان الكامل للحلقة", MediaKind.EPISODE,
        SourceRef(SourceProvider.RSS, "original-guid-1", feedUrl = "https://publisher.example.com/feed",
            episodeGuid = "original-guid-1", enclosureUrl = "https://publisher.example.com/discovery-only.mp3"),
        showId = "show-1", episodeNumber = 7, seasonNumber = 2, durationMs = 999_000,
        localArtworkPath = "authored/cache/episode.jpg")
    val episode2 = episode.copy(id = "episode-2", title = "Second complete episode title",
        source = episode.source.copy(canonicalId = "original-guid-2", episodeGuid = "original-guid-2"), episodeNumber = 8)
    val show = PodcastShow("show-1", "Authored show", "https://publisher.example.com/feed",
        publisher = "Original publisher", language = "ar", episodeIds = listOf(episode.id, episode2.id),
        feedAliases = setOf("https://publisher.example.com/old-feed"), etag = "\"metadata-validator\"",
        lastModified = "Mon, 02 Nov 2026 12:00:00 GMT", localArtworkPath = "authored/cache/show.jpg")
    val firstBinding = binding(first)
    val episodeBinding = binding(episode, video = "BBBBBBBBBBB")
    val musicEntries = listOf(QueueEntry("music-occurrence-1", first.id), QueueEntry("music-occurrence-2", second.id), QueueEntry("music-occurrence-3", first.id))
    val podcastEntries = listOf(QueueEntry("episode-occurrence-1", episode.id), QueueEntry("episode-occurrence-2", episode2.id), QueueEntry("episode-occurrence-3", episode.id))
    val asset = OfflineAsset("asset-1", first.id, "authored/offline/file.m4a", 4096,
        mimeType = "audio/mp4", codecs = "aac", sha256 = "a".repeat(64), representationId = "140",
        validatorGeneration = 3, completedEpochMs = 3000, executionBindingId = firstBinding.id, measuredDurationMs = 120_000)
    val complete = DownloadRecord("download-1", first.id, DownloadStatus.COMPLETE, bytesDownloaded = 4096,
        totalBytes = 4096, asset = asset, executionBindingId = firstBinding.id, createdEpochMs = 1000, updatedEpochMs = 3000)
    val pending = DownloadRecord("download-2", episode.id, DownloadStatus.CANCELLED, bytesDownloaded = 2048,
        totalBytes = 4096, stagingPath = "authored/staging/attempt-2.part", representationId = "140",
        etag = "\"strong-validator\"", lastModified = "Mon, 02 Nov 2026 12:00:00 GMT", validatorGeneration = 2,
        executionBindingId = episodeBinding.id, attemptCount = 2, activeAttemptId = "attempt-2",
        nextRetryEpochMs = 4000, priority = 4, createdEpochMs = 1000, updatedEpochMs = 2000,
        leaseOwner = "worker-A", leaseExpiresEpochMs = 62_000, lastProgressEpochMs = 2000,
        automaticRetryCount = 1, cleanupPending = true, removeWhenClean = true,
        failure = AppFailure(FailureCode.BACKGROUND_RESTRICTED, "authored_os_limit"))
    val attempts = listOf(
        DownloadAttempt("attempt-1", pending.id, 1, DownloadAttemptStatus.FAILED, 1000, 1100,
            failure = AppFailure(FailureCode.NETWORK_UNAVAILABLE), executionBindingId = episodeBinding.id),
        DownloadAttempt("attempt-2", pending.id, 2, DownloadAttemptStatus.FINALIZING, 1200,
            bytesDownloaded = 2048, totalBytes = 4096, stagingPath = pending.stagingPath,
            representationId = pending.representationId, executionBindingId = episodeBinding.id,
            etag = pending.etag, lastModified = pending.lastModified, validatorGeneration = 2, backgroundTaskId = "task-7"),
    )
    val receipt = DownloadFinalization(pending.id, episode.id, pending.stagingPath!!,
        "authored/offline/episode.m4a", "b".repeat(64), 2048, "attempt-2", 88_000,
        "audio/mp4", "aac", episodeBinding.id, "140", 2)
    return AppSnapshot(
        media = listOf(first, second, episode, episode2).associateBy { it.id }, shows = mapOf(show.id to show),
        artists = artists.associateBy { it.id }, albums = mapOf("album-1" to Album("album-1", "Original release",
            artists.map { it.id }, listOf(first.id, second.id), releaseYear = 2026)),
        favoriteIds = setOf(first.id, episode.id), followedShowIds = setOf(show.id), savedEpisodeIds = setOf(episode.id),
        playlists = listOf(Playlist("playlist-1", "المفضلة", listOf(PlaylistEntry("entry-1", first.id), PlaylistEntry("entry-2", first.id)))),
        queues = mapOf(QueueContext.MUSIC to QueueSnapshot(musicEntries.reversed(), "music-occurrence-2", 5000,
            shuffle = true, repeat = RepeatMode.ALL, originalOrder = musicEntries.map { it.id }, speed = 1.25f),
            QueueContext.PODCAST to QueueSnapshot(podcastEntries, "episode-occurrence-1", 6000, speed = 1.5f)),
        activeContext = QueueContext.PODCAST,
        progress = mapOf(episode.id to MediaProgress(6000, 88_000, false, 2000, "session-1", 2, 9, episodeBinding.id)),
        history = listOf(PlaybackHistoryEntry("history-1", first.id, 1000, 120_000, true, firstBinding.id),
            PlaybackHistoryEntry("history-2", episode.id, 2000, 6000, false, episodeBinding.id)),
        playbackBindings = listOf(firstBinding, episodeBinding).associateBy { it.id },
        downloads = listOf(complete, pending).associateBy { it.id }, downloadAttempts = attempts.associateBy { it.id },
        downloadFinalizations = mapOf(receipt.downloadId to receipt),
        settings = AppSettings(LanguagePreference.ARABIC, ThemeMode.DARK, 1.5f, true, 1.5f, 10, 45,
            mapOf(show.id to 1.75f), true, 4500, 4), navigation = mapOf("destination" to "Library", "podcast" to show.id),
    )
}

/**
 * Genuine exported schema-2 fixture. Rows are written only while the database is version 2 and
 * are then upgraded by the production migration, so these tests never grant schema-3 typed
 * authority through a raw snapshot write. No source/native/media bytes are involved.
 */
internal fun seedSchema2(directory: Path, snapshot: AppSnapshot) {
    val legacySnapshot = snapshot.copy(downloads = emptyMap(), downloadAttempts = emptyMap(), downloadFinalizations = emptyMap())
    val rows = legacySnapshot.toRows()
    val path = directory.resolve("veyra.db")
    Files.createDirectories(directory)
    withSchema2Helper(path) { helper ->
        helper.createDatabase(2).use { db ->
            db.execSQL("BEGIN IMMEDIATE")
            try {
                fun insert(table: String, values: List<Pair<String, Any?>>) = db.insertSchema2(table, values)
                rows.artists.forEach { a -> insert("artists", listOf(
                    "id" to a.id, "name" to a.name, "sortName" to a.sortName, "description" to a.description,
                    "artworkUrl" to a.artworkUrl) + a.source.schema2Columns()) }
                rows.albums.forEach { a -> insert("albums", listOf(
                    "id" to a.id, "title" to a.title, "description" to a.description, "artworkUrl" to a.artworkUrl,
                    "releaseYear" to a.releaseYear) + a.source.schema2Columns()) }
                rows.albumArtists.forEach { insert("album_artists", listOf("albumId" to it.albumId, "artistId" to it.artistId, "position" to it.position)) }
                rows.media.forEach { m -> insert("media", listOf(
                    "id" to m.id, "title" to m.title, "kind" to m.kind.name, "creator" to m.creator,
                    "artworkUrl" to m.artworkUrl, "advisoryDurationMs" to m.advisoryDurationMs,
                    "description" to m.description, "publishedEpochMs" to m.publishedEpochMs, "explicit" to m.explicit,
                    "albumLabel" to m.albumLabel, "artistLabel" to m.artistLabel, "localArtworkPath" to m.localArtworkPath,
                ) + m.source.schema2Columns() + m.attribution.schema2Columns()) }
                rows.tracks.forEach { insert("tracks", listOf("mediaId" to it.mediaId, "albumId" to it.albumId,
                    "trackNumber" to it.trackNumber, "discNumber" to it.discNumber)) }
                rows.trackArtists.forEach { insert("track_artists", listOf("trackId" to it.trackId, "artistId" to it.artistId, "position" to it.position)) }
                rows.albumTracks.forEach { insert("album_tracks", listOf("albumId" to it.albumId, "trackId" to it.trackId, "position" to it.position)) }
                rows.podcasts.forEach { p -> insert("podcasts", listOf(
                    "id" to p.id, "title" to p.title, "feedUrl" to p.feedUrl, "publisher" to p.publisher,
                    "description" to p.description, "artworkUrl" to p.artworkUrl, "websiteUrl" to p.websiteUrl,
                    "language" to p.language, "explicit" to p.explicit, "etag" to p.etag, "lastModified" to p.lastModified,
                    "lastRefreshEpochMs" to p.lastRefreshEpochMs, "copyright" to p.copyright,
                    "localArtworkPath" to p.localArtworkPath) + p.attribution.schema2Columns()) }
                rows.feedAliases.forEach { insert("podcast_feed_aliases", listOf("podcastId" to it.podcastId, "feedUrl" to it.feedUrl)) }
                rows.episodes.forEach { insert("episodes", listOf("mediaId" to it.mediaId, "podcastId" to it.podcastId,
                    "episodeNumber" to it.episodeNumber, "seasonNumber" to it.seasonNumber)) }
                rows.podcastEpisodes.forEach { insert("podcast_episodes", listOf("podcastId" to it.podcastId,
                    "episodeId" to it.episodeId, "position" to it.position)) }
                rows.playlists.forEach { insert("playlists", listOf("id" to it.id, "name" to it.name,
                    "createdEpochMs" to it.createdEpochMs, "updatedEpochMs" to it.updatedEpochMs, "position" to it.position)) }
                rows.playlistEntries.forEach { insert("playlist_entries", listOf("id" to it.id, "playlistId" to it.playlistId,
                    "mediaId" to it.mediaId, "position" to it.position)) }
                rows.queueContexts.forEach { insert("queue_contexts", listOf("id" to it.id.name, "positionMs" to it.positionMs,
                    "shuffle" to it.shuffle, "repeat" to it.repeat.name, "speed" to it.speed,
                    "hasOriginalOrder" to it.hasOriginalOrder)) }
                rows.queueEntries.forEach { insert("queue_entries", listOf("id" to it.id, "contextId" to it.contextId.name,
                    "mediaId" to it.mediaId, "position" to it.position, "originalPosition" to it.originalPosition)) }
                rows.queueCursors.forEach { insert("queue_cursors", listOf("contextId" to it.contextId.name, "entryId" to it.entryId)) }
                rows.favorites.forEach { insert("favorites", listOf("mediaId" to it.mediaId)) }
                rows.savedEpisodes.forEach { insert("saved_episodes", listOf("episodeId" to it.episodeId)) }
                rows.followedPodcasts.forEach { insert("followed_podcasts", listOf("podcastId" to it.podcastId)) }
                rows.bindings.forEach { b -> insert("playback_bindings", listOf(
                    "id" to b.id, "mediaId" to b.mediaId, "queryTitle" to b.queryTitle,
                    "firstResultKind" to b.firstResultKind.name, "firstResultId" to b.firstResultId,
                    "firstResultTitle" to b.firstResultTitle, "firstResultUrl" to b.firstResultUrl,
                    "selectedVideoId" to b.selectedVideoId, "resolvedEpochMs" to b.resolvedEpochMs,
                    "selectionOrdinal" to b.selectionOrdinal)) }
                rows.progress.forEach { p -> insert("media_progress", listOf(
                    "mediaId" to p.mediaId, "positionMs" to p.positionMs, "durationMs" to p.durationMs,
                    "played" to p.played, "updatedEpochMs" to p.updatedEpochMs, "sessionId" to p.sessionId,
                    "generation" to p.generation, "sequence" to p.sequence, "executionBindingId" to p.executionBindingId)) }
                rows.history.forEach { h -> insert("playback_history", listOf(
                    "id" to h.id, "mediaId" to h.mediaId, "startedEpochMs" to h.startedEpochMs,
                    "lastPositionMs" to h.lastPositionMs, "endedNaturally" to h.endedNaturally,
                    "executionBindingId" to h.executionBindingId, "position" to h.position)) }
                snapshot.downloads.values.mapNotNull { it.asset }.distinctBy { it.id }.forEach { asset ->
                    insert("offline_assets", listOf(
                        "id" to asset.id, "mediaId" to asset.mediaId, "uri" to asset.uri, "sizeBytes" to asset.sizeBytes,
                        "kind" to asset.kind.name, "mimeType" to asset.mimeType, "codecs" to asset.codecs,
                        "sha256" to asset.sha256, "representationId" to asset.representationId,
                        "validatorGeneration" to asset.validatorGeneration, "completedEpochMs" to asset.completedEpochMs,
                        "executionBindingId" to asset.executionBindingId, "measuredDurationMs" to asset.measuredDurationMs))
                }
                snapshot.downloads.values.forEach { d -> insert("downloads", listOf(
                    "id" to d.id, "mediaId" to d.mediaId, "status" to d.status.name, "bytesDownloaded" to d.bytesDownloaded,
                    "totalBytes" to d.totalBytes, "assetId" to d.asset?.id, "stagingPath" to d.stagingPath,
                    "representationId" to d.representationId, "etag" to d.etag, "lastModified" to d.lastModified,
                    "validatorGeneration" to d.validatorGeneration, "updatedEpochMs" to d.updatedEpochMs,
                    "executionBindingId" to d.executionBindingId, "attemptCount" to d.attemptCount,
                    "activeAttemptId" to d.activeAttemptId, "nextRetryEpochMs" to d.nextRetryEpochMs,
                    "priority" to d.priority, "createdEpochMs" to d.createdEpochMs, "userPaused" to d.userPaused,
                    "leaseOwner" to d.leaseOwner, "leaseExpiresEpochMs" to d.leaseExpiresEpochMs,
                    "lastProgressEpochMs" to d.lastProgressEpochMs, "automaticRetryCount" to d.automaticRetryCount,
                    "cleanupPending" to d.cleanupPending, "removeWhenClean" to d.removeWhenClean,
                    "failure_code" to d.failure?.code?.name, "failure_detail" to d.failure?.detail,
                    "failure_retryAfterMs" to d.failure?.retryAfterMs)) }
                snapshot.downloadAttempts.values.forEach { a -> insert("download_attempts", listOf(
                    "id" to a.id, "downloadId" to a.downloadId, "number" to a.number, "status" to a.status.name,
                    "startedEpochMs" to a.startedEpochMs, "finishedEpochMs" to a.finishedEpochMs,
                    "bytesDownloaded" to a.bytesDownloaded, "totalBytes" to a.totalBytes,
                    "stagingPath" to a.stagingPath, "representationId" to a.representationId,
                    "executionBindingId" to a.executionBindingId, "etag" to a.etag, "lastModified" to a.lastModified,
                    "validatorGeneration" to a.validatorGeneration, "backgroundTaskId" to a.backgroundTaskId,
                    "failure_code" to a.failure?.code?.name, "failure_detail" to a.failure?.detail,
                    "failure_retryAfterMs" to a.failure?.retryAfterMs)) }
                snapshot.downloadFinalizations.values.forEach { f -> insert("download_finalizations", listOf(
                    "downloadId" to f.downloadId, "mediaId" to f.mediaId, "stagingPath" to f.stagingPath,
                    "finalPath" to f.finalPath, "sha256" to f.sha256, "sizeBytes" to f.sizeBytes,
                    "attemptId" to f.attemptId, "mediaDurationMs" to f.mediaDurationMs, "mimeType" to f.mimeType,
                    "codecs" to f.codecs, "executionBindingId" to f.executionBindingId,
                    "representationId" to f.representationId, "validatorGeneration" to f.validatorGeneration)) }
                rows.podcastSpeeds.forEach { insert("podcast_speeds", listOf("podcastId" to it.podcastId, "speed" to it.speed)) }
                rows.navigation.forEach { insert("navigation_state", listOf("key" to it.key, "value" to it.value)) }
                insert("store_meta", listOf("id" to 0, "revision" to rows.meta!!.revision,
                    "activeContext" to rows.meta!!.activeContext.name))
                rows.settings!!.let { s -> insert("settings", listOf(
                    "id" to 0, "language" to s.language.name, "theme" to s.theme.name, "textScale" to s.textScale,
                    "offlineOnly" to s.offlineOnly, "speed" to s.speed, "skipBackSeconds" to s.skipBackSeconds,
                    "skipForwardSeconds" to s.skipForwardSeconds, "crossfadeEnabled" to s.crossfadeEnabled,
                    "crossfadeDurationMs" to s.crossfadeDurationMs, "downloadParallelism" to s.downloadParallelism)) }
                db.execSQL("COMMIT")
            } catch (failure: Throwable) {
                try { db.execSQL("ROLLBACK") } catch (rollback: Throwable) { if (rollback !== failure) failure.addSuppressed(rollback) }
                throw failure
            }
        }
    }
}

private fun SourceRef?.schema2Columns(): List<Pair<String, Any?>> = listOf(
    "source_provider" to this?.provider?.name,
    "source_canonicalId" to this?.canonicalId,
    "source_canonicalUrl" to this?.canonicalUrl,
    "source_feedUrl" to this?.feedUrl,
    "source_episodeGuid" to this?.episodeGuid,
    "source_enclosureUrl" to this?.enclosureUrl,
    "source_mimeType" to this?.mimeType,
    "source_localAssetId" to this?.localAssetId,
)

private fun Attribution?.schema2Columns(): List<Pair<String, Any?>> = listOf(
    "attribution_creator" to this?.creator,
    "attribution_sourceUrl" to this?.sourceUrl,
    "attribution_licenseName" to this?.licenseName,
    "attribution_licenseUrl" to this?.licenseUrl,
)

private fun SQLiteConnection.insertSchema2(table: String, values: List<Pair<String, Any?>>) {
    val columns = values.joinToString(",") { "\"${it.first}\"" }
    val placeholders = values.joinToString(",") { "?" }
    prepare("INSERT INTO `$table` ($columns) VALUES ($placeholders)").use { statement ->
        values.forEachIndexed { index, pair ->
            when (val value = pair.second) {
                null -> statement.bindNull(index + 1)
                is String -> statement.bindText(index + 1, value)
                is Boolean -> statement.bindLong(index + 1, if (value) 1L else 0L)
                is Int -> statement.bindLong(index + 1, value.toLong())
                is Long -> statement.bindLong(index + 1, value)
                is Double -> statement.bindDouble(index + 1, value)
                is Float -> statement.bindDouble(index + 1, value.toDouble())
                else -> error("Unsupported authored schema-2 scalar: ${value::class.simpleName}")
            }
        }
        statement.step()
    }
}

private fun withSchema2Helper(path: Path, block: (androidx.room.testing.MigrationTestHelper) -> Unit) {
    val opened = mutableListOf<SQLiteConnection>()
    val real = BundledSQLiteDriver()
    val driver = dev.veyra.data.room.VeyraSQLiteDriver(object : androidx.sqlite.SQLiteDriver by real {
        override fun open(fileName: String) = real.open(fileName).also(opened::add)
    })
    val owner = object : AutoCloseable {
        override fun close() {
            var failure: Throwable? = null
            opened.asReversed().forEach { connection ->
                try { connection.close() } catch (error: Throwable) {
                    val previous = failure
                    if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }
    owner.use {
        block(androidx.room.testing.MigrationTestHelper(
            schemaDirectoryPath = projectRoot().resolve("shared/schemas"), databasePath = path,
            driver = driver, databaseClass = dev.veyra.data.room.VeyraDatabase::class,
            databaseFactory = dev.veyra.data.room.VeyraDatabaseConstructor::initialize,
        ))
    }
}

/**
 * Low-level retained differential-writer fixture. The initial state always comes from a genuine
 * exported schema-2 migration or canonical typed commands; this helper only exercises the
 * production RowWriter/readback for relation-order and payload data that the public typed UI
 * no longer accepts as arbitrary snapshot mutations. It never creates a second store or SQL owner.
 */
internal suspend fun writerUpdate(store: LibraryStore, transform: (AppSnapshot) -> AppSnapshot): AppSnapshot =
    store.transactAcquisition { graph ->
        val proposed = transform(graph.snapshot())
        val next = proposed.toRows(graph.library)
        dev.veyra.data.room.AcquisitionMutation(graph.copy(library = next)) { it.snapshot() }
    }

internal suspend fun seededStore(directory: Path, snapshot: AppSnapshot = completeLibrary()): Pair<LibraryStore, AppSnapshot> {
    seedSchema2(directory, snapshot)
    val store = createPlatformLibraryStore(directory.toString())
    return store to store.initialize().getOrThrow()
}

internal fun failureOfForTest(error: Throwable): AppFailure = when (error) {
    is dev.veyra.model.VeyraException -> error.failure
    else -> AppFailure(FailureCode.INVALID_STATE)
}

/** Low-level physical-row writer fixture; still the same Room owner, transaction and readback. */
internal suspend fun writerRowsUpdate(store: LibraryStore,
    transform: (dev.veyra.data.room.LibraryRows) -> dev.veyra.data.room.LibraryRows): AppSnapshot =
    store.transactAcquisition { graph ->
        dev.veyra.data.room.AcquisitionMutation(graph.copy(library = transform(graph.library))) { it.snapshot() }
    }
