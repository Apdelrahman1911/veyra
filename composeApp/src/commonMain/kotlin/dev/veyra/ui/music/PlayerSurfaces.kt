package dev.veyra.ui.music

import dev.veyra.playback.AudioOrigin
import dev.veyra.ui.design.bottomChrome
import dev.veyra.ui.design.SegmentedTabs
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import dev.veyra.ui.design.LocalVeyraColors
import androidx.compose.ui.semantics.Role
import com.mohamedrejeb.calf.ui.gesture.adaptiveClickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.BorderStroke
import dev.veyra.ui.design.ActionButton
import com.mohamedrejeb.calf.ui.dialog.uikit.AlertDialogIosAction
import com.mohamedrejeb.calf.ui.dialog.uikit.AlertDialogIosActionStyle
import com.mohamedrejeb.calf.ui.dialog.uikit.AlertDialogIosProperties
import com.mohamedrejeb.calf.ui.dialog.uikit.AlertDialogIosStyle
import dev.veyra.nativecomponents.NativeAlert
import dev.veyra.nativecomponents.NativeBarButton
import dev.veyra.nativecomponents.NativeToolbar
import dev.veyra.nativecomponents.nativeToolbarAvailable
import dev.veyra.nativecomponents.nativeToolbarClearance
import dev.veyra.nativecomponents.NativeButton
import dev.veyra.nativecomponents.NativeButtonStyle
import dev.veyra.nativecomponents.NativeDurationPicker
import dev.veyra.nativecomponents.NativeSheet
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import dev.veyra.ui.design.chromeBottom
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.veyra.model.AppSnapshot
import dev.veyra.model.PlaybackControlsMode
import dev.veyra.model.QueueContext
import dev.veyra.model.RepeatMode
import dev.veyra.playback.PlaybackPhase
import dev.veyra.playback.PlaybackState
import dev.veyra.playback.CrossfadePhase
import dev.veyra.playback.CrossfadeState
import dev.veyra.playback.SleepTimerMode
import dev.veyra.playback.SleepTimerState
import dev.veyra.ui.design.veyraAmbient
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.veyra.ui.design.Artwork
import dev.veyra.ui.design.EmptyState
import dev.veyra.ui.design.ErrorState
import dev.veyra.ui.design.LocalStrings
import dev.veyra.ui.design.MediaDirection
import dev.veyra.ui.design.MetadataText
import dev.veyra.ui.design.QuantityKey
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraIcons
import com.mohamedrejeb.calf.ui.slider.AdaptiveSlider
import dev.veyra.nativecomponents.LocalNativeControlAccessibility
import dev.veyra.nativecomponents.NativeControlAccessibility

/** Always describes the audible session, never the queue that happens to be inspected. */
@Composable
fun MiniPlayer(
    snapshot: AppSnapshot,
    playback: PlaybackState,
    onAction: (MusicUiAction) -> Unit,
    modifier: Modifier = Modifier,
    options: MusicUiOptions = MusicUiOptions(),
    compactHeight: Boolean = false,
    docked: Boolean = false,
) {
    // The owner-selected occurrence is the identity. A neutral source recording has no
    // MediaItem and must still reach the same player surfaces.
    val identity = playback.surfaceIdentity(snapshot) ?: return
    val strings = LocalStrings.current
    // One line each at normal sizes keeps the bar a calm, fixed-height strip. Large text gets a
    // second line instead of truncating most of a title; short panes always keep one line.
    // Both identities remain visible here and complete through the OpenPlayer action.
    val largeText = LocalDensity.current.fontScale >= 1.3f
    val metadataLines = if (compactHeight || !largeText) 1 else 2
    var timerDialog by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(if (compactHeight) 20.dp else 26.dp)
    // A floating card that sits with the tab bar rather than a slab across the screen.
    Surface(modifier = modifier.fillMaxWidth()
        .padding(horizontal = if (docked) 20.dp else 12.dp, vertical = 6.dp).testTag("mini-player"),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, LocalVeyraColors.current.outlineSubtle.copy(alpha = .7f)),
        shadowElevation = 10.dp) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).heightIn(min = 52.dp)
                        .adaptiveClickable(role = Role.Button, shape = RoundedCornerShape(20.dp)) { onAction(MusicUiAction.OpenPlayer) }
                        .testTag("mini-open-player").padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(identity.artworkUrl, identity.title.orEmpty(), Modifier.size(if (compactHeight) 40.dp else 46.dp), offlineOnly = snapshot.settings.offlineOnly, localPath = identity.localArtworkPath)
                    Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        MetadataText(identity.title ?: strings.text(TextKey.InformationUnavailable), Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface, maxLines = metadataLines, overflow = TextOverflow.Ellipsis)
                        val status = when {
                            playback.failure != null -> strings.errorFor(playback.failure!!)
                            playback.isBuffering -> playbackStatusLabel(playback, strings)
                            playback.crossfade?.phase == CrossfadePhase.OVERLAPPING -> strings.text(TextKey.CrossfadeActive)
                            else -> null
                        }
                        if (identity.resolvedTitle != null && identity.resolvedTitle != identity.title) {
                            Text(strings.format(TextKey.ResolvedTitleValue, identity.resolvedTitle), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = metadataLines, overflow = TextOverflow.Ellipsis)
                        } else if (status == null && !identity.creator.isNullOrBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                            if (playback.audioOrigin == AudioOrigin.DOWNLOADED_FILE) DownloadedMark()
                            MetadataText(identity.creator!!, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        status?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall,
                                color = if (playback.failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                maxLines = metadataLines, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                MediaDirection {
                    PlayPauseButton(playback, onAction, Modifier.testTag("mini-toggle"))
                }
                ActionIcon(
                    VeyraIcons.Queue,
                    strings.text(TextKey.ShowQueue),
                    { onAction(MusicUiAction.OpenQueue(playback.activeContext)) },
                    Modifier.testTag("mini-queue"),
                )
            }
            playback.sleepTimer?.let { timer ->
                TimerStatus(timer, onOpen = { timerDialog = true }, onCancel = { onAction(MusicUiAction.ClearSleepTimer) }, modifier = Modifier.padding(horizontal = 8.dp).testTag("mini-timer"))
            }
            // A slim inset line inside the card, the same direction as the player's timeline.
            val progressModifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 6.dp)
                .height(3.dp).clip(CircleShape).clearAndSetSemantics { }
            if (playback.isBuffering) LinearProgressIndicator(progressModifier,
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f))
            else if ((playback.durationMs ?: 0L) > 0L) MediaDirection {
                val position = livePosition(playback)
                LinearProgressIndicator(progress = { seekFraction(position(), playback.durationMs) }, modifier = progressModifier,
                    color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f),
                    strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
            }
        }
    }
    if (timerDialog) SleepTimerDialog(identity, playback, onAction, onDismiss = { timerDialog = false })
}

/** Says plainly whether the audio is the saved file on this device or an online stream. */
@Composable
private fun AudioOriginBadge(origin: AudioOrigin) {
    val strings = LocalStrings.current
    val downloaded = origin == AudioOrigin.DOWNLOADED_FILE
    Surface(shape = CircleShape, color = if (downloaded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (downloaded) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("audio-origin-${origin.name}")) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (downloaded) VeyraIcons.Download else VeyraIcons.Volume, null, Modifier.size(14.dp))
            Text(strings.text(if (downloaded) TextKey.PlayingFromDownload else TextKey.PlayingOnline), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** A small mark in the mini player for audio played from the downloaded file. */
@Composable
private fun DownloadedMark() {
    val strings = LocalStrings.current
    Icon(VeyraIcons.Download, strings.text(TextKey.PlayingFromDownload), Modifier.padding(end = 4.dp).size(13.dp).testTag("mini-downloaded"),
        tint = MaterialTheme.colorScheme.primary)
}

/** Layout adapts to its pane, rather than assuming a desktop window or a portrait phone. */
@Composable
fun FullPlayer(
    snapshot: AppSnapshot,
    playback: PlaybackState,
    onAction: (MusicUiAction) -> Unit,
    modifier: Modifier = Modifier,
    options: MusicUiOptions = MusicUiOptions(),
) {
    val strings = LocalStrings.current
    val identity = playback.surfaceIdentity(snapshot)
    var timerDialog by remember { mutableStateOf(false) }
    var speedDialog by remember(playback.currentEntryId) { mutableStateOf(false) }
    Surface(modifier.fillMaxSize().veyraAmbient().testTag("full-player"),
        color = androidx.compose.ui.graphics.Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
        Box(Modifier.fillMaxSize()) {
            // The cover itself, softly blurred, tints the whole player; a scrim keeps text legible.
            if (identity != null) {
                Artwork(identity.artworkUrl, identity.title.orEmpty(), Modifier.fillMaxSize().blur(72.dp).alpha(.55f),
                    offlineOnly = snapshot.settings.offlineOnly, localPath = identity.localArtworkPath)
                val canvas = MaterialTheme.colorScheme.background
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    listOf(canvas.copy(alpha = .35f), canvas.copy(alpha = .7f), canvas.copy(alpha = .94f)))))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.padding(top = 8.dp, bottom = 2.dp).size(width = 40.dp, height = 5.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f), CircleShape)
                    .testTag("player-drag-handle").clearAndSetSemantics { })
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(VeyraIcons.Collapse, strings.text(TextKey.ClosePlayer), { onAction(MusicUiAction.ClosePlayer) }, Modifier.testTag("close-player"),
                        contentColor = MaterialTheme.colorScheme.onSurface)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(strings.text(TextKey.NowPlaying), Modifier.semantics { heading() },
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (identity != null) Text(strings.text(queueLabel(playback.activeContext)),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ActionIcon(VeyraIcons.Queue, strings.text(TextKey.ShowQueue),
                        { onAction(MusicUiAction.OpenQueue(playback.activeContext)) }, Modifier.testTag("player-queue-top"),
                        contentColor = MaterialTheme.colorScheme.onSurface)
                }
                if (identity == null) {
                    EmptyState(strings.text(TextKey.NothingPlaying), strings.text(TextKey.NothingPlayingDescription), strings.text(TextKey.Search), { onAction(MusicUiAction.OpenSearch(SearchKind.ALL)) }, Modifier.fillMaxWidth())
                } else {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        // The measured space left above transport, not window orientation, decides
                        // when decoration yields to both listing and executed-recording identities.
                        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                        val compactIdentity = maxHeight < 420.dp || fontScale >= 1.5f
                        val identityLines = if (maxHeight < (180f * fontScale).dp) 1 else 2
                        val split = !compactIdentity && maxWidth >= (720f * fontScale).dp
                        val cover = Modifier.shadow(28.dp, RoundedCornerShape(28.dp), clip = false,
                            ambientColor = MaterialTheme.colorScheme.primary, spotColor = MaterialTheme.colorScheme.primary)
                        Column(
                            Modifier.fillMaxWidth().testTag("player-details").verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp, vertical = if (compactIdentity) 8.dp else 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (split) Row(Modifier.widthIn(max = 1_120.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Artwork(identity.artworkUrl, identity.title.orEmpty(), Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f).then(cover), offlineOnly = snapshot.settings.offlineOnly, localPath = identity.localArtworkPath)
                                }
                                PlayerDetails(identity, snapshot, playback, onAction, options, { timerDialog = true }, { speedDialog = true }, Modifier.weight(1f))
                            } else {
                                if (!compactIdentity) {
                                    Artwork(identity.artworkUrl, identity.title.orEmpty(), Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f).then(cover), offlineOnly = snapshot.settings.offlineOnly, localPath = identity.localArtworkPath)
                                    Spacer(Modifier.height(28.dp))
                                }
                                PlayerDetails(identity, snapshot, playback, onAction, options, { timerDialog = true }, { speedDialog = true },
                                    Modifier.widthIn(max = 560.dp).fillMaxWidth(), compactIdentityLines = if (compactIdentity) identityLines else null)
                            }
                        }
                    }
                    // Essential pause/seek/skip never sit below an arbitrarily long receipt or
                    // creator name. Resizing and reading details must not hide the active transport.
                    PlayerTransport(identity, snapshot, playback, onAction)
                }
            }
        }
    }
    if (timerDialog && identity != null) SleepTimerDialog(identity, playback, onAction, onDismiss = { timerDialog = false })
    if (speedDialog && identity?.controlsMode == PlaybackControlsMode.PODCAST) SpeedDialog(identity, snapshot, playback, options, onAction, onDismiss = { speedDialog = false })
}

/** A labelled round tool under the title: an icon in a soft circle with a short caption. */
@Composable
private fun PlayerTool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, active: Boolean = false, enabled: Boolean = true, stateLabel: String? = null) {
    Column(modifier.widthIn(min = 64.dp).clip(RoundedCornerShape(16.dp))
        .adaptiveClickable(role = Role.Button, enabled = enabled, shape = RoundedCornerShape(16.dp), onClick = onClick)
        .semantics(mergeDescendants = true) { stateLabel?.let { stateDescription = it } }
        .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(44.dp).background(if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = .07f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(21.dp), tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
                active -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurface
            })
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerDetails(
    identity: PlaybackSurfaceIdentity,
    snapshot: AppSnapshot,
    playback: PlaybackState,
    onAction: (MusicUiAction) -> Unit,
    options: MusicUiOptions,
    openTimer: () -> Unit,
    openSpeed: () -> Unit,
    modifier: Modifier,
    compactIdentityLines: Int? = null,
) {
    val strings = LocalStrings.current
    val podcast = identity.controlsMode == PlaybackControlsMode.PODCAST
    val compactIdentity = compactIdentityLines != null
    val catalogItem = identity.catalogItem
    val title = identity.title ?: strings.text(TextKey.InformationUnavailable)
    // Source details stay folded, except when the playing recording differs from the one
    // requested: D005 requires that difference to be disclosed, not hidden behind a toggle.
    val differs = identity.provenance?.let { it.requestedTitle != null && it.requestedTitle != it.resolvedTitle } == true
    var audioDetails by remember(playback.currentEntryId, differs) { mutableStateOf(differs) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compactIdentity) 8.dp else 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MetadataText(title, Modifier.fillMaxWidth().testTag("player-title"),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = compactIdentityLines ?: 3, overflow = TextOverflow.Ellipsis)
                identity.creator?.takeIf { it.isNotBlank() }?.let { creator ->
                    MetadataText(creator, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (catalogItem != null) Row(verticalAlignment = Alignment.CenterVertically) {
                val saved = snapshot.isSaved(catalogItem)
                ActionIcon(savedIcon(catalogItem, saved), strings.text(savedActionLabel(catalogItem, saved)), { onAction(MusicUiAction.ToggleFavorite(catalogItem)) },
                    Modifier.testTag("player-save"), isSelected = saved,
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                MediaActionsMenu(catalogItem, snapshot, onAction, options = options)
            }
        }
        if (compactIdentity) {
            // This preview describes only the current owner's receipt, never a latest library
            // lookup. Full immutable query/result titles remain in the disclosure below.
            identity.resolvedTitle?.let { resolved ->
                Text(strings.format(TextKey.ResolvedTitleValue, resolved),
                    Modifier.fillMaxWidth().testTag("player-resolved-title"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = compactIdentityLines ?: 1,
                    overflow = TextOverflow.Ellipsis)
            }
            identity.listingProvider?.let { provider ->
                Text(strings.format(TextKey.OriginalMetadataSource, providerName(provider)), Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 26.dp)) {
                // Online preparation can take several seconds; a visible spinner shows the app is working.
                if (playback.isBuffering) com.mohamedrejeb.calf.ui.progress.AdaptiveCircularProgressIndicator(
                    Modifier.padding(end = 8.dp).size(14.dp).clearAndSetSemantics { },
                    strokeWidth = 2.dp,
                )
                Text(playbackStatusLabel(playback, strings), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            playback.audioOrigin?.let { AudioOriginBadge(it) }
        }
        playback.failure?.let { failure ->
            ErrorState(strings.errorFor(failure), onRetry = { onAction(MusicUiAction.TogglePlayPause) }, modifier = Modifier.fillMaxWidth().testTag("player-error").semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (identity.resolvedTitle != null && identity.resolvedTitle != identity.title && !compactIdentity) {
            Text(strings.format(TextKey.ResolvedTitleValue, identity.resolvedTitle), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // One calm row of tools instead of large buttons: each is a round icon with a caption.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (podcast) {
                val speed = strings.format(TextKey.SpeedValue, strings.localizeDigits(speedText(playback.effectiveSpeed)))
                PlayerTool(VeyraIcons.Refresh, speed, openSpeed, Modifier.testTag("player-speed"),
                    active = playback.effectiveSpeed != 1f, enabled = availableSpeeds(options.minSpeed, options.maxSpeed).isNotEmpty())
                PlayerTool(VeyraIcons.Next, strings.text(TextKey.NextEpisode), { onAction(MusicUiAction.Next) },
                    Modifier.testTag("player-next-episode"), enabled = playback.hasNextOccurrence(snapshot))
            }
            PlayerTool(VeyraIcons.Timer, strings.text(TextKey.SleepTimer), openTimer, Modifier.testTag("player-sleep"),
                active = playback.sleepTimer != null)
            PlayerTool(VeyraIcons.Info, strings.text(TextKey.AudioDetails), { audioDetails = !audioDetails },
                Modifier.testTag("player-audio-details"), active = audioDetails,
                stateLabel = strings.text(if (audioDetails) TextKey.ShowLess else TextKey.ShowMore))
            PlayerTool(VeyraIcons.Queue, strings.text(TextKey.Queue), { onAction(MusicUiAction.OpenQueue(playback.activeContext)) },
                Modifier.testTag("player-queue"))
        }
        playback.sleepTimer?.let { TimerStatus(it, openTimer, { onAction(MusicUiAction.ClearSleepTimer) }, Modifier.testTag("player-timer")) }
        playback.crossfade?.let { CrossfadeStatus(it) }
        if (audioDetails) PlaybackProvenanceCard(view = identity.provenance)
    }
}

@Composable
private fun PlayerTransport(identity: PlaybackSurfaceIdentity, snapshot: AppSnapshot, playback: PlaybackState, onAction: (MusicUiAction) -> Unit) {
    val strings = LocalStrings.current
    val podcast = identity.controlsMode == PlaybackControlsMode.PODCAST
    val facts = playback.activeQueueFacts(snapshot)
    Column(Modifier.fillMaxWidth().testTag("player-transport").windowInsetsPadding(WindowInsets.chromeBottom)
        .padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PlayerSeek(playback, onAction)
            MediaDirection {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (podcast) Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally) else Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    if (!podcast) ActionIcon(VeyraIcons.Shuffle, strings.text(if (facts.shuffle) TextKey.ShuffleOn else TextKey.ShuffleOff),
                        { onAction(MusicUiAction.SetShuffle(!facts.shuffle)) }, Modifier.testTag("player-shuffle"), isSelected = facts.shuffle,
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        contentColor = if (facts.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, iconSize = 22.dp)
                    if (podcast) SkipButton(-snapshot.settings.skipBackSeconds.toLong() * 1_000L, strings.format(TextKey.SkipBackSeconds, strings.number(snapshot.settings.skipBackSeconds.toLong())), VeyraIcons.SkipBack, playback, onAction, "player-skip-back")
                    else ActionIcon(VeyraIcons.Previous, strings.text(TextKey.Previous), { onAction(MusicUiAction.Previous) }, Modifier.size(60.dp).testTag("player-previous"),
                        contentColor = MaterialTheme.colorScheme.onSurface, iconSize = 34.dp)
                    PlayPauseButton(playback, onAction, Modifier.size(72.dp).testTag("player-toggle"), prominent = true)
                    if (podcast) SkipButton(snapshot.settings.skipForwardSeconds.toLong() * 1_000L, strings.format(TextKey.SkipForwardSeconds, strings.number(snapshot.settings.skipForwardSeconds.toLong())), VeyraIcons.SkipForward, playback, onAction, "player-skip-forward")
                    else ActionIcon(VeyraIcons.Next, strings.text(TextKey.Next), { onAction(MusicUiAction.Next) }, Modifier.size(60.dp).testTag("player-next"),
                        enabled = playback.hasNextOccurrence(snapshot), contentColor = MaterialTheme.colorScheme.onSurface, iconSize = 34.dp)
                    if (!podcast) Box {
                        val repeating = facts.repeat != RepeatMode.OFF
                        ActionIcon(VeyraIcons.Repeat, strings.text(repeatLabel(facts.repeat)),
                            { onAction(MusicUiAction.SetRepeat(nextRepeatMode(facts.repeat))) }, Modifier.testTag("player-repeat"),
                            isSelected = repeating, stateLabel = strings.text(repeatLabel(facts.repeat)),
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = if (repeating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, iconSize = 22.dp)
                        if (facts.repeat == RepeatMode.ONE) Box(Modifier.align(Alignment.BottomEnd).testTag("player-repeat-one-badge")) {
                            Text(strings.number(1), Modifier.padding(4.dp).clearAndSetSemantics { },
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CrossfadeStatus(crossfade: CrossfadeState) {
    val strings = LocalStrings.current
    val label = when (crossfade.phase) {
        CrossfadePhase.PREPARING -> TextKey.CrossfadePreparing
        CrossfadePhase.READY -> TextKey.CrossfadeReady
        CrossfadePhase.OVERLAPPING -> TextKey.CrossfadeActive
    }
    Column(Modifier.fillMaxWidth().testTag("player-crossfade"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.text(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        // Incoming identity is the exact silent-deck selection. Requested A, captured B and
        // executed C stay labelled as themselves; a neutral recording has no catalogue item.
        val incoming = crossfade.incomingView()
        incoming?.title?.let { title ->
            val key = when (incoming.identity) {
                IncomingIdentity.EXECUTED_RESULT -> TextKey.ResolvedTitleValue
                IncomingIdentity.OBSERVED_MEMBER -> TextKey.CapturedMemberTitleValue
                IncomingIdentity.REQUESTED -> TextKey.RequestedTitleValue
            }
            Text(strings.format(key, title), style = MaterialTheme.typography.bodySmall)
        }
        // The disclosure appears only for an exact receipt. A pending request is still described
        // by its requested/captured/executed label above, and no legacy row is borrowed.
        incoming?.provenance?.let { view ->
            PlaybackProvenanceCard(view = view, compact = true, context = PlaybackProvenanceContext.INCOMING)
        }
        if (crossfade.phase == CrossfadePhase.OVERLAPPING) MediaDirection {
            val progress = crossfade.fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().testTag("crossfade-progress").semantics {
                contentDescription = strings.format(TextKey.CrossfadeProgress, strings.number((progress * 100f).toLong()))
            })
        }
    }
}

@Composable
private fun PlayerSeek(playback: PlaybackState, onAction: (MusicUiAction) -> Unit) {
    val strings = LocalStrings.current
    val duration = playback.durationMs?.takeIf { it > 0L }
    val enabled = playback.seekable && duration != null
    val owner = playback.currentEntryId to playback.generation
    // Disposed UIKit controls can still deliver a final callback. Keep the current owner
    // outside the keyed control so those callbacks cannot seek a different recording.
    val activeOwner by rememberUpdatedState(owner)
    val position = livePosition(playback)
    key(owner) {
        val interactions = remember { MutableInteractionSource() }
        // Native position samples must not steal a draft, and a new owner starts fresh.
        var draft by remember { mutableStateOf<Float?>(null) }
        val shownPosition = draft?.let { seekPosition(it, duration ?: 0L) } ?: position()
        val label = strings.text(TextKey.Seek)
        val spokenPosition = strings.format(TextKey.ProgressValue, strings.spokenDuration(shownPosition), strings.spokenDuration(duration))
        MediaDirection {
            Column(Modifier.fillMaxWidth()) {
                CompositionLocalProvider(LocalNativeControlAccessibility provides NativeControlAccessibility(
                    label = label,
                    value = spokenPosition,
                    languageTag = if (strings.isRtl) "ar" else "en",
                )) {
                    AdaptiveSlider(
                        value = draft ?: seekFraction(position(), duration),
                        onValueChange = { if (enabled && activeOwner == owner) draft = it },
                        onValueChangeFinished = {
                            val fraction = draft
                            draft = null
                            if (fraction != null && enabled && duration != null && activeOwner == owner) {
                                onAction(MusicUiAction.Seek(seekPosition(fraction, duration), owner.first, owner.second))
                            }
                        },
                        enabled = enabled,
                        interactionSource = interactions,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("player-seek").semantics {
                            contentDescription = label
                            stateDescription = spokenPosition
                        },
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(strings.mediaTime(shownPosition), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics { contentDescription = strings.text(TextKey.Elapsed) + ": " + strings.spokenDuration(shownPosition) })
                    Text(strings.mediaTime(duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics { contentDescription = strings.text(TextKey.Duration) + ": " + strings.spokenDuration(duration) })
                }
            }
        }
    }
    if (!playback.seekable) Text(strings.text(TextKey.NotSeekable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PlayPauseButton(playback: PlaybackState, onAction: (MusicUiAction) -> Unit, modifier: Modifier, prominent: Boolean = false) {
    val strings = LocalStrings.current
    val pause = showsPause(playback)
    val label = strings.text(if (pause) TextKey.Pause else if (playback.phase == PlaybackPhase.ERROR) TextKey.Retry else TextKey.Play)
    if (prominent) ActionIcon(
        if (pause) VeyraIcons.Pause else VeyraIcons.Play,
        label,
        { onAction(MusicUiAction.TogglePlayPause) },
        modifier.shadow(14.dp, CircleShape, ambientColor = MaterialTheme.colorScheme.primary, spotColor = MaterialTheme.colorScheme.primary),
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        iconSize = 36.dp,
    ) else ActionIcon(
        if (pause) VeyraIcons.Pause else VeyraIcons.Play,
        label,
        { onAction(MusicUiAction.TogglePlayPause) },
        modifier,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SkipButton(milliseconds: Long, label: String, image: androidx.compose.ui.graphics.vector.ImageVector, playback: PlaybackState, onAction: (MusicUiAction) -> Unit, tag: String) {
    val strings = LocalStrings.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ActionIcon(image, label, { onAction(MusicUiAction.Skip(milliseconds, playback.currentEntryId, playback.generation)) }, Modifier.size(56.dp).testTag(tag),
            enabled = playback.seekable, contentColor = MaterialTheme.colorScheme.onSurface, iconSize = 32.dp)
        Text(strings.number(kotlin.math.abs(milliseconds / 1_000L)), style = MaterialTheme.typography.labelSmall, modifier = Modifier.clearAndSetSemantics { })
    }
}

@Composable
private fun TimerStatus(timer: SleepTimerState, onOpen: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val label = when (timer.mode) {
        SleepTimerMode.DURATION -> strings.format(TextKey.TimerActive, strings.mediaTime(timer.remainingMs))
        SleepTimerMode.END_OF_EPISODE -> strings.text(TextKey.TimerEndsWithEpisode)
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onOpen, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            Icon(VeyraIcons.Timer, null, Modifier.size(20.dp).padding(end = 4.dp))
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        }
        ActionIcon(VeyraIcons.Close, strings.text(TextKey.CancelTimer), onCancel, Modifier.testTag("cancel-sleep-timer"))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepTimerDialog(identity: PlaybackSurfaceIdentity, playback: PlaybackState, onAction: (MusicUiAction) -> Unit, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    var minutes by remember { mutableStateOf("30") }
    var endOfEpisode by remember(identity.key) { mutableStateOf(false) }
    val validMinutes = validatedTimerMinutes(minutes)
    val podcast = identity.controlsMode == PlaybackControlsMode.PODCAST
    // iOS: a native action sheet of presets; "Custom duration" opens the countdown wheel in a native sheet.
    var customStep by remember { mutableStateOf(false) }
    fun set(minutesValue: Int) { onAction(MusicUiAction.SetSleepMinutes(minutesValue)); onDismiss() }
    val material: @Composable () -> Unit = { SleepTimerMaterialDialog(playback, podcast, minutes, { minutes = it }, endOfEpisode,
        { endOfEpisode = it }, validMinutes, onAction, onDismiss) }
    if (!customStep) NativeAlert(
        ios = AlertDialogIosProperties(
            title = strings.text(TextKey.SleepTimer),
            text = strings.text(TextKey.TimerActiveDescription),
            style = AlertDialogIosStyle.ActionSheet,
            actions = buildList {
                listOf(15, 30, 45, 60).forEach { preset ->
                    add(AlertDialogIosAction(strings.quantity(QuantityKey.Minutes, preset), AlertDialogIosActionStyle.Default, onClick = { set(preset) }))
                }
                if (podcast) add(AlertDialogIosAction(strings.text(TextKey.EndOfEpisode), AlertDialogIosActionStyle.Default,
                    onClick = { onAction(MusicUiAction.SleepAtEpisodeEnd); onDismiss() }))
                add(AlertDialogIosAction(strings.text(TextKey.CustomDuration), AlertDialogIosActionStyle.Default, onClick = { customStep = true }))
                if (playback.sleepTimer != null) add(AlertDialogIosAction(strings.text(TextKey.CancelTimer), AlertDialogIosActionStyle.Destructive,
                    onClick = { onAction(MusicUiAction.ClearSleepTimer); onDismiss() }))
                add(AlertDialogIosAction(strings.text(TextKey.Cancel), AlertDialogIosActionStyle.Cancel, onClick = onDismiss))
            },
        ),
        onDismiss = onDismiss,
        material = material,
    ) else NativeSheet(onDismiss = onDismiss, containerColor = MaterialTheme.colorScheme.surface, material = material) {
        // iOS: the system countdown wheel (hours and minutes) in a native sheet.
        var wheelMinutes by remember { mutableStateOf(30) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.text(TextKey.CustomDuration), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            NativeDurationPicker(wheelMinutes, { wheelMinutes = it }, strings.text(TextKey.CustomDuration)) {}
            NativeButton(strings.text(TextKey.SetTimer), { set(wheelMinutes) }, NativeButtonStyle.Prominent,
                MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth(), symbol = "timer") {}
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepTimerMaterialDialog(playback: PlaybackState, podcast: Boolean, minutes: String, setMinutes: (String) -> Unit,
    endOfEpisode: Boolean, setEndOfEpisode: (Boolean) -> Unit, validMinutes: Int?, onAction: (MusicUiAction) -> Unit, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.text(TextKey.SleepTimer)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.text(TextKey.TimerActiveDescription), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 45, 60).forEach { preset ->
                        FilterChip(!endOfEpisode && validMinutes == preset, { setMinutes(preset.toString()); setEndOfEpisode(false) }, { Text(strings.quantity(QuantityKey.Minutes, preset)) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { setMinutes(it); setEndOfEpisode(false) },
                    label = { Text(strings.text(TextKey.CustomDuration)) },
                    supportingText = { Text(strings.text(TextKey.TimerDurationHint)) },
                    isError = !endOfEpisode && validMinutes == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("sleep-minutes"),
                )
                if (podcast) FilterChip(endOfEpisode, { setEndOfEpisode(true) }, { Text(strings.text(TextKey.EndOfEpisode)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("sleep-episode-end"))
                if (playback.sleepTimer != null) TextButton({ onAction(MusicUiAction.ClearSleepTimer); onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.CancelTimer)) }
            }
        },
        confirmButton = {
            TextButton(
                {
                    if (endOfEpisode && podcast) onAction(MusicUiAction.SleepAtEpisodeEnd)
                    else validMinutes?.let { onAction(MusicUiAction.SetSleepMinutes(it)) }
                    onDismiss()
                },
                enabled = endOfEpisode || validMinutes != null,
                modifier = Modifier.heightIn(min = 48.dp).testTag("sleep-confirm"),
            ) { Text(strings.text(TextKey.SetTimer)) }
        },
        dismissButton = { TextButton(onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.Cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedDialog(identity: PlaybackSurfaceIdentity, snapshot: AppSnapshot, playback: PlaybackState, options: MusicUiOptions, onAction: (MusicUiAction) -> Unit, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    val supported = availableSpeeds(options.minSpeed, options.maxSpeed)
    val showId = identity.catalogItem?.showId
    var speed by remember(identity.key) { mutableStateOf(playback.speed) }
    var forThisShow by remember(identity.key) { mutableStateOf(showId != null && showId in snapshot.settings.showSpeeds) }
    // iOS: a native action sheet of speeds (current one checked); for a podcast show a second sheet
    // then asks whether the speed applies to this show or to all podcasts.
    var chosen by remember(identity.key) { mutableStateOf<Float?>(null) }
    val material: @Composable () -> Unit = { SpeedMaterialDialog(supported, showId, speed, { speed = it }, forThisShow, { forThisShow = it }, onAction, onDismiss) }
    val pending = chosen
    if (pending == null) NativeAlert(
        ios = AlertDialogIosProperties(
            title = strings.text(TextKey.PlaybackSpeed),
            style = AlertDialogIosStyle.ActionSheet,
            actions = supported.map { rate ->
                val label = strings.format(TextKey.SpeedValue, strings.localizeDigits(speedText(rate)))
                AlertDialogIosAction(if (rate == playback.speed) "$label  ✓" else label, AlertDialogIosActionStyle.Default, onClick = {
                    if (showId != null) chosen = rate else { onAction(MusicUiAction.SetSpeed(rate, false)); onDismiss() }
                })
            } + AlertDialogIosAction(strings.text(TextKey.Cancel), AlertDialogIosActionStyle.Cancel, onClick = onDismiss),
        ),
        onDismiss = onDismiss,
        material = material,
    ) else NativeAlert(
        ios = AlertDialogIosProperties(
            title = strings.text(TextKey.SpeedScope),
            text = strings.format(TextKey.SpeedValue, strings.localizeDigits(speedText(pending))),
            style = AlertDialogIosStyle.ActionSheet,
            actions = listOf(
                AlertDialogIosAction(strings.text(TextKey.ThisShow), AlertDialogIosActionStyle.Default,
                    onClick = { onAction(MusicUiAction.SetSpeed(pending, true)); onDismiss() }, isPreferred = forThisShow),
                AlertDialogIosAction(strings.text(TextKey.AllPodcasts), AlertDialogIosActionStyle.Default,
                    onClick = { onAction(MusicUiAction.SetSpeed(pending, false)); onDismiss() }, isPreferred = !forThisShow),
                AlertDialogIosAction(strings.text(TextKey.Cancel), AlertDialogIosActionStyle.Cancel, onClick = onDismiss),
            ),
        ),
        onDismiss = onDismiss,
        material = material,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedMaterialDialog(supported: List<Float>, showId: String?, speed: Float, setSpeed: (Float) -> Unit,
    forThisShow: Boolean, setForThisShow: (Boolean) -> Unit, onAction: (MusicUiAction) -> Unit, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.text(TextKey.PlaybackSpeed)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    supported.forEach { rate ->
                        FilterChip(speed == rate, { setSpeed(rate) }, { Text(strings.format(TextKey.SpeedValue, strings.localizeDigits(speedText(rate)))) }, modifier = Modifier.heightIn(min = 48.dp).testTag("speed-$rate"))
                    }
                }
                if (showId != null) {
                    Text(strings.text(TextKey.SpeedScope), style = MaterialTheme.typography.labelLarge)
                    SegmentedTabs(listOf(strings.text(TextKey.ThisShow), strings.text(TextKey.AllPodcasts)),
                        if (forThisShow) 0 else 1, { setForThisShow(it == 0) })
                }
                TextButton({ setSpeed(1f) }, enabled = 1f in supported, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.ResetSpeed)) }
            }
        },
        confirmButton = { TextButton({ onAction(MusicUiAction.SetSpeed(speed, forThisShow)); onDismiss() }, enabled = speed in supported, modifier = Modifier.heightIn(min = 48.dp).testTag("speed-confirm")) { Text(strings.text(TextKey.Apply)) } },
        dismissButton = { TextButton(onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.Cancel)) } },
    )
}

/** Queue chips dispatch view-only intents. Only explicit occurrence selection can activate audio. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QueuePanel(
    snapshot: AppSnapshot,
    playback: PlaybackState,
    viewedContext: QueueContext,
    onAction: (MusicUiAction) -> Unit,
    modifier: Modifier = Modifier,
    options: MusicUiOptions = MusicUiOptions(),
    bottomEdgeFree: Boolean = false,
) {
    val strings = LocalStrings.current
    val surface = playback.queueSurface(viewedContext, snapshot)
    val pending = surface.upcoming
    var clear by remember(viewedContext) { mutableStateOf(false) }
    // iOS: queue-wide actions live in a native bottom toolbar instead of the list, where nothing
    // else (the mini player) occupies the bottom edge.
    val toolbar = nativeToolbarAvailable && bottomEdgeFree
    if (toolbar) NativeToolbar(buildList {
        add(NativeBarButton(strings.text(TextKey.ClearUpcoming), "trash", enabled = pending.isNotEmpty()) { clear = true })
        if (viewedContext in options.undoableQueueContexts)
            add(NativeBarButton(strings.text(TextKey.UndoQueueEdit), "arrow.uturn.backward") { onAction(MusicUiAction.UndoQueue(viewedContext)) })
    })
    LazyColumn(modifier.fillMaxSize().testTag("queue-panel"), contentPadding = PaddingValues(bottom = bottomChrome(24.dp) + if (toolbar) nativeToolbarClearance else 0.dp)) {
        item {
            SectionTitle(strings.text(TextKey.Queue))
            SegmentedTabs(QueueContext.entries.map { strings.text(queueLabel(it)) }, QueueContext.entries.indexOf(viewedContext),
                { onAction(MusicUiAction.ViewQueue(QueueContext.entries[it])) }, Modifier.padding(horizontal = 20.dp),
                tags = QueueContext.entries.map { "view-queue-${it.name}" })
            Text(strings.format(TextKey.ViewingQueue, strings.text(queueLabel(viewedContext))), Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
            if (playback.hasCurrent) Text(strings.format(TextKey.ActiveQueue, strings.text(queueLabel(playback.activeContext))), Modifier.padding(horizontal = 20.dp, vertical = 4.dp).testTag("active-queue-label"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        surface.current?.let { current ->
            item(key = "queue-current-${current.entryId}") {
                SectionTitle(strings.text(TextKey.CurrentItem))
                val audible = viewedContext == playback.activeContext && current.entryId == playback.currentEntryId
                QueueOccurrence(current, snapshot, viewedContext, onAction, options, audible, isCurrent = true)
            }
        }
        item {
            SectionTitle(strings.text(TextKey.Upcoming), description = strings.quantity(QuantityKey.Items, pending.size))
            if (!toolbar) FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ clear = true }, enabled = pending.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp).testTag("clear-upcoming")) { Text(strings.text(TextKey.ClearUpcoming)) }
                if (viewedContext in options.undoableQueueContexts) TextButton({ onAction(MusicUiAction.UndoQueue(viewedContext)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("undo-queue")) { Text(strings.text(TextKey.UndoQueueEdit)) }
            }
            if (surface.shuffle && pending.size > 1) Text(strings.text(TextKey.ReorderTurnsOffShuffle), Modifier.padding(horizontal = 20.dp, vertical = 8.dp).testTag("queue-reorder-warning"), style = MaterialTheme.typography.bodyMedium)
        }
        if (pending.isEmpty()) item {
            EmptyState(strings.text(TextKey.QueueEmpty), strings.text(TextKey.QueueEmptyDescription), strings.text(if (viewedContext == QueueContext.PODCAST) TextKey.FindPodcasts else TextKey.SearchMusic), { onAction(MusicUiAction.OpenSearch(if (viewedContext == QueueContext.PODCAST) SearchKind.PODCASTS else SearchKind.MUSIC)) }, Modifier.padding(horizontal = 20.dp))
        }
        itemsIndexed(pending, key = { _, row -> row.entryId }) { index, row ->
            Column(Modifier.fillMaxWidth()) {
                QueueOccurrence(row, snapshot, viewedContext, onAction, options, audible = false)
                val title = row.title ?: strings.text(TextKey.InformationUnavailable)
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                    ActionIcon(VeyraIcons.ArrowUp, strings.format(TextKey.MoveEntryUp, title), {
                        val toIndex = surface.displayedIds.indexOf(pending[index - 1].entryId)
                        if (toIndex >= 0) onAction(MusicUiAction.MoveQueueEntry(viewedContext, row.entryId, toIndex))
                    }, Modifier.testTag("queue-up-${row.entryId}"), enabled = index > 0, stateLabel = if (surface.shuffle) strings.text(TextKey.ReorderTurnsOffShuffle) else null)
                    ActionIcon(VeyraIcons.ArrowDown, strings.format(TextKey.MoveEntryDown, title), {
                        val toIndex = surface.displayedIds.indexOf(pending[index + 1].entryId)
                        if (toIndex >= 0) onAction(MusicUiAction.MoveQueueEntry(viewedContext, row.entryId, toIndex))
                    }, Modifier.testTag("queue-down-${row.entryId}"), enabled = index < pending.lastIndex, stateLabel = if (surface.shuffle) strings.text(TextKey.ReorderTurnsOffShuffle) else null)
                    ActionIcon(VeyraIcons.Delete, strings.text(TextKey.RemoveFromQueue), { onAction(MusicUiAction.RemoveQueueEntry(viewedContext, row.entryId)) }, Modifier.testTag("queue-remove-${row.entryId}"))
                }
                HorizontalDivider()
            }
        }
    }
    if (clear) ConfirmActionDialog(strings.text(TextKey.ClearUpcoming), strings.text(TextKey.ConfirmClearQueue), strings.text(TextKey.ClearUpcoming), { clear = false }) { onAction(MusicUiAction.ClearQueue(viewedContext)); clear = false }
}

@Composable
private fun QueueOccurrence(row: QueueItemRow, snapshot: AppSnapshot, context: QueueContext, onAction: (MusicUiAction) -> Unit, options: MusicUiOptions, audible: Boolean, isCurrent: Boolean = false) {
    val strings = LocalStrings.current
    Surface(color = if (audible) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("queue-entry-${row.entryId}").semantics { selected = isCurrent }) {
        Column {
            TextButton({ onAction(MusicUiAction.PlayQueueEntry(context, row.entryId)) }, enabled = row.playable, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("queue-play-${row.entryId}"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                Artwork(row.artworkUrl, row.title.orEmpty(), Modifier.size(48.dp), offlineOnly = snapshot.settings.offlineOnly, localPath = row.localArtworkPath)
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (row.title != null) {
                        MetadataText(row.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        row.creator?.let { creator ->
                            MetadataText(creator, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        row.listingProvider?.let { provider -> Text(providerName(provider), style = MaterialTheme.typography.labelSmall) }
                    } else Text(strings.text(TextKey.InformationUnavailable), color = MaterialTheme.colorScheme.error)
                    when (row.status) {
                        QueueRowStatus.PENDING_RESOLUTION -> Text(strings.text(TextKey.ResolvingTitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        QueueRowStatus.UNAVAILABLE -> Text(strings.text(TextKey.QueueEntryUnavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        QueueRowStatus.MISSING_MEDIA -> Text(strings.text(TextKey.FileUnavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        QueueRowStatus.MISSING_SELECTION -> Text(strings.text(TextKey.InformationUnavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        QueueRowStatus.READY -> Unit
                    }
                }
                Icon(if (audible) VeyraIcons.Listen else VeyraIcons.Play, if (audible) strings.text(TextKey.CurrentItem) else strings.text(TextKey.Play), Modifier.size(20.dp))
            }
            row.mediaId?.let { options.unavailableItems[it] }?.let { failure ->
                Text(strings.errorFor(failure), Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun queueLabel(context: QueueContext): TextKey = if (context == QueueContext.MUSIC) TextKey.MusicQueue else TextKey.PodcastQueue
private fun repeatLabel(mode: RepeatMode): TextKey = when (mode) { RepeatMode.OFF -> TextKey.RepeatOff; RepeatMode.ALL -> TextKey.RepeatAll; RepeatMode.ONE -> TextKey.RepeatOne }
private fun speedText(rate: Float): String = rate.toString().removeSuffix(".0")
/** The source-declared availability wait the current preparation honors, if any (P8). */
val LocalPlaybackWait = androidx.compose.runtime.staticCompositionLocalOf<dev.veyra.source.youtube.SourcePlaybackWait?> { null }

/**
 * The live playback position, which moves four times a second. The app root strips it from the
 * [PlaybackState] it passes down so a tick recomposes only the timelines that read this, not the
 * whole app. Null (previews, tests) falls back to the passed state's own position.
 */
val LocalPlaybackPosition = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<Long>?> { null }

/** Reads the position where it is used: in a draw lambda this only redraws, never recomposes. */
@Composable
private fun livePosition(playback: PlaybackState): () -> Long {
    val live = LocalPlaybackPosition.current
    return if (live == null) { { playback.positionMs } } else { { live.value } }
}


/**
 * "Starting in N s" while a declared availability wait is being honored, so a delayed start is
 * shown as a known countdown rather than an open-ended preparation; otherwise the phase label.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
@Composable
private fun playbackStatusLabel(playback: PlaybackState, strings: dev.veyra.ui.design.AppStrings): String {
    val wait = LocalPlaybackWait.current
    if (wait == null || playback.phase !in setOf(PlaybackPhase.RESOLVING, PlaybackPhase.PREPARING)) return strings.text(playbackStatus(playback))
    fun now() = kotlin.time.Clock.System.now().toEpochMilliseconds()
    var current by androidx.compose.runtime.remember(wait) { androidx.compose.runtime.mutableLongStateOf(now()) }
    androidx.compose.runtime.LaunchedEffect(wait) {
        while (current < wait.availableAtEpochMs) { kotlinx.coroutines.delay(250); current = now() }
    }
    val seconds = (wait.availableAtEpochMs - current + 999) / 1000
    return if (seconds > 0) strings.format(TextKey.StartingIn, strings.number(seconds)) else strings.text(TextKey.PreparingAudio)
}

private fun playbackStatus(playback: PlaybackState): TextKey = when (playback.phase) {
    PlaybackPhase.IDLE -> TextKey.NothingPlaying
    PlaybackPhase.RESOLVING, PlaybackPhase.PREPARING -> TextKey.PreparingAudio
    PlaybackPhase.BUFFERING -> TextKey.Buffering
    PlaybackPhase.PLAYING -> TextKey.Playing
    PlaybackPhase.ENDED -> TextKey.PlaybackEnded
    PlaybackPhase.ERROR -> TextKey.PlaybackUnavailable
    PlaybackPhase.READY, PlaybackPhase.PAUSED -> TextKey.Paused
}
