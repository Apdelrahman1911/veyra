package dev.veyra.downloads

import dev.veyra.data.AcquisitionRepositoryState
import dev.veyra.data.LibraryAcquisitionView
import dev.veyra.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

/** Authored process-state/projection checks only; these values are not real file validation. */
class DownloadMaterializationChecksTest {
    @Test fun sameAssetSuccessGetsANewReferenceAndIsNotConflatedByStateFlow() {
        val first = DownloadMaterializationChecks.Empty.recordSuccess(job.id, asset)
        val second = first.recordSuccess(job.id, asset)
        val state = MutableStateFlow(first)
        state.value = second
        assertSame(second, state.value)
        assertEquals(first.assets, second.assets)
        assertNotSame(first.successfulChecks.getValue(job.id), second.successfulChecks.getValue(job.id))
        assertSame(asset, second.successfulChecks.getValue(job.id).asset)
        assertSame(asset, second.assets.getValue(job.id))
        assertEquals(job.id, second.successfulChecks.getValue(job.id).downloadId)
        assertEquals(1L, second.assets.getValue(job.id).ownershipGeneration)
    }

    @Test fun anotherJobsSuccessDoesNotRefreshThisJobsReference() {
        val before = DownloadMaterializationChecks.Empty.recordSuccess(job.id, asset)
        val other = asset.copy(id = MaterializationAssetId("other-asset"), materializerDownloadId = "other-job")
        val after = before.recordSuccess("other-job", other)
        assertSame(before.successfulChecks.getValue(job.id), after.successfulChecks.getValue(job.id))
        assertEquals(setOf(job.id, "other-job"), after.assets.keys)
        assertEquals(after.assets.keys, after.successfulChecks.keys)
        after.successfulChecks.forEach { (id, check) ->
            assertEquals(id, check.downloadId)
            assertSame(after.assets.getValue(id), check.asset)
        }
    }

    @Test fun removalRevokesBothSidesWithoutMutatingAnAlreadyDisplayedSnapshot() {
        val before = DownloadMaterializationChecks.Empty.recordSuccess(job.id, asset)
        val shown = projectAcquisitionDownloadPresentation(view(), before, host)
        val after = before.without(job.id)
        assertTrue(after.assets.isEmpty())
        assertTrue(after.successfulChecks.isEmpty())
        assertSame(after, after.without(job.id))
        val current = projectAcquisitionDownloadPresentation(view(), after, host)
        assertTrue(current.successfulChecks.isEmpty())
        assertEquals(DownloadStatus.VERIFYING, current.states.getValue(job.id).status)
        assertSame(before.successfulChecks.getValue(job.id), shown.successfulChecks.getValue(job.id))
        assertEquals(DownloadStatus.COMPLETE, shown.states.getValue(job.id).status)
    }

    @Test fun pairedProjectionUsesOneExactInputAndDoesNotCreateAnR2UseOrLegacyMedia() {
        val canonical = view()
        val checked = DownloadMaterializationChecks.Empty.recordSuccess(job.id, asset)
        val shown = projectAcquisitionDownloadPresentation(canonical, checked, host)
        assertSame(canonical, shown.view)
        assertSame(checked.successfulChecks.getValue(job.id), shown.successfulChecks.getValue(job.id))
        assertEquals(DownloadStatus.COMPLETE, shown.states.getValue(job.id).status)
        assertEquals(setOf(job.id), checkedPhysicalDownloadIds(canonical.acquisition, checked))
        assertTrue(shown.view.acquisition.assetUses.isEmpty(), "Physical success is not an R2 AssetUse")
        assertEquals(canonical.snapshot, projectLegacyPhysicalDownloads(canonical, checked, host))
        val cold = projectAcquisitionDownloadPresentation(canonical, DownloadMaterializationChecks.Empty, host)
        assertTrue(cold.successfulChecks.isEmpty(), "A new process has no reconstructed success reference")
        assertEquals(DownloadStatus.VERIFYING, cold.states.getValue(job.id).status)
    }

    @Test fun sameAssetIdCannotBorrowAReferenceAfterMaterializationIdentityChanges() {
        val checked = DownloadMaterializationChecks.Empty.recordSuccess(job.id, asset)
        val changed = listOf(
            asset.copy(uri = "/authored/other"), asset.copy(sizeBytes = asset.sizeBytes + 1),
            asset.copy(sha256 = "b".repeat(64)), asset.copy(representationId = "other-key"),
            asset.copy(mimeType = "audio/ogg"), asset.copy(codecs = "opus"),
            asset.copy(validatorGeneration = 1), asset.copy(ownershipGeneration = 2),
            asset.copy(completedEpochMs = 1), asset.copy(measuredDurationMs = 12),
            asset.copy(materializerAttemptId = "other-attempt"), asset.copy(materializerDownloadId = "other-job"),
            asset.copy(producer = producer.copy(bindingId = ExecutionBindingId("r2"))),
        )
        changed.forEach { replacement ->
            assertEquals(asset.id, replacement.id)
            val canonical = view(state().copy(materializations = mapOf(replacement.id to replacement)))
            val shown = projectAcquisitionDownloadPresentation(canonical, checked, host)
            assertTrue(shown.successfulChecks.isEmpty())
            assertEquals(DownloadStatus.VERIFYING, shown.states.getValue(job.id).status)
        }
    }

    @Test fun changedHistorySuppressesProofButCleanupReadinessCannotHideAndResurfaceAnOldRef() {
        val checked = DownloadMaterializationChecks.Empty.recordSuccess(job.id, asset)
        val changedHistory = state().copy(physicalAttempts = mapOf(attempt.id to attempt.copy(ownershipGeneration = 2)))
        assertTrue(checkedPhysicalDownloadIds(changedHistory, checked).isEmpty())
        assertTrue(projectAcquisitionDownloadPresentation(view(changedHistory), checked, host).successfulChecks.isEmpty())
        val cleanup = state().copy(physicalDownloads = mapOf(job.id to job.copy(cleanupPending = true)))
        assertTrue(checkedPhysicalDownloadIds(cleanup, checked).isEmpty(), "Cleanup still rejects physical readiness")
        val pending = projectAcquisitionDownloadPresentation(view(cleanup), checked, host)
        val released = projectAcquisitionDownloadPresentation(view(), checked, host)
        assertSame(checked.successfulChecks.getValue(job.id), pending.successfulChecks.getValue(job.id))
        assertSame(pending.successfulChecks.getValue(job.id), released.successfulChecks.getValue(job.id))
        assertTrue(pending.states.getValue(job.id).cleanupPending)
        assertSame(asset, checked.successfulChecks.getValue(job.id).asset)
    }

    @Test fun unrelatedRevisionHostAndMaintenanceAdmissionDoNotCreateANewerSuccessfulCheck() {
        val checked = DownloadMaterializationChecks.Empty.recordSuccess(job.id, asset)
        val admitted = job.copy(ownershipGeneration = 2, leaseOwner = "authored-maintenance",
            leaseExpiresEpochMs = 50_000, maintenanceKind = PhysicalDownloadMaintenanceKind.VALIDATE)
        val busy = state().copy(libraryRevision = 19, physicalDownloads = mapOf(job.id to admitted))
        val failed = state().copy(physicalDownloads = mapOf(job.id to job.copy(status = DownloadStatus.FAILED,
            failure = AppFailure(FailureCode.CORRUPT_MEDIA, "authored_failed_recheck"))))
        val finalizing = state().copy(physicalFinalizations = mapOf(job.id to PhysicalDownloadFinalization(
            job.id, producer, "/authored/stage", asset.uri, checkNotNull(asset.sha256), asset.sizeBytes,
            attemptId = attempt.id, representationId = asset.representationId, assetId = asset.id)))
        listOf(busy, failed, finalizing).forEach { current ->
            val shown = projectAcquisitionDownloadPresentation(view(current), checked, host.copy(offlineOnly = true))
            assertSame(checked.successfulChecks.getValue(job.id), shown.successfulChecks.getValue(job.id))
            assertTrue(shown.view.acquisition.assetUses.isEmpty())
        }
        assertNotNull(projectPhysicalDownloadStates(busy, checked, host).getValue(job.id).leaseOwner,
            "A physical reference is not a native/lease-drain acknowledgement or local playback grant")
        assertEquals(DownloadStatus.FAILED, projectPhysicalDownloadStates(failed, checked, host).getValue(job.id).status)
        assertTrue(checkedPhysicalDownloadIds(finalizing, checked).isEmpty())
        assertEquals(DownloadStatus.VERIFYING, projectPhysicalDownloadStates(finalizing, checked, host).getValue(job.id).status)
    }

    @Test fun legacyMapAndDtoCompatibilityNeverMintSuccessfulCheckReferences() {
        val shown = projectAcquisitionDownloadPresentation(view(), mapOf(job.id to asset), host)
        assertEquals(DownloadStatus.COMPLETE, shown.states.getValue(job.id).status)
        assertTrue(shown.successfulChecks.isEmpty())
        assertTrue(DownloadAcquisitionPresentation(view(), shown.states).successfulChecks.isEmpty())
    }

    private fun view(value: AcquisitionRepositoryState = state()) =
        LibraryAcquisitionView(AppSnapshot(revision = value.libraryRevision), value)

    private fun state() = AcquisitionRepositoryState(0, physicalDownloads = mapOf(job.id to job),
        physicalAttempts = mapOf(attempt.id to attempt), materializations = mapOf(asset.id to asset))

    private val producer = PhysicalDownloadProducer.Typed(ExecutableSubjectId("subject"),
        ExecutionBindingId("r1"), SourceRecordingId("c"))
    private val asset = MaterializationAsset(MaterializationAssetId("asset"), producer, "/authored/local", 12,
        mimeType = "audio/mpeg", codecs = "mp3", sha256 = "a".repeat(64), representationId = "key",
        materializerDownloadId = "job", materializerAttemptId = "original", ownershipGeneration = 1)
    private val job = PhysicalDownloadJob("job", producer, DownloadStatus.COMPLETE,
        assetId = asset.id, representationId = "key", ownershipGeneration = 1)
    private val attempt = PhysicalDownloadAttempt("original", "job", producer, 1, 1,
        DownloadAttemptStatus.SUCCEEDED, representationId = "key")
    private val host = DownloadPhysicalHostState(true, false, false, true, 0, 0, null, false, 2,
        DownloadNetworkState.CONNECTED)
}
