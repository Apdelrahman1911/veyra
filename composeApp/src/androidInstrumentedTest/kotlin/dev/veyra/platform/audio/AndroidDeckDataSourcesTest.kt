@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.veyra.platform.audio

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Loader-ownership contracts, not a claim of real decoding or physical audio. */
@RunWith(AndroidJUnit4::class)
class AndroidDeckDataSourcesTest {
    @Test fun retirementWaitsForActualDelegateCloseAndRejectsLateOpen() = runBlocking {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val native = FakeDataSource(onClose = {
            entered.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
        })
        val owner = AndroidDeckDataSources(DataSource.Factory { native })
        val opened = owner.createDataSource()
        val late = owner.createDataSource()
        opened.open(spec())
        owner.retire()
        val drained = async { owner.awaitClosed() }
        val closing = async(Dispatchers.IO) { opened.close() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            delay(50)
            assertFalse(drained.isCompleted)
            try { late.open(spec()); fail("Retired source reopened a native handle") }
            catch (_: IOException) { }
        } finally { finish.countDown() }
        withTimeout(2_000) { closing.await(); drained.await() }
        opened.close()
        assertEquals(1, native.closes)
    }

    @Test fun failedOpenStillClosesPartialDelegateExactlyOnce() = runBlocking {
        val native = FakeDataSource(onOpen = { throw IOException("Authored open fault") })
        val owner = AndroidDeckDataSources(DataSource.Factory { native })
        val source = owner.createDataSource()
        try { source.open(spec()); fail("Fault was ignored") } catch (_: IOException) { }
        source.close() // Media3's required close after failed open is safe and idempotent.
        owner.retire()
        withTimeout(2_000) { owner.awaitClosed() }
        assertEquals(1, native.closes)
    }

    @Test fun closeFailureNeverTurnsIntoDeletionAcknowledgement() = runBlocking {
        val native = FakeDataSource(onClose = { throw IOException("Authored close fault") })
        val owner = AndroidDeckDataSources(DataSource.Factory { native })
        val source = owner.createDataSource()
        source.open(spec())
        owner.retire()
        try { source.close(); fail("Close fault was ignored") } catch (_: IOException) { }
        repeat(2) {
            try { owner.awaitClosed(); fail("Failed close was acknowledged as safe") }
            catch (_: IOException) { }
        }
    }

    private fun spec() = DataSpec(Uri.parse("file:///authored-contract.wav"))
    private class FakeDataSource(
        private val onOpen: () -> Unit = {},
        private val onClose: () -> Unit = {},
    ) : DataSource {
        var closes = 0
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long { onOpen(); return 1 }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
        override fun getUri(): Uri? = null
        override fun close() { closes++; onClose() }
    }
}
