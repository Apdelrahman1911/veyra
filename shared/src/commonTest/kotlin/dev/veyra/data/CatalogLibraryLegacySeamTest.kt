package dev.veyra.data

import dev.veyra.acquisition.authoredMedia
import dev.veyra.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Explicitly projection-only compatibility tests, not schema3/SQLite evidence. */
class CatalogLibraryLegacySeamTest {
    @Test fun existingScalarHelpersRetainTheirLegacyProjectionOnlyTestBehavior() = runTest {
        val persistence = CatalogLegacyProjectionPersistence()
        val store = LibraryStore(persistence, StandardTestDispatcher(testScheduler))
        try {
            val item = authoredMedia()
            val episode = authoredMedia("episode", kind = MediaKind.EPISODE)
            store.initialize().getOrThrow()
            assertFalse(store.requiresTypedPlayback)
            store.toggleFavorite(item).getOrThrow()
            store.toggleSavedEpisode(episode).getOrThrow()
            store.createPlaylist(" First ", "list").getOrThrow()
            store.addToPlaylist("list", item).getOrThrow()
            store.addToPlaylist("list", item).getOrThrow()
            val entries = store.state.value.playlists.single().entries
            store.movePlaylistEntry("list", entries.first().id, 1).getOrThrow()
            assertEquals(entries.reversed(), store.state.value.playlists.single().entries)
            store.renamePlaylist("list", " Renamed ").getOrThrow()
            store.removeFromPlaylist("list", entries.last().id).getOrThrow()
            assertEquals(listOf(entries.first()), store.state.value.playlists.single().entries)
            assertEquals("Renamed", store.state.value.playlists.single().name)
            store.deletePlaylist("list").getOrThrow()
            store.toggleFavorite(item).getOrThrow()
            store.toggleSavedEpisode(episode).getOrThrow()
            assertTrue(store.state.value.playlists.isEmpty())
            assertTrue(store.state.value.favoriteIds.isEmpty())
            assertTrue(store.state.value.savedEpisodeIds.isEmpty())
        } finally { store.close() }
    }

    @Test fun explicitCatalogueEditCannotSilentlyFallBackToALegacyOnlyBackend() = runTest {
        val persistence = CatalogLegacyProjectionPersistence()
        val store = LibraryStore(persistence, StandardTestDispatcher(testScheduler))
        try {
            store.initialize().getOrThrow()
            val before = store.state.value
            val failure = assertFailsWith<VeyraException> { store.editCatalogLibrary { it } }
            assertEquals(FailureCode.INVALID_STATE, failure.failure.code)
            assertEquals(0, persistence.transactions)
            assertEquals(before, store.state.value)
        } finally { store.close() }
    }
}

private class CatalogLegacyProjectionPersistence : LibraryPersistence {
    private var snapshot = AppSnapshot()
    var transactions = 0
        private set
    override suspend fun open() = OpenedLibrary(snapshot)
    override suspend fun read() = snapshot
    override suspend fun transact(expectedRevision: Long?, transform: (AppSnapshot) -> AppSnapshot): AppSnapshot {
        transactions++
        require(expectedRevision == null || expectedRevision == snapshot.revision)
        val next = transform(snapshot)
        validateLibraryTransition(snapshot, next)
        snapshot = if (next == snapshot) snapshot else next.copy(revision = snapshot.revision + 1)
        return snapshot
    }
    override fun changes(): Flow<Unit> = emptyFlow()
    override suspend fun backup() = Unit
    override suspend fun recoverFromBackup(): OpenedLibrary = error("Projection-only fixture has no recovery")
    override suspend fun close() = Unit
}
