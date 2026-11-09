package dev.veyra.desktop

import dev.veyra.desktop.session.LinuxMprisSession
import dev.veyra.desktop.session.MprisPlayer
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.QueueContext
import dev.veyra.model.QueueEntry
import dev.veyra.model.QueueSnapshot
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.playback.PlaybackCommand
import dev.veyra.playback.PlaybackPhase
import dev.veyra.playback.PlaybackState
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Introspectable
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A private, repository-owned session bus; no system/global D-Bus state is modified. */
class LinuxMprisSessionTest {
    @Test fun actualDbusIntrospectionMetadataAndControlsRouteToTheSingleCoordinator() {
        assumeTrue("Linux session-bus test", System.getProperty("os.name").equals("Linux", true))
        val daemon = Path.of("/usr/bin/dbus-daemon")
        assumeTrue("An existing dbus-daemon is required", Files.isExecutable(daemon))
        val project = System.getenv("VEYRA_PROJECT_ROOT")?.let(Path::of)
            ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("settings.gradle.kts")) }
        val root = project.resolve(".work/desktop-audio/mpris-tests").also { Files.createDirectories(it) }
        val socket = root.resolve("session-${System.nanoTime()}.sock")
        val process = ProcessBuilder(daemon.toString(), "--session", "--nofork", "--nopidfile", "--print-address=1", "--address=unix:path=$socket")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        var session: LinuxMprisSession? = null
        var client: org.freedesktop.dbus.connections.impl.DBusConnection? = null
        try {
            val address = process.inputStream.bufferedReader().readLine()
            assertTrue(address.startsWith("unix:"))
            val commands = LinkedBlockingQueue<PlaybackCommand>()
            session = LinuxMprisSession.create({ commands.add(it) }, {}, {}, {}, address)
            client = DBusConnectionBuilder.forAddress(address).withShared(false).build()
            val name = "org.mpris.MediaPlayer2.veyra.instance${ProcessHandle.current().pid()}"
            val path = "/org/mpris/MediaPlayer2"
            val introspection = client.getRemoteObject(name, path, Introspectable::class.java).Introspect()
            assertTrue(introspection.contains("Metadata") && introspection.contains("a{sv}"), introspection)
            val state = PlaybackState(
                initialized = true,
                currentItem = MediaItem("fixture", "Authored session fixture", MediaKind.MUSIC, SourceRef(SourceProvider.LOCAL, "fixture"), artist = "Veyra tests"),
                currentEntryId = "occurrence-fixture",
                queues = mapOf(QueueContext.MUSIC to QueueSnapshot(listOf(QueueEntry("occurrence-fixture", "fixture")), "occurrence-fixture")),
                phase = PlaybackPhase.PAUSED, seekable = true, positionMs = 1250, durationMs = 12_000, generation = 9,
            )
            session.update(state)
            val properties = client.getRemoteObject(name, path, Properties::class.java)
            val all = properties.GetAll("org.mpris.MediaPlayer2.Player")
            assertEquals("Paused", all["PlaybackStatus"]?.value)
            assertEquals(1_250_000L, properties.Get<Any>("org.mpris.MediaPlayer2.Player", "Position"))
            @Suppress("UNCHECKED_CAST") val metadata = all["Metadata"]?.value as Map<String, Variant<*>>
            assertEquals("Authored session fixture", metadata["xesam:title"]?.value)
            val track = assertIs<DBusPath>(metadata["mpris:trackid"]?.value)
            val player = client.getRemoteObject(name, path, MprisPlayer::class.java)
            player.SetPosition(DBusPath("/wrong/track"), 3_000_000)
            assertEquals(null, commands.poll(100, TimeUnit.MILLISECONDS))
            player.SetPosition(track, 3_000_000)
            assertEquals(PlaybackCommand.Seek(3000, "occurrence-fixture", 9), commands.poll(2, TimeUnit.SECONDS))
            player.Seek(-750_000)
            assertEquals(PlaybackCommand.SkipBy(-750, "occurrence-fixture", 9), commands.poll(2, TimeUnit.SECONDS))
            player.PlayPause()
            assertEquals(PlaybackCommand.PlayPause, commands.poll(2, TimeUnit.SECONDS))
            properties.Set("org.mpris.MediaPlayer2.Player", "Rate", 1.5)
            assertEquals(PlaybackCommand.SetRate(1.5f), commands.poll(2, TimeUnit.SECONDS))
            assertFails { player.OpenUri("https://example.com/not-a-central-resolution") }
            assertNotNull(properties.GetAll("org.mpris.MediaPlayer2")["CanRaise"])
        } finally {
            client?.close(); session?.close()
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
            Files.deleteIfExists(socket)
        }
    }
}
