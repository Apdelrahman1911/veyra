package dev.veyra.playback

import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.SourceResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*

/** Deterministic controller seam only. Real relational durability is tested with Room on JVM. */
internal class PlaybackTestPersistence(initial: AppSnapshot = AppSnapshot()) : LibraryPersistence {
    var snapshot = initial
    var reject: ((AppSnapshot) -> Boolean)? = null
    val commits = mutableListOf<AppSnapshot>()
    override suspend fun open() = OpenedLibrary(snapshot.also(::validateLibrarySnapshot))
    override suspend fun read() = snapshot
    override suspend fun transact(expectedRevision: Long?, transform: (AppSnapshot) -> AppSnapshot): AppSnapshot {
        if (expectedRevision != null && expectedRevision != snapshot.revision) throw VeyraException(AppFailure(FailureCode.INVALID_STATE))
        val next = transform(snapshot)
        if (next == snapshot) return snapshot
        validateLibraryTransition(snapshot, next)
        if (reject?.invoke(next) == true) throw VeyraException(AppFailure(FailureCode.STORAGE_IO))
        snapshot = next.copy(revision = snapshot.revision + 1)
        commits += snapshot
        return snapshot
    }
    override fun changes(): Flow<Unit> = emptyFlow()
    override suspend fun backup() = Unit
    override suspend fun recoverFromBackup(): OpenedLibrary = error("Not a persistence recovery test")
    override suspend fun close() = Unit
}

internal fun testTrack(id: String = "first", kind: MediaKind = MediaKind.MUSIC) = MediaItem(
    id, "The complete original title for $id — العنوان الكامل", kind,
    SourceRef(if (kind == MediaKind.MUSIC) SourceProvider.COMMONS else SourceProvider.RSS, "canonical-$id"),
    durationMs = 9_999_999, // Deliberately unrelated publisher duration, never the native duration.
)

internal class CountingPlaybackResource(uri: String = "http://127.0.0.1:43210/authored-capability-not-a-real-server") : PlaybackResource {
    override val localEndpoint = EphemeralPlaybackEndpoint(uri)
    var closeCalls = 0
    var closed = false
    override suspend fun close() { closeCalls++; closed = true }
}

internal class PlaybackTestResolver(private val store: LibraryStore) : SourceResolver {
    data class Request(val item: MediaItem, val bindingId: String?)
    val requests = mutableListOf<Request>()
    var behavior: (suspend (MediaItem, String?) -> ResolvedPlayable)? = null
    val durations = mutableMapOf<String, Long?>()
    val selectedVideos = mutableMapOf<String, String>()
    val resources = mutableMapOf<String, PlaybackResource>()
    var local = false
    private var sequence = 0

    override suspend fun resolve(item: MediaItem, capabilities: EngineCapabilities, offlineOnly: Boolean,
        purpose: ResolvePurpose, reuseBindingId: String?): ResolvedPlayable {
        requests += Request(item, reuseBindingId)
        return behavior?.invoke(item, reuseBindingId) ?: result(item, reuseBindingId)
    }

    suspend fun result(item: MediaItem, reuseBindingId: String? = null): ResolvedPlayable {
        val binding = if (reuseBindingId != null) store.state.value.playbackBindings.getValue(reuseBindingId) else {
            val video = selectedVideos[item.id] ?: if (item.id == "first") "AAAAAAAAAAA" else "BBBBBBBBBBB"
            PlaybackBinding("binding-${++sequence}", item.id, item.title, FirstResultKind.VIDEO, video,
                "Authored first search result", "https://www.youtube.com/watch?v=$video", video, 1000)
                .also { store.recordPlaybackBinding(it).getOrThrow() }
        }
        val resource = resources[item.id]
        return ResolvedPlayable(item.id, resource?.localEndpoint?.uri ?: if (local) "authored/local.m4a" else "https://media.example.com/authored-audio",
            mimeType = "audio/mp4", codecs = "aac", durationMs = if (item.id in durations) durations[item.id] else 100_000L,
            isLocal = local, executionBinding = binding, item = item, resource = resource)
    }
}

/** Manual native clock/overlap fake, not audio evidence. It enforces the retained-owner ACK contract. */
internal class PlaybackTestEngine : CrossfadeAudioEngine {
    data class Load(val source: ResolvedPlayable, val startPositionMs: Long, val generation: Long)
    data class Fade(val durationMs: Long, val incoming: Long, val outgoing: Long)
    data class Seek(val positionMs: Long, val sequence: Long, val generation: Long)
    override val capabilities = EngineCapabilities(supportsCrossfade = true)
    val eventsChannel = Channel<EngineEvent>(Channel.UNLIMITED)
    override val events = eventsChannel.receiveAsFlow()
    val loads = mutableListOf<Load>()
    val preloads = mutableListOf<Load>()
    val fades = mutableListOf<Fade>()
    val confirmations = mutableListOf<Pair<Long, Long>>()
    val cancellations = mutableListOf<Pair<Long, Long>>()
    val seeks = mutableListOf<Seek>()
    val plays = mutableListOf<Long>()
    val pauses = mutableListOf<Long>()
    val stops = mutableListOf<Long>()
    val rates = mutableListOf<Pair<Float, Long>>()
    var activeGeneration = 0L
    var active: Load? = null
    var staged: Load? = null
    var currentSpeed = 1f
    var closed = false
    var autoReady = true
    var autoSeek = true
    var beforePrepare: (suspend () -> Unit)? = null
    var beforeStop: (suspend () -> Unit)? = null
    var beforeConfirm: (suspend () -> Unit)? = null
    var beforeCancelNext: (suspend () -> Unit)? = null

    override suspend fun prepare(source: ResolvedPlayable, startPositionMs: Long, generation: Long) {
        var retained = false
        try {
            beforePrepare?.invoke()
            if (generation < activeGeneration) return
            closeActive()
            activeGeneration = generation
            active = Load(source, startPositionMs, generation).also { loads += it }
            retained = true
            if (autoReady) emit(EngineEvent.Ready(generation, source.durationMs, true))
        } finally { if (!retained) source.resource?.close() }
    }
    override suspend fun play(generation: Long) {
        if (generation != activeGeneration) return
        plays += generation
        emit(EngineEvent.StateChanged(generation, EnginePhase.PLAYING, true, currentSpeed))
    }
    override suspend fun pause(generation: Long) {
        if (generation != activeGeneration) return
        clearStage()
        pauses += generation
        emit(EngineEvent.StateChanged(generation, EnginePhase.PAUSED, false, currentSpeed))
    }
    override suspend fun seek(positionMs: Long, seekSequence: Long, generation: Long) {
        if (generation != activeGeneration) return
        clearStage()
        seeks += Seek(positionMs, seekSequence, generation)
        if (autoSeek) emit(EngineEvent.SeekCompleted(generation, seekSequence, positionMs))
    }
    override suspend fun setRate(speed: Float, generation: Long) {
        if (generation != activeGeneration) return
        clearStage()
        rates += speed to generation
        currentSpeed = speed
    }
    override suspend fun stop(generation: Long) {
        beforeStop?.invoke()
        if (generation < activeGeneration) return
        closeActive()
        activeGeneration = generation
        stops += generation
    }
    override suspend fun preloadNext(source: ResolvedPlayable, startPositionMs: Long, nextGeneration: Long, generation: Long) {
        if (generation != activeGeneration) { source.resource?.close(); return }
        clearStage()
        staged = Load(source, startPositionMs, nextGeneration).also { preloads += it }
    }
    override suspend fun beginCrossfade(durationMs: Long, nextGeneration: Long, generation: Long) {
        if (generation != activeGeneration || staged?.generation != nextGeneration) return
        fades += Fade(durationMs, nextGeneration, generation)
        emit(EngineEvent.CrossfadeStarted(generation, nextGeneration, durationMs))
    }
    override suspend fun confirmCrossfade(nextGeneration: Long, generation: Long) {
        beforeConfirm?.invoke()
        if (generation != activeGeneration || staged?.generation != nextGeneration) return
        active?.source?.resource?.close()
        active = staged
        staged = null
        activeGeneration = nextGeneration
        confirmations += generation to nextGeneration
    }
    override suspend fun cancelNext(nextGeneration: Long, generation: Long) {
        beforeCancelNext?.invoke()
        if (generation != activeGeneration) return
        if (staged?.generation == nextGeneration) clearStage()
        cancellations += generation to nextGeneration
    }
    override suspend fun close() { closeActive(); closed = true; eventsChannel.close() }
    private suspend fun clearStage() { staged?.source?.resource?.close(); staged = null }
    private suspend fun closeActive() { clearStage(); active?.source?.resource?.close(); active = null }
    suspend fun emit(event: EngineEvent) { eventsChannel.send(event) }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class PlaybackFixture(val testScope: TestScope, initial: AppSnapshot = AppSnapshot()) {
    val dispatcher = StandardTestDispatcher(testScope.testScheduler)
    val persistence = PlaybackTestPersistence(initial)
    val store = LibraryStore(persistence, dispatcher)
    val engine = PlaybackTestEngine()
    val resolver = PlaybackTestResolver(store)
    val clock = object : Clock {
        override fun epochMs(): Long = 1_000_000 + testScope.testScheduler.currentTime
        override fun monotonicMs(): Long = testScope.testScheduler.currentTime
    }
    val controller = PlaybackController(store, engine, resolver, testScope, clock, dispatcher)
    val state get() = controller.state.value

    suspend fun command(command: PlaybackCommand) { controller.dispatch(command).getOrThrow(); testScope.runCurrent() }
    suspend fun event(event: EngineEvent) { engine.emit(event); testScope.runCurrent() }
    suspend fun play(items: List<MediaItem> = listOf(testTrack(), testTrack("second")), crossfade: Boolean = false) {
        store.update { it.copy(settings = it.settings.copy(crossfadeEnabled = crossfade)) }.getOrThrow()
        command(PlaybackCommand.Play(if (items.first().kind == MediaKind.EPISODE) QueueContext.PODCAST else QueueContext.MUSIC, items))
    }
    suspend fun readyNext(outgoingPosition: Long = 90_000, nextDuration: Long? = 100_000) {
        event(EngineEvent.Position(state.generation, outgoingPosition, state.durationMs))
        // Position samples are conflated separately; a critical/tick path evaluates preload.
        testScope.advanceTimeBy(250); testScope.runCurrent()
        val next = requireNotNull(engine.staged)
        event(EngineEvent.StageReady(state.generation, next.generation, nextDuration, true))
    }
    suspend fun startFade(position: Long = 98_000) {
        readyNext()
        event(EngineEvent.Position(state.generation, position, state.durationMs))
        testScope.advanceTimeBy(250); testScope.runCurrent()
    }
    suspend fun close() { controller.close(); store.close() }
}
