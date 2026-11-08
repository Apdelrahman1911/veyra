package dev.veyra.app

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import dev.veyra.model.Attribution
import dev.veyra.model.FirstResultKind
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.PlaybackBinding
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Authored schema-2 history for the external-resolver lifetime controls.
 *
 * The database file is created from the REAL exported schema-2 JSON: every exported table,
 * index and `setupQueries` entry is executed with the verified `${TABLE_NAME}` substitution,
 * `PRAGMA user_version` is set to 2, and only baseline/context/settings/catalog/track/binding
 * rows are authored. Room then opens that file through the production `LibraryStore`, so
 * `MIGRATION_2_3` genuinely executes. There is no raw schema-3 insert, no completed/offline
 * asset, no download/attempt/finalization, no queue occurrence and no source I/O here.
 *
 * The single VIDEO binding is a complete historical receipt (ordinal 0, canonical locator).
 * The current catalogue title intentionally differs from the historical query title: a reopen
 * must not recapture today's metadata over the preserved full query.
 */
internal object AppContainerSchema2Fixture {
    const val MEDIA_ID = "authored-schema2-history-media"
    const val CURRENT_MEDIA_TITLE = "Current corrected schema2 metadata — العنوان الحديث"
    const val HISTORICAL_QUERY_TITLE = "Historical complete requested title — العنوان التاريخي"
    const val HISTORICAL_VIDEO_ID = "Schema2Vid1"
    const val HISTORICAL_VIDEO_TITLE = "Historical first recording"
    const val CANONICAL_VIDEO_URL = "https://www.youtube.com/watch?v=Schema2Vid1"
    const val BINDING_ID = "authored-schema2-history-binding"
    const val CATALOG_CANONICAL_ID = "authored-schema2-catalog-id"
    const val CATALOG_URL = "https://commons.example.org/authored-schema2"
    const val RECORDING_ID = "schema2:youtube:Schema2Vid1"
    const val STORE_REVISION = 7L
    const val RESOLVED_EPOCH_MS = 900L

    // Mutable application state (store revision, settings, navigation) is deliberately not
    // compared as historical scalars. The preserved receipt/media/track/binding rows are.
    private val seedTables = listOf(
        "queue_contexts", "media", "tracks", "playback_bindings",
    )

    private val zeroRowTables = listOf(
        "acquisition_requests", "acquisition_outcomes", "acquisition_jobs",
        "execution_occurrences", "queue_members", "queue_heads", "occurrence_checkpoints",
        "downloads", "download_attempts", "download_finalizations", "offline_assets",
        "download_claims", "download_batches", "download_batch_members",
        "materialization_receipts", "asset_uses",
    )

    data class Receipt(
        val databasePath: Path,
        val item: MediaItem,
        val binding: PlaybackBinding,
        private val originalScalars: Map<String, List<List<String>>>,
    ) {
        val expectedOriginalScalars: Map<String, List<List<String>>> get() = originalScalars
    }

    fun receiptItem(): MediaItem = MediaItem(
        id = MEDIA_ID,
        title = CURRENT_MEDIA_TITLE,
        kind = MediaKind.MUSIC,
        source = SourceRef(SourceProvider.COMMONS, CATALOG_CANONICAL_ID, canonicalUrl = CATALOG_URL),
        creator = "Authored schema2 creator",
        durationMs = 9_999_999L,
        description = "Authored schema2 metadata only; no completed asset or source access",
        attribution = Attribution("Authored schema2 creator", CATALOG_URL, "Authored fixture only"),
    )

    fun historicalBinding(): PlaybackBinding = PlaybackBinding(
        id = BINDING_ID,
        mediaId = MEDIA_ID,
        queryTitle = HISTORICAL_QUERY_TITLE,
        firstResultKind = FirstResultKind.VIDEO,
        firstResultId = HISTORICAL_VIDEO_ID,
        firstResultTitle = HISTORICAL_VIDEO_TITLE,
        firstResultUrl = CANONICAL_VIDEO_URL,
        selectedVideoId = HISTORICAL_VIDEO_ID,
        resolvedEpochMs = RESOLVED_EPOCH_MS,
        selectionOrdinal = 0,
    )

    /** Create `directory/library/veyra.db` as a real exported-version-2 database, then close it. */
    fun seed(directory: Path): Receipt {
        val schemaFile = projectRoot().resolve("shared/schemas/dev.veyra.data.room.VeyraDatabase/2.json")
        val database = Json.parseToJsonElement(Files.readString(schemaFile)).jsonObject.getValue("database").jsonObject
        assertEquals(2, database.getValue("version").jsonPrimitive.content.toInt())
        val entities = database.getValue("entities").jsonArray.map { it.jsonObject }
        val setupQueries = database.getValue("setupQueries").jsonArray.map { it.jsonPrimitive.content }

        val databasePath = directory.resolve("library/veyra.db")
        assertFalse(Files.exists(databasePath), "The schema2 fixture must be created before Room opens the path")
        Files.createDirectories(requireNotNull(databasePath.parent))

        val item = receiptItem()
        val binding = historicalBinding()
        bundledDriver().open(databasePath.toString()).use { connection ->
            connection.execSQL("PRAGMA busy_timeout = 5000")
            connection.execSQL("PRAGMA foreign_keys = ON")
            assertEquals(1L, connection.longValue("PRAGMA foreign_keys"))
            connection.execSQL("BEGIN IMMEDIATE")
            try {
                entities.forEach { entity ->
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    connection.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace(TABLE_PLACEHOLDER, table))
                    entity["indices"]?.jsonArray?.forEach { index ->
                        connection.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace(TABLE_PLACEHOLDER, table))
                    }
                }
                setupQueries.forEach(connection::execSQL)
                connection.insert("queue_contexts", mapOf(
                    "id" to "MUSIC", "positionMs" to 0L, "shuffle" to false, "repeat" to "OFF",
                    "speed" to 1.0, "hasOriginalOrder" to false,
                ))
                connection.insert("queue_contexts", mapOf(
                    "id" to "PODCAST", "positionMs" to 0L, "shuffle" to false, "repeat" to "OFF",
                    "speed" to 1.0, "hasOriginalOrder" to false,
                ))
                connection.insert("store_meta", mapOf("id" to 0, "revision" to STORE_REVISION, "activeContext" to "MUSIC"))
                connection.insert("settings", mapOf(
                    "id" to 0, "language" to "ENGLISH", "theme" to "LIGHT", "textScale" to 1.0,
                    "offlineOnly" to false, "speed" to 1.0, "skipBackSeconds" to 15,
                    "skipForwardSeconds" to 30, "crossfadeEnabled" to false,
                    "crossfadeDurationMs" to 3_000L, "downloadParallelism" to 1,
                ))
                connection.insert("media", mapOf(
                    "id" to item.id, "title" to item.title, "kind" to item.kind.name,
                    "creator" to item.creator, "description" to item.description, "explicit" to false,
                    "advisoryDurationMs" to item.durationMs,
                    "source_provider" to item.source.provider.name,
                    "source_canonicalId" to item.source.canonicalId,
                    "source_canonicalUrl" to item.source.canonicalUrl,
                    "attribution_creator" to item.attribution?.creator,
                    "attribution_sourceUrl" to item.attribution?.sourceUrl,
                    "attribution_licenseName" to item.attribution?.licenseName,
                ))
                connection.insert("tracks", mapOf("mediaId" to item.id))
                connection.insert("playback_bindings", mapOf(
                    "id" to binding.id, "mediaId" to binding.mediaId, "queryTitle" to binding.queryTitle,
                    "firstResultKind" to binding.firstResultKind.name, "firstResultId" to binding.firstResultId,
                    "firstResultTitle" to binding.firstResultTitle, "firstResultUrl" to binding.firstResultUrl,
                    "selectedVideoId" to binding.selectedVideoId, "resolvedEpochMs" to binding.resolvedEpochMs,
                    "selectionOrdinal" to binding.selectionOrdinal,
                ))
                connection.execSQL("PRAGMA user_version = 2")
                connection.requireIntegrity()
                connection.execSQL("COMMIT")
            } catch (failure: Throwable) {
                try { connection.execSQL("ROLLBACK") } catch (rollback: Throwable) {
                    if (rollback !== failure) failure.addSuppressed(rollback)
                }
                throw failure
            }
            assertEquals(2L, connection.longValue("PRAGMA user_version"))
            connection.requireIntegrity()
            val original = seedTables.associateWith { connection.scalarRows(it) }
            return Receipt(databasePath, item, binding, original)
        }
    }

    /**
     * Read-only proof over the real migrated database. It never calls a migration helper, writes
     * schema-3 rows, opens an application facade or creates acquisition work.
     */
    fun assertPreserved(receipt: Receipt) {
        bundledDriver().open(receipt.databasePath.toString()).use { connection ->
            assertEquals(3L, connection.longValue("PRAGMA user_version"), "The production migration must be at schema 3")
            receipt.expectedOriginalScalars.forEach { (table, rows) ->
                assertEquals(rows, connection.scalarRows(table), "Schema2 table `$table` must remain byte-for-byte scalar-equal")
            }
            connection.requireIntegrity()
            assertEquals(1L, connection.count("first_result_receipts"))
            assertEquals(1L, connection.count("execution_bindings"))
            assertEquals(1L, connection.count("source_recordings"))
            assertEquals(1L, connection.count("executable_subjects"))
            assertEquals(2L, connection.count("queue_contexts"))
            assertEquals(listOf("MUSIC"), connection.row("SELECT activeContext FROM store_meta", emptyList()))
            assertTrue(connection.longValue("SELECT revision FROM store_meta") >= STORE_REVISION,
                "The schema revision may advance but must never move backwards")
            zeroRowTables.forEach { table ->
                assertEquals(0L, connection.count(table), "Schema2 history must not fabricate `$table` rows")
            }
            assertCanonicalReceipt(connection, receipt.binding)
        }
    }

    fun assertCanonicalReceipt(connection: SQLiteConnection, binding: PlaybackBinding) {
        assertEquals(
            listOf("SCHEMA2", binding.mediaId, binding.queryTitle, binding.firstResultKind.name,
                binding.firstResultId, binding.firstResultTitle, requireNotNull(binding.firstResultUrl),
                requireNotNull(binding.selectedVideoId), binding.resolvedEpochMs.toString(),
                binding.selectionOrdinal.toString()),
            connection.row(
                "SELECT origin, legacyMediaId, queryTitle, legacyFirstResultKind, legacyFirstResultId, " +
                    "legacyFirstResultTitle, canonicalUrl, legacySelectedVideoId, legacyResolvedEpochMs, " +
                    "legacySelectionOrdinal FROM first_result_receipts WHERE id = ?",
                listOf(binding.id),
            ),
        )
        assertEquals(
            listOf(binding.mediaId, binding.id, RECORDING_ID, "SCHEMA2_EXECUTABLE"),
            connection.row(
                "SELECT subjectId, firstReceiptId, recordingId, origin FROM execution_bindings WHERE id = ?",
                listOf(binding.id),
            ),
        )
        assertEquals(
            listOf(RECORDING_ID, "YOUTUBE", requireNotNull(binding.selectedVideoId), requireNotNull(binding.firstResultUrl)),
            connection.row(
                "SELECT id, provider, canonicalId, canonicalUrl FROM source_recordings WHERE id = ?",
                listOf(RECORDING_ID),
            ),
        )
    }

    private val TABLE_PLACEHOLDER = "\${TABLE_NAME}"

    private fun bundledDriver(): SQLiteDriver =
        Class.forName("androidx.sqlite.driver.bundled.BundledSQLiteDriver").getConstructor().newInstance() as SQLiteDriver

    private fun projectRoot(): Path {
        System.getenv("VEYRA_PROJECT_ROOT")?.let { return Path.of(it) }
        return generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
    }

    private fun SQLiteConnection.insert(table: String, values: Map<String, Any?>) {
        val fields = values.entries.toList()
        prepare("INSERT INTO `$table` (${fields.joinToString(",") { "`${it.key}`" }}) VALUES (${fields.joinToString(",") { "?" }})").use { statement ->
            fields.forEachIndexed { index, entry ->
                when (val value = entry.value) {
                    null -> statement.bindNull(index + 1)
                    is String -> statement.bindText(index + 1, value)
                    is Boolean -> statement.bindLong(index + 1, if (value) 1L else 0L)
                    is Int -> statement.bindLong(index + 1, value.toLong())
                    is Long -> statement.bindLong(index + 1, value)
                    is Double -> statement.bindDouble(index + 1, value)
                    else -> error("Unsupported authored schema2 scalar")
                }
            }
            statement.step()
        }
    }

    private fun SQLiteConnection.scalarRows(table: String): List<List<String>> {
        val columns = prepare("PRAGMA table_info(`$table`)").use { statement ->
            buildList { while (statement.step()) add(statement.getText(1)) }
        }
        assertTrue(columns.isNotEmpty() && columns.all { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) })
        val selection = columns.joinToString(",") { "quote(`$it`)" }
        val order = columns.joinToString(",") { "`$it`" }
        return prepare("SELECT $selection FROM `$table` ORDER BY $order").use { statement ->
            buildList {
                while (statement.step()) add(columns.indices.map { statement.getText(it) })
            }
        }
    }

    private fun SQLiteConnection.row(query: String, arguments: List<String>): List<String> =
        prepare(query).use { statement ->
            arguments.forEachIndexed { index, argument -> statement.bindText(index + 1, argument) }
            assertTrue(statement.step(), "Expected one authored row: $query")
            val values = buildList { for (index in 0 until statement.getColumnCount()) add(statement.getText(index)) }
            assertFalse(statement.step(), "Expected exactly one authored row: $query")
            values
        }

    private fun SQLiteConnection.count(table: String): Long =
        prepare("SELECT count(*) FROM `$table`").use {
            assertTrue(it.step())
            val count = it.getLong(0)
            assertFalse(it.step())
            count
        }

    private fun SQLiteConnection.longValue(query: String): Long = prepare(query).use {
        assertTrue(it.step())
        val value = it.getLong(0)
        assertFalse(it.step())
        value
    }

    private fun SQLiteConnection.requireIntegrity() {
        assertEquals("ok", longText("PRAGMA integrity_check"))
        assertFalse(prepare("PRAGMA foreign_key_check").use { it.step() }, "Foreign keys must be intact")
    }

    private fun SQLiteConnection.longText(query: String): String = prepare(query).use {
        assertTrue(it.step())
        val value = it.getText(0)
        assertFalse(it.step(), "Integrity check must return exactly one row")
        value
    }
}
