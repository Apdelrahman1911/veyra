package dev.veyra.data.benchmark

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.veyra.data.longValue
import dev.veyra.data.roomWorkDirectory
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class RoomTransactionTimingDriverTest {
    @Test fun realImmediateTransactionRecordsBeginStepAndHoldWithoutTimingOrdinarySql() {
        val ticks = AtomicLong()
        val driver = RoomTransactionTimingDriver(BundledSQLiteDriver()) { ticks.addAndGet(10) }
        driver.open(path()).use { connection ->
            connection.execSQL("CREATE TABLE authored(id INTEGER PRIMARY KEY)")
            assertEquals(0L, ticks.get())
            connection.execSQL("BEGIN IMMEDIATE TRANSACTION")
            connection.execSQL("INSERT INTO authored VALUES(1)")
            assertEquals(30L, ticks.get())
            connection.execSQL("END TRANSACTION")
            val event = driver.snapshot().single()
            assertEquals(10L, event.preparedAt)
            assertEquals(20L, event.stepStartedAt)
            assertEquals(30L, event.acquiredAt)
            assertEquals(60L, event.finishedAt)
            assertEquals(10L, event.beginStepNs)
            assertEquals(30L, event.holdNs)
            assertEquals("COMMIT", event.outcome)
            assertEquals(1L, connection.longValue("SELECT count(*) FROM authored"))
        }
        assertEquals(1, driver.snapshot().size)
    }

    @Test fun deferredReadTransactionIsNotMislabeledAsImmediateWriterHoldingTime() {
        val driver = RoomTransactionTimingDriver(BundledSQLiteDriver())
        driver.open(path()).use { connection ->
            connection.execSQL("BEGIN DEFERRED TRANSACTION")
            connection.longValue("SELECT 1")
            connection.execSQL("END TRANSACTION")
        }
        assertTrue(driver.snapshot().isEmpty())
    }

    @Test fun deferredForeignKeyCommitFailureRetainsTheActiveTimingUntilExplicitRollback() {
        val driver = RoomTransactionTimingDriver(BundledSQLiteDriver())
        driver.open(path()).use { connection ->
            connection.execSQL("PRAGMA foreign_keys = ON")
            connection.execSQL("CREATE TABLE parent(id INTEGER PRIMARY KEY)")
            connection.execSQL("CREATE TABLE child(id INTEGER REFERENCES parent(id) DEFERRABLE INITIALLY DEFERRED)")
            connection.execSQL("BEGIN IMMEDIATE")
            try {
                connection.execSQL("INSERT INTO child VALUES(7)")
                assertFails { connection.execSQL("COMMIT") }
                assertTrue(connection.inTransaction())
                assertTrue(driver.snapshot().isEmpty())
                assertEquals(1, driver.failedEnds.get())
            } finally { if (connection.inTransaction()) connection.execSQL("ROLLBACK") }
            assertEquals("ROLLBACK", driver.snapshot().single().outcome)
            assertEquals(0L, connection.longValue("SELECT count(*) FROM child"))
        }
    }

    @Test fun rollbackToSavepointDoesNotRecordAnOuterTransactionRelease() {
        val driver = RoomTransactionTimingDriver(BundledSQLiteDriver())
        driver.open(path()).use { connection ->
            connection.execSQL("CREATE TABLE authored(id INTEGER)")
            connection.execSQL("BEGIN IMMEDIATE")
            connection.execSQL("SAVEPOINT 'authored'")
            connection.execSQL("INSERT INTO authored VALUES(1)")
            connection.execSQL("ROLLBACK TRANSACTION TO SAVEPOINT 'authored'")
            assertTrue(driver.snapshot().isEmpty())
            connection.execSQL("RELEASE SAVEPOINT 'authored'")
            connection.execSQL("COMMIT")
            assertEquals("COMMIT", driver.snapshot().single().outcome)
            assertEquals(0L, connection.longValue("SELECT count(*) FROM authored"))
        }
    }

    @Test fun failedNestedBeginDoesNotReplaceTheExistingTransactionInterval() {
        val driver = RoomTransactionTimingDriver(BundledSQLiteDriver())
        driver.open(path()).use { connection ->
            connection.execSQL("BEGIN IMMEDIATE")
            assertFails { connection.execSQL("BEGIN IMMEDIATE") }
            assertEquals(1, driver.failedBegins.get())
            assertTrue(driver.snapshot().isEmpty())
            connection.execSQL("ROLLBACK")
            assertEquals("ROLLBACK", driver.snapshot().single().outcome)
            assertEquals(0, driver.displacedTransactions.get())
        }
    }

    @Test fun closingAnOpenConnectionIsNotACommitReceipt() {
        val file = path()
        val driver = RoomTransactionTimingDriver(BundledSQLiteDriver())
        driver.open(file).use { connection ->
            connection.execSQL("CREATE TABLE authored(id INTEGER)")
            connection.execSQL("BEGIN IMMEDIATE")
            connection.execSQL("INSERT INTO authored VALUES(1)")
        }
        assertEquals("CONNECTION_CLOSED", driver.snapshot().single().outcome)
        BundledSQLiteDriver().open(file).use { assertEquals(0L, it.longValue("SELECT count(*) FROM authored")) }
    }

    @Test fun unchangedSqlAndDriverMetadataReachTheRealDelegate() {
        val sql = "SELECT  7 /* authored unchanged statement */"
        val prepared = mutableListOf<String>()
        val driver = RoomTransactionTimingDriver(object : SQLiteDriver {
            override val hasConnectionPool = true
            override fun open(fileName: String): SQLiteConnection {
                val real = BundledSQLiteDriver().open(fileName)
                return object : SQLiteConnection by real {
                    override fun prepare(sql: String): SQLiteStatement { prepared += sql; return real.prepare(sql) }
                }
            }
        })
        assertTrue(driver.hasConnectionPool)
        driver.open(path()).use { assertEquals(7L, it.longValue(sql)) }
        assertEquals(listOf(sql), prepared)
    }

    @Test fun connectionIdentitiesRemainDistinct() {
        val driver = RoomTransactionTimingDriver(BundledSQLiteDriver())
        for (index in 0..1) driver.open(path()).use { connection ->
            connection.execSQL("BEGIN IMMEDIATE")
            connection.execSQL("ROLLBACK")
        }
        assertEquals(2, driver.snapshot().map { it.connection }.distinct().size)
    }

    @Test fun failedCloseIsCountedWithoutInventingAnEndedTransaction() {
        val real = BundledSQLiteDriver().open(path())
        val driver = RoomTransactionTimingDriver(object : SQLiteDriver {
            override fun open(fileName: String) = object : SQLiteConnection by real {
                override fun close() { error("authored_close_failure") }
            }
        })
        val connection = driver.open("authored")
        try {
            connection.execSQL("BEGIN IMMEDIATE")
            assertFailsWith<IllegalStateException> { connection.close() }
            assertEquals(1, driver.failedCloses.get())
            assertTrue(driver.snapshot().isEmpty())
            connection.execSQL("ROLLBACK")
            assertEquals("ROLLBACK", driver.snapshot().single().outcome)
        } finally { real.close() }
    }

    private fun path(): String = roomWorkDirectory("transaction-timing").resolve("authored.db").toString()
}
