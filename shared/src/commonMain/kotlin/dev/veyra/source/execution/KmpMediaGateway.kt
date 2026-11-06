package dev.veyra.source.execution

import dev.veyra.model.AppFailure
import dev.veyra.model.FirstResultKind
import dev.veyra.model.PlaybackBinding
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.youtube.YouTubeTransferProgress
import dev.veyra.source.youtube.YouTubeTransferResult

/**
 * The actual leading kmpYT search entry, BEFORE filtering video/channel/playlist/unavailable
 * cards. A truly empty search returns null instead. Unknown cards may have empty id/title:
 * inventing a video identity or advancing to a later usable entry is forbidden.
 *
 * [queryTitle] is the complete submitted title, not a display string or shortened retry.
 * [canonicalUrl] may contain only a stable public YouTube locator, never an execution URL.
 * [failure] retains an unavailable FIRST entry's typed diagnostic without replacing that entry.
 */
data class KmpFirstResult(
    val queryTitle: String,
    val kind: FirstResultKind,
    val id: String,
    val title: String,
    val canonicalUrl: String? = null,
    val selectionOrdinal: Int = 0,
    val failure: AppFailure? = null,
)

/**
 * Single injectable seam for the real kmpYT implementation. The production bridge is
 * YouTubeKmpMediaGateway; neither RSS/Commons adapters nor a competing HTTP downloader belong
 * here. All exceptions are typed VeyraException failures, except cancellation which propagates.
 */
interface KmpMediaGateway {
    suspend fun firstResult(completeTitle: String): KmpFirstResult?

    /** Resolve ONLY the selected video in [binding], including bounded expired-link renewal. */
    suspend fun resolve(
        binding: PlaybackBinding,
        capabilities: EngineCapabilities,
        purpose: ResolvePurpose,
    ): ResolvedPlayable

    /**
     * kmpYT owns transfer/ranges/parallelism/resume journals under the provided private staging
     * path. This return is transfer evidence, not a validated offline asset or Ready commit.
     */
    suspend fun download(
        binding: PlaybackBinding,
        destinationPath: String,
        capabilities: EngineCapabilities,
        representationId: String? = null,
        onProgress: suspend (YouTubeTransferProgress) -> Unit = {},
    ): YouTubeTransferResult
}
