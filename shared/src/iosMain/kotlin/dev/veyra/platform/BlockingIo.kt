package dev.veyra.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** On Darwin, `Dispatchers.Default` is the GCD global queue; the cap matches `Dispatchers.IO`'s 64. */
actual val BlockingIo: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(64)
