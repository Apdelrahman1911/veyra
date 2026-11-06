package dev.veyra.desktop

import dev.veyra.desktop.runtime.DesktopStartupJobs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Host admission/lifecycle tests only; no native window, audio, or process is started. */
class DesktopStartupJobsTest {
    @Test fun retirementKeepsBothAdmittedJobsInDependencyOrderAndRejectsLateEffects() {
        val gate = DesktopStartupJobs()
        val platform = Job()
        val session = Job()
        val late = Job()
        try {
            assertTrue(gate.admit(DesktopStartupJobs.Kind.MEDIA_SESSION, session))
            assertTrue(gate.admit(DesktopStartupJobs.Kind.PLATFORM, platform))
            assertEquals(listOf(platform, session), gate.retire())
            assertFalse(gate.admit(DesktopStartupJobs.Kind.MEDIA_SESSION, late))
            assertEquals(listOf(platform, session), gate.retire())
        } finally { platform.cancel(); session.cancel(); late.cancel() }
    }

    @Test fun closingBeforeAnyEffectPreventsAllOwners() {
        val gate = DesktopStartupJobs()
        val job = Job()
        try {
            assertTrue(gate.retire().isEmpty())
            for (kind in DesktopStartupJobs.Kind.entries) assertFalse(gate.admit(kind, job))
            assertTrue(gate.retire().isEmpty())
        } finally { job.cancel() }
    }

    @Test fun duplicateOrAlreadyCancelledEffectsCannotReplaceAnAdmittedOwner() {
        val gate = DesktopStartupJobs()
        val original = Job()
        val duplicate = Job()
        val cancelled = Job().apply { cancel() }
        try {
            assertFalse(gate.admit(DesktopStartupJobs.Kind.PLATFORM, cancelled))
            assertTrue(gate.admit(DesktopStartupJobs.Kind.PLATFORM, original))
            assertFalse(gate.admit(DesktopStartupJobs.Kind.PLATFORM, duplicate))
            assertSame(original, gate.retire().single())
        } finally { original.cancel(); duplicate.cancel() }
    }

    @Test fun concurrentAdmissionCannotFallOutsideTheRetiredSnapshot() {
        repeat(50) {
            val gate = DesktopStartupJobs()
            val job = Job()
            val start = CountDownLatch(1)
            val admitted = AtomicBoolean()
            val worker = thread(start = true, name = "Veyra-authored-admission-race") {
                check(start.await(10, TimeUnit.SECONDS))
                admitted.set(gate.admit(DesktopStartupJobs.Kind.MEDIA_SESSION, job))
            }
            try {
                start.countDown()
                val captured = gate.retire()
                worker.join(10_000)
                assertFalse(worker.isAlive)
                assertEquals(admitted.get(), job in captured)
                assertFalse(gate.admit(DesktopStartupJobs.Kind.MEDIA_SESSION, job))
            } finally { start.countDown(); worker.join(10_000); job.cancel() }
        }
    }

    @Test fun admittedInitializationMustFullyFinishBeforeItsHostDrainReturns(): Unit = runBlocking {
        val gate = DesktopStartupJobs()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val proceed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val completed = AtomicBoolean()
        val initializer = launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                entered.complete(Unit)
                proceed.await()
                completed.set(true)
            }
        }
        try {
            entered.await()
            assertTrue(gate.admit(DesktopStartupJobs.Kind.PLATFORM, initializer))
            val closing = async(start = CoroutineStart.UNDISPATCHED) {
                gate.retire().forEach { it.cancelAndJoin() }
            }
            assertFalse(closing.isCompleted)
            assertFalse(completed.get())
            proceed.complete(Unit)
            closing.await()
            assertTrue(completed.get())
            assertTrue(initializer.isCompleted)
        } finally { proceed.complete(Unit); initializer.cancelAndJoin() }
    }
}
