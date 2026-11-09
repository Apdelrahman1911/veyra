package dev.veyra.app

import dev.veyra.data.getOrThrow
import dev.veyra.downloads.DownloadAcquisitionPresentation
import dev.veyra.model.AppFailure
import dev.veyra.model.DownloadBatch
import dev.veyra.model.DownloadBatchIntent
import dev.veyra.model.DownloadBatchMemberState
import dev.veyra.model.DownloadStatus
import dev.veyra.model.ExecutionBindingId
import dev.veyra.model.FailureCode
import dev.veyra.model.MediaItem
import dev.veyra.model.PhysicalDownloadJob
import dev.veyra.playback.AssetValidation
import dev.veyra.ui.downloads.DownloadSurfaceEntry
import dev.veyra.ui.downloads.RetainedValidationTarget
import dev.veyra.ui.downloads.downloadSurface
import dev.veyra.ui.music.MusicUiAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Connected AppContainer/Room/coordinator coverage for the Task A retained Validate and
 * foreground-handoff actions. Real Room, files and coordinator; source and native validation are
 * authored non-audio fakes. This is not playable-audio, live-source or device evidence.
 */
class TaskAUiRecoveryIntegrationTest {
    private fun entry(presentation: DownloadAcquisitionPresentation, jobId: String): DownloadSurfaceEntry =
        assertNotNull(downloadSurface(presentation.view.snapshot, presentation).entries.singleOrNull { it.id == jobId })

    private fun completedJob(presentation: DownloadAcquisitionPresentation): Pair<PhysicalDownloadJob, ExecutionBindingId> {
        val job = presentation.states.values.single { it.status == DownloadStatus.COMPLETE }
        return job to assertNotNull(presentation.view.acquisition.physicalDownloads.getValue(job.id).producer.bindingId)
    }

    private fun assertRetainedFile(presentation: DownloadAcquisitionPresentation, jobId: String) {
        val job = presentation.view.acquisition.physicalDownloads.getValue(jobId)
        val asset = assertNotNull(job.assetId?.let(presentation.view.acquisition.materializations::get))
        assertTrue(Files.isRegularFile(Path.of(asset.uri)))
        assertContentEquals(TypedDownloadPresentationSupport.NON_AUDIO, Files.readAllBytes(Path.of(asset.uri)))
    }

    @Test
    fun retainedStandaloneValidateUsesTheExactTargetAndIsNotNetworkRetry() = runBlocking {
        withTimeout(180_000L) {
            val directory = TypedDownloadPresentationSupport.workDirectory()
            val item = TypedDownloadPresentationSupport.youtubeItem("task-a-standalone")
            val support = TypedDownloadPresentationSupport(directory)
            try {
                support.initialize()
                support.store.upsertCatalog(listOf(item)).getOrThrow()
                support.app.onMusicAction(MusicUiAction.Download(item))
                val completed = support.awaitPresentation { presentation ->
                    presentation.states.values.any { it.status == DownloadStatus.COMPLETE } &&
                        presentation.view.acquisition.assetUses.isNotEmpty()
                }
                val (job, binding) = completedJob(completed)
                assertEquals(1, support.gateway.transfers.get())

                support.failRetainedStandalone(job.id, binding)
                val failed = support.app.downloads.acquisitionPresentation.value
                val failedEntry = entry(failed, job.id)
                assertEquals(DownloadStatus.FAILED, failedEntry.status)
                assertTrue(failedEntry.hasOwnedBytes)
                assertEquals(RetainedValidationTarget(job.id, binding.value), failedEntry.retainedValidation)
                assertRetainedFile(failed, job.id)
                val transfers = support.gateway.transfers.get()
                val firstResults = support.gateway.firstResults.get()

                support.platform.validateLocal = { AssetValidation.Valid(12_345L) }
                support.app.onMusicAction(MusicUiAction.ValidateRetainedDownload(failedEntry.id,
                    binding.value, failedEntry.retainedValidation?.batchId, failedEntry.retainedValidation?.memberPosition,
                    failedEntry.retainedValidation?.expectedIntentRevision))
                val recovered = support.awaitPresentation { it.states[job.id]?.status == DownloadStatus.COMPLETE }
                assertNull(recovered.states.getValue(job.id).failure)
                assertNull(recovered.states.getValue(job.id).nextRetryEpochMs)
                assertRetainedFile(recovered, job.id)
                assertEquals(transfers, support.gateway.transfers.get(), "Explicit local Validate must not transfer again")
                assertEquals(firstResults, support.gateway.firstResults.get(), "Explicit local Validate must not query a source")
                assertTrue(recovered.view.snapshot.media.isNotEmpty())
            } finally {
                support.close()
            }
        }
    }

    @Test
    fun retainedNeutralSourceValidateUsesTheExactBindingWithoutCatalogueMedia() = runBlocking {
        withTimeout(180_000L) {
            val directory = TypedDownloadPresentationSupport.workDirectory()
            val title = "Captured neutral retained — تسجيل محتفظ به"
            val support = TypedDownloadPresentationSupport(directory)
            try {
                support.initialize()
                val queued = support.enqueueNeutralDownload(title)
                val completed = support.awaitPresentation { presentation ->
                    presentation.states[queued.jobId]?.status == DownloadStatus.COMPLETE &&
                        presentation.view.acquisition.assetUses.any { it.requestedBindingId == queued.bindingId }
                }
                val completedEntry = entry(completed, queued.jobId)
                assertEquals(dev.veyra.ui.downloads.DownloadSurfaceOrigin.TYPED_SOURCE, completedEntry.origin)
                assertNull(completedEntry.mediaId)
                assertNull(completedEntry.bindingId)
                support.failRetainedStandalone(queued.jobId, queued.bindingId)
                val failed = support.app.downloads.acquisitionPresentation.value
                val failedEntry = entry(failed, queued.jobId)
                assertEquals(DownloadStatus.FAILED, failedEntry.status)
                assertEquals(RetainedValidationTarget(queued.jobId, queued.bindingId.value),
                    failedEntry.retainedValidation)
                assertNull(failedEntry.mediaId)
                assertNull(failedEntry.bindingId)
                assertTrue(failed.view.snapshot.media.isEmpty(),
                    "A neutral source row never manufactures catalogue media")

                support.platform.validateLocal = { AssetValidation.Valid(12_345L) }
                support.app.onMusicAction(MusicUiAction.ValidateRetainedDownload(failedEntry.id,
                    queued.bindingId.value))
                val recovered = support.awaitPresentation { it.states[queued.jobId]?.status == DownloadStatus.COMPLETE }
                assertEquals(queued.bindingId, recovered.view.acquisition.assetUses.single().requestedBindingId)
                assertNull(entry(recovered, queued.jobId).bindingId,
                    "Validation is not playback authority; the neutral row exposes no catalogue binding")
                assertNull(entry(recovered, queued.jobId).mediaId)
                assertRetainedFile(recovered, queued.jobId)
            } finally {
                support.close()
            }
        }
    }

    @Test
    fun retainedStandaloneValidationNegativeKeepsExactFailureAndBytes() = runBlocking {
        withTimeout(180_000L) {
            val directory = TypedDownloadPresentationSupport.workDirectory()
            val item = TypedDownloadPresentationSupport.youtubeItem("task-a-negative")
            val support = TypedDownloadPresentationSupport(directory)
            try {
                support.initialize()
                support.store.upsertCatalog(listOf(item)).getOrThrow()
                support.app.onMusicAction(MusicUiAction.Download(item))
                val completed = support.awaitPresentation { presentation ->
                    presentation.states.values.any { it.status == DownloadStatus.COMPLETE } &&
                        presentation.view.acquisition.assetUses.isNotEmpty()
                }
                val (job, binding) = completedJob(completed)
                support.failRetainedStandalone(job.id, binding)
                val before = support.app.downloads.acquisitionPresentation.value
                val beforeJob = before.states.getValue(job.id)
                val beforeClaims = before.view.acquisition.claims
                val beforeUses = before.view.acquisition.assetUses
                val validations = support.platform.localValidationCalls.get()
                val transfers = support.gateway.transfers.get()
                val firstResults = support.gateway.firstResults.get()

                support.platform.validateLocal = {
                    AssetValidation.Invalid(AppFailure(FailureCode.UNSUPPORTED_CODEC, "authored_task_a_rejected"))
                }
                support.app.onMusicAction(MusicUiAction.ValidateRetainedDownload(job.id, binding.value))
                val notice = withTimeout(20_000L) { support.app.notice.first {
                    it?.failure?.code == FailureCode.UNSUPPORTED_CODEC
                } }
                val after = support.app.downloads.acquisitionPresentation.value
                val afterJob = after.states.getValue(job.id)
                assertEquals(DownloadStatus.FAILED, afterJob.status)
                assertEquals(FailureCode.UNSUPPORTED_CODEC, afterJob.failure?.code)
                assertEquals(beforeJob.assetId, afterJob.assetId)
                assertNotNull(afterJob.nextRetryEpochMs)
                assertTrue(afterJob.nextRetryEpochMs!! >= beforeJob.nextRetryEpochMs!!,
                    "A real negative keeps its bounded local backoff instead of clearing it")
                assertEquals(beforeClaims, after.view.acquisition.claims)
                assertEquals(beforeUses, after.view.acquisition.assetUses)
                assertTrue(support.platform.localValidationCalls.get() > validations)
                assertEquals(transfers, support.gateway.transfers.get())
                assertEquals(firstResults, support.gateway.firstResults.get())
                assertRetainedFile(after, job.id)
            } finally {
                support.close()
            }
        }
    }

    @Test
    fun retainedBatchMemberValidateCarriesExactMemberAndRejectsStaleRevision() = runBlocking {
        withTimeout(180_000L) {
            val directory = TypedDownloadPresentationSupport.workDirectory()
            val item = TypedDownloadPresentationSupport.youtubeItem("task-a-batch")
            val support = TypedDownloadPresentationSupport(directory)
            try {
                support.initialize()
                support.store.upsertCatalog(listOf(item)).getOrThrow()
                val batch = support.admitCatalogueBatch(item).single()
                val completed = support.awaitPresentation { presentation ->
                    presentation.states.values.any { it.status == DownloadStatus.COMPLETE } &&
                        presentation.view.acquisition.assetUses.isNotEmpty() &&
                        presentation.view.acquisition.batches.containsKey(batch.id)
                }
                val (job, binding) = completedJob(completed)
                val member = completed.view.acquisition.batches.getValue(batch.id).members.single()
                assertEquals(DownloadBatchMemberState.AVAILABLE, member.state)
                val fresh = completed.view.acquisition.batches.getValue(batch.id)
                support.failRetainedMember(batch.id, 0, fresh.intentRevision, binding)
                val failed = support.app.downloads.acquisitionPresentation.value
                val failedEntry = entry(failed, job.id)
                val target = assertNotNull(failedEntry.retainedValidation)
                assertEquals(batch.id.value, target.batchId)
                assertEquals(0, target.memberPosition)
                assertEquals(fresh.intentRevision, target.expectedIntentRevision)
                assertTrue(target.isBatchMember)

                val stale = support.setBatchIntent(batch.id, fresh.intentRevision,
                    DownloadBatchIntent.PAUSED, retainCompletedAssets = true)
                assertTrue(stale.intentRevision > fresh.intentRevision)
                val current = entry(support.awaitPresentation {
                    it.view.acquisition.batches.getValue(batch.id).intentRevision == stale.intentRevision
                }, job.id)
                assertEquals(stale.intentRevision, current.retainedValidation?.expectedIntentRevision)
                val before = support.app.downloads.acquisitionPresentation.value
                val beforeClaims = before.view.acquisition.claims
                val beforeUses = before.view.acquisition.assetUses
                val validations = support.platform.localValidationCalls.get()
                val transfers = support.gateway.transfers.get()

                support.app.onMusicAction(MusicUiAction.ValidateRetainedDownload(job.id, binding.value,
                    target.batchId, target.memberPosition, target.expectedIntentRevision))
                withTimeout(20_000L) { support.app.notice.first {
                    it?.failure?.detail == "retained_validation_target_stale"
                } }
                val after = support.app.downloads.acquisitionPresentation.value
                assertEquals(beforeClaims, after.view.acquisition.claims)
                assertEquals(beforeUses, after.view.acquisition.assetUses)
                assertEquals(validations, support.platform.localValidationCalls.get(),
                    "A stale row action must fail before authority/native validation mutation")
                assertEquals(transfers, support.gateway.transfers.get())

                val active = support.setBatchIntent(batch.id, stale.intentRevision,
                    DownloadBatchIntent.ACTIVE, retainCompletedAssets = true)
                assertTrue(active.intentRevision > stale.intentRevision)
                val activeEntry = entry(support.awaitPresentation {
                    it.view.acquisition.batches.getValue(batch.id).intentRevision == active.intentRevision
                }, job.id)
                support.platform.validateLocal = { AssetValidation.Valid(12_345L) }
                support.app.onMusicAction(MusicUiAction.ValidateRetainedDownload(job.id, binding.value,
                    activeEntry.retainedValidation?.batchId, activeEntry.retainedValidation?.memberPosition,
                    activeEntry.retainedValidation?.expectedIntentRevision))
                val recovered = try {
                    support.awaitPresentation { it.states[job.id]?.status == DownloadStatus.COMPLETE }
                } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    val now = support.app.downloads.acquisitionPresentation.value
                    throw AssertionError("Exact member validation did not complete; " +
                        "notice=${support.app.notice.value} job=${now.states[job.id]} " +
                        "batches=${now.view.acquisition.batches}", timeout)
                }
                assertRetainedFile(recovered, job.id)
                assertEquals(transfers, support.gateway.transfers.get())
            } finally {
                support.close()
            }
        }
    }

    @Test
    fun repeatedOriginRowOffersExplicitMemberChoiceWhileTheSiblingRemainsRetained() = runBlocking {
        withTimeout(240_000L) {
            val directory = TypedDownloadPresentationSupport.workDirectory()
            val item = TypedDownloadPresentationSupport.youtubeItem("task-a-repeated")
            val support = TypedDownloadPresentationSupport(directory)
            try {
                support.initialize()
                support.store.upsertCatalog(listOf(item)).getOrThrow()
                val batches = support.admitCatalogueBatch(item, count = 2)
                val completed = support.awaitPresentation { presentation ->
                    presentation.states.values.any { it.status == DownloadStatus.COMPLETE } &&
                        presentation.view.acquisition.assetUses.isNotEmpty() &&
                        batches.all { presentation.view.acquisition.batches.containsKey(it.id) }
                }
                val (job, binding) = completedJob(completed)
                assertEquals(2, completed.view.acquisition.claims.values.count { it.physicalJobId == job.id },
                    "The repeated origin must share the same immutable binding/job")
                val firstCompleted = completed.view.acquisition.batches.getValue(batches[0].id)
                support.failRetainedMember(firstCompleted.id, 0, firstCompleted.intentRevision, binding)
                val ambiguous = entry(support.app.downloads.acquisitionPresentation.value, job.id)
                assertNull(ambiguous.retainedValidation,
                    "Multiple targets must not be silently collapsed to a newest/first member")
                assertEquals(2, ambiguous.retainedValidationChoices.size)
                val failed = support.app.downloads.acquisitionPresentation.value
                val first = failed.view.acquisition.batches.getValue(batches[0].id)
                val second = failed.view.acquisition.batches.getValue(batches[1].id)
                val firstClaim = failed.view.acquisition.claims.values.single {
                    it.owner == dev.veyra.model.DownloadClaimOwner.BatchMember(first.id, 0)
                }
                val secondClaim = failed.view.acquisition.claims.values.single {
                    it.owner == dev.veyra.model.DownloadClaimOwner.BatchMember(second.id, 0)
                }
                assertEquals(dev.veyra.model.DownloadBatchIntent.ACTIVE, firstClaim.intent)
                assertEquals(dev.veyra.model.DownloadBatchIntent.ACTIVE, secondClaim.intent)
                val requested = ambiguous.retainedValidationChoices.first { it.batchId == second.id.value }

                val beforeUses = failed.view.acquisition.assetUses.filter { it.requestedBindingId == binding }
                assertEquals(1, beforeUses.size, "The failed negative does not add or fabricate a use")
                val validations = support.platform.localValidationCalls.get()
                support.platform.validateLocal = { AssetValidation.Valid(12_345L) }
                support.app.onMusicAction(MusicUiAction.ValidateRetainedDownload(requested.jobId, requested.bindingId,
                    requested.batchId, requested.memberPosition, requested.expectedIntentRevision))
                val recovered = support.awaitPresentation {
                    it.states[job.id]?.status == DownloadStatus.COMPLETE &&
                        it.view.acquisition.batches.getValue(second.id).members.single().state == DownloadBatchMemberState.AVAILABLE
                }
                assertEquals(DownloadBatchMemberState.AVAILABLE,
                    recovered.view.acquisition.batches.getValue(second.id).members.single().state)
                assertEquals(1, recovered.view.acquisition.assetUses.count { it.requestedBindingId == binding },
                    "Exact member validation grants only the requested binding use; it does not duplicate it")
                assertEquals(0, recovered.view.acquisition.assetUses.count { it.requestedBindingId != binding },
                    "No sibling/other binding use is inferred")
                assertTrue(support.platform.localValidationCalls.get() > validations)
                assertRetainedFile(recovered, job.id)
                assertEquals(dev.veyra.model.DownloadBatchIntent.ACTIVE,
                    recovered.view.acquisition.claims.getValue(firstClaim.id).intent,
                    "Validating one exact member must not cancel or revive the retained sibling")
                assertEquals(dev.veyra.model.DownloadBatchIntent.ACTIVE,
                    recovered.view.acquisition.claims.getValue(secondClaim.id).intent)
            } finally {
                support.close()
            }
        }
    }

    @Test
    fun needsForegroundResumeUsesTheRealForegroundAnnouncementNotOrdinaryRetry() = runBlocking {
        withTimeout(180_000L) {
            val directory = TypedDownloadPresentationSupport.workDirectory()
            val item = TypedDownloadPresentationSupport.youtubeItem("task-a-foreground")
            val support = TypedDownloadPresentationSupport(directory)
            try {
                support.initialize()
                support.store.upsertCatalog(listOf(item)).getOrThrow()
                support.app.onMusicAction(MusicUiAction.Download(item))
                val completed = support.awaitPresentation { presentation ->
                    presentation.states.values.any { it.status == DownloadStatus.COMPLETE } &&
                        presentation.view.acquisition.assetUses.isNotEmpty()
                }
                val (job, binding) = completedJob(completed)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                support.platform.validateLocal = {
                    entered.complete(Unit)
                    release.await()
                    AssetValidation.Valid(12_345L)
                }
                val pending = async {
                    runCatching { support.app.downloads.validateStandaloneAssetUse(job.id, binding) }
                }
                withTimeout(20_000L) { entered.await() }
                support.app.downloads.pauseForBackground()
                release.complete(Unit)
                pending.cancelAndJoin()
                val held = support.awaitPresentation {
                    it.states[job.id]?.status == DownloadStatus.NEEDS_FOREGROUND
                }
                assertEquals("download_completed_check_needs_foreground", held.states[job.id]?.failure?.detail)
                val ordinary = assertFailsWith<Exception> { support.app.downloads.resume(job.id) }
                assertTrue(ordinary is dev.veyra.model.VeyraException || ordinary.cause is dev.veyra.model.VeyraException)

                val transfers = support.gateway.transfers.get()
                val firstResults = support.gateway.firstResults.get()
                support.app.onMusicAction(MusicUiAction.ResumeDownload(job.id))
                val resumed = support.awaitPresentation { it.states[job.id]?.status == DownloadStatus.COMPLETE }
                assertNull(resumed.states.getValue(job.id).failure)
                assertRetainedFile(resumed, job.id)
                assertEquals(transfers, support.gateway.transfers.get())
                assertEquals(firstResults, support.gateway.firstResults.get())
            } finally {
                support.close()
            }
        }
    }
}
