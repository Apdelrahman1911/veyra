package dev.veyra.data.room

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * SQL access stays behind Room repositories. All multi-table projections and mutations run in one
 * explicit Room transaction; individual table flows must not be combined into torn app snapshots.
 */
@Dao
interface LibraryDao {
    @Query("SELECT * FROM store_meta WHERE id = 0")
    suspend fun meta(): StoreMetaEntity?

    @Query("SELECT revision FROM store_meta WHERE id = 0")
    fun revisions(): Flow<Long?>

    @Upsert suspend fun upsertMeta(row: StoreMetaEntity)

    @Query("SELECT * FROM settings WHERE id = 0")
    suspend fun settings(): SettingsEntity?

    @Upsert suspend fun upsertSettings(row: SettingsEntity)

    @Query("SELECT * FROM artists")
    suspend fun readArtists(): List<ArtistEntity>
    @Upsert suspend fun upsertArtists(rows: List<ArtistEntity>)
    @Delete suspend fun deleteArtists(rows: List<ArtistEntity>)

    @Query("SELECT * FROM albums")
    suspend fun readAlbums(): List<AlbumEntity>
    @Upsert suspend fun upsertAlbums(rows: List<AlbumEntity>)
    @Delete suspend fun deleteAlbums(rows: List<AlbumEntity>)

    @Query("SELECT * FROM album_artists")
    suspend fun readAlbumArtists(): List<AlbumArtistEntity>
    @Upsert suspend fun upsertAlbumArtists(rows: List<AlbumArtistEntity>)
    @Delete suspend fun deleteAlbumArtists(rows: List<AlbumArtistEntity>)

    @Query("SELECT * FROM media")
    suspend fun readMedia(): List<MediaEntity>
    @Upsert suspend fun upsertMedia(rows: List<MediaEntity>)
    @Delete suspend fun deleteMedia(rows: List<MediaEntity>)

    @Query("SELECT * FROM tracks")
    suspend fun readTracks(): List<TrackEntity>
    @Upsert suspend fun upsertTracks(rows: List<TrackEntity>)
    @Delete suspend fun deleteTracks(rows: List<TrackEntity>)

    @Query("SELECT * FROM track_artists")
    suspend fun readTrackArtists(): List<TrackArtistEntity>
    @Upsert suspend fun upsertTrackArtists(rows: List<TrackArtistEntity>)
    @Delete suspend fun deleteTrackArtists(rows: List<TrackArtistEntity>)

    @Query("SELECT * FROM album_tracks")
    suspend fun readAlbumTracks(): List<AlbumTrackEntity>
    @Upsert suspend fun upsertAlbumTracks(rows: List<AlbumTrackEntity>)
    @Delete suspend fun deleteAlbumTracks(rows: List<AlbumTrackEntity>)

    @Query("SELECT * FROM podcasts")
    suspend fun readPodcasts(): List<PodcastEntity>
    @Upsert suspend fun upsertPodcasts(rows: List<PodcastEntity>)
    @Delete suspend fun deletePodcasts(rows: List<PodcastEntity>)

    @Query("SELECT * FROM podcast_feed_aliases")
    suspend fun readFeedAliases(): List<PodcastFeedAliasEntity>
    @Upsert suspend fun upsertFeedAliases(rows: List<PodcastFeedAliasEntity>)
    @Delete suspend fun deleteFeedAliases(rows: List<PodcastFeedAliasEntity>)

    @Query("SELECT * FROM episodes")
    suspend fun readEpisodes(): List<EpisodeEntity>
    @Upsert suspend fun upsertEpisodes(rows: List<EpisodeEntity>)
    @Delete suspend fun deleteEpisodes(rows: List<EpisodeEntity>)

    @Query("SELECT * FROM podcast_episodes")
    suspend fun readPodcastEpisodes(): List<PodcastEpisodeEntity>
    @Upsert suspend fun upsertPodcastEpisodes(rows: List<PodcastEpisodeEntity>)
    @Delete suspend fun deletePodcastEpisodes(rows: List<PodcastEpisodeEntity>)

    @Query("SELECT * FROM playlists")
    suspend fun readPlaylists(): List<PlaylistEntity>
    @Upsert suspend fun upsertPlaylists(rows: List<PlaylistEntity>)
    @Delete suspend fun deletePlaylists(rows: List<PlaylistEntity>)

    @Query("SELECT * FROM playlist_entries")
    suspend fun readPlaylistEntries(): List<PlaylistEntryEntity>
    @Upsert suspend fun upsertPlaylistEntries(rows: List<PlaylistEntryEntity>)
    @Delete suspend fun deletePlaylistEntries(rows: List<PlaylistEntryEntity>)

    @Query("SELECT * FROM queue_contexts")
    suspend fun readQueueContexts(): List<QueueContextEntity>
    @Upsert suspend fun upsertQueueContexts(rows: List<QueueContextEntity>)
    @Delete suspend fun deleteQueueContexts(rows: List<QueueContextEntity>)

    @Query("SELECT * FROM queue_entries")
    suspend fun readQueueEntries(): List<QueueEntryEntity>
    @Upsert suspend fun upsertQueueEntries(rows: List<QueueEntryEntity>)
    @Delete suspend fun deleteQueueEntries(rows: List<QueueEntryEntity>)

    @Query("SELECT * FROM queue_cursors")
    suspend fun readQueueCursors(): List<QueueCursorEntity>
    @Upsert suspend fun upsertQueueCursors(rows: List<QueueCursorEntity>)
    @Delete suspend fun deleteQueueCursors(rows: List<QueueCursorEntity>)

    @Query("SELECT * FROM favorites")
    suspend fun readFavorites(): List<FavoriteEntity>
    @Upsert suspend fun upsertFavorites(rows: List<FavoriteEntity>)
    @Delete suspend fun deleteFavorites(rows: List<FavoriteEntity>)

    @Query("SELECT * FROM saved_episodes")
    suspend fun readSavedEpisodes(): List<SavedEpisodeEntity>
    @Upsert suspend fun upsertSavedEpisodes(rows: List<SavedEpisodeEntity>)
    @Delete suspend fun deleteSavedEpisodes(rows: List<SavedEpisodeEntity>)

    @Query("SELECT * FROM followed_podcasts")
    suspend fun readFollowedPodcasts(): List<FollowedPodcastEntity>
    @Upsert suspend fun upsertFollowedPodcasts(rows: List<FollowedPodcastEntity>)
    @Delete suspend fun deleteFollowedPodcasts(rows: List<FollowedPodcastEntity>)

    @Query("SELECT * FROM playback_bindings")
    suspend fun readBindings(): List<PlaybackBindingEntity>
    @Upsert suspend fun upsertBindings(rows: List<PlaybackBindingEntity>)
    @Delete suspend fun deleteBindings(rows: List<PlaybackBindingEntity>)

    @Query("SELECT * FROM media_progress")
    suspend fun readProgress(): List<MediaProgressEntity>
    @Upsert suspend fun upsertProgress(rows: List<MediaProgressEntity>)
    @Delete suspend fun deleteProgress(rows: List<MediaProgressEntity>)

    @Query("SELECT * FROM playback_history")
    suspend fun readHistory(): List<HistoryEntity>
    @Upsert suspend fun upsertHistory(rows: List<HistoryEntity>)
    @Delete suspend fun deleteHistory(rows: List<HistoryEntity>)

    @Query("SELECT * FROM offline_assets")
    suspend fun readAssets(): List<OfflineAssetEntity>
    @Upsert suspend fun upsertAssets(rows: List<OfflineAssetEntity>)
    @Delete suspend fun deleteAssets(rows: List<OfflineAssetEntity>)

    @Query("SELECT * FROM downloads")
    suspend fun readDownloads(): List<DownloadEntity>
    @Upsert suspend fun upsertDownloads(rows: List<DownloadEntity>)
    @Delete suspend fun deleteDownloads(rows: List<DownloadEntity>)

    @Query("SELECT * FROM download_attempts")
    suspend fun readAttempts(): List<DownloadAttemptEntity>
    @Upsert suspend fun upsertAttempts(rows: List<DownloadAttemptEntity>)
    @Delete suspend fun deleteAttempts(rows: List<DownloadAttemptEntity>)

    @Query("SELECT * FROM download_finalizations")
    suspend fun readFinalizations(): List<DownloadFinalizationEntity>
    @Upsert suspend fun upsertFinalizations(rows: List<DownloadFinalizationEntity>)
    @Delete suspend fun deleteFinalizations(rows: List<DownloadFinalizationEntity>)

    @Query("SELECT * FROM podcast_speeds")
    suspend fun readPodcastSpeeds(): List<PodcastSpeedEntity>
    @Upsert suspend fun upsertPodcastSpeeds(rows: List<PodcastSpeedEntity>)
    @Delete suspend fun deletePodcastSpeeds(rows: List<PodcastSpeedEntity>)

    @Query("SELECT * FROM navigation_state")
    suspend fun readNavigation(): List<NavigationEntity>
    @Upsert suspend fun upsertNavigation(rows: List<NavigationEntity>)
    @Delete suspend fun deleteNavigation(rows: List<NavigationEntity>)

    @Query("UPDATE album_artists SET position = -position - 1 WHERE albumId = :albumId AND artistId IN (:artistIds)")
    suspend fun stageAlbumArtistsOrder(albumId: String, artistIds: List<String>)

    @Query("UPDATE track_artists SET position = -position - 1 WHERE trackId = :trackId AND artistId IN (:artistIds)")
    suspend fun stageTrackArtistsOrder(trackId: String, artistIds: List<String>)

    @Query("UPDATE album_tracks SET position = -position - 1 WHERE albumId = :albumId AND trackId IN (:trackIds)")
    suspend fun stageAlbumTracksOrder(albumId: String, trackIds: List<String>)

    @Query("UPDATE podcast_episodes SET position = -position - 1 WHERE podcastId = :podcastId AND episodeId IN (:episodeIds)")
    suspend fun stagePodcastEpisodesOrder(podcastId: String, episodeIds: List<String>)

    @Query("UPDATE playlists SET position = -position - 1 WHERE id IN (:ids)")
    suspend fun stagePlaylistsOrder(ids: List<String>)

    @Query("UPDATE playlist_entries SET position = -position - 1 WHERE id IN (:ids)")
    suspend fun stagePlaylistEntriesOrder(ids: List<String>)

    @Query("UPDATE queue_entries SET position = -position - 1, originalPosition = -originalPosition - 1 WHERE id IN (:ids)")
    suspend fun stageQueueEntriesOrder(ids: List<String>)

    @Query("UPDATE playback_history SET position = -position - 1 WHERE id IN (:ids)")
    suspend fun stageHistoryOrder(ids: List<String>)

    @Query("SELECT a.* FROM artists a JOIN track_artists x ON x.artistId = a.id WHERE x.trackId = :trackId ORDER BY x.position")
    suspend fun artistsForTrack(trackId: String): List<ArtistEntity>

    @Query("SELECT m.* FROM media m JOIN album_tracks x ON x.trackId = m.id WHERE x.albumId = :albumId ORDER BY x.position")
    suspend fun albumTrackMedia(albumId: String): List<MediaEntity>

    @Query("SELECT m.* FROM media m JOIN podcast_episodes x ON x.episodeId = m.id WHERE x.podcastId = :podcastId ORDER BY x.position")
    suspend fun podcastEpisodeMedia(podcastId: String): List<MediaEntity>

    @Query("SELECT * FROM media WHERE title LIKE :pattern ESCAPE '\\' OR creator LIKE :pattern ESCAPE '\\' ORDER BY title, id LIMIT :limit OFFSET :offset")
    suspend fun searchMedia(pattern: String, limit: Int, offset: Int): List<MediaEntity>

    @Query("SELECT * FROM artists ORDER BY sortName, id LIMIT :limit OFFSET :offset")
    fun observeArtists(limit: Int, offset: Int): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM albums ORDER BY title, id LIMIT :limit OFFSET :offset")
    fun observeAlbums(limit: Int, offset: Int): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM downloads WHERE cleanupPending = 0 AND userPaused = 0 AND (status = 'QUEUED' OR status = 'WAITING_NETWORK') AND (nextRetryEpochMs IS NULL OR nextRetryEpochMs <= :now) AND (leaseExpiresEpochMs IS NULL OR leaseExpiresEpochMs <= :now) ORDER BY priority DESC, createdEpochMs, id")
    suspend fun claimableDownloads(now: Long): List<DownloadEntity>
}
