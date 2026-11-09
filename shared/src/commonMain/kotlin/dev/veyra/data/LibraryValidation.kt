package dev.veyra.data

import dev.veyra.model.*

/** Domain invariants complement, rather than replace, Room's keys/relations and SQLite transactions. */
internal fun validateLibrarySnapshot(snapshot: AppSnapshot) {
    require(snapshot.schemaVersion == AppSnapshot.CURRENT_SCHEMA && snapshot.revision >= 0)
    require(snapshot.media.all { (id, item) -> id.isNotBlank() && id == item.id && item.title.isNotBlank() && (item.durationMs == null || item.durationMs >= 0) })
    require(snapshot.artists.all { (id, artist) -> id == artist.id && id.isNotBlank() && artist.name.isNotBlank() })
    require(snapshot.albums.all { (id, album) ->
        id == album.id && id.isNotBlank() && album.title.isNotBlank() &&
            album.artistIds.distinct().size == album.artistIds.size && album.artistIds.all { it in snapshot.artists } &&
            album.trackIds.distinct().size == album.trackIds.size && album.trackIds.all { snapshot.media[it]?.kind == MediaKind.MUSIC }
    })
    require(snapshot.shows.all { (id, show) -> id.isNotBlank() && id == show.id && show.title.isNotBlank() && show.feedUrl.isNotBlank() })
    require(snapshot.shows.values.map { it.feedUrl }.distinct().size == snapshot.shows.size)
    snapshot.shows.values.forEach { show ->
        require(show.episodeIds.distinct().size == show.episodeIds.size)
        require(show.episodeIds.all { snapshot.media[it]?.let { episode -> episode.kind == MediaKind.EPISODE && episode.showId == show.id } == true })
    }
    snapshot.media.values.forEach { item ->
        require(item.artistIds.distinct().size == item.artistIds.size && item.artistIds.all { it in snapshot.artists })
        require(item.albumId == null || item.albumId in snapshot.albums)
        require(item.showId == null || item.showId in snapshot.shows)
        require(item.kind == MediaKind.MUSIC || (item.artistIds.isEmpty() && item.albumId == null && item.trackNumber == null && item.discNumber == null))
        require(item.kind == MediaKind.EPISODE || (item.showId == null && item.episodeNumber == null && item.seasonNumber == null))
        validateStableRef(item.source)
    }
    snapshot.artists.values.forEach { it.source?.let(::validateStableRef) }
    snapshot.albums.values.forEach { it.source?.let(::validateStableRef) }
    require(snapshot.favoriteIds.all { it in snapshot.media })
    require(snapshot.savedEpisodeIds.all { snapshot.media[it]?.kind == MediaKind.EPISODE })
    require(snapshot.followedShowIds.all { it in snapshot.shows })
    require(snapshot.playlists.map { it.id }.distinct().size == snapshot.playlists.size)
    val playlistOccurrences = mutableSetOf<String>()
    snapshot.playlists.forEach { playlist ->
        require(playlist.id.isNotBlank() && playlist.name.isNotBlank())
        require(playlist.entries.all { it.id.isNotBlank() && playlistOccurrences.add(it.id) && it.mediaId in snapshot.media })
    }
    val queueOccurrences = mutableSetOf<String>()
    require(snapshot.queues.keys == QueueContext.entries.toSet())
    snapshot.queues.forEach { (context, queue) ->
        require(queue.positionMs >= 0 && queue.speed.isFinite() && queue.speed in 0.5f..2f)
        require(queue.entries.all { it.id.isNotBlank() && queueOccurrences.add(it.id) && it.mediaId in snapshot.media })
        require(queue.entries.all { (snapshot.media.getValue(it.mediaId).kind == MediaKind.EPISODE) == (context == QueueContext.PODCAST) })
        require(queue.currentEntryId == null || queue.entries.any { it.id == queue.currentEntryId })
        require(queue.originalOrder.distinct().size == queue.originalOrder.size)
        require(queue.originalOrder.isEmpty() || queue.originalOrder.toSet() == queue.entries.map { it.id }.toSet())
        require(context == QueueContext.MUSIC || (!queue.shuffle && queue.repeat == RepeatMode.OFF))
    }
    snapshot.playbackBindings.forEach { (id, b) ->
        require(id == b.id && id.isNotBlank() && b.mediaId in snapshot.media && b.queryTitle.isNotBlank())
        require(b.selectionOrdinal == 0 && b.resolvedEpochMs >= 0)
        require(b.firstResultKind == FirstResultKind.UNKNOWN || (b.firstResultId.isNotBlank() && b.firstResultTitle.isNotBlank()))
        require(b.selectedVideoId == null || (b.firstResultKind == FirstResultKind.VIDEO && b.selectedVideoId == b.firstResultId))
        require(b.firstResultKind != FirstResultKind.VIDEO || b.firstResultId.matches(Regex("[A-Za-z0-9_-]{11}")))
        b.firstResultUrl?.let { url ->
            val expected = when (b.firstResultKind) {
                FirstResultKind.VIDEO -> "https://www.youtube.com/watch?v=${b.firstResultId}"
                FirstResultKind.CHANNEL -> "https://www.youtube.com/channel/${b.firstResultId}"
                FirstResultKind.PLAYLIST -> "https://www.youtube.com/playlist?list=${b.firstResultId}"
                FirstResultKind.UNKNOWN -> null
            }
            require(url == expected) { "Playback receipt must use a canonical non-capability locator" }
        }
    }
    fun validBinding(id: String?, mediaId: String): Boolean = id == null || snapshot.playbackBindings[id]?.mediaId == mediaId
    require(snapshot.progress.all { (id, p) -> id in snapshot.media && p.positionMs >= 0 && (p.durationMs == null || p.durationMs >= 0) && validBinding(p.executionBindingId, id) })
    require(snapshot.history.map { it.id }.distinct().size == snapshot.history.size)
    require(snapshot.history.all { it.mediaId in snapshot.media && it.lastPositionMs >= 0 && validBinding(it.executionBindingId, it.mediaId) })
    require(snapshot.downloads.values.map { it.mediaId }.distinct().size == snapshot.downloads.size)
    val assets = snapshot.downloads.values.mapNotNull { it.asset }
    require(assets.map { it.id }.distinct().size == assets.size) { "Offline asset identities must be unique" }
    require(assets.map { it.uri }.distinct().size == assets.size) { "Offline asset files must have one owner" }
    snapshot.downloads.forEach { (id, d) ->
        require(id == d.id && id.isNotBlank() && d.mediaId in snapshot.media && d.bytesDownloaded >= 0 && d.attemptCount >= 0 && d.automaticRetryCount >= 0)
        require(d.totalBytes == null || d.totalBytes >= 0)
        require(d.status != DownloadStatus.COMPLETE || d.asset != null)
        require(validBinding(d.executionBindingId, d.mediaId))
        require(d.activeAttemptId == null || snapshot.downloadAttempts[d.activeAttemptId]?.downloadId == d.id)
        require((d.leaseOwner == null) == (d.leaseExpiresEpochMs == null))
        d.asset?.let { a ->
            require(a.id.isNotBlank() && a.mediaId == d.mediaId && a.sizeBytes > 0 && a.uri.isNotBlank())
            require(validBinding(a.executionBindingId, a.mediaId))
            require(a.measuredDurationMs == null || a.measuredDurationMs >= 0)
            require(a.sha256 == null || a.sha256.matches(Regex("[a-fA-F0-9]{64}")))
        }
    }
    val attempts = mutableSetOf<Pair<String, Int>>()
    snapshot.downloadAttempts.forEach { (id, a) ->
        require(id == a.id && a.id.isNotBlank() && a.number > 0 && a.downloadId in snapshot.downloads)
        require(attempts.add(a.downloadId to a.number) && a.bytesDownloaded >= 0 && (a.totalBytes == null || a.totalBytes >= 0))
        require(validBinding(a.executionBindingId, snapshot.downloads.getValue(a.downloadId).mediaId))
    }
    snapshot.downloadFinalizations.forEach { (id, f) ->
        require(id == f.downloadId && snapshot.downloads[id]?.mediaId == f.mediaId)
        require(f.stagingPath.isNotBlank() && f.finalPath.isNotBlank() && f.stagingPath != f.finalPath && f.sizeBytes > 0)
        require(f.sha256.matches(Regex("[a-fA-F0-9]{64}")) && validBinding(f.executionBindingId, f.mediaId))
        require(f.attemptId == null || snapshot.downloadAttempts[f.attemptId]?.downloadId == id)
    }
    val settings = snapshot.settings
    require(settings.textScale.isFinite() && settings.textScale in 0.75f..2f)
    require(settings.speed.isFinite() && settings.speed in 0.5f..2f)
    require(settings.skipBackSeconds in 1..300 && settings.skipForwardSeconds in 1..300)
    require(settings.showSpeeds.all { (id, speed) -> id in snapshot.shows && speed.isFinite() && speed in 0.5f..2f })
    require(settings.crossfadeDurationMs in 100..30_000 && settings.downloadParallelism in 1..4)
}

internal fun validateLibraryTransition(previous: AppSnapshot, next: AppSnapshot) {
    validateLibrarySnapshot(next)
    next.media.forEach { (id, item) -> previous.media[id]?.let { old ->
        require(item.kind == old.kind && item.source.provider == old.source.provider && item.source.canonicalId == old.source.canonicalId) {
            "A discovery identity cannot be reassigned to different content"
        }
    } }
    next.playbackBindings.forEach { (id, binding) ->
        val old = previous.playbackBindings[id]
        if (old != null) require(binding == old) { "Execution provenance is immutable" }
        else require(binding.queryTitle == next.media.getValue(binding.mediaId).title) { "Search must use the complete discovery title" }
    }
}

private fun validateStableRef(ref: SourceRef) {
    require(ref.canonicalId.isNotBlank())
    if (ref.provider == SourceProvider.YOUTUBE) {
        require(ref.enclosureUrl == null)
        require(ref.canonicalUrl?.contains("googlevideo.com", ignoreCase = true) != true)
    }
}
