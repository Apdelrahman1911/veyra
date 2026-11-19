package dev.veyra.source.youtube

import dev.kmpyt.IosDownloadStageEvent
import dev.kmpyt.IosResearchEvent
import dev.kmpyt.createIosYouTubeClient

/** Keep one app-lifetime client so owned Darwin/WebKit resources have one close. */
fun createIosYouTubeSource(): YouTubeMediaSource = YouTubeMediaSource(createIosYouTubeClient())

fun createIosYouTubeSource(observer: (IosDownloadStageEvent) -> Unit): YouTubeMediaSource =
    YouTubeMediaSource(createIosYouTubeClient(observer))

fun createIosYouTubeSource(download: (IosDownloadStageEvent) -> Unit, research: (IosResearchEvent) -> Unit,
    diagnosticsEnabled: () -> Boolean): YouTubeMediaSource =
    YouTubeMediaSource(createIosYouTubeClient(download, research, diagnosticsEnabled))
