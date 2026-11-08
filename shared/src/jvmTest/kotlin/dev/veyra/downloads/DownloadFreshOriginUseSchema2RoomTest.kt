package dev.veyra.downloads

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.data.room.*
import dev.veyra.model.*
import dev.veyra.playback.*
import dev.veyra.source.execution.AppSourceResolver
import kotlinx.coroutines.*
import okio.ByteString.Companion.toByteString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Real exported schema2 migration + real owner; source/native decoding use authored NON-AUDIO fakes. */
class DownloadFreshOriginUseSchema2RoomTest {
    @Test fun knownSchema2FreshTransferPublishesOnlyItsRecordedExecutableRequestedBinding() = acquisitionDownloadRoomCase {
        val seed = seedOriginSchema2(OriginBinding.EXECUTABLE)
        val owner = owner()
        val before = owner.store.acquisitionView()
        val producer = assertIs<PhysicalDownloadProducer.Schema2>(before.acquisition.physicalDownloads.getValue(seed.jobId).producer)
        val claim = before.acquisition.claims.values.single()
        assertEquals(producer.bindingId, claim.requestedBindingId)
        assertNotNull(producer.recordingId)
        assertTrue(before.acquisition.assetUses.isEmpty(), "Migration alone cannot create a validated use")
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val after = awaitOriginUsePublication(owner, seed.jobId).acquisition
        val use = assertOnlyOriginUse(after, seed.jobId)
        assertEquals(ExecutionBindingId(assertNotNull(seed.bindingId)), use.requestedBindingId)
        assertEquals(before.acquisition.claims, after.claims)
        assertEquals(before.snapshot.playbackBindings, owner.store.acquisitionView().snapshot.playbackBindings)
        before.acquisition.receipts.forEach { (id, receipt) -> assertEquals(receipt, after.receipts[id]) }
        assertEquals(1, owner.legacy.preparations.size)
        assertEquals(1, owner.legacy.transfers.size)
        assertEquals(1, owner.validations.size)
        assertTrue(owner.gateway.preparations.isEmpty())
        assertTrue(owner.selector.requests.isEmpty())
        assertTrue(after.requests.isEmpty(), "Fresh schema2 execution never fabricates a typed request or recaptures today's title")
    }

    @Test fun knownSchema2RecoveryWithNoHistoricalAttemptGrantsOnlyItsRecordedOriginAfterValidation() = acquisitionDownloadRoomCase {
        val seed = seedOriginSchema2(OriginBinding.EXECUTABLE, finalization = true)
        val old = owner()
        val before = old.store.acquisitionView().acquisition
        val intent = before.physicalFinalizations.getValue(seed.jobId)
        assertNull(intent.attemptId)
        assertNull(intent.assetId)
        assertEquals(0L, intent.ownershipGeneration)
        assertTrue(before.physicalAttempts.isEmpty())
        assertTrue(before.assetUses.isEmpty())
        old.coordinator.close()
        old.store.close()
        val resumed = owner()
        offline(resumed)
        resumed.coordinator.initializeScheduling()
        assertTrue(resumed.validations.isEmpty())
        resumed.coordinator.start(DownloadActivation.DURABLE_WORK)
        val after = awaitOriginUsePublication(resumed, seed.jobId).acquisition
        val use = assertOnlyOriginUse(after, seed.jobId)
        val asset = after.materializations.getValue(use.materializationAssetId)
        assertEquals(MaterializationAssetId("${seed.jobId}-${intent.validatorGeneration}"), asset.id)
        assertEquals(intent.producer, asset.producer)
        assertEquals(seed.jobId, asset.materializerDownloadId)
        assertNull(asset.materializerAttemptId)
        assertEquals(0L, asset.ownershipGeneration, "A live maintenance lease is not the historical materializer generation")
        assertTrue(after.physicalDownloads.getValue(seed.jobId).ownershipGeneration > asset.ownershipGeneration)
        assertEquals(intent.sha256, asset.sha256)
        assertEquals(intent.finalPath, asset.uri)
        assertEquals(before.claims, after.claims)
        assertTrue(after.physicalAttempts.isEmpty())
        assertTrue(after.physicalFinalizations.isEmpty())
        assertEquals(1, resumed.validations.size)
        assertEquals(intent.stagingPath, resumed.validations.single().uri)
        assertTrue(resumed.gateway.preparations.isEmpty())
        assertTrue(resumed.selector.requests.isEmpty())
        resumed.legacy.assertUnused()
    }

    @Test fun unknownSchema2FinalizationCannotInventAnOriginBindingOrUse() =
        unknownOriginFinalization(OriginBinding.ABSENT)

    @Test fun nonExecutableSchema2FinalizationCannotTurnItsHistoricalReceiptIntoAUse() =
        unknownOriginFinalization(OriginBinding.NON_EXECUTABLE)

    @Test fun adoptedSchema2NullRequestedBindingDoesNotAcquireAnInventedFreshOriginUse() =
        adoptedHistoricalClaim(OriginBinding.ABSENT)

    @Test fun adoptedSchema2DifferentHistoricalRequestedBindingDoesNotAcquireTheNewProducerUse() =
        adoptedHistoricalClaim(OriginBinding.NON_EXECUTABLE)

    @Test fun schema3LegacyResolverHashNegativeRevokesSameOwnerEvidenceWithNullableHistoricalAttempt() = acquisitionDownloadRoomCase {
        val seed = seedOriginSchema2(OriginBinding.EXECUTABLE, finalization = true)
        val owner = owner()
        offline(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val before = awaitOriginUsePublication(owner, seed.jobId)
        val asset = before.acquisition.materializations.values.single()
        assertNull(asset.materializerAttemptId)
        assertEquals(0L, asset.ownershipGeneration)
        val checks = owner.materializationEvidence.checks.value
        val c0 = checks.successfulChecks.getValue(seed.jobId)
        awaitCondition { owner.coordinator.acquisitionPresentation.value.states[seed.jobId]?.status == DownloadStatus.COMPLETE }
        val oldPresentation = owner.coordinator.acquisitionPresentation.value
        var nativeCalls = 0
        val resolver = AppSourceResolver(owner.store, files, AssetValidator { nativeCalls++; AssetValidation.Valid(733) },
            owner.legacy, clock, owner.materializationEvidence)
        try {
            Files.write(Path.of(asset.uri), PHYSICAL_BYTES.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
            assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> {
                resolver.resolveWithBinding(before.snapshot.media.getValue(seed.mediaId), EngineCapabilities(),
                    offlineOnly = true, reuseBindingId = seed.bindingId)
            }.failure.code)
            assertEquals(0, nativeCalls)
            assertSame(c0, owner.materializationEvidence.checks.value.successfulChecks.getValue(seed.jobId))
            assertFalse(seed.jobId in owner.materializationEvidence.checks.value.currentValidAssets)
            assertEquals(before, owner.store.acquisitionView(), "No forbidden legacy Room edit or invented typed request/use")
            awaitCondition { owner.coordinator.acquisitionPresentation.value.states[seed.jobId]?.status == DownloadStatus.VERIFYING }
            assertSame(c0, owner.coordinator.acquisitionPresentation.value.successfulChecks.getValue(seed.jobId))
            assertEquals(DownloadStatus.COMPLETE, oldPresentation.states.getValue(seed.jobId).status)
            assertEquals(asset, checks.currentValidAssets.getValue(seed.jobId))
            assertTrue(before.acquisition.requests.isEmpty())
            assertTrue(owner.gateway.preparations.isEmpty())
            assertTrue(owner.selector.requests.isEmpty())
            owner.legacy.assertUnused()
        } finally { resolver.closeReaders() }
    }

    @Test fun schema3LegacyExplicitNativeNegativeRevokesBeforeRealCloseMasksItsFailure() = acquisitionDownloadRoomCase {
        val seed = seedOriginSchema2(OriginBinding.EXECUTABLE)
        val owner = owner()
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitOriginUsePublication(owner, seed.jobId)
        offline(owner)
        val before = owner.store.acquisitionView()
        val asset = before.acquisition.materializations.values.single()
        val c0 = owner.materializationEvidence.checks.value.successfulChecks.getValue(seed.jobId)
        val masking = MaskingMaterializationLease(this, owner, seed.jobId)
        val resolver = AppSourceResolver(owner.store, masking.files,
            AssetValidator { AssetValidation.Invalid(AppFailure(FailureCode.UNSUPPORTED_CODEC, "authored_legacy_native_negative")) },
            owner.legacy, clock, owner.materializationEvidence)
        try {
            assertEquals(FailureCode.STORAGE_IO, assertFailsWith<VeyraException> {
                resolver.resolveWithBinding(before.snapshot.media.getValue(seed.mediaId), EngineCapabilities(),
                    offlineOnly = true, reuseBindingId = seed.bindingId)
            }.failure.code)
            assertTrue(masking.revokedBeforeClose.get())
            assertEquals(1, masking.closes.get())
            assertSame(c0, owner.materializationEvidence.checks.value.successfulChecks.getValue(seed.jobId))
            assertFalse(seed.jobId in owner.materializationEvidence.checks.value.currentValidAssets)
            assertEquals(before, owner.store.acquisitionView())
            assertNotNull(files.tryLeasePaths(listOf(asset.uri))).close()
            assertEquals(1, owner.legacy.transfers.size)
            assertTrue(owner.gateway.preparations.isEmpty())
        } finally {
            assertEquals(FailureCode.STORAGE_IO, assertFailsWith<VeyraException> { resolver.closeReaders() }.failure.code)
        }
    }

    @Test fun schema3LegacyOnlyRealPostHashNegativeRevokesAfterGenericCancellationAndHealthyChecks() = acquisitionDownloadRoomCase {
        val seed = seedOriginSchema2(OriginBinding.EXECUTABLE)
        val owner = owner()
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitOriginUsePublication(owner, seed.jobId)
        offline(owner)
        val before = owner.store.acquisitionView()
        val checks = owner.materializationEvidence.checks.value
        val item = before.snapshot.media.getValue(seed.mediaId)
        val failure = AppFailure(FailureCode.CORRUPT_MEDIA, "authored_thrown_not_a_primitive_negative")
        var native: suspend (OfflineAsset) -> AssetValidation = { throw VeyraException(failure) }
        val resolver = AppSourceResolver(owner.store, files, AssetValidator { native(it) }, owner.legacy, clock, owner.materializationEvidence)
        try {
            assertEquals(failure, assertFailsWith<VeyraException> {
                resolver.resolveWithBinding(item, EngineCapabilities(), offlineOnly = true, reuseBindingId = seed.bindingId)
            }.failure)
            assertSame(checks, owner.materializationEvidence.checks.value)
            val held = barrier()
            native = { held.stop(); AssetValidation.Valid(733) }
            supervisorScope {
                val pending = async { resolver.resolveWithBinding(item, EngineCapabilities(), offlineOnly = true, reuseBindingId = seed.bindingId) }
                try {
                    held.entered.await()
                    pending.cancelAndJoin()
                    assertFailsWith<CancellationException> { pending.await() }
                    assertSame(checks, owner.materializationEvidence.checks.value)
                } finally { held.release(); pending.cancelAndJoin() }
            }
            native = { AssetValidation.Valid(733) }
            assertTrue(resolver.resolveWithBinding(item, EngineCapabilities(), offlineOnly = true, reuseBindingId = seed.bindingId).isLocal)
            assertSame(checks, owner.materializationEvidence.checks.value)
            assertEquals(before, owner.store.acquisitionView())
            native = { asset ->
                Files.write(Path.of(asset.uri), PHYSICAL_BYTES.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
                AssetValidation.Valid(733)
            }
            assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> {
                resolver.resolveWithBinding(item, EngineCapabilities(), offlineOnly = true, reuseBindingId = seed.bindingId)
            }.failure.code)
            assertFalse(seed.jobId in owner.materializationEvidence.checks.value.currentValidAssets)
            assertSame(checks.successfulChecks.getValue(seed.jobId), owner.materializationEvidence.checks.value.successfulChecks.getValue(seed.jobId))
            assertEquals(before, owner.store.acquisitionView())
            assertTrue(before.acquisition.requests.isEmpty())
            assertEquals(1, owner.legacy.transfers.size)
            assertTrue(owner.gateway.preparations.isEmpty())
            assertTrue(owner.selector.requests.isEmpty())
        } finally { resolver.closeReaders() }
    }

    private fun unknownOriginFinalization(binding: OriginBinding) = acquisitionDownloadRoomCase {
        val seed = seedOriginSchema2(binding, finalization = true)
        val owner = owner()
        val before = owner.store.acquisitionView()
        val producer = assertIs<PhysicalDownloadProducer.Schema2>(before.acquisition.physicalDownloads.getValue(seed.jobId).producer)
        assertNull(producer.recordingId)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        idle(owner)
        val after = owner.store.acquisitionView()
        assertTrue(after.acquisition.assetUses.isEmpty())
        assertTrue(after.acquisition.materializations.isEmpty())
        assertTrue(after.acquisition.materializationReceipts.isEmpty())
        assertEquals(before.acquisition.physicalFinalizations, after.acquisition.physicalFinalizations)
        assertEquals(before.acquisition.physicalAttempts, after.acquisition.physicalAttempts)
        assertEquals(before.acquisition.claims, after.acquisition.claims)
        assertEquals(before.acquisition.receipts, after.acquisition.receipts)
        assertEquals(producer, after.acquisition.physicalDownloads.getValue(seed.jobId).producer)
        assertTrue(owner.validations.isEmpty())
        assertTrue(owner.gateway.preparations.isEmpty())
        assertTrue(owner.selector.requests.isEmpty())
        owner.legacy.assertUnused()
    }

    private fun adoptedHistoricalClaim(binding: OriginBinding) = acquisitionDownloadRoomCase {
        val seed = seedOriginSchema2(binding)
        val owner = owner()
        val before = owner.store.acquisitionView()
        val historicalClaim = before.acquisition.claims.values.single()
        val media = before.snapshot.media.getValue(seed.mediaId)
        val request = authoredRequest(newEntityId("authored-explicit-origin-adoption"), MediaCapture(media))
            .copy(capturedEpochMs = clock.epochMs())
        val selected = accept(owner, request, authoredSingle(request))
        val newBinding = assertIs<AcquisitionOutcome.Single>(selected.outcome).bindingId
        assertNotEquals(historicalClaim.requestedBindingId, newBinding)
        offline(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        assertEquals(seed.jobId, owner.coordinator.adoptSchema2(seed.jobId, request.id, newBinding) { })
        val adopted = owner.store.acquisitionView().acquisition
        assertEquals(historicalClaim.requestedBindingId, adopted.claims.getValue(historicalClaim.id).requestedBindingId)
        assertEquals(newBinding, assertIs<PhysicalDownloadProducer.Typed>(adopted.physicalDownloads.getValue(seed.jobId).producer).bindingId)
        offline(owner, enabled = false)
        awaitState(owner.store) { it.physicalDownloads.getValue(seed.jobId).status == DownloadStatus.COMPLETE }
        idle(owner)
        val after = owner.store.acquisitionView().acquisition
        assertTrue(after.assetUses.isEmpty(), "Producer replacement cannot rewrite a null/different historical claim into a requested R1")
        val claim = after.claims.getValue(historicalClaim.id)
        assertEquals(historicalClaim.requestedBindingId, claim.requestedBindingId)
        assertEquals(historicalClaim.owner, claim.owner)
        assertEquals(DownloadBatchIntent.ACTIVE, claim.intent)
        assertEquals(newBinding, after.materializations.values.single().producer.bindingId)
        before.acquisition.receipts.forEach { (id, receipt) -> assertEquals(receipt, after.receipts[id]) }
        assertEquals(before.snapshot.playbackBindings, owner.store.acquisitionView().snapshot.playbackBindings)
        assertEquals(1, owner.gateway.preparations.size)
        assertEquals(1, owner.gateway.transfers.size)
        assertEquals(1, owner.validations.size)
        assertTrue(owner.selector.requests.isEmpty())
        owner.legacy.assertUnused()
    }

    private enum class OriginBinding { EXECUTABLE, NON_EXECUTABLE, ABSENT }
    private data class OriginSchema2Seed(val mediaId: String, val jobId: String, val bindingId: String?)

    /** Creates only exported schema2 rows. Canonical migration must derive every schema3 edge. */
    private fun DownloadAcquisitionRoomFixture.seedOriginSchema2(
        bindingKind: OriginBinding,
        finalization: Boolean = false,
    ): OriginSchema2Seed {
        check(!Files.exists(databasePath))
        val media = "authored-origin-schema2-media"
        val job = "authored-origin-schema2-job"
        val binding = "authored-origin-schema2-binding".takeUnless { bindingKind == OriginBinding.ABSENT }
        val video = "LMNOPQRSTUV"
        val executable = bindingKind == OriginBinding.EXECUTABLE
        val part = if (finalization) files.prepareStaging(media, "authored-schema2-origin-stage").toString() else null
        val finalPath = if (finalization) files.finalPath(media, 7, "audio/mpeg", "authored-schema2-origin-stage").toString() else null
        if (part != null) Files.write(Path.of(part), PHYSICAL_BYTES)
        withOriginSchema2Helper(databasePath) { helper -> helper.createDatabase(2).use { db ->
            db.execSQL("BEGIN IMMEDIATE")
            try {
                listOf("MUSIC", "PODCAST").forEach { context -> db.insertOriginSeed("queue_contexts", mapOf(
                    "id" to context, "positionMs" to 0L, "shuffle" to false, "repeat" to "OFF", "speed" to 1.0, "hasOriginalOrder" to false)) }
                db.insertOriginSeed("store_meta", mapOf("id" to 0, "revision" to 7L, "activeContext" to "MUSIC"))
                db.insertOriginSeed("settings", mapOf("id" to 0, "language" to "ARABIC", "theme" to "DARK", "textScale" to 1.0,
                    "offlineOnly" to false, "speed" to 1.0, "skipBackSeconds" to 15, "skipForwardSeconds" to 30,
                    "crossfadeEnabled" to false, "crossfadeDurationMs" to 3000L, "downloadParallelism" to 1))
                db.insertOriginSeed("media", mapOf("id" to media, "title" to "Today's different full title — عنوان اليوم",
                    "kind" to "MUSIC", "creator" to "Authored creator", "description" to "Authored metadata only", "explicit" to false,
                    "source_provider" to "COMMONS", "source_canonicalId" to "authored-origin-schema2-catalog-id"))
                db.insertOriginSeed("tracks", mapOf("mediaId" to media))
                if (binding != null) db.insertOriginSeed("playback_bindings", mapOf("id" to binding, "mediaId" to media,
                    "queryTitle" to "Historical unchanged complete title — العنوان التاريخي",
                    "firstResultKind" to if (executable) "VIDEO" else "PLAYLIST",
                    "firstResultId" to if (executable) video else "PLauthored-original-collection",
                    "firstResultTitle" to "Historical first result",
                    "firstResultUrl" to if (executable) "https://www.youtube.com/watch?v=$video"
                        else "https://www.youtube.com/playlist?list=PLauthored-original-collection",
                    "selectedVideoId" to video.takeIf { executable }, "resolvedEpochMs" to 900L, "selectionOrdinal" to 0))
                db.insertOriginSeed("downloads", mapOf("id" to job, "mediaId" to media,
                    "status" to if (finalization) "VERIFYING" else "QUEUED",
                    "bytesDownloaded" to if (finalization) PHYSICAL_BYTES.size.toLong() else 0L,
                    "totalBytes" to PHYSICAL_BYTES.size.toLong().takeIf { finalization },
                    "stagingPath" to part, "representationId" to PHYSICAL_REPRESENTATION.representationId.takeIf { finalization },
                    "validatorGeneration" to if (finalization) 7L else 0L,
                    "updatedEpochMs" to 2_000L, "executionBindingId" to binding,
                    "attemptCount" to 0, "priority" to 0, "createdEpochMs" to 1_000L, "userPaused" to false, "automaticRetryCount" to 0))
                if (finalization) db.insertOriginSeed("download_finalizations", mapOf("downloadId" to job, "mediaId" to media,
                    "stagingPath" to assertNotNull(part), "finalPath" to assertNotNull(finalPath),
                    "sha256" to PHYSICAL_BYTES.toByteString().sha256().hex(), "sizeBytes" to PHYSICAL_BYTES.size.toLong(),
                    "attemptId" to null, "mediaDurationMs" to 733L, "mimeType" to "audio/mpeg", "codecs" to "mp3",
                    "executionBindingId" to binding, "representationId" to PHYSICAL_REPRESENTATION.representationId, "validatorGeneration" to 7L))
                db.execSQL("COMMIT")
            } catch (failure: Throwable) {
                try { db.execSQL("ROLLBACK") } catch (rollback: Throwable) { if (rollback !== failure) failure.addSuppressed(rollback) }
                throw failure
            }
        } }
        return OriginSchema2Seed(media, job, binding)
    }

    private fun withOriginSchema2Helper(path: Path, block: (MigrationTestHelper) -> Unit) {
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

    private fun SQLiteConnection.insertOriginSeed(table: String, values: Map<String, Any?>) {
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
}
