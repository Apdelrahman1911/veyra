package dev.veyra.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.veyra.data.room.MIGRATION_1_2
import dev.veyra.data.room.VeyraDatabase
import dev.veyra.data.room.VeyraDatabaseConstructor
import dev.veyra.data.room.VeyraSQLiteDriver
import dev.veyra.data.room.buildVeyraDatabase
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.*

class VeyraSQLiteDriverTest {
    @Test fun everyBundledConnectionIsConfiguredBeforeItIsReturned() {
        val path = roomWorkDirectory("preopen-fk").resolve("driver.db").toString()
        val driver = VeyraSQLiteDriver()
        driver.open(path).use { first ->
            driver.open(path).use { second ->
                assertEquals(1L, first.longValue("PRAGMA foreign_keys"))
                assertEquals(1L, second.longValue("PRAGMA foreign_keys"))
                assertFalse(first.inTransaction())
                assertFalse(second.inTransaction())
            }
        }
    }

    @Test fun successfulConfigurationReturnsTheSameConnectionWithoutInterceptingSql() {
        val connection = BundledSQLiteDriver().open(roomWorkDirectory("same-handle").resolve("driver.db").toString())
        val driver = VeyraSQLiteDriver(object : SQLiteDriver {
            override fun open(fileName: String) = connection
        })
        driver.open("unused-by-authored-delegate").use {
            assertSame(connection, it)
            it.execSQL("CREATE TABLE authored(value INTEGER NOT NULL)")
            it.execSQL("INSERT INTO authored VALUES(7)")
            assertEquals(7L, it.longValue("SELECT value FROM authored"))
        }
    }

    @Test fun failedEnableClosesOnceAndPreservesTheOriginalError() {
        val failure = IllegalStateException("authored_enable_failure")
        configurationFailure(enableFailure = failure) { driver, closes ->
            assertSame(failure, assertFailsWith<IllegalStateException> { driver.open("authored") })
            assertEquals(1, closes())
        }
    }

    @Test fun disabledReadbackClosesInsteadOfExposingAnUnprotectedConnection() {
        configurationFailure(readbackSql = "SELECT 0") { driver, closes ->
            assertEquals("foreign_key_enforcement_unavailable",
                assertFailsWith<IllegalStateException> { driver.open("authored") }.message)
            assertEquals(1, closes())
        }
    }

    @Test fun missingReadbackClosesInsteadOfAssumingThePragmaWorked() {
        configurationFailure(readbackSql = "SELECT 1 WHERE 0") { driver, closes ->
            assertFailsWith<IllegalStateException> { driver.open("authored") }
            assertEquals(1, closes())
        }
    }

    @Test fun closeFailureIsSuppressedWithoutReplacingConfigurationFailure() {
        val failure = IllegalStateException("authored_enable_failure")
        val closeFailure = IllegalArgumentException("authored_close_failure")
        configurationFailure(enableFailure = failure, closeFailure = closeFailure) { driver, closes ->
            assertSame(failure, assertFailsWith<IllegalStateException> { driver.open("authored") })
            assertEquals(listOf(closeFailure), failure.suppressedExceptions)
            assertEquals(1, closes())
        }
    }

    @Test fun delegateOpenFailureHasNoConnectionForTheWrapperToClose() {
        val failure = IllegalStateException("authored_open_failure")
        var attempts = 0
        val driver = VeyraSQLiteDriver(object : SQLiteDriver {
            override fun open(fileName: String): SQLiteConnection { attempts++; throw failure }
        })
        assertSame(failure, assertFailsWith<IllegalStateException> { driver.open("authored") })
        assertEquals(1, attempts)
    }

    @Test fun delegateConnectionPoolMetadataIsPreserved() {
        val driver = VeyraSQLiteDriver(object : SQLiteDriver {
            override val hasConnectionPool = true
            override fun open(fileName: String): SQLiteConnection = error("not_opened")
        })
        assertTrue(driver.hasConnectionPool)
    }

    @Test fun freshProductionBuilderHasForeignKeysBeforeTheRoomCreateCallback() = runBlocking {
        var callbackInvoked = false
        val database = buildVeyraDatabase(Room.databaseBuilder<VeyraDatabase>(
            roomWorkDirectory("production-create-fk").resolve("veyra.db").toString(),
            factory = VeyraDatabaseConstructor::initialize,
        ).addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(connection: SQLiteConnection) {
                assertEquals(1L, connection.longValue("PRAGMA foreign_keys"))
                assertTrue(connection.inTransaction())
                callbackInvoked = true
            }
        }))
        val store = LibraryStore(database)
        try { store.initialize().getOrThrow(); assertTrue(callbackInvoked) }
        finally { store.close() }
    }

    @Test fun realRoomMigrationSeesForeignKeysInsideItsTransactionBeforeOnOpen() {
        val path = roomWorkDirectory("migration-entry-fk").resolve("veyra.db")
        withTrackedHelper(path) { helper ->
            helper.createDatabase(1).use { it.execSQL("INSERT INTO artists(id,name,sortName,description) VALUES('kept','Artist','Artist','authored')") }
            var called = false
            val checkedMigration = object : Migration(1, 2) {
                override fun migrate(connection: SQLiteConnection) {
                    assertEquals(1L, connection.longValue("PRAGMA foreign_keys"))
                    assertTrue(connection.inTransaction())
                    called = true
                    MIGRATION_1_2.migrate(connection)
                    assertFalse(connection.prepare("PRAGMA foreign_key_check").use { it.step() })
                }
            }
            helper.runMigrationsAndValidate(2, listOf(checkedMigration)).use {
                assertTrue(called)
                assertEquals(2L, it.longValue("PRAGMA user_version"))
                assertEquals("kept", it.textValue("SELECT id FROM artists"))
                assertEquals(1L, it.longValue("PRAGMA foreign_keys"))
            }
        }
    }

    @Test fun preCommitIntegrityFailureRollsBackMigrationSchemaAndData() {
        val path = roomWorkDirectory("migration-fk-rollback").resolve("veyra.db")
        withTrackedHelper(path) { helper ->
            helper.createDatabase(1).use { it.execSQL("INSERT INTO artists(id,name,sortName,description) VALUES('kept','Artist','Artist','authored')") }
            val invalid = object : Migration(1, 2) {
                override fun migrate(connection: SQLiteConnection) {
                    assertEquals(1L, connection.longValue("PRAGMA foreign_keys"))
                    MIGRATION_1_2.migrate(connection)
                    // Deferred constraints allow this statement, but never the migrated graph.
                    connection.execSQL("INSERT INTO favorites(mediaId) VALUES('missing-media')")
                    check(!connection.prepare("PRAGMA foreign_key_check").use { it.step() }) {
                        "authored_precommit_integrity_failure"
                    }
                }
            }
            assertEquals("authored_precommit_integrity_failure",
                assertFailsWith<IllegalStateException> { helper.runMigrationsAndValidate(2, listOf(invalid)).close() }.message)
        }
        withSql(path) {
            assertEquals(1L, it.longValue("PRAGMA user_version"))
            assertEquals("kept", it.textValue("SELECT id FROM artists"))
            assertEquals(0L, it.longValue("SELECT count(*) FROM favorites"))
            assertEquals(0L, it.longValue("SELECT count(*) FROM pragma_table_info('settings') WHERE name='crossfadeEnabled'"))
        }
    }

    @Test fun realDeferredConstraintRejectsCommitAndExplicitRollbackPreservesTheDatabase() {
        val path = roomWorkDirectory("driver-deferred-fk").resolve("driver.db")
        VeyraSQLiteDriver().open(path.toString()).use {
            it.execSQL("CREATE TABLE parent(id INTEGER PRIMARY KEY)")
            it.execSQL("CREATE TABLE child(id INTEGER PRIMARY KEY, parentId INTEGER REFERENCES parent(id) DEFERRABLE INITIALLY DEFERRED)")
            it.execSQL("BEGIN IMMEDIATE")
            try {
                it.execSQL("INSERT INTO parent VALUES(1)")
                it.execSQL("INSERT INTO child VALUES(1,2)")
                assertFails { it.execSQL("COMMIT") }
                // A failed COMMIT is not an automatic rollback or an ownership-drain receipt.
                assertTrue(it.inTransaction())
            } finally { if (it.inTransaction()) it.execSQL("ROLLBACK") }
            assertEquals(0L, it.longValue("SELECT count(*) FROM parent"))
            assertEquals(0L, it.longValue("SELECT count(*) FROM child"))
        }
    }

    private fun configurationFailure(
        enableFailure: Throwable? = null,
        readbackSql: String? = null,
        closeFailure: Throwable? = null,
        block: (VeyraSQLiteDriver, () -> Int) -> Unit,
    ) {
        val raw = BundledSQLiteDriver().open(roomWorkDirectory("driver-config-fault").resolve("driver.db").toString())
        var closes = 0
        val fault = object : SQLiteConnection by raw {
            override fun prepare(sql: String): SQLiteStatement {
                if (sql == "PRAGMA foreign_keys = ON" && enableFailure != null) throw enableFailure
                return raw.prepare(if (sql == "PRAGMA foreign_keys" && readbackSql != null) readbackSql else sql)
            }
            override fun close() { closes++; raw.close(); if (closeFailure != null) throw closeFailure }
        }
        val driver = VeyraSQLiteDriver(object : SQLiteDriver { override fun open(fileName: String) = fault })
        try { block(driver) { closes } } finally { raw.close() }
    }

    private fun withTrackedHelper(path: Path, block: (MigrationTestHelper) -> Unit) {
        val opened = mutableListOf<SQLiteConnection>()
        val driver = VeyraSQLiteDriver(object : SQLiteDriver {
            private val real = BundledSQLiteDriver()
            override fun open(fileName: String) = real.open(fileName).also(opened::add)
        })
        val helper = MigrationTestHelper(projectRoot().resolve("shared/schemas"), path, driver,
            VeyraDatabase::class, databaseFactory = VeyraDatabaseConstructor::initialize)
        try { block(helper) }
        finally {
            // Room2.8.5's helper open-failure path does not promise to close an opened handle.
            // This is test ownership cleanup, not evidence of production migration auto-drain.
            opened.asReversed().forEach(SQLiteConnection::close)
        }
    }
}
