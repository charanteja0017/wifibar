package com.charan.wifibar.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    operator fun div(k: Double) = Vec3(x / k, y / k, z / k)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    val length: Double get() = sqrt(this dot this)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}

/**
 * Tracks how far the device has turned about the vertical axis (port of the Mac's HeadingTracker).
 *
 * Feed it gyroscope readings in degrees per second and an "up" vector in the device's own axes.
 * On Android, `Sensor.TYPE_GRAVITY` reads +9.81 on z when lying flat, so it already points up
 * (Apple's accelerometer points down, which is why the Mac version negates it).
 * Turning left (counter-clockwise seen from above) is positive.
 */
class HeadingTracker {
    /** Degrees turned since calibration ended. */
    var yaw = 0.0
        private set
    var isCalibrating = false
        private set
    private var up = Vec3(0.0, 0.0, 1.0)
    /** Resting gyroscope offset measured during calibration (deg/s). */
    var bias = Vec3.ZERO
        private set
    private var biasSum = Vec3.ZERO
    private var biasSquares = Vec3.ZERO
    private var biasCount = 0
    private var hasUp = false
    private var lastGyroTime: Double? = null

    /** Smoothed "up" direction; any magnitude. The first reading is used as-is, not blended with a guess. */
    fun addUp(value: Vec3) {
        up = if (hasUp) up * 0.9 + value * 0.1 else value
        hasUp = true
    }

    /** Gyroscope reading in degrees per second at [timeSeconds]. */
    fun addRotation(value: Vec3, timeSeconds: Double) {
        val last = lastGyroTime
        lastGyroTime = timeSeconds
        if (isCalibrating) {
            biasSum += value
            biasSquares += Vec3(value.x * value.x, value.y * value.y, value.z * value.z)
            biasCount += 1
            return
        }
        if (last == null) return
        val dt = timeSeconds - last
        // Skip gaps (sleep, dropped events) rather than integrating a stale rate across them.
        if (dt <= 0 || dt >= 0.1) return
        val length = up.length
        if (length < 1e-6) return
        yaw += ((value - bias) dot (up / length)) * dt
    }

    /** Hold still: averages the gyroscope's resting offset. */
    fun beginCalibration() {
        isCalibrating = true
        biasSum = Vec3.ZERO
        biasSquares = Vec3.ZERO
        biasCount = 0
    }

    /** False when the last calibration was rejected because the device moved during it. */
    var calibrationAccepted = false
        private set

    /** Number of gyroscope readings averaged in the last calibration. */
    var calibrationSamples = 0
        private set

    val upDirection: Vec3 get() = up

    /**
     * Learns the resting offset only if the device was actually still: a turn during calibration would
     * otherwise be subtracted forever after and the heading would creep (seen on the Xiaomi Pad 8).
     * "Still" = little wobble (< 0.3 °/s) and a plausible offset (< 2 °/s; the M4 Mac's was ~0.7).
     * A perfectly smooth slow turn can't be told from an offset by a gyroscope alone, which is why the
     * Android app doesn't calibrate at all: its gyroscope arrives with the offset already removed.
     */
    fun endCalibration() {
        calibrationSamples = biasCount
        val mean = if (biasCount > 0) biasSum / biasCount.toDouble() else Vec3.ZERO
        val spread = if (biasCount > 0) {
            val sq = biasSquares / biasCount.toDouble()
            sqrt(maxOf(sq.x - mean.x * mean.x, sq.y - mean.y * mean.y, sq.z - mean.z * mean.z, 0.0))
        } else {
            Double.MAX_VALUE
        }
        calibrationAccepted = biasCount >= 10 && mean.length < 2.0 && spread < 0.3
        bias = if (calibrationAccepted) mean else Vec3.ZERO
        isCalibrating = false
        yaw = 0.0
    }

    /** Starts counting the turn from here without touching the offset. */
    fun resetYaw() {
        yaw = 0.0
    }
}

/** Signal readings taken while turning on the spot, and where they peak (port of the Mac's DirectionSweep). */
class DirectionSweep {
    data class Reading(
        /** Raw degrees turned when the reading was taken (left positive). */
        val heading: Double,
        val rssi: Int,
    )

    data class Result(
        /** Strongest direction, in degrees left of where the sweep started (0 until 360). */
        val bearing: Double,
        /** dB between the strongest and weakest directions. */
        val spread: Double,
        /** Smoothed signal around the circle: `profile[i]` is at `i * profileStep` degrees. */
        val profile: List<Double>,
        val profileStep: Double,
        /** Multiplier that maps raw headings onto a true 360° circle. */
        val scale: Double,
        /** How the best 45° sector compares with the strongest non-neighbouring one; null without enough sectors. */
        val confidence: Confidence? = null,
    ) {
        /**
         * Clear when the profile varies (≥ 4 dB) and the directions statistically tied with the best
         * sit side by side (one span ≤ 90° wide). Scattered ties mean reflections dominate: on a
         * Xiaomi Pad 8 close to the router, 225° tied with 0° and 45°, and the old arrow was wrong.
         */
        val isClear: Boolean get() = spread >= 4 && (confidence?.isConsistent ?: true)

        /** Where to point: the middle of the tied span when there is one, else the smoothed peak. */
        val direction: Double get() = confidence?.takeIf { it.isConsistent }?.center ?: bearing
    }

    /** Eight 45° sectors (0 = start, counting left). */
    data class Confidence(
        val bestSector: Int,
        /** Sectors whose mean is within noise of the best (always includes it). */
        val tiedSectors: Set<Int>,
        /** Pooled reading-to-reading standard deviation within a sector, dB. */
        val noise: Double,
    ) {
        /** Tied sectors in order around the circle from the run's first sector; null if they aren't one run. */
        val run: List<Int>?
            get() {
                if (tiedSectors.size >= 8) return null
                val starts = tiedSectors.filter { ((it + 7) % 8) !in tiedSectors }
                if (starts.size != 1) return null
                return generateSequence(starts[0]) { (it + 1) % 8 }.take(tiedSectors.size).toList()
            }

        /** One run of at most 3 neighbouring sectors (≤ 90°). */
        val isConsistent: Boolean get() = run?.let { it.size <= 3 } ?: false

        /** Degrees left of start where the span begins and ends (sector centres). */
        val from: Double get() = (run?.first() ?: bestSector) * 45.0
        val to: Double get() = (run?.last() ?: bestSector) * 45.0
        val center: Double get() = normalized(from + ((run?.size ?: 1) - 1) * 22.5)
    }

    sealed class Failure(message: String) : Exception(message) {
        data class TooFewReadings(val count: Int) : Failure("Only $count readings")
        data class TurnTooSmall(val degrees: Double) : Failure("Turned ${degrees.toInt()}°")
        data class TurnTooLarge(val degrees: Double) : Failure("Turned ${degrees.toInt()}°")
    }

    private val _readings = ArrayList<Reading>()
    val readings: List<Reading> get() = _readings

    fun add(heading: Double, rssi: Int) {
        _readings.add(Reading(heading, rssi))
    }

    /**
     * [totalTurn] is the raw yaw measured when the person said they were facing the start again.
     * Treating that as exactly 360° cancels gyroscope scale error and slow drift.
     */
    @Throws(Failure::class)
    fun result(totalTurn: Double): Result {
        val turn = abs(totalTurn)
        if (turn < 250) throw Failure.TurnTooSmall(turn)
        if (turn > 540) throw Failure.TurnTooLarge(turn)
        if (_readings.size < MIN_READINGS) throw Failure.TooFewReadings(_readings.size)

        val scale = 360 / turn
        val points = _readings.map { normalized(it.heading * scale) to it.rssi.toDouble() }

        // Circular Gaussian smoothing: a single noisy reading can't make a peak on its own.
        val step = 5.0
        val width = 25.0
        val profile = (0 until (360 / step).toInt()).map { index ->
            val center = index * step
            var weighted = 0.0
            var total = 0.0
            for ((angle, rssi) in points) {
                val distance = angularDistance(angle, center)
                val weight = exp(-0.5 * (distance / width) * (distance / width))
                weighted += weight * rssi
                total += weight
            }
            if (total > 0) weighted / total else -100.0
        }
        val best = profile.indices.maxBy { profile[it] }
        return Result(
            bearing = best * step,
            spread = profile.max() - profile.min(),
            profile = profile,
            profileStep = step,
            scale = scale,
            confidence = confidence(points),
        )
    }

    /** Groups readings into eight 45° sectors; a sector ties with the best unless it's lower by 2 standard errors (and 3 dB). */
    private fun confidence(points: List<Pair<Double, Double>>): Confidence? {
        val sectors = points.groupBy({ (Math.round(it.first / 45).toInt()) % 8 }, { it.second })
        if (sectors.size < 4) return null
        val means = sectors.mapValues { (_, values) -> values.average() }
        val best = means.maxBy { it.value }.key
        var sumSquares = 0.0
        var degrees = 0
        for ((sector, values) in sectors) {
            val mean = means.getValue(sector)
            sumSquares += values.sumOf { (it - mean) * (it - mean) }
            degrees += values.size - 1
        }
        val pooledSd = if (degrees > 0) sqrt(sumSquares / degrees) else 0.0
        val bestCount = sectors.getValue(best).size
        val tied = means.filter { (sector, mean) ->
            val standardError = pooledSd * sqrt(1.0 / bestCount + 1.0 / sectors.getValue(sector).size)
            means.getValue(best) - mean < maxOf(3.0, 2 * standardError)
        }.keys
        return Confidence(best, tied, pooledSd)
    }

    companion object {
        /** Stop-and-hold gives ~2 readings per direction × 8 directions; a continuous turn gives more. */
        const val MIN_READINGS = 12

        /** 0 until 360 */
        fun normalized(angle: Double): Double {
            val value = angle % 360
            return if (value < 0) value + 360 else value
        }

        /** −180 until 180: positive means turn left. */
        fun signedDifference(current: Double, target: Double): Double {
            val difference = normalized(target - current)
            return if (difference >= 180) difference - 360 else difference
        }

        internal fun angularDistance(a: Double, b: Double) = abs(signedDifference(a, b))
    }
}
