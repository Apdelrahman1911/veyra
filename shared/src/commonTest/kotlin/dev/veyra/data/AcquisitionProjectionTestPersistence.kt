package dev.veyra.data

import dev.veyra.data.room.*
import dev.veyra.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Test-only transaction acknowledgement over the ACTUAL pure normalized graph/commands.
 * LibraryStore still owns its normal mutex, canonical facade and coherent publication. This
 * does not execute SQLite, migrations, native I/O, durable readback, restart or crash recovery.
 */
internal class AcquisitionProjectionTestPersistence(initial: AcquisitionGraph = commandGraph()) :
    LibraryPersistence, AcquisitionPersistence {
    var graph: AcquisitionGraph = initial
    val commits = mutableListOf<AcquisitionGraph>()
    var opens = 0
    var reads = 0
    var closes = 0
    var transactions = 0
    var reject: ((AcquisitionGraph) -> Boolean)? = null
    var beforeAcknowledgement: suspend (AcquisitionGraph) -> Unit = {}

    override suspend fun open(): OpenedLibrary {
        opens++
        graph.snapshot(); graph.state(); graph.releaseCatalogState()
        return OpenedLibrary(graph.snapshot(), acquisitionGraph = graph)
    }
    override suspend fun read(): AppSnapshot = readAcquisitionGraph().snapshot()
    override suspend fun readAcquisitionGraph(): AcquisitionGraph { reads++; return graph }

    override suspend fun <T> transactAcquisition(transform: (AcquisitionGraph) -> AcquisitionMutation<T>): AcquisitionTransactionResult<T> {
        transactions++
        val before = graph
        val mutation = transform(before)
        require(mutation.graph.revision == before.revision)
        val projected = if (before.sameRelations(mutation.graph)) before else mutation.graph.withRevision(before.revision + 1)
        projected.snapshot(); projected.state(); projected.releaseCatalogState()
        if (projected !== before) validateAcquisitionTransition(before, projected)
        if (reject?.invoke(projected) == true) throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_backend_rejection"))
        beforeAcknowledgement(projected)
        graph = projected
        if (projected !== before) commits += projected
        return AcquisitionTransactionResult(projected, mutation.result(projected))
    }

    override suspend fun transact(expectedRevision: Long?, transform: (AppSnapshot) -> AppSnapshot): AppSnapshot =
        error("Canonical test fixture must use the existing acquisition transaction path")
    override fun changes(): Flow<Unit> = emptyFlow()
    override suspend fun backup() = Unit
    override suspend fun recoverFromBackup(): OpenedLibrary = error("Not a disk-recovery fixture")
    override suspend fun close() { closes++ }
}
