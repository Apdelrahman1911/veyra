package dev.veyra.platform.background

import android.app.ForegroundServiceStartNotAllowedException
import android.app.ServiceStartNotAllowedException
import android.os.Build
import android.os.Looper
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException

/** Only failures of an actual foreground/notification API call are passed to this classifier. */
internal fun isPlaybackForegroundRejection(error: Exception): Boolean =
    error is SecurityException || Build.VERSION.SDK_INT >= 31 && error is ServiceStartNotAllowedException

/** Never retain an OS exception's message/cause, which may include a notification or private URI. */
internal fun playbackForegroundFailure() = AppFailure(FailureCode.BACKGROUND_RESTRICTED, "playback_foreground_service")

/**
 * Main-confined retirement shared by explicit foreground starts, manual notification refreshes,
 * Media3's rejection listener and destruction. Only the matching service may silence/remove the
 * application session. Retirement is NOT proof that native files/resources have been released.
 */
internal class PlaybackForegroundFailureGuard(
    private val retireLocal: () -> Unit,
    private val detachOwner: () -> Boolean,
    private val silenceOwner: (AppFailure?) -> Unit,
    private val removeOwnedSession: () -> Unit,
    private val stopService: () -> Unit,
) {
    var retired = false
        private set

    /** Notification callbacks have no caller to throw to; the owning engine receives typed Error. */
    fun attempt(update: () -> Unit): Boolean {
        checkMain()
        if (retired) return false
        try {
            update()
            return !retired
        } catch (error: Exception) {
            if (!isPlaybackForegroundRejection(error)) throw error
            rejected()
            return false
        }
    }

    /** Explicit Play also receives the sanitized failure synchronously through its foreground gate. */
    fun requireForeground(update: () -> Unit) {
        if (!attempt(update)) throw VeyraException(playbackForegroundFailure())
    }

    /**
     * Media3 1.8 only recognizes this concrete exception when rejecting its synchronous Play
     * request on API31/32. Normalize recognized policy failures AFTER retirement, with no raw
     * cause/message for Media3 to log. This signals framework refusal; it does not assert which
     * exception class Android originally threw. Older APIs have no such Media3 rejection path;
     * the native service-loss barrier and typed engine error still prevent output.
     */
    fun frameworkUpdate(startInForegroundRequired: Boolean, update: () -> Unit) {
        checkMain()
        val alreadyRetired = retired
        if (attempt(update)) return
        if (Build.VERSION.SDK_INT >= 31 && (!alreadyRetired || startInForegroundRequired)) {
            throw ForegroundServiceStartNotAllowedException("playback_foreground_service")
        }
    }

    fun rejected() {
        checkMain()
        if (retired) return
        try { retire(playbackForegroundFailure()) }
        finally { stopService() }
    }

    fun destroyed() {
        checkMain()
        retire(null)
    }

    private fun retire(failure: AppFailure?) {
        if (retired) return
        retired = true
        retireLocal()
        if (detachOwner()) {
            // Silence and publish the originating native generation before async service teardown.
            // Session detachment does not close the application engine or acknowledge file release.
            try { silenceOwner(failure) }
            finally { removeOwnedSession() }
        }
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Retire the Android foreground owner on Main." }
    }
}
