import Foundation
import SPUMotion
import WiFiCore

/// Feeds the Mac's gyroscope and accelerometer into a `HeadingTracker`.
/// Readings arrive ~100 times a second on the main run loop, so the heading isn't observable
/// state; views poll `yaw` (e.g. from a `TimelineView`).
@MainActor
final class MotionSensor {
    private var handle: OpaquePointer?
    private(set) var tracker = HeadingTracker()

    var isRunning: Bool { handle != nil }
    var yaw: Double { tracker.yaw }

    /// Returns false when the sensor can't be opened (other Macs, or a future macOS).
    func start() -> Bool {
        guard handle == nil else { return true }
        handle = spu_motion_start(10_000, motionCallback, Unmanaged.passUnretained(self).toOpaque())
        if handle == nil { log.error("Motion sensor unavailable") }
        return handle != nil
    }

    func stop() {
        guard let handle else { return }
        spu_motion_stop(handle)
        self.handle = nil
    }

    func beginCalibration() { tracker.beginCalibration() }
    func endCalibration() { tracker.endCalibration() }

    fileprivate func receive(_ kind: SPUMotionKind, _ value: SIMD3<Double>, at time: Double) {
        switch kind {
        case SPUMotionKindRotation: tracker.addRotation(value, at: time)
        case SPUMotionKindAcceleration: tracker.addAcceleration(value)
        default: break
        }
    }
}

/// C callback; runs on the main run loop, where `start()` scheduled the sensor clients.
private let motionCallback: SPUMotionCallback = { context, kind, x, y, z, time in
    guard let context else { return }
    let sensor = Unmanaged<MotionSensor>.fromOpaque(context).takeUnretainedValue()
    MainActor.assumeIsolated {
        sensor.receive(kind, SIMD3(x, y, z), at: time)
    }
}
