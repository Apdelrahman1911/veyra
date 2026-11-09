package dev.veyra.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.veyra.ui.design.LocalStrings
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraSpacing

/** A compact status opens scrollable details without displacing the phone/landscape player. */
@Composable
internal fun MediaRuntimeIndicator(status: MediaRuntimeStatus, offline: Boolean, prepare: () -> Unit) {
    val strings = LocalStrings.current
    var showDetails by remember { mutableStateOf(false) }
    val phaseKey = when (status.phase) {
        MediaRuntimePhase.READY -> TextKey.PlaybackToolsReady
        MediaRuntimePhase.REQUIRED -> TextKey.PlaybackTools
        MediaRuntimePhase.VERIFYING -> TextKey.PlaybackToolsVerifying
        MediaRuntimePhase.DOWNLOADING -> TextKey.PlaybackToolsDownloading
        MediaRuntimePhase.INSTALLING -> TextKey.PlaybackToolsInstalling
        MediaRuntimePhase.ERROR -> TextKey.PlaybackToolsFailed
    }
    val active = status.phase in setOf(MediaRuntimePhase.VERIFYING, MediaRuntimePhase.DOWNLOADING, MediaRuntimePhase.INSTALLING)
    Surface(color = if (status.phase == MediaRuntimePhase.ERROR) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().testTag("media-runtime-status")) {
            TextButton(onClick = { showDetails = true },
                modifier = Modifier.fillMaxWidth().runtimeActionTarget().testTag("media-runtime-open")) {
                Text(strings.text(phaseKey), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (active) {
                val total = status.totalBytes?.takeIf { it > 0 }
                if (status.phase == MediaRuntimePhase.DOWNLOADING && total != null) {
                    LinearProgressIndicator(progress = { (status.completedBytes.toDouble() / total).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().testTag("media-runtime-progress"))
                } else LinearProgressIndicator(Modifier.fillMaxWidth().testTag("media-runtime-progress"))
            }
        }
    }
    if (showDetails) AlertDialog(
        onDismissRequest = { showDetails = false },
        modifier = Modifier.testTag("media-runtime-details"),
        title = { Text(strings.text(TextKey.PlaybackTools), modifier = Modifier.fillMaxWidth()) },
        text = {
            // Every detail uses the dialog's bounded content width, not a different intrinsic
            // width for each short bidi-isolated value. Keep full copy and scrolling at 200%.
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.text(phaseKey), modifier = Modifier.fillMaxWidth())
                status.component?.let { component ->
                    val componentKey = when (component) {
                        MediaRuntimeComponent.NODE -> TextKey.PlaybackToolNode
                        MediaRuntimeComponent.CHROMIUM -> TextKey.PlaybackToolChromium
                        MediaRuntimeComponent.FFMPEG -> TextKey.PlaybackToolFfmpeg
                    }
                    Text(strings.format(TextKey.PlaybackToolsComponent, strings.text(componentKey)),
                        modifier = Modifier.fillMaxWidth())
                }
                if (status.phase == MediaRuntimePhase.DOWNLOADING) {
                    val completed = strings.byteSize(status.completedBytes.coerceAtLeast(0))
                    Text(status.totalBytes?.takeIf { it > 0 }?.let {
                        strings.format(TextKey.PlaybackToolsDownloadProgress, completed, strings.byteSize(it))
                    } ?: strings.format(TextKey.PlaybackToolsDownloadedBytes, completed),
                        modifier = Modifier.fillMaxWidth())
                }
                status.failure?.let {
                    Text(strings.errorFor(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
                }
                Text(strings.text(TextKey.PlaybackToolsRequired), modifier = Modifier.fillMaxWidth())
                Text(strings.text(TextKey.PlaybackToolsPrivacy), modifier = Modifier.fillMaxWidth())
                if (offline) Text(strings.text(TextKey.Offline), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            if (!active && status.phase != MediaRuntimePhase.READY) TextButton(onClick = prepare, enabled = !offline,
                modifier = Modifier.runtimeActionTarget().testTag("media-runtime-prepare")) {
                Text(strings.text(if (status.phase == MediaRuntimePhase.ERROR) TextKey.Retry else TextKey.PlaybackToolsPrepare))
            } else TextButton(onClick = { showDetails = false }, modifier = Modifier.runtimeActionTarget()) {
                Text(strings.text(TextKey.Close))
            }
        },
        dismissButton = {
            if (!active && status.phase != MediaRuntimePhase.READY) TextButton(
                onClick = { showDetails = false }, modifier = Modifier.runtimeActionTarget()) {
                Text(strings.text(TextKey.Close))
            }
        },
    )
}

// Material's compact text-button visual can otherwise be only 40dp tall, including when disabled.
private fun Modifier.runtimeActionTarget(): Modifier =
    sizeIn(minWidth = VeyraSpacing.TouchTarget, minHeight = VeyraSpacing.TouchTarget)
