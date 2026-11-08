package dev.veyra.acquisition

import dev.veyra.model.*
import kotlin.test.*

class AcquisitionAdmissionFactoryTest {
    private val clock = AcquisitionTestClock()
    private val captures = AcquisitionCaptureFactory(clock)
    private val factory = AcquisitionAdmissionFactory(captures, clock)

    @Test fun wholeListingRequiresExplicitEndAndNoHolesOrUnknownSuffix() {
        for (snapshot in listOf(
            authoredSnapshot(traversal = CollectionTraversal.MORE_AVAILABLE),
            authoredSnapshot(traversal = CollectionTraversal.INCOMPLETE),
            authoredSnapshot(holes = setOf(1)),
            authoredSnapshot().let { it.copy(extent = it.extent.copy(problem = CollectionProblem(CollectionProblemCode.PARSE_LIMIT))) },
        )) assertEquals("collection_whole_listing_unavailable", assertFailsWith<VeyraException> {
            factory.collection(snapshot, ObservedCollectionChoice.WholeObservedListing, PlaybackControlsMode.MUSIC)
        }.failure.detail)
    }

    @Test fun completeListingPreservesRepeatedBAsSeparateRequestAndOccurrencePositions() {
        val snapshot = authoredSnapshot()
        val prepared = factory.collection(snapshot, ObservedCollectionChoice.WholeObservedListing, PlaybackControlsMode.MUSIC)
        val scope = assertNotNull(prepared.scope)
        assertEquals(AdmissionScopeKind.WHOLE_OBSERVED_LISTING, scope.kind)
        assertEquals(snapshot.parentRequestId, scope.parentRequestId)
        assertEquals(snapshot.id, scope.snapshotId)
        assertEquals(3, prepared.requests.map { it.id }.toSet().size)
        assertEquals(3, prepared.occurrences.map { it.id }.toSet().size)
        assertEquals(listOf(0, 1, 2), prepared.occurrences.map { it.snapshotMember!!.ordinal })
        assertTrue(prepared.requests.all { (it.metadata as CollectionMemberCapture).member.entry.id == "ABCDEFGHIJK" })
        assertTrue(prepared.occurrences.all { it.bindingId == null && it.subjectId == null && it.selectionState == OccurrenceSelectionState.PENDING })
    }

    @Test fun availableSubsetPreservesExcludedHoleRowsWithoutInventingAChildRequest() {
        val prepared = factory.collection(authoredSnapshot(holes = setOf(1)), ObservedCollectionChoice.Available(true, false), PlaybackControlsMode.PODCAST)
        val scope = assertNotNull(prepared.scope)
        assertEquals(AdmissionScopeKind.AVAILABLE_SUBSET, scope.kind)
        assertEquals(PlaybackControlsMode.PODCAST, scope.controlsMode)
        assertEquals(listOf(0, 1, 2), scope.members.map { it.position })
        assertEquals(AdmissionDisposition.EXCLUDED, scope.members[1].disposition)
        assertEquals(AdmissionExclusion.KNOWN_HOLE, scope.members[1].exclusion)
        assertNull(scope.members[1].requestId)
        assertEquals(listOf(0, 2), prepared.occurrences.map { it.snapshotMember!!.ordinal })
        assertEquals(listOf(0, 2), prepared.occurrences.map { it.scopeMember!!.position })
        assertEquals(2, prepared.requests.size)
    }

    @Test fun anAvailableSubsetRequiresTheOwnerToAcknowledgeKnownHoles() {
        assertEquals("collection_holes_acknowledgement_required", assertFailsWith<VeyraException> {
            factory.collection(authoredSnapshot(holes = setOf(1)), ObservedCollectionChoice.Available(false, false), PlaybackControlsMode.MUSIC)
        }.failure.detail)
    }

    @Test fun partialAvailableAndSelectedScopesRequireAnExplicitUnknownSuffixAcknowledgement() {
        val snapshot = authoredSnapshot(traversal = CollectionTraversal.MORE_AVAILABLE)
        for (choice in listOf(ObservedCollectionChoice.Available(true, false), ObservedCollectionChoice.Selected(setOf(2), false))) {
            assertEquals("collection_unobserved_suffix_acknowledgement_required", assertFailsWith<VeyraException> {
                factory.collection(snapshot, choice, PlaybackControlsMode.MUSIC)
            }.failure.detail)
        }
        val prepared = factory.collection(snapshot, ObservedCollectionChoice.Selected(setOf(2), true), PlaybackControlsMode.MUSIC)
        val scope = assertNotNull(prepared.scope)
        assertTrue(scope.acknowledgesUnobservedSuffix)
        assertEquals(AdmissionScopeKind.SELECTED_SUBSET, scope.kind)
    }

    @Test fun selectedSubsetUsesCapturedSourceOrderNotCheckboxOrSetIterationOrder() {
        val selected = linkedSetOf(2, 0)
        val prepared = factory.collection(authoredSnapshot(), ObservedCollectionChoice.Selected(selected, false), PlaybackControlsMode.MUSIC)
        selected.clear()
        assertEquals(listOf(0, 2), prepared.occurrences.map { it.snapshotMember!!.ordinal })
        assertEquals(AdmissionExclusion.UNSELECTED, prepared.scope!!.members[1].exclusion)
        assertEquals(listOf(0, 2), prepared.requests.map { (it.metadata as CollectionMemberCapture).member.reference.ordinal })
    }

    @Test fun selectionOfAHoleOrUnknownOrdinalIsRejectedRatherThanSkippingToTheNextMember() {
        val snapshot = authoredSnapshot(holes = setOf(1))
        for (selection in listOf(setOf(1), setOf(0, 5))) assertEquals("collection_selected_member_unavailable", assertFailsWith<VeyraException> {
            factory.collection(snapshot, ObservedCollectionChoice.Selected(selection, false), PlaybackControlsMode.MUSIC)
        }.failure.detail)
    }

    @Test fun unverifiedMembershipAndCorruptCountsCannotAdmitAnyScope() {
        val snapshot = authoredSnapshot()
        for (invalid in listOf(snapshot.copy(observation = null),
            snapshot.copy(members = snapshot.members.reversed()),
            snapshot.copy(extent = snapshot.extent.copy(observedMemberCount = 4)),
            snapshot.copy(members = listOf(snapshot.members[0], snapshot.members[0], snapshot.members[2])),
        )) assertFailsWith<VeyraException> { factory.collection(invalid, ObservedCollectionChoice.Available(true, true), PlaybackControlsMode.MUSIC) }
    }

    @Test fun corruptOptionalDisplayNeverFillsAPlayableHoleOrMakesWholeListingAvailable() {
        val snapshot = authoredSnapshot(count = 2)
        val corrupted = snapshot.copy(members = listOf(snapshot.members[0].let { it.copy(display = it.display!!.copy(title = "Other title")) }, snapshot.members[1]))
        val prepared = factory.collection(corrupted, ObservedCollectionChoice.Available(true, false), PlaybackControlsMode.MUSIC)
        assertEquals(listOf(1), prepared.occurrences.map { it.snapshotMember!!.ordinal })
        assertEquals(AdmissionExclusion.KNOWN_HOLE, prepared.scope!!.members[0].exclusion)
        assertFailsWith<VeyraException> { factory.collection(corrupted, ObservedCollectionChoice.WholeObservedListing, PlaybackControlsMode.MUSIC) }
    }

    @Test fun catalogueLeavesRemainADifferentScopeWithoutAFictitiousSourceCollectionParent() {
        val originals = listOf(authoredMedia("catalog-one"), authoredMedia("catalog-two", kind = MediaKind.EPISODE))
        val requests = originals.map { captures.capture(MediaCapture(it), 7) }
        val prepared = factory.catalogLeaves(requests, PlaybackControlsMode.PODCAST, 7)
        val scope = assertNotNull(prepared.scope)
        assertEquals(AdmissionScopeKind.CATALOG_LEAVES, scope.kind)
        assertNull(scope.parentRequestId)
        assertNull(scope.snapshotId)
        assertTrue(prepared.occurrences.all { it.snapshotMember == null && it.controlsMode == PlaybackControlsMode.PODCAST })
        assertEquals(originals, prepared.requests.map { (it.metadata as MediaCapture).media })
    }

    @Test fun albumParentCannotMasqueradeAsCatalogueLeavesOrReuseOneRequestAcrossPositions() {
        val parent = authoredRequest("parent", AlbumCapture(Album("album", "Edition")))
        assertFailsWith<VeyraException> { factory.catalogLeaves(listOf(parent), PlaybackControlsMode.MUSIC, null) }
        val leaf = authoredRequest()
        assertFailsWith<VeyraException> { factory.catalogLeaves(listOf(leaf, leaf), PlaybackControlsMode.MUSIC, null) }
    }

    @Test fun frozenOldSnapshotAdmissionCannotRetargetToTheLatestObservedMembership() {
        val old = authoredSnapshot(observationId = "old-observation")
        val prepared = factory.collection(old, ObservedCollectionChoice.WholeObservedListing, PlaybackControlsMode.MUSIC)
        val fresh = authoredSnapshot(count = 1, observationId = "fresh-observation")
        assertNotEquals(old.observationId, fresh.observationId)
        assertEquals(old.id, prepared.scope!!.snapshotId)
        assertTrue(prepared.requests.all { (it.metadata as CollectionMemberCapture).member.reference.observationId == old.observationId })
        assertTrue(prepared.occurrences.all { it.snapshotMember!!.snapshotId == old.id })
    }
}
