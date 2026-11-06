package dev.veyra.desktop.runtime

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale

/** Own this before opening Room, starting transfers, or creating any audio/session object. */
class DesktopProcessContext private constructor(
    val dataDirectory: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    private var closed = false // Protected by instanceGate, including acquisition/release.

    override fun close() = synchronized(instanceGate) {
        if (closed) return@synchronized
        closed = true
        try { if (lock.isValid) lock.release() }
        finally {
            // Keep the process registry reservation until every descriptor is closed. An old,
            // repeated close must never drop a newly acquired owner's POSIX record lock.
            // On an exceptional close, retain the reservation until process exit. A possibly
            // lingering descriptor must not later be GC-closed over a replacement owner's lock.
            channel.close()
            activeDirectories.remove(dataDirectory)
        }
        // Do not unlink a lock file: another process could hold the old inode while a third creates it.
    }

    fun ownedPath(candidate: Path): Path {
        val path = candidate.toAbsolutePath().normalize()
        require(path.startsWith(dataDirectory) && path != dataDirectory) { "Output must be inside Veyra's owned data directory" }
        rejectSymbolicAncestors(path)
        return path
    }

    companion object {
        private val instanceGate = Any()
        private val activeDirectories = mutableSetOf<Path>()

        fun defaultDirectory(environment: Map<String, String> = System.getenv()): Path {
            environment["VEYRA_DATA_DIR"]?.takeIf(String::isNotBlank)?.let { return Path.of(it).toAbsolutePath().normalize() }
            val home = Path.of(System.getProperty("user.home"))
            return when {
                System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows") ->
                    environment["LOCALAPPDATA"]?.takeIf(String::isNotBlank)?.let(Path::of)?.resolve("Veyra")
                        ?: home.resolve("AppData/Local/Veyra")
                System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("mac") -> home.resolve("Library/Application Support/Veyra")
                else -> environment["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() && Path.of(it).isAbsolute }?.let(Path::of)?.resolve("veyra")
                    ?: home.resolve(".local/share/veyra")
            }.toAbsolutePath().normalize()
        }

        fun acquire(directory: Path = defaultDirectory()): DesktopProcessContext {
            val requested = directory.toAbsolutePath().normalize()
            privateDirectory(requested)
            val root = requested.toRealPath(NOFOLLOW_LINKS)
            val path = root.resolve("instance.lock")
            rejectSymbolicAncestors(path)
            return synchronized(instanceGate) {
                // On POSIX, closing ANY descriptor for this inode releases this process's
                // fcntl locks, even after OverlappingFileLockException. Reject before open.
                if (!activeDirectories.add(root)) throw AlreadyRunningException()
                var channel: FileChannel? = null
                try {
                    val opened = FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS).also { channel = it }
                    val lock = try { opened.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (lock == null) throw AlreadyRunningException()
                    DesktopProcessContext(root, opened, lock)
                } catch (error: Throwable) {
                    try {
                        channel?.close()
                        activeDirectories.remove(root)
                    } catch (closeError: Throwable) { error.addSuppressed(closeError) }
                    throw error
                }
            }
        }

        internal fun privateDirectory(path: Path) {
            rejectSymbolicAncestors(path)
            Files.createDirectories(path)
            require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "Veyra directory is not a directory" }
            if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
            }
        }

        internal fun rejectSymbolicAncestors(path: Path) {
            var current: Path? = path.toAbsolutePath().normalize()
            while (current != null) {
                require(!Files.isSymbolicLink(current)) { "Veyra private paths must not contain symbolic links" }
                current = current.parent
            }
        }
    }
}

class AlreadyRunningException : IllegalStateException("Veyra is already running for this data directory")
