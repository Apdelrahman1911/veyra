package dev.veyra.source.youtube

import dev.kmpyt.YouTubeDownloadResult
import dev.kmpyt.YouTubeDownloadProgress
import dev.kmpyt.YouTubeDownloader
import dev.kmpyt.extraction.ResearchResponse
import dev.veyra.model.*
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.youtube.AcquisitionExecutionFixtures as F
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** No clock sleep, live source or native transfer. Real parser/planner + authored expiry faults. */
class YouTubeAcquisitionExecutionRefreshTest {
    private val engine = EngineCapabilities()
    private class ExpiredResourceException : Exception("Authored expired-link outcome")

    @Test fun rejectedTransferRefreshesTheSameCAndKeyOnceWithoutRepeatingSelection() = runTest {
        var transfers = 0
        val urls = mutableListOf<String?>()
        val fixture = AcquisitionExecutionFixture(handler = { request, watch ->
            assertEquals(F.WATCH, request.url)
            ResearchResponse(request.url, 200, F.watch(listOf(F.format(signature = "authored-$watch"))))
        }, downloader = YouTubeDownloader { plan, path, _ ->
            transfers++; urls += plan.primary.url
            assertEquals(F.VIDEO_C, plan.mediaId)
            if (transfers == 1) throw ExpiredResourceException()
            YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val context = F.context(F.request(AcquisitionKind.COLLECTION_MEMBER))
            val prepared = fixture.execution.prepareDownload(context, engine)
            val result = fixture.execution.download(prepared, "authored-stage.m4a")
            assertEquals(context, result.context)
            assertEquals(prepared.representation, result.representation)
            assertEquals(2, fixture.watches)
            assertEquals(2, transfers)
            assertTrue(urls[0]!!.contains("authored-1"))
            assertTrue(urls[1]!!.contains("authored-2"))
            assertEquals(listOf(F.WATCH, F.WATCH), fixture.requests.map { it.url })
        } finally { fixture.close() }
    }

    @Test fun expiryDuringPreparationConsumesTheOnlyRenewalBeforeTransfer() = runTest {
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(handler = { request, watch -> ResearchResponse(request.url, 200,
            F.watch(listOf(if (watch == 1) F.format(expirySeconds = 1) else F.format())))
        }, downloader = YouTubeDownloader { _, _, _ -> transfers++; throw ExpiredResourceException() })
        try {
            val prepared = fixture.execution.prepareDownload(F.context(), engine)
            assertEquals(2, fixture.watches)
            assertExpired { fixture.execution.download(prepared, "authored-stage.m4a") }
            assertEquals(2, fixture.watches)
            assertEquals(1, transfers)
        } finally { fixture.close() }
    }

    @Test fun repeatedExpiredPreparationIsBoundedToTwoDirectInspectionsAndNoTransfer() = runTest {
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(handler = { request, _ ->
            ResearchResponse(request.url, 200, F.watch(listOf(F.format(expirySeconds = 1))))
        }, downloader = YouTubeDownloader { _, path, _ -> transfers++; YouTubeDownloadResult(path, 32, "audio/mp4") })
        try {
            assertExpired { fixture.execution.prepareDownload(F.context(), engine) }
            assertEquals(2, fixture.watches)
            assertEquals(0, transfers)
        } finally { fixture.close() }
    }

    @Test fun aDelayedPreparedSourceRenewsBeforeTransferUsingTheSameBudgetAndRepresentation() = runTest {
        val expiry = 4_000_000_000L
        var now = expiry * 1_000 - 60_000
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(handler = { request, watch -> ResearchResponse(request.url, 200,
            F.watch(listOf(F.format(signature = "authored-$watch", expirySeconds = expiry + (watch - 1) * 1_000L))))
        }, nowEpochMs = { now }, downloader = YouTubeDownloader { plan, path, _ ->
            transfers++; assertTrue(plan.primary.url!!.contains("authored-2"))
            YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val prepared = fixture.execution.prepareDownload(F.context(), engine)
            assertEquals(1, fixture.watches)
            now += 50_000
            val result = fixture.execution.download(prepared, "authored-stage.m4a")
            assertEquals(prepared.representation, result.representation)
            assertEquals(2, fixture.watches)
            assertEquals(1, transfers)
        } finally { fixture.close() }
    }

    @Test fun delayedExpiryCannotCreateANewBudgetAfterPreparationAlreadyRenewed() = runTest {
        val expiry = 4_000_000_000L
        var now = expiry * 1_000 - 60_000
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(handler = { request, watch -> ResearchResponse(request.url, 200,
            F.watch(listOf(F.format(expirySeconds = if (watch == 1) 1 else expiry))))
        }, nowEpochMs = { now }, downloader = YouTubeDownloader { _, path, _ ->
            transfers++; YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val prepared = fixture.execution.prepareDownload(F.context(), engine)
            assertEquals(2, fixture.watches)
            now += 50_000
            assertExpired { fixture.execution.download(prepared, "authored-stage.m4a") }
            assertEquals(2, fixture.watches)
            assertEquals(0, transfers)
        } finally { fixture.close() }
    }

    @Test fun delayedExpiryRenewalConsumesTheOnlyBudgetBeforeAnExpiredTransferResponse() = runTest {
        val expiry = 4_000_000_000L
        var now = expiry * 1_000 - 60_000
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(handler = { request, watch -> ResearchResponse(request.url, 200,
            F.watch(listOf(F.format(expirySeconds = expiry + (watch - 1) * 1_000L))))
        }, nowEpochMs = { now }, downloader = YouTubeDownloader { _, _, _ -> transfers++; throw ExpiredResourceException() })
        try {
            val prepared = fixture.execution.prepareDownload(F.context(), engine)
            now += 50_000
            assertExpired { fixture.execution.download(prepared, "authored-stage.m4a") }
            assertEquals(2, fixture.watches)
            assertEquals(1, transfers)
        } finally { fixture.close() }
    }

    @Test fun anExpiredSuppliedInspectionUsesOneRenewalRatherThanASeparateDownloadInspectionBudget() = runTest {
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(handler = { request, watch -> ResearchResponse(request.url, 200,
            F.watch(listOf(F.format(expirySeconds = if (watch == 1) 1 else null))))
        }, downloader = YouTubeDownloader { _, _, _ -> transfers++; throw ExpiredResourceException() })
        try {
            val context = F.context()
            val single = assertIs<SourceAcquisitionResult.Single>(fixture.acquisition.restore(context.request, context.receipt.observation))
            val inspected = fixture.acquisition.inspect(context.request, single)
            assertEquals(1, fixture.watches)
            val prepared = fixture.execution.prepareDownload(context, engine, inspection = inspected)
            assertEquals(2, fixture.watches)
            assertExpired { fixture.execution.download(prepared, "authored-stage.m4a") }
            assertEquals(2, fixture.watches)
            assertEquals(1, transfers)
        } finally { fixture.close() }
    }

    @Test fun repeatedRejectedTransfersAreBoundedToOneRenewalAndTwoNativeAttempts() = runTest {
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { _, _, _ -> transfers++; throw ExpiredResourceException() })
        try {
            val prepared = fixture.execution.prepareDownload(F.context(), engine)
            assertExpired { fixture.execution.download(prepared, "authored-stage.m4a") }
            assertEquals(2, transfers)
            assertEquals(2, fixture.watches)
            assertEquals("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED", assertFailsWith<VeyraException> {
                fixture.execution.download(prepared, "authored-stage.m4a")
            }.failure.detail)
        } finally { fixture.close() }
    }

    @Test fun renewalRechecksAuthorizationAndNeverTransfersAfterAnActualRefusalReport() = runTest {
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(handler = { request, watch -> ResearchResponse(request.url, 200,
            if (watch == 1) F.watch() else F.watch(emptyList(), status = "LOGIN_REQUIRED"))
        }, downloader = YouTubeDownloader { _, _, _ -> transfers++; throw ExpiredResourceException() })
        try {
            val prepared = fixture.execution.prepareDownload(F.context(), engine)
            val error = assertFailsWith<VeyraException> { fixture.execution.download(prepared, "authored-stage.m4a") }
            assertEquals(FailureCode.PROVIDER_ACCESS_REQUIRED, error.failure.code)
            assertEquals(2, fixture.watches)
            assertEquals(1, transfers)
        } finally { fixture.close() }
    }

    @Test fun renewalCannotSubstituteLanguageFormatCodecOrSizeForTheOriginalExactKey() = runTest {
        for (changed in listOf(F.format(language = "ar"), F.format(itag = 141), F.format(size = 33),
            F.format(mime = "audio/webm", codec = "opus"))) {
            var transfers = 0
            val fixture = AcquisitionExecutionFixture(handler = { request, watch -> ResearchResponse(request.url, 200,
                F.watch(listOf(if (watch == 1) F.format() else changed)))
            }, downloader = YouTubeDownloader { _, _, _ -> transfers++; throw ExpiredResourceException() })
            try {
                val prepared = fixture.execution.prepareDownload(F.context(), engine)
                val error = assertFailsWith<VeyraException> { fixture.execution.download(prepared, "authored-stage.m4a") }
                assertEquals("YOUTUBE_SELECTED_TRACK_CHANGED", error.failure.detail)
                assertEquals(2, fixture.watches)
                assertEquals(1, transfers)
            } finally { fixture.close() }
        }
    }

    @Test fun aDifferentVideoInARefreshResponseIsRejectedWithoutChangingTheBinding() = runTest {
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(handler = { request, watch ->
            if (request.stage == "youtube-oembed") ResearchResponse(request.url, 404, "authored missing metadata")
            else {
                assertEquals(F.WATCH, request.url)
                ResearchResponse(request.url, 200, F.watch(videoId = if (watch == 1) F.VIDEO_C else F.VIDEO_B))
            }
        }, downloader = YouTubeDownloader { _, _, _ -> transfers++; throw ExpiredResourceException() })
        try {
            val context = F.context()
            val prepared = fixture.execution.prepareDownload(context, engine)
            assertEquals("YOUTUBE_IDENTITY_MISMATCH", assertFailsWith<VeyraException> {
                fixture.execution.download(prepared, "authored-stage.m4a")
            }.failure.detail)
            assertEquals(context, prepared.context)
            assertEquals(2, fixture.watches)
            assertEquals(1, transfers)
            assertTrue(fixture.requests.none { "/results" in it.url })
        } finally { fixture.close() }
    }

    @Test fun nativeCapabilitiesAreDetachedAtPreparationAndRetainedForTheSameAttemptRenewal() = runTest {
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
            val prepared = fixture.execution.prepareDownload(F.context(), capabilities)
            transports.clear(); codecs.clear(); mimes.clear()
            assertEquals(32L, fixture.execution.download(prepared, "authored-stage.m4a").bytesWritten)
            assertEquals(2, fixture.watches)
            assertEquals(2, transfers)
        } finally { fixture.close() }
    }

    @Test fun inspectAndTransferCancellationRemainCancellationWithoutRefreshOrCapabilityReuse() = runTest {
        val cancelled = CancellationException("authored cancellation")
        fun sameCancellation(actual: CancellationException) {
            // JVM coroutine stacktrace recovery may copy the exception while retaining cause.
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.take(8).any { it === cancelled })
            assertEquals(cancelled.message, actual.message)
        }
        val inspection = AcquisitionExecutionFixture(handler = { _, _ -> throw cancelled })
        try {
            sameCancellation(assertFailsWith<CancellationException> { inspection.execution.prepareDownload(F.context(), engine) })
            assertEquals(1, inspection.watches)
        } finally { inspection.close() }
        var transfers = 0
        val transfer = AcquisitionExecutionFixture(downloader = YouTubeDownloader { _, _, _ -> transfers++; throw cancelled })
        try {
            val prepared = transfer.execution.prepareDownload(F.context(), engine)
            sameCancellation(assertFailsWith<CancellationException> { transfer.execution.download(prepared, "authored-stage.m4a") })
            assertEquals(1, transfer.watches)
            assertEquals(1, transfers)
            assertEquals("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED", assertFailsWith<VeyraException> {
                transfer.execution.download(prepared, "authored-stage.m4a")
            }.failure.detail)
        } finally { transfer.close() }
    }

    @Test fun throwingProgressCancellationAbortsTheSameAttemptWithoutRenewalOrTerminalReuse() = runTest {
        val cancellation = CancellationException("authored progress cancellation")
        var transfers = 0
        var callbacks = 0
        var returned = false
        val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { _, path, report ->
            transfers++
            report(YouTubeDownloadProgress(16, 32))
            returned = true
            YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val prepared = fixture.execution.prepareDownload(F.context(), engine)
            val actual = assertFailsWith<CancellationException> {
                fixture.execution.download(prepared, "authored-stage.m4a") { callbacks++; throw cancellation }
            }
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.take(8).any { it === cancellation })
            assertEquals(1, callbacks)
            assertEquals(1, transfers)
            assertEquals(1, fixture.watches)
            assertFalse(returned)
            assertEquals("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED", assertFailsWith<VeyraException> {
                fixture.execution.download(prepared, "authored-stage.m4a")
            }.failure.detail)
        } finally { fixture.close() }
    }

    private suspend fun assertExpired(block: suspend () -> Unit) {
        val error = assertFailsWith<VeyraException> { block() }
        assertEquals(FailureCode.RECOVERY_REQUIRED, error.failure.code)
        assertEquals("YOUTUBE_SOURCE_EXPIRED", error.failure.detail)
        assertNull(error.cause)
    }
}
