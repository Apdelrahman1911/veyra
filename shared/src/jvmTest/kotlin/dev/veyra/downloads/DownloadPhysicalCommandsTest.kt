package dev.veyra.downloads

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * Real executor locks/Jobs/owned local files with FAKE Room/source/player callbacks. These
 * cases test metadata admission and barrier ordering, NOT canonical transactions, a real
 * root acquisition-action owner, actual playback-reader drain, audio or source integration.
 */
class DownloadPhysicalCommandsTest {
    @Test fun acceptedStandaloneUsesOnlyTheImmutableAcceptedIdsAndCreatesNoWorker() = physicalRuntimeCase {
        val result = runtime.userCommands.enqueueAccepted(contextR2.request.id, contextR2.binding.id) { }
        assertEquals(JOB, result)
        val accepted = repository.standaloneAdmissions.single()
        assertEquals(contextR2.request.id, accepted.requestId)
        assertEquals(contextR2.binding.id, accepted.requiredBindingId)
        assertEquals(producer(contextR1), currentJob().producer)
        assertTrue(host.active.isEmpty())
        assertTrue(gateway.preparations.isEmpty())
        assertEquals(0, gate.acquires.get())
        legacy.assertUnused()
    }

    @Test fun applicationAdmissionFailurePrecedesStandaloneIntent() = physicalRuntimeCase {
        admission = { throw VeyraException(AppFailure(FailureCode.BACKGROUND_RESTRICTED, "authored_coverage_failure")) }
        assertFailsWith<VeyraException> { runtime.userCommands.enqueueAccepted(contextR1.request.id, contextR1.binding.id) { } }
        assertTrue(repository.standaloneAdmissions.isEmpty())
        assertEquals(0, leases.open.get())
    }

    @Test fun revokedRootActionIsRecheckedAfterWaitingForTheExistingCommandsMutex() = physicalRuntimeCase {
        supervisorScope {
            var current = true
            val checks = AtomicInteger()
            host.commands.lock()
            try {
                val pending = async(start = CoroutineStart.UNDISPATCHED) {
                    runtime.userCommands.enqueueAccepted(contextR1.request.id, contextR1.binding.id) {
                        checks.incrementAndGet()
                        if (!current) throw CancellationException("Authored root action was revoked")
                    }
                }
                assertFalse(pending.isCompleted)
                current = false
                host.commands.unlock()
                assertFailsWith<CancellationException> { pending.await() }
                assertEquals(1, checks.get())
                assertTrue(repository.standaloneAdmissions.isEmpty())
            } finally { if (host.commands.isLocked) host.commands.unlock() }
        }
    }

    @Test fun cancellationAfterDurableAcceptanceRequiresExplicitOwnerIntentReconciliation() = physicalRuntimeCase {
        supervisorScope {
            val identity = CompletableDeferred<Job>()
            repository.afterStandaloneAdmission = { identity.await().cancel() }
            val request = async(start = CoroutineStart.LAZY) {
                runtime.userCommands.enqueueAccepted(contextR1.request.id, contextR1.binding.id) { }
            }
            identity.complete(request)
            request.start()
            assertFailsWith<CancellationException> { request.await() }
            val accepted = repository.state.value.claims.values.single { it.owner is DownloadClaimOwner.Standalone }
            assertEquals(DownloadBatchIntent.ACTIVE, accepted.intent)
            // This is the required root-owned follow-up, not a claimed root integration test.
            runtime.userCommands.stop(JOB, discard = false, remove = false)
            assertEquals(DownloadBatchIntent.PAUSED, repository.state.value.claims.getValue(accepted.id).intent)
            assertTrue(host.active.isEmpty())
            assertTrue(gateway.preparations.isEmpty())
        }
    }

    @Test fun genuinelyNewCatalogSelectionCannotFallBackToALockHeldFirstLookup() = physicalRuntimeCase {
        val item = authoredMedia("new-catalogue")
        catalog.value = AppSnapshot(media = mapOf(item.id to item))
        val failure = assertFailsWith<VeyraException> { runtime.userCommands.enqueueKnownCatalog(item, null) }.failure
        assertEquals(FailureCode.RECOVERY_REQUIRED, failure.code)
        assertEquals("download_acquisition_selection_required", failure.detail)
        assertTrue(repository.standaloneAdmissions.isEmpty())
        assertTrue(repository.schema2Admissions.isEmpty())
        legacy.assertUnused()
    }

    @Test fun knownSchema2PlaybackReceiptCanStartANewDownloadWithoutAnyPriorDownloadRow() = physicalRuntimeCase {
        val binding = seedLegacy("saved-nondefault-key")
        val item = catalog.value.media.getValue(binding.mediaId)
        repository.state.value = repository.state.value.copy(physicalDownloads = emptyMap(), claims = emptyMap())
        val receipts = repository.state.value.receipts
        val result = runtime.userCommands.enqueueKnownCatalog(item, binding.id)
        val job = repository.state.value.physicalDownloads.getValue(result)
        assertEquals(PhysicalDownloadProducer.Schema2(item.id, ExecutionBindingId(binding.id), SourceRecordingId("legacy-c")), job.producer)
        assertEquals(receipts, repository.state.value.receipts)
        assertTrue(repository.state.value.requests.isEmpty(), "No fabricated typed request is required for migrated receipt reuse")
        assertEquals(binding.id, repository.schema2Admissions.single().requiredBindingId.value)
        assertEquals(0, gate.acquires.get())
        legacy.assertUnused()
    }

    @Test fun savedSchema2R2NeverRelabelsTheExistingPhysicalR1OrItsStandaloneClaim() = physicalRuntimeCase {
        val r1 = seedLegacy()
        val job = currentJob()
        val owner = repository.state.value.claims.values.single()
        val executionR1 = repository.state.value.bindings.getValue(ExecutionBindingId(r1.id))
        val receiptR1 = repository.state.value.receipts.getValue(executionR1.firstReceiptId) as FirstResultReceipt.Schema2
        val r2 = r1.copy(id = "legacy-r2", queryTitle = "Second explicit historical title — عنوان مختلف")
        val receiptR2 = receiptR1.copy(id = FirstResultReceiptId("legacy-r2-receipt"), queryTitle = r2.queryTitle)
        val executionR2 = executionR1.copy(id = ExecutionBindingId(r2.id), firstReceiptId = receiptR2.id)
        repository.savedLegacyBindings[r2.id] = r2
        repository.state.value = repository.state.value.copy(bindings = repository.state.value.bindings + (executionR2.id to executionR2),
            receipts = repository.state.value.receipts + (receiptR2.id to receiptR2))
        catalog.value = catalog.value.copy(playbackBindings = catalog.value.playbackBindings + (r2.id to r2))
        assertEquals(JOB, runtime.userCommands.enqueueKnownCatalog(catalog.value.media.getValue(r1.mediaId), r2.id))
        assertEquals(job, currentJob())
        assertEquals(owner, repository.state.value.claims.getValue(owner.id))
        assertEquals(r2.id, repository.schema2Admissions.single().requiredBindingId.value)
        legacy.assertUnused()
    }

    @Test fun savedSchema2R2MayShareAnActualTypedR1WithoutChangingEitherOrigin() = physicalRuntimeCase {
        val saved = seedLegacy()
        val item = catalog.value.media.getValue(saved.mediaId)
        val typed = physicalMediaContext("typed-materializer", item, SourceRecordingId("legacy-c"))
        repository.addContext(typed)
        repository.state.value = repository.state.value.copy(physicalDownloads = mapOf(JOB to currentJob().copy(producer = producer(typed))))
        val claim = standaloneOnly(typed.binding.id)
        assertEquals(JOB, runtime.userCommands.enqueueKnownCatalog(item, saved.id))
        assertEquals(producer(typed), currentJob().producer)
        assertEquals(claim, repository.state.value.claims.getValue(claim.id))
        assertEquals(ExecutionBindingOrigin.SCHEMA2_EXECUTABLE, repository.state.value.bindings.getValue(ExecutionBindingId(saved.id)).origin)
        assertTrue(gateway.preparations.isEmpty())
        legacy.assertUnused()
    }

    @Test fun duplicateAdmissionDoesNotSilentlyReviveAPausedStandaloneClaim() = physicalRuntimeCase {
        val original = standaloneOnly(intent = DownloadBatchIntent.PAUSED)
        runtime.userCommands.enqueueAccepted(contextR2.request.id, contextR2.binding.id) { }
        assertEquals(original, repository.state.value.claims.getValue(original.id))
        assertTrue(repository.standaloneIntents.isEmpty())
    }

    @Test fun explicitResumeUsesTheExactOwnerGenerationAndRetryAdmission() = physicalRuntimeCase {
        val original = standaloneOnly(intent = DownloadBatchIntent.PAUSED)
        repository.state.value = repository.state.value.copy(physicalDownloads = mapOf(JOB to currentJob().copy(
            userPaused = true, status = DownloadStatus.FAILED, failure = AppFailure(FailureCode.NETWORK_UNAVAILABLE))))
        runtime.userCommands.resume(JOB)
        val command = repository.standaloneIntents.single()
        assertEquals(original.id, command.claimId)
        assertEquals(original.generation, command.expectedClaimGeneration)
        assertEquals(original.ownerIntentRevision, command.expectedOwnerIntentRevision)
        assertTrue(command.allowRetryFailure)
        assertEquals(DownloadBatchIntent.ACTIVE, repository.state.value.claims.getValue(original.id).intent)
        assertFalse(currentJob().userPaused)
        assertEquals(DownloadStatus.QUEUED, currentJob().status)
        assertTrue(host.active.isEmpty())
    }

    @Test fun resumeCoverageFailureLeavesThePausedOwnerUntouched() = physicalRuntimeCase {
        val original = standaloneOnly(intent = DownloadBatchIntent.PAUSED)
        admission = { throw VeyraException(AppFailure(FailureCode.BACKGROUND_RESTRICTED)) }
        assertFailsWith<VeyraException> { runtime.userCommands.resume(JOB) }
        assertEquals(original, repository.state.value.claims.getValue(original.id))
        assertTrue(repository.standaloneIntents.isEmpty())
    }

    @Test fun pausingOneStandaloneCannotCancelAnotherActiveBatchOwner() = physicalRuntimeCase {
        runtime.userCommands.enqueueAccepted(contextR1.request.id, contextR1.binding.id) { }
        val transfer = barrier()
        gateway.transfer = { _, _, _ -> transfer.stop(); error("Authored transfer is cancelled during fixture cleanup") }
        val run = start()
        transfer.entered.await()
        runtime.userCommands.stop(JOB, discard = false, remove = false)
        assertTrue(assertNotNull(run.epoch.job).isActive)
        assertEquals(DownloadBatchIntent.ACTIVE, repository.state.value.claims.getValue(claimR2.id).intent)
        assertFalse(currentJob().userPaused)
    }

    @Test fun standaloneStopJoinsTheActualWriterOutsideBothAdmissionLocks() = physicalRuntimeCase {
        standaloneOnly()
        val transfer = barrier()
        gateway.transfer = { _, _, _ -> transfer.stop(); error("Stopped transfer cannot complete") }
        val run = start()
        transfer.entered.await()
        gate.beforeClose = {
            assertTrue(host.commands.tryLock(), "Drain cannot hold commands across native close")
            host.commands.unlock()
            assertTrue(host.activeLock.tryLock(), "Drain cannot hold activeLock across native close")
            host.activeLock.unlock()
        }
        runtime.userCommands.stop(JOB, discard = false, remove = false)
        assertTrue(assertNotNull(run.epoch.job).isCompleted)
        assertTrue(runtime.epochs.drain(run.epoch))
        assertEquals(DownloadStatus.PAUSED, currentJob().status)
        assertEquals(0, leases.open.get())
    }

    @Test fun aScopedCleanupNeverAcquiresANewerOwnersGenerationAfterTheOriginalJoin() = physicalRuntimeCase {
        standaloneOnly(intent = DownloadBatchIntent.CANCELLED, retain = false)
        val original = assertNotNull(repository.physicalDownload(JOB))
        repository.state.value = repository.state.value.copy(physicalDownloads = mapOf(JOB to currentJob().copy(
            ownershipGeneration = original.job.ownershipGeneration + 1, cleanupPending = true)))
        assertNull(runtime.cleanupStoppedStandalone(original))
        assertTrue(repository.maintenanceClaims.isEmpty())
        assertEquals(0, leases.open.get())
    }

    @Test fun standaloneR2UseRetainsTheExactR1AssetAndOriginalMaterializerHistory() = physicalRuntimeCase {
        val owner = standaloneOnly()
        val asset = seedComplete()
        val originalAttempts = repository.state.value.physicalAttempts
        val use = runtime.validateStandaloneAssetUse(JOB, contextR2.binding.id)
        assertEquals(contextR2.binding.id, use.requestedBindingId)
        assertEquals(asset.id, use.materializationAssetId)
        assertEquals(asset, repository.state.value.materializations.getValue(asset.id))
        assertEquals(originalAttempts, repository.state.value.physicalAttempts)
        val command = repository.standaloneUses.single()
        assertEquals(owner.id, command.claimId)
        assertEquals(owner.generation, command.expectedClaimGeneration)
        assertEquals(asset, command.expectedAsset)
        assertEquals(1, gateway.transfers.size)
    }

    @Test fun wrongStandaloneRecordingIsRejectedBeforeHashNativeValidationAndDoesNotPoisonR1() = physicalRuntimeCase {
        standaloneOnly()
        val asset = seedComplete()
        val priorChecks = validated.size
        val incompatible = contextR2.binding.copy(recordingId = SourceRecordingId("incompatible-recording"))
        repository.state.value = repository.state.value.copy(bindings = repository.state.value.bindings + (incompatible.id to incompatible))
        assertEquals(FailureCode.INVALID_STATE,
            assertFailsWith<VeyraException> { runtime.validateStandaloneAssetUse(JOB, incompatible.id) }.failure.code)
        assertEquals(priorChecks, validated.size)
        assertEquals(DownloadStatus.COMPLETE, currentJob().status)
        assertNull(currentJob().failure)
        assertEquals(asset, repository.state.value.materializations.getValue(asset.id))
        assertTrue(repository.standaloneUses.isEmpty())
    }

    @Test fun claimRevisionChangeDuringStandaloneValidationCannotPublishAUseOrFailTheSharedFile() = physicalRuntimeCase {
        val owner = standaloneOnly()
        seedComplete()
        validation = {
            repository.state.value = repository.state.value.copy(claims = mapOf(owner.id to owner.copy(
                generation = owner.generation + 1, ownerIntentRevision = owner.ownerIntentRevision + 1)))
            AssetValidation.Valid(733)
        }
        assertEquals(FailureCode.INVALID_STATE,
            assertFailsWith<VeyraException> { runtime.validateStandaloneAssetUse(JOB, contextR2.binding.id) }.failure.code)
        assertTrue(repository.state.value.assetUses.isEmpty())
        assertEquals(owner.generation, repository.standaloneUses.single().expectedClaimGeneration)
        assertEquals(DownloadStatus.COMPLETE, currentJob().status)
        assertNull(currentJob().failure)
    }

    @Test fun unknownLegacyAdoptionRequiresRealReaderCallbackEvenWithoutAnyAssetRow() = physicalRuntimeCase {
        val selected = seedUnknownLegacy(withBinding = false)
        val original = currentJob().producer
        var readers = 0
        beforeAdoption = { old ->
            readers++
            assertEquals(original, old)
            assertTrue(repository.state.value.materializations.isEmpty())
            assertTrue(host.commands.tryLock()); host.commands.unlock()
            assertTrue(host.activeLock.tryLock()); host.activeLock.unlock()
            val claim = repository.state.value.claims.values.single()
            assertEquals(DownloadBatchIntent.CANCELLED, claim.intent)
            assertFalse(claim.retainCompletedAsset)
        }
        repository.beforeAdoptionCommit = {
            assertEquals(5, leases.open.get(), "Admission plus ALL four existing slots are held")
            assertNull(files.tryTransferSlot(4), "The existing writer admission namespace is fenced")
        }
        assertEquals(JOB, runtime.userCommands.adoptSchema2(JOB, selected.request.id, selected.binding.id) { })
        assertEquals(1, readers)
        assertEquals(producer(selected), currentJob().producer)
        assertNull(repository.state.value.claims.values.single().requestedBindingId, "Unknown historical claim lineage stays unknown")
        assertEquals(0, leases.open.get())
        assertTrue(runtime.epochs.allLocalOwnersConsumed())
        assertTrue(gateway.preparations.isEmpty())
        legacy.assertUnused()
    }

    @Test fun adoptionCleansEveryRetiredDirectoryButPreservesHistoricalReceiptAttemptAndClaim() = physicalRuntimeCase {
        val selected = seedUnknownLegacy(withStage = true)
        val history = repository.state.value.physicalAttempts
        val receipts = repository.state.value.receipts
        val originalClaim = repository.state.value.claims.values.single()
        val path = Path.of(checkNotNull(history.values.single().stagingPath))
        beforeAdoption = {
            assertFalse(Files.exists(path.parent), "Not just transfer.part: the journal and whole generation are gone")
            assertFalse(currentJob().cleanupPending)
        }
        runtime.userCommands.adoptSchema2(JOB, selected.request.id, selected.binding.id) { }
        assertEquals(history, repository.state.value.physicalAttempts)
        assertEquals(receipts, repository.state.value.receipts)
        val claim = repository.state.value.claims.values.single()
        assertEquals(originalClaim.id, claim.id)
        assertEquals(originalClaim.owner, claim.owner)
        assertEquals(originalClaim.requestedBindingId, claim.requestedBindingId)
        assertEquals(DownloadBatchIntent.ACTIVE, claim.intent)
        assertEquals(0L, currentJob().bytesDownloaded)
        assertNull(currentJob().representationId)
        assertEquals(1, repository.adoptions.size)
    }

    @Test fun readerDrainFailureCannotIssueAnAdoptionReceiptOrReviveTheOldOwner() = physicalRuntimeCase {
        val selected = seedUnknownLegacy()
        val producer = currentJob().producer
        beforeAdoption = { throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_native_reader_retained")) }
        assertFailsWith<VeyraException> { runtime.userCommands.adoptSchema2(JOB, selected.request.id, selected.binding.id) { } }
        assertEquals(producer, currentJob().producer)
        assertEquals(DownloadBatchIntent.CANCELLED, repository.state.value.claims.values.single().intent)
        assertFalse(repository.state.value.claims.values.single().retainCompletedAsset)
        assertTrue(repository.adoptions.isEmpty())
        assertEquals(0, leases.open.get())
    }

    @Test fun revokedRootActionAfterReaderSuspensionCannotResurrectTheCancelledLegacyJob() = physicalRuntimeCase {
        val selected = seedUnknownLegacy()
        var current = true
        beforeAdoption = { current = false }
        assertFailsWith<CancellationException> {
            runtime.userCommands.adoptSchema2(JOB, selected.request.id, selected.binding.id) {
                if (!current) throw CancellationException("Authored replacement action revoked")
            }
        }
        assertTrue(repository.adoptions.isEmpty())
        assertTrue(currentJob().producer is PhysicalDownloadProducer.Schema2)
        assertEquals(DownloadBatchIntent.CANCELLED, repository.state.value.claims.values.single().intent)
        assertEquals(0, leases.open.get())
    }

    @Test fun aClaimMutationAfterTheActualReaderDrainInvalidatesTheEntireAdoptionJoin() = physicalRuntimeCase {
        val selected = seedUnknownLegacy()
        beforeAdoption = {
            val claim = repository.state.value.claims.values.single()
            repository.state.value = repository.state.value.copy(claims = mapOf(claim.id to claim.copy(generation = claim.generation + 1)))
        }
        assertEquals("download_adoption_owner_changed", assertFailsWith<VeyraException> {
            runtime.userCommands.adoptSchema2(JOB, selected.request.id, selected.binding.id) { }
        }.failure.detail)
        assertTrue(repository.adoptions.isEmpty())
        assertEquals(0, leases.open.get())
    }

    @Test fun aLiveRetiredHighNativeSlotRejectsAdoptionWithoutCancellingAnUnrelatedJob() = physicalRuntimeCase {
        val selected = seedUnknownLegacy()
        hostState.value = hostState.value.copy(parallelism = 1)
        val unrelated = scope.launch { awaitCancellation() }
        val high = assertNotNull(leases.acquire((files.root / ".locks" / "slot-3").toString()))
        host.activeLock.withLock { host.active["unrelated-job"] = unrelated }
        try {
            assertEquals("download_adoption_busy", assertFailsWith<VeyraException> {
                runtime.userCommands.adoptSchema2(JOB, selected.request.id, selected.binding.id) { }
            }.failure.detail)
            assertTrue(unrelated.isActive)
            assertTrue(repository.adoptions.isEmpty())
            assertEquals(1, leases.open.get(), "Only the unrelated native slot remains owned")
            assertTrue(runtime.epochs.allLocalOwnersConsumed())
        } finally {
            high.close()
            unrelated.cancelAndJoin()
            host.activeLock.withLock { host.active.remove("unrelated-job") }
        }
    }

    @Test fun aRetiredGenerationPathLockMustStillBeHeldAcrossAdoptionAfterCleanup() = physicalRuntimeCase {
        val selected = seedUnknownLegacy(withStage = true)
        val retired = checkNotNull(repository.state.value.physicalAttempts.values.single().stagingPath)
        var readerLease: DownloadFileLease? = null
        beforeAdoption = { readerLease = assertNotNull(files.tryLeasePaths(setOf(retired))) }
        try {
            assertEquals("download_adoption_busy", assertFailsWith<VeyraException> {
                runtime.userCommands.adoptSchema2(JOB, selected.request.id, selected.binding.id) { }
            }.failure.detail)
            assertTrue(repository.adoptions.isEmpty())
        } finally { readerLease?.close() }
    }

    @Test fun aRemainingJournalWithoutTransferPartIsNotZeroStagingProof() = physicalRuntimeCase {
        val selected = seedUnknownLegacy(withStage = true)
        val directory = Path.of(checkNotNull(repository.state.value.physicalAttempts.values.single().stagingPath)).parent
        beforeAdoption = {
            Files.createDirectories(directory)
            Files.writeString(directory.resolve("authored-journal-only"), "Authored leftover, no source credentials")
        }
        assertEquals("download_legacy_staging_remains", assertFailsWith<VeyraException> {
            runtime.userCommands.adoptSchema2(JOB, selected.request.id, selected.binding.id) { }
        }.failure.detail)
        assertTrue(repository.adoptions.isEmpty())
        assertEquals(0, leases.open.get())
    }

    @Test fun unusedReceiptIsInvalidatedBeforeNativeBarrierReleaseOnCanonicalFailure() = physicalRuntimeCase {
        val selected = seedUnknownLegacy()
        var heldReceipt: PhysicalDownloadDrainReceipt? = null
        var priorJob: PhysicalDownloadJob? = null
        var priorClaims: List<DownloadClaim>? = null
        repository.beforeAdoptionCommit = { command ->
            heldReceipt = command.drainReceipt
            priorJob = currentJob()
            priorClaims = repository.state.value.claims.values.toList()
            throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_adoption_commit_failure"))
        }
        assertFailsWith<VeyraException> { runtime.userCommands.adoptSchema2(JOB, selected.request.id, selected.binding.id) { } }
        assertEquals(0, leases.open.get())
        assertFailsWith<IllegalArgumentException> {
            assertNotNull(heldReceipt).consume(repository.transactionOwner, assertNotNull(priorJob), assertNotNull(priorClaims))
        }
        assertTrue(repository.adoptions.isEmpty())
        assertTrue(runtime.epochs.allLocalOwnersConsumed())
    }

    @Test fun failedAdoptionBarrierCloseRetainsTheExactOwnerAndBlocksNewAdmissionUntilExplicitCleanup() = physicalRuntimeCase {
        val selected = seedUnknownLegacy()
        val failedCloses = AtomicInteger()
        leases.beforeClose = { name, _ ->
            if (name == "slot-2") { failedCloses.incrementAndGet(); throw IllegalStateException("Authored native close failure") }
        }
        runtime.userCommands.adoptSchema2(JOB, selected.request.id, selected.binding.id) { }
        val epoch = assertNotNull(runtime.epochs.current(JOB))
        assertEquals(1, repository.adoptions.size, "Durable adoption is independent of physical release")
        assertEquals(producer(selected), currentJob().producer)
        assertFalse(runtime.epochs.canAdmit(JOB))
        assertFalse(runtime.epochs.drain(epoch))
        assertEquals(1, failedCloses.get(), "Drain never retries a failed native close")
        assertTrue(hostState.value.suspended)
        assertTrue(leases.open.get() > 0)
        leases.beforeClose = { _, _ -> }
        runtime.attemptCleanup(cleanup.owners.value.values.single() as DownloadPhysicalCleanup, retryFailed = true)
        assertTrue(runtime.epochs.drain(epoch))
        assertEquals(0, leases.open.get())
    }

    @Test fun aMismatchedReplacementCannotAcceptDestructiveOldOwnerIntent() = physicalRuntimeCase {
        seedUnknownLegacy(withStage = true)
        val original = currentJob()
        val claim = repository.state.value.claims.values.single()
        assertFailsWith<VeyraException> {
            runtime.userCommands.adoptSchema2(JOB, contextR2.request.id, contextR2.binding.id) { }
        }
        assertEquals(original, currentJob())
        assertEquals(claim, repository.state.value.claims.values.single())
        assertTrue(repository.standaloneIntents.isEmpty())
        assertTrue(repository.adoptions.isEmpty())
    }

    private companion object { const val JOB = PhysicalRuntimeFixture.JOB }
}
