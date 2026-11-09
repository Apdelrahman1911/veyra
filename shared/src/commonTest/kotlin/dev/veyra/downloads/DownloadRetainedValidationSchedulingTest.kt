package dev.veyra.downloads

import dev.veyra.data.AcquisitionRepositoryState
import dev.veyra.model.*
import kotlin.test.*

/** Pure rule projection only; not Room, native, transfer or actual wake-delivery evidence. */
class DownloadRetainedValidationSchedulingTest {
    @Test fun automaticMaintenanceNeverSchedulesARetainedFailedJob() {
        val failed = state(retainedFailure())
        val record = failed.physicalSchedulingRecords().getValue(JOB)
        assertFalse(DownloadPhysicalClaimRules.maintenanceIntent(record, PhysicalDownloadMaintenanceKind.VALIDATE, checked = false))
        val plan = plan(failed)
        assertFalse(plan.hasDueWork)
        assertNull(plan.nextLocalEpochMs)
        assertNull(plan.nextNetworkEpochMs)
    }

    @Test fun explicitLocalValidateMayBypassOnlyTheRetainedLocalFailureRule() {
        val failed = state(retainedFailure())
        val record = failed.physicalSchedulingRecords().getValue(JOB)
        assertTrue(DownloadPhysicalClaimRules.maintenanceIntent(record, PhysicalDownloadMaintenanceKind.VALIDATE,
            checked = false, explicitRetainedValidation = true))
        assertFalse(DownloadPhysicalClaimRules.maintenanceIntent(record, PhysicalDownloadMaintenanceKind.VALIDATE,
            checked = true, explicitRetainedValidation = true))
        val cleanup = state(retainedFailure().copy(cleanupPending = true))
        assertFalse(DownloadPhysicalClaimRules.maintenanceIntent(cleanup.physicalSchedulingRecords().getValue(JOB),
            PhysicalDownloadMaintenanceKind.VALIDATE, checked = false, explicitRetainedValidation = true))
        val noAsset = state(job().copy(status = DownloadStatus.FAILED, failure = AppFailure(FailureCode.CORRUPT_MEDIA)))
        assertFalse(DownloadPhysicalClaimRules.maintenanceIntent(noAsset.physicalSchedulingRecords().getValue(JOB),
            PhysicalDownloadMaintenanceKind.VALIDATE, checked = false, explicitRetainedValidation = true))
    }

    @Test fun explicitRuleStillRequiresKnownProducerAndDoesNotAuthorizeAFinishedCheck() {
        val failed = retainedFailure()
        val unknown = state(failed.copy(producer = PhysicalDownloadProducer.Schema2("legacy", null, null)))
        assertFalse(DownloadPhysicalClaimRules.maintenanceIntent(unknown.physicalSchedulingRecords().getValue(JOB),
            PhysicalDownloadMaintenanceKind.VALIDATE, checked = false, explicitRetainedValidation = true))
        val checked = state(failed)
        assertTrue(DownloadPhysicalClaimRules.maintenanceIntent(checked.physicalSchedulingRecords().getValue(JOB),
            PhysicalDownloadMaintenanceKind.VALIDATE, checked = false, explicitRetainedValidation = true))
    }

    private fun retainedFailure(): PhysicalDownloadJob {
        val asset = MaterializationAsset(ASSET, PRODUCER, "/owned/retained.mp3", 7,
            sha256 = "a".repeat(64), representationId = "representation")
        return job().copy(status = DownloadStatus.FAILED, assetId = asset.id,
            failure = AppFailure(FailureCode.CORRUPT_MEDIA, "authored_retained_negative"), nextRetryEpochMs = 15_000)
    }

    private fun state(job: PhysicalDownloadJob) = AcquisitionRepositoryState(0,
        physicalDownloads = mapOf(job.id to job), claims = mapOf(CLAIM to claim()),
        materializations = job.assetId?.let { mapOf(it to MaterializationAsset(it, PRODUCER, "/owned/retained.mp3", 7,
            sha256 = "a".repeat(64), representationId = "representation")) }.orEmpty())

    private fun job() = PhysicalDownloadJob(JOB, PRODUCER)
    private fun claim() = DownloadClaim(CLAIM, DownloadClaimOwner.Standalone(JOB), JOB, PRODUCER.bindingId,
        DownloadBatchIntent.ACTIVE, true, 1)
    private fun plan(state: AcquisitionRepositoryState) = projectPhysicalDownloadWakePlan(
        DownloadPhysicalSchedulingInputs(state.physicalSchedulingRecords(), emptySet(), emptyMap()),
        DownloadPhysicalHostState(started = true, closed = false, suspended = false, checksActivated = true,
            generation = 0, drainsInFlight = 0, failure = null, offlineOnly = false, parallelism = 2,
            network = DownloadNetworkState.CONNECTED), 10_000, DownloadSchedulingKey("existing-coordinator", 1))

    private companion object {
        const val JOB = "physical-job"
        val CLAIM = DownloadClaimId("owner-claim")
        val ASSET = MaterializationAssetId("retained-asset")
        val PRODUCER = PhysicalDownloadProducer.Typed(ExecutableSubjectId("neutral-subject"),
            ExecutionBindingId("producer-r1"), SourceRecordingId("recording-c"))
    }
}
