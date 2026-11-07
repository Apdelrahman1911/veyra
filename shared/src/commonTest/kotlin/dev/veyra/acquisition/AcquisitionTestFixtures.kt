package dev.veyra.acquisition

import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.playback.Clock
import dev.veyra.source.execution.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Authored orchestration test double ONLY. Not Room, migrations, native execution or persistence evidence. */
internal class AcquisitionTestRepository : AcquisitionRepository {
    override val transactionOwner = LibraryTransactionOwner()
    override val state = MutableStateFlow(AcquisitionRepositoryState(0))
    val captured = mutableListOf<CaptureAcquisition>()
    val claimed = mutableListOf<ClaimAcquisition>()
    val committed = mutableListOf<CommitAcquisition>()
    val unknown = mutableListOf<MarkAcquisitionOutcomeUnknown>()
    val unsent = mutableListOf<AcquisitionLease>()
    val appended = mutableListOf<AppendCollectionPage>()
    val sealed = mutableListOf<SealCollectionSnapshot>()
    val queueAdmissions = mutableListOf<QueueAcquisitionAdmission>()
    val batchAdmissions = mutableListOf<BatchAcquisitionAdmission>()
    val batchIntents = mutableListOf<UpdateBatchClaims>()
    val attachments = mutableListOf<AttachAcquisitionOccurrence>()
    val downloadClaims = mutableListOf<AttachBatchDownloadClaim>()
    val pages = mutableMapOf<CollectionObservationId, List<CollectionPageData>>()
    var initializeCalls = 0
    var afterClaim: suspend () -> Unit = {}
    var beforeUnsentRelease: suspend (AcquisitionLease) -> Unit = {}
    var beforeCommit: suspend (CommitAcquisition) -> Unit = {}
    var beforeSeal: suspend (SealCollectionSnapshot) -> Unit = {}
    var commitOverride: (suspend (CommitAcquisition) -> AcquisitionCommitResult)? = null

    override suspend fun initialize(): AcquisitionRepositoryState { initializeCalls++; return state.value }
    override suspend fun request(id: AcquisitionRequestId) = state.value.requests[id]

    override suspend fun capture(command: CaptureAcquisition): AcquisitionRecord {
        captured += command
        val previous = state.value.requests[command.request.id]
        if (previous != null) { check(previous.request == command.request); return previous }
        val record = AcquisitionRecord(command.request, AcquisitionJobState.READY, updatedEpochMs = command.request.capturedEpochMs)
        state.value = state.value.copy(requests = state.value.requests + (record.request.id to record))
        return record
    }

    override suspend fun claim(command: ClaimAcquisition): AcquisitionClaimResult {
        claimed += command
        val old = state.value.requests.getValue(command.requestId)
        when (old.state) {
            AcquisitionJobState.COMMITTED -> return AcquisitionClaimResult.AlreadyCommitted(old)
            AcquisitionJobState.OUTCOME_UNKNOWN -> return AcquisitionClaimResult.OutcomeUnknown(old)
            AcquisitionJobState.CLAIMED -> {
                val lease = checkNotNull(old.lease)
                if (lease.expiresEpochMs > command.nowEpochMs) return AcquisitionClaimResult.Busy(old)
                return AcquisitionClaimResult.OutcomeUnknown(markOutcomeUnknown(MarkAcquisitionOutcomeUnknown(lease, command.nowEpochMs)))
            }
            AcquisitionJobState.READY -> Unit
        }
        val lease = AcquisitionLease(command.requestId, command.token, command.owner, command.nowEpochMs,
            command.nowEpochMs + command.leaseDurationMs)
        state.value = state.value.copy(requests = state.value.requests +
            (command.requestId to old.copy(state = AcquisitionJobState.CLAIMED, lease = lease, updatedEpochMs = command.nowEpochMs)))
        afterClaim()
        return AcquisitionClaimResult.Claimed(old.request, lease)
    }

    override suspend fun releaseUnsentClaim(lease: AcquisitionLease, nowEpochMs: Long): Boolean {
        unsent += lease
        beforeUnsentRelease(lease)
        val old = state.value.requests.getValue(lease.requestId)
        if (old.state != AcquisitionJobState.CLAIMED || old.lease != lease || lease.expiresEpochMs <= nowEpochMs) return false
        state.value = state.value.copy(requests = state.value.requests +
            (lease.requestId to old.copy(state = AcquisitionJobState.READY, lease = null, updatedEpochMs = nowEpochMs)))
        return true
    }

    override suspend fun commit(command: CommitAcquisition): AcquisitionCommitResult {
        committed += command
        beforeCommit(command)
        commitOverride?.let { return it(command) }
        val old = state.value.requests.getValue(command.lease.requestId)
        check(old.state == AcquisitionJobState.CLAIMED && old.lease == command.lease)
        check(command.result.requestId == old.request.id)
        var updated = state.value
        val raw = command.result.receiptOrNull()
        if (raw != null) {
            check(raw.requestId == old.request.id && raw.queryTitle == old.request.completeTitle && raw.entry.ordinal == 0)
            val receipt = FirstResultReceipt.Observed(checkNotNull(command.receiptId), raw, command.nowEpochMs)
            updated = updated.copy(receipts = updated.receipts + (receipt.id to receipt))
        } else check(command.receiptId == null)
        val outcome = when (val result = command.result) {
            is SourceAcquisitionResult.Single -> {
                val allocation = checkNotNull(command.bindingAllocation)
                val recording = updated.recordings.values.firstOrNull { it.canonicalId == result.recording.id }
                    ?: SourceRecording(allocation.newRecordingId, result.recording.id, result.recording.publicUrl)
                val subject = (old.request.metadata as? MediaCapture)?.let {
                    ExecutableSubject.CatalogMedia(ExecutableSubjectId(it.media.id), it.media.id)
                } ?: updated.subjects.values.filterIsInstance<ExecutableSubject.SourceRecordingSubject>()
                    .firstOrNull { it.recordingId == recording.id }
                    ?: ExecutableSubject.SourceRecordingSubject(allocation.newNeutralSubjectId, recording.id)
                val binding = ExecutionBinding(allocation.bindingId, subject.id, checkNotNull(command.receiptId),
                    recording.id, ExecutionBindingOrigin.TYPED)
                updated = updated.copy(recordings = updated.recordings + (recording.id to recording),
                    subjects = updated.subjects + (subject.id to subject), bindings = updated.bindings + (binding.id to binding))
                AcquisitionOutcome.Single(old.request.id, binding.firstReceiptId, binding.id)
            }
            is SourceAcquisitionResult.Collection -> AcquisitionOutcome.Collection(old.request.id,
                checkNotNull(command.receiptId), result.identity, result.scope)
            is SourceAcquisitionResult.Unplayable -> AcquisitionOutcome.Unplayable(old.request.id,
                command.receiptId, command.failureStage, result.problem)
        }
        val record = old.copy(state = AcquisitionJobState.COMMITTED, lease = null, outcome = outcome, updatedEpochMs = command.nowEpochMs)
        state.value = updated.copy(requests = updated.requests + (record.request.id to record))
        val attached = command.attachments.filter { attachOccurrence(AttachAcquisitionOccurrence(it)) }.map { it.occurrenceId }.toSet()
        return AcquisitionCommitResult.Committed(record, attached, command.attachments.map { it.occurrenceId }.toSet() - attached)
    }

    override suspend fun markOutcomeUnknown(command: MarkAcquisitionOutcomeUnknown): AcquisitionRecord {
        unknown += command
        val old = state.value.requests.getValue(command.lease.requestId)
        if (old.state != AcquisitionJobState.CLAIMED || old.lease != command.lease) return old
        val record = old.copy(state = AcquisitionJobState.OUTCOME_UNKNOWN, lease = null, updatedEpochMs = command.nowEpochMs)
        state.value = state.value.copy(requests = state.value.requests + (record.request.id to record))
        return record
    }

    override suspend fun collectionSource(id: AcquisitionRequestId): CollectionSource {
        val record = state.value.requests.getValue(id)
        val outcome = record.outcome as AcquisitionOutcome.Collection
        return CollectionSource(record.request, state.value.receipts.getValue(outcome.receiptId) as FirstResultReceipt.Observed, outcome)
    }

    override suspend fun appendPage(command: AppendCollectionPage): CollectionAppendResult {
        appended += command
        val data = command.data
        val old = state.value.runs[data.observationId]
        val frontier = old?.frontier ?: CollectionFrontier(0, 0, 0)
        if (command.expectedFrontier != null && command.expectedFrontier != frontier) {
            return CollectionAppendResult(CollectionAppendDisposition.STALE_FRONTIER, checkNotNull(old))
        }
        val observed = data.disposition == CollectionPageDisposition.OBSERVED_DELTA
        if (observed) pages[data.observationId] = pages[data.observationId].orEmpty() + data.copy(members = data.members.toList())
        val run = CollectionRun(data.observationId, data.selection.requestId, command.parentReceiptId,
            data.selection.identity, data.selection.scope, data.observation,
            if (observed) CollectionFrontier(data.pageIndex + 1, data.extent.observedMemberCount, data.extent.nonPlayableMemberCount) else frontier,
            data.extent, if (observed) 0 else (old?.retryableFailuresAtFrontier ?: 0) + 1, null,
            old?.startedEpochMs ?: command.receivedEpochMs, command.receivedEpochMs)
        state.value = state.value.copy(runs = state.value.runs + (run.id to run))
        return CollectionAppendResult(if (observed) CollectionAppendDisposition.COMMITTED_DELTA else CollectionAppendDisposition.RECORDED_FAILURE, run)
    }

    override suspend fun sealSnapshot(command: SealCollectionSnapshot): CollectionSnapshot {
        sealed += command
        beforeSeal(command)
        var run = state.value.runs.getValue(command.observationId)
        check(run.frontier == command.expectedFrontier)
        if (command.stopRun != null) run = run.copy(stoppedReason = command.stopRun,
            extent = run.extent.copy(traversal = CollectionTraversal.INCOMPLETE))
        val deltas = pages[run.id].orEmpty()
        val snapshot = CollectionSnapshot(command.id, run.id, run.parentRequestId, run.parentReceiptId, run.observation,
            deltas.lastOrNull()?.pageIndex, deltas.flatMap { it.members }, run.extent, command.sealedEpochMs)
        state.value = state.value.copy(runs = state.value.runs + (run.id to run), snapshots = state.value.snapshots + (snapshot.id to snapshot))
        return snapshot
    }

    override suspend fun admitQueue(command: QueueAcquisitionAdmission): AcquisitionRepositoryState {
        queueAdmissions += command
        val old = state.value.queues[command.context] ?: ExecutionQueue(command.context, 0, emptyList(), emptyList(), null, 0, 1f, false, RepeatMode.OFF)
        if (command.expectedQueueRevision != old.editRevision) throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "test_queue_stale"))
        command.requests.forEach { capture(CaptureAcquisition(it, command.expectedCatalogRevision)) }
        val additions = command.occurrences.map { it.id }
        val order = when (command.operation) {
            QueueAdmissionOperation.REPLACE -> additions
            QueueAdmissionOperation.APPEND -> old.displayedOrder + additions
            QueueAdmissionOperation.INSERT_NEXT -> {
                val split = (old.currentOccurrenceId?.let { old.displayedOrder.indexOf(it) } ?: -1) + 1
                old.displayedOrder.take(split) + additions + old.displayedOrder.drop(split)
            }
        }
        val queue = old.copy(editRevision = old.editRevision + 1, displayedOrder = order, originalOrder = order,
            currentOccurrenceId = if (command.operation == QueueAdmissionOperation.REPLACE) order.firstOrNull() else old.currentOccurrenceId ?: order.firstOrNull())
        state.value = state.value.copy(occurrences = state.value.occurrences + command.occurrences.associateBy { it.id },
            queues = state.value.queues + (command.context to queue), scopes = state.value.scopes + listOfNotNull(command.scope).associateBy { it.id })
        return state.value
    }

    override suspend fun admitBatch(command: BatchAcquisitionAdmission): DownloadBatch {
        batchAdmissions += command
        command.requests.forEach { capture(CaptureAcquisition(it, command.expectedCatalogRevision)) }
        state.value = state.value.copy(occurrences = state.value.occurrences + command.occurrences.associateBy { it.id },
            batches = state.value.batches + (command.batch.id to command.batch), scopes = state.value.scopes + (command.scope.id to command.scope))
        return command.batch
    }

    override suspend fun attachOccurrence(command: AttachAcquisitionOccurrence): Boolean {
        attachments += command
        val expected = command.expected
        val occurrence = state.value.occurrences[expected.occurrenceId] ?: return false
        if (occurrence.requestId != expected.requestId) return false
        if (expected.context != null) {
            val queue = state.value.queues[expected.context] ?: return false
            if (queue.editRevision != expected.expectedQueueRevision || occurrence.id !in queue.displayedOrder) return false
        }
        val batch = expected.batchId?.let { state.value.batches[it] ?: return false }
        if (batch != null && (batch.intentRevision != expected.expectedBatchIntentRevision || batch.intent != DownloadBatchIntent.ACTIVE)) return false
        val outcome = state.value.requests[expected.requestId]?.outcome ?: return false
        val binding = (outcome as? AcquisitionOutcome.Single)?.let { state.value.bindings.getValue(it.bindingId) }
        val selection = when (outcome) {
            is AcquisitionOutcome.Single -> OccurrenceSelectionState.BOUND
            is AcquisitionOutcome.Collection -> OccurrenceSelectionState.NEEDS_COLLECTION_ACTION
            is AcquisitionOutcome.Unplayable -> OccurrenceSelectionState.UNPLAYABLE
        }
        if (occurrence.selectionState != OccurrenceSelectionState.PENDING) return occurrence.bindingId == binding?.id && occurrence.selectionState == selection
        state.value = state.value.copy(occurrences = state.value.occurrences + (occurrence.id to occurrence.copy(
            bindingId = binding?.id, subjectId = binding?.subjectId, selectionState = selection)))
        if (batch != null) {
            val updated = batch.copy(members = batch.members.map { member -> if (member.occurrenceId != occurrence.id) member else member.copy(
                requiredBindingId = binding?.id, state = when (selection) {
                    OccurrenceSelectionState.BOUND -> DownloadBatchMemberState.WAITING_FOR_JOB
                    OccurrenceSelectionState.NEEDS_COLLECTION_ACTION -> DownloadBatchMemberState.NEEDS_COLLECTION_ACTION
                    else -> DownloadBatchMemberState.UNPLAYABLE
                }) })
            state.value = state.value.copy(batches = state.value.batches + (updated.id to updated))
        }
        return true
    }

    override suspend fun updateBatchClaims(command: UpdateBatchClaims): DownloadBatch {
        batchIntents += command
        val batch = state.value.batches.getValue(command.batchId)
        check(batch.intentRevision == command.expectedIntentRevision)
        val updated = batch.copy(intent = command.intent, intentRevision = batch.intentRevision + 1, updatedEpochMs = command.nowEpochMs)
        state.value = state.value.copy(libraryRevision = state.value.libraryRevision + 1,
            batches = state.value.batches + (updated.id to updated), claims = state.value.claims.mapValues { (_, claim) ->
            if ((claim.owner as? DownloadClaimOwner.BatchMember)?.batchId == batch.id) claim.copy(intent = command.intent,
                retainCompletedAsset = command.retainCompletedAssets, updatedEpochMs = command.nowEpochMs,
                generation = claim.generation + 1, ownerIntentRevision = updated.intentRevision) else claim
        })
        return updated
    }

    override suspend fun attachBatchDownloadClaim(command: AttachBatchDownloadClaim): DownloadClaim {
        downloadClaims += command
        val batch = state.value.batches.getValue(command.batchId)
        check(batch.intentRevision == command.expectedIntentRevision)
        val member = batch.members.single { it.position == command.memberPosition }
        check(member.requiredBindingId == command.requiredBindingId)
        val updated = batch.copy(members = batch.members.map { if (it.position == member.position) it.copy(claimId = command.claim.id) else it })
        state.value = state.value.copy(batches = state.value.batches + (updated.id to updated), claims = state.value.claims + (command.claim.id to command.claim))
        return command.claim
    }
}

internal class AcquisitionTestGateway : AcquisitionGateway {
    val acquisitions = mutableListOf<AcquisitionRequest>()
    val inspections = mutableListOf<Pair<AcquisitionRequest, SourceAcquisitionResult.Single>>()
    val collections = mutableListOf<Pair<AcquisitionRequest, SourceAcquisitionResult.Collection>>()
    val continuations = mutableListOf<CollectionContinuation>()
    var restores = 0
    var acquireResult: suspend (AcquisitionRequest) -> SourceAcquisitionResult = { authoredSingle(it) }
    var collectionResult: suspend (AcquisitionRequest, SourceAcquisitionResult.Collection) -> SourceCollectionPage = { _, _ -> error("Unexpected collection") }
    var moreResult: suspend (CollectionContinuation) -> SourceCollectionPage = { error("Unexpected More") }

    override suspend fun acquire(request: AcquisitionRequest): SourceAcquisitionResult { acquisitions += request; return acquireResult(request) }
    override fun restore(request: AcquisitionRequest, receipt: FirstResultObservation): SourceAcquisitionResult {
        restores++
        check(receipt.requestId == request.id && receipt.queryTitle == request.completeTitle && receipt.entry.ordinal == 0)
        return if (receipt.entry.kind == AcquisitionEntryKind.VIDEO) SourceAcquisitionResult.Single(request.id, receipt,
            SourceDisplayItem(receipt.entry.id!!, receipt.entry.title!!, receipt.canonicalUrl!!, AcquisitionEntryKind.VIDEO))
        else SourceAcquisitionResult.Collection(request.id, receipt, authoredIdentity(), CollectionScope.PLAYLIST_MEMBERS)
    }
    override suspend fun collection(request: AcquisitionRequest, selection: SourceAcquisitionResult.Collection, limits: CollectionLimits): SourceCollectionPage {
        collections += request to selection
        return collectionResult(request, selection)
    }
    override suspend fun more(continuation: CollectionContinuation): SourceCollectionPage { continuations += continuation; return moreResult(continuation) }
    override suspend fun inspect(request: AcquisitionRequest, selection: SourceAcquisitionResult.Single): AcquisitionInspection {
        inspections += request to selection
        return object : AcquisitionInspection { override val selection = selection }
    }
}

internal class AcquisitionTestClock(var now: Long = 123_000) : Clock {
    override fun epochMs() = now
    override fun monotonicMs() = now
}

internal class AuthoredContinuation : CollectionContinuation

internal fun authoredMedia(id: String = "authored-media", title: String = "  Complete requested title — العنوان الكامل  ",
    kind: MediaKind = MediaKind.MUSIC) = MediaItem(id, title, kind, SourceRef(SourceProvider.COMMONS, id))

internal fun authoredRequest(id: String = "authored-request", metadata: RequestedMetadata = MediaCapture(authoredMedia())) =
    AcquisitionRequest(AcquisitionRequestId(id), metadata.completeTitle, metadata, 123_000)

internal fun authoredIdentity() = CollectionIdentity(AcquisitionEntryKind.PLAYLIST, "PLauthored", "https://www.youtube.com/playlist?list=PLauthored")

internal fun authoredSingle(request: AcquisitionRequest, videoId: String = "LMNOPQRSTUV") = SourceAcquisitionResult.Single(request.id,
    FirstResultObservation(request.id, request.completeTitle, SourceEntry(AcquisitionEntryKind.VIDEO, videoId,
        "Different recording C", "https://www.youtube.com/watch?v=$videoId"), "https://www.youtube.com/watch?v=$videoId"),
    SourceDisplayItem(videoId, "Different recording C", "https://www.youtube.com/watch?v=$videoId", AcquisitionEntryKind.VIDEO))

internal fun authoredCollection(request: AcquisitionRequest): SourceAcquisitionResult.Collection {
    val identity = authoredIdentity()
    return SourceAcquisitionResult.Collection(request.id, FirstResultObservation(request.id, request.completeTitle,
        SourceEntry(AcquisitionEntryKind.PLAYLIST, identity.id, "Actual listing", identity.canonicalUrl), identity.canonicalUrl), identity, CollectionScope.PLAYLIST_MEMBERS)
}

internal fun authoredObservation(request: AcquisitionRequest, id: String = "authored-observation") = CollectionObservation(
    CollectionObservationId(id), request.id, authoredIdentity(), CollectionScope.PLAYLIST_MEMBERS, "Actual complete listing",
    CollectionTitleOrigin.PLAYLIST_METADATA, setOf(CollectionIdentityEvidence.PLAYLIST_LIST), authoredIdentity().canonicalUrl)

internal fun authoredMember(observation: CollectionObservation, ordinal: Int, videoId: String = "ABCDEFGHIJK", hole: Boolean = false): CollectionMember {
    val title = if (hole) "[Private video]" else "  Complete member B — العضو $ordinal  "
    val raw = SourceEntry(AcquisitionEntryKind.VIDEO, videoId, title, "https://www.youtube.com/watch?v=$videoId",
        if (hole) SourceEntryIssue.UNAVAILABLE else null, ordinal)
    return CollectionMember(CollectionMemberReference(observation.id, observation.parentRequestId, observation.identity, observation.scope, ordinal),
        raw, if (hole) null else SourceDisplayItem(videoId, title, raw.publicUrl!!, AcquisitionEntryKind.VIDEO))
}

internal fun authoredSnapshot(request: AcquisitionRequest = authoredRequest("parent", AlbumCapture(Album("album", "Complete edition title"))),
    holes: Set<Int> = emptySet(), count: Int = 3, traversal: CollectionTraversal = CollectionTraversal.OBSERVED_TO_END,
    observationId: String = "authored-observation"): CollectionSnapshot {
    val observation = authoredObservation(request, observationId)
    return CollectionSnapshot(CollectionSnapshotId("snapshot-$observationId"), observation.id, request.id,
        FirstResultReceiptId("parent-receipt"), observation, 0, (0 until count).map { authoredMember(observation, it, hole = it in holes) },
        CollectionExtent(traversal, count, holes.size), 123_000)
}
