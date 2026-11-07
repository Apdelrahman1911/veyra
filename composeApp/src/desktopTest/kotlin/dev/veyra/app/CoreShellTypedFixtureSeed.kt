package dev.veyra.app

import dev.veyra.acquisition.AcquisitionCaptureFactory
import dev.veyra.acquisition.AcquisitionCoordinator
import dev.veyra.acquisition.AcquisitionResolution
import dev.veyra.data.AcquisitionPlaybackCommand
import dev.veyra.data.AdmitStandalonePhysicalDownload
import dev.veyra.data.AttachAcquisitionOccurrence
import dev.veyra.data.CaptureAcquisition
import dev.veyra.data.ExecutionQueueExpectation
import dev.veyra.data.LibraryStore
import dev.veyra.data.OccurrenceAttachmentExpectation
import dev.veyra.data.PhysicalDownloadCommit
import dev.veyra.data.QueueAcquisitionAdmission
import dev.veyra.data.QueueAdmissionOperation
import dev.veyra.data.StandalonePhysicalDownloadAdmission
import dev.veyra.data.UpdateStandaloneDownloadIntent
import dev.veyra.model.*
import dev.veyra.playback.Clock
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.ResolvedPlayable
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
import dev.veyra.source.youtube.YouTubeTransferProgress
import dev.veyra.source.youtube.YouTubeTransferResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Authored recording answers only. No title is truncated and every answer is raw ordinal zero. */
internal data class CoreShellTypedTitles(
    val resolvedTitles: Map<String, String> = emptyMap(),
) {
    fun recordingId(title: String): String = when (title) {
        CoreShellTestFixture.music.title -> "AAAAAAAAAAA"
        CoreShellTestFixture.second.title -> "BBBBBBBBBBB"
        CoreShellTestFixture.episode.title -> "CCCCCCCCCCC"
        else -> "DDDDDDDDDDD"
    }

    fun resolvedTitle(title: String): String = resolvedTitles[title] ?: when (title) {
        CoreShellTestFixture.music.title -> "Authored resolved music — not live audio"
        CoreShellTestFixture.second.title -> "Authored resolved second — not live audio"
        CoreShellTestFixture.episode.title -> "Authored resolved episode — not live audio"
        else -> "Authored resolved recording — not live audio"
    }

    fun canonicalUrl(title: String): String = "https://www.youtube.com/watch?v=${recordingId(title)}"
}

/** Exact authored IDs returned by the fixture seed so tests can reason about the canonical graph. */
internal data class CoreShellSeedState(
    val pausedDownloadId: String,
    val bindingIds: Map<String, ExecutionBindingId>,
    val occurrenceIds: Map<String, ExecutionOccurrenceId>,
)

/**
 * Seeds the real Room owner through actual capture/claim/commit/admission/attachment commands.
 * No snapshot execution/download row is written and no native source is prepared.
 */
internal object CoreShellTypedFixtureSeed {
    const val PAUSED_DOWNLOAD_ID = "ui-fixture-paused-download"
    private const val PAUSED_CLAIM_ID = "ui-fixture-paused-claim"

    suspend fun seed(store: LibraryStore, titles: CoreShellTypedTitles, clock: Clock): CoreShellSeedState {
        val seedGateway = CoreShellFixtureGateway(titles, recordQueries = false)
        val captures = AcquisitionCaptureFactory(clock)
        val coordinator = AcquisitionCoordinator(store.acquisition, seedGateway, captures, clock, offline = { false })
        val catalog = store.acquisitionView().snapshot
        fun request(mediaId: String): AcquisitionRequest = captures.media(catalog.media.getValue(mediaId), catalog)

        val musicNow = request(CoreShellTestFixture.music.id)
        val musicNext = request(CoreShellTestFixture.second.id)
        val musicCopy = request(CoreShellTestFixture.music.id)
        val episodeNow = request(CoreShellTestFixture.episode.id)
        listOf(musicNow, musicNext, musicCopy, episodeNow).forEach {
            store.acquisition.capture(CaptureAcquisition(it))
        }

        fun occurrence(id: String, request: AcquisitionRequest, mode: PlaybackControlsMode) = ExecutionOccurrence(
            id = ExecutionOccurrenceId(id),
            origin = AcquisitionOrigin.TYPED,
            requestId = request.id,
            snapshotMember = null,
            scopeMember = null,
            subjectId = null,
            bindingId = null,
            controlsMode = mode,
            selectionState = OccurrenceSelectionState.PENDING,
            admittedEpochMs = clock.epochMs(),
        )

        val musicOccurrences = listOf(
            occurrence("music-now", musicNow, PlaybackControlsMode.MUSIC),
            occurrence("music-next", musicNext, PlaybackControlsMode.MUSIC),
            occurrence("music-copy", musicCopy, PlaybackControlsMode.MUSIC),
        )
        val podcastOccurrence = occurrence("episode-now", episodeNow, PlaybackControlsMode.PODCAST)
        val musicQueue = store.acquisitionView().acquisition.queues.getValue(QueueContext.MUSIC)
        val podcastQueue = store.acquisitionView().acquisition.queues.getValue(QueueContext.PODCAST)
        store.acquisition.admitQueue(QueueAcquisitionAdmission(
            context = QueueContext.MUSIC,
            expectedQueueRevision = musicQueue.editRevision,
            expectedCatalogRevision = null,
            operation = QueueAdmissionOperation.REPLACE,
            requests = listOf(musicNow, musicNext, musicCopy),
            scope = null,
            occurrences = musicOccurrences,
            outgoingCheckpoint = null,
        ))
        store.acquisition.admitQueue(QueueAcquisitionAdmission(
            context = QueueContext.PODCAST,
            expectedQueueRevision = podcastQueue.editRevision,
            expectedCatalogRevision = null,
            operation = QueueAdmissionOperation.REPLACE,
            requests = listOf(episodeNow),
            scope = null,
            occurrences = listOf(podcastOccurrence),
            outgoingCheckpoint = null,
        ))

        val bindingIds = mutableMapOf<String, ExecutionBindingId>()
        listOf(musicNow to musicOccurrences[0], musicNext to musicOccurrences[1], musicCopy to musicOccurrences[2])
            .forEach { (request, occurrence) ->
                val resolved = coordinator.resolve(request.id)
                val accepted = resolved as? AcquisitionResolution.Accepted
                    ?: error("Authored typed seed selection was not accepted: $resolved")
                val single = accepted.record.outcome as? AcquisitionOutcome.Single
                    ?: error("Authored typed seed did not produce a Single")
                val queue = store.acquisitionView().acquisition.queues.getValue(QueueContext.MUSIC)
                check(store.acquisition.attachOccurrence(AttachAcquisitionOccurrence(
                    OccurrenceAttachmentExpectation(occurrence.id, request.id, QueueContext.MUSIC, queue.editRevision),
                ))) { "Authored typed seed occurrence could not be attached" }
                bindingIds[occurrence.id.value] = single.bindingId
            }

        suspend fun editQueue(
            context: QueueContext,
            order: List<ExecutionOccurrenceId>,
            current: ExecutionOccurrenceId,
            positionMs: Long,
            speed: Float,
            shuffle: Boolean,
            repeat: RepeatMode,
        ) {
            val queue = store.acquisitionView().acquisition.queues.getValue(context)
            store.acquisition.applyPlayback(AcquisitionPlaybackCommand.EditQueue(
                expected = ExecutionQueueExpectation(context, queue.editRevision, queue.currentOccurrenceId),
                displayedOrder = order,
                originalOrder = order,
                currentOccurrenceId = current,
                positionMs = positionMs,
                speed = speed,
                shuffle = shuffle,
                repeat = repeat,
                outgoing = null,
            ))
        }

        val musicOrder = musicOccurrences.map { it.id }
        editQueue(QueueContext.MUSIC, musicOrder, musicOrder.first(), 17_000L, 1f, shuffle = true, repeat = RepeatMode.OFF)
        editQueue(QueueContext.PODCAST, listOf(podcastOccurrence.id), podcastOccurrence.id, 25_000L, 1f,
            shuffle = false, repeat = RepeatMode.OFF)

        // A real scheduling-only physical job/claim paused by the canonical intent command. It has no
        // transfer, lease, staging path, asset or fabricated historical observation.
        val musicBinding = bindingIds.getValue("music-now")
        val admitted: StandalonePhysicalDownloadAdmission = when (val result = store.acquisition.admitStandalonePhysicalDownload(
            AdmitStandalonePhysicalDownload(
                requestId = musicNow.id,
                requiredBindingId = musicBinding,
                proposedDownloadId = PAUSED_DOWNLOAD_ID,
                proposedClaimId = DownloadClaimId(PAUSED_CLAIM_ID),
                nowEpochMs = clock.epochMs(),
            ),
        )) {
            is PhysicalDownloadCommit.Committed -> result.value
            PhysicalDownloadCommit.Stale -> error("Authored paused-download admission was rejected as stale")
        }
        val updated = store.acquisition.updateStandaloneDownloadIntent(UpdateStandaloneDownloadIntent(
            downloadId = PAUSED_DOWNLOAD_ID,
            expectedProducer = admitted.physical.job.producer,
            expectedOwnershipGeneration = admitted.physical.job.ownershipGeneration,
            claimId = admitted.claim.id,
            expectedClaimGeneration = admitted.claim.generation,
            expectedOwnerIntentRevision = admitted.claim.ownerIntentRevision,
            intent = DownloadBatchIntent.PAUSED,
            retainCompletedAsset = true,
            nowEpochMs = clock.epochMs(),
        ))
        val paused = when (updated) {
            is PhysicalDownloadCommit.Committed -> updated.value
            PhysicalDownloadCommit.Stale -> error("Authored paused-download intent was rejected as stale")
        }
        check(paused.job.status == DownloadStatus.PAUSED && paused.job.userPaused) {
            "Authored paused download did not retain paused user intent"
        }

        return CoreShellSeedState(
            pausedDownloadId = PAUSED_DOWNLOAD_ID,
            bindingIds = bindingIds.toMap(),
            occurrenceIds = (musicOccurrences.map { it.id.value } + podcastOccurrence.id.value)
                .associateWith { ExecutionOccurrenceId(it) },
        )
    }
}

/**
 * Deterministic authored source port. It never opens a socket, allocates a native resource for
 * DOWNLOAD or claims a platform client. Invocation and completion counters stay separate.
 */
internal class CoreShellFixtureGateway(
    private val titles: CoreShellTypedTitles = CoreShellTypedTitles(),
    private val recordQueries: Boolean = true,
) : KmpMediaGateway, AcquisitionGateway, AcquisitionExecutionGateway {
    val queries = CopyOnWriteArrayList<String>()
    val resolutions = CopyOnWriteArrayList<PlaybackBinding>()
    val downloadCalls = AtomicInteger()
    val prepareInvocations = AtomicInteger()
    val prepareCompletions = AtomicInteger()
    val prepareFailures = AtomicInteger()
    val prepareCancellations = AtomicInteger()
    val typedDownloadPrepareInvocations = AtomicInteger()
    val typedDownloadInvocations = AtomicInteger()

    private fun noteQuery(title: String) {
        if (recordQueries) queries += title
    }

    override suspend fun firstResult(completeTitle: String): KmpFirstResult {
        noteQuery(completeTitle)
        return KmpFirstResult(completeTitle, FirstResultKind.VIDEO, titles.recordingId(completeTitle),
            titles.resolvedTitle(completeTitle), titles.canonicalUrl(completeTitle))
    }

    override suspend fun resolve(binding: PlaybackBinding, capabilities: EngineCapabilities,
        purpose: ResolvePurpose): ResolvedPlayable {
        resolutions += binding
        // Reserved example.org metadata locator. This authored fake never opens it.
        return ResolvedPlayable(binding.mediaId, "https://example.org/veyra-authored-ui-fixture.m4a",
            mimeType = "audio/mp4", codecs = "aac", durationMs = 180_000L, seekableHint = true,
            executionBinding = binding)
    }

    override suspend fun download(binding: PlaybackBinding, destinationPath: String,
        capabilities: EngineCapabilities, representationId: String?,
        onProgress: suspend (YouTubeTransferProgress) -> Unit): YouTubeTransferResult {
        downloadCalls.incrementAndGet()
        throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE, "fixture_legacy_transfer_unavailable"))
    }

    override suspend fun acquire(request: AcquisitionRequest): SourceAcquisitionResult {
        noteQuery(request.completeTitle)
        if (request.metadata !is MediaCapture) return SourceAcquisitionResult.Unplayable(
            request.id, null, AcquisitionProblem(AcquisitionProblemCode.INVALID_REQUEST))
        val id = titles.recordingId(request.completeTitle)
        val resolved = titles.resolvedTitle(request.completeTitle)
        val url = titles.canonicalUrl(request.completeTitle)
        val entry = SourceEntry(AcquisitionEntryKind.VIDEO, id, resolved, url, ordinal = 0)
        val receipt = FirstResultObservation(request.id, request.completeTitle, entry, url)
        return SourceAcquisitionResult.Single(request.id, receipt,
            SourceDisplayItem(id, resolved, url, AcquisitionEntryKind.VIDEO))
    }

    override fun restore(request: AcquisitionRequest, receipt: FirstResultObservation): SourceAcquisitionResult {
        val entry = receipt.entry
        if (receipt.requestId != request.id || receipt.queryTitle != request.completeTitle || entry.ordinal != 0) {
            throw VeyraException(AppFailure(FailureCode.INVALID_STATE, "fixture_receipt_mismatch"))
        }
        val id = entry.id
        val title = entry.title
        val publicUrl = entry.publicUrl
        return if (entry.kind == AcquisitionEntryKind.VIDEO && entry.issue == null && id != null &&
            title != null && publicUrl != null) {
            SourceAcquisitionResult.Single(request.id, receipt,
                SourceDisplayItem(id, title, publicUrl, AcquisitionEntryKind.VIDEO))
        } else {
            SourceAcquisitionResult.Unplayable(request.id, receipt,
                AcquisitionProblem(AcquisitionProblemCode.FIRST_RESULT_UNSUPPORTED))
        }
    }

    override suspend fun collection(request: AcquisitionRequest, selection: SourceAcquisitionResult.Collection,
        limits: CollectionLimits): SourceCollectionPage =
        throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE, "fixture_collection_unavailable"))

    override suspend fun more(continuation: CollectionContinuation): SourceCollectionPage =
        throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE, "fixture_collection_unavailable"))

    override suspend fun inspect(request: AcquisitionRequest,
        selection: SourceAcquisitionResult.Single): AcquisitionInspection = object : AcquisitionInspection {
        override val selection = selection
    }

    override suspend fun preparePlayback(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
        requiredRepresentationId: String?, inspection: AcquisitionInspection?): AcquisitionPlaybackSource {
        prepareInvocations.incrementAndGet()
        try {
            currentCoroutineContext().ensureActive()
            if (context.request.metadata !is MediaCapture) throw VeyraException(
                AppFailure(FailureCode.UNSUPPORTED_SOURCE, "fixture_media_playback_only"))
            val id = titles.recordingId(context.request.completeTitle)
            val resolved = titles.resolvedTitle(context.request.completeTitle)
            val url = titles.canonicalUrl(context.request.completeTitle)
            if (context.recording.canonicalId != id || context.recording.canonicalUrl != url ||
                context.receipt.observation.entry.id != id || context.receipt.observation.canonicalUrl != url ||
                context.receipt.observation.entry.title != resolved) {
                throw VeyraException(AppFailure(FailureCode.INVALID_STATE,
                    "fixture_typed_playback_identity_mismatch"))
            }
            currentCoroutineContext().ensureActive()
            val mediaDuration = (context.request.metadata as? MediaCapture)?.media?.durationMs
            val source = AcquisitionPlaybackSource(
                context = context,
                uri = "https://example.org/veyra-authored-ui-fixture.m4a",
                transport = StreamTransport.PROGRESSIVE,
                mimeType = "audio/mp4",
                codecs = "aac",
                representationId = requiredRepresentationId ?: "authored-aac",
                durationMs = mediaDuration ?: 180_000L,
                seekableHint = true,
                isLocal = false,
            )
            currentCoroutineContext().ensureActive()
            prepareCompletions.incrementAndGet()
            return source
        } catch (cancelled: CancellationException) {
            prepareCancellations.incrementAndGet()
            throw cancelled
        } catch (failure: Throwable) {
            prepareFailures.incrementAndGet()
            throw failure
        }
    }

    override suspend fun prepareDownload(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
        requiredRepresentationId: String?, inspection: AcquisitionInspection?): PreparedAcquisitionDownload {
        typedDownloadPrepareInvocations.incrementAndGet()
        throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE, "fixture_typed_download_unavailable"))
    }

    override suspend fun download(prepared: PreparedAcquisitionDownload, destinationPath: String,
        onProgress: suspend (AcquisitionTransferProgress) -> Unit): AcquisitionTransferResult {
        typedDownloadInvocations.incrementAndGet()
        downloadCalls.incrementAndGet()
        throw VeyraException(AppFailure(FailureCode.UNSUPPORTED_SOURCE, "fixture_typed_transfer_unavailable"))
    }
}
