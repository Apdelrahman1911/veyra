package dev.veyra.data.room

import dev.veyra.data.validateLibraryTransition
import dev.veyra.model.*

/** Full saved relations, including hidden typed rows. Map equality retains explicit positions. */
internal data class CatalogLibrarySavedFrame private constructor(
    private val playlists: Map<String, PlaylistEntity>,
    private val entries: Map<String, PlaylistEntryEntity>,
    private val favorites: Map<String, FavoriteEntity>,
    private val savedEpisodes: Map<String, SavedEpisodeEntity>,
    private val favoriteSubjects: Map<String, FavoriteSubjectEntity>,
    private val members: Map<String, PlaylistMemberEntity>,
) {
    fun restore(graph: AcquisitionGraph): AcquisitionGraph = graph.copy(
        library = graph.library.copy(
            playlists = playlists.values.toList(), playlistEntries = entries.values.toList(),
            favorites = favorites.values.toList(), savedEpisodes = savedEpisodes.values.toList(),
        ),
        rows = graph.rows.copy(favoriteSubjectRows = favoriteSubjects.values.toList(), playlistMemberRows = members.values.toList()),
    )

    companion object {
        fun of(graph: AcquisitionGraph): CatalogLibrarySavedFrame = CatalogLibrarySavedFrame(
            graph.library.playlists.rowIndex { it.id }, graph.library.playlistEntries.rowIndex { it.id },
            graph.library.favorites.rowIndex { it.mediaId }, graph.library.savedEpisodes.rowIndex { it.episodeId },
            graph.rows.favoriteSubjectRows.rowIndex { it.subjectId }, graph.rows.playlistMemberRows.rowIndex { it.id },
        )
    }
}

/**
 * Explicit metadata-only save ingress. This deliberately does NOT widen applyCatalogSnapshot:
 * arbitrary snapshot writes still cannot mint saved lineage, execution or physical authority.
 */
internal fun AcquisitionGraph.applyCatalogLibraryEdit(proposed: AppSnapshot): AcquisitionGraph {
    val previous = snapshot()
    require(proposed.copy(media = previous.media, favoriteIds = previous.favoriteIds,
        savedEpisodeIds = previous.savedEpisodeIds, playlists = previous.playlists) == previous) {
        "Catalogue-library edit changed fields outside its metadata/saved allowlist"
    }
    require(proposed.media.keys.containsAll(previous.media.keys)) { "Saved edits cannot delete catalogue identities" }
    validateLibraryTransition(previous, proposed)

    // Existing scalar metadata ingress already preserves immutable catalogue subject identities.
    val catalog = applyCatalogSnapshot(previous.copy(media = proposed.media))
    val subjects = catalog.subjects()
    fun catalogSubject(mediaId: String): String {
        val subject = subjects[ExecutableSubjectId(mediaId)]
        require(subject is ExecutableSubject.CatalogMedia && subject.mediaId == mediaId) {
            "A catalogue save requires a genuine catalogue media subject"
        }
        return subject.id.value
    }

    val favorites = rows.favoriteSubjectRows.rowIndex { it.subjectId }
    previous.favoriteIds.forEach { id ->
        require(favorites[catalogSubject(id)] != null) { "Projected favorite is missing its canonical saved row" }
    }
    val removedFavorites = previous.favoriteIds - proposed.favoriteIds
    val nextFavorites = favorites.toMutableMap()
    removedFavorites.forEach { nextFavorites.remove(catalogSubject(it)) }
    proposed.favoriteIds.forEach { id ->
        val subject = catalogSubject(id)
        // Preserve an existing typed/schema2 row even when it was not projected previously.
        if (subject !in nextFavorites) nextFavorites[subject] = FavoriteSubjectEntity(
            subject, SavedLibraryOrigin.CATALOG, null, null, null, null,
        )
    }

    val oldPlaylists = previous.playlists.associateBy { it.id }
    val newPlaylists = proposed.playlists.associateBy { it.id }
    val oldMembers = rows.playlistMemberRows.rowIndex { it.id }
    val byPlaylist = rows.playlistMemberRows.groupBy { it.playlistId }
    previous.playlists.forEach { playlist ->
        val projected = playlist.entries.map { it.id }.toSet()
        val canonical = byPlaylist[playlist.id].orEmpty()
        playlist.entries.forEach { entry ->
            val saved = requireNotNull(oldMembers[entry.id]) { "Projected entry is missing its canonical saved row" }
            require(saved.playlistId == playlist.id && saved.subjectId == catalogSubject(entry.mediaId)) {
                "Projected entry identity differs from its saved lineage"
            }
        }
        require(canonical.filter { it.id in projected }.sortedBy { it.position }.map { it.id } == playlist.entries.map { it.id }) {
            "Projected entry order differs from its canonical occurrence order"
        }
        if (canonical.any { it.id !in projected }) {
            require(newPlaylists[playlist.id]?.entries == playlist.entries) {
                "Catalogue projection cannot edit/delete a playlist containing hidden saved members"
            }
        }
    }

    val nextMembers = proposed.playlists.flatMap { playlist ->
        if (oldPlaylists[playlist.id]?.entries == playlist.entries) byPlaylist[playlist.id].orEmpty()
        else playlist.entries.mapIndexed { position, entry ->
            val subject = catalogSubject(entry.mediaId)
            val old = oldMembers[entry.id]
            if (old == null) PlaylistMemberEntity(entry.id, playlist.id, subject, SavedLibraryOrigin.CATALOG,
                null, null, null, null, position)
            else {
                // Entry IDs are never recycled across a parent/subject, including hidden rows.
                require(old.playlistId == playlist.id && old.subjectId == subject &&
                    oldPlaylists[playlist.id]?.entries?.any { it.id == entry.id } == true) {
                    "Saved playlist occurrence identity cannot be reassigned"
                }
                old.copy(position = position)
            }
        }
    }

    return catalog.copy(
        library = catalog.library.copy(
            favorites = proposed.favoriteIds.map(::FavoriteEntity),
            savedEpisodes = proposed.savedEpisodeIds.map(::SavedEpisodeEntity),
            playlists = proposed.playlists.mapIndexed { index, playlist ->
                PlaylistEntity(playlist.id, playlist.name, playlist.createdEpochMs, playlist.updatedEpochMs, index)
            },
            playlistEntries = proposed.playlists.flatMap { playlist -> playlist.entries.mapIndexed { index, entry ->
                PlaylistEntryEntity(entry.id, playlist.id, entry.mediaId, index)
            } },
        ),
        rows = catalog.rows.copy(favoriteSubjectRows = nextFavorites.values.toList(), playlistMemberRows = nextMembers),
    )
}
