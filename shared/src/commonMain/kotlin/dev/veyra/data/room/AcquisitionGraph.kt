package dev.veyra.data.room

import dev.veyra.data.*
import dev.veyra.model.*

/** Actual transaction-local rows. Public state is deliberately not an invertible database. */
internal data class AcquisitionGraph(val library: LibraryRows, val rows: AcquisitionRows) {
    val revision: Long get() = requireNotNull(library.meta).revision
    fun snapshot(): AppSnapshot = library.toSnapshot().also(::validateLibrarySnapshot)
    fun state(): AcquisitionRepositoryState = rows.toAcquisitionState(library)
    /** Transient in-memory projection only; the committed graph must still pass [state]. */
    fun stateForBatchProjection(): AcquisitionRepositoryState = rows.toAcquisitionState(library, validate = false)
    fun sameRelations(other: AcquisitionGraph): Boolean = library.sameLibraryRelations(other.library) && rows.sameRelations(other.rows)
    fun withRevision(value: Long): AcquisitionGraph = copy(library = library.copy(meta = requireNotNull(library.meta).copy(revision = value)))
    fun withJob(job: PhysicalDownloadJob): AcquisitionGraph = copy(library = library.copy(
        downloads = library.downloads.withRow(job.toEntity(subjects())) { it.id },
    ))
    fun withAttempt(attempt: PhysicalDownloadAttempt): AcquisitionGraph = copy(library = library.copy(
        attempts = library.attempts.withRow(attempt.toEntity(subjects())) { it.id },
    ))
    fun withFinalization(intent: PhysicalDownloadFinalization): AcquisitionGraph = copy(library = library.copy(
        finalizations = library.finalizations.withRow(intent.toEntity(subjects())) { it.downloadId },
    ))
    fun withAsset(asset: MaterializationAsset): AcquisitionGraph {
        val subjects = subjects()
        return copy(
            library = library.copy(assets = library.assets.withImmutableRow(asset.toEntity(subjects)) { it.id }),
            rows = rows.copy(materializationReceiptRows = rows.materializationReceiptRows.withImmutableRow(asset.receipt().toEntity(subjects)) { it.id }),
        )
    }
    fun withClaim(claim: DownloadClaim): AcquisitionGraph = copy(rows = rows.copy(
        downloadClaimRows = rows.downloadClaimRows.withRow(claim.toEntity()) { it.id },
    ))
    fun withBatch(batch: DownloadBatch): AcquisitionGraph = copy(rows = rows.copy(
        downloadBatchRows = rows.downloadBatchRows.withRow(batch.toEntity()) { it.id },
        downloadBatchMemberRows = rows.downloadBatchMemberRows.filterNot { it.batchId == batch.id.value } + batch.memberRows(),
    ))
    fun subjects(): Map<ExecutableSubjectId, ExecutableSubject> = rows.executableSubjectRows.associate { ExecutableSubjectId(it.id) to it.toSubject() }
}

internal data class AcquisitionMutation<T>(val graph: AcquisitionGraph, val result: (AcquisitionGraph) -> T)
internal data class AcquisitionTransactionResult<T>(val graph: AcquisitionGraph, val value: T)

/** Implemented only by RoomLibraryPersistence and serialized/published by its one LibraryStore. */
internal interface AcquisitionPersistence {
    suspend fun readAcquisitionGraph(): AcquisitionGraph
    suspend fun <T> transactAcquisition(transform: (AcquisitionGraph) -> AcquisitionMutation<T>): AcquisitionTransactionResult<T>
}

internal fun seedAcquisitionRows(library: LibraryRows): AcquisitionRows = AcquisitionRows(
    executableSubjectRows = library.media.map { ExecutableSubjectEntity(it.id, "CATALOG_MEDIA", it.id, null) },
    executionQueueRevisionRows = library.queueContexts.map { ExecutionQueueRevisionEntity(it.id, 0) },
)

/**
 * Existing scalar catalogue/settings commands remain on the same owner. Identity-bearing
 * execution/physical edits must use typed commands; an arbitrary legacy snapshot cannot grant
 * lease/binding/occurrence authority or manufacture post-migration SCHEMA2 history.
 */
internal fun AcquisitionGraph.applyCatalogSnapshot(proposed: AppSnapshot): AcquisitionGraph {
    val previous = snapshot()
    require(proposed.downloads == previous.downloads && proposed.downloadAttempts == previous.downloadAttempts &&
        proposed.downloadFinalizations == previous.downloadFinalizations) { "Schema3 physical commands required" }
    require(proposed.playbackBindings == previous.playbackBindings) { "Typed acquisition binding commands required" }
    require(proposed.queues == previous.queues && proposed.history == previous.history && proposed.progress == previous.progress) {
        "Occurrence-fenced playback commands required"
    }
    require(proposed.favoriteIds == previous.favoriteIds && proposed.playlists == previous.playlists) {
        "Typed saved-lineage commands required"
    }
    validateLibraryTransition(previous, proposed)
    var changed = rows
    proposed.media.keys.forEach { id ->
        changed = changed.copy(executableSubjectRows = changed.executableSubjectRows.withImmutableRow(
            ExecutableSubjectEntity(id, "CATALOG_MEDIA", id, null),
        ) { it.id })
    }
    return copy(library = proposed.toRows(library), rows = changed)
}

/** Accepted MEDIA captures may use only genuine captured catalogue rows, never neutral stand-ins. */
internal fun AcquisitionGraph.ensureCapturedCatalog(request: AcquisitionRequest): AcquisitionGraph {
    val metadata = request.metadata as? MediaCapture ?: return this
    val before = snapshot()
    val old = before.media[metadata.media.id]
    if (old != null) {
        require(old.kind == metadata.media.kind && old.source.provider == metadata.media.source.provider &&
            old.source.canonicalId == metadata.media.source.canonicalId) { "Captured catalogue identity changed" }
        return this
    }
    val knownArtists = metadata.artists.mapNotNull { it.metadata }.associateBy { it.id }
    val next = before.copy(media = before.media + (metadata.media.id to metadata.media.copy(artistIds = metadata.media.artistIds.toList())),
        artists = before.artists + knownArtists)
    // Missing artist/album/show metadata is not fabricated to satisfy a foreign key.
    return applyCatalogSnapshot(next)
}

internal fun validateAcquisitionTransition(before: AcquisitionGraph, next: AcquisitionGraph) {
    val old = before.state()
    val changed = next.state()
    validateLibraryTransition(before.snapshot(), next.snapshot())
    require(changed.libraryRevision == old.libraryRevision + 1)
    old.occurrences.forEach { (id, occurrence) ->
        val value = changed.occurrences.getValue(id)
        require(value.copy(subjectId = occurrence.subjectId, bindingId = occurrence.bindingId,
            selectionState = occurrence.selectionState) == occurrence) { "Occurrence admission provenance changed" }
        if (occurrence.selectionState != OccurrenceSelectionState.PENDING) require(value == occurrence) { "Selected occurrence changed" }
    }
    old.requests.forEach { (id, record) ->
        val value = changed.requests.getValue(id)
        require(value.request == record.request && value.updatedEpochMs >= record.updatedEpochMs)
        record.outcome?.let { require(value.outcome == it && value.state == AcquisitionJobState.COMMITTED) }
        if (record.state == AcquisitionJobState.OUTCOME_UNKNOWN) require(value.state == AcquisitionJobState.OUTCOME_UNKNOWN) {
            "Unknown first query cannot be silently repeated"
        }
    }
    old.batches.forEach { (id, batch) ->
        val value = changed.batches.getValue(id)
        require(value.scopeId == batch.scopeId && value.createdEpochMs == batch.createdEpochMs && value.intentRevision >= batch.intentRevision)
        require(value.members.size == batch.members.size)
        batch.members.zip(value.members).forEach { (prior, later) ->
            require(later.position == prior.position && later.scopeMemberPosition == prior.scopeMemberPosition &&
                later.occurrenceId == prior.occurrenceId && later.requestId == prior.requestId)
            prior.requiredBindingId?.let { require(later.requiredBindingId == it) }
            prior.claimId?.let { require(later.claimId == it) }
        }
    }
    old.claims.forEach { (id, claim) ->
        val value = changed.claims.getValue(id)
        require(value.id == claim.id && value.owner == claim.owner && value.physicalJobId == claim.physicalJobId &&
            value.requestedBindingId == claim.requestedBindingId && value.generation >= claim.generation &&
            value.ownerIntentRevision >= claim.ownerIntentRevision)
        if (value != claim) require(value.generation == claim.generation + 1)
    }
    old.physicalDownloads.forEach { (id, job) ->
        val value = changed.physicalDownloads.getValue(id)
        require(value.ownershipGeneration >= job.ownershipGeneration && value.attemptCount >= job.attemptCount)
        if (value.producer != job.producer) {
            require(job.producer is PhysicalDownloadProducer.Schema2 && job.producer.recordingId == null &&
                value.producer is PhysicalDownloadProducer.Typed)
            val adoption = next.rows.physicalDownloadAdoptionRows.single { it.jobId == id && it.newOwnershipGeneration == value.ownershipGeneration }
            require(adoption.oldOwnershipGeneration == job.ownershipGeneration && adoption.oldMediaId == job.producer.mediaId &&
                adoption.oldBindingId == job.producer.bindingId?.value && adoption.oldRecordingId == null &&
                adoption.newBindingId == value.producer.bindingId.value)
            require(job.assetId == null && id !in old.physicalFinalizations && job.stagingPath == null &&
                job.leaseOwner == null && !job.cleanupPending && job.maintenanceKind == null && job.activeAttemptId == null)
            require(value.bytesDownloaded == 0L && value.totalBytes == null && value.stagingPath == null &&
                value.representationId == null && value.etag == null && value.lastModified == null)
        }
        if (job.maintenanceKind == PhysicalDownloadMaintenanceKind.CLEANUP && value.maintenanceKind == PhysicalDownloadMaintenanceKind.CLEANUP) {
            require(value.stagingPath == job.stagingPath && value.assetId == job.assetId && value.producer == job.producer)
            require(changed.physicalFinalizations[id] == old.physicalFinalizations[id])
            require(changed.claims.values.filter { it.physicalJobId == id }.none { newClaim ->
                val oldClaim = old.claims[newClaim.id]
                oldClaim == null || (newClaim.intent != DownloadBatchIntent.CANCELLED && oldClaim.intent == DownloadBatchIntent.CANCELLED) ||
                    (newClaim.retainCompletedAsset && !oldClaim.retainCompletedAsset)
            }) { "Cleanup reservation cannot gain owners or retain about-to-delete bytes" }
        }
    }
    old.physicalAttempts.forEach { (id, attempt) ->
        val value = changed.physicalAttempts.getValue(id)
        require(value.producer == attempt.producer && value.downloadId == attempt.downloadId && value.number == attempt.number &&
            value.ownershipGeneration == attempt.ownershipGeneration && value.startedEpochMs == attempt.startedEpochMs)
        if (attempt.finishedEpochMs != null) require(value == attempt) { "Retired attempt history changed" }
    }
    old.materializationReceipts.forEach { (id, receipt) -> require(changed.materializationReceipts[id] == receipt) }
    old.materializations.forEach { (id, asset) -> changed.materializations[id]?.let { require(it == asset) } }
}

internal fun LibraryRows.sameLibraryRelations(other: LibraryRows): Boolean =
    meta == other.meta && settings == other.settings &&
    sameRowRelation(artists, other.artists) &&
    sameRowRelation(albums, other.albums) &&
    sameRowRelation(albumArtists, other.albumArtists) &&
    sameRowRelation(media, other.media) &&
    sameRowRelation(tracks, other.tracks) &&
    sameRowRelation(trackArtists, other.trackArtists) &&
    sameRowRelation(albumTracks, other.albumTracks) &&
    sameRowRelation(podcasts, other.podcasts) &&
    sameRowRelation(feedAliases, other.feedAliases) &&
    sameRowRelation(episodes, other.episodes) &&
    sameRowRelation(podcastEpisodes, other.podcastEpisodes) &&
    sameRowRelation(playlists, other.playlists) &&
    sameRowRelation(playlistEntries, other.playlistEntries) &&
    sameRowRelation(queueContexts, other.queueContexts) &&
    sameRowRelation(queueEntries, other.queueEntries) &&
    sameRowRelation(queueCursors, other.queueCursors) &&
    sameRowRelation(favorites, other.favorites) &&
    sameRowRelation(savedEpisodes, other.savedEpisodes) &&
    sameRowRelation(followedPodcasts, other.followedPodcasts) &&
    sameRowRelation(bindings, other.bindings) &&
    sameRowRelation(progress, other.progress) &&
    sameRowRelation(history, other.history) &&
    sameRowRelation(assets, other.assets) &&
    sameRowRelation(downloads, other.downloads) &&
    sameRowRelation(attempts, other.attempts) &&
    sameRowRelation(finalizations, other.finalizations) &&
    sameRowRelation(podcastSpeeds, other.podcastSpeeds) &&
    sameRowRelation(navigation, other.navigation)
