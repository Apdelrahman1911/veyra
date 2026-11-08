package dev.veyra.acquisition

import dev.veyra.data.*
import dev.veyra.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Coordinator/fake-port tests, not durable Room or successful native/source execution proof. */
class AcquisitionCoordinatorTest {
    private class Rig {
        val repository = AcquisitionTestRepository()
        val gateway = AcquisitionTestGateway()
        val clock = AcquisitionTestClock()
        var offline = false
        val captures = AcquisitionCaptureFactory(clock)
        val coordinator = AcquisitionCoordinator(repository, gateway, captures, clock, { offline }, owner = "authored-owner")
        val admissions = AcquisitionAdmissionFactory(captures, clock)
        val queues = QueueAcquisitionCoordinator(repository, coordinator)

        suspend fun admit(request: AcquisitionRequest = authoredRequest(), mode: PlaybackControlsMode = PlaybackControlsMode.MUSIC): ExecutionOccurrence {
            val revision = repository.state.value.queues[QueueContext.MUSIC]?.editRevision ?: 0
            val prepared = admissions.single(request, mode)
            queues.admit(prepared, QueueContext.MUSIC, revision, QueueAdmissionOperation.REPLACE)
            return prepared.occurrences.single()
        }

        suspend fun resolveCurrent(): AcquisitionResolution {
            val queue = repository.state.value.queues.getValue(QueueContext.MUSIC)
            return queues.resolveCurrent(queue.context, queue.editRevision, queue.currentOccurrenceId!!)
        }
    }

    @Test fun captureClaimAndAcceptedBindingPrecedeDirectInspectionOfTheSameFullTitleReceipt() = runTest {
        val rig = Rig()
        val occurrence = rig.admit()
        rig.gateway.acquireResult = { request ->
            val durable = rig.repository.state.value.requests.getValue(request.id)
            assertEquals(AcquisitionJobState.CLAIMED, durable.state)
            assertEquals(request, durable.request)
            assertEquals("authored-owner", durable.lease?.owner)
            authoredSingle(request)
        }
        val accepted = assertIs<AcquisitionResolution.Accepted>(rig.resolveCurrent())
        assertEquals(setOf(occurrence.id), accepted.attachedOccurrences)
        val outcome = assertIs<AcquisitionOutcome.Single>(accepted.record.outcome)
        val bound = rig.coordinator.boundOccurrence(occurrence.id, outcome.bindingId)
        assertEquals(accepted.record.request, bound.request)
        assertEquals(bound.receipt.id, bound.binding.firstReceiptId)
        assertEquals(bound.binding.id, rig.repository.state.value.occurrences.getValue(occurrence.id).bindingId)
        rig.coordinator.inspect(occurrence.id, outcome.bindingId)
        assertEquals(listOf(accepted.record.request.completeTitle), rig.gateway.acquisitions.map { it.completeTitle })
        assertEquals(bound.selection, rig.gateway.inspections.single().second)
        assertEquals(1, rig.repository.committed.size)
    }

    @Test fun repeatedAcceptedRequestReattachesWithoutAnotherClaimOrQueryEvenOffline() = runTest {
        val rig = Rig()
        val occurrence = rig.admit()
        val first = assertIs<AcquisitionResolution.Accepted>(rig.resolveCurrent())
        rig.offline = true
        val again = assertIs<AcquisitionResolution.Accepted>(rig.resolveCurrent())
        assertEquals(first.record, again.record)
        assertEquals(setOf(occurrence.id), again.attachedOccurrences)
        assertEquals(1, rig.gateway.acquisitions.size)
        assertEquals(1, rig.repository.claimed.size)
    }

    @Test fun coldOfflineRestoreIsRoomOnlyAndDoesNotEvenAskTheGatewayToRestoreOrInspect() = runTest {
        val rig = Rig()
        rig.admit()
        rig.offline = true
        assertEquals(rig.repository.state.value, rig.coordinator.restore())
        assertEquals(1, rig.repository.initializeCalls)
        assertEquals(0, rig.gateway.restores)
        assertTrue(rig.gateway.acquisitions.isEmpty())
        assertTrue(rig.gateway.collections.isEmpty())
        assertTrue(rig.gateway.inspections.isEmpty())
    }

    @Test fun offlinePendingActionCannotClaimOrSearch() = runTest {
        val rig = Rig()
        rig.admit()
        rig.offline = true
        assertEquals(FailureCode.OFFLINE_POLICY, assertFailsWith<VeyraException> { rig.resolveCurrent() }.failure.code)
        assertTrue(rig.repository.claimed.isEmpty())
        assertTrue(rig.gateway.acquisitions.isEmpty())
    }

    @Test fun offlinePolicyChangingAfterClaimReleasesOnlyTheProvablyUnsentClaim() = runTest {
        val rig = Rig()
        val occurrence = rig.admit()
        rig.repository.afterClaim = { rig.offline = true }
        assertEquals(FailureCode.OFFLINE_POLICY, assertFailsWith<VeyraException> { rig.resolveCurrent() }.failure.code)
        assertEquals(AcquisitionJobState.READY, rig.repository.state.value.requests.getValue(occurrence.requestId!!).state)
        assertEquals(1, rig.repository.unsent.size)
        assertTrue(rig.repository.unknown.isEmpty())
        assertTrue(rig.gateway.acquisitions.isEmpty())
    }

    @Test fun anExpiredUnsentClaimCannotBeReportedReleasedOrResearched() = runTest {
        val rig = Rig()
        val occurrence = rig.admit()
        rig.repository.afterClaim = { rig.clock.now += 60_001; rig.offline = true }
        val failure = assertFailsWith<VeyraException> { rig.resolveCurrent() }
        assertEquals(AppFailure(FailureCode.RECOVERY_REQUIRED, "acquisition_unsent_claim_not_released"), failure.failure)
        val requestId = assertNotNull(occurrence.requestId)
        assertEquals(AcquisitionJobState.CLAIMED, rig.repository.state.value.requests.getValue(requestId).state)
        assertTrue(rig.repository.unknown.isEmpty())
        rig.offline = false
        assertIs<AcquisitionResolution.OutcomeUnknown>(rig.coordinator.resolve(requestId))
        assertTrue(rig.gateway.acquisitions.isEmpty())
    }

    @Test fun rejectedUnsentReleaseNeverChangesAForeignLiveLease() = runTest {
        val rig = Rig()
        val occurrence = rig.admit()
        val requestId = assertNotNull(occurrence.requestId)
        rig.repository.afterClaim = {
            val state = rig.repository.state.value
            val record = state.requests.getValue(requestId)
            val foreign = assertNotNull(record.lease).copy(token = AcquisitionAttemptToken("foreign-token"), owner = "foreign-owner")
            rig.repository.state.value = state.copy(requests = state.requests + (requestId to record.copy(lease = foreign)))
            rig.offline = true
        }
        assertEquals("acquisition_unsent_claim_not_released", assertFailsWith<VeyraException> { rig.resolveCurrent() }.failure.detail)
        assertEquals("foreign-owner", rig.repository.state.value.requests.getValue(requestId).lease?.owner)
        rig.offline = false
        assertIs<AcquisitionResolution.InProgress>(rig.coordinator.resolve(requestId))
        assertTrue(rig.repository.unknown.isEmpty())
        assertTrue(rig.gateway.acquisitions.isEmpty())
    }

    @Test fun aThrownUnsentReleaseIsUnconfirmedStorageNotAnOfflineOrReadyReceipt() = runTest {
        val rig = Rig()
        val occurrence = rig.admit()
        rig.repository.afterClaim = { rig.offline = true }
        rig.repository.beforeUnsentRelease = { error("authored-private-storage-transcript") }
        val failure = assertFailsWith<VeyraException> { rig.resolveCurrent() }
        assertEquals(AppFailure(FailureCode.STORAGE_IO, "acquisition_settlement_unconfirmed"), failure.failure)
        assertNull(failure.cause)
        assertTrue(failure.suppressedExceptions.isEmpty())
        assertFalse(failure.toString().contains("private-storage-transcript"))
        assertEquals(AcquisitionJobState.CLAIMED, rig.repository.state.value.requests.getValue(assertNotNull(occurrence.requestId)).state)
        assertTrue(rig.gateway.acquisitions.isEmpty())
    }

    @Test fun cancellationAfterDurableClaimButBeforeGatewayCanReturnTheUnsentClaim() = runTest {
        val rig = Rig()
        val occurrence = rig.admit()
        val entered = CompletableDeferred<Unit>()
        val leave = CompletableDeferred<Unit>()
        rig.repository.afterClaim = { entered.complete(Unit); leave.await() }
        val action = launch { rig.resolveCurrent() }
        entered.await()
        action.cancel()
        leave.complete(Unit)
        action.join()
        assertTrue(action.isCancelled)
        assertEquals(AcquisitionJobState.READY, rig.repository.state.value.requests.getValue(occurrence.requestId!!).state)
        assertEquals(1, rig.repository.unsent.size)
        assertTrue(rig.gateway.acquisitions.isEmpty())
        assertTrue(rig.repository.unknown.isEmpty())
    }

    @Test fun rejectedUnsentReleaseDoesNotReplaceCallerCancellationWithAStorageFailure() = runTest {
        val rig = Rig()
        val occurrence = rig.admit()
        val entered = CompletableDeferred<Unit>()
        val leave = CompletableDeferred<Unit>()
        rig.repository.afterClaim = { entered.complete(Unit); leave.await(); rig.clock.now += 60_001 }
        val action = launch { rig.resolveCurrent() }
        entered.await(); action.cancel(); leave.complete(Unit); action.join()
        assertTrue(action.isCancelled)
        assertEquals(AcquisitionJobState.CLAIMED, rig.repository.state.value.requests.getValue(assertNotNull(occurrence.requestId)).state)
        assertEquals(1, rig.repository.unsent.size)
        assertTrue(rig.gateway.acquisitions.isEmpty())
    }

    @Test fun concurrentDuplicateResolutionSeesTheLiveClaimRatherThanStartingAnotherSearch() = runTest {
        val rig = Rig()
        val occurrence = rig.admit()
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        rig.gateway.acquireResult = { entered.complete(Unit); response.await(); authoredSingle(it) }
        val first = async { rig.resolveCurrent() }
        entered.await()
        assertIs<AcquisitionResolution.InProgress>(rig.coordinator.resolve(occurrence.requestId!!))
        assertEquals(1, rig.gateway.acquisitions.size)
        response.complete(Unit)
        assertIs<AcquisitionResolution.Accepted>(first.await())
    }

    @Test fun anUnconfirmedDurableClaimErrorIsSanitizedWithoutAssumingThatTheClaimWasNotWritten() = runTest {
        val rig = Rig()
        val occurrence = rig.admit()
        rig.repository.afterClaim = { error("authored-private-storage-transcript") }
        val failure = assertFailsWith<VeyraException> { rig.resolveCurrent() }
        assertEquals(AppFailure(FailureCode.STORAGE_IO, "acquisition_claim_unconfirmed"), failure.failure)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains("private-storage-transcript"))
        assertTrue(rig.gateway.acquisitions.isEmpty())
        assertTrue(rig.repository.unsent.isEmpty())
        rig.clock.now += 60_001
        assertIs<AcquisitionResolution.OutcomeUnknown>(rig.coordinator.resolve(assertNotNull(occurrence.requestId)))
        assertTrue(rig.gateway.acquisitions.isEmpty())
    }

    @Test fun cancellationWhileTheSourceIsInFlightPersistsUnknownAndNeverRetriesThatRequest() = runTest {
        val rig = Rig()
        val occurrence = rig.admit()
        val entered = CompletableDeferred<Unit>()
        rig.gateway.acquireResult = { entered.complete(Unit); awaitCancellation() }
        val action = launch { rig.resolveCurrent() }
        entered.await()
        action.cancelAndJoin()
        assertEquals(AcquisitionJobState.OUTCOME_UNKNOWN, rig.repository.state.value.requests.getValue(occurrence.requestId!!).state)
        assertIs<AcquisitionResolution.OutcomeUnknown>(rig.coordinator.resolve(assertNotNull(occurrence.requestId)))
        assertEquals(1, rig.gateway.acquisitions.size)
        assertTrue(rig.repository.unsent.isEmpty())
        assertTrue(rig.repository.committed.isEmpty())
    }

    @Test fun aKnownResponseIsCommittedNoncancellablyBeforeCallerCancellationEscapes() = runTest {
        val rig = Rig()
        val occurrence = rig.admit()
        val commitEntered = CompletableDeferred<Unit>()
        val permitCommit = CompletableDeferred<Unit>()
        rig.repository.beforeCommit = {
            assertTrue(currentCoroutineContext().isActive)
            commitEntered.complete(Unit)
            permitCommit.await()
            assertTrue(currentCoroutineContext().isActive)
        }
        val action = launch { rig.resolveCurrent() }
        commitEntered.await()
        action.cancel()
        permitCommit.complete(Unit)
        action.join()
        val record = rig.repository.state.value.requests.getValue(occurrence.requestId!!)
        assertEquals(AcquisitionJobState.COMMITTED, record.state)
        assertNotNull(record.outcome)
        assertTrue(rig.repository.unknown.isEmpty())
        assertEquals(OccurrenceSelectionState.BOUND, rig.repository.state.value.occurrences.getValue(occurrence.id).selectionState)
        assertTrue(rig.gateway.inspections.isEmpty())
    }

    @Test fun aGatewayThatCancelsThenActuallyReturnsAValueStillHasThatKnownResultCommitted() = runTest {
        val rig = Rig()
        val occurrence = rig.admit()
        rig.gateway.acquireResult = { request ->
            currentCoroutineContext().job.cancel(CancellationException("authored return cancellation"))
            authoredSingle(request)
        }
        val action = launch { rig.resolveCurrent() }
        action.join()
        assertTrue(action.isCancelled)
        assertEquals(AcquisitionJobState.COMMITTED, rig.repository.state.value.requests.getValue(assertNotNull(occurrence.requestId)).state)
        assertEquals(1, rig.repository.state.value.receipts.size)
        assertEquals(OccurrenceSelectionState.BOUND, rig.repository.state.value.occurrences.getValue(occurrence.id).selectionState)
        assertTrue(rig.repository.unknown.isEmpty())
        assertTrue(rig.gateway.inspections.isEmpty())
    }

    @Test fun thrownSourceErrorIsSanitizedAndItsUnknownRequestCannotBeImplicitlyRetried() = runTest {
        val rig = Rig()
        val occurrence = rig.admit()
        rig.gateway.acquireResult = { error("authored-private-response https://media.example/?sig=not-for-errors") }
        val failure = assertFailsWith<VeyraException> { rig.resolveCurrent() }
        assertEquals(AppFailure(FailureCode.RECOVERY_REQUIRED, "acquisition_outcome_unknown"), failure.failure)
        assertNull(failure.cause)
        assertTrue(failure.suppressedExceptions.isEmpty())
        assertFalse(failure.toString().contains("not-for-errors"))
        assertIs<AcquisitionResolution.OutcomeUnknown>(rig.coordinator.resolve(occurrence.requestId!!))
        assertEquals(1, rig.gateway.acquisitions.size)
    }

    @Test fun failedReceiptCommitNeverExposesTheUncommittedSingleForInspection() = runTest {
        val rig = Rig()
        val occurrence = rig.admit()
        rig.repository.beforeCommit = { throw VeyraException(AppFailure(FailureCode.STORAGE_IO)) }
        assertEquals("acquisition_outcome_unknown", assertFailsWith<VeyraException> { rig.resolveCurrent() }.failure.detail)
        assertTrue(rig.repository.state.value.receipts.isEmpty())
        assertTrue(rig.repository.state.value.bindings.isEmpty())
        assertIs<AcquisitionResolution.OutcomeUnknown>(rig.coordinator.resolve(occurrence.requestId!!))
        assertEquals(1, rig.gateway.acquisitions.size)
        assertTrue(rig.gateway.inspections.isEmpty())
    }

    @Test fun staleCommitReturnsAuthoritativeStateWithoutTreatingTheSourceSingleAsAccepted() = runTest {
        val rig = Rig()
        val occurrence = rig.admit()
        rig.repository.commitOverride = { command ->
            val unknown = rig.repository.markOutcomeUnknown(MarkAcquisitionOutcomeUnknown(command.lease, rig.clock.now))
            AcquisitionCommitResult.Stale(unknown, historicalReceiptRetained = false)
        }
        assertIs<AcquisitionResolution.Stale>(rig.resolveCurrent())
        assertEquals(OccurrenceSelectionState.PENDING, rig.repository.state.value.occurrences.getValue(occurrence.id).selectionState)
        assertTrue(rig.repository.state.value.bindings.isEmpty())
        assertTrue(rig.gateway.inspections.isEmpty())
    }

    @Test fun queueEditDuringSearchKeepsHistoricalReceiptButRejectsTheOldOccurrenceAttachment() = runTest {
        val rig = Rig()
        val old = rig.admit()
        rig.gateway.acquireResult = { request ->
            val queue = rig.repository.state.value.queues.getValue(QueueContext.MUSIC)
            rig.repository.state.value = rig.repository.state.value.copy(queues = mapOf(QueueContext.MUSIC to
                queue.copy(editRevision = queue.editRevision + 1, displayedOrder = emptyList(), originalOrder = emptyList(), currentOccurrenceId = null)))
            authoredSingle(request)
        }
        val result = assertIs<AcquisitionResolution.Accepted>(rig.resolveCurrent())
        assertTrue(result.attachedOccurrences.isEmpty())
        assertEquals(setOf(old.id), result.rejectedAttachments)
        assertEquals(1, rig.repository.state.value.receipts.size)
        assertEquals(OccurrenceSelectionState.PENDING, rig.repository.state.value.occurrences.getValue(old.id).selectionState)
        assertTrue(rig.gateway.inspections.isEmpty())
    }

    @Test fun parentSingleUsesItsParentReceiptAndANeutralSubjectWithSeparatePodcastControls() = runTest {
        val rig = Rig()
        val request = authoredRequest("album-request", AlbumCapture(Album("album", "Unchanged edition title")))
        val occurrence = rig.admit(request, PlaybackControlsMode.PODCAST)
        val outcome = assertIs<AcquisitionOutcome.Single>(assertIs<AcquisitionResolution.Accepted>(rig.resolveCurrent()).record.outcome)
        val bound = rig.coordinator.boundOccurrence(occurrence.id, outcome.bindingId)
        assertIs<ExecutableSubject.SourceRecordingSubject>(bound.subject)
        assertIs<AlbumCapture>(bound.request.metadata)
        assertEquals(PlaybackControlsMode.PODCAST, bound.occurrence.controlsMode)
        rig.coordinator.inspect(occurrence.id, outcome.bindingId)
        assertEquals(listOf("Unchanged edition title"), rig.gateway.acquisitions.map { it.completeTitle })
        assertEquals("LMNOPQRSTUV", rig.gateway.inspections.single().second.recording.id)
        assertTrue(rig.gateway.collections.isEmpty())
    }

    @Test fun observedMemberBRemainsPinnedWhenItsCompleteTitleSelectsRecordingC() = runTest {
        val rig = Rig()
        val snapshot = authoredSnapshot(holes = setOf(1))
        val request = rig.captures.member(snapshot, 2)
        val occurrence = rig.admit(request)
        val outcome = assertIs<AcquisitionOutcome.Single>(assertIs<AcquisitionResolution.Accepted>(rig.resolveCurrent()).record.outcome)
        val bound = rig.coordinator.boundOccurrence(occurrence.id, outcome.bindingId)
        val b = assertIs<CollectionMemberCapture>(bound.request.metadata)
        assertEquals("ABCDEFGHIJK", b.member.entry.id)
        assertEquals(2, b.member.reference.ordinal)
        assertEquals(snapshot.id, b.snapshotId)
        assertEquals("LMNOPQRSTUV", bound.recording.canonicalId)
        assertIs<ExecutableSubject.SourceRecordingSubject>(bound.subject)
        assertEquals(listOf(b.member.entry.title), rig.gateway.acquisitions.map { it.completeTitle })
        assertTrue(rig.gateway.collections.isEmpty())
    }

    @Test fun pureExecutionContextRetainsTheExactRequestReceiptSubjectAndRecordingWithoutSourceWork() = runTest {
        for (metadata in listOf<RequestedMetadata>(
            authoredRequest().metadata,
            AlbumCapture(Album("edition", "Complete edition title")),
            AcquisitionCaptureFactory().member(authoredSnapshot(), 0).metadata,
        )) {
            val rig = Rig()
            val occurrence = rig.admit(authoredRequest(metadata = metadata))
            val outcome = assertIs<AcquisitionOutcome.Single>(assertIs<AcquisitionResolution.Accepted>(rig.resolveCurrent()).record.outcome)
            val bound = rig.coordinator.boundOccurrence(occurrence.id, outcome.bindingId)
            rig.offline = true
            val restores = rig.gateway.restores
            val context = bound.executionContext()
            assertSame(bound.request, context.request)
            assertSame(bound.receipt, context.receipt)
            assertSame(bound.binding, context.binding)
            assertSame(bound.subject, context.subject)
            assertSame(bound.recording, context.recording)
            assertEquals(restores, rig.gateway.restores)
            assertEquals(1, rig.gateway.acquisitions.size)
            assertTrue(rig.gateway.inspections.isEmpty())
            assertTrue(rig.gateway.collections.isEmpty())
        }
    }

    @Test fun twoParentRequestsSelectingTheSameRecordingKeepSeparateReceiptsWithoutInventedAssetSharing() = runTest {
        val rig = Rig()
        for (id in listOf("R1", "R2")) {
            rig.admit(authoredRequest(id, AlbumCapture(Album("album-$id", "Complete edition $id"))))
            rig.resolveCurrent()
        }
        assertEquals(2, rig.repository.state.value.receipts.size)
        assertEquals(2, rig.repository.state.value.bindings.size)
        assertEquals(1, rig.repository.state.value.recordings.size)
        assertEquals(1, rig.repository.state.value.subjects.size)
        assertTrue(rig.repository.state.value.assetUses.isEmpty())
    }

    @Test fun resolveAgainCreatesANewCapturedRequestRatherThanReopeningTheOldUnknownJob() = runTest {
        val rig = Rig()
        val occurrence = rig.admit()
        rig.gateway.acquireResult = { throw CancellationException("authored uncertain result") }
        assertFailsWith<CancellationException> { rig.resolveCurrent() }
        val old = rig.repository.state.value.requests.getValue(occurrence.requestId!!)
        val again = rig.coordinator.resolveAgain(old.request.id)
        assertNotEquals(old.request.id, again.request.id)
        assertEquals(old.request.metadata, again.request.metadata)
        assertEquals(AcquisitionJobState.READY, again.state)
        assertEquals(AcquisitionJobState.OUTCOME_UNKNOWN, rig.repository.state.value.requests.getValue(old.request.id).state)
        rig.gateway.acquireResult = { authoredSingle(it) }
        assertIs<AcquisitionResolution.Accepted>(rig.coordinator.resolve(again.request.id))
        assertEquals(listOf(old.request.completeTitle, old.request.completeTitle), rig.gateway.acquisitions.map { it.completeTitle })
    }

    @Test fun knownUnplayableWithoutReceiptIsAnImmutableOutcomeNotAnAutomaticSourceRetry() = runTest {
        val rig = Rig()
        val occurrence = rig.admit()
        rig.gateway.acquireResult = { SourceAcquisitionResult.Unplayable(it.id, null,
            AcquisitionProblem(AcquisitionProblemCode.SOURCE_FAILURE, SourceProblem(SourceProblemCode.NETWORK))) }
        val result = assertIs<AcquisitionResolution.Accepted>(rig.resolveCurrent())
        val failure = assertIs<AcquisitionOutcome.Unplayable>(result.record.outcome)
        assertEquals(AcquisitionFailureStage.SEARCH, failure.stage)
        assertNull(failure.receiptId)
        assertTrue(rig.repository.state.value.receipts.isEmpty())
        assertIs<AcquisitionResolution.Accepted>(rig.coordinator.resolve(occurrence.requestId!!))
        assertEquals(1, rig.gateway.acquisitions.size)
    }

    @Test fun blockedPageWithAValidFirstReceiptStaysUnplayableAcrossRestoreAndCannotBind() = runTest {
        val rig = Rig()
        val occurrence = rig.admit()
        val raw = FirstResultObservation(occurrence.requestId!!, rig.repository.state.value.requests.getValue(
            occurrence.requestId!!).request.completeTitle,
            SourceEntry(AcquisitionEntryKind.VIDEO, "ABCDEFGHIJK", "Actual first video title",
                "https://www.youtube.com/watch?v=ABCDEFGHIJK"), "https://www.youtube.com/watch?v=ABCDEFGHIJK")
        rig.gateway.acquireResult = { SourceAcquisitionResult.Unplayable(it.id, raw,
            AcquisitionProblem(AcquisitionProblemCode.FIRST_RESULT_UNAVAILABLE)) }
        val accepted = assertIs<AcquisitionResolution.Accepted>(rig.resolveCurrent())
        val failure = assertIs<AcquisitionOutcome.Unplayable>(accepted.record.outcome)
        assertEquals(AcquisitionFailureStage.SELECTION, failure.stage)
        assertEquals(raw, rig.repository.state.value.receipts.getValue(assertNotNull(failure.receiptId))
            .let { (it as FirstResultReceipt.Observed).observation })
        assertTrue(rig.repository.state.value.bindings.isEmpty())
        assertEquals(OccurrenceSelectionState.UNPLAYABLE,
            rig.repository.state.value.occurrences.getValue(occurrence.id).selectionState)
        // Room-only restore returns the same immutable outcome; no binding/occurrence is invented.
        val restored = rig.coordinator.restore()
        assertEquals(accepted.record, restored.requests.getValue(accepted.record.request.id))
        assertEquals(failure, restored.requests.getValue(accepted.record.request.id).outcome)
        assertEquals(AcquisitionFailureStage.SELECTION, restored.requests.getValue(accepted.record.request.id)
            .outcome.let { assertIs<AcquisitionOutcome.Unplayable>(it).stage })
        assertTrue(restored.bindings.isEmpty())
        assertFailsWith<VeyraException> {
            rig.coordinator.boundOccurrence(occurrence.id, ExecutionBindingId("authored-missing-binding"))
        }
        // A repeated resolve is not a second source query and cannot promote the blocked page.
        assertIs<AcquisitionResolution.Accepted>(rig.coordinator.resolve(accepted.record.request.id))
        assertEquals(1, rig.gateway.acquisitions.size)
        assertTrue(rig.repository.state.value.bindings.isEmpty())
    }

    @Test fun aDefensivelyRejectedSavedMemberWithoutAFirstReceiptIsAnAdmissionFailure() = runTest {
        val rig = Rig()
        rig.admit(rig.captures.member(authoredSnapshot(), 0))
        rig.gateway.acquireResult = { SourceAcquisitionResult.Unplayable(it.id, null,
            AcquisitionProblem(AcquisitionProblemCode.MEMBER_NOT_PLAYABLE)) }
        val result = assertIs<AcquisitionResolution.Accepted>(rig.resolveCurrent())
        val failure = assertIs<AcquisitionOutcome.Unplayable>(result.record.outcome)
        assertEquals(AcquisitionFailureStage.ADMISSION, failure.stage)
        assertNull(failure.receiptId)
        assertTrue(rig.gateway.inspections.isEmpty())
    }

    @Test fun offlineBoundSelectionCanRestoreButCannotInspectRenewOrQuery() = runTest {
        val rig = Rig()
        val occurrence = rig.admit()
        val outcome = assertIs<AcquisitionOutcome.Single>(assertIs<AcquisitionResolution.Accepted>(rig.resolveCurrent()).record.outcome)
        rig.offline = true
        assertEquals(outcome.bindingId, rig.coordinator.boundOccurrence(occurrence.id, outcome.bindingId).binding.id)
        assertEquals(FailureCode.OFFLINE_POLICY, assertFailsWith<VeyraException> { rig.coordinator.inspect(occurrence.id, outcome.bindingId) }.failure.code)
        assertTrue(rig.gateway.inspections.isEmpty())
        assertEquals(1, rig.gateway.acquisitions.size)
    }
}
