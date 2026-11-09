package dev.veyra.data.benchmark

import dev.veyra.data.validateLibrarySnapshot
import dev.veyra.model.*

/** Fixed authored metadata only; no network, decoder, final asset, or downloaded-media claim. */
internal object RoomSchema2Fixture {
    const val SEED = "authored-room-v2-20261106-1"
    fun musicId(index: Int) = "bench-music-$index"
    fun episodeId(index: Int) = "bench-episode-$index"
    private fun bindingId(id: String) = "receipt-$id"

    fun create(size: Int): AppSnapshot {
        require(size == 1_000 || size == 10_000)
        val half = size / 2
        val artistCount = minOf(100, half / 5)
        val albumCount = minOf(100, half / 10)
        val showCount = minOf(50, half / 20)
        fun credits(index: Int) = listOf("artist-${index % artistCount}", "artist-${(index + 1) % artistCount}")
        val artists = (0 until artistCount).map { Artist("artist-$it", "Authored artist $it — الفنان") }.associateBy { it.id }
        val music = (0 until half).map { index ->
            MediaItem(musicId(index), "Authored complete track $index — موسيقى", MediaKind.MUSIC,
                SourceRef(SourceProvider.COMMONS, "authored-track-$index", "https://example.invalid/catalog/track/$index"),
                creator = "Authored creator", durationMs = 240_000, description = "Redistributable metadata fixture",
                artistIds = credits(index), albumId = "album-${index % albumCount}", trackNumber = index / albumCount + 1,
                discNumber = 1, attribution = Attribution("Veyra test authors", "https://example.invalid/fixture", "CC0"))
        }
        val episodes = (0 until half).map { index ->
            val feed = "https://example.invalid/feed/${index % showCount}"
            MediaItem(episodeId(index), "Authored complete episode $index — الحلقة", MediaKind.EPISODE,
                SourceRef(SourceProvider.RSS, "authored-guid-$index", feedUrl = feed, episodeGuid = "authored-guid-$index"),
                creator = "Authored publisher", durationMs = 3_600_000, showId = "show-${index % showCount}",
                episodeNumber = index / showCount + 1, seasonNumber = 1)
        }
        val media = (music + episodes).associateBy { it.id }
        val albums = (0 until albumCount).map { index ->
            Album("album-$index", "Authored release $index", credits(index),
                music.filter { it.albumId == "album-$index" }.map { it.id }, releaseYear = 2026)
        }.associateBy { it.id }
        val shows = (0 until showCount).map { index ->
            PodcastShow("show-$index", "Authored show $index", "https://example.invalid/feed/$index",
                publisher = "Authored publisher", language = if (index % 2 == 0) "ar" else "en",
                episodeIds = episodes.filter { it.showId == "show-$index" }.map { it.id },
                feedAliases = setOf("https://example.invalid/old-feed/$index"))
        }.associateBy { it.id }
        val bindings = media.values.mapIndexed { index, item ->
            val video = index.toString().padStart(11, '0')
            PlaybackBinding(bindingId(item.id), item.id, item.title, FirstResultKind.VIDEO, video,
                "Authored first-result metadata $index", "https://www.youtube.com/watch?v=$video", video, 1_000)
        }.associateBy { it.id }
        val boundedIds = (0 until minOf(500, half)).flatMap { listOf(musicId(it), episodeId(it)) }
        val history = boundedIds.map { PlaybackHistoryEntry("history-$it", it, 1_000, 1, executionBindingId = bindingId(it)) }
        val downloads = boundedIds.mapIndexed { index, id ->
            DownloadRecord("download-$index", id, DownloadStatus.RUNNING, bytesDownloaded = 1,
                totalBytes = 1_000_000_000, representationId = "authored-audio", updatedEpochMs = 1_000,
                executionBindingId = bindingId(id), attemptCount = 1, activeAttemptId = "attempt-$index",
                createdEpochMs = 1_000, leaseOwner = "authored-worker-${index % 4}", leaseExpiresEpochMs = 61_000,
                lastProgressEpochMs = 1_000)
        }.associateBy { it.id }
        val attempts = downloads.values.map { record ->
            DownloadAttempt(requireNotNull(record.activeAttemptId), record.id, 1, startedEpochMs = 1_000,
                bytesDownloaded = 1, totalBytes = record.totalBytes, representationId = record.representationId,
                executionBindingId = record.executionBindingId)
        }.associateBy { it.id }
        val queues = QueueContext.entries.associateWith { context ->
            val entries = (0 until minOf(200, half)).map { index ->
                QueueEntry("${context.name}-occurrence-$index", if (context == QueueContext.MUSIC) musicId(index) else episodeId(index))
            }
            QueueSnapshot(entries, entries.first().id, originalOrder = entries.map { it.id })
        }
        return AppSnapshot(media = media, artists = artists, albums = albums, shows = shows, playbackBindings = bindings,
            progress = media.values.associate { it.id to MediaProgress(1, it.durationMs, updatedEpochMs = 1_000,
                sessionId = "authored-session", generation = 1, sequence = 1, executionBindingId = bindingId(it.id)) },
            history = history, downloads = downloads, downloadAttempts = attempts, queues = queues,
            favoriteIds = boundedIds.take(100).toSet(), savedEpisodeIds = episodes.take(100).map { it.id }.toSet(),
            followedShowIds = shows.keys.take(20).toSet(),
            playlists = (0 until 10).map { list -> Playlist("playlist-$list", "Authored playlist $list",
                boundedIds.take(100).mapIndexed { i, id -> PlaylistEntry("playlist-$list-entry-$i", id) }) },
            settings = AppSettings(showSpeeds = shows.keys.take(10).associateWith { 1.25f }),
            navigation = mapOf("destination" to "Library"))
            .also(::validateLibrarySnapshot)
    }

    fun change(old: AppSnapshot, workload: String, producer: Int = 0): AppSnapshot = when (workload) {
        "progress_history" -> progress(old, episodeId(producer))
        "playback_checkpoint" -> {
            val changed = progress(old, musicId(0))
            val queue = changed.queue(QueueContext.MUSIC)
            changed.copy(queues = changed.queues + (QueueContext.MUSIC to queue.copy(positionMs = queue.positionMs + 1)))
        }
        "settings" -> old.copy(settings = old.settings.copy(crossfadeEnabled = !old.settings.crossfadeEnabled))
        "queue_order" -> {
            val queue = old.queue(QueueContext.MUSIC)
            val order = queue.entries.drop(1) + queue.entries.first()
            old.copy(queues = old.queues + (QueueContext.MUSIC to queue.copy(entries = order, originalOrder = order.map { it.id })))
        }
        "download_heartbeat" -> {
            val id = "download-$producer"
            val record = old.downloads.getValue(id)
            val attemptId = requireNotNull(record.activeAttemptId)
            val attempt = old.downloadAttempts.getValue(attemptId)
            old.copy(downloads = old.downloads + (id to record.copy(bytesDownloaded = record.bytesDownloaded + 1_024,
                updatedEpochMs = record.updatedEpochMs + 1, lastProgressEpochMs = requireNotNull(record.lastProgressEpochMs) + 1,
                leaseExpiresEpochMs = requireNotNull(record.leaseExpiresEpochMs) + 1_000)),
                downloadAttempts = old.downloadAttempts + (attemptId to attempt.copy(bytesDownloaded = attempt.bytesDownloaded + 1_024)))
        }
        else -> error("unknown_authored_workload")
    }

    private fun progress(old: AppSnapshot, id: String): AppSnapshot {
        val progress = old.progress.getValue(id)
        return old.copy(progress = old.progress + (id to progress.copy(positionMs = progress.positionMs + 1,
            updatedEpochMs = progress.updatedEpochMs + 1, sequence = progress.sequence + 1)),
            history = old.history.map { if (it.mediaId == id) it.copy(lastPositionMs = it.lastPositionMs + 1) else it })
    }
}
