package dev.veyra.source.release

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

data class ReleaseMetadataPolicy(
    val minimumIntervalMs: Long = 1_100,
    val maximumPendingRequests: Int = 8,
    val searchPageSize: Int = 25,
    val maximumSearchRows: Int = 200,
    val maximumCacheBytes: Int = 32 * 1024 * 1024,
    val maximumCachedObservations: Int = 200,
    val searchFreshForMs: Long = 24 * 60 * 60_000L,
    val releaseFreshForMs: Long = 7 * 24 * 60 * 60_000L,
) {
    init {
        require(minimumIntervalMs >= 1_100 && maximumPendingRequests in 1..8)
        require(searchPageSize in 1..25 && maximumSearchRows in searchPageSize..200)
        require(maximumCacheBytes in 1..32 * 1024 * 1024 && maximumCachedObservations in 1..200)
        require(searchFreshForMs in 0..24 * 60 * 60_000L && releaseFreshForMs in 0..7 * 24 * 60 * 60_000L)
    }
}

interface ReleaseMetadataClock {
    fun epochMs(): Long
    fun monotonicMs(): Long
    suspend fun sleep(ms: Long)
}

@OptIn(ExperimentalTime::class)
class SystemReleaseMetadataClock : ReleaseMetadataClock {
    private val origin = TimeSource.Monotonic.markNow()
    override fun epochMs(): Long = Clock.System.now().toEpochMilliseconds()
    override fun monotonicMs(): Long = origin.elapsedNow().inWholeMilliseconds
    override suspend fun sleep(ms: Long) { delay(ms) }
}

/**
 * One application-owned producer, not a MediaSource: no resolve/download/audio operation exists.
 * No persistence is implemented here. The composition root stores saved immutable core values in
 * normalized Room and opens them there offline; this bounded LRU holds only disposable metadata.
 */
class MusicBrainzReleaseSource(
    private val transport: MusicBrainzMetadataTransport,
    parentScope: CoroutineScope,
    private val isOffline: () -> Boolean,
    private val policy: ReleaseMetadataPolicy = ReleaseMetadataPolicy(),
    private val clock: ReleaseMetadataClock = SystemReleaseMetadataClock(),
    private val ownsTransport: Boolean = false,
    workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val lifetime = SupervisorJob(requireNotNull(parentScope.coroutineContext[Job]) { "An application-owned scope is required." })
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime + workerDispatcher)
    private val lock = Mutex()
    private val networkSlot = Mutex()
    private val closeLock = Mutex()
    private val cursorOwner = Any()
    private val tasks = linkedMapOf<MusicBrainzMetadataRequest, Task>()
    private val cache = linkedMapOf<MusicBrainzMetadataRequest, Cached>()
    private var cacheBytes = 0L
    private var cacheObservations = 0
    private var completedNetworkAt: Long? = null
    private var cooldownUntil: Long? = null
    private var closed = false
    private var closeFinished = false
    private var closeFailure: ReleaseMetadataException? = null

    suspend fun release(mbid: String, refresh: Boolean = false): ReleaseMetadataRead<ReleaseObservation> {
        if (!RELEASE_MBID.matches(mbid)) releaseFailure(ReleaseFailureCode.INVALID_RELEASE_ID)
        val result = read(MusicBrainzMetadataRequest.Release(mbid.lowercase()), refresh)
        return ReleaseMetadataRead((result.value as Value.Detail).observation.detached(), result.fromCache, result.stale)
    }

    suspend fun search(query: ReleaseSearchQuery, cursor: ReleaseSearchCursor? = null, refresh: Boolean = false): ReleaseMetadataRead<ReleaseSearchPage> {
        if (!validReleaseTitle(query.text)) releaseFailure(ReleaseFailureCode.INVALID_QUERY)
        if (cursor != null && (cursor.owner !== cursorOwner || cursor.query != query || cursor.nextOffset !in 1 until policy.maximumSearchRows)) {
            releaseFailure(ReleaseFailureCode.INVALID_CURSOR)
        }
        val offset = cursor?.nextOffset ?: 0
        val limit = minOf(policy.searchPageSize, policy.maximumSearchRows - offset)
        val result = read(MusicBrainzMetadataRequest.Search(query, offset, limit), refresh)
        val value = result.value as Value.Search
        val parsed = value.parsed
        val next = parsed.nextOffset?.let { ReleaseSearchCursor(cursorOwner, query, it) }
        val page = ReleaseSearchPage(query, offset, parsed.offset, parsed.count,
            parsed.hits.map { it.copy(metadata = it.metadata?.detached(), problems = it.problems.toList()) },
            parsed.traversal, parsed.problems.toList(), value.fetchedAt, next)
        return ReleaseMetadataRead(page, result.fromCache, result.stale)
    }

    /** UI owners use separate sessions; a superseded query cancels only that owner's waiter. */
    fun newSearchSession(): ReleaseSearchSession = ReleaseSearchSession(this)

    /** Cancellation closes admission and fully drains all owned jobs before closing owned HTTP. */
    suspend fun close(): Unit = withContext(NonCancellable) {
        closeLock.withLock {
            if (closeFinished) { closeFailure?.let { throw it }; return@withLock }
            lock.withLock { closed = true; cache.clear(); cacheBytes = 0; cacheObservations = 0 }
            lifetime.cancelAndJoin()
            try { if (ownsTransport) transport.close() }
            catch (_: Exception) { closeFailure = ReleaseMetadataException(ReleaseFailureCode.NETWORK) }
            closeFinished = true
            closeFailure?.let { throw it }
        }
    }

    private suspend fun read(request: MusicBrainzMetadataRequest, refresh: Boolean): Read {
        val ticket = lock.withLock {
            checkOpen()
            if (!refresh) cache.remove(request)?.let { cached ->
                cache[request] = cached
                val freshness = if (request is MusicBrainzMetadataRequest.Release) policy.releaseFreshForMs else policy.searchFreshForMs
                val age = clock.epochMs() - cached.value.fetchedAt
                // A backward wall clock is unknown freshness, never an indefinitely fresh entry.
                return Read(cached.value, fromCache = true, stale = age < 0 || age >= freshness)
            }
            if (isOffline()) releaseFailure(ReleaseFailureCode.OFFLINE)
            checkCooldown()
            val existing = tasks[request]
            val task = existing ?: run {
                // One active pipeline (possibly waiting for its start permit) plus <=8 pending.
                if (tasks.size >= policy.maximumPendingRequests + 1) releaseFailure(ReleaseFailureCode.QUEUE_FULL)
                Task(scope.async(start = CoroutineStart.LAZY) { execute(request) }).also { tasks[request] = it }
            }
            task.waiters++
            task
        }
        try {
            ticket.work.start()
            return Read(ticket.work.await(), fromCache = false, stale = false)
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    ticket.waiters--
                    if (ticket.waiters == 0) {
                        if (tasks[request] === ticket) tasks.remove(request)
                        if (!ticket.work.isCompleted) ticket.work.cancel()
                    }
                }
            }
        }
    }

    private suspend fun execute(request: MusicBrainzMetadataRequest): Value = networkSlot.withLock {
        checkAllowed()
        completedNetworkAt?.let { previous ->
            val now = clock.monotonicMs()
            val elapsed = if (now >= previous) now - previous else 0
            if (elapsed < policy.minimumIntervalMs) clock.sleep(policy.minimumIntervalMs - elapsed)
        }
        checkAllowed()
        val response = try {
            transport.get(request, ::checkAllowed)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ReleaseMetadataException) { throw failure }
        catch (_: Exception) { releaseFailure(ReleaseFailureCode.NETWORK) }
        finally {
            // Conservative completion-to-start spacing also covers a slow DNS/HTTP operation:
            // assigning the timestamp before DNS could otherwise permit HTTP starts <1100ms apart.
            completedNetworkAt = clock.monotonicMs()
        }
        if (response.status == 429 || response.status == 503) {
            val backoff = musicBrainzRetryAfterMs(response.retryAfter, clock.epochMs())
            // A response already received still constrains the application after its last UI
            // waiter cancels. Do not let cancellation discard an observed provider cooldown.
            withContext(NonCancellable) {
                lock.withLock {
                    val until = saturatingAdd(clock.monotonicMs(), backoff)
                    cooldownUntil = maxOf(cooldownUntil ?: until, until)
                }
            }
            currentCoroutineContext().ensureActive()
            releaseFailure(ReleaseFailureCode.PROVIDER_COOLDOWN, response.status, backoff)
        }
        currentCoroutineContext().ensureActive()
        if (response.status != 200) releaseFailure(ReleaseFailureCode.HTTP, response.status)
        val body = response.body ?: releaseFailure(ReleaseFailureCode.INVALID_RESPONSE)
        checkAllowed()
        val at = clock.epochMs()
        val value = when (request) {
            is MusicBrainzMetadataRequest.Release -> {
                val observation = MusicBrainzReleaseParser.release(body, request.mbid, at, response.etag)
                Value.Detail(observation, MusicBrainzReleaseParser.payloadBytes(observation.metadata).toLong() + 1_024)
            }
            is MusicBrainzMetadataRequest.Search -> {
                val parsed = MusicBrainzReleaseParser.search(body, request.offset, request.limit, policy.maximumSearchRows)
                Value.Search(parsed, at, MusicBrainzReleaseParser.payloadBytes(parsed.hits).toLong() + request.query.text.length * 2L + 1_024)
            }
        }
        lock.withLock {
            currentCoroutineContext().ensureActive()
            checkOpen()
            remember(request, value)
        }
        value
    }

    private suspend fun checkAllowed() {
        currentCoroutineContext().ensureActive()
        lock.withLock {
            checkOpen()
            if (isOffline()) releaseFailure(ReleaseFailureCode.OFFLINE)
            checkCooldown()
        }
    }

    /** Caller holds lock. */
    private fun checkOpen() { if (closed || !lifetime.isActive) releaseFailure(ReleaseFailureCode.CLOSED) }
    private fun checkCooldown() {
        val now = clock.monotonicMs()
        val until = cooldownUntil ?: return
        if (until > now) releaseFailure(ReleaseFailureCode.PROVIDER_COOLDOWN, retryAfterMs = positiveDifference(until, now))
        cooldownUntil = null
    }

    private fun remember(request: MusicBrainzMetadataRequest, value: Value) {
        cache.remove(request)?.let { cacheBytes -= it.value.payloadBytes; cacheObservations -= it.value.observations }
        if (value.payloadBytes > policy.maximumCacheBytes || value.observations > policy.maximumCachedObservations) return
        while (cache.isNotEmpty() && (cacheBytes + value.payloadBytes > policy.maximumCacheBytes ||
                cacheObservations + value.observations > policy.maximumCachedObservations)) {
            val removed = cache.remove(cache.keys.first())!!
            cacheBytes -= removed.value.payloadBytes; cacheObservations -= removed.value.observations
        }
        cache[request] = Cached(value)
        cacheBytes += value.payloadBytes; cacheObservations += value.observations
    }

    private class Task(val work: Deferred<Value>, var waiters: Int = 0)
    private data class Cached(val value: Value)
    private data class Read(val value: Value, val fromCache: Boolean, val stale: Boolean)
    private sealed interface Value {
        val fetchedAt: Long
        val payloadBytes: Long
        val observations: Int
        data class Detail(val observation: ReleaseObservation, override val payloadBytes: Long) : Value {
            override val fetchedAt: Long get() = observation.fetchedAtEpochMs
            override val observations: Int get() = 1
        }
        data class Search(val parsed: MusicBrainzReleaseParser.Search, override val fetchedAt: Long, override val payloadBytes: Long) : Value {
            override val observations: Int get() = maxOf(1, parsed.hits.size)
        }
    }
}

class ReleaseSearchSession internal constructor(private val source: MusicBrainzReleaseSource) {
    private val lock = Mutex()
    private var active: Job? = null
    private var closed = false

    suspend fun search(query: ReleaseSearchQuery, cursor: ReleaseSearchCursor? = null, refresh: Boolean = false): ReleaseMetadataRead<ReleaseSearchPage> = coroutineScope {
        val current = requireNotNull(currentCoroutineContext()[Job])
        lock.withLock {
            if (closed) releaseFailure(ReleaseFailureCode.CLOSED)
            active?.cancel(CancellationException("release_search_superseded"))
            active = current
        }
        try { source.search(query, cursor, refresh) }
        finally { withContext(NonCancellable) { lock.withLock { if (active === current) active = null } } }
    }

    suspend fun close(): Unit = withContext(NonCancellable) {
        val old = lock.withLock { closed = true; active.also { active = null } }
        old?.cancelAndJoin()
    }
}

private fun positiveDifference(later: Long, earlier: Long): Long =
    if (earlier < 0 && later > Long.MAX_VALUE + earlier) Long.MAX_VALUE else later - earlier

private fun saturatingAdd(value: Long, delta: Long): Long =
    if (delta > 0 && value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

/** Consumers may build mutable UI lists; none share the private cache's collection instances. */
private fun ReleaseSummaryMetadata.detached() = copy(
    releaseGroup = releaseGroup?.copy(secondaryTypes = releaseGroup.secondaryTypes.toList()),
    artistCredits = artistCredits.toList(),
    releaseEvents = releaseEvents.map { it.copy(countryCodes = it.countryCodes.toList()) },
    labels = labels.toList(), problems = problems.toList(),
)

private fun ReleaseObservation.detached() = copy(metadata = metadata.copy(
    release = metadata.release.detached(),
    media = metadata.media.map { medium -> medium.copy(tracks = medium.tracks.map { track ->
        track.copy(artistCredits = track.artistCredits.toList(), problems = track.problems.toList())
    }, problems = medium.problems.toList()) }, problems = metadata.problems.toList(),
))
