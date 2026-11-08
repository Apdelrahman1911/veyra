package dev.veyra.source.execution

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.downloads.*
import dev.veyra.model.*
import dev.veyra.playback.*
import okio.ByteString.Companion.toByteString
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real catalogue/request/first-result/occurrence/member/claim; not a seeded legacy projection. */
internal data class ResolverSchema3Member(
    val item: MediaItem,
    val batch: DownloadBatch,
    val bound: BoundAcquisitionOccurrence,
    val downloadId: String?,
) {
    val member: DownloadBatchMember get() = batch.members.single()
    val context: AcquisitionExecutionContext get() = bound.executionContext()

    fun currentKey(state: AcquisitionRepositoryState): AcquisitionDownloadMemberKey {
        val current = state.batches.getValue(batch.id)
        val row = current.members.single { it.occurrenceId == bound.occurrence.id }
        assertEquals(bound.request.id, row.requestId)
        assertEquals(bound.binding.id, row.requiredBindingId)
        return AcquisitionDownloadMemberKey(current.id, row.position, current.intentRevision,
            row.occurrenceId, row.requestId, assertNotNull(row.requiredBindingId))
    }
}

internal data class ResolverSchema3Publication(
    val member: ResolverSchema3Member,
    val asset: MaterializationAsset,
) {
    val downloadId: String get() = assertNotNull(member.downloadId)
    val context: AcquisitionExecutionContext get() = member.context
}

/**
 * The existing real Room fixture owns all stores/files/leases. Only selector/native answers
 * and NON-AUDIO transfer bytes are authored. No direct binding/download/use/evidence seeding.
 * Call while the coordinator is cold/drained so a background member worker cannot race setup.
 */
internal suspend fun DownloadAcquisitionRoomFixture.admitResolverMedia(
    owner: DownloadAcquisitionRoomOwner,
    item: MediaItem = executionTestItem(),
    video: String = FIRST_VIDEO_ID,
    admitPhysical: Boolean = true,
): ResolverSchema3Member {
    owner.store.upsertCatalog(listOf(item)).getOrThrow()
    assertTrue(owner.store.requiresTypedPlayback, "Do not hide AcquisitionPersistence behind a legacy facade")
    val view = owner.store.acquisitionView()
    val captures = AcquisitionCaptureFactory(clock)
    val request = captures.media(item, view.snapshot)
    val mode = if (item.kind == MediaKind.EPISODE) PlaybackControlsMode.PODCAST else PlaybackControlsMode.MUSIC
    val admitted = owner.batches.admit(AcquisitionAdmissionFactory(captures, clock).catalogLeaves(
        listOf(request), mode, view.snapshot.revision))
    owner.selector.answer = { selected -> authoredSingle(selected, video) }
    val queriesBefore = owner.selector.requests.size
    val selected = assertIs<AcquisitionResolution.Accepted>(owner.batches.resolveMember(
        admitted.id, admitted.members.single().position, admitted.intentRevision))
    assertEquals(listOf(request), owner.selector.requests.drop(queriesBefore))
    assertEquals(setOf(admitted.members.single().occurrenceId), selected.attachedOccurrences)
    assertTrue(selected.rejectedAttachments.isEmpty())
    val binding = assertIs<AcquisitionOutcome.Single>(selected.record.outcome).bindingId
    val id = if (admitPhysical) owner.adapter.admitMember(admitted.id, admitted.members.single().position,
        admitted.intentRevision).fence.job.physicalJobId else null
    val state = owner.store.acquisitionView().acquisition
    val batch = state.batches.getValue(admitted.id)
    val bound = owner.acquisition.boundOccurrence(batch.members.single().occurrenceId, binding, state)
    assertEquals(request, bound.request)
    assertEquals(item.title, bound.receipt.observation.queryTitle)
    assertEquals(0, bound.receipt.observation.entry.ordinal)
    assertEquals(video, bound.recording.canonicalId)
    assertIs<ExecutableSubject.CatalogMedia>(bound.subject)
    assertEquals(item.id, bound.subject.id.value)
    assertEquals(binding, batch.members.single().requiredBindingId)
    if (id != null) {
        val claim = state.claims.getValue(assertNotNull(batch.members.single().claimId))
        assertEquals(DownloadClaimOwner.BatchMember(batch.id, batch.members.single().position), claim.owner)
        assertEquals(binding, claim.requestedBindingId)
        assertEquals(id, claim.physicalJobId)
    }
    owner.legacy.assertUnused()
    return ResolverSchema3Member(item, batch, bound, id)
}

internal suspend fun DownloadAcquisitionRoomFixture.publishResolverMedia(
    owner: DownloadAcquisitionRoomOwner,
    member: ResolverSchema3Member,
): ResolverSchema3Publication {
    val id = assertNotNull(member.downloadId)
    owner.validation = { AssetValidation.Valid(30_000) }
    owner.coordinator.start(DownloadActivation.DURABLE_WORK)
    val view = awaitOriginUsePublication(owner, id)
    val job = view.acquisition.physicalDownloads.getValue(id)
    val asset = view.acquisition.materializations.getValue(assertNotNull(job.assetId))
    assertEquals(member.bound.binding.id, job.producer.bindingId)
    assertEquals(job.producer, asset.producer)
    assertEquals(asset.receipt(), view.acquisition.materializationReceipts[asset.id])
    assertTrue(view.acquisition.assetUses.any {
        it.requestedBindingId == member.bound.binding.id && it.materializationAssetId == asset.id
    })
    assertEquals(asset, owner.materializationEvidence.checks.value.currentValidAssets[id])
    assertEquals(asset, owner.materializationEvidence.checks.value.successfulChecks.getValue(id).asset)
    assertEquals(1, owner.gateway.transfers.count { it.context == member.context })
    assertContentEquals(PHYSICAL_BYTES, Files.readAllBytes(Path.of(asset.uri)))
    awaitCondition { owner.coordinator.acquisitionPresentation.value.states[id]?.status == DownloadStatus.COMPLETE }
    return ResolverSchema3Publication(member, asset)
}

internal suspend fun DownloadAcquisitionRoomFixture.completeResolverMedia(
    owner: DownloadAcquisitionRoomOwner,
): ResolverSchema3Publication = publishResolverMedia(owner, admitResolverMedia(owner))

internal suspend fun DownloadAcquisitionRoomFixture.reopenResolverOwner(
    previous: DownloadAcquisitionRoomOwner,
): DownloadAcquisitionRoomOwner {
    previous.coordinator.close()
    previous.store.close()
    return owner().also { it.store.initialize().getOrThrow() }
}

/** Counts forbidden resolver work instead of treating an unobserved throwing gateway as proof. */
internal class ResolverNoRemoteGateway(private val owner: DownloadAcquisitionRoomOwner) :
    AcquisitionExecutionGateway by owner.gateway {
    private val requests = owner.selector.requests.toList()
    private val preparations = owner.gateway.preparations.toList()
    private val transfers = owner.gateway.transfers.toList()
    val playbackCalls = AtomicInteger()

    override suspend fun preparePlayback(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
        requiredRepresentationId: String?, inspection: AcquisitionInspection?): AcquisitionPlaybackSource {
        playbackCalls.incrementAndGet()
        error("No remote playback/replacement is permitted in this authored local guard")
    }

    fun assertNoNewWork() {
        assertEquals(0, playbackCalls.get())
        assertEquals(requests, owner.selector.requests.toList(), "No new complete-title first-result query")
        assertEquals(preparations, owner.gateway.preparations.toList())
        assertEquals(transfers, owner.gateway.transfers.toList())
        owner.legacy.assertUnused()
    }
}

internal fun DownloadAcquisitionRoomFixture.resolverFor(
    owner: DownloadAcquisitionRoomOwner,
    gateway: ResolverNoRemoteGateway,
) = AcquisitionPlaybackResolver(owner.store, gateway, files, owner.localValidator, owner.materializationEvidence)

internal fun assertResolverColdEvidence(owner: DownloadAcquisitionRoomOwner) {
    assertTrue(owner.materializationEvidence.checks.value.successfulChecks.isEmpty())
    assertTrue(owner.materializationEvidence.checks.value.currentValidAssets.isEmpty())
}

internal fun assertResolverHistory(before: AcquisitionRepositoryState, after: AcquisitionRepositoryState) {
    assertEquals(before.requests, after.requests)
    assertEquals(before.receipts, after.receipts)
    assertEquals(before.bindings, after.bindings)
    assertEquals(before.recordings, after.recordings)
    assertEquals(before.subjects, after.subjects)
    assertEquals(before.occurrences, after.occurrences)
    assertEquals(before.assetUses, after.assetUses)
    assertEquals(before.materializationReceipts, after.materializationReceipts)
    assertEquals(before.physicalAttempts, after.physicalAttempts)
}

/**
 * Negative-command setup only: valid pending canonical job/finalization and real authored bytes.
 * Supplied already-validated metadata is NOT a native/physical owner or C0. This deliberately
 * never publishes, commits AssetUse or touches the evidence registry. A separate sibling's C0
 * must come from the actual leased runtime above. Coordinator must remain drained during setup.
 */
internal suspend fun DownloadAcquisitionRoomFixture.stageResolverPublication(
    owner: DownloadAcquisitionRoomOwner,
    member: ResolverSchema3Member,
): OriginUsePublicationSeed {
    val id = assertNotNull(member.downloadId)
    val before = assertNotNull(owner.repository.physicalDownload(id))
    assertEquals(DownloadStatus.QUEUED, before.job.status)
    val attemptId = newEntityId("authored-resolver-pending-materializer")
    val part = files.prepareStaging(before.job.producer.subjectId.value, attemptId)
    Files.write(Path.of(part.toString()), PHYSICAL_BYTES)
    val claimed = assertIs<PhysicalDownloadCommit.Committed<ClaimedPhysicalDownload>>(
        owner.repository.claimPhysicalDownloadAttempt(ClaimPhysicalDownloadAttempt(id, before.job.producer,
            before.job.ownershipGeneration, attemptId, "authored-resolver-pending-owner", clock.epochMs(),
            5_000, part.toString())))
    val fence = claimed.value.fence
    assertIs<PhysicalDownloadCommit.Committed<*>>(owner.repository.selectPhysicalDownloadRepresentation(
        SelectPhysicalDownloadRepresentation(fence, PHYSICAL_REPRESENTATION.representationId, 7, null, null, clock.epochMs())))
    assertIs<PhysicalDownloadCommit.Committed<*>>(owner.repository.heartbeatPhysicalDownload(HeartbeatPhysicalDownload(
        fence, clock.epochMs(), 5_000, PHYSICAL_BYTES.size.toLong(), PHYSICAL_BYTES.size.toLong())))
    assertIs<PhysicalDownloadCommit.Committed<*>>(owner.repository.beginPhysicalDownloadValidation(
        BeginPhysicalDownloadValidation(fence, clock.epochMs())))
    val intent = PhysicalDownloadFinalization(id, before.job.producer, part.toString(),
        files.finalPath(before.job.producer.subjectId.value, 7, "audio/mpeg", attemptId).toString(),
        PHYSICAL_BYTES.toByteString().sha256().hex(), PHYSICAL_BYTES.size.toLong(), attemptId, 30_000,
        "audio/mpeg", "mp3", PHYSICAL_REPRESENTATION.representationId, 7, fence.ownershipGeneration,
        MaterializationAssetId(newEntityId("authored-resolver-pending-asset")))
    assertIs<PhysicalDownloadCommit.Committed<*>>(owner.repository.preparePhysicalDownloadFinalization(
        PreparePhysicalDownloadFinalization(fence, intent, clock.epochMs())))
    val asset = MaterializationAsset(assertNotNull(intent.assetId), intent.producer, intent.finalPath,
        intent.sizeBytes, mimeType = intent.mimeType, codecs = intent.codecs, sha256 = intent.sha256,
        representationId = intent.representationId, validatorGeneration = intent.validatorGeneration,
        completedEpochMs = clock.epochMs(), measuredDurationMs = intent.mediaDurationMs,
        materializerDownloadId = id, materializerAttemptId = attemptId, ownershipGeneration = fence.ownershipGeneration)
    assertNull(owner.materializationEvidence.checks.value.successfulChecks[id])
    assertNull(owner.store.acquisitionView().acquisition.materializations[asset.id])
    return OriginUsePublicationSeed(fence, intent, asset)
}
