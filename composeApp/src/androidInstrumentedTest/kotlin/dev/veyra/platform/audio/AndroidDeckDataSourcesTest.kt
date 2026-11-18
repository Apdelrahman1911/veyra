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

    @Test fun knownRemoteLengthBoundsInitialAndSeekReadsWithoutRewritingTheirIdentity() {
        for (position in listOf(0L, 250L)) {
            val native = FakeDataSource()
            val owner = AndroidDeckDataSources(DataSource.Factory { native }, contentLength = 1_000)
            val source = owner.createDataSource()
            val request = DataSpec.Builder().setUri("https://r1---fixture.googlevideo.com/videoplayback?sig=authored")
                .setPosition(position).setKey("native-owner").setHttpRequestHeaders(mapOf("X-Authored" to "value")).build()
            source.open(request)
            val actual = checkNotNull(native.opened)
            assertEquals(1_000L - position, actual.length)
            assertEquals(request.uri, actual.uri)
            assertEquals(request.position, actual.position)
            assertEquals(request.key, actual.key)
            assertEquals(request.httpRequestHeaders, actual.httpRequestHeaders)
            source.close()
        }
    }

    @Test fun localUnknownExplicitlyBoundedAndOtherOriginReadsRetainTheirOriginalSpec() {
        val cdn = "https://r1---fixture.googlevideo.com/videoplayback"
        val cases = listOf(
            DataSpec.Builder().setUri(cdn).setLength(100).build() to 1_000L,
            DataSpec.Builder().setUri(cdn).build() to null,
            DataSpec.Builder().setUri(cdn).setPosition(1_000).build() to 1_000L,
            DataSpec.Builder().setUri("file:///authored.wav").build() to 1_000L,
            DataSpec.Builder().setUri("https://googlevideo.com.fixture.invalid/media").build() to 1_000L,
            DataSpec.Builder().setUri("https://fixture.invalid/media").build() to 1_000L,
        )
        for ((request, length) in cases) {
            val native = FakeDataSource()
            val source = AndroidDeckDataSources(DataSource.Factory { native }, length).createDataSource()
            source.open(request)
            assertSame(request, native.opened)
            source.close()
        }
    }

    @Test fun longRemoteObjectsAreReadAsConsecutiveExactSections() {
        val media = ByteArray(1_000) { (it % 251).toByte() }
        val opened = mutableListOf<Pair<Long, Long>>()
        val native = object : DataSource {
            var at = 0L; var end = 0L
            override fun addTransferListener(transferListener: TransferListener) = Unit
            override fun open(dataSpec: DataSpec): Long {
                opened += dataSpec.position to dataSpec.length
                at = dataSpec.position; end = dataSpec.position + dataSpec.length
                return dataSpec.length
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (at >= end) return androidx.media3.common.C.RESULT_END_OF_INPUT
                val count = minOf(length.toLong(), end - at, 97).toInt()
                media.copyInto(buffer, offset, at.toInt(), at.toInt() + count)
                at += count
                return count
            }
            override fun getUri(): Uri? = null
            override fun close() = Unit
        }
        val source = AndroidDeckDataSources(DataSource.Factory { native }, contentLength = 1_000, sectionBytes = 300)
            .createDataSource()
        val request = DataSpec.Builder().setUri("https://r1---fixture.googlevideo.com/videoplayback?sig=authored")
            .setPosition(100).build()
        assertEquals(900L, source.open(request))
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(128)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == androidx.media3.common.C.RESULT_END_OF_INPUT) break
            output.write(buffer, 0, count)
        }
        source.close()
        org.junit.Assert.assertArrayEquals(media.copyOfRange(100, 1_000), output.toByteArray())
        assertEquals(listOf(100L to 300L, 400L to 300L, 700L to 300L), opened)
    }

    private fun spec() = DataSpec(Uri.parse("file:///authored-contract.wav"))
    private class FakeDataSource(
        private val onOpen: () -> Unit = {},
        private val onClose: () -> Unit = {},
    ) : DataSource {
        var closes = 0
        var opened: DataSpec? = null
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            opened = dataSpec; onOpen()
            return if (dataSpec.length != androidx.media3.common.C.LENGTH_UNSET.toLong()) dataSpec.length else 1
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
        override fun getUri(): Uri? = null
        override fun close() { closes++; onClose() }
    }
}
