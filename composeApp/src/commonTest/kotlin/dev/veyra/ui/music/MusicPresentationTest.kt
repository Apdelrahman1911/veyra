package dev.veyra.ui.music

import dev.veyra.model.AppSnapshot
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.PodcastShow
import dev.veyra.model.RepeatMode
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.playback.PlaybackPhase
import dev.veyra.playback.PlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicPresentationTest {
    @Test fun timeFormattingUsesActualMediaTimeAndHandlesLongEpisodes() {
        assertEquals("0:00", formatMediaTime(-10))
        assertEquals("0:59", formatMediaTime(59_999))
        assertEquals("1:00", formatMediaTime(60_000))
        assertEquals("2:13:05", formatMediaTime(7_985_000))
        assertEquals("120:00:00", formatMediaTime(432_000_000))
    }

    @Test fun unknownAndInvalidDurationsCannotProduceAnInvalidSeek() {
        assertEquals(0f, seekFraction(100, null))
        assertEquals(0f, seekFraction(100, 0))
        assertEquals(1f, seekFraction(10_000, 1_000))
        assertEquals(0f, seekFraction(-10, 1_000))
        assertEquals(500L, seekPosition(0.5f, 1_000))
        assertEquals(0L, seekPosition(Float.NaN, 1_000))
        assertEquals(1_000L, seekPosition(5f, 1_000))
    }

    @Test fun bilingualMetadataIsIsolatedWithoutReversingOrNormalizingContent() {
        val original = "الحلقة ١٢: Design Systems — 2026"
        assertEquals("\u2068$original\u2069", isolateMetadata(original))
    }

    @Test fun playlistEditorRejectsBlankNamesAndKeepsUnicode() {
        assertNull(validatedPlaylistName(" \n\t "))
        assertEquals("ليلة هادئة", validatedPlaylistName("  ليلة هادئة  "))
    }

    @Test fun movingOneDuplicateDoesNotRemoveOtherOccurrences() {
        assertEquals(listOf("a-2", "a-1", "b"), moveOccurrence(listOf("a-1", "b", "a-2"), 2, 0))
        assertEquals(listOf("a", "b"), moveOccurrence(listOf("a", "b"), -1, 0))
    }

    @Test fun clearUpcomingDoesNotIncludeCurrentOrHistory() {
        assertEquals(listOf("next-a", "next-b"), pendingOccurrenceIds(listOf("past", "now", "next-a", "next-b"), "now"))
        assertEquals(emptyList(), pendingOccurrenceIds(listOf("past", "now"), "now"))
        assertEquals(listOf("first"), pendingOccurrenceIds(listOf("first"), null))
    }

    @Test fun cachedOnlineMetadataIsNotAutomaticallyLocalLibraryMembership() {
        val cached = music("cached")
        val favorite = music("favorite")
        val local = music("imported", SourceProvider.LOCAL)
        val episode = music("episode", SourceProvider.RSS).copy(kind = MediaKind.EPISODE, showId = "show")
        val snapshot = AppSnapshot(
            media = listOf(cached, favorite, local, episode).associateBy { it.id },
            favoriteIds = setOf(favorite.id),
            followedShowIds = setOf("show"),
            shows = mapOf("show" to PodcastShow("show", "Show", "https://example.org/feed", episodeIds = listOf(episode.id))),
        )
        assertEquals(setOf("favorite", "imported", "episode"), libraryMedia(snapshot).map { it.id }.toSet())
    }

    @Test fun arabicRetrievalNormalizationNeverChangesTheDisplayedTitle() {
        val title = "مُوسِيقَى ـ هادئة"
        val item = music("arabic").copy(title = title, creator = "Studio 2026")
        assertTrue(matchesMediaQuery(item, "موسيقى هادئة 2026"))
        assertEquals(title, item.title)
        assertFalse(matchesMediaQuery(item, "different"))
        assertFalse(matchesSearchKind(item, SearchKind.PODCASTS))
        assertTrue(matchesSearchKind(item.copy(kind = MediaKind.EPISODE), SearchKind.ALL))
    }

    @Test fun bidiControlsCannotEscapeThePresentationIsolate() {
        assertEquals("\u2068title suffix\u2069", isolateMetadata("title\u2069\u202E suffix"))
    }

    @Test fun timerInputsMatchTheCoordinatorBoundAndAcceptArabicDigits() {
        assertEquals(12, validatedTimerMinutes("١٢"))
        assertEquals(90, validatedTimerMinutes(" ۹۰ "))
        assertEquals(1_440, validatedTimerMinutes("1440"))
        listOf("", "0", "1441", "2.5", "-15", "lots", "9999999999999999999").forEach { assertNull(validatedTimerMinutes(it)) }
    }

    @Test fun shuffleAndRepeatUiDoNotInventUnsupportedSpeedOrState() {
        assertEquals(RepeatMode.ALL, nextRepeatMode(RepeatMode.OFF))
        assertEquals(RepeatMode.ONE, nextRepeatMode(RepeatMode.ALL))
        assertEquals(RepeatMode.OFF, nextRepeatMode(RepeatMode.ONE))
        assertEquals(listOf(.75f, 1f, 1.25f), availableSpeeds(.7f, 1.3f))
        assertTrue(availableSpeeds(2f, 1f).isEmpty())
    }

    @Test fun bufferingKeepsPauseAvailableWhileFailureOffersRetry() {
        assertTrue(showsPause(PlaybackState(phase = PlaybackPhase.BUFFERING, desiredPlaying = true)))
        assertTrue(showsPause(PlaybackState(phase = PlaybackPhase.RESOLVING, desiredPlaying = true)))
        assertFalse(showsPause(PlaybackState(phase = PlaybackPhase.ERROR, desiredPlaying = true)))
        assertFalse(showsPause(PlaybackState(phase = PlaybackPhase.ENDED, desiredPlaying = true)))
        assertFalse(showsPause(PlaybackState(phase = PlaybackPhase.PAUSED, desiredPlaying = false)))
    }

    private fun music(id: String, provider: SourceProvider = SourceProvider.YOUTUBE) =
        MediaItem(id, "Track $id", MediaKind.MUSIC, SourceRef(provider, id))
}
