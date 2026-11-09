package dev.veyra.playback

import dev.veyra.data.getOrThrow
import dev.veyra.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CrossfadeControllerTest {
    @Test fun actualCompletionCommitsBothPositionsAndIncomingReceiptBeforeAckWithoutReprepare() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play(crossfade = true)
            f.startFade()
            val outgoing = f.state.generation
            val next = f.engine.staged!!
            val incomingEntry = f.state.crossfade!!.incomingEntryId
            assertEquals(CrossfadePhase.OVERLAPPING, f.state.crossfade?.phase)
            assertEquals("first", f.state.currentItem?.id)
            var durableAtAck = false
            f.engine.beforeConfirm = {
                val persisted = f.store.state.value
                durableAtAck = persisted.queue(QueueContext.MUSIC).currentEntryId == incomingEntry &&
                    persisted.progress["first"]?.positionMs == 98_000L &&
                    persisted.progress["second"]?.positionMs == 2250L &&
                    persisted.progress["second"]?.executionBindingId == next.source.executionBinding?.id
            }
            f.event(EngineEvent.CrossfadeCompleted(outgoing, next.generation, 2250, 100_000, true))
            assertTrue(durableAtAck)
            assertEquals("second", f.state.currentItem?.id)
            assertEquals(2250L, f.state.positionMs)
            assertEquals(next.source.executionBinding, f.state.executionBinding)
            assertEquals(listOf(outgoing to next.generation), f.engine.confirmations)
            assertEquals(1, f.engine.loads.size)
            assertEquals(1, f.engine.preloads.size)
            assertNull(f.state.crossfade)
            assertEquals(2, f.store.state.value.history.size)
            f.event(EngineEvent.Ended(outgoing))
            f.event(EngineEvent.Position(outgoing, 100_000, 100_000))
            f.event(EngineEvent.CrossfadeCompleted(outgoing, next.generation, 9999, 100_000, true))
            assertEquals(2250L, f.state.positionMs)
            assertEquals(1, f.engine.confirmations.size)
        } finally { f.close() }
    }

    @Test fun pauseBeforeProposalHandlingCancelsBothAndNeverAcknowledgesStaleIncoming() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play(crossfade = true)
            f.startFade()
            val outgoing = f.state.generation
            val incoming = f.engine.staged!!.generation
            val entry = f.state.currentEntryId
            f.command(PlaybackCommand.Pause)
            f.event(EngineEvent.CrossfadeCompleted(outgoing, incoming, 2000, 100_000, true))
            assertEquals(entry, f.state.currentEntryId)
            assertFalse(f.state.isPlaying || f.state.desiredPlaying)
            assertTrue(f.engine.confirmations.isEmpty())
            assertNull(f.engine.staged)
            assertNull(f.state.crossfade)
            // A retained outgoing EOF after cancellation is truthful but cannot auto-advance paused.
            f.event(EngineEvent.Ended(outgoing))
            assertEquals(entry, f.state.currentEntryId)
            assertFalse(f.state.desiredPlaying)
        } finally { f.close() }
    }

    @Test fun pauseAfterDurableProposalButBeforeAckTargetsIncomingAfterNativeHandover() = runTest {
        val f = PlaybackFixture(this)
        val ackGate = CompletableDeferred<Unit>()
        val ackEntered = CompletableDeferred<Unit>()
        try {
            f.play(crossfade = true)
            f.startFade()
            val outgoing = f.state.generation
            val incoming = f.engine.staged!!.generation
            f.engine.beforeConfirm = { ackEntered.complete(Unit); ackGate.await() }
            f.event(EngineEvent.CrossfadeCompleted(outgoing, incoming, 2100, 100_000, true))
            assertTrue(ackEntered.isCompleted)
            assertEquals(incoming, f.state.generation)
            assertEquals(outgoing, f.engine.activeGeneration) // Native deliberately retains owner until ACK.
            f.command(PlaybackCommand.Pause)
            assertFalse(f.state.desiredPlaying)
            ackGate.complete(Unit); runCurrent()
            assertEquals(incoming, f.engine.activeGeneration)
            assertEquals(incoming, f.engine.pauses.last())
            assertFalse(f.state.isPlaying)
        } finally { ackGate.complete(Unit); f.close() }
    }

    @Test fun failedDurableHandoverDoesNotAckAndStopsBothNativeDecks() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play(crossfade = true)
            f.startFade()
            val outgoing = f.state.generation
            val incoming = f.engine.staged!!.generation
            val oldEntry = f.state.currentEntryId
            f.persistence.reject = { it.queue(QueueContext.MUSIC).currentEntryId != oldEntry }
            f.event(EngineEvent.CrossfadeCompleted(outgoing, incoming, 2100, 100_000, true))
            assertEquals(oldEntry, f.store.state.value.queue(QueueContext.MUSIC).currentEntryId)
            assertTrue(f.engine.confirmations.isEmpty())
            assertNull(f.engine.active)
            assertNull(f.engine.staged)
            assertEquals(PlaybackPhase.ERROR, f.state.phase)
        } finally { f.persistence.reject = null; f.close() }
    }

    @Test fun seekDuringOverlapClearsProjectionAndRejectsOldProposalWithoutRestartingIncoming() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play(crossfade = true)
            f.startFade()
            val outgoing = f.state.generation
            val incoming = f.engine.staged!!.generation
            f.command(PlaybackCommand.Seek(2000))
            assertNull(f.state.crossfade)
            assertNull(f.engine.staged)
            f.event(EngineEvent.CrossfadeCompleted(outgoing, incoming, 2000, 100_000, true))
            assertEquals("first", f.state.currentItem?.id)
            assertEquals(2000L, f.state.positionMs)
            assertTrue(f.engine.confirmations.isEmpty())
        } finally { f.close() }
    }

    @Test fun queueReplacementAndSleepExpiryCannotLeaveAnOrphanedIncomingDeck() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play(crossfade = true)
            f.startFade()
            val old = f.state.generation
            val incoming = f.engine.staged!!.generation
            f.command(PlaybackCommand.Play(QueueContext.MUSIC, listOf(testTrack("replacement"))))
            f.event(EngineEvent.CrossfadeCompleted(old, incoming, 2000, 100_000, true))
            assertEquals("replacement", f.state.currentItem?.id)
            assertNull(f.engine.staged)
            f.play(crossfade = true)
            f.startFade()
            val outgoing2 = f.state.generation
            val incoming2 = f.engine.staged!!.generation
            f.command(PlaybackCommand.SleepDuration(100))
            advanceTimeBy(100)
            // Even before the next timer tick, critical event handling checks the monotonic deadline.
            f.event(EngineEvent.CrossfadeCompleted(outgoing2, incoming2, 2000, 100_000, true))
            assertFalse(f.state.desiredPlaying)
            assertEquals("first", f.state.currentItem?.id)
            assertTrue(f.engine.confirmations.isEmpty())
            assertNull(f.engine.staged)
        } finally { f.close() }
    }

    @Test fun persistentTwoThreeFourFiveSecondAndCustomDurationsDriveNativeOverlap() = runTest {
        for (duration in listOf(2000L, 3000L, 4000L, 5000L, 1750L)) {
            val f = PlaybackFixture(this)
            try {
                f.store.update { it.copy(settings = it.settings.copy(crossfadeDurationMs = duration)) }.getOrThrow()
                f.play(crossfade = true)
                f.readyNext()
                f.event(EngineEvent.Position(f.state.generation, 100_000 - duration, 100_000))
                advanceTimeBy(250); runCurrent()
                assertEquals(duration, f.engine.fades.single().durationMs)
            } finally { f.close() }
        }
    }

    @Test fun disabledCrossfadePodcastAndRepeatOneNeverStageAutomaticOverlap() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play()
            f.event(EngineEvent.Position(f.state.generation, 99_000, 100_000)); advanceTimeBy(250); runCurrent()
            assertTrue(f.engine.preloads.isEmpty())
            f.play(crossfade = true)
            f.command(PlaybackCommand.SetRepeat(RepeatMode.ONE))
            f.event(EngineEvent.Position(f.state.generation, 99_000, 100_000)); advanceTimeBy(250); runCurrent()
            assertTrue(f.engine.preloads.isEmpty())
            f.play(listOf(testTrack("episode", MediaKind.EPISODE), testTrack("episode-2", MediaKind.EPISODE)), crossfade = true)
            f.event(EngineEvent.Position(f.state.generation, 99_000, 100_000)); advanceTimeBy(250); runCurrent()
            assertTrue(f.engine.preloads.isEmpty() && f.engine.fades.isEmpty())
        } finally { f.close() }
    }

    @Test fun unknownDurationsSkipAndVeryShortIncomingCapsRatherThanOverruns() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.resolver.durations["first"] = null
            f.play(crossfade = true)
            f.event(EngineEvent.Position(f.state.generation, 99_000, null)); advanceTimeBy(250); runCurrent()
            assertNull(f.state.durationMs)
            assertTrue(f.engine.preloads.isEmpty())
            f.resolver.durations["first"] = 100_000
            f.play(crossfade = true)
            f.readyNext(nextDuration = null)
            assertNull(f.engine.staged)
            assertTrue(f.engine.fades.isEmpty())
            f.play(crossfade = true)
            f.readyNext(nextDuration = 200)
            f.event(EngineEvent.Position(f.state.generation, 99_900, 100_000)); advanceTimeBy(250); runCurrent()
            assertEquals(100L, f.engine.fades.single().durationMs)
        } finally { f.close() }
    }

    @Test fun duplicateReadyEventsDoNotReapplyRateOrBeginOverlapTwice() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play(crossfade = true)
            f.startFade()
            val incoming = f.engine.staged!!.generation
            val outgoing = f.state.generation
            val rateCount = f.engine.rates.size
            f.event(EngineEvent.Ready(outgoing, 100_000, true))
            f.event(EngineEvent.StageReady(outgoing, incoming, 100_000, true))
            advanceTimeBy(500); runCurrent()
            assertEquals(rateCount, f.engine.rates.size)
            assertEquals(1, f.engine.fades.size)
            assertNotNull(f.engine.staged)
        } finally { f.close() }
    }

    @Test fun durationSettingChangeDisposesOldStageAndRejectsItsLateReadyOrCompletion() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play(crossfade = true)
            f.readyNext()
            val outgoing = f.state.generation
            val oldNext = f.engine.staged!!.generation
            f.store.update { it.copy(settings = it.settings.copy(crossfadeDurationMs = 5000)) }.getOrThrow()
            advanceTimeBy(250); runCurrent()
            assertNotEquals(oldNext, f.engine.staged?.generation)
            f.event(EngineEvent.StageReady(outgoing, oldNext, 100_000, true))
            f.event(EngineEvent.CrossfadeCompleted(outgoing, oldNext, 2000, 100_000, true))
            assertEquals("first", f.state.currentItem?.id)
            assertTrue(f.engine.confirmations.isEmpty())
            f.store.update { it.copy(settings = it.settings.copy(crossfadeEnabled = false)) }.getOrThrow()
            advanceTimeBy(250); runCurrent()
            assertNull(f.engine.staged)
            assertNull(f.state.crossfade)
        } finally { f.close() }
    }

    @Test fun incomingFailureIsContainedAndSuppressedUntilNormalTransition() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play(crossfade = true)
            f.readyNext()
            val incoming = f.engine.staged!!.generation
            f.event(EngineEvent.StageFailed(f.state.generation, incoming, AppFailure(FailureCode.UNSUPPORTED_CODEC)))
            assertEquals("first", f.state.currentItem?.id)
            assertTrue(f.state.desiredPlaying && f.state.isPlaying)
            assertNull(f.engine.staged)
            advanceTimeBy(5000); runCurrent()
            assertEquals(1, f.engine.preloads.size)
        } finally { f.close() }
    }

    @Test fun cancellationEnvelopeRemainsInsideHeadroomAndEqualPowerIsContinuous() {
        val masterSquared = CrossfadeMix.MUSIC_GAIN * CrossfadeMix.MUSIC_GAIN
        var previousOutgoing = Float.POSITIVE_INFINITY
        var previousIncoming = -1f
        for (index in 0..1000) {
            val t = index / 1000f
            val outgoing = CrossfadeMix.outgoing(t)
            val incoming = CrossfadeMix.incoming(t)
            assertTrue(abs(outgoing * outgoing + incoming * incoming - masterSquared) < 0.000001f)
            assertTrue(outgoing + incoming < 1f) // Even fully correlated unity source peaks retain headroom.
            assertTrue(outgoing <= previousOutgoing && incoming >= previousIncoming)
            previousOutgoing = outgoing; previousIncoming = incoming
            for (step in 0..10) {
                val (out, inside) = CrossfadeMix.cancel(outgoing, incoming, step / 10f)
                assertTrue(out >= 0f && inside >= 0f && out + inside < 1f)
            }
        }
        assertEquals(CrossfadeMix.MUSIC_GAIN, CrossfadeMix.outgoing(0f))
        assertEquals(0f, CrossfadeMix.incoming(0f))
        assertTrue(abs(CrossfadeMix.outgoing(1f)) < 0.000001f)
        assertEquals(CrossfadeMix.MUSIC_GAIN, CrossfadeMix.incoming(1f))
        assertEquals(CrossfadeMix.outgoing(0f), CrossfadeMix.outgoing(Float.NaN))
        assertEquals(CrossfadeMix.incoming(0f), CrossfadeMix.incoming(-1f))
    }
}
