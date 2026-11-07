package dev.veyra.data

import androidx.sqlite.execSQL
import dev.veyra.data.room.*
import dev.veyra.model.*
import kotlinx.coroutines.*
import kotlin.test.*

/** Actual Room commits/readback and two facades, with invalidation deliberately withheld. */
class CatalogLibraryUndoRoomTest {
    @Test fun witnessedPlaybackDownloadAndSettingsCommitsKeepUndoWithoutRestoringTheirState() = catalogLibraryRoomCase {
        val selection = selected()
        val item = assertIs<MediaCapture>(selection.request.metadata).media
        val originalSaved = CatalogLibrarySavedFrame.of(graph())
        val receipt = assertNotNull(store.editCatalogLibrary { it.copy(favoriteIds = setOf(item.id)) }.undo)
        val stamp = store.catalogLibraryUndoAvailability.value
        var playback = store.acquisition.playbackView()
        fun expected(): ExecutionQueueExpectation {
            val queue = playback.acquisition.queues.getValue(QueueContext.MUSIC)
            return ExecutionQueueExpectation(QueueContext.MUSIC, queue.editRevision, queue.currentOccurrenceId)
        }
        playback = store.acquisition.applyPlayback(AcquisitionPlaybackCommand.Activate(expected(),
            selection.occurrence.id, selection.binding, QueueContext.MUSIC, 0, 1f, false, 123_100, null))
        playback = store.acquisition.applyPlayback(AcquisitionPlaybackCommand.AdmitLoad(expected(),
            ExecutionLoadAdmission(selection.binding, null, "authored-native-epoch", 1, 0, null, 123_110)))
        val checkpoint = OccurrenceCheckpoint(selection.occurrence.id, selection.binding, 12_000, 120_000,
            123_120, "authored-native-epoch", 1, 1, false)
        store.acquisition.applyPlayback(AcquisitionPlaybackCommand.Checkpoint(ExecutionCheckpointWrite(
            QueueContext.MUSIC, checkpoint, "authored-history", 123_110)))

        val admission = assertIs<PhysicalDownloadCommit.Committed<StandalonePhysicalDownloadAdmission>>(
            store.acquisition.admitStandalonePhysicalDownload(AdmitStandalonePhysicalDownload(selection.request.id,
                selection.binding, "authored-download", DownloadClaimId("authored-claim"), 123_200))).value
        val claim = assertIs<PhysicalDownloadCommit.Committed<ClaimedPhysicalDownload>>(
            store.acquisition.claimPhysicalDownloadAttempt(ClaimPhysicalDownloadAttempt(admission.physical.job.id,
                admission.physical.job.producer, admission.physical.job.ownershipGeneration, "authored-attempt",
                "authored-worker", 123_210, 60_000, "authored/staging/file.part"))).value
        assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(store.acquisition.selectPhysicalDownloadRepresentation(
            SelectPhysicalDownloadRepresentation(claim.fence, "authored-representation", 0, null, null, 123_220)))
        assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(store.acquisition.heartbeatPhysicalDownload(
            HeartbeatPhysicalDownload(claim.fence, 123_230, 60_000, 40, 100)))
        store.update { it.copy(settings = it.settings.copy(offlineOnly = true),
            navigation = mapOf("destination" to "Library")) }.getOrThrow()
        store.upsertCatalog(listOf(item.copy(title = "Corrected complete metadata title — عنوان مصحح"))).getOrThrow()
        val beforeUndo = graph()
        assertSame(stamp, store.catalogLibraryUndoAvailability.value)
        assertTrue(stamp.accepts(receipt), "Known same-owner unrelated commits must not disable Undo while listening")
        assertEquals(checkpoint, store.acquisition.playbackView().checkpoints[selection.occurrence.id])
        assertEquals(40L, beforeUndo.state().physicalDownloads.getValue(admission.physical.job.id).bytesDownloaded)
        store.undoCatalogLibrary(receipt)
        val afterUndo = graph()
        assertEquals(originalSaved, CatalogLibrarySavedFrame.of(afterUndo))
        assertOnlySavedRelationsChanged(beforeUndo, afterUndo)
        assertEquals(beforeUndo.snapshot().media, afterUndo.snapshot().media)
        assertEquals(beforeUndo.rows.occurrenceCheckpointRows, afterUndo.rows.occurrenceCheckpointRows)
        assertEquals(beforeUndo.library.attempts, afterUndo.library.attempts)
        assertFalse(store.catalogLibraryUndoAvailability.value.accepts(receipt))
    }

    @Test fun mediaOnlyCorrectionsAndActualNoOpsDoNotReplaceTheSavedReceipt() = catalogLibraryRoomCase {
        val item = music()
        val receipt = assertNotNull(store.editCatalogLibrary { it.copy(media = mapOf(item.id to item), favoriteIds = setOf(item.id)) }.undo)
        val initialRevision = store.state.value.revision
        val stamp = store.catalogLibraryUndoAvailability.value
        assertNull(store.editCatalogLibrary { it }.undo)
        store.update { it }.getOrThrow()
        store.acquisitionView(); store.flush().getOrThrow()
        assertEquals(initialRevision, store.state.value.revision)
        val corrected = item.copy(title = "Metadata corrected after save", localArtworkPath = "authored/cache/new.jpg")
        assertNull(store.editCatalogLibrary { it.copy(media = mapOf(item.id to corrected)) }.undo)
        assertEquals(initialRevision + 1, store.state.value.revision)
        assertSame(stamp, store.catalogLibraryUndoAvailability.value)
        val beforeUndo = graph()
        store.undoCatalogLibrary(receipt)
        assertEquals(corrected, store.state.value.media.getValue(item.id))
        assertTrue(store.state.value.favoriteIds.isEmpty())
        assertOnlySavedRelationsChanged(beforeUndo, graph())
    }

    @Test fun ownSavedAbaInvalidatesEarlierReceiptEvenWhenEverySavedRowMatchesAgain() = catalogLibraryRoomCase {
        val item = music()
        val first = assertNotNull(store.editCatalogLibrary { it.copy(media = mapOf(item.id to item), favoriteIds = setOf(item.id)) }.undo)
        val firstFrame = CatalogLibrarySavedFrame.of(graph())
        store.editCatalogLibrary { it.copy(favoriteIds = emptySet()) }
        val latest = assertNotNull(store.editCatalogLibrary { it.copy(favoriteIds = setOf(item.id)) }.undo)
        val before = graph()
        assertEquals(firstFrame, CatalogLibrarySavedFrame.of(before))
        assertFalse(store.catalogLibraryUndoAvailability.value.accepts(first))
        assertTrue(store.catalogLibraryUndoAvailability.value.accepts(latest))
        assertFailsWith<IllegalArgumentException> { store.undoCatalogLibrary(first) }
        assertUnchanged(before)
    }

    @Test fun unchangedProjectionWithDifferentCanonicalLineageInvalidatesUndo() = catalogLibraryRoomCase {
        val selection = selected()
        val item = assertIs<MediaCapture>(selection.request.metadata).media
        val receipt = assertNotNull(store.editCatalogLibrary { it.copy(favoriteIds = setOf(item.id)) }.undo)
        val previous = graph()
        installTypedSaved(selection, emptyList())
        val before = graph()
        assertEquals(previous.snapshot().copy(revision = before.revision), before.snapshot())
        assertNotEquals(CatalogLibrarySavedFrame.of(previous), CatalogLibrarySavedFrame.of(before))
        assertEquals(SavedLibraryOrigin.TYPED, before.rows.favoriteSubjectRows.single().origin)
        assertFalse(store.catalogLibraryUndoAvailability.value.accepts(receipt))
        assertFailsWith<IllegalArgumentException> { store.undoCatalogLibrary(receipt) }
        assertUnchanged(before)
    }

    @Test fun foreignOwnerCloseAndReopenCannotReuseAProcessLocalReceipt() = catalogLibraryRoomCase {
        val item = music()
        val receipt = assertNotNull(store.editCatalogLibrary { it.copy(media = mapOf(item.id to item), favoriteIds = setOf(item.id)) }.undo)
        val before = graph()
        val foreign = facade().store
        foreign.initialize().getOrThrow()
        assertFalse(foreign.catalogLibraryUndoAvailability.value.accepts(receipt))
        assertFailsWith<IllegalArgumentException> { foreign.undoCatalogLibrary(receipt) }
        assertUnchanged(before, foreign)
        store.close()
        assertFalse(store.catalogLibraryUndoAvailability.value.accepts(receipt))
        assertFailsWith<VeyraException> { store.undoCatalogLibrary(receipt) }
        val reopened = facade().store
        reopened.initialize().getOrThrow()
        assertFalse(reopened.catalogLibraryUndoAvailability.value.accepts(receipt))
        assertFailsWith<IllegalArgumentException> { reopened.undoCatalogLibrary(receipt) }
        assertUnchanged(before, reopened)
    }

    @Test fun exceptionalCloseStillInvalidatesOwnerIncarnation() = catalogLibraryRoomCase {
        val item = music()
        val receipt = assertNotNull(store.editCatalogLibrary { it.copy(media = mapOf(item.id to item), favoriteIds = setOf(item.id)) }.undo)
        probe.afterClose = { error("authored_after_real_room_close") }
        assertFailsWith<IllegalStateException> { store.close() }
        assertEquals(StoreStatus.Closed, store.status.value)
        assertFalse(store.catalogLibraryUndoAvailability.value.accepts(receipt))
        probe.afterClose = {}
        val reopened = facade().store
        reopened.initialize().getOrThrow()
        assertEquals(setOf(item.id), reopened.state.value.favoriteIds)
    }

    @Test fun concurrentUndoConsumesExactlyOnceAtTheSerializedRoomCommit() = catalogLibraryRoomCase {
        val item = music()
        val receipt = assertNotNull(store.editCatalogLibrary { it.copy(media = mapOf(item.id to item), favoriteIds = setOf(item.id)) }.undo)
        val revision = store.state.value.revision
        val results = coroutineScope {
            List(2) { async(Dispatchers.Default) { runCatching { store.undoCatalogLibrary(receipt) } } }.awaitAll()
        }
        assertEquals(1, results.count { it.isSuccess })
        assertIs<IllegalArgumentException>(results.single { it.isFailure }.exceptionOrNull())
        assertEquals(revision + 1, store.state.value.revision)
        assertTrue(store.state.value.favoriteIds.isEmpty())
        assertTrue(graph().rows.favoriteSubjectRows.isEmpty())
    }

    @Test fun externalSavedAbaWithoutAnyNotificationFailsInsideTheActualUndoTransaction() = catalogLibraryRoomCase {
        val item = music()
        val receipt = assertNotNull(store.editCatalogLibrary { it.copy(media = mapOf(item.id to item), favoriteIds = setOf(item.id)) }.undo)
        val local = graph()
        val foreign = facade().store
        foreign.initialize().getOrThrow()
        foreign.editCatalogLibrary { it.copy(favoriteIds = emptySet()) }
        foreign.editCatalogLibrary { it.copy(favoriteIds = setOf(item.id)) }
        val actual = graph(foreign)
        assertEquals(CatalogLibrarySavedFrame.of(local), CatalogLibrarySavedFrame.of(actual))
        assertEquals(local.revision + 2, actual.revision)
        assertEquals(local.snapshot(), store.state.value, "No invalidation has been delivered")
        assertTrue(store.catalogLibraryUndoAvailability.value.accepts(receipt), "Advisory hint is not authority")
        assertFailsWith<IllegalArgumentException> { store.undoCatalogLibrary(receipt) }
        assertTrue(actual.sameRelations(graph(foreign)))
        graph()
        assertFalse(store.catalogLibraryUndoAvailability.value.accepts(receipt))
    }

    @Test fun ownUnrelatedCommitAfterExternalAbaCannotConvertAnUnknownGapIntoContinuity() = catalogLibraryRoomCase {
        val item = music()
        val receipt = assertNotNull(store.editCatalogLibrary { it.copy(media = mapOf(item.id to item), favoriteIds = setOf(item.id)) }.undo)
        val foreign = facade().store
        foreign.initialize().getOrThrow()
        foreign.editCatalogLibrary { it.copy(favoriteIds = emptySet()) }
        foreign.editCatalogLibrary { it.copy(favoriteIds = setOf(item.id)) }
        store.update { it.copy(navigation = mapOf("destination" to "Podcasts")) }.getOrThrow()
        val before = graph()
        assertFalse(store.catalogLibraryUndoAvailability.value.accepts(receipt))
        assertFailsWith<IllegalArgumentException> { store.undoCatalogLibrary(receipt) }
        assertUnchanged(before)
        assertEquals(setOf(item.id), before.snapshot().favoriteIds)
        assertEquals("Podcasts", before.snapshot().navigation["destination"])
    }

    @Test fun observingAnUnknownExternalUnrelatedRevisionConservativelyInvalidatesUndo() = catalogLibraryRoomCase {
        val item = music()
        val receipt = assertNotNull(store.editCatalogLibrary { it.copy(media = mapOf(item.id to item), favoriteIds = setOf(item.id)) }.undo)
        val before = graph()
        val foreign = facade().store
        foreign.initialize().getOrThrow()
        foreign.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow()
        val after = graph()
        assertEquals(CatalogLibrarySavedFrame.of(before), CatalogLibrarySavedFrame.of(after))
        assertFalse(store.catalogLibraryUndoAvailability.value.accepts(receipt))
        assertFailsWith<IllegalArgumentException> { store.undoCatalogLibrary(receipt) }
        assertUnchanged(after)
    }

    @Test fun externalCommitBetweenOwnCommitAndPublicationCannotMakeAFalseUndoAuthority() = catalogLibraryRoomCase {
        val item = music()
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val previous = store.state.value
        val foreign = facade().store
        foreign.initialize().getOrThrow()
        coroutineScope {
            probe.afterCommit = { committed.complete(Unit); release.await() }
            val edit = async(Dispatchers.Default) { store.editCatalogLibrary {
                it.copy(media = mapOf(item.id to item), favoriteIds = setOf(item.id))
            } }
            try {
                committed.await()
                assertFalse(edit.isCompleted, "No receipt has been returned before post-COMMIT publication")
                assertEquals(previous, store.state.value)
                withSql(path) { assertEquals(1L, it.longValue("SELECT count(*) FROM favorite_subjects")) }
                foreign.update { it.copy(navigation = mapOf("destination" to "Library")) }.getOrThrow()
                val actual = graph(foreign)
                release.complete(Unit)
                val receipt = assertNotNull(edit.await().undo)
                assertTrue(store.catalogLibraryUndoAvailability.value.accepts(receipt))
                assertTrue(actual.revision > store.state.value.revision)
                assertFailsWith<IllegalArgumentException> { store.undoCatalogLibrary(receipt) }
                assertTrue(actual.sameRelations(graph(foreign)))
            } finally {
                release.complete(Unit)
                probe.afterCommit = {}
                withContext(NonCancellable) { edit.cancelAndJoin() }
            }
        }
    }

    @Test fun sqlAbortDuringUndoRollsBackEveryRowAndDoesNotConsumeTheReceipt() = catalogLibraryRoomCase {
        val item = music()
        val receipt = assertNotNull(store.editCatalogLibrary { it.copy(media = mapOf(item.id to item), favoriteIds = setOf(item.id)) }.undo)
        val before = graph()
        val stamp = store.catalogLibraryUndoAvailability.value
        withSql(path) { it.execSQL("CREATE TRIGGER abort_undo BEFORE DELETE ON favorite_subjects BEGIN SELECT RAISE(ABORT, 'authored_undo_abort'); END") }
        try {
            assertFails { store.undoCatalogLibrary(receipt) }
            assertUnchanged(before)
            assertSame(stamp, store.catalogLibraryUndoAvailability.value)
            assertTrue(stamp.accepts(receipt))
        } finally { withSql(path) { it.execSQL("DROP TRIGGER abort_undo") } }
        store.undoCatalogLibrary(receipt)
        assertEquals(before.revision + 1, store.state.value.revision)
        assertTrue(store.state.value.favoriteIds.isEmpty())
        assertFalse(store.catalogLibraryUndoAvailability.value.accepts(receipt))
    }

    @Test fun rejectedNewSavedEditDoesNotInvalidateAPreviouslyCommittedUndo() = catalogLibraryRoomCase {
        val item = music()
        val receipt = assertNotNull(store.editCatalogLibrary { it.copy(media = mapOf(item.id to item), favoriteIds = setOf(item.id)) }.undo)
        val before = graph()
        val stamp = store.catalogLibraryUndoAvailability.value
        withSql(path) { it.execSQL("CREATE TRIGGER ignore_member BEFORE INSERT ON playlist_members BEGIN SELECT RAISE(IGNORE); END") }
        try {
            assertFailsWith<IllegalStateException> { store.editCatalogLibrary { it.copy(playlists = listOf(
                Playlist("rejected", "Must roll back", listOf(PlaylistEntry("rejected-entry", item.id))))) }
            }
            assertUnchanged(before)
            assertSame(stamp, store.catalogLibraryUndoAvailability.value)
            assertTrue(stamp.accepts(receipt))
        } finally { withSql(path) { it.execSQL("DROP TRIGGER ignore_member") } }
        store.undoCatalogLibrary(receipt)
        assertTrue(store.state.value.favoriteIds.isEmpty())
        assertTrue(store.state.value.playlists.isEmpty())
    }

    @Test fun failedReturnAfterPositiveCommitIsNotRollbackOrPermissionToRestoreAnOldReceipt() = catalogLibraryRoomCase {
        val item = music()
        val receipt = assertNotNull(store.editCatalogLibrary { it.copy(media = mapOf(item.id to item), favoriteIds = setOf(item.id)) }.undo)
        val before = graph()
        probe.afterCommit = { error("authored_committed_but_return_failed") }
        try {
            assertFailsWith<IllegalStateException> { store.editCatalogLibrary { it.copy(favoriteIds = emptySet()) } }
        } finally { probe.afterCommit = {} }
        withSql(path) { sql ->
            assertEquals(before.revision + 1, sql.longValue("SELECT revision FROM store_meta WHERE id=0"))
            assertEquals(0L, sql.longValue("SELECT count(*) FROM favorite_subjects"))
        }
        assertEquals(before.snapshot(), store.state.value, "Failure to return a commit cannot publish its receipt")
        assertTrue(store.catalogLibraryUndoAvailability.value.accepts(receipt), "The hint can lag an unknown committed return")
        assertFailsWith<IllegalArgumentException> { store.undoCatalogLibrary(receipt) }
        val actual = graph()
        assertTrue(actual.rows.favoriteSubjectRows.isEmpty())
        assertFalse(store.catalogLibraryUndoAvailability.value.accepts(receipt))
        assertOnlySavedRelationsChanged(before, actual)
    }

    @Test fun cancellationAfterPositiveCommitPublishesAndNeverResurrectsThePreviousReceipt() = catalogLibraryRoomCase {
        val item = music()
        val first = assertNotNull(store.editCatalogLibrary { it.copy(media = mapOf(item.id to item), favoriteIds = setOf(item.id)) }.undo)
        val previous = graph()
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coroutineScope {
            probe.afterCommit = { committed.complete(Unit); release.await() }
            val edit = async(Dispatchers.Default) { store.editCatalogLibrary { it.copy(favoriteIds = emptySet()) } }
            try {
                committed.await()
                withSql(path) { assertEquals(0L, it.longValue("SELECT count(*) FROM favorite_subjects")) }
                assertEquals(previous.snapshot(), store.state.value)
                edit.cancel()
                release.complete(Unit)
                edit.join()
                assertTrue(edit.isCancelled)
                assertFailsWith<CancellationException> { edit.await() }
                assertEquals(previous.revision + 1, store.state.value.revision)
                assertTrue(store.state.value.favoriteIds.isEmpty())
                assertFalse(store.catalogLibraryUndoAvailability.value.accepts(first))
                assertFailsWith<IllegalArgumentException> { store.undoCatalogLibrary(first) }
                val actual = graph()
                assertTrue(actual.rows.favoriteSubjectRows.isEmpty())
                assertOnlySavedRelationsChanged(previous, actual)
            } finally {
                release.complete(Unit)
                probe.afterCommit = {}
                withContext(NonCancellable) { edit.cancelAndJoin() }
            }
        }
    }
}
