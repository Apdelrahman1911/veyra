package dev.veyra.app

import dev.veyra.model.AppSnapshot
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.Playlist
import dev.veyra.model.PlaylistEntry
import dev.veyra.model.PodcastShow
import dev.veyra.model.QueueContext
import dev.veyra.model.QueueEntry
import dev.veyra.model.QueueSnapshot
import dev.veyra.model.RepeatMode
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.source.BrowseKind
import dev.veyra.source.MediaCollection
import dev.veyra.ui.music.LibrarySection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Pure routing tests only: these are not evidence of Room, Compose, or native playback behavior. */
class AppNavigationTest {
    private val track = MediaItem("track", "Complete requested track title — عنوان كامل", MediaKind.MUSIC,
        SourceRef(SourceProvider.YOUTUBE, "video-id"))
    private val episode = MediaItem("episode", "Complete episode title", MediaKind.EPISODE,
        SourceRef(SourceProvider.RSS, "guid", feedUrl = "https://example.org/feed.xml"), showId = "show")
    private val show = PodcastShow("show", "Show title", "https://example.org/feed.xml", episodeIds = listOf(episode.id))
    private val playlist = Playlist("playlist", "Saved references", listOf(
        PlaylistEntry("playlist-first", track.id), PlaylistEntry("playlist-duplicate", track.id)))
    private val collection = MediaCollection("collection", "Complete collection title — عنوان المجموعة",
        SourceRef(SourceProvider.YOUTUBE, "collection-canonical-id", "https://www.youtube.com/playlist?list=collection-canonical-id"),
        artworkUrl = "https://example.org/artwork.png", creator = "Collection creator", kind = BrowseKind.PLAYLIST)
    private val snapshot = AppSnapshot(
        media = listOf(track, episode).associateBy { it.id },
        shows = mapOf(show.id to show),
        playlists = listOf(playlist),
        activeContext = QueueContext.MUSIC,
        queues = mapOf(
            QueueContext.MUSIC to QueueSnapshot(
                entries = listOf(QueueEntry("music-first", track.id), QueueEntry("music-duplicate", track.id)),
                currentEntryId = "music-first", positionMs = 12_345L, shuffle = true, repeat = RepeatMode.ALL,
                originalOrder = listOf("music-first", "music-duplicate")),
            QueueContext.PODCAST to QueueSnapshot(
                entries = listOf(QueueEntry("podcast-occurrence", episode.id)),
                currentEntryId = "podcast-occurrence", positionMs = 98_765L, speed = 1.5f),
        ),
    )
    private val trackDetail = AppDetail(DetailKind.TRACK, track.id)

    @Test fun backClosesQueueThenPlayerThenDetailBeforeLeavingTheDestination() {
        val start = AppNavigation(destination = AppDestination.LIBRARY, librarySection = LibrarySection.DOWNLOADS,
            details = listOf(AppDetail(DetailKind.PLAYLIST, playlist.id), trackDetail),
            playerOpen = true, queueOpen = true, viewedQueue = QueueContext.PODCAST)

        val noQueue = start.back()
        assertEquals(start.copy(queueOpen = false), noQueue)
        val noPlayer = noQueue.back()
        assertEquals(noQueue.copy(playerOpen = false), noPlayer)
        val playlistOnly = noPlayer.back()
        assertEquals(noPlayer.copy(details = listOf(AppDetail(DetailKind.PLAYLIST, playlist.id))), playlistOnly)
        val library = playlistOnly.back()
        assertEquals(playlistOnly.copy(details = emptyList()), library)
        val listen = library.back()
        assertEquals(library.copy(destination = AppDestination.LISTEN), listen)
        assertFalse(listen.canGoBack)
        assertEquals(LibrarySection.DOWNLOADS, listen.librarySection)
        assertEquals(QueueContext.PODCAST, listen.viewedQueue)
    }

    @Test fun backAtTheListenRootIsANoOp() {
        val navigation = AppNavigation()
        assertFalse(navigation.canGoBack)
        assertSame(navigation, navigation.back())
        assertNull(navigation.detail)
    }

    @Test fun openingADetailClosesOverlaysAndPreservesItsNavigationContext() {
        val start = AppNavigation(destination = AppDestination.SEARCH, librarySection = LibrarySection.PLAYLISTS,
            playerOpen = true, queueOpen = true, viewedQueue = QueueContext.PODCAST)

        assertEquals(start.copy(details = listOf(trackDetail), playerOpen = false, queueOpen = false), start.open(trackDetail))
        assertTrue(start.open(trackDetail).canGoBack)
    }

    @Test fun openingTheTopDetailAgainDoesNotDuplicateItButDoesCloseOverlays() {
        val start = AppNavigation(details = listOf(trackDetail), playerOpen = true, queueOpen = true)
        val opened = start.open(trackDetail)

        assertEquals(listOf(trackDetail), opened.details)
        assertFalse(opened.playerOpen)
        assertFalse(opened.queueOpen)
        assertEquals(opened, opened.open(trackDetail))
    }

    @Test fun theDetailStackKeepsOnlyTheTwelveMostRecentRoutes() {
        val routes = (0 until 17).map { AppDetail(DetailKind.TRACK, "track-$it") }
        val navigation = routes.fold(AppNavigation()) { current, route -> current.open(route) }

        assertEquals(routes.takeLast(12), navigation.details)
        assertEquals(routes.last(), navigation.detail)
    }

    @Test fun navigationRoundTripPreservesAllSupportedDetailKindsAndCollectionMetadata() {
        val details = listOf(
            trackDetail,
            AppDetail(DetailKind.EPISODE, episode.id),
            AppDetail(DetailKind.SHOW, show.id),
            AppDetail(DetailKind.PLAYLIST, playlist.id),
            AppDetail(DetailKind.COLLECTION, collection.id, collection),
            AppDetail(DetailKind.LICENSES, "legal"),
        )
        val navigation = AppNavigation(AppDestination.SETTINGS, LibrarySection.DOWNLOADS,
            details = details, viewedQueue = QueueContext.PODCAST)

        val restored = AppNavigation.restore(snapshot.copy(navigation = navigation.persisted()))

        assertEquals(navigation, restored)
        assertEquals(collection, restored.details.first { it.kind == DetailKind.COLLECTION }.collection)
    }

    @Test fun coldRestorationNeverReopensTransientPlayerOrQueueOverlays() {
        val navigation = AppNavigation(destination = AppDestination.LIBRARY, details = listOf(trackDetail),
            playerOpen = true, queueOpen = true, viewedQueue = QueueContext.PODCAST)
        val persisted = navigation.persisted()
        assertFalse("playerOpen" in persisted)
        assertFalse("queueOpen" in persisted)

        // Unknown keys from an older or future producer must not turn cold launch into an overlay.
        val restored = AppNavigation.restore(snapshot.copy(navigation = persisted + mapOf("playerOpen" to "true", "queueOpen" to "true")))
        assertEquals(navigation.copy(playerOpen = false, queueOpen = false), restored)
    }

    @Test fun missingReferencesAndUnknownDetailKindsAreDroppedWithoutLosingValidOrder() {
        val details = listOf(
            AppDetail(DetailKind.TRACK, "missing-track"),
            trackDetail,
            AppDetail(DetailKind.EPISODE, "missing-episode"),
            AppDetail(DetailKind.SHOW, "missing-show"),
            AppDetail(DetailKind.PLAYLIST, "missing-playlist"),
            AppDetail(DetailKind.SHOW, show.id),
        )
        val values = AppNavigation(details = details).persisted() + mapOf(
            "details" to "9",
            "detail.6.kind" to "UNSUPPORTED_ROUTE", "detail.6.id" to track.id,
            "detail.7.kind" to "TRACK", "detail.7.id" to "   ",
            "detail.8.kind" to "TRACK", // Missing ID.
        )

        val restored = AppNavigation.restore(snapshot.copy(navigation = values))

        assertEquals(listOf(trackDetail, AppDetail(DetailKind.SHOW, show.id)), restored.details)
    }

    @Test fun restoredMediaRoutesMustMatchTheReferencedMediaKind() {
        val details = listOf(
            AppDetail(DetailKind.TRACK, episode.id),
            AppDetail(DetailKind.EPISODE, track.id),
            trackDetail,
            AppDetail(DetailKind.EPISODE, episode.id),
        )

        val restored = AppNavigation.restore(snapshot.copy(navigation = AppNavigation(details = details).persisted()))

        assertEquals(details.takeLast(2), restored.details)
    }

    @Test fun invalidTopLevelValuesUseSafeDefaultsAndTheSavedActiveContext() {
        val restored = AppNavigation.restore(snapshot.copy(activeContext = QueueContext.PODCAST, navigation = mapOf(
            "screen" to "REMOVED_SCREEN", "librarySection" to "REMOVED_SECTION", "viewedQueue" to "MIXED",
            "details" to "not-a-number")))

        assertEquals(AppNavigation(viewedQueue = QueueContext.PODCAST), restored)
        assertEquals(AppNavigation(), AppNavigation.restore(AppSnapshot()))
    }

    @Test fun everyDestinationLibrarySectionAndViewedContextSurvivesRestoration() {
        AppDestination.entries.forEach { destination ->
            LibrarySection.entries.forEach { section ->
                QueueContext.entries.forEach { context ->
                    val navigation = AppNavigation(destination, section, viewedQueue = context)
                    assertEquals(navigation, AppNavigation.restore(snapshot.copy(navigation = navigation.persisted())))
                }
            }
        }
    }

    @Test fun malformedDepthCannotAllocateAnUnboundedStackOrRecoverUncountedDetails() {
        val values = buildMap {
            put("details", Int.MAX_VALUE.toString())
            repeat(16) { index ->
                put("detail.$index.kind", "LICENSES")
                put("detail.$index.id", "legal-$index")
            }
        }
        val restored = AppNavigation.restore(snapshot.copy(navigation = values))
        assertEquals(12, restored.details.size)
        assertEquals("legal-11", restored.detail?.id)

        listOf("-1", "0", "999999999999999999999999", "", "1.5").forEach { invalidDepth ->
            assertTrue(AppNavigation.restore(snapshot.copy(navigation = values + ("details" to invalidDepth))).details.isEmpty())
        }
    }

    @Test fun incompleteOrUnrecognizedCollectionMetadataDropsTheRoute() {
        val values = AppNavigation(details = listOf(AppDetail(DetailKind.COLLECTION, collection.id, collection))).persisted()
        listOf("provider", "canonicalId", "title", "browseKind").forEach { required ->
            val restored = AppNavigation.restore(snapshot.copy(navigation = values - "detail.0.$required"))
            assertTrue(restored.details.isEmpty(), "Missing collection $required must not restore an unusable detail")
        }
        listOf("provider" to "UNRECOGNIZED_PROVIDER", "browseKind" to "ALBUM_FROM_GUESSED_TITLE",
            "canonicalId" to " ", "title" to "\n\t").forEach { (field, invalid) ->
            val restored = AppNavigation.restore(snapshot.copy(navigation = values + ("detail.0.$field" to invalid)))
            assertTrue(restored.details.isEmpty(), "Invalid collection $field must not restore an unusable detail")
        }
    }

    @Test fun optionalCollectionMetadataDoesNotPreventRestoration() {
        val minimal = collection.copy(source = collection.source.copy(canonicalUrl = null), artworkUrl = null, creator = "")
        val navigation = AppNavigation(details = listOf(AppDetail(DetailKind.COLLECTION, minimal.id, minimal)))

        assertEquals(navigation, AppNavigation.restore(snapshot.copy(navigation = navigation.persisted())))
    }

    @Test fun licensesAreALocalRouteAndDoNotNeedACatalogEntity() {
        val navigation = AppNavigation(destination = AppDestination.SETTINGS,
            details = listOf(AppDetail(DetailKind.LICENSES, "legal")))
        assertEquals(navigation, AppNavigation.restore(AppSnapshot(navigation = navigation.persisted())))
    }

    @Test fun inspectingQueuesAndOpeningDetailsCannotMutateThePersistedAudioState() {
        val before = snapshot.copy(navigation = AppNavigation(viewedQueue = QueueContext.MUSIC).persisted())
        val savedQueues = before.queues.toMap()
        val browsing = AppNavigation.restore(before).copy(viewedQueue = QueueContext.PODCAST)
            .open(AppDetail(DetailKind.EPISODE, episode.id))

        assertEquals(QueueContext.PODCAST, browsing.viewedQueue)
        assertEquals(QueueContext.MUSIC, before.activeContext)
        assertEquals(savedQueues, before.queues)
        assertEquals("music-first", before.queue(QueueContext.MUSIC).currentEntryId)
        assertEquals(listOf("music-first", "music-duplicate"), before.queue(QueueContext.MUSIC).entries.map { it.id })
        assertEquals(12_345L, before.queue(QueueContext.MUSIC).positionMs)
        assertEquals(98_765L, before.queue(QueueContext.PODCAST).positionMs)
        assertEquals(1.5f, before.queue(QueueContext.PODCAST).speed)
    }
}
