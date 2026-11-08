package dev.veyra.downloads

import dev.veyra.model.*
import okio.FileSystem
import okio.ForwardingFileSystem
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** EX31/EX32 share only their actual file-read fault; no gateway/persistence error relabeling. */
internal fun executorSchema3StorageFailure(message: String, expected: FailureCode): Unit =
    executorSchema3Case(object : ForwardingFileSystem(FileSystem.SYSTEM) {
        override fun onPathParameter(path: okio.Path, functionName: String, parameterName: String): okio.Path {
            if (functionName == "source" && path.name == "transfer.part") throw IOException(message)
            return path
        }
    }) {
        val owner = owner()
        val (id, selected) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val failed = awaitJob(owner, id) { it.job.status == DownloadStatus.FAILED && it.job.leaseOwner == null }
        assertEquals(expected, failed.job.failure?.code)
        assertEquals(0, failed.job.automaticRetryCount)
        assertNull(failed.asset)
        assertNull(failed.finalization)
        assertTrue(owner.validations.isEmpty())
        assertEquals(selected.context, failed.producerContext)
        assertTrue(owner.store.acquisitionView().acquisition.assetUses.isEmpty())
        assertNull(owner.materializationEvidence.checks.value.successfulChecks[id])
        owner.coordinator.cancel(id)
        val cleaned = awaitJob(owner, id) { !it.job.cleanupPending && it.job.stagingPath == null }
        assertEquals(failed.attempts, cleaned.attempts)
        assertEquals(failed.job.producer, cleaned.job.producer)
        assertFalse(Files.exists(Path.of(owner.gateway.transfers.single().path)))
    }
