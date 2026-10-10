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

    fun start() {
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
