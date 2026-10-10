package com.charan.wifibar.core

import java.io.File
import kotlin.test.Test
import org.junit.Assume

/**
 * Replays real walks recorded on a Xiaomi Pad 8 (app's files/walks, copied to test resources).
 * Ground truth is approximate: where the walk got its strongest reading (≥ −30 dBm ≈ within a metre),
 * blurred by step-counting drift. Only readings from before that moment are used.
 */
class WalkReplay {
    data class Reading(val t: Double, val rssi: Int, val at: MapPoint, val facing: Double, val walked: Double)

    fun load(file: File): List<Reading> {
        val rows = file.readLines().drop(1).map { it.split(",") }
        var steps = 0
        val out = ArrayList<Reading>()
        for (r in rows) when (r[1]) {
            "step" -> if (r[2] == "acc") steps++
            "rssi" -> out += Reading(r[0].toDouble(), r[2].toInt(), MapPoint(r[3].toDouble(), r[4].toDouble()), r[5].toDouble(), steps * 0.7)
        }
        return out
    }

    @Test fun replay() {
        Assume.assumeTrue(System.getProperty("bench") == "true")
        val dir = File(javaClass.getResource("/walks")!!.toURI())
        for (file in dir.listFiles()!!.sortedBy { it.name }) {
            val readings = load(file)
            val strongest = readings.maxByOrNull { it.rssi } ?: continue
            if (strongest.rssi < -30) { println("${file.name}: never closer than ${strongest.rssi} dBm, skipped"); continue }
            val truth = strongest.at
            val before = readings.takeWhile { it.t < strongest.t }
            println("${file.name}: router ≈ (%.1f, %.1f) [%d dBm at %.0f s], %d readings before".format(truth.x, truth.y, strongest.rssi, strongest.t, before.size))
            val old = RouterFinder(-36.4)
            val new = RouterSearch(-36.4)
            val line = StringBuilder()
            for (k in 3..before.size) {
                val obs = before.take(k).map { Observation(it.at, it.facing, it.rssi, it.walked) }
                val here = before[k - 1].at
                val o = if (k >= 4) old.estimate(obs).position?.let { (it - truth).length } else null
                val belief = new.belief(obs, before[k - 1].walked)
                val n = (belief.mostLikely - truth).length
                val p = belief.within(here, 2.0)
                val dist = (here - truth).length
                line.append("  #%2d t=%3.0fs you %.1f m away (%d dBm) | old err %s | new err %4.1f ±%3.1f | P(≤2 m) %3.0f%%%s\n".format(
                    k, before[k - 1].t, dist, before[k - 1].rssi, o?.let { "%4.1f".format(it) } ?: "  - ", n, belief.spread, 100 * p,
                    if (p >= 0.8 && dist > 2.5) "  <-- would stop too far" else if (p >= 0.8) "  <-- stop" else ""))
            }
            print(line)
        }
    }
}
