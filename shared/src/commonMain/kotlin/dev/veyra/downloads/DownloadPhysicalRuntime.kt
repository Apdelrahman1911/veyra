package dev.veyra.downloads

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.playback.*
import dev.veyra.source.execution.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All dependencies belong to the SAME app lifetime; no new client/player/store is constructed. */
class DownloadAcquisitionDependencies(
    val repository: AcquisitionLibraryRepository,
    val gateway: AcquisitionExecutionGateway,
    val validator: LocalMediaValidator,
    /** Must await real reader/native release; the persisted cleanup fence blocks future readers. */
    val beforePhysicalDelete: suspend (MaterializationAsset) -> Unit,
    /**
     * Required even when a migrated job has no asset row. Root routes the exact schema2 mediaId
     * through the EXISTING PlaybackController.releaseForAssetRemoval(...).getOrThrow(), which
     * awaits staged/pending/retired native readers. Missing rows or expired leases are not proof.
     */
    val beforePhysicalAdoption: suspend (PhysicalDownloadProducer.Schema2) -> Unit,
    /** Synchronous application coverage/activation check BEFORE new durable owner admission. */
    val requireOwnerAdmission: () -> Unit,
    /** The existing app-owned once-only selector and durable batch coordinator; no new source owner. */
    val acquisition: AcquisitionCoordinator,
    val batches: DownloadBatchCoordinator,
    /** Shared synchronously with BOTH local resolvers; no independently defaulted registry. */
    val materializationEvidence: DownloadMaterializationEvidence,
    val compatibilityPolicyVersion: Int = 1,
) {
    init {
        require(compatibilityPolicyVersion >= 1)
        require(acquisition.transactionOwner === repository.transactionOwner && batches.transactionOwner === repository.transactionOwner &&
            materializationEvidence.transactionOwner === repository.transactionOwner) {
            "Download acquisition, batches and materialization evidence require the same Room transaction owner"
        }
    }
}

internal data class DownloadPhysicalHostState(
    val started: Boolean,
    val closed: Boolean,
    val suspended: Boolean,
    val checksActivated: Boolean,
    val generation: Long,
    val drainsInFlight: Int,
    val failure: AppFailure?,
    val offlineOnly: Boolean,
    val parallelism: Int,
    val network: DownloadNetworkState,
)

/** Adapter to the existing coordinator's locks, active jobs, scope and cleanup registry. */
internal class DownloadPhysicalHost(
    val commands: Mutex,
    val activeLock: Mutex,
    val active: MutableMap<String, Job>,
    val cleanupRegistry: DownloadCleanupRegistry,
    val state: () -> DownloadPhysicalHostState,
    /** One same-owner Room read; never pair independently sampled UI/acquisition revisions. */
    val acquisitionView: suspend () -> LibraryAcquisitionView,
    val changed: () -> Unit,
    val signalWork: () -> Unit,
    val publishFailure: (AppFailure) -> Unit,
    val publishCleanupFailure: suspend () -> Unit,
    /** Synchronous, nonthrowing latch on the EXISTING host admission fence; never takes commands. */
    val failCheckpointAdmission: () -> Unit,
    /** Exact active-map/generation guard, then latch and cancel ALL jobs; never join inline. */
    val allowanceExpired: suspend (DownloadPhysicalRun) -> Unit,
)

internal class DownloadPhysicalRun(
    val epoch: DownloadOwnershipEpochs.Epoch,
    val fence: PhysicalDownloadFence,
    current: PhysicalDownloadJoin,
    val hostGeneration: Long,
    val kind: PhysicalDownloadMaintenanceKind?,
    val pathLease: DownloadFileLease,
    val slot: DownloadTransferSlot?,
) {
    private val currentState = MutableStateFlow(current)
    var current: PhysicalDownloadJoin
        get() = currentState.value
        set(value) { currentState.value = value }
    val mutation = Mutex()
    val allowanceCallbacksOpen = MutableStateFlow(true)
    val actionFinished = MutableStateFlow(false)
    /** Latched by the existing host BEFORE cancellation; durable settlement follows real drain. */
    val hostFinishStatus = MutableStateFlow<DownloadStatus?>(null)
    var published: PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>? = null
    /** Set only after this run's complete hash/native check; the durable finish grants its use. */
    var retainedValidation: RetainedValidationGrant? = null
    var recoveredValidationUse: AssetUse? = null
    var recoveredValidationFence: AcquisitionDownloadFence? = null
    var recoveredValidationRevision: Long = 0
    var cleanupCompleted = false
    val validationReaders = DownloadValidationReaderRetention()
    override fun toString() = "DownloadPhysicalRun(identity=redacted,paths=redacted)"
}

/** Exact owner/asset identity captured BEFORE a manual retained-file validation starts. */
internal data class RetainedValidationGrant(
    val expectedClaim: DownloadClaim,
    val requestedBindingId: ExecutionBindingId,
    val asset: MaterializationAsset,
    /** Non-null only for the batch adapter path; standalone uses its existing public receipt. */
    val member: AcquisitionDownloadMemberKey? = null,
)

/** Physical stages and the durable finish are deliberately different acknowledgements. */
internal class DownloadPhysicalCleanup(
    val runtime: DownloadPhysicalRuntime,
    val epoch: DownloadOwnershipEpochs.Epoch,
    val physicalSteps: List<DownloadPhysicalCleanupStep>,
    /** Only an actually accepted canonical owner creates a host checkpoint obligation. */
    val acceptedDurableOwner: Boolean,
    /** Exact claim set observed at owner admission; a changed set is positive supersession. */
    val expectedClaims: List<DownloadClaim> = emptyList(),
    /** Null for a transfer finalizer; maintenance kinds may allow a superseded stale settlement. */
    val maintenanceKind: PhysicalDownloadMaintenanceKind? = null,
    val durableFinish: suspend () -> AcquisitionDownloadDurableSettlement?,
) : DownloadCleanupOwner(newEntityId("download-physical-cleanup")) {
    var durableAttempted = false
    var durableResult: AcquisitionDownloadDurableSettlement? = null
}

internal class DownloadPhysicalCleanupStep(
    val close: suspend () -> Unit,
    /** A temporary compensation ALREADY failed; initial unwind may not retry that stage. */
    val explicitRetryOnly: Boolean = false,
)

/** Coherent removal observation independent of a registry owner that may already be consumed. */
internal sealed interface DownloadCleanupObservation {
    data object InProgress : DownloadCleanupObservation
    data object Completed : DownloadCleanupObservation
    data class Failed(val failure: AppFailure) : DownloadCleanupObservation
}

/** Observation is generation-scoped so a previous removal cannot settle a newer one. */
private data class GeneratedCleanupObservation(
    val ownershipGeneration: Long,
    val observation: DownloadCleanupObservation,
)

private data class DownloadRemovalSnapshot(
    val pending: Boolean,
    val observation: DownloadCleanupObservation?,
    val ownerPresent: Boolean,
)

/**
 * Concrete schema3 body of DownloadCoordinator's EXISTING claim/execute/maintenance flow.
 * It creates no scope, polling loop, second queue, HTTP downloader or local native validator.
 * The same coordinator schedules it with its activeLock, native slots and cleanup owner graph.
 * BOTH typed and known-schema2 jobs use the canonical physical commands; legacy projections
 * are never used as a second write authority. All transfers still invoke the existing kmpYT
 * gateways, and neutral subjects never become fabricated MediaItem/OfflineAsset instances.
 */
internal class DownloadPhysicalRuntime(
    libraryOwner: LibraryTransactionOwned,
    private val dependencies: DownloadAcquisitionDependencies,
    private val files: DownloadFiles,
    private val legacyGateway: KmpMediaGateway,
    private val capabilities: EngineCapabilities,
    private val gate: DownloadExecutionGate,
    private val clock: Clock,
    private val leaseDurationMs: Long,
    private val heartbeatMs: Long,
    internal val host: DownloadPhysicalHost,
    private val removalOwnerGraceMs: Long = REMOVAL_OWNER_GRACE_MS,
) : AcquisitionDownloadExecutor {
    internal val repository = dependencies.repository
    override val transactionOwner: LibraryTransactionOwner get() = repository.transactionOwner
    internal val epochs = DownloadOwnershipEpochs()
    internal val userCommands = DownloadPhysicalCommands(this, dependencies, clock)
    private val owner = newEntityId("download-physical-owner")
    private val materializationEvidence = dependencies.materializationEvidence
    private val materializationChecks = materializationEvidence.checks
    internal val checkedMaterializations = materializationChecks
    private val runs = MutableStateFlow<Map<String, DownloadPhysicalRun>>(emptyMap())
    private val claimDeferrals = MutableStateFlow<Map<DownloadClaimKey, DownloadPhysicalClaimDeferral>>(emptyMap())
    // A retired native owner must not stay retained merely to preserve a durable diagnostic.
    // Conversely a later background/close call cannot forget an accepted finalizer failure.
    private val firstCheckpointFailure = MutableStateFlow<AppFailure?>(null)
    // Keep the original unknown outcome process-local, never in Room or public diagnostics.
    // Neither value resets: a later finalizer cannot re-arm delivery after the first drain.
    private val firstUnknownCheckpointFailure = MutableStateFlow<Throwable?>(null)
    private val unknownCheckpointFailureDelivered = MutableStateFlow(false)
    private val cleanupObservations = MutableStateFlow<Map<String, GeneratedCleanupObservation>>(emptyMap())

    init {
        require(libraryOwner.transactionOwner === repository.transactionOwner) { "Downloads require the same Room transaction owner" }
        require(leaseDurationMs in 1_000..600_000 && heartbeatMs in 1 until leaseDurationMs)
        require(removalOwnerGraceMs > 0)
    }

    internal fun candidates(): List<String> = repository.state.value.physicalDownloads.values
        .sortedWith(compareByDescending<PhysicalDownloadJob> { it.priority }.thenBy { it.createdEpochMs }.thenBy { it.id })
        .map { it.id }

    internal fun transferCount(): Int = runs.value.values.count { it.kind == null }

    internal fun schedulingInputs(acquisition: AcquisitionRepositoryState): DownloadPhysicalSchedulingInputs {
        val records = acquisition.physicalSchedulingRecords()
        return DownloadPhysicalSchedulingInputs(records, checkedPhysicalDownloadIds(acquisition, materializationChecks.value),
            claimDeferrals.value.filter { (key, value) -> records[key.id] == value.record })
    }

    internal fun wakePlan(inputs: DownloadPhysicalSchedulingInputs, key: DownloadSchedulingKey): DownloadWakePlan =
        projectPhysicalDownloadWakePlan(inputs, host.state(), clock.epochMs(), key)

    /** Caller holds activeLock, so retirement cannot remove an owner before this snapshot. */
    internal fun ownedEpochs(): List<DownloadOwnershipEpochs.Epoch> = (runs.value.values.map { it.epoch } +
        host.cleanupRegistry.owners.value.values.filterIsInstance<DownloadPhysicalCleanup>().map { it.epoch }).distinct()

    /** Only the existing activeLock authorizes these exact-map comparisons. */
    internal fun isCurrentRun(run: DownloadPhysicalRun): Boolean = runs.value[run.fence.downloadId] === run &&
        run.epoch.job != null && host.active[run.fence.downloadId] === run.epoch.job

    internal fun activeTransferJobs(): List<Job> = runs.value.values.filter { it.kind == null && isCurrentRun(it) }
        .mapNotNull { it.epoch.job }

    private fun forgetRun(run: DownloadPhysicalRun) {
        runs.update { current -> if (current[run.fence.downloadId] === run) current - run.fence.downloadId else current }
    }

    /**
     * A lowered canonical global limit revokes only the actual surplus transfer slots through
     * their normal cancellation/unwind. It never releases a lease, path or slot early and never
     * touches local maintenance; the next admission sees the same persisted limit.
     */
    internal fun revokeExcessSlots(parallelism: Int) {
        val limit = parallelism.coerceIn(1, 4)
        runs.value.values.forEach { run ->
            if (run.kind == null && (run.slot?.index ?: 0) >= limit) {
                host.active[run.fence.downloadId]?.cancel(
                    CancellationException("Download transfer slot retired by parallelism policy"))
            }
        }
    }

    /** No early lease release or pretend checkpoint. Actual finalizers use canonical commands. */
    internal fun requestHostSuspension(target: DownloadStatus) {
        runs.value.values.forEach { run ->
            if (target != DownloadStatus.WAITING_NETWORK || run.kind == null) run.hostFinishStatus.value = target
        }
    }

    /** Durable diagnostics survive consumed physical owners; refused claims are not obligations. */
    internal fun drainCheckpointFailure(): AppFailure? = firstCheckpointFailure.value

    /** The host calls this only AFTER joining owners. Concurrent drains deliver the raw cause once. */
    internal fun takeUnknownCheckpointFailure(): Throwable? {
        val failure = firstUnknownCheckpointFailure.value ?: return null
        return if (unknownCheckpointFailureDelivered.compareAndSet(false, true)) failure else null
    }

    /** Explicit already-gated foreground resume only; never called by cold/scheduled restore. */
    internal suspend fun resumeHostPending() {
        for (id in candidates()) {
            val current = repository.physicalDownload(id) ?: continue
            if (DownloadPhysicalClaimRules.backgroundBlocked(current.job) ||
                current.job.status == DownloadStatus.PAUSED && !current.job.userPaused) {
                val result = repository.reconcilePhysicalDownloadIntent(ReconcilePhysicalDownloadIntent(id,
                    current.job.producer, current.job.ownershipGeneration, current.claims, clock.epochMs(), allowHostResume = true))
                if (result is PhysicalDownloadCommit.Committed) clearClaimDeferrals(id)
            }
        }
        host.changed()
        host.signalWork()
    }

    /** Used by the EXISTING orphan sweep under its exact OS path lock and fresh Room barrier. */
    internal fun stagingReferenced(state: AcquisitionRepositoryState, path: String): Boolean {
        return state.physicalDownloads.values.any { it.stagingPath == path } ||
            state.physicalFinalizations.values.any { it.stagingPath == path } ||
            state.physicalAttempts.values.any { it.stagingPath == path && state.physicalDownloads[it.downloadId]?.let { job ->
                job.cleanupPending || job.livePhysicalLease(clock.epochMs()) } == true }
    }

    override suspend fun admitMember(command: AdmitAcquisitionDownload): AcquisitionDownloadAdmission = host.commands.withLock {
        currentCoroutineContext().ensureActive()
        dependencies.requireOwnerAdmission()
        if (host.state().closed) fail(FailureCode.INVALID_STATE, "download_executor_closed")
        host.state().failure?.let { throw VeyraException(it) }
        val member = command.member.expectation(command.previouslyAttachedClaimId)
        val committed = withContext(NonCancellable) {
            repository.admitBatchPhysicalDownload(AdmitBatchPhysicalDownload(member, newEntityId("download"),
                DownloadClaimId(newEntityId("download-claim")), clock.epochMs()))
        }
        if (committed !is PhysicalDownloadCommit.Committed) fail(FailureCode.INVALID_STATE, "download_batch_action_stale")
        val admitted = committed.value
        if (admitted.member != member || admitted.requestedContext.request != command.acquisition.request ||
            admitted.requestedContext.binding != command.acquisition.binding ||
            admitted.requestedContext.subject != command.acquisition.subject ||
            admitted.requestedContext.recording != command.acquisition.recording ||
            admitted.requestedContext.receipt != command.acquisition.receipt)
            fail(FailureCode.RECOVERY_REQUIRED, "download_admission_join_mismatch")
        val fence = host.activeLock.withLock { issueFence(command.member, admitted.claim, admitted.physical).first }
        host.changed()
        host.signalWork() // A hint only. No initialization/activation/native work is started here.
        AcquisitionDownloadAdmission(fence, committed.libraryRevision)
    }

    /**
     * Metadata intent only, under the EXISTING commands -> activeLock order. A cold/offline
     * healthy owner may queue the same accepted recording; this never activates the host or
     * performs source, native, file or slot work. Actual execution still needs its normal OS
     * slot/path/epoch/lease barriers. No cleanup retry, adoption or quiescence is implied here.
     */
    override suspend fun retryMember(command: RetryAcquisitionDownload): AcquisitionDownloadRetryResult = try {
        val result = host.commands.withLock {
            currentCoroutineContext().ensureActive()
            dependencies.requireOwnerAdmission()
            host.activeLock.withLock {
                val admittedHost = host.state()
                requireRetryHost(admittedHost)
                val original = repository.physicalDownload(command.expectedClaim.physicalJobId)
                    ?: fail(FailureCode.INVALID_STATE, "download_member_job_missing")
                requireMemberClaim(command.member, command.expectedClaim, original)
                val job = original.job
                if (job != command.expectedJob || !isExplicitMemberRetryCandidate(job) ||
                    original.asset != null || original.finalization != null || original.activeAttempt != null ||
                    original.cleanupReservation != null || original.attempts.any {
                        it.finishedEpochMs == null || it.status in setOf(DownloadAttemptStatus.RUNNING,
                            DownloadAttemptStatus.VALIDATING, DownloadAttemptStatus.FINALIZING)
                    }) fail(FailureCode.INVALID_STATE, "download_member_retry_stale")
                if (host.active.containsKey(job.id) || !epochs.canAdmit(job.id)) {
                    fail(FailureCode.RECOVERY_REQUIRED, "download_member_retry_owner_retained")
                }
                requireKnownProducer(original)
                currentCoroutineContext().ensureActive()
                val member = command.member.expectation(command.expectedClaim.id)
                val now = clock.epochMs()
                val committed = withContext(NonCancellable) {
                    // Coverage can disappear during the Room read or producer validation. These
                    // final checks intentionally allow offline/cold, never suspended/draining.
                    dependencies.requireOwnerAdmission()
                    val currentHost = host.state()
                    requireRetryHost(currentHost)
                    if (currentHost.generation != admittedHost.generation || host.active.containsKey(job.id) || !epochs.canAdmit(job.id)) {
                        fail(FailureCode.INVALID_STATE, "download_member_retry_stale")
                    }
                    repository.retryBatchPhysicalDownload(RetryBatchPhysicalDownload(member,
                        command.expectedClaim, command.expectedJob, now))
                }
                if (committed !is PhysicalDownloadCommit.Committed) fail(FailureCode.INVALID_STATE, "download_member_retry_stale")
                val retried = committed.value
                val expectedClaim = command.expectedClaim.copy(generation = command.expectedClaim.generation + 1, updatedEpochMs = now)
                if (retried.member != member || retried.claim != expectedClaim ||
                    retried.physical.job != job.afterExplicitMemberRetry(now) ||
                    retried.requestedContext.request.id != command.member.requestId ||
                    retried.requestedContext.binding.id != command.member.requestedBindingId ||
                    retried.requestedContext.subject.id != job.producer.subjectId ||
                    retried.requestedContext.recording.id != job.producer.recordingId) {
                    fail(FailureCode.RECOVERY_REQUIRED, "download_member_retry_join_mismatch")
                }
                val fence = issueFence(command.member, retried.claim, retried.physical).first
                clearClaimDeferrals(job.id)
                AcquisitionDownloadRetryResult.Retried(AcquisitionDownloadAdmission(fence, committed.libraryRevision), retried.physical.job)
            }
        }
        host.changed()
        host.signalWork() // Hint only; existing offline/cold/native gates are not bypassed.
        result
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) { AcquisitionDownloadRetryResult.Unavailable(classify(error)) }

    private fun requireRetryHost(state: DownloadPhysicalHostState) {
        state.failure?.let { throw VeyraException(it) }
        if (state.closed) fail(FailureCode.INVALID_STATE, "download_executor_closed")
        if (state.suspended || state.drainsInFlight != 0) fail(FailureCode.BACKGROUND_RESTRICTED, "download_host_not_active")
    }

    override suspend fun reconcileClaim(command: ReconcileAcquisitionDownload): AcquisitionDownloadReconciliation {
        data class Pending(val fence: AcquisitionDownloadFence, val epoch: DownloadOwnershipEpochs.Epoch,
            val durable: AcquisitionDownloadDurableSettlement, val stop: Boolean)
        val pending = host.commands.withLock {
            currentCoroutineContext().ensureActive()
            if (host.state().closed) fail(FailureCode.INVALID_STATE, "download_executor_closed")
            val original = repository.physicalDownload(command.expectedClaim.physicalJobId)
                ?: fail(FailureCode.INVALID_STATE, "download_member_job_missing")
            requireMemberClaim(command.member, command.expectedClaim, original)
            var joined = original
            var durable: AcquisitionDownloadDurableSettlement
            var stop = false
            try {
                val claim = repository.reconcilePhysicalDownloadClaim(ReconcilePhysicalDownloadClaim(
                    command.member.expectation(command.expectedClaim.id), command.expectedClaim.id,
                    command.expectedClaim.generation, clock.epochMs()))
                durable = when (claim) {
                    PhysicalDownloadCommit.Stale -> AcquisitionDownloadDurableSettlement.Stale
                    is PhysicalDownloadCommit.Committed -> {
                        joined = claim.value
                        val result = repository.reconcilePhysicalDownloadIntent(ReconcilePhysicalDownloadIntent(
                            joined.job.id, joined.job.producer, joined.job.ownershipGeneration, joined.claims, clock.epochMs()))
                        when (result) {
                            PhysicalDownloadCommit.Stale -> AcquisitionDownloadDurableSettlement.Stale
                            is PhysicalDownloadCommit.Committed -> {
                                joined = result.value
                                stop = summarizeDownloadClaims(joined.job.id, joined.claims).demand in setOf(
                                    DownloadClaimDemand.PAUSE_AND_RETAIN, DownloadClaimDemand.CANCEL_UNFINISHED_KEEP_COMPLETE,
                                    DownloadClaimDemand.REMOVE_CANDIDATE)
                                AcquisitionDownloadDurableSettlement.Confirmed(result.libraryRevision)
                            }
                        }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { durable = AcquisitionDownloadDurableSettlement.Unconfirmed(classify(error)) }
            val exact = joined.claims.singleOrNull { it.id == command.expectedClaim.id }
                ?: fail(FailureCode.INVALID_STATE, "download_member_claim_missing")
            val issued = host.activeLock.withLock { issueFence(command.member, exact, joined) }
            host.changed()
            Pending(issued.first, issued.second, durable, stop)
        }
        val physical = if (!pending.stop) AcquisitionDownloadPhysicalSettlement.NotRequested else {
            // This is independent of the owned job and all admission locks. Failed cleanup is
            // never automatically retried, and the exact same fence instance gets the ACK.
            if (epochs.drain(pending.epoch)) AcquisitionDownloadPhysicalSettlement.Quiescent(
                AcquisitionDownloadQuiescence(pending.fence))
            else AcquisitionDownloadPhysicalSettlement.Retained(host.state().failure)
        }
        val finished = if (pending.stop) epochs.durableSettlement(pending.epoch) else null
        // A failed finalizer CAS cannot be laundered by the earlier successful intent commit.
        val durable = when (finished) {
            is AcquisitionDownloadDurableSettlement.Unconfirmed, AcquisitionDownloadDurableSettlement.Stale -> finished
            else -> pending.durable
        }
        host.signalWork()
        return AcquisitionDownloadReconciliation(pending.fence, durable, physical)
    }

    override suspend fun validateAssetUse(command: ValidateAcquisitionDownloadAsset): AcquisitionDownloadAssetCheck {
        // Owned by this invocation, not an actor/UI callback. A real negative survives a later
        // cleanup exception; precheck/CAS/policy/busy/cleanup-only failures leave it null.
        var validationRejection: DownloadMaterializationRejection? = null
        var retainedRun: DownloadPhysicalRun? = null
        return try {
            val before = repository.physicalDownload(command.expectedClaim.physicalJobId)
            val explicitRetained = before?.job?.status == DownloadStatus.FAILED
            val outcome = withMaintenance(command.expectedClaim.physicalJobId, PhysicalDownloadMaintenanceKind.VALIDATE,
                explicitLocalRevalidation = explicitRetained,
                requestedBindingId = command.member.requestedBindingId) { run ->
                requireMemberClaim(command.member, command.expectedClaim, run.current)
                val asset = run.current.asset ?: fail(FailureCode.NOT_FOUND, "download_asset_missing")
                if (asset.id != command.materializationAssetId || run.current.job.assetId != asset.id ||
                    asset.producer != run.fence.producer || run.current.job.cleanupPending ||
                    command.expectedClaim.intent == DownloadBatchIntent.CANCELLED && !command.expectedClaim.retainCompletedAsset)
                    fail(FailureCode.INVALID_STATE, "download_asset_generation_changed")
                requireMaterialization(run.current, asset)
                requireValidationMember(command, run, asset)
                ensureRun(run, network = false)
                // withMaintenance owns the exact path lease and a fresh complete member join.
                val attempt = materializationEvidence.admit(run.fence.downloadId, asset,
                    command.member.requestedBindingId, command.member.requestId, command.member, command.expectedClaim)
                validate(run, asset.validationInput(), attempt) { validationRejection = it }
                ensureRun(run, network = false)
                if (run.current.job.status == DownloadStatus.FAILED) {
                    // The durable COMPLETE/use transition is the exact-fenced finish, after the
                    // physical readers/allowance/path lease close. No provisional use or status.
                    retainedRun = run
                    run.retainedValidation = RetainedValidationGrant(command.expectedClaim,
                        command.member.requestedBindingId, asset, command.member)
                    null
                } else {
                    val use = repository.commitMaterializationUse(CommitMaterializationUse(
                        command.member.expectation(command.expectedClaim.id), command.expectedClaim.id, command.expectedClaim.generation,
                        run.fence.ownershipGeneration, asset, dependencies.compatibilityPolicyVersion, clock.epochMs()))
                    if (use !is PhysicalDownloadCommit.Committed) fail(FailureCode.INVALID_STATE, "download_asset_use_stale")
                    if (use.value.requestedBindingId != command.member.requestedBindingId ||
                        use.value.materializationAssetId != asset.id ||
                        use.value.compatibilityPolicyVersion != dependencies.compatibilityPolicyVersion)
                        fail(FailureCode.RECOVERY_REQUIRED, "download_asset_use_mismatch")
                    materializationEvidence.recordPhysicalSuccess(run.fence.downloadId, asset)
                    val fence = host.activeLock.withLock { issueFence(command.member, command.expectedClaim, run.current).first }
                    AcquisitionDownloadAssetCheck.Validated(AcquisitionDownloadValidatedUse(fence, use.value, use.libraryRevision))
                }
            }
            retainedRun?.let { recovered ->
                val use = recovered.recoveredValidationUse
                val fence = recovered.recoveredValidationFence
                return if (use != null && fence != null) AcquisitionDownloadAssetCheck.Validated(
                    AcquisitionDownloadValidatedUse(fence, use, recovered.recoveredValidationRevision))
                else AcquisitionDownloadAssetCheck.Unavailable(
                    AppFailure(FailureCode.INVALID_STATE, "download_retained_validation_not_committed"))
            }
            return outcome ?: AcquisitionDownloadAssetCheck.Unavailable(AppFailure(FailureCode.BACKGROUND_RESTRICTED,
                "download_asset_check_not_admitted"))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            // A real cancellation after the negative still revokes validity but returns no
            // fabricated Unavailable/receipt, even if cleanup masked the cancellation cause.
            currentCoroutineContext().ensureActive()
            AcquisitionDownloadAssetCheck.Unavailable(classify(error), validationRejection)
        }
    }

    /** Exact standalone R2 compatibility, through the same owned local-validation path. */
    internal suspend fun validateStandaloneAssetUse(id: String, bindingId: ExecutionBindingId): AssetUse {
        val explicitRetained = repository.physicalDownload(id)?.job?.status == DownloadStatus.FAILED
        var retainedRun: DownloadPhysicalRun? = null
        val result = withMaintenance(id, PhysicalDownloadMaintenanceKind.VALIDATE,
            explicitLocalRevalidation = explicitRetained, requestedBindingId = bindingId) { run ->
            val claim = run.current.claims.singleOrNull { it.owner == DownloadClaimOwner.Standalone(id) ||
                it.owner == DownloadClaimOwner.Schema2Standalone(id) }
                ?: fail(FailureCode.INVALID_STATE, "download_standalone_claim_missing")
            if (claim.intent == DownloadBatchIntent.CANCELLED && !claim.retainCompletedAsset)
                fail(FailureCode.INVALID_STATE, "download_asset_generation_changed")
            val view = host.acquisitionView()
            val requested = view.acquisition.bindings[bindingId]
            if (requested == null || requested.subjectId != run.fence.producer.subjectId ||
                requested.recordingId == null || requested.recordingId != run.fence.producer.recordingId)
                fail(FailureCode.INVALID_STATE, "download_binding_identity")
            val asset = run.current.asset ?: fail(FailureCode.NOT_FOUND, "download_asset_missing")
            requireMaterialization(run.current, asset)
            val requestId = (view.acquisition.receipts[requested.firstReceiptId] as? FirstResultReceipt.Observed)?.observation?.requestId
            val attempt = materializationEvidence.admit(id, asset, bindingId, requestId)
            validate(run, asset.validationInput(), attempt)
            ensureRun(run, network = false)
            if (run.current.job.status == DownloadStatus.FAILED) {
                // The exact-fenced finish below atomically restores COMPLETE, clears the local
                // failure/backoff and grants ONLY this requested binding's use.
                retainedRun = run
                run.retainedValidation = RetainedValidationGrant(claim, bindingId, asset)
                null
            } else {
                val committed = repository.commitStandaloneMaterializationUse(CommitStandaloneMaterializationUse(id,
                    bindingId, claim.id, claim.generation, run.fence.ownershipGeneration, asset,
                    dependencies.compatibilityPolicyVersion, clock.epochMs()))
                if (committed !is PhysicalDownloadCommit.Committed)
                    fail(FailureCode.INVALID_STATE, "download_asset_use_stale")
                if (committed.value.requestedBindingId != bindingId || committed.value.materializationAssetId != asset.id ||
                    committed.value.compatibilityPolicyVersion != dependencies.compatibilityPolicyVersion)
                    fail(FailureCode.RECOVERY_REQUIRED, "download_asset_use_mismatch")
                materializationEvidence.recordPhysicalSuccess(id, asset)
                committed.value
            }
        }
        retainedRun?.let {
            return it.recoveredValidationUse
                ?: fail(FailureCode.INVALID_STATE, "download_retained_validation_not_committed")
        }
        return result ?: fail(FailureCode.BACKGROUND_RESTRICTED, "download_asset_check_not_admitted")
    }

    /**
     * Explicit local Validate for a retained FAILED physical job, keyed by the surfaced physical
     * job and the exact requested binding. This never performs a network Retry/removal and never
     * grants a sibling binding's use. The batch adapter path is reused so its lineage/claim
     * checks stay authoritative; [validateStandaloneAssetUse] owns the standalone equivalent.
     */
    internal suspend fun validateRetainedAssetUse(id: String, bindingId: ExecutionBindingId): AssetUse {
        val before = repository.physicalDownload(id) ?: fail(FailureCode.NOT_FOUND, "download_job_missing")
        val asset = before.asset ?: fail(FailureCode.NOT_FOUND, "download_asset_missing")
        if (before.job.status != DownloadStatus.FAILED || before.finalization != null || before.job.cleanupPending ||
            before.job.assetId != asset.id) fail(FailureCode.INVALID_STATE, "download_retained_validation_not_available")
        val matching = before.claims.filter { claim ->
            claim.requestedBindingId == bindingId &&
                (claim.intent != DownloadBatchIntent.CANCELLED || claim.retainCompletedAsset)
        }
        if (matching.size > 1)
            fail(FailureCode.RECOVERY_REQUIRED, "download_retained_validation_member_required")
        val claim = matching.singleOrNull() ?: before.claims.singleOrNull { claim ->
            (claim.owner is DownloadClaimOwner.Standalone || claim.owner is DownloadClaimOwner.Schema2Standalone) &&
                (claim.intent != DownloadBatchIntent.CANCELLED || claim.retainCompletedAsset)
        } ?: fail(FailureCode.INVALID_STATE, "download_retained_validation_claim_missing")
        return when (val owner = claim.owner) {
            is DownloadClaimOwner.Standalone, is DownloadClaimOwner.Schema2Standalone ->
                validateStandaloneAssetUse(id, bindingId)
            is DownloadClaimOwner.BatchMember -> {
                val state = repository.state.value
                val batch = state.batches[owner.batchId]
                    ?: fail(FailureCode.INVALID_STATE, "download_retained_validation_batch_missing")
                val member = batch.members.singleOrNull { it.position == owner.position }
                    ?: fail(FailureCode.INVALID_STATE, "download_retained_validation_member_missing")
                val required = member.requiredBindingId
                    ?: fail(FailureCode.INVALID_STATE, "download_retained_validation_binding_missing")
                val key = AcquisitionDownloadMemberKey(batch.id, member.position, batch.intentRevision,
                    member.occurrenceId, member.requestId, required)
                when (val result = validateAssetUse(ValidateAcquisitionDownloadAsset(key, claim, asset.id))) {
                    is AcquisitionDownloadAssetCheck.Validated -> result.validation.use
                    is AcquisitionDownloadAssetCheck.Unavailable -> throw VeyraException(result.failure)
                }
            }
        }
    }

    /**
     * Exact repeated-origin entry point: the caller supplies the batch member identity rather
     * than allowing the runtime to choose among two claims that request the same binding.
     */
    internal suspend fun validateRetainedMemberAssetUse(batchId: DownloadBatchId, memberPosition: Int,
        expectedIntentRevision: Long, bindingId: ExecutionBindingId): AssetUse {
        val state = repository.state.value
        val batch = state.batches[batchId] ?: fail(FailureCode.NOT_FOUND, "download_retained_validation_batch_missing")
        if (batch.intentRevision != expectedIntentRevision)
            fail(FailureCode.INVALID_STATE, "download_retained_validation_member_stale")
        val member = batch.members.singleOrNull { it.position == memberPosition }
            ?: fail(FailureCode.INVALID_STATE, "download_retained_validation_member_missing")
        val required = member.requiredBindingId ?: fail(FailureCode.INVALID_STATE, "download_retained_validation_binding_missing")
        if (required != bindingId) fail(FailureCode.INVALID_STATE, "download_retained_validation_binding_mismatch")
        val claim = member.claimId?.let(state.claims::get)
            ?: fail(FailureCode.INVALID_STATE, "download_retained_validation_claim_missing")
        val before = repository.physicalDownload(claim.physicalJobId)
            ?: fail(FailureCode.NOT_FOUND, "download_job_missing")
        val asset = before.asset ?: fail(FailureCode.NOT_FOUND, "download_asset_missing")
        if (before.job.status != DownloadStatus.FAILED || before.finalization != null || before.job.cleanupPending ||
            before.job.assetId != asset.id) fail(FailureCode.INVALID_STATE, "download_retained_validation_not_available")
        if (claim.physicalJobId != before.job.id || claim.owner != DownloadClaimOwner.BatchMember(batch.id, memberPosition) ||
            claim.requestedBindingId != bindingId || claim.intent == DownloadBatchIntent.CANCELLED && !claim.retainCompletedAsset)
            fail(FailureCode.INVALID_STATE, "download_retained_validation_claim_changed")
        val key = AcquisitionDownloadMemberKey(batch.id, memberPosition, batch.intentRevision,
            member.occurrenceId, member.requestId, bindingId)
        return when (val result = validateAssetUse(ValidateAcquisitionDownloadAsset(key, claim, asset.id))) {
            is AcquisitionDownloadAssetCheck.Validated -> result.validation.use
            is AcquisitionDownloadAssetCheck.Unavailable -> throw VeyraException(result.failure)
        }
    }

    /** Metadata precondition only: never a replacement for the actual reader/native barrier. */
    internal fun requireClearedSchema2(join: PhysicalDownloadJoin) {
        val job = join.job
        val producer = job.producer as? PhysicalDownloadProducer.Schema2
            ?: fail(FailureCode.INVALID_STATE, "download_unknown_legacy_required")
        if (producer.recordingId != null || join.claims.none { it.owner == DownloadClaimOwner.Schema2Standalone(job.id) } ||
            join.claims.any { it.intent != DownloadBatchIntent.CANCELLED || it.retainCompletedAsset } ||
            job.stagingPath != null || job.assetId != null || join.asset != null || join.finalization != null ||
            job.leaseOwner != null || job.leaseExpiresEpochMs != null || job.activeAttemptId != null ||
            join.activeAttempt != null || job.cleanupPending || job.maintenanceKind != null || join.cleanupReservation != null ||
            job.bytesDownloaded != 0L || job.totalBytes != null || join.attempts.any { it.finishedEpochMs == null })
            fail(FailureCode.RECOVERY_REQUIRED, "download_legacy_cleanup_required")
    }

    /**
     * Called only after the required authoritative reader-drain callback. All four EXISTING
     * native slots, the EXISTING admission lock and EVERY retained generation path are held
     * across the one-shot canonical adoption command. Contention is busy; no other job is
     * cancelled to obtain this barrier. A failed close remains in the existing owner registry.
     */
    internal suspend fun adoptAfterReaderDrain(expected: PhysicalDownloadJoin, requestId: AcquisitionRequestId,
        bindingId: ExecutionBindingId, requireIntentCurrent: () -> Unit): StandalonePhysicalDownloadAdmission {
        val operation = currentCoroutineContext().job
        try {
            return host.commands.withLock {
                currentCoroutineContext().ensureActive()
                dependencies.requireOwnerAdmission()
                host.activeLock.withLock {
                    val state = host.state()
                    ensureHost(state.generation, network = false)
                    if (expected.job.id in host.active || !epochs.canAdmit(expected.job.id))
                        fail(FailureCode.BACKGROUND_RESTRICTED, "download_adoption_busy")
                    val before = repository.physicalDownload(expected.job.id)
                        ?: fail(FailureCode.NOT_FOUND, "download_job_missing")
                    if (!samePhysicalGraph(before, expected))
                        fail(FailureCode.INVALID_STATE, "download_adoption_owner_changed")
                    requireClearedSchema2(before)
                    check(before.job.ownershipGeneration < Long.MAX_VALUE)
                    val producer = before.job.producer as PhysicalDownloadProducer.Schema2
                    val claim = before.claims.single { it.owner == DownloadClaimOwner.Schema2Standalone(before.job.id) }
                    val epoch = epochs.begin(before.job.id, producer, before.job.ownershipGeneration + 1, null, null, operation)
                    val partial = DownloadLeaseCleanupScope(host.cleanupRegistry)
                    var barrier: DownloadFileLease? = null
                    var durable: AcquisitionDownloadDurableSettlement? = null
                    try {
                        val paths = stagingPaths(before)
                        barrier = files.tryPhysicalAdoptionBarrier(paths, partial)
                            ?: fail(FailureCode.BACKGROUND_RESTRICTED, "download_adoption_busy")
                        val current = repository.physicalDownload(before.job.id)
                            ?: fail(FailureCode.NOT_FOUND, "download_job_missing")
                        if (!samePhysicalGraph(current, before))
                            fail(FailureCode.INVALID_STATE, "download_adoption_owner_changed")
                        // A leftover journal/empty generation directory is NOT zero staging.
                        if (paths.any { !files.stagingGenerationAbsent(it) })
                            fail(FailureCode.RECOVERY_REQUIRED, "download_legacy_staging_remains")
                        operation.ensureActive()
                        ensureHost(state.generation, network = false)
                        dependencies.requireOwnerAdmission()
                        requireIntentCurrent()
                        val held = MutableStateFlow(true)
                        val receipt = PhysicalDownloadDrainReceipt(transactionOwner, before.job.id, producer,
                            before.job.ownershipGeneration, before.claims) { held.value }
                        try {
                            // Acceptance is now owned; root cancellation after this point must
                            // reconcile the newly accepted owner, not silently discard its ID.
                            withContext(NonCancellable) {
                                when (val result = repository.adoptSchema2PhysicalDownload(AdoptSchema2PhysicalDownload(
                                    before.job.id, requestId, bindingId, producer, before.job.ownershipGeneration,
                                    claim.id, claim.generation, claim.ownerIntentRevision, receipt, clock.epochMs()))) {
                                    is PhysicalDownloadCommit.Committed -> {
                                        durable = AcquisitionDownloadDurableSettlement.Confirmed(result.libraryRevision)
                                        result.value
                                    }
                                    PhysicalDownloadCommit.Stale -> {
                                        durable = AcquisitionDownloadDurableSettlement.Stale
                                        fail(FailureCode.INVALID_STATE, "download_adoption_owner_changed")
                                    }
                                }
                            }
                        } finally {
                            receipt.invalidate() // ALWAYS before releasing ANY native/path barrier.
                            held.value = false
                        }
                    } finally {
                        withContext(NonCancellable) {
                            retainCleanup(epoch, partial, listOf({ barrier?.close(); Unit }), report = false) { durable }
                            epochs.finishAdmission(epoch)
                        }
                    }
                }
            }
        } finally { withContext(NonCancellable) { host.publishCleanupFailure() } }
    }

    private fun samePhysicalGraph(left: PhysicalDownloadJoin, right: PhysicalDownloadJoin): Boolean =
        left.copy(libraryRevision = 0) == right.copy(libraryRevision = 0)

    /** Called only by the existing serialized maintenance pass; no new background loop. */
    internal suspend fun reconcilePass() {
        var checks = 0
        for (id in candidates()) {
            currentCoroutineContext().ensureActive()
            val state = host.state()
            if (!state.started || state.closed || state.suspended || state.drainsInFlight != 0 || state.failure != null) return
            val joined = repository.physicalDownload(id) ?: continue
            val relevant = joined.scheduleRecord()
            try {
                when {
                    DownloadPhysicalClaimRules.needsIntentReconciliation(relevant) -> reconcileIntent(joined)
                    joined.job.cleanupPending -> withMaintenance(id, PhysicalDownloadMaintenanceKind.CLEANUP) { clean(it) }
                    joined.finalization != null -> withMaintenance(id, PhysicalDownloadMaintenanceKind.RECOVER_FINALIZATION) { recover(it) }
                    state.checksActivated && joined.job.status == DownloadStatus.COMPLETE &&
                        joined.asset != null && materializationChecks.value.assets[id] != joined.asset && checks < 2 -> {
                        checks++
                        withMaintenance(id, PhysicalDownloadMaintenanceKind.VALIDATE) { run ->
                            val asset = requireNotNull(run.current.asset)
                            requireMaterialization(run.current, asset)
                            val attempt = materializationEvidence.admit(id, asset, requireNotNull(asset.producer.bindingId),
                                run.current.producerContext?.request?.id)
                            validate(run, asset.validationInput(), attempt)
                            ensureRun(run, network = false)
                            val current = repository.physicalDownload(id)
                            if (current?.job?.ownershipGeneration != run.fence.ownershipGeneration || current.asset != asset ||
                                current.job.cleanupPending) throw CancellationException("Completed materialization changed")
                            materializationEvidence.recordPhysicalSuccess(id, asset)
                        }
                    }
                    DownloadPhysicalClaimRules.expiredRecoveryIntent(relevant) && !joined.job.livePhysicalLease(clock.epochMs()) ->
                        reconcileIntent(joined)
                }
            } catch (error: CancellationException) { currentCoroutineContext().ensureActive() }
            catch (error: Exception) { host.publishFailure(classify(error)) }
        }
    }

    private suspend fun reconcileIntent(before: PhysicalDownloadJoin) {
        val relevant = before.scheduleRecord()
        if ((physicalClaimDeferredUntil(relevant, DownloadClaimKind.EXPIRED_RECOVERY, claimDeferrals.value) ?: 0) > clock.epochMs()) return
        try {
            val result = repository.reconcilePhysicalDownloadIntent(ReconcilePhysicalDownloadIntent(before.job.id,
                before.job.producer, before.job.ownershipGeneration, before.claims, clock.epochMs()))
            if (result !is PhysicalDownloadCommit.Committed) { deferClaim(before.job.id, DownloadClaimKind.EXPIRED_RECOVERY); return }
            if (result.value.scheduleRecord() == relevant) deferClaim(before.job.id, DownloadClaimKind.EXPIRED_RECOVERY)
            else clearClaimDeferrals(before.job.id, DownloadClaimKind.EXPIRED_RECOVERY)
            if (summarizeDownloadClaims(result.value.job.id, result.value.claims).demand in STOP_DEMANDS) {
                val epoch = host.activeLock.withLock { epochs.current(before.job.id)?.takeIf {
                    it.producer == before.job.producer && it.physicalOwnershipGeneration == before.job.ownershipGeneration
                } }
                // Real physical join, independent of the scheduler and outside every admission lock.
                // A durable intent result alone neither consumes old owners nor retries failed closes.
                epoch?.let { epochs.drain(it) }
            }
            host.changed()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { deferClaim(before.job.id, DownloadClaimKind.EXPIRED_RECOVERY); throw error }
    }

    private suspend fun <T> withMaintenance(id: String, kind: PhysicalDownloadMaintenanceKind,
        expectedOwner: PhysicalDownloadJoin? = null,
        explicitLocalRevalidation: Boolean = false,
        requestedBindingId: ExecutionBindingId? = null,
        action: suspend (DownloadPhysicalRun) -> T): T? {
        var cleanup: DownloadPhysicalCleanup? = null
        val result = coroutineScope {
            val operation = currentCoroutineContext().job
            var run: DownloadPhysicalRun? = null
            var allowance: DownloadExecutionLease? = null
            var heartbeat: Job? = null
            val failure = MutableStateFlow<AppFailure?>(null)
            try {
                val admitted = host.commands.withLock {
                    host.activeLock.withLock admission@ {
                        if (id in host.active) return@admission null
                        // Preserve the accepted CAS AND actual Job registration across dispatch
                        // cancellation. The outer finally owns run before this block can return.
                        withContext(NonCancellable) {
                            val claimed = claimMaintenance(id, kind, operation, expectedOwner,
                                explicitLocalRevalidation, requestedBindingId) ?: return@withContext null
                            run = claimed
                            host.active[id] = operation
                            epochs.registerJob(claimed.epoch, operation)
                            epochs.finishAdmission(claimed.epoch)
                            runs.update { it + (id to claimed) }
                            host.changed()
                            claimed
                        }
                    }
                } ?: return@coroutineScope null
                // The claim COMMIT and owner registration above are noncancellable. A caller
                // cancellation that arrived after that commit must release this exact lease through
                // the finally path, not acquire a new host allowance for orphaned work.
                currentCoroutineContext().ensureActive()
                heartbeat = launch {
                    while (isActive && !admitted.actionFinished.value) {
                        delay(heartbeatMs)
                        if (admitted.actionFinished.value) break
                        try { checkpoint(admitted) }
                        catch (error: Exception) {
                            if (error !is CancellationException) {
                                failure.update { it ?: classify(error) }
                                host.publishFailure(checkNotNull(failure.value))
                            }
                            operation.cancel(CancellationException("Maintenance ownership revoked"))
                        }
                    }
                }
                withContext(NonCancellable) {
                    // The outer ensureActive above cannot be atomic with a resource acquire. Check
                    // the exact admission job again immediately before the gate, and if the gate
                    // returns a lease after cancellation, retain it in `allowance` so the finally
                    // path closes it. This is the bounded acquire/close contract; it never leaves an
                    // orphan allowance, while a gate that acquires inside its own critical section
                    // cannot be preempted by an external cancellation callback.
                    if (!operation.isActive)
                        throw CancellationException("Download maintenance cancelled before allowance admission")
                    allowance = gate.acquire { expire(admitted) }
                    if (!operation.isActive)
                        throw CancellationException("Download maintenance cancelled during allowance admission")
                }
                ensureRun(admitted, network = false)
                checkpoint(admitted)
                action(admitted).also { admitted.actionFinished.value = true }
            } catch (error: CancellationException) {
                // An allowance/host suspension is an actual restriction, not a silent stop.
                // Preserve a truthful diagnostic through the FINAL atomic maintenance command.
                if (failure.value == null && run?.hostFinishStatus?.value == DownloadStatus.NEEDS_FOREGROUND)
                    failure.value = AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_executor_needs_foreground")
                throw error
            }
            catch (error: Exception) {
                failure.update { it ?: classify(error) }
                if (failure.value?.code == FailureCode.BACKGROUND_RESTRICTED) run?.let { expire(it) }
                // A real cancellation may arrive while the action is classifying a native
                // negative (or while cleanup masks it). The cancelled scope is authoritative:
                // never return a fabricated Unavailable success value for that invocation.
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("Download maintenance cancelled").also { it.addSuppressed(error) }
                }
                throw error
            }
            finally {
                withContext(NonCancellable) {
                    heartbeat?.cancelAndJoin()
                    run?.let { current ->
                        cleanup = retainCleanup(current.epoch, DownloadLeaseCleanupScope(host.cleanupRegistry), listOf(
                            { current.validationReaders.requireReleased() },
                            { allowance?.close(); current.allowanceCallbacksOpen.value = false },
                            { current.pathLease.close() },
                        ), acceptedDurableOwner = true, expectedClaims = current.current.claims, maintenanceKind = kind) { finishMaintenance(current, failure.value) }
                        epochs.finishAdmission(current.epoch)
                        if (failure.value != null || !current.actionFinished.value) deferClaim(id, kind.physicalClaimKind())
                        else clearClaimDeferrals(id, kind.physicalClaimKind())
                        host.activeLock.withLock {
                            if (host.active[id] === operation) host.active.remove(id)
                            forgetRun(current)
                            host.changed()
                        }
                        host.signalWork()
                    }
                }
            }
        }
        if (cleanup?.failed?.value == true) fail(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_failed_restart_required")
        return result
    }

    /**
     * Called ONLY after the accepted run's native/file closes, never from admission rollback
     * (which already holds commands). A pause/remove can change claims while validation is
     * suspended. Rejoin those claims under the original owner fence rather than submit the
     * pre-validation snapshot or settle a newer generation. Room still performs the full CAS.
     */
    private suspend fun finishMaintenance(run: DownloadPhysicalRun, failure: AppFailure?): AcquisitionDownloadDurableSettlement {
        run.published?.let { return AcquisitionDownloadDurableSettlement.Confirmed(it.libraryRevision) }
        val kind = requireNotNull(run.kind)
        val rawPhysicalFailure = failure?.takeUnless {
            kind == PhysicalDownloadMaintenanceKind.VALIDATE && it.code == FailureCode.INVALID_STATE
        }
        return host.commands.withLock {
            val current = repository.physicalDownload(run.fence.downloadId)
            if (current == null || !sameMaintenanceFence(current, run))
                return@withLock AcquisitionDownloadDurableSettlement.Stale
            val priorStatus = run.current.job.status
            // A late host-failure finalizer from an older foreground generation must not suspend
            // the already-resumed host. Keep the actual local result (if any) instead.
            val staleHostFailure = kind == PhysicalDownloadMaintenanceKind.VALIDATE &&
                failure?.code == FailureCode.BACKGROUND_RESTRICTED &&
                host.state().generation != run.hostGeneration && !host.state().suspended
            val physicalFailure = if (staleHostFailure) null else rawPhysicalFailure
            val hostBlocked = run.hostFinishStatus.value == DownloadStatus.NEEDS_FOREGROUND &&
                host.state().generation == run.hostGeneration ||
                host.state().suspended && !host.state().closed ||
                failure?.code == FailureCode.BACKGROUND_RESTRICTED && !staleHostFailure
            // A host-only restriction must not overwrite an already-retained local failure with
            // NEEDS_FOREGROUND (which used to reconcile to a dead QUEUED state). An actual
            // hash/native negative remains the durable failure.
            val validationHostHold = kind == PhysicalDownloadMaintenanceKind.VALIDATE && hostBlocked &&
                (physicalFailure == null || physicalFailure.code == FailureCode.BACKGROUND_RESTRICTED)
            val statusOverride = when {
                validationHostHold && priorStatus == DownloadStatus.COMPLETE -> DownloadStatus.NEEDS_FOREGROUND
                validationHostHold && priorStatus == DownloadStatus.FAILED -> DownloadStatus.NEEDS_FOREGROUND
                run.hostFinishStatus.value == DownloadStatus.NEEDS_FOREGROUND &&
                    host.state().generation == run.hostGeneration -> DownloadStatus.NEEDS_FOREGROUND
                host.state().suspended && !host.state().closed ||
                    failure?.code == FailureCode.BACKGROUND_RESTRICTED && !staleHostFailure -> DownloadStatus.NEEDS_FOREGROUND
                // Rejection of this exact R2/claim does not corrupt the shared R1 file.
                kind == PhysicalDownloadMaintenanceKind.VALIDATE && physicalFailure != null -> DownloadStatus.FAILED
                else -> null
            }
            val finishFailure = when {
                // The marker lets foreground resume distinguish a previously COMPLETE file whose
                // check was denied from a retained local failure that must stay stopped.
                validationHostHold && priorStatus == DownloadStatus.COMPLETE ->
                    AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_completed_check_needs_foreground")
                validationHostHold && priorStatus == DownloadStatus.FAILED ->
                    run.current.job.failure?.takeUnless { it.code == FailureCode.BACKGROUND_RESTRICTED }
                        ?: AppFailure(FailureCode.RECOVERY_REQUIRED, "download_local_validation_required")
                else -> physicalFailure
            }
            val nextRetry = when {
                validationHostHold && priorStatus == DownloadStatus.FAILED -> run.current.job.nextRetryEpochMs
                else -> physicalFailure?.let { deadline(LOCAL_RECHECK_MS) }
            }
            val grant = run.retainedValidation
            val grantValid = grant != null && failure == null && kind == PhysicalDownloadMaintenanceKind.VALIDATE &&
                statusOverride == null && current.job.status == DownloadStatus.FAILED && !current.job.cleanupPending &&
                current.finalization == null && current.asset == grant.asset && current.job.assetId == grant.asset.id &&
                current.claims.singleOrNull { it.id == grant.expectedClaim.id } == grant.expectedClaim &&
                (grant.expectedClaim.intent != DownloadBatchIntent.CANCELLED || grant.expectedClaim.retainCompletedAsset)
            if (grantValid) {
                checkNotNull(grant)
                val committed = repository.finishPhysicalDownloadMaintenance(FinishPhysicalDownloadMaintenance(run.fence,
                    kind, current.claims, run.cleanupCompleted, null, clock.epochMs(),
                    retainedValidation = RetainedValidationCompletion(grant.expectedClaim, grant.requestedBindingId,
                        grant.asset, dependencies.compatibilityPolicyVersion)))
                if (committed !is PhysicalDownloadCommit.Committed)
                    return@withLock AcquisitionDownloadDurableSettlement.Stale
                val use = repository.state.value.assetUses.singleOrNull {
                    it.requestedBindingId == grant.requestedBindingId && it.materializationAssetId == grant.asset.id
                } ?: fail(FailureCode.RECOVERY_REQUIRED, "download_retained_validation_use_missing")
                run.current = committed.value
                materializationEvidence.recordPhysicalSuccess(run.fence.downloadId, grant.asset)
                run.recoveredValidationUse = use
                run.recoveredValidationRevision = committed.libraryRevision
                grant.member?.let { member ->
                    run.recoveredValidationFence = host.activeLock.withLock {
                        issueFence(member, grant.expectedClaim, run.current).first
                    }
                }
                return@withLock AcquisitionDownloadDurableSettlement.Confirmed(committed.libraryRevision)
            }
            settle { repository.finishPhysicalDownloadMaintenance(FinishPhysicalDownloadMaintenance(run.fence,
                kind, current.claims, run.cleanupCompleted, finishFailure, clock.epochMs(),
                status = statusOverride, nextRetryEpochMs = nextRetry)) }
        }
    }

    private fun sameMaintenanceFence(current: PhysicalDownloadJoin, run: DownloadPhysicalRun): Boolean {
        val fence = run.fence
        val job = current.job
        if (job.id != fence.downloadId || job.producer != fence.producer ||
            job.ownershipGeneration != fence.ownershipGeneration || job.leaseOwner != fence.leaseOwner ||
            job.maintenanceKind != run.kind) return false
        val attemptId = if (run.kind == PhysicalDownloadMaintenanceKind.RECOVER_FINALIZATION)
            current.finalization?.attemptId else job.activeAttemptId
        if (attemptId != fence.attemptId) return false
        return attemptId == null || current.attempts.singleOrNull { it.id == attemptId }?.let {
            it.number == fence.attemptNumber && it.downloadId == job.id && it.producer == job.producer
        } == true
    }

    /** Existing commands+active locks are held until this claim's actual task is registered. */
    private suspend fun claimMaintenance(id: String, kind: PhysicalDownloadMaintenanceKind, admissionOwner: Job,
        expectedOwner: PhysicalDownloadJoin?, explicitLocalRevalidation: Boolean = false,
        requestedBindingId: ExecutionBindingId? = null): DownloadPhysicalRun? {
        val state = host.state()
        if (!state.started || state.closed || state.suspended || state.drainsInFlight != 0 || state.failure != null ||
            kind == PhysicalDownloadMaintenanceKind.VALIDATE && !state.checksActivated || !epochs.canAdmit(id)) return null
        val before = repository.physicalDownload(id) ?: return null
        if (expectedOwner != null && !sameClaimOwner(before, expectedOwner)) return null
        if (explicitLocalRevalidation && (kind != PhysicalDownloadMaintenanceKind.VALIDATE || requestedBindingId == null)) return null
        val relevant = before.scheduleRecord()
        if (kind != PhysicalDownloadMaintenanceKind.CLEANUP && DownloadPhysicalClaimRules.backgroundBlocked(before.job) ||
            before.job.livePhysicalLease(clock.epochMs()) ||
            // An accepted removal intent owns cleanup regardless of a local validation recheck
            // deadline; waiting for that deadline would leave the removal forever pending.
            kind != PhysicalDownloadMaintenanceKind.CLEANUP &&
                !explicitLocalRevalidation && (before.job.nextRetryEpochMs ?: 0) > clock.epochMs() ||
            !explicitLocalRevalidation &&
                (physicalClaimDeferredUntil(relevant, kind.physicalClaimKind(), claimDeferrals.value) ?: 0) > clock.epochMs()) {
            return null
        }
        val intent = before.finalization
        // Explicit validate may recheck an already-checked exact asset for a new R2; it never
        // bypasses host/lease/coverage/claim/cleanup gates, only the local recheck deadline.
        val eligible = DownloadPhysicalClaimRules.maintenanceIntent(relevant, kind, checked = false,
            explicitRetainedValidation = explicitLocalRevalidation)
        if (!eligible) return null
        if (kind != PhysicalDownloadMaintenanceKind.CLEANUP) requireKnownProducer(before)
        dependencies.requireOwnerAdmission()
        // Migrated finalization can truthfully have no original attempt. Its null is evidence,
        // not permission to relabel it as whichever attempt happens to be active now.
        val attemptId = if (kind == PhysicalDownloadMaintenanceKind.RECOVER_FINALIZATION)
            intent?.attemptId else before.job.activeAttemptId
        val attempt = attemptId?.let { value -> before.attempts.singleOrNull { it.id == value }
            ?: fail(FailureCode.RECOVERY_REQUIRED, "download_attempt_join_missing") }
        check(before.job.ownershipGeneration < Long.MAX_VALUE)
        val token = newEntityId("download-maintenance")
        val proposedFence = PhysicalDownloadFence(id, before.job.producer, before.job.ownershipGeneration + 1,
            token, attemptId, attempt?.number)
        val epoch = epochs.begin(id, before.job.producer, proposedFence.ownershipGeneration, attemptId, attempt?.number, admissionOwner)
        val partial = DownloadLeaseCleanupScope(host.cleanupRegistry)
        var lease: DownloadFileLease? = null
        var handedOff = false
        var acceptedDurableOwner = false
        // A Committed claim may reveal a newer exact fence/claim set than the pre-read snapshot.
        // Rollback must settle THAT committed identity; using the pre-claim identity leaves the
        // canonical maintenance lease live forever and blocks every later cleanup pass.
        var rollbackFence = proposedFence
        var rollbackClaims = before.claims
        try {
            val paths = maintenancePaths(before, kind)
            lease = files.tryLeasePaths(paths, partial) ?: return null
            ensureHost(state.generation, network = false)
            val claimed = withContext(NonCancellable) {
                repository.claimPhysicalDownloadMaintenance(ClaimPhysicalDownloadMaintenance(id, before.job.producer,
                    before.job.ownershipGeneration, kind, token, clock.epochMs(), leaseDurationMs,
                    explicitLocalRevalidation = explicitLocalRevalidation, requestedBindingId = requestedBindingId))
            }
            if (claimed !is PhysicalDownloadCommit.Committed) return null
            acceptedDurableOwner = true
            rollbackFence = claimed.value.fence
            rollbackClaims = claimed.value.physical.claims
            if (claimed.value.fence != proposedFence || maintenancePaths(claimed.value.physical, kind) != paths ||
                expectedOwner != null && claimed.value.physical.claims.toSet() != expectedOwner.claims.toSet()) {
                // A concurrent exact-generation path/claim change is a retryable claim race, not a
                // host failure. The finally below settles the ACTUAL returned fence/claims so the
                // committed maintenance lease cannot be stranded; a later pass may clean again.
                if (kind == PhysicalDownloadMaintenanceKind.CLEANUP) return null
                fail(FailureCode.RECOVERY_REQUIRED, "download_maintenance_join_changed")
            }
            if (kind == PhysicalDownloadMaintenanceKind.CLEANUP) {
                val reservation = claimed.value.physical.cleanupReservation
                    ?: fail(FailureCode.RECOVERY_REQUIRED, "download_cleanup_not_reserved")
                if (reservation.fence != proposedFence || reservation.stagingPaths != stagingPaths(claimed.value.physical))
                    fail(FailureCode.RECOVERY_REQUIRED, "download_cleanup_reservation_changed")
            }
            return DownloadPhysicalRun(epoch, claimed.value.fence, claimed.value.physical, state.generation,
                kind, requireNotNull(lease), null).also { handedOff = true }
        } finally {
            if (!handedOff) withContext(NonCancellable) {
                rollback(epoch, partial, listOfNotNull(lease), acceptedDurableOwner) {
                    settle { repository.finishPhysicalDownloadMaintenance(FinishPhysicalDownloadMaintenance(rollbackFence,
                        kind, rollbackClaims, false, null, clock.epochMs())) }
                }
                epochs.finishAdmission(epoch)
                // A committed cleanup claim/rollback already made durable progress and released
                // its exact lease. Re-deferring it behind a virtual/real recheck deadline can
                // leave an accepted removal pending forever; let the scheduler retry promptly.
                if (kind == PhysicalDownloadMaintenanceKind.CLEANUP && acceptedDurableOwner)
                    clearClaimDeferrals(id, kind.physicalClaimKind())
                else deferClaim(id, kind.physicalClaimKind())
            }
        }
    }

    private suspend fun clean(run: DownloadPhysicalRun) {
        val reservation = run.current.cleanupReservation ?: fail(FailureCode.RECOVERY_REQUIRED, "download_cleanup_not_reserved")
        if (reservation.fence != run.fence || !run.current.job.cleanupPending ||
            run.current.job.maintenanceKind != PhysicalDownloadMaintenanceKind.CLEANUP)
            fail(FailureCode.RECOVERY_REQUIRED, "download_cleanup_reservation_changed")
        if (reservation.removeCompletedAsset) reservation.asset?.let { dependencies.beforePhysicalDelete(it) }
        ensureRun(run, network = false)
        val current = repository.physicalDownload(run.fence.downloadId)
        if (current?.cleanupReservation != reservation || current.job.ownershipGeneration != run.fence.ownershipGeneration)
            throw CancellationException("Cleanup reservation changed")
        // The canonical reservation irreversibly fences new sharing/retention and every path
        // mutation, even after lease expiry. OS locks cover ALL historical stages; the callback
        // above awaited actual readers. No post-deletion CAS is used as deletion authority.
        for (path in reservation.stagingPaths) {
            ensureRun(run, network = false)
            files.resetStaging(path)
        }
        val finals = setOfNotNull(reservation.finalization?.finalPath,
            reservation.asset?.uri?.takeIf { reservation.removeCompletedAsset })
        for (path in finals) {
            if (!reservation.removeCompletedAsset && path == reservation.asset?.uri)
                fail(FailureCode.RECOVERY_REQUIRED, "download_retained_asset_path_conflict")
            ensureRun(run, network = false)
            files.deleteFinal(path)
        }
        run.cleanupCompleted = true
        if (reservation.removeCompletedAsset) reservation.asset?.let { asset ->
            materializationEvidence.forgetDeleted(run.fence.downloadId, asset)
        }
    }

    private suspend fun recover(run: DownloadPhysicalRun) {
        val intent = run.current.finalization ?: fail(FailureCode.RECOVERY_REQUIRED, "download_finalization_missing")
        if (intent.producer != run.fence.producer) fail(FailureCode.RECOVERY_REQUIRED, "download_producer_changed")
        if (intent.representationId.isNullOrBlank() || intent.representationId != run.current.job.representationId ||
            intent.validatorGeneration != run.current.job.validatorGeneration)
            fail(FailureCode.CORRUPT_MEDIA, "download_finalization_representation_changed")
        requireLocalSupport(intent.mimeType, intent.codecs)
        val final = files.ownedPath(intent.finalPath)
        val published = storageOperation { files.fileSystem.exists(final) }
        val chosen = if (published) intent.finalPath else intent.stagingPath
        val input = LocalMediaValidationInput(chosen, OfflineAssetKind.FILE, intent.sizeBytes, intent.mimeType,
            intent.codecs, intent.sha256, intent.representationId, intent.validatorGeneration)
        validate(run, input)
        ensureRun(run, network = false)
        if (!published) files.atomicPublish(intent.stagingPath, intent.finalPath)
        publish(run, intent) // Original materializer attempt/generation is never rewritten.
    }

    private fun stagingPaths(join: PhysicalDownloadJoin): Set<String> = setOfNotNull(join.job.stagingPath, join.finalization?.stagingPath) +
        join.attempts.mapNotNull { it.stagingPath }

    private fun maintenancePaths(join: PhysicalDownloadJoin, kind: PhysicalDownloadMaintenanceKind): Set<String> = when (kind) {
        PhysicalDownloadMaintenanceKind.CLEANUP -> stagingPaths(join) + setOfNotNull(join.finalization?.finalPath, join.asset?.uri)
        PhysicalDownloadMaintenanceKind.RECOVER_FINALIZATION -> setOfNotNull(join.finalization?.stagingPath, join.finalization?.finalPath, join.asset?.uri)
        PhysicalDownloadMaintenanceKind.VALIDATE -> setOfNotNull(join.asset?.uri)
    }

    /**
     * Read-only SAME-owner full admission after acquiring the path. The command's member fields
     * alone are not issuer authority. No source restore/selection or second transaction owner.
     */
    private suspend fun requireValidationMember(command: ValidateAcquisitionDownloadAsset,
        run: DownloadPhysicalRun, asset: MaterializationAsset) {
        val state = host.acquisitionView().acquisition
        val key = command.member
        val claim = command.expectedClaim
        val batch = state.batches[key.batchId]
        val member = batch?.members?.singleOrNull { it.position == key.memberPosition }
        val occurrence = state.occurrences[key.occurrenceId]
        val scope = batch?.scopeId?.let(state.scopes::get)
        val scopeMember = scope?.members?.singleOrNull { it.position == member?.scopeMemberPosition }
        val job = state.physicalDownloads[run.fence.downloadId]
        if (batch == null || batch.id != key.batchId || batch.intentRevision != key.intentRevision ||
            batch.intent != claim.intent || member == null || member.occurrenceId != key.occurrenceId ||
            member.requestId != key.requestId || member.requiredBindingId != key.requestedBindingId || member.claimId != claim.id ||
            state.claims[claim.id] != claim || occurrence == null || occurrence.id != key.occurrenceId ||
            occurrence.origin != AcquisitionOrigin.TYPED || occurrence.selectionState != OccurrenceSelectionState.BOUND ||
            occurrence.requestId != key.requestId || occurrence.bindingId != key.requestedBindingId ||
            occurrence.scopeMember != AdmissionScopeMemberRef(batch.scopeId, member.scopeMemberPosition) ||
            scope == null || scope.id != batch.scopeId || scope.controlsMode != occurrence.controlsMode ||
            scopeMember == null || scopeMember.disposition != AdmissionDisposition.INCLUDED ||
            scopeMember.requestId != key.requestId || scopeMember.snapshotMember != occurrence.snapshotMember ||
            job == null || job.producer != run.fence.producer || job.ownershipGeneration != run.fence.ownershipGeneration ||
            job.leaseOwner != run.fence.leaseOwner || job.maintenanceKind != PhysicalDownloadMaintenanceKind.VALIDATE ||
            job.cleanupPending || job.assetId != asset.id || state.materializations[asset.id] != asset ||
            state.materializationReceipts[asset.id] != asset.receipt()) {
            fail(FailureCode.INVALID_STATE, "download_asset_validation_member_changed")
        }
        val context = try { state.executionContext(key.requestedBindingId) }
            catch (_: Exception) { fail(FailureCode.INVALID_STATE, "download_asset_validation_member_changed") }
        val record = state.requests[key.requestId]
        if (record?.state != AcquisitionJobState.COMMITTED || record.request != context.request ||
            record.outcome != AcquisitionOutcome.Single(key.requestId, context.receipt.id, key.requestedBindingId) ||
            context.request.id != key.requestId || context.subject.id != occurrence.subjectId ||
            context.subject.id != asset.producer.subjectId || context.recording.id != asset.producer.recordingId) {
            fail(FailureCode.INVALID_STATE, "download_asset_validation_member_changed")
        }
        physicalMaterializationIdentityFailure(job, state.physicalFinalizations[job.id], asset,
            state.physicalAttempts.values.filter { it.downloadId == job.id })?.let { throw VeyraException(it) }
    }

    private fun requireMemberClaim(member: AcquisitionDownloadMemberKey, expected: DownloadClaim, join: PhysicalDownloadJoin) {
        if (join.claims.singleOrNull { it.id == expected.id } != expected || expected.physicalJobId != join.job.id ||
            expected.owner != DownloadClaimOwner.BatchMember(member.batchId, member.memberPosition) ||
            expected.requestedBindingId != member.requestedBindingId || expected.ownerIntentRevision != member.intentRevision)
            fail(FailureCode.INVALID_STATE, "download_member_claim_changed")
    }

    private suspend fun issueFence(member: AcquisitionDownloadMemberKey, claim: DownloadClaim, join: PhysicalDownloadJoin):
        Pair<AcquisitionDownloadFence, DownloadOwnershipEpochs.Epoch> {
        requireMemberClaim(member, claim, join)
        requireKnownProducer(join)
        val producer = join.job.producer
        val epoch = observePhysicalEpoch(join)
        return AcquisitionDownloadFence(this, member, claim, AcquisitionDownloadJobIdentity(join.job.id, producer.subjectId,
            requireNotNull(producer.bindingId), requireNotNull(producer.recordingId)), epoch.generation) to epoch
    }

    /** Caller holds existing activeLock; unknown legacy provenance is not a fabricated fence. */
    internal suspend fun observePhysicalEpoch(join: PhysicalDownloadJoin): DownloadOwnershipEpochs.Epoch {
        val producer = join.job.producer
        val local = epochs.current(join.job.id)
        val known = local != null && local.physicalOwnershipGeneration == join.job.ownershipGeneration && local.producer == producer
        return epochs.observe(join.job.id, producer, join.job.ownershipGeneration,
            join.activeAttempt?.id, join.activeAttempt?.number,
            externallyUnacknowledged = !known && (join.job.leaseOwner != null || join.job.activeAttemptId != null))
    }

    internal suspend fun cleanupStoppedStandalone(expected: PhysicalDownloadJoin): DownloadOwnershipEpochs.Epoch? =
        withMaintenance(expected.job.id, PhysicalDownloadMaintenanceKind.CLEANUP, expectedOwner = expected) { clean(it); it.epoch }

    private fun sameClaimOwner(left: PhysicalDownloadJoin, right: PhysicalDownloadJoin): Boolean =
        left.job.id == right.job.id && left.job.producer == right.job.producer &&
            left.job.ownershipGeneration == right.job.ownershipGeneration && left.claims.size == right.claims.size &&
            left.claims.toSet() == right.claims.toSet()

    internal suspend fun requireExecutableProducer(join: PhysicalDownloadJoin) = requireKnownProducer(join)

    internal fun clearPhysicalClaimDeferrals(id: String) = clearClaimDeferrals(id)

    internal fun cleanupObservation(id: String): DownloadCleanupObservation? =
        cleanupObservations.value[id]?.observation
    internal fun cleanupOwner(id: String): DownloadPhysicalCleanup? =
        host.cleanupRegistry.owners.value.values.filterIsInstance<DownloadPhysicalCleanup>()
            .singleOrNull { it.epoch.downloadId == id }

    /**
     * Bounded observation of a removal whose cleanup may be owned by the scheduler. The initial
     * phase learns whether an exact cleanup owner is present. The settled phase then waits for
     * durable `cleanupPending=false` or, when terminal failure policy applies, a terminal
     * `Failed` observation; a consumed owner alone is never success or failure. If neither
     * happens, the caller fails within a fixed grace instead of waiting indefinitely for a
     * missing owner. The grace covers the real handoff between a failed execution finalizer and
     * the scheduler's cleanup claim.
     */
    internal suspend fun awaitRemovalOutcome(id: String, failedIsTerminal: Boolean = true): AppFailure? {
        val pending = repository.state.map { state ->
            val job = state.physicalDownloads[id]
            (job?.cleanupPending == true) to job?.ownershipGeneration
        }
        val ownerPresent = host.cleanupRegistry.owners.map { owners ->
            owners.values.any { it is DownloadPhysicalCleanup && it.epoch.downloadId == id }
        }
        val observed = combine(pending, cleanupObservations, ownerPresent) { current, observations, hasOwner ->
            val (isPending, generation) = current
            val observation = observations[id]?.let { stored ->
                // A terminal observation from an older removal generation cannot settle this one.
                if (generation == null || stored.ownershipGeneration >= generation) stored.observation else null
            }
            DownloadRemovalSnapshot(isPending, observation, hasOwner)
        }
        val initial = withTimeoutOrNull(removalOwnerGraceMs) {
            observed.first { !it.pending || it.ownerPresent ||
                failedIsTerminal && it.observation is DownloadCleanupObservation.Failed }
        } ?: return AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_unconfirmed")
        if (failedIsTerminal) (initial.observation as? DownloadCleanupObservation.Failed)?.let { return it.failure }
        if (!initial.pending) return null
        if (!initial.ownerPresent)
            return AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_unconfirmed")
        // An owner is actually doing the removal; wait for its durable pending/observation
        // settlement rather than for an owner registry that is legitimately consumed afterwards.
        // A terminal Failed observation settles immediately even if its owner stays registered.
        // A consumed owner with cleanup still pending is not terminal: the scheduler may have
        // already handed the exact deletion to a newer owner. Wait within the same bound for
        // either durable completion or a terminal failure.
        val settled = withTimeoutOrNull(removalOwnerGraceMs) {
            observed.first { !it.pending || failedIsTerminal && it.observation is DownloadCleanupObservation.Failed }
        } ?: return AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_unconfirmed")
        if (failedIsTerminal) (settled.observation as? DownloadCleanupObservation.Failed)?.let { return it.failure }
        if (!settled.pending) return null
        return AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_unconfirmed")
    }

    private fun AcquisitionDownloadMemberKey.expectation(claimId: DownloadClaimId?) = BatchDownloadMemberExpectation(
        batchId, memberPosition, intentRevision, occurrenceId, requestId, requestedBindingId, claimId,
    )

    /** Caller holds the existing activeLock; epoch starts before any slot/path acquisition. */
    internal suspend fun claimExecution(id: String, admissionOwner: Job? = null): DownloadPhysicalRun? {
        val before = repository.physicalDownload(id) ?: return null
        val state = host.state()
        val record = before.job
        val relevant = before.scheduleRecord()
        if (!canExecute(state) || !DownloadPhysicalClaimRules.acquisitionIntent(relevant) ||
            DownloadPhysicalClaimRules.backgroundBlocked(record) || record.livePhysicalLease(clock.epochMs()) ||
            (record.nextRetryEpochMs ?: 0) > clock.epochMs() ||
            (physicalClaimDeferredUntil(relevant, DownloadClaimKind.ACQUISITION, claimDeferrals.value) ?: 0) > clock.epochMs() ||
            !epochs.canAdmit(id)) return null
        val occupied = repository.state.value.physicalDownloads.values.count {
            it.livePhysicalLease(clock.epochMs()) && it.maintenanceKind == null && !it.cleanupPending && it.status in DownloadClaimRules.busy
        }
        if (occupied >= state.parallelism.coerceIn(1, 4)) return null
        requireKnownProducer(before)
        dependencies.requireOwnerAdmission()
        check(record.ownershipGeneration < Long.MAX_VALUE && record.attemptCount < Int.MAX_VALUE)
        val attemptId = newEntityId("download-attempt")
        val epoch = epochs.begin(id, record.producer, record.ownershipGeneration + 1, attemptId, record.attemptCount + 1, admissionOwner)
        val partial = DownloadLeaseCleanupScope(host.cleanupRegistry)
        var slot: DownloadTransferSlot? = null
        var pathLease: DownloadFileLease? = null
        var handedOff = false
        var acceptedDurableOwner = false
        val proposedFence = PhysicalDownloadFence(id, record.producer, record.ownershipGeneration + 1, owner,
            attemptId, record.attemptCount + 1)
        try {
            slot = files.tryTransferSlot(state.parallelism, partial) ?: return null
            var path = record.stagingPath.takeIf { !record.representationId.isNullOrBlank() }
            if (path != null) pathLease = files.tryLeasePaths(listOf(path), partial)
            if (pathLease == null) {
                path = files.part(record.producer.subjectId.value, attemptId).toString()
                pathLease = files.tryLeasePaths(listOf(path), partial) ?: return null
            }
            ensureHost(state.generation, network = true)
            // Preserve an exact returned CAS even when the caller is cancelled on dispatch.
            val result = withContext(NonCancellable) {
                repository.claimPhysicalDownloadAttempt(ClaimPhysicalDownloadAttempt(id, record.producer,
                    record.ownershipGeneration, attemptId, owner, clock.epochMs(), leaseDurationMs, requireNotNull(path)))
            }
            if (result !is PhysicalDownloadCommit.Committed) return null
            acceptedDurableOwner = true
            val claimed = result.value
            requireClaim(claimed, proposedFence)
            if (claimed.physical.job.stagingPath != path || claimed.physical.activeAttempt?.stagingPath != path)
                fail(FailureCode.RECOVERY_REQUIRED, "download_claim_path_changed")
            if (path != record.stagingPath && (claimed.physical.job.bytesDownloaded != 0L || claimed.physical.job.totalBytes != null ||
                claimed.physical.job.etag != null || claimed.physical.job.lastModified != null))
                fail(FailureCode.RECOVERY_REQUIRED, "download_new_path_checkpoint_not_reset")
            return DownloadPhysicalRun(epoch, claimed.fence, claimed.physical, state.generation, null,
                requireNotNull(pathLease), slot).also {
                runs.update { current -> current + (id to it) }
                clearClaimDeferrals(id, DownloadClaimKind.ACQUISITION)
                handedOff = true
            }
        } finally {
            if (!handedOff) withContext(NonCancellable) {
                rollback(epoch, partial, listOfNotNull(pathLease, slot), acceptedDurableOwner) {
                    settle { repository.finishPhysicalDownloadAttempt(FinishPhysicalDownloadAttempt(proposedFence,
                        DownloadStatus.QUEUED, DownloadAttemptStatus.PAUSED, null, null,
                        record.automaticRetryCount, clock.epochMs())) }
                }
                epochs.finishAdmission(epoch)
                deferClaim(id, DownloadClaimKind.ACQUISITION)
            }
        }
    }

    /** Coordinator has registered this exact ATOMIC task in its active map before this call. */
    internal suspend fun registerExecution(run: DownloadPhysicalRun, job: Job) = withContext(NonCancellable) {
        epochs.registerJob(run.epoch, job)
        epochs.finishAdmission(run.epoch)
    }

    /**
     * Called under the EXISTING commands+active locks. Uses that coordinator's scope, not a
     * second worker. Even an already-cancelled parent starts the ATOMIC owner and its finally.
     * The finite registration barrier completes on every installer exit; it is not a dummy
     * coroutine/empty-map quiescence receipt. Native work cannot precede actual Job registration.
     */
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    internal suspend fun installExecution(run: DownloadPhysicalRun, scope: CoroutineScope): Unit = withContext(NonCancellable) {
        val registered = CompletableDeferred<Boolean>()
        var task: Job? = null
        try {
            val launched = scope.launch(start = CoroutineStart.ATOMIC) {
                val actual = currentCoroutineContext().job
                var enteredExecution = false
                try {
                    if (!withContext(NonCancellable) { registered.await() }) return@launch
                    enteredExecution = true
                    execute(run)
                } finally {
                    withContext(NonCancellable) {
                        if (!enteredExecution) abandonExecution(run)
                        host.activeLock.withLock {
                            if (host.active[run.fence.downloadId] === actual) host.active.remove(run.fence.downloadId)
                            forgetRun(run)
                            host.changed()
                        }
                        host.signalWork()
                    }
                }
            }
            task = launched
            host.active[run.fence.downloadId] = launched
            registerExecution(run, launched)
            host.changed()
            registered.complete(true)
        } catch (error: Throwable) {
            try {
                task?.cancel()
                // A created ATOMIC task owns exceptional unwind after the barrier is released.
                // A failed launch with no returned task leaves that responsibility with this call.
                if (task == null) {
                    abandonExecution(run, report = false)
                    forgetRun(run) // This installer still holds the existing activeLock.
                }
            } finally { registered.complete(false) }
            throw error
        }
        Unit
    }

    private suspend fun abandonExecution(run: DownloadPhysicalRun, report: Boolean = true) = withContext(NonCancellable) {
        retainCleanup(run.epoch, DownloadLeaseCleanupScope(host.cleanupRegistry), listOf(
            { run.pathLease.close() }, { run.slot?.close(); Unit },
        ), report = report, acceptedDurableOwner = true, expectedClaims = run.current.claims) { finishExecution(run, null, cancelled = true) }
        epochs.finishAdmission(run.epoch)
    }

    internal suspend fun execute(run: DownloadPhysicalRun) {
        var allowance: DownloadExecutionLease? = null
        var publicationLease: DownloadFileLease? = null
        val failure = MutableStateFlow<AppFailure?>(null)
        var cancelled = false
        val partial = DownloadLeaseCleanupScope(host.cleanupRegistry)
        try {
            coroutineScope {
                val task = currentCoroutineContext().job
                val heartbeat = launch {
                    while (isActive && !run.actionFinished.value) {
                        delay(heartbeatMs)
                        if (run.actionFinished.value) break
                        try { checkpoint(run) }
                        catch (error: Exception) {
                            if (error !is CancellationException) {
                                failure.update { it ?: classify(error) }
                                host.publishFailure(checkNotNull(failure.value))
                            }
                            task.cancel(CancellationException("Download ownership revoked"))
                        }
                    }
                }
                try {
                    checkpoint(run)
                    withContext(NonCancellable) { allowance = gate.acquire { expire(run) } }
                    ensureRun(run, network = true)
                    val prepared = prepare(run)
                    mutate(run) { repository.selectPhysicalDownloadRepresentation(SelectPhysicalDownloadRepresentation(
                        run.fence, prepared.representation.representationId, run.current.job.validatorGeneration,
                        run.current.job.etag, run.current.job.lastModified, clock.epochMs())) }
                    val part = withContext(Dispatchers.IO) { files.prepareStagingPath(requireNotNull(run.current.job.stagingPath)) }
                    var lastProgress: Long? = null
                    val result = prepared.transfer(part.toString()) { progress ->
                        ensureRun(run, network = true)
                        if (progress.bytesDownloaded < 0 || progress.totalBytes?.let { it < progress.bytesDownloaded } == true)
                            fail(FailureCode.CORRUPT_MEDIA, "invalid_transfer_count")
                        val now = clock.monotonicMs()
                        if (lastProgress == null || now - lastProgress!! >= 500 || progress.bytesDownloaded == progress.totalBytes) {
                            checkpoint(run, progress.bytesDownloaded, progress.totalBytes)
                            lastProgress = now
                        }
                    }
                    ensureRun(run, network = true)
                    if (result.bytesWritten <= 0 || result.representation != prepared.representation)
                        fail(FailureCode.CORRUPT_MEDIA, "transfer_identity_changed")
                    requireNativeRepresentation(result.representation)
                    checkpoint(run, result.bytesWritten, result.bytesWritten)
                    mutate(run) { repository.beginPhysicalDownloadValidation(BeginPhysicalDownloadValidation(run.fence, clock.epochMs())) }
                    if (storageOperation { files.fileSystem.metadata(part).size } != result.bytesWritten)
                        fail(FailureCode.CORRUPT_MEDIA, "transfer_size_changed")
                    val hash = files.sha256(part)
                    val local = LocalMediaValidationInput(part.toString(), OfflineAssetKind.FILE, result.bytesWritten,
                        result.representation.mimeType, result.representation.codecs, hash,
                        result.representation.representationId, run.current.job.validatorGeneration)
                    val duration = validate(run, local)
                    ensureRun(run, network = true)
                    val intent = PhysicalDownloadFinalization(run.fence.downloadId, run.fence.producer, part.toString(),
                        files.finalPath(run.fence.producer.subjectId.value, local.validatorGeneration, local.mimeType,
                            requireNotNull(run.fence.attemptId)).toString(), hash, result.bytesWritten,
                        run.fence.attemptId, duration, local.mimeType, local.codecs, local.representationId,
                        local.validatorGeneration, run.fence.ownershipGeneration, MaterializationAssetId(newEntityId("materialization")))
                    publicationLease = files.tryLeasePaths(listOf(intent.finalPath), partial)
                        ?: throw CancellationException("Publication path is still owned")
                    mutate(run) { repository.preparePhysicalDownloadFinalization(PreparePhysicalDownloadFinalization(run.fence, intent, clock.epochMs())) }
                    ensureRun(run, network = true)
                    // A losing cancellation can leave only this reserved generation's file.
                    // Publication still needs exact intent/owner CAS; recovery never reselects.
                    files.atomicPublish(intent.stagingPath, intent.finalPath)
                    publish(run, intent)
                } finally { withContext(NonCancellable) { heartbeat.cancelAndJoin() } }
            }
        } catch (error: CancellationException) { cancelled = failure.value == null; throw error }
        catch (error: Exception) {
            failure.update { it ?: classify(error) }
            if (failure.value?.code == FailureCode.BACKGROUND_RESTRICTED) expire(run)
            else host.publishFailure(checkNotNull(failure.value))
        } finally {
            withContext(NonCancellable) {
                retainCleanup(run.epoch, partial, listOf(
                    { run.validationReaders.requireReleased() },
                    { allowance?.close(); run.allowanceCallbacksOpen.value = false },
                    { publicationLease?.close(); Unit },
                    { run.pathLease.close() },
                    { run.slot?.close(); Unit },
                ), temporaryAfter = 2, acceptedDurableOwner = true, expectedClaims = run.current.claims) { finishExecution(run, failure.value, cancelled) }
                host.signalWork()
            }
        }
    }

    /** Never classify native-release failure before it is pinned ahead of this run's leases. */
    private suspend fun validate(run: DownloadPhysicalRun, input: LocalMediaValidationInput,
        attempt: DownloadMaterializationValidationAttempt? = null,
        onRejection: (DownloadMaterializationRejection) -> Unit = {},
    ): Long? = run.validationReaders.validate {
        files.validateMaterialization(input, dependencies.validator) { negative ->
            attempt?.let { materializationEvidence.reject(it, negative) }?.let(onRejection)
        }
    }

    private class Prepared(
        val representation: AcquisitionRepresentation,
        val transfer: suspend (String, suspend (AcquisitionTransferProgress) -> Unit) -> Transferred,
    )
    private class Transferred(val bytesWritten: Long, val representation: AcquisitionRepresentation)

    private suspend fun prepare(run: DownloadPhysicalRun): Prepared = when (run.fence.producer) {
        is PhysicalDownloadProducer.Typed -> {
            val context = requireNotNull(run.current.producerContext)
            val selected = dependencies.gateway.prepareDownload(context, capabilities, run.current.job.representationId)
            if (selected.context != context || selected.requiredRepresentationId != run.current.job.representationId ||
                run.current.job.representationId?.let { it != selected.representation.representationId } == true)
                fail(FailureCode.INVALID_STATE, "download_gateway_identity")
            requireNativeRepresentation(selected.representation)
            Prepared(selected.representation) { path, progress ->
                val result = dependencies.gateway.download(selected, path, progress)
                if (result.context != context) fail(FailureCode.CORRUPT_MEDIA, "download_producer_changed")
                Transferred(result.bytesWritten, result.representation)
            }
        }
        is PhysicalDownloadProducer.Schema2 -> {
            val binding = legacyBinding(run.current)
            val gateway = legacyGateway as? LegacyPreparedDownloadGateway
                ?: fail(FailureCode.UNSUPPORTED_SOURCE, "download_legacy_preparation_unavailable")
            val selected = gateway.prepareDownload(binding, capabilities, run.current.job.representationId)
            if (selected.binding != binding || selected.requiredRepresentationId != run.current.job.representationId ||
                run.current.job.representationId?.let { it != selected.representation.representationId } == true)
                fail(FailureCode.INVALID_STATE, "download_gateway_identity")
            requireNativeRepresentation(selected.representation)
            Prepared(selected.representation) { path, progress ->
                val result = gateway.download(selected, path, progress)
                if (result.binding != binding) fail(FailureCode.CORRUPT_MEDIA, "download_producer_changed")
                Transferred(result.bytesWritten, result.representation)
            }
        }
    }

    private suspend fun checkpoint(run: DownloadPhysicalRun, bytes: Long? = null, total: Long? = null) = mutate(run) {
        repository.heartbeatPhysicalDownload(HeartbeatPhysicalDownload(run.fence, clock.epochMs(), leaseDurationMs,
            bytes ?: run.current.job.bytesDownloaded, if (bytes == null) run.current.job.totalBytes else total))
    }

    private suspend fun mutate(run: DownloadPhysicalRun,
        committed: suspend (PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>) -> Unit = {},
        command: suspend () -> PhysicalDownloadCommit<PhysicalDownloadJoin>) =
        run.mutation.withLock {
            ensureRun(run, network = run.kind == null)
            withContext(NonCancellable) {
                when (val result = command()) {
                    is PhysicalDownloadCommit.Committed -> {
                        if (result.value.job.id != run.fence.downloadId || result.value.job.producer != run.fence.producer ||
                            result.value.job.ownershipGeneration != run.fence.ownershipGeneration)
                            fail(FailureCode.RECOVERY_REQUIRED, "download_cas_identity_changed")
                        run.current = result.value
                        committed(result)
                        result
                    }
                    PhysicalDownloadCommit.Stale -> throw CancellationException("Download ownership changed")
                }
            }
        }

    private suspend fun publish(run: DownloadPhysicalRun, intent: PhysicalDownloadFinalization) {
        val asset = materialized(intent)
        files.verifiedFile(asset.validationInput())
        mutate(run, committed = { result ->
            val joined = result.value
            if (joined.job.status != DownloadStatus.COMPLETE || joined.asset != asset || joined.job.assetId != asset.id ||
                joined.finalization != null || joined.job.activeAttemptId != null || joined.job.leaseOwner != null ||
                joined.job.leaseExpiresEpochMs != null || joined.job.maintenanceKind != null ||
                joined.job.representationId != asset.representationId || joined.job.validatorGeneration != asset.validatorGeneration)
                fail(FailureCode.RECOVERY_REQUIRED, "download_publication_join_mismatch")
            run.published = result
            run.actionFinished.value = true
            materializationEvidence.recordPhysicalSuccess(run.fence.downloadId, asset)
            epochs.recordDurable(run.epoch, AcquisitionDownloadDurableSettlement.Confirmed(result.libraryRevision))
        }) {
            repository.publishPhysicalDownloadFinalization(PublishPhysicalDownloadFinalization(run.fence, intent, asset, clock.epochMs()))
        }
    }

    private suspend fun finishExecution(run: DownloadPhysicalRun, failure: AppFailure?, cancelled: Boolean): AcquisitionDownloadDurableSettlement {
        run.published?.let { return AcquisitionDownloadDurableSettlement.Confirmed(it.libraryRevision) }
        val state = host.state()
        val before = run.current.job
        val retry = !cancelled && failure?.code in RETRYABLE && before.automaticRetryCount < 5
        // A foreground resume advances the host generation. A LATE finish from the previous
        // generation must not relabel the newly admitted/resumed host as suspended again.
        val activeSuspension = run.hostFinishStatus.value?.takeIf { state.generation == run.hostGeneration }
        val target = when {
            activeSuspension != null -> activeSuspension
            state.suspended && !state.closed || failure?.code == FailureCode.BACKGROUND_RESTRICTED -> DownloadStatus.NEEDS_FOREGROUND
            state.offlineOnly || failure?.code == FailureCode.OFFLINE_POLICY || retry -> DownloadStatus.WAITING_NETWORK
            run.current.finalization != null && failure == null -> DownloadStatus.VERIFYING
            cancelled || state.closed -> DownloadStatus.QUEUED
            else -> DownloadStatus.FAILED
        }
        return settle { repository.finishPhysicalDownloadAttempt(FinishPhysicalDownloadAttempt(run.fence, target,
            if (cancelled) DownloadAttemptStatus.PAUSED else DownloadAttemptStatus.FAILED,
            if (target == DownloadStatus.NEEDS_FOREGROUND) AppFailure(FailureCode.BACKGROUND_RESTRICTED) else failure,
            if (retry) deadline(DownloadCoordinator.retryDelayMs(before.automaticRetryCount, failure?.retryAfterMs)) else before.nextRetryEpochMs,
            before.automaticRetryCount + if (retry) 1 else 0, clock.epochMs())) }
    }

    private fun materialized(intent: PhysicalDownloadFinalization): MaterializationAsset {
        val id = intent.assetId ?: if (intent.producer is PhysicalDownloadProducer.Schema2)
            MaterializationAssetId("${intent.downloadId}-${intent.attemptId ?: intent.validatorGeneration}")
        else fail(FailureCode.RECOVERY_REQUIRED, "download_asset_identity_missing")
        return MaterializationAsset(id, intent.producer, intent.finalPath, intent.sizeBytes,
            mimeType = intent.mimeType, codecs = intent.codecs, sha256 = intent.sha256,
            representationId = intent.representationId, validatorGeneration = intent.validatorGeneration,
            completedEpochMs = clock.epochMs(), measuredDurationMs = intent.mediaDurationMs,
            materializerDownloadId = intent.downloadId, materializerAttemptId = intent.attemptId,
            ownershipGeneration = intent.ownershipGeneration)
    }

    internal suspend fun attemptCleanup(cleanup: DownloadPhysicalCleanup, retryFailed: Boolean = false, report: Boolean = true) {
        cleanup.lock.withLock {
            if (cleanup.failed.value && !retryFailed) return@withLock
            cleanupObservations.update { it + (cleanup.epoch.downloadId to GeneratedCleanupObservation(
                cleanup.epoch.physicalOwnershipGeneration, DownloadCleanupObservation.InProgress)) }
            try {
                while (cleanup.nextStep < cleanup.physicalSteps.size) {
                    val step = cleanup.physicalSteps[cleanup.nextStep]
                    if (step.explicitRetryOnly && !retryFailed) {
                        cleanup.failed.value = true
                        cleanupObservations.update { it + (cleanup.epoch.downloadId to GeneratedCleanupObservation(
                            cleanup.epoch.physicalOwnershipGeneration, DownloadCleanupObservation.Failed(
                                AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_unconfirmed")))) }
                        return@withLock
                    }
                    step.close()
                    cleanup.nextStep++
                }
            } catch (_: Throwable) {
                cleanup.failed.value = true
                cleanupObservations.update { it + (cleanup.epoch.downloadId to GeneratedCleanupObservation(
                    cleanup.epoch.physicalOwnershipGeneration, DownloadCleanupObservation.Failed(
                        AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_unconfirmed")))) }
                return@withLock
            }
            // Room failure is NOT positive durable settlement, but no physical stage remains
            // in use once its exception is caught here. Keep the two receipts independent.
            if (!cleanup.durableAttempted) {
                cleanup.durableAttempted = true
                cleanup.durableResult = try { cleanup.durableFinish() }
                catch (error: Throwable) {
                    if (cleanup.acceptedDurableOwner && error !is VeyraException) {
                        firstUnknownCheckpointFailure.update { it ?: error }
                        // This can run under an admission lock during rollback. Fence immediately
                        // without joining/taking commands, then consume physical ownership once.
                        host.failCheckpointAdmission()
                    }
                    AcquisitionDownloadDurableSettlement.Unconfirmed(classify(error))
                }
            }
            val durable = cleanup.durableResult
            if (durable != null) epochs.recordDurable(cleanup.epoch, durable)
            // A stale finalizer from a revoked/superseded owner is not an unknown checkpoint.
            // Its physical stages already ran under the retained cleanup owner; the current
            // physical generation can proceed with its own cleanup/retry or retained settlement.
            val stateSnapshot = repository.state.value
            val currentJob = stateSnapshot.physicalDownloads[cleanup.epoch.downloadId]
            val currentClaims = stateSnapshot.claims.values.filter {
                it.physicalJobId == cleanup.epoch.downloadId
            }.toSet()
            val claimsChanged = cleanup.expectedClaims.isNotEmpty() && currentClaims != cleanup.expectedClaims.toSet()
            val superseded = cleanup.epoch.revoked || currentJob == null ||
                currentJob.ownershipGeneration != cleanup.epoch.physicalOwnershipGeneration ||
                currentJob.producer != cleanup.epoch.producer || claimsChanged
            // A late execution/rollback finalizer may lose the durable finish race to the foreign
            // cancellation that already owns or completed cleanup (possibly through a newer
            // maintenance generation). Exact proof that THIS producer is terminally cancelled:
            // the current row is CANCELLED at this or a newer generation and every surviving claim
            // is CANCELLED. The stale write was rejected, nothing active remains, so it is not an
            // unknown checkpoint. An unchanged/non-cancelled generation or an ACTIVE claim stays
            // fatal.
            val cancelledSuperseded = currentJob != null &&
                currentJob.status == DownloadStatus.CANCELLED &&
                currentJob.producer == cleanup.epoch.producer &&
                currentJob.ownershipGeneration >= cleanup.epoch.physicalOwnershipGeneration &&
                (currentClaims.isEmpty() || currentClaims.all { it.intent == DownloadBatchIntent.CANCELLED })
            val benignStale = cleanup.acceptedDurableOwner && durable == AcquisitionDownloadDurableSettlement.Stale &&
                (cleanup.maintenanceKind != null && superseded || cancelledSuperseded ||
                    currentJob?.cleanupPending == true && (cleanup.epoch.revoked || claimsChanged ||
                        currentJob?.ownershipGeneration != cleanup.epoch.physicalOwnershipGeneration) ||
                    cleanup.epoch.revoked && currentJob?.status == DownloadStatus.CANCELLED)
            val checkpointFailure = if (cleanup.acceptedDurableOwner && !benignStale) when (durable) {
                is AcquisitionDownloadDurableSettlement.Confirmed -> null
                is AcquisitionDownloadDurableSettlement.Unconfirmed -> durable.failure
                AcquisitionDownloadDurableSettlement.Stale -> AppFailure(FailureCode.INVALID_STATE, "download_checkpoint_stale")
                null -> AppFailure(FailureCode.RECOVERY_REQUIRED, "download_checkpoint_unconfirmed")
            } else null
            checkpointFailure?.let { failure ->
                firstCheckpointFailure.update { it ?: failure }
                host.publishFailure(failure)
            }
            if (!cleanup.acceptedDurableOwner && durable is AcquisitionDownloadDurableSettlement.Unconfirmed)
                host.publishFailure(durable.failure)
            val observation = when {
                currentJob?.cleanupPending == false -> DownloadCleanupObservation.Completed
                durable is AcquisitionDownloadDurableSettlement.Unconfirmed -> DownloadCleanupObservation.Failed(durable.failure)
                // The revoked/superseded owner has released all physical resources. Its stale
                // finish leaves cleanup pending for the foreground retry, including when the
                // scheduler won admission and the command never caught the child cancellation.
                benignStale -> DownloadCleanupObservation.InProgress
                durable is AcquisitionDownloadDurableSettlement.Stale -> DownloadCleanupObservation.Failed(
                    AppFailure(FailureCode.INVALID_STATE, "download_checkpoint_stale"))
                cleanup.failed.value || durable == null -> DownloadCleanupObservation.Failed(
                    AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_unconfirmed"))
                else -> DownloadCleanupObservation.InProgress
            }
            cleanupObservations.update { it + (cleanup.epoch.downloadId to GeneratedCleanupObservation(
                cleanup.epoch.physicalOwnershipGeneration, observation)) }
            // Publish the diagnostic BEFORE consuming the owner; no later empty registry loses it.
            epochs.consumedFinalizer(cleanup.epoch, cleanup.token, cleanup)
            host.cleanupRegistry.remove(cleanup)
        }
        if (report) host.publishCleanupFailure()
    }

    private suspend fun retainCleanup(
        epoch: DownloadOwnershipEpochs.Epoch,
        partial: DownloadLeaseCleanupScope,
        stages: List<suspend () -> Unit>,
        temporaryAfter: Int = 0,
        report: Boolean = true,
        acceptedDurableOwner: Boolean = false,
        expectedClaims: List<DownloadClaim> = emptyList(),
        maintenanceKind: PhysicalDownloadMaintenanceKind? = null,
        durable: suspend () -> AcquisitionDownloadDurableSettlement?,
    ): DownloadPhysicalCleanup {
        require(temporaryAfter in 0..stages.size)
        val temporary = partial.pending()
        val cleanup = DownloadPhysicalCleanup(this, epoch,
            stages.take(temporaryAfter).map { DownloadPhysicalCleanupStep(it) } +
                temporary.map { DownloadPhysicalCleanupStep({ it.close() }, explicitRetryOnly = it.failed.value) } +
                stages.drop(temporaryAfter).map { DownloadPhysicalCleanupStep(it) }, acceptedDurableOwner,
            expectedClaims, maintenanceKind, durable)
        host.cleanupRegistry.register(cleanup)
        epochs.retain(epoch, cleanup.token, cleanup, completesAcquisition = true)
        partial.adopted()
        attemptCleanup(cleanup, report = report)
        return cleanup
    }

    private suspend fun rollback(epoch: DownloadOwnershipEpochs.Epoch, partial: DownloadLeaseCleanupScope,
        leases: List<DownloadFileLease>, acceptedDurableOwner: Boolean,
        durable: suspend () -> AcquisitionDownloadDurableSettlement?) {
        retainCleanup(epoch, partial, leases.map { lease -> suspend { lease.close() } }, report = false,
            acceptedDurableOwner = acceptedDurableOwner, durable = durable)
    }

    private suspend fun expire(run: DownloadPhysicalRun) {
        if (!run.allowanceCallbacksOpen.value || run.actionFinished.value) return
        // The existing host must recheck the exact registered owner under activeLock BEFORE
        // cancelling it. A late/replaced callback cannot revoke a now-unrelated generation.
        host.allowanceExpired(run)
    }

    private suspend fun ensureRun(run: DownloadPhysicalRun, network: Boolean) {
        currentCoroutineContext().ensureActive()
        ensureHost(run.hostGeneration, network)
        if (epochs.isRevoked(run.epoch)) throw CancellationException("Download physical ownership revoked")
        if (run.kind != null) return // Local maintenance owns no transfer slot.
        // Another Room facade may lower the GLOBAL limit without publishing to this host's
        // cached Flow. Retire only this actual high slot, through its existing cancel/unwind.
        val parallelism = host.acquisitionView().snapshot.settings.downloadParallelism.coerceIn(1, 4)
        currentCoroutineContext().ensureActive()
        ensureHost(run.hostGeneration, network)
        if (epochs.isRevoked(run.epoch)) throw CancellationException("Download physical ownership revoked")
        if (requireNotNull(run.slot).index >= parallelism)
            throw CancellationException("Download transfer slot retired by parallelism policy")
    }

    private fun ensureHost(generation: Long, network: Boolean) {
        val state = host.state()
        state.failure?.let { throw VeyraException(it) }
        if (!state.started || state.closed || state.suspended || state.drainsInFlight != 0 || state.generation != generation)
            fail(FailureCode.BACKGROUND_RESTRICTED, "download_host_not_active")
        // A connection is a NEW admission gate. An in-flight owner reports its actual source
        // outcome rather than being relabeled/cancelled merely by a connectivity observation.
        if (network && state.offlineOnly) fail(FailureCode.OFFLINE_POLICY, "download_offline_policy")
    }

    private fun canExecute(state: DownloadPhysicalHostState): Boolean = state.started && !state.closed && !state.suspended &&
        state.drainsInFlight == 0 && state.failure == null && !state.offlineOnly && state.network == DownloadNetworkState.CONNECTED

    private fun requireNativeRepresentation(value: AcquisitionRepresentation) {
        if (value.representationId.isBlank()) fail(FailureCode.CORRUPT_MEDIA, "download_representation_missing")
        requireLocalSupport(value.mimeType, value.codecs)
    }

    private fun requireLocalSupport(mimeType: String?, codecs: String?) {
        if (!capabilities.supportsLocalFiles || !capabilities.supports(StreamTransport.PROGRESSIVE, mimeType, codecs))
            fail(FailureCode.UNSUPPORTED_CODEC, "download_output_not_supported")
    }

    private fun requireMaterialization(join: PhysicalDownloadJoin, asset: MaterializationAsset) {
        if (join.asset != asset)
            fail(FailureCode.CORRUPT_MEDIA, "download_materialization_identity_changed")
        physicalMaterializationIdentityFailure(join.job, join.finalization, asset, join.attempts)?.let { throw VeyraException(it) }
        requireLocalSupport(asset.mimeType, asset.codecs)
    }

    private suspend fun requireClaim(value: ClaimedPhysicalDownload, expected: PhysicalDownloadFence) {
        if (value.fence != expected || value.physical.job.id != expected.downloadId ||
            value.physical.job.producer != expected.producer || value.physical.job.ownershipGeneration != expected.ownershipGeneration ||
            value.physical.activeAttempt?.id != expected.attemptId || value.physical.activeAttempt?.number != expected.attemptNumber ||
            value.physical.activeAttempt?.producer != expected.producer ||
            value.physical.activeAttempt?.ownershipGeneration != expected.ownershipGeneration)
            fail(FailureCode.RECOVERY_REQUIRED, "download_claim_identity_changed")
        requireKnownProducer(value.physical)
    }

    private suspend fun requireKnownProducer(join: PhysicalDownloadJoin) {
        when (val producer = join.job.producer) {
            is PhysicalDownloadProducer.Typed -> {
                val context = join.producerContext ?: fail(FailureCode.RECOVERY_REQUIRED, "download_producer_context_missing")
                if (producer.subjectId != context.subject.id || producer.bindingId != context.binding.id ||
                    producer.recordingId != context.recording.id) fail(FailureCode.RECOVERY_REQUIRED, "download_producer_context_mismatch")
            }
            is PhysicalDownloadProducer.Schema2 -> legacyBinding(join)
        }
    }

    /** Only the real existing schema2 receipt/catalogue projection may enter the legacy port. */
    private suspend fun legacyBinding(join: PhysicalDownloadJoin): PlaybackBinding {
        val producer = join.job.producer as? PhysicalDownloadProducer.Schema2
            ?: fail(FailureCode.INVALID_STATE, "download_legacy_producer_required")
        val view = host.acquisitionView()
        val state = view.acquisition
        val id = producer.bindingId ?: fail(FailureCode.RECOVERY_REQUIRED, "download_legacy_acquisition_required")
        val binding = state.bindings[id] ?: fail(FailureCode.RECOVERY_REQUIRED, "download_legacy_binding_missing")
        val receipt = state.receipts[binding.firstReceiptId] as? FirstResultReceipt.Schema2
            ?: fail(FailureCode.RECOVERY_REQUIRED, "download_legacy_receipt_missing")
        val recordingId = producer.recordingId ?: fail(FailureCode.RECOVERY_REQUIRED, "download_legacy_recording_missing")
        val recording = state.recordings[recordingId] ?: fail(FailureCode.RECOVERY_REQUIRED, "download_legacy_recording_missing")
        val legacy = view.snapshot.playbackBindings[id.value]
            ?: fail(FailureCode.RECOVERY_REQUIRED, "download_legacy_binding_missing")
        if (join.producerContext != null || binding.origin != ExecutionBindingOrigin.SCHEMA2_EXECUTABLE ||
            binding.subjectId != producer.subjectId || binding.recordingId != producer.recordingId ||
            view.snapshot.media[producer.mediaId] == null || receipt.mediaId != producer.mediaId ||
            receipt.selectionOrdinal != 0 || receipt.firstResultKind != FirstResultKind.VIDEO ||
            receipt.firstResultId != recording.canonicalId || receipt.selectedVideoId != recording.canonicalId ||
            receipt.canonicalUrl?.let { it != recording.canonicalUrl } == true || legacy.mediaId != producer.mediaId ||
            legacy.queryTitle != receipt.queryTitle || legacy.firstResultKind != receipt.firstResultKind ||
            legacy.firstResultId != receipt.firstResultId || legacy.firstResultTitle != receipt.firstResultTitle ||
            legacy.firstResultUrl != receipt.canonicalUrl || legacy.selectedVideoId != receipt.selectedVideoId ||
            legacy.resolvedEpochMs != receipt.resolvedEpochMs || legacy.selectionOrdinal != receipt.selectionOrdinal)
            fail(FailureCode.RECOVERY_REQUIRED, "download_legacy_receipt_changed")
        return legacy
    }

    private suspend fun settle(command: suspend () -> PhysicalDownloadCommit<*>): AcquisitionDownloadDurableSettlement =
        when (val result = command()) {
            is PhysicalDownloadCommit.Committed -> AcquisitionDownloadDurableSettlement.Confirmed(result.libraryRevision)
            PhysicalDownloadCommit.Stale -> AcquisitionDownloadDurableSettlement.Stale
        }

    private fun PhysicalDownloadJoin.scheduleRecord() = DownloadPhysicalScheduleRecord(job.copy(bytesDownloaded = 0,
        totalBytes = null, updatedEpochMs = 0, lastProgressEpochMs = null), claims.sortedBy { it.id.value }, finalization, asset,
        stagingPaths(this))

    private fun deferClaim(id: String, kind: DownloadClaimKind) {
        val relevant = repository.state.value.physicalSchedulingRecords()[id] ?: return
        val key = DownloadClaimKey(id, kind)
        claimDeferrals.update { previous ->
            val refusals = previous[key]?.takeIf { it.record == relevant }?.refusals?.coerceAtMost(4)?.plus(1) ?: 1
            previous + (key to DownloadPhysicalClaimDeferral(relevant,
                deadline((LOCAL_RECHECK_MS shl (refusals - 1)).coerceAtMost(60_000)), refusals))
        }
        host.changed()
    }

    private fun clearClaimDeferrals(id: String, kind: DownloadClaimKind? = null) {
        claimDeferrals.update { previous -> previous.filterKeys { it.id != id || kind != null && it.kind != kind } }
    }

    private fun deadline(delay: Long) = clock.epochMs().coerceIn(0, Long.MAX_VALUE - delay) + delay
    private fun classify(error: Throwable): AppFailure = when (error) {
        is LocalMediaValidationReaderUnacknowledged -> AppFailure(FailureCode.BACKGROUND_RESTRICTED, "local_validation_reader_unacknowledged")
        is VeyraException -> error.failure
        is IllegalArgumentException, is IllegalStateException -> AppFailure(FailureCode.INVALID_STATE, "download_executor_invalid_state")
        else -> AppFailure(FailureCode.UNEXPECTED, "download_executor_operation_failed")
    }
    private fun fail(code: FailureCode, detail: String): Nothing = throw VeyraException(AppFailure(code, detail))
    private companion object {
        const val LOCAL_RECHECK_MS = 5_000L
        /** Bounded real handoff allowance for a scheduler-owned removal; never an unbounded wait. */
        const val REMOVAL_OWNER_GRACE_MS = 5_000L
        val RETRYABLE = setOf(FailureCode.NETWORK_UNAVAILABLE, FailureCode.RATE_LIMITED)
        val STOP_DEMANDS = setOf(DownloadClaimDemand.PAUSE_AND_RETAIN, DownloadClaimDemand.CANCEL_UNFINISHED_KEEP_COMPLETE,
            DownloadClaimDemand.REMOVE_CANDIDATE)
    }
}
