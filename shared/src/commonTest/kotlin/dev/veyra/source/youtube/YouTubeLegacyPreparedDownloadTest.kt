package dev.veyra.source.youtube

import dev.kmpyt.*
import dev.kmpyt.extraction.*
import dev.veyra.model.*
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.execution.*
import dev.veyra.source.youtube.AcquisitionExecutionFixtures as F
import io.ktor.http.Url
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Actual public parser/planner with authored adapters; no Room, native, partial-file or live proof. */
class YouTubeLegacyPreparedDownloadTest {
    private val engine = EngineCapabilities()

    @Test fun savedSchema2ReceiptIsReturnedExactlyWithoutAnotherSearchOrCatalogueMediaFabrication() = runTest {
        var transfers = 0
        val observed = mutableListOf<AcquisitionTransferProgress>()
        val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { plan, path, progress ->
            transfers++
            assertEquals(F.VIDEO_C, plan.mediaId)
            assertEquals(F.WATCH, plan.inspectionUrl)
            assertNull(plan.audio)
            assertTrue(plan.requestHeaders.isEmpty())
            assertNull(plan.credentialRef)
            progress(YouTubeDownloadProgress(32, 32, -4))
            YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            assertIs<LegacyPreparedDownloadGateway>(gateway)
            val saved = legacyBinding()
            val selected = gateway.prepareDownload(saved, engine)
            assertSame(saved, selected.binding)
            assertNull(selected.requiredRepresentationId)
            assertEquals("audio/mp4", selected.representation.mimeType)
            assertEquals("mp4a.40.2", selected.representation.codecs)
            assertEquals("m4a", selected.representation.extension)
            assertEquals(0, transfers)
            val result = gateway.download(selected, "authored-existing-private-stage.m4a") { observed += it }
            assertSame(saved, result.binding)
            assertEquals("rss:original-guid", result.binding.mediaId)
            assertEquals(F.TITLE, result.binding.queryTitle)
            assertEquals(F.RETURNED_TITLE, result.binding.firstResultTitle)
            assertEquals(123_456L, result.binding.resolvedEpochMs)
            assertEquals(0, result.binding.selectionOrdinal)
            assertEquals(selected.representation, result.representation)
            assertEquals(32L, result.bytesWritten)
            assertEquals(listOf(AcquisitionTransferProgress(32, 32, 0)), observed)
            assertEquals(listOf(F.WATCH), fixture.requests.map { it.url })
            assertEquals(1, transfers)
            for (value in listOf(selected, result)) {
                assertFalse(value.toString().contains("authored-secret"))
                assertFalse(value.toString().contains(F.TITLE))
                assertFalse(value.toString().contains("rss:original-guid"))
            }
        } finally { fixture.close() }
    }

    @Test fun aSavedNondefaultKeyIsBoundAtPreparationAfterRestartInsteadOfReselectingTheDefault() = runTest {
        val arabic = F.format(language = "ar", default = false, itag = 141)
        val old = AcquisitionExecutionFixture(handler = { request, _ ->
            ResearchResponse(request.url, 200, F.watch(listOf(arabic)))
        })
        val saved = legacyBinding()
        val savedKey = try {
            YouTubeKmpMediaGateway(old.source).prepareDownload(saved, engine).representation.representationId
        } finally { old.close() }
        var transfers = 0
        val restarted = AcquisitionExecutionFixture(handler = { request, _ ->
            ResearchResponse(request.url, 200, F.watch(listOf(F.format(), arabic)))
        }, downloader = YouTubeDownloader { plan, path, _ ->
            transfers++
            assertEquals("ar", plan.primary.audioLanguage)
            assertEquals(savedKey, plan.primary.audioSelectionKey(F.VIDEO_C))
            YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val gateway = YouTubeKmpMediaGateway(restarted.source)
            val unconstrained = gateway.prepareDownload(saved, engine)
            assertNotEquals(savedKey, unconstrained.representation.representationId)
            val resumed = gateway.prepareDownload(saved, engine, savedKey)
            assertEquals(savedKey, resumed.requiredRepresentationId)
            assertEquals(savedKey, resumed.representation.representationId)
            val beforeTransfer = restarted.requests.toList()
            val result = gateway.download(resumed, "authored-same-private-stage.m4a")
            assertSame(saved, result.binding)
            assertEquals(resumed.representation, result.representation)
            assertEquals(beforeTransfer, restarted.requests)
            assertEquals(listOf(F.WATCH, F.WATCH), restarted.requests.map { it.url })
            assertEquals(1, transfers)
        } finally { restarted.close() }
    }

    @Test fun aMissingStoredRepresentationFailsBeforeAnyTransferOrLaterFormatFallback() = runTest {
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { _, path, _ ->
            transfers++; YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            assertDetail("YOUTUBE_SELECTED_TRACK_CHANGED") {
                YouTubeKmpMediaGateway(fixture.source).prepareDownload(legacyBinding(), engine, "yt-audio-v1:" + "0".repeat(64))
            }
            assertEquals(1, fixture.watches)
            assertEquals(0, transfers)
        } finally { fixture.close() }
    }

    @Test fun unknownOrInconsistentLegacyProvenanceNeverReachesSourceOrAStagingDestination() = runTest {
        val fixture = AcquisitionExecutionFixture(handler = { _, _ -> error("Invalid saved provenance must not inspect") },
            downloader = YouTubeDownloader { _, _, _ -> error("Unknown partial bytes must not be transferred") })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val saved = legacyBinding()
            for (invalid in listOf(saved.copy(id = ""), saved.copy(mediaId = " "),
                saved.copy(selectionOrdinal = 1), saved.copy(firstResultId = F.VIDEO_B),
                saved.copy(selectedVideoId = null), saved.copy(selectedVideoId = F.VIDEO_B),
                saved.copy(queryTitle = ""), saved.copy(queryTitle = "x".repeat(16_385)),
                saved.copy(firstResultTitle = ""), saved.copy(firstResultTitle = "x".repeat(16_385)),
                saved.copy(firstResultUrl = F.WATCH + "&sig=authored-secret"),
                saved.copy(firstResultUrl = F.WATCH + "#fragment"))) {
                assertDetail("YOUTUBE_EXECUTION_BINDING_MISMATCH") { gateway.prepareDownload(invalid, engine) }
            }
            for (kind in listOf(FirstResultKind.UNKNOWN, FirstResultKind.CHANNEL, FirstResultKind.PLAYLIST)) {
                assertDetail("YOUTUBE_FIRST_RESULT_NOT_VIDEO") { gateway.prepareDownload(saved.copy(firstResultKind = kind), engine) }
            }
            assertTrue(fixture.requests.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun aHistoricallyMissingOptionalUrlIsNotInventedInTheReturnedLegacyReceipt() = runTest {
        val fixture = AcquisitionExecutionFixture()
        try {
            val saved = legacyBinding().copy(firstResultUrl = null)
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val result = gateway.download(gateway.prepareDownload(saved, engine), "authored-private-stage.m4a")
            assertSame(saved, result.binding)
            assertNull(result.binding.firstResultUrl)
            assertEquals(listOf(F.WATCH), fixture.requests.map { it.url })
        } finally { fixture.close() }
    }

    @Test fun aPreparedLegacyCapabilityBelongsToItsIssuingGatewayNotMerelyItsClientOrPublicFields() = runTest {
        val fixture = AcquisitionExecutionFixture()
        val foreign = AcquisitionExecutionFixture(handler = { _, _ -> error("Foreign source may not inspect") })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val selected = gateway.prepareDownload(legacyBinding(), engine)
            assertDetail("YOUTUBE_PREPARED_DOWNLOAD_MISMATCH") {
                YouTubeKmpMediaGateway(fixture.source).download(selected, "foreign-stage.m4a")
            }
            assertDetail("YOUTUBE_PREPARED_DOWNLOAD_MISMATCH") {
                YouTubeKmpMediaGateway(foreign.source).download(selected, "foreign-stage.m4a")
            }
            val forged = object : PreparedLegacyDownload {
                override val binding: PlaybackBinding get() = error("Do not read a forged public getter")
                override val requiredRepresentationId: String? get() = error("Do not read a forged public getter")
                override val representation: AcquisitionRepresentation get() = error("Do not read a forged public getter")
            }
            assertDetail("YOUTUBE_PREPARED_DOWNLOAD_MISMATCH") { gateway.download(forged, "foreign-stage.m4a") }
            // Failed foreign use must not consume the actual owner's capability.
            assertEquals(32L, gateway.download(selected, "authored-private-stage.m4a").bytesWritten)
            assertEquals(1, fixture.watches)
            assertTrue(foreign.requests.isEmpty())
        } finally { fixture.close(); foreign.close() }
    }

    @Test fun successFailureAndInvalidDestinationEachConsumeTheExactPreparedAttempt() = runTest {
        for (case in listOf("success", "failure", "blank")) {
            var transfers = 0
            val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { _, path, _ ->
                transfers++
                if (case == "failure") error("authored-secret transfer failure")
                YouTubeDownloadResult(path, 32, "audio/mp4")
            })
            try {
                val gateway = YouTubeKmpMediaGateway(fixture.source)
                val selected = gateway.prepareDownload(legacyBinding(), engine)
                if (case == "success") gateway.download(selected, "authored-private-stage.m4a")
                else assertDetail(if (case == "blank") "YOUTUBE_INVALID_DESTINATION" else "YOUTUBE_DOWNLOAD_FAILED") {
                    gateway.download(selected, if (case == "blank") " " else "authored-private-stage.m4a")
                }
                assertDetail("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED") { gateway.download(selected, "another-stage.m4a") }
                assertEquals(if (case == "blank") 0 else 1, transfers)
                assertEquals(1, fixture.watches)
            } finally { fixture.close() }
        }
    }

    @Test fun simultaneousConsumptionCanLaunchOnlyOneTransfer() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { _, path, _ ->
            transfers++; entered.complete(Unit); release.await()
            YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val selected = gateway.prepareDownload(legacyBinding(), engine)
            val first = async { gateway.download(selected, "authored-private-stage.m4a") }
            entered.await()
            assertDetail("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED") { gateway.download(selected, "another-stage.m4a") }
            release.complete(Unit)
            assertEquals(32L, first.await().bytesWritten)
            assertEquals(1, transfers)
            assertEquals(1, fixture.watches)
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test fun cancellationAtEntryConsumesTheCapabilityWithoutTransferOrANewInspection() = runTest {
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { _, path, _ ->
            transfers++; YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val selected = gateway.prepareDownload(legacyBinding(), engine)
            var cancelled = false
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                try { gateway.download(selected, "authored-private-stage.m4a") }
                catch (_: CancellationException) { cancelled = true }
            }
            job.join()
            assertTrue(cancelled)
            assertDetail("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED") { gateway.download(selected, "another-stage.m4a") }
            assertEquals(0, transfers)
            assertEquals(1, fixture.watches)
        } finally { fixture.close() }
    }

    @Test fun cancellationFromProgressIsNotRetriedAndCannotReuseThePreparedSelection() = runTest {
        val cancellation = CancellationException("authored legacy progress cancellation")
        var transfers = 0
        var completed = false
        val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { _, path, report ->
            transfers++; report(YouTubeDownloadProgress(16, 32)); completed = true
            YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val selected = gateway.prepareDownload(legacyBinding(), engine)
            val actual = assertFailsWith<CancellationException> {
                gateway.download(selected, "authored-private-stage.m4a") { throw cancellation }
            }
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.take(8).any { it === cancellation })
            assertFalse(completed)
            assertEquals(1, transfers)
            assertEquals(1, fixture.watches)
            assertDetail("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED") { gateway.download(selected, "another-stage.m4a") }
        } finally { fixture.close() }
    }

    @Test fun sourceCloseIsTheSharedLifetimeAndAnUnusableExistingCapabilityIsStillConsumed() = runTest {
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { _, path, _ ->
            transfers++; YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        val gateway = YouTubeKmpMediaGateway(fixture.source)
        val selected = gateway.prepareDownload(legacyBinding(), engine)
        fixture.close()
        assertDetail("YOUTUBE_SOURCE_CLOSED") { gateway.prepareDownload(legacyBinding(), engine) }
        assertDetail("YOUTUBE_SOURCE_CLOSED") { fixture.execution.prepareDownload(F.context(), engine) }
        assertDetail("YOUTUBE_SOURCE_CLOSED") { gateway.download(selected, "authored-private-stage.m4a") }
        assertDetail("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED") { gateway.download(selected, "another-stage.m4a") }
        assertEquals(1, fixture.watches)
        assertEquals(0, transfers)
    }

    @Test fun malformedRequiredKeysFailBeforeSourceInspection() = runTest {
        val fixture = AcquisitionExecutionFixture(handler = { _, _ -> error("Malformed key cannot inspect") })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            for (key in listOf("", "140", "yt-audio-v1:" + "z".repeat(64))) {
                assertDetail("YOUTUBE_INVALID_SELECTION") { gateway.prepareDownload(legacyBinding(), engine, key) }
            }
            assertTrue(fixture.requests.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun sabrDownloadOpensNoPlaybackResourceAndTransfersTheSamePreparedNativePlan() = runTest {
        var opens = 0
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(handler = { request, _ ->
            when (request.stage) {
                "youtube-watch" -> {
                    assertEquals(F.WATCH, request.url)
                    ResearchResponse(request.url, 200, F.CONFIG + "<script>var ytInitialPlayerResponse=${F.sabrPlayer()};</script>")
                }
                "youtube-player-client" -> {
                    assertEquals("POST", request.method)
                    assertEquals("www.youtube.com", Url(request.url).host)
                    assertEquals("/youtubei/v1/player", Url(request.url).encodedPath)
                    val body = Json.parseToJsonElement(assertNotNull(request.body)).jsonObject
                    assertEquals(F.VIDEO_C, body.getValue("videoId").jsonPrimitive.content)
                    assertFalse("serviceIntegrityDimensions" in body)
                    ResearchResponse(request.url, 200, F.sabrPlayer())
                }
                else -> error("Legacy prepared execution cannot search or use another source")
            }
        }, downloader = YouTubeDownloader { plan, path, _ ->
            transfers++
            assertEquals(F.VIDEO_C, plan.mediaId)
            assertEquals(TransportKind.SABR, plan.primary.transport)
            assertEquals(F.SABR_ENDPOINT, plan.primary.url)
            assertNull(plan.audio)
            YouTubeDownloadResult(path, 32, "audio/mp4")
        }, playbackAdapter = YouTubePlaybackAdapter { _, _ -> opens++; error("DOWNLOAD cannot open playback") })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val selected = gateway.prepareDownload(legacyBinding(), engine)
            assertEquals(0, transfers)
            val before = fixture.requests.toList()
            val result = gateway.download(selected, "authored-private-stage.m4a")
            assertEquals(selected.representation, result.representation)
            assertEquals(before, fixture.requests)
            assertEquals(1, transfers)
            assertEquals(0, opens)
        } finally { fixture.close() }
    }

    @Test fun invalidTransferCountsAndMimeCannotBecomeSuccessfulLegacyEvidence() = runTest {
        for (case in listOf("negative-progress", "empty", "short", "mime")) {
            val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { _, path, report ->
                if (case == "negative-progress") report(YouTubeDownloadProgress(-1))
                YouTubeDownloadResult(path, when (case) { "empty" -> 0L; "short" -> 31L; else -> 32L },
                    if (case == "mime") "audio/webm" else "audio/mp4")
            })
            try {
                val gateway = YouTubeKmpMediaGateway(fixture.source)
                val selected = gateway.prepareDownload(legacyBinding(), engine)
                val error = assertFailsWith<VeyraException> { gateway.download(selected, "authored-private-stage.m4a") }
                assertEquals(FailureCode.CORRUPT_MEDIA, error.failure.code)
                assertDetail("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED") { gateway.download(selected, "another-stage.m4a") }
            } finally { fixture.close() }
        }
    }

    private suspend fun assertDetail(detail: String, block: suspend () -> Unit) {
        val error = assertFailsWith<VeyraException> { block() }
        assertEquals(detail, error.failure.detail)
        assertNull(error.cause)
        assertFalse(error.toString().contains("authored-secret"))
    }
}

/** An already saved, complete legacy receipt; never a fabricated typed AcquisitionRequest. */
internal fun legacyBinding() = PlaybackBinding("legacy:known-receipt", "rss:original-guid", F.TITLE,
    FirstResultKind.VIDEO, F.VIDEO_C, F.RETURNED_TITLE, F.WATCH, F.VIDEO_C, 123_456, 0)
