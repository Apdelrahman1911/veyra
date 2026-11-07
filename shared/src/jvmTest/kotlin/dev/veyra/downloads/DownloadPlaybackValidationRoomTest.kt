package dev.veyra.downloads

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.playback.*
import kotlinx.coroutines.*
import okio.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real Room graph/publication and OS leases; supplied source/native answers and bytes are FAKE. */
class DownloadPlaybackValidationRoomTest {
    @Test fun typedHashRejectionRevokesPhysicalReadinessWithoutRoomWritesAndOnlyOwnerRecheckMintsC1() = acquisitionDownloadRoomCase {
        val owner = owner()
        val (id, accepted) = complete(owner)
        offline(owner)
        val before = owner.store.acquisitionView()
        val context = before.acquisition.executionContext(assertIs<AcquisitionOutcome.Single>(accepted.outcome).bindingId)
        val asset = before.acquisition.materializations.getValue(assertNotNull(before.acquisition.physicalDownloads.getValue(id).assetId))
        val checks = owner.materializationEvidence.checks.value
        val c0 = checks.successfulChecks.getValue(id)
        awaitCondition { owner.coordinator.acquisitionPresentation.value.states[id]?.status == DownloadStatus.COMPLETE }
        val oldPresentation = owner.coordinator.acquisitionPresentation.value
        val resolver = AcquisitionPlaybackResolver(owner.store, owner.gateway, files, owner.localValidator, owner.materializationEvidence)
        try {
            Files.write(Path.of(asset.uri), PHYSICAL_BYTES.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
            assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> {
                resolver.resolve(context, EngineCapabilities(), offlineOnly = true)
            }.failure.code)
            assertSame(c0, owner.materializationEvidence.checks.value.successfulChecks.getValue(id))
            assertFalse(id in owner.materializationEvidence.checks.value.currentValidAssets)
            assertEquals(before, owner.store.acquisitionView(), "A resolver negative cannot edit canonical Room lineage/status/uses")
            awaitCondition { owner.coordinator.acquisitionPresentation.value.states[id]?.status == DownloadStatus.VERIFYING }
            assertSame(c0, owner.coordinator.acquisitionPresentation.value.successfulChecks.getValue(id))
            assertEquals(DownloadStatus.COMPLETE, oldPresentation.states.getValue(id).status)
            assertEquals(asset, checks.currentValidAssets.getValue(id), "Previously observed immutable snapshots stay intact")

            Files.write(Path.of(asset.uri), PHYSICAL_BYTES)
            assertTrue(resolver.resolve(context, EngineCapabilities(), offlineOnly = true).source.isLocal)
            assertSame(c0, owner.materializationEvidence.checks.value.successfulChecks.getValue(id))
            assertFalse(id in owner.materializationEvidence.checks.value.currentValidAssets,
                "A healthy resolver invocation is not an owner successful-check mint")
            owner.coordinator.start(DownloadActivation.USER_SESSION)
            awaitCondition { owner.materializationEvidence.checks.value.currentValidAssets[id] == asset }
            idle(owner)
            assertNotSame(c0, owner.materializationEvidence.checks.value.successfulChecks.getValue(id))
            val after = owner.store.acquisitionView().acquisition
            assertEquals(before.acquisition.assetUses, after.assetUses)
            assertEquals(before.acquisition.materializations, after.materializations)
            assertEquals(before.acquisition.materializationReceipts, after.materializationReceipts)
            assertEquals(before.acquisition.physicalAttempts, after.physicalAttempts)
            assertEquals(1, owner.gateway.transfers.size)
            assertTrue(owner.selector.requests.isEmpty())
        } finally { resolver.close() }
    }

    @Test fun typedPostHashRejectionIsVisibleBeforeRealLeaseCloseMasksTheException() = acquisitionDownloadRoomCase {
        val owner = owner()
        val (id, accepted) = complete(owner)
        offline(owner)
        val before = owner.store.acquisitionView()
        val context = before.acquisition.executionContext(assertIs<AcquisitionOutcome.Single>(accepted.outcome).bindingId)
        val asset = before.acquisition.materializations.getValue(assertNotNull(before.acquisition.physicalDownloads.getValue(id).assetId))
        val c0 = owner.materializationEvidence.checks.value.successfulChecks.getValue(id)
        val masking = MaskingMaterializationLease(this, owner, id)
        val resolver = AcquisitionPlaybackResolver(owner.store, owner.gateway, masking.files, owner.localValidator, owner.materializationEvidence)
        owner.validation = { input ->
            Files.write(Path.of(input.uri), PHYSICAL_BYTES.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
            AssetValidation.Valid(733)
        }
        try {
            assertEquals(FailureCode.STORAGE_IO, assertFailsWith<VeyraException> {
                resolver.resolve(context, EngineCapabilities(), offlineOnly = true)
            }.failure.code)
            assertTrue(masking.revokedBeforeClose.get())
            assertEquals(1, masking.closes.get())
            assertSame(c0, owner.materializationEvidence.checks.value.successfulChecks.getValue(id))
            assertFalse(id in owner.materializationEvidence.checks.value.currentValidAssets)
            assertEquals(before, owner.store.acquisitionView())
            // The authored close reports failure AFTER releasing the genuine OS handle.
            // Acquisition by the independent original owner proves release, not resolver ACK.
            assertNotNull(files.tryLeasePaths(listOf(asset.uri))).close()
        } finally {
            assertEquals(FailureCode.STORAGE_IO, assertFailsWith<VeyraException> { resolver.close() }.failure.code)
        }
    }

    @Test fun typedExplicitNativeInvalidRevokesButIdenticallyCodedThrownFailureDoesNot() = acquisitionDownloadRoomCase {
        val owner = owner()
        val (id, accepted) = complete(owner)
        offline(owner)
        val before = owner.store.acquisitionView()
        val context = before.acquisition.executionContext(assertIs<AcquisitionOutcome.Single>(accepted.outcome).bindingId)
        val checks = owner.materializationEvidence.checks.value
        val failure = AppFailure(FailureCode.CORRUPT_MEDIA, "authored_native_same_code")
        val resolver = AcquisitionPlaybackResolver(owner.store, owner.gateway, files, owner.localValidator, owner.materializationEvidence)
        try {
            owner.validation = { throw VeyraException(failure) }
            assertEquals(failure, assertFailsWith<VeyraException> {
                resolver.resolve(context, EngineCapabilities(), offlineOnly = true)
            }.failure)
            assertSame(checks, owner.materializationEvidence.checks.value)
            owner.validation = { AssetValidation.Invalid(failure) }
            assertEquals(failure, assertFailsWith<VeyraException> {
                resolver.resolve(context, EngineCapabilities(), offlineOnly = true)
            }.failure)
            assertSame(checks.successfulChecks.getValue(id), owner.materializationEvidence.checks.value.successfulChecks.getValue(id))
            assertFalse(id in owner.materializationEvidence.checks.value.currentValidAssets)
            assertEquals(before, owner.store.acquisitionView())
            assertEquals(1, owner.gateway.transfers.size)
        } finally { resolver.close() }
    }

    @Test fun typedCancelledNativeWaitIsNotARejectionOrANewSuccessfulCheck() = acquisitionDownloadRoomCase {
        val owner = owner()
        val (id, accepted) = complete(owner)
        offline(owner)
        val before = owner.store.acquisitionView()
        val context = before.acquisition.executionContext(assertIs<AcquisitionOutcome.Single>(accepted.outcome).bindingId)
        val checks = owner.materializationEvidence.checks.value
        val native = barrier()
        owner.validation = { native.stop(); AssetValidation.Valid(733) }
        val resolver = AcquisitionPlaybackResolver(owner.store, owner.gateway, files, owner.localValidator, owner.materializationEvidence)
        try {
            supervisorScope {
                val pending = async { resolver.resolve(context, EngineCapabilities(), offlineOnly = true) }
                try {
                    native.entered.await()
                    pending.cancelAndJoin()
                    assertFailsWith<CancellationException> { pending.await() }
                    assertSame(checks, owner.materializationEvidence.checks.value)
                    assertEquals(before, owner.store.acquisitionView())
                    val asset = checks.currentValidAssets.getValue(id)
                    assertNotNull(files.tryLeasePaths(listOf(asset.uri))).close()
                } finally { native.release(); pending.cancelAndJoin() }
            }
        } finally { resolver.close() }
    }

    @Test fun sameOwnerHealthyResolutionAndBusyOrRepresentationGuardsDoNotChangePhysicalEvidence() = acquisitionDownloadRoomCase {
        val owner = owner()
        val (id, accepted) = complete(owner)
        offline(owner)
        val before = owner.store.acquisitionView()
        val context = before.acquisition.executionContext(assertIs<AcquisitionOutcome.Single>(accepted.outcome).bindingId)
        val checks = owner.materializationEvidence.checks.value
        val asset = checks.currentValidAssets.getValue(id)
        val foreign = DownloadMaterializationEvidence(LibraryTransactionOwner())
        assertFailsWith<IllegalArgumentException> {
            AcquisitionPlaybackResolver(owner.store, owner.gateway, files, owner.localValidator, foreign)
        }
        assertFailsWith<IllegalArgumentException> {
            dev.veyra.source.execution.AppSourceResolver(owner.store, files, AssetValidator { AssetValidation.Valid(733) },
                owner.legacy, clock, foreign)
        }
        assertFailsWith<IllegalArgumentException> {
            DownloadAcquisitionDependencies(owner.repository, owner.gateway, owner.localValidator, { }, { }, { },
                owner.acquisition, owner.batches, foreign)
        }
        val resolver = AcquisitionPlaybackResolver(owner.store, owner.gateway, files, owner.localValidator, owner.materializationEvidence)
        try {
            val local = resolver.resolve(context, EngineCapabilities(), offlineOnly = true)
            assertTrue(local.source.isLocal)
            assertEquals(asset, local.validatedMaterialization)
            assertSame(checks, owner.materializationEvidence.checks.value)
            val validations = owner.validations.size
            assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> {
                resolver.resolve(context, EngineCapabilities(), offlineOnly = true, requiredRepresentationId = "different-representation")
            }.failure.code)
            val lease = assertNotNull(files.tryLeasePaths(listOf(asset.uri)))
            try {
                assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> {
                    resolver.resolve(context, EngineCapabilities(), offlineOnly = true)
                }.failure.code)
            } finally { lease.close() }
            assertEquals(validations, owner.validations.size)
            assertSame(checks, owner.materializationEvidence.checks.value)
            assertEquals(before, owner.store.acquisitionView())
            assertEquals(1, owner.gateway.transfers.size)
            assertTrue(owner.selector.requests.isEmpty())
        } finally { resolver.close() }
    }

    @Test fun realAdapterPreservesExactNegativeReceiptWhenIntentChangesDuringHeldCleanup() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = enrollOriginUseBatch(owner)
        val id = owner.store.acquisitionView().acquisition.physicalDownloads.values.single().id
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitOriginUsePublication(owner, id)
        offline(owner)
        owner.coordinator.start(DownloadActivation.USER_SESSION)
        val before = owner.store.acquisitionView().acquisition
        val member = before.batches.getValue(batch.id).members.single()
        val claim = before.claims.getValue(assertNotNull(member.claimId))
        val key = AcquisitionDownloadMemberKey(batch.id, member.position, batch.intentRevision,
            member.occurrenceId, member.requestId, assertNotNull(member.requiredBindingId))
        val asset = before.materializations.getValue(assertNotNull(before.physicalDownloads.getValue(id).assetId))
        val c0 = owner.materializationEvidence.checks.value.successfulChecks.getValue(id)
        val closing = barrier()
        val validationsBefore = owner.validations.size
        owner.validation = { AssetValidation.Invalid(AppFailure(FailureCode.UNSUPPORTED_CODEC, "authored_native_negative")) }
        owner.gate.beforeClose = { if (owner.validations.size > validationsBefore) closing.stop() }
        supervisorScope {
            val pending = async { owner.adapter.validateMemberAsset(batch.id, member.position, batch.intentRevision, asset.id) }
            try {
                closing.entered.await()
                assertFalse(pending.isCompleted)
                assertFalse(id in owner.materializationEvidence.checks.value.currentValidAssets)
                assertNotNull(owner.store.acquisitionView().acquisition.physicalDownloads.getValue(id).leaseOwner,
                    "Synchronous rejection is not a maintenance/drain ACK")
                val changed = owner.batches.setIntent(batch.id, batch.intentRevision, DownloadBatchIntent.PAUSED)
                assertNotEquals(batch.intentRevision, changed.intentRevision)
                owner.gate.beforeClose = { }
                closing.release()
                val rejected = assertIs<AcquisitionDownloadAssetCheck.Unavailable>(pending.await())
                val receipt = assertNotNull(rejected.validationRejection)
                assertTrue(owner.materializationEvidence.owns(receipt))
                assertEquals(key, receipt.member)
                assertEquals(claim, receipt.expectedClaim)
                assertEquals(asset, receipt.asset)
                assertSame(c0, receipt.successfulCheck)
                assertSame(c0, owner.materializationEvidence.checks.value.successfulChecks.getValue(id))
                assertEquals(before.assetUses, owner.store.acquisitionView().acquisition.assetUses)
                assertEquals(DownloadBatchIntent.PAUSED, owner.store.acquisitionView().acquisition.batches.getValue(batch.id).intent)
                assertEquals(1, owner.gateway.transfers.size)
            } finally { owner.gate.beforeClose = { }; closing.release(); pending.cancelAndJoin() }
        }
    }
}

/** Fault seam owns a REAL OS path lease and releases it before reporting the authored failure. */
internal class MaskingMaterializationLease(fixture: DownloadAcquisitionRoomFixture,
    owner: DownloadAcquisitionRoomOwner, downloadId: String) {
    val revokedBeforeClose = AtomicBoolean(false)
    val closes = AtomicInteger()
    val files = DownloadFiles(fixture.directory.resolve("media").toString(), FileSystem.SYSTEM,
        DownloadFileLeaseFactory { path ->
            val actual = tryAcquireDownloadFileLease(path) ?: return@DownloadFileLeaseFactory null
            object : DownloadFileLease {
                override fun close() {
                    revokedBeforeClose.set(downloadId !in owner.materializationEvidence.checks.value.currentValidAssets)
                    actual.close()
                    closes.incrementAndGet()
                    throw IllegalStateException("Authored error AFTER genuine OS release; not a positive resolver ACK")
                }
            }
        })
}
