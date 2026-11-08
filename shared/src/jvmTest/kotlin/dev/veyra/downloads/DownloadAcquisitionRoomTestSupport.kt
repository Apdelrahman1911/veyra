package dev.veyra.downloads

import androidx.room.Room
import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.data.room.*
import dev.veyra.model.*
import dev.veyra.playback.*
import dev.veyra.source.execution.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okio.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * REAL Coordinator, canonical schema3 commands, Room/SQLite, files and OS leases. Source answers
 * and native decoding/allowances are deliberately FAKE; bytes are authored NON-AUDIO. These
 * tests cannot establish live-source, codec, hardware/background or playable offline evidence.
 */
internal class DownloadAcquisitionRoomFixture {
    val directory: Path = downloadTestDirectory()
    val databasePath: Path = directory.resolve("library.db")
    val events = CopyOnWriteArrayList<String>()
    val leases = PhysicalRuntimeLeaseProbe(events)
    // Do not initialize files here: Room-only cold behavior must be observable in its own test.
    val files = DownloadFiles(directory.resolve("media").toString(), FileSystem.SYSTEM, DownloadFileLeaseFactory(leases::acquire))
    val clock = DownloadTestClock()
    val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val owners = CopyOnWriteArrayList<DownloadAcquisitionRoomOwner>()
    private val stores = CopyOnWriteArrayList<LibraryStore>()
    private val barriers = CopyOnWriteArrayList<DownloadBarrier>()
    private val faults = mutableMapOf<LibraryStore, DownloadRoomTransactionFaults>()

    fun store(): LibraryStore {
        val room = RoomLibraryPersistence(buildVeyraDatabase(Room.databaseBuilder<VeyraDatabase>(
            databasePath.toString(), factory = VeyraDatabaseConstructor::initialize)))
        val backend = DownloadRoomTransactionFaults(room)
        return LibraryStore(backend).also { stores += it; faults[it] = backend }
    }

    fun faults(store: LibraryStore): DownloadRoomTransactionFaults = faults.getValue(store)
    fun owner(store: LibraryStore = store()): DownloadAcquisitionRoomOwner =
        DownloadAcquisitionRoomOwner(this, store).also { owners += it }

    fun barrier(): DownloadBarrier = DownloadBarrier().also { barriers += it }

    suspend fun awaitCondition(message: String = "Authored condition did not become true", predicate: suspend () -> Boolean) {
        withTimeout(15_000) {
            while (!predicate()) delay(10)
            assertTrue(predicate(), message)
        }
    }

    suspend fun awaitState(store: LibraryStore, predicate: (AcquisitionRepositoryState) -> Boolean): AcquisitionRepositoryState {
        var state = store.acquisitionView().acquisition
        withTimeout(15_000) {
            while (!predicate(state)) { delay(10); state = store.acquisitionView().acquisition }
        }
        assertTrue(predicate(state))
        return state
    }

    suspend fun idle(owner: DownloadAcquisitionRoomOwner): DownloadIdleDecision.Deferred {
        var decision: DownloadIdleDecision = owner.coordinator.idleDecision()
        withTimeout(15_000) {
            while (decision !is DownloadIdleDecision.Deferred) { delay(10); decision = owner.coordinator.idleDecision() }
        }
        return assertIs<DownloadIdleDecision.Deferred>(decision)
    }

    suspend fun offline(owner: DownloadAcquisitionRoomOwner, enabled: Boolean = true) {
        owner.store.update { it.copy(settings = it.settings.copy(offlineOnly = enabled)) }.getOrThrow()
    }

    suspend fun parallelism(owner: DownloadAcquisitionRoomOwner, value: Int) {
        owner.store.update { it.copy(settings = it.settings.copy(downloadParallelism = value)) }.getOrThrow()
    }

    /** Seed an authored receipt through canonical claim+commit, not a fake Room implementation. */
    suspend fun accept(owner: DownloadAcquisitionRoomOwner, request: AcquisitionRequest,
        result: SourceAcquisitionResult = authoredSingle(request),
        attachments: List<OccurrenceAttachmentExpectation> = emptyList()): AcquisitionRecord {
        owner.repository.capture(CaptureAcquisition(request))
        val claim = assertIs<AcquisitionClaimResult.Claimed>(owner.repository.claim(ClaimAcquisition(request.id,
            AcquisitionAttemptToken(newEntityId("authored-selection-attempt")), "authored-fixture-selection", clock.epochMs(), 60_000)))
        return assertIs<AcquisitionCommitResult.Committed>(owner.repository.commit(commandResult(request,
            claim.lease, result, clock.epochMs(), attachments))).record
    }

    suspend fun acceptNeutral(owner: DownloadAcquisitionRoomOwner, label: String = newEntityId("authored-parent"),
        video: String = "LMNOPQRSTUV"): AcquisitionRecord {
        val request = authoredRequest(label, AlbumCapture(Album("edition-$label", "Complete parent title $label — العنوان الكامل")))
            .copy(capturedEpochMs = clock.epochMs())
        return accept(owner, request, authoredSingle(request, video))
    }

    suspend fun enqueue(owner: DownloadAcquisitionRoomOwner, accepted: AcquisitionRecord): String =
        owner.coordinator.enqueueAccepted(accepted.request.id, assertIs<AcquisitionOutcome.Single>(accepted.outcome).bindingId) { }

    suspend fun complete(owner: DownloadAcquisitionRoomOwner, label: String = newEntityId("authored-parent"),
        video: String = "LMNOPQRSTUV"): Pair<String, AcquisitionRecord> {
        val accepted = acceptNeutral(owner, label, video)
        val id = enqueue(owner, accepted)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitState(owner.store) { it.physicalDownloads[id]?.status == DownloadStatus.COMPLETE }
        idle(owner)
        return id to accepted
    }

    /** Real immutable observation/snapshot/scope graph; only the supplied source facts are authored. */
    suspend fun batch(owner: DownloadAcquisitionRoomOwner, count: Int = 1,
        mode: PlaybackControlsMode = PlaybackControlsMode.PODCAST): DownloadBatch {
        val label = newEntityId("authored-collection-parent")
        val request = authoredRequest(label, AlbumCapture(Album("edition-$label", "Complete collection title — عنوان المجموعة")))
            .copy(capturedEpochMs = clock.epochMs())
        val selected = authoredCollection(request)
        val accepted = accept(owner, request, selected)
        val receipt = assertIs<AcquisitionOutcome.Collection>(accepted.outcome).receiptId
        val observation = authoredObservation(request, newEntityId("authored-observation"))
        val members = (0 until count).map { authoredMember(observation, it) }
        val page = CollectionPageData(selected, observation.id, observation, CollectionPageDisposition.OBSERVED_DELTA,
            0, 0, members, CollectionExtent(CollectionTraversal.OBSERVED_TO_END, count, 0))
        val appended = owner.repository.appendPage(AppendCollectionPage(receipt, page, null, null, clock.epochMs()))
        val snapshot = owner.repository.sealSnapshot(SealCollectionSnapshot(CollectionSnapshotId(newEntityId("authored-snapshot")),
            observation.id, appended.run.frontier, clock.epochMs()))
        val prepared = AcquisitionAdmissionFactory(AcquisitionCaptureFactory(clock), clock).collection(snapshot,
            ObservedCollectionChoice.WholeObservedListing, mode)
        return owner.batches.admit(prepared)
    }

    suspend fun acceptMember(owner: DownloadAcquisitionRoomOwner, batch: DownloadBatch, position: Int = 0,
        attach: Boolean = true, video: String = "LMNOPQRSTUV"): AcquisitionRecord {
        val member = batch.members.single { it.position == position }
        val request = assertNotNull(owner.repository.request(member.requestId)).request
        return accept(owner, request, authoredSingle(request, video), if (attach) listOf(OccurrenceAttachmentExpectation(
            member.occurrenceId, member.requestId, null, null, batch.id, batch.intentRevision)) else emptyList())
    }

    fun assertSQLite() {
        Files.newInputStream(databasePath).use { assertEquals("SQLite format 3\u0000", it.readNBytes(16).decodeToString()) }
    }

    suspend fun close() {
        barriers.forEach(DownloadBarrier::release)
        leases.beforeClose = { _, _ -> }
        faults.values.forEach { it.beforeCommit = { _, _ -> } }
        owners.forEach { owner ->
            owner.gate.beforeClose = { }
            owner.beforeDelete = { }
            owner.beforeAdoption = { }
            owner.repository.afterAttemptClaim = { it }
            owner.repository.beforeAttemptFinish = { }
        }
        for (owner in owners.asReversed()) {
            try { withTimeout(10_000) { owner.coordinator.close() } }
            catch (failure: VeyraException) {
                // A prior typed durable/cleanup failure can remain sticky AFTER real release.
                assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose, "Failed owner must not be forgotten")
            }
            assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
        }
        parent.coroutineContext.job.cancelAndJoin()
        stores.asReversed().forEach { it.close() }
        assertEquals(0, leases.open.get(), "Every actual test-owned OS lease must be consumed")
    }
}

internal class DownloadAcquisitionRoomOwner(val fixture: DownloadAcquisitionRoomFixture, val store: LibraryStore) {
    val events get() = fixture.events
    val repository = DownloadRoomRepositoryFaults(store.acquisition)
    val selector = DownloadRoomSelectionGateway(events)
    val gateway = PhysicalRuntimeGateway(events)
    val legacy = PhysicalRuntimeLegacyGateway(events)
    val gate = PhysicalRuntimeGate(events)
    val network = MutableStateFlow(DownloadNetworkState.CONNECTED)
    val validations = CopyOnWriteArrayList<LocalMediaValidationInput>()
    val coverageChecks = AtomicInteger()
    @Volatile var admission: () -> Unit = { }
    @Volatile var validation: suspend (LocalMediaValidationInput) -> AssetValidation = { AssetValidation.Valid(733) }
    @Volatile var beforeDelete: suspend (MaterializationAsset) -> Unit = { }
    @Volatile var beforeAdoption: suspend (PhysicalDownloadProducer.Schema2) -> Unit = { }
    val localValidator = LocalMediaValidator { input -> validations += input; validation(input) }
    private val legacyValidator = AssetValidator { error("Schema3 must use its typed local validator") }
    val acquisition = AcquisitionCoordinator(repository, selector, AcquisitionCaptureFactory(fixture.clock), fixture.clock,
        offline = { store.acquisitionViews.value.snapshot.settings.offlineOnly })
    val batches = DownloadBatchCoordinator(repository, acquisition, fixture.clock)
    val materializationEvidence = DownloadMaterializationEvidence(store.transactionOwner)
    val dependencies = DownloadAcquisitionDependencies(repository, gateway, localValidator, { beforeDelete(it) },
        { beforeAdoption(it) }, { coverageChecks.incrementAndGet(); admission() }, acquisition, batches, materializationEvidence)
    val coordinator = DownloadCoordinator(store, fixture.files,
        AppSourceResolver(store, fixture.files, legacyValidator, legacy, fixture.clock, materializationEvidence), legacy, legacyValidator,
        EngineCapabilities(), fixture.parent, gate, fixture.clock, pollingMs = 30, leaseDurationMs = 5_000,
        heartbeatMs = 100, networkState = network, acquisitionDependencies = dependencies)
    val adapter = AcquisitionDownloadAdapter(repository, acquisition, coordinator.acquisitionExecutor)
}

/** Both interfaces delegate to REAL Room. Transform faults happen INSIDE the rollback boundary. */
internal class DownloadRoomTransactionFaults(private val room: RoomLibraryPersistence) :
    LibraryPersistence by room, AcquisitionPersistence by room {
    @Volatile var beforeCommit: (AcquisitionGraph, AcquisitionGraph) -> Unit = { _, _ -> }
    override suspend fun <T> transactAcquisition(transform: (AcquisitionGraph) -> AcquisitionMutation<T>): AcquisitionTransactionResult<T> =
        room.transactAcquisition { before -> transform(before).also { beforeCommit(before, it.graph) } }
}

/** Deliberately faulty returned-port seams; durable claim/finish still use canonical real Room. */
internal class DownloadRoomRepositoryFaults(private val real: AcquisitionLibraryRepository) : AcquisitionLibraryRepository by real {
    @Volatile var afterAttemptClaim: (PhysicalDownloadCommit<ClaimedPhysicalDownload>) -> PhysicalDownloadCommit<ClaimedPhysicalDownload> = { it }
    @Volatile var beforeAttemptFinish: () -> Unit = { }
    override suspend fun claimPhysicalDownloadAttempt(command: ClaimPhysicalDownloadAttempt): PhysicalDownloadCommit<ClaimedPhysicalDownload> =
        afterAttemptClaim(real.claimPhysicalDownloadAttempt(command))
    override suspend fun finishPhysicalDownloadAttempt(command: FinishPhysicalDownloadAttempt): PhysicalDownloadCommit<PhysicalDownloadJoin> {
        beforeAttemptFinish()
        return real.finishPhysicalDownloadAttempt(command)
    }
}

internal class DownloadRoomSelectionGateway(private val events: MutableList<String>) : AcquisitionGateway {
    val requests = CopyOnWriteArrayList<AcquisitionRequest>()
    val restores = AtomicInteger()
    @Volatile var answer: suspend (AcquisitionRequest) -> SourceAcquisitionResult = { authoredSingle(it) }
    override suspend fun acquire(request: AcquisitionRequest): SourceAcquisitionResult {
        requests += request
        events += "authored-select"
        return answer(request)
    }
    override fun restore(request: AcquisitionRequest, receipt: FirstResultObservation): SourceAcquisitionResult {
        restores.incrementAndGet()
        check(receipt.requestId == request.id && receipt.queryTitle == request.completeTitle && receipt.entry.ordinal == 0)
        check(receipt.entry.kind == AcquisitionEntryKind.VIDEO)
        return SourceAcquisitionResult.Single(request.id, receipt, SourceDisplayItem(requireNotNull(receipt.entry.id),
            requireNotNull(receipt.entry.title), requireNotNull(receipt.canonicalUrl), AcquisitionEntryKind.VIDEO))
    }
    override suspend fun collection(request: AcquisitionRequest, selection: SourceAcquisitionResult.Collection,
        limits: CollectionLimits): SourceCollectionPage = error("No recursive collection/network work in this download fixture")
    override suspend fun more(continuation: CollectionContinuation): SourceCollectionPage = error("No continuation retry in this fixture")
    override suspend fun inspect(request: AcquisitionRequest, selection: SourceAcquisitionResult.Single): AcquisitionInspection =
        error("Download selection must not inspect/reselect an accepted member")
}

internal fun acquisitionDownloadRoomCase(block: suspend DownloadAcquisitionRoomFixture.() -> Unit) = runBlocking {
    val fixture = DownloadAcquisitionRoomFixture()
    try { withTimeout(60_000) { fixture.block() }; fixture.assertSQLite() }
    finally { withContext(NonCancellable) { fixture.close() } }
}
