package dev.veyra.data.room

import androidx.sqlite.SQLiteException

internal actual fun <T> withWalRecoveryRetry(read: () -> T): T = retryWindowsWalRecovery(read = read)

/**
 * SQLite 3.50.1 can return IOERR_TRUNCATE while Windows retires a dead writer's
 * -shm mapping. Upstream: sqlite.org/forum/info/ba7afbd0e81501118563617430fe06d0aca052e55ece2b0d457f8e16fcf85bba
 * Reopen the validation connection for at most three seconds. Never remove the
 * WAL, relax validation, retry a transaction, or hide a persistent I/O failure.
 */
internal fun <T> retryWindowsWalRecovery(
    windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
    nanoTime: () -> Long = System::nanoTime,
    pause: (Long) -> Unit = Thread::sleep,
    read: () -> T,
): T {
    if (!windows) return read()
    val started = nanoTime()
    var retries = 0
    while (true) {
        try {
            return read()
        } catch (error: SQLiteException) {
            // This SQLite API exposes its extended result code only in the
            // driver's message. Require the exact prefix emitted by the driver.
            if (!error.message.orEmpty().startsWith("Error code: 1546, message:") ||
                retries >= 30 || nanoTime() - started >= 3_000_000_000L) throw error
            retries++
            pause(100)
        }
    }
}
