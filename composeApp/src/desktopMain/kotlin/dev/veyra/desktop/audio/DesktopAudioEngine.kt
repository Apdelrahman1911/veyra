package dev.veyra.desktop.audio

import dev.veyra.desktop.runtime.DesktopRuntimeConfig
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.PlaybackControlsMode
import dev.veyra.model.VeyraException
import dev.veyra.playback.CrossfadeAudioEngine
import dev.veyra.playback.CrossfadeCancelReason
import dev.veyra.playback.CrossfadeMix
import dev.veyra.playback.EngineEvent
import dev.veyra.playback.EnginePhase
import dev.veyra.playback.InterruptionKind
import dev.veyra.playback.NativeReaderDrainResult
import dev.veyra.playback.NativePlaybackSource
import dev.veyra.playback.PlaybackResource
import dev.veyra.playback.PlaybackSelection
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.source.execution.AcquisitionPlaybackSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One app-lifetime transport and coordinator-facing event stream. Each deck owns its own libmpv
 * handle/thread; this actor is the only writer of deck ownership, gains and promotion state.
 * No native playlist, second session, synthetic position clock, or system-mpv fallback is used.
 */
class DesktopAudioEngine private constructor(
    firstDeck: DesktopAudioDeck,
    private val newDeck: suspend () -> DesktopAudioDeck,
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
    // Source registration/rejected-plan disposal can run on the caller. All sets/owner completion
    // fields below are guarded by gate. Failed records remain strongly held, not Boolean-only.
    private var everOwnedSource = false
    private val ownedSources = linkedSetOf<SourceOwner>()
    // Only in-flight/failed closes are indexed. Successful resources are not kept in a lifetime
    // cache, but handing the same failed resource back to this engine cannot trigger an auto-retry.
    private val resourceCloseOwners = IdentityHashMap<PlaybackResource, SourceOwner>()
    private val retiredDecks = linkedSetOf<Slot>()
    private val cleanupFailed = AtomicBoolean()
    private val nativeSilenceFailed = AtomicBoolean()

    private class SourceOwner(val source: NativePlaybackSource, val resourceAlias: SourceOwner? = null) {
        // The actual native reader, not a generation/phase/mute inference. Source resources
        // cannot be released until this exact deck positively acknowledges destruction.
        var nativeSlot: Slot? = null
        var retired = false
        var completion: CompletableDeferred<Boolean>? = null
    }

    private class Slot(val deck: DesktopAudioDeck) {
        var generation = Long.MIN_VALUE
        var sourceOwner: SourceOwner? = null
        val source: NativePlaybackSource? get() = sourceOwner?.source
        var everPrepared = false
        var disposal: CompletableDeferred<Boolean>? = null
        // null: close has not returned; false: attempted but unacknowledged; true: positive ACK.
        var nativeReleaseAcknowledged: Boolean? = null
        var silenceAcknowledged = true
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
        class Command(val action: suspend () -> Unit, val result: CompletableDeferred<Unit>, val sourceOwner: SourceOwner? = null) : Message
        class Drain(val expectedGeneration: Long, val result: CompletableDeferred<NativeReaderDrainResult>) : Message
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
                                releaseResource(message.sourceOwner)
                                // Only uncertain native silence is a transport-wide containment
                                // problem. A retired A resource failure must not silence live B.
                                if (error is NativeSilenceUnacknowledged) silenceAll()
                            }
                            message.result.completeExceptionally(if (error is NativeSilenceUnacknowledged)
                                VeyraException(AppFailure(FailureCode.INVALID_STATE, "native_audio_silence_unacknowledged")) else error)
                        }
                    is Message.Drain -> {
                        val result = try { drainRetiredReaders(message.expectedGeneration) }
                        catch (_: Throwable) {
                            retainCleanupFailure()
                            NativeReaderDrainResult.Unacknowledged(cleanupFailure().failure)
                        }
                        message.result.complete(result)
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
                    // Includes a rejected invocation registered while shutdown was already
                    // draining. Source closure is single-attempt per transferred ownership.
                    finishResourceClosures(includeUnstarted = true)
                }
                messages.close()
                while (true) {
                    val command = messages.tryReceive().getOrNull() ?: break
                    if (command is Message.Command) {
                        withContext(NonCancellable) { releaseResource(command.sourceOwner) }
                        command.result.completeExceptionally(IllegalStateException("Audio engine is closed"))
                    } else if (command is Message.Drain) {
                        command.result.complete(NativeReaderDrainResult.Unacknowledged(
                            AppFailure(FailureCode.RECOVERY_REQUIRED, "native_audio_owner_closed"),
                        ))
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

    override suspend fun prepare(source: ResolvedPlayable, startPositionMs: Long, generation: Long) =
        prepareInput(NativePlaybackSource.Legacy(source), startPositionMs, generation)

    override suspend fun prepare(source: AcquisitionPlaybackSource, selection: PlaybackSelection, startPositionMs: Long, generation: Long) =
        prepareInput(NativePlaybackSource.Acquired(source, selection), startPositionMs, generation)

    private suspend fun prepareInput(source: NativePlaybackSource, startPositionMs: Long, generation: Long) = submitOwned(source) { owner ->
        if (generation < barrier) { releaseResource(owner); return@submitOwned }
        // An invalid typed presentation is rejected without touching current B. The actor already
        // registered this handoff, so failure uses the same retained close/fence as every source.
        source.validateSelection()
        checkUniqueResourceOwnership(owner)
        checkNativeSilence()
        // A loadfile replacement is not an old-reader release acknowledgement. Never put a second
        // source on an already-loaded handle; retire/destroy/join the whole previous native deck.
        val previous = outgoing.takeIf { it.everPrepared }
        try {
            // Replacing/explicit navigation must never briefly restore a fading-out old track.
            outgoing.deck.pause(outgoing.generation)
            cancelStage(CrossfadeCancelReason.CANCELLED, immediate = true, emitHeldEnd = false)
            if (previous != null) outgoing = Slot(newDeck()).also(::observe)
            barrier = generation
            outgoing.apply {
                this.generation = generation; sourceOwner = owner; everPrepared = true
                music = source.controlsMode == PlaybackControlsMode.MUSIC
                ready = false; durationMs = null; seekable = false; heldEnd = false; heldFailure = null; snapshot = MpvDeckSnapshot()
            }
            synchronized(gate) { owner.nativeSlot = outgoing }
            outgoing.deck.setGain(outgoing.master * userGain)
            outgoing.deck.prepare(DesktopNativeInput.from(source), startPositionMs, generation)
        } catch (error: Throwable) {
            releaseResource(owner)
            throw error
        } finally {
            // The previous object can remain outgoing if allocating the replacement failed.
            // In that case its existing owner is kept until an explicit stop/close can retire it.
            if (previous != null && previous !== outgoing) dispose(previous)
        }
    }

    override suspend fun play(generation: Long) = submit {
        if (current(generation)) {
            checkNativeSilence()
            outgoing.deck.play(generation)
        }
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
        val previous = outgoing
        previous.deck.stop(generation)
        // mpv's stop command acceptance is not a Windows file-release proof. Destroy/join the
        // native handle before acknowledging the shared asset-removal barrier, then recreate idle.
        dispose(previous)
        val idle = Slot(newDeck()).apply { this.generation = generation }
        try { idle.deck.stop(generation) } catch (error: Throwable) {
            dispose(idle)
            throw error
        }
        outgoing = idle
        barrier = generation
        observe(outgoing)
        publish(EngineEvent.StateChanged(generation, EnginePhase.IDLE, false))
        checkCleanup()
    }

    override suspend fun preloadNext(source: ResolvedPlayable, startPositionMs: Long, nextGeneration: Long, generation: Long) =
        preloadInput(NativePlaybackSource.Legacy(source), startPositionMs, nextGeneration, generation)

    override suspend fun preloadNext(source: AcquisitionPlaybackSource, selection: PlaybackSelection, startPositionMs: Long, nextGeneration: Long, generation: Long) =
        preloadInput(NativePlaybackSource.Acquired(source, selection), startPositionMs, nextGeneration, generation)

    private suspend fun preloadInput(source: NativePlaybackSource, startPositionMs: Long, nextGeneration: Long, generation: Long) = submitOwned(source) { owner ->
        if (!current(generation) || nextGeneration <= generation) { releaseResource(owner); return@submitOwned }
        source.validateSelection()
        checkUniqueResourceOwnership(owner)
        checkNativeSilence()
        cancelStage(CrossfadeCancelReason.CANCELLED, immediate = false)
        if (!outgoing.music || source.controlsMode != PlaybackControlsMode.MUSIC) {
            publish(EngineEvent.StageFailed(generation, nextGeneration, AppFailure(FailureCode.INVALID_STATE, "podcasts_do_not_crossfade")))
            releaseResource(owner)
            return@submitOwned
        }
        var staged: Slot? = null
        try {
            staged = Slot(newDeck()).apply {
                this.generation = nextGeneration; sourceOwner = owner; everPrepared = true; music = true
            }
            synchronized(gate) { owner.nativeSlot = staged }
            incoming = staged
            observe(staged)
            staged.deck.setGain(0f)
            staged.deck.prepare(DesktopNativeInput.from(source), startPositionMs, nextGeneration)
        } catch (error: Exception) {
            incoming = null
            staged?.let { dispose(it) }
            releaseResource(owner)
            publish(EngineEvent.StageFailed(generation, nextGeneration, (error as? VeyraException)?.failure
                ?: AppFailure(FailureCode.INVALID_STATE, "crossfade_preload_failed")))
        }
    }

    override suspend fun beginCrossfade(durationMs: Long, nextGeneration: Long, generation: Long) = submit {
        val next = incoming ?: return@submit
        if (!current(generation) || next.generation != nextGeneration || !next.ready || overlap != null) return@submit
        checkNativeSilence()
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
        dispose(previous)
        val actual = outgoing.deck.snapshot()
        outgoing.snapshot = actual
        publish(EngineEvent.StateChanged(nextGeneration, actual.phase, actual.phase == EnginePhase.PLAYING, actual.effectiveSpeed))
        samples.trySend(EngineEvent.Position(nextGeneration, actual.positionMs, actual.durationMs))
        if (actual.ended) publish(EngineEvent.Ended(nextGeneration))
    }

    override suspend fun cancelNext(nextGeneration: Long, generation: Long) = submit {
        if (current(generation) && incoming?.generation == nextGeneration) cancelStage(CrossfadeCancelReason.CANCELLED, immediate = false)
        // Known cleanup-only failures are reported by awaitRetiredReaders, never as Error(B)
        // through a later unrelated cancel/seek/rate command. Unit is not a deletion ACK.
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

    override suspend fun awaitRetiredReaders(expectedGeneration: Long): NativeReaderDrainResult {
        currentCoroutineContext().ensureActive()
        val result = CompletableDeferred<NativeReaderDrainResult>()
        val accepted = synchronized(gate) {
            accepting && messages.trySend(Message.Drain(expectedGeneration, result)).isSuccess
        }
        if (!accepted) return NativeReaderDrainResult.Unacknowledged(
            AppFailure(FailureCode.RECOVERY_REQUIRED, "native_audio_owner_closed"),
        )
        // Cancellation of the caller cannot cancel a queued retirement or manufacture an ACK.
        return result.await()
    }

    override suspend fun close() {
        synchronized(gate) {
            if (accepting) { accepting = false; messages.trySend(Message.Shutdown) }
        }
        withContext(NonCancellable) {
            try { closed.await() } finally {
                // A rejected post-shutdown handoff can still be closing on another coroutine.
                // Join it even when the actor's earlier terminal result was already a failure;
                // a completed `closed` plus a not-yet-set failure flag is not a release receipt.
                finishResourceClosures(includeUnstarted = true)
            }
            checkCleanup()
        }
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
            is EngineEvent.Ended -> { cancelStage(CrossfadeCancelReason.OUTGOING_ENDED, immediate = true, emitHeldEnd = false); releaseResource(slot.sourceOwner) }
            is EngineEvent.Error -> { cancelStage(CrossfadeCancelReason.ERROR, immediate = true, emitHeldEnd = false); releaseResource(slot.sourceOwner) }
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
                // A failed central close is scoped, but if native pause AND destruction fail
                // it is unsafe to restore/continue another audible deck. dispose distinguishes it.
                dispose(next)
                outgoing.deck.setGain(outgoing.master * userGain)
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

    private class NativeSilenceUnacknowledged : Exception()

    private suspend fun dispose(slot: Slot): Unit = withContext(NonCancellable) {
        val existing = synchronized(gate) { slot.disposal }
        if (existing != null) {
            existing.await()
            if (!slot.silenceAcknowledged) throw NativeSilenceUnacknowledged()
            return@withContext
        }
        val completion = CompletableDeferred<Boolean>()
        synchronized(gate) { slot.disposal = completion; retiredDecks += slot }
        slot.collector?.cancelAndJoin()
        // Silence and reader-release acknowledgements are separate. A destroyed handle is silent
        // even if its preceding pause command failed; a paused handle whose close failed remains
        // strongly retained with a release veto but need not interrupt unrelated B controls.
        val paused = runCatching { slot.deck.pause(slot.generation) }.isSuccess
        val nativeClosed = runCatching { slot.deck.close() }.isSuccess
        synchronized(gate) { slot.nativeReleaseAcknowledged = nativeClosed }
        slot.silenceAcknowledged = paused || nativeClosed
        if (!slot.silenceAcknowledged) nativeSilenceFailed.set(true)
        val resourceClosed = releaseResource(slot.sourceOwner)
        val acknowledged = nativeClosed && resourceClosed
        synchronized(gate) {
            if (acknowledged) { retiredDecks -= slot; slot.sourceOwner = null }
        }
        if (!acknowledged) retainCleanupFailure()
        completion.complete(acknowledged)
        if (!paused && !nativeClosed) throw NativeSilenceUnacknowledged()
    }

    /** Exactly one close attempt per transferred source; failed owners remain pinned for life. */
    private suspend fun releaseResource(owner: SourceOwner?): Boolean = withContext(NonCancellable) {
        if (owner == null) return@withContext true
        var start = false
        val completion = synchronized(gate) {
            owner.resourceAlias?.let { leader ->
                // This input was rejected before native handoff. It is another reference to
                // an existing owner, not permission to close B's resource or start a retry.
                owner.retired = true
                ownedSources -= owner
                val existing = leader.completion ?: return@withContext true
                owner.completion = existing
                return@synchronized existing
            }
            val resource = owner.source.resource
            owner.nativeSlot?.let { native ->
                if (resource != null && native.nativeReleaseAcknowledged != true) {
                    if (native.nativeReleaseAcknowledged == null) {
                        // prepare failure/EOF/Error is not destruction. The deck still owns
                        // the source; an explicit replacement/stop/close will retire it.
                        return@withContext false
                    }
                    // Failed destruction is terminal for this owner. Retain BOTH the real
                    // deck and its exact resource without attempting resource.close at all.
                    owner.retired = true
                    return@synchronized owner.completion ?: CompletableDeferred(false).also {
                        owner.completion = it
                        resourceCloseOwners[resource] = owner
                    }
                }
            }
            owner.retired = true
            owner.completion ?: run {
                val prior = resource?.let(resourceCloseOwners::get)
                if (prior != null) {
                    // The indexed leader strongly retains this exact resource and its completion.
                    // Don't accumulate another failed-source record or close attempt for an alias.
                    ownedSources -= owner
                    requireNotNull(prior.completion).also { owner.completion = it }
                } else CompletableDeferred<Boolean>().also {
                    owner.completion = it
                    if (resource != null) resourceCloseOwners[resource] = owner
                    start = true
                }
            }
        }
        if (start) {
            val acknowledged = runCatching { owner.source.resource?.close() }.isSuccess
            if (acknowledged) synchronized(gate) {
                ownedSources -= owner
                owner.source.resource?.let { resourceCloseOwners.remove(it) }
            }
            else retainCleanupFailure()
            completion.complete(acknowledged)
        }
        completion.await()
    }

    private suspend fun finishResourceClosures(includeUnstarted: Boolean = false) {
        // Failed completed records stay retained, but never prevent awaiting another pending close.
        while (true) {
            val pending = synchronized(gate) {
                ownedSources.filter { (includeUnstarted || it.retired) && it.completion?.isCompleted != true &&
                    (it.resourceAlias != null || it.source.resource == null || it.nativeSlot == null ||
                        it.nativeSlot?.nativeReleaseAcknowledged != null) }
            }
            if (pending.isEmpty()) return
            for (owner in pending) releaseResource(owner)
        }
    }

    private fun pristine(): Boolean = synchronized(gate) {
        accepting && barrier == Long.MIN_VALUE && !everOwnedSource && !outgoing.everPrepared &&
            outgoing.sourceOwner == null && incoming == null && overlap == null &&
            retiredDecks.isEmpty() && ownedSources.isEmpty() && !cleanupFailed.get()
    }

    private suspend fun drainRetiredReaders(expectedGeneration: Long): NativeReaderDrainResult {
        if (expectedGeneration < 0L) return NativeReaderDrainResult.Superseded
        if (synchronized(gate) { !accepting }) return ownerClosedResult()
        if (expectedGeneration == 0L && pristine()) {
            val initial = outgoing.deck.awaitRetiredReaders(0L)
            if (initial is NativeReaderDrainResult.Unacknowledged) {
                retainCleanupFailure()
                return NativeReaderDrainResult.Unacknowledged(cleanupFailure().failure)
            }
            if (initial != NativeReaderDrainResult.Drained(0L)) return NativeReaderDrainResult.Superseded
            // A source invocation can register concurrently while the native-owner queue waits.
            // It must not turn a non-pristine engine into an arbitrary epoch-zero ACK.
            synchronized(gate) {
                if (initial == NativeReaderDrainResult.Drained(0L) && pristine()) {
                    barrier = 0L; outgoing.generation = 0L
                }
            }
        }
        if (barrier != expectedGeneration || outgoing.generation != expectedGeneration) {
            return NativeReaderDrainResult.Superseded
        }
        finishResourceClosures()
        val retirements = synchronized(gate) { retiredDecks.mapNotNull { it.disposal } }
        for (retirement in retirements) retirement.await()
        var unacknowledged = false
        for (slot in listOfNotNull(outgoing, incoming)) {
            when (val result = slot.deck.awaitRetiredReaders(slot.generation)) {
                is NativeReaderDrainResult.Drained -> if (result.expectedGeneration != slot.generation) {
                    return NativeReaderDrainResult.Superseded
                }
                NativeReaderDrainResult.Superseded -> return NativeReaderDrainResult.Superseded
                is NativeReaderDrainResult.Unacknowledged -> unacknowledged = true
            }
        }
        finishResourceClosures()
        currentCoroutineContext().ensureActive()
        if (unacknowledged) retainCleanupFailure()
        return synchronized(gate) {
            when {
                // close changes admission on its caller while this actor awaits a lower deck.
                // Linearize the result with that admission state, not just the old load epoch.
                !accepting -> ownerClosedResult()
                barrier != expectedGeneration || outgoing.generation != expectedGeneration -> NativeReaderDrainResult.Superseded
                cleanupFailed.get() -> NativeReaderDrainResult.Unacknowledged(cleanupFailure().failure)
                else -> NativeReaderDrainResult.Drained(expectedGeneration)
            }
        }
    }

    private fun retainCleanupFailure() {
        cleanupFailed.set(true)
        // An exceptional close must not make the app/GC lose failed native handles or sources.
        // Sticky veto deliberately lasts for this process; no implicit retry or URL is exposed.
        synchronized(failedOwnerPins) { failedOwnerPins += this }
    }

    internal fun retainedReadersForTest(): Pair<Int, Int> = synchronized(gate) {
        retiredDecks.size to ownedSources.count { it.retired && it.completion?.isCompleted == true }
    }

    private suspend fun silenceAll() {
        incoming?.let { runCatching { it.deck.pause(it.generation) } }
        runCatching { outgoing.deck.pause(outgoing.generation) }
    }

    private fun cleanupFailure() = VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "playback_resource_cleanup"))
    private fun ownerClosedResult() = NativeReaderDrainResult.Unacknowledged(
        AppFailure(FailureCode.RECOVERY_REQUIRED, "native_audio_owner_closed"),
    )
    private fun checkCleanup() { if (cleanupFailed.get()) throw cleanupFailure() }
    private fun checkNativeSilence() { if (nativeSilenceFailed.get()) throw NativeSilenceUnacknowledged() }
    private fun checkUniqueResourceOwnership(owner: SourceOwner) {
        if (owner.resourceAlias != null) throw VeyraException(
            AppFailure(FailureCode.INVALID_STATE, "native_playback_resource_already_owned"),
        )
    }

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
    private suspend fun submitOwned(source: NativePlaybackSource, action: suspend (SourceOwner) -> Unit) {
        val result = CompletableDeferred<Unit>()
        val owner: SourceOwner
        val accepted = synchronized(gate) {
            owner = SourceOwner(source, source.resource?.let { resource ->
                ownedSources.firstOrNull { it.source.resource === resource }
            })
            everOwnedSource = true
            ownedSources += owner
            (accepting && messages.trySend(Message.Command({ action(owner) }, result, owner)).isSuccess)
                .also { if (!it) owner.retired = true }
        }
        if (!accepted) {
            releaseResource(owner)
            error("Audio engine is closed")
        }
        // Caller cancellation cannot prematurely destroy a resource already accepted by the actor.
        result.await()
    }

    companion object {
        private val failedOwnerPins = linkedSetOf<DesktopAudioEngine>()
        suspend fun create(config: DesktopRuntimeConfig): DesktopAudioEngine =
            DesktopAudioEngine(MpvAudioDeck.create(config)) { MpvAudioDeck.create(config) }
        internal fun createForTest(firstDeck: DesktopAudioDeck, newDeck: suspend () -> DesktopAudioDeck): DesktopAudioEngine =
            DesktopAudioEngine(firstDeck, newDeck)
        internal suspend fun createInternal(config: DesktopRuntimeConfig, validation: Boolean): MpvAudioDeck = MpvAudioDeck.createInternal(config, validation)
        internal fun localPath(uri: String): Path = MpvAudioDeck.localPath(uri)
    }
}
