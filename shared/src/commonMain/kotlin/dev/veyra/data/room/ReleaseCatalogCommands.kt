package dev.veyra.data.room

import dev.veyra.acquisition.ReleaseAcquisitionPolicy
import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.source.release.MusicBrainzReleaseParser
import dev.veyra.source.release.RELEASE_MBID

/**
 * Pure transaction transform on the EXISTING graph. No owner, DAO, suspension, source, clock
 * or revision publication is created here. The outer owner validates/writes/readbacks the
 * complete graph and supplies that actual readback to the pure result projection. Neither
 * the projection nor its graph may be published before the enclosing commit succeeds.
 */
internal fun AcquisitionGraph.applyReleaseCatalog(command: ReleaseCatalogCommand): AcquisitionMutation<ReleaseCatalogState> {
    if (command.expectedRevision < 0 || command.expectedRevision != revision) releaseCatalogInvalid("release_catalog_stale_revision")
    val before = releaseCatalogState()
    val changed = when (command) {
        is ReleaseCatalogCommand.RegisterObservation -> {
            val captured = command.observation.frozenForRegistration()
            val prior = before.observations[captured.id]
            if (prior != null) {
                if (prior != captured) releaseCatalogInvalid("release_catalog_observation_changed")
                // A late duplicate cannot undo another already-registered/latest observation.
                this
            } else {
                copy(rows = rows.copy(
                    releases = (before.observations.values + captured).toReleaseObservationRows(),
                    releaseCatalogEntryRows = rows.releaseCatalogEntryRows.withRow(ReleaseCatalogEntryEntity(
                        captured.value.requestedReleaseMbid, captured.id.value, captured.value.fetchedAtEpochMs,
                    )) { it.requestedReleaseMbid },
                ))
            }
        }
        is ReleaseCatalogCommand.SetLatest -> {
            before.requireObservation(command.requestedReleaseMbid, command.observationId)
            if (command.updatedEpochMs < 0) releaseCatalogInvalid("release_catalog_invalid_timestamp")
            val current = before.entries[command.requestedReleaseMbid]
            if (current?.latestObservationId == command.observationId) this else copy(rows = rows.copy(
                releaseCatalogEntryRows = rows.releaseCatalogEntryRows.withRow(ReleaseCatalogEntryEntity(
                    command.requestedReleaseMbid, command.observationId.value, command.updatedEpochMs,
                )) { it.requestedReleaseMbid },
            ))
        }
        is ReleaseCatalogCommand.Pin -> {
            before.requireObservation(command.requestedReleaseMbid, command.observationId)
            if (command.pinnedEpochMs < 0) releaseCatalogInvalid("release_catalog_invalid_timestamp")
            val current = before.saved[command.requestedReleaseMbid]
            if (current?.observationId == command.observationId) this else copy(rows = rows.copy(
                savedReleaseRows = rows.savedReleaseRows.withRow(SavedReleaseEntity(
                    command.requestedReleaseMbid, command.observationId.value, command.pinnedEpochMs,
                )) { it.requestedReleaseMbid },
            ))
        }
        is ReleaseCatalogCommand.Unpin -> {
            requireReleaseMbid(command.requestedReleaseMbid)
            val current = before.saved[command.requestedReleaseMbid]
            if (current == null) this else {
                if (current.observationId != command.expectedObservationId) releaseCatalogInvalid("release_catalog_saved_observation_changed")
                copy(rows = rows.copy(savedReleaseRows = rows.savedReleaseRows.filterNot { it.requestedReleaseMbid == command.requestedReleaseMbid }))
            }
        }
    }
    return AcquisitionMutation(changed) { actual -> actual.releaseCatalogState() }
}

/** Reconstruct fresh immutable-value projections from normalized rows, never provider payloads. */
internal fun AcquisitionGraph.releaseCatalogState(): ReleaseCatalogState {
    val observations = rows.releases.toCapturedReleaseObservations()
    val entries = rows.releaseCatalogEntryRows.rowIndex { it.requestedReleaseMbid }.mapValues { (_, row) ->
        requireReleaseMbid(row.requestedReleaseMbid)
        val id = ReleaseObservationId(row.latestObservationId)
        if (observations[id]?.value?.requestedReleaseMbid != row.requestedReleaseMbid || row.updatedEpochMs < 0) {
            releaseCatalogInvalid("release_catalog_pointer_mismatch")
        }
        ReleaseCatalogEntry(row.requestedReleaseMbid, id, row.updatedEpochMs)
    }
    val saved = rows.savedReleaseRows.rowIndex { it.requestedReleaseMbid }.mapValues { (_, row) ->
        requireReleaseMbid(row.requestedReleaseMbid)
        val id = ReleaseObservationId(row.observationId)
        if (observations[id]?.value?.requestedReleaseMbid != row.requestedReleaseMbid || row.pinnedEpochMs < 0) {
            releaseCatalogInvalid("release_catalog_pointer_mismatch")
        }
        SavedRelease(row.requestedReleaseMbid, id, row.pinnedEpochMs)
    }
    return ReleaseCatalogState(revision, observations, entries, saved)
}

/** One graph read supplies selected/latest/pin; missing explicit history can never mean latest. */
internal fun AcquisitionGraph.releaseCatalogDetail(
    requestedReleaseMbid: String,
    selectedObservationId: ReleaseObservationId? = null,
): ReleaseCatalogDetail? {
    requireReleaseMbid(requestedReleaseMbid)
    val state = releaseCatalogState()
    val latest = state.entries[requestedReleaseMbid]
    val saved = state.saved[requestedReleaseMbid]
    val selectedId = selectedObservationId ?: latest?.latestObservationId ?: return null
    val selected = state.requireObservation(requestedReleaseMbid, selectedId)
    return ReleaseCatalogDetail(state.libraryRevision, requestedReleaseMbid, selected,
        latest, latest?.let { state.observations.getValue(it.latestObservationId) },
        saved, saved?.let { state.observations.getValue(it.observationId) })
}

private fun ReleaseCatalogState.requireObservation(mbid: String, id: ReleaseObservationId): CapturedReleaseObservation {
    requireReleaseMbid(mbid)
    val captured = observations[id] ?: releaseCatalogInvalid("release_catalog_observation_missing")
    if (captured.value.requestedReleaseMbid != mbid) releaseCatalogInvalid("release_catalog_edition_mismatch")
    return captured
}

private fun CapturedReleaseObservation.frozenForRegistration(): CapturedReleaseObservation {
    // The typed whitelist is the only input. Its digest is an integrity check, not proof of a
    // live fetch/atomic provider snapshot or a license/acquisition/native-media capability.
    requireReleaseMbid(value.requestedReleaseMbid)
    if (value.fetchedAtEpochMs < 0) releaseCatalogInvalid("release_catalog_invalid_timestamp")
    if (MusicBrainzReleaseParser.safeEtag(value.etag) != value.etag) releaseCatalogInvalid("release_catalog_invalid_etag")
    if (value.metadata.media.size > MusicBrainzReleaseParser.MAX_MEDIA ||
        value.metadata.media.sumOf { it.tracks.size.toLong() } > MusicBrainzReleaseParser.MAX_TRACKS) {
        releaseCatalogInvalid("release_catalog_observation_limit")
    }
    val frozen = copy(value = ReleaseAcquisitionPolicy.freeze(value))
    if (MusicBrainzReleaseParser.digest(frozen.value.metadata) != frozen.value.coreDigest) {
        releaseCatalogInvalid("release_catalog_core_digest_mismatch")
    }
    return frozen
}

private fun requireReleaseMbid(value: String) {
    if (!RELEASE_MBID.matches(value)) releaseCatalogInvalid("release_catalog_invalid_release_id")
}

private fun releaseCatalogInvalid(detail: String): Nothing = throw VeyraException(AppFailure(FailureCode.INVALID_STATE, detail))
