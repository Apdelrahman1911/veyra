package dev.veyra.desktop

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI
import java.util.Locale

class DesktopPlatformServices private constructor(
    context: DesktopProcessContext,
    override val store: LibraryStore,
    override val engine: DesktopAudioEngine,
    override val validator: DesktopAssetValidator,
    override val youtube: YouTubeMediaSource,
    private val mediaRuntime: DesktopMediaRuntime,
) : PlatformServices {
    override val dataDirectory: String = context.dataDirectory.toString()
    override val systemLanguage = if (Locale.getDefault().language == "ar") LanguagePreference.ARABIC else LanguagePreference.ENGLISH
    override val supportsFileImport = true
    override val mediaRuntimeStatus = mediaRuntime.status
    override suspend fun prepareMediaRuntime() = mediaRuntime.prepare()
    override suspend fun closeMediaRuntime() = mediaRuntime.close()
    private val picker = DesktopFilePicker(context)
    override suspend fun chooseAudioFiles() = picker.chooseAudioFiles()
    override suspend fun openExternalUrl(url: String) = withContext(Dispatchers.IO) {
        val uri = URI(url)
        require(uri.scheme?.lowercase(Locale.ROOT) in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null)
        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) { "External browser is unavailable" }
        Desktop.getDesktop().browse(uri)
    }

    companion object {
        internal suspend fun create(context: DesktopProcessContext, silentAudio: Boolean,
            executionPolicy: DesktopMediaExecutionPolicy = DesktopMediaExecutionPolicy()): DesktopPlatformServices {
            var engine: DesktopAudioEngine? = null
            var store: LibraryStore? = null
            var youtube: YouTubeMediaSource? = null
            var mediaRuntime: DesktopMediaRuntime? = null
            try {
                return withContext(Dispatchers.IO) {
                    val config = DesktopRuntimeConfig(context.dataDirectory, silentAudio)
                    DesktopNativeRuntime.initializeProcessPaths(context.dataDirectory)
                    val audio = DesktopAudioEngine.create(config).also { engine = it }
                    val library = createPlatformLibraryStore(context.dataDirectory.toString()).also { store = it }
                    val tools = DesktopMediaRuntime(context).also { mediaRuntime = it }
                    val source = createDesktopYouTubeSource(context, tools, executionPolicy).also { youtube = it }
                    DesktopPlatformServices(context, library, audio, DesktopAssetValidator(config), source, tools)
                }
            } catch (error: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { mediaRuntime?.close() }; runCatching { engine?.close() }
                    runCatching { youtube?.close() }; runCatching { store?.close() }
                }
                throw error
            }
        }
    }
}
