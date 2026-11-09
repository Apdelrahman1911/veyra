package dev.veyra.desktop.verification

import dev.kmpyt.extraction.PlannedDownload
import dev.kmpyt.extraction.Representation
import dev.kmpyt.extraction.SabrClientInfo
import dev.kmpyt.extraction.SabrFormatId
import dev.kmpyt.extraction.SegmentResource
import dev.kmpyt.extraction.SourcePlaybackMetadata
import dev.kmpyt.extraction.SourcePlaybackRequest
import dev.kmpyt.extraction.TrackRole
import dev.kmpyt.extraction.TransportKind
import dev.kmpyt.extraction.YouTubeSabrResource
import dev.kmpyt.youtube.YouTubeBrowseText
import dev.veyra.model.*
import dev.veyra.playback.EngineCapabilities
import dev.veyra.playback.EphemeralPlaybackEndpoint
import dev.veyra.playback.PlaybackResource
import dev.veyra.playback.ResolvedPlayable
import dev.veyra.source.ResolvePurpose
import dev.veyra.source.execution.KmpFirstResult
import dev.veyra.source.execution.KmpMediaGateway
import dev.veyra.source.network.KtorSafeHttp
import dev.veyra.source.network.PublicDnsResolver
import dev.veyra.source.youtube.YouTubeTransferProgress
import dev.veyra.source.youtube.YouTubeTransferResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Authored policy/metadata/file fixtures ONLY. No live channel requests, source transfers,
 * native decoder, sockets, private network namespace, physical sound, or end-to-end claim.
 */
class CentralListeningProofTest {
    private val discoveredId = "ABCDEFGHIJK"
    private val selectedId = "LMNOPQRSTUV"
    private val title = "Authored complete title — عنوان كامل 😃"
    private val representation = "yt-audio-v1:" + "a".repeat(64)
    private val item = MediaItem("youtube:$discoveredId", title, MediaKind.MUSIC,
        SourceRef(SourceProvider.YOUTUBE, discoveredId, CentralProofPolicy.watch(discoveredId)))
    private val first = KmpFirstResult(title, FirstResultKind.VIDEO, selectedId, "Different approved raw first title",
        CentralProofPolicy.watch(selectedId))
    private val binding = PlaybackBinding("authored-proof-receipt", item.id, title, first.kind, first.id,
        first.title, first.canonicalUrl, selectedVideoId = selectedId, resolvedEpochMs = 10_000)
    private val audio = Representation("140", "https://media.example/audio?sig=authored-secret", TrackRole.AUDIO,
        mimeType = "audio/mp4", codecs = "mp4a.40.2", sizeBytes = 32, durationMs = 30_000)
    private val plan = PlannedDownload(audio, inspectionUrl = CentralProofPolicy.watch(selectedId), mediaId = selectedId)
    private val sabr = plan.copy(primary = audio.copy(transport = TransportKind.SABR,
        sabr = YouTubeSabrResource(selectedId, audio.url!!, "AQID", SabrFormatId(140, 1uL), SabrClientInfo(1, "authored"))))
    private val request = SourcePlaybackRequest(SourcePlaybackMetadata(selectedId, first.title, dev.kmpyt.MediaKind.AUDIO,
        durationMs = 30_000), sabr)

    private class Gateway(var first: KmpFirstResult?) : KmpMediaGateway {
        val queries = mutableListOf<String>()
        val resolutions = mutableListOf<PlaybackBinding>()
        val downloads = mutableListOf<PlaybackBinding>()
        var onResolve: suspend (PlaybackBinding, ResolvePurpose) -> ResolvedPlayable = { _, _ -> error("Unplanned source execution") }
        var onDownload: suspend (suspend (YouTubeTransferProgress) -> Unit) -> YouTubeTransferResult = { error("Unplanned transfer") }
        override suspend fun firstResult(completeTitle: String): KmpFirstResult? { queries += completeTitle; return first }
        override suspend fun resolve(binding: PlaybackBinding, capabilities: EngineCapabilities, purpose: ResolvePurpose): ResolvedPlayable {
            resolutions += binding
            return onResolve(binding, purpose)
        }
        override suspend fun download(binding: PlaybackBinding, destinationPath: String, capabilities: EngineCapabilities,
            representationId: String?, onProgress: suspend (YouTubeTransferProgress) -> Unit): YouTubeTransferResult {
            downloads += binding
            return onDownload(onProgress)
        }
    }

    private class Resource(var failedClosesRemaining: Int = 0) : PlaybackResource {
        override val localEndpoint = EphemeralPlaybackEndpoint("http://127.0.0.1:32123/authored-proof-token/audio")
        var closes = 0
        override suspend fun close() {
            closes++
            currentCoroutineContext().ensureActive()
            if (failedClosesRemaining-- > 0) error("Authored failure https://media.example/close?sig=never-report-this")
        }
    }

    private fun resolved(value: PlaybackBinding, resource: Resource? = null) = ResolvedPlayable(
        value.mediaId, resource?.localEndpoint?.uri ?: "https://media.example/audio?sig=authored-secret",
        mimeType = "audio/mp4", codecs = "mp4a.40.2", durationMs = 30_000, seekableHint = true,
        representationId = representation, executionBinding = value, resource = resource,
    )

    private fun metadataJson(fullTitle: String = title, authorUrl: String = CentralProofPolicy.CHANNEL_URL,
        provider: String = "YouTube", type: String = "video") = buildJsonObject {
        put("title", fullTitle); put("author_name", "Authored channel")
        put("author_url", authorUrl); put("provider_name", provider)
        put("provider_url", "https://www.youtube.com/"); put("type", type)
    }.toString()

    private suspend fun approved(delegate: Gateway = Gateway(first), beforeNetwork: () -> Unit = {}):
        Pair<CentralProofAuthorization, KmpMediaGateway> {
        val authorization = CentralProofAuthorization(beforeNetwork) { id ->
            assertEquals(selectedId, id)
            CentralProofMetadata.parse(metadataJson())
        }
        authorization.discovered(item)
        val wrapped = authorization.wrap(delegate)
        assertEquals(first, wrapped.firstResult(title))
        return authorization to wrapped
    }

    @Test fun approvedScopeAndNativeCacheCapMatchThePinnedRepositoryAuthorization() {
        val scope = Json.parseToJsonElement(Files.readString(repository().resolve("third_party/kmpYT/ci/approved-channel.json"))).jsonObject
        assertEquals(scope.getValue("channelUrl").jsonPrimitive.content, CentralProofPolicy.CHANNEL_URL)
        assertEquals(scope.getValue("maximumVideosPerRun").jsonPrimitive.int, CentralProofPolicy.MAX_VIDEOS)
        assertEquals(scope.getValue("maximumDownloadBytesPerVideo").jsonPrimitive.long, CentralProofPolicy.MAX_BYTES)
        CentralListeningProof(silentAudio = true).use { proof ->
            assertEquals(CentralProofPolicy.MAX_BYTES, proof.executionPolicy().nativePlaybackLimits.maxCacheBytes)
        }
    }

    @Test fun oembedRetainsEveryUtf16UnitOfACompleteBilingualTitle() {
        val prefix = "العنوان الموسيقي الكامل 😃 — Complete title "
        val full = prefix + "x".repeat(YouTubeBrowseText.MAX_TITLE_LENGTH - prefix.length)
        assertEquals(YouTubeBrowseText.MAX_TITLE_LENGTH, full.length)
        assertEquals(full, CentralProofMetadata.parse(metadataJson(full)).title)
        assertFailsWith<VeyraException> { CentralProofMetadata.parse(metadataJson(full + "x")) }
        for (invalid in listOf("", " \t", "line\nbreak", "lone-surrogate-\uD800")) {
            assertFailsWith<VeyraException> { CentralProofMetadata.parse(metadataJson(invalid)) }
        }
    }

    @Test fun authorVerificationDoesNotConfuseAFeaturedVideoOrSimilarUrlWithApproval() {
        assertTrue(CentralProofPolicy.isApprovedAuthor("https://www.youtube.com/@OPIILZ"))
        for (url in listOf(
            "https://www.youtube.com/@other", "https://www.youtube.com/channel/UC" + "a".repeat(22),
            "https://youtube.com/@oPiiLz", "http://www.youtube.com/@oPiiLz",
            "https://www.youtube.com:443/@oPiiLz", "https://www.youtube.com/@oPiiLz/",
            "https://www.youtube.com/@oPiiLz/videos", "https://www.youtube.com/@oPiiLz?approved=yes",
            "https://www.youtube.com/@oPiiLz#channel", "https://www.youtube.com@evil.example/@oPiiLz",
            "https://www.youtube.com.evil.example/@oPiiLz", "https://www.youtube.com./@oPiiLz",
            "https://www.youtube.com/%40oPiiLz", "https://user@www.youtube.com/@oPiiLz",
        )) {
            assertFalse(CentralProofPolicy.isApprovedAuthor(url))
            assertEquals(FailureCode.PERMISSION_DENIED,
                assertFailsWith<VeyraException> { CentralProofMetadata.parse(metadataJson(authorUrl = url)) }.failure.code)
        }
        assertFailsWith<VeyraException> { CentralProofMetadata.parse(metadataJson(provider = "Authored impostor")) }
        assertFailsWith<VeyraException> { CentralProofMetadata.parse(metadataJson(type = "link")) }
    }

    @Test fun oembedReaderUsesOnlyExactPublicMetadataForTheRequestedVideo() = runTest {
        var calls = 0
        var before = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            assertEquals("GET", request.method.value)
            assertEquals("https", request.url.protocol.name)
            assertEquals("www.youtube.com", request.url.host)
            assertEquals("/oembed", request.url.encodedPath)
            assertEquals(setOf("url", "format"), request.url.parameters.names())
            assertEquals(listOf(CentralProofPolicy.watch(selectedId)), request.url.parameters.getAll("url"))
            assertEquals(listOf("json"), request.url.parameters.getAll("format"))
            assertFalse(request.headers.contains("Cookie")); assertFalse(request.headers.contains("Authorization"))
            respond(metadataJson(), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }) { expectSuccess = false; followRedirects = false }
        val reader = CentralProofMetadata(KtorSafeHttp(client, PublicDnsResolver { listOf("93.184.216.34") }),
            beforeRequest = { before++ })
        try {
            assertEquals(title, reader.video(selectedId).title)
            assertEquals(1, calls)
            assertTrue(before >= 3)
        } finally { reader.close(); reader.close() }
        assertFailsWith<VeyraException> { reader.video(selectedId) }
        assertEquals(1, calls)
    }

    @Test fun metadataByteLimitAndPolicyDenialCannotBecomeAuthorApproval() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond("x".repeat(256 * 1024 + 1), HttpStatusCode.OK)
        }) { expectSuccess = false; followRedirects = false }
        var blocked = true
        val reader = CentralProofMetadata(KtorSafeHttp(client, PublicDnsResolver { listOf("93.184.216.34") }), beforeRequest = {
            if (blocked) throw VeyraException(AppFailure(FailureCode.OFFLINE_POLICY))
        })
        try {
            assertEquals(FailureCode.OFFLINE_POLICY, assertFailsWith<VeyraException> { reader.video(selectedId) }.failure.code)
            assertEquals(0, calls)
            blocked = false
            assertEquals(FailureCode.PARSE_FAILURE, assertFailsWith<VeyraException> { reader.video(selectedId) }.failure.code)
            assertEquals(1, calls)
        } finally { reader.close() }
    }

    @Test fun actualFirstResultIsAuthorizedEvenWhenItDiffersFromDiscoveryWithoutAnotherSearch() = runTest {
        val delegate = Gateway(first)
        val (authorization, wrapped) = approved(delegate)
        assertNotEquals(discoveredId, first.id)
        authorization.requireBinding(binding)
        assertEquals(listOf(title), delegate.queries)
        assertEquals(1, authorization.snapshot().metadataChecks)
        assertTrue(authorization.snapshot().approved)
        assertFailsWith<VeyraException> { wrapped.firstResult(title) }
        assertEquals(listOf(title), delegate.queries)
    }

    @Test fun unsupportedAndUnavailableRawFirstCardsAreNeverReplacedOrInspected() = runTest {
        val cases = listOf(
            first.copy(kind = FirstResultKind.CHANNEL, id = "UC" + "a".repeat(22), canonicalUrl = null),
            first.copy(kind = FirstResultKind.PLAYLIST, id = "authored-playlist", canonicalUrl = null),
            first.copy(kind = FirstResultKind.UNKNOWN, id = "", title = "", canonicalUrl = null),
            first.copy(failure = AppFailure(FailureCode.UNSUPPORTED_SOURCE, "YOUTUBE_FIRST_RESULT_UNAVAILABLE")),
            null,
        )
        for (raw in cases) {
            val delegate = Gateway(raw)
            val authorization = CentralProofAuthorization { error("Nonplayable raw card must not request video metadata") }
            authorization.discovered(item)
            val wrapped = authorization.wrap(delegate)
            assertSame(raw, wrapped.firstResult(title))
            assertEquals(listOf(title), delegate.queries)
            assertFalse(authorization.snapshot().approved)
            assertFailsWith<VeyraException> { wrapped.resolve(binding, EngineCapabilities(), ResolvePurpose.PLAYBACK) }
            assertTrue(delegate.resolutions.isEmpty())
        }
    }

    @Test fun refusedRawFirstRetainsItsIdentityAndFailureForDurableResolverReceipt() = runTest {
        val delegate = Gateway(first)
        val authorization = CentralProofAuthorization { CentralProofMetadata.parse(metadataJson(authorUrl = "https://www.youtube.com/@other")) }
        authorization.discovered(item)
        val wrapped = authorization.wrap(delegate)
        val refused = assertNotNull(wrapped.firstResult(title))
        assertEquals(first, refused.copy(failure = null))
        assertEquals(FailureCode.PERMISSION_DENIED, refused.failure?.code)
        assertEquals("FIRST_VIDEO_OUTSIDE_APPROVED_CHANNEL", refused.failure?.detail)
        assertEquals(listOf(title), delegate.queries)
        assertFailsWith<VeyraException> { wrapped.resolve(binding, EngineCapabilities(), ResolvePurpose.PLAYBACK) }
        assertTrue(delegate.resolutions.isEmpty())
        assertFalse(authorization.snapshot().approved)
    }

    @Test fun cancelledFirstResultAuthorizationPropagatesInsteadOfInventingAReceipt() = runTest {
        val cancelled = CancellationException("authored cancellation")
        val delegate = Gateway(first)
        val authorization = CentralProofAuthorization { throw cancelled }
        authorization.discovered(item)
        assertSame(cancelled, assertFailsWith<CancellationException> { authorization.wrap(delegate).firstResult(title) })
        assertFalse(authorization.snapshot().approved)
        assertEquals(listOf(title), delegate.queries)
    }

    @Test fun changedQueryOrBindingCannotReachActualExecution() = runTest {
        val delegate = Gateway(first)
        val (authorization, wrapped) = approved(delegate)
        authorization.requireBinding(binding)
        for (changed in listOf(
            binding.copy(id = "another-receipt"), binding.copy(mediaId = "another-discovery"),
            binding.copy(queryTitle = title + " truncated"), binding.copy(firstResultKind = FirstResultKind.PLAYLIST),
            binding.copy(firstResultId = discoveredId), binding.copy(firstResultTitle = first.title + " corrected"),
            binding.copy(firstResultUrl = CentralProofPolicy.watch(discoveredId)),
            binding.copy(selectedVideoId = discoveredId), binding.copy(selectionOrdinal = 1),
            binding.copy(resolvedEpochMs = binding.resolvedEpochMs + 1),
        )) assertFailsWith<VeyraException> { wrapped.resolve(changed, EngineCapabilities(), ResolvePurpose.PLAYBACK) }
        assertTrue(delegate.resolutions.isEmpty())

        val queryDelegate = Gateway(first)
        val queryAuthorization = CentralProofAuthorization { error("Wrong query must be rejected before metadata") }
        queryAuthorization.discovered(item)
        assertFailsWith<VeyraException> { queryAuthorization.wrap(queryDelegate).firstResult(title.take(10)) }
        assertTrue(queryDelegate.queries.isEmpty())
    }

    @Test fun bytePolicyAcceptsOnlyKnownBoundedAnonymousStandaloneAudioOfTheAuthorizedVideo() {
        assertEquals(32L, CentralProofPolicy.requirePlan(plan, selectedId))
        assertEquals(32L, CentralProofPolicy.requirePlan(sabr, selectedId))
        assertEquals(CentralProofPolicy.MAX_BYTES,
            CentralProofPolicy.requirePlan(plan.copy(primary = audio.copy(sizeBytes = CentralProofPolicy.MAX_BYTES)), selectedId))
        for (bad in listOf(
            plan.copy(mediaId = discoveredId), plan.copy(inspectionUrl = CentralProofPolicy.watch(discoveredId)),
            plan.copy(audio = audio), plan.copy(requestHeaders = mapOf("Accept" to "audio/*")),
            plan.copy(credentialRef = "authored-credential"), plan.copy(authorizedOrigin = "https://private.example"),
            plan.copy(primary = audio.copy(role = TrackRole.MUXED)), plan.copy(primary = audio.copy(role = TrackRole.VIDEO)),
            plan.copy(primary = audio.copy(sizeBytes = null)), plan.copy(primary = audio.copy(sizeBytes = 0)),
            plan.copy(primary = audio.copy(sizeBytes = -1)), plan.copy(primary = audio.copy(sizeBytes = CentralProofPolicy.MAX_BYTES + 1)),
            plan.copy(primary = audio.copy(transport = TransportKind.HLS)), plan.copy(primary = audio.copy(transport = TransportKind.DASH)),
            plan.copy(primary = audio.copy(segments = listOf(SegmentResource("https://media.example/chunk")))),
            plan.copy(primary = audio.copy(initialization = SegmentResource("https://media.example/init"))),
            plan.copy(primary = audio.copy(url = "https://127.0.0.1/audio")),
            plan.copy(primary = audio.copy(url = "https://user:password@media.example/audio")),
            sabr.copy(primary = sabr.primary.copy(sabr = sabr.primary.sabr!!.copy(videoId = discoveredId))),
        )) assertFailsWith<VeyraException> { CentralProofPolicy.requirePlan(bad, selectedId) }
    }

    @Test fun bareNativeProgressivePlaybackIsRefusedRatherThanGuessingAnUnknownBound() = runTest {
        val delegate = Gateway(first)
        val (_, wrapped) = approved(delegate)
        delegate.onResolve = { value, purpose -> assertEquals(ResolvePurpose.PLAYBACK, purpose); resolved(value) }
        val failed = assertFailsWith<VeyraException> { wrapped.resolve(binding, EngineCapabilities(), ResolvePurpose.PLAYBACK) }
        assertEquals("UNBOUNDED_NATIVE_PLAYBACK_REFUSED", failed.failure.detail)
        assertEquals(listOf(binding), delegate.resolutions)
        assertEquals(listOf(title), delegate.queries)
    }

    @Test fun boundedOwnedPlaybackRequiresTheInstalledHookAndClosesRejectedHandoffs() = runTest {
        val delegate = Gateway(first)
        val (authorization, wrapped) = approved(delegate)
        val owned = Resource()
        delegate.onResolve = { value, _ ->
            authorization.authorizePlayback(request)
            resolved(value, owned)
        }
        val result = wrapped.resolve(binding, EngineCapabilities(), ResolvePurpose.PLAYBACK)
        assertSame(owned, result.resource)
        assertEquals(owned.localEndpoint.uri, result.ownedLoopbackUriOrNull())
        assertEquals(0, owned.closes)
        authorization.authorizePlayback(request) // Per-resource reauthorization of the same source-owned request.
        assertFailsWith<VeyraException> { authorization.authorizePlayback(request.copy()) }
        result.resource!!.close()
        assertEquals(1, owned.closes)
        authorization.closeRejectedResources()
        authorization.closeRejectedResources()
        assertEquals(1, owned.closes) // Accepted handoff belongs to the native engine, not this registry.

        val unhookedDelegate = Gateway(first)
        val (_, unhooked) = approved(unhookedDelegate)
        val rejected = Resource()
        unhookedDelegate.onResolve = { value, _ -> resolved(value, rejected) }
        assertFailsWith<VeyraException> { unhooked.resolve(binding, EngineCapabilities(), ResolvePurpose.PLAYBACK) }
        assertEquals(1, rejected.closes)
    }

    @Test fun rejectedResourceCloseFailureStaysOwnedAndIsStickyEvenWhenFinalDrainSucceeds() = runTest {
        val delegate = Gateway(first)
        val (authorization, wrapped) = approved(delegate)
        val resource = Resource(failedClosesRemaining = 1)
        delegate.onResolve = { value, _ -> resolved(value, resource) } // No native policy hook: must reject.
        val primary = assertFailsWith<VeyraException> { wrapped.resolve(binding, EngineCapabilities(), ResolvePurpose.PLAYBACK) }
        assertEquals("UNBOUNDED_NATIVE_PLAYBACK_REFUSED", primary.failure.detail)
        assertEquals(1, resource.closes)
        assertEquals(1, authorization.snapshot().rejectedResourcesPending)
        assertTrue(authorization.snapshot().rejectedResourceCloseFailed)
        assertEquals("REJECTED_RESOURCE_CLOSE_FAILED",
            assertFailsWith<VeyraException> { authorization.requireBinding(binding) }.failure.detail)
        val evidence = authorization.snapshot().json().toString()
        assertFalse(evidence.contains("never-report-this")); assertFalse(evidence.contains("media.example"))

        assertEquals("REJECTED_RESOURCE_CLOSE_FAILED",
            assertFailsWith<VeyraException> { authorization.closeRejectedResources() }.failure.detail)
        assertEquals(2, resource.closes)
        assertEquals(0, authorization.snapshot().rejectedResourcesPending)
        assertTrue(authorization.snapshot().rejectedResourceCloseFailed)
        assertFailsWith<VeyraException> { authorization.closeRejectedResources() }
        assertEquals(2, resource.closes) // Successfully drained resources are not closed again.
    }

    @Test fun repeatedlyFailingRejectedResourceIsNeverForgottenOrReportedDrained() = runTest {
        val delegate = Gateway(first)
        val (authorization, wrapped) = approved(delegate)
        val resource = Resource(failedClosesRemaining = 3)
        delegate.onResolve = { value, _ -> resolved(value, resource) }
        assertFailsWith<VeyraException> { wrapped.resolve(binding, EngineCapabilities(), ResolvePurpose.PLAYBACK) }
        repeat(2) {
            assertFailsWith<VeyraException> { authorization.closeRejectedResources() }
            assertEquals(1, authorization.snapshot().rejectedResourcesPending)
            assertTrue(authorization.snapshot().rejectedResourceCloseFailed)
        }
        assertEquals(3, resource.closes)
        assertFailsWith<VeyraException> { authorization.closeRejectedResources() }
        assertEquals(4, resource.closes)
        assertEquals(0, authorization.snapshot().rejectedResourcesPending)
    }

    @Test fun cancellingAResolutionAfterAcquisitionStillClosesItsUnhandedResource() = runTest {
        val delegate = Gateway(first)
        val (authorization, wrapped) = approved(delegate)
        val resource = Resource()
        delegate.onResolve = { value, _ ->
            currentCoroutineContext().cancel(CancellationException("Authored late handoff cancellation"))
            resolved(value, resource)
        }
        val job = launch { wrapped.resolve(binding, EngineCapabilities(), ResolvePurpose.PLAYBACK) }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, resource.closes)
        assertEquals(0, authorization.snapshot().resolvesInFlight)
        assertEquals(0, authorization.snapshot().rejectedResourcesPending)
        assertFalse(authorization.snapshot().rejectedResourceCloseFailed)
        authorization.closeRejectedResources()
    }

    @Test fun finalDrainCannotClaimSuccessWhileAResolutionHasNotReturnedItsResource() = runTest {
        val delegate = Gateway(first)
        val (authorization, wrapped) = approved(delegate)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resource = Resource()
        delegate.onResolve = { value, _ -> entered.complete(Unit); release.await(); resolved(value, resource) }
        val job = launch {
            assertFailsWith<VeyraException> { wrapped.resolve(binding, EngineCapabilities(), ResolvePurpose.DOWNLOAD) }
        }
        entered.await()
        assertEquals(1, authorization.snapshot().resolvesInFlight)
        assertFailsWith<VeyraException> { authorization.closeRejectedResources() }
        release.complete(Unit)
        job.join()
        assertEquals(1, resource.closes)
        assertEquals(0, authorization.snapshot().resolvesInFlight)
        assertEquals(0, authorization.snapshot().rejectedResourcesPending)
        assertTrue(authorization.snapshot().rejectedResourceCloseFailed)
        assertFailsWith<VeyraException> { authorization.closeRejectedResources() }
    }

    @Test fun changedResolvedReceiptClosesItsUnhandedResourceWithoutAlternativeSearch() = runTest {
        val delegate = Gateway(first)
        val (authorization, wrapped) = approved(delegate)
        val owned = Resource()
        delegate.onResolve = { value, _ ->
            authorization.authorizePlayback(request)
            resolved(value.copy(mediaId = "other-item"), owned)
        }
        assertFailsWith<VeyraException> { wrapped.resolve(binding, EngineCapabilities(), ResolvePurpose.PLAYBACK) }
        assertEquals(1, owned.closes)
        assertEquals(listOf(title), delegate.queries)
    }

    @Test fun downloadReusesTheReceiptWithEnforcedProgressAndCompletionBounds() = runTest {
        val delegate = Gateway(first)
        val (authorization, wrapped) = approved(delegate)
        delegate.onDownload = { progress ->
            authorization.authorizeDownload(plan)
            progress(YouTubeTransferProgress(0, 32))
            progress(YouTubeTransferProgress(16, 32))
            progress(YouTubeTransferProgress(32, 32))
            YouTubeTransferResult(32, "audio/mp4", "mp4a.40.2", "m4a", representation)
        }
        val reported = mutableListOf<Long>()
        val result = wrapped.download(binding, "authored-stage-only-no-file-created", EngineCapabilities(), representation) {
            reported += it.bytesDownloaded
        }
        assertEquals(32L, result.bytesWritten)
        assertEquals(listOf(0L, 16L, 32L), reported)
        assertEquals(listOf(title), delegate.queries)
        assertEquals(listOf(binding), delegate.downloads)
        assertEquals(1, authorization.snapshot().downloadPlans)
        assertEquals(32L, authorization.snapshot().transferBytes)
        assertFailsWith<VeyraException> { wrapped.download(binding, "never-second-stage", EngineCapabilities(), representation) {} }
        assertEquals(1, delegate.downloads.size)
    }

    @Test fun missingDownloadHookOrOversizedCountsNeverReturnCompletion() = runTest {
        for (progress in listOf(YouTubeTransferProgress(-1, 32), YouTubeTransferProgress(33, 32),
            YouTubeTransferProgress(0, null), YouTubeTransferProgress(1, 33))) {
            val delegate = Gateway(first)
            val (authorization, wrapped) = approved(delegate)
            delegate.onDownload = { report ->
                authorization.authorizeDownload(plan)
                report(progress)
                error("Invalid counts must stop before completion")
            }
            assertFailsWith<VeyraException> { wrapped.download(binding, "authored-stage", EngineCapabilities(), representation) {} }
        }
        val delegate = Gateway(first)
        val (_, wrapped) = approved(delegate)
        delegate.onDownload = { YouTubeTransferResult(32, "audio/mp4", "mp4a.40.2", "m4a", representation) }
        assertFailsWith<VeyraException> { wrapped.download(binding, "authored-stage", EngineCapabilities(), representation) {} }
    }

    @Test fun disabledOrOfflinePolicyStopsLaterNativeRequestsAndDownloads() = runTest {
        var offline = false
        val delegate = Gateway(first)
        val (authorization, wrapped) = approved(delegate) {
            if (offline) throw VeyraException(AppFailure(FailureCode.OFFLINE_POLICY))
        }
        authorization.requireBinding(binding)
        offline = true
        assertEquals(FailureCode.OFFLINE_POLICY,
            assertFailsWith<VeyraException> { authorization.authorizePlayback(request) }.failure.code)
        assertEquals(FailureCode.OFFLINE_POLICY,
            assertFailsWith<VeyraException> { wrapped.download(binding, "never-stage", EngineCapabilities(), representation) {} }.failure.code)
        assertTrue(delegate.downloads.isEmpty())
        offline = false
        authorization.disable()
        assertEquals(FailureCode.PERMISSION_DENIED, assertFailsWith<VeyraException> { authorization.requireBinding(binding) }.failure.code)
    }

    @Test fun authorizationEvidenceNeverSerializesPlanSourceUrlsOrPrivatePaths() = runTest {
        val delegate = Gateway(first)
        val (authorization, wrapped) = approved(delegate)
        delegate.onDownload = {
            authorization.authorizeDownload(plan)
            YouTubeTransferResult(32, "audio/mp4", "mp4a.40.2", "m4a", representation)
        }
        wrapped.download(binding, "authored-private-stage-path", EngineCapabilities(), representation) {}
        val evidence = authorization.snapshot().json().toString()
        assertFalse(evidence.contains("authored-secret"))
        assertFalse(evidence.contains("media.example"))
        assertFalse(evidence.contains("authored-private-stage"))
        assertEquals(title, Json.parseToJsonElement(evidence).jsonObject.getValue("completeTitle").jsonPrimitive.content)
    }

    @Test fun sameProcessReopenAndStillAlivePredecessorAreNotRestartEvidence() {
        val current = CentralProcessEvidence.current()
        assertEquals("SAME_PROCESS_REOPEN_IS_NOT_RESTART", assertFailsWith<VeyraException> {
            CentralProofPolicy.requireTerminatedPredecessor(current, current)
        }.failure.detail)
        // The live current PID is the claimed predecessor; fake successor metadata cannot make it terminated.
        assertEquals("ONLINE_PROCESS_HAS_NOT_TERMINATED", assertFailsWith<VeyraException> {
            CentralProofPolicy.requireTerminatedPredecessor(current, current.copy(pid = current.pid + 10_000))
        }.failure.detail)
    }

    private fun repository(): Path = System.getenv("VEYRA_PROJECT_ROOT")?.let(Path::of)
            ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

    private fun workDirectory(): Path {
        val parent = repository().resolve(".work/central-proof-policy-tests")
        Files.createDirectories(parent)
        return Files.createTempDirectory(parent, "case-")
    }

    private fun marker() = CentralProofMarker(
        process = CentralProcessEvidence.current(), mediaId = item.id, completeTitle = title, discoveryVideoId = discoveredId,
        binding = binding, downloadId = "authored-download", assetId = "authored-asset", sizeBytes = 32,
        sha256 = "b".repeat(64), representationId = representation, measuredDurationMs = 30_000,
        entryId = "authored-queue-entry", positionMs = 1_400,
        native = CentralNativeEvidence(generation = 1, entryId = "authored-queue-entry", durationMs = 30_000,
            firstPlayingPositionMs = 600, advancingPositionMs = 2_650, pausedPositionMs = 2_700, resumedPositionMs = 3_250,
            forwardTargetMs = 15_000, forwardConfirmedMs = 15_010, forwardAdvancingPositionMs = 15_550,
            backwardConfirmedMs = 750, backwardAdvancingPositionMs = 1_350, finalPausedPositionMs = 1_400,
            silentAudio = true, mpvVersion = "authored-not-a-runtime", ffmpegVersion = "authored-not-a-runtime", audioOutput = "null"),
    )

    @Test fun markerIsBoundedAtomicOwnedMetadataAndCannotOverwriteAnExistingResult() = runTest {
        val directory = workDirectory()
        val files = CentralProofFiles(directory)
        val authored = marker()
        files.requireNoMarker()
        files.writeNew(authored)
        assertEquals(authored, files.read())
        assertFailsWith<VeyraException> { files.writeNew(authored.copy(positionMs = 1_450)) }
        assertEquals(authored, files.read())
        val content = Files.readString(directory.resolve("verification/central-listening-online.json"))
        assertFalse(content.contains("media.example")); assertFalse(content.contains("authored-secret"))
        Files.list(directory.resolve("verification")).use { stream -> assertEquals(1L, stream.count()) }
        assertFailsWith<VeyraException> { authored.copy(representationId = "").requireValid() }
        assertFailsWith<VeyraException> { authored.copy(binding = binding.copy(selectionOrdinal = 1)).requireValid() }
        assertFailsWith<VeyraException> { authored.copy(native = authored.native.copy(physicalAudioVerified = true)).requireValid() }
    }

    @Test fun proofMarkerRefusesSymlinkAndOversizedInputsWithoutFollowingThem() = runTest {
        val directory = workDirectory()
        val parent = directory.resolve("verification")
        Files.createDirectories(parent)
        val marker = parent.resolve("central-listening-online.json")
        Files.writeString(marker, "x".repeat(CentralProofFiles.MAX_BYTES + 1))
        assertFailsWith<VeyraException> { CentralProofFiles(directory).read() }
        Files.delete(marker)
        if (!System.getProperty("os.name").lowercase().startsWith("windows")) {
            val outside = directory.resolve("authored-unrelated-file")
            Files.writeString(outside, "authored sentinel")
            Files.createSymbolicLink(marker, outside)
            assertFailsWith<IllegalArgumentException> { CentralProofFiles(directory).read() }
            assertEquals("authored sentinel", Files.readString(outside))
        }
    }
}
