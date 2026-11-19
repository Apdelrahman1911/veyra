@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.platform.audio

import dev.veyra.model.OfflineAssetKind
import dev.veyra.platform.runIosMainContract
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.LocalMediaValidationInput
import dev.veyra.playback.LocalMediaValidationReaderUnacknowledged
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import kotlin.test.*

class IosValidationDeadlineTest {
    private val input = LocalMediaValidationInput("/fixture.m4a", OfflineAssetKind.FILE, 1, "audio/mp4", null, null, null, 0)

    @Test fun blockedNativeCallReturnsFailureBeforeUnwindAndRetainsTheExactReader() = runIosMainContract {
        val entered = CompletableDeferred<Unit>()
        val blocker = dispatch_semaphore_create(0)
        val worker = async {
            assertFailsWith<LocalMediaValidationReaderUnacknowledged> {
                runIosFileValidation(input, timeoutMs = 300) {
                    entered.complete(Unit)
                    dispatch_semaphore_wait(blocker, DISPATCH_TIME_FOREVER)
                    AssetValidation.Valid(1_000)
                }
            }
        }
        val failure = try {
            withTimeout(2_000) { entered.await() }
            withTimeout(2_000) { worker.await() }.also {
                assertSame(input, it.input)
                assertFalse(it.reader.isReleased())
            }
        } finally { dispatch_semaphore_signal(blocker) }
        withTimeout(3_000) { while (!failure.reader.isReleased()) delay(20) }
    }

    @Test fun healthyNativeWorkerReturnsTheDecodedResult() = runIosMainContract {
        assertEquals(AssetValidation.Valid(1_000), runIosFileValidation(input) { AssetValidation.Valid(1_000) })
    }
}
