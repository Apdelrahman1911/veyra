package dev.veyra.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IconDirectionTest {
    @Test fun navigationAndReadingOrderSymbolsMirror() {
        listOf(VeyraIcons.Back, VeyraIcons.ChevronForward, VeyraIcons.Queue, VeyraIcons.Sort, VeyraIcons.OpenInNew).forEach {
            assertTrue(it.autoMirror, it.name)
        }
    }

    @Test fun transportIconsNeverReverseMediaTimeInArabic() {
        listOf(VeyraIcons.Play, VeyraIcons.Pause, VeyraIcons.Stop, VeyraIcons.Previous, VeyraIcons.Next,
            VeyraIcons.SkipBack, VeyraIcons.SkipForward, VeyraIcons.Shuffle, VeyraIcons.Repeat, VeyraIcons.RepeatOne).forEach {
            assertFalse(it.autoMirror, it.name)
            assertEquals(24f, it.viewportWidth)
            assertEquals(24f, it.viewportHeight)
        }
    }

    @Test fun brandAndPhysicalObjectsDoNotMirror() {
        listOf(VeyraIcons.Brand, VeyraIcons.Clock, VeyraIcons.Check, VeyraIcons.Download,
            VeyraIcons.Music, VeyraIcons.Podcasts, VeyraIcons.Favorite, VeyraIcons.Bookmark).forEach {
            assertFalse(it.autoMirror, it.name)
        }
    }

    @Test fun convenienceAliasesReuseTheSameOriginalVectors() {
        assertSame(VeyraIcons.Back, VeyraIcons.ArrowBack)
        assertSame(VeyraIcons.Play, VeyraIcons.PlayArrow)
        assertSame(VeyraIcons.MoreOptions, VeyraIcons.MoreVert)
        assertSame(VeyraIcons.Timer, VeyraIcons.Clock)
    }
}
