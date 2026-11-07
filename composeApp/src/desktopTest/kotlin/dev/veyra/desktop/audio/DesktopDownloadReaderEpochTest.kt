package dev.veyra.desktop.audio

import dev.veyra.model.FailureCode
import dev.veyra.playback.NativeReaderDrainResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Direct tests of the REAL mpv-owner-thread epoch value in DesktopAudioDeck.kt. This value is
 * production code, not a second mock state machine. It answers only reader-release uncertainty;
 * the engine/controller still excludes the current playback target from deletion separately.
 */
class DesktopDownloadReaderEpochTest {
    @Test fun initialResultEstablishesOnlyPristineZero() {
        val epoch = DesktopNativeReaderEpoch()
        assertEquals(Long.MIN_VALUE, epoch.generation)

        assertIs<NativeReaderDrainResult.Superseded>(epoch.result(-1L))
        assertIs<NativeReaderDrainResult.Superseded>(epoch.result(1L))
        assertIs<NativeReaderDrainResult.Superseded>(epoch.result(7L))
        assertEquals(Long.MIN_VALUE, epoch.generation, "A wrong initial expectation must not adopt an epoch")

        val pristine = assertIs<NativeReaderDrainResult.Drained>(epoch.result(0L))
        assertEquals(0L, pristine.expectedGeneration)
        assertEquals(0L, epoch.generation)
        assertEquals(pristine, epoch.result(0L), "The established pristine epoch remains exactly zero")
    }

    @Test fun acceptedStopOrReplacementRetainsRetiredUncertaintyUntilExactDestruction() {
        val stopped = DesktopNativeReaderEpoch()
        assertTrue(stopped.adoptSource(1L))
        stopped.stopped(2L)
        assertEquals(2L, stopped.generation)
        val uncertain = assertIs<NativeReaderDrainResult.Unacknowledged>(stopped.result(2L))
        assertEquals(FailureCode.RECOVERY_REQUIRED, uncertain.failure.code)
        assertEquals("native_reader_release_unacknowledged", uncertain.failure.detail)
        assertIs<NativeReaderDrainResult.Superseded>(stopped.result(1L), "The old epoch is not the final epoch")

        stopped.destroyed(acknowledged = true)
        assertEquals(NativeReaderDrainResult.Drained(2L), stopped.result(2L))
        assertIs<NativeReaderDrainResult.Superseded>(stopped.result(1L))
        assertIs<NativeReaderDrainResult.Superseded>(stopped.result(3L))

        val replaced = DesktopNativeReaderEpoch()
        assertTrue(replaced.adoptSource(4L))
        assertTrue(replaced.adoptSource(5L))
        assertIs<NativeReaderDrainResult.Unacknowledged>(replaced.result(5L))
        replaced.destroyed(acknowledged = true)
        assertEquals(NativeReaderDrainResult.Drained(5L), replaced.result(5L))
        assertIs<NativeReaderDrainResult.Superseded>(replaced.result(4L))
    }

    @Test fun failedDestructionStaysUnacknowledgedAndNeverClearsItself() {
        val epoch = DesktopNativeReaderEpoch()
        assertTrue(epoch.adoptSource(11L))
        epoch.destroyed(acknowledged = false)

        val first = assertIs<NativeReaderDrainResult.Unacknowledged>(epoch.result(11L))
        val second = assertIs<NativeReaderDrainResult.Unacknowledged>(epoch.result(11L))
        assertEquals("native_reader_release_unacknowledged", first.failure.detail)
        assertEquals(first, second, "Re-observation is side-effect free; it cannot invent a retry or ACK")
        assertIs<NativeReaderDrainResult.Superseded>(epoch.result(10L))
        assertIs<NativeReaderDrainResult.Superseded>(epoch.result(12L))
    }

    @Test fun staleLowerAdoptionCannotRollBackAndEqualReuseIsExplicit() {
        val epoch = DesktopNativeReaderEpoch()
        assertTrue(epoch.adoptSource(20L))
        assertFalse(epoch.adoptSource(19L), "A stale lower adoption is rejected")
        assertEquals(20L, epoch.generation)

        assertTrue(epoch.adoptSource(20L), "The implementation explicitly permits equal-generation reuse")
        assertEquals(20L, epoch.generation)
        assertIs<NativeReaderDrainResult.Unacknowledged>(epoch.result(20L),
            "Equal reuse of a source requires a new destruction ACK")

        epoch.destroyed(acknowledged = true)
        assertEquals(NativeReaderDrainResult.Drained(20L), epoch.result(20L))
        assertIs<NativeReaderDrainResult.Superseded>(epoch.result(19L))
    }

    @Test fun liveCurrentReaderIsNotARetiredDeletionGrant() {
        val epoch = DesktopNativeReaderEpoch()
        assertTrue(epoch.adoptSource(30L))
        assertEquals(NativeReaderDrainResult.Drained(30L), epoch.result(30L),
            "There is no retired reader yet; this says nothing about deleting the live current target")

        // The same live epoch becomes retired-reader uncertainty only after an accepted stop.
        // The controller must also recheck queued/current target exclusion after this fence.
        epoch.stopped(30L)
        assertIs<NativeReaderDrainResult.Unacknowledged>(epoch.result(30L))
        epoch.destroyed(acknowledged = true)
        assertEquals(NativeReaderDrainResult.Drained(30L), epoch.result(30L))
    }

    @Test fun destroyedEpochCannotAdoptAnotherSource() {
        val epoch = DesktopNativeReaderEpoch()
        assertTrue(epoch.adoptSource(40L))
        epoch.destroyed(acknowledged = true)
        val error = kotlin.test.assertFailsWith<IllegalStateException> { epoch.adoptSource(41L) }
        assertTrue(error.message.orEmpty().isNotEmpty())
        assertEquals(NativeReaderDrainResult.Drained(40L), epoch.result(40L))
    }
}
