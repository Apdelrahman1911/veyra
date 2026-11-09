package dev.veyra.data

import dev.veyra.data.room.retryWindowsDatabaseLease
import java.io.IOException
import java.nio.file.AccessDeniedException
import kotlin.test.*

class WindowsDatabaseLeaseRetryTest {
    @Test fun onlyWindowsWaitsForAnUnavailableKernelLease() {
        var attempts = 0
        assertNull(retryWindowsDatabaseLease(windows = false, pause = { error("Unexpected retry") }) {
            attempts++; null
        })
        assertEquals(1, attempts)
        val token = Any()
        var now = 0L
        attempts = 0
        assertSame(token, retryWindowsDatabaseLease(windows = true, nanoTime = { now },
            pause = { now += it * 1_000_000 }) { if (++attempts < 3) null else token })
        assertEquals(3, attempts)
    }

    @Test fun permanentlyHeldLeaseIsNeverStolen() {
        var now = 0L
        var attempts = 0
        assertNull(retryWindowsDatabaseLease<Any>(windows = true, nanoTime = { now },
            pause = { now += it * 1_000_000 }) { attempts++; null })
        assertEquals(31, attempts)
        assertEquals(3_000_000_000L, now)
    }

    @Test fun transientAccessDeniedCanRecoverButOtherIoIsNotRetried() {
        var now = 0L
        var attempts = 0
        val token = Any()
        assertSame(token, retryWindowsDatabaseLease(windows = true, nanoTime = { now },
            pause = { now += it * 1_000_000 }) {
            if (++attempts == 1) throw AccessDeniedException("authored-lock") else token
        })
        val failure = IOException("authored-storage-error")
        assertSame(failure, assertFailsWith<IOException> {
            retryWindowsDatabaseLease<Any>(windows = true, pause = { error("Unexpected retry") }) { throw failure }
        })
    }

    @Test fun persistentAccessDeniedAndInterruptionRemainFailures() {
        var now = 0L
        val denied = AccessDeniedException("authored-lock")
        assertSame(denied, assertFailsWith<AccessDeniedException> {
            retryWindowsDatabaseLease<Any>(windows = true, nanoTime = { now },
                pause = { now += it * 1_000_000 }) { throw denied }
        })
        val interrupted = InterruptedException("authored-interruption")
        assertSame(interrupted, assertFailsWith<InterruptedException> {
            retryWindowsDatabaseLease<Any>(windows = true, pause = { throw interrupted }) { null }
        })
    }
}
