package dev.veyra.data

import dev.veyra.data.room.AcquisitionGraph
import dev.veyra.data.room.CatalogLibrarySavedFrame
import dev.veyra.model.AppSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A committed metadata edit. A no-op or media-only correction has no saved-library Undo. */
class CatalogLibraryEdit internal constructor(val snapshot: AppSnapshot, val undo: CatalogLibraryUndo?)

/** Process-local, same-owner saved-row receipt. Neither callers nor UI reconstruct lineage. */
class CatalogLibraryUndo internal constructor(
    internal val issuer: Any,
    internal val availability: CatalogLibraryUndoAvailability,
    internal val before: CatalogLibrarySavedFrame,
    internal val after: CatalogLibrarySavedFrame,
)

/**
 * An advisory UI hint, NOT transaction authority. Even an accepted receipt must be checked
 * against the actual database revision and complete saved frame inside the write transaction.
 */
class CatalogLibraryUndoAvailability internal constructor(private val issuer: Any) {
    fun accepts(receipt: CatalogLibraryUndo): Boolean =
        receipt.issuer === issuer && receipt.availability === this
}

/**
 * Access only while LibraryStore's existing mutex is held. Known local commits provide their
 * actual before graph; a conflated observer/read is never evidence of intervening continuity.
 */
internal class CatalogLibraryUndoOwner {
    private val issuer = Any()
    private data class Witness(val revision: Long, val frame: CatalogLibrarySavedFrame)
    private var witness: Witness? = null
    private val mutableAvailability = MutableStateFlow(CatalogLibraryUndoAvailability(issuer))
    val availability: StateFlow<CatalogLibraryUndoAvailability> = mutableAvailability.asStateFlow()

    fun publish(graph: AcquisitionGraph, committedFrom: AcquisitionGraph? = null, freshIncarnation: Boolean = false) {
        val frame = CatalogLibrarySavedFrame.of(graph)
        val known = witness
        val continuous = !freshIncarnation && known != null && if (committedFrom == null) {
            known.revision == graph.revision && known.frame == frame
        } else {
            // A hidden external A->B->A still advances revision before our next local commit.
            known.revision == committedFrom.revision && known.frame == CatalogLibrarySavedFrame.of(committedFrom) &&
                known.frame == frame
        }
        if (!continuous) mutableAvailability.value = CatalogLibraryUndoAvailability(issuer)
        witness = Witness(graph.revision, frame)
    }

    /** Called after positive COMMIT/readback and graph publication, before releasing the mutex. */
    fun receipt(before: CatalogLibrarySavedFrame, graph: AcquisitionGraph): CatalogLibraryUndo? {
        val known = checkNotNull(witness)
        check(known.revision == graph.revision && known.frame == CatalogLibrarySavedFrame.of(graph))
        return if (before == known.frame) null else
            CatalogLibraryUndo(issuer, mutableAvailability.value, before, known.frame)
    }

    /** Only the actual IMMEDIATE transaction's before-image may authorize a restoration. */
    fun restore(receipt: CatalogLibraryUndo, graph: AcquisitionGraph): AcquisitionGraph {
        require(mutableAvailability.value.accepts(receipt)) { "Stale or foreign saved-library Undo" }
        val known = requireNotNull(witness)
        val frame = CatalogLibrarySavedFrame.of(graph)
        require(graph.revision == known.revision && frame == known.frame && frame == receipt.after) {
            "Saved-library Undo encountered an unwitnessed revision or changed lineage"
        }
        require(receipt.before != receipt.after) { "A no-op cannot issue saved-library Undo" }
        return receipt.before.restore(graph)
    }

    /** Closing/recovery is an ownership-incarnation boundary, even for identical restored bytes. */
    fun invalidate() {
        witness = null
        mutableAvailability.value = CatalogLibraryUndoAvailability(issuer)
    }
}
