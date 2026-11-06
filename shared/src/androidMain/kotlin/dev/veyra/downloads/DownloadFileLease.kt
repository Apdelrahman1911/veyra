package dev.veyra.downloads

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

/** Reject before a second open: closing ANY fd for a POSIX process-lock inode releases its lock. */
private object DownloadLeaseRegistry { val held = mutableSetOf<String>() }

internal actual fun tryAcquireDownloadFileLease(path: String): DownloadFileLease? = synchronized(DownloadLeaseRegistry) {
    val key = File(path).canonicalPath
    if (!DownloadLeaseRegistry.held.add(key)) return@synchronized null
    var file: RandomAccessFile? = null
    try {
        val opened = RandomAccessFile(key, "rw")
        file = opened
        val lock = try { opened.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
        if (lock == null) {
            opened.close()
            DownloadLeaseRegistry.held.remove(key)
            return@synchronized null
        }
        object : DownloadFileLease {
            private var closed = false
            override fun close() {
                synchronized(DownloadLeaseRegistry) {
                    if (closed) return@synchronized
                    closed = true
                    try { lock.release() } finally {
                        // A failed close leaves the registry entry reserved until process exit.
                        opened.close()
                        DownloadLeaseRegistry.held.remove(key)
                    }
                }
            }
        }
    } catch (error: Exception) {
        try { file?.close(); DownloadLeaseRegistry.held.remove(key) }
        catch (closeError: Exception) { error.addSuppressed(closeError) }
        throw error
    }
}
