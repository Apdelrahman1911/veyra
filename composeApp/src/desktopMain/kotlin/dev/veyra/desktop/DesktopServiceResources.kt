package dev.veyra.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReferenceArray

/** The four app-lifetime desktop service owners, not a second media-resource registry.
 * A returned factory value is attached before cancellation may discard its dispatcher return.
 * Factories remain responsible for allocations that they never return to this owner. */
internal class DesktopServiceResources {
    enum class Slot { ENGINE, STORE, MEDIA_RUNTIME, YOUTUBE }

    private class Owned(val release: suspend (Any) -> Unit) {
        @Volatile var resource: Any? = null // Lock-free readers may observe an admitted empty holder.
        var attempted = false // Protected by operations, including suspended release.
        var failure: DesktopServicesFailure? = null
    }

    private val operations = Mutex()
    private val started = AtomicBoolean()
    private val retired = AtomicBoolean()
    private val resources = AtomicReferenceArray<Owned?>(Slot.entries.size)
    private val acquired = BooleanArray(Slot.entries.size) // Protected by operations.
    private var drained = false
    private var drainFailure: DesktopServicesFailure? = null

    /** Called only after constructing the exact, resource-free DesktopPlatformServices.
     * Publication happens before a dispatcher switch or any resource factory invocation. */
    suspend fun initialize(
        onOwned: () -> Unit,
        dispatcher: CoroutineDispatcher,
        acquire: suspend DesktopServiceResources.() -> Unit,
    ) {
        if (!started.compareAndSet(false, true)) throw DesktopServicesFailure("desktop_services_already_initialized")
        try { onOwned() }
        catch (failure: Throwable) {
            close()
            if (failure is CancellationException) throw cancelledInitialization()
            throw DesktopServicesFailure("desktop_services_owner_registration_failed")
        }
        try {
            withContext(dispatcher) {
                currentCoroutineContext().ensureActive()
                ensureOpen()
                acquire()
                currentCoroutineContext().ensureActive()
                ensureOpen()
            }
            currentCoroutineContext().ensureActive()
            ensureOpen()
        } catch (failure: Throwable) {
            // This includes cancellation after allocation but before dispatcher return.
            // A failed drain is deliberately NOT hidden by the original cancellation/error:
            // the host's already-published owner must repeat that sanitized failed outcome.
            close()
            if (failure is CancellationException) throw cancelledInitialization()
            throw DesktopServicesFailure("desktop_services_initialization_failed")
        }
    }

    suspend fun <T : Any> acquire(slot: Slot, release: suspend (T) -> Unit, factory: suspend () -> T): T =
        operations.withLock {
            currentCoroutineContext().ensureActive()
            ensureOpen()
            if (acquired[slot.ordinal]) throw DesktopServicesFailure("desktop_services_duplicate_resource")
            acquired[slot.ordinal] = true
            // Allocate and publish both the holder and its erased release adapter BEFORE the
            // factory. The returned owner must need only a field assignment to become retained;
            // allocating either wrapper after return would leave an allocation-failure gap.
            @Suppress("UNCHECKED_CAST")
            val owned = Owned { value -> release(value as T) }
            resources.set(slot.ordinal, owned)
            // The engine factory itself suspends. A JVM monitor must not span it, and caller
            // cancellation must not interrupt the factory-to-owner handoff. Close retires new
            // admissions immediately and suspends on this mutex until the admitted stage ends.
            withContext(NonCancellable) {
                val value = factory()
                owned.resource = value
                value
            }
        }

    fun resourceOrNull(slot: Slot): Any? = resources.get(slot.ordinal)?.resource

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> requireResource(slot: Slot): T =
        (resourceOrNull(slot) ?: throw DesktopServicesFailure("desktop_services_resource_unavailable")) as T

    /** AppContainer closes the runtime before its source/store. Share this slot's close fence
     * with the host's final drain rather than invoking a successful runtime close twice. */
    suspend fun closeMediaRuntime() {
        retired.set(true) // Before dispatch/suspension: no late factory can be admitted.
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) {
                operations.withLock { closeSlot(Slot.MEDIA_RUNTIME) }
            }
        }
    }

    suspend fun close() {
        retired.set(true)
        // Establish the non-cancellable caller context BEFORE changing dispatcher. Otherwise
        // return to an already-cancelled caller could discard the completed drain's result.
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) {
                operations.withLock {
                    if (!drained) {
                        for (slot in CLOSE_ORDER) {
                            try { closeSlot(slot) }
                            catch (_: Throwable) {
                                if (drainFailure == null) drainFailure = DesktopServicesFailure("desktop_services_cleanup_failed")
                            }
                        }
                        drained = true
                    }
                    drainFailure?.let { throw it }
                }
            }
        }
    }

    private suspend fun closeSlot(slot: Slot) {
        val owned = resources.get(slot.ordinal) ?: return
        val resource = owned.resource
        if (resource == null) {
            // An admitted factory threw without returning a value. There is nothing to release;
            // acquired[] still prevents this slot from being reused or mistaken for unadmitted.
            resources.set(slot.ordinal, null)
            return
        }
        if (owned.attempted) {
            owned.failure?.let { throw it }
            return
        }
        owned.attempted = true
        try {
            owned.release(resource)
            resources.set(slot.ordinal, null)
        } catch (_: Throwable) {
            // Retain the resource AND its release closure after failure, but never arbitrary
            // native/source exception text, causes or suppressed exceptions. No close retry.
            val failure = DesktopServicesFailure("desktop_services_cleanup_failed")
            owned.failure = failure
            throw failure
        }
    }

    private fun ensureOpen() {
        if (retired.get()) throw DesktopServicesFailure("desktop_services_closed_during_initialization")
    }

    private fun cancelledInitialization() = DesktopServicesCancellation("desktop_services_initialization_cancelled")

    private companion object {
        val CLOSE_ORDER = listOf(Slot.MEDIA_RUNTIME, Slot.ENGINE, Slot.YOUTUBE, Slot.STORE)
    }
}

/** A static classification only; the original Throwable is intentionally not retained. */
internal class DesktopServicesFailure(val reason: String) : IllegalStateException(reason)

/** Preserve cancellation with only a static classification, including across await.
 * The reason field prevents coroutine stack-recovery cloning; sealing the null cause
 * also prevents a constructor-based copy from attaching the original exception. */
private class DesktopServicesCancellation(val reason: String) : CancellationException(reason) {
    init { initCause(null) }
}
