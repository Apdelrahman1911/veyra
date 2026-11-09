package dev.veyra.data

import dev.veyra.model.CapturedReleaseObservation
import dev.veyra.model.ReleaseObservationId
import kotlinx.coroutines.flow.StateFlow

/** A mutable catalogue pointer, never a replacement for an already captured request or pin. */
data class ReleaseCatalogEntry(
    val requestedReleaseMbid: String,
    val latestObservationId: ReleaseObservationId,
    val updatedEpochMs: Long,
) {
    override fun toString() = "ReleaseCatalogEntry(identity=redacted)"
}

/** The exact observation explicitly selected by the owner, independently of later refreshes. */
data class SavedRelease(
    val requestedReleaseMbid: String,
    val observationId: ReleaseObservationId,
    val pinnedEpochMs: Long,
) {
    override fun toString() = "SavedRelease(identity=redacted)"
}

/**
 * Detached projection of ONE consistent library revision, not an independent database/cache.
 * Keys retain the producer's exact requested MBID; the MusicBrainz producer supplies lowercase
 * canonical MBIDs. Observations contain only its typed core whitelist and immutable fetch/hash/
 * ETag provenance. Search queries/raw payloads, artwork, audio locators and source plans are not
 * stored here. Fresh/stale display policy never grants permission for an offline network fetch.
 */
data class ReleaseCatalogState(
    val libraryRevision: Long,
    val observations: Map<ReleaseObservationId, CapturedReleaseObservation> = emptyMap(),
    val entries: Map<String, ReleaseCatalogEntry> = emptyMap(),
    val saved: Map<String, SavedRelease> = emptyMap(),
) {
    init { require(libraryRevision >= 0) }
    override fun toString() = "ReleaseCatalogState(revision=$libraryRevision,observations=${observations.size},saved=${saved.size})"
}

/** Selected, latest and pinned metadata all come from the SAME graph/revision. */
data class ReleaseCatalogDetail(
    val libraryRevision: Long,
    val requestedReleaseMbid: String,
    val selected: CapturedReleaseObservation,
    val latestEntry: ReleaseCatalogEntry?,
    val latestObservation: CapturedReleaseObservation?,
    val savedEntry: SavedRelease?,
    val savedObservation: CapturedReleaseObservation?,
) {
    override fun toString() = "ReleaseCatalogDetail(revision=$libraryRevision,identity=redacted)"
}

/**
 * IDs select rows; they are not caller-supplied commit authority. Every command checks the
 * expected global library revision in the existing owner's transaction. A stale revision fails
 * with INVALID_STATE/release_catalog_stale_revision, including otherwise idempotent operations.
 * No command queries MusicBrainz/kmpYT or changes a playback/download/request/receipt relation.
 */
sealed interface ReleaseCatalogCommand {
    val expectedRevision: Long

    /**
     * Atomically insert a NEW immutable observation and make it latest for its requested MBID.
     * The pointer time is the captured fetchedAtEpochMs, never a newly sampled clock. An exact
     * already-known ID is idempotent and does NOT move latest back after another observation.
     * Equal core digests do not collapse distinct observed IDs/fetches or their ETags. The
     * existing saved pin is unchanged. A conflicting value for an existing ID is rejected.
     */
    data class RegisterObservation(
        val observation: CapturedReleaseObservation,
        override val expectedRevision: Long,
    ) : ReleaseCatalogCommand {
        override fun toString() = "ReleaseCatalogCommand.RegisterObservation(revision=$expectedRevision,metadata=redacted)"
    }

    /** Explicit local choice of an already stored observation; not a source refresh. */
    data class SetLatest(
        val requestedReleaseMbid: String,
        val observationId: ReleaseObservationId,
        override val expectedRevision: Long,
        val updatedEpochMs: Long,
    ) : ReleaseCatalogCommand {
        override fun toString() = "ReleaseCatalogCommand.SetLatest(revision=$expectedRevision,identity=redacted)"
    }

    /** Pin the explicit selected ID, never whichever observation happens to be latest. */
    data class Pin(
        val requestedReleaseMbid: String,
        val observationId: ReleaseObservationId,
        override val expectedRevision: Long,
        val pinnedEpochMs: Long,
    ) : ReleaseCatalogCommand {
        override fun toString() = "ReleaseCatalogCommand.Pin(revision=$expectedRevision,identity=redacted)"
    }

    /** Remove only this pin. A different current pin fails; an already absent pin is unchanged. */
    data class Unpin(
        val requestedReleaseMbid: String,
        val expectedObservationId: ReleaseObservationId,
        override val expectedRevision: Long,
    ) : ReleaseCatalogCommand {
        override fun toString() = "ReleaseCatalogCommand.Unpin(revision=$expectedRevision,identity=redacted)"
    }
}

/**
 * Facade on the existing LibraryStore/Room transaction owner. No second DAO, store, source,
 * writer, clock, retry loop or offline fallback belongs behind this interface. Initialization
 * is the same owner's Room-only initialization. Publish state/results only after its complete
 * intended-graph readback and successful commit; do not infer success from a proposed graph.
 */
interface ReleaseCatalogRepository : LibraryTransactionOwned {
    val releaseCatalog: StateFlow<ReleaseCatalogState>

    suspend fun applyReleaseCatalog(command: ReleaseCatalogCommand): ReleaseCatalogState

    /**
     * Local-only consistent read. Null selects latest ONLY and returns null when no latest
     * exists. Saved/history/request callers must pass their exact observation ID. A missing
     * explicit ID or a requested-MBID mismatch fails rather than substituting latest or saved.
     * The returned fetchedAt/ETag/digest remain original; reading does not refresh their age.
     */
    suspend fun releaseDetail(
        requestedReleaseMbid: String,
        selectedObservationId: ReleaseObservationId? = null,
    ): ReleaseCatalogDetail?
}
