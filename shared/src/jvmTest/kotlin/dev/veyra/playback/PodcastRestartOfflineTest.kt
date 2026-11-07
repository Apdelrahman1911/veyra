package dev.veyra.playback

import dev.veyra.downloads.DownloadMaterializationEvidence
import dev.veyra.data.createPlatformLibraryStore
import dev.veyra.data.getOrThrow
import dev.veyra.downloads.DownloadFiles
import dev.veyra.model.*
import dev.veyra.source.execution.AppSourceResolver
import dev.veyra.source.execution.ExecutionTestGateway
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.*

/** Real Room reopen + production local resolver/SHA checks. Bytes are authored NON-AUDIO;
 * validator and engine are explicit fakes. No real acquisition/native/audible proof is claimed. */
class PodcastRestartOfflineTest {
    @Test fun offlineRestartKeepsBoundAssetAndZeroIntentAcrossRoomCloseAndReopen(): Unit = runBlocking {
        withTimeout(30_000) {
            val directory = workDirectory()
            val databaseDirectory = directory.resolve("library").toString()
            var store = createPlatformLibraryStore(databaseDirectory)
            var controller: PlaybackController? = null
            try {
                val episode = testTrack("offline-episode", MediaKind.EPISODE)
                val binding = PlaybackBinding("offline-binding", episode.id, episode.title,
                    FirstResultKind.VIDEO, "BBBBBBBBBBB", "Authored downloaded recording",
                    "https://www.youtube.com/watch?v=BBBBBBBBBBB", "BBBBBBBBBBB", resolvedEpochMs = 1_000)
                val files = DownloadFiles(directory.resolve("media").toString())
                files.initialize()
                val path = files.finalPath(episode.id, 1, "audio/mp4")
                val bytes = "Authored offline-restart integrity bytes. NOT AUDIO.".encodeToByteArray()
                Files.createDirectories(Path.of(path.parent!!.toString()))
                Files.write(Path.of(path.toString()), bytes)
                val asset = OfflineAsset("asset", episode.id, path.toString(), bytes.size.toLong(),
                    mimeType = "audio/mp4", codecs = "aac", sha256 = files.sha256(path),
                    representationId = "authored-representation", validatorGeneration = 1,
                    completedEpochMs = 2_000, executionBindingId = binding.id, measuredDurationMs = 100_000)
                val download = DownloadRecord("download", episode.id, DownloadStatus.COMPLETE,
                    bytesDownloaded = asset.sizeBytes, totalBytes = asset.sizeBytes, asset = asset,
                    representationId = asset.representationId, validatorGeneration = 1,
                    executionBindingId = binding.id)
                val historical = PlaybackHistoryEntry("previous-session", episode.id, 1_000, 45_000,
                    executionBindingId = binding.id)
                store.update { old -> old.copy(
                    media = mapOf(episode.id to episode),
                    playbackBindings = mapOf(binding.id to binding),
                    progress = mapOf(episode.id to MediaProgress(45_000, 100_000, played = true,
                        executionBindingId = binding.id)),
                    downloads = mapOf(download.id to download), history = listOf(historical),
                    savedEpisodeIds = setOf(episode.id), settings = old.settings.copy(offlineOnly = true),
                    activeContext = QueueContext.PODCAST,
                    queues = old.queues + (QueueContext.PODCAST to QueueSnapshot(
                        listOf(QueueEntry("saved-occurrence", episode.id)), "saved-occurrence", 45_000)),
                ) }.getOrThrow()

                val gateway = ExecutionTestGateway().apply {
                    first = { error("Offline restart must not search") }
                    execute = { _, _, _ -> error("Offline restart must not resolve remote audio") }
                }
                var validations = 0
                val validator = AssetValidator { checked ->
                    assertEquals(asset, checked)
                    validations++
                    AssetValidation.Valid(100_000)
                }
                // One caller event-loop serializes the explicit fake engine with assertions;
                // Room and production hash validation retain their real IO dispatchers.
                val dispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
                val firstEngine = PlaybackTestEngine().apply { autoSeek = false }
                val first = PlaybackController(store, firstEngine,
                    AppSourceResolver(store, files, validator, gateway, materializationEvidence = DownloadMaterializationEvidence(store.transactionOwner)), this, dispatcher = dispatcher)
                controller = first
                first.initialize().getOrThrow()
                assertEquals(PlaybackPhase.PAUSED, first.state.value.phase)
                assertEquals(45_000L, first.state.value.positionMs)
                assertTrue(firstEngine.loads.isEmpty())
                first.dispatch(PlaybackCommand.Play(QueueContext.PODCAST, listOf(episode), startFromBeginning = true)).getOrThrow()
                first.awaitPlaying()

                val load = firstEngine.loads.single()
                assertEquals(0L, load.startPositionMs)
                assertTrue(load.source.isLocal)
                assertEquals(asset.uri, load.source.uri)
                assertEquals(asset.id, load.source.assetId)
                assertEquals(binding, load.source.executionBinding)
                assertEquals(episode, load.source.item)
                assertTrue(firstEngine.seeks.isEmpty())
                assertEquals(1, validations)
                assertFalse(store.state.value.progress.getValue(episode.id).played)
                assertEquals(download, store.state.value.downloads[download.id])
                assertEquals(listOf(historical), store.state.value.history)

                firstEngine.emit(EngineEvent.Position(first.state.value.generation, 12_000, 100_000))
                first.state.first { it.positionMs == 12_000L }
                first.dispatch(PlaybackCommand.FlushProgress).getOrThrow()
                first.close(); controller = null
                store.close()
                store = createPlatformLibraryStore(databaseDirectory)
                store.initialize().getOrThrow()
                assertEquals(12_000L, store.state.value.queue(QueueContext.PODCAST).positionMs)
                assertEquals(binding.id, store.state.value.progress.getValue(episode.id).executionBindingId)
                assertEquals(download, store.state.value.downloads[download.id])
                assertEquals(setOf(episode.id), store.state.value.savedEpisodeIds)
                assertEquals(historical, store.state.value.history.first())
                assertEquals(12_000L, store.state.value.history.last().lastPositionMs)

                val secondEngine = PlaybackTestEngine().apply { autoSeek = false }
                val second = PlaybackController(store, secondEngine,
                    AppSourceResolver(store, files, validator, gateway, materializationEvidence = DownloadMaterializationEvidence(store.transactionOwner)), this, dispatcher = dispatcher)
                controller = second
                second.initialize().getOrThrow()
                assertEquals(PlaybackPhase.PAUSED, second.state.value.phase)
                assertEquals(12_000L, second.state.value.positionMs)
                assertFalse(second.state.value.desiredPlaying)
                assertTrue(secondEngine.loads.isEmpty())
                second.dispatch(PlaybackCommand.Play(QueueContext.PODCAST, listOf(episode), startFromBeginning = true)).getOrThrow()
                second.awaitPlaying()
                second.dispatch(PlaybackCommand.FlushProgress).getOrThrow()

                assertEquals(0L, secondEngine.loads.single().startPositionMs)
                assertEquals(binding, second.state.value.executionBinding)
                assertEquals(2, validations, "A reopened asset must be validated again")
                assertEquals(0L, store.state.value.progress.getValue(episode.id).positionMs)
                assertEquals(binding.id, store.state.value.progress.getValue(episode.id).executionBindingId)
                assertFalse(store.state.value.progress.getValue(episode.id).played)
                assertEquals(download, store.state.value.downloads[download.id])
                assertEquals(mapOf(binding.id to binding), store.state.value.playbackBindings)
                assertEquals(listOf(45_000L, 12_000L, 0L), store.state.value.history.map { it.lastPositionMs })
                assertTrue(secondEngine.seeks.isEmpty())
                assertTrue(gateway.queries.isEmpty())
                assertTrue(gateway.resolutions.isEmpty())
                assertEquals(0, gateway.downloads)
                Files.newInputStream(directory.resolve("library/veyra.db")).use {
                    assertEquals("SQLite format 3\u0000", it.readNBytes(16).decodeToString())
                }
            } finally {
                try { controller?.close() } finally { store.close() }
            }
        }
    }

    private suspend fun PlaybackController.awaitPlaying() {
        val result = state.first { it.phase == PlaybackPhase.PLAYING || it.phase == PlaybackPhase.ERROR }
        assertEquals(PlaybackPhase.PLAYING, result.phase, "Authored native seam must be playing: ${result.failure?.code}")
    }

    private fun workDirectory(): Path {
        // Derive repository identity from the actual test working directory, not an arbitrary
        // environment path. Reject a mismatching declared root before making any directory.
        val root = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts"), NOFOLLOW_LINKS) &&
                Files.isRegularFile(it.resolve("AGENTS.md"), NOFOLLOW_LINKS) &&
                Files.isRegularFile(it.resolve("shared/build.gradle.kts"), NOFOLLOW_LINKS) }
            .toRealPath()
        System.getenv("VEYRA_PROJECT_ROOT")?.let { require(Path.of(it).toRealPath() == root) }
        var parent = root
        for (segment in listOf(".work", "architecture", "restart-offline-tests")) {
            parent = parent.resolve(segment)
            require(!Files.isSymbolicLink(parent)) { "Fixture parent must not be a symbolic link" }
            if (Files.exists(parent, NOFOLLOW_LINKS)) {
                require(Files.isDirectory(parent, NOFOLLOW_LINKS))
                require(parent.toRealPath().startsWith(root))
            } else {
                // Every preceding parent was checked before this single-component mkdir.
                Files.createDirectory(parent)
                require(parent.toRealPath().startsWith(root))
            }
        }
        return Files.createTempDirectory(parent, "room-")
    }
}
