package dev.veyra.playback

import dev.veyra.data.getOrThrow
import dev.veyra.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Authored native-event seams: restart intent/order, not native seek or audible evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
class PodcastRestartTest {
    @Test fun currentEpisodeRestartsWithoutIssuingOrWaitingForANativeSeek() = runTest {
        val episode = testTrack("episode", MediaKind.EPISODE)
        val f = PlaybackFixture(this)
        try {
            f.play(listOf(episode))
            f.event(EngineEvent.Position(f.state.generation, 45_000, 100_000))
            val outgoing = f.state.generation
            val outgoingEntry = f.state.currentEntryId
            f.engine.autoSeek = false // This engine will never confirm a seek.
            f.engine.autoReady = false
            f.command(PlaybackCommand.Play(QueueContext.PODCAST, listOf(episode), startFromBeginning = true))

            assertNotEquals(outgoing, f.state.generation)
            assertNotEquals(outgoingEntry, f.state.currentEntryId)
            assertEquals(0L, f.engine.loads.last().startPositionMs)
            assertEquals(0L, f.state.positionMs)
            assertEquals(PlaybackPhase.PREPARING, f.state.phase)
            assertFalse(f.state.isPlaying, "A requested restart is not a confirmed native playing state")
            assertTrue(f.state.desiredPlaying)
            assertNull(f.state.pendingSeekMs)
            assertTrue(f.engine.seeks.isEmpty())
            assertEquals(45_000L, f.store.state.value.history.single().lastPositionMs)
            assertEquals(0L, f.store.state.value.progress.getValue(episode.id).positionMs)

            f.event(EngineEvent.Ready(f.state.generation, 100_000, true))
            assertTrue(f.state.isPlaying)
            assertTrue(f.engine.seeks.isEmpty())
        } finally { f.close() }
    }

    @Test fun resetAndOutgoingCheckpointAreOneCommitDespiteALateOldSeekCompletion() = runTest {
        val episode = testTrack("episode", MediaKind.EPISODE)
        val f = PlaybackFixture(this)
        val resolveRestart = CompletableDeferred<Unit>()
        try {
            f.play(listOf(episode))
            f.event(EngineEvent.Position(f.state.generation, 45_000, 100_000))
            f.command(PlaybackCommand.MarkPlayed(episode.id, true))
            val outgoing = f.state.generation
            val oldBinding = assertNotNull(f.state.executionBinding)
            f.engine.autoSeek = false
            f.command(PlaybackCommand.Seek(70_000))
            val pending = f.engine.seeks.single()
            assertEquals(45_000L, f.state.positionMs)
            assertEquals(70_000L, f.state.pendingSeekMs)
            f.resolver.behavior = { item, reuse -> resolveRestart.await(); f.resolver.result(item, reuse) }
            f.engine.autoReady = false
            val commits = f.persistence.commits.size

            f.command(PlaybackCommand.Play(QueueContext.PODCAST, listOf(episode), startFromBeginning = true))
            assertEquals(commits + 1, f.persistence.commits.size, "Checkpoint and restart intent must not be separate commits")
            val saved = f.persistence.commits.last()
            val progress = saved.progress.getValue(episode.id)
            assertEquals(0L, saved.queue(QueueContext.PODCAST).positionMs)
            assertEquals(0L, progress.positionMs)
            assertFalse(progress.played)
            assertEquals(100_000L, progress.durationMs)
            assertEquals(oldBinding.id, progress.executionBindingId)
            assertEquals("", progress.sessionId, "The reset is not an observation by the old native session")
            assertEquals(0L, progress.generation)
            assertEquals(0L, progress.sequence)
            val outgoingHistory = saved.history.single()
            assertEquals(45_000L, outgoingHistory.lastPositionMs)
            assertEquals(oldBinding.id, outgoingHistory.executionBindingId)
            assertFalse(outgoingHistory.endedNaturally)
            assertEquals(PlaybackPhase.RESOLVING, f.state.phase)
            assertFalse(f.state.isPlaying)
            assertNull(f.state.pendingSeekMs)

            f.event(EngineEvent.SeekCompleted(outgoing, pending.sequence, 70_000))
            f.event(EngineEvent.Position(outgoing, 71_000, 100_000, seekSequence = pending.sequence))
            f.event(EngineEvent.Ended(outgoing))
            assertEquals(saved, f.store.state.value)
            assertEquals(0L, f.state.positionMs)

            resolveRestart.complete(Unit); runCurrent()
            assertEquals(0L, f.engine.loads.last().startPositionMs)
            assertEquals(listOf(pending), f.engine.seeks)
            f.event(EngineEvent.Ready(f.state.generation, 100_000, true))
            f.command(PlaybackCommand.FlushProgress)
            assertEquals(2, f.store.state.value.history.size)
            assertEquals(outgoingHistory, f.store.state.value.history.first())
            assertEquals(0L, f.store.state.value.history.last().lastPositionMs)
            assertFalse(f.store.state.value.progress.getValue(episode.id).played)
        } finally { resolveRestart.complete(Unit); f.close() }
    }

    @Test fun onlySelectedPodcastResetsWhileOutgoingMusicAndOtherEpisodesArePreserved() = runTest {
        val episodes = (1..3).map { testTrack("episode-$it", MediaKind.EPISODE) }
        val binding = PlaybackBinding("selected-binding", episodes[1].id, episodes[1].title,
            FirstResultKind.VIDEO, "BBBBBBBBBBB", "Saved selected recording",
            "https://www.youtube.com/watch?v=BBBBBBBBBBB", "BBBBBBBBBBB")
        val progress = episodes.associate { item -> item.id to MediaProgress(80_000, 100_000, played = true,
            executionBindingId = binding.id.takeIf { item == episodes[1] }) }
        val initial = AppSnapshot(media = episodes.associateBy { it.id }, progress = progress,
            playbackBindings = mapOf(binding.id to binding), savedEpisodeIds = episodes.map { it.id }.toSet(),
            settings = AppSettings(speed = 1.5f))
        val f = PlaybackFixture(this, initial)
        val resolveRestart = CompletableDeferred<Unit>()
        try {
            f.play(listOf(testTrack()))
            f.event(EngineEvent.Position(f.state.generation, 33_000, 100_000))
            f.resolver.behavior = { item, reuse -> resolveRestart.await(); f.resolver.result(item, reuse) }
            f.command(PlaybackCommand.Play(QueueContext.PODCAST, episodes, startIndex = 1, startFromBeginning = true))

            val saved = f.store.state.value
            assertEquals(episodes[1], f.state.currentItem)
            assertEquals(episodes.map { it.id }, f.state.queue.entries.map { it.mediaId })
            assertEquals(1, f.state.queue.currentIndex)
            assertEquals(0L, f.state.queue.positionMs)
            assertEquals(1.5f, f.state.speed)
            assertEquals(progress.getValue(episodes[0].id), saved.progress[episodes[0].id])
            assertEquals(progress.getValue(episodes[2].id), saved.progress[episodes[2].id])
            val selected = saved.progress.getValue(episodes[1].id)
            assertEquals(0L, selected.positionMs)
            assertFalse(selected.played)
            assertEquals(binding.id, selected.executionBindingId)
            assertEquals(100_000L, selected.durationMs)
            assertEquals(initial.savedEpisodeIds, saved.savedEpisodeIds)
            assertEquals(33_000L, saved.queue(QueueContext.MUSIC).positionMs)
            assertEquals(33_000L, saved.progress.getValue("first").positionMs)
            assertEquals("first", saved.history.single().mediaId)
            assertEquals(33_000L, saved.history.single().lastPositionMs)
            resolveRestart.complete(Unit); runCurrent()
            assertEquals(0L, f.engine.loads.last().startPositionMs)
        } finally { resolveRestart.complete(Unit); f.close() }
    }

    @Test fun restartingNaturallyEndedEpisodeClearsPlayedButKeepsItsCompletedHistory() = runTest {
        val episode = testTrack("episode", MediaKind.EPISODE)
        val f = PlaybackFixture(this)
        try {
            f.play(listOf(episode))
            val endedGeneration = f.state.generation
            f.event(EngineEvent.Ended(endedGeneration))
            val completedHistory = f.store.state.value.history.single()
            assertTrue(completedHistory.endedNaturally)
            assertTrue(f.store.state.value.progress.getValue(episode.id).played)
            f.command(PlaybackCommand.Play(QueueContext.PODCAST, listOf(episode), startFromBeginning = true))
            f.event(EngineEvent.Ended(endedGeneration))
            f.command(PlaybackCommand.FlushProgress)
            assertEquals(0L, f.engine.loads.last().startPositionMs)
            assertFalse(f.store.state.value.progress.getValue(episode.id).played)
            assertEquals(completedHistory, f.store.state.value.history.first())
            assertEquals(2, f.store.state.value.history.size)
            assertFalse(f.store.state.value.history.last().endedNaturally)
        } finally { f.close() }
    }

    @Test fun rejectedAtomicRestartDoesNotResetProgressReplaceQueueOrStopOldNativeOwner() = runTest {
        val episode = testTrack("episode", MediaKind.EPISODE)
        val f = PlaybackFixture(this)
        try {
            f.play(listOf(episode))
            f.event(EngineEvent.Position(f.state.generation, 45_000, 100_000))
            f.command(PlaybackCommand.FlushProgress)
            f.command(PlaybackCommand.MarkPlayed(episode.id, true))
            val saved = f.store.state.value
            val state = f.state
            val loads = f.engine.loads.toList()
            val stops = f.engine.stops.toList()
            val requests = f.resolver.requests.toList()
            f.persistence.reject = { next -> next.queue(QueueContext.PODCAST).currentEntryId != state.currentEntryId }
            val result = f.controller.dispatch(PlaybackCommand.Play(QueueContext.PODCAST, listOf(episode), startFromBeginning = true))
            runCurrent()
            assertEquals(FailureCode.STORAGE_IO, assertIs<PlaybackResult.Failure>(result).failure.code)
            assertEquals(saved, f.store.state.value)
            assertEquals(saved, f.persistence.snapshot)
            assertEquals(state, f.state.copy(failure = state.failure))
            assertEquals(loads, f.engine.loads)
            assertEquals(stops, f.engine.stops)
            assertEquals(requests, f.resolver.requests)
            assertTrue(f.state.isPlaying)
        } finally { f.persistence.reject = null; f.close() }
    }

    @Test fun musicRestartFlagRejectsBeforePersistenceSourceOrNativeSideEffects() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play(crossfade = true)
            f.readyNext()
            val saved = f.store.state.value
            val state = f.state
            val staged = f.engine.staged
            val loads = f.engine.loads.toList()
            val stops = f.engine.stops.toList()
            val requests = f.resolver.requests.toList()
            val result = f.controller.dispatch(PlaybackCommand.Play(QueueContext.MUSIC, listOf(testTrack()), startFromBeginning = true))
            runCurrent()
            assertEquals(FailureCode.INVALID_STATE, assertIs<PlaybackResult.Failure>(result).failure.code)
            assertEquals(saved, f.store.state.value)
            assertEquals(state, f.state.copy(failure = state.failure))
            assertEquals(staged, f.engine.staged)
            assertEquals(loads, f.engine.loads)
            assertEquals(stops, f.engine.stops)
            assertEquals(requests, f.resolver.requests)
        } finally { f.close() }
    }

    @Test fun acceptedRestartSurvivesPausedRestoreWithoutAnyNativeCompletion() = runTest {
        val episode = testTrack("episode", MediaKind.EPISODE)
        val f = PlaybackFixture(this)
        val neverResolved = CompletableDeferred<Unit>()
        lateinit var oldBinding: PlaybackBinding
        try {
            f.play(listOf(episode))
            oldBinding = assertNotNull(f.state.executionBinding)
            f.event(EngineEvent.Position(f.state.generation, 45_000, 100_000))
            f.resolver.behavior = { item, reuse -> neverResolved.await(); f.resolver.result(item, reuse) }
            f.command(PlaybackCommand.Play(QueueContext.PODCAST, listOf(episode), startFromBeginning = true))
            assertEquals(PlaybackPhase.RESOLVING, f.state.phase)
            assertEquals(1, f.engine.loads.size, "New native preparation has not happened")
        } finally { f.close() }
        val reopened = PlaybackFixture(this, f.persistence.snapshot)
        try {
            reopened.controller.initialize().getOrThrow(); runCurrent()
            assertEquals(PlaybackPhase.PAUSED, reopened.state.phase)
            assertEquals(0L, reopened.state.positionMs)
            assertFalse(reopened.state.desiredPlaying)
            assertFalse(reopened.store.state.value.progress.getValue(episode.id).played)
            assertEquals(oldBinding, reopened.state.executionBinding)
            assertEquals(45_000L, reopened.store.state.value.history.single().lastPositionMs)
            assertTrue(reopened.engine.loads.isEmpty())
            assertTrue(reopened.resolver.requests.isEmpty())
        } finally { reopened.close() }
    }

    @Test fun ordinaryPlayStillResumesWithoutResettingExplicitPlayedState() = runTest {
        val episode = testTrack("episode", MediaKind.EPISODE)
        val binding = PlaybackBinding("saved", episode.id, episode.title, FirstResultKind.VIDEO,
            "BBBBBBBBBBB", "Saved recording", "https://www.youtube.com/watch?v=BBBBBBBBBBB", "BBBBBBBBBBB")
        val initial = AppSnapshot(media = mapOf(episode.id to episode), playbackBindings = mapOf(binding.id to binding),
            progress = mapOf(episode.id to MediaProgress(45_000, 100_000, executionBindingId = binding.id)))
        val f = PlaybackFixture(this, initial)
        try {
            f.command(PlaybackCommand.Play(QueueContext.PODCAST, listOf(episode)))
            assertEquals(45_000L, f.engine.loads.single().startPositionMs)
            f.command(PlaybackCommand.MarkPlayed(episode.id, true))
            f.command(PlaybackCommand.Play(QueueContext.PODCAST, listOf(episode)))
            assertEquals(0L, f.engine.loads.last().startPositionMs)
            assertTrue(f.store.state.value.progress.getValue(episode.id).played)
        } finally { f.close() }
    }
}
