package dev.veyra.data.room

import androidx.room.*

/**
 * Used only inside the same VeyraDatabase transaction owner as LibraryDao. Reads are not a
 * second cache/authority. Immutable relations have insert-only writes; conflicts fail rather
 * than replacing provenance. Mutable transitions still require canonical complete-graph CAS
 * validation and the full intended-graph readback before commit.
 */
@Dao
interface AcquisitionDao {
    // The ONE transaction owner stages every affected ID (bounded bind chunks) before any
    // final order upserts. Unchanged rows are not rewritten; all UNIQUE/readback guards stay.
    @Query("UPDATE queue_members SET position=-position-1, originalPosition=-originalPosition-1 WHERE occurrenceId IN (:ids)")
    suspend fun stageExecutionQueueMemberPositions(ids: List<String>)

    @Query("UPDATE listening_history SET position=-position-1 WHERE id IN (:ids)")
    suspend fun stageListeningHistoryPositions(ids: List<String>)

    @Query("UPDATE playlist_members SET position=-position-1 WHERE id IN (:ids)")
    suspend fun stagePlaylistMemberPositions(ids: List<String>)

    @Query("SELECT * FROM acquisition_requests")
    suspend fun readAcquisitionRequestRows(): List<AcquisitionRequestEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAcquisitionRequestRows(rows: List<AcquisitionRequestEntity>)

    @Query("SELECT * FROM request_media_metadata")
    suspend fun readRequestMediaMetadataRows(): List<RequestMediaMetadataEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRequestMediaMetadataRows(rows: List<RequestMediaMetadataEntity>)

    @Query("SELECT * FROM request_media_artists")
    suspend fun readRequestMediaArtistRows(): List<RequestMediaArtistEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRequestMediaArtistRows(rows: List<RequestMediaArtistEntity>)

    @Query("SELECT * FROM request_album_metadata")
    suspend fun readRequestAlbumMetadataRows(): List<RequestAlbumMetadataEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRequestAlbumMetadataRows(rows: List<RequestAlbumMetadataEntity>)

    @Query("SELECT * FROM request_album_artists")
    suspend fun readRequestAlbumArtistRows(): List<RequestAlbumArtistEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRequestAlbumArtistRows(rows: List<RequestAlbumArtistEntity>)

    @Query("SELECT * FROM request_album_members")
    suspend fun readRequestAlbumMemberRows(): List<RequestAlbumMemberEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRequestAlbumMemberRows(rows: List<RequestAlbumMemberEntity>)

    @Query("SELECT * FROM request_album_member_artists")
    suspend fun readRequestAlbumMemberArtistRows(): List<RequestAlbumMemberArtistEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRequestAlbumMemberArtistRows(rows: List<RequestAlbumMemberArtistEntity>)

    @Query("SELECT * FROM request_show_metadata")
    suspend fun readRequestShowMetadataRows(): List<RequestShowMetadataEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRequestShowMetadataRows(rows: List<RequestShowMetadataEntity>)

    @Query("SELECT * FROM request_show_aliases")
    suspend fun readRequestShowAliasRows(): List<RequestShowAliasEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRequestShowAliasRows(rows: List<RequestShowAliasEntity>)

    @Query("SELECT * FROM request_show_members")
    suspend fun readRequestShowMemberRows(): List<RequestShowMemberEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRequestShowMemberRows(rows: List<RequestShowMemberEntity>)

    @Query("SELECT * FROM request_source_collection_metadata")
    suspend fun readRequestSourceCollectionMetadataRows(): List<RequestSourceCollectionMetadataEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRequestSourceCollectionMetadataRows(rows: List<RequestSourceCollectionMetadataEntity>)

    @Query("SELECT * FROM release_observations")
    suspend fun readReleaseObservationRows(): List<ReleaseObservationEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertReleaseObservationRows(rows: List<ReleaseObservationEntity>)

    @Query("SELECT * FROM release_observation_groups")
    suspend fun readReleaseObservationGroupRows(): List<ReleaseObservationGroupEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertReleaseObservationGroupRows(rows: List<ReleaseObservationGroupEntity>)

    @Query("SELECT * FROM release_group_secondary_types")
    suspend fun readReleaseGroupSecondaryTypeRows(): List<ReleaseGroupSecondaryTypeEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertReleaseGroupSecondaryTypeRows(rows: List<ReleaseGroupSecondaryTypeEntity>)

    @Query("SELECT * FROM release_artist_credits")
    suspend fun readReleaseArtistCreditRows(): List<ReleaseArtistCreditEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertReleaseArtistCreditRows(rows: List<ReleaseArtistCreditEntity>)

    @Query("SELECT * FROM release_events")
    suspend fun readReleaseEventRows(): List<ReleaseEventEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertReleaseEventRows(rows: List<ReleaseEventEntity>)

    @Query("SELECT * FROM release_event_countries")
    suspend fun readReleaseEventCountryRows(): List<ReleaseEventCountryEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertReleaseEventCountryRows(rows: List<ReleaseEventCountryEntity>)

    @Query("SELECT * FROM release_labels")
    suspend fun readReleaseLabelRows(): List<ReleaseLabelEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertReleaseLabelRows(rows: List<ReleaseLabelEntity>)

    @Query("SELECT * FROM release_media")
    suspend fun readReleaseMediumRows(): List<ReleaseMediumEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertReleaseMediumRows(rows: List<ReleaseMediumEntity>)

    @Query("SELECT * FROM release_tracks")
    suspend fun readReleaseTrackRows(): List<ReleaseTrackEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertReleaseTrackRows(rows: List<ReleaseTrackEntity>)

    @Query("SELECT * FROM release_track_artist_credits")
    suspend fun readReleaseTrackArtistCreditRows(): List<ReleaseTrackArtistCreditEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertReleaseTrackArtistCreditRows(rows: List<ReleaseTrackArtistCreditEntity>)

    @Query("SELECT * FROM release_summary_problems")
    suspend fun readReleaseSummaryProblemRows(): List<ReleaseSummaryProblemEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertReleaseSummaryProblemRows(rows: List<ReleaseSummaryProblemEntity>)

    @Query("SELECT * FROM release_core_problems")
    suspend fun readReleaseCoreProblemRows(): List<ReleaseCoreProblemEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertReleaseCoreProblemRows(rows: List<ReleaseCoreProblemEntity>)

    @Query("SELECT * FROM release_medium_problems")
    suspend fun readReleaseMediumProblemRows(): List<ReleaseMediumProblemEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertReleaseMediumProblemRows(rows: List<ReleaseMediumProblemEntity>)

    @Query("SELECT * FROM release_track_problems")
    suspend fun readReleaseTrackProblemRows(): List<ReleaseTrackProblemEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertReleaseTrackProblemRows(rows: List<ReleaseTrackProblemEntity>)

    @Query("SELECT * FROM request_release_metadata")
    suspend fun readRequestReleaseMetadataRows(): List<RequestReleaseMetadataEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRequestReleaseMetadataRows(rows: List<RequestReleaseMetadataEntity>)

    @Query("SELECT * FROM request_media_release_occurrences")
    suspend fun readRequestMediaReleaseOccurrenceRows(): List<RequestMediaReleaseOccurrenceEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRequestMediaReleaseOccurrenceRows(rows: List<RequestMediaReleaseOccurrenceEntity>)

    @Query("SELECT * FROM release_catalog_entries")
    suspend fun readReleaseCatalogEntryRows(): List<ReleaseCatalogEntryEntity>
    @Upsert suspend fun upsertReleaseCatalogEntryRows(rows: List<ReleaseCatalogEntryEntity>)
    @Delete suspend fun deleteReleaseCatalogEntryRows(rows: List<ReleaseCatalogEntryEntity>)

    @Query("SELECT * FROM saved_releases")
    suspend fun readSavedReleaseRows(): List<SavedReleaseEntity>
    @Upsert suspend fun upsertSavedReleaseRows(rows: List<SavedReleaseEntity>)
    @Delete suspend fun deleteSavedReleaseRows(rows: List<SavedReleaseEntity>)

    @Query("SELECT * FROM first_result_receipts")
    suspend fun readFirstResultReceiptRows(): List<FirstResultReceiptEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertFirstResultReceiptRows(rows: List<FirstResultReceiptEntity>)

    @Query("SELECT * FROM source_recordings")
    suspend fun readSourceRecordingRows(): List<SourceRecordingEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSourceRecordingRows(rows: List<SourceRecordingEntity>)

    @Query("SELECT * FROM executable_subjects")
    suspend fun readExecutableSubjectRows(): List<ExecutableSubjectEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertExecutableSubjectRows(rows: List<ExecutableSubjectEntity>)

    @Query("SELECT * FROM execution_bindings")
    suspend fun readExecutionBindingRows(): List<ExecutionBindingEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertExecutionBindingRows(rows: List<ExecutionBindingEntity>)

    @Query("SELECT * FROM acquisition_outcomes")
    suspend fun readAcquisitionOutcomeRows(): List<AcquisitionOutcomeEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAcquisitionOutcomeRows(rows: List<AcquisitionOutcomeEntity>)

    @Query("SELECT * FROM acquisition_jobs")
    suspend fun readAcquisitionJobRows(): List<AcquisitionJobEntity>
    @Upsert suspend fun upsertAcquisitionJobRows(rows: List<AcquisitionJobEntity>)
    @Delete suspend fun deleteAcquisitionJobRows(rows: List<AcquisitionJobEntity>)

    @Query("SELECT * FROM collection_runs")
    suspend fun readCollectionRunRows(): List<CollectionRunEntity>
    @Upsert suspend fun upsertCollectionRunRows(rows: List<CollectionRunEntity>)
    @Delete suspend fun deleteCollectionRunRows(rows: List<CollectionRunEntity>)

    @Query("SELECT * FROM collection_observations")
    suspend fun readCollectionObservationRows(): List<CollectionObservationEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCollectionObservationRows(rows: List<CollectionObservationEntity>)

    @Query("SELECT * FROM collection_identity_evidence")
    suspend fun readCollectionIdentityEvidenceRows(): List<CollectionIdentityEvidenceEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCollectionIdentityEvidenceRows(rows: List<CollectionIdentityEvidenceEntity>)

    @Query("SELECT * FROM collection_page_commits")
    suspend fun readCollectionPageCommitRows(): List<CollectionPageCommitEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCollectionPageCommitRows(rows: List<CollectionPageCommitEntity>)

    @Query("SELECT * FROM collection_page_failures")
    suspend fun readCollectionPageFailureRows(): List<CollectionPageFailureEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCollectionPageFailureRows(rows: List<CollectionPageFailureEntity>)

    @Query("SELECT * FROM collection_members")
    suspend fun readCollectionMemberRows(): List<CollectionMemberEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCollectionMemberRows(rows: List<CollectionMemberEntity>)

    @Query("SELECT * FROM collection_snapshots")
    suspend fun readCollectionSnapshotRows(): List<CollectionSnapshotEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCollectionSnapshotRows(rows: List<CollectionSnapshotEntity>)

    @Query("SELECT * FROM collection_snapshot_members")
    suspend fun readCollectionSnapshotMemberRows(): List<CollectionSnapshotMemberEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCollectionSnapshotMemberRows(rows: List<CollectionSnapshotMemberEntity>)

    @Query("SELECT * FROM request_collection_member_metadata")
    suspend fun readRequestCollectionMemberMetadataRows(): List<RequestCollectionMemberMetadataEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRequestCollectionMemberMetadataRows(rows: List<RequestCollectionMemberMetadataEntity>)

    @Query("SELECT * FROM admission_scopes")
    suspend fun readAdmissionScopeRows(): List<AdmissionScopeEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAdmissionScopeRows(rows: List<AdmissionScopeEntity>)

    @Query("SELECT * FROM admission_scope_members")
    suspend fun readAdmissionScopeMemberRows(): List<AdmissionScopeMemberEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAdmissionScopeMemberRows(rows: List<AdmissionScopeMemberEntity>)

    @Query("SELECT * FROM execution_occurrences")
    suspend fun readExecutionOccurrenceRows(): List<ExecutionOccurrenceEntity>
    @Upsert suspend fun upsertExecutionOccurrenceRows(rows: List<ExecutionOccurrenceEntity>)
    @Delete suspend fun deleteExecutionOccurrenceRows(rows: List<ExecutionOccurrenceEntity>)

    @Query("SELECT * FROM execution_queue_revisions")
    suspend fun readExecutionQueueRevisionRows(): List<ExecutionQueueRevisionEntity>
    @Upsert suspend fun upsertExecutionQueueRevisionRows(rows: List<ExecutionQueueRevisionEntity>)
    @Delete suspend fun deleteExecutionQueueRevisionRows(rows: List<ExecutionQueueRevisionEntity>)

    @Query("SELECT * FROM queue_members")
    suspend fun readExecutionQueueMemberRows(): List<ExecutionQueueMemberEntity>
    @Upsert suspend fun upsertExecutionQueueMemberRows(rows: List<ExecutionQueueMemberEntity>)
    @Delete suspend fun deleteExecutionQueueMemberRows(rows: List<ExecutionQueueMemberEntity>)

    @Query("SELECT * FROM queue_heads")
    suspend fun readExecutionQueueHeadRows(): List<ExecutionQueueHeadEntity>
    @Upsert suspend fun upsertExecutionQueueHeadRows(rows: List<ExecutionQueueHeadEntity>)
    @Delete suspend fun deleteExecutionQueueHeadRows(rows: List<ExecutionQueueHeadEntity>)

    @Query("SELECT * FROM occurrence_checkpoints")
    suspend fun readOccurrenceCheckpointRows(): List<OccurrenceCheckpointEntity>
    @Upsert suspend fun upsertOccurrenceCheckpointRows(rows: List<OccurrenceCheckpointEntity>)
    @Delete suspend fun deleteOccurrenceCheckpointRows(rows: List<OccurrenceCheckpointEntity>)

    @Query("SELECT * FROM source_recording_resume")
    suspend fun readSourceRecordingResumeRows(): List<SourceRecordingResumeEntity>
    @Upsert suspend fun upsertSourceRecordingResumeRows(rows: List<SourceRecordingResumeEntity>)
    @Delete suspend fun deleteSourceRecordingResumeRows(rows: List<SourceRecordingResumeEntity>)

    @Query("SELECT * FROM catalog_progress")
    suspend fun readCatalogProgressRows(): List<CatalogProgressEntity>
    @Upsert suspend fun upsertCatalogProgressRows(rows: List<CatalogProgressEntity>)
    @Delete suspend fun deleteCatalogProgressRows(rows: List<CatalogProgressEntity>)

    @Query("SELECT * FROM listening_history")
    suspend fun readListeningHistoryRows(): List<ListeningHistoryEntity>
    @Upsert suspend fun upsertListeningHistoryRows(rows: List<ListeningHistoryEntity>)
    @Delete suspend fun deleteListeningHistoryRows(rows: List<ListeningHistoryEntity>)

    @Query("SELECT * FROM favorite_subjects")
    suspend fun readFavoriteSubjectRows(): List<FavoriteSubjectEntity>
    @Upsert suspend fun upsertFavoriteSubjectRows(rows: List<FavoriteSubjectEntity>)
    @Delete suspend fun deleteFavoriteSubjectRows(rows: List<FavoriteSubjectEntity>)

    @Query("SELECT * FROM playlist_members")
    suspend fun readPlaylistMemberRows(): List<PlaylistMemberEntity>
    @Upsert suspend fun upsertPlaylistMemberRows(rows: List<PlaylistMemberEntity>)
    @Delete suspend fun deletePlaylistMemberRows(rows: List<PlaylistMemberEntity>)

    @Query("SELECT * FROM download_batches")
    suspend fun readDownloadBatchRows(): List<DownloadBatchEntity>
    @Upsert suspend fun upsertDownloadBatchRows(rows: List<DownloadBatchEntity>)
    @Delete suspend fun deleteDownloadBatchRows(rows: List<DownloadBatchEntity>)

    @Query("SELECT * FROM download_batch_members")
    suspend fun readDownloadBatchMemberRows(): List<DownloadBatchMemberEntity>
    @Upsert suspend fun upsertDownloadBatchMemberRows(rows: List<DownloadBatchMemberEntity>)
    @Delete suspend fun deleteDownloadBatchMemberRows(rows: List<DownloadBatchMemberEntity>)

    @Query("SELECT * FROM download_claims")
    suspend fun readDownloadClaimRows(): List<DownloadClaimEntity>
    @Upsert suspend fun upsertDownloadClaimRows(rows: List<DownloadClaimEntity>)
    @Delete suspend fun deleteDownloadClaimRows(rows: List<DownloadClaimEntity>)

    @Query("SELECT * FROM download_producer_adoptions")
    suspend fun readPhysicalDownloadAdoptionRows(): List<PhysicalDownloadAdoptionEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertPhysicalDownloadAdoptionRows(rows: List<PhysicalDownloadAdoptionEntity>)

    @Query("SELECT * FROM materialization_receipts")
    suspend fun readMaterializationReceiptRows(): List<MaterializationReceiptEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMaterializationReceiptRows(rows: List<MaterializationReceiptEntity>)

    @Query("SELECT * FROM asset_uses")
    suspend fun readAssetUseRows(): List<AssetUseEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAssetUseRows(rows: List<AssetUseEntity>)

    @Query("SELECT * FROM acquisition_requests WHERE id = :id")
    suspend fun request(id: String): AcquisitionRequestEntity?

    @Query("SELECT * FROM first_result_receipts WHERE id = :id")
    suspend fun receipt(id: String): FirstResultReceiptEntity?

    @Query("SELECT * FROM execution_bindings WHERE id = :id")
    suspend fun binding(id: String): ExecutionBindingEntity?

    @Query("SELECT * FROM collection_runs WHERE id = :id")
    suspend fun collectionRun(id: String): CollectionRunEntity?

    @Query("SELECT * FROM download_claims WHERE physicalJobId = :downloadId ORDER BY id")
    suspend fun completeClaims(downloadId: String): List<DownloadClaimEntity>
}
