@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.veyra.platform.audio

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred

/**
 * Per-deck ownership of Media3 loader handles. stop()/clearMediaItems() mask state before the
 * loader's close() returns; that is not a sufficient local-file deletion acknowledgement.
 * Retirement rejects even an already-created loader's late open, then awaits real delegate closes.
 */
internal class AndroidDeckDataSources(private val delegate: DataSource.Factory) : DataSource.Factory {
    private val lock = Any()
    private val drained = CompletableDeferred<Unit>()
    private var retired = false
    private var openCount = 0
    private var closeFailed = false

    override fun createDataSource(): DataSource = Owned(delegate.createDataSource())

    fun retire() = synchronized(lock) {
        retired = true
        completeIfDrained()
    }

    suspend fun awaitClosed() {
        drained.await()
        if (synchronized(lock) { closeFailed }) throw IOException("Native media handle close failed")
    }

    private fun completeIfDrained() {
        if (retired && openCount == 0) drained.complete(Unit)
    }

    private inner class Owned(private val source: DataSource) : DataSource {
        private var leased = false

        override fun addTransferListener(transferListener: TransferListener) = source.addTransferListener(transferListener)
        override fun getUri(): Uri? = source.uri
        override fun getResponseHeaders(): Map<String, List<String>> = source.responseHeaders
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, length)

        override fun open(dataSpec: DataSpec): Long {
            synchronized(lock) {
                if (retired) throw IOException("Native media deck retired")
                check(!leased) { "Media source already open" }
                leased = true
                openCount++
            }
            return try { source.open(dataSpec) }
            catch (error: Exception) {
                // The Media3 contract also calls close after open failures. Our idempotent wrapper
                // releases a partially-open delegate now without losing the original failure.
                try { close() } catch (closing: Exception) { error.addSuppressed(closing) }
                throw error
            }
        }

        override fun close() {
            if (!synchronized(lock) { leased }) return
            var failed = false
            try { source.close() }
            catch (error: Exception) { failed = true; throw error }
            finally {
                synchronized(lock) {
                    // Media3 serializes each DataSource's read/open/close on its loader thread.
                    // Retirement is the only operation on the application thread.
                    if (leased) {
                        leased = false
                        openCount--
                    }
                    closeFailed = closeFailed || failed
                    completeIfDrained()
                }
            }
        }
    }
}
