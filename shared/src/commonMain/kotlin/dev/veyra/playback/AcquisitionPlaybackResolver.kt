package dev.veyra.playback

import dev.veyra.data.*
import dev.veyra.downloads.*
import dev.veyra.model.*
import dev.veyra.source.execution.AcquisitionExecutionContext
import dev.veyra.source.execution.AcquisitionExecutionGateway
import dev.veyra.source.execution.AcquisitionPlaybackSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

/** Detached local comparison input, not permission to skip the final actor/native admission. */
data class AcquisitionPlaybackPreparation(
    val source: AcquisitionPlaybackSource,
    val validatedMaterialization: MaterializationAsset? = null,
) {
    // Validation belongs after the controller has accepted disposal ownership. A malformed
    // gateway response must not throw here and strand its already-open central resource.
    override fun toString() = "AcquisitionPlaybackPreparation(local=${source.isLocal},identity=redacted)"
}

/**
 * Offline-first preparation on the SAME canonical library/files/native validator. It has no
 * source selection, transfer loop or coroutine scope. The controller owns every invocation and
 * waits it during removal/shutdown. A native local reader is still fenced by AudioEngine: the
 * temporary OS lease below protects hashing/validation, not an inferred native FD-close receipt.
 */
class AcquisitionPlaybackResolver(
    private val store: LibraryStore,
    private val gateway: AcquisitionExecutionGateway,
    private val files: DownloadFiles,
    private val validator: LocalMediaValidator,
    private val materializationEvidence: DownloadMaterializationEvidence,
) : LibraryTransactionOwned {
    override val transactionOwner: LibraryTransactionOwner get() = store.transactionOwner
    init {
        require(materializationEvidence.transactionOwner === transactionOwner) {
            "Local playback and materialization evidence require the same Room transaction owner"
        }
    }
    private val cleanup = DownloadCleanupRegistry()
    private class RetainedValidationReader(
        val failure: LocalMediaValidationReaderUnacknowledged,
        val lease: DownloadLeaseCleanup,
    )
    private val retainedValidationReaders = MutableStateFlow<List<RetainedValidationReader>>(emptyList())
    private var closed = false // Controller closes only after all its registered resolve jobs join.

    suspend fun resolve(
        context: AcquisitionExecutionContext,
        capabilities: EngineCapabilities,
        offlineOnly: Boolean,
        requiredRepresentationId: String? = null,
        localOnly: Boolean = false,
    ): AcquisitionPlaybackPreparation {
        currentCoroutineContext().ensureActive()
        checkOpen()
        val view = store.acquisitionView()
        requireContext(view.acquisition, context)
        requireNoCleanup(view.acquisition, context)
        val allUses = view.acquisition.assetUses.filter { it.requestedBindingId == context.binding.id }
        val uses = if (localOnly) allUses.filter { it.compatibilityPolicyVersion == 1 } else allUses
        val candidates = uses.mapNotNull { use -> view.acquisition.materializations[use.materializationAssetId] }
            .distinctBy { it.id }
        if (candidates.isNotEmpty()) {
            // Saved intent selects only the exact receipt/job/use that currently verifies. A stale
            // sibling use never wins by lowest ID, and more than one verified candidate is ambiguous.
            val asset = if (localOnly) verifiedLocalCandidate(view.acquisition, context, candidates)
            else candidates.sortedBy { it.id.value }.first().also { requireLocal(view.acquisition, context, it) }
            if (requiredRepresentationId != null && requiredRepresentationId != asset.representationId) fail(FailureCode.INVALID_STATE, "offline_representation_changed")
            if (!capabilities.supportsLocalFiles) fail(FailureCode.UNSUPPORTED_TRANSPORT, "local_files_not_supported")
            if (!capabilities.supports(StreamTransport.PROGRESSIVE, asset.mimeType, asset.codecs)) fail(FailureCode.UNSUPPORTED_CODEC, "offline_codec_not_supported")
            val scope = DownloadLeaseCleanupScope(cleanup)
            // NonCancellable registers an acquired handle before a dispatch-return cancellation
            // can hide it from the finally below. No native owner exists outside this registry.
            var acquired: DownloadLeaseCleanup? = null
            var retainedReader: RetainedValidationReader? = null
            val duration: Long?
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    val lease = files.tryLeasePaths(listOf(asset.uri), scope)
                        ?: fail(FailureCode.INVALID_STATE, "offline_file_busy")
                    acquired = DownloadLeaseCleanup(listOf(lease)).also(cleanup::register)
                }
                val rejoined = store.acquisitionView()
                val physical = requireLocal(rejoined.acquisition, context, asset)
                val attempt = materializationEvidence.admit(physical.id, asset, context.binding.id, context.request.id)
                duration = try {
                    files.validateMaterialization(asset.validationInput(), validator) { negative ->
                        // Authoritative physical revocation cannot depend on a later actor result,
                        // intent/navigation generation or cleanup success. No member is invented.
                        materializationEvidence.reject(attempt, negative)
                    } ?: asset.measuredDurationMs
                } catch (failure: LocalMediaValidationReaderUnacknowledged) {
                    // The reader may still own bytes even if its reported input is malformed.
                    // Retain before the encompassing finally can touch the caller's exact lease.
                    retainedReader = RetainedValidationReader(failure, requireNotNull(acquired)).also { owner ->
                        retainedValidationReaders.update { it + owner }
                    }
                    throw failure
                }
                requireLocal(store.acquisitionView().acquisition, context, asset)
                currentCoroutineContext().ensureActive()
            } finally {
                // Register before fallible close. No implicit retry after any unknown native result.
                withContext(NonCancellable + Dispatchers.IO) {
                    acquired?.let { owned ->
                        // A thrown decoder teardown is not a file-release receipt. The strong
                        // native holder and OS path lease survive together until explicit close.
                        if (retainedReader != null) return@let
                        try { owned.close(); cleanup.remove(owned) }
                        catch (_: Throwable) { fail(FailureCode.STORAGE_IO, "offline_file_lease_release") }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            return AcquisitionPlaybackPreparation(AcquisitionPlaybackSource(context, asset.uri, StreamTransport.PROGRESSIVE,
                asset.mimeType, asset.codecs, requireNotNull(asset.representationId), durationMs = duration,
                seekableHint = true, isLocal = true, materializationAssetId = asset.id), asset)
        }
        if (localOnly) fail(FailureCode.INVALID_STATE, "saved_local_authority_lost")
        if (offlineOnly || store.acquisitionViews.value.snapshot.settings.offlineOnly) fail(FailureCode.OFFLINE_POLICY, "offline_materialization_unavailable")
        currentCoroutineContext().ensureActive()
        // This prepares the already-accepted raw-first C. It cannot acquire/search again.
        val source = gateway.preparePlayback(context, capabilities, requiredRepresentationId)
        // Ownership passes to the controller even if policy changed during this await. It disposes
        // rejected/unhanded sources through its failure-retaining resource registry, never here.
        return AcquisitionPlaybackPreparation(source)
    }

    /**
     * Pre-activation saved-intent fence. Reads the current Room graph, before any queue mutation,
     * and requires the single exact compatibility-v1 use/asset/receipt/job for [bindingId].
     * This does not grant playback; the resolver and native worker still revalidate after activation.
     */
    suspend fun requireExactLocalAuthority(bindingId: ExecutionBindingId) {
        currentCoroutineContext().ensureActive()
        checkOpen()
        val view = store.acquisitionView()
        val context = view.acquisition.executionContext(bindingId)
        requireContext(view.acquisition, context)
        requireNoCleanup(view.acquisition, context)
        val candidates = view.acquisition.assetUses
            .filter { it.requestedBindingId == bindingId && it.compatibilityPolicyVersion == 1 }
            .mapNotNull { use -> view.acquisition.materializations[use.materializationAssetId] }
            .distinctBy { it.id }
        val verified = verifiedLocalCandidate(view.acquisition, context, candidates)
        requireLocal(view.acquisition, context, verified)
    }

    /**
     * Exactly one candidate must currently pass the full receipt/job/claim/use guard. A stale use
     * beside a valid newer materialization is not silently selected; two valid candidates fail.
     */
    private fun verifiedLocalCandidate(state: AcquisitionRepositoryState, context: AcquisitionExecutionContext,
        candidates: List<MaterializationAsset>): MaterializationAsset {
        val verified = candidates.mapNotNull { asset ->
            try { requireLocal(state, context, asset); asset }
            catch (_: VeyraException) { null }
        }
        if (verified.size != 1) {
            fail(FailureCode.INVALID_STATE,
                if (verified.isEmpty()) "saved_local_authority_lost" else "saved_local_asset_ambiguous")
        }
        return verified.single()
    }

    /** Synchronous coherent final guard for the ordered native worker; never a Room write. */
    fun requireAdmissible(prepared: AcquisitionPlaybackPreparation) {
        checkOpen()
        if (prepared.source.isLocal != (prepared.validatedMaterialization != null) ||
            prepared.source.materializationAssetId != prepared.validatedMaterialization?.id) {
            fail(FailureCode.INVALID_STATE, "playback_materialization_changed")
        }
        val view = store.acquisitionViews.value
        requireContext(view.acquisition, prepared.source.context)
        requireNoCleanup(view.acquisition, prepared.source.context)
        if (prepared.source.isLocal) requireLocal(view.acquisition, prepared.source.context,
            requireNotNull(prepared.validatedMaterialization))
        else if (view.snapshot.settings.offlineOnly) fail(FailureCode.OFFLINE_POLICY, "offline_policy_changed")
    }

    /** No retry of retained ambiguous OS releases. Controller invokes after all job/native drains. */
    suspend fun close() {
        closed = true
        retainedReaderFailure()?.let { throw VeyraException(it) }
        if (cleanup.owners.value.isNotEmpty()) fail(FailureCode.STORAGE_IO, "offline_file_lease_release")
    }

    /**
     * Called by the existing controller's removal fence after matching resolve jobs have joined.
     * Reader-only failure vetoes deletion, not unrelated transport. No native teardown is retried.
     */
    internal suspend fun retainedReaderFailure(): AppFailure? {
        // Matching resolve jobs are drained first; unrelated active validators remain untouched.
        // An explicit close/removal may consume only a positive release, not retry an unknown one.
        withContext(NonCancellable + Dispatchers.IO) {
            retainedValidationReaders.value.forEach { owner ->
                owner.lease.lock.withLock {
                    if (retainedValidationReaders.value.none { it === owner }) return@withLock
                    val released = try { owner.failure.reader.isReleased() } catch (_: Throwable) { false }
                    if (released && !owner.lease.failed.value) {
                        try {
                            owner.lease.close()
                            cleanup.remove(owner.lease)
                            retainedValidationReaders.update { it.filterNot { candidate -> candidate === owner } }
                        } catch (_: Throwable) { /* The registry retains the original failed OS owner. */ }
                    }
                }
            }
        }
        return when {
            retainedValidationReaders.value.isNotEmpty() -> AppFailure(FailureCode.STORAGE_IO, "local_validation_reader_unacknowledged")
            cleanup.owners.value.values.any { it.failed.value } -> AppFailure(FailureCode.STORAGE_IO, "offline_file_lease_release")
            else -> null
        }
    }

    private fun checkOpen() {
        if (closed) fail(FailureCode.INVALID_STATE, "playback_resolver_closed")
        if (retainedValidationReaders.value.isNotEmpty()) fail(FailureCode.STORAGE_IO, "local_validation_reader_unacknowledged")
        if (cleanup.owners.value.values.any { it.failed.value }) fail(FailureCode.STORAGE_IO, "offline_file_lease_release")
    }

    private fun requireContext(state: AcquisitionRepositoryState, context: AcquisitionExecutionContext) {
        if (state.executionContext(context.binding.id) != context) fail(FailureCode.INVALID_STATE, "playback_binding_changed")
    }

    private fun requireNoCleanup(state: AcquisitionRepositoryState, context: AcquisitionExecutionContext) {
        if (state.physicalDownloads.values.any { it.producer.subjectId == context.subject.id &&
                (it.producer.recordingId == null || it.producer.recordingId == context.recording.id) &&
                (it.cleanupPending || it.maintenanceKind == PhysicalDownloadMaintenanceKind.CLEANUP) }) {
            fail(FailureCode.INVALID_STATE, "offline_asset_removal_pending")
        }
    }

    private fun requireLocal(state: AcquisitionRepositoryState, context: AcquisitionExecutionContext,
        asset: MaterializationAsset): PhysicalDownloadJob {
        requireContext(state, context)
        requireNoCleanup(state, context)
        val use = state.assetUses.singleOrNull { it.requestedBindingId == context.binding.id && it.materializationAssetId == asset.id }
        val receipt = state.materializationReceipts[asset.id]
        val jobs = state.physicalDownloads.values.filter { it.assetId == asset.id }
        val job = jobs.singleOrNull()
        val claims = job?.let { owner -> state.claims.values.filter { it.physicalJobId == owner.id } }.orEmpty()
        if (use?.compatibilityPolicyVersion != 1 || state.materializations[asset.id] != asset ||
            asset.producer.subjectId != context.subject.id || asset.producer.recordingId != context.recording.id ||
            receipt == null || receipt.producer != asset.producer || receipt.sha256 != asset.sha256 || receipt.sizeBytes != asset.sizeBytes ||
            receipt.representationId != asset.representationId || receipt.validatorGeneration != asset.validatorGeneration ||
            receipt.ownershipGeneration != asset.ownershipGeneration || receipt.materializerAttemptId != asset.materializerAttemptId ||
            receipt.materializerDownloadId != asset.materializerDownloadId || receipt.kind != asset.kind ||
            receipt.mimeType != asset.mimeType || receipt.codecs != asset.codecs || receipt.completedEpochMs != asset.completedEpochMs ||
            receipt.measuredDurationMs != asset.measuredDurationMs ||
            job == null || job.producer != asset.producer || job.status != DownloadStatus.COMPLETE ||
            job.cleanupPending || job.maintenanceKind != null || job.leaseOwner != null ||
            job.id in state.physicalFinalizations || job.representationId != asset.representationId ||
            job.validatorGeneration != asset.validatorGeneration || claims.none { it.requestedBindingId == context.binding.id &&
                (it.intent != DownloadBatchIntent.CANCELLED || it.retainCompletedAsset) } ||
            asset.kind != OfflineAssetKind.FILE || asset.representationId.isNullOrBlank()) {
            fail(FailureCode.CORRUPT_MEDIA, "offline_materialization_binding_mismatch")
        }
        return job
    }

    private fun fail(code: FailureCode, detail: String): Nothing = throw VeyraException(AppFailure(code, detail))
}
