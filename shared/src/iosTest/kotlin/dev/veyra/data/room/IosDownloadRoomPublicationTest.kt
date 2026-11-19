@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.data.room

import dev.veyra.data.PhysicalDownloadCommit
import dev.veyra.data.createPlatformLibraryStore
import dev.veyra.model.DownloadStatus
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.*

class IosDownloadRoomPublicationTest {
    @Test fun finalizationJournalAndTerminalAssetSurviveSeparateNativeRoomOpens() = runTest {
        val directory = NSTemporaryDirectory().trimEnd('/') + "/veyra-download-room-" + NSUUID().UUIDString
        val manager = NSFileManager.defaultManager
        check(manager.createDirectoryAtPath(directory, true, null, null))
        val (prepared, publication) = PhysicalDownloadCommandTest().stagedPublication()
        val id = publication.expectedFinalization.downloadId
        try {
            val first = createPlatformLibraryStore(directory)
            try {
                first.transactAcquisition { database ->
                    AcquisitionMutation(prepared.withRevision(database.revision)) { Unit }
                }
            } finally { first.close() }

            val resumed = createPlatformLibraryStore(directory)
            try {
                val held = assertNotNull(resumed.acquisition.physicalDownload(id))
                assertEquals(DownloadStatus.VERIFYING, held.job.status)
                assertEquals(publication.expectedFinalization, held.finalization)
                assertNull(held.asset)
                assertIs<PhysicalDownloadCommit.Committed<*>>(resumed.acquisition.publishPhysicalDownloadFinalization(publication))
            } finally { resumed.close() }

            val reopened = createPlatformLibraryStore(directory)
            try {
                val terminal = assertNotNull(reopened.acquisition.physicalDownload(id))
                assertEquals(DownloadStatus.COMPLETE, terminal.job.status)
                assertEquals(publication.asset, terminal.asset)
                assertNull(terminal.finalization)
                assertNull(terminal.job.leaseOwner)
            } finally { reopened.close() }
        } finally { manager.removeItemAtPath(directory, null) }
    }
}
