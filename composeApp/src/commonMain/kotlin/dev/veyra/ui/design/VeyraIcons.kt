package dev.veyra.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Original code-native 24-unit icon family; no bitmap, icon-font or extended icon dependency.
 * Auto-mirroring is opt-in for navigation/reading-order symbols. Transport is always fixed.
 * Visible numbers for configurable skip intervals belong in labels, not baked into icon paths.
 */
object VeyraIcons {
    val Brand by lazy {
        strokeIcon("Veyra mark", width = 2.5f) {
            moveTo(3.5f, 6f)
            curveTo(6f, 6f, 6.8f, 7.8f, 8f, 10.5f)
            lineTo(11.5f, 18f)
            curveTo(11.8f, 18.8f, 12.8f, 18.8f, 13.2f, 18f)
            lineTo(20.5f, 5f)
            moveTo(12f, 5f)
            lineTo(9.8f, 9f)
        }
    }

    val Listen by lazy {
        strokeIcon("Listen") {
            moveTo(4f, 14f)
            lineTo(4f, 11.5f)
            curveTo(4f, 7f, 7.6f, 3.5f, 12f, 3.5f)
            curveTo(16.4f, 3.5f, 20f, 7f, 20f, 11.5f)
            lineTo(20f, 14f)
            roundedRect(3.5f, 12f, 7.5f, 20f, 1.5f)
            roundedRect(16.5f, 12f, 20.5f, 20f, 1.5f)
        }
    }

    val Search by lazy {
        strokeIcon("Search") {
            circle(10.5f, 10.5f, 6.5f)
            moveTo(15.3f, 15.3f); lineTo(21f, 21f)
        }
    }

    val Podcasts by lazy {
        strokeIcon("Podcasts") {
            roundedRect(9f, 3.5f, 15f, 14.5f, 3f)
            moveTo(6f, 10.5f)
            lineTo(6f, 12f)
            curveTo(6f, 15.4f, 8.5f, 18f, 12f, 18f)
            curveTo(15.5f, 18f, 18f, 15.4f, 18f, 12f)
            lineTo(18f, 10.5f)
            moveTo(12f, 18f); lineTo(12f, 21f)
            moveTo(8.5f, 21f); lineTo(15.5f, 21f)
        }
    }

    val Library by lazy {
        strokeIcon("Library") {
            roundedRect(3f, 4f, 7f, 20f, 1f)
            roundedRect(9f, 4f, 13f, 20f, 1f)
            moveTo(16f, 4.5f); lineTo(20f, 4f); lineTo(22f, 19.5f)
            lineTo(18f, 20f); close()
        }
    }

    val Music by lazy {
        strokeIcon("Music") {
            moveTo(10f, 17f); lineTo(10f, 5f); lineTo(20f, 3f); lineTo(20f, 15f)
            moveTo(10f, 9f); lineTo(20f, 7f)
            circle(7f, 18f, 3f)
            circle(17f, 16f, 3f)
        }
    }

    val Play by lazy {
        fillIcon("Play") {
            moveTo(8f, 4.8f)
            quadTo(8f, 3.7f, 9f, 4.3f)
            lineTo(19.5f, 11.2f)
            quadTo(20.7f, 12f, 19.5f, 12.8f)
            lineTo(9f, 19.7f)
            quadTo(8f, 20.3f, 8f, 19.2f)
            close()
        }
    }
    val PlayArrow: ImageVector get() = Play

    val Pause by lazy {
        fillIcon("Pause") {
            roundedRect(6f, 4f, 10f, 20f, 1f)
            roundedRect(14f, 4f, 18f, 20f, 1f)
        }
    }

    val Stop by lazy { fillIcon("Stop") { roundedRect(5f, 5f, 19f, 19f, 2f) } }

    val Previous by lazy {
        fillIcon("Previous") {
            roundedRect(4f, 5f, 6.5f, 19f, .6f)
            moveTo(19f, 5f); lineTo(8f, 12f); lineTo(19f, 19f); close()
        }
    }

    val Next by lazy {
        fillIcon("Next") {
            moveTo(5f, 5f); lineTo(16f, 12f); lineTo(5f, 19f); close()
            roundedRect(17.5f, 5f, 20f, 19f, .6f)
        }
    }

    val SkipBack by lazy {
        strokeIcon("Skip back") {
            moveTo(5f, 8f); lineTo(5f, 3.5f)
            moveTo(5f, 8f); lineTo(9.5f, 8f)
            moveTo(5.3f, 7.5f)
            curveTo(8.5f, 2.8f, 15f, 3f, 18.4f, 7.5f)
            curveTo(22.2f, 12.5f, 18.5f, 20.5f, 12f, 20.5f)
            curveTo(8.3f, 20.5f, 5.2f, 18.3f, 4.2f, 15f)
            moveTo(9f, 12.5f); lineTo(15f, 12.5f)
        }
    }

    val SkipForward by lazy {
        strokeIcon("Skip forward") {
            moveTo(19f, 8f); lineTo(19f, 3.5f)
            moveTo(19f, 8f); lineTo(14.5f, 8f)
            moveTo(18.7f, 7.5f)
            curveTo(15.5f, 2.8f, 9f, 3f, 5.6f, 7.5f)
            curveTo(1.8f, 12.5f, 5.5f, 20.5f, 12f, 20.5f)
            curveTo(15.7f, 20.5f, 18.8f, 18.3f, 19.8f, 15f)
            moveTo(9f, 12.5f); lineTo(15f, 12.5f)
            moveTo(12f, 9.5f); lineTo(12f, 15.5f)
        }
    }

    val Queue by lazy {
        strokeIcon("Queue", autoMirror = true) {
            moveTo(3f, 5f); lineTo(4f, 5f)
            moveTo(8f, 5f); lineTo(21f, 5f)
            moveTo(3f, 12f); lineTo(4f, 12f)
            moveTo(8f, 12f); lineTo(21f, 12f)
            moveTo(3f, 19f); lineTo(4f, 19f)
            moveTo(8f, 19f); lineTo(17f, 19f)
        }
    }

    val MoreOptions by lazy {
        fillIcon("More options") {
            circle(12f, 5f, 1.7f); circle(12f, 12f, 1.7f); circle(12f, 19f, 1.7f)
        }
    }
    val MoreVert: ImageVector get() = MoreOptions

    val Back by lazy {
        strokeIcon("Back", autoMirror = true) {
            moveTo(13f, 5f); lineTo(6f, 12f); lineTo(13f, 19f)
            moveTo(6f, 12f); lineTo(21f, 12f)
        }
    }
    val ArrowBack: ImageVector get() = Back

    val ChevronForward by lazy {
        strokeIcon("Forward", autoMirror = true) {
            moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f)
        }
    }

    val Close by lazy {
        strokeIcon("Close") {
            moveTo(6f, 6f); lineTo(18f, 18f)
            moveTo(18f, 6f); lineTo(6f, 18f)
        }
    }

    val Add by lazy {
        strokeIcon("Add") {
            moveTo(12f, 4f); lineTo(12f, 20f)
            moveTo(4f, 12f); lineTo(20f, 12f)
        }
    }

    val Download by lazy {
        strokeIcon("Download") {
            moveTo(12f, 3f); lineTo(12f, 15f)
            moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
            moveTo(4f, 16f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 16f)
        }
    }

    val Check by lazy {
        strokeIcon("Check") { moveTo(4f, 12f); lineTo(9f, 17f); lineTo(20f, 6f) }
    }

    val FavoriteBorder by lazy { strokeIcon("Add to favorites") { heart() } }
    val Favorite by lazy { fillIcon("Favorite") { heart() } }
    val BookmarkBorder by lazy { strokeIcon("Save for later") { bookmark() } }
    val Bookmark by lazy { fillIcon("Saved for later") { bookmark() } }

    val OpenInNew by lazy {
        strokeIcon("Open externally", autoMirror = true) {
            moveTo(13f, 4f); lineTo(20f, 4f); lineTo(20f, 11f)
            moveTo(20f, 4f); lineTo(10f, 14f)
            moveTo(9f, 4f); lineTo(4f, 4f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 15f)
        }
    }

    val Sort by lazy {
        strokeIcon("Sort", autoMirror = true) {
            moveTo(4f, 5f); lineTo(14f, 5f)
            moveTo(4f, 11f); lineTo(11f, 11f)
            moveTo(4f, 17f); lineTo(8f, 17f)
            moveTo(18f, 5f); lineTo(18f, 20f)
            moveTo(14.5f, 16.5f); lineTo(18f, 20f); lineTo(21.5f, 16.5f)
        }
    }

    val FilterList by lazy {
        strokeIcon("Filter") {
            moveTo(3f, 6f); lineTo(21f, 6f)
            moveTo(6f, 12f); lineTo(18f, 12f)
            moveTo(9f, 18f); lineTo(15f, 18f)
        }
    }

    val Refresh by lazy {
        strokeIcon("Refresh") {
            moveTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f)
            moveTo(19.5f, 9f)
            curveTo(17.7f, 4f, 11.8f, 2.6f, 7.5f, 5.3f)
            curveTo(2.5f, 8.5f, 2.9f, 16.3f, 8f, 19f)
            curveTo(12.5f, 21.5f, 18f, 19.2f, 19.8f, 15f)
        }
    }

    val Shuffle by lazy {
        strokeIcon("Shuffle") {
            moveTo(3f, 6f); lineTo(5f, 6f)
            curveTo(11f, 6f, 13f, 18f, 19f, 18f); lineTo(21f, 18f)
            moveTo(18f, 15f); lineTo(21f, 18f); lineTo(18f, 21f)
            moveTo(3f, 18f); lineTo(5f, 18f)
            curveTo(7.4f, 18f, 8.8f, 16f, 10f, 14f)
            moveTo(14f, 10f)
            curveTo(15.5f, 7.5f, 17f, 6f, 19f, 6f); lineTo(21f, 6f)
            moveTo(18f, 3f); lineTo(21f, 6f); lineTo(18f, 9f)
        }
    }

    val Repeat by lazy {
        strokeIcon("Repeat") {
            moveTo(4f, 10f); lineTo(4f, 6f); lineTo(20f, 6f)
            moveTo(17f, 3f); lineTo(20f, 6f); lineTo(17f, 9f)
            moveTo(20f, 14f); lineTo(20f, 18f); lineTo(4f, 18f)
            moveTo(7f, 15f); lineTo(4f, 18f); lineTo(7f, 21f)
        }
    }

    val RepeatOne by lazy {
        strokeIcon("Repeat one") {
            moveTo(4f, 10f); lineTo(4f, 6f); lineTo(20f, 6f)
            moveTo(17f, 3f); lineTo(20f, 6f); lineTo(17f, 9f)
            moveTo(20f, 14f); lineTo(20f, 18f); lineTo(4f, 18f)
            moveTo(7f, 15f); lineTo(4f, 18f); lineTo(7f, 21f)
            moveTo(10.5f, 11f); lineTo(12f, 10f); lineTo(12f, 14f)
        }
    }

    val Volume by lazy {
        strokeIcon("Volume", autoMirror = true) {
            moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f)
            lineTo(12f, 19f); lineTo(7f, 15f); lineTo(3f, 15f); close()
            moveTo(16f, 8f); curveTo(18f, 10f, 18f, 14f, 16f, 16f)
            moveTo(19f, 5f); curveTo(23f, 9f, 23f, 15f, 19f, 19f)
        }
    }

    val Timer by lazy {
        strokeIcon("Sleep timer") {
            circle(12f, 13.5f, 7.5f)
            moveTo(12f, 13.5f); lineTo(12f, 9f)
            moveTo(9f, 2.5f); lineTo(15f, 2.5f)
            moveTo(12f, 2.5f); lineTo(12f, 6f)
            moveTo(18f, 6.5f); lineTo(20f, 4.5f)
        }
    }
    val Clock: ImageVector get() = Timer

    val Settings by lazy {
        strokeIcon("Settings") {
            moveTo(4f, 6f); lineTo(9f, 6f)
            moveTo(15f, 6f); lineTo(20f, 6f)
            circle(12f, 6f, 3f)
            moveTo(4f, 17f); lineTo(6f, 17f)
            moveTo(12f, 17f); lineTo(20f, 17f)
            circle(9f, 17f, 3f)
        }
    }

    val Delete by lazy {
        strokeIcon("Delete") {
            moveTo(4f, 6f); lineTo(20f, 6f)
            moveTo(9f, 6f); lineTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 6f)
            moveTo(6f, 6f); lineTo(7f, 21f); lineTo(17f, 21f); lineTo(18f, 6f)
            moveTo(10f, 10f); lineTo(10f, 17f)
            moveTo(14f, 10f); lineTo(14f, 17f)
        }
    }

    val Edit by lazy {
        strokeIcon("Edit") {
            moveTo(4f, 16f); lineTo(15.5f, 4.5f)
            quadTo(17f, 3f, 18.5f, 4.5f)
            lineTo(19.5f, 5.5f); quadTo(21f, 7f, 19.5f, 8.5f)
            lineTo(8f, 20f); lineTo(3f, 21f); close()
            moveTo(13.5f, 6.5f); lineTo(17.5f, 10.5f)
        }
    }

    val ArrowUp by lazy {
        strokeIcon("Move up") {
            moveTo(5f, 11f); lineTo(12f, 4f); lineTo(19f, 11f)
            moveTo(12f, 4f); lineTo(12f, 21f)
        }
    }

    val ArrowDown by lazy {
        strokeIcon("Move down") {
            moveTo(5f, 13f); lineTo(12f, 20f); lineTo(19f, 13f)
            moveTo(12f, 3f); lineTo(12f, 20f)
        }
    }

    val Warning by lazy {
        strokeIcon("Warning") {
            moveTo(12f, 3f); lineTo(22f, 21f); lineTo(2f, 21f); close()
            moveTo(12f, 9f); lineTo(12f, 14f)
            moveTo(12f, 17f); lineTo(12f, 17.3f)
        }
    }

    val Info by lazy {
        strokeIcon("Information") {
            circle(12f, 12f, 9f)
            moveTo(12f, 11f); lineTo(12f, 17f)
            moveTo(12f, 7f); lineTo(12f, 7.2f)
        }
    }

    val Menu by lazy {
        strokeIcon("Navigation menu") {
            moveTo(3f, 5f); lineTo(21f, 5f)
            moveTo(3f, 12f); lineTo(21f, 12f)
            moveTo(3f, 19f); lineTo(21f, 19f)
        }
    }
}

private fun strokeIcon(
    name: String,
    autoMirror: Boolean = false,
    width: Float = 1.8f,
    drawing: PathBuilder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
    autoMirror = autoMirror,
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) { drawing() }
}.build()

private fun fillIcon(name: String, drawing: PathBuilder.() -> Unit): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
    autoMirror = false,
).apply { path(fill = SolidColor(Color.Black)) { drawing() } }.build()

private fun PathBuilder.roundedRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
    moveTo(left + radius, top)
    lineTo(right - radius, top); quadTo(right, top, right, top + radius)
    lineTo(right, bottom - radius); quadTo(right, bottom, right - radius, bottom)
    lineTo(left + radius, bottom); quadTo(left, bottom, left, bottom - radius)
    lineTo(left, top + radius); quadTo(left, top, left + radius, top)
    close()
}

private fun PathBuilder.circle(x: Float, y: Float, radius: Float) {
    val control = radius * .55228475f
    moveTo(x + radius, y)
    curveTo(x + radius, y + control, x + control, y + radius, x, y + radius)
    curveTo(x - control, y + radius, x - radius, y + control, x - radius, y)
    curveTo(x - radius, y - control, x - control, y - radius, x, y - radius)
    curveTo(x + control, y - radius, x + radius, y - control, x + radius, y)
    close()
}

private fun PathBuilder.heart() {
    moveTo(12f, 20.5f)
    curveTo(9f, 18f, 2.5f, 13.5f, 2.5f, 8f)
    curveTo(2.5f, 3f, 9f, 1.8f, 12f, 6f)
    curveTo(15f, 1.8f, 21.5f, 3f, 21.5f, 8f)
    curveTo(21.5f, 13.5f, 15f, 18f, 12f, 20.5f)
    close()
}

private fun PathBuilder.bookmark() {
    moveTo(6f, 3f); lineTo(18f, 3f); lineTo(18f, 21f)
    lineTo(12f, 17f); lineTo(6f, 21f); close()
}
