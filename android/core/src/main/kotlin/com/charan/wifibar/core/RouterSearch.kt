package com.charan.wifibar.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds an access point from readings taken while walking, and says which way to walk next.
 *
 * Model, per reading i taken at pᵢ facing φᵢ, for a router at c:
 *
 *     rssiᵢ = P − 10·n·log₁₀(√(dᵢ² + h²)) − A·behind(angle from φᵢ to c) + noise
 *
 * - h: the router usually sits higher or lower than the device (1 m assumed); this also stops the
 *   model predicting impossible signal right next to it.
 * - behind(): the body blocks a ~90° sector behind the person (Zhang et al., "I am the antenna",
 *   MobiCom 2011: 10–15 dB dips; Della Rosa et al. 2012: ~6 dB indoors without line of sight).
 *   A is fitted per cell with a prior around 6 dB.
 * - P is fitted per cell (prior around the band's typical value); n is not fixed but averaged over
 *   2.0–3.5 (Zhuang et al. 2014 and others estimate path loss jointly with the position).
 * - noise: Gaussian σ plus a 15% outlier term, because real readings jump (15 dB in one direction on a
 *   Xiaomi Pad 8) and a single wild reading mustn't drag the estimate across the room.
 * - Old readings are positioned by step counting, which drifts: their position error relative to
 *   where you are now grows with the distance walked since, and is turned into extra dB of noise
 *   (largest close to the router, where a metre changes the signal most).
 *
 * Steering ([guide]): of 8 directions, walk the one that most reduces the expected distance to the
 * router, plus a bonus for information from the next reading (Dressel & Kochenderfer, ICRA 2018, steer by
 * mutual information). Pure information seeking (infotaxis, Vergassola et al., Nature 2007) walked people
 * away from the router in simulation: from afar one more reading says little, so sideways moves
 * scored higher. Stop when it's ≥ 80% likely the router is within 2 m.
 *
 * Simulated closed loop (SearchBenchmark, 150 searches, one reading every 3 s, world unlike the model),
 * as stopped within 2.5 m / stopped wrongly / no stop in 3 minutes, median time to stop:
 *   this model:                      95% / 1% / 3%, 67 s   (with jumps as often as measured, -Pharsh=0.10: 90% / 1% / 8%, 70 s)
 *   previous model (fixed n, Gaussian noise, steer to the best cell): 76% / 4% / 18%, 85 s   (harsh: 66% / 12% / 22%, 94 s)
 *   one reading per second:          96% / 4% / 0%, 48 s   (harsh: 96% / 3% / 0%, 48 s)
 * Without the drift allowance: 96% / 2% / 0%, 61 s (an earlier seed set: 96% / 4% / 0%), so it stays for fewer
 * wrong stops. A 0.5 m grid did no better than 1 m (93% / 1% / 5%). Seed sets move these by a few points.
 */
class RouterSearch(
    private val referenceRssi: Double,
    private val noiseDb: Double = 5.0,
    private val outlier: Double = 0.15,
    private val height: Double = 1.0,
    /** Position error of a reading per metre walked since (step counting drifts a few percent). */
    private val driftPerMetre: Double = 0.05,
    private val halfSize: Double = 15.0,
    private val cell: Double = 1.0,
    private val exponents: DoubleArray = doubleArrayOf(2.0, 2.5, 3.0, 3.5),
) {
    /** The router's position as a probability over grid cells, with what fits best in each. */
    class Belief(
        val cells: Array<MapPoint>,
        /** Probability of each cell; sums to 1. */
        val weights: DoubleArray,
        val power: DoubleArray,
        val exponent: DoubleArray,
        val blocking: DoubleArray,
        val readings: Int,
    ) {
        val mostLikely: MapPoint get() = cells[weights.indices.maxBy { weights[it] }]

        /** Probability that the router is within [radius] metres of [point] (horizontally). */
        fun within(point: MapPoint, radius: Double): Double {
            var p = 0.0
            for (i in cells.indices) if ((cells[i] - point).length <= radius) p += weights[i]
            return p
        }

        /** Shannon entropy of the position (nats): lower = more certain. */
        val entropy: Double
            get() {
                var s = 0.0
                for (w in weights) if (w > 0) s -= w * ln(w)
                return s
            }

        /** Roughly one standard deviation of the position, metres. */
        val spread: Double
            get() {
                var mx = 0.0; var my = 0.0
                for (i in cells.indices) { mx += weights[i] * cells[i].x; my += weights[i] * cells[i].y }
                var v = 0.0
                for (i in cells.indices) {
                    val dx = cells[i].x - mx; val dy = cells[i].y - my
                    v += weights[i] * (dx * dx + dy * dy)
                }
                return sqrt(v / 2)
            }
    }

    /** Observation with how far you had walked when it was taken (for drift). */
    fun belief(observations: List<Observation>, walkedNow: Double): Belief {
        val positions = observations.map { it.position }
        val cx = positions.map { it.x }.average()
        val cy = positions.map { it.y }.average()
        val steps = (2 * halfSize / cell).toInt() + 1
        val count = steps * steps
        val cells = Array(count) { MapPoint(cx - halfSize + (it % steps) * cell, cy - halfSize + (it / steps) * cell) }
        val logPost = DoubleArray(count)
        val power = DoubleArray(count)
        val exponent = DoubleArray(count)
        val blocking = DoubleArray(count)

        val m = observations.size
        val logDistance = DoubleArray(m)
        val slope = DoubleArray(m) // d(log₁₀ distance)/d(distance)
        val behind = DoubleArray(m)
        val positionError = DoubleArray(m) { 0.3 + driftPerMetre * max(0.0, walkedNow - observations[it].walked) }
        val variance = DoubleArray(m)
        val residual = DoubleArray(m)
        val trust = DoubleArray(m)
        val floor = outlier / 60.0 // outliers spread over ~60 dB
        val inlier = 1 - outlier

        for (k in 0 until count) {
            val c = cells[k]
            for (i in 0 until m) {
                val v = c - observations[i].position
                val d2 = v.x * v.x + v.y * v.y + height * height
                logDistance[i] = 0.5 * log10(d2)
                slope[i] = sqrt(v.x * v.x + v.y * v.y) / d2 / ln(10.0)
                behind[i] = behindness(DirectionSweep.signedDifference(observations[i].facing, v.heading))
            }
            var bestLog = Double.NEGATIVE_INFINITY
            var sumExp = 0.0
            var keepP = 0.0; var keepA = 0.0; var keepN = 0.0
            val perN = DoubleArray(exponents.size)
            for ((e, n) in exponents.withIndex()) {
                for (i in 0 until m) {
                    val extra = 10 * n * slope[i] * positionError[i]
                    variance[i] = noiseDb * noiseDb + extra * extra
                    trust[i] = 1.0
                }
                // Robust weighted fit of P and A: two rounds of reweighting by inlier probability.
                var p = referenceRssi
                var a = PRIOR_BLOCKING
                repeat(3) { round ->
                    var s11 = 1 / (8.0 * 8.0); var s12 = 0.0; var s22 = 1 / (4.0 * 4.0)
                    var t1 = referenceRssi / (8.0 * 8.0); var t2 = PRIOR_BLOCKING / (4.0 * 4.0)
                    for (i in 0 until m) {
                        val w = trust[i] / variance[i]
                        val y = observations[i].rssi + 10 * n * logDistance[i]
                        // y = P − A·behind
                        s11 += w; s12 -= w * behind[i]; s22 += w * behind[i] * behind[i]
                        t1 += w * y; t2 -= w * y * behind[i]
                    }
                    val det = s11 * s22 - s12 * s12
                    p = (t1 * s22 - s12 * t2) / det
                    a = max(0.0, (s11 * t2 - s12 * t1) / det)
                    if (round < 2) for (i in 0 until m) {
                        val r = observations[i].rssi - (p - 10 * n * logDistance[i] - a * behind[i])
                        val g = inlier * exp(-r * r / (2 * variance[i])) / sqrt(2 * PI * variance[i])
                        trust[i] = g / (g + floor)
                    }
                }
                var ll = -(p - referenceRssi) * (p - referenceRssi) / (2 * 64.0) - (a - PRIOR_BLOCKING) * (a - PRIOR_BLOCKING) / (2 * 16.0)
                for (i in 0 until m) {
                    val r = observations[i].rssi - (p - 10 * n * logDistance[i] - a * behind[i])
                    ll += ln(inlier * exp(-r * r / (2 * variance[i])) / sqrt(2 * PI * variance[i]) + floor)
                }
                perN[e] = ll
                if (ll > bestLog) { bestLog = ll; keepP = p; keepA = a; keepN = n }
            }
            for (ll in perN) sumExp += exp(ll - bestLog)
            logPost[k] = bestLog + ln(sumExp)
            power[k] = keepP; exponent[k] = keepN; blocking[k] = keepA
        }
        val top = logPost.max()
        val weights = DoubleArray(count) { exp(logPost[it] - top) }
        val total = weights.sum()
        for (k in weights.indices) weights[k] /= total
        return Belief(cells, weights, power, exponent, blocking, m)
    }

    /** One possible next move: walk [distance] m toward [heading] (or turn on the spot when 0). */
    data class Move(val heading: Double, val distance: Double)

    /** The move chosen, why, and how it scored. */
    data class Advice(val move: Move, val arriveChance: Double, val informationGain: Double)

    /**
     * Picks the next move from [here] facing [facing]: 8 directions walked for one reading interval,
     * or turning around on the spot. Each scores (chance of arriving within [arriveRadius]) × current
     * uncertainty + (1 − that) × expected information from the next reading (infotaxis).
     */
    fun advise(
        belief: Belief,
        here: MapPoint,
        facing: Double,
        stride: Double,
        arriveRadius: Double = 2.0,
        candidates: List<Move> = (0 until 8).map { Move(facing + it * 45.0, stride) } + Move(facing + 180, 0.0),
    ): Advice {
        val moves = candidates
        val entropy = belief.entropy
        // Only cells that matter, to keep this fast.
        val top = belief.weights.max()
        val idx = belief.weights.indices.filter { belief.weights[it] > top * 1e-3 }
        val norm = idx.sumOf { belief.weights[it] }
        var best: Advice? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (move in moves) {
            val to = if (move.distance > 0) {
                val d = MapPoint.direction(move.heading)
                MapPoint(here.x + d.x * move.distance, here.y + d.y * move.distance)
            } else here
            val gain = expectedInformation(belief, idx, norm, to, move.heading)
            val arrive = belief.within(to, arriveRadius)
            val score = arrive * entropy + (1 - arrive) * gain
            if (score > bestScore) {
                bestScore = score
                best = Advice(move, arrive, gain)
            }
        }
        return best!!
    }

    /**
     * Which way to walk next (heading, degrees left of the map's +y): of 8 directions around [facing],
     * the one minimising expected distance to the router after [stride] metres, minus [bonus] metres
     * per nat of information the next reading there is expected to give.
     */
    fun guide(belief: Belief, here: MapPoint, facing: Double, stride: Double, bonus: Double = 6.0): Double =
        (0 until 8).map { facing + it * 45.0 }.minBy { heading ->
            val d = MapPoint.direction(heading)
            val to = MapPoint(here.x + d.x * stride, here.y + d.y * stride)
            expectedDistance(belief, to) - bonus * information(belief, to, heading)
        }

    /** Expected distance (m) from [point] to the router under the belief. */
    fun expectedDistance(belief: Belief, point: MapPoint): Double {
        var e = 0.0
        for (i in belief.cells.indices) if (belief.weights[i] > 1e-6) e += belief.weights[i] * (belief.cells[i] - point).length
        return e
    }

    /** Mutual information (nats) between the router's cell and a reading at [at] facing [facing]. */
    fun information(belief: Belief, at: MapPoint, facing: Double): Double {
        val top = belief.weights.max()
        val idx = belief.weights.indices.filter { belief.weights[it] > top * 1e-3 }
        return expectedInformation(belief, idx, idx.sumOf { belief.weights[it] }, at, facing)
    }

    private fun expectedInformation(belief: Belief, idx: List<Int>, norm: Double, at: MapPoint, facing: Double): Double {
        val mean = DoubleArray(idx.size)
        var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
        idx.forEachIndexed { j, k ->
            val v = belief.cells[k] - at
            val d2 = v.x * v.x + v.y * v.y + height * height
            mean[j] = belief.power[k] - 10 * belief.exponent[k] * 0.5 * log10(d2) -
                belief.blocking[k] * behindness(DirectionSweep.signedDifference(facing, v.heading))
            lo = min(lo, mean[j]); hi = max(hi, mean[j])
        }
        // H(z) − H(z | cell), by quadrature; H(z | cell) is the same for every cell.
        val sigma = noiseDb
        val step = 1.0
        var z = lo - 4 * sigma
        var hz = 0.0
        var hzGivenCell = 0.0
        val floor = outlier / 60.0
        val inlier = 1 - outlier
        val norm0 = 1 / sqrt(2 * PI * sigma * sigma)
        while (z <= hi + 4 * sigma) {
            var pz = 0.0
            idx.forEachIndexed { j, k ->
                val r = z - mean[j]
                pz += belief.weights[k] / norm * (inlier * norm0 * exp(-r * r / (2 * sigma * sigma)) + floor)
            }
            if (pz > 0) hz -= pz * ln(pz) * step
            z += step
        }
        // Entropy of one cell's reading over the same span (for the conditional term).
        z = -4 * sigma
        while (z <= 4 * sigma) {
            val pz = inlier * norm0 * exp(-z * z / (2 * sigma * sigma)) + floor
            hzGivenCell -= pz * ln(pz) * step
            z += step
        }
        hzGivenCell += -(hi - lo) * floor * ln(floor) // the outlier floor over the rest of the span
        return max(0.0, hz - hzGivenCell)
    }

    companion object {
        const val PRIOR_BLOCKING = 6.0

        /** 0 when the router is within 100° of where you face, 1 when it's within 40° of straight behind. */
        fun behindness(angleDegrees: Double): Double {
            val a = abs(angleDegrees)
            return when {
                a <= 100 -> 0.0
                a >= 140 -> 1.0
                else -> {
                    val t = (a - 100) / 40
                    t * t * (3 - 2 * t)
                }
            }
        }
    }
}
