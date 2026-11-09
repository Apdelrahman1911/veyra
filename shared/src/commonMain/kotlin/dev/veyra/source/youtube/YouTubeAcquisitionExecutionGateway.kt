package dev.veyra.source.youtube

import dev.veyra.model.*
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.execution.*

/**
 * Typed execution of an already accepted Single. The supplied source is the application-owned
 * legacy source too: both boundaries share its client, audio primitive and close lifetime.
 * No alternate client, media identity coercion, title query or native transfer is introduced.
 */
class YouTubeAcquisitionExecutionGateway(private val source: YouTubeMediaSource) : AcquisitionExecutionGateway {
    override suspend fun preparePlayback(
        context: AcquisitionExecutionContext,
        capabilities: EngineCapabilities,
        requiredRepresentationId: String?,
        inspection: AcquisitionInspection?,
    ): AcquisitionPlaybackSource = guarded {
        val input = checkedInput(context, inspection)
        source.audioExecution.resolve(input, capabilities, ResolvePurpose.PLAYBACK, requiredRepresentationId) { resolved ->
            AcquisitionPlaybackSource(
                context = context,
                uri = resolved.uri,
                transport = resolved.transport,
                mimeType = resolved.mimeType,
                codecs = resolved.codecs,
                representationId = resolved.key,
                expiresAtEpochMs = resolved.expiresAtEpochMs,
                durationMs = resolved.durationMs,
                seekableHint = true,
                resource = resolved.resource,
            )
        }
    }

    override suspend fun prepareDownload(
        context: AcquisitionExecutionContext,
        capabilities: EngineCapabilities,
        requiredRepresentationId: String?,
        inspection: AcquisitionInspection?,
    ): PreparedAcquisitionDownload = guarded(download = true) {
        val input = checkedInput(context, inspection)
        val prepared = source.audioExecution.prepareDownload(input, capabilities, requiredRepresentationId)
        Prepared(this, context, requiredRepresentationId, prepared)
    }

    override suspend fun download(
        prepared: PreparedAcquisitionDownload,
        destinationPath: String,
        onProgress: suspend (AcquisitionTransferProgress) -> Unit,
    ): AcquisitionTransferResult = guarded(download = true) {
        // Do not read caller-defined public getters before checking the private capability.
        // Even another gateway around the SAME source is a different issuing engine.
        val owned = prepared as? Prepared ?: invalidPrepared()
        if (owned.owner !== this) invalidPrepared()
        // consume() is the primitive's first suspension and is noncancellable. Invalid paths,
        // transfer failure and cancellation all terminate this attempt's one-shot capability.
        val result = source.audioExecution.download(owned.delegate, destinationPath) { progress ->
            onProgress(AcquisitionTransferProgress(progress.bytesDownloaded, progress.totalBytes, progress.speedBytesPerSecond))
        }
        val representation = AcquisitionRepresentation(result.representationId, result.mimeType, result.codecs, result.extension)
        if (representation != owned.representation) {
            youtubeFailure(FailureCode.CORRUPT_MEDIA, "YOUTUBE_TRANSFER_IDENTITY_CHANGED")
        }
        AcquisitionTransferResult(owned.context, result.bytesWritten, representation)
    }

    private fun checkedInput(context: AcquisitionExecutionContext, inspection: AcquisitionInspection?): YouTubeAudioExecution.Input {
        val restored = checkedContext(context)
        val supplied = inspection?.let { value ->
            val owned = value as? YouTubeAcquisitionInspection ?: invalidInspection()
            if (owned.owner !== source.client || owned.request != context.request ||
                owned.selection.requestId != restored.requestId || owned.selection.receipt != restored.receipt ||
                !owned.selection.recording.sameRawIdentity(restored.recording)) invalidInspection()
            owned
        }
        // Restore intentionally has no acquired thumbnail/subtitle/duration. They are not
        // identity authority and may not make a valid inspected Single look like another C.
        val selection = supplied?.selection ?: restored
        return YouTubeAudioExecution.Input(context.recording.canonicalId, initialReport = supplied?.report, inspect = {
            val inspected = source.acquisitionGateway.inspect(context.request, selection) as? YouTubeAcquisitionInspection
                ?: invalidInspection()
            if (inspected.owner !== source.client || inspected.request != context.request || inspected.selection != selection) {
                invalidInspection()
            }
            inspected.report
        })
    }

    /** Structural validation, never an attestation that caller-supplied history actually occurred. */
    private fun checkedContext(context: AcquisitionExecutionContext): SourceAcquisitionResult.Single {
        val request = context.request
        val receipt = context.receipt
        val binding = context.binding
        val recording = context.recording
        if (binding.origin != ExecutionBindingOrigin.TYPED || binding.firstReceiptId != receipt.id ||
            binding.subjectId != context.subject.id || binding.recordingId != recording.id ||
            recording.provider != SourceProvider.YOUTUBE || request.completeTitle != request.metadata.completeTitle ||
            receipt.observation.requestId != request.id || receipt.observation.queryTitle != request.completeTitle) invalidContext()
        when (val subject = context.subject) {
            is ExecutableSubject.CatalogMedia -> if (request.kind != AcquisitionKind.MEDIA ||
                (request.metadata as? MediaCapture)?.media?.id != subject.mediaId || subject.id.value != subject.mediaId) invalidContext()
            is ExecutableSubject.SourceRecordingSubject -> if (request.kind == AcquisitionKind.MEDIA ||
                subject.recordingId != recording.id) invalidContext()
        }
        // PUBLIC pure restore validates raw locator, ordinal, kind, complete title, request
        // fields, canonical C and saved B/ref/display. No watch/search/listing I/O occurs here.
        val restored = source.acquisitionGateway.restore(request, receipt.observation) as? SourceAcquisitionResult.Single
            ?: invalidContext()
        if (restored.requestId != request.id || restored.receipt != receipt.observation ||
            restored.recording.kind != AcquisitionEntryKind.VIDEO || restored.recording.id != recording.canonicalId ||
            restored.recording.publicUrl != recording.canonicalUrl || restored.receipt.canonicalUrl != recording.canonicalUrl) invalidContext()
        return restored
    }

    private class Prepared(
        val owner: YouTubeAcquisitionExecutionGateway,
        override val context: AcquisitionExecutionContext,
        override val requiredRepresentationId: String?,
        val delegate: YouTubeAudioExecution.PreparedDownload,
    ) : PreparedAcquisitionDownload {
        override val representation = delegate.representation.let {
            AcquisitionRepresentation(it.key, it.mimeType, it.codecs, it.extension)
        }
        override fun toString() = "YouTubePreparedAcquisitionDownload(context=redacted,plan=redacted)"
    }

    private suspend fun <T> guarded(download: Boolean = false, block: suspend () -> T): T =
        try { block() } catch (failure: Exception) { failure.asYouTubeFailure(download) }

    private fun SourceDisplayItem.sameRawIdentity(other: SourceDisplayItem) =
        kind == other.kind && id == other.id && title == other.title && publicUrl == other.publicUrl

    private companion object {
        fun invalidContext(): Nothing = youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_ACQUISITION_EXECUTION_MISMATCH")
        fun invalidInspection(): Nothing = youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_ACQUISITION_INSPECTION_MISMATCH")
        fun invalidPrepared(): Nothing = youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_PREPARED_DOWNLOAD_MISMATCH")
    }
}
