package dev.veyra.desktop.audio

import dev.veyra.desktop.runtime.DesktopRuntimeConfig
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.MediaKind
import dev.veyra.model.VeyraException
import dev.veyra.playback.CrossfadeAudioEngine
import dev.veyra.playback.CrossfadeCancelReason
import dev.veyra.playback.CrossfadeMix
import dev.veyra.playback.EngineEvent
import dev.veyra.playback.EnginePhase
import dev.veyra.playback.InterruptionKind
import dev.veyra.playback.ResolvedPlayable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One app-lifetime transport and coordinator-facing event stream. Each deck owns its own libmpv
 * handle/thread; this actor is the only writer of deck ownership, gains and promotion state.
 * No native playlist, second session, synthetic position clock, or system-mpv fallback is used.
 */
class DesktopAudioEngine private constructor(
    private val config: DesktopRuntimeConfig,
    firstDeck: MpvAudioDeck,
) : CrossfadeAudioEngine {
    override val capabilities = firstDeck.capabilities.copy(supportsCrossfade = true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val terminal = Channel<EngineEvent>(Channel.UNLIMITED)
    private val samples = Channel<EngineEvent>(Channel.CONFLATED)
    private val progress = Channel<EngineEvent>(Channel.CONFLATED)
    @OptIn(ExperimentalCoroutinesApi::class)
    override val events: Flow<EngineEvent> = merge(terminal.receiveAsFlow(), samples.receiveAsFlow(), progress.receiveAsFlow())
    private val messages = Channel<Message>(Channel.UNLIMITED)
    private val gate = Any()
    private var accepting = true
    private val closed = CompletableDeferred<Unit>()
    private val tickQueued = AtomicBoolean()
    private var barrier = Long.MIN_VALUE
    private var outgoing = Slot(firstDeck)
    private var incoming: Slot? = null
    private var overlap: Overlap? = null
    private var userGain = 1f
    // A retired source/deck may no longer be current when its release fails. Never let a later
    // successful stop of an unrelated deck turn that failure into permission to delete its files.
    // Atomic because a rejected submitOwned also releases its resource on the caller's context.
    private val cleanupFailed = AtomicBoolean()

    private class Slot(val deck: MpvAudioDeck) {
        var generation = Long.MIN_VALUE
        var source: ResolvedPlayable? = null
        var collector: Job? = null
        var ready = false
        var durationMs: Long? = null
        var seekable = false
        var snapshot = MpvDeckSnapshot()
        var heldEnd = false
        var heldFailure: AppFailure? = null
        var music = true
        val master: Float get() = if (music) CrossfadeMix.MUSIC_GAIN else 1f
    }

    private class Overlap(val durationMs: Long, val incomingStartMs: Long) {
        val requestedNanos = System.nanoTime()
        var outgoingStartMs = 0L
        var started = false
        var fraction = 0f
        var completed = false
        var lastIncomingMs = incomingStartMs
        var lastOutgoingMs = 0L
        var lastIncomingAdvance = requestedNanos
        var lastOutgoingAdvance = requestedNanos
    }

    private sealed interface Message {
        class Command(val action: suspend () -> Unit, val result: CompletableDeferred<Unit>, val source: ResolvedPlayable? = null) : Message
        class Native(val slot: Slot, val event: EngineEvent) : Message
        data object Tick : Message
        data object Shutdown : Message
    }

    init {
        observe(outgoing)
        scope.launch {
            try {
                for (message in messages) when (message) {
                    is Message.Command -> try { message.action(); message.result.complete(Unit) }
                        catch (error: Throwable) {
                            withContext(NonCancellable) {
                                runCatching { releaseResource(message.source) }
                                if (cleanupFailed.get()) silenceAll()
                            }
                            message.result.completeExceptionally(if (cleanupFailed.get()) cleanupFailure() else error)
                        }
                    is Message.Native -> nativeEvent(message.slot, message.event)
                    Message.Tick -> {
                        tickQueued.set(false)
                        try { tick() } catch (_: Exception) {
                            cancelStage(CrossfadeCancelReason.ERROR, immediate = true)
                            outgoing.deck.pause(outgoing.generation)
                            publish(EngineEvent.Error(barrier, AppFailure(FailureCode.INVALID_STATE, "crossfade_native_control_failed")))
                        }
                    }
                    Message.Shutdown -> break
                }
            } catch (_: Throwable) {
                if (barrier != Long.MIN_VALUE) terminal.trySend(EngineEvent.Error(barrier, AppFailure(FailureCode.INVALID_STATE, "desktop_audio_transport_failed")))
            } finally {
                synchronized(gate) { accepting = false }
                withContext(NonCancellable) {
                    // Both outputs become silent before either handle is destroyed.
                    incoming?.let { runCatching { it.deck.pause(it.generation) } }
                    runCatching { outgoing.deck.pause(outgoing.generation) }
                    incoming?.let { runCatching { dispose(it) } }
                    runCatching { dispose(outgoing) }
                }
                messages.close()
                while (true) {
                    val command = messages.tryReceive().getOrNull() ?: break
                    if (command is Message.Command) {
                        withContext(NonCancellable) { runCatching { releaseResource(command.source) } }
                        command.result.completeExceptionally(IllegalStateException("Audio engine is closed"))
                    }
                }
                terminal.close(); samples.close(); progress.close()
                if (cleanupFailed.get()) closed.completeExceptionally(cleanupFailure()) else closed.complete(Unit)
                scope.cancel()
            }
        }
        scope.launch {
            while (isActive) {
                delay(20)
                if (tickQueued.compareAndSet(false, true)) messages.trySend(Message.Tick)
            }
        }
    }

    override suspend fun prepare(source: ResolvedPlayable, startPositionMs: Long, generation: Long) = submitOwned(source) {
        if (generation < barrier) { releaseResource(source); return@submitOwned }
        val previous = outgoing.source
        try {
        // Replacing/explicit navigation must never briefly restore a fading-out old track.
        outgoing.deck.pause(outgoing.generation)
        cancelStage(CrossfadeCancelReason.CANCELLED, immediate = true, emitHeldEnd = false)
        barrier = generation
        outgoing.apply {
            this.generation = generation; this.source = source
            music = source.item?.kind != MediaKind.EPISODE
            ready = false; durationMs = null; seekable = false; heldEnd = false; heldFailure = null; snapshot = MpvDeckSnapshot()
        }
        outgoing.deck.setGain(outgoing.master * userGain)
        outgoing.deck.prepare(source, startPositionMs, generation)
        } catch (error: Throwable) {
            releaseResource(source)
            throw error
        } finally {
            if (previous?.resource !== source.resource) releaseResource(previous)
        }
    }

    override suspend fun play(generation: Long) = submit {
        if (current(generation)) outgoing.deck.play(generation)
    }

    override suspend fun pause(generation: Long) = submit {
        if (!current(generation)) return@submit
        // Urgent silence precedes cleanup; cancellation is forbidden from resuming either deck.
        incoming?.let { it.deck.pause(it.generation) }
        outgoing.deck.pause(generation)
        cancelStage(CrossfadeCancelReason.CANCELLED, immediate = true)
    }

    override suspend fun seek(positionMs: Long, seekSequence: Long, generation: Long) = submit {
        if (!current(generation)) return@submit
        val terminalOutgoing = outgoing.heldEnd || outgoing.snapshot.ended
        cancelStage(CrossfadeCancelReason.CANCELLED, immediate = false, emitHeldEnd = false)
        if (terminalOutgoing) {
            publish(EngineEvent.SeekCompleted(generation, seekSequence, outgoing.snapshot.positionMs, false))
        } else outgoing.deck.seek(positionMs, seekSequence, generation)
    }

    override suspend fun setRate(speed: Float, generation: Long) = submit {
        if (!current(generation)) return@submit
        cancelStage(CrossfadeCancelReason.CANCELLED, immediate = false)
        outgoing.deck.setRate(speed, generation)
    }

    override suspend fun stop(generation: Long) = submit {
        if (generation < barrier) { checkCleanup(); return@submit }
        incoming?.let { it.deck.pause(it.generation) }
        outgoing.deck.pause(outgoing.generation)
        cancelStage(CrossfadeCancelReason.CANCELLED, immediate = true, emitHeldEnd = false)
        barrier = generation
        val previous = outgoing
        previous.deck.stop(generation)
        // mpv's stop command acceptance is not a Windows file-release proof. Destroy/join the
        // native handle before acknowledging the shared asset-removal barrier, then recreate idle.
        dispose(previous)
        outgoing = Slot(MpvAudioDeck.create(config)).apply { this.generation = generation }
        observe(outgoing)
        publish(EngineEvent.StateChanged(generation, EnginePhase.IDLE, false))
        checkCleanup()
    }

    override suspend fun preloadNext(source: ResolvedPlayable, startPositionMs: Long, nextGeneration: Long, generation: Long) = submitOwned(source) {
        if (!current(generation) || nextGeneration <= generation) { releaseResource(source); return@submitOwned }
        cancelStage(CrossfadeCancelReason.CANCELLED, immediate = false)
        if (!outgoing.music || source.item?.kind == MediaKind.EPISODE) {
            publish(EngineEvent.StageFailed(generation, nextGeneration, AppFailure(FailureCode.INVALID_STATE, "podcasts_do_not_crossfade")))
            releaseResource(source)
            return@submitOwned
        }
        var staged: Slot? = null
        try {
            staged = Slot(MpvAudioDeck.create(config)).apply {
                this.generation = nextGeneration; this.source = source; music = true
            }
            incoming = staged
            observe(staged)
            staged.deck.setGain(0f)
            staged.deck.prepare(source, startPositionMs, nextGeneration)
        } catch (error: Exception) {
            incoming = null
            staged?.let { dispose(it) }
            releaseResource(source)
            publish(EngineEvent.StageFailed(generation, nextGeneration, (error as? VeyraException)?.failure
                ?: AppFailure(FailureCode.INVALID_STATE, "crossfade_preload_failed")))
        }
    }

    override suspend fun beginCrossfade(durationMs: Long, nextGeneration: Long, generation: Long) = submit {
        val next = incoming ?: return@submit
        if (!current(generation) || next.generation != nextGeneration || !next.ready || overlap != null) return@submit
        require(durationMs in 1..30_000) { "Invalid crossfade duration" }
        val from = outgoing.deck.snapshot()
        val to = next.deck.snapshot()
        outgoing.snapshot = from; next.snapshot = to
        // Native confirmations, not source estimates: unknown/ended/short inputs stay ordinary.
        val remaining = from.durationMs?.minus(from.positionMs)
        val incomingRemaining = to.durationMs?.minus(to.positionMs)
        if (from.phase != EnginePhase.PLAYING || remaining == null || incomingRemaining == null ||
            remaining < 300 || incomingRemaining < 300 || !from.seekable || !to.seekable) {
            cancelStage(CrossfadeCancelReason.CANCELLED, immediate = false)
            return@submit
        }
        val effective = minOf(durationMs, remaining - 100, incomingRemaining - 100).coerceAtLeast(1)
        overlap = Overlap(effective, to.positionMs)
        next.deck.setGain(0f)
        next.deck.play(nextGeneration)
    }

    override suspend fun confirmCrossfade(nextGeneration: Long, generation: Long) = submit {
        val next = incoming ?: return@submit
        if (!current(generation) || next.generation != nextGeneration || overlap?.completed != true) return@submit
        val previous = outgoing
        outgoing = next; incoming = null; overlap = null
        barrier = nextGeneration
        outgoing.heldEnd = false
        previous.deck.pause(previous.generation)
        dispose(previous)
        val actual = outgoing.deck.snapshot()
        outgoing.snapshot = actual
        publish(EngineEvent.StateChanged(nextGeneration, actual.phase, actual.phase == EnginePhase.PLAYING, actual.effectiveSpeed))
        samples.trySend(EngineEvent.Position(nextGeneration, actual.positionMs, actual.durationMs))
        if (actual.ended) publish(EngineEvent.Ended(nextGeneration))
    }

    override suspend fun cancelNext(nextGeneration: Long, generation: Long) = submit {
        if (current(generation) && incoming?.generation == nextGeneration) cancelStage(CrossfadeCancelReason.CANCELLED, immediate = false)
        checkCleanup()
    }

    /** Route/focus callbacks use the same engine event path as the coordinator; never a second queue. */
    suspend fun outputChanged() = submit {
        incoming?.let { it.deck.pause(it.generation) }
        outgoing.deck.pause(outgoing.generation)
        cancelStage(CrossfadeCancelReason.INTERRUPTED, immediate = true)
        if (barrier != Long.MIN_VALUE) publish(EngineEvent.Interruption(barrier, InterruptionKind.OUTPUT_REMOVED, false))
    }

    suspend fun setGain(gain: Float) = submit {
        require(gain.isFinite() && gain in 0f..1f)
        userGain = gain
        applyGains(overlap?.fraction ?: 0f)
    }

    suspend fun selectOutput(deviceId: String) = submit {
        incoming?.let { it.deck.pause(it.generation) }
        outgoing.deck.pause(outgoing.generation)
        cancelStage(CrossfadeCancelReason.INTERRUPTED, immediate = true)
        outgoing.deck.selectOutput(deviceId)
    }

    suspend fun diagnostics(): Map<String, String?> {
        var result = emptyMap<String, String?>()
        submit {
            result = outgoing.deck.diagnostics() + mapOf("music-master-gain" to CrossfadeMix.MUSIC_GAIN.toString(), "managed-decks" to if (incoming == null) "1" else "2")
            incoming?.let { next ->
                val diagnostics = next.deck.diagnostics()
                result = result + mapOf("incoming-volume" to diagnostics["volume"], "incoming-pause" to diagnostics["pause"], "incoming-position-ms" to next.deck.snapshot().positionMs.toString())
            }
        }
        return result
    }

    override suspend fun close() {
        synchronized(gate) {
            if (accepting) { accepting = false; messages.trySend(Message.Shutdown) }
        }
        withContext(NonCancellable) { closed.await() }
        // A resource handed to an already-closed engine can fail on another coroutine after the
        // actor's terminal result. Repeated close remains a truthful fail-closed release fence.
        checkCleanup()
    }

    private suspend fun tick() {
        val fade = overlap ?: return
        val next = incoming ?: return
        if (fade.completed) return // Logical old ownership is retained until the durable ACK.
        val from = outgoing.deck.snapshot()
        val to = next.deck.snapshot()
        outgoing.snapshot = from; next.snapshot = to
        val now = System.nanoTime()
        if (from.ended) { cancelStage(CrossfadeCancelReason.OUTGOING_ENDED, immediate = true); return }
        if (to.ended) { cancelStage(CrossfadeCancelReason.INCOMING_ENDED, immediate = false); return }
        if (to.phase == EnginePhase.BUFFERING) { cancelStage(CrossfadeCancelReason.INCOMING_STALLED, immediate = false); return }
        if (from.phase != EnginePhase.PLAYING) { cancelStage(CrossfadeCancelReason.INTERRUPTED, immediate = true); return }
        if (!fade.started) {
            if (to.phase == EnginePhase.PLAYING && to.positionMs > fade.incomingStartMs) {
                fade.started = true
                fade.outgoingStartMs = from.positionMs
                fade.lastOutgoingMs = from.positionMs
                fade.lastIncomingAdvance = now; fade.lastOutgoingAdvance = now
                publish(EngineEvent.CrossfadeStarted(barrier, next.generation, fade.durationMs))
            } else if (now - fade.requestedNanos > 2_000_000_000L) cancelStage(CrossfadeCancelReason.INCOMING_STALLED, immediate = false)
            return
        }
        if (to.positionMs > fade.lastIncomingMs) { fade.lastIncomingMs = to.positionMs; fade.lastIncomingAdvance = now }
        if (from.positionMs > fade.lastOutgoingMs) { fade.lastOutgoingMs = from.positionMs; fade.lastOutgoingAdvance = now }
        if (now - fade.lastIncomingAdvance > 500_000_000L) { cancelStage(CrossfadeCancelReason.INCOMING_STALLED, immediate = false); return }
        if (now - fade.lastOutgoingAdvance > 500_000_000L) { cancelStage(CrossfadeCancelReason.INTERRUPTED, immediate = false); return }
        // The slowest actual native media clock determines overlap. Wall time never invents audio.
        val advanced = minOf((from.positionMs - fade.outgoingStartMs) / from.effectiveSpeed,
            (to.positionMs - fade.incomingStartMs) / to.effectiveSpeed)
        fade.fraction = maxOf(fade.fraction, (advanced / fade.durationMs).coerceIn(0f, 1f))
        applyGains(fade.fraction)
        progress.trySend(EngineEvent.CrossfadeProgress(barrier, next.generation, fade.fraction))
        if (fade.fraction >= 1f) {
            fade.completed = true
            publish(EngineEvent.CrossfadeCompleted(barrier, next.generation, to.positionMs, to.durationMs, to.seekable, to.effectiveSpeed))
        }
    }

    private suspend fun nativeEvent(slot: Slot, event: EngineEvent) {
        if (event.generation != slot.generation) return
        if (slot === incoming) {
            when (event) {
                is EngineEvent.Ready -> {
                    slot.ready = true; slot.durationMs = event.durationMs; slot.seekable = event.seekable
                    publish(EngineEvent.StageReady(barrier, slot.generation, event.durationMs, event.seekable))
                }
                is EngineEvent.Error -> {
                    if (overlap == null) {
                        incoming = null; dispose(slot)
                        publish(EngineEvent.StageFailed(barrier, slot.generation, event.failure))
                    } else cancelStage(CrossfadeCancelReason.ERROR, immediate = false)
                }
                is EngineEvent.Ended -> cancelStage(CrossfadeCancelReason.INCOMING_ENDED, immediate = false)
                is EngineEvent.Interruption -> {
                    outgoing.deck.pause(outgoing.generation)
                    cancelStage(CrossfadeCancelReason.INTERRUPTED, immediate = true)
                    publish(event.copy(generation = barrier))
                }
                else -> Unit // An incoming deck cannot publish a second independent playback state.
            }
            return
        }
        if (slot !== outgoing || event.generation != barrier) return
        if (overlap?.completed == true && (event is EngineEvent.Ended || event is EngineEvent.StateChanged || event is EngineEvent.Error)) {
            if (event is EngineEvent.Ended) slot.heldEnd = true
            if (event is EngineEvent.Error) slot.heldFailure = event.failure
            return
        }
        when (event) {
            is EngineEvent.Ready -> { slot.ready = true; slot.durationMs = event.durationMs; slot.seekable = event.seekable }
            is EngineEvent.Ended -> { cancelStage(CrossfadeCancelReason.OUTGOING_ENDED, immediate = true, emitHeldEnd = false); releaseResource(slot.source) }
            is EngineEvent.Error -> { cancelStage(CrossfadeCancelReason.ERROR, immediate = true, emitHeldEnd = false); releaseResource(slot.source) }
            is EngineEvent.Interruption -> {
                outgoing.deck.pause(outgoing.generation)
                cancelStage(CrossfadeCancelReason.INTERRUPTED, immediate = true)
            }
            else -> Unit
        }
        if (event is EngineEvent.Position) samples.trySend(event) else publish(event)
    }

    private suspend fun cancelStage(reason: CrossfadeCancelReason, immediate: Boolean, emitHeldEnd: Boolean = true) {
        val next = incoming ?: return
        val fade = overlap
        // Clear ownership first so queued native callbacks cannot resurrect a cancelled deck.
        incoming = null; overlap = null
        try {
            if (!immediate && fade != null && !outgoing.heldEnd) {
                val startOut = CrossfadeMix.outgoing(fade.fraction)
                val startIn = CrossfadeMix.incoming(fade.fraction)
                // A convex return stays inside the safe sum-gain region, unlike independent boosts.
                repeat(6) { step ->
                    val (a, b) = CrossfadeMix.cancel(startOut, startIn, (step + 1) / 6f)
                    next.deck.setGain(b * userGain)
                    outgoing.deck.setGain(a * userGain)
                    delay(10)
                }
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { next.deck.pause(next.generation) }
                try { dispose(next) } finally { outgoing.deck.setGain(outgoing.master * userGain) }
            }
        }
        publish(EngineEvent.CrossfadeCancelled(barrier, next.generation, reason))
        if (outgoing.heldEnd) {
            outgoing.heldEnd = false
            if (emitHeldEnd) publish(EngineEvent.Ended(barrier))
        }
        outgoing.heldFailure?.let {
            outgoing.heldFailure = null
            publish(EngineEvent.Error(barrier, it))
        }
    }

    private suspend fun applyGains(fraction: Float) {
        val next = incoming
        if (next == null || overlap == null) outgoing.deck.setGain(outgoing.master * userGain)
        else {
            // Lower outgoing before raising incoming: sequential native calls cannot overshoot.
            outgoing.deck.setGain(CrossfadeMix.outgoing(fraction) * userGain)
            next.deck.setGain(CrossfadeMix.incoming(fraction) * userGain)
        }
    }

    private fun observe(slot: Slot) {
        slot.collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            slot.deck.events.collect { messages.send(Message.Native(slot, it)) }
        }
    }

    private suspend fun dispose(slot: Slot) = withContext(NonCancellable) {
        slot.collector?.cancel()
        var failed = false
        try { slot.deck.close() } catch (_: Throwable) { cleanupFailed.set(true); failed = true }
        try { releaseResource(slot.source); slot.source = null } catch (_: Throwable) { failed = true }
        if (failed) throw cleanupFailure()
    }

    private suspend fun releaseResource(source: ResolvedPlayable?) = withContext(NonCancellable) {
        try { source?.resource?.close() }
        catch (_: Throwable) {
            cleanupFailed.set(true)
            // Do not retain a bridge URL or native exception text in the public error.
            throw cleanupFailure()
        }
    }

    private suspend fun silenceAll() {
        incoming?.let { runCatching { it.deck.pause(it.generation) } }
        runCatching { outgoing.deck.pause(outgoing.generation) }
    }

    private fun cleanupFailure() = VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "playback_resource_cleanup"))
    private fun checkCleanup() { if (cleanupFailed.get()) throw cleanupFailure() }

    private fun current(generation: Long) = barrier == generation && outgoing.generation == generation
    private fun publish(event: EngineEvent) { terminal.trySend(event).getOrThrow() }
    private suspend fun submit(action: suspend () -> Unit) {
        val result = CompletableDeferred<Unit>()
        synchronized(gate) {
            check(accepting) { "Audio engine is closed" }
            messages.trySend(Message.Command(action, result)).getOrThrow()
        }
        result.await()
    }

    /** Invocation transfers the ephemeral resource even when this engine has already closed. */
    private suspend fun submitOwned(source: ResolvedPlayable, action: suspend () -> Unit) {
        val result = CompletableDeferred<Unit>()
        val accepted = synchronized(gate) { accepting && messages.trySend(Message.Command(action, result, source)).isSuccess }
        if (!accepted) {
            releaseResource(source)
            error("Audio engine is closed")
        }
        // Caller cancellation cannot prematurely destroy a resource already accepted by the actor.
        result.await()
    }

    companion object {
        suspend fun create(config: DesktopRuntimeConfig): DesktopAudioEngine = DesktopAudioEngine(config, MpvAudioDeck.create(config))
        internal suspend fun createInternal(config: DesktopRuntimeConfig, validation: Boolean): MpvAudioDeck = MpvAudioDeck.createInternal(config, validation)
        internal fun localPath(uri: String): Path = MpvAudioDeck.localPath(uri)
    }
}
