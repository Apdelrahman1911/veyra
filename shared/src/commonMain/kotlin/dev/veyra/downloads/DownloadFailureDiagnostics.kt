package dev.veyra.downloads

import dev.veyra.model.AppFailure
import dev.veyra.model.DownloadClaim
import dev.veyra.model.DownloadStatus
import dev.veyra.model.FailureCode
import dev.veyra.model.PhysicalDownloadAttempt
import dev.veyra.model.PhysicalDownloadFence
import dev.veyra.model.PhysicalDownloadFinalization
import dev.veyra.model.PhysicalDownloadJob
import dev.veyra.model.PhysicalDownloadMaintenanceKind
import dev.veyra.model.PhysicalDownloadProducerOrigin
import dev.veyra.model.VeyraException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

enum class DownloadDiagnosticStage {
    HOST_FAILURE, ENQUEUE, MEMBER_ADMISSION, EXECUTION_CLAIM, INITIAL_CHECKPOINT, EXECUTION_ALLOWANCE,
    SOURCE_PREPARATION, REPRESENTATION_COMMIT, STAGING, SOURCE_TRANSFER, PROGRESS_CHECKPOINT,
    LEASE_HEARTBEAT, TRANSFER_INTEGRITY, VALIDATION_COMMIT, FILE_INTEGRITY, NATIVE_VALIDATION,
    FINALIZATION_PREPARE, FILE_PUBLICATION, FINALIZATION_PUBLISH, OWNERSHIP_COMPARE_AND_SET,
    ATTEMPT_SETTLEMENT, MAINTENANCE_OPERATION,
}

enum class DownloadDiagnosticOutcome { FAILURE, STALE, SETTLED, STARTED, CANCELLED }
enum class DownloadDiagnosticClaimDemand {
    CONTINUE, PAUSE_AND_RETAIN, CANCEL_UNFINISHED_KEEP_COMPLETE, REMOVE_CANDIDATE, UNOWNED_UNKNOWN, INVALID_JOIN,
}
enum class DownloadDiagnosticErrorKind { REPORTED, TYPED, ARGUMENT_GUARD, STATE_GUARD, OTHER }
enum class DownloadDiagnosticReason {
    SOURCE_EXPIRED, REPRESENTATION_CHANGED, SOURCE_ACCESS, SOURCE_RUNTIME, PREPARED_CAPABILITY,
    TRANSFER_INTEGRITY, GATEWAY_IDENTITY, CLAIM_STALE, EXECUTOR_GUARD, HOST_RESTRICTED, UNCLASSIFIED,
    ACTION_STALE, CATALOG_IDENTITY, CATALOG_CAPTURE, ACQUISITION_IDENTITY, SELECTION_REJECTED, OWNED_FILE_INTEGRITY,
}

data class DownloadDiagnosticFailure private constructor(
    val code: FailureCode,
    val reason: DownloadDiagnosticReason,
    val kind: DownloadDiagnosticErrorKind,
    val detailCode: String?,
) {
    companion object {
        fun from(failure: AppFailure, error: Throwable? = null): DownloadDiagnosticFailure {
            val reason = when (failure.detail) {
                "YOUTUBE_SOURCE_EXPIRED" -> DownloadDiagnosticReason.SOURCE_EXPIRED
                "YOUTUBE_SELECTED_TRACK_CHANGED" -> DownloadDiagnosticReason.REPRESENTATION_CHANGED
                "YOUTUBE_ACCESS_REQUIRED" -> DownloadDiagnosticReason.SOURCE_ACCESS
                "YOUTUBE_RUNTIME_UNAVAILABLE" -> DownloadDiagnosticReason.SOURCE_RUNTIME
                "YOUTUBE_PREPARED_DOWNLOAD_CONSUMED", "YOUTUBE_PREPARED_DOWNLOAD_MISMATCH" ->
                    DownloadDiagnosticReason.PREPARED_CAPABILITY
                "YOUTUBE_INVALID_TRANSFER_COUNTS", "YOUTUBE_TRANSFER_IDENTITY_CHANGED", "YOUTUBE_TRANSFER_REJECTED",
                "invalid_transfer_count", "transfer_identity_changed", "transfer_size_changed", "download_producer_changed" ->
                    DownloadDiagnosticReason.TRANSFER_INTEGRITY
                "download_gateway_identity" -> DownloadDiagnosticReason.GATEWAY_IDENTITY
                "download_batch_action_stale", "download_standalone_action_stale", "download_member_retry_stale",
                "download_checkpoint_stale" -> DownloadDiagnosticReason.CLAIM_STALE
                "download_executor_invalid_state" -> DownloadDiagnosticReason.EXECUTOR_GUARD
                "download_host_not_active", "download_owner_drain_failed_restart_required",
                "download_owner_drain_unconfirmed" -> DownloadDiagnosticReason.HOST_RESTRICTED
                "download_action_stale" -> DownloadDiagnosticReason.ACTION_STALE
                "download_catalog_identity_changed", "download_binding_identity" -> DownloadDiagnosticReason.CATALOG_IDENTITY
                "download_catalog_capture_required" -> DownloadDiagnosticReason.CATALOG_CAPTURE
                "YOUTUBE_ACQUISITION_EXECUTION_MISMATCH", "YOUTUBE_ACQUISITION_INSPECTION_MISMATCH" ->
                    DownloadDiagnosticReason.ACQUISITION_IDENTITY
                "YOUTUBE_INVALID_SELECTION" -> DownloadDiagnosticReason.SELECTION_REJECTED
                "download_validation_timed_out", "local_validation_reader_unacknowledged",
                "ios_download_export_timeout", "ios_download_export_failed", "ios_download_export_cancelled",
                "invalid_interrupted_staging_output", "owned_file_integrity" -> DownloadDiagnosticReason.OWNED_FILE_INTEGRITY
                else -> DownloadDiagnosticReason.UNCLASSIFIED
            }
            val kind = when (error) {
                null -> DownloadDiagnosticErrorKind.REPORTED
                is VeyraException -> DownloadDiagnosticErrorKind.TYPED
                is IllegalArgumentException -> DownloadDiagnosticErrorKind.ARGUMENT_GUARD
                is IllegalStateException -> DownloadDiagnosticErrorKind.STATE_GUARD
                else -> DownloadDiagnosticErrorKind.OTHER
            }
            return DownloadDiagnosticFailure(failure.code, reason, kind,
                failure.detail?.takeIf { reason != DownloadDiagnosticReason.UNCLASSIFIED })
        }
    }
}

data class DownloadDiagnosticPhysicalState(
    val status: DownloadStatus,
    val origin: PhysicalDownloadProducerOrigin,
    val ownershipGeneration: Long,
    val attemptNumber: Int?,
    val validatorGeneration: Long,
    val maintenanceKind: PhysicalDownloadMaintenanceKind?,
    val producerMatches: Boolean,
    val generationMatches: Boolean,
    val ownerMatches: Boolean,
    val attemptMatches: Boolean,
    val leasePresent: Boolean,
    val leaseWithinTimeBounds: Boolean,
    val representationPresent: Boolean,
    val representationMatches: Boolean,
    val cleanupPending: Boolean,
    val finalizationPending: Boolean,
    val assetPresent: Boolean,
    val claimDemand: DownloadDiagnosticClaimDemand?,
)

data class DownloadDiagnosticHostState(
    val started: Boolean,
    val closed: Boolean,
    val suspended: Boolean,
    val generation: Long,
    val generationMatches: Boolean?,
    val drainsInFlight: Int,
    val offlineOnly: Boolean,
    val network: DownloadNetworkState,
)

data class DownloadDiagnosticEvent internal constructor(
    val sequence: Long,
    val stage: DownloadDiagnosticStage,
    val outcome: DownloadDiagnosticOutcome,
    val failure: DownloadDiagnosticFailure?,
    val expectedOwnershipGeneration: Long?,
    val expectedAttemptNumber: Int?,
    val runSequence: Long?,
    val lastCommitted: DownloadDiagnosticPhysicalState?,
    val cachedObservation: DownloadDiagnosticPhysicalState?,
    val host: DownloadDiagnosticHostState?,
    val epochMs: Long? = null,
    val durationMs: Long? = null,
)

internal class DownloadDiagnosticRecorder {
    private val mutableEntries = MutableStateFlow<List<DownloadDiagnosticEvent>>(emptyList())
    private val runSequence = MutableStateFlow(0L)
    val entries = mutableEntries.asStateFlow()

    fun nextRunSequence(): Long = runSequence.updateAndGet { it + 1 }

    fun record(
        stage: DownloadDiagnosticStage,
        failure: AppFailure? = null,
        error: Throwable? = null,
        outcome: DownloadDiagnosticOutcome = DownloadDiagnosticOutcome.FAILURE,
        expectedOwnershipGeneration: Long? = null,
        expectedAttemptNumber: Int? = null,
        runSequence: Long? = null,
        lastCommitted: DownloadDiagnosticPhysicalState? = null,
        cachedObservation: DownloadDiagnosticPhysicalState? = null,
        host: DownloadDiagnosticHostState? = null,
        epochMs: Long? = null,
        durationMs: Long? = null,
    ) {
        val sanitized = failure?.let { DownloadDiagnosticFailure.from(it, error) }
        mutableEntries.update { entries ->
            val event = DownloadDiagnosticEvent((entries.lastOrNull()?.sequence ?: 0) + 1, stage, outcome, sanitized,
                expectedOwnershipGeneration, expectedAttemptNumber, runSequence, lastCommitted, cachedObservation, host, epochMs, durationMs)
            entries.takeLast(31) + event
        }
    }
}

internal fun DownloadPhysicalHostState.diagnosticState(expectedGeneration: Long? = null) = DownloadDiagnosticHostState(
    started, closed, suspended, generation, expectedGeneration?.let { it == generation }, drainsInFlight, offlineOnly, network)

internal fun diagnosticDownloadClaimDemand(physicalJobId: String, claims: Collection<DownloadClaim>): DownloadDiagnosticClaimDemand = try {
    when (summarizeDownloadClaims(physicalJobId, claims).demand) {
        DownloadClaimDemand.CONTINUE -> DownloadDiagnosticClaimDemand.CONTINUE
        DownloadClaimDemand.PAUSE_AND_RETAIN -> DownloadDiagnosticClaimDemand.PAUSE_AND_RETAIN
        DownloadClaimDemand.CANCEL_UNFINISHED_KEEP_COMPLETE -> DownloadDiagnosticClaimDemand.CANCEL_UNFINISHED_KEEP_COMPLETE
        DownloadClaimDemand.REMOVE_CANDIDATE -> DownloadDiagnosticClaimDemand.REMOVE_CANDIDATE
        DownloadClaimDemand.UNOWNED_UNKNOWN -> DownloadDiagnosticClaimDemand.UNOWNED_UNKNOWN
    }
} catch (_: VeyraException) { DownloadDiagnosticClaimDemand.INVALID_JOIN }

internal fun physicalDownloadDiagnosticState(
    job: PhysicalDownloadJob,
    attempt: PhysicalDownloadAttempt?,
    finalization: PhysicalDownloadFinalization?,
    fence: PhysicalDownloadFence,
    nowEpochMs: Long,
    expectedRepresentationId: String?,
    claimDemand: DownloadDiagnosticClaimDemand? = null,
): DownloadDiagnosticPhysicalState {
    val attemptId = if (job.maintenanceKind == PhysicalDownloadMaintenanceKind.RECOVER_FINALIZATION)
        finalization?.attemptId else job.activeAttemptId
    return DownloadDiagnosticPhysicalState(job.status, job.producer.origin, job.ownershipGeneration, attempt?.number,
        job.validatorGeneration, job.maintenanceKind, job.producer == fence.producer,
        job.ownershipGeneration == fence.ownershipGeneration, job.leaseOwner == fence.leaseOwner,
        attemptId == fence.attemptId && (attemptId == null || attempt?.let {
            it.id == fence.attemptId && it.number == fence.attemptNumber && it.downloadId == job.id &&
                it.producer == job.producer && (job.maintenanceKind != null || it.ownershipGeneration == fence.ownershipGeneration)
        } == true), job.leaseOwner != null,
        nowEpochMs >= job.updatedEpochMs && (job.leaseExpiresEpochMs ?: 0) > nowEpochMs,
        !job.representationId.isNullOrBlank(), job.representationId == expectedRepresentationId,
        job.cleanupPending, finalization != null, job.assetId != null, claimDemand)
}
