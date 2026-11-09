package dev.veyra.acquisition

import dev.veyra.data.LibraryTransactionOwner
import kotlin.test.*

/** Constructor-only ownership checks: no executor, source, clock or persistence calls. */
class AcquisitionOwnershipTest {
    private class Executor(override val transactionOwner: LibraryTransactionOwner) : AcquisitionDownloadExecutor {
        var calls = 0
        override suspend fun admitMember(command: AdmitAcquisitionDownload): AcquisitionDownloadAdmission { calls++; error("not invoked") }
        override suspend fun reconcileClaim(command: ReconcileAcquisitionDownload): AcquisitionDownloadReconciliation { calls++; error("not invoked") }
        override suspend fun validateAssetUse(command: ValidateAcquisitionDownloadAsset): AcquisitionDownloadAssetCheck { calls++; error("not invoked") }
    }

    @Test fun queueCoordinatorRejectsASelectionCoordinatorFromAnotherRoomOwner() {
        val local = AcquisitionTestRepository(); val foreign = AcquisitionTestRepository()
        val gateway = AcquisitionTestGateway()
        val selection = AcquisitionCoordinator(foreign, gateway, offline = { false })
        assertFailsWith<IllegalArgumentException> { QueueAcquisitionCoordinator(local, selection) }
        assertTrue(gateway.acquisitions.isEmpty())
        assertEquals(0, local.initializeCalls)
        assertEquals(0, foreign.initializeCalls)
    }

    @Test fun batchCoordinatorRejectsASelectionCoordinatorFromAnotherRoomOwner() {
        val local = AcquisitionTestRepository(); val foreign = AcquisitionTestRepository()
        val gateway = AcquisitionTestGateway()
        val selection = AcquisitionCoordinator(foreign, gateway, offline = { false })
        assertFailsWith<IllegalArgumentException> { DownloadBatchCoordinator(local, selection) }
        assertTrue(gateway.acquisitions.isEmpty())
        assertTrue(local.batchAdmissions.isEmpty())
    }

    @Test fun adapterRequiresSelectionAndExecutorToShareTheRepositorysExactOwner() {
        val local = AcquisitionTestRepository(); val foreign = AcquisitionTestRepository()
        val gateway = AcquisitionTestGateway()
        val ownSelection = AcquisitionCoordinator(local, gateway, offline = { false })
        val otherSelection = AcquisitionCoordinator(foreign, gateway, offline = { false })
        val ownExecutor = Executor(local.transactionOwner); val otherExecutor = Executor(foreign.transactionOwner)
        assertFailsWith<IllegalArgumentException> { AcquisitionDownloadAdapter(local, ownSelection, otherExecutor) }
        assertFailsWith<IllegalArgumentException> { AcquisitionDownloadAdapter(local, otherSelection, ownExecutor) }
        AcquisitionDownloadAdapter(local, ownSelection, ownExecutor)
        assertEquals(0, ownExecutor.calls)
        assertEquals(0, otherExecutor.calls)
        assertTrue(gateway.acquisitions.isEmpty())
    }
}
