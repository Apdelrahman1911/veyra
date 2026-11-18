package dev.veyra.downloads

import dev.veyra.model.AppFailure
import dev.veyra.model.DownloadStatus
import dev.veyra.model.ExecutionBindingId
import dev.veyra.model.ExecutableSubjectId
import dev.veyra.model.FailureCode
import dev.veyra.model.PhysicalDownloadAttempt
import dev.veyra.model.PhysicalDownloadFence
import dev.veyra.model.PhysicalDownloadJob
import dev.veyra.model.PhysicalDownloadProducer
import dev.veyra.model.SourceRecordingId
import dev.veyra.model.VeyraException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadFailureDiagnosticsTest {
    @Test fun recordsAreBoundedAndSequencedWithoutRetainingArbitraryFailureDetailsOrExceptions() {
        val recorder = DownloadDiagnosticRecorder()
        val privateText = "https://private.example/audio?token=authored-secret /private/library.db private-title"
        repeat(100) {
            recorder.record(DownloadDiagnosticStage.SOURCE_TRANSFER,
                AppFailure(FailureCode.INVALID_STATE, privateText), IllegalStateException(privateText))
        }
        val entries = recorder.entries.value
        assertEquals(32, entries.size)
        assertEquals((69L..100L).toList(), entries.map { it.sequence })
        assertTrue(entries.all { it.failure?.kind == DownloadDiagnosticErrorKind.STATE_GUARD })
        assertTrue(entries.all { it.failure?.reason == DownloadDiagnosticReason.UNCLASSIFIED })
        assertFalse(entries.toString().contains("authored-secret"))
        assertFalse(entries.toString().contains("library.db"))
        assertFalse(entries.toString().contains("private-title"))
    }

    @Test fun actionableSourceRefreshAndIntegrityCodesAreNotRewrittenAsInvalidState() {
        val recorder = DownloadDiagnosticRecorder()
        val failures = listOf(
            AppFailure(FailureCode.RECOVERY_REQUIRED, "YOUTUBE_SOURCE_EXPIRED") to DownloadDiagnosticReason.SOURCE_EXPIRED,
            AppFailure(FailureCode.RECOVERY_REQUIRED, "YOUTUBE_SELECTED_TRACK_CHANGED") to DownloadDiagnosticReason.REPRESENTATION_CHANGED,
            AppFailure(FailureCode.CORRUPT_MEDIA, "YOUTUBE_TRANSFER_IDENTITY_CHANGED") to DownloadDiagnosticReason.TRANSFER_INTEGRITY,
            AppFailure(FailureCode.PROVIDER_ACCESS_REQUIRED, "YOUTUBE_ACCESS_REQUIRED") to DownloadDiagnosticReason.SOURCE_ACCESS,
        )
        failures.forEach { (failure, reason) ->
            recorder.record(DownloadDiagnosticStage.SOURCE_TRANSFER, failure, VeyraException(failure))
            val diagnostic = recorder.entries.value.last().failure!!
            assertEquals(failure.code, diagnostic.code)
            assertEquals(reason, diagnostic.reason)
            assertEquals(failure.detail, diagnostic.detailCode)
            assertEquals(DownloadDiagnosticErrorKind.TYPED, diagnostic.kind)
        }
    }

    @Test fun unknownIdentifierShapedDetailsAreNotMistakenForSafeAuthoredCodes() {
        listOf("PRIVATE_TITLE", "download_private_token", "YOUTUBE_PRIVATE_SECRET", "YOUTUBE_SOURCE_EXPIRED\nsecret").forEach { detail ->
            val diagnostic = DownloadDiagnosticFailure.from(AppFailure(FailureCode.INVALID_STATE, detail))
            assertEquals(DownloadDiagnosticReason.UNCLASSIFIED, diagnostic.reason)
            assertNull(diagnostic.detailCode)
            assertFalse(diagnostic.toString().contains(detail))
        }
    }

    @Test fun authoredPreExecutorActionRejectionsRemainDistinctFromPhysicalOwnershipFailures() {
        val stale = DownloadDiagnosticFailure.from(AppFailure(FailureCode.INVALID_STATE, "download_action_stale"))
        val catalog = DownloadDiagnosticFailure.from(AppFailure(FailureCode.INVALID_STATE, "download_catalog_identity_changed"))
        val capture = DownloadDiagnosticFailure.from(AppFailure(FailureCode.INVALID_STATE, "download_catalog_capture_required"))
        assertEquals(DownloadDiagnosticReason.ACTION_STALE, stale.reason)
        assertEquals(DownloadDiagnosticReason.CATALOG_IDENTITY, catalog.reason)
        assertEquals(DownloadDiagnosticReason.CATALOG_CAPTURE, capture.reason)
        assertEquals("download_action_stale", stale.detailCode)
    }

    @Test fun observedOwnershipChangesRemainExplicitWithoutExposingIdentifiersPathsOrRepresentationKeys() {
        val producer = PhysicalDownloadProducer.Typed(ExecutableSubjectId("private-subject"),
            ExecutionBindingId("private-binding"), SourceRecordingId("private-recording"))
        val job = PhysicalDownloadJob("private-job", producer, DownloadStatus.RUNNING,
            stagingPath = "/private/media.part", representationId = "private-representation",
            updatedEpochMs = 1_000, attemptCount = 1, activeAttemptId = "private-attempt",
            leaseOwner = "private-owner", leaseExpiresEpochMs = 5_000, ownershipGeneration = 7)
        val attempt = PhysicalDownloadAttempt("private-attempt", job.id, producer, 1, 7)
        val fence = PhysicalDownloadFence(job.id, producer, 7, "private-owner", attempt.id, 1)
        val committed = physicalDownloadDiagnosticState(job, attempt, null, fence, 2_000, job.representationId)
        assertTrue(committed.ownerMatches && committed.generationMatches && committed.attemptMatches)
        assertTrue(committed.leaseWithinTimeBounds && committed.representationMatches)
        val observed = physicalDownloadDiagnosticState(job.copy(ownershipGeneration = 8, leaseOwner = "new-private-owner",
            activeAttemptId = null, representationId = "other-private-representation"), null, null, fence, 6_000,
            job.representationId)
        assertFalse(observed.ownerMatches || observed.generationMatches || observed.attemptMatches)
        assertFalse(observed.leaseWithinTimeBounds || observed.representationMatches)
        assertEquals(8L, observed.ownershipGeneration)
        assertFalse(listOf(committed, observed).toString().contains("private-"))
        assertFalse(listOf(committed, observed).toString().contains("/private/"))
        assertEquals(DownloadDiagnosticClaimDemand.INVALID_JOIN, diagnosticDownloadClaimDemand("", emptyList()))
    }

    @Test fun concurrentFailuresKeepACompleteMonotonicBoundedTail() = runTest {
        val recorder = DownloadDiagnosticRecorder()
        coroutineScope {
            repeat(8) {
                launch(Dispatchers.Default) {
                    repeat(100) { recorder.record(DownloadDiagnosticStage.HOST_FAILURE, AppFailure(FailureCode.INVALID_STATE)) }
                }
            }
        }
        assertEquals((769L..800L).toList(), recorder.entries.value.map { it.sequence })
    }
}
