@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.platform.audio

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.MediaKind
import dev.veyra.model.StreamTransport
import dev.veyra.model.VeyraException
import dev.veyra.playback.CrossfadeAudioEngine
import dev.veyra.playback.CrossfadeCancelReason
import dev.veyra.playback.CrossfadeMix
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.EngineEvent
import dev.veyra.playback.EnginePhase
import dev.veyra.playback.InterruptionKind
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.source.network.PublicUrlPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFAudio.*
import platform.AVFoundation.*
import platform.CoreMedia.*
import platform.Foundation.*
import platform.QuartzCore.CACurrentMediaTime
import platform.darwin.NSObjectProtocol

/** One app transport, two managed AVPlayer decks, one audio session, no native-owned playlist. */
class IosAudioEngine : CrossfadeAudioEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controls = Channel<EngineEvent>(Channel.UNLIMITED)
    private val positions = MutableStateFlow<EngineEvent.Position?>(null)
    override val events: Flow<EngineEvent> = merge(controls.receiveAsFlow(), positions.filterNotNull())
    override val capabilities = EngineCapabilities(
        transports = setOf(StreamTransport.PROGRESSIVE),
        mimeTypes = setOf("audio/mpeg", "audio/mp4", "audio/aac", "audio/wav", "audio/x-wav", "audio/flac"),
        codecs = setOf("mp3", "aac", "mp4a", "alac", "flac", "pcm"),
        minSpeed = 0.5f, maxSpeed = 2f, supportsRequestHeaders = false, supportsCrossfade = true,
    )
    private var players = List(2) { makePlayer() }
    private var session = AVAudioSession.sharedInstance()
    private val sessionObservers = mutableListOf<NSObjectProtocol>()
    private var latestGeneration = -1L
    private var preparationRevision = 0L
    private var active: Deck? = null
    private var staged: Deck? = null
    private var fade: Fade? = null
    private var retiring: Deck? = null
    private var restoration: Job? = null
    private var interruptionGeneration: Long? = null
    private var interrupted = false
    private var closed = false
    private val resourceClosures = mutableListOf<Deferred<Unit>>()
    private var resourceCleanupFailed = false

    internal val nativeDeckVolumes: List<Float> get() = players.map { it.volume }
    internal val nativeDecksPlaying: List<Boolean> get() = players.map(::isPlaying)

    private class Deck(val generation: Long, val source: ResolvedPlayable, val player: AVPlayer,
        val item: AVPlayerItem, val startPositionMs: Long) {
        val observers = mutableListOf<NSObjectProtocol>()
        var preparingInitialSeek = false
        var ready = false
        var resourceReleased = false
        var ended = false
        var failed = false
        var requestedRate = 1f
        var confirmedSeekSequence = 0L
        var pendingSeekSequence: Long? = null
        var suppressNaturalEnd = false
        var lastState: EngineEvent.StateChanged? = null
        var lastReady: EngineEvent.Ready? = null
        var lastStageReady: EngineEvent.StageReady? = null
    }
    private class Fade(val outgoing: Deck, val incoming: Deck) {
        var job: Job? = null
        var started = false
        var awaitingConfirmation = false
        var pendingFailure: AppFailure? = null
        var pendingOutgoingFailure: AppFailure? = null
        var pendingReset = false
        val pendingInterruptions = mutableListOf<EngineEvent.Interruption>()
    }

    init {
        check(NSThread.isMainThread) { "Create the iOS audio owner on Main." }
        installSessionObservers()
        scope.launch {
            while (isActive) {
                active?.let(::sampleNativeState)
                staged?.let(::sampleNativeState)
                delay(250)
            }
        }
    }

    override suspend fun prepare(source: ResolvedPlayable, startPositionMs: Long, generation: Long) {
        var handedToDeck = false
        try {
            onMain {
                if (closed || generation < latestGeneration) return@onMain
                val revision = ++preparationRevision
                val url = checkedUrl(source)
                cancelStage(CrossfadeCancelReason.CANCELLED, smooth = true)?.join()
                if (closed || generation < latestGeneration || revision != preparationRevision) return@onMain
                clearRetiring()
                active?.let(::clearDeck)
                active = null
                latestGeneration = generation
                positions.value = null
                configureSession(source.item?.kind ?: MediaKind.MUSIC)
                val deck = createDeck(players.first(), source, url, startPositionMs, generation)
                active = deck
                handedToDeck = true
                try {
                    deck.player.volume = baseGain(deck)
                    emit(EngineEvent.StateChanged(generation, EnginePhase.PREPARING, false))
                    deck.player.replaceCurrentItemWithPlayerItem(deck.item)
                } catch (error: Exception) {
                    clearDeck(deck)
                    active = null
                    throw error
                }
            }
        } finally {
            if (!handedToDeck) withContext(NonCancellable) { source.resource?.close() }
        }
    }

    override suspend fun play(generation: Long): Unit = onMain {
        val deck = current(generation) ?: return@onMain
        if (interrupted) {
            interruptionGeneration = generation
            emit(EngineEvent.Interruption(generation, InterruptionKind.TRANSIENT_BEGAN))
            return@onMain
        }
        if (!deck.ready) return@onMain
        requireRate(deck, deck.requestedRate)
        if (!session.setActive(true, error = null)) {
            emit(EngineEvent.Interruption(generation, InterruptionKind.PERMANENT))
            throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "audio_session_activation"))
        }
        deck.player.defaultRate = deck.requestedRate
        deck.player.play()
        publishState(deck)
    }

    override suspend fun pause(generation: Long): Unit = onMain {
        val deck = current(generation) ?: return@onMain
        deck.player.pause()
        staged?.player?.pause()
        cancelStage(CrossfadeCancelReason.INTERRUPTED)
        clearRetiring()
        deck.player.volume = baseGain(deck)
        samplePosition(deck)
        publishState(deck)
        if (!interrupted) deactivateSession()
    }

    override suspend fun seek(positionMs: Long, seekSequence: Long, generation: Long): Unit = onMain {
        val deck = current(generation) ?: return@onMain
        cancelStage(CrossfadeCancelReason.CANCELLED, smooth = true, reportOutgoingEnd = false)?.join()
        if (current(generation) !== deck) return@onMain
        if (!deck.ready || deck.item.seekableTimeRanges.isEmpty()) {
            emit(EngineEvent.SeekCompleted(generation, seekSequence, nativePositionMs(deck), false))
            return@onMain
        }
        val target = nativeDurationMs(deck)?.let { positionMs.coerceIn(0, it) } ?: positionMs.coerceAtLeast(0)
        deck.pendingSeekSequence = seekSequence
        deck.ended = false
        deck.suppressNaturalEnd = true
        deck.player.seekToTime(CMTimeMake(target, 1000), toleranceBefore = kCMTimeZero,
            toleranceAfter = kCMTimeZero) { finished ->
            // AVFoundation does not promise our coroutine/main confinement for completion handlers.
            scope.launch {
                if (isCurrent(deck) && deck.pendingSeekSequence == seekSequence) {
                    deck.pendingSeekSequence = null
                    if (finished) deck.confirmedSeekSequence = seekSequence
                    val position = nativePositionMs(deck)
                    deck.suppressNaturalEnd = nativeDurationMs(deck)?.let { target >= it - 1 } ?: false
                    emit(EngineEvent.SeekCompleted(generation, seekSequence, position, finished))
                    if (finished && deck.suppressNaturalEnd) {
                        deck.ended = true
                        deck.player.pause()
                        publishState(deck) // No natural Ended event for scrubbing to the end.
                    }
                    samplePosition(deck)
                }
            }
        }
    }

    override suspend fun setRate(speed: Float, generation: Long): Unit = onMain {
        val deck = current(generation) ?: return@onMain
        requireRate(deck, speed)
        if (deck.requestedRate != speed) cancelStage(CrossfadeCancelReason.CANCELLED, smooth = true)?.join()
        if (current(generation) !== deck) return@onMain
        deck.requestedRate = speed
        deck.player.defaultRate = speed
        // Setting rate while paused would start audio. A preference change must never do so.
        if (deck.player.rate > 0f) deck.player.rate = speed
        publishState(deck)
    }

    override suspend fun preloadNext(source: ResolvedPlayable, startPositionMs: Long, nextGeneration: Long, generation: Long) {
        var handedToDeck = false
        try {
            onMain {
                val outgoing = current(generation) ?: return@onMain
                if (nextGeneration <= generation || source.item?.kind == MediaKind.EPISODE ||
                    outgoing.source.item?.kind == MediaKind.EPISODE) return@onMain
                if (staged?.generation == nextGeneration) {
                    handedToDeck = staged?.source?.resource === source.resource
                    return@onMain
                }
                val url = try { checkedUrl(source) } catch (failure: VeyraException) {
                    emit(EngineEvent.StageFailed(generation, nextGeneration, failure.failure))
                    return@onMain
                }
                cancelStage(CrossfadeCancelReason.CANCELLED, smooth = true)?.join()
                if (current(generation) !== outgoing) return@onMain
                clearRetiring()
                val deck = createDeck(players.first { it !== outgoing.player }, source, url, startPositionMs, nextGeneration)
                deck.requestedRate = outgoing.requestedRate
                deck.player.defaultRate = outgoing.requestedRate
                deck.player.volume = 0f
                staged = deck
                handedToDeck = true
                try { deck.player.replaceCurrentItemWithPlayerItem(deck.item) }
                catch (_: Exception) {
                    clearDeck(deck)
                    staged = null
                    emit(EngineEvent.StageFailed(generation, nextGeneration,
                        AppFailure(FailureCode.UNSUPPORTED_SOURCE, "native_stage_initialization")))
                }
            }
        } finally {
            if (!handedToDeck) withContext(NonCancellable) { source.resource?.close() }
        }
    }

    override suspend fun beginCrossfade(durationMs: Long, nextGeneration: Long, generation: Long): Unit = onMain {
        val outgoing = current(generation) ?: return@onMain
        val incoming = staged?.takeIf { it.generation == nextGeneration && it.ready && !it.failed } ?: return@onMain
        if (fade != null) return@onMain
        if (durationMs !in 100..30_000 || interrupted || !isPlaying(outgoing.player) || nativeDurationMs(outgoing) == null || nativeDurationMs(incoming) == null) {
            cancelStage(CrossfadeCancelReason.CANCELLED)
            return@onMain
        }
        val operation = Fade(outgoing, incoming)
        fade = operation
        incoming.player.volume = 0f
        incoming.requestedRate = outgoing.requestedRate
        incoming.player.defaultRate = outgoing.requestedRate
        incoming.player.play()
        operation.job = scope.launch {
            val waitStarted = CACurrentMediaTime()
            while (fade === operation && !isPlaying(incoming.player)) {
                if (incoming.failed || incoming.ended || CACurrentMediaTime() - waitStarted > 1.5) {
                    cancelStage(CrossfadeCancelReason.INCOMING_STALLED, smooth = true)
                    return@launch
                }
                delay(20)
            }
            if (fade !== operation) return@launch
            val speed = outgoing.requestedRate.coerceAtLeast(0.5f)
            val outgoingDuration = nativeDurationMs(outgoing)
            val incomingDuration = nativeDurationMs(incoming)
            if (outgoingDuration == null || incomingDuration == null) {
                cancelStage(CrossfadeCancelReason.CANCELLED)
                return@launch
            }
            val remaining = ((outgoingDuration - nativePositionMs(outgoing)) / speed).toLong()
            val nextRemaining = ((incomingDuration - nativePositionMs(incoming)) / speed).toLong()
            val length = minOf(durationMs, remaining, nextRemaining)
            if (length < 100 || !isPlaying(outgoing.player)) {
                cancelStage(CrossfadeCancelReason.OUTGOING_ENDED)
                return@launch
            }
            operation.started = true
            emit(EngineEvent.CrossfadeStarted(generation, nextGeneration, length))
            val began = CACurrentMediaTime()
            var lastProgress = -1L
            while (fade === operation) {
                if (!isPlaying(incoming.player)) {
                    cancelStage(CrossfadeCancelReason.INCOMING_STALLED, smooth = true)
                    return@launch
                }
                if (!isPlaying(outgoing.player)) {
                    if (outgoing.ended) completeFade(operation) else cancelStage(CrossfadeCancelReason.INTERRUPTED)
                    return@launch
                }
                val elapsed = ((CACurrentMediaTime() - began) * 1000).toLong()
                val fraction = (elapsed.toDouble() / length).coerceIn(0.0, 1.0)
                outgoing.player.volume = CrossfadeMix.outgoing(fraction.toFloat())
                incoming.player.volume = CrossfadeMix.incoming(fraction.toFloat())
                if (elapsed / 100 != lastProgress) {
                    lastProgress = elapsed / 100
                    emit(EngineEvent.CrossfadeProgress(generation, nextGeneration, fraction.toFloat()))
                }
                if (fraction >= 1.0) {
                    completeFade(operation)
                    return@launch
                }
                delay(20)
            }
        }
    }

    override suspend fun cancelNext(nextGeneration: Long, generation: Long): Unit = onMain {
        if (current(generation) != null && staged?.generation == nextGeneration) {
            cancelStage(CrossfadeCancelReason.CANCELLED, smooth = true)?.join()
        } else if (retiring?.generation == nextGeneration) {
            restoration?.join()
        }
        finishResourceClosures()
    }

    override suspend fun confirmCrossfade(nextGeneration: Long, generation: Long): Unit = onMain {
        val operation = fade?.takeIf { it.awaitingConfirmation && it.outgoing.generation == generation &&
            it.incoming.generation == nextGeneration && current(generation) === it.outgoing } ?: return@onMain
        fade = null
        val incoming = operation.incoming
        staged = null
        clearDeck(operation.outgoing)
        active = incoming
        latestGeneration = nextGeneration
        if (interruptionGeneration == generation) interruptionGeneration = nextGeneration
        incoming.player.volume = baseGain(incoming)
        if (operation.pendingReset) resetNativePlayers() else {
            incoming.lastReady = EngineEvent.Ready(nextGeneration, nativeDurationMs(incoming), incoming.item.seekableTimeRanges.isNotEmpty())
            publishState(incoming)
            samplePosition(incoming)
        }
        operation.pendingInterruptions.forEach { emit(it.copy(generation = nextGeneration)) }
        if (!operation.pendingReset) {
            val failure = operation.pendingFailure
            if (failure != null) emit(EngineEvent.Error(nextGeneration, failure))
            else if (incoming.ended) emit(EngineEvent.Ended(nextGeneration))
        }
    }

    override suspend fun stop(generation: Long): Unit = onMain {
        if (closed) { finishResourceClosures(); return@onMain }
        if (generation < latestGeneration) return@onMain
        preparationRevision++
        active?.player?.pause()
        staged?.player?.pause()
        cancelStage(CrossfadeCancelReason.CANCELLED)
        clearRetiring()
        active?.let(::clearDeck)
        active = null
        latestGeneration = generation
        positions.value = null
        interruptionGeneration = null
        deactivateSession()
        emit(EngineEvent.StateChanged(generation, EnginePhase.IDLE, false))
        finishResourceClosures()
    }

    override suspend fun close(): Unit = onMain {
        if (closed) { finishResourceClosures(); return@onMain }
        preparationRevision++
        active?.player?.pause()
        staged?.player?.pause()
        cancelStage(CrossfadeCancelReason.CANCELLED)
        clearRetiring()
        active?.let(::clearDeck)
        active = null
        closed = true
        sessionObservers.forEach(NSNotificationCenter.defaultCenter::removeObserver)
        sessionObservers.clear()
        deactivateSession()
        positions.value = null
        try { finishResourceClosures() }
        finally { scope.cancel(); controls.close() }
    }

    private fun createDeck(player: AVPlayer, source: ResolvedPlayable, url: NSURL, startPositionMs: Long, generation: Long): Deck {
        player.pause()
        val asset = AVURLAsset.URLAssetWithURL(url, options = null)
        val item = AVPlayerItem.playerItemWithAsset(asset)
        item.audioTimePitchAlgorithm = AVAudioTimePitchAlgorithmTimeDomain
        val deck = Deck(generation, source, player, item, startPositionMs.coerceAtLeast(0))
        val center = NSNotificationCenter.defaultCenter
        try {
            deck.observers += center.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification,
                `object` = item, queue = NSOperationQueue.mainQueue) {
                if (managed(deck) && !deck.ended) {
                    if (deck.pendingSeekSequence != null) return@addObserverForName
                    // Ignore a queued pre-seek EOF notification when the current native position moved back.
                    val duration = nativeDurationMs(deck)
                    if (duration != null && duration - nativePositionMs(deck) > 500) return@addObserverForName
                    deck.ended = true
                    if (fade?.awaitingConfirmation == true) return@addObserverForName
                    if (deck === staged) {
                        cancelStage(CrossfadeCancelReason.INCOMING_ENDED, smooth = true)
                        return@addObserverForName
                    }
                    val operation = fade
                    if (operation != null && operation.started && isPlaying(operation.incoming.player)) {
                        completeFade(operation)
                        return@addObserverForName
                    }
                    cancelStage(CrossfadeCancelReason.OUTGOING_ENDED)
                    samplePosition(deck)
                    if (!deck.suppressNaturalEnd) emit(EngineEvent.Ended(deck.generation))
                    publishState(deck)
                }
            }
            deck.observers += center.addObserverForName(AVPlayerItemFailedToPlayToEndTimeNotification,
                `object` = item, queue = NSOperationQueue.mainQueue) {
                if (managed(deck)) fail(deck, FailureCode.UNSUPPORTED_SOURCE, "native_audio_failed_to_end")
            }
        } catch (error: Exception) {
            deck.observers.forEach(center::removeObserver)
            deck.observers.clear()
            throw error // The caller still owns/releases source.resource before deck handover.
        }
        return deck
    }

    private fun sampleNativeState(deck: Deck) {
        if (!managed(deck) || fade?.pendingReset == true) return
        if (deck.item.status == AVPlayerItemStatusFailed) {
            fail(deck, if (deck.source.isLocal) FailureCode.CORRUPT_MEDIA else FailureCode.UNSUPPORTED_SOURCE,
                "native_audio_${deck.item.error?.code ?: 0}")
            return
        }
        if (!deck.failed && deck.item.status == AVPlayerItemStatusReadyToPlay && !deck.ready && !deck.preparingInitialSeek) {
            if (deck.startPositionMs > 0) {
                deck.preparingInitialSeek = true
                val target = nativeDurationMs(deck)?.let { deck.startPositionMs.coerceAtMost(it) } ?: deck.startPositionMs
                deck.suppressNaturalEnd = nativeDurationMs(deck)?.let { target >= it - 1 } ?: false
                deck.player.seekToTime(CMTimeMake(target, 1000), toleranceBefore = kCMTimeZero,
                    toleranceAfter = kCMTimeZero) { finished ->
                    scope.launch {
                        if (managed(deck)) {
                            deck.preparingInitialSeek = false
                            if (finished) markReady(deck)
                            else fail(deck, FailureCode.UNSUPPORTED_SOURCE, "initial_seek_failed")
                        }
                    }
                }
            } else markReady(deck)
        }
        if (deck.ready) {
            markReady(deck)
            if (deck === active) samplePosition(deck)
        }
        if (deck === active) publishState(deck)
    }

    private fun markReady(deck: Deck) {
        if (!managed(deck) || deck.failed) return
        deck.ready = true
        val duration = nativeDurationMs(deck)
        val seekable = deck.item.seekableTimeRanges.isNotEmpty()
        if (deck === staged) {
            val origin = active?.generation ?: return
            val ready = EngineEvent.StageReady(origin, deck.generation, duration, seekable)
            if (deck.lastStageReady != ready) { deck.lastStageReady = ready; emit(ready) }
        } else {
            val ready = EngineEvent.Ready(deck.generation, duration, seekable)
            if (deck.lastReady != ready) { deck.lastReady = ready; emit(ready) }
        }
    }

    private fun publishState(deck: Deck) {
        if (!isCurrent(deck) || fade?.awaitingConfirmation == true) return
        val playing = isPlaying(deck.player)
        val phase = when {
            deck.failed -> EnginePhase.ERROR
            deck.ended -> EnginePhase.ENDED
            !deck.ready -> EnginePhase.PREPARING
            playing -> EnginePhase.PLAYING
            deck.player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate -> EnginePhase.BUFFERING
            else -> EnginePhase.PAUSED
        }
        val state = EngineEvent.StateChanged(deck.generation, phase, playing, if (playing) deck.player.rate else 0f)
        if (deck.lastState != state) { deck.lastState = state; emit(state) }
    }
    private fun samplePosition(deck: Deck) {
        if (!isCurrent(deck)) return
        val buffered = deck.item.loadedTimeRanges.mapNotNull { value ->
            val range = value as? NSValue ?: return@mapNotNull null
            milliseconds(CMTimeGetSeconds(CMTimeRangeGetEnd(range.CMTimeRangeValue)))
        }.maxOrNull()
        positions.value = EngineEvent.Position(deck.generation, nativePositionMs(deck), nativeDurationMs(deck), buffered, deck.confirmedSeekSequence)
    }

    private fun completeFade(operation: Fade) {
        if (fade !== operation || operation.awaitingConfirmation || active !== operation.outgoing || staged !== operation.incoming) return
        operation.awaitingConfirmation = true
        operation.job?.cancel()
        val incoming = operation.incoming
        operation.outgoing.player.volume = 0f
        incoming.player.volume = baseGain(incoming)
        // Do not orphan incoming audio if an old-generation Pause/Seek is already queued.
        // Logical promotion happens only after the durable controller transaction ACKs.
        emit(EngineEvent.CrossfadeCompleted(operation.outgoing.generation, incoming.generation,
            nativePositionMs(incoming), nativeDurationMs(incoming), incoming.item.seekableTimeRanges.isNotEmpty(),
            incoming.requestedRate, isPlaying(incoming.player)))
    }
    private fun cancelStage(reason: CrossfadeCancelReason, smooth: Boolean = false, reportOutgoingEnd: Boolean = true): Job? {
        val incoming = staged ?: return restoration
        val origin = active?.generation ?: latestGeneration
        val outgoing = active
        val operation = fade
        fade = null
        operation?.job?.cancel()
        staged = null
        clearRetiring()
        val shouldRamp = smooth && operation?.pendingReset != true && outgoing != null && isPlaying(outgoing.player) &&
            (incoming.player.volume > 0f || outgoing.player.volume < baseGain(outgoing))
        if (shouldRamp) {
            val old = outgoing!!
            retiring = incoming
            val startOut = old.player.volume
            val startIn = incoming.player.volume
            restoration = scope.launch {
                val began = CACurrentMediaTime()
                while (retiring === incoming && active === old) {
                    val t = ((CACurrentMediaTime() - began) / 0.06).toFloat().coerceIn(0f, 1f)
                    val gains = CrossfadeMix.cancel(startOut, startIn, t)
                    incoming.player.volume = gains.second
                    old.player.volume = gains.first
                    if (t >= 1f) break
                    delay(10)
                }
                if (retiring === incoming) {
                    retiring = null
                    clearDeck(incoming)
                    restoration = null
                }
            }
        } else {
            clearDeck(incoming)
            outgoing?.let { it.player.volume = baseGain(it) }
        }
        if (operation?.pendingReset == true) resetNativePlayers()
        emit(EngineEvent.CrossfadeCancelled(origin, incoming.generation, reason))
        operation?.pendingInterruptions?.forEach(::emit)
        operation?.pendingOutgoingFailure?.let { emit(EngineEvent.Error(origin, it)) }
        if (reportOutgoingEnd && operation?.awaitingConfirmation == true && operation.outgoing.ended &&
            operation.pendingOutgoingFailure == null && !operation.pendingReset) emit(EngineEvent.Ended(origin))
        return restoration
    }
    private fun clearRetiring() {
        restoration?.cancel()
        restoration = null
        retiring?.let(::clearDeck)
        retiring = null
    }
    private fun pauseForInterruption() {
        active?.player?.pause()
        staged?.player?.pause()
        clearRetiring()
        if (fade?.awaitingConfirmation != true) cancelStage(CrossfadeCancelReason.INTERRUPTED)
        if (fade?.awaitingConfirmation != true) active?.let { it.player.volume = baseGain(it) }
    }
    private fun emitInterruption(event: EngineEvent.Interruption) {
        val pending = fade?.takeIf { it.awaitingConfirmation && it.outgoing.generation == event.generation }
        if (pending != null) pending.pendingInterruptions += event else emit(event)
    }
    private fun clearDeck(deck: Deck) {
        if (deck.resourceReleased) return
        deck.resourceReleased = true
        val detached = runCatching {
            deck.observers.forEach(NSNotificationCenter.defaultCenter::removeObserver)
            deck.observers.clear()
            deck.player.pause()
            deck.player.replaceCurrentItemWithPlayerItem(null)
            deck.player.volume = baseGain(deck)
        }
        if (deck.source.resource != null || detached.isFailure) {
            val closing = scope.async(start = CoroutineStart.LAZY) {
                // AVPlayer has no public per-file-descriptor close acknowledgement. Item removal
                // precedes central resource release; physical native deletion tests remain a gate.
                withContext(NonCancellable) { deck.source.resource?.close() }
                if (detached.isFailure) throw VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "native_item_detach"))
            }
            resourceClosures += closing
            closing.invokeOnCompletion { error ->
                if (error == null) scope.launch { resourceClosures.remove(closing) }
            }
            closing.start()
        }
    }
    private suspend fun finishResourceClosures() = withContext(NonCancellable) {
        while (resourceClosures.isNotEmpty()) {
            val pending = resourceClosures.toList()
            pending.forEach { closing ->
                try { closing.await() } catch (_: Throwable) { resourceCleanupFailed = true }
                resourceClosures.remove(closing)
            }
        }
        if (resourceCleanupFailed) throw VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "playback_resource_cleanup"))
    }

    private fun installSessionObservers() {
        val center = NSNotificationCenter.defaultCenter
        sessionObservers += center.addObserverForName(AVAudioSessionInterruptionNotification,
            `object` = null, queue = NSOperationQueue.mainQueue) { notification ->
            val type = (notification?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.longLongValue
            if (type == AVAudioSessionInterruptionTypeBegan.toLong()) {
                interrupted = true
                pauseForInterruption()
                active?.let { deck ->
                    interruptionGeneration = deck.generation
                    emitInterruption(EngineEvent.Interruption(deck.generation, InterruptionKind.TRANSIENT_BEGAN))
                    publishState(deck)
                }
            } else if (type == AVAudioSessionInterruptionTypeEnded.toLong()) {
                interrupted = false
                val origin = interruptionGeneration
                interruptionGeneration = null
                val options = (notification?.userInfo?.get(AVAudioSessionInterruptionOptionKey) as? NSNumber)?.longLongValue ?: 0L
                if (origin != null) emitInterruption(EngineEvent.Interruption(origin, InterruptionKind.TRANSIENT_ENDED,
                    mayResume = options and AVAudioSessionInterruptionOptionShouldResume.toLong() != 0L))
            }
        }
        sessionObservers += center.addObserverForName(AVAudioSessionRouteChangeNotification,
            `object` = null, queue = NSOperationQueue.mainQueue) { notification ->
            val reason = (notification?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.longLongValue
            if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable.toLong()) {
                pauseForInterruption()
                active?.let { deck ->
                    interruptionGeneration = null
                    emitInterruption(EngineEvent.Interruption(deck.generation, InterruptionKind.OUTPUT_REMOVED))
                    publishState(deck)
                }
            }
        }
        sessionObservers += center.addObserverForName(AVAudioSessionMediaServicesWereResetNotification,
            `object` = null, queue = NSOperationQueue.mainQueue) {
            val origin = active?.generation
            pauseForInterruption()
            val pending = fade?.takeIf { it.awaitingConfirmation }
            if (pending != null) pending.pendingReset = true else resetNativePlayers()
            // The controller invalidates prepared state and re-resolves on explicit Resume.
            if (origin != null) emitInterruption(EngineEvent.Interruption(origin, InterruptionKind.MEDIA_SERVICES_RESET))
        }
    }

    private fun configureSession(kind: MediaKind) {
        val mode = if (kind == MediaKind.EPISODE) AVAudioSessionModeSpokenAudio else AVAudioSessionModeDefault
        if (!session.setCategory(AVAudioSessionCategoryPlayback, mode = mode, options = 0u, error = null)) {
            throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "audio_session_configuration"))
        }
    }
    private fun resetNativePlayers() {
        clearRetiring()
        active?.let(::clearDeck)
        active = null
        players = List(2) { makePlayer() }
        session = AVAudioSession.sharedInstance()
        interrupted = false
        interruptionGeneration = null
        positions.value = null
    }
    private fun deactivateSession() {
        session.setActive(false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
    }
    private fun requireRate(deck: Deck, speed: Float) {
        if (!speed.isFinite() || speed !in capabilities.minSpeed..capabilities.maxSpeed ||
            (deck.ready && speed < 1f && !deck.item.canPlaySlowForward)) {
            throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE, "playback_rate"))
        }
    }
    private fun checkedUrl(source: ResolvedPlayable): NSURL {
        if (source.transport != StreamTransport.PROGRESSIVE) throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_TRANSPORT))
        if (source.headers.isNotEmpty()) throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE, "request_headers"))
        if (!capabilities.supports(source.transport, source.mimeType, source.codecs)) throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_CODEC))
        if (source.isLocal) {
            val path = if (source.uri.startsWith('/')) source.uri else NSURL.URLWithString(source.uri)?.takeIf { it.scheme == "file" }?.path
            if (path == null || !path.startsWith('/') || !NSFileManager.defaultManager.fileExistsAtPath(path)) {
                throw VeyraException(AppFailure(FailureCode.CORRUPT_MEDIA))
            }
            return NSURL.fileURLWithPath(path)
        }
        source.ownedLoopbackUriOrNull()?.let { return NSURL.URLWithString(it)!! }
        return NSURL.URLWithString(PublicUrlPolicy.normalize(source.uri))
            ?: throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE, "secure_media_url_required"))
    }
    private fun fail(deck: Deck, code: FailureCode, detail: String) {
        if (!managed(deck) || deck.failed) return
        deck.failed = true
        deck.player.pause()
        if (deck === staged) {
            if (fade?.awaitingConfirmation == true) {
                fade?.pendingFailure = AppFailure(code, detail)
                return
            }
            val origin = active?.generation ?: return
            emit(EngineEvent.StageFailed(origin, deck.generation, AppFailure(code, detail)))
            cancelStage(CrossfadeCancelReason.ERROR, smooth = true)
        } else {
            if (fade?.awaitingConfirmation == true) {
                fade?.pendingOutgoingFailure = AppFailure(code, detail)
                return
            }
            cancelStage(CrossfadeCancelReason.ERROR)
            emit(EngineEvent.Error(deck.generation, AppFailure(code, detail)))
            publishState(deck)
        }
    }
    private fun current(generation: Long) = active?.takeIf { it.generation == generation && isCurrent(it) }
    private fun managed(deck: Deck) = !closed && (deck === active || deck === staged) && deck.player.currentItem == deck.item
    private fun isCurrent(deck: Deck) = managed(deck) && deck === active
    private fun nativeDurationMs(deck: Deck): Long? = milliseconds(CMTimeGetSeconds(deck.item.duration))?.takeIf { it > 0 }
    private fun nativePositionMs(deck: Deck): Long = if (managed(deck)) milliseconds(CMTimeGetSeconds(deck.player.currentTime())) ?: 0 else 0
    private fun milliseconds(seconds: Double): Long? = seconds.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE / 1000.0 }?.let { (it * 1000).toLong() }
    private fun isPlaying(player: AVPlayer) = player.timeControlStatus == AVPlayerTimeControlStatusPlaying && player.rate > 0f
    private fun emit(event: EngineEvent) { controls.trySend(event) }
    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }
    private fun baseGain(deck: Deck) = if (deck.source.item?.kind == MediaKind.EPISODE) 1f else CrossfadeMix.MUSIC_GAIN
    private fun makePlayer() = AVPlayer().apply {
        automaticallyWaitsToMinimizeStalling = true
        actionAtItemEnd = AVPlayerActionAtItemEndPause
    }
}
