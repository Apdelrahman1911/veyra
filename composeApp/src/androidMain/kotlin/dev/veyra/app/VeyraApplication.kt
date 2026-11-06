package dev.veyra.app

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import dev.veyra.data.createAndroidLibraryStore
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.LanguagePreference
import dev.veyra.model.VeyraException
import dev.veyra.platform.audio.AndroidAssetValidator
import dev.veyra.platform.audio.AndroidAudioEngine
import dev.veyra.platform.audio.AndroidMediaSessionBridge
import dev.veyra.platform.background.AndroidDownloadExecutionGate
import dev.veyra.platform.background.PlaybackForegroundGate
import dev.veyra.playback.PlaybackCommand
import dev.veyra.playback.PlaybackResult
import dev.veyra.playback.PlaybackState
import dev.veyra.source.network.PublicUrlPolicy
import dev.veyra.source.youtube.createAndroidYouTubeSource
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One audio engine, Room store, source client and controller for the entire application process. */
class VeyraApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val constructed = CompletableDeferred<AppContainer>()
    private val mutableBootstrap = MutableStateFlow<AndroidBootstrap>(AndroidBootstrap.Loading)
    val bootstrap: StateFlow<AndroidBootstrap> = mutableBootstrap.asStateFlow()
    private val mutablePlayback = MutableStateFlow(PlaybackState())
    val playback: StateFlow<PlaybackState> = mutablePlayback.asStateFlow()
    private val mutableNativeLanguage = MutableStateFlow(LanguagePreference.ENGLISH)
    val nativeLanguage: StateFlow<LanguagePreference> = mutableNativeLanguage.asStateFlow()
    private var container: AppContainer? = null
    private var visibleActivities = 0
    private lateinit var engine: AndroidAudioEngine

    lateinit var playbackForeground: PlaybackForegroundGate
        private set
    lateinit var downloadExecution: AndroidDownloadExecutionGate
        private set
    lateinit var mediaSession: AndroidMediaSessionBridge
        private set

    override fun onCreate() {
        super.onCreate()
        refreshNativeLanguage()
        playbackForeground = PlaybackForegroundGate(this)
        downloadExecution = AndroidDownloadExecutionGate(this)
        engine = AndroidAudioEngine(this) { playbackForeground.awaitForeground() }
        var directory: File? = null
        mediaSession = AndroidMediaSessionBridge(
            context = this,
            scope = appScope,
            playback = playback,
            language = nativeLanguage,
            dispatch = ::dispatch,
            activityIntent = activityIntent(),
            mediaLookup = { container?.store?.state?.value?.media?.get(it) },
            skipBackSeconds = { container?.store?.state?.value?.settings?.skipBackSeconds ?: 15 },
            skipForwardSeconds = { container?.store?.state?.value?.settings?.skipForwardSeconds ?: 30 },
            cachedArtwork = { id ->
                withContext(Dispatchers.IO) {
                    val path = container?.cachedArtworkPath(id)
                    val file = path?.let(::File)
                    val root = directory
                    if (root != null && file != null && file.isFile && file.length() in 1..1_048_576 &&
                        file.canonicalPath.startsWith(root.absolutePath + File.separator)) file.readBytes() else null
                }
            },
        )
        // No Room opening, recovery, source call or blocking runBlocking in Application.onCreate.
        appScope.launch {
            val created = try {
                val root = withContext(Dispatchers.IO) { androidApplicationDirectory(filesDir) }
                directory = root
                val services = AndroidPlatformServices(this@VeyraApplication, root, engine, downloadExecution)
                AppContainer(services, appScope)
            } catch (cancelled: CancellationException) {
                constructed.cancel(cancelled)
                throw cancelled
            } catch (error: Exception) {
                val failure = (error as? VeyraException)?.failure
                    ?: AppFailure(FailureCode.RECOVERY_REQUIRED, "application_initialization")
                mutableBootstrap.value = AndroidBootstrap.Failed(failure)
                constructed.completeExceptionally(VeyraException(failure))
                return@launch
            }
            container = created
            // Availability is not successful initialization. The shared UI owns loading, typed
            // failures and the explicit backup-restore action, including subsequent attempts.
            mutableBootstrap.value = AndroidBootstrap.Available(created)
            constructed.complete(created)
            launch { created.playback.state.collect { mutablePlayback.value = it } }
            launch { created.store.state.map { it.settings.language }.distinctUntilChanged().collect { refreshNativeLanguage() } }
            launch {
                created.initialization.collect { state ->
                    if (state is AppInitialization.Ready) resumeDownloadsWhenVisible()
                }
            }
            try { created.initialize() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* AppInitialization.Failed remains visible and recoverable. */ }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshNativeLanguage()
    }

    private fun refreshNativeLanguage() {
        val preference = container?.store?.state?.value?.settings?.language ?: LanguagePreference.SYSTEM
        mutableNativeLanguage.value = if (preference == LanguagePreference.SYSTEM) applicationLanguage(resources.configuration) else preference
    }

    internal fun nativeLabelsContext() = nativeLanguageContext(this, nativeLanguage.value)

    /** Main-only service lifecycle callback; interruption is tagged by the originating native deck. */
    internal fun onPlaybackServiceLost() { engine.foregroundServiceLost() }

    /** Workers may await an attempt, but a failed attempt never poisons later explicit recovery. */
    suspend fun awaitContainer(): AppContainer = constructed.await().also {
        awaitApplicationInitialization(it.initialization)
    }

    suspend fun dispatch(command: PlaybackCommand): PlaybackResult {
        val state = bootstrap.value
        val available = (state as? AndroidBootstrap.Available)?.container
        val failure = (state as? AndroidBootstrap.Failed)?.failure
            ?: applicationInitializationFailure(available?.initialization?.value)
        // Never retain an old Play/Resume command until a user later restores a failed library.
        if (failure != null || available == null) return PlaybackResult.Failure(failure ?: AppFailure(FailureCode.INVALID_STATE))
        return try { available.playback.dispatch(command) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            PlaybackResult.Failure((error as? VeyraException)?.failure ?: AppFailure(FailureCode.INVALID_STATE))
        }
    }

    fun onVisible() {
        visibleActivities++
        resumeDownloadsWhenVisible()
    }

    fun onHidden() { visibleActivities = (visibleActivities - 1).coerceAtLeast(0) }

    private fun resumeDownloadsWhenVisible() {
        if (visibleActivities == 0) return
        appScope.launch {
            try {
                val initialized = container ?: return@launch
                if (initialized.initialization.value !is AppInitialization.Ready) return@launch
                downloadExecution.awaitForegroundReady()
                if (visibleActivities > 0 && initialized.initialization.value is AppInitialization.Ready) {
                    initialized.downloads.resumePending()
                }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Initialization/download failures remain visible in shared state. */ }
        }
    }

    fun checkpointPlayback() {
        if (container?.initialization?.value is AppInitialization.Ready) {
            appScope.launch { dispatch(PlaybackCommand.FlushProgress) }
        }
    }

    fun activityIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

sealed interface AndroidBootstrap {
    data object Loading : AndroidBootstrap
    data class Available(val container: AppContainer) : AndroidBootstrap
    data class Failed(val failure: AppFailure) : AndroidBootstrap
}

private class AndroidPlatformServices(
    private val application: VeyraApplication,
    directory: File,
    override val engine: AndroidAudioEngine,
    override val downloadExecution: AndroidDownloadExecutionGate,
) : PlatformServices {
    override val dataDirectory = directory.absolutePath
    override val store = createAndroidLibraryStore(application, dataDirectory)
    override val validator = AndroidAssetValidator(directory)
    override val youtube = createAndroidYouTubeSource(application)
    override val systemLanguage: LanguagePreference get() = applicationLanguage(application.resources.configuration)
    override val systemDarkTheme: Boolean get() =
        application.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    // A picker is not a second acquisition layer. Remains disabled until central registration is proven.
    override val supportsFileImport = false

    override suspend fun openExternalUrl(url: String) = withContext(Dispatchers.Main.immediate) {
        val checked = PublicUrlPolicy.normalize(url)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(checked)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { application.startActivity(intent) }
        catch (_: Exception) { throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE, "external_browser_unavailable")) }
    }
}

internal fun applicationLanguage(configuration: Configuration): LanguagePreference {
    @Suppress("DEPRECATION")
    val language = if (Build.VERSION.SDK_INT >= 24) configuration.locales[0]?.language else configuration.locale?.language
    return if (language == "ar") LanguagePreference.ARABIC else LanguagePreference.ENGLISH
}
