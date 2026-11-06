@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.platform.audio

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.OfflineAsset
import dev.veyra.model.OfflineAssetKind
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.AssetValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.AVFoundation.*
import platform.AudioToolbox.kAudioFormatLinearPCM
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.*
import platform.Foundation.*

/** Validates real local audio with AVFoundation and sampled PCM decoding, not filename/MIME alone. */
class IosAssetValidator(private val mediaRoot: String? = null) : AssetValidator {
    override suspend fun validate(asset: OfflineAsset): AssetValidation = withContext(Dispatchers.Default) {
        if (asset.kind != OfflineAssetKind.FILE) return@withContext invalid(FailureCode.UNSUPPORTED_TRANSPORT)
        val path = if (asset.uri.startsWith('/')) asset.uri
            else NSURL.URLWithString(asset.uri)?.takeIf { it.scheme == "file" }?.path
        if (path == null || !path.startsWith('/')) return@withContext invalid(FailureCode.CORRUPT_MEDIA)
        try {
            currentCoroutineContext().ensureActive()
            val url = NSURL.fileURLWithPath(path).URLByResolvingSymlinksInPath
                ?: return@withContext invalid(FailureCode.CORRUPT_MEDIA)
            val actualPath = url.path ?: return@withContext invalid(FailureCode.CORRUPT_MEDIA)
            val root = mediaRoot?.let { NSURL.fileURLWithPath(it).URLByResolvingSymlinksInPath?.path }
            if (root != null && !actualPath.startsWith(root.trimEnd('/') + "/")) {
                return@withContext invalid(FailureCode.PERMISSION_DENIED)
            }
            val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(actualPath, error = null)
                ?: return@withContext invalid(FailureCode.CORRUPT_MEDIA)
            val bytes = (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: 0
            if (bytes <= 0 || bytes != asset.sizeBytes || attributes[NSFileType] != NSFileTypeRegular) {
                return@withContext invalid(FailureCode.CORRUPT_MEDIA)
            }
            val native = AVURLAsset.URLAssetWithURL(url,
                options = mapOf(AVURLAssetPreferPreciseDurationAndTimingKey to true))
            val track = native.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack
                ?: return@withContext invalid(FailureCode.CORRUPT_MEDIA)
            val seconds = CMTimeGetSeconds(native.duration)
            if (!seconds.isFinite() || seconds <= 0 || seconds >= Long.MAX_VALUE / 1000.0) {
                return@withContext invalid(FailureCode.CORRUPT_MEDIA)
            }
            decodeSample(native, track, 0)
            if (seconds > 2) decodeSample(native, track, (seconds * 1000).toLong() - 1000)
            AssetValidation.Valid((seconds * 1000).toLong())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            invalid(FailureCode.CORRUPT_MEDIA)
        }
    }

    private suspend fun decodeSample(asset: AVAsset, track: AVAssetTrack, startMs: Long) {
        currentCoroutineContext().ensureActive()
        val reader = AVAssetReader(asset = asset, error = null)
        val output = AVAssetReaderTrackOutput(track = track,
            outputSettings = mapOf(AVFormatIDKey to kAudioFormatLinearPCM))
        output.alwaysCopiesSampleData = false
        check(reader.canAddOutput(output)) { "Unsupported local audio." }
        reader.addOutput(output)
        reader.timeRange = CMTimeRangeMake(CMTimeMake(startMs, 1000), CMTimeMake(1000, 1000))
        check(reader.startReading()) { "Cannot read local audio." }
        try {
            currentCoroutineContext().ensureActive()
            val buffer = output.copyNextSampleBuffer() ?: error("No decodable audio samples.")
            try { check(CMSampleBufferGetNumSamples(buffer) > 0) { "Empty decoded audio." } }
            finally { CFRelease(buffer) }
        } finally { reader.cancelReading() }
    }

    private fun invalid(code: FailureCode) = AssetValidation.Invalid(AppFailure(code, "local_audio_validation"))
}
