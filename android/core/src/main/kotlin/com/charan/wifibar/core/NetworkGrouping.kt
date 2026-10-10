package com.charan.wifibar.core

object NetworkGrouping {
    /**
     * Groups access points by network name.
     *
     * - APs inside a group are sorted strongest first.
     * - Groups are ordered: the joined network first, then by strongest signal, then by name.
     * - Unnamed (hidden) APs are dropped; count them with [hiddenCount].
     */
    fun group(
        accessPoints: List<AccessPoint>,
        currentSsid: String?,
        currentBssid: String?,
        knownSsids: Set<String> = emptySet(),
    ): List<NetworkGroup> {
        val bySsid = LinkedHashMap<String, MutableList<AccessPoint>>()
        val seen = HashSet<String>()
        for (ap in accessPoints) {
            val ssid = ap.ssid?.takeIf { it.isNotEmpty() } ?: continue
            // Scans can report the same BSSID twice; keep the first.
            val key = ap.bssid?.let(Bssid::normalize) ?: ap.id
            if (!seen.add(key)) continue
            bySsid.getOrPut(ssid) { mutableListOf() }.add(ap)
        }

        val groups = bySsid.map { (ssid, aps) ->
            val sorted = aps.sortedWith(compareByDescending<AccessPoint> { it.rssi }.thenBy { it.id })
            val isCurrent = ssid == currentSsid
            NetworkGroup(
                ssid = ssid,
                accessPoints = sorted,
                isKnown = ssid in knownSsids || isCurrent,
                currentBssid = if (isCurrent) currentBssid else null,
                isCurrent = isCurrent,
            )
        }

        return groups.sortedWith(
            compareByDescending<NetworkGroup> { it.isCurrent }
                .thenByDescending { it.strongest.rssi }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.ssid },
        )
    }

    fun hiddenCount(accessPoints: List<AccessPoint>): Int = accessPoints.count { it.ssid.isNullOrEmpty() }
}
