package com.charan.wifibar.core

/**
 * Learns how often Android refreshes the connected network's signal, from the times the value changes,
 * so an unchanged value is counted once per refresh and not once per look. Android's default is every
 * 3 s; FastPoll can make it faster, and nothing tells apps which is in effect.
 */
class PollRate(private val default: Long = 3_000, private val fastest: Long = 300) {
    private val gaps = ArrayDeque<Long>()
    private var lastChange: Long? = null

    /** Milliseconds between refreshes: the 20th percentile of recent gaps between changes. */
    val interval: Long
        get() = if (gaps.size < 4) default else gaps.sorted()[gaps.size / 5].coerceIn(fastest, default)

    /** Call when the reported signal changed, at [atMillis] (monotonic clock). */
    fun changed(atMillis: Long) {
        lastChange?.let {
            gaps.addLast(atMillis - it)
            if (gaps.size > 15) gaps.removeFirst()
        }
        lastChange = atMillis
    }
}
