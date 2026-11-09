package dev.veyra.downloads

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Download-integrity port support. Real SQLite, one IMMEDIATE transaction and a real revision
 * bump: this is a deliberately narrow CORRUPTION PROBE for canonical immutable rows, not a
 * second write authority, a legal typed update, or a production persistence seam.
 *
 * Typed history cannot legally rewrite a receipt/binding/finalization under the same ID, so the
 * two "changed value under the same ID" integrity regressions must model an external corrupting
 * writer. Every mutation keeps the remaining canonical graph internally readable except for the
 * explicitly labelled graph-guard probes in the test; no helper normalizes an error or invents a
 * Ready/receipt state. Nothing here is used by production code.
 */
internal enum class PersistedMaterializationField(val column: String) {
    REPRESENTATION("representationId"),
    VALIDATOR_GENERATION("validatorGeneration"),
    OWNERSHIP_GENERATION("ownershipGeneration"),
    MATERIALIZER_ATTEMPT("materializerAttemptId"),
}

internal enum class PersistedFinalizationField(val column: String) {
    REPRESENTATION("representationId"),
    VALIDATOR_GENERATION("validatorGeneration"),
    ASSET_ID("assetId"),
}

/** Replace one live asset identity AND its immutable receipt copy in the same real transaction. */
internal fun corruptMaterialization(databasePath: Path, assetId: String,
    field: PersistedMaterializationField, value: Any?): Long = mutateCanonicalRows(databasePath) { connection ->
    connection.boundUpdate("UPDATE offline_assets SET ${field.column} = ? WHERE id = ?", value, assetId)
    connection.boundUpdate("UPDATE materialization_receipts SET ${field.column} = ? WHERE id = ?", value, assetId)
}

/** Replace one durable finalization link; the surrounding canonical graph still validates. */
internal fun corruptFinalization(databasePath: Path, downloadId: String,
    field: PersistedFinalizationField, value: Any?): Long = mutateCanonicalRows(databasePath) { connection ->
    connection.boundUpdate("UPDATE download_finalizations SET ${field.column} = ? WHERE downloadId = ?", value, downloadId)
}

/**
 * Earlier canonical graph guard probes for legacy-only receipt shapes. These deliberately make
 * the whole graph read fail closed instead of pretending a typed materialization path ran.
 */
internal fun corruptTypedReceiptRawKind(databasePath: Path, bindingId: String, kind: String): Long =
    mutateCanonicalRows(databasePath) { connection ->
        connection.boundUpdate("UPDATE first_result_receipts SET rawKind = ? WHERE id = " +
            "(SELECT firstReceiptId FROM execution_bindings WHERE id = ?)", kind, bindingId)
    }

internal fun corruptTypedReceiptRawId(databasePath: Path, bindingId: String): Long =
    mutateCanonicalRows(databasePath) { connection ->
        connection.boundUpdate("UPDATE first_result_receipts SET rawId = NULL WHERE id = " +
            "(SELECT firstReceiptId FROM execution_bindings WHERE id = ?)", bindingId)
    }

internal fun corruptTypedBindingRecording(databasePath: Path, bindingId: String): Long =
    mutateCanonicalRows(databasePath) { connection ->
        connection.boundUpdate("UPDATE execution_bindings SET recordingId = NULL WHERE id = ? AND origin = 'TYPED'", bindingId)
    }

internal fun corruptFinalizationBinding(databasePath: Path, downloadId: String, bindingId: String): Long =
    mutateCanonicalRows(databasePath) { connection ->
        connection.boundUpdate("UPDATE download_finalizations SET executionBindingId = ? WHERE downloadId = ?", bindingId, downloadId)
    }

/** Replace both the live asset producer binding and its immutable receipt copy. */
internal fun corruptMaterializationBinding(databasePath: Path, assetId: String, bindingId: String): Long =
    mutateCanonicalRows(databasePath) { connection ->
        connection.boundUpdate("UPDATE offline_assets SET executionBindingId = ? WHERE id = ?", bindingId, assetId)
        connection.boundUpdate("UPDATE materialization_receipts SET executionBindingId = ? WHERE id = ?", bindingId, assetId)
    }

/** One real SQLite writer transaction with the canonical revision advanced exactly like a writer. */
internal fun mutateCanonicalRows(databasePath: Path, block: (SQLiteConnection) -> Unit): Long {
    var revision = -1L
    BundledSQLiteDriver().open(databasePath.toString()).use { connection ->
        connection.execSQL("PRAGMA busy_timeout = 5000")
        connection.execSQL("PRAGMA foreign_keys = ON")
        connection.execSQL("BEGIN IMMEDIATE")
        try {
            block(connection)
            connection.execSQL("UPDATE store_meta SET revision = revision + 1 WHERE id = 0")
            connection.prepare("SELECT revision FROM store_meta WHERE id = 0").use { statement ->
                assertTrue(statement.step(), "Authored corruption requires the canonical store_meta row")
                revision = statement.getLong(0)
            }
            connection.execSQL("COMMIT")
        } catch (failure: Throwable) {
            connection.execSQL("ROLLBACK")
            throw failure
        }
    }
    assertTrue(revision >= 0, "Authored corruption did not observe the committed canonical revision")
    return revision
}

private fun SQLiteConnection.boundUpdate(sql: String, vararg values: Any?) {
    prepare(sql).use { statement ->
        values.forEachIndexed { index, value ->
            when (value) {
                null -> statement.bindNull(index + 1)
                is String -> statement.bindText(index + 1, value)
                is Long -> statement.bindLong(index + 1, value)
                is Int -> statement.bindLong(index + 1, value.toLong())
                else -> error("Unsupported authored canonical corruption scalar")
            }
        }
        statement.step()
    }
}
