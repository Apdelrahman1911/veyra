package dev.veyra.desktop

import dev.veyra.desktop.runtime.AlreadyRunningException
import dev.veyra.desktop.runtime.DesktopProcessContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopProcessContextTest {
    private fun root(): Path {
        val project = System.getenv("VEYRA_PROJECT_ROOT")?.let(Path::of)
            ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("settings.gradle.kts")) }
        return project.resolve(".work/desktop-audio/process-tests").also { Files.createDirectories(it) }
    }

    @Test fun onlyOneOwnerCanOpenADatabaseAndAudioDirectory() {
        val directory = Files.createTempDirectory(root(), "instance-")
        DesktopProcessContext.acquire(directory).use { first ->
            assertFailsWith<AlreadyRunningException> { DesktopProcessContext.acquire(directory) }
            assertFailsWith<IllegalArgumentException> { first.ownedPath(directory.resolve("../escape.json")) }
        }
        DesktopProcessContext.acquire(directory).close()
    }

    @Test fun privateDirectoryRejectsSymbolicAncestorsBeforeWriting() {
        val directory = Files.createTempDirectory(root(), "symlink-")
        val actual = Files.createDirectory(directory.resolve("actual"))
        val link = directory.resolve("link")
        try { Files.createSymbolicLink(link, actual) } catch (_: UnsupportedOperationException) { return }
        catch (_: java.nio.file.FileSystemException) { return }
        assertFailsWith<IllegalArgumentException> { DesktopProcessContext.acquire(link.resolve("must-not-be-created")) }
        kotlin.test.assertFalse(Files.exists(actual.resolve("must-not-be-created")))
    }

    @Test fun rejectedSameProcessAcquisitionCannotReleaseTheOwnersKernelLock() {
        val directory = Files.createTempDirectory(root(), "kernel-lock-")
        val helper = directory.resolve("ProcessLockProbe.java")
        javaClass.getResourceAsStream("/dev/veyra/desktop/ProcessLockProbe.java")!!.use { Files.copy(it, helper) }
        fun childAcquires(): Boolean {
            val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            val child = ProcessBuilder(java.toString(), "-XX:-UsePerfData", "-Xmx64m",
                "-Djava.io.tmpdir=$directory", helper.toString(), directory.resolve("instance.lock").toString())
                .redirectErrorStream(true).start()
            try {
                assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Fresh-process lock probe timed out")
                val result = child.exitValue()
                assertTrue(result == 0 || result == 3, "Fresh-process lock probe failed: " + child.inputStream.bufferedReader().readText())
                return result == 0
            } finally {
                if (child.isAlive) child.destroyForcibly().waitFor()
            }
        }
        val first = DesktopProcessContext.acquire(directory)
        try {
            assertEquals(false, childAcquires(), "The original kernel lock is absent")
            repeat(3) { assertFailsWith<AlreadyRunningException> { DesktopProcessContext.acquire(directory) } }
            assertEquals(false, childAcquires(), "Closing a rejected same-JVM probe dropped the owner's POSIX lock")
        } finally { first.close() }
        assertEquals(true, childAcquires(), "The kernel lock remained held after close")
        DesktopProcessContext.acquire(directory).use {
            first.close() // A repeated old close must not release a newly acquired owner's lock.
            assertEquals(false, childAcquires())
        }
    }
}
