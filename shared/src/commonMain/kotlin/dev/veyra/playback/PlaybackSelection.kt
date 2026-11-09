package dev.veyra.playback

import dev.veyra.acquisition.AcquisitionCaptureFactory
import dev.veyra.data.LibraryAcquisitionView
import dev.veyra.data.AcquisitionRepositoryState
import dev.veyra.data.executionContext
import dev.veyra.model.*
import dev.veyra.source.execution.AcquisitionExecutionContext

/** Catalogue identity is explicit; a neutral recording never stands in for a MediaItem. */
sealed interface PlaybackTarget {
    data class Catalog(val subject: ExecutableSubject.CatalogMedia, val media: MediaItem) : PlaybackTarget
    data class NeutralRecording(
        val subject: ExecutableSubject.SourceRecordingSubject,
        val recording: SourceRecording,
    ) : PlaybackTarget
    /** Historical catalogue identity only; this variant does not invent a typed request/receipt. */
    data class Schema2(val mediaId: String, val media: MediaItem?) : PlaybackTarget
}

/**
 * No source/catalogue refresh, capture clock, or new ID is consulted. A committed request may
 * outlive a removed occurrence or await attachment; its latest outcome never supplies an
 * unbound occurrence with a fabricated target. Queue admission remains the controller's job.
 */
private fun projectPlaybackSelection(view: LibraryAcquisitionView, id: ExecutionOccurrenceId): PlaybackSelection {
    try {
        val state = view.acquisition
        selectionCheck(view.snapshot.revision == state.libraryRevision && state.libraryRevision >= 0L)
        val occurrence = state.occurrences[id] ?: invalidSelection()
        selectionCheck(occurrence.id == id)
        val context = when (occurrence.controlsMode) {
            PlaybackControlsMode.MUSIC -> QueueContext.MUSIC
            PlaybackControlsMode.PODCAST -> QueueContext.PODCAST
        }
        // A retained historical/batch occurrence need not still be queued. If it is, its actual
        // owner cannot disagree with its explicit mode. Do not use the currently browsed queue.
        state.queues.forEach { (key, queue) ->
            if (id in queue.displayedOrder || id in queue.originalOrder || queue.currentOccurrenceId == id) {
                selectionCheck(key == context && queue.context == context &&
                    queue.displayedOrder.count { it == id } == 1 && queue.originalOrder.count { it == id } == 1)
            }
        }
        if (occurrence.origin == AcquisitionOrigin.SCHEMA2) return schema2Selection(view, context, occurrence)

        val record = state.requests[occurrence.requestId] ?: invalidSelection()
        selectionCheck(record.request.id == occurrence.requestId)
        val request = record.request.detachedSelectionRequest()
        val outcome = record.outcome
        selectionCheck(outcome == null || outcome.requestId == request.id)
        val receipt = outcome?.receiptId?.let { receiptId ->
            val observed = state.receipts[receiptId] as? FirstResultReceipt.Observed ?: invalidSelection()
            selectionCheck(observed.id == receiptId && observed.observation.requestId == request.id &&
                observed.observation.queryTitle == request.completeTitle && observed.observation.entry.ordinal == 0)
            observed.detachedSelectionReceipt()
        }
        val member = memberSelection(state, occurrence, request)
        val scope = scopeSelection(state, occurrence, request, member)
        val exact = if (occurrence.selectionState == OccurrenceSelectionState.BOUND) {
            val single = outcome as? AcquisitionOutcome.Single ?: invalidSelection()
            selectionCheck(single.bindingId == occurrence.bindingId && single.receiptId == receipt?.id)
            val joined = state.executionContext(single.bindingId)
            selectionCheck(joined.request == request && joined.receipt == receipt &&
                joined.binding.id == occurrence.bindingId && joined.subject.id == occurrence.subjectId &&
                joined.receipt.observation.entry.title?.isNotBlank() == true)
            joined.copy(request = request, receipt = receipt ?: invalidSelection())
        } else null
        val target = exact?.let { execution ->
            when (val subject = execution.subject) {
                is ExecutableSubject.CatalogMedia -> {
                    val media = (request.metadata as? MediaCapture)?.media ?: invalidSelection()
                    selectionCheck(media.id == subject.mediaId)
                    PlaybackTarget.Catalog(subject, media)
                }
                is ExecutableSubject.SourceRecordingSubject -> PlaybackTarget.NeutralRecording(subject, execution.recording)
            }
        }
        return PlaybackSelection(
            libraryRevision = state.libraryRevision, context = context, occurrence = occurrence.copy(),
            request = request, receipt = receipt, binding = exact?.binding, target = target,
            requested = (member?.parentRequest ?: request).metadata.playbackSelectionDisplay(),
            observedMember = member?.member?.playbackSelectionDisplay(),
            executed = exact?.receipt?.observation?.let { PlaybackSelectionDisplay(it.entry.title, publicUrl = it.canonicalUrl) },
            memberLineage = member, scopeLineage = scope, executionContext = exact,
        )
    } catch (error: VeyraException) {
        throw error
    } catch (_: IllegalArgumentException) {
        invalidSelection()
    } catch (_: IllegalStateException) {
        invalidSelection()
    } catch (_: NoSuchElementException) {
        invalidSelection()
    }
}

private fun schema2Selection(
    view: LibraryAcquisitionView,
    context: QueueContext,
    occurrence: ExecutionOccurrence,
): PlaybackSelection {
    selectionCheck(occurrence.requestId == null && occurrence.snapshotMember == null && occurrence.scopeMember == null)
    val state = view.acquisition
    val subject = state.subjects[occurrence.subjectId] as? ExecutableSubject.CatalogMedia ?: invalidSelection()
    selectionCheck(subject.id == occurrence.subjectId)
    val media = view.snapshot.media[subject.mediaId]?.let {
        selectionCheck(it.id == subject.mediaId)
        it.copy(artistIds = it.artistIds.toList())
    }
    val binding = occurrence.bindingId?.let { state.bindings[it] ?: invalidSelection() }
    val receipt = binding?.let {
        selectionCheck(occurrence.selectionState == OccurrenceSelectionState.BOUND && it.id == occurrence.bindingId &&
            it.subjectId == subject.id && it.origin == ExecutionBindingOrigin.SCHEMA2_EXECUTABLE)
        val historical = state.receipts[it.firstReceiptId] as? FirstResultReceipt.Schema2 ?: invalidSelection()
        val recording = state.recordings[it.recordingId] ?: invalidSelection()
        selectionCheck(historical.id == it.firstReceiptId && historical.mediaId == subject.mediaId &&
            historical.firstResultKind == FirstResultKind.VIDEO && historical.selectionOrdinal == 0 &&
            historical.selectedVideoId == historical.firstResultId && recording.id == it.recordingId &&
            recording.canonicalId == historical.selectedVideoId &&
            (historical.canonicalUrl == null || historical.canonicalUrl == recording.canonicalUrl))
        historical.copy()
    }
    // No receipt on a migrated unbound queue slot: not latest-media, progress, or download receipt.
    return PlaybackSelection(
        libraryRevision = state.libraryRevision, context = context, occurrence = occurrence.copy(),
        request = null, receipt = receipt, binding = binding,
        target = PlaybackTarget.Schema2(subject.mediaId, media),
        requested = receipt?.let { PlaybackSelectionDisplay(it.queryTitle) },
        observedMember = null,
        executed = receipt?.let { PlaybackSelectionDisplay(it.firstResultTitle, publicUrl = it.canonicalUrl) },
    )
}

private fun memberSelection(
    state: AcquisitionRepositoryState,
    occurrence: ExecutionOccurrence,
    request: AcquisitionRequest,
): PlaybackMemberSelection? {
    val capture = request.metadata as? CollectionMemberCapture
    if (capture == null) {
        selectionCheck(occurrence.snapshotMember == null)
        return null
    }
    val reference = occurrence.snapshotMember ?: invalidSelection()
    selectionCheck(reference.snapshotId == capture.snapshotId && reference.ordinal == capture.member.reference.ordinal)
    val snapshot = state.snapshots[reference.snapshotId] ?: invalidSelection()
    val observation = snapshot.observation ?: invalidSelection()
    val member = snapshot.members.singleOrNull { it.reference.ordinal == reference.ordinal } ?: invalidSelection()
    selectionCheck(snapshot.id == reference.snapshotId && snapshot.observationId == observation.id &&
        snapshot.parentRequestId == observation.parentRequestId && snapshot.extent.observedMemberCount == snapshot.members.size &&
        member == capture.member && member.reference.observationId == observation.id &&
        member.reference.parentRequestId == snapshot.parentRequestId && member.reference.identity == observation.identity &&
        member.reference.scope == observation.scope && member.entry.ordinal == reference.ordinal)
    val parent = state.requests[snapshot.parentRequestId] ?: invalidSelection()
    val parentReceipt = state.receipts[snapshot.parentReceiptId] as? FirstResultReceipt.Observed ?: invalidSelection()
    val parentOutcome = parent.outcome as? AcquisitionOutcome.Collection ?: invalidSelection()
    selectionCheck(parent.request.id == snapshot.parentRequestId && parent.request.kind != AcquisitionKind.COLLECTION_MEMBER &&
        parentOutcome.requestId == parent.request.id && parentOutcome.receiptId == parentReceipt.id &&
        parentOutcome.identity == observation.identity && parentOutcome.scope == observation.scope &&
        parentReceipt.id == snapshot.parentReceiptId && parentReceipt.observation.requestId == parent.request.id &&
        parentReceipt.observation.queryTitle == parent.request.completeTitle && parentReceipt.observation.entry.ordinal == 0 &&
        parentReceipt.observation.entry.kind == observation.identity.kind &&
        parentReceipt.observation.entry.id == observation.identity.id && parentReceipt.observation.entry.issue == null &&
        parentReceipt.observation.canonicalUrl == observation.identity.canonicalUrl)
    return PlaybackMemberSelection(
        snapshot.id, snapshot.observationId, parent.request.detachedSelectionRequest(), parentReceipt.detachedSelectionReceipt(),
        observation.copy(identityEvidence = observation.identityEvidence.toSet()), capture.member,
        snapshot.extent.copy(), snapshot.sealedEpochMs,
    )
}

private fun scopeSelection(
    state: AcquisitionRepositoryState,
    occurrence: ExecutionOccurrence,
    request: AcquisitionRequest,
    member: PlaybackMemberSelection?,
): PlaybackScopeSelection? {
    val reference = occurrence.scopeMember ?: return null
    val scope = state.scopes[reference.scopeId] ?: invalidSelection()
    val position = scope.members.singleOrNull { it.position == reference.position } ?: invalidSelection()
    selectionCheck(scope.id == reference.scopeId && scope.controlsMode == occurrence.controlsMode &&
        position.disposition == AdmissionDisposition.INCLUDED && position.requestId == request.id &&
        position.snapshotMember == occurrence.snapshotMember && position.exclusion == null &&
        scope.snapshotId == member?.snapshotId && scope.parentRequestId == member?.parentRequest?.id &&
        ((scope.kind == AdmissionScopeKind.CATALOG_LEAVES) == (member == null)))
    return PlaybackScopeSelection(scope.id, scope.kind, scope.parentRequestId, scope.snapshotId, position.copy(),
        scope.acknowledgesUnobservedSuffix, scope.admittedEpochMs)
}

internal fun RequestedMetadata.playbackSelectionDisplay(): PlaybackSelectionDisplay = when (this) {
    is MediaCapture -> PlaybackSelectionDisplay(media.title, media.artist ?: media.creator.takeIf { it.isNotEmpty() },
        media.source.canonicalUrl, media.artworkUrl, media.localArtworkPath)
    is AlbumCapture -> PlaybackSelectionDisplay(album.title,
        artists.takeIf { it.isNotEmpty() && it.all { credit -> credit.metadata != null } }
            ?.joinToString(", ") { it.metadata!!.name }, album.source?.canonicalUrl, album.artworkUrl)
    is ReleaseCapture -> PlaybackSelectionDisplay(completeTitle, publicUrl = observation.value.sourceUrl)
    is ShowCapture -> PlaybackSelectionDisplay(show.title, show.publisher.takeIf { it.isNotEmpty() },
        show.websiteUrl ?: show.feedUrl, show.artworkUrl, show.localArtworkPath)
    is SourceCollectionCapture -> PlaybackSelectionDisplay(collection.completeTitle, collection.creator,
        collection.publicUrl, collection.artworkUrl)
    is CollectionMemberCapture -> member.playbackSelectionDisplay()
}

internal fun CollectionMember.playbackSelectionDisplay() = PlaybackSelectionDisplay(entry.title, display?.subtitle,
    entry.publicUrl, display?.thumbnailUrl)

private val selectionCaptures = AcquisitionCaptureFactory(clock = object : Clock {
    override fun epochMs(): Long = invalidSelection()
    override fun monotonicMs(): Long = invalidSelection()
})

private fun AcquisitionRequest.detachedSelectionRequest() = copy(metadata = selectionCaptures.freeze(metadata))
private fun FirstResultReceipt.Observed.detachedSelectionReceipt() = copy(observation = observation.copy(entry = observation.entry.copy()))
private fun selectionCheck(condition: Boolean) { if (!condition) invalidSelection() }
private fun invalidSelection(): Nothing = throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "playback_selection_mismatch"))

/** Captured public presentation, never a signed media locator/header or latest-catalogue lookup. */
data class PlaybackSelectionDisplay(
    val title: String?,
    val subtitle: String? = null,
    val publicUrl: String? = null,
    val artworkUrl: String? = null,
    val localArtworkPath: String? = null,
)

/** Only this exact B occurrence and its parent A; never a copy of a 5,000-member snapshot. */
data class PlaybackMemberSelection(
    val snapshotId: CollectionSnapshotId,
    val observationId: CollectionObservationId,
    val parentRequest: AcquisitionRequest,
    val parentReceipt: FirstResultReceipt.Observed,
    val observation: CollectionObservation,
    val member: CollectionMember,
    val extent: CollectionExtent,
    val sealedEpochMs: Long,
)

/** Only the selected admitted position, preserving its distinction from the source ordinal. */
data class PlaybackScopeSelection(
    val id: AdmissionScopeId,
    val kind: AdmissionScopeKind,
    val parentRequestId: AcquisitionRequestId?,
    val snapshotId: CollectionSnapshotId?,
    val member: AdmissionScopeMember,
    val acknowledgesUnobservedSuffix: Boolean,
    val admittedEpochMs: Long,
)

/**
 * Detached one-revision occurrence projection. This is presentation, not execution permission:
 * the controller still fences the exact occurrence/request/binding before native handoff.
 * For a collection child, request is B's child request, requested displays parent A, observedMember
 * displays B, and executed displays the actual first-result C. Pending work has no invented target.
 */
data class PlaybackSelection(
    val libraryRevision: Long,
    val context: QueueContext,
    val occurrence: ExecutionOccurrence,
    val request: AcquisitionRequest?,
    val receipt: FirstResultReceipt?,
    val binding: ExecutionBinding?,
    val target: PlaybackTarget?,
    val requested: PlaybackSelectionDisplay?,
    val observedMember: PlaybackSelectionDisplay?,
    val executed: PlaybackSelectionDisplay?,
    val memberLineage: PlaybackMemberSelection? = null,
    val scopeLineage: PlaybackScopeSelection? = null,
    /** Exact accepted typed Single. Null for schema2 and every unbound/non-executable state. */
    val executionContext: AcquisitionExecutionContext? = null,
) {
    val occurrenceId: ExecutionOccurrenceId get() = occurrence.id
    val requestId: AcquisitionRequestId? get() = occurrence.requestId
    val receiptId: FirstResultReceiptId? get() = receipt?.id
    val bindingId: ExecutionBindingId? get() = occurrence.bindingId
    val subjectId: ExecutableSubjectId? get() = occurrence.subjectId
    val controlsMode: PlaybackControlsMode get() = occurrence.controlsMode
    val selectionState: OccurrenceSelectionState get() = occurrence.selectionState

    /** Genuine captured catalogue metadata only, including an as-yet-unbound MEDIA request. */
    val catalogMedia: MediaItem? get() = when (val selected = target) {
        is PlaybackTarget.Catalog -> selected.media
        is PlaybackTarget.Schema2 -> selected.media
        is PlaybackTarget.NeutralRecording -> null
        null -> (request?.metadata as? MediaCapture)?.media
    }

    /** Compatibility projection only; neutral recordings never become a catalogue item. */
    val item: MediaItem? get() = catalogMedia

    /** Never substitute A/B's artwork/title once the execution receipt identifies C. */
    val primaryDisplay: PlaybackSelectionDisplay? get() = executed ?: observedMember ?: requested

    override fun toString(): String = "PlaybackSelection(context=$context,state=$selectionState,identity=redacted)"

    companion object {
        /** Pure historical or queued occurrence join; not a current-queue execution fence. */
        fun project(view: LibraryAcquisitionView, occurrenceId: ExecutionOccurrenceId): PlaybackSelection =
            projectPlaybackSelection(view, occurrenceId)
    }
}
