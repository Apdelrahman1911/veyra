package dev.veyra.source.youtube

import dev.kmpyt.YouTubeDownloadProgress
import dev.kmpyt.YouTubeDownloadResult
import dev.kmpyt.YouTubeDownloader
import dev.kmpyt.extraction.ResearchResponse
import dev.veyra.model.*
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.execution.*
import dev.veyra.source.youtube.AcquisitionExecutionFixtures as F
import io.ktor.http.Url
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Real upstream public parser/planner with authored adapters; not live, native or Room proof. */
class YouTubeAcquisitionExecutionGatewayTest {
    private val engine = EngineCapabilities()

    @Test fun everyTypedKindUsesTheSameAcceptedRecordingWithoutLegacyMediaCoercionOrSearch() = runTest {
        for (kind in AcquisitionKind.entries) {
            val fixture = AcquisitionExecutionFixture()
            try {
                val context = F.context(F.request(kind))
                val playback = fixture.execution.preparePlayback(context, engine)
                val prepared = fixture.execution.prepareDownload(context, engine, playback.representationId)
                val transferred = fixture.execution.download(prepared, "authored-stage.m4a")
                assertSame(context, playback.context)
                assertEquals(context, prepared.context)
                assertEquals(context, transferred.context)
                assertEquals(playback.representationId, prepared.requiredRepresentationId)
                assertEquals(prepared.representation, transferred.representation)
                assertEquals(32L, transferred.bytesWritten)
                assertEquals(listOf(F.WATCH, F.WATCH), fixture.requests.map { it.url })
                assertEquals(kind, context.request.kind)
                if (kind == AcquisitionKind.MEDIA) assertIs<ExecutableSubject.CatalogMedia>(playback.context.subject)
                else assertIs<ExecutableSubject.SourceRecordingSubject>(playback.context.subject)
                assertFalse(playback.isLocal)
                assertNull(playback.materializationAssetId)
                assertNull(playback.resource)
            } finally { fixture.close() }
        }
    }

    @Test fun aGenuineEpisodeKeepsItsCatalogIdentityButANeutralShowDoesNotBecomeAnEpisode() = runTest {
        val base = F.request(AcquisitionKind.MEDIA)
        val episode = MediaItem("rss:authored-guid", base.completeTitle, MediaKind.EPISODE,
            SourceRef(SourceProvider.RSS, "authored-guid", feedUrl = "https://podcast.example/feed.xml",
                episodeGuid = "authored-guid", enclosureUrl = "https://podcast.example/never-requested.mp3"),
            showId = "rss:show", episodeNumber = 7)
        val fixture = AcquisitionExecutionFixture()
        try {
            val genuine = F.context(base.copy(metadata = MediaCapture(episode)))
            val neutral = F.context(F.request(AcquisitionKind.SHOW))
            val first = fixture.execution.preparePlayback(genuine, engine)
            val second = fixture.execution.preparePlayback(neutral, engine)
            assertEquals(episode, (first.context.request.metadata as MediaCapture).media)
            assertEquals(episode.id, (first.context.subject as ExecutableSubject.CatalogMedia).mediaId)
            assertIs<ShowCapture>(second.context.request.metadata)
            assertIs<ExecutableSubject.SourceRecordingSubject>(second.context.subject)
            assertEquals(listOf(F.WATCH, F.WATCH), fixture.requests.map { it.url })
        } finally { fixture.close() }
    }

    @Test fun aRealAcquiredInspectionReusesItsReportDespiteAncillaryDisplayMissingFromPureRestore() = runTest {
        var transfers = 0
        val progress = mutableListOf<AcquisitionTransferProgress>()
        val fixture = AcquisitionExecutionFixture(handler = { request, _ -> ResearchResponse(request.url, 200,
            when (Url(request.url).encodedPath) {
                "/results" -> F.search()
                "/watch" -> F.watch()
                else -> error("No second search or catalogue member reload")
            }) }, downloader = YouTubeDownloader { plan, path, report ->
            transfers++
            assertEquals(F.VIDEO_C, plan.mediaId)
            assertEquals(F.WATCH, plan.inspectionUrl)
            assertNull(plan.audio)
            assertTrue(plan.requestHeaders.isEmpty())
            assertNull(plan.credentialRef)
            report(YouTubeDownloadProgress(32, 32, -10))
            // Native JVM adapters may normalize a relative destination to an absolute path.
            YouTubeDownloadResult("/authored-private-root/$path", 32, "audio/mp4; codecs=\"mp4a.40.2\"")
        })
        try {
            val request = F.request(AcquisitionKind.ALBUM)
            val selected = assertIs<SourceAcquisitionResult.Single>(fixture.acquisition.acquire(request))
            assertNotNull(selected.recording.thumbnailUrl)
            assertNotNull(selected.recording.subtitle)
            assertNotNull(selected.recording.durationText)
            val restored = assertIs<SourceAcquisitionResult.Single>(fixture.acquisition.restore(request, selected.receipt))
            assertNull(restored.recording.thumbnailUrl)
            assertNotEquals(selected, restored)
            val inspected = fixture.acquisition.inspect(request, selected)
            val context = F.context(request, selected.receipt)
            val playback = fixture.execution.preparePlayback(context, engine, inspection = inspected)
            val prepared = fixture.execution.prepareDownload(context, engine, inspection = inspected)
            assertNull(prepared.requiredRepresentationId)
            assertEquals(0, transfers)
            val result = fixture.execution.download(prepared, "authored-stage.m4a") { progress += it }
            assertEquals(playback.representationId, result.representation.representationId)
            assertEquals(listOf("/results", "/watch"), fixture.paths())
            assertEquals(F.TITLE, Url(fixture.requests.first().url).parameters["search_query"])
            assertEquals(F.RETURNED_TITLE, result.context.receipt.observation.entry.title)
            assertEquals(F.TITLE, result.context.request.completeTitle)
            assertEquals(listOf(AcquisitionTransferProgress(32, 32, 0)), progress)
            for (value in listOf(context, playback, prepared, prepared.representation, result, inspected)) {
                assertFalse(value.toString().contains("authored-secret"))
            }
            assertEquals(1, transfers)
        } finally { fixture.close() }
    }

    @Test fun restoredMemberBExecutesCWithoutReloadingBOrSearchingTheReturnedCTitle() = runTest {
        val fixture = AcquisitionExecutionFixture()
        try {
            val context = F.context(F.request(AcquisitionKind.COLLECTION_MEMBER))
            val before = context.request.metadata as CollectionMemberCapture
            val result = fixture.execution.download(fixture.execution.prepareDownload(context, engine), "authored-stage.m4a")
            val after = result.context.request.metadata as CollectionMemberCapture
            assertEquals(before, after)
            assertEquals(F.VIDEO_B, after.member.entry.id)
            assertEquals(2, after.member.reference.ordinal)
            assertEquals(CollectionSnapshotId("saved-snapshot"), after.snapshotId)
            assertEquals(AcquisitionRequestId("parent-request"), after.member.reference.parentRequestId)
            assertEquals(F.VIDEO_C, result.context.recording.canonicalId)
            assertEquals(F.TITLE, result.context.receipt.observation.queryTitle)
            assertEquals(listOf(F.WATCH), fixture.requests.map { it.url })
        } finally { fixture.close() }
    }

    @Test fun foreignAndCallerForgedInspectionsAreRejectedWithoutReadingForeignPublicGettersOrIo() = runTest {
        val owner = AcquisitionExecutionFixture()
        val other = AcquisitionExecutionFixture(handler = { _, _ -> error("Foreign receipt may not enter transport") })
        try {
            val context = F.context()
            val single = assertIs<SourceAcquisitionResult.Single>(owner.acquisition.restore(context.request, context.receipt.observation))
            val inspection = owner.acquisition.inspect(context.request, single)
            val forged = object : AcquisitionInspection {
                override val selection: SourceAcquisitionResult.Single get() = error("Do not trust caller-defined capability getters")
            }
            for (invalid in listOf(inspection, forged)) {
                assertDetail("YOUTUBE_ACQUISITION_INSPECTION_MISMATCH") {
                    other.execution.prepareDownload(context, engine, inspection = invalid)
                }
            }
            assertTrue(other.requests.isEmpty())
            assertEquals(1, owner.watches)
        } finally { owner.close(); other.close() }
    }

    @Test fun inspectionRequiresTheFullCapturedRequestNotOnlyItsEquivalentWireSelectionFields() = runTest {
        val fixture = AcquisitionExecutionFixture()
        try {
            val context = F.context()
            val selected = assertIs<SourceAcquisitionResult.Single>(fixture.acquisition.restore(context.request, context.receipt.observation))
            val inspected = fixture.acquisition.inspect(context.request, selected)
            val other = context.copy(request = context.request.copy(capturedEpochMs = context.request.capturedEpochMs + 1))
            assertEquals(context.request.selectionMetadataRevision, other.request.selectionMetadataRevision)
            assertDetail("YOUTUBE_ACQUISITION_INSPECTION_MISMATCH") {
                fixture.execution.preparePlayback(other, engine, inspection = inspected)
            }
            assertEquals(1, fixture.watches)
        } finally { fixture.close() }
    }

    @Test fun optionalInspectionCannotChangeAnyRawReceiptOrRecordingIdentityField() = runTest {
        val fixture = AcquisitionExecutionFixture()
        try {
            val context = F.context()
            val selected = assertIs<SourceAcquisitionResult.Single>(fixture.acquisition.restore(context.request, context.receipt.observation))
            val inspected = fixture.acquisition.inspect(context.request, selected) as YouTubeAcquisitionInspection
            val invalidSelections = listOf(
                selected.copy(requestId = AcquisitionRequestId("foreign-request")),
                selected.copy(receipt = selected.receipt.copy(queryTitle = "another query")),
                selected.copy(receipt = selected.receipt.copy(entry = selected.receipt.entry.copy(publicUrl = F.WATCH + "#changed"))),
                selected.copy(recording = selected.recording.copy(id = F.VIDEO_B)),
                selected.copy(recording = selected.recording.copy(kind = AcquisitionEntryKind.PLAYLIST)),
                selected.copy(recording = selected.recording.copy(title = "other title")),
                selected.copy(recording = selected.recording.copy(publicUrl = F.WATCH + "&other=1")),
            )
            for (wrong in invalidSelections) {
                val corrupt = YouTubeAcquisitionInspection(fixture.client, context.request, wrong, inspected.report)
                assertDetail("YOUTUBE_ACQUISITION_INSPECTION_MISMATCH") {
                    fixture.execution.preparePlayback(context, engine, inspection = corrupt)
                }
            }
            assertEquals(1, fixture.watches)
        } finally { fixture.close() }
    }

    @Test fun optionalReportStillRequiresTheExactCProviderAndInputBeforePlanning() = runTest {
        val fixture = AcquisitionExecutionFixture()
        try {
            val context = F.context()
            val selected = assertIs<SourceAcquisitionResult.Single>(fixture.acquisition.restore(context.request, context.receipt.observation))
            val inspected = fixture.acquisition.inspect(context.request, selected) as YouTubeAcquisitionInspection
            val corrupt = YouTubeAcquisitionInspection(fixture.client, context.request, selected,
                inspected.report.copy(input = "${F.ORIGIN}/watch?v=${F.VIDEO_B}"))
            assertDetail("YOUTUBE_IDENTITY_MISMATCH") {
                fixture.execution.prepareDownload(context, engine, inspection = corrupt)
            }
            assertEquals(1, fixture.watches)
        } finally { fixture.close() }
    }

    @Test fun rawReceiptLocatorsAndIncompleteTitlesAreRevalidatedBeforeAnySourceIo() = runTest {
        val fixture = AcquisitionExecutionFixture(handler = { _, _ -> error("Pure validation must reject before I/O") })
        try {
            val context = F.context()
            val observed = context.receipt.observation
            val raw = observed.entry
            val invalidEntries = listOf(
                raw.copy(publicUrl = F.WATCH + "&sig=authored-secret"), raw.copy(publicUrl = F.WATCH + "#fragment"),
                raw.copy(publicUrl = "${F.ORIGIN}/watch?v=${F.VIDEO_B}"), raw.copy(publicUrl = null),
                raw.copy(title = null), raw.copy(title = ""), raw.copy(title = "x".repeat(16_385)),
            )
            for (entry in invalidEntries) {
                val invalid = context.copy(receipt = context.receipt.copy(observation = observed.copy(entry = entry)))
                assertDetail("YOUTUBE_ACQUISITION_EXECUTION_MISMATCH") { fixture.execution.prepareDownload(invalid, engine) }
            }
            assertTrue(fixture.requests.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun contextConstructionRejectsBrokenBindingSubjectCAndFirstReceiptLinks() {
        val context = F.context()
        assertFailsWith<IllegalArgumentException> { context.copy(binding = context.binding.copy(firstReceiptId = FirstResultReceiptId("other"))) }
        assertFailsWith<IllegalArgumentException> { context.copy(binding = context.binding.copy(subjectId = ExecutableSubjectId("other"))) }
        assertFailsWith<IllegalArgumentException> { context.copy(binding = context.binding.copy(recordingId = SourceRecordingId("other"))) }
        assertFailsWith<IllegalArgumentException> { context.copy(recording = context.recording.copy(canonicalId = F.VIDEO_B)) }
        assertFailsWith<IllegalArgumentException> { context.copy(subject = ExecutableSubject.CatalogMedia(ExecutableSubjectId("other"), "other")) }
        assertFailsWith<IllegalArgumentException> { context.copy(receipt = context.receipt.copy(observation = context.receipt.observation.copy(queryTitle = "other"))) }
        assertFailsWith<IllegalArgumentException> { context.copy(receipt = context.receipt.copy(observation = context.receipt.observation.copy(
            entry = context.receipt.observation.entry.copy(ordinal = 1)))) }
    }

    @Test fun malformedSavedBDisplayScopeAndOccurrenceCannotAuthorizeExecutionOfC() = runTest {
        val fixture = AcquisitionExecutionFixture(handler = { _, _ -> error("Corrupt B cannot reach C inspection") })
        try {
            val request = F.request(AcquisitionKind.COLLECTION_MEMBER)
            val metadata = request.metadata as CollectionMemberCapture
            val member = metadata.member
            val invalid = listOf(
                member.copy(display = null), member.copy(display = member.display!!.copy(id = F.VIDEO_C)),
                member.copy(entry = member.entry.copy(issue = SourceEntryIssue.UNAVAILABLE)),
                member.copy(reference = member.reference.copy(scope = CollectionScope.CHANNEL_VIDEOS)),
                member.copy(reference = member.reference.copy(ordinal = 3)),
                member.copy(reference = member.reference.copy(parentRequestId = request.id)),
            )
            for (bad in invalid) {
                assertDetail("YOUTUBE_ACQUISITION_EXECUTION_MISMATCH") {
                    fixture.execution.preparePlayback(F.context(request.copy(metadata = metadata.copy(member = bad))), engine)
                }
            }
            assertTrue(fixture.requests.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun aPreparedDownloadBelongsToItsIssuingEngineNotMerelyItsClientOrPublicDescriptor() = runTest {
        val fixture = AcquisitionExecutionFixture()
        val other = AcquisitionExecutionFixture(handler = { _, _ -> error("Foreign engine cannot inspect") })
        try {
            val prepared = fixture.execution.prepareDownload(F.context(), engine)
            val sameClientOtherEngine = YouTubeAcquisitionExecutionGateway(fixture.source)
            assertDetail("YOUTUBE_PREPARED_DOWNLOAD_MISMATCH") { sameClientOtherEngine.download(prepared, "other.m4a") }
            assertDetail("YOUTUBE_PREPARED_DOWNLOAD_MISMATCH") { other.execution.download(prepared, "other.m4a") }
            val forged = object : PreparedAcquisitionDownload {
                override val context: AcquisitionExecutionContext get() = error("Do not read a foreign public getter")
                override val requiredRepresentationId: String? get() = error("Do not read a foreign public getter")
                override val representation: AcquisitionRepresentation get() = error("Do not read a foreign public getter")
            }
            assertDetail("YOUTUBE_PREPARED_DOWNLOAD_MISMATCH") { fixture.execution.download(forged, "other.m4a") }
            // Rejection by another engine does not consume the genuine owner's capability.
            assertEquals(32L, fixture.execution.download(prepared, "authored-stage.m4a").bytesWritten)
            assertEquals(1, fixture.watches)
            assertTrue(other.requests.isEmpty())
        } finally { fixture.close(); other.close() }
    }

    @Test fun everyTerminalTransferIncludingAnInvalidDestinationConsumesItsPreparedCapability() = runTest {
        for (case in listOf("success", "failure", "blank")) {
            var transfers = 0
            val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { _, path, _ ->
                transfers++
                if (case == "failure") error("authored-secret native failure")
                YouTubeDownloadResult(path, 32, "audio/mp4")
            })
            try {
                val prepared = fixture.execution.prepareDownload(F.context(), engine)
                if (case == "success") fixture.execution.download(prepared, "authored-stage.m4a")
                else assertFailsWith<VeyraException> { fixture.execution.download(prepared, if (case == "blank") " " else "authored-stage.m4a") }
                assertDetail("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED") { fixture.execution.download(prepared, "another-stage.m4a") }
                assertEquals(if (case == "blank") 0 else 1, transfers)
                assertEquals(1, fixture.watches)
            } finally { fixture.close() }
        }
    }

    @Test fun concurrentPreparedConsumptionAdmitsOnlyOneNativeTransfer() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { _, path, _ ->
            transfers++; entered.complete(Unit); release.await()
            YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val prepared = fixture.execution.prepareDownload(F.context(), engine)
            val first = async { fixture.execution.download(prepared, "authored-stage.m4a") }
            entered.await()
            assertDetail("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED") { fixture.execution.download(prepared, "other-stage.m4a") }
            release.complete(Unit)
            assertEquals(32L, first.await().bytesWritten)
            assertEquals(1, transfers)
            assertEquals(1, fixture.watches)
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test fun cancellationAtEntryConsumesThePreparedObjectWithoutStartingTransfer() = runTest {
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { _, path, _ ->
            transfers++; YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        try {
            val prepared = fixture.execution.prepareDownload(F.context(), engine)
            var canceled = false
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                try { fixture.execution.download(prepared, "authored-stage.m4a") }
                catch (_: CancellationException) { canceled = true }
            }
            job.join()
            assertTrue(canceled)
            assertDetail("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED") { fixture.execution.download(prepared, "other-stage.m4a") }
            assertEquals(0, transfers)
            assertEquals(1, fixture.watches)
        } finally { fixture.close() }
    }

    @Test fun closedSourceRejectsTypedPreparationAndConsumesAnExistingTransferAttemptWithoutIo() = runTest {
        var transfers = 0
        val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { _, path, _ ->
            transfers++; YouTubeDownloadResult(path, 32, "audio/mp4")
        })
        val context = F.context()
        val prepared = fixture.execution.prepareDownload(context, engine)
        fixture.close()
        assertDetail("YOUTUBE_SOURCE_CLOSED") { fixture.execution.preparePlayback(context, engine) }
        assertDetail("YOUTUBE_SOURCE_CLOSED") { fixture.execution.download(prepared, "authored-stage.m4a") }
        assertDetail("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED") { fixture.execution.download(prepared, "authored-stage.m4a") }
        assertEquals(1, fixture.watches)
        assertEquals(0, transfers)
    }

    @Test fun malformedRequiredRepresentationKeysFailBeforeInspection() = runTest {
        val fixture = AcquisitionExecutionFixture(handler = { _, _ -> error("Invalid selection must be rejected before I/O") })
        try {
            for (key in listOf("", "140", "yt-audio-v1:" + "z".repeat(64))) {
                assertDetail("YOUTUBE_INVALID_SELECTION") { fixture.execution.preparePlayback(F.context(), engine, key) }
                assertDetail("YOUTUBE_INVALID_SELECTION") { fixture.execution.prepareDownload(F.context(), engine, key) }
            }
            assertTrue(fixture.requests.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun unsupportedDefaultAudioCannotBeReplacedByAnotherLanguagesSupportedCodec() = runTest {
        val fixture = AcquisitionExecutionFixture(handler = { request, _ -> ResearchResponse(request.url, 200, F.watch(listOf(
            F.format(itag = 251, mime = "audio/webm", codec = "opus", language = "en", default = true),
            F.format(language = "ar", default = false),
        ))) })
        try {
            assertDetail("YOUTUBE_NATIVE_AUDIO_UNSUPPORTED") { fixture.execution.prepareDownload(F.context(), engine) }
            assertEquals(1, fixture.watches)
        } finally { fixture.close() }
    }

    @Test fun invalidProgressOutputLengthAndMimeNeverProduceTransferEvidence() = runTest {
        for (case in listOf("negative", "over-total", "empty", "short", "wrong-mime")) {
            var transfers = 0
            var visibleProgress = 0
            val fixture = AcquisitionExecutionFixture(downloader = YouTubeDownloader { _, path, report ->
                transfers++
                if (case == "negative") report(YouTubeDownloadProgress(-1))
                if (case == "over-total") report(YouTubeDownloadProgress(33, 32))
                YouTubeDownloadResult(path, when (case) { "empty" -> 0L; "short" -> 31L; else -> 32L },
                    if (case == "wrong-mime") "audio/webm" else "audio/mp4")
            })
            try {
                val prepared = fixture.execution.prepareDownload(F.context(), engine)
                val error = assertFailsWith<VeyraException> {
                    fixture.execution.download(prepared, "authored-stage.m4a") { visibleProgress++ }
                }
                assertEquals(FailureCode.CORRUPT_MEDIA, error.failure.code)
                assertEquals(0, visibleProgress)
                assertEquals(1, transfers)
                assertDetail("YOUTUBE_PREPARED_DOWNLOAD_CONSUMED") { fixture.execution.download(prepared, "authored-stage.m4a") }
            } finally { fixture.close() }
        }
    }

    private suspend fun assertDetail(detail: String, block: suspend () -> Unit) {
        val failure = assertFailsWith<VeyraException> { block() }
        assertEquals(detail, failure.failure.detail)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains("authored-secret"))
    }
}
