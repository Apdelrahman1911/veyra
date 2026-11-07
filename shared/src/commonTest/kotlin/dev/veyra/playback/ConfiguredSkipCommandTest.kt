package dev.veyra.playback

import dev.veyra.data.getOrThrow
import dev.veyra.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Authored actor/relational-fake regressions, NOT SQLite, native-session or audio evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfiguredSkipCommandTest {
    @Test fun capturedCommandReadsChangedConfiguredSecondsAtActorAdmissionNotCallbackTime() = runTest {
        val rig = AcquisitionPlaybackFixture(this)
        try {
            rig.initialize()
            val id = rig.neutral(PlaybackControlsMode.PODCAST)
            val generation = rig.state.generation
            val forward = PlaybackCommand.SkipConfigured(true, id.value, generation)
            val backward = PlaybackCommand.SkipConfigured(false, id.value, generation)
            rig.event(EngineEvent.Position(generation, 50_000, 100_000))
            rig.store.update { it.copy(settings = it.settings.copy(skipForwardSeconds = 17, skipBackSeconds = 8)) }.getOrThrow()
            rig.command(forward)
            assertEquals(67_000L, rig.engine.seeks.last().positionMs)
            rig.command(backward)
            assertEquals(59_000L, rig.engine.seeks.last().positionMs)
            rig.store.update { it.copy(settings = it.settings.copy(skipForwardSeconds = 4, skipBackSeconds = 13)) }.getOrThrow()
            rig.command(forward) // SAME captured pins, newly configured duration.
            rig.command(backward)
            assertEquals(listOf(67_000L, 59_000L, 63_000L, 50_000L), rig.engine.seeks.map { it.positionMs })
            assertTrue(rig.engine.seeks.all { it.generation == generation })
            assertEquals(4, rig.engine.seeks.map { it.sequence }.distinct().size)
            assertEquals(id.value, rig.state.currentEntryId)
            assertNull(rig.state.currentItem, "Neutral long-form controls do not manufacture an RSS episode")
            assertTrue(rig.store.state.value.progress.isEmpty())
            assertEquals(1, rig.gateway.selections.acquisitions.size)
        } finally { rig.close() }
    }

    @Test fun reloadedSameOccurrenceRejectsCapturedOldGenerationBeforeAnySeekOrStageCancellation() = runTest {
        val rig = AcquisitionPlaybackFixture(this)
        try {
            rig.initialize()
            val id = rig.neutral(PlaybackControlsMode.PODCAST)
            val old = rig.state.generation
            val captured = listOf(PlaybackCommand.SkipConfigured(false, id.value, old), PlaybackCommand.SkipConfigured(true, id.value, old))
            rig.command(PlaybackCommand.Stop)
            rig.command(PlaybackCommand.Resume)
            assertEquals(id.value, rig.state.currentEntryId)
            assertNotEquals(old, rig.state.generation)
            rig.event(EngineEvent.Position(rig.state.generation, 42_000, 100_000))
            val phase = rig.state.phase
            val stops = rig.engine.stops.toList()
            val cancellations = rig.engine.cancellations.toList()
            for (command in captured) {
                val result = assertIs<PlaybackResult.Failure>(rig.controller.dispatch(command))
                assertEquals(FailureCode.INVALID_STATE, result.failure.code)
                runCurrent()
            }
            assertTrue(rig.engine.seeks.isEmpty())
            assertEquals(stops, rig.engine.stops)
            assertEquals(cancellations, rig.engine.cancellations)
            assertEquals(42_000L, rig.state.positionMs)
            assertEquals(phase, rig.state.phase)
        } finally { rig.close() }
    }

    @Test fun wrongOccurrenceIsRejectedEvenWhenItSuppliesTheCurrentGenerationAndSameRecording() = runTest {
        val rig = AcquisitionPlaybackFixture(this)
        try {
            rig.initialize()
            val first = rig.neutral(PlaybackControlsMode.PODCAST)
            val recording = rig.state.currentSelection?.binding?.recordingId
            val second = rig.neutral(PlaybackControlsMode.PODCAST)
            assertNotEquals(first, second)
            assertEquals(recording, rig.state.currentSelection?.binding?.recordingId)
            val generation = rig.state.generation
            rig.event(EngineEvent.Position(generation, 31_000, 100_000))
            for (forward in listOf(false, true)) {
                val failure = assertIs<PlaybackResult.Failure>(rig.controller.dispatch(PlaybackCommand.SkipConfigured(forward, first.value, generation)))
                assertEquals(FailureCode.INVALID_STATE, failure.failure.code)
                runCurrent()
            }
            assertTrue(rig.engine.seeks.isEmpty())
            assertEquals(second.value, rig.state.currentEntryId)
            assertEquals(generation, rig.state.generation)
            assertEquals(31_000L, rig.state.positionMs)
        } finally { rig.close() }
    }

    @Test fun matchingPinsStillRequireAnActuallyPreparedSeekableSelection() = runTest {
        val rig = AcquisitionPlaybackFixture(this)
        try {
            rig.initialize()
            rig.engine.autoReady = false
            val id = rig.neutral(PlaybackControlsMode.PODCAST)
            val generation = rig.state.generation
            val command = PlaybackCommand.SkipConfigured(true, id.value, generation)
            assertEquals(PlaybackPhase.PREPARING, rig.state.phase)
            assertIs<PlaybackResult.Failure>(rig.controller.dispatch(command))
            runCurrent()
            assertTrue(rig.engine.seeks.isEmpty())
            rig.event(EngineEvent.Ready(generation, 100_000, seekable = false))
            assertFalse(rig.state.seekable)
            assertIs<PlaybackResult.Failure>(rig.controller.dispatch(command))
            runCurrent()
            assertTrue(rig.engine.seeks.isEmpty())
            assertEquals(id.value, rig.state.currentEntryId)
        } finally { rig.close() }
    }

    @Test fun configuredStepsUseTheSameNativeDurationAndZeroClampsAsOrdinarySeek() = runTest {
        val rig = AcquisitionPlaybackFixture(this)
        try {
            rig.initialize()
            val id = rig.neutral(PlaybackControlsMode.PODCAST)
            val generation = rig.state.generation
            rig.store.update { it.copy(settings = it.settings.copy(skipForwardSeconds = 300, skipBackSeconds = 300)) }.getOrThrow()
            rig.event(EngineEvent.Position(generation, 1_000, 100_000))
            rig.command(PlaybackCommand.SkipConfigured(false, id.value, generation))
            rig.command(PlaybackCommand.SkipConfigured(true, id.value, generation))
            assertEquals(listOf(0L, 100_000L), rig.engine.seeks.map { it.positionMs })
            assertEquals(100_000L, rig.state.positionMs)
        } finally { rig.close() }
    }

    @Test fun legacyEpisodeAcceptsPinnedConfiguredSkipAndRetainsExistingUnpinnedCommands() = runTest {
        val rig = PlaybackFixture(this)
        try {
            rig.play(listOf(testTrack("episode", MediaKind.EPISODE)))
            val entry = assertNotNull(rig.state.currentEntryId)
            val generation = rig.state.generation
            rig.store.update { it.copy(settings = it.settings.copy(skipForwardSeconds = 9, skipBackSeconds = 7)) }.getOrThrow()
            rig.event(EngineEvent.Position(generation, 40_000, 100_000))
            rig.command(PlaybackCommand.SkipConfigured(true, entry, generation))
            rig.command(PlaybackCommand.SkipBack)
            rig.command(PlaybackCommand.SkipForward)
            assertEquals(listOf(49_000L, 42_000L, 51_000L), rig.engine.seeks.map { it.positionMs })
            assertEquals(entry, rig.state.currentEntryId)
            assertEquals(MediaKind.EPISODE, rig.state.currentItem?.kind)
            assertTrue(rig.engine.loads.all { it.acquired == null })
            assertEquals(1, rig.resolver.requests.size)
        } finally { rig.close() }
    }
}
