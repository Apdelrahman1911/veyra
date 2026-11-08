package dev.veyra.data.room

import dev.veyra.model.*

internal fun physicalProducer(
    origin: PhysicalDownloadProducerOrigin,
    subjectId: String,
    mediaId: String?,
    bindingId: String?,
    recordingId: String?,
): PhysicalDownloadProducer = when (origin) {
    PhysicalDownloadProducerOrigin.TYPED -> {
        require(mediaId == null || mediaId == subjectId) { "Invalid typed physical catalogue projection" }
        PhysicalDownloadProducer.Typed(ExecutableSubjectId(subjectId), ExecutionBindingId(requireNotNull(bindingId)),
            SourceRecordingId(requireNotNull(recordingId)))
    }
    PhysicalDownloadProducerOrigin.SCHEMA2 -> {
        require(mediaId != null && subjectId == mediaId) { "Invalid legacy physical subject" }
        PhysicalDownloadProducer.Schema2(mediaId, bindingId?.let(::ExecutionBindingId), recordingId?.let(::SourceRecordingId))
    }
}

private fun PhysicalDownloadProducer.mediaId(subjects: Map<ExecutableSubjectId, ExecutableSubject>): String? {
    val subject = subjects.getValue(subjectId)
    return when (this) {
        is PhysicalDownloadProducer.Schema2 -> {
            require(subject == ExecutableSubject.CatalogMedia(subjectId, mediaId))
            mediaId
        }
        is PhysicalDownloadProducer.Typed -> (subject as? ExecutableSubject.CatalogMedia)?.mediaId
    }
}

internal fun DownloadEntity.toPhysicalJob() = PhysicalDownloadJob(
    id, physicalProducer(producerOrigin, subjectId, mediaId, executionBindingId, recordingId), status,
    bytesDownloaded, totalBytes, assetId?.let(::MaterializationAssetId), failure, stagingPath, representationId,
    etag, lastModified, validatorGeneration, updatedEpochMs, attemptCount, activeAttemptId, nextRetryEpochMs,
    priority, createdEpochMs, userPaused, leaseOwner, leaseExpiresEpochMs, lastProgressEpochMs, automaticRetryCount,
    cleanupPending, removeWhenClean, ownershipGeneration, maintenanceKind,
)

internal fun PhysicalDownloadJob.toEntity(subjects: Map<ExecutableSubjectId, ExecutableSubject>) = DownloadEntity(
    id, producer.mediaId(subjects), status, bytesDownloaded, totalBytes, assetId?.value, failure,
    stagingPath, representationId, etag, lastModified, validatorGeneration, updatedEpochMs, producer.bindingId?.value,
    attemptCount, activeAttemptId, nextRetryEpochMs, priority, createdEpochMs, userPaused, leaseOwner,
    leaseExpiresEpochMs, lastProgressEpochMs, automaticRetryCount, cleanupPending, removeWhenClean,
    producer.subjectId.value, producer.origin, producer.recordingId?.value, ownershipGeneration, maintenanceKind,
)

internal fun DownloadAttemptEntity.toPhysicalAttempt() = PhysicalDownloadAttempt(
    id, downloadId, physicalProducer(producerOrigin, subjectId, mediaId, executionBindingId, recordingId), number,
    ownershipGeneration, status, startedEpochMs, finishedEpochMs, bytesDownloaded, totalBytes, failure, stagingPath,
    representationId, etag, lastModified, validatorGeneration, backgroundTaskId,
)

internal fun PhysicalDownloadAttempt.toEntity(subjects: Map<ExecutableSubjectId, ExecutableSubject>) = DownloadAttemptEntity(
    id, downloadId, number, status, startedEpochMs, finishedEpochMs, bytesDownloaded, totalBytes, failure,
    stagingPath, representationId, producer.bindingId?.value, etag, lastModified, validatorGeneration,
    backgroundTaskId, producer.mediaId(subjects), producer.subjectId.value, producer.origin,
    producer.recordingId?.value, ownershipGeneration,
)

internal fun DownloadFinalizationEntity.toPhysicalFinalization() = PhysicalDownloadFinalization(
    downloadId, physicalProducer(producerOrigin, subjectId, mediaId, executionBindingId, recordingId), stagingPath,
    finalPath, sha256, sizeBytes, attemptId, mediaDurationMs, mimeType, codecs, representationId,
    validatorGeneration, ownershipGeneration, assetId?.let(::MaterializationAssetId),
)

internal fun PhysicalDownloadFinalization.toEntity(subjects: Map<ExecutableSubjectId, ExecutableSubject>) = DownloadFinalizationEntity(
    downloadId, producer.mediaId(subjects), stagingPath, finalPath, sha256, sizeBytes, attemptId, mediaDurationMs,
    mimeType, codecs, producer.bindingId?.value, representationId, validatorGeneration, producer.subjectId.value,
    producer.origin, producer.recordingId?.value, ownershipGeneration, assetId?.value,
)

internal fun OfflineAssetEntity.toMaterialization() = MaterializationAsset(
    MaterializationAssetId(id), physicalProducer(producerOrigin, subjectId, mediaId, executionBindingId, recordingId),
    uri, sizeBytes, kind, mimeType, codecs, sha256, representationId, validatorGeneration, completedEpochMs,
    measuredDurationMs, materializerDownloadId, materializerAttemptId, ownershipGeneration,
)

internal fun MaterializationAsset.toEntity(subjects: Map<ExecutableSubjectId, ExecutableSubject>) = OfflineAssetEntity(
    id.value, producer.mediaId(subjects), uri, sizeBytes, kind, mimeType, codecs, sha256, representationId,
    validatorGeneration, completedEpochMs, producer.bindingId?.value, measuredDurationMs, producer.subjectId.value,
    producer.origin, producer.recordingId?.value, ownershipGeneration, materializerDownloadId, materializerAttemptId,
)

internal fun MaterializationReceiptEntity.toReceipt() = MaterializationReceipt(
    MaterializationAssetId(id), physicalProducer(producerOrigin, subjectId, mediaId, executionBindingId, recordingId),
    sizeBytes, kind, mimeType, codecs, sha256, representationId, validatorGeneration, completedEpochMs,
    measuredDurationMs, materializerDownloadId, materializerAttemptId, ownershipGeneration,
)

internal fun MaterializationReceipt.toEntity(subjects: Map<ExecutableSubjectId, ExecutableSubject>) = MaterializationReceiptEntity(
    id.value, producer.mediaId(subjects), producer.subjectId.value, producer.origin, producer.recordingId?.value,
    producer.bindingId?.value, sizeBytes, kind, mimeType, codecs, sha256, representationId, validatorGeneration,
    completedEpochMs, measuredDurationMs, materializerDownloadId, materializerAttemptId, ownershipGeneration,
)

internal fun DownloadClaimEntity.toClaim(): DownloadClaim {
    val owner = when (ownerKind) {
        "STANDALONE", "SCHEMA2_STANDALONE" -> {
            require(standaloneJobId == physicalJobId && batchId == null && memberPosition == null)
            if (ownerKind == "STANDALONE") DownloadClaimOwner.Standalone(physicalJobId)
            else DownloadClaimOwner.Schema2Standalone(physicalJobId)
        }
        "BATCH_MEMBER" -> {
            require(standaloneJobId == null)
            DownloadClaimOwner.BatchMember(DownloadBatchId(requireNotNull(batchId)), requireNotNull(memberPosition))
        }
        else -> error("Invalid physical claim owner variant")
    }
    return DownloadClaim(DownloadClaimId(id), owner, physicalJobId, requestedBindingId?.let(::ExecutionBindingId),
        intent, retainCompletedAsset, updatedEpochMs, generation, ownerIntentRevision)
}

internal fun DownloadClaim.toEntity(): DownloadClaimEntity {
    val ownerKind: String
    val standalone: String?
    val batch: String?
    val position: Int?
    when (val value = owner) {
        is DownloadClaimOwner.Standalone -> { ownerKind = "STANDALONE"; standalone = value.jobId; batch = null; position = null }
        is DownloadClaimOwner.Schema2Standalone -> { ownerKind = "SCHEMA2_STANDALONE"; standalone = value.jobId; batch = null; position = null }
        is DownloadClaimOwner.BatchMember -> { ownerKind = "BATCH_MEMBER"; standalone = null; batch = value.batchId.value; position = value.position }
    }
    return DownloadClaimEntity(id.value, ownerKind, physicalJobId, standalone, batch, position,
        requestedBindingId?.value, intent, retainCompletedAsset, updatedEpochMs, generation, ownerIntentRevision)
}

internal fun AssetUseEntity.toUse() = AssetUse(ExecutionBindingId(requestedBindingId),
    MaterializationAssetId(materializationAssetId), compatibilityPolicyVersion, createdEpochMs)
internal fun AssetUse.toEntity() = AssetUseEntity(requestedBindingId.value, materializationAssetId.value,
    compatibilityPolicyVersion, createdEpochMs)
