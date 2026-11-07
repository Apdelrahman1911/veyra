package dev.veyra.downloads

import dev.veyra.model.DownloadBatchId
import dev.veyra.model.DownloadBatchIntent
import dev.veyra.model.DownloadClaim
import dev.veyra.model.DownloadClaimId
import dev.veyra.model.DownloadClaimOwner
import dev.veyra.model.ExecutionBindingId
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Pure demand projection only; these tests do not claim Room, cleanup or physical authority. */
class DownloadClaimPolicyTest {
    @Test fun oneActiveOwnerKeepsTheJobActiveWhenOtherOwnersArePausedOrRemoved() {
        val result = summarizeDownloadClaims(JOB, listOf(
            claim(0, DownloadBatchIntent.PAUSED), claim(1, DownloadBatchIntent.CANCELLED, false),
            claim(2, DownloadBatchIntent.ACTIVE), claim(3, DownloadBatchIntent.CANCELLED, true),
        ))
        assertEquals(DownloadClaimSummary(DownloadClaimDemand.CONTINUE, 1, 1, 1, 1), result)
    }

    @Test fun aPausedOwnerKeepsPartialAndCompletedDataDespiteAnotherOwnersRemoval() {
        val result = summarizeDownloadClaims(JOB, listOf(
            claim(0, DownloadBatchIntent.PAUSED, false), claim(1, DownloadBatchIntent.CANCELLED, false),
        ))
        assertEquals(DownloadClaimDemand.PAUSE_AND_RETAIN, result.demand)
        assertEquals(1, result.pausedOwners)
    }

    @Test fun aCancelledRetainingOwnerPreservesCompletedDataButDoesNotContinueAnUnfinishedTransfer() {
        assertEquals(DownloadClaimDemand.CANCEL_UNFINISHED_KEEP_COMPLETE,
            summarizeDownloadClaims(JOB, listOf(claim(0, DownloadBatchIntent.CANCELLED, true))).demand)
    }

    @Test fun completedRetentionSurvivesOtherCancelledNonretainingOwners() {
        assertEquals(DownloadClaimDemand.CANCEL_UNFINISHED_KEEP_COMPLETE,
            summarizeDownloadClaims(JOB, listOf(
                claim(0, DownloadBatchIntent.CANCELLED, false), claim(1, DownloadBatchIntent.CANCELLED, true),
            )).demand)
    }

    @Test fun onlyAnExplicitNonemptyEntirelyCancelledNonretainingJoinIsARemovalCandidate() {
        val result = summarizeDownloadClaims(JOB, listOf(
            claim(0, DownloadBatchIntent.CANCELLED, false), claim(1, DownloadBatchIntent.CANCELLED, false),
        ))
        assertEquals(DownloadClaimSummary(DownloadClaimDemand.REMOVE_CANDIDATE, 0, 0, 0, 2), result)
        // This enum is still only demand. No file/removal capability is created by this call.
    }

    @Test fun anEmptyJoinDoesNotTreatAnUnmigratedLegacyOwnerAsPermissionToRemove() {
        assertEquals(DownloadClaimSummary(DownloadClaimDemand.UNOWNED_UNKNOWN, 0, 0, 0, 0),
            summarizeDownloadClaims(JOB, emptyList()))
    }

    @Test fun realStandaloneOwnersParticipateAlongsideBatchMembers() {
        val standalone = claim(1, DownloadBatchIntent.PAUSED).copy(owner = DownloadClaimOwner.Standalone(JOB))
        assertEquals(DownloadClaimDemand.PAUSE_AND_RETAIN,
            summarizeDownloadClaims(JOB, listOf(claim(0, DownloadBatchIntent.CANCELLED, false), standalone)).demand)
    }

    @Test fun claimsForAnotherPhysicalJobAreRejectedNotFilteredIntoAnEmptyOrRemovableSet() {
        invalid(listOf(claim(0, DownloadBatchIntent.CANCELLED, false).copy(physicalJobId = "other-job")))
    }

    @Test fun aStandaloneOwnerMustNameThisExactPhysicalJob() {
        invalid(listOf(claim(0, DownloadBatchIntent.ACTIVE).copy(owner = DownloadClaimOwner.Standalone("other-job"))))
    }

    @Test fun aMigratedStandaloneOwnerMustNameThisExactPhysicalJobEvenWithUnknownBinding() {
        invalid(listOf(claim(0, DownloadBatchIntent.CANCELLED, false).copy(
            owner = DownloadClaimOwner.Schema2Standalone("other-job"), requestedBindingId = null,
        )))
    }

    @Test fun aMigratedUnknownBindingDoesNotEraseItsActualStandalonePauseOrRetention() {
        val standalone = claim(0, DownloadBatchIntent.PAUSED).copy(
            owner = DownloadClaimOwner.Schema2Standalone(JOB), requestedBindingId = null,
        )
        val removal = claim(1, DownloadBatchIntent.CANCELLED, false)
        assertEquals(DownloadClaimDemand.PAUSE_AND_RETAIN,
            summarizeDownloadClaims(JOB, listOf(standalone, removal)).demand)
        assertEquals(DownloadClaimDemand.CANCEL_UNFINISHED_KEEP_COMPLETE,
            summarizeDownloadClaims(JOB, listOf(standalone.copy(intent = DownloadBatchIntent.CANCELLED), removal)).demand)
    }

    @Test fun migratedAndTypedStandaloneVariantsCannotOccupyTheSameSingletonOwnerSlot() {
        val legacy = claim(0, DownloadBatchIntent.ACTIVE).copy(
            owner = DownloadClaimOwner.Schema2Standalone(JOB), requestedBindingId = null,
        )
        val typed = claim(1, DownloadBatchIntent.CANCELLED, false).copy(owner = DownloadClaimOwner.Standalone(JOB))
        invalid(listOf(legacy, typed))
    }

    @Test fun duplicateClaimIdsAreInvalidEvenIfTheirOtherValuesMatch() {
        val original = claim(0, DownloadBatchIntent.ACTIVE)
        invalid(listOf(original, original))
    }

    @Test fun theSameMemberCannotAcquireASecondClaimToDistortAggregateDemand() {
        val original = claim(0, DownloadBatchIntent.ACTIVE)
        invalid(listOf(original, original.copy(id = DownloadClaimId("different-claim"), intent = DownloadBatchIntent.CANCELLED)))
    }

    @Test fun distinctOccurrencesKeepTheirSeparateOwnersEvenIfTheyShareTheRequestedBinding() {
        val first = claim(0, DownloadBatchIntent.ACTIVE)
        val second = claim(1, DownloadBatchIntent.PAUSED).copy(requestedBindingId = first.requestedBindingId)
        assertEquals(DownloadClaimSummary(DownloadClaimDemand.CONTINUE, 1, 1, 0, 0),
            summarizeDownloadClaims(JOB, listOf(first, second)))
    }

    @Test fun demandDoesNotDependOnClaimOrderOrKeepACallerMutableList() {
        val claims = mutableListOf(claim(0, DownloadBatchIntent.PAUSED), claim(1, DownloadBatchIntent.CANCELLED, false))
        val before = summarizeDownloadClaims(JOB, claims)
        assertEquals(before, summarizeDownloadClaims(JOB, claims.reversed()))
        claims.clear()
        assertEquals(DownloadClaimDemand.PAUSE_AND_RETAIN, before.demand)
        assertEquals(DownloadClaimDemand.UNOWNED_UNKNOWN, summarizeDownloadClaims(JOB, claims).demand)
    }

    private fun invalid(claims: List<DownloadClaim>) {
        val error = assertFailsWith<VeyraException> { summarizeDownloadClaims(JOB, claims) }
        assertEquals(FailureCode.RECOVERY_REQUIRED, error.failure.code)
        assertEquals("download_claim_join_invalid", error.failure.detail)
    }

    private fun claim(position: Int, intent: DownloadBatchIntent, retain: Boolean = true) = DownloadClaim(
        DownloadClaimId("claim-$position"), DownloadClaimOwner.BatchMember(DownloadBatchId("batch"), position),
        JOB, ExecutionBindingId("requested-binding-$position"), intent, retain, 1_000,
    )

    private companion object { const val JOB = "physical-job" }
}
