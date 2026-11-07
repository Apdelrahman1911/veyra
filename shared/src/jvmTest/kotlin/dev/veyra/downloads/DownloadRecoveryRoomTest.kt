package dev.veyra.downloads

import dev.veyra.data.getOrThrow
import dev.veyra.model.*
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.AssetValidator
import dev.veyra.playback.EngineCapabilities
import dev.veyra.source.execution.AppSourceResolver
import kotlinx.coroutines.*
import okio.FileSystem
import okio.ForwardingFileSystem
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** D03/D06/D09/D13/D15, using actual SQLite rollback and real files; NOT AUDIO or power-loss proof. */
class DownloadRecoveryRoomTest {
    @Test fun sqliteRollbackAfterAtomicRenameIsRecoveredFromDurableIntentOnReopenOffline() = downloadCase {
        val fault = persistence()
        val first = store(fault)
        val attemptedCommits = AtomicInteger()
        fault.afterTransform = { before, next ->
            if (next.downloads.values.any { it.status == DownloadStatus.COMPLETE && before.downloadFinalizations[it.id] != null }) {
                attemptedCommits.incrementAndGet()
                throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_rollback_after_rename"))
            }
        }
        val gatewayA = DownloadTestGateway()
        val a = coordinator(first, gatewayA)
        val id = a.enqueue(item()); a.start()
        val interrupted = eventually(first) { it.downloads[id]?.let { d -> d.status == DownloadStatus.FAILED && d.leaseOwner == null } == true && id in it.downloadFinalizations }
        assertTrue(attemptedCommits.get() > 0, "Failure must occur inside the actual SQLite transaction after rename")
        val intent = interrupted.downloadFinalizations.getValue(id)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(intent.finalPath)))
        assertFalse(Files.exists(Path.of(intent.stagingPath)))
        assertNull(interrupted.downloads[id]?.asset)
        a.close()
        first.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow()
        first.close()
        clock.advance(5_100)
        val reopened = store()
        val gatewayB = DownloadTestGateway().apply { denyAcquisition() }
        val b = coordinator(reopened, gatewayB)
        b.start()
        val complete = eventually(reopened) { it.downloads[id]?.status == DownloadStatus.COMPLETE }
        val asset = complete.downloads.getValue(id).asset!!
        assertEquals(intent.finalPath, asset.uri)
        assertFalse(id in complete.downloadFinalizations)
        assertEquals(DownloadAttemptStatus.SUCCEEDED, complete.downloadAttempts.getValue(intent.attemptId!!).status)
        val local = AppSourceResolver(reopened, files, validator, gatewayB, clock, materializationEvidence(reopened))
            .resolve(complete.media.getValue(asset.mediaId), EngineCapabilities(), offlineOnly = true)
        assertTrue(local.isLocal)
        assertEquals(asset.uri, local.uri)
        assertEquals(asset.executionBindingId, local.executionBinding?.id)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(local.uri)))
        assertEquals(1, gatewayA.transfers.size)
        gatewayB.assertUnused()
    }

    @Test fun failedReturnAfterDurableIntentInsertionCanBeCancelledWithoutLostFileReferences() = downloadCase {
        val fault = persistence()
        val first = store(fault); val second = store()
        val interruptedOnce = AtomicBoolean(true)
        fault.afterCommit = { committed ->
            if (committed.downloadFinalizations.isNotEmpty() && interruptedOnce.compareAndSet(true, false))
                throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_failure_after_intent_commit"))
        }
        val closing = barrier()
        val gate = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit) = DownloadExecutionLease {
                withContext(NonCancellable) { closing.stop() }
            }
        }
        val gateway = DownloadTestGateway()
        val a = coordinator(first, gateway, gate = gate)
        val b = coordinator(second, DownloadTestGateway().apply { denyAcquisition() })
        val id = a.enqueue(item()); a.start(); closing.entered.await(); b.start()
        val interrupted = second.flush().getOrThrow()
        val intent = interrupted.downloadFinalizations.getValue(id)
        assertFalse(interruptedOnce.get())
        assertTrue(Files.exists(Path.of(intent.stagingPath)))
        assertFalse(Files.exists(Path.of(intent.finalPath)))
        b.cancel(id)
        b.resume(id)
        assertTrue(second.flush().getOrThrow().downloads.getValue(id).cleanupPending)
        assertTrue(Files.exists(Path.of(intent.stagingPath)), "The old writer has not finished closing")
        closing.release()
        val cancelled = eventually(second) { it.downloads[id]?.let { d -> d.status == DownloadStatus.CANCELLED && !d.cleanupPending } == true }
        assertNull(cancelled.downloads[id]?.asset)
        assertFalse(id in cancelled.downloadFinalizations)
        assertFalse(Files.exists(Path.of(intent.stagingPath)))
        assertFalse(Files.exists(Path.of(intent.finalPath)))
        assertEquals(1, gateway.transfers.size)
    }

    @Test fun removeAfterReadyCommitWaitsForThePublishingWriterToReleaseItsGeneration() = downloadCase {
        val first = store(); val second = store()
        val closing = barrier()
        val gate = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit) = DownloadExecutionLease {
                withContext(NonCancellable) { closing.stop() }
            }
        }
        val gateway = DownloadTestGateway()
        val a = coordinator(first, gateway, gate = gate)
        val b = coordinator(second, DownloadTestGateway().apply { denyAcquisition() })
        val id = a.enqueue(item()); a.start(); closing.entered.await()
        val ready = first.flush().getOrThrow().downloads.getValue(id)
        assertEquals(DownloadStatus.COMPLETE, ready.status)
        val asset = ready.asset!!
        b.start(); b.remove(id)
        assertTrue(second.flush().getOrThrow().downloads.getValue(id).cleanupPending)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(asset.uri)))
        assertEquals(clock.epochMs() + 5_000, b.schedulingPlan().nextLocalEpochMs,
            "A still-live publication lock uses a bounded local recheck, not an empty foreground owner")
        closing.release()
        a.close() // Prove the old physical writer has drained before advancing the authored clock.
        clock.advance(5_000)
        eventually(second) { id !in it.downloads }
        assertFalse(Files.exists(Path.of(asset.uri)))
        assertEquals(1, gateway.transfers.size)
    }

    @Test fun partialDeletionKeepsJournalForPriorAssetAndIntentDestinationUntilReopenCompletesAll() {
        val blocked = AtomicBoolean(true)
        var target: String? = null
        val faultingFiles = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun onPathParameter(path: okio.Path, functionName: String, parameterName: String): okio.Path {
                if (functionName == "delete" && path.toString() == target && blocked.get()) throw IOException("authored sharing violation")
                return path
            }
        }
        downloadCase(faultingFiles) {
            val first = store()
            val seeded = seedFinalization(first, published = true)
            val oldPath = files.finalPath(seeded.item.id, 0, "audio/mpeg", "prior-failed-generation")
            Files.write(Path.of(oldPath.toString()), authoredBytes)
            val old = OfflineAsset("prior-failed-asset", seeded.item.id, oldPath.toString(), authoredBytes.size.toLong(),
                mimeType = "audio/mpeg", codecs = "mp3", sha256 = files.sha256(oldPath), executionBindingId = seeded.binding.id)
            first.update { snapshot -> snapshot.copy(downloads = snapshot.downloads + (seeded.record.id to seeded.record.copy(
                status = DownloadStatus.CANCELLED, userPaused = true, cleanupPending = true, removeWhenClean = true, asset = old))) }.getOrThrow()
            target = seeded.intent!!.finalPath
            val gateway = DownloadTestGateway().apply { denyAcquisition() }
            val a = coordinator(first, gateway)
            a.start()
            val failed = eventually(first) { it.downloads[seeded.record.id]?.failure?.code == FailureCode.STORAGE_IO }
            assertTrue(failed.downloads.getValue(seeded.record.id).cleanupPending)
            assertEquals(old, failed.downloads[seeded.record.id]?.asset, "Do not lose the prior failed generation reference")
            assertEquals(seeded.intent, failed.downloadFinalizations[seeded.record.id])
            assertTrue(Files.exists(Path.of(seeded.intent.finalPath)))
            val retryAt = clock.epochMs() + 5_000
            assertEquals(retryAt, failed.downloads[seeded.record.id]?.nextRetryEpochMs)
            a.resume(seeded.record.id)
            assertTrue(first.flush().getOrThrow().downloads.getValue(seeded.record.id).cleanupPending)
            a.close(); first.close()
            blocked.set(false)
            val reopened = store()
            val b = coordinator(reopened, gateway)
            b.initializeScheduling()
            assertEquals(retryAt, b.schedulingPlan().nextLocalEpochMs,
                "Reopening or a Resume rejected during cleanup cannot erase its durable failure backoff")
            clock.advance(5_000)
            b.start()
            val cleaned = eventually(reopened) { seeded.record.id !in it.downloads }
            assertTrue(cleaned.downloadAttempts.isEmpty())
            assertTrue(cleaned.downloadFinalizations.isEmpty())
            assertFalse(Files.exists(Path.of(seeded.intent.finalPath)))
            assertFalse(Files.exists(Path.of(old.uri)))
            assertFalse(Files.exists(Path.of(seeded.intent.stagingPath)))
            gateway.assertUnused()
        }
    }

    @Test fun removeWaitsForActualReleaseCallbackAndCanBeReenteredWithoutCommandLockInversion() = downloadCase {
        val first = store(); val second = store()
        val seeded = seedComplete(first)
        val released = barrier()
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gateway, beforeDelete = { released.stop() })
        a.start()
        coroutineScope {
            val removing = async { a.remove(seeded.record.id) }
            released.entered.await()
            assertTrue(second.flush().getOrThrow().downloads.getValue(seeded.record.id).cleanupPending)
            assertTrue(Files.exists(Path.of(seeded.record.asset!!.uri)))
            a.resume(seeded.record.id) // must return, not wait behind a native release callback
            a.pause(seeded.record.id) // removal intent is sticky and cannot be converted to resumable bytes
            assertFalse(removing.isCompleted)
            released.release()
            removing.await()
        }
        eventually(first) { seeded.record.id !in it.downloads }
        assertFalse(Files.exists(Path.of(seeded.record.asset!!.uri)))
        gateway.assertUnused()
    }

    @Test fun startupSameSizeCorruptionFailsDurablyAndRetryDeletesOldGenerationBeforeReacquiring() = downloadCase {
        val first = store()
        val seeded = seedComplete(first)
        val old = seeded.record.asset!!
        val damaged = authoredBytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        Files.write(Path.of(old.uri), damaged)
        val cleanup = barrier()
        val gateway = DownloadTestGateway()
        val a = coordinator(first, gateway, beforeDelete = { cleanup.stop() })
        a.start()
        eventually(first) { it.downloads[seeded.record.id]?.status == DownloadStatus.FAILED }
        assertEquals(0, validationCount.get(), "Hash corruption must be rejected before decoder validation")
        a.resume(seeded.record.id)
        cleanup.entered.await()
        assertTrue(first.flush().getOrThrow().downloads.getValue(seeded.record.id).cleanupPending)
        assertEquals(old, first.state.value.downloads[seeded.record.id]?.asset)
        assertTrue(Files.exists(Path.of(old.uri)))
        gateway.assertUnused()
        cleanup.release()
        val complete = eventually(first) { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        val repaired = complete.downloads.getValue(seeded.record.id).asset!!
        assertNotEquals(old.uri, repaired.uri)
        assertFalse(Files.exists(Path.of(old.uri)))
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(repaired.uri)))
        assertTrue(gateway.queries.isEmpty(), "Repair retains the bound recording instead of searching again")
        a.remove(seeded.record.id)
        eventually(first) { seeded.record.id !in it.downloads }
        assertFalse(Files.exists(Path.of(repaired.uri)))
    }

    @Test fun redownloadOfCorruptUnpublishedIntentCleansItInsteadOfReplayingForever() = downloadCase {
        val first = store()
        val seeded = seedFinalization(first, status = DownloadStatus.FAILED)
        Files.write(Path.of(seeded.intent!!.stagingPath), "corrupt".encodeToByteArray())
        first.update { it.copy(downloads = it.downloads + (seeded.record.id to seeded.record.copy(status = DownloadStatus.FAILED,
            failure = AppFailure(FailureCode.CORRUPT_MEDIA)))) }.getOrThrow()
        val gateway = DownloadTestGateway()
        val a = coordinator(first, gateway)
        a.enqueue(seeded.item)
        assertTrue(first.flush().getOrThrow().downloads.getValue(seeded.record.id).cleanupPending)
        a.start()
        val repaired = eventually(first) { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        assertFalse(Files.exists(Path.of(seeded.intent.stagingPath)))
        assertFalse(Files.exists(Path.of(seeded.intent.finalPath)))
        assertEquals(1, gateway.transfers.size)
        assertTrue(gateway.queries.isEmpty())
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(repaired.downloads.getValue(seeded.record.id).asset!!.uri)))
    }

    @Test fun pauseDuringNativeValidationRetainsBoundBytesAndResumeDoesNotSearchAgain() = downloadCase {
        val first = store(); val observer = store()
        val verifying = barrier()
        val validations = AtomicInteger()
        val controlledValidator = AssetValidator {
            if (validations.incrementAndGet() == 1) withContext(NonCancellable) { verifying.stop() }
            AssetValidation.Valid(31_234)
        }
        val gateway = DownloadTestGateway()
        val a = coordinator(first, gateway, validator = controlledValidator)
        val id = a.enqueue(item()); a.start(); verifying.entered.await()
        coroutineScope {
            val paused = async { a.pause(id) }
            val saved = eventually(observer) { it.downloads[id]?.status == DownloadStatus.PAUSED }
            assertNotNull(saved.downloads[id]?.executionBindingId)
            assertEquals(authoredRepresentation, saved.downloads[id]?.representationId)
            assertFalse(paused.isCompleted)
            verifying.release(); paused.await()
        }
        val part = gateway.transfers.single().path
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(part)))
        a.resume(id)
        eventually(first) { it.downloads[id]?.status == DownloadStatus.COMPLETE }
        assertEquals(1, gateway.queries.size)
        assertEquals(listOf(part, part), gateway.transfers.map { it.path })
        assertEquals(1, gateway.transfers.map { it.binding.id }.distinct().size)
    }
}
