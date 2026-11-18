package dev.veyra.platform

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The dispatcher for blocking file, hash and database work: Veyra's stand-in for `Dispatchers.IO`.
 *
 * On iOS, kotlinx.coroutines' `Dispatchers.IO` is a pool of Kotlin/Native workers that are created
 * on demand and never released. While parked, each one is woken every 100 ms by `runBlocking`'s
 * keep-alive ping. After a busy moment, an idle app kept ~26 such workers and ~260 wakeups per
 * second. Apple targets therefore run blocking work on GCD, whose threads are reclaimed once idle.
 */
expect val BlockingIo: CoroutineDispatcher
