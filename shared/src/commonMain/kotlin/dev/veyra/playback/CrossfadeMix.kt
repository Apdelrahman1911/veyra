package dev.veyra.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Shared envelope math; native engines apply these gains to actual simultaneously running decks. */
object CrossfadeMix {
    /** Fixed music-only -3.5 dB headroom, even outside overlap; never a boundary gain jump. */
    const val MUSIC_GAIN: Float = 0.6683439f

    fun outgoing(fraction: Float): Float = (MUSIC_GAIN * cos(fraction.safeFraction() * PI / 2)).toFloat()
    fun incoming(fraction: Float): Float = (MUSIC_GAIN * sin(fraction.safeFraction() * PI / 2)).toFloat()

    /** Linear return inside the safe gain region; native ordinary cancellation takes >=40 ms. */
    fun cancel(startOut: Float, startIn: Float, fraction: Float): Pair<Float, Float> {
        val t = fraction.safeFraction()
        return (startOut + (MUSIC_GAIN - startOut) * t) to (startIn * (1 - t))
    }

    private fun Float.safeFraction(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f
}
