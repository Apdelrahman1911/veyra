package dev.veyra.desktop.session

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import dev.veyra.desktop.runtime.DesktopNativeRuntime
import dev.veyra.desktop.runtime.DesktopProcessContext
import dev.veyra.model.RepeatMode
import dev.veyra.playback.PlaybackCommand
import dev.veyra.playback.PlaybackPhase
import dev.veyra.playback.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Window
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest

sealed interface DesktopSessionStatus {
    data class Available(val implementation: String) : DesktopSessionStatus
    data class Unavailable(val reason: String) : DesktopSessionStatus
}

/** Application-lifetime system controls. Unavailable D-Bus/native sessions never crash playback. */
class DesktopMediaSession private constructor(
    private val backend: Backend?,
    parentScope: CoroutineScope,
    playback: StateFlow<PlaybackState>,
    initialStatus: DesktopSessionStatus,
) {
    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow(initialStatus)
    val status: StateFlow<DesktopSessionStatus> = mutableStatus.asStateFlow()
    private val updates = scope.launch {
        if (backend != null) playback.collect { state ->
            try { backend.update(state) }
            catch (_: Exception) { mutableStatus.value = DesktopSessionStatus.Unavailable("media_session_disconnected") }
        }
    }

    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        updates.cancel()
        updates.join()
        runCatching { backend?.close() }
        scope.cancel()
    }

    private interface Backend : AutoCloseable { fun update(state: PlaybackState) }

    companion object {
        suspend fun create(
            context: DesktopProcessContext,
            window: Window,
            parentScope: CoroutineScope,
            playback: StateFlow<PlaybackState>,
            command: (PlaybackCommand) -> Unit,
            raise: () -> Unit,
            quit: () -> Unit,
            setVolume: (Float) -> Unit,
            outputChanged: () -> Unit,
        ): DesktopMediaSession {
            val platform = DesktopNativeRuntime.platform()
            var status: DesktopSessionStatus = DesktopSessionStatus.Unavailable("media_session_not_initialized")
            var acquired: Backend? = null
            val backend = try {
                if (platform.startsWith("linux")) {
                    withContext(Dispatchers.IO) {
                        val mpris = LinuxMprisSession.create(command, raise, quit, setVolume)
                        status = DesktopSessionStatus.Available("MPRIS2")
                        object : Backend {
                            override fun update(state: PlaybackState) = mpris.update(state)
                            override fun close() = mpris.close()
                        }.also { acquired = it }
                    }
                } else {
                    val windowId = if (platform.startsWith("windows")) withContext(Dispatchers.Main) {
                        check(window.isDisplayable) { "Native window is not ready" }
                        Pointer.nativeValue(Native.getComponentPointer(window))
                    } else 0L
                    withContext(Dispatchers.IO) {
                        NativeBackend.create(context, platform, windowId, command, outputChanged).also {
                            acquired = it
                            status = DesktopSessionStatus.Available(if (platform.startsWith("windows")) "Windows SMTC" else "macOS MediaPlayer")
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { acquired?.close() } }
                throw cancelled
            }
            catch (_: LinkageError) {
                // A missing native dependency is a visible session capability failure, not an
                // exception which may tear down an otherwise usable audio/library window.
                status = DesktopSessionStatus.Unavailable("native_session_unavailable")
                null
            }
            catch (_: Exception) {
                status = DesktopSessionStatus.Unavailable(if (platform.startsWith("linux")) "session_bus_unavailable" else "native_session_unavailable")
                null
            }
            return DesktopMediaSession(backend, parentScope, playback, status)
        }
    }

    private class NativeBackend(
        private val library: NativeSessionLibrary,
        private var handle: Pointer?,
        // Strongly retain the callback until native close has drained all invocations.
        @Suppress("unused") private val callback: NativeSessionCallback,
    ) : Backend {
        @Synchronized override fun update(state: PlaybackState) {
            val pointer = handle ?: return
            val item = state.currentItem
            val controls = (if (item != null) 1 else 0) or
                (if (state.queue.currentIndex >= 0 && (state.queue.currentIndex < state.queue.entries.lastIndex || state.queue.repeat == RepeatMode.ALL)) 2 else 0) or
                (if (item != null) 4 else 0) or (if (state.seekable) 8 else 0)
            val status = when {
                state.isPlaying -> 1
                item != null && state.phase !in setOf(PlaybackPhase.IDLE, PlaybackPhase.ENDED, PlaybackPhase.ERROR) -> 2
                else -> 0
            }
            val artwork = item?.localArtworkPath?.takeIf { runCatching { Files.isRegularFile(java.nio.file.Path.of(it), NOFOLLOW_LINKS) }.getOrDefault(false) }.orEmpty()
            check(library.veyra_session_update(pointer, item?.title.orEmpty(), (item?.artist ?: item?.creator).orEmpty(), item?.album.orEmpty(),
                artwork, state.currentEntryId.orEmpty(), (state.durationMs ?: 0).coerceIn(0, Long.MAX_VALUE / 10_000),
                state.positionMs.coerceIn(0, Long.MAX_VALUE / 10_000), state.effectiveSpeed.toDouble(), status, controls) != 0)
        }

        @Synchronized override fun close() {
            handle?.let(library::veyra_session_close)
            handle = null
        }

        companion object {
            fun create(context: DesktopProcessContext, platform: String, window: Long, command: (PlaybackCommand) -> Unit, outputChanged: () -> Unit): NativeBackend {
                val filename = if (platform.startsWith("windows")) "veyra-session.dll" else "libveyra-session.dylib"
                val bytes = DesktopMediaSession::class.java.getResourceAsStream("/dev/veyra/desktop/native/$platform/$filename")
                    ?.use { input -> input.readNBytes(16 * 1024 * 1024 + 1) } ?: error("Bundled media-session bridge is missing")
                require(bytes.isNotEmpty() && bytes.size <= 16 * 1024 * 1024)
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val directory = context.ownedPath(context.dataDirectory.resolve("runtime/session/$digest"))
                DesktopProcessContext.privateDirectory(directory)
                val file = directory.resolve(filename)
                if (!Files.exists(file, NOFOLLOW_LINKS)) {
                    val stage = Files.createTempFile(directory, ".session-", ".part")
                    try { Files.write(stage, bytes); Files.move(stage, file, ATOMIC_MOVE) }
                    finally { Files.deleteIfExists(stage) }
                }
                require(Files.isRegularFile(file, NOFOLLOW_LINKS) && DesktopNativeRuntime.sha256(file) == digest) { "Bundled media session integrity check failed" }
                val library = Native.load(file.toString(), NativeSessionLibrary::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
                val callback = object : NativeSessionCallback {
                    override fun invoke(identifier: Int, value: Double) {
                        if (!value.isFinite()) return
                        when (identifier) {
                            1 -> command(PlaybackCommand.Resume)
                            2 -> command(PlaybackCommand.Pause)
                            3 -> command(PlaybackCommand.PlayPause)
                            4 -> command(PlaybackCommand.Next)
                            5 -> command(PlaybackCommand.Previous)
                            6 -> command(PlaybackCommand.Seek(value.toLong().coerceAtLeast(0)))
                            7 -> if (value in .5..2.0) command(PlaybackCommand.SetRate(value.toFloat()))
                            8 -> command(PlaybackCommand.SkipBack)
                            9 -> command(PlaybackCommand.SkipForward)
                            10 -> command(PlaybackCommand.Stop)
                            11 -> outputChanged()
                        }
                    }
                }
                val pointer = library.veyra_session_create(window, callback) ?: error("Native session is unavailable")
                return NativeBackend(library, pointer, callback)
            }
        }
    }
}

internal interface NativeSessionCallback : Callback { fun invoke(identifier: Int, value: Double) }
internal interface NativeSessionLibrary : Library {
    fun veyra_session_create(window: Long, callback: NativeSessionCallback): Pointer?
    fun veyra_session_update(session: Pointer, title: String, artist: String, album: String, artwork: String, entry: String,
        durationMs: Long, positionMs: Long, rate: Double, status: Int, capabilities: Int): Int
    fun veyra_session_close(session: Pointer)
}
