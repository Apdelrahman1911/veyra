package dev.veyra.data.room

import dev.veyra.data.*
import dev.veyra.model.*

internal fun AcquisitionRows.toAcquisitionState(library: LibraryRows, validate: Boolean = true): AcquisitionRepositoryState {
    val runs = collectionRuns()
    val snapshots = collectionSnapshots(runs)
    val captures = capturedRequests(snapshots)
    val outcomes = acquisitionOutcomeRows.rowIndex { AcquisitionRequestId(it.requestId) }.mapValues { it.value.toOutcome() }
    val jobs = acquisitionJobRows.rowIndex { AcquisitionRequestId(it.requestId) }
    require(jobs.keys == captures.keys && outcomes.keys.all { it in captures })
    val requests = captures.mapValues { (id, request) ->
        val row = jobs.getValue(id)
        val lease = row.leaseToken?.let {
            AcquisitionLease(id, AcquisitionAttemptToken(it), requireNotNull(row.leaseOwner),
                requireNotNull(row.claimedEpochMs), requireNotNull(row.expiresEpochMs))
        }
        require(lease != null || (row.leaseOwner == null && row.claimedEpochMs == null && row.expiresEpochMs == null))
        require((row.state == AcquisitionJobState.COMMITTED) == (id in outcomes))
        require(row.state != AcquisitionJobState.CLAIMED || lease != null)
        require(row.state != AcquisitionJobState.READY || lease == null)
        AcquisitionRecord(request, row.state, lease, outcomes[id], row.updatedEpochMs)
    }
    val receipts = firstResultReceiptRows.rowIndex { FirstResultReceiptId(it.id) }.mapValues { it.value.toReceipt() }
    val recordings = sourceRecordingRows.rowIndex { SourceRecordingId(it.id) }.mapValues { (_, row) ->
        SourceRecording(SourceRecordingId(row.id), row.canonicalId, row.canonicalUrl, row.provider)
    }
    val subjects = executableSubjectRows.rowIndex { ExecutableSubjectId(it.id) }.mapValues { (_, row) -> row.toSubject() }
    val bindings = executionBindingRows.rowIndex { ExecutionBindingId(it.id) }.mapValues { (_, row) ->
        ExecutionBinding(ExecutionBindingId(row.id), ExecutableSubjectId(row.subjectId), FirstResultReceiptId(row.firstReceiptId),
            row.recordingId?.let(::SourceRecordingId), row.origin)
    }
    val scopes = admissionScopes()
    val occurrences = executionOccurrenceRows.rowIndex { ExecutionOccurrenceId(it.id) }.mapValues { it.value.toOccurrence() }
    val revisions = executionQueueRevisionRows.rowIndex { it.contextId }
    val contexts = library.queueContexts.rowIndex { it.id }
    val heads = executionQueueHeadRows.rowIndex { it.contextId }
    executionQueueMemberRows.rowIndex { it.occurrenceId }
    require(revisions.keys == QueueContext.entries.toSet() && contexts.keys == revisions.keys)
    require(heads.keys.all { it in contexts } && executionQueueMemberRows.all { it.contextId in contexts })
    requireRowPositions(executionQueueMemberRows, { it.contextId }, { it.position })
    requireRowPositions(executionQueueMemberRows, { it.contextId }, { it.originalPosition })
    val queueMembers = executionQueueMemberRows.groupBy { it.contextId }
    val queues = contexts.mapValues { (context, row) ->
        val members = queueMembers[context].orEmpty()
        ExecutionQueue(context, revisions.getValue(context).editRevision,
            members.sortedBy { it.position }.map { ExecutionOccurrenceId(it.occurrenceId) },
            members.sortedBy { it.originalPosition }.map { ExecutionOccurrenceId(it.occurrenceId) },
            heads[context]?.occurrenceId?.let(::ExecutionOccurrenceId), row.positionMs, row.speed, row.shuffle, row.repeat)
    }
    val batchRows = downloadBatchRows.rowIndex { DownloadBatchId(it.id) }
    downloadBatchMemberRows.rowIndex { it.batchId to it.position }
    requireRowPositions(downloadBatchMemberRows, { it.batchId }, { it.position })
    require(downloadBatchMemberRows.all { DownloadBatchId(it.batchId) in batchRows })
    val batchMembers = downloadBatchMemberRows.groupBy { it.batchId }
    val batches = batchRows.mapValues { (id, row) ->
        DownloadBatch(id, AdmissionScopeId(row.scopeId), row.intent, row.intentRevision,
            batchMembers[row.id].orEmpty().sortedBy { it.position }.map {
                require(it.scopeId == row.scopeId)
                DownloadBatchMember(it.position, it.scopeMemberPosition, ExecutionOccurrenceId(it.occurrenceId),
                    AcquisitionRequestId(it.requestId), it.requiredBindingId?.let(::ExecutionBindingId),
                    it.claimId?.let(::DownloadClaimId), it.state, it.failure)
            }, row.createdEpochMs, row.updatedEpochMs)
    }
    val claims = downloadClaimRows.rowIndex { DownloadClaimId(it.id) }.mapValues { it.value.toClaim() }
    assetUseRows.rowIndex { it.requestedBindingId to it.materializationAssetId }
    val state = AcquisitionRepositoryState(
        libraryRevision = requireNotNull(library.meta).revision, requests = requests, receipts = receipts,
        recordings = recordings, subjects = subjects, bindings = bindings, runs = runs, snapshots = snapshots,
        scopes = scopes, occurrences = occurrences, queues = queues, batches = batches, claims = claims,
        assetUses = assetUseRows.map { it.toUse() }.toSet(),
        physicalDownloads = library.downloads.rowIndex { it.id }.mapValues { it.value.toPhysicalJob() },
        physicalAttempts = library.attempts.rowIndex { it.id }.mapValues { it.value.toPhysicalAttempt() },
        physicalFinalizations = library.finalizations.rowIndex { it.downloadId }.mapValues { it.value.toPhysicalFinalization() },
        materializations = library.assets.rowIndex { MaterializationAssetId(it.id) }.mapValues { it.value.toMaterialization() },
        materializationReceipts = materializationReceiptRows.rowIndex { MaterializationAssetId(it.id) }.mapValues { it.value.toReceipt() },
    )
    if (validate) validateAcquisitionState(state, library, this)
    return state
}

private fun AcquisitionRows.admissionScopes(): Map<AdmissionScopeId, AdmissionScope> {
    val scopes = admissionScopeRows.rowIndex { it.id }
    admissionScopeMemberRows.rowIndex { it.scopeId to it.position }
    require(admissionScopeMemberRows.all { it.scopeId in scopes })
    requireRowPositions(admissionScopeMemberRows, { it.scopeId }, { it.position })
    val members = admissionScopeMemberRows.groupBy { it.scopeId }
    return scopes.values.associate { row ->
        val id = AdmissionScopeId(row.id)
        id to AdmissionScope(id, row.kind, row.parentRequestId?.let(::AcquisitionRequestId),
            row.snapshotId?.let(::CollectionSnapshotId), row.controlsMode,
            members[row.id].orEmpty().sortedBy { it.position }.map {
                require((it.snapshotId == null) == (it.snapshotOrdinal == null))
                AdmissionScopeMember(it.position, it.snapshotId?.let { id ->
                    CollectionSnapshotMemberRef(CollectionSnapshotId(id), requireNotNull(it.snapshotOrdinal))
                }, it.requestId?.let(::AcquisitionRequestId), it.disposition, it.exclusion)
            }, row.acknowledgesUnobservedSuffix, row.admittedEpochMs)
    }
}

internal fun FirstResultReceiptEntity.toReceipt(): FirstResultReceipt = when (origin) {
    AcquisitionOrigin.TYPED -> {
        require(legacyMediaId == null && legacyFirstResultKind == null && legacyFirstResultId == null &&
            legacyFirstResultTitle == null && legacySelectedVideoId == null && legacyResolvedEpochMs == null && legacySelectionOrdinal == null)
        FirstResultReceipt.Observed(FirstResultReceiptId(id), FirstResultObservation(
            AcquisitionRequestId(requireNotNull(requestId)), queryTitle,
            SourceEntry(rawKind, rawId, rawTitle, rawPublicUrl, rawIssue, requireNotNull(rawOrdinal)), canonicalUrl,
        ), requireNotNull(observedEpochMs))
    }
    AcquisitionOrigin.SCHEMA2 -> {
        require(requestId == null && rawOrdinal == null && rawKind == null && rawId == null && rawTitle == null &&
            rawPublicUrl == null && rawIssue == null && observedEpochMs == null)
        FirstResultReceipt.Schema2(FirstResultReceiptId(id), requireNotNull(legacyMediaId), queryTitle,
            requireNotNull(legacyFirstResultKind), requireNotNull(legacyFirstResultId), requireNotNull(legacyFirstResultTitle),
            canonicalUrl, legacySelectedVideoId, requireNotNull(legacyResolvedEpochMs), requireNotNull(legacySelectionOrdinal))
    }
}

internal fun FirstResultReceipt.Observed.toEntity() = FirstResultReceiptEntity(
    id = id.value, origin = AcquisitionOrigin.TYPED, requestId = observation.requestId.value, legacyMediaId = null,
    queryTitle = observation.queryTitle, rawOrdinal = observation.entry.ordinal, rawKind = observation.entry.kind,
    rawId = observation.entry.id, rawTitle = observation.entry.title, rawPublicUrl = observation.entry.publicUrl,
    rawIssue = observation.entry.issue, canonicalUrl = observation.canonicalUrl, observedEpochMs = observedEpochMs,
    legacyFirstResultKind = null, legacyFirstResultId = null, legacyFirstResultTitle = null, legacySelectedVideoId = null,
    legacyResolvedEpochMs = null, legacySelectionOrdinal = null,
)

internal fun ExecutableSubjectEntity.toSubject(): ExecutableSubject = when (kind) {
    "CATALOG_MEDIA" -> {
        require(recordingId == null)
        ExecutableSubject.CatalogMedia(ExecutableSubjectId(id), requireNotNull(mediaId))
    }
    "SOURCE_RECORDING" -> {
        require(mediaId == null)
        ExecutableSubject.SourceRecordingSubject(ExecutableSubjectId(id), SourceRecordingId(requireNotNull(recordingId)))
    }
    else -> error("Invalid executable subject variant")
}

internal fun ExecutableSubject.toEntity() = when (this) {
    is ExecutableSubject.CatalogMedia -> ExecutableSubjectEntity(id.value, "CATALOG_MEDIA", mediaId, null)
    is ExecutableSubject.SourceRecordingSubject -> ExecutableSubjectEntity(id.value, "SOURCE_RECORDING", null, recordingId.value)
}

internal fun ExecutionBinding.toEntity() = ExecutionBindingEntity(id.value, subjectId.value, firstReceiptId.value, recordingId?.value, origin)
internal fun SourceRecording.toEntity() = SourceRecordingEntity(id.value, provider, canonicalId, canonicalUrl)

internal fun AcquisitionOutcomeEntity.toOutcome(): AcquisitionOutcome {
    require(sourceProblemCode != null || sourceHttpStatus == null)
    val id = AcquisitionRequestId(requestId)
    return when (kind) {
        "SINGLE" -> {
            require(collectionKind == null && collectionId == null && collectionCanonicalUrl == null && collectionScope == null &&
                failureStage == null && problemCode == null && sourceProblemCode == null)
            AcquisitionOutcome.Single(id, FirstResultReceiptId(requireNotNull(receiptId)), ExecutionBindingId(requireNotNull(bindingId)))
        }
        "COLLECTION" -> {
            require(bindingId == null && failureStage == null && problemCode == null && sourceProblemCode == null)
            AcquisitionOutcome.Collection(id, FirstResultReceiptId(requireNotNull(receiptId)),
                CollectionIdentity(requireNotNull(collectionKind), requireNotNull(collectionId), requireNotNull(collectionCanonicalUrl)),
                requireNotNull(collectionScope))
        }
        "UNPLAYABLE" -> {
            require(bindingId == null && collectionKind == null && collectionId == null && collectionCanonicalUrl == null && collectionScope == null)
            AcquisitionOutcome.Unplayable(id, receiptId?.let(::FirstResultReceiptId), requireNotNull(failureStage),
                AcquisitionProblem(requireNotNull(problemCode), sourceProblemCode?.let { SourceProblem(it, sourceHttpStatus) }))
        }
        else -> error("Invalid acquisition outcome variant")
    }
}

internal fun AcquisitionOutcome.toEntity(): AcquisitionOutcomeEntity = when (this) {
    is AcquisitionOutcome.Single -> AcquisitionOutcomeEntity(requestId.value, "SINGLE", receiptId.value, bindingId.value,
        null, null, null, null, null, null, null, null)
    is AcquisitionOutcome.Collection -> AcquisitionOutcomeEntity(requestId.value, "COLLECTION", receiptId.value, null,
        identity.kind, identity.id, identity.canonicalUrl, scope, null, null, null, null)
    is AcquisitionOutcome.Unplayable -> AcquisitionOutcomeEntity(requestId.value, "UNPLAYABLE", receiptId?.value, null,
        null, null, null, null, stage, problem.code, problem.sourceProblem?.code, problem.sourceProblem?.httpStatus)
}

internal fun AcquisitionRecord.toEntity() = AcquisitionJobEntity(request.id.value, state, lease?.token?.value,
    lease?.owner, lease?.claimedEpochMs, lease?.expiresEpochMs, updatedEpochMs)

internal fun ExecutionOccurrenceEntity.toOccurrence(): ExecutionOccurrence {
    require((snapshotId == null) == (snapshotOrdinal == null) && (scopeId == null) == (scopePosition == null))
    return ExecutionOccurrence(ExecutionOccurrenceId(id), origin, requestId?.let(::AcquisitionRequestId),
        snapshotId?.let { CollectionSnapshotMemberRef(CollectionSnapshotId(it), requireNotNull(snapshotOrdinal)) },
        scopeId?.let { AdmissionScopeMemberRef(AdmissionScopeId(it), requireNotNull(scopePosition)) },
        subjectId?.let(::ExecutableSubjectId), bindingId?.let(::ExecutionBindingId), controlsMode, selectionState, admittedEpochMs)
}

internal fun ExecutionOccurrence.toEntity() = ExecutionOccurrenceEntity(id.value, origin, requestId?.value,
    snapshotMember?.snapshotId?.value, snapshotMember?.ordinal, scopeMember?.scopeId?.value, scopeMember?.position,
    subjectId?.value, bindingId?.value, controlsMode, selectionState, admittedEpochMs)

internal fun AdmissionScope.toEntity() = AdmissionScopeEntity(id.value, kind, parentRequestId?.value, snapshotId?.value,
    controlsMode, acknowledgesUnobservedSuffix, admittedEpochMs)

internal fun AdmissionScope.memberRows() = members.map { member -> AdmissionScopeMemberEntity(id.value, member.position,
    member.snapshotMember?.snapshotId?.value, member.snapshotMember?.ordinal, member.requestId?.value,
    member.disposition, member.exclusion) }

internal fun DownloadBatch.toEntity() = DownloadBatchEntity(id.value, scopeId.value, intent, intentRevision, createdEpochMs, updatedEpochMs)
internal fun DownloadBatch.memberRows() = members.map { member -> DownloadBatchMemberEntity(id.value, member.position,
    scopeId.value, member.scopeMemberPosition, member.occurrenceId.value, member.requestId.value,
    member.requiredBindingId?.value, member.claimId?.value, member.state, member.failure) }
