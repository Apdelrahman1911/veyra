package dev.veyra.source.execution

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.veyra.data.*
import dev.veyra.data.room.*
import dev.veyra.downloads.*
import dev.veyra.model.*
import dev.veyra.playback.*
import okio.ByteString.Companion.toByteString
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Malformed historical facts are inserted ONLY into the real exported version2 schema. */
internal enum class ResolverSchema2Shape {
    VALID, DIFFERENT_ASSET_BINDING, UNBOUND, MISSING_ASSET_REPRESENTATION,
    EMPTY_MATCHING_REPRESENTATIONS, WHITESPACE_MATCHING_REPRESENTATIONS,
    MISSING_RECORD_REPRESENTATION, DIFFERENT_RECORD_REPRESENTATION,
}

internal data class ResolverSchema2Scalars(val columns: List<String>, val rows: List<List<String>>)

internal data class ResolverSchema2Seed(
    val shape: ResolverSchema2Shape,
    val mediaId: String,
    val jobId: String,
    val currentTitle: String,
    val bindings: List<PlaybackBinding>,
    val asset: OfflineAsset,
    val recordBindingId: String?,
    val recordRepresentationId: String?,
    val original: Map<String, ResolverSchema2Scalars>,
) {
    val binding: PlaybackBinding get() = bindings.first()

    /** Compare exactly the columns that existed before migration, not invented schema3 rows. */
    fun assertOriginalScalars(databasePath: Path, expectedVersion: Long? = 3L) {
        withResolverSchema2Database(databasePath) { db ->
            val version = db.prepare("PRAGMA user_version").use { assertTrue(it.step()); it.getLong(0) }
            if (expectedVersion == null) assertTrue(version in setOf(2L, 3L)) else assertEquals(expectedVersion, version)
            assertEquals(original, original.mapValues { (table, old) -> db.resolverScalars(table, old.columns) })
            assertEquals("ok", db.prepare("PRAGMA integrity_check").use { assertTrue(it.step()); it.getText(0) })
            assertFalse(db.prepare("PRAGMA foreign_key_check").use { it.step() })
        }
        assertContentEquals(PHYSICAL_BYTES, Files.readAllBytes(Path.of(asset.uri)))
    }

    fun assertPreserved(view: LibraryAcquisitionView) {
        assertEquals(currentTitle, view.snapshot.media.getValue(mediaId).title)
        assertNotEquals(currentTitle, binding.queryTitle, "Migration must not recapture today's title")
        assertEquals(bindings.associateBy { it.id }, view.snapshot.playbackBindings)
        val record = view.snapshot.downloads.getValue(jobId)
        assertEquals(DownloadStatus.COMPLETE, record.status)
        assertNull(record.failure)
        assertEquals(asset, record.asset)
        assertEquals(recordBindingId, record.executionBindingId)
        assertEquals(recordRepresentationId, record.representationId)
        assertEquals(asset.sizeBytes, record.bytesDownloaded)
        assertEquals(asset.sizeBytes, record.totalBytes)
        val state = view.acquisition
        val job = state.physicalDownloads.getValue(jobId)
        val physical = state.materializations.getValue(assertNotNull(job.assetId))
        val producer = assertIs<PhysicalDownloadProducer.Schema2>(job.producer)
        assertEquals(recordBindingId, producer.bindingId?.value)
        assertEquals(producer, physical.producer)
        assertEquals(asset.uri, physical.uri)
        assertEquals(asset.representationId, physical.representationId)
        assertNull(physical.materializerDownloadId, "Migration cannot guess the historical materializer")
        assertNull(physical.materializerAttemptId)
        assertEquals(0L, physical.ownershipGeneration)
        assertEquals(physical.receipt(), state.materializationReceipts[physical.id])
        assertTrue(state.requests.isEmpty())
        assertTrue(state.occurrences.isEmpty())
        assertTrue(state.batches.isEmpty())
        assertTrue(state.assetUses.isEmpty(), "Migration is not local validation or typed playback permission")
        assertTrue(state.physicalAttempts.isEmpty())
        assertEquals(bindings.size, state.receipts.size)
        bindings.forEach { legacy ->
            val execution = state.bindings.getValue(ExecutionBindingId(legacy.id))
            assertEquals(ExecutionBindingOrigin.SCHEMA2_EXECUTABLE, execution.origin)
            val receipt = assertIs<FirstResultReceipt.Schema2>(state.receipts[execution.firstReceiptId])
            assertEquals(legacy.queryTitle, receipt.queryTitle)
            assertEquals(legacy.selectedVideoId, receipt.selectedVideoId)
            assertEquals(legacy.selectionOrdinal, receipt.selectionOrdinal)
        }
        val claim = state.claims.values.single()
        assertIs<DownloadClaimOwner.Schema2Standalone>(claim.owner)
        assertEquals(recordBindingId, claim.requestedBindingId?.value)
        if (shape == ResolverSchema2Shape.UNBOUND) {
            assertNull(producer.bindingId)
            assertNull(producer.recordingId)
            assertNull(claim.requestedBindingId)
            assertTrue(view.snapshot.playbackBindings.containsKey(binding.id),
                "An unrelated available old receipt must not be inferred as this file's producer")
        }
    }
}

/**
 * Exported2 -> production3 -> actual Room close/reopen. This is deliberately NOT a completeLibrary
 * reflection fixture, a legacy facade hiding AcquisitionPersistence, or a raw schema3 write.
 */
internal fun DownloadAcquisitionRoomFixture.seedResolverSchema2(shape: ResolverSchema2Shape): ResolverSchema2Seed {
    check(!Files.exists(databasePath))
    val media = "authored-resolver-schema2-media"
    val job = "authored-resolver-schema2-job"
    val currentTitle = "Today's corrected complete title — عنوان اليوم المصحح"
    val binding = PlaybackBinding("authored-resolver-schema2-r1", media,
        "Historical unchanged complete title — العنوان التاريخي", FirstResultKind.VIDEO,
        FIRST_VIDEO_ID, "Historical first recording", "https://www.youtube.com/watch?v=$FIRST_VIDEO_ID",
        FIRST_VIDEO_ID, 900L, 0)
    val bindings = if (shape == ResolverSchema2Shape.DIFFERENT_ASSET_BINDING) listOf(binding,
        binding.copy(id = "authored-resolver-schema2-r2", firstResultId = LATER_VIDEO_ID,
            selectedVideoId = LATER_VIDEO_ID, firstResultUrl = "https://www.youtube.com/watch?v=$LATER_VIDEO_ID")) else listOf(binding)
    val key = "authored-kmpyt-representation"
    val assetKey = when (shape) {
        ResolverSchema2Shape.MISSING_ASSET_REPRESENTATION -> null
        ResolverSchema2Shape.EMPTY_MATCHING_REPRESENTATIONS -> ""
        ResolverSchema2Shape.WHITESPACE_MATCHING_REPRESENTATIONS -> " \t\n"
        else -> key
    }
    val recordKey = when (shape) {
        ResolverSchema2Shape.MISSING_ASSET_REPRESENTATION, ResolverSchema2Shape.MISSING_RECORD_REPRESENTATION -> null
        ResolverSchema2Shape.DIFFERENT_RECORD_REPRESENTATION -> "authored-other-representation"
        else -> assetKey
    }
    val recordBinding = binding.id.takeUnless { shape == ResolverSchema2Shape.UNBOUND }
    val assetBinding = if (shape == ResolverSchema2Shape.DIFFERENT_ASSET_BINDING) bindings.last().id else recordBinding
    files.initialize()
    val path = files.finalPath(media, 1, "audio/mpeg")
    Files.createDirectories(Path.of(requireNotNull(path.parent).toString()))
    Files.write(Path.of(path.toString()), PHYSICAL_BYTES)
    val asset = OfflineAsset("authored-resolver-schema2-asset", media, path.toString(), PHYSICAL_BYTES.size.toLong(),
        mimeType = "audio/mpeg", codecs = "mp3", sha256 = PHYSICAL_BYTES.toByteString().sha256().hex(),
        representationId = assetKey, validatorGeneration = 1, completedEpochMs = 11_000,
        executionBindingId = assetBinding, measuredDurationMs = 30_000)
    var original: Map<String, ResolverSchema2Scalars>? = null
    withResolverSchema2Helper(databasePath) { helper -> helper.createDatabase(2).use { db ->
        db.execSQL("BEGIN IMMEDIATE")
        try {
            listOf("MUSIC", "PODCAST").forEach { context -> db.resolverInsert("queue_contexts", mapOf(
                "id" to context, "positionMs" to 0L, "shuffle" to false, "repeat" to "OFF", "speed" to 1.0, "hasOriginalOrder" to false)) }
            db.resolverInsert("store_meta", mapOf("id" to 0, "revision" to 7L, "activeContext" to "MUSIC"))
            db.resolverInsert("settings", mapOf("id" to 0, "language" to "ARABIC", "theme" to "DARK", "textScale" to 1.0,
                "offlineOnly" to false, "speed" to 1.0, "skipBackSeconds" to 15, "skipForwardSeconds" to 30,
                "crossfadeEnabled" to false, "crossfadeDurationMs" to 3000L, "downloadParallelism" to 1))
            db.resolverInsert("media", mapOf("id" to media, "title" to currentTitle, "kind" to "MUSIC",
                "creator" to "Authored creator", "description" to "Metadata only, not the selected recording", "explicit" to false,
                "advisoryDurationMs" to 9_999_999L, "source_provider" to "COMMONS", "source_canonicalId" to "authored-resolver-schema2-catalog-id"))
            db.resolverInsert("tracks", mapOf("mediaId" to media))
            bindings.forEach { row -> db.resolverInsert("playback_bindings", mapOf("id" to row.id, "mediaId" to row.mediaId,
                "queryTitle" to row.queryTitle, "firstResultKind" to row.firstResultKind.name, "firstResultId" to row.firstResultId,
                "firstResultTitle" to row.firstResultTitle, "firstResultUrl" to row.firstResultUrl, "selectedVideoId" to row.selectedVideoId,
                "resolvedEpochMs" to row.resolvedEpochMs, "selectionOrdinal" to row.selectionOrdinal)) }
            db.resolverInsert("offline_assets", mapOf("id" to asset.id, "mediaId" to media, "uri" to asset.uri,
                "sizeBytes" to asset.sizeBytes, "kind" to asset.kind.name, "mimeType" to asset.mimeType, "codecs" to asset.codecs,
                "sha256" to asset.sha256, "representationId" to asset.representationId, "validatorGeneration" to asset.validatorGeneration,
                "completedEpochMs" to asset.completedEpochMs, "executionBindingId" to asset.executionBindingId,
                "measuredDurationMs" to asset.measuredDurationMs))
            db.resolverInsert("downloads", mapOf("id" to job, "mediaId" to media, "status" to "COMPLETE",
                "bytesDownloaded" to asset.sizeBytes, "totalBytes" to asset.sizeBytes, "assetId" to asset.id,
                "representationId" to recordKey, "validatorGeneration" to 1L, "updatedEpochMs" to 11_000L,
                "executionBindingId" to recordBinding, "attemptCount" to 0, "priority" to 0, "createdEpochMs" to 1_000L,
                "userPaused" to false, "automaticRetryCount" to 0))
            db.execSQL("COMMIT")
        } catch (failure: Throwable) {
            try { db.execSQL("ROLLBACK") } catch (rollback: Throwable) { if (rollback !== failure) failure.addSuppressed(rollback) }
            throw failure
        }
        original = RESOLVER_SCHEMA2_TABLES.associateWith { db.resolverScalars(it) }
        assertEquals(2L, db.prepare("PRAGMA user_version").use { assertTrue(it.step()); it.getLong(0) })
    } }
    return ResolverSchema2Seed(shape, media, job, currentTitle, bindings, asset, recordBinding, recordKey, assertNotNull(original))
}

internal fun resolverSchema2GuardCase(shape: ResolverSchema2Shape) = acquisitionDownloadRoomCase {
    require(shape != ResolverSchema2Shape.VALID)
    val seed = seedResolverSchema2(shape)
    seed.assertOriginalScalars(databasePath, expectedVersion = 2)
    val pathAttempts = AtomicInteger()
    leases.refuse = { pathAttempts.incrementAndGet(); false }
    try {
        if (shape == ResolverSchema2Shape.DIFFERENT_ASSET_BINDING) {
            // Actual canonical open is stricter than the old resolver. The SQL migration may
            // commit3 before graph validation rejects; failure must remain non-destructive.
            repeat(2) {
                val failedStore = store()
                val failure = assertIs<StoreResult.Failure>(failedStore.initialize())
                assertEquals(FailureCode.RECOVERY_REQUIRED, failure.failure.code)
                assertEquals(failure.failure, assertIs<StoreStatus.Failed>(failedStore.status.value).failure)
                assertEquals(failure, failedStore.initialize(), "Repeated failed open is not implicit recovery")
                failedStore.close()
                seed.assertOriginalScalars(databasePath, expectedVersion = null)
            }
            assertEquals(0, pathAttempts.get(), "Invalid imported producer graph fails before resolver/file/native admission")
        } else {
            var previous = owner()
            val migrated = previous.store.acquisitionView()
            assertTrue(previous.store.requiresTypedPlayback)
            seed.assertPreserved(migrated)
            seed.assertOriginalScalars(databasePath)
            for (offlineOnly in listOf(true, false)) {
                val owner = reopenResolverOwner(previous)
                previous = owner
                assertEquals(migrated, owner.store.acquisitionView())
                assertResolverColdEvidence(owner)
                val gateway = resolverSchema2NoRemoteGateway()
                val native = AtomicInteger()
                val resolver = AppSourceResolver(owner.store, files,
                    AssetValidator { native.incrementAndGet(); AssetValidation.Valid(31_234) }, gateway, clock, owner.materializationEvidence)
                try {
                    assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> {
                        resolver.resolveWithBinding(migrated.snapshot.media.getValue(seed.mediaId), EngineCapabilities(), offlineOnly)
                    }.failure.code)
                    assertEquals(0, native.get())
                    assertEquals(0, pathAttempts.get())
                    assertEquals(migrated, owner.store.acquisitionView(), "Historical bytes/receipts remain for explicit recovery, not durable FAILED")
                    seed.assertPreserved(owner.store.acquisitionView())
                    seed.assertOriginalScalars(databasePath)
                    assertResolverColdEvidence(owner)
                    assertResolverSchema2NoRemote(gateway, owner)
                } finally { resolver.closeReaders() }
            }
        }
    } finally { leases.refuse = { false } }
}

internal fun resolverSchema2NoRemoteGateway() = ExecutionTestGateway().apply {
    first = { error("No source query or guessed historical binding") }
    execute = { _, _, _ -> error("No remote replacement for imported owned bytes") }
}

internal fun assertResolverSchema2NoRemote(gateway: ExecutionTestGateway, owner: DownloadAcquisitionRoomOwner) {
    assertTrue(gateway.queries.isEmpty())
    assertTrue(gateway.resolutions.isEmpty())
    assertEquals(0, gateway.downloads)
    assertTrue(owner.selector.requests.isEmpty())
    assertTrue(owner.gateway.preparations.isEmpty())
    assertTrue(owner.gateway.transfers.isEmpty())
    assertTrue(owner.validations.isEmpty())
    owner.legacy.assertUnused()
}

/** Read-only after migration. No schema3 row mutation, completeLibrary invocation or owner facade. */
internal fun withResolverSchema2Database(path: Path, block: (SQLiteConnection) -> Unit) {
    VeyraSQLiteDriver(BundledSQLiteDriver()).open(path.toString()).use(block)
}

private val RESOLVER_SCHEMA2_TABLES = listOf("queue_contexts", "store_meta", "settings", "media", "tracks", "playback_bindings", "offline_assets", "downloads")

private fun SQLiteConnection.resolverScalars(table: String, oldColumns: List<String>? = null): ResolverSchema2Scalars {
    require(table in RESOLVER_SCHEMA2_TABLES)
    val columns = oldColumns ?: prepare("PRAGMA table_info(`$table`)").use { statement ->
        buildList { while (statement.step()) add(statement.getText(1)) }
    }
    require(columns.isNotEmpty() && columns.all { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) })
    // SQLite quote preserves null/type/string bytes (including whitespace) without coercing
    // SQL NULL to an empty string. Ordering by the complete original projection is deterministic.
    val selection = columns.joinToString(",") { "quote(`$it`)" }
    val order = columns.joinToString(",") { "`$it`" }
    val rows = prepare("SELECT $selection FROM `$table` ORDER BY $order").use { statement ->
        buildList { while (statement.step()) add(columns.indices.map(statement::getText)) }
    }
    return ResolverSchema2Scalars(columns, rows)
}

private fun withResolverSchema2Helper(path: Path, block: (MigrationTestHelper) -> Unit) {
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
    // Failed helper opens may retain raw handles not present in its successful-return list.
    owner.use { block(MigrationTestHelper(schemaDirectoryPath = projectRoot().resolve("shared/schemas"), databasePath = path,
        driver = driver, databaseClass = VeyraDatabase::class, databaseFactory = VeyraDatabaseConstructor::initialize)) }
}

private fun SQLiteConnection.resolverInsert(table: String, values: Map<String, Any?>) {
    require(table in RESOLVER_SCHEMA2_TABLES)
    val fields = values.entries.toList()
    prepare("INSERT INTO `$table` (${fields.joinToString(",") { "`${it.key}`" }}) VALUES (${fields.joinToString(",") { "?" }})").use { statement ->
        fields.forEachIndexed { index, entry -> when (val value = entry.value) {
            null -> statement.bindNull(index + 1)
            is String -> statement.bindText(index + 1, value)
            is Boolean -> statement.bindLong(index + 1, if (value) 1L else 0L)
            is Int -> statement.bindLong(index + 1, value.toLong())
            is Long -> statement.bindLong(index + 1, value)
            is Double -> statement.bindDouble(index + 1, value)
            else -> error("Unsupported authored schema2 scalar")
        } }
        statement.step()
    }
}
