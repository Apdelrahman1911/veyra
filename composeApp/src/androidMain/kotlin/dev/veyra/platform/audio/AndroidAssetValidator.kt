package dev.veyra.platform.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.OfflineAsset
import dev.veyra.model.OfflineAssetKind
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.AssetValidator
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** File-only validation. Transfer receipts/hash validation belong to the shared download owner. */
class AndroidAssetValidator(private val mediaRoot: File? = null) : AssetValidator {
    override suspend fun validate(asset: OfflineAsset): AssetValidation = withContext(Dispatchers.IO) {
        if (asset.kind != OfflineAssetKind.FILE) return@withContext invalid(FailureCode.UNSUPPORTED_TRANSPORT)
        val uri = Uri.parse(asset.uri)
        val path = when (uri.scheme) { null -> asset.uri; "file" -> uri.path; else -> null }
            ?: return@withContext invalid(FailureCode.CORRUPT_MEDIA)
        val file = File(path)
        if (!file.isAbsolute || !file.isFile || file.length() <= 0 || file.length() != asset.sizeBytes) {
            return@withContext invalid(FailureCode.CORRUPT_MEDIA)
        }
        try {
            val root = mediaRoot?.canonicalFile
            if (root != null && !file.canonicalPath.startsWith(root.path + File.separator)) {
                return@withContext invalid(FailureCode.PERMISSION_DENIED)
            }
            currentCoroutineContext().ensureActive()
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.canonicalPath)
                val audioTrack = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return@withContext invalid(FailureCode.CORRUPT_MEDIA)
                val format = extractor.getTrackFormat(audioTrack)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext invalid(FailureCode.CORRUPT_MEDIA)
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                if (durationUs <= 0) return@withContext invalid(FailureCode.CORRUPT_MEDIA)
                extractor.selectTrack(audioTrack)
                // Parse AND decode sampled audio, without a second audible player/audio focus owner.
                if (mime == "audio/raw") {
                    val sample = java.nio.ByteBuffer.allocate(64 * 1024)
                    if (extractor.readSampleData(sample, 0) <= 0) return@withContext invalid(FailureCode.CORRUPT_MEDIA)
                    if (durationUs > 2_000_000) {
                        currentCoroutineContext().ensureActive()
                        extractor.seekTo(durationUs - 1_000_000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        sample.clear()
                        if (extractor.readSampleData(sample, 0) <= 0) return@withContext invalid(FailureCode.CORRUPT_MEDIA)
                    }
                } else {
                    decodeSample(extractor, format, mime, 0)
                    if (durationUs > 2_000_000) decodeSample(extractor, format, mime, durationUs - 1_000_000)
                }
                AssetValidation.Valid(durationUs / 1000)
            } finally { extractor.release() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            invalid(FailureCode.CORRUPT_MEDIA)
        }
    }

    private suspend fun decodeSample(extractor: MediaExtractor, format: MediaFormat, mime: String, timeUs: Long) {
        currentCoroutineContext().ensureActive()
        extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val decoder = MediaCodec.createDecoderByType(mime)
        var started = false
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            started = true
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = checkNotNull(decoder.getInputBuffer(inputIndex))
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            check(size <= input.capacity())
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                            extractor.advance()
                        }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, 10_000)
                if (output >= 0) {
                    val decoded = info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    decoder.releaseOutputBuffer(output, false)
                    if (decoded) return
                    check(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) { "No decoded audio." }
                }
            }
            error("Audio validation timed out.")
        } finally {
            if (started) runCatching { decoder.stop() }
            decoder.release()
        }
    }

    private fun invalid(code: FailureCode) = AssetValidation.Invalid(AppFailure(code, "local_audio_validation"))
}
