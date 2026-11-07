package dev.veyra.downloads

import dev.veyra.data.*
import dev.veyra.model.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

internal data class FinalizationSchema3Seed(
    val record: PhysicalDownloadJob,
    val attempt: PhysicalDownloadAttempt,
    val intent: PhysicalDownloadFinalization,
)

/**
 * Authored interrupted-finalization history, NOT a process-kill or playable-media proof.
 * Reuses the real Room fixture/canonical command sequence; no snapshot/graph injection,
 * fabricated publication/use/current-process check, or fake durable/native-drain receipt.
 * The original scheduler fixture bytes, representation and recorded duration are retained.
 * A finished PAUSED materializer is history: recovery must not rewrite it as SUCCEEDED.
 */
internal suspend fun DownloadAcquisitionRoomFixture.prepareFinalizationSchema3(
    owner: DownloadAcquisitionRoomOwner,
    published: Boolean = false,
): FinalizationSchema3Seed {
    val accepted = acceptNeutral(owner, newEntityId("finalization-regression"), authoredVideo)
    val id = enqueue(owner, accepted)
    val before = assertNotNull(owner.repository.physicalDownload(id))
    val attemptId = newEntityId("authored-retired-materializer")
    val part = files.part(before.job.producer.subjectId.value, attemptId)
    val final = files.finalPath(before.job.producer.subjectId.value, 1, "audio/mpeg", attemptId)
    val slot = assertNotNull(files.tryTransferSlot(1))
    try {
        val paths = assertNotNull(files.tryLeasePaths(listOf(part.toString(), final.toString())))
        try {
            files.prepareStagingPath(part.toString())
            val claimed = assertIs<PhysicalDownloadCommit.Committed<ClaimedPhysicalDownload>>(
                owner.repository.claimPhysicalDownloadAttempt(ClaimPhysicalDownloadAttempt(
                    id, before.job.producer, before.job.ownershipGeneration, attemptId,
                    "authored-preparation-owner", clock.epochMs(), 5_000, part.toString())))
            val fence = claimed.value.fence
            assertIs<PhysicalDownloadCommit.Committed<*>>(owner.repository.selectPhysicalDownloadRepresentation(
                SelectPhysicalDownloadRepresentation(fence, authoredRepresentation, 1, null, null, clock.epochMs())))
            Files.write(Path.of(part.toString()), authoredBytes)
            assertIs<PhysicalDownloadCommit.Committed<*>>(owner.repository.heartbeatPhysicalDownload(
                HeartbeatPhysicalDownload(fence, clock.epochMs(), 5_000,
                    authoredBytes.size.toLong(), authoredBytes.size.toLong())))
            assertIs<PhysicalDownloadCommit.Committed<*>>(owner.repository.beginPhysicalDownloadValidation(
                BeginPhysicalDownloadValidation(fence, clock.epochMs())))
            val intent = PhysicalDownloadFinalization(
                downloadId = id, producer = before.job.producer,
                stagingPath = part.toString(), finalPath = final.toString(),
                sha256 = files.sha256(part), sizeBytes = authoredBytes.size.toLong(),
                attemptId = attemptId, mediaDurationMs = 31_234, mimeType = "audio/mpeg", codecs = "mp3",
                representationId = authoredRepresentation, validatorGeneration = 1,
                ownershipGeneration = fence.ownershipGeneration,
                assetId = MaterializationAssetId(newEntityId("authored-proposed-materialization")),
            )
            assertIs<PhysicalDownloadCommit.Committed<*>>(owner.repository.preparePhysicalDownloadFinalization(
                PreparePhysicalDownloadFinalization(fence, intent, clock.epochMs())))
            if (published) files.atomicPublish(intent.stagingPath, intent.finalPath)
            val retired = assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(
                owner.repository.finishPhysicalDownloadAttempt(FinishPhysicalDownloadAttempt(
                    fence, DownloadStatus.VERIFYING, DownloadAttemptStatus.PAUSED, null, null, 0, clock.epochMs()))).value
            val attempt = retired.attempts.single { it.id == attemptId }
            assertEquals(DownloadStatus.VERIFYING, retired.job.status)
            assertNull(retired.job.leaseOwner)
            assertNull(retired.job.activeAttemptId)
            assertEquals(intent, retired.finalization)
            assertNull(retired.asset)
            assertEquals(DownloadAttemptStatus.PAUSED, attempt.status)
            assertNotNull(attempt.finishedEpochMs)
            assertEquals(intent.producer, attempt.producer)
            assertEquals(intent.ownershipGeneration, attempt.ownershipGeneration)
            return FinalizationSchema3Seed(retired.job, attempt, intent)
        } finally { paths.close() }
    } finally { slot.close() }
}

/** Fail at accidental source ingress as well as checking all observed source counters later. */
internal fun DownloadAcquisitionRoomOwner.denyFinalizationSchema3Acquisition() {
    selector.answer = { error("Local finalization must not issue a new first-result query") }
    gateway.prepareResult = { _, _ -> error("Local finalization must not prepare a source") }
    gateway.transfer = { _, _, _ -> error("Local finalization must not transfer media") }
}

internal fun DownloadAcquisitionRoomOwner.assertFinalizationSchema3SourceUnused() {
    assertTrue(selector.requests.isEmpty())
    assertEquals(0, selector.restores.get())
    assertTrue(gateway.preparations.isEmpty())
    assertTrue(gateway.transfers.isEmpty())
    legacy.assertUnused()
}

/** Assertions only: actual runtime publication must preserve every materializer fact. */
internal suspend fun DownloadAcquisitionRoomOwner.assertFinalizationSchema3Recovered(seed: FinalizationSchema3Seed) {
    val state = store.acquisitionView().acquisition
    val job = state.physicalDownloads.getValue(seed.record.id)
    val asset = state.materializations.getValue(assertNotNull(job.assetId))
    assertEquals(DownloadStatus.COMPLETE, job.status)
    assertNull(job.failure)
    assertNull(job.leaseOwner)
    assertNull(state.physicalFinalizations[seed.record.id])
    assertEquals(seed.intent.producer, asset.producer)
    assertEquals(seed.intent.assetId, asset.id)
    assertEquals(seed.intent.finalPath, asset.uri)
    assertEquals(seed.intent.sha256, asset.sha256)
    assertEquals(seed.intent.sizeBytes, asset.sizeBytes)
    assertEquals(seed.intent.representationId, asset.representationId)
    assertEquals(seed.intent.validatorGeneration, asset.validatorGeneration)
    assertEquals(seed.intent.mediaDurationMs, asset.measuredDurationMs)
    assertEquals(seed.record.id, asset.materializerDownloadId)
    assertEquals(seed.attempt.id, asset.materializerAttemptId)
    assertEquals(seed.intent.ownershipGeneration, asset.ownershipGeneration)
    assertEquals(seed.attempt, state.physicalAttempts.getValue(seed.attempt.id))
    assertEquals(asset.receipt(), state.materializationReceipts[asset.id])
    assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(asset.uri)))
    assertFinalizationSchema3SourceUnused()
}
