package dev.veyra.source

import dev.veyra.model.AppFailure
import dev.veyra.model.MediaItem
import dev.veyra.model.PodcastShow
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.ResolvedPlayable

data class SourceCapabilities(
    val search: Boolean = false,
    val browse: Boolean = false,
    val resolve: Boolean = true,
    val download: Boolean = false,
    val podcasts: Boolean = false,
)

enum class BrowseKind { HOME, CHANNEL, PLAYLIST, SHOW }
data class BrowseRequest(
    val kind: BrowseKind = BrowseKind.HOME,
    val id: String? = null,
    val pageToken: String? = null,
    val language: String? = null,
)

data class MediaCollection(
    val id: String,
    val title: String,
    val source: SourceRef,
    val artworkUrl: String? = null,
    val creator: String = "",
    val kind: BrowseKind = BrowseKind.PLAYLIST,
)

data class SourcePage(
    val items: List<MediaItem> = emptyList(),
    val shows: List<PodcastShow> = emptyList(),
    val collections: List<MediaCollection> = emptyList(),
    val nextPageToken: String? = null,
    val attribution: String? = null,
    val notice: AppFailure? = null,
)

enum class ResolvePurpose { PLAYBACK, DOWNLOAD }

/** All failures use VeyraException(AppFailure); cancellation must propagate unchanged. */
interface MediaSource {
    val id: SourceProvider
    val capabilities: SourceCapabilities
    suspend fun search(query: String, pageToken: String? = null): SourcePage
    suspend fun browse(request: BrowseRequest = BrowseRequest()): SourcePage
    suspend fun details(ref: SourceRef): MediaItem
    suspend fun resolve(
        item: MediaItem,
        capabilities: EngineCapabilities,
        purpose: ResolvePurpose = ResolvePurpose.PLAYBACK,
    ): ResolvedPlayable
    suspend fun close()
}

typealias SourceAdapter = MediaSource

/** Root injects offline-first asset validation and source selection. No network when offlineOnly is true. */
interface SourceResolver {
    suspend fun resolve(
        item: MediaItem,
        capabilities: EngineCapabilities,
        offlineOnly: Boolean = false,
        purpose: ResolvePurpose = ResolvePurpose.PLAYBACK,
        /** Resume/URL renewal uses the existing immutable FIRST-result receipt, never a new search. */
        reuseBindingId: String? = null,
    ): ResolvedPlayable
}
