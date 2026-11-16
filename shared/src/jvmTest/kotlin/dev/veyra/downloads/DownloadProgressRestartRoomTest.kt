package dev.veyra.downloads

import dev.veyra.model.DownloadStatus
import dev.veyra.source.execution.AcquisitionTransferProgress
import dev.veyra.source.execution.AcquisitionTransferResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Real Room, physical ownership and file checks; authored non-audio source/native seams. */
class DownloadProgressRestartRoomTest {
    @Test fun restartedTransportCanReportItsActualPrefixBeforeCompleting() = acquisitionDownloadRoomCase {
        val owner = owner()
        val id = enqueue(owner, acceptNeutral(owner))
        val transfer = owner.gateway.transfer
        owner.gateway.transfer = { prepared, path, progress ->
            val total = PHYSICAL_BYTES.size.toLong()
            Files.write(Path.of(path), PHYSICAL_BYTES.copyOf(PHYSICAL_BYTES.size / 2))
            progress(AcquisitionTransferProgress(total / 2, total))
            assertEquals(total / 2, join(owner, id).job.bytesDownloaded)
            // The central transport discarded an unverifiable prefix and restarted.
            Files.write(Path.of(path), byteArrayOf())
            advance(501)
            progress(AcquisitionTransferProgress(0, total))
            assertEquals(0L, join(owner, id).job.bytesDownloaded)
            transfer(prepared, path, progress)
        }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val complete = awaitJob(owner, id) { it.job.status == DownloadStatus.COMPLETE && it.job.leaseOwner == null }
        assertEquals(PHYSICAL_BYTES.size.toLong(), complete.job.bytesDownloaded)
        assertEquals(complete.job.bytesDownloaded, complete.asset!!.sizeBytes)
        assertNull(complete.job.failure)
        assertEquals(1, owner.gateway.transfers.size)
        assertEquals(1, owner.validations.size)
        assertEquals(complete.job.bytesDownloaded, complete.attempts.single().bytesDownloaded)
        assertSQLite()
    }

    @Test fun materializedFileSizeCanBeSmallerThanTransportBytesWithoutFailingFinalValidation() = acquisitionDownloadRoomCase {
        val owner = owner()
        val id = enqueue(owner, acceptNeutral(owner))
        owner.gateway.transfer = { prepared, path, progress ->
            Files.write(Path.of(path), PHYSICAL_BYTES)
            val wireBytes = PHYSICAL_BYTES.size.toLong() + 42
            progress(AcquisitionTransferProgress(wireBytes, wireBytes))
            AcquisitionTransferResult(prepared.context, PHYSICAL_BYTES.size.toLong(), prepared.representation)
        }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val complete = awaitJob(owner, id) { it.job.status == DownloadStatus.COMPLETE && it.job.leaseOwner == null }
        assertEquals(PHYSICAL_BYTES.size.toLong(), complete.job.bytesDownloaded)
        assertEquals(complete.job.bytesDownloaded, complete.job.totalBytes)
        assertEquals(complete.job.bytesDownloaded, complete.asset!!.sizeBytes)
        assertEquals(complete.job.bytesDownloaded, complete.attempts.single().bytesDownloaded)
        assertNull(complete.job.failure)
        assertEquals(1, owner.validations.size)
        assertNotNull(complete.asset!!.sha256)
        assertSQLite()
    }
}
