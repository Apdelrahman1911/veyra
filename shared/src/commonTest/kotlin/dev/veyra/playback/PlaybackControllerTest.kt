package dev.veyra.playback

import dev.veyra.data.getOrThrow
import dev.veyra.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerTest {
    @Test fun restoresBothQueuesPausedWithoutSourceOrNativePreparation() = runTest {
        val track = testTrack()
        val episode = testTrack("episode", MediaKind.EPISODE)
        val initial = AppSnapshot(media = listOf(track, episode).associateBy { it.id }, activeContext = QueueContext.PODCAST,
            queues = mapOf(QueueContext.MUSIC to QueueSnapshot(listOf(QueueEntry("m", track.id)), "m", 1234),
                QueueContext.PODCAST to QueueSnapshot(listOf(QueueEntry("p", episode.id)), "p", 5678)))
        val f = PlaybackFixture(this, initial)
        try {
            f.controller.initialize().getOrThrow(); runCurrent()
            assertEquals(5678L, f.state.positionMs)
            assertEquals(PlaybackPhase.PAUSED, f.state.phase)
            assertFalse(f.state.desiredPlaying)
            assertEquals(initial.queues, f.state.queues)
            assertTrue(f.resolver.requests.isEmpty() && f.engine.loads.isEmpty())
            f.command(PlaybackCommand.SelectContext(QueueContext.MUSIC))
            assertEquals(QueueContext.PODCAST, f.state.activeContext)
            assertEquals(QueueContext.MUSIC, f.state.viewedContext)
            assertTrue(f.engine.loads.isEmpty())
        } finally { f.close() }
    }

    @Test fun occurrencesSurviveShuffleMoveAndUndoWithoutChangingCurrentIdentity() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play(listOf(testTrack(), testTrack("second"), testTrack()))
            val occurrences = f.state.queue.entries.map { it.id }
            assertEquals(3, occurrences.toSet().size)
            f.command(PlaybackCommand.SetShuffle(true))
            val shuffled = f.state.queue
            f.command(PlaybackCommand.Move(QueueContext.MUSIC, shuffled.entries.last().id, 1))
            assertFalse(f.state.queue.shuffle)
            assertEquals(occurrences.toSet(), f.state.queue.entries.map { it.id }.toSet())
            assertEquals(shuffled.currentEntryId, f.state.currentEntryId)
            assertTrue(QueueContext.MUSIC in f.state.undoableQueueContexts)
            f.command(PlaybackCommand.UndoQueueEdit(QueueContext.MUSIC))
            assertEquals(shuffled.entries, f.state.queue.entries)
            assertTrue(f.state.queue.shuffle)
            assertFalse(QueueContext.MUSIC in f.state.undoableQueueContexts)
        } finally { f.close() }
    }

    @Test fun atomicShuffleReplacementRetainsOneUndoAndActualOutgoingCheckpoint() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play()
            f.event(EngineEvent.Position(f.state.generation, 25_000, 100_000))
            val oldEntries = f.state.queue.entries
            f.command(PlaybackCommand.Play(QueueContext.MUSIC, listOf(testTrack("third"), testTrack("fourth")), shuffle = true))
            assertTrue(f.state.queue.shuffle)
            f.command(PlaybackCommand.UndoQueueEdit(QueueContext.MUSIC))
            assertEquals(oldEntries, f.state.queue.entries)
            assertEquals(25_000L, f.state.positionMs)
            assertFalse(f.state.queue.shuffle)
            f.command(PlaybackCommand.SetShuffle(true))
            f.command(PlaybackCommand.Play(QueueContext.MUSIC, listOf(testTrack()), shuffle = false))
            assertFalse(f.state.queue.shuffle)
        } finally { f.close() }
    }

    @Test fun activatingPodcastAtomicallySavesLatestMusicPositionWhileBrowsingDoesNotSwitch() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play()
            val oldGeneration = f.state.generation
            f.event(EngineEvent.Position(oldGeneration, 33_000, 100_000))
            f.command(PlaybackCommand.SelectContext(QueueContext.PODCAST))
            assertEquals(oldGeneration, f.state.generation)
            assertTrue(f.state.isPlaying)
            f.command(PlaybackCommand.Play(QueueContext.PODCAST, listOf(testTrack("episode", MediaKind.EPISODE))))
            assertEquals(33_000L, f.store.state.value.queue(QueueContext.MUSIC).positionMs)
            assertEquals(33_000L, f.store.state.value.progress.getValue("first").positionMs)
            f.event(EngineEvent.Position(oldGeneration, 99_000, 100_000))
            assertEquals(0L, f.state.positionMs)
            assertEquals(QueueContext.PODCAST, f.state.activeContext)
        } finally { f.close() }
    }

    @Test fun publisherDurationNeverClampsResumeAndDifferentSelectedAudioResetsPosition() = runTest {
        val item = testTrack("episode", MediaKind.EPISODE).copy(durationMs = 1000)
        val binding = PlaybackBinding("saved", item.id, item.title, FirstResultKind.VIDEO, "BBBBBBBBBBB",
            "Selected recording", "https://www.youtube.com/watch?v=BBBBBBBBBBB", "BBBBBBBBBBB")
        val initial = AppSnapshot(media = mapOf(item.id to item), playbackBindings = mapOf(binding.id to binding),
            progress = mapOf(item.id to MediaProgress(80_000, 100_000, executionBindingId = binding.id)))
        val f = PlaybackFixture(this, initial)
        try {
            f.play(listOf(item))
            assertEquals(80_000L, f.engine.loads.single().startPositionMs)
            assertEquals(100_000L, f.state.durationMs)
            f.command(PlaybackCommand.Pause)
            f.resolver.selectedVideos[item.id] = "CCCCCCCCCCC"
            f.command(PlaybackCommand.Play(QueueContext.PODCAST, listOf(item)))
            assertEquals(0L, f.engine.loads.last().startPositionMs)
            assertEquals("CCCCCCCCCCC", f.state.executionBinding?.selectedVideoId)
        } finally { f.close() }
    }

    @Test fun differentRecordingWithUnknownDurationClearsOldDurationAcrossCheckpointAndRestart() = runTest {
        val item = testTrack("episode", MediaKind.EPISODE)
        val oldBinding = PlaybackBinding("saved", item.id, item.title, FirstResultKind.VIDEO, "BBBBBBBBBBB",
            "Earlier recording", "https://www.youtube.com/watch?v=BBBBBBBBBBB", "BBBBBBBBBBB")
        val initial = AppSnapshot(media = mapOf(item.id to item), playbackBindings = mapOf(oldBinding.id to oldBinding),
            progress = mapOf(item.id to MediaProgress(80_000, 100_000, executionBindingId = oldBinding.id)))
        val f = PlaybackFixture(this, initial)
        try {
            f.resolver.selectedVideos[item.id] = "CCCCCCCCCCC"
            f.resolver.durations[item.id] = null
            f.play(listOf(item))
            assertEquals(0L, f.engine.loads.single().startPositionMs)
            assertNull(f.state.durationMs)
            f.event(EngineEvent.Position(f.state.generation, 12_000))
            f.command(PlaybackCommand.FlushProgress)
            val progress = f.store.state.value.progress.getValue(item.id)
            assertEquals(12_000L, progress.positionMs)
            assertNull(progress.durationMs)
            assertEquals(f.state.executionBinding?.id, progress.executionBindingId)
            assertNotEquals(oldBinding.id, progress.executionBindingId)
        } finally { f.close() }
        val reopened = PlaybackFixture(this, f.persistence.snapshot)
        try {
            reopened.controller.initialize().getOrThrow(); runCurrent()
            assertEquals(12_000L, reopened.state.positionMs)
            assertNull(reopened.state.durationMs)
            assertEquals("CCCCCCCCCCC", reopened.state.executionBinding?.selectedVideoId)
            assertEquals(PlaybackPhase.PAUSED, reopened.state.phase)
        } finally { reopened.close() }
    }

    @Test fun staleSeekSamplesAndOccurrenceGuardsNeverSeekTheNewTrack() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play()
            f.engine.autoSeek = false
            val firstEntry = f.state.currentEntryId
            val firstGeneration = f.state.generation
            f.command(PlaybackCommand.Seek(50_000, firstEntry, firstGeneration))
            val seek = f.engine.seeks.single()
            f.event(EngineEvent.Position(firstGeneration, 1000, 100_000, seekSequence = 0))
            assertEquals(0L, f.state.positionMs)
            f.event(EngineEvent.SeekCompleted(firstGeneration, seek.sequence, 50_000))
            f.event(EngineEvent.Position(firstGeneration, 2000, 100_000, seekSequence = 0))
            assertEquals(50_000L, f.state.positionMs)
            f.command(PlaybackCommand.Next)
            val result = f.controller.dispatch(PlaybackCommand.Seek(42_000, firstEntry, firstGeneration))
            assertIs<PlaybackResult.Failure>(result)
            assertEquals(1, f.engine.seeks.size)
            f.engine.autoSeek = true
            f.event(EngineEvent.Position(f.state.generation, 30_000, 100_000))
            f.command(PlaybackCommand.SkipBy(-15_000, f.state.currentEntryId, f.state.generation))
            assertEquals(15_000L, f.state.positionMs)
        } finally { f.close() }
    }

    @Test fun seekBackFromEndedAcceptsConfirmationAndIgnoresLateEndBeforeSeekCompletes() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play(listOf(testTrack()))
            val generation = f.state.generation
            val entry = f.state.currentEntryId
            f.event(EngineEvent.Ended(generation))
            assertEquals(PlaybackPhase.ENDED, f.state.phase)
            f.engine.autoSeek = false
            f.command(PlaybackCommand.Seek(20_000, entry, generation))
            val seek = f.engine.seeks.single()
            f.event(EngineEvent.Ended(generation))
            assertEquals(20_000L, f.state.pendingSeekMs)
            assertEquals(PlaybackPhase.PAUSED, f.state.phase)
            f.event(EngineEvent.SeekCompleted(generation, seek.sequence, 20_000))
            assertNull(f.state.pendingSeekMs)
            assertEquals(20_000L, f.state.positionMs)
            f.command(PlaybackCommand.Resume)
            assertTrue(f.state.isPlaying)
            assertEquals(entry, f.state.currentEntryId)
            assertEquals(generation, f.state.generation)
            assertEquals(1, f.engine.loads.size)
            f.event(EngineEvent.Position(generation, 21_000, 100_000, seekSequence = seek.sequence))
            assertEquals(21_000L, f.state.positionMs)
        } finally { f.close() }
    }

    @Test fun stopAndErrorBarriersNeverInventAdditionalHistoryOccurrences() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play()
            f.event(EngineEvent.Position(f.state.generation, 22_000, 100_000))
            f.command(PlaybackCommand.Stop)
            f.command(PlaybackCommand.FlushProgress)
            assertEquals(1, f.store.state.value.history.size)
            assertEquals(22_000L, f.store.state.value.history.single().lastPositionMs)
            f.command(PlaybackCommand.Resume)
            f.event(EngineEvent.Error(f.state.generation, AppFailure(FailureCode.UNSUPPORTED_CODEC)))
            f.command(PlaybackCommand.FlushProgress)
            assertEquals(2, f.store.state.value.history.size)
            assertEquals(PlaybackPhase.ERROR, f.state.phase)
            assertNull(f.engine.active)
        } finally { f.close() }
    }

    @Test fun naturalPodcastEndMarksPlayedOnceAndEndTimerStopsBeforeAdvance() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play(listOf(testTrack("episode", MediaKind.EPISODE), testTrack("episode-2", MediaKind.EPISODE)))
            f.command(PlaybackCommand.SleepAtEndOfEpisode)
            val generation = f.state.generation
            val entry = f.state.currentEntryId
            f.event(EngineEvent.Ended(generation))
            assertEquals(entry, f.state.currentEntryId)
            assertFalse(f.state.desiredPlaying)
            assertTrue(f.store.state.value.progress.getValue("episode").played)
            f.command(PlaybackCommand.FlushProgress)
            f.event(EngineEvent.Ended(generation))
            assertEquals(1, f.store.state.value.history.size)
            assertTrue(f.store.state.value.history.single().endedNaturally)
            assertNull(f.state.sleepTimer)
        } finally { f.close() }
    }

    @Test fun interruptionResumeTokenSurvivesDuplicateBeginButNotUserPause() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play()
            val token = f.state.generation
            f.event(EngineEvent.Interruption(token, InterruptionKind.TRANSIENT_BEGAN))
            f.event(EngineEvent.Interruption(token, InterruptionKind.TRANSIENT_BEGAN))
            assertFalse(f.state.desiredPlaying)
            f.event(EngineEvent.Interruption(token, InterruptionKind.TRANSIENT_ENDED, mayResume = true))
            assertTrue(f.state.desiredPlaying && f.state.isPlaying)
            f.event(EngineEvent.Interruption(token, InterruptionKind.TRANSIENT_BEGAN))
            f.command(PlaybackCommand.Pause)
            f.event(EngineEvent.Interruption(token, InterruptionKind.TRANSIENT_ENDED, mayResume = true))
            assertFalse(f.state.desiredPlaying)
        } finally { f.close() }
    }

    @Test fun renewalIsBoundedReusesOneReceiptAndDoesNotAddHistoryForNetworkBarriers() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play(listOf(testTrack()))
            f.event(EngineEvent.Position(f.state.generation, 24_000, 100_000))
            val receipt = f.state.executionBinding
            repeat(2) { retry ->
                f.event(EngineEvent.Error(f.state.generation, AppFailure(FailureCode.PROVIDER_ACCESS_REQUIRED)))
                assertEquals(PlaybackPhase.RESOLVING, f.state.phase)
                advanceTimeBy((retry + 1) * 1000L); runCurrent()
                assertEquals(receipt, f.state.executionBinding)
                assertEquals(24_000L, f.engine.loads.last().startPositionMs)
                assertTrue(f.state.isPlaying)
            }
            f.event(EngineEvent.Error(f.state.generation, AppFailure(FailureCode.NETWORK_UNAVAILABLE)))
            assertEquals(PlaybackPhase.ERROR, f.state.phase)
            assertEquals(listOf(null, receipt!!.id, receipt.id), f.resolver.requests.map { it.bindingId })
            assertEquals(1, f.store.state.value.playbackBindings.size)
            assertEquals(1, f.store.state.value.history.size)
            assertNull(f.engine.active)
        } finally { f.close() }
    }

    @Test fun offlinePolicyStopsRemoteOwnedLoopbackAndCannotBeOvertakenByCheckpointRevision() = runTest {
        val f = PlaybackFixture(this)
        val resource = CountingPlaybackResource()
        f.resolver.resources["first"] = resource
        try {
            f.play(listOf(testTrack()))
            f.store.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow()
            f.command(PlaybackCommand.FlushProgress)
            assertFalse(f.state.desiredPlaying)
            assertEquals(FailureCode.OFFLINE_POLICY, f.state.failure?.code)
            assertTrue(resource.closed)
            assertNull(f.engine.active)
        } finally { f.close() }
    }

    @Test fun perShowRateDoesNotOverwriteGlobalAndDefaultChoiceRemovesCurrentOverride() = runTest {
        val show = PodcastShow("show", "Show", "https://example.com/feed")
        val item = testTrack("episode", MediaKind.EPISODE).copy(showId = show.id)
        val f = PlaybackFixture(this, AppSnapshot(shows = mapOf(show.id to show)))
        try {
            f.play(listOf(item))
            f.command(PlaybackCommand.SetRate(1.75f, forThisShow = true))
            assertEquals(1f, f.store.state.value.settings.speed)
            assertEquals(1.75f, f.store.state.value.settings.showSpeeds[show.id])
            f.command(PlaybackCommand.SetRate(1.25f))
            assertEquals(1.25f, f.store.state.value.settings.speed)
            assertFalse(show.id in f.store.state.value.settings.showSpeeds)
        } finally { f.close() }
    }

    @Test fun settingsDefaultSpeedUpdatesCurrentPodcastAcrossCheckpointAndPausedRestore() = runTest {
        val item = testTrack("episode", MediaKind.EPISODE)
        val f = PlaybackFixture(this)
        try {
            f.play(listOf(item))
            val generation = f.state.generation
            f.store.update { it.copy(settings = it.settings.copy(speed = 1.5f)) }.getOrThrow()
            f.command(PlaybackCommand.FlushProgress)
            assertEquals(1.5f, f.state.speed)
            assertEquals(1.5f to generation, f.engine.rates.last())
            assertEquals(1.5f, f.engine.currentSpeed)
            assertEquals(1.5f, f.store.state.value.queue(QueueContext.PODCAST).speed)
            assertEquals(1, f.engine.loads.size)
            assertTrue(f.state.isPlaying)
        } finally { f.close() }
        // Older saved queues may carry the rate from before a defaults edit. Settings remain
        // authoritative on paused restore too, without consulting a source or preparing audio.
        val saved = f.persistence.snapshot
        val reopened = PlaybackFixture(this, saved.copy(queues = saved.queues +
            (QueueContext.PODCAST to saved.queue(QueueContext.PODCAST).copy(speed = 1f))))
        try {
            reopened.controller.initialize().getOrThrow(); runCurrent()
            assertEquals(1.5f, reopened.state.speed)
            assertEquals(1.5f, reopened.state.queue.speed)
            assertEquals(PlaybackPhase.PAUSED, reopened.state.phase)
            assertFalse(reopened.state.desiredPlaying)
            assertTrue(reopened.engine.loads.isEmpty() && reopened.engine.rates.isEmpty())
            assertTrue(reopened.resolver.requests.isEmpty())
        } finally { reopened.close() }
    }

    @Test fun settingsDefaultSpeedPreservesCurrentShowOverrideAndNeverChangesMusicRate() = runTest {
        val show = PodcastShow("show", "Show", "https://example.com/feed")
        val episode = testTrack("episode", MediaKind.EPISODE).copy(showId = show.id)
        val f = PlaybackFixture(this, AppSnapshot(shows = mapOf(show.id to show),
            settings = AppSettings(showSpeeds = mapOf(show.id to 1.75f))))
        try {
            f.play(listOf(episode))
            val showRates = f.engine.rates.toList()
            f.store.update { it.copy(settings = it.settings.copy(speed = 1.5f)) }.getOrThrow(); runCurrent()
            assertEquals(1.75f, f.state.speed)
            assertEquals(1.75f, f.store.state.value.settings.showSpeeds[show.id])
            assertEquals(showRates, f.engine.rates)
            f.play(listOf(testTrack()))
            f.command(PlaybackCommand.SetRate(1.25f))
            val musicRates = f.engine.rates.toList()
            f.store.update { it.copy(settings = it.settings.copy(speed = 2f)) }.getOrThrow(); runCurrent()
            assertEquals(QueueContext.MUSIC, f.state.activeContext)
            assertEquals(1.25f, f.state.speed)
            assertEquals(1.25f, f.engine.currentSpeed)
            assertEquals(musicRates, f.engine.rates)
            assertEquals(2f, f.store.state.value.settings.speed)
            f.play(listOf(episode))
            assertEquals(1.75f, f.state.speed)
            f.store.update { it.copy(settings = it.settings.copy(showSpeeds = emptyMap())) }.getOrThrow(); runCurrent()
            assertEquals(2f, f.state.speed)
            assertEquals(2f, f.engine.currentSpeed)
        } finally { f.close() }
    }

    @Test fun failedNativeEventStreamStopsAudioInsteadOfLeavingFrozenPlayingState() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play()
            f.engine.eventsChannel.close(IllegalStateException("authored event failure")); runCurrent()
            assertEquals(PlaybackPhase.ERROR, f.state.phase)
            assertNull(f.engine.active)
            assertIs<PlaybackResult.Failure>(f.controller.dispatch(PlaybackCommand.Resume))
        } finally { f.close() }
    }

    @Test fun lateCancelledResolverResourceIsDisposedAndNeverPrepared() = runTest {
        val f = PlaybackFixture(this)
        val gate = CompletableDeferred<Unit>()
        val resource = CountingPlaybackResource()
        f.resolver.resources["first"] = resource
        f.resolver.behavior = { item, binding ->
            val result = f.resolver.result(item, binding)
            if (item.id == "first") withContext(NonCancellable) { gate.await() }
            result
        }
        try {
            f.command(PlaybackCommand.Play(QueueContext.MUSIC, listOf(testTrack())))
            f.command(PlaybackCommand.Play(QueueContext.MUSIC, listOf(testTrack("second"))))
            gate.complete(Unit); runCurrent()
            assertTrue(resource.closed)
            assertEquals(listOf("second"), f.engine.loads.map { it.source.mediaId })
            assertEquals("second", f.state.currentItem?.id)
        } finally { gate.complete(Unit); f.close() }
    }

    @Test fun rejectedResolvedIdentityDisposesResourceAndStopsTransport() = runTest {
        val f = PlaybackFixture(this)
        val resource = CountingPlaybackResource()
        f.resolver.resources["first"] = resource
        f.resolver.behavior = { item, binding -> f.resolver.result(item, binding).copy(mediaId = "wrong-original-id") }
        try {
            f.play(listOf(testTrack()))
            assertEquals(PlaybackPhase.ERROR, f.state.phase)
            assertTrue(resource.closed)
            assertTrue(f.engine.loads.isEmpty())
        } finally { f.close() }
    }

    @Test fun assetRemovalAcknowledgesNativeReleaseNotMerelyQueuedStop() = runTest {
        val f = PlaybackFixture(this)
        val stopped = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resource = CountingPlaybackResource()
        f.resolver.resources["first"] = resource
        try {
            f.play(listOf(testTrack()))
            f.engine.beforeStop = { stopped.complete(Unit); release.await() }
            val request = async { f.controller.releaseForAssetRemoval("first") }
            runCurrent()
            assertTrue(stopped.isCompleted)
            assertFalse(request.isCompleted)
            assertFalse(resource.closed)
            // The actor is not awaiting native release; unrelated browsing still responds.
            f.command(PlaybackCommand.SelectContext(QueueContext.PODCAST))
            release.complete(Unit); runCurrent()
            assertIs<PlaybackResult.Accepted>(request.await())
            assertTrue(resource.closed)
            assertNull(f.engine.active)
        } finally { release.complete(Unit); f.close() }
    }

    @Test fun retiredActiveAssetRemovalStillWaitsForReplacementNativeStop() = runTest {
        val f = PlaybackFixture(this)
        val stopStarted = CompletableDeferred<Unit>()
        val releaseStop = CompletableDeferred<Unit>()
        val resource = CountingPlaybackResource()
        f.resolver.resources["first"] = resource
        try {
            f.play(listOf(testTrack()))
            f.engine.beforeStop = { stopStarted.complete(Unit); releaseStop.await() }
            f.command(PlaybackCommand.Play(QueueContext.MUSIC, listOf(testTrack("second"))))
            assertTrue(stopStarted.isCompleted)
            assertEquals("second", f.state.currentItem?.id)
            assertEquals("first", f.engine.active?.source?.mediaId)
            val request = async { f.controller.releaseForAssetRemoval("first") }
            runCurrent()
            assertFalse(request.isCompleted)
            assertFalse(resource.closed)
            releaseStop.complete(Unit); runCurrent()
            assertIs<PlaybackResult.Accepted>(request.await())
            assertTrue(resource.closed)
            assertEquals("second", f.engine.active?.source?.mediaId)
            assertTrue(f.state.isPlaying) // Removing the retired file must not stop unrelated audio.
        } finally { releaseStop.complete(Unit); f.close() }
    }

    @Test fun retiredStagedAssetRemovalStillWaitsForQueuedNativeCancel() = runTest {
        val f = PlaybackFixture(this)
        val cancelStarted = CompletableDeferred<Unit>()
        val releaseCancel = CompletableDeferred<Unit>()
        val resource = CountingPlaybackResource()
        f.resolver.resources["second"] = resource
        try {
            f.play(crossfade = true)
            f.readyNext()
            f.engine.beforeCancelNext = { cancelStarted.complete(Unit); releaseCancel.await() }
            f.command(PlaybackCommand.ClearUpcoming(QueueContext.MUSIC))
            assertTrue(cancelStarted.isCompleted)
            assertNull(f.state.crossfade)
            assertEquals("second", f.engine.staged?.source?.mediaId)
            val request = async { f.controller.releaseForAssetRemoval("second") }
            runCurrent()
            assertFalse(request.isCompleted)
            assertFalse(resource.closed)
            releaseCancel.complete(Unit); runCurrent()
            assertIs<PlaybackResult.Accepted>(request.await())
            assertTrue(resource.closed)
            assertEquals("first", f.engine.active?.source?.mediaId)
            assertTrue(f.state.isPlaying)
        } finally { releaseCancel.complete(Unit); f.close() }
    }

    @Test fun retiredResolverAndItsLateUnhandedResourceCloseBothPrecedeRemovalAck() = runTest {
        val f = PlaybackFixture(this)
        val resolveGate = CompletableDeferred<Unit>()
        val closeStarted = CompletableDeferred<Unit>()
        val closeGate = CompletableDeferred<Unit>()
        var closed = false
        val resource = object : PlaybackResource {
            override suspend fun close() { closeStarted.complete(Unit); closeGate.await(); closed = true }
        }
        f.resolver.resources["first"] = resource
        f.resolver.behavior = { item, binding ->
            val source = f.resolver.result(item, binding)
            if (item.id == "first") withContext(NonCancellable) { resolveGate.await() }
            source
        }
        try {
            f.command(PlaybackCommand.Play(QueueContext.MUSIC, listOf(testTrack())))
            f.command(PlaybackCommand.Play(QueueContext.MUSIC, listOf(testTrack("second"))))
            val request = async { f.controller.releaseForAssetRemoval("first") }
            runCurrent()
            assertFalse(request.isCompleted)
            resolveGate.complete(Unit); runCurrent()
            assertTrue(closeStarted.isCompleted)
            assertFalse(request.isCompleted)
            closeGate.complete(Unit); runCurrent()
            assertIs<PlaybackResult.Accepted>(request.await())
            assertTrue(closed)
            assertEquals(listOf("second"), f.engine.loads.map { it.source.mediaId })
        } finally { resolveGate.complete(Unit); closeGate.complete(Unit); f.close() }
    }

    @Test fun newGenerationDuringNativeReleaseCannotAuthorizePhysicalDeletion() = runTest {
        val f = PlaybackFixture(this)
        val releaseStop = CompletableDeferred<Unit>()
        try {
            f.play(listOf(testTrack()))
            f.engine.beforeStop = { releaseStop.await() }
            val request = async { f.controller.releaseForAssetRemoval("first") }
            runCurrent()
            assertFalse(request.isCompleted)
            f.command(PlaybackCommand.Play(QueueContext.MUSIC, listOf(testTrack("second"))))
            releaseStop.complete(Unit); runCurrent()
            assertIs<PlaybackResult.Failure>(request.await())
            assertEquals("second", f.engine.active?.source?.mediaId)
            assertIs<PlaybackResult.Accepted>(f.controller.releaseForAssetRemoval("first"))
        } finally { releaseStop.complete(Unit); f.close() }
    }

    @Test fun failedUnhandedResourceCloseVetoesRetiredAssetRemoval() = runTest {
        val f = PlaybackFixture(this)
        val resolveGate = CompletableDeferred<Unit>()
        var closeCalls = 0
        f.resolver.resources["first"] = object : PlaybackResource {
            override suspend fun close() { closeCalls++; error("authored cleanup failure") }
        }
        f.resolver.behavior = { item, binding ->
            val source = f.resolver.result(item, binding)
            if (item.id == "first") withContext(NonCancellable) { resolveGate.await() }
            source
        }
        try {
            f.command(PlaybackCommand.Play(QueueContext.MUSIC, listOf(testTrack())))
            f.command(PlaybackCommand.Play(QueueContext.MUSIC, listOf(testTrack("second"))))
            val request = async { f.controller.releaseForAssetRemoval("first") }
            runCurrent()
            resolveGate.complete(Unit); runCurrent()
            assertIs<PlaybackResult.Failure>(request.await())
            assertEquals(1, closeCalls)
            // A later successful native stop does not prove that a failed bridge close succeeded.
            assertIs<PlaybackResult.Failure>(f.controller.releaseForAssetRemoval("first"))
            val failure = assertFailsWith<VeyraException> { f.controller.close() }
            assertEquals("Playback resource cleanup failed", failure.failure.detail)
            assertSame(failure, assertFailsWith<VeyraException> { f.controller.close() })
            assertEquals(1, closeCalls)
        } finally {
            resolveGate.complete(Unit)
            try { assertFailsWith<VeyraException> { f.controller.close() } }
            finally { f.store.close() }
        }
    }

    @Test fun failedNativeStopVetoesDeletionWithoutInfiniteAutomaticStopRetries() = runTest {
        val f = PlaybackFixture(this)
        val sourceReady = CompletableDeferred<Unit>()
        val resource = CountingPlaybackResource()
        var attempts = 0
        var failedGeneration = -1L
        try {
            f.play(listOf(testTrack()))
            f.resolver.resources["second"] = resource
            f.resolver.behavior = { item, binding ->
                f.resolver.result(item, binding).also { if (item.id == "second") sourceReady.complete(Unit) }
            }
            f.engine.beforeStop = {
                attempts++
                failedGeneration = f.state.generation
                sourceReady.await() // A real unhanded source exists before native release fails.
                error("authored decoder release failure")
            }
            f.command(PlaybackCommand.Play(QueueContext.MUSIC, listOf(testTrack("second"))))
            assertEquals(1, attempts)
            assertEquals(PlaybackPhase.ERROR, f.state.phase)
            assertTrue(f.state.generation > failedGeneration)
            assertEquals(1, resource.closeCalls)
            assertEquals(1, f.engine.loads.size)
            f.event(EngineEvent.Ready(failedGeneration, 100_000, true))
            f.event(EngineEvent.Position(failedGeneration, 40_000, 100_000))
            f.event(EngineEvent.Ended(failedGeneration))
            assertEquals(PlaybackPhase.ERROR, f.state.phase)
            assertEquals(0L, f.state.positionMs)
            assertEquals("second", f.state.currentItem?.id)
            assertIs<PlaybackResult.Failure>(f.controller.releaseForAssetRemoval("first"))
            assertIs<PlaybackResult.Failure>(f.controller.releaseForAssetRemoval("first"))
            assertEquals(1, attempts)
            assertEquals("first", f.engine.active?.source?.mediaId)
            f.engine.beforeStop = null
            f.command(PlaybackCommand.Stop)
            assertIs<PlaybackResult.Accepted>(f.controller.releaseForAssetRemoval("first"))
            assertNull(f.engine.active)
        } finally { sourceReady.complete(Unit); f.engine.beforeStop = null; f.close() }
    }
}
