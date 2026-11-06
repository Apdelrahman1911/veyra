package dev.veyra.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Startup-policy tests, not evidence of a real corrupt-Room UI recovery journey. */
@RunWith(AndroidJUnit4::class)
class AndroidInitializationTest {
    @Test fun failedAttemptDoesNotPoisonExplicitRecovery() = runBlocking {
        val state = MutableStateFlow<AppInitialization>(AppInitialization.Loading)
        val initial = async(start = CoroutineStart.UNDISPATCHED) {
            try { awaitApplicationInitialization(state); null }
            catch (error: VeyraException) { error.failure }
        }
        assertFalse(initial.isCompleted)
        val failure = AppFailure(FailureCode.RECOVERY_REQUIRED, "test_backup_available")
        state.value = AppInitialization.Failed(failure)
        assertEquals(failure, withTimeout(2_000) { initial.await() })
        state.value = AppInitialization.Loading // A new, user-confirmed attempt.
        val recovered = async(start = CoroutineStart.UNDISPATCHED) { awaitApplicationInitialization(state) }
        assertFalse(recovered.isCompleted)
        state.value = AppInitialization.Ready
        withTimeout(2_000) { recovered.await() }
    }

    @Test fun osPlaybackCommandsFailFastUntilActuallyReady() {
        val failure = AppFailure(FailureCode.RECOVERY_REQUIRED)
        assertEquals(FailureCode.INVALID_STATE, applicationInitializationFailure(null)?.code)
        assertEquals(FailureCode.INVALID_STATE, applicationInitializationFailure(AppInitialization.Loading)?.code)
        assertEquals(failure, applicationInitializationFailure(AppInitialization.Failed(failure)))
        assertNull(applicationInitializationFailure(AppInitialization.Ready))
    }

    @Test fun cancelledWorkerDoesNotCancelAnotherInitializationWaiter() = runBlocking {
        val state = MutableStateFlow<AppInitialization>(AppInitialization.Loading)
        val old = async(start = CoroutineStart.UNDISPATCHED) { awaitApplicationInitialization(state) }
        old.cancelAndJoin()
        val successor = async(start = CoroutineStart.UNDISPATCHED) { awaitApplicationInitialization(state) }
        state.value = AppInitialization.Ready
        withTimeout(2_000) { successor.await() }
    }
}
