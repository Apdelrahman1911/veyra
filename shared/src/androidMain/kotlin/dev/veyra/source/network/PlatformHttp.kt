package dev.veyra.source.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

internal actual fun platformHttpClient(): HttpClient = HttpClient(Android) {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout)
    engine { connectTimeout = 10_000; socketTimeout = 20_000 }
}

internal actual suspend fun platformResolveHost(host: String): List<String> = withContext(Dispatchers.IO) {
    InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
}
