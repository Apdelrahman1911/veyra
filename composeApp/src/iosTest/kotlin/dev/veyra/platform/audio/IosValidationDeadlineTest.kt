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
import platform.Foundation.NSThread
import kotlin.test.*

class IosValidationDeadlineTest {
    private val input = LocalMediaValidationInput("/fixture.m4a", OfflineAssetKind.FILE, 1, "audio/mp4", null, null, null, 0)

    @Test fun blockedNativeCallReturnsFailureBeforeUnwindAndRetainsTheExactReader() = runIosMainContract {
        val entered = CompletableDeferred<Unit>()
        val worker = async {
            assertFailsWith<LocalMediaValidationReaderUnacknowledged> {
                runIosFileValidation(input, timeoutMs = 300) {
                    entered.complete(Unit)
                    NSThread.sleepForTimeInterval(0.8)
                    AssetValidation.Valid(1_000)
                }
            }
        }
        withTimeout(2_000) { entered.await() }
        val failure = withTimeout(600) { worker.await() }
        assertSame(input, failure.input)
        assertFalse(failure.reader.isReleased())
        withTimeout(3_000) { while (!failure.reader.isReleased()) delay(20) }
    }

    @Test fun healthyNativeWorkerReturnsTheDecodedResult() = runIosMainContract {
        assertEquals(AssetValidation.Valid(1_000), runIosFileValidation(input) { AssetValidation.Valid(1_000) })
    }
}
