import CoreLocation
import Observation

/// macOS only reveals Wi-Fi names and BSSIDs to apps with Location access.
@MainActor @Observable
final class LocationAuthorizer: NSObject, CLLocationManagerDelegate {
    private(set) var status: CLAuthorizationStatus
    @ObservationIgnored var onChange: (() -> Void)?
    @ObservationIgnored private let manager = CLLocationManager()

    override init() {
        status = manager.authorizationStatus
        super.init()
        manager.delegate = self
    }

    /// On macOS a granted "when in use" request reports `.authorizedAlways`.
    var isAuthorized: Bool { status == .authorizedAlways }
    var canAsk: Bool { status == .notDetermined }

    func request() {
        manager.requestWhenInUseAuthorization()
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        // The manager was created on the main thread, so it calls back there.
        MainActor.assumeIsolated {
            guard status != self.status else { return }
            log.notice("Location authorization changed to \(status.rawValue)")
            self.status = status
            self.onChange?()
        }
    }
}
