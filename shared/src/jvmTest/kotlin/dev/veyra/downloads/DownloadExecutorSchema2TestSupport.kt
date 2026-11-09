package dev.veyra.downloads

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.veyra.data.room.*
import dev.veyra.data.projectRoot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

internal data class DownloadExecutorUnknownSchema2(val mediaId: String, val jobId: String, val path: String)
internal val EXECUTOR_UNKNOWN_BYTES = "Veyra authored unknown historical bytes — ليست مادة صوتية".encodeToByteArray()

/** Exported SCHEMA2 ONLY, before Room exists. No schema3 request/binding/claim or receipt is seeded. */
internal fun DownloadExecutorSchema3Fixture.unknownSchema2(): DownloadExecutorUnknownSchema2 {
    check(!Files.exists(databasePath))
    val media = "executor-unknown-schema2-media"
    val job = "executor-unknown-schema2-job"
    val part = files.prepareStaging(media, "authored-unknown-history").toString()
    Files.write(Path.of(part), EXECUTOR_UNKNOWN_BYTES)
    executorSchema2Helper(databasePath) { helper -> helper.createDatabase(2).use { db ->
        db.execSQL("BEGIN IMMEDIATE")
        try {
            listOf("MUSIC", "PODCAST").forEach { context -> db.executorInsert2("queue_contexts", mapOf(
                "id" to context, "positionMs" to 0L, "shuffle" to false, "repeat" to "OFF", "speed" to 1.0, "hasOriginalOrder" to false)) }
            db.executorInsert2("store_meta", mapOf("id" to 0, "revision" to 7L, "activeContext" to "MUSIC"))
            db.executorInsert2("settings", mapOf("id" to 0, "language" to "ARABIC", "theme" to "DARK", "textScale" to 1.0,
                "offlineOnly" to false, "speed" to 1.0, "skipBackSeconds" to 15, "skipForwardSeconds" to 30,
                "crossfadeEnabled" to false, "crossfadeDurationMs" to 3000L, "downloadParallelism" to 1))
            db.executorInsert2("media", mapOf("id" to media, "title" to "Complete current catalogue title — العنوان الحالي الكامل",
                "kind" to "MUSIC", "creator" to "Authored discovery creator", "description" to "Authored metadata only", "explicit" to false,
                "source_provider" to "COMMONS", "source_canonicalId" to "authored-unknown-schema2-catalog"))
            db.executorInsert2("tracks", mapOf("mediaId" to media))
            db.executorInsert2("downloads", mapOf("id" to job, "mediaId" to media, "status" to "QUEUED",
                "bytesDownloaded" to EXECUTOR_UNKNOWN_BYTES.size.toLong(), "totalBytes" to null, "stagingPath" to part,
                "representationId" to null, "validatorGeneration" to 0L, "updatedEpochMs" to 2_000L,
                "executionBindingId" to null, "attemptCount" to 0, "priority" to 0, "createdEpochMs" to 1_000L,
                "userPaused" to false, "automaticRetryCount" to 0))
            db.execSQL("COMMIT")
        } catch (failure: Throwable) {
            try { db.execSQL("ROLLBACK") } catch (rollback: Throwable) { if (rollback !== failure) failure.addSuppressed(rollback) }
            throw failure
        }
    } }
    return DownloadExecutorUnknownSchema2(media, job, part)
}

private fun executorSchema2Helper(path: Path, block: (MigrationTestHelper) -> Unit) {
    val opened = mutableListOf<SQLiteConnection>()
    val real = BundledSQLiteDriver()
    val driver = VeyraSQLiteDriver(object : SQLiteDriver by real {
        override fun open(fileName: String) = real.open(fileName).also(opened::add)
    })
    val owner = object : AutoCloseable {
        override fun close() {
            var primary: Throwable? = null
            opened.asReversed().forEach { connection ->
                try { connection.close() } catch (failure: Throwable) {
                    val previous = primary
                    if (previous == null) primary = failure else if (previous !== failure) previous.addSuppressed(failure)
                }
            }
            primary?.let { throw it }
        }
    }
    owner.use { block(MigrationTestHelper(schemaDirectoryPath = projectRoot().resolve("shared/schemas"), databasePath = path,
        driver = driver, databaseClass = VeyraDatabase::class, databaseFactory = VeyraDatabaseConstructor::initialize)) }
}

private fun SQLiteConnection.executorInsert2(table: String, values: Map<String, Any?>) {
    val fields = values.entries.toList()
    prepare("INSERT INTO `$table` (${fields.joinToString(",") { "`${it.key}`" }}) VALUES (${fields.joinToString(",") { "?" }})").use { statement ->
        fields.forEachIndexed { index, entry -> when (val value = entry.value) {
            null -> statement.bindNull(index + 1)
            is String -> statement.bindText(index + 1, value)
            is Boolean -> statement.bindLong(index + 1, if (value) 1L else 0L)
            is Int -> statement.bindLong(index + 1, value.toLong())
            is Long -> statement.bindLong(index + 1, value)
            is Double -> statement.bindDouble(index + 1, value)
            else -> error("Unsupported authored exported-schema2 scalar")
        } }
        statement.step()
    }
}
