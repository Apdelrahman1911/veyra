package dev.veyra.data.room

import dev.veyra.data.*
import dev.veyra.model.*

internal fun OccurrenceCheckpointEntity.toCheckpoint() = OccurrenceCheckpoint(
    ExecutionOccurrenceId(occurrenceId), ExecutionBindingId(bindingId), positionMs, measuredDurationMs,
    observedEpochMs, sessionId, generation, sequence, endedNaturally,
)

internal fun AcquisitionGraph.playbackView() = PlaybackAcquisitionView(
    LibraryAcquisitionView(snapshot(), state()),
    rows.occurrenceCheckpointRows.associate { ExecutionOccurrenceId(it.occurrenceId) to it.toCheckpoint() },
    rows.sourceRecordingResumeRows.associate {
        (ExecutableSubjectId(it.subjectId) to it.controlsMode) to ExecutionOccurrenceId(it.occurrenceId)
    },
    rows.listeningHistoryRows.sortedBy { it.position }.map {
        ExecutionListeningHistory(it.id, ExecutableSubjectId(it.subjectId), it.origin,
            it.occurrenceId?.let(::ExecutionOccurrenceId), it.bindingId?.let(::ExecutionBindingId),
            it.startedEpochMs, it.lastPositionMs, it.endedNaturally)
    },
    rows.catalogProgressRows.associate { it.mediaId to MediaProgress(it.positionMs, it.durationMs,
        it.played, it.updatedEpochMs, it.sessionId, it.generation, it.sequence, it.executionBindingId) },
)

internal fun AcquisitionGraph.applyPlayback(command: AcquisitionPlaybackCommand): AcquisitionMutation<PlaybackAcquisitionView> {
    val graph = when (command) {
        is AcquisitionPlaybackCommand.AdmitQueue -> {
            require(requireNotNull(library.meta).activeContext == command.expectedActiveContext)
            require(command.admission.outgoingCheckpoint == null) // Only the fully fenced wrapper writes it.
            var next = command.outgoing?.let(::withPlaybackCheckpoint) ?: this
            next = next.applyAdmitQueue(command.admission).graph
            val queue = next.state().queues.getValue(command.admission.context)
            val selected = command.startOccurrenceId ?: queue.currentOccurrenceId
            if (command.startOccurrenceId != null) require(command.startOccurrenceId in command.admission.occurrences.map { it.id })
            if (command.activate) {
                require(selected != null)
                next = next.activateQueue(queue, selected, 0, queue.speed, command.startFromBeginning, command.nowEpochMs)
            } else require(!command.startFromBeginning && command.startOccurrenceId == null)
            next
        }
        is AcquisitionPlaybackCommand.Activate -> {
            require(requireNotNull(library.meta).activeContext == command.expectedActiveContext)
            val queue = requireQueue(command.queue)
            val occurrence = state().occurrences.getValue(command.occurrenceId)
            require(command.occurrenceId in queue.displayedOrder)
            // Null is a pending/explicit-current action, not permission to change a supplied binding.
            command.expectedBindingId?.let { require(occurrence.bindingId == it) }
            val next = command.outgoing?.let(::withPlaybackCheckpoint) ?: this
            next.activateQueue(queue, command.occurrenceId, command.positionMs, command.speed, command.startFromBeginning, command.nowEpochMs)
        }
        is AcquisitionPlaybackCommand.EditQueue -> {
            val queue = requireQueue(command.expected)
            require(queue.editRevision < Long.MAX_VALUE)
            require(command.displayedOrder.size <= 5_000 && command.displayedOrder.toSet().size == command.displayedOrder.size)
            require(command.originalOrder.size == command.displayedOrder.size && command.originalOrder.toSet() == command.displayedOrder.toSet())
            require(command.currentOccurrenceId == null && command.displayedOrder.isEmpty() || command.currentOccurrenceId in command.displayedOrder)
            require(command.positionMs >= 0 && command.speed.isFinite() && command.speed in .5f..2f)
            require(queue.context == QueueContext.MUSIC || !command.shuffle && command.repeat == RepeatMode.OFF)
            val state = state()
            command.displayedOrder.forEach { id ->
                val occurrence = state.occurrences.getValue(id)
                require(occurrence.controlsMode.name == queue.context.name)
                if (id !in queue.displayedOrder) {
                    require(command.restoreRetainedOccurrences)
                    require(state.queues.values.none { id in it.displayedOrder } && state.batches.values.none { batch -> batch.members.any { it.occurrenceId == id } })
                }
            }
            val next = command.outgoing?.let(::withPlaybackCheckpoint) ?: this
            next.withExecutionQueue(queue.copy(editRevision = queue.editRevision + 1,
                displayedOrder = command.displayedOrder.toList(), originalOrder = command.originalOrder.toList(),
                currentOccurrenceId = command.currentOccurrenceId, positionMs = command.positionMs,
                speed = command.speed, shuffle = command.shuffle, repeat = command.repeat))
        }
        is AcquisitionPlaybackCommand.AdmitLoad -> {
            val queue = requireQueue(command.queue)
            require(requireNotNull(library.meta).activeContext == queue.context)
            admitNativeLoad(requireNotNull(queue.currentOccurrenceId), command.load)
        }
        is AcquisitionPlaybackCommand.AdmitStage -> {
            val queue = requireQueue(command.queue)
            requireNeighbour(queue, command.incomingOccurrenceId)
            val outgoing = rows.occurrenceCheckpointRows.single { it.occurrenceId == queue.currentOccurrenceId?.value }
            require(outgoing.bindingId == command.outgoingBindingId.value && outgoing.sessionId == command.outgoingSessionId &&
                outgoing.generation == command.outgoingGeneration && command.load.sessionId == command.outgoingSessionId &&
                command.load.generation > command.outgoingGeneration)
            admitNativeLoad(command.incomingOccurrenceId, command.load)
        }
        is AcquisitionPlaybackCommand.Checkpoint -> withPlaybackCheckpoint(command.write)
        is AcquisitionPlaybackCommand.Handover -> {
            val queue = requireQueue(command.queue)
            val incoming = command.incoming.checkpoint
            requireNeighbour(queue, incoming.occurrenceId)
            require(command.outgoing.context == queue.context && command.outgoing.checkpoint.occurrenceId == queue.currentOccurrenceId)
            require(command.incoming.context == queue.context && incoming.sessionId == command.outgoing.checkpoint.sessionId &&
                incoming.generation > command.outgoing.checkpoint.generation)
            var next = withPlaybackCheckpoint(command.outgoing)
            require(queue.editRevision < Long.MAX_VALUE)
            next = next.withExecutionQueue(queue.copy(editRevision = queue.editRevision + 1,
                currentOccurrenceId = incoming.occurrenceId, positionMs = incoming.positionMs))
            // Requires the already-admitted incoming epoch; no unreserved callback can promote.
            next.withPlaybackCheckpoint(command.incoming)
        }
        is AcquisitionPlaybackCommand.CatalogProgress -> {
            require(command.nowEpochMs >= 0 && (command.played != null || command.resetPosition))
            val item = library.media.single { it.id == command.mediaId }
            require(item.kind == MediaKind.EPISODE)
            val next = command.outgoing?.let(::withPlaybackCheckpoint) ?: this
            next.withCatalogProgressIntent(command.mediaId, command.played, command.resetPosition, command.nowEpochMs)
        }
    }
    return AcquisitionMutation(graph) { it.playbackView() }
}

private fun AcquisitionGraph.requireNeighbour(queue: ExecutionQueue, incomingId: ExecutionOccurrenceId) {
    require(queue.context == QueueContext.MUSIC && requireNotNull(library.meta).activeContext == queue.context)
    val index = queue.displayedOrder.indexOf(queue.currentOccurrenceId)
    val nextId = queue.displayedOrder.getOrNull(index + 1)
        ?: if (queue.repeat == RepeatMode.ALL && queue.displayedOrder.size > 1) queue.displayedOrder.first() else null
    require(queue.repeat != RepeatMode.ONE && incomingId != queue.currentOccurrenceId && nextId == incomingId)
    val state = state()
    require(state.occurrences.getValue(requireNotNull(queue.currentOccurrenceId)).controlsMode == PlaybackControlsMode.MUSIC &&
        state.occurrences.getValue(incomingId).controlsMode == PlaybackControlsMode.MUSIC)
}

private fun AcquisitionGraph.requireQueue(expected: ExecutionQueueExpectation): ExecutionQueue {
    val queue = state().queues.getValue(expected.context)
    if (queue.editRevision != expected.editRevision || queue.currentOccurrenceId != expected.currentOccurrenceId) {
        acquisitionInvalid("playback_queue_action_stale")
    }
    return queue
}

private fun AcquisitionGraph.activateQueue(queue: ExecutionQueue, id: ExecutionOccurrenceId, position: Long, speed: Float,
    startFromBeginning: Boolean, nowEpochMs: Long): AcquisitionGraph {
    require(position >= 0 && speed.isFinite() && speed in .5f..2f && queue.editRevision < Long.MAX_VALUE && nowEpochMs >= 0)
    require(id in queue.displayedOrder)
    val occurrence = state().occurrences.getValue(id)
    require(occurrence.controlsMode.name == queue.context.name)
    var next = withExecutionQueue(queue.copy(editRevision = queue.editRevision + 1, currentOccurrenceId = id,
        positionMs = if (startFromBeginning) 0 else position, speed = speed))
    next = next.copy(library = next.library.copy(meta = requireNotNull(next.library.meta).copy(activeContext = queue.context)))
    if (startFromBeginning) {
        require(occurrence.controlsMode == PlaybackControlsMode.PODCAST)
        // Controls mode is independent of catalogue kind. Neutral recordings and catalogue
        // music in long-form mode reset only the queue, never manufacture RSS/episode progress.
        val request = occurrence.requestId?.let { next.state().requests.getValue(it).request }
        val mediaId = (request?.metadata as? MediaCapture)?.media?.takeIf { it.kind == MediaKind.EPISODE }?.id
            ?: (occurrence.subjectId?.let { next.state().subjects[it] } as? ExecutableSubject.CatalogMedia)?.mediaId
                ?.takeIf { catalogId -> next.library.media.single { it.id == catalogId }.kind == MediaKind.EPISODE }
        if (mediaId != null) next = next.withCatalogProgressIntent(mediaId, false, true, nowEpochMs)
    }
    return next
}

private fun AcquisitionGraph.admitNativeLoad(id: ExecutionOccurrenceId, load: ExecutionLoadAdmission): AcquisitionGraph {
    val occurrence = state().occurrences.getValue(id)
    require(occurrence.selectionState == OccurrenceSelectionState.BOUND && occurrence.bindingId == load.bindingId)
    require(load.sessionId.isNotBlank() && load.generation >= 0 && load.positionMs >= 0 && load.nowEpochMs >= 0 &&
        (load.measuredDurationMs == null || load.measuredDurationMs >= 0))
    val previous = rows.occurrenceCheckpointRows.singleOrNull { it.occurrenceId == id.value }
    if (previous?.toCheckpoint() != load.expectedCheckpoint) acquisitionInvalid("playback_load_admission_stale")
    if (previous != null && previous.sessionId == load.sessionId) require(load.generation > previous.generation)
    val row = OccurrenceCheckpointEntity(id.value, load.bindingId.value, load.positionMs, load.measuredDurationMs,
        maxOf(load.nowEpochMs, previous?.observedEpochMs ?: 0), load.sessionId, load.generation, 0, false)
    // Do not call withCheckpoint: this is admission, not new native progress/history evidence.
    return copy(rows = rows.copy(occurrenceCheckpointRows = rows.occurrenceCheckpointRows.withRow(row) { it.occurrenceId }))
}

private fun AcquisitionGraph.withPlaybackCheckpoint(write: ExecutionCheckpointWrite): AcquisitionGraph {
    val checkpoint = write.checkpoint
    val queue = state().queues.getValue(write.context)
    require(requireNotNull(library.meta).activeContext == write.context && queue.currentOccurrenceId == checkpoint.occurrenceId)
    val admitted = rows.occurrenceCheckpointRows.singleOrNull { it.occurrenceId == checkpoint.occurrenceId.value }
        ?: acquisitionInvalid("playback_load_not_admitted")
    require(admitted.sessionId == checkpoint.sessionId && admitted.generation == checkpoint.generation && admitted.bindingId == checkpoint.bindingId.value)
    var next = withCheckpoint(checkpoint).withExecutionQueue(queue.copy(positionMs = checkpoint.positionMs))
    require((write.historyId == null) == (write.historyStartedEpochMs == null))
    if (write.historyId != null) {
        require(write.historyId.isNotBlank() && requireNotNull(write.historyStartedEpochMs) in 0..checkpoint.observedEpochMs)
        val occurrence = state().occurrences.getValue(checkpoint.occurrenceId)
        val existing = rows.listeningHistoryRows.singleOrNull { it.id == write.historyId }
        require(existing == null || existing.subjectId == occurrence.subjectId?.value &&
            existing.occurrenceId == occurrence.id.value && existing.bindingId == checkpoint.bindingId.value &&
            existing.startedEpochMs == write.historyStartedEpochMs)
        val history = ListeningHistoryEntity(write.historyId, requireNotNull(occurrence.subjectId).value,
            occurrence.origin, occurrence.id.value, checkpoint.bindingId.value, requireNotNull(write.historyStartedEpochMs),
            checkpoint.positionMs, checkpoint.endedNaturally || existing?.endedNaturally == true, 0)
        // Keep all migrated history; typed writes never rewrite or evict unrelated historical lineage.
        val values = if (existing == null) rows.listeningHistoryRows.sortedBy { it.position } + history
            else rows.listeningHistoryRows.sortedBy { it.position }.map { if (it.id == history.id) history else it }
        next = next.copy(rows = next.rows.copy(listeningHistoryRows = values.mapIndexed { index, value -> value.copy(position = index) }))
    }
    return next
}

private fun AcquisitionGraph.withCatalogProgressIntent(mediaId: String, played: Boolean?, reset: Boolean, now: Long): AcquisitionGraph {
    require(library.media.single { it.id == mediaId }.kind == MediaKind.EPISODE)
    val prior = rows.catalogProgressRows.singleOrNull { it.mediaId == mediaId }
        ?: CatalogProgressEntity(mediaId, 0, null, false, 0, "", 0, 0, null, null)
    // A user reset retains the last real binding/duration but never invents a native seek ACK.
    val next = prior.copy(positionMs = if (reset) 0 else prior.positionMs,
        played = played ?: if (reset) false else prior.played, updatedEpochMs = maxOf(now, prior.updatedEpochMs))
    return copy(rows = rows.copy(catalogProgressRows = rows.catalogProgressRows.withRow(next) { it.mediaId }))
}
