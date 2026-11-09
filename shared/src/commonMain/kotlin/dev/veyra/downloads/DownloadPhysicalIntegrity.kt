package dev.veyra.downloads

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.MaterializationAsset
import dev.veyra.model.OfflineAssetKind
import dev.veyra.model.VeyraException
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.LocalMediaValidationInput
import dev.veyra.playback.LocalMediaValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Path

internal enum class DownloadMaterializationCheckPhase { PRE_NATIVE, POST_NATIVE }
internal enum class DownloadMaterializationFileProblem { KIND, MISSING, NOT_REGULAR, SIZE, EXPECTED_HASH, HASH }

/** Actual negative outcomes only, not thrown IO/cancellation/teardown or a relational guard. */
internal sealed interface DownloadMaterializationNegative {
    val input: LocalMediaValidationInput
    data class File(override val input: LocalMediaValidationInput, val phase: DownloadMaterializationCheckPhase,
        val problem: DownloadMaterializationFileProblem) : DownloadMaterializationNegative
    /** Native Invalid can mean unsupported/unavailable; never describe every such result as corrupt bytes. */
    data class Native(override val input: LocalMediaValidationInput, val failure: AppFailure) : DownloadMaterializationNegative
}

/** Identity-free equivalent of the existing owned-file check; reuses its streaming hash. */
internal suspend fun DownloadFiles.verifiedFile(
    input: LocalMediaValidationInput,
    phase: DownloadMaterializationCheckPhase = DownloadMaterializationCheckPhase.PRE_NATIVE,
    onRejected: (DownloadMaterializationNegative) -> Unit = {},
): Path = withContext(Dispatchers.IO) {
    storageOperation {
        val path = ownedPath(input.uri)
        val metadata = fileSystem.metadataOrNull(path)
        val problem = when {
            input.kind != OfflineAssetKind.FILE -> DownloadMaterializationFileProblem.KIND
            metadata == null -> DownloadMaterializationFileProblem.MISSING
            !metadata.isRegularFile -> DownloadMaterializationFileProblem.NOT_REGULAR
            input.sizeBytes <= 0 || metadata.size != input.sizeBytes -> DownloadMaterializationFileProblem.SIZE
            input.sha256?.matches(Regex("[0-9a-f]{64}")) != true -> DownloadMaterializationFileProblem.EXPECTED_HASH
            sha256(path) != input.sha256 -> DownloadMaterializationFileProblem.HASH
            else -> null
        }
        if (problem != null) {
            // Synchronous, non-suspending evidence update before a finally/OS close can mask it.
            onRejected(DownloadMaterializationNegative.File(input, phase, problem))
            throw VeyraException(AppFailure(FailureCode.CORRUPT_MEDIA, "owned_file_integrity"))
        }
        currentCoroutineContext().ensureActive()
        path
    }
}

internal fun MaterializationAsset.validationInput() = LocalMediaValidationInput(
    uri, kind, sizeBytes, mimeType, codecs, sha256, representationId, validatorGeneration,
)

/** Caller holds the exact generation/path lease throughout both file checks and native work. */
internal suspend fun DownloadFiles.validateMaterialization(
    input: LocalMediaValidationInput,
    validator: LocalMediaValidator,
    onRejected: (DownloadMaterializationNegative) -> Unit = {},
): Long? {
    verifiedFile(input, DownloadMaterializationCheckPhase.PRE_NATIVE, onRejected)
    currentCoroutineContext().ensureActive()
    val duration = when (val result = validator.validateLocal(input)) {
        is AssetValidation.Valid -> result.durationMs
        is AssetValidation.Invalid -> {
            onRejected(DownloadMaterializationNegative.Native(input, result.failure))
            throw VeyraException(result.failure)
        }
    }
    currentCoroutineContext().ensureActive()
    verifiedFile(input, DownloadMaterializationCheckPhase.POST_NATIVE, onRejected)
    return duration
}
