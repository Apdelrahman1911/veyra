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
import dev.veyra.playback.LocalMediaValidationInput
import dev.veyra.playback.LocalMediaValidationReaderRelease
import dev.veyra.playback.LocalMediaValidationReaderUnacknowledged
import dev.veyra.playback.LocalMediaValidator
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Full silent decoding on a separate native handle. Transfer completion remains the downloader's
 * obligation. The SAME primitive validates legacy catalogue assets and neutral typed recordings.
 *
 * [ValidationReader] retains the ORIGINAL decoder if [MpvAudioDeck.close] does not return
 * successfully. A successful close is this platform adapter's positive destruction receipt; it
 * is not independent descriptor measurement. Callers must still retain their path/gate/slot
 * leases behind [LocalMediaValidationReaderUnacknowledged].
 */
class DesktopAssetValidator internal constructor(
    /** Registers the SAME decoder before a later prepare/decoder failure can lose ownership. */
    private val openDecoder: suspend ((MpvAudioDeck) -> Unit) -> MpvAudioDeck,
) : AssetValidator, LocalMediaValidator {
    constructor(runtimeConfig: DesktopRuntimeConfig) : this({ owned ->
        MpvAudioDeck.createInternal(runtimeConfig.copy(silentTestOutput = true), validation = true).also(owned)
    })

    override suspend fun validate(asset: OfflineAsset): AssetValidation {
        if (asset.sizeBytes <= 0) return invalid("offline_byte_count_mismatch")
        if (asset.validatorGeneration < 0) return invalid("offline_validation_generation_mismatch")
        return validateLocal(LocalMediaValidationInput(asset.uri, asset.kind, asset.sizeBytes,
            asset.mimeType, asset.codecs, asset.sha256, asset.representationId, asset.validatorGeneration))
    }

    override suspend fun validateLocal(input: LocalMediaValidationInput): AssetValidation = withContext(Dispatchers.IO) {
        if (input.kind != OfflineAssetKind.FILE) return@withContext invalid("unsupported_offline_asset_kind")
        val path = try { DesktopAudioEngine.localPath(input.uri) } catch (_: Exception) {
            return@withContext invalid("invalid_local_audio_path")
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) return@withContext invalid("local_audio_missing")
        if (input.sizeBytes <= 0 || Files.size(path) != input.sizeBytes) return@withContext invalid("offline_byte_count_mismatch")
        val expectedHash = input.sha256
        if (expectedHash != null && DesktopNativeRuntime.sha256(path) != expectedHash.lowercase()) {
            return@withContext invalid("offline_hash_mismatch")
        }
        val reader = ValidationReader(input)
        try {
            val engine = try {
                openDecoder(reader::own)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                return@withContext AssetValidation.Invalid(AppFailure(FailureCode.INVALID_STATE, "native_validator_unavailable"))
            }
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
                        engine.prepare(ResolvedPlayable("local-validation", path.toString(), isLocal = true,
                            mimeType = input.mimeType, codecs = input.codecs), 0, 1)
                        completion.await()
                    } finally { collector.cancel() }
                }
            } ?: invalid("native_media_validation_timeout")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            invalid("native_media_validation_failed")
        } finally {
            // Outside the Invalid/cancellation classification: an unacknowledged native destroy
            // must reach the caller as the typed retained-reader failure, never a generic error.
            withContext(NonCancellable) { reader.closeOnce() }
        }
    }

    private fun invalid(detail: String) = AssetValidation.Invalid(AppFailure(FailureCode.CORRUPT_MEDIA, detail))

    private class ValidationReader(private val input: LocalMediaValidationInput) : LocalMediaValidationReaderRelease {
        private var engine: MpvAudioDeck? = null
        private var closeAttempted = false
        @Volatile private var released = false

        fun own(value: MpvAudioDeck) {
            check(engine == null && !closeAttempted)
            engine = value
        }

        override fun isReleased(): Boolean = released // Observation only; never retries destroy.

        suspend fun closeOnce() {
            val value = engine ?: return
            if (closeAttempted) {
                if (!released) throw LocalMediaValidationReaderUnacknowledged(input, this)
                return
            }
            closeAttempted = true
            // Keep the actual native handle strongly owned even if a noncompliant caller loses
            // the typed exception. This pin alone is NOT file-release permission.
            retainedValidationReaders.add(this)
            try { value.close() }
            catch (_: Throwable) { throw LocalMediaValidationReaderUnacknowledged(input, this) }
            released = true // MpvAudioDeck.close returns only after its owner thread terminates.
            engine = null
            retainedValidationReaders.remove(this)
        }
    }

    private companion object {
        val retainedValidationReaders = ConcurrentHashMap.newKeySet<ValidationReader>()
    }
}
