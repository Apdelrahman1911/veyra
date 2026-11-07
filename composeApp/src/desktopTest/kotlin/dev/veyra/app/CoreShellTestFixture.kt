package dev.veyra.app

import dev.kmpyt.YouTubeClient
import dev.kmpyt.extraction.ResearchTransport
import dev.veyra.data.LibraryStore
import dev.veyra.data.createPlatformLibraryStore
import dev.veyra.data.getOrThrow
import dev.veyra.model.AppFailure
import dev.veyra.model.AppSettings
import dev.veyra.model.AppSnapshot
import dev.veyra.model.FailureCode
import dev.veyra.model.LanguagePreference
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.Playlist
import dev.veyra.model.PlaylistEntry
import dev.veyra.model.PodcastShow
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.model.ThemeMode
import dev.veyra.model.VeyraException
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.AssetValidator
import dev.veyra.playback.AudioEngine
import dev.veyra.playback.Clock
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.EngineEvent
import dev.veyra.playback.EnginePhase
import dev.veyra.playback.NativePlaybackSource
import dev.veyra.playback.NativeReaderDrainResult
import dev.veyra.playback.PlaybackResource
import dev.veyra.playback.PlaybackSelection
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.source.execution.AcquisitionPlaybackSource
import dev.veyra.source.network.SafeHttp
import dev.veyra.source.network.SafeHttpBytes
import dev.veyra.source.network.SafeHttpRequest
import dev.veyra.source.network.SafeHttpResponse
import dev.veyra.source.youtube.YouTubeMediaSource
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private val coreShellClock = object : Clock {
    override fun epochMs() = 1_800_000_000_000L
    override fun monotonicMs() = 10_000L
}

/**
 * Real app composition/controller/Room, authored catalog, simulated engine and execution gateway.
 * No sockets, real source acquisition, media bytes, native decoding, or physical audio are used.
 * Catalogue/show/settings/navigation and saved metadata enter through their production commands;
 * execution/download/progress state is created only by the canonical acquisition/physical commands.
 */
internal class CoreShellTestFixture private constructor(
    val directory: File,
    val store: LibraryStore,
    private val fixtureLanguage: LanguagePreference = LanguagePreference.ENGLISH,
    val typedTitles: CoreShellTypedTitles = CoreShellTypedTitles(),
    val seed: CoreShellSeedState? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = CoreShellFixtureEngine()
    val gateway = CoreShellFixtureGateway(typedTitles)
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
    val container = AppContainer(platform, scope, http, gateway, coreShellClock)

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
        val defaultFavoriteIds = setOf(music.id, second.id)

        /**
         * Catalogue/show/settings/navigation only. Execution, progress, download and saved-lineage
         * fields stay empty here because those are created by their canonical production commands.
         */
        fun snapshot(settings: AppSettings = AppSettings(language = LanguagePreference.ENGLISH, theme = ThemeMode.LIGHT),
            navigation: AppNavigation = AppNavigation()): AppSnapshot = AppSnapshot(
            media = listOf(music, second, episode).associateBy { it.id },
            shows = mapOf(show.id to show),
            followedShowIds = setOf(show.id),
            settings = settings,
            navigation = navigation.persisted(),
        )

        suspend fun create(initial: AppSnapshot = snapshot(),
            typedTitles: CoreShellTypedTitles = CoreShellTypedTitles()): CoreShellTestFixture {
            require(initial.downloads.isEmpty() && initial.downloadAttempts.isEmpty() &&
                initial.downloadFinalizations.isEmpty() && initial.playbackBindings.isEmpty() &&
                initial.progress.isEmpty() && initial.history.isEmpty() &&
                initial.queues.values.all { it.entries.isEmpty() && it.currentEntryId == null && it.originalOrder.isEmpty() }) {
                "CoreShell fixtures must create execution/download/progress state through canonical commands"
            }
            val parent = coreShellOwnedDirectory("composeApp/build/test-work/core-shell")
            val directory = Files.createTempDirectory(parent.toPath(), "fixture-").toFile()
            val store = createPlatformLibraryStore(File(directory, "library").path)
            var fixture: CoreShellTestFixture? = null
            try {
                store.initialize().getOrThrow()
                store.upsertCatalog(initial.media.values.toList(), initial.shows.values.toList(),
                    initial.artists.values.toList(), initial.albums.values.toList()).getOrThrow()
                store.update { old -> old.copy(settings = initial.settings, navigation = initial.navigation,
                    followedShowIds = initial.followedShowIds, activeContext = initial.activeContext) }.getOrThrow()
                val favorites = (initial.favoriteIds.ifEmpty { defaultFavoriteIds })
                    .filter { it in initial.media }.toSet()
                val playlists = (initial.playlists.ifEmpty { listOf(playlist) }).map { value ->
                    value.copy(entries = value.entries.filter { it.mediaId in initial.media })
                }
                store.editCatalogLibrary { old -> old.copy(favoriteIds = favorites,
                    savedEpisodeIds = initial.savedEpisodeIds, playlists = playlists) }
                val seed = CoreShellTypedFixtureSeed.seed(store, typedTitles, coreShellClock)
                val created = CoreShellTestFixture(directory, store, typedTitles = typedTitles, seed = seed)
                fixture = created
                created.container.initialize()
                return created
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
            return CoreShellTestFixture(owned, store, fixtureLanguage = systemLanguage)
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

/** Token-checking intent/event seam. It never loads a native player or opens a media locator. */
internal class CoreShellFixtureEngine : AudioEngine {
    sealed interface Load {
        val generation: Long
        val startPositionMs: Long
        val mediaId: String?
        val resource: PlaybackResource?

        data class Legacy(
            val source: ResolvedPlayable,
            override val startPositionMs: Long,
            override val generation: Long,
        ) : Load {
            override val mediaId: String? get() = source.item?.id ?: source.mediaId
            override val resource: PlaybackResource? get() = source.resource
        }

        data class Typed(
            val source: AcquisitionPlaybackSource,
            val selection: PlaybackSelection,
            override val startPositionMs: Long,
            override val generation: Long,
        ) : Load {
            override val mediaId: String? get() = selection.catalogMedia?.id
            override val resource: PlaybackResource? get() = source.resource
        }
    }

    data class Seek(val positionMs: Long, val sequence: Long, val generation: Long)
    override val capabilities = EngineCapabilities()
    private val channel = Channel<EngineEvent>(Channel.UNLIMITED)
    override val events = channel.receiveAsFlow()
    val loads = CopyOnWriteArrayList<Load>()
    val plays = CopyOnWriteArrayList<Long>()
    val pauses = CopyOnWriteArrayList<Long>()
    val seeks = CopyOnWriteArrayList<Seek>()
    @Volatile private var activeGeneration = 0L
    private var active: Load? = null
    private var positionMs = 0L
    private var durationMs: Long? = null
    private var seekable = true
    private var speed = 1f
    private val retainedResources = CopyOnWriteArrayList<PlaybackResource>()
    @Volatile private var retainedFailure: AppFailure? = null

    override suspend fun prepare(source: ResolvedPlayable, startPositionMs: Long, generation: Long) {
        if (generation < activeGeneration || generation == activeGeneration && active != null) {
            releaseIncoming(source.resource)
            return
        }
        try { closeActive() } catch (failure: Throwable) {
            closeIncoming(source.resource, failure)
            throw failure
        }
        val load = Load.Legacy(source, startPositionMs.coerceAtLeast(0), generation)
        active = load
        activeGeneration = generation
        positionMs = load.startPositionMs
        durationMs = source.durationMs
        seekable = source.seekableHint ?: true
        loads += load
        sendReady(load)
    }

    override suspend fun prepare(source: AcquisitionPlaybackSource, selection: PlaybackSelection,
        startPositionMs: Long, generation: Long) {
        if (generation < activeGeneration || generation == activeGeneration && active != null) {
            releaseIncoming(source.resource)
            return
        }
        try { closeActive() } catch (failure: Throwable) {
            closeIncoming(source.resource, failure)
            throw failure
        }
        try {
            // Ownership transfers on invocation. Validate only after registering the incoming owner
            // as the sole active load, and close it if the public selection projection disagrees.
            NativePlaybackSource.Acquired(source, selection).validateSelection()
        } catch (failure: Throwable) {
            closeIncoming(source.resource, failure)
            throw failure
        }
        val load = Load.Typed(source, selection, startPositionMs.coerceAtLeast(0), generation)
        active = load
        activeGeneration = generation
        positionMs = load.startPositionMs
        durationMs = source.durationMs ?: selection.catalogMedia?.durationMs
        seekable = source.seekableHint ?: true
        loads += load
        sendReady(load)
    }

    override suspend fun play(generation: Long) {
        if (generation != activeGeneration || active == null) return
        plays += generation
        channel.send(EngineEvent.StateChanged(generation, EnginePhase.PLAYING, true, speed))
        channel.send(EngineEvent.Position(generation, positionMs, durationMs, seekSequence = 0))
    }

    override suspend fun pause(generation: Long) {
        if (generation != activeGeneration) return
        pauses += generation
        channel.send(EngineEvent.StateChanged(generation, EnginePhase.PAUSED, false, speed))
    }

    override suspend fun seek(positionMs: Long, seekSequence: Long, generation: Long) {
        if (generation != activeGeneration || active == null) return
        val target = positionMs.coerceAtLeast(0)
        seeks += Seek(target, seekSequence, generation)
        this.positionMs = target
        channel.send(EngineEvent.SeekCompleted(generation, seekSequence, target))
    }

    override suspend fun setRate(speed: Float, generation: Long) {
        if (generation == activeGeneration && active != null) this.speed = speed
    }

    override suspend fun stop(generation: Long) {
        if (generation < activeGeneration) return
        val old = active
        active = null
        activeGeneration = generation
        positionMs = 0
        durationMs = null
        seekable = false
        release(old?.resource)
    }

    override suspend fun awaitRetiredReaders(expectedGeneration: Long): NativeReaderDrainResult {
        if (expectedGeneration != activeGeneration) return NativeReaderDrainResult.Superseded
        if (active != null) return NativeReaderDrainResult.Unacknowledged(
            AppFailure(FailureCode.STORAGE_IO, "fixture_native_owner_retained"))
        drainRetained()
        return retainedFailure?.let { NativeReaderDrainResult.Unacknowledged(it) }
            ?: NativeReaderDrainResult.Drained(expectedGeneration)
    }

    override suspend fun close() {
        var failure = retainedFailure
        try { closeActive() } catch (error: Throwable) {
            val value = when (error) {
                is VeyraException -> error.failure
                else -> AppFailure(FailureCode.STORAGE_IO, "fixture_native_resource_close")
            }
            if (failure == null) failure = value
        }
        drainRetained()
        if (failure == null) failure = retainedFailure
        channel.close()
        failure?.let { throw VeyraException(it) }
    }

    private suspend fun sendReady(load: Load) {
        try {
            channel.send(EngineEvent.Ready(load.generation, durationMs, seekable))
        } catch (failure: Throwable) {
            active = null
            try { release(load.resource) } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            throw failure
        }
    }

    private suspend fun closeActive() {
        val old = active ?: return
        active = null
        release(old.resource)
    }

    private suspend fun releaseIncoming(resource: PlaybackResource?) {
        release(resource)
    }

    private suspend fun closeIncoming(resource: PlaybackResource?, primary: Throwable) {
        try { release(resource) } catch (closeFailure: Throwable) { primary.addSuppressed(closeFailure) }
    }

    private suspend fun release(resource: PlaybackResource?) {
        if (resource == null) return
        try {
            resource.close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (retainedResources.none { it === resource }) retainedResources += resource
            val failure = AppFailure(FailureCode.STORAGE_IO, "fixture_native_resource_close")
            retainedFailure = failure
            throw VeyraException(failure)
        }
    }

    private suspend fun drainRetained() {
        for (resource in retainedResources.toList()) {
            try {
                resource.close()
                retainedResources.remove(resource)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                retainedFailure = AppFailure(FailureCode.STORAGE_IO, "fixture_native_resource_close")
            }
        }
    }
}
