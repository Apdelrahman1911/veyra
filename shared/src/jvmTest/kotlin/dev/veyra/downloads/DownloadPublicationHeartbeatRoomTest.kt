package dev.veyra.downloads

import dev.veyra.data.HeartbeatPhysicalDownload
import dev.veyra.data.PhysicalDownloadCommit
import dev.veyra.model.DownloadStatus
import dev.veyra.model.PhysicalDownloadFence
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Source
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class DownloadPublicationHeartbeatRoomTest {
    @Test fun aHeartbeatDuringFinalHashDoesNotInvalidateTheCompletedAssetTimestamp() {
        var onFinalRead: () -> Unit = {}
        val intercepted = AtomicBoolean()
        val observedFiles = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun source(file: okio.Path): Source {
                if (file.parent?.segments?.takeLast(2) == listOf("media", "files") &&
                    intercepted.compareAndSet(false, true)) onFinalRead()
                return super.source(file)
            }
        }
        acquisitionDownloadRoomCase(observedFiles) {
            val owner = owner()
            val id = enqueue(owner, acceptNeutral(owner))
            onFinalRead = {
                runBlocking {
                    advance(20)
                    val current = join(owner, id)
                    val attempt = assertNotNull(current.activeAttempt)
                    val fence = PhysicalDownloadFence(id, current.job.producer, current.job.ownershipGeneration,
                        assertNotNull(current.job.leaseOwner), attempt.id, attempt.number)
                    assertIs<PhysicalDownloadCommit.Committed<*>>(owner.repository.heartbeatPhysicalDownload(
                        HeartbeatPhysicalDownload(fence, clock.epochMs(), 5_000,
                            current.job.bytesDownloaded, current.job.totalBytes)))
                }
            }
            owner.coordinator.start(DownloadActivation.DURABLE_WORK)
            val settled = awaitJob(owner, id) {
                it.job.status == DownloadStatus.COMPLETE ||
                    it.job.leaseOwner == null && it.attempts.any { attempt -> attempt.failure != null }
            }
            assertTrue(intercepted.get())
            assertEquals(DownloadStatus.COMPLETE, settled.job.status)
            assertNull(settled.job.failure)
            assertNotNull(settled.asset)
            assertNull(settled.finalization)
            assertEquals(1, owner.gateway.transfers.size)
        }
    }
}
