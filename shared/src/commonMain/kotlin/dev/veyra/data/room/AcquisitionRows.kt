package dev.veyra.data.room

/** Complete transaction-local graph, including all rows absent from public projections. */
internal data class AcquisitionRows(
    val releases: ReleaseObservationRows = ReleaseObservationRows(),
    val acquisitionRequestRows: List<AcquisitionRequestEntity> = emptyList(),
    val requestMediaMetadataRows: List<RequestMediaMetadataEntity> = emptyList(),
    val requestMediaArtistRows: List<RequestMediaArtistEntity> = emptyList(),
    val requestAlbumMetadataRows: List<RequestAlbumMetadataEntity> = emptyList(),
    val requestAlbumArtistRows: List<RequestAlbumArtistEntity> = emptyList(),
    val requestAlbumMemberRows: List<RequestAlbumMemberEntity> = emptyList(),
    val requestAlbumMemberArtistRows: List<RequestAlbumMemberArtistEntity> = emptyList(),
    val requestShowMetadataRows: List<RequestShowMetadataEntity> = emptyList(),
    val requestShowAliasRows: List<RequestShowAliasEntity> = emptyList(),
    val requestShowMemberRows: List<RequestShowMemberEntity> = emptyList(),
    val requestSourceCollectionMetadataRows: List<RequestSourceCollectionMetadataEntity> = emptyList(),
    val requestReleaseMetadataRows: List<RequestReleaseMetadataEntity> = emptyList(),
    val requestMediaReleaseOccurrenceRows: List<RequestMediaReleaseOccurrenceEntity> = emptyList(),
    val releaseCatalogEntryRows: List<ReleaseCatalogEntryEntity> = emptyList(),
    val savedReleaseRows: List<SavedReleaseEntity> = emptyList(),
    val firstResultReceiptRows: List<FirstResultReceiptEntity> = emptyList(),
    val sourceRecordingRows: List<SourceRecordingEntity> = emptyList(),
    val executableSubjectRows: List<ExecutableSubjectEntity> = emptyList(),
    val executionBindingRows: List<ExecutionBindingEntity> = emptyList(),
    val acquisitionOutcomeRows: List<AcquisitionOutcomeEntity> = emptyList(),
    val acquisitionJobRows: List<AcquisitionJobEntity> = emptyList(),
    val collectionRunRows: List<CollectionRunEntity> = emptyList(),
    val collectionObservationRows: List<CollectionObservationEntity> = emptyList(),
    val collectionIdentityEvidenceRows: List<CollectionIdentityEvidenceEntity> = emptyList(),
    val collectionPageCommitRows: List<CollectionPageCommitEntity> = emptyList(),
    val collectionPageFailureRows: List<CollectionPageFailureEntity> = emptyList(),
    val collectionMemberRows: List<CollectionMemberEntity> = emptyList(),
    val collectionSnapshotRows: List<CollectionSnapshotEntity> = emptyList(),
    val collectionSnapshotMemberRows: List<CollectionSnapshotMemberEntity> = emptyList(),
    val requestCollectionMemberMetadataRows: List<RequestCollectionMemberMetadataEntity> = emptyList(),
    val admissionScopeRows: List<AdmissionScopeEntity> = emptyList(),
    val admissionScopeMemberRows: List<AdmissionScopeMemberEntity> = emptyList(),
    val executionOccurrenceRows: List<ExecutionOccurrenceEntity> = emptyList(),
    val executionQueueRevisionRows: List<ExecutionQueueRevisionEntity> = emptyList(),
    val executionQueueMemberRows: List<ExecutionQueueMemberEntity> = emptyList(),
    val executionQueueHeadRows: List<ExecutionQueueHeadEntity> = emptyList(),
    val occurrenceCheckpointRows: List<OccurrenceCheckpointEntity> = emptyList(),
    val sourceRecordingResumeRows: List<SourceRecordingResumeEntity> = emptyList(),
    val catalogProgressRows: List<CatalogProgressEntity> = emptyList(),
    val listeningHistoryRows: List<ListeningHistoryEntity> = emptyList(),
    val favoriteSubjectRows: List<FavoriteSubjectEntity> = emptyList(),
    val playlistMemberRows: List<PlaylistMemberEntity> = emptyList(),
    val downloadBatchRows: List<DownloadBatchEntity> = emptyList(),
    val downloadBatchMemberRows: List<DownloadBatchMemberEntity> = emptyList(),
    val downloadClaimRows: List<DownloadClaimEntity> = emptyList(),
    val physicalDownloadAdoptionRows: List<PhysicalDownloadAdoptionEntity> = emptyList(),
    val materializationReceiptRows: List<MaterializationReceiptEntity> = emptyList(),
    val assetUseRows: List<AssetUseEntity> = emptyList(),
)

internal suspend fun AcquisitionDao.readAcquisitionRows(): AcquisitionRows = AcquisitionRows(
    releases = readCapturedReleaseRows(),
    acquisitionRequestRows = readAcquisitionRequestRows(),
    requestMediaMetadataRows = readRequestMediaMetadataRows(),
    requestMediaArtistRows = readRequestMediaArtistRows(),
    requestAlbumMetadataRows = readRequestAlbumMetadataRows(),
    requestAlbumArtistRows = readRequestAlbumArtistRows(),
    requestAlbumMemberRows = readRequestAlbumMemberRows(),
    requestAlbumMemberArtistRows = readRequestAlbumMemberArtistRows(),
    requestShowMetadataRows = readRequestShowMetadataRows(),
    requestShowAliasRows = readRequestShowAliasRows(),
    requestShowMemberRows = readRequestShowMemberRows(),
    requestSourceCollectionMetadataRows = readRequestSourceCollectionMetadataRows(),
    requestReleaseMetadataRows = readRequestReleaseMetadataRows(),
    requestMediaReleaseOccurrenceRows = readRequestMediaReleaseOccurrenceRows(),
    releaseCatalogEntryRows = readReleaseCatalogEntryRows(),
    savedReleaseRows = readSavedReleaseRows(),
    firstResultReceiptRows = readFirstResultReceiptRows(),
    sourceRecordingRows = readSourceRecordingRows(),
    executableSubjectRows = readExecutableSubjectRows(),
    executionBindingRows = readExecutionBindingRows(),
    acquisitionOutcomeRows = readAcquisitionOutcomeRows(),
    acquisitionJobRows = readAcquisitionJobRows(),
    collectionRunRows = readCollectionRunRows(),
    collectionObservationRows = readCollectionObservationRows(),
    collectionIdentityEvidenceRows = readCollectionIdentityEvidenceRows(),
    collectionPageCommitRows = readCollectionPageCommitRows(),
    collectionPageFailureRows = readCollectionPageFailureRows(),
    collectionMemberRows = readCollectionMemberRows(),
    collectionSnapshotRows = readCollectionSnapshotRows(),
    collectionSnapshotMemberRows = readCollectionSnapshotMemberRows(),
    requestCollectionMemberMetadataRows = readRequestCollectionMemberMetadataRows(),
    admissionScopeRows = readAdmissionScopeRows(),
    admissionScopeMemberRows = readAdmissionScopeMemberRows(),
    executionOccurrenceRows = readExecutionOccurrenceRows(),
    executionQueueRevisionRows = readExecutionQueueRevisionRows(),
    executionQueueMemberRows = readExecutionQueueMemberRows(),
    executionQueueHeadRows = readExecutionQueueHeadRows(),
    occurrenceCheckpointRows = readOccurrenceCheckpointRows(),
    sourceRecordingResumeRows = readSourceRecordingResumeRows(),
    catalogProgressRows = readCatalogProgressRows(),
    listeningHistoryRows = readListeningHistoryRows(),
    favoriteSubjectRows = readFavoriteSubjectRows(),
    playlistMemberRows = readPlaylistMemberRows(),
    downloadBatchRows = readDownloadBatchRows(),
    downloadBatchMemberRows = readDownloadBatchMemberRows(),
    downloadClaimRows = readDownloadClaimRows(),
    physicalDownloadAdoptionRows = readPhysicalDownloadAdoptionRows(),
    materializationReceiptRows = readMaterializationReceiptRows(),
    assetUseRows = readAssetUseRows(),
)

/** Detects silent ignored writes in EVERY relation, not just the visible domain projection. */
internal fun AcquisitionRows.sameRelations(other: AcquisitionRows): Boolean =
    releases.sameReleaseRelations(other.releases) &&
    sameRowRelation(acquisitionRequestRows, other.acquisitionRequestRows) &&
    sameRowRelation(requestMediaMetadataRows, other.requestMediaMetadataRows) &&
    sameRowRelation(requestMediaArtistRows, other.requestMediaArtistRows) &&
    sameRowRelation(requestAlbumMetadataRows, other.requestAlbumMetadataRows) &&
    sameRowRelation(requestAlbumArtistRows, other.requestAlbumArtistRows) &&
    sameRowRelation(requestAlbumMemberRows, other.requestAlbumMemberRows) &&
    sameRowRelation(requestAlbumMemberArtistRows, other.requestAlbumMemberArtistRows) &&
    sameRowRelation(requestShowMetadataRows, other.requestShowMetadataRows) &&
    sameRowRelation(requestShowAliasRows, other.requestShowAliasRows) &&
    sameRowRelation(requestShowMemberRows, other.requestShowMemberRows) &&
    sameRowRelation(requestSourceCollectionMetadataRows, other.requestSourceCollectionMetadataRows) &&
    sameRowRelation(requestReleaseMetadataRows, other.requestReleaseMetadataRows) &&
    sameRowRelation(requestMediaReleaseOccurrenceRows, other.requestMediaReleaseOccurrenceRows) &&
    sameRowRelation(releaseCatalogEntryRows, other.releaseCatalogEntryRows) &&
    sameRowRelation(savedReleaseRows, other.savedReleaseRows) &&
    sameRowRelation(firstResultReceiptRows, other.firstResultReceiptRows) &&
    sameRowRelation(sourceRecordingRows, other.sourceRecordingRows) &&
    sameRowRelation(executableSubjectRows, other.executableSubjectRows) &&
    sameRowRelation(executionBindingRows, other.executionBindingRows) &&
    sameRowRelation(acquisitionOutcomeRows, other.acquisitionOutcomeRows) &&
    sameRowRelation(acquisitionJobRows, other.acquisitionJobRows) &&
    sameRowRelation(collectionRunRows, other.collectionRunRows) &&
    sameRowRelation(collectionObservationRows, other.collectionObservationRows) &&
    sameRowRelation(collectionIdentityEvidenceRows, other.collectionIdentityEvidenceRows) &&
    sameRowRelation(collectionPageCommitRows, other.collectionPageCommitRows) &&
    sameRowRelation(collectionPageFailureRows, other.collectionPageFailureRows) &&
    sameRowRelation(collectionMemberRows, other.collectionMemberRows) &&
    sameRowRelation(collectionSnapshotRows, other.collectionSnapshotRows) &&
    sameRowRelation(collectionSnapshotMemberRows, other.collectionSnapshotMemberRows) &&
    sameRowRelation(requestCollectionMemberMetadataRows, other.requestCollectionMemberMetadataRows) &&
    sameRowRelation(admissionScopeRows, other.admissionScopeRows) &&
    sameRowRelation(admissionScopeMemberRows, other.admissionScopeMemberRows) &&
    sameRowRelation(executionOccurrenceRows, other.executionOccurrenceRows) &&
    sameRowRelation(executionQueueRevisionRows, other.executionQueueRevisionRows) &&
    sameRowRelation(executionQueueMemberRows, other.executionQueueMemberRows) &&
    sameRowRelation(executionQueueHeadRows, other.executionQueueHeadRows) &&
    sameRowRelation(occurrenceCheckpointRows, other.occurrenceCheckpointRows) &&
    sameRowRelation(sourceRecordingResumeRows, other.sourceRecordingResumeRows) &&
    sameRowRelation(catalogProgressRows, other.catalogProgressRows) &&
    sameRowRelation(listeningHistoryRows, other.listeningHistoryRows) &&
    sameRowRelation(favoriteSubjectRows, other.favoriteSubjectRows) &&
    sameRowRelation(playlistMemberRows, other.playlistMemberRows) &&
    sameRowRelation(downloadBatchRows, other.downloadBatchRows) &&
    sameRowRelation(downloadBatchMemberRows, other.downloadBatchMemberRows) &&
    sameRowRelation(downloadClaimRows, other.downloadClaimRows) &&
    sameRowRelation(physicalDownloadAdoptionRows, other.physicalDownloadAdoptionRows) &&
    sameRowRelation(materializationReceiptRows, other.materializationReceiptRows) &&
    sameRowRelation(assetUseRows, other.assetUseRows)

private fun ReleaseObservationRows.sameReleaseRelations(other: ReleaseObservationRows): Boolean =
    sameRowRelation(observations, other.observations) &&
    sameRowRelation(groups, other.groups) &&
    sameRowRelation(groupSecondaryTypes, other.groupSecondaryTypes) &&
    sameRowRelation(artistCredits, other.artistCredits) &&
    sameRowRelation(events, other.events) &&
    sameRowRelation(eventCountries, other.eventCountries) &&
    sameRowRelation(labels, other.labels) &&
    sameRowRelation(media, other.media) &&
    sameRowRelation(tracks, other.tracks) &&
    sameRowRelation(trackArtistCredits, other.trackArtistCredits) &&
    sameRowRelation(summaryProblems, other.summaryProblems) &&
    sameRowRelation(coreProblems, other.coreProblems) &&
    sameRowRelation(mediumProblems, other.mediumProblems) &&
    sameRowRelation(trackProblems, other.trackProblems)

internal fun <T> sameRowRelation(first: List<T>, second: List<T>): Boolean =
    first.size == second.size && first.toSet().size == first.size && second.toSet().size == second.size &&
        first.toSet() == second.toSet()

internal fun <T, K> List<T>.rowIndex(key: (T) -> K): Map<K, T> = associateBy(key).also {
    require(it.size == size) { "Duplicate relational identity" }
}

internal fun <T, K> List<T>.withRow(row: T, key: (T) -> K): List<T> {
    val identity = key(row)
    val index = indexOfFirst { key(it) == identity }
    return if (index < 0) this + row else toMutableList().also { it[index] = row }
}

internal fun <T, K> List<T>.withImmutableRow(row: T, key: (T) -> K): List<T> {
    val old = rowIndex(key)[key(row)]
    require(old == null || old == row) { "Immutable relational identity changed" }
    return if (old == null) this + row else this
}

internal fun <T, K> requireRowPositions(rows: List<T>, group: (T) -> K, position: (T) -> Int) {
    rows.groupBy(group).values.forEach { values ->
        require(values.map(position).sorted() == values.indices.toList()) { "Invalid relational position" }
    }
}

private fun <T, K> immutableDelta(before: List<T>, next: List<T>, key: (T) -> K): List<T> {
    val old = before.rowIndex(key)
    val proposed = next.rowIndex(key)
    require(old.all { (id, row) -> proposed[id] == row }) { "Immutable relational evidence changed" }
    return next.filter { key(it) !in old }
}

private fun <T, K> removedRows(before: List<T>, next: List<T>, key: (T) -> K): List<T> {
    val proposed = next.rowIndex(key)
    before.rowIndex(key)
    return before.filter { key(it) !in proposed }
}

/** Existing updates precede inserts so a new ordered row cannot occupy a still-live slot. */
private fun <T, K> changedRows(before: List<T>, next: List<T>, key: (T) -> K): List<T> {
    val old = before.rowIndex(key)
    next.rowIndex(key)
    return next.filter { key(it) in old && old[key(it)] != it } + next.filter { key(it) !in old }
}

/** Caller owns the SAME IMMEDIATE transaction and full graph readback. */
internal suspend fun AcquisitionDao.writeAcquisitionDifference(before: AcquisitionRows, next: AcquisitionRows) {
    // Preflight every immutable relation before the first SQL write.
    val newPhysicalDownloadAdoption = immutableDelta(before.physicalDownloadAdoptionRows, next.physicalDownloadAdoptionRows) { it.jobId to it.newOwnershipGeneration }
    val newAcquisitionRequest = immutableDelta(before.acquisitionRequestRows, next.acquisitionRequestRows) { it.id }
    val newRequestMediaMetadata = immutableDelta(before.requestMediaMetadataRows, next.requestMediaMetadataRows) { it.requestId }
    val newRequestMediaArtist = immutableDelta(before.requestMediaArtistRows, next.requestMediaArtistRows) { it.requestId to it.position }
    val newRequestAlbumMetadata = immutableDelta(before.requestAlbumMetadataRows, next.requestAlbumMetadataRows) { it.requestId }
    val newRequestAlbumArtist = immutableDelta(before.requestAlbumArtistRows, next.requestAlbumArtistRows) { it.requestId to it.position }
    val newRequestAlbumMember = immutableDelta(before.requestAlbumMemberRows, next.requestAlbumMemberRows) { it.requestId to it.position }
    val newRequestAlbumMemberArtist = immutableDelta(before.requestAlbumMemberArtistRows, next.requestAlbumMemberArtistRows) { Triple(it.requestId, it.memberPosition, it.artistPosition) }
    val newRequestShowMetadata = immutableDelta(before.requestShowMetadataRows, next.requestShowMetadataRows) { it.requestId }
    val newRequestShowAlias = immutableDelta(before.requestShowAliasRows, next.requestShowAliasRows) { it.requestId to it.feedUrl }
    val newRequestShowMember = immutableDelta(before.requestShowMemberRows, next.requestShowMemberRows) { it.requestId to it.position }
    val newRequestSourceCollectionMetadata = immutableDelta(before.requestSourceCollectionMetadataRows, next.requestSourceCollectionMetadataRows) { it.requestId }
    val newRequestReleaseMetadata = immutableDelta(before.requestReleaseMetadataRows, next.requestReleaseMetadataRows) { it.requestId }
    val newRequestMediaReleaseOccurrence = immutableDelta(before.requestMediaReleaseOccurrenceRows, next.requestMediaReleaseOccurrenceRows) { it.requestId }
    val newFirstResultReceipt = immutableDelta(before.firstResultReceiptRows, next.firstResultReceiptRows) { it.id }
    val newSourceRecording = immutableDelta(before.sourceRecordingRows, next.sourceRecordingRows) { it.id }
    val newExecutableSubject = immutableDelta(before.executableSubjectRows, next.executableSubjectRows) { it.id }
    val newExecutionBinding = immutableDelta(before.executionBindingRows, next.executionBindingRows) { it.id }
    val newAcquisitionOutcome = immutableDelta(before.acquisitionOutcomeRows, next.acquisitionOutcomeRows) { it.requestId }
    val newCollectionObservation = immutableDelta(before.collectionObservationRows, next.collectionObservationRows) { it.runId }
    val newCollectionIdentityEvidence = immutableDelta(before.collectionIdentityEvidenceRows, next.collectionIdentityEvidenceRows) { it.runId to it.evidence }
    val newCollectionPageCommit = immutableDelta(before.collectionPageCommitRows, next.collectionPageCommitRows) { it.runId to it.pageIndex }
    val newCollectionPageFailure = immutableDelta(before.collectionPageFailureRows, next.collectionPageFailureRows) { it.id }
    val newCollectionMember = immutableDelta(before.collectionMemberRows, next.collectionMemberRows) { it.runId to it.ordinal }
    val newCollectionSnapshot = immutableDelta(before.collectionSnapshotRows, next.collectionSnapshotRows) { it.id }
    val newCollectionSnapshotMember = immutableDelta(before.collectionSnapshotMemberRows, next.collectionSnapshotMemberRows) { it.snapshotId to it.ordinal }
    val newRequestCollectionMemberMetadata = immutableDelta(before.requestCollectionMemberMetadataRows, next.requestCollectionMemberMetadataRows) { it.requestId }
    val newAdmissionScope = immutableDelta(before.admissionScopeRows, next.admissionScopeRows) { it.id }
    val newAdmissionScopeMember = immutableDelta(before.admissionScopeMemberRows, next.admissionScopeMemberRows) { it.scopeId to it.position }
    val newMaterializationReceipt = immutableDelta(before.materializationReceiptRows, next.materializationReceiptRows) { it.id }
    val newAssetUse = immutableDelta(before.assetUseRows, next.assetUseRows) { it.requestedBindingId to it.materializationAssetId }
    val oldReleases = before.releases.toCapturedReleaseObservations()
    val newReleases = next.releases.toCapturedReleaseObservations()
    require(oldReleases.all { (id, value) -> newReleases[id] == value }) { "Immutable release evidence changed" }

    // Child-first deletions; no REPLACE/CASCADE rewrites provenance.
    removedRows(before.downloadClaimRows, next.downloadClaimRows) { it.id }.takeIf { it.isNotEmpty() }?.let { deleteDownloadClaimRows(it) }
    removedRows(before.downloadBatchMemberRows, next.downloadBatchMemberRows) { it.batchId to it.position }.takeIf { it.isNotEmpty() }?.let { deleteDownloadBatchMemberRows(it) }
    removedRows(before.downloadBatchRows, next.downloadBatchRows) { it.id }.takeIf { it.isNotEmpty() }?.let { deleteDownloadBatchRows(it) }
    removedRows(before.playlistMemberRows, next.playlistMemberRows) { it.id }.takeIf { it.isNotEmpty() }?.let { deletePlaylistMemberRows(it) }
    removedRows(before.favoriteSubjectRows, next.favoriteSubjectRows) { it.subjectId }.takeIf { it.isNotEmpty() }?.let { deleteFavoriteSubjectRows(it) }
    removedRows(before.listeningHistoryRows, next.listeningHistoryRows) { it.id }.takeIf { it.isNotEmpty() }?.let { deleteListeningHistoryRows(it) }
    removedRows(before.catalogProgressRows, next.catalogProgressRows) { it.mediaId }.takeIf { it.isNotEmpty() }?.let { deleteCatalogProgressRows(it) }
    removedRows(before.sourceRecordingResumeRows, next.sourceRecordingResumeRows) { it.subjectId to it.controlsMode }.takeIf { it.isNotEmpty() }?.let { deleteSourceRecordingResumeRows(it) }
    removedRows(before.occurrenceCheckpointRows, next.occurrenceCheckpointRows) { it.occurrenceId }.takeIf { it.isNotEmpty() }?.let { deleteOccurrenceCheckpointRows(it) }
    removedRows(before.executionQueueHeadRows, next.executionQueueHeadRows) { it.contextId }.takeIf { it.isNotEmpty() }?.let { deleteExecutionQueueHeadRows(it) }
    removedRows(before.executionQueueMemberRows, next.executionQueueMemberRows) { it.occurrenceId }.takeIf { it.isNotEmpty() }?.let { deleteExecutionQueueMemberRows(it) }
    removedRows(before.executionQueueRevisionRows, next.executionQueueRevisionRows) { it.contextId }.takeIf { it.isNotEmpty() }?.let { deleteExecutionQueueRevisionRows(it) }
    removedRows(before.executionOccurrenceRows, next.executionOccurrenceRows) { it.id }.takeIf { it.isNotEmpty() }?.let { deleteExecutionOccurrenceRows(it) }
    removedRows(before.collectionRunRows, next.collectionRunRows) { it.id }.takeIf { it.isNotEmpty() }?.let { deleteCollectionRunRows(it) }
    removedRows(before.acquisitionJobRows, next.acquisitionJobRows) { it.requestId }.takeIf { it.isNotEmpty() }?.let { deleteAcquisitionJobRows(it) }
    removedRows(before.savedReleaseRows, next.savedReleaseRows) { it.requestedReleaseMbid }.takeIf { it.isNotEmpty() }?.let { deleteSavedReleaseRows(it) }
    removedRows(before.releaseCatalogEntryRows, next.releaseCatalogEntryRows) { it.requestedReleaseMbid }.takeIf { it.isNotEmpty() }?.let { deleteReleaseCatalogEntryRows(it) }

    // Only changed UNIQUE tuples are staged; all staging precedes upserts.
    val oldExecutionQueueMember = before.executionQueueMemberRows.rowIndex { it.occurrenceId }
    next.executionQueueMemberRows.filter { row -> oldExecutionQueueMember[row.occurrenceId]?.let { old ->
        (Triple(old.contextId, old.position, old.originalPosition)) != (Triple(row.contextId, row.position, row.originalPosition))
    } == true }.map { it.occurrenceId }.chunked(256).forEach { stageExecutionQueueMemberPositions(it) }
    val oldListeningHistory = before.listeningHistoryRows.rowIndex { it.id }
    next.listeningHistoryRows.filter { row -> oldListeningHistory[row.id]?.let { old ->
        (old.position) != (row.position)
    } == true }.map { it.id }.chunked(256).forEach { stageListeningHistoryPositions(it) }
    val oldPlaylistMember = before.playlistMemberRows.rowIndex { it.id }
    next.playlistMemberRows.filter { row -> oldPlaylistMember[row.id]?.let { old ->
        (old.playlistId to old.position) != (row.playlistId to row.position)
    } == true }.map { it.id }.chunked(256).forEach { stagePlaylistMemberPositions(it) }

    appendCapturedReleaseRows(before.releases, next.releases)
    newPhysicalDownloadAdoption.takeIf { it.isNotEmpty() }?.let { insertPhysicalDownloadAdoptionRows(it) }
    newAcquisitionRequest.takeIf { it.isNotEmpty() }?.let { insertAcquisitionRequestRows(it) }
    newRequestMediaMetadata.takeIf { it.isNotEmpty() }?.let { insertRequestMediaMetadataRows(it) }
    newRequestMediaArtist.takeIf { it.isNotEmpty() }?.let { insertRequestMediaArtistRows(it) }
    newRequestAlbumMetadata.takeIf { it.isNotEmpty() }?.let { insertRequestAlbumMetadataRows(it) }
    newRequestAlbumArtist.takeIf { it.isNotEmpty() }?.let { insertRequestAlbumArtistRows(it) }
    newRequestAlbumMember.takeIf { it.isNotEmpty() }?.let { insertRequestAlbumMemberRows(it) }
    newRequestAlbumMemberArtist.takeIf { it.isNotEmpty() }?.let { insertRequestAlbumMemberArtistRows(it) }
    newRequestShowMetadata.takeIf { it.isNotEmpty() }?.let { insertRequestShowMetadataRows(it) }
    newRequestShowAlias.takeIf { it.isNotEmpty() }?.let { insertRequestShowAliasRows(it) }
    newRequestShowMember.takeIf { it.isNotEmpty() }?.let { insertRequestShowMemberRows(it) }
    newRequestSourceCollectionMetadata.takeIf { it.isNotEmpty() }?.let { insertRequestSourceCollectionMetadataRows(it) }
    newRequestReleaseMetadata.takeIf { it.isNotEmpty() }?.let { insertRequestReleaseMetadataRows(it) }
    newRequestMediaReleaseOccurrence.takeIf { it.isNotEmpty() }?.let { insertRequestMediaReleaseOccurrenceRows(it) }
    changedRows(before.releaseCatalogEntryRows, next.releaseCatalogEntryRows) { it.requestedReleaseMbid }.takeIf { it.isNotEmpty() }?.let { upsertReleaseCatalogEntryRows(it) }
    changedRows(before.savedReleaseRows, next.savedReleaseRows) { it.requestedReleaseMbid }.takeIf { it.isNotEmpty() }?.let { upsertSavedReleaseRows(it) }
    newFirstResultReceipt.takeIf { it.isNotEmpty() }?.let { insertFirstResultReceiptRows(it) }
    newSourceRecording.takeIf { it.isNotEmpty() }?.let { insertSourceRecordingRows(it) }
    newExecutableSubject.takeIf { it.isNotEmpty() }?.let { insertExecutableSubjectRows(it) }
    newExecutionBinding.takeIf { it.isNotEmpty() }?.let { insertExecutionBindingRows(it) }
    newAcquisitionOutcome.takeIf { it.isNotEmpty() }?.let { insertAcquisitionOutcomeRows(it) }
    changedRows(before.acquisitionJobRows, next.acquisitionJobRows) { it.requestId }.takeIf { it.isNotEmpty() }?.let { upsertAcquisitionJobRows(it) }
    changedRows(before.collectionRunRows, next.collectionRunRows) { it.id }.takeIf { it.isNotEmpty() }?.let { upsertCollectionRunRows(it) }
    newCollectionObservation.takeIf { it.isNotEmpty() }?.let { insertCollectionObservationRows(it) }
    newCollectionIdentityEvidence.takeIf { it.isNotEmpty() }?.let { insertCollectionIdentityEvidenceRows(it) }
    newCollectionPageCommit.takeIf { it.isNotEmpty() }?.let { insertCollectionPageCommitRows(it) }
    newCollectionPageFailure.takeIf { it.isNotEmpty() }?.let { insertCollectionPageFailureRows(it) }
    newCollectionMember.takeIf { it.isNotEmpty() }?.let { insertCollectionMemberRows(it) }
    newCollectionSnapshot.takeIf { it.isNotEmpty() }?.let { insertCollectionSnapshotRows(it) }
    newCollectionSnapshotMember.takeIf { it.isNotEmpty() }?.let { insertCollectionSnapshotMemberRows(it) }
    newRequestCollectionMemberMetadata.takeIf { it.isNotEmpty() }?.let { insertRequestCollectionMemberMetadataRows(it) }
    newAdmissionScope.takeIf { it.isNotEmpty() }?.let { insertAdmissionScopeRows(it) }
    newAdmissionScopeMember.takeIf { it.isNotEmpty() }?.let { insertAdmissionScopeMemberRows(it) }
    changedRows(before.executionOccurrenceRows, next.executionOccurrenceRows) { it.id }.takeIf { it.isNotEmpty() }?.let { upsertExecutionOccurrenceRows(it) }
    changedRows(before.executionQueueRevisionRows, next.executionQueueRevisionRows) { it.contextId }.takeIf { it.isNotEmpty() }?.let { upsertExecutionQueueRevisionRows(it) }
    changedRows(before.executionQueueMemberRows, next.executionQueueMemberRows) { it.occurrenceId }.takeIf { it.isNotEmpty() }?.let { upsertExecutionQueueMemberRows(it) }
    changedRows(before.executionQueueHeadRows, next.executionQueueHeadRows) { it.contextId }.takeIf { it.isNotEmpty() }?.let { upsertExecutionQueueHeadRows(it) }
    changedRows(before.occurrenceCheckpointRows, next.occurrenceCheckpointRows) { it.occurrenceId }.takeIf { it.isNotEmpty() }?.let { upsertOccurrenceCheckpointRows(it) }
    changedRows(before.sourceRecordingResumeRows, next.sourceRecordingResumeRows) { it.subjectId to it.controlsMode }.takeIf { it.isNotEmpty() }?.let { upsertSourceRecordingResumeRows(it) }
    changedRows(before.catalogProgressRows, next.catalogProgressRows) { it.mediaId }.takeIf { it.isNotEmpty() }?.let { upsertCatalogProgressRows(it) }
    changedRows(before.listeningHistoryRows, next.listeningHistoryRows) { it.id }.takeIf { it.isNotEmpty() }?.let { upsertListeningHistoryRows(it) }
    changedRows(before.favoriteSubjectRows, next.favoriteSubjectRows) { it.subjectId }.takeIf { it.isNotEmpty() }?.let { upsertFavoriteSubjectRows(it) }
    changedRows(before.playlistMemberRows, next.playlistMemberRows) { it.id }.takeIf { it.isNotEmpty() }?.let { upsertPlaylistMemberRows(it) }
    changedRows(before.downloadBatchRows, next.downloadBatchRows) { it.id }.takeIf { it.isNotEmpty() }?.let { upsertDownloadBatchRows(it) }
    changedRows(before.downloadBatchMemberRows, next.downloadBatchMemberRows) { it.batchId to it.position }.takeIf { it.isNotEmpty() }?.let { upsertDownloadBatchMemberRows(it) }
    changedRows(before.downloadClaimRows, next.downloadClaimRows) { it.id }.takeIf { it.isNotEmpty() }?.let { upsertDownloadClaimRows(it) }
    newMaterializationReceipt.takeIf { it.isNotEmpty() }?.let { insertMaterializationReceiptRows(it) }
    newAssetUse.takeIf { it.isNotEmpty() }?.let { insertAssetUseRows(it) }
}
