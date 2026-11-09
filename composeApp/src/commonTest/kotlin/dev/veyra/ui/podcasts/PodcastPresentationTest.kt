package dev.veyra.ui.podcasts

import dev.veyra.model.Attribution
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.PodcastShow
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PodcastPresentationTest {
    @Test
    fun publicationDatesAreStableCalendarDatesAndUnknownStaysUnknown() {
        assertNull(podcastPublishedDate(null))
        assertEquals("1970-01-01", podcastPublishedDate(0L))
        assertEquals("1969-12-31", podcastPublishedDate(-1L))
        assertEquals("1970-01-02", podcastPublishedDate(86_400_000L))
    }

    @Test
    fun absentDurationNeverLooksLikeACompletedOrEmptyEpisode() {
        assertNull(podcastProgressFraction(15_000L, null))
        assertNull(podcastProgressFraction(15_000L, 0L))
        assertNull(podcastProgressFraction(15_000L, -1L))
        assertNull(podcastRemainingMs(15_000L, null))
        assertEquals(0f, podcastProgressFraction(-1L, 60_000L))
        assertEquals(0.5f, podcastProgressFraction(30_000L, 60_000L))
        assertEquals(1f, podcastProgressFraction(90_000L, 60_000L))
        assertEquals(0L, podcastRemainingMs(Long.MAX_VALUE, 60_000L))
        assertEquals(60_000L, podcastRemainingMs(-1L, 60_000L))
    }

    @Test
    fun localSearchMatchesArabicVowelsAndMixedPublisherTokensWithoutChangingIdentity() {
        assertTrue(podcastMatchesQuery("فنجان", "فِنْجَان", "ثمانية"))
        assertTrue(podcastMatchesQuery("فنجان ثمانية", "فـنجان", "ثمانية"))
        assertTrue(podcastMatchesQuery("PLANET npr", "Planet Money", "NPR"))
        assertTrue(podcastMatchesQuery("  \n ", null, "Title"))
        assertFalse(podcastMatchesQuery("unrelated", "Planet Money", "NPR"))
        assertFalse(podcastMatchesQuery("Planet Thmanyah", "Planet Money", "NPR"))
    }

    @Test
    fun sourceLabelsNeverExposePrivateFeedPathsQueriesOrCredentials() {
        assertEquals("example.org", podcastPublicHost("https://example.org/secret-feed?token=private#secret"))
        assertEquals("example.org", podcastPublicHost("https://name:password@example.org/feed"))
        assertEquals("example.org:8443", podcastPublicHost("https://example.org:8443/feed"))
        assertNull(podcastPublicHost("file:///private/feed"))
        assertNull(podcastPublicHost("not a URL"))
        assertNull(podcastPublicHost("javascript:alert(1)"))
        assertNull(podcastPublicHost("https://a host.example/feed"))
        assertNull(podcastPublicHost("https://"))
        assertNull(podcastPublicHost(null))
    }

    @Test
    fun defaultViewIsExplicitlyNewestFirstAndDoesNotImplyQueuePlayback() {
        assertEquals(PodcastEpisodeFilter.ALL, PodcastListOptions().filter)
        assertEquals(PodcastEpisodeSort.NEWEST_FIRST, PodcastListOptions().sort)
        assertEquals("", PodcastListOptions().query)
    }

    @Test
    fun attributionCannotTurnPrivateFeedOrEnclosureLocatorsIntoPublisherPages() {
        val feed = "https://example.org/private-feed?token=authored-fixture"
        val enclosure = "https://example.org/private-audio.mp3?token=authored-fixture"
        val show = PodcastShow("show", "Authored show", " $feed ",
            websiteUrl = "$feed#website", feedAliases = setOf(" https://EXAMPLE.ORG/old-feed "))
        val episode = MediaItem("episode", "Authored episode", MediaKind.EPISODE,
            SourceRef(SourceProvider.RSS, "episode", feedUrl = feed, enclosureUrl = " $enclosure ",
                canonicalUrl = "https://example.org/old-feed#episode"), attribution = Attribution(sourceUrl = "$enclosure#source"))

        assertNull(podcastPublisherUrl(PodcastEpisodeUiState(episode, show)))
        assertNull(podcastPublisherUrl(show))
    }

    @Test
    fun publicPublisherPageSurvivesPrivateAttributionWithoutRewritingItsPath() {
        val feed = "https://example.org/feed.xml?token=authored-fixture"
        val page = "https://EXAMPLE.ORG/Episodes/One#notes"
        val show = PodcastShow("show", "Authored show", feed, websiteUrl = " $page ")
        val episode = MediaItem("episode", "Authored episode", MediaKind.EPISODE,
            SourceRef(SourceProvider.RSS, "episode", feedUrl = feed), attribution = Attribution(sourceUrl = "$feed#source"))

        assertEquals(page, podcastPublisherUrl(PodcastEpisodeUiState(episode, show)))
        assertEquals(page, podcastPublisherUrl(show))
        assertEquals(page, podcastPublisherUrl(show.copy(websiteUrl = feed, attribution = Attribution(sourceUrl = page))))
    }

    @Test
    fun publisherPageActionsDoNotExposeCredentialsOrAcceptMalformedSchemes() {
        val show = PodcastShow("show", "Authored show", "https://example.org/feed.xml")
        listOf("https://user:authored-fixture@example.org/page", "javascript:alert(1)",
            "file:///owned/page", "https://example.org/with\ncontrol", "https://example.org\\@other.org/page")
            .forEach { assertNull(podcastPublisherUrl(show.copy(websiteUrl = it)), it) }
    }
}
