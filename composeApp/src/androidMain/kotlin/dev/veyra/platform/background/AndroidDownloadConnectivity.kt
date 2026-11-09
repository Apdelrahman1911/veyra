package dev.veyra.platform.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import dev.veyra.downloads.DownloadNetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One application-lifetime observation, never a connectivity probe or a source request. */
internal class AndroidDownloadConnectivity(context: Context) {
    private val application = context.applicationContext
    private val manager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mutableState = MutableStateFlow(DownloadNetworkState.UNKNOWN)
    val state: StateFlow<DownloadNetworkState> = mutableState.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // WorkManager 2.10.3 uses capabilities of the default-network callback on API28+,
            // and the active network on older releases (not every available alternate network).
            if (Build.VERSION.SDK_INT >= 28) mutableState.value = observedDownloadNetwork(
                Build.VERSION.SDK_INT,
                connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            ) else refresh()
        }

        override fun onLost(network: Network) = refresh()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    init {
        try {
            if (Build.VERSION.SDK_INT >= 24) manager.registerDefaultNetworkCallback(callback)
            else {
                // The default-network callback was added in API24. Like pinned WorkManager,
                // API23 observes the protected connectivity broadcast and reads activeNetwork.
                @Suppress("DEPRECATION")
                application.registerReceiver(receiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
            }
            refresh()
        } catch (_: Exception) {
            // Permission/device-specific registration failure is not evidence of Internet access.
            // New network claims stay blocked; local repair/cleanup does not require connectivity.
            mutableState.value = DownloadNetworkState.UNKNOWN
        }
    }

    @Suppress("DEPRECATION")
    private fun refresh() {
        mutableState.value = try {
            val connected = manager.activeNetworkInfo?.isConnected == true
            val validated = manager.activeNetwork?.let(manager::getNetworkCapabilities)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            observedDownloadNetwork(Build.VERSION.SDK_INT, connected, validated)
        } catch (_: Exception) { DownloadNetworkState.UNKNOWN }
    }
}

/** Match CONNECTED constraints: API26+ requires validation; API23–25 requires connected only. */
internal fun observedDownloadNetwork(sdk: Int, connected: Boolean, validated: Boolean): DownloadNetworkState =
    if (connected && (sdk < 26 || validated)) DownloadNetworkState.CONNECTED else DownloadNetworkState.DISCONNECTED
