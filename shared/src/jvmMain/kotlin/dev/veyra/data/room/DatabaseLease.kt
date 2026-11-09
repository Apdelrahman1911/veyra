package dev.veyra.data.room

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

/** Closing ANY descriptor for a POSIX process-lock inode may release the original holder's lock. */
private object DatabaseLeaseRegistry { val held = mutableSetOf<String>() }

internal actual fun acquireDatabaseLease(path: String): DatabaseLease = synchronized(DatabaseLeaseRegistry) {
    val file = File(path).canonicalFile
    val key = file.path
    // Check before opening, not after catching OverlappingFileLockException on a second fd.
    if (!DatabaseLeaseRegistry.held.add(key)) throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "Database is already open"))
    var channel: FileChannel? = null
    try {
        val acquired = retryWindowsDatabaseLease {
            val opened = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            channel = opened
            var retained = false
            try {
                val lock = try { opened.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) null else (opened to lock).also { retained = true }
            } finally {
                if (!retained) { opened.close(); channel = null }
            }
        } ?: throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "Database is already open"))
        val (opened, lock) = acquired
        object : DatabaseLease {
            private var closed = false
            override fun release() = synchronized(DatabaseLeaseRegistry) {
                if (closed) return@synchronized
                closed = true
                try { lock.release() } finally {
                    // A failed descriptor close keeps this path reserved until process exit.
                    opened.close()
                    DatabaseLeaseRegistry.held.remove(key)
                }
            }
        }
    } catch (error: Exception) {
        try { channel?.close(); DatabaseLeaseRegistry.held.remove(key) }
        catch (closeError: Exception) { error.addSuppressed(closeError) }
        throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "Database lease could not be acquired"))
            .also { it.initCause(error) }
    }
}
