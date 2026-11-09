package dev.veyra.source.youtube

import android.content.Context
import dev.kmpyt.android.createAndroidYouTubeClient

/** Keep one app-lifetime client; the offline WebView runtime uses application context. */
fun createAndroidYouTubeSource(context: Context): YouTubeMediaSource =
    YouTubeMediaSource(createAndroidYouTubeClient(context.applicationContext))
