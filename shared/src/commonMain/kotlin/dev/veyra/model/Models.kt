package dev.veyra.model

import kotlinx.serialization.Serializable
import kotlin.random.Random

/** Requested metadata provenance; all media execution still uses the central acquisition port. */
@Serializable enum class SourceProvider { YOUTUBE, RSS, LOCAL, COMMONS, MUSICBRAINZ }
@Serializable enum class MediaKind { MUSIC, EPISODE }
@Serializable enum class QueueContext { MUSIC, PODCAST }
@Serializable enum class RepeatMode { OFF, ALL, ONE }
@Serializable enum class LanguagePreference { SYSTEM, ENGLISH, ARABIC }
@Serializable enum class ThemeMode { SYSTEM, LIGHT, DARK }
@Serializable enum class StreamTransport { PROGRESSIVE, HLS, DASH, SABR }

/** Only stable source locators belong here. Never persist resolved YouTube URLs or request headers. */
@Serializable
data class SourceRef(
    val provider: SourceProvider,
    val canonicalId: String,
    val canonicalUrl: String? = null,
    val feedUrl: String? = null,
    val episodeGuid: String? = null,
    val enclosureUrl: String? = null,
    val mimeType: String? = null,
    val localAssetId: String? = null,
)

@Serializable
data class Attribution(
    val creator: String = "",
    val sourceUrl: String = "",
    val licenseName: String? = null,
    val licenseUrl: String? = null,
)

@Serializable
data class MediaItem(
    val id: String,
    val title: String,
    val kind: MediaKind,
    val source: SourceRef,
    val creator: String = "",
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
    val showId: String? = null,
    val description: String = "",
    val publishedEpochMs: Long? = null,
    val explicit: Boolean = false,
    val album: String? = null,
    val artist: String? = null,
    val episodeNumber: Int? = null,
    val seasonNumber: Int? = null,
    val attribution: Attribution? = null,
    val artistIds: List<String> = emptyList(),
    val albumId: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val localArtworkPath: String? = null,
)

@Serializable
data class Artist(
    val id: String,
    val name: String,
    val sortName: String = name,
    val description: String = "",
    val artworkUrl: String? = null,
    val source: SourceRef? = null,
)

@Serializable
data class Album(
    val id: String,
    val title: String,
    val artistIds: List<String> = emptyList(),
    val trackIds: List<String> = emptyList(),
    val artworkUrl: String? = null,
    val description: String = "",
    val releaseYear: Int? = null,
    val source: SourceRef? = null,
)

@Serializable enum class FirstResultKind { VIDEO, CHANNEL, PLAYLIST, UNKNOWN }

/** Immutable receipt of title -> FIRST result. Never replace the discovery entity with this. */
@Serializable
data class PlaybackBinding(
    val id: String,
    val mediaId: String,
    val queryTitle: String,
    val firstResultKind: FirstResultKind,
    val firstResultId: String,
    val firstResultTitle: String,
    val firstResultUrl: String? = null,
    val selectedVideoId: String? = null,
    val resolvedEpochMs: Long = 0,
    val selectionOrdinal: Int = 0,
)

@Serializable
data class PodcastShow(
    val id: String,
    val title: String,
    val feedUrl: String,
    val publisher: String = "",
    val description: String = "",
    val artworkUrl: String? = null,
    val websiteUrl: String? = null,
    val language: String? = null,
    val explicit: Boolean = false,
    val episodeIds: List<String> = emptyList(),
    val feedAliases: Set<String> = emptySet(),
    val etag: String? = null,
    val lastModified: String? = null,
    val lastRefreshEpochMs: Long? = null,
    val copyright: String? = null,
    val attribution: Attribution? = null,
    val localArtworkPath: String? = null,
)

@Serializable
data class PlaylistEntry(val id: String, val mediaId: String)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val entries: List<PlaylistEntry> = emptyList(),
    val createdEpochMs: Long = 0,
    val updatedEpochMs: Long = 0,
)

@Serializable
data class QueueEntry(val id: String, val mediaId: String)

@Serializable
data class QueueSnapshot(
    val entries: List<QueueEntry> = emptyList(),
    val currentEntryId: String? = null,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    /** Canonical occurrence order retained while the visible order is shuffled. */
    val originalOrder: List<String> = emptyList(),
    val speed: Float = 1f,
) {
    val currentEntry: QueueEntry? get() = entries.firstOrNull { it.id == currentEntryId }
    val currentIndex: Int get() = entries.indexOfFirst { it.id == currentEntryId }
}

@Serializable
data class MediaProgress(
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val played: Boolean = false,
    val updatedEpochMs: Long = 0,
    val sessionId: String = "",
    val generation: Long = 0,
    val sequence: Long = 0,
    val executionBindingId: String? = null,
)

@Serializable
data class PlaybackHistoryEntry(
    val id: String,
    val mediaId: String,
    val startedEpochMs: Long,
    val lastPositionMs: Long = 0,
    val endedNaturally: Boolean = false,
    val executionBindingId: String? = null,
)

@Serializable enum class OfflineAssetKind { FILE, MEDIA3_CACHE, AV_HLS_PACKAGE }

@Serializable
data class OfflineAsset(
    val id: String,
    val mediaId: String,
    val uri: String,
    val sizeBytes: Long,
    val kind: OfflineAssetKind = OfflineAssetKind.FILE,
    val mimeType: String? = null,
    val codecs: String? = null,
    val sha256: String? = null,
    val representationId: String? = null,
    val validatorGeneration: Long = 0,
    val completedEpochMs: Long = 0,
    val executionBindingId: String? = null,
    val measuredDurationMs: Long? = null,
)

@Serializable enum class DownloadStatus {
    QUEUED, RESOLVING, RUNNING, PAUSED, WAITING_NETWORK, NEEDS_FOREGROUND,
    VERIFYING, COMPLETE, FAILED, CANCELLED,
}

@Serializable
data class DownloadRecord(
    val id: String,
    val mediaId: String,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null,
    val asset: OfflineAsset? = null,
    val failure: AppFailure? = null,
    val stagingPath: String? = null,
    val representationId: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val validatorGeneration: Long = 0,
    val updatedEpochMs: Long = 0,
    val executionBindingId: String? = null,
    val attemptCount: Int = 0,
    val activeAttemptId: String? = null,
    val nextRetryEpochMs: Long? = null,
    val priority: Int = 0,
    val createdEpochMs: Long = 0,
    val userPaused: Boolean = false,
    val leaseOwner: String? = null,
    val leaseExpiresEpochMs: Long? = null,
    val lastProgressEpochMs: Long? = null,
    val automaticRetryCount: Int = 0,
    /** Durable cleanup intent. Never delete bytes while a different unexpired lease owns them. */
    val cleanupPending: Boolean = false,
    val removeWhenClean: Boolean = false,
)

@Serializable enum class DownloadAttemptStatus { RUNNING, VALIDATING, FINALIZING, SUCCEEDED, FAILED, CANCELLED, PAUSED }

@Serializable
data class DownloadAttempt(
    val id: String,
    val downloadId: String,
    val number: Int,
    val status: DownloadAttemptStatus = DownloadAttemptStatus.RUNNING,
    val startedEpochMs: Long = 0,
    val finishedEpochMs: Long? = null,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null,
    val failure: AppFailure? = null,
    val stagingPath: String? = null,
    val representationId: String? = null,
    val executionBindingId: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val validatorGeneration: Long = 0,
    val backgroundTaskId: String? = null,
)

/** Persist BEFORE the final rename; recovery verifies these bytes before publishing the asset. */
@Serializable
data class DownloadFinalization(
    val downloadId: String,
    val mediaId: String,
    val stagingPath: String,
    val finalPath: String,
    val sha256: String,
    val sizeBytes: Long,
    val attemptId: String? = null,
    val mediaDurationMs: Long? = null,
    val mimeType: String? = null,
    val codecs: String? = null,
    val executionBindingId: String? = null,
    val representationId: String? = null,
    val validatorGeneration: Long = 0,
)

@Serializable
data class AppSettings(
    val language: LanguagePreference = LanguagePreference.SYSTEM,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val textScale: Float = 1f,
    val offlineOnly: Boolean = false,
    val speed: Float = 1f,
    val skipBackSeconds: Int = 15,
    val skipForwardSeconds: Int = 30,
    val showSpeeds: Map<String, Float> = emptyMap(),
    val crossfadeEnabled: Boolean = false,
    val crossfadeDurationMs: Long = 3_000,
    val downloadParallelism: Int = 2,
)

@Serializable
data class AppSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val revision: Long = 0,
    val media: Map<String, MediaItem> = emptyMap(),
    val shows: Map<String, PodcastShow> = emptyMap(),
    val favoriteIds: Set<String> = emptySet(),
    val playlists: List<Playlist> = emptyList(),
    val followedShowIds: Set<String> = emptySet(),
    val savedEpisodeIds: Set<String> = emptySet(),
    val progress: Map<String, MediaProgress> = emptyMap(),
    val queues: Map<QueueContext, QueueSnapshot> = QueueContext.entries.associateWith { QueueSnapshot() },
    val activeContext: QueueContext = QueueContext.MUSIC,
    val downloads: Map<String, DownloadRecord> = emptyMap(),
    val settings: AppSettings = AppSettings(),
    val history: List<PlaybackHistoryEntry> = emptyList(),
    val navigation: Map<String, String> = emptyMap(),
    val artists: Map<String, Artist> = emptyMap(),
    val albums: Map<String, Album> = emptyMap(),
    val playbackBindings: Map<String, PlaybackBinding> = emptyMap(),
    val downloadAttempts: Map<String, DownloadAttempt> = emptyMap(),
    val downloadFinalizations: Map<String, DownloadFinalization> = emptyMap(),
) {
    fun queue(context: QueueContext): QueueSnapshot = queues[context] ?: QueueSnapshot()
    companion object { const val CURRENT_SCHEMA: Int = 2 }
}

@Serializable enum class FailureCode {
    NETWORK_UNAVAILABLE, OFFLINE_POLICY, PROVIDER_ACCESS_REQUIRED, RATE_LIMITED,
    NOT_FOUND, UNSUPPORTED_SOURCE, UNSUPPORTED_TRANSPORT, UNSUPPORTED_CODEC,
    PERMISSION_DENIED, BACKGROUND_RESTRICTED, STORAGE_FULL, STORAGE_IO, CORRUPT_MEDIA, PARSE_FAILURE,
    RECOVERY_REQUIRED, FUTURE_SCHEMA, INVALID_STATE, CANCELLED, UNEXPECTED,
}

/** Presentation maps [code] to localized text. [detail] must never contain signed URLs or credentials. */
@Serializable
data class AppFailure(
    val code: FailureCode,
    val detail: String? = null,
    val retryAfterMs: Long? = null,
)

class VeyraException(val failure: AppFailure) : Exception(failure.code.name)

fun newEntityId(prefix: String = "entry"): String =
    "$prefix-${Random.nextLong().toULong().toString(16)}-${Random.nextLong().toULong().toString(16)}"
