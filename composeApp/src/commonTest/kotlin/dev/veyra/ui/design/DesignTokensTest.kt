package dev.veyra.ui.design

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opaque-token checks only: not a screenshot, blended-state or platform accessibility test. */
class DesignTokensTest {
    private val palettes = mapOf("light" to VeyraPalette.Light, "dark" to VeyraPalette.Dark)
    private val surfaces = listOf("canvas", "surface", "surfaceContainer", "surfaceContainerHighest")

    @Test fun allForegroundTokensRemainReadableOnSupportedSurfaces() {
        palettes.forEach { (name, palette) ->
            val colors = tokenMap(palette)
            colors.forEach { (token, color) -> assertEquals(1f, color.alpha, "$name $token must stay opaque") }
            listOf("onSurface", "onSurfaceVariant", "primary", "secondary", "error", "success", "warning").forEach { foreground ->
                surfaces.forEach { background -> assertContrast(name, colors, foreground, background, 4.5) }
            }
            listOf("primary", "primaryContainer", "secondary", "secondaryContainer", "error", "errorContainer").forEach { background ->
                val foreground = "on" + background.replaceFirstChar { it.uppercase() }
                assertContrast(name, colors, foreground, background, 4.5)
            }
            listOf("onSurface", "onSurfaceVariant", "primary").forEach { foreground ->
                assertContrast(name, colors, foreground, "primaryContainer", 4.5)
            }
        }
    }

    @Test fun essentialOutlinesAndKeyboardFocusAreDistinguishable() {
        palettes.forEach { (name, palette) ->
            val colors = tokenMap(palette)
            (surfaces + listOf("primaryContainer", "secondaryContainer", "errorContainer")).forEach { background ->
                assertContrast(name, colors, "outline", background, 3.0)
                assertContrast(name, colors, "primary", background, 3.0)
            }
            assertContrast(name, colors, "error", "errorContainer", 3.0)
            // outlineSubtle is intentionally omitted: decorative separators only, never text,
            // required control boundaries or the only indication of keyboard focus.
        }
    }

    @Test fun controlsKeepTheSharedMinimumTouchTarget() {
        assertTrue(VeyraSpacing.TouchTarget.value >= 48f)
        assertTrue(VeyraRadii.Small.value > 0f)
        assertEquals(21.0, contrast(Color.Black, Color.White), 0.00001)
    }

    private fun assertContrast(name: String, colors: Map<String, Color>, foreground: String, background: String, required: Double) {
        val ratio = contrast(colors.getValue(foreground), colors.getValue(background))
        assertTrue(ratio >= required, "$name $foreground / $background is $ratio, needs $required")
    }

    private fun contrast(a: Color, b: Color): Double {
        val first = luminance(a)
        val second = luminance(b)
        return (max(first, second) + .05) / (min(first, second) + .05)
    }

    private fun luminance(color: Color): Double {
        fun linear(channel: Float): Double = channel.toDouble().let {
            if (it <= .04045) it / 12.92 else ((it + .055) / 1.055).pow(2.4)
        }
        return .2126 * linear(color.red) + .7152 * linear(color.green) + .0722 * linear(color.blue)
    }

    private fun tokenMap(p: VeyraColorTokens): Map<String, Color> = mapOf(
        "canvas" to p.canvas, "surface" to p.surface,
        "surfaceContainer" to p.surfaceContainer, "surfaceContainerHighest" to p.surfaceContainerHighest,
        "onSurface" to p.onSurface, "onSurfaceVariant" to p.onSurfaceVariant,
        "outline" to p.outline, "outlineSubtle" to p.outlineSubtle,
        "primary" to p.primary, "onPrimary" to p.onPrimary,
        "primaryContainer" to p.primaryContainer, "onPrimaryContainer" to p.onPrimaryContainer,
        "secondary" to p.secondary, "onSecondary" to p.onSecondary,
        "secondaryContainer" to p.secondaryContainer, "onSecondaryContainer" to p.onSecondaryContainer,
        "error" to p.error, "onError" to p.onError,
        "errorContainer" to p.errorContainer, "onErrorContainer" to p.onErrorContainer,
        "success" to p.success, "warning" to p.warning,
    )
}
