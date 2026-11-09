package dev.veyra.downloads

import androidx.room.Room
import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.data.room.*
import dev.veyra.model.*
import dev.veyra.playback.*
import dev.veyra.source.execution.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * Executor58: REAL dual-interface Room, typed first-result admission, canonical commands,
 * owned files/hash and OS leases. First answers, transfers, allowances and decoder results are
 * AUTHORED fakes over redistributable NON-AUDIO bytes. No native/live/offline-audio proof.
 * Neither this fixture nor its fault ports can write a legacy snapshot/binding/download seed.
 */
internal class DownloadExecutorSchema3Fixture(
    fileSystem: FileSystem = FileSystem.SYSTEM,
    val clock: Clock = DownloadTestClock(),
    val directory: Path = downloadTestDirectory(),
) {
    val databasePath: Path = directory.resolve("library.db")
    val events = CopyOnWriteArrayList<String>()
    val leases = PhysicalRuntimeLeaseProbe(events)
    val files = DownloadFiles(directory.resolve("media").toString(), fileSystem, DownloadFileLeaseFactory(leases::acquire))
    // Separate REAL OS observer: inspection must not increment the runtime's fault-stage counters.
    // Every successful probe is explicitly closed by its caller; it never issues runtime receipts.
    val probeFiles = DownloadFiles(directory.resolve("media").toString(), fileSystem)
    val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val releaseOnClose = CopyOnWriteArrayList<() -> Unit>()
    private val stores = CopyOnWriteArrayList<LibraryStore>()
    private val closedStores = mutableSetOf<LibraryStore>()
    private val owners = CopyOnWriteArrayList<DownloadExecutorSchema3Owner>()
    private val barriers = CopyOnWriteArrayList<DownloadBarrier>()
    private val backends = mutableMapOf<LibraryStore, DownloadExecutorRoomFaults>()
    private val additionalScopes = CopyOnWriteArrayList<CoroutineScope>()

    fun store(suppressInvalidationHints: Boolean = false): LibraryStore {
        val room = RoomLibraryPersistence(buildVeyraDatabase(Room.databaseBuilder<VeyraDatabase>(
            databasePath.toString(), factory = VeyraDatabaseConstructor::initialize)))
        val backend = DownloadExecutorRoomFaults(room, suppressInvalidationHints)
        return LibraryStore(backend).also { stores += it; backends[it] = backend }
    }

    fun faults(store: LibraryStore): DownloadExecutorRoomFaults = backends.getValue(store)

    fun owner(
        store: LibraryStore = store(),
        gateway: DownloadExecutorSchema3Gateway = DownloadExecutorSchema3Gateway(events),
        executionGate: DownloadExecutionGate? = null,
        capabilities: EngineCapabilities = EngineCapabilities(),
        parentScope: CoroutineScope = parent,
        heartbeatMs: Long = 80,
        leaseDurationMs: Long = 1_000,
        schedulerClock: Clock = clock,
    ): DownloadExecutorSchema3Owner = DownloadExecutorSchema3Owner(this, store, gateway, executionGate,
        capabilities, parentScope, heartbeatMs, leaseDurationMs, schedulerClock).also { owners += it }

    fun extraScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { additionalScopes += it }
    fun barrier(): DownloadBarrier = DownloadBarrier().also { barriers += it }
    fun advance(ms: Long) = assertIs<DownloadTestClock>(clock).advance(ms)

    fun item(id: String = "executor-media"): MediaItem = MediaItem(id,
        "Complete requested title $id — العنوان الكامل", MediaKind.MUSIC,
        SourceRef(SourceProvider.COMMONS, "original-$id", canonicalUrl = "https://commons.wikimedia.org/wiki/File:$id"),
        creator = "Authored discovery creator", durationMs = 999_999)

    /** One ACTUAL selector call through AcquisitionCoordinator, after a current Media capture. */
    suspend fun select(owner: DownloadExecutorSchema3Owner, item: MediaItem = item(),
        video: String = authoredVideo): DownloadExecutorSelection {
        owner.store.upsertCatalog(listOf(item)).getOrThrow()
        val catalog = owner.store.acquisitionView().snapshot
        val current = catalog.media.getValue(item.id)
        val request = AcquisitionCaptureFactory(clock).media(current, catalog)
        owner.repository.capture(CaptureAcquisition(request, catalog.revision))
        owner.answers[request.id] = authoredSingle(request, video)
        val before = owner.selector.requests.size
        val accepted = assertIs<AcquisitionResolution.Accepted>(owner.acquisition.resolve(request.id)).record
        assertEquals(before + 1, owner.selector.requests.size)
        assertEquals(request, owner.selector.requests.last())
        val outcome = assertIs<AcquisitionOutcome.Single>(accepted.outcome)
        val context = owner.store.acquisitionView().acquisition.executionContext(outcome.bindingId)
        assertEquals(request, context.request)
        assertEquals(request.completeTitle, context.receipt.observation.queryTitle)
        assertEquals(0, context.receipt.observation.entry.ordinal)
        assertEquals(video, context.recording.canonicalId)
        assertEquals(ExecutableSubject.CatalogMedia(ExecutableSubjectId(item.id), item.id), context.subject)
        return DownloadExecutorSelection(current, accepted, context)
    }

    /** Live authored tap token, not an empty callback or a hidden executor-failure fence. */
    suspend fun enqueue(owner: DownloadExecutorSchema3Owner, selection: DownloadExecutorSelection): String =
        owner.coordinator.enqueueAccepted(selection.record.request.id, selection.context.binding.id) {
            selection.admissionChecks.incrementAndGet()
            check(selection.current.get()) { "Authored original admission token revoked" }
        }.also { assertTrue(selection.admissionChecks.get() > 0) }

    suspend fun enqueue(owner: DownloadExecutorSchema3Owner, item: MediaItem = item(),
        video: String = authoredVideo): Pair<String, DownloadExecutorSelection> {
        val selected = select(owner, item, video)
        return enqueue(owner, selected) to selected
    }

    suspend fun awaitCondition(message: String = "Authored condition did not become true", predicate: suspend () -> Boolean) {
        withTimeout(15_000) { while (!predicate()) delay(10) }
    }

    suspend fun awaitState(owner: DownloadExecutorSchema3Owner,
        predicate: (AcquisitionRepositoryState) -> Boolean): AcquisitionRepositoryState = withTimeout(15_000) {
        var state = owner.store.acquisitionView().acquisition
        while (!predicate(state)) { delay(10); state = owner.store.acquisitionView().acquisition }
        state
    }

    suspend fun join(owner: DownloadExecutorSchema3Owner, id: String): PhysicalDownloadJoin =
        assertNotNull(owner.repository.physicalDownload(id))

    suspend fun awaitJob(owner: DownloadExecutorSchema3Owner, id: String,
        predicate: (PhysicalDownloadJoin) -> Boolean): PhysicalDownloadJoin = withTimeout(15_000) {
        var value = join(owner, id)
        while (!predicate(value)) { delay(10); value = join(owner, id) }
        value
    }

    suspend fun idle(owner: DownloadExecutorSchema3Owner): DownloadIdleDecision.Deferred = withTimeout(15_000) {
        var value = owner.coordinator.idleDecision()
        while (value !is DownloadIdleDecision.Deferred) { delay(10); value = owner.coordinator.idleDecision() }
        assertIs<DownloadIdleDecision.Deferred>(value)
    }

    /** Publication/receipt/origin-use/C0 all come from the real runtime; no registry writes here. */
    suspend fun complete(owner: DownloadExecutorSchema3Owner, id: String,
        originUse: Boolean = true, evidenceOwners: List<DownloadExecutorSchema3Owner> = listOf(owner)): PhysicalDownloadJoin {
        val completed = awaitJob(owner, id) { it.job.status == DownloadStatus.COMPLETE && it.asset != null }
        val asset = assertNotNull(completed.asset)
        awaitCondition { evidenceOwners.any { it.materializationEvidence.checks.value.successfulChecks[id]?.asset == asset } }
        val current = join(owner, id)
        val state = owner.store.acquisitionView().acquisition
        assertEquals(asset, current.asset)
        assertEquals(asset.receipt(), state.materializationReceipts[asset.id])
        assertNull(current.finalization)
        assertNull(current.activeAttempt)
        assertNull(current.job.leaseOwner)
        assertEquals(current.job.producer, asset.producer)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(asset.uri)))
        if (originUse) assertTrue(state.assetUses.any {
            it.requestedBindingId == asset.producer.bindingId && it.materializationAssetId == asset.id &&
                it.compatibilityPolicyVersion == owner.dependencies.compatibilityPolicyVersion
        }, "Fresh retained producer requires its exact origin use, not a manual Validate workaround")
        return current
    }

    suspend fun offline(owner: DownloadExecutorSchema3Owner, enabled: Boolean = true) {
        owner.store.update { it.copy(settings = it.settings.copy(offlineOnly = enabled)) }.getOrThrow()
    }

    suspend fun parallelism(owner: DownloadExecutorSchema3Owner, value: Int) {
        owner.store.update { it.copy(settings = it.settings.copy(downloadParallelism = value)) }.getOrThrow()
    }

    /** Durable pending state produced by ONE actual typed failing transfer, never a row seed. */
    suspend fun runtimeRetry(owner: DownloadExecutorSchema3Owner, retryAfterMs: Long = 2_000,
        item: MediaItem = item(), video: String = authoredVideo): Pair<String, DownloadExecutorSelection> {
        val originalTransfer = owner.gateway.transfer
        owner.gateway.transfer = { call ->
            call.write()
            throw VeyraException(AppFailure(FailureCode.RATE_LIMITED, "authored_retry_setup", retryAfterMs = retryAfterMs))
        }
        val result = enqueue(owner, item, video)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val pending = awaitJob(owner, result.first) {
            it.job.status == DownloadStatus.WAITING_NETWORK && it.job.leaseOwner == null && it.activeAttempt == null
        }
        assertEquals(FailureCode.RATE_LIMITED, pending.job.failure?.code)
        assertNotNull(pending.job.nextRetryEpochMs)
        assertEquals(1, pending.attempts.size)
        assertNotNull(pending.attempts.single().finishedEpochMs)
        idle(owner)
        owner.gateway.transfer = originalTransfer
        return result
    }

    suspend fun closeStore(store: LibraryStore) {
        assertTrue(owners.filter { it.store === store }.all { it.coordinator.dependencyReleaseSafeAfterClose },
            "Store cannot close before every same-owner executor's positive dependency-release receipt")
        store.close()
        closedStores += store
    }

    /**
     * Separately labeled canonical foreign/crash setup, NOT a runtime execution/C0 proof.
     * Every canonical claim/representation/heartbeat occurs under real slot+generation locks.
     * Releasing these test-owned locks without Room finish explicitly models process loss.
     */
    suspend fun foreignClaim(owner: DownloadExecutorSchema3Owner, id: String,
        leaseDurationMs: Long = 60_000, bytes: ByteArray = authoredBytes): DownloadExecutorForeignClaim {
        files.initialize()
        val before = join(owner, id)
        val attemptId = newEntityId("authored-foreign-attempt")
        val path = files.part(before.job.producer.subjectId.value, attemptId).toString()
        val slot = assertNotNull(files.tryTransferSlot(owner.store.acquisitionView().snapshot.settings.downloadParallelism))
        val pathLease = try { assertNotNull(files.tryLeasePaths(listOf(path))) }
            catch (failure: Throwable) { slot.close(); throw failure }
        try {
            val claimed = assertIs<PhysicalDownloadCommit.Committed<ClaimedPhysicalDownload>>(
                owner.repository.claimPhysicalDownloadAttempt(ClaimPhysicalDownloadAttempt(id, before.job.producer,
                    before.job.ownershipGeneration, attemptId, "authored-foreign-owner", clock.epochMs(), leaseDurationMs, path))).value
            assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(owner.repository.selectPhysicalDownloadRepresentation(
                SelectPhysicalDownloadRepresentation(claimed.fence, EXECUTOR_REPRESENTATION.representationId,
                    before.job.validatorGeneration, null, null, clock.epochMs())))
            files.prepareStagingPath(path)
            Files.write(Path.of(path), bytes)
            assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(owner.repository.heartbeatPhysicalDownload(
                HeartbeatPhysicalDownload(claimed.fence, clock.epochMs(), leaseDurationMs, bytes.size.toLong(), null)))
            return DownloadExecutorForeignClaim(claimed.fence, path, slot, pathLease).also { claim ->
                releaseOnClose += { claim.releasePhysicalAsAuthoredCrash() }
            }
        } catch (failure: Throwable) {
            pathLease.close(); slot.close(); throw failure
        }
    }

    /** An authored canonical crash intent under real locks; no materialization/use/C0 is seeded. */
    suspend fun recoveryIntent(owner: DownloadExecutorSchema3Owner, id: String,
        published: Boolean = false): PhysicalDownloadFinalization {
        val held = foreignClaim(owner, id, leaseDurationMs = 1_000)
        try {
            assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(owner.repository.beginPhysicalDownloadValidation(
                BeginPhysicalDownloadValidation(held.fence, clock.epochMs())))
            val before = join(owner, id)
            val intent = PhysicalDownloadFinalization(id, before.job.producer, held.path,
                files.finalPath(before.job.producer.subjectId.value, before.job.validatorGeneration, "audio/mpeg",
                    assertNotNull(held.fence.attemptId)).toString(), files.sha256(held.path.toPath()),
                authoredBytes.size.toLong(), held.fence.attemptId, 733, "audio/mpeg", "mp3",
                EXECUTOR_REPRESENTATION.representationId, before.job.validatorGeneration,
                held.fence.ownershipGeneration, MaterializationAssetId(newEntityId("authored-recovery-asset")))
            assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(owner.repository.preparePhysicalDownloadFinalization(
                PreparePhysicalDownloadFinalization(held.fence, intent, clock.epochMs())))
            if (published) files.atomicPublish(intent.stagingPath, intent.finalPath)
            assertTrue(owner.store.acquisitionView().acquisition.materializations.values.none { it.materializerDownloadId == id })
            assertNull(owner.materializationEvidence.checks.value.successfulChecks[id])
            return intent
        } finally { held.releasePhysicalAsAuthoredCrash() }
    }

    fun assertSQLite() {
        Files.newInputStream(databasePath).use { assertEquals("SQLite format 3\u0000", it.readNBytes(16).decodeToString()) }
    }

    suspend fun close() {
        barriers.forEach(DownloadBarrier::release)
        releaseOnClose.forEach { it() }
        leases.beforeClose = { _, _ -> }
        leases.refuse = { false }
        backends.values.forEach { it.clearFaults() }
        owners.forEach { owner ->
            owner.repository.clearFaults()
            owner.gate.beforeAcquire = { }
            owner.gate.beforeClose = { }
            owner.beforeDelete = { }
            owner.beforeAdoption = { }
        }
        for (owner in owners.asReversed()) {
            try { withTimeout(10_000) { owner.coordinator.close() } }
            catch (failure: VeyraException) {
                assertEquals(assertNotNull(owner.expectedCloseFailure,
                    "Unexpected teardown failure is not a passing test: ${failure.failure}"), failure.failure)
                assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose, "Retained native/file owner cannot be forgotten")
            }
            assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
        }
        additionalScopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        parent.coroutineContext.job.cancelAndJoin()
        stores.asReversed().filterNot { it in closedStores }.forEach { it.close() }
        assertEquals(0, leases.open.get(), "All actual task-owned OS leases must be consumed")
    }
}

internal class DownloadExecutorSchema3Owner(
    val fixture: DownloadExecutorSchema3Fixture,
    val store: LibraryStore,
    val gateway: DownloadExecutorSchema3Gateway,
    executionGate: DownloadExecutionGate?,
    capabilities: EngineCapabilities,
    parentScope: CoroutineScope,
    heartbeatMs: Long,
    leaseDurationMs: Long,
    schedulerClock: Clock,
) {
    val repository = DownloadExecutorRepositoryFaults(store.acquisition)
    val answers = java.util.concurrent.ConcurrentHashMap<AcquisitionRequestId, SourceAcquisitionResult>()
    val selector = DownloadRoomSelectionGateway(fixture.events).apply {
        answer = { request -> assertNotNull(answers[request.id], "No unauthorised second title query") }
    }
    val legacy = PhysicalRuntimeLegacyGateway(fixture.events)
    val gate = PhysicalRuntimeGate(fixture.events)
    val network = MutableStateFlow(DownloadNetworkState.CONNECTED)
    val validations = CopyOnWriteArrayList<LocalMediaValidationInput>()
    val coverageChecks = AtomicInteger()
    @Volatile var admission: () -> Unit = { }
    @Volatile var validation: suspend (LocalMediaValidationInput) -> AssetValidation = { AssetValidation.Valid(733) }
    @Volatile var beforeDelete: suspend (MaterializationAsset) -> Unit = { }
    @Volatile var beforeAdoption: suspend (PhysicalDownloadProducer.Schema2) -> Unit = { }
    @Volatile var expectedCloseFailure: AppFailure? = null
    val localValidator = LocalMediaValidator { input -> validations += input; validation(input) }
    val legacyValidator = AssetValidator { error("Typed executor must use its exact local input, not a fake OfflineAsset") }
    val acquisition = AcquisitionCoordinator(repository, selector, AcquisitionCaptureFactory(schedulerClock), schedulerClock,
        offline = { store.acquisitionViews.value.snapshot.settings.offlineOnly })
    val batches = DownloadBatchCoordinator(repository, acquisition, schedulerClock)
    val materializationEvidence = DownloadMaterializationEvidence(store.transactionOwner)
    val dependencies = DownloadAcquisitionDependencies(repository, gateway, localValidator, { beforeDelete(it) },
        { beforeAdoption(it) }, { coverageChecks.incrementAndGet(); admission() }, acquisition, batches, materializationEvidence)
    val resolver = AppSourceResolver(store, fixture.files, legacyValidator, legacy, schedulerClock, materializationEvidence)
    val coordinator = DownloadCoordinator(store, fixture.files, resolver, legacy, legacyValidator, capabilities,
        parentScope, executionGate ?: gate, schedulerClock, pollingMs = 30, leaseDurationMs = leaseDurationMs,
        heartbeatMs = heartbeatMs, networkState = network, acquisitionDependencies = dependencies)

    init {
        assertSame(store.transactionOwner, repository.transactionOwner)
        assertSame(store.transactionOwner, acquisition.transactionOwner)
        assertSame(store.transactionOwner, materializationEvidence.transactionOwner)
    }
}

internal data class DownloadExecutorSelection(val item: MediaItem, val record: AcquisitionRecord,
    val context: AcquisitionExecutionContext, val current: AtomicBoolean = AtomicBoolean(true),
    val admissionChecks: AtomicInteger = AtomicInteger())

internal val EXECUTOR_REPRESENTATION = AcquisitionRepresentation(authoredRepresentation, "audio/mpeg", "mp3", "mp3")
internal val executorRestartRequired = AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_failed_restart_required")

internal fun executorPrepared(context: AcquisitionExecutionContext, key: String?,
    representation: AcquisitionRepresentation = EXECUTOR_REPRESENTATION): PreparedAcquisitionDownload =
    object : PreparedAcquisitionDownload {
        override val context = context
        override val requiredRepresentationId = key
        override val representation = representation
    }

internal data class DownloadExecutorTransfer(val prepared: PreparedAcquisitionDownload, val path: String,
    val progress: suspend (AcquisitionTransferProgress) -> Unit, val operation: Job) {
    val context get() = prepared.context
    fun write(bytes: ByteArray = authoredBytes) { Files.write(Path.of(path), bytes) }
    fun result(bytes: ByteArray = authoredBytes, representation: AcquisitionRepresentation = prepared.representation) =
        AcquisitionTransferResult(prepared.context, bytes.size.toLong(), representation)
    suspend fun complete(bytes: ByteArray = authoredBytes): AcquisitionTransferResult {
        write(bytes)
        progress(AcquisitionTransferProgress(bytes.size.toLong(), bytes.size.toLong()))
        return result(bytes)
    }
}

internal class DownloadExecutorSchema3Gateway(private val events: MutableList<String>) : AcquisitionExecutionGateway {
    val prepareCalls = CopyOnWriteArrayList<Pair<AcquisitionExecutionContext, String?>>()
    val preparations = CopyOnWriteArrayList<PreparedAcquisitionDownload>()
    val transfers = CopyOnWriteArrayList<DownloadExecutorTransfer>()
    val playbackCalls = AtomicInteger()
    @Volatile var prepare: suspend (AcquisitionExecutionContext, String?) -> PreparedAcquisitionDownload = { context, key ->
        executorPrepared(context, key)
    }
    @Volatile var transfer: suspend (DownloadExecutorTransfer) -> AcquisitionTransferResult = { it.complete() }
    override suspend fun preparePlayback(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
        requiredRepresentationId: String?, inspection: AcquisitionInspection?): AcquisitionPlaybackSource {
        playbackCalls.incrementAndGet()
        error("Prepared downloads do not own a PlaybackResource or native player")
    }
    override suspend fun prepareDownload(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
        requiredRepresentationId: String?, inspection: AcquisitionInspection?): PreparedAcquisitionDownload {
        prepareCalls += context to requiredRepresentationId
        events += "executor-prepare"
        return prepare(context, requiredRepresentationId).also { preparations += it }
    }
    override suspend fun download(prepared: PreparedAcquisitionDownload, destinationPath: String,
        onProgress: suspend (AcquisitionTransferProgress) -> Unit): AcquisitionTransferResult {
        val call = DownloadExecutorTransfer(prepared, destinationPath, onProgress, currentCoroutineContext().job)
        transfers += call
        events += "executor-transfer"
        return transfer(call)
    }
}

/** Both production interfaces remain exposed. Only invalidation HINTS may be suppressed. */
internal class DownloadExecutorRoomFaults(private val room: RoomLibraryPersistence,
    private val suppressInvalidationHints: Boolean) : LibraryPersistence by room, AcquisitionPersistence by room {
    val reads = AtomicInteger()
    val transactions = AtomicInteger()
    @Volatile var beforeCommit: (AcquisitionGraph, AcquisitionGraph) -> Unit = { _, _ -> }
    @Volatile var afterRead: suspend (AcquisitionGraph) -> Unit = { }
    override fun changes(): Flow<Unit> = if (suppressInvalidationHints) emptyFlow() else room.changes()
    override suspend fun readAcquisitionGraph(): AcquisitionGraph {
        val actual = room.readAcquisitionGraph()
        reads.incrementAndGet()
        afterRead(actual)
        return actual
    }
    override suspend fun <T> transactAcquisition(transform: (AcquisitionGraph) -> AcquisitionMutation<T>): AcquisitionTransactionResult<T> =
        room.transactAcquisition { before ->
            transactions.incrementAndGet()
            transform(before).also { beforeCommit(before, it.graph) }
        }
    fun clearFaults() { beforeCommit = { _, _ -> }; afterRead = { } }
}

/** Named before/returned real command hooks; never substitute a synthetic Committed result. */
internal class DownloadExecutorRepositoryFaults(private val real: AcquisitionLibraryRepository) : AcquisitionLibraryRepository by real {
    val claimCalls = CopyOnWriteArrayList<ClaimPhysicalDownloadAttempt>()
    val finishCalls = CopyOnWriteArrayList<FinishPhysicalDownloadAttempt>()
    val maintenanceFinishCalls = CopyOnWriteArrayList<FinishPhysicalDownloadMaintenance>()
    val maintenanceClaims = CopyOnWriteArrayList<ClaimPhysicalDownloadMaintenance>()
    @Volatile var afterAttemptClaim: suspend (ClaimPhysicalDownloadAttempt, PhysicalDownloadCommit<ClaimedPhysicalDownload>) -> Unit = { _, _ -> }
    @Volatile var beforeMaintenanceClaim: suspend (ClaimPhysicalDownloadMaintenance) -> Unit = { }
    @Volatile var transformMaintenanceClaim: suspend (ClaimPhysicalDownloadMaintenance,
        PhysicalDownloadCommit<ClaimedPhysicalDownload>) -> PhysicalDownloadCommit<ClaimedPhysicalDownload> = { _, result -> result }
    @Volatile var beforeAttemptFinish: suspend (FinishPhysicalDownloadAttempt) -> Unit = { }
    @Volatile var afterAttemptFinish: suspend (FinishPhysicalDownloadAttempt, PhysicalDownloadCommit<PhysicalDownloadJoin>) -> Unit = { _, _ -> }
    @Volatile var beforeMaintenanceFinish: suspend (FinishPhysicalDownloadMaintenance) -> Unit = { }
    @Volatile var afterMaintenanceFinish: suspend (FinishPhysicalDownloadMaintenance, PhysicalDownloadCommit<PhysicalDownloadJoin>) -> Unit = { _, _ -> }
    @Volatile var afterPrepare: suspend (PreparePhysicalDownloadFinalization, PhysicalDownloadCommit<PhysicalDownloadJoin>) -> Unit = { _, _ -> }
    @Volatile var afterPublish: suspend (PublishPhysicalDownloadFinalization, PhysicalDownloadCommit<PhysicalDownloadJoin>) -> Unit = { _, _ -> }
    override suspend fun claimPhysicalDownloadAttempt(command: ClaimPhysicalDownloadAttempt): PhysicalDownloadCommit<ClaimedPhysicalDownload> {
        claimCalls += command
        return real.claimPhysicalDownloadAttempt(command).also { afterAttemptClaim(command, it) }
    }
    override suspend fun claimPhysicalDownloadMaintenance(command: ClaimPhysicalDownloadMaintenance): PhysicalDownloadCommit<ClaimedPhysicalDownload> {
        beforeMaintenanceClaim(command)
        maintenanceClaims += command
        return transformMaintenanceClaim(command, real.claimPhysicalDownloadMaintenance(command))
    }
    override suspend fun finishPhysicalDownloadAttempt(command: FinishPhysicalDownloadAttempt): PhysicalDownloadCommit<PhysicalDownloadJoin> {
        finishCalls += command
        beforeAttemptFinish(command)
        return real.finishPhysicalDownloadAttempt(command).also { afterAttemptFinish(command, it) }
    }
    override suspend fun finishPhysicalDownloadMaintenance(command: FinishPhysicalDownloadMaintenance): PhysicalDownloadCommit<PhysicalDownloadJoin> {
        maintenanceFinishCalls += command
        beforeMaintenanceFinish(command)
        return real.finishPhysicalDownloadMaintenance(command).also { afterMaintenanceFinish(command, it) }
    }
    override suspend fun preparePhysicalDownloadFinalization(command: PreparePhysicalDownloadFinalization): PhysicalDownloadCommit<PhysicalDownloadJoin> =
        real.preparePhysicalDownloadFinalization(command).also { afterPrepare(command, it) }
    override suspend fun publishPhysicalDownloadFinalization(command: PublishPhysicalDownloadFinalization): PhysicalDownloadCommit<PhysicalDownloadJoin> =
        real.publishPhysicalDownloadFinalization(command).also { afterPublish(command, it) }
    fun clearFaults() {
        afterAttemptClaim = { _, _ -> }; beforeMaintenanceClaim = { }; transformMaintenanceClaim = { _, result -> result }
        beforeAttemptFinish = { }; afterAttemptFinish = { _, _ -> }
        beforeMaintenanceFinish = { }; afterMaintenanceFinish = { _, _ -> }; afterPrepare = { _, _ -> }; afterPublish = { _, _ -> }
    }
}

internal class DownloadExecutorForeignClaim(val fence: PhysicalDownloadFence, val path: String,
    val slot: DownloadTransferSlot, private val pathLease: DownloadFileLease) {
    private var released = false
    fun releasePhysicalAsAuthoredCrash() {
        if (!released) { pathLease.close(); slot.close(); released = true }
    }
}

internal fun executorSchema3Case(fileSystem: FileSystem = FileSystem.SYSTEM, clock: Clock = DownloadTestClock(),
    block: suspend DownloadExecutorSchema3Fixture.() -> Unit): Unit = runBlocking {
    val fixture = DownloadExecutorSchema3Fixture(fileSystem, clock)
    try { withTimeout(60_000) { fixture.block() }; fixture.assertSQLite() }
    finally { withContext(NonCancellable) { fixture.close() } }
}
