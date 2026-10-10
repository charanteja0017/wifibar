package com.charan.wifibar.core

/**
 * Turns Android's raw Wi-Fi values into the app's model. Pure functions, so they're unit-tested on the JVM.
 * Constants checked against API 37's android.jar (ScanResult.CHANNEL_WIDTH_*, WIFI_STANDARD_*, WifiManager.UNKNOWN_SSID).
 */
object WifiMapping {
    /** What Android returns instead of the SSID/BSSID when the app lacks location access. */
    const val UNKNOWN_SSID = "<unknown ssid>"
    const val REDACTED_BSSID = "02:00:00:00:00:00"

    fun band(frequencyMhz: Int): WifiBand = when (frequencyMhz) {
        in 2400..2500 -> WifiBand.GHZ_2_4
        in 4900..5899 -> WifiBand.GHZ_5
        in 5925..7125 -> WifiBand.GHZ_6
        else -> WifiBand.UNKNOWN
    }

    fun channel(frequencyMhz: Int): Int = when (band(frequencyMhz)) {
        WifiBand.GHZ_2_4 -> if (frequencyMhz == 2484) 14 else (frequencyMhz - 2407) / 5
        WifiBand.GHZ_5 -> (frequencyMhz - 5000) / 5
        WifiBand.GHZ_6 -> if (frequencyMhz == 5935) 2 else (frequencyMhz - 5950) / 5
        WifiBand.UNKNOWN -> 0
    }

    /** `ScanResult.channelWidth` code → MHz: 0=20, 1=40, 2=80, 3=160, 4=80+80, 5=320. */
    fun channelWidthMhz(code: Int): Int = when (code) {
        0 -> 20
        1 -> 40
        2 -> 80
        3, 4 -> 160
        5 -> 320
        else -> 0
    }

    /** `getWifiStandard()` code → label. */
    fun wifiStandard(code: Int): String = when (code) {
        1 -> "802.11a/b/g"
        4 -> "802.11n"
        5 -> "802.11ac"
        6 -> "802.11ax"
        7 -> "802.11ad"
        8 -> "802.11be"
        else -> "—"
    }

    /**
     * Security from `ScanResult.capabilities`, e.g. "[RSN-PSK+SAE-CCMP][ESS]".
     * Labels match the Mac app's. Most specific first: transition modes also list their parts.
     */
    fun security(capabilities: String): Pair<SecurityKind, String> {
        val caps = capabilities.uppercase()
        return when {
            "EAP_SUITE_B_192" in caps || "SUITE_B_192" in caps -> SecurityKind.ENTERPRISE to "WPA3 Enterprise"
            "EAP" in caps && ("RSN" in caps || "WPA2" in caps) -> SecurityKind.ENTERPRISE to "WPA2 Enterprise"
            "EAP" in caps -> SecurityKind.ENTERPRISE to "WPA Enterprise"
            "SAE" in caps && "PSK" in caps -> SecurityKind.PERSONAL to "WPA2/WPA3 Personal"
            "SAE" in caps -> SecurityKind.PERSONAL to "WPA3 Personal"
            "PSK" in caps && ("RSN" in caps || "WPA2" in caps) -> SecurityKind.PERSONAL to "WPA2 Personal"
            "PSK" in caps -> SecurityKind.PERSONAL to "WPA Personal"
            "OWE" in caps -> SecurityKind.ENHANCED_OPEN to "Enhanced Open"
            "WEP" in caps -> SecurityKind.WEP to "WEP"
            else -> SecurityKind.OPEN to "Open"
        }
    }

    /** `WifiInfo.getSSID()` wraps names in quotes and returns [UNKNOWN_SSID] when redacted. */
    fun cleanSsid(raw: String?): String? {
        val value = raw?.removeSurrounding("\"") ?: return null
        return value.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }
    }

    fun cleanBssid(raw: String?): String? {
        val value = raw?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = Bssid.normalize(value)
        return normalized.takeIf { it != REDACTED_BSSID }
    }
}
