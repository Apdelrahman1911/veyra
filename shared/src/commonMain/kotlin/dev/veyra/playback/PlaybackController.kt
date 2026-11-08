package dev.veyra.playback

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.source.SourceResolver
import dev.veyra.source.SourceReaderOwner
import dev.veyra.source.execution.AcquisitionExecutionContext
import dev.veyra.source.execution.AcquisitionPlaybackSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
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
    private val acquisition: PlaybackAcquisitionServices? = null,
) {
    // Reject a composition error before attaching any child Job or accepting a resource owner.
    init {
        require(checkpointIntervalMs > 0)
        require(acquisition == null || acquisition.repository.transactionOwner === store.transactionOwner) {
            "Playback requires the application's existing Room owner"
        }
    }
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + job + dispatcher)
    // Cleanup outlives owner cancellation, but is drained before close returns. Channel ownership
    // covers prompt-cancellation/undelivered-element races as well as stale generations.
    private val resourceCleanupScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val resourceCleanup = Channel<ResourceCleanup>(Channel.UNLIMITED)
    private val cleanupCompletion = CompletableDeferred<Throwable?>()
    // Strong ownership survives a failed/throwing close and even a rejected cleanup-channel send.
    // This is a maintenance/shutdown veto, not permission to stop an unrelated current transport.
    private val failedResourceOwners = MutableStateFlow<List<PlaybackResource>>(emptyList())
    private val nativeCloseLock = Mutex()
    private val nativeCloseCompletion = CompletableDeferred<Throwable?>()
    private val closeLock = Mutex()
    private val closeCompletion = CompletableDeferred<Throwable?>()
    private val acknowledgedClose = MutableStateFlow(false)
    /** Positive completed native/resource/resolver drain only; a thrown close never grants it. */
    val dependencyReleaseSafeAfterClose: Boolean get() = acknowledgedClose.value
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
    // Actor-owned. A new matching resolve invalidates an outstanding removal even when it both
    // starts and retires during the await without advancing the current transport generation.
    private val assetReleases = mutableSetOf<AssetRelease>()
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
    // Actor-confined policy/state only: no second scope, actor, engine, scheduler or source client.
    private val typed = acquisition?.let { AcquisitionRuntime(it) }

    init {
        cleanupJob = resourceCleanupScope.launch {
            var cleanupFailure: Throwable? = null
            try {
                for (work in resourceCleanup) {
                    when (work) {
                        is ResourceCleanup.Close -> try {
                            // A failed owner is retained, not automatically retried by another fence.
                            if (failedResourceOwners.value.none { it === work.resource }) work.resource.close()
                        }
                        catch (error: Throwable) {
                            // Even a resource which throws cancellation must not skip later closes.
                            // Preserve that cancellation for close(), rather than losing it in a Job.
                            cleanupFailure = combineCloseFailures(cleanupFailure,
                                closeFailureOf(error, "Playback resource cleanup failed"))
                            retainFailedResource(work.resource)
                        }
                        is ResourceCleanup.Fence -> work.result.complete(cleanupFailure?.let {
                            AppFailure(FailureCode.UNEXPECTED, "Playback resource cleanup failed")
                        } ?: retainedResourceFailure())
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
                    } catch (error: Throwable) {
                        val scoped = operation.scopedFailure
                        if (scoped != null) {
                            // Removal/retired-reader failures are NOT transport failures. In
                            // particular, don't poison nativeReleaseFailure and disable B's controls.
                            scoped(error)
                            if (error is CancellationException && !currentCoroutineContext().isActive) throw error
                        } else {
                            if (error is CancellationException) throw error
                            if (error !is Exception) throw error
                            val failure = failureOf(error)
                            nativeReleaseFailure = failure
                            messages.send(Message.Event(EngineEvent.Error(operation.generation, failure)))
                        }
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
    suspend fun releaseForAssetRemoval(mediaId: String): PlaybackResult = request { Message.ReleaseAsset(ReaderTarget.Catalog(mediaId), it) }

    /** Exact physical target for schema3 cleanup, without manufacturing a catalogue mediaId. */
    suspend fun releaseForMaterializationRemoval(asset: MaterializationAsset): PlaybackResult =
        request { Message.ReleaseAsset(ReaderTarget.Materialization(asset), it) }

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
                        closingFailure = combineCloseFailures(closingFailure, cleanupCompletion.await()
                            ?: retainedResourceFailure()?.let(::VeyraException))
                        try { acquisition?.resolver?.close() }
                        catch (error: Throwable) {
                            closingFailure = combineCloseFailures(closingFailure,
                                closeFailureOf(error, "Playback local resolver cleanup failed"))
                        }
                        try { (resolver as? SourceReaderOwner)?.closeReaders() }
                        catch (error: Throwable) {
                            closingFailure = combineCloseFailures(closingFailure,
                                closeFailureOf(error, "Legacy source reader cleanup failed"))
                        }
                        closeCompletion.complete(closingFailure)
                        if (closingFailure == null) acknowledgedClose.value = true
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
                try { releaseAssetOwned(message.target, message.result) }
                catch (cancelled: CancellationException) { message.result.complete(PlaybackResult.Failure(AppFailure(FailureCode.CANCELLED))); throw cancelled }
                catch (error: Exception) { message.result.complete(PlaybackResult.Failure(failureOf(error))) }
            }
            is Message.AssetResolversReleased -> {
                if (message.release.generation != generation) completeAssetRelease(message.release, releaseSuperseded())
                else finishAssetRelease(message.release)
            }
            is Message.AssetNativeReleased -> completeAssetRelease(message.release, message.outcome)
            is Message.NativeReleaseFailed -> if (message.generation == generation) {
                // A failed stop must not recursively produce another failed stop forever. Keep
                // deletion vetoed and reject ordinary native work until an explicit retry stops it.
                resolveJob?.cancel()
                cancelNext(notifyEngine = false)
                typed?.retireCurrentLoad()
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
                if (typed == null) handleResolved(message.source) else discardSource(message.source)
            } else discardSource(message.source)
            is Message.Schema2AcquisitionResolved -> if (initialized && message.generation == generation && typed != null) safely {
                typed.handleSchema2Resolved(message)
            } else discardSource(message.source)
            is Message.SelectionResolved -> if (initialized && message.generation == generation) safely {
                typed?.selectionResolved(message)
            }
            is Message.AcquisitionResolved -> if (initialized && message.generation == generation && typed != null) safely {
                typed.handleResolved(message)
            } else discardSource(message.prepared.source)
            is Message.AcquisitionResolveFailed -> if (initialized && message.generation == generation && typed != null) safely {
                if (message.nextGeneration == null) failPlayback(message.failure)
                else if (typed.next?.generation == message.nextGeneration) {
                    cancelNext(suppress = true)
                    mutableState.value = mutableState.value.copy(failure = message.failure)
                }
            }
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
                    checkpoint(force = true)
                    failPlayback(AppFailure(FailureCode.UNEXPECTED, "Native event stream closed"))
                }
            }
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
        if (store.requiresTypedPlayback && typed == null) {
            throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "schema3_playback_services_required"))
        }
        val snapshot = store.initialize().getOrThrow()
        if (typed != null) {
            typed.initialize()
            initialized = true
            applySnapshot()
            return
        }
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
        if (typed?.handleCommand(command) == true) return
        when (command) {
            is PlaybackCommand.AdmitAcquisition, is PlaybackCommand.PlayOccurrence ->
                throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "typed_playback_services_required"))
            is PlaybackCommand.Enqueue, is PlaybackCommand.Remove, is PlaybackCommand.Move,
            is PlaybackCommand.ClearUpcoming, is PlaybackCommand.UndoQueueEdit,
            is PlaybackCommand.SetShuffle, is PlaybackCommand.SetRepeat -> cancelNext()
            else -> Unit // Native pause/seek/stop paths cancel immediately, without a preceding ramp.
        }
        when (command) {
            is PlaybackCommand.AdmitAcquisition, is PlaybackCommand.PlayOccurrence ->
                throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "typed_playback_services_required"))
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
            is PlaybackCommand.SkipConfigured -> {
                requireExpectedOccurrence(command.expectedEntryId, command.expectedGeneration)
                val settings = store.state.value.settings
                val offset = if (command.forward) settings.skipForwardSeconds * 1_000L else -settings.skipBackSeconds * 1_000L
                val position = mutableState.value.positionMs
                val target = if (offset > 0 && position > Long.MAX_VALUE - offset) Long.MAX_VALUE else position + offset
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
            PlaybackCommand.SleepAtEndOfEpisode -> sleepAtCurrentEnd()
            is PlaybackCommand.SleepAtRecordingEnd -> {
                requireExpectedOccurrence(command.expectedEntryId, command.expectedGeneration)
                sleepAtCurrentEnd()
            }
            PlaybackCommand.CancelSleepTimer -> clearTimer()
            is PlaybackCommand.MarkPlayed -> store.markPlayed(command.mediaId, command.played, clock.epochMs()).getOrThrow()
            is PlaybackCommand.ResetProgress -> resetProgress(command.mediaId)
            PlaybackCommand.Stop -> {
                pauseOwned(true)
                stopTransport()
                mutableState.value = mutableState.value.copy(phase = if (!mutableState.value.hasCurrent) PlaybackPhase.IDLE else PlaybackPhase.PAUSED)
            }
            PlaybackCommand.FlushProgress -> checkpoint(force = true)
        }
        mutableState.value = mutableState.value.copy(undoableQueueContexts = typed?.undo?.keys?.toSet() ?: undoQueues.keys.toSet())
    }

    private fun sleepAtCurrentEnd() {
        require(mutableState.value.controlsMode == PlaybackControlsMode.PODCAST)
        durationDeadlineMs = null
        mutableState.value = mutableState.value.copy(sleepTimer = SleepTimerState(SleepTimerMode.END_OF_EPISODE,
            boundEntryId = requireNotNull(mutableState.value.currentEntryId)))
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
        if (typed != null) { typed.resume(); return }
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

    private suspend fun releaseAssetOwned(target: ReaderTarget, result: CompletableDeferred<PlaybackResult>) {
        if (target is ReaderTarget.Catalog) require(target.mediaId.isNotBlank())
        assetReleases.removeAll { it.result.isCompleted }
        initializeOwned()
        drainPosition()
        val active = target.matches(currentReader())
        val staged = typed?.stagedReader()?.takeIf { target.matches(it.identity) }
            ?: stagedNext?.takeIf { target.matches(ReaderIdentity(mediaId = it.item.id)) }
                ?.let { StagedReader(it.generation, ReaderIdentity(mediaId = it.item.id), it.source != null) }
        val pendingResolves = resolutions.filter { target.matches(it.identity) }.map { it.job }
        pendingResolves.forEach { it.cancel() }
        if (active) {
            pauseOwned(userIntent = true)
            stopTransport()
            mutableState.value = mutableState.value.copy(phase = PlaybackPhase.PAUSED)
        } else if (staged != null) cancelNext(suppress = true, notifyEngine = false)
        val transport = engine as? CrossfadeAudioEngine
        val stageCleanup = if (!active && staged?.hasSource == true && transport != null) CompletableDeferred<AppFailure?>() else null
        val release = AssetRelease(target, generation, result, stageCleanup)
        assetReleases += release
        if (stageCleanup != null && staged != null && transport != null) {
            // Targeted cancellation is scoped too: a failed retired A close must not produce
            // Error(current B) before the subsequent native-reader barrier even gets a turn.
            sendEngine(releaseBarrier = true,
                onDiscard = { stageCleanup.complete(releaseSuperseded().failure) },
                scopedFailure = { stageCleanup.complete(scopedFailureOf(it)) }) { token ->
                transport.cancelNext(staged.generation, token)
                stageCleanup.complete(null)
            }
        }
        // Do not join in the actor or native worker. A resolver may have already queued a source
        // message. After all matching jobs finish, this FIFO roundtrip ensures those messages have
        // been handled/discarded before the native + controller-resource cleanup fences are queued.
        ownerScope.launch {
            try {
                pendingResolves.forEach { it.join() }
                messages.send(Message.AssetResolversReleased(release))
            } catch (cancelled: CancellationException) {
                postAssetRelease(release, PlaybackResult.Failure(AppFailure(FailureCode.CANCELLED)))
                throw cancelled
            } catch (error: Throwable) {
                postAssetRelease(release, PlaybackResult.Failure(scopedFailureOf(error)))
            }
        }
        resolutions.removeAll { it.job.isCompleted }
    }

    private suspend fun finishAssetRelease(release: AssetRelease) {
        // Always enqueue this fence, including retired/noncurrent media. Its native stop/cancel/ACK
        // may still be in flight even though the actor already changed currentItem/stagedNext.
        sendEngine(releaseBarrier = true,
            onDiscard = { postAssetRelease(release, releaseSuperseded()) },
            scopedFailure = { postAssetRelease(release, PlaybackResult.Failure(scopedFailureOf(it))) }) { token ->
            val cancellationFailure = release.stageCleanup?.await()
            val nativeFailure = when (val drained = engine.awaitRetiredReaders(token)) {
                is NativeReaderDrainResult.Drained -> if (drained.expectedGeneration == token) null else releaseSuperseded().failure
                NativeReaderDrainResult.Superseded -> releaseSuperseded().failure
                is NativeReaderDrainResult.Unacknowledged -> drained.failure
            }
            val cleaned = CompletableDeferred<AppFailure?>()
            resourceCleanup.send(ResourceCleanup.Fence(cleaned))
            val controllerFailure = cleaned.await()
            val validationReaderFailure = acquisition?.resolver?.retainedReaderFailure()
            val legacyReaderFailure = (resolver as? SourceReaderOwner)?.retainedReaderFailure()
            val failure = cancellationFailure ?: nativeFailure ?: controllerFailure ?: validationReaderFailure ?: legacyReaderFailure ?: nativeReleaseFailure
            val outcome = when {
                token != mutableState.value.generation -> releaseSuperseded()
                failure != null -> PlaybackResult.Failure(failure)
                else -> PlaybackResult.Accepted
            }
            // No success directly from the native worker. Same-generation controller staging
            // and resolver admission can change while either independent cleanup owner awaits.
            messages.send(Message.AssetNativeReleased(release, outcome))
        }
    }

    /** Only the controller actor may make the final target-ownership decision. */
    private fun completeAssetRelease(release: AssetRelease, outcome: PlaybackResult) {
        if (!assetReleases.remove(release)) return
        val current = mutableState.value
        val targetPreparedOrResolving = release.target.matches(currentReader()) &&
            (preparedGeneration == generation || current.phase in setOf(PlaybackPhase.RESOLVING, PlaybackPhase.PREPARING) ||
                current.desiredPlaying || current.isPlaying)
        val superseded = release.generation != generation || release.readerAdmissionChanged ||
            release.target.matches(typed?.stagedReader()?.identity) ||
            release.target.matches(stagedNext?.let { ReaderIdentity(mediaId = it.item.id) }) || targetPreparedOrResolving ||
            resolutions.any { release.target.matches(it.identity) && !it.job.isCompleted }
        release.result.complete(if (outcome is PlaybackResult.Accepted && superseded) releaseSuperseded() else outcome)
    }

    /** Failure/discard callbacks cannot suspend; a full/closed actor mailbox is never an ACK. */
    private fun postAssetRelease(release: AssetRelease, outcome: PlaybackResult) {
        if (!messages.trySend(Message.AssetNativeReleased(release, outcome)).isSuccess) {
            release.result.complete(PlaybackResult.Failure(AppFailure(FailureCode.CANCELLED)))
        }
    }

    private suspend fun nextOwned(naturalEnd: Boolean) {
        if (typed != null) { typed.nextOwned(naturalEnd); return }
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
        if (typed != null) { typed.previous(); return }
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
        require(current.hasCurrent && current.seekable && preparedGeneration == generation)
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
        if (typed != null) { typed.setRate(speed, forThisShow); return }
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
        if (typed != null) { typed.catalogProgress(mediaId, played = false, reset = true); return }
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
        if (event.generation != generation || !mutableState.value.hasCurrent) return
        if (typed?.handleStageEvent(event) == true) return
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
                if (typed != null && preparedGeneration != generation) return
                if (terminalGeneration == generation || pendingSeekSequence != null || event.seekSequence != confirmedSeekSequence) return
                val duration = event.durationMs?.takeIf { it > 0 } ?: mutableState.value.durationMs
                mutableState.value = mutableState.value.copy(
                    positionMs = boundedPosition(event.positionMs, duration),
                    durationMs = duration,
                    bufferedPositionMs = event.bufferedPositionMs?.let { boundedPosition(it, duration) },
                )
                typed?.observedNativePosition()
                progressDirty = true
            }
            is EngineEvent.SeekCompleted -> {
                if (typed != null && preparedGeneration != generation) return
                if (event.seekSequence != pendingSeekSequence || terminalGeneration == generation) return
                pendingSeekSequence = null
                if (event.success) {
                    confirmedSeekSequence = event.seekSequence
                    mutableState.value = mutableState.value.copy(positionMs = boundedPosition(event.positionMs, mutableState.value.durationMs), pendingSeekMs = null)
                    typed?.observedNativePosition()
                    progressDirty = true
                    checkpoint(force = true)
                } else mutableState.value = mutableState.value.copy(pendingSeekMs = null, failure = AppFailure(FailureCode.UNEXPECTED, "Seek was not completed"))
            }
            is EngineEvent.Ended -> {
                if (typed != null && preparedGeneration != generation) return
                if (terminalGeneration == generation || pendingSeekSequence != null) return
                terminalGeneration = generation
                val current = mutableState.value
                mutableState.value = current.copy(isPlaying = false, phase = PlaybackPhase.ENDED, positionMs = current.durationMs ?: current.positionMs)
                typed?.observedNativePosition()
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
                    checkpoint(force = true)
                    failPlayback(event.failure)
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
        if (typed != null) { typed.synchronize(); return }
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
        if (offlineOnly && current.hasCurrent && !resolvedLocal && (becameEnabled || preparedGeneration == generation)) {
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
        if (typed != null) { typed.checkCrossfade(); return }
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
        if (typed != null) { typed.cancelNext(suppress, notifyEngine); return }
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
        if (typed != null) { typed.checkpoint(force, naturalEnd); return }
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
        typed?.retireCurrentLoad()
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
        if (typed != null) return typed.renew(failure)
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

    private suspend fun sendEngine(
        releaseBarrier: Boolean = false,
        onDiscard: () -> Unit = {},
        scopedFailure: ((Throwable) -> Unit)? = null,
        action: suspend (Long) -> Unit,
    ) {
        val token = generation
        engineOperations.send(EngineOperation(token, releaseBarrier, onDiscard, scopedFailure) { action(token) })
    }

    private fun launchResolution(mediaId: String, block: suspend CoroutineScope.() -> Unit): Job {
        return launchReaderResolution(ReaderIdentity(mediaId = mediaId), block)
    }

    private fun launchReaderResolution(identity: ReaderIdentity, block: suspend CoroutineScope.() -> Unit): Job {
        assetReleases.removeAll { it.result.isCompleted }
        assetReleases.filter { it.target.matches(identity) }.forEach { it.readerAdmissionChanged = true }
        resolutions.removeAll { it.job.isCompleted }
        return ownerScope.launch(block = block).also { resolutions += MediaResolution(identity, it) }
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

    private fun scopedFailureOf(error: Throwable): AppFailure = when (error) {
        is CancellationException -> AppFailure(FailureCode.CANCELLED)
        is Exception -> failureOf(error)
        else -> AppFailure(FailureCode.UNEXPECTED, "Native reader drain unacknowledged")
    }

    private fun retainFailedResource(resource: PlaybackResource) {
        failedResourceOwners.update { previous -> if (previous.any { it === resource }) previous else previous + resource }
    }

    private fun retainedResourceFailure(): AppFailure? = if (failedResourceOwners.value.isEmpty()) null
        else AppFailure(FailureCode.UNEXPECTED, "Playback resource cleanup failed")

    private fun discardSource(source: ResolvedPlayable) {
        source.resource?.let { resource ->
            if (!resourceCleanup.trySend(ResourceCleanup.Close(resource)).isSuccess) retainFailedResource(resource)
        }
    }

    private fun discardSource(source: AcquisitionPlaybackSource) {
        source.resource?.let { resource ->
            if (!resourceCleanup.trySend(ResourceCleanup.Close(resource)).isSuccess) retainFailedResource(resource)
        }
    }

    private fun discardMessage(message: Message) {
        when (message) {
            is Message.Resolved -> discardSource(message.source)
            is Message.NextResolved -> discardSource(message.source)
            is Message.AcquisitionResolved -> discardSource(message.prepared.source)
            is Message.Schema2AcquisitionResolved -> discardSource(message.source)
            is Message.AssetResolversReleased -> message.release.result.complete(PlaybackResult.Failure(AppFailure(FailureCode.CANCELLED)))
            is Message.AssetNativeReleased -> message.release.result.complete(PlaybackResult.Failure(AppFailure(FailureCode.CANCELLED)))
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
        is LocalMediaValidationReaderUnacknowledged -> AppFailure(FailureCode.STORAGE_IO, "local_validation_reader_unacknowledged")
        is IllegalArgumentException -> AppFailure(FailureCode.INVALID_STATE)
        else -> AppFailure(FailureCode.UNEXPECTED)
    }

    private enum class AcquisitionPosition { QUEUE, RESUME, ZERO }
    private class AcquisitionLoad(
        val outgoingGeneration: Long,
        val generation: Long,
        val queue: ExecutionQueueExpectation,
        val occurrenceId: ExecutionOccurrenceId,
        val positionPolicy: AcquisitionPosition,
        val queuePositionMs: Long,
        val expectedBindingId: ExecutionBindingId?,
        val renewalDelayMs: Long? = null,
        val requiredRepresentationId: String? = null,
    ) {
        val isStage: Boolean get() = generation != outgoingGeneration
        var selection: PlaybackSelection? = null // Actor-owned after exact current-queue rejoin.
        var admitted: OccurrenceCheckpoint? = null
        var admittedOutgoing: OccurrenceCheckpoint? = null // A stage also reserves the current native epoch.
    }
    private data class AcquisitionStage(
        val load: AcquisitionLoad,
        val requestedDurationMs: Long,
        val prepared: AcquisitionPlaybackPreparation? = null,
        val legacySource: ResolvedPlayable? = null,
        val durationMs: Long? = null,
        val ready: Boolean = false,
        val beginRequested: Boolean = false,
        val started: Boolean = false,
        val startedEpochMs: Long = 0,
    ) {
        val generation: Long get() = load.generation
        val hasSource: Boolean get() = prepared != null || legacySource != null
    }

    /** Actor-local schema3 policy. All jobs, resources, native commands and events use this controller. */
    private inner class AcquisitionRuntime(private val services: PlaybackAcquisitionServices) {
        private lateinit var view: PlaybackAcquisitionView
        val undo = mutableMapOf<QueueContext, ExecutionQueue>()
        private var currentLoad: AcquisitionLoad? = null
        var next: AcquisitionStage? = null
            private set
        private var materialization: MaterializationAsset? = null
        private var representationId: String? = null
        private var hasNativePosition = false

        suspend fun initialize() {
            val restored = services.repository.playbackView()
            publish(restored)
            val selection = mutableState.value.currentSelection
            val queue = restored.acquisition.queues.getValue(restored.snapshot.activeContext)
            val prior = selection?.let { sameBindingCheckpoint(it, restored) }
            mutableState.value = mutableState.value.copy(initialized = true,
                viewedContext = queue.context, phase = if (selection == null) PlaybackPhase.IDLE else PlaybackPhase.PAUSED,
                desiredPlaying = false, isPlaying = false, positionMs = queue.positionMs,
                durationMs = prior?.measuredDurationMs?.takeIf { it > 0 }, speed = queue.speed)
            lastOfflineOnly = restored.snapshot.settings.offlineOnly
            // Restore never selects, prepares, leases a local file, or trusts persisted playing state.
        }

        private fun publish(latest: PlaybackAcquisitionView) {
            view = latest
            lastSnapshotRevision = latest.snapshot.revision
            val selections = latest.acquisition.queues.values.flatMap { it.displayedOrder }.distinct()
                .associateWith { PlaybackSelection.project(latest.library, it) }
            val context = latest.snapshot.activeContext
            val id = latest.acquisition.queues.getValue(context).currentOccurrenceId
            val selected = id?.let { selections.getValue(it) }
            mutableState.value = mutableState.value.copy(activeContext = context,
                queues = latest.snapshot.queues, executionQueues = latest.acquisition.queues,
                queueSelections = selections, currentSelection = selected, currentItem = selected?.catalogMedia,
                currentEntryId = id?.value, executionBinding = selected?.takeIf { it.target is PlaybackTarget.Schema2 }
                    ?.bindingId?.value?.let { latest.snapshot.playbackBindings[it] },
                undoableQueueContexts = undo.keys.toSet())
        }

        private suspend fun read(): PlaybackAcquisitionView {
            val latest = services.repository.playbackView()
            reconcileExternalSelection(latest)
            publish(latest)
            return latest
        }
        private suspend fun apply(command: AcquisitionPlaybackCommand): PlaybackAcquisitionView =
            services.repository.applyPlayback(command).also {
                publish(it)
                // Stage/load reservation alone is not an outgoing checkpoint. In particular a
                // slow incoming selection must not make the current dirty native sample vanish.
                val checkpointed = when (command) {
                    is AcquisitionPlaybackCommand.AdmitQueue -> command.outgoing != null
                    is AcquisitionPlaybackCommand.Activate -> command.outgoing != null
                    is AcquisitionPlaybackCommand.EditQueue -> command.outgoing != null
                    is AcquisitionPlaybackCommand.CatalogProgress -> command.outgoing != null
                    is AcquisitionPlaybackCommand.Checkpoint, is AcquisitionPlaybackCommand.Handover -> true
                    is AcquisitionPlaybackCommand.AdmitLoad, is AcquisitionPlaybackCommand.AdmitStage -> false
                }
                if (checkpointed) {
                    progressDirty = false
                    lastCheckpointMs = clock.monotonicMs()
                }
            }

        /** Never relabel still-playing A with an externally selected B during a read barrier. */
        private suspend fun reconcileExternalSelection(latest: PlaybackAcquisitionView) {
            val load = currentLoad ?: return
            val queue = latest.acquisition.queues.getValue(latest.snapshot.activeContext)
            val occurrence = queue.currentOccurrenceId?.let { latest.acquisition.occurrences[it] }
            val replaced = latest.snapshot.activeContext != load.queue.context || queue.currentOccurrenceId != load.occurrenceId ||
                load.selection?.bindingId?.let { it != occurrence?.bindingId } == true
            if (replaced) {
                stopTransport()
                mutableState.value = mutableState.value.copy(phase = if (occurrence == null) PlaybackPhase.IDLE else PlaybackPhase.PAUSED,
                    positionMs = queue.positionMs, durationMs = null,
                    failure = AppFailure(FailureCode.INVALID_STATE, "playback_queue_changed_outside_owner"))
            }
        }

        private suspend fun readQueue(context: QueueContext, expectedRevision: Long?): ExecutionQueue {
            read()
            val queue = view.acquisition.queues.getValue(context)
            if (expectedRevision != null && queue.editRevision != expectedRevision) invalid("playback_queue_action_stale")
            return queue
        }

        private fun expected(queue: ExecutionQueue) = ExecutionQueueExpectation(queue.context, queue.editRevision, queue.currentOccurrenceId)
        private fun currentQueue() = view.acquisition.queues.getValue(mutableState.value.activeContext)
        private fun queuePosition(queue: ExecutionQueue): Long = if (queue.context == mutableState.value.activeContext &&
            queue.currentOccurrenceId?.value == mutableState.value.currentEntryId) mutableState.value.positionMs else queue.positionMs
        private fun remembered(queue: ExecutionQueue) = queue.copy(positionMs = queuePosition(queue))
        private fun identity(selection: PlaybackSelection?, asset: MaterializationAsset? = null): ReaderIdentity? = selection?.let {
            ReaderIdentity(it.catalogMedia?.id, it.subjectId, it.binding?.recordingId, asset?.id)
        }
        fun currentReader(): ReaderIdentity? = identity(currentLoad?.selection ?: mutableState.value.currentSelection, materialization)
        fun stagedReader(): StagedReader? = next?.let {
            identity(it.load.selection ?: mutableState.value.queueSelections[it.load.occurrenceId], it.prepared?.validatedMaterialization)
                ?.let { reader -> StagedReader(it.generation, reader, it.hasSource) }
        }
        fun retireCurrentLoad() { currentLoad = null; materialization = null; representationId = null; hasNativePosition = false }
        fun observedNativePosition() { hasNativePosition = true }

        suspend fun handleCommand(command: PlaybackCommand): Boolean {
            when (command) {
                is PlaybackCommand.AdmitAcquisition -> admit(command)
                is PlaybackCommand.PlayOccurrence -> playOccurrence(command)
                is PlaybackCommand.Play -> {
                    require(command.items.isNotEmpty() && command.startIndex in command.items.indices &&
                        command.items.all { contextFor(it) == command.context })
                    require(!command.startFromBeginning || command.context == QueueContext.PODCAST)
                    val before = read()
                    val mode = PlaybackControlsMode.valueOf(command.context.name)
                    val requests = command.items.map { services.captures.media(it, before.snapshot) }
                    val prepared = services.admissions.catalogLeaves(requests, mode, before.snapshot.revision)
                    admit(PlaybackCommand.AdmitAcquisition(prepared, command.context,
                        before.acquisition.queues.getValue(command.context).editRevision, QueueAdmissionOperation.REPLACE,
                        activate = true, startOccurrenceId = prepared.occurrences[command.startIndex].id,
                        startFromBeginning = command.startFromBeginning),
                        command.shuffle ?: before.acquisition.queues.getValue(command.context).shuffle)
                }
                is PlaybackCommand.Enqueue -> {
                    require(command.items.all { contextFor(it) == command.context })
                    if (command.items.isEmpty()) return true
                    val before = read()
                    val prepared = services.admissions.catalogLeaves(command.items.map { services.captures.media(it, before.snapshot) },
                        PlaybackControlsMode.valueOf(command.context.name), before.snapshot.revision)
                    admit(PlaybackCommand.AdmitAcquisition(prepared, command.context,
                        before.acquisition.queues.getValue(command.context).editRevision,
                        if (command.position == EnqueuePosition.NEXT) QueueAdmissionOperation.INSERT_NEXT else QueueAdmissionOperation.APPEND))
                }
                is PlaybackCommand.PlayEntry -> {
                    val before = read()
                    val queue = before.acquisition.queues.getValue(command.context)
                    playOccurrence(PlaybackCommand.PlayOccurrence(command.context, queue.editRevision, ExecutionOccurrenceId(command.entryId)))
                }
                is PlaybackCommand.Remove -> editRemove(command)
                is PlaybackCommand.Move -> {
                    val queue = readQueue(command.context, command.expectedQueueRevision)
                    val order = queue.displayedOrder.toMutableList()
                    val index = order.indexOf(ExecutionOccurrenceId(command.entryId))
                    require(index >= 0 && command.toIndex in order.indices)
                    order.add(command.toIndex, order.removeAt(index))
                    edit(queue, order, order, queue.currentOccurrenceId, queuePosition(queue), shuffle = false)
                }
                is PlaybackCommand.Clear -> {
                    val queue = readQueue(command.context, command.expectedQueueRevision)
                    edit(queue, emptyList(), emptyList(), null, 0, shuffle = false, repeat = RepeatMode.OFF)
                    if (command.context == mutableState.value.activeContext) start(view, false, true, AcquisitionPosition.ZERO)
                }
                is PlaybackCommand.ClearUpcoming -> {
                    val queue = readQueue(command.context, command.expectedQueueRevision)
                    val count = queue.displayedOrder.indexOf(queue.currentOccurrenceId) + 1
                    val order = queue.displayedOrder.take(count)
                    if (order != queue.displayedOrder) edit(queue, order, queue.originalOrder.filter { it in order },
                        queue.currentOccurrenceId?.takeIf { it in order }, queuePosition(queue))
                }
                is PlaybackCommand.UndoQueueEdit -> undo(command.context, command.expectedQueueRevision)
                is PlaybackCommand.SetShuffle -> {
                    readQueue(command.context, command.expectedQueueRevision)
                    shuffle(command.context, command.enabled)
                }
                is PlaybackCommand.SetRepeat -> {
                    require(command.context == QueueContext.MUSIC)
                    val queue = readQueue(command.context, command.expectedQueueRevision)
                    edit(queue, repeat = command.mode)
                }
                is PlaybackCommand.MarkPlayed -> catalogProgress(command.mediaId, command.played, false)
                is PlaybackCommand.ResetProgress -> catalogProgress(command.mediaId, false, true)
                else -> return false // Same transport/seek/timer/interruption commands and native worker.
            }
            mutableState.value = mutableState.value.copy(undoableQueueContexts = undo.keys.toSet())
            return true
        }

        private suspend fun admit(command: PlaybackCommand.AdmitAcquisition, shuffle: Boolean? = null) {
            val before = read()
            command.prepared.expectedCatalogRevision?.let { expected ->
                if (before.snapshot.revision != expected) invalid("acquisition_stale_library_revision")
            }
            val previous = remembered(before.acquisition.queues.getValue(command.context))
            require(previous.editRevision == command.expectedQueueRevision)
            val oldCurrent = mutableState.value.currentEntryId
            val oldContext = mutableState.value.activeContext
            val admitted = apply(AcquisitionPlaybackCommand.AdmitQueue(
                QueueAcquisitionAdmission(command.context, command.expectedQueueRevision, command.prepared.expectedCatalogRevision,
                    command.operation, command.prepared.requests, command.prepared.scope, command.prepared.occurrences, null),
                command.activate, oldContext, command.startOccurrenceId, command.startFromBeginning, clock.epochMs(), checkpointWrite()))
            // The final Room library/queue CAS can reject after suspension. Keep both the
            // resolver and native neighbour until the whole admission/checkpoint is ACKed;
            // queued actor events cannot promote it while this command is still suspended.
            cancelNext()
            undo[command.context] = previous
            if (shuffle == true) shuffle(command.context, true, remember = false, outgoing = false)
            if (command.activate) start(view, true, true,
                if (command.startFromBeginning) AcquisitionPosition.ZERO else AcquisitionPosition.RESUME)
            else if (oldContext == admitted.snapshot.activeContext && oldCurrent != mutableState.value.currentEntryId) {
                // Admission alone never initiates source work; a replaced active queue restores paused.
                stopTransport()
                mutableState.value = mutableState.value.copy(phase = if (mutableState.value.hasCurrent) PlaybackPhase.PAUSED else PlaybackPhase.IDLE,
                    positionMs = currentQueue().positionMs, durationMs = null)
            }
        }

        private suspend fun playOccurrence(command: PlaybackCommand.PlayOccurrence) {
            read()
            val queue = view.acquisition.queues.getValue(command.context)
            require(queue.editRevision == command.expectedQueueRevision && command.occurrenceId in queue.displayedOrder)
            val old = mutableState.value
            val policy = when {
                command.startFromBeginning -> AcquisitionPosition.ZERO
                old.currentEntryId == command.occurrenceId.value && old.activeContext == command.context && old.phase != PlaybackPhase.ENDED -> AcquisitionPosition.QUEUE
                else -> AcquisitionPosition.RESUME
            }
            // Activation is a transaction too. The handed stage and any in-flight neighbour
            // reservation belong to the outgoing owner until the final Room CAS and outgoing
            // checkpoint are acknowledged. start() retires them only after that positive ACK;
            // a rejected activation must leave the unchanged native stage and its events owned.
            val updated = apply(AcquisitionPlaybackCommand.Activate(expected(queue), command.occurrenceId, command.expectedBindingId,
                old.activeContext, if (policy == AcquisitionPosition.QUEUE) queuePosition(queue) else 0,
                rateFor(PlaybackSelection.project(view.library, command.occurrenceId), queue), command.startFromBeginning,
                clock.epochMs(), checkpointWrite()))
            start(updated, true, true, policy, command.expectedBindingId)
        }

        suspend fun resume() {
            val current = mutableState.value
            if (current.phase in setOf(PlaybackPhase.RESOLVING, PlaybackPhase.PREPARING) && currentLoad != null) {
                intentRevision++; resumeToken = null
                mutableState.value = current.copy(desiredPlaying = true, failure = null)
            } else if (preparedGeneration == generation && current.hasCurrent && current.phase !in setOf(PlaybackPhase.ERROR, PlaybackPhase.ENDED)) {
                intentRevision++; resumeToken = null
                mutableState.value = current.copy(desiredPlaying = true, failure = null)
                sendEngine { token -> if (mutableState.value.desiredPlaying) engine.play(token) }
            } else {
                read(); val queue = currentQueue()
                val selected = queue.currentOccurrenceId ?: queue.displayedOrder.firstOrNull() ?: return
                if (selected != queue.currentOccurrenceId) {
                    playOccurrence(PlaybackCommand.PlayOccurrence(queue.context, queue.editRevision, selected))
                } else start(view, true, true, if (current.phase == PlaybackPhase.ENDED) AcquisitionPosition.ZERO else AcquisitionPosition.QUEUE,
                    view.acquisition.occurrences.getValue(selected).bindingId)
            }
        }

        suspend fun nextOwned(naturalEnd: Boolean) {
            cancelNext(notifyEngine = false)
            read(); val current = mutableState.value; val queue = currentQueue()
            if (queue.displayedOrder.isEmpty()) return
            val index = queue.displayedOrder.indexOf(queue.currentOccurrenceId)
            val nextIndex = when {
                naturalEnd && queue.context == QueueContext.MUSIC && queue.repeat == RepeatMode.ONE -> index
                index + 1 < queue.displayedOrder.size -> index + 1
                queue.context == QueueContext.MUSIC && queue.repeat == RepeatMode.ALL -> 0
                else -> -1
            }
            if (nextIndex < 0) {
                pauseOwned(!naturalEnd)
                mutableState.value = mutableState.value.copy(phase = PlaybackPhase.ENDED, isPlaying = false)
                return
            }
            val id = queue.displayedOrder[nextIndex]
            val selection = PlaybackSelection.project(view.library, id)
            val policy = if (naturalEnd && id == queue.currentOccurrenceId) AcquisitionPosition.ZERO else AcquisitionPosition.RESUME
            val updated = apply(AcquisitionPlaybackCommand.Activate(expected(queue), id, selection.bindingId, current.activeContext,
                0, rateFor(selection, queue), false, clock.epochMs(), checkpointWrite()))
            start(updated, if (naturalEnd) current.desiredPlaying else true, !naturalEnd, policy, selection.bindingId)
        }

        suspend fun previous() {
            val current = mutableState.value
            if (current.positionMs > 3_000 && current.seekable) { seekOwned(0); return }
            read(); val queue = currentQueue()
            if (queue.displayedOrder.isEmpty()) return
            val index = queue.displayedOrder.indexOf(queue.currentOccurrenceId)
            val prior = if (index > 0) index - 1 else if (queue.context == QueueContext.MUSIC && queue.repeat == RepeatMode.ALL) queue.displayedOrder.lastIndex else 0
            val id = queue.displayedOrder[prior]
            val selection = PlaybackSelection.project(view.library, id)
            val updated = apply(AcquisitionPlaybackCommand.Activate(expected(queue), id, selection.bindingId, current.activeContext,
                0, rateFor(selection, queue), false, clock.epochMs(), checkpointWrite()))
            start(updated, true, true, AcquisitionPosition.ZERO, selection.bindingId)
        }

        private suspend fun editRemove(command: PlaybackCommand.Remove) {
            val queue = readQueue(command.context, command.expectedQueueRevision)
            val id = ExecutionOccurrenceId(command.entryId)
            val index = queue.displayedOrder.indexOf(id); require(index >= 0)
            val order = queue.displayedOrder.filterNot { it == id }
            val selected = if (id == queue.currentOccurrenceId) order.getOrNull(index) ?: order.lastOrNull() else queue.currentOccurrenceId
            val desired = mutableState.value.desiredPlaying
            edit(queue, order, queue.originalOrder.filterNot { it == id }, selected, if (id == queue.currentOccurrenceId) 0 else queuePosition(queue))
            if (command.context == mutableState.value.activeContext && id == queue.currentOccurrenceId) {
                start(view, desired, true, AcquisitionPosition.RESUME)
            }
        }

        private suspend fun edit(queue: ExecutionQueue, order: List<ExecutionOccurrenceId> = queue.displayedOrder,
            originals: List<ExecutionOccurrenceId> = queue.originalOrder, selected: ExecutionOccurrenceId? = queue.currentOccurrenceId,
            position: Long = queuePosition(queue), speed: Float = queue.speed, shuffle: Boolean = queue.shuffle,
            repeat: RepeatMode = queue.repeat, restore: Boolean = false, remember: Boolean = true, outgoing: Boolean = true) {
            cancelNext()
            val previous = remembered(queue)
            apply(AcquisitionPlaybackCommand.EditQueue(expected(queue), order, originals, selected, position, speed, shuffle, repeat,
                if (outgoing) checkpointWrite() else null, restore))
            if (remember) undo[queue.context] = previous
        }

        private suspend fun shuffle(context: QueueContext, enabled: Boolean, remember: Boolean = true, outgoing: Boolean = true) {
            require(context == QueueContext.MUSIC)
            val queue = view.acquisition.queues.getValue(context)
            if (queue.shuffle == enabled) return
            val order = if (enabled) {
                val split = queue.displayedOrder.indexOf(queue.currentOccurrenceId) + 1
                queue.displayedOrder.take(split) + queue.displayedOrder.drop(split).shuffled(random)
            } else queue.originalOrder
            edit(queue, order, if (enabled) queue.displayedOrder else order, shuffle = enabled, remember = remember, outgoing = outgoing)
        }

        private suspend fun undo(context: QueueContext, expectedRevision: Long?) {
            val queue = readQueue(context, expectedRevision)
            val previous = undo[context] ?: return
            val current = mutableState.value
            val selected = queue.currentOccurrenceId?.takeIf { it in previous.displayedOrder } ?: previous.currentOccurrenceId
            edit(queue, previous.displayedOrder, previous.originalOrder, selected,
                if (selected == queue.currentOccurrenceId) queuePosition(queue) else previous.positionMs,
                previous.speed, previous.shuffle, previous.repeat, restore = true, remember = false)
            undo.remove(context)
            if (context == current.activeContext && selected?.value != current.currentEntryId) start(view, current.desiredPlaying, true, AcquisitionPosition.QUEUE)
        }

        private suspend fun start(latest: PlaybackAcquisitionView, desired: Boolean, manual: Boolean,
            policy: AcquisitionPosition, expectedBinding: ExecutionBindingId? = null, renewalDelayMs: Long? = null) {
            cancelNext(notifyEngine = false)
            if (manual) { intentRevision++; if (mutableState.value.sleepTimer?.mode == SleepTimerMode.END_OF_EPISODE) clearTimer() }
            resumeToken = null; resolveJob?.cancel()
            val requiredRepresentation = representationId.takeIf { renewalDelayMs != null }
            generation = reserveGeneration(); preparedGeneration = -1; terminalGeneration = -1
            seekSequence = 0; confirmedSeekSequence = 0; pendingSeekSequence = null
            progressDirty = false; resolvedLocal = false; suppressedNext = null; materialization = null; hasNativePosition = false
            if (renewalDelayMs == null) {
                historyStartedMs = clock.epochMs(); historyGeneration = generation; hasPlayedCurrent = false; sourceRenewals = 0
            }
            publish(latest)
            val queue = currentQueue(); val selected = mutableState.value.currentSelection
            mutableState.value = mutableState.value.copy(generation = generation,
                viewedContext = if (manual) queue.context else mutableState.value.viewedContext,
                phase = if (selected == null) PlaybackPhase.IDLE else PlaybackPhase.RESOLVING,
                desiredPlaying = desired && selected != null, isPlaying = false,
                positionMs = if (policy == AcquisitionPosition.ZERO) 0 else queue.positionMs,
                durationMs = null, bufferedPositionMs = null, seekable = false, pendingSeekMs = null,
                speed = selected?.let { rateFor(it, queue) } ?: queue.speed, effectiveSpeed = 1f,
                failure = null, crossfade = null)
            currentLoad = null
            val token = generation
            sendEngine(releaseBarrier = true) { stopNative(token) }
            if (selected == null) return
            val load = AcquisitionLoad(token, token, expected(queue), selected.occurrenceId, policy, queue.positionMs,
                expectedBinding ?: selected.bindingId, renewalDelayMs, requiredRepresentation)
            currentLoad = load
            launchSelection(load, selected)
        }

        private fun launchSelection(load: AcquisitionLoad, selected: PlaybackSelection) {
            val launched = launchReaderResolution(identity(selected) ?: ReaderIdentity()) {
                try {
                    if (load.renewalDelayMs != null) delay(load.renewalDelayMs)
                    if (selected.occurrence.origin == AcquisitionOrigin.TYPED && selected.selectionState == OccurrenceSelectionState.PENDING) {
                        if (load.isStage) services.queues.resolveMusicPrefetch(load.queue.context, load.queue.editRevision,
                            requireNotNull(load.queue.currentOccurrenceId))
                        else services.queues.resolveCurrent(load.queue.context, load.queue.editRevision, load.occurrenceId)
                    }
                    ensureActive()
                    messages.send(Message.SelectionResolved(load.outgoingGeneration, load))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { messages.send(Message.AcquisitionResolveFailed(load.outgoingGeneration,
                    load.generation.takeIf { load.isStage }, failureOf(error))) }
            }
            if (load.isStage) nextResolveJob = launched else resolveJob = launched
        }

        private fun owns(load: AcquisitionLoad): Boolean = generation == load.outgoingGeneration &&
            (if (load.isStage) next?.load === load else currentLoad === load)

        private fun requireQueue(load: AcquisitionLoad, library: LibraryAcquisitionView) {
            val queue = library.acquisition.queues.getValue(load.queue.context)
            if (library.snapshot.activeContext != queue.context || expected(queue) != load.queue || load.occurrenceId !in queue.displayedOrder ||
                (if (load.isStage) neighbour(queue) != load.occurrenceId else queue.currentOccurrenceId != load.occurrenceId)) {
                invalid("playback_queue_action_stale")
            }
        }

        private suspend fun selectionResolvedOwned(message: Message.SelectionResolved) {
            val load = message.load
            if (!owns(load)) return
            val latest = read()
            requireQueue(load, latest.library)
            val selected = PlaybackSelection.project(latest.library, load.occurrenceId)
            if (selected.selectionState != OccurrenceSelectionState.BOUND || selected.bindingId == null) {
                val detail = when (selected.selectionState) {
                    OccurrenceSelectionState.OUTCOME_UNKNOWN -> "acquisition_outcome_unknown"
                    OccurrenceSelectionState.NEEDS_COLLECTION_ACTION -> "acquisition_collection_action_required"
                    OccurrenceSelectionState.LEGACY_UNBOUND -> "schema2_selection_requires_explicit_new_request"
                    OccurrenceSelectionState.PENDING -> "acquisition_selection_not_attached"
                    else -> "acquisition_selection_unplayable"
                }
                selectionFailure(load, AppFailure(FailureCode.RECOVERY_REQUIRED, detail)); return
            }
            if (load.expectedBindingId != null && load.expectedBindingId != selected.bindingId) invalid("playback_binding_changed")
            if (selected.target !is PlaybackTarget.Schema2 && selected.executionContext == null) invalid("playback_typed_context_missing")
            load.selection = selected
            val prior = sameBindingCheckpoint(selected, latest)
            val duration = measuredDuration(selected, latest)
            val position = boundedPosition(when (load.positionPolicy) {
                AcquisitionPosition.ZERO -> 0
                AcquisitionPosition.QUEUE -> load.queuePositionMs
                AcquisitionPosition.RESUME -> resumePosition(selected, latest)
            }, duration)
            val admission = ExecutionLoadAdmission(requireNotNull(selected.bindingId), prior, sessionId, load.generation,
                position, duration, clock.epochMs())
            val committed = if (load.isStage) {
                val outgoing = mutableState.value.currentSelection ?: invalid("playback_outgoing_missing")
                apply(AcquisitionPlaybackCommand.AdmitStage(load.queue, requireNotNull(outgoing.bindingId), sessionId,
                    generation, load.occurrenceId, admission))
            } else apply(AcquisitionPlaybackCommand.AdmitLoad(load.queue, admission))
            load.admitted = committed.checkpoints.getValue(load.occurrenceId)
            if (load.isStage) load.admittedOutgoing = committed.checkpoints.getValue(requireNotNull(load.queue.currentOccurrenceId))
            if (!load.isStage) mutableState.value = mutableState.value.copy(currentSelection = selected,
                currentItem = selected.catalogMedia, positionMs = position, durationMs = duration)
            else mutableState.value = mutableState.value.copy(crossfade = mutableState.value.crossfade?.copy(incomingSelection = selected))
            val launched = launchReaderResolution(requireNotNull(identity(selected))) {
                var unhanded: AcquisitionPlaybackSource? = null
                var legacyUnhanded: ResolvedPlayable? = null
                try {
                    if (selected.target is PlaybackTarget.Schema2) {
                        val historical = schema2Binding(selected, committed.library)
                        val source = resolver.resolve(requireNotNull(selected.catalogMedia), engine.capabilities,
                            committed.snapshot.settings.offlineOnly, reuseBindingId = historical.id)
                        legacyUnhanded = source
                        ensureActive(); legacyUnhanded = null
                        messages.send(Message.Schema2AcquisitionResolved(load.outgoingGeneration, load, source))
                    } else {
                        val prepared = services.resolver.resolve(requireNotNull(selected.executionContext), engine.capabilities,
                            committed.snapshot.settings.offlineOnly, load.requiredRepresentationId)
                        unhanded = prepared.source
                        ensureActive(); unhanded = null
                        messages.send(Message.AcquisitionResolved(load.outgoingGeneration, load, prepared))
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { messages.send(Message.AcquisitionResolveFailed(load.outgoingGeneration,
                    load.generation.takeIf { load.isStage }, failureOf(error))) }
                finally { unhanded?.let(::discardSource); legacyUnhanded?.let(::discardSource) }
            }
            if (load.isStage) nextResolveJob = launched else resolveJob = launched
        }

        private suspend fun selectionFailure(load: AcquisitionLoad, failure: AppFailure) {
            if (load.isStage) { cancelNext(suppress = true); mutableState.value = mutableState.value.copy(failure = failure) }
            else failPlayback(failure)
        }

        private fun sameBindingCheckpoint(selection: PlaybackSelection, latest: PlaybackAcquisitionView): OccurrenceCheckpoint? =
            latest.checkpoints[selection.occurrenceId]?.takeIf { it.bindingId == selection.bindingId }
        private fun sameRecording(bindingId: String?, selected: PlaybackSelection, latest: PlaybackAcquisitionView): Boolean =
            bindingId?.let { latest.acquisition.bindings[ExecutionBindingId(it)]?.recordingId }?.let {
                it == selected.binding?.recordingId
            } == true
        private fun measuredDuration(selected: PlaybackSelection, latest: PlaybackAcquisitionView): Long? {
            sameBindingCheckpoint(selected, latest)?.measuredDurationMs?.let { return it.takeIf { it > 0 } }
            val progress = selected.catalogMedia?.id?.let { latest.catalogProgress[it] }
            return progress?.takeIf { sameRecording(it.executionBindingId, selected, latest) }?.durationMs?.takeIf { it > 0 }
        }
        private fun resumePosition(selected: PlaybackSelection, latest: PlaybackAcquisitionView): Long {
            if (selected.controlsMode != PlaybackControlsMode.PODCAST) return 0
            val checkpoint = sameBindingCheckpoint(selected, latest)
            if (checkpoint != null && !checkpoint.endedNaturally) return checkpoint.positionMs
            return when (val subject = selected.target) {
                is PlaybackTarget.NeutralRecording -> latest.neutralResume[subject.subject.id to selected.controlsMode]
                    ?.let { latest.checkpoints[it] }?.takeIf { !it.endedNaturally &&
                        latest.acquisition.bindings[it.bindingId]?.recordingId == selected.binding?.recordingId }?.positionMs ?: 0
                else -> selected.catalogMedia?.id?.let { latest.catalogProgress[it] }?.takeIf {
                    !it.played && sameRecording(it.executionBindingId, selected, latest)
                }?.positionMs ?: 0
            }
        }
        private fun rateFor(selected: PlaybackSelection, queue: ExecutionQueue): Float =
            if (selected.controlsMode == PlaybackControlsMode.PODCAST) view.snapshot.settings.showSpeeds[selected.catalogMedia?.showId]
                ?: view.snapshot.settings.speed else queue.speed

        private fun schema2Binding(selected: PlaybackSelection, library: LibraryAcquisitionView): PlaybackBinding {
            require(selected.target is PlaybackTarget.Schema2 && selected.occurrence.origin == AcquisitionOrigin.SCHEMA2)
            val binding = selected.binding?.takeIf { it.origin == ExecutionBindingOrigin.SCHEMA2_EXECUTABLE }
                ?: invalid("schema2_bound_receipt_required")
            val receipt = selected.receipt as? FirstResultReceipt.Schema2 ?: invalid("schema2_bound_receipt_required")
            val historical = library.snapshot.playbackBindings[binding.id.value] ?: invalid("schema2_receipt_missing")
            if (historical.mediaId != receipt.mediaId || historical.queryTitle != receipt.queryTitle ||
                historical.firstResultKind != receipt.firstResultKind || historical.firstResultId != receipt.firstResultId ||
                historical.firstResultTitle != receipt.firstResultTitle || historical.firstResultUrl != receipt.canonicalUrl ||
                historical.selectedVideoId != receipt.selectedVideoId || historical.selectionOrdinal != receipt.selectionOrdinal ||
                historical.resolvedEpochMs != receipt.resolvedEpochMs) invalid("schema2_receipt_changed")
            return historical
        }

        suspend fun selectionResolved(message: Message.SelectionResolved) {
            try { selectionResolvedOwned(message) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!message.load.isStage) throw error
                if (owns(message.load)) selectionFailure(message.load, failureOf(error))
            }
        }

        /**
         * Called again in the ordered native worker. An unchanged queue/binding is insufficient:
         * a same-owner command can reserve a newer native epoch without editing either. Read the
         * durable checkpoint barrier, then recheck published controller state after suspension.
         */
        private suspend fun requireLiveAdmission(load: AcquisitionLoad, selected: PlaybackSelection) {
            val latest = services.repository.playbackView()
            val current = mutableState.value
            val crossfade = current.crossfade
            if (current.generation != load.outgoingGeneration) invalid("playback_load_superseded")
            requireQueue(load, latest.library)
            val admitted = load.admitted ?: invalid("playback_load_not_admitted")
            if (!sameEpoch(admitted, latest.checkpoints[load.occurrenceId])) invalid("playback_load_epoch_changed")
            if (load.isStage) {
                val outgoing = load.admittedOutgoing ?: invalid("playback_outgoing_not_admitted")
                if (outgoing.occurrenceId != load.queue.currentOccurrenceId || outgoing.sessionId != admitted.sessionId ||
                    outgoing.generation != load.outgoingGeneration || !sameEpoch(outgoing, latest.checkpoints[outgoing.occurrenceId])) {
                    invalid("playback_outgoing_epoch_changed")
                }
            }
            val actual = PlaybackSelection.project(latest.library, load.occurrenceId)
            if (actual.occurrence != selected.occurrence || actual.request != selected.request || actual.receipt != selected.receipt ||
                actual.binding != selected.binding || actual.executionContext != selected.executionContext) invalid("playback_selection_changed")
            if (load.isStage && (crossfade?.incomingGeneration != load.generation ||
                    crossfade.incomingEntryId != load.occurrenceId.value || !current.desiredPlaying)) invalid("playback_stage_superseded")
            if (!load.isStage && current.currentEntryId != load.occurrenceId.value) invalid("playback_load_superseded")
        }

        /** Real same-epoch sequence/position advances are not a different native reservation. */
        private fun sameEpoch(expected: OccurrenceCheckpoint, actual: OccurrenceCheckpoint?): Boolean = actual != null &&
            actual.occurrenceId == expected.occurrenceId && actual.bindingId == expected.bindingId &&
            actual.sessionId == expected.sessionId && actual.generation == expected.generation

        suspend fun handleResolved(message: Message.AcquisitionResolved) {
            val load = message.load; val prepared = message.prepared; val source = prepared.source
            var queued = false
            try {
                if (!owns(load)) return
                val selected = load.selection ?: invalid("playback_selection_missing")
                if (selected.executionContext != source.context) invalid("playback_returned_context_changed")
                requireLiveAdmission(load, selected)
                services.resolver.requireAdmissible(prepared)
                NativePlaybackSource.Acquired(source, selected).validateSelection()
                requireCapability(source.transport, source.mimeType, source.codecs, source.headers)
                if (load.isStage) {
                    val stage = next?.takeIf { it.load === load } ?: return
                    next = stage.copy(prepared = prepared)
                } else {
                    resolvedLocal = source.isLocal; materialization = prepared.validatedMaterialization; representationId = source.representationId
                    mutableState.value = mutableState.value.copy(phase = PlaybackPhase.PREPARING)
                }
                val position = requireNotNull(load.admitted).positionMs
                queued = true // Channel disposal owns every rejected send from this point.
                sendEngine(onDiscard = { discardSource(source) }, scopedFailure = if (load.isStage) { error ->
                    messages.trySend(Message.AcquisitionResolveFailed(load.outgoingGeneration, load.generation, scopedFailureOf(error)))
                    Unit
                } else null) {
                    var nativeOwns = false
                    try {
                        currentCoroutineContext().ensureActive()
                        requireLiveAdmission(load, selected)
                        services.resolver.requireAdmissible(prepared)
                        nativeOwns = true // Invocation transfers ownership even if the native method rejects/throws.
                        if (load.isStage) (engine as CrossfadeAudioEngine).preloadNext(source, selected, position, load.generation, load.outgoingGeneration)
                        else engine.prepare(source, selected, position, load.generation)
                    } finally { if (!nativeOwns) discardSource(source) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!load.isStage) throw error
                if (owns(load)) selectionFailure(load, failureOf(error))
            } finally { if (!queued) discardSource(source) }
        }

        suspend fun handleSchema2Resolved(message: Message.Schema2AcquisitionResolved) {
            val load = message.load; val source = message.source
            var queued = false
            try {
                if (!owns(load)) return
                val selected = load.selection ?: invalid("schema2_selection_missing")
                val historical = schema2Binding(selected, store.acquisitionViews.value)
                if (source.mediaId != selected.catalogMedia?.id || source.executionBinding != historical) invalid("schema2_returned_binding_changed")
                requireLiveAdmission(load, selected)
                requireSchema2Admissible(selected, source)
                requireCapability(source.transport, source.mimeType, source.codecs, source.headers)
                if (load.isStage) next = requireNotNull(next).copy(legacySource = source)
                else {
                    resolvedLocal = source.isLocal
                    materialization = source.assetId?.let { view.acquisition.materializations[MaterializationAssetId(it)] }
                    representationId = source.representationId
                    mutableState.value = mutableState.value.copy(phase = PlaybackPhase.PREPARING)
                }
                val tagged = source.copy(item = selected.catalogMedia)
                queued = true
                sendEngine(onDiscard = { discardSource(source) }, scopedFailure = if (load.isStage) { error ->
                    messages.trySend(Message.AcquisitionResolveFailed(load.outgoingGeneration, load.generation, scopedFailureOf(error)))
                    Unit
                } else null) {
                    var nativeOwns = false
                    try {
                        currentCoroutineContext().ensureActive()
                        requireLiveAdmission(load, selected)
                        requireSchema2Admissible(selected, source)
                        nativeOwns = true
                        if (load.isStage) (engine as CrossfadeAudioEngine).preloadNext(tagged, requireNotNull(load.admitted).positionMs,
                            load.generation, load.outgoingGeneration)
                        else engine.prepare(tagged, requireNotNull(load.admitted).positionMs, load.generation)
                    } finally { if (!nativeOwns) discardSource(source) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!load.isStage) throw error
                if (owns(load)) selectionFailure(load, failureOf(error))
            } finally { if (!queued) discardSource(source) }
        }

        private fun requireSchema2Admissible(selected: PlaybackSelection, source: ResolvedPlayable) {
            val latest = store.acquisitionViews.value
            if (!source.isLocal && latest.snapshot.settings.offlineOnly) invalid("offline_policy_changed")
            if (latest.acquisition.physicalDownloads.values.any { it.producer.subjectId == selected.subjectId &&
                    (it.producer.recordingId == null || it.producer.recordingId == selected.binding?.recordingId) &&
                    (it.cleanupPending || it.maintenanceKind == PhysicalDownloadMaintenanceKind.CLEANUP) }) invalid("offline_asset_removal_pending")
            if (source.isLocal && source.assetId != null) {
                val asset = latest.acquisition.materializations[MaterializationAssetId(source.assetId)]
                    ?: invalid("schema2_local_asset_missing")
                if (asset.producer.bindingId != selected.bindingId || asset.producer.recordingId != selected.binding?.recordingId) {
                    invalid("schema2_local_binding_changed")
                }
            }
        }

        private fun requireCapability(transport: StreamTransport, mime: String?, codecs: String?, headers: Map<String, String>) {
            if (!engine.capabilities.supports(transport, mime, codecs)) throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_CODEC))
            if (headers.isNotEmpty() && !engine.capabilities.supportsRequestHeaders) throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE))
        }

        private fun checkpointWrite(naturalEnd: Boolean = false): ExecutionCheckpointWrite? {
            val current = mutableState.value
            val load = currentLoad ?: return null
            val selected = load.selection ?: return null
            val admitted = load.admitted ?: return null
            // Neither a requested seek nor an unprepared/newly admitted epoch is a native sample.
            if (!hasNativePosition || preparedGeneration != generation || admitted.generation != generation || current.currentEntryId != selected.occurrenceId.value ||
                current.currentSelection?.bindingId != selected.bindingId) return null
            val previous = view.checkpoints[selected.occurrenceId] ?: return null
            if (previous.sessionId != sessionId || previous.generation != generation || previous.bindingId != selected.bindingId) return null
            check(progressSequence < Long.MAX_VALUE && previous.sequence < Long.MAX_VALUE)
            progressSequence = maxOf(progressSequence + 1, previous.sequence + 1)
            return ExecutionCheckpointWrite(current.activeContext, OccurrenceCheckpoint(selected.occurrenceId,
                requireNotNull(selected.bindingId), boundedPosition(current.positionMs, current.durationMs), current.durationMs,
                maxOf(clock.epochMs(), previous.observedEpochMs), sessionId, generation, progressSequence, naturalEnd || previous.endedNaturally),
                if (hasPlayedCurrent) sessionId + "-" + historyGeneration else null, historyStartedMs.takeIf { hasPlayedCurrent })
        }

        suspend fun checkpoint(force: Boolean, naturalEnd: Boolean) {
            if (!force && !progressDirty) return
            val write = checkpointWrite(naturalEnd) ?: return
            apply(AcquisitionPlaybackCommand.Checkpoint(write))
        }

        suspend fun catalogProgress(mediaId: String, played: Boolean?, reset: Boolean) {
            read()
            require(view.snapshot.media[mediaId]?.kind == MediaKind.EPISODE)
            val current = mutableState.value
            val selected = current.currentSelection
            apply(AcquisitionPlaybackCommand.CatalogProgress(mediaId, played, reset, clock.epochMs(), checkpointWrite()))
            if (reset && selected?.catalogMedia?.id == mediaId) {
                // Retire the outgoing epoch after the atomic checkpoint+reset. No unconfirmed
                // old seek sample can overwrite that user intent while preparing zero anew.
                start(view, current.desiredPlaying, true, AcquisitionPosition.ZERO, selected.bindingId)
            }
        }

        suspend fun setRate(speed: Float, forThisShow: Boolean) {
            require(speed.isFinite() && speed in .5f..2f && speed in engine.capabilities.minSpeed..engine.capabilities.maxSpeed)
            read(); val current = mutableState.value
            val selection = current.currentSelection
            val showId = selection?.catalogMedia?.takeIf { it.kind == MediaKind.EPISODE }?.showId
            require(!forThisShow || selection?.controlsMode == PlaybackControlsMode.PODCAST && showId != null)
            if (selection?.controlsMode == PlaybackControlsMode.PODCAST) {
                store.update { old -> old.copy(settings = old.settings.copy(
                    speed = if (forThisShow) old.settings.speed else speed,
                    showSpeeds = when {
                        forThisShow -> old.settings.showSpeeds + (requireNotNull(showId) to speed)
                        showId != null -> old.settings.showSpeeds - showId
                        else -> old.settings.showSpeeds
                    })) }.getOrThrow()
                read()
            }
            edit(currentQueue(), speed = speed, remember = false)
            mutableState.value = mutableState.value.copy(speed = speed)
            if (preparedGeneration == generation) sendEngine { token -> engine.setRate(speed, token) }
        }

        suspend fun synchronize() {
            val latest = store.acquisitionViews.value
            if (latest.snapshot.revision != view.snapshot.revision) {
                read()
            }
            val selection = mutableState.value.currentSelection
            if (selection?.controlsMode == PlaybackControlsMode.PODCAST) {
                val queue = currentQueue(); val rate = rateFor(selection, queue)
                if (mutableState.value.speed != rate || queue.speed != rate) {
                    edit(queue, speed = rate, remember = false)
                    mutableState.value = mutableState.value.copy(speed = rate)
                    if (preparedGeneration == generation) sendEngine { token -> engine.setRate(rate, token) }
                }
            }
            checkCrossfade()
        }

        suspend fun renew(failure: AppFailure): Boolean {
            val selected = mutableState.value.currentSelection ?: return false
            if (selected.bindingId == null || !mutableState.value.desiredPlaying || resolvedLocal ||
                store.state.value.settings.offlineOnly || sourceRenewals >= 2 || (failure.retryAfterMs ?: 0) > 30_000 ||
                failure.code !in setOf(FailureCode.NETWORK_UNAVAILABLE, FailureCode.PROVIDER_ACCESS_REQUIRED,
                    FailureCode.RATE_LIMITED, FailureCode.NOT_FOUND)) return false
            checkpoint(true, false)
            sourceRenewals++
            val delayMs = maxOf(1_000L * sourceRenewals, (failure.retryAfterMs ?: 0).coerceAtLeast(0))
            start(view, true, false, AcquisitionPosition.QUEUE, selected.bindingId, delayMs)
            return true
        }

        private fun neighbour(queue: ExecutionQueue): ExecutionOccurrenceId? {
            if (queue.context != QueueContext.MUSIC || queue.repeat == RepeatMode.ONE || queue.currentOccurrenceId == null) return null
            val index = queue.displayedOrder.indexOf(queue.currentOccurrenceId)
            return (queue.displayedOrder.getOrNull(index + 1) ?: if (queue.repeat == RepeatMode.ALL && queue.displayedOrder.size > 1)
                queue.displayedOrder.first() else null)?.takeUnless { it == queue.currentOccurrenceId }
        }

        suspend fun checkCrossfade() {
            val transport = engine as? CrossfadeAudioEngine ?: return
            val current = mutableState.value; val settings = store.state.value.settings
            val queue = store.acquisitionViews.value.acquisition.queues[current.activeContext] ?: return
            val nextId = neighbour(queue)
            val duration = current.durationMs
            val eligible = engine.capabilities.supportsCrossfade && settings.crossfadeEnabled &&
                current.controlsMode == PlaybackControlsMode.MUSIC && current.currentSelection?.selectionState == OccurrenceSelectionState.BOUND &&
                current.desiredPlaying && current.isPlaying && preparedGeneration == generation && pendingSeekSequence == null &&
                duration != null && duration > 0 && nextId != null
            if (!eligible) { if (next != null) cancelNext(); return }
            requireNotNull(duration); requireNotNull(nextId)
            val outgoingSpeed = current.effectiveSpeed.takeIf { it.isFinite() && it > 0 } ?: 1f
            val remaining = ((duration - current.positionMs).coerceAtLeast(0) / outgoingSpeed).toLong()
            if (next?.let { it.load.occurrenceId != nextId || it.load.queue != expected(queue) ||
                    it.requestedDurationMs != settings.crossfadeDurationMs } == true) cancelNext()
            if (next == null) {
                if (remaining < 100 || remaining > maxOf(15_000, settings.crossfadeDurationMs * 2) ||
                    suppressedNext == generation to nextId.value) return
                val selected = PlaybackSelection.project(store.acquisitionViews.value, nextId)
                if (selected.controlsMode != PlaybackControlsMode.MUSIC) return
                val token = reserveGeneration()
                val load = AcquisitionLoad(generation, token, expected(queue), nextId, AcquisitionPosition.ZERO, 0, selected.bindingId)
                next = AcquisitionStage(load, settings.crossfadeDurationMs)
                mutableState.value = mutableState.value.copy(crossfade = CrossfadeState(CrossfadePhase.PREPARING,
                    selected.catalogMedia, nextId.value, incomingSelection = selected, incomingGeneration = token))
                launchSelection(load, selected)
                return
            }
            val stage = next ?: return
            if (!stage.ready || stage.beginRequested) return
            val incomingDuration = stage.durationMs ?: return
            val maximum = minOf(settings.crossfadeDurationMs, (duration / outgoingSpeed / 2).toLong(),
                (incomingDuration / current.speed / 2).toLong())
            if (maximum < 100 || remaining < 100) { cancelNext(suppress = true); return }
            if (remaining > maximum) return
            next = stage.copy(beginRequested = true)
            sendEngine { token -> transport.beginCrossfade(minOf(maximum, remaining), stage.generation, token) }
        }

        suspend fun cancelNext(suppress: Boolean = false, notifyEngine: Boolean = true) {
            nextResolveJob?.cancel(); nextResolveJob = null
            val stage = next ?: return
            next = null
            if (suppress) suppressedNext = generation to stage.load.occurrenceId.value
            mutableState.value = mutableState.value.copy(crossfade = null)
            val transport = engine as? CrossfadeAudioEngine
            if (notifyEngine && transport != null && stage.hasSource) sendEngine { token -> transport.cancelNext(stage.generation, token) }
        }

        suspend fun handleStageEvent(event: EngineEvent): Boolean {
            when (event) {
                is EngineEvent.StageReady -> {
                    val stage = next?.takeIf { it.generation == event.nextGeneration } ?: return true
                    if (!stage.ready) {
                        if (event.durationMs == null || event.durationMs <= 0) cancelNext(suppress = true)
                        else {
                            next = stage.copy(ready = true, durationMs = event.durationMs)
                            mutableState.value = mutableState.value.copy(crossfade = mutableState.value.crossfade?.copy(phase = CrossfadePhase.READY))
                        }
                    }
                }
                is EngineEvent.StageFailed -> if (next?.generation == event.nextGeneration) {
                    cancelNext(suppress = true); mutableState.value = mutableState.value.copy(failure = event.failure)
                }
                is EngineEvent.CrossfadeStarted -> next?.takeIf { it.generation == event.nextGeneration && it.beginRequested }?.let { stage ->
                    next = stage.copy(started = true, startedEpochMs = clock.epochMs())
                    mutableState.value = mutableState.value.copy(crossfade = mutableState.value.crossfade?.copy(
                        phase = CrossfadePhase.OVERLAPPING, durationMs = event.durationMs, fraction = 0f))
                }
                is EngineEvent.CrossfadeProgress -> if (next?.generation == event.nextGeneration && next?.started == true && event.fraction.isFinite()) {
                    mutableState.value = mutableState.value.copy(crossfade = mutableState.value.crossfade?.copy(fraction = event.fraction.coerceIn(0f, 1f)))
                }
                is EngineEvent.CrossfadeCompleted -> handover(event)
                is EngineEvent.CrossfadeCancelled -> if (next?.generation == event.nextGeneration) cancelNext(suppress = true)
                else -> return false
            }
            return true
        }

        private suspend fun handover(event: EngineEvent.CrossfadeCompleted) {
            val stage = next?.takeIf { it.generation == event.nextGeneration && it.started && it.beginRequested && it.hasSource } ?: return
            val load = stage.load; val selected = load.selection ?: return
            if (!mutableState.value.desiredPlaying) { cancelNext(); return }
            requireLiveAdmission(load, selected)
            val outgoing = checkpointWrite() ?: invalid("playback_outgoing_not_admitted")
            val previous = requireNotNull(load.admitted)
            val incoming = ExecutionCheckpointWrite(QueueContext.MUSIC, previous.copy(
                positionMs = boundedPosition(event.positionMs, event.durationMs ?: stage.durationMs),
                measuredDurationMs = event.durationMs?.takeIf { it > 0 } ?: stage.durationMs,
                observedEpochMs = maxOf(clock.epochMs(), previous.observedEpochMs), sequence = previous.sequence + 1),
                sessionId + "-" + load.generation, stage.startedEpochMs)
            val oldGeneration = generation
            // Commit both real samples and the cursor before acknowledging any native promotion.
            val committed = apply(AcquisitionPlaybackCommand.Handover(load.queue, outgoing, incoming))
            next = null; nextResolveJob = null; currentLoad = load
            load.admitted = committed.checkpoints.getValue(load.occurrenceId)
            generation = load.generation; preparedGeneration = generation; terminalGeneration = -1
            seekSequence = 0; confirmedSeekSequence = 0; pendingSeekSequence = null
            resolvedLocal = stage.prepared?.source?.isLocal ?: stage.legacySource?.isLocal ?: false
            materialization = stage.prepared?.validatedMaterialization
            representationId = stage.prepared?.source?.representationId ?: stage.legacySource?.representationId
            hasPlayedCurrent = true; historyStartedMs = stage.startedEpochMs; historyGeneration = generation
            hasNativePosition = true
            sourceRenewals = 0; progressDirty = false; suppressedNext = null
            mutableState.value = mutableState.value.copy(generation = generation,
                positionMs = incoming.checkpoint.positionMs, durationMs = incoming.checkpoint.measuredDurationMs,
                seekable = event.seekable, phase = if (event.isPlaying) PlaybackPhase.PLAYING else PlaybackPhase.READY,
                isPlaying = event.isPlaying, effectiveSpeed = event.effectiveSpeed, crossfade = null,
                bufferedPositionMs = null, pendingSeekMs = null, failure = null)
            val transport = engine as CrossfadeAudioEngine
            sendEngine { transport.confirmCrossfade(load.generation, oldGeneration) }
        }

        private fun invalid(detail: String): Nothing = throw VeyraException(AppFailure(FailureCode.INVALID_STATE, detail))
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
    private data class EngineOperation(
        val generation: Long,
        val releaseBarrier: Boolean,
        val discard: () -> Unit,
        val scopedFailure: ((Throwable) -> Unit)?,
        val action: suspend () -> Unit,
    )
    private data class MediaResolution(val identity: ReaderIdentity, val job: Job)
    private data class ReaderIdentity(
        val mediaId: String? = null,
        val subjectId: ExecutableSubjectId? = null,
        val recordingId: SourceRecordingId? = null,
        val materializationId: MaterializationAssetId? = null,
    )
    private data class StagedReader(val generation: Long, val identity: ReaderIdentity, val hasSource: Boolean)
    private sealed interface ReaderTarget {
        data class Catalog(val mediaId: String) : ReaderTarget
        data class Materialization(val asset: MaterializationAsset) : ReaderTarget
        fun matches(reader: ReaderIdentity?): Boolean = reader != null && when (this) {
            is Catalog -> reader.mediaId == mediaId
            is Materialization -> reader.materializationId == asset.id ||
                reader.subjectId == asset.producer.subjectId &&
                (reader.recordingId == null || reader.recordingId == asset.producer.recordingId)
        }
    }
    private fun currentReader(): ReaderIdentity? = typed?.currentReader()
        ?: mutableState.value.currentItem?.let { ReaderIdentity(mediaId = it.id) }
    private class AssetRelease(
        val target: ReaderTarget,
        val generation: Long,
        val result: CompletableDeferred<PlaybackResult>,
        val stageCleanup: CompletableDeferred<AppFailure?>?,
    ) {
        /** Only the actor reads/writes this admission fence. */
        var readerAdmissionChanged = false
    }
    private sealed interface ResourceCleanup {
        data class Close(val resource: PlaybackResource) : ResourceCleanup
        data class Fence(val result: CompletableDeferred<AppFailure?>) : ResourceCleanup
    }
    private sealed interface Message {
        data class Initialize(val result: CompletableDeferred<PlaybackResult>) : Message
        data class Command(val command: PlaybackCommand, val result: CompletableDeferred<PlaybackResult>) : Message
        data class Shutdown(val result: CompletableDeferred<PlaybackResult>) : Message
        data class ReleaseAsset(val target: ReaderTarget, val result: CompletableDeferred<PlaybackResult>) : Message
        data class AssetResolversReleased(val release: AssetRelease) : Message
        data class AssetNativeReleased(val release: AssetRelease, val outcome: PlaybackResult) : Message
        data class NativeReleaseFailed(val generation: Long, val failure: AppFailure) : Message
        data class Event(val event: EngineEvent) : Message
        data class Resolved(val generation: Long, val source: ResolvedPlayable) : Message
        data class SelectionResolved(val generation: Long, val load: AcquisitionLoad) : Message
        data class AcquisitionResolved(val generation: Long, val load: AcquisitionLoad,
            val prepared: AcquisitionPlaybackPreparation) : Message
        data class Schema2AcquisitionResolved(val generation: Long, val load: AcquisitionLoad, val source: ResolvedPlayable) : Message
        data class AcquisitionResolveFailed(val generation: Long, val nextGeneration: Long?, val failure: AppFailure) : Message
        data class NextResolved(val generation: Long, val nextGeneration: Long, val source: ResolvedPlayable) : Message
        data class NextResolveFailed(val generation: Long, val nextGeneration: Long, val failure: AppFailure) : Message
        data class ResolveFailed(val generation: Long, val failure: AppFailure) : Message
        data object EventStreamFailed : Message
        data class SnapshotChanged(val snapshot: AppSnapshot) : Message
        data object Tick : Message
    }
}
