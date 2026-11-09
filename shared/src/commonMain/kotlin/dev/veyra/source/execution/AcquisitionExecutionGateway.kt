package dev.veyra.source.execution

import dev.veyra.model.*
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.PlaybackResource

/**
 * Detached exact join from the canonical Room owner. This public value is not authority by
 * itself: the gateway revalidates the complete request/raw-zero receipt and same-C linkage;
 * the caller separately fences its occurrence/job/attempt against current durable ownership.
 * Aggregate/member recordings remain neutral, never manufactured MediaItem/PlaybackBinding.
 * Explicit schema2 unknown provenance continues on its labeled legacy path, not this variant.
 */
data class AcquisitionExecutionContext(
    val request: AcquisitionRequest,
    val receipt: FirstResultReceipt.Observed,
    val binding: ExecutionBinding,
    val subject: ExecutableSubject,
    val recording: SourceRecording,
) {
    init {
        require(binding.origin == ExecutionBindingOrigin.TYPED)
        require(binding.firstReceiptId == receipt.id && binding.subjectId == subject.id && binding.recordingId == recording.id)
        require(receipt.observation.requestId == request.id && receipt.observation.queryTitle == request.completeTitle)
        require(receipt.observation.entry.ordinal == 0 && receipt.observation.entry.kind == AcquisitionEntryKind.VIDEO)
        require(receipt.observation.entry.issue == null && receipt.observation.entry.id == recording.canonicalId)
        require(receipt.observation.canonicalUrl == recording.canonicalUrl)
        when (subject) {
            is ExecutableSubject.CatalogMedia -> require((request.metadata as? MediaCapture)?.media?.id == subject.mediaId)
            is ExecutableSubject.SourceRecordingSubject -> {
                require(request.kind != AcquisitionKind.MEDIA && subject.recordingId == recording.id)
            }
        }
    }
    override fun toString() = "AcquisitionExecutionContext(identity=redacted,typed=true)"
}

/** Stable public selection/file metadata only: never signed URLs, headers, tokens or a plan. */
data class AcquisitionRepresentation(
    val representationId: String,
    val mimeType: String,
    val codecs: String,
    val extension: String,
)

/**
 * Ephemeral native source with an explicit neutral/catalogue subject, not a mediaId stand-in.
 * Never serialize/log URI, headers or the resource. A source stays remote even when its owned
 * central execution bridge uses loopback. The native target/occurrence owns presentation and
 * controls mode separately; no source inspection grants RSS progress/follow semantics.
 * Resource ownership transfers on native prepare/preload invocation, including failure; the
 * caller closes an unhanded resource on cancellation/stale-occurrence rejection.
 */
data class AcquisitionPlaybackSource(
    val context: AcquisitionExecutionContext,
    val uri: String,
    val transport: StreamTransport,
    val mimeType: String?,
    val codecs: String?,
    val representationId: String,
    val headers: Map<String, String> = emptyMap(),
    val expiresAtEpochMs: Long? = null,
    val durationMs: Long? = null,
    val seekableHint: Boolean? = null,
    val isLocal: Boolean = false,
    val materializationAssetId: MaterializationAssetId? = null,
    val resource: PlaybackResource? = null,
) {
    init { require(isLocal == (materializationAssetId != null)) }
    fun ownedLoopbackUriOrNull(): String? = resource?.localEndpoint?.uri?.takeIf { !isLocal && it == uri }
    override fun toString() = "AcquisitionPlaybackSource(transport=$transport,isLocal=$isLocal,identity=redacted,resource=redacted)"
}

/**
 * Opaque SAME-client prepared download. Holds a private accepted report/plan, exact selection
 * key and refresh budget; it opens no PlaybackResource or native player. Never serialize,
 * reconstruct or log it. Public fields are observations, not permission to bypass owner CAS.
 * A different client's/issuing engine's or caller-forged implementation must be rejected before source I/O.
 */
interface PreparedAcquisitionDownload {
    val context: AcquisitionExecutionContext
    /** Null only when this preparation chose its first compatible key; otherwise exactly equals the chosen key. */
    val requiredRepresentationId: String?
    val representation: AcquisitionRepresentation
}

data class AcquisitionTransferProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long? = null,
    val speedBytesPerSecond: Long = 0,
)

/** Transfer evidence only: exact file/hash/native validation and durable finalization follow. */
data class AcquisitionTransferResult(
    val context: AcquisitionExecutionContext,
    val bytesWritten: Long,
    val representation: AcquisitionRepresentation,
)

/**
 * Typed entry into the EXISTING kmpYT plan/transfer implementation, not another transfer engine.
 * Restore this exact accepted Single and inspect the same C directly; never search again.
 * An optional inspection is consumed only if SAME client, full request, exact raw receipt and
 * Single's authoritative kind/id/title/publicUrl match. Pure restore need not reproduce optional
 * thumbnail/subtitle/duration display observations; those fields are not historical attestation.
 * Reuse one internal video/report selection primitive with the legacy gateway; only the legacy
 * boundary may use its original MediaItem. No temporary fake MUSIC/EPISODE is allowed here.
 *
 * Respect current native capabilities and required immutable representation key. Across one
 * preparation+transfer, at most ONE total expired-link reinspection is allowed, with unchanged
 * C/key and renewed anonymous authorization. Preparing from an already refreshed selection
 * consumes that budget. No fallback to another first result/representation/source is permitted.
 * Offline/activation/job/lease admission belongs to the caller before invoking this source port.
 * Failures are sanitized VeyraException; cancellation propagates after owned cleanup.
 */
interface AcquisitionExecutionGateway {
    suspend fun preparePlayback(
        context: AcquisitionExecutionContext,
        capabilities: EngineCapabilities,
        requiredRepresentationId: String? = null,
        inspection: AcquisitionInspection? = null,
    ): AcquisitionPlaybackSource

    suspend fun prepareDownload(
        context: AcquisitionExecutionContext,
        capabilities: EngineCapabilities,
        requiredRepresentationId: String? = null,
        inspection: AcquisitionInspection? = null,
    ): PreparedAcquisitionDownload

    /**
     * kmpYT alone owns transfer/ranges/resume journals at this already-fenced private destination.
     * Consume the prepared same-client selection, not a new independent inspection budget.
     * The same prepared object cannot be transferred concurrently or silently reused after a
     * terminal return/cancellation. The executor owns a separately admitted future attempt.
     */
    suspend fun download(
        prepared: PreparedAcquisitionDownload,
        destinationPath: String,
        onProgress: suspend (AcquisitionTransferProgress) -> Unit = {},
    ): AcquisitionTransferResult
}
