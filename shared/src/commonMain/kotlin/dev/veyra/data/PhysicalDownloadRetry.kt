package dev.veyra.data

import dev.veyra.model.DownloadClaim
import dev.veyra.model.PhysicalDownloadJob

/**
 * Same-Room exact member/claim/job CAS. No batch intent revision change, source operation,
 * physical admission or expired-lease recovery is performed by this metadata-only command.
 */
data class RetryBatchPhysicalDownload(
    val member: BatchDownloadMemberExpectation,
    val expectedClaim: DownloadClaim,
    val expectedJob: PhysicalDownloadJob,
    val nowEpochMs: Long,
)
