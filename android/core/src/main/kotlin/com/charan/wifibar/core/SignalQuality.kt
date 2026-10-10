package com.charan.wifibar.core

/** Signal quality bucket for an RSSI reading; same thresholds as the Mac app. */
enum class SignalQuality(val bars: Int, val label: String) {
    WEAK(0, "Weak"),
    FAIR(1, "Fair"),
    GOOD(2, "Good"),
    EXCELLENT(3, "Excellent");

    companion object {
        fun of(rssi: Int): SignalQuality = when {
            rssi >= -55 -> EXCELLENT
            rssi >= -67 -> GOOD
            rssi >= -77 -> FAIR
            else -> WEAK
        }

        /** Position of an RSSI reading on a −90…−30 dBm scale, for meters. */
        fun meterFraction(rssi: Int): Double = (rssi.toDouble().coerceIn(-90.0, -30.0) + 90) / 60
    }
}
