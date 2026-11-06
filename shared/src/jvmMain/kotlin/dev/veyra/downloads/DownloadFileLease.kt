package dev.veyra.downloads

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

/**
 * POSIX process locks are lost when ANY descriptor for the inode is closed. In particular,
 * opening a second channel, catching OverlappingFileLockException and closing it silently
 * unlocks the first channel for other processes even though FileLock.isValid stays true.
 * Reject same-process contenders BEFORE open, under the same monitor used for close.
 */
private object DownloadLeaseRegistry { val held = mutableSetOf<String>() }

internal actual fun tryAcquireDownloadFileLease(path: String): DownloadFileLease? = synchronized(DownloadLeaseRegistry) {
    val file = File(path).canonicalFile
    val key = file.path
    if (!DownloadLeaseRegistry.held.add(key)) return@synchronized null
    var channel: FileChannel? = null
    try {
        val opened = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        channel = opened
        val lock = try { opened.tryLock() } catch (_: OverlappingFileLockException) { null }
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
                        // If close fails, fail closed in this process until restart.
                        opened.close()
                        DownloadLeaseRegistry.held.remove(key)
                    }
                }
            }
        }
    } catch (error: Exception) {
        try { channel?.close(); DownloadLeaseRegistry.held.remove(key) }
        catch (closeError: Exception) { error.addSuppressed(closeError) }
        throw error
    }
}
