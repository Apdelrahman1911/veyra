package dev.veyra.desktop

import dev.veyra.app.MediaRuntimePhase
import dev.veyra.app.MediaRuntimeStatus
import dev.veyra.desktop.DesktopServiceResources.Slot
import dev.veyra.desktop.runtime.DesktopProcessContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real services factory/owner path with authored stage values only: no native engine, Room,
 * media runtime, source client, window, helper preparation, download or live request is opened. */
class DesktopPlatformServicesOwnershipTest {
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun runNext() {
            checkNotNull(tasks.poll(10, TimeUnit.SECONDS)) { "Expected fixture continuation was not dispatched" }.run()
        }
        fun runAvailable() { while (true) (tasks.poll() ?: return).run() }
    }

    private class Resource(
        val slot: Slot,
        private val order: ConcurrentLinkedQueue<Slot> = ConcurrentLinkedQueue(),
        private val closing: suspend () -> Unit = {},
    ) {
        val closes = AtomicInteger()
        suspend fun close() { closes.incrementAndGet(); order.add(slot); closing() }
    }

    private suspend fun DesktopServiceResources.attach(resource: Resource): Resource =
        acquire(resource.slot, release = { it.close() }) { resource }

    // Read-only white-box observations protect this narrow allocation-order invariant without
    // adding a production hook or forcing process OOM. They do not simulate catastrophic failure.
    private fun DesktopServiceResources.slotHolder(slot: Slot): Any? {
        val field = DesktopServiceResources::class.java.getDeclaredField("resources").apply { isAccessible = true }
        return (field.get(this) as AtomicReferenceArray<*>).get(slot.ordinal)
    }

    private fun releaseAdapter(holder: Any): Any = checkNotNull(
        holder.javaClass.getDeclaredField("release").apply { isAccessible = true }.get(holder),
    )

    private class Harness(
        register: (DesktopPlatformServices) -> Unit = {},
        runtimeStatus: StateFlow<MediaRuntimeStatus>? = null,
        allocate: suspend DesktopServiceResources.() -> Unit,
    ) {
        private val directory = Files.createTempDirectory(fixtureRoot(), "owner-")
        val context = DesktopProcessContext.acquire(directory)
        private val caller = SupervisorJob()
        private val returns = QueuedDispatcher()
        val acquisition = QueuedDispatcher()
        private val published = AtomicReference<DesktopPlatformServices?>()
        private val ownerResources = AtomicReference<DesktopServiceResources?>()
        val factoryFailure = AtomicReference<Throwable?>()
        val registrations = AtomicInteger()
        val owned: DesktopPlatformServices get() = checkNotNull(published.get())
        val resources: DesktopServiceResources get() = checkNotNull(ownerResources.get())
        val task = CoroutineScope(caller + returns).async {
            try {
                DesktopPlatformServices.createOwned(
                    context = context, silentAudio = true, dispatcher = acquisition,
                    onOwned = { services ->
                        registrations.incrementAndGet()
                        check(published.compareAndSet(null, services)) { "Fixture registered a second services owner" }
                        register(services)
                    },
                    acquire = { publishRuntimeStatus ->
                        ownerResources.set(this)
                        allocate()
                        runtimeStatus?.let(publishRuntimeStatus)
                    },
                )
            } catch (error: Throwable) { factoryFailure.set(error); throw error }
        }
        fun start() { returns.runNext() }
        suspend fun finish(): Result<DesktopPlatformServices> {
            repeat(32) {
                if (task.isCompleted) return runCatching { task.await() }
                returns.runNext()
            }
            error("Fixture factory exceeded its bounded continuation count")
        }
        suspend fun cleanup() = withContext(NonCancellable) {
            try {
                task.cancel()
                acquisition.runAvailable()
                finish()
                published.get()?.let { runCatching { it.closeAcquiredResources() } }
                caller.cancelAndJoin()
            } finally {
                context.close()
                Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }
    }

    private fun sanitized(error: Throwable?, reason: String): Throwable {
        val failure = assertNotNull(error)
        assertEquals(reason, failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertFalse(failure.toString().contains(SENSITIVE_TEXT))
        return failure
    }

    @Test fun exactInertServicesArePublishedBeforeAnyStageAndSuccessfulOwnersCloseOnceInDependencyOrder(): Unit = runBlocking {
        val order = ConcurrentLinkedQueue<Slot>()
        val values = Slot.entries.associateWith { Resource(it, order) }
        val factories = AtomicInteger()
        val harness = Harness {
            for (slot in Slot.entries) { factories.incrementAndGet(); attach(values.getValue(slot)) }
        }
        try {
            harness.start()
            assertEquals(1, harness.registrations.get())
            assertEquals(0, factories.get())
            assertEquals(harness.context.dataDirectory.toString(), harness.owned.dataDirectory)
            sanitized(runCatching { harness.owned.engine }.exceptionOrNull(), "desktop_services_resource_unavailable")
            sanitized(runCatching { harness.owned.store }.exceptionOrNull(), "desktop_services_resource_unavailable")
            sanitized(runCatching { harness.owned.youtube }.exceptionOrNull(), "desktop_services_resource_unavailable")
            assertEquals(MediaRuntimePhase.REQUIRED, harness.owned.mediaRuntimeStatus.value.phase)
            harness.acquisition.runNext()
            assertFalse(harness.task.isCompleted, "The factory return is deliberately queued")
            assertSame(harness.owned, harness.finish().getOrThrow())
            for (slot in Slot.entries) assertSame(values.getValue(slot), harness.resources.resourceOrNull(slot))
            harness.owned.closeAcquiredResources()
            harness.owned.closeAcquiredResources()
            assertEquals(CLOSE_ORDER, order.toList())
            for (slot in Slot.entries) {
                assertEquals(1, values.getValue(slot).closes.get())
                assertNull(harness.resources.resourceOrNull(slot))
            }
        } finally { harness.cleanup() }
    }

    @Test fun failureAtEachFactoryClosesOnlyEarlierAcquiredSlotsAndNeverStartsLaterStages(): Unit = runBlocking {
        for (failing in Slot.entries) {
            val order = ConcurrentLinkedQueue<Slot>()
            val values = Slot.entries.associateWith { Resource(it, order) }
            val attempted = mutableListOf<Slot>()
            val harness = Harness {
                for (slot in Slot.entries) acquire(slot, release = { it: Resource -> it.close() }) {
                    attempted.add(slot)
                    if (slot == failing) throw LinkageError(SENSITIVE_TEXT)
                    values.getValue(slot)
                }
            }
            try {
                harness.start(); harness.acquisition.runNext()
                sanitized(harness.finish().exceptionOrNull(), "desktop_services_initialization_failed")
                assertEquals(Slot.entries.take(failing.ordinal + 1), attempted)
                assertEquals(CLOSE_ORDER.filter { it.ordinal < failing.ordinal }, order.toList())
                harness.owned.closeAcquiredResources()
                for (slot in Slot.entries) {
                    assertEquals(if (slot.ordinal < failing.ordinal) 1 else 0, values.getValue(slot).closes.get())
                    assertNull(harness.resources.resourceOrNull(slot))
                }
            } finally { harness.cleanup() }
        }
    }

    @Test fun holderAndReleaseAdapterAreInstalledBeforeSuspendingFactoryAndReusedAfterReturn(): Unit = runBlocking {
        val finishFactory = CompletableDeferred<Unit>()
        val value = Resource(Slot.ENGINE)
        val reservedHolder = AtomicReference<Any?>()
        val reservedRelease = AtomicReference<Any?>()
        val harness = Harness {
            acquire(Slot.ENGINE, release = { it: Resource -> assertSame(value, it); it.close() }) {
                val holder = assertNotNull(slotHolder(Slot.ENGINE), "The owner slot must exist before the factory starts")
                reservedHolder.set(holder)
                reservedRelease.set(releaseAdapter(holder))
                assertNull(resourceOrNull(Slot.ENGINE), "An admitted holder is not yet an acquired resource")
                finishFactory.await()
                value
            }
        }
        try {
            harness.start(); harness.acquisition.runNext()
            val holder = assertNotNull(reservedHolder.get())
            val release = assertNotNull(reservedRelease.get())
            assertFalse(harness.task.isCompleted)
            assertSame(holder, harness.resources.slotHolder(Slot.ENGINE))
            assertSame(release, releaseAdapter(holder))
            assertNull(harness.resources.resourceOrNull(Slot.ENGINE))
            finishFactory.complete(Unit)
            harness.acquisition.runNext(); harness.finish().getOrThrow()
            assertSame(holder, harness.resources.slotHolder(Slot.ENGINE), "Attachment must not allocate a replacement holder")
            assertSame(release, releaseAdapter(holder), "Attachment must not allocate a replacement release adapter")
            assertSame(value, harness.resources.resourceOrNull(Slot.ENGINE))
            harness.owned.closeAcquiredResources()
            assertEquals(1, value.closes.get())
            assertNull(harness.resources.slotHolder(Slot.ENGINE))
        } finally { finishFactory.complete(Unit); harness.cleanup() }
    }

    @Test fun failedFactoryReservationSkipsReleaseAndCannotAdmitAReplacement(): Unit = runBlocking {
        val failure = LinkageError(SENSITIVE_TEXT)
        val releases = AtomicInteger()
        val replacements = AtomicInteger()
        val reservedHolder = AtomicReference<Any?>()
        val store = Resource(Slot.STORE)
        val harness = Harness {
            val failed = runCatching {
                acquire<Resource>(Slot.ENGINE, release = { releases.incrementAndGet(); it.close() }) {
                    reservedHolder.set(assertNotNull(slotHolder(Slot.ENGINE)))
                    throw failure // No resource was returned; the factory owns any earlier allocations.
                }
            }
            // Coroutine stacktrace recovery may copy a standard Throwable across withContext.
            assertTrue(failed.exceptionOrNull() is LinkageError)
            assertEquals(failure.message, failed.exceptionOrNull()?.message)
            assertSame(reservedHolder.get(), slotHolder(Slot.ENGINE))
            assertNull(resourceOrNull(Slot.ENGINE))
            sanitized(runCatching {
                acquire(Slot.ENGINE, release = { it: Resource -> it.close() }) {
                    replacements.incrementAndGet(); Resource(Slot.ENGINE)
                }
            }.exceptionOrNull(), "desktop_services_duplicate_resource")
            attach(store)
        }
        try {
            harness.start(); harness.acquisition.runNext(); harness.finish().getOrThrow()
            assertSame(assertNotNull(reservedHolder.get()), harness.resources.slotHolder(Slot.ENGINE))
            harness.owned.closeAcquiredResources(); harness.owned.closeAcquiredResources()
            assertEquals(0, releases.get())
            assertEquals(0, replacements.get())
            assertEquals(1, store.closes.get())
            assertNull(harness.resources.slotHolder(Slot.ENGINE))
            assertNull(harness.resources.slotHolder(Slot.STORE))
        } finally { harness.cleanup() }
    }

    @Test fun partialInitializationFailedCleanupIsRetainedSanitizedAndStickyAtHostClose(): Unit = runBlocking {
        val order = ConcurrentLinkedQueue<Slot>()
        val engine = Resource(Slot.ENGINE, order) { throw IllegalStateException(SENSITIVE_TEXT) }
        val store = Resource(Slot.STORE, order)
        val harness = Harness {
            attach(engine); attach(store)
            acquire<Resource>(Slot.MEDIA_RUNTIME, release = { it.close() }) { throw IllegalArgumentException(SENSITIVE_TEXT) }
        }
        try {
            harness.start(); harness.acquisition.runNext()
            val failure = sanitized(harness.finish().exceptionOrNull(), "desktop_services_cleanup_failed")
            val repeated = sanitized(runCatching { harness.owned.closeAcquiredResources() }.exceptionOrNull(), "desktop_services_cleanup_failed")
            assertSame(failure, repeated)
            assertEquals(listOf(Slot.ENGINE, Slot.STORE), order.toList())
            assertSame(engine, harness.resources.resourceOrNull(Slot.ENGINE))
            assertNull(harness.resources.resourceOrNull(Slot.STORE))
            assertEquals(1, engine.closes.get()); assertEquals(1, store.closes.get())
        } finally { harness.cleanup() }
    }

    @Test fun cancelledDispatcherReturnKeepsThePublishedOwnerAndFullyDrainsAcquiredResources(): Unit = runBlocking {
        val value = Resource(Slot.ENGINE)
        val harness = Harness { attach(value) }
        try {
            harness.start(); harness.acquisition.runNext()
            assertFalse(harness.task.isCompleted)
            val owner = harness.owned
            harness.task.cancel(CancellationException(SENSITIVE_TEXT))
            assertTrue(harness.finish().exceptionOrNull() is CancellationException)
            // A cancelled Job keeps its caller-supplied reason; inspect what the factory itself
            // threw before Job completion, not that unrelated caller-owned cancellation object.
            sanitized(harness.factoryFailure.get(), "desktop_services_initialization_cancelled")
            assertSame(owner, harness.owned)
            assertEquals(1, value.closes.get())
            assertNull(harness.resources.resourceOrNull(Slot.ENGINE))
            owner.closeAcquiredResources()
            assertEquals(1, value.closes.get())
        } finally { harness.cleanup() }
    }

    @Test fun failedCleanupAfterCancelledReturnCannotBeHiddenByAStartupJobJoin(): Unit = runBlocking {
        val value = Resource(Slot.ENGINE) { throw IllegalStateException(SENSITIVE_TEXT) }
        val harness = Harness { attach(value) }
        try {
            harness.start(); harness.acquisition.runNext()
            harness.task.cancel()
            val failure = sanitized(harness.finish().exceptionOrNull(), "desktop_services_cleanup_failed")
            harness.task.join() // join alone is deliberately NOT treated as a successful cleanup.
            val firstHostClose = sanitized(runCatching { harness.owned.closeAcquiredResources() }.exceptionOrNull(), "desktop_services_cleanup_failed")
            val secondHostClose = sanitized(runCatching { harness.owned.closeAcquiredResources() }.exceptionOrNull(), "desktop_services_cleanup_failed")
            assertSame(failure, firstHostClose); assertSame(firstHostClose, secondHostClose)
            assertSame(value, harness.resources.resourceOrNull(Slot.ENGINE))
            assertEquals(1, value.closes.get())
        } finally { harness.cleanup() }
    }

    @Test fun cancellationBeforeAcquisitionNeverInvokesAnyFactory(): Unit = runBlocking {
        val factories = AtomicInteger()
        val harness = Harness { factories.incrementAndGet() }
        try {
            harness.start()
            harness.task.cancel(CancellationException(SENSITIVE_TEXT))
            harness.acquisition.runNext()
            assertTrue(harness.finish().exceptionOrNull() is CancellationException)
            sanitized(harness.factoryFailure.get(), "desktop_services_initialization_cancelled")
            harness.owned.closeAcquiredResources()
            assertEquals(0, factories.get())
            assertEquals(1, harness.registrations.get())
        } finally { harness.cleanup() }
    }

    @Test fun hostCanCloseThePublishedUninitializedOwnerAndRejectAllLaterAllocation(): Unit = runBlocking {
        val factories = AtomicInteger()
        val harness = Harness { factories.incrementAndGet() }
        try {
            harness.start()
            harness.owned.closeAcquiredResources()
            harness.acquisition.runNext()
            sanitized(harness.finish().exceptionOrNull(), "desktop_services_initialization_failed")
            harness.owned.closeAcquiredResources()
            assertEquals(0, factories.get())
        } finally { harness.cleanup() }
    }

    @Test fun throwingOwnerRegistrationIsSanitizedAndNeverAllocates(): Unit = runBlocking {
        val factories = AtomicInteger()
        val harness = Harness(register = { throw IllegalArgumentException(SENSITIVE_TEXT) }) { factories.incrementAndGet() }
        try {
            harness.start()
            sanitized(harness.finish().exceptionOrNull(), "desktop_services_owner_registration_failed")
            harness.owned.closeAcquiredResources()
            assertEquals(1, harness.registrations.get())
            assertEquals(0, factories.get())
        } finally { harness.cleanup() }
    }

    @Test fun registrationCancellationRemainsCancellationWithoutKeepingCallbackText(): Unit = runBlocking {
        val factories = AtomicInteger()
        val harness = Harness(register = {
            throw CancellationException(SENSITIVE_TEXT).apply {
                initCause(IllegalArgumentException("authored private callback cause: $SENSITIVE_TEXT"))
                addSuppressed(IllegalStateException("authored private callback suppressed: $SENSITIVE_TEXT"))
            }
        }) { factories.incrementAndGet() }
        try {
            harness.start()
            val failure = sanitized(harness.finish().exceptionOrNull(), "desktop_services_initialization_cancelled")
            assertTrue(failure is CancellationException)
            harness.owned.closeAcquiredResources()
            assertEquals(0, factories.get())
        } finally { harness.cleanup() }
    }

    @Test fun closeWaitsForAdmittedSuspendingFactoryAndCancellationCannotDropItsReturnedOwner(): Unit = runBlocking {
        val finishFactory = CompletableDeferred<Unit>()
        val value = Resource(Slot.ENGINE)
        val laterFactories = AtomicInteger()
        val harness = Harness {
            acquire(Slot.ENGINE, release = { it: Resource -> it.close() }) { finishFactory.await(); value }
            acquire(Slot.STORE, release = { it: Resource -> it.close() }) { laterFactories.incrementAndGet(); Resource(Slot.STORE) }
        }
        try {
            harness.start(); harness.acquisition.runNext() // Stage is now suspended, not blocking a monitor/UI thread.
            harness.task.cancel()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { harness.owned.closeAcquiredResources() }
            assertFalse(closing.isCompleted)
            assertFalse(harness.task.isCompleted)
            assertEquals(0, value.closes.get())
            finishFactory.complete(Unit)
            harness.acquisition.runNext()
            withTimeout(10_000) { closing.await() }
            assertTrue(harness.finish().exceptionOrNull() is CancellationException)
            assertEquals(1, value.closes.get())
            assertEquals(0, laterFactories.get())
            assertNull(harness.resources.resourceOrNull(Slot.ENGINE))
        } finally { finishFactory.complete(Unit); harness.cleanup() }
    }

    @Test fun everyFailedSlotStaysRetainedAndAllCloseAttemptsFinishWithoutRawCausesOrRetries(): Unit = runBlocking {
        val order = ConcurrentLinkedQueue<Slot>()
        val values = Slot.entries.associateWith { slot ->
            Resource(slot, order) {
                when (slot) {
                    Slot.ENGINE -> throw LinkageError(SENSITIVE_TEXT)
                    Slot.STORE -> throw CancellationException(SENSITIVE_TEXT)
                    else -> throw IllegalStateException(SENSITIVE_TEXT, IllegalArgumentException(SENSITIVE_TEXT)).apply {
                        addSuppressed(IllegalStateException(SENSITIVE_TEXT))
                    }
                }
            }
        }
        val harness = Harness { for (slot in Slot.entries) attach(values.getValue(slot)) }
        try {
            harness.start(); harness.acquisition.runNext(); harness.finish().getOrThrow()
            val first = sanitized(runCatching { harness.owned.closeAcquiredResources() }.exceptionOrNull(), "desktop_services_cleanup_failed")
            val repeated = sanitized(runCatching { harness.owned.closeAcquiredResources() }.exceptionOrNull(), "desktop_services_cleanup_failed")
            assertSame(first, repeated)
            assertEquals(CLOSE_ORDER, order.toList())
            for (slot in Slot.entries) {
                assertSame(values.getValue(slot), harness.resources.resourceOrNull(slot))
                assertEquals(1, values.getValue(slot).closes.get())
            }
        } finally { harness.cleanup() }
    }

    @Test fun concurrentAndCancelledClosersAwaitTheSameEntireDrainIncludingOwnersAfterFailure(): Unit = runBlocking {
        val order = ConcurrentLinkedQueue<Slot>()
        val runtimeEntered = CompletableDeferred<Unit>(); val finishRuntime = CompletableDeferred<Unit>()
        val youtubeEntered = CompletableDeferred<Unit>(); val finishYoutube = CompletableDeferred<Unit>()
        val values = mapOf(
            Slot.MEDIA_RUNTIME to Resource(Slot.MEDIA_RUNTIME, order) { runtimeEntered.complete(Unit); finishRuntime.await() },
            Slot.ENGINE to Resource(Slot.ENGINE, order) { throw IllegalStateException(SENSITIVE_TEXT) },
            Slot.YOUTUBE to Resource(Slot.YOUTUBE, order) { youtubeEntered.complete(Unit); finishYoutube.await() },
            Slot.STORE to Resource(Slot.STORE, order),
        )
        val harness = Harness { for (slot in Slot.entries) attach(values.getValue(slot)) }
        try {
            harness.start(); harness.acquisition.runNext(); harness.finish().getOrThrow()
            val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { harness.owned.closeAcquiredResources() } }
            withTimeout(10_000) { runtimeEntered.await() }
            val secondOutcome = AtomicReference<Result<Unit>?>()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { harness.owned.closeAcquiredResources() }.also(secondOutcome::set)
            }
            second.cancel(CancellationException(SENSITIVE_TEXT))
            assertFalse(first.isCompleted); assertFalse(second.isCompleted)
            assertEquals(listOf(Slot.MEDIA_RUNTIME), order.toList())
            finishRuntime.complete(Unit)
            withTimeout(10_000) { youtubeEntered.await() }
            assertFalse(first.isCompleted); assertFalse(second.isCompleted)
            assertEquals(listOf(Slot.MEDIA_RUNTIME, Slot.ENGINE, Slot.YOUTUBE), order.toList())
            finishYoutube.complete(Unit)
            val firstFailure = sanitized(withTimeout(10_000) { first.await() }.exceptionOrNull(), "desktop_services_cleanup_failed")
            withTimeout(10_000) { second.join() }
            val secondFailure = sanitized(assertNotNull(secondOutcome.get()).exceptionOrNull(), "desktop_services_cleanup_failed")
            assertSame(firstFailure, secondFailure)
            assertEquals(CLOSE_ORDER, order.toList())
            for (value in values.values) assertEquals(1, value.closes.get())
        } finally { finishRuntime.complete(Unit); finishYoutube.complete(Unit); harness.cleanup() }
    }

    @Test fun containerRuntimeCloseAndFinalHostDrainShareTheSuccessfulRuntimeFence(): Unit = runBlocking {
        val order = ConcurrentLinkedQueue<Slot>()
        val values = Slot.entries.associateWith { Resource(it, order) }
        val harness = Harness { for (slot in Slot.entries) attach(values.getValue(slot)) }
        try {
            harness.start(); harness.acquisition.runNext(); harness.finish().getOrThrow()
            harness.owned.closeMediaRuntime(); harness.owned.closeMediaRuntime()
            assertEquals(listOf(Slot.MEDIA_RUNTIME), order.toList())
            assertNull(harness.resources.resourceOrNull(Slot.MEDIA_RUNTIME))
            harness.owned.closeAcquiredResources()
            assertEquals(CLOSE_ORDER, order.toList())
            for (value in values.values) assertEquals(1, value.closes.get())
        } finally { harness.cleanup() }
    }

    @Test fun failedContainerRuntimeCloseStaysStickyDuringHostDrainAndDoesNotSkipOtherSlots(): Unit = runBlocking {
        val order = ConcurrentLinkedQueue<Slot>()
        val values = Slot.entries.associateWith { slot -> Resource(slot, order) {
            if (slot == Slot.MEDIA_RUNTIME) throw IllegalStateException(SENSITIVE_TEXT)
        } }
        val harness = Harness { for (slot in Slot.entries) attach(values.getValue(slot)) }
        try {
            harness.start(); harness.acquisition.runNext(); harness.finish().getOrThrow()
            val runtimeFailure = sanitized(runCatching { harness.owned.closeMediaRuntime() }.exceptionOrNull(), "desktop_services_cleanup_failed")
            sanitized(runCatching { harness.owned.closeAcquiredResources() }.exceptionOrNull(), "desktop_services_cleanup_failed")
            val repeatedRuntime = sanitized(runCatching { harness.owned.closeMediaRuntime() }.exceptionOrNull(), "desktop_services_cleanup_failed")
            assertSame(runtimeFailure, repeatedRuntime)
            assertEquals(CLOSE_ORDER, order.toList())
            assertSame(values.getValue(Slot.MEDIA_RUNTIME), harness.resources.resourceOrNull(Slot.MEDIA_RUNTIME))
            for (value in values.values) assertEquals(1, value.closes.get())
        } finally { harness.cleanup() }
    }

    @Test fun runtimeStatusProjectionSurvivesRuntimeReleaseAndShutdownRecompositionReads(): Unit = runBlocking {
        val status = MutableStateFlow(MediaRuntimeStatus(MediaRuntimePhase.READY))
        val runtime = Resource(Slot.MEDIA_RUNTIME) { status.value = MediaRuntimeStatus(MediaRuntimePhase.REQUIRED) }
        val harness = Harness(runtimeStatus = status) { attach(runtime) }
        try {
            harness.start()
            assertEquals(MediaRuntimePhase.REQUIRED, harness.owned.mediaRuntimeStatus.value.phase)
            harness.acquisition.runNext(); harness.finish().getOrThrow()
            val observedByUi = harness.owned.mediaRuntimeStatus
            assertSame(status, observedByUi)
            harness.owned.closeMediaRuntime()
            assertNull(harness.resources.resourceOrNull(Slot.MEDIA_RUNTIME))
            assertSame(observedByUi, harness.owned.mediaRuntimeStatus)
            assertEquals(MediaRuntimePhase.REQUIRED, harness.owned.mediaRuntimeStatus.value.phase)
            harness.owned.closeAcquiredResources()
            assertSame(observedByUi, harness.owned.mediaRuntimeStatus)
            assertEquals(1, runtime.closes.get())
        } finally { harness.cleanup() }
    }

    @Test fun duplicateSlotAdmissionFailsBeforeASecondFactoryCanOverwriteOwnership(): Unit = runBlocking {
        val value = Resource(Slot.ENGINE)
        val secondFactory = AtomicInteger()
        val harness = Harness {
            attach(value)
            acquire(Slot.ENGINE, release = { it: Resource -> it.close() }) { secondFactory.incrementAndGet(); Resource(Slot.ENGINE) }
        }
        try {
            harness.start(); harness.acquisition.runNext()
            sanitized(harness.finish().exceptionOrNull(), "desktop_services_initialization_failed")
            assertEquals(0, secondFactory.get())
            assertEquals(1, value.closes.get())
            harness.owned.closeAcquiredResources()
        } finally { harness.cleanup() }
    }

    private companion object {
        const val SENSITIVE_TEXT = "authored-private-source-token-must-not-escape"
        val CLOSE_ORDER = listOf(Slot.MEDIA_RUNTIME, Slot.ENGINE, Slot.YOUTUBE, Slot.STORE)
        fun fixtureRoot(): Path {
            val project = System.getenv("VEYRA_PROJECT_ROOT")?.let(Path::of)
                ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                    .first { Files.exists(it.resolve("settings.gradle.kts")) }
            return project.resolve(".work/feasibility/services-ownership-tests").also { Files.createDirectories(it) }
        }
    }
}
