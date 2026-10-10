@preconcurrency import CoreWLAN
import WiFiCore

extension WiFiBand {
    init(_ band: CWChannelBand?) {
        switch band {
        case .band2GHz: self = .ghz2_4
        case .band5GHz: self = .ghz5
        case .band6GHz: self = .ghz6
        default: self = .unknown
        }
    }
}

extension CWChannelWidth {
    var megahertz: Int {
        switch self {
        case .width20MHz: 20
        case .width40MHz: 40
        case .width80MHz: 80
        case .width160MHz: 160
        default: 0
        }
    }
}

extension CWPHYMode {
    var label: String {
        switch self {
        case .mode11a: "802.11a"
        case .mode11b: "802.11b"
        case .mode11g: "802.11g"
        case .mode11n: "802.11n"
        case .mode11ac: "802.11ac"
        case .mode11ax: "802.11ax"
        case .mode11be: "802.11be"
        default: "—"
        }
    }
}

enum SecurityMapping {
    /// Most specific first: transition and mixed modes also report support for their parts.
    private static let order: [(CWSecurity, SecurityKind, String)] = [
        (.wpa3Enterprise, .enterprise, "WPA3 Enterprise"),
        (.wpa2Enterprise, .enterprise, "WPA2 Enterprise"),
        (.wpaEnterpriseMixed, .enterprise, "WPA/WPA2 Enterprise"),
        (.wpaEnterprise, .enterprise, "WPA Enterprise"),
        (.enterprise, .enterprise, "Enterprise"),
        (.dynamicWEP, .enterprise, "802.1X WEP"),
        (.wpa3Transition, .personal, "WPA2/WPA3 Personal"),
        (.wpa3Personal, .personal, "WPA3 Personal"),
        (.wpa2Personal, .personal, "WPA2 Personal"),
        (.wpaPersonalMixed, .personal, "WPA/WPA2 Personal"),
        (.wpaPersonal, .personal, "WPA Personal"),
        (.personal, .personal, "Personal"),
        (.WEP, .wep, "WEP"),
        (.oweTransition, .enhancedOpen, "Enhanced Open"),
        (.OWE, .enhancedOpen, "Enhanced Open"),
        (.none, .open, "Open"),
    ]

    static func describe(_ network: CWNetwork) -> (kind: SecurityKind, label: String) {
        for (security, kind, label) in order where network.supportsSecurity(security) {
            return (kind, label)
        }
        return (.unknown, "Unknown")
    }

    static func describe(_ security: CWSecurity) -> (kind: SecurityKind, label: String) {
        order.first { $0.0 == security }.map { ($0.1, $0.2) } ?? (.unknown, "Unknown")
    }
}

extension AccessPoint {
    init(_ network: CWNetwork) {
        let channel = network.wlanChannel
        let security = SecurityMapping.describe(network)
        self.init(
            ssid: network.ssid,
            bssid: network.bssid.map(BSSID.normalize),
            rssi: network.rssiValue,
            noise: network.noiseMeasurement,
            channel: channel?.channelNumber ?? 0,
            band: WiFiBand(channel?.channelBand),
            channelWidth: channel?.channelWidth.megahertz ?? 0,
            security: security.kind,
            securityLabel: security.label
        )
    }
}
