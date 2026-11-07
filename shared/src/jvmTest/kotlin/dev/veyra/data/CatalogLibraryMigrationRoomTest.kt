package dev.veyra.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.veyra.data.room.*
import dev.veyra.model.*
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.test.*

/** Exported schema2 -> actual migration3, then actual generated converters and real Room. */
class CatalogLibraryMigrationRoomTest {
    @Test fun schema2SavedRowsCoexistWithCatalogueRowsThroughReorderUndoAndReopen(): Unit = runBlocking {
        val path = roomWorkDirectory("catalogue-saved-migration").resolve("library.db")
        withCatalogMigrationHelper(path) { helper ->
            helper.createDatabase(2).use { sql -> sql.savedSeedTransaction() }
            helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3)).use { sql ->
                sql.assertCatalogSchemaIntegrity()
                assertEquals(2L, sql.longValue("SELECT count(*) FROM favorite_subjects WHERE origin='SCHEMA2'"))
                assertEquals(2L, sql.longValue("SELECT count(*) FROM playlist_members WHERE origin='SCHEMA2'"))
                listOf("favorite_subjects", "playlist_members").forEach { table ->
                    assertEquals(0L, sql.longValue("SELECT count(*) FROM $table WHERE requestId IS NOT NULL OR bindingId IS NOT NULL OR occurrenceId IS NOT NULL OR controlsMode IS NOT NULL"))
                }
            }
        }
        val fixture = CatalogLibraryRoomFixture(path)
        try {
            withTimeout(30_000) {
                val store = fixture.store
                assertEquals(17L, store.initialize().getOrThrow().revision)
                val legacy = fixture.graph()
                assertEquals(listOf("legacy-first", "legacy-duplicate"), legacy.snapshot().playlists.single().entries.map { it.id })
                assertEquals(setOf("legacy-episode"), legacy.snapshot().savedEpisodeIds)
                legacy.rows.favoriteSubjectRows.forEach { assertEquals(SavedLibraryOrigin.SCHEMA2, it.origin) }
                legacy.rows.playlistMemberRows.forEach { assertEquals(SavedLibraryOrigin.SCHEMA2, it.origin) }
                val item = music("fresh-catalogue")
                val newSave = store.editCatalogLibrary { old -> old.copy(media = old.media + (item.id to item),
                    favoriteIds = old.favoriteIds + item.id, playlists = old.playlists.map { playlist ->
                        playlist.copy(entries = playlist.entries + PlaylistEntry("new-entry", item.id))
                    }) }
                val mixed = fixture.graph()
                legacy.rows.favoriteSubjectRows.forEach { old -> assertEquals(old, mixed.rows.favoriteSubjectRows.single { it.subjectId == old.subjectId }) }
                legacy.rows.playlistMemberRows.forEach { old -> assertEquals(old, mixed.rows.playlistMemberRows.single { it.id == old.id }) }
                assertEquals(SavedLibraryOrigin.CATALOG, mixed.rows.favoriteSubjectRows.single { it.subjectId == item.id }.origin)
                assertEquals(SavedLibraryOrigin.CATALOG, mixed.rows.playlistMemberRows.single { it.id == "new-entry" }.origin)
                val reorder = store.editCatalogLibrary { old -> old.copy(playlists = old.playlists.map {
                    it.copy(entries = it.entries.reversed())
                }) }
                assertEquals(listOf("new-entry", "legacy-duplicate", "legacy-first"), store.state.value.playlists.single().entries.map { it.id })
                store.undoCatalogLibrary(assertNotNull(reorder.undo))
                val undone = fixture.graph()
                assertEquals(CatalogLibrarySavedFrame.of(mixed), CatalogLibrarySavedFrame.of(undone))
                assertFalse(store.catalogLibraryUndoAvailability.value.accepts(assertNotNull(newSave.undo)),
                    "An intervening saved edit + Undo must not revive an older receipt")
                assertTrue(undone.rows.acquisitionRequestRows.isEmpty())
                assertTrue(undone.rows.firstResultReceiptRows.isEmpty())
                assertTrue(undone.rows.executionBindingRows.isEmpty())
                store.close()
                val reopened = fixture.facade().store
                reopened.initialize().getOrThrow()
                assertTrue(undone.sameRelations(fixture.graph(reopened)))
                withSql(path) { sql ->
                    sql.assertCatalogSchemaIntegrity()
                    assertEquals("SCHEMA2", sql.textValue("SELECT origin FROM favorite_subjects WHERE subjectId='legacy-track'"))
                    assertEquals("CATALOG", sql.textValue("SELECT origin FROM favorite_subjects WHERE subjectId='fresh-catalogue'"))
                    assertEquals(0L, sql.longValue("SELECT count(*) FROM playlist_members WHERE position < 0"))
                    assertEquals(0L, sql.longValue("SELECT count(*) FROM playlist_entries WHERE position < 0"))
                }
            }
        } finally { withContext(NonCancellable) { fixture.close() } }
    }

    @Test fun unknownFavoriteOriginFailsOpenWithoutResetOrRowDeletion(): Unit = runBlocking {
        withTimeout(30_000) { rejectUnknownOrigin("favorite_subjects", "subjectId", "catalogue") }
    }

    @Test fun unknownPlaylistMemberOriginFailsOpenWithoutResetOrRowDeletion(): Unit = runBlocking {
        withTimeout(30_000) { rejectUnknownOrigin("playlist_members", "id", "entry") }
    }

    private suspend fun rejectUnknownOrigin(table: String, key: String, id: String) {
        val fixture = CatalogLibraryRoomFixture()
        try {
            fixture.store.initialize().getOrThrow()
            val item = music("catalogue")
            fixture.store.editCatalogLibrary { it.copy(media = mapOf(item.id to item), favoriteIds = setOf(item.id),
                playlists = listOf(Playlist("list", "Saved before incompatible row", listOf(PlaylistEntry("entry", item.id))))) }
            val before = fixture.store.state.value
            fixture.store.close()
            withSql(fixture.path) { sql ->
                // Deliberate incompatible persisted enum fixture; never a production write route.
                sql.execSQL("UPDATE $table SET origin='FUTURE_SAVED_ORIGIN' WHERE $key='$id'")
            }
            val rejected = fixture.facade().store
            assertEquals(FailureCode.RECOVERY_REQUIRED, assertIs<StoreResult.Failure>(rejected.initialize()).failure.code)
            assertIs<StoreStatus.Failed>(rejected.status.value)
            rejected.close()
            withSql(fixture.path) { sql ->
                assertEquals("FUTURE_SAVED_ORIGIN", sql.textValue("SELECT origin FROM $table WHERE $key='$id'"))
                assertEquals(before.revision, sql.longValue("SELECT revision FROM store_meta WHERE id=0"))
                listOf("media", "favorites", "favorite_subjects", "playlists", "playlist_entries", "playlist_members").forEach {
                    assertEquals(1L, sql.longValue("SELECT count(*) FROM $it"), "Failed open must not clear $it")
                }
                sql.assertCatalogSchemaIntegrity()
            }
        } finally { withContext(NonCancellable) { fixture.close() } }
    }
}

private fun SQLiteConnection.assertCatalogSchemaIntegrity() {
    assertEquals(1L, longValue("PRAGMA foreign_keys"))
    assertEquals(3L, longValue("PRAGMA user_version"))
    assertEquals("ok", textValue("PRAGMA integrity_check"))
    assertFalse(prepare("PRAGMA foreign_key_check").use { it.step() })
    // No DDL/version change is expected for two saved-only TEXT enum converters.
    assertEquals("a51a13de7a6c06c5b3ff5f9890c7ddd2", textValue("SELECT identity_hash FROM room_master_table WHERE id=42"))
}

private fun SQLiteConnection.savedSeedTransaction() {
    execSQL("BEGIN IMMEDIATE")
    try {
        listOf("MUSIC", "PODCAST").forEach { context ->
            execSQL("INSERT INTO queue_contexts (id,positionMs,shuffle,repeat,speed,hasOriginalOrder) VALUES ('$context',0,0,'OFF',1.0,0)")
        }
        execSQL("INSERT INTO store_meta (id,revision,activeContext) VALUES (0,17,'PODCAST')")
        execSQL("INSERT INTO settings (id,language,theme,textScale,offlineOnly,speed,skipBackSeconds,skipForwardSeconds,downloadParallelism) VALUES (0,'ARABIC','DARK',1.0,0,1.0,10,30,2)")
        execSQL("INSERT INTO media (id,title,kind,creator,description,explicit,source_provider,source_canonicalId) VALUES ('legacy-track','عنوان أغنية محفوظة','MUSIC','Authored creator','Authored metadata',0,'COMMONS','authored-track'),('legacy-episode','Complete saved episode','EPISODE','Authored creator','Authored metadata',0,'RSS','authored-guid')")
        execSQL("INSERT INTO tracks (mediaId) VALUES ('legacy-track')")
        execSQL("INSERT INTO episodes (mediaId) VALUES ('legacy-episode')")
        execSQL("INSERT INTO favorites (mediaId) VALUES ('legacy-track'),('legacy-episode')")
        execSQL("INSERT INTO saved_episodes (episodeId) VALUES ('legacy-episode')")
        execSQL("INSERT INTO playlists (id,name,createdEpochMs,updatedEpochMs,position) VALUES ('legacy-list','قائمة محفوظة',10,20,0)")
        execSQL("INSERT INTO playlist_entries (id,playlistId,mediaId,position) VALUES ('legacy-first','legacy-list','legacy-track',0),('legacy-duplicate','legacy-list','legacy-track',1)")
        execSQL("COMMIT")
    } catch (failure: Throwable) {
        try { execSQL("ROLLBACK") } catch (rollback: Throwable) { if (rollback !== failure) failure.addSuppressed(rollback) }
        throw failure
    }
}

/** MigrationTestHelper owns successful handles only; also close raw handles from failed opens. */
private fun withCatalogMigrationHelper(path: Path, block: (MigrationTestHelper) -> Unit) {
    val opened = mutableListOf<SQLiteConnection>()
    val real = BundledSQLiteDriver()
    val driver = VeyraSQLiteDriver(object : SQLiteDriver by real {
        override fun open(fileName: String) = real.open(fileName).also(opened::add)
    })
    val owner = object : AutoCloseable {
        override fun close() {
            var failure: Throwable? = null
            opened.asReversed().forEach { connection ->
                try { connection.close() } catch (error: Throwable) {
                    val previous = failure
                    if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }
    owner.use {
        block(MigrationTestHelper(schemaDirectoryPath = projectRoot().resolve("shared/schemas"), databasePath = path,
            driver = driver, databaseClass = VeyraDatabase::class, databaseFactory = VeyraDatabaseConstructor::initialize))
    }
}
