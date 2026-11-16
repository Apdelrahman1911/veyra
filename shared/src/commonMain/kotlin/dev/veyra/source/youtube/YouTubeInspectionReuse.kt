package dev.veyra.source.youtube

import dev.kmpyt.extraction.ExtractionReport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Short-lived, in-memory, single-use reuse of one public audio inspection per video.
 *
 * A report is reused only while every declared media URL in it remains valid for at least
 * [MIN_REMAINING_VALIDITY_MS] and the report is at most [MAX_AGE_MS] old. Each stored report can
 * be taken once; the next request inspects again. Renewal paths (an expired or rejected link)
 * always inspect fresh and drop the entry. Declared availability times stay inside the report
 * and are still awaited by kmpYT, so reuse never shortens a source-declared wait: it only avoids
 * starting a new one. Nothing here is persisted, and URLs never leave the process.
 */
internal class YouTubeInspectionReuse(private val nowEpochMs: () -> Long) {
    private class Entry(val report: ExtractionReport, val storedAtMs: Long)
    private val entries = LinkedHashMap<String, Entry>()
    private val lock = Mutex()

    suspend fun take(videoId: String): ExtractionReport? {
        val entry = lock.withLock { entries.remove(videoId) } ?: return null
        return entry.report.takeIf { reusable(it, entry.storedAtMs) }
    }

    suspend fun offer(videoId: String, report: ExtractionReport) {
        val now = nowEpochMs()
        if (!reusable(report, now)) return
        lock.withLock {
            entries.remove(videoId)
            entries[videoId] = Entry(report, now)
            while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        }
    }

    /** Non-consuming read, for metadata views that should leave the report for playback. */
    suspend fun peek(videoId: String): ExtractionReport? = lock.withLock { entries[videoId] }
        ?.takeIf { reusable(it.report, it.storedAtMs) }?.report

    suspend fun forget(videoId: String) { lock.withLock { entries.remove(videoId) } }

    suspend fun clear() = lock.withLock { entries.clear() }

    private fun reusable(report: ExtractionReport, storedAtMs: Long): Boolean {
        val now = nowEpochMs()
        if (now - storedAtMs !in 0..MAX_AGE_MS) return false
        if (report.sourceFailure() != null) return false
        val representations = report.media.flatMap { it.representations }.filter { it.url != null }
        if (representations.isEmpty()) return false
        // An unknown expiry cannot be proven valid; never reuse it.
        return representations.all { (it.expiresAtEpochMs ?: return false) - now >= MIN_REMAINING_VALIDITY_MS }
    }

    companion object {
        const val MAX_AGE_MS = 30L * 60 * 1000
        const val MIN_REMAINING_VALIDITY_MS = 20L * 60 * 1000
        const val MAX_ENTRIES = 8
    }
}

/**
 * An inspection closure whose first call may reuse a stored report, and whose later calls
 * (refreshes after an expired or rejected link) always inspect fresh.
 */
internal fun reuseFirstInspection(alreadySupplied: Boolean, inspect: suspend (fresh: Boolean) -> ExtractionReport):
    suspend () -> ExtractionReport {
    var calls = if (alreadySupplied) 1 else 0
    return { inspect(calls++ > 0) }
}

/** A declared availability wait being honored for [videoId]'s playback, until [availableAtEpochMs]. */
data class SourcePlaybackWait(val videoId: String, val availableAtEpochMs: Long)
