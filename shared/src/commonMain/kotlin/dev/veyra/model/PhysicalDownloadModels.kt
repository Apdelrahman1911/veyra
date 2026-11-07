package dev.veyra.model

/** Persisted discriminator on the EXISTING downloads/attempts/finalizations/offline_assets tables. */
enum class PhysicalDownloadProducerOrigin { TYPED, SCHEMA2 }
enum class PhysicalDownloadMaintenanceKind { VALIDATE, RECOVER_FINALIZATION, CLEANUP }

/**
 * Immutable materializer R1, not the currently requesting claim R2. Schema2 is deliberately a
 * separate variant: absent recorded binding/C is unknown, never filled from today's catalogue.
 * Both variants use the SAME physical job/lease/attempt tables and existing transfer executor.
 */
sealed interface PhysicalDownloadProducer {
    val subjectId: ExecutableSubjectId
    val bindingId: ExecutionBindingId?
    val recordingId: SourceRecordingId?
    val origin: PhysicalDownloadProducerOrigin

    data class Typed(
        override val subjectId: ExecutableSubjectId,
        override val bindingId: ExecutionBindingId,
        override val recordingId: SourceRecordingId,
    ) : PhysicalDownloadProducer {
        override val origin: PhysicalDownloadProducerOrigin get() = PhysicalDownloadProducerOrigin.TYPED
    }

    data class Schema2(
        val mediaId: String,
        override val bindingId: ExecutionBindingId?,
        override val recordingId: SourceRecordingId?,
    ) : PhysicalDownloadProducer {
        override val subjectId: ExecutableSubjectId get() = ExecutableSubjectId(mediaId)
        override val origin: PhysicalDownloadProducerOrigin get() = PhysicalDownloadProducerOrigin.SCHEMA2
        init { require(recordingId == null || bindingId != null) }
    }
}

/**
 * Canonical schema3 projection of an existing physical downloads row. mediaId is no longer a
 * mandatory playback identity. All schema2 counters/validators/recovery fields are preserved.
 * ownershipGeneration increases on EVERY transfer/maintenance/cleanup lease admission; zero is
 * retained only for pre-schema3 history. It never means an old physical writer has drained.
 */
data class PhysicalDownloadJob(
    val id: String,
    val producer: PhysicalDownloadProducer,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null,
    val assetId: MaterializationAssetId? = null,
    val failure: AppFailure? = null,
    val stagingPath: String? = null,
    val representationId: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val validatorGeneration: Long = 0,
    val updatedEpochMs: Long = 0,
    val attemptCount: Int = 0,
    val activeAttemptId: String? = null,
    val nextRetryEpochMs: Long? = null,
    val priority: Int = 0,
    val createdEpochMs: Long = 0,
    val userPaused: Boolean = false,
    val leaseOwner: String? = null,
    val leaseExpiresEpochMs: Long? = null,
    val lastProgressEpochMs: Long? = null,
    val automaticRetryCount: Int = 0,
    val cleanupPending: Boolean = false,
    val removeWhenClean: Boolean = false,
    val ownershipGeneration: Long = 0,
    /** Persisted reservation kind, not an in-process flag or a lease-expiry deletion waiver. */
    val maintenanceKind: PhysicalDownloadMaintenanceKind? = null,
) {
    init {
        require(id.isNotBlank() && bytesDownloaded >= 0 && (totalBytes == null || totalBytes >= 0))
        require(validatorGeneration >= 0 && ownershipGeneration >= 0 && attemptCount >= 0 && automaticRetryCount >= 0)
    }
    override fun toString() = "PhysicalDownloadJob(status=$status,identity=redacted,paths=redacted)"
}

data class PhysicalDownloadAttempt(
    val id: String,
    val downloadId: String,
    val producer: PhysicalDownloadProducer,
    val number: Int,
    val ownershipGeneration: Long,
    val status: DownloadAttemptStatus = DownloadAttemptStatus.RUNNING,
    val startedEpochMs: Long = 0,
    val finishedEpochMs: Long? = null,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null,
    val failure: AppFailure? = null,
    val stagingPath: String? = null,
    val representationId: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val validatorGeneration: Long = 0,
    val backgroundTaskId: String? = null,
) {
    init { require(number >= 1 && ownershipGeneration >= 0 && validatorGeneration >= 0) }
    override fun toString() = "PhysicalDownloadAttempt(status=$status,identity=redacted,paths=redacted)"
}

/**
 * Durable intent BEFORE rename. R1, attempt/lease generation and proposed immutable asset ID
 * survive process death; no signed locator is stored. Null assetId occurs only in preserved
 * schema2 intents, whose existing deterministic allocation is retained on recovery.
 */
data class PhysicalDownloadFinalization(
    val downloadId: String,
    val producer: PhysicalDownloadProducer,
    val stagingPath: String,
    val finalPath: String,
    val sha256: String,
    val sizeBytes: Long,
    val attemptId: String? = null,
    val mediaDurationMs: Long? = null,
    val mimeType: String? = null,
    val codecs: String? = null,
    val representationId: String? = null,
    val validatorGeneration: Long = 0,
    val ownershipGeneration: Long = 0,
    val assetId: MaterializationAssetId? = null,
) {
    init { require(sizeBytes >= 0 && validatorGeneration >= 0 && ownershipGeneration >= 0) }
    override fun toString() = "PhysicalDownloadFinalization(identity=redacted,paths=redacted)"
}

/**
 * Live file projection in the existing offline_assets table, not a second cache. Its
 * producer never changes when another compatible claim requests this file. Historical rows
 * have no invented attempt/ownership generation. COMPLETE and this row alone are not Ready:
 * exact current-process file/hash/native checks and a compatible AssetUse are still required.
 */
data class MaterializationAsset(
    val id: MaterializationAssetId,
    val producer: PhysicalDownloadProducer,
    val uri: String,
    val sizeBytes: Long,
    val kind: OfflineAssetKind = OfflineAssetKind.FILE,
    val mimeType: String? = null,
    val codecs: String? = null,
    val sha256: String? = null,
    val representationId: String? = null,
    val validatorGeneration: Long = 0,
    val completedEpochMs: Long = 0,
    val measuredDurationMs: Long? = null,
    val materializerDownloadId: String? = null,
    val materializerAttemptId: String? = null,
    val ownershipGeneration: Long = 0,
) {
    init { require(sizeBytes >= 0 && validatorGeneration >= 0 && ownershipGeneration >= 0) }
    override fun toString() = "MaterializationAsset(kind=$kind,identity=redacted,uri=redacted)"
}

/**
 * Normalized immutable receipt retained AFTER live bytes/offline_assets are removed. AssetUse
 * references this ID, never a deletable URI row. These historical materializer IDs record only
 * what was actually known; they are not authority over a current same-subject job/attempt.
 */
data class MaterializationReceipt(
    val id: MaterializationAssetId,
    val producer: PhysicalDownloadProducer,
    val sizeBytes: Long,
    val kind: OfflineAssetKind,
    val mimeType: String?,
    val codecs: String?,
    val sha256: String?,
    val representationId: String?,
    val validatorGeneration: Long,
    val completedEpochMs: Long,
    val measuredDurationMs: Long?,
    val materializerDownloadId: String?,
    val materializerAttemptId: String?,
    val ownershipGeneration: Long,
)

fun MaterializationAsset.receipt(): MaterializationReceipt = MaterializationReceipt(
    id, producer, sizeBytes, kind, mimeType, codecs, sha256, representationId, validatorGeneration,
    completedEpochMs, measuredDurationMs, materializerDownloadId, materializerAttemptId, ownershipGeneration,
)

/** Durable CAS identity, distinct from the executor's process-local dependency/drain epoch. */
data class PhysicalDownloadFence(
    val downloadId: String,
    val producer: PhysicalDownloadProducer,
    val ownershipGeneration: Long,
    val leaseOwner: String,
    val attemptId: String?,
    val attemptNumber: Int?,
) {
    init {
        require(downloadId.isNotBlank() && ownershipGeneration > 0 && leaseOwner.isNotBlank())
        require((attemptId == null) == (attemptNumber == null))
        require(attemptNumber == null || attemptNumber >= 1)
    }
    override fun toString() = "PhysicalDownloadFence(durable=true,identity=redacted)"
}

/**
 * Exact cleanup path projection reserved by the Room owner. While CLEANUP/cleanupPending is
 * persisted, admission/resume/new sharing and mutations of these paths are fenced even after
 * lease expiry. A positive reservation is NOT physical drain/reader ACK; existing executor
 * file/resource ownership must independently admit destructive work before any path is used.
 */
data class PhysicalDownloadCleanupReservation(
    val fence: PhysicalDownloadFence,
    val stagingPaths: Set<String>,
    val finalization: PhysicalDownloadFinalization?,
    val asset: MaterializationAsset?,
    val removeCompletedAsset: Boolean,
    val removeJobWhenClean: Boolean,
) {
    override fun toString() = "PhysicalDownloadCleanupReservation(identity=redacted,paths=redacted)"
}
