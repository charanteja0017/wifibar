package com.charan.wifibar.locate

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records a "Walk to find" session as CSV so real walks can be replayed in tests and used for tuning.
 * App-private and excluded from backup. Read it with:
 * `adb shell run-as com.charan.wifibar ls files/walks` and `... cat files/walks/<name>`.
 *
 * Rows: `t,kind,…` with t in seconds since the walk started (sensor clock):
 * - `acc,up,filtered`: acceleration along up (m/s²) and the step counter's filtered value
 * - `step,source,x,y,yaw`: source is `acc` (moves you on the map) or `android` (step detector, comparison only)
 * - `rssi,dbm,x,y,facing`
 */
class WalkRecorder(directory: File, private val startNanos: Long) {
    val file: File = File(directory, "walk-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.csv")
    private val out = run {
        directory.mkdirs()
        // Keep the newest 20 walks.
        directory.listFiles { f -> f.name.startsWith("walk-") }?.sortedByDescending { it.name }?.drop(19)?.forEach { it.delete() }
        file.bufferedWriter().apply { write("t,kind,a,b,c,d\n") }
    }
    private var closed = false

    private fun t(nanos: Long) = "%.3f".format(Locale.US, (nanos - startNanos) / 1e9)
    private fun f(v: Double) = "%.3f".format(Locale.US, v)

    fun acceleration(nanos: Long, up: Double, filtered: Double) = line("${t(nanos)},acc,${f(up)},${f(filtered)}")
    fun step(nanos: Long, fromAccelerometer: Boolean, x: Double, y: Double, yaw: Double) =
        line("${t(nanos)},step,${if (fromAccelerometer) "acc" else "android"},${f(x)},${f(y)},${f(yaw)}")

    fun reading(nanos: Long, rssi: Int, x: Double, y: Double, facing: Double) {
        line("${t(nanos)},rssi,$rssi,${f(x)},${f(y)},${f(facing)}")
        if (!closed) out.flush() // every ~3 s, so a killed app still leaves the walk on disk
    }

    private fun line(text: String) {
        if (closed) return
        out.write(text)
        out.write("\n")
    }

    fun close() {
        if (closed) return
        closed = true
        out.close()
    }
}
