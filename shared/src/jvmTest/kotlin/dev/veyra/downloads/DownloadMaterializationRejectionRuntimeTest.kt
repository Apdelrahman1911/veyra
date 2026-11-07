package dev.veyra.downloads

import dev.veyra.acquisition.*
import dev.veyra.model.*
import dev.veyra.playback.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Existing runtime and REAL files/OS ownership; canonical commands/source/native are explicit fakes. */
class DownloadMaterializationRejectionRuntimeTest {
    @Test fun actualExplicitNegativeRevokesBeforeDelayedCleanupAndReturnsExactAdmittedStamp() = physicalRuntimeCase {
        val asset = seedComplete()
        val before = runtime.checkedMaterializations.value
        val close = barrier()
        val failure = AppFailure(FailureCode.CORRUPT_MEDIA, "authored_native_negative")
        validation = { AssetValidation.Invalid(failure) }
        gate.beforeClose = { close.stop() }
        supervisorScope {
            val pending = async { runtime.validateAssetUse(ValidateAcquisitionDownloadAsset(memberR2, claimR2, asset.id)) }
            try {
                close.entered.await()
                assertFalse(pending.isCompleted)
                assertNotNull(currentJob().leaseOwner)
                assertFalse(runtime.epochs.allLocalOwnersConsumed(), "A rejection is not an owner-drain ACK")
                assertRevoked(before, asset)
                close.release()
                val unavailable = assertIs<AcquisitionDownloadAssetCheck.Unavailable>(pending.await())
                assertEquals(failure, unavailable.failure)
                val stamp = assertNotNull(unavailable.validationRejection)
                assertTrue(materializationEvidence.owns(stamp))
                assertSame(before.successfulChecks.getValue(PhysicalRuntimeFixture.JOB), stamp.successfulCheck)
                assertEquals(memberR2, stamp.member)
                assertEquals(claimR2, stamp.expectedClaim)
                assertEquals(memberR2.requestId, stamp.requestId)
                assertEquals(memberR2.requestedBindingId, stamp.requestedBindingId)
                assertEquals(PhysicalRuntimeFixture.JOB, stamp.downloadId)
                assertEquals(asset, stamp.asset)
            } finally { close.release(); pending.cancelAndJoin() }
        }
        assertTrue(repository.state.value.assetUses.isEmpty(), "Negative R2 check cannot mint durable use")
        assertTrue(runtime.epochs.allLocalOwnersConsumed())
        assertEquals(1, gateway.transfers.size)
    }

    @Test fun disabledChecksRejectWithoutStampMutationOrNativeWork() = physicalRuntimeCase {
        val asset = seedComplete()
        val before = runtime.checkedMaterializations.value
        hostState.value = hostState.value.copy(checksActivated = false)
        val failed = assertIs<AcquisitionDownloadAssetCheck.Unavailable>(runtime.validateAssetUse(
            ValidateAcquisitionDownloadAsset(memberR2, claimR2, asset.id)))
        assertNull(failed.validationRejection)
        assertSame(before, runtime.checkedMaterializations.value)
        assertEquals(1, validated.size)
        assertTrue(repository.maintenanceClaims.isEmpty())
    }

    @Test fun malformedExactMemberIsRejectedAfterLeaseButBeforeAnyPrimitiveEvidence() = physicalRuntimeCase {
        val asset = seedComplete()
        val before = runtime.checkedMaterializations.value
        val command = ValidateAcquisitionDownloadAsset(memberR2.copy(occurrenceId = ExecutionOccurrenceId("wrong-occurrence")),
            claimR2, asset.id)
        val rejected = assertIs<AcquisitionDownloadAssetCheck.Unavailable>(runtime.validateAssetUse(command))
        assertEquals(FailureCode.INVALID_STATE, rejected.failure.code)
        assertNull(rejected.validationRejection)
        assertSame(before, runtime.checkedMaterializations.value)
        assertEquals(1, validated.size)
        assertTrue(repository.uses.isEmpty())
        assertEquals(DownloadStatus.COMPLETE, currentJob().status)
    }

    @Test fun busySecondCommandCannotBorrowTheFirstCommandsActualRejectionReceipt() = physicalRuntimeCase {
        val asset = seedComplete()
        val before = runtime.checkedMaterializations.value
        val native = barrier()
        validation = { native.stop(); AssetValidation.Invalid(AppFailure(FailureCode.CORRUPT_MEDIA)) }
        supervisorScope {
            val first = async { runtime.validateAssetUse(ValidateAcquisitionDownloadAsset(memberR2, claimR2, asset.id)) }
            try {
                native.entered.await()
                val second = assertIs<AcquisitionDownloadAssetCheck.Unavailable>(runtime.validateAssetUse(
                    ValidateAcquisitionDownloadAsset(memberR2, claimR2, asset.id)))
                assertNull(second.validationRejection)
                assertSame(before, runtime.checkedMaterializations.value)
                native.release()
                assertNotNull(assertIs<AcquisitionDownloadAssetCheck.Unavailable>(first.await()).validationRejection)
                assertRevoked(before, asset)
            } finally { native.release(); first.cancelAndJoin() }
        }
    }

    @Test fun downstreamCleanupFailureCannotMaskThePreviouslyRecordedTypedRejection() = physicalRuntimeCase {
        val asset = seedComplete()
        val before = runtime.checkedMaterializations.value
        val cleanupFailure = AppFailure(FailureCode.STORAGE_IO, "authored_post_cleanup_signal_failure")
        validation = { AssetValidation.Invalid(AppFailure(FailureCode.CORRUPT_MEDIA, "authored_native_negative")) }
        // The actual maintenance finally has consumed its owners before this downstream host callback.
        signalWork = { throw VeyraException(cleanupFailure) }
        val rejected = assertIs<AcquisitionDownloadAssetCheck.Unavailable>(runtime.validateAssetUse(
            ValidateAcquisitionDownloadAsset(memberR2, claimR2, asset.id)))
        signalWork = { }
        assertEquals(cleanupFailure, rejected.failure)
        assertNotNull(rejected.validationRejection)
        assertRevoked(before, asset)
        assertEquals(0, leases.open.get())
    }

    @Test fun cleanupOnlyFailureAfterPhysicalSuccessCarriesNoRejectionAndDoesNotInvalidateC1() = physicalRuntimeCase {
        val asset = seedComplete()
        val c0 = runtime.checkedMaterializations.value.successfulChecks.getValue(PhysicalRuntimeFixture.JOB)
        signalWork = { throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_cleanup_only")) }
        val rejected = assertIs<AcquisitionDownloadAssetCheck.Unavailable>(runtime.validateAssetUse(
            ValidateAcquisitionDownloadAsset(memberR2, claimR2, asset.id)))
        signalWork = { }
        assertNull(rejected.validationRejection)
        assertNotSame(c0, runtime.checkedMaterializations.value.successfulChecks.getValue(PhysicalRuntimeFixture.JOB))
        assertEquals(asset, runtime.checkedMaterializations.value.currentValidAssets.getValue(PhysicalRuntimeFixture.JOB))
        assertEquals(1, repository.state.value.assetUses.size)
    }

    @Test fun cancellationAfterActualNativeNegativeStillRevokesButCannotReturnAnUnavailableReceipt() = physicalRuntimeCase {
        val asset = seedComplete()
        val before = runtime.checkedMaterializations.value
        validation = {
            currentCoroutineContext().cancel(CancellationException("authored_cancel_after_native_negative"))
            AssetValidation.Invalid(AppFailure(FailureCode.CORRUPT_MEDIA, "authored_negative_before_cleanup"))
        }
        supervisorScope {
            var returned: AcquisitionDownloadAssetCheck? = null
            val pending = async { runtime.validateAssetUse(ValidateAcquisitionDownloadAsset(memberR2, claimR2, asset.id)).also { returned = it } }
            assertFailsWith<CancellationException> { pending.await() }
            assertNull(returned)
        }
        assertRevoked(before, asset)
        assertEquals(0, leases.open.get())
    }

    @Test fun standaloneRealHashFailureRevokesSharedValidityWithoutCreatingAnR2Use() = physicalRuntimeCase {
        val asset = seedComplete()
        val before = runtime.checkedMaterializations.value
        standaloneOnly(contextR2.binding.id)
        Files.write(Path.of(asset.uri), ByteArray(asset.sizeBytes.toInt()) { 0 })
        assertFailsWith<VeyraException> { runtime.validateStandaloneAssetUse(PhysicalRuntimeFixture.JOB, contextR2.binding.id) }
        assertRevoked(before, asset)
        assertTrue(repository.state.value.assetUses.isEmpty())
        assertEquals(1, validated.size, "Hash rejection occurred before another native validation")
        assertEquals(1, gateway.transfers.size)
    }

    @Test fun coldPhysicalRecheckFailureCannotReconstructHistoryOrCurrentValidity() = physicalRuntimeCase {
        val asset = seedComplete()
        val coldEvidence = DownloadMaterializationEvidence(repository.transactionOwner)
        val next = DownloadAcquisitionDependencies(repository, dependencies.gateway, dependencies.validator,
            dependencies.beforePhysicalDelete, dependencies.beforePhysicalAdoption, dependencies.requireOwnerAdmission,
            acquisition, batches, coldEvidence)
        val cold = DownloadPhysicalRuntime(repository, next, files, legacy, EngineCapabilities(), gate, clock,
            60_000, 10_000, host)
        validation = { AssetValidation.Invalid(AppFailure(FailureCode.UNSUPPORTED_CODEC, "authored_cold_invalid")) }
        cold.reconcilePass()
        assertTrue(cold.checkedMaterializations.value.currentValidAssets.isEmpty())
        assertTrue(cold.checkedMaterializations.value.successfulChecks.isEmpty())
        assertEquals(asset, repository.state.value.materializations.getValue(asset.id))
        assertEquals(1, gateway.transfers.size)
        assertEquals(2, validated.size)
        assertTrue(repository.state.value.assetUses.isEmpty())
    }

    private fun PhysicalRuntimeFixture.assertRevoked(before: DownloadMaterializationChecks, asset: MaterializationAsset) {
        val current = runtime.checkedMaterializations.value
        assertSame(before.successfulChecks.getValue(PhysicalRuntimeFixture.JOB), current.successfulChecks.getValue(PhysicalRuntimeFixture.JOB))
        assertFalse(PhysicalRuntimeFixture.JOB in current.currentValidAssets)
        assertEquals(asset, before.currentValidAssets.getValue(PhysicalRuntimeFixture.JOB))
    }
}
