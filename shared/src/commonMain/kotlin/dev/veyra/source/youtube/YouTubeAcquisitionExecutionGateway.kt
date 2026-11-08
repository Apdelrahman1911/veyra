package dev.veyra.source.youtube

import dev.kmpyt.extraction.ExtractionReport
import dev.veyra.model.*
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.execution.*

/**
 * Typed execution of an already accepted Single over the SAME application-owned source/client
 * and audio plan/transfer primitive as the legacy boundary. No alternate client, media identity
 * coercion, title query or native transfer exists. The delivered pin has no typed upstream
 * acquisition API, so only the structural request/receipt/binding join is validated here.
 */
class YouTubeAcquisitionExecutionGateway internal constructor(
    private val source: YouTubeMediaSource,
) : AcquisitionExecutionGateway {
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
        Prepared(this, context, requiredRepresentationId, source.audioExecution.prepareDownload(input, capabilities, requiredRepresentationId))
    }

    override suspend fun download(
        prepared: PreparedAcquisitionDownload,
        destinationPath: String,
        onProgress: suspend (AcquisitionTransferProgress) -> Unit,
    ): AcquisitionTransferResult = guarded(download = true) {
        // Reject a foreign/forged capability BEFORE reading caller-defined getters or doing I/O.
        val owned = prepared as? Prepared ?: invalidPrepared()
        if (owned.owner !== this) invalidPrepared()
        val result = source.audioExecution.download(owned.delegate, destinationPath) { progress ->
            onProgress(AcquisitionTransferProgress(progress.bytesDownloaded, progress.totalBytes, progress.speedBytesPerSecond))
        }
        val representation = AcquisitionRepresentation(result.representationId, result.mimeType, result.codecs, result.extension)
        if (representation != owned.representation) {
            youtubeFailure(FailureCode.CORRUPT_MEDIA, "YOUTUBE_TRANSFER_IDENTITY_CHANGED")
        }
        AcquisitionTransferResult(owned.context, result.bytesWritten, representation)
    }

    private fun checkedInput(context: AcquisitionExecutionContext,
        inspection: AcquisitionInspection?): YouTubeAudioExecution.Input {
        checkedContext(context)
        val supplied = inspection?.let { value ->
            val owned = value as? YouTubeAcquisitionInspection ?: invalidInspection()
            if (owned.owner !== source || owned.request != context.request || owned.selection.requestId != context.request.id ||
                owned.selection.receipt != context.receipt.observation || !owned.selection.recording.matches(context.recording)) invalidInspection()
            owned
        }
        val videoId = context.recording.canonicalId
        if (!VIDEO_ID.matches(videoId)) invalidContext()
        return YouTubeAudioExecution.Input(videoId,
            inspect = { source.inspectSelected(videoId) }, initialReport = supplied?.report)
    }

    /** Structural validation of the detached context; not historical source-I/O attestation. */
    private fun checkedContext(context: AcquisitionExecutionContext) {
        val request = context.request
        val receipt = context.receipt.observation
        val binding = context.binding
        val recording = context.recording
        if (binding.origin != ExecutionBindingOrigin.TYPED || binding.firstReceiptId != context.receipt.id ||
            binding.subjectId != context.subject.id || binding.recordingId != recording.id ||
            recording.provider != SourceProvider.YOUTUBE || request.completeTitle != request.metadata.completeTitle ||
            receipt.requestId != request.id || receipt.queryTitle != request.completeTitle ||
            receipt.entry.ordinal != 0 || receipt.entry.issue != null ||
            receipt.entry.kind != AcquisitionEntryKind.VIDEO ||
            receipt.entry.id != recording.canonicalId ||
            receipt.canonicalUrl != recording.canonicalUrl ||
            receipt.entry.publicUrl != recording.canonicalUrl) invalidContext()
        when (val subject = context.subject) {
            is ExecutableSubject.CatalogMedia -> if (request.kind != AcquisitionKind.MEDIA ||
                (request.metadata as? MediaCapture)?.media?.id != subject.mediaId || subject.id.value != subject.mediaId) invalidContext()
            is ExecutableSubject.SourceRecordingSubject -> if (request.kind == AcquisitionKind.MEDIA ||
                subject.recordingId != recording.id) invalidContext()
        }
    }

    private fun SourceDisplayItem.matches(other: SourceRecording) = kind == AcquisitionEntryKind.VIDEO &&
        id == other.canonicalId && publicUrl == other.canonicalUrl

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
        try { block() } catch (failure: VeyraException) { throw failure } catch (failure: Exception) {
            throw VeyraException(failure.asYouTubeFailure(download))
        }

    private companion object {
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        fun invalidContext(): Nothing = youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_ACQUISITION_EXECUTION_MISMATCH")
        fun invalidInspection(): Nothing = youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_ACQUISITION_INSPECTION_MISMATCH")
        fun invalidPrepared(): Nothing = youtubeFailure(FailureCode.INVALID_STATE, "YOUTUBE_PREPARED_DOWNLOAD_MISMATCH")
    }
}
