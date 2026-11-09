package dev.veyra.app

import dev.veyra.data.LibraryStore
import dev.veyra.downloads.DownloadExecutionGate
import dev.veyra.downloads.DownloadNetworkState
import dev.veyra.downloads.ProcessLifetimeDownloadGate
import dev.veyra.downloads.alwaysConnectedDownloadNetwork
import dev.veyra.model.LanguagePreference
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import dev.veyra.playback.AssetValidator
import dev.veyra.playback.AudioEngine
import dev.veyra.playback.LocalMediaValidator
import dev.veyra.source.youtube.YouTubeMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class MediaRuntimePhase { READY, REQUIRED, VERIFYING, DOWNLOADING, INSTALLING, ERROR }
enum class MediaRuntimeComponent { NODE, CHROMIUM, FFMPEG }
data class MediaRuntimeStatus(
    val phase: MediaRuntimePhase = MediaRuntimePhase.READY,
    val component: MediaRuntimeComponent? = null,
    val completedBytes: Long = 0,
    val totalBytes: Long? = null,
    val failure: AppFailure? = null,
)
private val readyRuntime: StateFlow<MediaRuntimeStatus> = MutableStateFlow(MediaRuntimeStatus())

/** One host-owned set per application lifetime, never created by an individual screen. */
interface PlatformServices {
    val dataDirectory: String
    val store: LibraryStore
    val engine: AudioEngine
    val validator: AssetValidator
    /**
     * Identity-free local decoder/header/duration primitive required by schema3 physical
     * downloads. Production platform validators implement it over the same native primitive;
     * a platform that does not is a truthful unavailable capability, never a fabricated success.
     */
    val localValidator: LocalMediaValidator get() = validator as? LocalMediaValidator
        ?: LocalMediaValidator { throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE, "local_validator_unavailable")) }
    val youtube: YouTubeMediaSource
    val systemLanguage: LanguagePreference
    val systemDarkTheme: Boolean get() = false
    val downloadExecution: DownloadExecutionGate get() = ProcessLifetimeDownloadGate
    val downloadNetworkState: StateFlow<DownloadNetworkState> get() = alwaysConnectedDownloadNetwork
    /**
     * ACK durable scheduling coverage before autonomous execution. A native host may orchestrate
     * kmpYT with OS workers, never implement another acquisition engine. Throw a typed failure if
     * coverage is unavailable; the application must keep its local library/playback usable.
     * Process-lifetime hosts require no external scheduler, hence the default no-op.
     */
    suspend fun ensureDownloadScheduling() = Unit
    val mediaRuntimeStatus: StateFlow<MediaRuntimeStatus> get() = readyRuntime
    /** Cancellable installation/verification of app-owned extraction tools, never media bytes. */
    suspend fun prepareMediaRuntime() = Unit
    suspend fun closeMediaRuntime() = Unit
    val supportsFileImport: Boolean get() = false
    suspend fun chooseAudioFiles(): List<ImportedAudio> = emptyList()
    suspend fun openExternalUrl(url: String)
}

/** A bounded streaming copier supplied by the native file picker; never whole-file bytes. */
class ImportedAudio(
    val fileName: String,
    val copyTo: suspend (destinationPath: String) -> Unit,
)
