package dev.veyra.acquisition

import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.playback.Clock
import dev.veyra.playback.SystemClock
import dev.veyra.source.execution.AcquisitionGateway
import dev.veyra.source.execution.CollectionContinuation
import dev.veyra.source.execution.SourceCollectionPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A process-local UI action, not the source cursor. Reference identity rejects duplicate taps,
 * including two taps at the same frontier after a failed attempt. Never persist/reconstruct it.
 */
class CollectionMoreTicket internal constructor(
    val observationId: CollectionObservationId,
    val expectedFrontier: CollectionFrontier,
) {
    override fun toString() = "CollectionMoreTicket(processLocal=true)"
}

data class CollectionTraversalUpdate(
    val run: CollectionRun,
    val snapshot: CollectionSnapshot,
    val more: CollectionMoreTicket?,
)

/**
 * Explicit one-page traversal and immutable prefix cuts. Only public page data crosses Room;
 * source continuations remain here, bounded to [maxOpenTraversals] without silent eviction.
 * There is no background pagination or retry loop. Queue/batch users keep the returned snapshot
 * ID even while a later explicit More or refresh creates another immutable snapshot.
 */
class CollectionTraversalCoordinator(
    private val repository: AcquisitionRepository,
    private val gateway: AcquisitionGateway,
    private val clock: Clock = SystemClock(),
    private val offline: () -> Boolean,
    private val maxOpenTraversals: Int = 16,
) {
    init { require(maxOpenTraversals in 1..32) }

    private class LiveTraversal(
        val source: CollectionSource,
        val limits: CollectionLimits,
        var run: CollectionRun,
        var cursor: CollectionContinuation?,
        var ticket: CollectionMoreTicket,
        var inFlight: Boolean = false,
    )

    private val registryMutex = Mutex()
    private val live = mutableMapOf<CollectionObservationId, LiveTraversal>()
    private var opening = 0

    /** New observation of the accepted source collection. Never re-search the parent title. */
    suspend fun open(requestId: AcquisitionRequestId, limits: CollectionLimits = CollectionLimits()): CollectionTraversalUpdate {
        requireOnline()
        currentCoroutineContext().ensureActive()
        val source = repository.collectionSource(requestId)
        val selection = restoreSelection(source)
        registryMutex.withLock {
            if (opening + live.size >= maxOpenTraversals) invalid("collection_open_traversal_limit")
            opening++
        }
        var observedId: CollectionObservationId? = null
        try {
            requireOnline()
            currentCoroutineContext().ensureActive()
            val page = gateway.collection(source.request, selection, limits)
            val update = withContext(NonCancellable) {
                // A refresh must not append a new response to any old observation by accident.
                if (page.data.observationId in repository.state.value.runs) invalid("collection_observation_not_new")
                validatePage(source, selection, page.data, null, limits)
                observedId = page.data.observationId
                persistPage(source, limits, page, null)
            }
            currentCoroutineContext().ensureActive()
            return update
        } catch (failure: Throwable) {
            val id = observedId
            val settled = id == null || stopAfterUncertainUse(id,
                if (failure is CancellationException) CollectionRunStopReason.CANCELLED else CollectionRunStopReason.CURSOR_LOST)
            rethrow(failure, settled)
        } finally {
            withContext(NonCancellable) { registryMutex.withLock { opening-- } }
        }
    }

    /** One current ticket, one gateway call. A stale/duplicate tap cannot consume the next page. */
    suspend fun more(ticket: CollectionMoreTicket): CollectionTraversalUpdate {
        requireOnline()
        currentCoroutineContext().ensureActive()
        val traversal = registryMutex.withLock {
            val entry = live[ticket.observationId] ?: invalid("collection_cursor_lost")
            if (entry.ticket !== ticket || entry.inFlight || entry.run.frontier != ticket.expectedFrontier) {
                invalid("collection_more_action_stale")
            }
            if (entry.cursor == null) invalid("collection_cursor_lost")
            entry.inFlight = true
            entry
        }
        val cursor = checkNotNull(traversal.cursor)
        var gatewayEntered = false
        try {
            requireOnline()
            currentCoroutineContext().ensureActive()
            gatewayEntered = true
            val page = gateway.more(cursor)
            val update = withContext(NonCancellable) {
                validatePage(traversal.source, restoreSelection(traversal.source), page.data, traversal.run, traversal.limits)
                persistPage(traversal.source, traversal.limits, page, traversal.run)
            }
            currentCoroutineContext().ensureActive()
            return update
        } catch (failure: Throwable) {
            val settled = if (gatewayEntered) stopAfterUncertainUse(ticket.observationId,
                if (failure is CancellationException) CollectionRunStopReason.CANCELLED else CollectionRunStopReason.CURSOR_LOST)
            else withContext(NonCancellable) {
                // Proven unsent. The same action/capability remains available after reconnect.
                registryMutex.withLock { if (live[ticket.observationId] === traversal) traversal.inFlight = false }
                true
            }
            rethrow(failure, settled)
        }
    }

    /** Explicitly stop this still-idle observation, keeping its public observed prefix. */
    suspend fun abandon(ticket: CollectionMoreTicket): CollectionSnapshot =
        abandonIfIdle(ticket) ?: invalid("collection_cursor_lost")

    /**
     * Route-owner cleanup, not a new source action. An absent/consumed/old ticket is an
     * acknowledged no-op, even if the same observation now has a newer ticket at the same
     * frontier. A matching in-flight ticket must first be cancelled and joined by its owner.
     *
     * The entire match/settlement/removal is noncancellable and serialized with More. A failed
     * Room seal keeps this exact ticket/run, with its cursor disabled, for an explicit cleanup
     * retry. It must not become an absent-ticket success or an available source continuation.
     */
    suspend fun abandonIfIdle(ticket: CollectionMoreTicket): CollectionSnapshot? = withContext(NonCancellable) {
        registryMutex.withLock {
            val entry = live[ticket.observationId] ?: return@withLock null
            if (entry.ticket !== ticket || entry.run.frontier != ticket.expectedFrontier) return@withLock null
            if (entry.inFlight) invalid("collection_more_action_stale")
            entry.cursor = null
            val stopped = try { seal(entry.run, CollectionRunStopReason.CANCELLED) }
            catch (failure: Throwable) { rethrow(failure, settlementConfirmed = false) }
            live.remove(ticket.observationId)
            stopped
        }
    }

    /**
     * Room-only restart repair. An unresumable old prefix is incomplete; its cursor is never
     * reconstructed and neither the old listing nor its parent query is fetched implicitly.
     * Call during cold bootstrap, before admitting this coordinator's explicit source actions.
     */
    suspend fun restoreLostCursors(): AcquisitionRepositoryState {
        val state = repository.initialize()
        for (run in state.runs.values) {
            if (run.stoppedReason != null || run.extent.traversal == CollectionTraversal.OBSERVED_TO_END) continue
            val owned = registryMutex.withLock { run.id in live }
            if (!owned) seal(run, CollectionRunStopReason.CURSOR_LOST)
        }
        return repository.state.value
    }

    private suspend fun persistPage(
        source: CollectionSource,
        limits: CollectionLimits,
        page: SourceCollectionPage,
        prior: CollectionRun?,
    ): CollectionTraversalUpdate {
        val appended = repository.appendPage(AppendCollectionPage(
            source.receipt.id, page.data, prior?.frontier,
            if (page.data.disposition == CollectionPageDisposition.FAILED_ATTEMPT) newEntityId("collection-attempt") else null,
            clock.epochMs(),
        ))
        if (appended.disposition == CollectionAppendDisposition.STALE_FRONTIER) invalid("collection_frontier_changed")
        val run = appended.run
        val stop = when {
            run.stoppedReason != null -> run.stoppedReason
            page.data.disposition == CollectionPageDisposition.FAILED_ATTEMPT && run.retryableFailuresAtFrontier >= 3 ->
                CollectionRunStopReason.RETRY_EXHAUSTED
            page.continuation == null && run.extent.traversal != CollectionTraversal.OBSERVED_TO_END ->
                if (run.extent.problem?.code == CollectionProblemCode.EXPIRED_CONTINUATION) CollectionRunStopReason.CURSOR_EXPIRED
                else CollectionRunStopReason.CURSOR_LOST
            else -> null
        }
        val snapshot = seal(run, stop)
        val durableRun = repository.state.value.runs[run.id] ?: invalid("collection_run_missing_after_commit")
        val canContinue = stop == null && page.continuation != null && run.extent.traversal != CollectionTraversal.OBSERVED_TO_END
        val ticket = if (canContinue) CollectionMoreTicket(run.id, durableRun.frontier) else null
        registryMutex.withLock {
            if (ticket == null) live.remove(run.id)
            else live[run.id] = LiveTraversal(source, limits, durableRun, page.continuation, ticket)
        }
        return CollectionTraversalUpdate(durableRun, snapshot, ticket)
    }

    private suspend fun seal(run: CollectionRun, stop: CollectionRunStopReason?) = repository.sealSnapshot(
        SealCollectionSnapshot(CollectionSnapshotId(newEntityId("collection-snapshot")), run.id, run.frontier, clock.epochMs(), stop),
    )

    private suspend fun stopAfterUncertainUse(id: CollectionObservationId, reason: CollectionRunStopReason): Boolean =
        withContext(NonCancellable) {
            registryMutex.withLock { live.remove(id) }
            val run = repository.state.value.runs[id] ?: return@withContext true
            if (run.stoppedReason == null && run.extent.traversal != CollectionTraversal.OBSERVED_TO_END) {
                try { seal(run, reason); true }
                catch (_: Throwable) {
                    // The cursor is already gone. Never reinterpret a storage failure as an
                    // available continuation; cold restore retries only this Room-only repair.
                    false
                }
            } else true
        }

    private fun restoreSelection(source: CollectionSource): SourceAcquisitionResult.Collection {
        val selected = gateway.restore(source.request, source.receipt.observation) as? SourceAcquisitionResult.Collection
            ?: invalid("collection_receipt_not_collection")
        if (source.outcome.requestId != source.request.id || source.outcome.receiptId != source.receipt.id ||
            selected.requestId != source.request.id || selected.receipt != source.receipt.observation ||
            selected.identity != source.outcome.identity || selected.scope != source.outcome.scope) {
            invalid("collection_parent_receipt_mismatch")
        }
        return selected
    }

    private fun validatePage(
        source: CollectionSource,
        selection: SourceAcquisitionResult.Collection,
        data: CollectionPageData,
        prior: CollectionRun?,
        limits: CollectionLimits,
    ) {
        val frontier = prior?.frontier ?: CollectionFrontier(0, 0, 0)
        if (data.selection != selection || data.pageIndex != frontier.nextPageIndex || data.startOrdinal != frontier.nextOrdinal ||
            (prior != null && (prior.id != data.observationId || prior.parentReceiptId != source.receipt.id ||
                data.observation != prior.observation)) || data.members.size > limits.maxMembersPerPage ||
            data.extent.observedMemberCount > limits.maxMembers || data.pageIndex >= limits.maxPages) {
            invalid("collection_page_frontier_mismatch")
        }
        data.observation?.let {
            if (it.id != data.observationId || it.parentRequestId != source.request.id || it.identity != selection.identity ||
                it.scope != selection.scope || it.identityEvidence.isEmpty()) invalid("collection_page_observation_mismatch")
            AcquisitionInputRules.completeTitle(it.title)
        }
        if (data.members.withIndex().any { (offset, member) ->
                val ref = member.reference
                ref.observationId != data.observationId || ref.parentRequestId != source.request.id ||
                    ref.identity != selection.identity || ref.scope != selection.scope ||
                    ref.ordinal != data.startOrdinal + offset || member.entry.ordinal != ref.ordinal
            }) invalid("collection_page_member_mismatch")
        when (data.disposition) {
            CollectionPageDisposition.FAILED_ATTEMPT -> if (data.members.isNotEmpty() ||
                data.extent.observedMemberCount != frontier.nextOrdinal ||
                data.extent.nonPlayableMemberCount != frontier.knownHoleCount ||
                data.extent.traversal != CollectionTraversal.INCOMPLETE || data.extent.problem == null) {
                invalid("collection_failed_attempt_is_not_delta")
            }
            CollectionPageDisposition.OBSERVED_DELTA -> if (data.observation == null ||
                data.extent.observedMemberCount != frontier.nextOrdinal + data.members.size ||
                data.extent.nonPlayableMemberCount != frontier.knownHoleCount + data.members.count { !it.isVideoCandidate }) {
                invalid("collection_delta_extent_mismatch")
            }
        }
    }

    private fun requireOnline() {
        if (offline()) throw VeyraException(AppFailure(FailureCode.OFFLINE_POLICY, "collection_offline"))
    }

    private fun invalid(detail: String): Nothing = throw VeyraException(AppFailure(FailureCode.INVALID_STATE, detail))

    private fun rethrow(failure: Throwable, settlementConfirmed: Boolean): Nothing = when {
        failure is CancellationException -> throw failure
        !settlementConfirmed -> throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "collection_settlement_unconfirmed"))
        failure is VeyraException -> throw failure
        else -> throw VeyraException(AppFailure(FailureCode.UNEXPECTED, "collection_operation_failed"))
    }
}
