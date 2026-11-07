package dev.veyra.downloads

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.data.room.VeyraSQLiteDriver
import dev.veyra.model.*
import okio.ByteString.Companion.toByteString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Helpers only. Real Room commands/owned files; all supplied media bytes and source facts are authored. */
internal suspend fun DownloadAcquisitionRoomFixture.enrollOriginUseBatch(
    owner: DownloadAcquisitionRoomOwner,
    count: Int = 1,
): DownloadBatch {
    val admitted = batch(owner, count)
    // Freeze the materializer order BEFORE activating execution; do not depend on scheduling order.
    admitted.members.forEach { member ->
        acceptMember(owner, admitted, member.position)
        owner.adapter.admitMember(admitted.id, member.position, admitted.intentRevision)
    }
    return owner.store.acquisitionView().acquisition.batches.getValue(admitted.id)
}

/** A second genuine observed scope may request the SAME immutable captured request/binding. */
internal suspend fun DownloadAcquisitionRoomFixture.repeatOriginUseBatch(
    owner: DownloadAcquisitionRoomOwner,
    original: DownloadBatch,
): DownloadBatch {
    val state = owner.store.acquisitionView().acquisition
    val member = state.batches.getValue(original.id).members.single()
    val scope = state.scopes.getValue(original.scopeId).copy(
        id = AdmissionScopeId(newEntityId("authored-repeated-origin-scope")), admittedEpochMs = clock.epochMs())
    val request = state.requests.getValue(member.requestId).request
    val snapshotMember = state.occurrences.getValue(member.occurrenceId).snapshotMember
    val occurrence = ExecutionOccurrence(ExecutionOccurrenceId(newEntityId("authored-repeated-origin-occurrence")),
        AcquisitionOrigin.TYPED, request.id, snapshotMember, AdmissionScopeMemberRef(scope.id, member.scopeMemberPosition),
        null, null, scope.controlsMode, OccurrenceSelectionState.PENDING, clock.epochMs())
    val repeated = owner.batches.admit(PreparedAcquisitionAdmission(listOf(request), scope, listOf(occurrence), null))
    assertTrue(owner.repository.attachOccurrence(AttachAcquisitionOccurrence(OccurrenceAttachmentExpectation(
        occurrence.id, request.id, null, null, repeated.id, repeated.intentRevision))))
    owner.adapter.admitMember(repeated.id, repeated.members.single().position, repeated.intentRevision)
    return owner.store.acquisitionView().acquisition.batches.getValue(repeated.id)
}

internal suspend fun DownloadAcquisitionRoomFixture.awaitOriginUsePublication(
    owner: DownloadAcquisitionRoomOwner,
    id: String,
): LibraryAcquisitionView {
    awaitState(owner.store) { state ->
        val job = state.physicalDownloads.getValue(id)
        job.status == DownloadStatus.COMPLETE && state.assetUses.any {
            it.requestedBindingId == job.producer.bindingId && it.materializationAssetId == job.assetId
        }
    }
    idle(owner)
    // COMPLETE can be observed before native/path/allowance cleanup; compare only the settled frame.
    return owner.store.acquisitionView()
}

internal fun assertOnlyOriginUse(state: AcquisitionRepositoryState, id: String): AssetUse {
    val job = state.physicalDownloads.getValue(id)
    val asset = state.materializations.getValue(assertNotNull(job.assetId))
    val use = state.assetUses.single { it.materializationAssetId == asset.id }
    assertEquals(job.producer.bindingId, use.requestedBindingId)
    assertEquals(1, use.compatibilityPolicyVersion)
    assertTrue(use.createdEpochMs >= asset.completedEpochMs)
    assertEquals(job.producer, asset.producer)
    assertEquals(asset.receipt(), state.materializationReceipts.getValue(asset.id))
    assertEquals(job.representationId, asset.representationId)
    assertEquals(job.validatorGeneration, asset.validatorGeneration)
    return use
}

internal data class OriginUsePublicationSeed(
    val fence: PhysicalDownloadFence,
    val intent: PhysicalDownloadFinalization,
    val asset: MaterializationAsset,
) {
    fun command(now: Long, owner: PhysicalDownloadFence = fence) =
        PublishPhysicalDownloadFinalization(owner, intent, asset.copy(completedEpochMs = now), now)
}

/**
 * Authored interrupted-process state through REAL canonical commands, not a seeded AssetUse.
 * This setup supplies synthetic already-validated metadata; runtime recovery must independently
 * hash and invoke its fake decoder before publication. It does not create a native/OS owner.
 */
internal suspend fun DownloadAcquisitionRoomFixture.stageOriginUsePublication(
    owner: DownloadAcquisitionRoomOwner,
    id: String,
    renamed: Boolean = false,
    retire: Boolean = false,
): OriginUsePublicationSeed {
    val before = assertNotNull(owner.repository.physicalDownload(id))
    val attemptId = newEntityId("authored-origin-materializer")
    val part = files.prepareStaging(before.job.producer.subjectId.value, attemptId)
    Files.write(Path.of(part.toString()), PHYSICAL_BYTES)
    val claimed = assertIs<PhysicalDownloadCommit.Committed<ClaimedPhysicalDownload>>(
        owner.repository.claimPhysicalDownloadAttempt(ClaimPhysicalDownloadAttempt(id, before.job.producer,
            before.job.ownershipGeneration, attemptId, "authored-dead-origin-process", clock.epochMs(),
            5_000, part.toString())))
    val fence = claimed.value.fence
    assertIs<PhysicalDownloadCommit.Committed<*>>(owner.repository.selectPhysicalDownloadRepresentation(
        SelectPhysicalDownloadRepresentation(fence, PHYSICAL_REPRESENTATION.representationId, 7,
            "\"authored-origin-validator\"", null, clock.epochMs())))
    assertIs<PhysicalDownloadCommit.Committed<*>>(owner.repository.heartbeatPhysicalDownload(HeartbeatPhysicalDownload(
        fence, clock.epochMs(), 5_000, PHYSICAL_BYTES.size.toLong(), PHYSICAL_BYTES.size.toLong())))
    assertIs<PhysicalDownloadCommit.Committed<*>>(owner.repository.beginPhysicalDownloadValidation(
        BeginPhysicalDownloadValidation(fence, clock.epochMs())))
    val intent = PhysicalDownloadFinalization(id, before.job.producer, part.toString(),
        files.finalPath(before.job.producer.subjectId.value, 7, "audio/mpeg", attemptId).toString(),
        PHYSICAL_BYTES.toByteString().sha256().hex(), PHYSICAL_BYTES.size.toLong(), attemptId, 733,
        "audio/mpeg", "mp3", PHYSICAL_REPRESENTATION.representationId, 7, fence.ownershipGeneration,
        MaterializationAssetId(newEntityId("authored-origin-asset")))
    assertIs<PhysicalDownloadCommit.Committed<*>>(owner.repository.preparePhysicalDownloadFinalization(
        PreparePhysicalDownloadFinalization(fence, intent, clock.epochMs())))
    if (renamed) files.atomicPublish(intent.stagingPath, intent.finalPath)
    if (retire) assertIs<PhysicalDownloadCommit.Committed<*>>(owner.repository.finishPhysicalDownloadAttempt(
        FinishPhysicalDownloadAttempt(fence, DownloadStatus.VERIFYING, DownloadAttemptStatus.PAUSED,
            null, null, 0, clock.epochMs())))
    val asset = MaterializationAsset(assertNotNull(intent.assetId), intent.producer, intent.finalPath,
        intent.sizeBytes, mimeType = intent.mimeType, codecs = intent.codecs, sha256 = intent.sha256,
        representationId = intent.representationId, validatorGeneration = intent.validatorGeneration,
        completedEpochMs = clock.epochMs(), measuredDurationMs = intent.mediaDurationMs,
        materializerDownloadId = id, materializerAttemptId = intent.attemptId,
        ownershipGeneration = intent.ownershipGeneration)
    assertTrue(owner.store.acquisitionView().acquisition.assetUses.isEmpty())
    return OriginUsePublicationSeed(fence, intent, asset)
}

/** Raw fault injection uses only the same test-owned SQLite file; no fake Room command backend. */
internal fun <T> originUseSql(path: Path, block: (SQLiteConnection) -> T): T =
    VeyraSQLiteDriver(BundledSQLiteDriver()).open(path.toString()).use(block)
