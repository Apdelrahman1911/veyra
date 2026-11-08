package dev.veyra.acquisition

import dev.veyra.data.*
import dev.veyra.model.*
import kotlinx.coroutines.sync.Mutex

/** Room-only admission followed by explicit, bounded current/one-music-neighbour selection. */
class QueueAcquisitionCoordinator(
    private val repository: AcquisitionRepository,
    private val acquisition: AcquisitionCoordinator,
) {
    val transactionOwner: LibraryTransactionOwner get() = repository.transactionOwner
    init { require(repository.transactionOwner === acquisition.transactionOwner) { "Queue selection requires one Room owner" } }
    private val prefetchMutex = Mutex()

    suspend fun admit(
        prepared: PreparedAcquisitionAdmission,
        context: QueueContext,
        expectedQueueRevision: Long,
        operation: QueueAdmissionOperation,
        outgoingCheckpoint: OccurrenceCheckpoint? = null,
    ): AcquisitionRepositoryState = repository.admitQueue(QueueAcquisitionAdmission(
        context, expectedQueueRevision, prepared.expectedCatalogRevision, operation,
        prepared.requests, prepared.scope, prepared.occurrences, outgoingCheckpoint,
    ))

    suspend fun resolveCurrent(
        context: QueueContext,
        expectedQueueRevision: Long,
        expectedCurrentOccurrenceId: ExecutionOccurrenceId,
    ): AcquisitionResolution {
        val state = repository.state.value
        val queue = queue(state, context, expectedQueueRevision, expectedCurrentOccurrenceId)
        val occurrence = state.occurrences[expectedCurrentOccurrenceId] ?: invalid("queue_occurrence_missing")
        return resolve(occurrence, queue)
    }

    /**
     * At most one active prefetch, never an eager search of the whole listing. A failed/unknown
     * next item does not promote a later one, and PODCAST controls never enter this path even
     * when the caller is browsing a music screen or a neutral subject has no catalogue kind.
     */
    suspend fun resolveMusicPrefetch(
        context: QueueContext,
        expectedQueueRevision: Long,
        expectedCurrentOccurrenceId: ExecutionOccurrenceId,
    ): AcquisitionResolution? {
        if (!prefetchMutex.tryLock()) return null
        try {
            val state = repository.state.value
            val queue = queue(state, context, expectedQueueRevision, expectedCurrentOccurrenceId)
            val current = state.occurrences[expectedCurrentOccurrenceId] ?: invalid("queue_occurrence_missing")
            if (current.controlsMode != PlaybackControlsMode.MUSIC || current.selectionState != OccurrenceSelectionState.BOUND ||
                queue.repeat == RepeatMode.ONE) return null
            val index = queue.displayedOrder.indexOf(expectedCurrentOccurrenceId)
            val nextId = (queue.displayedOrder.getOrNull(index + 1)
                ?: if (queue.repeat == RepeatMode.ALL && queue.displayedOrder.size > 1) queue.displayedOrder.first() else null)
                ?: return null
            val next = state.occurrences[nextId] ?: invalid("queue_occurrence_missing")
            if (next.controlsMode != PlaybackControlsMode.MUSIC) return null
            return resolve(next, queue)
        } finally { prefetchMutex.unlock() }
    }

    private suspend fun resolve(occurrence: ExecutionOccurrence, queue: ExecutionQueue): AcquisitionResolution {
        val requestId = occurrence.requestId ?: invalid("queue_legacy_selection_requires_explicit_action")
        return acquisition.resolve(requestId, listOf(OccurrenceAttachmentExpectation(
            occurrence.id, requestId, queue.context, queue.editRevision,
        )))
    }

    private fun queue(
        state: AcquisitionRepositoryState,
        context: QueueContext,
        revision: Long,
        currentId: ExecutionOccurrenceId,
    ): ExecutionQueue {
        val queue = state.queues[context] ?: invalid("queue_missing")
        if (queue.editRevision != revision || queue.currentOccurrenceId != currentId || currentId !in queue.displayedOrder) {
            invalid("queue_selection_action_stale")
        }
        return queue
    }

    private fun invalid(detail: String): Nothing = throw VeyraException(AppFailure(FailureCode.INVALID_STATE, detail))
}
