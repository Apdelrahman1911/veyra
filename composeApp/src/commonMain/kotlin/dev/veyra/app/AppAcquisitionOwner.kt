package dev.veyra.app

import dev.veyra.acquisition.AcquisitionAdmissionFactory
import dev.veyra.acquisition.AcquisitionCaptureFactory
import dev.veyra.acquisition.AcquisitionCoordinator
import dev.veyra.acquisition.AcquisitionDownloadAdapter
import dev.veyra.acquisition.AcquisitionDownloadExecutor
import dev.veyra.acquisition.CollectionTraversalCoordinator
import dev.veyra.acquisition.DownloadBatchCoordinator
import dev.veyra.acquisition.QueueAcquisitionCoordinator
import dev.veyra.data.LibraryAcquisitionView
import dev.veyra.data.LibraryStore
import dev.veyra.data.LibraryTransactionOwned
import dev.veyra.data.executionContext
import dev.veyra.downloads.DownloadAcquisitionDependencies
import dev.veyra.downloads.DownloadFiles
import dev.veyra.downloads.DownloadMaterializationEvidence
import dev.veyra.model.*
import dev.veyra.playback.AcquisitionPlaybackResolver
import dev.veyra.playback.Clock
import dev.veyra.playback.LocalMediaValidator
import dev.veyra.playback.PlaybackAcquisitionServices

/**
 * The one composition of the canonical Room owner and the policy-wrapped central kmpYT
 * facade. It owns no second coroutine scheduler, player, database, transfer or source client.
 * UI, the playback actor and cold durable download workers all receive these SAME instances.
 */
class AppAcquisitionOwner internal constructor(
    private val store: LibraryStore,
    private val gateway: PolicyMediaGateway,
    private val files: DownloadFiles,
    private val validator: LocalMediaValidator,
    private val clock: Clock,
    offline: () -> Boolean,
) : LibraryTransactionOwned {
    override val transactionOwner get() = store.transactionOwner
    val repository = store.acquisition
    val captures = AcquisitionCaptureFactory(clock)
    val admissions = AcquisitionAdmissionFactory(captures, clock)
    val acquisition = AcquisitionCoordinator(repository, gateway, captures, clock, offline)
    val traversal = CollectionTraversalCoordinator(repository, gateway, clock, offline)
    val queues = QueueAcquisitionCoordinator(repository, acquisition)
    val batches = DownloadBatchCoordinator(repository, acquisition, clock)
    val materializationEvidence = DownloadMaterializationEvidence(transactionOwner)
    val playbackResolver = AcquisitionPlaybackResolver(store, gateway, files, validator, materializationEvidence)
    val playbackServices = PlaybackAcquisitionServices(
        repository, acquisition, queues, playbackResolver, captures, admissions,
    )

    init {
        require(repository.transactionOwner === transactionOwner)
    }

    /** Room-only bootstrap: no source lookup, artwork, hashing, native gate or playback. */
    suspend fun initialize() {
        acquisition.restore()
        traversal.restoreLostCursors()
    }

    /** The database read barrier is used before actions; independently sampled flows are not. */
    suspend fun view(expectedRevision: Long? = null): LibraryAcquisitionView {
        val current = store.acquisitionView()
        if (expectedRevision != null && current.snapshot.revision != expectedRevision) {
            throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "acquisition_action_stale"))
        }
        return current
    }

    /**
     * UI admission only, over the SAME Room/physical-presentation pair. Never infer offline
     * readiness from a persisted COMPLETE flag, another recording, or a current catalogue ID.
     * The playback resolver still takes a path lease, validates bytes/codec and fences native
     * handoff; this no-I/O check does not replace any of those execution responsibilities.
     */
    fun hasVerifiedLocal(view: LibraryAcquisitionView, physicalStates: Map<String, PhysicalDownloadJob>,
        bindingId: ExecutionBindingId): Boolean = try {
        val state = view.acquisition
        if (view.snapshot.revision != state.libraryRevision) false else {
            val context = state.executionContext(bindingId)
            val removing = state.physicalDownloads.values.any { job ->
                job.producer.subjectId == context.subject.id &&
                    (job.producer.recordingId == null || job.producer.recordingId == context.recording.id) &&
                    (job.cleanupPending || job.maintenanceKind == PhysicalDownloadMaintenanceKind.CLEANUP)
            }
            !removing && state.assetUses.filter { it.requestedBindingId == bindingId && it.compatibilityPolicyVersion == 1 }
                .any { use ->
                    val asset = state.materializations[use.materializationAssetId]
                    val job = asset?.let { value -> state.physicalDownloads.values.singleOrNull { it.assetId == value.id } }
                    asset != null && job != null && asset.producer.subjectId == context.subject.id &&
                        asset.producer.recordingId == context.recording.id && asset.kind == OfflineAssetKind.FILE &&
                        !asset.representationId.isNullOrBlank() && state.materializationReceipts[asset.id] == asset.receipt() &&
                        job.producer == asset.producer && job.status == DownloadStatus.COMPLETE &&
                        physicalStates[job.id] == job && !job.cleanupPending && job.maintenanceKind == null &&
                        job.leaseOwner == null && job.id !in state.physicalFinalizations &&
                        job.representationId == asset.representationId && job.validatorGeneration == asset.validatorGeneration &&
                        state.claims.values.any { it.physicalJobId == job.id && it.requestedBindingId == bindingId &&
                            (it.intent != DownloadBatchIntent.CANCELLED || it.retainCompletedAsset) }
                }
        }
    } catch (_: Exception) { false }

    internal fun downloadDependencies(
        beforePhysicalDelete: suspend (MaterializationAsset) -> Unit,
        beforePhysicalAdoption: suspend (PhysicalDownloadProducer.Schema2) -> Unit,
        requireOwnerAdmission: () -> Unit,
    ) = DownloadAcquisitionDependencies(
        repository, gateway, validator, beforePhysicalDelete, beforePhysicalAdoption,
        requireOwnerAdmission, acquisition, batches, materializationEvidence,
    )

    /** The adapter operates the existing DownloadCoordinator executor, never another executor. */
    internal fun downloadAdapter(executor: AcquisitionDownloadExecutor) =
        AcquisitionDownloadAdapter(repository, acquisition, executor)
}
