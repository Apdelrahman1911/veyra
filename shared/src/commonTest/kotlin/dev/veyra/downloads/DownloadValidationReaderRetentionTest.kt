package dev.veyra.downloads

import dev.veyra.model.*
import dev.veyra.playback.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Authored cleanup-observation tests, not native destroy or OS file-lock evidence. */
class DownloadValidationReaderRetentionTest {
    @Test fun aSuccessfulValidationCreatesNoNativeCleanupObligation() = runTest {
        val retention = DownloadValidationReaderRetention()
        assertEquals(733L, retention.validate { 733L })
        retention.requireReleased()
    }

    @Test fun ordinaryValidationFailureIsNotManufacturedIntoAnUnreleasedNativeOwner() = runTest {
        val retention = DownloadValidationReaderRetention()
        val ordinary = VeyraException(AppFailure(FailureCode.CORRUPT_MEDIA))
        assertSame(ordinary, assertFailsWith<VeyraException> { retention.validate { throw ordinary } })
        retention.requireReleased()
    }

    @Test fun exactTypedFailureIsRetainedBeforeGenericClassificationCanDiscardIt() = runTest {
        val retention = DownloadValidationReaderRetention()
        val failure = failure { false }
        assertSame(failure, assertFailsWith<LocalMediaValidationReaderUnacknowledged> {
            retention.validate { throw failure }
        })
        repeat(2) {
            assertSame(failure, assertFailsWith<LocalMediaValidationReaderUnacknowledged> { retention.requireReleased() })
        }
    }

    @Test fun onlyOriginalReadersPositiveAcknowledgmentLetsTheCleanupBarrierPass() = runTest {
        val retention = DownloadValidationReaderRetention()
        var released = false
        var observations = 0
        val failure = failure { observations++; released }
        assertFailsWith<LocalMediaValidationReaderUnacknowledged> { retention.validate { throw failure } }
        assertFailsWith<LocalMediaValidationReaderUnacknowledged> { retention.requireReleased() }
        released = true // Authored original-native completion; NOT an implicit close or retry.
        retention.requireReleased()
        assertEquals(2, observations)
    }

    @Test fun throwingReleaseObservationDoesNotConsumeOrReplaceTheOriginalReader() = runTest {
        val retention = DownloadValidationReaderRetention()
        var observerThrows = true
        val failure = failure { if (observerThrows) throw IllegalStateException("authored observer failure") else false }
        assertFailsWith<LocalMediaValidationReaderUnacknowledged> { retention.validate { throw failure } }
        assertFailsWith<IllegalStateException> { retention.requireReleased() }
        observerThrows = false
        assertSame(failure, assertFailsWith<LocalMediaValidationReaderUnacknowledged> { retention.requireReleased() })
    }

    @Test fun cancellationOfALaterOperationCannotForgetAnAlreadyRetainedReader() = runTest {
        val retention = DownloadValidationReaderRetention()
        val failure = failure { false }
        assertFailsWith<LocalMediaValidationReaderUnacknowledged> { retention.validate { throw failure } }
        assertFailsWith<CancellationException> { retention.validate { throw CancellationException("authored cancellation") } }
        assertSame(failure, assertFailsWith<LocalMediaValidationReaderUnacknowledged> { retention.requireReleased() })
    }

    @Test fun multipleUnacknowledgedOriginalReadersMustAllReleaseIndependently() = runTest {
        val retention = DownloadValidationReaderRetention()
        var firstReleased = false
        var secondReleased = false
        val first = failure { firstReleased }
        // A mismatched reported input is still an owned reader, never an ordinary Invalid result.
        val second = LocalMediaValidationReaderUnacknowledged(first.input.copy(uri = "/authored/other-input"),
            LocalMediaValidationReaderRelease { secondReleased })
        assertFailsWith<LocalMediaValidationReaderUnacknowledged> { retention.validate { throw first } }
        assertFailsWith<LocalMediaValidationReaderUnacknowledged> { retention.validate { throw second } }
        assertSame(first, assertFailsWith<LocalMediaValidationReaderUnacknowledged> { retention.requireReleased() })
        firstReleased = true
        assertSame(second, assertFailsWith<LocalMediaValidationReaderUnacknowledged> { retention.requireReleased() })
        secondReleased = true
        retention.requireReleased()
    }

    private fun failure(released: () -> Boolean) = LocalMediaValidationReaderUnacknowledged(
        LocalMediaValidationInput("/authored/owned-local-input", OfflineAssetKind.FILE, 7, "audio/mpeg", "mp3",
            "a".repeat(64), "authored-representation", 0), LocalMediaValidationReaderRelease(released))
}
