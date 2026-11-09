@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.app

import dev.veyra.model.VeyraException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID

class IosDirectoriesTest {
    @Test fun osBaseAliasIsResolvedWithoutFollowingOwnedRootSymlinks() {
        val files = NSFileManager.defaultManager
        val fixture = NSTemporaryDirectory().trimEnd('/') + "/veyra-directories-${NSUUID().UUIDString}"
        val base = "$fixture/trusted"
        val alias = "$fixture/os-alias"
        val foreign = "$fixture/foreign"
        assertTrue(files.createDirectoryAtPath(base, true, null, null))
        assertTrue(files.createDirectoryAtPath(foreign, true, null, null))
        assertTrue(files.createDirectoryAtPath("$foreign/untouched", true, null, null))
        fun foreignBackupPolicy() = NSURL.fileURLWithPath(foreign)
            .resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), error = null)
        val initialBackupPolicy = foreignBackupPolicy()
        fun assertForeignUnchanged() {
            assertEquals(listOf("untouched"), files.contentsOfDirectoryAtPath(foreign, error = null))
            assertEquals(initialBackupPolicy, foreignBackupPolicy())
        }
        try {
            assertTrue(files.createSymbolicLinkAtPath(alias, withDestinationPath = base, error = null))
            val owned = iosApplicationDirectory(alias)
            assertEquals(NSURL.fileURLWithPath(base).URLByResolvingSymlinksInPath?.path + "/Veyra", owned)
            assertTrue(files.removeItemAtPath(owned, error = null))
            assertTrue(files.createSymbolicLinkAtPath(owned, withDestinationPath = foreign, error = null))
            assertFailsWith<VeyraException> { iosApplicationDirectory(alias) }
            assertForeignUnchanged()
            assertTrue(files.removeItemAtPath(owned, error = null))
            iosApplicationDirectory(alias)
            for (child in listOf("media", "artwork")) {
                assertTrue(files.removeItemAtPath("$owned/$child", error = null))
                assertTrue(files.createSymbolicLinkAtPath("$owned/$child", withDestinationPath = foreign, error = null))
                assertFailsWith<VeyraException> { iosApplicationDirectory(alias) }
                assertForeignUnchanged()
                assertTrue(files.removeItemAtPath("$owned/$child", error = null))
                iosApplicationDirectory(alias)
            }
        } finally { files.removeItemAtPath(fixture, error = null) }
    }
}
