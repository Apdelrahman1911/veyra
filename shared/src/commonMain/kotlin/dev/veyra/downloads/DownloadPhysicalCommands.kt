package dev.veyra.downloads

import dev.veyra.acquisition.AcquisitionDownloadDurableSettlement
import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.playback.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Metadata-only user commands for the existing executor. Selection belongs to the root's
 * already-owned acquisition action, never this helper, a new worker or a lock-held source call.
 * Every physical command reuses the canonical owner and this Coordinator's admission mutex.
 */
internal class DownloadPhysicalCommands(
    private val runtime: DownloadPhysicalRuntime,
    private val dependencies: DownloadAcquisitionDependencies,
    private val clock: Clock,
) {
    private val repository get() = runtime.repository
    private val host get() = runtime.host

    /**
     * The root's synchronous action/revision check runs AFTER lock acquisition and immediately
     * before accepting durable intent. It must not be a no-op for a pending source selection.
     * Root cancellation after this acceptance must reconcile the now-accepted standalone owner;
     * merely cancelling the source coroutine cannot retract an already committed Room command.
     */
    suspend fun enqueueAccepted(requestId: AcquisitionRequestId, requiredBindingId: ExecutionBindingId,
        requireIntentCurrent: () -> Unit): String = host.commands.withLock {
        requireAdmission()
        requireIntentCurrent()
        admitAcceptedLocked(requestId, requiredBindingId)
    }

    /**
     * Compatibility route for ALREADY selected genuine catalogue media only. New selections
     * must use root acquisition routing then enqueueAccepted; no fallback query is made here.
     */
    suspend fun enqueueKnownCatalog(item: MediaItem, reuseBindingId: String?): String = host.commands.withLock {
        requireAdmission()
        val view = host.acquisitionView()
        val currentItem = view.snapshot.media[item.id]
            ?: fail(FailureCode.RECOVERY_REQUIRED, "download_acquisition_selection_required")
        if (currentItem.kind != item.kind || currentItem.source.provider != item.source.provider ||
            currentItem.source.canonicalId != item.source.canonicalId)
            fail(FailureCode.INVALID_STATE, "download_catalog_identity_changed")
        val prior = view.acquisition.physicalDownloads.values.singleOrNull { it.producer.subjectId.value == item.id }
        val requested = reuseBindingId?.let(::ExecutionBindingId) ?: prior?.producer?.bindingId
            ?: fail(FailureCode.RECOVERY_REQUIRED, if (prior == null) "download_acquisition_selection_required"
                else "download_legacy_acquisition_required")
        val binding = view.acquisition.bindings[requested]
            ?: fail(FailureCode.RECOVERY_REQUIRED, "download_execution_binding_missing")
        if (binding.subjectId.value != item.id || binding.recordingId == null)
            fail(FailureCode.INVALID_STATE, "download_binding_identity")
        // No source suspension occurred, but a Room read may have yielded to root cancellation.
        requireAdmission()
        if (binding.origin == ExecutionBindingOrigin.TYPED) {
            val accepted = view.acquisition.requests.values.singleOrNull {
                (it.outcome as? AcquisitionOutcome.Single)?.bindingId == requested && it.state == AcquisitionJobState.COMMITTED
            } ?: fail(FailureCode.RECOVERY_REQUIRED, "download_acquisition_selection_required")
            if ((accepted.request.metadata as? MediaCapture)?.media?.id != item.id)
                fail(FailureCode.INVALID_STATE, "download_catalog_capture_required")
            admitAcceptedLocked(accepted.request.id, requested)
        } else {
            admitSchema2Locked(item.id, requested)
        }
    }

    suspend fun resume(id: String) = host.commands.withLock {
        requireAdmission()
        val before = repository.physicalDownload(id) ?: return@withLock
        runtime.requireExecutableProducer(before)
        // Network Retry must not consume a retained FAILED file. This used to reconcile the
        // asset-backed job to QUEUED, which neither transfer nor local validation can admit.
        if (before.job.assetId != null && before.job.status == DownloadStatus.FAILED)
            fail(FailureCode.RECOVERY_REQUIRED, "download_retained_local_validation_required")
        // A completed-check host hold is cleared only by resumePending(), the real foreground
        // announcement. Ordinary Resume must not spend the network Retry lane's retry flag as a
        // foreground authority and silently erase the durable marker.
        if (before.job.assetId != null && before.job.status == DownloadStatus.NEEDS_FOREGROUND)
            fail(FailureCode.RECOVERY_REQUIRED, "download_foreground_announcement_required")
        requireAdmission()
        updateIntentLocked(before, DownloadBatchIntent.ACTIVE, retain = true, retry = true)
        changed(id)
    }

    /**
     * Explicit replacement for UNKNOWN migrated provenance only. The selection was already
     * accepted by the root; this operation never resolves a title. Cancellation after the first
     * phase leaves the old owner cancelled/nonretaining, not eligible for an automatic revival.
     */
    suspend fun adoptSchema2(id: String, requestId: AcquisitionRequestId, bindingId: ExecutionBindingId,
        requireIntentCurrent: () -> Unit): String {
        data class Pending(val join: PhysicalDownloadJoin, val epoch: DownloadOwnershipEpochs.Epoch)
        val pending = host.commands.withLock {
            requireAdmission()
            requireIntentCurrent()
            val before = repository.physicalDownload(id) ?: fail(FailureCode.NOT_FOUND, "download_job_missing")
            val old = before.job.producer as? PhysicalDownloadProducer.Schema2
                ?: fail(FailureCode.INVALID_STATE, "download_unknown_legacy_required")
            if (old.recordingId != null || before.claims.any { it.owner is DownloadClaimOwner.BatchMember })
                fail(FailureCode.INVALID_STATE, "download_unknown_legacy_required")
            // Reject an invalid replacement BEFORE accepting destructive cleanup intent.
            val view = host.acquisitionView()
            val selected = view.acquisition.requests[requestId]
            val binding = view.acquisition.bindings[bindingId]
            if (selected == null || selected.state != AcquisitionJobState.COMMITTED ||
                (selected.outcome as? AcquisitionOutcome.Single)?.bindingId != bindingId ||
                (selected.request.metadata as? MediaCapture)?.media?.id != old.mediaId ||
                binding == null || binding.origin != ExecutionBindingOrigin.TYPED || binding.subjectId != old.subjectId ||
                binding.recordingId == null)
                fail(FailureCode.INVALID_STATE, "download_adoption_selection_mismatch")
            requireAdmission()
            requireIntentCurrent()
            val updated = updateIntentLocked(before, DownloadBatchIntent.CANCELLED, retain = false, retry = false)
            val cancelled = committed(repository.reconcilePhysicalDownloadIntent(ReconcilePhysicalDownloadIntent(id,
                updated.job.producer, updated.job.ownershipGeneration, updated.claims, clock.epochMs())))
            val epoch = host.activeLock.withLock { runtime.observePhysicalEpoch(cancelled) }
            changed(id)
            Pending(cancelled, epoch)
        }
        try {
            val stopped = runtime.epochs.drain(pending.epoch)
            val cleanup = runtime.cleanupStoppedStandalone(pending.join)
            if (cleanup == null && !stopped || cleanup != null && !runtime.epochs.drain(cleanup))
                fail(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_unconfirmed")
            val settled = cleanup ?: pending.epoch
            when (val durable = runtime.epochs.durableSettlement(settled)) {
                is AcquisitionDownloadDurableSettlement.Unconfirmed -> throw VeyraException(durable.failure)
                AcquisitionDownloadDurableSettlement.Stale -> fail(FailureCode.RECOVERY_REQUIRED, "download_checkpoint_stale")
                else -> Unit // No-resource observation still had the positive canonical intent CAS above.
            }
            val drained = host.commands.withLock {
                requireAdmission()
                requireIntentCurrent()
                val current = repository.physicalDownload(id) ?: fail(FailureCode.NOT_FOUND, "download_job_missing")
                // A consumed cleanup maintenance legitimately advances ownershipGeneration
                // (its claim fence). The exact producer/claim set and the later adopt CAS still
                // reject a concurrent transfer owner; only a regression to an older generation
                // is impossible here.
                if (current.job.producer != pending.join.job.producer ||
                    current.job.ownershipGeneration < settled.physicalOwnershipGeneration ||
                    current.claims.size != pending.join.claims.size || current.claims.toSet() != pending.join.claims.toSet())
                    fail(FailureCode.INVALID_STATE, "download_adoption_owner_changed")
                runtime.requireClearedSchema2(current)
                current
            }
            // This is the authoritative player's drain, not a second player/native owner.
            // It is required even without an old MaterializationAsset, and outside BOTH locks.
            dependencies.beforePhysicalAdoption(drained.job.producer as PhysicalDownloadProducer.Schema2)
            currentCoroutineContext().ensureActive()
            requireIntentCurrent()
            val admitted = runtime.adoptAfterReaderDrain(drained, requestId, bindingId, requireIntentCurrent)
            if (admitted.requestedContext.request.id != requestId || admitted.requestedContext.binding.id != bindingId ||
                admitted.claim.id != drained.claims.single { it.owner == DownloadClaimOwner.Schema2Standalone(id) }.id ||
                admitted.physical.job.id != id || admitted.physical.job.ownershipGeneration != drained.job.ownershipGeneration + 1 ||
                admitted.physical.job.producer != PhysicalDownloadProducer.Typed(admitted.requestedContext.subject.id,
                    bindingId, admitted.requestedContext.recording.id))
                fail(FailureCode.RECOVERY_REQUIRED, "download_adoption_commit_mismatch")
            changed(id)
            return id
        } finally { host.signalWork() }
    }

    /** Intent is accepted under commands; EVERY actual writer/native join is outside that lock. */
    suspend fun stop(id: String, discard: Boolean, remove: Boolean) {
        data class Pending(val epoch: DownloadOwnershipEpochs.Epoch, val join: PhysicalDownloadJoin)
        val pending = host.commands.withLock {
            currentCoroutineContext().ensureActive()
            requireOpen()
            val before = repository.physicalDownload(id) ?: return@withLock null
            val updated = updateIntentLocked(before, if (discard) DownloadBatchIntent.CANCELLED else DownloadBatchIntent.PAUSED,
                retain = !remove, retry = false)
            val intent = committed(repository.reconcilePhysicalDownloadIntent(ReconcilePhysicalDownloadIntent(id,
                updated.job.producer, updated.job.ownershipGeneration, updated.claims, clock.epochMs())))
            val epoch = if (summarizeDownloadClaims(id, intent.claims).demand in STOP_DEMANDS)
                host.activeLock.withLock { runtime.observePhysicalEpoch(intent) } else null
            changed(id)
            epoch?.let { Pending(it, intent) }
        }
        try {
            if (pending != null) {
                // An external/failed owner can refuse this ACK. A separately admitted cleanup
                // may still prove expired native path ownership; no retained close is retried.
                val stopped = runtime.epochs.drain(pending.epoch)
                val cleaned = if (discard) runtime.cleanupStoppedStandalone(pending.join) else null
                // Never look up/cancel "latest": another owner may already have admitted a
                // newer generation. ACK only the original epoch or OUR exact cleanup epoch.
                val settled = cleaned ?: pending.epoch
                if (cleaned == null && !stopped || cleaned != null && !runtime.epochs.drain(cleaned))
                    fail(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_unconfirmed")
                if (discard && cleaned == null && stopped && pending.join.asset != null &&
                    pending.join.job.failure != null &&
                    repository.physicalDownload(id)?.job?.cleanupPending == true) {
                    val state = host.state()
                    if (state.suspended || state.failure != null)
                        fail(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_unconfirmed")
                    awaitRemoval(id)
                }
                when (val durable = runtime.epochs.durableSettlement(settled)) {
                    is AcquisitionDownloadDurableSettlement.Unconfirmed -> throw VeyraException(durable.failure)
                    AcquisitionDownloadDurableSettlement.Stale -> fail(FailureCode.RECOVERY_REQUIRED, "download_checkpoint_stale")
                    else -> Unit // A detached idle epoch has no finalizer; the intent CAS above is positive.
                }
            }
        } catch (error: CancellationException) {
            // A gate expiration cancels the maintenance child after the removal intent was
            // durably accepted. If the caller itself is still active, observe the exact durable
            // cleanup outcome; a future foreground retry may complete the actual deletion.
            if (pending == null || !currentCoroutineContext().isActive) throw error
            if (repository.physicalDownload(id)?.job?.cleanupPending == true) awaitRemoval(id, allowMissingOwnerWait = true)
        } finally { host.signalWork() }
    }

    /** Bounded truthful removal observation; never waits for an already-consumed owner. */
    private suspend fun awaitRemoval(id: String, allowMissingOwnerWait: Boolean = false) {
        host.state().failure?.let { throw VeyraException(it) }
        runtime.awaitRemovalOutcome(id, allowMissingOwnerWait)?.let { throw VeyraException(it) }
        if (repository.physicalDownload(id)?.job?.cleanupPending == true)
            fail(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_unconfirmed")
    }

    private suspend fun admitAcceptedLocked(requestId: AcquisitionRequestId, requiredBindingId: ExecutionBindingId): String {
        val admitted = committed(withContext(NonCancellable) {
            repository.admitStandalonePhysicalDownload(AdmitStandalonePhysicalDownload(requestId, requiredBindingId,
                newEntityId("download"), DownloadClaimId(newEntityId("download-claim")), clock.epochMs()))
        })
        if (admitted.requestedContext.request.id != requestId || admitted.requestedContext.binding.id != requiredBindingId ||
            admitted.claim.physicalJobId != admitted.physical.job.id ||
            admitted.claim.owner !is DownloadClaimOwner.Standalone && admitted.claim.owner !is DownloadClaimOwner.Schema2Standalone ||
            admitted.requestedContext.subject.id != admitted.physical.job.producer.subjectId ||
            admitted.requestedContext.recording.id != admitted.physical.job.producer.recordingId ||
            admitted.physical.claims.singleOrNull { it.id == admitted.claim.id } != admitted.claim)
            fail(FailureCode.RECOVERY_REQUIRED, "download_standalone_admission_mismatch")
        changed(admitted.physical.job.id)
        return admitted.physical.job.id
    }

    private suspend fun admitSchema2Locked(mediaId: String, bindingId: ExecutionBindingId): String {
        val admitted = committed(withContext(NonCancellable) {
            repository.admitSchema2StandalonePhysicalDownload(AdmitSchema2StandalonePhysicalDownload(mediaId, bindingId,
                newEntityId("download"), DownloadClaimId(newEntityId("download-claim")), clock.epochMs()))
        })
        // A saved schema2 R2 may reuse a Typed physical R1 for the same subject/C. Neither the
        // actual producer nor a preexisting standalone owner is relabelled as schema2 here.
        val producer = admitted.physical.job.producer
        val view = host.acquisitionView()
        if (admitted.binding.mediaId != mediaId || admitted.binding.id != bindingId.value || producer.subjectId.value != mediaId ||
            producer.recordingId == null || admitted.binding.selectedVideoId !=
                view.acquisition.recordings[producer.recordingId]?.canonicalId ||
            admitted.claim.physicalJobId != admitted.physical.job.id ||
            admitted.claim.owner != DownloadClaimOwner.Schema2Standalone(admitted.physical.job.id) &&
                admitted.claim.owner != DownloadClaimOwner.Standalone(admitted.physical.job.id) ||
            admitted.physical.claims.singleOrNull { it.id == admitted.claim.id } != admitted.claim)
            fail(FailureCode.RECOVERY_REQUIRED, "download_standalone_admission_mismatch")
        runtime.requireExecutableProducer(admitted.physical)
        changed(admitted.physical.job.id)
        return admitted.physical.job.id
    }

    private suspend fun updateIntentLocked(before: PhysicalDownloadJoin, intent: DownloadBatchIntent,
        retain: Boolean, retry: Boolean): PhysicalDownloadJoin {
        val claim = before.claims.singleOrNull { it.owner == DownloadClaimOwner.Standalone(before.job.id) ||
            it.owner == DownloadClaimOwner.Schema2Standalone(before.job.id) }
            ?: fail(FailureCode.INVALID_STATE, "download_standalone_claim_missing")
        return committed(withContext(NonCancellable) {
            repository.updateStandaloneDownloadIntent(UpdateStandaloneDownloadIntent(before.job.id, before.job.producer,
                before.job.ownershipGeneration, claim.id, claim.generation, claim.ownerIntentRevision,
                intent, retain, clock.epochMs(), allowRetryFailure = retry))
        })
    }

    private suspend fun requireAdmission() {
        currentCoroutineContext().ensureActive()
        requireOpen()
        host.state().failure?.let { throw VeyraException(it) }
        dependencies.requireOwnerAdmission()
    }

    private fun requireOpen() {
        if (host.state().closed) fail(FailureCode.INVALID_STATE, "download_executor_closed")
    }

    private fun changed(id: String) {
        runtime.clearPhysicalClaimDeferrals(id)
        host.changed()
        host.signalWork()
    }

    private fun <T> committed(result: PhysicalDownloadCommit<T>): T = when (result) {
        is PhysicalDownloadCommit.Committed -> result.value
        PhysicalDownloadCommit.Stale -> fail(FailureCode.INVALID_STATE, "download_standalone_action_stale")
    }

    private fun fail(code: FailureCode, detail: String): Nothing = throw VeyraException(AppFailure(code, detail))
    private companion object {
        val STOP_DEMANDS = setOf(DownloadClaimDemand.PAUSE_AND_RETAIN,
            DownloadClaimDemand.CANCEL_UNFINISHED_KEEP_COMPLETE, DownloadClaimDemand.REMOVE_CANDIDATE)
    }
}
