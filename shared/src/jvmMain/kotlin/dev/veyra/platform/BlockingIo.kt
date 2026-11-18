package dev.veyra.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

actual val BlockingIo: CoroutineDispatcher = Dispatchers.IO
