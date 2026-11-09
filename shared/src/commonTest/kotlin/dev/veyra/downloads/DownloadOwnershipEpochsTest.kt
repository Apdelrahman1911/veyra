package dev.veyra.downloads

import dev.veyra.acquisition.AcquisitionDownloadDurableSettlement
import dev.veyra.model.AppFailure
import dev.veyra.model.ExecutableSubjectId
import dev.veyra.model.ExecutionBindingId
import dev.veyra.model.FailureCode
import dev.veyra.model.PhysicalDownloadProducer
import dev.veyra.model.SourceRecordingId
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Authored physical-barrier tests; no fake lease expiry/native count is treated as a close ACK. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadOwnershipEpochsTest {
    @Test fun independentDrainWaitsForTheActualPreAcquisitionAdmissionToReturn() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val opened = CompletableDeferred<DownloadOwnershipEpochs.Epoch>()
        val release = CompletableDeferred<Unit>()
        val admission = launch {
            val epoch = begin(ledger)
            opened.complete(epoch)
            release.await()
            ledger.acquiredNothing(epoch)
            ledger.finishAdmission(epoch)
        }
        val epoch = opened.await()
        val drain = async { ledger.drain(epoch) }
        runCurrent()
        assertFalse(drain.isCompleted)
        assertTrue(ledger.isRevoked(epoch))
        assertFalse(ledger.canAdmit(JOB))
        release.complete(Unit)
        admission.join()
        assertTrue(drain.await())
    }

    @Test fun aRefusedAdmissionRequiresAnExplicitNoAcquisitionReceipt() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val epoch = begin(ledger)
        assertFailsWith<IllegalStateException> { ledger.finishAdmission(epoch) }
        ledger.acquiredNothing(epoch)
        ledger.finishAdmission(epoch)
        assertTrue(ledger.drain(epoch))
    }

    @Test fun repeatingAdmissionFinishNeitherWaivesAcquisitionNorConsumesARetainedFinalizer() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val epoch = begin(ledger)
        repeat(2) { assertFailsWith<IllegalStateException> { ledger.finishAdmission(epoch) } }
        assertFalse(ledger.allLocalOwnersConsumed())
        val finalizer = Any()
        ledger.retain(epoch, "retained", finalizer, completesAcquisition = true)
        repeat(2) { ledger.finishAdmission(epoch) }
        assertFalse(ledger.allLocalOwnersConsumed())
        assertFalse(ledger.drain(epoch))
        ledger.consumedFinalizer(epoch, "retained", finalizer)
        ledger.finishAdmission(epoch)
        assertTrue(ledger.allLocalOwnersConsumed())
        assertTrue(ledger.drain(epoch))
    }

    @Test fun physicalFinalizerConsumptionStillDoesNotReplaceJoiningTheActualTask() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val epoch = begin(ledger)
        val finalizer = Any()
        ledger.retain(epoch, "finalizer", finalizer, completesAcquisition = true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val task = launch {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        ledger.registerJob(epoch, task)
        ledger.finishAdmission(epoch)
        ledger.consumedFinalizer(epoch, "finalizer", finalizer)
        entered.await()
        val drain = async { ledger.drain(epoch) }
        runCurrent()
        assertFalse(drain.isCompleted)
        release.complete(Unit)
        assertTrue(drain.await())
        assertTrue(task.isCompleted)
    }

    @Test fun aCompletedCoroutineWithAThrowingNativeCloseRemainsRetained() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val epoch = begin(ledger)
        val finalizer = Any()
        val task = launch { ledger.retain(epoch, "native-close", finalizer, completesAcquisition = true) }
        ledger.registerJob(epoch, task)
        ledger.finishAdmission(epoch)
        task.join()
        assertFalse(ledger.drain(epoch))
        assertFalse(ledger.canAdmit(JOB))
        // Only an explicit successful retry may record this; drain itself invokes no close.
        ledger.consumedFinalizer(epoch, "native-close", finalizer)
        assertTrue(ledger.drain(epoch))
    }

    @Test fun anEmptyFinalizerMapCannotClearTheOriginalAcquisitionObligation() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val epoch = begin(ledger)
        val task = launch { /* Fault injection: the executor omitted ownership handoff. */ }
        ledger.registerJob(epoch, task)
        ledger.finishAdmission(epoch)
        task.join()
        assertFalse(ledger.drain(epoch))
        assertFalse(ledger.canAdmit(JOB))
    }

    @Test fun adoptingATemporaryOwnerTransfersResponsibilityWithoutAConsumptionGap() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val epoch = begin(ledger)
        val child = Any()
        val parent = Any()
        ledger.retain(epoch, "temporary", child)
        ledger.retain(epoch, "replacement", parent, mapOf("temporary" to child), completesAcquisition = true)
        ledger.finishAdmission(epoch)
        assertFalse(ledger.drain(epoch))
        assertFailsWith<IllegalStateException> { ledger.consumedFinalizer(epoch, "temporary", child) }
        ledger.consumedFinalizer(epoch, "replacement", parent)
        assertTrue(ledger.drain(epoch))
    }

    @Test fun aReplacementCannotAdoptAForeignOrAlreadyConsumedChild() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val epoch = begin(ledger)
        val child = Any()
        ledger.retain(epoch, "temporary", child)
        assertFailsWith<IllegalStateException> {
            ledger.retain(epoch, "wrong", Any(), mapOf("temporary" to Any()))
        }
        ledger.consumedFinalizer(epoch, "temporary", child)
        assertFailsWith<IllegalStateException> {
            ledger.retain(epoch, "wrong", Any(), mapOf("temporary" to child))
        }
        val finalizer = Any()
        ledger.retain(epoch, "replacement", finalizer, completesAcquisition = true)
        ledger.finishAdmission(epoch)
        assertFalse(ledger.drain(epoch))
        ledger.consumedFinalizer(epoch, "replacement", finalizer)
        assertTrue(ledger.drain(epoch))
    }

    @Test fun finalizerReceiptRequiresTheExactOwnerObjectAndCannotBeConsumedTwice() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val epoch = begin(ledger)
        val finalizer = Any()
        ledger.retain(epoch, "finalizer", finalizer, completesAcquisition = true)
        ledger.finishAdmission(epoch)
        assertFailsWith<IllegalStateException> { ledger.consumedFinalizer(epoch, "finalizer", Any()) }
        ledger.consumedFinalizer(epoch, "finalizer", finalizer)
        assertFailsWith<IllegalStateException> { ledger.consumedFinalizer(epoch, "finalizer", finalizer) }
        assertTrue(ledger.drain(epoch))
    }

    @Test fun rejectedSecondAcquisitionCompletionDoesNotPartiallyAdoptItsChildren() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val epoch = begin(ledger)
        val original = Any()
        val child = Any()
        val replacement = Any()
        ledger.retain(epoch, "original", original, completesAcquisition = true)
        ledger.retain(epoch, "child", child)
        assertFailsWith<IllegalStateException> {
            ledger.retain(epoch, "replacement", replacement, mapOf("child" to child), completesAcquisition = true)
        }
        assertFailsWith<IllegalStateException> { ledger.consumedFinalizer(epoch, "replacement", replacement) }
        ledger.consumedFinalizer(epoch, "original", original)
        ledger.finishAdmission(epoch)
        assertFalse(ledger.drain(epoch))
        ledger.consumedFinalizer(epoch, "child", child)
        assertTrue(ledger.drain(epoch))
    }

    @Test fun ownershipEpochsCannotBePassedToAnotherExecutorInstance() = runTest {
        val first = DownloadOwnershipEpochs("first")
        val second = DownloadOwnershipEpochs("second")
        val epoch = begin(first)
        assertFailsWith<IllegalStateException> { second.acquiredNothing(epoch) }
        assertFailsWith<IllegalStateException> { second.drain(epoch) }
        first.acquiredNothing(epoch)
        first.finishAdmission(epoch)
        assertTrue(first.drain(epoch))
    }

    @Test fun aNewAdmissionCannotHideThePreviousEpochsRetainedFileOwner() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val epoch = begin(ledger)
        val retained = Any()
        ledger.retain(epoch, "retained", retained, completesAcquisition = true)
        ledger.finishAdmission(epoch)
        assertFailsWith<IllegalStateException> { begin(ledger, generation = 2, attempt = "next") }
        ledger.consumedFinalizer(epoch, "retained", retained)
        val successor = begin(ledger, generation = 2, attempt = "next")
        assertNotEquals(epoch.generation, successor.generation)
        assertSame(successor, ledger.current(JOB))
        assertTrue(ledger.drain(epoch))
        assertFalse(ledger.isRevoked(successor))
        ledger.acquiredNothing(successor)
        ledger.finishAdmission(successor)
        assertTrue(ledger.drain(successor))
    }

    @Test fun durableCASFailureIsIndependentFromActualPhysicalConsumption() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val epoch = begin(ledger)
        val durable = AcquisitionDownloadDurableSettlement.Unconfirmed(AppFailure(FailureCode.STORAGE_IO))
        ledger.recordDurable(epoch, durable)
        ledger.acquiredNothing(epoch)
        ledger.finishAdmission(epoch)
        assertTrue(ledger.drain(epoch))
        assertEquals(durable, ledger.durableSettlement(epoch))
    }

    @Test fun aPositiveDurableCASDoesNotConsumeAnUnknownNativeClose() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val epoch = begin(ledger)
        ledger.retain(epoch, "retained", Any(), completesAcquisition = true)
        ledger.finishAdmission(epoch)
        val durable = AcquisitionDownloadDurableSettlement.Confirmed(17)
        ledger.recordDurable(epoch, durable)
        assertFalse(ledger.drain(epoch))
        assertEquals(durable, ledger.durableSettlement(epoch))
    }

    @Test fun clearingActiveAttemptOnPublicationKeepsTheOriginalPhysicalAttemptFence() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val epoch = begin(ledger)
        ledger.retain(epoch, "still-closing", Any(), completesAcquisition = true)
        ledger.finishAdmission(epoch)
        val observed = ledger.observe(JOB, PRODUCER, 1, null, null, false)
        assertSame(epoch, observed)
        assertEquals("attempt", observed.generation.attemptId)
        assertEquals(1, observed.generation.attemptNumber)
        assertFalse(ledger.drain(observed))
    }

    @Test fun aDurableSuccessorCannotMakeAnOldRetainedEpochDisappear() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val epoch = begin(ledger)
        ledger.retain(epoch, "still-closing", Any(), completesAcquisition = true)
        ledger.finishAdmission(epoch)
        assertFailsWith<IllegalStateException> { ledger.observe(JOB, PRODUCER, 2, "next", 2, false) }
        assertSame(epoch, ledger.current(JOB))
    }

    @Test fun unknownExternalOwnershipNeverGetsAPhysicalACKFromAnIdleObservation() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val observed = ledger.observe(JOB, PRODUCER, 3, "foreign", 3, true)
        assertFalse(ledger.drain(observed))
        // This says only that no local owner remains. Actual future claims still need Room
        // ownershipGeneration CAS and OS locks, not this boolean or the external lease expiry.
        assertTrue(ledger.canAdmit(JOB))
        val local = begin(ledger, generation = 4, attempt = "local")
        assertNotEquals(observed.generation, local.generation)
        ledger.acquiredNothing(local)
        ledger.finishAdmission(local)
        assertTrue(ledger.drain(local))
        assertFalse(ledger.drain(observed))
    }

    @Test fun aNewUnknownExternalObservationCannotReuseAnEarlierIdleACK() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val idle = ledger.observe(JOB, PRODUCER, 1, null, null, false)
        assertTrue(ledger.drain(idle))
        val unknown = ledger.observe(JOB, PRODUCER, 1, null, null, true)
        assertNotSame(idle, unknown)
        assertFalse(ledger.drain(unknown))
        // The historical known epoch is still an honest ACK of ONLY its old local owners.
        assertTrue(ledger.drain(idle))
    }

    @Test fun unknownExternalTransitionCannotHideAnExistingUnconsumedLocalOwner() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val epoch = begin(ledger)
        val retained = Any()
        ledger.retain(epoch, "retained", retained, completesAcquisition = true)
        ledger.finishAdmission(epoch)
        assertFailsWith<IllegalStateException> { ledger.observe(JOB, PRODUCER, 1, null, null, true) }
        assertSame(epoch, ledger.current(JOB))
        assertFalse(ledger.drain(epoch))
    }

    @Test fun aConsumedEpochRetainsItsDurableFailureOnRepeatedIdleObservation() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val epoch = begin(ledger)
        ledger.acquiredNothing(epoch)
        ledger.finishAdmission(epoch)
        val failure = AcquisitionDownloadDurableSettlement.Unconfirmed(AppFailure(FailureCode.STORAGE_IO))
        ledger.recordDurable(epoch, failure)
        val observed = ledger.observe(JOB, PRODUCER, 1, null, null, false)
        assertSame(epoch, observed)
        assertEquals(failure, ledger.durableSettlement(observed))
    }

    @Test fun revocationBeforeJobRegistrationCancelsThatActualJobAndStillAwaitsAdmission() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val epoch = begin(ledger)
        ledger.revokeOnly(epoch)
        val task = launch(start = CoroutineStart.LAZY) { error("A revoked job cannot start") }
        ledger.registerJob(epoch, task)
        assertTrue(task.isCancelled)
        val rollback = Any()
        ledger.retain(epoch, "rollback", rollback, completesAcquisition = true)
        ledger.finishAdmission(epoch)
        assertFalse(ledger.drain(epoch))
        ledger.consumedFinalizer(epoch, "rollback", rollback)
        assertTrue(ledger.drain(epoch))
    }

    @Test fun inlineAdmittingCallerDoesNotPretendItJoinedItsOwnUnfinishedAdmission() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val epoch = begin(ledger)
        assertFalse(ledger.drain(epoch))
        assertTrue(ledger.isRevoked(epoch))
        ledger.acquiredNothing(epoch)
        ledger.finishAdmission(epoch)
        assertTrue(ledger.drain(epoch))
    }

    @Test fun nonCancellableInstallationPreservesTheRealAdmittingJobForInlineDrainRefusal() = runTest {
        val ledger = DownloadOwnershipEpochs("executor")
        val admittingJob = currentCoroutineContext().job
        val epoch = withContext(NonCancellable) {
            ledger.begin(JOB, PRODUCER, 1, "attempt", 1, admissionOwner = admittingJob)
        }
        assertSame(admittingJob, epoch.admittingJob)
        assertFalse(ledger.drain(epoch), "Returning from NonCancellable did not finish the actual admission")
        assertFalse(ledger.allLocalOwnersConsumed())
        ledger.acquiredNothing(epoch)
        ledger.finishAdmission(epoch)
        assertTrue(ledger.drain(epoch))
        assertTrue(ledger.allLocalOwnersConsumed())
    }

    private suspend fun begin(ledger: DownloadOwnershipEpochs, generation: Long = 1, attempt: String = "attempt") =
        ledger.begin(JOB, PRODUCER, generation, attempt, generation.toInt())

    private companion object {
        const val JOB = "physical-job"
        val PRODUCER = PhysicalDownloadProducer.Typed(ExecutableSubjectId("subject"),
            ExecutionBindingId("producer-r1"), SourceRecordingId("recording-c"))
    }
}
