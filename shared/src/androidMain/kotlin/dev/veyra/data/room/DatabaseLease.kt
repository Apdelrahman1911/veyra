package dev.veyra.data.room

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

/** Reject before open: closing any fd for a POSIX process-lock inode can release its owner's lock. */
private object DatabaseLeaseRegistry { val held = mutableSetOf<String>() }

internal actual fun acquireDatabaseLease(path: String): DatabaseLease = synchronized(DatabaseLeaseRegistry) {
    val key = File(path).canonicalPath
    if (!DatabaseLeaseRegistry.held.add(key)) throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "Database is already open"))
    var file: RandomAccessFile? = null
    try {
        val opened = RandomAccessFile(key, "rw")
        file = opened
        val lock = try { opened.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
        if (lock == null) throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "Database is already open"))
        object : DatabaseLease {
            private var closed = false
            override fun release() = synchronized(DatabaseLeaseRegistry) {
                if (closed) return@synchronized
                closed = true
                try { lock.release() } finally {
                    opened.close()
                    DatabaseLeaseRegistry.held.remove(key)
                }
            }
        }
    } catch (error: Exception) {
        try { file?.close(); DatabaseLeaseRegistry.held.remove(key) }
        catch (closeError: Exception) { error.addSuppressed(closeError) }
        throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "Database lease could not be acquired"))
    }
}

internal actual fun <T> withWalRecoveryRetry(read: () -> T): T = read()
