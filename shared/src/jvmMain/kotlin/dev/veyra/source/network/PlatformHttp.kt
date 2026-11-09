package dev.veyra.source.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

internal actual fun platformHttpClient(): HttpClient = HttpClient(CIO) {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout)
    engine {
        maxConnectionsCount = 16
        endpoint.connectAttempts = 1
        endpoint.maxConnectionsPerRoute = 4
    }
}

internal actual suspend fun platformResolveHost(host: String): List<String> = withContext(Dispatchers.IO) {
    InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
}
