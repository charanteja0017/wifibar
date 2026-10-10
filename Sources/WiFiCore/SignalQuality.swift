import Foundation

/// Signal quality bucket for an RSSI reading, matching the 3-bar Wi-Fi glyph macOS uses.
public enum SignalQuality: Int, Codable, Sendable, CaseIterable, Comparable {
    case weak = 0
    case fair = 1
    case good = 2
    case excellent = 3

    public init(rssi: Int) {
        switch rssi {
        case (-55)...: self = .excellent
        case (-67)...(-56): self = .good
        case (-77)...(-68): self = .fair
        default: self = .weak
        }
    }

    public static func < (lhs: Self, rhs: Self) -> Bool { lhs.rawValue < rhs.rawValue }

    /// Number of lit bars, 0–3.
    public var bars: Int { rawValue }

    public var label: String {
        switch self {
        case .excellent: "Excellent"
        case .good: "Good"
        case .fair: "Fair"
        case .weak: "Weak"
        }
    }

    /// Value for `Image(systemName: "wifi", variableValue:)`, lighting one layer per bar.
    /// Measured on macOS 27: the dot lights above 0, the inner arc from ~0.34, the outer arc above ~0.67.
    public var symbolVariableValue: Double {
        switch self {
        case .excellent: 1.0
        case .good: 0.5
        case .fair: 0.2
        case .weak: 0.0
        }
    }

    /// Position of an RSSI reading on a −90…−30 dBm scale, for meters.
    public static func meterFraction(rssi: Int) -> Double {
        let clamped = min(max(Double(rssi), -90), -30)
        return (clamped + 90) / 60
    }
}
