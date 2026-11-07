package dev.veyra.downloads

import dev.veyra.acquisition.AcquisitionDownloadDurableSettlement
import dev.veyra.acquisition.AcquisitionDownloadGeneration
import dev.veyra.model.PhysicalDownloadProducer
import dev.veyra.model.newEntityId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Physical ownership bookkeeping inside the EXISTING download executor, not a scheduler.
 * Every file/native admission first creates a real unfinished admission obligation. A Room
 * lease, coroutine completion, empty cleanup registry or native lease count cannot consume it.
 * The caller must explicitly transfer every acquired owner to a registered finalizer (or
 * certify that nothing was acquired) before completing admission. Temporary owner adoption
 * transfers responsibility atomically; removing a registry entry is deliberately not an API.
 *
 * The coordinator's admission locks surround begin/observe and actual job registration. This
 * smaller mutex never encloses Room, file/native calls or joining a job. A retained close is
 * retried only by the coordinator's explicit close path, never by drain or a scheduled pass.
 */
internal class DownloadOwnershipEpochs(
    val executorGeneration: String = newEntityId("download-executor"),
) {
    private val lock = Mutex()
    private val latest = mutableMapOf<String, Epoch>()

    internal class Epoch internal constructor(
        internal val issuer: DownloadOwnershipEpochs,
        val downloadId: String,
        val producer: PhysicalDownloadProducer,
        val physicalOwnershipGeneration: Long,
        val generation: AcquisitionDownloadGeneration,
        internal val externallyUnacknowledged: Boolean,
        internal val admittingJob: Job?,
    ) {
        internal val admissionFinished = CompletableDeferred<Unit>()
        internal var admissionOpen = true
        internal var acquisitionObligation = true
        internal var revoked = false
        internal var job: Job? = null
        internal val finalizers = mutableMapOf<String, Finalizer>()
        internal var durable: AcquisitionDownloadDurableSettlement? = null
        override fun toString() = "DownloadOwnershipEpoch(identity=redacted,processLocal=true)"
    }

    internal class Finalizer(val identity: Any, var consumed: Boolean = false)

    /** Called BEFORE slot/path/gate/source acquisition, including failed/rolled-back claims. */
    suspend fun begin(
        downloadId: String,
        producer: PhysicalDownloadProducer,
        physicalOwnershipGeneration: Long,
        attemptId: String?,
        attemptNumber: Int?,
        admissionOwner: Job? = null,
    ): Epoch {
        val caller = admissionOwner ?: currentCoroutineContext()[Job]
        return lock.withLock {
            require(downloadId.isNotBlank() && physicalOwnershipGeneration > 0)
            check(latest[downloadId]?.let(::locallyConsumed) != false) { "Previous physical ownership was not consumed" }
            newEpoch(downloadId, producer, physicalOwnershipGeneration, attemptId, attemptNumber, false, caller)
                .also { latest[downloadId] = it }
        }
    }

    /**
     * A detached idle observation is issued under the SAME admission lock, not inferred from
     * status/counts. Unknown foreign ownership stays explicitly unacknowledged. An older local
     * retained admission must not disappear just because a durable generation has advanced.
     */
    suspend fun observe(
        downloadId: String,
        producer: PhysicalDownloadProducer,
        physicalOwnershipGeneration: Long,
        attemptId: String?,
        attemptNumber: Int?,
        externallyUnacknowledged: Boolean,
    ): Epoch = lock.withLock {
        require(downloadId.isNotBlank() && physicalOwnershipGeneration >= 0)
        val previous = latest[downloadId]
        val same = previous != null && previous.producer == producer &&
            previous.physicalOwnershipGeneration == physicalOwnershipGeneration &&
            (attemptId == null || previous.generation.attemptId == attemptId && previous.generation.attemptNumber == attemptNumber) &&
            previous.externallyUnacknowledged == externallyUnacknowledged
        if (same) {
            // Publication can clear activeAttemptId before physical cleanup returns. Keep the
            // original exact admitted attempt and its independent durable settlement receipt.
            previous!!
        } else if (previous != null && !locallyConsumed(previous)) {
            error("Durable identity advanced before the named local owner drained")
        } else {
            newEpoch(downloadId, producer, physicalOwnershipGeneration, attemptId, attemptNumber, externallyUnacknowledged, null).also {
                it.acquisitionObligation = false
                it.admissionOpen = false
                it.admissionFinished.complete(Unit)
                latest[downloadId] = it
            }
        }
    }

    /** Actual coroutine identity; register before completing the admission obligation. */
    suspend fun registerJob(epoch: Epoch, job: Job) {
        val revoked = lock.withLock {
            owned(epoch)
            check(epoch.admissionOpen && epoch.job == null)
            epoch.job = job
            epoch.revoked
        }
        if (revoked) job.cancel()
    }

    /** Register the replacement BEFORE releasing temporary children, with no unowned gap. */
    suspend fun retain(
        epoch: Epoch,
        token: String,
        identity: Any,
        adopted: Map<String, Any> = emptyMap(),
        completesAcquisition: Boolean = false,
    ) = lock.withLock {
        owned(epoch)
        check(token.isNotBlank() && token !in epoch.finalizers)
        // Validate the whole handoff before changing either side of the responsibility graph.
        // A rejected second acquisition completion must not silently adopt/remove its children.
        if (completesAcquisition) check(epoch.acquisitionObligation)
        for ((childToken, child) in adopted) {
            check(epoch.finalizers[childToken]?.let { it.identity === child && !it.consumed } == true)
        }
        epoch.finalizers[token] = Finalizer(identity)
        adopted.keys.forEach(epoch.finalizers::remove)
        if (completesAcquisition) {
            epoch.acquisitionObligation = false
        }
    }

    /** Positive return from ALL this finalizer's physical stages, not mere registry removal. */
    suspend fun consumedFinalizer(epoch: Epoch, token: String, identity: Any) = lock.withLock {
        owned(epoch)
        val finalizer = epoch.finalizers[token]
        check(finalizer != null && finalizer.identity === identity && !finalizer.consumed)
        finalizer.consumed = true
    }

    /** Explicit no-resource outcome for admission refusals. Never use after a throwing close. */
    suspend fun acquiredNothing(epoch: Epoch) = lock.withLock {
        owned(epoch)
        check(epoch.acquisitionObligation && epoch.job == null && epoch.finalizers.isEmpty())
        epoch.acquisitionObligation = false
    }

    /** A real admission returned/handed off. A never-completed dummy Job is not used as proof. */
    suspend fun finishAdmission(epoch: Epoch) = lock.withLock {
        owned(epoch)
        // The installation path and its exceptional finalizer may both finish the SAME real
        // admission. Repeating an already-positive receipt grants no additional authority.
        if (!epoch.admissionOpen) return@withLock
        // A registered task owns the original acquisition obligation until its finally hands
        // off. A refused admission must already have transferred or consumed its resources.
        check(epoch.job != null || !epoch.acquisitionObligation)
        epoch.admissionOpen = false
        epoch.admissionFinished.complete(Unit)
    }

    suspend fun isRevoked(epoch: Epoch): Boolean = lock.withLock { owned(epoch); epoch.revoked }

    suspend fun recordDurable(epoch: Epoch, settlement: AcquisitionDownloadDurableSettlement) = lock.withLock {
        owned(epoch)
        epoch.durable = settlement
    }

    suspend fun durableSettlement(epoch: Epoch): AcquisitionDownloadDurableSettlement? =
        lock.withLock { owned(epoch); epoch.durable }

    suspend fun current(downloadId: String): Epoch? = lock.withLock { latest[downloadId] }

    suspend fun canAdmit(downloadId: String): Boolean = lock.withLock {
        // This is only the local barrier. Unknown external ownership must ALSO pass the
        // canonical lease CAS and real native path/slot locks; expiry alone grants nothing.
        latest[downloadId]?.let(::locallyConsumed) != false
    }

    /** Extra dependency-release guard after the coordinator joined ALL its workers. */
    suspend fun allLocalOwnersConsumed(): Boolean = lock.withLock { latest.values.all(::locallyConsumed) }

    /**
     * Independent acknowledgement request. Await the real admission and actual task outside
     * every lock. An inline allowance callback must use revokeOnly instead: it cannot join
     * itself. Even here self-drain fails closed rather than claiming to have joined its caller.
     * No cleanup is retried, and a joined failed finalizer remains retained.
     */
    suspend fun drain(epoch: Epoch): Boolean {
        val caller = currentCoroutineContext()[Job]
        return withContext(NonCancellable) {
            val initial = lock.withLock {
                owned(epoch)
                epoch.revoked = true
                Triple(epoch.job, epoch.admissionOpen, epoch.admittingJob)
            }
            initial.first?.cancel()
            if (ownsCaller(initial.first, caller) || initial.second && ownsCaller(initial.third, caller)) {
                return@withContext false
            }
            epoch.admissionFinished.await()
            val job = lock.withLock { epoch.job }
            job?.cancel()
            if (ownsCaller(job, caller)) return@withContext false
            listOfNotNull(job).joinAll()
            lock.withLock { consumed(epoch) }
        }
    }

    /** Safe for inline acquire/close expiration callbacks; never supplies a quiescence ACK. */
    suspend fun revokeOnly(epoch: Epoch) {
        val job = lock.withLock {
            owned(epoch)
            epoch.revoked = true
            epoch.job
        }
        job?.cancel()
    }

    private fun consumed(epoch: Epoch): Boolean = !epoch.externallyUnacknowledged && locallyConsumed(epoch)

    private fun locallyConsumed(epoch: Epoch): Boolean = !epoch.admissionOpen &&
        !epoch.acquisitionObligation && epoch.job?.isCompleted != false && epoch.finalizers.values.all { it.consumed }

    private fun ownsCaller(owner: Job?, caller: Job?): Boolean = owner != null && caller != null &&
        (owner === caller || owner.children.any { ownsCaller(it, caller) })

    private fun owned(epoch: Epoch) { check(epoch.issuer === this) { "Foreign download ownership epoch" } }

    private fun newEpoch(
        downloadId: String,
        producer: PhysicalDownloadProducer,
        physicalOwnershipGeneration: Long,
        attemptId: String?,
        attemptNumber: Int?,
        externallyUnacknowledged: Boolean,
        admittingJob: Job?,
    ) = Epoch(this, downloadId, producer, physicalOwnershipGeneration,
        AcquisitionDownloadGeneration(executorGeneration, newEntityId("download-ownership"), attemptId, attemptNumber),
        externallyUnacknowledged, admittingJob)
}
