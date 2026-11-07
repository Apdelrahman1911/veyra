package dev.veyra.desktop.verification

import dev.kmpyt.YouTubeNativePlaybackLimits
import dev.kmpyt.extraction.PlannedDownload
import dev.kmpyt.extraction.SourcePlaybackRequest
import dev.kmpyt.extraction.TrackRole
import dev.kmpyt.extraction.TransportKind
import dev.kmpyt.youtube.YouTubeBrowseText
import dev.veyra.app.AppContainer
import dev.veyra.app.AppInitialization
import dev.veyra.desktop.DesktopPlatformServices
import dev.veyra.desktop.runtime.DesktopMediaExecutionPolicy
import dev.veyra.desktop.runtime.DesktopProcessContext
import dev.veyra.model.*
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.PlaybackCommand
import dev.veyra.playback.PlaybackPhase
import dev.veyra.playback.PlaybackResource
import dev.veyra.playback.PlaybackState
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.playback.getOrThrow
import dev.veyra.source.BrowseKind
import dev.veyra.source.BrowseRequest
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.execution.KmpFirstResult
import dev.veyra.source.execution.KmpMediaGateway
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
        requireEmptyLibrary(app.store.state.value)
        val files = CentralProofFiles(Path.of(app.platform.dataDirectory))
        files.requireNoMarker()
        proofRequire(app.files.bytesUsed() == 0L, "NONEMPTY_MEDIA_DIRECTORY")
        proofRequire(!app.store.state.value.settings.offlineOnly, "ONLINE_PHASE_IS_OFFLINE")
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

        stage = "raw-first-search-and-native-play"
        app.onMusicAction(MusicUiAction.Play(item))
        val firstPlaying = awaitPlayback(app, timeoutMs = 180_000) {
            it.currentItem?.id == item.id && it.isPlaying && it.phase == PlaybackPhase.PLAYING && it.positionMs >= 600
        }
        val binding = firstPlaying.executionBinding ?: proofFail("PLAYBACK_BINDING_MISSING")
        authorization.requireBinding(binding)
        proofRequire(app.store.state.value.playbackBindings[binding.id] == binding, "UNDURABLE_FIRST_RESULT")
        milestones += "complete-title-raw-first-bound-in-room"
        observedNative = exerciseNative(app, firstPlaying)
        milestones += "native-advance-pause-resume-forward-back-seek"

        stage = "same-binding-production-download"
        // Normal UI command captures the currently displayed immutable receipt. No second search.
        app.onMusicAction(MusicUiAction.Download(item))
        val record = awaitCompletedDownload(app, item.id)
        val asset = record.asset ?: proofFail("COMPLETED_ASSET_MISSING")
        proofRequire(record.executionBindingId == binding.id && asset.executionBindingId == binding.id,
            "DOWNLOAD_BINDING_CHANGED")
        proofRequire(asset.mediaId == item.id && asset.representationId == record.representationId &&
            asset.representationId?.matches(CentralProofPolicy.REPRESENTATION) == true &&
            asset.sizeBytes in 1..CentralProofPolicy.MAX_BYTES && asset.sha256?.matches(CentralProofPolicy.SHA256) == true &&
            (asset.measuredDurationMs ?: 0) > 0, "DOWNLOAD_EVIDENCE_INCOMPLETE")
        app.files.verifiedFile(asset) // streaming local size/SHA check, never a competing downloader
        proofRequire(authorization.snapshot().searchCalls == 1 && authorization.snapshot().downloadCalls == 1 &&
            authorization.snapshot().downloadPlans == 1, "ACQUISITION_COUNT_CHANGED")
        milestones += "same-binding-kmpyt-download-native-validated-sha256-room-complete"

        stage = "persist-offline-restart-marker"
        app.playback.dispatch(PlaybackCommand.FlushProgress).getOrThrow()
        app.changeSettings { it.copy(offlineOnly = true) }
        awaitCondition(20_000) { app.store.state.value.settings.offlineOnly }
        app.playback.dispatch(PlaybackCommand.FlushProgress).getOrThrow()
        val snapshot = app.store.state.value
        val queue = snapshot.queue(QueueContext.MUSIC)
        val position = snapshot.progress[item.id] ?: proofFail("POSITION_NOT_PERSISTED")
        proofRequire(queue.currentEntry?.mediaId == item.id && queue.currentEntryId != null &&
            position.executionBindingId == binding.id && position.positionMs > 0 &&
            abs(queue.positionMs - position.positionMs) <= 500, "POSITION_BINDING_CHANGED")
        files.writeNew(CentralProofMarker(
            process = process, mediaId = item.id, completeTitle = item.title,
            discoveryVideoId = item.source.canonicalId, binding = binding,
            downloadId = record.id, assetId = asset.id, sizeBytes = asset.sizeBytes,
            sha256 = asset.sha256!!, representationId = asset.representationId!!,
            measuredDurationMs = asset.measuredDurationMs!!,
            entryId = queue.currentEntryId!!, positionMs = queue.positionMs,
            native = observedNative!!,
        ))
        milestones += "offline-setting-binding-hash-position-marker-persisted"
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

        stage = "room-offline-restore-and-file-validation"
        val snapshot = app.store.state.value
        proofRequire(snapshot.settings.offlineOnly, "OFFLINE_SETTING_NOT_RESTORED")
        val item = snapshot.media[marker.mediaId] ?: proofFail("ORIGINAL_ITEM_NOT_RESTORED")
        CentralProofPolicy.requireDiscovery(item)
        proofRequire(item.title == marker.completeTitle && item.source.canonicalId == marker.discoveryVideoId &&
            snapshot.playbackBindings[marker.binding.id] == marker.binding &&
            snapshot.queue(QueueContext.MUSIC).currentEntryId == marker.entryId &&
            snapshot.queue(QueueContext.MUSIC).currentEntry?.mediaId == item.id &&
            snapshot.queue(QueueContext.MUSIC).positionMs == marker.positionMs &&
            snapshot.progress[item.id]?.executionBindingId == marker.binding.id,
            "ROOM_MARKER_IDENTITY_CHANGED")
        val restored = app.playback.state.value
        proofRequire(!restored.isPlaying && !restored.desiredPlaying && restored.phase == PlaybackPhase.PAUSED &&
            restored.currentItem?.id == item.id && restored.positionMs == marker.positionMs,
            "RESTORE_AUTOPLAY_OR_POSITION_CHANGED")
        val record = awaitCompletedDownload(app, item.id)
        val asset = record.asset ?: proofFail("COMPLETED_ASSET_MISSING")
        proofRequire(record.id == marker.downloadId && asset.id == marker.assetId && asset.mediaId == marker.mediaId &&
            record.executionBindingId == marker.binding.id && asset.executionBindingId == marker.binding.id &&
            asset.sizeBytes == marker.sizeBytes && asset.sha256 == marker.sha256 &&
            record.representationId == marker.representationId && asset.representationId == marker.representationId &&
            asset.measuredDurationMs == marker.measuredDurationMs, "RESTORED_ASSET_CHANGED")
        app.files.verifiedFile(asset)
        milestones += "room-offline-binding-hash-position-restored-no-autoplay"

        stage = "local-native-play-and-seek-with-os-egress-denied"
        app.onMusicAction(MusicUiAction.PlayQueueEntry(QueueContext.MUSIC, marker.entryId))
        val playing = awaitPlayback(app, timeoutMs = 180_000) {
            it.currentItem?.id == item.id && it.executionBinding == marker.binding && it.isPlaying &&
                it.phase == PlaybackPhase.PLAYING && it.positionMs > marker.positionMs + 600
        }
        observedNative = exerciseNative(app, playing)
        proofRequire(authorization.snapshot().let { it.searchCalls == 0 && it.resolveCalls == 0 && it.downloadCalls == 0 },
            "OFFLINE_GATEWAY_WAS_ENTERED")
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
            val failure = (error as? VeyraException)?.failure
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
            proofRequire(abs(app.store.state.value.queue(QueueContext.MUSIC).positionMs - after.positionMs) <= 500,
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
            result = app.playback.state.value
            requireHealthy(result)
            if (generation != null) proofRequire(result.generation == generation, "NATIVE_GENERATION_CHANGED")
            predicate(result)
        }
        return result
    }

    private suspend fun awaitCompletedDownload(app: AppContainer, mediaId: String): DownloadRecord {
        var result: DownloadRecord? = null
        awaitCondition(8 * 60_000L) {
            requireHealthy(app.playback.state.value)
            val records = app.library.value.downloads.values.filter { it.mediaId == mediaId }
            proofRequire(records.size <= 1, "MULTIPLE_PROOF_DOWNLOADS")
            result = records.singleOrNull()
            result?.let { record ->
                if (record.status in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED, DownloadStatus.PAUSED,
                        DownloadStatus.NEEDS_FOREGROUND, DownloadStatus.WAITING_NETWORK)) {
                    throw VeyraException(record.failure ?: AppFailure(FailureCode.RECOVERY_REQUIRED, "DOWNLOAD_NOT_RUNNING"))
                }
            }
            result?.status == DownloadStatus.COMPLETE && result?.asset != null
        }
        return result!!
    }

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

    fun discovered(item: MediaItem) = synchronized(gate) {
        requireActive()
        proofRequire(discovery == null, "SECOND_DISCOVERY_NOT_ALLOWED")
        CentralProofPolicy.requireDiscovery(item)
        discovery = item
    }

    fun disable() = synchronized(gate) { disabled = true }

    fun wrap(delegate: KmpMediaGateway): KmpMediaGateway = object : KmpMediaGateway {
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

    fun authorizeDownload(plan: PlannedDownload): Unit = synchronized(gate) {
        requireActive()
        proofRequire(downloadCalls == 1 && downloadPlans == 0, "SECOND_DOWNLOAD_TRANSFER_DENIED", FailureCode.PERMISSION_DENIED)
        val selected = binding ?: proofFail("PLAN_WITHOUT_FIRST_BINDING", FailureCode.PERMISSION_DENIED)
        plannedBytes = CentralProofPolicy.requirePlan(plan, selected.selectedVideoId!!)
        downloadPlans++
        Unit
    }

    fun authorizePlayback(request: SourcePlaybackRequest) = synchronized(gate) {
        requireActive()
        val selected = binding ?: proofFail("PLAN_WITHOUT_FIRST_BINDING", FailureCode.PERMISSION_DENIED)
        val videoId = selected.selectedVideoId ?: proofFail("PLAN_WITHOUT_SELECTED_VIDEO", FailureCode.PERMISSION_DENIED)
        proofRequire(playbackResolves == 1 && request.metadata.id == videoId &&
            request.metadata.kind == dev.kmpyt.MediaKind.AUDIO && request.plan.primary.transport == TransportKind.SABR &&
            (playbackRequest == null || playbackRequest === request), "PLAYBACK_SESSION_CHANGED", FailureCode.PERMISSION_DENIED)
        CentralProofPolicy.requirePlan(request.plan, videoId)
        request.progressiveFallback?.let { CentralProofPolicy.requirePlan(it, videoId) }
        if (playbackRequest == null) playbackRequest = request
    }

    fun snapshot(): CentralAuthorizationEvidence = synchronized(gate) {
        CentralAuthorizationEvidence(searchCalls, resolveCalls, downloadCalls, downloadPlans, metadataChecks,
            transferBytes, discovery?.source?.canonicalId, discovery?.title, observed, accepted != null, binding?.id,
            resolvesInFlight, rejectedResources.size, rejectedResourceCloseFailed)
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
) {
    fun json() = buildJsonObject {
        put("searchCalls", searchCalls); put("resolveCalls", resolveCalls); put("downloadCalls", downloadCalls)
        put("downloadPlans", downloadPlans); put("firstVideoAuthorChecks", metadataChecks)
        put("transferBytes", transferBytes); put("discoveryVideoId", discoveryVideoId)
        put("completeTitle", completeTitle); put("approvedRawFirst", approved); put("bindingId", bindingId)
        put("resolvesInFlight", resolvesInFlight); put("rejectedResourcesPending", rejectedResourcesPending)
        put("rejectedResourceCloseFailed", rejectedResourceCloseFailed)
        first?.let { receipt -> putJsonObject("rawFirst") {
            put("kind", receipt.kind.name); put("ordinal", receipt.selectionOrdinal)
            // Invalid/malformed receipt locators are never echoed. Complete valid titles are
            // public metadata; no resolved endpoint, plan, source body or resource is serialized.
            put("id", receipt.id.takeIf { it.length <= 200 && it.matches(Regex("[A-Za-z0-9_-]*")) })
            put("title", receipt.title.takeIf(YouTubeBrowseText::isCompleteTitle))
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
    val schema: Int = 1, val approvedChannel: String = CentralProofPolicy.CHANNEL_URL,
    val process: CentralProcessEvidence, val mediaId: String, val completeTitle: String,
    val discoveryVideoId: String, val binding: PlaybackBinding, val downloadId: String,
    val assetId: String, val sizeBytes: Long, val sha256: String, val representationId: String,
    val measuredDurationMs: Long, val entryId: String, val positionMs: Long,
    val native: CentralNativeEvidence,
) {
    fun requireValid() {
        proofRequire(schema == 1 && approvedChannel == CentralProofPolicy.CHANNEL_URL &&
            CentralProofPolicy.VIDEO_ID.matches(discoveryVideoId) && mediaId == "youtube:$discoveryVideoId" &&
            YouTubeBrowseText.isCompleteTitle(completeTitle) && binding.mediaId == mediaId &&
            binding.queryTitle == completeTitle && binding.firstResultKind == FirstResultKind.VIDEO &&
            CentralProofPolicy.VIDEO_ID.matches(binding.firstResultId) && binding.selectedVideoId == binding.firstResultId &&
            binding.firstResultUrl == CentralProofPolicy.watch(binding.firstResultId) && binding.selectionOrdinal == 0 &&
            YouTubeBrowseText.isCompleteTitle(binding.firstResultTitle) && binding.id.isNotBlank() &&
            sizeBytes in 1..CentralProofPolicy.MAX_BYTES && CentralProofPolicy.SHA256.matches(sha256) &&
            CentralProofPolicy.REPRESENTATION.matches(representationId) && measuredDurationMs > 0 &&
            entryId.isNotBlank() && positionMs in 1 until measuredDurationMs &&
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

private suspend fun awaitCondition(timeoutMs: Long, predicate: () -> Boolean) = withTimeout(timeoutMs) {
    while (!predicate()) { currentCoroutineContext().ensureActive(); delay(25) }
}

private fun proofRequire(condition: Boolean, detail: String, code: FailureCode = FailureCode.INVALID_STATE) {
    if (!condition) proofFail(detail, code)
}

private fun proofFail(detail: String, code: FailureCode = FailureCode.INVALID_STATE): Nothing =
    throw VeyraException(AppFailure(code, detail))
