package dev.veyra.source.youtube

import dev.kmpyt.YouTubeDownloadResult
import dev.kmpyt.YouTubeDownloader
import dev.kmpyt.extraction.ResearchResponse
import dev.veyra.model.*
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.youtube.AcquisitionExecutionFixtures as F
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Authored expiry/cancellation faults around the SAME shared primitive, never a live/native run. */
class YouTubeLegacyPreparedDownloadRefreshTest {
    private val engine = EngineCapabilities()
    private class ExpiredResourceException : Exception("Authored expired-link outcome")

    @Test fun rejectedTransferRenewsOnlyTheSameSavedCAndKeyAndRetainsTheCompleteReceipt() = runTest {
        var transfers = 0
        val urls = mutableListOf<String?>()
        val fixture = AcquisitionExecutionFixture(handler = { request, watch ->
            assertEquals(F.WATCH, request.url)
            ResearchResponse(request.url, 200, F.watch(listOf(F.format(signature = "authored-$watch"))))
        }, downloader = YouTubeDownloader { plan, path, _ ->
            transfers++; urls += plan.primary.url
            assertEquals(F.VIDEO_C, plan.mediaId)
            assertEquals(F.WATCH, plan.inspectionUrl)
            if (transfers == 1) throw ExpiredResourceException()
            YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val saved = legacyBinding()
            val selected = gateway.prepareDownload(saved, engine)
            val result = gateway.download(selected, "authored-same-private-stage.m4a")
            assertSame(saved, result.binding)
            assertEquals(F.TITLE, result.binding.queryTitle)
            assertEquals(F.RETURNED_TITLE, result.binding.firstResultTitle)
            assertEquals(selected.representation, result.representation)
            assertEquals(2, transfers)
            assertEquals(listOf(F.WATCH, F.WATCH), fixture.requests.map { it.url })
            assertTrue(urls[0]!!.contains("authored-1"))
            assertTrue(urls[1]!!.contains("authored-2"))
        } finally { fixture.close() }
    }

    @Test fun expiryDuringPreparationConsumesTheOnlyRenewalInsteadOfResettingAtDownload() = runTest {
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(handler = { request, watch -> ResearchResponse(request.url, 200,
            F.watch(listOf(F.format(expirySeconds = if (watch == 1) 1 else null))))
        }, downloader = YouTubeDownloader { _, _, _ -> transfers++; throw ExpiredResourceException() })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val selected = gateway.prepareDownload(legacyBinding(), engine)
            assertEquals(2, fixture.watches)
            assertExpired { gateway.download(selected, "authored-private-stage.m4a") }
            assertEquals(2, fixture.watches)
            assertEquals(1, transfers)
        } finally { fixture.close() }
    }

    @Test fun repeatedExpiredPreparationStopsAfterTwoInspectionsWithoutAnyTransfer() = runTest {
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(handler = { request, _ ->
            ResearchResponse(request.url, 200, F.watch(listOf(F.format(expirySeconds = 1))))
        }, downloader = YouTubeDownloader { _, path, _ -> transfers++; YouTubeDownloadResult(path, 32, "audio/mp4") })
        try {
            assertExpired { YouTubeKmpMediaGateway(fixture.source).prepareDownload(legacyBinding(), engine) }
            assertEquals(2, fixture.watches)
            assertEquals(0, transfers)
        } finally { fixture.close() }
    }

    @Test fun aQueuedPreparedPlanMayRenewOnceButAnExpiredTransferCannotAddAnotherBudget() = runTest {
        val expiry = 4_000_000_000L
        var now = expiry * 1_000 - 60_000
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(handler = { request, watch -> ResearchResponse(request.url, 200,
            F.watch(listOf(F.format(signature = "authored-$watch", expirySeconds = expiry + (watch - 1) * 1_000L))))
        }, nowEpochMs = { now }, downloader = YouTubeDownloader { plan, _, _ ->
            transfers++
            assertTrue(plan.primary.url!!.contains("authored-2"))
            throw ExpiredResourceException()
        })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val selected = gateway.prepareDownload(legacyBinding(), engine)
            assertEquals(1, fixture.watches)
            now += 50_000
            assertExpired { gateway.download(selected, "authored-private-stage.m4a") }
            assertEquals(2, fixture.watches)
            assertEquals(1, transfers)
        } finally { fixture.close() }
    }

    @Test fun queuedExpiryAfterPreparationAlreadyRenewedStopsBeforeAnyTransfer() = runTest {
        val expiry = 4_000_000_000L
        var now = expiry * 1_000 - 60_000
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(handler = { request, watch -> ResearchResponse(request.url, 200,
            F.watch(listOf(F.format(expirySeconds = if (watch == 1) 1 else expiry))))
        }, nowEpochMs = { now }, downloader = YouTubeDownloader { _, path, _ ->
            transfers++; YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val selected = gateway.prepareDownload(legacyBinding(), engine)
            assertEquals(2, fixture.watches)
            now += 50_000
            assertExpired { gateway.download(selected, "authored-private-stage.m4a") }
            assertEquals(2, fixture.watches)
            assertEquals(0, transfers)
        } finally { fixture.close() }
    }

    @Test fun repeatedExpiredTransfersStopAfterOneRenewalAndConsumeTheAttempt() = runTest {
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { _, _, _ ->
            transfers++; throw ExpiredResourceException()
        })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val selected = gateway.prepareDownload(legacyBinding(), engine)
            assertExpired { gateway.download(selected, "authored-private-stage.m4a") }
            assertEquals(2, fixture.watches)
            assertEquals(2, transfers)
            assertEquals("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED", assertFailsWith<VeyraException> {
                gateway.download(selected, "another-stage.m4a")
            }.failure.detail)
        } finally { fixture.close() }
    }

    @Test fun sameCRefreshRechecksAuthorizationInsteadOfExecutingARefusedReport() = runTest {
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(handler = { request, watch -> ResearchResponse(request.url, 200,
            if (watch == 1) F.watch() else F.watch(emptyList(), status = "LOGIN_REQUIRED"))
        }, downloader = YouTubeDownloader { _, _, _ -> transfers++; throw ExpiredResourceException() })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val selected = gateway.prepareDownload(legacyBinding(), engine)
            assertEquals(FailureCode.PROVIDER_ACCESS_REQUIRED, assertFailsWith<VeyraException> {
                gateway.download(selected, "authored-private-stage.m4a")
            }.failure.code)
            assertEquals(2, fixture.watches)
            assertEquals(1, transfers)
        } finally { fixture.close() }
    }

    @Test fun refreshCannotSubstituteAnotherLanguageFormatSizeOrVideoForTheSavedProducer() = runTest {
        for (change in listOf("language", "format", "size", "video")) {
            var transfers = 0
            val fixture = AcquisitionExecutionFixture(handler = { request, watch ->
                if (request.stage == "youtube-oembed") ResearchResponse(request.url, 404, "Authored missing metadata")
                else {
                    assertEquals(F.WATCH, request.url)
                    val format = if (watch == 1) F.format() else when (change) {
                        "language" -> F.format(language = "ar")
                        "format" -> F.format(itag = 141)
                        "size" -> F.format(size = 33)
                        else -> F.format()
                    }
                    ResearchResponse(request.url, 200, F.watch(listOf(format),
                        videoId = if (watch > 1 && change == "video") F.VIDEO_B else F.VIDEO_C))
                }
            }, downloader = YouTubeDownloader { _, _, _ -> transfers++; throw ExpiredResourceException() })
            try {
                val gateway = YouTubeKmpMediaGateway(fixture.source)
                val saved = legacyBinding()
                val selected = gateway.prepareDownload(saved, engine)
                val error = assertFailsWith<VeyraException> { gateway.download(selected, "authored-private-stage.m4a") }
                assertEquals(if (change == "video") "YOUTUBE_IDENTITY_MISMATCH" else "YOUTUBE_SELECTED_TRACK_CHANGED", error.failure.detail)
                assertSame(saved, selected.binding)
                assertEquals(2, fixture.watches)
                assertEquals(1, transfers)
                assertTrue(fixture.requests.none { "/results" in it.url })
            } finally { fixture.close() }
        }
    }

    @Test fun mutableNativeCapabilitiesAreDetachedForTheWholePreparationAndRenewalAttempt() = runTest {
        var transfers = 0
        val transports = mutableSetOf(StreamTransport.PROGRESSIVE)
        val codecs = mutableSetOf("aac")
        val mimes = mutableSetOf("audio/mp4")
        val capabilities = EngineCapabilities(transports = transports, mimeTypes = mimes, codecs = codecs)
        val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { _, path, _ ->
            if (++transfers == 1) throw ExpiredResourceException()
            YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val gateway = YouTubeKmpMediaGateway(fixture.source)
            val selected = gateway.prepareDownload(legacyBinding(), capabilities)
            transports.clear(); codecs.clear(); mimes.clear()
            assertEquals(32L, gateway.download(selected, "authored-private-stage.m4a").bytesWritten)
            assertEquals(2, fixture.watches)
            assertEquals(2, transfers)
        } finally { fixture.close() }
    }

    @Test fun preparationCancellationPropagatesWithoutAnotherInspectionOrNativeTransfer() = runTest {
        val cancellation = CancellationException("authored legacy inspection cancellation")
        val fixture = AcquisitionExecutionFixture(handler = { _, _ -> throw cancellation },
            downloader = YouTubeDownloader { _, _, _ -> error("Canceled preparation must not transfer") })
        try {
            val actual = assertFailsWith<CancellationException> {
                YouTubeKmpMediaGateway(fixture.source).prepareDownload(legacyBinding(), engine)
            }
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.take(8).any { it === cancellation })
            assertEquals(1, fixture.watches)
        } finally { fixture.close() }
    }

    private suspend fun assertExpired(block: suspend () -> Unit) {
        val error = assertFailsWith<VeyraException> { block() }
        assertEquals(FailureCode.RECOVERY_REQUIRED, error.failure.code)
        assertEquals("YOUTUBE_SOURCE_EXPIRED", error.failure.detail)
        assertNull(error.cause)
    }
}
