package dev.veyra.downloads

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import java.nio.file.Files
import kotlin.test.*

/**
 * New helpers; the frozen real-Room fixture is not extended or rewritten. Setup through raw
 * canonical claim/select/heartbeat/finish commands supplies AUTHORED METADATA, not a transfer
 * or an OS lease. Tests which need live resource owners explicitly start the real Coordinator.
 */
internal suspend fun DownloadAcquisitionRoomFixture.attachRetryMember(
    owner: DownloadAcquisitionRoomOwner,
    batch: DownloadBatch,
    position: Int = 0,
    video: String = "LMNOPQRSTUV",
): AcquisitionDownloadAdmission {
    acceptMember(owner, batch, position, video = video)
    return owner.adapter.admitMember(batch.id, position, batch.intentRevision)
}

internal suspend fun DownloadAcquisitionRoomFixture.failRetryJob(
    owner: DownloadAcquisitionRoomOwner,
    id: String,
    failure: AppFailure = AppFailure(FailureCode.STORAGE_IO, "authored_retry_failure"),
    status: DownloadStatus = DownloadStatus.FAILED,
    count: Int = 5,
): PhysicalDownloadJob {
    val original = assertNotNull(owner.repository.physicalDownload(id)).job
    val claimed = assertIs<PhysicalDownloadCommit.Committed<ClaimedPhysicalDownload>>(
        owner.repository.claimPhysicalDownloadAttempt(ClaimPhysicalDownloadAttempt(id, original.producer,
            original.ownershipGeneration, newEntityId("authored-retry-attempt"), "authored-metadata-owner", clock.epochMs(),
            5_000, files.part(id, "authored-metadata-not-created").toString())))
    val fence = claimed.value.fence
    assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(
        owner.repository.selectPhysicalDownloadRepresentation(SelectPhysicalDownloadRepresentation(fence,
            PHYSICAL_REPRESENTATION.representationId, 3, "\"authored-validator\"", "Authored prior metadata", clock.epochMs())))
    assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(
        owner.repository.heartbeatPhysicalDownload(HeartbeatPhysicalDownload(fence, clock.epochMs(), 5_000, 17, 91)))
    val now = clock.epochMs()
    return assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(
        owner.repository.finishPhysicalDownloadAttempt(FinishPhysicalDownloadAttempt(fence, status,
            DownloadAttemptStatus.FAILED, failure, now + 600_000, count, now))).value.job
}

internal suspend fun retryRoomCommand(owner: DownloadAcquisitionRoomOwner, batchId: DownloadBatchId,
    position: Int = 0): RetryAcquisitionDownload {
    val state = owner.store.acquisitionView().acquisition
    val batch = state.batches.getValue(batchId)
    val member = batch.members.single { it.position == position }
    val claim = state.claims.getValue(assertNotNull(member.claimId))
    return RetryAcquisitionDownload(AcquisitionDownloadMemberKey(batch.id, member.position, batch.intentRevision,
        member.occurrenceId, member.requestId, assertNotNull(member.requiredBindingId)), claim,
        state.physicalDownloads.getValue(claim.physicalJobId))
}

internal fun RetryAcquisitionDownload.roomRetry(now: Long) = RetryBatchPhysicalDownload(
    BatchDownloadMemberExpectation(member.batchId, member.memberPosition, member.intentRevision,
        member.occurrenceId, member.requestId, member.requestedBindingId, expectedClaim.id), expectedClaim, expectedJob, now,
)

internal fun DownloadAcquisitionRoomFixture.assertNoRetryWork(owner: DownloadAcquisitionRoomOwner) {
    assertTrue(owner.selector.requests.isEmpty())
    assertTrue(owner.gateway.preparations.isEmpty())
    assertTrue(owner.gateway.transfers.isEmpty())
    assertTrue(owner.validations.isEmpty())
    assertEquals(0, owner.gate.acquires.get())
    assertEquals(0, leases.open.get())
    assertFalse(Files.exists(directory.resolve("media")), "Metadata Retry cannot initialize files or acquire native ownership")
    owner.legacy.assertUnused()
}

/** Exact graph oracle independent of the production reset/helper functions. */
internal fun assertOnlyRetryMetadataChanged(
    before: LibraryAcquisitionView,
    after: LibraryAcquisitionView,
    command: RetryAcquisitionDownload,
    returned: AcquisitionDownloadRetryResult.Retried,
    now: Long,
) {
    val job = command.expectedJob.copy(status = DownloadStatus.QUEUED, failure = null,
        nextRetryEpochMs = null, automaticRetryCount = 0, updatedEpochMs = now)
    val claim = command.expectedClaim.copy(generation = command.expectedClaim.generation + 1, updatedEpochMs = now)
    val batches = before.acquisition.batches.mapValues { (_, batch) ->
        val members = batch.members.map { member ->
            val owner = member.claimId?.let(before.acquisition.claims::get)
            if (owner == null || owner.physicalJobId != job.id) member else {
                val state = when (owner.intent) {
                    DownloadBatchIntent.ACTIVE -> DownloadBatchMemberState.WAITING_FOR_JOB
                    DownloadBatchIntent.PAUSED -> DownloadBatchMemberState.PAUSED
                    DownloadBatchIntent.CANCELLED -> DownloadBatchMemberState.CANCELLED
                }
                member.copy(state = state, failure = null)
            }
        }
        if (members == batch.members) batch else batch.copy(members = members, updatedEpochMs = maxOf(batch.updatedEpochMs, now))
    }
    assertEquals(before.snapshot.revision + 1, after.snapshot.revision, "One real Room retry commit")
    assertEquals(before.snapshot.copy(revision = after.snapshot.revision), after.snapshot)
    assertEquals(before.acquisition.copy(libraryRevision = after.acquisition.libraryRevision,
        claims = before.acquisition.claims + (claim.id to claim),
        physicalDownloads = before.acquisition.physicalDownloads + (job.id to job), batches = batches), after.acquisition)
    assertEquals(job, returned.job)
    assertEquals(claim, returned.admission.fence.claim)
    assertEquals(command.member, returned.admission.fence.member)
    assertEquals(after.snapshot.revision, returned.admission.committedLibraryRevision)
    assertEquals(command.member.intentRevision, after.acquisition.batches.getValue(command.member.batchId).intentRevision)
    assertEquals(command.expectedClaim.ownerIntentRevision, claim.ownerIntentRevision)
}

internal suspend fun DownloadAcquisitionRoomFixture.retryBatchWithHole(owner: DownloadAcquisitionRoomOwner): DownloadBatch {
    val label = newEntityId("authored-retry-parent")
    val request = authoredRequest(label, AlbumCapture(Album("edition-$label", "Complete parent — المجموعة الأصلية")))
        .copy(capturedEpochMs = clock.epochMs())
    val selected = authoredCollection(request)
    val accepted = accept(owner, request, selected)
    val receipt = assertIs<AcquisitionOutcome.Collection>(accepted.outcome).receiptId
    val observation = authoredObservation(request, newEntityId("authored-retry-observation"))
    val members = (0..2).map { authoredMember(observation, it, hole = it == 1) }
    val appended = owner.repository.appendPage(AppendCollectionPage(receipt,
        CollectionPageData(selected, observation.id, observation, CollectionPageDisposition.OBSERVED_DELTA, 0, 0,
            members, CollectionExtent(CollectionTraversal.OBSERVED_TO_END, 3, 1)), null, null, clock.epochMs()))
    val snapshot = owner.repository.sealSnapshot(SealCollectionSnapshot(CollectionSnapshotId(newEntityId("authored-retry-snapshot")),
        observation.id, appended.run.frontier, clock.epochMs()))
    return owner.batches.admit(AcquisitionAdmissionFactory(AcquisitionCaptureFactory(clock), clock).collection(snapshot,
        ObservedCollectionChoice.Available(acknowledgesKnownHoles = true, acknowledgesUnobservedSuffix = false), PlaybackControlsMode.PODCAST))
}
