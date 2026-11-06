package dev.veyra.downloads

import dev.veyra.data.getOrThrow
import dev.veyra.model.*
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.AssetValidator
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** D01–D08/D12/D13: real SQLite arbitration and filesystem fencing; fake acquisition, NOT AUDIO. */
class DownloadOwnershipRoomTest {
    @Test fun foreignFutureLeaseSurvivesStartBackgroundRestrictionAndClose() = downloadCase {
        val first = store()
        val second = store()
        val seeded = seedPending(first, status = DownloadStatus.RUNNING, bound = true, bytes = authoredBytes,
            leaseOwner = "foreign-writer", leaseUntil = clock.epochMs() + 20_000)
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val coordinator = coordinator(second, gateway)
        coordinator.start()
        coordinator.pauseForBackground()
        coordinator.close()
        val durable = first.flush().getOrThrow()
        assertEquals(seeded.record, durable.downloads[seeded.record.id])
        assertEquals(seeded.attempt, durable.downloadAttempts[seeded.attempt!!.id])
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(seeded.record.stagingPath!!)))
        gateway.assertUnused()
    }

    @Test fun twoReplayersHaveOneDurableClaimAndOnePublisher() = downloadCase {
        val first = store()
        val second = store()
        val seeded = seedFinalization(first)
        val barrier = barrier()
        val validations = AtomicInteger()
        val validator = AssetValidator { validations.incrementAndGet(); barrier.stop(); AssetValidation.Valid(31_234) }
        val gatewayA = DownloadTestGateway().apply { denyAcquisition() }
        val gatewayB = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gatewayA, validator)
        val b = coordinator(second, gatewayB, validator)
        coroutineScope {
            val starting = async { a.start() }
            barrier.entered.await()
            val claim = first.flush().getOrThrow().downloads.getValue(seeded.record.id)
            assertNotNull(claim.leaseOwner)
            assertNotEquals("foreign-writer", claim.leaseOwner)
            b.start()
            b.close()
            assertEquals(claim.leaseOwner, first.flush().getOrThrow().downloads[claim.id]?.leaseOwner)
            assertEquals(1, validations.get())
            barrier.release()
            starting.await()
        }
        val durable = eventually(first) { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        assertEquals(DownloadAttemptStatus.SUCCEEDED, durable.downloadAttempts[seeded.attempt!!.id]?.status)
        assertFalse(seeded.record.id in durable.downloadFinalizations)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(seeded.intent!!.finalPath)))
        assertFalse(Files.exists(Path.of(seeded.intent.stagingPath)))
        assertEquals(1, validations.get())
        gatewayA.assertUnused(); gatewayB.assertUnused()
    }

    @Test fun removeDuringNoncancellableValidationCannotPublishOrResurrect() = downloadCase {
        val first = store()
        val second = store()
        val seeded = seedFinalization(first)
        val validation = barrier()
        val validator = AssetValidator {
            withContext(NonCancellable) { validation.stop() }
            AssetValidation.Valid(31_234)
        }
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gateway, validator)
        val b = coordinator(second, gateway)
        coroutineScope {
            val starting = async { a.start() }
            validation.entered.await()
            b.start()
            b.remove(seeded.record.id)
            b.resume(seeded.record.id)
            val cancelling = second.flush().getOrThrow().downloads.getValue(seeded.record.id)
            assertTrue(cancelling.cleanupPending && cancelling.removeWhenClean)
            assertTrue(Files.exists(Path.of(seeded.intent!!.stagingPath)), "A still owns validation's generation lock")
            assertFalse(Files.exists(Path.of(seeded.intent.finalPath)))
            validation.release()
            starting.await()
        }
        eventually(second) { seeded.record.id !in it.downloads }
        assertFalse(Files.exists(Path.of(seeded.intent!!.stagingPath)))
        assertFalse(Files.exists(Path.of(seeded.intent.finalPath)))
        gateway.assertUnused()
    }

    @Test fun changedOwnerAttemptAndIntentRejectStaleRecoveryAndFailureUpdates() = downloadCase {
        val first = store()
        val second = store()
        val old = seedFinalization(first)
        val validation = barrier()
        val a = coordinator(first, DownloadTestGateway().apply { denyAcquisition() }, AssetValidator {
            withContext(NonCancellable) { validation.stop() }
            AssetValidation.Invalid(AppFailure(FailureCode.CORRUPT_MEDIA, "old_validator_rejected"))
        })
        coroutineScope {
            val starting = async { a.start() }
            validation.entered.await()
            val path = files.prepareStaging(old.item.id, "new-intent")
            Files.write(Path.of(path.toString()), authoredBytes)
            val attempt = old.attempt!!.copy(id = "attempt-new", number = 2, stagingPath = path.toString(), validatorGeneration = 2)
            val intent = old.intent!!.copy(attemptId = attempt.id, stagingPath = path.toString(), validatorGeneration = 2,
                finalPath = files.finalPath(old.item.id, 2, "audio/mpeg", attempt.id).toString())
            val winner = old.record.copy(leaseOwner = "new-owner", leaseExpiresEpochMs = clock.epochMs() + 30_000,
                activeAttemptId = attempt.id, stagingPath = intent.stagingPath, attemptCount = 2, validatorGeneration = 2)
            second.update { it.copy(downloads = it.downloads + (winner.id to winner),
                downloadAttempts = it.downloadAttempts + (attempt.id to attempt),
                downloadFinalizations = it.downloadFinalizations + (winner.id to intent)) }.getOrThrow()
            validation.release()
            starting.await()
            a.close()
            val actual = second.flush().getOrThrow()
            assertEquals(winner, actual.downloads[winner.id])
            assertEquals(intent, actual.downloadFinalizations[winner.id])
            assertEquals(attempt, actual.downloadAttempts[attempt.id])
            assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(intent.stagingPath)))
            assertFalse(Files.exists(Path.of(intent.finalPath)))
            assertTrue(Files.exists(Path.of(old.intent.stagingPath)))
        }
    }

    @Test fun silentTransferHeartbeatRevokesParentAndCleanupWaitsForUnwind() = downloadCase {
        val first = store()
        val second = store()
        val entered = CompletableDeferred<Unit>()
        val unwinding = barrier()
        val gateway = DownloadTestGateway().apply {
            transfer = { call ->
                call.write()
                entered.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { unwinding.stop() } }
            }
        }
        val a = coordinator(first, gateway)
        val b = coordinator(second, DownloadTestGateway().apply { denyAcquisition() })
        val id = a.enqueue(item())
        a.start(); entered.await(); b.start()
        val stage = gateway.transfers.single().path
        b.cancel(id)
        b.resume(id)
        unwinding.entered.await()
        assertTrue(second.flush().getOrThrow().downloads.getValue(id).cleanupPending)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(stage)))
        assertEquals(1, gateway.transfers.size)
        unwinding.release()
        val durable = eventually(second) { it.downloads[id]?.let { d -> d.status == DownloadStatus.CANCELLED && !d.cleanupPending && d.leaseOwner == null } == true }
        assertNull(durable.downloads[id]?.activeAttemptId)
        assertFalse(Files.exists(Path.of(stage)))
        assertEquals(1, gateway.transfers.size)
        assertEquals(DownloadAttemptStatus.CANCELLED, durable.downloadAttempts.values.single().status)
    }

    @Test fun expiredZombieCannotWriteNewGenerationOrDemoteWinningCompletion() = downloadCase {
        val first = store()
        val second = store()
        first.update { it.copy(settings = it.settings.copy(downloadParallelism = 2)) }.getOrThrow()
        val zombie = barrier()
        val oldReturned = CompletableDeferred<Unit>()
        val aGateway = DownloadTestGateway().apply {
            transfer = { call ->
                call.write("old partial".encodeToByteArray())
                withContext(NonCancellable) {
                    zombie.stop(); call.write("late old writer".encodeToByteArray()); oldReturned.complete(Unit)
                }
                call.result()
            }
        }
        val bGateway = DownloadTestGateway()
        val a = coordinator(first, aGateway)
        val b = coordinator(second, bGateway)
        val id = a.enqueue(item())
        a.start(); zombie.entered.await(); b.start()
        val oldPath = aGateway.transfers.single().path
        clock.advance(1_100)
        val completed = eventually(second) { it.downloads[id]?.status == DownloadStatus.COMPLETE }
        val asset = completed.downloads.getValue(id).asset!!
        val newPath = bGateway.transfers.single().path
        assertNotEquals(oldPath, newPath)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(asset.uri)))
        assertEquals(aGateway.transfers.single().binding, bGateway.transfers.single().binding)
        assertTrue(bGateway.queries.isEmpty(), "Takeover retains the exact first-result receipt")
        zombie.release(); oldReturned.await()
        eventually(second) { it.downloadAttempts.values.any { attempt -> attempt.stagingPath == oldPath && attempt.finishedEpochMs != null } }
        assertEquals(asset, second.flush().getOrThrow().downloads[id]?.asset)
        assertEquals(DownloadStatus.COMPLETE, second.state.value.downloads[id]?.status)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(asset.uri)))
        clock.advance(5_100)
        eventually(second) { it.downloadAttempts.values.none { attempt -> attempt.stagingPath == oldPath } }
        assertFalse(Files.exists(Path.of(oldPath)))
        assertEquals(2, second.state.value.downloadAttempts.size)
        assertEquals(1, second.state.value.downloadAttempts.values.count { it.status == DownloadAttemptStatus.SUCCEEDED })
    }

    @Test fun expiryDoesNotExceedOnePhysicalTransferWhileZombieHoldsGlobalSlot() = downloadCase {
        val first = store(); val second = store()
        first.update { it.copy(settings = it.settings.copy(downloadParallelism = 1)) }.getOrThrow()
        val zombie = barrier()
        val gatewayA = DownloadTestGateway().apply { transfer = { call -> withContext(NonCancellable) { zombie.stop() }; call.complete() } }
        val gatewayB = DownloadTestGateway()
        val a = coordinator(first, gatewayA); val b = coordinator(second, gatewayB)
        val id = a.enqueue(item())
        a.start(); zombie.entered.await(); b.start()
        clock.advance(1_100)
        eventually(second) { it.downloads[id]?.leaseOwner == null }
        delay(160)
        assertTrue(gatewayB.transfers.isEmpty())
        zombie.release()
        eventually(second) { it.downloads[id]?.status == DownloadStatus.COMPLETE }
        assertEquals(1, first.flush().getOrThrow().downloads.size)
        assertEquals(2, gatewayA.transfers.size + gatewayB.transfers.size)
    }

    @Test fun staleEnqueueResumeAndPauseCannotDemoteOtherConnectionsCompletedAsset() = downloadCase {
        val first = store(); val second = store()
        second.initialize().getOrThrow() // deliberately initialized before the winning transaction
        val seeded = seedComplete(first)
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val b = coordinator(second, gateway)
        assertEquals(seeded.record.id, b.enqueue(seeded.item))
        b.resume(seeded.record.id)
        b.pause(seeded.record.id)
        val durable = first.flush().getOrThrow()
        assertEquals(seeded.record, durable.downloads[seeded.record.id])
        assertEquals(1, durable.downloads.size)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(seeded.record.asset!!.uri)))
        gateway.assertUnused()
    }

    @Test fun publishedFailedIntentIsRecoveredAfterForeignLeaseExpiresWithoutRestart() = downloadCase {
        val first = store(); val second = store()
        val seeded = seedFinalization(first, published = true, status = DownloadStatus.FAILED,
            leaseOwner = "foreign-finalizer", leaseUntil = clock.epochMs() + 1_000)
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val b = coordinator(second, gateway)
        b.start()
        assertEquals(seeded.record, second.flush().getOrThrow().downloads[seeded.record.id])
        clock.advance(1_100)
        val completed = eventually(second) { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        assertEquals(seeded.intent!!.finalPath, completed.downloads[seeded.record.id]?.asset?.uri)
        assertFalse(seeded.record.id in completed.downloadFinalizations)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(seeded.intent.finalPath)))
        gateway.assertUnused()
    }

    @Test fun unboundRetainedBytesNeverAcquireAFreshReceiptInTheirOldDirectory() = downloadCase {
        val first = store()
        val seeded = seedPending(first, bytes = "unbound bytes".encodeToByteArray())
        val gateway = DownloadTestGateway()
        val a = coordinator(first, gateway)
        a.start()
        val done = eventually(first) { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        assertNotEquals(seeded.record.stagingPath, gateway.transfers.single().path)
        assertEquals(listOf(seeded.item.title), gateway.queries.toList())
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(done.downloads.getValue(seeded.record.id).asset!!.uri)))
    }

    @Test fun explicitPlayReceiptPreventsASecondSearchAndDifferentRecordingReplacement() = downloadCase {
        val first = store()
        val item = item()
        val played = binding(item, "played-binding")
        val other = binding(item, "other-binding", "BBBBBBBBBBB")
        first.update { it.copy(media = mapOf(item.id to item), playbackBindings = mapOf(played.id to played, other.id to other)) }.getOrThrow()
        val gateway = DownloadTestGateway()
        val a = coordinator(first, gateway)
        val id = a.enqueue(item, played.id)
        a.start()
        val complete = eventually(first) { it.downloads[id]?.status == DownloadStatus.COMPLETE }
        assertEquals(played.id, complete.downloads[id]?.asset?.executionBindingId)
        assertTrue(gateway.queries.isEmpty())
        assertEquals(played, gateway.transfers.single().binding)
        assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> { a.enqueue(item, other.id) }.failure.code)
        assertEquals(complete.downloads[id], first.flush().getOrThrow().downloads[id])
    }
}
