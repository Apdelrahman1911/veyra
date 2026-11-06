@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.downloads

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import platform.Foundation.NSLock
import platform.posix.*

internal actual fun tryAcquireDownloadFileLease(path: String): DownloadFileLease? {
    val descriptor = open(path, O_CREAT or O_RDWR, 384)
    if (descriptor < 0) throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "download_lock_open"))
    // flock locks independent open-file descriptions, including two opens in the same process.
    if (flock(descriptor, LOCK_EX or LOCK_NB) != 0) {
        val failure = errno
        platform.posix.close(descriptor)
        if (failure == EWOULDBLOCK || failure == EAGAIN) return null
        throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "download_lock_acquire"))
    }
    return object : DownloadFileLease {
        private val mutex = NSLock()
        private var closed = false
        override fun close() {
            mutex.lock()
            try {
                if (closed) return
                closed = true
                val unlocked = flock(descriptor, LOCK_UN) == 0
                val released = platform.posix.close(descriptor) == 0
                if (!unlocked || !released) throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "download_lock_release"))
            } finally { mutex.unlock() }
        }
    }
}
