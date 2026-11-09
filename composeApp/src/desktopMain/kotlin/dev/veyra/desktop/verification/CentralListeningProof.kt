package dev.veyra.desktop.verification

import dev.kmpyt.YouTubeNativePlaybackLimits
import dev.kmpyt.extraction.PlannedDownload
import dev.kmpyt.extraction.SourcePlaybackRequest
import dev.kmpyt.extraction.TrackRole
import dev.kmpyt.extraction.TransportKind
import dev.kmpyt.youtube.YouTubeBrowseText
import dev.veyra.app.AppContainer
import dev.veyra.app.AppInitialization
import dev.veyra.data.AcquisitionRepositoryState
import dev.veyra.data.executionContext
import dev.veyra.desktop.DesktopPlatformServices
import dev.veyra.desktop.runtime.DesktopMediaExecutionPolicy
import dev.veyra.desktop.runtime.DesktopProcessContext
import dev.veyra.model.*
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.LocalMediaValidationInput
import dev.veyra.playback.PlaybackCommand
import dev.veyra.playback.PlaybackPhase
import dev.veyra.playback.PlaybackResource
import dev.veyra.playback.PlaybackState
import dev.veyra.playback.PlaybackTarget
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.playback.getOrThrow
import dev.veyra.source.BrowseKind
import dev.veyra.source.BrowseRequest
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.execution.AcquisitionExecutionContext
import dev.veyra.source.execution.AcquisitionExecutionGateway
import dev.veyra.source.execution.AcquisitionGateway
import dev.veyra.source.execution.AcquisitionInspection
import dev.veyra.source.execution.AcquisitionPlaybackSource
import dev.veyra.source.execution.AcquisitionTransferProgress
import dev.veyra.source.execution.AcquisitionTransferResult
import dev.veyra.source.execution.CollectionContinuation
import dev.veyra.source.execution.KmpFirstResult
import dev.veyra.source.execution.KmpMediaGateway
import dev.veyra.source.execution.PreparedAcquisitionDownload
import dev.veyra.source.execution.SourceCollectionPage
import dev.veyra.source.network.SafeHttp
import dev.veyra.source.network.SafeHttpRequest
import dev.veyra.source.network.PublicUrlPolicy
import dev.veyra.source.network.createSafeHttp
import dev.veyra.source.youtube.YouTubeKmpMediaGateway
import dev.veyra.source.youtube.YouTubeMediaSource
import dev.veyra.source.youtube.YouTubeTransferProgress
import dev.veyra.source.youtube.YouTubeTransferResult
import dev.veyra.ui.music.MusicUiAction
import io.ktor.http.URLBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Safe partial evidence, not an exception containing a URL, response, or native log. */
internal class CentralListeningProofFailure(val report: String) : Exception("Central listening verification did not complete")

/**
 * The proof-owned facade must expose the typed acquisition/execution ports itself. Returning a
 * legacy-only [KmpMediaGateway] made the application's PolicyMediaGateway reject every typed
 * acquisition before the production delegate was reached.
 */
internal interface CentralProofGateway : KmpMediaGateway, AcquisitionGateway, AcquisitionExecutionGateway

/**
 * Opt-in D005 listening proof using the ONE supplied production AppContainer and native engine.
 * This is deliberately narrower than product playback: the authorized live run cannot switch
 * videos, skip a raw first card, use a direct fixture, or guess an unknown transfer size.
 * Neither a successful phase nor a real non-null output device attests physical audible output.
 */
internal class CentralListeningProof(private val silentAudio: Boolean = false) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val closeFailed = AtomicBoolean()
    private val mode = AtomicReference<String?>()
    private val appOwner = AtomicReference<AppContainer?>()
    private var sourceOwner: YouTubeMediaSource? = null
    private val metadata = CentralProofMetadata(beforeRequest = ::requireOnline)
    private val authorization = CentralProofAuthorization(::requireOnline, metadata::video)
    private var stage = "not-started"
    private val milestones = mutableListOf<String>()
    private var observedNative: CentralNativeEvidence? = null
    private var observedIsolation: CentralNetworkEvidence? = null
    private var restoredFrom: CentralProcessEvidence? = null
    private var currentProcess: CentralProcessEvidence? = null

    internal fun executionPolicy() = DesktopMediaExecutionPolicy(
        authorizeDownload = { authorization.authorizeDownload(it) },
        authorizeSabrPlayback = { authorization.authorizePlayback(it) },
        nativePlaybackLimits = YouTubeNativePlaybackLimits(maxCacheBytes = CentralProofPolicy.MAX_BYTES),
    )

    fun gateway(source: YouTubeMediaSource): KmpMediaGateway = synchronized(this) {
        proofRequire(!closed.get() && sourceOwner == null, "GATEWAY_OWNER_CHANGED")
        sourceOwner = source
        authorization.wrap(YouTubeKmpMediaGateway(source))
    }

    suspend fun runOnline(app: AppContainer): String = runPhase("online", app) {
        stage = "empty-dedicated-library"
        val initial = app.store.acquisitionView()
        requireEmptyLibrary(initial.snapshot)
        requireEmptyAcquisition(initial.acquisition)
        val files = CentralProofFiles(Path.of(app.platform.dataDirectory))
        files.requireNoMarker()
        proofRequire(app.files.bytesUsed() == 0L, "NONEMPTY_MEDIA_DIRECTORY")
        proofRequire(!initial.snapshot.settings.offlineOnly, "ONLINE_PHASE_IS_OFFLINE")
        val process = currentProcess!!
        milestones += "empty-dedicated-library"

        stage = "approved-channel-discovery"
        // This filtered discovery page is NOT the raw-first execution search. We choose only
        // one discovered video and verify its author independently; never try another on refusal.
        val page = app.platform.youtube.browse(BrowseRequest(BrowseKind.CHANNEL,
            CentralProofPolicy.CHANNEL_URL + "/videos"))
        val discovered = page.items.firstOrNull() ?: proofFail("NO_DISCOVERED_VIDEO", FailureCode.NOT_FOUND)
        CentralProofPolicy.requireDiscovery(discovered)
        val complete = metadata.video(discovered.source.canonicalId)
        val item = discovered.copy(title = complete.title, creator = complete.author)
        authorization.discovered(item)
        milestones += "approved-discovery-complete-title"

        stage = "typed-raw-first-search-and-native-play"
        app.onMusicAction(MusicUiAction.Play(item))
        val firstPlaying = awaitPlayback(app, timeoutMs = 180_000) {
            it.currentItem?.id == item.id && it.isPlaying && it.phase == PlaybackPhase.PLAYING && it.positionMs >= 600
        }
        val selection = firstPlaying.currentSelection ?: proofFail("TYPED_SELECTION_MISSING")
        proofRequire(selection.selectionState == OccurrenceSelectionState.BOUND &&
            selection.target is PlaybackTarget.Catalog && selection.bindingId != null,
            "TYPED_SELECTION_NOT_BOUND", FailureCode.PERMISSION_DENIED)
        val context = selection.executionContext ?: proofFail("TYPED_EXECUTION_CONTEXT_MISSING")
        authorization.requireTypedContext(context)
        val durable = app.store.acquisition.playbackView()
        val joined = try { durable.acquisition.executionContext(context.binding.id) }
        catch (_: Exception) { proofFail("TYPED_ROOM_CONTEXT_MISSING", FailureCode.RECOVERY_REQUIRED) }
        val occurrence = durable.acquisition.occurrences[selection.occurrenceId]
            ?: proofFail("TYPED_OCCURRENCE_MISSING", FailureCode.RECOVERY_REQUIRED)
        proofRequire(joined == context && occurrence.selectionState == OccurrenceSelectionState.BOUND &&
            occurrence.requestId == context.request.id && occurrence.bindingId == context.binding.id &&
            occurrence.subjectId == context.subject.id && occurrence.controlsMode == PlaybackControlsMode.MUSIC &&
            durable.acquisition.queues.getValue(QueueContext.MUSIC).currentOccurrenceId == occurrence.id &&
            durable.snapshot.media[item.id]?.title == item.title,
            "TYPED_DURABLE_IDENTITY_CHANGED", FailureCode.PERMISSION_DENIED)
        milestones += "complete-title-typed-raw-first-bound-in-room"

        stage = "native-advance-pause-resume-forward-back-seek"
        observedNative = exerciseNative(app, firstPlaying)
        milestones += "native-advance-pause-resume-forward-back-seek"

        stage = "same-binding-typed-production-download"
        // Normal UI command captures the currently displayed immutable receipt. No second search.
        app.onMusicAction(MusicUiAction.Download(item))
        val download = awaitCompletedTypedDownload(app, context)
        proofRequire(download.asset.representationId == authorization.downloadRepresentationId(),
            "DOWNLOAD_REPRESENTATION_CHANGED", FailureCode.PERMISSION_DENIED)
        verifyTypedAsset(app, download.asset)
        val counts = authorization.snapshot()
        proofRequire(counts.acquireCalls == 1 && counts.preparePlaybackCalls == 1 && counts.prepareDownloadCalls == 1 &&
            counts.transferCalls == 1 && counts.downloadCalls == 1 && counts.downloadPlans == 1 &&
            counts.searchCalls == 0 && counts.resolveCalls == 0 && counts.metadataChecks == 1,
            "ACQUISITION_COUNT_CHANGED", FailureCode.PERMISSION_DENIED)
        milestones += "same-binding-kmpyt-typed-download-native-validated-sha256-room-complete"

        stage = "persist-offline-restart-marker"
        app.playback.dispatch(PlaybackCommand.FlushProgress).getOrThrow()
        app.changeSettings { it.copy(offlineOnly = true) }
        awaitCondition(20_000) { app.store.state.value.settings.offlineOnly }
        app.playback.dispatch(PlaybackCommand.FlushProgress).getOrThrow()
        val playbackView = app.store.acquisition.playbackView()
        val queue = playbackView.acquisition.queues.getValue(QueueContext.MUSIC)
        val checkpoint = playbackView.checkpoints[occurrence.id] ?: proofFail("NATIVE_CHECKPOINT_MISSING")
        proofRequire(queue.currentOccurrenceId == occurrence.id && occurrence.id in queue.displayedOrder &&
            queue.positionMs > 0 && checkpoint.bindingId == context.binding.id && checkpoint.positionMs > 0 &&
            abs(queue.positionMs - checkpoint.positionMs) <= 500,
            "POSITION_BINDING_CHANGED", FailureCode.PERMISSION_DENIED)
        files.writeNew(CentralProofMarker(
            process = process, mediaId = item.id, completeTitle = item.title,
            discoveryVideoId = item.source.canonicalId,
            firstResultVideoId = context.recording.canonicalId,
            firstResultTitle = context.receipt.observation.entry.title
                ?: proofFail("RAW_FIRST_TITLE_MISSING", FailureCode.PARSE_FAILURE),
            requestId = context.request.id.value, receiptId = context.receipt.id.value,
            bindingId = context.binding.id.value, recordingId = context.recording.id.value,
            subjectId = context.subject.id.value, occurrenceId = occurrence.id.value,
            downloadId = download.jobId, assetId = download.asset.id.value,
            sizeBytes = download.asset.sizeBytes, sha256 = download.asset.sha256!!,
            representationId = download.asset.representationId!!,
            measuredDurationMs = download.asset.measuredDurationMs!!,
            entryId = occurrence.id.value, positionMs = queue.positionMs,
            native = observedNative!!,
        ))
        milestones += "offline-setting-typed-binding-hash-position-marker-persisted"
        stage = "online-phase-verified-restart-still-required"
    }

    suspend fun runOffline(app: AppContainer): String = runPhase("offline", app) {
        stage = "new-process-and-native-network-denial"
        val marker = CentralProofFiles(Path.of(app.platform.dataDirectory)).read()
        marker.requireValid()
        val process = currentProcess!!
        CentralProofPolicy.requireTerminatedPredecessor(marker.process, process)
        restoredFrom = marker.process
        observedIsolation = CentralNetworkEvidence.verify(marker.process.networkNamespace)
        milestones += "previous-online-process-terminated-different-process"
        milestones += "linux-private-network-namespace-all-current-threads-no-public-route-literal-tcp-denied"

        stage = "room-offline-typed-restore-and-file-validation"
        val playbackView = app.store.acquisition.playbackView()
        val state = playbackView.acquisition
        val snapshot = playbackView.snapshot
        proofRequire(snapshot.settings.offlineOnly, "OFFLINE_SETTING_NOT_RESTORED")
        val item = snapshot.media[marker.mediaId] ?: proofFail("ORIGINAL_ITEM_NOT_RESTORED")
        CentralProofPolicy.requireDiscovery(item)
        proofRequire(item.title == marker.completeTitle && item.source.canonicalId == marker.discoveryVideoId,
            "ROOM_MARKER_IDENTITY_CHANGED", FailureCode.PERMISSION_DENIED)
        val request = state.requests[AcquisitionRequestId(marker.requestId)]
            ?: proofFail("TYPED_REQUEST_NOT_RESTORED", FailureCode.RECOVERY_REQUIRED)
        val capture = request.request.metadata as? MediaCapture
            ?: proofFail("TYPED_CATALOG_CAPTURE_MISSING", FailureCode.RECOVERY_REQUIRED)
        proofRequire(request.request.completeTitle == marker.completeTitle && request.request.kind == AcquisitionKind.MEDIA &&
            capture.media.id == marker.mediaId && capture.media.title == marker.completeTitle &&
            capture.media.source.provider == SourceProvider.YOUTUBE &&
            capture.media.source.canonicalId == marker.discoveryVideoId,
            "ROOM_MARKER_REQUEST_CHANGED", FailureCode.PERMISSION_DENIED)
        val receipt = state.receipts[FirstResultReceiptId(marker.receiptId)] as? FirstResultReceipt.Observed
            ?: proofFail("TYPED_RECEIPT_NOT_RESTORED", FailureCode.RECOVERY_REQUIRED)
        proofRequire(receipt.observation.requestId == request.request.id &&
            receipt.observation.queryTitle == marker.completeTitle &&
            receipt.observation.entry.ordinal == 0 && receipt.observation.entry.kind == AcquisitionEntryKind.VIDEO &&
            receipt.observation.entry.id == marker.firstResultVideoId &&
            receipt.observation.entry.title == marker.firstResultTitle &&
            receipt.observation.entry.issue == null &&
            receipt.observation.canonicalUrl == CentralProofPolicy.watch(marker.firstResultVideoId),
            "ROOM_MARKER_RECEIPT_CHANGED", FailureCode.PERMISSION_DENIED)
        val recording = state.recordings[SourceRecordingId(marker.recordingId)]
            ?: proofFail("TYPED_RECORDING_NOT_RESTORED", FailureCode.RECOVERY_REQUIRED)
        val subject = state.subjects[ExecutableSubjectId(marker.subjectId)] as? ExecutableSubject.CatalogMedia
            ?: proofFail("TYPED_SUBJECT_NOT_RESTORED", FailureCode.RECOVERY_REQUIRED)
        val binding = state.bindings[ExecutionBindingId(marker.bindingId)]
            ?: proofFail("TYPED_BINDING_NOT_RESTORED", FailureCode.RECOVERY_REQUIRED)
        proofRequire(recording.canonicalId == marker.firstResultVideoId &&
            recording.canonicalUrl == CentralProofPolicy.watch(marker.firstResultVideoId) &&
            subject.mediaId == marker.mediaId && subject.id.value == marker.mediaId &&
            binding.origin == ExecutionBindingOrigin.TYPED && binding.firstReceiptId == receipt.id &&
            binding.recordingId == recording.id && binding.subjectId == subject.id,
            "ROOM_MARKER_TYPED_LINEAGE_CHANGED", FailureCode.PERMISSION_DENIED)
        val occurrence = state.occurrences[ExecutionOccurrenceId(marker.occurrenceId)]
            ?: proofFail("TYPED_OCCURRENCE_NOT_RESTORED", FailureCode.RECOVERY_REQUIRED)
        proofRequire(occurrence.origin == AcquisitionOrigin.TYPED &&
            occurrence.selectionState == OccurrenceSelectionState.BOUND &&
            occurrence.requestId == request.request.id && occurrence.bindingId == binding.id &&
            occurrence.subjectId == subject.id && occurrence.controlsMode == PlaybackControlsMode.MUSIC,
            "ROOM_MARKER_OCCURRENCE_CHANGED", FailureCode.PERMISSION_DENIED)
        val queue = state.queues[QueueContext.MUSIC]
            ?: proofFail("TYPED_QUEUE_NOT_RESTORED", FailureCode.RECOVERY_REQUIRED)
        val checkpoint = playbackView.checkpoints[occurrence.id]
            ?: proofFail("NATIVE_CHECKPOINT_NOT_RESTORED", FailureCode.RECOVERY_REQUIRED)
        proofRequire(queue.currentOccurrenceId == occurrence.id && occurrence.id in queue.displayedOrder &&
            queue.positionMs == marker.positionMs && checkpoint.bindingId == binding.id &&
            abs(checkpoint.positionMs - marker.positionMs) <= 500,
            "ROOM_MARKER_POSITION_CHANGED", FailureCode.PERMISSION_DENIED)
        val download = requireTypedDownload(state, try { playbackView.acquisition.executionContext(binding.id) }
        catch (_: Exception) { proofFail("TYPED_CONTEXT_NOT_RESTORED", FailureCode.RECOVERY_REQUIRED) })
        proofRequire(download.jobId == marker.downloadId && download.asset.id.value == marker.assetId &&
            download.asset.sizeBytes == marker.sizeBytes && download.asset.sha256 == marker.sha256 &&
            download.asset.representationId == marker.representationId &&
            download.asset.measuredDurationMs == marker.measuredDurationMs,
            "RESTORED_ASSET_CHANGED", FailureCode.CORRUPT_MEDIA)
        verifyTypedAsset(app, download.asset)
        val restored = app.playback.state.value
        proofRequire(!restored.isPlaying && !restored.desiredPlaying && restored.phase == PlaybackPhase.PAUSED &&
            restored.currentItem?.id == marker.mediaId && restored.currentEntryId == occurrence.id.value &&
            restored.currentSelection?.bindingId == binding.id && restored.positionMs == marker.positionMs,
            "RESTORE_AUTOPLAY_OR_POSITION_CHANGED", FailureCode.PERMISSION_DENIED)
        milestones += "room-offline-typed-binding-hash-position-restored-no-autoplay"

        stage = "local-native-play-and-seek-with-os-egress-denied"
        app.onMusicAction(MusicUiAction.PlayQueueEntry(QueueContext.MUSIC, marker.occurrenceId))
        val playing = awaitPlayback(app, timeoutMs = 180_000) {
            it.currentEntryId == marker.occurrenceId && it.currentSelection?.bindingId?.value == marker.bindingId &&
                it.isPlaying && it.phase == PlaybackPhase.PLAYING && it.positionMs > marker.positionMs + 600
        }
        observedNative = exerciseNative(app, playing)
        proofRequire(authorization.snapshot().let {
            it.searchCalls == 0 && it.resolveCalls == 0 && it.downloadCalls == 0 && it.acquireCalls == 0 &&
                it.restoreCalls == 0 && it.inspectCalls == 0 && it.preparePlaybackCalls == 0 &&
                it.prepareDownloadCalls == 0 && it.transferCalls == 0
        }, "OFFLINE_GATEWAY_WAS_ENTERED", FailureCode.OFFLINE_POLICY)
        val after = CentralNetworkEvidence.verify(marker.process.networkNamespace)
        proofRequire(after.networkNamespace == observedIsolation?.networkNamespace, "NATIVE_NETWORK_NAMESPACE_CHANGED")
        observedIsolation = after
        app.playback.dispatch(PlaybackCommand.FlushProgress).getOrThrow()
        milestones += "same-app-native-local-advance-pause-resume-forward-back-seek-no-remote-gateway"
        stage = "offline-phase-verified-physical-audio-unverified"
    }

    private suspend fun runPhase(value: String, app: AppContainer, operation: suspend () -> Unit): String {
        proofRequire(!closed.get() && mode.compareAndSet(null, value), "PROOF_ALREADY_USED")
        appOwner.set(app)
        try {
            proofRequire(sourceOwner === app.platform.youtube && app.platform is DesktopPlatformServices,
                "PROOF_REQUIRES_SINGLE_PRODUCTION_DESKTOP_CONTAINER")
            proofRequire(app.initialization.value is AppInitialization.Ready, "APP_NOT_INITIALIZED")
            currentProcess = CentralProcessEvidence.current()
            withTimeout(12 * 60_000L) { operation() }
            proofRequire(authorization.snapshot().let {
                !it.rejectedResourceCloseFailed && it.rejectedResourcesPending == 0 && it.resolvesInFlight == 0
            }, "REJECTED_RESOURCE_DRAIN_UNVERIFIED")
            return report(success = true)
        } catch (timeout: TimeoutCancellationException) {
            throw CentralListeningProofFailure(report(false, AppFailure(FailureCode.CANCELLED, "PHASE_TIMEOUT")))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val failure = authorization.failureOrNull()
                ?: (error as? VeyraException)?.failure
                ?: AppFailure(FailureCode.UNEXPECTED, "PROOF_OPERATION_FAILED")
            throw CentralListeningProofFailure(report(false, failure))
        } finally {
            // No autonomous retry or fresh acquisition after the runner has stopped observing.
            authorization.disable()
        }
    }

    private suspend fun exerciseNative(app: AppContainer, started: PlaybackState): CentralNativeEvidence {
        val duration = started.durationMs ?: proofFail("NATIVE_DURATION_UNKNOWN")
        proofRequire(duration >= 12_000 && started.seekable, "NATIVE_DURATION_OR_SEEK_INSUFFICIENT")
        val generation = started.generation
        val entry = started.currentEntryId ?: proofFail("NATIVE_OCCURRENCE_MISSING")
        val advanced = awaitPlayback(app, generation = generation) {
            it.currentEntryId == entry && it.isPlaying && it.phase == PlaybackPhase.PLAYING &&
                it.positionMs > started.positionMs + 2_000
        }
        suspend fun pause(): PlaybackState {
            app.onMusicAction(MusicUiAction.TogglePlayPause)
            val paused = awaitPlayback(app, generation = generation) {
                !it.desiredPlaying && !it.isPlaying && it.phase == PlaybackPhase.PAUSED
            }
            delay(250)
            val from = app.playback.state.value
            delay(500)
            val to = app.playback.state.value
            requireHealthy(to)
            proofRequire(from.generation == generation && to.generation == generation &&
                from.currentEntryId == entry && to.currentEntryId == entry &&
                !to.isPlaying && !to.desiredPlaying && to.phase == PlaybackPhase.PAUSED &&
                abs(to.positionMs - from.positionMs) <= 250, "NATIVE_PAUSE_DID_NOT_HOLD")
            return paused.copy(positionMs = to.positionMs)
        }
        val paused = pause()
        app.onMusicAction(MusicUiAction.TogglePlayPause)
        val resumed = awaitPlayback(app, generation = generation) {
            it.isPlaying && it.phase == PlaybackPhase.PLAYING && it.positionMs > paused.positionMs + 500
        }
        val beforeSeek = pause()
        val forwardTarget = minOf(duration - 2_000, maxOf(beforeSeek.positionMs + 3_000, minOf(duration / 2, 15_000)))
        proofRequire(forwardTarget - beforeSeek.positionMs >= 1_500, "NATIVE_TRACK_TOO_SHORT_FOR_SEEK_PROOF")
        suspend fun seek(target: Long): Long {
            val before = app.playback.state.value
            proofRequire(!before.isPlaying && before.pendingSeekMs == null && abs(before.positionMs - target) >= 1_500,
                "SEEK_PROOF_PRECONDITION")
            app.onMusicAction(MusicUiAction.Seek(target, entry, generation))
            // Paused position starts far away. Controller rejects pre-seek position callbacks;
            // only its native SeekCompleted barrier can publish the requested paused position.
            val after = awaitPlayback(app, generation = generation, timeoutMs = 60_000) {
                it.currentEntryId == entry && !it.isPlaying && it.pendingSeekMs == null && abs(it.positionMs - target) <= 500
            }
            app.playback.dispatch(PlaybackCommand.FlushProgress).getOrThrow()
            proofRequire(abs(app.store.acquisition.playbackView().acquisition.queues
                .getValue(QueueContext.MUSIC).positionMs - after.positionMs) <= 500,
                "NATIVE_SEEK_NOT_CHECKPOINTED")
            return after.positionMs
        }
        val forward = seek(forwardTarget)
        app.onMusicAction(MusicUiAction.TogglePlayPause)
        val forwardAdvanced = awaitPlayback(app, generation = generation) {
            it.isPlaying && it.phase == PlaybackPhase.PLAYING && it.positionMs > forward + 500
        }
        pause()
        val backward = seek(750)
        app.onMusicAction(MusicUiAction.TogglePlayPause)
        val backwardAdvanced = awaitPlayback(app, generation = generation) {
            it.isPlaying && it.phase == PlaybackPhase.PLAYING && it.positionMs > backward + 500
        }
        val finalPause = pause()
        val engine = (app.platform as DesktopPlatformServices).engine
        val native = engine.diagnostics() // Existing owner; do not consume engine.events a second time.
        proofRequire(native["tls-verify"] == "yes", "NATIVE_TLS_VERIFICATION_DISABLED")
        val output = native["current-ao"]
        proofRequire(!output.isNullOrBlank() && (if (silentAudio) output == "null" else output != "null"),
            "NATIVE_AUDIO_OUTPUT_UNAVAILABLE")
        return CentralNativeEvidence(
            generation = generation, entryId = entry, durationMs = duration,
            firstPlayingPositionMs = started.positionMs, advancingPositionMs = advanced.positionMs,
            pausedPositionMs = paused.positionMs, resumedPositionMs = resumed.positionMs,
            forwardTargetMs = forwardTarget, forwardConfirmedMs = forward,
            forwardAdvancingPositionMs = forwardAdvanced.positionMs,
            backwardConfirmedMs = backward, backwardAdvancingPositionMs = backwardAdvanced.positionMs,
            finalPausedPositionMs = finalPause.positionMs, silentAudio = silentAudio,
            mpvVersion = nativeDiagnostic(native["mpv-version"]), ffmpegVersion = nativeDiagnostic(native["ffmpeg-version"]),
            audioOutput = nativeDiagnostic(output),
        )
    }

    private suspend fun awaitPlayback(app: AppContainer, generation: Long? = null, timeoutMs: Long = 30_000,
        predicate: (PlaybackState) -> Boolean): PlaybackState {
        var result = app.playback.state.value
        awaitCondition(timeoutMs) {
            authorization.failureOrNull()?.let { throw VeyraException(it) }
            result = app.playback.state.value
            requireHealthy(result)
            if (generation != null) proofRequire(result.generation == generation, "NATIVE_GENERATION_CHANGED")
            predicate(result)
        }
        return result
    }

    private fun matchingTypedJobs(state: AcquisitionRepositoryState, context: AcquisitionExecutionContext) =
        state.physicalDownloads.values.filter { job ->
            val producer = job.producer as? PhysicalDownloadProducer.Typed
            producer != null && producer.bindingId == context.binding.id &&
                producer.recordingId == context.recording.id && producer.subjectId == context.subject.id
        }

    private suspend fun awaitCompletedTypedDownload(app: AppContainer,
        context: AcquisitionExecutionContext): CentralTypedDownload {
        var result: CentralTypedDownload? = null
        awaitCondition(8 * 60_000L) {
            requireHealthy(app.playback.state.value)
            val state = app.store.acquisitionView().acquisition
            val jobs = matchingTypedJobs(state, context)
            proofRequire(jobs.size <= 1, "MULTIPLE_PROOF_DOWNLOADS")
            val job = jobs.singleOrNull()
            if (job == null) false else {
                if (job.status in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED, DownloadStatus.PAUSED,
                        DownloadStatus.NEEDS_FOREGROUND, DownloadStatus.WAITING_NETWORK)) {
                    throw VeyraException(job.failure ?: AppFailure(FailureCode.RECOVERY_REQUIRED, "DOWNLOAD_NOT_RUNNING"))
                }
                if (job.status != DownloadStatus.COMPLETE || job.assetId == null) false else {
                    result = requireTypedDownload(state, context)
                    true
                }
            }
        }
        return result!!
    }

    /**
     * Canonical schema3 completion, not the legacy AppSnapshot.downloads projection. Typed jobs
     * are deliberately absent from that legacy map, so the old await could never observe them.
     */
    private fun requireTypedDownload(state: AcquisitionRepositoryState,
        context: AcquisitionExecutionContext): CentralTypedDownload {
        val jobs = matchingTypedJobs(state, context)
        proofRequire(jobs.size <= 1, "MULTIPLE_PROOF_DOWNLOADS")
        val job = jobs.singleOrNull() ?: proofFail("COMPLETED_DOWNLOAD_MISSING", FailureCode.RECOVERY_REQUIRED)
        if (job.status in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED, DownloadStatus.PAUSED,
                DownloadStatus.NEEDS_FOREGROUND, DownloadStatus.WAITING_NETWORK)) {
            throw VeyraException(job.failure ?: AppFailure(FailureCode.RECOVERY_REQUIRED, "DOWNLOAD_NOT_RUNNING"))
        }
        proofRequire(job.status == DownloadStatus.COMPLETE && job.assetId != null,
            "DOWNLOAD_NOT_COMPLETE", FailureCode.RECOVERY_REQUIRED)
        proofRequire(!job.cleanupPending && job.maintenanceKind == null && job.leaseOwner == null &&
            job.activeAttemptId == null && job.id !in state.physicalFinalizations,
            "DOWNLOAD_OWNERSHIP_NOT_SETTLED", FailureCode.PERMISSION_DENIED)
        val assetId = job.assetId ?: proofFail("COMPLETED_ASSET_MISSING", FailureCode.CORRUPT_MEDIA)
        val asset = state.materializations[assetId]
            ?: proofFail("COMPLETED_ASSET_MISSING", FailureCode.CORRUPT_MEDIA)
        val producer = job.producer as? PhysicalDownloadProducer.Typed
            ?: proofFail("DOWNLOAD_PRODUCER_CHANGED", FailureCode.PERMISSION_DENIED)
        proofRequire(asset.producer == producer && asset.id == assetId &&
            job.representationId == asset.representationId && job.bytesDownloaded == asset.sizeBytes &&
            job.totalBytes == asset.sizeBytes, "DOWNLOAD_EVIDENCE_INCOMPLETE", FailureCode.CORRUPT_MEDIA)
        proofRequire(asset.kind == OfflineAssetKind.FILE && asset.sizeBytes in 1..CentralProofPolicy.MAX_BYTES &&
            asset.sha256?.matches(CentralProofPolicy.SHA256) == true &&
            asset.representationId?.matches(CentralProofPolicy.REPRESENTATION) == true &&
            (asset.measuredDurationMs ?: 0) > 0, "DOWNLOAD_EVIDENCE_INCOMPLETE", FailureCode.CORRUPT_MEDIA)
        proofRequire(state.materializationReceipts[asset.id] == asset.receipt(),
            "DOWNLOAD_RECEIPT_CHANGED", FailureCode.CORRUPT_MEDIA)
        // The exact same-publication AssetUse is the typed playback authority. A COMPLETE flag
        // or a file on disk alone is not sufficient.
        proofRequire(state.assetUses.any { it.requestedBindingId == context.binding.id &&
            it.materializationAssetId == asset.id && it.compatibilityPolicyVersion == 1 },
            "DOWNLOAD_AUTHORITY_MISSING", FailureCode.PERMISSION_DENIED)
        return CentralTypedDownload(job.id, asset)
    }

    /** Independent size/SHA-256 and native validation of the canonical materialization. */
    private suspend fun verifyTypedAsset(app: AppContainer, asset: MaterializationAsset) {
        val offline = OfflineAsset(
            id = asset.id.value, mediaId = asset.producer.subjectId.value, uri = asset.uri,
            sizeBytes = asset.sizeBytes, kind = asset.kind, mimeType = asset.mimeType, codecs = asset.codecs,
            sha256 = asset.sha256, representationId = asset.representationId,
            validatorGeneration = asset.validatorGeneration, completedEpochMs = asset.completedEpochMs,
            executionBindingId = asset.producer.bindingId?.value, measuredDurationMs = asset.measuredDurationMs,
        )
        app.files.verifiedFile(offline)
        val validation = app.platform.localValidator.validateLocal(LocalMediaValidationInput(
            asset.uri, asset.kind, asset.sizeBytes, asset.mimeType, asset.codecs, asset.sha256,
            asset.representationId, asset.validatorGeneration))
        proofRequire(validation is AssetValidation.Valid,
            "DOWNLOADED_NATIVE_VALIDATION_FAILED", FailureCode.CORRUPT_MEDIA)
    }

    private data class CentralTypedDownload(val jobId: String, val asset: MaterializationAsset)

    private fun requireOnline() {
        proofRequire(!closed.get() && mode.get() == "online" && appOwner.get()?.store?.state?.value?.settings?.offlineOnly == false,
            "PROOF_NETWORK_NOT_ALLOWED", FailureCode.OFFLINE_POLICY)
    }

    private fun report(success: Boolean, failure: AppFailure? = null): String = buildJsonObject {
        put("schema", 1); put("proof", "Veyra-D005-central-listening"); put("phase", mode.get())
        put("success", success); put("phaseOnly", true); put("stage", stage)
        put("physicalAudioVerified", false); put("silentAudio", silentAudio)
        put("newProcessOfflineStageVerified", success && mode.get() == "offline")
        put("signedExecutionUrlsRecorded", false); put("ownersClosedByRunner", false)
        put("approvedChannel", CentralProofPolicy.CHANNEL_URL)
        put("maximumDistinctVideoCandidates", CentralProofPolicy.MAX_VIDEOS)
        put("maximumRetainedBytesPerAcquisition", CentralProofPolicy.MAX_BYTES)
        put("totalNetworkIngressBoundClaimed", false)
        put("milestones", JsonArray(milestones.map(::JsonPrimitive)))
        put("authorization", authorization.snapshot().json())
        observedNative?.let { put("native", Json.encodeToJsonElement(it)) }
        observedIsolation?.let { put("nativeNetworkDenial", Json.encodeToJsonElement(it)) }
        currentProcess?.let { put("process", Json.encodeToJsonElement(it)) }
        restoredFrom?.let { put("terminatedOnlineProcess", Json.encodeToJsonElement(it)) }
        if (failure != null) putJsonObject("failure") {
            put("code", failure.code.name)
            // Only bounded typed diagnostic tokens are allowed; native/source exception text,
            // response bodies, endpoint URLs and request headers are never copied.
            put("proofGate", failure.detail?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,95}")) })
        }
    }.toString().also { proofRequire(it.toByteArray().size <= CentralProofFiles.MAX_BYTES, "REPORT_TOO_LARGE") }

    @Synchronized override fun close() {
        closed.set(true)
        authorization.disable()
        // This opt-in CLI owner is closed only after AppContainer has awaited its jobs/native
        // decks. A rejected gateway handoff never reached those decks: retain and drain it here.
        // Any earlier failed close stays a failed proof even if this final drain succeeds.
        try { runBlocking(Dispatchers.IO) { authorization.closeRejectedResources() } }
        catch (_: Throwable) { closeFailed.set(true) }
        try { metadata.close() }
        catch (_: Throwable) { closeFailed.set(true) }
        proofRequire(!closeFailed.get(), "PROOF_OWNER_CLOSE_FAILED")
    }
}

internal data class CentralVideoMetadata(val title: String, val author: String)

/** Separate bounded public METADATA reader; no audio body, format probing, credentials or fallback. */
internal class CentralProofMetadata(
    private val http: SafeHttp = createSafeHttp(),
    private val beforeRequest: suspend () -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean()

    suspend fun video(id: String): CentralVideoMetadata {
        proofRequire(!closed.get() && CentralProofPolicy.VIDEO_ID.matches(id), "INVALID_METADATA_VIDEO")
        val url = URLBuilder("https://www.youtube.com/oembed").apply {
            parameters.append("url", CentralProofPolicy.watch(id)); parameters.append("format", "json")
        }.buildString()
        beforeRequest()
        val response = http.get(SafeHttpRequest(url, headers = mapOf("Accept" to "application/json"), beforeRequest = {
            proofRequire(!closed.get(), "METADATA_OWNER_CLOSED"); beforeRequest()
        }), maxBytes = 256 * 1024)
        proofRequire(response.response.status == 200 && response.response.finalUrl == url,
            "OEMBED_ORIGIN_OR_STATUS_CHANGED", FailureCode.PROVIDER_ACCESS_REQUIRED)
        return parse(response.text())
    }

    override fun close() { if (closed.compareAndSet(false, true)) http.close() }

    companion object {
        internal fun parse(text: String): CentralVideoMetadata {
            proofRequire(text.toByteArray().size <= 256 * 1024, "OEMBED_METADATA_TOO_LARGE")
            val body = try { Json.parseToJsonElement(text).jsonObject }
            catch (_: Exception) { proofFail("OEMBED_INVALID_JSON", FailureCode.PARSE_FAILURE) }
            fun string(name: String) = (body[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val title = string("title") ?: proofFail("OEMBED_TITLE_MISSING", FailureCode.PARSE_FAILURE)
            proofRequire(YouTubeBrowseText.isCompleteTitle(title), "OEMBED_COMPLETE_TITLE_REQUIRED", FailureCode.PARSE_FAILURE)
            proofRequire(string("type") == "video" && string("provider_name") == "YouTube" &&
                string("provider_url") == "https://www.youtube.com/", "OEMBED_PROVIDER_UNVERIFIED", FailureCode.PERMISSION_DENIED)
            proofRequire(CentralProofPolicy.isApprovedAuthor(string("author_url")), "FIRST_VIDEO_OUTSIDE_APPROVED_CHANNEL",
                FailureCode.PERMISSION_DENIED)
            val author = string("author_name") ?: proofFail("OEMBED_AUTHOR_MISSING", FailureCode.PARSE_FAILURE)
            proofRequire(author.length in 1..1024 && YouTubeBrowseText.isCompleteTitle(author), "OEMBED_AUTHOR_INVALID",
                FailureCode.PARSE_FAILURE)
            return CentralVideoMetadata(title, author)
        }
    }
}

/** Actual gateway guard, not a pre-search whose answer could change before the application plays. */
internal class CentralProofAuthorization(
    private val beforeNetwork: () -> Unit = {},
    private val metadata: suspend (String) -> CentralVideoMetadata,
) {
    private val gate = Any()
    private val resourceCleanup = Mutex()
    private val rejectedResources = mutableListOf<PlaybackResource>()
    private var rejectedResourceCloseFailed = false
    private var disabled = false
    private var discovery: MediaItem? = null
    private var observed: KmpFirstResult? = null
    private var accepted: KmpFirstResult? = null
    private var binding: PlaybackBinding? = null
    private var playbackRequest: SourcePlaybackRequest? = null
    private var plannedBytes: Long? = null
    private var searchCalls = 0
    private var resolveCalls = 0
    private var resolvesInFlight = 0
    private var playbackResolves = 0
    private var downloadCalls = 0
    private var downloadPlans = 0
    private var metadataChecks = 0
    private var transferBytes = 0L

    // Typed acquisition/execution state. These IDs are allocated by the canonical Room commit,
    // so the first exact context is pinned here and every later context must match it.
    private var typedRequest: AcquisitionRequest? = null
    private var typedReceipt: FirstResultObservation? = null
    private var typedVideoId: String? = null
    private var typedAccepted = false
    private var typedFailure: AppFailure? = null
    private var typedBindingId: ExecutionBindingId? = null
    private var typedReceiptId: FirstResultReceiptId? = null
    private var typedRecordingId: SourceRecordingId? = null
    private var typedSubjectId: ExecutableSubjectId? = null
    private var playbackRepresentation: String? = null
    private var downloadRepresentation: String? = null
    private var acquireCalls = 0
    private var restoreCalls = 0
    private var inspectCalls = 0
    private var preparePlaybackCalls = 0
    private var prepareDownloadCalls = 0
    private var transferCalls = 0

    fun discovered(item: MediaItem) = synchronized(gate) {
        requireActive()
        proofRequire(discovery == null, "SECOND_DISCOVERY_NOT_ALLOWED")
        CentralProofPolicy.requireDiscovery(item)
        discovery = item
    }

    fun disable() = synchronized(gate) { disabled = true }

    fun failureOrNull(): AppFailure? = synchronized(gate) { typedFailure }

    fun playbackRepresentationId(): String? = synchronized(gate) { playbackRepresentation }

    fun downloadRepresentationId(): String? = synchronized(gate) { downloadRepresentation }

    fun wrap(delegate: KmpMediaGateway): CentralProofGateway = object : CentralProofGateway {
        override suspend fun firstResult(completeTitle: String): KmpFirstResult? {
            synchronized(gate) {
                searchCalls++
                requireActive()
                proofRequire(searchCalls == 1 && discovery?.title == completeTitle, "SECOND_OR_CHANGED_TITLE_SEARCH_DENIED")
            }
            val first = delegate.firstResult(completeTitle)
            synchronized(gate) { observed = first }
            if (first == null || first.kind != FirstResultKind.VIDEO || first.failure != null) return first
            proofRequire(first.queryTitle == completeTitle && first.selectionOrdinal == 0 &&
                CentralProofPolicy.VIDEO_ID.matches(first.id) && first.canonicalUrl == CentralProofPolicy.watch(first.id) &&
                YouTubeBrowseText.isCompleteTitle(first.title), "RAW_FIRST_RECEIPT_INVALID", FailureCode.PARSE_FAILURE)
            return try {
                synchronized(gate) { requireActive(); metadataChecks++ }
                metadata(first.id) // Author of the ACTUAL raw first video, even if discovery had a different ID.
                synchronized(gate) { requireActive(); accepted = first }
                first
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // The resolver records this unchanged raw identity in Room BEFORE throwing.
                val failure = (error as? VeyraException)?.failure
                    ?: AppFailure(FailureCode.PERMISSION_DENIED, "RAW_FIRST_AUTHOR_UNVERIFIED")
                first.copy(failure = failure)
            }
        }

        override suspend fun resolve(binding: PlaybackBinding, capabilities: EngineCapabilities,
            purpose: ResolvePurpose): ResolvedPlayable {
            synchronized(gate) {
                resolveCalls++
                requireBinding(binding)
                if (purpose == ResolvePurpose.PLAYBACK) {
                    playbackResolves++
                    proofRequire(playbackResolves == 1, "SECOND_PLAYBACK_ACQUISITION_DENIED")
                }
                resolvesInFlight++
            }
            var acquired: ResolvedPlayable? = null
            try {
                val result = delegate.resolve(binding, capabilities, purpose).also { acquired = it }
                currentCoroutineContext().ensureActive()
                synchronized(gate) {
                    requireBinding(binding)
                    proofRequire(!result.isLocal && result.mediaId == binding.mediaId && result.executionBinding == binding,
                        "RESOLVED_RECEIPT_CHANGED")
                    if (purpose == ResolvePurpose.PLAYBACK) {
                        // Bare progressive native URLs have no pre-transfer byte-budget seam.
                        // Refuse this proof rather than choose a different offered representation.
                        proofRequire(result.ownedLoopbackUriOrNull() != null && playbackRequest != null,
                            "UNBOUNDED_NATIVE_PLAYBACK_REFUSED", FailureCode.UNSUPPORTED_TRANSPORT)
                    } else proofRequire(result.resource == null, "DOWNLOAD_PREVIEW_OPENED_RESOURCE")
                }
                return result
            } catch (failure: Throwable) {
                acquired?.resource?.let { rejectResource(it) }
                throw failure
            } finally {
                synchronized(gate) { resolvesInFlight-- }
            }
        }

        override suspend fun download(binding: PlaybackBinding, destinationPath: String, capabilities: EngineCapabilities,
            representationId: String?, onProgress: suspend (YouTubeTransferProgress) -> Unit): YouTubeTransferResult {
            synchronized(gate) {
                downloadCalls++
                requireBinding(binding)
                proofRequire(downloadCalls == 1 && representationId?.matches(CentralProofPolicy.REPRESENTATION) == true,
                    "SECOND_OR_UNBOUND_DOWNLOAD_DENIED")
            }
            val result = delegate.download(binding, destinationPath, capabilities, representationId) { progress ->
                synchronized(gate) {
                    requireBinding(binding)
                    val total = plannedBytes ?: proofFail("DOWNLOAD_HOOK_NOT_INSTALLED")
                    proofRequire(progress.bytesDownloaded in 0..total && progress.totalBytes == total,
                        "DOWNLOAD_PROGRESS_EXCEEDS_PLAN", FailureCode.CORRUPT_MEDIA)
                    transferBytes = maxOf(transferBytes, progress.bytesDownloaded)
                }
                onProgress(progress)
            }
            synchronized(gate) {
                requireBinding(binding)
                proofRequire(downloadPlans == 1 && result.bytesWritten == plannedBytes && result.bytesWritten > 0 &&
                    result.representationId == representationId, "DOWNLOAD_COMPLETION_CHANGED", FailureCode.CORRUPT_MEDIA)
                transferBytes = result.bytesWritten
            }
            return result
        }

        override suspend fun acquire(request: AcquisitionRequest): SourceAcquisitionResult =
            acquireTyped(delegate, request)

        override fun restore(request: AcquisitionRequest, receipt: FirstResultObservation): SourceAcquisitionResult =
            restoreTyped(delegate, request, receipt)

        override suspend fun collection(request: AcquisitionRequest, selection: SourceAcquisitionResult.Collection,
            limits: CollectionLimits): SourceCollectionPage =
            proofFail("PROOF_COLLECTION_ACQUISITION_NOT_ALLOWED", FailureCode.PERMISSION_DENIED)

        override suspend fun more(continuation: CollectionContinuation): SourceCollectionPage =
            proofFail("PROOF_COLLECTION_ACQUISITION_NOT_ALLOWED", FailureCode.PERMISSION_DENIED)

        override suspend fun inspect(request: AcquisitionRequest,
            selection: SourceAcquisitionResult.Single): AcquisitionInspection =
            inspectTyped(delegate, request, selection)

        override suspend fun preparePlayback(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
            requiredRepresentationId: String?, inspection: AcquisitionInspection?): AcquisitionPlaybackSource =
            preparePlaybackTyped(delegate, context, capabilities, requiredRepresentationId, inspection)

        override suspend fun prepareDownload(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
            requiredRepresentationId: String?, inspection: AcquisitionInspection?): PreparedAcquisitionDownload =
            prepareDownloadTyped(delegate, context, capabilities, requiredRepresentationId, inspection)

        override suspend fun download(prepared: PreparedAcquisitionDownload, destinationPath: String,
            onProgress: suspend (AcquisitionTransferProgress) -> Unit): AcquisitionTransferResult =
            downloadTyped(delegate, prepared, destinationPath, onProgress)
    }

    fun requireBinding(value: PlaybackBinding) = synchronized(gate) {
        requireActive()
        val first = accepted ?: proofFail("RAW_FIRST_NOT_AUTHORIZED", FailureCode.PERMISSION_DENIED)
        val item = discovery ?: proofFail("DISCOVERY_NOT_AUTHORIZED", FailureCode.PERMISSION_DENIED)
        proofRequire(value.id.isNotBlank() && value.mediaId == item.id && value.queryTitle == item.title &&
            value.firstResultKind == first.kind && value.firstResultId == first.id &&
            value.firstResultTitle == first.title && value.firstResultUrl == first.canonicalUrl &&
            value.selectedVideoId == first.id && value.selectionOrdinal == first.selectionOrdinal &&
            (binding == null || binding == value), "IMMUTABLE_FIRST_BINDING_CHANGED", FailureCode.PERMISSION_DENIED)
        if (binding == null) binding = value
    }

    /**
     * Exact durable typed join. Request/catalogue identity is the discovery capture; the actual
     * execution identity is the raw ordinal-zero first receipt and its recording. IDs allocated
     * by the canonical commit are pinned on first use and cannot change later.
     */
    fun requireTypedContext(context: AcquisitionExecutionContext) = synchronized(gate) {
        requireActive()
        val request = typedRequest ?: proofFail("TYPED_ACQUISITION_NOT_OBSERVED", FailureCode.PERMISSION_DENIED)
        val receipt = typedReceipt ?: proofFail("TYPED_RECEIPT_NOT_OBSERVED", FailureCode.PERMISSION_DENIED)
        val videoId = typedVideoId ?: proofFail("TYPED_VIDEO_NOT_OBSERVED", FailureCode.PERMISSION_DENIED)
        val item = discovery ?: proofFail("DISCOVERY_NOT_AUTHORIZED", FailureCode.PERMISSION_DENIED)
        proofRequire(context.request.id == request.id && context.request.completeTitle == request.completeTitle &&
            context.request.kind == AcquisitionKind.MEDIA, "TYPED_REQUEST_CHANGED", FailureCode.PERMISSION_DENIED)
        val capture = context.request.metadata as? MediaCapture
            ?: proofFail("TYPED_CATALOG_CAPTURE_REQUIRED", FailureCode.PERMISSION_DENIED)
        proofRequire(capture.media.id == item.id && capture.media.title == item.title &&
            capture.media.source.provider == SourceProvider.YOUTUBE &&
            capture.media.source.canonicalId == item.source.canonicalId,
            "TYPED_CATALOGUE_IDENTITY_CHANGED", FailureCode.PERMISSION_DENIED)
        proofRequire(context.receipt.observation == receipt &&
            context.receipt.observation.entry.kind == AcquisitionEntryKind.VIDEO &&
            context.receipt.observation.entry.ordinal == 0 &&
            context.receipt.observation.entry.id == videoId &&
            context.receipt.observation.queryTitle == request.completeTitle,
            "TYPED_RECEIPT_CHANGED", FailureCode.PERMISSION_DENIED)
        proofRequire(context.recording.canonicalId == videoId &&
            context.recording.canonicalUrl == CentralProofPolicy.watch(videoId),
            "TYPED_RECORDING_CHANGED", FailureCode.PERMISSION_DENIED)
        proofRequire(context.binding.origin == ExecutionBindingOrigin.TYPED &&
            context.binding.firstReceiptId == context.receipt.id &&
            context.binding.subjectId == context.subject.id &&
            context.binding.recordingId == context.recording.id,
            "TYPED_BINDING_CHANGED", FailureCode.PERMISSION_DENIED)
        val subject = context.subject as? ExecutableSubject.CatalogMedia
            ?: proofFail("TYPED_CATALOGUE_SUBJECT_REQUIRED", FailureCode.PERMISSION_DENIED)
        proofRequire(subject.mediaId == item.id && subject.id.value == item.id,
            "TYPED_SUBJECT_CHANGED", FailureCode.PERMISSION_DENIED)
        if (typedBindingId == null) {
            typedBindingId = context.binding.id
            typedReceiptId = context.receipt.id
            typedRecordingId = context.recording.id
            typedSubjectId = context.subject.id
        } else {
            proofRequire(typedBindingId == context.binding.id && typedReceiptId == context.receipt.id &&
                typedRecordingId == context.recording.id && typedSubjectId == context.subject.id,
                "TYPED_DURABLE_IDENTITY_CHANGED", FailureCode.PERMISSION_DENIED)
        }
    }

    private suspend fun acquireTyped(delegate: KmpMediaGateway, request: AcquisitionRequest): SourceAcquisitionResult {
        val target = delegate as? AcquisitionGateway
            ?: proofFail("PROOF_GATEWAY_NOT_TYPED", FailureCode.UNSUPPORTED_SOURCE)
        synchronized(gate) {
            acquireCalls++
            requireActive()
            proofRequire(acquireCalls == 1, "SECOND_ACQUISITION_SEARCH_DENIED", FailureCode.PERMISSION_DENIED)
            val item = discovery ?: proofFail("DISCOVERY_NOT_AUTHORIZED", FailureCode.PERMISSION_DENIED)
            proofRequire(request.completeTitle == item.title && request.kind == AcquisitionKind.MEDIA,
                "SECOND_OR_CHANGED_TITLE_ACQUISITION_DENIED", FailureCode.PERMISSION_DENIED)
            val capture = request.metadata as? MediaCapture
                ?: proofFail("TYPED_CATALOG_CAPTURE_REQUIRED", FailureCode.PERMISSION_DENIED)
            proofRequire(capture.media.id == item.id && capture.media.title == item.title &&
                capture.media.source.provider == SourceProvider.YOUTUBE &&
                capture.media.source.canonicalId == item.source.canonicalId,
                "ACQUISITION_CATALOGUE_IDENTITY_CHANGED", FailureCode.PERMISSION_DENIED)
        }
        val result = try { target.acquire(request) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: VeyraException) {
            synchronized(gate) { typedFailure = typedFailure ?: error.failure }
            throw error
        } catch (_: Exception) {
            synchronized(gate) { typedFailure = typedFailure ?: AppFailure(FailureCode.UNEXPECTED, "ACQUISITION_SOURCE_FAILED") }
            throw VeyraException(AppFailure(FailureCode.UNEXPECTED, "ACQUISITION_SOURCE_FAILED"))
        }
        return inspectAcquisition(request, result)
    }

    private suspend fun inspectAcquisition(request: AcquisitionRequest,
        result: SourceAcquisitionResult): SourceAcquisitionResult {
        val receipt = when (result) {
            is SourceAcquisitionResult.Single -> result.receipt
            is SourceAcquisitionResult.Collection ->
                proofFail("COLLECTION_ACQUISITION_NOT_ALLOWED", FailureCode.PERMISSION_DENIED)
            is SourceAcquisitionResult.Unplayable -> result.receipt
        }
        if (receipt != null) {
            validateRawReceiptIdentity(request, receipt)
            synchronized(gate) {
                typedReceipt = receipt
                typedVideoId = receipt.entry.id?.takeIf { receipt.entry.kind == AcquisitionEntryKind.VIDEO }
            }
        }
        return when (result) {
            is SourceAcquisitionResult.Unplayable -> {
                synchronized(gate) { typedFailure = typedFailure ?: typedProblemFailure(result.problem) }
                result
            }
            is SourceAcquisitionResult.Single -> {
                val singleReceipt = receipt ?: proofFail("RAW_FIRST_RECEIPT_MISSING", FailureCode.PARSE_FAILURE)
                val entry = singleReceipt.entry
                proofRequire(entry.kind == AcquisitionEntryKind.VIDEO && entry.id != null,
                    "RAW_FIRST_NOT_VIDEO", FailureCode.UNSUPPORTED_SOURCE)
                val firstId = entry.id ?: proofFail("RAW_FIRST_NOT_VIDEO", FailureCode.UNSUPPORTED_SOURCE)
                val recording = result.recording
                proofRequire(recording.kind == AcquisitionEntryKind.VIDEO && recording.id == firstId &&
                    recording.publicUrl == singleReceipt.canonicalUrl && YouTubeBrowseText.isCompleteTitle(recording.title),
                    "RAW_FIRST_RECEIPT_INVALID", FailureCode.PARSE_FAILURE)
                try {
                    synchronized(gate) { requireActive(); metadataChecks++ }
                    metadata(firstId) // Author of the ACTUAL raw first video, even if discovery differs.
                    synchronized(gate) {
                        requireActive()
                        typedRequest = request
                        typedAccepted = true
                    }
                    result
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    val failure = (error as? VeyraException)?.failure
                        ?: AppFailure(FailureCode.PERMISSION_DENIED, "RAW_FIRST_AUTHOR_UNVERIFIED")
                    synchronized(gate) { typedFailure = typedFailure ?: failure }
                    SourceAcquisitionResult.Unplayable(request.id, receipt,
                        AcquisitionProblem(AcquisitionProblemCode.FIRST_RESULT_UNAVAILABLE))
                }
            }
            is SourceAcquisitionResult.Collection -> error("unreachable")
        }
    }

    private fun restoreTyped(delegate: KmpMediaGateway, request: AcquisitionRequest,
        receipt: FirstResultObservation): SourceAcquisitionResult {
        val target = delegate as? AcquisitionGateway
            ?: proofFail("PROOF_GATEWAY_NOT_TYPED", FailureCode.UNSUPPORTED_SOURCE)
        synchronized(gate) {
            restoreCalls++
            requirePure()
            validateRawReceiptIdentity(request, receipt)
        }
        val result = target.restore(request, receipt)
        synchronized(gate) {
            requirePure()
            when (result) {
                is SourceAcquisitionResult.Single -> proofRequire(result.requestId == request.id &&
                    result.receipt == receipt && result.recording.id == receipt.entry.id &&
                    result.recording.publicUrl == receipt.canonicalUrl &&
                    result.recording.kind == AcquisitionEntryKind.VIDEO,
                    "RESTORED_RECEIPT_CHANGED", FailureCode.PERMISSION_DENIED)
                is SourceAcquisitionResult.Unplayable -> proofRequire(result.requestId == request.id &&
                    result.receipt == receipt, "RESTORED_RECEIPT_CHANGED", FailureCode.PERMISSION_DENIED)
                is SourceAcquisitionResult.Collection ->
                    proofFail("COLLECTION_RESTORE_NOT_ALLOWED", FailureCode.PERMISSION_DENIED)
            }
        }
        return result
    }

    private suspend fun inspectTyped(delegate: KmpMediaGateway, request: AcquisitionRequest,
        selection: SourceAcquisitionResult.Single): AcquisitionInspection {
        val target = delegate as? AcquisitionGateway
            ?: proofFail("PROOF_GATEWAY_NOT_TYPED", FailureCode.UNSUPPORTED_SOURCE)
        synchronized(gate) {
            inspectCalls++
            requireActive()
            val acceptedRequest = typedRequest
                ?: proofFail("TYPED_ACQUISITION_NOT_OBSERVED", FailureCode.PERMISSION_DENIED)
            val acceptedReceipt = typedReceipt
                ?: proofFail("TYPED_RECEIPT_NOT_OBSERVED", FailureCode.PERMISSION_DENIED)
            proofRequire(request.id == acceptedRequest.id &&
                request.completeTitle == acceptedRequest.completeTitle &&
                selection.requestId == request.id && selection.receipt == acceptedReceipt &&
                selection.recording.id == acceptedReceipt.entry.id,
                "TYPED_SELECTION_CHANGED", FailureCode.PERMISSION_DENIED)
        }
        val inspection = target.inspect(request, selection)
        synchronized(gate) {
            requireActive()
            proofRequire(inspection.selection == selection,
                "INSPECTION_SELECTION_CHANGED", FailureCode.PERMISSION_DENIED)
        }
        return inspection
    }

    private suspend fun preparePlaybackTyped(delegate: KmpMediaGateway, context: AcquisitionExecutionContext,
        capabilities: EngineCapabilities, requiredRepresentationId: String?,
        inspection: AcquisitionInspection?): AcquisitionPlaybackSource {
        val target = delegate as? AcquisitionExecutionGateway
            ?: proofFail("PROOF_GATEWAY_NOT_TYPED", FailureCode.UNSUPPORTED_SOURCE)
        synchronized(gate) {
            preparePlaybackCalls++
            requireActive()
            requireTypedContext(context)
            proofRequire(preparePlaybackCalls == 1, "SECOND_PLAYBACK_ACQUISITION_DENIED", FailureCode.PERMISSION_DENIED)
            proofRequire(requiredRepresentationId == null || requiredRepresentationId.matches(CentralProofPolicy.REPRESENTATION),
                "PLAYBACK_REPRESENTATION_REQUEST_INVALID", FailureCode.PERMISSION_DENIED)
            playbackResolves++
            proofRequire(playbackResolves == 1, "SECOND_PLAYBACK_ACQUISITION_DENIED", FailureCode.PERMISSION_DENIED)
            resolvesInFlight++
        }
        var acquired: AcquisitionPlaybackSource? = null
        try {
            val result = target.preparePlayback(context, capabilities, requiredRepresentationId, inspection)
                .also { acquired = it }
            currentCoroutineContext().ensureActive()
            synchronized(gate) {
                requireTypedContext(context)
                proofRequire(result.context == context && !result.isLocal &&
                    result.representationId.matches(CentralProofPolicy.REPRESENTATION),
                    "RESOLVED_RECEIPT_CHANGED", FailureCode.PERMISSION_DENIED)
                proofRequire(result.resource != null && result.ownedLoopbackUriOrNull() != null,
                    "UNBOUNDED_NATIVE_PLAYBACK_REFUSED", FailureCode.UNSUPPORTED_TRANSPORT)
                proofRequire(playbackRequest != null,
                    "UNBOUNDED_NATIVE_PLAYBACK_REFUSED", FailureCode.UNSUPPORTED_TRANSPORT)
                val pinned = playbackRepresentation
                if (pinned == null) playbackRepresentation = result.representationId
                else proofRequire(pinned == result.representationId,
                    "PLAYBACK_REPRESENTATION_CHANGED", FailureCode.PERMISSION_DENIED)
            }
            return result
        } catch (failure: Throwable) {
            acquired?.resource?.let { rejectResource(it) }
            throw failure
        } finally {
            synchronized(gate) { resolvesInFlight-- }
        }
    }

    private suspend fun prepareDownloadTyped(delegate: KmpMediaGateway, context: AcquisitionExecutionContext,
        capabilities: EngineCapabilities, requiredRepresentationId: String?,
        inspection: AcquisitionInspection?): PreparedAcquisitionDownload {
        val target = delegate as? AcquisitionExecutionGateway
            ?: proofFail("PROOF_GATEWAY_NOT_TYPED", FailureCode.UNSUPPORTED_SOURCE)
        synchronized(gate) {
            prepareDownloadCalls++
            requireActive()
            requireTypedContext(context)
            proofRequire(prepareDownloadCalls == 1, "SECOND_DOWNLOAD_PREPARATION_DENIED", FailureCode.PERMISSION_DENIED)
            proofRequire(requiredRepresentationId == null || requiredRepresentationId.matches(CentralProofPolicy.REPRESENTATION),
                "DOWNLOAD_REPRESENTATION_REQUEST_INVALID", FailureCode.PERMISSION_DENIED)
        }
        val prepared = target.prepareDownload(context, capabilities, requiredRepresentationId, inspection)
        synchronized(gate) {
            requireTypedContext(context)
            proofRequire(prepared.context == context && prepared.requiredRepresentationId == requiredRepresentationId &&
                prepared.representation.representationId.matches(CentralProofPolicy.REPRESENTATION),
                "DOWNLOAD_PREPARATION_CHANGED", FailureCode.PERMISSION_DENIED)
            // The download is a separate execution plan; it may legitimately choose a different
            // compatible audio representation than streaming playback. Pin THIS download's
            // immutable key so no later preparation/transfer can silently switch it.
            proofRequire(playbackRepresentation != null,
                "DOWNLOAD_WITHOUT_PLAYBACK_AUTHORIZATION", FailureCode.PERMISSION_DENIED)
            val pinnedDownload = downloadRepresentation
            if (pinnedDownload == null) downloadRepresentation = prepared.representation.representationId
            else proofRequire(pinnedDownload == prepared.representation.representationId,
                "DOWNLOAD_REPRESENTATION_CHANGED", FailureCode.PERMISSION_DENIED)
        }
        return prepared
    }

    private suspend fun downloadTyped(delegate: KmpMediaGateway, prepared: PreparedAcquisitionDownload,
        destinationPath: String, onProgress: suspend (AcquisitionTransferProgress) -> Unit): AcquisitionTransferResult {
        val target = delegate as? AcquisitionExecutionGateway
            ?: proofFail("PROOF_GATEWAY_NOT_TYPED", FailureCode.UNSUPPORTED_SOURCE)
        synchronized(gate) {
            requireActive()
            requireTypedContext(prepared.context)
            proofRequire(transferCalls == 0 && downloadCalls == 0,
                "SECOND_DOWNLOAD_TRANSFER_DENIED", FailureCode.PERMISSION_DENIED)
            val pinned = downloadRepresentation
                ?: proofFail("DOWNLOAD_NOT_PREPARED", FailureCode.PERMISSION_DENIED)
            proofRequire(prepared.representation.representationId == pinned,
                "DOWNLOAD_REPRESENTATION_CHANGED", FailureCode.PERMISSION_DENIED)
            transferCalls++
            downloadCalls++
            proofRequire(downloadCalls == 1, "SECOND_DOWNLOAD_TRANSFER_DENIED", FailureCode.PERMISSION_DENIED)
        }
        val result = target.download(prepared, destinationPath) { progress ->
            synchronized(gate) {
                requireTypedContext(prepared.context)
                val total = plannedBytes ?: proofFail("DOWNLOAD_HOOK_NOT_INSTALLED")
                proofRequire(progress.bytesDownloaded in 0..total && progress.totalBytes == total,
                    "DOWNLOAD_PROGRESS_EXCEEDS_PLAN", FailureCode.CORRUPT_MEDIA)
                transferBytes = maxOf(transferBytes, progress.bytesDownloaded)
            }
            onProgress(progress)
        }
        synchronized(gate) {
            requireTypedContext(prepared.context)
            proofRequire(downloadPlans == 1 && result.bytesWritten == plannedBytes && result.bytesWritten > 0 &&
                result.context == prepared.context && result.representation == prepared.representation,
                "DOWNLOAD_COMPLETION_CHANGED", FailureCode.CORRUPT_MEDIA)
            transferBytes = result.bytesWritten
        }
        return result
    }

    private fun validateRawReceiptIdentity(request: AcquisitionRequest, receipt: FirstResultObservation) {
        proofRequire(receipt.requestId == request.id && receipt.queryTitle == request.completeTitle &&
            receipt.entry.ordinal == 0, "RAW_FIRST_RECEIPT_INVALID", FailureCode.PARSE_FAILURE)
        val entry = receipt.entry
        when (entry.kind) {
            AcquisitionEntryKind.VIDEO -> {
                val id = entry.id
                proofRequire(id != null && CentralProofPolicy.VIDEO_ID.matches(id) &&
                    entry.title?.let(YouTubeBrowseText::isCompleteTitle) == true &&
                    entry.publicUrl == CentralProofPolicy.watch(id) &&
                    receipt.canonicalUrl == entry.publicUrl,
                    "RAW_FIRST_RECEIPT_INVALID", FailureCode.PARSE_FAILURE)
            }
            AcquisitionEntryKind.CHANNEL, AcquisitionEntryKind.PLAYLIST -> proofRequire(
                entry.id != null && entry.publicUrl != null && receipt.canonicalUrl == entry.publicUrl,
                "RAW_FIRST_RECEIPT_INVALID", FailureCode.PARSE_FAILURE)
            null -> proofRequire(entry.id == null && entry.publicUrl == null && receipt.canonicalUrl == null,
                "RAW_FIRST_RECEIPT_INVALID", FailureCode.PARSE_FAILURE)
        }
    }

    private fun typedProblemFailure(problem: AcquisitionProblem): AppFailure = when (problem.code) {
        AcquisitionProblemCode.NO_FIRST_RESULT -> AppFailure(FailureCode.NOT_FOUND, "KMPYT_NO_FIRST_RESULT")
        AcquisitionProblemCode.FIRST_RESULT_UNAVAILABLE ->
            AppFailure(FailureCode.UNSUPPORTED_SOURCE, "YOUTUBE_FIRST_RESULT_UNAVAILABLE")
        AcquisitionProblemCode.FIRST_RESULT_UNSUPPORTED ->
            AppFailure(FailureCode.UNSUPPORTED_SOURCE, "YOUTUBE_FIRST_RESULT_UNSUPPORTED")
        AcquisitionProblemCode.INVALID_FIRST_RESULT ->
            AppFailure(FailureCode.PARSE_FAILURE, "YOUTUBE_FIRST_RESULT_INVALID")
        AcquisitionProblemCode.SOURCE_FAILURE -> AppFailure(FailureCode.UNEXPECTED, "YOUTUBE_SOURCE_FAILURE")
        AcquisitionProblemCode.INVALID_REQUEST -> AppFailure(FailureCode.INVALID_STATE, "YOUTUBE_INVALID_REQUEST")
        AcquisitionProblemCode.MEMBER_NOT_PLAYABLE ->
            AppFailure(FailureCode.UNSUPPORTED_SOURCE, "YOUTUBE_MEMBER_NOT_PLAYABLE")
    }

    fun authorizeDownload(plan: PlannedDownload): Unit = synchronized(gate) {
        requireActive()
        proofRequire(downloadCalls == 1 && downloadPlans == 0, "SECOND_DOWNLOAD_TRANSFER_DENIED", FailureCode.PERMISSION_DENIED)
        val videoId = binding?.selectedVideoId ?: typedVideoId
            ?: proofFail("PLAN_WITHOUT_FIRST_BINDING", FailureCode.PERMISSION_DENIED)
        plannedBytes = CentralProofPolicy.requirePlan(plan, videoId)
        downloadPlans++
        Unit
    }

    fun authorizePlayback(request: SourcePlaybackRequest) = synchronized(gate) {
        requireActive()
        val selected = binding
        val videoId = selected?.selectedVideoId ?: typedVideoId
            ?: proofFail(if (selected == null) "PLAN_WITHOUT_FIRST_BINDING" else "PLAN_WITHOUT_SELECTED_VIDEO",
                FailureCode.PERMISSION_DENIED)
        proofRequire(playbackResolves == 1 && request.metadata.id == videoId &&
            request.metadata.kind == dev.kmpyt.MediaKind.AUDIO && request.plan.primary.transport == TransportKind.SABR &&
            (playbackRequest == null || playbackRequest === request), "PLAYBACK_SESSION_CHANGED", FailureCode.PERMISSION_DENIED)
        CentralProofPolicy.requirePlan(request.plan, videoId)
        request.progressiveFallback?.let { CentralProofPolicy.requirePlan(it, videoId) }
        if (playbackRequest == null) playbackRequest = request
    }

    fun snapshot(): CentralAuthorizationEvidence = synchronized(gate) {
        CentralAuthorizationEvidence(searchCalls, resolveCalls, downloadCalls, downloadPlans, metadataChecks,
            transferBytes, discovery?.source?.canonicalId, discovery?.title, observed, accepted != null || typedAccepted,
            binding?.id ?: typedBindingId?.value, resolvesInFlight, rejectedResources.size, rejectedResourceCloseFailed,
            acquireCalls, restoreCalls, inspectCalls, preparePlaybackCalls, prepareDownloadCalls, transferCalls,
            typedRequest?.id?.value, typedReceiptId?.value, typedBindingId?.value, typedRecordingId?.value,
            typedSubjectId?.value, typedVideoId, typedReceipt, playbackRepresentation, downloadRepresentation)
    }

    private fun requirePure() {
        proofRequire(!disabled, "PROOF_AUTHORIZATION_CLOSED", FailureCode.PERMISSION_DENIED)
        proofRequire(!rejectedResourceCloseFailed, "REJECTED_RESOURCE_CLOSE_FAILED")
    }

    private suspend fun rejectResource(resource: PlaybackResource) = withContext(NonCancellable) {
        resourceCleanup.withLock {
            synchronized(gate) {
                if (rejectedResources.none { it === resource }) rejectedResources += resource
            }
            closeRejected(resource)
        }
    }

    private suspend fun closeRejected(resource: PlaybackResource) {
        try {
            resource.close()
            synchronized(gate) { rejectedResources.removeAll { it === resource } }
        } catch (_: Throwable) {
            // Never retain/serialize a native exception that might contain a signed endpoint.
            // The resource itself stays strongly owned for the final drain; primary rejection
            // still propagates, and both evidence and close expose the sticky cleanup failure.
            synchronized(gate) { rejectedResourceCloseFailed = true }
        }
    }

    suspend fun closeRejectedResources() = withContext(NonCancellable) {
        disable()
        resourceCleanup.withLock {
            val pending = synchronized(gate) {
                if (resolvesInFlight != 0) rejectedResourceCloseFailed = true
                rejectedResources.toList()
            }
            pending.forEach { closeRejected(it) }
            synchronized(gate) {
                proofRequire(!rejectedResourceCloseFailed && rejectedResources.isEmpty() && resolvesInFlight == 0,
                    "REJECTED_RESOURCE_CLOSE_FAILED")
            }
        }
    }

    private fun requireActive() {
        beforeNetwork()
        proofRequire(!disabled, "PROOF_AUTHORIZATION_CLOSED", FailureCode.PERMISSION_DENIED)
        proofRequire(!rejectedResourceCloseFailed, "REJECTED_RESOURCE_CLOSE_FAILED")
    }
}

internal data class CentralAuthorizationEvidence(
    val searchCalls: Int, val resolveCalls: Int, val downloadCalls: Int, val downloadPlans: Int,
    val metadataChecks: Int, val transferBytes: Long, val discoveryVideoId: String?, val completeTitle: String?,
    val first: KmpFirstResult?, val approved: Boolean, val bindingId: String?,
    val resolvesInFlight: Int, val rejectedResourcesPending: Int, val rejectedResourceCloseFailed: Boolean,
    val acquireCalls: Int, val restoreCalls: Int, val inspectCalls: Int,
    val preparePlaybackCalls: Int, val prepareDownloadCalls: Int, val transferCalls: Int,
    val typedRequestId: String?, val typedReceiptId: String?, val typedBindingId: String?,
    val typedRecordingId: String?, val typedSubjectId: String?, val typedVideoId: String?,
    val typedFirst: FirstResultObservation?, val playbackRepresentationId: String?,
    val downloadRepresentationId: String?,
) {
    fun json() = buildJsonObject {
        put("searchCalls", searchCalls); put("resolveCalls", resolveCalls); put("downloadCalls", downloadCalls)
        put("downloadPlans", downloadPlans); put("firstVideoAuthorChecks", metadataChecks)
        put("transferBytes", transferBytes); put("discoveryVideoId", discoveryVideoId)
        put("completeTitle", completeTitle); put("approvedRawFirst", approved); put("bindingId", bindingId)
        put("resolvesInFlight", resolvesInFlight); put("rejectedResourcesPending", rejectedResourcesPending)
        put("rejectedResourceCloseFailed", rejectedResourceCloseFailed)
        put("acquireCalls", acquireCalls); put("restoreCalls", restoreCalls); put("inspectCalls", inspectCalls)
        put("preparePlaybackCalls", preparePlaybackCalls); put("prepareDownloadCalls", prepareDownloadCalls)
        put("transferCalls", transferCalls)
        put("typedRequestId", typedRequestId); put("typedReceiptId", typedReceiptId)
        put("typedBindingId", typedBindingId); put("typedRecordingId", typedRecordingId)
        put("typedSubjectId", typedSubjectId); put("typedVideoId", typedVideoId)
        put("playbackRepresentationId", playbackRepresentationId)
        put("downloadRepresentationId", downloadRepresentationId)
        first?.let { receipt -> putJsonObject("rawFirst") {
            put("kind", receipt.kind.name); put("ordinal", receipt.selectionOrdinal)
            // Invalid/malformed receipt locators are never echoed. Complete valid titles are
            // public metadata; no resolved endpoint, plan, source body or resource is serialized.
            put("id", receipt.id.takeIf { it.length <= 200 && it.matches(Regex("[A-Za-z0-9_-]*")) })
            put("title", receipt.title.takeIf(YouTubeBrowseText::isCompleteTitle))
        } }
        typedFirst?.let { receipt -> putJsonObject("typedRawFirst") {
            put("kind", receipt.entry.kind?.name); put("ordinal", receipt.entry.ordinal)
            put("queryTitle", receipt.queryTitle.takeIf(YouTubeBrowseText::isCompleteTitle))
            put("id", receipt.entry.id?.takeIf { it.length <= 200 && it.matches(Regex("[A-Za-z0-9_-]*")) })
            put("title", receipt.entry.title?.takeIf(YouTubeBrowseText::isCompleteTitle))
        } }
    }
}

internal object CentralProofPolicy {
    const val CHANNEL_URL = "https://www.youtube.com/@oPiiLz"
    const val MAX_VIDEOS = 2
    const val MAX_BYTES = 67_108_864L
    val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    val REPRESENTATION = Regex("yt-audio-v1:[0-9a-f]{64}")
    val SHA256 = Regex("[0-9a-f]{64}")
    fun watch(id: String) = "https://www.youtube.com/watch?v=$id"

    /** Mirrors the canonical durable-ID character/length contract; no URL or secret is allowed. */
    fun isProofId(value: String): Boolean = value.length in 1..256 && value.isNotBlank() &&
        value.none { it.code < 32 || it.code in 127..159 }

    fun isApprovedAuthor(value: String?): Boolean {
        val uri = try { URI(value ?: return false) } catch (_: Exception) { return false }
        return uri.scheme == "https" && uri.host?.lowercase(Locale.ROOT) == "www.youtube.com" && uri.port == -1 &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath?.lowercase(Locale.ROOT) == "/@opiilz" &&
            value?.lowercase(Locale.ROOT) == CHANNEL_URL.lowercase(Locale.ROOT)
    }

    fun requireDiscovery(item: MediaItem) {
        proofRequire(item.kind == MediaKind.MUSIC && item.source.provider == SourceProvider.YOUTUBE &&
            VIDEO_ID.matches(item.source.canonicalId) && item.id == "youtube:${item.source.canonicalId}" &&
            item.source.canonicalUrl == watch(item.source.canonicalId) && item.source.enclosureUrl == null &&
            item.source.feedUrl == null && item.source.localAssetId == null &&
            YouTubeBrowseText.isCompleteTitle(item.title), "INVALID_DISCOVERY_IDENTITY", FailureCode.PARSE_FAILURE)
    }

    fun requirePlan(plan: PlannedDownload, videoId: String): Long {
        proofRequire(VIDEO_ID.matches(videoId) && plan.mediaId == videoId && plan.inspectionUrl == watch(videoId),
            "PLAN_VIDEO_OUTSIDE_APPROVAL", FailureCode.PERMISSION_DENIED)
        proofRequire(plan.audio == null && plan.requestHeaders.isEmpty() && plan.credentialRef == null && plan.sabrCheckpoint == null &&
            plan.authorizedOrigin.isBlank() && plan.primary.role == TrackRole.AUDIO,
            "PLAN_IS_NOT_ANONYMOUS_STANDALONE_AUDIO", FailureCode.PERMISSION_DENIED)
        val track = plan.primary
        proofRequire(track.selectable && track.transport in setOf(TransportKind.PROGRESSIVE, TransportKind.SABR) &&
            track.segments.isEmpty() && track.initialization == null && track.sizeBytes?.let { it in 1..MAX_BYTES } == true,
            "PLAN_HAS_NO_ENFORCEABLE_RETAINED_BYTE_BOUND", FailureCode.PERMISSION_DENIED)
        proofRequire(track.sabr?.let { it.videoId == videoId } != false, "SABR_VIDEO_CHANGED", FailureCode.PERMISSION_DENIED)
        PublicUrlPolicy.normalize(track.url!!)
        track.sabr?.let { PublicUrlPolicy.normalize(it.endpoint) }
        return track.sizeBytes!!
    }

    fun requireTerminatedPredecessor(previous: CentralProcessEvidence, current: CentralProcessEvidence) {
        proofRequire(previous.pid > 0 && previous.startedEpochMs > 0 &&
            (previous.pid != current.pid || previous.startedEpochMs != current.startedEpochMs), "SAME_PROCESS_REOPEN_IS_NOT_RESTART")
        val old = ProcessHandle.of(previous.pid).orElse(null)
        if (old != null && old.isAlive) {
            val start = old.info().startInstant().orElse(null)?.toEpochMilli()
            proofRequire(start != null && start != previous.startedEpochMs, "ONLINE_PROCESS_HAS_NOT_TERMINATED")
        }
    }
}

@Serializable
internal data class CentralNativeEvidence(
    val generation: Long, val entryId: String, val durationMs: Long,
    val firstPlayingPositionMs: Long, val advancingPositionMs: Long, val pausedPositionMs: Long, val resumedPositionMs: Long,
    val forwardTargetMs: Long, val forwardConfirmedMs: Long, val forwardAdvancingPositionMs: Long,
    val backwardConfirmedMs: Long, val backwardAdvancingPositionMs: Long, val finalPausedPositionMs: Long,
    val silentAudio: Boolean, val mpvVersion: String?, val ffmpegVersion: String?, val audioOutput: String?,
    val tlsVerification: Boolean = true, val physicalAudioVerified: Boolean = false,
)

@Serializable
internal data class CentralProcessEvidence(val pid: Long, val startedEpochMs: Long, val networkNamespace: String?) {
    companion object {
        fun current(): CentralProcessEvidence {
            val process = ProcessHandle.current()
            val start = process.info().startInstant().orElse(null)?.toEpochMilli() ?: proofFail("PROCESS_START_UNAVAILABLE")
            return CentralProcessEvidence(process.pid(), start, networkNamespace())
        }
    }
}

@Serializable
internal data class CentralNetworkEvidence(
    val method: String = "linux-network-namespace-no-nonloopback-interface-or-route-literal-tcp-connect-denied",
    val networkNamespace: String, val threadNamespaceCount: Int, val nonLoopbackInterfaces: Int = 0,
    val nonLoopbackRoutes: Int = 0, val literalPublicTcpDenied: Boolean = true,
) {
    companion object {
        suspend fun verify(previousNamespace: String?): CentralNetworkEvidence = withContext(Dispatchers.IO) {
            proofRequire(System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("linux"),
                "OS_NETWORK_DENIAL_NOT_IMPLEMENTED_ON_THIS_HOST")
            val namespace = networkNamespace() ?: proofFail("NETWORK_NAMESPACE_UNAVAILABLE")
            proofRequire(previousNamespace != null && namespace != previousNamespace, "PRIVATE_NETWORK_NAMESPACE_REQUIRED")
            fun topology(): Int {
                proofRequire(networkNamespace() == namespace, "NETWORK_NAMESPACE_CHANGED")
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                proofRequire(interfaces.all { it.isLoopback && it.name == "lo" }, "NONLOOPBACK_NETWORK_INTERFACE_PRESENT")
                for (file in listOf("/proc/net/route", "/proc/net/ipv6_route")) {
                    val lines = readBounded(Path.of(file), 64 * 1024).decodeToString().lineSequence()
                        .filter { it.isNotBlank() }.toList()
                    val rows = if (file.endsWith("/route")) lines.drop(1) else lines
                    proofRequire(rows.all { row ->
                        val columns = row.trim().split(Regex("\\s+"))
                        if (file.endsWith("/route")) columns.size >= 11 && columns.first() == "lo"
                        else columns.size >= 10 && columns.last() == "lo"
                    }, "NONLOOPBACK_NETWORK_ROUTE_PRESENT")
                }
                var count = 0
                Files.newDirectoryStream(Path.of("/proc/self/task")).use { threads ->
                    for (thread in threads) {
                        val threadNs = try { Files.readSymbolicLink(thread.resolve("ns/net")).toString() }
                        catch (_: java.nio.file.NoSuchFileException) { continue } // A thread exited during enumeration.
                        proofRequire(threadNs == namespace, "NATIVE_THREAD_NETWORK_NAMESPACE_DIFFERS")
                        count++
                    }
                }
                proofRequire(count > 0, "NATIVE_THREAD_NAMESPACE_UNVERIFIED")
                return count
            }
            topology()
            // Literal address: DNS denial is not confused with actual OS/native-socket egress denial.
            var denied = false
            Socket().use { socket ->
                try { socket.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)), 443), 500) }
                catch (_: SocketException) { denied = true }
            }
            proofRequire(denied, "LITERAL_PUBLIC_TCP_NOT_OS_DENIED")
            CentralNetworkEvidence(networkNamespace = namespace, threadNamespaceCount = topology())
        }
    }
}

@Serializable
internal data class CentralProofMarker(
    val schema: Int = 2, val approvedChannel: String = CentralProofPolicy.CHANNEL_URL,
    val process: CentralProcessEvidence, val mediaId: String, val completeTitle: String,
    val discoveryVideoId: String, val firstResultVideoId: String, val firstResultTitle: String,
    val requestId: String, val receiptId: String, val bindingId: String, val recordingId: String,
    val subjectId: String, val occurrenceId: String, val downloadId: String,
    val assetId: String, val sizeBytes: Long, val sha256: String, val representationId: String,
    val measuredDurationMs: Long, val entryId: String, val positionMs: Long,
    val native: CentralNativeEvidence,
) {
    fun requireValid() {
        proofRequire(schema == 2 && approvedChannel == CentralProofPolicy.CHANNEL_URL &&
            CentralProofPolicy.VIDEO_ID.matches(discoveryVideoId) && mediaId == "youtube:$discoveryVideoId" &&
            YouTubeBrowseText.isCompleteTitle(completeTitle) &&
            CentralProofPolicy.VIDEO_ID.matches(firstResultVideoId) &&
            YouTubeBrowseText.isCompleteTitle(firstResultTitle) &&
            CentralProofPolicy.isProofId(requestId) && CentralProofPolicy.isProofId(receiptId) &&
            CentralProofPolicy.isProofId(bindingId) && CentralProofPolicy.isProofId(recordingId) &&
            CentralProofPolicy.isProofId(subjectId) && CentralProofPolicy.isProofId(occurrenceId) &&
            CentralProofPolicy.isProofId(downloadId) && CentralProofPolicy.isProofId(assetId) &&
            sizeBytes in 1..CentralProofPolicy.MAX_BYTES && CentralProofPolicy.SHA256.matches(sha256) &&
            CentralProofPolicy.REPRESENTATION.matches(representationId) && measuredDurationMs > 0 &&
            entryId == occurrenceId && positionMs in 1 until measuredDurationMs &&
            native.entryId == entryId && !native.physicalAudioVerified && native.tlsVerification && native.durationMs >= 12_000 &&
            native.advancingPositionMs > native.firstPlayingPositionMs + 2_000 &&
            native.resumedPositionMs > native.pausedPositionMs + 500 &&
            abs(native.forwardTargetMs - native.forwardConfirmedMs) <= 500 &&
            native.forwardAdvancingPositionMs > native.forwardConfirmedMs + 500 &&
            native.backwardConfirmedMs in 250..1_250 && native.backwardAdvancingPositionMs > native.backwardConfirmedMs + 500 &&
            abs(native.finalPausedPositionMs - positionMs) <= 500,
            "INVALID_ONLINE_PROOF_MARKER", FailureCode.CORRUPT_MEDIA)
    }
}

/** Owned, bounded, atomic metadata marker only. No audio bytes or signed URLs in proof files. */
internal class CentralProofFiles(private val directory: Path) {
    private val parent = directory.resolve("verification")
    private val marker = parent.resolve("central-listening-online.json")

    private fun requireOwned() {
        proofRequire(directory.isAbsolute && directory.parent != null &&
            marker.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize()),
            "PROOF_PATH_NOT_OWNED")
        DesktopProcessContext.rejectSymbolicAncestors(marker)
    }

    fun requireNoMarker() {
        requireOwned()
        proofRequire(!Files.exists(marker, NOFOLLOW_LINKS), "ONLINE_MARKER_ALREADY_EXISTS")
    }

    suspend fun writeNew(value: CentralProofMarker) = withContext(Dispatchers.IO) {
        requireNoMarker(); value.requireValid()
        val bytes = Json.encodeToString(value).toByteArray()
        proofRequire(bytes.size <= MAX_BYTES, "ONLINE_MARKER_TOO_LARGE")
        DesktopProcessContext.privateDirectory(parent)
        val staging = Files.createTempFile(parent, ".central-proof-", ".json")
        try {
            if (staging.fileSystem.supportedFileAttributeViews().contains("posix"))
                Files.setPosixFilePermissions(staging, PosixFilePermissions.fromString("rw-------"))
            FileChannel.open(staging, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            requireNoMarker()
            Files.move(staging, marker, ATOMIC_MOVE)
        } finally { Files.deleteIfExists(staging) }
    }

    suspend fun read(): CentralProofMarker = withContext(Dispatchers.IO) {
        requireOwned()
        proofRequire(Files.isRegularFile(marker, NOFOLLOW_LINKS) && Files.size(marker) <= MAX_BYTES,
            "ONLINE_MARKER_MISSING_OR_TOO_LARGE", FailureCode.CORRUPT_MEDIA)
        val text = readBounded(marker, MAX_BYTES).decodeToString(throwOnInvalidSequence = true)
        try { Json.decodeFromString<CentralProofMarker>(text).also { it.requireValid() } }
        catch (failure: VeyraException) { throw failure }
        catch (_: Exception) { proofFail("INVALID_ONLINE_PROOF_MARKER", FailureCode.CORRUPT_MEDIA) }
    }

    companion object { const val MAX_BYTES = 512 * 1024 }
}

private fun networkNamespace(): String? = try {
    Files.readSymbolicLink(Path.of("/proc/self/ns/net")).toString().takeIf { it.matches(Regex("net:\\[[0-9]+]")) }
} catch (_: Exception) { null }

private fun readBounded(path: Path, maximum: Int): ByteArray = Files.newInputStream(path, READ, NOFOLLOW_LINKS).use {
    it.readNBytes(maximum + 1).also { bytes -> proofRequire(bytes.size <= maximum, "PROOF_INPUT_TOO_LARGE") }
}

private fun nativeDiagnostic(value: String?): String? = value?.takeIf {
    it.length in 1..120 && it.none { character -> character.code < 32 || character.code == 127 } &&
        "://" !in it && '\\' !in it
}

private fun requireHealthy(value: PlaybackState) {
    value.failure?.let { throw VeyraException(it) }
    proofRequire(value.phase != PlaybackPhase.ERROR, "NATIVE_PLAYBACK_ERROR")
}

private fun requireEmptyLibrary(snapshot: AppSnapshot) {
    proofRequire(snapshot.media.isEmpty() && snapshot.shows.isEmpty() && snapshot.downloads.isEmpty() &&
        snapshot.playbackBindings.isEmpty() && snapshot.downloadAttempts.isEmpty() && snapshot.downloadFinalizations.isEmpty() &&
        snapshot.history.isEmpty() && snapshot.progress.isEmpty() && snapshot.queues.values.all { it.entries.isEmpty() } &&
        snapshot.playlists.isEmpty() && snapshot.favoriteIds.isEmpty() && snapshot.savedEpisodeIds.isEmpty() &&
        snapshot.followedShowIds.isEmpty() && snapshot.artists.isEmpty() && snapshot.albums.isEmpty(), "DEDICATED_EMPTY_LIBRARY_REQUIRED")
}

private fun requireEmptyAcquisition(state: AcquisitionRepositoryState) {
    proofRequire(state.requests.isEmpty() && state.receipts.isEmpty() && state.recordings.isEmpty() &&
        state.subjects.isEmpty() && state.bindings.isEmpty() && state.runs.isEmpty() && state.snapshots.isEmpty() &&
        state.scopes.isEmpty() && state.occurrences.isEmpty() && state.batches.isEmpty() && state.claims.isEmpty() &&
        state.assetUses.isEmpty() && state.physicalDownloads.isEmpty() && state.physicalAttempts.isEmpty() &&
        state.physicalFinalizations.isEmpty() && state.materializations.isEmpty() && state.materializationReceipts.isEmpty() &&
        state.queues.values.all { it.displayedOrder.isEmpty() && it.originalOrder.isEmpty() &&
            it.currentOccurrenceId == null && it.positionMs == 0L }, "DEDICATED_EMPTY_ACQUISITION_REQUIRED")
}

private suspend fun awaitCondition(timeoutMs: Long, predicate: suspend () -> Boolean) = withTimeout(timeoutMs) {
    while (!predicate()) { currentCoroutineContext().ensureActive(); delay(25) }
}

private fun proofRequire(condition: Boolean, detail: String, code: FailureCode = FailureCode.INVALID_STATE) {
    if (!condition) proofFail(detail, code)
}

private fun proofFail(detail: String, code: FailureCode = FailureCode.INVALID_STATE): Nothing =
    throw VeyraException(AppFailure(code, detail))
