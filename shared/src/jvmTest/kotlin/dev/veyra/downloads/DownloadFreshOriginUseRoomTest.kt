package dev.veyra.downloads

import androidx.sqlite.SQLiteException
import androidx.sqlite.execSQL
import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.playback.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real canonical Room/coordinator/files/OS locks; FAKE decoder/source/allowance, authored NON-AUDIO. */
class DownloadFreshOriginUseRoomTest {
    @Test fun freshStandalonePublishesItsExactOriginUseWithoutAnExtraValidateCommand() = acquisitionDownloadRoomCase {
        val owner = owner()
        val accepted = acceptNeutral(owner)
        val binding = assertIs<AcquisitionOutcome.Single>(accepted.outcome).bindingId
        val id = enqueue(owner, accepted)
        val claims = owner.store.acquisitionView().acquisition.claims
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val settled = awaitOriginUsePublication(owner, id).acquisition
        val use = assertOnlyOriginUse(settled, id)
        val asset = settled.materializations.getValue(use.materializationAssetId)
        val attempt = settled.physicalAttempts.getValue(assertNotNull(asset.materializerAttemptId))
        assertEquals(binding, use.requestedBindingId)
        assertEquals(DownloadAttemptStatus.SUCCEEDED, attempt.status)
        assertNotNull(attempt.finishedEpochMs)
        assertEquals(attempt.producer, asset.producer)
        assertEquals(attempt.ownershipGeneration, asset.ownershipGeneration)
        assertEquals(claims, settled.claims, "Publication cannot change owner intent or claim generations")
        assertEquals(1, owner.gateway.preparations.size)
        assertEquals(1, owner.gateway.transfers.size)
        assertEquals(1, owner.validations.size, "Fresh publication uses its actual decoder check, not a second Validate workaround")
        assertTrue(owner.selector.requests.isEmpty())
        assertEquals(0, leases.open.get())
    }

    @Test fun freshBatchOriginBecomesAvailableInTheSamePublicationAsItsVerifiedReceipt() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = enrollOriginUseBatch(owner)
        val before = owner.store.acquisitionView().acquisition
        val id = before.claims.getValue(assertNotNull(batch.members.single().claimId)).physicalJobId
        val publications = AtomicInteger()
        faults(owner.store).beforeCommit = { old, next ->
            if (old.state().physicalDownloads[id]?.assetId == null && next.state().physicalDownloads[id]?.assetId != null) {
                val proposed = next.state()
                assertOnlyOriginUse(proposed, id)
                assertEquals(DownloadBatchMemberState.AVAILABLE, proposed.batches.getValue(batch.id).members.single().state)
                publications.incrementAndGet()
            }
        }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val settled = awaitOriginUsePublication(owner, id).acquisition
        assertEquals(1, publications.get())
        assertOnlyOriginUse(settled, id)
        assertEquals(DownloadBatchMemberState.AVAILABLE, settled.batches.getValue(batch.id).members.single().state)
        assertEquals(before.claims, settled.claims)
        assertEquals(batch.intentRevision, settled.batches.getValue(batch.id).intentRevision)
        assertEquals(1, owner.validations.size)
        awaitCondition { owner.coordinator.acquisitionPresentation.value.states[id]?.status == DownloadStatus.COMPLETE }
    }

    @Test fun enrolledDistinctR2StaysCheckingUntilItsOwnExactLocalValidationWithoutAnotherTransfer() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = enrollOriginUseBatch(owner, count = 2)
        val before = owner.store.acquisitionView().acquisition
        val job = before.physicalDownloads.values.single()
        val second = batch.members.single { it.requiredBindingId != job.producer.bindingId }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val fresh = awaitOriginUsePublication(owner, job.id).acquisition
        val originUse = assertOnlyOriginUse(fresh, job.id)
        val asset = fresh.materializations.getValue(originUse.materializationAssetId)
        assertEquals(DownloadBatchMemberState.AVAILABLE,
            fresh.batches.getValue(batch.id).members.single { it.requiredBindingId == job.producer.bindingId }.state)
        assertEquals(DownloadBatchMemberState.VERIFYING,
            fresh.batches.getValue(batch.id).members.single { it.position == second.position }.state)
        assertTrue(fresh.assetUses.none { it.requestedBindingId == second.requiredBindingId })
        assertEquals(before.claims, fresh.claims)
        assertEquals(1, owner.gateway.transfers.size)
        assertEquals(1, owner.validations.size)
        owner.coordinator.start(DownloadActivation.USER_SESSION)
        val checked = assertIs<AcquisitionDownloadAssetCheck.Validated>(owner.adapter.validateMemberAsset(
            batch.id, second.position, batch.intentRevision, asset.id))
        idle(owner)
        val after = owner.store.acquisitionView().acquisition
        assertEquals(second.requiredBindingId, checked.validation.use.requestedBindingId)
        assertEquals(asset.id, checked.validation.use.materializationAssetId)
        assertEquals(setOf(originUse, checked.validation.use), after.assetUses)
        assertEquals(fresh.materializations, after.materializations)
        assertEquals(fresh.materializationReceipts, after.materializationReceipts)
        assertEquals(fresh.physicalAttempts, after.physicalAttempts)
        assertEquals(before.claims, after.claims)
        assertTrue(after.batches.getValue(batch.id).members.all { it.state == DownloadBatchMemberState.AVAILABLE })
        assertEquals(2, owner.validations.size)
        assertEquals(1, owner.gateway.transfers.size)
        assertTrue(owner.selector.requests.isEmpty())
    }

    @Test fun laterDistinctR2AdmissionCannotInheritTheEarlierOriginUse() = acquisitionDownloadRoomCase {
        val owner = owner()
        val first = enrollOriginUseBatch(owner)
        val id = owner.store.acquisitionView().acquisition.physicalDownloads.values.single().id
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val original = awaitOriginUsePublication(owner, id).acquisition
        val use = assertOnlyOriginUse(original, id)
        // Stop autonomous admission without closing the actual owner; the new request is explicit.
        offline(owner)
        val later = enrollOriginUseBatch(owner)
        idle(owner)
        val after = owner.store.acquisitionView().acquisition
        val laterMember = after.batches.getValue(later.id).members.single()
        assertNotEquals(first.members.single().requiredBindingId, laterMember.requiredBindingId)
        assertEquals(id, after.claims.getValue(assertNotNull(laterMember.claimId)).physicalJobId)
        assertEquals(setOf(use), after.assetUses)
        assertEquals(DownloadBatchMemberState.VERIFYING, laterMember.state)
        assertEquals(original.materializations, after.materializations)
        assertEquals(1, owner.gateway.transfers.size)
        assertEquals(1, owner.validations.size)
    }

    @Test fun persistedOriginUseDoesNotSubstituteForCurrentProcessCheckingAfterOfflineRestart() = acquisitionDownloadRoomCase {
        val old = owner()
        val batch = enrollOriginUseBatch(old)
        val id = old.store.acquisitionView().acquisition.physicalDownloads.values.single().id
        old.coordinator.start(DownloadActivation.DURABLE_WORK)
        val finished = awaitOriginUsePublication(old, id).acquisition
        val use = assertOnlyOriginUse(finished, id)
        old.coordinator.close()
        old.store.close()
        val resumed = owner()
        offline(resumed)
        resumed.coordinator.initializeScheduling()
        awaitCondition { resumed.coordinator.acquisitionPresentation.value.states[id]?.status == DownloadStatus.VERIFYING }
        val cold = resumed.store.acquisitionView().acquisition
        assertEquals(setOf(use), cold.assetUses)
        assertEquals(DownloadBatchMemberState.AVAILABLE, cold.batches.getValue(batch.id).members.single().state,
            "Persisted member metadata is separate from this process's checked physical presentation")
        resumed.coordinator.start(DownloadActivation.DURABLE_WORK)
        idle(resumed)
        assertTrue(resumed.validations.isEmpty())
        assertEquals(DownloadStatus.VERIFYING, resumed.coordinator.acquisitionPresentation.value.states.getValue(id).status)
        resumed.coordinator.start(DownloadActivation.USER_SESSION)
        awaitCondition { resumed.coordinator.acquisitionPresentation.value.states[id]?.status == DownloadStatus.COMPLETE }
        idle(resumed)
        val rechecked = resumed.store.acquisitionView().acquisition
        assertEquals(setOf(use), rechecked.assetUses, "Checking must not rewrite the origin's historical use time")
        assertEquals(finished.materializations, rechecked.materializations)
        assertEquals(finished.materializationReceipts, rechecked.materializationReceipts)
        assertEquals(finished.physicalAttempts, rechecked.physicalAttempts)
        assertEquals(1, resumed.validations.size)
        assertTrue(resumed.gateway.preparations.isEmpty())
        assertTrue(resumed.selector.requests.isEmpty())
        resumed.legacy.assertUnused()
    }

    @Test fun cancelledNonretainingOriginDuringValidationGetsNoUseWhileActiveR2KeepsTheTransfer() =
        originIntentChange(DownloadBatchIntent.CANCELLED, retain = false)

    @Test fun pausedOriginDuringValidationRetainsOnlyItsOwnUseWithoutResumingItsIntent() =
        originIntentChange(DownloadBatchIntent.PAUSED, retain = true)

    @Test fun cancelledRetainingOriginDuringValidationKeepsItsUseWithoutGrantingActiveR2() =
        originIntentChange(DownloadBatchIntent.CANCELLED, retain = true)

    @Test fun anotherKeepingClaimForTheSameR1CanReceiveOneUseWithoutRevivingTheRemovedMember() = acquisitionDownloadRoomCase {
        val owner = owner()
        val first = enrollOriginUseBatch(owner)
        val second = repeatOriginUseBatch(owner, first)
        assertEquals(first.members.single().requiredBindingId, second.members.single().requiredBindingId)
        val id = owner.store.acquisitionView().acquisition.physicalDownloads.values.single().id
        val held = barrier()
        owner.validation = { held.stop(); AssetValidation.Valid(733) }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        withTimeout(10_000) { held.entered.await() }
        owner.batches.setIntent(first.id, first.intentRevision, DownloadBatchIntent.CANCELLED, retainCompletedAssets = false)
        val changedClaims = owner.store.acquisitionView().acquisition.claims
        held.release()
        val published = awaitOriginUsePublication(owner, id).acquisition
        assertOnlyOriginUse(published, id)
        assertEquals(changedClaims, published.claims)
        assertEquals(DownloadBatchMemberState.CANCELLED, published.batches.getValue(first.id).members.single().state)
        assertEquals(DownloadBatchMemberState.AVAILABLE, published.batches.getValue(second.id).members.single().state)
        assertEquals(DownloadBatchIntent.CANCELLED, published.batches.getValue(first.id).intent)
        assertEquals(DownloadBatchIntent.ACTIVE, published.batches.getValue(second.id).intent)
        assertEquals(1, owner.gateway.transfers.size)
        assertEquals(1, owner.validations.size)
    }

    @Test fun actualLateSqlUseInsertFailureRollsBackTheWholePublicationAndReopenRecoversIt() = acquisitionDownloadRoomCase {
        val old = owner()
        val batch = enrollOriginUseBatch(old)
        val id = old.store.acquisitionView().acquisition.physicalDownloads.values.single().id
        val seed = stageOriginUsePublication(old, id)
        val before = old.store.acquisitionView()
        val beforeGraph = old.store.readAcquisition { it }
        val proposedPublications = AtomicInteger()
        faults(old.store).beforeCommit = { _, next ->
            if (next.state().physicalDownloads.getValue(id).status == DownloadStatus.COMPLETE) {
                assertOnlyOriginUse(next.state(), id)
                assertEquals(DownloadBatchMemberState.AVAILABLE, next.state().batches.getValue(batch.id).members.single().state)
                proposedPublications.incrementAndGet()
            }
        }
        originUseSql(databasePath) { db -> db.execSQL("""
            CREATE TRIGGER authored_origin_use_rollback AFTER INSERT ON asset_uses
            BEGIN SELECT RAISE(ABORT, 'authored-origin-use-rollback'); END
        """.trimIndent()) }
        try {
            val failure = assertFailsWith<SQLiteException> {
                old.repository.publishPhysicalDownloadFinalization(seed.command(clock.epochMs()))
            }
            assertTrue(failure.message.orEmpty().contains("authored-origin-use-rollback"))
            assertEquals(1, proposedPublications.get(), "The exact-use mutation must reach the real SQL insert fault")
            assertEquals(before, old.store.acquisitionView())
            val rolledBack = old.store.readAcquisition { it }
            assertEquals(beforeGraph.revision, rolledBack.revision)
            assertTrue(beforeGraph.sameRelations(rolledBack), "Asset/receipt/use/attempt/finalization/job/batch all roll back")
        } finally {
            originUseSql(databasePath) { it.execSQL("DROP TRIGGER authored_origin_use_rollback") }
            faults(old.store).beforeCommit = { _, _ -> }
        }
        old.coordinator.close()
        old.store.close()
        val resumed = owner()
        assertEquals(before, resumed.store.acquisitionView())
        clock.advance(6_000) // The authored dead process's lease expires; no native owner is being bypassed.
        offline(resumed)
        resumed.coordinator.start(DownloadActivation.DURABLE_WORK)
        val recovered = awaitOriginUsePublication(resumed, id).acquisition
        assertOnlyOriginUse(recovered, id)
        assertEquals(seed.intent.assetId, recovered.physicalDownloads.getValue(id).assetId)
        assertEquals(1, resumed.validations.size)
        assertTrue(resumed.gateway.preparations.isEmpty())
        assertTrue(resumed.selector.requests.isEmpty())
    }

    @Test fun retiredPublisherCannotGrantAUseAndRecoveryKeepsTheOriginalMaterializerGeneration() = acquisitionDownloadRoomCase {
        val owner = owner()
        enrollOriginUseBatch(owner)
        val id = owner.store.acquisitionView().acquisition.physicalDownloads.values.single().id
        val seed = stageOriginUsePublication(owner, id, retire = true)
        val originalAttempt = owner.store.acquisitionView().acquisition.physicalAttempts.getValue(assertNotNull(seed.fence.attemptId))
        val recovery = assertIs<PhysicalDownloadCommit.Committed<ClaimedPhysicalDownload>>(
            owner.repository.claimPhysicalDownloadMaintenance(ClaimPhysicalDownloadMaintenance(id, seed.fence.producer,
                seed.fence.ownershipGeneration, PhysicalDownloadMaintenanceKind.RECOVER_FINALIZATION,
                "authored-recovery-owner", clock.epochMs(), 5_000)))
        val held = owner.store.acquisitionView()
        assertSame(PhysicalDownloadCommit.Stale,
            owner.repository.publishPhysicalDownloadFinalization(seed.command(clock.epochMs())))
        assertEquals(held, owner.store.acquisitionView())
        assertTrue(held.acquisition.assetUses.isEmpty())
        assertIs<PhysicalDownloadCommit.Committed<*>>(owner.repository.publishPhysicalDownloadFinalization(
            seed.command(clock.epochMs(), recovery.value.fence)))
        val published = owner.store.acquisitionView().acquisition
        val use = assertOnlyOriginUse(published, id)
        val asset = published.materializations.getValue(use.materializationAssetId)
        assertEquals(seed.fence.ownershipGeneration, asset.ownershipGeneration)
        assertEquals(recovery.value.fence.ownershipGeneration, published.physicalDownloads.getValue(id).ownershipGeneration)
        assertNotEquals(asset.ownershipGeneration, published.physicalDownloads.getValue(id).ownershipGeneration)
        assertEquals(originalAttempt, published.physicalAttempts.getValue(originalAttempt.id))
        val settled = owner.store.acquisitionView()
        assertSame(PhysicalDownloadCommit.Stale, owner.repository.publishPhysicalDownloadFinalization(
            seed.command(clock.epochMs(), recovery.value.fence)))
        assertEquals(settled, owner.store.acquisitionView(), "Consumed publication cannot add a second use or rewrite its time")
        assertTrue(owner.validations.isEmpty(), "This is a Room command-contract test, not native readiness evidence")
    }

    @Test fun realRecoveryOfStagedBytesPublishesOnlyTheOriginalRequestedUse() = recoverOrigin(renamed = false)

    @Test fun realRecoveryOfAlreadyRenamedBytesPublishesOnlyTheOriginalRequestedUse() = recoverOrigin(renamed = true)

    @Test fun removedOriginDuringRecoveryCannotAcquireAUseFromTheOtherActiveR2() = acquisitionDownloadRoomCase {
        val old = owner()
        val first = enrollOriginUseBatch(old)
        val second = enrollOriginUseBatch(old)
        val id = old.store.acquisitionView().acquisition.physicalDownloads.values.single().id
        val seed = stageOriginUsePublication(old, id, retire = true)
        val originalAttempt = old.store.acquisitionView().acquisition.physicalAttempts.getValue(assertNotNull(seed.fence.attemptId))
        old.coordinator.close()
        old.store.close()
        val resumed = owner()
        offline(resumed)
        val held = barrier()
        resumed.validation = { held.stop(); AssetValidation.Valid(733) }
        resumed.coordinator.start(DownloadActivation.DURABLE_WORK)
        withTimeout(10_000) { held.entered.await() }
        resumed.batches.setIntent(first.id, first.intentRevision, DownloadBatchIntent.CANCELLED, retainCompletedAssets = false)
        val changedClaims = resumed.store.acquisitionView().acquisition.claims
        held.release()
        awaitState(resumed.store) { it.physicalDownloads.getValue(id).status == DownloadStatus.COMPLETE }
        idle(resumed)
        val after = resumed.store.acquisitionView().acquisition
        assertTrue(after.assetUses.isEmpty())
        assertEquals(changedClaims, after.claims)
        assertEquals(seed.asset, after.materializations.getValue(assertNotNull(seed.intent.assetId)))
        assertEquals(originalAttempt, after.physicalAttempts.getValue(originalAttempt.id))
        assertEquals(DownloadBatchMemberState.CANCELLED, after.batches.getValue(first.id).members.single().state)
        assertEquals(DownloadBatchMemberState.VERIFYING, after.batches.getValue(second.id).members.single().state)
        assertEquals(1, resumed.validations.size)
        assertTrue(resumed.gateway.preparations.isEmpty())
    }

    @Test fun failedFreshDecoderCannotPublishAnOriginUseOrReceipt() = acquisitionDownloadRoomCase {
        val owner = owner()
        enrollOriginUseBatch(owner)
        val id = owner.store.acquisitionView().acquisition.physicalDownloads.values.single().id
        val failure = AppFailure(FailureCode.CORRUPT_MEDIA, "authored-origin-decode-rejection")
        owner.validation = { AssetValidation.Invalid(failure) }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitState(owner.store) { it.physicalDownloads.getValue(id).let { job -> job.status == DownloadStatus.FAILED && job.leaseOwner == null } }
        idle(owner)
        val after = owner.store.acquisitionView().acquisition
        assertEquals(failure, after.physicalDownloads.getValue(id).failure)
        assertTrue(after.assetUses.isEmpty())
        assertTrue(after.materializations.isEmpty())
        assertTrue(after.materializationReceipts.isEmpty())
        assertTrue(after.physicalFinalizations.isEmpty())
        assertEquals(DownloadAttemptStatus.FAILED, after.physicalAttempts.values.single().status)
        assertEquals(1, owner.validations.size)
    }

    @Test fun unacknowledgedDecoderReaderCannotPublishOriginUseBeforeItsNativeOwnerReleases() = acquisitionDownloadRoomCase {
        val owner = owner()
        enrollOriginUseBatch(owner)
        val released = AtomicBoolean()
        val observed = CompletableDeferred<LocalMediaValidationInput>()
        owner.validation = { input ->
            observed.complete(input)
            throw LocalMediaValidationReaderUnacknowledged(input, LocalMediaValidationReaderRelease { released.get() })
        }
        try {
            owner.coordinator.start(DownloadActivation.DURABLE_WORK)
            val input = withTimeout(10_000) { observed.await() }
            awaitCondition { owner.coordinator.hasPendingCleanupOwners &&
                owner.coordinator.lastFailure.value?.detail == "download_owner_drain_failed_restart_required" }
            val held = owner.store.acquisitionView().acquisition
            assertTrue(held.assetUses.isEmpty())
            assertTrue(held.materializations.isEmpty())
            assertTrue(held.materializationReceipts.isEmpty())
            assertTrue(held.physicalFinalizations.isEmpty())
            assertEquals(0, owner.gate.closes.get())
            assertNull(files.tryLeasePaths(listOf(input.uri)), "Actual reader path is retained, not just a metadata flag")
            released.set(true)
            assertFailsWith<VeyraException> { owner.coordinator.close() }
            assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
            assertTrue(owner.store.acquisitionView().acquisition.assetUses.isEmpty())
        } finally { released.set(true) }
    }

    private fun originIntentChange(intent: DownloadBatchIntent, retain: Boolean) = acquisitionDownloadRoomCase {
        val owner = owner()
        val first = enrollOriginUseBatch(owner)
        val second = enrollOriginUseBatch(owner)
        val before = owner.store.acquisitionView().acquisition
        val job = before.physicalDownloads.values.single()
        assertEquals(first.members.single().requiredBindingId, job.producer.bindingId)
        assertNotEquals(first.members.single().requiredBindingId, second.members.single().requiredBindingId)
        val held = barrier()
        owner.validation = { held.stop(); AssetValidation.Valid(733) }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        withTimeout(10_000) { held.entered.await() }
        val changed = owner.batches.setIntent(first.id, first.intentRevision, intent, retainCompletedAssets = retain)
        val changedClaims = owner.store.acquisitionView().acquisition.claims
        held.release()
        awaitState(owner.store) { it.physicalDownloads.getValue(job.id).status == DownloadStatus.COMPLETE }
        idle(owner)
        val after = owner.store.acquisitionView().acquisition
        val keepsOrigin = intent != DownloadBatchIntent.CANCELLED || retain
        if (keepsOrigin) assertOnlyOriginUse(after, job.id) else assertTrue(after.assetUses.isEmpty())
        assertTrue(after.assetUses.none { it.requestedBindingId == second.members.single().requiredBindingId })
        assertEquals(changedClaims, after.claims)
        assertEquals(intent, after.batches.getValue(first.id).intent)
        assertEquals(changed.intentRevision, after.batches.getValue(first.id).intentRevision)
        assertEquals(if (keepsOrigin) DownloadBatchMemberState.AVAILABLE else DownloadBatchMemberState.CANCELLED,
            after.batches.getValue(first.id).members.single().state)
        assertEquals(DownloadBatchMemberState.VERIFYING, after.batches.getValue(second.id).members.single().state)
        assertEquals(DownloadBatchIntent.ACTIVE, after.batches.getValue(second.id).intent)
        assertEquals(second.intentRevision, after.batches.getValue(second.id).intentRevision)
        assertEquals(job.producer, after.physicalDownloads.getValue(job.id).producer)
        assertEquals(1, owner.gateway.transfers.size)
        assertEquals(1, owner.validations.size)
    }

    private fun recoverOrigin(renamed: Boolean) = acquisitionDownloadRoomCase {
        val old = owner()
        val batch = enrollOriginUseBatch(old, count = 2)
        val id = old.store.acquisitionView().acquisition.physicalDownloads.values.single().id
        val seed = stageOriginUsePublication(old, id, renamed, retire = true)
        val before = old.store.acquisitionView().acquisition
        val originalAttempt = before.physicalAttempts.getValue(assertNotNull(seed.fence.attemptId))
        old.coordinator.close()
        old.store.close()
        val resumed = owner()
        offline(resumed)
        resumed.coordinator.initializeScheduling()
        assertTrue(resumed.validations.isEmpty())
        assertTrue(resumed.store.acquisitionView().acquisition.assetUses.isEmpty())
        resumed.coordinator.start(DownloadActivation.DURABLE_WORK)
        val after = awaitOriginUsePublication(resumed, id).acquisition
        val use = assertOnlyOriginUse(after, id)
        val asset = after.materializations.getValue(use.materializationAssetId)
        assertEquals(seed.asset, asset)
        assertEquals(originalAttempt, after.physicalAttempts.getValue(originalAttempt.id), "Recovery never relabels the retired materializer attempt")
        assertTrue(after.physicalDownloads.getValue(id).ownershipGeneration > asset.ownershipGeneration)
        assertEquals(before.claims, after.claims)
        assertTrue(after.physicalFinalizations.isEmpty())
        assertEquals(1, after.batches.getValue(batch.id).members.count { it.state == DownloadBatchMemberState.AVAILABLE })
        assertEquals(1, after.batches.getValue(batch.id).members.count { it.state == DownloadBatchMemberState.VERIFYING })
        assertTrue(Files.exists(Path.of(asset.uri)))
        assertFalse(Files.exists(Path.of(seed.intent.stagingPath)))
        assertEquals(1, resumed.validations.size)
        assertEquals(if (renamed) seed.intent.finalPath else seed.intent.stagingPath, resumed.validations.single().uri)
        assertTrue(resumed.gateway.preparations.isEmpty())
        assertTrue(resumed.gateway.transfers.isEmpty())
        assertTrue(resumed.selector.requests.isEmpty())
        resumed.legacy.assertUnused()
    }
}
