package dev.veyra.data

import androidx.sqlite.execSQL
import dev.veyra.model.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RoomRecoveryTest {
    private suspend fun seed(directory: Path): AppSnapshot {
        seedSchema2(directory, completeLibrary())
        val store = createPlatformLibraryStore(directory.toString())
        return try { store.initialize().getOrThrow() } finally { store.close() }
    }

    @Test fun corruptPrimaryRequiresExplicitVerifiedRecoveryAndPreservesDamagedInput() = runBlocking {
        val directory = roomWorkDirectory("corrupt-recovery")
        val committed = seed(directory)
        val damaged = "authored damaged database; do not silently replace".encodeToByteArray()
        Files.write(directory.resolve("veyra.db"), damaged)
        val store = createPlatformLibraryStore(directory.toString())
        try {
            val failure = assertIs<StoreResult.Failure>(store.initialize())
            assertEquals(FailureCode.RECOVERY_REQUIRED, failure.failure.code)
            assertIs<StoreStatus.Failed>(store.status.value)
            assertContentEquals(damaged, Files.readAllBytes(directory.resolve("veyra.db")))
            assertEquals(committed, store.recoverFromBackup().getOrThrow())
            assertEquals(StoreStatus.Ready(recoveredFromBackup = true), store.status.value)
            val quarantine = Files.list(directory).use { paths -> paths.filter { it.fileName.toString().startsWith("veyra.corrupt-") && it.toString().endsWith(".db") }.toList() }
            assertEquals(1, quarantine.size)
            assertContentEquals(damaged, Files.readAllBytes(quarantine.single()))
        } finally { store.close() }
    }

    @Test fun missingInitializedDatabaseNeverLooksLikeFreshInstall() = runBlocking {
        val directory = roomWorkDirectory("missing-primary")
        val expected = seed(directory)
        Files.delete(directory.resolve("veyra.db"))
        val store = createPlatformLibraryStore(directory.toString())
        try {
            assertEquals(FailureCode.RECOVERY_REQUIRED, assertIs<StoreResult.Failure>(store.initialize()).failure.code)
            assertFalse(Files.exists(directory.resolve("veyra.db")))
            assertEquals(expected, store.recoverFromBackup().getOrThrow())
        } finally { store.close() }
    }

    @Test fun missingMetadataCannotSilentlyReseedAndEraseOtherNormalizedRows() = runBlocking {
        val directory = roomWorkDirectory("partial-seed")
        val expected = seed(directory)
        withSql(directory.resolve("veyra.db")) { it.execSQL("DELETE FROM store_meta") }
        val store = createPlatformLibraryStore(directory.toString())
        try {
            assertEquals(FailureCode.RECOVERY_REQUIRED, assertIs<StoreResult.Failure>(store.initialize()).failure.code)
            withSql(directory.resolve("veyra.db")) { db ->
                assertEquals(0L, db.longValue("SELECT count(*) FROM store_meta"))
                assertEquals(expected.artists.size.toLong(), db.longValue("SELECT count(*) FROM artists"))
                assertEquals(expected.media.size.toLong(), db.longValue("SELECT count(*) FROM media"))
            }
            assertEquals(expected, store.recoverFromBackup().getOrThrow())
        } finally { store.close() }
    }

    @Test fun missingSubtypeAndNoncontiguousOrderAreRejectedEvenWhenSqliteForeignKeysPass() = runBlocking {
        listOf("DELETE FROM tracks WHERE mediaId='music-1'",
            "UPDATE queue_entries SET position=19 WHERE id='music-occurrence-1'").forEachIndexed { index, corruption ->
            val directory = roomWorkDirectory("relational-corruption-$index")
            val expected = seed(directory)
            withSql(directory.resolve("veyra.db")) { db ->
                db.execSQL(corruption)
                assertFalse(db.prepare("PRAGMA foreign_key_check").use { it.step() })
            }
            val store = createPlatformLibraryStore(directory.toString())
            try {
                assertEquals(FailureCode.RECOVERY_REQUIRED, assertIs<StoreResult.Failure>(store.initialize()).failure.code)
                assertEquals(expected, store.recoverFromBackup().getOrThrow())
            } finally { store.close() }
        }
    }

    @Test fun futureSchemaRefusesBothOpenAndRecoveryDowngrade() = runBlocking {
        val directory = roomWorkDirectory("future-schema")
        seed(directory)
        withSql(directory.resolve("veyra.db")) { it.execSQL("PRAGMA user_version = 999") }
        val store = createPlatformLibraryStore(directory.toString())
        try {
            assertEquals(FailureCode.FUTURE_SCHEMA, assertIs<StoreResult.Failure>(store.initialize()).failure.code)
            assertEquals(FailureCode.FUTURE_SCHEMA, assertIs<StoreResult.Failure>(store.recoverFromBackup()).failure.code)
            withSql(directory.resolve("veyra.db")) { assertEquals(999L, it.longValue("PRAGMA user_version")) }
        } finally { store.close() }
    }

    @Test fun invalidBackupCannotTouchDamagedPrimaryOrPretendRecovery() = runBlocking {
        val directory = roomWorkDirectory("invalid-backup")
        seed(directory)
        val primary = "authored primary corruption".encodeToByteArray()
        Files.write(directory.resolve("veyra.db"), primary)
        Files.write(directory.resolve("veyra.backup.db"), "not sqlite".encodeToByteArray())
        val store = createPlatformLibraryStore(directory.toString())
        try {
            assertIs<StoreResult.Failure>(store.initialize())
            assertIs<StoreResult.Failure>(store.recoverFromBackup())
            assertContentEquals(primary, Files.readAllBytes(directory.resolve("veyra.db")))
            assertIs<StoreStatus.Failed>(store.status.value)
            assertFalse(Files.exists(directory.resolve("veyra.recovering")))
        } finally { store.close() }
    }

    @Test fun interruptedRecoveryMarkerRequiresExplicitRecoveryEvenForValidPrimary() = runBlocking {
        val directory = roomWorkDirectory("interrupted-recovery")
        val expected = seed(directory)
        Files.writeString(directory.resolve("veyra.recovering"), "authored interruption marker")
        val store = createPlatformLibraryStore(directory.toString())
        try {
            assertEquals(FailureCode.RECOVERY_REQUIRED, assertIs<StoreResult.Failure>(store.initialize()).failure.code)
            assertEquals(expected, store.recoverFromBackup().getOrThrow())
            assertFalse(Files.exists(directory.resolve("veyra.recovering")))
        } finally { store.close() }
    }

    @Test fun databaseLeasePreventsCompetingRecoveryAndIsReleasedOnClose() = runBlocking {
        val directory = roomWorkDirectory("lease")
        val first = createPlatformLibraryStore(directory.toString())
        val second = createPlatformLibraryStore(directory.toString())
        try {
            first.upsertCatalog(listOf(music())).getOrThrow()
            assertEquals(FailureCode.STORAGE_IO, assertIs<StoreResult.Failure>(second.initialize()).failure.code)
        } finally { second.close(); first.close() }
        val next = createPlatformLibraryStore(directory.toString())
        try { assertEquals(setOf("music-1"), next.initialize().getOrThrow().media.keys) } finally { next.close() }
    }

    @Test fun failedOpenRetainsLeaseUntilExplicitCloseAndThenCanRetryAfterRepair() = runBlocking {
        val directory = roomWorkDirectory("failed-open-lease")
        val expected = seed(directory)
        Files.writeString(directory.resolve("veyra.db"), "authored corruption")
        val first = createPlatformLibraryStore(directory.toString())
        val competing = createPlatformLibraryStore(directory.toString())
        try {
            assertIs<StoreResult.Failure>(first.initialize())
            assertEquals(FailureCode.STORAGE_IO, assertIs<StoreResult.Failure>(competing.initialize()).failure.code)
        } finally { competing.close(); first.close() }
        val retry = createPlatformLibraryStore(directory.toString())
        try {
            assertIs<StoreResult.Failure>(retry.initialize())
            assertEquals(expected, retry.recoverFromBackup().getOrThrow())
        } finally { retry.close() }
    }

    @Test fun explicitBackupIncludesCommittedWalAndCanBeRecoveredBeforeAnyCleanShutdown() = runBlocking {
        val directory = roomWorkDirectory("wal-backup")
        seedSchema2(directory, completeLibrary())
        val store = createPlatformLibraryStore(directory.toString())
        try {
            val expected = store.initialize().getOrThrow()
            store.backup().getOrThrow()
            val restoreDirectory = directory.resolve("independent-restore")
            Files.createDirectories(restoreDirectory)
            Files.copy(directory.resolve("veyra.backup.db"), restoreDirectory.resolve("veyra.db"))
            val restored = createPlatformLibraryStore(restoreDirectory.toString())
            try { assertEquals(expected, restored.initialize().getOrThrow()) } finally { restored.close() }
        } finally { store.close() }
    }
}
