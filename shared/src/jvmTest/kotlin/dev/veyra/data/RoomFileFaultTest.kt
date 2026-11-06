package dev.veyra.data

import dev.veyra.data.room.RoomDatabaseFiles
import dev.veyra.data.room.RoomLibraryPersistence
import dev.veyra.model.*
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSink
import okio.Path as OkioPath
import okio.Sink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Real SQLite, with narrowly injected failures in its owned backup/recovery filesystem boundary. */
class RoomFileFaultTest {
    private class Faults : ForwardingFileSystem(FileSystem.SYSTEM) {
        var failBackupMove = false
        var failStageDelete = false
        var failRecoveryCopy = false
        var failRecoveryMarkerDelete = false
        var lastStage: OkioPath? = null
        override fun atomicMove(source: OkioPath, target: OkioPath) {
            if (failBackupMove && target.name == "veyra.backup.db") {
                lastStage = source
                throw IOException("authored backup publication failure")
            }
            super.atomicMove(source, target)
        }
        override fun delete(path: OkioPath, mustExist: Boolean) {
            if (failStageDelete && path.name.startsWith("veyra.backup-")) throw IOException("authored stage cleanup failure")
            if (failRecoveryMarkerDelete && path.name == "veyra.recovering") throw IOException("authored recovery marker failure")
            super.delete(path, mustExist)
        }
        override fun sink(file: OkioPath, mustCreate: Boolean): Sink {
            val delegate = super.sink(file, mustCreate)
            return if (failRecoveryCopy && file.name.startsWith("veyra.recovery-")) object : ForwardingSink(delegate) {
                override fun write(source: Buffer, byteCount: Long) { throw IOException("authored recovery copy failure") }
            } else delegate
        }
    }

    @Test fun backupCleanupFailureDoesNotMaskPublicationFailureOrReplacePreviousBackup() = runBlocking {
        val directory = roomWorkDirectory("backup-fault")
        val faults = Faults()
        val files = RoomDatabaseFiles(directory.toString(), faults)
        val persistence = RoomLibraryPersistence({ path -> testDatabase(Path.of(path)) }, files)
        val store = LibraryStore(persistence)
        try {
            store.update { completeLibrary() }.getOrThrow()
            store.backup().getOrThrow()
            val original = Files.readAllBytes(directory.resolve("veyra.backup.db"))
            faults.failBackupMove = true
            faults.failStageDelete = true
            val error = assertFailsWith<IOException> { persistence.backup() }
            assertEquals("authored backup publication failure", error.message)
            assertTrue(error.suppressed.any { it.message == "authored stage cleanup failure" })
            assertContentEquals(original, Files.readAllBytes(directory.resolve("veyra.backup.db")))
        } finally {
            faults.failBackupMove = false
            faults.failStageDelete = false
            faults.lastStage?.let(files::removeStage)
            store.close()
        }
    }

    @Test fun failedRecoveryCopyRemovesPartialStageAndNeverTouchesDamagedPrimary() = runBlocking {
        val directory = roomWorkDirectory("recovery-copy-fault")
        seed(directory)
        val damaged = "authored damaged primary".encodeToByteArray()
        Files.write(directory.resolve("veyra.db"), damaged)
        val faults = Faults().apply { failRecoveryCopy = true }
        val files = RoomDatabaseFiles(directory.toString(), faults)
        try {
            assertFailsWith<IOException> { files.prepareRecoveryCandidate() }
            assertContentEquals(damaged, Files.readAllBytes(directory.resolve("veyra.db")))
            Files.list(directory).use { entries -> assertFalse(entries.anyMatch { it.fileName.toString().startsWith("veyra.recovery-") }) }
            assertFalse(Files.exists(directory.resolve("veyra.recovering")))
        } finally { files.close() }
    }

    @Test fun failedRecoveryMarkerRemovalRequiresExplicitRecoveryOnNextOpen() = runBlocking {
        val directory = roomWorkDirectory("recovery-marker-fault")
        val expected = seed(directory)
        Files.writeString(directory.resolve("veyra.db"), "authored primary damage")
        val faults = Faults().apply { failRecoveryMarkerDelete = true }
        val files = RoomDatabaseFiles(directory.toString(), faults)
        val store = LibraryStore(RoomLibraryPersistence({ path -> testDatabase(Path.of(path)) }, files))
        try {
            assertIs<StoreResult.Failure>(store.initialize())
            assertIs<StoreResult.Failure>(store.recoverFromBackup())
            assertTrue(Files.exists(directory.resolve("veyra.recovering")))
            assertIs<StoreStatus.Failed>(store.status.value)
        } finally { store.close() }
        val reopened = createPlatformLibraryStore(directory.toString())
        try {
            assertEquals(FailureCode.RECOVERY_REQUIRED, assertIs<StoreResult.Failure>(reopened.initialize()).failure.code)
            assertEquals(expected, reopened.recoverFromBackup().getOrThrow())
            assertFalse(Files.exists(directory.resolve("veyra.recovering")))
        } finally { reopened.close() }
    }

    private suspend fun seed(directory: Path): AppSnapshot {
        val store = createPlatformLibraryStore(directory.toString())
        return try { store.update { completeLibrary() }.getOrThrow() } finally { store.close() }
    }
}
