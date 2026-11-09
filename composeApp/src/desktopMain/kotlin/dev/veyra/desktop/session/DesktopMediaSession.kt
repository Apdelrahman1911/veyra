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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

sealed interface DesktopSessionStatus {
    data class Available(val implementation: String) : DesktopSessionStatus
    data class Unavailable(val reason: String) : DesktopSessionStatus
}

/** Application-lifetime system controls. Unavailable D-Bus/native sessions never crash playback. */
class DesktopMediaSession internal constructor(
    private val backend: Backend?,
    parentScope: CoroutineScope,
    playback: StateFlow<PlaybackState>,
    initialStatus: DesktopSessionStatus,
) {
    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow(initialStatus)
    val status: StateFlow<DesktopSessionStatus> = mutableStatus.asStateFlow()
    private val closing = AtomicBoolean()
    private val closed = CompletableDeferred<Unit>()
    private val updates = scope.launch {
        if (backend != null) playback.collect { state ->
            try { backend.update(state) }
            catch (_: Exception) { mutableStatus.value = DesktopSessionStatus.Unavailable("media_session_disconnected") }
        }
    }

    suspend fun close(): Unit = withContext(NonCancellable) {
        // Return from IO into an active non-cancellable context first. A combined NC+IO
        // switch can lose its terminal result while returning to an already-cancelled caller.
        withContext(Dispatchers.IO) drain@{
            if (!closing.compareAndSet(false, true)) { closed.await(); return@drain }
            var failure: Throwable? = null
            suspend fun release(action: suspend () -> Unit) {
                try { action() } catch (error: Throwable) { if (failure == null) failure = error }
            }
            try {
                // Never release a native backend while its last metadata update is still running.
                release { updates.cancelAndJoin() }
                release { backend?.close() }
            } finally {
                release { scope.coroutineContext[Job]?.cancelAndJoin() }
                // Closing is once-only, but its outcome is sticky for every concurrent/later owner.
                // A swallowed backend failure would let the host publish a false verified success.
                val error = failure
                if (error == null) closed.complete(Unit) else closed.completeExceptionally(error)
            }
            closed.await()
        }
    }

    internal interface Backend : AutoCloseable { fun update(state: PlaybackState) }

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
            onOwned: (DesktopMediaSession) -> Unit,
        ): DesktopMediaSession {
            val platform = DesktopNativeRuntime.platform()
            // Reading an existing window pointer allocates no session backend. If this fails,
            // createOwned still publishes one resource-free, visibly unavailable owner.
            val windowId = if (platform.startsWith("windows")) {
                try {
                    withContext(Dispatchers.Main) {
                        check(window.isDisplayable) { "Native window is not ready" }
                        Pointer.nativeValue(Native.getComponentPointer(window))
                    }
                } catch (_: CancellationException) {
                    throw FactoryCancellation("media_session_initialization_cancelled")
                } catch (_: LinkageError) { null }
                catch (_: Exception) { null }
            } else 0L
            return createOwned(
                parentScope = parentScope,
                playback = playback,
                implementation = when {
                    platform.startsWith("linux") -> "MPRIS2"
                    platform.startsWith("windows") -> "Windows SMTC"
                    else -> "macOS MediaPlayer"
                },
                unavailableReason = if (platform.startsWith("linux")) "session_bus_unavailable" else "native_session_unavailable",
                onOwned = onOwned,
                acquireBackend = {
                    if (platform.startsWith("linux")) {
                        val mpris = LinuxMprisSession.create(command, raise, quit, setVolume)
                        object : Backend {
                            override fun update(state: PlaybackState) = mpris.update(state)
                            override fun close() = mpris.close()
                        }
                    } else {
                        NativeBackend.create(context, platform, checkNotNull(windowId) { "Native window is unavailable" }, command, outputChanged)
                    }
                },
            )
        }

        /** Publish BEFORE allocation, not after a cancellation-sensitive dispatcher return.
         * A host must retain this exact owner even if create throws or its caller is cancelled.
         * The synchronous acquisition seam returns ownership of every backend it allocates;
         * any partial native allocation before it returns remains that backend factory's duty. */
        internal suspend fun createOwned(
            parentScope: CoroutineScope,
            playback: StateFlow<PlaybackState>,
            implementation: String,
            unavailableReason: String,
            onOwned: (DesktopMediaSession) -> Unit,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
            acquireBackend: () -> Backend,
        ): DesktopMediaSession {
            val holder = AcquiringBackend(playback)
            val session = DesktopMediaSession(holder, parentScope, playback,
                DesktopSessionStatus.Unavailable("media_session_not_initialized"))
            try {
                // This cannot strand native state when a registration callback throws: no
                // backend has been requested yet. Also drain the resource-free owned scope.
                onOwned(session)
            } catch (failure: Throwable) {
                session.close()
                if (failure is CancellationException)
                    throw FactoryCancellation("media_session_initialization_cancelled")
                throw FactoryFailure("media_session_owner_registration_failed")
            }
            try {
                withContext(dispatcher) {
                    currentCoroutineContext().ensureActive()
                    holder.acquire(acquireBackend) {
                        session.mutableStatus.value = DesktopSessionStatus.Available(implementation)
                    }
                }
                currentCoroutineContext().ensureActive()
                return session
            } catch (failure: Throwable) {
                session.mutableStatus.value = DesktopSessionStatus.Unavailable(unavailableReason)
                // close() is NonCancellable and joins the metadata collector before the holder.
                // A failed release remains retained/sanitized in that owner; NEVER turn it into
                // a graceful unavailable return or hide it behind a cancellation-only job join.
                session.close()
                when (failure) {
                    is CancellationException -> throw FactoryCancellation("media_session_initialization_cancelled")
                    is FactoryFailure -> throw failure
                    is LinkageError, is Exception -> return session
                    else -> throw FactoryFailure("media_session_initialization_failed")
                }
            }
        }

        private class FactoryFailure(val reason: String) : IllegalStateException(reason)

        /** Preserve cancellation with only a static classification, including across await.
         * The reason field prevents coroutine stack-recovery cloning; sealing the null cause
         * also prevents a constructor-based copy from attaching the original exception. */
        private class FactoryCancellation(val reason: String) : CancellationException(reason) {
            init { initCause(null) }
        }

        /** One pre-registered owner, not a detached resource registry. Admission, attachment,
         * projection and release share this monitor because Backend acquisition is synchronous.
         * A host close either prevents acquisition or waits until its returned backend is owned. */
        private class AcquiringBackend(private val playback: StateFlow<PlaybackState>) : Backend {
            private var backend: Backend? = null
            private var closed = false
            private var cleanupFailure: FactoryFailure? = null

            @Synchronized fun acquire(factory: () -> Backend, attached: () -> Unit) {
                if (closed) throw FactoryFailure("media_session_closed_during_initialization")
                check(backend == null) { "Session backend was already acquired" }
                backend = factory()
                // The collector may have consumed its initial projection while the holder was
                // empty. Populate controls now; no later playback change is needed for setup.
                backend!!.update(playback.value)
                // Publish before releasing the projection lock, so a subsequent collector
                // failure cannot be overwritten by a late optimistic Available assignment.
                attached()
            }

            @Synchronized override fun update(state: PlaybackState) {
                if (closed) return
                try { backend?.update(state) }
                catch (_: Throwable) { throw FactoryFailure("media_session_projection_failed") }
            }

            @Synchronized override fun close() {
                if (closed) {
                    cleanupFailure?.let { throw it }
                    return
                }
                closed = true
                try {
                    backend?.close()
                    backend = null
                } catch (_: Throwable) {
                    // Retain the backend after failure, but never its arbitrary exception text.
                    // The enclosing session's once-only close keeps this outcome sticky.
                    val failure = FactoryFailure("media_session_cleanup_failed")
                    cleanupFailure = failure
                    throw failure
                }
            }
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
            val pointer = handle ?: return
            // ABI2 consumes the native handle only after successful retirement.
            // A failure leaves the inert handle and callback strongly owned;
            // the enclosing session retains the failure and never retries it.
            check(library.veyra_session_close(pointer) == 1) { "Native media-session cleanup is incomplete" }
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
                // Disarmed native callback/delegate tails may remain enqueued by
                // the OS after unregister. Keep their code loaded until process
                // exit, independently of a closed backend's garbage collection.
                val library = sessionLibraries.computeIfAbsent(file.toString()) {
                    Native.load(it, NativeSessionLibrary::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
                }
                check(library.veyra_session_abi_version() == 2) { "Bundled native media-session ABI does not match" }
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
                // Allocate the Kotlin owner before C may return an owned partial
                // initialization. No wrapper allocation follows that handoff.
                val owner = NativeBackend(library, null, callback)
                owner.handle = library.veyra_session_create(window, callback) ?: error("Native session is unavailable")
                return owner
            }
        }
    }
}

internal interface NativeSessionCallback : Callback { fun invoke(identifier: Int, value: Double) }
private val sessionLibraries = ConcurrentHashMap<String, NativeSessionLibrary>()
internal interface NativeSessionLibrary : Library {
    fun veyra_session_abi_version(): Int
    fun veyra_session_create(window: Long, callback: NativeSessionCallback): Pointer?
    fun veyra_session_update(session: Pointer, title: String, artist: String, album: String, artwork: String, entry: String,
        durationMs: Long, positionMs: Long, rate: Double, status: Int, capabilities: Int): Int
    fun veyra_session_close(session: Pointer): Int
}
