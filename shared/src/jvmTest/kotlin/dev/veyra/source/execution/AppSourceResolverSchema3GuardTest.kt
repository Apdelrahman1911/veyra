package dev.veyra.source.execution

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.data.room.*
import dev.veyra.downloads.*
import dev.veyra.model.*
import dev.veyra.playback.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Additional active controls; these do not replace or conceal the sixteen mapped old methods. */
class AppSourceResolverSchema3GuardTest {
    @Test fun genericThrownNativeFailureWithTheSameCodeIsNotPrimitiveNegativeEvidence() = acquisitionDownloadRoomCase {
        val owner = owner()
        val published = completeResolverMedia(owner)
        val before = owner.store.acquisitionView()
        val checks = owner.materializationEvidence.checks.value
        val gateway = ResolverNoRemoteGateway(owner)
        val resolver = resolverFor(owner, gateway)
        try {
            for (code in listOf(FailureCode.CORRUPT_MEDIA, FailureCode.UNSUPPORTED_CODEC, FailureCode.STORAGE_IO)) {
                val thrown = VeyraException(AppFailure(code, "authored_thrown_not_explicit_invalid"))
                val nativeBefore = owner.validations.size
                owner.validation = { throw thrown }
                assertSame(thrown, assertFailsWith<VeyraException> {
                    resolver.resolve(published.context, EngineCapabilities(), offlineOnly = true)
                })
                assertEquals(nativeBefore + 1, owner.validations.size)
                assertSame(checks, owner.materializationEvidence.checks.value)
                assertEquals(before, owner.store.acquisitionView())
                assertContentEquals(PHYSICAL_BYTES, Files.readAllBytes(Path.of(published.asset.uri)))
                assertNotNull(files.tryLeasePaths(listOf(published.asset.uri))).close()
                gateway.assertNoNewWork()
            }
        } finally { resolver.close() }
    }

    @Test fun actualCancellationDuringNativeValidationReleasesItsLeaseWithoutRevokingC0() = acquisitionDownloadRoomCase {
        val owner = owner()
        val published = completeResolverMedia(owner)
        val before = owner.store.acquisitionView()
        val checks = owner.materializationEvidence.checks.value
        val nativeBefore = owner.validations.size
        val native = barrier()
        val cancellationSeen = AtomicBoolean()
        owner.validation = {
            try { native.stop(); AssetValidation.Valid(31_234) }
            catch (cancelled: CancellationException) { cancellationSeen.set(true); throw cancelled }
        }
        val gateway = ResolverNoRemoteGateway(owner)
        val resolver = resolverFor(owner, gateway)
        try {
            supervisorScope {
                val pending = async { resolver.resolve(published.context, EngineCapabilities(), offlineOnly = true) }
                try {
                    native.entered.await()
                    assertNull(files.tryLeasePaths(listOf(published.asset.uri)), "Original suspended validator owns the real path")
                    pending.cancelAndJoin()
                    assertTrue(pending.isCancelled)
                    assertFailsWith<CancellationException> { pending.await() }
                    assertTrue(cancellationSeen.get())
                    assertEquals(nativeBefore + 1, owner.validations.size)
                    assertSame(checks, owner.materializationEvidence.checks.value)
                    assertEquals(before, owner.store.acquisitionView())
                    assertNull(resolver.retainedReaderFailure())
                    assertNotNull(files.tryLeasePaths(listOf(published.asset.uri))).close()
                    gateway.assertNoNewWork()
                } finally { native.release(); pending.cancelAndJoin() }
            }
        } finally { resolver.close() }
    }

    @Test fun exactUnacknowledgedNativeReaderRetainsTheOriginalLeaseUntilPositiveRelease() =
        retainedResolverReaderCase(mismatchedInput = false, cancelInvocation = false)

    @Test fun mismatchedReportedNativeInputStillCannotReleaseTheOriginalOwnedPath() =
        retainedResolverReaderCase(mismatchedInput = true, cancelInvocation = false)

    @Test fun cancellationWithUnacknowledgedNativeCleanupStillRetainsTheOriginalReader() =
        retainedResolverReaderCase(mismatchedInput = false, cancelInvocation = true)

    @Test fun actualPostNativeSameSizeMutationRevokesBeforeLeaseCloseWithoutRewritingRoom() = acquisitionDownloadRoomCase {
        val owner = owner()
        val published = completeResolverMedia(owner)
        val before = owner.store.acquisitionView()
        val c0 = owner.materializationEvidence.checks.value.successfulChecks.getValue(published.downloadId)
        val nativeBefore = owner.validations.size
        val closedAfterRevocation = AtomicInteger()
        owner.validation = {
            val bytes = Files.readAllBytes(Path.of(published.asset.uri))
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
            Files.write(Path.of(published.asset.uri), bytes)
            AssetValidation.Valid(31_234) // Real post-hash detects mutation, not a fake Invalid.
        }
        leases.beforeClose = { _, _ ->
            assertFalse(published.downloadId in owner.materializationEvidence.checks.value.currentValidAssets)
            assertSame(c0, owner.materializationEvidence.checks.value.successfulChecks[published.downloadId])
            closedAfterRevocation.incrementAndGet()
        }
        val gateway = ResolverNoRemoteGateway(owner)
        val resolver = resolverFor(owner, gateway)
        try {
            assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> {
                resolver.resolve(published.context, EngineCapabilities(), offlineOnly = false)
            }.failure.code)
            assertEquals(nativeBefore + 1, owner.validations.size)
            assertEquals(published.asset.sizeBytes, Files.size(Path.of(published.asset.uri)))
            assertTrue(closedAfterRevocation.get() > 0)
            assertSame(c0, owner.materializationEvidence.checks.value.successfulChecks[published.downloadId])
            assertFalse(published.downloadId in owner.materializationEvidence.checks.value.currentValidAssets)
            assertEquals(before, owner.store.acquisitionView())
            gateway.assertNoNewWork()
        } finally { leases.beforeClose = { _, _ -> }; resolver.close() }
    }

    @Test fun canonicalPendingPublicationRejectsMalformedIdentityWithoutMintingUseOrTouchingSiblingC0() = acquisitionDownloadRoomCase {
        val owner = owner()
        val good = completeResolverMedia(owner)
        owner.coordinator.drainForBackground()
        val pending = admitResolverMedia(owner, executionTestItem(SourceProvider.COMMONS,
            title = "A distinct complete title for rejected publication — عنوان مستقل", id = "authored-pending-resolver-media"))
        val seed = stageResolverPublication(owner, pending)
        val before = owner.store.acquisitionView()
        val checks = owner.materializationEvidence.checks.value
        val nativeBefore = owner.validations.size
        val gateway = ResolverNoRemoteGateway(owner)
        // Construction occurs OUTSIDE assertFails: each variant must reach the actual Room
        // publication guard with a live exact fence/intent, not fail DTO construction or CAS.
        val malformed = listOf(
            "missing-representation" to seed.asset.copy(representationId = null),
            "empty-representation" to seed.asset.copy(representationId = ""),
            "blank-representation" to seed.asset.copy(representationId = " \t\n"),
            "different-representation" to seed.asset.copy(representationId = "authored-other-key"),
            "different-producer" to seed.asset.copy(producer = good.asset.producer),
            "missing-materializer-job" to seed.asset.copy(materializerDownloadId = null),
            "different-materializer-job" to seed.asset.copy(materializerDownloadId = good.downloadId),
            "missing-materializer-attempt" to seed.asset.copy(materializerAttemptId = null),
            "different-materializer-attempt" to seed.asset.copy(materializerAttemptId = good.asset.materializerAttemptId),
            "different-ownership-generation" to seed.asset.copy(ownershipGeneration = seed.asset.ownershipGeneration + 1),
        )
        malformed.forEach { (label, asset) ->
            val join = assertNotNull(owner.repository.physicalDownload(seed.fence.downloadId))
            assertEquals(seed.fence.producer, join.job.producer, label)
            assertEquals(seed.fence.ownershipGeneration, join.job.ownershipGeneration, label)
            assertEquals(seed.fence.leaseOwner, join.job.leaseOwner, label)
            assertTrue(assertNotNull(join.job.leaseExpiresEpochMs) > clock.epochMs(), label)
            assertEquals(seed.fence.attemptId, join.activeAttempt?.id, label)
            assertEquals(seed.intent, join.finalization, label)
            assertFalse(join.job.cleanupPending, label)
            assertTrue(join.claims.any { it.intent == DownloadBatchIntent.ACTIVE }, label)
            assertFailsWith<IllegalArgumentException>(label) {
                owner.repository.publishPhysicalDownloadFinalization(PublishPhysicalDownloadFinalization(
                    seed.fence, seed.intent, asset, clock.epochMs()))
            }
            assertEquals(before, owner.store.acquisitionView(), label)
            assertSame(checks, owner.materializationEvidence.checks.value, label)
            assertEquals(good.asset, checks.currentValidAssets[good.downloadId], label)
            assertNull(checks.successfulChecks[seed.fence.downloadId], label)
            assertEquals(nativeBefore, owner.validations.size, label)
            assertContentEquals(PHYSICAL_BYTES, Files.readAllBytes(Path.of(seed.intent.stagingPath)))
            gateway.assertNoNewWork()
        }
    }

    @Test fun anActuallyCapturedButUnacceptedRequestCannotBorrowAnotherRequestsAcceptedBinding() = acquisitionDownloadRoomCase {
        val owner = owner()
        val good = completeResolverMedia(owner)
        owner.coordinator.drainForBackground()
        val request = AcquisitionCaptureFactory(clock).media(good.member.item, owner.store.acquisitionView().snapshot)
        owner.repository.capture(CaptureAcquisition(request))
        val before = owner.store.acquisitionView()
        val record = before.acquisition.requests.getValue(request.id)
        assertEquals(AcquisitionJobState.READY, record.state)
        assertNull(record.outcome)
        assertNotEquals(good.context.request.id, request.id)
        val checks = owner.materializationEvidence.checks.value
        val nativeBefore = owner.validations.size
        val gateway = ResolverNoRemoteGateway(owner)
        val command = AdmitStandalonePhysicalDownload(request.id, good.context.binding.id,
            "authored-not-admitted-job", DownloadClaimId("authored-not-admitted-claim"), clock.epochMs())
        assertFailsWith<IllegalArgumentException> { owner.repository.admitStandalonePhysicalDownload(command) }
        assertEquals(before, owner.store.acquisitionView())
        assertSame(checks, owner.materializationEvidence.checks.value)
        assertEquals(nativeBefore, owner.validations.size)
        gateway.assertNoNewWork()
    }

    @Test fun exactCurrentMemberAndClaimStillCannotCommitAnAlteredMaterializationUse() = acquisitionDownloadRoomCase {
        val owner = owner()
        val first = admitResolverMedia(owner)
        val second = admitResolverMedia(owner, first.item)
        val good = publishResolverMedia(owner, first)
        owner.coordinator.drainForBackground()
        val before = owner.store.acquisitionView()
        val state = before.acquisition
        val key = second.currentKey(state)
        val claim = state.claims.getValue(assertNotNull(second.member.claimId))
        val job = state.physicalDownloads.getValue(claim.physicalJobId)
        val expectation = BatchDownloadMemberExpectation(key.batchId, key.memberPosition, key.intentRevision,
            key.occurrenceId, key.requestId, key.requestedBindingId, claim.id)
        assertFalse(state.assetUses.any { it.requestedBindingId == key.requestedBindingId })
        val checks = owner.materializationEvidence.checks.value
        val nativeBefore = owner.validations.size
        val gateway = ResolverNoRemoteGateway(owner)
        for (representation in listOf(null, "", " \t\n", "authored-other-key")) {
            val altered = good.asset.copy(representationId = representation)
            val command = CommitMaterializationUse(expectation, claim.id, claim.generation,
                job.ownershipGeneration, altered, 1, clock.epochMs())
            // Current real member/claim/generation passes the stale guards; addAssetUse itself
            // must reject the altered expected asset. It cannot validate by same subject/C.
            assertFailsWith<IllegalArgumentException> { owner.repository.commitMaterializationUse(command) }
            assertEquals(before, owner.store.acquisitionView())
            assertSame(checks, owner.materializationEvidence.checks.value)
            assertEquals(nativeBefore, owner.validations.size)
            gateway.assertNoNewWork()
        }
    }

    @Test fun exactCoreValidatorRejectsDetachedMalformedTypedReceiptsAndProducerDisagreement() = acquisitionDownloadRoomCase {
        val owner = owner()
        val first = admitResolverMedia(owner)
        val second = admitResolverMedia(owner, first.item)
        val good = publishResolverMedia(owner, first)
        owner.coordinator.drainForBackground()
        val graph = owner.store.readAcquisition { it }
        val before = owner.store.acquisitionView()
        assertEquals(before.acquisition, graph.state())
        val checks = owner.materializationEvidence.checks.value
        val nativeBefore = owner.validations.size
        val gateway = ResolverNoRemoteGateway(owner)
        for (representation in listOf(null, "", " \t\n")) {
            // Detached copy ONLY. Both asset and receipt agree so the intended typed nonblank
            // guard, rather than simple receipt disagreement, remains necessary.
            val detached = graph.copy(
                library = graph.library.copy(assets = graph.library.assets.map {
                    if (it.id == good.asset.id.value) it.copy(representationId = representation) else it
                }),
                rows = graph.rows.copy(materializationReceiptRows = graph.rows.materializationReceiptRows.map {
                    if (it.id == good.asset.id.value) it.copy(representationId = representation) else it
                }),
            )
            assertFailsWith<IllegalArgumentException> { detached.state() }
        }
        val otherProducer = graph.copy(
            library = graph.library.copy(assets = graph.library.assets.map {
                if (it.id == good.asset.id.value) it.copy(executionBindingId = second.context.binding.id.value) else it
            }),
            rows = graph.rows.copy(materializationReceiptRows = graph.rows.materializationReceiptRows.map {
                if (it.id == good.asset.id.value) it.copy(executionBindingId = second.context.binding.id.value) else it
            }),
        )
        assertNotEquals(first.context.binding.id, second.context.binding.id)
        assertEquals(first.context.recording, second.context.recording)
        assertFailsWith<IllegalArgumentException> { otherProducer.state() }
        assertEquals(graph, owner.store.readAcquisition { it })
        assertEquals(before, owner.store.acquisitionView())
        assertSame(checks, owner.materializationEvidence.checks.value)
        assertEquals(nativeBefore, owner.validations.size)
        gateway.assertNoNewWork()
        // These invoke the EXACT validateAcquisitionState through graph.state, but are NOT
        // malformed-schema3 database-open tests. No invalid graph is ever written to Room.
    }

    @Test fun representationDisagreementNeedsThePhysicalAndHandoffGuardsNotAnInventedCoreOpenRule() = acquisitionDownloadRoomCase {
        val owner = owner()
        val good = completeResolverMedia(owner)
        val before = owner.store.acquisitionView()
        val graph = owner.store.readAcquisition { it }
        val checks = owner.materializationEvidence.checks.value
        val gateway = ResolverNoRemoteGateway(owner)
        val resolver = resolverFor(owner, gateway)
        try {
            val prepared = resolver.resolve(good.context, EngineCapabilities(), offlineOnly = true)
            resolver.requireAdmissible(prepared)
            val nativeBefore = owner.validations.size
            val pathAttempts = AtomicInteger()
            leases.refuse = { pathAttempts.incrementAndGet(); false }
            for (representation in listOf(null, "", " \t\n", "authored-other-key")) {
                val detached = graph.copy(library = graph.library.copy(downloads = graph.library.downloads.map {
                    if (it.id == good.downloadId) it.copy(representationId = representation) else it
                }))
                // Core graph validation intentionally does NOT universally enforce the old
                // job/asset representation join. Do not falsely claim an actual-open negative.
                val detachedState = detached.state()
                val detachedJob = detachedState.physicalDownloads.getValue(good.downloadId)
                assertEquals(representation, detachedJob.representationId)
                assertEquals(FailureCode.CORRUPT_MEDIA, assertNotNull(physicalMaterializationIdentityFailure(
                    detachedJob, null, good.asset, detachedState.physicalAttempts.values)).code)
                // Separately exercise the real resolver's read-only handoff guard using its
                // real current store, not by installing detached malformed rows or a facade.
                val alteredPreparation = prepared.copy(validatedMaterialization = good.asset.copy(representationId = representation))
                assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> {
                    resolver.requireAdmissible(alteredPreparation)
                }.failure.code)
                assertEquals(0, pathAttempts.get())
                assertEquals(nativeBefore, owner.validations.size)
                assertSame(checks, owner.materializationEvidence.checks.value)
                assertEquals(before, owner.store.acquisitionView())
                gateway.assertNoNewWork()
            }
        } finally { leases.refuse = { false }; resolver.close() }
    }

    @Test fun actualExecutorPathBusyRefusalCarriesNoPrimitiveRejectionAndDoesNotChangeC0() = acquisitionDownloadRoomCase {
        val owner = owner()
        val good = completeResolverMedia(owner)
        owner.coordinator.start(DownloadActivation.USER_SESSION)
        val before = owner.store.acquisitionView()
        val checks = owner.materializationEvidence.checks.value
        val nativeBefore = owner.validations.size
        val key = good.member.currentKey(before.acquisition)
        val claim = before.acquisition.claims.getValue(assertNotNull(good.member.member.claimId))
        val gateway = ResolverNoRemoteGateway(owner)
        val held = assertNotNull(files.tryLeasePaths(listOf(good.asset.uri)))
        try {
            val unavailable = assertIs<AcquisitionDownloadAssetCheck.Unavailable>(owner.coordinator.acquisitionExecutor.validateAssetUse(
                ValidateAcquisitionDownloadAsset(key, claim, good.asset.id)))
            assertEquals(FailureCode.BACKGROUND_RESTRICTED, unavailable.failure.code)
            assertNull(unavailable.validationRejection, "No negative was observed before acquiring the primitive's real path")
            assertEquals(nativeBefore, owner.validations.size)
            assertSame(checks, owner.materializationEvidence.checks.value)
            assertEquals(before, owner.store.acquisitionView())
            gateway.assertNoNewWork()
        } finally { held.close() }
        assertNotNull(files.tryLeasePaths(listOf(good.asset.uri))).close()
    }

    @Test fun validSchema2ImportReopensLocallyWithoutInventingTypedLineageOrPhysicalC1() = acquisitionDownloadRoomCase {
        val seed = seedResolverSchema2(ResolverSchema2Shape.VALID)
        val old = owner()
        val before = old.store.acquisitionView()
        seed.assertPreserved(before)
        val owner = reopenResolverOwner(old)
        assertEquals(before, owner.store.acquisitionView())
        assertResolverColdEvidence(owner)
        val gateway = resolverSchema2NoRemoteGateway()
        val native = AtomicInteger()
        val resolver = AppSourceResolver(owner.store, files, AssetValidator {
            assertEquals(seed.asset, it)
            native.incrementAndGet()
            AssetValidation.Valid(31_234)
        }, gateway, clock, owner.materializationEvidence)
        try {
            val current = before.snapshot.media.getValue(seed.mediaId)
            assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> {
                resolver.resolveWithBinding(current, EngineCapabilities(), offlineOnly = true, reuseBindingId = "another-session")
            }.failure.code)
            assertEquals(0, native.get(), "A different legacy session is rejected before native use")
            val result = resolver.resolveWithBinding(current, EngineCapabilities(), offlineOnly = true, reuseBindingId = seed.binding.id)
            assertTrue(result.isLocal)
            assertEquals(seed.asset.uri, result.uri)
            assertEquals(seed.asset.id, result.assetId)
            assertEquals(current, result.item)
            assertEquals(seed.binding, result.executionBinding)
            assertEquals(FIRST_VIDEO_ID, result.executionBinding?.selectedVideoId)
            assertEquals(seed.binding.queryTitle, result.executionBinding?.queryTitle)
            assertEquals(31_234L, result.durationMs)
            assertNotEquals(seed.asset.measuredDurationMs, result.durationMs)
            assertNotEquals(current.durationMs, result.durationMs)
            assertEquals(1, native.get())
            assertEquals(before, owner.store.acquisitionView())
            seed.assertPreserved(owner.store.acquisitionView())
            seed.assertOriginalScalars(databasePath)
            assertResolverColdEvidence(owner)
            assertResolverSchema2NoRemote(gateway, owner)
            assertSQLite()
        } finally { resolver.closeReaders() }
    }

    private fun retainedResolverReaderCase(mismatchedInput: Boolean, cancelInvocation: Boolean) = acquisitionDownloadRoomCase {
        val owner = owner()
        val good = completeResolverMedia(owner)
        val before = owner.store.acquisitionView()
        val checks = owner.materializationEvidence.checks.value
        val nativeBefore = owner.validations.size
        val originalInput = good.asset.validationInput()
        val released = AtomicBoolean()
        val throwObservation = AtomicBoolean()
        val observations = AtomicInteger()
        val cancellationSeen = AtomicBoolean()
        val originalReader = LocalMediaValidationReaderRelease {
            observations.incrementAndGet()
            if (throwObservation.get()) error("authored_unknown_original_release_observation")
            released.get()
        }
        val reportedInput = if (mismatchedInput) originalInput.copy(
            uri = directory.resolve("authored-wrong-native-reported-input").toString(), representationId = "authored-wrong-native-key") else originalInput
        val failure = LocalMediaValidationReaderUnacknowledged(reportedInput, originalReader)
        val native = barrier()
        owner.validation = { input ->
            assertEquals(originalInput, input)
            if (cancelInvocation) {
                try { native.stop() }
                catch (cancelled: CancellationException) { cancellationSeen.set(true); throw cancelled }
                finally { throw failure }
            }
            throw failure
        }
        val gateway = ResolverNoRemoteGateway(owner)
        val resolver = resolverFor(owner, gateway)
        try {
            if (cancelInvocation) supervisorScope {
                val pending = async { resolver.resolve(good.context, EngineCapabilities(), offlineOnly = true) }
                try {
                    native.entered.await()
                    pending.cancelAndJoin()
                    assertTrue(pending.isCancelled)
                    assertTrue(cancellationSeen.get())
                    // Cancellation plus a finally-thrown teardown failure need not have one
                    // precise Deferred exception subtype; the retained original owner below
                    // is the positive assertion, not inference from a cancellation exception.
                } finally { native.release(); pending.cancelAndJoin() }
            } else {
                assertSame(failure, assertFailsWith<LocalMediaValidationReaderUnacknowledged> {
                    resolver.resolve(good.context, EngineCapabilities(), offlineOnly = true)
                })
            }
            assertEquals(nativeBefore + 1, owner.validations.size)
            assertSame(originalReader, failure.reader)
            assertEquals(reportedInput, failure.input)
            assertSame(checks, owner.materializationEvidence.checks.value)
            assertEquals(before, owner.store.acquisitionView())
            assertNull(files.tryLeasePaths(listOf(good.asset.uri)), "The original path, not the reported replacement input, stays owned")
            assertEquals(FailureCode.STORAGE_IO, assertNotNull(resolver.retainedReaderFailure()).code)
            assertTrue(observations.get() > 0)
            assertEquals(FailureCode.STORAGE_IO, assertFailsWith<VeyraException> {
                resolver.resolve(good.context, EngineCapabilities(), offlineOnly = true)
            }.failure.code)
            repeat(2) {
                throwObservation.set(it == 1)
                assertEquals(FailureCode.STORAGE_IO, assertNotNull(resolver.retainedReaderFailure()).code)
                assertEquals(FailureCode.STORAGE_IO, assertFailsWith<VeyraException> { resolver.close() }.failure.code)
                assertNull(files.tryLeasePaths(listOf(good.asset.uri)))
                assertEquals(nativeBefore + 1, owner.validations.size, "Observation/close must never retry native validation")
                assertSame(checks, owner.materializationEvidence.checks.value)
                assertEquals(before, owner.store.acquisitionView())
                gateway.assertNoNewWork()
            }
            throwObservation.set(false)
            released.set(true) // The ONLY positive original-reader receipt; not a new owner.
            resolver.close()
            assertNull(resolver.retainedReaderFailure())
            assertNotNull(files.tryLeasePaths(listOf(good.asset.uri))).close()
            assertEquals(nativeBefore + 1, owner.validations.size)
            assertSame(checks, owner.materializationEvidence.checks.value)
            assertEquals(before, owner.store.acquisitionView())
            gateway.assertNoNewWork()
        } finally {
            native.release()
            throwObservation.set(false)
            released.set(true)
            resolver.close()
        }
    }
}
