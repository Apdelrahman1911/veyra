@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.veyra.platform.audio

import android.content.Context
import android.content.res.Configuration
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veyra.app.R
import dev.veyra.app.nativeLanguageContext
import dev.veyra.model.LanguagePreference
import dev.veyra.model.QueueContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android resource/button projections, not OS lock-screen/session action execution. */
@RunWith(AndroidJUnit4::class)
class PlaybackNotificationProviderTest {
    @Test fun appLanguageChangesLabelsWithoutChangingTransportOrderOrOsConfiguration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = Configuration(context.resources.configuration)
        val english = nativeLanguageContext(context, LanguagePreference.ENGLISH)
        val arabic = nativeLanguageContext(context, LanguagePreference.ARABIC)
        val music = playbackNotificationButtons(english, QueueContext.MUSIC, QueueSessionPlayer.SAFE_COMMANDS, false)
        val translated = playbackNotificationButtons(arabic, QueueContext.MUSIC, QueueSessionPlayer.SAFE_COMMANDS, true)
        val expectedMusic = listOf(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT)
        assertEquals(expectedMusic, music.map { it.playerCommand })
        assertEquals(expectedMusic, translated.map { it.playerCommand })
        assertEquals("Previous track", music.first().displayName)
        assertEquals("المقطع السابق", translated.first().displayName)
        assertEquals("إيقاف مؤقت", translated[1].displayName)
        assertEquals("التنزيلات", arabic.getString(R.string.download_channel))
        assertEquals(original, context.resources.configuration)
        val podcast = playbackNotificationButtons(arabic, QueueContext.PODCAST, QueueSessionPlayer.SAFE_COMMANDS, true)
        assertEquals(listOf(Player.COMMAND_SEEK_BACK, Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_FORWARD),
            podcast.map { it.playerCommand })
        assertEquals("تخطي للخلف", podcast.first().displayName)
    }

    @Test fun notificationNeverOffersAnUnavailableCommand() {
        val context = nativeLanguageContext(ApplicationProvider.getApplicationContext(), LanguagePreference.ENGLISH)
        val onlyPlay = Player.Commands.Builder().add(Player.COMMAND_PLAY_PAUSE).build()
        assertEquals(listOf(Player.COMMAND_PLAY_PAUSE),
            playbackNotificationButtons(context, QueueContext.MUSIC, onlyPlay, false).map { it.playerCommand })
        assertTrue(playbackNotificationButtons(context, QueueContext.PODCAST, Player.Commands.EMPTY, false).isEmpty())
    }
}
