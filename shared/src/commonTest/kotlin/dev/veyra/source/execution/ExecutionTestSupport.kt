package dev.veyra.source.execution

import dev.veyra.data.LibraryPersistence
import dev.veyra.data.OpenedLibrary
import dev.veyra.data.validateLibrarySnapshot
import dev.veyra.data.validateLibraryTransition
import dev.veyra.model.*
import dev.veyra.playback.Clock
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.youtube.YouTubeTransferProgress
import dev.veyra.source.youtube.YouTubeTransferResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Authored deterministic seam only. Production persistence is Room, exercised by JVM tests. */
internal class ExecutionTestPersistence(initial: AppSnapshot = AppSnapshot()) : LibraryPersistence {
    var snapshot = initial
    var failBindingWrites = false
    override suspend fun open(): OpenedLibrary = OpenedLibrary(snapshot.also(::validateLibrarySnapshot))
    override suspend fun read(): AppSnapshot = snapshot
    override suspend fun transact(expectedRevision: Long?, transform: (AppSnapshot) -> AppSnapshot): AppSnapshot {
        if (expectedRevision != null && expectedRevision != snapshot.revision) throw VeyraException(AppFailure(FailureCode.INVALID_STATE))
        val next = transform(snapshot)
        if (next == snapshot) return snapshot
        validateLibraryTransition(snapshot, next)
        if (failBindingWrites && next.playbackBindings != snapshot.playbackBindings) throw VeyraException(AppFailure(FailureCode.STORAGE_IO))
        snapshot = next.copy(revision = snapshot.revision + 1)
        return snapshot
    }
    override fun changes(): Flow<Unit> = emptyFlow()
    override suspend fun backup() = Unit
    override suspend fun recoverFromBackup(): OpenedLibrary = error("No fake recovery")
    override suspend fun close() = Unit
}

internal const val FIRST_VIDEO_ID = "AAAAAAAAAAA"
internal const val LATER_VIDEO_ID = "BBBBBBBBBBB"

internal class ExecutionTestClock(var now: Long = 10_000L) : Clock {
    override fun epochMs(): Long = now
    override fun monotonicMs(): Long = now
}

/** Authored fixture gateway; no HTTP, playback, or download success is claimed by these tests. */
internal class ExecutionTestGateway : KmpMediaGateway {
    val queries = mutableListOf<String>()
    val resolutions = mutableListOf<Pair<PlaybackBinding, ResolvePurpose>>()
    var downloads = 0
    var first: suspend (String) -> KmpFirstResult? = { query ->
        KmpFirstResult(query, FirstResultKind.VIDEO, FIRST_VIDEO_ID, "Actual first result title",
            "https://www.youtube.com/watch?v=$FIRST_VIDEO_ID")
    }
    var execute: suspend (PlaybackBinding, EngineCapabilities, ResolvePurpose) -> ResolvedPlayable = { binding, _, _ ->
        ResolvedPlayable(
            mediaId = "youtube:${binding.selectedVideoId}", uri = "https://media.example.com/authored-only-ephemeral-audio",
            mimeType = "audio/mp4", codecs = "aac", durationMs = 123_456L,
            item = MediaItem("youtube:${binding.selectedVideoId}", "Actual first result title", MediaKind.MUSIC,
                SourceRef(SourceProvider.YOUTUBE, requireNotNull(binding.selectedVideoId))),
        )
    }
    override suspend fun firstResult(completeTitle: String): KmpFirstResult? {
        queries += completeTitle
        return first(completeTitle)
    }
    override suspend fun resolve(binding: PlaybackBinding, capabilities: EngineCapabilities, purpose: ResolvePurpose): ResolvedPlayable {
        resolutions += binding to purpose
        return execute(binding, capabilities, purpose)
    }
    override suspend fun download(
        binding: PlaybackBinding,
        destinationPath: String,
        capabilities: EngineCapabilities,
        representationId: String?,
        onProgress: suspend (YouTubeTransferProgress) -> Unit,
    ): YouTubeTransferResult {
        downloads++
        error("Transfer belongs to the download coordinator, never AppSourceResolver")
    }
}

internal fun executionTestItem(
    provider: SourceProvider = SourceProvider.RSS,
    title: String = "The full discovery title — العنوان الكامل",
    id: String = "original-discovery-identity",
) = MediaItem(
    id = id, title = title, kind = if (provider == SourceProvider.RSS) MediaKind.EPISODE else MediaKind.MUSIC,
    source = SourceRef(provider, if (provider == SourceProvider.YOUTUBE) LATER_VIDEO_ID else id,
        canonicalUrl = if (provider == SourceProvider.YOUTUBE) "https://www.youtube.com/watch?v=$LATER_VIDEO_ID" else "https://publisher.example.com/entry",
        enclosureUrl = if (provider == SourceProvider.RSS) "https://publisher.example.com/never-open-this.mp3" else null),
    durationMs = 9_999_999L, attribution = Attribution("Original metadata publisher", "https://publisher.example.com/entry", "Original source rights only"),
)
