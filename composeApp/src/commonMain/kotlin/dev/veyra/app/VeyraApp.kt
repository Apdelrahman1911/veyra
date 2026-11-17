package dev.veyra.app

import androidx.compose.foundation.layout.ime
import dev.veyra.nativecomponents.NativeBarButton
import dev.veyra.nativecomponents.NativeTab
import dev.veyra.nativecomponents.NativeTopBar
import dev.veyra.ui.music.LocalPlayerSheetTop
import dev.veyra.nativecomponents.NativeTabBar
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.veyra.generated.resources.Res
import dev.veyra.data.StoreStatus
import dev.veyra.model.*
import dev.veyra.playback.PlaybackState
import dev.veyra.source.SourcePage
import dev.veyra.ui.design.chromeBottom
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
    val playbackWait by container.playbackWait.collectAsState()
    val options by container.musicOptions.collectAsState()
    val search by container.search.collectAsState()
    val discovery by container.discovery.collectAsState()
    val collections by container.collections.collectAsState()
    val podcasts by container.podcasts.collectAsState()
    val podcastProgress by container.podcastProgress.collectAsState()
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
    CompositionLocalProvider(LocalDensity provides density, LocalRemoteArtworkAllowed provides false,
        dev.veyra.ui.music.LocalPlaybackWait provides playbackWait) {
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
            Surface(modifier.fillMaxSize().dismissKeyboardOnOutsideTap().testTag("veyra-app"), color = MaterialTheme.colorScheme.background) {
                BoxWithConstraints(Modifier.fillMaxSize().veyraAmbient()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).imePadding()
                    .focusRequester(backgroundFocus).onFocusChanged { focus ->
                        backgroundIsFocused = focus.isFocused
                        shellHasFocus = focus.hasFocus
                        if (!focus.isFocused) pressed = emptySet()
                    }.onPreviewKeyEvent { event ->
                        // A child may consume the key-up; release Escape here so the next press counts.
                        if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) pressed = pressed - event.key
                        // Back navigation sees Escape before focused children (an icon button's
                        // tooltip otherwise consumes it). Dialogs are separate windows and keep it.
                        if (event.key == Key.Escape && event.type == KeyEventType.KeyDown && navigation.canGoBack &&
                            !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) {
                            if (event.key !in pressed) { pressed = pressed + event.key; container.navigateBack() }
                            true
                        } else false
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
                        // A navigation change can remove the focused control (e.g. the Queue button
                        // when the queue opens). Its focus loss is reported a frame later, so check
                        // after it settles; otherwise Escape/shortcuts have no focused path at all.
                        androidx.compose.runtime.withFrameNanos { }
                        androidx.compose.runtime.withFrameNanos { }
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
                            ExpandablePlayerScaffold(
                                expanded = navigation.playerOpen,
                                hasPlayer = displayPlayer.hasCurrent,
                                dragEnabled = !navigation.queueOpen,
                                onExpandedChange = { open ->
                                    container.onMusicAction(if (open) MusicUiAction.OpenPlayer else MusicUiAction.ClosePlayer)
                                },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                compactPlayer = {
                                    MiniPlayer(snapshot, displayPlayer, container::onMusicAction, options = screenOptions,
                                        compactHeight = shortWindow, docked = true)
                                },
                                bottomBar = {
                                    if (!useRail && !navigation.queueOpen) AppBottomNavigation(navigation.destination,
                                        density.fontScale >= 1.5f || windowWidth < 360.dp, container::selectDestination)
                                    else if (displayPlayer.hasCurrent) Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.chromeBottom))
                                },
                                expandedPlayer = {
                                    if (navigation.queueOpen && sideQueue) Row(Modifier.fillMaxSize()) {
                                        FullPlayer(snapshot, displayPlayer, container::onMusicAction,
                                            Modifier.weight(1f), screenOptions)
                                        VerticalDivider()
                                        Column(Modifier.width(368.dp).fillMaxHeight()) {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                                VeyraIconButton(VeyraIcons.Close, strings.text(TextKey.Close), { container.navigateBack() })
                                            }
                                            QueuePanel(snapshot, displayPlayer, navigation.viewedQueue, container::onMusicAction,
                                                Modifier.weight(1f), screenOptions, bottomEdgeFree = true)
                                        }
                                    } else if (navigation.queueOpen) Column(Modifier.fillMaxSize()) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            VeyraIconButton(VeyraIcons.Close, strings.text(TextKey.Close), { container.navigateBack() })
                                        }
                                        QueuePanel(snapshot, displayPlayer, navigation.viewedQueue, container::onMusicAction,
                                            Modifier.weight(1f), screenOptions, bottomEdgeFree = true)
                                    } else FullPlayer(snapshot, displayPlayer, container::onMusicAction, options = screenOptions)
                                },
                            ) { _ ->
                                Column(Modifier.fillMaxSize()) {
                                    AppHeader(navigation.copy(playerOpen = false), navigation.headerTitle(snapshot, strings), settings.offlineOnly, container)
                                    if (mediaRuntime.phase != MediaRuntimePhase.READY &&
                                        (mediaRuntime.phase != MediaRuntimePhase.REQUIRED || navigation.destination == AppDestination.SETTINGS)) {
                                        MediaRuntimeIndicator(mediaRuntime, settings.offlineOnly, container::prepareMediaTools)
                                    }
                                    Row(Modifier.weight(1f).fillMaxWidth()) {
                                        Box(Modifier.weight(1f).fillMaxHeight()) {
                                            when {
                                                navigation.queueOpen && !sideQueue -> QueuePanel(snapshot, displayPlayer, navigation.viewedQueue,
                                                    container::onMusicAction, options = screenOptions, bottomEdgeFree = !displayPlayer.hasCurrent)
                                                else -> {
                                                    val detail = navigation.detail
                                                    when (detail?.kind) {
                                                        DetailKind.TRACK -> TrackDetailScreen(snapshot, detail.id, displayPlayer,
                                                            container::onMusicAction, options = screenOptions, showHeader = false)
                                                        DetailKind.EPISODE -> EpisodeDetailScreen(EpisodeDetailUiState(detail.id, snapshot,
                                                            currentBinding = displayPlayer.executionBinding?.takeIf { it.mediaId == detail.id },
                                                            downloads = screenDownloads, progressView = podcastProgress), container::onPodcastAction)
                                                        DetailKind.SHOW -> PodcastShowScreen(PodcastShowUiState(detail.id, snapshot,
                                                            options = podcastDetails.options[detail.id] ?: PodcastListOptions(),
                                                            isLoading = detail.id in podcastDetails.refreshing && snapshot.shows[detail.id]?.lastRefreshEpochMs == null,
                                                            isRefreshing = detail.id in podcastDetails.refreshing,
                                                            failure = podcastDetails.failures[detail.id],
                                                            downloads = screenDownloads,
                                                            progressView = podcastProgress), container::onPodcastAction)
                                                        DetailKind.PLAYLIST -> PlaylistDetailScreen(snapshot, detail.id, container::onMusicAction, options = screenOptions, playback = displayPlayer)
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
                                        if (sideQueue && !navigation.playerOpen) {
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
            ActionButton(strings.text(TextKey.RecoveryRestoreBackup), "arrow.counterclockwise", true, onClick = { confirm = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("recovery-restore")) {
                Text(strings.text(TextKey.RecoveryRestoreBackup))
            }
        }
    }
    if (confirm) com.mohamedrejeb.calf.ui.dialog.AdaptiveAlertDialog(
        onConfirm = { confirm = false; onRestore() },
        onDismiss = { confirm = false },
        confirmText = strings.text(TextKey.RecoveryRestoreBackup),
        dismissText = strings.text(TextKey.Cancel),
        title = strings.text(TextKey.RecoveryRestoreBackup),
        text = strings.text(TextKey.RecoveryBackupPrompt),
        modifier = Modifier.testTag("recovery-confirm-dialog"),
        materialTitle = { Text(strings.text(TextKey.RecoveryRestoreBackup)) },
        materialText = { Text(strings.text(TextKey.RecoveryBackupPrompt), modifier = Modifier.verticalScroll(rememberScrollState())) },
        materialConfirmButton = { TextButton(onClick = { confirm = false; onRestore() }, modifier = Modifier.testTag("recovery-confirm")) { Text(strings.text(TextKey.RecoveryRestoreBackup)) } },
        materialDismissButton = { TextButton(onClick = { confirm = false }, modifier = Modifier.testTag("recovery-cancel")) { Text(strings.text(TextKey.Cancel)) } },
        iosConfirmButtonStyle = com.mohamedrejeb.calf.ui.dialog.uikit.AlertDialogIosActionStyle.Destructive,
        iosDismissButtonStyle = com.mohamedrejeb.calf.ui.dialog.uikit.AlertDialogIosActionStyle.Cancel,
    )
}

private val primaryDestinations = listOf(AppDestination.LISTEN, AppDestination.SEARCH, AppDestination.PODCASTS, AppDestination.LIBRARY)
private fun AppDestination.label() = when (this) {
    AppDestination.LISTEN -> TextKey.Home
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

private fun AppDestination.symbol() = when (this) {
    AppDestination.LISTEN -> "house"
    AppDestination.SEARCH -> "magnifyingglass"
    AppDestination.PODCASTS -> "mic"
    AppDestination.LIBRARY -> "square.stack"
    AppDestination.SETTINGS -> "gearshape"
}

private fun AppDestination.selectedSymbol() = when (this) {
    AppDestination.SEARCH -> "magnifyingglass"
    else -> symbol() + ".fill"
}

@Composable
private fun AppBottomNavigation(selected: AppDestination, compactLabels: Boolean, select: (AppDestination) -> Unit) {
    val strings = LocalStrings.current
    // iOS: a real UITabBar. While the keyboard is up it stays at the bottom edge behind the keyboard,
    // as in iOS apps, so the bottom chrome reserves no room for it above the keyboard.
    val tabs = primaryDestinations.map { NativeTab(strings.text(it.label()), it.symbol(), it.selectedSymbol()) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    NativeTabBar(
        tabs = tabs,
        selectedIndex = primaryDestinations.indexOf(selected),
        onSelect = { index -> primaryDestinations.getOrNull(index)?.let(select) },
        selectedColor = MaterialTheme.colorScheme.primary,
        reserveSpace = !imeVisible,
    ) { MaterialBottomNavigation(selected, compactLabels, select) }
}

@Composable
private fun MaterialBottomNavigation(selected: AppDestination, compactLabels: Boolean, select: (AppDestination) -> Unit) {
    val strings = LocalStrings.current
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("bottom-navigation"),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.windowInsetsPadding(WindowInsets.chromeBottom)
            .padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            primaryDestinations.forEach { destination ->
                val isSelected = destination == selected
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(26.dp))
                        .selectable(isSelected, role = Role.Tab, onClick = { select(destination) })
                        .heightIn(min = 48.dp).padding(vertical = 3.dp).testTag("nav-${destination.name}"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(
                        Modifier.size(44.dp).then(if (isSelected) Modifier.background(VeyraAccentGradient, CircleShape)
                            else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(destination.icon(), if (compactLabels && !isSelected) strings.text(destination.label()) else null,
                            tint = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(23.dp))
                    }
                    // The label fills its slot so long/large Arabic labels wrap instead of overflowing.
                    if (!compactLabels || isSelected) Text(strings.text(destination.label()), Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                }
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

/**
 * The header names what is on screen: the open playlist, show, episode, track or collection, else
 * the destination. An item whose name is not known yet (or is blank) falls back to the destination.
 */
private fun AppNavigation.headerTitle(snapshot: AppSnapshot, strings: AppStrings): String {
    val destinationTitle = strings.text(destination.label())
    val detail = detail ?: return destinationTitle
    val name = when (detail.kind) {
        DetailKind.TRACK, DetailKind.EPISODE -> snapshot.media[detail.id]?.title
        DetailKind.SHOW -> snapshot.shows[detail.id]?.title
        DetailKind.PLAYLIST -> snapshot.playlists.firstOrNull { it.id == detail.id }?.name
        DetailKind.COLLECTION -> detail.collection?.title
        DetailKind.LICENSES -> strings.text(TextKey.Licenses)
    }
    return name?.takeIf { it.isNotBlank() } ?: destinationTitle
}

@Composable
private fun AppHeader(navigation: AppNavigation, title: String, offline: Boolean, container: AppContainer) {
    val strings = LocalStrings.current
    // iOS: a real UINavigationBar. UIKit draws it above Compose, so it steps aside as soon as the
    // player sheet slides over its area, and comes back when the sheet leaves it.
    val sheetTop = LocalPlayerSheetTop.current
    val uncovered by remember(sheetTop) { derivedStateOf { sheetTop.value > 72.dp } }
    val leading = if (navigation.canGoBack) listOf(NativeBarButton(strings.text(TextKey.Back), "chevron.backward") {
        container.navigateBack()
    }) else emptyList()
    val trailing = buildList {
        if (navigation.destination != AppDestination.SETTINGS) add(NativeBarButton(strings.text(TextKey.Settings), "gearshape") {
            container.selectDestination(AppDestination.SETTINGS)
        })
        if (offline) add(NativeBarButton(strings.text(TextKey.Offline), "icloud.slash", enabled = false) {})
    }
    NativeTopBar(
        title = title,
        leading = leading,
        trailing = trailing,
        titleColor = MaterialTheme.colorScheme.onSurface,
        tintColor = MaterialTheme.colorScheme.primary,
        visible = uncovered,
    ) { MaterialAppHeader(navigation, title, offline, container) }
}

@Composable
private fun MaterialAppHeader(navigation: AppNavigation, title: String, offline: Boolean, container: AppContainer) {
    val strings = LocalStrings.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (navigation.canGoBack) VeyraIconButton(VeyraIcons.Back, strings.text(TextKey.Back), { container.navigateBack() },
            Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = .65f), RoundedCornerShape(17.dp)).testTag("header-back"))
        else Box(Modifier.size(44.dp).background(VeyraAccentGradient, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Icon(VeyraIcons.Brand, null, Modifier.size(25.dp), tint = Color.White)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (!navigation.canGoBack) Text(strings.text(TextKey.HomeGreeting), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (offline) StatusBadge(strings.text(TextKey.Offline), icon = VeyraIcons.Download)
        if (navigation.destination != AppDestination.SETTINGS) VeyraIconButton(VeyraIcons.Settings, strings.text(TextKey.Settings),
            { container.selectDestination(AppDestination.SETTINGS) },
            Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = .65f), RoundedCornerShape(17.dp)))
    }
}

/** Legal texts are bundled and readable without DNS, a browser, an account, or a network. */
@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun LicensesScreen(readDocument: suspend (String) -> String = { Res.readBytes(it).decodeToString() }) {
    val strings = LocalStrings.current
    var documents by remember { mutableStateOf<List<Pair<String, String?>>?>(null) }
    LaunchedEffect(readDocument) {
        val paths = listOf("NOTICE.md", "LICENSE", "kmpYT-LICENSE", "kmpYT-THIRD_PARTY_NOTICES.md", "nativeCmpkit-LICENSE", "THIRD_PARTY_NOTICES.txt")
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
