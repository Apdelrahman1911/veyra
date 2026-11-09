@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.source.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.posix.*

internal actual fun platformHttpClient(): HttpClient = HttpClient(Darwin) {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout)
    engine { configureSession { URLCache = null; HTTPCookieStorage = null; HTTPShouldSetCookies = false } }
}

internal actual suspend fun platformResolveHost(host: String): List<String> = withContext(Dispatchers.Default) {
    memScoped {
        val hints = alloc<addrinfo>()
        memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM
        val output = alloc<CPointerVar<addrinfo>>()
        output.value = null
        if (getaddrinfo(host, null, hints.ptr, output.ptr) != 0) return@memScoped emptyList()
        val first = output.value
        try {
            val result = mutableListOf<String>()
            val buffer = allocArray<ByteVar>(1025)
            var cursor = first
            while (cursor != null) {
                val entry = cursor.pointed
                if (getnameinfo(entry.ai_addr, entry.ai_addrlen, buffer, 1025u, null, 0u, NI_NUMERICHOST) == 0) {
                    result += buffer.toKString()
                }
                cursor = entry.ai_next
            }
            result.distinct()
        } finally { if (first != null) freeaddrinfo(first) }
    }
}
