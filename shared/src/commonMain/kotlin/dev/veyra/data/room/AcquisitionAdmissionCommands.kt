package dev.veyra.data.room

import dev.veyra.data.*
import dev.veyra.model.*

private fun AcquisitionGraph.captureAdmission(
    requests: List<AcquisitionRequest>, scope: AdmissionScope?, occurrences: List<ExecutionOccurrence>,
): AcquisitionGraph {
    require(requests.size in 1..5_000 && requests.size == occurrences.size)
    require(requests.map { it.id }.toSet().size == requests.size && occurrences.map { it.id }.toSet().size == occurrences.size)
    require(occurrences.map { it.requestId }.toSet() == requests.map { it.id }.toSet())
    val before = state()
    require(occurrences.all { it.origin == AcquisitionOrigin.TYPED && it.selectionState == OccurrenceSelectionState.PENDING &&
        it.subjectId == null && it.bindingId == null && it.id !in before.occurrences })
    if (scope == null) require(occurrences.all { it.scopeMember == null }) else {
        require(scope.id !in before.scopes)
        require(scope.members.filter { it.disposition == AdmissionDisposition.INCLUDED }.map { it.requestId }.toSet() ==
            requests.map { it.id }.toSet())
    }
    var next = this
    requests.forEach { next = next.captureRequest(it) }
    if (scope != null) next = next.copy(rows = next.rows.copy(
        admissionScopeRows = next.rows.admissionScopeRows.withImmutableRow(scope.copy(members = scope.members.toList()).toEntity()) { it.id },
        admissionScopeMemberRows = next.rows.admissionScopeMemberRows + scope.memberRows(),
    ))
    return next.copy(rows = next.rows.copy(executionOccurrenceRows = next.rows.executionOccurrenceRows + occurrences.map { it.toEntity() }))
}

/** Canonical queue order; legacy raw rows may survive only for actual retained schema2 occurrences. */
internal fun AcquisitionGraph.withExecutionQueue(queue: ExecutionQueue): AcquisitionGraph {
    val originals = queue.originalOrder.withIndex().associate { it.value to it.index }
    require(originals.keys == queue.displayedOrder.toSet())
    val members = queue.displayedOrder.mapIndexed { index, id ->
        ExecutionQueueMemberEntity(id.value, queue.context, index, originals.getValue(id))
    }
    val oldEntries = library.queueEntries.filter { it.contextId == queue.context }.associateBy { it.id }
    val retained = queue.displayedOrder.mapNotNull { oldEntries[it.value] }
    val originalLegacy = queue.originalOrder.mapNotNull { oldEntries[it.value] }.mapIndexed { index, row -> row.id to index }.toMap()
    val retainedIds = retained.map { it.id }.toSet()
    return copy(
        library = library.copy(
            queueContexts = library.queueContexts.map { if (it.id == queue.context) it.copy(positionMs = queue.positionMs,
                speed = queue.speed, shuffle = queue.shuffle, repeat = queue.repeat, hasOriginalOrder = retained.isNotEmpty()) else it },
            queueEntries = library.queueEntries.filterNot { it.contextId == queue.context } + retained.mapIndexed { index, row ->
                row.copy(position = index, originalPosition = originalLegacy.getValue(row.id))
            },
            queueCursors = library.queueCursors.filterNot { it.contextId == queue.context } +
                listOfNotNull(queue.currentOccurrenceId?.value?.takeIf { it in retainedIds }?.let { QueueCursorEntity(queue.context, it) }),
        ),
        rows = rows.copy(
            executionQueueRevisionRows = rows.executionQueueRevisionRows.withRow(ExecutionQueueRevisionEntity(queue.context, queue.editRevision)) { it.contextId },
            executionQueueMemberRows = rows.executionQueueMemberRows.filterNot { it.contextId == queue.context } + members,
            executionQueueHeadRows = rows.executionQueueHeadRows.filterNot { it.contextId == queue.context } +
                listOfNotNull(queue.currentOccurrenceId?.let { ExecutionQueueHeadEntity(queue.context, it.value) }),
        ),
    )
}

internal fun AcquisitionGraph.applyAdmitQueue(command: QueueAcquisitionAdmission): AcquisitionMutation<AcquisitionRepositoryState> {
    requireRevision(command.expectedCatalogRevision)
    val before = state()
    val queue = before.queues.getValue(command.context)
    if (queue.editRevision != command.expectedQueueRevision) acquisitionInvalid("acquisition_queue_revision_stale")
    require(queue.editRevision < Long.MAX_VALUE)
    require(command.occurrences.all { it.controlsMode.name == command.context.name })
    var next = this
    command.outgoingCheckpoint?.let { checkpoint ->
        require(queue.currentOccurrenceId == checkpoint.occurrenceId)
        next = next.withCheckpoint(checkpoint)
    }
    next = next.captureAdmission(command.requests.toList(), command.scope, command.occurrences.toList())
    val ids = command.occurrences.map { it.id }
    val order = when (command.operation) {
        QueueAdmissionOperation.REPLACE -> ids
        QueueAdmissionOperation.APPEND -> queue.displayedOrder + ids
        QueueAdmissionOperation.INSERT_NEXT -> queue.displayedOrder.toMutableList().also {
            it.addAll(queue.displayedOrder.indexOf(queue.currentOccurrenceId).let { index -> if (index < 0) it.size else index + 1 }, ids)
        }
    }
    val replacing = command.operation == QueueAdmissionOperation.REPLACE
    // Explicit manual admission uses the shown order; no shuffled entries are dropped or inferred.
    next = next.withExecutionQueue(queue.copy(editRevision = queue.editRevision + 1, displayedOrder = order,
        originalOrder = order.toList(), currentOccurrenceId = if (replacing) ids.first() else queue.currentOccurrenceId ?: order.first(),
        positionMs = if (replacing || queue.currentOccurrenceId == null) 0 else queue.positionMs, shuffle = false))
    return AcquisitionMutation(next) { it.state() }
}

/** Current exact occurrence/binding + monotone native samples; no neutral subject can set RSS progress. */
internal fun AcquisitionGraph.withCheckpoint(checkpoint: OccurrenceCheckpoint): AcquisitionGraph {
    val state = state()
    val occurrence = state.occurrences.getValue(checkpoint.occurrenceId)
    require(occurrence.selectionState == OccurrenceSelectionState.BOUND && occurrence.bindingId == checkpoint.bindingId)
    require(checkpoint.positionMs >= 0 && (checkpoint.measuredDurationMs == null || checkpoint.measuredDurationMs >= 0) &&
        checkpoint.observedEpochMs >= 0 && checkpoint.sessionId.isNotBlank() && checkpoint.generation >= 0 && checkpoint.sequence >= 0)
    val row = OccurrenceCheckpointEntity(checkpoint.occurrenceId.value, checkpoint.bindingId.value, checkpoint.positionMs,
        checkpoint.measuredDurationMs, checkpoint.observedEpochMs, checkpoint.sessionId, checkpoint.generation,
        checkpoint.sequence, checkpoint.endedNaturally)
    val previous = rows.occurrenceCheckpointRows.singleOrNull { it.occurrenceId == row.occurrenceId }
    if (previous != null) {
        // A new session/load is admitted by the playback-owner command, never by a delayed sample.
        require(previous.sessionId == row.sessionId && previous.generation == row.generation && row.sequence >= previous.sequence &&
            row.observedEpochMs >= previous.observedEpochMs)
        require(row.sequence != previous.sequence || row == previous)
    }
    var next = rows.copy(occurrenceCheckpointRows = rows.occurrenceCheckpointRows.withRow(row) { it.occurrenceId })
    when (val subject = state.subjects.getValue(requireNotNull(occurrence.subjectId))) {
        is ExecutableSubject.SourceRecordingSubject -> next = next.copy(sourceRecordingResumeRows = next.sourceRecordingResumeRows.withRow(
            SourceRecordingResumeEntity(subject.id.value, occurrence.controlsMode, occurrence.id.value),
        ) { it.subjectId to it.controlsMode })
        is ExecutableSubject.CatalogMedia -> {
            val prior = next.catalogProgressRows.singleOrNull { it.mediaId == subject.mediaId }
            val actualMedia = library.media.single { it.id == subject.mediaId }
            val priorRecording = prior?.executionBindingId?.let { state.bindings[ExecutionBindingId(it)]?.recordingId }
            val currentRecording = state.bindings[checkpoint.bindingId]?.recordingId
            val sameRecording = priorRecording != null && priorRecording == currentRecording
            next = next.copy(catalogProgressRows = next.catalogProgressRows.withRow(CatalogProgressEntity(
                subject.mediaId, row.positionMs, row.measuredDurationMs ?: prior?.durationMs.takeIf { sameRecording },
                prior?.played == true || (checkpoint.endedNaturally && actualMedia.kind == MediaKind.EPISODE),
                row.observedEpochMs, row.sessionId, row.generation, row.sequence, row.bindingId, row.occurrenceId,
            )) { it.mediaId })
        }
    }
    return copy(rows = next)
}

internal fun AcquisitionGraph.applyAdmitBatch(command: BatchAcquisitionAdmission): AcquisitionMutation<DownloadBatch> {
    requireRevision(command.expectedCatalogRevision)
    val batch = command.batch
    require(batch.id !in state().batches && batch.scopeId == command.scope.id && batch.intent == DownloadBatchIntent.ACTIVE &&
        batch.intentRevision == 0L && batch.createdEpochMs >= command.scope.admittedEpochMs && batch.updatedEpochMs == batch.createdEpochMs)
    require(batch.members.all { it.requiredBindingId == null && it.claimId == null && it.state == DownloadBatchMemberState.PENDING_SELECTION && it.failure == null })
    require(batch.members.map { it.occurrenceId } == command.occurrences.map { it.id })
    val next = captureAdmission(command.requests.toList(), command.scope, command.occurrences.toList()).withBatch(batch.copy(members = batch.members.toList()))
    return AcquisitionMutation(next) { it.state().batches.getValue(batch.id) }
}

internal fun AcquisitionGraph.applyBatchIntent(command: UpdateBatchClaims): AcquisitionMutation<DownloadBatch> {
    val state = state()
    val batch = state.batches[command.batchId] ?: acquisitionInvalid("acquisition_batch_missing")
    if (batch.intentRevision != command.expectedIntentRevision) acquisitionInvalid("acquisition_batch_revision_stale")
    require(command.nowEpochMs >= batch.updatedEpochMs && batch.intentRevision < Long.MAX_VALUE)
    val related = state.claims.values.filter { (it.owner as? DownloadClaimOwner.BatchMember)?.batchId == batch.id }
    related.forEach { claim ->
        require(command.nowEpochMs >= claim.updatedEpochMs)
        val job = state.physicalDownloads.getValue(claim.physicalJobId)
        if ((job.cleanupPending || job.maintenanceKind == PhysicalDownloadMaintenanceKind.CLEANUP) &&
            (command.intent != DownloadBatchIntent.CANCELLED || command.retainCompletedAssets && !claim.retainCompletedAsset)) {
            acquisitionInvalid("download_cleanup_reserved")
        }
    }
    if (batch.intent == command.intent && related.all { it.intent == command.intent && it.retainCompletedAsset == command.retainCompletedAssets }) {
        return AcquisitionMutation(this) { it.state().batches.getValue(batch.id) }
    }
    val revision = batch.intentRevision + 1
    var next = this
    related.forEach { claim ->
        require(claim.generation < Long.MAX_VALUE)
        next = next.withClaim(claim.copy(intent = command.intent, retainCompletedAsset = command.retainCompletedAssets,
            generation = claim.generation + 1, ownerIntentRevision = revision, updatedEpochMs = command.nowEpochMs))
    }
    next = next.withBatch(batch.copy(intent = command.intent, intentRevision = revision, updatedEpochMs = command.nowEpochMs,
        members = batch.members.map { member ->
            val keepAvailable = member.state == DownloadBatchMemberState.AVAILABLE && command.retainCompletedAssets
            member.copy(state = when {
                keepAvailable -> DownloadBatchMemberState.AVAILABLE
                command.intent == DownloadBatchIntent.CANCELLED -> DownloadBatchMemberState.CANCELLED
                command.intent == DownloadBatchIntent.PAUSED -> DownloadBatchMemberState.PAUSED
                member.requiredBindingId != null -> DownloadBatchMemberState.WAITING_FOR_JOB
                else -> when (state.requests.getValue(member.requestId).state) {
                    AcquisitionJobState.OUTCOME_UNKNOWN -> DownloadBatchMemberState.OUTCOME_UNKNOWN
                    AcquisitionJobState.COMMITTED -> when (state.requests.getValue(member.requestId).outcome) {
                        // A pause/revision race may reject the attachment after accepting the
                        // Single. Resume reattaches it locally; it is not an unplayable result.
                        is AcquisitionOutcome.Single -> DownloadBatchMemberState.PENDING_SELECTION
                        is AcquisitionOutcome.Collection -> DownloadBatchMemberState.NEEDS_COLLECTION_ACTION
                        else -> DownloadBatchMemberState.UNPLAYABLE
                    }
                    else -> DownloadBatchMemberState.PENDING_SELECTION
                }
            })
        }))
    return AcquisitionMutation(next) { it.state().batches.getValue(batch.id) }
}

internal fun AcquisitionGraph.applyAttachBatchClaim(command: AttachBatchDownloadClaim): AcquisitionMutation<DownloadClaim> {
    val state = state()
    val batch = state.batches[command.batchId] ?: acquisitionInvalid("acquisition_batch_missing")
    if (batch.intentRevision != command.expectedIntentRevision || batch.intent != DownloadBatchIntent.ACTIVE) acquisitionInvalid("acquisition_batch_revision_stale")
    val member = batch.members.single { it.position == command.memberPosition }
    require(member.requiredBindingId == command.requiredBindingId && state.occurrences.getValue(member.occurrenceId).bindingId == command.requiredBindingId)
    require(command.claim.owner == DownloadClaimOwner.BatchMember(batch.id, member.position) &&
        command.claim.requestedBindingId == command.requiredBindingId && command.claim.intent == batch.intent &&
        command.claim.ownerIntentRevision == batch.intentRevision)
    val job = state.physicalDownloads.getValue(command.claim.physicalJobId)
    val binding = state.bindings.getValue(command.requiredBindingId)
    require(job.producer.subjectId == binding.subjectId && job.producer.recordingId == binding.recordingId &&
        job.producer.recordingId != null && !job.cleanupPending && job.maintenanceKind != PhysicalDownloadMaintenanceKind.CLEANUP)
    val existing = state.claims[command.claim.id]
    if (existing != null) require(existing == command.claim && member.claimId == command.claim.id)
    else require(command.claim.generation == 0L && member.claimId == null)
    val next = withClaim(command.claim).withBatch(batch.copy(members = batch.members.map {
        if (it.position == member.position) it.copy(claimId = command.claim.id) else it
    }))
    return AcquisitionMutation(next) { it.state().claims.getValue(command.claim.id) }
}
