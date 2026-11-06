package dev.veyra.app

import dev.veyra.artwork.ArtworkCache
import dev.veyra.artwork.ArtworkFiles
import dev.veyra.data.LibraryStore
import dev.veyra.data.StoreStatus
import dev.veyra.data.getOrThrow
import dev.veyra.downloads.DownloadCoordinator
import dev.veyra.downloads.DownloadFiles
import dev.veyra.model.*
import dev.veyra.playback.*
import dev.veyra.source.*
import dev.veyra.source.commons.CommonsSource
import dev.veyra.source.execution.AppSourceResolver
import dev.veyra.source.execution.KmpMediaGateway
import dev.veyra.source.network.SafeHttp
import dev.veyra.source.network.createSafeHttp
import dev.veyra.source.podcast.PodcastSource
import dev.veyra.source.youtube.YouTubeKmpMediaGateway
import dev.veyra.source.youtube.YouTubeTransferProgress
import dev.veyra.source.youtube.YouTubeTransferResult
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.music.*
import dev.veyra.ui.podcasts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

sealed interface AppInitialization {
    data object Loading : AppInitialization
    data object Ready : AppInitialization
    data class Failed(val failure: AppFailure) : AppInitialization
}

data class AppNotice(val id: String = newEntityId("notice"), val text: TextKey? = null, val failure: AppFailure? = null)
data class PodcastDetailStatus(
    val refreshing: Set<String> = emptySet(),
    val failures: Map<String, AppFailure> = emptyMap(),
    val options: Map<String, PodcastListOptions> = emptyMap(),
)

private data class LibrarySlice(val playlists: List<Playlist>, val favorites: Set<String>, val saved: Set<String>) {
    fun restore(snapshot: AppSnapshot) = snapshot.copy(playlists = playlists, favoriteIds = favorites, savedEpisodeIds = saved)
    companion object { fun of(snapshot: AppSnapshot) = LibrarySlice(snapshot.playlists, snapshot.favoriteIds, snapshot.savedEpisodeIds) }
}
private data class LibraryUndo(val before: LibrarySlice, val after: LibrarySlice)

/**
 * One app-lifetime composition root. Screens emit intents; they never construct clients, players,
 * Room databases, or media transfer implementations. The optional seams are for real integration
 * tests; the default acquisition gateway is always kmpYT.
 */
class AppContainer(
    val platform: PlatformServices,
    parentScope: CoroutineScope,
    private val http: SafeHttp = createSafeHttp(),
    gateway: KmpMediaGateway = YouTubeKmpMediaGateway(platform.youtube),
    private val clock: Clock = SystemClock(),
) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime + Dispatchers.Default)
    private val effectsLifetime = SupervisorJob(lifetime)
    private val effects = CoroutineScope(scope.coroutineContext + effectsLifetime)
    private val lifecycleLock = Mutex()
    private val closedState = MutableStateFlow(false)
    private var closed: Boolean
        get() = closedState.value
        set(value) { closedState.value = value }
    // Guarded by lifecycleLock. A second close must not falsely certify released owners.
    private var closeFailure: AppFailure? = null
    private val rejectedResourceLock = Mutex()
    private val rejectedResources = mutableListOf<PlaybackResource>()
    private var rejectedResourceFailure: AppFailure? = null
    // Completion includes policy-return failure cleanup, not just the inner request coroutine.
    private val activeAcquisitions = MutableStateFlow<List<CompletableDeferred<Unit>>>(emptyList())
    val store: LibraryStore = platform.store
    private val executionGateway = object : KmpMediaGateway {
        override suspend fun firstResult(completeTitle: String) = online { gateway.firstResult(completeTitle) }
        override suspend fun resolve(binding: PlaybackBinding, capabilities: EngineCapabilities, purpose: ResolvePurpose): ResolvedPlayable {
            // coroutineScope's cancellable return can discard a successful result. Retain the
            // resource here until the policy scope hands it to the resolver, not only there.
            var acquired: ResolvedPlayable? = null
            val completion = CompletableDeferred<Unit>()
            activeAcquisitions.update { it + completion }
            try {
                val result = online {
                    platform.prepareMediaRuntime()
                    gateway.resolve(binding, capabilities, purpose).also { acquired = it }
                }
                if (closed) throw VeyraException(AppFailure(FailureCode.INVALID_STATE))
                return result
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    try { acquired?.resource?.let { releaseRejectedResource(it) } }
                    catch (releaseFailure: Throwable) {
                        // Preserve only typed diagnostics; failed ownership is retained below,
                        // rather than forgotten after attaching an arbitrary native exception.
                        val sanitized = failureOf(releaseFailure)
                        failure.addSuppressed(VeyraException(sanitized))
                        fail(sanitized)
                    }
                }
                throw failure
            } finally {
                completion.complete(Unit)
                activeAcquisitions.update { old -> old.filterNot { it === completion } }
            }
        }
        override suspend fun download(binding: PlaybackBinding, destinationPath: String, capabilities: EngineCapabilities,
            representationId: String?, onProgress: suspend (YouTubeTransferProgress) -> Unit): YouTubeTransferResult = online {
            platform.prepareMediaRuntime()
            gateway.download(binding, destinationPath, capabilities, representationId, onProgress)
        }
    }
    val files = DownloadFiles("${platform.dataDirectory}/media")
    val resolver = AppSourceResolver(store, files, platform.validator, executionGateway, clock)
    val playback = PlaybackController(store, platform.engine, resolver, scope, clock)
    val downloads = DownloadCoordinator(store, files, resolver, executionGateway, platform.validator,
        platform.engine.capabilities, scope, platform.downloadExecution, clock,
        beforePhysicalDelete = { asset -> playback.releaseForAssetRemoval(asset.mediaId).getOrThrow() })
    /** UI projection only: durable completion is not proof that this process checked the file. */
    val library: StateFlow<AppSnapshot> = downloads.presentation
    private val podcastsSource = PodcastSource(http, nowMs = clock::epochMs)
    private val commonsSource = CommonsSource(http, nowMs = clock::epochMs)
    private val artwork = ArtworkCache(store, ArtworkFiles("${platform.dataDirectory}/artwork"), http)
    private val artworkPermits = Semaphore(3)

    private val mutableInitialization = MutableStateFlow<AppInitialization>(AppInitialization.Loading)
    val initialization = mutableInitialization.asStateFlow()
    private val mutableNavigation = MutableStateFlow(AppNavigation())
    val navigation = mutableNavigation.asStateFlow()
    private val mutableSearch = MutableStateFlow(SearchUiState())
    val search = mutableSearch.asStateFlow()
    private val mutableDiscovery = MutableStateFlow(CatalogUiState())
    val discovery = mutableDiscovery.asStateFlow()
    private val mutableCollections = MutableStateFlow<Map<String, CollectionUiState>>(emptyMap())
    val collections = mutableCollections.asStateFlow()
    private val mutablePodcasts = MutableStateFlow(PodcastsUiState())
    val podcasts = mutablePodcasts.asStateFlow()
    private val mutablePodcastDetails = MutableStateFlow(PodcastDetailStatus())
    val podcastDetails = mutablePodcastDetails.asStateFlow()
    private val mutableNotice = MutableStateFlow<AppNotice?>(null)
    val notice = mutableNotice.asStateFlow()
    private val undoLibrary = MutableStateFlow<LibraryUndo?>(null)
    private val mutableStorageBytes = MutableStateFlow<Long?>(null)
    val storageBytes = mutableStorageBytes.asStateFlow()
    val musicOptions: StateFlow<MusicUiOptions> = combine(store.state, playback.state, undoLibrary) { snapshot, player, undo ->
        MusicUiOptions(
            downloadableProviders = setOf(SourceProvider.YOUTUBE, SourceProvider.RSS, SourceProvider.COMMONS),
            undoableQueueContexts = player.undoableQueueContexts,
            canUndoLibrary = undo != null && LibrarySlice.of(snapshot) == undo.after,
            // Local import needs a central-library execution contract; never advertise an alternate engine.
            supportsImport = false,
            minSpeed = platform.engine.capabilities.minSpeed,
            maxSpeed = platform.engine.capabilities.maxSpeed,
        )
    }.stateIn(effects, SharingStarted.Eagerly, MusicUiOptions(supportsImport = false))

    private val actions = Channel<suspend () -> Unit>(128)
    private val navigationWrites = Channel<Unit>(Channel.CONFLATED)
    private var searchJob: Job? = null
    private var discoveryJob: Job? = null
    private var feedJob: Job? = null
    private var refreshJob: Job? = null
    private var mediaRuntimeJob: Job? = null
    private val collectionJobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    private val searchGeneration = MutableStateFlow(0L)
    private val discoveryGeneration = MutableStateFlow(0L)

    private suspend fun releaseRejectedResource(resource: PlaybackResource): Unit = rejectedResourceLock.withLock {
        if (rejectedResources.none { it === resource }) rejectedResources += resource
        try {
            resource.close()
            rejectedResources.removeAll { it === resource }
        } catch (error: Throwable) {
            if (rejectedResourceFailure == null) rejectedResourceFailure = failureOf(error)
            throw error
        }
    }

    /** Retry each retained owner, but a previously failed close never becomes a clean-close claim. */
    private suspend fun drainRejectedResources(): Unit = rejectedResourceLock.withLock {
        for (resource in rejectedResources.toList()) {
            try {
                resource.close()
                rejectedResources.removeAll { it === resource }
            } catch (error: Throwable) {
                if (rejectedResourceFailure == null) rejectedResourceFailure = failureOf(error)
            }
        }
        rejectedResourceFailure?.let { throw VeyraException(it) }
    }

    suspend fun initialize() = lifecycleLock.withLock { initializeLocked() }

    private suspend fun initializeLocked() {
        check(!closed)
        if (mutableInitialization.value is AppInitialization.Ready) return
        mutableInitialization.value = AppInitialization.Loading
        try {
            val snapshot = store.initialize().getOrThrow()
            withContext(Dispatchers.IO) { files.initialize() }
            artwork.initialize()
            mutableNavigation.value = AppNavigation.restore(snapshot)
            mutablePodcastDetails.value = PodcastDetailStatus(options = snapshot.shows.keys.associateWith { id ->
                val prefix = "podcast.options.$id."
                PodcastListOptions(
                    query = snapshot.navigation[prefix + "query"].orEmpty(),
                    filter = PodcastEpisodeFilter.entries.firstOrNull { it.name == snapshot.navigation[prefix + "filter"] } ?: PodcastEpisodeFilter.ALL,
                    sort = PodcastEpisodeSort.entries.firstOrNull { it.name == snapshot.navigation[prefix + "sort"] } ?: PodcastEpisodeSort.NEWEST_FIRST,
                )
            })
            playback.initialize().getOrThrow()
            downloads.start()
            effects.launch {
                for (action in actions) {
                    try { action() }
                    catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        fail(AppFailure(if (store.state.value.settings.offlineOnly) FailureCode.OFFLINE_POLICY else FailureCode.CANCELLED))
                    }
                    catch (error: Exception) { report(error) }
                }
            }
            effects.launch {
                for (signal in navigationWrites) persistNavigation()
            }
            effects.launch {
                downloads.lastFailure.filterNotNull().distinctUntilChanged().collect { fail(it) }
            }
            effects.launch {
                store.state.map { it.settings.offlineOnly }.distinctUntilChanged().collect { offline ->
                    if (offline) {
                        // Each network task also observes the policy and cancels its own transfer.
                        mutableSearch.update { it.copy(loading = false) }
                        mutableDiscovery.update { it.copy(loading = false) }
                    }
                }
            }
            mutableInitialization.value = AppInitialization.Ready
            mutableNavigation.value.details.mapNotNull { it.collection }.distinctBy { it.id }.forEach { collection ->
                if (snapshot.settings.offlineOnly) mutableCollections.update { it + (collection.id to CollectionUiState(
                    collection, failure = AppFailure(FailureCode.OFFLINE_POLICY))) }
                else loadCollection(collection, false)
            }
            refreshStorage()
            // Cold launch is usable immediately from Room. Network discovery is an explicit
            // request from Listen/Search, never a dependency of opening a local library.
            navigationWrites.trySend(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val failure = failureOf(error)
            mutableInitialization.value = AppInitialization.Failed(failure)
            throw VeyraException(failure)
        }
    }

    /** User-confirmed restore only. The store verifies a backup and refuses future-schema downgrade. */
    fun restoreLibraryBackup() {
        if (closed) return
        effects.launch {
            lifecycleLock.withLock {
                if (closed || mutableInitialization.value !is AppInitialization.Failed ||
                    (store.status.value as? StoreStatus.Failed)?.failure?.code != FailureCode.RECOVERY_REQUIRED) return@withLock
                mutableInitialization.value = AppInitialization.Loading
                try {
                    store.recoverFromBackup().getOrThrow()
                    initializeLocked()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { mutableInitialization.value = AppInitialization.Failed(failureOf(error)) }
            }
        }
    }

    /** Optional explicit software setup. Normal playback/download commands use the same native gate. */
    fun prepareMediaTools() = submit {
        if (mediaRuntimeJob?.isActive != true) mediaRuntimeJob = effects.launch {
            try { online { platform.prepareMediaRuntime() } }
            catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                fail(AppFailure(if (store.state.value.settings.offlineOnly) FailureCode.OFFLINE_POLICY else FailureCode.CANCELLED))
            }
            catch (error: Exception) { report(error) }
        }
    }

    fun cachedArtworkPath(mediaId: String): String? = artwork.cachedArtworkPath(mediaId)
    fun dismissNotice(id: String) { mutableNotice.update { if (it?.id == id) null else it } }

    fun selectDestination(destination: AppDestination) {
        mutableNavigation.update { it.copy(destination = destination, details = emptyList(), playerOpen = false, queueOpen = false) }
        navigationWrites.trySend(Unit)
        if (destination == AppDestination.SETTINGS) refreshStorage()
    }

    /** Synchronous so a native Back handler can decide whether the OS should handle the event. */
    fun navigateBack(): Boolean {
        while (true) {
            val current = mutableNavigation.value
            if (!current.canGoBack) return false
            if (mutableNavigation.compareAndSet(current, current.back())) {
                navigationWrites.trySend(Unit)
                return true
            }
        }
    }

    fun onMusicAction(action: MusicUiAction) = submit { handleMusic(action) }
    fun onPodcastAction(action: PodcastAction) = submit { handlePodcast(action) }

    /** Transform the latest transactional settings, never save a stale screen's whole settings. */
    fun changeSettings(transform: (AppSettings) -> AppSettings) = submit {
        store.update { it.copy(settings = transform(it.settings)) }.getOrThrow()
    }

    fun openLicenses() = navigate { it.open(AppDetail(DetailKind.LICENSES, "legal")) }

    fun refreshStorage() {
        if (closed) return
        effects.launch {
            try { mutableStorageBytes.value = files.bytesUsed() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(error) }
        }
    }

    private fun submit(action: suspend () -> Unit) {
        if (closed || mutableInitialization.value !is AppInitialization.Ready || actions.trySend(action).isFailure) {
            fail(AppFailure(FailureCode.INVALID_STATE))
        }
    }

    private fun navigate(transform: (AppNavigation) -> AppNavigation) {
        mutableNavigation.update(transform)
        navigationWrites.trySend(Unit)
    }

    private suspend fun persistNavigation() {
        try {
            persistNavigationOrThrow()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { report(error) }
    }

    private suspend fun persistNavigationOrThrow() {
        val values = mutableNavigation.value.persisted()
        store.update { old -> old.copy(navigation = old.navigation.filterKeys { it.startsWith("podcast.options.") } + values) }.getOrThrow()
    }

    private fun current(item: MediaItem): MediaItem = store.state.value.media[item.id] ?: item
    private fun context(item: MediaItem) = if (item.kind == MediaKind.EPISODE) QueueContext.PODCAST else QueueContext.MUSIC
    private suspend fun command(command: PlaybackCommand) { playback.dispatch(command).getOrThrow() }

    private suspend fun play(items: List<MediaItem>, shuffle: Boolean = false) {
        require(items.isNotEmpty())
        val selected = items.map(::current)
        val selectedContext = context(selected.first())
        require(selected.all { context(it) == selectedContext })
        command(PlaybackCommand.Play(selectedContext, selected, shuffle = if (selectedContext == QueueContext.MUSIC) shuffle else false))
        cacheArtwork(selected)
    }

    private fun download(item: MediaItem): Deferred<Boolean> {
        val selected = current(item)
        // Capture the recording displayed at the intent, not after later track switches.
        val bindingId = playback.state.value.executionBinding?.takeIf { it.mediaId == selected.id }?.id
        return downloadEffect {
            downloads.enqueue(selected, reuseBindingId = bindingId)
            cacheArtwork(listOf(selected))
            selected.showId?.let { id -> store.state.value.shows[id]?.let { cacheArtwork(emptyList(), listOf(it)) } }
        }
    }

    /**
     * The coordinator serializes durable intents, then releases its command lock before slow
     * native/file cleanup. Enter that ordering point immediately, but never make playback,
     * navigation or an offline-policy change wait for a download's release/validation barrier.
     */
    private fun downloadEffect(action: suspend () -> Unit): Deferred<Boolean> = effects.async(start = CoroutineStart.UNDISPATCHED) {
        try { action(); true }
        catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            fail(AppFailure(if (store.state.value.settings.offlineOnly) FailureCode.OFFLINE_POLICY else FailureCode.CANCELLED))
            false
        }
        catch (error: Exception) { report(error); false }
    }

    private suspend fun removeDownload(id: String) {
        // Durable intent is written by downloads first. Its beforePhysicalDelete callback waits
        // for the authoritative controller's native file-release barrier before removing bytes.
        downloads.remove(id)
        refreshStorage()
    }

    private suspend fun handleMusic(action: MusicUiAction) {
        when (action) {
            is MusicUiAction.SearchChanged -> {
                searchJob?.cancel()
                searchGeneration.update { it + 1 }
                val provider = if (action.kind == SearchKind.PODCASTS) SourceProvider.RSS
                    else action.provider.takeUnless { it == SourceProvider.RSS || it == SourceProvider.LOCAL } ?: SourceProvider.YOUTUBE
                mutableSearch.value = SearchUiState(action.query, action.scope, action.kind, provider)
            }
            MusicUiAction.RunSearch -> runSearch(false)
            MusicUiAction.LoadMoreSearch -> runSearch(true)
            is MusicUiAction.Discover -> discover(action.provider, false)
            MusicUiAction.LoadMoreDiscovery -> discover(mutableDiscovery.value.provider, true)
            is MusicUiAction.OpenSearch -> {
                navigate { it.copy(destination = AppDestination.SEARCH, details = emptyList(), playerOpen = false, queueOpen = false) }
                if (mutableSearch.value.kind != action.kind) {
                    searchJob?.cancel()
                    searchGeneration.update { it + 1 }
                    mutableSearch.update { SearchUiState(it.query, it.scope, action.kind,
                        if (action.kind == SearchKind.PODCASTS) SourceProvider.RSS
                        else it.provider.takeUnless { provider -> provider == SourceProvider.RSS } ?: SourceProvider.YOUTUBE) }
                }
            }
            is MusicUiAction.OpenLibrary -> navigate { it.copy(destination = AppDestination.LIBRARY,
                librarySection = action.section, details = emptyList(), playerOpen = false, queueOpen = false) }
            is MusicUiAction.OpenPlaylist -> navigate { it.open(AppDetail(DetailKind.PLAYLIST, action.playlistId)) }
            is MusicUiAction.OpenCollection -> {
                navigate { it.open(AppDetail(DetailKind.COLLECTION, action.collection.id, action.collection)) }
                loadCollection(action.collection, false)
            }
            is MusicUiAction.OpenMedia -> {
                rememberPage(SourcePage(items = listOf(action.item)))
                navigate { it.open(AppDetail(if (action.item.kind == MediaKind.EPISODE) DetailKind.EPISODE else DetailKind.TRACK, action.item.id)) }
                cacheArtwork(listOf(action.item))
            }
            is MusicUiAction.OpenShow -> {
                rememberPage(SourcePage(shows = listOf(action.show)))
                openShow(action.show.id)
            }
            MusicUiAction.NavigateBack -> navigateBack()
            MusicUiAction.ClosePlayer -> navigate { it.copy(playerOpen = false, queueOpen = false) }
            MusicUiAction.RefreshCollection -> mutableNavigation.value.detail?.collection?.let { loadCollection(it, false) }
            MusicUiAction.LoadMoreCollection -> mutableNavigation.value.detail?.collection?.let { loadCollection(it, true) }
            MusicUiAction.ImportAudio -> fail(AppFailure(FailureCode.UNSUPPORTED_SOURCE))
            is MusicUiAction.Play -> play(listOf(action.item))
            is MusicUiAction.PlayCollection -> play(action.items, action.shuffle)
            is MusicUiAction.EnqueueCollection -> {
                require(action.items.isNotEmpty())
                val selected = action.items.map(::current)
                require(selected.all { context(it) == context(selected.first()) })
                command(PlaybackCommand.Enqueue(context(selected.first()), selected))
            }
            is MusicUiAction.SetFavorites -> editLibrary { old ->
                val music = action.items.filter { it.kind == MediaKind.MUSIC }.map { it.id }.toSet()
                val episodes = action.items.filter { it.kind == MediaKind.EPISODE }.map { it.id }.toSet()
                old.copy(media = mergeMedia(old.media, action.items),
                    favoriteIds = if (action.favorite) old.favoriteIds + music else old.favoriteIds - music,
                    savedEpisodeIds = if (action.favorite) old.savedEpisodeIds + episodes else old.savedEpisodeIds - episodes)
            }
            is MusicUiAction.ToggleFavorite -> editLibrary { old ->
                val item = old.media[action.item.id] ?: action.item
                old.copy(media = old.media + (item.id to item),
                    favoriteIds = if (item.kind == MediaKind.MUSIC) old.favoriteIds.toggled(item.id) else old.favoriteIds,
                    savedEpisodeIds = if (item.kind == MediaKind.EPISODE) old.savedEpisodeIds.toggled(item.id) else old.savedEpisodeIds)
            }
            is MusicUiAction.Enqueue -> command(PlaybackCommand.Enqueue(context(action.item), listOf(current(action.item)),
                if (action.playNext) EnqueuePosition.NEXT else EnqueuePosition.END))
            is MusicUiAction.CreatePlaylist -> {
                require(action.name.isNotBlank() && action.name.length <= 500)
                val id = newEntityId("playlist")
                editLibrary { old ->
                    val item = action.initialItem?.let { old.media[it.id] ?: it }
                    old.copy(media = if (item == null) old.media else old.media + (item.id to item),
                        playlists = old.playlists + Playlist(id, action.name.trim(),
                            item?.let { listOf(PlaylistEntry(newEntityId(), it.id)) }.orEmpty(), clock.epochMs(), clock.epochMs()))
                }
            }
            is MusicUiAction.RenamePlaylist -> editLibrary { old ->
                require(action.name.isNotBlank() && action.name.length <= 500)
                require(old.playlists.any { it.id == action.playlistId })
                old.copy(playlists = old.playlists.map { if (it.id == action.playlistId) it.copy(name = action.name.trim(), updatedEpochMs = clock.epochMs()) else it })
            }
            is MusicUiAction.DeletePlaylist -> editLibrary { old -> old.copy(playlists = old.playlists.filterNot { it.id == action.playlistId }) }
            is MusicUiAction.AddToPlaylist -> editLibrary { old ->
                require(old.playlists.any { it.id == action.playlistId })
                old.copy(media = mergeMedia(old.media, listOf(action.item)), playlists = old.playlists.map { playlist ->
                    if (playlist.id != action.playlistId || (!action.allowDuplicate && playlist.entries.any { it.mediaId == action.item.id })) playlist
                    else playlist.copy(entries = playlist.entries + PlaylistEntry(newEntityId(), action.item.id), updatedEpochMs = clock.epochMs())
                })
            }
            is MusicUiAction.RemovePlaylistEntry -> editLibrary { old -> old.copy(playlists = old.playlists.map {
                if (it.id == action.playlistId) it.copy(entries = it.entries.filterNot { entry -> entry.id == action.entryId }, updatedEpochMs = clock.epochMs()) else it
            }) }
            is MusicUiAction.MovePlaylistEntry -> editLibrary { old -> old.copy(playlists = old.playlists.map { playlist ->
                if (playlist.id != action.playlistId) playlist else {
                    val entries = playlist.entries.toMutableList()
                    val from = entries.indexOfFirst { it.id == action.entryId }
                    require(from >= 0 && action.toIndex in entries.indices)
                    entries.add(action.toIndex, entries.removeAt(from))
                    playlist.copy(entries = entries, updatedEpochMs = clock.epochMs())
                }
            }) }
            MusicUiAction.UndoLibrary -> {
                val undo = undoLibrary.value ?: return
                store.update { old -> require(LibrarySlice.of(old) == undo.after); undo.before.restore(old) }.getOrThrow()
                undoLibrary.value = null
            }
            is MusicUiAction.Download -> download(action.item)
            is MusicUiAction.DownloadCollection -> {
                val pending = action.items.distinctBy { it.id }.map(::download)
                effects.launch { if (pending.isNotEmpty() && pending.awaitAll().all { it }) inform(TextKey.DownloadsQueued) }
            }
            is MusicUiAction.PauseDownload -> downloadEffect { downloads.pause(action.downloadId) }
            is MusicUiAction.ResumeDownload -> downloadEffect { downloads.resume(action.downloadId) }
            is MusicUiAction.CancelDownload -> downloadEffect { downloads.cancel(action.downloadId) }
            is MusicUiAction.RemoveDownload -> downloadEffect { removeDownload(action.downloadId) }
            MusicUiAction.ManageStorage -> selectDestination(AppDestination.SETTINGS)
            MusicUiAction.OpenPlayer -> navigate { it.copy(playerOpen = true, queueOpen = false) }
            is MusicUiAction.OpenQueue -> navigate { it.copy(queueOpen = true, viewedQueue = action.context ?: playback.state.value.activeContext) }
            is MusicUiAction.ViewQueue -> {
                navigate { it.copy(viewedQueue = action.context) }
                command(PlaybackCommand.SelectContext(action.context))
            }
            is MusicUiAction.PlayQueueEntry -> command(PlaybackCommand.PlayEntry(action.context, action.entryId))
            MusicUiAction.TogglePlayPause -> command(PlaybackCommand.PlayPause)
            MusicUiAction.Previous -> command(PlaybackCommand.Previous)
            MusicUiAction.Next -> command(PlaybackCommand.Next)
            is MusicUiAction.Seek -> command(PlaybackCommand.Seek(action.positionMs, action.expectedEntryId,
                action.expectedGeneration))
            is MusicUiAction.Skip -> {
                command(PlaybackCommand.SkipBy(action.milliseconds, action.expectedEntryId, action.expectedGeneration))
            }
            is MusicUiAction.SetShuffle -> command(PlaybackCommand.SetShuffle(action.enabled))
            is MusicUiAction.SetRepeat -> command(PlaybackCommand.SetRepeat(action.mode))
            is MusicUiAction.SetSpeed -> {
                command(PlaybackCommand.SetRate(action.rate, action.forThisShow))
            }
            is MusicUiAction.SetSleepMinutes -> { require(action.minutes in 1..1440); command(PlaybackCommand.SleepDuration(action.minutes * 60_000L)) }
            MusicUiAction.SleepAtEpisodeEnd -> command(PlaybackCommand.SleepAtEndOfEpisode)
            MusicUiAction.ClearSleepTimer -> command(PlaybackCommand.CancelSleepTimer)
            is MusicUiAction.MoveQueueEntry -> command(PlaybackCommand.Move(action.context, action.entryId, action.toIndex))
            is MusicUiAction.RemoveQueueEntry -> command(PlaybackCommand.Remove(action.context, action.entryId))
            is MusicUiAction.ClearQueue -> command(PlaybackCommand.ClearUpcoming(action.context))
            is MusicUiAction.UndoQueue -> command(PlaybackCommand.UndoQueueEdit(action.context))
            is MusicUiAction.OpenSourcePage -> openExternalInBackground(action.url)
        }
    }

    private suspend fun handlePodcast(action: PodcastAction) {
        when (action) {
            PodcastAction.FindPodcasts -> handleMusic(MusicUiAction.OpenSearch(SearchKind.PODCASTS))
            PodcastAction.OpenAddFeed -> mutablePodcasts.update { it.copy(addFeed = AddPodcastFeedUiState()) }
            PodcastAction.DismissAddFeed -> { feedJob?.cancel(); mutablePodcasts.update { it.copy(addFeed = null) } }
            is PodcastAction.FeedUrlChanged -> mutablePodcasts.update { it.copy(addFeed = (it.addFeed ?: AddPodcastFeedUiState()).copy(url = action.url, failure = null)) }
            is PodcastAction.SubmitFeed -> addFeed(action.url)
            PodcastAction.RefreshShows -> refreshFollowedShows()
            is PodcastAction.RefreshShow -> refreshShow(action.showId)
            is PodcastAction.OpenShow -> openShow(action.showId)
            is PodcastAction.OpenEpisode -> navigate { it.open(AppDetail(DetailKind.EPISODE, action.mediaId)) }
            PodcastAction.NavigateBack -> navigateBack()
            is PodcastAction.SetFollowed -> {
                store.update { old -> require(action.showId in old.shows); old.copy(followedShowIds =
                    if (action.followed) old.followedShowIds + action.showId else old.followedShowIds - action.showId) }.getOrThrow()
                store.state.value.shows[action.showId]?.let { cacheArtwork(emptyList(), listOf(it)) }
                if (action.followed && !store.state.value.settings.offlineOnly) refreshShow(action.showId)
            }
            is PodcastAction.SetShowListOptions -> {
                store.update { old ->
                    require(action.showId in old.shows)
                    val prefix = "podcast.options.${action.showId}."
                    old.copy(navigation = old.navigation + mapOf(prefix + "query" to action.options.query,
                        prefix + "filter" to action.options.filter.name, prefix + "sort" to action.options.sort.name))
                }.getOrThrow()
                mutablePodcastDetails.update { it.copy(options = it.options + (action.showId to action.options)) }
            }
            is PodcastAction.PlayEpisode -> play(listOf(episode(action.mediaId)))
            is PodcastAction.StartOver -> { command(PlaybackCommand.ResetProgress(action.mediaId)); play(listOf(episode(action.mediaId))) }
            is PodcastAction.SetPlayed -> command(PlaybackCommand.MarkPlayed(action.mediaId, action.played))
            is PodcastAction.SetSaved -> store.update { old ->
                require(old.media[action.mediaId]?.kind == MediaKind.EPISODE)
                old.copy(savedEpisodeIds = if (action.saved) old.savedEpisodeIds + action.mediaId else old.savedEpisodeIds - action.mediaId)
            }.getOrThrow().let { }
            is PodcastAction.PlayNext -> command(PlaybackCommand.Enqueue(QueueContext.PODCAST, listOf(episode(action.mediaId)), EnqueuePosition.NEXT))
            is PodcastAction.AddToEnd -> command(PlaybackCommand.Enqueue(QueueContext.PODCAST, listOf(episode(action.mediaId))))
            PodcastAction.OpenQueue -> navigate { it.copy(queueOpen = true, viewedQueue = QueueContext.PODCAST) }
            is PodcastAction.Download -> download(episode(action.mediaId))
            is PodcastAction.PauseDownload -> downloadEffect { downloads.pause(action.downloadId) }
            is PodcastAction.ResumeDownload -> downloadEffect { downloads.resume(action.downloadId) }
            is PodcastAction.CancelDownload -> downloadEffect { downloads.cancel(action.downloadId) }
            is PodcastAction.RemoveDownload -> downloadEffect { removeDownload(action.downloadId) }
            is PodcastAction.OpenPublisher -> openExternalInBackground(action.url)
        }
    }

    private fun episode(id: String): MediaItem = store.state.value.media[id]?.takeIf { it.kind == MediaKind.EPISODE }
        ?: throw VeyraException(AppFailure(FailureCode.NOT_FOUND))

    private suspend fun editLibrary(transform: (AppSnapshot) -> AppSnapshot) {
        var undo: LibraryUndo? = null
        store.update { old -> transform(old).also { next ->
            val before = LibrarySlice.of(old)
            val after = LibrarySlice.of(next)
            if (before != after) undo = LibraryUndo(before, after)
        } }.getOrThrow()
        if (undo != null) undoLibrary.value = undo
    }

    private fun source(provider: SourceProvider): MediaSource = when (provider) {
        SourceProvider.YOUTUBE -> platform.youtube
        SourceProvider.RSS -> podcastsSource
        SourceProvider.COMMONS -> commonsSource
        SourceProvider.LOCAL -> throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE))
    }

    private suspend fun runSearch(more: Boolean) {
        val request = mutableSearch.value
        if (request.scope == SearchScope.LIBRARY) { mutableSearch.update { it.copy(searched = true, loading = false) }; return }
        if (request.loading || (more && request.page.nextPageToken == null)) return
        searchJob?.cancel()
        val generation = searchGeneration.value + 1
        searchGeneration.value = generation
        mutableSearch.update { it.copy(loading = true, failure = null, searched = true, page = if (more) it.page else SourcePage()) }
        searchJob = effects.launch {
            try {
                val page = online {
                    when (request.kind) {
                        SearchKind.PODCASTS -> podcastsSource.search(request.query)
                        SearchKind.MUSIC -> source(request.provider).search(request.query, if (more) request.page.nextPageToken else null)
                        SearchKind.ALL -> {
                            if (more) source(request.provider).search(request.query, request.page.nextPageToken)
                            else {
                                var musicFailure: AppFailure? = null
                                var podcastFailure: AppFailure? = null
                                val music = try { source(request.provider).search(request.query) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (error: Exception) { musicFailure = failureOf(error); SourcePage() }
                                val podcastPage = try { podcastsSource.search(request.query) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (error: Exception) { podcastFailure = failureOf(error); SourcePage() }
                                if (musicFailure != null && podcastFailure != null) throw VeyraException(musicFailure)
                                mergePages(music, podcastPage).copy(nextPageToken = music.nextPageToken,
                                    notice = musicFailure ?: podcastFailure ?: podcastPage.notice ?: music.notice)
                            }
                        }
                    }
                }
                rememberPage(page)
                if (generation == searchGeneration.value) mutableSearch.update { it.copy(loading = false,
                    page = if (more) mergePages(request.page, page) else page, failure = null) }
                cacheArtwork(page.items, page.shows)
            } catch (cancelled: CancellationException) {
                if (generation == searchGeneration.value) mutableSearch.update { it.copy(loading = false,
                    failure = if (store.state.value.settings.offlineOnly) AppFailure(FailureCode.OFFLINE_POLICY) else it.failure) }
                throw cancelled
            } catch (error: Exception) {
                if (generation == searchGeneration.value) mutableSearch.update { it.copy(loading = false, failure = failureOf(error)) }
            }
        }
    }

    private fun discover(provider: SourceProvider, more: Boolean) {
        val before = mutableDiscovery.value
        if (more && (before.loading || before.page.nextPageToken == null)) return
        discoveryJob?.cancel()
        val generation = discoveryGeneration.value + 1
        discoveryGeneration.value = generation
        mutableDiscovery.value = CatalogUiState(provider, if (more) before.page else SourcePage(), loading = true, loaded = true)
        discoveryJob = effects.launch {
            try {
                val language = effectiveLanguage(store.state.value.settings.language)
                val page = online { source(provider).browse(BrowseRequest(pageToken = if (more) before.page.nextPageToken else null,
                    language = if (language == LanguagePreference.ARABIC) "ar" else "en")) }
                rememberPage(page)
                if (generation == discoveryGeneration.value) mutableDiscovery.update { it.copy(loading = false,
                    page = if (more) mergePages(before.page, page) else page) }
                cacheArtwork(page.items, page.shows)
            } catch (cancelled: CancellationException) {
                if (generation == discoveryGeneration.value) mutableDiscovery.update { it.copy(loading = false,
                    failure = if (store.state.value.settings.offlineOnly) AppFailure(FailureCode.OFFLINE_POLICY) else it.failure) }
                throw cancelled
            } catch (error: Exception) {
                if (generation == discoveryGeneration.value) mutableDiscovery.update { it.copy(loading = false, failure = failureOf(error)) }
            }
        }
    }

    private fun loadCollection(collection: MediaCollection, more: Boolean) {
        val before = mutableCollections.value[collection.id] ?: CollectionUiState(collection)
        if (more && (before.loading || before.page.nextPageToken == null)) return
        collectionJobs.value[collection.id]?.cancel()
        mutableCollections.update { it + (collection.id to before.copy(loading = true, failure = null)) }
        val job = effects.launch(start = CoroutineStart.LAZY) {
            val thisJob = currentCoroutineContext()[Job]
            try {
                val page = online { source(collection.source.provider).browse(BrowseRequest(collection.kind,
                    collection.source.canonicalId, if (more) before.page.nextPageToken else null)) }
                rememberPage(page)
                if (collectionJobs.value[collection.id] === thisJob) mutableCollections.update { it + (collection.id to before.copy(
                    loading = false, loaded = true, failure = null, page = if (more) mergePages(before.page, page)
                        .copy(items = before.page.items + page.items) else page)) }
                cacheArtwork(page.items, page.shows)
            } catch (cancelled: CancellationException) {
                if (collectionJobs.value[collection.id] === thisJob) mutableCollections.update { it + (collection.id to before.copy(loading = false,
                    failure = if (store.state.value.settings.offlineOnly) AppFailure(FailureCode.OFFLINE_POLICY) else before.failure)) }
                throw cancelled
            } catch (error: Exception) {
                if (collectionJobs.value[collection.id] === thisJob) mutableCollections.update { it + (collection.id to before.copy(loading = false, failure = failureOf(error))) }
            } finally {
                collectionJobs.update { if (it[collection.id] === thisJob) it - collection.id else it }
            }
        }
        collectionJobs.update { it + (collection.id to job) }
        job.start()
    }

    private fun openShow(id: String) {
        navigate { it.open(AppDetail(DetailKind.SHOW, id)) }
        val show = store.state.value.shows[id] ?: return
        cacheArtwork(emptyList(), listOf(show))
        if (show.lastRefreshEpochMs == null && !store.state.value.settings.offlineOnly) refreshShow(id)
    }

    private fun refreshShow(id: String) {
        if (id in mutablePodcastDetails.value.refreshing) return
        mutablePodcastDetails.update { it.copy(refreshing = it.refreshing + id, failures = it.failures - id) }
        effects.launch {
            try { loadShow(id) }
            catch (cancelled: CancellationException) {
                if (store.state.value.settings.offlineOnly) mutablePodcastDetails.update { it.copy(failures = it.failures + (id to AppFailure(FailureCode.OFFLINE_POLICY))) }
                throw cancelled
            }
            catch (error: Exception) { mutablePodcastDetails.update { it.copy(failures = it.failures + (id to failureOf(error))) } }
            finally { mutablePodcastDetails.update { it.copy(refreshing = it.refreshing - id) } }
        }
    }

    private suspend fun loadShow(id: String) {
        val snapshot = store.state.value
        val show = snapshot.shows[id] ?: throw VeyraException(AppFailure(FailureCode.NOT_FOUND))
        val feed = online { podcastsSource.refreshFeed(show.feedUrl, show, snapshot.media.values.filter { it.showId == id }) }
        rememberPage(SourcePage(items = feed.episodes, shows = listOf(feed.show)))
        cacheArtwork(emptyList(), listOf(feed.show))
    }

    private fun refreshFollowedShows() {
        if (refreshJob?.isActive == true) return
        mutablePodcasts.update { it.copy(isRefreshing = true, failure = null) }
        refreshJob = effects.launch {
            try {
                for (id in store.state.value.followedShowIds) {
                    try { loadShow(id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { mutablePodcasts.update { it.copy(failure = failureOf(error)) } }
                }
            } catch (cancelled: CancellationException) {
                if (store.state.value.settings.offlineOnly) mutablePodcasts.update { it.copy(failure = AppFailure(FailureCode.OFFLINE_POLICY)) }
                throw cancelled
            } finally { mutablePodcasts.update { it.copy(isRefreshing = false) } }
        }
    }

    private fun addFeed(url: String) {
        if (feedJob?.isActive == true) return
        mutablePodcasts.update { it.copy(addFeed = AddPodcastFeedUiState(url, isSubmitting = true)) }
        feedJob = effects.launch {
            try {
                val feed = online { podcastsSource.refreshFeed(url) }
                rememberPage(SourcePage(items = feed.episodes, shows = listOf(feed.show)))
                store.update { it.copy(followedShowIds = it.followedShowIds + feed.show.id) }.getOrThrow()
                mutablePodcasts.update { it.copy(addFeed = null) }
                navigate { it.open(AppDetail(DetailKind.SHOW, feed.show.id)) }
                cacheArtwork(emptyList(), listOf(feed.show))
            } catch (cancelled: CancellationException) {
                mutablePodcasts.update { state ->
                    val feed = state.addFeed
                    if (feed != null && feed.url == url) state.copy(addFeed = feed.copy(isSubmitting = false,
                        failure = if (store.state.value.settings.offlineOnly) AppFailure(FailureCode.OFFLINE_POLICY) else feed.failure)) else state
                }
                throw cancelled
            } catch (error: Exception) {
                mutablePodcasts.update { it.copy(addFeed = AddPodcastFeedUiState(url, failure = failureOf(error))) }
            }
        }
    }

    private suspend fun rememberPage(page: SourcePage) {
        store.upsertCatalog(page.items, page.shows).getOrThrow()
    }

    private fun cacheArtwork(items: List<MediaItem>, shows: List<PodcastShow> = emptyList()) {
        if (store.state.value.settings.offlineOnly) return
        // Image metadata only. These jobs never acquire or play audio. A broken cover must not
        // replace a valid old image or turn successful catalog/audio work into a fatal error.
        (items.distinctBy { it.id }.map { suspend { artwork.cacheMedia(current(it)); Unit } } +
            shows.distinctBy { it.id }.map { show -> suspend { artwork.cacheShow(store.state.value.shows[show.id] ?: show); Unit } })
            .forEach { fetch -> effects.launch {
                try { artworkPermits.withPermit { online(fetch) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Honest monogram placeholder; no fake cover success. */ }
            } }
    }

    /** An active offline-policy change cancels the actual request coroutine, not just its spinner. */
    private suspend fun <T> online(block: suspend () -> T): T = coroutineScope {
        if (closed) throw VeyraException(AppFailure(FailureCode.INVALID_STATE))
        if (store.state.value.settings.offlineOnly) throw VeyraException(AppFailure(FailureCode.OFFLINE_POLICY))
        val owner = currentCoroutineContext()[Job]!!
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            combine(store.state.map { it.settings.offlineOnly }, closedState) { offline, closing -> offline || closing }
                .distinctUntilChanged().first { it }
            owner.cancel(CancellationException("media_execution_policy"))
        }
        try {
            currentCoroutineContext().ensureActive()
            block()
        } finally { observer.cancel() }
    }

    private suspend fun openExternal(url: String) = online {
        val safe = http.validateUrl(url)
        currentCoroutineContext().ensureActive()
        if (store.state.value.settings.offlineOnly) throw VeyraException(AppFailure(FailureCode.OFFLINE_POLICY))
        platform.openExternalUrl(safe)
    }

    private fun openExternalInBackground(url: String) {
        effects.launch {
            try { withTimeout(30_000) { openExternal(url) } }
            catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                fail(AppFailure(if (store.state.value.settings.offlineOnly) FailureCode.OFFLINE_POLICY else FailureCode.NETWORK_UNAVAILABLE))
            }
            catch (error: Exception) { report(error) }
        }
    }

    fun effectiveLanguage(value: LanguagePreference): LanguagePreference = when (value) {
        LanguagePreference.SYSTEM -> platform.systemLanguage.takeUnless { it == LanguagePreference.SYSTEM } ?: LanguagePreference.ENGLISH
        else -> value
    }

    private fun inform(text: TextKey) { mutableNotice.value = AppNotice(text = text) }
    private fun fail(failure: AppFailure) { mutableNotice.value = AppNotice(failure = failure) }
    private fun report(error: Exception) = fail(failureOf(error))
    private fun failureOf(error: Throwable) = when (error) {
        is VeyraException -> error.failure
        is IllegalArgumentException -> AppFailure(FailureCode.INVALID_STATE)
        else -> AppFailure(FailureCode.UNEXPECTED)
    }

    suspend fun close(): Unit = withContext(NonCancellable) {
        lifecycleLock.withLock {
            if (closed) {
                try { drainRejectedResources() }
                catch (error: Throwable) { if (closeFailure == null) closeFailure = failureOf(error) }
                closeFailure?.let { throw VeyraException(it) }
                return@withLock
            }
            closed = true
            actions.close()
            navigationWrites.close()
            // Stop app effects before their dependencies. Player/download owners have their own
            // completion barriers and close checkpointed state before Room is closed.
            var failure: AppFailure? = null
            suspend fun release(block: suspend () -> Unit) {
                try { block() } catch (error: Throwable) { if (failure == null) failure = failureOf(error) }
            }
            release { effectsLifetime.cancelAndJoin() }
            release { downloads.close() }
            release { playback.close() }
            // Also cover explicitly awaited resolver calls (such as verification clients) that
            // were admitted before closing but aren't children of the playback/download actors.
            release { activeAcquisitions.value.forEach { it.await() } }
            release { drainRejectedResources() }
            release { artwork.close() }
            release { platform.closeMediaRuntime() }
            release { platform.youtube.close() }
            release { http.close() }
            if (mutableInitialization.value is AppInitialization.Ready) {
                release { persistNavigationOrThrow() }
                release { store.flush().getOrThrow() }
                release { store.backup().getOrThrow() }
            }
            release { store.close() }
            release { lifetime.cancelAndJoin() }
            closeFailure = failure
            failure?.let { throw VeyraException(it) }
        }
    }
}

fun createAppContainer(platform: PlatformServices, parentScope: CoroutineScope): AppContainer = AppContainer(platform, parentScope)

private fun Set<String>.toggled(id: String): Set<String> = if (id in this) this - id else this + id
private fun mergeMedia(existing: Map<String, MediaItem>, items: List<MediaItem>): Map<String, MediaItem> = existing + items.associate { item ->
    item.id to item.copy(localArtworkPath = item.localArtworkPath ?: existing[item.id]?.localArtworkPath)
}
private fun mergePages(first: SourcePage, next: SourcePage) = SourcePage(
    items = (first.items + next.items).distinctBy { it.id },
    shows = (first.shows + next.shows).distinctBy { it.id },
    collections = (first.collections + next.collections).distinctBy { it.id },
    nextPageToken = next.nextPageToken,
    attribution = listOfNotNull(first.attribution, next.attribution).distinct().joinToString("\n").ifBlank { null },
    notice = next.notice ?: first.notice,
)
