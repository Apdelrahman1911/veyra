package dev.veyra.data

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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
