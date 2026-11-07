package dev.veyra.downloads

import dev.veyra.model.*
import kotlin.test.*

/** Pure authored metadata, not a Room, native gate, WorkManager or source-execution test. */
class DownloadSchedulingRulesTest {
    private val now = 10_000L
    private val key = DownloadSchedulingKey("authored-process", 1)
    private fun record(id: String = "one", status: DownloadStatus = DownloadStatus.QUEUED) =
        DownloadRecord(id, "media-$id", status)
    private fun snapshot(vararg records: DownloadRecord, offline: Boolean = false, parallelism: Int = 2,
        intents: Map<String, DownloadFinalization> = emptyMap()): AppSnapshot = AppSnapshot(
        media = records.associate { it.mediaId to MediaItem(it.mediaId, "Complete authored title ${it.id}", MediaKind.MUSIC,
            SourceRef(SourceProvider.COMMONS, "source-${it.id}")) },
        downloads = records.associateBy { it.id }, downloadFinalizations = intents,
        settings = AppSettings(offlineOnly = offline, downloadParallelism = parallelism),
    )
    private fun intent(record: DownloadRecord) = DownloadFinalization(record.id, record.mediaId,
        "/authored/staging", "/authored/final", "a".repeat(64), 17, attemptId = record.activeAttemptId)
    private fun complete(id: String = "complete"): DownloadRecord = record(id, DownloadStatus.COMPLETE).let {
        it.copy(asset = OfflineAsset("asset-$id", it.mediaId, "/authored/$id", 17))
    }
    private fun plan(snapshot: AppSnapshot, at: Long = now, network: DownloadNetworkState = DownloadNetworkState.CONNECTED,
        suspended: Boolean = false, checks: Boolean = false, checked: Set<String> = emptySet(),
        deferrals: Map<DownloadClaimKey, DownloadClaimDeferral> = emptyMap()): DownloadWakePlan =
        projectDownloadWakePlan(snapshot, DownloadSchedulingContext(at, suspended, checks, network, checked, deferrals), key)

    @Test fun twoSecondRetryIsPendingButNotDueUntilExactDeadline() {
        val waiting = record(status = DownloadStatus.WAITING_NETWORK).copy(nextRetryEpochMs = now + 2_000)
        val before = plan(snapshot(waiting))
        assertEquals(now + 2_000, before.nextNetworkEpochMs)
        assertNull(before.nextLocalEpochMs)
        assertFalse(before.hasDueWork)
        assertFalse(plan(snapshot(waiting), at = now + 1_999).hasDueWork)
        assertTrue(plan(snapshot(waiting), at = now + 2_000).hasDueWork)
    }

    @Test fun publisherTwentyFourHourRetryIsNotShortenedToWorkerBackoff() {
        val waiting = record(status = DownloadStatus.WAITING_NETWORK).copy(
            nextRetryEpochMs = now + 86_400_000,
            failure = AppFailure(FailureCode.RATE_LIMITED, retryAfterMs = 86_400_000))
        val result = plan(snapshot(waiting))
        assertEquals(now + 86_400_000, result.nextNetworkEpochMs)
        assertFalse(result.hasDueWork)
        assertEquals(key, result.key)
    }

    @Test fun unknownAndDisconnectedRetainNetworkDeadlineWithoutAdmittingNewClaims() {
        val waiting = record(status = DownloadStatus.WAITING_NETWORK).copy(nextRetryEpochMs = now)
        for (network in listOf(DownloadNetworkState.UNKNOWN, DownloadNetworkState.DISCONNECTED)) {
            val result = plan(snapshot(waiting), network = network)
            assertEquals(now, result.nextNetworkEpochMs)
            assertFalse(result.hasDueWork)
            assertEquals(FailureCode.NETWORK_UNAVAILABLE, result.blockedReason?.code)
            assertFalse(DownloadClaimRules.acquisitionReady(snapshot(waiting), waiting, now, network, false, null))
        }
        assertTrue(plan(snapshot(waiting)).hasDueWork)
    }

    @Test fun reconnectCannotBypassRateLimitNotBefore() {
        val waiting = record(status = DownloadStatus.WAITING_NETWORK).copy(nextRetryEpochMs = now + 2_000)
        for (network in DownloadNetworkState.entries) {
            val result = plan(snapshot(waiting), network = network)
            assertEquals(now + 2_000, result.nextNetworkEpochMs)
            assertFalse(result.hasDueWork)
            assertFalse(DownloadClaimRules.acquisitionReady(snapshot(waiting), waiting, now, network, false, null))
        }
    }

    @Test fun offlinePolicyIsNotAConnectivityTimer() {
        val result = plan(snapshot(record(), offline = true))
        assertNull(result.nextNetworkEpochMs)
        assertNull(result.nextLocalEpochMs)
        assertFalse(result.hasDueWork)
        assertEquals(FailureCode.OFFLINE_POLICY, result.blockedReason?.code)
    }

    @Test fun userPausedAndNeedsForegroundNeverBecomeAutonomousNetworkWakes() {
        for (record in listOf(record().copy(userPaused = true), record(status = DownloadStatus.PAUSED),
            record(status = DownloadStatus.NEEDS_FOREGROUND),
            record().copy(failure = AppFailure(FailureCode.BACKGROUND_RESTRICTED)))) {
            val result = plan(snapshot(record))
            assertNull(result.nextNetworkEpochMs)
            assertNull(result.nextLocalEpochMs)
            assertFalse(result.hasDueWork)
            if (record.status == DownloadStatus.NEEDS_FOREGROUND)
                assertEquals(FailureCode.BACKGROUND_RESTRICTED, result.blockedReason?.code)
        }
    }

    @Test fun localCleanupIsEligibleOfflineDisconnectedAndUserPaused() {
        val cleanup = record(status = DownloadStatus.CANCELLED).copy(cleanupPending = true, userPaused = true)
        val result = plan(snapshot(cleanup, offline = true), network = DownloadNetworkState.DISCONNECTED)
        assertEquals(0L, result.nextLocalEpochMs)
        assertNull(result.nextNetworkEpochMs)
        assertTrue(result.hasDueWork)
        assertTrue(DownloadClaimRules.maintenanceReady(snapshot(cleanup), cleanup,
            DownloadMaintenanceKind.CLEANUP, false, now, false, null))
    }

    @Test fun delayedFinalizationAndLaterNetworkRetryHaveIndependentDeadlines() {
        val finishing = record("finishing", DownloadStatus.VERIFYING).copy(nextRetryEpochMs = now + 2_000,
            activeAttemptId = "attempt-finishing")
        val waiting = record("waiting", DownloadStatus.WAITING_NETWORK).copy(nextRetryEpochMs = now + 86_400_000)
        val source = snapshot(finishing, waiting, intents = mapOf(finishing.id to intent(finishing)))
        val result = plan(source)
        assertEquals(now + 2_000, result.nextLocalEpochMs)
        assertEquals(now + 86_400_000, result.nextNetworkEpochMs)
        assertFalse(result.hasDueWork, "Finalization cannot be sidestepped as an expired busy transfer")
        assertTrue(plan(source, at = now + 2_000, network = DownloadNetworkState.DISCONNECTED).hasDueWork)
    }

    @Test fun futureCleanupRetryRetainsReferencesAndItsOwnLocalDeadline() {
        val cleanup = complete().copy(cleanupPending = true, userPaused = true,
            nextRetryEpochMs = now + 5_000, failure = AppFailure(FailureCode.STORAGE_IO))
        val result = plan(snapshot(cleanup), checks = true)
        assertEquals(now + 5_000, result.nextLocalEpochMs)
        assertFalse(result.hasDueWork)
        assertEquals(17, cleanup.asset?.sizeBytes?.toInt(), "Projection cannot discard the failed cleanup asset")
    }

    @Test fun pausedFinalizationAndMismatchedAttemptAreNotReclassifiedAsExpiredAcquisition() {
        val finishing = record(status = DownloadStatus.VERIFYING).copy(activeAttemptId = "one")
        val finalization = intent(finishing)
        for (held in listOf(finishing.copy(userPaused = true), finishing.copy(activeAttemptId = "different"))) {
            val result = plan(snapshot(held, intents = mapOf(held.id to finalization)))
            assertFalse(result.hasDueWork)
            assertNull(result.nextLocalEpochMs)
            assertNull(result.nextNetworkEpochMs)
        }
    }

    @Test fun historicalCompleteIsAdmittedOnlyByUserSessionAndOnlyUntilChecked() {
        val completed = complete()
        assertNull(plan(snapshot(completed)).nextLocalEpochMs)
        assertFalse(plan(snapshot(completed)).hasDueWork)
        val admitted = plan(snapshot(completed), checks = true)
        assertEquals(0L, admitted.nextLocalEpochMs)
        assertTrue(admitted.hasDueWork)
        assertFalse(plan(snapshot(completed), checks = true, checked = setOf(completed.id)).hasDueWork)
    }

    @Test fun hostSuspensionAndBackgroundRestrictedCleanupHaveNoAutomaticLocalWake() {
        val cleanup = record().copy(cleanupPending = true)
        for (result in listOf(plan(snapshot(cleanup), suspended = true),
            plan(snapshot(cleanup.copy(failure = AppFailure(FailureCode.BACKGROUND_RESTRICTED)))))) {
            assertFalse(result.hasDueWork)
            assertNull(result.nextLocalEpochMs)
            assertEquals(FailureCode.BACKGROUND_RESTRICTED, result.blockedReason?.code)
        }
    }

    @Test fun foreignFinalizerIsDeferredToTheLaterLeaseAndRetryDeadline() {
        val finishing = record(status = DownloadStatus.VERIFYING).copy(leaseOwner = "foreign",
            leaseExpiresEpochMs = now + 3_000, nextRetryEpochMs = now + 2_000, activeAttemptId = "foreign-attempt")
        val source = snapshot(finishing, intents = mapOf(finishing.id to intent(finishing)))
        assertEquals(now + 3_000, plan(source).nextLocalEpochMs)
        assertFalse(plan(source).hasDueWork)
        assertFalse(DownloadClaimRules.maintenanceReady(source, finishing, DownloadMaintenanceKind.FINALIZATION,
            false, now + 2_999, false, null))
        assertTrue(DownloadClaimRules.maintenanceReady(source, finishing, DownloadMaintenanceKind.FINALIZATION,
            false, now + 3_000, false, null))
    }

    @Test fun occupiedForeignTransferSlotUsesLocalExpiryNotAnImmediateNetworkSpin() {
        val foreign = record("foreign", DownloadStatus.RUNNING).copy(leaseOwner = "foreign", leaseExpiresEpochMs = now + 3_000)
        val waiting = record("waiting")
        val source = snapshot(foreign, waiting, parallelism = 1)
        val result = plan(source)
        assertEquals(now + 3_000, result.nextLocalEpochMs)
        assertNull(result.nextNetworkEpochMs)
        assertFalse(result.hasDueWork)
        assertFalse(DownloadClaimRules.acquisitionReady(source, waiting, now, DownloadNetworkState.CONNECTED, false, null))
        assertTrue(plan(source, at = now + 3_000).hasDueWork)
    }

    @Test fun spareCapacityKeepsNetworkAndForeignExpiryLanesSeparate() {
        val foreign = record("foreign", DownloadStatus.RUNNING).copy(leaseOwner = "foreign", leaseExpiresEpochMs = now + 3_000)
        val waiting = record("waiting").copy(nextRetryEpochMs = now + 2_000)
        val result = plan(snapshot(foreign, waiting, parallelism = 2))
        assertEquals(now + 3_000, result.nextLocalEpochMs)
        assertEquals(now + 2_000, result.nextNetworkEpochMs)
        assertFalse(result.hasDueWork)
    }

    @Test fun physicalAcquisitionRefusalUsesBoundedLocalRecheckAndActualClaimHonorsIt() {
        val waiting = record()
        val deferrals = mapOf(DownloadClaimKey(waiting.id, DownloadClaimKind.ACQUISITION) to
            DownloadClaimDeferral(waiting.schedulingRecord(), now + 5_000, 1))
        val result = plan(snapshot(waiting), deferrals = deferrals)
        assertEquals(now + 5_000, result.nextLocalEpochMs)
        assertNull(result.nextNetworkEpochMs)
        assertFalse(result.hasDueWork)
        assertFalse(DownloadClaimRules.acquisitionReady(snapshot(waiting), waiting, now,
            DownloadNetworkState.CONNECTED, false, now + 5_000))
        assertTrue(plan(snapshot(waiting), at = now + 5_000, deferrals = deferrals).hasDueWork)
    }

    @Test fun failedExpiredRecoveryHasLocalCooldownWithoutExtendingTheOldOwnersLease() {
        val expired = record(status = DownloadStatus.RUNNING).copy(leaseOwner = "old", leaseExpiresEpochMs = now - 1)
        val deferrals = mapOf(DownloadClaimKey(expired.id, DownloadClaimKind.EXPIRED_RECOVERY) to
            DownloadClaimDeferral(expired.schedulingRecord(), now + 5_000, 1))
        val result = plan(snapshot(expired), deferrals = deferrals)
        assertEquals(now + 5_000, result.nextLocalEpochMs)
        assertFalse(result.hasDueWork)
        assertFalse(DownloadClaimRules.expiredRecoveryReady(snapshot(expired), expired, now, now + 5_000))
        assertEquals(now - 1, expired.leaseExpiresEpochMs)
    }

    @Test fun progressOnlyChangesKeepDeferralButAChangedIntentCannotInheritIt() {
        val waiting = record()
        val deferrals = mapOf(DownloadClaimKey(waiting.id, DownloadClaimKind.ACQUISITION) to
            DownloadClaimDeferral(waiting.schedulingRecord(), now + 5_000, 1))
        val progress = waiting.copy(bytesDownloaded = 5, totalBytes = 20, updatedEpochMs = now, lastProgressEpochMs = now)
        assertEquals(waiting.schedulingRecord(), progress.schedulingRecord())
        assertEquals(now + 5_000, claimDeferredUntil(progress, DownloadClaimKind.ACQUISITION, deferrals))
        assertNull(claimDeferredUntil(waiting.copy(priority = 10), DownloadClaimKind.ACQUISITION, deferrals))
        assertNull(claimDeferredUntil(waiting.copy(stagingPath = "/different/generation"), DownloadClaimKind.ACQUISITION, deferrals))
    }

    @Test fun missingRequestedMediaCannotProduceAnUnclaimableNetworkWake() {
        val waiting = record()
        val result = plan(snapshot(waiting).copy(media = emptyMap()))
        assertFalse(result.hasDueWork)
        assertNull(result.nextLocalEpochMs)
        assertNull(result.nextNetworkEpochMs)
    }
}
