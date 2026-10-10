import Foundation

public enum NetworkGrouping {
    /// Groups access points by network name.
    ///
    /// - APs inside a group are sorted strongest first.
    /// - Groups are ordered: the joined network first, then by strongest signal, then by name.
    /// - Unnamed (hidden) APs are dropped; count them with `hiddenCount(_:)`.
    public static func group(
        _ accessPoints: [AccessPoint],
        currentSSID: String?,
        currentBSSID: String?,
        knownSSIDs: Set<String>
    ) -> [NetworkGroup] {
        var bySSID: [String: [AccessPoint]] = [:]
        var seen = Set<String>()
        for ap in accessPoints {
            guard let ssid = ap.ssid, !ssid.isEmpty else { continue }
            // Scans can report the same BSSID twice (cached + fresh); keep the first.
            let key = ap.bssid.map(BSSID.normalize) ?? ap.id
            guard seen.insert(key).inserted else { continue }
            bySSID[ssid, default: []].append(ap)
        }

        let groups = bySSID.map { ssid, aps in
            let sorted = aps.sorted { lhs, rhs in
                lhs.rssi != rhs.rssi ? lhs.rssi > rhs.rssi : lhs.id < rhs.id
            }
            let isCurrent = ssid == currentSSID
            return NetworkGroup(
                ssid: ssid,
                accessPoints: sorted,
                isKnown: knownSSIDs.contains(ssid) || isCurrent,
                currentBSSID: isCurrent ? currentBSSID : nil,
                isCurrent: isCurrent
            )
        }

        return groups.sorted { lhs, rhs in
            if lhs.isCurrent != rhs.isCurrent { return lhs.isCurrent }
            if lhs.strongest.rssi != rhs.strongest.rssi { return lhs.strongest.rssi > rhs.strongest.rssi }
            return lhs.ssid.localizedStandardCompare(rhs.ssid) == .orderedAscending
        }
    }

    public static func hiddenCount(_ accessPoints: [AccessPoint]) -> Int {
        accessPoints.filter { $0.ssid?.isEmpty ?? true }.count
    }
}
