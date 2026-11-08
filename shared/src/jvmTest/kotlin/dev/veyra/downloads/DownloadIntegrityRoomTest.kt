package dev.veyra.downloads

import dev.veyra.model.*
import dev.veyra.playback.AssetValidation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Typed schema-3 integrity ports. Real Room/SQLite, canonical commands, owned files and OS
 * leases; transfers/decoder answers are AUTHORED fakes over non-audio bytes. Legacy
 * `DownloadFixture` snapshot seeding is deliberately not used: typed history is immutable.
 */
class DownloadIntegrityRoomTest {
    @Test fun missingCompletedFileIsNeverReadyAndExplicitRetryRetainsItsRecording() =
        damagedCompletedFile(CompletedFileDamage.MISSING)

    @Test fun truncatedCompletedFileIsNeverReadyAndExplicitRetryRetainsItsRecording() =
        damagedCompletedFile(CompletedFileDamage.TRUNCATED)

    @Test fun nonregularCompletedPathIsNeverReadyAndExplicitRetryRetainsItsRecording() =
        damagedCompletedFile(CompletedFileDamage.NONREGULAR)

    @Test fun invalidCompletedMaterializationLinksFailBeforeNativeValidationWithoutLosingTheAsset() {
        CompletedMaterializationLink.entries.forEach { defect ->
            executorSchema3Case {
                val first = owner()
                val (id, _) = enqueue(first)
                first.coordinator.start(DownloadActivation.USER_SESSION)
                val completed = complete(first, id)
                val original = assertNotNull(completed.asset)
                first.coordinator.close()
                closeStore(first.store)
                val field = when (defect) {
                    CompletedMaterializationLink.DIFFERENT_REPRESENTATION -> PersistedMaterializationField.REPRESENTATION
                    CompletedMaterializationLink.CHANGED_VALIDATOR_GENERATION -> PersistedMaterializationField.VALIDATOR_GENERATION
                    CompletedMaterializationLink.CHANGED_OWNERSHIP_GENERATION -> PersistedMaterializationField.OWNERSHIP_GENERATION
                    CompletedMaterializationLink.MISSING_MATERIALIZER_ATTEMPT -> PersistedMaterializationField.MATERIALIZER_ATTEMPT
                }
                val value: Any = when (defect) {
                    CompletedMaterializationLink.DIFFERENT_REPRESENTATION -> "authored-replaced-representation"
                    CompletedMaterializationLink.CHANGED_VALIDATOR_GENERATION -> original.validatorGeneration + 1
                    CompletedMaterializationLink.CHANGED_OWNERSHIP_GENERATION -> original.ownershipGeneration + 1
                    CompletedMaterializationLink.MISSING_MATERIALIZER_ATTEMPT -> "authored-missing-materializer"
                }
                corruptMaterialization(databasePath, original.id.value, field, value)
                val second = owner(store())
                val invalid = assertNotNull(second.store.acquisitionView().acquisition.materializations[original.id],
                    "$defect: corrupt link must stay a readable canonical asset")
                second.coordinator.start(DownloadActivation.USER_SESSION)
                val failed = awaitJob(second, id) { it.job.status == DownloadStatus.FAILED && it.job.leaseOwner == null }
                assertEquals(FailureCode.CORRUPT_MEDIA, failed.job.failure?.code, "$defect")
                assertEquals(0, second.validations.size, "$defect must be rejected before the decoder")
                assertEquals(invalid, failed.asset, "$defect must retain the exact durable asset row")
                assertEquals(invalid, second.store.acquisitionView().acquisition.materializations[original.id], "$defect")
                assertNull(failed.finalization, "$defect")
                assertTrue(Files.exists(Path.of(invalid.uri)), "$defect must retain owned bytes")
                assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(invalid.uri)), "$defect")
                assertEquals(invalid.receipt(), second.store.acquisitionView().acquisition.materializationReceipts[invalid.id], "$defect")
                assertTrue(second.store.acquisitionView().acquisition.assetUses.any {
                    it.materializationAssetId == invalid.id && it.requestedBindingId == invalid.producer.bindingId
                }, "$defect must not delete the historical exact use")
                assertTrue(second.gateway.prepareCalls.isEmpty(), "$defect")
                assertTrue(second.gateway.transfers.isEmpty(), "$defect")
                assertTrue(second.gateway.prepareCalls.isEmpty() && second.selector.requests.isEmpty(), "$defect")
                second.legacy.assertUnused()
            }
        }
        // The remaining legacy-only shapes (unselected video, non-video, unbound) cannot exist as a
        // legal typed materialization at all. Canonical graph validation is the real earlier guard;
        // assert it fails closed, without native work, and preserves the owned bytes. The later
        // typed VALIDATE/CORRUPT_MEDIA and cleanup paths are therefore NOT exercised for these
        // three shapes, and are not claimed as exercised.
        LegacyCompletedShape.entries.forEach { shape ->
            executorSchema3Case {
                val first = owner()
                val (id, selected) = enqueue(first)
                first.coordinator.start(DownloadActivation.USER_SESSION)
                val completed = complete(first, id)
                val asset = assertNotNull(completed.asset)
                val nativeValidations = first.validations.size
                val transfers = first.gateway.transfers.size
                val preparations = first.gateway.prepareCalls.size
                // The canonical graph guard will make every later flush fail closed; this is the
                // expected typed close diagnostic, set BEFORE the negative path so teardown cannot
                // hide a primary assertion.
                first.expectedCloseFailure = AppFailure(FailureCode.INVALID_STATE)
                val other = if (shape == LegacyCompletedShape.DIFFERENT_BINDING)
                    select(first, item("integrity-other-completed-binding"), "BBBBBBBBBBB") else null
                when (shape) {
                    LegacyCompletedShape.UNSELECTED_VIDEO -> corruptTypedReceiptRawId(databasePath, selected.context.binding.id.value)
                    LegacyCompletedShape.NON_VIDEO -> corruptTypedReceiptRawKind(databasePath,
                        selected.context.binding.id.value, AcquisitionEntryKind.PLAYLIST.name)
                    LegacyCompletedShape.UNBOUND -> corruptTypedBindingRecording(databasePath, selected.context.binding.id.value)
                    LegacyCompletedShape.DIFFERENT_BINDING -> corruptMaterializationBinding(databasePath,
                        asset.id.value, assertNotNull(other).context.binding.id.value)
                    LegacyCompletedShape.MISSING_REPRESENTATION -> corruptMaterialization(databasePath,
                        asset.id.value, PersistedMaterializationField.REPRESENTATION, null)
                    LegacyCompletedShape.BLANK_REPRESENTATION -> corruptMaterialization(databasePath,
                        asset.id.value, PersistedMaterializationField.REPRESENTATION, "  ")
                }
                assertFailsWith<IllegalArgumentException>("$shape must be rejected by the canonical graph guard") {
                    first.store.acquisitionView()
                }
                assertEquals(nativeValidations, first.validations.size, "$shape must not reach native validation")
                assertEquals(transfers, first.gateway.transfers.size, "$shape must not transfer")
                assertEquals(preparations, first.gateway.prepareCalls.size, "$shape must not prepare a source")
                assertTrue(Files.exists(Path.of(asset.uri)), "$shape must retain owned bytes")
                assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(asset.uri)), "$shape")
            }
        }
    }

    @Test fun invalidFinalizationLinksFailBeforeNativeValidationWithoutLosingPublishedOrStagedBytes() {
        for (published in listOf(false, true)) {
            FinalizationMaterializationLink.entries.forEach { defect ->
                executorSchema3Case {
                    val owner = owner()
                    val (id, _) = enqueue(owner)
                    val intent = recoveryIntent(owner, id, published = published)
                    advance(1_100) // The authored foreign finalizer lease is expired; its OS locks are already released.
                    val field = when (defect) {
                        FinalizationMaterializationLink.DIFFERENT_REPRESENTATION -> PersistedFinalizationField.REPRESENTATION
                        FinalizationMaterializationLink.MISSING_REPRESENTATION -> PersistedFinalizationField.REPRESENTATION
                        FinalizationMaterializationLink.BLANK_REPRESENTATION -> PersistedFinalizationField.REPRESENTATION
                        FinalizationMaterializationLink.CHANGED_VALIDATOR_GENERATION -> PersistedFinalizationField.VALIDATOR_GENERATION
                    }
                    val value: Any? = when (defect) {
                        FinalizationMaterializationLink.DIFFERENT_REPRESENTATION -> "authored-replaced-representation"
                        FinalizationMaterializationLink.MISSING_REPRESENTATION -> null
                        FinalizationMaterializationLink.BLANK_REPRESENTATION -> "  "
                        FinalizationMaterializationLink.CHANGED_VALIDATOR_GENERATION -> intent.validatorGeneration + 1
                    }
                    corruptFinalization(databasePath, id, field, value)
                    val graph = owner.store.acquisitionView().acquisition
                    val corrupted = assertNotNull(graph.physicalFinalizations[id])
                    assertEquals(intent.producer, corrupted.producer, "$defect")
                    assertEquals(intent.stagingPath, corrupted.stagingPath, "$defect")
                    assertEquals(intent.finalPath, corrupted.finalPath, "$defect")
                    owner.coordinator.start(DownloadActivation.DURABLE_WORK)
                    val failed = awaitJob(owner, id) { it.job.status == DownloadStatus.FAILED && it.job.leaseOwner == null }
                    assertEquals(FailureCode.CORRUPT_MEDIA, failed.job.failure?.code,
                        "$defect published=$published")
                    assertNull(failed.asset, "$defect published=$published")
                    assertEquals(corrupted, failed.finalization, "$defect must retain its journal")
                    assertEquals(0, owner.validations.size, "$defect must be rejected before the decoder")
                    assertEquals(1, owner.selector.requests.size, "$defect must not re-query")
                    assertTrue(owner.gateway.prepareCalls.isEmpty(), "$defect")
                    assertTrue(owner.gateway.transfers.isEmpty(), "$defect")
                    assertTrue(owner.store.acquisitionView().acquisition.materializations.isEmpty(), "$defect")
                    val retained = if (published) intent.finalPath else intent.stagingPath
                    assertTrue(Files.exists(Path.of(retained)), "$defect must retain its owned bytes")
                    assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(retained)), "$defect")
                    if (!published) assertFalse(Files.exists(Path.of(intent.finalPath)), "$defect must not rename")
                    if (published) assertFalse(Files.exists(Path.of(intent.stagingPath)), "$defect")
                }
            }
        }
        // Typed finalization has two further link identities that the canonical graph validates
        // before any recovery/native/cleanup stage. Preserve those negative paths explicitly and
        // state that the later materialization path was not reached.
        executorSchema3Case {
            val owner = owner()
            val (id, _) = enqueue(owner)
            val intent = recoveryIntent(owner, id)
            advance(1_100)
            corruptFinalization(databasePath, id, PersistedFinalizationField.ASSET_ID, null)
            assertFailsWith<IllegalArgumentException>("Missing typed finalization asset identity must fail closed") {
                owner.store.acquisitionView()
            }
            assertTrue(owner.validations.isEmpty())
            assertTrue(owner.gateway.transfers.isEmpty())
            assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(intent.stagingPath)))
            assertFalse(Files.exists(Path.of(intent.finalPath)))
        }
        executorSchema3Case {
            val owner = owner()
            val (id, _) = enqueue(owner)
            val other = select(owner, item("integrity-other-recording"), "BBBBBBBBBBB")
            val intent = recoveryIntent(owner, id)
            advance(1_100)
            corruptFinalizationBinding(databasePath, id, other.context.binding.id.value)
            assertFailsWith<IllegalArgumentException>("Replaced typed finalization binding must fail closed") {
                owner.store.acquisitionView()
            }
            assertTrue(owner.validations.isEmpty())
            assertTrue(owner.gateway.transfers.isEmpty())
            assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(intent.stagingPath)))
            assertFalse(Files.exists(Path.of(intent.finalPath)))
        }
    }

    @Test fun changedBindingValueUnderTheSameIdInvalidatesTheExactCompletedCheckReceipt() = executorSchema3Case {
        val first = owner()
        val (id, _) = enqueue(first)
        first.coordinator.start(DownloadActivation.USER_SESSION)
        val completed = complete(first, id)
        val original = assertNotNull(completed.asset)
        val preparationsBefore = first.gateway.prepareCalls.size
        val transfersBefore = first.gateway.transfers.size
        val rechecking = barrier()
        first.gate.beforeAcquire = { rechecking.stop() }
        // Canonical typed history is immutable; this is a real-SQLite external replacement probe,
        // not a legal update. It replaces the asset identity under the SAME asset id.
        val revision = corruptMaterialization(databasePath, original.id.value,
            PersistedMaterializationField.REPRESENTATION, "authored-externally-replaced-representation")
        first.store.acquisitionView()
        withTimeout(3_000) { first.coordinator.acquisitionPresentation.first { it.view.snapshot.revision >= revision } }
        rechecking.entered.await()
        val projected = first.coordinator.acquisitionPresentation.value
        assertEquals(DownloadStatus.VERIFYING, projected.states[id]?.status,
            "A replaced identity under the same id cannot inherit the old Ready map")
        assertEquals(original, first.materializationEvidence.checks.value.successfulChecks[id]?.asset,
            "The historical check names the exact replaced value")
        assertFalse(id in projected.successfulChecks, "A matching asset id is not a check of its replaced value")
        assertEquals(DownloadStatus.COMPLETE, join(first, id).job.status,
            "The durable completion is not rewritten by an external corruption until rechecked")
        rechecking.release()
        val failed = awaitJob(first, id) { it.job.status == DownloadStatus.FAILED && it.job.leaseOwner == null }
        assertEquals(FailureCode.CORRUPT_MEDIA, failed.job.failure?.code)
        assertEquals(1, first.validations.size,
            "The replaced identity must be rejected before an additional decoder validation")
        assertEquals("authored-externally-replaced-representation", failed.asset?.representationId)
        assertEquals(original.id, failed.asset?.id)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(assertNotNull(failed.asset).uri)))
        assertNull(failed.finalization)
        assertFalse(id in first.coordinator.acquisitionPresentation.value.successfulChecks)
        assertEquals(preparationsBefore, first.gateway.prepareCalls.size)
        assertEquals(transfersBefore, first.gateway.transfers.size)
        assertTrue(first.selector.requests.size == 1)
        first.legacy.assertUnused()
    }

    @Test fun removedAndRecreatedDownloadIdsCannotInheritAStaleReadyMapOrCheckedReceipt() = executorSchema3Case {
        val owner = owner()
        val (id, selected) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.USER_SESSION)
        val first = complete(owner, id)
        val stale = assertNotNull(first.asset)
        owner.coordinator.remove(id)
        val removed = awaitJob(owner, id) { !it.job.cleanupPending && it.asset == null }
        assertEquals(DownloadStatus.CANCELLED, removed.job.status)
        assertNull(removed.finalization)
        assertFalse(Files.exists(Path.of(stale.uri)))
        assertNull(owner.materializationEvidence.checks.value.successfulChecks[id],
            "Removal of the exact live asset must forget its process-only check")
        assertEquals(stale.receipt(), owner.store.acquisitionView().acquisition.materializationReceipts[stale.id],
            "Immutable historical receipt survives live-byte removal")
        assertTrue(owner.store.acquisitionView().acquisition.assetUses.any { it.materializationAssetId == stale.id })
        val secondRun = barrier()
        owner.gate.beforeAcquire = { secondRun.stop() }
        owner.coordinator.enqueueAccepted(selected.record.request.id, selected.context.binding.id) { }
        owner.coordinator.resume(id)
        secondRun.entered.await()
        val recreated = join(owner, id)
        assertNull(recreated.asset, "The recreated same download id must not inherit the old asset")
        assertNull(recreated.finalization)
        assertEquals(stale.id, owner.store.acquisitionView().acquisition.materializationReceipts.keys.single(),
            "Only history, never a Ready materialization, survives the ABA")
        assertTrue(owner.store.acquisitionView().acquisition.materializations.isEmpty())
        assertNull(owner.materializationEvidence.checks.value.successfulChecks[id])
        secondRun.release()
        val second = complete(owner, id)
        val replacement = assertNotNull(second.asset)
        assertNotEquals(stale.id, replacement.id)
        assertNotEquals(stale.uri, replacement.uri)
        assertFalse(Files.exists(Path.of(stale.uri)))
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(replacement.uri)))
        assertEquals(2, owner.validations.size, "The recreated generation needs its own current-process check")
        assertEquals(replacement, owner.materializationEvidence.checks.value.successfulChecks[id]?.asset)
        assertEquals(2, owner.gateway.transfers.size)
        assertEquals(1, owner.selector.requests.size, "Recreation reuses the durable accepted recording")
        assertEquals(1, owner.store.acquisitionView().acquisition.materializations.size)
        assertEquals(2, owner.store.acquisitionView().acquisition.assetUses.size)
        owner.legacy.assertUnused()
    }

    @Test fun bindingChangedDuringCompletedValidationCannotPublishACheckForTheReplacement() = executorSchema3Case {
        val first = owner()
        val (id, selected) = enqueue(first)
        first.coordinator.start(DownloadActivation.USER_SESSION)
        val completed = complete(first, id)
        val original = assertNotNull(completed.asset)
        first.coordinator.close()
        closeStore(first.store)
        val second = owner(store())
        val validating = barrier()
        second.validation = { withContext(NonCancellable) { validating.stop() }; AssetValidation.Valid(733) }
        second.coordinator.start(DownloadActivation.USER_SESSION)
        validating.entered.await()
        // A legal current request/claim replacement. It shares the subject but has another
        // recording, so it cannot rename or commit against the in-flight materialization.
        val replacement = select(second, item(), "BBBBBBBBBBB")
        val rejected = assertFailsWith<VeyraException> {
            second.coordinator.enqueueAccepted(replacement.record.request.id, replacement.context.binding.id) { }
        }
        assertEquals(FailureCode.INVALID_STATE, rejected.failure.code)
        assertEquals("download_has_different_recording", rejected.failure.detail)
        val unchanged = second.store.acquisitionView().acquisition
        assertEquals(1, unchanged.physicalDownloads.size)
        assertEquals(selected.context.binding.id, unchanged.physicalDownloads.getValue(id).producer.bindingId)
        assertEquals(setOf(original.id), unchanged.materializations.keys)
        assertEquals(1, unchanged.assetUses.size)
        validating.release()
        val ready = awaitJob(second, id) {
            it.job.status == DownloadStatus.COMPLETE && it.asset?.id == original.id &&
                it.job.leaseOwner == null && it.activeAttempt == null && it.finalization == null
        }
        awaitCondition {
            second.materializationEvidence.checks.value.successfulChecks[id]?.asset == original
        }
        assertEquals(original.id, ready.asset?.id)
        assertEquals(selected.context.binding.id, ready.asset?.producer?.bindingId)
        assertEquals(original, second.materializationEvidence.checks.value.successfulChecks[id]?.asset)
        assertEquals(1, second.validations.size)
        assertTrue(second.gateway.prepareCalls.isEmpty(), "A replacement check cannot prepare a source")
        assertTrue(second.gateway.transfers.isEmpty(), "A replacement check cannot transfer")
        assertEquals(listOf(replacement.record.request), second.selector.requests)
        assertEquals(1, second.store.acquisitionView().acquisition.materializations.size)
        assertEquals(1, second.store.acquisitionView().acquisition.assetUses.size)
        second.legacy.assertUnused()
    }

    @Test fun bindingChangedDuringFinalizationValidationCannotRenameOrCommitAgainstTheReplacement() = executorSchema3Case {
        val owner = owner()
        val (id, selected) = enqueue(owner)
        val intent = recoveryIntent(owner, id)
        advance(1_100) // Expire only the authored foreign finalizer's canonical lease.
        val validating = barrier()
        owner.validation = { withContext(NonCancellable) { validating.stop() }; AssetValidation.Valid(733) }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        validating.entered.await()
        val replacement = select(owner, item(), "BBBBBBBBBBB")
        val rejected = assertFailsWith<VeyraException> {
            owner.coordinator.enqueueAccepted(replacement.record.request.id, replacement.context.binding.id) { }
        }
        assertEquals(FailureCode.INVALID_STATE, rejected.failure.code)
        assertEquals("download_has_different_recording", rejected.failure.detail)
        val held = join(owner, id)
        assertEquals(intent, held.finalization)
        assertNull(held.asset)
        assertTrue(Files.exists(Path.of(intent.stagingPath)))
        assertFalse(Files.exists(Path.of(intent.finalPath)))
        validating.release()
        val ready = complete(owner, id)
        assertEquals(intent.finalPath, ready.asset?.uri)
        assertEquals(selected.context.binding.id, ready.asset?.producer?.bindingId)
        assertNull(ready.finalization)
        assertFalse(Files.exists(Path.of(intent.stagingPath)))
        assertTrue(Files.exists(Path.of(intent.finalPath)))
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(intent.finalPath)))
        assertEquals(1, owner.validations.size)
        assertTrue(owner.gateway.prepareCalls.isEmpty())
        assertTrue(owner.gateway.transfers.isEmpty())
        assertEquals(listOf(selected.record.request, replacement.record.request), owner.selector.requests)
        assertEquals(1, owner.store.acquisitionView().acquisition.materializations.size)
        assertEquals(1, owner.store.acquisitionView().acquisition.assetUses.size)
        owner.legacy.assertUnused()
    }
}

private enum class CompletedFileDamage { MISSING, TRUNCATED, NONREGULAR }

/**
 * The same real completed generation is damaged on disk, then a distinct owner revalidates it.
 * The explicit retry uses the canonical remove-then-readmit-the-same-accepted-receipt path: it
 * deletes the old generation BEFORE the fresh transfer, proves no new title query, and retains
 * the original binding/representation. It is not a silent Resume of asset-backed bytes.
 */
private fun damagedCompletedFile(damage: CompletedFileDamage): Unit = executorSchema3Case {
    val first = owner()
    val (id, selected) = enqueue(first)
    first.coordinator.start(DownloadActivation.USER_SESSION)
    val completed = complete(first, id)
    val old = assertNotNull(completed.asset)
    val context = selected.context
    first.coordinator.close()
    closeStore(first.store)
    when (damage) {
        CompletedFileDamage.MISSING -> Files.delete(Path.of(old.uri))
        CompletedFileDamage.TRUNCATED -> Files.write(Path.of(old.uri), authoredBytes.copyOf(authoredBytes.size / 2))
        CompletedFileDamage.NONREGULAR -> {
            Files.delete(Path.of(old.uri))
            Files.createDirectory(Path.of(old.uri))
        }
    }
    val second = owner(store())
    second.store.acquisitionView()
    withTimeout(3_000) {
        second.coordinator.physicalStates.first { it[id]?.status == DownloadStatus.VERIFYING }
    }
    second.coordinator.start(DownloadActivation.USER_SESSION)
    val failed = awaitJob(second, id) { it.job.status == DownloadStatus.FAILED && it.job.leaseOwner == null }
    assertEquals(FailureCode.CORRUPT_MEDIA, failed.job.failure?.code, "$damage")
    assertEquals(old, failed.asset, "$damage must not lose the durable asset reference")
    assertEquals(0, second.validations.size, "$damage must fail before the decoder")
    assertEquals(clock.epochMs() + 5_000, failed.job.nextRetryEpochMs, "$damage bounded local recheck")
    assertTrue(second.gateway.prepareCalls.isEmpty(), "$damage")
    assertTrue(second.gateway.transfers.isEmpty(), "$damage")
    assertTrue(second.selector.requests.isEmpty(), "$damage")
    advance(5_000)
    val cleaning = barrier()
    second.beforeDelete = { cleaning.stop() }
    coroutineScope {
        val removing = async { second.coordinator.remove(id) }
        cleaning.entered.await()
        val cleanup = join(second, id)
        assertTrue(cleanup.job.cleanupPending, "$damage removal intent is durable before native release")
        assertEquals(old, cleanup.asset, "$damage keeps the prior asset reference until cleanup")
        assertEquals(context.binding.id, cleanup.job.producer.bindingId, "$damage keeps the exact recording")
        assertFalse(removing.isCompleted, "$damage removal waits for the real release callback")
        cleaning.release()
        removing.await()
    }
    awaitJob(second, id) { !it.job.cleanupPending && it.asset == null }
    assertFalse(Files.exists(Path.of(old.uri)), "$damage old generation must be deleted before reacquisition")
    second.beforeDelete = { }
    second.coordinator.enqueueAccepted(context.request.id, context.binding.id) { }
    second.coordinator.resume(id)
    val ready = complete(second, id)
    val replacement = assertNotNull(ready.asset)
    assertNotEquals(old.uri, replacement.uri, "$damage replacement generation must be new")
    assertFalse(Files.exists(Path.of(old.uri)), "$damage old generation stays deleted")
    assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(replacement.uri)), "$damage")
    assertEquals(1, second.gateway.transfers.size, "$damage one repair transfer")
    assertEquals(context, second.gateway.transfers.single().context, "$damage reuses the exact accepted recording")
    assertTrue(second.selector.requests.isEmpty(), "$damage repair must reuse the durable receipt, not search again")
    second.coordinator.remove(id)
    awaitJob(second, id) { !it.job.cleanupPending && it.asset == null }
    assertFalse(Files.exists(Path.of(replacement.uri)), "$damage final removal deletes owned bytes")
    second.legacy.assertUnused()
}

private enum class CompletedMaterializationLink {
    DIFFERENT_REPRESENTATION,
    CHANGED_VALIDATOR_GENERATION,
    CHANGED_OWNERSHIP_GENERATION,
    MISSING_MATERIALIZER_ATTEMPT,
}

/** Legacy binding/link shapes that typed immutable history cannot legally contain. */
private enum class LegacyCompletedShape {
    UNSELECTED_VIDEO,
    NON_VIDEO,
    UNBOUND,
    DIFFERENT_BINDING,
    MISSING_REPRESENTATION,
    BLANK_REPRESENTATION,
}

private enum class FinalizationMaterializationLink {
    DIFFERENT_REPRESENTATION,
    MISSING_REPRESENTATION,
    BLANK_REPRESENTATION,
    CHANGED_VALIDATOR_GENERATION,
}
