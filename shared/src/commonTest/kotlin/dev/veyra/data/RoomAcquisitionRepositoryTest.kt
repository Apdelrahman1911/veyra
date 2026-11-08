package dev.veyra.data

import dev.veyra.acquisition.*
import dev.veyra.data.room.*
import dev.veyra.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * LibraryStore/facade ownership and publication tests using an explicit test-only persistence
 * double. These do NOT execute SQLite, its write-readback/commit guard, migrations or reopening.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomAcquisitionRepositoryTest {
    @Test fun allFacadesShareTheSameOwnerAndLocalReadsUseTheExistingBackend() = runTest {
        val backend = ProjectionPersistence()
        val store = LibraryStore(backend, StandardTestDispatcher(testScheduler))
        try {
            assertSame(store.transactionOwner, store.acquisition.transactionOwner)
            assertSame(store.transactionOwner, store.releases.transactionOwner)
            assertSame<Any>(store.acquisition, store.releases)
            assertEquals(0L, store.acquisition.initialize().libraryRevision)
            assertNull(store.acquisition.request(AcquisitionRequestId("not-stored")))
            assertNull(store.acquisition.physicalDownload("not-stored"))
            assertNull(store.releases.releaseDetail(MBID))
            assertEquals(1, backend.opens)
            assertEquals(4, backend.reads)
            assertEquals(0, backend.transactions)
            assertEquals(store.state.value.revision, store.acquisition.state.value.libraryRevision)
            assertEquals(store.state.value.revision, store.releases.releaseCatalog.value.libraryRevision)
        } finally { store.close() }
        assertEquals(1, backend.closes)
    }

    @Test fun commandAndLegacyScalarEditPublishOneCoherentRevisionFromTheBackendResult() = runTest {
        val backend = ProjectionPersistence()
        val store = LibraryStore(backend, StandardTestDispatcher(testScheduler))
        try {
            val request = authoredRequest()
            store.acquisition.capture(CaptureAcquisition(request))
            val lease = (store.acquisition.claim(ClaimAcquisition(request.id, AcquisitionAttemptToken("typed-lease"),
                "same-owner", 123_010, 1_000)) as AcquisitionClaimResult.Claimed).lease
            store.acquisition.commit(commandResult(request, lease))
            val binding = (store.acquisition.request(request.id)!!.outcome as AcquisitionOutcome.Single).bindingId
            val physical = store.acquisition.admitStandalonePhysicalDownload(AdmitStandalonePhysicalDownload(request.id,
                binding, "job", DownloadClaimId("claim"), 123_100)) as PhysicalDownloadCommit.Committed<StandalonePhysicalDownloadAdmission>
            assertEquals(4L, physical.libraryRevision)
            assertEquals(physical.libraryRevision, physical.value.physical.libraryRevision)
            store.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow()
            val view = store.acquisitionViews.value
            assertEquals(5L, view.snapshot.revision)
            assertEquals(view.snapshot.revision, view.acquisition.libraryRevision)
            assertTrue(view.snapshot.settings.offlineOnly)
            assertEquals(physical.value.physical.job, view.acquisition.physicalDownloads.getValue("job"))
            assertEquals(view.snapshot, store.state.value)
            assertEquals(view.acquisition, store.acquisition.state.value)
            assertEquals(view.snapshot.revision, store.releases.releaseCatalog.value.libraryRevision)
            assertEquals(5, backend.transactions)
            assertEquals(0, backend.legacyTransactions)
        } finally { store.close() }
    }

    @Test fun failedBackendAcknowledgementCannotPublishTheProposedGraph() = runTest {
        val backend = ProjectionPersistence()
        val store = LibraryStore(backend, StandardTestDispatcher(testScheduler))
        try {
            store.acquisition.initialize()
            val before = store.acquisitionViews.value
            backend.rejectCommit = true
            assertFailsWith<VeyraException> { store.acquisition.capture(CaptureAcquisition(authoredRequest())) }
            assertEquals(1, backend.transactions)
            assertSame(before, store.acquisitionViews.value)
            assertEquals(before.snapshot, store.state.value)
            assertEquals(before.acquisition, store.acquisition.state.value)
            assertTrue(backend.graph.state().requests.isEmpty())
            assertEquals(FailureCode.STORAGE_IO, store.lastFailure.value?.code)
        } finally { store.close() }
    }

    @Test fun cancellationAfterCommandAdmissionStillPublishesExactlyOneAcknowledgedGraph() = runTest {
        val backend = ProjectionPersistence()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        backend.beforeAcknowledgement = { entered.complete(Unit); finish.await() }
        val store = LibraryStore(backend, StandardTestDispatcher(testScheduler))
        try {
            val request = authoredRequest()
            val writer = launch { store.acquisition.capture(CaptureAcquisition(request)) }
            runCurrent()
            entered.await()
            writer.cancel()
            finish.complete(Unit)
            writer.cancelAndJoin()
            assertEquals(1, backend.transactions)
            assertEquals(1L, backend.graph.revision)
            assertEquals(request, store.acquisitionViews.value.acquisition.requests.getValue(request.id).request)
            assertEquals(1L, store.acquisitionViews.value.snapshot.revision)
            assertEquals(store.acquisitionViews.value.snapshot, store.state.value)
        } finally { store.close() }
    }

    @Test fun scalarAndAcquisitionCommandsAreSerializedByTheExistingStoreMutex() = runTest {
        val backend = ProjectionPersistence()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        backend.beforeAcknowledgement = { if (backend.transactions == 1) { entered.complete(Unit); finish.await() } }
        val store = LibraryStore(backend, StandardTestDispatcher(testScheduler))
        try {
            val request = authoredRequest()
            val capture = async { store.acquisition.capture(CaptureAcquisition(request)) }
            runCurrent()
            entered.await()
            val scalar = async { store.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow() }
            runCurrent()
            assertEquals(1, backend.transactions)
            finish.complete(Unit)
            capture.await()
            scalar.await()
            assertEquals(2, backend.transactions)
            val view = store.acquisitionViews.value
            assertEquals(2L, view.snapshot.revision)
            assertEquals(2L, view.acquisition.libraryRevision)
            assertTrue(view.snapshot.settings.offlineOnly)
            assertEquals(request, view.acquisition.requests.getValue(request.id).request)
        } finally { store.close() }
    }

    @Test fun readBarrierProjectsActualOneGraphRatherThanIndependentlySamplingPublishedFlows() = runTest {
        val backend = ProjectionPersistence()
        val store = LibraryStore(backend, StandardTestDispatcher(testScheduler))
        try {
            store.acquisition.initialize()
            backend.graph = backend.graph.withRevision(9) // Explicit external writer fixture, no invalidation event.
            assertEquals(0L, store.acquisitionViews.value.snapshot.revision)
            val barrier = store.acquisitionView()
            assertEquals(9L, barrier.snapshot.revision)
            assertEquals(9L, barrier.acquisition.libraryRevision)
            assertEquals(barrier, store.acquisitionViews.value)
            assertEquals(9L, store.releases.releaseCatalog.value.libraryRevision)
        } finally { store.close() }
    }

    @Test fun legacyTestSeamCannotSilentlyProvideAnInMemoryAcquisitionFallback() = runTest {
        val backend = ProjectionPersistence()
        val legacyOnly = object : LibraryPersistence by backend {}
        val store = LibraryStore(legacyOnly, StandardTestDispatcher(testScheduler))
        try {
            assertIs<StoreResult.Success>(store.initialize())
            val error = assertFailsWith<VeyraException> { store.acquisition.initialize() }
            assertEquals(FailureCode.INVALID_STATE, error.failure.code)
            assertTrue(store.acquisition.state.value.requests.isEmpty())
            assertEquals(0, backend.transactions)
        } finally { store.close() }
    }

    private class ProjectionPersistence : LibraryPersistence, AcquisitionPersistence {
        var graph = commandGraph()
        var opens = 0
        var reads = 0
        var transactions = 0
        var legacyTransactions = 0
        var closes = 0
        var rejectCommit = false
        var beforeAcknowledgement: suspend () -> Unit = {}

        override suspend fun open(): OpenedLibrary { opens++; return OpenedLibrary(graph.snapshot(), acquisitionGraph = graph) }
        override suspend fun read(): AppSnapshot = readAcquisitionGraph().snapshot()
        override suspend fun readAcquisitionGraph(): AcquisitionGraph { reads++; return graph }

        override suspend fun <T> transactAcquisition(transform: (AcquisitionGraph) -> AcquisitionMutation<T>): AcquisitionTransactionResult<T> {
            transactions++
            val before = graph
            val mutation = transform(before)
            require(mutation.graph.revision == before.revision)
            val projected = if (before.sameRelations(mutation.graph)) before else mutation.graph.withRevision(before.revision + 1)
            projected.snapshot()
            projected.state()
            projected.releaseCatalogState()
            if (projected !== before) validateAcquisitionTransition(before, projected)
            // A simulated backend failure, not an actual SQLite rollback/readback test.
            if (rejectCommit) throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_backend_rejection"))
            beforeAcknowledgement()
            graph = projected
            return AcquisitionTransactionResult(projected, mutation.result(projected))
        }

        override suspend fun transact(expectedRevision: Long?, transform: (AppSnapshot) -> AppSnapshot): AppSnapshot {
            legacyTransactions++
            error("Acquisition-capable store must use its one canonical transaction path")
        }
        override fun changes(): Flow<Unit> = emptyFlow()
        override suspend fun backup() = Unit
        override suspend fun recoverFromBackup(): OpenedLibrary = error("Not a recovery test")
        override suspend fun close() { closes++ }
    }

    private companion object { const val MBID = "00000001-0000-0000-0000-000000000001" }
}
