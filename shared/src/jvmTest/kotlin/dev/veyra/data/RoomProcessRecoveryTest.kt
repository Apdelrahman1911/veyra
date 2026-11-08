package dev.veyra.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.room.execSQL
import dev.veyra.data.room.*
import dev.veyra.model.*
import kotlinx.coroutines.*
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.*

/** Real separate JVMs, OS locks, SQLite WAL and forcible process death; not a mocked crash. */
class RoomProcessRecoveryTest {
    @Test fun rejectedSameJvmLeaseProbeCannotUnlockOriginalOwnerForAnotherProcess() {
        val directory = roomWorkDirectory("lease-process")
        val path = directory.resolve("veyra.lock")
        val first = acquireDatabaseLease(path.toString())
        try {
            assertEquals(3, probeExit(directory, "lease", path.toString()))
            repeat(3) { assertFailsWith<VeyraException> { acquireDatabaseLease(directory.resolve("./veyra.lock").toString()) } }
            assertEquals(3, probeExit(directory, "lease", path.toString()), "A failed same-JVM probe must not drop the kernel lock")
        } finally { first.release() }
        first.release() // Closing an already released wrapper cannot affect a future holder.
        assertEquals(0, probeExit(directory, "lease", path.toString()))
        val second = acquireDatabaseLease(path.toString())
        try {
            first.release()
            assertEquals(3, probeExit(directory, "lease", path.toString()))
        } finally { second.release() }
    }

    @Test fun killedWriterRetainsAcknowledgedRoomCommitWithoutCleanCloseOrBackup() = runBlocking {
        val directory = roomWorkDirectory("killed-commit")
        val before = seed(directory)
        killAtSignal(directory, "commit")
        val reopened = createPlatformLibraryStore(directory.toString())
        try {
            assertEquals(processMutation(before).copy(revision = before.revision + 1), reopened.initialize().getOrThrow())
            assertEquals(StoreStatus.Ready(), reopened.status.value)
        } finally { reopened.close() }
    }

    @Test fun killedWriterRollsBackUncommittedRowsAndStagedOccurrenceOrder() = runBlocking {
        val directory = roomWorkDirectory("killed-transaction")
        val before = seed(directory)
        killAtSignal(directory, "transaction")
        val reopened = createPlatformLibraryStore(directory.toString())
        try {
            assertEquals(before, reopened.initialize().getOrThrow())
            withSql(directory.resolve("veyra.db")) { db ->
                assertEquals(0L, db.longValue("SELECT count(*) FROM queue_entries WHERE position < 0"))
                assertFalse(db.prepare("PRAGMA foreign_key_check").use { it.step() })
            }
        } finally { reopened.close() }
    }

    private suspend fun seed(directory: Path): AppSnapshot {
        seedSchema2(directory, completeLibrary())
        val store = createPlatformLibraryStore(directory.toString())
        return try { store.initialize().getOrThrow() } finally { store.close() }
    }

    private suspend fun killAtSignal(directory: Path, mode: String) {
        val signal = directory.resolve("child.signal")
        val child = startProbe(directory, mode, directory.toString())
        try {
            withTimeout(30_000) {
                while (!Files.isRegularFile(signal)) {
                    check(child.isAlive) { "Room child exited before transaction handshake: ${child.inputStream.bufferedReader().readText().take(2000)}" }
                    delay(20)
                }
            }
            assertEquals(mode, Files.readString(signal))
            child.destroyForcibly()
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Killed child did not terminate")
            assertFalse(child.isAlive)
        } finally {
            if (child.isAlive) { child.destroyForcibly(); child.waitFor(15, TimeUnit.SECONDS) }
        }
    }

    private fun probeExit(directory: Path, vararg args: String): Int {
        val process = startProbe(directory, *args)
        try {
            check(process.waitFor(15, TimeUnit.SECONDS)) { "Lease child timed out" }
            val code = process.exitValue()
            check(code == 0 || code == 3) { "Lease child failed: ${process.inputStream.bufferedReader().readText().take(2000)}" }
            return code
        } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor(15, TimeUnit.SECONDS) } }
    }

    private fun startProbe(directory: Path, vararg args: String): Process {
        val root = projectRoot()
        val urls = generateSequence(RoomProcessProbe::class.java.classLoader) { it.parent }
            .filterIsInstance<URLClassLoader>().flatMap { it.getURLs().asSequence() }
            .filter { it.protocol == "file" }.map { Path.of(it.toURI()).toString() }.toList()
        val classpath = (urls + System.getProperty("java.class.path").split(File.pathSeparator) +
            listOf(RoomProcessProbe::class.java, VeyraDatabase::class.java, Unit::class.java, CoroutineScope::class.java)
                .mapNotNull { it.protectionDomain?.codeSource?.location?.toURI()?.let(Path::of)?.toString() })
            .distinct().joinToString(File.pathSeparator)
        return ProcessBuilder(listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-XX:-UsePerfData", "-Xmx160m",
            "-Duser.home=${root.resolve(".work/home")}", "-Djava.io.tmpdir=${root.resolve(".work/tmp")}",
            "-cp", classpath, RoomProcessProbe::class.java.name,
        ) + args).directory(directory.toFile()).redirectErrorStream(true).start()
    }
}

private fun processMutation(before: AppSnapshot): AppSnapshot = before.copy(
    favoriteIds = before.favoriteIds + "music-2",
    settings = before.settings.copy(theme = ThemeMode.LIGHT),
    navigation = before.navigation + ("crash-probe" to "committed"),
    queues = before.queues + (QueueContext.MUSIC to before.queue(QueueContext.MUSIC).let { it.copy(entries = it.entries.reversed()) }),
)

/** Invoked only by the scoped subprocess tests above; emits no user data or media URLs. */
object RoomProcessProbe {
    @JvmStatic fun main(args: Array<String>) {
        if (args[0] == "lease") {
            val lease = try { acquireDatabaseLease(args[1]) } catch (_: VeyraException) { exitProcess(3) }
            lease.release()
            return
        }
        runBlocking {
            val directory = Path.of(args[1])
            // Direct Room construction exposes the transaction for the controlled kill point.
            // The explicit same production lease is intentionally NOT closed before process death.
            val lease = acquireDatabaseLease(directory.resolve("veyra.lock").toString())
            val database = testDatabase(directory.resolve("veyra.db"))
            val store = LibraryStore(database)
            try {
                val before = store.initialize().getOrThrow()
                if (args[0] == "commit") {
                    writerUpdate(store, ::processMutation)
                    Files.writeString(directory.resolve("child.signal"), "commit")
                    awaitCancellation()
                } else {
                    require(args[0] == "transaction")
                    val baseline = AcquisitionGraph(database.libraryDao().readRows(), database.acquisitionDao().readAcquisitionRows())
                    database.useWriterConnection { connection ->
                        connection.execSQL("PRAGMA synchronous = FULL")
                        connection.immediateTransaction {
                            database.libraryDao().writeDifference(baseline.library,
                                processMutation(baseline.snapshot()).toRows(baseline.library))
                            // Preserve the all-current-rows negative-order crash point while the
                            // production DAO now requires exact, bounded staging identities.
                            database.libraryDao().readQueueEntries().map { it.id }.chunked(256).forEach { ids ->
                                database.libraryDao().stageQueueEntriesOrder(ids)
                            }
                            Files.writeString(directory.resolve("child.signal"), "transaction")
                            awaitCancellation()
                        }
                    }
                }
            } finally { lease.release() }
        }
    }
}
