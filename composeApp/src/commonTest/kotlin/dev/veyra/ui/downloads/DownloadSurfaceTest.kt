package dev.veyra.ui.downloads

import dev.veyra.data.AcquisitionRepositoryState
import dev.veyra.data.LibraryAcquisitionView
import dev.veyra.downloads.DownloadAcquisitionPresentation
import dev.veyra.model.AcquisitionJobState
import dev.veyra.model.AcquisitionOutcome
import dev.veyra.model.AcquisitionRequest
import dev.veyra.model.AcquisitionRequestId
import dev.veyra.model.AcquisitionEntryKind
import dev.veyra.model.AdmissionScopeId
import dev.veyra.model.AssetUse
import dev.veyra.model.AppSnapshot
import dev.veyra.model.DownloadBatch
import dev.veyra.model.DownloadBatchId
import dev.veyra.model.DownloadBatchIntent
import dev.veyra.model.DownloadBatchMember
import dev.veyra.model.DownloadBatchMemberState
import dev.veyra.model.DownloadClaim
import dev.veyra.model.DownloadClaimId
import dev.veyra.model.DownloadClaimOwner
import dev.veyra.model.DownloadRecord
import dev.veyra.model.DownloadStatus
import dev.veyra.model.ExecutableSubject
import dev.veyra.model.ExecutableSubjectId
import dev.veyra.model.ExecutionBinding
import dev.veyra.model.ExecutionBindingId
import dev.veyra.model.ExecutionBindingOrigin
import dev.veyra.model.ExecutionOccurrenceId
import dev.veyra.model.FirstResultObservation
import dev.veyra.model.FirstResultReceipt
import dev.veyra.model.FirstResultReceiptId
import dev.veyra.model.MaterializationAsset
import dev.veyra.model.MaterializationAssetId
import dev.veyra.model.MediaCapture
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.OfflineAsset
import dev.veyra.model.OfflineAssetKind
import dev.veyra.model.PhysicalDownloadJob
import dev.veyra.model.PhysicalDownloadMaintenanceKind
import dev.veyra.model.PhysicalDownloadProducer
import dev.veyra.model.SourceCollectionCapture
import dev.veyra.model.SourceCollectionMetadata
import dev.veyra.model.SourceEntry
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRecording
import dev.veyra.model.SourceRecordingId
import dev.veyra.model.SourceRef
import dev.veyra.model.receipt
import dev.veyra.ui.music.libraryMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure tests of the one UI projection wired to the existing screens; no Room/I/O/native work. */
class DownloadSurfaceTest {
    private val media = MediaItem("music", "Authored catalogue track", MediaKind.MUSIC,
        SourceRef(SourceProvider.YOUTUBE, "catalogue"), creator = "Fixture")
    private val sourceTitle = "Captured source recording — تسجيل من المصدر"

    private data class CatalogueState(
        val state: AcquisitionRepositoryState,
        val bindingId: ExecutionBindingId,
        val otherBindingId: ExecutionBindingId,
        val job: PhysicalDownloadJob,
        val asset: MaterializationAsset,
        val claim: DownloadClaim,
    )

    private fun catalogueState(complete: Boolean, useR1: Boolean = true, useR2: Boolean = false,
        r2Recording: SourceRecordingId? = null, cleanup: Boolean = false,
        maintenance: PhysicalDownloadMaintenanceKind? = null): CatalogueState {
        val requestR1 = AcquisitionRequest(AcquisitionRequestId("request-r1"), media.title, MediaCapture(media), 1)
        val requestR2 = AcquisitionRequest(AcquisitionRequestId("request-r2"), media.title, MediaCapture(media), 2)
        val entry = SourceEntry(AcquisitionEntryKind.VIDEO, "AAAAAAAAAAA", "Authored first recording",
            "https://www.youtube.com/watch?v=AAAAAAAAAAA", ordinal = 0)
        val receiptR1 = FirstResultReceipt.Observed(FirstResultReceiptId("receipt-r1"),
            FirstResultObservation(requestR1.id, media.title, entry, entry.publicUrl), 3)
        val receiptR2 = FirstResultReceipt.Observed(FirstResultReceiptId("receipt-r2"),
            FirstResultObservation(requestR2.id, media.title, entry, entry.publicUrl), 4)
        val recording = SourceRecording(SourceRecordingId("recording-r1"), "AAAAAAAAAAA",
            "https://www.youtube.com/watch?v=AAAAAAAAAAA")
        val otherRecording = SourceRecording(SourceRecordingId("recording-r2"), "BBBBBBBBBBB",
            "https://www.youtube.com/watch?v=BBBBBBBBBBB")
        val subject = ExecutableSubject.CatalogMedia(ExecutableSubjectId(media.id), media.id)
        val bindingR1 = ExecutionBinding(ExecutionBindingId("binding-r1"), subject.id, receiptR1.id,
            recording.id, ExecutionBindingOrigin.TYPED)
        val bindingR2 = ExecutionBinding(ExecutionBindingId("binding-r2"), subject.id, receiptR2.id,
            r2Recording ?: recording.id, ExecutionBindingOrigin.TYPED)
        val assetId = MaterializationAssetId("asset")
        val producer = PhysicalDownloadProducer.Typed(subject.id, bindingR1.id, recording.id)
        val asset = MaterializationAsset(assetId, producer, "/owned/authored-fixture", 4_096,
            kind = OfflineAssetKind.FILE, mimeType = "audio/mpeg", codecs = "mp3",
            sha256 = "A".repeat(64), representationId = "authored-representation", completedEpochMs = 9,
            measuredDurationMs = 12_345, materializerDownloadId = "job", materializerAttemptId = "attempt",
            ownershipGeneration = 1)
        val job = PhysicalDownloadJob("job", producer,
            status = if (complete) DownloadStatus.COMPLETE else DownloadStatus.RUNNING,
            bytesDownloaded = if (complete) 4_096 else 1_024, totalBytes = 4_096,
            assetId = assetId, representationId = "authored-representation", validatorGeneration = 0,
            updatedEpochMs = 10, attemptCount = 1, activeAttemptId = "attempt", ownershipGeneration = 1,
            cleanupPending = cleanup, maintenanceKind = maintenance)
        val claims = buildList {
            add(DownloadClaim(DownloadClaimId("claim-r1"), DownloadClaimOwner.Standalone("job"), "job",
                bindingR1.id, DownloadBatchIntent.ACTIVE, true, 11, generation = 1))
            if (useR2) add(DownloadClaim(DownloadClaimId("claim-r2"), DownloadClaimOwner.Standalone("job"), "job",
                bindingR2.id, DownloadBatchIntent.ACTIVE, true, 12, generation = 1))
        }
        val uses = buildSet {
            if (useR1) add(AssetUse(bindingR1.id, assetId, 1, 20))
            if (useR2) add(AssetUse(bindingR2.id, assetId, 1, 21))
        }
        val records = listOf(requestR1 to receiptR1 to bindingR1, requestR2 to receiptR2 to bindingR2)
            .associate { (pair, binding) ->
                val (request, receipt) = pair
                request.id to dev.veyra.model.AcquisitionRecord(request, AcquisitionJobState.COMMITTED,
                    outcome = AcquisitionOutcome.Single(request.id, receipt.id, binding.id), updatedEpochMs = 5)
            }
        val state = AcquisitionRepositoryState(
            libraryRevision = 1,
            requests = records,
            receipts = mapOf(receiptR1.id to receiptR1, receiptR2.id to receiptR2),
            recordings = mapOf(recording.id to recording, otherRecording.id to otherRecording),
            subjects = mapOf(subject.id to subject),
            bindings = mapOf(bindingR1.id to bindingR1, bindingR2.id to bindingR2),
            claims = claims.associateBy { it.id },
            assetUses = uses,
            physicalDownloads = mapOf(job.id to job),
            materializations = mapOf(assetId to asset),
            materializationReceipts = mapOf(assetId to asset.receipt()),
        )
        return CatalogueState(state, bindingR1.id, bindingR2.id, job, asset, claims.first())
    }

    private fun presentation(built: CatalogueState, states: Map<String, PhysicalDownloadJob> = mapOf(built.job.id to built.job),
        successfulChecks: Map<String, dev.veyra.downloads.DownloadMaterializationCheck> = emptyMap()): DownloadAcquisitionPresentation =
        DownloadAcquisitionPresentation(LibraryAcquisitionView(AppSnapshot(revision = 1, media = mapOf(media.id to media)), built.state),
            states, successfulChecks)

    @Test
    fun typedCatalogueJobIsVisibleFromTheTypedJobsEvenWhenLegacyDownloadsAreEmpty() {
        val built = catalogueState(complete = false, useR1 = false)
        val surface = downloadSurface(AppSnapshot(media = mapOf(media.id to media)), presentation(built,
            states = mapOf(built.job.id to built.job.copy(status = DownloadStatus.RUNNING, bytesDownloaded = 1_024))))
        val entry = surface.forMedia(media.id)
        assertEquals("job", entry?.id)
        assertEquals(DownloadStatus.RUNNING, entry?.status)
        assertEquals(1_024L, entry?.bytesDownloaded)
        assertEquals(4_096L, entry?.totalBytes)
        assertEquals(DownloadSurfaceOrigin.TYPED_CATALOGUE, entry?.origin)
        assertFalse(entry?.ready == true)
        assertTrue(media.id in surface.catalogueMediaIds)
        assertFalse(surface.isDownloaded(media.id))

        val queued = downloadSurface(AppSnapshot(media = mapOf(media.id to media)), presentation(built,
            states = mapOf(built.job.id to built.job.copy(status = DownloadStatus.QUEUED, bytesDownloaded = 0))))
        assertEquals(DownloadStatus.QUEUED, queued.forMedia(media.id)?.status)

        val cancelled = downloadSurface(AppSnapshot(media = mapOf(media.id to media)), presentation(built,
            states = mapOf(built.job.id to built.job.copy(status = DownloadStatus.CANCELLED))))
        assertEquals(DownloadStatus.CANCELLED, cancelled.entries.single().status,
            "An invoked job stays represented after cancellation, not only after success")
    }

    @Test
    fun coldCompleteProjectionShowsCheckingAndOnlyPairedCheckedStatesPlusExactUseBecomeReady() {
        val built = catalogueState(complete = true)
        val cold = presentation(built, states = mapOf(built.job.id to built.job.copy(
            status = DownloadStatus.NEEDS_FOREGROUND, failure = null)))
        val coldEntry = downloadSurface(AppSnapshot(), cold).forMedia(media.id)
        assertEquals(DownloadStatus.NEEDS_FOREGROUND, coldEntry?.status)
        assertTrue(coldEntry?.checking == true)
        assertFalse(coldEntry?.ready == true)
        assertEquals(0L, downloadSurface(AppSnapshot(), cold).storageBytes())
        assertEquals(null, cold.verifiedDownload(built.bindingId))

        val ready = presentation(built)
        val readyEntry = downloadSurface(AppSnapshot(), ready).forMedia(media.id)
        assertEquals(DownloadStatus.COMPLETE, readyEntry?.status)
        assertFalse(readyEntry?.checking == true)
        assertTrue(readyEntry?.ready == true)
        assertEquals("binding-r1", readyEntry?.bindingId)
        assertEquals("asset", readyEntry?.assetId)
        assertEquals(4_096L, downloadSurface(AppSnapshot(), ready).storageBytes())
        assertEquals("job", ready.verifiedDownload(built.bindingId)?.jobId)
    }

    @Test
    fun secondBindingRequiresItsOwnExactUseClaimAndRecordingNotJustProducerCompletion() {
        val r1Only = catalogueState(complete = true, useR1 = true, useR2 = false)
        val r1Presentation = presentation(r1Only)
        assertEquals("binding-r1", r1Presentation.verifiedDownload(r1Only.bindingId)?.bindingId)
        assertEquals(null, r1Presentation.verifiedDownload(r1Only.otherBindingId))
        assertFalse(downloadSurface(AppSnapshot(), r1Presentation).entries.single().bindingId == "binding-r2")

        val r2Use = catalogueState(complete = true, useR1 = false, useR2 = true)
        assertEquals("binding-r2", presentation(r2Use).verifiedDownload(r2Use.otherBindingId)?.bindingId)
        assertEquals(null, presentation(r2Use).verifiedDownload(r2Use.bindingId))

        val wrongRecording = catalogueState(complete = true, useR1 = false, useR2 = true,
            r2Recording = SourceRecordingId("recording-r2"))
        // The asset/job producer is R1's recording. An R2 use for another recording must not become Ready.
        assertEquals(null, presentation(wrongRecording).verifiedDownload(wrongRecording.otherBindingId))
        val wrongEntry = downloadSurface(AppSnapshot(), presentation(wrongRecording)).forMedia(media.id)
        assertFalse(wrongEntry?.ready == true)

        val noUse = catalogueState(complete = true, useR1 = false, useR2 = false)
        assertEquals(null, presentation(noUse).verifiedDownload(noUse.bindingId))
        assertEquals(null, presentation(noUse).verifiedDownload(noUse.otherBindingId))
    }

    @Test
    fun cleanupLeaseMaintenanceAndStaleProjectionNeverConferReady() {
        val built = catalogueState(complete = true)
        listOf(
            built.job.copy(cleanupPending = true),
            built.job.copy(leaseOwner = "foreign-owner"),
            built.job.copy(maintenanceKind = PhysicalDownloadMaintenanceKind.CLEANUP),
            built.job.copy(validatorGeneration = 7),
        ).forEach { altered ->
            val state = built.state.copy(physicalDownloads = mapOf(altered.id to altered))
            val paired = DownloadAcquisitionPresentation(
                LibraryAcquisitionView(AppSnapshot(revision = 1), state), mapOf(altered.id to altered),
            )
            assertEquals(null, paired.verifiedDownload(built.bindingId), "Altered ${altered.id} must not be Ready")
            val projected = DownloadAcquisitionPresentation(
                LibraryAcquisitionView(AppSnapshot(revision = 1), state),
                mapOf(altered.id to altered.copy(status = DownloadStatus.NEEDS_FOREGROUND)),
            )
            assertFalse(downloadSurface(AppSnapshot(), projected).entries.single().ready)
        }
        val cleaning = built.state.copy(
            physicalDownloads = built.state.physicalDownloads + ("other" to built.job.copy(id = "other",
                producer = built.job.producer, cleanupPending = true, assetId = null)),
        )
        val removing = DownloadAcquisitionPresentation(LibraryAcquisitionView(AppSnapshot(revision = 1), cleaning),
            mapOf(built.job.id to built.job, "other" to built.job.copy(id = "other", cleanupPending = true, assetId = null)))
        assertEquals(null, removing.verifiedDownload(built.bindingId))
    }

    @Test
    fun adoptedSchema2RowKeepsOneStableIdAndNeutralSourceRowsNeverCreateCatalogueMembership() {
        val built = catalogueState(complete = true)
        val legacy = DownloadRecord("job", media.id, DownloadStatus.COMPLETE, bytesDownloaded = 4_096,
            totalBytes = 4_096, asset = OfflineAsset("asset", media.id, "/owned/authored-fixture", 4_096,
                representationId = "authored-representation"))
        val legacySnapshot = AppSnapshot(media = mapOf(media.id to media), downloads = mapOf(legacy.id to legacy))
        val adopted = downloadSurface(legacySnapshot, presentation(built))
        val adoptedEntry = adopted.entries.single()
        assertEquals("job", adoptedEntry.id)
        assertEquals(DownloadSurfaceOrigin.TYPED_CATALOGUE, adoptedEntry.origin)
        assertTrue(adoptedEntry.ready)

        val sourceCapture = SourceCollectionCapture(SourceCollectionMetadata(SourceProvider.YOUTUBE, "captured",
            sourceTitle, "https://www.youtube.com/playlist?list=authored", AcquisitionEntryKind.PLAYLIST, null))
        val request = AcquisitionRequest(AcquisitionRequestId("neutral-request"), sourceTitle, sourceCapture, 1)
        val entry = SourceEntry(AcquisitionEntryKind.VIDEO, "CCCCCCCCCCC", "Observed neutral recording",
            "https://www.youtube.com/watch?v=CCCCCCCCCCC", ordinal = 0)
        val receipt = FirstResultReceipt.Observed(FirstResultReceiptId("neutral-receipt"),
            FirstResultObservation(request.id, sourceTitle, entry, entry.publicUrl), 2)
        val recording = SourceRecording(SourceRecordingId("neutral-recording"), "CCCCCCCCCCC",
            requireNotNull(entry.publicUrl))
        val subject = ExecutableSubject.SourceRecordingSubject(ExecutableSubjectId("neutral-subject"), recording.id)
        val binding = ExecutionBinding(ExecutionBindingId("neutral-binding"), subject.id, receipt.id, recording.id,
            ExecutionBindingOrigin.TYPED)
        val producer = PhysicalDownloadProducer.Typed(subject.id, binding.id, recording.id)
        val job = PhysicalDownloadJob("neutral-job", producer, status = DownloadStatus.RUNNING,
            bytesDownloaded = 512, totalBytes = 1_024, updatedEpochMs = 30, ownershipGeneration = 1)
        val neutral = AcquisitionRepositoryState(1,
            requests = mapOf(request.id to dev.veyra.model.AcquisitionRecord(request, AcquisitionJobState.COMMITTED,
                outcome = AcquisitionOutcome.Single(request.id, receipt.id, binding.id), updatedEpochMs = 3)),
            receipts = mapOf(receipt.id to receipt), recordings = mapOf(recording.id to recording),
            subjects = mapOf(subject.id to subject), bindings = mapOf(binding.id to binding),
            physicalDownloads = mapOf(job.id to job))
        val surface = downloadSurface(AppSnapshot(), DownloadAcquisitionPresentation(LibraryAcquisitionView(AppSnapshot(revision = 1), neutral),
            mapOf(job.id to job)))
        val neutralEntry = surface.entries.single()
        assertEquals(DownloadSurfaceOrigin.TYPED_SOURCE, neutralEntry.origin)
        assertEquals(null, neutralEntry.mediaId)
        assertEquals(sourceTitle, neutralEntry.displayTitle)
        assertFalse(neutralEntry.ready)
        assertNull(neutralEntry.bindingId)
        assertTrue(surface.catalogueMediaIds.isEmpty())
        assertTrue(libraryMedia(AppSnapshot(media = mapOf(media.id to media)), surface).isEmpty())
        assertEquals("neutral-job", neutralEntry.id)
    }

    @Test
    fun completedNeutralAssetIsPhysicallyVerifiedButHasNoCatalogueReadinessOrBinding() {
        val sourceCapture = SourceCollectionCapture(SourceCollectionMetadata(SourceProvider.YOUTUBE, "captured-complete",
            sourceTitle, "https://www.youtube.com/playlist?list=authored-complete", AcquisitionEntryKind.PLAYLIST, null))
        val request = AcquisitionRequest(AcquisitionRequestId("neutral-complete-request"), sourceTitle, sourceCapture, 1)
        val entry = SourceEntry(AcquisitionEntryKind.VIDEO, "CCCCCCCCCCC", "Observed neutral recording",
            "https://www.youtube.com/watch?v=CCCCCCCCCCC", ordinal = 0)
        val receipt = FirstResultReceipt.Observed(FirstResultReceiptId("neutral-complete-receipt"),
            FirstResultObservation(request.id, sourceTitle, entry, entry.publicUrl), 2)
        val recording = SourceRecording(SourceRecordingId("neutral-complete-recording"), "CCCCCCCCCCC",
            requireNotNull(entry.publicUrl))
        val subject = ExecutableSubject.SourceRecordingSubject(ExecutableSubjectId("neutral-complete-subject"), recording.id)
        val binding = ExecutionBinding(ExecutionBindingId("neutral-complete-binding"), subject.id, receipt.id, recording.id,
            ExecutionBindingOrigin.TYPED)
        val producer = PhysicalDownloadProducer.Typed(subject.id, binding.id, recording.id)
        val asset = MaterializationAsset(MaterializationAssetId("neutral-complete-asset"), producer,
            "/owned/authored-neutral-complete", 4_096, kind = OfflineAssetKind.FILE, mimeType = "audio/mpeg",
            codecs = "mp3", sha256 = "B".repeat(64), representationId = "authored-neutral-representation",
            completedEpochMs = 9, materializerDownloadId = "neutral-complete-job",
            materializerAttemptId = "neutral-complete-attempt", ownershipGeneration = 1)
        val job = PhysicalDownloadJob("neutral-complete-job", producer, status = DownloadStatus.COMPLETE,
            bytesDownloaded = 4_096, totalBytes = 4_096, assetId = asset.id, representationId = asset.representationId,
            updatedEpochMs = 10, attemptCount = 1, ownershipGeneration = 1)
        val claim = DownloadClaim(DownloadClaimId("neutral-complete-claim"), DownloadClaimOwner.Standalone(job.id),
            job.id, binding.id, DownloadBatchIntent.ACTIVE, true, 11)
        val state = AcquisitionRepositoryState(1,
            requests = mapOf(request.id to dev.veyra.model.AcquisitionRecord(request, AcquisitionJobState.COMMITTED,
                outcome = AcquisitionOutcome.Single(request.id, receipt.id, binding.id), updatedEpochMs = 3)),
            receipts = mapOf(receipt.id to receipt), recordings = mapOf(recording.id to recording),
            subjects = mapOf(subject.id to subject), bindings = mapOf(binding.id to binding),
            claims = mapOf(claim.id to claim), assetUses = setOf(AssetUse(binding.id, asset.id, 1, 12)),
            physicalDownloads = mapOf(job.id to job), materializations = mapOf(asset.id to asset),
            materializationReceipts = mapOf(asset.id to asset.receipt()))
        val complete = DownloadAcquisitionPresentation(LibraryAcquisitionView(AppSnapshot(revision = 1), state),
            mapOf(job.id to job))
        val verified = assertNotNull(complete.verifiedDownload(binding.id))
        assertNull(verified.mediaId)
        val verifiedSurface = downloadSurface(AppSnapshot(), complete)
        val verifiedEntry = verifiedSurface.entries.single()
        assertEquals(DownloadSurfaceOrigin.TYPED_SOURCE, verifiedEntry.origin)
        assertTrue(verifiedEntry.ready)
        assertNull(verifiedEntry.bindingId)
        assertEquals(4_096L, verifiedSurface.storageBytes())
        assertTrue(verifiedSurface.readyMediaIds.isEmpty())

        val checking = DownloadAcquisitionPresentation(LibraryAcquisitionView(AppSnapshot(revision = 1), state),
            mapOf(job.id to job.copy(status = DownloadStatus.NEEDS_FOREGROUND)))
        val checkingEntry = downloadSurface(AppSnapshot(), checking).entries.single()
        assertTrue(checkingEntry.checking)
        assertEquals(0L, downloadSurface(AppSnapshot(), checking).storageBytes())
        assertNull(checking.verifiedDownload(binding.id))
    }

    @Test
    fun retainedStandaloneFailureExposesOnlyTheExactPersistedBinding() {
        val built = catalogueState(complete = true)
        val failed = built.job.copy(status = DownloadStatus.FAILED, failure = null)
        val surface = downloadSurface(AppSnapshot(), presentation(built, mapOf(built.job.id to failed)))
        val target = assertNotNull(surface.entries.single().retainedValidation)
        assertEquals(RetainedValidationTarget(built.job.id, "binding-r1"), target)
        assertFalse(target.isBatchMember)
        assertNull(surface.entries.single().retainedValidation?.batchId)
    }

    @Test
    fun retainedBatchFailureCarriesExactMemberAndRevision() {
        val built = catalogueState(complete = true)
        val claim = DownloadClaim(DownloadClaimId("claim-member"), DownloadClaimOwner.BatchMember(
            DownloadBatchId("batch-1"), 0), built.job.id, built.bindingId, DownloadBatchIntent.ACTIVE, true, 11,
            generation = 1, ownerIntentRevision = 4)
        val member = DownloadBatchMember(0, 0, ExecutionOccurrenceId("occurrence-1"),
            AcquisitionRequestId("request-r1"), built.bindingId, claim.id, DownloadBatchMemberState.AVAILABLE)
        val batch = DownloadBatch(DownloadBatchId("batch-1"), AdmissionScopeId("scope-1"),
            DownloadBatchIntent.ACTIVE, 4, listOf(member), 1, 2)
        val state = built.state.copy(claims = mapOf(claim.id to claim), batches = mapOf(batch.id to batch))
        val failed = built.job.copy(status = DownloadStatus.FAILED)
        val surface = downloadSurface(AppSnapshot(), DownloadAcquisitionPresentation(
            LibraryAcquisitionView(AppSnapshot(revision = 1), state), mapOf(failed.id to failed)))
        assertEquals(RetainedValidationTarget(built.job.id, "binding-r1", "batch-1", 0, 4),
            surface.entries.single().retainedValidation)
    }

    @Test
    fun repeatedBatchIdentityExposesExactChoicesWhileStaleIdentityIsOmitted() {
        val built = catalogueState(complete = true)
        fun claim(id: String, batch: String, revision: Long) = DownloadClaim(DownloadClaimId(id),
            DownloadClaimOwner.BatchMember(DownloadBatchId(batch), 0), built.job.id, built.bindingId,
            DownloadBatchIntent.ACTIVE, true, 11, generation = 1, ownerIntentRevision = revision)
        fun batch(id: String, claimId: String, revision: Long) = DownloadBatch(DownloadBatchId(id),
            AdmissionScopeId("scope-$id"), DownloadBatchIntent.ACTIVE, revision,
            listOf(DownloadBatchMember(0, 0, ExecutionOccurrenceId("occurrence-$id"),
                AcquisitionRequestId("request-r1"), built.bindingId, DownloadClaimId(claimId),
                DownloadBatchMemberState.AVAILABLE)), 1, 2)
        val first = claim("claim-first", "batch-first", 4)
        val repeated = claim("claim-repeated", "batch-repeated", 4)
        val repeatedState = built.state.copy(
            claims = mapOf(first.id to first, repeated.id to repeated),
            batches = mapOf(DownloadBatchId("batch-first") to batch("batch-first", "claim-first", 4),
                DownloadBatchId("batch-repeated") to batch("batch-repeated", "claim-repeated", 4)))
        val failed = built.job.copy(status = DownloadStatus.FAILED)
        val repeatedEntry = downloadSurface(AppSnapshot(), DownloadAcquisitionPresentation(
            LibraryAcquisitionView(AppSnapshot(revision = 1), repeatedState), mapOf(failed.id to failed))).entries.single()
        assertNull(repeatedEntry.retainedValidation,
            "Multiple targets must not be silently collapsed to a newest/first member")
        assertEquals(setOf("batch-first", "batch-repeated"),
            repeatedEntry.retainedValidationChoices.mapNotNull { it.batchId }.toSet())
        assertEquals(listOf(0, 0), repeatedEntry.retainedValidationChoices.map { requireNotNull(it.memberPosition) })

        val stale = claim("claim-stale", "batch-stale", 3)
        val staleState = built.state.copy(claims = mapOf(stale.id to stale),
            batches = mapOf(DownloadBatchId("batch-stale") to batch("batch-stale", "claim-stale", 4)))
        val staleEntry = downloadSurface(AppSnapshot(), DownloadAcquisitionPresentation(
            LibraryAcquisitionView(AppSnapshot(revision = 1), staleState), mapOf(failed.id to failed))).entries.single()
        assertNull(staleEntry.retainedValidation,
            "A changed batch intent revision must invalidate the persisted row action")
        assertTrue(staleEntry.retainedValidationChoices.isEmpty())
    }

    @Test
    fun sharedAssetBytesCountOnceAndSchema2LegacyReadinessIsRetained() {
        val shared = catalogueState(complete = true, useR1 = true, useR2 = true)
        val surface = downloadSurface(AppSnapshot(), presentation(shared))
        assertEquals(4_096L, surface.storageBytes())
        assertEquals(setOf(media.id), surface.readyMediaIds)

        val legacy = DownloadRecord("legacy", media.id, DownloadStatus.COMPLETE,
            asset = OfflineAsset("legacy-asset", media.id, "/owned/legacy", 2_048))
        val legacySurface = legacyDownloadSurface(AppSnapshot(media = mapOf(media.id to media),
            downloads = mapOf(legacy.id to legacy)))
        assertTrue(legacySurface.isDownloaded(media.id))
        assertEquals(2_048L, legacySurface.storageBytes())
        assertEquals("legacy", legacySurface.forMedia(media.id)?.id)
    }

    @Test
    fun neutralRetainedFailureNeverExposesAValidationBinding() {
        val sourceCapture = SourceCollectionCapture(SourceCollectionMetadata(SourceProvider.YOUTUBE, "captured",
            sourceTitle, "https://www.youtube.com/playlist?list=authored", AcquisitionEntryKind.PLAYLIST, null))
        val request = AcquisitionRequest(AcquisitionRequestId("neutral-request"), sourceTitle, sourceCapture, 1)
        val raw = SourceEntry(AcquisitionEntryKind.VIDEO, "CCCCCCCCCCC", "Observed neutral recording",
            "https://www.youtube.com/watch?v=CCCCCCCCCCC", ordinal = 0)
        val receipt = FirstResultReceipt.Observed(FirstResultReceiptId("neutral-receipt"),
            FirstResultObservation(request.id, sourceTitle, raw, raw.publicUrl), 2)
        val recording = SourceRecording(SourceRecordingId("neutral-recording"), "CCCCCCCCCCC",
            requireNotNull(raw.publicUrl))
        val subject = ExecutableSubject.SourceRecordingSubject(ExecutableSubjectId("neutral-subject"), recording.id)
        val binding = ExecutionBinding(ExecutionBindingId("neutral-binding"), subject.id, receipt.id, recording.id,
            ExecutionBindingOrigin.TYPED)
        val producer = PhysicalDownloadProducer.Typed(subject.id, binding.id, recording.id)
        val asset = MaterializationAsset(MaterializationAssetId("neutral-asset"), producer, "/owned/neutral", 4_096,
            kind = OfflineAssetKind.FILE, representationId = "neutral-representation", completedEpochMs = 9)
        val job = PhysicalDownloadJob("neutral-job", producer, status = DownloadStatus.FAILED,
            bytesDownloaded = 4_096, totalBytes = 4_096, assetId = asset.id,
            representationId = asset.representationId, updatedEpochMs = 10, ownershipGeneration = 1)
        val claim = DownloadClaim(DownloadClaimId("neutral-claim"), DownloadClaimOwner.Standalone(job.id), job.id,
            binding.id, DownloadBatchIntent.ACTIVE, true, 11)
        val state = AcquisitionRepositoryState(1,
            requests = mapOf(request.id to dev.veyra.model.AcquisitionRecord(request, AcquisitionJobState.COMMITTED,
                outcome = AcquisitionOutcome.Single(request.id, receipt.id, binding.id), updatedEpochMs = 3)),
            receipts = mapOf(receipt.id to receipt), recordings = mapOf(recording.id to recording),
            subjects = mapOf(subject.id to subject), bindings = mapOf(binding.id to binding),
            claims = mapOf(claim.id to claim), physicalDownloads = mapOf(job.id to job),
            materializations = mapOf(asset.id to asset), materializationReceipts = mapOf(asset.id to asset.receipt()))
        val entry = downloadSurface(AppSnapshot(), DownloadAcquisitionPresentation(
            LibraryAcquisitionView(AppSnapshot(revision = 1), state), mapOf(job.id to job))).entries.single()
        assertEquals(DownloadSurfaceOrigin.TYPED_SOURCE, entry.origin)
        assertNull(entry.bindingId)
        val target = assertNotNull(entry.retainedValidation)
        assertEquals("neutral-binding", target.bindingId)
        assertNull(target.batchId)
        assertTrue(entry.retainedValidationChoices.isEmpty())
        assertNull(entry.mediaId)
        assertFalse(entry.ready)
    }
}
