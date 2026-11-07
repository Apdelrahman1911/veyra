package dev.veyra.acquisition

import dev.veyra.model.*
import dev.veyra.playback.Clock
import dev.veyra.playback.SystemClock

/**
 * Constructs detached immutable request inputs before source admission. These are real domain
 * values consumed by AcquisitionRepository, not an alternate store or a remote-selection cache.
 * A caller must still use the repository's expected-revision/occurrence transaction at admission.
 */
class AcquisitionCaptureFactory(
    private val clock: Clock = SystemClock(),
    private val newRequestId: () -> AcquisitionRequestId = { AcquisitionRequestId(newEntityId("acquisition")) },
) {
    fun capture(
        metadata: RequestedMetadata,
        catalogRevision: Long? = null,
        providerRevision: String? = null,
    ): AcquisitionRequest {
        AcquisitionInputRules.providerRevision(providerRevision)
        val frozen = freeze(metadata)
        return AcquisitionRequest(newRequestId(), AcquisitionInputRules.completeTitle(frozen.completeTitle),
            frozen, clock.epochMs(), catalogRevision, providerRevision)
    }

    fun media(item: MediaItem, catalog: AppSnapshot): AcquisitionRequest = capture(
        MediaCapture(item, item.artistIds.map { CapturedArtistCredit(it, catalog.artists[it]) }),
        catalogRevision = catalog.revision,
    )

    fun album(album: Album, catalog: AppSnapshot): AcquisitionRequest = capture(
        AlbumCapture(
            album,
            album.artistIds.map { CapturedArtistCredit(it, catalog.artists[it]) },
            album.trackIds.mapIndexed { position, mediaId ->
                val item = catalog.media[mediaId]
                AlbumMemberCapture(position, mediaId, item, item?.discNumber, item?.trackNumber)
            },
        ),
        catalogRevision = catalog.revision,
    )

    fun show(show: PodcastShow, catalogRevision: Long? = null): AcquisitionRequest =
        capture(ShowCapture(show), catalogRevision)

    fun release(observation: CapturedReleaseObservation, catalogRevision: Long? = null): AcquisitionRequest =
        capture(ReleaseCapture(observation), catalogRevision)

    fun releaseTrack(
        item: MediaItem,
        observation: CapturedReleaseObservation,
        mediumArrayOrdinal: Int,
        trackArrayOrdinal: Int,
        artists: List<CapturedArtistCredit> = emptyList(),
        catalogRevision: Long? = null,
    ): AcquisitionRequest = capture(
        MediaCapture(item, artists, ReleaseTrackCapture(observation, mediumArrayOrdinal, trackArrayOrdinal)),
        catalogRevision,
    )

    /** The ordinal is B's source occurrence, never a video ID, filtered list index or catalogue row. */
    fun member(snapshot: CollectionSnapshot, ordinal: Int): AcquisitionRequest {
        val observation = snapshot.observation ?: reject("collection_observation_unverified")
        if (observation.id != snapshot.observationId || observation.parentRequestId != snapshot.parentRequestId) {
            reject("collection_snapshot_lineage_mismatch")
        }
        val member = snapshot.members.singleOrNull { it.reference.ordinal == ordinal }
            ?: reject("collection_member_missing_or_ambiguous")
        if (member.reference.observationId != snapshot.observationId ||
            member.reference.parentRequestId != snapshot.parentRequestId || member.reference.identity != observation.identity ||
            member.reference.scope != observation.scope) reject("collection_member_lineage_mismatch")
        return capture(CollectionMemberCapture(snapshot.id, member))
    }

    /** Explicit resolve-again: new request identity, same frozen metadata, no invisible old-query retry. */
    fun resolveAgain(previous: AcquisitionRequest): AcquisitionRequest =
        capture(previous.metadata, previous.catalogRevision, previous.providerRevision)

    fun freeze(metadata: RequestedMetadata): RequestedMetadata {
        AcquisitionInputRules.completeTitle(metadata.completeTitle)
        return when (metadata) {
            is MediaCapture -> {
                metadata.releaseTrack?.let { reference ->
                    val track = ReleaseAcquisitionPolicy.catalogTrack(reference.observation.value,
                        reference.mediumSourceArrayOrdinal, reference.trackSourceArrayOrdinal)
                    if (metadata.media.kind != MediaKind.MUSIC || track.completeTrackTitle != metadata.media.title) {
                        reject("release_track_capture_mismatch")
                    }
                }
                requireCredits(metadata.media.artistIds, metadata.artists)
                metadata.copy(media = metadata.media.detached(), artists = metadata.artists.toList(),
                    releaseTrack = metadata.releaseTrack?.let { it.copy(observation = it.observation.detached()) })
            }
            is AlbumCapture -> {
                requireCredits(metadata.album.artistIds, metadata.artists)
                if (metadata.members.size != metadata.album.trackIds.size || metadata.members.withIndex().any { (index, member) ->
                        member.position != index || member.mediaId != metadata.album.trackIds[index] ||
                            member.media?.id?.let { it != member.mediaId } == true
                    }) reject("album_capture_membership_mismatch")
                metadata.copy(
                    album = metadata.album.copy(artistIds = metadata.album.artistIds.toList(), trackIds = metadata.album.trackIds.toList()),
                    artists = metadata.artists.toList(),
                    members = metadata.members.map { it.copy(media = it.media?.detached()) },
                )
            }
            is ReleaseCapture -> metadata.copy(observation = metadata.observation.detached())
            is ShowCapture -> metadata.copy(show = metadata.show.copy(
                episodeIds = metadata.show.episodeIds.toList(), feedAliases = metadata.show.feedAliases.toSet(),
            ))
            is SourceCollectionCapture -> metadata.copy(collection = metadata.collection.copy())
            is CollectionMemberCapture -> {
                val member = metadata.member
                val display = member.display ?: reject("collection_member_not_playable")
                if (!member.isVideoCandidate || member.entry.ordinal != member.reference.ordinal ||
                    member.entry.id.isNullOrBlank() || display.id != member.entry.id ||
                    display.title != member.entry.title || display.publicUrl != member.entry.publicUrl ||
                    display.kind != AcquisitionEntryKind.VIDEO) reject("collection_member_not_playable")
                metadata.copy(member = member.copy(reference = member.reference.copy(), entry = member.entry.copy(),
                    display = display.copy()))
            }
        }
    }

    private fun requireCredits(artistIds: List<String>, credits: List<CapturedArtistCredit>) {
        if (credits.map { it.artistId } != artistIds || credits.any { credit -> credit.metadata?.id?.let { it != credit.artistId } == true }) {
            reject("acquisition_artist_capture_mismatch")
        }
    }

    private fun MediaItem.detached() = copy(artistIds = artistIds.toList())
    private fun CapturedReleaseObservation.detached() = copy(value = ReleaseAcquisitionPolicy.freeze(value))
    private fun reject(detail: String): Nothing = throw VeyraException(AppFailure(FailureCode.INVALID_STATE, detail))
}
