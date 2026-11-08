package dev.veyra.data.room

import dev.veyra.data.*
import dev.veyra.model.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin facade on LibraryStore's existing Room transaction, mutex, lifetime and publication.
 * It owns no database, mutable projection, coroutine scope, source client or transfer worker.
 * All command results are projected by the owner from its verified readback graph; neither a
 * cached StateFlow value nor a caller-supplied graph grants commit authority here.
 */
internal class RoomAcquisitionRepository(private val store: LibraryStore) :
    AcquisitionLibraryRepository, ReleaseCatalogRepository {
    override val transactionOwner: LibraryTransactionOwner get() = store.transactionOwner
    override val state: StateFlow<AcquisitionRepositoryState> get() = store.acquisitionState
    override val releaseCatalog: StateFlow<ReleaseCatalogState> get() = store.releaseCatalogState

    override suspend fun initialize(): AcquisitionRepositoryState = store.readAcquisition { it.state() }

    override suspend fun playbackView(): PlaybackAcquisitionView = store.readAcquisition { it.playbackView() }

    override suspend fun applyPlayback(command: AcquisitionPlaybackCommand): PlaybackAcquisitionView =
        store.transactAcquisition { it.applyPlayback(command) }

    override suspend fun request(id: AcquisitionRequestId): AcquisitionRecord? =
        store.readAcquisition { it.state().requests[id] }

    override suspend fun capture(command: CaptureAcquisition): AcquisitionRecord =
        store.transactAcquisition { it.applyCapture(command) }

    override suspend fun claim(command: ClaimAcquisition): AcquisitionClaimResult =
        store.transactAcquisition { it.applyClaim(command) }

    override suspend fun releaseUnsentClaim(lease: AcquisitionLease, nowEpochMs: Long): Boolean =
        store.transactAcquisition { it.applyReleaseUnsent(lease, nowEpochMs) }

    override suspend fun commit(command: CommitAcquisition): AcquisitionCommitResult =
        store.transactAcquisition { it.applyCommit(command) }

    override suspend fun markOutcomeUnknown(command: MarkAcquisitionOutcomeUnknown): AcquisitionRecord =
        store.transactAcquisition { it.applyOutcomeUnknown(command) }

    override suspend fun collectionSource(id: AcquisitionRequestId): CollectionSource =
        store.readAcquisition { it.collectionSource(id) }

    override suspend fun appendPage(command: AppendCollectionPage): CollectionAppendResult =
        store.transactAcquisition { it.applyAppendPage(command) }

    override suspend fun sealSnapshot(command: SealCollectionSnapshot): CollectionSnapshot =
        store.transactAcquisition { it.applySealSnapshot(command) }

    override suspend fun admitQueue(command: QueueAcquisitionAdmission): AcquisitionRepositoryState =
        store.transactAcquisition { it.applyAdmitQueue(command) }

    override suspend fun admitBatch(command: BatchAcquisitionAdmission): DownloadBatch =
        store.transactAcquisition { it.applyAdmitBatch(command) }

    override suspend fun attachOccurrence(command: AttachAcquisitionOccurrence): Boolean =
        store.transactAcquisition { it.applyAttachOccurrence(command) }

    override suspend fun updateBatchClaims(command: UpdateBatchClaims): DownloadBatch =
        store.transactAcquisition { it.applyBatchIntent(command) }

    override suspend fun attachBatchDownloadClaim(command: AttachBatchDownloadClaim): DownloadClaim =
        store.transactAcquisition { it.applyAttachBatchClaim(command) }

    override suspend fun physicalDownload(id: String): PhysicalDownloadJoin? =
        store.readAcquisition { it.physicalJoin(id) }

    override suspend fun admitStandalonePhysicalDownload(command: AdmitStandalonePhysicalDownload): PhysicalDownloadCommit<StandalonePhysicalDownloadAdmission> =
        store.transactAcquisition { it.applyAdmitStandalone(command) }

    override suspend fun admitSchema2StandalonePhysicalDownload(command: AdmitSchema2StandalonePhysicalDownload): PhysicalDownloadCommit<Schema2StandalonePhysicalDownloadAdmission> =
        store.transactAcquisition { it.applyAdmitSchema2Standalone(command) }

    override suspend fun updateStandaloneDownloadIntent(command: UpdateStandaloneDownloadIntent): PhysicalDownloadCommit<PhysicalDownloadJoin> =
        store.transactAcquisition { it.applyStandaloneIntent(command) }

    override suspend fun commitStandaloneMaterializationUse(command: CommitStandaloneMaterializationUse): PhysicalDownloadCommit<AssetUse> =
        store.transactAcquisition { it.applyStandaloneUse(command) }

    override suspend fun adoptSchema2PhysicalDownload(command: AdoptSchema2PhysicalDownload): PhysicalDownloadCommit<StandalonePhysicalDownloadAdmission> =
        store.transactAcquisition { it.applyAdoptSchema2(command, transactionOwner) }

    override suspend fun admitBatchPhysicalDownload(command: AdmitBatchPhysicalDownload): PhysicalDownloadCommit<BatchPhysicalDownloadAdmission> =
        store.transactAcquisition { it.applyAdmitBatchPhysical(command) }

    override suspend fun retryBatchPhysicalDownload(command: RetryBatchPhysicalDownload): PhysicalDownloadCommit<BatchPhysicalDownloadAdmission> =
        store.transactAcquisition { it.applyRetryBatchPhysical(command) }

    override suspend fun reconcilePhysicalDownloadClaim(command: ReconcilePhysicalDownloadClaim): PhysicalDownloadCommit<PhysicalDownloadJoin> =
        store.transactAcquisition { it.applyReconcileClaim(command) }

    override suspend fun claimPhysicalDownloadAttempt(command: ClaimPhysicalDownloadAttempt): PhysicalDownloadCommit<ClaimedPhysicalDownload> =
        store.transactAcquisition { it.applyClaimAttempt(command) }

    override suspend fun heartbeatPhysicalDownload(command: HeartbeatPhysicalDownload): PhysicalDownloadCommit<PhysicalDownloadJoin> =
        store.transactAcquisition { it.applyPhysicalHeartbeat(command) }

    override suspend fun selectPhysicalDownloadRepresentation(command: SelectPhysicalDownloadRepresentation): PhysicalDownloadCommit<PhysicalDownloadJoin> =
        store.transactAcquisition { it.applySelectRepresentation(command) }

    override suspend fun beginPhysicalDownloadValidation(command: BeginPhysicalDownloadValidation): PhysicalDownloadCommit<PhysicalDownloadJoin> =
        store.transactAcquisition { it.applyBeginValidation(command) }

    override suspend fun preparePhysicalDownloadFinalization(command: PreparePhysicalDownloadFinalization): PhysicalDownloadCommit<PhysicalDownloadJoin> =
        store.transactAcquisition { it.applyPrepareFinalization(command) }

    override suspend fun publishPhysicalDownloadFinalization(command: PublishPhysicalDownloadFinalization): PhysicalDownloadCommit<PhysicalDownloadJoin> =
        store.transactAcquisition { it.applyPublishFinalization(command) }

    override suspend fun finishPhysicalDownloadAttempt(command: FinishPhysicalDownloadAttempt): PhysicalDownloadCommit<PhysicalDownloadJoin> =
        store.transactAcquisition { it.applyFinishAttempt(command) }

    override suspend fun reconcilePhysicalDownloadIntent(command: ReconcilePhysicalDownloadIntent): PhysicalDownloadCommit<PhysicalDownloadJoin> =
        store.transactAcquisition { it.applyReconcileIntent(command) }

    override suspend fun claimPhysicalDownloadMaintenance(command: ClaimPhysicalDownloadMaintenance): PhysicalDownloadCommit<ClaimedPhysicalDownload> =
        store.transactAcquisition { it.applyClaimMaintenance(command) }

    override suspend fun finishPhysicalDownloadMaintenance(command: FinishPhysicalDownloadMaintenance): PhysicalDownloadCommit<PhysicalDownloadJoin> =
        store.transactAcquisition { it.applyFinishMaintenance(command) }

    override suspend fun commitMaterializationUse(command: CommitMaterializationUse): PhysicalDownloadCommit<AssetUse> =
        store.transactAcquisition { it.applyMaterializationUse(command) }

    override suspend fun applyReleaseCatalog(command: ReleaseCatalogCommand): ReleaseCatalogState =
        store.transactAcquisition { it.applyReleaseCatalog(command) }

    override suspend fun releaseDetail(requestedReleaseMbid: String, selectedObservationId: ReleaseObservationId?): ReleaseCatalogDetail? =
        store.readAcquisition { it.releaseCatalogDetail(requestedReleaseMbid, selectedObservationId) }
}
