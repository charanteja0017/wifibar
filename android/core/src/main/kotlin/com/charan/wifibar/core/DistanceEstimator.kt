package com.charan.wifibar.core

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** How cluttered the space between you and the access point is. */
enum class SignalEnvironment(val label: String, internal val exponent: Double, internal val exponentRange: ClosedFloatingPointRange<Double>) {
    /** Path-loss exponent n: 2.7–3.5 is typical inside buildings. */
    INDOORS("Indoors (walls)", 3.0, 2.6..3.5),
    /** n = 2 is free space. */
    OPEN_SPACE("Open space", 2.0, 1.8..2.3),
}

/** A rough distance with the spread from typical variation in the path-loss exponent. */
data class DistanceEstimate(val meters: Double, val low: Double, val high: Double)

/**
 * Log-distance path-loss model: d = 10^((RSSI₁ₘ − RSSI) / (10·n)). Same as the Mac app.
 * It ignores the router's real transmit power, antennas and obstacles, so treat it as a guide.
 */
object DistanceEstimator {
    /**
     * Expected RSSI 1 m from a typical router: 20 dBm EIRP, minus free-space path loss at 1 m,
     * minus ~10 dB for the client's antenna and body/case losses.
     */
    fun referenceRssi(band: WifiBand): Double {
        val megahertz = when (band) {
            WifiBand.GHZ_2_4, WifiBand.UNKNOWN -> 2437.0
            WifiBand.GHZ_5 -> 5500.0
            WifiBand.GHZ_6 -> 6000.0
        }
        val freeSpaceLossAt1m = 20 * log10(megahertz) - 27.55
        return 20 - freeSpaceLossAt1m - 10
    }

    fun estimate(rssi: Int, band: WifiBand, environment: SignalEnvironment): DistanceEstimate {
        val reference = referenceRssi(band)
        fun distance(exponent: Double) = max(0.3, 10.0.pow((reference - rssi) / (10 * exponent)))
        val a = distance(environment.exponentRange.start)
        val b = distance(environment.exponentRange.endInclusive)
        return DistanceEstimate(distance(environment.exponent), min(a, b), max(a, b))
    }

    /** "≈ 6 m", "< 1 m", "≈ 35 m" */
    fun format(meters: Double): String = when {
        meters < 1 -> "< 1 m"
        meters < 10 -> "≈ ${meters.roundToInt()} m"
        else -> "≈ ${(meters / 5).roundToInt() * 5} m"
    }

    /** "4–7 m", or null when the whole range is within a couple of metres. */
    fun formatRange(estimate: DistanceEstimate): String? {
        if (estimate.high < 2) return null
        fun short(meters: Double) =
            if (meters < 10) max(1, meters.roundToInt()).toString() else ((meters / 5).roundToInt() * 5).toString()
        return "${short(estimate.low)}–${short(estimate.high)} m"
    }
}
