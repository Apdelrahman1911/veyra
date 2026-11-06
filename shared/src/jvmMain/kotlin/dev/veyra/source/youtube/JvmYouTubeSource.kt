package dev.veyra.source.youtube

import dev.kmpyt.jvm.createJvmYouTubeClient
import dev.kmpyt.jvm.DesktopYouTubeRuntimeOptions

/** The application owns this source and closes it once, not on screen disposal. */
fun createJvmYouTubeSource(ffmpegPath: String? = System.getenv("KMPYT_FFMPEG")): YouTubeMediaSource =
    YouTubeMediaSource(createJvmYouTubeClient(ffmpegPath))

/** Runtime provisioning supplies vetted absolute locations without mutating global properties. */
fun createJvmYouTubeSource(ffmpegPath: String? = System.getenv("KMPYT_FFMPEG"),
    runtimeOptions: DesktopYouTubeRuntimeOptions): YouTubeMediaSource =
    YouTubeMediaSource(createJvmYouTubeClient(ffmpegPath, runtimeOptions))
