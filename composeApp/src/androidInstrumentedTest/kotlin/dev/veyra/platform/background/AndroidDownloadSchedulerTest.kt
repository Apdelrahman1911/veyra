package dev.veyra.platform.background

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.workDataOf
import dev.veyra.downloads.DownloadBackgroundDrainAck
import dev.veyra.downloads.DownloadIdleDecision
import dev.veyra.downloads.DownloadIdleTicket
import dev.veyra.downloads.DownloadScheduling
import dev.veyra.downloads.DownloadSchedulingKey
import dev.veyra.downloads.DownloadWakePlan
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Simulated WorkManager ACK/Room scheduling ports, plus inspection of real WorkRequest specs.
 * These are NOT real WorkManager/cold-process/reboot/foreground-quota/transfer/Room tests.
 * Instrumented methods are authored here; execution requires a separately approved device run.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDownloadSchedulerTest {
    @Test fun future24HourRetryPublishesSeparateWakeAndRetiresZeroLeaseForeground() = runBlocking {
        val scheduling = FakeScheduling(plan(network = NOW + DAY))
        val backend = FakeBackend()
        val gate = AndroidDownloadExecutionGate(ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() }, isWorkUnfinished = { true }) { fail("Future work must not enqueue an immediate successor") }
        var foregroundRequests = 0
        val scheduler = scheduler(backend) { foregroundRequests++ }
        assertTrue(gate.foregroundStarted("future-retry-worker"))

        withTimeout(2_000) { AndroidDownloadWorkerLifetime(gate, scheduler).retireWhenIdle("future-retry-worker", scheduling) }

        assertTrue(backend.installed)
        assertEquals(1, backend.installCalls)
        assertEquals(listOf(Wake(DownloadWakeKind.NETWORK, NOW + DAY, NOW)), backend.acknowledged)
        assertEquals(0, foregroundRequests)
        assertEquals(0, gate.leaseCount.value)
        assertEquals(1, scheduling.retirementCallbacks)
        assertTrue(scheduling.confirmedOnMain)
        assertFalse("Native owner must already be retired", gate.retireIfIdle("future-retry-worker"))
        assertEquals("Publishing a wake must not clear the durable retry", NOW + DAY, scheduling.current.nextNetworkEpochMs)
    }

    @Test fun publicationAckMustPrecedeTheSharedRetirementCallback() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val ack = CompletableDeferred<Unit>()
        val backend = FakeBackend().apply { replacing = { entered.complete(Unit); ack.await() } }
        val scheduling = FakeScheduling(plan(network = NOW + DAY))
        val gate = AndroidDownloadExecutionGate(ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() }, isWorkUnfinished = { true }) { }
        assertTrue(gate.foregroundStarted("ack-worker"))
        val retire = async { AndroidDownloadWorkerLifetime(gate, scheduler(backend)).retireWhenIdle("ack-worker", scheduling) }
        try {
            withTimeout(2_000) { entered.await() }
            assertEquals(0, scheduling.retirementCallbacks)
            assertNotNull(gate.idleCandidate("ack-worker"))
            assertFalse(retire.isCompleted)
            assertTrue(backend.acknowledged.isEmpty())
        } finally { ack.complete(Unit) }
        withTimeout(2_000) { retire.await() }
        assertEquals(1, scheduling.retirementCallbacks)
        assertEquals(1, backend.acknowledged.size)
    }

    @Test fun acquireAndReleaseDuringPublicationInvalidatesTheNativeEpoch() = runBlocking {
        val gate = AndroidDownloadExecutionGate(ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() }, isWorkUnfinished = { true }) { fail("A real foreground owner already exists") }
        assertTrue(gate.foregroundStarted("epoch-worker"))
        val backend = FakeBackend().apply { replacing = { gate.acquire {}.close() } }
        val scheduling = FakeScheduling(plan(network = NOW + DAY))

        withTimeout(2_000) { AndroidDownloadWorkerLifetime(gate, scheduler(backend)).retireWhenIdle("epoch-worker", scheduling) }

        assertEquals(0, gate.leaseCount.value)
        assertEquals("First callback must refuse an acquire+release epoch change", 2, scheduling.retirementCallbacks)
        assertEquals("A second confirmation reuses the already durable wake", 1, backend.acknowledged.size)
        assertFalse(gate.retireIfIdle("epoch-worker"))
    }

    @Test fun ownedWorkWithZeroNativeLeasesDoesNotRetireOrSpinOnItsOwnDecisionHint() = runBlocking {
        val decisions = Channel<Unit>(Channel.UNLIMITED)
        val scheduling = FakeScheduling(plan(local = NOW, due = true)).apply {
            deciding = { schedulingChanges.value++; decisions.trySend(Unit) }
        }
        val backend = FakeBackend()
        val gate = AndroidDownloadExecutionGate(ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() }, isWorkUnfinished = { true }) { }
        assertTrue(gate.foregroundStarted("pending-claim"))
        val retire = async {
            AndroidDownloadWorkerLifetime(gate, scheduler(backend), hintTimeoutMs = 60_000)
                .retireWhenIdle("pending-claim", scheduling)
        }
        try {
            withTimeout(2_000) { decisions.receive() }
            assertEquals(0, gate.leaseCount.value)
            assertFalse(retire.isCompleted)
            assertEquals(0, scheduling.retirementCallbacks)
            assertNull("The decision's own hint must not create a hot authoritative-read loop",
                withTimeoutOrNull(150) { decisions.receive() })
            scheduling.replace(plan(network = NOW + DAY, revision = 2))
            withTimeout(2_000) { retire.await() }
            assertEquals(1, scheduling.retirementCallbacks)
        } finally {
            retire.cancelAndJoin()
            gate.foregroundEnded("pending-claim")
        }
    }

    @Test fun anEarlierPlanCommittedDuringAckCannotBeOverwrittenByTheOldPublisher() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val ack = CompletableDeferred<Unit>()
        val backend = FakeBackend().apply {
            replacing = { if (requests.size == 1) { entered.complete(Unit); ack.await() } }
        }
        val scheduling = FakeScheduling(plan(network = NOW + DAY))
        val scheduler = scheduler(backend)
        val first = async { scheduler.repair(scheduling) }
        withTimeout(2_000) { entered.await() }
        val earlier = plan(network = NOW + 5_000, revision = 2)
        scheduling.replace(earlier)
        val second = async { scheduler.repair(scheduling) }
        ack.complete(Unit)
        withTimeout(2_000) { assertEquals(earlier, first.await().plan); assertEquals(earlier, second.await().plan) }

        assertEquals(listOf(NOW + DAY, NOW + 5_000), backend.acknowledged.map { it.epochMs })
        assertEquals(NOW + 5_000, backend.records.getValue(DownloadWakeKind.NETWORK).single().requestedEpochMs)
        assertEquals(1, backend.installCalls)
    }

    @Test fun sameKeyDueCrossingIsRereadAndDelegatedOnlyByTheWake() = runBlocking {
        val before = plan(network = NOW + 1, due = false)
        val after = before.copy(hasDueWork = true)
        var clock = NOW
        val scheduling = FakeScheduling(before)
        val backend = FakeBackend().apply {
            replacing = { clock = NOW + 1; scheduling.replace(after) }
        }
        var foregroundRequests = 0
        val scheduler = AndroidDownloadScheduler(backend, { foregroundRequests++ }, nowEpochMs = { clock })
        val published = scheduler.wake(scheduling, DownloadWakeIdentity(DownloadWakeKind.LOCAL, "wake"))

        assertEquals(after, published.plan)
        assertFalse(published.retryCurrentWake)
        assertEquals("A key comparison alone misses the due-time transition", 1, foregroundRequests)
        assertEquals(1, backend.acknowledged.size)
    }

    @Test fun actualDueWakeWaitsForForegroundEnqueueAckButTakesNoTransferLease() = runBlocking {
        val entered = CompletableDeferred<OneTimeWorkRequest>()
        val ack = CompletableDeferred<Unit>()
        val gate = AndroidDownloadExecutionGate(ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() }, isWorkUnfinished = { true }) { work -> entered.complete(work); ack.await() }
        val backend = FakeBackend()
        val scheduling = FakeScheduling(plan(local = NOW, due = true))
        val scheduler = scheduler(backend, gate::requestForegroundWork)
        val wake = async { scheduler.wake(scheduling, DownloadWakeIdentity(DownloadWakeKind.LOCAL, "local-wake")) }
        val work = withTimeout(2_000) { entered.await() }
        try {
            assertTrue(backend.installed)
            assertFalse(wake.isCompleted)
            assertEquals(0, gate.leaseCount.value)
            assertNull("An enqueue request is not an actual foreground ACK", gate.idleCandidate(work.id.toString()))
            assertTrue(backend.acknowledged.isEmpty())
        } finally { ack.complete(Unit) }
        withTimeout(2_000) { assertFalse(wake.await().retryCurrentWake) }
        assertEquals(0L, work.workSpec.initialDelay)
        assertTrue(AndroidDownloadExecutionGate.WORK_NAME in work.tags)
        assertTrue(gate.foregroundStarted(work.id.toString()))
        assertTrue(gate.retireIfIdle(work.id.toString()))
    }

    @Test fun periodicRepairPublishesDueLocalAndNetworkWakesButNeverDelegatesForeground() = runBlocking {
        val backend = FakeBackend()
        var foregroundRequests = 0
        val scheduler = scheduler(backend) { foregroundRequests++ }
        val due = FakeScheduling(plan(local = NOW, network = NOW, due = true))

        scheduler.repair(due)

        assertEquals(0, foregroundRequests)
        assertEquals(listOf(DownloadWakeKind.LOCAL, DownloadWakeKind.NETWORK), backend.acknowledged.map { it.kind })
        assertTrue(backend.acknowledged.all { it.epochMs == NOW })
        assertTrue(backend.installWasFirst)
    }

    @Test fun disconnectedOwnDueWakeUsesBoundedRetryWithoutSelfReplacingOrStartingForeground() = runBlocking {
        val backend = FakeBackend().apply {
            records[DownloadWakeKind.NETWORK] = listOf(record("network-wake", WorkInfo.State.RUNNING, NOW))
        }
        var foregroundRequests = 0
        val scheduler = scheduler(backend) { foregroundRequests++ }
        val scheduling = FakeScheduling(plan(local = NOW + 5_000, network = NOW, due = false))

        val publication = scheduler.wake(scheduling, DownloadWakeIdentity(DownloadWakeKind.NETWORK, "network-wake"))

        assertTrue(publication.retryCurrentWake)
        assertEquals(0, foregroundRequests)
        assertEquals(listOf(DownloadWakeKind.LOCAL), backend.acknowledged.map { it.kind })
        assertTrue(downloadSchedulingRetry(0) is ListenableWorker.Result.Retry)
        assertTrue(downloadSchedulingRetry(2) is ListenableWorker.Result.Retry)
        assertTrue(downloadSchedulingRetry(3) is ListenableWorker.Result.Failure)
    }

    @Test fun runningWakeDoesNotCountAsFutureCoverageAfterItReturns() = runBlocking {
        val backend = FakeBackend().apply {
            records[DownloadWakeKind.NETWORK] = listOf(record("network-wake", WorkInfo.State.RUNNING, NOW + DAY))
        }
        val publication = scheduler(backend).wake(
            FakeScheduling(plan(network = NOW + DAY)), DownloadWakeIdentity(DownloadWakeKind.NETWORK, "network-wake"),
        )

        assertFalse(publication.retryCurrentWake)
        assertEquals(1, backend.acknowledged.size)
        assertNotEquals("network-wake", backend.records.getValue(DownloadWakeKind.NETWORK).single().worker)
    }

    @Test fun cancellationDuringSelfReplacementStillDrainsTheBoundedDurableAck() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val ack = CompletableDeferred<Unit>()
        val backend = FakeBackend().apply {
            records[DownloadWakeKind.NETWORK] = listOf(record("running", WorkInfo.State.RUNNING, NOW))
            replacing = { entered.complete(Unit); ack.await() }
        }
        val scheduler = scheduler(backend)
        val wake = async {
            scheduler.wake(FakeScheduling(plan(network = NOW + DAY)), DownloadWakeIdentity(DownloadWakeKind.NETWORK, "running"))
        }
        withTimeout(2_000) { entered.await() }
        wake.cancel()
        try {
            assertFalse("The replacement ACK must finish even if the old wake is cancelled", wake.isCompleted)
            assertTrue(backend.acknowledged.isEmpty())
        } finally { ack.complete(Unit) }
        withTimeout(2_000) { wake.join() }
        assertTrue(wake.isCancelled)
        assertEquals(1, backend.acknowledged.size)
    }

    @Test fun anAlreadyEnqueuedEarlierWakeIsReusedButLaterRetryBackoffIsNot() = runBlocking {
        val backend = FakeBackend().apply {
            records[DownloadWakeKind.LOCAL] = listOf(record("earlier", WorkInfo.State.ENQUEUED, NOW + 100))
            records[DownloadWakeKind.NETWORK] = listOf(
                record("backed-off", WorkInfo.State.ENQUEUED, NOW, next = NOW + DAY, attempts = 2),
            )
        }
        scheduler(backend).repair(FakeScheduling(plan(local = NOW + 500, network = NOW + 5_000)))
        assertEquals(listOf(DownloadWakeKind.NETWORK), backend.acknowledged.map { it.kind })
        assertEquals(NOW + 5_000, backend.acknowledged.single().epochMs)
    }

    @Test fun longFutureWakeNeverOccupiesTheImmediateForegroundUniqueChain() = runBlocking {
        val backend = FakeBackend()
        val immediate = Channel<OneTimeWorkRequest>(Channel.UNLIMITED)
        val gate = AndroidDownloadExecutionGate(ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() }, isWorkUnfinished = { true }) { immediate.send(it) }
        scheduler(backend, gate::requestForegroundWork).repair(FakeScheduling(plan(network = NOW + DAY)))

        val acquisition = async { gate.acquire {} }
        val work = withTimeout(2_000) { immediate.receive() }
        assertEquals(0L, work.workSpec.initialDelay)
        assertEquals(VeyraDownloadWorker::class.java.name, work.workSpec.workerClassName)
        assertTrue(AndroidDownloadExecutionGate.WORK_NAME in work.tags)
        assertEquals(NOW + DAY, backend.records.getValue(DownloadWakeKind.NETWORK).single().requestedEpochMs)
        assertTrue(gate.foregroundStarted(work.id.toString()))
        withTimeout(2_000) { acquisition.await() }.close()
        assertTrue(gate.retireIfIdle(work.id.toString()))
    }

    @Test fun coverageInstallationAckPrecedesEvenNativeLeaseRegistration() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val ack = CompletableDeferred<Unit>()
        val backend = FakeBackend().apply { installing = { entered.complete(Unit); ack.await() } }
        val work = Channel<OneTimeWorkRequest>(Channel.UNLIMITED)
        val scheduler = scheduler(backend)
        val gate = AndroidDownloadExecutionGate(ensureCoverage = scheduler::ensureInstalled, drainOwners = { DownloadBackgroundDrainAck() }, isWorkUnfinished = { true }) { work.send(it) }
        val acquisition = async { gate.acquire {} }
        try {
            withTimeout(2_000) { entered.await() }
            assertEquals(0, gate.leaseCount.value)
            assertFalse(acquisition.isCompleted)
            assertTrue(work.tryReceive().isFailure)
        } finally { ack.complete(Unit) }
        val request = withTimeout(2_000) { work.receive() }
        assertTrue(backend.installed)
        assertTrue(gate.foregroundStarted(request.id.toString()))
        withTimeout(2_000) { acquisition.await() }.close()
        assertTrue(gate.retireIfIdle(request.id.toString()))
    }

    @Test fun coverageFailureRejectsNativeAdmissionAndWakePublicationWithTypedFailure() = runBlocking {
        val backend = FakeBackend().apply { installing = { error("sensitive backend diagnostics") } }
        val scheduler = scheduler(backend)
        var enqueues = 0
        val gate = AndroidDownloadExecutionGate(ensureCoverage = scheduler::ensureInstalled, drainOwners = { DownloadBackgroundDrainAck() }, isWorkUnfinished = { true }) { enqueues++ }

        val failure = restricted { gate.acquire {} }
        restricted { scheduler.repair(FakeScheduling(plan(network = NOW + DAY))) }

        assertEquals(FailureCode.BACKGROUND_RESTRICTED, failure.failure.code)
        assertFalse(failure.message.orEmpty().contains("sensitive"))
        assertEquals(0, enqueues)
        assertEquals(0, gate.leaseCount.value)
        assertFalse(backend.installed)
        assertTrue(backend.requests.isEmpty())
    }

    @Test fun cancelledInstallationDoesNotMemoizeUnacknowledgedCoverage() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val ack = CompletableDeferred<Unit>()
        val backend = FakeBackend().apply { installing = { entered.complete(Unit); ack.await() } }
        val scheduler = scheduler(backend)
        val first = async { scheduler.ensureInstalled() }
        withTimeout(2_000) { entered.await() }
        first.cancelAndJoin()
        assertFalse(backend.installed)
        ack.complete(Unit)
        scheduler.ensureInstalled()
        scheduler.ensureInstalled()
        assertTrue(backend.installed)
        assertEquals("Only a real ACK is memoized; one retry is needed", 2, backend.installCalls)
    }

    @Test fun failedPublicationDoesNotRetireForegroundOrRemoveRepairCoverage() = runBlocking {
        val backend = FakeBackend().apply { replacing = { error("sensitive publication diagnostics") } }
        val scheduling = FakeScheduling(plan(network = NOW + DAY))
        val gate = AndroidDownloadExecutionGate(ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() }, isWorkUnfinished = { true }) { }
        assertTrue(gate.foregroundStarted("failed-publication"))

        restricted { AndroidDownloadWorkerLifetime(gate, scheduler(backend)).retireWhenIdle("failed-publication", scheduling) }

        assertTrue(backend.installed)
        assertEquals(0, scheduling.retirementCallbacks)
        assertNotNull(gate.idleCandidate("failed-publication"))
        // The real worker's finally performs this exact-owner cleanup and returns terminal failure,
        // NEVER retry in the foreground lane. Persistent repair may reconstruct the missing wake.
        gate.foregroundEnded("failed-publication")
        assertTrue(downloadSchedulingFailed("foreground_scheduling") is ListenableWorker.Result.Failure)
        assertEquals(0, gate.leaseCount.value)
    }

    @Test fun aFreshStoreReadFailureCannotBecomeCachedIdleSuccess() = runBlocking {
        val backend = FakeBackend()
        val scheduling = FakeScheduling(plan()).apply {
            reading = { throw VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "authored_store_failure")) }
        }
        val gate = AndroidDownloadExecutionGate(ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() }, isWorkUnfinished = { true }) { }
        assertTrue(gate.foregroundStarted("read-failure"))
        val failure = restricted { AndroidDownloadWorkerLifetime(gate, scheduler(backend)).retireWhenIdle("read-failure", scheduling) }
        assertEquals(FailureCode.RECOVERY_REQUIRED, failure.failure.code)
        assertTrue(backend.installed)
        assertEquals(0, scheduling.retirementCallbacks)
        assertNotNull(gate.idleCandidate("read-failure"))
        gate.foregroundEnded("read-failure")
    }

    @Test fun publisherConvergenceAndIdleConflictsAreFinite() = runBlocking {
        val backend = FakeBackend()
        val scheduling = FakeScheduling(plan(network = NOW + DAY)).apply {
            reading = { replace(current.copy(key = current.key.copy(revision = current.key.revision + 1))) }
        }
        val failure = restricted { scheduler(backend).repair(scheduling) }
        assertEquals("download_wake_plan_changed", failure.failure.detail)
        assertEquals(5, scheduling.reads)
        assertTrue(backend.installed)

        val stable = FakeScheduling(plan(network = NOW + DAY)).apply { rejectConfirmation = true }
        val gate = AndroidDownloadExecutionGate(ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() }, isWorkUnfinished = { true }) { }
        assertTrue(gate.foregroundStarted("conflicts"))
        val idleFailure = restricted {
            AndroidDownloadWorkerLifetime(gate, scheduler(backend), maximumIdleConflicts = 2).retireWhenIdle("conflicts", stable)
        }
        assertEquals("download_idle_confirmation_changed", idleFailure.failure.detail)
        assertEquals(2, stable.confirmations)
        gate.foregroundEnded("conflicts")
    }

    @Test fun schedulingOnlyEmptyPlanRetainsRepairWithoutWakeOrForegroundActivation() = runBlocking {
        val backend = FakeBackend()
        var foregroundRequests = 0
        val scheduler = scheduler(backend) { foregroundRequests++ }
        val scheduling = FakeScheduling(plan())
        scheduler.repair(scheduling)
        scheduler.repair(scheduling)
        assertTrue(backend.installed)
        assertEquals(1, backend.installCalls)
        assertEquals(0, foregroundRequests)
        assertTrue(backend.requests.isEmpty())
        assertEquals(0, scheduling.retirementCallbacks)
        // This native fake-port test does not assert how a real COMPLETE library produces the
        // empty durable plan; shared/AppContainer tests cover that separate cold-bootstrap seam.
    }

    @Test fun requestSpecsKeepLocalNetworkRepairAndForegroundLanesDistinct() {
        val local = downloadWakeRequest(DownloadWakeKind.LOCAL, NOW + 5_000, NOW)
        val network = downloadWakeRequest(DownloadWakeKind.NETWORK, NOW + DAY, NOW)
        val repair = downloadRepairRequest()
        assertEquals(5_000L, local.workSpec.initialDelay)
        assertEquals(DAY, network.workSpec.initialDelay)
        assertEquals(NetworkType.NOT_REQUIRED, local.workSpec.constraints.requiredNetworkType)
        assertEquals(NetworkType.CONNECTED, network.workSpec.constraints.requiredNetworkType)
        assertEquals(NetworkType.NOT_REQUIRED, repair.workSpec.constraints.requiredNetworkType)
        assertEquals(TimeUnit.MINUTES.toMillis(15), repair.workSpec.intervalDuration)
        assertEquals(VeyraDownloadRepairWorker::class.java.name, repair.workSpec.workerClassName)
        assertEquals(VeyraDownloadWakeWorker::class.java.name, local.workSpec.workerClassName)
        assertEquals(VeyraDownloadWakeWorker::class.java.name, network.workSpec.workerClassName)
        assertEquals(setOf(AndroidDownloadScheduler.INPUT_VERSION, AndroidDownloadScheduler.INPUT_KIND,
            AndroidDownloadScheduler.INPUT_EPOCH_MS), network.workSpec.input.keyValueMap.keys)
        assertFalse(AndroidDownloadExecutionGate.WORK_NAME in local.tags)
        assertFalse(AndroidDownloadExecutionGate.WORK_NAME in network.tags)
        assertFalse(AndroidDownloadExecutionGate.WORK_NAME in repair.tags)
        assertEquals(4, (DownloadWakeKind.entries.map { it.workName } +
            AndroidDownloadScheduler.REPAIR_WORK_NAME + AndroidDownloadExecutionGate.WORK_NAME).toSet().size)
        assertEquals(0L, downloadWakeRequest(DownloadWakeKind.LOCAL, NOW - 1, NOW).workSpec.initialDelay)
    }

    @Test fun unversionedMalformedAndNegativeTimestampWakeInputsAreRejected() {
        assertNull(AndroidDownloadScheduler.wakeIdentity(workDataOf(), "worker"))
        assertNull(AndroidDownloadScheduler.wakeIdentity(workDataOf(
            AndroidDownloadScheduler.INPUT_VERSION to 2, AndroidDownloadScheduler.INPUT_KIND to "LOCAL",
            AndroidDownloadScheduler.INPUT_EPOCH_MS to NOW,
        ), "worker"))
        assertNull(AndroidDownloadScheduler.wakeIdentity(AndroidDownloadScheduler.wakeInput(DownloadWakeKind.LOCAL, -1), "worker"))
        assertNull(AndroidDownloadScheduler.wakeIdentity(AndroidDownloadScheduler.repairInput(), "worker"))
        assertEquals(DownloadWakeIdentity(DownloadWakeKind.NETWORK, "worker"), AndroidDownloadScheduler.wakeIdentity(
            AndroidDownloadScheduler.wakeInput(DownloadWakeKind.NETWORK, NOW + DAY), "worker",
        ))
        assertTrue(AndroidDownloadScheduler.validRepairInput(AndroidDownloadScheduler.repairInput()))
        assertFalse(AndroidDownloadScheduler.validRepairInput(AndroidDownloadScheduler.wakeInput(DownloadWakeKind.LOCAL, NOW)))
    }

    private suspend fun restricted(action: suspend () -> Unit): VeyraException {
        try { action() }
        catch (error: VeyraException) { return error }
        fail("Expected a typed scheduling/recovery failure")
        throw AssertionError()
    }

    private fun scheduler(backend: FakeBackend, foreground: suspend () -> Unit = {}) =
        AndroidDownloadScheduler(backend, foreground, nowEpochMs = { NOW })

    private data class Wake(val kind: DownloadWakeKind, val epochMs: Long, val now: Long)
    private class FakeBackend : DownloadWorkBackend {
        var installed = false
        var installCalls = 0
        var installWasFirst = true
        var installing: suspend () -> Unit = {}
        var replacing: suspend (Wake) -> Unit = {}
        val requests = mutableListOf<Wake>()
        val acknowledged = mutableListOf<Wake>()
        val records = mutableMapOf<DownloadWakeKind, List<DownloadWakeRecord>>()

        override suspend fun installRepair() {
            installCalls++
            installing()
            installed = true
        }

        override suspend fun wakes(kind: DownloadWakeKind): List<DownloadWakeRecord> {
            if (!installed) installWasFirst = false
            return records[kind].orEmpty()
        }

        override suspend fun replaceWake(kind: DownloadWakeKind, epochMs: Long, nowEpochMs: Long) {
            if (!installed) installWasFirst = false
            val request = Wake(kind, epochMs, nowEpochMs)
            requests += request
            replacing(request)
            records[kind] = listOf(record("published-${requests.size}", WorkInfo.State.ENQUEUED, epochMs))
            acknowledged += request
        }
    }

    private class FakeScheduling(var current: DownloadWakePlan) : DownloadScheduling {
        override val schedulingChanges = MutableStateFlow(0L)
        var reading: suspend () -> Unit = {}
        var deciding: suspend () -> Unit = {}
        var reads = 0
        var confirmations = 0
        var retirementCallbacks = 0
        var confirmedOnMain = false
        var rejectConfirmation = false
        private var issued: DownloadIdleTicket? = null

        fun replace(plan: DownloadWakePlan) { current = plan; schedulingChanges.value++ }
        override suspend fun schedulingPlan(): DownloadWakePlan { reads++; reading(); return current }
        override suspend fun idleDecision(): DownloadIdleDecision {
            deciding()
            if (current.hasDueWork) return DownloadIdleDecision.OwnedWork
            val ticket = DownloadIdleTicket(current.key).also { issued = it }
            return DownloadIdleDecision.Deferred(ticket, current)
        }

        override suspend fun confirmIdle(ticket: DownloadIdleTicket, retire: () -> Boolean): Boolean {
            confirmations++
            if (rejectConfirmation || issued !== ticket || ticket.key != current.key || current.hasDueWork) return false
            issued = null
            retirementCallbacks++
            confirmedOnMain = Looper.myLooper() == Looper.getMainLooper()
            return retire()
        }
    }

    private companion object {
        const val NOW = 10_000L
        const val DAY = 86_400_000L
        fun plan(local: Long? = null, network: Long? = null, due: Boolean = false, revision: Long = 1) =
            DownloadWakePlan(DownloadSchedulingKey("authored-native-port", revision), local, network, due)
        fun record(worker: String, state: WorkInfo.State, deadline: Long, next: Long = deadline, attempts: Int = 0) =
            DownloadWakeRecord(worker, state, deadline, next, attempts)
    }
}
