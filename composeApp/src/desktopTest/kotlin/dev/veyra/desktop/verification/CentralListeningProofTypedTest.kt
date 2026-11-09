package dev.veyra.desktop.verification

import dev.kmpyt.extraction.PlannedDownload
import dev.kmpyt.extraction.Representation
import dev.kmpyt.extraction.SabrClientInfo
import dev.kmpyt.extraction.SabrFormatId
import dev.kmpyt.extraction.SourcePlaybackMetadata
import dev.kmpyt.extraction.SourcePlaybackRequest
import dev.kmpyt.extraction.TrackRole
import dev.kmpyt.extraction.TransportKind
import dev.kmpyt.extraction.YouTubeSabrResource
import dev.kmpyt.youtube.YouTubeBrowseText
import dev.veyra.app.PolicyMediaGateway
import dev.veyra.app.PolicyMediaGatewayHooks
import dev.veyra.model.*
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.EphemeralPlaybackEndpoint
import dev.veyra.playback.PlaybackResource
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.execution.*
import dev.veyra.source.youtube.YouTubeTransferProgress
import dev.veyra.source.youtube.YouTubeTransferResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Authored typed-port regression fixtures ONLY. No live channel/search, source transfer, native
 * decoder, socket, private namespace or physical sound. These tests specifically prove that the
 * proof wrapper is a real AcquisitionGateway/AcquisitionExecutionGateway, so the application's
 * PolicyMediaGateway reaches the production delegate instead of failing an interface cast.
 */
class CentralListeningProofTypedTest {
    private val discoveredId = "ABCDEFGHIJK"
    private val selectedId = "LMNOPQRSTUV"
    private val title = "Authored typed complete title — عنوان كامل 😃"
    private val firstTitle = "Authored typed raw first title"
    private val representation = "yt-audio-v1:" + "a".repeat(64)
    private val item = MediaItem("youtube:$discoveredId", title, MediaKind.MUSIC,
        SourceRef(SourceProvider.YOUTUBE, discoveredId, CentralProofPolicy.watch(discoveredId)))
    private val request = AcquisitionRequest(AcquisitionRequestId("authored-request"), title, MediaCapture(item), 1_000)
    private val observation = FirstResultObservation(request.id, title,
        SourceEntry(AcquisitionEntryKind.VIDEO, selectedId, firstTitle,
            CentralProofPolicy.watch(selectedId), ordinal = 0),
        CentralProofPolicy.watch(selectedId))
    private val recording = SourceRecording(SourceRecordingId("authored-recording"), selectedId,
        CentralProofPolicy.watch(selectedId))
    private val subject = ExecutableSubject.CatalogMedia(ExecutableSubjectId(item.id), item.id)
    private val receipt = FirstResultReceipt.Observed(FirstResultReceiptId("authored-receipt"), observation, 1_000)
    private val binding = ExecutionBinding(ExecutionBindingId("authored-binding"), subject.id, receipt.id,
        recording.id, ExecutionBindingOrigin.TYPED)
    private val context = AcquisitionExecutionContext(request, receipt, binding, subject, recording)
    private val single = SourceAcquisitionResult.Single(request.id, observation,
        SourceDisplayItem(selectedId, firstTitle, CentralProofPolicy.watch(selectedId), AcquisitionEntryKind.VIDEO))

    private val audio = Representation("140", "https://media.example/audio?sig=authored-secret", TrackRole.AUDIO,
        mimeType = "audio/mp4", codecs = "mp4a.40.2", sizeBytes = 32, durationMs = 30_000)
    private val plan = PlannedDownload(audio, inspectionUrl = CentralProofPolicy.watch(selectedId), mediaId = selectedId)
    private val sabr = plan.copy(primary = audio.copy(transport = TransportKind.SABR,
        sabr = YouTubeSabrResource(selectedId, audio.url!!, "AQID", SabrFormatId(140, 1uL), SabrClientInfo(1, "authored"))))
    private val playbackRequest = SourcePlaybackRequest(
        SourcePlaybackMetadata(selectedId, firstTitle, dev.kmpyt.MediaKind.AUDIO, durationMs = 30_000), sabr)

    private class Resource : PlaybackResource {
        override val localEndpoint = EphemeralPlaybackEndpoint("http://127.0.0.1:32123/authored-typed-token/audio")
        var closes = 0
        override suspend fun close() {
            closes++
            currentCoroutineContext().ensureActive()
        }
    }

    private class TypedGateway : KmpMediaGateway, AcquisitionGateway, AcquisitionExecutionGateway {
        var acquireCalls = 0
        var preparePlaybackCalls = 0
        var prepareDownloadCalls = 0
        var transferCalls = 0
        var onAcquire: suspend (AcquisitionRequest) -> SourceAcquisitionResult = { error("Unplanned typed acquire") }
        var onPreparePlayback: suspend (AcquisitionExecutionContext, String?) -> AcquisitionPlaybackSource =
            { _, _ -> error("Unplanned typed playback") }
        var onPrepareDownload: suspend (AcquisitionExecutionContext, String?) -> PreparedAcquisitionDownload =
            { _, _ -> error("Unplanned typed download") }
        var onDownload: suspend (PreparedAcquisitionDownload,
            suspend (AcquisitionTransferProgress) -> Unit) -> AcquisitionTransferResult =
            { _, _ -> error("Unplanned typed transfer") }

        override suspend fun firstResult(completeTitle: String): KmpFirstResult? =
            error("Legacy search must not be used by the typed proof")
        override suspend fun resolve(binding: PlaybackBinding, capabilities: EngineCapabilities,
            purpose: ResolvePurpose) = error("Legacy resolve must not be used by the typed proof")
        override suspend fun download(binding: PlaybackBinding, destinationPath: String,
            capabilities: EngineCapabilities, representationId: String?,
            onProgress: suspend (YouTubeTransferProgress) -> Unit): YouTubeTransferResult =
            error("Legacy download must not be used by the typed proof")

        override suspend fun acquire(request: AcquisitionRequest): SourceAcquisitionResult {
            acquireCalls++
            return onAcquire(request)
        }

        override fun restore(request: AcquisitionRequest, receipt: FirstResultObservation): SourceAcquisitionResult =
            error("Authored typed fixtures do not restore")

        override suspend fun collection(request: AcquisitionRequest, selection: SourceAcquisitionResult.Collection,
            limits: CollectionLimits): SourceCollectionPage = error("No collection execution in this fixture")
        override suspend fun more(continuation: CollectionContinuation): SourceCollectionPage =
            error("No collection continuation in this fixture")
        override suspend fun inspect(request: AcquisitionRequest,
            selection: SourceAcquisitionResult.Single): AcquisitionInspection =
            error("No inspection in this fixture")

        override suspend fun preparePlayback(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
            requiredRepresentationId: String?, inspection: AcquisitionInspection?): AcquisitionPlaybackSource {
            preparePlaybackCalls++
            return onPreparePlayback(context, requiredRepresentationId)
        }

        override suspend fun prepareDownload(context: AcquisitionExecutionContext, capabilities: EngineCapabilities,
            requiredRepresentationId: String?, inspection: AcquisitionInspection?): PreparedAcquisitionDownload {
            prepareDownloadCalls++
            return onPrepareDownload(context, requiredRepresentationId)
        }

        override suspend fun download(prepared: PreparedAcquisitionDownload, destinationPath: String,
            onProgress: suspend (AcquisitionTransferProgress) -> Unit): AcquisitionTransferResult {
            transferCalls++
            return onDownload(prepared, onProgress)
        }
    }

    private class Prepared(
        override val context: AcquisitionExecutionContext,
        override val requiredRepresentationId: String?,
        override val representation: AcquisitionRepresentation,
    ) : PreparedAcquisitionDownload

    private class Hooks : PolicyMediaGatewayHooks {
        override suspend fun <T> online(block: suspend () -> T): T = block()
        override suspend fun prepareMediaRuntime() = Unit
        override fun checkOpen() = Unit
        override fun acquisitionStarted(completion: CompletableDeferred<Unit>) = Unit
        override fun acquisitionFinished(completion: CompletableDeferred<Unit>) = Unit
        override suspend fun releaseRejectedResource(resource: PlaybackResource) { resource.close() }
        override fun reportRejectedResourceFailure(failure: AppFailure) = Unit
    }

    private fun metadataJson(authorUrl: String = CentralProofPolicy.CHANNEL_URL) = buildJsonObject {
        put("title", firstTitle); put("author_name", "Authored channel")
        put("author_url", authorUrl); put("provider_name", "YouTube")
        put("provider_url", "https://www.youtube.com/"); put("type", "video")
    }.toString()

    private fun authorization(authorUrl: String = CentralProofPolicy.CHANNEL_URL): CentralProofAuthorization {
        val authorization = CentralProofAuthorization { id ->
            assertEquals(selectedId, id)
            CentralProofMetadata.parse(metadataJson(authorUrl))
        }
        authorization.discovered(item)
        return authorization
    }

    private fun policy(authorization: CentralProofAuthorization, delegate: TypedGateway) =
        PolicyMediaGateway(authorization.wrap(delegate), Hooks())

    private fun playbackSource(context: AcquisitionExecutionContext, resource: Resource?) = AcquisitionPlaybackSource(
        context = context, uri = resource?.localEndpoint?.uri ?: "https://media.example/audio?sig=authored-secret",
        transport = StreamTransport.PROGRESSIVE, mimeType = "audio/mp4", codecs = "mp4a.40.2",
        representationId = representation, resource = resource)

    @Test fun typedPolicyGatewayReachesTheDelegateAndAuthorizesTheActualRawFirst() = runTest {
        val delegate = TypedGateway().apply { onAcquire = { single } }
        val authorization = authorization()
        val wrapped = authorization.wrap(delegate)
        assertIs<AcquisitionGateway>(wrapped)
        assertIs<AcquisitionExecutionGateway>(wrapped)
        val gateway = PolicyMediaGateway(wrapped, Hooks())

        val result = gateway.acquire(request)

        assertIs<SourceAcquisitionResult.Single>(result)
        assertEquals(1, delegate.acquireCalls)
        assertEquals(1, authorization.snapshot().acquireCalls)
        assertEquals(1, authorization.snapshot().metadataChecks)
        assertEquals(selectedId, authorization.snapshot().typedVideoId)
        assertTrue(authorization.snapshot().approved)
        assertEquals(selectedId, authorization.snapshot().typedFirst?.entry?.id)
    }

    @Test fun typedPolicyGatewayRefusesAForeignFirstAuthorBeforeAnyPlayback() = runTest {
        val delegate = TypedGateway().apply { onAcquire = { single } }
        val authorization = authorization("https://www.youtube.com/@other")
        val gateway = policy(authorization, delegate)

        val result = gateway.acquire(request)

        assertIs<SourceAcquisitionResult.Unplayable>(result)
        assertEquals(1, delegate.acquireCalls)
        assertEquals(0, delegate.preparePlaybackCalls)
        assertEquals("FIRST_VIDEO_OUTSIDE_APPROVED_CHANNEL", authorization.failureOrNull()?.detail)
        assertFalse(authorization.snapshot().approved)
    }

    @Test fun typedPlaybackAndDownloadUseOnePinnedRepresentationAndBoundedPlan() = runTest {
        val delegate = TypedGateway().apply { onAcquire = { single } }
        val authorization = authorization()
        val gateway = policy(authorization, delegate)
        assertIs<SourceAcquisitionResult.Single>(gateway.acquire(request))

        val resource = Resource()
        delegate.onPreparePlayback = { selected, _ ->
            assertEquals(context, selected)
            authorization.authorizePlayback(playbackRequest)
            playbackSource(selected, resource)
        }
        val prepared = gateway.preparePlayback(context, EngineCapabilities(), null)
        assertSame(resource, prepared.resource)
        assertEquals(1, delegate.preparePlaybackCalls)
        assertEquals(1, authorization.snapshot().preparePlaybackCalls)
        assertEquals(representation, authorization.snapshot().playbackRepresentationId)

        delegate.onPrepareDownload = { selected, required ->
            assertEquals(context, selected)
            assertNull(required)
            Prepared(selected, required, AcquisitionRepresentation(representation, "audio/mp4", "mp4a.40.2", "m4a"))
        }
        val download = gateway.prepareDownload(context, EngineCapabilities(), null)
        assertEquals(1, delegate.prepareDownloadCalls)
        assertEquals(representation, authorization.snapshot().downloadRepresentationId)

        val reported = mutableListOf<Long>()
        delegate.onDownload = { preparedDownload, progress ->
            authorization.authorizeDownload(plan)
            progress(AcquisitionTransferProgress(0, 32))
            progress(AcquisitionTransferProgress(16, 32))
            progress(AcquisitionTransferProgress(32, 32))
            AcquisitionTransferResult(preparedDownload.context, 32, preparedDownload.representation)
        }
        val result = gateway.download(download, "authored-stage-only-no-file-created") { reported += it.bytesDownloaded }

        assertEquals(32L, result.bytesWritten)
        assertEquals(listOf(0L, 16L, 32L), reported)
        assertEquals(1, delegate.transferCalls)
        assertEquals(1, authorization.snapshot().transferCalls)
        assertEquals(1, authorization.snapshot().downloadCalls)
        assertEquals(1, authorization.snapshot().downloadPlans)
        assertEquals(32L, authorization.snapshot().transferBytes)
        assertFailsWith<VeyraException> { gateway.acquire(request) }
        assertEquals(1, delegate.acquireCalls)
    }

    @Test fun typedSecondPlaybackOrDownloadPreparationIsDeniedBeforeTheDelegate() = runTest {
        val delegate = TypedGateway().apply { onAcquire = { single } }
        val authorization = authorization()
        val gateway = policy(authorization, delegate)
        assertIs<SourceAcquisitionResult.Single>(gateway.acquire(request))
        delegate.onPreparePlayback = { selected, _ ->
            authorization.authorizePlayback(playbackRequest)
            playbackSource(selected, Resource())
        }
        gateway.preparePlayback(context, EngineCapabilities(), null)
        assertFailsWith<VeyraException> { gateway.preparePlayback(context, EngineCapabilities(), null) }
        assertEquals(1, delegate.preparePlaybackCalls)

        delegate.onPrepareDownload = { selected, required ->
            Prepared(selected, required, AcquisitionRepresentation(representation, "audio/mp4", "mp4a.40.2", "m4a"))
        }
        gateway.prepareDownload(context, EngineCapabilities(), null)
        assertFailsWith<VeyraException> { gateway.prepareDownload(context, EngineCapabilities(), null) }
        assertEquals(1, delegate.prepareDownloadCalls)
    }

    @Test fun typedChangedDurableIdentityIsRejectedBeforeTheDelegate() = runTest {
        val delegate = TypedGateway().apply { onAcquire = { single } }
        val authorization = authorization()
        val gateway = policy(authorization, delegate)
        assertIs<SourceAcquisitionResult.Single>(gateway.acquire(request))
        delegate.onPreparePlayback = { selected, _ ->
            authorization.authorizePlayback(playbackRequest)
            playbackSource(selected, Resource())
        }
        gateway.preparePlayback(context, EngineCapabilities(), null)
        val changed = context.copy(binding = binding.copy(id = ExecutionBindingId("authored-other-binding")))
        assertFailsWith<VeyraException> { gateway.preparePlayback(changed, EngineCapabilities(), null) }
        assertEquals(1, delegate.preparePlaybackCalls)
    }

    @Test fun typedUnboundedProgressiveSourceIsRefusedAndItsResourceClosed() = runTest {
        val delegate = TypedGateway().apply { onAcquire = { single } }
        val authorization = authorization()
        val gateway = policy(authorization, delegate)
        assertIs<SourceAcquisitionResult.Single>(gateway.acquire(request))
        val resource = Resource()
        delegate.onPreparePlayback = { selected, _ ->
            authorization.authorizePlayback(playbackRequest)
            AcquisitionPlaybackSource(selected, "https://media.example/not-owned", StreamTransport.PROGRESSIVE,
                "audio/mp4", "mp4a.40.2", representation, resource = resource)
        }
        val failure = assertFailsWith<VeyraException> {
            gateway.preparePlayback(context, EngineCapabilities(), null)
        }
        assertEquals("UNBOUNDED_NATIVE_PLAYBACK_REFUSED", failure.failure.detail)
        assertEquals(1, resource.closes)
        assertEquals(0, authorization.snapshot().rejectedResourcesPending)
        authorization.closeRejectedResources()
        assertEquals(1, resource.closes)
    }

    @Test fun typedDownloadPinsItsOwnRepresentationIndependentlyOfPlayback() = runTest {
        val delegate = TypedGateway().apply { onAcquire = { single } }
        val authorization = authorization()
        val gateway = policy(authorization, delegate)
        assertIs<SourceAcquisitionResult.Single>(gateway.acquire(request))
        delegate.onPreparePlayback = { selected, _ ->
            authorization.authorizePlayback(playbackRequest)
            playbackSource(selected, Resource())
        }
        gateway.preparePlayback(context, EngineCapabilities(), null)
        val downloadRepresentation = "yt-audio-v1:" + "b".repeat(64)
        delegate.onPrepareDownload = { selected, required ->
            Prepared(selected, required, AcquisitionRepresentation(downloadRepresentation, "audio/mp4", "mp4a.40.2", "m4a"))
        }
        val download = gateway.prepareDownload(context, EngineCapabilities(), null)
        assertEquals(downloadRepresentation, authorization.snapshot().downloadRepresentationId)
        delegate.onDownload = { prepared, progress ->
            authorization.authorizeDownload(plan)
            progress(AcquisitionTransferProgress(32, 32))
            AcquisitionTransferResult(prepared.context, 32, prepared.representation)
        }
        val result = gateway.download(download, "authored-stage", {})
        assertEquals(32L, result.bytesWritten)
        assertEquals(downloadRepresentation, result.representation.representationId)
    }

    @Test fun typedDownloadTransferCannotSwitchThePinnedRepresentation() = runTest {
        val delegate = TypedGateway().apply { onAcquire = { single } }
        val authorization = authorization()
        val wrapped = authorization.wrap(delegate)
        assertIs<SourceAcquisitionResult.Single>(wrapped.acquire(request))
        delegate.onPreparePlayback = { selected, _ ->
            authorization.authorizePlayback(playbackRequest)
            playbackSource(selected, Resource())
        }
        wrapped.preparePlayback(context, EngineCapabilities(), null)
        delegate.onPrepareDownload = { selected, required ->
            Prepared(selected, required, AcquisitionRepresentation(representation, "audio/mp4", "mp4a.40.2", "m4a"))
        }
        wrapped.prepareDownload(context, EngineCapabilities(), null)
        val other = "yt-audio-v1:" + "b".repeat(64)
        val forged = Prepared(context, null, AcquisitionRepresentation(other, "audio/mp4", "mp4a.40.2", "m4a"))
        val failure = assertFailsWith<VeyraException> { wrapped.download(forged, "authored-stage", {}) }
        assertEquals("DOWNLOAD_REPRESENTATION_CHANGED", failure.failure.detail)
        assertEquals(0, delegate.transferCalls)
    }

    @Test fun typedOversizedDownloadPlanIsRefusedBeforeCompletion() = runTest {
        val delegate = TypedGateway().apply { onAcquire = { single } }
        val authorization = authorization()
        val gateway = policy(authorization, delegate)
        assertIs<SourceAcquisitionResult.Single>(gateway.acquire(request))
        delegate.onPreparePlayback = { selected, _ ->
            authorization.authorizePlayback(playbackRequest)
            playbackSource(selected, Resource())
        }
        gateway.preparePlayback(context, EngineCapabilities(), null)
        delegate.onPrepareDownload = { selected, required ->
            Prepared(selected, required, AcquisitionRepresentation(representation, "audio/mp4", "mp4a.40.2", "m4a"))
        }
        val download = gateway.prepareDownload(context, EngineCapabilities(), null)
        delegate.onDownload = { _, _ ->
            authorization.authorizeDownload(plan.copy(primary = audio.copy(sizeBytes = CentralProofPolicy.MAX_BYTES + 1)))
            error("Oversized plan must not reach transfer")
        }
        val failure = assertFailsWith<VeyraException> {
            gateway.download(download, "authored-stage", {})
        }
        assertEquals("PLAN_HAS_NO_ENFORCEABLE_RETAINED_BYTE_BOUND", failure.failure.detail)
    }

    @Test fun disabledTypedAuthorizationStopsAcquisitionBeforeTheDelegate() = runTest {
        val delegate = TypedGateway().apply { onAcquire = { single } }
        val authorization = authorization()
        authorization.disable()
        val gateway = policy(authorization, delegate)
        val failure = assertFailsWith<VeyraException> { gateway.acquire(request) }
        assertEquals(FailureCode.PERMISSION_DENIED, failure.failure.code)
        assertEquals(0, delegate.acquireCalls)
    }

    @Test fun typedFirstReceiptMustRemainTheOrdinalZeroCompleteVideo() = runTest {
        val delegate = TypedGateway().apply {
            onAcquire = {
                SourceAcquisitionResult.Single(request.id, observation.copy(
                    entry = observation.entry.copy(ordinal = 1)), single.recording)
            }
        }
        val authorization = authorization()
        val gateway = policy(authorization, delegate)
        val failure = assertFailsWith<VeyraException> { gateway.acquire(request) }
        assertEquals("RAW_FIRST_RECEIPT_INVALID", failure.failure.detail)
        assertEquals(1, delegate.acquireCalls)
        assertFalse(authorization.snapshot().approved)
    }

    @Test fun typedEvidenceDoesNotSerializeSourceUrlsOrPlans() = runTest {
        val delegate = TypedGateway().apply { onAcquire = { single } }
        val authorization = authorization()
        val gateway = policy(authorization, delegate)
        assertIs<SourceAcquisitionResult.Single>(gateway.acquire(request))
        delegate.onPreparePlayback = { selected, _ ->
            authorization.authorizePlayback(playbackRequest)
            playbackSource(selected, Resource())
        }
        gateway.preparePlayback(context, EngineCapabilities(), null)
        val evidence = authorization.snapshot().json().toString()
        assertFalse(evidence.contains("authored-secret"))
        assertFalse(evidence.contains("media.example"))
        assertEquals(selectedId, Json.parseToJsonElement(evidence).jsonObject
            .getValue("typedRawFirst").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(firstTitle, Json.parseToJsonElement(evidence).jsonObject
            .getValue("typedRawFirst").jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals(YouTubeBrowseText.isCompleteTitle(firstTitle), true)
    }
}
