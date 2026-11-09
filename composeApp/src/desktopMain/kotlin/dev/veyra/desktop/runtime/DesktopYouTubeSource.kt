package dev.veyra.desktop.runtime

import dev.kmpyt.YouTubeClient
import dev.kmpyt.YouTubeDownloader
import dev.kmpyt.YouTubeNativePlaybackLimits
import dev.kmpyt.YouTubePlaybackAdapter
import dev.kmpyt.extraction.KtorResearchTransport
import dev.kmpyt.extraction.PlannedDownload
import dev.kmpyt.extraction.SourcePlaybackAuthorization
import dev.kmpyt.extraction.SourcePlaybackRequest
import dev.kmpyt.extraction.YouTubeJavaScriptRuntime
import dev.kmpyt.extraction.YouTubeJavaScriptSession
import dev.kmpyt.jvm.DesktopYouTubeJavaScriptRuntime
import dev.kmpyt.jvm.DesktopYouTubeRuntimeOptions
import dev.kmpyt.jvm.JvmYouTubeDownloader
import dev.kmpyt.jvm.createJvmYouTubeSabrPlaybackAdapter
import dev.veyra.source.youtube.YouTubeMediaSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding

/** Optional, stricter host policy used by the explicitly invoked central verification runner.
 * These hooks may only deny the selected plan, never replace its video or representation.
 * A plan-size check is not itself a bound on a changed network response. */
internal data class DesktopMediaExecutionPolicy(
    val authorizeDownload: suspend (PlannedDownload) -> Unit = {},
    val authorizeSabrPlayback: suspend (SourcePlaybackRequest) -> Unit = {},
    val nativePlaybackLimits: YouTubeNativePlaybackLimits = YouTubeNativePlaybackLimits(),
)

/** No developer PATH fallback, synchronous downloads, global environment edits, or extra media
 * transport. kmpYT remains the sole executor including its owned SABR/native bridge. */
internal fun createDesktopYouTubeSource(
    context: DesktopProcessContext,
    tools: DesktopMediaRuntime,
    policy: DesktopMediaExecutionPolicy = DesktopMediaExecutionPolicy(),
): YouTubeMediaSource {
    val runtime = LazyJavaScriptRuntime(tools)
    val http = HttpClient(CIO) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 60_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 20_000 }
        install(ContentEncoding) { gzip(); deflate() }
    }
    try {
        val cache = context.ownedPath(context.dataDirectory.resolve("tmp/kmpyt-playback"))
        DesktopProcessContext.privateDirectory(cache)
        val downloader = YouTubeDownloader { plan, path, progress ->
            policy.authorizeDownload(plan)
            JvmYouTubeDownloader(http, tools.requirePrepared().ffmpeg.toString()).download(plan, path, progress)
        }
        val nativePlayback = createJvmYouTubeSabrPlaybackAdapter(http, cache.toString(), policy.nativePlaybackLimits)
        val playback = YouTubePlaybackAdapter { request, authorization ->
            policy.authorizeSabrPlayback(request)
            nativePlayback.open(request, SourcePlaybackAuthorization { actual, resourceUrl ->
                policy.authorizeSabrPlayback(actual)
                authorization.requireResource(actual, resourceUrl)
            })
        }
        return YouTubeMediaSource(YouTubeClient(KtorResearchTransport(http, maximumBytes = 8_000_000), runtime, downloader,
            onClose = { try { runtime.close() } finally { http.close() } }, platformName = "JVM",
            playbackAdapter = playback))
    } catch (error: Throwable) {
        runtime.close(); http.close()
        throw error
    }
}

internal class LazyJavaScriptRuntime(private val tools: DesktopMediaRuntime) : YouTubeJavaScriptRuntime, AutoCloseable {
    private var delegate: DesktopYouTubeJavaScriptRuntime? = null
    private var closed = false

    @Synchronized override fun prepare(program: String): YouTubeJavaScriptSession {
        check(!closed) { "Media JavaScript runtime has closed" }
        val verified = tools.requirePrepared() // Fails if the suspend platform gate was omitted.
        val runtime = delegate ?: DesktopYouTubeJavaScriptRuntime(DesktopYouTubeRuntimeOptions(
            nodeExecutable = verified.node.toString(), chromiumExecutable = verified.chromium.toString(),
            temporaryDirectory = verified.temporaryDirectory.toString())).also { delegate = it }
        return runtime.prepare(program)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        delegate?.close()
        delegate = null
    }
}
