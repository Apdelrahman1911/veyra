package dev.veyra.playback

import dev.veyra.data.LibraryStore
import dev.veyra.data.getOrThrow
import dev.veyra.data.withRetainedLocalArtwork
import dev.veyra.model.*
import dev.veyra.source.SourceResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

private const val GRACEFUL_SHUTDOWN_TIMEOUT_MS = 5_000L

/**
 * Single application-lifetime owner of two saved queue contexts and one native transport.
 * Network resolution is cancellable and outside the command loop. Native operations are ordered;
 * their callbacks must carry the originating load token. Only this owner writes queue/progress fields.
 */
class PlaybackController(
    private val store: LibraryStore,
    private val engine: AudioEngine,
    private val resolver: SourceResolver,
    scope: CoroutineScope,
    private val clock: Clock = SystemClock(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val random: Random = Random.Default,
    private val checkpointIntervalMs: Long = 5_000,
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + job + dispatcher)
    // Cleanup outlives owner cancellation, but is drained before close returns. Channel ownership
    // covers prompt-cancellation/undelivered-element races as well as stale generations.
    private val resourceCleanupScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val resourceCleanup = Channel<ResourceCleanup>(Channel.UNLIMITED)
    private val cleanupCompletion = CompletableDeferred<Throwable?>()
    private val nativeCloseLock = Mutex()
    private val nativeCloseCompletion = CompletableDeferred<Throwable?>()
    private val closeLock = Mutex()
    private val closeCompletion = CompletableDeferred<Throwable?>()
    private val cleanupJob: Job
    private val engineOperationJob: Job
    private val messages = Channel<Message>(256, onUndeliveredElement = ::discardMessage)
    private val positions = Channel<EngineEvent.Position>(Channel.CONFLATED)
    private val engineOperations = Channel<EngineOperation>(128, onUndeliveredElement = { it.discard() })
    private val mutableState = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private var initialized = false
    private var generation = 0L
    private var generationCounter = 0L
    private var preparedGeneration = -1L
    private var terminalGeneration = -1L
    private var seekSequence = 0L
    private var confirmedSeekSequence = 0L
    private var pendingSeekSequence: Long? = null
    private var progressSequence = 0L
    private var lastCheckpointMs = clock.monotonicMs()
    private var progressDirty = false
    private var intentRevision = 0L
    private var resumeToken: ResumeToken? = null
    private var durationDeadlineMs: Long? = null
    private var resolvedLocal = false
    private var lastSnapshotRevision = -1L
    private var lastOfflineOnly = false
    private var resolveJob: Job? = null
    private var nextResolveJob: Job? = null
    // Retired resolutions can still hold validators/resources after the visible item has changed.
    // Only the actor mutates this collection; completion is read through Job's thread-safe state.
    private val resolutions = mutableListOf<MediaResolution>()
    // Accessed only by the ordered native worker. Any failed release vetoes deletion until a
    // successful full stop proves that both native decks were actually released.
    private var nativeReleaseFailure: AppFailure? = null
    private var stagedNext: StagedNext? = null
    private var suppressedNext: Pair<Long, String>? = null
    private var historyStartedMs = 0L
    /** A stop/retry generation is a callback barrier, not a new listening occurrence. */
    private var historyGeneration = 0L
    private var hasPlayedCurrent = false
    private var sourceRenewals = 0
    private var requestedBindingId: String? = null
    private var eventStreamFailed = false
    private val sessionId = newEntityId("session")
    private val undoQueues = mutableMapOf<QueueContext, QueueSnapshot>()

    init {
        require(checkpointIntervalMs > 0)
        cleanupJob = resourceCleanupScope.launch {
            var cleanupFailure: Throwable? = null
            try {
                for (work in resourceCleanup) {
                    when (work) {
                        is ResourceCleanup.Close -> try { work.resource.close() }
                        catch (error: Throwable) {
                            // Even a resource which throws cancellation must not skip later closes.
                            // Preserve that cancellation for close(), rather than losing it in a Job.
                            cleanupFailure = combineCloseFailures(cleanupFailure,
                                closeFailureOf(error, "Playback resource cleanup failed"))
                            messages.trySend(Message.ResourceCleanupFailed)
                        }
                        is ResourceCleanup.Fence -> work.result.complete(cleanupFailure?.let {
                            AppFailure(FailureCode.UNEXPECTED, "Playback resource cleanup failed")
                        })
                    }
                }
            } catch (error: Throwable) {
                cleanupFailure = combineCloseFailures(cleanupFailure,
                    closeFailureOf(error, "Playback resource cleanup failed"))
            } finally {
                cleanupCompletion.complete(cleanupFailure)
                resourceCleanupScope.cancel()
            }
        }
        job.invokeOnCompletion { resourceCleanup.close() }
        engineOperationJob = ownerScope.launch {
            try {
                for (operation in engineOperations) {
                    if (operation.generation != mutableState.value.generation) { operation.discard(); continue }
                    if (nativeReleaseFailure != null && !operation.releaseBarrier) { operation.discard(); continue }
                    try {
                        operation.action()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        val failure = failureOf(error)
                        nativeReleaseFailure = failure
                        messages.send(Message.Event(EngineEvent.Error(operation.generation, failure)))
                    }
                }
            } finally { engineOperations.cancel() }
        }
        ownerScope.launch {
            try {
                while (isActive) {
                    select<Unit> {
                        messages.onReceive { message -> handle(message) }
                        positions.onReceive { position -> if (initialized) safely { handleEvent(position) } }
                    }
                }
            } finally {
                messages.close()
                positions.close()
                engineOperations.close()
                val failure = PlaybackResult.Failure(AppFailure(FailureCode.CANCELLED))
                while (true) {
                    when (val pending = messages.tryReceive().getOrNull() ?: break) {
                        is Message.Command -> pending.result.complete(failure)
                        is Message.Initialize -> pending.result.complete(failure)
                        is Message.Shutdown -> pending.result.complete(failure)
                        is Message.ReleaseAsset -> pending.result.complete(failure)
                        else -> discardMessage(pending)
                    }
                }
                job.cancel()
                withContext(NonCancellable) {
                    engineOperationJob.cancelAndJoin()
                    // SupervisorJob/join do not propagate a child's exception to public close().
                    // Keep the actual result, including stop failure, for every later caller.
                    finishNativeClose()
                }
            }
        }
        ownerScope.launch {
            try {
                engine.events.collect { event ->
                    if (event is EngineEvent.Position) positions.send(event) else messages.send(Message.Event(event))
                }
                if (isActive) messages.send(Message.EventStreamFailed)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (isActive) messages.send(Message.EventStreamFailed)
            }
        }
        ownerScope.launch { store.state.collect { messages.send(Message.SnapshotChanged(it)) } }
        ownerScope.launch {
            while (isActive) {
                delay(250)
                messages.trySend(Message.Tick)
            }
        }
    }

    suspend fun initialize(): PlaybackResult = request { Message.Initialize(it) }
    suspend fun dispatch(command: PlaybackCommand): PlaybackResult = request { Message.Command(command, it) }

    /**
     * Native-release acknowledgement, unlike the queued Stop command. Persist removal intent before
     * calling, so new resolves cannot reopen the asset while cleanup waits for this barrier.
     */
    suspend fun releaseForAssetRemoval(mediaId: String): PlaybackResult = request { Message.ReleaseAsset(mediaId, it) }

    /** Drain all owners once; a failed shutdown remains failed on every subsequent call. */
    suspend fun close() {
        var failure: Throwable? = null
        try {
            withContext(NonCancellable) {
                closeLock.withLock {
                    if (!closeCompletion.isCompleted) {
                        var closingFailure: Throwable? = null
                        try {
                            if (job.isActive) {
                                // A full native-operation mailbox can prevent the actor from even
                                // accepting Shutdown. Bound only this graceful checkpoint attempt;
                                // actual native/resource release below is still fully awaited.
                                val result = withTimeoutOrNull(GRACEFUL_SHUTDOWN_TIMEOUT_MS) {
                                    request { Message.Shutdown(it) }
                                } ?: throw VeyraException(AppFailure(FailureCode.UNEXPECTED,
                                    "Playback shutdown checkpoint timed out"))
                                result.getOrThrow()
                            }
                        } catch (error: Throwable) {
                            closingFailure = closeFailureOf(error, "Playback shutdown checkpoint failed")
                        }
                        job.cancelAndJoin() // Includes retired/cancellation-delayed resolvers.
                        // The actor may never have started when its parent was already cancelled.
                        // Cancel the mailboxes in that case too, disposing any undelivered owners.
                        messages.cancel()
                        positions.cancel()
                        engineOperations.cancel()
                        closingFailure = combineCloseFailures(closingFailure, finishNativeClose())
                        cleanupJob.join()
                        closingFailure = combineCloseFailures(closingFailure, cleanupCompletion.await())
                        closeCompletion.complete(closingFailure)
                    }
                    failure = closeCompletion.await()
                }
            }
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            // NonCancellable guarantees the drain, not successful completion to a cancelled caller.
            // Caller cancellation is not cached as a failure for later, still-active close callers.
            failure?.takeUnless { it === cancelled }?.let(cancelled::addSuppressed)
            throw cancelled
        }
        failure?.let { throw it }
    }

    /** Called only after native work has stopped, both by the actor and its never-started fallback. */
    private suspend fun finishNativeClose(): Throwable? = nativeCloseLock.withLock {
        if (!nativeCloseCompletion.isCompleted) {
            var failure: Throwable? = null
            try { engine.stop(reserveGeneration()) }
            catch (error: Throwable) { failure = closeFailureOf(error, "Native playback stop failed") }
            try { engine.close() }
            catch (error: Throwable) {
                failure = combineCloseFailures(failure, closeFailureOf(error, "Native playback close failed"))
            }
            nativeCloseCompletion.complete(failure)
        }
        nativeCloseCompletion.await()
    }

    private fun closeFailureOf(error: Throwable, detail: String): Throwable = when (error) {
        is CancellationException, is VeyraException -> error
        else -> VeyraException(AppFailure(FailureCode.UNEXPECTED, detail))
    }

    private fun combineCloseFailures(first: Throwable?, next: Throwable?): Throwable? {
        if (first == null) return next
        if (next != null && next !== first) first.addSuppressed(next)
        return first
    }

    private suspend fun request(create: (CompletableDeferred<PlaybackResult>) -> Message): PlaybackResult {
        if (!job.isActive) return PlaybackResult.Failure(AppFailure(FailureCode.CANCELLED))
        val result = CompletableDeferred<PlaybackResult>(job)
        return try {
            messages.send(create(result))
            result.await()
        } catch (cancelled: CancellationException) {
            if (currentCoroutineContext().isActive && !job.isActive) PlaybackResult.Failure(AppFailure(FailureCode.CANCELLED))
            else throw cancelled
        } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
            PlaybackResult.Failure(AppFailure(FailureCode.CANCELLED))
        } finally { if (!result.isCompleted) result.cancel() }
    }

    private suspend fun handle(message: Message) {
        when (message) {
            is Message.Initialize -> complete(message.result) { initializeOwned() }
            is Message.Command -> complete(message.result) {
                initializeOwned()
                drainPosition()
                enforceOfflinePolicy()
                checkSleepTimer()
                handleCommand(message.command)
                checkCrossfade()
            }
            is Message.Shutdown -> complete(message.result) {
                if (initialized) {
                    drainPosition()
                    pauseOwned(userIntent = true)
                    checkpoint(force = true)
                }
                resolveJob?.cancel()
            }
            is Message.ReleaseAsset -> {
                try { releaseAssetOwned(message.mediaId, message.result) }
                catch (cancelled: CancellationException) { message.result.complete(PlaybackResult.Failure(AppFailure(FailureCode.CANCELLED))); throw cancelled }
                catch (error: Exception) { message.result.complete(PlaybackResult.Failure(failureOf(error))) }
            }
            is Message.AssetResolversReleased -> {
                if (message.generation != generation) message.result.complete(releaseSuperseded())
                else finishAssetRelease(message.result)
            }
            is Message.NativeReleaseFailed -> if (message.generation == generation) {
                // A failed stop must not recursively produce another failed stop forever. Keep
                // deletion vetoed and reject ordinary native work until an explicit retry stops it.
                resolveJob?.cancel()
                cancelNext(notifyEngine = false)
                // Cancellation cannot retract a source/event already queued for this token. Retire
                // it without another native stop so late work is disposed, not shown as PREPARING.
                generation = reserveGeneration()
                preparedGeneration = -1
                terminalGeneration = -1
                pendingSeekSequence = null
                resumeToken = null
                mutableState.value = mutableState.value.copy(generation = generation, phase = PlaybackPhase.ERROR,
                    desiredPlaying = false, isPlaying = false, seekable = false, pendingSeekMs = null, failure = message.failure)
            }
            is Message.Event -> if (initialized) safely {
                drainPosition()
                enforceOfflinePolicy()
                checkSleepTimer()
                handleEvent(message.event)
                checkCrossfade()
            }
            is Message.Resolved -> if (initialized && message.generation == generation) safely {
                handleResolved(message.source)
            } else discardSource(message.source)
            is Message.NextResolved -> if (initialized && message.generation == generation) safely { handleNextResolved(message) }
                else discardSource(message.source)
            is Message.NextResolveFailed -> if (initialized && message.generation == generation && stagedNext?.generation == message.nextGeneration) safely {
                cancelNext(suppress = true)
                mutableState.value = mutableState.value.copy(failure = message.failure)
            }
            is Message.ResolveFailed -> if (initialized && message.generation == generation) failPlayback(message.failure)
            Message.EventStreamFailed -> {
                eventStreamFailed = true
                if (initialized) safely {
                    failPlayback(AppFailure(FailureCode.UNEXPECTED, "Native event stream closed"))
                    checkpoint(force = true)
                }
            }
            Message.ResourceCleanupFailed -> if (initialized) safely { failPlayback(AppFailure(FailureCode.UNEXPECTED, "Playback resource cleanup failed")) }
            is Message.SnapshotChanged -> if (initialized) safely { applySnapshot() }
            Message.Tick -> if (initialized) safely {
                drainPosition()
                enforceOfflinePolicy()
                checkSleepTimer()
                if (mutableState.value.isPlaying && progressDirty && clock.monotonicMs() - lastCheckpointMs >= checkpointIntervalMs) checkpoint()
                checkCrossfade()
            }
        }
    }

    private suspend fun complete(result: CompletableDeferred<PlaybackResult>, block: suspend () -> Unit) {
        try {
            block()
            result.complete(PlaybackResult.Accepted)
        } catch (cancelled: CancellationException) {
            result.complete(PlaybackResult.Failure(AppFailure(FailureCode.CANCELLED)))
            throw cancelled
        } catch (error: Exception) {
            val failure = failureOf(error)
            mutableState.value = mutableState.value.copy(failure = failure)
            result.complete(PlaybackResult.Failure(failure))
        }
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failPlayback(failureOf(error)) }
    }

    private suspend fun initializeOwned() {
        check(!eventStreamFailed) { "Native event stream is unavailable" }
        if (initialized) return
        val snapshot = store.initialize().getOrThrow()
        val queue = snapshot.queue(snapshot.activeContext)
        val item = queue.currentEntry?.let { snapshot.media[it.mediaId] }
        lastSnapshotRevision = snapshot.revision
        lastOfflineOnly = snapshot.settings.offlineOnly
        mutableState.value = PlaybackState(
            initialized = true,
            activeContext = snapshot.activeContext,
            viewedContext = snapshot.activeContext,
            queues = snapshot.queues,
            currentItem = item,
            currentEntryId = queue.currentEntryId,
            positionMs = queue.positionMs.coerceAtLeast(0),
            durationMs = item?.let { snapshot.progress[it.id] }?.takeIf { it.executionBindingId in snapshot.playbackBindings }
                ?.durationMs?.takeIf { it > 0 },
            phase = if (item == null) PlaybackPhase.IDLE else PlaybackPhase.PAUSED,
            speed = item?.let { speedFor(it, snapshot, queue) } ?: queue.speed,
            executionBinding = item?.let { snapshot.progress[it.id]?.executionBindingId }?.let { snapshot.playbackBindings[it] },
        )
        initialized = true
        applySnapshot()
        // Restoring desiredPlaying from disk is deliberately impossible.
    }

    private suspend fun handleCommand(command: PlaybackCommand) {
        when (command) {
            is PlaybackCommand.Enqueue, is PlaybackCommand.Remove, is PlaybackCommand.Move,
            is PlaybackCommand.ClearUpcoming, is PlaybackCommand.UndoQueueEdit,
            is PlaybackCommand.SetShuffle, is PlaybackCommand.SetRepeat -> cancelNext()
            else -> Unit // Native pause/seek/stop paths cancel immediately, without a preceding ramp.
        }
        when (command) {
            is PlaybackCommand.Play -> playCollection(command)
            is PlaybackCommand.PlayEntry -> playEntry(command.context, command.entryId)
            is PlaybackCommand.SelectContext -> mutableState.value = mutableState.value.copy(viewedContext = command.context)
            PlaybackCommand.PlayPause -> if (mutableState.value.desiredPlaying || mutableState.value.isPlaying) pauseOwned(true) else resumeOwned()
            PlaybackCommand.Resume -> resumeOwned()
            PlaybackCommand.Pause -> pauseOwned(true)
            PlaybackCommand.Next -> nextOwned(naturalEnd = false)
            PlaybackCommand.Previous -> previousOwned()
            is PlaybackCommand.Seek -> {
                requireExpectedOccurrence(command.expectedEntryId, command.expectedGeneration)
                seekOwned(command.positionMs)
            }
            is PlaybackCommand.SkipBy -> {
                requireExpectedOccurrence(command.expectedEntryId, command.expectedGeneration)
                require(command.offsetMs in -86_400_000L..86_400_000L)
                val position = mutableState.value.positionMs
                val target = if (command.offsetMs > 0 && position > Long.MAX_VALUE - command.offsetMs) Long.MAX_VALUE else position + command.offsetMs
                seekOwned(target)
            }
            PlaybackCommand.SkipBack -> seekOwned(mutableState.value.positionMs - store.state.value.settings.skipBackSeconds * 1_000L)
            PlaybackCommand.SkipForward -> seekOwned(mutableState.value.positionMs + store.state.value.settings.skipForwardSeconds * 1_000L)
            is PlaybackCommand.Enqueue -> enqueue(command)
            is PlaybackCommand.Remove -> remove(command)
            is PlaybackCommand.Move -> move(command)
            is PlaybackCommand.Clear -> clear(command.context)
            is PlaybackCommand.ClearUpcoming -> clearUpcoming(command.context)
            is PlaybackCommand.UndoQueueEdit -> undo(command.context)
            is PlaybackCommand.SetShuffle -> shuffle(command)
            is PlaybackCommand.SetRepeat -> {
                require(command.context == QueueContext.MUSIC)
                commit { old -> old.copy(queues = old.queues + (command.context to old.queue(command.context).copy(repeat = command.mode))) }
            }
            is PlaybackCommand.SetRate -> setRate(command.speed, command.forThisShow)
            is PlaybackCommand.SleepDuration -> {
                require(command.durationMs in 1..86_400_000L)
                durationDeadlineMs = clock.monotonicMs() + command.durationMs
                mutableState.value = mutableState.value.copy(sleepTimer = SleepTimerState(SleepTimerMode.DURATION, remainingMs = command.durationMs))
            }
            PlaybackCommand.SleepAtEndOfEpisode -> {
                require(mutableState.value.currentItem?.kind == MediaKind.EPISODE)
                durationDeadlineMs = null
                mutableState.value = mutableState.value.copy(sleepTimer = SleepTimerState(SleepTimerMode.END_OF_EPISODE, boundEntryId = mutableState.value.currentEntryId))
            }
            PlaybackCommand.CancelSleepTimer -> clearTimer()
            is PlaybackCommand.MarkPlayed -> store.markPlayed(command.mediaId, command.played, clock.epochMs()).getOrThrow()
            is PlaybackCommand.ResetProgress -> resetProgress(command.mediaId)
            PlaybackCommand.Stop -> {
                pauseOwned(true)
                stopTransport()
                mutableState.value = mutableState.value.copy(phase = if (mutableState.value.currentItem == null) PlaybackPhase.IDLE else PlaybackPhase.PAUSED)
            }
            PlaybackCommand.FlushProgress -> checkpoint(force = true)
        }
        mutableState.value = mutableState.value.copy(undoableQueueContexts = undoQueues.keys.toSet())
    }

    private suspend fun playCollection(command: PlaybackCommand.Play) {
        require(command.items.isNotEmpty() && command.startIndex in command.items.indices)
        require(command.items.all { contextFor(it) == command.context })
        require(!command.startFromBeginning || command.context == QueueContext.PODCAST)
        var previous = store.state.value.queue(command.context)
        val entries = command.items.map { QueueEntry(newEntityId(), it.id) }
        val selected = entries[command.startIndex]
        val snapshot = commit { old ->
            previous = old.queue(command.context) // Includes the outgoing native checkpoint for undo.
            val media = old.media + command.items.associate { it.id to it.withRetainedLocalArtwork(old.media[it.id]) }
            val item = media.getValue(selected.mediaId)
            // The checkpoint above must happen BEFORE this durable user intent. A separate
            // ResetProgress -> Play sequence can checkpoint the outgoing, still-unseeked
            // position over the reset. Preserve its history and recording provenance, but
            // never label the reset as a confirmed position from that outgoing generation.
            val progress = if (command.startFromBeginning) old.progress + (item.id to MediaProgress(
                durationMs = old.progress[item.id]?.durationMs,
                updatedEpochMs = clock.epochMs(),
                executionBindingId = old.progress[item.id]?.executionBindingId,
            )) else old.progress
            var queue = previous.copy(
                entries = entries,
                currentEntryId = selected.id,
                positionMs = if (command.startFromBeginning) 0 else resumePosition(item, old),
                originalOrder = entries.map { it.id },
                speed = speedFor(item, old, previous),
                shuffle = command.shuffle ?: previous.shuffle,
            )
            if (queue.shuffle) queue = queue.copy(entries = shuffledAfterCurrent(queue))
            old.copy(media = media, progress = progress, queues = old.queues + (command.context to queue), activeContext = command.context)
        }
        undoQueues[command.context] = previous
        activate(snapshot, desiredPlaying = true, manual = true)
    }

    private suspend fun playEntry(context: QueueContext, entryId: String) {
        val snapshot = commit { old ->
            val queue = old.queue(context)
            val entry = queue.entries.firstOrNull { it.id == entryId } ?: throw IllegalArgumentException("Missing queue occurrence")
            val item = old.media.getValue(entry.mediaId)
            val position = if (entryId == queue.currentEntryId && mutableState.value.phase != PlaybackPhase.ENDED) queue.positionMs else resumePosition(item, old)
            old.copy(
                activeContext = context,
                queues = old.queues + (context to queue.copy(currentEntryId = entryId, positionMs = position, speed = speedFor(item, old, queue))),
            )
        }
        activate(snapshot, desiredPlaying = true, manual = true)
    }

    private suspend fun resumeOwned() {
        val current = mutableState.value
        if (current.currentItem == null) {
            val queue = store.state.value.queue(current.activeContext)
            queue.entries.firstOrNull()?.let { playEntry(current.activeContext, it.id) }
            return
        }
        intentRevision++
        resumeToken = null
        if (preparedGeneration == generation && current.phase !in setOf(PlaybackPhase.ERROR, PlaybackPhase.ENDED)) {
            mutableState.value = current.copy(desiredPlaying = true, failure = null)
            sendEngine { token -> if (mutableState.value.desiredPlaying && mutableState.value.generation == token) engine.play(token) }
        } else if (current.phase in setOf(PlaybackPhase.RESOLVING, PlaybackPhase.PREPARING)) {
            mutableState.value = current.copy(desiredPlaying = true, failure = null)
        } else {
            val snapshot = if (current.phase == PlaybackPhase.ENDED) commit { old ->
                old.copy(queues = old.queues + (current.activeContext to old.queue(current.activeContext).copy(positionMs = 0)))
            } else store.state.value
            activate(snapshot, desiredPlaying = true, manual = true, reuseBindingId = current.executionBinding?.id)
        }
    }

    private suspend fun pauseOwned(userIntent: Boolean) {
        cancelNext(notifyEngine = false)
        if (userIntent) { intentRevision++; resumeToken = null }
        mutableState.value = mutableState.value.copy(desiredPlaying = false)
        sendEngine { token -> engine.pause(token) }
        checkpoint(force = true)
    }

    private suspend fun releaseAssetOwned(mediaId: String, result: CompletableDeferred<PlaybackResult>) {
        require(mediaId.isNotBlank())
        initializeOwned()
        drainPosition()
        val active = mutableState.value.currentItem?.id == mediaId
        val staged = stagedNext?.takeIf { it.item.id == mediaId }
        val pendingResolves = resolutions.filter { it.mediaId == mediaId }.map { it.job }
        pendingResolves.forEach { it.cancel() }
        if (active) {
            pauseOwned(userIntent = true)
            stopTransport()
            mutableState.value = mutableState.value.copy(phase = PlaybackPhase.PAUSED)
        } else if (staged != null) cancelNext(suppress = true)
        val token = generation
        // Do not join in the actor or native worker. A resolver may have already queued a source
        // message. After all matching jobs finish, this FIFO roundtrip ensures those messages have
        // been handled/discarded before the native + controller-resource cleanup fences are queued.
        ownerScope.launch {
            try {
                pendingResolves.forEach { it.join() }
                messages.send(Message.AssetResolversReleased(token, result))
            } catch (cancelled: CancellationException) {
                result.complete(PlaybackResult.Failure(AppFailure(FailureCode.CANCELLED)))
                throw cancelled
            } catch (error: Exception) {
                result.complete(PlaybackResult.Failure(failureOf(error)))
                throw error
            }
        }
        resolutions.removeAll { it.job.isCompleted }
    }

    private suspend fun finishAssetRelease(result: CompletableDeferred<PlaybackResult>) {
        // Always enqueue this fence, including retired/noncurrent media. Its native stop/cancel/ACK
        // may still be in flight even though the actor already changed currentItem/stagedNext.
        sendEngine(releaseBarrier = true, onDiscard = { result.complete(releaseSuperseded()) }) { token ->
            try {
                val cleaned = CompletableDeferred<AppFailure?>()
                resourceCleanup.send(ResourceCleanup.Fence(cleaned))
                val failure = cleaned.await() ?: nativeReleaseFailure
                result.complete(when {
                    token != mutableState.value.generation -> releaseSuperseded()
                    failure != null -> PlaybackResult.Failure(failure)
                    else -> PlaybackResult.Accepted
                })
            } catch (cancelled: CancellationException) {
                result.complete(PlaybackResult.Failure(AppFailure(FailureCode.CANCELLED)))
                throw cancelled
            } catch (error: Exception) {
                result.complete(PlaybackResult.Failure(failureOf(error)))
                throw error
            }
        }
    }

    private suspend fun nextOwned(naturalEnd: Boolean) {
        cancelNext(notifyEngine = false)
        val current = mutableState.value
        val queue = store.state.value.queue(current.activeContext)
        if (queue.entries.isEmpty()) return
        val nextIndex = when {
            naturalEnd && queue.repeat == RepeatMode.ONE && current.activeContext == QueueContext.MUSIC -> queue.currentIndex
            queue.currentIndex + 1 < queue.entries.size -> queue.currentIndex + 1
            queue.repeat == RepeatMode.ALL && current.activeContext == QueueContext.MUSIC -> 0
            else -> -1
        }
        if (nextIndex < 0) {
            pauseOwned(userIntent = !naturalEnd)
            mutableState.value = mutableState.value.copy(phase = PlaybackPhase.ENDED, isPlaying = false)
            return
        }
        val selected = queue.entries[nextIndex]
        val snapshot = commit { old ->
            val item = old.media.getValue(selected.mediaId)
            old.copy(queues = old.queues + (current.activeContext to old.queue(current.activeContext).copy(
                currentEntryId = selected.id,
                positionMs = if (naturalEnd && selected.id == current.currentEntryId) 0 else resumePosition(item, old),
                speed = speedFor(item, old, queue),
            )))
        }
        activate(snapshot, desiredPlaying = if (naturalEnd) current.desiredPlaying else true, manual = !naturalEnd)
    }

    private suspend fun previousOwned() {
        val current = mutableState.value
        if (current.positionMs > 3_000 && current.seekable) { seekOwned(0); return }
        val queue = store.state.value.queue(current.activeContext)
        if (queue.entries.isEmpty()) return
        val index = when {
            queue.currentIndex > 0 -> queue.currentIndex - 1
            queue.repeat == RepeatMode.ALL && current.activeContext == QueueContext.MUSIC -> queue.entries.lastIndex
            else -> 0
        }
        val selected = queue.entries[index]
        val snapshot = commit { old -> old.copy(queues = old.queues + (current.activeContext to old.queue(current.activeContext).copy(currentEntryId = selected.id, positionMs = 0))) }
        activate(snapshot, desiredPlaying = true, manual = true)
    }

    private suspend fun seekOwned(positionMs: Long) {
        val current = mutableState.value
        require(current.currentItem != null && current.seekable && preparedGeneration == generation)
        cancelNext(notifyEngine = false)
        val target = boundedPosition(positionMs, current.durationMs)
        val sequence = ++seekSequence
        pendingSeekSequence = sequence
        terminalGeneration = -1 // Scrubbing back from a completed item is a new native seek barrier.
        mutableState.value = mutableState.value.copy(pendingSeekMs = target, failure = null,
            phase = if (mutableState.value.phase == PlaybackPhase.ENDED) PlaybackPhase.PAUSED else mutableState.value.phase)
        sendEngine { token -> engine.seek(target, sequence, token) }
    }

    private fun requireExpectedOccurrence(entryId: String?, expectedGeneration: Long?) {
        require(entryId == null || mutableState.value.currentEntryId == entryId) { "Playback occurrence changed" }
        require(expectedGeneration == null || generation == expectedGeneration) { "Playback generation changed" }
    }

    private suspend fun enqueue(command: PlaybackCommand.Enqueue) {
        require(command.items.all { contextFor(it) == command.context })
        if (command.items.isEmpty()) return
        val previous = store.state.value.queue(command.context)
        val additions = command.items.map { QueueEntry(newEntityId(), it.id) }
        commit { old ->
            val queue = old.queue(command.context)
            val entries = queue.entries.toMutableList()
            val index = if (command.position == EnqueuePosition.NEXT) (queue.currentIndex + 1).coerceAtLeast(0) else entries.size
            entries.addAll(index, additions)
            val original = queue.originalOrder.ifEmpty { queue.entries.map { it.id } }.toMutableList()
            val originalIndex = if (command.position == EnqueuePosition.NEXT) (original.indexOf(queue.currentEntryId) + 1).coerceAtLeast(0) else original.size
            original.addAll(originalIndex, additions.map { it.id })
            old.copy(media = old.media + command.items.associate { it.id to it.withRetainedLocalArtwork(old.media[it.id]) }, queues = old.queues + (command.context to queue.copy(entries = entries, originalOrder = original)))
        }
        undoQueues[command.context] = previous
    }

    private suspend fun remove(command: PlaybackCommand.Remove) {
        val before = store.state.value.queue(command.context)
        val index = before.entries.indexOfFirst { it.id == command.entryId }
        require(index >= 0)
        val activeRemoved = command.context == mutableState.value.activeContext && command.entryId == mutableState.value.currentEntryId
        val desired = mutableState.value.desiredPlaying
        val snapshot = commit { old ->
            val queue = old.queue(command.context)
            val entries = queue.entries.filterNot { it.id == command.entryId }
            val selected = if (queue.currentEntryId == command.entryId) entries.getOrNull(index) ?: entries.lastOrNull() else queue.currentEntry
            val position = if (queue.currentEntryId == command.entryId) selected?.let { resumePosition(old.media.getValue(it.mediaId), old) } ?: 0 else queue.positionMs
            old.copy(queues = old.queues + (command.context to queue.copy(entries = entries, currentEntryId = selected?.id, positionMs = position, originalOrder = queue.originalOrder.filterNot { it == command.entryId })))
        }
        undoQueues[command.context] = before
        if (activeRemoved) activate(snapshot, desired, manual = true)
    }

    private suspend fun move(command: PlaybackCommand.Move) {
        val before = store.state.value.queue(command.context)
        commit { old ->
            val queue = old.queue(command.context)
            val entries = queue.entries.toMutableList()
            val index = entries.indexOfFirst { it.id == command.entryId }
            require(index >= 0 && command.toIndex in entries.indices)
            entries.add(command.toIndex, entries.removeAt(index))
            old.copy(queues = old.queues + (command.context to queue.copy(entries = entries, shuffle = false, originalOrder = entries.map { it.id })))
        }
        undoQueues[command.context] = before
    }

    private suspend fun clear(context: QueueContext) {
        val before = store.state.value.queue(context)
        val snapshot = commit { old -> old.copy(queues = old.queues + (context to QueueSnapshot(speed = old.queue(context).speed))) }
        undoQueues[context] = before
        if (context == mutableState.value.activeContext) activate(snapshot, desiredPlaying = false, manual = true)
    }

    private suspend fun clearUpcoming(context: QueueContext) {
        val before = store.state.value.queue(context)
        val selectedIndex = before.currentIndex
        val retained = if (selectedIndex < 0) emptyList() else before.entries.take(selectedIndex + 1)
        if (retained.size == before.entries.size) return
        val retainedIds = retained.map { it.id }.toSet()
        commit { old -> old.copy(queues = old.queues + (context to old.queue(context).copy(
            entries = retained,
            originalOrder = old.queue(context).originalOrder.filter { it in retainedIds },
        ))) }
        undoQueues[context] = before
    }

    private suspend fun undo(context: QueueContext) {
        val previous = undoQueues.remove(context) ?: return
        val currentEntry = store.state.value.queue(context).currentEntryId
        val active = mutableState.value.activeContext == context
        val desired = mutableState.value.desiredPlaying
        val snapshot = commit { old ->
            val selected = if (previous.entries.any { it.id == currentEntry }) currentEntry else previous.currentEntryId
            val position = if (selected == currentEntry) old.queue(context).positionMs else previous.positionMs
            old.copy(queues = old.queues + (context to previous.copy(currentEntryId = selected, positionMs = position)))
        }
        if (active && snapshot.queue(context).currentEntryId != mutableState.value.currentEntryId) activate(snapshot, desired, manual = true)
    }

    private suspend fun shuffle(command: PlaybackCommand.SetShuffle) {
        require(command.context == QueueContext.MUSIC)
        val before = store.state.value.queue(command.context)
        if (before.shuffle == command.enabled) return
        commit { old ->
            val queue = old.queue(command.context)
            val next = if (command.enabled) {
                queue.copy(shuffle = true, originalOrder = queue.entries.map { it.id }, entries = shuffledAfterCurrent(queue))
            } else {
                val byId = queue.entries.associateBy { it.id }
                val original = queue.originalOrder.mapNotNull { byId[it] }
                val remaining = queue.entries.filterNot { it.id in queue.originalOrder }
                queue.copy(shuffle = false, entries = original + remaining, originalOrder = (original + remaining).map { it.id })
            }
            old.copy(queues = old.queues + (command.context to next))
        }
        undoQueues[command.context] = before
    }

    private fun shuffledAfterCurrent(queue: QueueSnapshot): List<QueueEntry> {
        val split = (queue.currentIndex + 1).coerceAtLeast(0)
        return queue.entries.take(split) + queue.entries.drop(split).shuffled(random)
    }

    private suspend fun setRate(speed: Float, forThisShow: Boolean) {
        require(speed.isFinite() && speed in 0.5f..2f && speed in engine.capabilities.minSpeed..engine.capabilities.maxSpeed)
        cancelNext(notifyEngine = false)
        val context = mutableState.value.activeContext
        val showId = mutableState.value.currentItem?.showId
        require(!forThisShow || (context == QueueContext.PODCAST && showId != null))
        commit { old ->
            val settings = if (context == QueueContext.PODCAST) old.settings.copy(
                speed = if (forThisShow) old.settings.speed else speed,
                showSpeeds = when {
                    forThisShow -> old.settings.showSpeeds + (requireNotNull(showId) to speed)
                    showId != null -> old.settings.showSpeeds - showId
                    else -> old.settings.showSpeeds
                },
            ) else old.settings
            old.copy(settings = settings, queues = old.queues + (context to old.queue(context).copy(speed = speed)))
        }
        mutableState.value = mutableState.value.copy(speed = speed)
        if (preparedGeneration == generation) sendEngine { token -> engine.setRate(speed, token) }
    }

    private suspend fun resetProgress(mediaId: String) {
        require(store.state.value.media[mediaId] != null)
        store.update { old -> old.copy(progress = old.progress + (mediaId to MediaProgress(updatedEpochMs = clock.epochMs()))) }.getOrThrow()
        if (mutableState.value.currentItem?.id == mediaId) {
            if (mutableState.value.seekable && preparedGeneration == generation) seekOwned(0)
            else {
                val current = mutableState.value
                val snapshot = store.update { old -> old.copy(queues = old.queues + (current.activeContext to old.queue(current.activeContext).copy(positionMs = 0))) }.getOrThrow()
                // Reprepare rather than claiming that an unseekable live transport moved to zero.
                activate(snapshot, current.desiredPlaying, manual = true, reuseBindingId = current.executionBinding?.id)
            }
        }
    }

    private suspend fun activate(
        snapshot: AppSnapshot,
        desiredPlaying: Boolean,
        manual: Boolean,
        reuseBindingId: String? = null,
        renewalDelayMs: Long? = null,
    ) {
        cancelNext(notifyEngine = false)
        if (manual) {
            intentRevision++
            if (mutableState.value.sleepTimer?.mode == SleepTimerMode.END_OF_EPISODE) clearTimer()
        }
        resumeToken = null
        resolveJob?.cancel()
        generation = reserveGeneration()
        preparedGeneration = -1
        terminalGeneration = -1
        seekSequence = 0
        confirmedSeekSequence = 0
        pendingSeekSequence = null
        progressDirty = false
        resolvedLocal = false
        suppressedNext = null
        if (renewalDelayMs == null) {
            historyStartedMs = clock.epochMs()
            historyGeneration = generation
            hasPlayedCurrent = false
            sourceRenewals = 0
        }
        requestedBindingId = reuseBindingId
        val queue = snapshot.queue(snapshot.activeContext)
        val item = queue.currentEntry?.let { snapshot.media[it.mediaId] }
        val current = mutableState.value
        mutableState.value = current.copy(
            activeContext = snapshot.activeContext,
            viewedContext = if (manual) snapshot.activeContext else current.viewedContext,
            queues = snapshot.queues,
            currentItem = item,
            currentEntryId = queue.currentEntryId,
            generation = generation,
            phase = if (item == null) PlaybackPhase.IDLE else PlaybackPhase.RESOLVING,
            desiredPlaying = desiredPlaying && item != null,
            isPlaying = false,
            positionMs = queue.positionMs.coerceAtLeast(0),
            durationMs = item?.let { snapshot.progress[it.id] }
                ?.takeIf { reuseBindingId != null && sameRecording(it.executionBindingId, reuseBindingId, snapshot) }
                ?.durationMs?.takeIf { it > 0 },
            bufferedPositionMs = null,
            seekable = false,
            speed = queue.speed,
            effectiveSpeed = 1f,
            pendingSeekMs = null,
            failure = null,
            crossfade = null,
            executionBinding = reuseBindingId?.let { snapshot.playbackBindings[it] },
        )
        val token = generation
        sendEngine(releaseBarrier = true) { stopNative(token) }
        if (item == null) return
        val offlineOnly = snapshot.settings.offlineOnly
        resolveJob = launchResolution(item.id) {
            var unhanded: ResolvedPlayable? = null
            try {
                if (renewalDelayMs != null) delay(renewalDelayMs)
                val source = resolver.resolve(item, engine.capabilities, offlineOnly, reuseBindingId = reuseBindingId)
                unhanded = source
                ensureActive()
                unhanded = null // Channel's onUndeliveredElement owns disposal from this point.
                messages.send(Message.Resolved(token, source))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                messages.send(Message.ResolveFailed(token, failureOf(error)))
            } finally { unhanded?.let(::discardSource) }
        }
    }

    private suspend fun handleResolved(source: ResolvedPlayable) {
        var handedToEngine = false
        try {
            if (source.mediaId != mutableState.value.currentItem?.id) {
                failPlayback(AppFailure(FailureCode.INVALID_STATE, "Resolved source identity differs from the selected item")); return
            }
            if (requestedBindingId != null && source.executionBinding?.id != requestedBindingId) {
                failPlayback(AppFailure(FailureCode.INVALID_STATE, "Source renewal changed execution identity")); return
            }
            if (store.state.value.settings.offlineOnly && !source.isLocal) {
                failPlayback(AppFailure(FailureCode.OFFLINE_POLICY)); return
            }
            if (source.transport !in engine.capabilities.transports) {
                failPlayback(AppFailure(FailureCode.UNSUPPORTED_TRANSPORT)); return
            }
            if (source.headers.isNotEmpty() && !engine.capabilities.supportsRequestHeaders) {
                failPlayback(AppFailure(FailureCode.UNSUPPORTED_SOURCE)); return
            }
            if (!engine.capabilities.supports(source.transport, source.mimeType, source.codecs)) {
                failPlayback(AppFailure(FailureCode.UNSUPPORTED_CODEC)); return
            }
            resolvedLocal = source.isLocal
            val previousProgress = store.state.value.progress[source.mediaId]
            val previousBinding = previousProgress?.executionBindingId?.let { store.state.value.playbackBindings[it] }
            val sameAudio = source.executionBinding == null || previousBinding?.selectedVideoId == source.executionBinding.selectedVideoId
            mutableState.value = mutableState.value.copy(
                phase = PlaybackPhase.PREPARING,
                positionMs = if (sameAudio) mutableState.value.positionMs else 0,
                durationMs = if (sameAudio) previousProgress?.durationMs else null,
                executionBinding = source.executionBinding,
            )
            val token = generation
            val position = mutableState.value.positionMs
            val tagged = source.copy(item = mutableState.value.currentItem)
            handedToEngine = true
            sendEngine(onDiscard = { discardSource(source) }) { engine.prepare(tagged, position, token) }
        } finally { if (!handedToEngine) discardSource(source) }
    }

    private suspend fun handleEvent(event: EngineEvent) {
        if (event.generation != generation || mutableState.value.currentItem == null) return
        when (event) {
            is EngineEvent.Ready -> {
                if (terminalGeneration == generation) return
                val alreadyPrepared = preparedGeneration == generation
                preparedGeneration = generation
                mutableState.value = mutableState.value.copy(
                    phase = if (alreadyPrepared) mutableState.value.phase else if (mutableState.value.desiredPlaying) PlaybackPhase.READY else PlaybackPhase.PAUSED,
                    durationMs = event.durationMs?.takeIf { it > 0 } ?: mutableState.value.durationMs,
                    seekable = event.seekable,
                )
                if (alreadyPrepared) return // Duration refresh must not cancel an overlap via setRate.
                val token = generation
                val speed = mutableState.value.speed
                sendEngine {
                    engine.setRate(speed, token)
                    if (mutableState.value.desiredPlaying && mutableState.value.generation == token) engine.play(token)
                }
            }
            is EngineEvent.StateChanged -> {
                if (terminalGeneration == generation && event.phase != EnginePhase.ERROR && event.phase != EnginePhase.ENDED) return
                val phase = when (event.phase) {
                    EnginePhase.IDLE -> if (mutableState.value.phase == PlaybackPhase.RESOLVING) PlaybackPhase.RESOLVING else PlaybackPhase.PAUSED
                    EnginePhase.PREPARING -> PlaybackPhase.PREPARING
                    EnginePhase.READY -> if (mutableState.value.desiredPlaying) PlaybackPhase.READY else PlaybackPhase.PAUSED
                    EnginePhase.PLAYING -> PlaybackPhase.PLAYING
                    EnginePhase.PAUSED -> PlaybackPhase.PAUSED
                    EnginePhase.BUFFERING -> PlaybackPhase.BUFFERING
                    EnginePhase.ENDED -> PlaybackPhase.ENDED
                    EnginePhase.ERROR -> PlaybackPhase.ERROR
                }
                mutableState.value = mutableState.value.copy(phase = phase, isPlaying = event.isPlaying, effectiveSpeed = event.effectiveSpeed)
                if (event.isPlaying) {
                    hasPlayedCurrent = true
                    if (!mutableState.value.desiredPlaying) sendEngine { token -> engine.pause(token) }
                }
            }
            is EngineEvent.Position -> {
                if (terminalGeneration == generation || pendingSeekSequence != null || event.seekSequence != confirmedSeekSequence) return
                val duration = event.durationMs?.takeIf { it > 0 } ?: mutableState.value.durationMs
                mutableState.value = mutableState.value.copy(
                    positionMs = boundedPosition(event.positionMs, duration),
                    durationMs = duration,
                    bufferedPositionMs = event.bufferedPositionMs?.let { boundedPosition(it, duration) },
                )
                progressDirty = true
            }
            is EngineEvent.SeekCompleted -> {
                if (event.seekSequence != pendingSeekSequence || terminalGeneration == generation) return
                pendingSeekSequence = null
                if (event.success) {
                    confirmedSeekSequence = event.seekSequence
                    mutableState.value = mutableState.value.copy(positionMs = boundedPosition(event.positionMs, mutableState.value.durationMs), pendingSeekMs = null)
                    progressDirty = true
                    checkpoint(force = true)
                } else mutableState.value = mutableState.value.copy(pendingSeekMs = null, failure = AppFailure(FailureCode.UNEXPECTED, "Seek was not completed"))
            }
            is EngineEvent.Ended -> {
                if (terminalGeneration == generation || pendingSeekSequence != null) return
                terminalGeneration = generation
                val current = mutableState.value
                mutableState.value = current.copy(isPlaying = false, phase = PlaybackPhase.ENDED, positionMs = current.durationMs ?: current.positionMs)
                checkpoint(force = true, naturalEnd = true)
                val sleepAtEnd = current.sleepTimer?.mode == SleepTimerMode.END_OF_EPISODE && current.sleepTimer.boundEntryId == current.currentEntryId
                if (sleepAtEnd) {
                    clearTimer()
                    resumeToken = null
                    intentRevision++
                    mutableState.value = mutableState.value.copy(desiredPlaying = false)
                } else if (current.desiredPlaying) nextOwned(naturalEnd = true)
            }
            is EngineEvent.Error -> {
                terminalGeneration = generation
                if (!renewSourceAfterFailure(event.failure)) {
                    failPlayback(event.failure)
                    checkpoint(force = true)
                }
            }
            is EngineEvent.Interruption -> interruption(event)
            is EngineEvent.StageReady -> handleStageReady(event)
            is EngineEvent.StageFailed -> if (stagedNext?.generation == event.nextGeneration) {
                cancelNext(suppress = true)
                mutableState.value = mutableState.value.copy(failure = event.failure)
            }
            is EngineEvent.CrossfadeStarted -> if (stagedNext?.generation == event.nextGeneration) {
                stagedNext = stagedNext?.copy(started = true, startedEpochMs = clock.epochMs())
                mutableState.value = mutableState.value.copy(crossfade = mutableState.value.crossfade?.copy(
                    phase = CrossfadePhase.OVERLAPPING, durationMs = event.durationMs, fraction = 0f,
                ))
            }
            is EngineEvent.CrossfadeProgress -> if (stagedNext?.generation == event.nextGeneration && stagedNext?.started == true && event.fraction.isFinite()) {
                mutableState.value = mutableState.value.copy(crossfade = mutableState.value.crossfade?.copy(fraction = event.fraction.coerceIn(0f, 1f)))
            }
            is EngineEvent.CrossfadeCompleted -> completeCrossfade(event)
            is EngineEvent.CrossfadeCancelled -> if (stagedNext?.generation == event.nextGeneration) cancelNext(suppress = true)
        }
    }

    private suspend fun interruption(event: EngineEvent.Interruption) {
        when (event.kind) {
            InterruptionKind.TRANSIENT_BEGAN -> {
                if (resumeToken == null && mutableState.value.desiredPlaying) resumeToken = ResumeToken(generation, intentRevision)
                pauseOwned(userIntent = false)
            }
            InterruptionKind.TRANSIENT_ENDED -> {
                val token = resumeToken
                resumeToken = null
                if (event.mayResume && token != null && token.generation == generation && token.intentRevision == intentRevision) {
                    mutableState.value = mutableState.value.copy(desiredPlaying = true)
                    if (preparedGeneration == generation) sendEngine { current -> engine.play(current) }
                }
            }
            InterruptionKind.PERMANENT, InterruptionKind.OUTPUT_REMOVED, InterruptionKind.MEDIA_SERVICES_RESET -> {
                pauseOwned(userIntent = true)
                if (event.kind == InterruptionKind.MEDIA_SERVICES_RESET) {
                    preparedGeneration = -1
                    mutableState.value = mutableState.value.copy(phase = PlaybackPhase.PAUSED, seekable = false, isPlaying = false)
                }
            }
        }
    }

    private suspend fun applySnapshot() {
        // Settings may have changed in a transaction whose revision our own checkpoint overtook.
        // Read live policy independently of observer message ordering.
        enforceOfflinePolicy()
        synchronizePodcastRate()
        val snapshot = store.state.value
        if (snapshot.revision <= lastSnapshotRevision) return
        lastSnapshotRevision = snapshot.revision
        val current = mutableState.value
        mutableState.value = current.copy(queues = snapshot.queues, currentItem = current.currentItem?.let { snapshot.media[it.id] ?: it })
        checkCrossfade()
    }

    private suspend fun synchronizePodcastRate() {
        val current = mutableState.value
        if (current.activeContext != QueueContext.PODCAST) return
        val item = current.currentItem?.takeIf { it.kind == MediaKind.EPISODE } ?: return
        val latest = store.state.value
        val requested = speedFor(item, latest, latest.queue(QueueContext.PODCAST))
        if (current.speed == requested && latest.queue(QueueContext.PODCAST).speed == requested) return
        // A defaults change must not use SetRate: that command deliberately clears the current
        // show's override and would instead change MUSIC when it is the active context.
        val updated = store.update { old ->
            val queue = old.queue(QueueContext.PODCAST)
            val effective = speedFor(old.media[item.id] ?: item, old, queue)
            if (queue.speed == effective) old
            else old.copy(queues = old.queues + (QueueContext.PODCAST to queue.copy(speed = effective)))
        }.getOrThrow()
        val effective = updated.queue(QueueContext.PODCAST).speed
        mutableState.value = mutableState.value.copy(speed = effective)
        if (current.speed != effective && preparedGeneration == generation) {
            sendEngine { token -> engine.setRate(effective, token) }
        }
    }

    private suspend fun enforceOfflinePolicy() {
        val offlineOnly = store.state.value.settings.offlineOnly
        val becameEnabled = offlineOnly && !lastOfflineOnly
        lastOfflineOnly = offlineOnly
        val current = mutableState.value
        if (offlineOnly && current.currentItem != null && !resolvedLocal && (becameEnabled || preparedGeneration == generation)) {
            pauseOwned(userIntent = true)
            stopTransport()
            mutableState.value = mutableState.value.copy(phase = PlaybackPhase.PAUSED, failure = AppFailure(FailureCode.OFFLINE_POLICY))
        }
    }

    private suspend fun drainPosition() {
        positions.tryReceive().getOrNull()?.let { if (initialized) handleEvent(it) }
    }

    private fun nextCrossfadeEntry(): QueueEntry? {
        val queue = store.state.value.queue(QueueContext.MUSIC)
        if (queue.currentEntryId != mutableState.value.currentEntryId || queue.repeat == RepeatMode.ONE) return null
        val next = queue.entries.getOrNull(queue.currentIndex + 1)
            ?: if (queue.repeat == RepeatMode.ALL) queue.entries.firstOrNull() else null
        return next?.takeUnless { it.id == queue.currentEntryId }
    }

    private suspend fun checkCrossfade() {
        val transport = engine as? CrossfadeAudioEngine ?: return
        val current = mutableState.value
        val settings = store.state.value.settings
        val duration = current.durationMs
        val next = nextCrossfadeEntry()
        val eligible = engine.capabilities.supportsCrossfade && settings.crossfadeEnabled &&
            current.activeContext == QueueContext.MUSIC && current.currentItem?.kind == MediaKind.MUSIC &&
            current.desiredPlaying && current.isPlaying && preparedGeneration == generation &&
            pendingSeekSequence == null && duration != null && duration > 0 && next != null
        if (!eligible) { if (stagedNext != null) cancelNext(); return }
        requireNotNull(duration)
        requireNotNull(next)
        val outgoingSpeed = current.effectiveSpeed.takeIf { it.isFinite() && it > 0 } ?: 1f
        val remainingMs = ((duration - current.positionMs).coerceAtLeast(0) / outgoingSpeed).toLong()
        val existing = stagedNext
        if (existing != null && (existing.entry.id != next.id || existing.requestedDurationMs != settings.crossfadeDurationMs)) {
            cancelNext()
        }
        if (stagedNext == null) {
            if (remainingMs < 100 || remainingMs > maxOf(15_000, settings.crossfadeDurationMs * 2) || suppressedNext == generation to next.id) return
            val item = store.state.value.media[next.mediaId] ?: return
            val token = reserveGeneration()
            val outgoing = generation
            stagedNext = StagedNext(token, next, item, settings.crossfadeDurationMs)
            mutableState.value = mutableState.value.copy(crossfade = CrossfadeState(CrossfadePhase.PREPARING, item, next.id))
            val offlineOnly = settings.offlineOnly
            nextResolveJob = launchResolution(item.id) {
                var unhanded: ResolvedPlayable? = null
                try {
                    val source = resolver.resolve(item, engine.capabilities, offlineOnly)
                    unhanded = source
                    ensureActive()
                    unhanded = null
                    messages.send(Message.NextResolved(outgoing, token, source))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { messages.send(Message.NextResolveFailed(outgoing, token, failureOf(error))) }
                finally { unhanded?.let(::discardSource) }
            }
            return
        }
        val staged = stagedNext ?: return
        if (!staged.ready || staged.beginRequested) return
        val incomingDuration = staged.durationMs ?: return
        // Short tracks never overlap more than half of either recording. Unknown durations do not fade.
        val maximumMs = minOf(settings.crossfadeDurationMs, (duration / outgoingSpeed / 2).toLong(),
            (incomingDuration / current.speed / 2).toLong())
        if (maximumMs < 100 || remainingMs < 100) { cancelNext(suppress = true); return }
        if (remainingMs > maximumMs) return
        val fadeMs = minOf(maximumMs, remainingMs)
        stagedNext = staged.copy(beginRequested = true)
        sendEngine { token -> transport.beginCrossfade(fadeMs, staged.generation, token) }
    }

    private suspend fun handleNextResolved(message: Message.NextResolved) {
        val source = message.source
        var handedToEngine = false
        try {
            val staged = stagedNext?.takeIf { it.generation == message.nextGeneration } ?: return
            if (source.mediaId != staged.item.id) {
                cancelNext(suppress = true)
                mutableState.value = mutableState.value.copy(failure = AppFailure(FailureCode.INVALID_STATE, "Incoming source identity differs"))
                return
            }
            if (store.state.value.settings.offlineOnly && !source.isLocal) { cancelNext(suppress = true); return }
            if (!engine.capabilities.supports(source.transport, source.mimeType, source.codecs) ||
                (source.headers.isNotEmpty() && !engine.capabilities.supportsRequestHeaders)) { cancelNext(suppress = true); return }
            val transport = engine as? CrossfadeAudioEngine ?: return
            stagedNext = staged.copy(source = source)
            mutableState.value = mutableState.value.copy(crossfade = mutableState.value.crossfade?.copy(incomingExecutionBinding = source.executionBinding))
            handedToEngine = true
            sendEngine(onDiscard = { discardSource(source) }) { token -> transport.preloadNext(source.copy(item = staged.item), 0, staged.generation, token) }
        } finally { if (!handedToEngine) discardSource(source) }
    }

    private suspend fun handleStageReady(event: EngineEvent.StageReady) {
        val staged = stagedNext?.takeIf { it.generation == event.nextGeneration } ?: return
        if (staged.ready) return
        if (event.durationMs == null || event.durationMs <= 0) { cancelNext(suppress = true); return }
        stagedNext = staged.copy(ready = true, durationMs = event.durationMs)
        mutableState.value = mutableState.value.copy(crossfade = mutableState.value.crossfade?.copy(phase = CrossfadePhase.READY))
    }

    private suspend fun cancelNext(suppress: Boolean = false, notifyEngine: Boolean = true) {
        nextResolveJob?.cancel()
        nextResolveJob = null
        val staged = stagedNext ?: return
        stagedNext = null
        if (suppress) suppressedNext = generation to staged.entry.id
        mutableState.value = mutableState.value.copy(crossfade = null)
        val transport = engine as? CrossfadeAudioEngine
        if (notifyEngine && transport != null && staged.source != null) {
            sendEngine { token -> transport.cancelNext(staged.generation, token) }
        }
    }

    private suspend fun completeCrossfade(event: EngineEvent.CrossfadeCompleted) {
        val staged = stagedNext?.takeIf { it.generation == event.nextGeneration && it.started && it.beginRequested } ?: return
        if (!mutableState.value.desiredPlaying || nextCrossfadeEntry()?.id != staged.entry.id) { cancelNext(); return }
        val transport = engine as? CrossfadeAudioEngine ?: return
        val outgoingGeneration = generation
        val incomingPosition = boundedPosition(event.positionMs, event.durationMs)
        val binding = staged.source?.executionBinding
        val snapshot = commit { old ->
            val queue = old.queue(QueueContext.MUSIC)
            require(queue.entries.any { it.id == staged.entry.id && it.mediaId == staged.item.id })
            val previous = old.progress[staged.item.id] ?: MediaProgress()
            val progress = previous.copy(positionMs = incomingPosition, durationMs = event.durationMs,
                updatedEpochMs = clock.epochMs(), sessionId = sessionId, generation = staged.generation,
                sequence = ++progressSequence, executionBindingId = binding?.id)
            val history = PlaybackHistoryEntry("$sessionId-${staged.generation}", staged.item.id,
                staged.startedEpochMs, incomingPosition, executionBindingId = binding?.id)
            old.copy(
                queues = old.queues + (QueueContext.MUSIC to queue.copy(currentEntryId = staged.entry.id, positionMs = incomingPosition)),
                progress = old.progress + (staged.item.id to progress),
                history = (old.history + history).takeLast(1_000),
            )
        }
        stagedNext = null
        nextResolveJob = null
        generation = staged.generation
        preparedGeneration = generation
        terminalGeneration = -1
        confirmedSeekSequence = 0
        seekSequence = 0
        pendingSeekSequence = null
        resolvedLocal = staged.source?.isLocal == true
        hasPlayedCurrent = true
        historyStartedMs = staged.startedEpochMs
        historyGeneration = generation
        sourceRenewals = 0
        requestedBindingId = binding?.id
        progressDirty = false
        suppressedNext = null
        mutableState.value = mutableState.value.copy(
            currentItem = snapshot.media.getValue(staged.item.id), currentEntryId = staged.entry.id,
            queues = snapshot.queues, generation = generation, positionMs = incomingPosition,
            durationMs = event.durationMs?.takeIf { it > 0 }, seekable = event.seekable,
            phase = if (event.isPlaying) PlaybackPhase.PLAYING else PlaybackPhase.READY, isPlaying = event.isPlaying,
            effectiveSpeed = event.effectiveSpeed, bufferedPositionMs = null, pendingSeekMs = null,
            crossfade = null, executionBinding = binding, failure = null,
        )
        // This queued operation is keyed to the NEW generation, but acknowledges the OLD native owner.
        sendEngine { transport.confirmCrossfade(staged.generation, outgoingGeneration) }
    }

    private suspend fun commit(transform: (AppSnapshot) -> AppSnapshot): AppSnapshot {
        val snapshot = store.update { old -> transform(checkpointSnapshot(old)) }.getOrThrow()
        lastSnapshotRevision = snapshot.revision
        mutableState.value = mutableState.value.copy(queues = snapshot.queues)
        progressDirty = false
        lastCheckpointMs = clock.monotonicMs()
        return snapshot
    }

    private suspend fun checkpoint(force: Boolean = false, naturalEnd: Boolean = false) {
        if (mutableState.value.currentItem == null || (!force && !progressDirty)) return
        lastCheckpointMs = clock.monotonicMs()
        val snapshot = store.update { old -> checkpointSnapshot(old, naturalEnd) }.getOrThrow()
        lastSnapshotRevision = snapshot.revision
        mutableState.value = mutableState.value.copy(queues = snapshot.queues)
        progressDirty = false
    }

    private fun checkpointSnapshot(old: AppSnapshot, naturalEnd: Boolean = false): AppSnapshot {
        val current = mutableState.value
        val item = current.currentItem ?: return old
        val queue = old.queue(current.activeContext)
        if (queue.currentEntryId != current.currentEntryId || item.id !in old.media) return old
        val previous = old.progress[item.id] ?: MediaProgress()
        val bindingId = current.executionBinding?.id ?: previous.executionBindingId
        val progress = previous.copy(
            positionMs = boundedPosition(current.positionMs, current.durationMs),
            durationMs = current.durationMs ?: previous.durationMs.takeIf { sameRecording(previous.executionBindingId, bindingId, old) },
            played = previous.played || (naturalEnd && item.kind == MediaKind.EPISODE),
            updatedEpochMs = clock.epochMs(),
            sessionId = sessionId,
            generation = generation,
            sequence = ++progressSequence,
            executionBindingId = bindingId,
        )
        val historyId = "$sessionId-$historyGeneration"
        val history = if (!hasPlayedCurrent) old.history else {
            val previouslyEnded = old.history.firstOrNull { it.id == historyId }?.endedNaturally == true
            val entry = PlaybackHistoryEntry(historyId, item.id, historyStartedMs, progress.positionMs, naturalEnd || previouslyEnded, current.executionBinding?.id)
            (old.history.filterNot { it.id == historyId } + entry).takeLast(1_000)
        }
        return old.copy(
            progress = old.progress + (item.id to progress),
            queues = old.queues + (current.activeContext to queue.copy(positionMs = progress.positionMs)),
            history = history,
        )
    }

    private suspend fun checkSleepTimer() {
        val deadline = durationDeadlineMs ?: return
        val remaining = (deadline - clock.monotonicMs()).coerceAtLeast(0)
        if (remaining == 0L) {
            clearTimer()
            pauseOwned(userIntent = true)
        } else mutableState.value = mutableState.value.copy(sleepTimer = SleepTimerState(SleepTimerMode.DURATION, remainingMs = remaining))
    }

    private fun clearTimer() {
        durationDeadlineMs = null
        mutableState.value = mutableState.value.copy(sleepTimer = null)
    }

    private suspend fun stopTransport() {
        cancelNext(notifyEngine = false)
        resolveJob?.cancel()
        generation = reserveGeneration()
        preparedGeneration = -1
        terminalGeneration = -1
        pendingSeekSequence = null
        resumeToken = null
        mutableState.value = mutableState.value.copy(generation = generation, desiredPlaying = false, isPlaying = false, pendingSeekMs = null, seekable = false)
        val token = generation
        sendEngine(releaseBarrier = true) { stopNative(token) }
    }

    private suspend fun failPlayback(failure: AppFailure) {
        resumeToken = null
        stopTransport() // An engine failure is not proof that native audio has stopped.
        mutableState.value = mutableState.value.copy(phase = PlaybackPhase.ERROR, desiredPlaying = false, isPlaying = false, failure = failure)
    }

    /** Bounded URL renewal reuses the immutable receipt; it never searches for a different result. */
    private suspend fun renewSourceAfterFailure(failure: AppFailure): Boolean {
        val current = mutableState.value
        val binding = current.executionBinding ?: return false
        if (!current.desiredPlaying || resolvedLocal || store.state.value.settings.offlineOnly || sourceRenewals >= 2) return false
        if (failure.code !in setOf(FailureCode.NETWORK_UNAVAILABLE, FailureCode.PROVIDER_ACCESS_REQUIRED,
                FailureCode.RATE_LIMITED, FailureCode.NOT_FOUND)) return false
        if ((failure.retryAfterMs ?: 0) > 30_000) return false // Do not violate a long provider retry delay.
        checkpoint(force = true)
        sourceRenewals++
        val delayMs = maxOf(1_000L * sourceRenewals, (failure.retryAfterMs ?: 0).coerceAtLeast(0))
        activate(store.state.value, desiredPlaying = true, manual = false, reuseBindingId = binding.id, renewalDelayMs = delayMs)
        return true
    }

    private suspend fun sendEngine(releaseBarrier: Boolean = false, onDiscard: () -> Unit = {}, action: suspend (Long) -> Unit) {
        val token = generation
        engineOperations.send(EngineOperation(token, releaseBarrier, onDiscard) { action(token) })
    }

    private fun launchResolution(mediaId: String, block: suspend CoroutineScope.() -> Unit): Job {
        resolutions.removeAll { it.job.isCompleted }
        return ownerScope.launch(block = block).also { resolutions += MediaResolution(mediaId, it) }
    }

    private suspend fun stopNative(token: Long) {
        try {
            engine.stop(token)
            nativeReleaseFailure = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val failure = failureOf(error)
            nativeReleaseFailure = failure
            messages.send(Message.NativeReleaseFailed(token, failure))
        }
    }

    private fun releaseSuperseded() = PlaybackResult.Failure(AppFailure(FailureCode.INVALID_STATE, "Native release superseded"))

    private fun discardSource(source: ResolvedPlayable) { source.resource?.let { resourceCleanup.trySend(ResourceCleanup.Close(it)) } }

    private fun discardMessage(message: Message) {
        when (message) {
            is Message.Resolved -> discardSource(message.source)
            is Message.NextResolved -> discardSource(message.source)
            is Message.AssetResolversReleased -> message.result.complete(PlaybackResult.Failure(AppFailure(FailureCode.CANCELLED)))
            else -> Unit
        }
    }

    private fun resumePosition(item: MediaItem, snapshot: AppSnapshot): Long {
        val progress = snapshot.progress[item.id]
        return if (item.kind == MediaKind.EPISODE && progress?.played != true) (progress?.positionMs ?: 0).coerceAtLeast(0) else 0
    }

    private fun sameRecording(firstBindingId: String?, secondBindingId: String?, snapshot: AppSnapshot): Boolean {
        if (firstBindingId == secondBindingId) return true
        val video = firstBindingId?.let { snapshot.playbackBindings[it]?.selectedVideoId } ?: return false
        return video == secondBindingId?.let { snapshot.playbackBindings[it]?.selectedVideoId }
    }

    private fun reserveGeneration(): Long {
        check(generationCounter < Long.MAX_VALUE)
        return ++generationCounter
    }

    private fun speedFor(item: MediaItem, snapshot: AppSnapshot, queue: QueueSnapshot): Float =
        if (item.kind == MediaKind.EPISODE) snapshot.settings.showSpeeds[item.showId] ?: snapshot.settings.speed else queue.speed

    private fun contextFor(item: MediaItem): QueueContext = if (item.kind == MediaKind.EPISODE) QueueContext.PODCAST else QueueContext.MUSIC
    private fun boundedPosition(position: Long, duration: Long?): Long = position.coerceAtLeast(0).let { if (duration != null && duration > 0) it.coerceAtMost(duration) else it }
    private fun failureOf(error: Exception): AppFailure = when (error) {
        is VeyraException -> error.failure
        is IllegalArgumentException -> AppFailure(FailureCode.INVALID_STATE)
        else -> AppFailure(FailureCode.UNEXPECTED)
    }

    private data class ResumeToken(val generation: Long, val intentRevision: Long)
    private data class StagedNext(
        val generation: Long,
        val entry: QueueEntry,
        val item: MediaItem,
        val requestedDurationMs: Long,
        val source: ResolvedPlayable? = null,
        val durationMs: Long? = null,
        val ready: Boolean = false,
        val beginRequested: Boolean = false,
        val started: Boolean = false,
        val startedEpochMs: Long = 0,
    )
    private data class EngineOperation(val generation: Long, val releaseBarrier: Boolean, val discard: () -> Unit, val action: suspend () -> Unit)
    private data class MediaResolution(val mediaId: String, val job: Job)
    private sealed interface ResourceCleanup {
        data class Close(val resource: PlaybackResource) : ResourceCleanup
        data class Fence(val result: CompletableDeferred<AppFailure?>) : ResourceCleanup
    }
    private sealed interface Message {
        data class Initialize(val result: CompletableDeferred<PlaybackResult>) : Message
        data class Command(val command: PlaybackCommand, val result: CompletableDeferred<PlaybackResult>) : Message
        data class Shutdown(val result: CompletableDeferred<PlaybackResult>) : Message
        data class ReleaseAsset(val mediaId: String, val result: CompletableDeferred<PlaybackResult>) : Message
        data class AssetResolversReleased(val generation: Long, val result: CompletableDeferred<PlaybackResult>) : Message
        data class NativeReleaseFailed(val generation: Long, val failure: AppFailure) : Message
        data class Event(val event: EngineEvent) : Message
        data class Resolved(val generation: Long, val source: ResolvedPlayable) : Message
        data class NextResolved(val generation: Long, val nextGeneration: Long, val source: ResolvedPlayable) : Message
        data class NextResolveFailed(val generation: Long, val nextGeneration: Long, val failure: AppFailure) : Message
        data class ResolveFailed(val generation: Long, val failure: AppFailure) : Message
        data object EventStreamFailed : Message
        data object ResourceCleanupFailed : Message
        data class SnapshotChanged(val snapshot: AppSnapshot) : Message
        data object Tick : Message
    }
}
