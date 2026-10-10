package com.charan.wifibar.core

/** Where you are while walking the way the sweep pointed. */
enum class Arrival {
    /** Keep going. */
    WALKING,

    /** Very strong signal: the access point is within about a metre. */
    ARRIVED,

    /** The signal rose and has now clearly fallen: you walked past the strongest spot. */
    PASSED,

    /** No improvement for a while: you're as close as this direction gets you. */
    PLATEAU,
}

/**
 * Decides when to tell someone following the "Go this way" arrow to stop.
 *
 * Readings are noisy (one direction ranged over 15 dB on a Xiaomi Pad 8), so the best point is the mean of
 * two readings, and "passed" needs the last two *raw* readings both well below it. (Checking two
 * overlapping averages instead let a single noisy reading count twice.)
 */
class ArrivalDetector(
    /** Smoothed RSSI at which the access point is treated as within about a metre. */
    private val arrivedRssi: Double = -32.0,
    /** dB below the best smoothed value that counts as having passed it. */
    private val dropDb: Double = 4.0,
    /** Readings without a new best (≈ 3 s each on Android) before calling it a plateau. */
    private val plateauReadings: Int = 5,
) {
    private val readings = ArrayList<SignalSample>()

    /** Best smoothed reading so far on this walk. */
    var best: SignalSample? = null
        private set
    private var readingsSinceBest = 0

    var status: Arrival = Arrival.WALKING
        private set

    val readingCount: Int get() = readings.size

    fun add(sample: SignalSample): Arrival {
        readings.add(sample)
        val smoothed = readings.takeLast(2).map { it.rssi }.average()

        val currentBest = best
        if (currentBest == null || smoothed > currentBest.rssi + 0.5) {
            best = SignalSample(sample.timeMillis, Math.round(smoothed).toInt())
            readingsSinceBest = 0
        } else {
            readingsSinceBest += 1
        }
        val bestValue = best!!.rssi.toDouble()
        val passed = readings.size >= 3 && readings.takeLast(2).all { bestValue - it.rssi >= dropDb }

        status = when {
            smoothed >= arrivedRssi -> Arrival.ARRIVED
            passed -> Arrival.PASSED
            readings.size > plateauReadings && readingsSinceBest >= plateauReadings -> Arrival.PLATEAU
            else -> Arrival.WALKING
        }
        return status
    }

    fun reset() {
        readings.clear()
        best = null
        readingsSinceBest = 0
        status = Arrival.WALKING
    }
}
