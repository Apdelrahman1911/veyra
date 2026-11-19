package dev.veyra.ui.settings

import dev.veyra.ui.design.bottomChrome
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import dev.veyra.nativecomponents.NativeSlider
import androidx.compose.material3.Surface
import dev.veyra.nativecomponents.NativeSwitch
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import dev.veyra.ui.design.VeyraIcons
import dev.veyra.ui.design.ScreenIntro
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.veyra.nativecomponents.LocalNativeControlAccessibility
import dev.veyra.nativecomponents.NativeControlAccessibility
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.veyra.model.AppSettings
import dev.veyra.model.LanguagePreference
import dev.veyra.model.ThemeMode
import dev.veyra.playback.EngineCapabilities
import dev.veyra.ui.design.LocalStrings
import dev.veyra.ui.design.LocalVeyraColors
import dev.veyra.ui.design.QuantityKey
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraRadii
import dev.veyra.ui.design.VeyraSpacing
import kotlin.math.roundToInt

/** Real persisted settings supplied by the root. No client, engine or store is created here. */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    capabilities: EngineCapabilities,
    storageBytes: Long?,
    onSettingsChange: (SettingsAction) -> Unit,
    onManageStorage: () -> Unit,
    onOpenLicenses: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    Box(modifier.fillMaxSize().testTag("settings-screen"), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 880.dp).fillMaxSize().testTag("settings-list"),
            contentPadding = PaddingValues(start = VeyraSpacing.Large, end = VeyraSpacing.Large, top = VeyraSpacing.Section,
                bottom = bottomChrome(VeyraSpacing.Section)),
            verticalArrangement = Arrangement.spacedBy(VeyraSpacing.Large),
        ) {
            item("title") {
                ScreenIntro(strings.text(TextKey.Settings), strings.text(TextKey.SettingsSubtitle))
            }
            item("playback") {
                SettingsSection(TextKey.Playback, VeyraIcons.Music) {
                    ToggleSetting(
                        title = strings.text(TextKey.Crossfade), description = strings.text(TextKey.CrossfadeDescription),
                        checked = settings.crossfadeEnabled,
                        modifier = Modifier.testTag("settings-crossfade"),
                        // A saved unsupported preference may still be switched OFF safely.
                        enabled = capabilities.supportsCrossfade || settings.crossfadeEnabled,
                        onChange = { if (!it || capabilities.supportsCrossfade) onSettingsChange(SettingsAction.SetCrossfadeEnabled(it)) },
                    )
                    if (!capabilities.supportsCrossfade) {
                        Text(strings.text(if (settings.crossfadeEnabled) TextKey.CrossfadeSavedUnsupported else TextKey.CrossfadeUnsupported),
                            color = LocalVeyraColors.current.warning, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("settings-crossfade-unavailable"))
                    }
                    ChoiceGroup(strings.text(TextKey.CrossfadeDuration)) {
                        listOf(2L, 3L, 4L, 5L).forEach { seconds ->
                            SettingsButton(strings.quantity(QuantityKey.Seconds, seconds),
                                selected = settings.crossfadeDurationMs == seconds * 1_000,
                                enabled = capabilities.supportsCrossfade,
                                modifier = Modifier.testTag("settings-crossfade-duration-${seconds * 1_000}"),
                                onClick = { onSettingsChange(SettingsAction.SetCrossfadeDuration(seconds * 1_000)) })
                        }
                    }
                    CrossfadeDurationInput(settings.crossfadeDurationMs, capabilities.supportsCrossfade, onSettingsChange)
                    SettingsDivider()
                    Text(strings.text(TextKey.CrossfadeHeadroom), style = MaterialTheme.typography.titleSmall)
                    SettingsNote(strings.text(TextKey.CrossfadeHeadroomDescription))
                }
            }
            item("podcasts") {
                SettingsSection(TextKey.PodcastDefaults, VeyraIcons.Podcasts) {
                    SettingsNote(strings.text(TextKey.DefaultPodcastSpeedDescription))
                    val speeds = supportedPodcastSpeeds(capabilities, settings.speed)
                    if (settings.speed !in speeds) {
                        Text(strings.text(TextKey.SpeedPreferenceUnavailable), color = LocalVeyraColors.current.warning)
                    }
                    ChoiceGroup(strings.text(TextKey.DefaultPodcastSpeed)) {
                        speeds.forEach { speed ->
                            SettingsButton(strings.format(TextKey.SpeedValue, strings.localizeDigits(speed.toString().removeSuffix(".0"))),
                                selected = settings.speed == speed,
                                modifier = Modifier.testTag("settings-podcast-speed-$speed"),
                                onClick = { onSettingsChange(SettingsAction.SetPodcastSpeed(speed)) })
                        }
                    }
                    SkipChoices(TextKey.SkipBackSetting, settings.skipBackSeconds) {
                        onSettingsChange(SettingsAction.SetSkipBack(it))
                    }
                    SkipChoices(TextKey.SkipForwardSetting, settings.skipForwardSeconds) {
                        onSettingsChange(SettingsAction.SetSkipForward(it))
                    }
                }
            }
            item("appearance") {
                SettingsSection(TextKey.Appearance, VeyraIcons.Settings) {
                    ChoiceGroup(strings.text(TextKey.Language)) {
                        LanguagePreference.entries.forEach { language ->
                            SettingsButton(strings.languageName(language), selected = settings.language == language,
                                modifier = Modifier.testTag("settings-language-${language.name}"),
                                onClick = { onSettingsChange(SettingsAction.SetLanguage(language)) })
                        }
                    }
                    ChoiceGroup(strings.text(TextKey.Theme)) {
                        ThemeMode.entries.forEach { theme ->
                            SettingsButton(strings.themeName(theme), selected = settings.theme == theme,
                                modifier = Modifier.testTag("settings-theme-${theme.name}"),
                                onClick = { onSettingsChange(SettingsAction.SetTheme(theme)) })
                        }
                    }
                    TextSizeSetting(settings.textScale, onSettingsChange)
                }
            }
            item("offline") {
                SettingsSection(TextKey.Offline, VeyraIcons.Library) {
                    ToggleSetting(strings.text(TextKey.OfflineMode), strings.text(TextKey.OfflineModeDescription),
                        checked = settings.offlineOnly,
                        modifier = Modifier.testTag("settings-offline"),
                        onChange = { onSettingsChange(SettingsAction.SetOfflineOnly(it)) })
                }
            }
            item("downloads") {
                SettingsSection(TextKey.Downloads, VeyraIcons.Download) {
                    SettingsNote(strings.text(TextKey.ParallelDownloadsDescription))
                    ChoiceGroup(strings.text(TextKey.ParallelDownloads)) {
                        (1..4).forEach { count ->
                            SettingsButton(strings.number(count.toLong()), selected = settings.downloadParallelism == count,
                                modifier = Modifier.testTag("settings-parallelism-$count"),
                                onClick = { onSettingsChange(SettingsAction.SetDownloadParallelism(count)) })
                        }
                    }
                    SettingsNote(strings.text(TextKey.DownloadExecutionNotice))
                    SettingsDivider()
                    Text(storageBytes?.takeIf { it >= 0 }?.let { strings.format(TextKey.StorageUsed, strings.byteSize(it)) }
                        ?: strings.text(TextKey.StorageUsageUnavailable), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("settings-storage-value"))
                    SettingsLinkRow(strings.text(TextKey.ManageStorage), VeyraIcons.Download, onManageStorage,
                        Modifier.testTag("settings-manage-storage"))
                }
            }
            item("about") {
                SettingsSection(TextKey.About, VeyraIcons.Info) {
                    SettingsLinkRow(strings.text(TextKey.Licenses), VeyraIcons.OpenInNew, onOpenLicenses,
                        Modifier.testTag("settings-licenses"))
                    SettingsDivider()
                    SettingsNote(strings.text(TextKey.LocalDataNotice))
                    Text(strings.text(TextKey.Privacy), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() })
                    SettingsNote(strings.text(TextKey.PublisherNetworkNotice))
                    SettingsNote(strings.text(TextKey.CompleteTitlePrivacyNotice))
                    SettingsDivider()
                    Text(strings.text(TextKey.KeyboardShortcuts), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() })
                    SettingsNote(strings.text(TextKey.ShortcutSearch))
                    SettingsNote(strings.text(TextKey.ShortcutClose))
                    SettingsNote(strings.text(TextKey.ShortcutPlayback))
                    SettingsNote(strings.text(TextKey.KeyboardHelp))
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(titleKey: TextKey, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    val strings = LocalStrings.current
    val title = strings.text(titleKey)
    // A grouped list: a small tinted heading above one quiet card of compact rows.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, Modifier.weight(1f).testTag("settings-section-${titleKey.name}").semantics { heading() },
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary)
        }
        Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
        }
    }
}

/** Secondary explanation under a setting: smaller and quieter than the setting itself. */
@Composable
private fun SettingsNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = LocalVeyraColors.current.outlineSubtle.copy(alpha = .5f))
}

/** A row that opens another screen: icon, title, optional detail and a trailing chevron. */
@Composable
private fun SettingsLinkRow(title: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier,
    detail: (@Composable () -> Unit)? = null) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(VeyraRadii.Small)
    Row(
        modifier.fillMaxWidth().sizeIn(minHeight = VeyraSpacing.TouchTarget)
            .then(if (focused) Modifier.border(2.dp, LocalVeyraColors.current.primary, shape) else Modifier)
            .clip(shape)
            .clickable(interactionSource = interactions, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            detail?.invoke()
        }
        Icon(VeyraIcons.ChevronForward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() })
        // The chips draw 34dp tall inside full 48dp touch targets, so rows stay tight.
        FlowRow(modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/**
 * A compact chip: it draws 34dp tall but takes taps across a full 48dp target. The chosen one is
 * filled and checked; keyboard focus adds a ring.
 */
@Composable
private fun SettingsButton(label: String, onClick: () -> Unit, selected: Boolean? = null, enabled: Boolean = true,
    modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val colors = LocalVeyraColors.current
    val chosen = selected == true
    val shape = CircleShape
    Box(
        modifier.sizeIn(minWidth = VeyraSpacing.TouchTarget, minHeight = VeyraSpacing.TouchTarget)
            .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (selected != null) this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.sizeIn(minHeight = 34.dp)
                .then(if (focused) Modifier.border(2.dp, colors.primary, shape) else Modifier)
                .clip(shape)
                .background(if (chosen) colors.primary else MaterialTheme.colorScheme.surfaceContainerLow)
                .then(if (chosen) Modifier else Modifier.border(1.dp, colors.outlineSubtle, shape))
                .indication(interactions, LocalIndication.current)
                .alpha(if (enabled) 1f else .38f)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (chosen) Icon(VeyraIcons.Check, null, Modifier.padding(end = 4.dp).size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimary)
            Text(label, style = MaterialTheme.typography.labelLarge,
                color = if (chosen) MaterialTheme.colorScheme.onPrimary else colors.onSurface)
        }
    }
}

@Composable
private fun ToggleSetting(title: String, description: String, checked: Boolean, enabled: Boolean = true,
    onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(VeyraRadii.Small)
    Row(
        modifier = modifier.fillMaxWidth().sizeIn(minHeight = VeyraSpacing.TouchTarget)
            .then(if (focused) Modifier.border(2.dp, LocalVeyraColors.current.primary, shape) else Modifier)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, interactionSource = interactions,
                indication = LocalIndication.current, onValueChange = onChange)
            .semantics(mergeDescendants = true) { stateDescription = strings.text(if (checked) TextKey.SettingOn else TextKey.SettingOff) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(VeyraSpacing.Medium), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // On iOS the switch is a native UISwitch drawn above Compose that takes its own taps, so it
        // must report them itself; the row stays toggleable for the rest of its area.
        NativeSwitch(checked = checked, onCheckedChange = if (enabled) onChange else null, enabled = enabled,
            modifier = Modifier.sizeIn(minWidth = VeyraSpacing.TouchTarget, minHeight = VeyraSpacing.TouchTarget).clearAndSetSemantics { })
    }
}

@Composable
private fun CrossfadeDurationInput(value: Long, enabled: Boolean, onAction: (SettingsAction) -> Unit) {
    val strings = LocalStrings.current
    var draft by remember(value) { mutableStateOf(crossfadeDurationText(value)) }
    val parsed = parseCrossfadeDurationMs(draft)
    val invalid = draft.isNotBlank() && parsed == null
    val errorLabel = strings.text(TextKey.CrossfadeDurationInvalid)
    val apply = { if (enabled && parsed != null && parsed != value) onAction(SettingsAction.SetCrossfadeDuration(parsed)) }
    Column(verticalArrangement = Arrangement.spacedBy(VeyraSpacing.Small)) {
        OutlinedTextField(
            value = draft, onValueChange = { if (it.length <= 32) draft = it }, enabled = enabled,
            label = { Text(strings.text(TextKey.CustomCrossfadeDuration)) }, singleLine = true,
            supportingText = { Text(if (invalid) errorLabel else strings.text(TextKey.CrossfadeCustomRange)) },
            isError = invalid, modifier = Modifier.fillMaxWidth().testTag("settings-crossfade-custom")
                .semantics { if (invalid) error(errorLabel) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { apply() }),
        )
        SettingsButton(strings.text(TextKey.ApplyCrossfadeDuration), onClick = apply,
            modifier = Modifier.testTag("settings-crossfade-apply"),
            enabled = enabled && parsed != null && parsed != value)
    }
}

@Composable
private fun SkipChoices(title: TextKey, current: Int, onSelect: (Int) -> Unit) {
    val strings = LocalStrings.current
    ChoiceGroup(strings.text(title)) {
        (listOf(5, 10, 15, 30, 45, 60) + current).distinct().sorted().forEach { seconds ->
            SettingsButton(strings.quantity(QuantityKey.Seconds, seconds), selected = current == seconds,
                modifier = Modifier.testTag("settings-${title.name}-$seconds"),
                onClick = { onSelect(seconds) })
        }
    }
}

@Composable
private fun TextSizeSetting(value: Float, onAction: (SettingsAction) -> Unit) {
    val strings = LocalStrings.current
    var draft by remember(value) { mutableStateOf(value.coerceIn(.75f, 2f)) }
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val label = strings.format(TextKey.TextSizeValue, strings.number((draft * 100).roundToInt().toLong()))
    val title = strings.text(TextKey.TextSize)
    Column(verticalArrangement = Arrangement.spacedBy(VeyraSpacing.Small)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        SettingsNote(strings.text(TextKey.TextSizeDescription))
        CompositionLocalProvider(LocalNativeControlAccessibility provides NativeControlAccessibility(
            label = title,
            value = strings.format(TextKey.PercentValue, strings.number((draft * 100).roundToInt().toLong())),
            languageTag = if (strings.isRtl) "ar" else "en",
        )) {
            NativeSlider(value = draft, onValueChange = { draft = it }, valueRange = .75f..2f, steps = 24,
                interactionSource = interactions,
                onValueChangeFinished = { onAction(SettingsAction.SetTextScale((draft * 20).roundToInt() / 20f)) },
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = VeyraSpacing.TouchTarget).testTag("settings-text-scale")
                    .then(if (focused) Modifier.border(2.dp, LocalVeyraColors.current.primary, RoundedCornerShape(VeyraRadii.Small)) else Modifier)
                    .semantics { contentDescription = title; stateDescription = label })
        }
        ChoiceGroup(title) {
            // Non-drag alternatives apply relative to the CURRENT Room value, including rapid clicks.
            SettingsButton(strings.text(TextKey.SmallerText), enabled = value > .75f,
                modifier = Modifier.testTag("settings-smaller-text"),
                onClick = { onAction(SettingsAction.StepTextScale(-1)) })
            SettingsButton(strings.text(TextKey.LargerText), enabled = value < 2f,
                modifier = Modifier.testTag("settings-larger-text"),
                onClick = { onAction(SettingsAction.StepTextScale(1)) })
            SettingsButton(strings.format(TextKey.PercentValue, strings.number(100)), selected = value == 1f,
                modifier = Modifier.testTag("settings-text-100"),
                onClick = { onAction(SettingsAction.SetTextScale(1f)) })
            SettingsButton(strings.format(TextKey.PercentValue, strings.number(200)), selected = value == 2f,
                modifier = Modifier.testTag("settings-text-200"),
                onClick = { onAction(SettingsAction.SetTextScale(2f)) })
        }
    }
}
