package dev.veyra.data.room

import androidx.room.*
import dev.veyra.data.*
import dev.veyra.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The sole production persistence backend. No encoded AppSnapshot is written to any column/file. */
internal class RoomLibraryPersistence private constructor(
    private val factory: (() -> VeyraDatabase)?,
    private val recoveryFactory: ((String) -> VeyraDatabase)?,
    private val files: RoomDatabaseFiles?,
    initialDatabase: VeyraDatabase?,
) : LibraryPersistence {
    constructor(database: VeyraDatabase) : this(null, null, null, database)
    constructor(factory: (String) -> VeyraDatabase, files: RoomDatabaseFiles) :
        this({ factory(files.databasePath.toString()) }, factory, files, null)

    private var database: VeyraDatabase? = initialDatabase
    private var opened = false
    private var closed = false

    override suspend fun open(): OpenedLibrary {
        check(!closed)
        if (opened) return OpenedLibrary(read())
        files?.prepareForOpen()
        val db = database ?: requireNotNull(factory).invoke().also { database = it }
        try {
            val snapshot = db.useWriterConnection { connection ->
                // Room's WAL default is NORMAL. FULL is deliberate for acknowledged local changes.
                connection.execSQL("PRAGMA synchronous = FULL")
                connection.immediateTransaction {
                    val dao = db.libraryDao()
                    val rows = dao.readRows()
                    if (rows.meta == null) {
                        check(rows == LibraryRows()) { "Partial database seed; refusing to erase orphaned data" }
                        val empty = AppSnapshot()
                        dao.writeDifference(rows, empty.toRows())
                        empty
                    } else rows.toSnapshot().also(::validateLibrarySnapshot)
                }
            }
            files?.markInitialized()
            opened = true
            return OpenedLibrary(snapshot)
        } catch (error: Exception) {
            try { db.close() } catch (closeError: Exception) { error.addSuppressed(closeError) }
            finally { database = null; opened = false }
            throw error
        }
    }

    override suspend fun read(): AppSnapshot {
        val db = requireOpen()
        return db.useReaderConnection { connection ->
            connection.deferredTransaction { db.libraryDao().readRows().toSnapshot().also(::validateLibrarySnapshot) }
        }
    }

    override suspend fun transact(expectedRevision: Long?, transform: (AppSnapshot) -> AppSnapshot): AppSnapshot {
        val db = requireOpen()
        return db.useWriterConnection { connection ->
            connection.execSQL("PRAGMA synchronous = FULL")
            connection.immediateTransaction {
                val dao = db.libraryDao()
                val before = dao.readRows()
                val previous = before.toSnapshot().also(::validateLibrarySnapshot)
                if (expectedRevision != null && previous.revision != expectedRevision) {
                    throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "Stale database revision"))
                }
                val proposed = transform(previous)
                if (proposed == previous) return@immediateTransaction previous
                require(previous.revision < Long.MAX_VALUE)
                val next = proposed.copy(schemaVersion = AppSnapshot.CURRENT_SCHEMA, revision = previous.revision + 1)
                validateLibraryTransition(previous, next)
                dao.writeDifference(before, next.toRows())
                // Immediate transaction commit also checks all deferred foreign-key constraints.
                next
            }
        }
    }

    override fun changes(): Flow<Unit> = requireOpen().libraryDao().revisions().map { Unit }

    override suspend fun backup() {
        val files = files ?: throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "File-backed store required"))
        val stage = files.newBackupStage()
        var primaryFailure: Throwable? = null
        try {
            requireOpen().useWriterConnection { connection ->
                connection.execSQL("PRAGMA synchronous = FULL")
                // SQLite takes a consistent snapshot, including committed WAL pages.
                connection.usePrepared("VACUUM INTO ?") { statement ->
                    statement.bindText(1, stage.toString())
                    statement.step()
                }
            }
            files.commitBackup(stage)
        } catch (error: Throwable) { primaryFailure = error; throw error }
        finally { files.removeStagePreservingFailure(stage, primaryFailure) }
    }

    override suspend fun recoverFromBackup(): OpenedLibrary = withContext(NonCancellable) {
        check(!closed)
        val files = files ?: throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "File-backed store required"))
        database?.close()
        database = null
        opened = false
        val stage = files.prepareRecoveryCandidate()
        var primaryFailure: Throwable? = null
        try {
            // Validate Room identity/schema and all domain relations BEFORE touching the damaged DB.
            val candidate = requireNotNull(recoveryFactory).invoke(stage.toString())
            try {
                candidate.useReaderConnection { connection ->
                    connection.deferredTransaction { candidate.libraryDao().readRows().toSnapshot().also(::validateLibrarySnapshot) }
                }
            } finally { candidate.close() }
            files.installRecovery(stage)
            open().copy(recoveredFromBackup = true)
        } catch (error: Throwable) { primaryFailure = error; throw error }
        finally { files.removeStagePreservingFailure(stage, primaryFailure) }
    }

    override suspend fun close() {
        if (closed) return
        withContext(NonCancellable) {
            try {
                if (opened && files != null) backup()
            } finally {
                try { database?.close() }
                finally {
                    database = null
                    opened = false
                    closed = true
                    files?.close()
                }
            }
        }
    }

    private fun requireOpen(): VeyraDatabase {
        check(opened && !closed) { "Database is not open" }
        return requireNotNull(database)
    }
}
