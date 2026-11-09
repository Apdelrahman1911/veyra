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
    val owner = IosDownloadFileLease(descriptor)
    // flock locks independent open-file descriptions, including two opens in the same process.
    if (flock(descriptor, LOCK_EX or LOCK_NB) != 0) {
        val failure = errno
        try { owner.close() }
        catch (_: Throwable) { throw DownloadFileLeaseAcquisitionFailure(owner) }
        if (failure == EWOULDBLOCK || failure == EAGAIN) return null
        throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "download_lock_acquire"))
    }
    return owner
}

private class IosDownloadFileLease(private val descriptor: Int) : DownloadFileLease {
    private val mutex = NSLock()
    private var consumed = false
    private var failure: VeyraException? = null

    override fun close() {
        mutex.lock()
        try {
            failure?.let { throw it }
            if (consumed) return
            // close releases this open-file-description's flock. Never retry an ambiguous
            // numeric fd (including EINTR): the OS may already have reused that number.
            if (platform.posix.close(descriptor) != 0) {
                val sticky = downloadLockReleaseFailure()
                failure = sticky
                throw sticky
            }
            consumed = true
        } finally { mutex.unlock() }
    }
}
