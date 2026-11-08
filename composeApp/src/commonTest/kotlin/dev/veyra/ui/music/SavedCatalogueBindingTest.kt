package dev.veyra.ui.music

import dev.veyra.model.DownloadStatus
import dev.veyra.ui.downloads.DownloadSurface
import dev.veyra.ui.downloads.DownloadSurfaceEntry
import dev.veyra.ui.downloads.DownloadSurfaceOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Media-row/detail saved selection is exact only when one ready recording is unambiguous. */
class SavedCatalogueBindingTest {
    private fun entry(id: String, bindingId: String?) = DownloadSurfaceEntry(
        id = id, origin = DownloadSurfaceOrigin.TYPED_CATALOGUE, mediaId = "media",
        status = DownloadStatus.COMPLETE, bytesDownloaded = 10, hasOwnedBytes = true,
        ready = true, bindingId = bindingId,
    )

    @Test
    fun soleReadyRecordingIsAnExactTarget() {
        assertEquals("binding-r1", DownloadSurface(listOf(entry("job", "binding-r1"))).savedCatalogueBinding("media"))
    }

    @Test
    fun twoReadyRecordingsAreNotSilentlyCollapsedToNewest() {
        assertNull(DownloadSurface(listOf(entry("old", "binding-r1"), entry("new", "binding-r2")))
            .savedCatalogueBinding("media"))
    }

    @Test
    fun neutralAndNonReadyRowsExposeNoCataloguePlayTarget() {
        val neutral = entry("neutral", null).copy(origin = DownloadSurfaceOrigin.TYPED_SOURCE, mediaId = null, ready = false)
        assertNull(DownloadSurface(listOf(neutral)).savedCatalogueBinding("media"))
        assertNull(DownloadSurface(listOf(entry("checking", "binding-r1").copy(ready = false)))
            .savedCatalogueBinding("media"))
    }
}
