package dev.veyra.data.room

import dev.veyra.model.*

/** A transaction-local relational projection, never a persistence blob. */
internal data class LibraryRows(
    val meta: StoreMetaEntity? = null,
    val settings: SettingsEntity? = null,
    val artists: List<ArtistEntity> = emptyList(),
    val albums: List<AlbumEntity> = emptyList(),
    val albumArtists: List<AlbumArtistEntity> = emptyList(),
    val media: List<MediaEntity> = emptyList(),
    val tracks: List<TrackEntity> = emptyList(),
    val trackArtists: List<TrackArtistEntity> = emptyList(),
    val albumTracks: List<AlbumTrackEntity> = emptyList(),
    val podcasts: List<PodcastEntity> = emptyList(),
    val feedAliases: List<PodcastFeedAliasEntity> = emptyList(),
    val episodes: List<EpisodeEntity> = emptyList(),
    val podcastEpisodes: List<PodcastEpisodeEntity> = emptyList(),
    val playlists: List<PlaylistEntity> = emptyList(),
    val playlistEntries: List<PlaylistEntryEntity> = emptyList(),
    val queueContexts: List<QueueContextEntity> = emptyList(),
    val queueEntries: List<QueueEntryEntity> = emptyList(),
    val queueCursors: List<QueueCursorEntity> = emptyList(),
    val favorites: List<FavoriteEntity> = emptyList(),
    val savedEpisodes: List<SavedEpisodeEntity> = emptyList(),
    val followedPodcasts: List<FollowedPodcastEntity> = emptyList(),
    val bindings: List<PlaybackBindingEntity> = emptyList(),
    val progress: List<MediaProgressEntity> = emptyList(),
    val history: List<HistoryEntity> = emptyList(),
    val assets: List<OfflineAssetEntity> = emptyList(),
    val downloads: List<DownloadEntity> = emptyList(),
    val attempts: List<DownloadAttemptEntity> = emptyList(),
    val finalizations: List<DownloadFinalizationEntity> = emptyList(),
    val podcastSpeeds: List<PodcastSpeedEntity> = emptyList(),
    val navigation: List<NavigationEntity> = emptyList(),
)

internal suspend fun LibraryDao.readRows(): LibraryRows = LibraryRows(
    meta = meta(),
    settings = settings(),
    artists = readArtists(),
    albums = readAlbums(),
    albumArtists = readAlbumArtists(),
    media = readMedia(),
    tracks = readTracks(),
    trackArtists = readTrackArtists(),
    albumTracks = readAlbumTracks(),
    podcasts = readPodcasts(),
    feedAliases = readFeedAliases(),
    episodes = readEpisodes(),
    podcastEpisodes = readPodcastEpisodes(),
    playlists = readPlaylists(),
    playlistEntries = readPlaylistEntries(),
    queueContexts = readQueueContexts(),
    queueEntries = readQueueEntries(),
    queueCursors = readQueueCursors(),
    favorites = readFavorites(),
    savedEpisodes = readSavedEpisodes(),
    followedPodcasts = readFollowedPodcasts(),
    bindings = readBindings(),
    progress = readProgress(),
    history = readHistory(),
    assets = readAssets(),
    downloads = readDownloads(),
    attempts = readAttempts(),
    finalizations = readFinalizations(),
    podcastSpeeds = readPodcastSpeeds(),
    navigation = readNavigation(),
)

internal fun AppSnapshot.toRows(): LibraryRows = LibraryRows(
    meta = StoreMetaEntity(revision = revision, activeContext = activeContext),
    settings = SettingsEntity(
        language = settings.language, theme = settings.theme, textScale = settings.textScale,
        offlineOnly = settings.offlineOnly, speed = settings.speed, skipBackSeconds = settings.skipBackSeconds,
        skipForwardSeconds = settings.skipForwardSeconds, crossfadeEnabled = settings.crossfadeEnabled,
        crossfadeDurationMs = settings.crossfadeDurationMs, downloadParallelism = settings.downloadParallelism,
    ),
    artists = artists.values.map { ArtistEntity(it.id, it.name, it.sortName, it.description, it.artworkUrl, it.source) },
    albums = albums.values.map { AlbumEntity(it.id, it.title, it.description, it.artworkUrl, it.releaseYear, it.source) },
    albumArtists = albums.values.flatMap { album -> album.artistIds.mapIndexed { index, artist -> AlbumArtistEntity(album.id, artist, index) } },
    media = media.values.map { item ->
        MediaEntity(item.id, item.title, item.kind, item.source, item.creator, item.artworkUrl, item.durationMs,
            item.description, item.publishedEpochMs, item.explicit, item.album, item.artist, item.localArtworkPath, item.attribution)
    },
    tracks = media.values.filter { it.kind == MediaKind.MUSIC }.map { TrackEntity(it.id, it.albumId, it.trackNumber, it.discNumber) },
    trackArtists = media.values.filter { it.kind == MediaKind.MUSIC }.flatMap { track -> track.artistIds.mapIndexed { index, artist -> TrackArtistEntity(track.id, artist, index) } },
    albumTracks = albums.values.flatMap { album -> album.trackIds.mapIndexed { index, track -> AlbumTrackEntity(album.id, track, index) } },
    podcasts = shows.values.map { show ->
        PodcastEntity(show.id, show.title, show.feedUrl, show.publisher, show.description, show.artworkUrl,
            show.websiteUrl, show.language, show.explicit, show.etag, show.lastModified, show.lastRefreshEpochMs, show.copyright, show.localArtworkPath, show.attribution)
    },
    feedAliases = shows.values.flatMap { show -> show.feedAliases.map { PodcastFeedAliasEntity(show.id, it) } },
    episodes = media.values.filter { it.kind == MediaKind.EPISODE }.map { EpisodeEntity(it.id, it.showId, it.episodeNumber, it.seasonNumber) },
    podcastEpisodes = shows.values.flatMap { show -> show.episodeIds.mapIndexed { index, id -> PodcastEpisodeEntity(show.id, id, index) } },
    playlists = playlists.mapIndexed { index, p -> PlaylistEntity(p.id, p.name, p.createdEpochMs, p.updatedEpochMs, index) },
    playlistEntries = playlists.flatMap { playlist -> playlist.entries.mapIndexed { index, entry -> PlaylistEntryEntity(entry.id, playlist.id, entry.mediaId, index) } },
    queueContexts = queues.map { (context, queue) -> QueueContextEntity(context, queue.positionMs, queue.shuffle, queue.repeat, queue.speed, queue.originalOrder.isNotEmpty()) },
    queueEntries = queues.flatMap { (context, queue) ->
        val original = (queue.originalOrder.ifEmpty { queue.entries.map { it.id } }).withIndex().associate { it.value to it.index }
        queue.entries.mapIndexed { index, entry -> QueueEntryEntity(entry.id, context, entry.mediaId, index, original.getValue(entry.id)) }
    },
    queueCursors = queues.mapNotNull { (context, queue) -> queue.currentEntryId?.let { QueueCursorEntity(context, it) } },
    favorites = favoriteIds.map(::FavoriteEntity),
    savedEpisodes = savedEpisodeIds.map(::SavedEpisodeEntity),
    followedPodcasts = followedShowIds.map(::FollowedPodcastEntity),
    bindings = playbackBindings.values.map { b -> PlaybackBindingEntity(b.id, b.mediaId, b.queryTitle, b.firstResultKind, b.firstResultId, b.firstResultTitle, b.firstResultUrl, b.selectedVideoId, b.resolvedEpochMs, b.selectionOrdinal) },
    progress = progress.map { (id, p) -> MediaProgressEntity(id, p.positionMs, p.durationMs, p.played, p.updatedEpochMs, p.sessionId, p.generation, p.sequence, p.executionBindingId) },
    history = history.mapIndexed { index, h -> HistoryEntity(h.id, h.mediaId, h.startedEpochMs, h.lastPositionMs, h.endedNaturally, h.executionBindingId, index) },
    assets = downloads.values.mapNotNull { it.asset }.distinctBy { it.id }.map { a ->
        OfflineAssetEntity(a.id, a.mediaId, a.uri, a.sizeBytes, a.kind, a.mimeType, a.codecs, a.sha256, a.representationId,
            a.validatorGeneration, a.completedEpochMs, a.executionBindingId, a.measuredDurationMs)
    },
    downloads = downloads.values.map { d -> DownloadEntity(
        id = d.id, mediaId = d.mediaId, status = d.status, bytesDownloaded = d.bytesDownloaded, totalBytes = d.totalBytes,
        assetId = d.asset?.id, failure = d.failure, stagingPath = d.stagingPath, representationId = d.representationId,
        etag = d.etag, lastModified = d.lastModified, validatorGeneration = d.validatorGeneration, updatedEpochMs = d.updatedEpochMs,
        executionBindingId = d.executionBindingId, attemptCount = d.attemptCount, activeAttemptId = d.activeAttemptId,
        nextRetryEpochMs = d.nextRetryEpochMs, priority = d.priority, createdEpochMs = d.createdEpochMs, userPaused = d.userPaused,
        leaseOwner = d.leaseOwner, leaseExpiresEpochMs = d.leaseExpiresEpochMs, lastProgressEpochMs = d.lastProgressEpochMs,
        automaticRetryCount = d.automaticRetryCount,
        cleanupPending = d.cleanupPending, removeWhenClean = d.removeWhenClean,
    ) },
    attempts = downloadAttempts.values.map { a -> DownloadAttemptEntity(a.id, a.downloadId, a.number, a.status,
        a.startedEpochMs, a.finishedEpochMs, a.bytesDownloaded, a.totalBytes, a.failure, a.stagingPath, a.representationId,
        a.executionBindingId, a.etag, a.lastModified, a.validatorGeneration, a.backgroundTaskId) },
    finalizations = downloadFinalizations.values.map { f -> DownloadFinalizationEntity(f.downloadId, f.mediaId, f.stagingPath,
        f.finalPath, f.sha256, f.sizeBytes, f.attemptId, f.mediaDurationMs, f.mimeType, f.codecs, f.executionBindingId, f.representationId, f.validatorGeneration) },
    podcastSpeeds = settings.showSpeeds.map { (id, speed) -> PodcastSpeedEntity(id, speed) },
    navigation = navigation.map { (key, value) -> NavigationEntity(key, value) },
)

internal fun LibraryRows.toSnapshot(): AppSnapshot {
    val meta = requireNotNull(meta) { "Missing database metadata" }
    val settings = requireNotNull(settings) { "Missing database settings" }
    require(tracks.map { it.mediaId }.toSet() == media.filter { it.kind == MediaKind.MUSIC }.map { it.id }.toSet()) { "Broken track subtype relation" }
    require(episodes.map { it.mediaId }.toSet() == media.filter { it.kind == MediaKind.EPISODE }.map { it.id }.toSet()) { "Broken episode subtype relation" }
    require(assets.map { it.id }.toSet() == downloads.mapNotNull { it.assetId }.toSet()) { "Unreferenced offline asset row" }
    requireContiguousOrder(playlists, { Unit }, { it.position })
    requireContiguousOrder(playlistEntries, { it.playlistId }, { it.position })
    requireContiguousOrder(queueEntries, { it.contextId }, { it.position })
    requireContiguousOrder(queueEntries, { it.contextId }, { it.originalPosition })
    requireContiguousOrder(albumArtists, { it.albumId }, { it.position })
    requireContiguousOrder(trackArtists, { it.trackId }, { it.position })
    requireContiguousOrder(albumTracks, { it.albumId }, { it.position })
    requireContiguousOrder(podcastEpisodes, { it.podcastId }, { it.position })
    requireContiguousOrder(history, { Unit }, { it.position })
    val tracks = tracks.associateBy { it.mediaId }
    val episodes = episodes.associateBy { it.mediaId }
    val credits = trackArtists.groupBy { it.trackId }
    val playlistEntries = playlistEntries.groupBy { it.playlistId }
    val queueEntries = queueEntries.groupBy { it.contextId }
    val cursors = queueCursors.associateBy { it.contextId }
    val albumArtists = albumArtists.groupBy { it.albumId }
    val albumTracks = albumTracks.groupBy { it.albumId }
    val podcastEpisodes = podcastEpisodes.groupBy { it.podcastId }
    val feedAliases = feedAliases.groupBy { it.podcastId }
    val assets = assets.associate { a -> a.id to OfflineAsset(a.id, a.mediaId, a.uri, a.sizeBytes, a.kind,
        a.mimeType, a.codecs, a.sha256, a.representationId, a.validatorGeneration, a.completedEpochMs, a.executionBindingId, a.measuredDurationMs) }
    return AppSnapshot(
        revision = meta.revision,
        activeContext = meta.activeContext,
        artists = artists.associate { a -> a.id to Artist(a.id, a.name, a.sortName, a.description, a.artworkUrl, a.source) },
        albums = albums.associate { a -> a.id to Album(a.id, a.title,
            albumArtists[a.id].orEmpty().sortedBy { it.position }.map { it.artistId },
            albumTracks[a.id].orEmpty().sortedBy { it.position }.map { it.trackId }, a.artworkUrl, a.description, a.releaseYear, a.source) },
        media = media.associate { m -> m.id to MediaItem(
            id = m.id, title = m.title, kind = m.kind, source = m.source, creator = m.creator, artworkUrl = m.artworkUrl,
            durationMs = m.advisoryDurationMs, showId = episodes[m.id]?.podcastId, description = m.description,
            publishedEpochMs = m.publishedEpochMs, explicit = m.explicit, album = m.albumLabel, artist = m.artistLabel,
            episodeNumber = episodes[m.id]?.episodeNumber, seasonNumber = episodes[m.id]?.seasonNumber, attribution = m.attribution,
            artistIds = credits[m.id].orEmpty().sortedBy { it.position }.map { it.artistId }, albumId = tracks[m.id]?.albumId,
            trackNumber = tracks[m.id]?.trackNumber, discNumber = tracks[m.id]?.discNumber,
            localArtworkPath = m.localArtworkPath,
        ) },
        shows = podcasts.associate { p -> p.id to PodcastShow(p.id, p.title, p.feedUrl, p.publisher, p.description,
            p.artworkUrl, p.websiteUrl, p.language, p.explicit, podcastEpisodes[p.id].orEmpty().sortedBy { it.position }.map { it.episodeId },
            feedAliases[p.id].orEmpty().map { it.feedUrl }.toSet(), p.etag, p.lastModified, p.lastRefreshEpochMs, p.copyright, p.attribution, p.localArtworkPath) },
        playlists = playlists.sortedBy { it.position }.map { p -> Playlist(p.id, p.name,
            playlistEntries[p.id].orEmpty().sortedBy { it.position }.map { PlaylistEntry(it.id, it.mediaId) }, p.createdEpochMs, p.updatedEpochMs) },
        queues = queueContexts.associate { q -> q.id to QueueSnapshot(
            entries = queueEntries[q.id].orEmpty().sortedBy { it.position }.map { QueueEntry(it.id, it.mediaId) },
            currentEntryId = cursors[q.id]?.entryId, positionMs = q.positionMs, shuffle = q.shuffle, repeat = q.repeat,
            originalOrder = if (!q.hasOriginalOrder) emptyList() else queueEntries[q.id].orEmpty().sortedBy { it.originalPosition }.map { it.id },
            speed = q.speed,
        ) },
        favoriteIds = favorites.map { it.mediaId }.toSet(),
        savedEpisodeIds = savedEpisodes.map { it.episodeId }.toSet(),
        followedShowIds = followedPodcasts.map { it.podcastId }.toSet(),
        playbackBindings = bindings.associate { b -> b.id to PlaybackBinding(b.id, b.mediaId, b.queryTitle, b.firstResultKind,
            b.firstResultId, b.firstResultTitle, b.firstResultUrl, b.selectedVideoId, b.resolvedEpochMs, b.selectionOrdinal) },
        progress = progress.associate { p -> p.mediaId to MediaProgress(p.positionMs, p.durationMs, p.played, p.updatedEpochMs,
            p.sessionId, p.generation, p.sequence, p.executionBindingId) },
        history = history.sortedBy { it.position }.map { h -> PlaybackHistoryEntry(h.id, h.mediaId, h.startedEpochMs,
            h.lastPositionMs, h.endedNaturally, h.executionBindingId) },
        downloads = downloads.associate { d -> d.id to DownloadRecord(
            id = d.id, mediaId = d.mediaId, status = d.status, bytesDownloaded = d.bytesDownloaded, totalBytes = d.totalBytes,
            asset = d.assetId?.let { assets.getValue(it) }, failure = d.failure, stagingPath = d.stagingPath, representationId = d.representationId,
            etag = d.etag, lastModified = d.lastModified, validatorGeneration = d.validatorGeneration, updatedEpochMs = d.updatedEpochMs,
            executionBindingId = d.executionBindingId, attemptCount = d.attemptCount, activeAttemptId = d.activeAttemptId,
            nextRetryEpochMs = d.nextRetryEpochMs, priority = d.priority, createdEpochMs = d.createdEpochMs, userPaused = d.userPaused,
            leaseOwner = d.leaseOwner, leaseExpiresEpochMs = d.leaseExpiresEpochMs, lastProgressEpochMs = d.lastProgressEpochMs,
            automaticRetryCount = d.automaticRetryCount,
            cleanupPending = d.cleanupPending, removeWhenClean = d.removeWhenClean,
        ) },
        downloadAttempts = attempts.associate { a -> a.id to DownloadAttempt(a.id, a.downloadId, a.number, a.status,
            a.startedEpochMs, a.finishedEpochMs, a.bytesDownloaded, a.totalBytes, a.failure, a.stagingPath, a.representationId,
            a.executionBindingId, a.etag, a.lastModified, a.validatorGeneration, a.backgroundTaskId) },
        downloadFinalizations = finalizations.associate { f -> f.downloadId to DownloadFinalization(f.downloadId, f.mediaId,
            f.stagingPath, f.finalPath, f.sha256, f.sizeBytes, f.attemptId, f.mediaDurationMs, f.mimeType, f.codecs, f.executionBindingId, f.representationId, f.validatorGeneration) },
        settings = AppSettings(settings.language, settings.theme, settings.textScale, settings.offlineOnly, settings.speed,
            settings.skipBackSeconds, settings.skipForwardSeconds, podcastSpeeds.associate { it.podcastId to it.speed },
            settings.crossfadeEnabled, settings.crossfadeDurationMs, settings.downloadParallelism),
        navigation = navigation.associate { it.key to it.value },
    )
}

private fun <T, K> requireContiguousOrder(rows: List<T>, group: (T) -> K, position: (T) -> Int) {
    rows.groupBy(group).values.forEach { related ->
        require(related.map(position).sorted() == related.indices.toList()) { "Broken relational occurrence order" }
    }
}
