package dev.veyra.downloads

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import kotlin.test.*

/** Pure authored identity/state cases, NOT evidence that real bytes or a native codec passed. */
class DownloadMaterializationEvidenceTest {
    @Test fun actualNegativeAtomicallyRemovesCurrentValidityWithoutHidingC0History() {
        val owner = DownloadMaterializationEvidence(LibraryTransactionOwner())
        owner.recordPhysicalSuccess(job.id, asset)
        val before = owner.checks.value
        val c0 = before.successfulChecks.getValue(job.id)
        val attempt = owner.admit(job.id, asset, binding, request)
        val stamp = assertNotNull(owner.reject(attempt, negative()))
        val after = owner.checks.value
        assertNotSame(before, after)
        assertSame(c0, after.successfulChecks.getValue(job.id))
        assertTrue(after.currentValidAssets.isEmpty())
        assertSame(asset, before.currentValidAssets.getValue(job.id))
        val shown = projectAcquisitionDownloadPresentation(view, after, host)
        assertSame(view, shown.view)
        assertSame(c0, shown.successfulChecks.getValue(job.id))
        assertEquals(DownloadStatus.VERIFYING, shown.states.getValue(job.id).status)
        assertEquals(view.acquisition.assetUses, shown.view.acquisition.assetUses, "No durable R2 grant or revocation")
        assertTrue(owner.owns(stamp))
        assertSame(c0, stamp.successfulCheck)
        assertNull(stamp.member)
        assertNull(stamp.expectedClaim)
    }

    @Test fun sameLibraryTokenIsNotAnIssuerAndWrongInputCannotCreateARejectionReceipt() {
        val token = LibraryTransactionOwner()
        val owner = DownloadMaterializationEvidence(token)
        val foreign = DownloadMaterializationEvidence(token)
        owner.recordPhysicalSuccess(job.id, asset)
        val before = owner.checks.value
        val attempt = owner.admit(job.id, asset, binding, request)
        assertNull(foreign.reject(attempt, negative()))
        assertNull(owner.reject(attempt, DownloadMaterializationNegative.Native(
            asset.validationInput().copy(uri = "/authored/different-file"), failure)))
        assertSame(before, owner.checks.value)
        val actual = assertNotNull(owner.reject(attempt, negative()))
        assertTrue(owner.owns(actual))
        assertFalse(foreign.owns(actual), "Equal Room token never authorizes another registry's receipt")
        assertFalse(actual.toString().contains(asset.uri))
    }

    @Test fun delayedC0CannotRevokeC1ForTheSameAssetOrAReplacementFullAsset() {
        val owner = DownloadMaterializationEvidence(LibraryTransactionOwner())
        owner.recordPhysicalSuccess(job.id, asset)
        val old = owner.admit(job.id, asset, binding, request)
        owner.recordPhysicalSuccess(job.id, asset)
        val c1 = owner.checks.value
        val late = assertNotNull(owner.reject(old, negative()))
        assertSame(c1, owner.checks.value)
        assertNotSame(late.successfulCheck, c1.successfulChecks.getValue(job.id))
        val replacement = asset.copy(uri = "/authored/replacement", ownershipGeneration = 2)
        owner.recordPhysicalSuccess(job.id, replacement)
        val c2 = owner.checks.value
        assertNotNull(owner.reject(old, negative()))
        assertSame(c2, owner.checks.value)
        assertEquals(replacement, c2.currentValidAssets.getValue(job.id))
    }

    @Test fun exactDeletionClearsBothEntriesButCannotClearANewerFullAsset() {
        val owner = DownloadMaterializationEvidence(LibraryTransactionOwner())
        owner.recordPhysicalSuccess(job.id, asset)
        val shown = owner.checks.value
        owner.forgetDeleted(job.id, asset.copy(uri = "/authored/not-this-file"))
        owner.forgetDeleted("another-job", asset)
        assertSame(shown, owner.checks.value)
        owner.forgetDeleted(job.id, asset)
        assertTrue(owner.checks.value.successfulChecks.isEmpty())
        assertTrue(owner.checks.value.currentValidAssets.isEmpty())
        assertSame(asset, shown.currentValidAssets.getValue(job.id))
    }

    @Test fun explicitReceiptRetainsExactMemberAndClaimWhileResolverReceiptInventsNeither() {
        val owner = DownloadMaterializationEvidence(LibraryTransactionOwner())
        owner.recordPhysicalSuccess(job.id, asset)
        val member = AcquisitionDownloadMemberKey(DownloadBatchId("batch"), 2, 7,
            ExecutionOccurrenceId("occurrence"), request, binding)
        val claim = DownloadClaim(DownloadClaimId("claim"), DownloadClaimOwner.BatchMember(member.batchId, 2),
            job.id, binding, DownloadBatchIntent.PAUSED, true, 10, 4, 7)
        val attempt = owner.admit(job.id, asset, binding, request, member, claim)
        val stamp = assertNotNull(owner.reject(attempt, negative()))
        assertSame(member, stamp.member)
        assertSame(claim, stamp.expectedClaim)
        assertEquals(request, stamp.requestId)
        assertEquals(binding, stamp.requestedBindingId)
        assertEquals(job.id, stamp.downloadId)
        assertSame(asset, stamp.asset)
        val resolver = assertNotNull(owner.reject(owner.admit(job.id, asset, binding, request), negative()))
        assertNull(resolver.member)
        assertNull(resolver.expectedClaim)
        assertSame(stamp.successfulCheck, resolver.successfulCheck, "Repeated negatives never hide/reissue C0")
    }

    @Test fun genericUnavailableAndFreshApplicationOwnerCannotReconstructPhysicalEvidence() {
        val owner = DownloadMaterializationEvidence(LibraryTransactionOwner())
        owner.recordPhysicalSuccess(job.id, asset)
        assertNull(AcquisitionDownloadAssetCheck.Unavailable(failure).validationRejection)
        val cold = DownloadMaterializationEvidence(LibraryTransactionOwner())
        assertTrue(cold.checks.value.successfulChecks.isEmpty())
        assertTrue(cold.checks.value.currentValidAssets.isEmpty())
        assertEquals(DownloadStatus.VERIFYING, projectAcquisitionDownloadPresentation(view, cold.checks.value, host)
            .states.getValue(job.id).status)
    }

    private val binding = ExecutionBindingId("r2")
    private val request = AcquisitionRequestId("request-r2")
    private val producer = PhysicalDownloadProducer.Typed(ExecutableSubjectId("subject"), ExecutionBindingId("r1"), SourceRecordingId("c"))
    private val asset = MaterializationAsset(MaterializationAssetId("asset"), producer, "/authored/file", 12,
        mimeType = "audio/mpeg", codecs = "mp3", sha256 = "a".repeat(64), representationId = "key",
        materializerDownloadId = "job", materializerAttemptId = "original", ownershipGeneration = 1)
    private val job = PhysicalDownloadJob("job", producer, DownloadStatus.COMPLETE, assetId = asset.id,
        representationId = "key", ownershipGeneration = 1)
    private val original = PhysicalDownloadAttempt("original", job.id, producer, 1, 1,
        DownloadAttemptStatus.SUCCEEDED, representationId = "key")
    private val view = LibraryAcquisitionView(AppSnapshot(), AcquisitionRepositoryState(0,
        physicalDownloads = mapOf(job.id to job), physicalAttempts = mapOf(original.id to original),
        materializations = mapOf(asset.id to asset), materializationReceipts = mapOf(asset.id to asset.receipt()),
        assetUses = setOf(AssetUse(binding, asset.id, 1, 10))))
    private val host = DownloadPhysicalHostState(true, false, false, true, 0, 0, null, false, 2, DownloadNetworkState.CONNECTED)
    private val failure = AppFailure(FailureCode.CORRUPT_MEDIA, "authored_negative")
    private fun negative() = DownloadMaterializationNegative.Native(asset.validationInput(), failure)
}
