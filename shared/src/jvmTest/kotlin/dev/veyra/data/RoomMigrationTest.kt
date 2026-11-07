package dev.veyra.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.veyra.data.room.MIGRATION_1_2
import dev.veyra.data.room.VeyraDatabase
import dev.veyra.data.room.VeyraDatabaseConstructor
import dev.veyra.data.room.VeyraSQLiteDriver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Schema 1 is an explicitly authored, unshipped baseline fixture; schema 2 is the KSP export. */
class RoomMigrationTest {
    private fun withTrackedHelper(path: Path, block: (MigrationTestHelper) -> Unit) {
        val opened = mutableListOf<SQLiteConnection>()
        val real = BundledSQLiteDriver()
        val driver = VeyraSQLiteDriver(object : SQLiteDriver by real {
            override fun open(fileName: String) = real.open(fileName).also(opened::add)
        })
        val owner = object : AutoCloseable {
            override fun close() {
                var failure: Throwable? = null
                opened.asReversed().forEach { connection ->
                    try { connection.close() }
                    catch (error: Throwable) {
                        val previous = failure
                        if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
                    }
                }
                failure?.let { throw it }
            }
        }
        // Room2.8.5 registers only successfully returned helper connections. A failed
        // open/migration can still own a raw handle. This finally owner is test-only;
        // SQLiteConnection.close is a no-op for the successful handles already closed by use.
        // use also preserves a body failure if cleanup fails, after attempting every handle.
        owner.use {
            block(MigrationTestHelper(
                schemaDirectoryPath = projectRoot().resolve("shared/schemas"), databasePath = path,
                driver = driver, databaseClass = VeyraDatabase::class,
                databaseFactory = VeyraDatabaseConstructor::initialize,
            ))
        }
    }

    @Test fun migrationPreservesEveryNormalizedTableAndAppliesSafeDefaults() = runBlocking {
        val directory = roomWorkDirectory("migration")
        val sourceDirectory = directory.resolve("current")
        val source = createPlatformLibraryStore(sourceDirectory.toString())
        val committed = try { source.update { completeLibrary() }.getOrThrow() } finally { source.close() }
        val targetDirectory = directory.resolve("baseline")
        Files.createDirectories(targetDirectory)
        val path = targetDirectory.resolve("veyra.db")
        withTrackedHelper(path) { helper ->
            helper.createDatabase(1).use { baseline ->
                val sourcePath = sourceDirectory.resolve("veyra.db").toString().replace("'", "''")
                baseline.execSQL("ATTACH DATABASE '$sourcePath' AS fixture_source")
                baseline.execSQL("BEGIN IMMEDIATE")
                try {
                    val schema = Json.parseToJsonElement(Files.readString(projectRoot().resolve("shared/schemas/dev.veyra.data.room.VeyraDatabase/1.json")))
                    val entities = schema.jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
                    entities.forEach { element ->
                        val entity = element.jsonObject
                        val table = entity.getValue("tableName").jsonPrimitive.content
                        val columns = entity.getValue("fields").jsonArray.joinToString(",") { "`${it.jsonObject.getValue("columnName").jsonPrimitive.content}`" }
                        baseline.execSQL("INSERT INTO `$table` ($columns) SELECT $columns FROM fixture_source.`$table`")
                    }
                    baseline.execSQL("COMMIT")
                } catch (error: Exception) { baseline.execSQL("ROLLBACK"); throw error }
                baseline.execSQL("DETACH DATABASE fixture_source")
                assertEquals(1L, baseline.longValue("PRAGMA user_version"))
            }
            helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2)).use { migrated ->
                assertEquals(2L, migrated.longValue("PRAGMA user_version"))
                assertEquals(0L, migrated.longValue("SELECT crossfadeEnabled FROM settings"))
                assertEquals(3000L, migrated.longValue("SELECT crossfadeDurationMs FROM settings"))
                assertEquals(0L, migrated.longValue("SELECT sum(cleanupPending + removeWhenClean) FROM downloads"))
                assertFalse(migrated.prepare("PRAGMA foreign_key_check").use { it.step() })
            }
        }
        val reopened = createPlatformLibraryStore(targetDirectory.toString())
        try {
            val expected = committed.copy(settings = committed.settings.copy(crossfadeEnabled = false, crossfadeDurationMs = 3000),
                downloads = committed.downloads.mapValues { (_, d) -> d.copy(cleanupPending = false, removeWhenClean = false) })
            assertEquals(expected, reopened.initialize().getOrThrow())
        } finally { reopened.close() }
    }

    @Test fun missingMigrationFailsWithoutErasingBaselineData() {
        val path = roomWorkDirectory("missing-migration").resolve("veyra.db")
        withTrackedHelper(path) { helper ->
            helper.createDatabase(1).use { it.execSQL("INSERT INTO artists(id,name,sortName,description) VALUES('retained','Artist','Artist','authored baseline')") }
            assertFailsWith<IllegalStateException> { helper.runMigrationsAndValidate(2, emptyList()).close() }
        }
        withSql(path) { db ->
            assertEquals(1L, db.longValue("PRAGMA user_version"))
            assertEquals("retained", db.textValue("SELECT id FROM artists"))
        }
    }
}
