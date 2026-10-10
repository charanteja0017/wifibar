import Foundation

public struct SignalSample: Sendable, Equatable, Identifiable {
    public let date: Date
    public let rssi: Int
    public var id: Date { date }

    public init(date: Date, rssi: Int) {
        self.date = date
        self.rssi = rssi
    }
}

/// Whether you're walking toward or away from the access point.
public enum SignalTrend: Sendable, Equatable {
    case stronger
    case weaker
    case steady
    /// Not enough readings yet.
    case unknown
}

/// Keeps a short history of readings for one access point: its best reading and which way it's heading.
public struct SignalTracker: Sendable {
    public private(set) var samples: [SignalSample] = []
    public private(set) var peak: SignalSample?
    /// How much history to keep.
    public var window: TimeInterval
    /// Readings averaged on each side when judging the trend; single readings jump by several dB.
    public var trendSpan: Int
    /// Change in average (dB) that counts as stronger or weaker.
    public var trendThreshold: Double

    public init(window: TimeInterval = 120, trendSpan: Int = 3, trendThreshold: Double = 3) {
        self.window = window
        self.trendSpan = trendSpan
        self.trendThreshold = trendThreshold
    }

    /// Adds a reading. Returns true when it beats the best reading so far.
    @discardableResult
    public mutating func add(_ sample: SignalSample) -> Bool {
        samples.append(sample)
        samples.removeAll { sample.date.timeIntervalSince($0.date) > window }
        guard let peak, sample.rssi <= peak.rssi else {
            let isNewBest = self.peak != nil
            self.peak = sample
            return isNewBest
        }
        return false
    }

    public mutating func reset() {
        samples = []
        peak = nil
    }

    public var latest: SignalSample? { samples.last }

    public var trend: SignalTrend {
        guard samples.count >= trendSpan * 2 else { return .unknown }
        let recent = samples.suffix(trendSpan)
        let before = samples.dropLast(trendSpan).suffix(trendSpan)
        let delta = Self.mean(recent) - Self.mean(before)
        if delta >= trendThreshold { return .stronger }
        if delta <= -trendThreshold { return .weaker }
        return .steady
    }

    private static func mean(_ samples: some Collection<SignalSample>) -> Double {
        Double(samples.reduce(0) { $0 + $1.rssi }) / Double(samples.count)
    }
}
