package dev.veyra.data.room

import dev.veyra.acquisition.AcquisitionCaptureFactory
import dev.veyra.acquisition.AcquisitionInputRules
import dev.veyra.model.*

/** Historical metadata is reconstructed only from its captured rows, never today's catalogue. */
internal fun AcquisitionRows.capturedRequests(
    snapshots: Map<CollectionSnapshotId, CollectionSnapshot>,
): Map<AcquisitionRequestId, AcquisitionRequest> {
    val requests = acquisitionRequestRows.rowIndex { it.id }
    val media = requestMediaMetadataRows.rowIndex { it.requestId }
    val albums = requestAlbumMetadataRows.rowIndex { it.requestId }
    val shows = requestShowMetadataRows.rowIndex { it.requestId }
    val sources = requestSourceCollectionMetadataRows.rowIndex { it.requestId }
    val releaseRequests = requestReleaseMetadataRows.rowIndex { it.requestId }
    val members = requestCollectionMemberMetadataRows.rowIndex { it.requestId }
    val releaseTracks = requestMediaReleaseOccurrenceRows.rowIndex { it.requestId }
    val releases = releases.toCapturedReleaseObservations()
    fun expectKind(ids: Set<String>, kind: AcquisitionKind) {
        require(ids == requests.values.filter { it.kind == kind }.map { it.id }.toSet()) {
            "Captured metadata variant mismatch"
        }
    }
    expectKind(media.keys, AcquisitionKind.MEDIA)
    expectKind(albums.keys, AcquisitionKind.ALBUM)
    expectKind(shows.keys, AcquisitionKind.SHOW)
    expectKind(sources.keys, AcquisitionKind.SOURCE_COLLECTION)
    expectKind(releaseRequests.keys, AcquisitionKind.RELEASE)
    expectKind(members.keys, AcquisitionKind.COLLECTION_MEMBER)
    require(releaseTracks.keys.all { it in media }) { "Orphan release track capture" }
    requestMediaArtistRows.rowIndex { it.requestId to it.position }
    requestAlbumArtistRows.rowIndex { it.requestId to it.position }
    requestAlbumMemberRows.rowIndex { it.requestId to it.position }
    requestAlbumMemberArtistRows.rowIndex { Triple(it.requestId, it.memberPosition, it.artistPosition) }
    requestShowAliasRows.rowIndex { it.requestId to it.feedUrl }
    requestShowMemberRows.rowIndex { it.requestId to it.position }
    require(requestMediaArtistRows.all { it.requestId in media })
    require(requestAlbumArtistRows.all { it.requestId in albums })
    require(requestAlbumMemberRows.all { it.requestId in albums })
    val albumMemberIndex = requestAlbumMemberRows.associateBy { it.requestId to it.position }
    require(requestAlbumMemberArtistRows.all { albumMemberIndex[it.requestId to it.memberPosition]?.media != null })
    require(requestShowAliasRows.all { it.requestId in shows })
    require(requestShowMemberRows.all { it.requestId in shows })
    requireRowPositions(requestMediaArtistRows, { it.requestId }, { it.position })
    requireRowPositions(requestAlbumArtistRows, { it.requestId }, { it.position })
    requireRowPositions(requestAlbumMemberRows, { it.requestId }, { it.position })
    requireRowPositions(requestAlbumMemberArtistRows, { it.requestId to it.memberPosition }, { it.artistPosition })
    requireRowPositions(requestShowMemberRows, { it.requestId }, { it.position })
    val mediaCredits = requestMediaArtistRows.groupBy { it.requestId }
    val albumCredits = requestAlbumArtistRows.groupBy { it.requestId }
    val albumMembers = requestAlbumMemberRows.groupBy { it.requestId }
    val albumMemberArtists = requestAlbumMemberArtistRows.groupBy { it.requestId to it.memberPosition }
    val aliases = requestShowAliasRows.groupBy { it.requestId }
    val episodes = requestShowMemberRows.groupBy { it.requestId }
    return requests.values.associate { row ->
        val metadata: RequestedMetadata = when (row.kind) {
            AcquisitionKind.MEDIA -> {
                val credits = mediaCredits[row.id].orEmpty().sortedBy { it.position }
                    .map { CapturedArtistCredit(it.artistId, it.metadata?.toArtist()) }
                MediaCapture(media.getValue(row.id).media.toMedia(credits.map { it.artistId }), credits,
                    releaseTracks[row.id]?.let {
                        ReleaseTrackCapture(releases.getValue(ReleaseObservationId(it.observationId)),
                            it.mediumArrayOrdinal, it.trackArrayOrdinal)
                    })
            }
            AcquisitionKind.ALBUM -> {
                val credits = albumCredits[row.id].orEmpty().sortedBy { it.position }
                    .map { CapturedArtistCredit(it.artistId, it.metadata?.toArtist()) }
                val capturedMembers = albumMembers[row.id].orEmpty().sortedBy { it.position }.map {
                    AlbumMemberCapture(it.position, it.mediaId, it.media?.toMedia(
                        albumMemberArtists[row.id to it.position].orEmpty().sortedBy { it.artistPosition }.map { it.artistId },
                    ), it.discNumber, it.trackNumber)
                }
                AlbumCapture(albums.getValue(row.id).album.toAlbum(credits.map { it.artistId },
                    capturedMembers.map { it.mediaId }), credits, capturedMembers)
            }
            AcquisitionKind.SHOW -> ShowCapture(shows.getValue(row.id).show.toShow(
                episodes[row.id].orEmpty().sortedBy { it.position }.map { it.episodeId },
                aliases[row.id].orEmpty().map { it.feedUrl }.toSet(),
            ))
            AcquisitionKind.RELEASE -> ReleaseCapture(releases.getValue(
                ReleaseObservationId(releaseRequests.getValue(row.id).observationId),
            ))
            AcquisitionKind.SOURCE_COLLECTION -> sources.getValue(row.id).let {
                SourceCollectionCapture(SourceCollectionMetadata(it.provider, it.collectionId, it.completeTitle,
                    it.publicUrl, it.declaredKind, it.declaredScope, it.creator, it.artworkUrl,
                    it.description, it.observationProvenance))
            }
            AcquisitionKind.COLLECTION_MEMBER -> members.getValue(row.id).let {
                val snapshot = snapshots.getValue(CollectionSnapshotId(it.snapshotId))
                val member = snapshot.members.single { member -> member.reference.ordinal == it.ordinal }
                CollectionMemberCapture(snapshot.id, member)
            }
        }
        AcquisitionInputRules.completeTitle(row.completeTitle)
        AcquisitionInputRules.providerRevision(row.providerRevision)
        val detached = AcquisitionCaptureFactory().freeze(metadata)
        val request = AcquisitionRequest(AcquisitionRequestId(row.id), row.completeTitle, detached,
            row.capturedEpochMs, row.catalogRevision, row.providerRevision)
        request.id to request
    }
}

/** Adds only an exact capture, preserving all previously stored relations and missing values. */
internal fun AcquisitionRows.withCapturedRequest(request: AcquisitionRequest): AcquisitionRows {
    AcquisitionInputRules.completeTitle(request.completeTitle)
    AcquisitionInputRules.providerRevision(request.providerRevision)
    val metadata = AcquisitionCaptureFactory().freeze(request.metadata)
    var next = copy(acquisitionRequestRows = acquisitionRequestRows.withImmutableRow(AcquisitionRequestEntity(
        request.id.value, request.kind, request.completeTitle, request.capturedEpochMs, request.catalogRevision,
        request.providerRevision,
    )) { it.id })
    val id = request.id.value
    when (metadata) {
        is MediaCapture -> {
            next = next.copy(requestMediaMetadataRows = next.requestMediaMetadataRows.withImmutableRow(
                RequestMediaMetadataEntity(id, metadata.media.toCapturedColumns()),
            ) { it.requestId })
            metadata.artists.forEachIndexed { position, credit ->
                next = next.copy(requestMediaArtistRows = next.requestMediaArtistRows.withImmutableRow(
                    RequestMediaArtistEntity(id, position, credit.artistId, credit.metadata?.toCapturedColumns()),
                ) { it.requestId to it.position })
            }
            metadata.releaseTrack?.let { reference ->
                next = next.withCapturedRelease(reference.observation).let {
                    it.copy(requestMediaReleaseOccurrenceRows = it.requestMediaReleaseOccurrenceRows.withImmutableRow(
                        RequestMediaReleaseOccurrenceEntity(id, reference.observation.id.value,
                            reference.mediumSourceArrayOrdinal, reference.trackSourceArrayOrdinal),
                    ) { it.requestId })
                }
            }
        }
        is AlbumCapture -> {
            next = next.copy(requestAlbumMetadataRows = next.requestAlbumMetadataRows.withImmutableRow(
                RequestAlbumMetadataEntity(id, metadata.album.toCapturedColumns()),
            ) { it.requestId })
            metadata.artists.forEachIndexed { position, credit ->
                next = next.copy(requestAlbumArtistRows = next.requestAlbumArtistRows.withImmutableRow(
                    RequestAlbumArtistEntity(id, position, credit.artistId, credit.metadata?.toCapturedColumns()),
                ) { it.requestId to it.position })
            }
            metadata.members.forEach { member ->
                next = next.copy(requestAlbumMemberRows = next.requestAlbumMemberRows.withImmutableRow(
                    RequestAlbumMemberEntity(id, member.position, member.mediaId, member.discNumber, member.trackNumber,
                        member.media?.toCapturedColumns()),
                ) { it.requestId to it.position })
                member.media?.artistIds?.forEachIndexed { position, artist ->
                    next = next.copy(requestAlbumMemberArtistRows = next.requestAlbumMemberArtistRows.withImmutableRow(
                        RequestAlbumMemberArtistEntity(id, member.position, position, artist),
                    ) { Triple(it.requestId, it.memberPosition, it.artistPosition) })
                }
            }
        }
        is ShowCapture -> {
            next = next.copy(requestShowMetadataRows = next.requestShowMetadataRows.withImmutableRow(
                RequestShowMetadataEntity(id, metadata.show.toCapturedColumns()),
            ) { it.requestId })
            metadata.show.feedAliases.forEach { alias ->
                next = next.copy(requestShowAliasRows = next.requestShowAliasRows.withImmutableRow(
                    RequestShowAliasEntity(id, alias),
                ) { it.requestId to it.feedUrl })
            }
            metadata.show.episodeIds.forEachIndexed { position, episode ->
                next = next.copy(requestShowMemberRows = next.requestShowMemberRows.withImmutableRow(
                    RequestShowMemberEntity(id, position, episode),
                ) { it.requestId to it.position })
            }
        }
        is ReleaseCapture -> next = next.withCapturedRelease(metadata.observation).let {
            it.copy(requestReleaseMetadataRows = it.requestReleaseMetadataRows.withImmutableRow(
                RequestReleaseMetadataEntity(id, metadata.observation.id.value),
            ) { it.requestId })
        }
        is SourceCollectionCapture -> metadata.collection.let {
            next = next.copy(requestSourceCollectionMetadataRows = next.requestSourceCollectionMetadataRows.withImmutableRow(
                RequestSourceCollectionMetadataEntity(id, it.provider, it.id, it.completeTitle, it.publicUrl,
                    it.declaredKind, it.declaredScope, it.creator, it.artworkUrl, it.description, it.observationProvenance),
            ) { it.requestId })
        }
        is CollectionMemberCapture -> next = next.copy(
            requestCollectionMemberMetadataRows = next.requestCollectionMemberMetadataRows.withImmutableRow(
                RequestCollectionMemberMetadataEntity(id, metadata.snapshotId.value, metadata.member.reference.ordinal),
            ) { it.requestId },
        )
    }
    return next
}

internal fun AcquisitionRows.withCapturedRelease(capture: CapturedReleaseObservation): AcquisitionRows = copy(
    releases = (releases.toCapturedReleaseObservations().values + capture).toReleaseObservationRows(),
)

private fun Artist.toCapturedColumns() = CapturedArtistColumns(id, name, sortName, description, artworkUrl, source)
private fun CapturedArtistColumns.toArtist() = Artist(id, name, sortName, description, artworkUrl, source)
private fun Album.toCapturedColumns() = CapturedAlbumColumns(id, title, artworkUrl, description, releaseYear, source)
private fun CapturedAlbumColumns.toAlbum(artists: List<String>, members: List<String>) =
    Album(id, title, artists, members, artworkUrl, description, releaseYear, source)

private fun MediaItem.toCapturedColumns() = CapturedMediaColumns(id, title, kind, source, creator, artworkUrl,
    durationMs, showId, description, publishedEpochMs, explicit, album, artist, episodeNumber, seasonNumber,
    albumId, trackNumber, discNumber, localArtworkPath, attribution)
private fun CapturedMediaColumns.toMedia(artists: List<String>) = MediaItem(
    id = id, title = title, kind = kind, source = source, creator = creator, artworkUrl = artworkUrl,
    durationMs = advisoryDurationMs, showId = showId, description = description, publishedEpochMs = publishedEpochMs,
    explicit = explicit, album = albumLabel, artist = artistLabel, episodeNumber = episodeNumber,
    seasonNumber = seasonNumber, attribution = attribution, artistIds = artists, albumId = albumId,
    trackNumber = trackNumber, discNumber = discNumber, localArtworkPath = localArtworkPath,
)

private fun PodcastShow.toCapturedColumns() = CapturedShowColumns(id, title, feedUrl, publisher, description,
    artworkUrl, websiteUrl, language, explicit, etag, lastModified, lastRefreshEpochMs, copyright, localArtworkPath, attribution)
private fun CapturedShowColumns.toShow(episodes: List<String>, aliases: Set<String>) = PodcastShow(
    id, title, feedUrl, publisher, description, artworkUrl, websiteUrl, language, explicit, episodes, aliases,
    etag, lastModified, lastRefreshEpochMs, copyright, attribution, localArtworkPath,
)
