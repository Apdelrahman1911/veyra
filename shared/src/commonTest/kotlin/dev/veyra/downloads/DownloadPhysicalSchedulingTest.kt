package dev.veyra.downloads

import dev.veyra.data.AcquisitionRepositoryState
import dev.veyra.model.*
import kotlin.test.*

/** Pure metadata/intent projections only; not native ownership, Room or actual wake delivery. */
class DownloadPhysicalSchedulingTest {
    @Test fun activeQueuedCanonicalJobUsesOnlyTheExistingConnectedNetworkLane() {
        val plan = plan(state())
        assertNull(plan.nextLocalEpochMs)
        assertEquals(0L, plan.nextNetworkEpochMs)
        assertTrue(plan.hasDueWork)
    }

    @Test fun coldDurableWorkerDoesNotScheduleHistoricalCompletedFileValidation() {
        val plan = plan(complete(), host = host(checks = false))
        assertNull(plan.nextLocalEpochMs)
        assertNull(plan.nextNetworkEpochMs)
        assertFalse(plan.hasDueWork)
    }

    @Test fun userSessionExplicitlyActivatesLocalCompletedChecksWithoutANetworkWake() {
        val plan = plan(complete())
        assertEquals(0L, plan.nextLocalEpochMs)
        assertNull(plan.nextNetworkEpochMs)
        assertTrue(plan.hasDueWork)
    }

    @Test fun currentExactCheckedAssetDoesNotNeedAnotherHistoricalCheck() {
        val plan = plan(complete(), checked = setOf(JOB))
        assertFalse(plan.hasDueWork)
        assertNull(plan.nextLocalEpochMs)
    }

    @Test fun disconnectedNetworkPreservesTheNetworkDeadlineButNotDueWork() {
        val plan = plan(state(job().copy(nextRetryEpochMs = 5_000)), host = host(network = DownloadNetworkState.DISCONNECTED))
        assertEquals(5_000L, plan.nextNetworkEpochMs)
        assertFalse(plan.hasDueWork)
        assertEquals(FailureCode.NETWORK_UNAVAILABLE, plan.blockedReason?.code)
    }

    @Test fun explicitOfflinePolicySuppressesAcquisitionButNeverBlocksLocalCleanup() {
        val queued = plan(state(), host = host(offline = true))
        assertNull(queued.nextNetworkEpochMs)
        assertEquals(FailureCode.OFFLINE_POLICY, queued.blockedReason?.code)
        val cleanup = job().copy(status = DownloadStatus.CANCELLED, userPaused = true, cleanupPending = true,
            removeWhenClean = true, maintenanceKind = PhysicalDownloadMaintenanceKind.CLEANUP)
        val local = plan(state(cleanup, listOf(claim(intent = DownloadBatchIntent.CANCELLED, retain = false))),
            host = host(offline = true, network = DownloadNetworkState.DISCONNECTED))
        assertEquals(0L, local.nextLocalEpochMs)
        assertTrue(local.hasDueWork)
    }

    @Test fun activeClaimAloneDoesNotAuthorizeAutomaticRetryOfAFailedFirstExecution() {
        val plan = plan(state(job().copy(status = DownloadStatus.FAILED, failure = AppFailure(FailureCode.UNSUPPORTED_CODEC))))
        assertNull(plan.nextLocalEpochMs)
        assertNull(plan.nextNetworkEpochMs)
        assertFalse(plan.hasDueWork)
    }

    @Test fun persistedNeedsForegroundDoesNotAutoResumeOnAConnectedNetwork() {
        val plan = plan(state(job().copy(status = DownloadStatus.NEEDS_FOREGROUND,
            failure = AppFailure(FailureCode.BACKGROUND_RESTRICTED))))
        assertFalse(plan.hasDueWork)
        assertNull(plan.nextNetworkEpochMs)
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, plan.blockedReason?.code)
    }

    @Test fun pausedClaimWithAnUnreconciledQueuedJobSchedulesLocalIntentRepairNotTransfer() {
        val plan = plan(state(claims = listOf(claim(intent = DownloadBatchIntent.PAUSED))))
        assertTrue(plan.hasDueWork)
        assertEquals(0L, plan.nextLocalEpochMs)
        assertNull(plan.nextNetworkEpochMs)
    }

    @Test fun cancelledRetainingClaimNeverSchedulesDeletionOfItsCompletedAsset() {
        val complete = complete().let { it.copy(claims = mapOf(CLAIM to claim(intent = DownloadBatchIntent.CANCELLED))) }
        val plan = plan(complete, checked = setOf(JOB))
        assertFalse(plan.hasDueWork)
        assertNull(plan.nextLocalEpochMs)
    }

    @Test fun cancelledRetainingTombstoneDoesNotEnterAnEmptyCleanupLoop() {
        val plan = plan(state(job().copy(status = DownloadStatus.CANCELLED, userPaused = true),
            listOf(claim(intent = DownloadBatchIntent.CANCELLED))))
        assertFalse(plan.hasDueWork)
        assertNull(plan.nextLocalEpochMs)
    }

    @Test fun nonretainingRemovedTombstoneKeepsHistoryWithoutRepeatedDeletion() {
        val plan = plan(state(job().copy(status = DownloadStatus.CANCELLED, userPaused = true, removeWhenClean = true),
            listOf(claim(intent = DownloadBatchIntent.CANCELLED, retain = false))))
        assertFalse(plan.hasDueWork)
        assertNull(plan.nextLocalEpochMs)
    }

    @Test fun noClaimsIsUnknownOwnershipNotADeletionOrAcquisitionAuthorization() {
        val plan = plan(state(claims = emptyList()))
        assertFalse(plan.hasDueWork)
        assertNull(plan.nextLocalEpochMs)
        assertNull(plan.nextNetworkEpochMs)
        assertEquals(FailureCode.RECOVERY_REQUIRED, plan.blockedReason?.code)
    }

    @Test fun localSlotRefusalUsesBoundedLocalRecheckNotImmediateNetworkWork() {
        val state = state()
        val record = state.physicalSchedulingRecords().getValue(JOB)
        val deferred = mapOf(DownloadClaimKey(JOB, DownloadClaimKind.ACQUISITION) to
            DownloadPhysicalClaimDeferral(record, 15_000, 1))
        val plan = plan(state, deferrals = deferred)
        assertEquals(15_000L, plan.nextLocalEpochMs)
        assertNull(plan.nextNetworkEpochMs)
        assertFalse(plan.hasDueWork)
    }

    @Test fun progressOnlyPublicationDoesNotInvalidateThePhysicalRefusalReceipt() {
        val before = state()
        val record = before.physicalSchedulingRecords().getValue(JOB)
        val after = before.copy(physicalDownloads = mapOf(JOB to job().copy(bytesDownloaded = 20,
            totalBytes = 30, updatedEpochMs = 99, lastProgressEpochMs = 98)))
        assertEquals(record, after.physicalSchedulingRecords().getValue(JOB))
        val plan = plan(after, deferrals = mapOf(DownloadClaimKey(JOB, DownloadClaimKind.ACQUISITION) to
            DownloadPhysicalClaimDeferral(record, 15_000, 1)))
        assertEquals(15_000L, plan.nextLocalEpochMs)
        assertFalse(plan.hasDueWork)
    }

    @Test fun anotherClaimGenerationInvalidatesAnOldPhysicalDeferral() {
        val before = state()
        val record = before.physicalSchedulingRecords().getValue(JOB)
        val changed = state(claims = listOf(claim().copy(generation = 1)))
        val plan = plan(changed, deferrals = mapOf(DownloadClaimKey(JOB, DownloadClaimKind.ACQUISITION) to
            DownloadPhysicalClaimDeferral(record, 15_000, 1)))
        assertNull(plan.nextLocalEpochMs)
        assertEquals(0L, plan.nextNetworkEpochMs)
    }

    @Test fun foreignLiveTransferCapacityDefersAtTheEarliestLeaseExpiry() {
        val foreign = job().copy(id = "foreign", status = DownloadStatus.RUNNING, leaseOwner = "foreign-owner",
            leaseExpiresEpochMs = 12_000)
        val state = state().let { it.copy(physicalDownloads = it.physicalDownloads + (foreign.id to foreign),
            claims = it.claims + (DownloadClaimId("foreign-claim") to claim().copy(id = DownloadClaimId("foreign-claim"),
                owner = DownloadClaimOwner.Standalone(foreign.id), physicalJobId = foreign.id))) }
        val plan = plan(state, host = host(parallelism = 1))
        assertEquals(12_000L, plan.nextLocalEpochMs)
        assertNull(plan.nextNetworkEpochMs)
        assertFalse(plan.hasDueWork)
    }

    @Test fun expiredBusyRecordSchedulesIntentRecoveryWithoutAPhysicalAck() {
        val plan = plan(state(job().copy(status = DownloadStatus.RUNNING, leaseOwner = "foreign-owner",
            leaseExpiresEpochMs = 9_000)))
        assertEquals(9_000L, plan.nextLocalEpochMs)
        assertTrue(plan.hasDueWork)
        assertNull(plan.nextNetworkEpochMs)
    }

    @Test fun pausedFinalizationCannotBeBypassedByOrdinaryExpiredTransferRecovery() {
        val paused = job().copy(status = DownloadStatus.PAUSED, userPaused = true, leaseOwner = "expired", leaseExpiresEpochMs = 9_000)
        val state = state(paused, listOf(claim(intent = DownloadBatchIntent.PAUSED))).copy(
            physicalFinalizations = mapOf(JOB to finalization()))
        val plan = plan(state)
        assertFalse(plan.hasDueWork)
        assertNull(plan.nextLocalEpochMs)
        assertNull(plan.nextNetworkEpochMs)
    }

    @Test fun permanentlyCorruptFinalizationDoesNotEnterAutomaticNativeValidationLoop() {
        val state = state(job().copy(status = DownloadStatus.FAILED, failure = AppFailure(FailureCode.CORRUPT_MEDIA))).copy(
            physicalFinalizations = mapOf(JOB to finalization()))
        val plan = plan(state)
        assertFalse(plan.hasDueWork)
        assertEquals(FailureCode.CORRUPT_MEDIA, plan.blockedReason?.code)
    }

    @Test fun unknownSchema2ProducerNeedsExplicitAcquisitionNotAFreshHiddenLookup() {
        val state = state(job().copy(producer = PhysicalDownloadProducer.Schema2("legacy", null, null)),
            listOf(claim().copy(owner = DownloadClaimOwner.Schema2Standalone(JOB), requestedBindingId = null)))
        val plan = plan(state)
        assertFalse(plan.hasDueWork)
        assertNull(plan.nextNetworkEpochMs)
        assertEquals(FailureCode.RECOVERY_REQUIRED, plan.blockedReason?.code)
    }

    @Test fun unknownSchema2CompletionDoesNotEnterAnImpossibleAutomaticNativeCheckLoop() {
        val unknown = PhysicalDownloadProducer.Schema2("legacy", null, null)
        val asset = MaterializationAsset(MaterializationAssetId("legacy-asset"), unknown, "/owned/legacy.mp3", 7)
        val current = state(job().copy(producer = unknown, status = DownloadStatus.COMPLETE, assetId = asset.id),
            listOf(claim().copy(owner = DownloadClaimOwner.Schema2Standalone(JOB), requestedBindingId = null)))
            .copy(materializations = mapOf(asset.id to asset))
        val plan = plan(current)
        assertFalse(plan.hasDueWork)
        assertNull(plan.nextLocalEpochMs)
        assertEquals(FailureCode.RECOVERY_REQUIRED, plan.blockedReason?.code)
    }

    @Test fun activeClaimRepairsAStalePauseFlagWithoutAuthorizingRetryOfItsFailedExecution() {
        val failed = job().copy(status = DownloadStatus.FAILED, userPaused = true,
            failure = AppFailure(FailureCode.UNSUPPORTED_CODEC))
        assertEquals(0L, plan(state(failed)).nextLocalEpochMs)
        val repaired = plan(state(failed.copy(userPaused = false)))
        assertFalse(repaired.hasDueWork)
        assertNull(repaired.nextNetworkEpochMs)
    }

    @Test fun anIndependentHostDrainSuppressesNewPhysicalAdmission() {
        val plan = plan(state(), host = host().copy(drainsInFlight = 1))
        assertFalse(plan.hasDueWork)
        assertNull(plan.nextNetworkEpochMs)
    }

    @Test fun retiredAttemptPathChangesParticipateInTheCoherentIdleInput() {
        val before = state()
        val attempt = PhysicalDownloadAttempt("old", JOB, PRODUCER, 1, 1, stagingPath = "/owned/old/transfer.part")
        val after = before.copy(physicalAttempts = mapOf(attempt.id to attempt))
        assertNotEquals(before.physicalSchedulingRecords(), after.physicalSchedulingRecords())
        assertEquals(setOf(attempt.stagingPath), after.physicalSchedulingRecords().getValue(JOB).stagingPaths)
    }

    private fun state(job: PhysicalDownloadJob = job(), claims: List<DownloadClaim> = listOf(claim())) =
        AcquisitionRepositoryState(0, physicalDownloads = mapOf(job.id to job), claims = claims.associateBy { it.id })

    private fun complete(): AcquisitionRepositoryState {
        val asset = MaterializationAsset(MaterializationAssetId("asset"), PRODUCER, "/owned/asset.mp3", 7,
            sha256 = "a".repeat(64), representationId = "representation")
        return state(job().copy(status = DownloadStatus.COMPLETE, assetId = asset.id)).copy(materializations = mapOf(asset.id to asset))
    }

    private fun job() = PhysicalDownloadJob(JOB, PRODUCER)
    private fun claim(intent: DownloadBatchIntent = DownloadBatchIntent.ACTIVE, retain: Boolean = true) =
        DownloadClaim(CLAIM, DownloadClaimOwner.Standalone(JOB), JOB, PRODUCER.bindingId, intent, retain, 1)
    private fun finalization() = PhysicalDownloadFinalization(JOB, PRODUCER, "/owned/transfer.part", "/owned/asset.mp3", "a".repeat(64), 7)
    private fun host(checks: Boolean = true, offline: Boolean = false, network: DownloadNetworkState = DownloadNetworkState.CONNECTED,
        parallelism: Int = 2) = DownloadPhysicalHostState(started = true, closed = false, suspended = false,
        checksActivated = checks, generation = 0, drainsInFlight = 0, failure = null, offlineOnly = offline,
        parallelism = parallelism, network = network)
    private fun plan(state: AcquisitionRepositoryState, host: DownloadPhysicalHostState = host(), checked: Set<String> = emptySet(),
        deferrals: Map<DownloadClaimKey, DownloadPhysicalClaimDeferral> = emptyMap()) = projectPhysicalDownloadWakePlan(
            DownloadPhysicalSchedulingInputs(state.physicalSchedulingRecords(), checked, deferrals), host, 10_000,
            DownloadSchedulingKey("existing-coordinator", 1))

    private companion object {
        const val JOB = "physical-job"
        val CLAIM = DownloadClaimId("owner-claim")
        val PRODUCER = PhysicalDownloadProducer.Typed(ExecutableSubjectId("neutral-subject"),
            ExecutionBindingId("producer-r1"), SourceRecordingId("recording-c"))
    }
}
