package dev.veyra.data

import dev.veyra.acquisition.authoredRequest
import dev.veyra.data.room.*
import dev.veyra.downloads.acquisitionDownloadRoomCase
import dev.veyra.model.*
import kotlin.test.*

/**
 * REAL bundled Room close/reopen for a blocked page whose raw ordinal-zero first entry is an
 * otherwise valid video. Source answers are authored; no live query, native playback or audio
 * is claimed. The current-process restore is Room-only and cannot promote the persisted
 * Unplayable outcome to a binding/occurrence after restart.
 */
class RoomAcquisitionRestoreTest {
    @Test fun blockedValidVideoOutcomeSurvivesRealRoomCloseAndReopenWithoutBindingOrSourceWork() = acquisitionDownloadRoomCase {
        val owner = owner()
        val item = music("restore-media")
        val request = authoredRequest("room-restore-request", MediaCapture(item))
            .copy(capturedEpochMs = clock.epochMs())
        val initialQueue = owner.store.acquisitionView().acquisition.queues.getValue(QueueContext.MUSIC)
        val pending = pendingOccurrence(request, "room-restore-occurrence", PlaybackControlsMode.MUSIC)
        owner.repository.admitQueue(QueueAcquisitionAdmission(QueueContext.MUSIC, initialQueue.editRevision, null,
            QueueAdmissionOperation.APPEND, listOf(request), null, listOf(pending), null))
        val admittedQueue = owner.store.acquisitionView().acquisition.queues.getValue(QueueContext.MUSIC)
        val raw = FirstResultObservation(request.id, request.completeTitle,
            SourceEntry(AcquisitionEntryKind.VIDEO, "ABCDEFGHIJK", "Actual first video title",
                "https://www.youtube.com/watch?v=ABCDEFGHIJK"), "https://www.youtube.com/watch?v=ABCDEFGHIJK")
        val claim = assertIs<AcquisitionClaimResult.Claimed>(owner.repository.claim(ClaimAcquisition(request.id,
            AcquisitionAttemptToken(newEntityId("authored-room-restore-attempt")), "authored-room-restore-owner",
            clock.epochMs(), 60_000)))
        val committed = assertIs<AcquisitionCommitResult.Committed>(owner.repository.commit(commandResult(request,
            claim.lease, SourceAcquisitionResult.Unplayable(request.id, raw,
                AcquisitionProblem(AcquisitionProblemCode.FIRST_RESULT_UNAVAILABLE)), clock.epochMs() + 10,
            listOf(OccurrenceAttachmentExpectation(pending.id, request.id, QueueContext.MUSIC, admittedQueue.editRevision)))))
        assertEquals(setOf(pending.id), committed.attachedOccurrences)

        val beforeClose = owner.store.acquisitionView().acquisition
        val record = beforeClose.requests.getValue(request.id)
        assertEquals(AcquisitionJobState.COMMITTED, record.state)
        val unplayable = assertIs<AcquisitionOutcome.Unplayable>(record.outcome)
        assertEquals(AcquisitionFailureStage.SELECTION, unplayable.stage)
        val receiptId = assertNotNull(unplayable.receiptId)
        assertEquals(raw, assertIs<FirstResultReceipt.Observed>(beforeClose.receipts.getValue(receiptId)).observation)
        assertTrue(beforeClose.bindings.isEmpty())
        val occurrence = beforeClose.occurrences.getValue(pending.id)
        assertEquals(OccurrenceSelectionState.UNPLAYABLE, occurrence.selectionState)
        assertNull(occurrence.bindingId)
        assertNull(occurrence.subjectId)
        assertTrue(owner.selector.requests.isEmpty())
        assertTrue(owner.gateway.preparations.isEmpty())
        assertTrue(owner.gateway.transfers.isEmpty())
        owner.legacy.assertUnused()

        // Actual cold close and a fresh Room/store/owner. No source or native owner is retained.
        owner.coordinator.close()
        assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
        owner.store.close()
        val reopenedOwner = owner(store())
        val restored = reopenedOwner.acquisition.restore()
        assertEquals(beforeClose, restored)
        val restoredRecord = restored.requests.getValue(request.id)
        assertEquals(AcquisitionJobState.COMMITTED, restoredRecord.state)
        val restoredUnplayable = assertIs<AcquisitionOutcome.Unplayable>(restoredRecord.outcome)
        assertEquals(unplayable, restoredUnplayable)
        assertEquals(unplayable.receiptId, restoredUnplayable.receiptId)
        assertEquals(unplayable.stage, restoredUnplayable.stage)
        assertEquals(unplayable.problem, restoredUnplayable.problem)
        assertEquals(raw, assertIs<FirstResultReceipt.Observed>(
            restored.receipts.getValue(assertNotNull(restoredUnplayable.receiptId))).observation)
        assertTrue(restored.bindings.isEmpty())
        assertEquals(OccurrenceSelectionState.UNPLAYABLE, restored.occurrences.getValue(pending.id).selectionState)
        assertNull(restored.occurrences.getValue(pending.id).bindingId)
        assertNull(restored.occurrences.getValue(pending.id).subjectId)
        assertTrue(reopenedOwner.selector.requests.isEmpty(), "Restore must not issue a source query")
        assertTrue(reopenedOwner.gateway.preparations.isEmpty())
        assertTrue(reopenedOwner.gateway.transfers.isEmpty())
        assertTrue(reopenedOwner.validations.isEmpty())
        reopenedOwner.legacy.assertUnused()
        assertFailsWith<VeyraException> {
            reopenedOwner.acquisition.boundOccurrence(pending.id, ExecutionBindingId("authored-missing-binding"))
        }
    }
}
