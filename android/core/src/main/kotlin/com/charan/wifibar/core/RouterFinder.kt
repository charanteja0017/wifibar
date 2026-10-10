package com.charan.wifibar.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A point on the walk map, in metres. The frame is fixed when the walk starts:
 * +y is the way the device faced, +x is to its right.
 */
data class MapPoint(val x: Double, val y: Double) {
    operator fun minus(o: MapPoint) = MapPoint(x - o.x, y - o.y)
    val length: Double get() = hypot(x, y)

    /** Heading of this vector in the app's convention: degrees left of +y. */
    val heading: Double get() = Math.toDegrees(atan2(-x, y))

    companion object {
        val ORIGIN = MapPoint(0.0, 0.0)

        /** Unit vector for a heading (degrees left of +y). */
        fun direction(headingDegrees: Double): MapPoint {
            val r = Math.toRadians(headingDegrees)
            return MapPoint(-sin(r), cos(r))
        }
    }
}

/** Pedestrian dead reckoning: each step moves [stepLength] metres the way the device faces. */
class DeadReckoning(var stepLength: Double = 0.7) {
    var position = MapPoint.ORIGIN
        private set
    private val _path = arrayListOf(MapPoint.ORIGIN)
    val path: List<MapPoint> get() = _path

    fun step(headingDegrees: Double) {
        val d = MapPoint.direction(headingDegrees)
        position = MapPoint(position.x + d.x * stepLength, position.y + d.y * stepLength)
        _path.add(position)
    }

    fun reset() {
        position = MapPoint.ORIGIN
        _path.clear()
        _path.add(MapPoint.ORIGIN)
    }
}

/** One signal reading, where it was taken, which way the device faced, and how far you'd walked (m). */
data class Observation(val position: MapPoint, val facing: Double, val rssi: Int, val walked: Double = 0.0)

/**
 * Estimates where the access point is from readings taken while walking.
 *
 * For every cell of a grid around the walk it asks how well "the router is here" explains all readings,
 * using the log-distance law plus a facing term (the body blocks signal from behind):
 *
 *     rssi_i ≈ P₁ₘ − 10·n·log₁₀(dᵢ) + B·cos(angle between facing and the router)
 *
 * P₁ₘ and B are fitted per cell by least squares with weak priors (P₁ₘ near the band's typical value,
 * B near 0), n is fixed. With Gaussian noise σ the cell's likelihood is exp(−residual²/2σ²); the
 * normalised grid is the posterior over the router's position.
 */
class RouterFinder(
    private val referenceRssi: Double,
    private val exponent: Double = 3.0,
    private val noiseDb: Double = 5.0,
    private val halfSize: Double = 15.0,
    private val cell: Double = 0.5,
) {
    enum class Status {
        /** Fewer than 4 readings. */
        NEED_READINGS,

        /** All readings within ~1.5 m of each other: only distance is known, not where. */
        NEED_MOVEMENT,

        /** Walked in a straight line: the router could be on either side of it. */
        NEED_TURN,

        /** A position estimate. */
        READY,
    }

    data class Estimate(
        val status: Status,
        /**
         * Most likely router position (the best grid cell), or null before there's enough data.
         * Simulated walk-and-follow benchmark (σ = 5 dB readings, 5 router positions × 20 runs, 40 steps):
         * steering to the best cell reached within 1.5 m in 78/100 runs; the posterior mean, whose long
         * "maybe farther" tail pulls it off course, in 62/100.
         */
        val position: MapPoint?,
        /** Roughly one standard deviation of the position, metres. */
        val uncertainty: Double,
        /** Posterior cells with noticeable probability: (centre, probability relative to the best cell). */
        val heat: List<Pair<MapPoint, Double>>,
        val readings: Int,
    )

    /**
     * The grid posterior over the router's position: each cell with its probability (summing to 1)
     * and the P₁ₘ and facing gain B that fit best if the router is there.
     */
    class Posterior(
        val cells: List<MapPoint>,
        val weights: DoubleArray,
        val power: DoubleArray,
        val facingGain: DoubleArray,
        val exponent: Double,
        val noiseDb: Double,
    ) {
        /** Expected reading at [at] facing [facing] if the router is in cell [i]. */
        fun predicted(i: Int, at: MapPoint, facing: Double): Double {
            val v = cells[i] - at
            return power[i] - 10 * exponent * log10(max(0.5, v.length)) + facingGain[i] * cos(Math.toRadians(v.heading - facing))
        }
    }

    fun posterior(observations: List<Observation>): Posterior {
        val positions = observations.map { it.position }
        val center = MapPoint(positions.map { it.x }.average(), positions.map { it.y }.average())
        val steps = (2 * halfSize / cell).toInt() + 1
        val count = steps * steps
        val logLikelihood = DoubleArray(count)
        val power = DoubleArray(count)
        val gain = DoubleArray(count)
        val cells = ArrayList<MapPoint>(count)

        // Priors expressed as ridge terms: P₁ₘ ~ N(reference, 8²), B ~ N(0, 4²), relative to noise σ².
        val variance = noiseDb * noiseDb
        val lambdaP = variance / 64.0
        val lambdaB = variance / 16.0
        val ys = DoubleArray(observations.size)
        val fs = DoubleArray(observations.size)

        var best = Double.NEGATIVE_INFINITY
        for (iy in 0 until steps) for (ix in 0 until steps) {
            val c = MapPoint(center.x - halfSize + ix * cell, center.y - halfSize + iy * cell)
            val index = cells.size
            cells.add(c)
            var n = 0.0; var sumF = 0.0; var sumFF = 0.0; var sumY = 0.0; var sumYF = 0.0
            observations.forEachIndexed { i, o ->
                val v = c - o.position
                val d = max(0.5, v.length)
                val y = o.rssi + 10 * exponent * log10(d)
                val f = cos(Math.toRadians(v.heading - o.facing))
                ys[i] = y; fs[i] = f
                n += 1; sumF += f; sumFF += f * f; sumY += y; sumYF += y * f
            }
            // Normal equations for (P, B) with ridge priors.
            val a11 = n + lambdaP
            val a12 = sumF
            val a22 = sumFF + lambdaB
            val b1 = sumY + lambdaP * referenceRssi
            val b2 = sumYF
            val det = a11 * a22 - a12 * a12
            val p = (b1 * a22 - a12 * b2) / det
            val b = (a11 * b2 - a12 * b1) / det
            var residual = lambdaP * (p - referenceRssi) * (p - referenceRssi) + lambdaB * b * b
            for (i in ys.indices) {
                val e = ys[i] - p - b * fs[i]
                residual += e * e
            }
            val ll = -residual / (2 * variance)
            logLikelihood[index] = ll
            power[index] = p
            gain[index] = b
            if (ll > best) best = ll
        }
        val weights = DoubleArray(count) { exp(logLikelihood[it] - best) }
        val total = weights.sum()
        for (i in weights.indices) weights[i] /= total
        return Posterior(cells, weights, power, gain, exponent, noiseDb)
    }

    fun estimate(observations: List<Observation>): Estimate {
        if (observations.size < 4) return Estimate(Status.NEED_READINGS, null, Double.NaN, emptyList(), observations.size)
        return estimate(observations, posterior(observations))
    }

    fun estimate(observations: List<Observation>, posterior: Posterior): Estimate {
        if (observations.size < 4) return Estimate(Status.NEED_READINGS, null, Double.NaN, emptyList(), observations.size)
        val cells = posterior.cells
        val weights = posterior.weights
        var mx = 0.0; var my = 0.0
        for (i in cells.indices) {
            mx += weights[i] * cells[i].x; my += weights[i] * cells[i].y
        }
        var vxx = 0.0; var vyy = 0.0
        for (i in cells.indices) {
            vxx += weights[i] * (cells[i].x - mx) * (cells[i].x - mx)
            vyy += weights[i] * (cells[i].y - my) * (cells[i].y - my)
        }
        val uncertainty = sqrt((vxx + vyy) / 2)
        val bestIndex = weights.indices.maxBy { weights[it] }
        val bestWeight = weights[bestIndex]
        val heat = cells.indices.filter { weights[it] > 0.05 * bestWeight }.map { cells[it] to weights[it] / bestWeight }

        val positions = places(observations.map { it.position })
        val status = when {
            spread(positions) < 1.5 -> Status.NEED_MOVEMENT
            sideUnknown(positions, posterior, bestIndex) -> Status.NEED_TURN
            else -> Status.READY
        }
        return Estimate(status, cells[bestIndex], uncertainty, heat, observations.size)
    }

    /**
     * Where readings were taken, each place once (within 0.3 m). Readings while standing still pile up at
     * one point, which made a walk that had turned look straight again.
     */
    private fun places(points: List<MapPoint>): List<MapPoint> {
        val out = ArrayList<MapPoint>()
        for (p in points) if (out.none { (it - p).length < 0.3 }) out.add(p)
        return out
    }

    private fun spread(points: List<MapPoint>): Double {
        var max = 0.0
        for (i in points.indices) for (j in i + 1 until points.size) max = max(max, (points[i] - points[j]).length)
        return max
    }

    /**
     * True when the readings can't tell which side of the walk the router is on: a fairly straight walk
     * gives the same readings for a router and its mirror image across the walk's line. Asked of the
     * posterior directly: away from the line (±1 m), at least a quarter of the probability lies on the
     * opposite side from the best cell. (A geometric "is the walk straight" test flipped back and forth
     * on a gently curved real walk.)
     */
    private fun sideUnknown(points: List<MapPoint>, posterior: Posterior, bestIndex: Int): Boolean {
        val mx = points.map { it.x }.average()
        val my = points.map { it.y }.average()
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (p in points) {
            sxx += (p.x - mx) * (p.x - mx); syy += (p.y - my) * (p.y - my); sxy += (p.x - mx) * (p.y - my)
        }
        // Direction of the walk's main axis.
        val angle = 0.5 * atan2(2 * sxy, sxx - syy)
        val ux = cos(angle)
        val uy = sin(angle)
        fun side(c: MapPoint) = ux * (c.y - my) - uy * (c.x - mx)
        val bestSide = side(posterior.cells[bestIndex])
        if (abs(bestSide) <= 1.0) return false // on (or near) the line ahead or behind: no mirror to confuse it with
        var same = 0.0
        var opposite = 0.0
        posterior.cells.forEachIndexed { i, c ->
            val d = side(c)
            if (abs(d) > 1.0) if (d * bestSide > 0) same += posterior.weights[i] else opposite += posterior.weights[i]
        }
        return opposite >= 0.25 * (same + opposite)
    }

    companion object {
        /** Distance and turn (degrees, left positive) from where you stand and face to a target. */
        fun bearingTo(from: MapPoint, facing: Double, target: MapPoint): Pair<Double, Double> {
            val v = target - from
            return v.length to DirectionSweep.signedDifference(facing, v.heading)
        }

    }
}
