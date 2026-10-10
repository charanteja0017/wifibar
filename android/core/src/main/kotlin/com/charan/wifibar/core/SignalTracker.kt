package com.charan.wifibar.core

data class SignalSample(val timeMillis: Long, val rssi: Int)

/** Whether you're walking toward or away from the access point. */
enum class SignalTrend { STRONGER, WEAKER, STEADY, UNKNOWN }

/** A short history of readings for one access point: its best reading and which way it's heading. */
class SignalTracker(
    /** How much history to keep, in milliseconds. */
    var windowMillis: Long = 120_000,
    /** Readings averaged on each side when judging the trend; single readings jump by several dB. */
    var trendSpan: Int = 3,
    /** Change in average (dB) that counts as stronger or weaker. */
    var trendThreshold: Double = 3.0,
) {
    private val _samples = ArrayList<SignalSample>()
    val samples: List<SignalSample> get() = _samples
    var peak: SignalSample? = null
        private set

    val latest: SignalSample? get() = _samples.lastOrNull()

    /** Adds a reading. Returns true when it beats the best reading so far. */
    fun add(sample: SignalSample): Boolean {
        _samples.add(sample)
        _samples.removeAll { sample.timeMillis - it.timeMillis > windowMillis }
        val best = peak
        if (best != null && sample.rssi <= best.rssi) return false
        peak = sample
        return best != null
    }

    fun reset() {
        _samples.clear()
        peak = null
    }

    val trend: SignalTrend
        get() {
            if (_samples.size < trendSpan * 2) return SignalTrend.UNKNOWN
            val recent = _samples.takeLast(trendSpan)
            val before = _samples.dropLast(trendSpan).takeLast(trendSpan)
            val delta = recent.map { it.rssi }.average() - before.map { it.rssi }.average()
            return when {
                delta >= trendThreshold -> SignalTrend.STRONGER
                delta <= -trendThreshold -> SignalTrend.WEAKER
                else -> SignalTrend.STEADY
            }
        }
}
