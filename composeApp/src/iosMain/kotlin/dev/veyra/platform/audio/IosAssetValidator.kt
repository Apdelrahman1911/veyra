@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.platform.audio

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
import kotlin.coroutines.CoroutineContext
import kotlinx.cinterop.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.AudioToolbox.*
import platform.CoreAudioTypes.*
import platform.CoreFoundation.*
import platform.Foundation.*

internal enum class IosValidationNativeStep { FILE_OWNED, FORMAT_READY, SEEK, READ, DISPOSE }
internal fun interface IosValidationNativeFault {
    fun before(input: LocalMediaValidationInput, step: IosValidationNativeStep, nativeOwner: ExtAudioFileRef)

    /** Test-only construction-error injection AFTER a nonnull native result was registered. */
    fun openStatus(input: LocalMediaValidationInput, nativeOwner: ExtAudioFileRef?, actualStatus: Int): Int = actualStatus

    /** A nonzero test failure skips Dispose; zero is rejected rather than fabricating a receipt. */
    fun disposeFailureStatus(input: LocalMediaValidationInput, nativeOwner: ExtAudioFileRef): Int? = null
}

/**
 * SAME identity-free primitive for legacy/typed local files. ExtAudioFile reads bounded PCM
 * samples without an audible session. ExtAudioFileDispose(noErr), documented to close the file,
 * is an API-level lifecycle receipt; it is NOT an independent physical/descriptor measurement.
 * AVAssetReader.cancelReading/status is deliberately NOT used as a destruction acknowledgement.
 */
class IosAssetValidator internal constructor(
    private val mediaRoot: String?,
    private val nativeFault: IosValidationNativeFault,
) : AssetValidator, LocalMediaValidator {
    constructor(mediaRoot: String? = null) : this(mediaRoot, IosValidationNativeFault { _, _, _ -> })

    override suspend fun validate(asset: OfflineAsset): AssetValidation {
        if (asset.sizeBytes < 0 || asset.validatorGeneration < 0) return invalid(FailureCode.CORRUPT_MEDIA)
        return validateLocal(LocalMediaValidationInput(asset.uri, asset.kind, asset.sizeBytes,
            asset.mimeType, asset.codecs, asset.sha256, asset.representationId, asset.validatorGeneration))
    }

    override suspend fun validateLocal(input: LocalMediaValidationInput): AssetValidation = withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        // This function and all C operations below are NON-suspending. ExtAudioFileRead requires
        // one thread for an instance; a Default dispatcher migration between samples is forbidden.
        validateOnCurrentThread(input, context)
    }

    private fun validateOnCurrentThread(input: LocalMediaValidationInput, context: CoroutineContext): AssetValidation {
        val reader = ValidationReader(input, nativeFault)
        try {
            context.ensureActive()
            if (input.kind != OfflineAssetKind.FILE) return invalid(FailureCode.UNSUPPORTED_TRANSPORT)
            // WebM/Ogg are not advertised by this AudioToolbox/AVPlayer adapter. No MIME-based
            // success or fallback decoder: other formats still have to open and actually decode.
            if (input.mimeType?.substringBefore(';')?.trim()?.lowercase() in unsupportedContainers) {
                return invalid(FailureCode.UNSUPPORTED_TRANSPORT)
            }
            val path = if (input.uri.startsWith('/')) input.uri
                else NSURL.URLWithString(input.uri)?.takeIf { it.scheme == "file" }?.path
            if (path == null || !path.startsWith('/')) return invalid(FailureCode.CORRUPT_MEDIA)
            val url = NSURL.fileURLWithPath(path).URLByResolvingSymlinksInPath
                ?: return invalid(FailureCode.CORRUPT_MEDIA)
            val actualPath = url.path ?: return invalid(FailureCode.CORRUPT_MEDIA)
            if (mediaRoot != null) {
                val root = NSURL.fileURLWithPath(mediaRoot).URLByResolvingSymlinksInPath?.path
                    ?: return invalid(FailureCode.PERMISSION_DENIED)
                if (!actualPath.startsWith(root.trimEnd('/') + "/")) return invalid(FailureCode.PERMISSION_DENIED)
            }
            val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(actualPath, error = null)
                ?: return invalid(FailureCode.CORRUPT_MEDIA)
            val bytes = (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: 0
            if (bytes <= 0 || bytes != input.sizeBytes || attributes[NSFileType] != NSFileTypeRegular) {
                return invalid(FailureCode.CORRUPT_MEDIA)
            }
            context.ensureActive()
            val pathBytes = actualPath.encodeToByteArray()
            val nativeUrl = pathBytes.usePinned { pinned ->
                CFURLCreateFromFileSystemRepresentation(kCFAllocatorDefault, pinned.addressOf(0).reinterpret(),
                    pathBytes.size.toLong(), false)
            } ?: return invalid(FailureCode.CORRUPT_MEDIA)
            reader.ownUrl(nativeUrl)
            return memScoped {
                val output = alloc<ExtAudioFileRefVar>()
                output.value = null
                val openStatus = ExtAudioFileOpenURL(nativeUrl, output.ptr)
                val nativeFile = output.value
                // A partially constructed NONNULL handle is owned BEFORE the status is checked,
                // before cancellation/faults, and before any format/property/decode operation.
                if (nativeFile != null) reader.ownFile(nativeFile)
                requireNativeStatus(nativeFault.openStatus(input, nativeFile, openStatus))
                val handle = nativeFile ?: return@memScoped invalid(FailureCode.CORRUPT_MEDIA)
                context.ensureActive()
                val fileFormat = alloc<AudioStreamBasicDescription>()
                val propertySize = alloc<UIntVar>()
                propertySize.value = sizeOf<AudioStreamBasicDescription>().toUInt()
                requireNativeStatus(ExtAudioFileGetProperty(handle, kExtAudioFileProperty_FileDataFormat,
                    propertySize.ptr, fileFormat.ptr))
                check(propertySize.value == sizeOf<AudioStreamBasicDescription>().toUInt())
                val fileFrames = alloc<LongVar>() // kExtAudioFileProperty_FileLengthFrames is SInt64.
                propertySize.value = sizeOf<LongVar>().toUInt()
                requireNativeStatus(ExtAudioFileGetProperty(handle, kExtAudioFileProperty_FileLengthFrames,
                    propertySize.ptr, fileFrames.ptr))
                check(propertySize.value == sizeOf<LongVar>().toUInt())
                val sampleRate = fileFormat.mSampleRate
                val channels = fileFormat.mChannelsPerFrame
                check(fileFrames.value > 0 && sampleRate.isFinite() && sampleRate >= 1 && sampleRate <= Int.MAX_VALUE)
                if (channels !in 1u..8u) return@memScoped invalid(FailureCode.UNSUPPORTED_CODEC)
                val durationMs = fileFrames.value.toDouble() * 1_000.0 / sampleRate
                check(durationMs.isFinite() && durationMs >= 1 && durationMs < Long.MAX_VALUE.toDouble())
                val frameBytes = channels * 2u
                val clientFormat = alloc<AudioStreamBasicDescription>().apply {
                    mSampleRate = sampleRate
                    mFormatID = kAudioFormatLinearPCM
                    mFormatFlags = kAudioFormatFlagIsSignedInteger or kAudioFormatFlagIsPacked
                    mBytesPerPacket = frameBytes
                    mFramesPerPacket = 1u
                    mBytesPerFrame = frameBytes
                    mChannelsPerFrame = channels
                    mBitsPerChannel = 16u
                    mReserved = 0u
                }
                requireNativeStatus(ExtAudioFileSetProperty(handle, kExtAudioFileProperty_ClientDataFormat,
                    sizeOf<AudioStreamBasicDescription>().toUInt(), clientFormat.ptr))
                nativeFault.before(input, IosValidationNativeStep.FORMAT_READY, handle)
                val sample = allocArray<ByteVar>(SAMPLE_BYTES)
                val buffers = alloc<AudioBufferList>()
                buffers.mNumberBuffers = 1u
                buffers.mBuffers[0].mNumberChannels = channels
                buffers.mBuffers[0].mData = sample
                val framesRequested = SAMPLE_BYTES.toUInt() / frameBytes
                val framesRead = alloc<UIntVar>()
                fun decodeAt(fileFrame: Long) {
                    context.ensureActive()
                    nativeFault.before(input, IosValidationNativeStep.SEEK, handle)
                    requireNativeStatus(ExtAudioFileSeek(handle, fileFrame))
                    framesRead.value = framesRequested
                    buffers.mBuffers[0].mDataByteSize = SAMPLE_BYTES.toUInt()
                    context.ensureActive()
                    nativeFault.before(input, IosValidationNativeStep.READ, handle)
                    requireNativeStatus(ExtAudioFileRead(handle, framesRead.ptr, buffers.ptr))
                    check(framesRead.value in 1u..framesRequested)
                    check(buffers.mBuffers[0].mDataByteSize in (framesRead.value * frameBytes)..SAMPLE_BYTES.toUInt())
                    context.ensureActive()
                }
                decodeAt(0)
                if (durationMs > 2_000.0) decodeAt((fileFrames.value - sampleRate.toLong()).coerceAtLeast(0))
                context.ensureActive()
                AssetValidation.Valid(durationMs.toLong())
            }
        } catch (unacknowledged: LocalMediaValidationReaderUnacknowledged) {
            throw unacknowledged
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: NativeValidationFailure) {
            return invalid(rejected.code)
        } catch (_: Exception) {
            return invalid(FailureCode.CORRUPT_MEDIA)
        } finally {
            // No suspension/cancellation point and OUTSIDE Invalid/cancellation classification.
            // The caller must retain its exact path/gate/slot lease before handling this exception.
            reader.closeOnce()
        }
    }

    private class ValidationReader(
        private val input: LocalMediaValidationInput,
        private val fault: IosValidationNativeFault,
    ) : LocalMediaValidationReaderRelease {
        private var url: CFURLRef? = null
        private var file: ExtAudioFileRef? = null
        private var closeAttempted = false
        private val receiptLock = NSLock()
        private var released = false

        fun ownUrl(value: CFURLRef) {
            url = value
            retainedLock.lock()
            try { retainedValidationReaders.add(this) } finally { retainedLock.unlock() }
        }

        fun ownFile(value: ExtAudioFileRef) {
            file = value
            fault.before(input, IosValidationNativeStep.FILE_OWNED, value)
        }

        override fun isReleased(): Boolean {
            receiptLock.lock()
            return try { released } finally { receiptLock.unlock() }
        }

        fun closeOnce() {
            if (closeAttempted) {
                if (url != null && !isReleased()) unacknowledged()
                return
            }
            closeAttempted = true
            val nativeUrl = url ?: return // No native URL/reader object was ever registered.
            val handle = file
            try {
                if (handle != null) {
                    fault.before(input, IosValidationNativeStep.DISPOSE, handle)
                    val forcedFailure = fault.disposeFailureStatus(input, handle)
                    check(forcedFailure == null || forcedFailure != 0)
                    val status = forcedFailure ?: ExtAudioFileDispose(handle)
                    if (status != 0) unacknowledged()
                }
                CFRelease(nativeUrl)
            } catch (_: Throwable) {
                // Exact handle+URL+input remain process-pinned. NEVER retry an ambiguous Dispose.
                unacknowledged()
            }
            // A null open result is not claimed as an ORIGINAL native reader release receipt.
            if (handle != null) {
                receiptLock.lock()
                try { released = true } finally { receiptLock.unlock() }
            }
            retainedLock.lock()
            try { retainedValidationReaders.remove(this) } finally { retainedLock.unlock() }
        }

        private fun unacknowledged(): Nothing = throw LocalMediaValidationReaderUnacknowledged(input, this)
    }

    private class NativeValidationFailure(val code: FailureCode) : Exception("local_audio_validation")

    private fun requireNativeStatus(status: Int) {
        if (status == 0) return
        val code = when (status) {
            kAudioFileUnsupportedFileTypeError, kAudioFileUnsupportedDataFormatError,
            kExtAudioFileError_InvalidDataFormat -> FailureCode.UNSUPPORTED_CODEC
            else -> FailureCode.CORRUPT_MEDIA
        }
        throw NativeValidationFailure(code)
    }

    private fun invalid(code: FailureCode) = AssetValidation.Invalid(AppFailure(code, "local_audio_validation"))

    private companion object {
        const val SAMPLE_BYTES = 64 * 1024
        val unsupportedContainers = setOf("audio/webm", "video/webm", "audio/ogg", "application/ogg", "video/ogg")
        val retainedLock = NSLock()
        val retainedValidationReaders = mutableSetOf<ValidationReader>()
    }
}
