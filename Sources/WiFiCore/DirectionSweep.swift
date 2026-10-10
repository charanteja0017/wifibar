import Foundation

/// Tracks how far the Mac has turned about the vertical axis, from gyroscope and accelerometer readings.
///
/// The gyroscope reports degrees per second in the Mac's own axes. Projecting that rotation onto
/// "up" (the opposite of gravity) gives the turn rate however the Mac is tilted. Turning left
/// (counter-clockwise seen from above) is positive.
public struct HeadingTracker: Sendable {
    /// Degrees turned since calibration ended.
    public private(set) var yaw: Double = 0
    public private(set) var isCalibrating = false
    private var gravity = SIMD3<Double>(0, 0, -1)
    private var bias = SIMD3<Double>(repeating: 0)
    private var biasSum = SIMD3<Double>(repeating: 0)
    private var biasCount = 0
    private var lastGyroTime: Double?

    public init() {}

    /// Accelerometer reading in g. Smoothed to find gravity.
    public mutating func addAcceleration(_ value: SIMD3<Double>) {
        gravity = gravity * 0.9 + value * 0.1
    }

    /// Gyroscope reading in degrees per second at `time` seconds.
    public mutating func addRotation(_ value: SIMD3<Double>, at time: Double) {
        defer { lastGyroTime = time }
        if isCalibrating {
            biasSum += value
            biasCount += 1
            return
        }
        guard let lastGyroTime else { return }
        let dt = time - lastGyroTime
        // Skip gaps (sleep, dropped events) rather than integrating a stale rate across them.
        guard dt > 0, dt < 0.1 else { return }
        let length = (gravity * gravity).sum().squareRoot()
        guard length > 0.5 else { return }
        let up = -gravity / length
        yaw += ((value - bias) * up).sum() * dt
    }

    /// Hold still: averages the gyroscope's resting offset.
    public mutating func beginCalibration() {
        isCalibrating = true
        biasSum = .zero
        biasCount = 0
    }

    public mutating func endCalibration() {
        if biasCount > 0 { bias = biasSum / Double(biasCount) }
        isCalibrating = false
        yaw = 0
    }
}

/// Signal readings taken while turning on the spot, and where they peak.
public struct DirectionSweep: Sendable {
    public struct Reading: Sendable, Equatable {
        /// Raw degrees turned when the reading was taken (left positive).
        public let heading: Double
        public let rssi: Int
    }

    public struct Result: Sendable, Equatable {
        /// Strongest direction, in degrees left of where the sweep started (0..<360).
        public let bearing: Double
        /// dB between the strongest and weakest directions.
        public let spread: Double
        /// Smoothed signal around the circle: `profile[i]` is at `i * profileStep` degrees.
        public let profile: [Double]
        public let profileStep: Double
        /// Multiplier that maps raw headings onto a true 360° circle.
        public let scale: Double

        /// Under ~4 dB the peak is within normal fluctuation.
        public var isClear: Bool { spread >= 4 }
    }

    public enum Failure: Error, Equatable {
        case tooFewReadings(Int)
        case turnTooSmall(Double)
        case turnTooLarge(Double)
    }

    public private(set) var readings: [Reading] = []

    public init() {}

    public mutating func add(heading: Double, rssi: Int) {
        readings.append(Reading(heading: heading, rssi: rssi))
    }

    /// `totalTurn` is the raw yaw measured when the person said they were facing the start again.
    /// Treating that as exactly 360° cancels gyroscope scale error and slow drift.
    public func result(totalTurn: Double) throws(Failure) -> Result {
        let turn = abs(totalTurn)
        guard turn >= 250 else { throw .turnTooSmall(turn) }
        guard turn <= 540 else { throw .turnTooLarge(turn) }
        guard readings.count >= 12 else { throw .tooFewReadings(readings.count) }

        let scale = 360 / turn
        let points = readings.map { (angle: Self.normalized($0.heading * scale), rssi: Double($0.rssi)) }

        // Circular Gaussian smoothing: a single noisy reading can't make a peak on its own.
        let step = 5.0
        let width = 25.0
        let profile = stride(from: 0.0, to: 360, by: step).map { center in
            var weighted = 0.0
            var total = 0.0
            for point in points {
                let distance = Self.angularDistance(point.angle, center)
                let weight = exp(-0.5 * (distance / width) * (distance / width))
                weighted += weight * point.rssi
                total += weight
            }
            return total > 0 ? weighted / total : -100
        }
        let best = profile.indices.max { profile[$0] < profile[$1] }!
        return Result(
            bearing: Double(best) * step,
            spread: profile.max()! - profile.min()!,
            profile: profile,
            profileStep: step,
            scale: scale
        )
    }

    /// 0..<360
    public static func normalized(_ angle: Double) -> Double {
        let value = angle.truncatingRemainder(dividingBy: 360)
        return value < 0 ? value + 360 : value
    }

    /// −180..<180: positive means turn left.
    public static func signedDifference(from current: Double, to target: Double) -> Double {
        let difference = normalized(target - current)
        return difference >= 180 ? difference - 360 : difference
    }

    static func angularDistance(_ a: Double, _ b: Double) -> Double {
        abs(signedDifference(from: a, to: b))
    }
}
