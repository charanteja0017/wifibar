import Foundation
import Testing
@testable import WiFiCore

@Suite struct HeadingTrackerTests {
    /// Feeds `seconds` of a steady turn at 100 Hz, after calibrating with the gyro's resting offset.
    private func turn(
        rate: SIMD3<Double>,
        bias: SIMD3<Double>,
        gravity: SIMD3<Double>,
        seconds: Double
    ) -> Double {
        var tracker = HeadingTracker()
        for _ in 0..<50 { tracker.addAcceleration(gravity) }
        tracker.beginCalibration()
        var time = 0.0
        for _ in 0..<100 {
            tracker.addRotation(bias, at: time)
            time += 0.01
        }
        tracker.endCalibration()
        for _ in 0..<Int(seconds * 100) {
            tracker.addRotation(rate + bias, at: time)
            time += 0.01
        }
        return tracker.yaw
    }

    @Test func flatMacTurningLeftCountsUp() {
        // Measured on an M4 MacBook: flat on a desk the accelerometer reads (0, 0, −1).
        let yaw = turn(rate: [0, 0, 10], bias: [-0.18, 0.66, -0.12], gravity: [0, 0, -1], seconds: 9)
        #expect(abs(yaw - 90) < 0.5)
    }

    @Test func tiltedMacStillMeasuresTurnsAboutVertical() {
        // Lid side tilted 30°: "up" is no longer the Mac's z axis.
        let tilt = 30.0 * .pi / 180
        let gravity = SIMD3<Double>(0, -sin(tilt), -cos(tilt))
        let up = -gravity
        let yaw = turn(rate: up * 20, bias: [0.1, 0.5, 0], gravity: gravity, seconds: 4.5)
        #expect(abs(yaw - 90) < 0.5)
    }

    @Test func restingOffsetIsRemoved() {
        let yaw = turn(rate: .zero, bias: [0.3, 0.6, 0.9], gravity: [0, 0, -1], seconds: 60)
        #expect(abs(yaw) < 0.01)
    }

    @Test func gapsAreNotIntegrated() {
        var tracker = HeadingTracker()
        tracker.beginCalibration()
        tracker.endCalibration()
        tracker.addRotation([0, 0, 100], at: 0)
        tracker.addRotation([0, 0, 100], at: 5) // 5 s gap: Mac slept or events dropped
        #expect(tracker.yaw == 0)
    }
}

@Suite struct DirectionSweepTests {
    /// A sweep where the signal peaks `peak` degrees left of the start, read by a gyro that reads `scale` × too much.
    private func sweep(peak: Double, scale: Double = 1, clockwise: Bool = false, depth: Double = 8, readings: Int = 60) -> (DirectionSweep, Double) {
        var sweep = DirectionSweep()
        var generator = SeededGenerator(seed: 7)
        for index in 0..<readings {
            let trueAngle = Double(index) / Double(readings) * 360
            let signed = clockwise ? -trueAngle : trueAngle
            let rssi = -60 + depth * cos((DirectionSweep.normalized(signed) - peak) * .pi / 180)
                + Double.random(in: -2...2, using: &generator)
            sweep.add(heading: signed * scale, rssi: Int(rssi.rounded()))
        }
        return (sweep, (clockwise ? -360 : 360) * scale)
    }

    @Test(arguments: [0.0, 45, 120, 200, 310])
    func findsThePeak(peak: Double) throws {
        let (sweep, total) = sweep(peak: peak)
        let result = try sweep.result(totalTurn: total)
        #expect(DirectionSweep.angularDistance(result.bearing, peak) <= 10)
        #expect(result.isClear)
    }

    @Test func gyroScaleErrorIsCancelledByTheFullTurn() throws {
        // On the test Mac a full circle read as 421°.
        let (sweep, total) = sweep(peak: 120, scale: 421.0 / 360)
        let result = try sweep.result(totalTurn: total)
        #expect(DirectionSweep.angularDistance(result.bearing, 120) <= 10)
        #expect(abs(result.scale - 360 / 421) < 0.001)
    }

    @Test func turningRightWorksToo() throws {
        let (sweep, total) = sweep(peak: 300, clockwise: true)
        let result = try sweep.result(totalTurn: total)
        #expect(DirectionSweep.angularDistance(result.bearing, 300) <= 10)
    }

    @Test func flatSignalIsNotAClearDirection() throws {
        let (sweep, total) = sweep(peak: 90, depth: 0.5)
        #expect(try !sweep.result(totalTurn: total).isClear)
    }

    @Test func incompleteSweepsAreRejected() {
        let (sweep, _) = sweep(peak: 90)
        #expect(throws: DirectionSweep.Failure.turnTooSmall(180)) { try sweep.result(totalTurn: 180) }
        #expect(throws: DirectionSweep.Failure.turnTooLarge(720)) { try sweep.result(totalTurn: -720) }
        var short = DirectionSweep()
        short.add(heading: 0, rssi: -50)
        #expect(throws: DirectionSweep.Failure.tooFewReadings(1)) { try short.result(totalTurn: 360) }
    }

    @Test func turnDirections() {
        #expect(DirectionSweep.signedDifference(from: 10, to: 50) == 40)    // turn left 40°
        #expect(DirectionSweep.signedDifference(from: 10, to: 330) == -40)  // turn right 40°
        #expect(DirectionSweep.signedDifference(from: 350, to: 20) == 30)
        #expect(DirectionSweep.normalized(-90) == 270)
    }
}

/// Deterministic noise so tests can't flake.
private struct SeededGenerator: RandomNumberGenerator {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return state
    }
}
