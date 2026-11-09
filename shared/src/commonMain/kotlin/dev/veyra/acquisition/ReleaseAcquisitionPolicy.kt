package dev.veyra.acquisition

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import dev.veyra.source.release.ReleaseMembershipStatus
import dev.veyra.source.release.ReleaseMetadataField
import dev.veyra.source.release.ReleaseObservation
import dev.veyra.source.release.ReleaseProblemCode
import dev.veyra.source.release.ReleaseTrackObservation

/**
 * Genuine catalogue admission only. MusicBrainz membership never supplies the members of a
 * returned YouTube collection. Parent selection uses the unchanged edition title; individually
 * selected catalogue tracks are separate leaf requests, addressed by their source occurrences.
 */
object ReleaseAcquisitionPolicy {
    /** Unknown/incomplete track metadata does not prevent a truthful parent-title request. */
    fun parentTitle(observation: ReleaseObservation): String {
        if (!MBID.matches(observation.requestedReleaseMbid) || !MBID.matches(observation.observedReleaseMbid)) {
            reject("release_edition_identity_invalid")
        }
        if (!observation.requestedReleaseMbid.equals(observation.observedReleaseMbid, ignoreCase = true)) {
            // A new explicitly selected edition requires its own observation, not a silent swap.
            reject("release_edition_changed")
        }
        return AcquisitionInputRules.completeTitle(observation.completeTitle)
    }

    /**
     * Array ordinals identify occurrences; printed number, declared position, track MBID and
     * recording MBID do not. Missing IDs remain unknown. Missing track titles are never repaired
     * from recordingTitle, and another title-bearing occurrence cannot replace the selected one.
     */
    fun catalogTrack(
        observation: ReleaseObservation,
        mediumArrayOrdinal: Int,
        trackArrayOrdinal: Int,
    ): ReleaseTrackObservation {
        parentTitle(observation)
        if (mediumArrayOrdinal < 0 || trackArrayOrdinal < 0) reject("release_track_occurrence_missing")
        val medium = observation.metadata.media.singleOrNull { it.sourceArrayOrdinal == mediumArrayOrdinal }
            ?: reject("release_medium_occurrence_missing_or_ambiguous")
        val track = medium.tracks.singleOrNull { it.sourceArrayOrdinal == trackArrayOrdinal }
            ?: reject("release_track_occurrence_missing_or_ambiguous")
        requireTrackTitle(track)
        return track
    }

    /**
     * Guard for an explicitly named catalogue-leaf batch, NOT for a parent search outcome or
     * source-listing "All". An incomplete observation can still admit explicit valid tracks.
     */
    fun requireWholeCatalogMembership(observation: ReleaseObservation) {
        parentTitle(observation)
        if (observation.metadata.membershipStatus != ReleaseMembershipStatus.OBSERVED_COMPLETE ||
            observation.metadata.media.any { it.membershipStatus != ReleaseMembershipStatus.OBSERVED_COMPLETE }) {
            reject("release_catalog_membership_incomplete")
        }
        val mediumOrdinals = observation.metadata.media.map { it.sourceArrayOrdinal }
        if (mediumOrdinals.any { it < 0 } || mediumOrdinals.distinct().size != mediumOrdinals.size) {
            reject("release_medium_occurrence_missing_or_ambiguous")
        }
        observation.metadata.media.forEach { medium ->
            val trackOrdinals = medium.tracks.map { it.sourceArrayOrdinal }
            if (trackOrdinals.any { it < 0 } || trackOrdinals.distinct().size != trackOrdinals.size) {
                reject("release_track_occurrence_missing_or_ambiguous")
            }
            medium.tracks.forEach { track ->
                requireTrackTitle(track)
            }
        }
    }

    /** Detach every supplied relation before a request captures it; no new fields are inferred. */
    fun freeze(observation: ReleaseObservation): ReleaseObservation {
        parentTitle(observation)
        val core = observation.metadata
        val release = core.release
        return observation.copy(metadata = core.copy(
            release = release.copy(
                releaseGroup = release.releaseGroup?.let { it.copy(secondaryTypes = it.secondaryTypes.toList()) },
                artistCredits = release.artistCredits.toList(),
                releaseEvents = release.releaseEvents.map { it.copy(countryCodes = it.countryCodes.toList()) },
                labels = release.labels.toList(),
                problems = release.problems.toList(),
            ),
            media = core.media.map { medium ->
                medium.copy(
                    tracks = medium.tracks.map { track ->
                        track.copy(artistCredits = track.artistCredits.toList(), problems = track.problems.toList())
                    },
                    problems = medium.problems.toList(),
                )
            },
            problems = core.problems.toList(),
        ))
    }

    private val MBID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private fun requireTrackTitle(track: ReleaseTrackObservation) {
        if (track.problems.any { it.field == ReleaseMetadataField.TRACK_TITLE &&
                it.code in setOf(ReleaseProblemCode.MISSING_TITLE, ReleaseProblemCode.INVALID_TITLE) }) {
            reject("release_track_title_unavailable")
        }
        AcquisitionInputRules.completeTitle(track.completeTrackTitle ?: reject("release_track_title_unavailable"))
    }
    private fun reject(detail: String): Nothing = throw VeyraException(AppFailure(FailureCode.INVALID_STATE, detail))
}
