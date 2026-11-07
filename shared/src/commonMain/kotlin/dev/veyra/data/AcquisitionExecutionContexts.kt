package dev.veyra.data

import dev.veyra.model.AcquisitionOutcome
import dev.veyra.model.ExecutionBindingId
import dev.veyra.model.FirstResultReceipt
import dev.veyra.source.execution.AcquisitionExecutionContext

/**
 * Pure exact accepted-Single join shared by playback, physical downloads and app projections.
 * This detached projection is not a commit/readback barrier: the caller must still fence its
 * occurrence or job through the existing LibraryStore transaction owner before execution.
 * Public for cross-module composition/UI checks, not a source/native execution authority.
 * It performs no source lookup and cannot turn a schema2 receipt into typed provenance.
 */
fun AcquisitionRepositoryState.executionContext(id: ExecutionBindingId): AcquisitionExecutionContext {
    val binding = bindings.getValue(id)
    val receipt = receipts.getValue(binding.firstReceiptId) as? FirstResultReceipt.Observed
        ?: error("Typed receipt required")
    val record = requests.getValue(receipt.observation.requestId)
    require((record.outcome as? AcquisitionOutcome.Single)?.bindingId == id) { "Accepted Single binding required" }
    return AcquisitionExecutionContext(record.request, receipt, binding, subjects.getValue(binding.subjectId),
        recordings.getValue(requireNotNull(binding.recordingId)))
}
