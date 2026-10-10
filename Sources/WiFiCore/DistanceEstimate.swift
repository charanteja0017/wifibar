import Foundation

/// How cluttered the space between you and the access point is.
public enum SignalEnvironment: String, CaseIterable, Codable, Sendable {
    case indoors
    case openSpace

    public var label: String {
        switch self {
        case .indoors: "Indoors (walls)"
        case .openSpace: "Open space"
        }
    }

    /// Path-loss exponent n: 2 is free space; 2.7–3.5 is typical inside buildings.
    var exponent: Double {
        switch self {
        case .indoors: 3.0
        case .openSpace: 2.0
        }
    }

    var exponentRange: ClosedRange<Double> {
        switch self {
        case .indoors: 2.6...3.5
        case .openSpace: 1.8...2.3
        }
    }
}

/// A rough distance with the spread you'd get from typical variation in the path-loss exponent.
public struct DistanceEstimate: Sendable, Equatable {
    public let meters: Double
    public let low: Double
    public let high: Double
}

/// Log-distance path-loss model: d = 10^((RSSI₁ₘ − RSSI) / (10·n)).
/// It ignores the router's real transmit power, antennas and obstacles, so treat it as a guide.
public enum DistanceEstimator {
    /// Expected RSSI 1 m from a typical router: 20 dBm EIRP, minus free-space path loss at 1 m,
    /// minus ~10 dB for the client's antenna and body/case losses.
    public static func referenceRSSI(band: WiFiBand) -> Double {
        let megahertz: Double = switch band {
        case .ghz2_4, .unknown: 2437
        case .ghz5: 5500
        case .ghz6: 6000
        }
        let freeSpaceLossAt1m = 20 * log10(megahertz) - 27.55
        return 20 - freeSpaceLossAt1m - 10
    }

    public static func estimate(rssi: Int, band: WiFiBand, environment: SignalEnvironment) -> DistanceEstimate {
        let reference = referenceRSSI(band: band)
        func distance(_ exponent: Double) -> Double {
            max(0.3, pow(10, (reference - Double(rssi)) / (10 * exponent)))
        }
        let a = distance(environment.exponentRange.lowerBound)
        let b = distance(environment.exponentRange.upperBound)
        return DistanceEstimate(meters: distance(environment.exponent), low: min(a, b), high: max(a, b))
    }

    /// "≈ 6 m", "< 1 m", "≈ 35 m"
    public static func format(_ meters: Double) -> String {
        if meters < 1 { return "< 1 m" }
        if meters < 10 { return "≈ \(Int(meters.rounded())) m" }
        return "≈ \(Int((meters / 5).rounded() * 5)) m"
    }

    /// "4–8 m", or nil when the whole range is within a couple of metres (no useful spread to show).
    public static func formatRange(_ estimate: DistanceEstimate) -> String? {
        guard estimate.high >= 2 else { return nil }
        func short(_ meters: Double) -> String {
            meters < 10 ? String(Int(max(1, meters.rounded()))) : String(Int((meters / 5).rounded() * 5))
        }
        return "\(short(estimate.low))–\(short(estimate.high)) m"
    }
}
