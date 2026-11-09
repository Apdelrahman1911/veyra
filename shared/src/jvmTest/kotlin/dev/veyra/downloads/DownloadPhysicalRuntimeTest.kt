package dev.veyra.downloads

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.playback.*
import dev.veyra.source.execution.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.toByteString
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * Actual runtime/Jobs/owned files/OS locks with FAKE Room commands, transfer and native gate.
 * These authored non-audio checks do not establish real transaction, source or native success.
 */
class DownloadPhysicalRuntimeTest {
    @Test fun executorForwardsTheExactCanonicalRoomOwnerToken() = physicalRuntimeCase {
        assertSame(repository.transactionOwner, runtime.transactionOwner)
        val different = object : LibraryTransactionOwned { override val transactionOwner = LibraryTransactionOwner() }
        assertFailsWith<IllegalArgumentException> {
            DownloadPhysicalRuntime(different, dependencies, files, legacy, EngineCapabilities(), gate,
                clock, 60_000, 10_000, host)
        }
        assertEquals(0, leases.open.get())
        legacy.assertUnused()
    }

    @Test fun applicationCoverageRefusalPrecedesEveryDurableOwnerAdmission() = physicalRuntimeCase {
        val denied = AppFailure(FailureCode.BACKGROUND_RESTRICTED, "authored_coverage_not_acknowledged")
        admission = { throw VeyraException(denied) }
        assertEquals(denied, assertFailsWith<VeyraException> { runtime.admitMember(admissionCommand()) }.failure)
        assertFalse("admit-member" in events)
        assertEquals(0, gate.acquires.get())
        assertTrue(gateway.preparations.isEmpty())
        assertTrue(runtime.epochs.allLocalOwnersConsumed())
    }

    @Test fun admittedBatchObservationDoesNotCreateAWorkerOrActivateColdValidation() = physicalRuntimeCase {
        hostState.value = hostState.value.copy(started = false, checksActivated = false)
        val result = runtime.admitMember(admissionCommand())
        assertSame(runtime, result.fence.issuer)
        assertEquals(contextR1.binding.id, result.fence.job.producerBindingId)
        assertEquals(contextR2.binding.id, result.fence.claim.requestedBindingId)
        assertTrue(host.active.isEmpty())
        assertEquals(0, gate.acquires.get())
        assertTrue(gateway.preparations.isEmpty())
        assertTrue(validated.isEmpty())
        legacy.assertUnused()
    }

    @Test fun existingExecutionPublishesOriginalR1AndValidatesAnIndependentR2UseLocally() = physicalRuntimeCase {
        val run = start()
        joined(run)
        val asset = repository.state.value.materializations.values.single()
        assertEquals(DownloadStatus.COMPLETE, currentJob().status)
        assertEquals(producer(contextR1), asset.producer)
        assertEquals(run.fence.attemptId, asset.materializerAttemptId)
        assertEquals(run.fence.ownershipGeneration, asset.ownershipGeneration)
        assertEquals(contextR1, gateway.preparations.single().context)
        assertSame(gateway.preparations.single(), gateway.transfers.single())
        assertTrue(repository.state.value.assetUses.isEmpty(), "Publication alone is not an R2 validation receipt")
        val result = assertIs<AcquisitionDownloadAssetCheck.Validated>(runtime.validateAssetUse(
            ValidateAcquisitionDownloadAsset(memberR2, claimR2, asset.id)))
        assertEquals(contextR2.binding.id, result.validation.use.requestedBindingId)
        assertEquals(asset.id, result.validation.use.materializationAssetId)
        assertEquals(asset, repository.state.value.materializations.getValue(asset.id))
        assertEquals(asset.receipt(), repository.state.value.materializationReceipts.getValue(asset.id))
        assertEquals(1, gateway.transfers.size)
        assertEquals(2, validated.size)
        assertTrue(currentJob().ownershipGeneration > asset.ownershipGeneration,
            "Validation owner advances without relabelling the old materializer")
        assertTrue(runtime.epochs.allLocalOwnersConsumed())
        legacy.assertUnused()
    }

    @Test fun knownSchema2UsesItsRealNullableHistoricalReceiptThroughPreparedSameKeyDownload() = physicalRuntimeCase {
        val binding = seedLegacy(savedKey = "saved-legacy-nondefault-key")
        val historical = repository.state.value.receipts.values.single()
        joined(start())
        val prepared = legacy.preparations.single()
        assertEquals(binding, prepared.binding)
        assertEquals("saved-legacy-nondefault-key", prepared.requiredRepresentationId)
        assertSame(prepared, legacy.transfers.single())
        assertEquals(historical, repository.state.value.receipts.values.single())
        assertNull(binding.firstResultUrl)
        assertEquals(DownloadStatus.COMPLETE, currentJob().status)
        assertIs<PhysicalDownloadProducer.Schema2>(repository.state.value.materializations.values.single().producer)
        assertTrue(gateway.preparations.isEmpty())
        assertTrue(repository.state.value.requests.isEmpty(), "No typed request or historical capture was invented")
        legacy.split.assertUnused()
    }

    @Test fun missingPreparedLegacyCapabilityFailsInsteadOfSplittingResolveAndTransfer() = physicalRuntimeCase {
        seedLegacy()
        val oldOnly = DownloadTestGateway().apply { denyAcquisition() }
        val executor = DownloadPhysicalRuntime(repository, dependencies, files, oldOnly, EngineCapabilities(), gate,
            clock, 60_000, 10_000, host)
        val run = start(executor = executor)
        joined(run)
        assertEquals(FailureCode.UNSUPPORTED_SOURCE, currentJob().failure?.code)
        assertEquals("download_legacy_preparation_unavailable", currentJob().failure?.detail)
        assertTrue(repository.publications.isEmpty())
        assertTrue(executor.epochs.allLocalOwnersConsumed())
        oldOnly.assertUnused()
        legacy.assertUnused()
    }

    @Test fun knownLegacySelectionUsesTheCoherentOwnerViewNotASeparatelyNewerCatalogSample() = physicalRuntimeCase {
        val binding = seedLegacy(savedKey = "coherent-saved-key")
        val coherentCatalog = catalog.value
        val reads = AtomicInteger()
        coherentView = {
            reads.incrementAndGet()
            val acquisition = repository.state.value
            LibraryAcquisitionView(coherentCatalog.copy(revision = acquisition.libraryRevision), acquisition)
        }
        // This intentionally inconsistent raw test flow is NOT the canonical read barrier.
        catalog.value = AppSnapshot(revision = 900)
        joined(start())
        assertTrue(reads.get() > 0)
        assertEquals(binding, legacy.preparations.single().binding)
        assertEquals(DownloadStatus.COMPLETE, currentJob().status)
        legacy.split.assertUnused()
    }

    @Test fun schedulingInputsAreDerivedFromTheExactCoherentReadSuppliedByTheHost() = physicalRuntimeCase {
        val atBarrier = repository.state.value
        repository.state.value = atBarrier.copy(libraryRevision = atBarrier.libraryRevision + 1,
            physicalDownloads = mapOf(currentJob().id to currentJob().copy(status = DownloadStatus.CANCELLED)))
        val inputs = runtime.schedulingInputs(atBarrier)
        assertEquals(DownloadStatus.QUEUED, inputs.records.getValue(PhysicalRuntimeFixture.JOB).job.status)
        assertEquals(DownloadStatus.CANCELLED, currentJob().status)
        assertTrue(gateway.preparations.isEmpty())
    }

    @Test fun unknownSchema2ProvenanceCannotPerformAFreshHiddenFirstLookup() = physicalRuntimeCase {
        val binding = seedLegacy()
        val unknown = PhysicalDownloadProducer.Schema2(binding.mediaId, null, null)
        val claim = repository.state.value.claims.values.single().copy(requestedBindingId = null)
        repository.state.value = repository.state.value.copy(physicalDownloads = mapOf(currentJob().id to
            currentJob().copy(producer = unknown)), claims = mapOf(claim.id to claim))
        host.commands.withLock { host.activeLock.withLock { assertNull(runtime.claimExecution(PhysicalRuntimeFixture.JOB)) } }
        assertTrue(repository.attemptClaims.isEmpty())
        assertEquals(0, leases.open.get())
        assertTrue(gateway.preparations.isEmpty())
        legacy.assertUnused()
    }

    @Test fun completedBytesWithADifferentJobRepresentationNeverReceiveANativeOrR2UseReceipt() = physicalRuntimeCase {
        val asset = seedComplete()
        repository.state.value = repository.state.value.copy(physicalDownloads = mapOf(currentJob().id to
            currentJob().copy(representationId = "another-key")))
        val result = runtime.validateAssetUse(ValidateAcquisitionDownloadAsset(memberR2, claimR2, asset.id))
        assertEquals(FailureCode.CORRUPT_MEDIA, assertIs<AcquisitionDownloadAssetCheck.Unavailable>(result).failure.code)
        assertEquals(1, validated.size, "Only the original execution reached the fake native validator")
        assertTrue(repository.uses.isEmpty())
        assertEquals(asset, repository.state.value.materializations.getValue(asset.id))
    }

    @Test fun materializerHistoryCannotBeRelabelledToTheLaterMaintenanceGeneration() = physicalRuntimeCase {
        val asset = seedComplete()
        val attempt = repository.state.value.physicalAttempts.getValue(checkNotNull(asset.materializerAttemptId))
        repository.state.value = repository.state.value.copy(physicalAttempts = mapOf(attempt.id to
            attempt.copy(ownershipGeneration = attempt.ownershipGeneration + 1)))
        val result = runtime.validateAssetUse(ValidateAcquisitionDownloadAsset(memberR2, claimR2, asset.id))
        assertEquals(FailureCode.CORRUPT_MEDIA, assertIs<AcquisitionDownloadAssetCheck.Unavailable>(result).failure.code)
        assertEquals(1, validated.size)
        assertTrue(repository.uses.isEmpty())
    }

    @Test fun aPreparedR2CannotReplaceThePhysicalProducerR1EvenForTheSameRecording() = physicalRuntimeCase {
        gateway.prepareResult = { _, key -> prepared(contextR2, key, PHYSICAL_REPRESENTATION) }
        joined(start())
        assertEquals(FailureCode.INVALID_STATE, currentJob().failure?.code)
        assertTrue(gateway.transfers.isEmpty())
        assertTrue(repository.publications.isEmpty())
        assertEquals(producer(contextR1), currentJob().producer)
        assertEquals(0, leases.open.get())
    }

    @Test fun resumedPhysicalBytesRequireTheirSavedImmutableRepresentationKey() = physicalRuntimeCase {
        val previous = currentJob().copy(representationId = "saved-nondefault-key")
        repository.state.value = repository.state.value.copy(physicalDownloads = mapOf(previous.id to previous))
        joined(start())
        assertEquals("saved-nondefault-key", gateway.preparations.single().requiredRepresentationId)
        assertTrue(gateway.transfers.isEmpty(), "A different default key is not a resume fallback")
        assertEquals(FailureCode.INVALID_STATE, currentJob().failure?.code)
        assertEquals("saved-nondefault-key", currentJob().representationId)
    }

    @Test fun aMissingSavedKeyNeverHandsUnknownHistoricalPartialBytesToThePreparedGateway() = physicalRuntimeCase {
        val oldPath = files.prepareStaging(contextR1.subject.id.value, "unknown-key")
        val unknownBytes = "Unverified authored historical partial".encodeToByteArray()
        Files.write(Path.of(oldPath.toString()), unknownBytes)
        val oldAttempt = PhysicalDownloadAttempt("unknown-key-attempt", currentJob().id, producer(contextR1), 1, 1,
            DownloadAttemptStatus.PAUSED, stagingPath = oldPath.toString())
        repository.state.value = repository.state.value.copy(physicalDownloads = mapOf(currentJob().id to currentJob().copy(
            stagingPath = oldPath.toString(), bytesDownloaded = unknownBytes.size.toLong(), totalBytes = 999,
            etag = "authored-old-validator", attemptCount = 1, ownershipGeneration = 1)),
            physicalAttempts = mapOf(oldAttempt.id to oldAttempt))
        val transfer = gateway.transfer
        gateway.transfer = { prepared, path, progress ->
            assertNotEquals(oldPath.toString(), path)
            assertFalse(Files.exists(Path.of(path)), "New attempt has no inherited unknown journal or bytes")
            assertEquals(0L, currentJob().bytesDownloaded)
            assertNull(currentJob().etag)
            transfer(prepared, path, progress)
        }
        joined(start())
        assertEquals(DownloadStatus.COMPLETE, currentJob().status)
        assertContentEquals(unknownBytes, Files.readAllBytes(Path.of(oldPath.toString())))
        assertEquals(oldAttempt, repository.state.value.physicalAttempts.getValue(oldAttempt.id))
        assertNull(gateway.preparations.single().requiredRepresentationId)
    }

    @Test fun transferCannotReturnAnotherRequestContextOrRepresentationAsReady() = physicalRuntimeCase {
        gateway.transfer = { prepared, path, _ ->
            Files.write(Path.of(path), PHYSICAL_BYTES)
            AcquisitionTransferResult(contextR2, PHYSICAL_BYTES.size.toLong(), prepared.representation)
        }
        joined(start())
        assertEquals(FailureCode.CORRUPT_MEDIA, currentJob().failure?.code)
        assertTrue(repository.publications.isEmpty())
        assertTrue(validated.isEmpty())
        assertTrue(repository.state.value.materializations.isEmpty())
    }

    @Test fun alreadyCancelledExistingScopeStillRunsTheRegisteredOwnersFinalizer() = physicalRuntimeCase {
        val ended = SupervisorJob().also { it.cancel() }
        val run = start(CoroutineScope(ended + Dispatchers.Default))
        joined(run)
        assertTrue(gateway.preparations.isEmpty())
        assertEquals(0, gate.acquires.get())
        assertEquals(1, repository.attemptClaims.size)
        assertEquals(1, repository.finishes.size)
        assertTrue(runtime.epochs.drain(run.epoch))
        assertTrue(runtime.epochs.allLocalOwnersConsumed())
        assertEquals(0, leases.open.get())
        assertTrue(host.active.isEmpty())
    }

    @Test fun cancellationAfterDurableClaimCannotAbandonInstallationOrItsRealLeases() = physicalRuntimeCase {
        supervisorScope {
            val installerIdentity = CompletableDeferred<Job>()
            repository.afterAttemptClaim = { installerIdentity.await().cancel() }
            val installer = async(start = CoroutineStart.LAZY) { start() }
            installerIdentity.complete(installer)
            installer.start()
            assertFailsWith<CancellationException> { installer.await() }
            val epoch = assertNotNull(runtime.epochs.current(PhysicalRuntimeFixture.JOB))
            withTimeout(10_000) { assertNotNull(epoch.job).join() }
            assertEquals(1, repository.attemptClaims.size)
            assertTrue(runtime.epochs.drain(epoch))
            assertTrue(runtime.epochs.allLocalOwnersConsumed())
            assertEquals(0, leases.open.get())
            assertTrue(host.active.isEmpty())
        }
    }

    @Test fun failedAllowanceCloseRetainsTheSlotAndPathAndDrainNeverRetriesIt() = physicalRuntimeCase {
        val inTransfer = barrier()
        gateway.transfer = { _, _, _ -> inTransfer.stop(); error("Cancelled transfer cannot finish") }
        gate.beforeClose = { throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_native_close")) }
        val run = start()
        inTransfer.entered.await()
        assertFalse(runtime.epochs.drain(run.epoch))
        assertEquals(1, gate.closes.get())
        assertTrue(leases.open.get() >= 2)
        assertTrue(repository.finishes.isEmpty(), "Physical close precedes durable owner release")
        assertFalse(runtime.epochs.canAdmit(PhysicalRuntimeFixture.JOB))
        assertFalse(runtime.epochs.drain(run.epoch))
        assertEquals(1, gate.closes.get(), "Quiescence requests are not implicit close retries")
        gate.beforeClose = { }
        runtime.attemptCleanup(cleanup.owners.value.values.single() as DownloadPhysicalCleanup, retryFailed = true)
        assertEquals(2, gate.closes.get())
        assertEquals(1, repository.finishes.size)
        assertTrue(runtime.epochs.drain(run.epoch))
        assertEquals(0, leases.open.get())
    }

    @Test fun durablePublicationAndAFailedNativeCloseProduceConfirmedButRetained() = physicalRuntimeCase {
        gate.beforeClose = { throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_native_close")) }
        val run = start()
        joined(run)
        val (member, claim) = pauseOwner()
        val result = runtime.reconcileClaim(ReconcileAcquisitionDownload(member, claim))
        assertIs<AcquisitionDownloadDurableSettlement.Confirmed>(result.durable)
        assertIs<AcquisitionDownloadPhysicalSettlement.Retained>(result.physical)
        assertFalse(runtime.epochs.drain(run.epoch))
        assertEquals(1, gate.closes.get())
        assertEquals(DownloadStatus.COMPLETE, currentJob().status)
        assertTrue(repository.finishes.isEmpty())
    }

    @Test fun explicitRetainedCleanupThenReconciliationAcknowledgesOnlyItsExactFenceInstance() = physicalRuntimeCase {
        gate.beforeClose = { throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_native_close")) }
        joined(start())
        val (member, claim) = pauseOwner()
        val retained = runtime.reconcileClaim(ReconcileAcquisitionDownload(member, claim))
        assertIs<AcquisitionDownloadPhysicalSettlement.Retained>(retained.physical)
        gate.beforeClose = { }
        runtime.attemptCleanup(cleanup.owners.value.values.single() as DownloadPhysicalCleanup, retryFailed = true)
        val drained = runtime.reconcileClaim(ReconcileAcquisitionDownload(member, claim))
        val ack = assertIs<AcquisitionDownloadPhysicalSettlement.Quiescent>(drained.physical).acknowledgement
        assertSame(drained.fence, ack.fence)
        assertNotSame(retained.fence, ack.fence)
        assertEquals(retained.fence.generation, ack.fence.generation)
        assertIs<AcquisitionDownloadDurableSettlement.Confirmed>(drained.durable)
        assertEquals(1, repository.publications.size)
        assertEquals(0, leases.open.get())
    }

    @Test fun failedDurableFinalizerIsUnconfirmedEvenAfterARealPhysicalQuiescenceAck() = physicalRuntimeCase {
        finalizerFailure(PhysicalFinishBehavior.UNCONFIRMED)
    }

    @Test fun staleDurableFinalizerIsNotLaunderedByTheEarlierSuccessfulIntentCommit() = physicalRuntimeCase {
        finalizerFailure(PhysicalFinishBehavior.STALE)
    }

    @Test fun physicalDrainAwaitsActualBlockedAllowanceCloseNotJustCoroutineCancellation() = physicalRuntimeCase {
        val inTransfer = barrier()
        val closing = barrier()
        gateway.transfer = { _, _, _ -> inTransfer.stop(); error("Cancelled transfer cannot finish") }
        gate.beforeClose = { closing.stop() }
        val run = start()
        inTransfer.entered.await()
        coroutineScope {
            val draining = async { runtime.epochs.drain(run.epoch) }
            closing.entered.await()
            assertFalse(draining.isCompleted)
            assertFalse(assertNotNull(run.epoch.job).isCompleted)
            assertTrue(repository.finishes.isEmpty())
            closing.release()
            assertTrue(draining.await())
        }
        assertTrue(runtime.epochs.allLocalOwnersConsumed())
        assertEquals(0, leases.open.get())
    }

    @Test fun failedTemporarySlotCompensationStaysOwnedBeforeAnyDurableAttemptExists() = physicalRuntimeCase {
        val admissionCloses = AtomicInteger()
        leases.beforeClose = { name, _ -> if (name == "slot-admission") {
            admissionCloses.incrementAndGet()
            throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_slot_compensation"))
        } }
        assertFailsWith<VeyraException> {
            host.commands.withLock { host.activeLock.withLock { runtime.claimExecution(PhysicalRuntimeFixture.JOB) } }
        }
        val epoch = assertNotNull(runtime.epochs.current(PhysicalRuntimeFixture.JOB))
        assertTrue(repository.attemptClaims.isEmpty())
        assertEquals(1, admissionCloses.get(), "Initial rollback cannot retry an already-failed temporary close")
        assertFalse(runtime.epochs.drain(epoch))
        assertFalse(runtime.epochs.allLocalOwnersConsumed())
        assertFalse(runtime.epochs.canAdmit(PhysicalRuntimeFixture.JOB))
        leases.beforeClose = { _, _ -> }
        runtime.attemptCleanup(cleanup.owners.value.values.single() as DownloadPhysicalCleanup, retryFailed = true)
        assertTrue(runtime.epochs.drain(epoch))
        assertEquals(0, leases.open.get())
    }

    @Test fun lateAllowanceExpirationAfterPositivePublicationCannotSuspendHealthyCompletion() = physicalRuntimeCase {
        gate.beforeClose = { gate.expirations.single().invoke() }
        val run = start()
        joined(run)
        assertEquals(DownloadStatus.COMPLETE, currentJob().status)
        assertFalse(hostState.value.suspended)
        assertFalse(runtime.epochs.isRevoked(run.epoch))
        assertIs<AcquisitionDownloadDurableSettlement.Confirmed>(runtime.epochs.durableSettlement(run.epoch))
        assertEquals(0, leases.open.get())
    }

    @Test fun expirationRejectedByTheExistingHostGenerationGuardCannotRevokeTheOldEpochFirst() = physicalRuntimeCase {
        val inTransfer = barrier()
        gateway.transfer = { _, _, _ -> inTransfer.stop(); error("Cancelled transfer cannot finish") }
        val run = start()
        inTransfer.entered.await()
        hostState.value = hostState.value.copy(generation = hostState.value.generation + 1)
        gate.expirations.single().invoke()
        assertFalse(runtime.epochs.isRevoked(run.epoch))
        assertFalse(assertNotNull(run.epoch.job).isCancelled)
        assertFalse(hostState.value.suspended)
        assertTrue(runtime.epochs.drain(run.epoch))
        assertEquals(0, leases.open.get())
    }

    @Test fun cleanupReservesEveryRetiredStageAndWaitsForTheActualReaderBeforeDeleting() = physicalRuntimeCase {
        val retiredPath = files.prepareStaging(contextR1.subject.id.value, "retired")
        Files.write(Path.of(retiredPath.toString()), PHYSICAL_BYTES)
        val retired = PhysicalDownloadAttempt("retired", PhysicalRuntimeFixture.JOB, producer(contextR1), 1, 1,
            DownloadAttemptStatus.PAUSED, stagingPath = retiredPath.toString())
        repository.state.value = repository.state.value.copy(physicalDownloads = mapOf(currentJob().id to
            currentJob().copy(attemptCount = 1, ownershipGeneration = 1)), physicalAttempts = mapOf(retired.id to retired))
        val asset = seedComplete()
        val historicalReceipt = repository.state.value.materializationReceipts.getValue(asset.id)
        removeOwner()
        val reader = barrier()
        beforeDelete = { checked -> assertEquals(asset, checked); reader.stop() }
        coroutineScope {
            val work = async { runtime.reconcilePass() }
            reader.entered.await()
            val reserved = assertNotNull(repository.physicalDownload(PhysicalRuntimeFixture.JOB)?.cleanupReservation)
            assertEquals(repository.state.value.physicalAttempts.values.mapNotNull { it.stagingPath }.toSet(), reserved.stagingPaths)
            assertTrue(Files.exists(Path.of(asset.uri)))
            assertTrue(Files.exists(Path.of(retiredPath.toString())))
            assertNull(files.tryLeasePaths(setOf(retiredPath.toString())), "The actual retired-generation lock is held")
            reader.release()
            work.await()
        }
        assertFalse(Files.exists(Path.of(asset.uri)))
        assertFalse(Files.exists(Path.of(retiredPath.toString())))
        assertFalse(currentJob().cleanupPending)
        assertTrue(currentJob().removeWhenClean)
        assertEquals(retired, repository.state.value.physicalAttempts.getValue(retired.id))
        assertEquals(historicalReceipt, repository.state.value.materializationReceipts.getValue(asset.id))
        assertTrue(repository.state.value.materializations.isEmpty())
    }

    @Test fun readerReleaseFailurePreservesBytesAndDefersLocalCleanupWithoutSourceWork() = physicalRuntimeCase {
        val asset = seedComplete()
        removeOwner()
        val calls = AtomicInteger()
        beforeDelete = { calls.incrementAndGet(); throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_reader_retained")) }
        runtime.reconcilePass()
        assertTrue(Files.exists(Path.of(asset.uri)))
        assertTrue(currentJob().cleanupPending)
        assertEquals(clock.epochMs() + 5_000, currentJob().nextRetryEpochMs)
        runtime.reconcilePass()
        assertEquals(1, calls.get(), "Failed local cleanup has a bounded delay rather than an immediate loop")
        assertEquals(1, gateway.transfers.size)
    }

    @Test fun recoveryUsesTheOriginalMaterializerAttemptAndGenerationNotItsMaintenanceEpoch() = physicalRuntimeCase {
        val original = seedFinalization(published = true, attemptGeneration = 3)
        runtime.reconcilePass()
        val publication = repository.publications.single()
        assertEquals(original, publication.expectedFinalization)
        assertEquals(original.attemptId, publication.asset.materializerAttemptId)
        assertEquals(3L, publication.asset.ownershipGeneration)
        assertEquals(4L, publication.fence.ownershipGeneration)
        assertEquals(original.attemptId, publication.fence.attemptId)
        assertEquals(producer(contextR1), publication.asset.producer)
        assertEquals(DownloadStatus.COMPLETE, currentJob().status)
        assertTrue(gateway.preparations.isEmpty())
        assertTrue(gateway.transfers.isEmpty())
        assertTrue(runtime.epochs.allLocalOwnersConsumed())
        assertIs<AcquisitionDownloadDurableSettlement.Confirmed>(runtime.epochs.durableSettlement(
            assertNotNull(runtime.epochs.current(PhysicalRuntimeFixture.JOB))))
        legacy.assertUnused()
    }

    @Test fun legacyRecoveryPreservesAnUnknownOriginalAttemptInsteadOfFallingBackToTheActiveOne() = physicalRuntimeCase {
        seedLegacy(PHYSICAL_REPRESENTATION.representationId)
        val producer = currentJob().producer
        val part = files.prepareStaging(producer.subjectId.value, "legacy-unknown-finalizer")
        Files.write(Path.of(part.toString()), PHYSICAL_BYTES)
        val active = PhysicalDownloadAttempt("legacy-unrelated-active", PhysicalRuntimeFixture.JOB, producer,
            3, 4, DownloadAttemptStatus.PAUSED, finishedEpochMs = 3_000, stagingPath = part.toString(),
            representationId = PHYSICAL_REPRESENTATION.representationId)
        val original = PhysicalDownloadFinalization(PhysicalRuntimeFixture.JOB, producer, part.toString(),
            files.finalPath(producer.subjectId.value, 0, "audio/mpeg", "legacy-unknown-finalizer").toString(),
            PHYSICAL_BYTES.toByteString().sha256().hex(), PHYSICAL_BYTES.size.toLong(), attemptId = null,
            mimeType = "audio/mpeg", codecs = "mp3", representationId = PHYSICAL_REPRESENTATION.representationId,
            ownershipGeneration = 0)
        repository.state.value = repository.state.value.copy(physicalDownloads = mapOf(PhysicalRuntimeFixture.JOB to
            currentJob().copy(status = DownloadStatus.VERIFYING, activeAttemptId = active.id,
                attemptCount = active.number, ownershipGeneration = active.ownershipGeneration, stagingPath = part.toString())),
            physicalAttempts = mapOf(active.id to active), physicalFinalizations = mapOf(PhysicalRuntimeFixture.JOB to original))
        runtime.reconcilePass()
        assertNull(repository.publications.single().fence.attemptId)
        val materialized = repository.state.value.materializations.values.single()
        assertNull(materialized.materializerAttemptId)
        assertEquals(0L, materialized.ownershipGeneration)
        assertEquals(active, repository.state.value.physicalAttempts.getValue(active.id))
        assertTrue(gateway.preparations.isEmpty())
        legacy.assertUnused()
    }

    @Test fun permanentlyCorruptFinalizationDoesNotEnterAnAutomaticNativeRetryLoop() = physicalRuntimeCase {
        val original = seedFinalization()
        Files.write(Path.of(original.stagingPath), ByteArray(PHYSICAL_BYTES.size) { 1 })
        runtime.reconcilePass()
        assertEquals(FailureCode.CORRUPT_MEDIA, currentJob().failure?.code)
        assertTrue(repository.publications.isEmpty())
        assertTrue(validated.isEmpty())
        clock.advance(60_000)
        runtime.reconcilePass()
        assertEquals(1, repository.maintenanceClaims.size)
        assertTrue(gateway.preparations.isEmpty())
    }

    @Test fun hostSuspensionWritesNeedsForegroundOnlyAfterItsPhysicalOwnerHasDrained() = physicalRuntimeCase {
        val inTransfer = barrier()
        val closing = barrier()
        gateway.transfer = { _, _, _ -> inTransfer.stop(); error("Cancelled transfer cannot finish") }
        gate.beforeClose = { closing.stop() }
        val run = start()
        inTransfer.entered.await()
        hostState.value = hostState.value.copy(suspended = true, generation = 1)
        runtime.requestHostSuspension(DownloadStatus.NEEDS_FOREGROUND)
        coroutineScope {
            val work = async { runtime.epochs.drain(run.epoch) }
            closing.entered.await()
            assertEquals(DownloadStatus.RUNNING, currentJob().status)
            assertNotNull(currentJob().leaseOwner)
            assertTrue(repository.finishes.isEmpty())
            closing.release()
            assertTrue(work.await())
        }
        assertEquals(DownloadStatus.NEEDS_FOREGROUND, currentJob().status)
        assertNull(currentJob().leaseOwner)
        assertNull(runtime.drainCheckpointFailure())
        assertEquals(0, leases.open.get())
    }

    private suspend fun PhysicalRuntimeFixture.finalizerFailure(behavior: PhysicalFinishBehavior) {
        val inTransfer = barrier()
        gateway.transfer = { _, _, _ -> inTransfer.stop(); error("Cancelled transfer cannot finish") }
        repository.finishBehavior = behavior
        val run = start()
        inTransfer.entered.await()
        val (member, claim) = pauseOwner()
        val result = runtime.reconcileClaim(ReconcileAcquisitionDownload(member, claim))
        assertSame(result.fence, assertIs<AcquisitionDownloadPhysicalSettlement.Quiescent>(result.physical).acknowledgement.fence)
        if (behavior == PhysicalFinishBehavior.UNCONFIRMED)
            assertEquals(FailureCode.STORAGE_IO, assertIs<AcquisitionDownloadDurableSettlement.Unconfirmed>(result.durable).failure.code)
        else assertSame(AcquisitionDownloadDurableSettlement.Stale, result.durable)
        assertEquals(1, repository.finishes.size)
        assertTrue(runtime.epochs.drain(run.epoch))
        assertNotNull(runtime.drainCheckpointFailure())
        assertTrue(runtime.epochs.allLocalOwnersConsumed())
        assertEquals(0, leases.open.get())
    }

    private fun PhysicalRuntimeFixture.admissionCommand(): AdmitAcquisitionDownload {
        val occurrence = ExecutionOccurrence(memberR2.occurrenceId, AcquisitionOrigin.TYPED, contextR2.request.id,
            null, null, contextR2.subject.id, contextR2.binding.id, PlaybackControlsMode.PODCAST,
            OccurrenceSelectionState.BOUND, 10_000)
        return AdmitAcquisitionDownload(memberR2, BoundAcquisitionOccurrence(occurrence, contextR2.request,
            contextR2.receipt, contextR2.binding, contextR2.subject, contextR2.recording, authoredSingle(contextR2.request)), claimR2.id)
    }

    private fun PhysicalRuntimeFixture.pauseOwner(): Pair<AcquisitionDownloadMemberKey, DownloadClaim> {
        val member = memberR2.copy(intentRevision = 1)
        val claim = claimR2.copy(intent = DownloadBatchIntent.PAUSED, generation = 1, ownerIntentRevision = 1)
        repository.state.value = repository.state.value.copy(claims = mapOf(claim.id to claim))
        return member to claim
    }

    private fun PhysicalRuntimeFixture.removeOwner() {
        val claim = claimR2.copy(intent = DownloadBatchIntent.CANCELLED, retainCompletedAsset = false,
            generation = 1, ownerIntentRevision = 1)
        repository.state.value = repository.state.value.copy(claims = mapOf(claim.id to claim),
            physicalDownloads = mapOf(currentJob().id to currentJob().copy(status = DownloadStatus.CANCELLED,
                userPaused = true, cleanupPending = true, removeWhenClean = true)))
    }

    private fun prepared(context: AcquisitionExecutionContext, key: String?, representation: AcquisitionRepresentation) =
        object : PreparedAcquisitionDownload {
            override val context = context
            override val requiredRepresentationId = key
            override val representation = representation
        }
}
