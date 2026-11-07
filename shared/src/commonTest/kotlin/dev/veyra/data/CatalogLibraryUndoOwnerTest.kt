package dev.veyra.data

import dev.veyra.acquisition.authoredMedia
import dev.veyra.data.room.*
import dev.veyra.model.*
import kotlin.test.*

/** Pure helper contracts only. These do not execute Room or establish backup/recovery success. */
class CatalogLibraryUndoOwnerTest {
    private fun saved(): AcquisitionGraph {
        val first = authoredMedia("first", kind = MediaKind.EPISODE)
        val second = authoredMedia("second", kind = MediaKind.EPISODE)
        return commandGraph().applyCatalogLibraryEdit(AppSnapshot(
            media = mapOf(first.id to first, second.id to second),
            favoriteIds = setOf(first.id, second.id), savedEpisodeIds = setOf(first.id, second.id),
            playlists = listOf(Playlist("one", "First", listOf(PlaylistEntry("a", first.id), PlaylistEntry("b", first.id))),
                Playlist("two", "Second", listOf(PlaylistEntry("c", second.id)))),
        )).withRevision(1)
    }

    @Test fun completeFrameEqualityDoesNotDependOnDaoIterationOrder() {
        val graph = saved()
        val reordered = graph.copy(library = graph.library.copy(
            playlists = graph.library.playlists.reversed(), playlistEntries = graph.library.playlistEntries.reversed(),
            favorites = graph.library.favorites.reversed(), savedEpisodes = graph.library.savedEpisodes.reversed(),
        ), rows = graph.rows.copy(favoriteSubjectRows = graph.rows.favoriteSubjectRows.reversed(),
            playlistMemberRows = graph.rows.playlistMemberRows.reversed()))
        val first = CatalogLibrarySavedFrame.of(graph)
        val second = CatalogLibrarySavedFrame.of(reordered)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertTrue(first.restore(reordered).sameRelations(graph))
    }

    @Test fun explicitPositionsAndCanonicalOnlyChangesArePartOfTheFullFrame() {
        val graph = saved()
        val moved = graph.copy(rows = graph.rows.copy(playlistMemberRows = graph.rows.playlistMemberRows.map {
            if (it.playlistId == "one") it.copy(position = 1 - it.position) else it
        }))
        assertNotEquals(CatalogLibrarySavedFrame.of(graph), CatalogLibrarySavedFrame.of(moved))
        val historical = graph.copy(rows = graph.rows.copy(favoriteSubjectRows = graph.rows.favoriteSubjectRows.map {
            it.copy(origin = SavedLibraryOrigin.SCHEMA2)
        }))
        assertEquals(graph.snapshot(), historical.snapshot())
        assertNotEquals(CatalogLibrarySavedFrame.of(graph), CatalogLibrarySavedFrame.of(historical))
    }

    @Test fun capturedFrameCopiesTheSourceRowCollections() {
        val graph = saved()
        val mutable = graph.library.playlists.toMutableList()
        val input = graph.copy(library = graph.library.copy(playlists = mutable))
        val frame = CatalogLibrarySavedFrame.of(input)
        mutable[0] = mutable[0].copy(name = "Changed after capture")
        assertEquals(CatalogLibrarySavedFrame.of(graph), frame)
        assertNotEquals(CatalogLibrarySavedFrame.of(input), frame)
        assertTrue(frame.restore(input).sameRelations(graph))
    }

    @Test fun forcedOpenedIncarnationRotatesEvenAtIdenticalRevisionAndSavedBytes() {
        val owner = CatalogLibraryUndoOwner()
        val before = commandGraph()
        val graph = saved()
        owner.publish(before, freshIncarnation = true)
        owner.publish(graph, committedFrom = before)
        val receipt = assertNotNull(owner.receipt(CatalogLibrarySavedFrame.of(before), graph))
        val initial = owner.availability.value
        owner.publish(graph)
        assertSame(initial, owner.availability.value)
        // publishOpened/recovery explicitly asks for this boundary; no real recovery is faked here.
        owner.publish(graph, freshIncarnation = true)
        assertNotSame(initial, owner.availability.value)
        assertFalse(owner.availability.value.accepts(receipt))
        assertFailsWith<IllegalArgumentException> { owner.restore(receipt, graph) }
    }

    @Test fun anUnpublishedProposalCannotIssueAReceiptAndInvalidationRejectsPriorOwnership() {
        val owner = CatalogLibraryUndoOwner()
        val before = commandGraph()
        val graph = saved()
        owner.publish(before, freshIncarnation = true)
        assertFailsWith<IllegalStateException> { owner.receipt(CatalogLibrarySavedFrame.of(before), graph) }
        owner.publish(graph, committedFrom = before)
        val receipt = assertNotNull(owner.receipt(CatalogLibrarySavedFrame.of(before), graph))
        owner.invalidate()
        assertFalse(owner.availability.value.accepts(receipt))
        assertFailsWith<IllegalArgumentException> { owner.restore(receipt, graph) }
    }
}
