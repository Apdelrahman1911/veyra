package dev.veyra.data.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL

/**
 * Configure the actual connection before Room can start creation or migration transactions.
 * Room's generated onOpen callback runs too late to enable foreign keys for a migration.
 * This is configuration-before-exposure, not ownership of Room's later open/migration failures.
 * This changes no SQL, transaction ownership, journal mode or synchronous policy.
 */
internal class VeyraSQLiteDriver(
    private val delegate: SQLiteDriver = BundledSQLiteDriver(),
) : SQLiteDriver by delegate {
    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        try {
            connection.execSQL("PRAGMA foreign_keys = ON")
            connection.prepare("PRAGMA foreign_keys").use { statement ->
                check(statement.step() && statement.getLong(0) == 1L) {
                    "foreign_key_enforcement_unavailable"
                }
            }
            return connection
        } catch (failure: Throwable) {
            try { connection.close() }
            catch (closeFailure: Throwable) {
                if (closeFailure !== failure) failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }
}
