@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.veyra.platform.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.veyra.model.MediaKind
import dev.veyra.playback.EngineEvent
import dev.veyra.playback.InterruptionKind

/**
 * A single focus lease for the application transport, not one lease per screen/session.
 * We intentionally do not combine this with Media3 automatic focus handling: a controller
 * pause must not be mistaken for the end of a transient focus loss. Only AUDIOFOCUS_GAIN
 * can produce that event, and only the shared controller may decide to resume.
 */
internal class AndroidAudioFocus(
    context: Context,
    private val pauseNative: () -> Unit,
    private val emit: (EngineEvent.Interruption) -> Unit,
) {
    enum class Result { GRANTED, WAITING, DENIED }

    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var lease: Lease? = null

    private class Lease(var generation: Long) {
        lateinit var listener: AudioManager.OnAudioFocusChangeListener
        var request: AudioFocusRequest? = null
        var transientlyLost = false
    }

    fun request(generation: Long, kind: MediaKind): Result {
        checkMain()
        lease?.takeIf { it.generation == generation }?.let { current ->
            if (current.transientlyLost) {
                emit(EngineEvent.Interruption(generation, InterruptionKind.TRANSIENT_BEGAN))
                return Result.WAITING
            }
            return Result.GRANTED
        }
        abandon()
        val current = Lease(generation)
        current.listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (Looper.myLooper() == Looper.getMainLooper()) onChange(current, change)
            else handler.post { onChange(current, change) }
        }
        lease = current
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(if (kind == MediaKind.EPISODE) AudioAttributes.CONTENT_TYPE_SPEECH
                    else AudioAttributes.CONTENT_TYPE_MUSIC).build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(true)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(current.listener, handler).build()
            current.request = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(current.listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return Result.GRANTED
        abandon()
        return Result.DENIED
    }

    /** A transient pause retains the listener for a genuine gain event, not an auto-resume. */
    fun paused() {
        checkMain()
        if (lease?.transientlyLost != true) abandon()
    }

    /** The same app-owned focus lease follows an atomically promoted crossfade deck. */
    fun transfer(fromGeneration: Long, toGeneration: Long) {
        checkMain()
        lease?.takeIf { it.generation == fromGeneration }?.generation = toGeneration
    }

    fun abandon() {
        checkMain()
        val previous = lease ?: return
        lease = null // Reject queued callbacks before asking Android to abandon the old lease.
        if (Build.VERSION.SDK_INT >= 26) previous.request?.let(manager::abandonAudioFocusRequest)
        else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(previous.listener)
        }
    }

    private fun onChange(current: Lease, change: Int) {
        checkMain()
        if (lease !== current) return
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (!current.transientlyLost) {
                    current.transientlyLost = true
                    pauseNative()
                    emit(EngineEvent.Interruption(current.generation, InterruptionKind.TRANSIENT_BEGAN))
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (current.transientlyLost) {
                current.transientlyLost = false
                emit(EngineEvent.Interruption(current.generation, InterruptionKind.TRANSIENT_ENDED, mayResume = true))
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                pauseNative()
                abandon()
                emit(EngineEvent.Interruption(current.generation, InterruptionKind.PERMANENT))
            }
        }
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
}
