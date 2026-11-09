package dev.veyra.playback

import dev.veyra.model.*
import dev.veyra.source.execution.AcquisitionPlaybackSource

/**
 * Ephemeral discriminated input, not another resolver or a catalogue identity adapter. Never
 * serialize/log transport locators, headers or resources. Constructing a wrapper is inert and
 * does NOT take or release ownership. Engines first register the handed resource, then call
 * [validateSelection] within their existing ownership/failure path before native admission.
 * This deliberately prevents a mismatched public presentation value from leaking its source.
 *
 * Native adapters may copy the transport fields into private decoder input. They must not
 * manufacture ResolvedPlayable.mediaId/MediaItem/RSS identity for the Acquired branch. A native
 * generation/occurrence token is a transport token, not an executable-subject/catalogue ID.
 */
sealed interface NativePlaybackSource {
    data class Legacy(val source: ResolvedPlayable) : NativePlaybackSource {
        override fun toString() = "NativePlaybackSource.Legacy(identity=redacted,transport=redacted)"
    }

    data class Acquired(val source: AcquisitionPlaybackSource, val selection: PlaybackSelection) : NativePlaybackSource {
        override fun toString() = "NativePlaybackSource.Acquired(identity=redacted,transport=redacted)"
    }

    val uri: String get() = when (this) { is Legacy -> source.uri; is Acquired -> source.uri }
    val transport: StreamTransport get() = when (this) { is Legacy -> source.transport; is Acquired -> source.transport }
    val mimeType: String? get() = when (this) { is Legacy -> source.mimeType; is Acquired -> source.mimeType }
    val codecs: String? get() = when (this) { is Legacy -> source.codecs; is Acquired -> source.codecs }
    val headers: Map<String, String> get() = when (this) { is Legacy -> source.headers; is Acquired -> source.headers }
    val expiresAtEpochMs: Long? get() = when (this) { is Legacy -> source.expiresAtEpochMs; is Acquired -> source.expiresAtEpochMs }
    /** Only actual source/validated materialization observations, never A/B's publisher duration. */
    val durationMs: Long? get() = when (this) { is Legacy -> source.durationMs; is Acquired -> source.durationMs }
    val seekableHint: Boolean? get() = when (this) { is Legacy -> source.seekableHint; is Acquired -> source.seekableHint }
    val isLocal: Boolean get() = when (this) { is Legacy -> source.isLocal; is Acquired -> source.isLocal }
    val representationId: String? get() = when (this) { is Legacy -> source.representationId; is Acquired -> source.representationId }
    val resource: PlaybackResource? get() = when (this) { is Legacy -> source.resource; is Acquired -> source.resource }

    /** The old nullable-media convention is confined to the explicitly legacy overload. */
    val controlsMode: PlaybackControlsMode get() = when (this) {
        is Legacy -> if (source.item?.kind == MediaKind.EPISODE) PlaybackControlsMode.PODCAST else PlaybackControlsMode.MUSIC
        is Acquired -> selection.controlsMode
    }

    val catalogMedia: MediaItem? get() = when (this) { is Legacy -> source.item; is Acquired -> selection.catalogMedia }
    val display: PlaybackSelectionDisplay? get() = when (this) {
        is Legacy -> source.executionBinding?.let { PlaybackSelectionDisplay(it.firstResultTitle, publicUrl = it.firstResultUrl) }
            ?: source.item?.let { PlaybackSelectionDisplay(it.title, it.artist ?: it.creator, it.source.canonicalUrl, it.artworkUrl, it.localArtworkPath) }
        is Acquired -> selection.executed
    }

    fun ownedLoopbackUriOrNull(): String? = when (this) {
        is Legacy -> source.ownedLoopbackUriOrNull()
        is Acquired -> source.ownedLoopbackUriOrNull()
    }

    /**
     * Execution/C-native identity plus internal A/B presentation consistency only, NOT saved
     * historical-lineage attestation or current Room/queue execution authority. The independent
     * parent receipt/snapshot must still come from the coherent pure projector, not this input.
     * Equal binding IDs or an internally consistent foreign source are insufficient. Projection
     * revisions may advance without altering these immutable pins; the controller independently
     * fences durable occurrence membership/mode/binding immediately before this handoff.
     */
    fun validateSelection() {
        if (this !is Acquired) return
        val expected = source.context
        val occurrence = selection.occurrence
        nativeSelectionCheck(selection.libraryRevision >= 0L && occurrence.origin == AcquisitionOrigin.TYPED &&
            occurrence.selectionState == OccurrenceSelectionState.BOUND &&
            occurrence.requestId == expected.request.id && occurrence.bindingId == expected.binding.id &&
            occurrence.subjectId == expected.subject.id && selection.executionContext == expected &&
            selection.request == expected.request && selection.receipt == expected.receipt && selection.binding == expected.binding &&
            expected.binding.origin == ExecutionBindingOrigin.TYPED &&
            selection.context == when (occurrence.controlsMode) {
                PlaybackControlsMode.MUSIC -> QueueContext.MUSIC
                PlaybackControlsMode.PODCAST -> QueueContext.PODCAST
            })
        nativeSelectionCheck(expected.receipt.observation.entry.title?.isNotBlank() == true &&
            selection.executed == PlaybackSelectionDisplay(expected.receipt.observation.entry.title,
                publicUrl = expected.receipt.observation.canonicalUrl))
        when (val target = selection.target) {
            is PlaybackTarget.Catalog -> nativeSelectionCheck(target.subject == expected.subject &&
                target.media == (expected.request.metadata as? MediaCapture)?.media &&
                target.media.id == target.subject.mediaId)
            is PlaybackTarget.NeutralRecording -> nativeSelectionCheck(target.subject == expected.subject &&
                target.recording == expected.recording && expected.request.kind != AcquisitionKind.MEDIA &&
                target.subject.recordingId == expected.recording.id)
            is PlaybackTarget.Schema2, null -> nativeSelectionCheck(false)
        }
        val capturedMember = expected.request.metadata as? CollectionMemberCapture
        val member = selection.memberLineage
        if (capturedMember == null) {
            nativeSelectionCheck(occurrence.snapshotMember == null && member == null && selection.observedMember == null)
        } else {
            nativeSelectionCheck(member != null && occurrence.snapshotMember == CollectionSnapshotMemberRef(
                capturedMember.snapshotId, capturedMember.member.reference.ordinal))
            val lineage = member ?: nativeSelectionMismatch()
            val reference = capturedMember.member.reference
            nativeSelectionCheck(lineage.snapshotId == capturedMember.snapshotId && lineage.member == capturedMember.member &&
                lineage.observationId == reference.observationId && lineage.parentRequest.id == reference.parentRequestId &&
                lineage.parentRequest.kind != AcquisitionKind.COLLECTION_MEMBER &&
                lineage.parentReceipt.observation.requestId == lineage.parentRequest.id &&
                lineage.parentReceipt.observation.queryTitle == lineage.parentRequest.completeTitle &&
                lineage.parentReceipt.observation.entry.ordinal == 0 &&
                lineage.parentReceipt.observation.entry.kind == reference.identity.kind &&
                lineage.parentReceipt.observation.entry.id == reference.identity.id &&
                lineage.parentReceipt.observation.entry.issue == null &&
                lineage.parentReceipt.observation.canonicalUrl == reference.identity.canonicalUrl &&
                lineage.observation.id == reference.observationId && lineage.observation.parentRequestId == reference.parentRequestId &&
                lineage.observation.identity == reference.identity && lineage.observation.scope == reference.scope &&
                lineage.member.entry.ordinal == reference.ordinal)
        }
        nativeSelectionCheck(selection.requested == (member?.parentRequest ?: expected.request).metadata.playbackSelectionDisplay() &&
            selection.observedMember == member?.member?.playbackSelectionDisplay())
        val scopeReference = occurrence.scopeMember
        val scope = selection.scopeLineage
        if (scopeReference == null) nativeSelectionCheck(scope == null)
        else {
            val lineage = scope ?: nativeSelectionMismatch()
            nativeSelectionCheck(lineage.id == scopeReference.scopeId && lineage.member.position == scopeReference.position &&
                lineage.member.disposition == AdmissionDisposition.INCLUDED && lineage.member.exclusion == null &&
                lineage.member.requestId == expected.request.id && lineage.member.snapshotMember == occurrence.snapshotMember &&
                lineage.snapshotId == member?.snapshotId && lineage.parentRequestId == member?.parentRequest?.id &&
                ((lineage.kind == AdmissionScopeKind.CATALOG_LEAVES) == (member == null)))
        }
    }
}

private fun nativeSelectionCheck(condition: Boolean) { if (!condition) nativeSelectionMismatch() }
private fun nativeSelectionMismatch(): Nothing = throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "native_playback_selection_mismatch"))
