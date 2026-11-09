package dev.veyra.acquisition

import dev.veyra.model.*
import kotlin.test.*

/** Pure display/early-rejection policy, not evidence of Room, native or executor admission. */
class AcquisitionDownloadRetryPolicyTest {
    private val producer = PhysicalDownloadProducer.Typed(ExecutableSubjectId("authored-subject"),
        ExecutionBindingId("authored-r1"), SourceRecordingId("authored-c"))
    private fun failed() = PhysicalDownloadJob("authored-job", producer, status = DownloadStatus.FAILED,
        failure = AppFailure(FailureCode.STORAGE_IO), nextRetryEpochMs = 20_000, automaticRetryCount = 5)

    @Test fun terminalFailureIsARequestToRetryTheSameAcceptedJobNotToResumeTheBatch() {
        assertTrue(isExplicitMemberRetryCandidate(failed()))
        assertTrue(isExplicitMemberRetryCandidate(failed().copy(failure = null)))
        assertFalse(isExplicitMemberRetryCandidate(failed().copy(failure = AppFailure(FailureCode.BACKGROUND_RESTRICTED))))
    }

    @Test fun ordinaryQueuedDisconnectedAndForegroundStatesNeverPretendToBeFailedRetries() {
        DownloadStatus.entries.filter { it != DownloadStatus.FAILED }.forEach { status ->
            assertFalse(isExplicitMemberRetryCandidate(failed().copy(status = status)), "Not a retry candidate: $status")
        }
    }

    @Test fun transientWaitingRequiresActualFailureDeadlineAndBoundedAutomaticHistory() {
        val waiting = failed().copy(status = DownloadStatus.WAITING_NETWORK, failure = AppFailure(FailureCode.NETWORK_UNAVAILABLE))
        assertTrue(isExplicitMemberRetryCandidate(waiting))
        assertTrue(isExplicitMemberRetryCandidate(waiting.copy(failure = AppFailure(FailureCode.RATE_LIMITED))))
        assertFalse(isExplicitMemberRetryCandidate(waiting.copy(nextRetryEpochMs = null)))
        assertFalse(isExplicitMemberRetryCandidate(waiting.copy(automaticRetryCount = 0)))
        assertFalse(isExplicitMemberRetryCandidate(waiting.copy(automaticRetryCount = 6)))
        assertFalse(isExplicitMemberRetryCandidate(waiting.copy(failure = null)))
        assertFalse(isExplicitMemberRetryCandidate(waiting.copy(failure = AppFailure(FailureCode.OFFLINE_POLICY))))
    }

    @Test fun evenExpiredOrPartiallyPresentDurableLeaseFieldsRejectRetry() {
        assertFalse(isExplicitMemberRetryCandidate(failed().copy(leaseOwner = "authored-owner")))
        assertFalse(isExplicitMemberRetryCandidate(failed().copy(leaseExpiresEpochMs = 1)))
        assertFalse(isExplicitMemberRetryCandidate(failed().copy(leaseOwner = "expired-owner", leaseExpiresEpochMs = 1)))
        assertFalse(isExplicitMemberRetryCandidate(failed().copy(activeAttemptId = "authored-attempt")))
    }

    @Test fun cleanupRemovalPauseAndEveryMaintenanceKindRemainFenced() {
        assertFalse(isExplicitMemberRetryCandidate(failed().copy(userPaused = true)))
        assertFalse(isExplicitMemberRetryCandidate(failed().copy(cleanupPending = true)))
        assertFalse(isExplicitMemberRetryCandidate(failed().copy(removeWhenClean = true)))
        PhysicalDownloadMaintenanceKind.entries.forEach { kind ->
            assertFalse(isExplicitMemberRetryCandidate(failed().copy(maintenanceKind = kind)))
        }
    }

    @Test fun retainedAssetRequiresItsValidationJourneyNotPhysicalRetry() {
        assertFalse(isExplicitMemberRetryCandidate(failed().copy(assetId = MaterializationAssetId("authored-asset"))))
        assertFalse(isExplicitMemberRetryCandidate(failed().copy(status = DownloadStatus.COMPLETE)))
    }

    @Test fun unknownSchema2ProvenanceCannotBeRetriedByInventingANewCapture() {
        assertFalse(isExplicitMemberRetryCandidate(failed().copy(producer = PhysicalDownloadProducer.Schema2("authored-media", null, null))))
        assertFalse(isExplicitMemberRetryCandidate(failed().copy(producer = PhysicalDownloadProducer.Schema2("authored-media",
            ExecutionBindingId("authored-old-binding"), null))))
        assertTrue(isExplicitMemberRetryCandidate(failed().copy(producer = PhysicalDownloadProducer.Schema2("authored-media",
            ExecutionBindingId("authored-known-r1"), SourceRecordingId("authored-c")))))
    }

    @Test fun resetPreservesProducerKeyPathsCountersAndPhysicalOwnershipHistoryExactly() {
        val before = failed().copy(bytesDownloaded = 17, totalBytes = 91, stagingPath = "authored/path.part",
            representationId = "authored-fixed-key", etag = "authored-etag", lastModified = "authored-last-modified",
            validatorGeneration = 3, attemptCount = 7, ownershipGeneration = 9, createdEpochMs = 12,
            updatedEpochMs = 14, lastProgressEpochMs = 13, priority = 6)
        assertEquals(before.copy(status = DownloadStatus.QUEUED, failure = null, nextRetryEpochMs = null,
            automaticRetryCount = 0, updatedEpochMs = 30), before.afterExplicitMemberRetry(30))
    }
}
