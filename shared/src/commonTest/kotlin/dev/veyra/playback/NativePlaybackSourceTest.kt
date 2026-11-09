package dev.veyra.playback

import dev.veyra.model.*
import dev.veyra.source.execution.AcquisitionPlaybackSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Inert native-input identity checks; no ownership transfer, native decoder or current-queue authority. */
class NativePlaybackSourceTest {
    private class Resource(endpoint: String? = null) : PlaybackResource {
        override val localEndpoint = endpoint?.let(::EphemeralPlaybackEndpoint)
        var closeCalls = 0
        override suspend fun close() { closeCalls++ }
    }

    @Test fun everyTypedRequestKindAndExplicitModeUsesItsExactBoundSelection() {
        for (kind in AcquisitionKind.entries) for (mode in PlaybackControlsMode.entries) {
            val fixture = PlaybackSelectionFixtures.single(kind, mode)
            val native = NativePlaybackSource.Acquired(source(fixture), fixture.selection())
            native.validateSelection()
            assertEquals(mode, native.controlsMode)
            assertEquals(fixture.selection().executed, native.display)
            if (kind != AcquisitionKind.MEDIA) assertNull(native.catalogMedia)
        }
    }

    @Test fun constructingAndRejectingTheWrapperDoesNotCloseAnUnregisteredResource() {
        val fixture = PlaybackSelectionFixtures.single()
        val resource = Resource()
        val source = source(fixture, resource)
        val mismatched = fixture.selection().copy(executed = PlaybackSelectionDisplay("Fabricated C"))
        val native = NativePlaybackSource.Acquired(source, mismatched)
        assertSame(resource, native.resource)
        assertEquals(0, resource.closeCalls)
        assertMismatch(native)
        assertEquals(0, resource.closeCalls, "The owning engine must register before validating; the wrapper is not an owner")
    }

    @Test fun fullContextIsRequiredEvenWhenRequestReceiptBindingAndSubjectIdsAreEqual() {
        val fixture = PlaybackSelectionFixtures.single()
        val expected = fixture.execution
        val changedRequest = expected.request.copy(capturedEpochMs = expected.request.capturedEpochMs + 1)
        val changed = expected.copy(request = changedRequest)
        assertEquals(expected.request.id, changed.request.id)
        assertEquals(expected.receipt.id, changed.receipt.id)
        assertEquals(expected.binding.id, changed.binding.id)
        assertEquals(expected.subject.id, changed.subject.id)
        assertMismatch(NativePlaybackSource.Acquired(source(fixture).copy(context = changed), fixture.selection()))
    }

    @Test fun typedIdentityPinsCannotBeReplacedBySchema2UnboundOrForeignSelectionFields() {
        val fixture = PlaybackSelectionFixtures.single()
        val selection = fixture.selection()
        val invalid = listOf(
            selection.copy(libraryRevision = -1),
            selection.copy(context = QueueContext.PODCAST),
            selection.copy(occurrence = selection.occurrence.copy(origin = AcquisitionOrigin.SCHEMA2, requestId = null)),
            selection.copy(occurrence = selection.occurrence.copy(selectionState = OccurrenceSelectionState.PENDING,
                bindingId = null, subjectId = null)),
            selection.copy(occurrence = selection.occurrence.copy(requestId = AcquisitionRequestId("foreign:request"))),
            selection.copy(occurrence = selection.occurrence.copy(bindingId = ExecutionBindingId("foreign:binding"))),
            selection.copy(occurrence = selection.occurrence.copy(subjectId = ExecutableSubjectId("foreign:subject"))),
            selection.copy(executionContext = null),
            selection.copy(request = null),
            selection.copy(receipt = null),
            selection.copy(binding = null),
            selection.copy(target = null),
            selection.copy(target = PlaybackTarget.Schema2("not-a-neutral-catalog-id", null)),
        )
        for (value in invalid) assertMismatch(NativePlaybackSource.Acquired(source(fixture), value))
    }

    @Test fun executedTitleUrlAndAbsentOptionalFieldsMustBeTheExactCReceipt() {
        val fixture = PlaybackSelectionFixtures.single()
        val selection = fixture.selection()
        val c = requireNotNull(selection.executed)
        for (display in listOf(null, c.copy(title = "Requested A"), c.copy(publicUrl = "https://example.test/foreign"),
            c.copy(subtitle = "A publisher"), c.copy(artworkUrl = "https://example.test/a.png"),
            c.copy(localArtworkPath = "/authored/a.png"))) {
            assertMismatch(NativePlaybackSource.Acquired(source(fixture), selection.copy(executed = display)))
        }
    }

    @Test fun requestedAndObservedDisplaysAreCheckedAsWholeCapturedValues() {
        val fixture = PlaybackSelectionFixtures.single(AcquisitionKind.COLLECTION_MEMBER)
        val selection = fixture.selection()
        val a = requireNotNull(selection.requested)
        val b = requireNotNull(selection.observedMember)
        val invalid = listOf(
            selection.copy(requested = null),
            selection.copy(requested = a.copy(title = "Later A")),
            selection.copy(requested = a.copy(artworkUrl = "https://example.test/later-a.png")),
            selection.copy(observedMember = null),
            selection.copy(observedMember = b.copy(title = "Other duplicate B")),
            selection.copy(observedMember = b.copy(subtitle = "Uncaptured B author")),
        )
        for (value in invalid) assertMismatch(NativePlaybackSource.Acquired(source(fixture), value))
    }

    @Test fun selectedMemberOrdinalParentReceiptAndAdmittedPositionAreNotInterchangeable() {
        val fixture = PlaybackSelectionFixtures.single(AcquisitionKind.COLLECTION_MEMBER)
        val selection = fixture.selection()
        val member = requireNotNull(selection.memberLineage)
        val scope = requireNotNull(selection.scopeLineage)
        val parent = member.parentReceipt
        val invalid = listOf(
            selection.copy(memberLineage = null),
            selection.copy(occurrence = selection.occurrence.copy(snapshotMember = CollectionSnapshotMemberRef(member.snapshotId, 0))),
            selection.copy(memberLineage = member.copy(member = member.member.copy(entry = member.member.entry.copy(ordinal = 0)))),
            selection.copy(memberLineage = member.copy(parentReceipt = parent.copy(observation = parent.observation.copy(
                entry = parent.observation.entry.copy(ordinal = 1))))),
            selection.copy(memberLineage = member.copy(parentReceipt = parent.copy(observation = parent.observation.copy(
                canonicalUrl = "https://www.youtube.com/playlist?list=foreign")))),
            selection.copy(scopeLineage = null),
            selection.copy(scopeLineage = scope.copy(kind = AdmissionScopeKind.CATALOG_LEAVES)),
            selection.copy(scopeLineage = scope.copy(member = scope.member.copy(position = 0))),
            selection.copy(scopeLineage = scope.copy(member = scope.member.copy(snapshotMember = CollectionSnapshotMemberRef(member.snapshotId, 0)))),
        )
        for (value in invalid) assertMismatch(NativePlaybackSource.Acquired(source(fixture), value))
    }

    @Test fun catalogTargetCannotSilentlyUseAChangedCaptureWithTheSameMediaId() {
        val fixture = PlaybackSelectionFixtures.single(AcquisitionKind.MEDIA)
        val selection = fixture.selection()
        val target = selection.target as PlaybackTarget.Catalog
        assertMismatch(NativePlaybackSource.Acquired(source(fixture), selection.copy(target = target.copy(
            media = target.media.copy(creator = "Later catalogue creator")))))
    }

    @Test fun advancingLibraryRevisionIsAllowedButDoesNotGrantCurrentOccurrenceAdmission() {
        val fixture = PlaybackSelectionFixtures.single()
        val selection = fixture.selection()
        NativePlaybackSource.Acquired(source(fixture), selection.copy(libraryRevision = selection.libraryRevision + 1)).validateSelection()
        // An inert DTO cannot attest current durable queue membership. The controller separately
        // fences this token immediately before calling the owning native prepare/preload method.
        NativePlaybackSource.Acquired(source(fixture), selection.copy(occurrence = selection.occurrence.copy(
            id = ExecutionOccurrenceId("not-attested-by-this-wrapper")))).validateSelection()
    }

    @Test fun onlyTransportObservationsSupplyDurationCodecAndSeekability() {
        val original = PlaybackSelectionFixtures.request(AcquisitionKind.MEDIA)
        val capture = original.metadata as MediaCapture
        val fixture = PlaybackSelectionFixtures.single(AcquisitionKind.MEDIA,
            request = original.copy(metadata = capture.copy(media = capture.media.copy(durationMs = 900_000))))
        val source = source(fixture).copy(mimeType = "audio/webm", codecs = "opus", durationMs = 12_345,
            seekableHint = false, transport = StreamTransport.PROGRESSIVE, representationId = "actual:opus")
        val known = NativePlaybackSource.Acquired(source, fixture.selection())
        known.validateSelection()
        assertEquals(12_345L, known.durationMs)
        assertEquals("opus", known.codecs)
        assertEquals("audio/webm", known.mimeType)
        assertEquals("actual:opus", known.representationId)
        assertEquals(false, known.seekableHint)
        assertEquals(900_000L, known.catalogMedia?.durationMs)
        val unknown = NativePlaybackSource.Acquired(source.copy(durationMs = null, seekableHint = null), fixture.selection())
        assertNull(unknown.durationMs)
        assertNull(unknown.seekableHint)
    }

    @Test fun loopbackExceptionRequiresExactOwnedEndpointAndDoesNotMakeRemoteMediaLocal() {
        val fixture = PlaybackSelectionFixtures.single()
        val uri = "http://127.0.0.1:32123/authored-capability/audio"
        val resource = Resource(uri)
        val source = source(fixture, resource).copy(uri = uri)
        val native = NativePlaybackSource.Acquired(source, fixture.selection())
        assertEquals(uri, native.ownedLoopbackUriOrNull())
        assertFalse(native.isLocal)
        assertNull(NativePlaybackSource.Acquired(source.copy(uri = "$uri-other"), fixture.selection()).ownedLoopbackUriOrNull())
        assertNull(NativePlaybackSource.Acquired(source.copy(resource = Resource()), fixture.selection()).ownedLoopbackUriOrNull())
        val local = NativePlaybackSource.Acquired(source.copy(isLocal = true,
            materializationAssetId = MaterializationAssetId("asset:verified")), fixture.selection())
        assertTrue(local.isLocal)
        assertNull(local.ownedLoopbackUriOrNull())
        assertEquals(0, resource.closeCalls)
    }

    @Test fun explicitlyLegacyInputsKeepLegacyModeWithoutManufacturingTypedSelection() {
        val captured = (PlaybackSelectionFixtures.request(AcquisitionKind.MEDIA).metadata as MediaCapture).media
        val resource = Resource()
        val old = ResolvedPlayable(captured.id, "authored-fake://legacy", durationMs = 3_000, item = captured, resource = resource)
        val music = NativePlaybackSource.Legacy(old)
        music.validateSelection()
        assertEquals(PlaybackControlsMode.MUSIC, music.controlsMode)
        assertSame(captured, music.catalogMedia)
        assertEquals(3_000L, music.durationMs)
        val podcast = NativePlaybackSource.Legacy(old.copy(item = captured.copy(kind = MediaKind.EPISODE)))
        assertEquals(PlaybackControlsMode.PODCAST, podcast.controlsMode)
        assertEquals(0, resource.closeCalls)
    }

    @Test fun wrapperDiagnosticsDoNotExposeMetadataLocatorsOrHeaders() {
        val fixture = PlaybackSelectionFixtures.single()
        val source = source(fixture).copy(uri = "https://media.example/audio?sig=authored-private-token",
            headers = mapOf("X-Authored-Private" to "header-value-not-for-logs"))
        val native = NativePlaybackSource.Acquired(source, fixture.selection())
        val diagnostic = native.toString()
        assertFalse(diagnostic.contains("authored-private-token"))
        assertFalse(diagnostic.contains("header-value-not-for-logs"))
        assertFalse(diagnostic.contains(fixture.execution.request.completeTitle))
        assertTrue(diagnostic.contains("redacted"))
    }

    private fun source(fixture: PlaybackSelectionFixtures.Fixture, resource: PlaybackResource? = null) =
        AcquisitionPlaybackSource(fixture.execution, "https://media.example/authored", StreamTransport.PROGRESSIVE,
            "audio/mp4", "mp4a.40.2", "authored:140", resource = resource)

    private fun assertMismatch(source: NativePlaybackSource) {
        val error = assertFailsWith<VeyraException> { source.validateSelection() }
        assertEquals(FailureCode.INVALID_STATE, error.failure.code)
        assertEquals("native_playback_selection_mismatch", error.failure.detail)
    }
}
