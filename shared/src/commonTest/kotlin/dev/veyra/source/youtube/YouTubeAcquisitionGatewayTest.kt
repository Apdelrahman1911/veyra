package dev.veyra.source.youtube

import dev.kmpyt.YouTubeClient
import dev.kmpyt.extraction.ResearchRequest
import dev.kmpyt.extraction.ResearchResponse
import dev.kmpyt.extraction.ResearchTransport
import dev.veyra.model.*
import dev.veyra.source.execution.CollectionContinuation
import dev.veyra.source.release.*
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Actual upstream public client/parser, authored fixtures only. No native, live or Room proof. */
class YouTubeAcquisitionGatewayTest {
    @Test fun everyRequestKindSubmitsItsCompleteUnicodeTitleOnceWithoutCatalogSubstitution() = runTest {
        val title = "  " + "مرحبا 🎵 漢字".repeat(500) + " — final edition suffix  "
        for (kind in AcquisitionKind.entries) {
            val request = request(kind, title)
            withGateway({ response(it, search(video(VIDEO_C, "Different returned recording"))) }) { gateway, transport, _ ->
                val result = assertIs<SourceAcquisitionResult.Single>(gateway.acquire(request))
                assertEquals(request.id, result.requestId)
                assertEquals(title, result.receipt.queryTitle)
                assertEquals(title, Url(transport.requests.single().url).parameters["search_query"])
                assertEquals(title, request.metadata.completeTitle)
                assertEquals(VIDEO_C, result.receipt.entry.id)
                assertEquals(0, result.receipt.entry.ordinal)
                assertEquals("Different returned recording", result.recording.title)
                assertNotEquals(request.completeTitle, result.recording.title)
            }
        }
    }

    @Test fun genuineEpisodeCaptureRemainsAnEpisodeWhileItsSourceResultIsOnlyARecording() = runTest {
        val episode = MediaItem("rss:authored-guid", PARENT_TITLE, MediaKind.EPISODE,
            SourceRef(SourceProvider.RSS, "authored-guid", feedUrl = "https://podcast.example/feed.xml",
                episodeGuid = "authored-guid", enclosureUrl = "https://podcast.example/not-the-execution.mp3"),
            showId = "rss:authored-show", episodeNumber = 7, durationMs = 900_000)
        val request = request(metadata = MediaCapture(episode))
        withGateway({ response(it, search(video(VIDEO_C, "Different C"))) }) { gateway, transport, _ ->
            val selected = assertIs<SourceAcquisitionResult.Single>(gateway.acquire(request))
            assertEquals(episode, assertIs<MediaCapture>(request.metadata).media)
            assertEquals(VIDEO_C, selected.recording.id)
            assertEquals(AcquisitionEntryKind.VIDEO, selected.recording.kind)
            assertEquals(listOf("/results"), transport.requests.map { Url(it.url).encodedPath })
        }
    }

    @Test fun rawChannelHandleUrlIsNotReplacedByItsSeparateStableIdentity() = runTest {
        withGateway({ response(it, when (Url(it.url).encodedPath) {
            "/results" -> search(channel(), video(VIDEO_C))
            "/channel/$CHANNEL_ID/videos" -> html(channelData())
            else -> error("Only the selected channel videos scope may be observed")
        }) }) { gateway, transport, _ ->
            val request = request(AcquisitionKind.SHOW)
            val selection = assertIs<SourceAcquisitionResult.Collection>(gateway.acquire(request))
            assertEquals("$ORIGIN/@authored/videos", selection.receipt.entry.publicUrl)
            assertEquals("$ORIGIN/channel/$CHANNEL_ID", selection.receipt.canonicalUrl)
            assertEquals(CollectionIdentity(AcquisitionEntryKind.CHANNEL, CHANNEL_ID, "$ORIGIN/channel/$CHANNEL_ID"), selection.identity)
            assertEquals(CollectionScope.CHANNEL_VIDEOS, selection.scope)
            val page = gateway.collection(request, selection)
            val observation = assertNotNull(page.data.observation)
            assertEquals(CollectionTitleOrigin.CHANNEL_METADATA, observation.titleOrigin)
            assertEquals(setOf(CollectionIdentityEvidence.CHANNEL_METADATA), observation.identityEvidence)
            assertEquals("$ORIGIN/channel/$CHANNEL_ID/videos", observation.sourceUrl)
            assertEquals(CollectionConsistency.UNVERSIONED_OBSERVATION, observation.consistency)
            assertEquals(listOf("/results", "/channel/$CHANNEL_ID/videos"), transport.requests.map { Url(it.url).encodedPath })
        }
    }

    @Test fun firstPlaylistIsACollectionNotItsFirstChildOrALaterSearchVideo() = runTest {
        withGateway({ response(it, search(playlist(), video(VIDEO_C))) }) { gateway, transport, _ ->
            val result = assertIs<SourceAcquisitionResult.Collection>(gateway.acquire(request(AcquisitionKind.ALBUM)))
            assertEquals(AcquisitionEntryKind.PLAYLIST, result.receipt.entry.kind)
            assertEquals(PLAYLIST_ID, result.identity.id)
            assertEquals(CollectionScope.PLAYLIST_MEMBERS, result.scope)
            assertEquals(1, transport.requests.size)
        }
    }

    @Test fun unavailableUnknownAndMalformedRawFirstNeverPromoteALaterVideo() = runTest {
        val cases = listOf(
            video(VIDEO_B, "[Private video]", playable = false) to AcquisitionProblemCode.FIRST_RESULT_UNAVAILABLE,
            wrap("futureContentRenderer", video(VIDEO_B)) to AcquisitionProblemCode.FIRST_RESULT_UNSUPPORTED,
            video(VIDEO_B, "x".repeat(16_385)) to AcquisitionProblemCode.INVALID_FIRST_RESULT,
        )
        for ((first, problem) in cases) {
            withGateway({ response(it, search(first, video(VIDEO_C))) }) { gateway, transport, _ ->
                val result = assertIs<SourceAcquisitionResult.Unplayable>(gateway.acquire(request()))
                assertEquals(problem, result.problem.code)
                assertEquals(0, assertNotNull(result.receipt).entry.ordinal)
                assertNotEquals(VIDEO_C, result.receipt.entry.id)
                if (problem == AcquisitionProblemCode.INVALID_FIRST_RESULT) {
                    assertNull(result.receipt.entry.title)
                    assertEquals(SourceEntryIssue.TEXT_LIMIT, result.receipt.entry.issue)
                }
                assertEquals(1, transport.requests.size)
            }
        }
    }

    @Test fun emptyAndFailedSearchHaveDistinctProblemsAndNoInventedReceipt() = runTest {
        val cases = listOf(
            Triple(200, search(), null),
            Triple(403, "authored response not for persistence", SourceProblemCode.ACCESS_REQUIRED),
            Triple(404, "authored response not for persistence", SourceProblemCode.NOT_FOUND),
            Triple(429, "authored response not for persistence", SourceProblemCode.RATE_LIMITED),
            Triple(503, "authored response not for persistence", SourceProblemCode.HTTP_ERROR),
            Triple(200, "unreadable authored response", SourceProblemCode.INVALID_RESPONSE),
        )
        for ((status, body, sourceProblem) in cases) {
            withGateway({ response(it, body, status) }) { gateway, transport, _ ->
                val result = assertIs<SourceAcquisitionResult.Unplayable>(gateway.acquire(request()))
                assertEquals(if (sourceProblem == null) AcquisitionProblemCode.NO_FIRST_RESULT else AcquisitionProblemCode.SOURCE_FAILURE,
                    result.problem.code)
                assertEquals(sourceProblem, result.problem.sourceProblem?.code)
                if (status != 200) assertEquals(status, result.problem.sourceProblem?.httpStatus)
                assertNull(result.receipt)
                assertEquals(1, transport.requests.size)
                assertFalse(result.toString().contains("authored response"))
            }
        }
    }

    @Test fun invalidTitlesAndMetadataRevisionFailBeforeIoButExactUtf16LimitIsAccepted() = runTest {
        withGateway({ response(it, search(video())) }) { gateway, transport, _ ->
            for (title in listOf("", "  ", "bad\nline", "bad\u0085control", "bad\uD800", "bad\uDC00", "x".repeat(16_385))) {
                val result = assertIs<SourceAcquisitionResult.Unplayable>(gateway.acquire(request(title = title)))
                assertEquals(AcquisitionProblemCode.INVALID_REQUEST, result.problem.code)
                assertNull(result.receipt)
            }
            assertEquals(AcquisitionProblemCode.INVALID_REQUEST, assertIs<SourceAcquisitionResult.Unplayable>(
                gateway.acquire(request().copy(providerRevision = "r".repeat(513)))).problem.code)
            assertTrue(transport.requests.isEmpty())
            val exact = "x".repeat(16_382) + "🎵"
            val result = assertIs<SourceAcquisitionResult.Single>(gateway.acquire(request(title = exact)))
            assertEquals(exact, result.receipt.queryTitle)
            assertEquals(exact, Url(transport.requests.single().url).parameters["search_query"])
        }
    }

    @Test fun restoringSavedSingleAndCollectionReceiptsIsPureWithoutALiveSelectionMap() = runTest {
        withGateway({ error("Pure restore must not perform I/O") }) { gateway, transport, _ ->
            val request = request(AcquisitionKind.RELEASE)
            val singleReceipt = singleReceipt(request)
            val single = assertIs<SourceAcquisitionResult.Single>(gateway.restore(request, singleReceipt))
            assertEquals(singleReceipt, single.receipt)
            assertEquals(VIDEO_C, single.recording.id)
            val collectionReceipt = collectionReceipt(request)
            val collection = assertIs<SourceAcquisitionResult.Collection>(gateway.restore(request, collectionReceipt))
            assertEquals(collectionReceipt, collection.receipt)
            assertEquals(PLAYLIST_ID, collection.identity.id)
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test fun restorePreservesRejectedReceiptButNeverAcceptsBrokenLinkageOrCanonicalUrl() = runTest {
        withGateway({ error("Invalid receipts must not perform I/O") }) { gateway, transport, _ ->
            val request = request()
            val valid = singleReceipt(request)
            val cases = listOf(
                valid.copy(requestId = AcquisitionRequestId("other-request")),
                valid.copy(queryTitle = "Different query"),
                valid.copy(canonicalUrl = "$ORIGIN/watch?v=$VIDEO_B"),
                valid.copy(entry = valid.entry.copy(ordinal = 2)),
                valid.copy(entry = valid.entry.copy(title = "bad\uD800")),
                valid.copy(entry = valid.entry.copy(publicUrl = "$ORIGIN/watch?v=$VIDEO_C&sig=authored-capability")),
            )
            for (receipt in cases) {
                val result = assertIs<SourceAcquisitionResult.Unplayable>(gateway.restore(request, receipt))
                assertEquals(AcquisitionProblemCode.INVALID_FIRST_RESULT, result.problem.code)
                assertEquals(receipt, result.receipt)
            }
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test fun parentSingleInspectsItsExactReceiptWithOnlySameVideoMetadataSupplementation() = runTest {
        for (withThumbnail in listOf(true, false)) {
            withGateway({ input -> response(input, when (input.stage) {
                "youtube-browse" -> search(video(VIDEO_C, "Returned full release recording"))
                "youtube-watch" -> { assertEquals("$ORIGIN/watch?v=$VIDEO_C", input.url); watch(withThumbnail = withThumbnail) }
                "youtube-oembed" -> {
                    assertFalse(withThumbnail)
                    assertEquals("$ORIGIN/watch?v=$VIDEO_C", Url(input.url).parameters["url"])
                    assertEquals("json", Url(input.url).parameters["format"])
                    oembed()
                }
                else -> error("No media bytes, alternate source or second query")
            }) }) { gateway, transport, client ->
                val request = request(AcquisitionKind.ALBUM)
                val selection = assertIs<SourceAcquisitionResult.Single>(gateway.acquire(request))
                val inspection = assertIs<YouTubeAcquisitionInspection>(gateway.inspect(request, selection))
                assertSame(client, inspection.owner)
                assertSame(selection, inspection.selection)
                assertEquals(request, inspection.request)
                assertEquals(VIDEO_C, inspection.report.media.single().id)
                assertEquals("Inspected source recording", inspection.report.media.single().title)
                assertEquals("https://i.ytimg.com/vi/$VIDEO_C/hqdefault.jpg", inspection.report.media.single().thumbnailUrl)
                assertEquals(listOf("youtube-browse", "youtube-watch") + if (withThumbnail) emptyList() else listOf("youtube-oembed"),
                    transport.requests.map { it.stage })
                assertEquals(listOf(PARENT_TITLE), transport.requests.mapNotNull { Url(it.url).parameters["search_query"] })
                assertFalse(inspection.toString().contains("authored-capability"))
                assertFalse(inspection.toString().contains(selection.recording.title))
            }
        }
    }

    @Test fun anUnsuccessfulInspectionRemainsAnOpaqueReportNotAnAcquisitionFailureOrPlan() = runTest {
        withGateway({ input ->
            assertEquals("youtube-watch", input.stage)
            response(input, watch(status = "LOGIN_REQUIRED"))
        }) { gateway, transport, _ ->
            val request = request(AcquisitionKind.SHOW)
            val selection = assertIs<SourceAcquisitionResult.Single>(gateway.restore(request, singleReceipt(request)))
            val inspection = assertIs<YouTubeAcquisitionInspection>(gateway.inspect(request, selection))
            assertSame(selection, inspection.selection)
            assertEquals(FailureCode.PROVIDER_ACCESS_REQUIRED, inspection.report.sourceFailure()?.code)
            assertEquals(listOf("youtube-watch"), transport.requests.map { it.stage })
        }
    }

    @Test fun forgedSingleDisplayOrRequestIsRejectedBeforeInspectionIo() = runTest {
        withGateway({ error("Tampered selection must not perform I/O") }) { gateway, transport, _ ->
            val request = request()
            val selected = assertIs<SourceAcquisitionResult.Single>(gateway.restore(request, singleReceipt(request)))
            for (invalid in listOf(
                selected.copy(requestId = AcquisitionRequestId("other-request")),
                selected.copy(recording = selected.recording.copy(id = VIDEO_B)),
                selected.copy(recording = selected.recording.copy(title = "Changed returned title")),
                selected.copy(recording = selected.recording.copy(publicUrl = "$ORIGIN/watch?v=$VIDEO_B")),
                selected.copy(recording = selected.recording.copy(kind = AcquisitionEntryKind.PLAYLIST)),
                selected.copy(receipt = selected.receipt.copy(entry = selected.receipt.entry.copy(ordinal = 1))),
            )) assertInvalidSelection { gateway.inspect(request, invalid) }
            assertInvalidSelection { gateway.inspect(request.copy(id = AcquisitionRequestId("other-request")), selected) }
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test fun forgedCollectionIdentityScopeOrRequestIsRejectedBeforeListingIo() = runTest {
        withGateway({ error("Tampered selection must not perform I/O") }) { gateway, transport, _ ->
            val request = request(AcquisitionKind.RELEASE)
            val selected = assertIs<SourceAcquisitionResult.Collection>(gateway.restore(request, collectionReceipt(request)))
            for (invalid in listOf(
                selected.copy(requestId = AcquisitionRequestId("other-request")),
                selected.copy(scope = CollectionScope.CHANNEL_VIDEOS),
                selected.copy(identity = selected.identity.copy(id = "PLdifferent")),
                selected.copy(identity = selected.identity.copy(canonicalUrl = "$ORIGIN/playlist?list=PLdifferent")),
                selected.copy(receipt = selected.receipt.copy(queryTitle = "Different query")),
            )) assertInvalidSelection { gateway.collection(request, invalid) }
            assertInvalidSelection { gateway.collection(request.copy(id = AcquisitionRequestId("other-request")), selected) }
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test fun savedObservedMemberBQueriesBOnceThenRestoresAndInspectsCWithoutListingReload() = runTest {
        // Host values survive three separately closed fixture clients. This is NOT a Room or
        // process-restart test; it checks the public API's reconstructible request/receipt seam.
        lateinit var savedRequest: AcquisitionRequest
        lateinit var savedReceipt: FirstResultObservation
        withGateway({ response(it, when (Url(it.url).encodedPath) {
            "/results" -> search(playlist())
            "/playlist" -> html(playlistData(listOf(video(VIDEO_B, MEMBER_TITLE, "playlistVideoRenderer"))))
            else -> error("Parent selection/listing only")
        }) }) { gateway, transport, _ ->
            val parent = request(AcquisitionKind.RELEASE)
            val selected = assertIs<SourceAcquisitionResult.Collection>(gateway.acquire(parent))
            val member = gateway.collection(parent, selected).data.members.single()
            savedRequest = request(metadata = CollectionMemberCapture(CollectionSnapshotId("saved-snapshot"), member), id = "saved-member-request")
            assertEquals(VIDEO_B, member.entry.id)
            assertEquals(parent.id, member.reference.parentRequestId)
            assertEquals(2, transport.requests.size)
        }
        withGateway({ response(it, search(video(VIDEO_C, "Different execution C"))) }) { gateway, transport, _ ->
            val selected = assertIs<SourceAcquisitionResult.Single>(gateway.acquire(savedRequest))
            savedReceipt = selected.receipt
            assertEquals(MEMBER_TITLE, selected.receipt.queryTitle)
            assertEquals(VIDEO_C, selected.recording.id)
            assertEquals(MEMBER_TITLE, Url(transport.requests.single().url).parameters["search_query"])
            val observedB = assertIs<CollectionMemberCapture>(savedRequest.metadata).member
            assertEquals(VIDEO_B, observedB.entry.id)
            assertEquals(MEMBER_TITLE, observedB.entry.title)
        }
        withGateway({ input ->
            assertEquals("youtube-watch", input.stage)
            assertEquals("$ORIGIN/watch?v=$VIDEO_C", input.url)
            response(input, watch())
        }) { gateway, transport, _ ->
            val selected = assertIs<SourceAcquisitionResult.Single>(gateway.restore(savedRequest, savedReceipt))
            assertTrue(transport.requests.isEmpty())
            val inspection = assertIs<YouTubeAcquisitionInspection>(gateway.inspect(savedRequest, selected))
            assertEquals(savedRequest, inspection.request)
            assertEquals(VIDEO_C, inspection.report.media.single().id)
            assertEquals(listOf("youtube-watch"), transport.requests.map { it.stage })
        }
    }

    @Test fun unavailableAndCorruptSavedMembersCannotStartAQuery() = runTest {
        val member = member()
        val nonPlayable = listOf(
            member.copy(entry = member.entry.copy(issue = SourceEntryIssue.UNAVAILABLE), display = null),
            member.copy(display = null),
            member.copy(display = member.display!!.copy(id = VIDEO_C)),
            member.copy(display = member.display!!.copy(title = "Other member")),
            member.copy(entry = member.entry.copy(publicUrl = "$ORIGIN/watch?v=$VIDEO_C")),
            member.copy(entry = member.entry.copy(id = "invalid-video")),
            member.copy(entry = member.entry.copy(kind = AcquisitionEntryKind.PLAYLIST)),
        )
        val invalidReferences = listOf(
            member.copy(entry = member.entry.copy(ordinal = 1)),
            member.copy(reference = member.reference.copy(parentRequestId = AcquisitionRequestId("member-request"))),
            member.copy(reference = member.reference.copy(ordinal = 5_000), entry = member.entry.copy(ordinal = 5_000)),
            member.copy(reference = member.reference.copy(scope = CollectionScope.CHANNEL_VIDEOS)),
            member.copy(reference = member.reference.copy(identity = member.reference.identity.copy(id = "PLother"))),
        )
        withGateway({ error("Invalid saved B must not search") }) { gateway, transport, _ ->
            for ((value, problem) in nonPlayable.map { it to AcquisitionProblemCode.MEMBER_NOT_PLAYABLE } +
                invalidReferences.map { it to AcquisitionProblemCode.INVALID_REQUEST }) {
                val request = request(metadata = CollectionMemberCapture(CollectionSnapshotId("saved-snapshot"), value), id = "member-request")
                val result = assertIs<SourceAcquisitionResult.Unplayable>(gateway.acquire(request))
                assertEquals(problem, result.problem.code)
                assertNull(result.receipt)
                val saved = singleReceipt(request)
                val restored = assertIs<SourceAcquisitionResult.Unplayable>(gateway.restore(request, saved))
                assertEquals(problem, restored.problem.code)
                assertEquals(saved, restored.receipt)
            }
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test fun aMembersFirstCollectionIsVisibleFailureAndNeverRecursiveAutoplay() = runTest {
        for (first in listOf(playlist(), channel())) {
            withGateway({ response(it, search(first, video(VIDEO_C))) }) { gateway, transport, _ ->
                val result = assertIs<SourceAcquisitionResult.Unplayable>(gateway.acquire(request(AcquisitionKind.COLLECTION_MEMBER, MEMBER_TITLE)))
                assertEquals(AcquisitionProblemCode.MEMBER_NOT_PLAYABLE, result.problem.code)
                assertNotNull(result.receipt)
                assertNotEquals(AcquisitionEntryKind.VIDEO, result.receipt.entry.kind)
                assertEquals(MEMBER_TITLE, result.receipt.queryTitle)
                assertEquals(1, transport.requests.size)
            }
        }
    }

    @Test fun collectionDeltasPreserveDuplicatePositionsAndEarlierUnavailableHoles() = runTest {
        withGateway({ response(it, when (Url(it.url).encodedPath) {
            "/results" -> search(playlist())
            "/playlist" -> html(playlistData(listOf(
                video(VIDEO_B, MEMBER_TITLE, "playlistVideoRenderer"),
                video(VIDEO_B, "[Private video]", "playlistVideoRenderer", playable = false),
                video(VIDEO_B, MEMBER_TITLE, "playlistVideoRenderer"), token(),
            )))
            "/youtubei/v1/browse" -> append(listOf(video(VIDEO_C, "Next C", "playlistVideoRenderer"),
                video(VIDEO_B, MEMBER_TITLE, "playlistVideoRenderer"))).toString()
            else -> error("Only selected playlist traversal")
        }) }) { gateway, transport, _ ->
            val request = request(AcquisitionKind.ALBUM)
            val selection = assertIs<SourceAcquisitionResult.Collection>(gateway.acquire(request))
            val first = gateway.collection(request, selection)
            assertEquals(CollectionPageDisposition.OBSERVED_DELTA, first.data.disposition)
            assertEquals(CollectionTraversal.MORE_AVAILABLE, first.data.extent.traversal)
            assertEquals(0, first.data.pageIndex)
            assertEquals(0, first.data.startOrdinal)
            assertEquals(listOf(0, 1, 2), first.data.members.map { it.reference.ordinal })
            assertEquals(listOf(VIDEO_B, VIDEO_B, VIDEO_B), first.data.members.map { it.entry.id })
            assertNull(first.data.members[1].display)
            assertEquals(SourceEntryIssue.UNAVAILABLE, first.data.members[1].entry.issue)
            assertEquals(3, first.data.extent.observedMemberCount)
            assertEquals(1, first.data.extent.nonPlayableMemberCount)
            val next = gateway.more(assertNotNull(first.continuation))
            assertEquals(CollectionPageDisposition.OBSERVED_DELTA, next.data.disposition)
            assertEquals(1, next.data.pageIndex)
            assertEquals(3, next.data.startOrdinal)
            assertEquals(listOf(3, 4), next.data.members.map { it.reference.ordinal })
            assertEquals(listOf(VIDEO_C, VIDEO_B), next.data.members.map { it.entry.id })
            assertEquals(5, next.data.extent.observedMemberCount)
            assertEquals(1, next.data.extent.nonPlayableMemberCount)
            assertTrue(next.data.extent.hasKnownHoles)
            assertEquals(CollectionTraversal.OBSERVED_TO_END, next.data.extent.traversal)
            assertNull(next.continuation)
            assertEquals(first.data.observation, next.data.observation)
            (first.data.members + next.data.members).forEach {
                assertEquals(first.data.observationId, it.reference.observationId)
                assertEquals(request.id, it.reference.parentRequestId)
                assertEquals(selection.identity, it.reference.identity)
                assertEquals(selection.scope, it.reference.scope)
                assertEquals(it.entry.ordinal, it.reference.ordinal)
            }
            assertEquals(3, transport.requests.size)
            assertEquals("POST", transport.requests.last().method)
            assertFalse(first.data.toString().contains(AUTHORED_CURSOR))
            assertFalse(first.toString().contains(AUTHORED_CURSOR))
            assertFalse(first.continuation.toString().contains(AUTHORED_CURSOR))
        }
    }

    @Test fun emptyObservedListingAndFailedInitialAttemptAreNotTheSamePage() = runTest {
        for (failed in listOf(false, true)) {
            withGateway({ input -> response(input, if (failed) "authored denied response" else html(playlistData(emptyList())),
                if (failed) 403 else 200) }) { gateway, transport, _ ->
                val request = request(AcquisitionKind.SOURCE_COLLECTION)
                val selection = assertIs<SourceAcquisitionResult.Collection>(gateway.restore(request, collectionReceipt(request)))
                val page = gateway.collection(request, selection)
                assertTrue(page.data.members.isEmpty())
                assertEquals(0, page.data.extent.observedMemberCount)
                assertEquals(0, page.data.extent.nonPlayableMemberCount)
                assertNull(page.continuation)
                if (failed) {
                    assertEquals(CollectionPageDisposition.FAILED_ATTEMPT, page.data.disposition)
                    assertEquals(CollectionTraversal.INCOMPLETE, page.data.extent.traversal)
                    assertEquals(CollectionProblemCode.SOURCE_FAILURE, page.data.extent.problem?.code)
                    assertEquals(SourceProblemCode.ACCESS_REQUIRED, page.data.extent.problem?.sourceProblem?.code)
                    assertNull(page.data.observation)
                } else {
                    assertEquals(CollectionPageDisposition.OBSERVED_DELTA, page.data.disposition)
                    assertEquals(CollectionTraversal.OBSERVED_TO_END, page.data.extent.traversal)
                    assertNotNull(page.data.observation)
                    assertNull(page.data.extent.problem)
                }
                assertEquals(1, transport.requests.size)
            }
        }
    }

    @Test fun retryableContinuationFailureRetainsFrontierHolesAndTheSameOpaqueCursor() = runTest {
        var continuationCalls = 0
        withGateway({ input -> when (Url(input.url).encodedPath) {
            "/playlist" -> response(input, html(playlistData(listOf(
                video(VIDEO_B, "[Private video]", "playlistVideoRenderer", playable = false),
                video(VIDEO_B, MEMBER_TITLE, "playlistVideoRenderer"), token(),
            ))))
            "/youtubei/v1/browse" -> {
                continuationCalls++
                if (continuationCalls == 1) response(input, "authored temporary failure", 503)
                else response(input, append(listOf(video(VIDEO_C, "C", "playlistVideoRenderer"))).toString())
            }
            else -> error("No query or fallback")
        } }) { gateway, transport, _ ->
            val request = request(AcquisitionKind.RELEASE)
            val selected = assertIs<SourceAcquisitionResult.Collection>(gateway.restore(request, collectionReceipt(request)))
            val first = gateway.collection(request, selected)
            val cursor = assertNotNull(first.continuation)
            val failed = gateway.more(cursor)
            assertEquals(1, continuationCalls, "One explicit call cannot hide an automatic retry")
            assertSame(cursor, failed.continuation)
            assertEquals(CollectionPageDisposition.FAILED_ATTEMPT, failed.data.disposition)
            assertEquals(CollectionTraversal.INCOMPLETE, failed.data.extent.traversal)
            assertEquals(first.data.observation, failed.data.observation)
            assertEquals(first.data.observationId, failed.data.observationId)
            assertEquals(1, failed.data.pageIndex)
            assertEquals(2, failed.data.startOrdinal)
            assertTrue(failed.data.members.isEmpty())
            assertEquals(2, failed.data.extent.observedMemberCount)
            assertEquals(1, failed.data.extent.nonPlayableMemberCount)
            assertEquals(SourceProblem(SourceProblemCode.HTTP_ERROR, 503), failed.data.extent.problem?.sourceProblem)
            val next = gateway.more(assertNotNull(failed.continuation))
            assertEquals(2, continuationCalls)
            assertEquals(CollectionPageDisposition.OBSERVED_DELTA, next.data.disposition)
            assertEquals(1, next.data.pageIndex)
            assertEquals(2, next.data.startOrdinal)
            assertEquals(listOf(2), next.data.members.map { it.reference.ordinal })
            assertEquals(3, next.data.extent.observedMemberCount)
            assertEquals(1, next.data.extent.nonPlayableMemberCount)
            val consumed = gateway.more(cursor)
            assertEquals(CollectionProblemCode.CONSUMED_CONTINUATION, consumed.data.extent.problem?.code)
            assertEquals(CollectionPageDisposition.FAILED_ATTEMPT, consumed.data.disposition)
            assertTrue(consumed.data.members.isEmpty())
            assertNull(consumed.continuation)
            assertEquals(3, transport.requests.size, "Consumed cursors must not perform another source request")
        }
    }

    @Test fun terminalContinuationFailureCannotBeRetriedByReusingTheOldCapability() = runTest {
        withGateway({ input -> if (Url(input.url).encodedPath == "/playlist")
            response(input, html(playlistData(listOf(video(renderer = "playlistVideoRenderer"), token()))))
            else response(input, "authored denied response", 403)
        }) { gateway, transport, _ ->
            val request = request(AcquisitionKind.SOURCE_COLLECTION)
            val selection = assertIs<SourceAcquisitionResult.Collection>(gateway.restore(request, collectionReceipt(request)))
            val cursor = assertNotNull(gateway.collection(request, selection).continuation)
            val firstFailure = gateway.more(cursor)
            assertEquals(CollectionPageDisposition.FAILED_ATTEMPT, firstFailure.data.disposition)
            assertEquals(SourceProblemCode.ACCESS_REQUIRED, firstFailure.data.extent.problem?.sourceProblem?.code)
            assertNull(firstFailure.continuation)
            val again = gateway.more(cursor)
            assertEquals(CollectionProblemCode.SOURCE_FAILURE, again.data.extent.problem?.code)
            assertNull(again.continuation)
            assertEquals(2, transport.requests.size)
        }
    }

    @Test fun foreignAndForgedContinuationCapabilitiesFailBeforeAnyOtherClientIo() = runTest {
        withGateway({ response(it, html(playlistData(listOf(video(renderer = "playlistVideoRenderer"), token())))) }) { firstGateway, firstTransport, _ ->
            val request = request(AcquisitionKind.ALBUM)
            val selection = assertIs<SourceAcquisitionResult.Collection>(firstGateway.restore(request, collectionReceipt(request)))
            val cursor = assertNotNull(firstGateway.collection(request, selection).continuation)
            withGateway({ error("A foreign capability cannot use this client") }) { otherGateway, otherTransport, _ ->
                for (invalid in listOf(cursor, object : CollectionContinuation {
                    override fun toString() = "authored-private-context"
                })) {
                    val error = assertFailsWith<VeyraException> { otherGateway.more(invalid) }
                    assertEquals(FailureCode.INVALID_STATE, error.failure.code)
                    assertEquals("YOUTUBE_COLLECTION_CONTINUATION_MISMATCH", error.failure.detail)
                    assertNull(error.cause)
                    assertFalse(error.toString().contains("authored-private-context"))
                }
                assertTrue(otherTransport.requests.isEmpty())
            }
            assertEquals(1, firstTransport.requests.size)
        }
    }

    @Test fun explicitPageAndMemberLimitsKeepTheObservedPrefixButNeverClaimCompletion() = runTest {
        for ((limits, expected) in listOf(
            CollectionLimits(maxPages = 1) to CollectionProblemCode.PAGE_LIMIT,
            CollectionLimits(maxMembers = 1) to CollectionProblemCode.MEMBER_LIMIT,
            CollectionLimits(maxMembersPerPage = 1) to CollectionProblemCode.PARSE_LIMIT,
        )) {
            withGateway({ response(it, html(playlistData(listOf(video(renderer = "playlistVideoRenderer"),
                video(VIDEO_C, "C", "playlistVideoRenderer"), token())))) }) { gateway, transport, _ ->
                val request = request(AcquisitionKind.RELEASE)
                val selection = assertIs<SourceAcquisitionResult.Collection>(gateway.restore(request, collectionReceipt(request)))
                val page = gateway.collection(request, selection, limits)
                assertEquals(CollectionPageDisposition.OBSERVED_DELTA, page.data.disposition)
                assertEquals(CollectionTraversal.INCOMPLETE, page.data.extent.traversal)
                assertEquals(expected, page.data.extent.problem?.code)
                assertEquals(if (expected == CollectionProblemCode.PAGE_LIMIT) 2 else 1, page.data.members.size)
                assertEquals(page.data.members.size, page.data.extent.observedMemberCount)
                assertNull(page.continuation)
                assertEquals(1, transport.requests.size)
            }
        }
    }

    @Test fun listingIdentityMismatchIsAnExplicitFailedAttemptWithoutAnyMembersOrFallback() = runTest {
        withGateway({ response(it, html(playlistData(listOf(video(renderer = "playlistVideoRenderer")), listId = "PLdifferent"))) }) { gateway, transport, _ ->
            val request = request(AcquisitionKind.RELEASE)
            val selection = assertIs<SourceAcquisitionResult.Collection>(gateway.restore(request, collectionReceipt(request)))
            val page = gateway.collection(request, selection)
            assertEquals(CollectionPageDisposition.FAILED_ATTEMPT, page.data.disposition)
            assertEquals(CollectionProblemCode.IDENTITY_MISMATCH, page.data.extent.problem?.code)
            assertEquals(CollectionTraversal.INCOMPLETE, page.data.extent.traversal)
            assertTrue(page.data.members.isEmpty())
            assertNull(page.data.observation)
            assertNull(page.continuation)
            assertEquals(1, transport.requests.size)
        }
    }

    @Test fun refreshingASelectedCollectionCreatesANewObservationWithoutRepeatingParentSearch() = runTest {
        withGateway({ response(it, if (Url(it.url).encodedPath == "/results") search(playlist())
            else html(playlistData(listOf(video(renderer = "playlistVideoRenderer"))))) }) { gateway, transport, _ ->
            val request = request(AcquisitionKind.ALBUM)
            val selection = assertIs<SourceAcquisitionResult.Collection>(gateway.acquire(request))
            val first = gateway.collection(request, selection).data
            val second = gateway.collection(request, selection).data
            assertNotEquals(first.observationId, second.observationId)
            assertEquals(selection, first.selection)
            assertEquals(selection, second.selection)
            assertEquals(first.observationId, first.members.single().reference.observationId)
            assertEquals(second.observationId, second.members.single().reference.observationId)
            assertEquals(listOf("/results", "/playlist", "/playlist"), transport.requests.map { Url(it.url).encodedPath })
        }
    }

    @Test fun displayMetadataDoesNotPersistCapabilityOrTrackingArtworkUrls() = runTest {
        val stable = "https://i.ytimg.com/vi/$VIDEO_C/hqdefault.jpg"
        for (thumbnail in listOf(stable, "$stable?sig=authored-capability", "https://user:authored-capability@i.ytimg.com/image.jpg",
            "https://unrelated.example/art.jpg", "$stable#authored-capability")) {
            withGateway({ response(it, search(video(VIDEO_C, "Returned complete title", thumbnail = thumbnail))) }) { gateway, _, _ ->
                val selected = assertIs<SourceAcquisitionResult.Single>(gateway.acquire(request()))
                assertEquals(if (thumbnail == stable) stable else null, selected.recording.thumbnailUrl)
                assertEquals("Returned complete title", selected.receipt.entry.title)
                assertEquals("$ORIGIN/watch?v=$VIDEO_C", selected.receipt.entry.publicUrl)
                assertFalse(selected.toString().contains("authored-capability"))
            }
        }
    }

    @Test fun sourceTransportFailureUsesFixedCodesWithoutLeakingTheOriginalException() = runTest {
        withGateway({ throw IllegalStateException("authored-private-response https://media.example/?sig=authored-capability") }) { gateway, transport, _ ->
            val result = assertIs<SourceAcquisitionResult.Unplayable>(gateway.acquire(request()))
            assertEquals(AcquisitionProblemCode.SOURCE_FAILURE, result.problem.code)
            assertEquals(SourceProblemCode.NETWORK, result.problem.sourceProblem?.code)
            assertNull(result.receipt)
            assertFalse(result.toString().contains("authored-private"))
            assertFalse(result.toString().contains("authored-capability"))
            assertEquals(1, transport.requests.size)
        }
    }

    @Test fun cancellationIsPropagatedWithoutBecomingAnUnplayableResult() = runTest {
        val cancelled = CancellationException("authored cancellation")
        withGateway({ throw cancelled }) { gateway, transport, _ ->
            val failure = assertFailsWith<CancellationException> { gateway.acquire(request()) }
            assertEquals(cancelled.message, failure.message)
            assertEquals(1, transport.requests.size)
        }
    }

    @Test fun gatewayDoesNotOwnOrCloseTheApplicationClient() = runTest {
        var closes = 0
        val transport = FixtureTransport { response(it, search(video())) }
        val client = YouTubeClient(transport, onClose = { closes++ })
        try {
            val gateway = YouTubeAcquisitionGateway(client)
            assertIs<SourceAcquisitionResult.Single>(gateway.acquire(request()))
            assertEquals(0, closes)
            // Another app-lifetime user of the same client still works after the gateway call.
            assertNotNull(client.search("Another explicit caller query").firstEntry)
            assertEquals(0, closes)
        } finally { client.close() }
        client.close()
        assertEquals(1, closes)
    }

    private suspend fun assertInvalidSelection(block: suspend () -> Any?) {
        val failure = assertFailsWith<VeyraException> { block() }
        assertEquals(FailureCode.INVALID_STATE, failure.failure.code)
        assertEquals("YOUTUBE_ACQUISITION_SELECTION_MISMATCH", failure.failure.detail)
        assertNull(failure.cause)
    }

    private suspend fun <T> withGateway(
        handler: suspend (ResearchRequest) -> ResearchResponse,
        block: suspend (YouTubeAcquisitionGateway, FixtureTransport, YouTubeClient) -> T,
    ): T {
        val transport = FixtureTransport(handler)
        val client = YouTubeClient(transport)
        try { return block(YouTubeAcquisitionGateway(client), transport, client) }
        finally { client.close() }
    }

    private class FixtureTransport(val handler: suspend (ResearchRequest) -> ResearchResponse) : ResearchTransport {
        val requests = mutableListOf<ResearchRequest>()
        override suspend fun execute(request: ResearchRequest): ResearchResponse {
            requests += request
            return handler(request)
        }
    }

    private fun request(kind: AcquisitionKind = AcquisitionKind.MEDIA, title: String = PARENT_TITLE): AcquisitionRequest =
        request(metadata = when (kind) {
            AcquisitionKind.MEDIA -> MediaCapture(MediaItem("catalog:media", title, MediaKind.MUSIC,
                SourceRef(SourceProvider.COMMONS, "authored-catalog-id", "https://commons.wikimedia.org/wiki/Authored")))
            AcquisitionKind.ALBUM -> AlbumCapture(Album("catalog:album", title, trackIds = listOf("unrelated-original-catalog-track")))
            AcquisitionKind.RELEASE -> ReleaseCapture(CapturedReleaseObservation(ReleaseObservationId("captured-release"), release(title)))
            AcquisitionKind.SHOW -> ShowCapture(PodcastShow("rss:show", title, "https://podcast.example/feed.xml",
                episodeIds = listOf("unrelated-original-rss-guid")))
            AcquisitionKind.SOURCE_COLLECTION -> SourceCollectionCapture(SourceCollectionMetadata(SourceProvider.YOUTUBE,
                "PLrequested", title, "$ORIGIN/playlist?list=PLrequested", AcquisitionEntryKind.PLAYLIST, CollectionScope.PLAYLIST_MEMBERS))
            AcquisitionKind.COLLECTION_MEMBER -> CollectionMemberCapture(CollectionSnapshotId("saved-snapshot"), member(title))
        }, id = "${kind.name.lowercase()}-request")

    private fun request(metadata: RequestedMetadata, id: String = "request-id") = AcquisitionRequest(
        AcquisitionRequestId(id), metadata.completeTitle, metadata, capturedEpochMs = 123_000,
        catalogRevision = 7, providerRevision = "captured-provider-revision")

    private fun member(title: String = MEMBER_TITLE): CollectionMember {
        val reference = CollectionMemberReference(CollectionObservationId("saved-observation"), AcquisitionRequestId("parent-request"),
            CollectionIdentity(AcquisitionEntryKind.PLAYLIST, PLAYLIST_ID, "$ORIGIN/playlist?list=$PLAYLIST_ID"),
            CollectionScope.PLAYLIST_MEMBERS, 0)
        return CollectionMember(reference, SourceEntry(AcquisitionEntryKind.VIDEO, VIDEO_B, title, "$ORIGIN/watch?v=$VIDEO_B"),
            SourceDisplayItem(VIDEO_B, title, "$ORIGIN/watch?v=$VIDEO_B", AcquisitionEntryKind.VIDEO))
    }

    // Explicit authored public metadata, not an assertion that this receipt was observed live.
    private fun singleReceipt(request: AcquisitionRequest) = FirstResultObservation(request.id, request.completeTitle,
        SourceEntry(AcquisitionEntryKind.VIDEO, VIDEO_C, "Returned recording C", "$ORIGIN/watch?v=$VIDEO_C"), "$ORIGIN/watch?v=$VIDEO_C")
    private fun collectionReceipt(request: AcquisitionRequest) = FirstResultObservation(request.id, request.completeTitle,
        SourceEntry(AcquisitionEntryKind.PLAYLIST, PLAYLIST_ID, "Returned collection", "$ORIGIN/playlist?list=$PLAYLIST_ID"),
        "$ORIGIN/playlist?list=$PLAYLIST_ID")

    private fun release(title: String) = ReleaseObservation(
        requestedReleaseMbid = "00000000-0000-4000-8000-000000000001",
        metadata = ReleaseCoreMetadata(ReleaseSummaryMetadata(
            releaseMbid = "00000000-0000-4000-8000-000000000001", completeTitle = title,
            releaseGroup = ReleaseGroupMetadata("00000000-0000-4000-8000-000000000002", "Different group", "EP", emptyList()),
            artistCredits = emptyList(), status = "Official", date = "2026-11", country = "GB", releaseEvents = emptyList(),
            labels = emptyList(), barcode = null, disambiguation = "Authored edition", textLanguage = "ara", textScript = "Arab",
            packaging = null, packagingMbid = null, problems = emptyList(),
        ), emptyList(), ReleaseMembershipStatus.OBSERVED_COMPLETE, emptyList()),
        fetchedAtEpochMs = 123_000, coreDigest = "a".repeat(64), etag = null,
    )

    private fun response(request: ResearchRequest, body: String, status: Int = 200) = ResearchResponse(request.url, status, body)
    private fun wrap(key: String, value: JsonElement) = buildJsonObject { put(key, value) }
    private fun text(value: String) = wrap("simpleText", JsonPrimitive(value))
    private fun video(id: String = VIDEO_B, title: String = MEMBER_TITLE, renderer: String = "videoRenderer",
        playable: Boolean = true, thumbnail: String? = null) = wrap(renderer, buildJsonObject {
        put("videoId", id); put("title", text(title)); put("isPlayable", playable)
        thumbnail?.let { put("thumbnail", wrap("thumbnails", JsonArray(listOf(buildJsonObject { put("url", it) })))) }
    })
    private fun playlist() = wrap("playlistRenderer", buildJsonObject { put("playlistId", PLAYLIST_ID); put("title", text("Returned collection")) })
    private fun channel() = wrap("channelRenderer", buildJsonObject {
        put("channelId", CHANNEL_ID); put("title", text("Returned channel"))
        put("navigationEndpoint", wrap("browseEndpoint", buildJsonObject { put("browseId", CHANNEL_ID); put("canonicalBaseUrl", "/@authored") }))
    })
    private fun token() = wrap("continuationItemRenderer", wrap("continuationEndpoint", buildJsonObject {
        put("commandMetadata", wrap("webCommandMetadata", buildJsonObject { put("apiUrl", "/youtubei/v1/browse") }))
        put("continuationCommand", buildJsonObject { put("token", AUTHORED_CURSOR) })
    }))
    private fun html(data: JsonObject) = "<script>ytcfg.set({\"INNERTUBE_CONTEXT\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"2.fixture.version\",\"hl\":\"en\",\"visitorData\":\"authored-visitor\"}},\"INNERTUBE_CONTEXT_CLIENT_NAME\":1,\"INNERTUBE_API_KEY\":\"authored-key\"});</script>" +
        "<script>var ytInitialData=$data;</script>"
    private fun search(vararg rows: JsonElement) = html(wrap("contents", wrap("twoColumnSearchResultsRenderer", buildJsonObject {
        put("primaryContents", wrap("sectionListRenderer", wrap("contents", JsonArray(listOf(
            wrap("itemSectionRenderer", wrap("contents", JsonArray(rows.toList()))))))))
        put("secondaryContents", video(VIDEO_C, "Not the actual first primary result"))
    })))
    private fun playlistData(rows: List<JsonElement>, listId: String = PLAYLIST_ID): JsonObject {
        val listing = wrap("playlistVideoListRenderer", buildJsonObject {
            put("playlistId", listId); put("targetId", PLAYLIST_TARGET); put("contents", JsonArray(rows))
        })
        val itemSection = wrap("itemSectionRenderer", wrap("contents", JsonArray(listOf(listing))))
        val section = wrap("sectionListRenderer", wrap("contents", JsonArray(listOf(itemSection))))
        val tab = wrap("tabRenderer", buildJsonObject { put("selected", true); put("content", section) })
        return buildJsonObject {
            put("contents", wrap("twoColumnBrowseResultsRenderer", wrap("tabs", JsonArray(listOf(tab)))))
            put("metadata", wrap("playlistMetadataRenderer", buildJsonObject { put("title", "Actual complete source playlist") }))
        }
    }
    private fun channelData(): JsonObject {
        val tab = wrap("tabRenderer", buildJsonObject {
            put("selected", true); put("title", "Videos")
            put("endpoint", buildJsonObject {
                put("browseEndpoint", buildJsonObject { put("browseId", CHANNEL_ID) })
                put("commandMetadata", wrap("webCommandMetadata", buildJsonObject { put("url", "/channel/$CHANNEL_ID/videos") }))
            })
            put("content", wrap("richGridRenderer", buildJsonObject {
                put("targetId", "browse-feed"); put("contents", JsonArray(listOf(video())))
            }))
        })
        return buildJsonObject {
            put("metadata", wrap("channelMetadataRenderer", buildJsonObject { put("externalId", CHANNEL_ID); put("title", "Actual source channel") }))
            put("contents", wrap("twoColumnBrowseResultsRenderer", wrap("tabs", JsonArray(listOf(tab)))))
        }
    }
    private fun append(rows: List<JsonElement>) = wrap("onResponseReceivedActions", JsonArray(listOf(
        wrap("appendContinuationItemsAction", buildJsonObject { put("targetId", PLAYLIST_TARGET); put("continuationItems", JsonArray(rows)) })
    )))
    private fun watch(withThumbnail: Boolean = true, status: String = "OK") = "<script>var ytInitialPlayerResponse=" + buildJsonObject {
        put("videoDetails", buildJsonObject {
            put("videoId", VIDEO_C); put("channelId", CHANNEL_ID); put("title", "Inspected source recording")
            put("author", "Authored fixture"); put("lengthSeconds", "3")
            if (withThumbnail) put("thumbnail", wrap("thumbnails", JsonArray(listOf(buildJsonObject {
                put("url", "https://i.ytimg.com/vi/$VIDEO_C/hqdefault.jpg")
            }))))
        })
        put("playabilityStatus", buildJsonObject { put("status", status) })
        if (status == "OK") put("streamingData", wrap("adaptiveFormats", JsonArray(listOf(buildJsonObject {
            put("itag", 140); put("url", "https://media.example/videoplayback?sig=authored-capability")
            put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\""); put("contentLength", "100")
            put("bitrate", 128_000); put("audioSampleRate", "44100"); put("audioChannels", 2)
        }))))
    }.toString() + ";</script>"
    private fun oembed() = buildJsonObject {
        put("type", "video"); put("provider_name", "YouTube"); put("title", "Supplemental title")
        put("author_name", "Authored metadata author"); put("thumbnail_url", "https://i.ytimg.com/vi/$VIDEO_C/hqdefault.jpg")
    }.toString()

    private companion object {
        const val ORIGIN = "https://www.youtube.com"
        const val VIDEO_B = "ABCDEFGHIJK"
        const val VIDEO_C = "LMNOPQRSTUV"
        const val CHANNEL_ID = "UCabcdefghijklmnopqrstuv"
        const val PLAYLIST_ID = "PLabcdefghijklmnopqrstuv"
        const val PLAYLIST_TARGET = "VL$PLAYLIST_ID"
        const val PARENT_TITLE = "  Requested complete collection — الحلقة الأخيرة  "
        const val MEMBER_TITLE = "  Observed member B — 尾声 🎵  "
        const val AUTHORED_CURSOR = "authored-next-cursor-not-for-persistence"
    }
}
