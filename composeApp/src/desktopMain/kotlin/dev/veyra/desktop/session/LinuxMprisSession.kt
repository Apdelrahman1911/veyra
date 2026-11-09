package dev.veyra.desktop.session

import dev.veyra.model.QueueContext
import dev.veyra.model.RepeatMode
import dev.veyra.playback.PlaybackCommand
import dev.veyra.playback.PlaybackPhase
import dev.veyra.playback.PlaybackState
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.TypeRef
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperties
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.annotations.DBusProperty.Access.READ
import org.freedesktop.dbus.annotations.DBusProperty.Access.READ_WRITE
import org.freedesktop.dbus.annotations.PropertiesEmitsChangedSignal.EmitChangeSignal.FALSE
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import java.security.MessageDigest
import kotlin.math.abs

private const val ROOT = "org.mpris.MediaPlayer2"
private const val PLAYER = "org.mpris.MediaPlayer2.Player"
private const val OBJECT_PATH = "/org/mpris/MediaPlayer2"

interface StringArraySignature : TypeRef<List<String>>
interface MetadataSignature : TypeRef<Map<String, Variant<*>>>

@DBusInterfaceName(ROOT)
@DBusProperties(
    DBusProperty(name = "CanQuit", type = Boolean::class, access = READ),
    DBusProperty(name = "CanRaise", type = Boolean::class, access = READ),
    DBusProperty(name = "HasTrackList", type = Boolean::class, access = READ),
    DBusProperty(name = "Identity", type = String::class, access = READ),
    DBusProperty(name = "SupportedUriSchemes", type = StringArraySignature::class, access = READ),
    DBusProperty(name = "SupportedMimeTypes", type = StringArraySignature::class, access = READ),
)
interface MprisRoot : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName(PLAYER)
@DBusProperties(
    DBusProperty(name = "PlaybackStatus", type = String::class, access = READ),
    DBusProperty(name = "LoopStatus", type = String::class, access = READ_WRITE),
    DBusProperty(name = "Rate", type = Double::class, access = READ_WRITE),
    DBusProperty(name = "Shuffle", type = Boolean::class, access = READ_WRITE),
    DBusProperty(name = "Metadata", type = MetadataSignature::class, access = READ),
    DBusProperty(name = "Volume", type = Double::class, access = READ_WRITE),
    DBusProperty(name = "Position", type = Long::class, access = READ, emitChangeSignal = FALSE),
    DBusProperty(name = "MinimumRate", type = Double::class, access = READ),
    DBusProperty(name = "MaximumRate", type = Double::class, access = READ),
    DBusProperty(name = "CanGoNext", type = Boolean::class, access = READ),
    DBusProperty(name = "CanGoPrevious", type = Boolean::class, access = READ),
    DBusProperty(name = "CanPlay", type = Boolean::class, access = READ),
    DBusProperty(name = "CanPause", type = Boolean::class, access = READ),
    DBusProperty(name = "CanSeek", type = Boolean::class, access = READ),
    DBusProperty(name = "CanControl", type = Boolean::class, access = READ),
)
interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)
    class Seeked(path: String, position: Long) : DBusSignal(path, position)
}

/** The session is a projection/command adapter, not a player or a second queue. */
internal class LinuxMprisSession private constructor(
    private val connection: DBusConnection,
    private val busName: String,
    private val command: (PlaybackCommand) -> Unit,
    private val raise: () -> Unit,
    private val quit: () -> Unit,
    private val setVolume: (Float) -> Unit,
) : MprisRoot, MprisPlayer, Properties, AutoCloseable {
    @Volatile private var state = PlaybackState()
    @Volatile private var volume = 1.0
    @Volatile private var closed = false

    override fun getObjectPath(): String = OBJECT_PATH
    override fun Raise() = raise()
    override fun Quit() = quit()
    override fun Next() = command(PlaybackCommand.Next)
    override fun Previous() = command(PlaybackCommand.Previous)
    override fun Pause() = command(PlaybackCommand.Pause)
    override fun PlayPause() = command(PlaybackCommand.PlayPause)
    override fun Stop() = command(PlaybackCommand.Stop)
    override fun Play() = command(PlaybackCommand.Resume)
    override fun Seek(offset: Long) {
        val current = state
        if (!current.seekable) return
        val delta = offset / 1000
        command(PlaybackCommand.SkipBy(delta, current.currentEntryId, current.generation))
    }
    override fun SetPosition(trackId: DBusPath, position: Long) {
        val current = state
        if (!current.seekable || position < 0 || trackId.path != trackPath(current).path) return
        if (current.durationMs != null && position / 1000 > current.durationMs!!) return
        command(PlaybackCommand.Seek(position / 1000, current.currentEntryId, current.generation))
    }
    override fun OpenUri(uri: String): Unit = unsupported()

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
        (GetAll(interfaceName)[propertyName] ?: unknownProperty()) as A

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
        if (interfaceName != PLAYER) unknownProperty()
        val unwrapped = if (value is Variant<*>) value.value else value
        when (propertyName) {
            "Rate" -> {
                val rate = (unwrapped as? Number)?.toFloat() ?: invalid()
                if (!rate.isFinite() || rate !in .5f..2f) invalid()
                command(PlaybackCommand.SetRate(rate))
            }
            "Shuffle" -> {
                if (state.activeContext != QueueContext.MUSIC) unsupported()
                command(PlaybackCommand.SetShuffle(unwrapped as? Boolean ?: invalid()))
            }
            "LoopStatus" -> {
                if (state.activeContext != QueueContext.MUSIC) unsupported()
                command(PlaybackCommand.SetRepeat(when (unwrapped) { "None" -> RepeatMode.OFF; "Track" -> RepeatMode.ONE; "Playlist" -> RepeatMode.ALL; else -> invalid() }))
            }
            "Volume" -> {
                val gain = (unwrapped as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: invalid()
                volume = gain.coerceIn(0.0, 1.0)
                setVolume(volume.toFloat())
                connection.sendMessage(Properties.PropertiesChanged(OBJECT_PATH, PLAYER, mapOf("Volume" to Variant(volume)), emptyList()))
            }
            else -> unknownProperty()
        }
    }

    override fun GetAll(interfaceName: String): MutableMap<String, Variant<*>> = when (interfaceName) {
        ROOT -> linkedMapOf(
            "CanQuit" to Variant(true), "CanRaise" to Variant(true), "HasTrackList" to Variant(false),
            "Identity" to Variant("Veyra"), "SupportedUriSchemes" to Variant(emptyList<String>(), "as"),
            "SupportedMimeTypes" to Variant(emptyList<String>(), "as"),
        )
        PLAYER -> properties(state)
        else -> unknownProperty()
    }

    @Synchronized fun update(next: PlaybackState) {
        if (closed) return
        val previous = state
        val before = properties(previous)
        state = next
        val changed = properties(next).filter { (key, value) -> key != "Position" && before[key] != value }
        if (changed.isNotEmpty()) connection.sendMessage(Properties.PropertiesChanged(OBJECT_PATH, PLAYER, changed, emptyList()))
        if (previous.currentEntryId == next.currentEntryId && next.currentEntryId != null && next.pendingSeekMs == null &&
            ((previous.pendingSeekMs != null && abs(next.positionMs - previous.pendingSeekMs!!) < 1_000) ||
                next.positionMs < previous.positionMs || abs(next.positionMs - previous.positionMs) > 2_000)) {
            connection.sendMessage(MprisPlayer.Seeked(OBJECT_PATH, micros(next.positionMs)))
        }
    }

    private fun properties(current: PlaybackState): MutableMap<String, Variant<*>> = linkedMapOf(
        "PlaybackStatus" to Variant(when {
            current.isPlaying -> "Playing"
            current.currentItem != null && current.phase !in setOf(PlaybackPhase.IDLE, PlaybackPhase.ENDED, PlaybackPhase.ERROR) -> "Paused"
            else -> "Stopped"
        }),
        "LoopStatus" to Variant(when (current.queue.repeat) { RepeatMode.OFF -> "None"; RepeatMode.ONE -> "Track"; RepeatMode.ALL -> "Playlist" }),
        "Rate" to Variant(current.effectiveSpeed.toDouble()), "Shuffle" to Variant(current.queue.shuffle),
        "Metadata" to Variant(metadata(current), "a{sv}"), "Volume" to Variant(volume),
        "Position" to Variant(micros(current.positionMs)), "MinimumRate" to Variant(.5), "MaximumRate" to Variant(2.0),
        "CanGoNext" to Variant(current.queue.currentIndex >= 0 && (current.queue.currentIndex < current.queue.entries.lastIndex || current.queue.repeat == RepeatMode.ALL)),
        "CanGoPrevious" to Variant(current.currentItem != null), "CanPlay" to Variant(current.currentItem != null),
        "CanPause" to Variant(current.currentItem != null), "CanSeek" to Variant(current.seekable), "CanControl" to Variant(true),
    )

    private fun metadata(current: PlaybackState): Map<String, Variant<*>> {
        val item = current.currentItem ?: return emptyMap()
        return buildMap {
            put("mpris:trackid", Variant(trackPath(current)))
            put("xesam:title", Variant(item.title))
            put("xesam:artist", Variant(listOf(item.artist ?: item.creator), "as"))
            item.album?.let { put("xesam:album", Variant(it)) }
            current.durationMs?.let { put("mpris:length", Variant(micros(it))) }
            // No ephemeral/signed source URLs or remote artwork fetches are sent over the session bus.
            item.localArtworkPath?.let { path ->
                runCatching { java.nio.file.Path.of(path) }.getOrNull()?.takeIf(java.nio.file.Files::isRegularFile)?.let {
                    put("mpris:artUrl", Variant(it.toUri().toString()))
                }
            }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.unExportObject(OBJECT_PATH) }
        runCatching { connection.releaseBusName(busName) }
        connection.close()
    }

    companion object {
        fun create(command: (PlaybackCommand) -> Unit, raise: () -> Unit, quit: () -> Unit, setVolume: (Float) -> Unit,
            sessionBusAddress: String? = null): LinuxMprisSession {
            val connection = (sessionBusAddress?.let(DBusConnectionBuilder::forAddress) ?: DBusConnectionBuilder.forSessionBus()).withShared(false).build()
            val name = "org.mpris.MediaPlayer2.veyra.instance${ProcessHandle.current().pid()}"
            try {
                val session = LinuxMprisSession(connection, name, command, raise, quit, setVolume)
                connection.exportObject(OBJECT_PATH, session)
                connection.requestBusName(name)
                return session
            } catch (error: Throwable) { connection.close(); throw error }
        }

        private fun micros(ms: Long) = ms.coerceIn(0, Long.MAX_VALUE / 1000) * 1000
        private fun trackPath(state: PlaybackState): DBusPath = DBusPath(state.currentEntryId?.let { id ->
            "/dev/veyra/track/" + MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        } ?: "/org/mpris/MediaPlayer2/TrackList/NoTrack")
        private fun unsupported(): Nothing = throw DBusExecutionException("This operation is not supported").apply { type = "org.mpris.MediaPlayer2.Player.Error.NotSupported" }
        private fun invalid(): Nothing = throw DBusExecutionException("Invalid property value").apply { type = "org.freedesktop.DBus.Error.InvalidArgs" }
        private fun unknownProperty(): Nothing = throw DBusExecutionException("Unknown or read-only property").apply { type = "org.freedesktop.DBus.Error.UnknownProperty" }
    }
}
