package dev.veyra.app

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.veyra.generated.resources.Res
import dev.veyra.data.StoreStatus
import dev.veyra.model.*
import dev.veyra.playback.PlaybackState
import dev.veyra.source.SourcePage
import dev.veyra.ui.design.*
import dev.veyra.ui.downloads.downloadSurface
import dev.veyra.ui.music.*
import dev.veyra.ui.podcasts.*
import dev.veyra.ui.settings.SettingsScreen
import dev.veyra.ui.settings.applyTo
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** Adaptive shared UI; host ownership survives window/activity/controller recreation. */
@Composable
fun VeyraApp(container: AppContainer, modifier: Modifier = Modifier) {
    val snapshot by container.library.collectAsState()
    val initialization by container.initialization.collectAsState()
    val navigation by container.navigation.collectAsState()
    val player by container.playback.state.collectAsState()
    val options by container.musicOptions.collectAsState()
    val search by container.search.collectAsState()
    val discovery by container.discovery.collectAsState()
    val collections by container.collections.collectAsState()
    val podcasts by container.podcasts.collectAsState()
    val podcastDetails by container.podcastDetails.collectAsState()
    val storageBytes by container.storageBytes.collectAsState()
    val downloadPresentation by container.downloads.acquisitionPresentation.collectAsState()
    val screenDownloads = remember(snapshot, downloadPresentation) { downloadSurface(snapshot, downloadPresentation) }
    val screenOptions = remember(options, screenDownloads) { options.copy(downloads = screenDownloads) }
    val notice by container.notice.collectAsState()
    val storeStatus by container.store.status.collectAsState()
    val mediaRuntime by container.platform.mediaRuntimeStatus.collectAsState()
    val settings = snapshot.settings
    val language = container.effectiveLanguage(settings.language)
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.theme) { ThemeMode.SYSTEM -> systemDark; ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val deviceDensity = LocalDensity.current
    val density = remember(deviceDensity.density, deviceDensity.fontScale, settings.textScale) {
        Density(deviceDensity.density, deviceDensity.fontScale * settings.textScale)
    }
    CompositionLocalProvider(LocalDensity provides density, LocalRemoteArtworkAllowed provides false) {
        VeyraTheme(dark, language) {
            val strings = LocalStrings.current
            val snackbar = remember { SnackbarHostState() }
            val latestNotice = notice
            LaunchedEffect(latestNotice?.id, language) {
                latestNotice?.let { message ->
                    val text = message.failure?.let(strings::errorFor) ?: message.text?.let(strings::text) ?: return@let
                    snackbar.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Long)
                    container.dismissNotice(message.id)
                }
            }
            val displayPlayer = remember(player, snapshot.media) { player.withArtwork(snapshot) }
            var pressed by remember { mutableStateOf<Set<Key>>(emptySet()) }
            val backgroundFocus = remember { FocusRequester() }
            var backgroundIsFocused by remember { mutableStateOf(false) }
            var shellHasFocus by remember { mutableStateOf(false) }
            val windowIsFocused = LocalWindowInfo.current.isWindowFocused
            Surface(modifier.fillMaxSize().testTag("veyra-app"), color = MaterialTheme.colorScheme.background) {
                BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()
                    .focusRequester(backgroundFocus).onFocusChanged { focus ->
                        backgroundIsFocused = focus.isFocused
                        shellHasFocus = focus.hasFocus
                        if (!focus.isFocused) pressed = emptySet()
                    }.onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp) { pressed = pressed - event.key; return@onKeyEvent false }
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val action = when {
                        (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.K -> 1
                        (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.Comma -> 2
                        event.key == Key.Escape && navigation.canGoBack -> 3
                        backgroundIsFocused && event.key == Key.Spacebar && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed -> 4
                        else -> return@onKeyEvent false
                    }
                    if (event.key !in pressed) {
                        pressed = pressed + event.key
                        when (action) {
                            1 -> container.onMusicAction(MusicUiAction.OpenSearch())
                            2 -> container.selectDestination(AppDestination.SETTINGS)
                            3 -> container.navigateBack()
                            4 -> container.onMusicAction(MusicUiAction.TogglePlayPause)
                        }
                    }
                    true
                }.focusable()) {
                    // BoxWithConstraints composes its content during measure. Keep focus
                    // rehoming in the same subcomposition that replaces the focused subtree.
                    // Restore only an empty shell in its active window; surviving controls and
                    // separate modal layers own focus. Do not key this effect on focus loss.
                    LaunchedEffect(initialization, navigation, windowIsFocused) {
                        if (!windowIsFocused) pressed = emptySet()
                        if (initialization is AppInitialization.Ready && windowIsFocused && !shellHasFocus) {
                            backgroundFocus.requestFocus()
                        }
                    }
                    // Capture this scope before entering Row/Column layout receivers.
                    val windowWidth = maxWidth
                    val shortWindow = maxHeight < 480.dp
                    val expanded = windowWidth >= 1080.dp && density.fontScale < 1.7f
                    val useRail = windowWidth >= 680.dp || (windowWidth >= 560.dp && shortWindow)
                    val sideQueue = windowWidth >= 1140.dp && density.fontScale < 1.7f && navigation.queueOpen
                    val initial = initialization
                    if (initial !is AppInitialization.Ready) {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            when (initial) {
                                is AppInitialization.Failed -> StartupFailure(initial.failure,
                                    canRecover = (storeStatus as? StoreStatus.Failed)?.failure?.code == FailureCode.RECOVERY_REQUIRED,
                                    onRestore = container::restoreLibraryBackup)
                                else -> LoadingState()
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxSize()) {
                            if (useRail) AppNavigationRail(navigation.destination, expanded, shortWindow, container::selectDestination)
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                if (!navigation.playerOpen || navigation.queueOpen && !sideQueue) AppHeader(navigation, settings.offlineOnly, container)
                                if (mediaRuntime.phase != MediaRuntimePhase.READY &&
                                    (mediaRuntime.phase != MediaRuntimePhase.REQUIRED || navigation.destination == AppDestination.SETTINGS)) {
                                    MediaRuntimeIndicator(mediaRuntime, settings.offlineOnly, container::prepareMediaTools)
                                }
                                Row(Modifier.weight(1f).fillMaxWidth()) {
                                    Box(Modifier.weight(1f).fillMaxHeight()) {
                                        when {
                                            navigation.queueOpen && !sideQueue -> QueuePanel(snapshot, displayPlayer, navigation.viewedQueue,
                                                container::onMusicAction, options = screenOptions)
                                            navigation.playerOpen -> FullPlayer(snapshot, displayPlayer, container::onMusicAction, options = screenOptions)
                                            else -> {
                                                val detail = navigation.detail
                                                when (detail?.kind) {
                                                    DetailKind.TRACK -> TrackDetailScreen(snapshot, detail.id, displayPlayer,
                                                        container::onMusicAction, options = screenOptions)
                                                    DetailKind.EPISODE -> EpisodeDetailScreen(EpisodeDetailUiState(detail.id, snapshot,
                                                        currentBinding = displayPlayer.executionBinding?.takeIf { it.mediaId == detail.id },
                                                        downloads = screenDownloads), container::onPodcastAction)
                                                    DetailKind.SHOW -> PodcastShowScreen(PodcastShowUiState(detail.id, snapshot,
                                                        options = podcastDetails.options[detail.id] ?: PodcastListOptions(),
                                                        isLoading = detail.id in podcastDetails.refreshing && snapshot.shows[detail.id]?.lastRefreshEpochMs == null,
                                                        isRefreshing = detail.id in podcastDetails.refreshing,
                                                        failure = podcastDetails.failures[detail.id],
                                                        downloads = screenDownloads), container::onPodcastAction)
                                                    DetailKind.PLAYLIST -> PlaylistDetailScreen(snapshot, detail.id, container::onMusicAction, options = screenOptions)
                                                    DetailKind.COLLECTION -> detail.collection?.let { collection ->
                                                        val state = collections[detail.id] ?: CollectionUiState(collection)
                                                        CollectionDetailScreen(snapshot, state.copy(page = state.page.withArtwork(snapshot)),
                                                            container::onMusicAction, options = screenOptions)
                                                    }
                                                    DetailKind.LICENSES -> LicensesScreen()
                                                    null -> when (navigation.destination) {
                                                        AppDestination.LISTEN -> ListenScreen(snapshot, displayPlayer,
                                                            discovery.copy(page = discovery.page.withArtwork(snapshot)), container::onMusicAction, options = screenOptions)
                                                        AppDestination.SEARCH -> SearchScreen(snapshot,
                                                            search.copy(page = search.page.withArtwork(snapshot)), container::onMusicAction, options = screenOptions)
                                                        AppDestination.PODCASTS -> PodcastsScreen(podcasts.copy(snapshot = snapshot, downloads = screenDownloads), container::onPodcastAction)
                                                        AppDestination.LIBRARY -> LibraryScreen(snapshot, navigation.librarySection, container::onMusicAction, options = screenOptions)
                                                        AppDestination.SETTINGS -> SettingsScreen(settings, container.platform.engine.capabilities, storageBytes,
                                                            onSettingsChange = { action -> container.changeSettings { action.applyTo(it) } },
                                                            onManageStorage = { container.onMusicAction(MusicUiAction.OpenLibrary(LibrarySection.DOWNLOADS)) },
                                                            onOpenLicenses = container::openLicenses)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (sideQueue) {
                                        VerticalDivider()
                                        Column(Modifier.width(368.dp).fillMaxHeight()) {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                                VeyraIconButton(VeyraIcons.Close, strings.text(TextKey.Close), { container.navigateBack() })
                                            }
                                            QueuePanel(snapshot, displayPlayer, navigation.viewedQueue, container::onMusicAction,
                                                Modifier.weight(1f), screenOptions)
                                        }
                                    }
                                }
                                if (!navigation.playerOpen && displayPlayer.currentItem != null) {
                                    MiniPlayer(snapshot, displayPlayer, container::onMusicAction, options = screenOptions,
                                        compactHeight = shortWindow)
                                }
                                if (!useRail && !navigation.playerOpen && !navigation.queueOpen) {
                                    AppBottomNavigation(navigation.destination, density.fontScale >= 1.5f || windowWidth < 360.dp,
                                        container::selectDestination)
                                }
                            }
                        }
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 640.dp))
                }
            }
        }
    }
}

@Composable
private fun StartupFailure(failure: AppFailure, canRecover: Boolean, onRestore: () -> Unit) {
    val strings = LocalStrings.current
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ErrorState(strings.errorFor(failure))
        if (canRecover) {
            Text(strings.text(TextKey.RecoveryBackupPrompt), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { confirm = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("recovery-restore")) {
                Text(strings.text(TextKey.RecoveryRestoreBackup))
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        modifier = Modifier.testTag("recovery-confirm-dialog"),
        title = { Text(strings.text(TextKey.RecoveryRestoreBackup)) },
        text = { Text(strings.text(TextKey.RecoveryBackupPrompt), modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { confirm = false; onRestore() }, modifier = Modifier.testTag("recovery-confirm")) { Text(strings.text(TextKey.RecoveryRestoreBackup)) } },
        dismissButton = { TextButton(onClick = { confirm = false }, modifier = Modifier.testTag("recovery-cancel")) { Text(strings.text(TextKey.Cancel)) } },
    )
}

private val primaryDestinations = listOf(AppDestination.LISTEN, AppDestination.SEARCH, AppDestination.PODCASTS, AppDestination.LIBRARY)
private fun AppDestination.label() = when (this) {
    AppDestination.LISTEN -> TextKey.Listen
    AppDestination.SEARCH -> TextKey.Search
    AppDestination.PODCASTS -> TextKey.Podcasts
    AppDestination.LIBRARY -> TextKey.Library
    AppDestination.SETTINGS -> TextKey.Settings
}
private fun AppDestination.icon() = when (this) {
    AppDestination.LISTEN -> VeyraIcons.Listen
    AppDestination.SEARCH -> VeyraIcons.Search
    AppDestination.PODCASTS -> VeyraIcons.Podcasts
    AppDestination.LIBRARY -> VeyraIcons.Library
    AppDestination.SETTINGS -> VeyraIcons.Settings
}

@Composable
private fun AppNavigationRail(selected: AppDestination, expanded: Boolean, short: Boolean, select: (AppDestination) -> Unit) {
    val strings = LocalStrings.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.width(if (expanded) 236.dp else 104.dp).fillMaxHeight().padding(horizontal = 12.dp, vertical = if (short) 4.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!short) {
                Icon(VeyraIcons.Brand, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                if (expanded) Text(strings.text(TextKey.AppName), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                primaryDestinations.forEach { destination ->
                    if (expanded) NavigationDrawerItem(
                    label = { Text(strings.text(destination.label()), Modifier.fillMaxWidth()) }, selected = destination == selected,
                    onClick = { select(destination) }, icon = { Icon(destination.icon(), null) },
                    modifier = Modifier.heightIn(min = 56.dp).testTag("nav-${destination.name}"),
                    ) else NavigationRailItem(
                    selected = destination == selected, onClick = { select(destination) },
                    icon = { Icon(destination.icon(), strings.text(destination.label())) },
                    label = { NavigationLabel(destination) }, alwaysShowLabel = false,
                    modifier = Modifier.testTag("nav-${destination.name}"),
                    )
                }
            }
            if (expanded && !short) Text(strings.text(TextKey.Tagline), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            VeyraIconButton(VeyraIcons.Settings, strings.text(TextKey.Settings), { select(AppDestination.SETTINGS) },
                selected = selected == AppDestination.SETTINGS)
        }
    }
}

@Composable
private fun AppBottomNavigation(selected: AppDestination, compactLabels: Boolean, select: (AppDestination) -> Unit) {
    val strings = LocalStrings.current
    val hasSelectedPrimary = selected in primaryDestinations
    NavigationBar {
        primaryDestinations.forEach { destination ->
            val isSelected = destination == selected
            // Material3's item imposes weight(1f) internally. Give each item its own Row
            // rather than shrinking or clipping the selected Arabic/large-text label.
            // In compact mode inactive icons retain 48dp targets; the selected label gets
            // the remaining width. The slots still follow the app's RTL direction.
            val slot = if (compactLabels && hasSelectedPrimary && !isSelected) Modifier.width(48.dp)
                else Modifier.weight(1f)
            Row(slot) {
                NavigationBarItem(selected = isSelected, onClick = { select(destination) },
                    icon = { Icon(destination.icon(), strings.text(destination.label())) },
                    label = if (!compactLabels || isSelected) ({ NavigationLabel(destination) }) else null,
                    modifier = Modifier.testTag("nav-${destination.name}"))
            }
        }
    }
}

@Composable
private fun NavigationLabel(destination: AppDestination) {
    // The label owns its bounded slot, not only the natural width of a short word. This
    // keeps centered/wrapped text and its semantics paragraph on the same constraints;
    // it does not shrink text, cap lines or hide overflow at large Arabic text sizes.
    Text(LocalStrings.current.text(destination.label()), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
}

@Composable
private fun AppHeader(navigation: AppNavigation, offline: Boolean, container: AppContainer) {
    val strings = LocalStrings.current
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (navigation.canGoBack) VeyraIconButton(VeyraIcons.Back, strings.text(TextKey.Back), { container.navigateBack() })
            else Icon(VeyraIcons.Brand, null, Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Text(strings.text(TextKey.AppName), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (offline) StatusBadge(strings.text(TextKey.Offline), icon = VeyraIcons.Download)
            VeyraIconButton(VeyraIcons.Settings, strings.text(TextKey.Settings), { container.selectDestination(AppDestination.SETTINGS) })
        }
    }
}

/** Legal texts are bundled and readable without DNS, a browser, an account, or a network. */
@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun LicensesScreen(readDocument: suspend (String) -> String = { Res.readBytes(it).decodeToString() }) {
    val strings = LocalStrings.current
    var documents by remember { mutableStateOf<List<Pair<String, String?>>?>(null) }
    LaunchedEffect(readDocument) {
        val paths = listOf("NOTICE.md", "LICENSE", "kmpYT-LICENSE", "kmpYT-THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.txt")
            .map { "files/legal/$it" } + "files/licenses/ibm_plex_sans_arabic_ofl.txt"
        documents = paths.map { path ->
            val body = try { readDocument(path) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            path.substringAfterLast('/') to body
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("licenses-screen"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(strings.text(TextKey.Licenses), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() }) }
        item { Text(strings.text(TextKey.LegalOriginalTerms), style = MaterialTheme.typography.bodyMedium) }
        if (documents == null) item { LoadingState() }
        items(documents.orEmpty(), key = { it.first }) { (name, body) ->
            Column(Modifier.testTag("legal-document-$name"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MetadataText(name, style = MaterialTheme.typography.titleMedium)
                if (body == null) ErrorState(strings.text(TextKey.InformationUnavailable), modifier = Modifier.testTag("legal-unavailable-$name"))
                else SelectionContainer { MetadataText(body, style = MaterialTheme.typography.bodySmall, paragraph = true) }
            }
        }
    }
}

private fun SourcePage.withArtwork(snapshot: AppSnapshot) = copy(
    items = items.map { item -> item.copy(localArtworkPath = snapshot.media[item.id]?.localArtworkPath ?: item.localArtworkPath) },
    shows = shows.map { show -> show.copy(localArtworkPath = snapshot.shows[show.id]?.localArtworkPath ?: show.localArtworkPath) },
)

private fun PlaybackState.withArtwork(snapshot: AppSnapshot) = copy(
    currentItem = currentItem?.let { it.copy(localArtworkPath = snapshot.media[it.id]?.localArtworkPath ?: it.localArtworkPath) },
    crossfade = crossfade?.let { fade -> fade.copy(incomingItem = fade.incomingItem?.let { item -> item.copy(
        localArtworkPath = snapshot.media[item.id]?.localArtworkPath ?: item.localArtworkPath) }) },
)
