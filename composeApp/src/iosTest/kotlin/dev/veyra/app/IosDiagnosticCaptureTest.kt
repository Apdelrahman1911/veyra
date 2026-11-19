@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.app

import dev.veyra.diagnostics.DiagnosticComponent.APP
import dev.veyra.diagnostics.DiagnosticOperation.FOREGROUND
import dev.veyra.platform.runIosMainContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import okio.FileSystem
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUUID

class IosDiagnosticCaptureTest {
    @Test fun nativePreferencesRestoreCaptureAndExportTheSameSession() = runIosMainContract {
        val suite = "dev.veyra.diagnostics.test." + NSUUID().UUIDString
        val preferences = NSUserDefaults(suiteName = suite)
        val fs = FileSystem.SYSTEM
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / suite
        fs.createDirectories(directory)
        try {
            val first = iosDiagnosticJournal(this, directory.toString(), preferences)
            assertFalse(first.state.value.enabled)
            first.setEnabled(true)
            val session = assertNotNull(first.state.value.sessionId)
            first.diagnostics.record(APP, FOREGROUND)
            assertTrue(fs.read(first.export()) { readUtf8() }.contains(session))
            val reopened = iosDiagnosticJournal(this, directory.toString(), preferences)
            assertTrue(reopened.state.value.enabled)
            assertEquals(session, reopened.state.value.sessionId)
            reopened.setEnabled(false)
            val exported = fs.read(reopened.export()) { readUtf8() }
            assertTrue(exported.contains("\"operation\":\"FOREGROUND\""))
            assertTrue(exported.contains("\"phase\":\"DISABLED\""))
        } finally {
            preferences.removePersistentDomainForName(suite)
            fs.deleteRecursively(directory)
        }
    }
}
