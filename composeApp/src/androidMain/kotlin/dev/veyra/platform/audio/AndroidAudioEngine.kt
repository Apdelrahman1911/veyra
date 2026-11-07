@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.veyra.platform.audio

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as NativeMediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
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
import java.io.File
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
import kotlinx.coroutines.withTimeout

/**
 * One application transport with two managed native decks for music crossfades.
 * The shared controller is the only queue owner; the staged deck is never a second session.
 * Construct on Main. [beforePlay] establishes the mediaPlayback foreground service before focus.
 */
class AndroidAudioEngine(
    context: Context,
    private val beforePlay: suspend () -> Unit = {},
) : CrossfadeAudioEngine {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controls = Channel<EngineEvent>(Channel.UNLIMITED)
    private val positions = MutableStateFlow<EngineEvent.Position?>(null)
    override val events: Flow<EngineEvent> = merge(controls.receiveAsFlow(), positions.filterNotNull())
    override val capabilities = EngineCapabilities(
        transports = setOf(StreamTransport.PROGRESSIVE),
        mimeTypes = setOf("audio/mpeg", "audio/mp4", "audio/aac", "audio/webm", "audio/ogg",
            "application/ogg", "audio/flac", "audio/wav", "audio/x-wav"),
        codecs = setOf("mp3", "aac", "mp4a", "opus", "vorbis", "flac", "pcm"),
        minSpeed = 0.5f, maxSpeed = 2f, supportsRequestHeaders = true, supportsCrossfade = true,
    )
    private val players: List<ExoPlayer>
    private val focus: AndroidAudioFocus
    private var active: Deck? = null
    private var staged: Deck? = null
    private var fade: Fade? = null
    private var retiring: Deck? = null
    private var restoration: Job? = null
    private var generation = -1L
    private var preparationRevision = 0L
    private var outputRevision = 0L
    private var serviceLossPending = false
    private var failedForegroundGeneration: Long? = null
    private var closed = false
    private val resourceClosures = mutableListOf<Deferred<Unit>>()
    private var resourceCleanupFailed = false

    /** Tests only. Application/session commands go through PlaybackController. */
    internal val nativePlayer: ExoPlayer get() = active?.player ?: players.first()
    internal val nativeDeckVolumes: List<Float> get() = players.map { it.volume }
    internal val nativeDecksPlaying: List<Boolean> get() = players.map { it.isPlaying }

    private class Deck(val player: ExoPlayer, val source: ResolvedPlayable, val generation: Long) {
        var listener: Player.Listener? = null
        var confirmedSeekSequence = 0L
        var pendingSeekSequence: Long? = null
        var ended = false
        var hasPlayed = false
        var suppressNaturalEnd = false
        var initialPositionMs = 0L
        var failed = false
        var ready = false
        var resourceReleased = false
        var dataSources: AndroidDeckDataSources? = null
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
        var pendingServiceFailure: AppFailure? = null
        val pendingInterruptions = mutableListOf<EngineEvent.Interruption>()
    }

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Create the Android audio owner on Main." }
        players = List(2) {
            ExoPlayer.Builder(this.context).build().apply {
                setAudioAttributes(attributes(MediaKind.MUSIC), false)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
                repeatMode = Player.REPEAT_MODE_OFF
                shuffleModeEnabled = false
            }
        }
        focus = AndroidAudioFocus(this.context, pauseNative = ::pauseForInterruption, emit = ::emitInterruption)
        scope.launch {
            while (isActive) {
                active?.let(::samplePosition)
                delay(250)
            }
        }
    }

    override suspend fun prepare(source: ResolvedPlayable, startPositionMs: Long, generation: Long) {
        var handedToDeck = false
        try {
            onMain {
                if (closed || generation < this.generation) return@onMain
                val revision = ++preparationRevision
                val uri = checkedUri(source)
                cancelStage(CrossfadeCancelReason.CANCELLED, smooth = true)?.join()
                if (closed || generation < this.generation || revision != preparationRevision) return@onMain
                outputRevision++
                serviceLossPending = false
                failedForegroundGeneration = null
                clearRetiring()
                active?.let(::clearDeck)
                active = null
                focus.abandon()
                this.generation = generation
                positions.value = null
                val deck = Deck(players.first(), source, generation)
                active = deck
                handedToDeck = true
                try {
                    configureDeck(deck, uri, startPositionMs)
                    emit(EngineEvent.StateChanged(generation, EnginePhase.PREPARING, false))
                    deck.player.prepare()
                } catch (error: Exception) {
                    clearDeck(deck)
                    active = null
                    throw error
                }
            }
        } finally {
            // Invocation transfers ownership even if Main dispatch is cancelled before it starts,
            // or a stale generation/invalid source is rejected without constructing a native deck.
            if (!handedToDeck) withContext(NonCancellable) { source.resource?.close() }
        }
    }

    override suspend fun play(generation: Long) {
        val revision = onMain { if (current(generation) == null || serviceLossPending) null else outputRevision } ?: return
        beforePlay()
        onMain {
            val deck = current(generation) ?: return@onMain
            if (revision != outputRevision || serviceLossPending) return@onMain
            when (focus.request(generation, deck.source.item?.kind ?: MediaKind.MUSIC)) {
                AndroidAudioFocus.Result.GRANTED -> deck.player.play()
                AndroidAudioFocus.Result.WAITING -> Unit
                AndroidAudioFocus.Result.DENIED -> throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "audio_focus_denied"))
            }
        }
    }

    override suspend fun pause(generation: Long): Unit = onMain {
        val deck = current(generation) ?: return@onMain
        outputRevision++
        // Pause may acknowledge an ordinary service-loss interruption, but a queued same-
        // generation Pause cannot clear a terminal policy failure and let a later Play retry.
        if (failedForegroundGeneration != generation) serviceLossPending = false
        deck.player.pause()
        staged?.player?.pause()
        cancelStage(CrossfadeCancelReason.INTERRUPTED)
        clearRetiring()
        deck.player.volume = baseGain(deck)
        focus.paused()
        samplePosition(deck)
        publishState(deck)
    }

    override suspend fun seek(positionMs: Long, seekSequence: Long, generation: Long): Unit = onMain {
        val deck = current(generation) ?: return@onMain
        cancelStage(CrossfadeCancelReason.CANCELLED, smooth = true, reportOutgoingEnd = false)?.join()
        if (current(generation) !== deck) return@onMain
        if (!deck.player.isCurrentMediaItemSeekable) {
            emit(EngineEvent.SeekCompleted(generation, seekSequence, deck.player.currentPosition, false))
            return@onMain
        }
        val position = duration(deck)?.let { positionMs.coerceIn(0, it) } ?: positionMs.coerceAtLeast(0)
        deck.pendingSeekSequence = seekSequence
        deck.ended = false
        deck.hasPlayed = false
        deck.initialPositionMs = 0
        deck.suppressNaturalEnd = duration(deck)?.let { position >= it - 1 } ?: false
        // A discontinuity masks the requested position before decoding. A later READY/ENDED
        // callback confirms playback-thread processing; do not persist the masked position yet.
        deck.player.seekTo(position)
    }

    override suspend fun setRate(speed: Float, generation: Long): Unit = onMain {
        val deck = current(generation) ?: return@onMain
        if (!speed.isFinite() || speed !in capabilities.minSpeed..capabilities.maxSpeed) {
            throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE, "playback_rate"))
        }
        if (deck.player.playbackParameters.speed != speed) cancelStage(CrossfadeCancelReason.CANCELLED, smooth = true)?.join()
        if (current(generation) !== deck) return@onMain
        deck.player.playbackParameters = PlaybackParameters(speed, 1f)
        publishState(deck)
    }

    override suspend fun preloadNext(source: ResolvedPlayable, startPositionMs: Long, nextGeneration: Long, generation: Long) {
        var handedToDeck = false
        try {
            onMain {
                val outgoing = current(generation) ?: return@onMain
                if (serviceLossPending || !outgoing.player.playWhenReady) return@onMain
                val revision = outputRevision
                if (nextGeneration <= generation || source.item?.kind == MediaKind.EPISODE ||
                    outgoing.source.item?.kind == MediaKind.EPISODE) return@onMain
                if (staged?.generation == nextGeneration) {
                    handedToDeck = staged?.source?.resource === source.resource
                    return@onMain
                }
                val uri = try { checkedUri(source) } catch (failure: VeyraException) {
                    emit(EngineEvent.StageFailed(generation, nextGeneration, failure.failure))
                    return@onMain
                }
                cancelStage(CrossfadeCancelReason.CANCELLED, smooth = true)?.join()
                if (current(generation) !== outgoing || outputRevision != revision || serviceLossPending) return@onMain
                clearRetiring()
                val deck = Deck(players.first { it !== outgoing.player }, source, nextGeneration)
                staged = deck
                handedToDeck = true
                try {
                    configureDeck(deck, uri, startPositionMs)
                    deck.player.volume = 0f
                    deck.player.playbackParameters = outgoing.player.playbackParameters
                    deck.player.prepare()
                } catch (_: Exception) {
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
        if (durationMs !in 100..30_000 || !outgoing.player.isPlaying || duration(outgoing) == null || duration(incoming) == null) {
            cancelStage(CrossfadeCancelReason.CANCELLED)
            return@onMain
        }
        val operation = Fade(outgoing, incoming)
        fade = operation
        incoming.player.volume = 0f
        incoming.player.playbackParameters = outgoing.player.playbackParameters
        incoming.player.play()
        operation.job = scope.launch {
            val waitStarted = SystemClock.elapsedRealtime()
            while (fade === operation && !incoming.player.isPlaying) {
                if (incoming.failed || incoming.player.playbackState == Player.STATE_ENDED ||
                    SystemClock.elapsedRealtime() - waitStarted > 1_500) {
                    cancelStage(CrossfadeCancelReason.INCOMING_STALLED, smooth = true)
                    return@launch
                }
                delay(20)
            }
            if (fade !== operation) return@launch
            val speed = outgoing.player.playbackParameters.speed.coerceAtLeast(0.5f)
            val outgoingDuration = duration(outgoing)
            val incomingDuration = duration(incoming)
            if (outgoingDuration == null || incomingDuration == null) {
                cancelStage(CrossfadeCancelReason.CANCELLED, smooth = true)
                return@launch
            }
            val remaining = ((outgoingDuration - outgoing.player.currentPosition) / speed).toLong()
            val incomingRemaining = ((incomingDuration - incoming.player.currentPosition) / speed).toLong()
            val length = minOf(durationMs, remaining, incomingRemaining)
            if (length < 100 || !outgoing.player.isPlaying) {
                cancelStage(CrossfadeCancelReason.OUTGOING_ENDED)
                return@launch
            }
            operation.started = true
            emit(EngineEvent.CrossfadeStarted(generation, nextGeneration, length))
            val startedAt = SystemClock.elapsedRealtimeNanos()
            var lastProgress = -1L
            while (fade === operation) {
                if (!incoming.player.isPlaying) {
                    cancelStage(CrossfadeCancelReason.INCOMING_STALLED, smooth = true)
                    return@launch
                }
                if (!outgoing.player.isPlaying) {
                    if (outgoing.player.playbackState == Player.STATE_ENDED) completeFade(operation)
                    else cancelStage(CrossfadeCancelReason.INTERRUPTED)
                    return@launch
                }
                val elapsed = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000
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
        this.generation = incoming.generation
        if (failedForegroundGeneration == generation) failedForegroundGeneration = nextGeneration
        incoming.player.volume = baseGain(incoming)
        focus.transfer(generation, nextGeneration)
        incoming.lastReady = EngineEvent.Ready(nextGeneration, duration(incoming), incoming.player.isCurrentMediaItemSeekable)
        publishState(incoming)
        samplePosition(incoming)
        operation.pendingInterruptions.forEach { emit(it.copy(generation = nextGeneration)) }
        val failure = operation.pendingServiceFailure ?: operation.pendingFailure
        if (failure != null) emit(EngineEvent.Error(nextGeneration, failure))
        else if (incoming.ended) emit(EngineEvent.Ended(nextGeneration))
    }

    override suspend fun stop(generation: Long): Unit = onMain {
        if (closed) { finishResourceClosures(); return@onMain }
        if (generation < this.generation) return@onMain
        preparationRevision++
        outputRevision++
        serviceLossPending = false
        failedForegroundGeneration = null
        active?.player?.pause()
        staged?.player?.pause()
        cancelStage(CrossfadeCancelReason.CANCELLED)
        clearRetiring()
        active?.let(::clearDeck)
        active = null
        focus.abandon()
        this.generation = generation
        positions.value = null
        emit(EngineEvent.StateChanged(generation, EnginePhase.IDLE, false))
        finishResourceClosures()
    }

    override suspend fun close(): Unit = onMain {
        if (closed) { finishResourceClosures(); return@onMain }
        preparationRevision++
        outputRevision++
        active?.player?.pause()
        staged?.player?.pause()
        cancelStage(CrossfadeCancelReason.CANCELLED)
        clearRetiring()
        active?.let(::clearDeck)
        active = null
        closed = true
        focus.abandon()
        players.forEach(ExoPlayer::release)
        positions.value = null
        try { finishResourceClosures() }
        finally { scope.cancel(); controls.close() }
    }

    private fun configureDeck(deck: Deck, uri: Uri, startPositionMs: Long) {
        val player = deck.player
        player.stop()
        player.clearMediaItems()
        player.volume = if (deck === active) baseGain(deck) else 0f
        player.playWhenReady = false
        deck.initialPositionMs = startPositionMs.coerceAtLeast(0)
        player.setAudioAttributes(attributes(deck.source.item?.kind ?: MediaKind.MUSIC), false)
        val item = deck.source.item
        val metadata = MediaMetadata.Builder().setTitle(item?.title).setArtist(item?.artist ?: item?.creator)
            .setAlbumTitle(item?.album).setMediaType(if (item?.kind == MediaKind.EPISODE)
                MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE else MediaMetadata.MEDIA_TYPE_MUSIC).build()
        val media = NativeMediaItem.Builder().setMediaId("veyra:${deck.generation}:${deck.source.mediaId}")
            .setUri(uri).setMimeType(deck.source.mimeType).setMediaMetadata(metadata).setTag(deck.generation).build()
        // Artwork is deliberately not fetched by either player.
        val http = DefaultHttpDataSource.Factory().setUserAgent("Veyra/1.0")
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000).setAllowCrossProtocolRedirects(false)
            .setDefaultRequestProperties(deck.source.headers)
        val dataSources = AndroidDeckDataSources(DefaultDataSource.Factory(context, http))
        deck.dataSources = dataSources
        deck.listener = listenerFor(deck)
        player.addListener(checkNotNull(deck.listener))
        player.setMediaSource(ProgressiveMediaSource.Factory(dataSources).createMediaSource(media), startPositionMs.coerceAtLeast(0))
    }

    private fun listenerFor(deck: Deck) = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!managed(deck)) return
            if (!deck.hasPlayed && deck.initialPositionMs > 0 &&
                duration(deck)?.let { deck.initialPositionMs >= it - 1 } == true) deck.suppressNaturalEnd = true
            if (playbackState == Player.STATE_READY && deck.player.playbackState == Player.STATE_READY) {
                deck.ready = true
                if (deck === active) {
                    publishReady(deck)
                    confirmSeek(deck)
                } else publishStageReady(deck)
            }
            if (playbackState == Player.STATE_ENDED && deck.player.playbackState == Player.STATE_ENDED && !deck.ended) {
                deck.ended = true
                if (fade?.awaitingConfirmation == true) return
                if (deck === staged) {
                    cancelStage(CrossfadeCancelReason.INCOMING_ENDED, smooth = true)
                    return
                }
                val operation = fade
                if (operation != null && operation.outgoing === deck && operation.started && operation.incoming.player.isPlaying) {
                    completeFade(operation)
                    return
                }
                cancelStage(CrossfadeCancelReason.OUTGOING_ENDED)
                val seekEnded = deck.pendingSeekSequence != null
                confirmSeek(deck)
                samplePosition(deck)
                // Scrubbing to the end is not a naturally completed episode.
                if (!seekEnded && deck.hasPlayed && !deck.suppressNaturalEnd) emit(EngineEvent.Ended(deck.generation))
            }
            if (deck === active) publishState(deck)
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!managed(deck)) return
            if (isPlaying) deck.hasPlayed = true
            if (deck === active) publishState(deck)
        }
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) { if (deck === active) publishState(deck) }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (deck === active) samplePosition(deck)
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!managed(deck)) return
            if (deck === active && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                val current = active ?: return
                pauseForInterruption()
                focus.paused()
                emitInterruption(EngineEvent.Interruption(current.generation, InterruptionKind.OUTPUT_REMOVED))
            }
            if (deck === active) publishState(deck)
        }
        override fun onPlayerError(error: PlaybackException) {
            if (!managed(deck) || deck.failed) return
            deck.failed = true
            val failure = classify(error, deck.source.isLocal)
            if (deck === staged) {
                if (fade?.awaitingConfirmation == true) {
                    fade?.pendingFailure = failure
                    return
                }
                val origin = active?.generation ?: return
                emit(EngineEvent.StageFailed(origin, deck.generation, failure))
                cancelStage(CrossfadeCancelReason.ERROR, smooth = true)
            } else {
                if (fade?.awaitingConfirmation == true) {
                    // A durable handover may already be committing. Failure of the now-silent
                    // outgoing deck must not orphan the incoming deck before its ACK arrives.
                    fade?.pendingOutgoingFailure = failure
                    return
                }
                cancelStage(CrossfadeCancelReason.ERROR)
                emit(EngineEvent.Error(deck.generation, failure))
                publishState(deck)
            }
        }
    }

    private fun publishReady(deck: Deck) {
        val ready = EngineEvent.Ready(deck.generation, duration(deck), deck.player.isCurrentMediaItemSeekable)
        if (ready != deck.lastReady) { deck.lastReady = ready; emit(ready) }
    }
    private fun publishStageReady(deck: Deck) {
        val origin = active?.generation ?: return
        val ready = EngineEvent.StageReady(origin, deck.generation, duration(deck), deck.player.isCurrentMediaItemSeekable)
        if (ready != deck.lastStageReady) { deck.lastStageReady = ready; emit(ready) }
    }
    private fun confirmSeek(deck: Deck) {
        val sequence = deck.pendingSeekSequence ?: return
        if (deck !== active) return
        deck.confirmedSeekSequence = sequence
        deck.pendingSeekSequence = null
        emit(EngineEvent.SeekCompleted(deck.generation, sequence, deck.player.currentPosition.coerceAtLeast(0)))
    }
    private fun samplePosition(deck: Deck) {
        if (deck !== active || closed) return
        positions.value = EngineEvent.Position(deck.generation, deck.player.currentPosition.coerceAtLeast(0), duration(deck),
            deck.player.bufferedPosition.takeIf { it >= 0 }, deck.confirmedSeekSequence)
    }
    private fun publishState(deck: Deck) {
        if (deck !== active || closed || fade?.awaitingConfirmation == true) return
        val player = deck.player
        val phase = when {
            player.playerError != null -> EnginePhase.ERROR
            player.playbackState == Player.STATE_ENDED -> EnginePhase.ENDED
            player.playbackState == Player.STATE_BUFFERING -> EnginePhase.BUFFERING
            player.isPlaying -> EnginePhase.PLAYING
            player.playbackState == Player.STATE_READY -> EnginePhase.PAUSED
            else -> EnginePhase.PREPARING
        }
        val state = EngineEvent.StateChanged(deck.generation, phase, player.isPlaying,
            if (player.isPlaying) player.playbackParameters.speed else 0f)
        if (state != deck.lastState) { deck.lastState = state; emit(state) }
    }

    private fun completeFade(operation: Fade) {
        if (fade !== operation || operation.awaitingConfirmation || active !== operation.outgoing || staged !== operation.incoming) return
        operation.awaitingConfirmation = true
        operation.job?.cancel()
        val incoming = operation.incoming
        operation.outgoing.player.volume = 0f
        incoming.player.volume = baseGain(incoming)
        // Keep outgoing logical ownership until the shared durable queue transaction ACKs.
        emit(EngineEvent.CrossfadeCompleted(operation.outgoing.generation, incoming.generation,
            incoming.player.currentPosition.coerceAtLeast(0), duration(incoming), incoming.player.isCurrentMediaItemSeekable,
            incoming.player.playbackParameters.speed, incoming.player.isPlaying))
    }
    private fun cancelStage(reason: CrossfadeCancelReason, smooth: Boolean = false, reportOutgoingEnd: Boolean = true): Job? {
        val incoming = staged ?: return restoration
        val origin = active?.generation ?: generation
        val outgoing = active
        val operation = fade
        fade = null
        operation?.job?.cancel()
        staged = null
        clearRetiring()
        val shouldRamp = smooth && outgoing != null && outgoing.player.isPlaying &&
            (incoming.player.volume > 0f || outgoing.player.volume < baseGain(outgoing))
        if (shouldRamp) {
            val old = outgoing!!
            retiring = incoming
            val startOut = old.player.volume
            val startIn = incoming.player.volume
            restoration = scope.launch {
                val began = SystemClock.elapsedRealtimeNanos()
                while (retiring === incoming && active === old) {
                    val t = ((SystemClock.elapsedRealtimeNanos() - began) / 60_000_000.0).toFloat().coerceIn(0f, 1f)
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
            outgoing?.player?.volume = outgoing?.let(::baseGain) ?: CrossfadeMix.MUSIC_GAIN
        }
        emit(EngineEvent.CrossfadeCancelled(origin, incoming.generation, reason))
        operation?.pendingInterruptions?.forEach(::emit)
        val pendingFailure = operation?.pendingServiceFailure ?: operation?.pendingOutgoingFailure
        pendingFailure?.let { emit(EngineEvent.Error(origin, it)) }
        if (reportOutgoingEnd && operation?.awaitingConfirmation == true && operation.outgoing.ended &&
            pendingFailure == null) emit(EngineEvent.Ended(origin))
        return restoration
    }
    private fun clearRetiring() {
        restoration?.cancel()
        restoration = null
        retiring?.let(::clearDeck)
        retiring = null
    }
    private fun pauseForInterruption() {
        outputRevision++
        active?.player?.pause()
        staged?.player?.pause()
        clearRetiring()
        if (fade?.awaitingConfirmation != true) cancelStage(CrossfadeCancelReason.INTERRUPTED)
        if (fade?.awaitingConfirmation != true) active?.let { it.player.volume = baseGain(it) }
    }
    /** No queued unscoped Pause can accidentally stop playback owned by a successor service. */
    internal fun foregroundServiceLost(failure: AppFailure? = null) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val origin = active?.takeIf { !closed }?.generation ?: return
        serviceLossPending = true
        if (failure != null) failedForegroundGeneration = origin
        pauseForInterruption()
        focus.abandon()
        if (failure == null) emitInterruption(EngineEvent.Interruption(origin, InterruptionKind.PERMANENT))
        else {
            val pending = fade?.takeIf { it.awaitingConfirmation && it.outgoing.generation == origin }
            // A global allowance failure applies to whichever side the durable handover selects.
            if (pending != null) pending.pendingServiceFailure = failure
            else emit(EngineEvent.Error(origin, failure))
        }
    }
    private fun emitInterruption(event: EngineEvent.Interruption) {
        val pending = fade?.takeIf { it.awaitingConfirmation && it.outgoing.generation == event.generation }
        if (pending != null) pending.pendingInterruptions += event else emit(event)
    }
    private fun clearDeck(deck: Deck) {
        if (deck.resourceReleased) return // Never clear a later item reusing this native player.
        deck.resourceReleased = true
        deck.dataSources?.retire()
        val stopped = runCatching {
            deck.listener?.let(deck.player::removeListener)
            deck.listener = null
            deck.player.stop()
            deck.player.clearMediaItems()
            deck.player.volume = baseGain(deck)
            deck.player.createMessage { _, _ -> }.send()
        }
        val closing = scope.async(start = CoroutineStart.LAZY) {
            withContext(NonCancellable) {
                var failed = stopped.isFailure
                // Closing the central bridge promptly also unblocks pending native HTTP reads.
                try { deck.source.resource?.close() } catch (_: Exception) { failed = true }
                try {
                    val barrier = stopped.getOrNull()
                    if (barrier != null && !withContext(Dispatchers.IO) { barrier.blockUntilDelivered(5_000) }) failed = true
                } catch (_: Exception) { failed = true }
                try {
                    // The playback-thread message alone does not acknowledge asynchronous Loader
                    // closure. Observe the real DataSource.close() return before local-file removal.
                    withTimeout(10_000) { deck.dataSources?.awaitClosed() }
                } catch (_: Exception) { failed = true }
                if (failed) throw VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "native_stop_not_acknowledged"))
            }
        }
        resourceClosures += closing
        closing.invokeOnCompletion { error ->
            if (error == null) scope.launch { resourceClosures.remove(closing) }
        }
        closing.start()
    }
    private suspend fun finishResourceClosures() = withContext(NonCancellable) {
        // Closing a source may itself need a Main-dispatched cancellation callback. Joining here
        // suspends rather than blocking Main; no arbitrary timeout can claim resources were closed.
        while (resourceClosures.isNotEmpty()) {
            val pending = resourceClosures.toList()
            pending.forEach { closing ->
                try { closing.await() } catch (_: Throwable) { resourceCleanupFailed = true }
                resourceClosures.remove(closing)
            }
        }
        // A later duplicate stop must not turn an unacknowledged cleanup into deletion approval.
        if (resourceCleanupFailed) throw VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "playback_resource_cleanup"))
    }
    private fun current(origin: Long) = active?.takeIf { !closed && origin == generation && it.generation == origin }
    private fun managed(deck: Deck) = !closed && (deck === active || deck === staged)
    private fun duration(deck: Deck) = deck.player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
    private fun emit(event: EngineEvent) { controls.trySend(event) }
    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }
    private fun baseGain(deck: Deck) = if (deck.source.item?.kind == MediaKind.EPISODE) 1f else CrossfadeMix.MUSIC_GAIN

    private fun checkedUri(source: ResolvedPlayable): Uri {
        if (source.transport != StreamTransport.PROGRESSIVE) throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_TRANSPORT))
        if (!capabilities.supports(source.transport, source.mimeType, source.codecs)) throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_CODEC))
        val uri = Uri.parse(source.uri)
        if (source.isLocal) {
            val path = when (uri.scheme) { null -> source.uri; "file" -> uri.path; else -> null }
                ?: throw VeyraException(AppFailure(FailureCode.CORRUPT_MEDIA))
            val file = File(path)
            if (!file.isAbsolute || !file.isFile || file.length() <= 0) throw VeyraException(AppFailure(FailureCode.CORRUPT_MEDIA))
            return Uri.fromFile(file)
        }
        source.ownedLoopbackUriOrNull()?.let { return Uri.parse(it) }
        // Platform TLS/redirect handling is not a DNS-pinning guarantee.
        return Uri.parse(PublicUrlPolicy.normalize(source.uri))
    }
    private fun classify(error: PlaybackException, local: Boolean): AppFailure {
        val response = generateSequence(error as Throwable?) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        val code = when {
            response == 401 || response == 403 -> FailureCode.PROVIDER_ACCESS_REQUIRED
            response == 404 || response == 410 -> FailureCode.NOT_FOUND
            response == 429 -> FailureCode.RATE_LIMITED
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> FailureCode.NETWORK_UNAVAILABLE
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> FailureCode.UNSUPPORTED_CODEC
            local -> FailureCode.CORRUPT_MEDIA
            else -> FailureCode.UNSUPPORTED_SOURCE
        }
        return AppFailure(code, "native_audio_${error.errorCode}")
    }
    private fun attributes(kind: MediaKind) = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
        .setContentType(if (kind == MediaKind.EPISODE) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC).build()
}
