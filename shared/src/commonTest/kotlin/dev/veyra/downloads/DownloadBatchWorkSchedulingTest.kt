package dev.veyra.downloads

import dev.veyra.acquisition.*
import dev.veyra.model.*
import kotlin.test.*

/** Pure extension of the existing wake plan; no Room, source, native owner or wake delivery. */
class DownloadBatchWorkSchedulingTest {
    @Test fun anEmptyPendingProjectionPreservesTheExactPhysicalPlan() {
        val physical = DownloadWakePlan(KEY, 14_000, 15_000, false, AppFailure(FailureCode.STORAGE_IO))
        assertEquals(physical, plan(emptyList(), physical = physical))
    }

    @Test fun coldReadyMemberExposesNetworkDeadlineWithoutRequiringHostActivation() {
        val result = plan(listOf(work()), host = host().copy(started = false))
        assertEquals(KEY, result.key)
        assertEquals(0L, result.nextNetworkEpochMs)
        assertNull(result.nextLocalEpochMs)
        assertTrue(result.hasDueWork)
    }

    @Test fun unknownOrDisconnectedConnectivityKeepsDeadlineButIsNotDueNetworkWork() {
        for (network in listOf(DownloadNetworkState.UNKNOWN, DownloadNetworkState.DISCONNECTED)) {
            val result = plan(listOf(work()), host = host().copy(network = network))
            assertEquals(0L, result.nextNetworkEpochMs)
            assertFalse(result.hasDueWork)
            assertEquals(FailureCode.NETWORK_UNAVAILABLE, result.blockedReason?.code)
        }
    }

    @Test fun explicitOfflineSuppressesSelectionInsteadOfCreatingARetryWake() {
        val result = plan(listOf(work()), host = host().copy(offlineOnly = true))
        assertNull(result.nextNetworkEpochMs)
        assertNull(result.nextLocalEpochMs)
        assertFalse(result.hasDueWork)
        assertEquals(FailureCode.OFFLINE_POLICY, result.blockedReason?.code)
    }

    @Test fun localCommittedAttachmentAndBoundAdmissionRemainDueWhileOffline() {
        for (kind in listOf(PendingDownloadBatchWorkKind.ATTACH_COMMITTED, PendingDownloadBatchWorkKind.ADMIT_BOUND)) {
            val result = plan(listOf(work(kind)), host = host().copy(offlineOnly = true, network = DownloadNetworkState.DISCONNECTED))
            assertEquals(0L, result.nextLocalEpochMs)
            assertNull(result.nextNetworkEpochMs)
            assertTrue(result.hasDueWork)
            assertNull(result.blockedReason)
        }
    }

    @Test fun localWorkRemainsDueAlongsideAnOfflineBlockedSelection() {
        val result = plan(listOf(work(), work(PendingDownloadBatchWorkKind.ATTACH_COMMITTED, position = 1)),
            host = host().copy(offlineOnly = true))
        assertEquals(0L, result.nextLocalEpochMs)
        assertNull(result.nextNetworkEpochMs)
        assertTrue(result.hasDueWork)
        assertEquals(FailureCode.OFFLINE_POLICY, result.blockedReason?.code)
    }

    @Test fun anExactRefusalUsesLocalArbitrationNotAConnectedNetworkWakeLoop() {
        val work = work()
        val result = plan(listOf(work), deferrals = mapOf(work.key to DownloadBatchWorkDeferral(work, 12_000, 1)))
        assertEquals(12_000L, result.nextLocalEpochMs)
        assertNull(result.nextNetworkEpochMs)
        assertFalse(result.hasDueWork)
    }

    @Test fun aLocalAttachmentRefusalAlsoDefersWithoutNetworkPermission() {
        val work = work(PendingDownloadBatchWorkKind.ATTACH_COMMITTED)
        val failure = AppFailure(FailureCode.INVALID_STATE, "authored_refused_join")
        val result = plan(listOf(work), deferrals = mapOf(work.key to DownloadBatchWorkDeferral(work, 12_000, 2, failure)))
        assertEquals(12_000L, result.nextLocalEpochMs)
        assertNull(result.nextNetworkEpochMs)
        assertEquals(failure, result.blockedReason)
    }

    @Test fun exactDeadlineCrossingRestoresOnlyTheOriginalKindsLane() {
        for (kind in PendingDownloadBatchWorkKind.entries) {
            val work = work(kind)
            val result = plan(listOf(work), deferrals = mapOf(work.key to DownloadBatchWorkDeferral(work, NOW, 1)))
            assertTrue(result.hasDueWork)
            assertEquals(if (work.requiresNetwork) 0L else null, result.nextNetworkEpochMs)
            assertEquals(if (work.requiresNetwork) null else 0L, result.nextLocalEpochMs)
        }
    }

    @Test fun aDifferentIntentRevisionCannotReuseAnOldDeferral() {
        val original = work()
        val current = original.copy(key = original.key.copy(intentRevision = 1))
        val result = plan(listOf(current), deferrals = mapOf(original.key to DownloadBatchWorkDeferral(original, 12_000, 1)))
        assertEquals(0L, result.nextNetworkEpochMs)
        assertNull(result.nextLocalEpochMs)
    }

    @Test fun aChangedWorkKindCannotBeSuppressedByTheSameKeysOldDeferral() {
        val original = work()
        val local = work(PendingDownloadBatchWorkKind.ATTACH_COMMITTED)
        assertEquals(original.key, local.key)
        val result = plan(listOf(local), deferrals = mapOf(original.key to DownloadBatchWorkDeferral(original, 12_000, 1)))
        assertEquals(0L, result.nextLocalEpochMs)
        assertNull(result.nextNetworkEpochMs)
    }

    @Test fun aChangedExactBindingCannotReuseAnAdmissionDeferral() {
        val original = work(PendingDownloadBatchWorkKind.ADMIT_BOUND)
        val current = original.copy(bindingId = ExecutionBindingId("different-exact-binding"))
        val result = plan(listOf(current), deferrals = mapOf(original.key to DownloadBatchWorkDeferral(original, 12_000, 1)))
        assertEquals(0L, result.nextLocalEpochMs)
        assertTrue(result.hasDueWork)
    }

    @Test fun liveForeignClaimUsesItsOneLocalExpiryEvenOffline() {
        val result = plan(emptyList(), nextLocal = 12_000, host = host().copy(offlineOnly = true),
            blocked = listOf(PendingDownloadBatchBlocked(work().key, PendingDownloadBatchBlockedReason.IN_PROGRESS)))
        assertEquals(12_000L, result.nextLocalEpochMs)
        assertNull(result.nextNetworkEpochMs)
        assertFalse(result.hasDueWork)
    }

    @Test fun anUnknownOutcomeHasNoSelectionWakeOrImpliedCompletion() {
        val result = plan(emptyList(), blocked = listOf(PendingDownloadBatchBlocked(work().key,
            PendingDownloadBatchBlockedReason.OUTCOME_UNKNOWN)))
        assertNull(result.nextLocalEpochMs)
        assertNull(result.nextNetworkEpochMs)
        assertFalse(result.hasDueWork)
        assertEquals(AppFailure(FailureCode.RECOVERY_REQUIRED, "download_selection_outcome_unknown"), result.blockedReason)
    }

    @Test fun invalidCanonicalJoinReportsRecoveryNotSourceEligibility() {
        val result = plan(emptyList(), blocked = listOf(PendingDownloadBatchBlocked(work().key,
            PendingDownloadBatchBlockedReason.INVALID_STATE)))
        assertNull(result.nextLocalEpochMs)
        assertNull(result.nextNetworkEpochMs)
        assertEquals(AppFailure(FailureCode.RECOVERY_REQUIRED, "download_selection_join_invalid"), result.blockedReason)
    }

    @Test fun closedSuspendedDrainingOrFailedHostAddsNoPendingDeadline() {
        val failure = AppFailure(FailureCode.STORAGE_IO, "authored_failed_owner")
        for (host in listOf(host().copy(closed = true), host().copy(suspended = true),
            host().copy(drainsInFlight = 1), host().copy(failure = failure))) {
            val result = plan(listOf(work()), nextLocal = 12_000, host = host)
            assertNull(result.nextLocalEpochMs)
            assertNull(result.nextNetworkEpochMs)
            assertFalse(result.hasDueWork)
            assertEquals(host.failure ?: AppFailure(FailureCode.BACKGROUND_RESTRICTED), result.blockedReason)
        }
    }

    @Test fun pendingWorkCombinesEarliestLocalAndNetworkDeadlinesWithoutReplacingTheKey() {
        val work = work(PendingDownloadBatchWorkKind.ATTACH_COMMITTED)
        val physical = DownloadWakePlan(KEY, 13_000, 14_000, false)
        val result = plan(listOf(work), nextLocal = 11_000,
            deferrals = mapOf(work.key to DownloadBatchWorkDeferral(work, 12_000, 1)), physical = physical)
        assertEquals(KEY, result.key)
        assertEquals(11_000L, result.nextLocalEpochMs)
        assertEquals(14_000L, result.nextNetworkEpochMs)
        assertFalse(result.hasDueWork)
    }

    @Test fun pendingUnknownDiagnosticCannotEraseAnExistingPhysicalFailureOrDueLocalWork() {
        val failure = AppFailure(FailureCode.CORRUPT_MEDIA, "authored_physical_failure")
        val physical = DownloadWakePlan(KEY, 0, null, true, failure)
        val result = plan(emptyList(), blocked = listOf(PendingDownloadBatchBlocked(work().key,
            PendingDownloadBatchBlockedReason.OUTCOME_UNKNOWN)), physical = physical)
        assertEquals(physical, result)
    }

    private fun work(kind: PendingDownloadBatchWorkKind = PendingDownloadBatchWorkKind.SELECT_READY, position: Int = 0) =
        PendingDownloadBatchWork(PendingDownloadBatchKey(DownloadBatchId("authored-batch"), position, 0,
            ExecutionOccurrenceId("authored-occurrence-$position"), AcquisitionRequestId("authored-request-$position")),
            kind, ExecutionBindingId("authored-binding-$position").takeIf { kind == PendingDownloadBatchWorkKind.ADMIT_BOUND })

    private fun host() = DownloadPhysicalHostState(started = true, closed = false, suspended = false,
        checksActivated = false, generation = 0, drainsInFlight = 0, failure = null, offlineOnly = false,
        parallelism = 2, network = DownloadNetworkState.CONNECTED)

    private fun plan(work: List<PendingDownloadBatchWork>, nextLocal: Long? = null,
        blocked: List<PendingDownloadBatchBlocked> = emptyList(),
        deferrals: Map<PendingDownloadBatchKey, DownloadBatchWorkDeferral> = emptyMap(),
        host: DownloadPhysicalHostState = host(), physical: DownloadWakePlan = DownloadWakePlan(KEY, null, null, false)) =
        withPendingDownloadBatchWork(physical, DownloadBatchWorkSchedulingInputs(
            PendingDownloadBatchProjection(work, nextLocal, blocked), deferrals), host, NOW)

    private companion object {
        const val NOW = 10_000L
        val KEY = DownloadSchedulingKey("same-coordinator", 7)
    }
}
