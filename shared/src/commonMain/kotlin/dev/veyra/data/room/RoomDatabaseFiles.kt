package dev.veyra.data.room

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.veyra.model.*
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

internal interface DatabaseLease { fun release() }
internal expect fun acquireDatabaseLease(path: String): DatabaseLease

/** Owned database lifecycle; neither failed opening nor a missing file silently erases a library. */
internal class RoomDatabaseFiles(directory: String, private val fileSystem: FileSystem = FileSystem.SYSTEM) {
    private val directory = directory.toPath(normalize = true)
    val databasePath: Path = this.directory / "veyra.db"
    private val backupPath = this.directory / "veyra.backup.db"
    private val initializedMarker = this.directory / "veyra.initialized"
    private val recoveryMarker = this.directory / "veyra.recovering"
    private var lease: DatabaseLease? = null

    fun prepareForOpen() {
        acquire()
        if (fileSystem.exists(recoveryMarker)) recoveryRequired("Database recovery was interrupted")
        if (fileSystem.exists(databasePath)) validateDatabase(databasePath)
        else if (fileSystem.exists(initializedMarker) || fileSystem.exists(backupPath) ||
            fileSystem.exists(sidecar(databasePath, "-wal")) || fileSystem.exists(directory / "state.json")) {
            recoveryRequired("Previously initialized database is missing")
        }
    }

    fun markInitialized() {
        if (!fileSystem.exists(initializedMarker)) writeMarker(initializedMarker)
    }

    fun newBackupStage(): Path = directory / "veyra.backup-${newEntityId("stage")}.db"

    fun commitBackup(stage: Path) {
        validateDatabase(stage)
        checkpointAndSync(stage)
        fileSystem.atomicMove(stage, backupPath)
    }

    fun prepareRecoveryCandidate(): Path {
        acquire()
        // A future writer's database cannot be downgraded even by the ordinary recovery action.
        if (fileSystem.exists(databasePath)) {
            try { validateDatabase(databasePath) }
            catch (error: VeyraException) { if (error.failure.code == FailureCode.FUTURE_SCHEMA) throw error }
            catch (_: Exception) { /* Preserve and quarantine only after the backup validates. */ }
        }
        if (!fileSystem.exists(backupPath)) recoveryRequired("No verified backup is available")
        validateDatabase(backupPath)
        val stage = directory / "veyra.recovery-${newEntityId("stage")}.db"
        try {
            fileSystem.source(backupPath).buffer().use { source ->
                fileSystem.sink(stage, mustCreate = true).buffer().use { sink -> sink.writeAll(source) }
            }
            sync(stage)
            return stage
        } catch (error: Throwable) {
            removeStagePreservingFailure(stage, error)
            throw error
        }
    }

    fun installRecovery(stage: Path) {
        checkpointAndSync(stage)
        validateDatabase(stage)
        writeMarker(recoveryMarker)
        val quarantine = directory / "veyra.corrupt-${newEntityId("preserved")}.db"
        for (suffix in listOf("", "-wal", "-shm")) {
            val path = sidecar(databasePath, suffix)
            if (fileSystem.exists(path)) fileSystem.atomicMove(path, sidecar(quarantine, suffix))
        }
        fileSystem.atomicMove(stage, databasePath)
        markInitialized()
        fileSystem.delete(recoveryMarker, mustExist = true)
    }

    fun removeStage(stage: Path) {
        for (suffix in listOf("", "-wal", "-shm")) fileSystem.delete(sidecar(stage, suffix), mustExist = false)
    }

    fun removeStagePreservingFailure(stage: Path, primaryFailure: Throwable?) {
        try { removeStage(stage) }
        catch (cleanupError: Throwable) {
            if (primaryFailure == null) throw cleanupError
            primaryFailure.addSuppressed(cleanupError)
        }
    }

    fun close() { lease?.release(); lease = null }

    private fun acquire() {
        if (lease != null) return
        fileSystem.createDirectories(directory)
        lease = acquireDatabaseLease((directory / "veyra.lock").toString())
    }

    private fun validateDatabase(path: Path) {
        val header = fileSystem.source(path).buffer().use { source ->
            if (!source.request(16)) recoveryRequired("Truncated database header")
            source.readByteArray(16)
        }
        if (!header.contentEquals("SQLite format 3\u0000".encodeToByteArray())) recoveryRequired("Invalid database header")
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            val version = connection.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) }
            if (version > VeyraDatabase.VERSION) throw VeyraException(AppFailure(FailureCode.FUTURE_SCHEMA))
            if (version < 1) recoveryRequired("Unknown database schema")
            val quickCheck = connection.prepare("PRAGMA quick_check(1)").use { it.step() && it.getText(0) == "ok" }
            if (!quickCheck) recoveryRequired("SQLite integrity check failed")
            if (connection.prepare("PRAGMA foreign_key_check").use { it.step() }) recoveryRequired("Broken database relations")
        }
    }

    private fun checkpointAndSync(path: Path) {
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            connection.execSQL("PRAGMA synchronous = FULL")
            connection.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use {
                if (it.step() && it.getLong(0) != 0L) recoveryRequired("Backup checkpoint is busy")
            }
        }
        sync(path)
    }

    private fun writeMarker(path: Path) {
        fileSystem.sink(path).buffer().use { it.writeUtf8("Veyra Room database\n") }
        sync(path)
    }

    private fun sync(path: Path) { fileSystem.openReadWrite(path, mustExist = true).use { it.flush() } }
    private fun sidecar(path: Path, suffix: String): Path = (path.toString() + suffix).toPath()
    private fun recoveryRequired(detail: String): Nothing = throw VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, detail))
}
