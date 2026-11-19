package dev.veyra.app

import dev.veyra.diagnostics.DiagnosticComponent as Component
import dev.veyra.diagnostics.DiagnosticContext
import dev.veyra.diagnostics.DiagnosticOperation.*
import dev.veyra.diagnostics.DiagnosticOutcome
import dev.veyra.downloads.DownloadDiagnosticOutcome
import dev.veyra.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal fun AppContainer.observeDiagnosticState(scope: CoroutineScope) {
    val capture = platform.diagnosticJournal ?: return
    val log = capture.diagnostics
    scope.launch {
        capture.state.map { it.enabled }.distinctUntilChanged().collectLatest { enabled ->
            if (!enabled) return@collectLatest
            coroutineScope {
                launch {
                    navigation.map { Triple(it.destination, it.detail?.kind, it.librarySection) }.distinctUntilChanged()
                        .collect { (destination, detail, section) -> log.record(Component.UI, NAVIGATE, phase = destination, detail = detail ?: section) }
                }
                launch {
                    navigation.map { it.playerOpen to it.queueOpen }.distinctUntilChanged().collect { (player, queue) ->
                        log.record(Component.UI, PLAYER_UI, value = if (player) 1 else 0, expected = if (queue) 1 else 0)
                    }
                }
                launch {
                    playback.state.map { PlaybackObservation(it.generation, it.phase, it.audioOrigin, it.failure?.code) }
                        .distinctUntilChanged().collect { state ->
                            log.record(Component.PLAYBACK, PLAYER_STATE, phase = state.phase, detail = state.origin,
                                failure = state.failure, generation = state.generation)
                        }
                }
                launch {
                    playback.state.map { it.crossfade?.phase }.distinctUntilChanged().collect { phase ->
                        log.record(Component.PLAYBACK, CROSSFADE, phase = phase)
                    }
                }
                launch {
                    store.state.map { it.history.size }.distinctUntilChanged().collect { count ->
                        log.record(Component.DATABASE, HISTORY, value = count.toLong())
                    }
                }
                launch {
                    store.status.collect { status -> when (status) {
                        is dev.veyra.data.StoreStatus.Ready -> log.record(Component.DATABASE, RESTORE,
                            value = if (status.recoveredFromBackup) 1 else 0)
                        is dev.veyra.data.StoreStatus.Failed -> log.record(Component.DATABASE, RESTORE, DiagnosticOutcome.FAILED,
                            failure = status.failure.code)
                        else -> Unit
                    } }
                }
                launch {
                    platform.downloadNetworkState.collect { state -> log.record(Component.NETWORK, SCHEDULER, phase = state) }
                }
                launch {
                    var previous = downloads.diagnosticEvents.value.lastOrNull()?.sequence ?: 0L
                    downloads.diagnosticEvents.collect { entries ->
                        entries.asSequence().filter { it.sequence > previous }.forEach { event ->
                            if (event.sequence > previous + 1) log.record(Component.DOWNLOAD, DOWNLOAD_STAGE,
                                DiagnosticOutcome.DROPPED, value = event.sequence - previous - 1)
                            previous = event.sequence
                            val outcome = when (event.outcome) {
                                DownloadDiagnosticOutcome.FAILURE -> DiagnosticOutcome.FAILED
                                DownloadDiagnosticOutcome.STALE -> DiagnosticOutcome.REJECTED
                                DownloadDiagnosticOutcome.SETTLED -> DiagnosticOutcome.SUCCEEDED
                                DownloadDiagnosticOutcome.STARTED -> DiagnosticOutcome.STARTED
                                DownloadDiagnosticOutcome.CANCELLED -> DiagnosticOutcome.CANCELLED
                            }
                            log.record(Component.DOWNLOAD, DOWNLOAD_STAGE, outcome, event.stage,
                                failure = event.failure?.code, detail = event.failure?.reason,
                                durationMs = event.durationMs, value = event.lastCommitted?.attemptNumber?.toLong(),
                                expected = event.expectedAttemptNumber?.toLong(), generation = event.runSequence, link = event.downloadKey)
                        }
                    }
                }
                launch {
                    val active = mutableMapOf<String, ProgressObservation>() // Room IDs stay in process memory.
                    downloads.physicalStates.collect { jobs ->
                        (active.keys - jobs.keys).forEach { id -> active.remove(id)?.let { removed ->
                            log.end(Component.DOWNLOAD, DOWNLOAD_PROGRESS, removed.context, DiagnosticOutcome.CANCELLED)
                        } }
                        for ((id, job) in jobs) {
                            val before = active[id]
                            val terminal = job.status in terminalDownloadStates
                            if (before == null && terminal) continue
                            val percent = job.totalBytes?.takeIf { it > 0 }?.let {
                                ((job.bytesDownloaded.toDouble() / it).coerceIn(0.0, 1.0) * 4).toInt() * 25
                            }
                            if (before != null && before.status == job.status && before.percent == percent) continue
                            val context = before?.context ?: log.begin(Component.DOWNLOAD, DOWNLOAD_PROGRESS,
                                phase = job.status, link = log.downloadKey(id))
                            if (terminal) {
                                log.end(Component.DOWNLOAD, DOWNLOAD_PROGRESS, context, when (job.status) {
                                    DownloadStatus.COMPLETE -> DiagnosticOutcome.SUCCEEDED
                                    DownloadStatus.FAILED -> DiagnosticOutcome.FAILED
                                    else -> DiagnosticOutcome.CANCELLED
                                }, phase = job.status, value = percent?.toLong())
                                active.remove(id)
                            } else {
                                active[id] = ProgressObservation(job.status, percent, context)
                                log.record(Component.DOWNLOAD, DOWNLOAD_PROGRESS, phase = job.status, context = context,
                                    value = percent?.toLong(), expected = if (percent == null) null else 100)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class PlaybackObservation(val generation: Long, val phase: dev.veyra.playback.PlaybackPhase,
    val origin: dev.veyra.playback.AudioOrigin?, val failure: dev.veyra.model.FailureCode?)
private data class ProgressObservation(val status: DownloadStatus, val percent: Int?, val context: DiagnosticContext?)
private val terminalDownloadStates = setOf(DownloadStatus.COMPLETE, DownloadStatus.FAILED, DownloadStatus.CANCELLED)
