package dev.veyra.acquisition

import dev.veyra.model.*
import dev.veyra.playback.Clock
import dev.veyra.playback.SystemClock

/** Named user intent. Available/Selected never mean the provider's complete album or show. */
sealed interface ObservedCollectionChoice {
    data object WholeObservedListing : ObservedCollectionChoice
    data class Available(
        val acknowledgesKnownHoles: Boolean,
        val acknowledgesUnobservedSuffix: Boolean,
    ) : ObservedCollectionChoice
    data class Selected(
        val ordinals: Set<Int>,
        val acknowledgesUnobservedSuffix: Boolean,
    ) : ObservedCollectionChoice
}

/** Pure prepared command inputs, not a second repository or a successful admission receipt. */
data class PreparedAcquisitionAdmission(
    val requests: List<AcquisitionRequest>,
    val scope: AdmissionScope?,
    val occurrences: List<ExecutionOccurrence>,
    val expectedCatalogRevision: Long?,
)

/** Preserve every included/excluded source position; create no request for an unavailable hole. */
class AcquisitionAdmissionFactory(
    private val captures: AcquisitionCaptureFactory = AcquisitionCaptureFactory(),
    private val clock: Clock = SystemClock(),
) {
    fun single(
        request: AcquisitionRequest,
        mode: PlaybackControlsMode,
        expectedCatalogRevision: Long? = request.catalogRevision,
    ): PreparedAcquisitionAdmission {
        val frozen = freeze(request)
        return PreparedAcquisitionAdmission(listOf(frozen), null, listOf(occurrence(frozen, mode)), expectedCatalogRevision)
    }

    /**
     * An explicitly selected genuine catalogue/local-playlist leaf operation. Never use an
     * Album/Show parent lookup as ceremony before substituting these for its source membership.
     */
    fun catalogLeaves(
        requests: List<AcquisitionRequest>,
        mode: PlaybackControlsMode,
        expectedCatalogRevision: Long?,
    ): PreparedAcquisitionAdmission {
        val frozen = requests.map(::freeze)
        if (frozen.size !in 1..5_000 || frozen.any { it.kind != AcquisitionKind.MEDIA } ||
            frozen.map { it.id }.toSet().size != frozen.size ||
            frozen.any { it.catalogRevision != null && it.catalogRevision != expectedCatalogRevision }) {
            invalid("catalog_leaf_admission_mismatch")
        }
        val scope = AdmissionScope(
            AdmissionScopeId(newEntityId("catalog-scope")), AdmissionScopeKind.CATALOG_LEAVES, null, null, mode,
            frozen.mapIndexed { index, request -> AdmissionScopeMember(index, null, request.id, AdmissionDisposition.INCLUDED, null) },
            acknowledgesUnobservedSuffix = false, admittedEpochMs = clock.epochMs(),
        )
        return PreparedAcquisitionAdmission(frozen, scope,
            frozen.mapIndexed { index, request -> occurrence(request, mode, AdmissionScopeMemberRef(scope.id, index)) },
            expectedCatalogRevision)
    }

    /**
     * One NEW catalogue-leaf command may contain current captures and exact older accepted
     * requests. Historical request.catalogRevision values remain immutable; only this command
     * compares expectedLibraryRevision at the Room boundary. This pure factory is not proof
     * that an old request exists or was accepted: the caller and canonical owner rejoin it.
     * The original strict catalogLeaves API deliberately keeps its original semantics.
     */
    fun catalogLeavesPreservingCaptures(
        requests: List<AcquisitionRequest>,
        mode: PlaybackControlsMode,
        expectedLibraryRevision: Long,
    ): PreparedAcquisitionAdmission {
        val frozen = requests.map(::freeze)
        if (expectedLibraryRevision < 0 || frozen.size !in 1..5_000 ||
            frozen.any { it.kind != AcquisitionKind.MEDIA } ||
            frozen.map { it.id }.toSet().size != frozen.size) {
            invalid("catalog_leaf_admission_mismatch")
        }
        val scope = AdmissionScope(
            AdmissionScopeId(newEntityId("catalog-scope")), AdmissionScopeKind.CATALOG_LEAVES, null, null, mode,
            frozen.mapIndexed { index, request -> AdmissionScopeMember(index, null, request.id, AdmissionDisposition.INCLUDED, null) },
            acknowledgesUnobservedSuffix = false, admittedEpochMs = clock.epochMs(),
        )
        return PreparedAcquisitionAdmission(frozen, scope,
            frozen.mapIndexed { index, request -> occurrence(request, mode, AdmissionScopeMemberRef(scope.id, index)) },
            expectedLibraryRevision)
    }

    fun collection(
        snapshot: CollectionSnapshot,
        choice: ObservedCollectionChoice,
        mode: PlaybackControlsMode,
    ): PreparedAcquisitionAdmission {
        validateSnapshot(snapshot)
        val members = snapshot.members.toList()
        val holes = members.filterNot(::eligibleMember)
        val partial = snapshot.extent.traversal != CollectionTraversal.OBSERVED_TO_END
        val selected: Set<Int>
        val kind: AdmissionScopeKind
        val acknowledgesSuffix: Boolean
        when (choice) {
            ObservedCollectionChoice.WholeObservedListing -> {
                if (partial || snapshot.extent.problem != null || holes.isNotEmpty() || snapshot.extent.hasKnownHoles) {
                    invalid("collection_whole_listing_unavailable")
                }
                selected = members.map { it.reference.ordinal }.toSet()
                kind = AdmissionScopeKind.WHOLE_OBSERVED_LISTING
                acknowledgesSuffix = false
            }
            is ObservedCollectionChoice.Available -> {
                if (holes.isNotEmpty() && !choice.acknowledgesKnownHoles) invalid("collection_holes_acknowledgement_required")
                selected = members.filter(::eligibleMember).map { it.reference.ordinal }.toSet()
                kind = AdmissionScopeKind.AVAILABLE_SUBSET
                acknowledgesSuffix = choice.acknowledgesUnobservedSuffix
            }
            is ObservedCollectionChoice.Selected -> {
                selected = choice.ordinals.toSet()
                if (selected.any { ordinal -> members.none { it.reference.ordinal == ordinal && eligibleMember(it) } }) {
                    invalid("collection_selected_member_unavailable")
                }
                kind = AdmissionScopeKind.SELECTED_SUBSET
                acknowledgesSuffix = choice.acknowledgesUnobservedSuffix
            }
        }
        if (partial && !acknowledgesSuffix) invalid("collection_unobserved_suffix_acknowledgement_required")
        if (selected.isEmpty()) invalid("collection_no_included_members")
        val requestsByOrdinal = members.filter { it.reference.ordinal in selected }
            .associate { it.reference.ordinal to captures.member(snapshot, it.reference.ordinal) }
        val scope = AdmissionScope(
            AdmissionScopeId(newEntityId("collection-scope")), kind, snapshot.parentRequestId, snapshot.id, mode,
            members.mapIndexed { position, member ->
                val request = requestsByOrdinal[member.reference.ordinal]
                AdmissionScopeMember(position, CollectionSnapshotMemberRef(snapshot.id, member.reference.ordinal), request?.id,
                    if (request == null) AdmissionDisposition.EXCLUDED else AdmissionDisposition.INCLUDED,
                    if (request != null) null else exclusion(member))
            },
            acknowledgesUnobservedSuffix = acknowledgesSuffix,
            admittedEpochMs = clock.epochMs(),
        )
        val occurrences = scope.members.filter { it.disposition == AdmissionDisposition.INCLUDED }.map { member ->
            val reference = checkNotNull(member.snapshotMember)
            occurrence(requestsByOrdinal.getValue(reference.ordinal), mode, AdmissionScopeMemberRef(scope.id, member.position))
        }
        return PreparedAcquisitionAdmission(requestsByOrdinal.values.toList(), scope, occurrences, null)
    }

    private fun validateSnapshot(snapshot: CollectionSnapshot) {
        val observed = snapshot.observation ?: invalid("collection_observation_unverified")
        if (observed.id != snapshot.observationId || observed.parentRequestId != snapshot.parentRequestId ||
            snapshot.members.size != snapshot.extent.observedMemberCount || snapshot.members.size > 5_000 ||
            snapshot.members.withIndex().any { (index, member) ->
                member.reference.ordinal != index || member.entry.ordinal != index ||
                    member.reference.observationId != snapshot.observationId || member.reference.parentRequestId != snapshot.parentRequestId ||
                    member.reference.identity != observed.identity || member.reference.scope != observed.scope
            } || snapshot.extent.nonPlayableMemberCount != snapshot.members.count { !it.isVideoCandidate }) {
            invalid("collection_snapshot_membership_mismatch")
        }
        AcquisitionInputRules.completeTitle(observed.title)
    }

    private fun eligibleMember(member: CollectionMember): Boolean {
        if (!member.isVideoCandidate) return false
        val display = member.display ?: return false
        if (member.entry.ordinal != member.reference.ordinal || member.entry.id.isNullOrBlank() ||
            display.id != member.entry.id || display.title != member.entry.title || display.publicUrl != member.entry.publicUrl ||
            display.kind != AcquisitionEntryKind.VIDEO) return false
        return try { AcquisitionInputRules.completeTitle(member.entry.title.orEmpty()); true }
        catch (_: VeyraException) { false }
    }

    private fun exclusion(member: CollectionMember): AdmissionExclusion = when {
        member.entry.title.isNullOrBlank() -> AdmissionExclusion.MISSING_COMPLETE_TITLE
        member.entry.kind != AcquisitionEntryKind.VIDEO -> AdmissionExclusion.UNSUPPORTED_MEMBER
        !eligibleMember(member) -> AdmissionExclusion.KNOWN_HOLE
        else -> AdmissionExclusion.UNSELECTED
    }

    private fun freeze(request: AcquisitionRequest): AcquisitionRequest {
        AcquisitionInputRules.providerRevision(request.providerRevision)
        return request.copy(metadata = captures.freeze(request.metadata))
    }

    private fun occurrence(
        request: AcquisitionRequest,
        mode: PlaybackControlsMode,
        scopeMember: AdmissionScopeMemberRef? = null,
    ) = ExecutionOccurrence(
        ExecutionOccurrenceId(newEntityId("execution-occurrence")), AcquisitionOrigin.TYPED, request.id,
        (request.metadata as? CollectionMemberCapture)?.let { CollectionSnapshotMemberRef(it.snapshotId, it.member.reference.ordinal) },
        scopeMember, subjectId = null, bindingId = null, controlsMode = mode,
        selectionState = OccurrenceSelectionState.PENDING, admittedEpochMs = clock.epochMs(),
    )

    private fun invalid(detail: String): Nothing = throw VeyraException(AppFailure(FailureCode.INVALID_STATE, detail))
}
