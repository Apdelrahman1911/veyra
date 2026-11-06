package dev.veyra.artwork

import dev.veyra.data.LibraryStore
import dev.veyra.data.getOrThrow
import dev.veyra.model.AppSnapshot
import dev.veyra.model.FailureCode
import dev.veyra.model.MediaItem
import dev.veyra.model.PodcastShow
import dev.veyra.model.VeyraException
import dev.veyra.source.network.PublicUrlPolicy
import dev.veyra.source.network.SafeHttp
import dev.veyra.source.network.SafeHttpRequest
import dev.veyra.source.network.requireHttpSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * App-lifetime, image-only disk cache. Uses the same bounded public HTTPS metadata transport as
 * discovery, never the media execution/download path. Room holds managed local paths; bytes are
 * streamed to a separate image store. No source URL, response headers, or bytes are logged.
 *
 * Call [initialize] after the library opens. Local getters are synchronous, no-I/O projections
 * of paths validated by initialization/cache work, suitable for native now-playing callbacks.
 * New catalog/relationship metadata should normally be persisted before cacheMedia/cacheShow.
 * The shared [http] belongs to the app container and is not closed by this service.
 */
class ArtworkCache(
    private val store: LibraryStore,
    private val files: ArtworkFiles,
    private val http: SafeHttp,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    maximumConcurrentRequests: Int = 2,
) {
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + dispatcher)
    private val initialization = Mutex()
    private val pendingLock = Mutex()
    private val pending = mutableMapOf<String, Deferred<String?>>()
    private val requestSlots = Semaphore(maximumConcurrentRequests.also { require(it in 1..4) })
    private val verifiedPaths = MutableStateFlow<Map<String, String>>(emptyMap())
    private var initialized = false

    /** Local validation only, including after a process restart with all networking disabled. */
    suspend fun initialize() = withContext(dispatcher) {
        initialization.withLock {
            requireOpen()
            if (initialized) return@withLock
            val snapshot = store.initialize().getOrThrow()
            files.initialize()
            val validated = mutableMapOf<String, String>()
            val rejected = mutableMapOf<String, String>()
            val references = snapshot.media.values.map { mediaKey(it.id) to it.localArtworkPath } +
                snapshot.shows.values.map { showKey(it.id) to it.localArtworkPath }
            for ((key, path) in references) {
                currentCoroutineContext().ensureActive()
                if (path == null) continue
                val valid = verified(key, path)
                if (valid != null) validated[key] = valid.path else rejected[key] = path
            }
            // A missing, modified or external file must not remain a trusted UI/native path.
            // Do not erase a newer concurrent cache publication while repairing stale metadata.
            if (rejected.isNotEmpty()) store.update { current ->
                current.copy(
                    media = current.media.mapValues { (_, item) ->
                        if (item.localArtworkPath != null && rejected[mediaKey(item.id)] == item.localArtworkPath)
                            item.copy(localArtworkPath = null) else item
                    },
                    shows = current.shows.mapValues { (_, show) ->
                        if (show.localArtworkPath != null && rejected[showKey(show.id)] == show.localArtworkPath)
                            show.copy(localArtworkPath = null) else show
                    },
                )
            }.getOrThrow()
            requireOpen()
            verifiedPaths.value = validated
            initialized = true
        }
    }

    /** Current persisted discovery metadata wins over a stale screen argument. */
    suspend fun cacheMedia(item: MediaItem): String? {
        initialize()
        return coalesced(mediaKey(item.id)) {
            val current = store.update { snapshot ->
                val prior = snapshot.media[item.id]
                if (prior != null && (prior.kind != item.kind || prior.source.provider != item.source.provider ||
                        prior.source.canonicalId != item.source.canonicalId)) {
                    artworkFailure(FailureCode.INVALID_STATE, "artwork_media_identity_changed")
                }
                if (prior == null) snapshot.copy(media = snapshot.media + (item.id to item.copy(localArtworkPath = null))) else snapshot
            }.getOrThrow().media.getValue(item.id)
            cache(mediaKey(item.id), current.artworkUrl, current.localArtworkPath) { snapshot, path ->
                val latest = snapshot.media[item.id]
                    ?: artworkFailure(FailureCode.INVALID_STATE, "artwork_media_removed")
                if (latest.artworkUrl != current.artworkUrl) artworkFailure(FailureCode.INVALID_STATE, "artwork_metadata_changed")
                snapshot.copy(media = snapshot.media + (item.id to latest.copy(localArtworkPath = path)))
            }
        }
    }

    suspend fun cacheShow(show: PodcastShow): String? {
        initialize()
        return coalesced(showKey(show.id)) {
            val current = store.update { snapshot ->
                if (show.id !in snapshot.shows) snapshot.copy(shows = snapshot.shows + (show.id to show.copy(localArtworkPath = null))) else snapshot
            }.getOrThrow().shows.getValue(show.id)
            cache(showKey(show.id), current.artworkUrl, current.localArtworkPath) { snapshot, path ->
                val latest = snapshot.shows[show.id]
                    ?: artworkFailure(FailureCode.INVALID_STATE, "artwork_show_removed")
                if (latest.artworkUrl != current.artworkUrl) artworkFailure(FailureCode.INVALID_STATE, "artwork_metadata_changed")
                snapshot.copy(shows = snapshot.shows + (show.id to latest.copy(localArtworkPath = path)))
            }
        }
    }

    /** No HTTP, DNS, filesystem I/O or fresh image acquisition. Show artwork is a local fallback. */
    fun cachedArtworkPath(mediaId: String): String? {
        if (!lifetime.isActive) return null
        val item = store.state.value.media[mediaId] ?: return null
        return item.localArtworkPath?.takeIf { verifiedPaths.value[mediaKey(mediaId)] == it }
            ?: item.showId?.let(::cachedShowArtworkPath)
    }

    fun cachedShowArtworkPath(showId: String): String? {
        if (!lifetime.isActive) return null
        return store.state.value.shows[showId]?.localArtworkPath?.takeIf { verifiedPaths.value[showKey(showId)] == it }
    }

    /** Cancels coalesced transfers/readers and removes their staging bytes, but retains images. */
    suspend fun close() {
        lifetime.cancelAndJoin()
        verifiedPaths.value = emptyMap()
    }

    private suspend fun coalesced(key: String, action: suspend () -> String?): String? {
        requireOpen()
        val task = pendingLock.withLock {
            requireOpen()
            pending[key] ?: scope.async {
                try { action() }
                finally { withContext(NonCancellable) { pendingLock.withLock { pending.remove(key) } } }
            }.also { pending[key] = it }
        }
        // Cancelling one screen waiter does not cancel another's shared request. The app lifetime
        // and persisted offline toggle do cancel the producer, even if no waiter remains.
        return task.await()
    }

    private suspend fun cache(
        key: String,
        rawUrl: String?,
        priorPath: String?,
        update: (AppSnapshot, String) -> AppSnapshot,
    ): String? {
        val existing = priorPath?.let { verified(key, it) }
        if (existing != null) verifiedPaths.update { it + (key to existing.path) }
        else verifiedPaths.update { it - key }
        // Publisher artwork can change. Retain the historical image without any lookup offline.
        if (existing != null && (store.state.value.settings.offlineOnly || rawUrl == null)) return existing.path
        if (rawUrl == null) return null
        requireOnline()
        val url = PublicUrlPolicy.normalize(rawUrl)
        if (existing != null && files.matchesUrl(existing, url)) return existing.path
        return requestSlots.withPermit {
            requireOnline()
            val stage = files.newStage(key)
            try {
                val mime = onlineTransfer {
                    requireOnline()
                    http.stream(SafeHttpRequest(url, mapOf("Accept" to ArtworkImage.ACCEPT), timeoutMs = 30_000,
                        beforeRequest = ::requireOnline)) { response, channel ->
                        requireOnline()
                        requireHttpSuccess(response)
                        if (response.status != 200 || response.header("content-range") != null) {
                            artworkFailure(FailureCode.PARSE_FAILURE, "artwork_partial_response")
                        }
                        val encoding = response.header("content-encoding")?.trim()?.lowercase()
                        if (encoding != null && encoding != "identity") artworkFailure(FailureCode.UNSUPPORTED_SOURCE, "artwork_content_encoding")
                        val type = ArtworkImage.requireMime(response.header("content-type"))
                        val declared = response.header("content-length")?.let { text ->
                            text.toLongOrNull()?.takeIf { it in 1..ArtworkImage.MAX_BYTES }
                                ?: artworkFailure(FailureCode.PARSE_FAILURE, "artwork_size_limit")
                        }
                        var received = 0L
                        files.sink(stage).use { sink ->
                            val bytes = ByteArray(16 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                requireOnline()
                                val count = channel.readAvailable(bytes, 0, bytes.size)
                                if (count < 0) break
                                if (count == 0) continue
                                requireOnline()
                                received += count
                                if (received > ArtworkImage.MAX_BYTES || declared?.let { received > it } == true) {
                                    artworkFailure(FailureCode.PARSE_FAILURE, "artwork_size_limit")
                                }
                                sink.write(bytes, 0, count)
                            }
                        }
                        if (received == 0L || declared?.let { it != received } == true) {
                            artworkFailure(FailureCode.PARSE_FAILURE, "artwork_incomplete_response")
                        }
                        type
                    }
                }
                requireOnline()
                val path = files.publish(stage, key, url, mime)
                // Atomic file publication precedes the Room link. A failed/interrupted commit can
                // leave a complete unreferenced image, never a persisted path to partial bytes.
                store.update { snapshot -> update(snapshot, path) }.getOrThrow()
                verifiedPaths.update { it + (key to path) }
                path
            } finally {
                // A cleanup error cannot replace the primary network/cancellation/storage error.
                withContext(NonCancellable) { try { files.discard(stage) } catch (_: Exception) { } }
            }
        }
    }

    /** The policy is rechecked per redirect; a toggle also cancels a currently waiting response. */
    private suspend fun <T> onlineTransfer(block: suspend () -> T): T = supervisorScope {
        requireOnline()
        val offline = async { store.state.first { it.settings.offlineOnly } }
        val transfer = async { block() }
        try {
            select {
                offline.onAwait { artworkFailure(FailureCode.OFFLINE_POLICY, "artwork_offline_only") }
                transfer.onAwait { it }
            }
        } finally {
            withContext(NonCancellable) { offline.cancelAndJoin(); transfer.cancelAndJoin() }
        }
    }

    private fun verified(key: String, path: String): VerifiedArtwork? = try {
        files.verify(path).takeIf { files.matchesOwner(it, key) }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: VeyraException) {
        if (failure.failure.code in setOf(FailureCode.STORAGE_IO, FailureCode.STORAGE_FULL)) throw failure
        null
    }

    private fun requireOpen() {
        if (!lifetime.isActive) artworkFailure(FailureCode.INVALID_STATE, "artwork_cache_closed")
    }

    private fun requireOnline() {
        requireOpen()
        if (store.state.value.settings.offlineOnly) artworkFailure(FailureCode.OFFLINE_POLICY, "artwork_offline_only")
    }

    private fun mediaKey(id: String): String = "media:${id.length}:$id"
    private fun showKey(id: String): String = "show:${id.length}:$id"
}
