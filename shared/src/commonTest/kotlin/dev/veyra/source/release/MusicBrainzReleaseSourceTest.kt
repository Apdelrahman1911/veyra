package dev.veyra.source.release

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MusicBrainzReleaseSourceTest {
    private val f = ReleaseMetadataFixtures

    private class TestClock(private val scheduler: TestCoroutineScheduler) : ReleaseMetadataClock {
        var wallOffset = 0L
        override fun monotonicMs() = scheduler.currentTime
        override fun epochMs() = 1_700_000_000_000L + scheduler.currentTime + wallOffset
        override suspend fun sleep(ms: Long) { delay(ms) }
    }

    private class Transport(private val clock: TestClock) : MusicBrainzMetadataTransport {
        val calls = mutableListOf<Pair<Long, MusicBrainzMetadataRequest>>()
        var active = 0
        var maximumActive = 0
        var finished = 0
        var closed = 0
        var dnsDelayMs = 0L
        var searchCount = 300L
        var action: suspend (MusicBrainzMetadataRequest) -> MusicBrainzMetadataResponse = { request -> normal(request) }
        fun normal(request: MusicBrainzMetadataRequest): MusicBrainzMetadataResponse {
            val f = ReleaseMetadataFixtures
            val body = when (request) {
                is MusicBrainzMetadataRequest.Release -> f.release(id = request.mbid).toString()
                is MusicBrainzMetadataRequest.Search -> {
                    val rows = minOf(request.limit.toLong(), (searchCount - request.offset).coerceAtLeast(0)).toInt()
                    f.search(List(rows) { f.summary(f.id(30_000 + request.offset + it), "Authored edition ${request.offset + it}") },
                        request.offset, searchCount).toString()
                }
            }
            return MusicBrainzMetadataResponse(200, body, "\"authored\"")
        }
        override suspend fun get(request: MusicBrainzMetadataRequest, beforeNetwork: suspend () -> Unit): MusicBrainzMetadataResponse {
            beforeNetwork()
            if (dnsDelayMs > 0) delay(dnsDelayMs)
            beforeNetwork()
            calls += clock.monotonicMs() to request
            active++; maximumActive = maxOf(maximumActive, active)
            return try { action(request) } finally { active--; finished++ }
        }
        override fun close() { closed++ }
    }

    private fun TestScope.producer(transport: Transport, clock: TestClock, policy: ReleaseMetadataPolicy = ReleaseMetadataPolicy(),
        offline: () -> Boolean = { false }) = MusicBrainzReleaseSource(transport, this, offline, policy, clock,
        ownsTransport = true, workerDispatcher = StandardTestDispatcher(testScheduler))

    @Test fun completeReleaseValueHasNoMediaExecutionSideEffect() = runTest {
        val clock = TestClock(testScheduler); val transport = Transport(clock); val source = producer(transport, clock)
        try {
            val result = source.release(f.releaseId)
            assertEquals(f.title, result.value.completeTitle)
            assertFalse(result.fromCache)
            assertEquals(listOf(MusicBrainzMetadataRequest.Release(f.releaseId)), transport.calls.map { it.second })
            assertEquals("EP", result.value.metadata.release.releaseGroup?.primaryType)
        } finally { source.close() }
    }

    @Test fun concurrentDifferentRequestsUseOnePipelineAndAtLeast1100msSeparation() = runTest {
        val clock = TestClock(testScheduler); val transport = Transport(clock); val source = producer(transport, clock)
        try {
            val jobs = List(4) { async { source.release(f.id(it + 1)) } }
            jobs.awaitAll()
            assertEquals(listOf(0L, 1_100L, 2_200L, 3_300L), transport.calls.map { it.first })
            assertEquals(1, transport.maximumActive)
        } finally { source.close() }
    }

    @Test fun slowDnsCannotCompressActualHttpStartSpacing() = runTest {
        val clock = TestClock(testScheduler); val transport = Transport(clock).apply { dnsDelayMs = 2_000 }
        val source = producer(transport, clock)
        try {
            listOf(async { source.release(f.id(1)) }, async { source.release(f.id(2)) }).awaitAll()
            assertEquals(listOf(2_000L, 5_100L), transport.calls.map { it.first })
            assertEquals(1, transport.maximumActive)
        } finally { source.close() }
    }

    @Test fun identicalReadsCoalesceAndOneCancelledWaiterCannotCancelTheOther() = runTest {
        val gate = CompletableDeferred<Unit>()
        val clock = TestClock(testScheduler); val transport = Transport(clock)
        transport.action = { request -> gate.await(); transport.normal(request) }
        val source = producer(transport, clock)
        try {
            val first = async { source.release(f.releaseId) }
            val second = async { source.release(f.releaseId) }
            runCurrent()
            assertEquals(1, transport.calls.size)
            first.cancelAndJoin()
            assertEquals(1, transport.active)
            gate.complete(Unit)
            assertEquals(f.title, second.await().value.completeTitle)
            assertEquals(1, transport.calls.size)
        } finally { source.close() }
    }

    @Test fun cancellingTheLastWaiterCancelsAndDrainsThatTransportOperation() = runTest {
        val clock = TestClock(testScheduler); val transport = Transport(clock)
        transport.action = { awaitCancellation() }
        val source = producer(transport, clock)
        try {
            val pending = async { source.release(f.releaseId) }
            runCurrent()
            pending.cancelAndJoin()
            runCurrent()
            assertEquals(0, transport.active)
            assertEquals(1, transport.finished)
            transport.action = { transport.normal(it) }
            assertEquals(f.id(2), source.release(f.id(2)).value.observedReleaseMbid)
            assertTrue(transport.calls[1].first - transport.calls[0].first >= 1_100)
        } finally { source.close() }
    }

    @Test fun pendingQueueIsBoundedAndCancelledQueuedWorkNeverCallsTransport() = runTest {
        val gate = CompletableDeferred<Unit>()
        val clock = TestClock(testScheduler); val transport = Transport(clock)
        transport.action = { request -> gate.await(); transport.normal(request) }
        val source = producer(transport, clock, ReleaseMetadataPolicy(maximumPendingRequests = 2))
        try {
            val active = async { source.release(f.id(1)) }
            val queued = listOf(async { source.release(f.id(2)) }, async { source.release(f.id(3)) })
            runCurrent()
            assertEquals(ReleaseFailureCode.QUEUE_FULL, assertFailsWith<ReleaseMetadataException> { source.release(f.id(4)) }.code)
            val coalesced = async { source.release(f.id(1)) }
            runCurrent()
            queued.forEach { it.cancelAndJoin() }
            gate.complete(Unit)
            listOf(active, coalesced).awaitAll()
            assertEquals(1, transport.calls.size)
        } finally { source.close() }
    }

    @Test fun rateLimitedResponseSetsSharedCooldownWithNoAutomaticRetry() = runTest {
        val clock = TestClock(testScheduler); val transport = Transport(clock)
        transport.action = { MusicBrainzMetadataResponse(429, null) }
        val source = producer(transport, clock)
        try {
            val first = assertFailsWith<ReleaseMetadataException> { source.release(f.id(1)) }
            assertEquals(ReleaseFailureCode.PROVIDER_COOLDOWN, first.code)
            assertEquals(30_000L, first.retryAfterMs)
            advanceTimeBy(29_000)
            val second = assertFailsWith<ReleaseMetadataException> { source.release(f.id(2)) }
            assertEquals(1_000L, second.retryAfterMs)
            assertEquals(1, transport.calls.size)
            transport.action = { transport.normal(it) }
            advanceTimeBy(1_000)
            source.release(f.id(2))
            assertEquals(2, transport.calls.size)
        } finally { source.close() }
    }

    @Test fun provider503CooldownAppliesToAlreadyQueuedRequests() = runTest {
        val gate = CompletableDeferred<Unit>()
        val clock = TestClock(testScheduler); val transport = Transport(clock)
        transport.action = { gate.await(); MusicBrainzMetadataResponse(503, null, retryAfter = "120") }
        val source = producer(transport, clock)
        try {
            val active = async { runCatching { source.release(f.id(1)) } }
            val queued = async { runCatching { source.release(f.id(2)) } }
            runCurrent(); gate.complete(Unit)
            for (result in listOf(active.await(), queued.await())) {
                assertEquals(ReleaseFailureCode.PROVIDER_COOLDOWN, (result.exceptionOrNull() as ReleaseMetadataException).code)
            }
            assertEquals(1, transport.calls.size)
            advanceTimeBy(119_000)
            assertEquals(1_000L, assertFailsWith<ReleaseMetadataException> { source.search(ReleaseSearchQuery("authored")) }.retryAfterMs)
            assertEquals(1, transport.calls.size)
        } finally { source.close() }
    }

    @Test fun cancellingAtAnAlreadyReceivedThrottleResponseDoesNotDiscardItsCooldown() = runTest {
        val clock = TestClock(testScheduler); val transport = Transport(clock)
        transport.action = {
            currentCoroutineContext().cancel(CancellationException("authored response-boundary cancellation"))
            MusicBrainzMetadataResponse(429, null, retryAfter = "120")
        }
        val source = producer(transport, clock)
        try {
            val cancelled = async { source.release(f.id(1)) }
            assertFailsWith<CancellationException> { cancelled.await() }
            assertEquals(1, transport.finished)
            val cooldown = assertFailsWith<ReleaseMetadataException> { source.release(f.id(2)) }
            assertEquals(ReleaseFailureCode.PROVIDER_COOLDOWN, cooldown.code)
            assertEquals(120_000L, cooldown.retryAfterMs)
            assertEquals(1, transport.calls.size)
        } finally { source.close() }
    }

    @Test fun offlineReadsOnlyCachedValuesAndNeverRefreshesOrPolls() = runTest {
        var offline = false
        val clock = TestClock(testScheduler); val transport = Transport(clock); val source = producer(transport, clock, offline = { offline })
        try {
            source.release(f.releaseId)
            offline = true
            assertTrue(source.release(f.releaseId).fromCache)
            assertEquals(ReleaseFailureCode.OFFLINE, assertFailsWith<ReleaseMetadataException> { source.release(f.releaseId, refresh = true) }.code)
            assertEquals(ReleaseFailureCode.OFFLINE, assertFailsWith<ReleaseMetadataException> { source.release(f.id(999)) }.code)
            assertEquals(ReleaseFailureCode.OFFLINE, assertFailsWith<ReleaseMetadataException> { source.search(ReleaseSearchQuery("new query")) }.code)
            advanceTimeBy(8 * 24 * 60 * 60_000L)
            assertTrue(source.release(f.releaseId).stale)
            assertEquals(1, transport.calls.size)
        } finally { source.close() }
    }

    @Test fun wallClockRollbackOrAgeIsLabeledStaleWithoutImplicitRefresh() = runTest {
        val clock = TestClock(testScheduler); val transport = Transport(clock); val source = producer(transport, clock)
        try {
            source.release(f.releaseId)
            clock.wallOffset = -10_000
            assertTrue(source.release(f.releaseId).stale)
            clock.wallOffset = 8 * 24 * 60 * 60_000L
            assertTrue(source.release(f.releaseId).stale)
            assertEquals(1, transport.calls.size)
            assertFalse(source.release(f.releaseId, refresh = true).fromCache)
            assertEquals(2, transport.calls.size)
        } finally { source.close() }
    }

    @Test fun lruObservationAndLogicalByteLimitsDoNotOwnSavedLibraryRecords() = runTest {
        var offline = false
        val clock = TestClock(testScheduler); val transport = Transport(clock)
        val source = producer(transport, clock, ReleaseMetadataPolicy(maximumCachedObservations = 2), offline = { offline })
        try {
            val savedOutsideCache = source.release(f.id(1)).value
            source.release(f.id(2)); source.release(f.id(1)); source.release(f.id(3))
            offline = true
            assertTrue(source.release(f.id(1)).fromCache)
            assertEquals(ReleaseFailureCode.OFFLINE, assertFailsWith<ReleaseMetadataException> { source.release(f.id(2)) }.code)
            assertEquals(f.id(1), savedOutsideCache.observedReleaseMbid)
        } finally { source.close() }
        offline = false
        val tiny = producer(Transport(clock), clock, ReleaseMetadataPolicy(maximumCacheBytes = 1), offline = { offline })
        try {
            val delivered = tiny.release(f.id(4)).value
            offline = true
            assertEquals(ReleaseFailureCode.OFFLINE, assertFailsWith<ReleaseMetadataException> { tiny.release(f.id(4)) }.code)
            assertEquals(f.id(4), delivered.observedReleaseMbid)
        } finally { tiny.close() }
    }

    @Test fun searchStopsAt200ObservedRowsWithoutClaimingTheWholeCatalogue() = runTest {
        val clock = TestClock(testScheduler); val transport = Transport(clock); val source = producer(transport, clock)
        try {
            val query = ReleaseSearchQuery("authored", albumsOnly = true)
            var page = source.search(query).value
            val hits = page.hits.toMutableList()
            while (page.nextCursor != null) { page = source.search(query, page.nextCursor).value; hits += page.hits }
            assertEquals(200, hits.size)
            assertEquals((0 until 200).toList(), hits.map { it.absoluteOrdinal })
            assertEquals(ReleaseSearchTraversal.INCOMPLETE, page.traversal)
            assertTrue(page.problems.any { it.code == ReleaseProblemCode.SEARCH_LIMIT })
            assertEquals((0 until 200 step 25).toList(), transport.calls.map { (it.second as MusicBrainzMetadataRequest.Search).offset })
            assertTrue(hits.all { it.detailState == ReleaseMembershipStatus.DETAILS_UNLOADED })
        } finally { source.close() }
    }

    @Test fun searchCursorsCannotCrossQueryOrSourceOwners() = runTest {
        val clock = TestClock(testScheduler); val transport = Transport(clock); val source = producer(transport, clock)
        val otherTransport = Transport(clock); val other = producer(otherTransport, clock)
        try {
            val query = ReleaseSearchQuery("first")
            val cursor = assertNotNull(source.search(query).value.nextCursor)
            assertEquals(ReleaseFailureCode.INVALID_CURSOR, assertFailsWith<ReleaseMetadataException> {
                source.search(ReleaseSearchQuery("second"), cursor)
            }.code)
            assertEquals(ReleaseFailureCode.INVALID_CURSOR, assertFailsWith<ReleaseMetadataException> { other.search(query, cursor) }.code)
            assertTrue(otherTransport.calls.isEmpty())
        } finally { source.close(); other.close() }
    }

    @Test fun supersededSearchSessionCancelsItsWaiterNotAnotherOwnersSharedRead() = runTest {
        val gate = CompletableDeferred<Unit>()
        val clock = TestClock(testScheduler); val transport = Transport(clock)
        transport.action = { request ->
            if ((request as MusicBrainzMetadataRequest.Search).query.text == "old") gate.await()
            transport.normal(request)
        }
        val source = producer(transport, clock); val session = source.newSearchSession()
        try {
            val old = async { session.search(ReleaseSearchQuery("old")) }
            val independent = async { source.search(ReleaseSearchQuery("old")) }
            runCurrent()
            val fresh = async { session.search(ReleaseSearchQuery("new")) }
            runCurrent()
            assertTrue(old.isCancelled)
            assertFalse(independent.isCancelled)
            gate.complete(Unit)
            independent.await(); fresh.await()
            assertEquals(listOf("old", "new"), transport.calls.map { (it.second as MusicBrainzMetadataRequest.Search).query.text })
        } finally { session.close(); source.close() }
    }

    @Test fun closeDrainsOwnedAndQueuedJobsAndIsIdempotent() = runTest {
        val clock = TestClock(testScheduler); val transport = Transport(clock)
        transport.action = { awaitCancellation() }
        val source = producer(transport, clock)
        val active = async { source.release(f.id(1)) }
        val queued = async { source.release(f.id(2)) }
        runCurrent()
        source.close()
        active.join(); queued.join()
        assertTrue(active.isCancelled)
        assertTrue(queued.isCancelled)
        assertEquals(0, transport.active)
        assertEquals(1, transport.finished)
        assertEquals(1, transport.closed)
        source.close()
        assertEquals(1, transport.closed)
        assertEquals(ReleaseFailureCode.CLOSED, assertFailsWith<ReleaseMetadataException> { source.release(f.releaseId) }.code)
    }

    @Test fun malformedOrFailedRequestsAreNotCachedAndMessagesStaySanitized() = runTest {
        var offline = false
        val clock = TestClock(testScheduler); val transport = Transport(clock)
        val source = producer(transport, clock, offline = { offline })
        try {
            transport.action = { throw IllegalStateException("authored-private-diagnostic") }
            val failure = assertFailsWith<ReleaseMetadataException> { source.release(f.releaseId) }
            assertEquals(ReleaseFailureCode.NETWORK, failure.code)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains("authored-private"))
            transport.action = { MusicBrainzMetadataResponse(200, "{}") }
            assertFailsWith<ReleaseMetadataException> { source.release(f.releaseId) }
            offline = true
            assertEquals(ReleaseFailureCode.OFFLINE, assertFailsWith<ReleaseMetadataException> { source.release(f.releaseId) }.code)
        } finally { source.close() }
    }

    @Test fun consumerMutableViewsDoNotChangeThePrivateCachedCoreValue() = runTest {
        val clock = TestClock(testScheduler); val transport = Transport(clock); val source = producer(transport, clock)
        try {
            val first = source.release(f.releaseId).value
            val media = first.metadata.media
            if (media is MutableList<*>) media.clear()
            val second = source.release(f.releaseId)
            assertTrue(second.fromCache)
            assertEquals(1, second.value.metadata.media.size)
            assertEquals(1, transport.calls.size)
        } finally { source.close() }
    }

    @Test fun offlineChangeDuringProviderWaitPreventsTheNextRequest() = runTest {
        var offline = false
        val clock = TestClock(testScheduler); val transport = Transport(clock); val source = producer(transport, clock, offline = { offline })
        try {
            source.release(f.id(1))
            val pending = async { runCatching { source.release(f.id(2)) } }
            runCurrent()
            offline = true
            advanceTimeBy(1_100); runCurrent()
            assertEquals(ReleaseFailureCode.OFFLINE, (pending.await().exceptionOrNull() as ReleaseMetadataException).code)
            assertEquals(1, transport.calls.size)
        } finally { source.close() }
    }
}
