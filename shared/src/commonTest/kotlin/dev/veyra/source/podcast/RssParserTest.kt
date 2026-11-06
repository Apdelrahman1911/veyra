package dev.veyra.source.podcast

import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import kotlin.test.*

class RssParserTest {
    private fun feed(items: String, title: String = "برنامج عربي", extra: String = ""): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:i="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel>
        <title>$title</title><description><![CDATA[<p>وصف &amp; نص</p><script>bad()</script>]]></description>
        <language>ar</language><i:author>الناشر</i:author><copyright>Publisher rights reserved</copyright>$extra
        $items</channel></rss>
    """.trimIndent()
    private fun item(guid: String = "stable", url: String = "https://cdn.example.com/episode.mp3", title: String = "الحلقة الأولى"): String = """
        <item><title>$title</title><guid>$guid</guid><enclosure url="$url" length="191262162" type="audio/mpeg"/>
        <i:duration>02:13:05</i:duration><pubDate>Sun, 05 Apr 2026 03:00:50 GMT</pubDate><i:episode>7</i:episode></item>
    """.trimIndent()

    @Test fun parsesGenuineShapeArabicNamespaceDurationAndRightsWithoutMarkup() {
        val result = RssParser.parse(feed(item()), "https://example.com/feed.xml")
        assertEquals("برنامج عربي", result.show.title)
        assertEquals("ar", result.show.language)
        assertEquals("الناشر", result.show.publisher)
        assertEquals("وصف & نص", result.show.description)
        assertEquals("Publisher rights reserved", result.show.copyright)
        assertEquals(7_985_000L, result.episodes.single().durationMs)
        assertEquals(7, result.episodes.single().episodeNumber)
        assertNotNull(result.episodes.single().publishedEpochMs)
        assertNull(result.episodes.single().attribution?.licenseName)
    }

    @Test fun rejectsDtdExternalEntitiesAndEntityExpansion() {
        listOf("<!DOCTYPE rss SYSTEM 'https://example.com/private'>",
            "<!DOCTYPE rss [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>",
            "<!DOCTYPE rss [<!ENTITY a 'expansion'><!ENTITY b '&a;&a;'>]>").forEach {
            val error = assertFailsWith<VeyraException> { RssParser.parse(it + feed(item()), "https://example.com/feed") }
            assertEquals(FailureCode.PARSE_FAILURE, error.failure.code)
        }
        assertFailsWith<VeyraException> { RssParser.parse(feed(item(), "&undeclared;"), "https://example.com/feed") }
    }

    @Test fun rejectsMalformedOverdeepAndMultipleRoots() {
        listOf("<rss><channel></rss>", "<rss/><rss/>", "<rss>" + "<x>".repeat(60) + "</x>".repeat(60) + "</rss>")
            .forEach { assertFailsWith<VeyraException> { RssParser.parse(it, "https://example.com/feed") } }
    }

    @Test fun shortRefreshRetainsOldEpisodesAndDetectsChangedResourceNotNewIdentity() {
        val first = RssParser.parse(feed(item("same") + item("older")), "https://example.com/feed")
        val refreshed = RssParser.parse(feed(item("same", "https://cdn.example.com/replaced.mp3")),
            "https://new.example.com/feed", first.show, first.episodes)
        assertEquals(first.show.id, refreshed.show.id)
        assertEquals(2, refreshed.episodes.size)
        assertEquals(first.episodes[0].id, refreshed.episodes[0].id)
        assertEquals(setOf(first.episodes[0].id), refreshed.changedEnclosureIds)
        assertTrue("https://example.com/feed" in refreshed.show.feedAliases)
        assertEquals(first.episodes[1], refreshed.episodes[1])
    }

    @Test fun guidIdentityIgnoresTitleAndDoesNotCollideAcrossShows() {
        val first = RssParser.parse(feed(item()), "https://one.example.com/feed")
        val renamed = RssParser.parse(feed(item(title = "عنوان جديد")), "https://one.example.com/feed")
        val other = RssParser.parse(feed(item()), "https://two.example.com/feed")
        assertEquals(first.episodes.single().id, renamed.episodes.single().id)
        assertNotEquals(first.episodes.single().id, other.episodes.single().id)
    }

    @Test fun duplicateGuidDoesNotDuplicateEpisodesAndUnsafeEnclosureCannotBeRelayed() {
        val result = RssParser.parse(feed(item() + item() + item("unsafe", "http://localhost/audio")), "https://example.com/feed")
        assertEquals(2, result.episodes.size)
        assertNull(result.episodes.last().source.enclosureUrl)
        assertEquals("unsafe", result.episodes.last().source.episodeGuid)
        assertEquals(listOf("episode_without_safe_https_enclosure"), result.warnings)
    }

    @Test fun titledGuidEpisodesRemainDiscoverableWithoutAnyEnclosure() {
        val xml = feed("<item><title>Full publisher episode title</title><guid>no-enclosure</guid></item>" +
            "<item><title>Missing stable identity</title></item>")
        val result = RssParser.parse(xml, "https://example.com/feed")
        assertEquals("Full publisher episode title", result.episodes.single().title)
        assertEquals("no-enclosure", result.episodes.single().source.episodeGuid)
        assertNull(result.episodes.single().source.enclosureUrl)
        assertTrue("episode_without_stable_identity" in result.warnings)
    }

    @Test fun completeEnglishAndArabicTitlesAreNeverSilentlyTruncatedForSearch() {
        val title = "Complete عنوان ".repeat(150) + " — final identifying suffix 🎵"
        val result = RssParser.parse(feed(item(title = title), title), "https://example.com/feed")
        assertEquals(title, result.show.title)
        assertEquals(title, result.episodes.single().title)
        assertTrue(result.episodes.single().title.length > 1_000)
    }

    @Test fun fullTitleBoundRejectsRatherThanRetainingATruncatedIdentity() {
        val maximum = "x".repeat(MAX_DISCOVERY_TITLE_LENGTH)
        assertEquals(maximum, RssParser.parse(feed(item(title = maximum)), "https://example.com/feed").episodes.single().title)
        listOf(feed(item(title = maximum + "x")), feed(item(), maximum + "x"),
            feed(item(title = maximum + "<b>suffix</b>"))).forEach { xml ->
            assertEquals(FailureCode.PARSE_FAILURE, assertFailsWith<VeyraException> {
                RssParser.parse(xml, "https://example.com/feed")
            }.failure.code)
        }
    }

    @Test fun titlePunctuationAndLiteralEntitiesDoNotDisappearAsMarkup() {
        val title = "Fish &amp; Chips &lt;5&gt; &amp;copy; &#x1F3B5;"
        val result = RssParser.parse(feed(item(title = title), title), "https://example.com/feed")
        assertEquals("Fish & Chips <5> &copy; 🎵", result.episodes.single().title)
        assertEquals(result.show.title, result.episodes.single().title)
    }

    @Test fun longGuidsDoNotAliasAtTheFormerTruncationBoundary() {
        val prefix = "guid-" + "x".repeat(2_048)
        val result = RssParser.parse(feed(item(prefix + "one") + item(prefix + "two")), "https://example.com/feed")
        assertEquals(2, result.episodes.size)
        assertNotEquals(result.episodes[0].id, result.episodes[1].id)
        assertEquals(prefix + "two", result.episodes[1].source.episodeGuid)
    }

    @Test fun numericUnicodeAndCdataAreHandledWithoutInterpretingHtml() {
        val result = RssParser.parse(feed(item(), "&#x1F3B5; &#1601;&#1606;&#1580;&#1575;&#1606;"), "https://example.com/feed")
        assertEquals("🎵 فنجان", result.show.title)
        assertFailsWith<VeyraException> { RssParser.parse(feed(item(), "&#0;"), "https://example.com/feed") }
    }

    @Test fun atomFeedWithEnclosureUsesStableIdAndDefaultNamespace() {
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom" xml:lang="en"><title>Atom show</title>
            <author><name>Publisher</name></author><entry><id>tag:example.com,2026:one</id><title>Episode</title>
            <link rel="enclosure" href="https://cdn.example.com/one.mp3" type="audio/mpeg"/>
            <published>2026-11-06T12:00:00Z</published></entry></feed>"""
        val result = RssParser.parse(atom, "https://example.com/atom")
        assertEquals("Publisher", result.show.publisher)
        assertEquals("tag:example.com,2026:one", result.episodes.single().source.episodeGuid)
        assertNotNull(result.episodes.single().publishedEpochMs)
    }

    @Test fun durationAndDatesRejectInvalidValuesWithoutInventingZero() {
        assertEquals(1_266_000L, parsePodcastDuration("1266"))
        assertEquals(90_500L, parsePodcastDuration("1:30.5"))
        listOf("NaN", "Infinity", "-1", "n/a", "100000000000000000").forEach { assertNull(parsePodcastDuration(it)) }
        assertEquals(parsePodcastDate("2026-11-06T12:30:00Z"), parsePodcastDate("Fri, 06 Nov 2026 14:30:00 +0200"))
        assertNull(parsePodcastDate("Fri, 99 Nov 2026 14:30:00 +0200"))
    }
}
