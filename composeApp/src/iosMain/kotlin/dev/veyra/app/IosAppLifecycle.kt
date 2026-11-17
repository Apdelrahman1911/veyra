@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.app

import dev.veyra.data.createPlatformLibraryStore
import dev.veyra.downloads.DownloadDiagnosticFailure
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.LanguagePreference
import dev.veyra.model.VeyraException
import dev.veyra.platform.audio.IosAssetValidator
import dev.veyra.platform.audio.IosAudioEngine
import dev.veyra.platform.audio.IosNowPlayingBridge
import dev.veyra.platform.background.IosDownloadExecutionGate
import dev.veyra.playback.PlaybackCommand
import dev.veyra.playback.PlaybackResult
import dev.veyra.playback.PlaybackState
import dev.veyra.source.network.PublicUrlPolicy
import dev.veyra.source.youtube.createIosYouTubeSource
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.*
import platform.UIKit.*

/** Called by the Swift host on Main. Scene/view recreation never creates another audio owner. */
object IosAppLifecycle {
    fun start() { check(NSThread.isMainThread); IosApplication.start() }
    fun didBecomeActive() {
        check(NSThread.isMainThread)
        IosApplication.start()
        IosApplication.becameActive()
    }
    fun willResignActive() { check(NSThread.isMainThread); IosApplication.checkpoint() }
    fun didEnterBackground() { check(NSThread.isMainThread); IosApplication.enteredBackground() }
    fun willTerminate() {
        check(NSThread.isMainThread)
        // iOS does not guarantee this callback or time to finish it. Room and periodic confirmed
        // playback/download checkpoints, not this best-effort callback, provide crash recovery.
        IosApplication.terminate()
    }
}

internal object IosApplication {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableBootstrap = MutableStateFlow<IosBootstrap>(IosBootstrap.Loading)
    val bootstrap: StateFlow<IosBootstrap> = mutableBootstrap.asStateFlow()
    private val playback = MutableStateFlow(PlaybackState())
    private val gate = IosDownloadExecutionGate(scope)
    private var container: AppContainer? = null
    private var nowPlaying: IosNowPlayingBridge? = null
    private var started = false
    private var active = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val created = try {
                val directory = withContext(Dispatchers.Default) { applicationDirectory() }
                debugIosStartup("directory_ready")
                val platform = IosPlatformServices(directory, gate)
                debugIosStartup("platform_ready")
                AppContainer(platform, scope).also { created ->
                    container = created
                    nowPlaying = IosNowPlayingBridge(scope, playback, ::dispatch,
                        skipBackSeconds = { created.store.state.value.settings.skipBackSeconds },
                        skipForwardSeconds = { created.store.state.value.settings.skipForwardSeconds },
                        cachedArtworkPath = { id ->
                            created.cachedArtworkPath(id)?.takeIf { path ->
                                // Resolve both sides the same way: resolving symlinks rewrites
                                // /private/var to /var, so a resolved file never matched the
                                // unresolved app folder and no cover ever reached the lock screen.
                                val resolved = resolvedPath(path)
                                val root = resolvedPath(directory)
                                resolved != null && root != null && resolved.startsWith(root.trimEnd('/') + "/")
                            }
                        },
                        artworkUpdates = created.artworkUpdates,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                debugIosStartup("bootstrap_failed", error)
                val failure = (error as? VeyraException)?.failure
                    ?: AppFailure(FailureCode.RECOVERY_REQUIRED, "application_initialization")
                mutableBootstrap.value = IosBootstrap.Failed(failure)
                return@launch
            }
            // The common UI must remain mounted on Failed so explicit backup recovery is usable.
            mutableBootstrap.value = IosBootstrap.Available(created)
            observeIosDownloadDiagnostics(scope, created)
            launch {
                var previousFailure: AppFailure? = null
                created.playback.state.collect {
                    playback.value = it
                    if (it.failure != previousFailure) {
                        previousFailure = it.failure
                        it.failure?.let(::debugIosPlaybackFailure)
                    }
                }
            }
            launch {
                created.initialization.collect { state ->
                    if (state is AppInitialization.Ready) resumeDownloadsWhenActive()
                }
            }
            try {
                debugIosStartup("library_initializing")
                created.initialize()
                debugIosStartup("library_ready")
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { debugIosStartup("library_failed", error) }
        }
    }

    fun becameActive() {
        active = true
        gate.didBecomeActive()
        resumeDownloadsWhenActive()
    }
    private fun resumeDownloadsWhenActive() {
        if (!active) return
        scope.launch {
            try {
                val initialized = container ?: return@launch
                if (initialized.initialization.value !is AppInitialization.Ready) return@launch
                if (gate.awaitForegroundReady() && active && initialized.initialization.value is AppInitialization.Ready) {
                    initialized.downloads.resumePending()
                }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Bootstrap/coordinator state presents the failure. */ }
        }
    }
    fun enteredBackground() {
        active = false
        gate.didEnterBackground()
        checkpoint()
        // audio background mode is independent: entering background never pauses permitted audio.
    }
    fun checkpoint() {
        if (container?.initialization?.value is AppInitialization.Ready) scope.launch { dispatch(PlaybackCommand.FlushProgress) }
    }
    fun terminate() {
        scope.launch {
            gate.close()
            nowPlaying?.close()
            nowPlaying = null
            container?.close()
        }
    }
    private suspend fun dispatch(command: PlaybackCommand): PlaybackResult {
        val state = bootstrap.value
        val available = (state as? IosBootstrap.Available)?.container
        val failure = (state as? IosBootstrap.Failed)?.failure
            ?: applicationInitializationFailure(available?.initialization?.value)
        // OS commands fail immediately while loading/failed; recovery must never replay old Play.
        if (failure != null || available == null) return PlaybackResult.Failure(failure ?: AppFailure(FailureCode.INVALID_STATE))
        return try { available.playback.dispatch(command) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            PlaybackResult.Failure((error as? VeyraException)?.failure ?: AppFailure(FailureCode.INVALID_STATE))
        }
    }
}

internal sealed interface IosBootstrap {
    data object Loading : IosBootstrap
    data class Available(val container: AppContainer) : IosBootstrap
    data class Failed(val failure: AppFailure) : IosBootstrap
}

private class IosPlatformServices(
    override val dataDirectory: String,
    override val downloadExecution: IosDownloadExecutionGate,
) : PlatformServices {
    override val store = createPlatformLibraryStore(dataDirectory)
    override val engine = IosAudioEngine()
    override val validator = IosAssetValidator("$dataDirectory/media")
    override val youtube = createIosYouTubeSource()
    override val systemLanguage: LanguagePreference get() = iosLanguage()
    override val systemDarkTheme: Boolean get() = UITraitCollection.currentTraitCollection.userInterfaceStyle == UIUserInterfaceStyle.UIUserInterfaceStyleDark
    // Local import is intentionally unavailable until central kmpYT registration is verified.
    override val supportsFileImport = false

    override suspend fun openExternalUrl(url: String): Unit = withContext(Dispatchers.Main.immediate) {
        val checked = NSURL.URLWithString(PublicUrlPolicy.normalize(url))
            ?: throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE, "external_url"))
        val opened = suspendCancellableCoroutine { continuation ->
            UIApplication.sharedApplication.openURL(checked, options = emptyMap<Any?, Any>()) { accepted ->
                if (continuation.isActive) continuation.resume(accepted)
            }
        }
        if (!opened) throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE, "external_browser_unavailable"))
    }
}

internal fun iosLanguage(): LanguagePreference =
    if ((NSLocale.preferredLanguages.firstOrNull() as? String)?.substringBefore('-') == "ar")
        LanguagePreference.ARABIC else LanguagePreference.ENGLISH

private fun resolvedPath(path: String): String? = NSURL.fileURLWithPath(path).URLByResolvingSymlinksInPath?.path

private fun applicationDirectory(): String {
    val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String ?: throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "application_directory"))
    return iosApplicationDirectory(base)
}

/** Resolve the OS-provided base (/var may alias /private/var), never app-owned child symlinks. */
internal fun iosApplicationDirectory(applicationSupportDirectory: String): String {
    val files = NSFileManager.defaultManager
    // The OS owns this base. Foundation can retain /var even after resolving the URL;
    // use the same filesystem canonicalization as the media ownership checks.
    if (!files.createDirectoryAtPath(applicationSupportDirectory, withIntermediateDirectories = true,
            attributes = null, error = null)) {
        throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "application_directory"))
    }
    val fileSystem = FileSystem.SYSTEM
    val base = try { fileSystem.canonicalize(applicationSupportDirectory.toPath()).toString() }
    catch (_: Exception) { throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "application_directory")) }
    debugIosStartup("directory_os_alias_resolved_${base != applicationSupportDirectory}")
    val path = "${base.trimEnd('/')}/Veyra"
    fun requireOwnedPath(value: String) {
        if (fileSystem.metadataOrNull(value.toPath())?.symlinkTarget != null) {
            throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "application_directory_symlink"))
        }
    }
    requireOwnedPath(path)
    val protection = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication)
    if (!files.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = protection, error = null) ||
        !files.setAttributes(protection, ofItemAtPath = path, error = null)) {
        throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "application_directory"))
    }
    requireOwnedPath(path)
    for (name in listOf("media", "artwork")) {
        val cache = "$path/$name"
        requireOwnedPath(cache)
        if (!files.createDirectoryAtPath(cache, withIntermediateDirectories = true, attributes = protection, error = null) ||
            !NSURL.fileURLWithPath(cache).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)) {
            throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "offline_cache_directory"))
        }
        requireOwnedPath(cache)
    }
    return path
}

/** Local Debug diagnostics contain only stages, typed codes and stack frames, never messages. */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private fun debugIosStartup(stage: String, error: Exception? = null) {
    if (!kotlin.native.Platform.isDebugBinary) return
    val failure = (error as? VeyraException)?.failure
    val detail = failure?.detail?.takeIf { it.matches(Regex("[A-Za-z_]{1,80}")) }
    println("VEYRA_STARTUP stage=$stage type=${error?.let { it::class.simpleName }} code=${failure?.code} detail=$detail")
    error?.stackTraceToString()?.lineSequence()
        ?.filter { it.trimStart().startsWith("at ") }
        ?.take(16)?.forEach(::println)
}

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private fun debugIosPlaybackFailure(failure: AppFailure) {
    if (!kotlin.native.Platform.isDebugBinary) return
    val detail = failure.detail?.takeIf {
        it.matches(Regex("(?:YOUTUBE_[A-Z_]+|native_audio_-?[0-9]+|request_headers|secure_media_url_required|playback_rate|initial_seek_failed)")) ||
            it in setOf("acquisition_outcome_unknown", "acquisition_collection_action_required",
                "schema2_selection_requires_explicit_new_request", "acquisition_selection_not_attached",
                "acquisition_selection_unplayable", "acquisition_unsent_claim_not_released")
    }
    println("VEYRA_PLAYBACK_FAILURE code=${failure.code} detail=$detail")
}

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private fun observeIosDownloadDiagnostics(scope: CoroutineScope, container: AppContainer) {
    if (!kotlin.native.Platform.isDebugBinary) return
    scope.launch {
        var previousSequence = 0L
        container.downloads.diagnosticEvents.collect { entries ->
            val pending = entries.filter { it.sequence > previousSequence }
            pending.firstOrNull()?.let { first ->
                if (first.sequence > previousSequence + 1) {
                    println("VEYRA_DOWNLOAD_DIAGNOSTIC dropped=${first.sequence - previousSequence - 1}")
                }
            }
            pending.forEach { entry ->
                println("VEYRA_DOWNLOAD_DIAGNOSTIC $entry")
                previousSequence = entry.sequence
            }
        }
    }
    scope.launch {
        container.notice.collect { notice ->
            notice?.failure?.let { failure ->
                println("VEYRA_APP_FAILURE ${DownloadDiagnosticFailure.from(failure)}")
            }
        }
    }
}
