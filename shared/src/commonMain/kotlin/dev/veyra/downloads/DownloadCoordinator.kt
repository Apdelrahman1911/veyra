package dev.veyra.downloads

import dev.veyra.acquisition.AcquisitionDownloadExecutor
import dev.veyra.data.LibraryAcquisitionView
import dev.veyra.data.LibraryStore
import dev.veyra.data.getOrThrow
import dev.veyra.model.*
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.AssetValidator
import dev.veyra.playback.Clock
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.LocalMediaValidationReaderUnacknowledged
import dev.veyra.playback.PlaybackResource
import dev.veyra.playback.SystemClock
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.execution.AppSourceResolver
import dev.veyra.source.execution.KmpMediaGateway
import okio.Path.Companion.toPath
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private typealias MaintenanceKind = DownloadMaintenanceKind
private typealias CleanupOwner = DownloadCleanupOwner

/**
 * Room owns durable intent; kmpYT alone acquires media. Expiring a database lease does NOT prove
 * that an old writer stopped. OS generation/slot locks fence the filesystem and actual parallel
 * acquisition; every durable mutation is conditional on the latest Room transaction.
 */
class DownloadCoordinator(
    private val store: LibraryStore,
    val files: DownloadFiles,
    private val resolver: AppSourceResolver,
    private val gateway: KmpMediaGateway,
    private val validator: AssetValidator,
    private val capabilities: EngineCapabilities,
    parentScope: CoroutineScope,
    private val executionGate: DownloadExecutionGate = ProcessLifetimeDownloadGate,
    private val clock: Clock = SystemClock(),
    private val pollingMs: Long = 1_000,
    private val leaseDurationMs: Long = 120_000,
    private val heartbeatMs: Long = minOf(pollingMs, 20_000),
    /** Root must await actual native file release, not just an accepted/queued Stop command. */
    private val beforePhysicalDelete: suspend (OfflineAsset) -> Unit = {},
    private val networkState: StateFlow<DownloadNetworkState> = alwaysConnectedDownloadNetwork,
    /** Production schema3 MUST inject the same-owner ports; null is legacy schema2/test compatibility only. */
    acquisitionDependencies: DownloadAcquisitionDependencies? = null,
) : DownloadScheduling {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)
    // Public item actions enter this mutex BEFORE their first possible suspension. The app's
    // undispatched intent admission relies on that order; slow join/native/file work stays outside.
    private val commands = Mutex()
    private val closeLock = Mutex()
    private val activeLock = Mutex()
    private val maintenanceLock = Mutex()
    private val active = mutableMapOf<String, Job>()
    private val maintenance = mutableMapOf<String, TrackedMaintenance>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val maintenanceWake = Channel<Unit>(Channel.CONFLATED)
    private val lifecycle = MutableStateFlow(Lifecycle())
    // A coroutine finishing is NOT a receipt that a throwing native close consumed its owner.
    // These records remain strongly owned after their jobs terminate, including during close().
    private val cleanupRegistry = DownloadCleanupRegistry { noteActivity() }
    private val cleanupOwners = cleanupRegistry.owners
    private val cleanupFailure = MutableStateFlow<AppFailure?>(null)
    private val dependencyReleaseSafe = MutableStateFlow(false)
    // Only closeLock accesses this first sanitized close outcome.
    private var closeFailure: AppFailure? = null
    private val owner = newEntityId("download-owner")
    private val activityEpoch = MutableStateFlow(0L)
    private val mutableSchedulingChanges = MutableStateFlow(0L)
    override val schedulingChanges: StateFlow<Long> = mutableSchedulingChanges.asStateFlow()
    private val claimDeferrals = MutableStateFlow<Map<DownloadClaimKey, DownloadClaimDeferral>>(emptyMap())
    // These three values are accessed only with commands + activeLock held, in that order.
    private var schedulingRevision = 0L
    private var previousSchedulingInputs: SchedulingInputs? = null
    private var issuedIdle: IssuedIdle? = null
    private data class CheckedAsset(val asset: OfflineAsset, val binding: PlaybackBinding)
    private val checkedAssets = MutableStateFlow<Map<String, CheckedAsset>>(emptyMap())
    private var scheduler: Job? = null
    private var reconciler: Job? = null
    private var startup: CompletableDeferred<Unit>? = null
    private var policyObserver: Job? = null
    private var networkObserver: Job? = null
    private var lastSweepMs: Long? = null
    private data class Lifecycle(val started: Boolean = false, val suspended: Boolean = false, val closed: Boolean = false,
        val generation: Long = 0, val checksActivated: Boolean = false, val drainsInFlight: Int = 0,
        val drainFailure: AppFailure? = null)
    private data class RequestedItem(val title: String, val kind: MediaKind, val source: SourceRef)
    private data class SchedulingInputs(
        val records: Map<String, DownloadRecord>,
        val finalizations: Map<String, DownloadFinalization>,
        val requests: Map<String, RequestedItem?>,
        val bindings: Map<String, PlaybackBinding?>,
        val attemptPaths: Map<String, Set<String>>,
        val offlineOnly: Boolean,
        val parallelism: Int,
        val life: Lifecycle,
        val network: DownloadNetworkState,
        val checkedIds: Set<String>,
        val deferrals: Map<DownloadClaimKey, DownloadClaimDeferral>,
        val physical: DownloadPhysicalSchedulingInputs? = null,
        val physicalHost: DownloadPhysicalHostState? = null,
        val batchWork: DownloadBatchWorkSchedulingInputs? = null,
    )
    private data class BuiltPlan(val plan: DownloadWakePlan, val inputs: SchedulingInputs)
    private data class IssuedIdle(val ticket: DownloadIdleTicket, val generation: Long, val activityEpoch: Long,
        val inputs: SchedulingInputs)
    private data class ExecutionClaim(val record: DownloadRecord, val item: MediaItem, val attempt: DownloadAttempt,
        val generationLease: DownloadFileLease, val slot: DownloadTransferSlot, val lifecycleGeneration: Long,
        val allowanceCallbacksOpen: MutableStateFlow<Boolean> = MutableStateFlow(true),
        val validationReaders: DownloadValidationReaderRetention = DownloadValidationReaderRetention())
    private data class MaintenanceClaim(val record: DownloadRecord, val intent: DownloadFinalization?,
        val leaseOwner: String, val kind: MaintenanceKind, val stagingPaths: Set<String>, val finalPaths: Set<String>,
        val fileLease: DownloadFileLease, val lifecycleGeneration: Long,
        val validationReaders: DownloadValidationReaderRetention = DownloadValidationReaderRetention())
    private data class MaintenanceRun(val token: String, val lifecycleGeneration: Long,
        val claim: MaintenanceClaim? = null, val orphanLease: DownloadFileLease? = null,
        val allowanceCallbacksOpen: MutableStateFlow<Boolean> = MutableStateFlow(true),
        val actionFinished: MutableStateFlow<Boolean> = MutableStateFlow(false))
    private data class TrackedMaintenance(val run: MaintenanceRun, val job: Job)
    private class ExecutionCleanup(val claim: ExecutionClaim, val resource: PlaybackResource?,
        val allowance: DownloadExecutionLease?, val publicationLease: DownloadFileLease?,
        val failure: AppFailure?, val cancelled: Boolean, val finalization: DownloadFinalization?,
        val partialLeases: List<DownloadLeaseCleanup>) :
        CleanupOwner("execution:${claim.attempt.id}")
    private class MaintenanceCleanup(val run: MaintenanceRun, val allowance: DownloadExecutionLease?,
        val admissionFailed: Boolean) : CleanupOwner("maintenance:${run.token}")
    private class RollbackCleanup(val leases: DownloadLeaseCleanup,
        val executionClaim: ExecutionClaim? = null, val maintenanceClaim: MaintenanceClaim? = null,
        val failure: AppFailure? = null, val cancelled: Boolean = false) :
        CleanupOwner(newEntityId("download-claim-rollback"))

    // This is the schema3 body of THIS coordinator: same mutexes, jobs, slots, scope and registry.
    // In particular it is not another lifetime, scheduler, native player or source-selection queue.
    private val physicalRuntime: DownloadPhysicalRuntime? = acquisitionDependencies?.let { dependencies ->
        DownloadPhysicalRuntime(store, dependencies, files, gateway, capabilities, executionGate, clock,
            leaseDurationMs, heartbeatMs, DownloadPhysicalHost(
                commands = commands, activeLock = activeLock, active = active, cleanupRegistry = cleanupRegistry,
                state = { physicalHostState() }, acquisitionView = store::acquisitionView,
                changed = ::noteActivity, signalWork = ::signalWork, publishFailure = { publishFailure(it) },
                publishCleanupFailure = ::publishCleanupFailure, failCheckpointAdmission = ::failPhysicalCheckpointAdmission,
                allowanceExpired = ::physicalAllowanceExpired,
            ))
    }
    private val batchWorkRuntime: DownloadBatchWorkRuntime? = acquisitionDependencies?.let { dependencies ->
        DownloadBatchWorkRuntime(dependencies, requireNotNull(physicalRuntime), files, executionGate, clock, ::batchAllowanceExpired)
    }
    private val checkedMaterializations: StateFlow<DownloadMaterializationChecks> =
        physicalRuntime?.checkedMaterializations ?: MutableStateFlow(DownloadMaterializationChecks.Empty).asStateFlow()

    /** The exact issuer consumed by AcquisitionDownloadAdapter, not a wrapping replacement owner. */
    val acquisitionExecutor: AcquisitionDownloadExecutor get() = requirePhysicalRuntime()

    /** Includes in-progress and failed cleanup; a zero native lease count cannot clear it. */
    val hasPendingCleanupOwners: Boolean get() = cleanupOwners.value.isNotEmpty()

    /**
     * Monotonic post-close receipt: every scheduler/registered job joined, every native/file
     * owner consumed, Room cleanup calls returned, and this coordinator's scope joined. Empty
     * cleanup records ALONE are not proof. Existing reported/caught Room finalizer exceptions
     * are not upgraded to durable-CAS success by this dependency-lifetime receipt.
     * A prior sanitized close failure may still be thrown when this becomes true. Hosts retain
     * their dependency graph while false; they must not infer safety from close throwing/returning.
     */
    val dependencyReleaseSafeAfterClose: Boolean get() = dependencyReleaseSafe.value

    init { require(pollingMs > 0 && leaseDurationMs in 1_000..600_000 && heartbeatMs in 1 until leaseDurationMs) }

    /** One coherent snapshot: never merge a newer Room revision with an older download map. */
    val presentation: StateFlow<AppSnapshot> = if (physicalRuntime != null) {
        combine(store.acquisitionViews, checkedMaterializations, lifecycle) { view, checked, life ->
            projectLegacyPhysicalDownloads(view, checked, physicalHostState(view.snapshot, life))
        }.stateIn(scope, SharingStarted.Eagerly, store.acquisitionViews.value.let { view ->
            projectLegacyPhysicalDownloads(view, DownloadMaterializationChecks.Empty, physicalHostState(view.snapshot))
        })
    } else combine(store.state, checkedAssets, lifecycle) { snapshot, checked, life ->
        snapshot.copy(downloads = projectStates(snapshot, checked, life))
    }.stateIn(scope, SharingStarted.Eagerly, store.state.value.let {
        it.copy(downloads = projectStates(it, emptyMap(), lifecycle.value))
    })
    /** Session-only projection: Room's completion receipt is not a new process's integrity check. */
    val states: StateFlow<Map<String, DownloadRecord>> = presentation.map { it.downloads }
        .stateIn(scope, SharingStarted.Eagerly, presentation.value.downloads)
    /** Paired canonical Room view/checking state: UI must not combine independently delayed maps. */
    val acquisitionPresentation: StateFlow<DownloadAcquisitionPresentation> = if (physicalRuntime != null) {
        combine(store.acquisitionViews, checkedMaterializations, lifecycle) { view, checked, life ->
            projectAcquisitionDownloadPresentation(view, checked, physicalHostState(view.snapshot, life))
        }.stateIn(scope, SharingStarted.Eagerly, store.acquisitionViews.value.let { view ->
            projectAcquisitionDownloadPresentation(view, DownloadMaterializationChecks.Empty, physicalHostState(view.snapshot))
        })
    } else store.acquisitionViews.map { DownloadAcquisitionPresentation(it, emptyMap()) }
        .stateIn(scope, SharingStarted.Eagerly, DownloadAcquisitionPresentation(store.acquisitionViews.value, emptyMap()))

    /** Canonical neutral/catalogue jobs; never manufacture a legacy MediaItem or OfflineAsset. */
    val physicalStates: StateFlow<Map<String, PhysicalDownloadJob>> = acquisitionPresentation.map { it.states }
        .stateIn(scope, SharingStarted.Eagerly, acquisitionPresentation.value.states)

    val hasPendingWork: StateFlow<Boolean> = if (physicalRuntime != null) {
        combine(store.acquisitionViews, lifecycle, checkedMaterializations) { view, life, checked ->
            val inputs = DownloadPhysicalSchedulingInputs(view.acquisition.physicalSchedulingRecords(),
                checkedPhysicalDownloadIds(view.acquisition, checked), emptyMap())
            val host = physicalHostState(view.snapshot, life)
            val now = clock.epochMs()
            val plan = withPendingDownloadBatchWork(projectPhysicalDownloadWakePlan(inputs, host,
                now, DownloadSchedulingKey(owner, 0)), requireNotNull(batchWorkRuntime).schedulingInputs(view.acquisition), host, now)
            life.started && !life.closed && !life.suspended &&
                (plan.nextLocalEpochMs != null || plan.nextNetworkEpochMs != null)
        }.stateIn(scope, SharingStarted.Eagerly, false)
    } else combine(store.state, lifecycle, checkedAssets) { snapshot, life, checked ->
        life.started && !life.closed && !life.suspended && snapshot.downloads.values.any {
            it.cleanupPending || !it.userPaused && it.id in snapshot.downloadFinalizations ||
                it.status == DownloadStatus.COMPLETE && !assetChecked(snapshot, it, checked) ||
                !snapshot.settings.offlineOnly && !it.userPaused && it.status in RUNNABLE_OR_BUSY
        }
    }.stateIn(scope, SharingStarted.Eagerly, false)
    private val mutableFailure = MutableStateFlow<AppFailure?>(null)
    val lastFailure: StateFlow<AppFailure?> = mutableFailure.asStateFlow()

    /** Headless scheduling bootstrap: Room only, no files, hashes, source calls or native allowance. */
    suspend fun initializeScheduling() = commands.withLock {
        requireOpen()
        store.initialize().getOrThrow()
        Unit
    }

    /** Durable batch changes invalidate idle and wake the existing workers; NEVER activate work. */
    fun onAcquisitionBatchChanged() {
        noteActivity()
        signalWork()
    }

    override suspend fun schedulingPlan(): DownloadWakePlan = commands.withLock {
        requireOpen()
        activeLock.withLock { buildSchedulingPlan().plan }
    }

    override suspend fun idleDecision(): DownloadIdleDecision = commands.withLock {
        requireOpen()
        requireNoFailedBackgroundDrain()
        activeLock.withLock admission@ {
            issuedIdle = null
            val built = buildSchedulingPlan()
            if (lifecycle.value.drainsInFlight != 0 || active.isNotEmpty() || maintenance.isNotEmpty() ||
                hasPendingCleanupOwners || physicalRuntime?.epochs?.allLocalOwnersConsumed() == false || built.plan.hasDueWork) {
                // The scheduler has a due admission to try. Physical refusal is recorded as
                // bounded LOCAL deferral; a future retry never enters this branch by itself.
                if (built.plan.hasDueWork) signalWork()
                return@admission DownloadIdleDecision.OwnedWork
            }
            val ticket = DownloadIdleTicket(built.plan.key)
            issuedIdle = IssuedIdle(ticket, lifecycle.value.generation, activityEpoch.value, built.inputs)
            DownloadIdleDecision.Deferred(ticket, built.plan)
        }
    }

    override suspend fun confirmIdle(ticket: DownloadIdleTicket, retire: () -> Boolean): Boolean = commands.withLock {
        requireOpen()
        requireNoFailedBackgroundDrain()
        activeLock.withLock admission@ {
            val issued = issuedIdle
            if (issued == null || issued.ticket !== ticket) return@admission false
            issuedIdle = null // Every confirmation attempt consumes the exact instance, even on failure.
            val built = buildSchedulingPlan()
            if (lifecycle.value.drainsInFlight != 0 || active.isNotEmpty() || maintenance.isNotEmpty() ||
                hasPendingCleanupOwners || physicalRuntime?.epochs?.allLocalOwnersConsumed() == false || built.plan.hasDueWork ||
                issued.generation != lifecycle.value.generation || issued.activityEpoch != activityEpoch.value ||
                ticket.key != built.plan.key || issued.inputs != built.inputs) return@admission false
            // A completed concurrent store publication and a due-time crossing during the read
            // cannot be hidden by the earlier barrier. No suspension is allowed after this point.
            val currentInputs = if (physicalRuntime == null) schedulingInputs(store.state.value)
                else schedulingInputs(store.acquisitionViews.value)
            if (currentInputs != built.inputs) return@admission false
            val now = clock.epochMs()
            if (built.plan.nextLocalEpochMs?.let { it <= now } == true ||
                networkState.value == DownloadNetworkState.CONNECTED &&
                built.plan.nextNetworkEpochMs?.let { it <= now } == true) return@admission false
            retire() // Native exact-owner/epoch/zero-lease CAS only. Never WorkManager or expiration.
        }
    }

    private fun schedulingInputs(snapshot: AppSnapshot): SchedulingInputs {
        val records = snapshot.downloads.mapValues { (_, record) -> record.schedulingRecord() }
        val ids = records.values.mapTo(hashSetOf()) { it.mediaId }
        val bindingIds = records.values.flatMap { record ->
            listOfNotNull(record.executionBindingId, record.asset?.executionBindingId,
                snapshot.downloadFinalizations[record.id]?.executionBindingId)
        }.toSet()
        val checked = records.values.filter { assetChecked(snapshot, it, checkedAssets.value) }.mapTo(hashSetOf()) { it.id }
        return SchedulingInputs(records, snapshot.downloadFinalizations,
            ids.associateWith { id -> snapshot.media[id]?.let { RequestedItem(it.title, it.kind, it.source) } },
            bindingIds.associateWith { snapshot.playbackBindings[it] },
            snapshot.downloadAttempts.values.filter { it.downloadId in records && it.stagingPath != null }
                .groupBy { it.downloadId }.mapValues { (_, attempts) -> attempts.mapNotNull { it.stagingPath }.toSet() },
            snapshot.settings.offlineOnly, snapshot.settings.downloadParallelism.coerceIn(1, 4),
            lifecycle.value, networkState.value, checked,
            claimDeferrals.value.filter { (key, value) -> records[key.id] == value.record })
    }

    private fun schedulingInputs(view: LibraryAcquisitionView): SchedulingInputs {
        val snapshot = view.snapshot
        val life = lifecycle.value
        val network = networkState.value
        return SchedulingInputs(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
            snapshot.settings.offlineOnly, snapshot.settings.downloadParallelism.coerceIn(1, 4),
            life, network, emptySet(), emptyMap(), requirePhysicalRuntime().schedulingInputs(view.acquisition),
            physicalHostState(snapshot, life, network), requireNotNull(batchWorkRuntime).schedulingInputs(view.acquisition))
    }

    private fun nextSchedulingKey(inputs: SchedulingInputs): DownloadSchedulingKey {
        if (inputs != previousSchedulingInputs) {
            check(schedulingRevision < Long.MAX_VALUE) { "Download scheduling epoch exhausted" }
            schedulingRevision++
            previousSchedulingInputs = inputs
        }
        return DownloadSchedulingKey(owner, schedulingRevision)
    }

    private suspend fun buildSchedulingPlan(): BuiltPlan {
        if (physicalRuntime != null) {
            val view = store.acquisitionView() // ONE same-owner transaction/read barrier.
            val inputs = schedulingInputs(view)
            val now = clock.epochMs()
            val host = checkNotNull(inputs.physicalHost)
            val projected = withPendingDownloadBatchWork(projectPhysicalDownloadWakePlan(checkNotNull(inputs.physical),
                host, now, nextSchedulingKey(inputs)), checkNotNull(inputs.batchWork), host, now)
            return BuiltPlan(projected, inputs)
        }
        val snapshot = store.flush().getOrThrow()
        val inputs = schedulingInputs(snapshot)
        val context = DownloadSchedulingContext(clock.epochMs(), inputs.life.suspended,
            inputs.life.checksActivated, inputs.network, inputs.checkedIds, inputs.deferrals)
        val projected = projectDownloadWakePlan(snapshot, context, nextSchedulingKey(inputs))
        return BuiltPlan(inputs.life.drainFailure?.let { projected.copy(blockedReason = it) } ?: projected, inputs)
    }

    private fun physicalHostState(snapshot: AppSnapshot = store.acquisitionViews.value.snapshot,
        life: Lifecycle = lifecycle.value, network: DownloadNetworkState = networkState.value) =
        DownloadPhysicalHostState(life.started, life.closed, life.suspended, life.checksActivated,
            life.generation, life.drainsInFlight, life.drainFailure ?: cleanupFailure.value,
            snapshot.settings.offlineOnly, snapshot.settings.downloadParallelism.coerceIn(1, 4), network)

    private fun requirePhysicalRuntime(): DownloadPhysicalRuntime = physicalRuntime
        ?: throw VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "download_acquisition_owner_required"))

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    suspend fun start(activation: DownloadActivation = DownloadActivation.USER_SESSION) {
        val ready = commands.withLock {
            requireOpen()
            requireNoFailedBackgroundDrain()
            if (lifecycle.value.started) {
                if (activation == DownloadActivation.USER_SESSION && !lifecycle.value.checksActivated) {
                    lifecycle.value = lifecycle.value.copy(checksActivated = true)
                    noteActivity()
                    signalWork()
                }
                return@withLock startup!!
            }
            store.initialize().getOrThrow()
            withContext(Dispatchers.IO) { files.initialize() }
            lifecycle.value = lifecycle.value.copy(started = true, checksActivated = activation == DownloadActivation.USER_SESSION)
            noteActivity()
            val initialized = CompletableDeferred<Unit>().also { startup = it }
            scheduler = scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    while (isActive) {
                        try {
                            // Cached StateFlows are hints, never the authority for arbitration.
                            if (physicalRuntime == null) store.flush().getOrThrow()
                            schedule()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { publishFailure(classify(error)) }
                        withTimeoutOrNull(pollingMs) { wake.receive() }
                    }
                } finally { if (!initialized.isCompleted) initialized.cancel() }
            }
            // Validation may take arbitrarily longer than opening Room. Do not hold app startup
            // or new acquisitions behind every stored file; maintenance is serial and separately gated.
            reconciler = scope.launch(start = CoroutineStart.ATOMIC) {
                while (isActive) {
                    try { reconcile() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { publishFailure(classify(error)) }
                    withTimeoutOrNull(pollingMs) { maintenanceWake.receive() }
                }
            }
            policyObserver = scope.launch {
                val policy = if (physicalRuntime == null) store.state.map { it.settings.offlineOnly to 4 }
                    else store.acquisitionViews.map { it.snapshot.settings.offlineOnly to
                        it.snapshot.settings.downloadParallelism.coerceIn(1, 4) }
                policy.distinctUntilChanged().collect { (offline, parallelism) ->
                    try {
                        if (offline) pauseForPolicy()
                        else {
                            // The canonical lower limit is enforced on active typed slots, not
                            // only on the next claim. Cancellation uses the existing unwind path.
                            physicalRuntime?.revokeExcessSlots(parallelism)
                            signalWork()
                        }
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { publishFailure(classify(error)) }
                }
            }
            networkObserver = scope.launch {
                // A connection is eligibility for new claims only. Existing native work keeps
                // its exact lease and reports typed failures; local cleanup never needs network.
                networkState.collect { signalWork() }
            }
            initialized.complete(Unit)
            initialized
        }
        // Startup establishes the workers, not an unbounded scan of the local media library.
        ready.await()
    }

    /** Root already selected this exact immutable Single, outside ALL download locks. */
    suspend fun enqueueAccepted(requestId: AcquisitionRequestId, requiredBindingId: ExecutionBindingId,
        requireIntentCurrent: () -> Unit): String =
        requirePhysicalRuntime().userCommands.enqueueAccepted(requestId, requiredBindingId, requireIntentCurrent)

    /** Explicit unknown-schema2 replacement; reader/native drain is mandatory even without an asset row. */
    suspend fun adoptSchema2(id: String, requestId: AcquisitionRequestId, requiredBindingId: ExecutionBindingId,
        requireIntentCurrent: () -> Unit): String =
        requirePhysicalRuntime().userCommands.adoptSchema2(id, requestId, requiredBindingId, requireIntentCurrent)

    /** Physical completion is not an R2 use receipt. This performs owned local validation/CAS only. */
    suspend fun validateStandaloneAssetUse(id: String, requiredBindingId: ExecutionBindingId): AssetUse =
        requirePhysicalRuntime().validateStandaloneAssetUse(id, requiredBindingId)

    /** An explicit playback receipt pins Play -> Download; an existing different selection is not silently replaced. */
    suspend fun enqueue(item: MediaItem, reuseBindingId: String? = null): String =
        physicalRuntime?.userCommands?.enqueueKnownCatalog(item, reuseBindingId) ?: commands.withLock {
        requireOpen()
        requireNoFailedBackgroundDrain()
        noteActivity()
        var actualId = files.key(item.id)
        store.update { snapshot ->
            val existingItem = snapshot.media[item.id]
            require(existingItem == null || existingItem.kind == item.kind && existingItem.source.provider == item.source.provider &&
                existingItem.source.canonicalId == item.source.canonicalId)
            val original = existingItem ?: item
            val prior = snapshot.downloads.values.firstOrNull { it.mediaId == item.id }
            actualId = prior?.id ?: actualId
            requireRequestedBinding(snapshot, original, prior, reuseBindingId)
            if (prior != null && (prior.cleanupPending || prior.status in BUSY + setOf(DownloadStatus.QUEUED, DownloadStatus.COMPLETE)))
                return@update snapshot
            val now = clock.epochMs()
            val intent = snapshot.downloadFinalizations[actualId]
            val discardCorrupt = prior?.asset != null || intent != null && prior?.failure?.code == FailureCode.CORRUPT_MEDIA
            val record = (prior ?: DownloadRecord(actualId, item.id, createdEpochMs = now)).copy(
                status = when { discardCorrupt -> DownloadStatus.FAILED; intent != null -> DownloadStatus.VERIFYING; else -> queuedStatus(snapshot) },
                cleanupPending = discardCorrupt,
                userPaused = false, failure = if (discardCorrupt) prior?.failure else queueFailure(snapshot),
                nextRetryEpochMs = null, automaticRetryCount = 0,
                executionBindingId = prior?.executionBindingId ?: reuseBindingId, updatedEpochMs = now,
            )
            snapshot.copy(media = if (existingItem == null) snapshot.media + (item.id to item) else snapshot.media,
                downloads = snapshot.downloads + (actualId to record))
        }.getOrThrow()
        signalWork()
        actualId
    }

    private fun requireRequestedBinding(snapshot: AppSnapshot, item: MediaItem, prior: DownloadRecord?, requestedId: String?) {
        if (requestedId == null) return
        val requested = snapshot.playbackBindings[requestedId]
            ?: throw VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "execution_binding_missing"))
        if (requested.mediaId != item.id || requested.selectionOrdinal != 0 || requested.firstResultKind != FirstResultKind.VIDEO ||
            requested.selectedVideoId.isNullOrBlank()) throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "download_binding_identity"))
        val priorId = prior?.asset?.executionBindingId ?: prior?.executionBindingId ?: return
        if (priorId != requestedId && snapshot.playbackBindings[priorId]?.selectedVideoId != requested.selectedVideoId)
            throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "download_has_different_recording"))
    }

    suspend fun pause(id: String) = stopItem(id, discard = false, remove = false)
    suspend fun cancel(id: String) = stopItem(id, discard = true, remove = false)
    suspend fun remove(id: String) = stopItem(id, discard = true, remove = true)

    suspend fun resume(id: String) {
        physicalRuntime?.let { it.userCommands.resume(id); return }
        commands.withLock {
            requireOpen()
            requireNoFailedBackgroundDrain()
            noteActivity()
            clearClaimDeferrals(id)
            store.update { snapshot ->
                val current = snapshot.downloads[id] ?: return@update snapshot
                if (current.cleanupPending || current.status in BUSY + setOf(DownloadStatus.QUEUED, DownloadStatus.COMPLETE)) return@update snapshot
                val intent = snapshot.downloadFinalizations[id]
                val discardCorrupt = current.asset != null || intent != null && current.failure?.code == FailureCode.CORRUPT_MEDIA
                snapshot.copy(downloads = snapshot.downloads + (id to current.copy(
                    status = when { discardCorrupt -> DownloadStatus.FAILED; intent != null -> DownloadStatus.VERIFYING; else -> queuedStatus(snapshot) },
                    cleanupPending = discardCorrupt, userPaused = false,
                    failure = if (discardCorrupt) current.failure else queueFailure(snapshot),
                    nextRetryEpochMs = null, automaticRetryCount = 0, updatedEpochMs = clock.epochMs(),
                )))
            }.getOrThrow()
        }
        signalWork()
    }

    private suspend fun stopItem(id: String, discard: Boolean, remove: Boolean) {
        physicalRuntime?.let { it.userCommands.stop(id, discard, remove); return }
        val jobs = commands.withLock {
            requireOpen()
            noteActivity()
            clearClaimDeferrals(id)
            var stopped = false
            store.update { snapshot ->
                val current = snapshot.downloads[id] ?: return@update snapshot
                if (!remove && current.status == DownloadStatus.COMPLETE) return@update snapshot
                stopped = true
                snapshot.copy(downloads = snapshot.downloads + (id to current.copy(
                    status = if (discard || current.cleanupPending) DownloadStatus.CANCELLED else DownloadStatus.PAUSED,
                    userPaused = true, cleanupPending = current.cleanupPending || discard,
                    removeWhenClean = current.removeWhenClean || remove, failure = null, nextRetryEpochMs = null,
                    // A foreign lease/attempt is retained until that writer acknowledges or locks prove it dead.
                    updatedEpochMs = clock.epochMs(),
                )))
            }.getOrThrow()
            if (!stopped) emptyList() else activeLock.withLock {
                listOfNotNull(active[id]) + maintenance.values.filter {
                    it.run.claim?.record?.id == id && it.run.claim?.kind != MaintenanceKind.CLEANUP
                }.map { it.job }
            }
        }
        cancelAndJoinJobs(jobs)
        if (discard) cleanup(id)
        signalWork()
    }

    /** Existing host pause semantics: report a checkpoint failure only after accepted owner drain. */
    suspend fun pauseForBackground() {
        drainForBackground().checkpointFailure?.let { throw VeyraException(it) }
    }

    /**
     * Independent host/worker drain, NOT an allowance callback. Callback return or a zero native
     * lease count does not prove resource/file/Room unwind: registered owners remain until then.
     * Once accepted, caller cancellation or a failed checkpoint must not skip joining those owners.
     * Only the returned receipt acknowledges drain; an unknown thrown error must remain fail-closed.
     * Foreground resume is rejected, never queued, until every concurrent drain has joined its owners.
     * Unknown checkpoint failure leaves a visible, sticky restriction; only a recreated host can
     * recover it. There is no implicit retry that declares an earlier unacknowledged drain safe.
     */
    suspend fun drainForBackground(): DownloadBackgroundDrainAck = withContext(NonCancellable) {
        var checkpointError: Throwable? = null
        var physicalEpochs = emptyList<DownloadOwnershipEpochs.Epoch>()
        val jobs = commands.withLock {
            val life = lifecycle.value
            check(life.drainsInFlight < Int.MAX_VALUE) { "Too many independent download drains" }
            // Block both new claims and foreground reentry before observing owners, including
            // concurrent drains and an already-closing coordinator. Never reopen a closed host.
            lifecycle.value = life.copy(suspended = true, drainsInFlight = life.drainsInFlight + 1)
            noteActivity()
            val (running, maintaining) = activeLock.withLock {
                physicalEpochs = physicalRuntime?.ownedEpochs().orEmpty()
                active.values.toList() to maintenance.values.toList()
            }
            // close() may have latched closed but still be awaiting native owners. Join that
            // same registry without reopening/checkpointing an already-closed library.
            // A cleanup failure can precede OTHER jobs' successful unwind. Even an already
            // failed drain must independently join those live jobs before returning no ACK.
            // It never retries the failed cleanup records or replays a Room checkpoint.
            if (!life.closed && life.drainFailure == null) {
                try { suspendOwnedRecords(DownloadStatus.NEEDS_FOREGROUND, maintaining = maintaining) }
                catch (error: Throwable) { checkpointError = error }
            }
            running + maintaining.map { it.job }
        }
        // No admission lock is held while callbacks or owner finalizers need it to complete.
        cancelAndJoinJobs(jobs)
        var physicallyDrained = true
        val physical = physicalRuntime
        for (epoch in physicalEpochs) {
            try { if (physical == null || !physical.epochs.drain(epoch)) physicallyDrained = false }
            catch (error: Throwable) { physicallyDrained = false; if (checkpointError == null) checkpointError = error }
        }
        val physicalCheckpoint = if (physical != null && physicallyDrained) physical.drainCheckpointFailure() else null
        val checkpointFailure = (checkpointError as? VeyraException)?.failure ?: physicalCheckpoint
        val unknown = checkpointError?.takeUnless { it is VeyraException } ?: physical?.takeUnknownCheckpointFailure()
        val blocked = commands.withLock {
            val life = lifecycle.value
            check(life.drainsInFlight > 0)
            val sticky = life.drainFailure ?: cleanupFailure.value ?: if (unknown != null || !physicallyDrained || hasPendingCleanupOwners ||
                physical?.epochs?.allLocalOwnersConsumed() == false) restartRequiredFailure() else null
            // Joined is not still-running. Retire this in-flight count, but retain a separate
            // failed-drain admission fence and the FIRST typed failure rather than an invisible wait.
            lifecycle.value = life.copy(drainsInFlight = life.drainsInFlight - 1, drainFailure = sticky)
            if (sticky != null || checkpointFailure != null) publishFailure(sticky ?: checkpointFailure)
            noteActivity()
            sticky
        }
        // No suspension after releasing the fence: the native Main-confined caller consumes
        // this receipt and clears its exact expiration barrier in the same dispatch segment.
        unknown?.let { throw it } // Unknown/cancellation is never converted into a post-drain ACK.
        blocked?.let { throw VeyraException(it) }
        DownloadBackgroundDrainAck(checkpointFailure)
    }

    /** Exact schema3 generation/map guard; inline acquire/close callbacks MUST be cancel-only. */
    private suspend fun physicalAllowanceExpired(run: DownloadPhysicalRun): Unit = withContext(NonCancellable) {
        val jobs = commands.withLock {
            val physical = physicalRuntime ?: return@withLock emptyList<Job>()
            activeLock.withLock current@ {
                val life = lifecycle.value
                if (life.closed || life.generation != run.hostGeneration || !run.allowanceCallbacksOpen.value ||
                    run.actionFinished.value || !physical.isCurrentRun(run)) return@current emptyList<Job>()
                // Never revoke an epoch before the exact active-map check. A retired allowance
                // cannot suspend/cancel a newer generation or manufacture a drain receipt.
                lifecycle.value = life.copy(suspended = true)
                physical.requestHostSuspension(DownloadStatus.NEEDS_FOREGROUND)
                noteActivity()
                active.values.toList() + maintenance.values.map { it.job }
            }
        }
        jobs.forEach { it.cancel(CancellationException("Physical download execution allowance ended")) }
    }

    /** The pending member has an ACTUAL registered Job; late/inline callbacks never join it. */
    private suspend fun batchAllowanceExpired(run: DownloadBatchWorkRun): Unit = withContext(NonCancellable) {
        val jobs = commands.withLock {
            val pending = batchWorkRuntime ?: return@withLock emptyList<Job>()
            activeLock.withLock current@ {
                val life = lifecycle.value
                if (life.closed || life.generation != run.hostGeneration || !run.allowanceCallbacksOpen.value ||
                    run.actionFinished.value || !pending.isCurrentRun(run)) return@current emptyList<Job>()
                lifecycle.value = life.copy(suspended = true)
                physicalRuntime?.requestHostSuspension(DownloadStatus.NEEDS_FOREGROUND)
                noteActivity()
                active.values.toList() + maintenance.values.map { it.job }
            }
        }
        jobs.forEach { it.cancel(CancellationException("Batch selection execution allowance ended")) }
    }

    /** Safe even when acquire/close invokes its expiration callback inline in the transfer itself. */
    private suspend fun allowanceExpired(claim: ExecutionClaim) {
        withContext(NonCancellable) {
            val jobs = commands.withLock {
                val life = lifecycle.value
                if (life.closed || life.generation != claim.lifecycleGeneration || !claim.allowanceCallbacksOpen.value)
                    return@withLock emptyList()
                val running = activeLock.withLock { active.toMap() }
                val expiredJob = running[claim.record.id] ?: return@withLock emptyList()
                val maintaining = activeLock.withLock { maintenance.values.toList() }
                // Latch locally BEFORE SQLite: an unavailable database must not permit work
                // outside its OS allowance. Foreground resume advances the epoch under this lock.
                lifecycle.value = life.copy(suspended = true)
                noteActivity()
                try {
                    if (!suspendOwnedRecords(DownloadStatus.NEEDS_FOREGROUND, onlyIfClaim = claim, maintaining = maintaining)) {
                        // The callback was queued for a now-completed/superseded claim. Do not
                        // suspend a healthy current host because a retired allowance reported late.
                        lifecycle.value = life
                        noteActivity()
                        return@withLock listOf(expiredJob)
                    }
                } catch (error: Exception) { publishFailure(classify(error)) }
                running.values.toList() + maintaining.map { it.job }
            }
            jobs.forEach { it.cancel(CancellationException("Background execution allowance ended")) }
            // Never join here: the callback can be running from acquire() or a noncancellable close().
        }
    }

    private suspend fun pauseForPolicy() = cancelAndJoinJobs(restrictOwnedAcquisitionsForPolicy())

    private suspend fun restrictOwnedAcquisitionsForPolicy(): List<Job> = commands.withLock {
        if (lifecycle.value.closed) return@withLock emptyList()
        val jobs = activeLock.withLock {
            physicalRuntime?.let { it.activeTransferJobs() + batchWorkRuntime?.activeNetworkJobs().orEmpty() }
                ?: active.values.toList()
        }
        try { suspendOwnedRecords(DownloadStatus.WAITING_NETWORK) }
        catch (error: Exception) { jobs.forEach { it.cancel() }; throw error }
        jobs
    }

    private suspend fun suspendOwnedRecords(target: DownloadStatus, onlyIfClaim: ExecutionClaim? = null,
        onlyIfMaintenance: MaintenanceRun? = null, maintaining: List<TrackedMaintenance> = emptyList()): Boolean {
        physicalRuntime?.let { physical ->
            // Schema3 publishes no legacy physical writes and releases no ownership early.
            // The registered run settles this status through its canonical finalizer AFTER
            // actual native/file drain. The legacy maintenance path here owns orphan sweeps only.
            if (onlyIfClaim != null || onlyIfMaintenance?.claim != null) return false
            physical.requestHostSuspension(target)
            return true
        }
        var matched = false
        store.update { snapshot ->
            if (onlyIfClaim != null) {
                val current = snapshot.downloads[onlyIfClaim.record.id]
                if (current == null || current.leaseOwner != owner || current.activeAttemptId != onlyIfClaim.attempt.id || current.status !in BUSY)
                    return@update snapshot
            }
            if (onlyIfMaintenance?.claim?.let { !matchesMaintenanceReferences(snapshot, it) } == true) return@update snapshot
            matched = true
            snapshot.copy(downloads = snapshot.downloads.mapValues { (_, record) ->
                val maintenanceClaim = maintaining.firstOrNull { it.run.token == record.leaseOwner }?.run?.claim
                if (target == DownloadStatus.NEEDS_FOREGROUND && maintenanceClaim != null &&
                    matchesMaintenanceReferences(snapshot, maintenanceClaim)) restrictMaintenanceRecord(record, maintenanceClaim.kind)
                else if (record.leaseOwner == owner && !record.userPaused && !record.cleanupPending && record.status in BUSY)
                    record.copy(status = target,
                        failure = if (target == DownloadStatus.NEEDS_FOREGROUND) AppFailure(FailureCode.BACKGROUND_RESTRICTED) else record.failure,
                        updatedEpochMs = clock.epochMs()) else record
            })
        }.getOrThrow()
        return matched
    }

    /** A gate may call this inline from acquire; never join the coroutine invoking the callback. */
    private suspend fun maintenanceAllowanceExpired(run: MaintenanceRun) = withContext(NonCancellable) {
        val jobs = commands.withLock {
            val life = lifecycle.value
            if (life.closed || life.generation != run.lifecycleGeneration || !run.allowanceCallbacksOpen.value ||
                run.actionFinished.value) return@withLock emptyList()
            val maintaining = activeLock.withLock { maintenance.values.toList() }
            val expired = maintaining.firstOrNull { it.run === run } ?: return@withLock emptyList()
            lifecycle.value = life.copy(suspended = true)
            noteActivity()
            try {
                if (!suspendOwnedRecords(DownloadStatus.NEEDS_FOREGROUND, onlyIfMaintenance = run, maintaining = maintaining)) {
                    lifecycle.value = life
                    noteActivity()
                    return@withLock listOf(expired.job)
                }
                publishFailure(AppFailure(FailureCode.BACKGROUND_RESTRICTED))
            } catch (error: Exception) { publishFailure(classify(error)) }
            activeLock.withLock { active.values.toList() } + maintaining.map { it.job }
        }
        jobs.forEach { it.cancel(CancellationException("Maintenance execution allowance ended")) }
    }

    private fun restrictMaintenanceRecord(record: DownloadRecord, kind: MaintenanceKind): DownloadRecord {
        val status = if (kind == MaintenanceKind.FINALIZATION) DownloadStatus.NEEDS_FOREGROUND else record.status
        val failure = AppFailure(FailureCode.BACKGROUND_RESTRICTED)
        if (record.status == status && record.failure == failure) return record
        return record.copy(status = status, failure = failure, updatedEpochMs = clock.epochMs())
    }

    suspend fun resumePending() {
        // Do not retain a foreground announcement behind a suspended checkpoint and replay it
        // after that drain's ACK. The locked recheck also covers admission racing the first read.
        requireOpen()
        requireForegroundResumeAdmission()
        commands.withLock {
            requireOpen()
            requireForegroundResumeAdmission()
            val life = lifecycle.value
            if (life.suspended) require(life.generation < Long.MAX_VALUE)
            lifecycle.value = life.copy(suspended = false, generation = life.generation + if (life.suspended) 1 else 0)
            noteActivity()
            if (physicalRuntime != null) physicalRuntime.resumeHostPending()
            else store.update { snapshot -> snapshot.copy(downloads = snapshot.downloads.mapValues { (_, record) ->
                if (record.failure?.code == FailureCode.BACKGROUND_RESTRICTED && !record.liveLease(clock.epochMs()) &&
                    (record.cleanupPending || record.status == DownloadStatus.COMPLETE)) return@mapValues record.copy(failure = null)
                if (!record.userPaused && !record.cleanupPending && !record.liveLease(clock.epochMs()) &&
                    record.status in setOf(DownloadStatus.NEEDS_FOREGROUND, DownloadStatus.PAUSED))
                    record.copy(status = if (record.id in snapshot.downloadFinalizations) DownloadStatus.VERIFYING else queuedStatus(snapshot),
                        failure = queueFailure(snapshot), updatedEpochMs = clock.epochMs()) else record
            }) }.getOrThrow()
        }
        signalWork()
    }

    private fun requireForegroundResumeAdmission() {
        requireNoFailedBackgroundDrain()
        if (lifecycle.value.drainsInFlight != 0)
            throw VeyraException(AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_in_progress"))
    }

    private fun requireNoFailedBackgroundDrain() {
        (lifecycle.value.drainFailure ?: cleanupFailure.value)?.let { throw VeyraException(it) }
    }

    /**
     * Serialized, explicit cleanup retry. Each previously failed owner gets at most one attempt
     * at each remaining stage per call; already successful stages never run again. This bounds
     * retries, NOT the wall time of an accepted native close. Real blocked consumption is awaited.
     * A failure never turns into success merely because a later retry consumes all owners.
     */
    suspend fun close(): Unit = withContext(NonCancellable) {
        closeLock.withLock {
            if (dependencyReleaseSafeAfterClose) {
                closeFailure?.let { throw VeyraException(it) }
                return@withLock
            }
            try {
                var wasStarted = false
                var retrying = emptyList<CleanupOwner>()
                var physicalEpochs = emptyList<DownloadOwnershipEpochs.Epoch>()
                val jobs = commands.withLock {
                    val life = lifecycle.value
                    wasStarted = life.started
                    // Only an already FAILED owner is an explicit retry. A normal finalizer
                    // that first fails while this close joins it is not silently retried here.
                    retrying = cleanupOwners.value.values.filter { it.failed.value }
                    lifecycle.value = life.copy(closed = true, suspended = true)
                    noteActivity()
                    val running = activeLock.withLock {
                        physicalEpochs = physicalRuntime?.ownedEpochs().orEmpty()
                        active.values.toList() + maintenance.values.map { it.job }
                    }
                    if (!life.closed && wasStarted) {
                        try { suspendOwnedRecords(DownloadStatus.QUEUED) }
                        catch (error: Throwable) { rememberCloseFailure(error) }
                    }
                    running
                }
                cancelAndJoinJobs(listOfNotNull(scheduler, reconciler, policyObserver, networkObserver) + jobs)
                for (retained in retrying) {
                    if (cleanupOwners.value[retained.token] === retained) attemptCleanup(retained, retryFailed = true)
                }
                val physical = physicalRuntime
                for (epoch in physicalEpochs) {
                    if (physical == null || !physical.epochs.drain(epoch)) {
                        if (closeFailure == null) closeFailure = restartRequiredFailure()
                    }
                }
                physical?.drainCheckpointFailure()?.let { rememberCloseFailure(VeyraException(it)) }
                cleanupFailure.value?.let { if (closeFailure == null) closeFailure = it }
                if (!hasPendingCleanupOwners && physical?.epochs?.allLocalOwnersConsumed() != false) {
                    if (wasStarted) {
                        try { store.flush().getOrThrow() }
                        catch (error: Throwable) { rememberCloseFailure(error) }
                    }
                    scope.coroutineContext.job.cancelAndJoin()
                    activeLock.withLock {
                        check(active.isEmpty() && maintenance.isEmpty() && !hasPendingCleanupOwners &&
                            physical?.epochs?.allLocalOwnersConsumed() != false)
                        // This is the only writer. No admission is possible after closed=true.
                        dependencyReleaseSafe.value = true
                    }
                } else if (closeFailure == null) {
                    closeFailure = restartRequiredFailure()
                }
            } catch (error: Throwable) {
                // An exception before the explicit drain receipt retains the host dependencies.
                rememberCloseFailure(error)
            }
            closeFailure?.let { throw VeyraException(it) }
        }
    }

    private fun rememberCloseFailure(error: Throwable) {
        if (closeFailure == null) closeFailure = cleanupFailure.value ?: when (error) {
            is VeyraException -> error.failure
            is Exception -> classify(error)
            else -> AppFailure(FailureCode.UNEXPECTED)
        }
        publishFailure(closeFailure)
    }

    private fun registerCleanup(owner: CleanupOwner) {
        cleanupRegistry.register(owner)
    }

    /**
     * Resource/allowance closes and failure publication run OUTSIDE admission locks. The initial
     * file-only rollback may keep its existing claim lock, but cannot invoke native callbacks or
     * acquire commands/activeLock again; report=false defers that failure latch until lock exit.
     */
    private suspend fun attemptCleanup(owner: CleanupOwner, retryFailed: Boolean = false, report: Boolean = true) {
        if (owner is DownloadPhysicalCleanup) {
            // Runtime owns this SAME cleanup mutex. Dispatch before locking; never self-deadlock.
            owner.runtime.attemptCleanup(owner, retryFailed, report)
            return
        }
        if (owner is DownloadBatchWorkCleanup) {
            owner.runtime.attemptCleanup(owner, retryFailed, report)
            return
        }
        owner.lock.withLock {
            try {
                // Temporary compensation may already have thrown before the enclosing claim failed.
                // Register/adopt it, but never turn this initial unwind into an automatic retry.
                if (owner.failed.value && !retryFailed) throw VeyraException(restartRequiredFailure())
                if (owner is DownloadLeaseCleanup) {
                    owner.close()
                    cleanupRegistry.remove(owner)
                    return@withLock
                }
                val steps = when (owner) {
                    is ExecutionCleanup -> 7
                    is MaintenanceCleanup -> 3
                    is RollbackCleanup -> 2
                    is DownloadLeaseCleanup -> error("File-only cleanup already handled")
                    is DownloadPhysicalCleanup -> error("Physical cleanup already dispatched")
                    is DownloadBatchWorkCleanup -> error("Batch cleanup already dispatched")
                }
                while (owner.nextStep < steps) {
                    when (owner) {
                        is ExecutionCleanup -> when (owner.nextStep) {
                            0 -> {
                                owner.claim.validationReaders.requireReleased()
                                owner.resource?.close()
                            }
                            1 -> {
                                owner.allowance?.close()
                                owner.claim.allowanceCallbacksOpen.value = false
                            }
                            2 -> {
                                if (!retryFailed && owner.partialLeases.any { it.failed.value })
                                    throw VeyraException(restartRequiredFailure())
                                owner.partialLeases.forEach { it.close() }
                            }
                            3 -> owner.publicationLease?.close()
                            4 -> owner.claim.generationLease.close()
                            5 -> owner.claim.slot.close()
                            6 -> finishExecution(owner.claim, owner.failure, owner.cancelled, owner.finalization)
                            else -> error("Invalid execution cleanup stage")
                        }
                        is MaintenanceCleanup -> when (owner.nextStep) {
                            0 -> {
                                owner.run.claim?.validationReaders?.requireReleased()
                                owner.allowance?.close()
                                owner.run.allowanceCallbacksOpen.value = false
                            }
                            1 -> (owner.run.claim?.fileLease ?: owner.run.orphanLease)?.close()
                            2 -> {
                                finishMaintenance(owner.run)
                                // Defer after the lease-release transaction so this receipt belongs
                                // to the current unowned record, not the pre-release Room revision.
                                if (owner.admissionFailed) owner.run.claim?.let { claim ->
                                    store.state.value.downloads[claim.record.id]?.let { deferClaim(it, claim.kind.claimKind()) }
                                }
                            }
                            else -> error("Invalid maintenance cleanup stage")
                        }
                        is RollbackCleanup -> when (owner.nextStep) {
                            0 -> owner.leases.close()
                            1 -> {
                                owner.executionClaim?.let { finishExecution(it, owner.failure, owner.cancelled) }
                                owner.maintenanceClaim?.let { finishMaintenanceRollback(it) }
                            }
                            else -> error("Invalid rollback cleanup stage")
                        }
                        is DownloadLeaseCleanup -> error("File-only cleanup already handled")
                        is DownloadPhysicalCleanup -> error("Physical cleanup already dispatched")
                        is DownloadBatchWorkCleanup -> error("Batch cleanup already dispatched")
                    }
                    owner.nextStep++
                }
                cleanupRegistry.remove(owner)
            } catch (_: Throwable) {
                // Even CancellationException (or a typed error) is not proof of consumption. Keep
                // this exact stage and every dependent owner; never log the native exception text.
                owner.failed.value = true
                cleanupFailure.update { it ?: restartRequiredFailure() }
            }
        }
        if (report) publishCleanupFailure()
    }

    /**
     * An accepted canonical finish threw without a typed outcome. This synchronous callback
     * may run while commands is held: latch the existing atomic admission fence, not lifecycle
     * under an unlocked read/copy. Normal publication below later suspends/cancels owned jobs.
     */
    private fun failPhysicalCheckpointAdmission() {
        cleanupFailure.update { it ?: restartRequiredFailure() }
        publishFailure(restartRequiredFailure())
        noteActivity()
    }

    /** Called after claim locks exit, including when a synchronous Files compensation failed. */
    private suspend fun publishCleanupFailure() {
        if (cleanupFailure.value == null && cleanupOwners.value.values.none { it.failed.value }) return
        val jobs = commands.withLock {
            val sticky = lifecycle.value.drainFailure ?: cleanupFailure.value ?: restartRequiredFailure()
            cleanupFailure.update { it ?: sticky }
            lifecycle.value = lifecycle.value.copy(suspended = true, drainFailure = sticky)
            publishFailure(sticky)
            noteActivity()
            activeLock.withLock { active.values.toList() + maintenance.values.map { it.job } }
        }
        // Cancel-only: this may be the currently registered owner's own finalizer.
        jobs.forEach { it.cancel(CancellationException("Download cleanup ownership was not consumed")) }
    }

    private suspend fun cancelAndJoinJobs(jobs: List<Job>) {
        val caller = currentCoroutineContext()[Job]
        jobs.forEach { it.cancel() }
        jobs.filterNot { it === caller }.joinAll()
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private suspend fun schedule() {
        val physical = physicalRuntime
        if (physical != null) {
            val admissionOwner = currentCoroutineContext().job // Capture BEFORE NonCancellable.
            var staleBatchJobs = emptyList<Job>()
            try {
                commands.withLock {
                    activeLock.withLock admission@ {
                        val view = store.acquisitionView()
                        staleBatchJobs = batchWorkRuntime?.staleIntentJobs(view.acquisition).orEmpty()
                        val state = physicalHostState(view.snapshot)
                        if (!state.started || state.closed || state.suspended || state.drainsInFlight != 0 || state.failure != null)
                            return@admission
                        if (!state.offlineOnly && state.network == DownloadNetworkState.CONNECTED) {
                            for (id in physical.candidates()) {
                                admissionOwner.ensureActive()
                                if (cleanupFailure.value != null || physical.transferCount() + (batchWorkRuntime?.networkCount() ?: 0) >=
                                    store.acquisitionViews.value.snapshot.settings.downloadParallelism.coerceIn(1, 4)) break
                                if (id in active) continue
                                // Exact durable claim AND ATOMIC actual-Job installation form one
                                // owned segment. No cancellation gap strands physical resources.
                                withContext(NonCancellable) {
                                    admissionOwner.ensureActive()
                                    val run = physical.claimExecution(id, admissionOwner) ?: return@withContext
                                    physical.installExecution(run, scope)
                                }
                            }
                        }
                        // Local committed attachment/admission remains eligible offline. READY
                        // selection also needs connectivity, native allowance and an actual slot.
                        withContext(NonCancellable) {
                            batchWorkRuntime?.installCandidate(store.acquisitionViews.value.acquisition, scope, admissionOwner)
                        }
                    }
                }
            } finally {
                // Batch intent changes revoke only their captured member operation. Never join
                // under admission locks or reinterpret a current self-CLAIMED request as stale.
                staleBatchJobs.forEach { it.cancel(CancellationException("Download batch intent changed")) }
                withContext(NonCancellable) { publishCleanupFailure() }
            }
            return
        }
        val life = lifecycle.value
        if (!life.started || life.closed || life.suspended || store.state.value.settings.offlineOnly ||
            networkState.value != DownloadNetworkState.CONNECTED) return
        try { activeLock.withLock {
            val candidates = store.state.value.downloads.values.filter { it.id !in active }
                .sortedWith(compareByDescending<DownloadRecord> { it.priority }.thenBy { it.createdEpochMs }.thenBy { it.id })
            for (candidate in candidates) {
                if (cleanupFailure.value != null || active.size >= store.state.value.settings.downloadParallelism.coerceIn(1, 4)) break
                val claim = claimExecution(candidate.id) ?: continue
                // ATOMIC enters the try/finally even if the parent is cancelled just after the durable claim.
                val task = scope.launch(start = CoroutineStart.ATOMIC) {
                    try { execute(claim) }
                    finally { withContext(NonCancellable) {
                        activeLock.withLock { active.remove(claim.record.id); noteActivity() }
                        signalWork()
                    } }
                }
                active[claim.record.id] = task
                noteActivity()
            }
        } } finally { withContext(NonCancellable) { publishCleanupFailure() } }
    }

    private suspend fun claimExecution(id: String): ExecutionClaim? {
        var claim: ExecutionClaim? = null
        var slot: DownloadTransferSlot? = null
        var generationLease: DownloadFileLease? = null
        val partial = DownloadLeaseCleanupScope(cleanupRegistry)
        try {
            store.update { snapshot ->
                val record = snapshot.downloads[id] ?: return@update snapshot
                val now = clock.epochMs()
                if (!lifecycle.value.started || lifecycle.value.closed || !DownloadClaimRules.acquisitionReady(snapshot, record,
                        now, networkState.value, lifecycle.value.suspended,
                        claimDeferredUntil(record, DownloadClaimKind.ACQUISITION, claimDeferrals.value))) return@update snapshot
                val limit = snapshot.settings.downloadParallelism.coerceIn(1, 4)
                val item = snapshot.media[record.mediaId] ?: return@update snapshot
                slot = files.tryTransferSlot(limit, partial)
                if (slot == null) { deferClaim(record, DownloadClaimKind.ACQUISITION); return@update snapshot }
                val attemptId = newEntityId("download-attempt")
                val bound = record.executionBindingId?.let(snapshot.playbackBindings::get)?.let {
                    it.mediaId == record.mediaId && it.selectionOrdinal == 0 && it.firstResultKind == FirstResultKind.VIDEO &&
                        it.selectedVideoId == it.firstResultId && it.firstResultId.matches(Regex("[A-Za-z0-9_-]{11}"))
                } == true
                // Bytes may only be resumed with their immutable selection AND representation.
                // An unbound pre-attempt directory must not be paired with a fresh title search.
                var path = record.stagingPath.takeIf { bound && !record.representationId.isNullOrBlank() }
                if (path != null) generationLease = files.tryLeasePaths(listOf(path), partial)
                val retained = generationLease != null
                if (!retained) {
                    path = files.part(record.mediaId, attemptId).toString()
                    generationLease = files.tryLeasePaths(listOf(path), partial)
                    if (generationLease == null) { deferClaim(record, DownloadClaimKind.ACQUISITION); return@update snapshot }
                }
                require(record.validatorGeneration < Long.MAX_VALUE && record.attemptCount < Int.MAX_VALUE)
                val generation = maxOf(now.coerceAtLeast(0), record.validatorGeneration + 1)
                val attempt = DownloadAttempt(attemptId, id, record.attemptCount + 1, startedEpochMs = now,
                    bytesDownloaded = if (retained) record.bytesDownloaded else 0,
                    totalBytes = if (retained) record.totalBytes else null, stagingPath = path,
                    representationId = record.representationId.takeIf { bound }, executionBindingId = record.executionBindingId, validatorGeneration = generation)
                val next = record.copy(status = DownloadStatus.RESOLVING, activeAttemptId = attemptId,
                    attemptCount = attempt.number, failure = null, stagingPath = path, bytesDownloaded = attempt.bytesDownloaded,
                    totalBytes = attempt.totalBytes, representationId = attempt.representationId, validatorGeneration = generation,
                    leaseOwner = owner, leaseExpiresEpochMs = now + leaseDurationMs, updatedEpochMs = now)
                claim = ExecutionClaim(next, item, attempt, generationLease!!, slot!!, lifecycle.value.generation)
                snapshot.copy(downloads = snapshot.downloads + (id to next), downloadAttempts = snapshot.downloadAttempts + (attemptId to attempt))
            }.getOrThrow()
            if (claim == null) rollbackClaim(partial, listOfNotNull(generationLease, slot))
            else clearClaimDeferrals(id, DownloadClaimKind.ACQUISITION)
            return claim
        } catch (error: Throwable) {
            // A cancellation on the dispatcher return may happen AFTER the SQLite claim committed.
            // Release only that exact attempt; a rolled-back claim makes this an inert CAS.
            rollbackClaim(partial, listOfNotNull(generationLease, slot), executionClaim = claim, error = error)
            // The transform can have allocated locks before SQLite rolls it back. A non-null
            // tentative claim is not evidence that a retry/terminal state became durable.
            if (error !is CancellationException)
                store.state.value.downloads[id]?.let { deferClaim(it, DownloadClaimKind.ACQUISITION) }
            throw error
        }
    }

    /** Initial FILE-ONLY compensation under the existing claim lock; no allowance callbacks. */
    private suspend fun rollbackClaim(partial: DownloadLeaseCleanupScope, leases: List<DownloadFileLease>,
        executionClaim: ExecutionClaim? = null, maintenanceClaim: MaintenanceClaim? = null, error: Throwable? = null) {
        val pending = partial.pending()
        if (pending.isEmpty() && leases.isEmpty() && executionClaim == null && maintenanceClaim == null) return
        withContext(NonCancellable) {
            val cleanup = RollbackCleanup(DownloadLeaseCleanup(pending + leases), executionClaim, maintenanceClaim,
                failure = when (error) {
                    null, is CancellationException -> null
                    is Exception -> classify(error)
                    else -> AppFailure(FailureCode.UNEXPECTED)
                }, cancelled = error is CancellationException)
            cleanup.failed.value = pending.any { it.failed.value }
            registerCleanup(cleanup)
            partial.adopted()
            // Publication/cancellation is deferred until schedule/runMaintenance exits its locks.
            attemptCleanup(cleanup, report = false)
        }
    }

    private suspend fun execute(claim: ExecutionClaim) {
        val id = claim.record.id
        val attemptId = claim.attempt.id
        var allowance: DownloadExecutionLease? = null
        var publicationLease: DownloadFileLease? = null
        var planResource: PlaybackResource? = null
        var failure: AppFailure? = null
        var cancelled = false
        var intendedFinalization: DownloadFinalization? = null
        val partial = DownloadLeaseCleanupScope(cleanupRegistry)
        try {
            coroutineScope {
                val executionJob = currentCoroutineContext().job
                val heartbeat = launch {
                    while (isActive) {
                        delay(heartbeatMs)
                        try { updateOwned(claim) { it } }
                        catch (error: Exception) {
                            if (error !is CancellationException) { failure = classify(error); publishFailure(failure) }
                            executionJob.cancel(CancellationException("Download lease revoked", error))
                        }
                    }
                }
                try {
                    currentCoroutineContext().ensureActive()
                    updateOwned(claim) { it }
                    withContext(NonCancellable) { allowance = executionGate.acquire { allowanceExpired(claim) } }
                    currentCoroutineContext().ensureActive()
                    val binding = resolver.acquireBinding(claim.item, claim.record.executionBindingId)
                    updateOwned(claim) { it.copy(executionBindingId = binding.id) }
                    val source = gateway.resolve(binding, capabilities, ResolvePurpose.DOWNLOAD)
                    planResource = source.resource
                    if (source.isLocal || source.executionBinding?.let { it != binding } == true || source.item?.let {
                            it.source.provider != SourceProvider.YOUTUBE || it.source.canonicalId != binding.selectedVideoId
                        } == true)
                        throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "download_gateway_identity"))
                    // DOWNLOAD transport describes kmpYT's acquisition, not the materialized
                    // file (HLS/DASH/segmented may be remuxed by the central library).
                    if (!capabilities.supportsLocalFiles || !capabilities.supports(StreamTransport.PROGRESSIVE, source.mimeType, source.codecs))
                        throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_CODEC, "download_execution_not_supported"))
                    val representation = claim.record.representationId ?: source.representationId
                    updateOwned(claim) { it.copy(status = DownloadStatus.RUNNING, representationId = representation) }
                    val part = withContext(Dispatchers.IO) { files.prepareStagingPath(claim.record.stagingPath!!) }
                    var lastProgress: Long? = null
                    val result = gateway.download(binding, part.toString(), capabilities, representation) { progress ->
                        currentCoroutineContext().ensureActive()
                        if (progress.bytesDownloaded < 0 || progress.totalBytes?.let { it < progress.bytesDownloaded } == true)
                            throw VeyraException(AppFailure(FailureCode.CORRUPT_MEDIA, "invalid_transfer_count"))
                        val stamp = clock.monotonicMs()
                        if (lastProgress == null || stamp - lastProgress!! >= 500 || progress.bytesDownloaded == progress.totalBytes) {
                            updateOwned(claim) { it.copy(bytesDownloaded = progress.bytesDownloaded, totalBytes = progress.totalBytes,
                                lastProgressEpochMs = clock.epochMs()) }
                            lastProgress = stamp
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (result.bytesWritten <= 0 || representation != null && representation != result.representationId)
                        throw VeyraException(AppFailure(FailureCode.CORRUPT_MEDIA, "transfer_identity_changed"))
                    if (!capabilities.supports(StreamTransport.PROGRESSIVE, result.mimeType, result.codecs))
                        throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_CODEC, "download_output_not_supported"))
                    updateOwned(claim, DownloadAttemptStatus.VALIDATING) { it.copy(status = DownloadStatus.VERIFYING,
                        bytesDownloaded = result.bytesWritten, totalBytes = result.bytesWritten, representationId = result.representationId) }
                    if (storageOperation { files.fileSystem.metadata(part).size } != result.bytesWritten)
                        throw VeyraException(AppFailure(FailureCode.CORRUPT_MEDIA, "transfer_size_changed"))
                    val hash = files.sha256(part)
                    val staged = OfflineAsset("$id-$attemptId", claim.item.id, part.toString(), result.bytesWritten,
                        mimeType = result.mimeType, codecs = result.codecs, sha256 = hash, representationId = result.representationId,
                        validatorGeneration = claim.record.validatorGeneration, executionBindingId = binding.id)
                    val duration = validate(staged, claim.validationReaders)
                    files.verifiedFile(staged)
                    val intent = DownloadFinalization(id, claim.item.id, part.toString(),
                        files.finalPath(claim.item.id, claim.record.validatorGeneration, result.mimeType, attemptId).toString(),
                        hash, result.bytesWritten, attemptId, duration, result.mimeType, result.codecs, binding.id,
                        result.representationId, claim.record.validatorGeneration)
                    intendedFinalization = intent
                    // The independently-running integrity worker must not inspect a just-committed
                    // file before this validated writer has published its session check receipt.
                    publicationLease = files.tryLeasePaths(listOf(intent.finalPath), partial)
                        ?: throw CancellationException("Final publication path is still owned")
                    store.update { snapshot ->
                        val record = requireOwned(snapshot, claim)
                        if (record.status != DownloadStatus.VERIFYING || id in snapshot.downloadFinalizations) throw CancellationException("Finalization superseded")
                        snapshot.copy(downloadFinalizations = snapshot.downloadFinalizations + (id to intent),
                            downloadAttempts = snapshot.downloadAttempts + (attemptId to snapshot.downloadAttempts.getValue(attemptId)
                                .copy(status = DownloadAttemptStatus.FINALIZING)))
                    }.getOrThrow()
                    // Intent is ALREADY durable. Rename inside the next exact CAS is short and cannot
                    // run after a winning cancel/removal transaction. A failed SQLite commit is replayable.
                    commitFinalization(intent, owner, claim.lifecycleGeneration, binding, alreadyPublished = false)
                } finally { withContext(NonCancellable) { heartbeat.cancelAndJoin() } }
            }
        } catch (error: CancellationException) { cancelled = failure == null; throw error }
        catch (error: Exception) {
            failure = classify(error)
            if (failure?.code == FailureCode.BACKGROUND_RESTRICTED) allowanceExpired(claim)
        } finally {
            withContext(NonCancellable) {
                val cleanup = ExecutionCleanup(claim, planResource, allowance, publicationLease,
                    failure, cancelled, intendedFinalization, partial.pending())
                registerCleanup(cleanup) // BEFORE the first fallible close, while the job is registered.
                partial.adopted()
                attemptCleanup(cleanup)
            }
        }
    }

    private fun requireOwned(snapshot: AppSnapshot, claim: ExecutionClaim): DownloadRecord {
        val record = snapshot.downloads[claim.record.id] ?: throw CancellationException("Download removed")
        if (record.leaseOwner != owner || record.activeAttemptId != claim.attempt.id || !record.liveLease(clock.epochMs()) ||
            record.userPaused || record.cleanupPending || record.status !in BUSY || snapshot.settings.offlineOnly ||
            lifecycle.value.closed || lifecycle.value.suspended || claim.slot.index >= snapshot.settings.downloadParallelism)
            throw CancellationException("Download no longer owns executable intent")
        if (lifecycle.value.generation != claim.lifecycleGeneration) throw CancellationException("Download host epoch changed")
        return record
    }

    private suspend fun updateOwned(claim: ExecutionClaim, attemptStatus: DownloadAttemptStatus? = null,
        transform: (DownloadRecord) -> DownloadRecord) {
        store.update { snapshot ->
            val record = requireOwned(snapshot, claim)
            val updated = transform(record).copy(updatedEpochMs = clock.epochMs(), leaseExpiresEpochMs = clock.epochMs() + leaseDurationMs)
            val previousAttempt = snapshot.downloadAttempts.getValue(claim.attempt.id)
            val attempt = previousAttempt.copy(status = attemptStatus ?: previousAttempt.status,
                bytesDownloaded = updated.bytesDownloaded, totalBytes = updated.totalBytes,
                representationId = updated.representationId, executionBindingId = updated.executionBindingId)
            snapshot.copy(downloads = snapshot.downloads + (record.id to updated), downloadAttempts = snapshot.downloadAttempts + (attempt.id to attempt))
        }.getOrThrow()
    }

    private suspend fun finishExecution(claim: ExecutionClaim, failure: AppFailure?, cancelled: Boolean,
        expectedIntent: DownloadFinalization? = null) {
        try {
            store.update { snapshot ->
                val record = snapshot.downloads[claim.record.id] ?: return@update snapshot
                val now = clock.epochMs()
                val priorAttempt = snapshot.downloadAttempts[claim.attempt.id]
                val storedIntent = snapshot.downloadFinalizations[record.id]
                // A failed intent INSERT can leave no intent at all. Still release this exact
                // durable attempt; only a DIFFERENT stored intent belongs to a successor.
                val intentUnchanged = storedIntent == null || storedIntent == expectedIntent
                val owns = record.leaseOwner == owner && record.activeAttemptId == claim.attempt.id && intentUnchanged
                val automatic = owns && record.status in BUSY && !cancelled && failure?.code in RETRYABLE && record.automaticRetryCount < MAX_RETRIES
                val background = !lifecycle.value.closed && lifecycle.value.suspended
                val resumedHost = record.status == DownloadStatus.NEEDS_FOREGROUND && !background && !lifecycle.value.closed &&
                    !record.userPaused && !record.cleanupPending
                val status = when {
                    resumedHost -> queuedStatus(snapshot)
                    record.status !in BUSY -> record.status
                    background || failure?.code == FailureCode.BACKGROUND_RESTRICTED -> DownloadStatus.NEEDS_FOREGROUND
                    failure?.code == FailureCode.OFFLINE_POLICY || automatic -> DownloadStatus.WAITING_NETWORK
                    cancelled -> DownloadStatus.QUEUED
                    else -> DownloadStatus.FAILED
                }
                val updated = if (!owns) record else record.copy(status = status,
                    failure = if (resumedHost) queueFailure(snapshot) else if (record.status !in BUSY) record.failure else if (background) AppFailure(FailureCode.BACKGROUND_RESTRICTED)
                        else if (cancelled) null else failure,
                    leaseOwner = null, leaseExpiresEpochMs = null, activeAttemptId = null,
                    automaticRetryCount = if (automatic) record.automaticRetryCount + 1 else record.automaticRetryCount,
                    nextRetryEpochMs = if (automatic) now + retryDelayMs(record.automaticRetryCount, failure?.retryAfterMs) else record.nextRetryEpochMs,
                    updatedEpochMs = now)
                // Even a superseded writer may acknowledge its OWN old attempt after releasing its
                // native lock. It must not overwrite the new record/lease or a winning success.
                val attempt = priorAttempt?.takeUnless { it.status == DownloadAttemptStatus.SUCCEEDED ||
                    record.activeAttemptId == it.id && !owns || !intentUnchanged }?.copy(
                    status = if (record.status == DownloadStatus.CANCELLED) DownloadAttemptStatus.CANCELLED
                        else if (cancelled) DownloadAttemptStatus.PAUSED else DownloadAttemptStatus.FAILED,
                    finishedEpochMs = now, failure = failure)
                snapshot.copy(downloads = if (owns) snapshot.downloads + (record.id to updated) else snapshot.downloads,
                    downloadAttempts = if (attempt == null) snapshot.downloadAttempts else snapshot.downloadAttempts + (attempt.id to attempt))
            }.getOrThrow()
        } catch (error: Exception) { publishFailure(classify(error)) }
    }

    private suspend fun validate(asset: OfflineAsset, readers: DownloadValidationReaderRetention): Long? =
        readers.validate {
            when (val result = validator.validate(asset)) {
                is AssetValidation.Valid -> result.durationMs
                is AssetValidation.Invalid -> throw VeyraException(result.failure)
            }
        }

    private suspend fun commitFinalization(intent: DownloadFinalization, claimant: String, lifecycleGeneration: Long,
        expectedBinding: PlaybackBinding, alreadyPublished: Boolean): Boolean {
        var committed = false
        var verifiedBinding: PlaybackBinding? = null
        val asset = OfflineAsset("${intent.downloadId}-${intent.attemptId ?: intent.validatorGeneration}", intent.mediaId,
            intent.finalPath, intent.sizeBytes, mimeType = intent.mimeType, codecs = intent.codecs, sha256 = intent.sha256,
            representationId = intent.representationId, validatorGeneration = intent.validatorGeneration,
            completedEpochMs = clock.epochMs(), executionBindingId = intent.executionBindingId, measuredDurationMs = intent.mediaDurationMs)
        store.update { snapshot ->
            val record = snapshot.downloads[intent.downloadId] ?: return@update snapshot
            if (snapshot.downloadFinalizations[intent.downloadId] != intent || record.leaseOwner != claimant || !record.liveLease(clock.epochMs()) ||
                record.activeAttemptId != intent.attemptId || record.cleanupPending || record.userPaused || record.status != DownloadStatus.VERIFYING) return@update snapshot
            if (lifecycle.value.closed || lifecycle.value.suspended || lifecycle.value.generation != lifecycleGeneration)
                return@update snapshot
            requireMaterializedBinding(snapshot, record, intent.mediaId, intent.executionBindingId, intent.representationId)
            verifiedBinding = snapshot.playbackBindings.getValue(intent.executionBindingId!!)
            if (verifiedBinding != expectedBinding)
                throw VeyraException(AppFailure(FailureCode.CORRUPT_MEDIA, "offline_execution_binding_changed"))
            if (record.asset != null && record.asset.uri != intent.finalPath)
                throw VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "prior_asset_requires_cleanup"))
            if (!alreadyPublished) files.atomicPublish(intent.stagingPath, intent.finalPath)
            val attempt = intent.attemptId?.let(snapshot.downloadAttempts::get)?.copy(status = DownloadAttemptStatus.SUCCEEDED,
                finishedEpochMs = clock.epochMs(), bytesDownloaded = intent.sizeBytes, totalBytes = intent.sizeBytes, failure = null)
            committed = true
            snapshot.copy(downloads = snapshot.downloads + (record.id to record.copy(status = DownloadStatus.COMPLETE,
                asset = asset, bytesDownloaded = intent.sizeBytes, totalBytes = intent.sizeBytes, stagingPath = null,
                failure = null, leaseOwner = null, leaseExpiresEpochMs = null, activeAttemptId = null, nextRetryEpochMs = null,
                validatorGeneration = intent.validatorGeneration, executionBindingId = intent.executionBindingId,
                representationId = intent.representationId, updatedEpochMs = clock.epochMs())),
                downloadAttempts = if (attempt == null) snapshot.downloadAttempts else snapshot.downloadAttempts + (attempt.id to attempt),
                downloadFinalizations = snapshot.downloadFinalizations - record.id)
        }.getOrThrow()
        if (committed) checkedAssets.update { it + (intent.downloadId to CheckedAsset(asset, verifiedBinding!!)) }
        return committed
    }

    /** Startup AND periodic reconciliation; unexpired foreign owners are never relabelled or cleared. */
    private suspend fun reconcile() = maintenanceLock.withLock {
        val physical = physicalRuntime
        if (physical != null) {
            store.acquisitionView() // Refresh the ONE canonical owner, never combine its public flows.
            physical.reconcilePass()
            maybeSweepRetiredStaging()
            return@withLock
        }
        val ids = store.flush().getOrThrow().downloads.keys.toList()
        var checked = 0
        for (id in ids) {
            currentCoroutineContext().ensureActive()
            val record = store.state.value.downloads[id] ?: continue
            try {
                when {
                    record.cleanupPending -> cleanup(id)
                    id in store.state.value.downloadFinalizations -> recoverFinalization(id)
                    lifecycle.value.checksActivated && record.status == DownloadStatus.COMPLETE &&
                        !assetChecked(store.state.value, record, checkedAssets.value) &&
                        checked < MAX_ASSET_CHECKS_PER_PASS -> { checked++; checkCompletedAsset(id) }
                    DownloadClaimRules.expiredRecoveryReady(store.state.value, record, clock.epochMs(),
                        claimDeferredUntil(record, DownloadClaimKind.EXPIRED_RECOVERY, claimDeferrals.value)) -> releaseExpired(id)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { publishFailure(classify(error)) }
        }
        maybeSweepRetiredStaging()
    }

    private suspend fun maybeSweepRetiredStaging() {
        val stamp = clock.monotonicMs()
        // Orphan staging deletion is not a user-visible asset validation. It yields while any
        // canonical download/finalization is pending so cleanup cannot consume another owner's
        // gate/local retry slot. Scheduling-only bootstrap never starts this scheduler.
        val pending = physicalRuntime?.let { physical ->
            val state = store.acquisitionViews.value.acquisition
            state.physicalFinalizations.isNotEmpty() || state.physicalDownloads.values.any {
                it.cleanupPending || it.maintenanceKind != null || it.activeAttemptId != null ||
                    it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.VERIFYING,
                        DownloadStatus.WAITING_NETWORK, DownloadStatus.NEEDS_FOREGROUND)
            }
        } ?: false
        if (!pending && !lifecycle.value.closed && !lifecycle.value.suspended &&
            (lastSweepMs == null || stamp - lastSweepMs!! >= 5_000)) {
            sweepRetiredStaging()
            lastSweepMs = stamp
        }
    }

    private suspend fun releaseExpired(id: String) {
        try {
            store.update { snapshot ->
                val record = snapshot.downloads[id] ?: return@update snapshot
                if (!lifecycle.value.started || lifecycle.value.closed || lifecycle.value.suspended ||
                    !DownloadClaimRules.expiredRecoveryReady(snapshot, record, clock.epochMs(),
                        claimDeferredUntil(record, DownloadClaimKind.EXPIRED_RECOVERY, claimDeferrals.value))) return@update snapshot
                val target = if (record.status !in BUSY) record.status else if (record.userPaused) DownloadStatus.PAUSED else queuedStatus(snapshot)
                snapshot.copy(downloads = snapshot.downloads + (id to record.copy(status = target,
                    leaseOwner = null, leaseExpiresEpochMs = null, activeAttemptId = null, updatedEpochMs = clock.epochMs())))
                // Do not fabricate an old writer's acknowledgement. Its file lock may still be alive.
            }.getOrThrow()
        } catch (error: Exception) {
            if (error !is CancellationException)
                store.state.value.downloads[id]?.let { deferClaim(it, DownloadClaimKind.EXPIRED_RECOVERY) }
            throw error
        }
    }

    private suspend fun claimMaintenance(id: String, kind: MaintenanceKind): MaintenanceClaim? {
        var claimed: MaintenanceClaim? = null
        var fileLease: DownloadFileLease? = null
        val partial = DownloadLeaseCleanupScope(cleanupRegistry)
        val token = newEntityId("download-maintenance")
        try {
            store.update { snapshot ->
                val record = snapshot.downloads[id] ?: return@update snapshot
                val now = clock.epochMs()
                val life = lifecycle.value
                if (!life.started || life.closed || record.liveLease(now) ||
                    kind == MaintenanceKind.CHECK_ASSET && !life.checksActivated) return@update snapshot
                val intent = snapshot.downloadFinalizations[id]
                val checked = assetChecked(snapshot, record, checkedAssets.value)
                if (!DownloadClaimRules.maintenanceIntent(snapshot, record, kind, checked) ||
                    (record.nextRetryEpochMs ?: 0) > now ||
                    (claimDeferredUntil(record, kind.claimKind(), claimDeferrals.value) ?: 0) > now) return@update snapshot
                if (life.suspended) return@update snapshot.copy(downloads = snapshot.downloads +
                    (id to restrictMaintenanceRecord(record, kind)))
                if (!DownloadClaimRules.maintenanceReady(snapshot, record, kind, checked, now, life.suspended,
                        claimDeferredUntil(record, kind.claimKind(), claimDeferrals.value))) return@update snapshot
                val staging = when (kind) {
                    MaintenanceKind.CLEANUP -> stagingPaths(snapshot, record)
                    MaintenanceKind.FINALIZATION -> setOf(intent!!.stagingPath)
                    MaintenanceKind.CHECK_ASSET -> emptySet()
                }
                val finals = when (kind) {
                    MaintenanceKind.CLEANUP -> setOfNotNull(record.asset?.uri, intent?.finalPath)
                    MaintenanceKind.FINALIZATION -> setOfNotNull(intent!!.finalPath, record.asset?.uri)
                    MaintenanceKind.CHECK_ASSET -> setOf(record.asset!!.uri)
                }
                fileLease = files.tryLeasePaths(staging + finals, partial)
                if (fileLease == null) { deferClaim(record, kind.claimKind()); return@update snapshot }
                val next = record.copy(leaseOwner = token, leaseExpiresEpochMs = now + leaseDurationMs,
                    status = if (kind == MaintenanceKind.FINALIZATION) DownloadStatus.VERIFYING else record.status,
                    activeAttemptId = if (kind == MaintenanceKind.FINALIZATION) intent!!.attemptId else record.activeAttemptId,
                    updatedEpochMs = now)
                claimed = MaintenanceClaim(next, intent, token, kind, staging, finals, fileLease!!, life.generation)
                snapshot.copy(downloads = snapshot.downloads + (id to next))
            }.getOrThrow()
            if (claimed != null) clearClaimDeferrals(id, kind.claimKind())
            else rollbackClaim(partial, listOfNotNull(fileLease))
            return claimed
        } catch (error: Throwable) {
            // As with execution claims, cancellation/adapter failure can surface after the real
            // SQLite commit. Do not strand a maintenance lease before withMaintenance begins.
            rollbackClaim(partial, listOfNotNull(fileLease), maintenanceClaim = claimed, error = error)
            if (error !is CancellationException)
                store.state.value.downloads[id]?.let { deferClaim(it, kind.claimKind()) }
            throw error
        }
    }

    private suspend fun finishMaintenanceRollback(claim: MaintenanceClaim) {
        try {
            store.update { snapshot ->
                val record = snapshot.downloads[claim.record.id] ?: return@update snapshot
                if (record.leaseOwner != claim.leaseOwner) snapshot else snapshot.copy(downloads = snapshot.downloads +
                    (record.id to record.copy(leaseOwner = null, leaseExpiresEpochMs = null)))
            }.getOrThrow()
        } catch (error: Exception) { publishFailure(classify(error)) }
    }

    private fun matchesMaintenance(snapshot: AppSnapshot, claim: MaintenanceClaim): Boolean =
        !lifecycle.value.closed && !lifecycle.value.suspended && lifecycle.value.generation == claim.lifecycleGeneration &&
            matchesMaintenanceReferences(snapshot, claim)

    /** Does not include the host epoch: expiry checkpoints must test ownership after latching suspension. */
    private fun matchesMaintenanceReferences(snapshot: AppSnapshot, claim: MaintenanceClaim): Boolean {
        val record = snapshot.downloads[claim.record.id] ?: return false
        if (record.leaseOwner != claim.leaseOwner || !record.liveLease(clock.epochMs())) return false
        return when (claim.kind) {
            MaintenanceKind.CLEANUP -> record.cleanupPending && record.asset == claim.record.asset &&
                record.activeAttemptId == claim.record.activeAttemptId &&
                record.stagingPath == claim.record.stagingPath && snapshot.downloadFinalizations[record.id] == claim.intent &&
                stagingPaths(snapshot, record) == claim.stagingPaths
            MaintenanceKind.FINALIZATION -> !record.cleanupPending && !record.userPaused && record.status == DownloadStatus.VERIFYING &&
                record.activeAttemptId == claim.intent?.attemptId && snapshot.downloadFinalizations[record.id] == claim.intent
            MaintenanceKind.CHECK_ASSET -> !record.cleanupPending && record.status == DownloadStatus.COMPLETE && record.asset == claim.record.asset
        }
    }

    private suspend fun withMaintenance(id: String, kind: MaintenanceKind, action: suspend (MaintenanceClaim) -> Unit) =
        runMaintenance(prepare = {
            claimMaintenance(id, kind)?.let { MaintenanceRun(it.leaseOwner, it.lifecycleGeneration, claim = it) }
        }) { run -> action(run.claim!!) }

    private suspend fun renewMaintenance(claim: MaintenanceClaim) {
        store.update { snapshot ->
            if (!matchesMaintenance(snapshot, claim)) throw CancellationException("Maintenance intent revoked")
            val record = snapshot.downloads.getValue(claim.record.id)
            snapshot.copy(downloads = snapshot.downloads + (record.id to record.copy(leaseExpiresEpochMs = clock.epochMs() + leaseDurationMs)))
        }.getOrThrow()
    }

    private suspend fun runMaintenance(prepare: suspend () -> MaintenanceRun?, action: suspend (MaintenanceRun) -> Unit) {
        try {
            coroutineScope {
                val operation = currentCoroutineContext().job
                var run: MaintenanceRun? = null
                var allowance: DownloadExecutionLease? = null
                var heartbeat: Job? = null
                var admissionFailed = false
                try {
                    val current = commands.withLock {
                        // Claim and registration share intent admission. A local Remove/Background
                        // cannot miss a just-committed claim in the gap before its job is recorded.
                        val prepared = prepare() ?: return@withLock null
                        run = prepared // retain ownership even if dispatcher cancellation wins on return
                        val life = lifecycle.value
                        if (life.closed || life.suspended || life.generation != prepared.lifecycleGeneration)
                            throw CancellationException("Maintenance host epoch ended")
                        activeLock.withLock {
                            maintenance[prepared.token] = TrackedMaintenance(prepared, operation)
                            noteActivity()
                        }
                        prepared
                    } ?: return@coroutineScope
                    current.claim?.let { claim ->
                        heartbeat = launch {
                            while (isActive) {
                                delay(heartbeatMs)
                                try { renewMaintenance(claim) }
                                catch (error: Exception) {
                                    if (error !is CancellationException) publishFailure(classify(error))
                                    operation.cancel(CancellationException("Maintenance lease revoked", error))
                                }
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    // Registered BEFORE acquire: a mobile gate may expire inline before it returns.
                    withContext(NonCancellable) { allowance = executionGate.acquire { maintenanceAllowanceExpired(current) } }
                    currentCoroutineContext().ensureActive()
                    current.claim?.let { renewMaintenance(it) }
                    action(current)
                    current.actionFinished.value = true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    val current = run
                    if (classify(error).code == FailureCode.BACKGROUND_RESTRICTED && current != null)
                        maintenanceAllowanceExpired(current)
                    else { admissionFailed = true; throw error }
                } finally {
                    withContext(NonCancellable) {
                        heartbeat?.cancelAndJoin()
                        val current = run
                        if (current != null) {
                            // Validator/release callback has returned. The independently retained
                            // cleanup owner now fences allowance -> file -> Room consumption;
                            // native lease0 and coroutine completion are never drain receipts.
                            val cleanup = MaintenanceCleanup(current, allowance, admissionFailed)
                            registerCleanup(cleanup)
                            try { attemptCleanup(cleanup) }
                            finally {
                                activeLock.withLock { maintenance.remove(current.token); noteActivity() }
                                wake.trySend(Unit)
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // A locally revoked maintenance child must not kill the scheduler; external shutdown must.
            currentCoroutineContext().ensureActive()
        } finally { withContext(NonCancellable) { publishCleanupFailure() } }
    }

    private suspend fun finishMaintenance(run: MaintenanceRun) {
        val claim = run.claim ?: return
        try {
            store.update { snapshot ->
                val record = snapshot.downloads[claim.record.id] ?: return@update snapshot
                if (record.leaseOwner != claim.leaseOwner) return@update snapshot
                val life = lifecycle.value
                val unchanged = record.asset == claim.record.asset && snapshot.downloadFinalizations[record.id] == claim.intent
                val restricted = life.suspended && !life.closed && unchanged &&
                    (claim.kind == MaintenanceKind.CLEANUP || record.status in setOf(DownloadStatus.COMPLETE, DownloadStatus.VERIFYING))
                var updated = if (restricted) restrictMaintenanceRecord(record, claim.kind) else record
                if (!life.suspended && !life.closed && unchanged && updated.failure?.code == FailureCode.BACKGROUND_RESTRICTED) {
                    updated = updated.copy(status = if (updated.status == DownloadStatus.NEEDS_FOREGROUND &&
                        claim.kind == MaintenanceKind.FINALIZATION && !updated.userPaused && !updated.cleanupPending)
                        DownloadStatus.VERIFYING else updated.status, failure = null)
                }
                snapshot.copy(downloads = snapshot.downloads +
                    (record.id to updated.copy(leaseOwner = null, leaseExpiresEpochMs = null)))
            }.getOrThrow()
        } catch (error: Exception) { publishFailure(classify(error)) }
    }

    private suspend fun recoverFinalization(id: String) {
        withMaintenance(id, MaintenanceKind.FINALIZATION) { claim ->
            val intent = claim.intent!!
            var verifiedBinding: PlaybackBinding? = null
            try {
                store.update { snapshot ->
                    if (!matchesMaintenance(snapshot, claim)) throw CancellationException("Finalization identity revoked")
                    requireMaterializedBinding(snapshot, snapshot.downloads.getValue(id), intent.mediaId,
                        intent.executionBindingId, intent.representationId)
                    verifiedBinding = snapshot.playbackBindings.getValue(intent.executionBindingId!!)
                    snapshot
                }.getOrThrow()
                val destination = files.ownedPath(intent.finalPath)
                val published = storageOperation { files.fileSystem.exists(destination) }
                val chosen = if (published) destination else files.ownedPath(intent.stagingPath)
                val asset = OfflineAsset("recover-$id", intent.mediaId, chosen.toString(), intent.sizeBytes,
                    mimeType = intent.mimeType, codecs = intent.codecs, sha256 = intent.sha256,
                    executionBindingId = intent.executionBindingId, measuredDurationMs = intent.mediaDurationMs)
                files.verifiedFile(asset)
                validate(asset, claim.validationReaders)
                files.verifiedFile(asset)
                currentCoroutineContext().ensureActive()
                commitFinalization(intent, claim.leaseOwner, claim.lifecycleGeneration, verifiedBinding!!, alreadyPublished = published)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: LocalMediaValidationReaderUnacknowledged) { throw error }
            catch (error: Exception) {
                store.update { snapshot ->
                    if (!matchesMaintenance(snapshot, claim)) return@update snapshot
                    val record = snapshot.downloads.getValue(id)
                    snapshot.copy(downloads = snapshot.downloads + (id to record.copy(status = DownloadStatus.FAILED,
                        failure = classify(error), activeAttemptId = null, nextRetryEpochMs = clock.epochMs() + 5_000,
                        updatedEpochMs = clock.epochMs())))
                }.getOrThrow()
            }
        }
    }

    private suspend fun checkCompletedAsset(id: String) {
        withMaintenance(id, MaintenanceKind.CHECK_ASSET) { claim ->
            val asset = claim.record.asset!!
            var verifiedBinding: PlaybackBinding? = null
            try {
                store.update { snapshot ->
                    if (!matchesMaintenance(snapshot, claim)) throw CancellationException("Completed asset identity revoked")
                    requireMaterializedBinding(snapshot, snapshot.downloads.getValue(id), asset.mediaId,
                        asset.executionBindingId, asset.representationId)
                    verifiedBinding = snapshot.playbackBindings.getValue(asset.executionBindingId!!)
                    snapshot
                }.getOrThrow()
                files.verifiedFile(asset)
                validate(asset, claim.validationReaders)
                files.verifiedFile(asset)
                var stillCurrent = false
                store.update { snapshot ->
                    stillCurrent = matchesMaintenance(snapshot, claim)
                    if (!stillCurrent) snapshot else {
                        requireMaterializedBinding(snapshot, snapshot.downloads.getValue(id), asset.mediaId,
                            asset.executionBindingId, asset.representationId)
                        if (snapshot.playbackBindings[asset.executionBindingId] != verifiedBinding)
                            throw VeyraException(AppFailure(FailureCode.CORRUPT_MEDIA, "offline_execution_binding_changed"))
                        snapshot.copy(downloads = snapshot.downloads + (id to snapshot.downloads.getValue(id).copy(failure = null)))
                    }
                }.getOrThrow()
                if (stillCurrent) checkedAssets.update { it + (id to CheckedAsset(asset, verifiedBinding!!)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: LocalMediaValidationReaderUnacknowledged) { throw error }
            catch (error: Exception) {
                store.update { snapshot ->
                    if (!matchesMaintenance(snapshot, claim)) return@update snapshot
                    val record = snapshot.downloads.getValue(id)
                    snapshot.copy(downloads = snapshot.downloads + (id to record.copy(status = DownloadStatus.FAILED,
                        failure = classify(error), updatedEpochMs = clock.epochMs())))
                }.getOrThrow()
            }
        }
    }

    private fun stagingPaths(snapshot: AppSnapshot, record: DownloadRecord): Set<String> =
        setOfNotNull(record.stagingPath, snapshot.downloadFinalizations[record.id]?.stagingPath) +
            snapshot.downloadAttempts.values.filter { it.downloadId == record.id }.mapNotNull { it.stagingPath }

    private suspend fun cleanup(id: String) {
        withMaintenance(id, MaintenanceKind.CLEANUP) { claim ->
            try {
                // The durable intent is already visible to the resolver. Await actual native release
                // outside SQLite; the next transaction rechecks the exact intent and every file reference.
                claim.record.asset?.let { beforePhysicalDelete(it) }
                currentCoroutineContext().ensureActive()
                store.update { snapshot ->
                    if (!matchesMaintenance(snapshot, claim)) return@update snapshot
                    val record = snapshot.downloads.getValue(id)
                    claim.stagingPaths.forEach(files::resetStaging)
                    claim.finalPaths.forEach(files::deleteFinal)
                    if (record.removeWhenClean) snapshot.copy(downloads = snapshot.downloads - id,
                        downloadAttempts = snapshot.downloadAttempts.filterValues { it.downloadId != id },
                        downloadFinalizations = snapshot.downloadFinalizations - id)
                    else snapshot.copy(downloads = snapshot.downloads + (id to record.copy(
                        status = if (record.userPaused) DownloadStatus.CANCELLED else queuedStatus(snapshot),
                        cleanupPending = false, removeWhenClean = false, asset = null, stagingPath = null,
                        bytesDownloaded = 0, totalBytes = null, representationId = null,
                        executionBindingId = if (record.userPaused) null else record.executionBindingId,
                        failure = if (record.userPaused) null else queueFailure(snapshot), nextRetryEpochMs = null,
                        activeAttemptId = null, leaseOwner = null, leaseExpiresEpochMs = null, updatedEpochMs = clock.epochMs())),
                        downloadAttempts = snapshot.downloadAttempts.mapValues { (_, attempt) ->
                            if (attempt.downloadId == id) attempt.copy(stagingPath = null) else attempt
                        }, downloadFinalizations = snapshot.downloadFinalizations - id)
                }.getOrThrow()
                checkedAssets.update { it - id }
                clearClaimDeferrals(id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val failure = classify(error)
                publishFailure(failure)
                store.update { snapshot ->
                    if (!matchesMaintenance(snapshot, claim)) return@update snapshot
                    val record = snapshot.downloads.getValue(id)
                    snapshot.copy(downloads = snapshot.downloads + (id to record.copy(failure = failure,
                        nextRetryEpochMs = schedulingDeadline(clock.epochMs(), LOCAL_RECHECK_MS), updatedEpochMs = clock.epochMs())))
                }.getOrThrow()
            }
        }
    }

    private suspend fun sweepRetiredStaging() {
        for (path in files.generatedStagingParts()) {
            currentCoroutineContext().ensureActive()
            // generatedStagingParts is a metadata-only shape hint and may name a canonical
            // transfer.part that never existed. Never acquire an execution allowance for that.
            if (files.fileSystem.metadataOrNull(path.toPath()) == null) continue
            val life = lifecycle.value
            if (life.closed || life.suspended) return
            // Metadata-only preflight and a nonblocking native claim avoid starting an OS worker
            // for empty/live generations. All recursive deletion runs under the execution gate.
            val physical = physicalRuntime
            if (if (physical == null) stagingReferenced(store.state.value, path)
                else physical.stagingReferenced(store.acquisitionViews.value.acquisition, path)) continue
            runMaintenance(prepare = prepare@ {
                if (lifecycle.value.closed || lifecycle.value.suspended) return@prepare null
                val lease = files.tryLeasePaths(listOf(path), DownloadLeaseCleanupScope(cleanupRegistry)) ?: return@prepare null
                MaintenanceRun(newEntityId("download-sweep"), lifecycle.value.generation, orphanLease = lease)
            }) { run ->
                if (physical != null) {
                    // The exact OS generation lock is already held. Re-read under that lock;
                    // new physical path claims also require it, so a stale hint cannot authorize
                    // deletion. Retained schema3 attempt/receipt HISTORY is never rewritten.
                    val view = store.acquisitionView()
                    currentCoroutineContext().ensureActive()
                    if (!lifecycle.value.closed && !lifecycle.value.suspended && lifecycle.value.generation == run.lifecycleGeneration &&
                        !physical.stagingReferenced(view.acquisition, path)) files.resetStaging(path)
                } else store.update { snapshot ->
                    if (lifecycle.value.closed || lifecycle.value.suspended || lifecycle.value.generation != run.lifecycleGeneration ||
                        stagingReferenced(snapshot, path)) return@update snapshot
                    val attempts = snapshot.downloadAttempts.values.filter { it.stagingPath == path }
                    files.resetStaging(path)
                    val cleared = attempts.mapTo(hashSetOf()) { it.id }
                    snapshot.copy(downloadAttempts = snapshot.downloadAttempts.mapValues { (id, attempt) ->
                        if (id in cleared) attempt.copy(stagingPath = null) else attempt
                    })
                }.getOrThrow()
            }
        }
    }

    private fun stagingReferenced(snapshot: AppSnapshot, path: String): Boolean =
        snapshot.downloads.values.any { it.stagingPath == path } ||
            snapshot.downloadFinalizations.values.any { it.stagingPath == path } ||
            snapshot.downloadAttempts.values.any { attempt -> attempt.stagingPath == path &&
                snapshot.downloads[attempt.downloadId]?.let { it.cleanupPending || it.liveLease(clock.epochMs()) } == true }

    private fun projectStates(snapshot: AppSnapshot, checked: Map<String, CheckedAsset>, life: Lifecycle) =
        snapshot.downloads.mapValues { (_, record) ->
            if (record.status != DownloadStatus.COMPLETE || record.cleanupPending || assetChecked(snapshot, record, checked)) record
            else if (life.suspended || record.failure?.code == FailureCode.BACKGROUND_RESTRICTED)
                record.copy(status = DownloadStatus.NEEDS_FOREGROUND, failure = AppFailure(FailureCode.BACKGROUND_RESTRICTED))
            else record.copy(status = DownloadStatus.VERIFYING)
        }

    private fun assetChecked(snapshot: AppSnapshot, record: DownloadRecord, checked: Map<String, CheckedAsset>): Boolean {
        val asset = record.asset ?: return false
        val receipt = checked[record.id] ?: return false
        return receipt.asset == asset && snapshot.playbackBindings[asset.executionBindingId] == receipt.binding && materializedBindingMatches(snapshot, record,
            asset.mediaId, asset.executionBindingId, asset.representationId)
    }

    private fun materializedBindingMatches(snapshot: AppSnapshot, record: DownloadRecord, mediaId: String,
        bindingId: String?, representationId: String?): Boolean {
        val binding = bindingId?.let(snapshot.playbackBindings::get) ?: return false
        return !representationId.isNullOrBlank() && record.mediaId == mediaId && record.executionBindingId == bindingId && record.representationId == representationId &&
            binding.mediaId == mediaId && binding.selectionOrdinal == 0 && binding.firstResultKind == FirstResultKind.VIDEO &&
            binding.queryTitle.isNotBlank() && binding.selectedVideoId == binding.firstResultId && VIDEO_ID.matches(binding.firstResultId) &&
            (binding.firstResultUrl == null || binding.firstResultUrl == "https://www.youtube.com/watch?v=${binding.firstResultId}")
    }

    private fun requireMaterializedBinding(snapshot: AppSnapshot, record: DownloadRecord, mediaId: String,
        bindingId: String?, representationId: String?) {
        if (!materializedBindingMatches(snapshot, record, mediaId, bindingId, representationId))
            throw VeyraException(AppFailure(FailureCode.CORRUPT_MEDIA, "offline_execution_binding_mismatch"))
    }

    private fun signalWork() {
        wake.trySend(Unit)
        maintenanceWake.trySend(Unit)
        notifySchedulingChange()
    }

    private fun notifySchedulingChange() {
        // Hints may wrap; authoritative activity/ticket epochs below never do.
        mutableSchedulingChanges.update { if (it == Long.MAX_VALUE) 0 else it + 1 }
    }

    private fun noteActivity() {
        activityEpoch.update { check(it < Long.MAX_VALUE) { "Download activity epoch exhausted" }; it + 1 }
        notifySchedulingChange()
    }

    private fun deferClaim(record: DownloadRecord, kind: DownloadClaimKind) {
        val key = DownloadClaimKey(record.id, kind)
        val relevant = record.schedulingRecord()
        claimDeferrals.update { previous ->
            val refusals = previous[key]?.takeIf { it.record == relevant }?.refusals?.coerceAtMost(4)?.plus(1) ?: 1
            val delay = (LOCAL_RECHECK_MS shl (refusals - 1)).coerceAtMost(60_000)
            previous + (key to DownloadClaimDeferral(relevant, schedulingDeadline(clock.epochMs(), delay), refusals))
        }
        noteActivity()
    }

    private fun clearClaimDeferrals(id: String, kind: DownloadClaimKind? = null) {
        claimDeferrals.update { previous -> previous.filterKeys { it.id != id || kind != null && it.kind != kind } }
    }

    private fun schedulingDeadline(now: Long, delayMs: Long): Long =
        now.coerceIn(0, Long.MAX_VALUE - delayMs) + delayMs

    private fun queuedStatus(snapshot: AppSnapshot): DownloadStatus = when {
        snapshot.settings.offlineOnly -> DownloadStatus.WAITING_NETWORK
        lifecycle.value.suspended -> DownloadStatus.NEEDS_FOREGROUND
        else -> DownloadStatus.QUEUED
    }
    private fun queueFailure(snapshot: AppSnapshot): AppFailure? =
        if (!snapshot.settings.offlineOnly && lifecycle.value.suspended) AppFailure(FailureCode.BACKGROUND_RESTRICTED) else null
    private fun DownloadRecord.liveLease(now: Long): Boolean = liveDownloadLease(now)
    private fun requireOpen() { check(!lifecycle.value.closed) { "Download coordinator closed" } }
    private fun restartRequiredFailure() =
        AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_failed_restart_required")
    private fun publishFailure(failure: AppFailure?) {
        mutableFailure.update { lifecycle.value.drainFailure ?: cleanupFailure.value ?: failure }
    }
    private fun classify(error: Exception): AppFailure = when (error) {
        is LocalMediaValidationReaderUnacknowledged -> AppFailure(FailureCode.BACKGROUND_RESTRICTED, "local_validation_reader_unacknowledged")
        is VeyraException -> error.failure
        is IllegalArgumentException, is IllegalStateException -> AppFailure(FailureCode.INVALID_STATE)
        else -> AppFailure(FailureCode.UNEXPECTED)
    }

    companion object {
        private const val MAX_ASSET_CHECKS_PER_PASS = 2
        private const val LOCAL_RECHECK_MS = 5_000L
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        private const val MAX_RETRIES = 5
        private val BUSY = DownloadClaimRules.busy
        private val QUEUED = DownloadClaimRules.queued
        private val RUNNABLE_OR_BUSY = BUSY + QUEUED
        private val RETRYABLE = setOf(FailureCode.NETWORK_UNAVAILABLE, FailureCode.RATE_LIMITED)
        fun retryDelayMs(attempt: Int, retryAfterMs: Long? = null): Long =
            maxOf((2_000L shl attempt.coerceIn(0, 6)).coerceAtMost(120_000), retryAfterMs?.coerceIn(0, 86_400_000) ?: 0)
    }
}
