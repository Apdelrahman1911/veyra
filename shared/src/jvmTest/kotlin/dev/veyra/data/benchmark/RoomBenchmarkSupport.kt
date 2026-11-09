package dev.veyra.data.benchmark

import dev.veyra.model.AppSnapshot
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

internal object RoomBenchmarkLimits {
    const val HEAP_BYTES = 512L * 1024 * 1024
    const val DATA_BYTES = 512L * 1024 * 1024
    const val EVIDENCE_BYTES = 10L * 1024 * 1024
    const val EVENT_BYTES = 8L * 1024 * 1024 // Leave bounded room for the final summary.
    const val ADMISSION_NS = 300_000_000_000L
    const val CONTENTION_NS = 30_000_000_000L
    const val ISOLATED_P95_NS = 100_000_000L
    const val P99_NS = 500_000_000L
    const val WARMUPS = 10
    const val MEASURED = 50
}

/** Empirical nearest-rank percentiles: with 50 samples p99 is the maximum, not an SLA. */
internal data class RoomLatencyStats(val count: Int, val p50: Long, val p95: Long, val p99: Long, val maximum: Long) {
    companion object {
        fun of(values: List<Long>): RoomLatencyStats? {
            if (values.isEmpty()) return null
            require(values.all { it >= 0 })
            val sorted = values.sorted()
            fun rank(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceAtLeast(0)]
            return RoomLatencyStats(sorted.size, rank(.50), rank(.95), rank(.99), sorted.last())
        }
    }
}

internal class RoomAdmissionGate(private val start: Long, private val now: () -> Long = System::nanoTime) {
    data class Stop(val reason: String, val atNs: Long)
    private val stopped = AtomicReference<Stop?>()
    val stop: Stop? get() = stopped.get()
    fun requestStop(reason: String) { stopped.compareAndSet(null, Stop(reason, now())) }
    fun admit(): Boolean {
        if (now() - start >= RoomBenchmarkLimits.ADMISSION_NS) requestStop("admission_time_limit")
        return stop == null
    }
}

/** One missed slot includes a skipped start OR a started write completing after that slot's deadline. */
internal class RoomDeadlineStreak {
    var consecutive = 0
        private set
    var maximum = 0
        private set
    fun record(missed: Boolean): Boolean {
        consecutive = if (missed) consecutive + 1 else 0
        maximum = maxOf(maximum, consecutive)
        return consecutive >= 3
    }
}

/** Concurrent facade callers may resume out of commit order; never keep merely the last callback. */
internal class RoomExpectedSnapshot(initial: AppSnapshot) {
    private val latest = AtomicReference(initial)
    fun get(): AppSnapshot = latest.get()
    fun committed(snapshot: AppSnapshot) {
        while (true) {
            val old = latest.get()
            if (snapshot.revision <= old.revision) return
            if (latest.compareAndSet(old, snapshot)) return
        }
    }
}

internal data class RoomBenchmarkOperation(
    val id: Int,
    val size: Int,
    val phase: String,
    val workload: String,
    val producer: Int,
    val ordinal: Int,
    val measured: Boolean,
    val scheduledNs: Long?,
    val deadlineNs: Long?,
    val startedNs: Long,
    val transformNs: Long?,
    val finishedNs: Long,
    val beforeRevision: Long?,
    val afterRevision: Long?,
    val outcome: String,
) {
    val facadeNs get() = finishedNs - startedNs
    val startLatenessNs get() = scheduledNs?.let { (startedNs - it).coerceAtLeast(0) }
    val completionLatenessNs get() = deadlineNs?.let { (finishedNs - it).coerceAtLeast(0) }
}

internal data class RoomResourceSample(
    val heapUsed: Long, val heapCommitted: Long, val heapMax: Long,
    val gcCount: Long, val gcTimeMs: Long, val rssBytes: Long?, val peakRssBytes: Long?,
) {
    companion object {
        fun read(): RoomResourceSample {
            val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
            val collectors = ManagementFactory.getGarbageCollectorMXBeans()
            // Linux-only read; missing/non-Linux RSS is explicit rather than guessed from heap.
            val status = runCatching { Files.readAllLines(Path.of("/proc/self/status")) }.getOrDefault(emptyList())
            fun kib(key: String): Long? = status.firstOrNull { it.startsWith("$key:") }
                ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.times(1024)
            return RoomResourceSample(heap.used, heap.committed, heap.max,
                collectors.map { it.collectionCount }.filter { it >= 0 }.sum(),
                collectors.map { it.collectionTime }.filter { it >= 0 }.sum(), kib("VmRSS"), kib("VmHWM"))
        }
    }
}

/** Appends bounded, authored-only rows immediately; no filesystem sync is added to measured writes. */
internal class RoomBenchmarkEvidence(val directory: Path, private val gate: RoomAdmissionGate) : AutoCloseable {
    private val outputs = linkedMapOf<String, OutputStream>()
    private var eventBytes = 0L
    private var summaryWritten = false
    val dataDirectory: Path = Files.createDirectory(directory.resolve("data"))

    @Synchronized fun csv(name: String, header: List<String>, values: List<Any?>) {
        require(name.matches(Regex("[a-z-]+")) && header.size == values.size)
        val row = csvRow(values)
        val prefix = if (name in outputs) ByteArray(0) else csvRow(header)
        if (eventBytes + row.size + prefix.size > RoomBenchmarkLimits.EVENT_BYTES) {
            gate.requestStop("evidence_event_limit")
            return
        }
        val output = outputs.getOrPut(name) {
            Files.newOutputStream(directory.resolve("$name.csv"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }
        if (prefix.isNotEmpty()) output.write(prefix)
        output.write(row)
        output.flush()
        eventBytes += row.size + prefix.size
    }

    @Synchronized fun finalSummary(json: String) {
        check(!summaryWritten)
        val bytes = (json + "\n").toByteArray(Charsets.UTF_8)
        check(eventBytes + bytes.size <= RoomBenchmarkLimits.EVIDENCE_BYTES)
        Files.write(directory.resolve("summary.json"), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        summaryWritten = true
    }

    fun dataBytes(): Long = Files.walk(dataDirectory).use { paths ->
        paths.filter { Files.isRegularFile(it) }.mapToLong(::roomBenchmarkFileSize).sum()
    }

    @Synchronized override fun close() {
        var failure: Throwable? = null
        outputs.values.forEach { output ->
            try { output.close() }
            catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) }
        }
        outputs.clear()
        failure?.let { throw it }
    }

    private fun csvRow(values: List<Any?>): ByteArray = (values.joinToString(",") { value ->
        "\"" + (value?.toString() ?: "").replace("\"", "\"\"") + "\""
    } + "\n").toByteArray(Charsets.UTF_8)
}

// SQLite can remove its WAL/SHM between a sampled directory listing and stat during clean close.
internal fun roomBenchmarkFileSize(path: Path): Long = try { Files.size(path) }
catch (_: NoSuchFileException) { 0 }
