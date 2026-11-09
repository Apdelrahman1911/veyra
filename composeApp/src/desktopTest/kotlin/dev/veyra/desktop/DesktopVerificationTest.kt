package dev.veyra.desktop

import dev.veyra.desktop.runtime.CentralProofPhase
import dev.veyra.desktop.runtime.DesktopProcessContext
import dev.veyra.desktop.runtime.desktopResultWriteFailure
import dev.veyra.desktop.runtime.finishDesktopVerification
import dev.veyra.desktop.runtime.parseDesktopOptions
import dev.veyra.desktop.runtime.requireDesktopResultPath
import dev.veyra.desktop.runtime.writeDesktopVerificationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopVerificationTest {
    private fun directory(): Path {
        val project = System.getenv("VEYRA_PROJECT_ROOT")?.let(Path::of)
            ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("settings.gradle.kts")) }
        val work = project.resolve(".work/desktop-audio/verification-tests")
        Files.createDirectories(work)
        return Files.createTempDirectory(work, "case-")
    }

    @Test fun centralProofRequiresAnExplicitDedicatedDirectoryAndExclusivePhase() {
        val root = directory()
        assertFailsWith<IllegalArgumentException> { parseDesktopOptions(arrayOf("--central-proof", "online")) }
        for (other in listOf(arrayOf("--ui-smoke"), arrayOf("--self-test", root.resolve("tone.mp3").toString()))) {
            assertFailsWith<IllegalArgumentException> {
                parseDesktopOptions(arrayOf("--data-dir", root.toString(), "--central-proof", "offline") + other)
            }
        }
        assertFailsWith<IllegalStateException> { parseDesktopOptions(arrayOf("--data-dir", root.toString(), "--central-proof", "later")) }
        assertEquals(CentralProofPhase.ONLINE, parseDesktopOptions(arrayOf("--central-proof", "online", "--data-dir", root.toString())).centralProof)
        assertEquals(CentralProofPhase.OFFLINE, parseDesktopOptions(arrayOf("--data-dir", root.toString(), "--central-proof", "offline")).centralProof)
    }

    @Test fun malformedOrDuplicateOptionsCannotChangeTheProofDirectoryOrOutput() {
        val root = directory()
        val result = root.resolve("verification/results/online.json").toString()
        assertFailsWith<IllegalArgumentException> { parseDesktopOptions(arrayOf("--data-dir", "--ui-smoke")) }
        assertFailsWith<IllegalArgumentException> { parseDesktopOptions(arrayOf("--data-dir", root.toString(), "--data-dir", root.toString())) }
        assertFailsWith<IllegalArgumentException> { parseDesktopOptions(arrayOf("--ui-smoke", "--self-test-result", result, "--verification-result", result)) }
        assertFailsWith<IllegalArgumentException> { parseDesktopOptions(arrayOf("--data-dir", root.toString(), "--verification-result", result)) }
        for (alias in listOf("--self-test-result", "--verification-result")) {
            val options = parseDesktopOptions(arrayOf("--data-dir", root.toString(), "--ui-smoke", alias, result))
            assertEquals(Path.of(result), options.result)
        }
    }

    @Test fun resultsCanNeverTargetDatabaseRuntimeLockOrOnlineMarker() {
        val root = directory()
        for (path in listOf("veyra.db", "instance.lock", "runtime/ffmpeg.json", "verification/central-listening-online.json",
            "verification/results/nested/result.json", "verification/results/result.txt", "verification/results/CON.json")) {
            val target = root.resolve(path)
            assertFailsWith<IllegalArgumentException>(path) { requireDesktopResultPath(root, target) }
            assertFalse(Files.exists(target))
        }
        assertFailsWith<IllegalArgumentException> { requireDesktopResultPath(root, root.resolve("../escape.json")) }
        assertEquals(root.resolve("verification/results/online-1.json"), requireDesktopResultPath(root, root.resolve("verification/results/online-1.json")))
    }

    @Test fun resultsRejectLinkedParentsAndExistingLinkedFilesBeforeAnyWrite() {
        val root = directory()
        val outside = Files.createDirectory(root.resolve("caller-owned"))
        val context = DesktopProcessContext.acquire(root)
        try {
            Files.createDirectories(root.resolve("verification"))
            val parent = root.resolve("verification/results")
            try { Files.createSymbolicLink(parent, outside) }
            catch (error: UnsupportedOperationException) { Assume.assumeNoException(error); return }
            catch (error: java.nio.file.FileSystemException) { Assume.assumeNoException(error); return }
            assertFailsWith<IllegalArgumentException> { writeDesktopVerificationResult(context, parent.resolve("escape.json"), "{}") }
            assertFalse(Files.exists(outside.resolve("escape.json")))
            Files.delete(parent)
            Files.createDirectory(parent)
            val marker = outside.resolve("keep.json")
            Files.writeString(marker, "authored caller-owned data")
            Files.createSymbolicLink(parent.resolve("existing.json"), marker)
            assertFailsWith<IllegalArgumentException> { writeDesktopVerificationResult(context, parent.resolve("existing.json"), "{}") }
            assertEquals("authored caller-owned data", Files.readString(marker))
        } finally { context.close() }
    }

    @Test fun successfulReportCannotEscapeBeforeEveryOwnerHasClosed(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var closeEntered = false
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            finishDesktopVerification("{\"success\":true,\"physicalAudioVerified\":true}") { closeEntered = true; gate.await() }
        }
        assertTrue(closeEntered)
        assertFalse(result.isCompleted)
        gate.complete(Unit)
        val outcome = result.await()
        assertTrue(outcome.success && outcome.ownersClosed)
        val report = Json.parseToJsonElement(outcome.report).jsonObject
        assertTrue(report.getValue("ownersClosedByHost").jsonPrimitive.boolean)
        assertFalse(report.getValue("physicalAudioVerified").jsonPrimitive.boolean)
    }

    @Test fun cleanupFailureIsNonzeroAndNeverLeaksExceptionDetails(): Unit = runBlocking {
        val outcome = finishDesktopVerification("{\"success\":true,\"newProcessOfflineStageVerified\":true}") {
            error("private source URL and runtime details must never be emitted")
        }
        assertFalse(outcome.success || outcome.ownersClosed)
        val report = Json.parseToJsonElement(outcome.report).jsonObject
        assertFalse(report.getValue("success").jsonPrimitive.boolean)
        assertFalse(report.getValue("newProcessOfflineStageVerified").jsonPrimitive.boolean)
        assertFalse(outcome.report.contains("private source"))
        assertEquals("runtime_resource_cleanup_failed", report.getValue("cleanupFailure").jsonPrimitive.content)
    }

    @Test fun invalidOrFailedCandidatesStillCloseAndCannotBecomeSuccess(): Unit = runBlocking {
        var closes = 0
        for (candidate in listOf("invalid", "[]", "{\"success\":false}", "{}", "{\"success\":\"true\"}")) {
            val outcome = finishDesktopVerification(candidate) { closes++ }
            assertFalse(outcome.success)
            assertTrue(outcome.ownersClosed)
        }
        assertEquals(5, closes)
    }

    @Test fun reportPublicationAfterContextCloseTouchesOnlyDedicatedResultsAndIsAtomic() {
        val root = directory()
        val context = DesktopProcessContext.acquire(root)
        val marker = root.resolve("verification/central-listening-online.json")
        Files.createDirectories(marker.parent)
        Files.writeString(marker, "authored marker to preserve")
        val database = root.resolve("veyra.db")
        Files.writeString(database, "authored database to preserve")
        context.close()
        val target = root.resolve("verification/results/online.json")
        writeDesktopVerificationResult(context, target, "{\"success\":false}")
        writeDesktopVerificationResult(context, target, "{\"success\":true}")
        assertEquals("{\"success\":true}\n", Files.readString(target))
        for (reserved in listOf(marker, database)) assertFailsWith<IllegalArgumentException> {
            writeDesktopVerificationResult(context, reserved, "{\"success\":true}")
        }
        assertEquals("authored marker to preserve", Files.readString(marker))
        assertEquals("authored database to preserve", Files.readString(database))
        Files.list(target.parent).use { files -> assertEquals(listOf("online.json"), files.map { it.fileName.toString() }.toList()) }
        assertFalse(Json.parseToJsonElement(desktopResultWriteFailure("{\"success\":true}")).jsonObject.getValue("success").jsonPrimitive.boolean)
    }
}
