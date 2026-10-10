package com.charan.wifibar.core

/**
 * Counts walking steps from the acceleration along "up", for a device held in front of you.
 *
 * Each step is one bounce: the up-acceleration (gravity removed by a slow baseline, then smoothed)
 * rises above [rise] and falls below −[fall]. A lone bounce, such as a hand movement, doesn't count:
 * the first step is held until a second follows within [maxGap] seconds, and then both are reported.
 *
 * Unlike Android's step detector this needs no permission and adds no start-up delay; some vendors'
 * detectors hold back the first several steps or ignore a device carried in the hand.
 */
class StepCounter(
    /** m/s² above the baseline that starts a bounce. */
    private val rise: Double = 0.6,
    /** m/s² below the baseline that completes it. */
    private val fall: Double = 0.3,
    /** Seconds; steps closer together than this are one step (faster than ~3.5 steps/s). */
    private val minGap: Double = 0.28,
    /** Seconds; a longer pause means walking stopped, and the next bounce needs confirming again. */
    private val maxGap: Double = 1.6,
) {
    private var baseline = Double.NaN
    private var smooth = 0.0
    private var lastTime = 0.0
    private var high = false
    private var lastBounce = Double.NEGATIVE_INFINITY
    private var pending = false

    /** Smoothed up-acceleration after the last sample, for recording walks. */
    val filtered: Double get() = smooth

    /**
     * Adds one sample of acceleration along "up" (m/s², gravity included) at [time] seconds.
     * Returns how many steps to take now: 0, 1, or 2 when a held first step is confirmed.
     */
    fun add(upAcceleration: Double, time: Double): Int {
        if (baseline.isNaN()) {
            baseline = upAcceleration
            lastTime = time
            return 0
        }
        val dt = (time - lastTime).coerceIn(0.0, 0.2)
        lastTime = time
        baseline += (upAcceleration - baseline) * dt / (1.0 + dt)
        smooth += (upAcceleration - baseline - smooth) * dt / (0.06 + dt)

        if (!high) {
            if (smooth > rise) high = true
            return 0
        }
        if (smooth > -fall) return 0
        high = false
        val gap = time - lastBounce
        if (gap < minGap) return 0
        lastBounce = time
        if (gap > maxGap) {
            pending = true
            return 0
        }
        if (pending) {
            pending = false
            return 2
        }
        return 1
    }

    fun reset() {
        baseline = Double.NaN
        smooth = 0.0
        high = false
        lastBounce = Double.NEGATIVE_INFINITY
        pending = false
    }
}
