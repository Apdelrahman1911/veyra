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
import dev.veyra.playback.LocalMediaValidationInput
import dev.veyra.playback.LocalMediaValidationReaderRelease
import dev.veyra.playback.LocalMediaValidationReaderUnacknowledged
import dev.veyra.playback.LocalMediaValidator
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Source-authored faults run before the named native call, after exact owner registration. */
internal enum class AndroidValidationNativeStep {
    EXTRACTOR_OWNED, EXTRACTOR_INPUT_SET, CODEC_OWNED, CODEC_STARTED,
    CODEC_STOP, CODEC_RELEASE, EXTRACTOR_RELEASE,
}
internal fun interface AndroidValidationNativeFault {
    fun before(input: LocalMediaValidationInput, step: AndroidValidationNativeStep, nativeOwner: Any)
}

/**
 * SAME file-only MediaExtractor/MediaCodec validator for catalogue and neutral recordings.
 * Transfer receipts/hash validation remain the shared download owner's obligation. A successful
 * release() is the platform API's lifecycle receipt, not independently measured descriptor proof.
 */
class AndroidAssetValidator internal constructor(
    private val mediaRoot: File?,
    private val nativeFault: AndroidValidationNativeFault,
) : AssetValidator, LocalMediaValidator {
    constructor(mediaRoot: File? = null) : this(mediaRoot, AndroidValidationNativeFault { _, _, _ -> })

    override suspend fun validate(asset: OfflineAsset): AssetValidation {
        if (asset.sizeBytes < 0 || asset.validatorGeneration < 0) return invalid(FailureCode.CORRUPT_MEDIA)
        return validateLocal(LocalMediaValidationInput(asset.uri, asset.kind, asset.sizeBytes,
            asset.mimeType, asset.codecs, asset.sha256, asset.representationId, asset.validatorGeneration))
    }

    override suspend fun validateLocal(input: LocalMediaValidationInput): AssetValidation = withContext(Dispatchers.IO) {
        val reader = ValidationReader(input, nativeFault)
        try {
            if (input.kind != OfflineAssetKind.FILE) return@withContext invalid(FailureCode.UNSUPPORTED_TRANSPORT)
            val uri = Uri.parse(input.uri)
            val path = when (uri.scheme) { null -> input.uri; "file" -> uri.path; else -> null }
                ?: return@withContext invalid(FailureCode.CORRUPT_MEDIA)
            val file = File(path)
            if (!file.isAbsolute || !file.isFile || file.length() <= 0 || file.length() != input.sizeBytes) {
                return@withContext invalid(FailureCode.CORRUPT_MEDIA)
            }
            val root = mediaRoot?.canonicalFile
            if (root != null && !file.canonicalPath.startsWith(root.path + File.separator)) {
                return@withContext invalid(FailureCode.PERMISSION_DENIED)
            }
            currentCoroutineContext().ensureActive()
            // ownExtractor/ownCodec register BEFORE a data source/configuration/fault can fail.
            val extractor = reader.ownExtractor(MediaExtractor())
            extractor.setDataSource(file.canonicalPath)
            nativeFault.before(input, AndroidValidationNativeStep.EXTRACTOR_INPUT_SET, extractor)
            val audioTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext invalid(FailureCode.CORRUPT_MEDIA)
            val format = extractor.getTrackFormat(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext invalid(FailureCode.CORRUPT_MEDIA)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs <= 0) return@withContext invalid(FailureCode.CORRUPT_MEDIA)
            extractor.selectTrack(audioTrack)
            // Parse AND decode bounded samples, without a second audible player/focus owner.
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
                decodeSample(reader, extractor, format, mime, 0)
                if (durationUs > 2_000_000) decodeSample(reader, extractor, format, mime, durationUs - 1_000_000)
            }
            currentCoroutineContext().ensureActive()
            AssetValidation.Valid(durationUs / 1000)
        } catch (unacknowledged: LocalMediaValidationReaderUnacknowledged) {
            throw unacknowledged
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            invalid(FailureCode.CORRUPT_MEDIA)
        } finally {
            // Synchronous, non-suspending cleanup runs even during cancellation. Deliberately
            // outside all Invalid/cancellation catches: an unacknowledged exact reader MUST
            // reach the caller so its path/gate/slot leases can be retained before classification.
            reader.closeOnce()
        }
    }

    private suspend fun decodeSample(reader: ValidationReader, extractor: MediaExtractor,
        format: MediaFormat, mime: String, timeUs: Long) {
        currentCoroutineContext().ensureActive()
        extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val owned = reader.ownCodec(MediaCodec.createDecoderByType(mime))
        val decoder = owned.codec
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            owned.started = true
            nativeFault.before(reader.input, AndroidValidationNativeStep.CODEC_STARTED, decoder)
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = checkNotNull(decoder.getInputBuffer(inputIndex))
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            check(size <= buffer.capacity())
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
                    check(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0)
                }
            }
            error("local_audio_validation_timeout")
        } finally {
            // Do not allocate a second codec while an earlier codec failed to release.
            if (!reader.releaseCodecOnce(owned)) reader.unacknowledged()
        }
    }

    private class CodecOwner(val codec: MediaCodec) {
        var started = false
        var releaseAttempted = false
        var released = false
    }

    private class ValidationReader(
        val input: LocalMediaValidationInput,
        private val fault: AndroidValidationNativeFault,
    ) : LocalMediaValidationReaderRelease {
        private var extractor: MediaExtractor? = null
        private var extractorReleaseAttempted = false
        private var extractorReleased = false
        private val codecs = mutableListOf<CodecOwner>()
        private var closeAttempted = false
        private val released = AtomicBoolean(false)

        fun ownExtractor(value: MediaExtractor): MediaExtractor {
            extractor = value
            retainedValidationReaders.add(this)
            fault.before(input, AndroidValidationNativeStep.EXTRACTOR_OWNED, value)
            return value
        }

        fun ownCodec(value: MediaCodec): CodecOwner {
            val owned = CodecOwner(value)
            codecs.add(owned)
            fault.before(input, AndroidValidationNativeStep.CODEC_OWNED, value)
            return owned
        }

        override fun isReleased(): Boolean = released.get() // Observation ONLY; no native call.

        fun releaseCodecOnce(owned: CodecOwner): Boolean {
            if (owned.releaseAttempted) return owned.released
            owned.releaseAttempted = true
            if (owned.started) {
                try {
                    fault.before(input, AndroidValidationNativeStep.CODEC_STOP, owned.codec)
                    owned.codec.stop()
                } catch (_: Throwable) { /* stop is not a release receipt; still attempt release */ }
            }
            try {
                fault.before(input, AndroidValidationNativeStep.CODEC_RELEASE, owned.codec)
                owned.codec.release()
                owned.released = true
            } catch (_: Throwable) { /* Keep exact codec; never retry an ambiguous release. */ }
            return owned.released
        }

        fun closeOnce() {
            if (closeAttempted) {
                if (extractor != null && !released.get()) unacknowledged()
                return
            }
            closeAttempted = true
            // Independent handles are all given their first cleanup attempt, even if one failed.
            codecs.asReversed().forEach { releaseCodecOnce(it) }
            val value = extractor ?: return // No native object was ever returned/registered.
            if (!extractorReleaseAttempted) {
                extractorReleaseAttempted = true
                try {
                    fault.before(input, AndroidValidationNativeStep.EXTRACTOR_RELEASE, value)
                    value.release()
                    extractorReleased = true
                } catch (_: Throwable) { /* Keep exact extractor and immutable input. */ }
            }
            if (!extractorReleased || codecs.any { !it.released }) unacknowledged()
            released.set(true) // Only normal returns from EVERY original release call qualify.
            retainedValidationReaders.remove(this)
        }

        fun unacknowledged(): Nothing = throw LocalMediaValidationReaderUnacknowledged(input, this)
    }

    private fun invalid(code: FailureCode) = AssetValidation.Invalid(AppFailure(code, "local_audio_validation"))

    private companion object {
        // Pins the actual native handles even if a noncompliant legacy caller drops the exception.
        // This is NOT deletion permission: the caller must additionally preserve all its leases.
        val retainedValidationReaders = Collections.newSetFromMap(ConcurrentHashMap<ValidationReader, Boolean>())
    }
}
