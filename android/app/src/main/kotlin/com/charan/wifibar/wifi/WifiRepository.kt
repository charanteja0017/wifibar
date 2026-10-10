package com.charan.wifibar.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.charan.wifibar.core.AccessPoint
import com.charan.wifibar.core.LinkInfo
import com.charan.wifibar.core.NetworkGroup
import com.charan.wifibar.core.NetworkGrouping
import com.charan.wifibar.core.SecurityKind
import com.charan.wifibar.core.WifiBand
import com.charan.wifibar.core.WifiMapping
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

const val TAG = "WiFiBar"

data class WifiState(
    val wifiEnabled: Boolean = true,
    val link: LinkInfo? = null,
    val accessPoints: List<AccessPoint> = emptyList(),
    val groups: List<NetworkGroup> = emptyList(),
    val hiddenCount: Int = 0,
    /** Elapsed-realtime millis of the newest scan result, to show how old the list is. */
    val resultsAtElapsedMillis: Long? = null,
)

/**
 * All Wi-Fi reads. Android limits apps to 4 scans per 2 minutes in the foreground; results from scans
 * started by the system or other apps arrive through the same callback, so the list stays fresh-ish.
 */
class WifiRepository(private val context: Context) {
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val _state = MutableStateFlow(WifiState())
    val state: StateFlow<WifiState> = _state.asStateFlow()

    private val scanCallback = object : WifiManager.ScanResultsCallback() {
        override fun onScanResultsAvailable() = readResults()
    }
    private var registered = false

    /**
     * Debug builds, for README screenshots: made-up networks (the same names and BSSIDs as the Mac widget's
     * preview), so no real network names or addresses appear. Real scans and link reads are ignored after this.
     */
    var usesSampleData = false
        private set

    fun useSampleData() {
        usesSampleData = true
        fun ap(ssid: String, bssid: String, rssi: Int, channel: Int, band: WifiBand, width: Int) =
            AccessPoint(ssid, bssid, rssi, channel, band, width, SecurityKind.PERSONAL, "WPA2/WPA3 Personal", SystemClock.elapsedRealtimeNanos() / 1000)
        val aps = listOf(
            ap("Home", "a4:83:e7:10:20:01", -48, 36, WifiBand.GHZ_5, 80),
            ap("Home", "a4:83:e7:10:20:02", -61, 6, WifiBand.GHZ_2_4, 20),
            ap("Home", "a4:83:e7:10:20:03", -70, 149, WifiBand.GHZ_5, 80),
            ap("Office", "3c:22:fb:00:11:01", -66, 44, WifiBand.GHZ_5, 80),
            ap("Office", "3c:22:fb:00:11:02", -79, 1, WifiBand.GHZ_2_4, 20),
            ap("Library", "5a:10:44:2e:00:01", -71, 48, WifiBand.GHZ_5, 40),
            ap("Library", "5a:10:44:2e:00:02", -77, 157, WifiBand.GHZ_5, 40),
            ap("Café Guest", "f0:9f:c2:aa:bb:01", -74, 11, WifiBand.GHZ_2_4, 20),
        )
        _state.value = WifiState(
            wifiEnabled = true,
            accessPoints = aps,
            groups = NetworkGrouping.group(aps, "Home", "a4:83:e7:10:20:01"),
            resultsAtElapsedMillis = SystemClock.elapsedRealtime(),
        )
        readLink()
    }

    fun start() {
        if (usesSampleData) return
        if (!registered) {
            wifi.registerScanResultsCallback(context.mainExecutor, scanCallback)
            registered = true
        }
        readLink()
        readResults()
    }

    fun stop() {
        if (registered) {
            wifi.unregisterScanResultsCallback(scanCallback)
            registered = false
        }
    }

    /** False when Android throttled the request (or Wi-Fi is off). Deprecated but still the only way. */
    @Suppress("DEPRECATION")
    fun startScan(): Boolean {
        if (usesSampleData) return true
        val started = try {
            wifi.startScan()
        } catch (e: SecurityException) {
            Log.w(TAG, "startScan denied: ${e.message}")
            false
        }
        Log.i(TAG, "startScan -> $started")
        return started
    }

    @SuppressLint("MissingPermission")
    fun readResults() {
        if (usesSampleData) return
        val results = try {
            wifi.scanResults
        } catch (e: SecurityException) {
            Log.w(TAG, "scanResults denied: ${e.message}")
            emptyList()
        }
        val accessPoints = results.map(::toAccessPoint)
        val newest = results.maxOfOrNull { it.timestamp }?.let { it / 1000 }
        _state.update { current ->
            current.copy(
                wifiEnabled = wifi.isWifiEnabled,
                accessPoints = accessPoints,
                groups = NetworkGrouping.group(accessPoints, current.link?.ssid, current.link?.bssid),
                hiddenCount = NetworkGrouping.hiddenCount(accessPoints),
                resultsAtElapsedMillis = newest ?: current.resultsAtElapsedMillis,
            )
        }
        Log.i(TAG, "scan results: ${accessPoints.size} APs, ${_state.value.groups.size} networks, newest ${newest?.let { SystemClock.elapsedRealtime() - it }} ms old")
    }

    /** The connected network. Android refreshes its RSSI about every 3 s (measured on a Xiaomi Pad 8). */
    @Suppress("DEPRECATION")
    fun readLink(): LinkInfo? {
        if (usesSampleData) {
            // Wobbles a little, like a real reading, so Locate's chart has something to show.
            val link = LinkInfo("Home", "a4:83:e7:10:20:01", -48 + (SystemClock.elapsedRealtime() / 3_000 % 5).toInt() - 2, 1201, 36, WifiBand.GHZ_5, "802.11ax")
            _state.update { it.copy(link = link, groups = NetworkGrouping.group(it.accessPoints, link.ssid, link.bssid)) }
            return link
        }
        val info = wifi.connectionInfo
        val link = if (info == null || info.rssi <= -127 || info.frequency <= 0) {
            null
        } else {
            LinkInfo(
                ssid = WifiMapping.cleanSsid(info.ssid),
                bssid = WifiMapping.cleanBssid(info.bssid),
                rssi = info.rssi,
                linkSpeedMbps = info.linkSpeed,
                channel = WifiMapping.channel(info.frequency),
                band = WifiMapping.band(info.frequency),
                wifiStandard = WifiMapping.wifiStandard(info.wifiStandard),
            )
        }
        _state.update { current ->
            current.copy(
                wifiEnabled = wifi.isWifiEnabled,
                link = link,
                groups = NetworkGrouping.group(current.accessPoints, link?.ssid, link?.bssid),
            )
        }
        return link
    }

    @Suppress("DEPRECATION")
    private fun toAccessPoint(result: ScanResult): AccessPoint {
        val ssid = if (Build.VERSION.SDK_INT >= 33) {
            WifiMapping.cleanSsid(result.wifiSsid?.toString())
        } else {
            WifiMapping.cleanSsid(result.SSID)
        }
        val (kind, label) = WifiMapping.security(result.capabilities ?: "")
        return AccessPoint(
            ssid = ssid,
            bssid = WifiMapping.cleanBssid(result.BSSID),
            rssi = result.level,
            channel = WifiMapping.channel(result.frequency),
            band = WifiMapping.band(result.frequency),
            channelWidth = WifiMapping.channelWidthMhz(result.channelWidth),
            security = kind,
            securityLabel = label,
            seenAtMicros = result.timestamp,
        )
    }
}
