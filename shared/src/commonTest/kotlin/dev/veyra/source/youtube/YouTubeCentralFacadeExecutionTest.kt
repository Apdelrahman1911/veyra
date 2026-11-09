package dev.veyra.source.youtube

import dev.kmpyt.YouTubeDownloadResult
import dev.kmpyt.YouTubeDownloader
import dev.kmpyt.YouTubePlaybackAdapter
import dev.kmpyt.YouTubePlaybackEndpoint
import dev.kmpyt.YouTubePlaybackResource
import dev.kmpyt.extraction.ResearchResponse
import dev.kmpyt.extraction.TrackRole
import dev.kmpyt.extraction.TransportKind
import dev.veyra.model.*
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.execution.*
import dev.veyra.source.youtube.AcquisitionExecutionFixtures as F
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** New facade forwarding only; authored adapters exercise existing source primitives, not native I/O. */
class YouTubeCentralFacadeExecutionTest {
    private val engine = EngineCapabilities()
    private class ExpiredResourceException : Exception("Authored expired-link outcome")

    @Test fun oneStableFacadeIssuerAcceptsItsOwnTypedPreparationButRejectsOtherFacadesAndForgedGetters() = runTest {
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { _, path, _ ->
            transfers++; YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        val foreign = AcquisitionExecutionFixture(handler = { _, _ -> error("Foreign capability cannot enter transport") })
        try {
            val supplied: KmpMediaGateway = YouTubeKmpMediaGateway(fixture.source)
            val owner = assertIs<AcquisitionExecutionGateway>(supplied)
            val anotherFacade = YouTubeKmpMediaGateway(fixture.source)
            val anotherClient = YouTubeKmpMediaGateway(foreign.source)
            val context = F.context()
            val prepared = owner.prepareDownload(context, engine)
            for (other in listOf<AcquisitionExecutionGateway>(anotherFacade, anotherClient, fixture.execution)) {
                assertDetail("YOUTUBE_PREPARED_DOWNLOAD_MISMATCH") { other.download(prepared, "never-used-stage.m4a") }
            }
            val forged = object : PreparedAcquisitionDownload {
                override val context: AcquisitionExecutionContext get() = error("Do not read forged context")
                override val requiredRepresentationId: String? get() = error("Do not read forged key")
                override val representation: AcquisitionRepresentation get() = error("Do not read forged representation")
            }
            assertDetail("YOUTUBE_PREPARED_DOWNLOAD_MISMATCH") { owner.download(forged, "never-used-stage.m4a") }
            assertEquals(0, transfers)
            assertTrue(foreign.requests.isEmpty())
            assertEquals(1, fixture.watches)
            val result = owner.download(prepared, "authored-central-stage.m4a")
            assertSame(context, result.context)
            assertEquals(prepared.representation, result.representation)
            assertEquals(32L, result.bytesWritten)
            assertEquals(1, transfers)
            assertEquals(1, fixture.watches)
            assertDetail("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED") { owner.download(prepared, "another-stage.m4a") }
        } finally { fixture.close(); foreign.close() }
    }

    @Test fun typedAndLegacyPreparedOverloadsKeepTheirSeparateExactReceiptsAndSharedRepresentation() = runTest {
        val paths = mutableListOf<String>()
        val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { plan, path, _ ->
            assertEquals(F.VIDEO_C, plan.mediaId)
            paths += path
            YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val supplied: KmpMediaGateway = YouTubeKmpMediaGateway(fixture.source)
            val typed = assertIs<AcquisitionExecutionGateway>(supplied)
            val legacy = assertIs<LegacyPreparedDownloadGateway>(supplied)
            val context = F.context(F.request(AcquisitionKind.SHOW))
            val original = legacyBinding()
            val typedPrepared = typed.prepareDownload(context, engine)
            val legacyPrepared = legacy.prepareDownload(original, engine, typedPrepared.representation.representationId)
            assertSame(context, typedPrepared.context)
            assertSame(original, legacyPrepared.binding)
            assertEquals(typedPrepared.representation, legacyPrepared.representation)
            assertEquals(typedPrepared.representation.representationId, legacyPrepared.requiredRepresentationId)
            val before = fixture.requests.toList()
            val legacyResult = legacy.download(legacyPrepared, "authored-legacy-stage.m4a")
            val typedResult = typed.download(typedPrepared, "authored-typed-stage.m4a")
            assertSame(original, legacyResult.binding)
            assertSame(context, typedResult.context)
            assertEquals(F.TITLE, legacyResult.binding.queryTitle)
            assertEquals(F.RETURNED_TITLE, legacyResult.binding.firstResultTitle)
            assertIs<ExecutableSubject.SourceRecordingSubject>(typedResult.context.subject)
            assertEquals(legacyResult.representation, typedResult.representation)
            assertEquals(listOf("authored-legacy-stage.m4a", "authored-typed-stage.m4a"), paths)
            assertEquals(before, fixture.requests)
            assertEquals(listOf(F.WATCH, F.WATCH), fixture.requests.map { it.url })
        } finally { fixture.close() }
    }

    @Test fun aSavedNondefaultRepresentationKeyIsForwardedAtPreparationWithoutDefaultReselection() = runTest {
        // Recreate an authored source and carry its saved key; not a real process/file restart.
        val seed = AcquisitionExecutionFixture(handler = { request, _ -> ResearchResponse(request.url, 200,
            F.watch(listOf(F.format(language = "ar")))) })
        val context = F.context()
        val savedKey = try {
            YouTubeKmpMediaGateway(seed.source).prepareDownload(context, engine).representation.representationId
        } finally { seed.close() }
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(handler = { request, _ -> ResearchResponse(request.url, 200, F.watch(listOf(
            F.format(language = "en", default = true), F.format(language = "ar", default = false),
        ))) }, downloader = YouTubeDownloader { plan, path, _ ->
            transfers++
            assertEquals("ar", plan.primary.audioLanguage)
            YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val default = gateway.prepareDownload(context, engine)
            assertNotEquals(savedKey, default.representation.representationId)
            val selected = gateway.prepareDownload(context, engine, savedKey)
            assertEquals(savedKey, selected.requiredRepresentationId)
            assertEquals(savedKey, selected.representation.representationId)
            val before = fixture.requests.toList()
            assertEquals(savedKey, gateway.download(selected, "authored-saved-key-stage.m4a").representation.representationId)
            assertEquals(before, fixture.requests)
            assertEquals(1, transfers)
            assertEquals(2, fixture.watches)
        } finally { fixture.close() }
    }

    @Test fun aRenewalSpentInPreparationIsNotResetByTheSeparateFacadeDownloadCall() = runTest {
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(handler = { request, watch -> ResearchResponse(request.url, 200,
            F.watch(listOf(F.format(expirySeconds = if (watch == 1) 1 else null))))
        }, nowEpochMs = { 200_000L }, downloader = YouTubeDownloader { _, _, _ ->
            transfers++; throw ExpiredResourceException()
        })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val prepared = gateway.prepareDownload(F.context(), engine)
            assertEquals(2, fixture.watches)
            assertDetail("YOUTUBE_SOURCE_EXPIRED") { gateway.download(prepared, "authored-stage.m4a") }
            assertEquals(2, fixture.watches)
            assertEquals(1, transfers)
            assertDetail("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED") { gateway.download(prepared, "another-stage.m4a") }
            assertTrue(fixture.requests.all { it.url == F.WATCH })
        } finally { fixture.close() }
    }

    @Test fun queuedExpiryUsesOnlyOneSameCAndKeyRenewalThroughTheStableFacade() = runTest {
        val expiry = 4_000_000_000L
        var now = expiry * 1_000 - 60_000
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(handler = { request, watch -> ResearchResponse(request.url, 200,
            F.watch(listOf(F.format(signature = "authored-$watch", expirySeconds = expiry + (watch - 1) * 1_000L))))
        }, nowEpochMs = { now }, downloader = YouTubeDownloader { plan, _, _ ->
            transfers++
            assertEquals(F.VIDEO_C, plan.mediaId)
            assertTrue(plan.primary.url!!.contains("authored-2"))
            throw ExpiredResourceException()
        })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val prepared = gateway.prepareDownload(F.context(), engine)
            val key = prepared.representation.representationId
            assertEquals(1, fixture.watches)
            now += 50_000
            assertDetail("YOUTUBE_SOURCE_EXPIRED") { gateway.download(prepared, "authored-queued-stage.m4a") }
            assertEquals(key, prepared.representation.representationId)
            assertEquals(2, fixture.watches)
            assertEquals(1, transfers)
            assertEquals(listOf(F.WATCH, F.WATCH), fixture.requests.map { it.url })
        } finally { fixture.close() }
    }

    @Test fun concurrentFacadeCallsCannotTransferTheSameTypedCapabilityTwice() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { _, path, _ ->
            transfers++; entered.complete(Unit); release.await()
            YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val prepared = gateway.prepareDownload(F.context(), engine)
            val first = async { gateway.download(prepared, "authored-stage.m4a") }
            entered.await()
            assertDetail("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED") { gateway.download(prepared, "another-stage.m4a") }
            release.complete(Unit)
            assertEquals(32L, first.await().bytesWritten)
            assertEquals(1, transfers)
            assertEquals(1, fixture.watches)
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test fun alreadyCancelledTypedDownloadEntryStillConsumesTheFacadePreparedAttempt() = runTest {
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { _, path, _ ->
            transfers++; YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val prepared = gateway.prepareDownload(F.context(), engine)
            var observedCancellation = false
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                try { gateway.download(prepared, "authored-stage.m4a") }
                catch (_: CancellationException) { observedCancellation = true }
            }
            job.join()
            assertTrue(observedCancellation)
            assertDetail("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED") { gateway.download(prepared, "another-stage.m4a") }
            assertEquals(0, transfers)
            assertEquals(1, fixture.watches)
        } finally { fixture.close() }
    }

    @Test fun closingTheSingleSourceInvalidatesTypedAndLegacyExecutionWithoutAnotherOwnedClient() = runTest {
        var closes = 0
        var transfers = 0
        var closed = false
        val fixture = AcquisitionExecutionFixture(onClose = { closes++ }, downloader = YouTubeDownloader { _, path, _ ->
            transfers++; YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val typed = gateway.prepareDownload(F.context(), engine)
            val legacy = gateway.prepareDownload(legacyBinding(), engine)
            assertEquals(0, closes)
            fixture.close(); closed = true
            assertEquals(1, closes)
            assertDetail("YOUTUBE_SOURCE_CLOSED") { gateway.preparePlayback(F.context(), engine) }
            assertDetail("YOUTUBE_SOURCE_CLOSED") { gateway.prepareDownload(legacyBinding(), engine) }
            assertDetail("YOUTUBE_SOURCE_CLOSED") { gateway.download(typed, "authored-typed-stage.m4a") }
            assertDetail("YOUTUBE_SOURCE_CLOSED") { gateway.download(legacy, "authored-legacy-stage.m4a") }
            assertDetail("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED") { gateway.download(typed, "another-typed-stage.m4a") }
            assertDetail("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED") { gateway.download(legacy, "another-legacy-stage.m4a") }
            assertEquals(0, transfers)
            assertEquals(2, fixture.watches)
        } finally { if (!closed) fixture.close() }
    }

    @Test fun typedSabrPlaybackOwnershipPassesThroughWhileDownloadOpensNoPlaybackResource() = runTest {
        var opens = 0
        var nativeCloses = 0
        var transfers = 0
        val native = object : YouTubePlaybackResource {
            override val endpoints = listOf(YouTubePlaybackEndpoint(TrackRole.AUDIO, F.LOOPBACK, "audio/mp4", 32))
            override suspend fun close() { nativeCloses++ }
        }
        val fixture = AcquisitionExecutionFixture(handler = { request, _ -> ResearchResponse(request.url, 200,
            when (request.stage) {
                "youtube-watch" -> F.CONFIG + "<script>var ytInitialPlayerResponse=${F.sabrPlayer()};</script>"
                "youtube-player" -> F.sabrPlayer()
                else -> error("No independent playback or media endpoint")
            }) }, playbackAdapter = YouTubePlaybackAdapter { request, authorization ->
            opens++
            authorization.requireResource(request, F.SABR_ENDPOINT)
            native
        }, downloader = YouTubeDownloader { plan, path, _ ->
            transfers++
            assertEquals(TransportKind.SABR, plan.primary.transport)
            assertEquals(F.SABR_ENDPOINT, plan.primary.url)
            YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val context = F.context(F.request(AcquisitionKind.SHOW))
            val playback = gateway.preparePlayback(context, engine)
            val resource = assertNotNull(playback.resource)
            assertSame(context, playback.context)
            assertEquals(F.LOOPBACK, playback.ownedLoopbackUriOrNull())
            assertFalse(playback.isLocal)
            assertIs<ExecutableSubject.SourceRecordingSubject>(playback.context.subject)
            assertEquals(0, nativeCloses)
            resource.close(); resource.close()
            assertEquals(1, nativeCloses)
            val prepared = gateway.prepareDownload(context, engine)
            val before = fixture.requests.toList()
            assertEquals(prepared.representation, gateway.download(prepared, "authored-sabr-stage.m4a").representation)
            assertEquals(before, fixture.requests)
            assertEquals(1, opens)
            assertEquals(1, transfers)
            assertEquals(1, nativeCloses)
        } finally { fixture.close() }
    }

    private suspend fun assertDetail(detail: String, block: suspend () -> Unit) {
        val error = assertFailsWith<VeyraException> { block() }
        assertEquals(detail, error.failure.detail)
        assertNull(error.cause)
        assertFalse(error.toString().contains("authored-secret"))
    }
}
