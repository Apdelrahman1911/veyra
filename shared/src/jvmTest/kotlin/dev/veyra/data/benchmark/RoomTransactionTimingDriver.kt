package dev.veyra.data.benchmark

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Test instrumentation only: delegates unchanged SQL to the actual bundled driver. */
internal class RoomTransactionTimingDriver(
    private val delegate: SQLiteDriver,
    private val now: () -> Long = System::nanoTime,
) : SQLiteDriver by delegate {
    data class Timing(val connection: Int, val preparedAt: Long, val stepStartedAt: Long,
        val acquiredAt: Long, val finishedAt: Long, val outcome: String) {
        val beginStepNs get() = acquiredAt - stepStartedAt
        val holdNs get() = finishedAt - acquiredAt
    }
    private val connections = AtomicInteger()
    private val timings = ConcurrentLinkedQueue<Timing>()
    private val count = AtomicInteger()
    val overflow = AtomicInteger()
    val failedBegins = AtomicInteger()
    val failedEnds = AtomicInteger()
    val displacedTransactions = AtomicInteger()
    val failedCloses = AtomicInteger()
    fun snapshot(): List<Timing> = timings.toList()

    override fun open(fileName: String): SQLiteConnection {
        val real = delegate.open(fileName)
        val id = connections.incrementAndGet()
        return object : SQLiteConnection by real {
            private var active: Timing? = null
            override fun prepare(sql: String): SQLiteStatement {
                // Room2.8.5 emits these exact statements. No per-query trim/case conversion,
                // timer call or statement wrapper is added to ordinary SELECT/INSERT SQL.
                val command = when (sql) {
                    "BEGIN IMMEDIATE TRANSACTION", "BEGIN IMMEDIATE" -> "BEGIN"
                    "COMMIT", "COMMIT TRANSACTION", "END", "END TRANSACTION" -> "COMMIT"
                    "ROLLBACK", "ROLLBACK TRANSACTION" -> "ROLLBACK"
                    else -> return real.prepare(sql)
                }
                val prepared = now()
                val statement = real.prepare(sql)
                val begin = command == "BEGIN"
                return object : SQLiteStatement by statement {
                    override fun step(): Boolean {
                        val start = now()
                        val hasRow = try { statement.step() }
                        catch (failure: Throwable) {
                            if (begin) failedBegins.incrementAndGet() else failedEnds.incrementAndGet()
                            // Failed COMMIT leaves the active transaction open; never record a release.
                            throw failure
                        }
                        val finished = now()
                        if (begin) {
                            active?.let {
                                displacedTransactions.incrementAndGet()
                                record(it.copy(finishedAt = finished, outcome = "DISPLACED"))
                            }
                            active = Timing(id, prepared, start, finished, finished, "OPEN")
                        } else active?.let { record(it.copy(finishedAt = finished, outcome = command)); active = null }
                        return hasRow
                    }
                }
            }

            override fun close() {
                try { real.close() }
                catch (failure: Throwable) { failedCloses.incrementAndGet(); throw failure }
                active?.let { record(it.copy(finishedAt = now(), outcome = "CONNECTION_CLOSED")); active = null }
            }
        }
    }

    private fun record(timing: Timing) {
        if (count.incrementAndGet() <= 20_000) timings.add(timing) else overflow.incrementAndGet()
    }
}
