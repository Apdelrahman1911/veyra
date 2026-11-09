package dev.veyra.acquisition

import dev.veyra.data.AttachBatchDownloadClaim
import dev.veyra.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Authored fake executor/repository contract tests ONLY. They create no files, acquire no native
 * owners and establish neither atomic Room job admission nor actual transfer/validation/ACK.
 */
class AcquisitionDownloadAdapterTest {
    private class Rig {
        val repository = AcquisitionTestRepository()
        val gateway = AcquisitionTestGateway()
        val clock = AcquisitionTestClock()
        var offline = false
        val acquisition = AcquisitionCoordinator(repository, gateway, clock = clock, offline = { offline })
        val factory = AcquisitionAdmissionFactory(clock = clock)
        val batches = DownloadBatchCoordinator(repository, acquisition, clock)
        val executor = FakeExecutor(repository)
        val adapter = AcquisitionDownloadAdapter(repository, acquisition, executor)

        fun prepared(count: Int = 2) = factory.collection(authoredSnapshot(count = count),
            ObservedCollectionChoice.WholeObservedListing, PlaybackControlsMode.PODCAST)

        suspend fun resolved(prepared: PreparedAcquisitionAdmission = this.prepared()): DownloadBatch {
            val batch = batches.admit(prepared)
            for (member in batch.members) batches.resolveMember(batch.id, member.position, batch.intentRevision)
            return repository.state.value.batches.getValue(batch.id)
        }

        suspend fun legacyResolved(change: (FirstResultReceipt.Schema2) -> FirstResultReceipt.Schema2 = { it }):
            Pair<DownloadBatch, FirstResultReceipt.Schema2> {
            val request = authoredRequest("requested-r2", MediaCapture(authoredMedia("catalogue-subject", "Current complete title")))
            val batch = resolved(factory.catalogLeaves(listOf(request), PlaybackControlsMode.MUSIC, null))
            val state = repository.state.value
            val requested = state.bindings.getValue(checkNotNull(batch.members.single().requiredBindingId))
            val recording = state.recordings.getValue(checkNotNull(requested.recordingId))
            val receipt = change(FirstResultReceipt.Schema2(FirstResultReceiptId("legacy-receipt"), requested.subjectId.value,
                "Original complete requested title", FirstResultKind.VIDEO, recording.canonicalId,
                "Original complete first recording title", null, recording.canonicalId, 100_000, 0))
            val binding = ExecutionBinding(ExecutionBindingId("legacy-binding"), requested.subjectId, receipt.id,
                recording.id, ExecutionBindingOrigin.SCHEMA2_EXECUTABLE)
            repository.state.value = state.copy(receipts = state.receipts + (receipt.id to receipt), bindings = state.bindings + (binding.id to binding))
            executor.jobBySubject[requested.subjectId] = "legacy-job"
            executor.producerByJob["legacy-job"] = binding.id
            return batch to receipt
        }
    }

    /** A deliberately permissive fake lets tests challenge an executor's malformed response. */
    private class FakeExecutor(val repository: AcquisitionTestRepository) : AcquisitionDownloadExecutor {
        override val transactionOwner get() = repository.transactionOwner
        val admissions = mutableListOf<AdmitAcquisitionDownload>()
        val reconciliations = mutableListOf<ReconcileAcquisitionDownload>()
        val assetChecks = mutableListOf<ValidateAcquisitionDownloadAsset>()
        val producerByJob = mutableMapOf<String, ExecutionBindingId>()
        val jobBySubject = mutableMapOf<ExecutableSubjectId, String>()
        var admission: (suspend (AdmitAcquisitionDownload) -> AcquisitionDownloadAdmission)? = null
        var reconciliation: (suspend (ReconcileAcquisitionDownload) -> AcquisitionDownloadReconciliation)? = null
        var assetCheck: (suspend (ValidateAcquisitionDownloadAsset) -> AcquisitionDownloadAssetCheck)? = null
        var nextClaim = 0

        override suspend fun admitMember(command: AdmitAcquisitionDownload): AcquisitionDownloadAdmission {
            admissions += command
            return admission?.invoke(command) ?: persist(command)
        }

        suspend fun persist(command: AdmitAcquisitionDownload): AcquisitionDownloadAdmission {
            val batch = repository.state.value.batches.getValue(command.member.batchId)
            val member = batch.members.single { it.position == command.member.memberPosition }
            val job = jobBySubject.getOrPut(command.acquisition.subject.id) { "authored-job-${jobBySubject.size}" }
            if (job !in producerByJob) producerByJob[job] = command.acquisition.binding.id
            val claim = member.claimId?.let { repository.state.value.claims.getValue(it) } ?: DownloadClaim(
                DownloadClaimId("authored-claim-${nextClaim++}"), DownloadClaimOwner.BatchMember(batch.id, member.position),
                job, command.acquisition.binding.id, batch.intent, true, 123_000,
                ownerIntentRevision = batch.intentRevision,
            )
            check(command.previouslyAttachedClaimId == null || command.previouslyAttachedClaimId == claim.id)
            repository.attachBatchDownloadClaim(AttachBatchDownloadClaim(batch.id, member.position, command.member.intentRevision,
                command.member.requestedBindingId, claim))
            val state = repository.state.value
            val physical = state.physicalDownloads[job] ?: PhysicalDownloadJob(job, producer(job),
                createdEpochMs = 123_000, updatedEpochMs = 123_000)
            repository.state.value = state.copy(libraryRevision = state.libraryRevision + 1,
                physicalDownloads = state.physicalDownloads + (job to physical))
            return AcquisitionDownloadAdmission(fence(command.member, claim), repository.state.value.libraryRevision)
        }

        fun fence(key: AcquisitionDownloadMemberKey, claim: DownloadClaim): AcquisitionDownloadFence {
            val producer = repository.state.value.bindings.getValue(producerByJob.getValue(claim.physicalJobId))
            val attempt = repository.state.value.physicalDownloads[claim.physicalJobId]?.activeAttemptId
                ?.let { repository.state.value.physicalAttempts[it] }
            return AcquisitionDownloadFence(this, key, claim,
                AcquisitionDownloadJobIdentity(claim.physicalJobId, producer.subjectId, producer.id, checkNotNull(producer.recordingId)),
                AcquisitionDownloadGeneration("authored-executor-process", "authored-ownership-epoch", attempt?.id, attempt?.number))
        }

        private fun producer(jobId: String): PhysicalDownloadProducer {
            val binding = repository.state.value.bindings.getValue(producerByJob.getValue(jobId))
            return when (binding.origin) {
                ExecutionBindingOrigin.TYPED -> PhysicalDownloadProducer.Typed(binding.subjectId, binding.id, checkNotNull(binding.recordingId))
                ExecutionBindingOrigin.SCHEMA2_EXECUTABLE -> PhysicalDownloadProducer.Schema2(binding.subjectId.value, binding.id, binding.recordingId)
                ExecutionBindingOrigin.SCHEMA2_NON_EXECUTABLE -> error("Authored producer must have a known C")
            }
        }

        /** Inert metadata ONLY: no file, actual lease, transfer, validator or drain runs here. */
        fun attempt(jobId: String): PhysicalDownloadAttempt {
            val state = repository.state.value
            val job = state.physicalDownloads.getValue(jobId)
            val attempt = PhysicalDownloadAttempt("$jobId-authored-attempt-${job.attemptCount + 1}", jobId, job.producer,
                job.attemptCount + 1, job.ownershipGeneration + 1, startedEpochMs = 123_000,
                representationId = "authored-representation", validatorGeneration = 1)
            repository.state.value = state.copy(physicalAttempts = state.physicalAttempts + (attempt.id to attempt),
                physicalDownloads = state.physicalDownloads + (job.id to job.copy(status = DownloadStatus.RUNNING,
                    activeAttemptId = attempt.id, attemptCount = attempt.number, ownershipGeneration = attempt.ownershipGeneration)))
            return attempt
        }

        /** A structurally coherent fake materialization does not establish actual local Ready. */
        fun materialization(jobId: String, assetId: MaterializationAssetId): MaterializationAsset {
            repository.state.value.materializations[assetId]?.let { return it }
            val attempt = attempt(jobId).copy(status = DownloadAttemptStatus.SUCCEEDED, finishedEpochMs = 123_000,
                bytesDownloaded = 128, totalBytes = 128)
            val state = repository.state.value
            val job = state.physicalDownloads.getValue(jobId)
            val asset = MaterializationAsset(assetId, job.producer, "/authored-test-only/${assetId.value}.webm", 128,
                mimeType = "audio/webm", codecs = "opus", sha256 = "a".repeat(64), representationId = attempt.representationId,
                validatorGeneration = attempt.validatorGeneration, completedEpochMs = 123_000,
                materializerDownloadId = jobId, materializerAttemptId = attempt.id, ownershipGeneration = attempt.ownershipGeneration)
            repository.state.value = state.copy(physicalAttempts = state.physicalAttempts + (attempt.id to attempt),
                physicalDownloads = state.physicalDownloads + (jobId to job.copy(status = DownloadStatus.COMPLETE,
                    bytesDownloaded = 128, totalBytes = 128, activeAttemptId = null, assetId = assetId)),
                materializations = state.materializations + (assetId to asset),
                materializationReceipts = state.materializationReceipts + (assetId to asset.receipt()))
            return asset
        }

        override suspend fun reconcileClaim(command: ReconcileAcquisitionDownload): AcquisitionDownloadReconciliation {
            reconciliations += command
            return reconciliation?.invoke(command) ?: AcquisitionDownloadReconciliation(fence(command.member, command.expectedClaim),
                AcquisitionDownloadDurableSettlement.Confirmed(repository.state.value.libraryRevision),
                AcquisitionDownloadPhysicalSettlement.NotRequested)
        }

        override suspend fun validateAssetUse(command: ValidateAcquisitionDownloadAsset): AcquisitionDownloadAssetCheck {
            assetChecks += command
            return assetCheck?.invoke(command) ?: AcquisitionDownloadAssetCheck.Unavailable(
                AppFailure(FailureCode.RECOVERY_REQUIRED, "authored_current_process_validation_missing"))
        }

        fun validated(command: ValidateAcquisitionDownloadAsset, persistUse: Boolean = true,
            asset: MaterializationAssetId = command.materializationAssetId): AcquisitionDownloadAssetCheck.Validated {
            materialization(command.expectedClaim.physicalJobId, asset)
            val use = AssetUse(command.member.requestedBindingId, asset, 1, 123_000)
            if (persistUse) repository.state.value = repository.state.value.copy(libraryRevision = repository.state.value.libraryRevision + 1,
                assetUses = repository.state.value.assetUses + use)
            return AcquisitionDownloadAssetCheck.Validated(AcquisitionDownloadValidatedUse(
                fence(command.member, command.expectedClaim), use, repository.state.value.libraryRevision))
        }
    }

    @Test fun anotherRoomOwnerIsRejectedBeforeSourceOrExecutorOperations() {
        val rig = Rig()
        val otherRepository = AcquisitionTestRepository()
        val foreignExecutor = FakeExecutor(otherRepository)
        val originalState = rig.repository.state.value
        val foreignState = otherRepository.state.value
        assertEquals(originalState, foreignState, "Equal projected values do not establish a shared transaction owner")
        assertNotSame(rig.repository.transactionOwner, foreignExecutor.transactionOwner)

        assertEquals("Acquisition adapter and download executor must share one Room owner",
            assertFailsWith<IllegalArgumentException> {
                AcquisitionDownloadAdapter(rig.repository, rig.acquisition, foreignExecutor)
            }.message)

        assertSame(originalState, rig.repository.state.value)
        assertSame(foreignState, otherRepository.state.value)
        assertTrue(foreignExecutor.admissions.isEmpty())
        assertTrue(foreignExecutor.reconciliations.isEmpty())
        assertTrue(foreignExecutor.assetChecks.isEmpty())
        assertTrue(rig.gateway.acquisitions.isEmpty())
        assertTrue(rig.gateway.inspections.isEmpty())
        assertTrue(rig.gateway.collections.isEmpty())
        assertTrue(rig.gateway.continuations.isEmpty())
        assertEquals(0, rig.gateway.restores)
    }

    @Test fun neutralMemberAdmissionUsesItsExactBoundSubjectAndNeverFabricatesMusicOrEpisodeMetadata() = runTest {
        val rig = Rig()
        val batch = rig.resolved(rig.prepared(1))
        val response = rig.adapter.admitMember(batch.id, 0, batch.intentRevision)
        val command = rig.executor.admissions.single()
        assertIs<CollectionMemberCapture>(command.acquisition.request.metadata)
        assertIs<ExecutableSubject.SourceRecordingSubject>(command.acquisition.subject)
        assertEquals(PlaybackControlsMode.PODCAST, command.acquisition.occurrence.controlsMode)
        assertEquals(command.member.requestedBindingId, command.acquisition.binding.id)
        assertEquals(response.fence.claim.id, rig.repository.state.value.batches.getValue(batch.id).members.single().claimId)
        assertEquals(DownloadBatchMemberState.WAITING_FOR_JOB, rig.repository.state.value.batches.getValue(batch.id).members.single().state)
        assertEquals(1, rig.gateway.acquisitions.size)
        assertTrue(rig.gateway.inspections.isEmpty())
        assertTrue(rig.repository.state.value.assetUses.isEmpty())
        assertTrue(rig.executor.assetChecks.isEmpty())
    }

    @Test fun sameRecordingRequestsKeepSeparateReceiptsAndClaimsEvenWhenTheFakeExecutorReusesOneJob() = runTest {
        val rig = Rig()
        val batch = rig.resolved()
        val first = rig.adapter.admitMember(batch.id, 0, 0)
        val second = rig.adapter.admitMember(batch.id, 1, 0)
        assertNotEquals(first.fence.member.requestedBindingId, second.fence.member.requestedBindingId)
        assertNotEquals(first.fence.claim.id, second.fence.claim.id)
        assertEquals(first.fence.job, second.fence.job)
        assertEquals(first.fence.member.requestedBindingId, second.fence.job.producerBindingId)
        assertEquals(second.fence.member.requestedBindingId, second.fence.claim.requestedBindingId)
        assertEquals(2, rig.repository.state.value.receipts.size)
        assertEquals(1, rig.executor.producerByJob.size)
        assertTrue(rig.repository.state.value.assetUses.isEmpty(), "A compatible running job is not an asset-use receipt")
    }

    @Test fun repeatedMemberAdmissionPassesTheExistingClaimWithoutCreatingAnotherOwnerOrQuery() = runTest {
        val rig = Rig()
        val batch = rig.resolved(rig.prepared(1))
        val first = rig.adapter.admitMember(batch.id, 0, 0)
        val again = rig.adapter.admitMember(batch.id, 0, 0)
        assertEquals(first.fence.claim, again.fence.claim)
        assertEquals(first.fence.claim.id, rig.executor.admissions.last().previouslyAttachedClaimId)
        assertEquals(1, rig.repository.state.value.claims.size)
        assertEquals(1, rig.executor.producerByJob.size)
        assertEquals(1, rig.gateway.acquisitions.size)
    }

    @Test fun unboundMemberCannotEnterTheExistingExecutor() = runTest {
        val rig = Rig()
        val batch = rig.batches.admit(rig.prepared())
        assertEquals("download_member_not_bound", assertFailsWith<VeyraException> { rig.adapter.admitMember(batch.id, 0, 0) }.failure.detail)
        assertTrue(rig.executor.admissions.isEmpty())
        assertTrue(rig.gateway.acquisitions.isEmpty())
    }

    @Test fun pausedAndStaleBatchActionsFailBeforeExecutorAdmission() = runTest {
        val rig = Rig()
        val batch = rig.resolved()
        val paused = rig.batches.setIntent(batch.id, 0, DownloadBatchIntent.PAUSED)
        for (revision in listOf(0L, paused.intentRevision)) {
            assertEquals("download_batch_action_stale", assertFailsWith<VeyraException> {
                rig.adapter.admitMember(batch.id, 0, revision)
            }.failure.detail)
        }
        assertTrue(rig.executor.admissions.isEmpty())
    }

    @Test fun anEqualActiveIntentCannotHideAStaleClaimOwnerRevision() = runTest {
        val rig = Rig()
        val batch = rig.resolved(rig.prepared(1))
        rig.adapter.admitMember(batch.id, 0, 0)
        val paused = rig.batches.setIntent(batch.id, 0, DownloadBatchIntent.PAUSED)
        val active = rig.batches.setIntent(batch.id, paused.intentRevision, DownloadBatchIntent.ACTIVE)
        val claim = rig.repository.state.value.claims.values.single()
        assertEquals(2L, claim.generation)
        assertEquals(active.intentRevision, claim.ownerIntentRevision)
        rig.repository.state.value = rig.repository.state.value.copy(claims = mapOf(claim.id to claim.copy(ownerIntentRevision = 0)))
        assertEquals("download_member_claim_mismatch", assertFailsWith<VeyraException> {
            rig.adapter.reconcileMember(batch.id, 0, active.intentRevision)
        }.failure.detail)
        assertTrue(rig.executor.reconciliations.isEmpty())
    }

    @Test fun firstAdmissionAfterExplicitResumeCapturesThatOwnerRevisionNotZero() = runTest {
        val rig = Rig()
        val batch = rig.resolved(rig.prepared(1))
        val paused = rig.batches.setIntent(batch.id, 0, DownloadBatchIntent.PAUSED)
        val active = rig.batches.setIntent(batch.id, paused.intentRevision, DownloadBatchIntent.ACTIVE)
        val admitted = rig.adapter.admitMember(batch.id, 0, active.intentRevision)
        assertEquals(active.intentRevision, admitted.fence.claim.ownerIntentRevision)
        assertEquals(0L, admitted.fence.claim.generation)
        assertEquals(1, rig.gateway.acquisitions.size)
    }

    @Test fun anUnpersistedClaimResponseIsRejectedInsteadOfBeingAttachedAfterExecutorReturn() = runTest {
        val rig = Rig()
        val batch = rig.resolved(rig.prepared(1))
        rig.executor.admission = { command ->
            val job = "uncommitted-job"
            rig.executor.producerByJob[job] = command.acquisition.binding.id
            val claim = DownloadClaim(DownloadClaimId("uncommitted-claim"), DownloadClaimOwner.BatchMember(batch.id, 0), job,
                command.member.requestedBindingId, DownloadBatchIntent.ACTIVE, true, 123_000)
            AcquisitionDownloadAdmission(rig.executor.fence(command.member, claim), rig.repository.state.value.libraryRevision)
        }
        assertEquals("download_executor_fence_mismatch", assertFailsWith<VeyraException> { rig.adapter.admitMember(batch.id, 0, 0) }.failure.detail)
        assertTrue(rig.repository.downloadClaims.isEmpty(), "The adapter must not run a later claim-attachment transaction")
        assertTrue(rig.repository.state.value.claims.isEmpty())
    }

    @Test fun aDifferentExecutorCannotSupplyTheAdmissionFenceEvenWithIdenticalPublicValues() = runTest {
        val rig = Rig()
        val batch = rig.resolved(rig.prepared(1))
        val foreign = FakeExecutor(rig.repository)
        rig.executor.admission = { command ->
            val result = rig.executor.persist(command)
            val old = result.fence
            result.copy(fence = AcquisitionDownloadFence(foreign, old.member, old.claim, old.job, old.generation))
        }
        assertEquals("download_executor_fence_mismatch", assertFailsWith<VeyraException> { rig.adapter.admitMember(batch.id, 0, 0) }.failure.detail)
        assertEquals(1, rig.repository.state.value.claims.size, "Unknown admission is not compensated by cancelling a possibly shared job")
        assertTrue(rig.executor.reconciliations.isEmpty())
    }

    @Test fun anAdmissionCannotClaimACommitRevisionThatHasNotBeenPublishedByTheRoomPort() = runTest {
        val rig = Rig()
        val batch = rig.resolved(rig.prepared(1))
        rig.executor.admission = { command -> rig.executor.persist(command).let { it.copy(committedLibraryRevision = it.committedLibraryRevision + 1) } }
        assertEquals("download_executor_commit_not_observed", assertFailsWith<VeyraException> {
            rig.adapter.admitMember(batch.id, 0, 0)
        }.failure.detail)
    }

    @Test fun aPublishedClaimWithoutTheActualPhysicalJobDoesNotProveAdmission() = runTest {
        val rig = Rig()
        val batch = rig.resolved(rig.prepared(1))
        rig.executor.admission = { command -> rig.executor.persist(command).also {
            rig.repository.state.value = rig.repository.state.value.copy(physicalDownloads = emptyMap())
        } }
        assertEquals("download_physical_job_missing", assertFailsWith<VeyraException> {
            rig.adapter.admitMember(batch.id, 0, 0)
        }.failure.detail)
        assertEquals(1, rig.repository.state.value.claims.size)
        assertTrue(rig.executor.reconciliations.isEmpty(), "The adapter must not compensate by cancelling shared work")
    }

    @Test fun anEchoedFenceCannotHideRelabellingTheActualJobFromR1ToR2() = runTest {
        val rig = Rig()
        val batch = rig.resolved()
        rig.adapter.admitMember(batch.id, 0, 0)
        rig.executor.admission = { command -> rig.executor.persist(command).also { result ->
            val state = rig.repository.state.value
            val job = state.physicalDownloads.getValue(result.fence.job.physicalJobId)
            val replacement = PhysicalDownloadProducer.Typed(command.acquisition.subject.id,
                command.acquisition.binding.id, command.acquisition.recording.id)
            rig.repository.state.value = state.copy(physicalDownloads = state.physicalDownloads + (job.id to job.copy(producer = replacement)))
        } }
        assertEquals("download_physical_job_mismatch", assertFailsWith<VeyraException> {
            rig.adapter.admitMember(batch.id, 1, 0)
        }.failure.detail)
        assertTrue(rig.repository.state.value.assetUses.isEmpty())
    }

    @Test fun aNamedAttemptRequiresItsExactStoredJobProducerNumberAndDurableHistory() = runTest {
        for (corruption in 0..5) {
            val rig = Rig()
            val batch = rig.resolved(rig.prepared(1))
            rig.executor.admission = { command ->
                val admitted = rig.executor.persist(command)
                val attempt = rig.executor.attempt(admitted.fence.job.physicalJobId)
                val fence = rig.executor.fence(command.member, admitted.fence.claim)
                val altered = when (corruption) {
                    0 -> null
                    1 -> attempt.copy(downloadId = "another-job")
                    2 -> attempt.copy(producer = PhysicalDownloadProducer.Typed(attempt.producer.subjectId,
                        ExecutionBindingId("another-producer"), checkNotNull(attempt.producer.recordingId)))
                    3 -> attempt.copy(number = attempt.number + 1)
                    4 -> attempt.copy(ownershipGeneration = attempt.ownershipGeneration + 1)
                    else -> attempt.copy(id = "another-attempt")
                }
                val state = rig.repository.state.value
                rig.repository.state.value = state.copy(physicalAttempts = if (altered == null) state.physicalAttempts - attempt.id
                    else state.physicalAttempts + (attempt.id to altered))
                admitted.copy(fence = fence)
            }
            assertEquals(if (corruption == 0) "download_physical_attempt_missing" else "download_physical_attempt_mismatch",
                assertFailsWith<VeyraException> { rig.adapter.admitMember(batch.id, 0, 0) }.failure.detail)
        }
    }

    @Test fun aRetiredNamedAttemptRemainsAnObservationEvenIfASuccessorAlreadyStarted() = runTest {
        for (successor in listOf(false, true)) {
            val rig = Rig()
            val batch = rig.resolved(rig.prepared(1))
            var originalAttempt: PhysicalDownloadAttempt? = null
            rig.executor.admission = { command ->
                val admitted = rig.executor.persist(command)
                val attempt = rig.executor.attempt(admitted.fence.job.physicalJobId)
                originalAttempt = attempt
                val fence = rig.executor.fence(command.member, admitted.fence.claim)
                val state = rig.repository.state.value
                val job = state.physicalDownloads.getValue(attempt.downloadId)
                rig.repository.state.value = state.copy(physicalDownloads = state.physicalDownloads +
                    (job.id to job.copy(activeAttemptId = null, status = DownloadStatus.PAUSED)),
                    physicalAttempts = state.physicalAttempts + (attempt.id to attempt.copy(status = DownloadAttemptStatus.PAUSED, finishedEpochMs = 123_000)))
                if (successor) rig.executor.attempt(job.id)
                admitted.copy(fence = fence)
            }
            val admitted = rig.adapter.admitMember(batch.id, 0, 0)
            assertEquals(originalAttempt?.id, admitted.fence.generation.attemptId)
            assertNotEquals(originalAttempt?.id, rig.repository.state.value.physicalDownloads.getValue(admitted.fence.job.physicalJobId).activeAttemptId)
            assertTrue(rig.repository.state.value.assetUses.isEmpty(), "Historical admission is neither current-epoch permission nor Ready")
        }
    }

    @Test fun returnedClaimCannotRetargetTheOwnerRequestedBindingOrPhysicalJob() = runTest {
        for (corruption in 0..2) {
            val rig = Rig()
            val batch = rig.resolved()
            rig.executor.admission = { command ->
                val result = rig.executor.persist(command)
                val old = result.fence
                val claim = when (corruption) {
                    0 -> old.claim.copy(owner = DownloadClaimOwner.BatchMember(batch.id, 1))
                    1 -> old.claim.copy(requestedBindingId = checkNotNull(batch.members[1].requiredBindingId))
                    else -> old.claim.copy(physicalJobId = "different-job")
                }
                result.copy(fence = AcquisitionDownloadFence(rig.executor, old.member, claim, old.job, old.generation))
            }
            assertEquals("download_executor_fence_mismatch", assertFailsWith<VeyraException> { rig.adapter.admitMember(batch.id, 0, 0) }.failure.detail)
        }
    }

    @Test fun anAvailableMemberDoesNotBypassTheSameSubjectDifferentRecordingConflict() = runTest {
        val rig = Rig()
        val requests = listOf("one", "two").map { authoredRequest(it, MediaCapture(authoredMedia("same-catalogue-subject", "Complete title $it"))) }
        rig.gateway.acquireResult = { authoredSingle(it, if (it.id == requests.first().id) "LMNOPQRSTUV" else "UVWXYZABCDE") }
        val batch = rig.resolved(rig.factory.catalogLeaves(requests, PlaybackControlsMode.MUSIC, null))
        rig.adapter.admitMember(batch.id, 0, 0)
        val current = rig.repository.state.value.batches.getValue(batch.id)
        rig.repository.state.value = rig.repository.state.value.copy(batches = rig.repository.state.value.batches +
            (batch.id to current.copy(members = current.members.map { if (it.position == 1) it.copy(state = DownloadBatchMemberState.AVAILABLE) else it })))
        assertFailsWith<VeyraException> { rig.adapter.admitMember(batch.id, 1, 0) }
        assertTrue(rig.repository.state.value.assetUses.isEmpty())
        assertEquals(2, rig.gateway.acquisitions.size)
    }

    @Test fun knownSchema2ProducerRetainsANullHistoricalUrlWithoutInventingATypedReceipt() = runTest {
        for (hasRecordedUrl in listOf(false, true)) {
            val rig = Rig()
            val (batch, legacy) = rig.legacyResolved { if (hasRecordedUrl) it.copy(canonicalUrl = "https://www.youtube.com/watch?v=${it.selectedVideoId}") else it }
            val before = rig.repository.state.value
            val admitted = rig.adapter.admitMember(batch.id, 0, 0)
            val job = rig.repository.state.value.physicalDownloads.getValue(admitted.fence.job.physicalJobId)
            assertIs<PhysicalDownloadProducer.Schema2>(job.producer)
            assertEquals(ExecutionBindingId("legacy-binding"), job.producer.bindingId)
            assertNotEquals(job.producer.bindingId, admitted.fence.claim.requestedBindingId)
            assertEquals(legacy, rig.repository.state.value.receipts.getValue(legacy.id))
            assertEquals(before.requests, rig.repository.state.value.requests)
            assertEquals(before.receipts, rig.repository.state.value.receipts)
            assertEquals(1, rig.gateway.acquisitions.size, "There was only R2's explicit complete-title query")
            assertTrue(rig.repository.state.value.assetUses.isEmpty())
        }
    }

    @Test fun schema2ExecutableLabelDoesNotExcuseAnIncompleteHistoricalQueryTitleOrWrongFirstIdentity() = runTest {
        for (corruption in 0..8) {
            val rig = Rig()
            val (batch, _) = rig.legacyResolved { receipt -> when (corruption) {
                0 -> receipt.copy(queryTitle = " ")
                1 -> receipt.copy(queryTitle = "Original\nquery")
                2 -> receipt.copy(queryTitle = "q".repeat(AcquisitionInputRules.MAX_COMPLETE_TITLE_LENGTH + 1))
                3 -> receipt.copy(firstResultTitle = "")
                4 -> receipt.copy(firstResultTitle = "unpaired \uD800")
                5 -> receipt.copy(canonicalUrl = "https://www.youtube.com/watch?v=ABCDEFGHIJK")
                6 -> receipt.copy(firstResultKind = FirstResultKind.PLAYLIST)
                7 -> receipt.copy(selectionOrdinal = 1)
                else -> receipt.copy(firstResultId = "ABCDEFGHIJK")
            } }
            assertEquals("download_producer_receipt_mismatch", assertFailsWith<VeyraException> {
                rig.adapter.admitMember(batch.id, 0, 0)
            }.failure.detail)
            assertTrue(rig.repository.state.value.assetUses.isEmpty())
        }
    }

    @Test fun anotherRequestedBindingCannotLaunderMalformedRawTitleOrUrlOnTheOriginalTypedProducer() = runTest {
        for (corruption in 0..5) {
            val rig = Rig()
            val batch = rig.resolved()
            val original = rig.adapter.admitMember(batch.id, 0, 0)
            rig.executor.admission = { command -> rig.executor.persist(command).also {
                val state = rig.repository.state.value
                val binding = state.bindings.getValue(original.fence.job.producerBindingId)
                val receipt = state.receipts.getValue(binding.firstReceiptId) as FirstResultReceipt.Observed
                val raw = receipt.observation.entry
                val altered = when (corruption) {
                    0 -> raw.copy(title = null)
                    1 -> raw.copy(title = " \t ")
                    2 -> raw.copy(title = "unpaired \uDC00")
                    3 -> raw.copy(title = "t".repeat(AcquisitionInputRules.MAX_COMPLETE_TITLE_LENGTH + 1))
                    4 -> raw.copy(publicUrl = null)
                    else -> raw.copy(publicUrl = "https://www.youtube.com/watch?v=ABCDEFGHIJK")
                }
                rig.repository.state.value = state.copy(receipts = state.receipts +
                    (receipt.id to receipt.copy(observation = receipt.observation.copy(entry = altered))))
            } }
            assertEquals("download_producer_receipt_mismatch", assertFailsWith<VeyraException> {
                rig.adapter.admitMember(batch.id, 1, 0)
            }.failure.detail)
            assertEquals(2, rig.gateway.acquisitions.size)
        }
    }

    @Test fun anIntentRaceAfterDurableAdmissionDoesNotExposeTheOldClaimAsCurrentOrStopOtherOwners() = runTest {
        val rig = Rig()
        val batch = rig.resolved(rig.prepared(1))
        rig.executor.admission = { command ->
            rig.executor.persist(command).also { rig.batches.setIntent(batch.id, 0, DownloadBatchIntent.PAUSED) }
        }
        assertEquals("download_batch_action_stale", assertFailsWith<VeyraException> { rig.adapter.admitMember(batch.id, 0, 0) }.failure.detail)
        assertEquals(DownloadBatchIntent.PAUSED, rig.repository.state.value.claims.values.single().intent)
        assertTrue(rig.executor.reconciliations.isEmpty())
        assertTrue(rig.repository.state.value.assetUses.isEmpty())
    }

    @Test fun cancellationAtAnAdmissionReturnKeepsTheDurableClaimForRoomOnlyRestore() = runTest {
        val rig = Rig()
        val batch = rig.resolved(rig.prepared(1))
        rig.executor.admission = { command ->
            rig.executor.persist(command).also { currentCoroutineContext().job.cancel(CancellationException("authored return cancellation")) }
        }
        val action = launch { rig.adapter.admitMember(batch.id, 0, 0) }
        action.join()
        assertTrue(action.isCancelled)
        assertEquals(1, rig.repository.state.value.claims.size)
        rig.offline = true
        assertEquals(1, rig.acquisition.restore().claims.size)
        assertEquals(1, rig.executor.admissions.size)
        assertTrue(rig.executor.reconciliations.isEmpty())
        assertTrue(rig.executor.assetChecks.isEmpty())
        assertEquals(1, rig.gateway.acquisitions.size)
    }

    @Test fun durableClaimSettlementIsNotAPhysicalQuiescenceAcknowledgement() = runTest {
        val rig = Rig()
        val batch = rig.resolved(rig.prepared(1))
        rig.adapter.admitMember(batch.id, 0, 0)
        val paused = rig.batches.setIntent(batch.id, 0, DownloadBatchIntent.PAUSED)
        rig.executor.reconciliation = { command -> AcquisitionDownloadReconciliation(rig.executor.fence(command.member, command.expectedClaim),
            AcquisitionDownloadDurableSettlement.Confirmed(rig.repository.state.value.libraryRevision),
            AcquisitionDownloadPhysicalSettlement.Retained(AppFailure(FailureCode.RECOVERY_REQUIRED, "authored_native_close_not_consumed"))) }
        val result = rig.adapter.reconcileMember(batch.id, 0, paused.intentRevision)
        assertIs<AcquisitionDownloadDurableSettlement.Confirmed>(result.durable)
        assertIs<AcquisitionDownloadPhysicalSettlement.Retained>(result.physical)
        assertEquals(paused.intentRevision, result.fence.member.intentRevision)
        assertTrue(rig.repository.state.value.assetUses.isEmpty())
    }

    @Test fun positivePhysicalCleanupDoesNotConvertAnUnconfirmedRoomFinalizerIntoDurableSuccess() = runTest {
        val rig = Rig()
        val batch = rig.resolved(rig.prepared(1))
        rig.adapter.admitMember(batch.id, 0, 0)
        val cancelled = rig.batches.setIntent(batch.id, 0, DownloadBatchIntent.CANCELLED)
        rig.executor.reconciliation = { command ->
            val fence = rig.executor.fence(command.member, command.expectedClaim)
            AcquisitionDownloadReconciliation(fence,
                AcquisitionDownloadDurableSettlement.Unconfirmed(AppFailure(FailureCode.STORAGE_IO, "authored_finalizer_unconfirmed")),
                AcquisitionDownloadPhysicalSettlement.Quiescent(AcquisitionDownloadQuiescence(fence)))
        }
        val result = rig.adapter.reconcileMember(batch.id, 0, cancelled.intentRevision)
        assertIs<AcquisitionDownloadDurableSettlement.Unconfirmed>(result.durable)
        assertSame(result.fence, assertIs<AcquisitionDownloadPhysicalSettlement.Quiescent>(result.physical).acknowledgement.fence)
        assertTrue(rig.repository.state.value.assetUses.isEmpty())
    }

    @Test fun pausingOneSharedJobOwnerDoesNotChangeAnotherBatchOrAssertPhysicalQuiescence() = runTest {
        val rig = Rig()
        val first = rig.resolved(rig.prepared(1))
        val second = rig.resolved(rig.prepared(1))
        val firstClaim = rig.adapter.admitMember(first.id, 0, 0).fence.claim
        val secondClaim = rig.adapter.admitMember(second.id, 0, 0).fence.claim
        assertEquals(firstClaim.physicalJobId, secondClaim.physicalJobId)
        val paused = rig.batches.setIntent(first.id, 0, DownloadBatchIntent.PAUSED)
        val result = rig.adapter.reconcileMember(first.id, 0, paused.intentRevision)
        assertIs<AcquisitionDownloadPhysicalSettlement.NotRequested>(result.physical)
        assertEquals(DownloadBatchIntent.PAUSED, result.fence.claim.intent)
        assertEquals(secondClaim, rig.repository.state.value.claims.getValue(secondClaim.id))
        assertEquals(DownloadBatchIntent.ACTIVE, rig.repository.state.value.batches.getValue(second.id).intent)
        assertTrue(rig.executor.reconciliations.all { it.member.batchId == first.id })
    }

    @Test fun quiescenceFromAnotherAttemptOrAnotherFenceInstanceCannotAcknowledgeThisOperation() = runTest {
        for (differentAttempt in listOf(false, true)) {
            val rig = Rig()
            val batch = rig.resolved(rig.prepared(1))
            rig.adapter.admitMember(batch.id, 0, 0)
            val paused = rig.batches.setIntent(batch.id, 0, DownloadBatchIntent.PAUSED)
            rig.executor.reconciliation = { command ->
                val fence = rig.executor.fence(command.member, command.expectedClaim)
                val other = AcquisitionDownloadFence(rig.executor, fence.member, fence.claim, fence.job,
                    if (differentAttempt) fence.generation.copy(ownershipGeneration = "other-epoch", attemptId = "other-attempt", attemptNumber = 2)
                    else fence.generation)
                AcquisitionDownloadReconciliation(fence, AcquisitionDownloadDurableSettlement.Confirmed(rig.repository.state.value.libraryRevision),
                    AcquisitionDownloadPhysicalSettlement.Quiescent(AcquisitionDownloadQuiescence(other)))
            }
            assertEquals("download_quiescence_generation_mismatch", assertFailsWith<VeyraException> {
                rig.adapter.reconcileMember(batch.id, 0, paused.intentRevision)
            }.failure.detail)
        }
    }

    @Test fun aStoredUseAndSameRecordingDoNotSkipCurrentProcessValidation() = runTest {
        val rig = Rig()
        val batch = rig.resolved(rig.prepared(1))
        val admission = rig.adapter.admitMember(batch.id, 0, 0)
        val asset = MaterializationAssetId("authored-asset-generation")
        rig.executor.materialization(admission.fence.job.physicalJobId, asset)
        val use = AssetUse(admission.fence.member.requestedBindingId, asset, 1, 123_000)
        rig.repository.state.value = rig.repository.state.value.copy(assetUses = setOf(use))
        assertIs<AcquisitionDownloadAssetCheck.Unavailable>(rig.adapter.validateMemberAsset(batch.id, 0, 0, asset))
        assertEquals(1, rig.executor.assetChecks.size)
        assertEquals(1, rig.gateway.acquisitions.size)
        assertTrue(rig.gateway.inspections.isEmpty())
    }

    @Test fun aValidationClaimWithoutItsExactPersistedAssetUseIsRejected() = runTest {
        val rig = Rig()
        val batch = rig.resolved(rig.prepared(1))
        rig.adapter.admitMember(batch.id, 0, 0)
        rig.executor.assetCheck = { rig.executor.validated(it, persistUse = false) }
        assertEquals("download_asset_use_not_committed", assertFailsWith<VeyraException> {
            rig.adapter.validateMemberAsset(batch.id, 0, 0, MaterializationAssetId("authored-asset"))
        }.failure.detail)
        assertTrue(rig.repository.state.value.assetUses.isEmpty())
    }

    @Test fun aValidatedUsePreservesR2RequestedBindingAndR1MaterializerWithoutRelabellingEitherReceipt() = runTest {
        val rig = Rig()
        val batch = rig.resolved()
        val r1 = rig.adapter.admitMember(batch.id, 0, 0)
        val r2 = rig.adapter.admitMember(batch.id, 1, 0)
        val beforeBindings = rig.repository.state.value.bindings
        val beforeReceipts = rig.repository.state.value.receipts
        val asset = MaterializationAssetId("authored-exact-generation")
        rig.executor.assetCheck = { rig.executor.validated(it) }
        val proof = assertIs<AcquisitionDownloadAssetCheck.Validated>(rig.adapter.validateMemberAsset(batch.id, 1, 0, asset)).validation
        assertEquals(r2.fence.member.requestedBindingId, proof.use.requestedBindingId)
        assertEquals(r1.fence.member.requestedBindingId, proof.fence.job.producerBindingId)
        assertNotEquals(proof.use.requestedBindingId, proof.fence.job.producerBindingId)
        assertEquals(asset, proof.use.materializationAssetId)
        assertEquals(beforeBindings, rig.repository.state.value.bindings)
        assertEquals(beforeReceipts, rig.repository.state.value.receipts)
        assertEquals(2, rig.gateway.acquisitions.size)
        val materialization = rig.repository.state.value.materializations.getValue(asset)
        assertEquals(r1.fence.job.producerBindingId, materialization.producer.bindingId)
        assertEquals(materialization.receipt(), rig.repository.state.value.materializationReceipts.getValue(asset))
        assertNull(proof.fence.generation.attemptId, "An idle validation receipt need not name the original materializer attempt")
        assertNotNull(materialization.materializerAttemptId)
    }

    @Test fun aValidatedUseNeedsTheExactLiveMaterializationAndItsImmutableReceipt() = runTest {
        for (corruption in 0..4) {
            val rig = Rig()
            val batch = rig.resolved(rig.prepared(1))
            rig.adapter.admitMember(batch.id, 0, 0)
            rig.executor.assetCheck = { command -> rig.executor.validated(command).also { result ->
                val state = rig.repository.state.value
                val assetId = result.validation.use.materializationAssetId
                val asset = state.materializations.getValue(assetId)
                val job = state.physicalDownloads.getValue(command.expectedClaim.physicalJobId)
                rig.repository.state.value = when (corruption) {
                    0 -> state.copy(materializations = state.materializations - assetId)
                    1 -> state.copy(materializationReceipts = state.materializationReceipts - assetId)
                    2 -> state.copy(materializationReceipts = state.materializationReceipts +
                        (assetId to asset.receipt().copy(sizeBytes = asset.sizeBytes + 1)))
                    3 -> state.copy(physicalDownloads = state.physicalDownloads + (job.id to job.copy(assetId = MaterializationAssetId("other-asset"))))
                    else -> state.copy(materializations = state.materializations +
                        (assetId to asset.copy(id = MaterializationAssetId("other-live-identity"))))
                }
            } }
            val expected = when (corruption) {
                0 -> "download_live_materialization_missing"
                1 -> "download_materialization_receipt_missing"
                else -> "download_materialization_mismatch"
            }
            assertEquals(expected, assertFailsWith<VeyraException> {
                rig.adapter.validateMemberAsset(batch.id, 0, 0, MaterializationAssetId("authored-asset"))
            }.failure.detail)
        }
    }

    @Test fun typedMaterializationCannotInventAnUnknownMaterializerOrIncompleteIntegrityMetadata() = runTest {
        for (corruption in 0..6) {
            val rig = Rig()
            val batch = rig.resolved(rig.prepared(1))
            rig.adapter.admitMember(batch.id, 0, 0)
            rig.executor.assetCheck = { command -> rig.executor.validated(command).also { result ->
                val state = rig.repository.state.value
                val assetId = result.validation.use.materializationAssetId
                val asset = state.materializations.getValue(assetId)
                val changed = when (corruption) {
                    0 -> asset.copy(materializerDownloadId = null)
                    1 -> asset.copy(materializerAttemptId = null)
                    2 -> asset.copy(ownershipGeneration = 0)
                    3 -> asset.copy(representationId = null)
                    4 -> asset.copy(sizeBytes = 0)
                    5 -> asset.copy(sha256 = null)
                    else -> asset.copy(sha256 = "not-a-hash")
                }
                rig.repository.state.value = state.copy(materializations = state.materializations + (assetId to changed),
                    materializationReceipts = state.materializationReceipts + (assetId to changed.receipt()))
            } }
            assertEquals(if (corruption <= 3) "download_materializer_lineage_missing" else "download_materialization_mismatch",
                assertFailsWith<VeyraException> {
                    rig.adapter.validateMemberAsset(batch.id, 0, 0, MaterializationAssetId("authored-asset"))
                }.failure.detail)
        }
    }

    @Test fun materializerAttemptCannotBorrowAnotherProducerGenerationOrRepresentation() = runTest {
        for (corruption in 0..5) {
            val rig = Rig()
            val batch = rig.resolved(rig.prepared(1))
            rig.adapter.admitMember(batch.id, 0, 0)
            rig.executor.assetCheck = { command -> rig.executor.validated(command).also { result ->
                val state = rig.repository.state.value
                val asset = state.materializations.getValue(result.validation.use.materializationAssetId)
                val attempt = state.physicalAttempts.getValue(checkNotNull(asset.materializerAttemptId))
                val altered = when (corruption) {
                    0 -> null
                    1 -> attempt.copy(downloadId = "other-job")
                    2 -> attempt.copy(producer = PhysicalDownloadProducer.Typed(attempt.producer.subjectId,
                        ExecutionBindingId("other-binding"), checkNotNull(attempt.producer.recordingId)))
                    3 -> attempt.copy(ownershipGeneration = attempt.ownershipGeneration + 1)
                    4 -> attempt.copy(representationId = "other-representation")
                    else -> attempt.copy(validatorGeneration = attempt.validatorGeneration + 1)
                }
                rig.repository.state.value = state.copy(physicalAttempts = if (altered == null) state.physicalAttempts - attempt.id
                    else state.physicalAttempts + (attempt.id to altered))
            } }
            assertEquals(if (corruption == 0) "download_materializer_attempt_missing" else "download_materializer_attempt_mismatch",
                assertFailsWith<VeyraException> {
                    rig.adapter.validateMemberAsset(batch.id, 0, 0, MaterializationAssetId("authored-asset"))
                }.failure.detail)
        }
    }

    @Test fun newerAttemptlessMaintenanceDoesNotRelabelTheOriginalMaterializationGeneration() = runTest {
        val rig = Rig()
        val batch = rig.resolved(rig.prepared(1))
        rig.adapter.admitMember(batch.id, 0, 0)
        rig.executor.assetCheck = { command -> rig.executor.validated(command).also {
            val state = rig.repository.state.value
            val job = state.physicalDownloads.getValue(command.expectedClaim.physicalJobId)
            rig.repository.state.value = state.copy(physicalDownloads = state.physicalDownloads +
                (job.id to job.copy(ownershipGeneration = job.ownershipGeneration + 1)))
        } }
        val assetId = MaterializationAssetId("authored-asset")
        val result = assertIs<AcquisitionDownloadAssetCheck.Validated>(rig.adapter.validateMemberAsset(batch.id, 0, 0, assetId))
        val asset = rig.repository.state.value.materializations.getValue(assetId)
        val job = rig.repository.state.value.physicalDownloads.getValue(result.validation.fence.job.physicalJobId)
        assertNull(result.validation.fence.generation.attemptId)
        assertTrue(job.ownershipGeneration > asset.ownershipGeneration)
        assertEquals(asset.ownershipGeneration, rig.repository.state.value.physicalAttempts.getValue(checkNotNull(asset.materializerAttemptId)).ownershipGeneration)
    }

    @Test fun knownLegacyAssetValidationDoesNotManufactureUnknownMaterializerIds() = runTest {
        val rig = Rig()
        val (batch, _) = rig.legacyResolved()
        val admitted = rig.adapter.admitMember(batch.id, 0, 0)
        val assetId = MaterializationAssetId("authored-legacy-asset")
        val state = rig.repository.state.value
        val job = state.physicalDownloads.getValue(admitted.fence.job.physicalJobId)
        // Seed historical metadata before the check; no fake transfer may invent then erase
        // unknown original materializer IDs. This remains metadata-only fixture evidence.
        val legacy = MaterializationAsset(assetId, job.producer, "/authored-test-only/legacy.webm", 128,
            mimeType = "audio/webm", codecs = "opus", sha256 = "a".repeat(64),
            representationId = "authored-legacy-representation", completedEpochMs = 100_000)
        rig.repository.state.value = state.copy(physicalDownloads = state.physicalDownloads +
            (job.id to job.copy(status = DownloadStatus.COMPLETE, assetId = assetId, bytesDownloaded = 128, totalBytes = 128)),
            materializations = state.materializations + (assetId to legacy),
            materializationReceipts = state.materializationReceipts + (assetId to legacy.receipt()))
        rig.executor.assetCheck = { command -> rig.executor.validated(command) }

        assertIs<AcquisitionDownloadAssetCheck.Validated>(rig.adapter.validateMemberAsset(batch.id, 0, 0, assetId))
        val receipt = rig.repository.state.value.materializationReceipts.getValue(assetId)
        assertEquals(legacy.receipt(), receipt)
        assertIs<PhysicalDownloadProducer.Schema2>(receipt.producer)
        assertNull(receipt.materializerDownloadId)
        assertNull(receipt.materializerAttemptId)
        assertEquals(0L, receipt.ownershipGeneration)
        assertTrue(rig.repository.state.value.physicalAttempts.isEmpty())
        assertEquals(1, rig.gateway.acquisitions.size)
    }

    @Test fun aValidationResponseCannotOutliveADurableCleanupOrUnsettledFinalizationFence() = runTest {
        for (corruption in 0..3) {
            val rig = Rig()
            val batch = rig.resolved(rig.prepared(1))
            rig.adapter.admitMember(batch.id, 0, 0)
            rig.executor.assetCheck = { command -> rig.executor.validated(command).also { result ->
                val state = rig.repository.state.value
                val job = state.physicalDownloads.getValue(command.expectedClaim.physicalJobId)
                val asset = state.materializations.getValue(result.validation.use.materializationAssetId)
                rig.repository.state.value = when (corruption) {
                    0 -> state.copy(physicalDownloads = state.physicalDownloads + (job.id to job.copy(cleanupPending = true)))
                    1 -> state.copy(physicalDownloads = state.physicalDownloads + (job.id to job.copy(removeWhenClean = true)))
                    2 -> state.copy(physicalDownloads = state.physicalDownloads +
                        (job.id to job.copy(maintenanceKind = PhysicalDownloadMaintenanceKind.CLEANUP)))
                    else -> state.copy(physicalFinalizations = state.physicalFinalizations + (job.id to PhysicalDownloadFinalization(
                        job.id, job.producer, "/authored-test-only/staging.part", asset.uri, checkNotNull(asset.sha256), asset.sizeBytes,
                        attemptId = asset.materializerAttemptId, representationId = asset.representationId,
                        validatorGeneration = asset.validatorGeneration, ownershipGeneration = asset.ownershipGeneration, assetId = asset.id)))
                }
            } }
            assertEquals("download_materialization_removal_or_finalization_pending", assertFailsWith<VeyraException> {
                rig.adapter.validateMemberAsset(batch.id, 0, 0, MaterializationAssetId("authored-asset"))
            }.failure.detail)
        }
    }

    @Test fun aClaimGenerationRaceCannotPublishAnOtherwiseValidAssetUseAsTheCurrentOwner() = runTest {
        val rig = Rig()
        val batch = rig.resolved(rig.prepared(1))
        rig.adapter.admitMember(batch.id, 0, 0)
        rig.executor.assetCheck = { command -> rig.executor.validated(command).also {
            val state = rig.repository.state.value
            val claim = state.claims.getValue(command.expectedClaim.id)
            rig.repository.state.value = state.copy(claims = state.claims + (claim.id to claim.copy(generation = claim.generation + 1)))
        } }
        assertEquals("download_member_claim_changed", assertFailsWith<VeyraException> {
            rig.adapter.validateMemberAsset(batch.id, 0, 0, MaterializationAssetId("authored-asset"))
        }.failure.detail)
    }

    @Test fun anotherAssetGenerationCannotReplaceTheOneExplicitlyRequestedForValidation() = runTest {
        val rig = Rig()
        val batch = rig.resolved(rig.prepared(1))
        rig.adapter.admitMember(batch.id, 0, 0)
        rig.executor.assetCheck = { rig.executor.validated(it, asset = MaterializationAssetId("different-generation")) }
        assertEquals("download_asset_use_not_committed", assertFailsWith<VeyraException> {
            rig.adapter.validateMemberAsset(batch.id, 0, 0, MaterializationAssetId("required-generation"))
        }.failure.detail)
    }

    @Test fun cancelledNonretainingMemberCannotAdmitAssetValidationDuringRemoval() = runTest {
        val rig = Rig()
        val batch = rig.resolved(rig.prepared(1))
        rig.adapter.admitMember(batch.id, 0, 0)
        val cancelled = rig.batches.setIntent(batch.id, 0, DownloadBatchIntent.CANCELLED, retainCompletedAssets = false)
        assertEquals("download_member_removal_pending", assertFailsWith<VeyraException> {
            rig.adapter.validateMemberAsset(batch.id, 0, cancelled.intentRevision, MaterializationAssetId("authored-asset"))
        }.failure.detail)
        assertTrue(rig.executor.assetChecks.isEmpty())
    }

    @Test fun rawExecutorFailuresAreSanitizedWithoutCompensationOrAnotherTitleSearch() = runTest {
        val rig = Rig()
        val batch = rig.resolved(rig.prepared(1))
        rig.executor.admission = { error("authored-private-response https://media.example/?sig=private") }
        val failure = assertFailsWith<VeyraException> { rig.adapter.admitMember(batch.id, 0, 0) }
        assertEquals(AppFailure(FailureCode.UNEXPECTED, "download_executor_operation_failed"), failure.failure)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains("sig="))
        assertEquals(1, rig.executor.admissions.size)
        assertTrue(rig.executor.reconciliations.isEmpty())
        assertEquals(1, rig.gateway.acquisitions.size)
    }
}
