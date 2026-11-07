package dev.veyra.downloads

import androidx.room.Room
import dev.veyra.data.AcquisitionRepositoryState
import dev.veyra.data.LibraryPersistence
import dev.veyra.data.LibraryStore
import dev.veyra.data.PhysicalDownloadJoin
import dev.veyra.data.room.AcquisitionGraph
import dev.veyra.data.room.AcquisitionMutation
import dev.veyra.data.room.AcquisitionPersistence
import dev.veyra.data.room.AcquisitionTransactionResult
import dev.veyra.data.room.RoomLibraryPersistence
import dev.veyra.data.room.VeyraDatabase
import dev.veyra.data.room.VeyraDatabaseConstructor
import dev.veyra.data.room.buildVeyraDatabase
import dev.veyra.model.VeyraException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Purpose-specific maintenance-port fault adapter. It wraps the REAL Room persistence with the
 * EXISTING transaction/readback contract; it does not replace canonical commands or seed a graph.
 *
 * beforeCommit runs inside the SQLite transaction (a thrown error rolls every proposed row back).
 * afterCommit runs after the real COMMIT returned but before the command's caller observes it,
 * which is the authored analogue of a post-COMMIT dispatcher/return loss. No rows are fabricated
 * and the stored graph is always the Room-committed one.
 */
internal class DownloadMaintenancePortPersistence(private val room: RoomLibraryPersistence) :
    LibraryPersistence by room, AcquisitionPersistence by room {
    @Volatile var beforeCommit: (AcquisitionGraph, AcquisitionGraph) -> Unit = { _, _ -> }
    @Volatile var afterCommit: suspend (AcquisitionRepositoryState) -> Unit = { }

    override suspend fun <T> transactAcquisition(
        transform: (AcquisitionGraph) -> AcquisitionMutation<T>,
    ): AcquisitionTransactionResult<T> {
        val committed = room.transactAcquisition { before ->
            transform(before).also { beforeCommit(before, it.graph) }
        }
        afterCommit(committed.graph.state())
        return committed
    }

    fun clearFaults() {
        beforeCommit = { _, _ -> }
        afterCommit = { }
    }
}

private fun maintenancePortStore(databasePath: Path): Pair<LibraryStore, DownloadMaintenancePortPersistence> {
    val room = RoomLibraryPersistence(buildVeyraDatabase(Room.databaseBuilder<VeyraDatabase>(
        databasePath.toString(), factory = VeyraDatabaseConstructor::initialize)))
    val backend = DownloadMaintenancePortPersistence(room)
    return LibraryStore(backend) to backend
}

/** A second real store over the fixture's database file, for post-COMMIT claim fault tests. */
internal fun DownloadAcquisitionRoomFixture.maintenancePortStore() = maintenancePortStore(databasePath)
internal fun DownloadExecutorSchema3Fixture.maintenancePortStore() = maintenancePortStore(databasePath)

internal fun DownloadAcquisitionRoomFixture.advance(ms: Long) = clock.advance(ms)

/** Same canonical physical join for the real Room acquisition fixture. */
internal suspend fun join(owner: DownloadAcquisitionRoomOwner, id: String): PhysicalDownloadJoin {
    return kotlin.test.assertNotNull(owner.repository.physicalDownload(id))
}

internal suspend fun DownloadAcquisitionRoomFixture.awaitJob(owner: DownloadAcquisitionRoomOwner, id: String,
    predicate: (PhysicalDownloadJoin) -> Boolean): PhysicalDownloadJoin = withTimeout(15_000) {
    var value = join(owner, id)
    while (!predicate(value)) {
        kotlinx.coroutines.delay(10)
        value = join(owner, id)
    }
    value
}

/**
 * The same sticky-owner contract as the legacy helper, expressed through canonical physical state
 * and the coordinator's actual drain/idle/resume receipts. It never retries a failed cleanup.
 */
internal suspend fun assertMaintenanceBlocked(scheduler: DownloadCoordinator) {
    assertTrue(scheduler.hasPendingCleanupOwners)
    assertFalse(scheduler.dependencyReleaseSafeAfterClose)
    assertEquals(executorRestartRequired, scheduler.lastFailure.value)
    assertEquals(executorRestartRequired, scheduler.schedulingPlan().blockedReason)
    assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { scheduler.drainForBackground() }.failure)
    assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { scheduler.idleDecision() }.failure)
    assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { scheduler.resumePending() }.failure)
}

internal fun AcquisitionRepositoryState.maintenanceLease(id: String): Boolean =
    physicalDownloads[id]?.let { job ->
        job.maintenanceKind != null && job.leaseOwner?.startsWith("download-maintenance") == true
    } == true

/**
 * A real maintenance heartbeat/path checkpoint changes only the lease deadline while keeping the
 * same canonical maintenance lease. Used to target ONE repository update, never a broad relabeler.
 */
internal fun maintenanceHeartbeat(before: AcquisitionRepositoryState, after: AcquisitionRepositoryState, id: String): Boolean {
    val previous = before.physicalDownloads[id] ?: return false
    val next = after.physicalDownloads[id] ?: return false
    return previous.maintenanceKind != null && next.maintenanceKind == previous.maintenanceKind &&
        previous.leaseOwner != null && next.leaseOwner == previous.leaseOwner &&
        (next.leaseExpiresEpochMs ?: 0) > (previous.leaseExpiresEpochMs ?: 0)
}

/** Wait for one exact AppFailure without inventing a scheduler receipt. */
internal suspend fun DownloadCoordinator.awaitFailure(expected: dev.veyra.model.AppFailure) {
    withTimeout(15_000) { lastFailure.first { it == expected } }
}
