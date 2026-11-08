package dev.veyra.downloads

import dev.veyra.data.executionContext
import dev.veyra.model.DownloadBatchIntent
import dev.veyra.model.DownloadClaim
import dev.veyra.model.DownloadClaimId
import dev.veyra.model.DownloadClaimOwner
import dev.veyra.data.room.AcquisitionMutation
import dev.veyra.data.room.AssetUseEntity
import dev.veyra.model.AcquisitionOutcome
import dev.veyra.model.AppFailure
import dev.veyra.model.ExecutionBindingId
import dev.veyra.model.FailureCode
import dev.veyra.model.MaterializationAssetId
import dev.veyra.model.VeyraException
import dev.veyra.playback.AcquisitionPlaybackResolver
import dev.veyra.playback.EngineCapabilities
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Saved intent must never prepare an online source when the exact local authority is absent. */
class SavedLocalOnlyResolverTest {
    @Test fun localOnlyWithoutExactUseFailsBeforeAnyRemotePreparationEvenWhenOnline() = acquisitionDownloadRoomCase {
        val owner = owner()
        val accepted = acceptNeutral(owner, label = "saved-local-only-missing", video = "ABCDEFGHIJK")
        val bindingId = assertIs<AcquisitionOutcome.Single>(accepted.outcome).bindingId
        val context = owner.store.acquisitionView().acquisition.executionContext(bindingId)
        val resolver = AcquisitionPlaybackResolver(owner.store, owner.gateway, files,
            owner.localValidator, owner.materializationEvidence)
        try {
            val failure = assertFailsWith<VeyraException> {
                resolver.resolve(context, EngineCapabilities(), offlineOnly = false, localOnly = true)
            }
            assertEquals(FailureCode.INVALID_STATE, failure.failure.code)
            assertEquals("saved_local_authority_lost", failure.failure.detail)
            assertTrue(owner.gateway.preparations.isEmpty(), "Saved intent must not prepare a remote source")
            assertTrue(owner.gateway.transfers.isEmpty(), "Saved intent must not transfer")
        } finally {
            resolver.close()
        }
    }

    @Test fun oneVerifiedCandidateWinsBesideAStaleSiblingUse() = acquisitionDownloadRoomCase {
        val owner = owner()
        val (_, accepted) = complete(owner)
        val bindingId = assertIs<AcquisitionOutcome.Single>(accepted.outcome).bindingId
        val before = owner.store.acquisitionView().acquisition
        val asset = before.materializations.getValue(before.assetUses.single {
            it.requestedBindingId == bindingId
        }.materializationAssetId)
        val job = before.physicalDownloads.values.single { it.assetId == asset.id }
        addDuplicateAsset(owner, bindingId, asset, job, stale = true)
        val context = owner.store.acquisitionView().acquisition.executionContext(bindingId)
        val resolver = AcquisitionPlaybackResolver(owner.store, owner.gateway, files,
            owner.localValidator, owner.materializationEvidence)
        try {
            val prepared = resolver.resolve(context, EngineCapabilities(), offlineOnly = false, localOnly = true)
            assertTrue(prepared.source.isLocal)
            assertEquals(asset.id, prepared.validatedMaterialization?.id)
            assertTrue(owner.gateway.preparations.isEmpty(), "Stale sibling must not cause online fallback")
        } finally {
            resolver.close()
        }
    }

    @Test fun twoVerifiedCandidatesRejectExactAmbiguousAuthority() = acquisitionDownloadRoomCase {
        val owner = owner()
        val (_, accepted) = complete(owner)
        val bindingId = assertIs<AcquisitionOutcome.Single>(accepted.outcome).bindingId
        val before = owner.store.acquisitionView().acquisition
        val asset = before.materializations.getValue(before.assetUses.single {
            it.requestedBindingId == bindingId
        }.materializationAssetId)
        val job = before.physicalDownloads.values.single { it.assetId == asset.id }
        addDuplicateAsset(owner, bindingId, asset, job, stale = false)
        val context = owner.store.acquisitionView().acquisition.executionContext(bindingId)
        val resolver = AcquisitionPlaybackResolver(owner.store, owner.gateway, files,
            owner.localValidator, owner.materializationEvidence)
        try {
            val failure = assertFailsWith<VeyraException> {
                resolver.resolve(context, EngineCapabilities(), offlineOnly = false, localOnly = true)
            }
            assertEquals(FailureCode.INVALID_STATE, failure.failure.code)
            assertEquals("saved_local_asset_ambiguous", failure.failure.detail)
            assertTrue(owner.gateway.preparations.isEmpty())
            assertTrue(owner.gateway.transfers.isEmpty())
        } finally {
            resolver.close()
        }
    }

    private suspend fun addDuplicateAsset(owner: DownloadAcquisitionRoomOwner,
        bindingId: ExecutionBindingId, asset: dev.veyra.model.MaterializationAsset,
        job: dev.veyra.model.PhysicalDownloadJob, stale: Boolean) {
        val sourcePath = Path.of(asset.uri)
        val secondPath = sourcePath.parent.resolve("authored-second-${asset.id.value}.m4a")
        Files.copy(sourcePath, secondPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val secondAsset = asset.copy(id = MaterializationAssetId("authored-second-asset"), uri = secondPath.toString())
        val secondJob = job.copy(id = "authored-second-job", assetId = secondAsset.id,
            ownershipGeneration = job.ownershipGeneration + 1)
        val claim = DownloadClaim(
            id = DownloadClaimId("authored-second-claim"),
            owner = DownloadClaimOwner.Standalone(secondJob.id),
            physicalJobId = secondJob.id,
            requestedBindingId = bindingId,
            intent = if (stale) DownloadBatchIntent.CANCELLED else DownloadBatchIntent.ACTIVE,
            retainCompletedAsset = !stale,
            updatedEpochMs = asset.completedEpochMs + 1,
        )
        owner.store.transactAcquisition { graph ->
            val withAsset = graph.withAsset(secondAsset).withJob(secondJob).withClaim(claim)
            val use = AssetUseEntity(bindingId.value, secondAsset.id.value, 1, asset.completedEpochMs + 1)
            val nextRows = withAsset.rows.copy(assetUseRows = withAsset.rows.assetUseRows + use)
            AcquisitionMutation(withAsset.copy(rows = nextRows)) { Unit }
        }
    }
}