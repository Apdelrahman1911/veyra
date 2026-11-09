package dev.veyra.data.room

import dev.veyra.acquisition.AcquisitionCaptureFactory
import dev.veyra.acquisition.AcquisitionInputRules
import dev.veyra.data.*
import dev.veyra.downloads.summarizeDownloadClaims
import dev.veyra.model.*

internal fun requireVideoIdentity(id: String, url: String) {
    require(id.length == 11 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' })
    require(url == "https://www.youtube.com/watch?v=$id") { "Noncanonical recording locator" }
}

internal fun requireCollectionIdentity(identity: CollectionIdentity, scope: CollectionScope) {
    require(identity.id.isNotBlank() && identity.id.length <= 256 && identity.id.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-'
    })
    when (identity.kind) {
        AcquisitionEntryKind.PLAYLIST -> require(scope == CollectionScope.PLAYLIST_MEMBERS &&
            identity.canonicalUrl == "https://www.youtube.com/playlist?list=${identity.id}")
        AcquisitionEntryKind.CHANNEL -> require(scope == CollectionScope.CHANNEL_VIDEOS &&
            identity.canonicalUrl == "https://www.youtube.com/channel/${identity.id}")
        AcquisitionEntryKind.VIDEO -> error("Collection cannot be a video")
    }
}

internal fun validateSourceResult(request: AcquisitionRequest, result: SourceAcquisitionResult) {
    require(result.requestId == request.id)
    val receipt = when (result) {
        is SourceAcquisitionResult.Single -> result.receipt
        is SourceAcquisitionResult.Collection -> result.receipt
        is SourceAcquisitionResult.Unplayable -> result.receipt
    }
    receipt?.let {
        require(it.requestId == request.id && it.queryTitle == request.completeTitle && it.entry.ordinal == 0)
    }
    when (result) {
        is SourceAcquisitionResult.Single -> {
            val raw = result.receipt.entry
            val recording = result.recording
            require(raw.kind == AcquisitionEntryKind.VIDEO && raw.issue == null && raw.id == recording.id &&
                raw.title == recording.title && raw.publicUrl == recording.publicUrl && recording.kind == AcquisitionEntryKind.VIDEO)
            AcquisitionInputRules.completeTitle(recording.title)
            requireVideoIdentity(recording.id, recording.publicUrl)
            require(result.receipt.canonicalUrl == recording.publicUrl)
        }
        is SourceAcquisitionResult.Collection -> {
            requireCollectionIdentity(result.identity, result.scope)
            val raw = result.receipt.entry
            require(raw.kind == result.identity.kind && raw.id == result.identity.id && raw.issue == null &&
                raw.publicUrl == result.identity.canonicalUrl && result.receipt.canonicalUrl == result.identity.canonicalUrl)
            AcquisitionInputRules.completeTitle(requireNotNull(raw.title))
        }
        is SourceAcquisitionResult.Unplayable -> {
            require(result.problem.sourceProblem?.httpStatus == null || result.problem.sourceProblem.httpStatus in 100..599)
        }
    }
}

/** Full relational graph validation. SQLite FKs/UNIQUE and same-transaction readback remain independent guards. */
internal fun validateAcquisitionState(state: AcquisitionRepositoryState, library: LibraryRows, rows: AcquisitionRows) {
    require(state.libraryRevision >= 0)
    val media = library.media.rowIndex { it.id }
    require(state.recordings.values.map { it.provider to it.canonicalId }.toSet().size == state.recordings.size)
    state.recordings.values.forEach { requireVideoIdentity(it.canonicalId, it.canonicalUrl) }
    require(state.subjects.values.filterIsInstance<ExecutableSubject.SourceRecordingSubject>().map { it.recordingId }.toSet().size ==
        state.subjects.values.count { it is ExecutableSubject.SourceRecordingSubject })
    state.subjects.values.forEach { subject -> when (subject) {
        is ExecutableSubject.CatalogMedia -> require(subject.mediaId in media && subject.id.value == subject.mediaId)
        is ExecutableSubject.SourceRecordingSubject -> require(subject.recordingId in state.recordings)
    } }
    require(media.keys.all { state.subjects[ExecutableSubjectId(it)] == ExecutableSubject.CatalogMedia(ExecutableSubjectId(it), it) })
    state.receipts.values.forEach { receipt -> when (receipt) {
        is FirstResultReceipt.Observed -> {
            val request = state.requests.getValue(receipt.observation.requestId).request
            require(receipt.observation.queryTitle == request.completeTitle && receipt.observation.entry.ordinal == 0)
            require(receipt.observedEpochMs >= request.capturedEpochMs)
        }
        is FirstResultReceipt.Schema2 -> {
            val old = library.bindings.single { it.id == receipt.id.value }
            require(old.mediaId == receipt.mediaId && old.queryTitle == receipt.queryTitle && old.firstResultKind == receipt.firstResultKind &&
                old.firstResultId == receipt.firstResultId && old.firstResultTitle == receipt.firstResultTitle && old.firstResultUrl == receipt.canonicalUrl &&
                old.selectedVideoId == receipt.selectedVideoId && old.resolvedEpochMs == receipt.resolvedEpochMs && old.selectionOrdinal == receipt.selectionOrdinal)
        }
    } }
    state.bindings.values.forEach { binding ->
        val subject = state.subjects.getValue(binding.subjectId)
        val receipt = state.receipts.getValue(binding.firstReceiptId)
        val recording = binding.recordingId?.let(state.recordings::getValue)
        when (binding.origin) {
            ExecutionBindingOrigin.TYPED -> {
                require(receipt is FirstResultReceipt.Observed && recording != null)
                val request = state.requests.getValue(receipt.observation.requestId).request
                validateSourceResult(request, SourceAcquisitionResult.Single(request.id, receipt.observation,
                    SourceDisplayItem(recording.canonicalId, requireNotNull(receipt.observation.entry.title),
                        recording.canonicalUrl, AcquisitionEntryKind.VIDEO)))
                when (subject) {
                    is ExecutableSubject.CatalogMedia -> require((request.metadata as? MediaCapture)?.media?.id == subject.mediaId)
                    is ExecutableSubject.SourceRecordingSubject -> require(request.kind != AcquisitionKind.MEDIA && subject.recordingId == recording.id)
                }
            }
            ExecutionBindingOrigin.SCHEMA2_EXECUTABLE -> {
                require(receipt is FirstResultReceipt.Schema2 && subject is ExecutableSubject.CatalogMedia && recording != null)
                require(receipt.mediaId == subject.mediaId && receipt.firstResultKind == FirstResultKind.VIDEO &&
                    receipt.selectionOrdinal == 0 && receipt.selectedVideoId == receipt.firstResultId && recording.canonicalId == receipt.selectedVideoId)
            }
            ExecutionBindingOrigin.SCHEMA2_NON_EXECUTABLE -> {
                require(receipt is FirstResultReceipt.Schema2 && subject is ExecutableSubject.CatalogMedia && recording == null)
                require(receipt.mediaId == subject.mediaId)
            }
        }
    }
    state.requests.values.forEach { record ->
        require(record.updatedEpochMs >= record.request.capturedEpochMs)
        record.outcome?.let { outcome ->
            require(outcome.requestId == record.request.id)
            outcome.receiptId?.let { require((state.receipts[it] as? FirstResultReceipt.Observed)?.observation?.requestId == record.request.id) }
            when (outcome) {
                is AcquisitionOutcome.Single -> {
                    val binding = state.bindings.getValue(outcome.bindingId)
                    require(binding.origin == ExecutionBindingOrigin.TYPED && binding.firstReceiptId == outcome.receiptId)
                }
                is AcquisitionOutcome.Collection -> {
                    val receipt = state.receipts.getValue(outcome.receiptId) as FirstResultReceipt.Observed
                    validateSourceResult(record.request, SourceAcquisitionResult.Collection(record.request.id, receipt.observation, outcome.identity, outcome.scope))
                }
                is AcquisitionOutcome.Unplayable -> Unit
            }
        }
    }
    state.runs.values.forEach { run ->
        val outcome = state.requests.getValue(run.parentRequestId).outcome as? AcquisitionOutcome.Collection
            ?: error("Collection run lacks accepted parent")
        require(outcome.receiptId == run.parentReceiptId && outcome.identity == run.identity && outcome.scope == run.scope)
        requireCollectionIdentity(run.identity, run.scope)
        run.observation?.let { observation ->
            require(observation.identityEvidence.isNotEmpty() && observation.identity == run.identity && observation.scope == run.scope)
            AcquisitionInputRules.completeTitle(observation.title)
        }
    }
    state.scopes.values.forEach { scope -> validateScope(scope, state) }
    state.occurrences.values.forEach { occurrence ->
        occurrence.subjectId?.let { require(it in state.subjects) }
        occurrence.requestId?.let { requestId ->
            val request = state.requests.getValue(requestId).request
            require(occurrence.admittedEpochMs != null && occurrence.admittedEpochMs >= request.capturedEpochMs)
            val capturedMember = request.metadata as? CollectionMemberCapture
            require(occurrence.snapshotMember == capturedMember?.let { CollectionSnapshotMemberRef(it.snapshotId, it.member.reference.ordinal) })
        }
        occurrence.scopeMember?.let { ref ->
            val scope = state.scopes.getValue(ref.scopeId)
            val member = scope.members.single { it.position == ref.position }
            require(member.disposition == AdmissionDisposition.INCLUDED && member.requestId == occurrence.requestId &&
                scope.controlsMode == occurrence.controlsMode && member.snapshotMember == occurrence.snapshotMember)
        }
        occurrence.bindingId?.let {
            val binding = state.bindings.getValue(it)
            require(binding.subjectId == occurrence.subjectId)
            if (occurrence.origin == AcquisitionOrigin.TYPED) require(
                (state.requests.getValue(requireNotNull(occurrence.requestId)).outcome as? AcquisitionOutcome.Single)?.bindingId == it,
            )
        }
        if (occurrence.origin == AcquisitionOrigin.SCHEMA2) require(occurrence.snapshotMember == null && occurrence.scopeMember == null)
    }
    val queued = mutableSetOf<ExecutionOccurrenceId>()
    state.queues.values.forEach { queue ->
        require(queue.editRevision >= 0 && queue.positionMs >= 0 && queue.speed.isFinite() && queue.speed in 0.5f..2f)
        require(queue.displayedOrder.toSet().size == queue.displayedOrder.size && queue.originalOrder.toSet().size == queue.originalOrder.size &&
            queue.displayedOrder.toSet() == queue.originalOrder.toSet())
        require(queue.currentOccurrenceId == null || queue.currentOccurrenceId in queue.displayedOrder)
        require(queue.context == QueueContext.MUSIC || (!queue.shuffle && queue.repeat == RepeatMode.OFF))
        queue.displayedOrder.forEach {
            require(queued.add(it) && state.occurrences.getValue(it).controlsMode.name == queue.context.name)
        }
    }
    state.batches.values.forEach { batch ->
        val scope = state.scopes.getValue(batch.scopeId)
        require(batch.intentRevision >= 0 && batch.createdEpochMs >= 0 && batch.updatedEpochMs >= batch.createdEpochMs)
        require(batch.members.map { it.position } == batch.members.indices.toList())
        require(batch.members.map { it.scopeMemberPosition } == scope.members.filter { it.disposition == AdmissionDisposition.INCLUDED }.map { it.position })
        require(batch.members.map { it.occurrenceId }.toSet().size == batch.members.size)
        batch.members.forEach { member ->
            val occurrence = state.occurrences.getValue(member.occurrenceId)
            require(occurrence.requestId == member.requestId && occurrence.scopeMember == AdmissionScopeMemberRef(batch.scopeId, member.scopeMemberPosition))
            member.requiredBindingId?.let { require(occurrence.bindingId == it) }
            member.claimId?.let { claimId ->
                val claim = state.claims.getValue(claimId)
                require(claim.owner == DownloadClaimOwner.BatchMember(batch.id, member.position) && claim.requestedBindingId == member.requiredBindingId)
            }
            if (member.state == DownloadBatchMemberState.AVAILABLE) {
                val claim = state.claims.getValue(requireNotNull(member.claimId))
                val job = state.physicalDownloads.getValue(claim.physicalJobId)
                require(job.status == DownloadStatus.COMPLETE && job.assetId != null && state.assetUses.any {
                    it.requestedBindingId == member.requiredBindingId && it.materializationAssetId == job.assetId
                })
            }
        }
    }
    validatePhysicalGraph(state, rows)
    validateNonProjectedAcquisitionRows(state, library, rows)
}

private fun validateScope(scope: AdmissionScope, state: AcquisitionRepositoryState) {
    require(scope.members.map { it.position } == scope.members.indices.toList() && scope.members.size in 1..5_000)
    require(scope.members.any { it.disposition == AdmissionDisposition.INCLUDED })
    if (scope.kind == AdmissionScopeKind.CATALOG_LEAVES) {
        require(scope.snapshotId == null && scope.parentRequestId == null && !scope.acknowledgesUnobservedSuffix)
        require(scope.members.all { it.snapshotMember == null && it.disposition == AdmissionDisposition.INCLUDED &&
            state.requests.getValue(requireNotNull(it.requestId)).request.kind == AcquisitionKind.MEDIA })
        return
    }
    val snapshot = state.snapshots.getValue(requireNotNull(scope.snapshotId))
    require(snapshot.parentRequestId == scope.parentRequestId && snapshot.observation != null && scope.members.size == snapshot.members.size)
    if (snapshot.extent.traversal != CollectionTraversal.OBSERVED_TO_END) require(scope.acknowledgesUnobservedSuffix)
    scope.members.forEach { member ->
        val source = snapshot.members[member.position]
        require(member.snapshotMember == CollectionSnapshotMemberRef(snapshot.id, source.reference.ordinal))
        if (member.disposition == AdmissionDisposition.INCLUDED) {
            val request = state.requests.getValue(requireNotNull(member.requestId)).request
            require(request.metadata == CollectionMemberCapture(snapshot.id, source) && source.isVideoCandidate)
        } else require(member.requestId == null)
    }
    if (scope.kind == AdmissionScopeKind.WHOLE_OBSERVED_LISTING) require(
        snapshot.extent.traversal == CollectionTraversal.OBSERVED_TO_END && !snapshot.extent.hasKnownHoles &&
            snapshot.extent.problem == null && scope.members.all { it.disposition == AdmissionDisposition.INCLUDED },
    )
    if (scope.kind == AdmissionScopeKind.AVAILABLE_SUBSET) {
        val captures = AcquisitionCaptureFactory()
        require(scope.members.withIndex().all { (index, member) ->
            // A raw VIDEO candidate may still have an unusable title/display. Match the exact
            // capture policy rather than forcing those preserved holes into a request.
            val eligible = try { captures.freeze(CollectionMemberCapture(snapshot.id, snapshot.members[index])); true }
            catch (_: VeyraException) { false }
            (member.disposition == AdmissionDisposition.INCLUDED) == eligible
        })
    }
}

internal fun validatePhysicalProducer(producer: PhysicalDownloadProducer, state: AcquisitionRepositoryState) {
    require(producer.subjectId in state.subjects)
    producer.bindingId?.let { id ->
        val binding = state.bindings.getValue(id)
        require(binding.subjectId == producer.subjectId && binding.recordingId == producer.recordingId)
        when (producer) {
            is PhysicalDownloadProducer.Typed -> require(binding.origin == ExecutionBindingOrigin.TYPED)
            is PhysicalDownloadProducer.Schema2 -> require(binding.origin != ExecutionBindingOrigin.TYPED)
        }
    }
    if (producer is PhysicalDownloadProducer.Typed) state.executionContext(producer.bindingId)
    if (producer is PhysicalDownloadProducer.Schema2) require(
        state.subjects[producer.subjectId] == ExecutableSubject.CatalogMedia(producer.subjectId, producer.mediaId),
    )
}

private fun validatePhysicalGraph(state: AcquisitionRepositoryState, rows: AcquisitionRows) {
    require(state.physicalDownloads.values.map { it.producer.subjectId }.toSet().size == state.physicalDownloads.size)
    val claimsByJob = state.claims.values.groupBy { it.physicalJobId }
    state.physicalDownloads.values.forEach { job ->
        validatePhysicalProducer(job.producer, state)
        require(job.createdEpochMs >= 0 && job.updatedEpochMs >= 0 && (job.leaseOwner == null) == (job.leaseExpiresEpochMs == null))
        require(job.leaseOwner == null || job.leaseOwner.isNotBlank())
        require(job.status != DownloadStatus.COMPLETE || job.assetId != null)
        require(job.maintenanceKind != PhysicalDownloadMaintenanceKind.CLEANUP || job.cleanupPending)
        job.activeAttemptId?.let { require(state.physicalAttempts.getValue(it).downloadId == job.id) }
        job.assetId?.let { id ->
            val asset = state.materializations.getValue(id)
            require(asset.producer == job.producer)
        }
        summarizeDownloadClaims(job.id, claimsByJob[job.id].orEmpty())
    }
    require(state.physicalAttempts.values.map { it.downloadId to it.number }.toSet().size == state.physicalAttempts.size)
    state.physicalAttempts.values.forEach { attempt ->
        validatePhysicalProducer(attempt.producer, state)
        val job = state.physicalDownloads.getValue(attempt.downloadId)
        require(attempt.producer.subjectId == job.producer.subjectId && attempt.number <= job.attemptCount &&
            attempt.ownershipGeneration <= job.ownershipGeneration && attempt.bytesDownloaded >= 0 &&
            (attempt.totalBytes == null || attempt.totalBytes >= 0))
        require(attempt.startedEpochMs >= 0 && (attempt.finishedEpochMs == null || attempt.finishedEpochMs >= attempt.startedEpochMs))
        // Earlier schema2 attempts deliberately retain their own historical binding, not the
        // current job's C. An explicit adoption similarly never rewrites these producer facts.
    }
    state.physicalFinalizations.values.forEach { intent ->
        validatePhysicalProducer(intent.producer, state)
        val job = state.physicalDownloads.getValue(intent.downloadId)
        require(intent.producer == job.producer && intent.stagingPath.isNotBlank() && intent.finalPath.isNotBlank() &&
            intent.stagingPath != intent.finalPath && intent.sizeBytes > 0 && validHash(intent.sha256))
        intent.attemptId?.let { id ->
            val attempt = state.physicalAttempts.getValue(id)
            require(attempt.downloadId == job.id && attempt.producer == intent.producer &&
                attempt.ownershipGeneration == intent.ownershipGeneration)
        }
        if (intent.producer is PhysicalDownloadProducer.Typed) require(intent.assetId != null && intent.attemptId != null)
    }
    require(state.materializations.values.map { it.uri }.toSet().size == state.materializations.size)
    require(state.physicalDownloads.values.mapNotNull { it.assetId }.let { it.toSet().size == it.size && it.toSet() == state.materializations.keys })
    state.materializationReceipts.values.forEach { receipt ->
        validatePhysicalProducer(receipt.producer, state)
        require(receipt.sizeBytes > 0 && receipt.validatorGeneration >= 0 && receipt.ownershipGeneration >= 0 &&
            receipt.completedEpochMs >= 0 && (receipt.sha256 == null || validHash(receipt.sha256)) &&
            (receipt.measuredDurationMs == null || receipt.measuredDurationMs >= 0))
        if (receipt.producer is PhysicalDownloadProducer.Typed) require(receipt.sha256 != null &&
            !receipt.representationId.isNullOrBlank() && receipt.materializerDownloadId != null &&
            receipt.materializerAttemptId != null && receipt.ownershipGeneration > 0)
    }
    state.materializations.values.forEach {
        require(it.uri.isNotBlank() && state.materializationReceipts[it.id] == it.receipt())
    }
    state.claims.values.forEach { claim ->
        val job = state.physicalDownloads.getValue(claim.physicalJobId)
        claim.requestedBindingId?.let { id ->
            val requested = state.bindings.getValue(id)
            // Unknown legacy standalone history may remain after explicit adoption but does
            // not assert compatibility or authorize transfer/sharing on its own.
            if (claim.owner !is DownloadClaimOwner.Schema2Standalone) require(
                requested.subjectId == job.producer.subjectId && requested.recordingId != null && requested.recordingId == job.producer.recordingId,
            )
        }
        (claim.owner as? DownloadClaimOwner.BatchMember)?.let { owner ->
            val batch = state.batches.getValue(owner.batchId)
            val member = batch.members.single { it.position == owner.position }
            require(member.claimId == claim.id && member.requiredBindingId == claim.requestedBindingId &&
                claim.ownerIntentRevision <= batch.intentRevision)
        }
        require(claim.updatedEpochMs >= 0)
    }
    state.assetUses.forEach { use ->
        val requested = state.bindings.getValue(use.requestedBindingId)
        val receipt = state.materializationReceipts.getValue(use.materializationAssetId)
        require(use.compatibilityPolicyVersion == 1 && use.createdEpochMs >= receipt.completedEpochMs &&
            requested.recordingId != null && requested.recordingId == receipt.producer.recordingId && requested.subjectId == receipt.producer.subjectId)
    }
    rows.physicalDownloadAdoptionRows.rowIndex { it.jobId to it.newOwnershipGeneration }
    rows.physicalDownloadAdoptionRows.forEach { adoption ->
        require(adoption.oldRecordingId == null && adoption.oldMediaId == adoption.subjectId &&
            adoption.newOwnershipGeneration == adoption.oldOwnershipGeneration + 1 && adoption.oldOwnershipGeneration >= 0)
        val job = state.physicalDownloads.getValue(adoption.jobId)
        val binding = state.bindings.getValue(ExecutionBindingId(adoption.newBindingId))
        require(binding.origin == ExecutionBindingOrigin.TYPED && binding.subjectId.value == adoption.subjectId &&
            binding.recordingId?.value == adoption.newRecordingId && job.ownershipGeneration >= adoption.newOwnershipGeneration)
        require((state.requests.getValue(AcquisitionRequestId(adoption.newRequestId)).outcome as? AcquisitionOutcome.Single)?.bindingId == binding.id)
        require(adoption.adoptedEpochMs >= 0)
    }
}

internal fun validHash(value: String): Boolean = value.length == 64 && value.all { it in 'a'..'f' || it in 'A'..'F' || it in '0'..'9' }

private fun validateNonProjectedAcquisitionRows(state: AcquisitionRepositoryState, library: LibraryRows, rows: AcquisitionRows) {
    val releases = rows.releases.toCapturedReleaseObservations()
    rows.releaseCatalogEntryRows.rowIndex { it.requestedReleaseMbid }
    rows.savedReleaseRows.rowIndex { it.requestedReleaseMbid }
    rows.releaseCatalogEntryRows.forEach {
        require(releases.getValue(ReleaseObservationId(it.latestObservationId)).value.requestedReleaseMbid == it.requestedReleaseMbid && it.updatedEpochMs >= 0)
    }
    rows.savedReleaseRows.forEach {
        require(releases.getValue(ReleaseObservationId(it.observationId)).value.requestedReleaseMbid == it.requestedReleaseMbid && it.pinnedEpochMs >= 0)
    }
    val checkpoints = rows.occurrenceCheckpointRows.rowIndex { it.occurrenceId }
    checkpoints.values.forEach {
        val occurrence = state.occurrences.getValue(ExecutionOccurrenceId(it.occurrenceId))
        require(occurrence.bindingId?.value == it.bindingId && it.positionMs >= 0 &&
            (it.measuredDurationMs == null || it.measuredDurationMs >= 0) && it.observedEpochMs >= 0 &&
            it.sessionId.isNotBlank() && it.generation >= 0 && it.sequence >= 0)
    }
    rows.sourceRecordingResumeRows.rowIndex { it.subjectId to it.controlsMode }
    rows.sourceRecordingResumeRows.forEach {
        val occurrence = state.occurrences.getValue(ExecutionOccurrenceId(it.occurrenceId))
        require(occurrence.subjectId?.value == it.subjectId && occurrence.controlsMode == it.controlsMode &&
            state.subjects[occurrence.subjectId] is ExecutableSubject.SourceRecordingSubject && it.occurrenceId in checkpoints)
    }
    rows.catalogProgressRows.rowIndex { it.mediaId }
    rows.catalogProgressRows.forEach { progress ->
        require(state.subjects[ExecutableSubjectId(progress.mediaId)] is ExecutableSubject.CatalogMedia && progress.positionMs >= 0 &&
            (progress.durationMs == null || progress.durationMs >= 0))
        progress.executionBindingId?.let { require(state.bindings.getValue(ExecutionBindingId(it)).subjectId.value == progress.mediaId) }
        progress.lastOccurrenceId?.let { require(state.occurrences.getValue(ExecutionOccurrenceId(it)).subjectId?.value == progress.mediaId) }
    }
    rows.listeningHistoryRows.rowIndex { it.id }
    requireRowPositions(rows.listeningHistoryRows, { Unit }, { it.position })
    rows.listeningHistoryRows.forEach { history ->
        require(ExecutableSubjectId(history.subjectId) in state.subjects && history.startedEpochMs >= 0 && history.lastPositionMs >= 0)
        if (history.occurrenceId != null) {
            // New native observations of a retained schema2 occurrence keep schema2 lineage;
            // making that observation does not fabricate a typed request or rewrite old history.
            val occurrence = state.occurrences.getValue(ExecutionOccurrenceId(history.occurrenceId))
            require(occurrence.origin == history.origin && occurrence.subjectId?.value == history.subjectId &&
                occurrence.bindingId?.value == history.bindingId)
        } else require(history.origin == AcquisitionOrigin.SCHEMA2)
        history.bindingId?.let { require(state.bindings.getValue(ExecutionBindingId(it)).subjectId.value == history.subjectId) }
    }
    rows.favoriteSubjectRows.rowIndex { it.subjectId }
    rows.playlistMemberRows.rowIndex { it.id }
    requireRowPositions(rows.playlistMemberRows, { it.playlistId }, { it.position })
    val playlists = library.playlists.map { it.id }.toSet()
    rows.favoriteSubjectRows.forEach { row -> validateSavedLineage(row.subjectId, row.origin, row.requestId,
        row.bindingId, row.occurrenceId, row.controlsMode, state) }
    rows.playlistMemberRows.forEach { row ->
        require(row.playlistId in playlists)
        validateSavedLineage(row.subjectId, row.origin, row.requestId, row.bindingId, row.occurrenceId, row.controlsMode, state)
    }
}

private fun validateSavedLineage(subject: String, origin: SavedLibraryOrigin, request: String?, binding: String?,
    occurrence: String?, mode: PlaybackControlsMode?, state: AcquisitionRepositoryState) {
    val subjectId = ExecutableSubjectId(subject)
    require(subjectId in state.subjects)
    val executable = state.subjects.getValue(subjectId)
    when (origin) {
        SavedLibraryOrigin.CATALOG -> {
            require(executable is ExecutableSubject.CatalogMedia)
            require(request == null && binding == null && occurrence == null && mode == null)
        }
        SavedLibraryOrigin.SCHEMA2 -> require(request == null && binding == null && occurrence == null && mode == null)
        SavedLibraryOrigin.TYPED -> {
            val saved = state.occurrences.getValue(ExecutionOccurrenceId(requireNotNull(occurrence)))
            require(saved.subjectId?.value == subject && saved.requestId?.value == request && saved.bindingId?.value == binding && saved.controlsMode == mode)
        }
    }
}
