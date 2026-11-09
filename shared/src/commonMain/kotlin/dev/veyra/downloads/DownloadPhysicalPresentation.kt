package dev.veyra.downloads

import dev.veyra.data.AcquisitionRepositoryState
import dev.veyra.data.LibraryAcquisitionView
import dev.veyra.model.*

/** A single canonical Room view and the process-only checking projection derived from THAT view. */
data class DownloadAcquisitionPresentation(
    val view: LibraryAcquisitionView,
    val states: Map<String, PhysicalDownloadJob>,
    /** Historical process-only physical checks from the same atomic input as [states]; never local grants. */
    val successfulChecks: Map<String, DownloadMaterializationCheck> = emptyMap(),
)

internal fun projectAcquisitionDownloadPresentation(view: LibraryAcquisitionView,
    checked: DownloadMaterializationChecks, host: DownloadPhysicalHostState): DownloadAcquisitionPresentation {
    val state = view.acquisition
    val attempts = state.physicalAttempts.values.groupBy { it.downloadId }
    // Readiness-only transitions AND actual negative rechecks retain historical C0. Only the
    // independently paired currentValidAssets can confer physical completion below; history is
    // not readiness, an R2 use, a raw local grant or an operation/native/lease-drain receipt.
    val successful = checked.successfulChecks.filter { (id, check) ->
        val job = state.physicalDownloads[id]
        val asset = job?.assetId?.let(state.materializations::get)
        job != null && job.id == id && check.downloadId == id && asset == check.asset &&
            physicalMaterializationHistoryFailure(job, asset, attempts[id].orEmpty()) == null
    }
    return DownloadAcquisitionPresentation(view, projectPhysicalDownloadStates(view.acquisition, checked, host),
        successful)
}

/** Compatibility callers can project checked assets, but do not fabricate new runtime refs. */
internal fun projectAcquisitionDownloadPresentation(view: LibraryAcquisitionView,
    checked: Map<String, MaterializationAsset>, host: DownloadPhysicalHostState): DownloadAcquisitionPresentation =
    DownloadAcquisitionPresentation(view, projectPhysicalDownloadStates(view.acquisition, checked, host))

/** Pure identity check shared by native-validation admission and current-process presentation. */
internal fun physicalMaterializationIdentityFailure(job: PhysicalDownloadJob,
    finalization: PhysicalDownloadFinalization?, asset: MaterializationAsset?,
    attempts: Collection<PhysicalDownloadAttempt>): AppFailure? {
    if (job.cleanupPending || finalization != null)
        return AppFailure(FailureCode.CORRUPT_MEDIA, "download_materialization_identity_changed")
    return physicalMaterializationHistoryFailure(job, asset, attempts)
}

/** Immutable identity only, NOT readiness. Kept separate so transient fences cannot mint proof. */
private fun physicalMaterializationHistoryFailure(job: PhysicalDownloadJob, asset: MaterializationAsset?,
    attempts: Collection<PhysicalDownloadAttempt>): AppFailure? {
    fun invalid(detail: String) = AppFailure(FailureCode.CORRUPT_MEDIA, detail)
    if (asset == null || job.assetId != asset.id || job.producer != asset.producer ||
        asset.representationId.isNullOrBlank() || job.representationId != asset.representationId ||
        job.validatorGeneration != asset.validatorGeneration || asset.ownershipGeneration > job.ownershipGeneration ||
        asset.materializerDownloadId?.let { it != job.id } == true)
        return invalid("download_materialization_identity_changed")
    if (asset.producer is PhysicalDownloadProducer.Typed && (asset.materializerDownloadId == null ||
        asset.materializerAttemptId == null || asset.ownershipGeneration <= 0))
        return invalid("download_materializer_history_missing")
    asset.materializerAttemptId?.let { id ->
        val original = attempts.singleOrNull { it.id == id } ?: return invalid("download_materializer_history_missing")
        if (original.downloadId != job.id || original.producer != asset.producer ||
            original.ownershipGeneration != asset.ownershipGeneration || original.representationId != asset.representationId ||
            original.validatorGeneration != asset.validatorGeneration)
            return invalid("download_materializer_history_changed")
    }
    return null
}

/** Current validity only: historical C0 survives a negative but cannot make any binding Ready. */
internal fun checkedPhysicalDownloadIds(state: AcquisitionRepositoryState,
    checked: DownloadMaterializationChecks): Set<String> = checkedPhysicalDownloadIds(state, checked.assets)

internal fun checkedPhysicalDownloadIds(state: AcquisitionRepositoryState,
    checked: Map<String, MaterializationAsset>): Set<String> {
    val attempts = state.physicalAttempts.values.groupBy { it.downloadId }
    return state.physicalDownloads.values.filter { job ->
        val asset = job.assetId?.let(state.materializations::get)
        asset != null && checked[job.id] == asset && physicalMaterializationIdentityFailure(job,
            state.physicalFinalizations[job.id], asset, attempts[job.id].orEmpty()) == null
    }.mapTo(hashSetOf()) { it.id }
}

/**
 * Physical completion only. An R2/batch member still needs its own exact AssetUse; this cannot
 * make a partially downloaded batch Ready or manufacture a catalogue item for a neutral subject.
 */
internal fun projectPhysicalDownloadStates(state: AcquisitionRepositoryState,
    checked: DownloadMaterializationChecks, host: DownloadPhysicalHostState): Map<String, PhysicalDownloadJob> =
    projectPhysicalDownloadStates(state, checked.assets, host)

internal fun projectPhysicalDownloadStates(state: AcquisitionRepositoryState,
    checked: Map<String, MaterializationAsset>, host: DownloadPhysicalHostState): Map<String, PhysicalDownloadJob> {
    val checkedIds = checkedPhysicalDownloadIds(state, checked)
    return state.physicalDownloads.mapValues { (id, job) ->
        if (job.status != DownloadStatus.COMPLETE || job.cleanupPending || id in checkedIds) job
        else if (!DownloadPhysicalClaimRules.knownProducer(job)) job.copy(status = DownloadStatus.NEEDS_FOREGROUND,
            failure = AppFailure(FailureCode.RECOVERY_REQUIRED, "download_legacy_acquisition_required"))
        else if (host.suspended || host.failure != null || DownloadPhysicalClaimRules.backgroundBlocked(job))
            job.copy(status = DownloadStatus.NEEDS_FOREGROUND,
                failure = host.failure ?: AppFailure(FailureCode.BACKGROUND_RESTRICTED))
        else job.copy(status = DownloadStatus.VERIFYING)
    }
}

/** Project only real preexisting schema2 rows; never adapt a typed/neutral asset to OfflineAsset. */
internal fun projectLegacyPhysicalDownloads(view: LibraryAcquisitionView,
    checked: DownloadMaterializationChecks, host: DownloadPhysicalHostState): AppSnapshot =
    projectLegacyPhysicalDownloads(view, checked.assets, host)

internal fun projectLegacyPhysicalDownloads(view: LibraryAcquisitionView,
    checked: Map<String, MaterializationAsset>, host: DownloadPhysicalHostState): AppSnapshot {
    val physical = projectPhysicalDownloadStates(view.acquisition, checked, host)
    return view.snapshot.copy(downloads = view.snapshot.downloads.mapValues { (id, record) ->
        val canonical = physical[id]
        val producer = canonical?.producer as? PhysicalDownloadProducer.Schema2
        val asset = canonical?.assetId?.let(view.acquisition.materializations::get)
        val projectedAsset = record.asset
        val matches = canonical != null && producer != null && producer.mediaId == record.mediaId &&
            producer.bindingId?.value == record.executionBindingId && canonical.cleanupPending == record.cleanupPending &&
            canonical.representationId == record.representationId && canonical.validatorGeneration == record.validatorGeneration &&
            (asset == null && projectedAsset == null || asset != null && projectedAsset != null &&
                asset.id.value == projectedAsset.id && asset.uri == projectedAsset.uri && asset.sizeBytes == projectedAsset.sizeBytes &&
                asset.producer == producer && asset.producer.bindingId?.value == projectedAsset.executionBindingId &&
                projectedAsset.mediaId == producer.mediaId && asset.kind == projectedAsset.kind &&
                asset.mimeType == projectedAsset.mimeType && asset.codecs == projectedAsset.codecs && asset.sha256 == projectedAsset.sha256 &&
                asset.representationId == projectedAsset.representationId && asset.validatorGeneration == projectedAsset.validatorGeneration)
        if (!matches) record.copy(status = DownloadStatus.NEEDS_FOREGROUND,
            failure = AppFailure(FailureCode.RECOVERY_REQUIRED, "download_legacy_projection_mismatch"))
        else record.copy(status = checkNotNull(canonical).status, failure = canonical.failure)
    })
}
