package dev.veyra.downloads

import dev.veyra.acquisition.*
import dev.veyra.data.AcquisitionRepositoryState
import dev.veyra.model.*
import dev.veyra.playback.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

internal class DownloadBatchWorkRun(
    val work: PendingDownloadBatchWork,
    val hostGeneration: Long,
) {
    val token = newEntityId("download-batch-work")
    var job: Job? = null // Only the existing commands + activeLock install/read this owner.
    val allowanceCallbacksOpen = MutableStateFlow(true)
    val actionFinished = MutableStateFlow(false)
    override fun toString() = "DownloadBatchWorkRun(kind=${work.kind},identity=redacted)"
}

/** Source selection returns no physical download owner. Its native allowance still must drain. */
internal class DownloadBatchWorkCleanup(
    val runtime: DownloadBatchWorkRuntime,
    val steps: List<DownloadPhysicalCleanupStep>,
) : DownloadCleanupOwner(newEntityId("download-batch-work-cleanup"))

/**
 * One member operation in DownloadCoordinator's EXISTING active map/scope/cleanup registry.
 * There is no scope, loop, request retry, downloader, player or new transaction owner here.
 * Canonical AcquisitionCoordinator alone claims/settles a once-only search. Committed receipts
 * attach locally; an accepted exact rejoin enters the same physical executor's admission CAS.
 */
internal class DownloadBatchWorkRuntime(
    private val dependencies: DownloadAcquisitionDependencies,
    private val physical: DownloadPhysicalRuntime,
    private val files: DownloadFiles,
    private val gate: DownloadExecutionGate,
    private val clock: Clock,
    private val allowanceExpired: suspend (DownloadBatchWorkRun) -> Unit,
) {
    private val host get() = physical.host
    private val adapter = AcquisitionDownloadAdapter(dependencies.repository, dependencies.acquisition, physical)
    private val current = MutableStateFlow<DownloadBatchWorkRun?>(null)
    private val deferrals = MutableStateFlow<Map<PendingDownloadBatchKey, DownloadBatchWorkDeferral>>(emptyMap())

    internal fun schedulingInputs(state: AcquisitionRepositoryState): DownloadBatchWorkSchedulingInputs {
        val pending = projectPendingDownloadBatchWork(state, clock.epochMs())
        val byKey = pending.work.associateBy { it.key }
        return DownloadBatchWorkSchedulingInputs(pending, deferrals.value.filter { (key, value) -> byKey[key] == value.work })
    }

    internal fun networkCount(): Int = if (current.value?.work?.requiresNetwork == true) 1 else 0

    /** Caller holds the same activeLock; neither a key nor a completed Job proves consumption. */
    internal fun isCurrentRun(run: DownloadBatchWorkRun): Boolean = current.value === run && run.job != null &&
        host.active[run.token] === run.job

    internal fun activeNetworkJobs(): List<Job> = current.value?.takeIf { it.work.requiresNetwork && isCurrentRun(it) }
        ?.job?.let(::listOf).orEmpty()

    /** A self-claimed request becomes CLAIMED/COMMITTED during this run; do not cancel that. */
    internal fun staleIntentJobs(state: AcquisitionRepositoryState): List<Job> = current.value?.takeIf { run ->
        isCurrentRun(run) && !intentMatches(state, run.work.key)
    }?.job?.let(::listOf).orEmpty()

    /** Existing scheduler calls once under commands + activeLock; actual native work is outside. */
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    internal suspend fun installCandidate(state: AcquisitionRepositoryState, scope: CoroutineScope, admittingJob: Job) {
        if (current.value != null) return
        val life = host.state()
        if (!hostEligible(life)) return
        val inputs = schedulingInputs(state)
        val candidate = inputs.pending.work.firstOrNull { work ->
            (inputs.deferrals[work.key]?.untilEpochMs ?: 0) <= clock.epochMs() &&
                (!work.requiresNetwork || !life.offlineOnly && life.network == DownloadNetworkState.CONNECTED &&
                    physical.transferCount() < life.parallelism)
        } ?: return
        admittingJob.ensureActive()
        dependencies.requireOwnerAdmission()
        val run = DownloadBatchWorkRun(candidate, life.generation)
        val registered = CompletableDeferred<Boolean>()
        var task: Job? = null
        try {
            val launched = scope.launch(start = CoroutineStart.ATOMIC) {
                val actual = currentCoroutineContext().job
                try {
                    if (withContext(NonCancellable) { registered.await() }) execute(run)
                } finally {
                    withContext(NonCancellable) {
                        host.activeLock.withLock {
                            if (host.active[run.token] === actual) host.active.remove(run.token)
                            if (current.value === run) current.value = null
                            host.changed()
                        }
                        host.signalWork()
                    }
                }
            }
            task = launched
            run.job = launched
            host.active[run.token] = launched
            current.value = run
            host.changed()
            registered.complete(true)
        } catch (error: Throwable) {
            task?.cancel()
            registered.complete(false) // An actual ATOMIC task owns any installed map entry.
            throw error
        }
    }

    private suspend fun execute(run: DownloadBatchWorkRun) {
        var allowance: DownloadExecutionLease? = null
        var slot: DownloadTransferSlot? = null
        val partial = DownloadLeaseCleanupScope(host.cleanupRegistry)
        try {
            if (!rejoinBeforeOperation(run, run.work)) return
            // A returned native owner is captured even if an inline expiration cancels our
            // actual registered Job before acquire returns. No admission lock is held here.
            withContext(NonCancellable) { allowance = gate.acquire { expire(run) } }
            if (!rejoinBeforeOperation(run, run.work)) return
            if (run.work.requiresNetwork) {
                slot = files.tryTransferSlot(host.state().parallelism, partial)
                if (slot == null) { defer(run.work); return }
                if (!rejoinBeforeOperation(run, run.work)) return
            }
            when (run.work.kind) {
                PendingDownloadBatchWorkKind.SELECT_READY, PendingDownloadBatchWorkKind.ATTACH_COMMITTED -> {
                    val key = run.work.key
                    // Exactly one member/resolve. InProgress, Unknown, Stale, child collections
                    // and rejected attachments cannot enter the physical admission below.
                    val resolution = dependencies.batches.resolveMember(key.batchId, key.memberPosition, key.intentRevision)
                    val next = host.commands.withLock {
                        host.activeLock.withLock {
                            ensureRun(run, network = false)
                            val view = host.acquisitionView()
                            ensureRun(run, network = false)
                            admissionAfterResolution(view.acquisition, key, resolution, clock.epochMs())
                        }
                    }
                    if (next != null) {
                        admit(run, next)
                        clearDeferral(run.work.key)
                    } else {
                        // An unchanged Busy/Unknown/Stale/rejected return is NOT fresh work
                        // permission. Canonical terminal states disappear from eligibility;
                        // an unchanged READY/local hint waits for bounded re-arbitration.
                        defer(run.work)
                    }
                }
                PendingDownloadBatchWorkKind.ADMIT_BOUND -> {
                    admit(run, run.work)
                    clearDeferral(run.work.key)
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val failure = classify(error)
            if (failure.code == FailureCode.BACKGROUND_RESTRICTED) expire(run)
            else {
                host.publishFailure(failure)
                // This only defers an unchanged eligible hint. A sent/unknown request has NO
                // eligible SELECT hint, so the once-only source contract cannot be retried.
                defer(run.work, failure)
            }
        } finally {
            withContext(NonCancellable) {
                run.actionFinished.value = true
                val temporary = partial.pending()
                val cleanup = DownloadBatchWorkCleanup(this@DownloadBatchWorkRuntime, listOf(DownloadPhysicalCleanupStep({
                    allowance?.close()
                    run.allowanceCallbacksOpen.value = false
                })) + temporary.map { DownloadPhysicalCleanupStep({ it.close() }, explicitRetryOnly = it.failed.value) } +
                    listOf(DownloadPhysicalCleanupStep({ slot?.close(); Unit })))
                // Register before adopting temporary compensation: never an unowned handle gap.
                host.cleanupRegistry.register(cleanup)
                partial.adopted()
                attemptCleanup(cleanup)
                host.changed()
                host.signalWork()
            }
        }
    }

    private suspend fun admit(run: DownloadBatchWorkRun, bound: PendingDownloadBatchWork) {
        if (!rejoinBeforeOperation(run, bound)) return
        val key = bound.key
        // This adapter uses the injected acquisition coordinator and exact physical executor;
        // the latter rechecks owner admission plus immutable member/claim/binding in Room.
        adapter.admitMember(key.batchId, key.memberPosition, key.intentRevision)
    }

    private suspend fun rejoinBeforeOperation(run: DownloadBatchWorkRun, expected: PendingDownloadBatchWork): Boolean =
        host.commands.withLock {
            host.activeLock.withLock {
                ensureRun(run, expected.requiresNetwork)
                val view = host.acquisitionView()
                ensureRun(run, expected.requiresNetwork)
                if (rejoinPendingDownloadBatchWork(view.acquisition, expected.key, clock.epochMs()) != expected) false
                else { dependencies.requireOwnerAdmission(); true }
            }
        }

    private fun ensureRun(run: DownloadBatchWorkRun, network: Boolean) {
        run.job?.ensureActive() ?: throw CancellationException("Batch work not registered")
        val state = host.state()
        state.failure?.let { throw VeyraException(it) }
        if (!isCurrentRun(run) || !hostEligible(state) || state.generation != run.hostGeneration)
            throw CancellationException("Batch work owner changed")
        if (network && state.offlineOnly) throw VeyraException(AppFailure(FailureCode.OFFLINE_POLICY))
        if (network && state.network != DownloadNetworkState.CONNECTED)
            throw VeyraException(AppFailure(FailureCode.NETWORK_UNAVAILABLE))
    }

    /** Consumes only successful close stages; failed owners await EXPLICIT existing close retry. */
    internal suspend fun attemptCleanup(cleanup: DownloadBatchWorkCleanup, retryFailed: Boolean = false, report: Boolean = true) {
        cleanup.lock.withLock {
            if (cleanup.failed.value && !retryFailed) return@withLock
            try {
                while (cleanup.nextStep < cleanup.steps.size) {
                    val step = cleanup.steps[cleanup.nextStep]
                    if (step.explicitRetryOnly && !retryFailed) {
                        cleanup.failed.value = true
                        return@withLock
                    }
                    step.close()
                    cleanup.nextStep++
                }
                host.cleanupRegistry.remove(cleanup)
            } catch (_: Throwable) { cleanup.failed.value = true }
        }
        if (report) host.publishCleanupFailure()
    }

    private suspend fun expire(run: DownloadBatchWorkRun) {
        if (run.allowanceCallbacksOpen.value && !run.actionFinished.value) allowanceExpired(run)
    }

    private fun defer(work: PendingDownloadBatchWork, failure: AppFailure? = null) {
        if (rejoinPendingDownloadBatchWork(dependencies.repository.state.value, work.key, clock.epochMs()) != work) return
        deferrals.update { previous ->
            val count = previous[work.key]?.takeIf { it.work == work }?.refusals?.coerceAtMost(4)?.plus(1) ?: 1
            val delay = (5_000L shl (count - 1)).coerceAtMost(60_000)
            previous + (work.key to DownloadBatchWorkDeferral(work,
                clock.epochMs().coerceIn(0, Long.MAX_VALUE - delay) + delay, count, failure))
        }
        host.changed()
    }

    private fun clearDeferral(key: PendingDownloadBatchKey) { deferrals.update { it - key } }

    private fun hostEligible(state: DownloadPhysicalHostState): Boolean = state.started && !state.closed && !state.suspended &&
        state.drainsInFlight == 0 && state.failure == null

    private fun intentMatches(state: AcquisitionRepositoryState, key: PendingDownloadBatchKey): Boolean {
        val batch = state.batches[key.batchId] ?: return false
        val member = batch.members.singleOrNull { it.position == key.memberPosition } ?: return false
        return batch.id == key.batchId && batch.intent == DownloadBatchIntent.ACTIVE && batch.intentRevision == key.intentRevision &&
            member.requestId == key.requestId && member.occurrenceId == key.occurrenceId &&
            state.occurrences[key.occurrenceId]?.requestId == key.requestId
    }

    private fun classify(error: Throwable): AppFailure = when (error) {
        is VeyraException -> error.failure
        is IllegalArgumentException, is IllegalStateException -> AppFailure(FailureCode.INVALID_STATE, "download_batch_work_invalid")
        else -> AppFailure(FailureCode.UNEXPECTED, "download_batch_work_failed")
    }
}
