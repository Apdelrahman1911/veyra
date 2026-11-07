package dev.veyra.downloads

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * AUTHORED, NOT EXECUTED. Real Room/canonical commands and the existing Coordinator; deliberately
 * authored selection facts, fake execution/decoding and NON-AUDIO bytes. No live/native proof.
 * Cold cases never activate that Coordinator or initialize its files/OS/native owners.
 */
class DownloadMemberRetryRoomTest {
    @Test fun genuineCatalogueMemberRetryUsesItsOriginalCaptureRatherThanTheCurrentEditedTitle() = acquisitionDownloadRoomCase {
        val owner = owner()
        val captures = listOf(
            authoredRequest("authored-retry-catalogue-0", MediaCapture(authoredMedia("authored-retry-media-0"))),
            authoredRequest("authored-retry-catalogue-1", MediaCapture(authoredMedia("authored-retry-media-1"))),
        ).map { it.copy(capturedEpochMs = clock.epochMs()) }
        val batch = owner.batches.admit(AcquisitionAdmissionFactory(AcquisitionCaptureFactory(clock), clock)
            .catalogLeaves(captures, PlaybackControlsMode.MUSIC, null))
        val target = attachRetryMember(owner, batch, 1)
        failRetryJob(owner, target.fence.job.physicalJobId)
        val mediaId = assertIs<MediaCapture>(captures[1].metadata).media.id
        owner.store.update { snapshot -> snapshot.copy(media = snapshot.media +
            (mediaId to snapshot.media.getValue(mediaId).copy(title = "Edited catalogue title — ليس الطلب الأصلي"))) }.getOrThrow()
        val command = retryRoomCommand(owner, batch.id, 1)
        val before = owner.store.acquisitionView()
        assertEquals(AdmissionScopeKind.CATALOG_LEAVES, before.acquisition.scopes.getValue(batch.scopeId).kind)
        assertNotEquals(captures[1].completeTitle, before.snapshot.media.getValue(mediaId).title)
        clock.advance(1)
        val result = assertIs<AcquisitionDownloadRetryResult.Retried>(owner.adapter.retryMember(command))
        val after = owner.store.acquisitionView()
        assertOnlyRetryMetadataChanged(before, after, command, result, clock.epochMs())
        assertEquals(captures[1], after.acquisition.requests.getValue(command.member.requestId).request)
        assertNoRetryWork(owner)
    }

    @Test fun coldOfflineRetryIsExactlyOneMetadataCommitWithoutChangingTheBatchIntentOrAcceptedHistory() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner)
        val admitted = attachRetryMember(owner, batch)
        failRetryJob(owner, admitted.fence.job.physicalJobId)
        offline(owner)
        owner.coordinator.initializeScheduling()
        val command = retryRoomCommand(owner, batch.id)
        val before = owner.store.acquisitionView()
        val coverageBefore = owner.coverageChecks.get()
        clock.advance(1)
        val result = assertIs<AcquisitionDownloadRetryResult.Retried>(owner.adapter.retryMember(command))
        assertOnlyRetryMetadataChanged(before, owner.store.acquisitionView(), command, result, clock.epochMs())
        assertEquals(coverageBefore + 2, owner.coverageChecks.get(), "Coverage is checked again immediately before Room CAS")
        assertNotNull(before.acquisition.requests.getValue(command.member.requestId).lease, "Retained selection lease is provenance")
        assertEquals(DownloadStatus.QUEUED, result.job.status)
        assertNoRetryWork(owner)
    }

    @Test fun sharedJobRetryBumpsOnlyTheSelectedClaimAndNeverTouchesAnUnrelatedPhysicalMember() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner, count = 3)
        val first = attachRetryMember(owner, batch, 0)
        val second = attachRetryMember(owner, batch, 1)
        val other = attachRetryMember(owner, batch, 2, "ABCDEFGHIJK")
        assertEquals(first.fence.job, second.fence.job, "Same accepted C/subject may share R1's physical producer")
        assertNotEquals(first.fence.claim.requestedBindingId, second.fence.claim.requestedBindingId)
        assertNotEquals(first.fence.job.physicalJobId, other.fence.job.physicalJobId)
        failRetryJob(owner, first.fence.job.physicalJobId)
        failRetryJob(owner, other.fence.job.physicalJobId)
        val command = retryRoomCommand(owner, batch.id, 1)
        val before = owner.store.acquisitionView()
        clock.advance(1)
        val result = assertIs<AcquisitionDownloadRetryResult.Retried>(owner.adapter.retryMember(command))
        val after = owner.store.acquisitionView()
        assertOnlyRetryMetadataChanged(before, after, command, result, clock.epochMs())
        assertEquals(before.acquisition.claims[first.fence.claim.id], after.acquisition.claims[first.fence.claim.id])
        assertEquals(before.acquisition.claims[other.fence.claim.id], after.acquisition.claims[other.fence.claim.id])
        assertEquals(before.acquisition.physicalDownloads[other.fence.job.physicalJobId], after.acquisition.physicalDownloads[other.fence.job.physicalJobId])
        assertEquals(before.acquisition.batches.getValue(batch.id).members[2], after.acquisition.batches.getValue(batch.id).members[2])
        assertEquals(listOf(DownloadBatchMemberState.WAITING_FOR_JOB, DownloadBatchMemberState.WAITING_FOR_JOB),
            after.acquisition.batches.getValue(batch.id).members.take(2).map { it.state })
        assertEquals(first.fence.job.producerBindingId, result.job.producer.bindingId)
        assertNoRetryWork(owner)
    }

    @Test fun explicitTransientRetryClearsTheExistingBoundedBackoffWithoutASecondSelection() = acquisitionDownloadRoomCase {
        val owner = owner()
        for (code in listOf(FailureCode.NETWORK_UNAVAILABLE, FailureCode.RATE_LIMITED)) {
            val batch = batch(owner)
            val admitted = attachRetryMember(owner, batch, video = if (code == FailureCode.NETWORK_UNAVAILABLE) "LMNOPQRSTUV" else "ABCDEFGHIJK")
            val failed = failRetryJob(owner, admitted.fence.job.physicalJobId, AppFailure(code), DownloadStatus.WAITING_NETWORK, 3)
            assertTrue(assertNotNull(failed.nextRetryEpochMs) > clock.epochMs())
            val command = retryRoomCommand(owner, batch.id)
            val before = owner.store.acquisitionView()
            clock.advance(1)
            val result = assertIs<AcquisitionDownloadRetryResult.Retried>(owner.adapter.retryMember(command))
            assertOnlyRetryMetadataChanged(before, owner.store.acquisitionView(), command, result, clock.epochMs())
            assertNull(result.job.nextRetryEpochMs)
            assertEquals(0, result.job.automaticRetryCount)
        }
        assertNoRetryWork(owner)
    }

    @Test fun concurrentIdenticalDisplayedRetriesHaveExactlyOneSuccessAndNoSuccessfulNoOp() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner)
        val admitted = attachRetryMember(owner, batch)
        failRetryJob(owner, admitted.fence.job.physicalJobId)
        val command = retryRoomCommand(owner, batch.id)
        val before = owner.store.acquisitionView()
        clock.advance(1)
        val results = coroutineScope {
            val start = CompletableDeferred<Unit>()
            val first = async(Dispatchers.Default) { start.await(); owner.adapter.retryMember(command) }
            val second = async(Dispatchers.Default) { start.await(); owner.adapter.retryMember(command) }
            start.complete(Unit)
            listOf(first.await(), second.await())
        }
        val result = results.filterIsInstance<AcquisitionDownloadRetryResult.Retried>().single()
        assertEquals(1, results.filterIsInstance<AcquisitionDownloadRetryResult.Unavailable>().size)
        assertOnlyRetryMetadataChanged(before, owner.store.acquisitionView(), command, result, clock.epochMs())
        assertNoRetryWork(owner)
    }

    @Test fun staleStatusAndDeadlineRejectEvenWhenPhysicalOwnershipGenerationDidNotChange() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner)
        val admitted = attachRetryMember(owner, batch)
        failRetryJob(owner, admitted.fence.job.physicalJobId, AppFailure(FailureCode.NETWORK_UNAVAILABLE), DownloadStatus.WAITING_NETWORK)
        val displayed = retryRoomCommand(owner, batch.id)
        val join = assertNotNull(owner.repository.physicalDownload(displayed.expectedJob.id))
        clock.advance(1)
        val changed = assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(
            owner.repository.reconcilePhysicalDownloadIntent(ReconcilePhysicalDownloadIntent(join.job.id,
                join.job.producer, join.job.ownershipGeneration, join.claims, clock.epochMs(), allowHostResume = true)))
        assertEquals(displayed.expectedJob.ownershipGeneration, changed.value.job.ownershipGeneration)
        assertEquals(DownloadStatus.QUEUED, changed.value.job.status)
        assertNull(changed.value.job.nextRetryEpochMs)
        assertNotEquals(displayed.expectedJob, changed.value.job)
        val before = owner.store.acquisitionView()
        assertIs<PhysicalDownloadCommit.Stale>(owner.repository.retryBatchPhysicalDownload(displayed.roomRetry(clock.epochMs())))
        assertIs<AcquisitionDownloadRetryResult.Unavailable>(owner.adapter.retryMember(displayed))
        assertEquals(before, owner.store.acquisitionView())
        assertNoRetryWork(owner)
    }

    @Test fun wrongMemberClaimBindingRecordingAndWholeJobAreIndependentlyRejectedAtEveryBoundary() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner)
        val admitted = attachRetryMember(owner, batch)
        failRetryJob(owner, admitted.fence.job.physicalJobId)
        val command = retryRoomCommand(owner, batch.id)
        val producer = assertIs<PhysicalDownloadProducer.Typed>(command.expectedJob.producer)
        val wrong = listOf(
            command.copy(member = command.member.copy(memberPosition = 1)),
            command.copy(member = command.member.copy(requestId = AcquisitionRequestId("authored-wrong-request"))),
            command.copy(member = command.member.copy(occurrenceId = ExecutionOccurrenceId("authored-wrong-occurrence"))),
            command.copy(member = command.member.copy(requestedBindingId = ExecutionBindingId("authored-wrong-binding"))),
            command.copy(expectedClaim = command.expectedClaim.copy(id = DownloadClaimId("authored-wrong-claim"))),
            command.copy(expectedClaim = command.expectedClaim.copy(generation = command.expectedClaim.generation + 1)),
            command.copy(expectedJob = command.expectedJob.copy(producer = producer.copy(recordingId = SourceRecordingId("authored-wrong-c")))),
            command.copy(expectedJob = command.expectedJob.copy(nextRetryEpochMs = command.expectedJob.nextRetryEpochMs?.plus(1))),
            command.copy(expectedJob = command.expectedJob.copy(bytesDownloaded = command.expectedJob.bytesDownloaded + 1)),
        )
        val before = owner.store.acquisitionView()
        for (stale in wrong) {
            assertIs<AcquisitionDownloadRetryResult.Unavailable>(owner.adapter.retryMember(stale))
            assertIs<AcquisitionDownloadRetryResult.Unavailable>(owner.coordinator.acquisitionExecutor.retryMember(stale))
            assertIs<PhysicalDownloadCommit.Stale>(owner.repository.retryBatchPhysicalDownload(stale.roomRetry(clock.epochMs())))
            assertEquals(before, owner.store.acquisitionView())
        }
        assertNoRetryWork(owner)
    }

    @Test fun pendingUnknownAndCollectionMembersCannotBorrowAnAcceptedMembersClaimToSearchAgain() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner, count = 4)
        val admitted = attachRetryMember(owner, batch, 0)
        failRetryJob(owner, admitted.fence.job.physicalJobId)
        val unknownMember = batch.members[2]
        val unknownLease = assertIs<AcquisitionClaimResult.Claimed>(owner.repository.claim(ClaimAcquisition(unknownMember.requestId,
            AcquisitionAttemptToken("authored-sent-not-returned"), "authored-metadata-owner", clock.epochMs(), 60_000))).lease
        owner.repository.markOutcomeUnknown(MarkAcquisitionOutcomeUnknown(unknownLease, clock.epochMs()))
        val collectionRequest = assertNotNull(owner.repository.request(batch.members[3].requestId)).request
        accept(owner, collectionRequest, authoredCollection(collectionRequest))
        val actual = retryRoomCommand(owner, batch.id, 0)
        val before = owner.store.acquisitionView()
        assertEquals(AcquisitionJobState.OUTCOME_UNKNOWN, before.acquisition.requests.getValue(unknownMember.requestId).state)
        // The canonical child-collection result is a visible unplayable outcome, never a recursive collection capability.
        val childOutcome = assertIs<AcquisitionOutcome.Unplayable>(before.acquisition.requests.getValue(collectionRequest.id).outcome)
        assertEquals(AcquisitionProblemCode.MEMBER_NOT_PLAYABLE, childOutcome.problem.code)
        val childReceipt = assertIs<FirstResultReceipt.Observed>(before.acquisition.receipts[childOutcome.receiptId])
        assertEquals(AcquisitionEntryKind.PLAYLIST, childReceipt.observation.entry.kind)
        for (member in batch.members.drop(1)) {
            val invented = actual.copy(member = actual.member.copy(memberPosition = member.position,
                occurrenceId = member.occurrenceId, requestId = member.requestId))
            assertIs<AcquisitionDownloadRetryResult.Unavailable>(owner.adapter.retryMember(invented))
            assertIs<AcquisitionDownloadRetryResult.Unavailable>(owner.coordinator.acquisitionExecutor.retryMember(invented))
            assertIs<PhysicalDownloadCommit.Stale>(owner.repository.retryBatchPhysicalDownload(invented.roomRetry(clock.epochMs())))
        }
        assertEquals(before, owner.store.acquisitionView())
        assertNoRetryWork(owner)
    }

    @Test fun changedBatchIntentInvalidatesTheOldRetryWithoutImplicitResume() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner)
        val admitted = attachRetryMember(owner, batch)
        failRetryJob(owner, admitted.fence.job.physicalJobId)
        val command = retryRoomCommand(owner, batch.id)
        val paused = owner.batches.setIntent(batch.id, batch.intentRevision, DownloadBatchIntent.PAUSED)
        val before = owner.store.acquisitionView()
        assertTrue(paused.intentRevision > command.member.intentRevision)
        assertIs<AcquisitionDownloadRetryResult.Unavailable>(owner.adapter.retryMember(command))
        assertIs<PhysicalDownloadCommit.Stale>(owner.repository.retryBatchPhysicalDownload(command.roomRetry(clock.epochMs())))
        assertEquals(before, owner.store.acquisitionView())
        assertEquals(DownloadBatchIntent.PAUSED, before.acquisition.batches.getValue(batch.id).intent)
        assertNoRetryWork(owner)
    }

    @Test fun deniedCoverageInitiallyOrAtTheFinalPreCasCheckLeavesNoLatentRetryIntent() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner)
        val admitted = attachRetryMember(owner, batch)
        failRetryJob(owner, admitted.fence.job.physicalJobId)
        val command = retryRoomCommand(owner, batch.id)
        val failure = AppFailure(FailureCode.RECOVERY_REQUIRED, "authored_retry_coverage_denied")
        for (denyAt in listOf(1, 2)) {
            val checks = AtomicInteger()
            owner.admission = { if (checks.incrementAndGet() == denyAt) throw VeyraException(failure) }
            val before = owner.store.acquisitionView()
            val rejected = assertIs<AcquisitionDownloadRetryResult.Unavailable>(owner.adapter.retryMember(command))
            assertEquals(failure, rejected.failure)
            assertEquals(denyAt, checks.get())
            assertEquals(before, owner.store.acquisitionView())
        }
        owner.admission = { }
        assertNoRetryWork(owner)
    }

    @Test fun suspendedAndClosedOwnersRejectInsteadOfImplicitlyReopeningOrActivating() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner)
        val admitted = attachRetryMember(owner, batch)
        failRetryJob(owner, admitted.fence.job.physicalJobId)
        owner.coordinator.drainForBackground()
        val command = retryRoomCommand(owner, batch.id)
        var before = owner.store.acquisitionView()
        val suspended = assertIs<AcquisitionDownloadRetryResult.Unavailable>(owner.adapter.retryMember(command))
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, suspended.failure.code)
        assertEquals(before, owner.store.acquisitionView())
        owner.coordinator.close()
        before = owner.store.acquisitionView()
        val closed = assertIs<AcquisitionDownloadRetryResult.Unavailable>(owner.adapter.retryMember(command))
        assertEquals(FailureCode.INVALID_STATE, closed.failure.code)
        assertEquals(before, owner.store.acquisitionView())
        assertNoRetryWork(owner)
    }

    @Test fun transactionFailureRollsBackJobClaimBatchProjectionAndRevisionTogether() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner)
        val admitted = attachRetryMember(owner, batch)
        failRetryJob(owner, admitted.fence.job.physicalJobId)
        val command = retryRoomCommand(owner, batch.id)
        val before = owner.store.acquisitionView()
        val failure = AppFailure(FailureCode.STORAGE_IO, "authored_retry_transaction_rollback")
        val callbacks = AtomicInteger()
        faults(owner.store).beforeCommit = { old, next ->
            if (old.state().claims[command.expectedClaim.id]?.generation == command.expectedClaim.generation &&
                next.state().claims[command.expectedClaim.id]?.generation == command.expectedClaim.generation + 1) {
                callbacks.incrementAndGet()
                throw VeyraException(failure)
            }
        }
        val rejected = assertIs<AcquisitionDownloadRetryResult.Unavailable>(owner.adapter.retryMember(command))
        assertEquals(failure, rejected.failure)
        assertEquals(1, callbacks.get())
        assertEquals(before, owner.store.acquisitionView())
        faults(owner.store).beforeCommit = { _, _ -> }
        assertNoRetryWork(owner)
    }

    @Test fun fabricatedPositiveExecutorReturnCannotReplaceTheActualCommittedGeneration() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner)
        val admitted = attachRetryMember(owner, batch)
        failRetryJob(owner, admitted.fence.job.physicalJobId)
        val command = retryRoomCommand(owner, batch.id)
        val before = owner.store.acquisitionView()
        val calls = AtomicInteger()
        val dishonest = object : AcquisitionDownloadExecutor by owner.coordinator.acquisitionExecutor {
            override suspend fun retryMember(command: RetryAcquisitionDownload): AcquisitionDownloadRetryResult {
                calls.incrementAndGet()
                val claim = command.expectedClaim.copy(generation = command.expectedClaim.generation + 1, updatedEpochMs = clock.epochMs())
                val fence = AcquisitionDownloadFence(this, command.member, claim, admitted.fence.job, admitted.fence.generation)
                return AcquisitionDownloadRetryResult.Retried(AcquisitionDownloadAdmission(fence, before.snapshot.revision),
                    command.expectedJob.copy(status = DownloadStatus.QUEUED, failure = null, nextRetryEpochMs = null,
                        automaticRetryCount = 0, updatedEpochMs = clock.epochMs()))
            }
        }
        val adapter = AcquisitionDownloadAdapter(owner.repository, owner.acquisition, dishonest)
        assertIs<AcquisitionDownloadRetryResult.Unavailable>(adapter.retryMember(command))
        assertEquals(1, calls.get())
        assertEquals(before, owner.store.acquisitionView(), "A fake success cannot mutate Room or count as a successful no-op")
        assertNoRetryWork(owner)
    }

    @Test fun cancellationAfterRealCommitPropagatesWithoutCompensatingCancellationOrDuplicateRetry() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner)
        val admitted = attachRetryMember(owner, batch)
        failRetryJob(owner, admitted.fence.job.physicalJobId)
        val command = retryRoomCommand(owner, batch.id)
        val before = owner.store.acquisitionView()
        var committed: AcquisitionDownloadRetryResult.Retried? = null
        val real = owner.coordinator.acquisitionExecutor
        val lostReturn = object : AcquisitionDownloadExecutor by real {
            override suspend fun retryMember(command: RetryAcquisitionDownload): AcquisitionDownloadRetryResult {
                committed = assertIs<AcquisitionDownloadRetryResult.Retried>(real.retryMember(command))
                throw CancellationException("Authored caller cancellation after the real Room commit")
            }
        }
        clock.advance(1)
        val adapter = AcquisitionDownloadAdapter(owner.repository, owner.acquisition, lostReturn)
        assertFailsWith<CancellationException> { adapter.retryMember(command) }
        val after = owner.store.acquisitionView()
        assertOnlyRetryMetadataChanged(before, after, command, assertNotNull(committed), clock.epochMs())
        assertIs<AcquisitionDownloadRetryResult.Unavailable>(owner.adapter.retryMember(command))
        assertEquals(after, owner.store.acquisitionView())
        assertNoRetryWork(owner)
    }

    @Test fun realObservedHoleKeepsScopePositionTwoWhenBatchMemberPositionOneRetries() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = retryBatchWithHole(owner)
        assertEquals(listOf(0, 1), batch.members.map { it.position })
        assertEquals(listOf(0, 2), batch.members.map { it.scopeMemberPosition })
        attachRetryMember(owner, batch, 0, "ABCDEFGHIJK")
        val target = attachRetryMember(owner, batch, 1, "LMNOPQRSTUV")
        failRetryJob(owner, target.fence.job.physicalJobId)
        val command = retryRoomCommand(owner, batch.id, 1)
        val before = owner.store.acquisitionView()
        val occurrence = before.acquisition.occurrences.getValue(command.member.occurrenceId)
        assertEquals(AdmissionScopeMemberRef(batch.scopeId, 2), occurrence.scopeMember)
        assertIs<CollectionMemberCapture>(before.acquisition.requests.getValue(command.member.requestId).request.metadata)
        clock.advance(1)
        val result = assertIs<AcquisitionDownloadRetryResult.Retried>(owner.adapter.retryMember(command))
        assertOnlyRetryMetadataChanged(before, owner.store.acquisitionView(), command, result, clock.epochMs())
        assertNoRetryWork(owner)
    }
}
