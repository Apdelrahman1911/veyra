package dev.veyra.source.execution

import dev.veyra.model.PlaybackBinding
import dev.veyra.playback.EngineCapabilities

/**
 * Opaque prepared download for an already known schema2 PlaybackBinding, not a typed request.
 * These public observations do not authorize a Room job/attempt or unknown partial bytes.
 * No URI, headers, native player or PlaybackResource is exposed or opened by preparation.
 * Never serialize/reconstruct this capability; foreign/client-forged implementations are
 * rejected before reading public getters or doing I/O. Only the issuing gateway may consume it.
 */
interface PreparedLegacyDownload {
    val binding: PlaybackBinding
    /** Null only for a separately admitted first selection, never to adopt an unknown partial. */
    val requiredRepresentationId: String?
    val representation: AcquisitionRepresentation
}

/** Exact original saved receipt and transfer evidence only; not local validation or Ready. */
data class LegacyTransferResult(
    val binding: PlaybackBinding,
    val bytesWritten: Long,
    val representation: AcquisitionRepresentation,
) {
    override fun toString() = "LegacyTransferResult(binding=redacted,bytesWritten=$bytesWritten,representation=$representation)"
}

/**
 * Optional additive capability of the SAME existing KmpMediaGateway/source lifetime.
 * The physical executor requires it for known-schema2 downloads; missing support is a
 * truthful failure, never a split resolve/download fallback or a second transfer engine.
 * Execute the original complete-title/first VIDEO receipt directly with no search and no
 * invented MediaItem, AcquisitionRequest or typed lineage. Structural validation is not
 * attestation of historical source I/O; the canonical LibraryTransactionOwned caller must
 * first rejoin and fence the actual saved receipt/producer/job/attempt/path/representation.
 * Unknown provenance or partial bytes without their exact saved key are not admitted here.
 * Null key means a genuinely new selection after the caller safely reconciles old state.
 *
 * Reuse the SAME client/audio planning primitive as typed execution. A single preparation
 * plus transfer owns at most ONE total same-C/key expired-link renewal, not one per method.
 * Native capabilities are detached at preparation. A queued plan cannot gain a fresh budget.
 * No PlaybackResource is created for DOWNLOAD. Preserve cancellation and sanitized failures.
 */
interface LegacyPreparedDownloadGateway {
    suspend fun prepareDownload(
        binding: PlaybackBinding,
        capabilities: EngineCapabilities,
        requiredRepresentationId: String? = null,
    ): PreparedLegacyDownload

    /**
     * kmpYT owns range/resume journals at the already-fenced private staging destination.
     * Entry consumes the exact issuing-gateway capability even on cancellation, closed source,
     * invalid destination or failure. Concurrent/terminal reuse fails; a separately admitted
     * future attempt must prepare again. This does not release any caller-owned lease or ACK.
     */
    suspend fun download(
        prepared: PreparedLegacyDownload,
        destinationPath: String,
        onProgress: suspend (AcquisitionTransferProgress) -> Unit = {},
    ): LegacyTransferResult
}
