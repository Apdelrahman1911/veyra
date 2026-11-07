package dev.veyra.data.room

/**
 * Writes changed rows only. An order edit first moves just the existing rows whose unique order
 * key changes to a disjoint negative namespace, avoiding transient unique-index collisions.
 * Payload-only history/playlist updates never stage positions or rewrite unchanged neighbours.
 * All work is inside one IMMEDIATE transaction; no observer can see staged positions.
 */
internal suspend fun LibraryDao.writeDifference(old: LibraryRows, next: LibraryRows) {
    val artistsDelta = difference(old.artists, next.artists) { it.id }
    val albumsDelta = difference(old.albums, next.albums) { it.id }
    val albumArtistsDelta = orderedDifference(old.albumArtists, next.albumArtists, { it.albumId to it.artistId }, { it.position })
    val mediaDelta = difference(old.media, next.media) { it.id }
    val tracksDelta = difference(old.tracks, next.tracks) { it.mediaId }
    val trackArtistsDelta = orderedDifference(old.trackArtists, next.trackArtists, { it.trackId to it.artistId }, { it.position })
    val albumTracksDelta = orderedDifference(old.albumTracks, next.albumTracks, { it.albumId to it.trackId }, { it.position })
    val podcastsDelta = difference(old.podcasts, next.podcasts) { it.id }
    val feedAliasesDelta = difference(old.feedAliases, next.feedAliases) { it.podcastId to it.feedUrl }
    val episodesDelta = difference(old.episodes, next.episodes) { it.mediaId }
    val podcastEpisodesDelta = orderedDifference(old.podcastEpisodes, next.podcastEpisodes, { it.podcastId to it.episodeId }, { it.position })
    val playlistsDelta = orderedDifference(old.playlists, next.playlists, { it.id }, { it.position })
    val playlistEntriesDelta = orderedDifference(old.playlistEntries, next.playlistEntries, { it.id }, { it.playlistId to it.position })
    val queueContextsDelta = difference(old.queueContexts, next.queueContexts) { it.id }
    val queueEntriesDelta = orderedDifference(old.queueEntries, next.queueEntries, { it.id }, { Triple(it.contextId, it.position, it.originalPosition) })
    val queueCursorsDelta = difference(old.queueCursors, next.queueCursors) { it.contextId }
    val favoritesDelta = difference(old.favorites, next.favorites) { it.mediaId }
    val savedEpisodesDelta = difference(old.savedEpisodes, next.savedEpisodes) { it.episodeId }
    val followedPodcastsDelta = difference(old.followedPodcasts, next.followedPodcasts) { it.podcastId }
    val bindingsDelta = difference(old.bindings, next.bindings) { it.id }
    val progressDelta = difference(old.progress, next.progress) { it.mediaId }
    val historyDelta = orderedDifference(old.history, next.history, { it.id }, { it.position })
    val assetsDelta = difference(old.assets, next.assets) { it.id }
    val downloadsDelta = difference(old.downloads, next.downloads) { it.id }
    val attemptsDelta = difference(old.attempts, next.attempts) { it.id }
    val finalizationsDelta = difference(old.finalizations, next.finalizations) { it.downloadId }
    val podcastSpeedsDelta = difference(old.podcastSpeeds, next.podcastSpeeds) { it.podcastId }
    val navigationDelta = difference(old.navigation, next.navigation) { it.key }

    // Child-first deletion; deferred NO_ACTION relations allow valid multi-row replacements.
    if (finalizationsDelta.removed.isNotEmpty()) deleteFinalizations(finalizationsDelta.removed)
    if (progressDelta.removed.isNotEmpty()) deleteProgress(progressDelta.removed)
    if (historyDelta.removed.isNotEmpty()) deleteHistory(historyDelta.removed)
    if (podcastSpeedsDelta.removed.isNotEmpty()) deletePodcastSpeeds(podcastSpeedsDelta.removed)
    if (followedPodcastsDelta.removed.isNotEmpty()) deleteFollowedPodcasts(followedPodcastsDelta.removed)
    if (savedEpisodesDelta.removed.isNotEmpty()) deleteSavedEpisodes(savedEpisodesDelta.removed)
    if (favoritesDelta.removed.isNotEmpty()) deleteFavorites(favoritesDelta.removed)
    if (queueCursorsDelta.removed.isNotEmpty()) deleteQueueCursors(queueCursorsDelta.removed)
    if (queueEntriesDelta.removed.isNotEmpty()) deleteQueueEntries(queueEntriesDelta.removed)
    if (playlistEntriesDelta.removed.isNotEmpty()) deletePlaylistEntries(playlistEntriesDelta.removed)
    if (playlistsDelta.removed.isNotEmpty()) deletePlaylists(playlistsDelta.removed)
    if (podcastEpisodesDelta.removed.isNotEmpty()) deletePodcastEpisodes(podcastEpisodesDelta.removed)
    if (albumTracksDelta.removed.isNotEmpty()) deleteAlbumTracks(albumTracksDelta.removed)
    if (trackArtistsDelta.removed.isNotEmpty()) deleteTrackArtists(trackArtistsDelta.removed)
    if (albumArtistsDelta.removed.isNotEmpty()) deleteAlbumArtists(albumArtistsDelta.removed)
    if (attemptsDelta.removed.isNotEmpty()) deleteAttempts(attemptsDelta.removed)
    if (downloadsDelta.removed.isNotEmpty()) deleteDownloads(downloadsDelta.removed)
    if (assetsDelta.removed.isNotEmpty()) deleteAssets(assetsDelta.removed)
    if (bindingsDelta.removed.isNotEmpty()) deleteBindings(bindingsDelta.removed)
    if (episodesDelta.removed.isNotEmpty()) deleteEpisodes(episodesDelta.removed)
    if (tracksDelta.removed.isNotEmpty()) deleteTracks(tracksDelta.removed)
    if (mediaDelta.removed.isNotEmpty()) deleteMedia(mediaDelta.removed)
    if (feedAliasesDelta.removed.isNotEmpty()) deleteFeedAliases(feedAliasesDelta.removed)
    if (podcastsDelta.removed.isNotEmpty()) deletePodcasts(podcastsDelta.removed)
    if (albumsDelta.removed.isNotEmpty()) deleteAlbums(albumsDelta.removed)
    if (artistsDelta.removed.isNotEmpty()) deleteArtists(artistsDelta.removed)
    if (navigationDelta.removed.isNotEmpty()) deleteNavigation(navigationDelta.removed)
    if (queueContextsDelta.removed.isNotEmpty()) deleteQueueContexts(queueContextsDelta.removed)

    if (artistsDelta.upserts.isNotEmpty()) upsertArtists(artistsDelta.upserts)
    if (albumsDelta.upserts.isNotEmpty()) upsertAlbums(albumsDelta.upserts)
    albumArtistsDelta.orderChanges.groupBy { it.albumId }.forEach { (album, rows) ->
        rows.map { it.artistId }.chunked(ORDER_STAGE_BIND_LIMIT).forEach { stageAlbumArtistsOrder(album, it) }
    }
    if (albumArtistsDelta.upserts.isNotEmpty()) upsertAlbumArtists(albumArtistsDelta.upserts)
    if (mediaDelta.upserts.isNotEmpty()) upsertMedia(mediaDelta.upserts)
    if (tracksDelta.upserts.isNotEmpty()) upsertTracks(tracksDelta.upserts)
    trackArtistsDelta.orderChanges.groupBy { it.trackId }.forEach { (track, rows) ->
        rows.map { it.artistId }.chunked(ORDER_STAGE_BIND_LIMIT).forEach { stageTrackArtistsOrder(track, it) }
    }
    if (trackArtistsDelta.upserts.isNotEmpty()) upsertTrackArtists(trackArtistsDelta.upserts)
    albumTracksDelta.orderChanges.groupBy { it.albumId }.forEach { (album, rows) ->
        rows.map { it.trackId }.chunked(ORDER_STAGE_BIND_LIMIT).forEach { stageAlbumTracksOrder(album, it) }
    }
    if (albumTracksDelta.upserts.isNotEmpty()) upsertAlbumTracks(albumTracksDelta.upserts)
    if (podcastsDelta.upserts.isNotEmpty()) upsertPodcasts(podcastsDelta.upserts)
    if (feedAliasesDelta.upserts.isNotEmpty()) upsertFeedAliases(feedAliasesDelta.upserts)
    if (episodesDelta.upserts.isNotEmpty()) upsertEpisodes(episodesDelta.upserts)
    podcastEpisodesDelta.orderChanges.groupBy { it.podcastId }.forEach { (podcast, rows) ->
        rows.map { it.episodeId }.chunked(ORDER_STAGE_BIND_LIMIT).forEach { stagePodcastEpisodesOrder(podcast, it) }
    }
    if (podcastEpisodesDelta.upserts.isNotEmpty()) upsertPodcastEpisodes(podcastEpisodesDelta.upserts)
    playlistsDelta.orderChanges.map { it.id }.chunked(ORDER_STAGE_BIND_LIMIT).forEach { stagePlaylistsOrder(it) }
    if (playlistsDelta.upserts.isNotEmpty()) upsertPlaylists(playlistsDelta.upserts)
    playlistEntriesDelta.orderChanges.map { it.id }.chunked(ORDER_STAGE_BIND_LIMIT).forEach { stagePlaylistEntriesOrder(it) }
    if (playlistEntriesDelta.upserts.isNotEmpty()) upsertPlaylistEntries(playlistEntriesDelta.upserts)
    if (queueContextsDelta.upserts.isNotEmpty()) upsertQueueContexts(queueContextsDelta.upserts)
    queueEntriesDelta.orderChanges.map { it.id }.chunked(ORDER_STAGE_BIND_LIMIT).forEach { stageQueueEntriesOrder(it) }
    if (queueEntriesDelta.upserts.isNotEmpty()) upsertQueueEntries(queueEntriesDelta.upserts)
    if (queueCursorsDelta.upserts.isNotEmpty()) upsertQueueCursors(queueCursorsDelta.upserts)
    if (favoritesDelta.upserts.isNotEmpty()) upsertFavorites(favoritesDelta.upserts)
    if (savedEpisodesDelta.upserts.isNotEmpty()) upsertSavedEpisodes(savedEpisodesDelta.upserts)
    if (followedPodcastsDelta.upserts.isNotEmpty()) upsertFollowedPodcasts(followedPodcastsDelta.upserts)
    if (bindingsDelta.upserts.isNotEmpty()) upsertBindings(bindingsDelta.upserts)
    if (progressDelta.upserts.isNotEmpty()) upsertProgress(progressDelta.upserts)
    historyDelta.orderChanges.map { it.id }.chunked(ORDER_STAGE_BIND_LIMIT).forEach { stageHistoryOrder(it) }
    if (historyDelta.upserts.isNotEmpty()) upsertHistory(historyDelta.upserts)
    if (assetsDelta.upserts.isNotEmpty()) upsertAssets(assetsDelta.upserts)
    if (downloadsDelta.upserts.isNotEmpty()) upsertDownloads(downloadsDelta.upserts)
    if (attemptsDelta.upserts.isNotEmpty()) upsertAttempts(attemptsDelta.upserts)
    if (finalizationsDelta.upserts.isNotEmpty()) upsertFinalizations(finalizationsDelta.upserts)
    if (podcastSpeedsDelta.upserts.isNotEmpty()) upsertPodcastSpeeds(podcastSpeedsDelta.upserts)
    if (navigationDelta.upserts.isNotEmpty()) upsertNavigation(navigationDelta.upserts)
    if (old.settings != next.settings) next.settings?.let { upsertSettings(it) }
    if (old.meta != next.meta) next.meta?.let { upsertMeta(it) }
}

private const val ORDER_STAGE_BIND_LIMIT = 256

private data class Difference<T>(val removed: List<T>, val upserts: List<T>, val orderChanges: List<T> = emptyList())

/** New rows have no old unique position to release; removals are deleted before this plan runs. */
private fun <T, K, O> orderedDifference(
    old: List<T>, next: List<T>, key: (T) -> K, orderKey: (T) -> O,
): Difference<T> {
    val delta = difference(old, next, key)
    val before = old.associateBy(key)
    return delta.copy(orderChanges = delta.upserts.filter { row ->
        val previous = before[key(row)]
        previous != null && orderKey(previous) != orderKey(row)
    })
}

private fun <T, K> difference(old: List<T>, next: List<T>, key: (T) -> K): Difference<T> {
    val before = old.associateBy(key)
    val after = next.associateBy(key)
    check(before.size == old.size && after.size == next.size) { "Duplicate relational identity" }
    return Difference(
        old.filter { key(it) !in after },
        // Release changed scalar unique values before a new identity tries to use one. Room's
        // @Upsert can silently no-op on a non-PK conflict followed by UPDATE of an absent PK.
        // Cyclic unique-value swaps still fail transactionally; never use destructive REPLACE.
        next.filter { key(it) in before && before[key(it)] != it } + next.filter { key(it) !in before },
    )
}
