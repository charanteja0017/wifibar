package com.charan.wifibar.core

/** Radio band an access point is broadcasting on. */
enum class WifiBand(val label: String) {
    GHZ_2_4("2.4 GHz"),
    GHZ_5("5 GHz"),
    GHZ_6("6 GHz"),
    UNKNOWN("Unknown band"),
}

/** Coarse security family. */
enum class SecurityKind {
    OPEN, ENHANCED_OPEN, WEP, PERSONAL, ENTERPRISE, UNKNOWN;

    val isSecured: Boolean get() = this != OPEN && this != ENHANCED_OPEN
}

/** One radio (BSSID) seen in a scan. Android reports no noise floor, so unlike the Mac there's no SNR. */
data class AccessPoint(
    val ssid: String?,
    val bssid: String?,
    val rssi: Int,
    val channel: Int,
    val band: WifiBand,
    /** MHz, 0 when unknown. */
    val channelWidth: Int,
    val security: SecurityKind,
    val securityLabel: String,
    /** When Android last heard this AP (`ScanResult.timestamp`, µs since boot); 0 if unknown. */
    val seenAtMicros: Long = 0,
) {
    /** The BSSID when available, otherwise a synthetic identifier (same rule as the Mac app). */
    val id: String = bssid ?: "${ssid ?: "?"}|${band.label}|$channel|$rssi"

    val quality: SignalQuality get() = SignalQuality.of(rssi)

    /** "5 GHz · Ch 36 · 80 MHz" */
    val channelSummary: String
        get() = buildList {
            add(band.label)
            add("Ch $channel")
            if (channelWidth > 0) add("$channelWidth MHz")
        }.joinToString(" · ")
}

/** The network this device is connected to. */
data class LinkInfo(
    val ssid: String?,
    val bssid: String?,
    val rssi: Int,
    val linkSpeedMbps: Int,
    val channel: Int,
    val band: WifiBand,
    val wifiStandard: String,
) {
    val quality: SignalQuality get() = SignalQuality.of(rssi)
}

/** All access points that broadcast the same network name. */
data class NetworkGroup(
    val ssid: String,
    /** Strongest first. Never empty. */
    val accessPoints: List<AccessPoint>,
    val isKnown: Boolean,
    /** BSSID this device is associated with, if it belongs to this group. */
    val currentBssid: String?,
    val isCurrent: Boolean,
) {
    init {
        require(accessPoints.isNotEmpty()) { "A network group needs at least one access point" }
    }

    val strongest: AccessPoint get() = accessPoints[0]
    val security: SecurityKind get() = strongest.security
    val bands: List<WifiBand> get() = WifiBand.entries.filter { band -> accessPoints.any { it.band == band } }

    fun isCurrent(ap: AccessPoint): Boolean {
        val current = currentBssid ?: return false
        val bssid = ap.bssid ?: return false
        return Bssid.normalize(bssid) == Bssid.normalize(current)
    }
}

object Bssid {
    /** Lowercases and zero-pads each octet so "A:B:C:D:E:F" and "0a:0b:0c:0d:0e:0f" compare equal. */
    fun normalize(bssid: String): String =
        bssid.split(":").joinToString(":") { if (it.length == 1) "0" + it.lowercase() else it.lowercase() }
}
