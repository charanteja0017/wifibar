import AppKit
import Observation
import WiFiCore

/// The access point being located.
struct LocateTarget: Hashable, Sendable {
    var ssid: String
    var bssid: String
    var band: WiFiBand
    var channel: Int
    var channelWidth: Int

    init(ssid: String, accessPoint: AccessPoint) {
        self.ssid = ssid
        bssid = accessPoint.bssid ?? ""
        band = accessPoint.band
        channel = accessPoint.channel
        channelWidth = accessPoint.channelWidth
    }

    init?(link: LinkInfo) {
        guard let ssid = link.ssid, let bssid = link.bssid else { return nil }
        self.ssid = ssid
        self.bssid = bssid
        band = link.band
        channel = link.channel
        channelWidth = link.channelWidth
    }

    var channelSummary: String {
        var parts = [band.rawValue, "Ch \(channel)"]
        if channelWidth > 0 { parts.append("\(channelWidth) MHz") }
        return parts.joined(separator: " · ")
    }
}

/// Live readings for one access point while you walk around looking for it.
@MainActor @Observable
final class LocatorModel {
    enum MapState: Equatable {
        case idle
        case loading
        case found(WiGLENetwork)
        case notFound
        case failed(String)
    }

    let target: LocateTarget
    private(set) var tracker = SignalTracker()
    private(set) var isLinkReading = false
    /// The access point wasn't in the latest scan.
    private(set) var isMissing = false
    private(set) var errorMessage: String?
    var environment: SignalEnvironment {
        didSet { UserDefaults.standard.set(environment.rawValue, forKey: "locatorEnvironment") }
    }
    var playsSound: Bool {
        didSet { UserDefaults.standard.set(playsSound, forKey: "locatorPlaysSound") }
    }
    private(set) var mapState = MapState.idle
    private(set) var credentials = WiGLEKeychain.load()

    enum SweepPhase: Equatable {
        case idle
        /// Standing still while the gyroscope's resting offset is measured.
        case calibrating
        case turning
        case done(DirectionSweep.Result)
        case failed(String)
    }

    private(set) var sweepPhase = SweepPhase.idle
    private(set) var sweep = DirectionSweep()
    /// Raw degrees turned so far, refreshed with each signal reading during a sweep.
    private(set) var turned = 0.0
    @ObservationIgnored let motion = MotionSensor()
    @ObservationIgnored private var sweepScale = 1.0

    @ObservationIgnored private let service: WiFiService
    @ObservationIgnored private var samplingTask: Task<Void, Never>?
    @ObservationIgnored private var lastSound = Date.distantPast

    init(target: LocateTarget, service: WiFiService) {
        self.target = target
        self.service = service
        let defaults = UserDefaults.standard
        environment = defaults.string(forKey: "locatorEnvironment").flatMap(SignalEnvironment.init) ?? .indoors
        playsSound = defaults.bool(forKey: "locatorPlaysSound")
    }

    var latestRSSI: Int? { tracker.latest?.rssi }

    var estimate: DistanceEstimate? {
        latestRSSI.map { DistanceEstimator.estimate(rssi: $0, band: target.band, environment: environment) }
    }

    // MARK: - Sampling

    func start() {
        guard samplingTask == nil else { return }
        log.notice("Locating \(self.target.ssid, privacy: .public) \(self.target.bssid, privacy: .public)")
        samplingTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let interval = await self?.sampleOnce() else { return }
                try? await Task.sleep(for: interval)
            }
        }
    }

    func stop() {
        samplingTask?.cancel()
        samplingTask = nil
        motion.stop()
    }

    func resetHistory() {
        tracker.reset()
    }

    /// Takes one reading; returns how long to wait before the next.
    private func sampleOnce() async -> Duration {
        do {
            let reading = try await service.readSignal(ssid: target.ssid, bssid: target.bssid)
            errorMessage = nil
            isLinkReading = reading.isLinkReading
            // Link readings arrive every second, so average three; fresh scans arrive every 10–15 s,
            // so compare single readings with a wider margin for their noise.
            tracker.trendSpan = reading.isLinkReading ? 3 : 1
            tracker.trendThreshold = reading.isLinkReading ? 3 : 4
            // A cached scan repeats the last value; recording it would draw a fake flat line.
            // The first reading is kept anyway so the window isn't empty while waiting for a real scan.
            guard reading.isFresh || tracker.samples.isEmpty else { return .seconds(2) }
            if let rssi = reading.rssi {
                isMissing = false
                if tracker.add(SignalSample(date: .now, rssi: rssi)) { playNewBestSound() }
                if sweepPhase == .turning, reading.isLinkReading {
                    turned = motion.yaw
                    sweep.add(heading: turned, rssi: rssi)
                }
            } else {
                isMissing = true
            }
            if sweepPhase == .calibrating || sweepPhase == .turning { return .milliseconds(150) }
            // Link readings are cheap; scans take the radio off-channel, so space them out.
            return reading.isLinkReading ? .seconds(1) : .seconds(2)
        } catch {
            errorMessage = error.localizedDescription
            return .seconds(3)
        }
    }

    // MARK: - Direction sweep

    /// Turn on the spot while the gyroscope tracks the angle; the signal peaks facing the router.
    /// Needs the joined access point, whose signal updates several times a second.
    func startSweep() {
        guard isLinkReading else { return }
        guard motion.start() else {
            sweepPhase = .failed("This Mac’s motion sensor couldn’t be read, so the app can’t tell how far you’ve turned.")
            return
        }
        sweep = DirectionSweep()
        turned = 0
        motion.beginCalibration()
        sweepPhase = .calibrating
        log.notice("Direction sweep started")
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard let self, sweepPhase == .calibrating else { return }
            motion.endCalibration()
            sweepPhase = .turning
        }
    }

    /// Called when the person faces where they started.
    func finishSweep() {
        guard sweepPhase == .turning else { return }
        let total = motion.yaw
        do {
            let result = try sweep.result(totalTurn: total)
            sweepScale = result.scale
            sweepPhase = .done(result)
            log.notice("Direction sweep: turn \(Int(total))°, \(self.sweep.readings.count) readings, best \(Int(result.bearing))°, spread \(result.spread, format: .fixed(precision: 1)) dB")
        } catch {
            log.notice("Direction sweep failed: turn \(Int(total))°, \(self.sweep.readings.count) readings, \(String(describing: error), privacy: .public)")
            motion.stop()
            sweepPhase = .failed(Self.message(for: error))
        }
    }

    func cancelSweep() {
        if sweepPhase == .turning || sweepPhase == .calibrating {
            log.notice("Direction sweep cancelled after \(Int(self.motion.yaw))°")
        }
        motion.stop()
        sweepPhase = .idle
    }

    /// Degrees to turn to face the strongest direction from where you face now: positive = left.
    func turnToStrongest(_ result: DirectionSweep.Result) -> Double {
        DirectionSweep.signedDifference(from: motion.yaw * sweepScale, to: result.bearing)
    }

    private static func message(for failure: DirectionSweep.Failure) -> String {
        switch failure {
        case let .turnTooSmall(degrees):
            "You turned about \(Int(degrees))°. Make one full circle, ending where you started, then click Done."
        case let .turnTooLarge(degrees):
            "You turned about \(Int(degrees))°, more than one circle. Stop as soon as you face where you started."
        case .tooFewReadings:
            "Not enough signal readings were taken. Turn more slowly, taking about 30 seconds for the circle."
        }
    }

    private func playNewBestSound() {
        guard playsSound, Date.now.timeIntervalSince(lastSound) > 0.7 else { return }
        lastSound = .now
        NSSound(named: "Tink")?.play()
    }

    // MARK: - WiGLE

    func saveCredentials(name: String, token: String) throws {
        let credentials = WiGLECredentials(
            name: name.trimmingCharacters(in: .whitespacesAndNewlines),
            token: token.trimmingCharacters(in: .whitespacesAndNewlines)
        )
        try WiGLEKeychain.save(credentials)
        self.credentials = credentials
    }

    func forgetCredentials() {
        WiGLEKeychain.delete()
        credentials = nil
        mapState = .idle
    }

    func lookUpOnWiGLE() {
        guard let credentials, mapState != .loading else { return }
        mapState = .loading
        let bssid = target.bssid
        Task {
            do {
                let result = try await WiGLEClient(credentials: credentials).lookUp(bssid: bssid)
                mapState = result.map(MapState.found) ?? .notFound
            } catch {
                mapState = .failed(error.localizedDescription)
            }
        }
    }
}
