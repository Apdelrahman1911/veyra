package dev.veyra.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
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
    // Short panes reserve room for the actual screen, not a second multi-line detail view.
    // Both identities remain visible here and complete through the OpenPlayer action.
    val metadataLines = if (compactHeight) 1 else 2
    var timerDialog by remember { mutableStateOf(false) }
    Surface(modifier = modifier.fillMaxWidth()
        .then(if (docked) Modifier else Modifier.padding(horizontal = 12.dp, vertical = 6.dp)).testTag("mini-player"),
        shape = if (docked) RectangleShape else RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = if (docked) 0.dp else 2.dp) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onAction(MusicUiAction.OpenPlayer) },
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp).testTag("mini-open-player"),
                    contentPadding = PaddingValues(4.dp),
                    // Multiline metadata must not inherit a pill clip at its corner lines.
                    shape = RectangleShape,
                ) {
                    Artwork(identity.artworkUrl, identity.title.orEmpty(), Modifier.size(44.dp), offlineOnly = snapshot.settings.offlineOnly, localPath = identity.localArtworkPath)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        MetadataText(identity.title ?: strings.text(TextKey.InformationUnavailable), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = metadataLines, overflow = TextOverflow.Ellipsis)
                        if (identity.resolvedTitle != null && identity.resolvedTitle != identity.title) {
                            Text(strings.format(TextKey.ResolvedTitleValue, identity.resolvedTitle), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = metadataLines, overflow = TextOverflow.Ellipsis)
                        } else if (!identity.creator.isNullOrBlank()) MetadataText(identity.creator!!, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (playback.crossfade?.phase == CrossfadePhase.OVERLAPPING) Text(strings.text(TextKey.CrossfadeActive), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (playback.isBuffering || playback.failure != null) Text(
                            playback.failure?.let(strings::errorFor) ?: playbackStatusLabel(playback, strings),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (playback.failure == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            maxLines = metadataLines,
                            overflow = TextOverflow.Ellipsis,
                        )
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
            if (playback.isBuffering) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).clearAndSetSemantics { })
            else if ((playback.durationMs ?: 0L) > 0L) MediaDirection {
                LinearProgressIndicator(progress = { seekFraction(playback.positionMs, playback.durationMs) }, modifier = Modifier.fillMaxWidth().height(2.dp).clearAndSetSemantics { })
            }
        }
    }
    if (timerDialog) SleepTimerDialog(identity, playback, onAction, onDismiss = { timerDialog = false })
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 8.dp, bottom = 4.dp).size(width = 36.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f), CircleShape)
                .testTag("player-drag-handle").clearAndSetSemantics { })
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionIcon(VeyraIcons.Collapse, strings.text(TextKey.ClosePlayer), { onAction(MusicUiAction.ClosePlayer) }, Modifier.testTag("close-player"))
                Text(strings.text(TextKey.NowPlaying), Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                ActionIcon(VeyraIcons.Queue, strings.text(TextKey.ShowQueue),
                    { onAction(MusicUiAction.OpenQueue(playback.activeContext)) }, Modifier.testTag("player-queue-top"))
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
                    Column(
                        Modifier.fillMaxWidth().testTag("player-details").verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = if (compactIdentity) 8.dp else 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (split) Row(Modifier.widthIn(max = 1_120.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Artwork(identity.artworkUrl, identity.title.orEmpty(), Modifier.widthIn(max = 400.dp).fillMaxWidth().aspectRatio(1f), offlineOnly = snapshot.settings.offlineOnly, localPath = identity.localArtworkPath)
                            }
                            PlayerDetails(identity, snapshot, playback, onAction, options, { timerDialog = true }, { speedDialog = true }, Modifier.weight(1f))
                        } else {
                            if (!compactIdentity) {
                                Artwork(identity.artworkUrl, identity.title.orEmpty(), Modifier.widthIn(max = 320.dp).fillMaxWidth(.88f).aspectRatio(1f), offlineOnly = snapshot.settings.offlineOnly, localPath = identity.localArtworkPath)
                                Spacer(Modifier.height(16.dp))
                            }
                            PlayerDetails(identity, snapshot, playback, onAction, options, { timerDialog = true }, { speedDialog = true },
                                Modifier.widthIn(max = 560.dp).fillMaxWidth(), compactIdentityLines = if (compactIdentity) identityLines else null)
                        }
                    }
                }
                HorizontalDivider()
                // Essential pause/seek/skip never sit below an arbitrarily long receipt or
                // creator name. Resizing and reading details must not hide the active transport.
                PlayerTransport(identity, snapshot, playback, onAction)
            }
        }
    }
    if (timerDialog && identity != null) SleepTimerDialog(identity, playback, onAction, onDismiss = { timerDialog = false })
    if (speedDialog && identity?.controlsMode == PlaybackControlsMode.PODCAST) SpeedDialog(identity, snapshot, playback, options, onAction, onDismiss = { speedDialog = false })
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
    var audioDetails by remember(playback.currentEntryId) { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MetadataText(title, Modifier.fillMaxWidth().testTag("player-title"), style = MaterialTheme.typography.headlineSmall,
            maxLines = compactIdentityLines ?: 3, overflow = TextOverflow.Ellipsis)
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
        identity.creator?.takeIf { it.isNotBlank() }?.let { creator ->
            MetadataText(creator, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Online preparation can take several seconds; a visible spinner shows the app is working.
            if (playback.isBuffering) androidx.compose.material3.CircularProgressIndicator(
                Modifier.padding(end = 8.dp).size(16.dp).clearAndSetSemantics { },
                strokeWidth = 2.dp,
            )
            Text(playbackStatusLabel(playback, strings), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (catalogItem != null) {
                ActionIcon(savedIcon(catalogItem, snapshot.isSaved(catalogItem)), strings.text(savedActionLabel(catalogItem, snapshot.isSaved(catalogItem))), { onAction(MusicUiAction.ToggleFavorite(catalogItem)) }, Modifier.testTag("player-save"), isSelected = snapshot.isSaved(catalogItem))
                MediaActionsMenu(catalogItem, snapshot, onAction, options = options)
            }
        }
        playback.failure?.let { failure ->
            ErrorState(strings.errorFor(failure), onRetry = { onAction(MusicUiAction.TogglePlayPause) }, modifier = Modifier.fillMaxWidth().testTag("player-error").semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (identity.resolvedTitle != null && identity.resolvedTitle != identity.title && !compactIdentity) {
            Text(strings.format(TextKey.ResolvedTitleValue, identity.resolvedTitle), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (podcast) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(openSpeed, modifier = Modifier.heightIn(min = 48.dp).testTag("player-speed"), enabled = availableSpeeds(options.minSpeed, options.maxSpeed).isNotEmpty()) {
                    Text(strings.format(TextKey.SpeedValue, strings.localizeDigits(speedText(playback.effectiveSpeed))))
                }
                TextButton({ onAction(MusicUiAction.Next) }, enabled = playback.hasNextOccurrence(snapshot), modifier = Modifier.heightIn(min = 48.dp).testTag("player-next-episode")) { Text(strings.text(TextKey.NextEpisode)) }
            }
        }
        HorizontalDivider()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton({ onAction(MusicUiAction.OpenQueue(playback.activeContext)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("player-queue")) {
                Icon(VeyraIcons.Queue, null, Modifier.size(20.dp).padding(end = 4.dp))
                Text(strings.text(TextKey.Queue))
            }
            OutlinedButton(openTimer, modifier = Modifier.heightIn(min = 48.dp).testTag("player-sleep")) {
                Icon(VeyraIcons.Timer, null, Modifier.size(20.dp).padding(end = 4.dp))
                Text(strings.text(TextKey.SleepTimer))
            }
        }
        playback.sleepTimer?.let { TimerStatus(it, openTimer, { onAction(MusicUiAction.ClearSleepTimer) }, Modifier.testTag("player-timer")) }
        playback.crossfade?.let { CrossfadeStatus(it) }
        TextButton({ audioDetails = !audioDetails }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("player-audio-details")
            .semantics { stateDescription = strings.text(if (audioDetails) TextKey.ShowLess else TextKey.ShowMore) }) {
            Icon(VeyraIcons.Info, null, Modifier.size(18.dp))
            Text(strings.text(TextKey.AudioDetails), Modifier.padding(horizontal = 8.dp))
        }
        if (audioDetails) PlaybackProvenanceCard(view = identity.provenance)

    }
}

@Composable
private fun PlayerTransport(identity: PlaybackSurfaceIdentity, snapshot: AppSnapshot, playback: PlaybackState, onAction: (MusicUiAction) -> Unit) {
    val strings = LocalStrings.current
    val podcast = identity.controlsMode == PlaybackControlsMode.PODCAST
    val facts = playback.activeQueueFacts(snapshot)
    Surface(Modifier.fillMaxWidth().testTag("player-transport"), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerSeek(playback, onAction)
                MediaDirection {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (podcast) 16.dp else 6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                        if (!podcast) ActionIcon(VeyraIcons.Shuffle, strings.text(if (facts.shuffle) TextKey.ShuffleOn else TextKey.ShuffleOff),
                            { onAction(MusicUiAction.SetShuffle(!facts.shuffle)) }, Modifier.testTag("player-shuffle"), isSelected = facts.shuffle)
                        if (podcast) SkipButton(-snapshot.settings.skipBackSeconds.toLong() * 1_000L, strings.format(TextKey.SkipBackSeconds, strings.number(snapshot.settings.skipBackSeconds.toLong())), VeyraIcons.SkipBack, playback, onAction, "player-skip-back")
                        else ActionIcon(VeyraIcons.Previous, strings.text(TextKey.Previous), { onAction(MusicUiAction.Previous) }, Modifier.testTag("player-previous"))
                        PlayPauseButton(playback, onAction, Modifier.size(64.dp).testTag("player-toggle"), prominent = true)
                        if (podcast) SkipButton(snapshot.settings.skipForwardSeconds.toLong() * 1_000L, strings.format(TextKey.SkipForwardSeconds, strings.number(snapshot.settings.skipForwardSeconds.toLong())), VeyraIcons.SkipForward, playback, onAction, "player-skip-forward")
                        else ActionIcon(VeyraIcons.Next, strings.text(TextKey.Next), { onAction(MusicUiAction.Next) }, Modifier.testTag("player-next"), enabled = playback.hasNextOccurrence(snapshot))
                        if (!podcast) Box {
                            ActionIcon(VeyraIcons.Repeat, strings.text(repeatLabel(facts.repeat)),
                                { onAction(MusicUiAction.SetRepeat(nextRepeatMode(facts.repeat))) }, Modifier.testTag("player-repeat"),
                                isSelected = facts.repeat != RepeatMode.OFF, stateLabel = strings.text(repeatLabel(facts.repeat)))
                            if (facts.repeat == RepeatMode.ONE) Box(Modifier.align(Alignment.BottomEnd).testTag("player-repeat-one-badge")) {
                                Text(strings.number(1), Modifier.padding(4.dp).clearAndSetSemantics { },
                                    style = MaterialTheme.typography.labelSmall)
                            }
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
    key(owner) {
        val interactions = remember { MutableInteractionSource() }
        // Native position samples must not steal a draft, and a new owner starts fresh.
        var draft by remember { mutableStateOf<Float?>(null) }
        val shownPosition = draft?.let { seekPosition(it, duration ?: 0L) } ?: playback.positionMs
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
                        value = draft ?: seekFraction(playback.positionMs, duration),
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(strings.mediaTime(shownPosition), style = MaterialTheme.typography.labelMedium, modifier = Modifier.clearAndSetSemantics { contentDescription = strings.text(TextKey.Elapsed) + ": " + strings.spokenDuration(shownPosition) })
                    Text(strings.mediaTime(duration), style = MaterialTheme.typography.labelMedium, modifier = Modifier.clearAndSetSemantics { contentDescription = strings.text(TextKey.Duration) + ": " + strings.spokenDuration(duration) })
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
    ActionIcon(
        if (pause) VeyraIcons.Pause else VeyraIcons.Play,
        label,
        { onAction(MusicUiAction.TogglePlayPause) },
        if (prominent) modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape) else modifier,
    )
}

@Composable
private fun SkipButton(milliseconds: Long, label: String, image: androidx.compose.ui.graphics.vector.ImageVector, playback: PlaybackState, onAction: (MusicUiAction) -> Unit, tag: String) {
    val strings = LocalStrings.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ActionIcon(image, label, { onAction(MusicUiAction.Skip(milliseconds, playback.currentEntryId, playback.generation)) }, Modifier.testTag(tag), enabled = playback.seekable)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.text(TextKey.SleepTimer)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.text(TextKey.TimerActiveDescription), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 45, 60).forEach { preset ->
                        FilterChip(!endOfEpisode && validMinutes == preset, { minutes = preset.toString(); endOfEpisode = false }, { Text(strings.quantity(QuantityKey.Minutes, preset)) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it; endOfEpisode = false },
                    label = { Text(strings.text(TextKey.CustomDuration)) },
                    supportingText = { Text(strings.text(TextKey.TimerDurationHint)) },
                    isError = !endOfEpisode && validMinutes == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("sleep-minutes"),
                )
                if (podcast) FilterChip(endOfEpisode, { endOfEpisode = true }, { Text(strings.text(TextKey.EndOfEpisode)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("sleep-episode-end"))
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.text(TextKey.PlaybackSpeed)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    supported.forEach { rate ->
                        FilterChip(speed == rate, { speed = rate }, { Text(strings.format(TextKey.SpeedValue, strings.localizeDigits(speedText(rate)))) }, modifier = Modifier.heightIn(min = 48.dp).testTag("speed-$rate"))
                    }
                }
                if (showId != null) {
                    Text(strings.text(TextKey.SpeedScope), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(forThisShow, { forThisShow = true }, { Text(strings.text(TextKey.ThisShow)) }, modifier = Modifier.heightIn(min = 48.dp))
                        FilterChip(!forThisShow, { forThisShow = false }, { Text(strings.text(TextKey.AllPodcasts)) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                TextButton({ speed = 1f }, enabled = 1f in supported, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.ResetSpeed)) }
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
) {
    val strings = LocalStrings.current
    val surface = playback.queueSurface(viewedContext, snapshot)
    val pending = surface.upcoming
    var clear by remember(viewedContext) { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize().testTag("queue-panel"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            SectionTitle(strings.text(TextKey.Queue))
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QueueContext.entries.forEach { context ->
                    FilterChip(viewedContext == context, { onAction(MusicUiAction.ViewQueue(context)) }, { Text(strings.text(queueLabel(context))) }, modifier = Modifier.heightIn(min = 48.dp).testTag("view-queue-${context.name}"))
                }
            }
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
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
