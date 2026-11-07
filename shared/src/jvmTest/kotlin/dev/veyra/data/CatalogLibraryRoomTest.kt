package dev.veyra.data

import androidx.sqlite.execSQL
import dev.veyra.data.room.*
import dev.veyra.model.*
import kotlin.test.*

/** Authored metadata and real Room only; no source query, native audio or downloaded bytes. */
class CatalogLibraryRoomTest {
    @Test fun freshCatalogueSavesHaveHonestNullLineageAndSurviveReopen() = catalogLibraryRoomCase {
        val track = music().copy(localArtworkPath = "authored/cache/track.jpg")
        val episode = MediaItem("episode", "عنوان الحلقة الكامل", MediaKind.EPISODE,
            SourceRef(SourceProvider.RSS, "authored-guid", episodeGuid = "authored-guid"))
        val committed = store.editCatalogLibrary { old -> old.copy(
            media = mapOf(track.id to track, episode.id to episode), favoriteIds = setOf(track.id),
            savedEpisodeIds = setOf(episode.id), playlists = listOf(Playlist("list", "قائمتي",
                listOf(PlaylistEntry("first", track.id), PlaylistEntry("duplicate", track.id)), 10, 20)),
        ) }
        assertNotNull(committed.undo)
        val before = graph()
        val favorite = before.rows.favoriteSubjectRows.single()
        assertEquals(FavoriteSubjectEntity(track.id, SavedLibraryOrigin.CATALOG, null, null, null, null), favorite)
        assertEquals(listOf("first", "duplicate"), before.rows.playlistMemberRows.sortedBy { it.position }.map { it.id })
        before.rows.playlistMemberRows.forEach {
            assertEquals(SavedLibraryOrigin.CATALOG, it.origin)
            assertNull(it.requestId); assertNull(it.bindingId); assertNull(it.occurrenceId); assertNull(it.controlsMode)
        }
        assertTrue(before.rows.acquisitionRequestRows.isEmpty())
        assertTrue(before.rows.firstResultReceiptRows.isEmpty())
        assertTrue(before.rows.executionBindingRows.isEmpty())
        assertTrue(before.rows.executionOccurrenceRows.isEmpty())
        assertTrue(before.library.downloads.isEmpty())
        withSql(path) { sql ->
            assertEquals("CATALOG", sql.textValue("SELECT origin FROM favorite_subjects"))
            assertEquals(2L, sql.longValue("SELECT count(*) FROM playlist_members WHERE origin='CATALOG'"))
            assertFalse(sql.prepare("PRAGMA foreign_key_check").use { it.step() })
        }
        store.close()
        val reopened = facade().store
        assertEquals(committed.snapshot, reopened.initialize().getOrThrow())
        assertTrue(before.sameRelations(graph(reopened)))
    }

    @Test fun existingScalarHelpersUseTheExplicitPathAndKeepDuplicateEntryIdentity() = catalogLibraryRoomCase {
        val item = music()
        store.toggleFavorite(item).getOrThrow()
        store.createPlaylist(" First ", "list", 1).getOrThrow()
        store.addToPlaylist("list", item, 2).getOrThrow()
        store.addToPlaylist("list", item, 3).getOrThrow()
        val entries = store.state.value.playlists.single().entries
        assertEquals(2, entries.map { it.id }.toSet().size)
        store.movePlaylistEntry("list", entries.first().id, 1).getOrThrow()
        assertEquals(entries.reversed(), store.state.value.playlists.single().entries)
        store.renamePlaylist("list", " Renamed — العربية ", 4).getOrThrow()
        store.removeFromPlaylist("list", entries.last().id).getOrThrow()
        val rows = graph().rows.playlistMemberRows
        assertEquals(listOf(entries.first().id), rows.map { it.id })
        assertEquals(0, rows.single().position)
        assertEquals("Renamed — العربية", store.state.value.playlists.single().name)
        withSql(path) { sql ->
            listOf("playlists", "playlist_entries", "playlist_members").forEach { table ->
                assertEquals(0L, sql.longValue("SELECT count(*) FROM $table WHERE position < 0"))
            }
        }
        store.deletePlaylist("list").getOrThrow()
        store.toggleFavorite(item).getOrThrow()
        val emptySaved = graph()
        assertTrue(emptySaved.library.playlists.isEmpty()); assertTrue(emptySaved.library.playlistEntries.isEmpty())
        assertTrue(emptySaved.rows.playlistMemberRows.isEmpty()); assertTrue(emptySaved.rows.favoriteSubjectRows.isEmpty())
        assertTrue(emptySaved.rows.firstResultReceiptRows.isEmpty())
    }

    @Test fun scalarSavedEpisodeAndRetainedArtworkRemainMetadataOnly() = catalogLibraryRoomCase {
        val item = MediaItem("episode", "Complete episode title", MediaKind.EPISODE,
            SourceRef(SourceProvider.RSS, "guid"), localArtworkPath = "authored/cache/episode.jpg")
        store.upsertCatalog(listOf(item)).getOrThrow()
        store.toggleSavedEpisode(item.copy(localArtworkPath = null)).getOrThrow()
        assertEquals(setOf(item.id), store.state.value.savedEpisodeIds)
        assertEquals(item.localArtworkPath, store.state.value.media.getValue(item.id).localArtworkPath)
        store.toggleSavedEpisode(item).getOrThrow()
        assertTrue(store.state.value.savedEpisodeIds.isEmpty())
        assertTrue(graph().rows.firstResultReceiptRows.isEmpty())
    }

    @Test fun legacySnapshotSavedIngressRemainsRejectedOnRealRoom() = catalogLibraryRoomCase {
        val item = music()
        store.upsertCatalog(listOf(item)).getOrThrow()
        val before = graph()
        assertEquals(FailureCode.INVALID_STATE, assertIs<StoreResult.Failure>(store.update {
            it.copy(favoriteIds = setOf(item.id))
        }).failure.code)
        assertEquals(FailureCode.INVALID_STATE, assertIs<StoreResult.Failure>(store.update {
            it.copy(playlists = listOf(Playlist("list", "Cannot bypass canonical save")))
        }).failure.code)
        assertUnchanged(before)
        assertNotNull(store.editCatalogLibrary { it.copy(favoriteIds = setOf(item.id)) }.undo)
    }

    @Test fun exactAllowlistRejectsExecutionPhysicalAndUnrelatedProjectionWrites() = catalogLibraryRoomCase {
        val item = music()
        store.upsertCatalog(listOf(item)).getOrThrow()
        val before = graph()
        val changes: List<(AppSnapshot) -> AppSnapshot> = listOf(
            { it.copy(schemaVersion = it.schemaVersion + 1) },
            { it.copy(revision = it.revision + 1) },
            { it.copy(activeContext = QueueContext.PODCAST) },
            { it.copy(settings = it.settings.copy(offlineOnly = true)) },
            { it.copy(navigation = mapOf("destination" to "Library")) },
            { it.copy(artists = mapOf("artist" to Artist("artist", "Unrelated artist"))) },
            { it.copy(albums = mapOf("album" to Album("album", "Unrelated album"))) },
            { it.copy(shows = mapOf("show" to PodcastShow("show", "Unrelated show", "https://example.invalid/feed"))) },
            { it.copy(followedShowIds = setOf("show")) },
            { it.copy(queues = it.queues + (QueueContext.MUSIC to QueueSnapshot(listOf(QueueEntry("q", item.id)), "q"))) },
            { it.copy(history = listOf(PlaybackHistoryEntry("h", item.id, 1, 0, false))) },
            { it.copy(progress = mapOf(item.id to MediaProgress(positionMs = 10))) },
            { it.copy(playbackBindings = mapOf("b" to binding(item, "b"))) },
            { it.copy(downloads = mapOf("d" to DownloadRecord("d", item.id))) },
            { it.copy(downloadAttempts = mapOf("a" to DownloadAttempt("a", "d", 1, DownloadAttemptStatus.RUNNING, 1))) },
            { it.copy(downloadFinalizations = mapOf("d" to DownloadFinalization("d", item.id,
                "authored/part", "authored/final", "a".repeat(64), 10))) },
        )
        changes.forEach { change ->
            assertFailsWith<IllegalArgumentException> { store.editCatalogLibrary(change) }
            assertUnchanged(before)
        }
    }

    @Test fun catalogueIdentityDeletionAndPlaylistEntryReassignmentFailWithoutPublishing() = catalogLibraryRoomCase {
        val first = music(); val second = music("second")
        store.editCatalogLibrary { it.copy(media = mapOf(first.id to first, second.id to second),
            playlists = listOf(Playlist("a", "First", listOf(PlaylistEntry("stable", first.id))), Playlist("b", "Second"))) }
        val before = graph()
        val bad: List<(AppSnapshot) -> AppSnapshot> = listOf(
            { it.copy(media = it.media - second.id) },
            { it.copy(media = it.media + (first.id to first.copy(source = first.source.copy(canonicalId = "different")))) },
            { it.copy(media = it.media + (first.id to first.copy(kind = MediaKind.EPISODE))) },
            { it.copy(playlists = listOf(it.playlists[0].copy(entries = listOf(PlaylistEntry("stable", second.id))), it.playlists[1])) },
            { it.copy(playlists = listOf(it.playlists[0].copy(entries = emptyList()), it.playlists[1].copy(entries = listOf(PlaylistEntry("stable", first.id))))) },
        )
        bad.forEach { change -> assertFailsWith<IllegalArgumentException> { store.editCatalogLibrary(change) }; assertUnchanged(before) }
    }

    @Test fun hiddenNeutralSavedRowsSurviveRenameAndIndependentCatalogueSaves() = catalogLibraryRoomCase {
        val neutral = selected("neutral", AlbumCapture(Album("edition", "Complete release title")), PlaybackControlsMode.PODCAST)
        store.createPlaylist("Hidden members", "hidden").getOrThrow()
        installTypedSaved(neutral, listOf(neutral.member("hidden-member", "hidden", 0)))
        val typedBefore = graph().rows
        assertTrue(store.state.value.favoriteIds.isEmpty())
        assertTrue(store.state.value.playlists.single().entries.isEmpty())
        store.renamePlaylist("hidden", "Still contains neutral member").getOrThrow()
        store.toggleFavorite(music()).getOrThrow()
        val after = graph()
        assertEquals(typedBefore.playlistMemberRows, after.rows.playlistMemberRows)
        assertEquals(typedBefore.favoriteSubjectRows.single(), after.rows.favoriteSubjectRows.single { it.subjectId == neutral.subject.value })
        assertEquals(setOf(music().id), after.snapshot().favoriteIds)
        assertTrue(after.snapshot().media.keys.none { it == neutral.subject.value })
        assertEquals(typedBefore.firstResultReceiptRows, after.rows.firstResultReceiptRows)
    }

    @Test fun revealingAnExistingHiddenCatalogueFavoritePreservesItsTypedRowAndUndoHidesOnlyTheProjection() = catalogLibraryRoomCase {
        val selection = selected()
        val item = assertIs<MediaCapture>(selection.request.metadata).media
        installTypedSaved(selection, emptyList())
        val before = graph()
        assertTrue(before.snapshot().favoriteIds.isEmpty())
        assertEquals(selection.favorite(), before.rows.favoriteSubjectRows.single())
        val receipt = assertNotNull(store.editCatalogLibrary { it.copy(favoriteIds = setOf(item.id)) }.undo)
        val projected = graph()
        assertEquals(before.rows.favoriteSubjectRows, projected.rows.favoriteSubjectRows)
        assertEquals(setOf(item.id), projected.snapshot().favoriteIds)
        store.undoCatalogLibrary(receipt)
        val undone = graph()
        assertEquals(CatalogLibrarySavedFrame.of(before), CatalogLibrarySavedFrame.of(undone))
        assertOnlySavedRelationsChanged(projected, undone)
    }

    @Test fun lossyHiddenPlaylistMembershipEditsAndDeletionAreRejected() = catalogLibraryRoomCase {
        val item = music()
        store.editCatalogLibrary { it.copy(media = mapOf(item.id to item),
            playlists = listOf(Playlist("list", "Mixed", listOf(PlaylistEntry("visible", item.id))))) }
        val neutral = selected("hidden", AlbumCapture(Album("edition", "Genuine parent")))
        installTypedSaved(neutral, listOf(neutral.member("hidden", "list", 1)))
        val before = graph()
        val bad: List<(AppSnapshot) -> AppSnapshot> = listOf(
            { it.copy(playlists = emptyList()) },
            { it.copy(playlists = it.playlists.map { p -> p.copy(entries = emptyList()) }) },
            { it.copy(playlists = it.playlists.map { p -> p.copy(entries = p.entries + PlaylistEntry("new", item.id)) }) },
            { it.copy(playlists = it.playlists.map { p -> p.copy(entries = listOf(PlaylistEntry("hidden", item.id))) }) },
        )
        bad.forEach { change -> assertFailsWith<IllegalArgumentException> { store.editCatalogLibrary(change) }; assertUnchanged(before) }
    }

    @Test fun typedVisibleOccurrencesKeepExactLineageThroughReorderUndoAndReopen() = catalogLibraryRoomCase {
        val selected = selected()
        val item = assertIs<MediaCapture>(selected.request.metadata).media
        store.editCatalogLibrary { it.copy(favoriteIds = setOf(item.id), playlists = listOf(Playlist("list", "Typed saved",
            listOf(PlaylistEntry("first", item.id), PlaylistEntry("second", item.id))))) }
        installTypedSaved(selected, listOf(selected.member("first", "list", 0), selected.member("second", "list", 1)))
        val before = graph()
        val edit = store.editCatalogLibrary { it.copy(playlists = it.playlists.map { p -> p.copy(entries = p.entries.reversed()) }) }
        val reordered = graph()
        before.rows.playlistMemberRows.forEach { old ->
            val next = reordered.rows.playlistMemberRows.single { it.id == old.id }
            assertEquals(old, next.copy(position = old.position))
        }
        assertEquals(before.rows.favoriteSubjectRows, reordered.rows.favoriteSubjectRows)
        store.undoCatalogLibrary(assertNotNull(edit.undo))
        val undone = graph()
        assertEquals(CatalogLibrarySavedFrame.of(before), CatalogLibrarySavedFrame.of(undone))
        assertOnlySavedRelationsChanged(reordered, undone)
        store.close()
        val reopened = facade().store
        reopened.initialize().getOrThrow()
        assertTrue(undone.sameRelations(graph(reopened)))
    }

    @Test fun catalogueOriginCannotReferToNeutralSubjectsOrExecutionLineage() = catalogLibraryRoomCase {
        val selected = selected("neutral", AlbumCapture(Album("edition", "Full parent")))
        val item = music()
        store.upsertCatalog(listOf(item)).getOrThrow()
        val before = graph()
        val invalid = listOf(
            FavoriteSubjectEntity(selected.subject.value, SavedLibraryOrigin.CATALOG, null, null, null, null),
            FavoriteSubjectEntity(item.id, SavedLibraryOrigin.CATALOG, selected.request.id.value, null, null, null),
            FavoriteSubjectEntity(item.id, SavedLibraryOrigin.CATALOG, null, selected.binding.value, null, null),
            FavoriteSubjectEntity(item.id, SavedLibraryOrigin.CATALOG, null, null, selected.occurrence.id.value, null),
            FavoriteSubjectEntity(item.id, SavedLibraryOrigin.CATALOG, null, null, null, PlaybackControlsMode.MUSIC),
        )
        invalid.forEach { saved ->
            assertFailsWith<IllegalArgumentException> { store.transactAcquisition { graph ->
                AcquisitionMutation(graph.copy(rows = graph.rows.copy(favoriteSubjectRows = listOf(saved)))) { Unit }
            } }
            assertUnchanged(before)
        }
    }

    @Test fun projectedCanonicalMismatchIsNotRepairedByManufacturingNewOrigins() = catalogLibraryRoomCase {
        val item = music()
        store.toggleFavorite(item).getOrThrow()
        // Authored inconsistent mirror; present graph validation does not manufacture a repair.
        store.transactAcquisition { graph -> AcquisitionMutation(graph.copy(rows = graph.rows.copy(favoriteSubjectRows = emptyList()))) { Unit } }
        val before = graph()
        assertFailsWith<IllegalArgumentException> { store.editCatalogLibrary { it.copy(favoriteIds = emptySet()) } }
        assertUnchanged(before)
    }

    @Test fun ignoredNormalizedInsertRollsBackLegacyProjectionMediaAndRevision() = catalogLibraryRoomCase {
        val item = music()
        val before = graph()
        withSql(path) { it.execSQL("CREATE TRIGGER ignore_saved BEFORE INSERT ON favorite_subjects BEGIN SELECT RAISE(IGNORE); END") }
        try {
            assertFailsWith<IllegalStateException> { store.editCatalogLibrary {
                it.copy(media = mapOf(item.id to item), favoriteIds = setOf(item.id))
            } }
            assertUnchanged(before)
            withSql(path) {
                assertEquals(0L, it.longValue("SELECT count(*) FROM favorites"))
                assertEquals(0L, it.longValue("SELECT count(*) FROM favorite_subjects"))
                assertEquals(0L, it.longValue("SELECT count(*) FROM media"))
            }
        } finally { withSql(path) { it.execSQL("DROP TRIGGER ignore_saved") } }
        assertNotNull(store.editCatalogLibrary { it.copy(media = mapOf(item.id to item), favoriteIds = setOf(item.id)) }.undo)
    }
}
