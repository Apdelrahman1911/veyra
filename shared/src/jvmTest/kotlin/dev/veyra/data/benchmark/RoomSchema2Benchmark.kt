package dev.veyra.data.benchmark

import androidx.room.*
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.veyra.data.LibraryStore
import dev.veyra.data.StoreStatus
import dev.veyra.data.getOrThrow
import dev.veyra.data.room.MIGRATION_1_2
import dev.veyra.data.room.VeyraDatabase
import dev.veyra.data.room.VeyraDatabaseConstructor
import dev.veyra.model.AppSnapshot
import dev.veyra.model.VeyraException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

/** Opt-in main, deliberately NOT a JUnit test. Root must authorize/freeze a bounded process first. */
object RoomSchema2Benchmark {
    @JvmStatic fun main(arguments: Array<String>) {
        val code = try {
            val config = RoomBenchmarkConfig.parse(arguments)
            val root = Path.of(requireNotNull(System.getenv("VEYRA_PROJECT_ROOT"))).toRealPath()
            check(System.getenv("VEYRA_SCOPED_ENV") == "1") { "scoped_environment_required" }
            val work = root.resolve(".work").toRealPath()
            check(work.startsWith(root)) { "scoped_work_directory_required" }
            for (property in listOf("user.home", "java.io.tmpdir")) {
                check(Path.of(System.getProperty(property)).toRealPath().startsWith(work)) { "scoped_jvm_paths_required" }
            }
            check(Runtime.getRuntime().maxMemory() <= RoomBenchmarkLimits.HEAP_BYTES) { "heap_limit_required" }
            check(Runtime.getRuntime().availableProcessors() in 1..2) { "two_cpu_limit_required" }
            check(VeyraDatabase.VERSION == 2 && AppSnapshot.CURRENT_SCHEMA == 2) { "schema2_only" }
            check(Files.readString(root.resolve("gradle/kmpyt.commit")).trim() == BASELINE_PIN) { "baseline_pin_changed" }
            val parent = work.resolve("architecture/room-benchmark")
            // Check before mkdir: a pre-existing escaping symlink may not redirect output.
            var ancestor: Path? = parent
            while (ancestor != null && ancestor != work) {
                check(!Files.isSymbolicLink(ancestor)) { "symlink_output_ancestor" }
                ancestor = ancestor.parent
            }
            Files.createDirectories(parent)
            check(parent.toRealPath().startsWith(work))
            val directory = Files.createDirectory(parent.resolve(config.runId)) // Never overwrite an earlier run.
            val start = System.nanoTime()
            val gate = RoomAdmissionGate(start)
            RoomBenchmarkEvidence(directory, gate).use { evidence ->
                runBlocking { RoomBenchmarkRun(config, evidence, gate, start).execute() }
            }
        } catch (failure: Throwable) {
            // No credentials, arbitrary exception message, SQL values, or JVM option dump.
            System.err.println("room_schema2_benchmark_incomplete:${failure.javaClass.simpleName}")
            3
        }
        exitProcess(code)
    }

    internal const val BASELINE_PIN = "c0a08090fe7dbd76e20216559b8184ad2cccde63"
}

internal data class RoomBenchmarkConfig(val runId: String, val driver: String, val profile: String, val inputSha256: String) {
    companion object {
        fun parse(arguments: Array<String>): RoomBenchmarkConfig {
            require(arguments.size % 2 == 0)
            val options = linkedMapOf<String, String>()
            arguments.toList().chunked(2).forEach { (key, value) ->
                require(key in setOf("--run-id", "--driver", "--profile", "--input-sha256") && key !in options)
                options[key] = value
            }
            val runId = requireNotNull(options["--run-id"])
            require(runId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,70}")))
            val driver = options["--driver"] ?: "bare"
            val profile = options["--profile"] ?: "full"
            require(driver in setOf("bare", "timed") && profile in setOf("full", "control"))
            val seal = requireNotNull(options["--input-sha256"])
            require(seal.matches(Regex("[a-f0-9]{64}")))
            return RoomBenchmarkConfig(runId, driver, profile, seal)
        }
    }
}

/** This snapshot precedes Evidence.close and process exit; neither is established by the JSON. */
internal fun roomBenchmarkProvisionalStatus(code: Int): JsonObject {
    require(code in 0..3)
    return buildJsonObject {
        put("summary_provisional", true)
        put("provisional_result", when (code) { 0 -> "completed"; 1 -> "failed"; 2 -> "stopped_trigger_drained"; else -> "incomplete_cleanup" })
        put("provisional_exit_code", code)
        put("evidence_stream_close_confirmed", false)
        put("process_exit_confirmed", false)
    }
}

private class RoomBenchmarkRun(
    private val config: RoomBenchmarkConfig,
    private val evidence: RoomBenchmarkEvidence,
    private val gate: RoomAdmissionGate,
    private val origin: Long,
) {
    private data class Handle(val path: Path, val database: VeyraDatabase, val store: LibraryStore, var closeAttempted: Boolean = false)
    private val timing = if (config.driver == "timed") RoomTransactionTimingDriver(BundledSQLiteDriver()) else null
    private val operations = ConcurrentLinkedQueue<RoomBenchmarkOperation>()
    private val failures = ConcurrentLinkedQueue<String>()
    private val operationIds = AtomicInteger()
    private val peakHeap = AtomicLong()
    private val peakRss = AtomicLong()
    private val completeShapes = mutableListOf<Int>()
    private var current: Handle? = null
    private var expected = RoomExpectedSnapshot(AppSnapshot())
    private var currentSize = 0
    private var reopenChecks = 0
    private var closedStores = 0
    private var cleanupFailed = false
    private val startedUtc = Instant.now().toString()
    private val firstResources = RoomResourceSample.read()

    suspend fun execute(): Int = coroutineScope {
        val monitor = launch(Dispatchers.IO) {
            while (isActive) {
                try { sampleResources("periodic"); gate.admit() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Throwable) { recordFailure("resource_sampling", failure) }
                delay(250)
            }
        }
        try {
            for (size in if (config.profile == "control") listOf(1_000) else listOf(1_000, 10_000)) {
                if (!gate.admit()) break
                runShape(size)
                if (!gate.admit()) break
            }
        } catch (failure: Throwable) {
            recordFailure("run", failure)
        } finally {
            // Admission cancellation is never advertised as rollback/drain of NonCancellable writes.
            // All producer children are awaited by runContention before reaching this block.
            withContext(NonCancellable) {
                current?.takeIf { !it.closeAttempted }?.let { handle ->
                    try { check(handle.store.flush().getOrThrow() == expected.get()) { "final_flush_mismatch" } }
                    catch (failure: Throwable) { recordFailure("final_flush", failure) }
                    closeCurrent()
                }
                monitor.cancelAndJoin()
            }
        }
        try { exportTimings(); sampleResources("finished") }
        catch (failure: Throwable) { recordFailure("final_evidence", failure) }
        val requiredShapes = if (config.profile == "control") listOf(1_000) else listOf(1_000, 10_000)
        val code = when {
            cleanupFailed || current != null -> 3
            failures.isNotEmpty() -> 1
            gate.stop != null || completeShapes != requiredShapes -> 2
            else -> 0
        }
        val lastResources = RoomResourceSample.read()
        evidence.finalSummary(buildJsonObject {
            put("format", "veyra-room-schema2-benchmark-v2")
            roomBenchmarkProvisionalStatus(code).forEach { (key, value) -> put(key, value) }
            put("driver", config.driver); put("profile", config.profile)
            put("fixture_seed", RoomSchema2Fixture.SEED)
            put("baseline_worktree_commit", "9cca1286d626a68d1cb7c138ebe7c5afcacf1b67")
            put("baseline_pin", RoomSchema2Benchmark.BASELINE_PIN)
            put("external_input_manifest_sha256", config.inputSha256)
            put("external_before_after_hash_and_process_audit_required", true)
            put("production_preopen_fk_wrapper_used", false)
            put("real_room_graph_guards_and_observer_used", true)
            put("started_utc", startedUtc); put("elapsed_ns", System.nanoTime() - origin)
            put("java_version", System.getProperty("java.version"))
            put("java_vm", System.getProperty("java.vm.name"))
            put("os_name", System.getProperty("os.name")); put("os_arch", System.getProperty("os.arch"))
            put("available_processors", Runtime.getRuntime().availableProcessors())
            put("max_heap_bytes", Runtime.getRuntime().maxMemory())
            put("peak_sampled_heap_bytes", peakHeap.get())
            if (peakRss.get() == 0L) put("peak_sampled_rss_bytes", JsonNull) else put("peak_sampled_rss_bytes", peakRss.get())
            put("gc_count_delta", lastResources.gcCount - firstResources.gcCount)
            put("gc_time_ms_delta", lastResources.gcTimeMs - firstResources.gcTimeMs)
            put("data_bytes", evidence.dataBytes()); put("verified_reopens", reopenChecks)
            put("store_close_calls_returned", closedStores); put("in_process_store_cleanup_confirmed", !cleanupFailed && current == null)
            put("stop_reason", gate.stop?.reason ?: "none")
            put("stop_at_ns", gate.stop?.let { it.atNs - origin } ?: -1)
            putJsonArray("complete_shapes") { completeShapes.forEach { add(it) } }
            putJsonArray("failures") { failures.forEach { add(it) } }
            putJsonArray("latencies") {
                operations.filter { it.measured && it.outcome == "COMMITTED" }.groupBy { it.size to it.phase }.forEach { (key, rows) ->
                    val stats = requireNotNull(RoomLatencyStats.of(rows.map { it.facadeNs }))
                    add(buildJsonObject {
                        put("media_count", key.first); put("phase", key.second); put("samples", stats.count)
                        put("p50_ns", stats.p50); put("p95_ns", stats.p95); put("p99_ns", stats.p99); put("max_ns", stats.maximum)
                        val span = rows.maxOf { it.finishedNs } - rows.minOf { it.startedNs }
                        put("completed_per_wall_second", rows.size * 1_000_000_000.0 / span.coerceAtLeast(1))
                    })
                }
            }
            put("interpretation", "nearest-rank empirical tails; warm cache; shared-host noise; no platform SLA, native media, or production migration claim")
        }.toString())
        println("room_schema2_benchmark_provisional_exit=$code result_directory=${evidence.directory}")
        code
    }

    private suspend fun runShape(size: Int) {
        currentSize = size
        val fixture = RoomSchema2Fixture.create(size)
        if (!gate.admit()) return
        expected = RoomExpectedSnapshot(AppSnapshot())
        current = openBaseline(evidence.dataDirectory.resolve("library-$size.db"))
        check(requireNotNull(current).store.initialize().getOrThrow() == AppSnapshot()) { "nonempty_fresh_fixture" }
        perform("seed", "seed", 0, 0, measured = false) { fixture }
        checkpoint("seed")
        val workloads = listOf("playback_checkpoint", "settings", "queue_order", "download_heartbeat")
        var completedIsolated = 0
        for (workload in workloads) {
            if (!gate.admit()) break
            val phase = "isolated_$workload"
            for (index in 0 until RoomBenchmarkLimits.WARMUPS + RoomBenchmarkLimits.MEASURED) {
                if (!gate.admit()) break
                perform(phase, workload, 0, index, measured = index >= RoomBenchmarkLimits.WARMUPS)
            }
            checkpoint(phase)
            val rows = operations.filter { it.size == size && it.phase == phase && it.measured && it.outcome == "COMMITTED" }
            val stats = RoomLatencyStats.of(rows.map { it.facadeNs })
            if (stats?.count == RoomBenchmarkLimits.MEASURED) {
                completedIsolated++
                if (stats.p95 > RoomBenchmarkLimits.ISOLATED_P95_NS) gate.requestStop("isolated_p95_over_100ms")
                if (stats.p99 > RoomBenchmarkLimits.P99_NS) gate.requestStop("isolated_p99_over_500ms")
            }
            sampleResources(phase)
        }
        if (completedIsolated == workloads.size && gate.admit()) {
            if (config.profile == "full") {
                runContention()
                checkpoint("contention")
            }
            if (gate.admit()) completeShapes += size
        }
        // Completion/revision consistency includes warmups and seed, never just measured samples.
        check(expected.get().revision == operations.count { it.size == size && it.outcome == "COMMITTED" }.toLong()) {
            "committed_revision_count_mismatch"
        }
        check(closeCurrent()) { "shape_close_failed" }
    }

    private fun openBaseline(path: Path): Handle {
        // Deliberately replicate the OLD builder. Do not call buildVeyraDatabase here: its new
        // pre-open FK prerequisite is a separate experiment and is NOT the schema2 baseline.
        val database = Room.databaseBuilder<VeyraDatabase>(path.toString(), factory = VeyraDatabaseConstructor::initialize)
            .setDriver(timing ?: BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(MIGRATION_1_2)
            .build()
        return Handle(path, database, LibraryStore(database))
    }

    private suspend fun perform(
        phase: String, workload: String, producer: Int, ordinal: Int, measured: Boolean,
        scheduled: Long? = null, deadline: Long? = null,
        transform: ((AppSnapshot) -> AppSnapshot)? = null,
    ): RoomBenchmarkOperation {
        var before: Long? = null
        var intended: AppSnapshot? = null
        var transformAt: Long? = null
        var after: Long? = null
        var finished: Long? = null
        var outcome = "FAILED"
        val started = System.nanoTime()
        try {
            val result = requireNotNull(current).store.update { old ->
                transformAt = System.nanoTime()
                before = old.revision
                val next = transform?.invoke(old) ?: RoomSchema2Fixture.change(old, workload, producer)
                check(next.revision == old.revision)
                intended = next.copy(schemaVersion = AppSnapshot.CURRENT_SCHEMA, revision = old.revision + 1)
                next
            }
            finished = System.nanoTime() // End facade timer before benchmark-side equality/logging.
            val actual = result.getOrThrow()
            after = actual.revision
            val wanted = requireNotNull(intended)
            check(actual.revision == requireNotNull(before) + 1) { "no_op_or_revision_mismatch" }
            check(actual == wanted) { "intended_graph_mismatch" }
            expected.committed(wanted)
            outcome = "COMMITTED"
        } catch (failure: Throwable) {
            if (finished == null) finished = System.nanoTime()
            recordFailure("operation_$workload", failure)
        }
        val operation = RoomBenchmarkOperation(operationIds.incrementAndGet(), currentSize, phase, workload, producer, ordinal,
            measured, scheduled, deadline, started, transformAt, requireNotNull(finished), before, after, outcome)
        operations.add(operation)
        evidence.csv("operations", OPERATION_HEADER, listOf(operation.id, operation.size, phase, workload, producer, ordinal,
            measured, relative(scheduled), relative(deadline), relative(started), relative(transformAt), relative(finished),
            operation.facadeNs, operation.startLatenessNs, operation.completionLatenessNs, before, after, outcome))
        return operation
    }

    private suspend fun runContention() = coroutineScope {
        data class Producer(val workload: String, val index: Int, val period: Long)
        val producers = (0 until 4).map { Producer("progress_history", it, 500_000_000) } +
            (0 until 4).map { Producer("download_heartbeat", it, 1_000_000_000) } +
            listOf(Producer("playback_checkpoint", 0, 5_000_000_000), Producer("settings", 0, 1_000_000_000),
                Producer("queue_order", 0, 5_000_000_000))
        val start = System.nanoTime()
        val end = start + RoomBenchmarkLimits.CONTENTION_NS
        val completed = AtomicInteger()
        producers.map { producer -> launch {
            val slots = (RoomBenchmarkLimits.CONTENTION_NS / producer.period).toInt()
            val states = MutableList(slots) { "UNSEEN" }
            val streak = RoomDeadlineStreak()
            var startedCount = 0
            var completedCount = 0
            var skippedCount = 0
            var lateCount = 0
            for (slot in 0 until slots) {
                val scheduled = start + slot * producer.period
                val deadline = scheduled + producer.period
                var state: String
                var operation: RoomBenchmarkOperation? = null
                if (!gate.admit()) state = "NOT_ADMITTED_STOP"
                else {
                    delayUntil(scheduled)
                    if (!gate.admit()) state = "NOT_ADMITTED_STOP"
                    else if (System.nanoTime() >= deadline) {
                        state = "MISSED_START"
                        skippedCount++
                        if (streak.record(true)) gate.requestStop("three_consecutive_producer_deadline_misses")
                    } else {
                        startedCount++
                        operation = perform("contention", producer.workload, producer.index, slot, true, scheduled, deadline)
                        state = operation.outcome
                        if (operation.outcome == "COMMITTED") completedCount++
                        val late = requireNotNull(operation.completionLatenessNs) > 0
                        if (late) lateCount++
                        if (streak.record(late)) gate.requestStop("three_consecutive_producer_deadline_misses")
                        // Evaluate the empirical tail only at 50-sample boundaries, not a purported
                        // stable percentile from one warmup. Deadline streaks are checked per slot.
                        if (operation.outcome == "COMMITTED" && completed.incrementAndGet() % 50 == 0) {
                            val latencies = operations.filter { it.size == currentSize && it.phase == "contention" && it.outcome == "COMMITTED" }
                            if (requireNotNull(RoomLatencyStats.of(latencies.map { it.facadeNs })).p99 > RoomBenchmarkLimits.P99_NS)
                                gate.requestStop("contention_p99_over_500ms")
                        }
                    }
                }
                states[slot] = state
                evidence.csv("schedule", SCHEDULE_HEADER, listOf(currentSize, producer.workload, producer.index, slot,
                    relative(scheduled), relative(deadline), state, operation?.id))
            }
            val stopAt = minOf(gate.stop?.atNs ?: end, end)
            val offered = (0 until slots).count { start + it * producer.period < stopAt }
            val dueButStopped = (0 until slots).count {
                start + it * producer.period < stopAt && states[it] == "NOT_ADMITTED_STOP"
            }
            evidence.csv("producers", PRODUCER_HEADER, listOf(currentSize, producer.workload, producer.index, producer.period,
                slots, offered, startedCount, completedCount, skippedCount, dueButStopped, lateCount, streak.maximum))
        } }.joinAll() // No cancellations, detached writes, catch-up queue, or more than one call/producer.
        if (gate.admit()) delayUntil(end)
        val drained = System.nanoTime()
        val admissionsEnd = minOf(gate.stop?.atNs ?: end, end)
        evidence.csv("drain", listOf("media_count", "phase", "admissions_end_ns", "children_joined_ns", "drain_ns"),
            listOf(currentSize, "contention", relative(admissionsEnd), relative(drained), (drained - admissionsEnd).coerceAtLeast(0)))
        val all = operations.filter { it.size == currentSize && it.phase == "contention" && it.outcome == "COMMITTED" }
        if ((RoomLatencyStats.of(all.map { it.facadeNs })?.p99 ?: 0) > RoomBenchmarkLimits.P99_NS)
            gate.requestStop("contention_p99_over_500ms")
    }

    private suspend fun delayUntil(target: Long) {
        while (true) {
            val remaining = target - System.nanoTime()
            if (remaining <= 0) return
            if (!gate.admit()) return
            // Short bounded sleeps let a global trigger stop other producers without cancellation.
            delay(minOf(100L, (remaining + 999_999) / 1_000_000))
        }
    }

    private suspend fun checkpoint(phase: String) {
        val handle = requireNotNull(current)
        val wanted = expected.get()
        check(handle.store.flush().getOrThrow() == wanted) { "flush_graph_mismatch" }
        check(handle.store.lastFailure.value == null && handle.store.state.value == wanted) { "facade_projection_mismatch" }
        val flags = handle.database.useWriterConnection { connection ->
            val version = connection.usePrepared("PRAGMA user_version") { check(it.step()); it.getLong(0) }
            val sync = connection.usePrepared("PRAGMA synchronous") { check(it.step()); it.getLong(0) }
            val foreignKeys = connection.usePrepared("PRAGMA foreign_keys") { check(it.step()); it.getLong(0) }
            val journal = connection.usePrepared("PRAGMA journal_mode") { check(it.step()); it.getText(0) }
            listOf(version.toString(), sync.toString(), foreignKeys.toString(), journal)
        }
        evidence.csv("database-flags", listOf("media_count", "phase", "schema", "writer_synchronous", "writer_foreign_keys", "journal"),
            listOf(currentSize, phase) + flags)
        check(flags == listOf("2", "2", "1", "wal")) { "baseline_sqlite_flags_mismatch" }
        handle.database.useReaderConnection { connection -> connection.deferredTransaction {
            val tables = connection.usePrepared("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name") { statement ->
                buildList { while (statement.step()) add(statement.getText(0)) }
            }
            for (table in tables) {
                check(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
                val count = connection.usePrepared("SELECT count(*) FROM \"$table\"") { check(it.step()); it.getLong(0) }
                evidence.csv("table-counts", listOf("media_count", "phase", "table", "rows"), listOf(currentSize, phase, table, count))
            }
            check(!connection.usePrepared("PRAGMA foreign_key_check") { it.step() }) { "foreign_key_check_failed" }
        } }
        recordDatabaseSizes(phase, handle.path)
        check(closeCurrent()) { "checkpoint_close_failed" }
        current = openBaseline(handle.path)
        check(requireNotNull(current).store.initialize().getOrThrow() == wanted) { "reopen_graph_mismatch" }
        reopenChecks++
        evidence.csv("reopen", listOf("media_count", "phase", "intended_revision", "exact_graph_equal", "at_ns"),
            listOf(currentSize, phase, wanted.revision, true, relative(System.nanoTime())))
    }

    private suspend fun closeCurrent(): Boolean {
        val handle = current ?: return true
        if (handle.closeAttempted) return false // Never retry an unproven failed close implicitly.
        handle.closeAttempted = true
        val start = System.nanoTime()
        return try {
            handle.store.close()
            check(handle.store.status.value == StoreStatus.Closed)
            current = null
            closedStores++
            evidence.csv("close", listOf("media_count", "started_ns", "returned_ns", "store_closed"),
                listOf(currentSize, relative(start), relative(System.nanoTime()), true))
            true
        } catch (failure: Throwable) {
            cleanupFailed = true
            recordFailure("store_close", failure)
            false
        }
    }

    private fun sampleResources(phase: String) {
        val sample = RoomResourceSample.read()
        peakHeap.accumulateAndGet(sample.heapUsed) { a, b -> maxOf(a, b) }
        sample.rssBytes?.let { peakRss.accumulateAndGet(it) { a, b -> maxOf(a, b) } }
        val bytes = evidence.dataBytes()
        if (bytes > RoomBenchmarkLimits.DATA_BYTES) gate.requestStop("database_file_limit")
        evidence.csv("resources", listOf("at_ns", "phase", "heap_used", "heap_committed", "heap_max", "gc_count", "gc_ms", "rss_bytes", "os_peak_rss_bytes", "data_bytes"),
            listOf(relative(System.nanoTime()), phase, sample.heapUsed, sample.heapCommitted, sample.heapMax, sample.gcCount,
                sample.gcTimeMs, sample.rssBytes, sample.peakRssBytes, bytes))
    }

    private fun recordDatabaseSizes(phase: String, path: Path) {
        for (suffix in listOf("", "-wal", "-shm")) {
            val file = path.resolveSibling(path.fileName.toString() + suffix)
            evidence.csv("database-files", listOf("media_count", "phase", "file", "bytes"),
                listOf(currentSize, phase, file.fileName, roomBenchmarkFileSize(file)))
        }
    }

    private fun exportTimings() {
        val driver = timing ?: return
        val events = driver.snapshot()
        events.forEachIndexed { index, event ->
            evidence.csv("transactions", listOf("transaction_id", "connection", "prepare_ns", "step_start_ns", "acquired_ns", "finished_ns", "begin_step_ns", "hold_ns", "outcome"),
                listOf(index, event.connection, relative(event.preparedAt), relative(event.stepStartedAt), relative(event.acquiredAt),
                    relative(event.finishedAt), event.beginStepNs, event.holdNs, event.outcome))
        }
        for (operation in operations) {
            val point = operation.transformNs ?: continue
            val matches = events.withIndex().filter { (_, event) -> point >= event.acquiredAt && point <= event.finishedAt }
            if (operation.outcome == "COMMITTED") {
                check(matches.size == 1 && matches.single().value.outcome == "COMMIT") { "transaction_correlation_failed" }
            }
            val match = matches.singleOrNull() ?: continue
            val event = match.value
            val interval = event.finishedAt - event.stepStartedAt
            check(event.stepStartedAt >= operation.startedNs && event.finishedAt <= operation.finishedNs)
            evidence.csv("timing-links", listOf("operation_id", "transaction_id", "facade_ns", "begin_step_ns", "hold_ns", "outside_transaction_aggregate_ns", "pre_begin_aggregate_ns", "post_commit_aggregate_ns"),
                listOf(operation.id, match.index, operation.facadeNs, event.beginStepNs, event.holdNs,
                    operation.facadeNs - interval, event.stepStartedAt - operation.startedNs, operation.finishedNs - event.finishedAt))
        }
        evidence.csv("timing-counters", listOf("overflow", "failed_begin_steps", "failed_end_steps", "displaced_transactions", "failed_closes"),
            listOf(driver.overflow.get(), driver.failedBegins.get(), driver.failedEnds.get(), driver.displacedTransactions.get(), driver.failedCloses.get()))
        check(driver.overflow.get() == 0 && driver.displacedTransactions.get() == 0 && driver.failedCloses.get() == 0) { "timing_incomplete" }
    }

    private fun recordFailure(stage: String, failure: Throwable) {
        val kind = if (failure is VeyraException) failure.failure.code.name else failure.javaClass.simpleName
        if (failures.size < 64) failures.add("$stage:$kind")
        gate.requestStop("correctness_or_execution_failure")
    }

    private fun relative(value: Long?): Long? = value?.let { it - origin }

    companion object {
        val OPERATION_HEADER = listOf("operation_id", "media_count", "phase", "workload", "producer", "ordinal", "measured",
            "scheduled_ns", "deadline_ns", "started_ns", "transform_entry_ns", "finished_ns", "facade_ns", "start_lateness_ns",
            "completion_lateness_ns", "before_revision", "after_revision", "outcome")
        val SCHEDULE_HEADER = listOf("media_count", "workload", "producer", "slot", "scheduled_ns", "deadline_ns", "outcome", "operation_id")
        val PRODUCER_HEADER = listOf("media_count", "workload", "producer", "period_ns", "scheduled", "offered", "started", "completed", "missed_starts", "due_but_stopped", "late_completions", "max_consecutive_misses")
    }
}
