package com.charan.wifibar.locate

/**
 * A real stop-and-hold sweep logged on the Xiaomi Pad 8 (2026-10-10 17:46–17:48): yaw degrees left of
 * the start, and RSSI. Strongest from straight ahead to 90° left. Used to demo the "Go this way" arrow.
 */
object SampleSweep {
    val at1746: List<Pair<Double, Int>> = listOf(
        0 to -40, 0 to -38, 0 to -42, 0 to -41, 51 to -44, 46 to -45, 48 to -38, 48 to -38,
        92 to -35, 92 to -33, 94 to -34, 88 to -46, 130 to -48, 136 to -54, 136 to -58, 136 to -58,
        180 to -45, 181 to -57, 181 to -47, 181 to -48, 223 to -56, 225 to -59, 225 to -60, 225 to -59,
        279 to -50, 270 to -53, 271 to -52, 270 to -50, 315 to -55, 315 to -52, 315 to -56, 315 to -56,
    ).map { (yaw, rssi) -> yaw.toDouble() to rssi }
}
