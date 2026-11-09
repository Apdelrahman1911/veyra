package dev.veyra.downloads

import dev.veyra.data.AcquisitionRepositoryState
import dev.veyra.data.LibraryAcquisitionView
import dev.veyra.model.*
import kotlin.test.*

/** Pure display/identity rules. The supplied checked map is an authored receipt, not file proof. */
class DownloadPhysicalPresentationTest {
    @Test fun durableCompleteAloneProjectsCheckingInANewProcess() {
        assertEquals(DownloadStatus.VERIFYING, projectPhysicalDownloadStates(state(), emptyMap(), host).getValue("job").status)
    }

    @Test fun exactCurrentProcessMaterializationPreservesPhysicalCompletionButAddsNoR2Use() {
        val state = state()
        val projected = projectPhysicalDownloadStates(state, mapOf("job" to asset), host)
        assertEquals(DownloadStatus.COMPLETE, projected.getValue("job").status)
        assertTrue(state.assetUses.isEmpty())
    }

    @Test fun laterValidationOwnershipDoesNotRelabelTheOriginalMaterializer() {
        val later = job.copy(ownershipGeneration = 4)
        val checked = checkedPhysicalDownloadIds(state(later), mapOf("job" to asset))
        assertEquals(setOf("job"), checked)
        assertEquals(1L, asset.ownershipGeneration)
    }

    @Test fun changedOriginalAttemptInvalidatesTheOldProcessReceipt() {
        val changed = state().copy(physicalAttempts = mapOf(attempt.id to attempt.copy(ownershipGeneration = 4)))
        assertTrue(checkedPhysicalDownloadIds(changed, mapOf("job" to asset)).isEmpty())
    }

    @Test fun aDifferentCurrentKeyCannotBorrowAnEarlierCheckedFile() {
        val changed = state(job.copy(representationId = "other"))
        assertEquals(DownloadStatus.VERIFYING,
            projectPhysicalDownloadStates(changed, mapOf("job" to asset), host).getValue("job").status)
    }

    @Test fun cleanupReservationInvalidatesAFileCheckWithoutChangingItsHistoricalReceipt() {
        assertTrue(checkedPhysicalDownloadIds(state(job.copy(cleanupPending = true)), mapOf("job" to asset)).isEmpty())
        assertEquals(asset, state().materializations.getValue(asset.id))
    }

    @Test fun aSuspendedHostShowsUnvalidatedCompletionAsNeedsForeground() {
        val projected = projectPhysicalDownloadStates(state(), emptyMap(), host.copy(suspended = true)).getValue("job")
        assertEquals(DownloadStatus.NEEDS_FOREGROUND, projected.status)
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, projected.failure?.code)
    }

    @Test fun unknownSchema2CompletionRequiresExplicitAcquisitionInsteadOfAnEndlessCheckingState() {
        val unknown = job.copy(producer = PhysicalDownloadProducer.Schema2("media", null, null))
        val projected = projectPhysicalDownloadStates(state(unknown), emptyMap(), host).getValue("job")
        assertEquals(DownloadStatus.NEEDS_FOREGROUND, projected.status)
        assertEquals(FailureCode.RECOVERY_REQUIRED, projected.failure?.code)
    }

    @Test fun neutralTypedDownloadsNeverAppearAsNewLegacyMediaOrOfflineAssets() {
        val snapshot = AppSnapshot()
        val projected = projectLegacyPhysicalDownloads(LibraryAcquisitionView(snapshot, state()), mapOf("job" to asset), host)
        assertEquals(snapshot, projected)
        assertTrue(projected.downloads.isEmpty())
        assertTrue(projected.media.isEmpty())
    }

    @Test fun actualSchema2ProjectionUsesTheSameCheckedPhysicalFile() {
        val (view, legacyAsset) = legacyView()
        val projected = projectLegacyPhysicalDownloads(view, mapOf("job" to legacyAsset), host)
        assertEquals(DownloadStatus.COMPLETE, projected.downloads.getValue("job").status)
        assertEquals(view.snapshot.downloads.getValue("job").asset, projected.downloads.getValue("job").asset)
        assertEquals(view.snapshot.revision, projected.revision)
    }

    @Test fun aMismatchedLegacyUriCannotBeBlessedByACanonicalReceipt() {
        val (view, legacyAsset) = legacyView()
        val original = view.snapshot.downloads.getValue("job")
        val wrong = view.copy(snapshot = view.snapshot.copy(downloads = mapOf("job" to
            original.copy(asset = checkNotNull(original.asset).copy(uri = "/authored/wrong")))))
        val projected = projectLegacyPhysicalDownloads(wrong, mapOf("job" to legacyAsset), host).downloads.getValue("job")
        assertEquals(DownloadStatus.NEEDS_FOREGROUND, projected.status)
        assertEquals(FailureCode.RECOVERY_REQUIRED, projected.failure?.code)
    }

    @Test fun pairedPresentationRetainsTheExactCanonicalViewAndProjectsCheckingFromThatViewOnly() {
        val before = LibraryAcquisitionView(AppSnapshot(revision = 7), state().copy(libraryRevision = 7))
        val old = projectAcquisitionDownloadPresentation(before, mapOf("job" to asset), host)
        assertSame(before, old.view)
        assertEquals(DownloadStatus.COMPLETE, old.states.getValue("job").status)
        val changedAsset = asset.copy(sha256 = "b".repeat(64))
        val after = LibraryAcquisitionView(AppSnapshot(revision = 8), state().copy(libraryRevision = 8,
            materializations = mapOf(changedAsset.id to changedAsset)))
        val current = projectAcquisitionDownloadPresentation(after, mapOf("job" to asset), host)
        assertSame(after, current.view)
        assertEquals(8L, current.view.snapshot.revision)
        assertEquals(current.view.snapshot.revision, current.view.acquisition.libraryRevision)
        assertEquals(DownloadStatus.VERIFYING, current.states.getValue("job").status)
        assertEquals(DownloadStatus.COMPLETE, old.states.getValue("job").status)
        assertEquals(asset, old.view.acquisition.materializations.getValue(asset.id))
    }

    @Test fun pairedProjectionDoesNotFabricateLegacyMediaOrRewriteTheDurableCompleteRow() {
        val view = LibraryAcquisitionView(AppSnapshot(), state())
        val paired = projectAcquisitionDownloadPresentation(view, emptyMap(), host.copy(started = false))
        assertSame(view, paired.view)
        assertTrue(paired.view.snapshot.media.isEmpty())
        assertTrue(paired.view.snapshot.downloads.isEmpty())
        assertEquals(DownloadStatus.COMPLETE, paired.view.acquisition.physicalDownloads.getValue("job").status)
        assertEquals(DownloadStatus.VERIFYING, paired.states.getValue("job").status)
        assertTrue(paired.view.acquisition.assetUses.isEmpty())
    }

    private fun legacyView(): Pair<LibraryAcquisitionView, MaterializationAsset> {
        val producer = PhysicalDownloadProducer.Schema2("media", ExecutionBindingId("legacy-binding"), SourceRecordingId("c"))
        val legacyAsset = asset.copy(producer = producer, materializerAttemptId = null, ownershipGeneration = 0)
        val legacyJob = job.copy(producer = producer)
        val offline = OfflineAsset(legacyAsset.id.value, "media", legacyAsset.uri, legacyAsset.sizeBytes,
            mimeType = legacyAsset.mimeType, codecs = legacyAsset.codecs, sha256 = legacyAsset.sha256,
            representationId = legacyAsset.representationId, executionBindingId = producer.bindingId?.value)
        val record = DownloadRecord("job", "media", DownloadStatus.COMPLETE, asset = offline,
            representationId = "key", executionBindingId = producer.bindingId?.value)
        return LibraryAcquisitionView(AppSnapshot(downloads = mapOf("job" to record)),
            state(legacyJob).copy(materializations = mapOf(legacyAsset.id to legacyAsset), physicalAttempts = emptyMap())) to legacyAsset
    }

    private fun state(value: PhysicalDownloadJob = job) = AcquisitionRepositoryState(0,
        physicalDownloads = mapOf(value.id to value), physicalAttempts = mapOf(attempt.id to attempt),
        materializations = mapOf(asset.id to asset))

    private val producer = PhysicalDownloadProducer.Typed(ExecutableSubjectId("subject"), ExecutionBindingId("r1"), SourceRecordingId("c"))
    private val asset = MaterializationAsset(MaterializationAssetId("asset"), producer, "/authored/local", 12,
        mimeType = "audio/mpeg", codecs = "mp3", sha256 = "a".repeat(64), representationId = "key",
        materializerDownloadId = "job", materializerAttemptId = "original", ownershipGeneration = 1)
    private val job = PhysicalDownloadJob("job", producer, DownloadStatus.COMPLETE,
        assetId = asset.id, representationId = "key", ownershipGeneration = 1)
    private val attempt = PhysicalDownloadAttempt("original", "job", producer, 1, 1,
        DownloadAttemptStatus.SUCCEEDED, representationId = "key")
    private val host = DownloadPhysicalHostState(true, false, false, true, 0, 0, null, false, 2, DownloadNetworkState.CONNECTED)
}
