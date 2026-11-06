@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.platform

import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.QuartzCore.CACurrentMediaTime

/** Pump Darwin callbacks; runBlocking(Main) would deadlock native main-thread lifecycle tests. */
internal fun runIosMainContract(block: suspend CoroutineScope.() -> Unit) {
    check(NSThread.isMainThread)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var finished = false
    var failure: Throwable? = null
    scope.launch {
        try { block() }
        catch (error: Throwable) { failure = error }
        finally { finished = true }
    }
    val deadline = CACurrentMediaTime() + 20.0
    while (!finished && CACurrentMediaTime() < deadline) {
        NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
    }
    scope.cancel()
    val cleanupDeadline = CACurrentMediaTime() + 5.0
    while (!finished && CACurrentMediaTime() < cleanupDeadline) {
        NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
    }
    if (!finished) fail("Native lifecycle test timed out")
    failure?.let { throw it }
}
