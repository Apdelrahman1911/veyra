package dev.veyra.desktop.audio

import dev.veyra.desktop.runtime.DesktopNativeRuntime
import dev.veyra.desktop.runtime.DesktopRuntimeConfig
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.OfflineAsset
import dev.veyra.model.OfflineAssetKind
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.AssetValidator
import dev.veyra.playback.EngineEvent
import dev.veyra.playback.ResolvedPlayable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files

/** Full silent decoding on a separate handle. Transfer completion remains the downloader's obligation. */
class DesktopAssetValidator(private val runtimeConfig: DesktopRuntimeConfig) : AssetValidator {
    override suspend fun validate(asset: OfflineAsset): AssetValidation = withContext(Dispatchers.IO) {
        if (asset.kind != OfflineAssetKind.FILE) return@withContext invalid("unsupported_offline_asset_kind")
        val path = try { DesktopAudioEngine.localPath(asset.uri) } catch (_: Exception) {
            return@withContext invalid("invalid_local_audio_path")
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) return@withContext invalid("local_audio_missing")
        if (asset.sizeBytes <= 0 || Files.size(path) != asset.sizeBytes) return@withContext invalid("offline_byte_count_mismatch")
        val expectedHash = asset.sha256
        if (expectedHash != null && DesktopNativeRuntime.sha256(path) != expectedHash.lowercase()) {
            return@withContext invalid("offline_hash_mismatch")
        }
        val engine = try { DesktopAudioEngine.createInternal(runtimeConfig.copy(silentTestOutput = true), validation = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@withContext AssetValidation.Invalid(AppFailure(FailureCode.INVALID_STATE, "native_validator_unavailable")) }
        try {
            withTimeoutOrNull(120_000) {
                coroutineScope {
                    val completion = CompletableDeferred<AssetValidation>()
                    var duration: Long? = null
                    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                        engine.events.collect { event ->
                            when (event) {
                                is EngineEvent.Ready -> { duration = event.durationMs; engine.play(1) }
                                is EngineEvent.Ended -> completion.complete(AssetValidation.Valid(duration))
                                is EngineEvent.Error -> completion.complete(AssetValidation.Invalid(event.failure))
                                else -> Unit
                            }
                        }
                    }
                    try {
                        engine.prepare(ResolvedPlayable(asset.mediaId, path.toString(), isLocal = true, mimeType = asset.mimeType, codecs = asset.codecs), 0, 1)
                        completion.await()
                    } finally { collector.cancel() }
                }
            } ?: invalid("native_media_validation_timeout")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            invalid("native_media_validation_failed")
        } finally {
            withContext(NonCancellable) { engine.close() }
        }
    }

    private fun invalid(detail: String) = AssetValidation.Invalid(AppFailure(FailureCode.CORRUPT_MEDIA, detail))
}
