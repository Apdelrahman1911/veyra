package dev.veyra.data

import dev.veyra.acquisition.authoredRequest
import dev.veyra.acquisition.authoredSingle
import dev.veyra.data.room.*
import dev.veyra.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.nio.file.Path
import kotlin.test.*

/**
 * Real bundled SQLite/Room. Only invalidation delivery and post-COMMIT return can be held;
 * both persistence interfaces still delegate to the same real Room backend. No media/network.
 */
internal class CatalogLibraryRoomProbe(private val room: RoomLibraryPersistence) :
    LibraryPersistence by room, AcquisitionPersistence by room {
    @Volatile var afterCommit: suspend () -> Unit = {}
    @Volatile var afterClose: () -> Unit = {}
    override fun changes(): Flow<Unit> = emptyFlow() // Deliberately no Flow-based continuity proof.
    override suspend fun <T> transactAcquisition(transform: (AcquisitionGraph) -> AcquisitionMutation<T>): AcquisitionTransactionResult<T> {
        val result = room.transactAcquisition(transform)
        afterCommit()
        return result
    }
    override suspend fun close() { room.close(); afterClose() }
}

internal data class CatalogLibraryRoomHandle(val store: LibraryStore, val probe: CatalogLibraryRoomProbe)

internal data class CatalogTypedSelection(val request: AcquisitionRequest, val occurrence: ExecutionOccurrence) {
    val binding: ExecutionBindingId get() = requireNotNull(occurrence.bindingId)
    val subject: ExecutableSubjectId get() = requireNotNull(occurrence.subjectId)
    fun favorite() = FavoriteSubjectEntity(subject.value, SavedLibraryOrigin.TYPED, request.id.value,
        binding.value, occurrence.id.value, occurrence.controlsMode)
    fun member(id: String, playlist: String, position: Int) = PlaylistMemberEntity(id, playlist, subject.value,
        SavedLibraryOrigin.TYPED, request.id.value, binding.value, occurrence.id.value, occurrence.controlsMode, position)
}

internal class CatalogLibraryRoomFixture(
    val path: Path = roomWorkDirectory("catalogue-library-edits").resolve("library.db"),
) {
    private val handles = mutableListOf<CatalogLibraryRoomHandle>()
    val first: CatalogLibraryRoomHandle = facade()
    val store: LibraryStore get() = first.store
    val probe: CatalogLibraryRoomProbe get() = first.probe

    fun facade(): CatalogLibraryRoomHandle {
        val probe = CatalogLibraryRoomProbe(RoomLibraryPersistence(testDatabase(path)))
        return CatalogLibraryRoomHandle(LibraryStore(probe), probe).also(handles::add)
    }

    suspend fun graph(owner: LibraryStore = store): AcquisitionGraph = owner.readAcquisition { it }

    /** Only source facts are authored; capture/admission/claim/commit are actual Room commands. */
    suspend fun selected(
        label: String = "selected-catalogue",
        metadata: RequestedMetadata = MediaCapture(music()),
        mode: PlaybackControlsMode = PlaybackControlsMode.MUSIC,
    ): CatalogTypedSelection {
        val request = authoredRequest(label, metadata)
        val context = QueueContext.valueOf(mode.name)
        val before = store.acquisitionView().acquisition.queues.getValue(context)
        val pending = pendingOccurrence(request, "occurrence-$label", mode)
        store.acquisition.admitQueue(QueueAcquisitionAdmission(context, before.editRevision, null,
            QueueAdmissionOperation.APPEND, listOf(request), null, listOf(pending), null))
        val queue = store.acquisitionView().acquisition.queues.getValue(context)
        val lease = assertIs<AcquisitionClaimResult.Claimed>(store.acquisition.claim(ClaimAcquisition(request.id,
            AcquisitionAttemptToken("attempt-$label"), "authored-catalogue-fixture", 123_010, 1_000))).lease
        val committed = assertIs<AcquisitionCommitResult.Committed>(store.acquisition.commit(commandResult(request,
            lease, authoredSingle(request), attachments = listOf(OccurrenceAttachmentExpectation(pending.id,
                request.id, context, queue.editRevision)))))
        assertEquals(setOf(pending.id), committed.attachedOccurrences)
        return CatalogTypedSelection(request, store.acquisitionView().acquisition.occurrences.getValue(pending.id))
    }

    /** Existing typed saved-row fixture, not a public metadata command that mints provenance. */
    suspend fun installTypedSaved(selection: CatalogTypedSelection, members: List<PlaylistMemberEntity>) {
        store.transactAcquisition { before -> AcquisitionMutation(before.copy(rows = before.rows.copy(
            favoriteSubjectRows = before.rows.favoriteSubjectRows.withRow(selection.favorite()) { it.subjectId },
            playlistMemberRows = members.fold(before.rows.playlistMemberRows) { rows, row -> rows.withRow(row) { it.id } },
        ))) { Unit } }
    }

    suspend fun assertUnchanged(before: AcquisitionGraph, owner: LibraryStore = store) {
        assertEquals(before.snapshot(), owner.state.value)
        val actual = graph(owner)
        assertTrue(before.sameRelations(actual), "All rows and revision must remain unchanged")
    }

    suspend fun close() {
        handles.forEach { it.probe.afterCommit = {}; it.probe.afterClose = {} }
        var failure: Throwable? = null
        handles.asReversed().forEach { handle ->
            try { handle.store.close() } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}

internal fun catalogLibraryRoomCase(block: suspend CatalogLibraryRoomFixture.() -> Unit): Unit = runBlocking {
    val fixture = CatalogLibraryRoomFixture()
    try {
        fixture.store.initialize().getOrThrow()
        withTimeout(30_000) { fixture.block() }
    } finally { withContext(NonCancellable) { fixture.close() } }
}

/** Equality including normalized hidden rows and revision; not merely a public snapshot. */
internal fun assertOnlySavedRelationsChanged(before: AcquisitionGraph, after: AcquisitionGraph) {
    val expected = CatalogLibrarySavedFrame.of(after).restore(before).withRevision(after.revision)
    assertTrue(expected.sameRelations(after), "Undo must preserve every unrelated raw relation")
}
