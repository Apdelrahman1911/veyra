package dev.veyra.app

import dev.kmpyt.YouTubeClient
import dev.kmpyt.extraction.ResearchTransport
import dev.veyra.data.LibraryStore
import dev.veyra.data.createPlatformLibraryStore
import dev.veyra.data.getOrThrow
import dev.veyra.model.AppFailure
import dev.veyra.model.AppSettings
import dev.veyra.model.AppSnapshot
import dev.veyra.model.DownloadRecord
import dev.veyra.model.DownloadStatus
import dev.veyra.model.FailureCode
import dev.veyra.model.FirstResultKind
import dev.veyra.model.LanguagePreference
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.MediaProgress
import dev.veyra.model.PlaybackBinding
import dev.veyra.model.Playlist
import dev.veyra.model.PlaylistEntry
import dev.veyra.model.PodcastShow
import dev.veyra.model.QueueContext
import dev.veyra.model.QueueEntry
import dev.veyra.model.QueueSnapshot
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.model.ThemeMode
import dev.veyra.model.VeyraException
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.AssetValidator
import dev.veyra.playback.AudioEngine
import dev.veyra.playback.Clock
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.execution.AcquisitionPlaybackSource
import dev.veyra.playback.EngineEvent
import dev.veyra.playback.EnginePhase
import dev.veyra.playback.NativeReaderDrainResult
import dev.veyra.playback.PlaybackResource
import dev.veyra.playback.PlaybackSelection
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.execution.KmpFirstResult
import dev.veyra.source.execution.KmpMediaGateway
import dev.veyra.source.network.SafeHttp
import dev.veyra.source.network.SafeHttpBytes
import dev.veyra.source.network.SafeHttpRequest
import dev.veyra.source.network.SafeHttpResponse
import dev.veyra.source.youtube.YouTubeMediaSource
import dev.veyra.source.youtube.YouTubeTransferProgress
import dev.veyra.source.youtube.YouTubeTransferResult
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Real app composition/controller/Room, authored catalog, simulated engine and execution gateway.
 * No sockets, real source acquisition, media bytes, native decoding, or physical audio are used.
 * The fake gateway deliberately preserves the production full-title/first-receipt interface.
 */
internal class CoreShellTestFixture private constructor(
    val directory: File,
    val store: LibraryStore,
    private val fixtureLanguage: LanguagePreference = LanguagePreference.ENGLISH,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = CoreShellFixtureEngine()
    val gateway = CoreShellFixtureGateway()
    val runtime = MutableStateFlow(MediaRuntimeStatus())
    val runtimePrepareCalls = AtomicInteger()
    @Volatile var prepareRuntime: suspend () -> Unit = {}
    val unexpectedNetworkCalls = AtomicInteger()
    val externalPages = CopyOnWriteArrayList<String>()
    private var closed = false

    private fun rejectNetwork(): Nothing {
        unexpectedNetworkCalls.incrementAndGet()
        throw VeyraException(AppFailure(FailureCode.OFFLINE_POLICY))
    }

    private val http = object : SafeHttp {
        override suspend fun get(request: SafeHttpRequest, maxBytes: Int): SafeHttpBytes = rejectNetwork()
        override suspend fun <T> stream(request: SafeHttpRequest, consume: suspend (SafeHttpResponse, ByteReadChannel) -> T): T = rejectNetwork()
        override suspend fun validateUrl(url: String): String = rejectNetwork()
        override fun close() = Unit
    }
    private val platform = object : PlatformServices {
        override val dataDirectory = directory.path
        override val store = this@CoreShellTestFixture.store
        override val engine = this@CoreShellTestFixture.engine
        override val validator = AssetValidator { AssetValidation.Invalid(AppFailure(FailureCode.CORRUPT_MEDIA)) }
        override val youtube = YouTubeMediaSource(YouTubeClient(ResearchTransport { rejectNetwork() }))
        override val systemLanguage = fixtureLanguage
        override val mediaRuntimeStatus = runtime
        override suspend fun prepareMediaRuntime() {
            runtimePrepareCalls.incrementAndGet()
            prepareRuntime()
        }
        override suspend fun openExternalUrl(url: String) { externalPages += url }
    }
    val container = AppContainer(platform, scope, http, gateway, object : Clock {
        override fun epochMs() = 1_800_000_000_000L
        override fun monotonicMs() = 10_000L
    })

    override fun close() {
        if (closed) return
        closed = true
        try { runBlocking { withTimeout(20_000L) { container.close() } } }
        finally { scope.cancel() }
        // Keep the small, task-owned Room fixture on failure for inspection; no broad cleanup.
    }

    companion object {
        val music = MediaItem("ui-fixture-music", "UI fixture — البحر الهادئ", MediaKind.MUSIC,
            SourceRef(SourceProvider.COMMONS, "authored-original-music"), creator = "Authored UI catalog",
            durationMs = 600_000L, description = "Authored metadata, not a live catalog or recording.")
        val second = MediaItem("ui-fixture-second", "UI fixture — Second complete track title", MediaKind.MUSIC,
            SourceRef(SourceProvider.COMMONS, "authored-original-second"), creator = "Authored UI catalog", durationMs = 500_000L)
        val episode = MediaItem("ui-fixture-episode", "UI fixture — حلقة تجريبية كاملة", MediaKind.EPISODE,
            SourceRef(SourceProvider.RSS, "authored-episode-guid", feedUrl = "https://ui-fixtures.invalid/feed.xml",
                episodeGuid = "authored-episode-guid"), creator = "Authored UI show", durationMs = 900_000L, showId = "ui-fixture-show")
        val show = PodcastShow("ui-fixture-show", "UI fixture podcast", "https://ui-fixtures.invalid/feed.xml",
            episodeIds = listOf(episode.id), lastRefreshEpochMs = 1_000L)
        val playlist = Playlist("ui-fixture-playlist", "UI fixture — قائمة مرجعية", listOf(
            PlaylistEntry("playlist-first", music.id), PlaylistEntry("playlist-copy", music.id)))
        val musicReceipt = PlaybackBinding("ui-fixture-restored-receipt", music.id, music.title,
            FirstResultKind.VIDEO, "AAAAAAAAAAA", "Authored resolved music — not live audio",
            "https://www.youtube.com/watch?v=AAAAAAAAAAA", "AAAAAAAAAAA", resolvedEpochMs = 1_000L)

        fun snapshot(settings: AppSettings = AppSettings(language = LanguagePreference.ENGLISH, theme = ThemeMode.LIGHT),
            navigation: AppNavigation = AppNavigation()): AppSnapshot = AppSnapshot(
            media = listOf(music, second, episode).associateBy { it.id },
            shows = mapOf(show.id to show),
            favoriteIds = setOf(music.id, second.id),
            playlists = listOf(playlist),
            followedShowIds = setOf(show.id),
            progress = mapOf(music.id to MediaProgress(17_000L, 180_000L, executionBindingId = musicReceipt.id)),
            playbackBindings = mapOf(musicReceipt.id to musicReceipt),
            queues = mapOf(
                QueueContext.MUSIC to QueueSnapshot(listOf(QueueEntry("music-now", music.id),
                    QueueEntry("music-next", second.id), QueueEntry("music-copy", music.id)), "music-now", 17_000L,
                    shuffle = true, originalOrder = listOf("music-now", "music-next", "music-copy")),
                QueueContext.PODCAST to QueueSnapshot(listOf(QueueEntry("episode-now", episode.id)), "episode-now", 25_000L),
            ),
            downloads = mapOf("ui-fixture-paused-download" to DownloadRecord("ui-fixture-paused-download", music.id,
                DownloadStatus.PAUSED, userPaused = true)),
            settings = settings,
            navigation = navigation.persisted(),
        )

        suspend fun create(initial: AppSnapshot = snapshot()): CoreShellTestFixture {
            val parent = coreShellOwnedDirectory("composeApp/build/test-work/core-shell")
            val directory = Files.createTempDirectory(parent.toPath(), "fixture-").toFile()
            val store = createPlatformLibraryStore(File(directory, "library").path)
            var fixture: CoreShellTestFixture? = null
            try {
                store.update { initial }.getOrThrow()
                fixture = CoreShellTestFixture(directory, store)
                fixture.container.initialize()
                return fixture
            } catch (failure: Throwable) {
                if (fixture != null) fixture.close() else store.close()
                throw failure
            }
        }

        /** Attach a caller-owned Room store without concealing a failed startup behind a fake. */
        fun attachUninitialized(directory: File, store: LibraryStore,
            systemLanguage: LanguagePreference = LanguagePreference.ENGLISH): CoreShellTestFixture {
            val owned = directory.canonicalFile
            val root = coreShellProjectRoot()
            require(owned.toPath().startsWith(root.toPath()) && owned != root) {
                "Refusing an escaping core-shell fixture directory"
            }
            require(owned.isDirectory) { "The task-owned fixture directory must already exist" }
            return CoreShellTestFixture(owned, store, systemLanguage)
        }
    }
}

/** Resolve canonical containment before mkdir; never use Java's global default temp directory. */
internal fun coreShellOwnedDirectory(relative: String): File {
    val root = coreShellProjectRoot()
    val directory = File(root, relative).canonicalFile
    require(directory.toPath().startsWith(root.toPath())) { "Refusing an escaping core-shell fixture path" }
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create the owned core-shell fixture directory" }
    return directory
}

private fun coreShellProjectRoot(): File = System.getenv("VEYRA_PROJECT_ROOT")?.let(::File)?.canonicalFile
    ?: generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "AGENTS.md").isFile }
    ?: error("Core-shell fixtures require the Veyra repository context")

internal class CoreShellFixtureGateway : KmpMediaGateway {
    val queries = CopyOnWriteArrayList<String>()
    val resolutions = CopyOnWriteArrayList<PlaybackBinding>()
    val downloadCalls = AtomicInteger()

    override suspend fun firstResult(completeTitle: String): KmpFirstResult {
        queries += completeTitle
        val id = when (completeTitle) {
            CoreShellTestFixture.music.title -> "AAAAAAAAAAA"
            CoreShellTestFixture.second.title -> "BBBBBBBBBBB"
            CoreShellTestFixture.episode.title -> "CCCCCCCCCCC"
            else -> error("Unexpected authored UI fixture title")
        }
        return KmpFirstResult(completeTitle, FirstResultKind.VIDEO, id, "Authored first-result recording — not live audio",
            "https://www.youtube.com/watch?v=$id")
    }

    override suspend fun resolve(binding: PlaybackBinding, capabilities: EngineCapabilities, purpose: ResolvePurpose): ResolvedPlayable {
        resolutions += binding
        // Reserved example.org metadata locator passes lexical policy. The fake never opens it.
        return ResolvedPlayable(binding.mediaId, "https://example.org/veyra-authored-ui-fixture.m4a",
            mimeType = "audio/mp4", codecs = "aac", durationMs = 180_000L, seekableHint = true, executionBinding = binding)
    }

    override suspend fun download(binding: PlaybackBinding, destinationPath: String, capabilities: EngineCapabilities,
        representationId: String?, onProgress: suspend (YouTubeTransferProgress) -> Unit): YouTubeTransferResult {
        downloadCalls.incrementAndGet()
        throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE))
    }
}

/** Token-checking intent/event seam. It never loads a native player or opens a media locator. */
internal class CoreShellFixtureEngine : AudioEngine {
    data class Load(val source: ResolvedPlayable, val positionMs: Long, val generation: Long)
    data class Seek(val positionMs: Long, val sequence: Long, val generation: Long)
    override val capabilities = EngineCapabilities()
    private val channel = Channel<EngineEvent>(Channel.UNLIMITED)
    override val events = channel.receiveAsFlow()
    val loads = CopyOnWriteArrayList<Load>()
    val plays = CopyOnWriteArrayList<Long>()
    val pauses = CopyOnWriteArrayList<Long>()
    val seeks = CopyOnWriteArrayList<Seek>()
    @Volatile private var activeGeneration = 0L
    private var source: ResolvedPlayable? = null
    private var ownedTyped: PlaybackResource? = null
    private var speed = 1f
    val typedPreparations = CopyOnWriteArrayList<Triple<PlaybackSelection, Long, Long>>()

    override suspend fun prepare(source: ResolvedPlayable, startPositionMs: Long, generation: Long) {
        if (generation < activeGeneration) { source.resource?.close(); return }
        this.source?.resource?.close()
        this.source = source
        activeGeneration = generation
        loads += Load(source, startPositionMs, generation)
        channel.send(EngineEvent.Ready(generation, source.durationMs, true))
    }

    override suspend fun prepare(source: AcquisitionPlaybackSource, selection: PlaybackSelection,
        startPositionMs: Long, generation: Long) {
        if (generation < activeGeneration) { source.resource?.close(); return }
        this.source?.resource?.close()
        this.source = null
        ownedTyped = source.resource
        activeGeneration = generation
        typedPreparations += Triple(selection, startPositionMs, generation)
        channel.send(EngineEvent.Ready(generation, source.durationMs, true))
    }

    override suspend fun awaitRetiredReaders(expectedGeneration: Long): NativeReaderDrainResult =
        NativeReaderDrainResult.Drained(expectedGeneration)

    override suspend fun play(generation: Long) {
        if (generation != activeGeneration || source == null) return
        plays += generation
        channel.send(EngineEvent.StateChanged(generation, EnginePhase.PLAYING, true, speed))
    }

    override suspend fun pause(generation: Long) {
        if (generation != activeGeneration) return
        pauses += generation
        channel.send(EngineEvent.StateChanged(generation, EnginePhase.PAUSED, false, speed))
    }

    override suspend fun seek(positionMs: Long, seekSequence: Long, generation: Long) {
        if (generation != activeGeneration || source == null) return
        seeks += Seek(positionMs, seekSequence, generation)
        channel.send(EngineEvent.SeekCompleted(generation, seekSequence, positionMs))
    }

    override suspend fun setRate(speed: Float, generation: Long) {
        if (generation == activeGeneration) this.speed = speed
    }

    override suspend fun stop(generation: Long) {
        if (generation < activeGeneration) return
        source?.resource?.close()
        source = null
        activeGeneration = generation
    }

    override suspend fun close() { source?.resource?.close(); ownedTyped?.close(); ownedTyped = null; source = null; channel.close() }
}
