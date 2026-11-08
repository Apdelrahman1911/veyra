package dev.veyra.downloads

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.playback.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Real canonical Room/coordinator/files/OS leases. Transfers, decoder results and source facts
 * are authored NON-AUDIO fakes; these cases do not establish native/audio/live-source behavior.
 */
class DownloadRetainedFailureRevalidationRoomTest {
    @Test fun retainedValidationFailureRecoversOnlyThroughExplicitLocalValidation() = acquisitionDownloadRoomCase {
        val owner = owner()
        val accepted = acceptNeutral(owner)
        val binding = assertIs<AcquisitionOutcome.Single>(accepted.outcome).bindingId
        val id = enqueue(owner, accepted)
        owner.coordinator.start(DownloadActivation.USER_SESSION)
        awaitOriginUsePublication(owner, id)
        val origin = owner.store.acquisitionView().acquisition
        val asset = origin.materializations.values.single()
        val originalUse = origin.assetUses.single()
        val originalReceipt = origin.materializationReceipts.getValue(asset.id)

        owner.validation = { AssetValidation.Invalid(AppFailure(FailureCode.CORRUPT_MEDIA, "authored_retained_negative")) }
        assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> {
            owner.coordinator.validateStandaloneAssetUse(id, binding)
        }.failure.code)
        idle(owner)
        val failed = owner.store.acquisitionView().acquisition
        val failedJob = failed.physicalDownloads.getValue(id)
        assertEquals(DownloadStatus.FAILED, failedJob.status)
        assertEquals(FailureCode.CORRUPT_MEDIA, failedJob.failure?.code)
        assertNotNull(failedJob.nextRetryEpochMs, "The actual negative keeps its bounded local backoff")
        assertEquals(asset.id, failedJob.assetId)
        assertEquals(asset, failed.materializations.getValue(asset.id))
        assertTrue(Files.exists(Path.of(asset.uri)))
        clock.advance(60_000)
        val autoChecks = owner.validations.size
        delay(120)
        assertEquals(DownloadStatus.FAILED, owner.store.acquisitionView().acquisition.physicalDownloads.getValue(id).status,
            "Automatic maintenance never retries a FAILED retained job")
        assertEquals(autoChecks, owner.validations.size)
        val validationsBefore = owner.validations.size
        val transfersBefore = owner.gateway.transfers.size

        owner.validation = { AssetValidation.Valid(733) }
        val recovered = owner.coordinator.validateStandaloneAssetUse(id, binding)
        idle(owner)
        assertEquals(binding, recovered.requestedBindingId)
        assertEquals(asset.id, recovered.materializationAssetId)
        assertEquals(originalUse, recovered, "Historic exact use/receipt identity is never rewritten")
        val after = owner.store.acquisitionView().acquisition
        val recoveredJob = after.physicalDownloads.getValue(id)
        assertEquals(DownloadStatus.COMPLETE, recoveredJob.status)
        assertNull(recoveredJob.failure)
        assertNull(recoveredJob.nextRetryEpochMs)
        assertEquals(setOf(originalUse), after.assetUses)
        assertEquals(originalReceipt, after.materializationReceipts.getValue(asset.id))
        assertEquals(failed.physicalAttempts, after.physicalAttempts)
        assertEquals(validationsBefore + 1, owner.validations.size)
        assertEquals(transfersBefore, owner.gateway.transfers.size, "Local validation cannot perform a new transfer")
        assertTrue(owner.selector.requests.isEmpty())
    }

    @Test fun networkRetryCannotConsumeTheRetainedFailedFile() = acquisitionDownloadRoomCase {
        val owner = owner()
        val accepted = acceptNeutral(owner)
        val binding = assertIs<AcquisitionOutcome.Single>(accepted.outcome).bindingId
        val id = enqueue(owner, accepted)
        owner.coordinator.start(DownloadActivation.USER_SESSION)
        awaitOriginUsePublication(owner, id)
        owner.validation = { AssetValidation.Invalid(AppFailure(FailureCode.CORRUPT_MEDIA, "authored_retained_negative")) }
        assertFailsWith<VeyraException> { owner.coordinator.validateStandaloneAssetUse(id, binding) }
        idle(owner)
        val before = owner.store.acquisitionView().acquisition
        val beforeJob = before.physicalDownloads.getValue(id)
        val beforeClaims = before.claims
        val asset = before.materializations.getValue(assertNotNull(beforeJob.assetId))
        assertEquals(DownloadStatus.FAILED, beforeJob.status)

        val error = assertFailsWith<VeyraException> { owner.coordinator.resume(id) }
        assertEquals(FailureCode.RECOVERY_REQUIRED, error.failure.code)
        assertEquals("download_retained_local_validation_required", error.failure.detail)
        val after = owner.store.acquisitionView().acquisition
        assertEquals(beforeJob, after.physicalDownloads.getValue(id))
        assertEquals(beforeClaims, after.claims)
        assertTrue(Files.exists(Path.of(asset.uri)), "A refused network Retry never deletes retained bytes")
    }

    @Test fun foregroundReturnNeverQueuesAnInterruptedRetainedCheck() = acquisitionDownloadRoomCase {
        val owner = owner()
        val accepted = acceptNeutral(owner)
        val binding = assertIs<AcquisitionOutcome.Single>(accepted.outcome).bindingId
        val id = enqueue(owner, accepted)
        owner.coordinator.start(DownloadActivation.USER_SESSION)
        awaitOriginUsePublication(owner, id)
        val asset = owner.store.acquisitionView().acquisition.materializations.values.single()

        val held = barrier()
        owner.validation = { held.stop(); AssetValidation.Valid(733) }
        supervisorScope {
            val pending = async { runCatching { owner.coordinator.validateStandaloneAssetUse(id, binding) } }
            withTimeout(10_000) { held.entered.await() }
            owner.coordinator.pauseForBackground()
            held.release()
            pending.cancelAndJoin()
        }
        idle(owner)
        val paused = owner.store.acquisitionView().acquisition.physicalDownloads.getValue(id)
        assertEquals(DownloadStatus.NEEDS_FOREGROUND, paused.status,
            "A host interruption is presented as foreground work, never a fake COMPLETE")
        assertEquals("download_completed_check_needs_foreground", paused.failure?.detail)
        assertEquals(asset.id, paused.assetId)

        owner.coordinator.resumePending()
        idle(owner)
        val resumed = owner.store.acquisitionView().acquisition.physicalDownloads.getValue(id)
        assertEquals(DownloadStatus.COMPLETE, resumed.status)
        assertNull(resumed.failure)
        assertNull(resumed.nextRetryEpochMs)
        assertEquals(asset.id, resumed.assetId)
        assertNotEquals(DownloadStatus.QUEUED, resumed.status, "Retained local recovery must not become a dead transfer lane")
    }

    @Test fun retainedFailureHostReturnStaysFailedAndCanThenValidate() = acquisitionDownloadRoomCase {
        val owner = owner()
        val (id, binding) = retainedStandaloneFailure(owner)
        val before = owner.store.acquisitionView().acquisition.physicalDownloads.getValue(id)
        val originalFailure = assertNotNull(before.failure)
        val held = barrier()
        owner.validation = { held.stop(); AssetValidation.Valid(733) }
        supervisorScope {
            val pending = async { runCatching { owner.coordinator.validateRetainedAssetUse(id, binding) } }
            withTimeout(10_000) { held.entered.await() }
            owner.coordinator.pauseForBackground()
            held.release()
            pending.cancelAndJoin()
        }
        idle(owner)
        val hostHeld = owner.store.acquisitionView().acquisition.physicalDownloads.getValue(id)
        assertEquals(DownloadStatus.NEEDS_FOREGROUND, hostHeld.status)
        assertEquals(originalFailure, hostHeld.failure)
        assertEquals(before.nextRetryEpochMs, hostHeld.nextRetryEpochMs)
        owner.coordinator.resumePending()
        idle(owner)
        val resumed = owner.store.acquisitionView().acquisition.physicalDownloads.getValue(id)
        assertEquals(DownloadStatus.FAILED, resumed.status)
        assertEquals(originalFailure, resumed.failure)
        assertEquals(before.nextRetryEpochMs, resumed.nextRetryEpochMs)
        assertNotEquals(DownloadStatus.QUEUED, resumed.status)
        owner.validation = { AssetValidation.Valid(733) }
        owner.coordinator.validateRetainedAssetUse(id, binding)
        val recovered = owner.store.acquisitionView().acquisition.physicalDownloads.getValue(id)
        assertEquals(DownloadStatus.COMPLETE, recovered.status)
        assertNull(recovered.failure)
        assertNull(recovered.nextRetryEpochMs)
    }

    @Test fun blockedForegroundKeepsRetainedFailureBackoffAndBytes() = acquisitionDownloadRoomCase {
        val owner = owner()
        val (id, binding) = retainedStandaloneFailure(owner)
        val before = owner.store.acquisitionView().acquisition
        val beforeJob = before.physicalDownloads.getValue(id)
        val beforeClaims = before.claims
        val asset = before.materializations.getValue(assertNotNull(beforeJob.assetId))
        val validations = owner.validations.size
        owner.coordinator.pauseForBackground()
        val error = assertFailsWith<VeyraException> {
            owner.coordinator.validateRetainedAssetUse(id, binding)
        }
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, error.failure.code)
        assertEquals(validations, owner.validations.size, "A blocked host must be decided before native work")
        val blocked = owner.store.acquisitionView().acquisition
        assertEquals(beforeJob, blocked.physicalDownloads.getValue(id))
        assertEquals(beforeClaims, blocked.claims)
        assertTrue(Files.exists(Path.of(asset.uri)))
        owner.coordinator.resumePending()
        val resumed = owner.store.acquisitionView().acquisition.physicalDownloads.getValue(id)
        assertEquals(DownloadStatus.FAILED, resumed.status, "A plain retained failure stays stopped after foreground return")
        assertEquals(FailureCode.CORRUPT_MEDIA, resumed.failure?.code)
        assertNotNull(resumed.nextRetryEpochMs)
    }

    @Test fun offlineLocalValidationNeverCallsTheSourceOrTransfer() = acquisitionDownloadRoomCase {
        val owner = owner()
        val (id, binding) = retainedStandaloneFailure(owner)
        val before = owner.store.acquisitionView().acquisition
        val asset = before.materializations.values.single()
        val transfers = owner.gateway.transfers.size
        offline(owner)
        owner.validation = { AssetValidation.Valid(733) }
        val use = owner.coordinator.validateRetainedAssetUse(id, binding)
        assertEquals(binding, use.requestedBindingId)
        assertEquals(asset.id, use.materializationAssetId)
        val after = owner.store.acquisitionView().acquisition
        assertEquals(DownloadStatus.COMPLETE, after.physicalDownloads.getValue(id).status)
        assertEquals(transfers, owner.gateway.transfers.size)
        assertTrue(owner.selector.requests.isEmpty())
        assertTrue(Files.exists(Path.of(asset.uri)))
    }

    @Test fun cancelledExplicitValidationPreservesFailureBackoffAndBytes() = acquisitionDownloadRoomCase {
        val owner = owner()
        val (id, binding) = retainedStandaloneFailure(owner)
        val before = owner.store.acquisitionView().acquisition
        val beforeJob = before.physicalDownloads.getValue(id)
        val beforeClaims = before.claims
        val asset = before.materializations.getValue(assertNotNull(beforeJob.assetId))
        val held = barrier()
        owner.validation = { held.stop(); AssetValidation.Valid(733) }
        supervisorScope {
            val pending = async { owner.coordinator.validateRetainedAssetUse(id, binding) }
            withTimeout(10_000) { held.entered.await() }
            pending.cancel()
            held.release()
            assertFailsWith<CancellationException> { pending.await() }
        }
        val after = owner.store.acquisitionView().acquisition
        val afterJob = after.physicalDownloads.getValue(id)
        assertEquals(beforeJob.status, afterJob.status)
        assertEquals(beforeJob.failure, afterJob.failure)
        assertEquals(beforeJob.nextRetryEpochMs, afterJob.nextRetryEpochMs)
        assertEquals(beforeJob.assetId, afterJob.assetId)
        assertEquals(beforeClaims, after.claims)
        assertEquals(before.assetUses, after.assetUses)
        assertTrue(Files.exists(Path.of(asset.uri)))
    }

    @Test fun actualNegativeDuringExplicitRetainedValidationStaysFailedUntilRealRecovery() = acquisitionDownloadRoomCase {
        val owner = owner()
        val (id, binding) = retainedStandaloneFailure(owner)
        val before = owner.store.acquisitionView().acquisition
        val beforeJob = before.physicalDownloads.getValue(id)
        owner.validation = { AssetValidation.Invalid(AppFailure(FailureCode.UNSUPPORTED_CODEC, "authored_second_negative")) }
        assertEquals(FailureCode.UNSUPPORTED_CODEC, assertFailsWith<VeyraException> {
            owner.coordinator.validateRetainedAssetUse(id, binding)
        }.failure.code)
        val rejected = owner.store.acquisitionView().acquisition
        val rejectedJob = rejected.physicalDownloads.getValue(id)
        assertEquals(DownloadStatus.FAILED, rejectedJob.status)
        assertEquals(FailureCode.UNSUPPORTED_CODEC, rejectedJob.failure?.code)
        assertNotNull(rejectedJob.nextRetryEpochMs)
        assertEquals(beforeJob.assetId, rejectedJob.assetId)
        assertEquals(before.assetUses, rejected.assetUses)
        owner.validation = { AssetValidation.Valid(733) }
        owner.coordinator.validateRetainedAssetUse(id, binding)
        assertEquals(DownloadStatus.COMPLETE, owner.store.acquisitionView().acquisition.physicalDownloads.getValue(id).status)
    }

    @Test fun exactR2RecoveryGrantsOnlyItsOwnUseAndKeepsTheOriginUse() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = enrollOriginUseBatch(owner, count = 2)
        val initial = owner.store.acquisitionView().acquisition
        val job = initial.physicalDownloads.values.single()
        val second = batch.members.single { it.requiredBindingId != job.producer.bindingId }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val published = awaitOriginUsePublication(owner, job.id).acquisition
        owner.coordinator.start(DownloadActivation.USER_SESSION)
        val originUse = assertOnlyOriginUse(published, job.id)
        val publishedJob = published.physicalDownloads.getValue(job.id)
        val asset = published.materializations.getValue(assertNotNull(publishedJob.assetId))
        assertEquals(originUse, assertOnlyOriginUse(published, job.id))
        owner.validation = { AssetValidation.Invalid(AppFailure(FailureCode.CORRUPT_MEDIA, "authored_r2_negative")) }
        assertIs<AcquisitionDownloadAssetCheck.Unavailable>(owner.adapter.validateMemberAsset(
            batch.id, second.position, batch.intentRevision, asset.id))
        idle(owner)
        val failed = owner.store.acquisitionView().acquisition
        assertEquals(DownloadStatus.FAILED, failed.physicalDownloads.getValue(job.id).status)
        assertTrue(failed.assetUses.none { it.requestedBindingId == second.requiredBindingId })
        val transfers = owner.gateway.transfers.size

        owner.validation = { AssetValidation.Valid(733) }
        val use = owner.coordinator.validateRetainedAssetUse(job.id, assertNotNull(second.requiredBindingId))
        assertEquals(second.requiredBindingId, use.requestedBindingId)
        assertEquals(asset.id, use.materializationAssetId)
        val recovered = owner.store.acquisitionView().acquisition
        assertEquals(DownloadStatus.COMPLETE, recovered.physicalDownloads.getValue(job.id).status)
        assertNull(recovered.physicalDownloads.getValue(job.id).failure)
        assertEquals(setOf(originUse, use), recovered.assetUses)
        assertEquals(originUse, recovered.assetUses.single { it.requestedBindingId == job.producer.bindingId })
        assertEquals(originUse.createdEpochMs, recovered.assetUses.single {
            it.requestedBindingId == job.producer.bindingId
        }.createdEpochMs)
        assertEquals(published.materializationReceipts, recovered.materializationReceipts)
        assertEquals(published.physicalAttempts, recovered.physicalAttempts)
        assertEquals(transfers, owner.gateway.transfers.size)
        assertEquals(DownloadBatchMemberState.AVAILABLE, recovered.batches.getValue(batch.id).members.single {
            it.requiredBindingId == second.requiredBindingId
        }.state)
    }

    @Test fun staleClaimDuringExplicitRetainedValidationCannotCommitAUseOrComplete() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = enrollOriginUseBatch(owner, count = 2)
        val initial = owner.store.acquisitionView().acquisition
        val job = initial.physicalDownloads.values.single()
        val second = batch.members.single { it.requiredBindingId != job.producer.bindingId }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val published = awaitOriginUsePublication(owner, job.id).acquisition
        owner.coordinator.start(DownloadActivation.USER_SESSION)
        val publishedJob = published.physicalDownloads.getValue(job.id)
        val asset = published.materializations.getValue(assertNotNull(publishedJob.assetId))
        owner.validation = { AssetValidation.Invalid(AppFailure(FailureCode.CORRUPT_MEDIA, "authored_r2_negative")) }
        assertIs<AcquisitionDownloadAssetCheck.Unavailable>(owner.adapter.validateMemberAsset(
            batch.id, second.position, batch.intentRevision, asset.id))
        idle(owner)
        val failed = owner.store.acquisitionView().acquisition
        val failedJob = failed.physicalDownloads.getValue(job.id)
        assertEquals(DownloadStatus.FAILED, failedJob.status)
        val held = barrier()
        owner.validation = { held.stop(); AssetValidation.Valid(733) }
        supervisorScope {
            val pending = async { runCatching {
                owner.coordinator.validateRetainedAssetUse(job.id, assertNotNull(second.requiredBindingId))
            } }
            withTimeout(10_000) { held.entered.await() }
            owner.batches.setIntent(batch.id, batch.intentRevision, DownloadBatchIntent.PAUSED, retainCompletedAssets = true)
            held.release()
            val result = pending.await()
            assertTrue(result.isFailure)
            when (val error = result.exceptionOrNull()) {
                is VeyraException -> assertEquals(FailureCode.INVALID_STATE, error.failure.code)
                is CancellationException -> Unit // The exact claim/intent race may cancel this dispatcher.
                else -> fail("Unexpected stale-claim failure: $error")
            }
        }
        idle(owner)
        val after = owner.store.acquisitionView().acquisition
        val afterJob = after.physicalDownloads.getValue(job.id)
        assertEquals(FailureCode.CORRUPT_MEDIA, afterJob.failure?.code)
        assertNotEquals(DownloadStatus.COMPLETE, afterJob.status)
        assertNotEquals(DownloadStatus.QUEUED, afterJob.status)
        assertTrue(after.assetUses.none { it.requestedBindingId == second.requiredBindingId })
        assertTrue(after.assetUses.any { it.requestedBindingId == job.producer.bindingId })
        assertEquals(asset.id, afterJob.assetId)
        assertTrue(Files.exists(Path.of(asset.uri)))
    }

    @Test fun pathCloseFailureCannotPublishRetainedSuccess() = acquisitionDownloadRoomCase {
        val owner = owner()
        val (id, binding) = retainedStandaloneFailure(owner)
        val before = owner.store.acquisitionView().acquisition
        val beforeJob = before.physicalDownloads.getValue(id)
        val asset = before.materializations.getValue(assertNotNull(beforeJob.assetId))
        owner.validation = { AssetValidation.Valid(733) }
        leases.beforeClose = { _, _ -> throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_drain_failure")) }
        val thrown = try {
            owner.coordinator.validateRetainedAssetUse(id, binding)
            null
        } catch (error: Throwable) { error }
        assertTrue(thrown is VeyraException || thrown is CancellationException,
            "A failed close must never return a successful validation")
        if (thrown is VeyraException) assertEquals(FailureCode.BACKGROUND_RESTRICTED, thrown.failure.code)
        val after = owner.store.acquisitionView().acquisition
        val afterJob = after.physicalDownloads.getValue(id)
        assertEquals(DownloadStatus.FAILED, afterJob.status)
        assertEquals(beforeJob.failure, afterJob.failure)
        assertEquals(beforeJob.nextRetryEpochMs, afterJob.nextRetryEpochMs)
        assertEquals(before.assetUses, after.assetUses)
        assertEquals(asset.id, afterJob.assetId)
        assertTrue(Files.exists(Path.of(asset.uri)))
        leases.beforeClose = { _, _ -> }
    }

    private suspend fun DownloadAcquisitionRoomFixture.retainedStandaloneFailure(
        owner: DownloadAcquisitionRoomOwner,
    ): Pair<String, ExecutionBindingId> {
        val accepted = acceptNeutral(owner)
        val binding = assertIs<AcquisitionOutcome.Single>(accepted.outcome).bindingId
        val id = enqueue(owner, accepted)
        owner.coordinator.start(DownloadActivation.USER_SESSION)
        awaitOriginUsePublication(owner, id)
        owner.validation = { AssetValidation.Invalid(AppFailure(FailureCode.CORRUPT_MEDIA, "authored_retained_negative")) }
        assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> {
            owner.coordinator.validateStandaloneAssetUse(id, binding)
        }.failure.code)
        idle(owner)
        assertEquals(DownloadStatus.FAILED, owner.store.acquisitionView().acquisition.physicalDownloads.getValue(id).status)
        return id to binding
    }
}
