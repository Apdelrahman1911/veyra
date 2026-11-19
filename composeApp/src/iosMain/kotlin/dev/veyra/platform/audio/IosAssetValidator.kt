@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.platform.audio

import dev.veyra.downloads.DownloadFiles
import dev.veyra.diagnostics.*
import dev.veyra.diagnostics.DiagnosticComponent.NATIVE_AUDIO
import dev.veyra.diagnostics.DiagnosticOperation.DECODER
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
import kotlin.time.TimeSource
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import platform.AudioToolbox.*
import platform.CoreAudioTypes.*
import platform.CoreFoundation.*
import platform.Foundation.*

internal enum class IosValidationNativeStep { FILE_OWNED, FORMAT_READY, SEEK, READ, DISPOSE }
private enum class IosValidationDiagnosticStep { PATH, OPEN, FORMAT, LENGTH, CLIENT_FORMAT, FIRST_SAMPLE, LAST_SAMPLE }
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
    private val diagnostics: Diagnostics = Diagnostics.None,
) : AssetValidator, LocalMediaValidator {
    constructor(mediaRoot: String? = null, diagnostics: Diagnostics = Diagnostics.None) :
        this(mediaRoot, IosValidationNativeFault { _, _, _ -> }, diagnostics)

    override suspend fun validate(asset: OfflineAsset): AssetValidation {
        if (asset.sizeBytes < 0 || asset.validatorGeneration < 0) return invalid(FailureCode.CORRUPT_MEDIA)
        return validateLocal(LocalMediaValidationInput(asset.uri, asset.kind, asset.sizeBytes,
            asset.mimeType, asset.codecs, asset.sha256, asset.representationId, asset.validatorGeneration))
    }

    override suspend fun validateLocal(input: LocalMediaValidationInput): AssetValidation =
        diagnostics.trace(NATIVE_AUDIO, DECODER, resultFailure = { (it as? AssetValidation.Invalid)?.failure?.code }) {
            val trace = currentCoroutineContext()[DiagnosticContext]
            runIosFileValidation(input) { context -> validateOnCurrentThread(input, context, trace) }
        }

    private fun validateOnCurrentThread(input: LocalMediaValidationInput, context: CoroutineContext, trace: DiagnosticContext?): AssetValidation {
        val reader = ValidationReader(input, nativeFault, diagnostics, trace)
        var diagnosticStep = IosValidationDiagnosticStep.PATH
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
            val ownedPath = mediaRoot?.let { DownloadFiles(it).ownedPath(path).toString() } ?: path
            val url = NSURL.fileURLWithPath(ownedPath).URLByResolvingSymlinksInPath
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
                diagnosticStep = IosValidationDiagnosticStep.OPEN
                val openStatus = traceIosDecoder(DiagnosticPhase.OPEN, diagnostics, trace) { ExtAudioFileOpenURL(nativeUrl, output.ptr) }
                val nativeFile = output.value
                // A partially constructed NONNULL handle is owned BEFORE the status is checked,
                // before cancellation/faults, and before any format/property/decode operation.
                if (nativeFile != null) reader.ownFile(nativeFile)
                requireNativeStatus(nativeFault.openStatus(input, nativeFile, openStatus))
                val handle = nativeFile ?: return@memScoped invalid(FailureCode.CORRUPT_MEDIA)
                context.ensureActive()
                diagnosticStep = IosValidationDiagnosticStep.FORMAT
                val fileFormat = alloc<AudioStreamBasicDescription>()
                val propertySize = alloc<UIntVar>()
                propertySize.value = sizeOf<AudioStreamBasicDescription>().toUInt()
                requireNativeStatus(traceIosDecoder(DiagnosticPhase.FORMAT, diagnostics, trace) {
                    ExtAudioFileGetProperty(handle, kExtAudioFileProperty_FileDataFormat, propertySize.ptr, fileFormat.ptr)
                })
                check(propertySize.value == sizeOf<AudioStreamBasicDescription>().toUInt())
                diagnosticStep = IosValidationDiagnosticStep.LENGTH
                val fileFrames = alloc<LongVar>() // kExtAudioFileProperty_FileLengthFrames is SInt64.
                propertySize.value = sizeOf<LongVar>().toUInt()
                requireNativeStatus(traceIosDecoder(DiagnosticPhase.LENGTH, diagnostics, trace) {
                    ExtAudioFileGetProperty(handle, kExtAudioFileProperty_FileLengthFrames, propertySize.ptr, fileFrames.ptr)
                })
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
                diagnosticStep = IosValidationDiagnosticStep.CLIENT_FORMAT
                requireNativeStatus(traceIosDecoder(DiagnosticPhase.CLIENT_FORMAT, diagnostics, trace) {
                    ExtAudioFileSetProperty(handle, kExtAudioFileProperty_ClientDataFormat,
                        sizeOf<AudioStreamBasicDescription>().toUInt(), clientFormat.ptr)
                })
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
                    val first = diagnosticStep == IosValidationDiagnosticStep.FIRST_SAMPLE
                    requireNativeStatus(traceIosDecoder(if (first) DiagnosticPhase.FIRST_SAMPLE_SEEK else DiagnosticPhase.LAST_SAMPLE_SEEK,
                        diagnostics, trace) { ExtAudioFileSeek(handle, fileFrame) })
                    framesRead.value = framesRequested
                    buffers.mBuffers[0].mDataByteSize = SAMPLE_BYTES.toUInt()
                    context.ensureActive()
                    nativeFault.before(input, IosValidationNativeStep.READ, handle)
                    requireNativeStatus(traceIosDecoder(if (first) DiagnosticPhase.FIRST_SAMPLE_READ else DiagnosticPhase.LAST_SAMPLE_READ,
                        diagnostics, trace) { ExtAudioFileRead(handle, framesRead.ptr, buffers.ptr) })
                    check(framesRead.value in 1u..framesRequested)
                    check(buffers.mBuffers[0].mDataByteSize in (framesRead.value * frameBytes)..SAMPLE_BYTES.toUInt())
                    context.ensureActive()
                }
                diagnosticStep = IosValidationDiagnosticStep.FIRST_SAMPLE
                decodeAt(0)
                if (durationMs > 2_000.0) {
                    diagnosticStep = IosValidationDiagnosticStep.LAST_SAMPLE
                    decodeAt((fileFrames.value - sampleRate.toLong()).coerceAtLeast(0))
                }
                context.ensureActive()
                AssetValidation.Valid(durationMs.toLong())
            }
        } catch (unacknowledged: LocalMediaValidationReaderUnacknowledged) {
            throw unacknowledged
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: NativeValidationFailure) {
            debugValidationRejection(diagnosticStep, rejected.status, "NATIVE")
            return invalid(rejected.code)
        } catch (error: Exception) {
            debugValidationRejection(diagnosticStep, null, when (error) {
                is IllegalArgumentException -> "ARGUMENT_GUARD"
                is IllegalStateException -> "STATE_GUARD"
                else -> "OTHER"
            })
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
        private val diagnostics: Diagnostics,
        private val trace: DiagnosticContext?,
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
                    val status = forcedFailure ?: traceIosDecoder(DiagnosticPhase.DISPOSE, diagnostics, trace) { ExtAudioFileDispose(handle) }
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

    private class NativeValidationFailure(val code: FailureCode, val status: Int) : Exception("local_audio_validation")

    private fun requireNativeStatus(status: Int) {
        if (status == 0) return
        val code = when (status) {
            kAudioFileUnsupportedFileTypeError, kAudioFileUnsupportedDataFormatError,
            kExtAudioFileError_InvalidDataFormat -> FailureCode.UNSUPPORTED_CODEC
            else -> FailureCode.CORRUPT_MEDIA
        }
        throw NativeValidationFailure(code, status)
    }

    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    private fun debugValidationRejection(step: IosValidationDiagnosticStep, status: Int?, kind: String) {
        if (kotlin.native.Platform.isDebugBinary)
            println("VEYRA_LOCAL_VALIDATION step=$step status=$status kind=$kind")
    }

    private fun invalid(code: FailureCode) = AssetValidation.Invalid(AppFailure(code, "local_audio_validation"))

    private companion object {
        const val SAMPLE_BYTES = 64 * 1024
        val unsupportedContainers = setOf("audio/webm", "video/webm", "audio/ogg", "application/ogg", "video/ogg")
        val retainedLock = NSLock()
        val retainedValidationReaders = mutableSetOf<ValidationReader>()
    }
}

private val iosValidationWorkers = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(2))

// ExtAudioFile operations and disposal stay in one nonsuspending worker. A caller may stop
// waiting, but only completion of that worker can acknowledge release of its exact file.
internal suspend fun runIosFileValidation(
    input: LocalMediaValidationInput,
    timeoutMs: Long = 45_000,
    validate: (CoroutineContext) -> AssetValidation,
): AssetValidation {
    currentCoroutineContext().ensureActive()
    val unacknowledged = MutableStateFlow<LocalMediaValidationReaderUnacknowledged?>(null)
    val worker = iosValidationWorkers.async {
        try {
            validate(currentCoroutineContext())
        } catch (failed: LocalMediaValidationReaderUnacknowledged) {
            unacknowledged.value = failed
            throw failed
        }
    }
    fun requestCancellation() {
        worker.cancel()
        if (!worker.isCompleted) {
            throw LocalMediaValidationReaderUnacknowledged(input, LocalMediaValidationReaderRelease {
                worker.isCompleted && (unacknowledged.value?.reader?.isReleased() ?: true)
            })
        }
        unacknowledged.value?.let { throw it }
    }
    val result = try {
        withTimeoutOrNull(timeoutMs) { worker.await() }
    } catch (cancelled: CancellationException) {
        requestCancellation()
        throw cancelled
    }
    if (result != null) return result
    requestCancellation()
    return AssetValidation.Invalid(AppFailure(FailureCode.RECOVERY_REQUIRED, "download_validation_timed_out"))
}

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private inline fun traceIosDecoder(stage: DiagnosticPhase, diagnostics: Diagnostics, trace: DiagnosticContext?, call: () -> Int): Int {
    val debug = kotlin.native.Platform.isDebugBinary
    if (!debug && !diagnostics.enabled) return call()
    val operation = diagnostics.begin(NATIVE_AUDIO, DECODER, trace, stage)
    val start = if (debug) TimeSource.Monotonic.markNow() else null
    if (debug) println("VEYRA_IOS_DECODER ts_ms=${(NSDate().timeIntervalSince1970 * 1000).toLong()} stage=$stage outcome=START")
    val status = call()
    diagnostics.end(NATIVE_AUDIO, DECODER, operation,
        if (status == 0) DiagnosticOutcome.SUCCEEDED else DiagnosticOutcome.FAILED, phase = stage, value = status.toLong())
    if (start != null) println("VEYRA_IOS_DECODER ts_ms=${(NSDate().timeIntervalSince1970 * 1000).toLong()} stage=$stage " +
        "outcome=${if (status == 0) "SUCCESS" else "FAILED"} duration_ms=${start.elapsedNow().inWholeMilliseconds} os_status=$status")
    return status
}
