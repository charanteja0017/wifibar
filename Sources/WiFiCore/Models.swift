import Foundation

/// Radio band an access point is broadcasting on.
public enum WiFiBand: String, Codable, Sendable, CaseIterable {
    case ghz2_4 = "2.4 GHz"
    case ghz5 = "5 GHz"
    case ghz6 = "6 GHz"
    case unknown = "Unknown band"
}

/// Coarse security family, used to decide how to join a network.
public enum SecurityKind: String, Codable, Sendable {
    case open
    case enhancedOpen
    case wep
    case personal
    case enterprise
    case unknown

    /// Whether joining needs a password or credentials.
    public var isSecured: Bool {
        switch self {
        case .open, .enhancedOpen: false
        case .wep, .personal, .enterprise, .unknown: true
        }
    }
}

/// One radio (BSSID) seen in a scan.
public struct AccessPoint: Codable, Sendable, Hashable, Identifiable {
    /// The BSSID when available, otherwise a synthetic identifier.
    public let id: String
    public let ssid: String?
    public let bssid: String?
    public let rssi: Int
    public let noise: Int
    public let channel: Int
    public let band: WiFiBand
    /// Channel width in MHz, 0 when unknown.
    public let channelWidth: Int
    public let security: SecurityKind
    public let securityLabel: String

    public init(
        id: String? = nil,
        ssid: String?,
        bssid: String?,
        rssi: Int,
        noise: Int,
        channel: Int,
        band: WiFiBand,
        channelWidth: Int,
        security: SecurityKind,
        securityLabel: String
    ) {
        self.id = id ?? bssid ?? "\(ssid ?? "?")|\(band.rawValue)|\(channel)|\(rssi)"
        self.ssid = ssid
        self.bssid = bssid
        self.rssi = rssi
        self.noise = noise
        self.channel = channel
        self.band = band
        self.channelWidth = channelWidth
        self.security = security
        self.securityLabel = securityLabel
    }

    public var quality: SignalQuality { SignalQuality(rssi: rssi) }

    /// Signal-to-noise ratio in dB, or nil when the driver reports no noise floor.
    public var snr: Int? { noise < 0 ? rssi - noise : nil }

    /// "5 GHz · Ch 36 · 80 MHz"
    public var channelSummary: String {
        var parts = [band.rawValue, "Ch \(channel)"]
        if channelWidth > 0 { parts.append("\(channelWidth) MHz") }
        return parts.joined(separator: " · ")
    }
}

/// The link the Wi-Fi interface is currently associated with.
public struct LinkInfo: Codable, Sendable, Hashable {
    public let ssid: String?
    public let bssid: String?
    public let rssi: Int
    public let noise: Int
    /// Transmit rate in Mbps.
    public let transmitRate: Double
    public let channel: Int
    public let band: WiFiBand
    public let channelWidth: Int
    public let security: SecurityKind
    public let securityLabel: String
    /// "802.11ax", "802.11be", …
    public let phyMode: String

    public init(
        ssid: String?,
        bssid: String?,
        rssi: Int,
        noise: Int,
        transmitRate: Double,
        channel: Int,
        band: WiFiBand,
        channelWidth: Int,
        security: SecurityKind,
        securityLabel: String,
        phyMode: String
    ) {
        self.ssid = ssid
        self.bssid = bssid
        self.rssi = rssi
        self.noise = noise
        self.transmitRate = transmitRate
        self.channel = channel
        self.band = band
        self.channelWidth = channelWidth
        self.security = security
        self.securityLabel = securityLabel
        self.phyMode = phyMode
    }

    public var quality: SignalQuality { SignalQuality(rssi: rssi) }
    public var snr: Int? { noise < 0 ? rssi - noise : nil }
}

/// All access points that broadcast the same network name.
public struct NetworkGroup: Codable, Sendable, Hashable, Identifiable {
    public var id: String { ssid }
    public let ssid: String
    /// Strongest first. Never empty.
    public let accessPoints: [AccessPoint]
    public let isKnown: Bool
    /// BSSID of the access point this Mac is associated with, if it belongs to this group.
    public let currentBSSID: String?
    /// True when this Mac is joined to this network name (even if the joined BSSID wasn't in the scan).
    public let isCurrent: Bool

    public init(ssid: String, accessPoints: [AccessPoint], isKnown: Bool, currentBSSID: String?, isCurrent: Bool) {
        precondition(!accessPoints.isEmpty, "A network group needs at least one access point")
        self.ssid = ssid
        self.accessPoints = accessPoints
        self.isKnown = isKnown
        self.currentBSSID = currentBSSID
        self.isCurrent = isCurrent
    }

    public var strongest: AccessPoint { accessPoints[0] }
    public var security: SecurityKind { strongest.security }
    public var bands: [WiFiBand] {
        WiFiBand.allCases.filter { band in accessPoints.contains { $0.band == band } }
    }

    public func isCurrent(_ ap: AccessPoint) -> Bool {
        guard let currentBSSID, let bssid = ap.bssid else { return false }
        return BSSID.normalize(bssid) == BSSID.normalize(currentBSSID)
    }
}

/// Everything the UI (and the widget) needs to draw one frame.
public struct WiFiSnapshot: Codable, Sendable, Hashable {
    public var date: Date
    public var interfaceName: String?
    public var powerOn: Bool
    public var locationAuthorized: Bool
    public var link: LinkInfo?
    public var groups: [NetworkGroup]
    /// Count of networks that don't broadcast a name.
    public var hiddenCount: Int
    /// Token the widget must put into `wifibar://` links. See `DeepLink`.
    public var linkToken: String?

    public init(
        date: Date = .now,
        interfaceName: String? = nil,
        powerOn: Bool = false,
        locationAuthorized: Bool = false,
        link: LinkInfo? = nil,
        groups: [NetworkGroup] = [],
        hiddenCount: Int = 0,
        linkToken: String? = nil
    ) {
        self.date = date
        self.interfaceName = interfaceName
        self.powerOn = powerOn
        self.locationAuthorized = locationAuthorized
        self.link = link
        self.groups = groups
        self.hiddenCount = hiddenCount
        self.linkToken = linkToken
    }

    public static let empty = WiFiSnapshot()
}

public enum BSSID {
    /// Lowercases and zero-pads each octet so "A:B:C:D:E:F" and "0a:0b:0c:0d:0e:0f" compare equal.
    public static func normalize(_ bssid: String) -> String {
        bssid.split(separator: ":")
            .map { $0.count == 1 ? "0" + $0.lowercased() : $0.lowercased() }
            .joined(separator: ":")
    }
}
