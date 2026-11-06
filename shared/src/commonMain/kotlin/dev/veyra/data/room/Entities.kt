package dev.veyra.data.room

import androidx.room.*
import dev.veyra.model.*

// Every collection is a table/junction, not a serialized list or authoritative JSON document.
@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortName: String,
    val description: String,
    val artworkUrl: String?,
    @Embedded(prefix = "source_") val source: SourceRef?,
)

@Entity(tableName = "albums", indices = [Index("title")])
data class AlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val artworkUrl: String?,
    val releaseYear: Int?,
    @Embedded(prefix = "source_") val source: SourceRef?,
)

@Entity(
    tableName = "album_artists", primaryKeys = ["albumId", "artistId"],
    foreignKeys = [
        ForeignKey(entity = AlbumEntity::class, parentColumns = ["id"], childColumns = ["albumId"], onDelete = ForeignKey.CASCADE, deferred = true),
        ForeignKey(entity = ArtistEntity::class, parentColumns = ["id"], childColumns = ["artistId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ], indices = [Index("artistId"), Index(value = ["albumId", "position"], unique = true)],
)
data class AlbumArtistEntity(val albumId: String, val artistId: String, val position: Int)

@Entity(tableName = "media", indices = [Index("title"), Index("kind"), Index(value = ["source_provider", "source_canonicalId"])])
data class MediaEntity(
    @PrimaryKey val id: String,
    val title: String,
    val kind: MediaKind,
    @Embedded(prefix = "source_") val source: SourceRef,
    val creator: String,
    val artworkUrl: String?,
    /** Discovery metadata only; never an authoritative playback duration. */
    val advisoryDurationMs: Long?,
    val description: String,
    val publishedEpochMs: Long?,
    val explicit: Boolean,
    val albumLabel: String?,
    val artistLabel: String?,
    val localArtworkPath: String?,
    @Embedded(prefix = "attribution_") val attribution: Attribution?,
)

@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE, deferred = true),
        ForeignKey(entity = AlbumEntity::class, parentColumns = ["id"], childColumns = ["albumId"], onDelete = ForeignKey.SET_NULL, deferred = true),
    ], indices = [Index("albumId")],
)
data class TrackEntity(@PrimaryKey val mediaId: String, val albumId: String?, val trackNumber: Int?, val discNumber: Int?)

@Entity(
    tableName = "track_artists", primaryKeys = ["trackId", "artistId"],
    foreignKeys = [
        ForeignKey(entity = TrackEntity::class, parentColumns = ["mediaId"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE, deferred = true),
        ForeignKey(entity = ArtistEntity::class, parentColumns = ["id"], childColumns = ["artistId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ], indices = [Index("artistId"), Index(value = ["trackId", "position"], unique = true)],
)
data class TrackArtistEntity(val trackId: String, val artistId: String, val position: Int)

@Entity(
    tableName = "album_tracks", primaryKeys = ["albumId", "trackId"],
    foreignKeys = [
        ForeignKey(entity = AlbumEntity::class, parentColumns = ["id"], childColumns = ["albumId"], onDelete = ForeignKey.CASCADE, deferred = true),
        ForeignKey(entity = TrackEntity::class, parentColumns = ["mediaId"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE, deferred = true),
    ], indices = [Index("trackId"), Index(value = ["albumId", "position"], unique = true)],
)
data class AlbumTrackEntity(val albumId: String, val trackId: String, val position: Int)

@Entity(tableName = "podcasts", indices = [Index(value = ["feedUrl"], unique = true), Index("title")])
data class PodcastEntity(
    @PrimaryKey val id: String,
    val title: String,
    val feedUrl: String,
    val publisher: String,
    val description: String,
    val artworkUrl: String?,
    val websiteUrl: String?,
    val language: String?,
    val explicit: Boolean,
    val etag: String?,
    val lastModified: String?,
    val lastRefreshEpochMs: Long?,
    val copyright: String?,
    val localArtworkPath: String?,
    @Embedded(prefix = "attribution_") val attribution: Attribution?,
)

@Entity(
    tableName = "podcast_feed_aliases", primaryKeys = ["podcastId", "feedUrl"],
    foreignKeys = [ForeignKey(entity = PodcastEntity::class, parentColumns = ["id"], childColumns = ["podcastId"], onDelete = ForeignKey.CASCADE, deferred = true)],
    indices = [Index("feedUrl")],
)
data class PodcastFeedAliasEntity(val podcastId: String, val feedUrl: String)

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE, deferred = true),
        ForeignKey(entity = PodcastEntity::class, parentColumns = ["id"], childColumns = ["podcastId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ], indices = [Index("podcastId")],
)
data class EpisodeEntity(@PrimaryKey val mediaId: String, val podcastId: String?, val episodeNumber: Int?, val seasonNumber: Int?)

@Entity(
    tableName = "podcast_episodes", primaryKeys = ["podcastId", "episodeId"],
    foreignKeys = [
        ForeignKey(entity = PodcastEntity::class, parentColumns = ["id"], childColumns = ["podcastId"], onDelete = ForeignKey.CASCADE, deferred = true),
        ForeignKey(entity = EpisodeEntity::class, parentColumns = ["mediaId"], childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE, deferred = true),
    ], indices = [Index("episodeId"), Index(value = ["podcastId", "position"], unique = true)],
)
data class PodcastEpisodeEntity(val podcastId: String, val episodeId: String, val position: Int)

@Entity(tableName = "playlists", indices = [Index(value = ["position"], unique = true)])
data class PlaylistEntity(@PrimaryKey val id: String, val name: String, val createdEpochMs: Long, val updatedEpochMs: Long, val position: Int)

@Entity(
    tableName = "playlist_entries",
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE, deferred = true),
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ], indices = [Index("mediaId"), Index(value = ["playlistId", "position"], unique = true)],
)
data class PlaylistEntryEntity(@PrimaryKey val id: String, val playlistId: String, val mediaId: String, val position: Int)

@Entity(tableName = "queue_contexts")
data class QueueContextEntity(@PrimaryKey val id: QueueContext, val positionMs: Long, val shuffle: Boolean, val repeat: RepeatMode, val speed: Float, val hasOriginalOrder: Boolean)

@Entity(
    tableName = "queue_entries",
    foreignKeys = [
        ForeignKey(entity = QueueContextEntity::class, parentColumns = ["id"], childColumns = ["contextId"], onDelete = ForeignKey.CASCADE, deferred = true),
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ], indices = [Index("mediaId"), Index(value = ["contextId", "position"], unique = true), Index(value = ["contextId", "originalPosition"], unique = true), Index(value = ["contextId", "id"], unique = true)],
)
data class QueueEntryEntity(@PrimaryKey val id: String, val contextId: QueueContext, val mediaId: String, val position: Int, val originalPosition: Int)

@Entity(
    tableName = "queue_cursors",
    foreignKeys = [
        ForeignKey(entity = QueueContextEntity::class, parentColumns = ["id"], childColumns = ["contextId"], onDelete = ForeignKey.CASCADE, deferred = true),
        ForeignKey(entity = QueueEntryEntity::class, parentColumns = ["contextId", "id"], childColumns = ["contextId", "entryId"], onDelete = ForeignKey.CASCADE, deferred = true),
    ], indices = [Index(value = ["contextId", "entryId"])],
)
data class QueueCursorEntity(@PrimaryKey val contextId: QueueContext, val entryId: String)

@Entity(
    tableName = "store_meta",
    foreignKeys = [ForeignKey(entity = QueueContextEntity::class, parentColumns = ["id"], childColumns = ["activeContext"], onDelete = ForeignKey.NO_ACTION, deferred = true)],
    indices = [Index("activeContext")],
)
data class StoreMetaEntity(@PrimaryKey val id: Int = 0, val revision: Long, val activeContext: QueueContext)

@Entity(
    tableName = "favorites",
    foreignKeys = [ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE, deferred = true)],
)
data class FavoriteEntity(@PrimaryKey val mediaId: String)

@Entity(
    tableName = "saved_episodes",
    foreignKeys = [ForeignKey(entity = EpisodeEntity::class, parentColumns = ["mediaId"], childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE, deferred = true)],
)
data class SavedEpisodeEntity(@PrimaryKey val episodeId: String)

@Entity(
    tableName = "followed_podcasts",
    foreignKeys = [ForeignKey(entity = PodcastEntity::class, parentColumns = ["id"], childColumns = ["podcastId"], onDelete = ForeignKey.CASCADE, deferred = true)],
)
data class FollowedPodcastEntity(@PrimaryKey val podcastId: String)

@Entity(
    tableName = "playback_bindings",
    foreignKeys = [ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE, deferred = true)],
    indices = [Index(value = ["mediaId", "resolvedEpochMs"]), Index("selectedVideoId")],
)
data class PlaybackBindingEntity(
    @PrimaryKey val id: String, val mediaId: String, val queryTitle: String, val firstResultKind: FirstResultKind,
    val firstResultId: String, val firstResultTitle: String, val firstResultUrl: String?, val selectedVideoId: String?,
    val resolvedEpochMs: Long, val selectionOrdinal: Int,
)

@Entity(
    tableName = "media_progress",
    foreignKeys = [
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE, deferred = true),
        ForeignKey(entity = PlaybackBindingEntity::class, parentColumns = ["id"], childColumns = ["executionBindingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ], indices = [Index("executionBindingId"), Index("updatedEpochMs")],
)
data class MediaProgressEntity(
    @PrimaryKey val mediaId: String, val positionMs: Long, val durationMs: Long?, val played: Boolean,
    val updatedEpochMs: Long, val sessionId: String, val generation: Long, val sequence: Long, val executionBindingId: String?,
)

@Entity(
    tableName = "playback_history",
    foreignKeys = [
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE, deferred = true),
        ForeignKey(entity = PlaybackBindingEntity::class, parentColumns = ["id"], childColumns = ["executionBindingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ], indices = [Index(value = ["mediaId", "startedEpochMs"]), Index("executionBindingId"), Index(value = ["position"], unique = true)],
)
data class HistoryEntity(
    @PrimaryKey val id: String, val mediaId: String, val startedEpochMs: Long, val lastPositionMs: Long,
    val endedNaturally: Boolean, val executionBindingId: String?, val position: Int,
)

@Entity(
    tableName = "offline_assets",
    foreignKeys = [
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = PlaybackBindingEntity::class, parentColumns = ["id"], childColumns = ["executionBindingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ], indices = [Index("mediaId"), Index("executionBindingId"), Index(value = ["uri"], unique = true)],
)
data class OfflineAssetEntity(
    @PrimaryKey val id: String, val mediaId: String, val uri: String, val sizeBytes: Long, val kind: OfflineAssetKind,
    val mimeType: String?, val codecs: String?, val sha256: String?, val representationId: String?,
    val validatorGeneration: Long, val completedEpochMs: Long, val executionBindingId: String?, val measuredDurationMs: Long?,
)

@Entity(
    tableName = "downloads",
    foreignKeys = [
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = OfflineAssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = PlaybackBindingEntity::class, parentColumns = ["id"], childColumns = ["executionBindingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = DownloadAttemptEntity::class, parentColumns = ["id"], childColumns = ["activeAttemptId"], onDelete = ForeignKey.SET_NULL, deferred = true),
    ], indices = [Index(value = ["mediaId"], unique = true), Index("assetId"), Index("executionBindingId"), Index("activeAttemptId"), Index(value = ["status", "nextRetryEpochMs", "priority"])],
)
data class DownloadEntity(
    @PrimaryKey val id: String, val mediaId: String, val status: DownloadStatus, val bytesDownloaded: Long,
    val totalBytes: Long?, val assetId: String?, @Embedded(prefix = "failure_") val failure: AppFailure?,
    val stagingPath: String?, val representationId: String?, val etag: String?, val lastModified: String?,
    val validatorGeneration: Long, val updatedEpochMs: Long, val executionBindingId: String?, val attemptCount: Int,
    val activeAttemptId: String?, val nextRetryEpochMs: Long?, val priority: Int, val createdEpochMs: Long,
    val userPaused: Boolean, val leaseOwner: String?, val leaseExpiresEpochMs: Long?, val lastProgressEpochMs: Long?,
    val automaticRetryCount: Int,
    @ColumnInfo(defaultValue = "0") val cleanupPending: Boolean,
    @ColumnInfo(defaultValue = "0") val removeWhenClean: Boolean,
)

@Entity(
    tableName = "download_attempts",
    foreignKeys = [
        ForeignKey(entity = DownloadEntity::class, parentColumns = ["id"], childColumns = ["downloadId"], onDelete = ForeignKey.CASCADE, deferred = true),
        ForeignKey(entity = PlaybackBindingEntity::class, parentColumns = ["id"], childColumns = ["executionBindingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ], indices = [Index(value = ["downloadId", "number"], unique = true), Index("executionBindingId")],
)
data class DownloadAttemptEntity(
    @PrimaryKey val id: String, val downloadId: String, val number: Int, val status: DownloadAttemptStatus,
    val startedEpochMs: Long, val finishedEpochMs: Long?, val bytesDownloaded: Long, val totalBytes: Long?,
    @Embedded(prefix = "failure_") val failure: AppFailure?, val stagingPath: String?, val representationId: String?,
    val executionBindingId: String?, val etag: String?, val lastModified: String?, val validatorGeneration: Long,
    val backgroundTaskId: String?,
)

@Entity(
    tableName = "download_finalizations",
    foreignKeys = [
        ForeignKey(entity = DownloadEntity::class, parentColumns = ["id"], childColumns = ["downloadId"], onDelete = ForeignKey.CASCADE, deferred = true),
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = DownloadAttemptEntity::class, parentColumns = ["id"], childColumns = ["attemptId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = PlaybackBindingEntity::class, parentColumns = ["id"], childColumns = ["executionBindingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ], indices = [Index("mediaId"), Index("attemptId"), Index("executionBindingId")],
)
data class DownloadFinalizationEntity(
    @PrimaryKey val downloadId: String, val mediaId: String, val stagingPath: String, val finalPath: String,
    val sha256: String, val sizeBytes: Long, val attemptId: String?, val mediaDurationMs: Long?, val mimeType: String?,
    val codecs: String?, val executionBindingId: String?, val representationId: String?, val validatorGeneration: Long,
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 0, val language: LanguagePreference, val theme: ThemeMode, val textScale: Float,
    val offlineOnly: Boolean, val speed: Float, val skipBackSeconds: Int, val skipForwardSeconds: Int,
    @ColumnInfo(defaultValue = "0") val crossfadeEnabled: Boolean,
    @ColumnInfo(defaultValue = "3000") val crossfadeDurationMs: Long,
    val downloadParallelism: Int,
)

@Entity(
    tableName = "podcast_speeds",
    foreignKeys = [ForeignKey(entity = PodcastEntity::class, parentColumns = ["id"], childColumns = ["podcastId"], onDelete = ForeignKey.CASCADE, deferred = true)],
)
data class PodcastSpeedEntity(@PrimaryKey val podcastId: String, val speed: Float)

@Entity(tableName = "navigation_state")
data class NavigationEntity(@PrimaryKey val key: String, val value: String)
