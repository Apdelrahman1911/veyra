package dev.veyra.acquisition

import dev.veyra.model.*
import kotlin.test.*

class AcquisitionCaptureFactoryTest {
    private val factory = AcquisitionCaptureFactory(AcquisitionTestClock())

    @Test fun mediaCaptureDetachesOrderedArtistIdsAndUnknownCreditsWithoutTrimmingTheTitle() {
        val artists = mutableListOf("artist-known", "artist-unknown")
        val media = authoredMedia().copy(artistIds = artists)
        val catalog = AppSnapshot(revision = 7, media = mapOf(media.id to media),
            artists = mapOf("artist-known" to Artist("artist-known", "Complete artist name")))
        val request = factory.media(media, catalog)
        artists.clear()
        val captured = assertIs<MediaCapture>(request.metadata)
        assertEquals(listOf("artist-known", "artist-unknown"), captured.media.artistIds)
        assertEquals(listOf("artist-known", "artist-unknown"), captured.artists.map { it.artistId })
        assertNull(captured.artists.last().metadata)
        assertEquals(media.title, request.completeTitle)
        assertEquals(7L, request.catalogRevision)
    }

    @Test fun albumCaptureKeepsDuplicatePositionsAndExplicitMissingCatalogueRows() {
        val tracks = mutableListOf("track", "missing", "track")
        val track = authoredMedia("track").copy(discNumber = 2, trackNumber = 7)
        val album = Album("album", "  Complete album edition — ألبوم  ", trackIds = tracks)
        val request = factory.album(album, AppSnapshot(revision = 9, media = mapOf(track.id to track)))
        tracks.reverse(); tracks.clear()
        val captured = assertIs<AlbumCapture>(request.metadata)
        assertEquals(listOf("track", "missing", "track"), captured.album.trackIds)
        assertEquals(listOf(0, 1, 2), captured.members.map { it.position })
        assertNull(captured.members[1].media)
        assertEquals(captured.members[0].media, captured.members[2].media)
        assertEquals(2, captured.members[2].discNumber)
        assertEquals(7, captured.members[2].trackNumber)
    }

    @Test fun showCaptureFreezesAliasesAndKnownEpisodesWithoutInventingEpisodeMetadata() {
        val ids = mutableListOf("rss-guid-one", "rss-guid-two")
        val aliases = mutableSetOf("https://publisher.example/old.xml")
        val show = PodcastShow("show", "  Genuine show title  ", "https://publisher.example/feed.xml",
            episodeIds = ids, feedAliases = aliases)
        val capture = assertIs<ShowCapture>(factory.show(show).metadata)
        ids.clear(); aliases.clear()
        assertEquals(listOf("rss-guid-one", "rss-guid-two"), capture.show.episodeIds)
        assertEquals(setOf("https://publisher.example/old.xml"), capture.show.feedAliases)
        assertEquals(show.title, capture.completeTitle)
    }

    @Test fun memberCaptureUsesTheRawSourceOrdinalAndKeepsBDistinctFromItsFutureRecording() {
        val snapshot = authoredSnapshot(holes = setOf(1))
        val request = factory.member(snapshot, 2)
        val captured = assertIs<CollectionMemberCapture>(request.metadata)
        assertEquals(snapshot.id, captured.snapshotId)
        assertEquals(2, captured.member.reference.ordinal)
        assertEquals("ABCDEFGHIJK", captured.member.entry.id)
        assertEquals(snapshot.members[2].entry.title, request.completeTitle)
        assertFailsWith<VeyraException> { factory.member(snapshot, 1) }
        assertFailsWith<VeyraException> { factory.member(snapshot, 3) }
    }

    @Test fun corruptDisplayOrForeignSnapshotReferenceIsNotAnEligibleMemberCapture() {
        val snapshot = authoredSnapshot(count = 1)
        val member = snapshot.members.single()
        for (invalid in listOf(
            member.copy(display = member.display!!.copy(title = "Different B")),
            member.copy(display = member.display!!.copy(publicUrl = "https://www.youtube.com/watch?v=LMNOPQRSTUV")),
            member.copy(reference = member.reference.copy(observationId = CollectionObservationId("different-observation"))),
            member.copy(entry = member.entry.copy(ordinal = 1)),
        )) assertFailsWith<VeyraException> { factory.member(snapshot.copy(members = listOf(invalid)), 0) }
    }

    @Test fun explicitResolveAgainGetsANewIdButTheSameFrozenTitleMetadataAndRevisions() {
        val old = factory.capture(MediaCapture(authoredMedia()), 7, "provider-observation-9")
        val again = factory.resolveAgain(old)
        assertNotEquals(old.id, again.id)
        assertEquals(old.completeTitle, again.completeTitle)
        assertEquals(old.metadata, again.metadata)
        assertEquals(old.catalogRevision, again.catalogRevision)
        assertEquals(old.providerRevision, again.providerRevision)
    }

    @Test fun anInvalidProviderRevisionIsRejectedBeforeItCanBecomeADurableRequest() {
        assertEquals("acquisition_provider_revision_invalid", assertFailsWith<VeyraException> {
            factory.capture(MediaCapture(authoredMedia()), providerRevision = "unsafe\nrevision")
        }.failure.detail)
    }
}
