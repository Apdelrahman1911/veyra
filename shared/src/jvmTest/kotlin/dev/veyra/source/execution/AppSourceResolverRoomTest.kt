package dev.veyra.source.execution

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.downloads.*
import dev.veyra.model.*
import dev.veyra.playback.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * Explicit ports of all sixteen historical resolver cases: ten typed-schema3 owner journeys
 * and six exported-schema2 migration/legacy guards. No legacy snapshot seed or disabled case.
 * Room/files/hash/OS leases are real; supplied source/native answers and NON-AUDIO are authored.
 * Schema3 primitive rejection revokes current evidence, NOT durable history or sibling AssetUse.
 */
class AppSourceResolverRoomTest {
    @Test fun roomCloseAndReopenValidatesHashAndNativeContractWithoutAnyGateway() = acquisitionDownloadRoomCase {
        val old = owner()
        val published = completeResolverMedia(old)
        offline(old)
        val before = old.store.acquisitionView()
        val owner = reopenResolverOwner(old)
        assertEquals(before, owner.store.acquisitionView())
        assertResolverColdEvidence(owner)
        owner.coordinator.initializeScheduling() // Room-only cold observation, not local validation.
        awaitCondition { owner.coordinator.acquisitionPresentation.value.states[published.downloadId]?.status == DownloadStatus.VERIFYING }
        assertTrue(owner.validations.isEmpty())
        val gateway = ResolverNoRemoteGateway(owner)
        val resolver = resolverFor(owner, gateway)
        owner.validation = { AssetValidation.Valid(31_234) }
        try {
            val result = resolver.resolve(published.context, EngineCapabilities(), offlineOnly = true)
            assertTrue(result.source.isLocal)
            assertEquals(published.asset.uri, result.source.uri)
            assertEquals(published.asset, result.validatedMaterialization)
            assertEquals(published.context, result.source.context)
            assertEquals(published.member.bound, owner.acquisition.boundOccurrence(
                published.member.bound.occurrence.id, published.context.binding.id))
            assertEquals(FIRST_VIDEO_ID, result.source.context.recording.canonicalId)
            assertEquals(31_234L, result.source.durationMs)
            assertNotEquals(published.asset.measuredDurationMs, result.source.durationMs)
            assertNotEquals(published.member.item.durationMs, result.source.durationMs)
            assertEquals(listOf(published.asset.validationInput()), owner.validations.toList())
            resolver.requireAdmissible(result)
            assertResolverColdEvidence(owner) // A resolver success cannot mint physical C1.
            assertEquals(before, owner.store.acquisitionView())
            gateway.assertNoNewWork()
            assertSQLite()
        } finally { resolver.close() }
    }

    @Test fun publisherTitleCorrectionPreservesHistoricalOfflineReceiptAndSelectedVideo() = acquisitionDownloadRoomCase {
        val old = owner()
        val published = completeResolverMedia(old)
        val before = old.store.acquisitionView().acquisition
        val corrected = published.member.item.copy(title = "Corrected full publisher title — العنوان المصحح")
        old.store.upsertCatalog(listOf(corrected)).getOrThrow()
        val correctedView = old.store.acquisitionView()
        assertResolverHistory(before, correctedView.acquisition)
        val owner = reopenResolverOwner(old)
        val gateway = ResolverNoRemoteGateway(owner)
        val resolver = resolverFor(owner, gateway)
        try {
            val result = resolver.resolve(published.context, EngineCapabilities(), offlineOnly = true)
            assertEquals(corrected, owner.store.acquisitionView().snapshot.media[corrected.id])
            assertEquals(published.context, result.source.context)
            assertEquals(published.member.item, assertIs<MediaCapture>(result.source.context.request.metadata).media)
            assertEquals(published.member.item.title, result.source.context.request.completeTitle)
            assertEquals(published.member.item.title, result.source.context.receipt.observation.queryTitle)
            assertEquals(FIRST_VIDEO_ID, result.source.context.recording.canonicalId)
            assertTrue(result.source.isLocal)
            assertEquals(correctedView, owner.store.acquisitionView())
            assertResolverColdEvidence(owner)
            gateway.assertNoNewWork()
        } finally { resolver.close() }
    }

    // Historical method #3 expected durable FAILED. The typed port honestly names the new
    // process-local contract and preserves the old source/method span in the evidence packet.
    @Test fun sameSizeCorruptionRevokesCurrentEvidenceWithoutRewritingHistoryOrOnlineFallback() = acquisitionDownloadRoomCase {
        val old = owner()
        val published = completeResolverMedia(old)
        val before = old.store.acquisitionView()
        val checks = old.materializationEvidence.checks.value
        val c0 = checks.successfulChecks.getValue(published.downloadId)
        val nativeBefore = old.validations.size
        val closedAfterRevocation = AtomicInteger()
        val gateway = ResolverNoRemoteGateway(old)
        val resolver = resolverFor(old, gateway)
        leases.beforeClose = { _, _ ->
            assertFalse(published.downloadId in old.materializationEvidence.checks.value.currentValidAssets)
            assertSame(c0, old.materializationEvidence.checks.value.successfulChecks[published.downloadId])
            closedAfterRevocation.incrementAndGet()
        }
        try {
            val corrupted = Files.readAllBytes(Path.of(published.asset.uri))
            corrupted[0] = (corrupted[0].toInt() xor 1).toByte()
            Files.write(Path.of(published.asset.uri), corrupted)
            assertEquals(published.asset.sizeBytes, Files.size(Path.of(published.asset.uri)))
            assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> {
                resolver.resolve(published.context, EngineCapabilities(), offlineOnly = false)
            }.failure.code)
            assertEquals(nativeBefore, old.validations.size)
            assertTrue(closedAfterRevocation.get() > 0, "Revocation must precede actual path-lease cleanup")
            assertSame(c0, old.materializationEvidence.checks.value.successfulChecks[published.downloadId])
            assertFalse(published.downloadId in old.materializationEvidence.checks.value.currentValidAssets)
            assertEquals(published.asset, checks.currentValidAssets[published.downloadId], "Prior immutable observation stays historical")
            assertEquals(before, old.store.acquisitionView())
            awaitCondition { old.coordinator.acquisitionPresentation.value.states[published.downloadId]?.status == DownloadStatus.VERIFYING }
            assertEquals(DownloadStatus.COMPLETE, before.acquisition.physicalDownloads.getValue(published.downloadId).status)
            gateway.assertNoNewWork()
        } finally { leases.beforeClose = { _, _ -> }; resolver.close() }
        val owner = reopenResolverOwner(old)
        assertResolverColdEvidence(owner)
        assertEquals(before, owner.store.acquisitionView())
        owner.coordinator.initializeScheduling()
        awaitCondition { owner.coordinator.acquisitionPresentation.value.states[published.downloadId]?.status == DownloadStatus.VERIFYING }
        val coldGateway = ResolverNoRemoteGateway(owner)
        val coldResolver = resolverFor(owner, coldGateway)
        try {
            assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> {
                coldResolver.resolve(published.context, EngineCapabilities(), offlineOnly = false)
            }.failure.code)
            assertTrue(owner.validations.isEmpty())
            assertResolverColdEvidence(owner)
            assertEquals(before, owner.store.acquisitionView())
            coldGateway.assertNoNewWork()
        } finally { coldResolver.close() }
    }

    @Test fun missingFileLosesReadyStatusWithoutRemoteReplacement() = acquisitionDownloadRoomCase {
        val old = owner()
        val published = completeResolverMedia(old)
        val before = old.store.acquisitionView()
        val c0 = old.materializationEvidence.checks.value.successfulChecks.getValue(published.downloadId)
        val nativeBefore = old.validations.size
        Files.delete(Path.of(published.asset.uri))
        val gateway = ResolverNoRemoteGateway(old)
        val resolver = resolverFor(old, gateway)
        try {
            assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> {
                resolver.resolve(published.context, EngineCapabilities(), offlineOnly = false)
            }.failure.code)
            assertEquals(nativeBefore, old.validations.size)
            assertSame(c0, old.materializationEvidence.checks.value.successfulChecks[published.downloadId])
            assertFalse(published.downloadId in old.materializationEvidence.checks.value.currentValidAssets)
            assertEquals(before, old.store.acquisitionView())
            awaitCondition { old.coordinator.acquisitionPresentation.value.states[published.downloadId]?.status == DownloadStatus.VERIFYING }
            gateway.assertNoNewWork()
        } finally { resolver.close() }
        val owner = reopenResolverOwner(old)
        assertEquals(before, owner.store.acquisitionView())
        assertResolverColdEvidence(owner)
        owner.coordinator.initializeScheduling()
        awaitCondition { owner.coordinator.acquisitionPresentation.value.states[published.downloadId]?.status == DownloadStatus.VERIFYING }
        assertTrue(owner.validations.isEmpty())
        assertTrue(owner.selector.requests.isEmpty())
        assertTrue(owner.gateway.preparations.isEmpty())
        assertFalse(Files.exists(Path.of(published.asset.uri)))
    }

    @Test fun nativeValidatorRejectionIsNotOverriddenByMatchingByteHash() = acquisitionDownloadRoomCase {
        val owner = owner()
        val first = admitResolverMedia(owner)
        val second = admitResolverMedia(owner, first.item)
        val published = publishResolverMedia(owner, first)
        owner.coordinator.start(DownloadActivation.USER_SESSION)
        assertIs<AcquisitionDownloadAssetCheck.Validated>(owner.adapter.validateMemberAsset(
            second.batch.id, second.member.position, second.batch.intentRevision, published.asset.id))
        idle(owner)
        // Keep this a resolver rejection: USER_SESSION would otherwise admit a separate
        // scheduled physical revalidation as soon as the resolver revokes current evidence.
        owner.coordinator.drainForBackground()
        val before = owner.store.acquisitionView()
        assertEquals(setOf(first.context.binding.id, second.context.binding.id), before.acquisition.assetUses.map { it.requestedBindingId }.toSet())
        val c0 = owner.materializationEvidence.checks.value.successfulChecks.getValue(published.downloadId)
        val nativeBefore = owner.validations.size
        val closedAfterRevocation = AtomicInteger()
        val rejection = AppFailure(FailureCode.UNSUPPORTED_CODEC, "authored_explicit_native_rejection")
        owner.validation = { AssetValidation.Invalid(rejection) }
        leases.beforeClose = { _, _ ->
            assertFalse(published.downloadId in owner.materializationEvidence.checks.value.currentValidAssets)
            closedAfterRevocation.incrementAndGet()
        }
        val gateway = ResolverNoRemoteGateway(owner)
        val resolver = resolverFor(owner, gateway)
        try {
            assertEquals(rejection, assertFailsWith<VeyraException> {
                resolver.resolve(published.context, EngineCapabilities(), offlineOnly = false)
            }.failure)
            assertEquals(nativeBefore + 1, owner.validations.size)
            assertContentEquals(PHYSICAL_BYTES, Files.readAllBytes(Path.of(published.asset.uri)))
            assertTrue(closedAfterRevocation.get() > 0)
            assertSame(c0, owner.materializationEvidence.checks.value.successfulChecks[published.downloadId])
            assertFalse(published.downloadId in owner.materializationEvidence.checks.value.currentValidAssets)
            assertEquals(before, owner.store.acquisitionView(), "Neither R1 nor explicitly validated R2 history is rewritten")
            gateway.assertNoNewWork()
        } finally { leases.beforeClose = { _, _ -> }; resolver.close() }
    }

    @Test fun mismatchedAssetAndDownloadReceiptsFailBeforeFileOrNativeUse() =
        resolverSchema2GuardCase(ResolverSchema2Shape.DIFFERENT_ASSET_BINDING)

    @Test fun legacyUnboundLocalBytesCannotBypassFirstResultPolicy() =
        resolverSchema2GuardCase(ResolverSchema2Shape.UNBOUND)

    @Test fun missingAssetRepresentationIsRejectedBeforeNativeValidationAfterRoomRestart() =
        resolverSchema2GuardCase(ResolverSchema2Shape.MISSING_ASSET_REPRESENTATION)

    @Test fun blankAssetRepresentationIsRejectedEvenWhenRecordMatchesAfterRoomRestart() {
        resolverSchema2GuardCase(ResolverSchema2Shape.EMPTY_MATCHING_REPRESENTATIONS)
        resolverSchema2GuardCase(ResolverSchema2Shape.WHITESPACE_MATCHING_REPRESENTATIONS)
    }

    @Test fun missingRecordRepresentationIsRejectedBeforeNativeValidationAfterRoomRestart() =
        resolverSchema2GuardCase(ResolverSchema2Shape.MISSING_RECORD_REPRESENTATION)

    @Test fun mismatchedRecordRepresentationIsRejectedBeforeNativeValidationAfterRoomRestart() =
        resolverSchema2GuardCase(ResolverSchema2Shape.DIFFERENT_RECORD_REPRESENTATION)

    @Test fun unsupportedEngineDoesNotRelabelAValidArtifactAsCorrupt() = acquisitionDownloadRoomCase {
        val owner = owner()
        val published = completeResolverMedia(owner)
        val before = owner.store.acquisitionView()
        val checks = owner.materializationEvidence.checks.value
        val nativeBefore = owner.validations.size
        val pathAttempts = AtomicInteger()
        leases.refuse = { pathAttempts.incrementAndGet(); false }
        val gateway = ResolverNoRemoteGateway(owner)
        val resolver = resolverFor(owner, gateway)
        try {
            assertEquals(FailureCode.UNSUPPORTED_CODEC, assertFailsWith<VeyraException> {
                resolver.resolve(published.context, EngineCapabilities(mimeTypes = setOf("audio/mp4"), codecs = setOf("aac")), offlineOnly = true)
            }.failure.code)
            assertEquals(FailureCode.UNSUPPORTED_TRANSPORT, assertFailsWith<VeyraException> {
                resolver.resolve(published.context, EngineCapabilities(supportsLocalFiles = false), offlineOnly = true)
            }.failure.code)
            assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> {
                resolver.resolve(published.context, EngineCapabilities(), offlineOnly = true, requiredRepresentationId = "different-key")
            }.failure.code)
            assertEquals(0, pathAttempts.get())
            assertEquals(nativeBefore, owner.validations.size)
            assertSame(checks, owner.materializationEvidence.checks.value)
            owner.validation = { AssetValidation.Valid() }
            val result = resolver.resolve(published.context, EngineCapabilities(), offlineOnly = true)
            assertEquals(published.asset.measuredDurationMs, result.source.durationMs)
            assertNotEquals(published.member.item.durationMs, result.source.durationMs)
            assertTrue(result.source.isLocal)
            assertSame(checks, owner.materializationEvidence.checks.value)
            assertEquals(before, owner.store.acquisitionView())
            gateway.assertNoNewWork()
        } finally { leases.refuse = { false }; resolver.close() }
    }

    @Test fun aDifferentRequestedSessionCannotSilentlyUseCompletedAudioFromAnotherBinding() = acquisitionDownloadRoomCase {
        val owner = owner()
        val first = admitResolverMedia(owner)
        val sameC = admitResolverMedia(owner, first.item)
        val differentC = admitResolverMedia(owner, first.item, video = LATER_VIDEO_ID, admitPhysical = false)
        assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> {
            owner.adapter.admitMember(differentC.batch.id, differentC.member.position, differentC.batch.intentRevision)
        }.failure.code)
        // Its genuine bound request/occurrence remains testable without a pending batch worker
        // repeatedly attempting the already-rejected different-C physical admission.
        owner.batches.setIntent(differentC.batch.id, differentC.batch.intentRevision, DownloadBatchIntent.PAUSED)
        val published = publishResolverMedia(owner, first)
        val before = owner.store.acquisitionView()
        val checks = owner.materializationEvidence.checks.value
        assertNotEquals(first.context.request.id, sameC.context.request.id)
        assertNotEquals(first.context.binding.id, sameC.context.binding.id)
        assertNotEquals(first.bound.occurrence.id, sameC.bound.occurrence.id)
        assertEquals(first.context.recording, sameC.context.recording)
        assertEquals(first.downloadId, sameC.downloadId)
        val secondClaim = before.acquisition.claims.getValue(assertNotNull(sameC.member.claimId))
        assertEquals(sameC.context.binding.id, secondClaim.requestedBindingId)
        assertEquals(DownloadClaimOwner.BatchMember(sameC.batch.id, sameC.member.position), secondClaim.owner)
        assertEquals(listOf(first.context.binding.id), before.acquisition.assetUses.map { it.requestedBindingId })
        assertEquals(first.context.binding.id, published.asset.producer.bindingId)
        assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> {
            owner.acquisition.boundOccurrence(sameC.bound.occurrence.id, first.context.binding.id, before.acquisition)
        }.failure.code)
        val nativeBefore = owner.validations.size
        val pathAttempts = AtomicInteger()
        leases.refuse = { pathAttempts.incrementAndGet(); false }
        val gateway = ResolverNoRemoteGateway(owner)
        val resolver = resolverFor(owner, gateway)
        try {
            for (request in listOf(sameC, differentC)) {
                assertEquals(FailureCode.OFFLINE_POLICY, assertFailsWith<VeyraException> {
                    resolver.resolve(request.context, EngineCapabilities(), offlineOnly = true)
                }.failure.code)
            }
            // Online preparation of the exact accepted C is a separate allowed policy; this
            // test does not invent an unconditional online denial for a merely absent R2 use.
            assertEquals(0, pathAttempts.get())
            assertEquals(nativeBefore, owner.validations.size)
            assertSame(checks, owner.materializationEvidence.checks.value)
            assertEquals(first.context, resolver.resolve(first.context, EngineCapabilities(), offlineOnly = true).source.context)
            assertEquals(before, owner.store.acquisitionView())
            assertSame(checks, owner.materializationEvidence.checks.value)
            gateway.assertNoNewWork()
        } finally { leases.refuse = { false }; resolver.close() }
    }

    @Test fun roomRejectsOriginalSourceReassignmentWithoutChangingHistoricalOfflineReceipt() = acquisitionDownloadRoomCase {
        val old = owner()
        val published = completeResolverMedia(old)
        val before = old.store.acquisitionView()
        val item = published.member.item
        assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> {
            old.store.upsertCatalog(listOf(item.copy(source = item.source.copy(canonicalId = "another-original")))).getOrThrow()
        }.failure.code)
        assertEquals(before, old.store.acquisitionView())
        val owner = reopenResolverOwner(old)
        val gateway = ResolverNoRemoteGateway(owner)
        val resolver = resolverFor(owner, gateway)
        try {
            assertEquals(before, owner.store.acquisitionView())
            assertEquals(item, owner.store.state.value.media[item.id])
            val result = resolver.resolve(published.context, EngineCapabilities(), offlineOnly = true)
            assertEquals(published.context, result.source.context)
            assertEquals(FIRST_VIDEO_ID, result.source.context.recording.canonicalId)
            assertEquals(before, owner.store.acquisitionView())
            gateway.assertNoNewWork()
        } finally { resolver.close() }
    }

    @Test fun durableCleanupIntentBlocksNativeReopenAndRemoteFallbackWithoutCorruptingTheAsset() = acquisitionDownloadRoomCase {
        val publisher = owner()
        val published = completeResolverMedia(publisher)
        // A fresh cold owner lets real intent/reconciliation settle BEFORE activating cleanup.
        // Otherwise reconciliation could race/cancel the very cleanup epoch held below.
        val old = reopenResolverOwner(publisher)
        val history = old.store.acquisitionView().acquisition
        val deleting = barrier()
        old.beforeDelete = { assertEquals(published.asset, it); deleting.stop() }
        val cancelled = old.batches.setIntent(published.member.batch.id, published.member.batch.intentRevision,
            DownloadBatchIntent.CANCELLED, retainCompletedAssets = false)
        val reconciled = old.adapter.reconcileMember(cancelled.id, published.member.member.position, cancelled.intentRevision)
        assertIs<AcquisitionDownloadDurableSettlement.Confirmed>(reconciled.durable)
        assertFalse(deleting.entered.isCompleted, "Cold metadata admission is not physical deletion")
        old.coordinator.start(DownloadActivation.DURABLE_WORK)
        deleting.entered.await()
        val reserved = assertNotNull(old.repository.physicalDownload(published.downloadId))
        assertTrue(reserved.job.cleanupPending)
        assertEquals(PhysicalDownloadMaintenanceKind.CLEANUP, reserved.job.maintenanceKind)
        assertEquals(published.asset, assertNotNull(reserved.cleanupReservation).asset)
        assertNull(files.tryLeasePaths(listOf(published.asset.uri)), "Actual cleanup owner holds the reserved path")
        // Close the actual owner BEFORE deletion; its fenced non-success finish retains the
        // irreversible reservation. This is an app-close/reopen, not background-resume proof.
        old.coordinator.close()
        deleting.release()
        val retained = old.store.acquisitionView()
        val retainedJob = retained.acquisition.physicalDownloads.getValue(published.downloadId)
        assertTrue(retainedJob.cleanupPending)
        assertEquals(PhysicalDownloadMaintenanceKind.CLEANUP, retainedJob.maintenanceKind)
        assertNull(retainedJob.leaseOwner)
        assertEquals(history.materializations, retained.acquisition.materializations)
        assertResolverHistory(history, retained.acquisition)
        assertTrue(Files.isRegularFile(Path.of(published.asset.uri)))
        assertNotNull(files.tryLeasePaths(listOf(published.asset.uri))).close()
        val owner = reopenResolverOwner(old)
        assertEquals(retained, owner.store.acquisitionView())
        assertResolverColdEvidence(owner)
        val gateway = ResolverNoRemoteGateway(owner)
        val resolver = resolverFor(owner, gateway)
        val pathAttempts = AtomicInteger()
        leases.refuse = { pathAttempts.incrementAndGet(); false }
        try {
            assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> {
                resolver.resolve(published.context, EngineCapabilities(), offlineOnly = false)
            }.failure.code)
            assertEquals(0, pathAttempts.get())
            assertTrue(owner.validations.isEmpty())
            assertEquals(retained, owner.store.acquisitionView())
            assertTrue(Files.isRegularFile(Path.of(published.asset.uri)))
            gateway.assertNoNewWork()
        } finally { leases.refuse = { false }; resolver.close() }
        owner.beforeDelete = { assertEquals(published.asset, it) }
        owner.coordinator.start(DownloadActivation.USER_SESSION)
        awaitState(owner.store) { it.physicalDownloads[published.downloadId]?.let { job -> !job.cleanupPending && job.assetId == null } == true }
        idle(owner)
        val after = owner.store.acquisitionView().acquisition
        assertFalse(Files.exists(Path.of(published.asset.uri)))
        assertEquals(DownloadStatus.CANCELLED, after.physicalDownloads.getValue(published.downloadId).status)
        assertEquals(retained.acquisition.claims, after.claims)
        assertResolverHistory(retained.acquisition, after)
        gateway.assertNoNewWork()
    }

    @Test fun cleanupBeginningDuringValidationCannotReturnTheArtifactForNativeHandoff() = acquisitionDownloadRoomCase {
        val owner = owner()
        val published = completeResolverMedia(owner)
        val before = owner.store.acquisitionView()
        val checks = owner.materializationEvidence.checks.value
        val native = barrier()
        val deleting = barrier()
        owner.validation = { native.stop(); AssetValidation.Valid(31_234) }
        owner.beforeDelete = { assertEquals(published.asset, it); deleting.stop() }
        val gateway = ResolverNoRemoteGateway(owner)
        val resolver = resolverFor(owner, gateway)
        try {
            supervisorScope {
                val pending = async { resolver.resolve(published.context, EngineCapabilities(), offlineOnly = false) }
                var removal: Deferred<AcquisitionDownloadReconciliation>? = null
                try {
                    native.entered.await()
                    assertNull(files.tryLeasePaths(listOf(published.asset.uri)))
                    // Another coroutine owns removal. Never await its full drain from inside
                    // the validator that itself holds the awaited original reader/path lease.
                    removal = async {
                        val cancelled = owner.batches.setIntent(published.member.batch.id, published.member.batch.intentRevision,
                            DownloadBatchIntent.CANCELLED, retainCompletedAssets = false)
                        owner.adapter.reconcileMember(cancelled.id, published.member.member.position, cancelled.intentRevision)
                    }
                    awaitState(owner.store) { it.physicalDownloads[published.downloadId]?.cleanupPending == true }
                    // The real scheduler attempts cleanup, cannot acquire the resolver's path,
                    // and records its real local deferral. Honor that deadline; no busy loop or
                    // invented lease-release/clock-expiry acknowledgement substitutes for it.
                    val deferred = idle(owner)
                    val cleanupDeadline = assertNotNull(deferred.plan.nextLocalEpochMs)
                    assertTrue(cleanupDeadline > clock.epochMs())
                    assertFalse(pending.isCompleted)
                    assertFalse(deleting.entered.isCompleted, "Cleanup cannot acquire the real path before validator release")
                    assertTrue(Files.exists(Path.of(published.asset.uri)))
                    native.release()
                    assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> { pending.await() }.failure.code)
                    assertNotNull(removal).await()
                    clock.advance(cleanupDeadline - clock.epochMs())
                    owner.coordinator.onAcquisitionBatchChanged()
                    deleting.entered.await() // Separate physical owner, only after resolver relinquished its lease.
                    val held = owner.store.acquisitionView().acquisition
                    assertEquals(before.acquisition.materializations, held.materializations)
                    assertResolverHistory(before.acquisition, held)
                    assertSame(checks, owner.materializationEvidence.checks.value, "Relational rejoin refusal is not a primitive negative")
                    assertTrue(Files.exists(Path.of(published.asset.uri)))
                    deleting.release()
                    awaitState(owner.store) { it.physicalDownloads[published.downloadId]?.let { job -> !job.cleanupPending && job.assetId == null } == true }
                    idle(owner)
                    assertFalse(Files.exists(Path.of(published.asset.uri)))
                    assertNull(owner.materializationEvidence.checks.value.successfulChecks[published.downloadId], "Only actual deletion forgets C0")
                    assertNotNull(files.tryLeasePaths(listOf(published.asset.uri))).close()
                    gateway.assertNoNewWork()
                } finally {
                    native.release(); deleting.release()
                    pending.cancelAndJoin(); removal?.cancelAndJoin()
                }
            }
        } finally { resolver.close() }
    }
}
