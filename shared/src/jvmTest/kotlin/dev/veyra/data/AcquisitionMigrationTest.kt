package dev.veyra.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.veyra.data.room.MIGRATION_1_2
import dev.veyra.data.room.MIGRATION_2_3
import dev.veyra.data.room.VeyraDatabase
import dev.veyra.data.room.VeyraDatabaseConstructor
import dev.veyra.data.room.VeyraSQLiteDriver
import dev.veyra.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Real bundled SQLite/Room migration tests, not a fake persistence implementation. Fixture URLs,
 * paths and metadata are authored inert values; no file/audio/source validation is asserted.
 * Version3 must come from the real KSP schema export, never a hand-written expected schema.
 */
class AcquisitionMigrationTest {
    @Test fun everySchema2ColumnAndRowSurvivesMigrationAndARealRoomReopen() = runBlocking {
        val path = roomWorkDirectory("acquisition-migration-all").resolve("veyra.db")
        lateinit var before: Map<String, List<List<Any?>>>
        withTrackedHelper(path) { helper ->
            helper.createDatabase(2).use { db ->
                db.transaction { seedLibrary() }
                before = db.legacyRows()
                assertEquals(30, before.size)
                assertTrue(before.values.all { it.isNotEmpty() }, "Every legacy table is exercised")
            }
            helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3)).use { db ->
                assertEquals(before, db.legacyRows())
                db.assertIntegrity()
                assertEquals(0, db.longValue("SELECT count(*) FROM sqlite_master WHERE name LIKE '_migration_2_3_%'").toInt())
            }
        }
        val reopened = testDatabase(path)
        try {
            assertEquals(17L, reopened.libraryDao().meta()?.revision)
            assertEquals(5, reopened.acquisitionDao().readExecutionOccurrenceRows().size)
            assertEquals(3, reopened.acquisitionDao().readDownloadClaimRows().size)
            val pending = reopened.libraryDao().readDownloads().single { it.id == "job-episode" }
            assertEquals("attempt-episode", pending.activeAttemptId)
            assertEquals("authored-worker", pending.leaseOwner)
            assertEquals(62_000L, pending.leaseExpiresEpochMs)
            assertTrue(pending.cleanupPending && pending.removeWhenClean)
            assertEquals(0L, pending.ownershipGeneration)
            assertNull(pending.maintenanceKind)
            assertEquals(PhysicalDownloadProducerOrigin.SCHEMA2, pending.producerOrigin)
        } finally { reopened.close() }
        withSql(path) { assertEquals(before, it.legacyRows()); it.assertIntegrity() }
    }

    @Test fun invalidAndNonvideoLegacyFactsStayNonExecutableWithoutBeingDiscarded() {
        val path = migrated("legacy-classification") {
            legacyMedia("catalog")
            legacyBinding("valid-null", "catalog", "AAAAAAAAAAA", url = null)
            legacyBinding("valid-url", "catalog", "BBBBBBBBBBB")
            legacyBinding("playlist", "catalog", "PL-authored", kind = "PLAYLIST", selected = null,
                url = "https://www.youtube.com/playlist?list=PL-authored")
            legacyBinding("channel", "catalog", "UC-authored", kind = "CHANNEL", selected = null,
                url = "https://www.youtube.com/channel/UC-authored")
            legacyBinding("unknown", "catalog", "unknown", kind = "UNKNOWN", selected = null, url = null)
            legacyBinding("later-result", "catalog", "CCCCCCCCCCC", ordinal = 1)
            legacyBinding("different-c", "catalog", "DDDDDDDDDDD", selected = "EEEEEEEEEEE")
            legacyBinding("bad-id", "catalog", "short")
            legacyBinding("blank-query", "catalog", "FFFFFFFFFFF", query = "   ")
            legacyBinding("control-query", "catalog", "GGGGGGGGGGG", query = "not\ncomplete")
            legacyBinding("oversized-query", "catalog", "HHHHHHHHHHH", query = "x".repeat(16_385))
            legacyBinding("blank-first-title", "catalog", "IIIIIIIIIII", title = "")
            legacyBinding("wrong-locator", "catalog", "JJJJJJJJJJJ", url = "https://www.youtube.com/watch?v=KKKKKKKKKKK")
        }
        withSql(path) { db ->
            assertEquals(13L, db.longValue("SELECT count(*) FROM first_result_receipts"))
            assertEquals(2L, db.longValue("SELECT count(*) FROM execution_bindings WHERE origin='SCHEMA2_EXECUTABLE'"))
            assertEquals(11L, db.longValue("SELECT count(*) FROM execution_bindings WHERE origin='SCHEMA2_NON_EXECUTABLE' AND recordingId IS NULL"))
            assertEquals(2L, db.longValue("SELECT count(*) FROM source_recordings"))
            assertEquals("UNKNOWN", db.textValue("SELECT legacyFirstResultKind FROM first_result_receipts WHERE id='unknown'"))
            assertEquals("not\ncomplete", db.textValue("SELECT queryTitle FROM first_result_receipts WHERE id='control-query'"))
            assertEquals(16_385L, db.longValue("SELECT length(queryTitle) FROM first_result_receipts WHERE id='oversized-query'"))
            assertEquals(1L, db.longValue("SELECT canonicalUrl IS NULL FROM first_result_receipts WHERE id='valid-null'"))
            assertEquals("https://www.youtube.com/watch?v=AAAAAAAAAAA", db.textValue(
                "SELECT canonicalUrl FROM source_recordings WHERE canonicalId='AAAAAAAAAAA'"))
            assertEquals(0L, db.longValue("SELECT count(*) FROM first_result_receipts WHERE requestId IS NOT NULL OR " +
                "rawOrdinal IS NOT NULL OR rawKind IS NOT NULL OR rawId IS NOT NULL OR rawTitle IS NOT NULL OR " +
                "rawPublicUrl IS NOT NULL OR rawIssue IS NOT NULL OR observedEpochMs IS NOT NULL"))
            db.assertIntegrity()
        }
    }

    @Test fun duplicateKnownCReusesOnlyItsRecordingIdentityNotARequestOrSubject() {
        val path = migrated("legacy-same-recording") {
            legacyMedia("first"); legacyMedia("second", kind = "EPISODE")
            legacyBinding("r1", "first", "AAAAAAAAAAA", query = "Original requested title")
            legacyBinding("r2", "second", "AAAAAAAAAAA", query = "عنوان مستقل بالكامل", url = null)
        }
        withSql(path) { db ->
            assertEquals(1L, db.longValue("SELECT count(*) FROM source_recordings"))
            assertEquals(2L, db.longValue("SELECT count(*) FROM executable_subjects WHERE kind='CATALOG_MEDIA'"))
            assertEquals(2L, db.longValue("SELECT count(*) FROM execution_bindings"))
            assertEquals(2L, db.longValue("SELECT count(DISTINCT subjectId) FROM execution_bindings"))
            listOf("acquisition_requests", "acquisition_outcomes", "acquisition_jobs", "admission_scopes",
                "collection_runs", "release_observations", "occurrence_checkpoints", "source_recording_resume", "download_producer_adoptions").forEach {
                assertEquals(0L, db.longValue("SELECT count(*) FROM $it"), it)
            }
            assertEquals("عنوان مستقل بالكامل", db.textValue("SELECT queryTitle FROM first_result_receipts WHERE id='r2'"))
        }
    }

    @Test fun queueDuplicatesModesPositionsAndHistoryDoNotAcquireGuessedBindings() {
        val path = migrated("legacy-queue") { seedLibrary() }
        withSql(path) { db ->
            assertEquals(5L, db.longValue("SELECT count(*) FROM execution_occurrences WHERE origin='SCHEMA2' AND selectionState='LEGACY_UNBOUND'"))
            assertEquals(0L, db.longValue("SELECT count(*) FROM execution_occurrences WHERE requestId IS NOT NULL OR bindingId IS NOT NULL OR " +
                "snapshotId IS NOT NULL OR snapshotOrdinal IS NOT NULL OR scopeId IS NOT NULL OR scopePosition IS NOT NULL OR admittedEpochMs IS NOT NULL"))
            assertEquals(0L, db.longValue("SELECT count(*) FROM execution_occurrences x JOIN queue_entries q ON q.id=x.id " +
                "WHERE x.subjectId<>q.mediaId OR x.controlsMode<>q.contextId"))
            assertEquals(0L, db.longValue("SELECT count(*) FROM queue_members n JOIN queue_entries old ON old.id=n.occurrenceId " +
                "WHERE n.position<>old.position OR n.originalPosition<>old.originalPosition OR n.contextId<>old.contextId"))
            assertEquals("queue-music-2", db.textValue("SELECT occurrenceId FROM queue_heads WHERE contextId='MUSIC'"))
            assertEquals(0L, db.longValue("SELECT sum(editRevision) FROM execution_queue_revisions"))
            assertEquals(0L, db.longValue("SELECT count(*) FROM listening_history WHERE occurrenceId IS NOT NULL"))
            assertEquals("music-old", db.textValue("SELECT bindingId FROM listening_history WHERE id='history-old'"))
            assertEquals("music-new", db.textValue("SELECT bindingId FROM listening_history WHERE id='history-new'"))
            assertEquals(0L, db.longValue("SELECT count(*) FROM catalog_progress WHERE lastOccurrenceId IS NOT NULL"))
            assertEquals("music-old", db.textValue("SELECT executionBindingId FROM catalog_progress WHERE mediaId='music-1'"))
            assertEquals(2L, db.longValue("SELECT count(*) FROM playlist_members WHERE subjectId='music-1'"))
            assertEquals(0L, db.longValue("SELECT count(*) FROM favorite_subjects WHERE bindingId IS NOT NULL OR controlsMode IS NOT NULL"))
            assertEquals("Historical complete title — العنوان", db.textValue("SELECT queryTitle FROM first_result_receipts WHERE id='music-old'"))
            assertNotEquals(db.textValue("SELECT title FROM media WHERE id='music-1'"),
                db.textValue("SELECT queryTitle FROM first_result_receipts WHERE id='music-old'"))
        }
    }

    @Test fun actualLegacyStandaloneClaimsPreserveEveryStatusAndExplicitIntent() {
        val statuses = DownloadStatus.entries.map { it.name }
        val path = migrated("legacy-claims") {
            statuses.forEachIndexed { index, status ->
                legacyMedia("media-$index")
                legacyDownload("job-$index", "media-$index", status = status)
            }
            legacyMedia("paused-explicit")
            legacyDownload("job-paused-explicit", "paused-explicit", status = "PAUSED", extra = mapOf("userPaused" to true))
            legacyMedia("remove-complete")
            legacyDownload("job-remove-complete", "remove-complete", status = "COMPLETE", extra = mapOf("removeWhenClean" to true, "cleanupPending" to true))
        }
        withSql(path) { db ->
            statuses.forEachIndexed { index, status ->
                assertEquals(status, db.textValue("SELECT status FROM downloads WHERE id='job-$index'"))
                assertEquals(if (status == "CANCELLED") "CANCELLED" else "ACTIVE",
                    db.textValue("SELECT intent FROM download_claims WHERE id='job-$index'"))
                assertEquals(1L, db.longValue("SELECT retainCompletedAsset FROM download_claims WHERE id='job-$index'"))
            }
            assertEquals("PAUSED", db.textValue("SELECT intent FROM download_claims WHERE id='job-paused-explicit'"))
            assertEquals("CANCELLED", db.textValue("SELECT intent FROM download_claims WHERE id='job-remove-complete'"))
            assertEquals(0L, db.longValue("SELECT retainCompletedAsset FROM download_claims WHERE id='job-remove-complete'"))
            assertEquals((statuses.size + 2).toLong(), db.longValue("SELECT count(*) FROM download_claims WHERE " +
                "ownerKind='SCHEMA2_STANDALONE' AND id=physicalJobId AND physicalJobId=standaloneJobId AND requestedBindingId IS NULL"))
            assertEquals(0L, db.longValue("SELECT sum(generation+ownerIntentRevision) FROM download_claims"))
            assertEquals(0L, db.longValue("SELECT count(*) FROM downloads WHERE maintenanceKind IS NOT NULL OR ownershipGeneration<>0"))
        }
    }

    @Test fun physicalHistoryUsesEachRowsOwnBindingAndDoesNotGuessAssetMaterializers() {
        val path = migrated("legacy-physical-producer") { seedLibrary() }
        withSql(path) { db ->
            assertEquals("schema2:youtube:AAAAAAAAAAA", db.textValue("SELECT recordingId FROM downloads WHERE id='job-music'"))
            assertEquals("schema2:youtube:BBBBBBBBBBB", db.textValue("SELECT recordingId FROM download_attempts WHERE id='attempt-music-history'"))
            assertEquals("music-new", db.textValue("SELECT executionBindingId FROM download_attempts WHERE id='attempt-music-history'"))
            assertEquals("music-1", db.textValue("SELECT mediaId FROM download_attempts WHERE id='attempt-music-history'"))
            assertEquals(1L, db.longValue("SELECT recordingId IS NULL AND executionBindingId IS NULL FROM download_attempts WHERE id='attempt-unbound'"))
            assertEquals(1L, db.longValue("SELECT recordingId IS NULL FROM downloads WHERE id='job-second'"))
            assertEquals(1L, db.longValue("SELECT count(*) FROM materialization_receipts"))
            assertEquals(0L, db.longValue("SELECT count(*) FROM materialization_receipts WHERE materializerDownloadId IS NOT NULL OR " +
                "materializerAttemptId IS NOT NULL OR ownershipGeneration<>0"))
            assertEquals(0L, db.longValue("SELECT count(*) FROM offline_assets WHERE materializerDownloadId IS NOT NULL OR materializerAttemptId IS NOT NULL"))
            assertEquals(0L, db.longValue("SELECT count(*) FROM download_finalizations WHERE assetId IS NOT NULL"))
            assertEquals(0L, db.longValue("SELECT count(*) FROM asset_uses"))
            assertEquals("episode-binding", db.textValue("SELECT requestedBindingId FROM download_claims WHERE id='job-episode'"))
        }
    }

    @Test fun neutralTypedSubjectCanOwnTheExistingDeferredPhysicalCycleWithoutFakeMedia() {
        val path = migrated("typed-physical-cycle") { seedLibrary() }
        withSql(path) { db ->
            db.transaction {
                seedTypedSingle()
                legacyDownload("typed-job", null, status = "RUNNING", extra = mapOf(
                    "subjectId" to "neutral-subject", "producerOrigin" to "TYPED", "recordingId" to "typed-recording",
                    "executionBindingId" to "typed-binding", "ownershipGeneration" to 1L,
                    "attemptCount" to 1, "activeAttemptId" to "typed-attempt", "leaseOwner" to "typed-owner", "leaseExpiresEpochMs" to 5000L))
                legacyAttempt("typed-attempt", "typed-job", "typed-binding", extra = mapOf(
                    "mediaId" to null, "subjectId" to "neutral-subject", "producerOrigin" to "TYPED",
                    "recordingId" to "typed-recording", "ownershipGeneration" to 1L))
            }
            db.assertIntegrity() // transaction COMMIT above must also succeed, not merely PRAGMA.
            assertEquals(1L, db.longValue("SELECT mediaId IS NULL FROM downloads WHERE id='typed-job'"))
            assertEquals("typed-attempt", db.textValue("SELECT activeAttemptId FROM downloads WHERE id='typed-job'"))
            assertEquals(3L, db.longValue("SELECT count(*) FROM media"))
        }
    }

    @Test fun activeAttemptAndFinalizationCannotBorrowADifferentJobsAttempt() {
        val path = migrated("physical-owner-fk") { seedLibrary() }
        withSql(path) { db ->
            assertFails { db.transaction { execSQL("UPDATE downloads SET activeAttemptId='attempt-episode' WHERE id='job-second'") } }
            assertFails { db.transaction { execSQL("UPDATE download_finalizations SET attemptId='attempt-music-history' WHERE downloadId='job-episode'") } }
            assertEquals(1L, db.longValue("SELECT activeAttemptId IS NULL FROM downloads WHERE id='job-second'"))
            assertEquals("attempt-episode", db.textValue("SELECT attemptId FROM download_finalizations WHERE downloadId='job-episode'"))
            db.assertIntegrity()
        }
    }

    @Test fun oneLiveAssetCannotBeAttachedToTwoPhysicalJobs() {
        val path = migrated("physical-asset-unique") { seedLibrary() }
        withSql(path) { db ->
            assertFails { db.transaction { execSQL("UPDATE downloads SET assetId='asset-music' WHERE id='job-episode'") } }
            assertEquals("asset-music", db.textValue("SELECT assetId FROM downloads WHERE id='job-music'"))
            assertEquals(1L, db.longValue("SELECT assetId IS NULL FROM downloads WHERE id='job-episode'"))
            db.assertIntegrity()
        }
    }

    @Test fun typedAndLegacyStandaloneDiscriminatorsCannotCreateTwoOwnersForOneJob() {
        val path = migrated("standalone-owner-unique") { seedLibrary() }
        withSql(path) { db ->
            assertFails { db.transaction {
                insert("download_claims", mapOf("id" to "new-standalone", "ownerKind" to "STANDALONE", "physicalJobId" to "job-music",
                    "standaloneJobId" to "job-music", "requestedBindingId" to "music-old", "intent" to "ACTIVE",
                    "retainCompletedAsset" to true, "updatedEpochMs" to 9000L, "generation" to 1L, "ownerIntentRevision" to 1L))
            } }
            assertEquals(1L, db.longValue("SELECT count(*) FROM download_claims WHERE standaloneJobId='job-music'"))
            assertEquals("SCHEMA2_STANDALONE", db.textValue("SELECT ownerKind FROM download_claims WHERE standaloneJobId='job-music'"))
        }
    }

    @Test fun sourceCollectionDuplicatesAndUnavailableHolesPersistAsDistinctOccurrences() = runBlocking {
        val path = migrated("collection-normalized-occurrences") { seedLibrary() }
        withSql(path) { it.transaction { seedCollection() } }
        val reopened = testDatabase(path)
        try {
            val rows = reopened.acquisitionDao().readCollectionMemberRows().sortedBy { it.ordinal }
            assertEquals(listOf(0, 1, 2), rows.map { it.ordinal })
            assertEquals(listOf("MMMMMMMMMMM", null, "MMMMMMMMMMM"), rows.map { it.entry.id })
            assertEquals(SourceEntryIssue.UNAVAILABLE, rows[1].entry.issue)
            assertNull(rows[1].display)
            assertEquals(3, reopened.acquisitionDao().readCollectionSnapshotMemberRows().size)
            assertEquals(1, reopened.acquisitionDao().readCollectionSnapshotRows().single().nonPlayableMemberCount)
        } finally { reopened.close() }
    }

    @Test fun capturedMemberCannotPointToAnUnobservedSnapshotOrdinal() {
        val path = migrated("snapshot-member-fk") { seedLibrary() }
        withSql(path) { db ->
            db.transaction { seedCollection() }
            assertFails { db.transaction {
                insert("acquisition_requests", mapOf("id" to "missing-member-request", "kind" to "COLLECTION_MEMBER", "completeTitle" to "Complete member title", "capturedEpochMs" to 40L))
                insert("request_collection_member_metadata", mapOf("requestId" to "missing-member-request", "snapshotId" to "snapshot", "ordinal" to 99))
            } }
            assertEquals(0L, db.longValue("SELECT count(*) FROM acquisition_requests WHERE id='missing-member-request'"))
            assertEquals(3L, db.longValue("SELECT count(*) FROM collection_snapshot_members"))
            db.assertIntegrity()
        }
    }

    @Test fun sealedHeaderlessPrefixDoesNotInheritALaterVerifiedRunHeader() = runBlocking {
        val path = migrated("snapshot-observation-absence") { seedLibrary() }
        withSql(path) { it.transaction { seedCollection(retainHeaderlessPrefix = true) } }
        val reopened = testDatabase(path)
        try {
            assertEquals(1, reopened.acquisitionDao().readCollectionObservationRows().size)
            val snapshots = reopened.acquisitionDao().readCollectionSnapshotRows().associateBy { it.id }
            val earlier = snapshots.getValue("headerless-snapshot")
            assertFalse(earlier.observationCaptured)
            assertNull(earlier.lastCommittedPageIndex)
            assertEquals(0, earlier.observedMemberCount)
            assertTrue(snapshots.getValue("snapshot").observationCaptured)
        } finally { reopened.close() }
    }

    @Test fun deletingLiveBytesDoesNotDeleteImmutableMaterializationOrAssetUseHistory() {
        val path = migrated("retained-materialization-receipt") { seedLibrary() }
        withSql(path) { db ->
            // This authored SQL row tests FK lifetime only, NOT current-process validation or Ready.
            db.transaction {
                insert("asset_uses", mapOf("requestedBindingId" to "music-old", "materializationAssetId" to "asset-music",
                    "compatibilityPolicyVersion" to 1, "createdEpochMs" to 6000L))
                execSQL("UPDATE downloads SET assetId=NULL WHERE id='job-music'")
                execSQL("DELETE FROM offline_assets WHERE id='asset-music'")
            }
            assertEquals(0L, db.longValue("SELECT count(*) FROM offline_assets"))
            assertEquals(1L, db.longValue("SELECT count(*) FROM materialization_receipts WHERE id='asset-music'"))
            assertEquals(1L, db.longValue("SELECT count(*) FROM asset_uses WHERE materializationAssetId='asset-music'"))
            assertFails { db.transaction { execSQL("DELETE FROM materialization_receipts WHERE id='asset-music'") } }
            db.assertIntegrity()
        }
    }

    @Test fun referencedReceiptsAndBindingsCannotBeCascadedOutOfHistory() {
        val path = migrated("retained-execution-provenance") { seedLibrary() }
        withSql(path) { db ->
            assertFails { db.transaction { execSQL("DELETE FROM execution_bindings WHERE id='music-old'") } }
            assertFails { db.transaction { execSQL("DELETE FROM first_result_receipts WHERE id='music-old'") } }
            assertEquals("music-old", db.textValue("SELECT bindingId FROM listening_history WHERE id='history-old'"))
            assertEquals(1L, db.longValue("SELECT count(*) FROM first_result_receipts WHERE id='music-old'"))
        }
    }

    @Test fun missingSchema3MigrationFailsWithoutErasingAnySchema2Rows() {
        val path = roomWorkDirectory("missing-acquisition-migration").resolve("veyra.db")
        lateinit var before: Map<String, List<List<Any?>>>
        withTrackedHelper(path) { helper ->
            helper.createDatabase(2).use { it.transaction { seedLibrary() }; before = it.legacyRows() }
            assertFails { helper.runMigrationsAndValidate(3, emptyList()).close() }
        }
        withSql(path) { db ->
            assertEquals(2L, db.longValue("PRAGMA user_version"))
            assertEquals(before, db.legacyRows())
            assertEquals(0L, db.longValue("SELECT count(*) FROM sqlite_master WHERE name='acquisition_requests'"))
            db.assertIntegrity()
        }
    }

    @Test fun previouslyAllowedCrossJobCorruptionFailsAndRollsBackEveryMigrationChange() {
        val path = roomWorkDirectory("corrupt-acquisition-migration").resolve("veyra.db")
        lateinit var before: Map<String, List<List<Any?>>>
        withTrackedHelper(path) { helper ->
            helper.createDatabase(2).use { db ->
                db.transaction {
                    seedLibrary()
                    // Schema2's single-column FK allowed this wrong owner. No FK is disabled.
                    execSQL("UPDATE downloads SET activeAttemptId='attempt-episode' WHERE id='job-second'")
                }
                before = db.legacyRows()
                db.assertIntegrity()
            }
            assertFails { helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3)).close() }
        }
        withSql(path) { db ->
            assertEquals(2L, db.longValue("PRAGMA user_version"))
            assertEquals(before, db.legacyRows())
            assertEquals(0L, db.longValue("SELECT count(*) FROM sqlite_master WHERE name='execution_bindings' OR name LIKE '_migration_2_3_%'"))
            assertEquals("attempt-episode", db.textValue("SELECT activeAttemptId FROM downloads WHERE id='job-second'"))
            db.assertIntegrity()
        }
    }

    @Test fun version1To3UsesBothExplicitMigrationsAndPreservesBaselineValues() {
        val path = roomWorkDirectory("acquisition-migration-chain").resolve("veyra.db")
        lateinit var before: Map<String, List<List<Any?>>>
        withTrackedHelper(path) { helper ->
            helper.createDatabase(1).use { db -> db.transaction { seedLibrary(version = 1) }; before = db.legacyRows(version = 1) }
            helper.runMigrationsAndValidate(3, listOf(MIGRATION_1_2, MIGRATION_2_3)).use { db ->
                assertEquals(before, db.legacyRows(version = 1))
                assertEquals(0L, db.longValue("SELECT crossfadeEnabled FROM settings"))
                assertEquals(3000L, db.longValue("SELECT crossfadeDurationMs FROM settings"))
                assertEquals(0L, db.longValue("SELECT sum(cleanupPending+removeWhenClean) FROM downloads"))
                assertEquals("CANCELLED", db.textValue("SELECT intent FROM download_claims WHERE id='job-episode'"))
                assertEquals(1L, db.longValue("SELECT retainCompletedAsset FROM download_claims WHERE id='job-episode'"))
                db.assertIntegrity()
            }
        }
    }

    private fun migrated(label: String, seed: SQLiteConnection.() -> Unit): Path {
        val path = roomWorkDirectory(label).resolve("veyra.db")
        withTrackedHelper(path) { helper ->
            helper.createDatabase(2).use { it.transaction(seed) }
            helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3)).use { it.assertIntegrity() }
        }
        return path
    }

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
                    try { connection.close() } catch (error: Throwable) {
                        val previous = failure
                        if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
                    }
                }
                failure?.let { throw it }
            }
        }
        // MigrationTestHelper2.8.5 owns only successfully returned handles. Failed opens are
        // still raw resources; this finally owner closes all, preserving any primary failure.
        owner.use {
            block(MigrationTestHelper(schemaDirectoryPath = projectRoot().resolve("shared/schemas"), databasePath = path,
                driver = driver, databaseClass = VeyraDatabase::class, databaseFactory = VeyraDatabaseConstructor::initialize))
        }
    }

    private fun SQLiteConnection.transaction(block: SQLiteConnection.() -> Unit) {
        execSQL("BEGIN IMMEDIATE")
        try { block(); execSQL("COMMIT") } catch (failure: Throwable) {
            try { execSQL("ROLLBACK") } catch (rollback: Throwable) { if (rollback !== failure) failure.addSuppressed(rollback) }
            throw failure
        }
    }

    private fun SQLiteConnection.assertIntegrity() {
        assertEquals(1L, longValue("PRAGMA foreign_keys"))
        assertEquals("ok", textValue("PRAGMA integrity_check"))
        assertFalse(prepare("PRAGMA foreign_key_check").use { it.step() })
    }

    private fun SQLiteConnection.legacyRows(version: Int = 2): Map<String, List<List<Any?>>> {
        val schema = Json.parseToJsonElement(Files.readString(projectRoot().resolve("shared/schemas/dev.veyra.data.room.VeyraDatabase/$version.json")))
        return schema.jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray.associate { element ->
            val entity = element.jsonObject
            val table = entity.getValue("tableName").jsonPrimitive.content
            val fields = entity.getValue("fields").jsonArray.map { it.jsonObject }
            val columns = fields.joinToString(",") { "`${it.getValue("columnName").jsonPrimitive.content}`" }
            val rows = prepare("SELECT $columns FROM `$table` ORDER BY $columns").use { statement ->
                buildList {
                    while (statement.step()) add(fields.mapIndexed { index, field ->
                        if (statement.isNull(index)) null else when (field.getValue("affinity").jsonPrimitive.content) {
                            "TEXT" -> statement.getText(index)
                            "INTEGER" -> statement.getLong(index)
                            "REAL" -> statement.getDouble(index)
                            else -> error("unexpected_authored_schema_affinity")
                        }
                    })
                }
            }
            table to rows
        }
    }

    private fun SQLiteConnection.insert(table: String, values: Map<String, Any?>) {
        val fields = values.entries.toList()
        prepare("INSERT INTO `$table` (${fields.joinToString(",") { "`${it.key}`" }}) VALUES (${fields.joinToString(",") { "?" }})").use { statement ->
            fields.forEachIndexed { index, entry -> when (val value = entry.value) {
                null -> statement.bindNull(index + 1)
                is String -> statement.bindText(index + 1, value)
                is Boolean -> statement.bindLong(index + 1, if (value) 1L else 0L)
                is Int -> statement.bindLong(index + 1, value.toLong())
                is Long -> statement.bindLong(index + 1, value)
                is Double -> statement.bindDouble(index + 1, value)
                else -> error("unsupported_authored_fixture_scalar")
            } }
            statement.step()
        }
    }

    private fun SQLiteConnection.legacyMedia(id: String, kind: String = "MUSIC") = insert("media", mapOf(
        "id" to id, "title" to "Current catalogue title $id — عنوان", "kind" to kind,
        "source_provider" to if (kind == "MUSIC") "COMMONS" else "RSS", "source_canonicalId" to "authored-$id",
        "creator" to "Authored creator", "description" to "Authored discovery metadata", "explicit" to false,
        "advisoryDurationMs" to 123_000L, "publishedEpochMs" to 10L, "localArtworkPath" to "authored/cache/$id.jpg"))

    private fun SQLiteConnection.legacyBinding(id: String, media: String, video: String, kind: String = "VIDEO",
        selected: String? = video, url: String? = "https://www.youtube.com/watch?v=$video", ordinal: Int = 0,
        query: String = "Historical complete title — العنوان", title: String = "Actually observed first result title") = insert("playback_bindings", mapOf(
        "id" to id, "mediaId" to media, "queryTitle" to query, "firstResultKind" to kind, "firstResultId" to video,
        "firstResultTitle" to title, "firstResultUrl" to url, "selectedVideoId" to selected, "resolvedEpochMs" to 1234L, "selectionOrdinal" to ordinal))

    private fun SQLiteConnection.legacyDownload(id: String, media: String?, status: String = "QUEUED", extra: Map<String, Any?> = emptyMap()) = insert("downloads", mapOf(
        "id" to id, "mediaId" to media, "status" to status, "bytesDownloaded" to 0L, "validatorGeneration" to 0L,
        "updatedEpochMs" to 2000L, "attemptCount" to 0, "priority" to 0, "createdEpochMs" to 1000L,
        "userPaused" to false, "automaticRetryCount" to 0) + extra)

    private fun SQLiteConnection.legacyAttempt(id: String, job: String, binding: String?, extra: Map<String, Any?> = emptyMap()) = insert("download_attempts", mapOf(
        "id" to id, "downloadId" to job, "number" to 1, "status" to "FAILED", "startedEpochMs" to 1000L,
        "finishedEpochMs" to 1100L, "bytesDownloaded" to 12L, "totalBytes" to 100L,
        "executionBindingId" to binding, "validatorGeneration" to 1L) + extra)

    /** At least one row in every one of the thirty original normalized tables. */
    private fun SQLiteConnection.seedLibrary(version: Int = 2) {
        insert("artists", mapOf("id" to "artist", "name" to "الفنان", "sortName" to "الفنان", "description" to "Authored artist"))
        insert("albums", mapOf("id" to "album", "title" to "Original album", "description" to "Not an inferred release edition", "releaseYear" to 2026))
        insert("album_artists", mapOf("albumId" to "album", "artistId" to "artist", "position" to 0))
        legacyMedia("music-1"); legacyMedia("music-2"); legacyMedia("episode", "EPISODE")
        listOf("music-1", "music-2").forEachIndexed { index, id ->
            insert("tracks", mapOf("mediaId" to id, "albumId" to "album", "trackNumber" to index + 1, "discNumber" to 1))
            insert("track_artists", mapOf("trackId" to id, "artistId" to "artist", "position" to 0))
            insert("album_tracks", mapOf("albumId" to "album", "trackId" to id, "position" to index))
        }
        insert("podcasts", mapOf("id" to "show", "title" to "Authored show", "feedUrl" to "https://publisher.example.invalid/feed",
            "publisher" to "Authored publisher", "description" to "Discovery only", "language" to "ar", "explicit" to false,
            "etag" to "\"metadata-etag\"", "lastModified" to "Mon, 02 Nov 2026 12:00:00 GMT", "lastRefreshEpochMs" to 500L))
        insert("podcast_feed_aliases", mapOf("podcastId" to "show", "feedUrl" to "https://publisher.example.invalid/old"))
        insert("episodes", mapOf("mediaId" to "episode", "podcastId" to "show", "episodeNumber" to 7, "seasonNumber" to 2))
        insert("podcast_episodes", mapOf("podcastId" to "show", "episodeId" to "episode", "position" to 0))
        insert("playlists", mapOf("id" to "playlist", "name" to "قائمة محلية", "createdEpochMs" to 100L, "updatedEpochMs" to 200L, "position" to 0))
        repeat(2) { insert("playlist_entries", mapOf("id" to "playlist-$it", "playlistId" to "playlist", "mediaId" to "music-1", "position" to it)) }
        listOf("MUSIC", "PODCAST").forEach { context ->
            insert("queue_contexts", mapOf("id" to context, "positionMs" to if (context == "MUSIC") 5000L else 6000L,
                "shuffle" to (context == "MUSIC"), "repeat" to if (context == "MUSIC") "ALL" else "OFF",
                "speed" to if (context == "MUSIC") 1.25 else 1.5, "hasOriginalOrder" to (context == "MUSIC")))
        }
        listOf("music-1", "music-2", "music-1").forEachIndexed { index, media ->
            insert("queue_entries", mapOf("id" to "queue-music-$index", "contextId" to "MUSIC", "mediaId" to media,
                "position" to index, "originalPosition" to (index + 2) % 3))
        }
        repeat(2) { insert("queue_entries", mapOf("id" to "queue-episode-$it", "contextId" to "PODCAST", "mediaId" to "episode", "position" to it, "originalPosition" to it)) }
        insert("queue_cursors", mapOf("contextId" to "MUSIC", "entryId" to "queue-music-2"))
        insert("queue_cursors", mapOf("contextId" to "PODCAST", "entryId" to "queue-episode-0"))
        insert("store_meta", mapOf("id" to 0, "revision" to 17L, "activeContext" to "PODCAST"))
        listOf("music-1", "episode").forEach { insert("favorites", mapOf("mediaId" to it)) }
        insert("saved_episodes", mapOf("episodeId" to "episode")); insert("followed_podcasts", mapOf("podcastId" to "show"))
        legacyBinding("music-old", "music-1", "AAAAAAAAAAA", url = null)
        legacyBinding("music-new", "music-1", "BBBBBBBBBBB", query = "A later independent complete query")
        legacyBinding("episode-binding", "episode", "CCCCCCCCCCC")
        legacyBinding("second-unplayable", "music-2", "PL-authored", kind = "PLAYLIST", selected = null,
            url = "https://www.youtube.com/playlist?list=PL-authored")
        listOf("music-1" to "music-old", "episode" to "episode-binding").forEach { (media, binding) ->
            insert("media_progress", mapOf("mediaId" to media, "positionMs" to 6000L, "durationMs" to 88_000L, "played" to false,
                "updatedEpochMs" to 2000L, "sessionId" to "recorded-session", "generation" to 2L, "sequence" to 9L, "executionBindingId" to binding))
        }
        listOf("music-old", "music-new").forEachIndexed { index, binding ->
            insert("playback_history", mapOf("id" to if (index == 0) "history-old" else "history-new", "mediaId" to "music-1",
                "startedEpochMs" to 1000L + index, "lastPositionMs" to 120_000L, "endedNaturally" to (index == 0),
                "executionBindingId" to binding, "position" to index))
        }
        insert("offline_assets", mapOf("id" to "asset-music", "mediaId" to "music-1", "uri" to "authored/offline/music.m4a",
            "sizeBytes" to 4096L, "kind" to "FILE", "mimeType" to "audio/mp4", "codecs" to "aac", "sha256" to "a".repeat(64),
            "representationId" to "140", "validatorGeneration" to 3L, "completedEpochMs" to 3000L,
            "executionBindingId" to "music-old", "measuredDurationMs" to 120_000L))
        legacyDownload("job-music", "music-1", "COMPLETE", mapOf("bytesDownloaded" to 4096L, "totalBytes" to 4096L,
            "assetId" to "asset-music", "executionBindingId" to "music-old", "attemptCount" to 1))
        legacyDownload("job-episode", "episode", "CANCELLED", mapOf("bytesDownloaded" to 2048L, "totalBytes" to 4096L,
            "failure_code" to "BACKGROUND_RESTRICTED", "failure_detail" to "authored_os_limit", "failure_retryAfterMs" to 500L,
            "stagingPath" to "authored/staging/episode.part", "representationId" to "140", "etag" to "\"strong-validator\"",
            "lastModified" to "Mon, 02 Nov 2026 12:00:00 GMT", "validatorGeneration" to 2L,
            "executionBindingId" to "episode-binding", "attemptCount" to 2, "activeAttemptId" to "attempt-episode",
            "nextRetryEpochMs" to 4000L, "priority" to 4, "leaseOwner" to "authored-worker", "leaseExpiresEpochMs" to 62_000L,
            "lastProgressEpochMs" to 2000L, "automaticRetryCount" to 1) +
            if (version >= 2) mapOf("cleanupPending" to true, "removeWhenClean" to true) else emptyMap())
        legacyDownload("job-second", "music-2", "NEEDS_FOREGROUND", mapOf("executionBindingId" to "second-unplayable", "attemptCount" to 1))
        legacyAttempt("attempt-music-history", "job-music", "music-new", mapOf("status" to "SUCCEEDED"))
        legacyAttempt("attempt-episode", "job-episode", "episode-binding", mapOf("number" to 2, "status" to "FINALIZING",
            "finishedEpochMs" to null, "bytesDownloaded" to 2048L, "totalBytes" to 4096L, "stagingPath" to "authored/staging/episode.part",
            "representationId" to "140", "etag" to "\"strong-validator\"", "lastModified" to "Mon, 02 Nov 2026 12:00:00 GMT",
            "validatorGeneration" to 2L, "backgroundTaskId" to "authored-native-task"))
        legacyAttempt("attempt-unbound", "job-second", null)
        insert("download_finalizations", mapOf("downloadId" to "job-episode", "mediaId" to "episode",
            "stagingPath" to "authored/staging/episode.part", "finalPath" to "authored/offline/episode.m4a", "sha256" to "b".repeat(64),
            "sizeBytes" to 2048L, "attemptId" to "attempt-episode", "mediaDurationMs" to 88_000L, "mimeType" to "audio/mp4",
            "codecs" to "aac", "executionBindingId" to "episode-binding", "representationId" to "140", "validatorGeneration" to 2L))
        insert("settings", mapOf("id" to 0, "language" to "ARABIC", "theme" to "DARK", "textScale" to 1.5, "offlineOnly" to true,
            "speed" to 1.25, "skipBackSeconds" to 10, "skipForwardSeconds" to 45, "downloadParallelism" to 4) +
            if (version >= 2) mapOf("crossfadeEnabled" to true, "crossfadeDurationMs" to 4500L) else emptyMap())
        insert("podcast_speeds", mapOf("podcastId" to "show", "speed" to 1.75))
        insert("navigation_state", mapOf("key" to "destination", "value" to "Library"))
    }

    private fun SQLiteConnection.seedTypedSingle() {
        insert("acquisition_requests", mapOf("id" to "typed-request", "kind" to "SOURCE_COLLECTION", "completeTitle" to "Full parent title", "capturedEpochMs" to 10L))
        insert("request_source_collection_metadata", mapOf("requestId" to "typed-request", "provider" to "YOUTUBE",
            "collectionId" to "PL-authored-single", "completeTitle" to "Full parent title"))
        insert("first_result_receipts", mapOf("id" to "typed-receipt", "origin" to "TYPED", "requestId" to "typed-request",
            "queryTitle" to "Full parent title", "rawOrdinal" to 0, "rawKind" to "VIDEO", "rawId" to "TTTTTTTTTTT",
            "rawTitle" to "Different returned recording", "rawPublicUrl" to "https://www.youtube.com/watch?v=TTTTTTTTTTT",
            "canonicalUrl" to "https://www.youtube.com/watch?v=TTTTTTTTTTT", "observedEpochMs" to 11L))
        insert("source_recordings", mapOf("id" to "typed-recording", "provider" to "YOUTUBE", "canonicalId" to "TTTTTTTTTTT", "canonicalUrl" to "https://www.youtube.com/watch?v=TTTTTTTTTTT"))
        insert("executable_subjects", mapOf("id" to "neutral-subject", "kind" to "SOURCE_RECORDING", "recordingId" to "typed-recording"))
        insert("execution_bindings", mapOf("id" to "typed-binding", "subjectId" to "neutral-subject", "firstReceiptId" to "typed-receipt", "recordingId" to "typed-recording", "origin" to "TYPED"))
        insert("acquisition_outcomes", mapOf("requestId" to "typed-request", "kind" to "SINGLE", "receiptId" to "typed-receipt", "bindingId" to "typed-binding"))
        insert("acquisition_jobs", mapOf("requestId" to "typed-request", "state" to "COMMITTED", "updatedEpochMs" to 11L))
    }

    private fun SQLiteConnection.seedCollection(retainHeaderlessPrefix: Boolean = false) {
        val url = "https://www.youtube.com/playlist?list=PL-authored"
        insert("acquisition_requests", mapOf("id" to "parent", "kind" to "SOURCE_COLLECTION", "completeTitle" to "Full collection title", "capturedEpochMs" to 10L))
        insert("request_source_collection_metadata", mapOf("requestId" to "parent", "provider" to "YOUTUBE", "collectionId" to "PL-authored", "completeTitle" to "Full collection title"))
        insert("first_result_receipts", mapOf("id" to "parent-receipt", "origin" to "TYPED", "requestId" to "parent", "queryTitle" to "Full collection title",
            "rawOrdinal" to 0, "rawKind" to "PLAYLIST", "rawId" to "PL-authored", "rawTitle" to "Observed listing title", "rawPublicUrl" to url, "canonicalUrl" to url, "observedEpochMs" to 11L))
        insert("acquisition_outcomes", mapOf("requestId" to "parent", "kind" to "COLLECTION", "receiptId" to "parent-receipt", "collectionKind" to "PLAYLIST",
            "collectionId" to "PL-authored", "collectionCanonicalUrl" to url, "collectionScope" to "PLAYLIST_MEMBERS"))
        insert("acquisition_jobs", mapOf("requestId" to "parent", "state" to "COMMITTED", "updatedEpochMs" to 11L))
        val summary = mapOf("traversal" to "OBSERVED_TO_END", "observedMemberCount" to 3, "nonPlayableMemberCount" to 1)
        insert("collection_runs", mapOf("id" to "run", "parentRequestId" to "parent", "parentReceiptId" to "parent-receipt", "collectionKind" to "PLAYLIST",
            "collectionId" to "PL-authored", "collectionCanonicalUrl" to url, "scope" to "PLAYLIST_MEMBERS", "nextPageIndex" to 1, "nextOrdinal" to 3,
            "knownHoleCount" to 1, "retryableFailuresAtFrontier" to 0, "startedEpochMs" to 12L, "updatedEpochMs" to 13L) + summary)
        if (retainHeaderlessPrefix) insert("collection_snapshots", mapOf("id" to "headerless-snapshot", "runId" to "run",
            "observationCaptured" to false, "traversal" to "INCOMPLETE", "observedMemberCount" to 0,
            "nonPlayableMemberCount" to 0, "problemCode" to "SOURCE_FAILURE", "sourceProblemCode" to "NETWORK", "sealedEpochMs" to 12L))
        insert("collection_observations", mapOf("runId" to "run", "parentRequestId" to "parent", "collectionKind" to "PLAYLIST", "collectionId" to "PL-authored",
            "collectionCanonicalUrl" to url, "scope" to "PLAYLIST_MEMBERS", "title" to "Observed listing title", "titleOrigin" to "PLAYLIST_METADATA",
            "sourceUrl" to url, "consistency" to "UNVERSIONED_OBSERVATION"))
        insert("collection_identity_evidence", mapOf("runId" to "run", "evidence" to "PLAYLIST_METADATA"))
        insert("collection_page_commits", mapOf("runId" to "run", "pageIndex" to 0, "startOrdinal" to 0, "memberCount" to 3, "receivedEpochMs" to 13L) + summary)
        repeat(3) { ordinal ->
            val row = mapOf("runId" to "run", "ordinal" to ordinal, "pageIndex" to 0, "raw_ordinal" to ordinal,
                "raw_kind" to "VIDEO", "raw_id" to if (ordinal == 1) null else "MMMMMMMMMMM",
                "raw_title" to if (ordinal == 1) "Unavailable item" else "Complete repeated member title",
                "raw_publicUrl" to if (ordinal == 1) null else "https://www.youtube.com/watch?v=MMMMMMMMMMM",
                "raw_issue" to if (ordinal == 1) "UNAVAILABLE" else null)
            insert("collection_members", row + if (ordinal == 1) emptyMap() else mapOf(
                "display_id" to "MMMMMMMMMMM", "display_title" to "Complete repeated member title",
                "display_publicUrl" to "https://www.youtube.com/watch?v=MMMMMMMMMMM", "display_kind" to "VIDEO"))
        }
        insert("collection_snapshots", mapOf("id" to "snapshot", "runId" to "run", "observationCaptured" to true, "lastCommittedPageIndex" to 0, "sealedEpochMs" to 14L) + summary)
        repeat(3) { ordinal -> insert("collection_snapshot_members", mapOf("snapshotId" to "snapshot", "ordinal" to ordinal, "runId" to "run", "sourceOrdinal" to ordinal)) }
    }
}
