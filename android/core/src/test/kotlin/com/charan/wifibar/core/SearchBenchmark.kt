package com.charan.wifibar.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import org.junit.Assume

/**
 * Closed-loop simulation of a person following the app, to compare search strategies by what the user
 * experiences: seconds until the app says stop, and how far the router really is at that moment.
 * The simulated world deliberately differs from the app's model (shape of the body shadow, smooth wall
 * shadowing, outliers, router height, step length, gyro drift, human turning and reaction time).
 */
class SearchBenchmark {
    private fun gauss(r: Random): Double {
        val u = r.nextDouble(1e-12, 1.0)
        return sqrt(-2 * ln(u)) * cos(2 * Math.PI * r.nextDouble())
    }

    /** Extra probability of a big jump per reading (0 = the original world). */
    var harsh = (System.getProperty("harsh") ?: "0").toDouble()

    inner class World(seed: Int) {
        val random = Random(seed * 7919 + 13)
        val router: MapPoint = run {
            val d = random.nextDouble(4.0, 14.0)
            val b = random.nextDouble(0.0, 360.0)
            MapPoint.direction(b).let { MapPoint(it.x * d, it.y * d) }
        }
        val height = random.nextDouble(0.0, 2.0)
        val power = -37.0 + 4 * gauss(random)
        val exponent = random.nextDouble(2.2, 3.4)
        val block = random.nextDouble(3.0, 10.0)
        val stepLength = random.nextDouble(0.55, 0.8)
        private val waves = List(24) {
            val wl = random.nextDouble(1.5, 6.0)
            val dir = random.nextDouble(0.0, 2 * Math.PI)
            Triple(2 * Math.PI / wl * cos(dir), 2 * Math.PI / wl * sin(dir), random.nextDouble(0.0, 2 * Math.PI))
        }

        /** Smooth shadowing field, σ ≈ 3 dB. */
        fun shadow(p: MapPoint) = 3.0 * sqrt(2.0 / waves.size) * waves.sumOf { (kx, ky, ph) -> cos(kx * p.x + ky * p.y + ph) }

        fun rssi(p: MapPoint, facing: Double, r: Random): Int {
            val v = router - p
            val d = sqrt(v.x * v.x + v.y * v.y + height * height)
            val off = 180 - abs(DirectionSweep.signedDifference(facing, v.heading))
            val body = block * exp(-off * off / (2 * 35.0 * 35.0))
            var x = power - 10 * exponent * log10(max(0.3, d)) - body + shadow(p) + 4 * gauss(r)
            // Jumps: on the Xiaomi Pad 8, 13% of back-to-back readings standing still differed by ≥ 10 dB.
            if (r.nextDouble() < harsh) x += (if (r.nextBoolean()) 1 else -1) * r.nextDouble(6.0, 20.0)
            else if (r.nextDouble() < 0.05) x -= r.nextDouble(5.0, 15.0)
            return min(-15, x.roundToInt())
        }
    }

    data class Outcome(val stopTime: Double?, val distanceAtStop: Double?, val firstWithin2m: Double?)

    /** What the app tells the walker after each reading: heading to walk (app frame), stand still, or stop. */
    data class Command(val heading: Double?, val walk: Boolean, val stop: Boolean)

    interface Strategy {
        fun reset()
        fun decide(obs: List<Observation>, here: MapPoint, facing: Double, walked: Double, stride: Double, interval: Double): Command
    }

    /** What the app does today: RouterFinder, steer to the best cell, "turn" when asked, Locator's stop rules. */
    class Current : Strategy {
        private var turned = false
        private var stopped = false
        override fun reset() { turned = false; stopped = false }
        override fun decide(obs: List<Observation>, here: MapPoint, facing: Double, walked: Double, stride: Double, interval: Double): Command {
            val strong = obs.size >= 2 && obs.takeLast(2).map { it.rssi }.average() >= -32
            if (strong) return Command(null, false, true)
            val e = RouterFinder(-36.4).estimate(obs)
            return when (e.status) {
                RouterFinder.Status.NEED_READINGS, RouterFinder.Status.NEED_MOVEMENT -> Command(facing, true, false)
                RouterFinder.Status.NEED_TURN -> {
                    if (!turned) { turned = true; Command(facing - 90, true, false) } else Command(facing, true, false)
                }
                RouterFinder.Status.READY -> {
                    turned = false
                    val target = e.position!!
                    val at = (target - here).length <= 1.0 && e.uncertainty <= 2.0
                    if (at) Command(null, false, true) else Command((target - here).heading, true, false)
                }
            }
        }
    }

    companion object {
        /** Readings averaged for "very strong signal": about 3 s worth, at least 2 (as in the app). */
        fun strongWindow(interval: Double) = max(2, Math.round(3.0 / interval).toInt())
    }

    enum class Steer { BEST_CELL, INFOTAXIS, CLOSER, CLOSER_INFORMED }

    /**
     * New model with a steering rule:
     * BEST_CELL heads for the most likely cell; INFOTAXIS maximises arrival chance + information;
     * CLOSER picks, of 8 directions, the one that most reduces the expected distance to the router;
     * CLOSER_INFORMED does the same but adds information gain (λ m per nat).
     */
    class Search(private val steer: Steer, private val cell: Double, private val stopAt: Double = 0.8, private val lambda: Double = 4.0, private val drift: Double = 0.05) : Strategy {
        override fun reset() {}
        override fun decide(obs: List<Observation>, here: MapPoint, facing: Double, walked: Double, stride: Double, interval: Double): Command {
            val window = strongWindow(interval)
            val strong = obs.size >= window && obs.takeLast(window).map { it.rssi }.average() >= -32
            if (strong) return Command(null, false, true)
            if (obs.size < 3) return Command(facing, true, false)
            val search = RouterSearch(-36.4, cell = cell, driftPerMetre = drift)
            val belief = search.belief(obs, walked)
            if (belief.within(here, 2.0) >= stopAt) return Command(null, false, true)
            fun step(h: Double) = MapPoint.direction(h).let { MapPoint(here.x + it.x * stride, here.y + it.y * stride) }
            return when (steer) {
                Steer.BEST_CELL -> Command((belief.mostLikely - here).heading, true, false)
                Steer.INFOTAXIS -> search.advise(belief, here, facing, stride).let { Command(it.move.heading, it.move.distance > 0, false) }
                Steer.CLOSER -> {
                    val h = (0 until 8).map { facing + it * 45.0 }.minBy { search.expectedDistance(belief, step(it)) }
                    Command(h, true, false)
                }
                Steer.CLOSER_INFORMED -> Command(search.guide(belief, here, facing, stride, lambda), true, false)
            }
        }
    }

    fun run(strategy: Strategy, seed: Int, interval: Double, limit: Double = 180.0): Outcome {
        val world = World(seed)
        val r = Random(seed * 31 + 7)
        strategy.reset()
        val speed = 0.8
        val dt = 0.05
        var t = 0.0
        var truePos = MapPoint.ORIGIN
        var trueHeading = 0.0
        var appPos = MapPoint.ORIGIN
        var walked = 0.0
        val drift = r.nextDouble(-1.0, 1.0) / 60 // °/s gyro drift
        var command = Command(0.0, true, false)
        var pending: Pair<Double, Command>? = null
        var nextStep = world.stepLength / speed
        var nextReading = 0.5
        var firstWithin: Double? = null
        val obs = ArrayList<Observation>()
        while (t < limit) {
            val appYaw = trueHeading + drift * t
            pending?.let { (at, c) -> if (t >= at) { command = c; pending = null } }
            val horizontal = (world.router - truePos).length
            if (horizontal <= 2.0 && firstWithin == null) firstWithin = t
            // Turn toward the commanded heading (app frame), at most 120°/s.
            val want = command.heading?.let { it - drift * t }
            var aligned = true
            if (want != null) {
                val diff = DirectionSweep.signedDifference(trueHeading, want)
                val turn = diff.coerceIn(-120 * dt, 120 * dt)
                trueHeading += turn
                aligned = abs(diff) < 30
            }
            if (command.walk && aligned && t >= nextStep) {
                val d = MapPoint.direction(trueHeading + 5 * gauss(r))
                truePos = MapPoint(truePos.x + d.x * world.stepLength, truePos.y + d.y * world.stepLength)
                val a = MapPoint.direction(appYaw)
                appPos = MapPoint(appPos.x + a.x * 0.7, appPos.y + a.y * 0.7)
                walked += 0.7
                nextStep = t + world.stepLength / speed
            } else if (!command.walk || !aligned) {
                nextStep = max(nextStep, t + 0.3)
            }
            if (t >= nextReading) {
                nextReading = t + interval + r.nextDouble(-0.1, 0.1)
                obs += Observation(appPos, appYaw, world.rssi(truePos, trueHeading, r), walked)
                val c = strategy.decide(obs, appPos, appYaw, walked, max(1.5, speed * interval), interval)
                if (c.stop) return Outcome(t, (world.router - truePos).length, firstWithin)
                pending = (t + 1.0) to c // reaction time
            }
            t += dt
        }
        return Outcome(null, null, firstWithin)
    }

    private fun report(name: String, outcomes: List<Outcome>) {
        val n = outcomes.size
        val good = outcomes.filter { it.stopTime != null && it.distanceAtStop!! <= 2.5 }
        val wrong = outcomes.count { it.stopTime != null && it.distanceAtStop!! > 2.5 }
        val timeout = outcomes.count { it.stopTime == null }
        val times = good.map { it.stopTime!! }.sorted()
        fun pct(q: Double) = if (times.isEmpty()) Double.NaN else times[((times.size - 1) * q).roundToInt()]
        val reach = outcomes.mapNotNull { it.firstWithin2m }.sorted()
        println(
            "%-26s stopped right %3d%%  wrong %3d%%  no stop %3d%% | time to stop: median %5.0f s, 80th %5.0f s | got within 2 m at some point %3d%% (median %3.0f s)"
                .format(name, 100 * good.size / n, 100 * wrong / n, 100 * timeout / n, pct(0.5), pct(0.8),
                    100 * reach.size / n, if (reach.isEmpty()) Double.NaN else reach[reach.size / 2]),
        )
    }

    @Test fun compareStrategies() {
        Assume.assumeTrue(System.getProperty("bench") == "true")
        val runs = (System.getProperty("runs") ?: "60").toInt()
        val seeds = (1..runs).toList()
        val intervals = (System.getProperty("intervals") ?: "3,1").split(",").map { it.toDouble() }
        val only = System.getProperty("only")?.split("|")
        for (interval in intervals) {
            println("--- one reading every ${interval.toInt()} s, $runs simulated searches ---")
            val strategies = listOf(
                "current (best cell)" to { Current() as Strategy },
                "robust, best cell" to { Search(Steer.BEST_CELL, 1.0) as Strategy },
                "robust, best cell, stop .6" to { Search(Steer.BEST_CELL, 1.0, stopAt = 0.6) as Strategy },
                "robust, closer" to { Search(Steer.CLOSER, 1.0) as Strategy },
                "robust, closer, stop .6" to { Search(Steer.CLOSER, 1.0, stopAt = 0.6) as Strategy },
                "robust, closer+info λ2" to { Search(Steer.CLOSER_INFORMED, 1.0, lambda = 2.0) as Strategy },
                "robust, closer+info λ6" to { Search(Steer.CLOSER_INFORMED, 1.0, lambda = 6.0) as Strategy },
                "robust, closer+info λ3" to { Search(Steer.CLOSER_INFORMED, 1.0, lambda = 3.0) as Strategy },
                "λ6, no drift allowance" to { Search(Steer.CLOSER_INFORMED, 1.0, lambda = 6.0, drift = 0.0) as Strategy },
                "λ6, drift 0.10/m" to { Search(Steer.CLOSER_INFORMED, 1.0, lambda = 6.0, drift = 0.10) as Strategy },
                "λ6, cell 0.5 m" to { Search(Steer.CLOSER_INFORMED, 0.5, lambda = 6.0) as Strategy },
            ).filter { (name, _) -> only == null || only.any { name == it } }
            for ((name, make) in strategies) {
                val start = System.nanoTime()
                val out = seeds.parallelStream().map { run(make(), it, interval) }.toList()
                report(name, out)
                println("    (%.0f s compute)".format((System.nanoTime() - start) / 1e9))
            }
        }
    }
}
