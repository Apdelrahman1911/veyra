package dev.veyra.acquisition

import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.playback.Clock
import dev.veyra.playback.SystemClock

/**
 * Durable batch admission/selection/owner intent only. Physical transfer, shared-asset checks,
 * file validation/finalization and acknowledged cancellation belong to the existing executor.
 * No parent fake file, fabricated catalogue item or per-batch competing downloader is created.
 */
class DownloadBatchCoordinator(
    private val repository: AcquisitionRepository,
    private val acquisition: AcquisitionCoordinator,
    private val clock: Clock = SystemClock(),
) {
    val transactionOwner: LibraryTransactionOwner get() = repository.transactionOwner
    init { require(repository.transactionOwner === acquisition.transactionOwner) { "Batch selection requires one Room owner" } }
    suspend fun admit(prepared: PreparedAcquisitionAdmission): DownloadBatch {
        val scope = prepared.scope ?: invalid("download_batch_explicit_scope_required")
        if (prepared.occurrences.isEmpty() || prepared.requests.size != prepared.occurrences.size) {
            invalid("download_batch_admission_mismatch")
        }
        val now = clock.epochMs()
        val batch = DownloadBatch(
            DownloadBatchId(newEntityId("download-batch")), scope.id, DownloadBatchIntent.ACTIVE, 0,
            prepared.occurrences.mapIndexed { position, occurrence ->
                val member = occurrence.scopeMember ?: invalid("download_batch_scope_member_required")
                if (member.scopeId != scope.id || occurrence.selectionState != OccurrenceSelectionState.PENDING) {
                    invalid("download_batch_admission_mismatch")
                }
                DownloadBatchMember(position, member.position, occurrence.id,
                    occurrence.requestId ?: invalid("download_batch_request_required"), null, null,
                    DownloadBatchMemberState.PENDING_SELECTION)
            }, now, now,
        )
        return repository.admitBatch(BatchAcquisitionAdmission(prepared.expectedCatalogRevision,
            prepared.requests, scope, prepared.occurrences, batch))
    }

    /** One admitted member per call. A member's child collection is not recursively expanded. */
    suspend fun resolveMember(
        batchId: DownloadBatchId,
        memberPosition: Int,
        expectedIntentRevision: Long,
    ): AcquisitionResolution {
        val batch = repository.state.value.batches[batchId] ?: invalid("download_batch_missing")
        if (batch.intent != DownloadBatchIntent.ACTIVE || batch.intentRevision != expectedIntentRevision) {
            invalid("download_batch_action_stale")
        }
        val member = batch.members.singleOrNull { it.position == memberPosition } ?: invalid("download_batch_member_missing")
        return acquisition.resolve(member.requestId, listOf(OccurrenceAttachmentExpectation(
            member.occurrenceId, member.requestId, context = null, expectedQueueRevision = null,
            batchId = batchId, expectedBatchIntentRevision = expectedIntentRevision,
        )))
    }

    /** This fences only this batch's claims. The return value is not physical stop/delete ACK. */
    suspend fun setIntent(
        batchId: DownloadBatchId,
        expectedIntentRevision: Long,
        intent: DownloadBatchIntent,
        retainCompletedAssets: Boolean = true,
    ): DownloadBatch = repository.updateBatchClaims(UpdateBatchClaims(
        batchId, expectedIntentRevision, intent, retainCompletedAssets, clock.epochMs(),
    ))

    private fun invalid(detail: String): Nothing = throw VeyraException(AppFailure(FailureCode.INVALID_STATE, detail))
}
