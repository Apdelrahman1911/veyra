package dev.veyra.app

import dev.veyra.data.LibraryStore
import dev.veyra.downloads.DownloadExecutionGate
import dev.veyra.downloads.ProcessLifetimeDownloadGate
import dev.veyra.model.LanguagePreference
import dev.veyra.model.AppFailure
import dev.veyra.playback.AssetValidator
import dev.veyra.playback.AudioEngine
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
    val youtube: YouTubeMediaSource
    val systemLanguage: LanguagePreference
    val systemDarkTheme: Boolean get() = false
    val downloadExecution: DownloadExecutionGate get() = ProcessLifetimeDownloadGate
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
