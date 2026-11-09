package dev.veyra.data.room

import java.nio.file.AccessDeniedException

/** A terminated Windows process can leave its kernel file handle briefly pending release. */
internal fun <T : Any> retryWindowsDatabaseLease(
    windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
    nanoTime: () -> Long = System::nanoTime,
    pause: (Long) -> Unit = Thread::sleep,
    attempt: () -> T?,
): T? {
    if (!windows) return attempt()
    val started = nanoTime()
    var retries = 0
    while (true) {
        try {
            val acquired = attempt()
            if (acquired != null) return acquired
        } catch (denied: AccessDeniedException) {
            if (retries >= 30 || nanoTime() - started >= 3_000_000_000L) throw denied
        }
        if (retries >= 30 || nanoTime() - started >= 3_000_000_000L) return null
        retries++
        pause(100)
    }
}
