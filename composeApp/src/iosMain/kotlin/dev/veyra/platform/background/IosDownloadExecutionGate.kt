@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.platform.background

import dev.veyra.downloads.DownloadExecutionGate
import dev.veyra.downloads.DownloadExecutionLease
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSThread
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * Bounded UIKit allowance, not a transfer implementation. All bytes and renewal remain with kmpYT.
 * Exhaustion persists until foreground; stale expiration handlers cannot end a successor task.
 */
class IosDownloadExecutionGate internal constructor(
    private val scope: CoroutineScope,
    private val host: IosBackgroundTaskHost,
) : DownloadExecutionGate {
    constructor(scope: CoroutineScope) : this(scope, UIKitBackgroundTaskHost)

    private val leases = linkedMapOf<Long, Lease>()
    private var nextId = 0L
    private var task: Allowance? = null
    private var background = false
    private var exhausted = false
    private var closed = false
    private var watchdog: Job? = null
    private val ending = mutableSetOf<Allowance>()
    private val expirations = mutableSetOf<Job>()

    private class Allowance {
        var identifier: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
        var endRequested = false
        var ended = false
    }
    private class Lease(val id: Long, val onExpiration: suspend () -> Unit) {
        var released = false
        var expired = false
    }

    override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
        var created: Lease? = null
        try {
            return withContext(Dispatchers.Main.immediate) {
                if (closed || exhausted || expirations.isNotEmpty()) throw restricted()
                if (task == null && !beginAllowance()) throw restricted()
                val lease = Lease(++nextId, onExpiration)
                created = lease // Retain across prompt cancellation on the withContext return edge.
                leases[lease.id] = lease
                startWatchdog()
                DownloadExecutionLease { release(lease) }
            }
        } catch (error: Throwable) {
            created?.let { release(it) }
            throw error
        }
    }

    private fun beginAllowance(): Boolean {
        val current = Allowance()
        task = current
        current.identifier = try {
            host.begin { scope.launch(Dispatchers.Main.immediate) { expire(current, systemDeadline = true) } }
        } catch (_: Exception) {
            // UIKit normally returns the invalid identifier. An unavailable host is the same
            // bounded-execution denial, never a partially accepted lease or a renewed budget.
            if (task === current) task = null
            current.ended = true
            ending.remove(current)
            exhausted = true
            return false
        }
        if (current.identifier == UIBackgroundTaskInvalid) {
            if (task === current) task = null
            current.ended = true
            ending.remove(current)
            exhausted = true
            return false
        }
        // Handles even a host invoking expiration inline before returning its identifier.
        if (current.endRequested || task !== current) {
            endOnce(current)
            return false
        }
        return true
    }

    private suspend fun release(lease: Lease): Unit = withContext(NonCancellable + Dispatchers.Main.immediate) {
        if (lease.released) return@withContext
        lease.released = true
        leases.remove(lease.id)
        if (leases.isEmpty()) {
            watchdog?.cancel()
            watchdog = null
            val previous = task
            task = null
            previous?.let(::endOnce)
        }
    }

    fun didEnterBackground() {
        check(NSThread.isMainThread)
        background = true
        if (!closed && !exhausted && task == null && leases.values.any { !it.expired && !it.released }) {
            if (!beginAllowance()) expireLeases(null, systemDeadline = true)
        }
        startWatchdog()
    }

    fun didBecomeActive() {
        check(NSThread.isMainThread)
        if (closed) return
        background = false
        watchdog?.cancel()
        watchdog = null
        // Invalidate the old callback identity before a foreground resume can acquire a new task.
        // Existing transfers need no UIKit extension while foreground. Reacquire on background.
        val previous = task
        task = null
        previous?.let(::endOnce)
        if (expirations.isEmpty()) exhausted = false
    }

    /** Finish old global checkpoint/cancellation callbacks BEFORE the host resumes new transfers. */
    suspend fun awaitForegroundReady(): Boolean = withContext(Dispatchers.Main.immediate) {
        while (expirations.isNotEmpty()) {
            expirations.toList().forEach { it.join(); expirations.remove(it) }
        }
        if (closed || background) return@withContext false
        exhausted = false
        true
    }

    private fun startWatchdog() {
        val watched = task ?: return
        if (!background || exhausted || watchdog?.isActive == true) return
        watchdog = scope.launch(Dispatchers.Main.immediate) {
            while (isActive && background && task === watched) {
                val remaining = host.backgroundTimeRemaining
                // An estimate, not a guaranteed transfer window. Checkpoint before final expiry.
                if (remaining.isFinite() && remaining <= 6.0) {
                    expire(watched, systemDeadline = false)
                    return@launch
                }
                delay(500)
            }
        }
    }

    private fun expire(expected: Allowance, systemDeadline: Boolean) {
        check(NSThread.isMainThread)
        if (task !== expected) return
        task = null
        expireLeases(expected, systemDeadline)
    }

    private fun expireLeases(previous: Allowance?, systemDeadline: Boolean) {
        exhausted = true
        watchdog?.cancel()
        watchdog = null
        val expired = leases.values.filter { !it.expired && !it.released }
        expired.forEach { it.expired = true }
        if (expired.isEmpty()) { previous?.let(::endOnce); return }
        previous?.let { ending += it }
        // The final OS callback ends immediately. Proactive expiry allows at most two seconds
        // for checkpointing; filesystem/Room work never keeps UIKit's task indefinitely.
        val guard = scope.launch(Dispatchers.Main.immediate) {
            if (!systemDeadline) delay(2_000)
            previous?.let(::endOnce)
        }
        val cleanup = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            try {
                for (lease in expired) {
                    try { lease.onExpiration() }
                    catch (_: Exception) { /* Root coordinator owns durable recovery failures. */ }
                    finally { release(lease) }
                }
            } finally {
                guard.cancel()
                previous?.let(::endOnce)
            }
        }
        expirations += cleanup
        cleanup.invokeOnCompletion { scope.launch(Dispatchers.Main.immediate) { expirations.remove(cleanup) } }
        cleanup.start()
    }

    private fun endOnce(allowance: Allowance) {
        allowance.endRequested = true
        if (allowance.ended || allowance.identifier == UIBackgroundTaskInvalid) return
        allowance.ended = true
        ending.remove(allowance)
        host.end(allowance.identifier)
    }

    suspend fun close(): Unit = withContext(NonCancellable + Dispatchers.Main.immediate) {
        if (!closed) {
            closed = true
            val previous = task
            task = null
            expireLeases(previous, systemDeadline = true)
            ending.toList().forEach(::endOnce)
        }
        while (expirations.isNotEmpty()) expirations.toList().forEach { it.join(); expirations.remove(it) }
    }

    private fun restricted() = VeyraException(AppFailure(FailureCode.BACKGROUND_RESTRICTED, "ios_execution_allowance"))
}

/** Injectable only for lifecycle/expiration contract tests; production always uses real UIKit. */
internal interface IosBackgroundTaskHost {
    val backgroundTimeRemaining: Double
    fun begin(expiration: () -> Unit): UIBackgroundTaskIdentifier
    fun end(identifier: UIBackgroundTaskIdentifier)
}

private object UIKitBackgroundTaskHost : IosBackgroundTaskHost {
    private val application get() = UIApplication.sharedApplication
    override val backgroundTimeRemaining get() = application.backgroundTimeRemaining
    override fun begin(expiration: () -> Unit): UIBackgroundTaskIdentifier =
        application.beginBackgroundTaskWithName("Veyra central media transfer", expiration)
    override fun end(identifier: UIBackgroundTaskIdentifier) = application.endBackgroundTask(identifier)
}
