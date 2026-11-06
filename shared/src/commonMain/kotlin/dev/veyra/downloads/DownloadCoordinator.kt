package dev.veyra.downloads

import dev.veyra.data.LibraryStore
import dev.veyra.data.getOrThrow
import dev.veyra.model.*
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.AssetValidator
import dev.veyra.playback.Clock
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.PlaybackResource
import dev.veyra.playback.SystemClock
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.execution.AppSourceResolver
import dev.veyra.source.execution.KmpMediaGateway
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)
    // Public item actions enter this mutex BEFORE their first possible suspension. The app's
    // undispatched intent admission relies on that order; slow join/native/file work stays outside.
    private val commands = Mutex()
    private val activeLock = Mutex()
    private val maintenanceLock = Mutex()
    private val active = mutableMapOf<String, Job>()
    private val maintenance = mutableMapOf<String, TrackedMaintenance>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val maintenanceWake = Channel<Unit>(Channel.CONFLATED)
    private val lifecycle = MutableStateFlow(Lifecycle())
    private val owner = newEntityId("download-owner")
    private data class CheckedAsset(val asset: OfflineAsset, val binding: PlaybackBinding)
    private val checkedAssets = MutableStateFlow<Map<String, CheckedAsset>>(emptyMap())
    private var scheduler: Job? = null
    private var reconciler: Job? = null
    private var startup: CompletableDeferred<Unit>? = null
    private var policyObserver: Job? = null
    private var lastSweepMs: Long? = null
    private data class Lifecycle(val started: Boolean = false, val suspended: Boolean = false, val closed: Boolean = false,
        val generation: Long = 0)
    private data class ExecutionClaim(val record: DownloadRecord, val item: MediaItem, val attempt: DownloadAttempt,
        val generationLease: DownloadFileLease, val slot: DownloadTransferSlot, val lifecycleGeneration: Long,
        val allowanceCallbacksOpen: MutableStateFlow<Boolean> = MutableStateFlow(true))
    private enum class MaintenanceKind { CLEANUP, FINALIZATION, CHECK_ASSET }
    private data class MaintenanceClaim(val record: DownloadRecord, val intent: DownloadFinalization?,
        val leaseOwner: String, val kind: MaintenanceKind, val stagingPaths: Set<String>, val finalPaths: Set<String>,
        val fileLease: DownloadFileLease, val lifecycleGeneration: Long)
    private data class MaintenanceRun(val token: String, val lifecycleGeneration: Long,
        val claim: MaintenanceClaim? = null, val orphanLease: DownloadFileLease? = null,
        val allowanceCallbacksOpen: MutableStateFlow<Boolean> = MutableStateFlow(true),
        val actionFinished: MutableStateFlow<Boolean> = MutableStateFlow(false))
    private data class TrackedMaintenance(val run: MaintenanceRun, val job: Job)

    init { require(pollingMs > 0 && leaseDurationMs in 1_000..600_000 && heartbeatMs in 1 until leaseDurationMs) }

    /** One coherent snapshot: never merge a newer Room revision with an older download map. */
    val presentation: StateFlow<AppSnapshot> = combine(store.state, checkedAssets, lifecycle) { snapshot, checked, life ->
        snapshot.copy(downloads = projectStates(snapshot, checked, life))
    }.stateIn(scope, SharingStarted.Eagerly, store.state.value.let {
        it.copy(downloads = projectStates(it, emptyMap(), lifecycle.value))
    })
    /** Session-only projection: Room's completion receipt is not a new process's integrity check. */
    val states: StateFlow<Map<String, DownloadRecord>> = presentation.map { it.downloads }
        .stateIn(scope, SharingStarted.Eagerly, presentation.value.downloads)
    val hasPendingWork: StateFlow<Boolean> = combine(store.state, lifecycle, checkedAssets) { snapshot, life, checked ->
        life.started && !life.closed && !life.suspended && snapshot.downloads.values.any {
            it.cleanupPending || !it.userPaused && it.id in snapshot.downloadFinalizations ||
                it.status == DownloadStatus.COMPLETE && !assetChecked(snapshot, it, checked) ||
                !snapshot.settings.offlineOnly && !it.userPaused && it.status in RUNNABLE_OR_BUSY
        }
    }.stateIn(scope, SharingStarted.Eagerly, false)
    private val mutableFailure = MutableStateFlow<AppFailure?>(null)
    val lastFailure: StateFlow<AppFailure?> = mutableFailure.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    suspend fun start() {
        val ready = commands.withLock {
            if (lifecycle.value.started) return@withLock startup!!
            requireOpen()
            store.initialize().getOrThrow()
            withContext(Dispatchers.IO) { files.initialize() }
            lifecycle.value = lifecycle.value.copy(started = true)
            val initialized = CompletableDeferred<Unit>().also { startup = it }
            scheduler = scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    while (isActive) {
                        try {
                            // Cached StateFlows are hints, never the authority for arbitration.
                            store.flush().getOrThrow()
                            schedule()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { mutableFailure.value = classify(error) }
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
                    catch (error: Exception) { mutableFailure.value = classify(error) }
                    withTimeoutOrNull(pollingMs) { maintenanceWake.receive() }
                }
            }
            policyObserver = scope.launch {
                store.state.map { it.settings.offlineOnly }.distinctUntilChanged().collect { offline ->
                    try { if (offline) pauseForPolicy() else signalWork() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { mutableFailure.value = classify(error) }
                }
            }
            initialized.complete(Unit)
            initialized
        }
        // Startup establishes the workers, not an unbounded scan of the local media library.
        ready.await()
    }

    /** An explicit playback receipt pins Play -> Download; an existing different selection is not silently replaced. */
    suspend fun enqueue(item: MediaItem, reuseBindingId: String? = null): String = commands.withLock {
        requireOpen()
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
        commands.withLock {
            requireOpen()
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
        val jobs = commands.withLock {
            requireOpen()
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

    suspend fun pauseForBackground() {
        val jobs = restrictOwnedWork(DownloadStatus.NEEDS_FOREGROUND, suspendHost = true)
        cancelAndJoinJobs(jobs)
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
                try {
                    if (!suspendOwnedRecords(DownloadStatus.NEEDS_FOREGROUND, onlyIfClaim = claim, maintaining = maintaining)) {
                        // The callback was queued for a now-completed/superseded claim. Do not
                        // suspend a healthy current host because a retired allowance reported late.
                        lifecycle.value = life
                        return@withLock listOf(expiredJob)
                    }
                } catch (error: Exception) { mutableFailure.value = classify(error) }
                running.values.toList() + maintaining.map { it.job }
            }
            jobs.forEach { it.cancel(CancellationException("Background execution allowance ended")) }
            // Never join here: the callback can be running from acquire() or a noncancellable close().
        }
    }

    private suspend fun pauseForPolicy() = cancelAndJoinJobs(restrictOwnedWork(DownloadStatus.WAITING_NETWORK, suspendHost = false))

    private suspend fun restrictOwnedWork(target: DownloadStatus, suspendHost: Boolean): List<Job> = commands.withLock {
        if (lifecycle.value.closed) return@withLock emptyList()
        if (suspendHost) lifecycle.value = lifecycle.value.copy(suspended = true)
        val maintaining = activeLock.withLock { if (suspendHost) maintenance.values.toList() else emptyList() }
        val jobs = activeLock.withLock { active.values.toList() } + maintaining.map { it.job }
        try { suspendOwnedRecords(target, maintaining = maintaining) }
        catch (error: Exception) { jobs.forEach { it.cancel() }; throw error }
        jobs
    }

    private suspend fun suspendOwnedRecords(target: DownloadStatus, onlyIfClaim: ExecutionClaim? = null,
        onlyIfMaintenance: MaintenanceRun? = null, maintaining: List<TrackedMaintenance> = emptyList()): Boolean {
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
            try {
                if (!suspendOwnedRecords(DownloadStatus.NEEDS_FOREGROUND, onlyIfMaintenance = run, maintaining = maintaining)) {
                    lifecycle.value = life
                    return@withLock listOf(expired.job)
                }
                mutableFailure.value = AppFailure(FailureCode.BACKGROUND_RESTRICTED)
            } catch (error: Exception) { mutableFailure.value = classify(error) }
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
        commands.withLock {
            requireOpen()
            val life = lifecycle.value
            if (life.suspended) require(life.generation < Long.MAX_VALUE)
            lifecycle.value = life.copy(suspended = false, generation = life.generation + if (life.suspended) 1 else 0)
            store.update { snapshot -> snapshot.copy(downloads = snapshot.downloads.mapValues { (_, record) ->
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

    suspend fun close() {
        var checkpointError: Exception? = null
        var wasStarted = false
        val jobs = commands.withLock {
            if (lifecycle.value.closed) return
            wasStarted = lifecycle.value.started
            lifecycle.value = lifecycle.value.copy(closed = true, suspended = true)
            val running = activeLock.withLock { active.values.toList() + maintenance.values.map { it.job } }
            try { if (wasStarted) suspendOwnedRecords(DownloadStatus.QUEUED) }
            catch (error: Exception) { checkpointError = error; mutableFailure.value = classify(error) }
            running
        }
        try {
            cancelAndJoinJobs(listOfNotNull(scheduler, reconciler, policyObserver) + jobs)
            if (wasStarted) store.flush().getOrThrow()
        } finally { scope.cancel() }
        checkpointError?.let { throw it }
    }

    private suspend fun cancelAndJoinJobs(jobs: List<Job>) {
        val caller = currentCoroutineContext()[Job]
        jobs.forEach { it.cancel() }
        jobs.filterNot { it === caller }.joinAll()
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private suspend fun schedule() {
        val life = lifecycle.value
        if (!life.started || life.closed || life.suspended || store.state.value.settings.offlineOnly) return
        activeLock.withLock {
            val candidates = store.state.value.downloads.values.filter { it.id !in active }
                .sortedWith(compareByDescending<DownloadRecord> { it.priority }.thenBy { it.createdEpochMs }.thenBy { it.id })
            for (candidate in candidates) {
                if (active.size >= store.state.value.settings.downloadParallelism.coerceIn(1, 4)) break
                val claim = claimExecution(candidate.id) ?: continue
                // ATOMIC enters the try/finally even if the parent is cancelled just after the durable claim.
                val task = scope.launch(start = CoroutineStart.ATOMIC) {
                    try { execute(claim) }
                    finally { withContext(NonCancellable) { activeLock.withLock { active.remove(claim.record.id) }; signalWork() } }
                }
                active[claim.record.id] = task
            }
        }
    }

    private suspend fun claimExecution(id: String): ExecutionClaim? {
        var claim: ExecutionClaim? = null
        var slot: DownloadTransferSlot? = null
        var generationLease: DownloadFileLease? = null
        try {
            store.update { snapshot ->
                val record = snapshot.downloads[id] ?: return@update snapshot
                val now = clock.epochMs()
                if (lifecycle.value.closed || lifecycle.value.suspended || snapshot.settings.offlineOnly || record.cleanupPending || record.userPaused ||
                    record.status !in QUEUED || record.liveLease(now) || (record.nextRetryEpochMs ?: 0) > now ||
                    id in snapshot.downloadFinalizations || record.asset != null) return@update snapshot
                val limit = snapshot.settings.downloadParallelism.coerceIn(1, 4)
                if (snapshot.downloads.values.count { it.liveLease(now) && it.status in BUSY && !it.cleanupPending } >= limit) return@update snapshot
                val item = snapshot.media[record.mediaId] ?: return@update snapshot
                slot = files.tryTransferSlot(limit) ?: return@update snapshot
                val attemptId = newEntityId("download-attempt")
                val bound = record.executionBindingId?.let(snapshot.playbackBindings::get)?.let {
                    it.mediaId == record.mediaId && it.selectionOrdinal == 0 && it.firstResultKind == FirstResultKind.VIDEO &&
                        it.selectedVideoId == it.firstResultId && it.firstResultId.matches(Regex("[A-Za-z0-9_-]{11}"))
                } == true
                // Bytes may only be resumed with their immutable selection AND representation.
                // An unbound pre-attempt directory must not be paired with a fresh title search.
                var path = record.stagingPath.takeIf { bound && !record.representationId.isNullOrBlank() }
                if (path != null) generationLease = files.tryLeasePaths(listOf(path))
                val retained = generationLease != null
                if (!retained) {
                    path = files.part(record.mediaId, attemptId).toString()
                    generationLease = files.tryLeasePaths(listOf(path))
                    if (generationLease == null) return@update snapshot
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
            if (claim == null) { generationLease?.close(); slot?.close() }
            return claim
        } catch (error: Exception) {
            runCatching { generationLease?.close() }; runCatching { slot?.close() }
            // A cancellation on the dispatcher return may happen AFTER the SQLite claim committed.
            // Release only that exact attempt; a rolled-back claim makes this an inert CAS.
            claim?.let { withContext(NonCancellable) { finishExecution(it,
                if (error is CancellationException) null else classify(error), error is CancellationException) } }
            throw error
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
        try {
            coroutineScope {
                val executionJob = currentCoroutineContext().job
                val heartbeat = launch {
                    while (isActive) {
                        delay(heartbeatMs)
                        try { updateOwned(claim) { it } }
                        catch (error: Exception) {
                            if (error !is CancellationException) { failure = classify(error); mutableFailure.value = failure }
                            executionJob.cancel(CancellationException("Download lease revoked", error))
                        }
                    }
                }
                try {
                    currentCoroutineContext().ensureActive()
                    updateOwned(claim) { it }
                    allowance = executionGate.acquire { allowanceExpired(claim) }
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
                    val duration = validate(staged)
                    files.verifiedFile(staged)
                    val intent = DownloadFinalization(id, claim.item.id, part.toString(),
                        files.finalPath(claim.item.id, claim.record.validatorGeneration, result.mimeType, attemptId).toString(),
                        hash, result.bytesWritten, attemptId, duration, result.mimeType, result.codecs, binding.id,
                        result.representationId, claim.record.validatorGeneration)
                    intendedFinalization = intent
                    // The independently-running integrity worker must not inspect a just-committed
                    // file before this validated writer has published its session check receipt.
                    publicationLease = files.tryLeasePaths(listOf(intent.finalPath))
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
                    commitFinalization(intent, owner, claim.lifecycleGeneration, alreadyPublished = false)
                } finally { withContext(NonCancellable) { heartbeat.cancelAndJoin() } }
            }
        } catch (error: CancellationException) { cancelled = failure == null; throw error }
        catch (error: Exception) {
            failure = classify(error)
            if (failure?.code == FailureCode.BACKGROUND_RESTRICTED) allowanceExpired(claim)
        } finally {
            withContext(NonCancellable) {
                try { planResource?.close() }
                catch (error: Exception) { mutableFailure.value = classify(error) }
                try { allowance?.close() }
                catch (error: Exception) { mutableFailure.value = classify(error) }
                finally { claim.allowanceCallbacksOpen.value = false }
                // Only after all gateway/native/allowance callbacks returned can another host reuse or remove these bytes.
                runCatching { publicationLease?.close() }.onFailure { mutableFailure.value = AppFailure(FailureCode.STORAGE_IO, "publication_lock_release") }
                runCatching { claim.generationLease.close() }.onFailure { mutableFailure.value = AppFailure(FailureCode.STORAGE_IO, "generation_lock_release") }
                runCatching { claim.slot.close() }.onFailure { mutableFailure.value = AppFailure(FailureCode.STORAGE_IO, "transfer_slot_release") }
                finishExecution(claim, failure, cancelled, intendedFinalization)
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
        } catch (error: Exception) { mutableFailure.value = classify(error) }
    }

    private suspend fun validate(asset: OfflineAsset): Long? = when (val result = validator.validate(asset)) {
        is AssetValidation.Valid -> result.durationMs
        is AssetValidation.Invalid -> throw VeyraException(result.failure)
    }

    private suspend fun commitFinalization(intent: DownloadFinalization, claimant: String, lifecycleGeneration: Long,
        alreadyPublished: Boolean): Boolean {
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
        val ids = store.flush().getOrThrow().downloads.keys.toList()
        var checked = 0
        for (id in ids) {
            currentCoroutineContext().ensureActive()
            val record = store.state.value.downloads[id] ?: continue
            try {
                when {
                    record.cleanupPending -> cleanup(id)
                    id in store.state.value.downloadFinalizations -> recoverFinalization(id)
                    record.status == DownloadStatus.COMPLETE && !assetChecked(store.state.value, record, checkedAssets.value) &&
                        checked < MAX_ASSET_CHECKS_PER_PASS -> { checked++; checkCompletedAsset(id) }
                    record.leaseOwner != null && !record.liveLease(clock.epochMs()) || record.status in BUSY && record.leaseOwner == null -> releaseExpired(id)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableFailure.value = classify(error) }
        }
        val stamp = clock.monotonicMs()
        if (!lifecycle.value.closed && !lifecycle.value.suspended && (lastSweepMs == null || stamp - lastSweepMs!! >= 5_000)) {
            sweepRetiredStaging()
            lastSweepMs = stamp
        }
    }

    private suspend fun releaseExpired(id: String) {
        store.update { snapshot ->
            val record = snapshot.downloads[id] ?: return@update snapshot
            if (record.liveLease(clock.epochMs()) || record.cleanupPending || id in snapshot.downloadFinalizations) return@update snapshot
            val target = if (record.status !in BUSY) record.status else if (record.userPaused) DownloadStatus.PAUSED else queuedStatus(snapshot)
            snapshot.copy(downloads = snapshot.downloads + (id to record.copy(status = target,
                leaseOwner = null, leaseExpiresEpochMs = null, activeAttemptId = null, updatedEpochMs = clock.epochMs())))
            // Do not fabricate an old writer's acknowledgement. Its file lock may still be alive.
        }.getOrThrow()
    }

    private suspend fun claimMaintenance(id: String, kind: MaintenanceKind): MaintenanceClaim? {
        var claimed: MaintenanceClaim? = null
        var fileLease: DownloadFileLease? = null
        val token = newEntityId("download-maintenance")
        try {
            store.update { snapshot ->
                val record = snapshot.downloads[id] ?: return@update snapshot
                val now = clock.epochMs()
                val life = lifecycle.value
                if (life.closed || record.liveLease(now)) return@update snapshot
                val intent = snapshot.downloadFinalizations[id]
                when (kind) {
                    MaintenanceKind.CLEANUP -> if (!record.cleanupPending) return@update snapshot
                    MaintenanceKind.FINALIZATION -> if (record.cleanupPending || record.userPaused || intent == null ||
                        (record.nextRetryEpochMs ?: 0) > now || record.activeAttemptId != null && record.activeAttemptId != intent.attemptId) return@update snapshot
                    MaintenanceKind.CHECK_ASSET -> if (record.cleanupPending || record.status != DownloadStatus.COMPLETE || record.asset == null ||
                        assetChecked(snapshot, record, checkedAssets.value)) return@update snapshot
                }
                if (life.suspended) return@update snapshot.copy(downloads = snapshot.downloads +
                    (id to restrictMaintenanceRecord(record, kind)))
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
                fileLease = files.tryLeasePaths(staging + finals) ?: return@update snapshot
                val next = record.copy(leaseOwner = token, leaseExpiresEpochMs = now + leaseDurationMs,
                    status = if (kind == MaintenanceKind.FINALIZATION) DownloadStatus.VERIFYING else record.status,
                    activeAttemptId = if (kind == MaintenanceKind.FINALIZATION) intent!!.attemptId else record.activeAttemptId,
                    updatedEpochMs = now)
                claimed = MaintenanceClaim(next, intent, token, kind, staging, finals, fileLease!!, life.generation)
                snapshot.copy(downloads = snapshot.downloads + (id to next))
            }.getOrThrow()
            return claimed
        } catch (error: Exception) {
            runCatching { fileLease?.close() }
            // As with execution claims, cancellation/adapter failure can surface after the real
            // SQLite commit. Do not strand a maintenance lease before withMaintenance begins.
            claimed?.let { claim -> withContext(NonCancellable) {
                try {
                    store.update { snapshot ->
                        val record = snapshot.downloads[id] ?: return@update snapshot
                        if (record.leaseOwner != claim.leaseOwner) snapshot else snapshot.copy(downloads = snapshot.downloads +
                            (id to record.copy(leaseOwner = null, leaseExpiresEpochMs = null)))
                    }.getOrThrow()
                } catch (releaseError: Exception) { mutableFailure.value = classify(releaseError) }
            } }
            throw error
        }
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
                try {
                    val current = commands.withLock {
                        // Claim and registration share intent admission. A local Remove/Background
                        // cannot miss a just-committed claim in the gap before its job is recorded.
                        val prepared = prepare() ?: return@withLock null
                        run = prepared // retain ownership even if dispatcher cancellation wins on return
                        val life = lifecycle.value
                        if (life.closed || life.suspended || life.generation != prepared.lifecycleGeneration)
                            throw CancellationException("Maintenance host epoch ended")
                        activeLock.withLock { maintenance[prepared.token] = TrackedMaintenance(prepared, operation) }
                        prepared
                    } ?: return@coroutineScope
                    current.claim?.let { claim ->
                        heartbeat = launch {
                            while (isActive) {
                                delay(heartbeatMs)
                                try { renewMaintenance(claim) }
                                catch (error: Exception) {
                                    if (error !is CancellationException) mutableFailure.value = classify(error)
                                    operation.cancel(CancellationException("Maintenance lease revoked", error))
                                }
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    // Registered BEFORE acquire: a mobile gate may expire inline before it returns.
                    allowance = executionGate.acquire { maintenanceAllowanceExpired(current) }
                    currentCoroutineContext().ensureActive()
                    current.claim?.let { renewMaintenance(it) }
                    action(current)
                    current.actionFinished.value = true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    val current = run
                    if (classify(error).code == FailureCode.BACKGROUND_RESTRICTED && current != null)
                        maintenanceAllowanceExpired(current)
                    else throw error
                } finally {
                    withContext(NonCancellable) {
                        heartbeat?.cancelAndJoin()
                        val current = run
                        if (current != null) {
                            // Keep the native OS lease until ALL file/native/Room unwind is over.
                            // The native validator/release callback has returned through its finally.
                            try { finishMaintenance(current) }
                            finally {
                                try { allowance?.close() }
                                catch (error: Exception) { mutableFailure.value = classify(error) }
                                finally {
                                    current.allowanceCallbacksOpen.value = false
                                    activeLock.withLock { maintenance.remove(current.token) }
                                    wake.trySend(Unit)
                                }
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // A locally revoked maintenance child must not kill the scheduler; external shutdown must.
            currentCoroutineContext().ensureActive()
        }
    }

    private suspend fun finishMaintenance(run: MaintenanceRun) {
        val claim = run.claim
        try { (claim?.fileLease ?: run.orphanLease)?.close() }
        finally {
            if (claim != null) {
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
                } catch (error: Exception) { mutableFailure.value = classify(error) }
            }
        }
    }

    private suspend fun recoverFinalization(id: String) {
        withMaintenance(id, MaintenanceKind.FINALIZATION) { claim ->
            val intent = claim.intent!!
            try {
                store.update { snapshot ->
                    if (!matchesMaintenance(snapshot, claim)) throw CancellationException("Finalization identity revoked")
                    requireMaterializedBinding(snapshot, snapshot.downloads.getValue(id), intent.mediaId,
                        intent.executionBindingId, intent.representationId)
                    snapshot
                }.getOrThrow()
                val destination = files.ownedPath(intent.finalPath)
                val published = storageOperation { files.fileSystem.exists(destination) }
                val chosen = if (published) destination else files.ownedPath(intent.stagingPath)
                val asset = OfflineAsset("recover-$id", intent.mediaId, chosen.toString(), intent.sizeBytes,
                    mimeType = intent.mimeType, codecs = intent.codecs, sha256 = intent.sha256,
                    executionBindingId = intent.executionBindingId, measuredDurationMs = intent.mediaDurationMs)
                files.verifiedFile(asset)
                validate(asset)
                files.verifiedFile(asset)
                currentCoroutineContext().ensureActive()
                commitFinalization(intent, claim.leaseOwner, claim.lifecycleGeneration, alreadyPublished = published)
            } catch (cancelled: CancellationException) { throw cancelled }
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
                validate(asset)
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
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val failure = classify(error)
                mutableFailure.value = failure
                store.update { snapshot ->
                    if (!matchesMaintenance(snapshot, claim)) return@update snapshot
                    val record = snapshot.downloads.getValue(id)
                    snapshot.copy(downloads = snapshot.downloads + (id to record.copy(failure = failure, updatedEpochMs = clock.epochMs())))
                }.getOrThrow()
            }
        }
    }

    private suspend fun sweepRetiredStaging() {
        for (path in files.generatedStagingParts()) {
            currentCoroutineContext().ensureActive()
            val life = lifecycle.value
            if (life.closed || life.suspended) return
            // Metadata-only preflight and a nonblocking native claim avoid starting an OS worker
            // for empty/live generations. All recursive deletion runs under the execution gate.
            val snapshot = store.state.value
            if (stagingReferenced(snapshot, path)) continue
            runMaintenance(prepare = prepare@ {
                if (lifecycle.value.closed || lifecycle.value.suspended) return@prepare null
                val lease = files.tryLeasePaths(listOf(path)) ?: return@prepare null
                MaintenanceRun(newEntityId("download-sweep"), lifecycle.value.generation, orphanLease = lease)
            }) { run ->
                store.update { snapshot ->
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
        return record.mediaId == mediaId && record.executionBindingId == bindingId && record.representationId == representationId &&
            binding.mediaId == mediaId && binding.selectionOrdinal == 0 && binding.firstResultKind == FirstResultKind.VIDEO &&
            binding.queryTitle.isNotBlank() && binding.selectedVideoId == binding.firstResultId && VIDEO_ID.matches(binding.firstResultId) &&
            (binding.firstResultUrl == null || binding.firstResultUrl == "https://www.youtube.com/watch?v=${binding.firstResultId}")
    }

    private fun requireMaterializedBinding(snapshot: AppSnapshot, record: DownloadRecord, mediaId: String,
        bindingId: String?, representationId: String?) {
        if (!materializedBindingMatches(snapshot, record, mediaId, bindingId, representationId))
            throw VeyraException(AppFailure(FailureCode.CORRUPT_MEDIA, "offline_execution_binding_mismatch"))
    }

    private fun signalWork() { wake.trySend(Unit); maintenanceWake.trySend(Unit) }

    private fun queuedStatus(snapshot: AppSnapshot): DownloadStatus = when {
        snapshot.settings.offlineOnly -> DownloadStatus.WAITING_NETWORK
        lifecycle.value.suspended -> DownloadStatus.NEEDS_FOREGROUND
        else -> DownloadStatus.QUEUED
    }
    private fun queueFailure(snapshot: AppSnapshot): AppFailure? =
        if (!snapshot.settings.offlineOnly && lifecycle.value.suspended) AppFailure(FailureCode.BACKGROUND_RESTRICTED) else null
    private fun DownloadRecord.liveLease(now: Long): Boolean = leaseOwner != null && (leaseExpiresEpochMs ?: 0) > now
    private fun requireOpen() { check(!lifecycle.value.closed) { "Download coordinator closed" } }
    private fun classify(error: Exception): AppFailure = when (error) {
        is VeyraException -> error.failure
        is IllegalArgumentException, is IllegalStateException -> AppFailure(FailureCode.INVALID_STATE)
        else -> AppFailure(FailureCode.UNEXPECTED)
    }

    companion object {
        private const val MAX_ASSET_CHECKS_PER_PASS = 2
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        private const val MAX_RETRIES = 5
        private val BUSY = setOf(DownloadStatus.RESOLVING, DownloadStatus.RUNNING, DownloadStatus.VERIFYING)
        private val QUEUED = setOf(DownloadStatus.QUEUED, DownloadStatus.WAITING_NETWORK)
        private val RUNNABLE_OR_BUSY = BUSY + QUEUED
        private val RETRYABLE = setOf(FailureCode.NETWORK_UNAVAILABLE, FailureCode.RATE_LIMITED)
        fun retryDelayMs(attempt: Int, retryAfterMs: Long? = null): Long =
            maxOf((2_000L shl attempt.coerceIn(0, 6)).coerceAtMost(120_000), retryAfterMs?.coerceIn(0, 86_400_000) ?: 0)
    }
}
