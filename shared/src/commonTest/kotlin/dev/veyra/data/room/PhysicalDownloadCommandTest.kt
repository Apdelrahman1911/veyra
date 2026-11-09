package dev.veyra.data.room

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import kotlin.test.*

/**
 * Pure authored relational transitions ONLY. Paths, hash strings, validation and drain callbacks
 * are test inputs, not real files, hashes, native validation, drain evidence or Room commits.
 */
class PhysicalDownloadCommandTest {
    @Test fun newPhysicalPathNeverInheritsPartialCountersOrValidatorsFromAnEarlierAttempt() {
        val setup = typed()
        val fixture = setup.fixture
        val first = claim(fixture, setup.admission.physical.job, now = 124_000)
        fixture.project { it.applySelectRepresentation(SelectPhysicalDownloadRepresentation(first.fence, "authored-key", 4, "\"old-validator\"", null, 124_010)) }
        fixture.project { it.applyPhysicalHeartbeat(HeartbeatPhysicalDownload(first.fence, 124_020, 1_000, 8, 16)) }
        fixture.project { it.applyFinishAttempt(FinishPhysicalDownloadAttempt(first.fence, DownloadStatus.FAILED,
            DownloadAttemptStatus.FAILED, AppFailure(FailureCode.NETWORK_UNAVAILABLE), null, 0, 124_030)) }
        val blocked = fixture.graph.physicalJoin(first.fence.downloadId)!!
        fixture.project { it.applyStandaloneIntent(intent(blocked, DownloadBatchIntent.ACTIVE, 124_040, retry = true)) }
        val resumed = fixture.graph.physicalJoin(first.fence.downloadId)!!
        val second = claim(fixture, resumed.job, "attempt-2", "/authored/second.part", 124_050)
        assertEquals(0L, second.physical.job.bytesDownloaded)
        assertNull(second.physical.job.totalBytes)
        assertNull(second.physical.job.etag)
        assertNull(second.physical.job.lastModified)
        assertEquals("authored-key", second.physical.job.representationId)
        assertEquals(4L, second.physical.job.validatorGeneration)
        val retired = second.physical.attempts.single { it.id == first.fence.attemptId }
        assertEquals(8L, retired.bytesDownloaded)
        assertEquals("\"old-validator\"", retired.etag)
        assertNotNull(retired.finishedEpochMs)
        assertFailsWith<IllegalArgumentException> {
            fixture.project { it.applySelectRepresentation(SelectPhysicalDownloadRepresentation(second.fence, "different-key", 5, null, null, 124_060)) }; Unit
        }
    }

    @Test fun completeSameSubjectAndCReuseKeepsActualR1WhileR2HasItsOwnClaimAndAssetUse() {
        val setup = completed()
        val fixture = setup.fixture
        val actual = fixture.graph.physicalJoin(setup.admission.physical.job.id)!!
        val requested = authoredRequest("request-R2", MediaCapture(authoredMedia(title = "Second captured catalogue title A2")))
        fixture.accept(requested)
        val batch = fixture.catalogBatch(requested, "batch-R2")
        assertTrue(fixture.project { it.applyAttachOccurrence(AttachAcquisitionOccurrence(OccurrenceAttachmentExpectation(
            batch.members.single().occurrenceId, requested.id, null, null, batch.id, batch.intentRevision))) })
        val expected = batchExpectation(fixture.graph.state().batches.getValue(batch.id))
        val admission = fixture.project { it.applyAdmitBatchPhysical(AdmitBatchPhysicalDownload(expected, "must-not-create-another-job",
            DownloadClaimId("claim-R2"), 124_100)) }.projectedValue()
        assertEquals(expected, admission.member) // A null prior claim ID is echoed unchanged.
        assertNull(admission.member.previouslyAttachedClaimId)
        assertEquals(actual.job.producer, admission.physical.job.producer)
        assertEquals(actual.asset, admission.physical.asset)
        assertEquals(2, admission.physical.claims.size)
        assertNotEquals(admission.requestedContext.binding.id, admission.physical.job.producer.bindingId)
        assertEquals(admission.requestedContext.binding.id, admission.claim.requestedBindingId)
        assertEquals(DownloadBatchMemberState.VERIFYING, fixture.graph.state().batches.getValue(batch.id).members.single().state)
        val use = fixture.project { it.applyMaterializationUse(CommitMaterializationUse(
            batchExpectation(it.state().batches.getValue(batch.id)), admission.claim.id, admission.claim.generation,
            actual.job.ownershipGeneration, actual.asset!!, 1, 124_110)) }.projectedValue()
        assertEquals(admission.requestedContext.binding.id, use.requestedBindingId)
        assertEquals(actual.asset!!.id, use.materializationAssetId)
        assertEquals(actual.asset, fixture.graph.physicalJoin(actual.job.id)!!.asset)
        assertEquals(DownloadBatchMemberState.AVAILABLE, fixture.graph.state().batches.getValue(batch.id).members.single().state)
        assertEquals(1, fixture.graph.state().physicalDownloads.size)
    }

    @Test fun completedDifferentCForSameSubjectFailsRatherThanReusingOrRelabellingTheAsset() {
        val setup = completed()
        val fixture = setup.fixture
        val second = authoredRequest("different-C")
        val binding = (fixture.accept(second, authoredSingle(second, "ABCDEFGHIJK")).outcome as AcquisitionOutcome.Single).bindingId
        val before = fixture.graph
        val failure = assertFailsWith<VeyraException> {
            fixture.project { it.applyAdmitStandalone(AdmitStandalonePhysicalDownload(second.id, binding, "second-job", DownloadClaimId("second-claim"), 124_100)) }; Unit
        }
        assertEquals("download_has_different_recording", failure.failure.detail)
        assertSame(before, fixture.graph)
        assertEquals(1, fixture.graph.state().materializations.size)
    }

    @Test fun partialClaimJoinCannotAuthorizeReconciliationOfSharedPhysicalWork() {
        val setup = typed()
        val fixture = setup.fixture
        val requested = authoredRequest("R2")
        fixture.accept(requested)
        val batch = fixture.catalogBatch(requested, "shared")
        fixture.project { it.applyAttachOccurrence(AttachAcquisitionOccurrence(OccurrenceAttachmentExpectation(batch.members.single().occurrenceId,
            requested.id, null, null, batch.id, 0))) }
        fixture.project { it.applyAdmitBatchPhysical(AdmitBatchPhysicalDownload(batchExpectation(it.state().batches.getValue(batch.id)),
            "unused-id", DownloadClaimId("batch-claim"), 123_100)) }
        val join = fixture.graph.physicalJoin(setup.admission.physical.job.id)!!
        assertEquals(2, join.claims.size)
        val before = fixture.graph
        assertEquals(PhysicalDownloadCommit.Stale, fixture.project { it.applyReconcileIntent(ReconcilePhysicalDownloadIntent(join.job.id,
            join.job.producer, join.job.ownershipGeneration, join.claims.take(1), 123_110)) })
        assertSame(before, fixture.graph)
        val pausedStandalone = fixture.project { it.applyStandaloneIntent(intent(join, DownloadBatchIntent.PAUSED, 123_120)) }.projectedValue()
        assertFalse(pausedStandalone.job.userPaused)
        assertEquals(DownloadStatus.QUEUED, pausedStandalone.job.status)
        assertEquals(setOf(DownloadBatchIntent.PAUSED, DownloadBatchIntent.ACTIVE), pausedStandalone.claims.map { it.intent }.toSet())
    }

    @Test fun expiredLeaseIsLogicalRecoveryNotASecondTransferAdmissionOrImplicitFailureRetry() {
        val setup = typed()
        val fixture = setup.fixture
        val claimed = claim(fixture, setup.admission.physical.job)
        assertEquals(PhysicalDownloadCommit.Stale, fixture.project { it.applyClaimAttempt(ClaimPhysicalDownloadAttempt(claimed.physical.job.id,
            claimed.physical.job.producer, claimed.physical.job.ownershipGeneration, "premature", "owner-2", 126_000, 1_000, "/authored/new.part")) })
        val joined = fixture.graph.physicalJoin(claimed.physical.job.id)!!
        val recovered = fixture.project { it.applyReconcileIntent(ReconcilePhysicalDownloadIntent(joined.job.id, joined.job.producer,
            joined.job.ownershipGeneration, joined.claims, 126_010)) }.projectedValue()
        assertNull(recovered.job.leaseOwner)
        assertNull(recovered.job.activeAttemptId)
        assertEquals(DownloadAttemptStatus.PAUSED, recovered.attempts.single().status)
        assertNotNull(recovered.attempts.single().finishedEpochMs)
        assertEquals(DownloadStatus.QUEUED, recovered.job.status)
        assertNull(recovered.cleanupReservation)
        val second = claim(fixture, recovered.job, "later-attempt", "/authored/later.part", 126_020)
        fixture.project { it.applyFinishAttempt(FinishPhysicalDownloadAttempt(second.fence, DownloadStatus.FAILED,
            DownloadAttemptStatus.FAILED, AppFailure(FailureCode.CORRUPT_MEDIA), null, 0, 126_030)) }
        val failed = fixture.graph.physicalJoin(joined.job.id)!!
        val reconciled = fixture.project { it.applyReconcileIntent(ReconcilePhysicalDownloadIntent(failed.job.id, failed.job.producer,
            failed.job.ownershipGeneration, failed.claims, 126_040, allowHostResume = true)) }.projectedValue()
        assertEquals(DownloadStatus.FAILED, reconciled.job.status)
        assertEquals(FailureCode.CORRUPT_MEDIA, reconciled.job.failure?.code)
    }

    @Test fun publicationRequiresExactMaterializerKeyHashAndAttemptAndConsumesTheLeaseOnce() {
        val setup = prepared()
        val fixture = setup.fixture
        val join = fixture.graph.physicalJoin(setup.admission.physical.job.id)!!
        val intent = join.finalization!!
        val fence = physicalFence(join.job, join.activeAttempt)
        val asset = asset(intent, 124_050)
        for (wrong in listOf(asset.copy(representationId = "another-key"), asset.copy(sha256 = "b".repeat(64)),
            asset.copy(materializerAttemptId = "another-attempt"), asset.copy(ownershipGeneration = asset.ownershipGeneration + 1))) {
            assertFailsWith<IllegalArgumentException> {
                fixture.project { it.applyPublishFinalization(PublishPhysicalDownloadFinalization(fence, intent, wrong, 124_050)) }; Unit
            }
        }
        val published = fixture.project { it.applyPublishFinalization(PublishPhysicalDownloadFinalization(fence, intent, asset, 124_050)) }.projectedValue()
        assertEquals(DownloadStatus.COMPLETE, published.job.status)
        assertNull(published.job.leaseOwner)
        assertNull(published.job.activeAttemptId)
        assertNull(published.job.maintenanceKind)
        assertNull(published.finalization)
        assertEquals(DownloadAttemptStatus.SUCCEEDED, published.attempts.single().status)
        assertEquals(asset, published.asset)
        assertEquals(asset.receipt(), fixture.graph.state().materializationReceipts[asset.id])
        assertEquals(PhysicalDownloadCommit.Stale, fixture.project { it.applyPublishFinalization(PublishPhysicalDownloadFinalization(fence, intent, asset, 124_051)) })
    }

    @Test fun recoveredPublicationKeepsOriginalAttemptGenerationAndDoesNotRewriteRetiredHistory() {
        val setup = prepared()
        val fixture = setup.fixture
        val before = fixture.graph.physicalJoin(setup.admission.physical.job.id)!!
        val recovered = fixture.project { it.applyClaimMaintenance(ClaimPhysicalDownloadMaintenance(before.job.id, before.job.producer,
            before.job.ownershipGeneration, PhysicalDownloadMaintenanceKind.RECOVER_FINALIZATION, "recovery-owner", 126_000, 1_000)) }.projectedValue()
        assertEquals(before.job.ownershipGeneration + 1, recovered.fence.ownershipGeneration)
        val retired = recovered.physical.attempts.single()
        assertEquals(DownloadAttemptStatus.PAUSED, retired.status)
        assertNotNull(retired.finishedEpochMs)
        val intent = recovered.physical.finalization!!
        val recoveredAsset = asset(intent, 126_010)
        val published = fixture.project { it.applyPublishFinalization(PublishPhysicalDownloadFinalization(recovered.fence, intent, recoveredAsset, 126_010)) }.projectedValue()
        assertEquals(retired, published.attempts.single())
        assertEquals(before.job.ownershipGeneration, published.asset!!.ownershipGeneration)
        assertNotEquals(published.job.ownershipGeneration, published.asset!!.ownershipGeneration)
        assertNull(published.job.leaseOwner)
        assertNull(published.job.maintenanceKind)
        assertEquals(PhysicalDownloadCommit.Stale, fixture.project { it.applyFinishMaintenance(FinishPhysicalDownloadMaintenance(recovered.fence,
            PhysicalDownloadMaintenanceKind.RECOVER_FINALIZATION, published.claims, false, null, 126_020)) })
    }

    @Test fun failedCleanupRetainsReservationAndCompletePathJoinUntilPositiveFencedFinish() {
        val setup = completed()
        val fixture = setup.fixture
        var join = fixture.graph.physicalJoin(setup.admission.physical.job.id)!!
        val originalAsset = join.asset!!
        val originalAttempt = join.attempts.single()
        fixture.project { it.applyStandaloneUse(CommitStandaloneMaterializationUse(join.job.id, setup.admission.requestedContext.binding.id,
            join.claims.single().id, join.claims.single().generation, join.job.ownershipGeneration, originalAsset, 1, 124_060)) }
        join = fixture.graph.physicalJoin(join.job.id)!!
        fixture.project { it.applyStandaloneIntent(intent(join, DownloadBatchIntent.CANCELLED, 124_070, retain = false)) }
        val cancelled = fixture.graph.physicalJoin(join.job.id)!!
        val cleanup = fixture.project { it.applyClaimMaintenance(ClaimPhysicalDownloadMaintenance(cancelled.job.id, cancelled.job.producer,
            cancelled.job.ownershipGeneration, PhysicalDownloadMaintenanceKind.CLEANUP, "cleanup-owner", 124_080, 1_000)) }.projectedValue()
        val reservation = cleanup.physical.cleanupReservation!!
        assertEquals(setOf(originalAttempt.stagingPath), reservation.stagingPaths)
        assertEquals(originalAsset, reservation.asset)
        assertTrue(reservation.removeCompletedAsset)
        val failed = fixture.project { it.applyFinishMaintenance(FinishPhysicalDownloadMaintenance(cleanup.fence,
            PhysicalDownloadMaintenanceKind.CLEANUP, cleanup.physical.claims, false, AppFailure(FailureCode.STORAGE_IO), 124_090)) }.projectedValue()
        assertTrue(failed.job.cleanupPending)
        assertEquals(PhysicalDownloadMaintenanceKind.CLEANUP, failed.job.maintenanceKind)
        assertNull(failed.job.leaseOwner)
        assertEquals(originalAsset, failed.asset)
        assertEquals(PhysicalDownloadCommit.Stale, fixture.project { it.applyStandaloneIntent(intent(failed, DownloadBatchIntent.ACTIVE, 124_100)) })
        assertFailsWith<VeyraException> { fixture.project { it.applyAdmitStandalone(AdmitStandalonePhysicalDownload(setup.admission.requestedContext.request.id,
            setup.admission.requestedContext.binding.id, "blocked-new-job", DownloadClaimId("blocked-new-claim"), 126_000)) }; Unit }
        val second = fixture.project { it.applyClaimMaintenance(ClaimPhysicalDownloadMaintenance(failed.job.id, failed.job.producer,
            failed.job.ownershipGeneration, PhysicalDownloadMaintenanceKind.CLEANUP, "cleanup-owner-2", 126_010, 1_000)) }.projectedValue()
        val removed = fixture.project { it.applyFinishMaintenance(FinishPhysicalDownloadMaintenance(second.fence,
            PhysicalDownloadMaintenanceKind.CLEANUP, second.physical.claims, true, null, 126_020)) }.projectedValue()
        assertNull(removed.asset)
        assertFalse(removed.job.cleanupPending)
        assertNull(removed.job.maintenanceKind)
        assertEquals(DownloadStatus.CANCELLED, removed.job.status)
        assertEquals(originalAttempt, removed.attempts.single())
        assertEquals(originalAsset.receipt(), fixture.graph.state().materializationReceipts[originalAsset.id])
        assertEquals(1, fixture.graph.state().assetUses.size)
        assertEquals(1, fixture.graph.state().physicalDownloads.size) // Tombstone, not history deletion.
    }

    @Test fun maintenanceHeartbeatRenewsOnlyItsLeaseAndCannotFabricateMoreTransferredBytes() {
        val setup = completed()
        val fixture = setup.fixture
        val join = fixture.graph.physicalJoin(setup.admission.physical.job.id)!!
        val validation = fixture.project { it.applyClaimMaintenance(ClaimPhysicalDownloadMaintenance(join.job.id, join.job.producer,
            join.job.ownershipGeneration, PhysicalDownloadMaintenanceKind.VALIDATE, "validator", 124_100, 1_000)) }.projectedValue()
        assertFailsWith<IllegalArgumentException> { fixture.project { it.applyPhysicalHeartbeat(HeartbeatPhysicalDownload(validation.fence,
            124_110, 1_000, join.job.bytesDownloaded + 1, join.job.totalBytes!! + 1)) }; Unit }
        val refreshed = fixture.project { it.applyPhysicalHeartbeat(HeartbeatPhysicalDownload(validation.fence,
            124_110, 1_000, join.job.bytesDownloaded, join.job.totalBytes)) }.projectedValue()
        assertEquals(join.asset, refreshed.asset)
        assertEquals(125_110L, refreshed.job.leaseExpiresEpochMs)
        assertEquals(join.attempts, refreshed.attempts)
    }

    @Test fun unknownSchema2AdoptionRequiresLiveSingleUseDrainAndRetainsOriginalUnknownHistory() {
        val media = authoredMedia()
        val oldJob = PhysicalDownloadJob("old-job", PhysicalDownloadProducer.Schema2(media.id, null, null), DownloadStatus.CANCELLED,
            attemptCount = 1, createdEpochMs = 123_000, updatedEpochMs = 123_000)
        val oldClaim = DownloadClaim(DownloadClaimId("old-claim"), DownloadClaimOwner.Schema2Standalone(oldJob.id), oldJob.id,
            null, DownloadBatchIntent.CANCELLED, false, 123_000)
        val oldAttempt = PhysicalDownloadAttempt("old-attempt", oldJob.id, oldJob.producer, 1, 0,
            status = DownloadAttemptStatus.CANCELLED, startedEpochMs = 122_000, finishedEpochMs = 122_100, stagingPath = "/authored/retired.part")
        val fixture = AcquisitionCommandFixture(commandGraph(AppSnapshot(media = mapOf(media.id to media))).withJob(oldJob).withClaim(oldClaim).withAttempt(oldAttempt))
        val request = authoredRequest("fresh-explicit", MediaCapture(media))
        val binding = (fixture.accept(request).outcome as AcquisitionOutcome.Single).bindingId
        val owner = LibraryTransactionOwner()
        var barrier = false
        val proof = PhysicalDownloadDrainReceipt(owner, oldJob.id, oldJob.producer, 0, listOf(oldClaim)) { barrier }
        val command = AdoptSchema2PhysicalDownload(oldJob.id, request.id, binding, oldJob.producer as PhysicalDownloadProducer.Schema2,
            0, oldClaim.id, 0, 0, proof, 123_100)
        assertFailsWith<IllegalArgumentException> { fixture.project { it.applyAdoptSchema2(command, owner) }; Unit }
        barrier = true
        assertFailsWith<IllegalArgumentException> { fixture.project { it.applyAdoptSchema2(command, LibraryTransactionOwner()) }; Unit }
        val adopted = fixture.project { it.applyAdoptSchema2(command, owner) }.projectedValue()
        assertIs<PhysicalDownloadProducer.Typed>(adopted.physical.job.producer)
        assertEquals(oldAttempt, adopted.physical.attempts.single())
        assertEquals(oldClaim.owner, adopted.claim.owner)
        assertNull(adopted.claim.requestedBindingId)
        assertEquals(DownloadBatchIntent.ACTIVE, adopted.claim.intent)
        assertEquals(1L, adopted.claim.generation)
        assertEquals(1L, adopted.physical.job.ownershipGeneration)
        assertEquals(0L, adopted.physical.job.bytesDownloaded)
        assertNull(adopted.physical.job.representationId)
        assertEquals(1, fixture.graph.rows.physicalDownloadAdoptionRows.size)
        assertFailsWith<IllegalArgumentException> { proof.consume(owner, oldJob, listOf(oldClaim)) }
        proof.invalidate()
    }

    private data class Setup(val fixture: AcquisitionCommandFixture, val admission: StandalonePhysicalDownloadAdmission)

    private fun typed(): Setup {
        val fixture = AcquisitionCommandFixture()
        val request = authoredRequest("producer-R1")
        val binding = (fixture.accept(request).outcome as AcquisitionOutcome.Single).bindingId
        val admission = fixture.project { it.applyAdmitStandalone(AdmitStandalonePhysicalDownload(request.id, binding,
            "physical-job", DownloadClaimId("standalone-claim"), 123_100)) }.projectedValue()
        return Setup(fixture, admission)
    }

    private fun prepared(): Setup {
        val setup = typed()
        val fixture = setup.fixture
        val claimed = claim(fixture, setup.admission.physical.job)
        fixture.project { it.applySelectRepresentation(SelectPhysicalDownloadRepresentation(claimed.fence, "authored-key", 4, "\"authored-validator\"", null, 124_010)) }
        fixture.project { it.applyPhysicalHeartbeat(HeartbeatPhysicalDownload(claimed.fence, 124_020, 1_000, 16, 16)) }
        fixture.project { it.applyBeginValidation(BeginPhysicalDownloadValidation(claimed.fence, 124_030)) }
        val intent = PhysicalDownloadFinalization(claimed.physical.job.id, claimed.physical.job.producer,
            claimed.physical.job.stagingPath!!, "/authored/final.webm", "a".repeat(64), 16, claimed.fence.attemptId,
            4_000, "audio/webm", "opus", "authored-key", 4, claimed.fence.ownershipGeneration, MaterializationAssetId("asset-R1"))
        fixture.project { it.applyPrepareFinalization(PreparePhysicalDownloadFinalization(claimed.fence, intent, 124_040)) }
        return setup
    }

    private fun completed(): Setup = prepared().also { setup ->
        val join = setup.fixture.graph.physicalJoin(setup.admission.physical.job.id)!!
        val intent = join.finalization!!
        setup.fixture.project { it.applyPublishFinalization(PublishPhysicalDownloadFinalization(physicalFence(join.job, join.activeAttempt),
            intent, asset(intent, 124_050), 124_050)) }; Unit
    }

    private fun claim(fixture: AcquisitionCommandFixture, job: PhysicalDownloadJob, id: String = "attempt-1",
        path: String = "/authored/first.part", now: Long = 124_000): ClaimedPhysicalDownload =
        fixture.project { it.applyClaimAttempt(ClaimPhysicalDownloadAttempt(job.id, job.producer, job.ownershipGeneration,
            id, "transfer-owner", now, 1_000, path)) }.projectedValue()

    private fun asset(intent: PhysicalDownloadFinalization, now: Long) = MaterializationAsset(intent.assetId!!, intent.producer,
        intent.finalPath, intent.sizeBytes, mimeType = intent.mimeType, codecs = intent.codecs, sha256 = intent.sha256,
        representationId = intent.representationId, validatorGeneration = intent.validatorGeneration, completedEpochMs = now,
        measuredDurationMs = intent.mediaDurationMs, materializerDownloadId = intent.downloadId, materializerAttemptId = intent.attemptId,
        ownershipGeneration = intent.ownershipGeneration)

    private fun intent(join: PhysicalDownloadJoin, intent: DownloadBatchIntent, now: Long, retain: Boolean = true, retry: Boolean = false): UpdateStandaloneDownloadIntent {
        val claim = join.claims.single { it.owner !is DownloadClaimOwner.BatchMember }
        return UpdateStandaloneDownloadIntent(join.job.id, join.job.producer, join.job.ownershipGeneration, claim.id, claim.generation,
            claim.ownerIntentRevision, intent, retain, now, retry)
    }
}
