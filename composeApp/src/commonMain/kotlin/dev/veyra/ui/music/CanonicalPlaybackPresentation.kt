package dev.veyra.ui.music

import dev.veyra.model.AcquisitionEntryKind
import dev.veyra.model.AppSnapshot
import dev.veyra.model.ExecutionOccurrenceId
import dev.veyra.model.FirstResultKind
import dev.veyra.model.FirstResultReceipt
import dev.veyra.model.MediaItem
import dev.veyra.model.PlaybackBinding
import dev.veyra.model.PlaybackControlsMode
import dev.veyra.model.QueueContext
import dev.veyra.model.RepeatMode
import dev.veyra.model.SourceProvider
import dev.veyra.playback.CrossfadeState
import dev.veyra.playback.PlaybackSelection
import dev.veyra.playback.PlaybackState
import dev.veyra.playback.PlaybackTarget

/**
 * Bounded presentation projection over the one published [PlaybackState]. It never consults the
 * current catalogue, a progress row, a download receipt or a "latest" search result to fill a
 * missing identity. A canonical empty queue stays empty even when the legacy snapshot still has
 * rows; the legacy projection is used only when no canonical queues were published at all.
 */
internal enum class QueueRowStatus { READY, PENDING_RESOLUTION, UNAVAILABLE, MISSING_MEDIA, MISSING_SELECTION }

internal data class QueueItemRow(
    val entryId: String,
    val occurrenceId: ExecutionOccurrenceId?,
    val mediaId: String?,
    val title: String?,
    val creator: String?,
    val artworkUrl: String?,
    val localArtworkPath: String?,
    val listingProvider: SourceProvider?,
    val status: QueueRowStatus,
    val canonical: Boolean,
    val neutral: Boolean,
) {
    val playable: Boolean get() = status == QueueRowStatus.READY || status == QueueRowStatus.PENDING_RESOLUTION
}

internal data class QueueSurface(
    val canonical: Boolean,
    val current: QueueItemRow?,
    val upcoming: List<QueueItemRow>,
    val displayedIds: List<String>,
    val shuffle: Boolean,
    val repeat: RepeatMode,
    val currentOccurrenceId: String?,
)

internal fun PlaybackState.queueSurface(context: QueueContext, snapshot: AppSnapshot): QueueSurface {
    if (executionQueues.isNotEmpty()) {
        val queue = executionQueues[context]
            ?: return QueueSurface(true, null, emptyList(), emptyList(), false, RepeatMode.OFF, null)
        val rows = queue.displayedOrder.map { id -> canonicalRow(id, queueSelections[id]) }
        val currentIndex = queue.currentOccurrenceId?.let { queue.displayedOrder.indexOf(it) } ?: -1
        return QueueSurface(
            canonical = true,
            current = rows.getOrNull(currentIndex),
            upcoming = if (currentIndex >= 0) rows.drop(currentIndex + 1) else rows,
            displayedIds = queue.displayedOrder.map { it.value },
            shuffle = queue.shuffle,
            repeat = queue.repeat,
            currentOccurrenceId = queue.currentOccurrenceId?.value,
        )
    }
    val legacy = queues[context] ?: snapshot.queue(context)
    val pendingIds = pendingOccurrenceIds(legacy.entries.map { it.id }, legacy.currentEntryId)
    return QueueSurface(
        canonical = false,
        current = legacy.currentEntry?.let { legacyRow(it.id, it.mediaId, snapshot) },
        upcoming = legacy.entries.filter { it.id in pendingIds }.map { legacyRow(it.id, it.mediaId, snapshot) },
        displayedIds = legacy.entries.map { it.id },
        shuffle = legacy.shuffle,
        repeat = legacy.repeat,
        currentOccurrenceId = legacy.currentEntryId,
    )
}

private fun canonicalRow(id: ExecutionOccurrenceId, selection: PlaybackSelection?): QueueItemRow {
    if (selection == null) {
        return QueueItemRow(id.value, id, null, null, null, null, null, null,
            QueueRowStatus.MISSING_SELECTION, canonical = true, neutral = false)
    }
    val catalog = selection.catalogMedia
    val requested = selection.requested
    val observed = selection.observedMember
    val executed = selection.executed
    val status = when (selection.selectionState) {
        dev.veyra.model.OccurrenceSelectionState.BOUND -> QueueRowStatus.READY
        dev.veyra.model.OccurrenceSelectionState.PENDING -> QueueRowStatus.PENDING_RESOLUTION
        else -> QueueRowStatus.UNAVAILABLE
    }
    return QueueItemRow(
        entryId = id.value,
        occurrenceId = id,
        mediaId = catalog?.id,
        // Listing identity first: a C receipt may differ and is disclosed separately, never
        // substituted for the requested recording's title.
        title = catalog?.title ?: observed?.title ?: requested?.title ?: executed?.title,
        creator = catalog?.creator?.takeIf { it.isNotBlank() } ?: observed?.subtitle ?: requested?.subtitle,
        artworkUrl = catalog?.artworkUrl ?: (observed ?: requested)?.artworkUrl,
        localArtworkPath = catalog?.localArtworkPath ?: (observed ?: requested)?.localArtworkPath,
        listingProvider = catalog?.source?.provider,
        status = status,
        canonical = true,
        neutral = selection.target is PlaybackTarget.NeutralRecording,
    )
}

private fun legacyRow(entryId: String, mediaId: String, snapshot: AppSnapshot): QueueItemRow {
    val item = snapshot.media[mediaId]
    return QueueItemRow(
        entryId = entryId,
        occurrenceId = null,
        mediaId = mediaId,
        title = item?.title,
        creator = item?.creator?.takeIf { it.isNotBlank() },
        artworkUrl = item?.artworkUrl,
        localArtworkPath = item?.localArtworkPath,
        listingProvider = item?.source?.provider,
        status = if (item == null) QueueRowStatus.MISSING_MEDIA else QueueRowStatus.READY,
        canonical = false,
        neutral = false,
    )
}

/** One immutable owner-selected identity for the mini/full player and its receipt disclosure. */
internal data class PlaybackSurfaceIdentity(
    val key: String,
    val title: String?,
    val creator: String?,
    val artworkUrl: String?,
    val localArtworkPath: String?,
    val listingProvider: SourceProvider?,
    val catalogItem: MediaItem?,
    val neutral: Boolean,
    val controlsMode: PlaybackControlsMode,
    val requestedTitle: String?,
    val resolvedTitle: String?,
    val provenance: PlaybackProvenanceView?,
)

internal fun PlaybackState.surfaceIdentity(snapshot: AppSnapshot): PlaybackSurfaceIdentity? {
    currentSelection?.let { selection ->
        val catalog = selection.catalogMedia
        val current = catalog?.let { captured ->
            // Only the mutable local artwork cache path is refreshed; captured title/creator/
            // provider remain the owner-selected request identity.
            val local = snapshot.media[captured.id]?.localArtworkPath
            if (local != null && local != captured.localArtworkPath) captured.copy(localArtworkPath = local) else captured
        }
        return PlaybackSurfaceIdentity(
            key = selection.occurrenceId.value,
            title = current?.title ?: selection.observedMember?.title ?: selection.requested?.title
                ?: selection.executed?.title,
            creator = current?.creator?.takeIf { it.isNotBlank() } ?: selection.observedMember?.subtitle
                ?: selection.requested?.subtitle,
            artworkUrl = current?.artworkUrl ?: (selection.observedMember ?: selection.requested)?.artworkUrl,
            localArtworkPath = current?.localArtworkPath ?: (selection.observedMember ?: selection.requested)?.localArtworkPath,
            listingProvider = current?.source?.provider,
            catalogItem = current,
            neutral = selection.target is PlaybackTarget.NeutralRecording,
            controlsMode = selection.controlsMode,
            requestedTitle = selection.requestedTitle(),
            resolvedTitle = selection.resolvedTitle(),
            provenance = selection.provenanceView(),
        )
    }
    val item = currentItem ?: return null
    val binding = executionBinding?.takeIf { it.mediaId == item.id }
    return PlaybackSurfaceIdentity(
        key = currentEntryId ?: item.id,
        title = item.title,
        creator = item.creator.takeIf { it.isNotBlank() },
        artworkUrl = item.artworkUrl,
        localArtworkPath = item.localArtworkPath,
        listingProvider = item.source.provider,
        catalogItem = item,
        neutral = false,
        controlsMode = if (item.kind == dev.veyra.model.MediaKind.EPISODE) PlaybackControlsMode.PODCAST else PlaybackControlsMode.MUSIC,
        requestedTitle = binding?.queryTitle,
        resolvedTitle = binding?.firstResultTitle,
        provenance = binding?.provenanceView(),
    )
}

private fun PlaybackSelection.requestedTitle(): String? =
    request?.completeTitle ?: (receipt as? FirstResultReceipt.Schema2)?.queryTitle

private fun PlaybackSelection.resolvedTitle(): String? = when (val value = receipt) {
    is FirstResultReceipt.Observed -> value.observation.entry.title
    is FirstResultReceipt.Schema2 -> value.firstResultTitle
    null -> null
}

/** Exact current/incoming receipt identity; absent receipts are not replaced by another row. */
internal data class PlaybackProvenanceView(
    val key: String,
    val requestedTitle: String?,
    val resolvedTitle: String,
    val selectionOrdinal: Int,
    val resultKind: FirstResultKind,
    val selectedVideoId: String?,
    val neutral: Boolean,
)

internal fun PlaybackSelection.provenanceView(): PlaybackProvenanceView? = when (val value = receipt) {
    is FirstResultReceipt.Observed -> {
        val entry = value.observation.entry
        val resolved = entry.title ?: return null
        PlaybackProvenanceView(
            key = value.id.value,
            requestedTitle = request?.completeTitle ?: value.observation.queryTitle,
            resolvedTitle = resolved,
            selectionOrdinal = entry.ordinal,
            resultKind = entry.kind.resultKind(),
            selectedVideoId = entry.id?.takeIf { entry.kind == AcquisitionEntryKind.VIDEO },
            neutral = target is PlaybackTarget.NeutralRecording,
        )
    }
    is FirstResultReceipt.Schema2 -> PlaybackProvenanceView(
        key = value.id.value,
        requestedTitle = value.queryTitle,
        resolvedTitle = value.firstResultTitle,
        selectionOrdinal = value.selectionOrdinal,
        resultKind = value.firstResultKind,
        selectedVideoId = value.selectedVideoId,
        neutral = false,
    )
    null -> null
}

internal fun PlaybackBinding.provenanceView(): PlaybackProvenanceView = PlaybackProvenanceView(
    key = id,
    requestedTitle = queryTitle,
    resolvedTitle = firstResultTitle,
    selectionOrdinal = selectionOrdinal,
    resultKind = firstResultKind,
    selectedVideoId = selectedVideoId,
    neutral = false,
)

private fun AcquisitionEntryKind?.resultKind(): FirstResultKind = when (this) {
    AcquisitionEntryKind.VIDEO -> FirstResultKind.VIDEO
    AcquisitionEntryKind.CHANNEL -> FirstResultKind.CHANNEL
    AcquisitionEntryKind.PLAYLIST -> FirstResultKind.PLAYLIST
    null -> FirstResultKind.UNKNOWN
}

internal enum class IncomingIdentity { REQUESTED, OBSERVED_MEMBER, EXECUTED_RESULT }

internal data class IncomingCrossfadeView(
    val title: String?,
    val identity: IncomingIdentity,
    val provenance: PlaybackProvenanceView?,
)

/** The incoming deck's exact owner selection; legacy item/binding is a labelled fallback only. */
internal fun CrossfadeState.incomingView(): IncomingCrossfadeView? {
    incomingSelection?.let { selection ->
        selection.executed?.title?.let {
            return IncomingCrossfadeView(it, IncomingIdentity.EXECUTED_RESULT, selection.provenanceView())
        }
        selection.observedMember?.title?.let {
            return IncomingCrossfadeView(it, IncomingIdentity.OBSERVED_MEMBER, selection.provenanceView())
        }
        selection.requested?.title?.let {
            return IncomingCrossfadeView(it, IncomingIdentity.REQUESTED, selection.provenanceView())
        }
        return IncomingCrossfadeView(null, IncomingIdentity.REQUESTED, selection.provenanceView())
    }
    val item = incomingItem ?: return null
    val binding = incomingExecutionBinding?.takeIf { it.mediaId == item.id }
    return IncomingCrossfadeView(item.title, IncomingIdentity.REQUESTED, binding?.provenanceView())
}

/** Canonical transport availability; never the legacy cursor when canonical queues exist. */
internal fun PlaybackState.hasNextOccurrence(snapshot: AppSnapshot): Boolean {
    if (executionQueues.isNotEmpty()) {
        val queue = executionQueues[activeContext] ?: return false
        val index = queue.currentOccurrenceId?.let { queue.displayedOrder.indexOf(it) } ?: -1
        return index >= 0 && (index < queue.displayedOrder.lastIndex ||
            queue.repeat == RepeatMode.ALL && queue.displayedOrder.isNotEmpty())
    }
    val queue = queues[activeContext] ?: snapshot.queue(activeContext)
    return queue.currentIndex < queue.entries.lastIndex || queue.repeat == RepeatMode.ALL && queue.entries.isNotEmpty()
}

/**
 * Used by playlist replacement confirmation. Canonical pending work is authoritative; only a
 * state with no canonical queues at all uses the legacy snapshot queue.
 */
internal fun PlaybackState.hasUpcomingOccurrence(context: QueueContext, snapshot: AppSnapshot): Boolean {
    if (executionQueues.isNotEmpty()) {
        val queue = executionQueues[context] ?: return false
        return pendingOccurrenceIds(queue.displayedOrder.map { it.value }, queue.currentOccurrenceId?.value).isNotEmpty()
    }
    val queue = snapshot.queue(context)
    return pendingOccurrenceIds(queue.entries.map { it.id }, queue.currentEntryId).isNotEmpty()
}

internal data class ActiveQueueFacts(val shuffle: Boolean, val repeat: RepeatMode)

internal fun PlaybackState.activeQueueFacts(snapshot: AppSnapshot): ActiveQueueFacts {
    if (executionQueues.isNotEmpty()) {
        val queue = executionQueues[activeContext] ?: return ActiveQueueFacts(false, RepeatMode.OFF)
        return ActiveQueueFacts(queue.shuffle, queue.repeat)
    }
    val queue = queues[activeContext] ?: snapshot.queue(activeContext)
    return ActiveQueueFacts(queue.shuffle, queue.repeat)
}
