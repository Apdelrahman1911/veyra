package dev.veyra.app

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Native command admission only; a real corrupt-Room recovery journey remains a device gate. */
class IosInitializationTest {
    @Test fun osPlaybackCommandsFailFastDuringFailureAndExplicitRecovery() {
        val failure = AppFailure(FailureCode.RECOVERY_REQUIRED)
        assertEquals(FailureCode.INVALID_STATE, applicationInitializationFailure(null)?.code)
        assertEquals(failure, applicationInitializationFailure(AppInitialization.Failed(failure)))
        assertEquals(FailureCode.INVALID_STATE, applicationInitializationFailure(AppInitialization.Loading)?.code)
        assertNull(applicationInitializationFailure(AppInitialization.Ready))
    }
}
