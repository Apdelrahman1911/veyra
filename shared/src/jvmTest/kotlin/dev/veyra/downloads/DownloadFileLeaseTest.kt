package dev.veyra.downloads

import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import org.junit.Assume.assumeNoException
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlin.test.*

/** A fresh process deliberately knows nothing of the parent's registry: it tests the KERNEL lock. */
internal object DownloadFileLockProcessProbe {
    @JvmStatic fun main(args: Array<String>) {
        FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = channel.tryLock()
            if (lock == null) { System.out.println("BUSY"); System.exit(3) }
            lock!!.use { System.out.println("ACQUIRED") }
        }
    }
}

class DownloadFileLeaseTest {
    private fun fileCase(block: (Path, DownloadFiles) -> Unit) {
        val directory = downloadTestDirectory()
        val files = DownloadFiles(directory.resolve("media").toString())
        files.initialize()
        block(directory, files)
    }

    private fun childAcquires(directory: Path, path: Path): Boolean {
        val entries = listOf(DownloadFileLockProcessProbe::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct()
        val scratch = directory.resolve("child-temp")
        Files.createDirectories(scratch)
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val child = ProcessBuilder(java.toString(), "-XX:-UsePerfData", "-Djava.io.tmpdir=$scratch", "-Duser.home=$scratch",
            "-cp", entries.joinToString(File.pathSeparator), DownloadFileLockProcessProbe::class.java.name, path.toString())
            .directory(directory.toFile()).redirectErrorStream(true).apply {
                environment()["TMPDIR"] = scratch.toString(); environment()["TMP"] = scratch.toString(); environment()["TEMP"] = scratch.toString()
            }.start()
        try {
            if (!child.waitFor(15, TimeUnit.SECONDS)) fail("Fresh-process OS lock probe did not finish")
            val output = child.inputStream.bufferedReader().readText()
            assertTrue(child.exitValue() in setOf(0, 3), "Child failed (${child.exitValue()}): $output")
            return child.exitValue() == 0
        } finally { if (child.isAlive) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS) } }
    }

    @Test fun failedSameProcessContenderDoesNotReleaseFirstOwnersKernelLock() = fileCase { directory, _ ->
        val path = directory.resolve("stable-native.lock")
        val first = assertNotNull(tryAcquireDownloadFileLease(path.toString()))
        try {
            assertFalse(childAcquires(directory, path), "A real second process must observe the owner's kernel lock")
            repeat(5) { assertNull(tryAcquireDownloadFileLease(path.toString())) }
            assertFalse(childAcquires(directory, path), "Rejecting/closing a same-JVM contender must NOT silently unlock the inode")
        } finally { first.close(); first.close() }
        assertTrue(childAcquires(directory, path), "Idempotent owner release must permit a fresh process")
        assertTrue(Files.exists(path), "Stable lock inodes must not be unlinked")
    }

    @Test fun canonicalAliasesUseOneRegistryIdentityBeforeOpeningAnySecondDescriptor() = fileCase { directory, _ ->
        Files.createDirectories(directory.resolve("alias-segment"))
        val path = directory.resolve("canonical.lock")
        val alias = directory.resolve("alias-segment/../canonical.lock")
        val lease = assertNotNull(tryAcquireDownloadFileLease(path.toString()))
        try {
            assertNull(tryAcquireDownloadFileLease(alias.toString()))
            assertFalse(childAcquires(directory, path))
        } finally { lease.close() }
    }

    @Test fun unknownNativeCloseResultStaysStickyEvenAfterChannelAndKernelLockWereConsumed() {
        for (fault in listOf<() -> Throwable>(
            { IOException("Authored private native-close diagnostic") },
            { CancellationException("Authored native-close cancellation") },
            { AssertionError("Authored unknown native-close error") },
        )) fileCase { directory, _ ->
            val path = directory.resolve("unknown-native-close.lock")
            val closes = AtomicInteger()
            val lease = assertNotNull(acquireJvmDownloadFileLease(path.toString()) { channel ->
                closes.incrementAndGet()
                channel.close() // The real kernel owner is consumed BEFORE the injected native-port error.
                throw fault()
            })
            val first = assertFailsWith<VeyraException> { lease.close() }
            assertEquals(FailureCode.STORAGE_IO, first.failure.code)
            assertEquals("download_lock_release", first.failure.detail)
            repeat(3) { assertSame(first, assertFailsWith<VeyraException> { lease.close() }) }
            assertEquals(1, closes.get(), "Never retry a channel whose native close result is unknown")
            assertNull(tryAcquireDownloadFileLease(path.toString()), "Same-process path reservation stays fail-closed")
            assertTrue(childAcquires(directory, path), "Kernel state alone must not erase the failed consumption receipt")
        }
    }

    @Test fun nativePortFailureBeforeConsumptionNeverRetriesTheStillHeldRealChannel() = fileCase { directory, _ ->
        val path = directory.resolve("unconsumed-native-close.lock")
        val channel = AtomicReference<FileChannel>()
        val closes = AtomicInteger()
        val lease = assertNotNull(acquireJvmDownloadFileLease(path.toString()) {
            channel.set(it); closes.incrementAndGet()
            throw IOException("Authored native-port failure before channel consumption")
        })
        try {
            val first = assertFailsWith<VeyraException> { lease.close() }
            repeat(3) { assertSame(first, assertFailsWith<VeyraException> { lease.close() }) }
            assertEquals(1, closes.get())
            assertTrue(assertNotNull(channel.get()).isOpen)
            assertFalse(childAcquires(directory, path))
            assertNull(tryAcquireDownloadFileLease(path.toString()))
        } finally {
            // Test-only access to the exact captured real channel prevents a fixture leak.
            // Production has no such bypass; its native holder/registry remain sticky.
            channel.get()?.close()
        }
        assertFailsWith<VeyraException> { lease.close() }
        assertEquals(1, closes.get())
        assertNull(tryAcquireDownloadFileLease(path.toString()))
    }

    @Test fun failedMultiLockAcquisitionReleasesItsPartialSetButNeverTheContendedOwner() = fileCase { directory, files ->
        fun lockFor(part: String): Path {
            val lockDirectory = directory.resolve("media/.locks")
            val before = Files.list(lockDirectory).use { it.toList().toSet() }
            assertNotNull(files.tryLeasePaths(listOf(part))).close()
            return Files.list(lockDirectory).use { it.toList().toSet() - before }.single()
        }
        val parts = listOf(files.part("media-one", "one").toString(), files.part("media-two", "two").toString())
        val ordered = parts.map { it to lockFor(it) }.sortedBy { it.second.fileName.toString() }
        val first = ordered.first(); val last = ordered.last()
        val held = assertNotNull(files.tryLeasePaths(listOf(last.first)))
        try {
            assertNull(files.tryLeasePaths(parts.reversed())) // call order must not affect lock ordering
            assertNotNull(files.tryLeasePaths(listOf(first.first))).close()
            assertFalse(childAcquires(directory, last.second), "Failed aggregate acquisition cannot release the owner's kernel lock")
        } finally { held.close() }
        assertNotNull(files.tryLeasePaths(parts)).close()
    }

    @Test fun loweringParallelismWaitsForOccupiedHighSlotsEvenWhenSlotZeroIsFree() = fileCase { directory, files ->
        val slots = (0 until 4).map { assertNotNull(files.tryTransferSlot(4)) }
        try {
            assertEquals(listOf(0, 1, 2, 3), slots.map { it.index })
            assertNull(files.tryTransferSlot(4))
            slots[0].close(); slots[2].close(); slots[3].close()
            assertNull(files.tryTransferSlot(1), "A retired high slot is still a physical acquisition")
            assertFalse(childAcquires(directory, directory.resolve("media/.locks/slot-1")))
            slots[1].close()
            val single = assertNotNull(files.tryTransferSlot(1))
            try { assertEquals(0, single.index); assertNull(files.tryTransferSlot(1)) }
            finally { single.close() }
        } finally { slots.forEach { it.close() } }
    }

    @Test fun symlinkAncestorsAndGenerationDescendantsAreRejectedBeforeWritesOrDeletes() = fileCase { directory, files ->
        val target = directory.resolve("task-owned-target")
        Files.createDirectories(target)
        val sentinel = target.resolve("preserve.txt")
        Files.writeString(sentinel, "authored fixture to preserve")
        val alias = directory.resolve("linked-ancestor")
        try { Files.createSymbolicLink(alias, target) }
        catch (error: Exception) { assumeNoException("This host cannot create test symlinks", error); return@fileCase }
        assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> {
            DownloadFiles(alias.resolve("not-created").toString()).initialize()
        }.failure.code)
        assertFalse(Files.exists(target.resolve("not-created")))
        val generation = files.stagingDirectory("symlink-media", "owned-attempt")
        Files.createDirectories(Path.of(generation.parent!!.toString()))
        Files.createSymbolicLink(Path.of(generation.toString()), target)
        assertFailsWith<VeyraException> { files.prepareStaging("symlink-media", "owned-attempt") }
        assertFailsWith<VeyraException> { files.resetStaging((generation / "transfer.part").toString()) }
        assertEquals("authored fixture to preserve", Files.readString(sentinel))
        assertFalse(Files.exists(target.resolve("transfer.part")))
    }
}
