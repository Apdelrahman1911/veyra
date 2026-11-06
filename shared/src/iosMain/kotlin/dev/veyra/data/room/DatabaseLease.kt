@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.data.room

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import platform.Foundation.NSLock
import platform.posix.*

internal actual fun acquireDatabaseLease(path: String): DatabaseLease {
    val descriptor = open(path, O_CREAT or O_RDWR, 384) // 0600; never globally readable listening data.
    if (descriptor < 0) throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "Database lease could not be opened"))
    if (flock(descriptor, LOCK_EX or LOCK_NB) != 0) {
        close(descriptor)
        throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "Database is already open"))
    }
    return object : DatabaseLease {
        private val monitor = NSLock()
        private var closed = false
        override fun release() {
            monitor.lock()
            try {
                if (!closed) { closed = true; flock(descriptor, LOCK_UN); close(descriptor) }
            } finally { monitor.unlock() }
        }
    }
}
