package dev.veyra.ui.settings

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
            contentPadding = PaddingValues(horizontal = VeyraSpacing.Large, vertical = VeyraSpacing.Section),
            verticalArrangement = Arrangement.spacedBy(VeyraSpacing.Section),
        ) {
            item("title") {
                Text(strings.text(TextKey.Settings), style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() })
            }
            item("playback") {
                SettingsSection(strings.text(TextKey.Playback)) {
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
                    Text(strings.text(TextKey.CrossfadeHeadroom), style = MaterialTheme.typography.titleSmall)
                    Text(strings.text(TextKey.CrossfadeHeadroomDescription), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item("podcasts") {
                SettingsSection(strings.text(TextKey.PodcastDefaults)) {
                    Text(strings.text(TextKey.DefaultPodcastSpeedDescription), style = MaterialTheme.typography.bodyMedium)
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
                SettingsSection(strings.text(TextKey.Appearance)) {
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
                SettingsSection(strings.text(TextKey.Offline)) {
                    ToggleSetting(strings.text(TextKey.OfflineMode), strings.text(TextKey.OfflineModeDescription),
                        checked = settings.offlineOnly,
                        modifier = Modifier.testTag("settings-offline"),
                        onChange = { onSettingsChange(SettingsAction.SetOfflineOnly(it)) })
                }
            }
            item("downloads") {
                SettingsSection(strings.text(TextKey.Downloads)) {
                    Text(strings.text(TextKey.ParallelDownloadsDescription), style = MaterialTheme.typography.bodyMedium)
                    ChoiceGroup(strings.text(TextKey.ParallelDownloads)) {
                        (1..4).forEach { count ->
                            SettingsButton(strings.number(count.toLong()), selected = settings.downloadParallelism == count,
                                modifier = Modifier.testTag("settings-parallelism-$count"),
                                onClick = { onSettingsChange(SettingsAction.SetDownloadParallelism(count)) })
                        }
                    }
                    Text(strings.text(TextKey.DownloadExecutionNotice), style = MaterialTheme.typography.bodyMedium)
                    Text(storageBytes?.takeIf { it >= 0 }?.let { strings.format(TextKey.StorageUsed, strings.byteSize(it)) }
                        ?: strings.text(TextKey.StorageUsageUnavailable), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("settings-storage-value"))
                    SettingsButton(strings.text(TextKey.ManageStorage), onClick = onManageStorage,
                        modifier = Modifier.testTag("settings-manage-storage"))
                }
            }
            item("about") {
                SettingsSection(strings.text(TextKey.About)) {
                    Text(strings.text(TextKey.LocalDataNotice), style = MaterialTheme.typography.bodyMedium)
                    Text(strings.text(TextKey.Privacy), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() })
                    Text(strings.text(TextKey.PublisherNetworkNotice), style = MaterialTheme.typography.bodyMedium)
                    Text(strings.text(TextKey.CompleteTitlePrivacyNotice), style = MaterialTheme.typography.bodyMedium)
                    SettingsButton(strings.text(TextKey.Licenses), onClick = onOpenLicenses,
                        modifier = Modifier.testTag("settings-licenses"))
                    Text(strings.text(TextKey.KeyboardShortcuts), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() })
                    Text(strings.text(TextKey.ShortcutSearch), style = MaterialTheme.typography.bodyMedium)
                    Text(strings.text(TextKey.ShortcutClose), style = MaterialTheme.typography.bodyMedium)
                    Text(strings.text(TextKey.ShortcutPlayback), style = MaterialTheme.typography.bodyMedium)
                    Text(strings.text(TextKey.KeyboardHelp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), color = LocalVeyraColors.current.surface,
        shape = RoundedCornerShape(VeyraRadii.Card)) {
        Column(Modifier.padding(VeyraSpacing.Large), verticalArrangement = Arrangement.spacedBy(VeyraSpacing.Large)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(VeyraSpacing.Small)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        FlowRow(modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(VeyraSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(VeyraSpacing.Small)) { content() }
    }
}

/** Wrapping, minimum-48dp buttons with a high-contrast border for keyboard focus. */
@Composable
private fun SettingsButton(label: String, onClick: () -> Unit, selected: Boolean? = null, enabled: Boolean = true,
    modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val colors = LocalVeyraColors.current
    val shape = RoundedCornerShape(VeyraRadii.Small)
    OutlinedButton(
        onClick = onClick, enabled = enabled, interactionSource = interactions,
        // Reserve space for a separate focus ring so focusing an already-selected choice is
        // distinguishable, without moving controls or reducing the inner 48dp target.
        modifier = modifier.then(if (focused) Modifier.border(2.dp, colors.primary, shape) else Modifier)
            .padding(VeyraSpacing.Tiny)
            .sizeIn(minWidth = VeyraSpacing.TouchTarget, minHeight = VeyraSpacing.TouchTarget)
            .semantics { if (selected != null) this.selected = selected },
        border = BorderStroke(if (selected == true) 2.dp else 1.dp,
            if (selected == true) colors.primary else colors.outline),
        shape = shape,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected == true) colors.primaryContainer else colors.surface,
            contentColor = if (selected == true) colors.onPrimaryContainer else colors.onSurface,
        ),
    ) { Text(label) }
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
            .padding(VeyraSpacing.Small),
        horizontalArrangement = Arrangement.spacedBy(VeyraSpacing.Medium), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VeyraSpacing.Tiny)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled,
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
        Text(strings.text(TextKey.TextSizeDescription), style = MaterialTheme.typography.bodyMedium)
        Slider(value = draft, onValueChange = { draft = it }, valueRange = .75f..2f, steps = 24,
            interactionSource = interactions,
            onValueChangeFinished = { onAction(SettingsAction.SetTextScale((draft * 20).roundToInt() / 20f)) },
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = VeyraSpacing.TouchTarget).testTag("settings-text-scale")
                .then(if (focused) Modifier.border(2.dp, LocalVeyraColors.current.primary, RoundedCornerShape(VeyraRadii.Small)) else Modifier)
                .semantics { contentDescription = title; stateDescription = label })
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
