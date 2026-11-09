package dev.veyra.downloads

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

/**
 * POSIX process locks are lost when ANY descriptor for the inode is closed. In particular,
 * opening a second channel, catching OverlappingFileLockException and closing it silently
 * unlocks the first channel for other processes even though FileLock.isValid stays true.
 * Reject same-process contenders BEFORE open, under the same monitor used for close.
 */
private object DownloadLeaseRegistry { val held = mutableMapOf<String, JvmDownloadFileLease?>() }

internal actual fun tryAcquireDownloadFileLease(path: String): DownloadFileLease? =
    acquireJvmDownloadFileLease(path) { it.close() }

/** Per-call fault seam: no global native-operation replacement or other owner's handles. */
internal fun acquireJvmDownloadFileLease(path: String, release: (FileChannel) -> Unit): DownloadFileLease? = synchronized(DownloadLeaseRegistry) {
    val file = File(path).canonicalFile
    val key = file.path
    if (key in DownloadLeaseRegistry.held) return@synchronized null
    DownloadLeaseRegistry.held[key] = null // Reject aliases before opening another descriptor.
    val opened = try { FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE) }
    catch (error: Throwable) { DownloadLeaseRegistry.held.remove(key); throw error }
    val owner = JvmDownloadFileLease(key, opened, release)
    DownloadLeaseRegistry.held[key] = owner // Strong handle ownership before any fallible release.
    val lock = try { try { opened.tryLock() } catch (_: OverlappingFileLockException) { null } }
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

private class JvmDownloadFileLease(private val key: String, private val channel: FileChannel,
    private val release: (FileChannel) -> Unit) : DownloadFileLease {
    var fileLock: FileLock? = null
    private var consumed = false
    private var failure: dev.veyra.model.VeyraException? = null

    override fun close() = synchronized(DownloadLeaseRegistry) {
        failure?.let { throw it }
        if (consumed) return@synchronized
        try {
            // FileChannel.close releases its lock. One native close avoids a separate unlock
            // succeeding/failing ambiguously before another fallible descriptor operation.
            release(channel)
        } catch (_: Throwable) {
            val sticky = downloadLockReleaseFailure()
            failure = sticky
            // isOpen=false is NOT a consumption receipt after a throwing channel close.
            // Keep the actual channel/lock and canonical reservation; never retry this channel.
            throw sticky
        }
        consumed = true
        fileLock = null
        DownloadLeaseRegistry.held.remove(key)
        Unit
    }
}
