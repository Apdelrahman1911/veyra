package dev.veyra.app

import dev.veyra.model.AppSnapshot
import dev.veyra.model.QueueContext
import dev.veyra.model.MediaKind
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.source.BrowseKind
import dev.veyra.source.MediaCollection
import dev.veyra.ui.music.LibrarySection

enum class AppDestination { LISTEN, SEARCH, PODCASTS, LIBRARY, SETTINGS }
enum class DetailKind { TRACK, EPISODE, SHOW, PLAYLIST, COLLECTION, LICENSES }

data class AppDetail(val kind: DetailKind, val id: String, val collection: MediaCollection? = null)

/** Navigation is independent of the active audio context. Opening a screen never starts audio. */
data class AppNavigation(
    val destination: AppDestination = AppDestination.LISTEN,
    val librarySection: LibrarySection = LibrarySection.FAVORITES,
    val details: List<AppDetail> = emptyList(),
    val playerOpen: Boolean = false,
    val queueOpen: Boolean = false,
    val viewedQueue: QueueContext = QueueContext.MUSIC,
) {
    val detail: AppDetail? get() = details.lastOrNull()
    val canGoBack: Boolean get() = queueOpen || playerOpen || details.isNotEmpty() || destination != AppDestination.LISTEN

    fun back(): AppNavigation = when {
        queueOpen -> copy(queueOpen = false)
        playerOpen -> copy(playerOpen = false)
        details.isNotEmpty() -> copy(details = details.dropLast(1))
        destination != AppDestination.LISTEN -> copy(destination = AppDestination.LISTEN)
        else -> this
    }

    fun open(next: AppDetail): AppNavigation = copy(
        details = if (detail == next) details else (details + next).takeLast(MAX_DEPTH),
        playerOpen = false, queueOpen = false,
    )

    /** Typed small Room navigation rows, not a second persistence engine or an AppSnapshot blob. */
    fun persisted(): Map<String, String> = buildMap {
        put("screen", destination.name)
        put("librarySection", librarySection.name)
        put("viewedQueue", viewedQueue.name)
        put("details", details.size.toString())
        details.forEachIndexed { index, detail ->
            val prefix = "detail.$index."
            put(prefix + "kind", detail.kind.name)
            put(prefix + "id", detail.id)
            detail.collection?.let { collection ->
                put(prefix + "title", collection.title)
                put(prefix + "provider", collection.source.provider.name)
                put(prefix + "canonicalId", collection.source.canonicalId)
                put(prefix + "browseKind", collection.kind.name)
                put(prefix + "creator", collection.creator)
                collection.source.canonicalUrl?.let { put(prefix + "canonicalUrl", it) }
                collection.artworkUrl?.let { put(prefix + "artwork", it) }
            }
        }
        // Do not reopen transient player/queue overlays on a cold launch or start audio.
    }

    companion object {
        private const val MAX_DEPTH = 12

        fun restore(snapshot: AppSnapshot): AppNavigation {
            val values = snapshot.navigation
            fun <T : Enum<T>> enum(name: String, options: List<T>, default: T): T =
                options.firstOrNull { it.name == values[name] } ?: default
            val details = buildList {
                repeat((values["details"]?.toIntOrNull() ?: 0).coerceIn(0, MAX_DEPTH)) { index ->
                    val prefix = "detail.$index."
                    val id = values[prefix + "id"]?.takeIf { it.isNotBlank() } ?: return@repeat
                    val kind = DetailKind.entries.firstOrNull { it.name == values[prefix + "kind"] } ?: return@repeat
                    val collection = if (kind == DetailKind.COLLECTION) {
                        val provider = SourceProvider.entries.firstOrNull { it.name == values[prefix + "provider"] }
                            ?: return@repeat
                        val canonicalId = values[prefix + "canonicalId"]?.takeIf { it.isNotBlank() } ?: return@repeat
                        val title = values[prefix + "title"]?.takeIf { it.isNotBlank() } ?: return@repeat
                        val browse = BrowseKind.entries.firstOrNull { it.name == values[prefix + "browseKind"] }
                            ?: return@repeat
                        MediaCollection(id, title, SourceRef(provider, canonicalId, values[prefix + "canonicalUrl"]),
                            values[prefix + "artwork"], values[prefix + "creator"].orEmpty(), browse)
                    } else null
                    val exists = when (kind) {
                        DetailKind.TRACK -> snapshot.media[id]?.kind == MediaKind.MUSIC
                        DetailKind.EPISODE -> snapshot.media[id]?.kind == MediaKind.EPISODE
                        DetailKind.SHOW -> id in snapshot.shows
                        DetailKind.PLAYLIST -> snapshot.playlists.any { it.id == id }
                        DetailKind.COLLECTION -> collection != null
                        DetailKind.LICENSES -> true
                    }
                    if (exists) add(AppDetail(kind, id, collection))
                }
            }
            return AppNavigation(
                enum("screen", AppDestination.entries, AppDestination.LISTEN),
                enum("librarySection", LibrarySection.entries, LibrarySection.FAVORITES),
                details = details,
                viewedQueue = enum("viewedQueue", QueueContext.entries, snapshot.activeContext),
            )
        }
    }
}
