package dev.veyra.desktop

import dev.veyra.app.MediaRuntimeComponent
import dev.veyra.app.MediaRuntimePhase
import dev.veyra.app.MediaRuntimeStatus
import dev.veyra.app.PlatformServices
import dev.veyra.data.LibraryStore
import dev.veyra.data.createPlatformLibraryStore
import dev.veyra.desktop.audio.DesktopAssetValidator
import dev.veyra.desktop.audio.DesktopAudioEngine
import dev.veyra.desktop.runtime.DesktopFilePicker
import dev.veyra.desktop.runtime.DesktopMediaRuntime
import dev.veyra.desktop.runtime.DesktopMediaExecutionPolicy
import dev.veyra.desktop.runtime.DesktopNativeRuntime
import dev.veyra.desktop.runtime.DesktopProcessContext
import dev.veyra.desktop.runtime.DesktopRuntimeConfig
import dev.veyra.desktop.runtime.createDesktopYouTubeSource
import dev.veyra.model.LanguagePreference
import dev.veyra.source.youtube.YouTubeMediaSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.util.Locale

class DesktopPlatformServices private constructor(
    context: DesktopProcessContext,
    config: DesktopRuntimeConfig,
) : PlatformServices {
    private val resources = DesktopServiceResources()
    override val store: LibraryStore get() = resources.requireResource(DesktopServiceResources.Slot.STORE)
    override val engine: DesktopAudioEngine get() = resources.requireResource(DesktopServiceResources.Slot.ENGINE)
    override val youtube: YouTubeMediaSource get() = resources.requireResource(DesktopServiceResources.Slot.YOUTUBE)
    private val mediaRuntime: DesktopMediaRuntime get() = resources.requireResource(DesktopServiceResources.Slot.MEDIA_RUNTIME)
    @Volatile private var runtimeStatus: StateFlow<MediaRuntimeStatus> = MutableStateFlow(
        MediaRuntimeStatus(MediaRuntimePhase.REQUIRED, MediaRuntimeComponent.NODE),
    ).asStateFlow()
    // Both constructors below are resource-free: neither native validation nor a picker starts.
    override val validator = DesktopAssetValidator(config)
    private val picker = DesktopFilePicker(context)
    override val dataDirectory: String = context.dataDirectory.toString()
    override val systemLanguage = if (Locale.getDefault().language == "ar") LanguagePreference.ARABIC else LanguagePreference.ENGLISH
    override val supportsFileImport = true
    // UI remains mounted during shutdown. Retain the projection (not its runtime owner) after
    // the slot is released, so a shutdown-triggered recomposition never reads a missing owner.
    override val mediaRuntimeStatus get() = runtimeStatus
    override suspend fun prepareMediaRuntime() = mediaRuntime.prepare()
    override suspend fun closeMediaRuntime() = resources.closeMediaRuntime()
    /** Safe for a published owner even if create never returned or acquired its first resource. */
    internal suspend fun closeAcquiredResources() = resources.close()
    override suspend fun chooseAudioFiles() = picker.chooseAudioFiles()
    override suspend fun openExternalUrl(url: String) = withContext(Dispatchers.IO) {
        val uri = URI(url)
        require(uri.scheme?.lowercase(Locale.ROOT) in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null)
        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) { "External browser is unavailable" }
        Desktop.getDesktop().browse(uri)
    }

    companion object {
        internal suspend fun create(
            context: DesktopProcessContext,
            silentAudio: Boolean,
            executionPolicy: DesktopMediaExecutionPolicy = DesktopMediaExecutionPolicy(),
            onOwned: (DesktopPlatformServices) -> Unit,
        ): DesktopPlatformServices = createOwned(context, silentAudio, onOwned) { publishRuntimeStatus ->
            val config = DesktopRuntimeConfig(context.dataDirectory, silentAudio)
            acquire(DesktopServiceResources.Slot.ENGINE, release = { it: DesktopAudioEngine -> it.close() }) {
                DesktopNativeRuntime.initializeProcessPaths(context.dataDirectory)
                DesktopAudioEngine.create(config)
            }
            acquire(DesktopServiceResources.Slot.STORE, release = { it: LibraryStore -> it.close() }) {
                createPlatformLibraryStore(context.dataDirectory.toString())
            }
            val tools = acquire(DesktopServiceResources.Slot.MEDIA_RUNTIME, release = { it: DesktopMediaRuntime -> it.close() }) {
                DesktopMediaRuntime(context)
            }
            publishRuntimeStatus(tools.status)
            acquire(DesktopServiceResources.Slot.YOUTUBE, release = { it: YouTubeMediaSource -> it.close() }) {
                createDesktopYouTubeSource(context, tools, executionPolicy)
            }
        }

        /** Fault-injection seam for the same factory/owner path, with no native allocation. */
        internal suspend fun createOwned(
            context: DesktopProcessContext,
            silentAudio: Boolean,
            onOwned: (DesktopPlatformServices) -> Unit,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
            acquire: suspend DesktopServiceResources.(publishRuntimeStatus: (StateFlow<MediaRuntimeStatus>) -> Unit) -> Unit,
        ): DesktopPlatformServices {
            val services = DesktopPlatformServices(context, DesktopRuntimeConfig(context.dataDirectory, silentAudio))
            services.resources.initialize(onOwned = { onOwned(services) }, dispatcher = dispatcher) {
                acquire { services.runtimeStatus = it }
            }
            return services
        }
    }
}
