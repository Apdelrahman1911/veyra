package dev.veyra.acquisition

import dev.veyra.data.AcquisitionRepositoryState
import dev.veyra.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Authored pure-projection cases, not Room restart, source selection or native execution tests. */
class PendingDownloadBatchWorkTest {
    @Test fun durableReadyMemberCanBeProjectedAfterRestartWithoutAnInMemoryAdmission() {
        val fixture = fixture()
        val before = fixture.state
        val first = projectPendingDownloadBatchWork(before, 250)
        val restored = projectPendingDownloadBatchWork(before.copy(), 250)
        assertEquals(first, restored)
        assertEquals(fixture.key, first.work.single().key)
        assertEquals(PendingDownloadBatchWorkKind.SELECT_READY, first.work.single().kind)
        assertTrue(first.work.single().requiresNetwork)
        assertNull(first.nextLocalWakeEpochMs)
        assertTrue(first.blocked.isEmpty())
        assertEquals(before, fixture.state)
        assertEquals("  Complete requested title — العنوان الكامل  ", fixture.record.request.completeTitle)
    }

    @Test fun orderingUsesAdmissionTimeThenBatchIdThenPositionNotMapInsertion() {
        val later = fixture("later", created = 20)
        val b = fixture("b", count = 3, created = 10).let { original ->
            changeBatch(original) { it.copy(members = it.members.reversed()) }
        }
        val a = fixture("a", created = 10)
        val state = merge(later, b, a)
        val projected = projectPendingDownloadBatchWork(state, 250).work.map { it.key }
        assertEquals(listOf(a.key) + b.batch.members.sortedBy { it.position }.map { b.key(it) } + later.key, projected)
        assertEquals(projected, projectPendingDownloadBatchWork(state.copy(batches = state.batches.entries.reversed()
            .associate { it.toPair() }), 250).work.map { it.key })
    }

    @Test fun pausedAndCancelledBatchesHaveNoWorkOrClaimExpiryWake() {
        val claimed = claimed(fixture(), 500)
        listOf(DownloadBatchIntent.PAUSED, DownloadBatchIntent.CANCELLED).forEach { intent ->
            val stopped = changeBatch(claimed) { it.copy(intent = intent, intentRevision = it.intentRevision + 1) }
            val projection = projectPendingDownloadBatchWork(stopped.state, 250)
            assertTrue(projection.work.isEmpty())
            assertTrue(projection.blocked.isEmpty())
            assertNull(projection.nextLocalWakeEpochMs)
            assertNull(rejoinPendingDownloadBatchWork(stopped.state, claimed.key, 250))
        }
    }

    @Test fun committedSingleWithRejectedPauseAttachmentUsesLocalAttachDespiteUnplayableLabel() {
        val accepted = committed(fixture())
        val resumed = changeBatch(accepted) { batch -> batch.copy(intentRevision = 4,
            members = batch.members.map { it.copy(state = DownloadBatchMemberState.UNPLAYABLE) }) }
        val work = projectPendingDownloadBatchWork(resumed.state, 250).work.single()
        assertEquals(PendingDownloadBatchWorkKind.ATTACH_COMMITTED, work.kind)
        assertEquals(4L, work.key.intentRevision)
        assertFalse(work.requiresNetwork)
        assertNull(work.bindingId)
        assertEquals(OccurrenceSelectionState.PENDING, resumed.occurrence.selectionState)
    }

    @Test fun pendingCommittedCollectionAndUnplayableOnlyRequestLocalAttachment() {
        listOf(OutcomeKind.COLLECTION, OutcomeKind.UNPLAYABLE).forEach { outcome ->
            val fixture = committed(fixture(), outcome = outcome)
            val work = projectPendingDownloadBatchWork(fixture.state, 250).work.single()
            assertEquals(PendingDownloadBatchWorkKind.ATTACH_COMMITTED, work.kind)
            assertFalse(work.requiresNetwork)
            assertNull(work.bindingId)
            assertNull(admissionAfterResolution(fixture.state, fixture.key, accepted(fixture), 250))
        }
    }

    @Test fun boundSingleWithoutClaimIsLocalAdmissionHintNotAnotherSelection() {
        val fixture = committed(fixture(), attached = true)
        val work = projectPendingDownloadBatchWork(fixture.state, 250).work.single()
        assertEquals(PendingDownloadBatchWorkKind.ADMIT_BOUND, work.kind)
        assertEquals(fixture.member.requiredBindingId, work.bindingId)
        assertFalse(work.requiresNetwork)
        assertEquals(work, rejoinPendingDownloadBatchWork(fixture.state, fixture.key, 250))
        assertTrue(fixture.state.physicalDownloads.isEmpty())
        assertTrue(fixture.state.materializations.isEmpty())
        assertTrue(fixture.state.assetUses.isEmpty())
    }

    @Test fun alreadyAttachedClaimBelongsToPhysicalSchedulerEvenWithStalePendingLabel() {
        val bound = committed(fixture(), attached = true)
        val withClaim = attachClaim(bound)
        assertTrue(projectPendingDownloadBatchWork(withClaim.state, 250).work.isEmpty())
        assertNull(rejoinPendingDownloadBatchWork(withClaim.state, bound.key, 250))
        assertNull(admissionAfterResolution(withClaim.state, bound.key, accepted(bound), 250))
    }

    @Test fun liveClaimWaitsAndEarliestLeaseDeadlineIsOneLocalRecheck() {
        val early = claimed(fixture("early"), 400)
        val late = claimed(fixture("late"), 800)
        val projection = projectPendingDownloadBatchWork(merge(late, early), 250)
        assertTrue(projection.work.isEmpty())
        assertEquals(400L, projection.nextLocalWakeEpochMs)
        assertEquals(2, projection.blocked.size)
        assertTrue(projection.blocked.all { it.reason == PendingDownloadBatchBlockedReason.IN_PROGRESS })
    }

    @Test fun exactClaimExpiryBecomesBlockedUnknownAndNeverReadyToQueryAgain() {
        val fixture = claimed(fixture(), 400)
        assertEquals(400L, projectPendingDownloadBatchWork(fixture.state, 399).nextLocalWakeEpochMs)
        listOf(400L, 401L, Long.MAX_VALUE).forEach { now ->
            val projection = projectPendingDownloadBatchWork(fixture.state, now)
            assertTrue(projection.work.isEmpty())
            assertNull(projection.nextLocalWakeEpochMs)
            assertEquals(PendingDownloadBatchBlockedReason.OUTCOME_UNKNOWN, projection.blocked.single().reason)
            assertNull(rejoinPendingDownloadBatchWork(fixture.state, fixture.key, now))
        }
        // The pure projection diagnoses uncertainty; it does not invent a durable state change.
        assertEquals(AcquisitionJobState.CLAIMED, fixture.record.state)
    }

    @Test fun maximumClaimDeadlineDoesNotOverflowOrCreateAnImmediateWake() {
        val fixture = claimed(fixture(), Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, projectPendingDownloadBatchWork(fixture.state, Long.MAX_VALUE - 1).nextLocalWakeEpochMs)
        assertNull(projectPendingDownloadBatchWork(fixture.state, Long.MAX_VALUE).nextLocalWakeEpochMs)
    }

    @Test fun canonicalUnknownOverridesStalePendingLabel() {
        val fixture = changeRequest(fixture()) { it.copy(state = AcquisitionJobState.OUTCOME_UNKNOWN) }
        assertEquals(DownloadBatchMemberState.PENDING_SELECTION, fixture.member.state)
        assertBlocked(fixture, PendingDownloadBatchBlockedReason.OUTCOME_UNKNOWN)
    }

    @Test fun unknownOccurrenceAlsoCannotBeRequeriedFromAReadyRecord() {
        val fixture = changeOccurrence(fixture()) { it.copy(selectionState = OccurrenceSelectionState.OUTCOME_UNKNOWN) }
        assertBlocked(fixture, PendingDownloadBatchBlockedReason.OUTCOME_UNKNOWN)
    }

    @Test fun attachedTerminalOutcomesNeverStartAnotherSelectionOrTransfer() {
        listOf(OutcomeKind.COLLECTION, OutcomeKind.UNPLAYABLE).forEach { outcome ->
            val fixture = committed(fixture(), attached = true, outcome = outcome)
            assertTrue(projectPendingDownloadBatchWork(fixture.state, 250).work.isEmpty())
            assertNull(rejoinPendingDownloadBatchWork(fixture.state, fixture.key, 250))
            assertNull(admissionAfterResolution(fixture.state, fixture.key, accepted(fixture), 250))
        }
    }

    @Test fun readyRecordWithLeaseOrOutcomeIsNotAQueryCandidate() {
        val ready = fixture()
        val lease = AcquisitionLease(ready.key.requestId, AcquisitionAttemptToken("unsent"), "owner", 100, 500)
        assertBlocked(changeRequest(ready) { it.copy(lease = lease) }, PendingDownloadBatchBlockedReason.INVALID_STATE)
        assertBlocked(changeRequest(ready) { it.copy(outcome = AcquisitionOutcome.Unplayable(it.request.id,
            null, AcquisitionFailureStage.SEARCH, AcquisitionProblem(AcquisitionProblemCode.NO_FIRST_RESULT))) },
            PendingDownloadBatchBlockedReason.INVALID_STATE)
    }

    @Test fun malformedClaimDoesNotBecomeSourceWork() {
        val noLease = changeRequest(fixture()) { it.copy(state = AcquisitionJobState.CLAIMED) }
        assertBlocked(noLease, PendingDownloadBatchBlockedReason.INVALID_STATE)
        val wrongRequest = claimed(fixture(), 500).let { source -> changeRequest(source) { record ->
            record.copy(lease = requireNotNull(record.lease).copy(requestId = AcquisitionRequestId("other-request")))
        } }
        assertBlocked(wrongRequest, PendingDownloadBatchBlockedReason.INVALID_STATE)
    }

    @Test fun exactIntentKeyCannotSurvivePauseResumeOrBatchRemoval() {
        val fixture = committed(fixture(), attached = true)
        val resumed = changeBatch(fixture) { it.copy(intentRevision = it.intentRevision + 2) }
        assertNull(rejoinPendingDownloadBatchWork(resumed.state, fixture.key, 250))
        assertNull(admissionAfterResolution(resumed.state, fixture.key, accepted(fixture), 250))
        assertNotNull(rejoinPendingDownloadBatchWork(resumed.state, resumed.key, 250))
        assertNull(rejoinPendingDownloadBatchWork(fixture.state.copy(batches = emptyMap()), fixture.key, 250))
    }

    @Test fun everyIdentityFieldInTheMemberKeyIsRejoined() {
        val fixture = fixture()
        val key = fixture.key
        listOf(key.copy(batchId = DownloadBatchId("other-batch")), key.copy(memberPosition = 9),
            key.copy(intentRevision = 9), key.copy(occurrenceId = ExecutionOccurrenceId("other-occurrence")),
            key.copy(requestId = AcquisitionRequestId("other-request"))).forEach { stale ->
            assertNull(rejoinPendingDownloadBatchWork(fixture.state, stale, 250))
        }
    }

    @Test fun replacedOccurrenceAndMissingOrMiskeyedRequestAreRefused() {
        val fixture = fixture()
        val replaced = changeBatch(fixture) { batch -> batch.copy(members = batch.members.map {
            it.copy(occurrenceId = ExecutionOccurrenceId("replacement"))
        }) }
        assertNull(rejoinPendingDownloadBatchWork(replaced.state, fixture.key, 250))
        assertBlocked(fixture.copy(state = fixture.state.copy(requests = emptyMap())), PendingDownloadBatchBlockedReason.INVALID_STATE)
        val foreign = changeRequest(fixture) { it.copy(request = it.request.copy(id = AcquisitionRequestId("foreign"))) }
        assertBlocked(foreign, PendingDownloadBatchBlockedReason.INVALID_STATE)
        val wrongId = changeBatch(fixture) { it.copy(id = DownloadBatchId("foreign-batch")) }
        assertTrue(projectPendingDownloadBatchWork(wrongId.state, 250).work.isEmpty())
    }

    @Test fun exactScopeRequestPositionModeAndSnapshotMembershipAreRequired() {
        val fixture = fixture()
        val scope = fixture.scope
        val invalid = listOf(
            fixture.copy(state = fixture.state.copy(scopes = emptyMap())),
            changeScope(fixture) { it.copy(controlsMode = PlaybackControlsMode.PODCAST) },
            changeScope(fixture) { it.copy(members = listOf(it.members.single().copy(requestId = AcquisitionRequestId("other")))) },
            changeScope(fixture) { it.copy(members = listOf(it.members.single().copy(disposition = AdmissionDisposition.EXCLUDED,
                exclusion = AdmissionExclusion.UNSELECTED))) },
            changeOccurrence(fixture) { it.copy(scopeMember = AdmissionScopeMemberRef(scope.id, 1)) },
            changeOccurrence(fixture) { it.copy(snapshotMember = CollectionSnapshotMemberRef(CollectionSnapshotId("other-snapshot"), 0)) },
        )
        invalid.forEach { assertBlocked(it, PendingDownloadBatchBlockedReason.INVALID_STATE) }
    }

    @Test fun duplicateMemberPositionsOccurrencesAndScopeRowsAreNotFirstMatchAuthority() {
        val fixture = fixture()
        val repeatedPosition = changeBatch(fixture) { it.copy(members = it.members + it.members.single()) }
        val repeatedOccurrence = changeBatch(fixture) { it.copy(members = it.members + it.members.single().copy(position = 1)) }
        val repeatedScope = changeScope(fixture) { it.copy(members = it.members + it.members.single()) }
        listOf(repeatedPosition, repeatedOccurrence, repeatedScope).forEach { invalid ->
            val projection = projectPendingDownloadBatchWork(invalid.state, 250)
            assertTrue(projection.work.isEmpty())
            assertTrue(projection.blocked.isNotEmpty())
            assertTrue(projection.blocked.all { it.reason == PendingDownloadBatchBlockedReason.INVALID_STATE })
            assertNull(rejoinPendingDownloadBatchWork(invalid.state, fixture.key, 250))
        }
    }

    @Test fun boundAdmissionRequiresExactCanonicalSingleMemberOccurrenceAndBinding() {
        val fixture = committed(fixture(), attached = true)
        val wrong = ExecutionBindingId("other-binding")
        val mismatches = listOf(
            changeBatch(fixture) { it.copy(members = it.members.map { member -> member.copy(requiredBindingId = wrong) }) },
            changeOccurrence(fixture) { it.copy(bindingId = wrong) },
            changeOccurrence(fixture) { it.copy(subjectId = ExecutableSubjectId("other-subject")) },
            fixture.copy(state = fixture.state.copy(bindings = emptyMap())),
            changeRequest(fixture) { it.copy(state = AcquisitionJobState.READY) },
        )
        mismatches.forEach { assertBlocked(it, PendingDownloadBatchBlockedReason.INVALID_STATE) }
        val binding = fixture.state.bindings.values.single()
        val foreign = fixture.copy(state = fixture.state.copy(bindings = mapOf(binding.id to binding.copy(
            firstReceiptId = FirstResultReceiptId("foreign-receipt")))))
        assertBlocked(foreign, PendingDownloadBatchBlockedReason.INVALID_STATE)
    }

    @Test fun onlyAcceptedExactCurrentAttachmentYieldsAdmissionHint() {
        val fixture = committed(fixture(), attached = true)
        val hint = assertNotNull(admissionAfterResolution(fixture.state, fixture.key, accepted(fixture), 250))
        assertEquals(PendingDownloadBatchWorkKind.ADMIT_BOUND, hint.kind)
        assertEquals(fixture.member.requiredBindingId, hint.bindingId)
        assertFalse(hint.requiresNetwork)
        assertTrue(fixture.state.claims.isEmpty())
    }

    @Test fun selectionReturnBeforeAttachmentOrWithRejectedAttachmentCannotAdmit() {
        val pending = committed(fixture())
        assertNull(admissionAfterResolution(pending.state, pending.key, accepted(pending), 250))
        val bound = committed(fixture(), attached = true)
        val result = accepted(bound)
        assertNull(admissionAfterResolution(bound.state, bound.key, result.copy(attachedOccurrences = emptySet()), 250))
        assertNull(admissionAfterResolution(bound.state, bound.key, result.copy(rejectedAttachments = setOf(bound.key.occurrenceId)), 250))
        assertNull(admissionAfterResolution(bound.state, bound.key,
            result.copy(record = result.record.copy(request = result.record.request.copy(id = AcquisitionRequestId("other")))), 250))
    }

    @Test fun inProgressOutcomeUnknownAndStaleAreNeverTransferPermissionEvenWithBoundState() {
        val fixture = committed(fixture(), attached = true)
        listOf(AcquisitionResolution.InProgress(fixture.record), AcquisitionResolution.OutcomeUnknown(fixture.record),
            AcquisitionResolution.Stale(fixture.record)).forEach { result ->
            assertNull(admissionAfterResolution(fixture.state, fixture.key, result, 250))
        }
    }

    @Test fun acceptedOldRecordCannotAuthorizeDifferentCurrentBinding() {
        val original = committed(fixture(), attached = true)
        val result = accepted(original)
        val replacementId = ExecutionBindingId("replacement-binding")
        val oldBinding = original.state.bindings.values.single()
        val replacedRecord = changeRequest(original) { it.copy(outcome = (it.outcome as AcquisitionOutcome.Single).copy(bindingId = replacementId)) }
        val replacedOccurrence = changeOccurrence(replacedRecord) { it.copy(bindingId = replacementId) }
        val replacedMember = changeBatch(replacedOccurrence) { batch -> batch.copy(members = batch.members.map {
            it.copy(requiredBindingId = replacementId)
        }) }
        val changed = replacedMember.copy(state = replacedMember.state.copy(bindings = mapOf(
            replacementId to oldBinding.copy(id = replacementId))))
        assertEquals(replacementId, rejoinPendingDownloadBatchWork(changed.state, original.key, 250)?.bindingId)
        assertNull(admissionAfterResolution(changed.state, original.key, result, 250))
        val different = committed(fixture("different"), attached = true)
        assertNull(admissionAfterResolution(different.state, original.key, result, 250))
    }

    @Test fun unrelatedLibraryRevisionDoesNotInvalidateTheExactMemberKey() {
        val fixture = committed(fixture(), attached = true)
        val changed = fixture.state.copy(libraryRevision = fixture.state.libraryRevision + 10)
        assertEquals(rejoinPendingDownloadBatchWork(fixture.state, fixture.key, 250),
            admissionAfterResolution(changed, fixture.key, accepted(fixture), 250))
    }

    @Test fun repeatedRecordingStillProducesDistinctOccurrenceAdmissionHints() {
        val fixture = committed(fixture(count = 3), attached = true)
        assertEquals(1, fixture.state.bindings.values.map { it.recordingId }.toSet().size)
        val work = projectPendingDownloadBatchWork(fixture.state, 250).work
        assertEquals(3, work.size)
        assertEquals(3, work.map { it.key.occurrenceId }.toSet().size)
        assertEquals(listOf(0, 1, 2), work.map { it.key.memberPosition })
        assertTrue(work.all { it.kind == PendingDownloadBatchWorkKind.ADMIT_BOUND })
    }

    @Test fun earlierBlockedMemberDoesNotHideLaterEligibleWork() {
        val first = claimed(fixture("a", created = 10), 200)
        val later = fixture("b", created = 20)
        val projection = projectPendingDownloadBatchWork(merge(first, later), 250)
        assertEquals(listOf(later.key), projection.work.map { it.key })
        assertEquals(PendingDownloadBatchBlockedReason.OUTCOME_UNKNOWN, projection.blocked.single().reason)
        assertNull(projection.nextLocalWakeEpochMs)
    }

    @Test fun returnedHintsDoNotRetainMutableInputMemberLists() {
        val original = fixture(count = 2)
        val members = original.batch.members.toMutableList()
        val fixture = changeBatch(original) { it.copy(members = members) }
        val projection = projectPendingDownloadBatchWork(fixture.state, 250)
        members.clear()
        assertEquals(2, projection.work.size)
        assertEquals(listOf(0, 1), projection.work.map { it.key.memberPosition })
    }

    @Test fun hintAndKeyConstructionRejectsInconsistentNumericOrBindingFields() {
        val key = fixture().key
        assertFailsWith<IllegalArgumentException> { key.copy(memberPosition = -1) }
        assertFailsWith<IllegalArgumentException> { key.copy(intentRevision = -1) }
        assertFailsWith<IllegalArgumentException> { PendingDownloadBatchWork(key, PendingDownloadBatchWorkKind.ADMIT_BOUND) }
        assertFailsWith<IllegalArgumentException> { PendingDownloadBatchWork(key, PendingDownloadBatchWorkKind.SELECT_READY,
            ExecutionBindingId("not-a-selection-permission")) }
        assertFailsWith<IllegalArgumentException> { projectPendingDownloadBatchWork(fixture().state, -1) }
    }

    private enum class OutcomeKind { SINGLE, COLLECTION, UNPLAYABLE }

    private data class Fixture(val state: AcquisitionRepositoryState, val batchId: DownloadBatchId) {
        val batch get() = state.batches.getValue(batchId)
        val member get() = batch.members.first()
        val record get() = state.requests.getValue(member.requestId)
        val occurrence get() = state.occurrences.getValue(member.occurrenceId)
        val scope get() = state.scopes.getValue(batch.scopeId)
        val key get() = key(member)
        fun key(member: DownloadBatchMember) = PendingDownloadBatchKey(batchId, member.position,
            batch.intentRevision, member.occurrenceId, member.requestId)
    }

    private fun fixture(name: String = "batch", count: Int = 1, created: Long = 0): Fixture {
        val batchId = DownloadBatchId(name)
        val scopeId = AdmissionScopeId("scope-$name")
        val requests = (0 until count).associate { position ->
            val media = MediaItem("media-$name-$position", "  Complete requested title — العنوان الكامل  ",
                MediaKind.MUSIC, SourceRef(SourceProvider.COMMONS, "authored-$name-$position"))
            val request = AcquisitionRequest(AcquisitionRequestId("request-$name-$position"), media.title, MediaCapture(media), created)
            request.id to AcquisitionRecord(request, AcquisitionJobState.READY, updatedEpochMs = created)
        }
        val occurrences = requests.values.mapIndexed { position, record ->
            ExecutionOccurrence(ExecutionOccurrenceId("occurrence-$name-$position"), AcquisitionOrigin.TYPED,
                record.request.id, null, AdmissionScopeMemberRef(scopeId, position), null, null,
                PlaybackControlsMode.MUSIC, OccurrenceSelectionState.PENDING, created)
        }
        val members = occurrences.mapIndexed { position, occurrence -> DownloadBatchMember(position, position,
            occurrence.id, requireNotNull(occurrence.requestId), null, null, DownloadBatchMemberState.PENDING_SELECTION) }
        val scope = AdmissionScope(scopeId, AdmissionScopeKind.CATALOG_LEAVES, null, null, PlaybackControlsMode.MUSIC,
            members.map { AdmissionScopeMember(it.position, null, it.requestId, AdmissionDisposition.INCLUDED, null) }, false, created)
        val batch = DownloadBatch(batchId, scopeId, DownloadBatchIntent.ACTIVE, 0, members, created, created)
        return Fixture(AcquisitionRepositoryState(7, requests = requests, scopes = mapOf(scope.id to scope),
            occurrences = occurrences.associateBy { it.id }, batches = mapOf(batch.id to batch)), batchId)
    }

    private fun committed(fixture: Fixture, attached: Boolean = false, outcome: OutcomeKind = OutcomeKind.SINGLE): Fixture {
        var state = fixture.state
        val members = fixture.batch.members.map { member ->
            val record = state.requests.getValue(member.requestId)
            val receiptId = FirstResultReceiptId("receipt-${member.requestId.value}")
            val bindingId = ExecutionBindingId("binding-${member.requestId.value}")
            val media = (record.request.metadata as MediaCapture).media
            val subject = ExecutableSubject.CatalogMedia(ExecutableSubjectId(media.id), media.id)
            val recording = SourceRecording(SourceRecordingId("shared-recording-C"), "LMNOPQRSTUV", "https://www.youtube.com/watch?v=LMNOPQRSTUV")
            val accepted = when (outcome) {
                OutcomeKind.SINGLE -> AcquisitionOutcome.Single(member.requestId, receiptId, bindingId)
                OutcomeKind.COLLECTION -> AcquisitionOutcome.Collection(member.requestId, receiptId,
                    CollectionIdentity(AcquisitionEntryKind.PLAYLIST, "PLauthored", "https://www.youtube.com/playlist?list=PLauthored"),
                    CollectionScope.PLAYLIST_MEMBERS)
                OutcomeKind.UNPLAYABLE -> AcquisitionOutcome.Unplayable(member.requestId, null,
                    AcquisitionFailureStage.SEARCH, AcquisitionProblem(AcquisitionProblemCode.NO_FIRST_RESULT))
            }
            val target = when (outcome) {
                OutcomeKind.SINGLE -> OccurrenceSelectionState.BOUND
                OutcomeKind.COLLECTION -> OccurrenceSelectionState.NEEDS_COLLECTION_ACTION
                OutcomeKind.UNPLAYABLE -> OccurrenceSelectionState.UNPLAYABLE
            }
            val occurrence = state.occurrences.getValue(member.occurrenceId)
            state = state.copy(requests = state.requests + (member.requestId to record.copy(state = AcquisitionJobState.COMMITTED,
                lease = null, outcome = accepted, updatedEpochMs = 100)), occurrences = state.occurrences + (occurrence.id to
                if (!attached) occurrence else occurrence.copy(selectionState = target,
                    bindingId = bindingId.takeIf { outcome == OutcomeKind.SINGLE }, subjectId = subject.id.takeIf { outcome == OutcomeKind.SINGLE })))
            if (outcome != OutcomeKind.UNPLAYABLE) {
                val entry = if (outcome == OutcomeKind.SINGLE) SourceEntry(AcquisitionEntryKind.VIDEO, recording.canonicalId,
                    "Different recording C", recording.canonicalUrl) else SourceEntry(AcquisitionEntryKind.PLAYLIST, "PLauthored",
                    "Authored listing", "https://www.youtube.com/playlist?list=PLauthored")
                val receipt = FirstResultReceipt.Observed(receiptId, FirstResultObservation(member.requestId,
                    record.request.completeTitle, entry, entry.publicUrl), 100)
                state = state.copy(receipts = state.receipts + (receiptId to receipt))
            }
            if (outcome == OutcomeKind.SINGLE) {
                val binding = ExecutionBinding(bindingId, subject.id, receiptId, recording.id, ExecutionBindingOrigin.TYPED)
                state = state.copy(bindings = state.bindings + (bindingId to binding), subjects = state.subjects + (subject.id to subject),
                    recordings = state.recordings + (recording.id to recording))
            }
            if (!attached) member else member.copy(requiredBindingId = bindingId.takeIf { outcome == OutcomeKind.SINGLE }, state = when (outcome) {
                OutcomeKind.SINGLE -> DownloadBatchMemberState.WAITING_FOR_JOB
                OutcomeKind.COLLECTION -> DownloadBatchMemberState.NEEDS_COLLECTION_ACTION
                OutcomeKind.UNPLAYABLE -> DownloadBatchMemberState.UNPLAYABLE
            })
        }
        return fixture.copy(state = state.copy(batches = state.batches + (fixture.batchId to fixture.batch.copy(members = members))))
    }

    private fun claimed(fixture: Fixture, expires: Long): Fixture = changeRequest(fixture) { record ->
        record.copy(state = AcquisitionJobState.CLAIMED, lease = AcquisitionLease(record.request.id,
            AcquisitionAttemptToken("authored-token"), "authored-owner", 100, expires), updatedEpochMs = 100)
    }

    private fun accepted(fixture: Fixture) = AcquisitionResolution.Accepted(fixture.record, setOf(fixture.key.occurrenceId), emptySet())

    private fun attachClaim(fixture: Fixture): Fixture {
        val claim = DownloadClaim(DownloadClaimId("claim-${fixture.batchId.value}"),
            DownloadClaimOwner.BatchMember(fixture.batchId, fixture.member.position), "existing-physical-job",
            fixture.member.requiredBindingId, DownloadBatchIntent.ACTIVE, true, 100, ownerIntentRevision = fixture.batch.intentRevision)
        val changed = changeBatch(fixture) { it.copy(members = it.members.map { member ->
            member.copy(claimId = claim.id, state = DownloadBatchMemberState.PENDING_SELECTION)
        }) }
        return changed.copy(state = changed.state.copy(claims = changed.state.claims + (claim.id to claim)))
    }

    private fun changeBatch(fixture: Fixture, change: (DownloadBatch) -> DownloadBatch): Fixture =
        fixture.copy(state = fixture.state.copy(batches = fixture.state.batches + (fixture.batchId to change(fixture.batch))))

    private fun changeRequest(fixture: Fixture, change: (AcquisitionRecord) -> AcquisitionRecord): Fixture =
        fixture.copy(state = fixture.state.copy(requests = fixture.state.requests + (fixture.member.requestId to change(fixture.record))))

    private fun changeOccurrence(fixture: Fixture, change: (ExecutionOccurrence) -> ExecutionOccurrence): Fixture =
        fixture.copy(state = fixture.state.copy(occurrences = fixture.state.occurrences + (fixture.member.occurrenceId to change(fixture.occurrence))))

    private fun changeScope(fixture: Fixture, change: (AdmissionScope) -> AdmissionScope): Fixture =
        fixture.copy(state = fixture.state.copy(scopes = fixture.state.scopes + (fixture.scope.id to change(fixture.scope))))

    private fun merge(vararg fixtures: Fixture): AcquisitionRepositoryState = fixtures.fold(AcquisitionRepositoryState(7)) { state, fixture ->
        state.copy(requests = state.requests + fixture.state.requests, receipts = state.receipts + fixture.state.receipts,
            bindings = state.bindings + fixture.state.bindings, subjects = state.subjects + fixture.state.subjects,
            recordings = state.recordings + fixture.state.recordings, occurrences = state.occurrences + fixture.state.occurrences,
            scopes = state.scopes + fixture.state.scopes, batches = state.batches + fixture.state.batches, claims = state.claims + fixture.state.claims)
    }

    private fun assertBlocked(fixture: Fixture, reason: PendingDownloadBatchBlockedReason) {
        val projection = projectPendingDownloadBatchWork(fixture.state, 250)
        assertTrue(projection.work.isEmpty())
        assertEquals(reason, projection.blocked.single().reason)
        assertNull(rejoinPendingDownloadBatchWork(fixture.state, fixture.key, 250))
    }
}
