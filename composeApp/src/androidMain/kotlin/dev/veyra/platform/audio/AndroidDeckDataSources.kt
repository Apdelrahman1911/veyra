@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.veyra.platform.audio

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred

/**
 * Per-deck ownership of Media3 loader handles. stop()/clearMediaItems() mask state before the
 * loader's close() returns; that is not a sufficient local-file deletion acknowledgement.
 * Retirement rejects even an already-created loader's late open, then awaits real delegate closes.
 */
internal class AndroidDeckDataSources(
    private val delegate: DataSource.Factory,
    private val contentLength: Long? = null,
    /** Largest single HTTP range for a googlevideo object; larger ranges are paced to ~bitrate. */
    private val sectionBytes: Long = PLAYBACK_SECTION_BYTES,
) : DataSource.Factory {
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

    /**
     * The exclusive end of a googlevideo read with a known object size, or null for any other
     * request (local files, other hosts, unknown size), which is passed through unchanged.
     */
    private fun objectEnd(request: DataSpec): Long? {
        val total = contentLength ?: return null
        if (request.position < 0 || total <= request.position) return null
        if (request.uri.scheme != "https") return null
        val host = request.uri.host?.lowercase() ?: return null
        if (host != "googlevideo.com" && !host.endsWith(".googlevideo.com")) return null
        // A caller's own bounded read that already fits one section keeps its exact DataSpec.
        if (request.length != C.LENGTH_UNSET.toLong() && request.length <= sectionBytes) return null
        val requested = if (request.length == C.LENGTH_UNSET.toLong()) total else request.position + request.length
        return minOf(requested, total)
    }

    /**
     * Media3 still sees one DataSpec with its full exact length. Underneath, a googlevideo object
     * is read as consecutive exact ranges of at most [sectionBytes], so every request stays in
     * the unpaced regime. URLs, headers and loader ownership are unchanged; the next section is
     * opened only when the previous one ended exactly at its declared end.
     */
    private inner class Owned(private val source: DataSource) : DataSource {
        private var leased = false
        private var base: DataSpec? = null
        private var position = 0L
        private var sectionEnd = 0L
        private var end = 0L

        override fun addTransferListener(transferListener: TransferListener) = source.addTransferListener(transferListener)
        override fun getUri(): Uri? = source.uri
        override fun getResponseHeaders(): Map<String, List<String>> = source.responseHeaders

        private fun openSection(request: DataSpec, at: Long) {
            sectionEnd = minOf(end, at + sectionBytes)
            val expected = sectionEnd - at
            val opened = source.open(request.buildUpon().setPosition(at).setLength(expected).build())
            if (opened != C.LENGTH_UNSET.toLong() && opened != expected) {
                throw IOException("The media server returned a different section length")
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val request = base ?: return source.read(buffer, offset, length)
            if (length == 0) return 0
            if (position >= end) return C.RESULT_END_OF_INPUT
            var count = source.read(buffer, offset, minOf(length.toLong(), sectionEnd - position).toInt())
            if (count == C.RESULT_END_OF_INPUT) {
                if (position < sectionEnd) throw IOException("A media section ended before its declared range")
                source.close()
                openSection(request, position)
                count = source.read(buffer, offset, minOf(length.toLong(), sectionEnd - position).toInt())
                if (count == C.RESULT_END_OF_INPUT) throw IOException("A media section returned no bytes")
            }
            if (count > 0) position += count
            return count
        }

        override fun open(dataSpec: DataSpec): Long {
            synchronized(lock) {
                if (retired) throw IOException("Native media deck retired")
                check(!leased) { "Media source already open" }
                leased = true
                openCount++
            }
            return try {
                val objectEnd = objectEnd(dataSpec)
                if (objectEnd == null) { base = null; source.open(dataSpec) }
                else {
                    base = dataSpec; position = dataSpec.position; end = objectEnd
                    openSection(dataSpec, position)
                    end - dataSpec.position
                }
            }
            catch (error: Exception) {
                // The Media3 contract also calls close after open failures. Our idempotent wrapper
                // releases a partially-open delegate now without losing the original failure.
                try { close() } catch (closing: Exception) { error.addSuppressed(closing) }
                throw error
            }
        }

        override fun close() {
            if (!synchronized(lock) { leased }) return
            base = null
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

/** Below the measured ~10–20 MiB range size above which googlevideo paces a single response. */
internal const val PLAYBACK_SECTION_BYTES = 8L * 1024 * 1024
