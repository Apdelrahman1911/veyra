@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.veyra.diagnostics

import dev.veyra.diagnostics.DiagnosticComponent.APP
import dev.veyra.diagnostics.DiagnosticComponent.NETWORK
import dev.veyra.diagnostics.DiagnosticOperation.HTTP
import dev.veyra.diagnostics.DiagnosticOperation.INITIALIZE
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

class DiagnosticJournalTest {
    @Test fun disabledModeDoesNotWriteAndExportJoinsCorrelatedSpansWithoutPayloads() = runTest {
        withDirectory { folder ->
            val journal = DiagnosticJournal(backgroundScope, folder, io = StandardTestDispatcher(testScheduler))
            val log = journal.diagnostics
            log.record(APP, INITIALIZE)
            assertFalse(fs.exists(folder))
            assertNull(journal.state.value.sessionId)
            journal.setEnabled(true)
            val privateData = "https://fixture.invalid/private-media?token=DO_NOT_RECORD"
            assertFailsWith<VeyraException> {
                log.trace(APP, INITIALIZE) {
                    log.trace(NETWORK, HTTP) {
                        throw VeyraException(AppFailure(FailureCode.NETWORK_UNAVAILABLE, privateData))
                    }
                }
            }
            val file = journal.export()
            val rows = events(file)
            val app = rows.single { it.field("operation") == "INITIALIZE" && it.field("outcome") == "STARTED" }
            val http = rows.single { it.field("operation") == "HTTP" && it.field("outcome") == "FAILED" }
            assertEquals(app.field("operation_id"), http.field("parent_id"))
            assertEquals(app.field("correlation_id"), http.field("correlation_id"))
            assertEquals("NETWORK_UNAVAILABLE", http.field("failure_code"))
            assertTrue(http.field("duration_ms")!!.toLong() >= 0)
            val text = fs.read(file) { readUtf8() }
            assertFalse(text.contains("DO_NOT_RECORD") || text.contains("fixture.invalid"))
        }
    }

    @Test fun reopenKeepsSessionAndDownloadLinksRepairsPartialTailAndExportsWhenDisabled() = runTest {
        withDirectory { folder ->
            val writer = StandardTestDispatcher(testScheduler)
            var preferences = DiagnosticCaptureState()
            val savedKey = "fixture-journal-key".encodeUtf8()
            val firstScope = CoroutineScope(writer + SupervisorJob())
            try {
                val journal = DiagnosticJournal(firstScope, folder, save = { preferences = it }, key = { savedKey }, io = writer)
                journal.setEnabled(true)
                val alias = assertNotNull(journal.diagnostics.downloadKey("private-download-identity"))
                journal.diagnostics.record(APP, INITIALIZE, link = alias)
                val old = events(journal.export())
                firstScope.cancel()
                runCurrent()
                fs.appendingSink(folder / "current.jsonl").buffer().use { it.writeUtf8("{incomplete") }
                val reopened = DiagnosticJournal(backgroundScope, folder, initial = preferences, save = { preferences = it },
                    key = { savedKey }, io = writer)
                assertEquals(alias, reopened.diagnostics.downloadKey("private-download-identity"))
                assertNotEquals(alias, reopened.diagnostics.downloadKey("second-private-download-identity"))
                val job = launch { reopened.diagnostics.trace(APP, INITIALIZE) { awaitCancellation() } }
                runCurrent()
                job.cancelAndJoin()
                reopened.setEnabled(false)
                assertFalse(preferences.enabled)
                val rows = events(reopened.export())
                assertEquals(old.first().field("session_id"), rows.last().field("session_id"))
                assertNotEquals(old.first().field("process_id"), rows.last().field("process_id"))
                assertTrue(rows.any { it.field("outcome") == "CANCELLED" })
                assertEquals("DISABLED", rows.last().field("phase"))
            } finally { firstScope.cancel() }
        }
    }

    @Test fun recordingRotatesWithinBudgetAndReportsPressureLoss() = runTest {
        withDirectory { folder ->
            val bound = 1_024L
            val journal = DiagnosticJournal(backgroundScope, folder, io = StandardTestDispatcher(testScheduler),
                segmentBytes = bound, capacity = 3)
            journal.setEnabled(true)
            repeat(25) { journal.diagnostics.record(APP, INITIALIZE, value = it.toLong()) }
            assertTrue(events(journal.export()).any { it.field("outcome") == "DROPPED" })
            repeat(20) {
                journal.diagnostics.record(APP, INITIALIZE, value = it.toLong())
                runCurrent()
            }
            val path = journal.export()
            assertTrue(assertNotNull(fs.metadataOrNull(folder / "current.jsonl")?.size) <= bound)
            assertTrue(assertNotNull(fs.metadataOrNull(folder / "previous.jsonl")?.size) <= bound)
            assertTrue(assertNotNull(fs.metadataOrNull(path)?.size) <= bound * 2)
            val rows = events(path)
            assertEquals("19", rows.last().field("value"))
            assertTrue(rows.zipWithNext().all { (a, b) -> a.field("sequence")!!.toLong() < b.field("sequence")!!.toLong() })
        }
    }

    @Test fun storageFailureCannotTurnAnApplicationOperationIntoFailure() = runTest {
        withDirectory { folder ->
            fs.write(folder) { writeUtf8("fixture file prevents directory creation") }
            val journal = DiagnosticJournal(backgroundScope, folder, io = StandardTestDispatcher(testScheduler))
            journal.setEnabled(true)
            assertEquals(7, journal.diagnostics.trace(APP, INITIALIZE) { 7 })
            runCurrent()
            assertTrue(journal.state.value.writeFailed)
            assertFailsWith<Exception> { journal.export() }
        }
    }

    private val fs = FileSystem.SYSTEM
    private suspend fun withDirectory(test: suspend (Path) -> Unit) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "veyra-diagnostic-test-${Uuid.random()}"
        fs.createDirectories(root)
        try { test(root / "diagnostics") }
        finally { fs.deleteRecursively(root) }
    }

    private fun events(file: Path) = fs.read(file) { readUtf8() }.lineSequence().filter { it.isNotBlank() }
        .map { Json.parseToJsonElement(it).jsonObject }.toList()
    private fun JsonObject.field(name: String) = get(name)?.jsonPrimitive?.content
}
