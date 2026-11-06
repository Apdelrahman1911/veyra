package dev.veyra.app

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veyra.model.VeyraException
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDirectoriesTest {
    @Test fun trustedBaseAliasIsCanonicalizedButOwnedRootSymlinkIsRejected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = File(context.cacheDir, "directory-contract-${UUID.randomUUID()}").apply { mkdirs() }
        val base = File(fixture, "trusted").apply { mkdirs() }
        val alias = File(fixture, "os-alias")
        val foreign = File(fixture, "foreign").apply { mkdirs() }
        try {
            Os.symlink(base.absolutePath, alias.absolutePath)
            val owned = androidApplicationDirectory(alias)
            assertEquals(File(base.canonicalFile, "veyra"), owned)
            assertTrue(owned.delete())
            Os.symlink(foreign.absolutePath, owned.absolutePath)
            try { androidApplicationDirectory(alias); fail("App-owned symlink was followed") }
            catch (_: VeyraException) { }
            finally { owned.delete() }
        } finally {
            alias.delete() // Remove links before recursively cleaning this authored fixture.
            fixture.deleteRecursively()
        }
    }
}
