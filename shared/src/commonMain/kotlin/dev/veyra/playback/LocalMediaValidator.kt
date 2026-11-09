package dev.veyra.playback

import dev.veyra.model.OfflineAssetKind

/**
 * Identity-free local decoder/header/duration input. File ownership and exact size/hash/
 * representation/validator-generation validation remain the caller's separate obligations.
 * No MediaItem, RSS identity or fake OfflineAsset is needed for a neutral source recording.
 */
data class LocalMediaValidationInput(
    val uri: String,
    val kind: OfflineAssetKind,
    val sizeBytes: Long,
    val mimeType: String?,
    val codecs: String?,
    val sha256: String?,
    val representationId: String?,
    val validatorGeneration: Long,
) {
    init { require(sizeBytes >= 0 && validatorGeneration >= 0) }
    override fun toString() = "LocalMediaValidationInput(kind=$kind,uri=redacted)"
}

/**
 * A side-effect-free observation of the ORIGINAL native reader's positive release receipt.
 * The implementation must strongly retain that exact decoder/cleanup owner and be safe to
 * observe from another thread. False, an exception, or missing evidence is never a release.
 * Calling this MUST NOT close/retry/destroy, open a replacement decoder, or infer release from
 * silence, an empty Java/Kotlin field, cancellation, or a thrown native teardown operation.
 */
fun interface LocalMediaValidationReaderRelease {
    fun isReleased(): Boolean
}

/**
 * Native local validation cannot prove its reader released this exact input. The caller must
 * retain BOTH this owner and its existing path/gate/slot leases before classifying the failure.
 * Keep it as the first cleanup barrier until [reader] positively observes the original release;
 * no later path deletion/finalization or shutdown ACK may bypass that barrier. Even an input
 * mismatch remains owned/fail-closed. Do not replace this type with a generic Invalid result.
 * No native diagnostic, transport locator or nested exception is exposed in its message.
 */
class LocalMediaValidationReaderUnacknowledged(
    val input: LocalMediaValidationInput,
    val reader: LocalMediaValidationReaderRelease,
) : Exception("local_validation_reader_unacknowledged")

/**
 * Implement on the EXISTING platform validator using its same local primitive as AssetValidator.
 * No independent native owner, network access, fresh source query or fabricated catalog item.
 * Cancellation awaits owned native cleanup. A result does not itself publish Ready or grant a
 * future playback reader: callers fence the exact immutable materialization separately.
 * If native cleanup lacks a positive receipt, throw [LocalMediaValidationReaderUnacknowledged]
 * with the exact owned input/reader, including on cancellation and construction-failure paths.
 */
fun interface LocalMediaValidator {
    suspend fun validateLocal(input: LocalMediaValidationInput): AssetValidation
}
