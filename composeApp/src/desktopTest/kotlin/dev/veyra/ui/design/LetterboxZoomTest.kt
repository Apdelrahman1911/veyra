package dev.veyra.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals

class LetterboxZoomTest {
    @Test fun onlyYouTubeFourByThreeThumbnailsAreZoomedPastTheirBars() {
        assertEquals(4f / 3f, letterboxZoom("https://i.ytimg.com/vi/abc/hqdefault.jpg"))
        assertEquals(4f / 3f, letterboxZoom("https://i.ytimg.com/vi_webp/abc/sddefault.webp?sqp=1"))
        assertEquals(4f / 3f, letterboxZoom("https://i.ytimg.com/vi/abc/hqdefault_live.jpg"))
        assertEquals(1f, letterboxZoom("https://i.ytimg.com/vi/abc/maxresdefault.jpg"))
        assertEquals(1f, letterboxZoom("https://i.ytimg.com/vi/abc/mqdefault.jpg"))
        assertEquals(1f, letterboxZoom("https://example.com/covers/hqdefault.jpg"))
        assertEquals(1f, letterboxZoom(null))
    }
}
