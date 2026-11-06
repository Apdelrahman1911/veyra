package dev.veyra.downloads

/**
 * An operating-system execution allowance, not a media transfer implementation.
 * The coordinator remains the sole caller of kmpYT. Expiration must checkpoint/pause
 * that coordinator; a worker must never start a second independent downloader.
 */
interface DownloadExecutionGate {
    suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease
}

fun interface DownloadExecutionLease {
    suspend fun close()
}

/** Desktop foreground/lifetime scheduling; closing the process still checkpoints work. */
object ProcessLifetimeDownloadGate : DownloadExecutionGate {
    override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease =
        DownloadExecutionLease { }
}
