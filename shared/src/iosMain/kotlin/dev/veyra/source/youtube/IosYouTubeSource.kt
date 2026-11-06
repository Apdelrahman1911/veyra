package dev.veyra.source.youtube

import dev.kmpyt.createIosYouTubeClient

/** Keep one app-lifetime client so owned Darwin/WebKit resources have one close. */
fun createIosYouTubeSource(): YouTubeMediaSource = YouTubeMediaSource(createIosYouTubeClient())
