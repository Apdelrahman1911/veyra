package dev.veyra.acquisition

import dev.veyra.model.*

/**
 * Explicit retry of ONE already accepted, attached Single. The whole displayed claim AND job
 * are compared in the same Room transaction: failure/backoff can change without changing the
 * physical ownership generation. This is not Resume, a new admission, or a fresh first query.
 */
data class RetryAcquisitionDownload(
    val member: AcquisitionDownloadMemberKey,
    val expectedClaim: DownloadClaim,
    val expectedJob: PhysicalDownloadJob,
)

sealed interface AcquisitionDownloadRetryResult {
    /**
     * Positive metadata CAS only. [job] is the exact committed reset row, not the latest physical
     * execution state, a native start, an available file, or an acknowledgement of physical drain.
     * The unchanged batch intent revision and only this claim's incremented generation are in
     * [admission]. Another member sharing the physical job can observe the same reset job.
     */
    data class Retried(val admission: AcquisitionDownloadAdmission, val job: PhysicalDownloadJob) : AcquisitionDownloadRetryResult
    data class Unavailable(val failure: AppFailure) : AcquisitionDownloadRetryResult
}

/**
 * Display/early-rejection predicate only, NEVER admission authority. The executor additionally
 * checks healthy same-owner coverage, local epochs/owners, every durable attempt/finalization
 * and exact current member/claim/job under the existing locks and canonical transaction.
 *
 * A transient automatic retry has a real bounded retry count, failure and deadline. Merely
 * disconnected/offline/queued/NeedsForeground work is not a failed-member Retry candidate.
 * An explicit accepted Retry may remain queued while offline or before cold host activation;
 * it does not activate a scheduler, acquire an allowance, or perform source/native work.
 */
fun isExplicitMemberRetryCandidate(job: PhysicalDownloadJob): Boolean {
    if (job.userPaused || job.cleanupPending || job.removeWhenClean || job.assetId != null ||
        job.activeAttemptId != null || job.leaseOwner != null || job.leaseExpiresEpochMs != null ||
        job.maintenanceKind != null || job.producer.bindingId == null || job.producer.recordingId == null ||
        job.failure?.code == FailureCode.BACKGROUND_RESTRICTED) return false
    return when (job.status) {
        DownloadStatus.FAILED -> true
        DownloadStatus.WAITING_NETWORK -> job.failure?.code in setOf(FailureCode.NETWORK_UNAVAILABLE, FailureCode.RATE_LIMITED) &&
            job.automaticRetryCount in 1..5 && job.nextRetryEpochMs != null
        else -> false
    }
}

/** No paths, producer, selected key, counters, history, attempts or ownership epoch are replaced. */
internal fun PhysicalDownloadJob.afterExplicitMemberRetry(nowEpochMs: Long): PhysicalDownloadJob = copy(
    status = DownloadStatus.QUEUED, failure = null, nextRetryEpochMs = null, automaticRetryCount = 0, updatedEpochMs = nowEpochMs,
)
