@preconcurrency import CoreWLAN
import Foundation

enum WiFiEvent: Sendable {
    case power, network, linkQuality, scanCache
}

/// Turns CoreWLAN's delegate callbacks into an `AsyncStream`.
/// `CWWiFiClient.delegate` is weak, so whoever owns this object keeps it alive.
final class WiFiEvents: NSObject, CWEventDelegate, Sendable {
    let stream: AsyncStream<WiFiEvent>
    private let continuation: AsyncStream<WiFiEvent>.Continuation

    override init() {
        (stream, continuation) = AsyncStream.makeStream(bufferingPolicy: .bufferingNewest(16))
        super.init()
    }

    func start() {
        let client = CWWiFiClient.shared()
        client.delegate = self
        let types: [CWEventType] = [
            .powerDidChange, .ssidDidChange, .bssidDidChange, .linkDidChange,
            .linkQualityDidChange, .scanCacheUpdated,
        ]
        for type in types {
            do {
                try client.startMonitoringEvent(with: type)
            } catch {
                log.error("Can't monitor Wi-Fi event \(type.rawValue): \(error.localizedDescription, privacy: .public)")
            }
        }
    }

    func powerStateDidChangeForWiFiInterface(withName interfaceName: String) { continuation.yield(.power) }
    func ssidDidChangeForWiFiInterface(withName interfaceName: String) { continuation.yield(.network) }
    func bssidDidChangeForWiFiInterface(withName interfaceName: String) { continuation.yield(.network) }
    func linkDidChangeForWiFiInterface(withName interfaceName: String) { continuation.yield(.network) }
    func scanCacheUpdatedForWiFiInterface(withName interfaceName: String) { continuation.yield(.scanCache) }
    func linkQualityDidChangeForWiFiInterface(withName interfaceName: String, rssi: Int, transmitRate: Double) {
        continuation.yield(.linkQuality)
    }
}
