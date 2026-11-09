package dev.veyra.data

import androidx.sqlite.SQLiteException
import dev.veyra.data.room.retryWindowsWalRecovery
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import kotlin.test.*

class WindowsWalRecoveryTest {
    private fun truncate() = SQLiteException("Error code: 1546, message: disk I/O error")

    @Test fun transientWindowsWalFailureReopensAndReturnsTheActualRead() {
        var calls = 0
        val waits = mutableListOf<Long>()
        val result = retryWindowsWalRecovery(windows = true, nanoTime = { 0L }, pause = { waits.add(it) }) {
            if (++calls == 1) throw truncate()
            "verified database"
        }
        assertEquals("verified database", result)
        assertEquals(2, calls)
        assertEquals(listOf(100L), waits)
    }

    @Test fun persistentFailureRemainsAFailureAfterThreeSeconds() {
        val failure = truncate()
        var now = 0L
        var calls = 0
        val error = assertFailsWith<SQLiteException> {
            retryWindowsWalRecovery(windows = true, nanoTime = { now }, pause = { now += it * 1_000_000 }) {
                calls++
                throw failure
            }
        }
        assertSame(failure, error)
        assertEquals(31, calls)
        assertEquals(3_000_000_000L, now)
    }

    @Test fun aSlowFailedReadConsumesTheSameDeadline() {
        val failure = truncate()
        var now = 0L
        var calls = 0
        assertSame(failure, assertFailsWith<SQLiteException> {
            retryWindowsWalRecovery(windows = true, nanoTime = { now }, pause = { fail("Deadline expired") }) {
                calls++
                now += 3_000_000_000L
                throw failure
            }
        })
        assertEquals(1, calls)
    }

    @Test fun nonWindowsAndOtherSqliteFailuresAreNeverRetried() {
        for ((windows, failure) in listOf(false to truncate(),
            true to SQLiteException("Error code: 10, message: disk I/O error"),
            true to SQLiteException("Error code: 15460, message: unrelated"))) {
            var calls = 0
            assertSame(failure, assertFailsWith<SQLiteException> {
                retryWindowsWalRecovery(windows = windows, pause = { fail("Unrelated error retried") }) {
                    calls++
                    throw failure
                }
            })
            assertEquals(1, calls)
        }
    }

    @Test fun futureSchemaAndCancellationStillPropagate() {
        for (failure in listOf(VeyraException(AppFailure(FailureCode.FUTURE_SCHEMA)),
            java.util.concurrent.CancellationException("cancelled"))) {
            assertSame(failure, assertFails {
                retryWindowsWalRecovery<Unit>(windows = true, pause = { fail("Unrelated failure retried") }) {
                    throw failure
                }
            })
        }
    }

    @Test fun interruptedWaitStopsRecoveryImmediately() {
        val interrupted = InterruptedException("interrupted")
        assertSame(interrupted, assertFailsWith<InterruptedException> {
            retryWindowsWalRecovery<Unit>(windows = true, pause = { throw interrupted }) { throw truncate() }
        })
    }
}
