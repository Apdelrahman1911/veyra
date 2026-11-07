package dev.veyra.downloads

import dev.veyra.acquisition.*
import dev.veyra.model.*
import dev.veyra.playback.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Actual runtime/files/locks with the EXISTING fake Room commands, transfer and native gate. */
class DownloadMaterializationCheckRuntimeTest {
    @Test fun publicationAndRepeatedMemberSuccessRefreshReferencesForTheSameAssetAndUse() = physicalRuntimeCase {
        val asset = seedComplete()
        val published = currentCheck()
        assertEquals(asset, published.asset)
        assertTrue(repository.state.value.assetUses.isEmpty(), "The retaining R2 is not the producing R1")
        val first = assertIs<AcquisitionDownloadAssetCheck.Validated>(runtime.validateAssetUse(
            ValidateAcquisitionDownloadAsset(memberR2, claimR2, asset.id)))
        val rechecked = currentCheck()
        assertNotSame(published, rechecked)
        val uses = repository.state.value.assetUses
        val second = assertIs<AcquisitionDownloadAssetCheck.Validated>(runtime.validateAssetUse(
            ValidateAcquisitionDownloadAsset(memberR2, claimR2, asset.id)))
        assertNotSame(rechecked, currentCheck())
        assertEquals(first.validation.use, second.validation.use, "The authored clock and immutable use are unchanged")
        assertEquals(uses, repository.state.value.assetUses)
        assertEquals(asset, currentCheck().asset)
        assertEquals(asset, runtime.checkedMaterializations.value.assets.getValue(PhysicalRuntimeFixture.JOB))
        assertEquals(3, validated.size)
        assertEquals(1, gateway.transfers.size)
        assertTrue(runtime.epochs.allLocalOwnersConsumed())
        legacy.assertUnused()
    }

    @Test fun standaloneSuccessRefreshesTheSamePhysicalAssetWithoutRelabellingItsProducer() = physicalRuntimeCase {
        val asset = seedComplete()
        val before = currentCheck()
        standaloneOnly(contextR2.binding.id)
        val use = runtime.validateStandaloneAssetUse(PhysicalRuntimeFixture.JOB, contextR2.binding.id)
        assertNotSame(before, currentCheck())
        assertEquals(asset, currentCheck().asset)
        assertEquals(producer(contextR1), currentCheck().asset.producer)
        assertEquals(contextR2.binding.id, use.requestedBindingId)
        assertEquals(1, repository.standaloneUses.size)
        assertEquals(1, gateway.transfers.size)
    }

    @Test fun aNewRuntimeRechecksTheRetainedFileButDoesNotCreateTheMissingR2Use() = physicalRuntimeCase {
        val asset = seedComplete()
        val priorProcess = currentCheck()
        val restarted = coldRuntime()
        assertTrue(restarted.checkedMaterializations.value.assets.isEmpty())
        assertTrue(restarted.checkedMaterializations.value.successfulChecks.isEmpty())
        restarted.reconcilePass()
        val current = restarted.checkedMaterializations.value.successfulChecks.getValue(PhysicalRuntimeFixture.JOB)
        assertNotSame(priorProcess, current)
        assertEquals(asset, current.asset)
        assertEquals(asset, restarted.checkedMaterializations.value.assets.getValue(PhysicalRuntimeFixture.JOB))
        assertTrue(repository.state.value.assetUses.isEmpty(), "Automatic physical checking cannot grant the retaining R2")
        assertEquals(2, validated.size)
        assertEquals(1, gateway.transfers.size)
        assertTrue(restarted.epochs.allLocalOwnersConsumed())
    }

    @Test fun busyRejectionAndFailedSameAssetCheckKeepTheOldRefDespiteTheAdmissionGenerationAdvance() = physicalRuntimeCase {
        val asset = seedComplete()
        assertIs<AcquisitionDownloadAssetCheck.Validated>(runtime.validateAssetUse(
            ValidateAcquisitionDownloadAsset(memberR2, claimR2, asset.id)))
        val before = runtime.checkedMaterializations.value
        val generation = currentJob().ownershipGeneration
        val uses = repository.state.value.assetUses
        val failure = AppFailure(FailureCode.CORRUPT_MEDIA, "authored_recheck_refused")
        val native = barrier()
        validation = { native.stop(); AssetValidation.Invalid(failure) }
        supervisorScope {
            val pending = async { runtime.validateAssetUse(ValidateAcquisitionDownloadAsset(memberR2, claimR2, asset.id)) }
            try {
                native.entered.await()
                val busy = projectAcquisitionDownloadPresentation(coherentView(), runtime.checkedMaterializations.value, hostState.value)
                assertSame(before.successfulChecks.getValue(PhysicalRuntimeFixture.JOB),
                    busy.successfulChecks.getValue(PhysicalRuntimeFixture.JOB), "A tap during a lease must capture the old ref, not null")
                assertNotNull(busy.states.getValue(PhysicalRuntimeFixture.JOB).leaseOwner)
                val rejected = assertIs<AcquisitionDownloadAssetCheck.Unavailable>(runtime.validateAssetUse(
                    ValidateAcquisitionDownloadAsset(memberR2, claimR2, asset.id)))
                assertEquals("download_asset_check_not_admitted", rejected.failure.detail)
                assertSame(before, runtime.checkedMaterializations.value)
                native.release()
                assertEquals(failure, assertIs<AcquisitionDownloadAssetCheck.Unavailable>(pending.await()).failure)
            } finally { native.release(); pending.cancelAndJoin() }
        }
        assertNotSame(before, runtime.checkedMaterializations.value)
        assertSame(before.successfulChecks.getValue(PhysicalRuntimeFixture.JOB), currentCheck())
        assertTrue(runtime.checkedMaterializations.value.currentValidAssets.isEmpty())
        assertEquals(asset, before.currentValidAssets.getValue(PhysicalRuntimeFixture.JOB), "Displayed snapshots remain immutable")
        val released = projectAcquisitionDownloadPresentation(coherentView(), runtime.checkedMaterializations.value, hostState.value)
        assertSame(before.successfulChecks.getValue(PhysicalRuntimeFixture.JOB),
            released.successfulChecks.getValue(PhysicalRuntimeFixture.JOB), "Lease release cannot masquerade as a new successful check")
        assertTrue(currentJob().ownershipGeneration > generation, "Maintenance admission is not a successful check")
        assertEquals(DownloadStatus.FAILED, currentJob().status)
        assertEquals(uses, repository.state.value.assetUses)
        assertEquals(1, gateway.transfers.size)
    }

    @Test fun unrelatedRepositoryWritesAndRefusedAdmissionDoNotRefreshTheExistingCheck() = physicalRuntimeCase {
        val asset = seedComplete()
        val before = runtime.checkedMaterializations.value
        repository.state.value = repository.state.value.copy(libraryRevision = repository.state.value.libraryRevision + 1)
        runtime.reconcilePass()
        assertSame(before, runtime.checkedMaterializations.value)
        assertEquals(1, validated.size)
        hostState.value = hostState.value.copy(checksActivated = false)
        assertIs<AcquisitionDownloadAssetCheck.Unavailable>(runtime.validateAssetUse(
            ValidateAcquisitionDownloadAsset(memberR2, claimR2, asset.id)))
        assertSame(before, runtime.checkedMaterializations.value)
        assertTrue(repository.maintenanceClaims.isEmpty())
    }

    @Test fun cancellationWhileNativeValidationIsDelayedCannotMintANewCheck() = physicalRuntimeCase {
        val asset = seedComplete()
        val before = runtime.checkedMaterializations.value
        val native = barrier()
        validation = { withContext(NonCancellable) { native.stop() }; AssetValidation.Valid(733) }
        supervisorScope {
            val pending = async { runtime.validateAssetUse(ValidateAcquisitionDownloadAsset(memberR2, claimR2, asset.id)) }
            try {
                native.entered.await()
                assertSame(before, runtime.checkedMaterializations.value)
                pending.cancel()
                assertFalse(pending.isCompleted, "Cancellation cannot bypass the delayed native owner")
                native.release()
                assertFailsWith<CancellationException> { pending.await() }
            } finally { native.release(); pending.cancelAndJoin() }
        }
        assertSame(before, runtime.checkedMaterializations.value)
        assertTrue(repository.uses.isEmpty())
        assertTrue(repository.state.value.assetUses.isEmpty())
        assertTrue(runtime.epochs.allLocalOwnersConsumed())
    }

    @Test fun aDelayedStandaloneCheckCannotPublishAfterItsExactUseClaimChanges() = physicalRuntimeCase {
        val asset = seedComplete()
        val claim = standaloneOnly(contextR2.binding.id)
        val before = runtime.checkedMaterializations.value
        val native = barrier()
        validation = { native.stop(); AssetValidation.Valid(733) }
        supervisorScope {
            val pending = async { runtime.validateStandaloneAssetUse(PhysicalRuntimeFixture.JOB, contextR2.binding.id) }
            try {
                native.entered.await()
                assertSame(before, runtime.checkedMaterializations.value)
                val changed = claim.copy(generation = claim.generation + 1, ownerIntentRevision = claim.ownerIntentRevision + 1)
                repository.state.value = repository.state.value.copy(claims = mapOf(changed.id to changed))
                native.release()
                val error = assertFailsWith<VeyraException> { pending.await() }
                assertEquals("download_asset_use_stale", error.failure.detail)
            } finally { native.release(); pending.cancelAndJoin() }
        }
        assertSame(before, runtime.checkedMaterializations.value)
        assertEquals(1, repository.standaloneUses.size, "The existing fake's exact standalone CAS rejected the stale use")
        assertTrue(repository.state.value.assetUses.isEmpty())
        assertEquals(DownloadStatus.COMPLETE, currentJob().status, "R2 rejection does not corrupt R1")
    }

    @Test fun physicalSuccessReferenceIsNotAnAcknowledgementOfDelayedOwnerCleanup() = physicalRuntimeCase {
        val asset = seedComplete()
        val before = currentCheck()
        val nativeClose = barrier()
        gate.beforeClose = { nativeClose.stop() }
        coroutineScope {
            val pending = async { runtime.validateAssetUse(ValidateAcquisitionDownloadAsset(memberR2, claimR2, asset.id)) }
            try {
                nativeClose.entered.await()
                assertNotSame(before, currentCheck(), "Physical validation and exact use have succeeded")
                assertEquals(asset, currentCheck().asset)
                assertFalse(pending.isCompleted)
                assertNotNull(currentJob().leaseOwner)
                assertEquals(PhysicalDownloadMaintenanceKind.VALIDATE, currentJob().maintenanceKind)
                assertFalse(runtime.epochs.allLocalOwnersConsumed())
                nativeClose.release()
                assertIs<AcquisitionDownloadAssetCheck.Validated>(pending.await())
            } finally { nativeClose.release(); pending.cancelAndJoin() }
        }
        assertNull(currentJob().leaseOwner)
        assertNull(currentJob().maintenanceKind)
        assertTrue(runtime.epochs.allLocalOwnersConsumed())
    }

    @Test fun cleanupRetainsTheHistoricalRefWhileFencedThenActualDeletionRevokesBothAtomicEntries() = physicalRuntimeCase {
        val asset = seedComplete()
        val before = runtime.checkedMaterializations.value
        val removedClaim = claimR2.copy(intent = DownloadBatchIntent.CANCELLED, retainCompletedAsset = false,
            generation = 1, ownerIntentRevision = 1)
        repository.state.value = repository.state.value.copy(claims = mapOf(removedClaim.id to removedClaim),
            physicalDownloads = mapOf(currentJob().id to currentJob().copy(status = DownloadStatus.CANCELLED,
                userPaused = true, cleanupPending = true, removeWhenClean = true)))
        val reader = barrier()
        beforeDelete = { checked -> assertEquals(asset, checked); reader.stop() }
        coroutineScope {
            val pending = async { runtime.reconcilePass() }
            try {
                reader.entered.await()
                assertSame(before, runtime.checkedMaterializations.value, "No physical deletion has been acknowledged yet")
                val shown = projectAcquisitionDownloadPresentation(coherentView(), before, hostState.value)
                assertSame(before.successfulChecks.getValue(PhysicalRuntimeFixture.JOB),
                    shown.successfulChecks.getValue(PhysicalRuntimeFixture.JOB), "Cleanup readiness must not hide an old success identity")
                assertTrue(shown.states.getValue(PhysicalRuntimeFixture.JOB).cleanupPending)
                assertTrue(checkedPhysicalDownloadIds(shown.view.acquisition, before).isEmpty())
                assertTrue(Files.exists(Path.of(asset.uri)))
                reader.release()
                pending.await()
            } finally { reader.release(); pending.cancelAndJoin() }
        }
        assertTrue(runtime.checkedMaterializations.value.assets.isEmpty())
        assertTrue(runtime.checkedMaterializations.value.successfulChecks.isEmpty())
        assertEquals(asset, before.assets.getValue(PhysicalRuntimeFixture.JOB))
        assertFalse(Files.exists(Path.of(asset.uri)))
        assertTrue(repository.state.value.materializations.isEmpty())
    }

    private fun PhysicalRuntimeFixture.currentCheck() =
        runtime.checkedMaterializations.value.successfulChecks.getValue(PhysicalRuntimeFixture.JOB)

    private fun PhysicalRuntimeFixture.coldRuntime(): DownloadPhysicalRuntime {
        // Explicit replacement process evidence; reusing the app owner's registry is not restart.
        val next = DownloadAcquisitionDependencies(repository, dependencies.gateway, dependencies.validator,
            dependencies.beforePhysicalDelete, dependencies.beforePhysicalAdoption, dependencies.requireOwnerAdmission,
            acquisition, batches, DownloadMaterializationEvidence(repository.transactionOwner), dependencies.compatibilityPolicyVersion)
        return DownloadPhysicalRuntime(repository, next, files, legacy, EngineCapabilities(), gate, clock,
            leaseDurationMs = 60_000, heartbeatMs = 10_000, host = host)
    }
}
