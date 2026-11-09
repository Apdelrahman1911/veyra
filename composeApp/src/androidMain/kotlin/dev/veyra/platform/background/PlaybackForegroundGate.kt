package dev.veyra.platform.background

import android.content.Context
import android.content.Intent
import android.os.Build
import dev.veyra.model.VeyraException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Await real startForeground(), not merely startForegroundService(), before requesting focus. */
class PlaybackForegroundGate(private val context: Context) {
    private var service: PlaybackService? = null
    private var waiting: CompletableDeferred<Unit>? = null

    suspend fun awaitForeground(): Unit = withContext(Dispatchers.Main.immediate) {
        service?.let { it.ensurePlaybackForeground(); return@withContext }
        val request = waiting ?: CompletableDeferred<Unit>().also {
            waiting = it
            try {
                val intent = Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_START)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (error: Exception) {
                waiting = null
                if (!isPlaybackForegroundRejection(error)) throw error
                it.completeExceptionally(restricted())
            }
        }
        try { withTimeout(8_000) { request.await() } }
        catch (_: TimeoutCancellationException) {
            if (waiting === request) waiting = null
            throw restricted()
        }
    }

    internal fun attached(value: PlaybackService) { service = value }
    internal fun owns(value: PlaybackService): Boolean = service === value
    internal fun started(value: PlaybackService) {
        if (service !== value) return
        val request = waiting
        waiting = null
        request?.complete(Unit)
    }
    internal fun detached(value: PlaybackService): Boolean {
        if (service !== value) return false
        service = null
        // Retire identity before resuming a failed waiter; its retry must not reuse this service.
        val request = waiting
        waiting = null
        request?.completeExceptionally(restricted())
        return true
    }
    private fun restricted() = VeyraException(playbackForegroundFailure())
}
