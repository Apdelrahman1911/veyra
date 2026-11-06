package dev.veyra.ui.settings

import dev.veyra.model.AppSettings
import dev.veyra.model.LanguagePreference
import dev.veyra.model.ThemeMode
import dev.veyra.playback.EngineCapabilities
import kotlin.math.roundToInt

/** Single-field intents. Apply against the latest settings INSIDE the Room transaction. */
sealed interface SettingsAction {
    data class SetLanguage(val value: LanguagePreference) : SettingsAction
    data class SetTheme(val value: ThemeMode) : SettingsAction
    data class SetTextScale(val value: Float) : SettingsAction
    data class StepTextScale(val direction: Int) : SettingsAction
    data class SetOfflineOnly(val value: Boolean) : SettingsAction
    data class SetPodcastSpeed(val value: Float) : SettingsAction
    data class SetSkipBack(val seconds: Int) : SettingsAction
    data class SetSkipForward(val seconds: Int) : SettingsAction
    data class SetCrossfadeEnabled(val value: Boolean) : SettingsAction
    data class SetCrossfadeDuration(val milliseconds: Long) : SettingsAction
    data class SetDownloadParallelism(val value: Int) : SettingsAction
}

/** Does not capture a screen snapshot or replace unrelated settings/show-specific speeds. */
fun SettingsAction.applyTo(current: AppSettings): AppSettings = when (this) {
    is SettingsAction.SetLanguage -> current.copy(language = value)
    is SettingsAction.SetTheme -> current.copy(theme = value)
    is SettingsAction.SetTextScale -> {
        require(value.isFinite() && value in .75f..2f)
        current.copy(textScale = value)
    }
    is SettingsAction.StepTextScale -> {
        require(direction == -1 || direction == 1)
        val next = ((current.textScale * 20).roundToInt() + direction).coerceIn(15, 40) / 20f
        current.copy(textScale = next)
    }
    is SettingsAction.SetOfflineOnly -> current.copy(offlineOnly = value)
    is SettingsAction.SetPodcastSpeed -> {
        require(value.isFinite() && value in .5f..2f)
        current.copy(speed = value)
    }
    is SettingsAction.SetSkipBack -> {
        require(seconds in 1..300)
        current.copy(skipBackSeconds = seconds)
    }
    is SettingsAction.SetSkipForward -> {
        require(seconds in 1..300)
        current.copy(skipForwardSeconds = seconds)
    }
    is SettingsAction.SetCrossfadeEnabled -> current.copy(crossfadeEnabled = value)
    is SettingsAction.SetCrossfadeDuration -> {
        require(milliseconds in 100..30_000)
        current.copy(crossfadeDurationMs = milliseconds)
    }
    is SettingsAction.SetDownloadParallelism -> {
        require(value in 1..4)
        current.copy(downloadParallelism = value)
    }
}

/** Bounded decimal input; both Arabic digit sets and Arabic decimal punctuation are accepted. */
internal fun parseCrossfadeDurationMs(input: String): Long? {
    if (input.length > 32) return null
    val normalized = input.trim().map { char ->
        when (char) {
            in '٠'..'٩' -> '0' + (char - '٠')
            in '۰'..'۹' -> '0' + (char - '۰')
            '٫', ',' -> '.'
            else -> char
        }
    }.joinToString("")
    if (normalized.isEmpty() || !normalized.matches(Regex("[0-9]{0,2}(?:\\.[0-9]{1,3})?"))) return null
    val whole = normalized.substringBefore('.').ifEmpty { "0" }.toLongOrNull() ?: return null
    val fraction = normalized.substringAfter('.', "").padEnd(3, '0').toLongOrNull() ?: return null
    return (whole * 1_000 + fraction).takeIf { it in 100..30_000 }
}

internal fun crossfadeDurationText(milliseconds: Long): String {
    val fraction = milliseconds % 1_000
    return (milliseconds / 1_000).toString() + if (fraction == 0L) ""
    else "." + fraction.toString().padStart(3, '0').trimEnd('0')
}

internal fun supportedPodcastSpeeds(capabilities: EngineCapabilities, current: Float): List<Float> =
    (listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f) + current).distinct().sorted().filter {
        it.isFinite() && it in .5f..2f && it >= capabilities.minSpeed && it <= capabilities.maxSpeed
    }
