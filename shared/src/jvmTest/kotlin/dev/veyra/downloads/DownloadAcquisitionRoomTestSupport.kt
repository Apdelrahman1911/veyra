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
internal class DownloadAcquisitionRoomFixture(fileSystem: FileSystem = FileSystem.SYSTEM) {
    val directory: Path = downloadTestDirectory()
    val databasePath: Path = directory.resolve("library.db")
    val events = CopyOnWriteArrayList<String>()
    val leases = PhysicalRuntimeLeaseProbe(events)
    // Do not initialize files here: Room-only cold behavior must be observable in its own test.
    val files = DownloadFiles(directory.resolve("media").toString(), fileSystem, DownloadFileLeaseFactory(leases::acquire))
    val clock = DownloadTestClock()
    val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val owners = CopyOnWriteArrayList<DownloadAcquisitionRoomOwner>()
    val releaseOnClose = CopyOnWriteArrayList<() -> Unit>()
    private val stores = CopyOnWriteArrayList<LibraryStore>()
    private val barriers = CopyOnWriteArrayList<DownloadBarrier>()
    private val faults = mutableMapOf<LibraryStore, DownloadRoomTransactionFaults>()

    fun store(suppressInvalidationHints: Boolean = false): LibraryStore {
        val room = RoomLibraryPersistence(buildVeyraDatabase(Room.databaseBuilder<VeyraDatabase>(
            databasePath.toString(), factory = VeyraDatabaseConstructor::initialize)))
        val backend = DownloadRoomTransactionFaults(room, suppressInvalidationHints)
        return LibraryStore(backend).also { stores += it; faults[it] = backend }
    }

    fun faults(store: LibraryStore): DownloadRoomTransactionFaults = faults.getValue(store)
    fun owner(store: LibraryStore = store(), files: DownloadFiles = this.files,
        schedulerClock: Clock = clock): DownloadAcquisitionRoomOwner =
        DownloadAcquisitionRoomOwner(this, store, files, schedulerClock).also { owners += it }

    fun barrier(): DownloadBarrier = DownloadBarrier().also { barriers += it }

    fun advance(ms: Long) = clock.advance(ms)

    suspend fun join(owner: DownloadAcquisitionRoomOwner, id: String): PhysicalDownloadJoin =
        assertNotNull(owner.repository.physicalDownload(id))

    suspend fun awaitJob(owner: DownloadAcquisitionRoomOwner, id: String,
        predicate: (PhysicalDownloadJoin) -> Boolean): PhysicalDownloadJoin {
        var value = join(owner, id)
        try {
            withTimeout(15_000) { while (!predicate(value)) { delay(10); value = join(owner, id) } }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("Authored job $id did not settle; status=${value.job.status} " +
                "lease=${value.job.leaseOwner} attempt=${value.activeAttempt?.id} " +
                "failure=${value.job.failure} retry=${value.job.nextRetryEpochMs} " +
                "cleanup=${value.job.cleanupPending} asset=${value.asset?.id}", timeout)
        }
        return value
    }

    suspend fun awaitState(store: LibraryStore, predicate: (AcquisitionRepositoryState) -> Boolean): AcquisitionRepositoryState {
        var state = store.acquisitionView().acquisition
        try {
            withTimeout(15_000) {
                while (!predicate(state)) { delay(10); state = store.acquisitionView().acquisition }
            }
        } catch (timeout: TimeoutCancellationException) {
            val actual = store.acquisitionView().acquisition
            throw AssertionError("Authored state did not become true; downloads=${
                actual.physicalDownloads.values.joinToString { "${it.id}:${it.status}:lease=${it.leaseOwner}:attempt=${it.activeAttemptId}:failure=${it.failure}:retry=${it.nextRetryEpochMs}" }
            }; attempts=${actual.physicalAttempts.values.size}", timeout)
        }
        assertTrue(predicate(state))
        return state
    }

    /** Accepted immutable intent that has not started a native/source execution yet. */
    suspend fun queued(owner: DownloadAcquisitionRoomOwner, label: String = newEntityId("authored-queued"),
        video: String = "LMNOPQRSTUV"): String {
        val accepted = acceptNeutral(owner, label, video)
        return enqueue(owner, accepted)
    }

    suspend fun awaitCondition(message: String = "Authored condition did not become true", predicate: suspend () -> Boolean) {
        withTimeout(15_000) {
            while (!predicate()) delay(10)
            assertTrue(predicate(), message)
        }
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

    suspend fun enqueue(owner: DownloadAcquisitionRoomOwner, accepted: AcquisitionRecord): String {
        val requestId = accepted.request.id
        val bindingId = assertIs<AcquisitionOutcome.Single>(accepted.outcome).bindingId
        return owner.coordinator.enqueueAccepted(requestId, bindingId) {
            // Live canonical admission token: the accepted request must still be COMMITTED in the
            // published same-owner state at the instant the command accepts durable intent.
            check(owner.store.acquisitionViews.value.acquisition.requests[requestId]?.state ==
                AcquisitionJobState.COMMITTED) { "Authored admission token is no longer current" }
        }
    }

    suspend fun complete(owner: DownloadAcquisitionRoomOwner, label: String = newEntityId("authored-parent"),
        video: String = "LMNOPQRSTUV"): Pair<String, AcquisitionRecord> {
        val accepted = acceptNeutral(owner, label, video)
        val id = enqueue(owner, accepted)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitState(owner.store) { it.physicalDownloads[id]?.status == DownloadStatus.COMPLETE }
        idle(owner)
        return id to accepted
    }

    /**
     * Durable pending state produced by ONE actual typed failing transfer, never a row seed.
     * The retry deadline is the exact bounded value published by the real canonical finish.
     */
    suspend fun runtimeRetry(owner: DownloadAcquisitionRoomOwner, retryAfterMs: Long = 2_000,
        label: String = newEntityId("authored-retry-parent"), video: String = "LMNOPQRSTUV"):
        Pair<String, AcquisitionRecord> {
        val accepted = acceptNeutral(owner, label, video)
        val id = enqueue(owner, accepted)
        val originalTransfer = owner.gateway.transfer
        owner.gateway.transfer = { prepared, path, _ ->
            Files.write(Path.of(path), authoredBytes)
            throw VeyraException(AppFailure(FailureCode.RATE_LIMITED, "authored_retry_setup", retryAfterMs = retryAfterMs))
        }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitState(owner.store) {
            it.physicalDownloads[id]?.let { job ->
                job.status == DownloadStatus.WAITING_NETWORK && job.leaseOwner == null && job.activeAttemptId == null &&
                    job.failure?.code == FailureCode.RATE_LIMITED && job.nextRetryEpochMs != null
            } == true
        }
        idle(owner)
        owner.gateway.transfer = originalTransfer
        return id to accepted
    }

    /**
     * Authored canonical foreign/crash setup under real slot+generation locks; NOT an execution
     * proof. Releasing the returned physical locks without a Room finish models process loss.
     */
    suspend fun foreignClaim(owner: DownloadAcquisitionRoomOwner, id: String,
        leaseDurationMs: Long = 60_000, bytes: ByteArray = authoredBytes): DownloadExecutorForeignClaim {
        files.initialize()
        val before = assertNotNull(owner.repository.physicalDownload(id))
        val attemptId = newEntityId("authored-foreign-attempt")
        val part = files.part(before.job.producer.subjectId.value, attemptId)
        val slot = assertNotNull(files.tryTransferSlot(1))
        val pathLease = try { assertNotNull(files.tryLeasePaths(listOf(part.toString()))) }
            catch (failure: Throwable) { slot.close(); throw failure }
        try {
            val claimed = assertIs<PhysicalDownloadCommit.Committed<ClaimedPhysicalDownload>>(
                owner.repository.claimPhysicalDownloadAttempt(ClaimPhysicalDownloadAttempt(id, before.job.producer,
                    before.job.ownershipGeneration, attemptId, "authored-foreign-owner", clock.epochMs(), leaseDurationMs,
                    part.toString()))).value
            assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(owner.repository.selectPhysicalDownloadRepresentation(
                SelectPhysicalDownloadRepresentation(claimed.fence, authoredRepresentation, 1, null, null, clock.epochMs())))
            files.prepareStagingPath(part.toString())
            Files.write(Path.of(part.toString()), bytes)
            assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(owner.repository.heartbeatPhysicalDownload(
                HeartbeatPhysicalDownload(claimed.fence, clock.epochMs(), leaseDurationMs, bytes.size.toLong(), null)))
            return DownloadExecutorForeignClaim(claimed.fence, part.toString(), slot, pathLease).also { claim ->
                releaseOnClose += { claim.releasePhysicalAsAuthoredCrash() }
            }
        } catch (failure: Throwable) {
            pathLease.close(); slot.close(); throw failure
        }
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
        releaseOnClose.forEach { it() }
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

internal class DownloadAcquisitionRoomOwner(val fixture: DownloadAcquisitionRoomFixture, val store: LibraryStore,
    val files: DownloadFiles = fixture.files, schedulerClock: Clock = fixture.clock) {
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
    val coordinator = DownloadCoordinator(store, files,
        AppSourceResolver(store, files, legacyValidator, legacy, schedulerClock, materializationEvidence), legacy, legacyValidator,
        EngineCapabilities(), fixture.parent, gate, schedulerClock, pollingMs = 30, leaseDurationMs = 5_000,
        heartbeatMs = 100, networkState = network, acquisitionDependencies = dependencies)
    val adapter = AcquisitionDownloadAdapter(repository, acquisition, coordinator.acquisitionExecutor)
}

/** Both interfaces delegate to REAL Room. Transform faults happen INSIDE the rollback boundary. */
internal class DownloadRoomTransactionFaults(private val room: RoomLibraryPersistence,
    private val suppressInvalidationHints: Boolean = false) :
    LibraryPersistence by room, AcquisitionPersistence by room {
    @Volatile var beforeCommit: (AcquisitionGraph, AcquisitionGraph) -> Unit = { _, _ -> }
    @Volatile var afterRead: suspend (AcquisitionGraph) -> Unit = { }
    override fun changes(): kotlinx.coroutines.flow.Flow<Unit> =
        if (suppressInvalidationHints) kotlinx.coroutines.flow.emptyFlow() else room.changes()
    override suspend fun readAcquisitionGraph(): AcquisitionGraph {
        val actual = room.readAcquisitionGraph()
        afterRead(actual)
        return actual
    }
    override suspend fun <T> transactAcquisition(transform: (AcquisitionGraph) -> AcquisitionMutation<T>): AcquisitionTransactionResult<T> =
        room.transactAcquisition { before -> transform(before).also { beforeCommit(before, it.graph) } }
    fun clearFaults() { beforeCommit = { _, _ -> }; afterRead = { } }
}

/** Deliberately faulty returned-port seams; durable claim/finish still use canonical real Room. */
internal class DownloadRoomRepositoryFaults(private val real: AcquisitionLibraryRepository) : AcquisitionLibraryRepository by real {
    val claimCalls = CopyOnWriteArrayList<ClaimPhysicalDownloadAttempt>()
    val finishCalls = CopyOnWriteArrayList<FinishPhysicalDownloadAttempt>()
    val maintenanceFinishCalls = CopyOnWriteArrayList<FinishPhysicalDownloadMaintenance>()
    @Volatile var afterAttemptClaim: (PhysicalDownloadCommit<ClaimedPhysicalDownload>) -> PhysicalDownloadCommit<ClaimedPhysicalDownload> = { it }
    @Volatile var beforeAttemptFinish: () -> Unit = { }
    @Volatile var afterAttemptClaimHook: suspend (ClaimPhysicalDownloadAttempt, PhysicalDownloadCommit<ClaimedPhysicalDownload>) -> Unit = { _, _ -> }
    @Volatile var beforeAttemptFinishHook: suspend (FinishPhysicalDownloadAttempt) -> Unit = { }
    @Volatile var afterAttemptFinishHook: (FinishPhysicalDownloadAttempt, PhysicalDownloadCommit<PhysicalDownloadJoin>) -> Unit = { _, _ -> }
    @Volatile var beforeMaintenanceFinishHook: suspend (FinishPhysicalDownloadMaintenance) -> Unit = { }
    @Volatile var afterMaintenanceFinishHook: (FinishPhysicalDownloadMaintenance, PhysicalDownloadCommit<PhysicalDownloadJoin>) -> Unit = { _, _ -> }
    @Volatile var afterPrepareHook: (PreparePhysicalDownloadFinalization, PhysicalDownloadCommit<PhysicalDownloadJoin>) -> Unit = { _, _ -> }
    @Volatile var afterPublishHook: (PublishPhysicalDownloadFinalization, PhysicalDownloadCommit<PhysicalDownloadJoin>) -> Unit = { _, _ -> }
    override suspend fun claimPhysicalDownloadAttempt(command: ClaimPhysicalDownloadAttempt): PhysicalDownloadCommit<ClaimedPhysicalDownload> {
        claimCalls += command
        val result = afterAttemptClaim(real.claimPhysicalDownloadAttempt(command))
        afterAttemptClaimHook(command, result)
        return result
    }
    override suspend fun finishPhysicalDownloadAttempt(command: FinishPhysicalDownloadAttempt): PhysicalDownloadCommit<PhysicalDownloadJoin> {
        finishCalls += command
        beforeAttemptFinish()
        beforeAttemptFinishHook(command)
        return real.finishPhysicalDownloadAttempt(command).also { afterAttemptFinishHook(command, it) }
    }
    override suspend fun finishPhysicalDownloadMaintenance(command: FinishPhysicalDownloadMaintenance): PhysicalDownloadCommit<PhysicalDownloadJoin> {
        maintenanceFinishCalls += command
        beforeMaintenanceFinishHook(command)
        return real.finishPhysicalDownloadMaintenance(command).also { afterMaintenanceFinishHook(command, it) }
    }
    override suspend fun preparePhysicalDownloadFinalization(command: PreparePhysicalDownloadFinalization): PhysicalDownloadCommit<PhysicalDownloadJoin> =
        real.preparePhysicalDownloadFinalization(command).also { afterPrepareHook(command, it) }
    override suspend fun publishPhysicalDownloadFinalization(command: PublishPhysicalDownloadFinalization): PhysicalDownloadCommit<PhysicalDownloadJoin> =
        real.publishPhysicalDownloadFinalization(command).also { afterPublishHook(command, it) }
    fun clearFaults() {
        afterAttemptClaim = { it }; beforeAttemptFinish = { }
        afterAttemptClaimHook = { _, _ -> }; beforeAttemptFinishHook = { }; afterAttemptFinishHook = { _, _ -> }
        beforeMaintenanceFinishHook = { }; afterMaintenanceFinishHook = { _, _ -> }
        afterPrepareHook = { _, _ -> }; afterPublishHook = { _, _ -> }
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

internal fun acquisitionDownloadRoomCase(fileSystem: FileSystem = FileSystem.SYSTEM,
    block: suspend DownloadAcquisitionRoomFixture.() -> Unit) = runBlocking {
    val fixture = DownloadAcquisitionRoomFixture(fileSystem)
    try { withTimeout(60_000) { fixture.block() }; fixture.assertSQLite() }
    finally { withContext(NonCancellable) { fixture.close() } }
}
