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
) : LibraryPersistence, AcquisitionPersistence {
    constructor(database: VeyraDatabase) : this(null, null, null, database)
    constructor(factory: (String) -> VeyraDatabase, files: RoomDatabaseFiles) :
        this({ factory(files.databasePath.toString()) }, factory, files, null)

    private var database: VeyraDatabase? = initialDatabase
    private var opened = false
    private var closed = false

    override suspend fun open(): OpenedLibrary {
        check(!closed)
        if (opened) return readAcquisitionGraph().let { OpenedLibrary(it.snapshot(), acquisitionGraph = it) }
        files?.prepareForOpen()
        val db = database ?: requireNotNull(factory).invoke().also { database = it }
        try {
            val graph = db.useWriterConnection { connection ->
                connection.execSQL("PRAGMA synchronous = FULL")
                connection.immediateTransaction {
                    val before = db.readCompleteGraph()
                    if (before.library.meta == null) {
                        check(before.library == LibraryRows() && before.rows == AcquisitionRows()) {
                            "Partial database seed; refusing to erase orphaned data"
                        }
                        val library = AppSnapshot().toRows()
                        val seeded = AcquisitionGraph(library, seedAcquisitionRows(library))
                        seeded.validateCompleteGraph()
                        db.libraryDao().writeDifference(before.library, seeded.library)
                        db.acquisitionDao().writeAcquisitionDifference(before.rows, seeded.rows)
                        db.verifyWrittenGraph(seeded)
                    } else before.also { it.validateCompleteGraph() }
                }
            }
            files?.markInitialized()
            opened = true
            return OpenedLibrary(graph.snapshot(), acquisitionGraph = graph)
        } catch (error: Exception) {
            try { db.close() } catch (closeError: Exception) { error.addSuppressed(closeError) }
            finally { database = null; opened = false }
            throw error
        }
    }

    override suspend fun read(): AppSnapshot = readAcquisitionGraph().snapshot()

    override suspend fun readAcquisitionGraph(): AcquisitionGraph {
        val db = requireOpen()
        return db.useReaderConnection { connection ->
            connection.deferredTransaction { db.readCompleteGraph().also { it.validateCompleteGraph() } }
        }
    }

    /** Compatibility catalogue/settings edits use the exact same schema3 transaction path. */
    override suspend fun transact(expectedRevision: Long?, transform: (AppSnapshot) -> AppSnapshot): AppSnapshot =
        transactAcquisition { before ->
            if (expectedRevision != null && before.revision != expectedRevision) {
                throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "Stale database revision"))
            }
            AcquisitionMutation(before.applyCatalogSnapshot(transform(before.snapshot()))) { it.snapshot() }
        }.value

    override suspend fun <T> transactAcquisition(
        transform: (AcquisitionGraph) -> AcquisitionMutation<T>,
    ): AcquisitionTransactionResult<T> {
        val db = requireOpen()
        return db.useWriterConnection { connection ->
            connection.execSQL("PRAGMA synchronous = FULL")
            connection.immediateTransaction {
                val before = db.readCompleteGraph().also { it.validateCompleteGraph() }
                val mutation = transform(before)
                require(mutation.graph.revision == before.revision) { "Only the transaction owner advances revision" }
                val actual = if (before.sameRelations(mutation.graph)) before else {
                    require(before.revision < Long.MAX_VALUE)
                    val intended = mutation.graph.withRevision(before.revision + 1)
                    intended.validateCompleteGraph()
                    validateAcquisitionTransition(before, intended)
                    // All related FK edges are deferred. Every changed raw relation is included
                    // in readback; neither an ignored upsert nor a trigger can bless a false result.
                    db.libraryDao().writeDifference(before.library, intended.library)
                    db.acquisitionDao().writeAcquisitionDifference(before.rows, intended.rows)
                    db.verifyWrittenGraph(intended)
                }
                // Computed from the actual re-read graph. Returning from immediateTransaction
                // still must successfully commit (including all deferred FK constraints).
                AcquisitionTransactionResult(actual, mutation.result(actual))
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
                    connection.deferredTransaction { candidate.readCompleteGraph().validateCompleteGraph() }
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

    private suspend fun VeyraDatabase.readCompleteGraph(): AcquisitionGraph =
        AcquisitionGraph(libraryDao().readRows(), acquisitionDao().readAcquisitionRows())

    private suspend fun VeyraDatabase.verifyWrittenGraph(expected: AcquisitionGraph): AcquisitionGraph {
        val actual = readCompleteGraph().also { it.validateCompleteGraph() }
        check(actual.sameRelations(expected)) { "Relational write did not match the complete intended graph" }
        check(actual.snapshot() == expected.snapshot() && actual.state() == expected.state() &&
            actual.releaseCatalogState() == expected.releaseCatalogState()) {
            "Relational write projection did not match the intended revision"
        }
        return actual
    }
}

private fun AcquisitionGraph.validateCompleteGraph() {
    snapshot()
    state()
    releaseCatalogState()
}
