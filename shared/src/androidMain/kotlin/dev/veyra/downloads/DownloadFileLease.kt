package dev.veyra.downloads

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/** Reject before a second open: closing ANY fd for a POSIX process-lock inode releases its lock. */
private object DownloadLeaseRegistry { val held = mutableMapOf<String, AndroidDownloadFileLease?>() }

internal actual fun tryAcquireDownloadFileLease(path: String): DownloadFileLease? = synchronized(DownloadLeaseRegistry) {
    val key = File(path).canonicalPath
    if (key in DownloadLeaseRegistry.held) return@synchronized null
    DownloadLeaseRegistry.held[key] = null
    val opened = try { RandomAccessFile(key, "rw") }
    catch (error: Throwable) { DownloadLeaseRegistry.held.remove(key); throw error }
    val owner = AndroidDownloadFileLease(key, opened)
    DownloadLeaseRegistry.held[key] = owner
    val lock = try { try { opened.channel.tryLock() } catch (_: OverlappingFileLockException) { null } }
    catch (error: Throwable) {
        closeUnhanded(owner)
        throw error
    }
    owner.fileLock = lock
    if (lock == null) { closeUnhanded(owner); null } else owner
}

private fun closeUnhanded(owner: DownloadFileLease) {
    try { owner.close() }
    catch (_: Throwable) { throw DownloadFileLeaseAcquisitionFailure(owner) }
}

private class AndroidDownloadFileLease(private val key: String, private val file: RandomAccessFile) : DownloadFileLease {
    var fileLock: FileLock? = null
    private var consumed = false
    private var failure: dev.veyra.model.VeyraException? = null

    override fun close() = synchronized(DownloadLeaseRegistry) {
        failure?.let { throw it }
        if (consumed) return@synchronized
        try {
            // Closing the file closes its channel and releases its lock. A failed API return
            // is never retried, even if Java has already marked the channel/descriptor closed.
            file.close()
        } catch (_: Throwable) {
            val sticky = downloadLockReleaseFailure()
            failure = sticky
            throw sticky // Strong file/lock and canonical reservation survive until process exit.
        }
        consumed = true
        fileLock = null
        DownloadLeaseRegistry.held.remove(key)
        Unit
    }
}
