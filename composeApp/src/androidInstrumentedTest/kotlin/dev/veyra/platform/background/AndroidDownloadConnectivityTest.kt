package dev.veyra.platform.background

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veyra.downloads.DownloadNetworkState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Pure capability-policy cases, not network callback delivery or physical Internet evidence. */
@RunWith(AndroidJUnit4::class)
class AndroidDownloadConnectivityTest {
    @Test fun connectedPolicyMatchesPinnedWorkManagerApiBoundary() {
        assertEquals(DownloadNetworkState.CONNECTED, observedDownloadNetwork(23, connected = true, validated = false))
        assertEquals(DownloadNetworkState.CONNECTED, observedDownloadNetwork(25, connected = true, validated = false))
        assertEquals(DownloadNetworkState.DISCONNECTED, observedDownloadNetwork(26, connected = true, validated = false))
        assertEquals(DownloadNetworkState.CONNECTED, observedDownloadNetwork(26, connected = true, validated = true))
        assertEquals(DownloadNetworkState.CONNECTED, observedDownloadNetwork(35, connected = true, validated = true))
    }

    @Test fun validationNeverInventsAnAvailableConnection() {
        for (sdk in listOf(23, 25, 26, 28, 35)) {
            assertEquals(DownloadNetworkState.DISCONNECTED, observedDownloadNetwork(sdk, connected = false, validated = true))
            assertEquals(DownloadNetworkState.DISCONNECTED, observedDownloadNetwork(sdk, connected = false, validated = false))
        }
    }
}
