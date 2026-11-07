package dev.veyra.source.execution

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.FirstResultKind
import dev.veyra.model.PlaybackBinding
import dev.veyra.model.VeyraException
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.source.ResolvePurpose
import kotlinx.coroutines.sync.Mutex

/**
 * Compatibility bridge from the pinned [KmpMediaGateway] to the physical executor's
 * [LegacyPreparedDownloadGateway]. The pinned kmpYT revision exposes only `resolve` + `download`;
 * it has no same-client prepared-transfer capability. This adapter therefore performs one
 * `resolve(DOWNLOAD)` for the exact saved schema2 receipt and then the existing single
 * `download(binding, ...)` call for the same binding/representation. It is not a second transfer
 * engine, does not search, and never selects another first result or representation.
 *
 * Limitations that cannot be repaired in Veyra alone:
 * - the pinned `download` re-plans internally, so the adapter cannot enforce the newer
 *   "at most one expired-link reinspection across prepare+transfer" budget;
 * - the returned representation must exactly match the prepared key/mime/codecs/extension or the
 *   transfer is rejected as corrupt.
 *
 * The prepared value is single-consume and issuer-checked. Unknown historical partial bytes are
 * still fenced by the Room owner; this adapter grants no resumability or Ready authority.
 */
class KmpLegacyPreparedDownloadAdapter(private val gateway: KmpMediaGateway) : LegacyPreparedDownloadGateway {
    private val issuer = Any()

    override suspend fun prepareDownload(
        binding: PlaybackBinding,
        capabilities: EngineCapabilities,
        requiredRepresentationId: String?,
    ): PreparedLegacyDownload {
        requireBinding(binding)
        val planned = gateway.resolve(binding, capabilities, ResolvePurpose.DOWNLOAD)
        // DOWNLOAD resolves must not create a playback bridge owner. If one is returned, close it
        // before failing so a rejected preparation cannot leak a native resource.
        val unexpectedResource = planned.resource
        if (unexpectedResource != null) {
            try {
                unexpectedResource.close()
            } catch (closeFailure: Throwable) {
                val failure = VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE,
                    "legacy_download_unexpected_resource_close_failed"))
                failure.addSuppressed(closeFailure)
                throw failure
            }
            fail(FailureCode.UNSUPPORTED_SOURCE, "legacy_download_unexpected_resource")
        }
        if (planned.executionBinding != null && planned.executionBinding != binding) {
            fail(FailureCode.INVALID_STATE, "legacy_download_binding_changed")
        }
        val representationId = planned.representationId?.takeIf { it.isNotBlank() }
            ?: fail(FailureCode.CORRUPT_MEDIA, "legacy_download_representation_missing")
        if (requiredRepresentationId != null && requiredRepresentationId != representationId) {
            fail(FailureCode.INVALID_STATE, "legacy_download_representation_changed")
        }
        val mime = planned.mimeType?.takeIf { it.isNotBlank() }
            ?: fail(FailureCode.CORRUPT_MEDIA, "legacy_download_mime_missing")
        val codecs = planned.codecs?.takeIf { it.isNotBlank() }
            ?: fail(FailureCode.CORRUPT_MEDIA, "legacy_download_codecs_missing")
        return Prepared(
            issuer = issuer,
            binding = binding,
            capabilities = capabilities,
            requiredRepresentationId = requiredRepresentationId,
            representation = AcquisitionRepresentation(representationId, mime, codecs, extensionFor(mime)),
        )
    }

    override suspend fun download(
        prepared: PreparedLegacyDownload,
        destinationPath: String,
        onProgress: suspend (AcquisitionTransferProgress) -> Unit,
    ): LegacyTransferResult {
        val owned = prepared as? Prepared ?: invalidPrepared()
        if (owned.issuer !== issuer) invalidPrepared()
        // Consume even on cancellation/invalid destination; a later attempt must prepare again.
        if (!owned.consume.tryLock()) invalidPrepared()
        // The lock is deliberately never released: this exact prepared capability is single-use,
        // including after a terminal return or cancellation.
        val result = gateway.download(owned.binding, destinationPath, owned.capabilities,
            owned.requiredRepresentationId, onProgress = { progress ->
                onProgress(AcquisitionTransferProgress(progress.bytesDownloaded, progress.totalBytes,
                    progress.speedBytesPerSecond))
            })
        val actual = AcquisitionRepresentation(result.representationId, result.mimeType, result.codecs, result.extension)
        if (actual != owned.representation) {
            throw VeyraException(AppFailure(FailureCode.CORRUPT_MEDIA, "legacy_download_representation_changed"))
        }
        return LegacyTransferResult(owned.binding, result.bytesWritten, actual)
    }

    private class Prepared(
        val issuer: Any,
        override val binding: PlaybackBinding,
        val capabilities: EngineCapabilities,
        override val requiredRepresentationId: String?,
        override val representation: AcquisitionRepresentation,
    ) : PreparedLegacyDownload {
        val consume = Mutex()
        override fun toString() = "KmpPreparedLegacyDownload(identity=redacted)"
    }

    private fun requireBinding(binding: PlaybackBinding) {
        if (binding.firstResultKind != FirstResultKind.VIDEO || binding.selectionOrdinal != 0 ||
            binding.firstResultId.isBlank() || binding.selectedVideoId != binding.firstResultId ||
            binding.mediaId.isBlank() || binding.queryTitle.isBlank() || binding.firstResultTitle.isBlank()) {
            fail(FailureCode.INVALID_STATE, "legacy_download_binding_identity")
        }
    }

    private fun invalidPrepared(): Nothing =
        throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "legacy_download_prepared_invalid"))

    private fun fail(code: FailureCode, detail: String): Nothing =
        throw VeyraException(AppFailure(code, detail))

    private fun extensionFor(mimeType: String?): String = when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
        "audio/mp4" -> "m4a"
        "audio/webm" -> "webm"
        "audio/ogg" -> "ogg"
        "audio/mpeg" -> "mp3"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/aac" -> "aac"
        else -> "bin"
    }
}
