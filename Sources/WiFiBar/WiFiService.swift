@preconcurrency import CoreWLAN
import Foundation
import os
import WiFiCore

let log = Logger(subsystem: "com.charan.wifibar", category: "wifi")

enum WiFiError: LocalizedError {
    case noInterface
    case accessPointOutOfRange(ssid: String)
    case networkOutOfRange(ssid: String)
    /// Saved credentials didn't work (or there were none); the user has to type them.
    case needsCredentials(enterprise: Bool, reason: String?)

    var errorDescription: String? {
        switch self {
        case .noInterface:
            "This Mac has no Wi-Fi interface."
        case let .accessPointOutOfRange(ssid):
            "That access point for “\(ssid)” is no longer in range."
        case let .networkOutOfRange(ssid):
            "“\(ssid)” is no longer in range."
        case let .needsCredentials(enterprise, _):
            enterprise ? "Enter your username and password." : "Enter the network password."
        }
    }
}

struct Credentials: Sendable {
    var username: String?
    var password: String
}

/// One read of the Wi-Fi interface: link, power and the access points around it.
struct WiFiReading: Sendable {
    var interfaceName: String?
    var powerOn: Bool
    var link: LinkInfo?
    var accessPoints: [AccessPoint]
    var knownSSIDs: Set<String>
    var didFullScan: Bool
    var scanError: String?
}

struct LocateReading: Sendable {
    /// Nil when the access point wasn't in the scan.
    var rssi: Int?
    /// True when read from the current connection rather than a scan.
    var isLinkReading: Bool
    /// False when macOS answered a scan from its cache instead of listening on the air.
    var isFresh: Bool
}

/// Owns all CoreWLAN calls. Scanning and joining block for seconds, so the actor runs on its own
/// serial queue instead of the shared cooperative pool.
actor WiFiService {
    private let queue = DispatchSerialQueue(label: "com.charan.wifibar.corewlan", qos: .userInitiated)
    nonisolated var unownedExecutor: UnownedSerialExecutor { queue.asUnownedSerialExecutor() }

    private let client = CWWiFiClient.shared()
    /// Last full scan, keyed by AP id. Cached reads refresh it; they never shrink it, because macOS
    /// empties its scan cache between scans.
    private var lastScan: [String: AccessPoint] = [:]

    private var interface: CWInterface? { client.interface() }

    func read(fullScan: Bool) -> WiFiReading {
        guard let interface else {
            return WiFiReading(powerOn: false, accessPoints: [], knownSSIDs: [], didFullScan: false)
        }
        let powerOn = interface.powerOn()
        var reading = WiFiReading(
            interfaceName: interface.interfaceName,
            powerOn: powerOn,
            link: powerOn ? Self.link(of: interface) : nil,
            accessPoints: [],
            knownSSIDs: Self.knownSSIDs(of: interface),
            didFullScan: false
        )
        guard powerOn else {
            lastScan = [:]
            return reading
        }

        if fullScan {
            do {
                let found = try interface.scanForNetworks(withName: nil, includeHidden: true).map(AccessPoint.init)
                lastScan = Dictionary(found.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
                reading.didFullScan = true
                log.notice("Full scan: \(found.count) access points, \(found.filter { $0.ssid != nil }.count) named")
            } catch {
                // The driver refuses back-to-back scans ("busy"); keep the previous results.
                reading.scanError = error.localizedDescription
                log.error("Scan failed: \(error.localizedDescription, privacy: .public)")
            }
        }
        for ap in (interface.cachedScanResults() ?? []).map(AccessPoint.init) {
            lastScan[ap.id] = ap
        }
        reading.accessPoints = Array(lastScan.values)
        return reading
    }

    /// One reading of a single access point, for the locator.
    /// The joined AP is read from the link (cheap, ~every second); any other AP needs a scan for its name.
    func readSignal(ssid: String, bssid: String) throws -> LocateReading {
        guard let interface else { throw WiFiError.noInterface }
        let wanted = BSSID.normalize(bssid)
        if let current = interface.bssid(), BSSID.normalize(current) == wanted {
            return LocateReading(rssi: interface.rssiValue(), isLinkReading: true, isFresh: true)
        }
        // macOS throttles scans: most return its cache in ~30 ms, and a real scan (1–3 s) happens about
        // every 10–15 s. Only real scans carry a new reading.
        let started = ContinuousClock.now
        let found = try interface.scanForNetworks(withName: ssid, includeHidden: false).map(AccessPoint.init)
        let isFresh = ContinuousClock.now - started >= .milliseconds(500)
        for ap in found { lastScan[ap.id] = ap }
        return LocateReading(rssi: found.first { $0.bssid == wanted }?.rssi, isLinkReading: false, isFresh: isFresh)
    }

    func setPower(_ on: Bool) throws {
        guard let interface else { throw WiFiError.noInterface }
        try interface.setPower(on)
    }

    /// Joins `ssid` on the access point `bssid` (or the strongest one), returning the BSSID joined.
    func join(ssid: String, bssid: String?, credentials: Credentials?) throws -> String? {
        guard let interface else { throw WiFiError.noInterface }
        let before = interface.bssid()

        // Always join a fresh CWNetwork: cached ones can be stale and the driver rejects them.
        let candidates = try interface.scanForNetworks(withName: ssid, includeHidden: false)
        let target: CWNetwork
        if let bssid {
            let wanted = BSSID.normalize(bssid)
            guard let match = candidates.first(where: { $0.bssid.map(BSSID.normalize) == wanted }) else {
                throw WiFiError.accessPointOutOfRange(ssid: ssid)
            }
            target = match
        } else {
            guard let strongest = candidates.max(by: { $0.rssiValue < $1.rssiValue }) else {
                throw WiFiError.networkOutOfRange(ssid: ssid)
            }
            target = strongest
        }

        let security = SecurityMapping.describe(target)
        log.notice("""
            Joining \(ssid, privacy: .public) bssid=\(target.bssid ?? "nil", privacy: .public) \
            rssi=\(target.rssiValue) security=\(security.label, privacy: .public) \
            from bssid=\(before ?? "nil", privacy: .public)
            """)

        switch security.kind {
        case .enterprise:
            try joinEnterprise(target, on: interface, credentials: credentials)
        case .open, .enhancedOpen:
            try interface.associate(to: target, password: nil)
        case .wep, .personal, .unknown:
            try joinPersonal(target, on: interface, credentials: credentials)
        }

        let after = interface.bssid()
        log.notice("Joined \(ssid, privacy: .public): bssid now \(after ?? "nil", privacy: .public)")
        return after
    }

    // MARK: - Joining

    /// Personal networks need the passphrase (CoreWLAN won't reuse the saved one; a nil password fails
    /// with tmpErr), so read it from the keychain — macOS asks the user to allow that — or ask for it.
    private func joinPersonal(_ network: CWNetwork, on interface: CWInterface, credentials: Credentials?) throws {
        if let credentials {
            try interface.associate(to: network, password: credentials.password)
            return
        }
        guard let ssidData = network.ssidData else {
            throw WiFiError.needsCredentials(enterprise: false, reason: nil)
        }
        switch Self.savedPassword(for: ssidData) {
        case let .found(saved):
            do {
                try interface.associate(to: network, password: saved)
            } catch {
                log.notice("Join with saved password failed: \(error.localizedDescription, privacy: .public)")
                throw WiFiError.needsCredentials(enterprise: false, reason: error.localizedDescription)
            }
        case .cancelled:
            throw WiFiError.needsCredentials(enterprise: false, reason: "Access to the saved password wasn’t allowed.")
        case .notFound:
            throw WiFiError.needsCredentials(enterprise: false, reason: nil)
        }
    }

    private enum SavedPassword {
        case found(String)
        case cancelled
        case notFound
    }

    private func joinEnterprise(_ network: CWNetwork, on interface: CWInterface, credentials: Credentials?) throws {
        if let credentials {
            try interface.associate(
                toEnterpriseNetwork: network, identity: nil,
                username: credentials.username, password: credentials.password
            )
            return
        }
        var lastError: Error?
        do {
            try interface.associate(toEnterpriseNetwork: network, identity: nil, username: nil, password: nil)
            return
        } catch {
            lastError = error
            log.notice("Enterprise join without credentials failed: \(error.localizedDescription, privacy: .public)")
        }
        if let ssidData = network.ssidData {
            let saved = Self.savedEnterpriseCredentials(for: ssidData)
            if saved.username != nil || saved.password != nil || saved.identity != nil {
                do {
                    try interface.associate(
                        toEnterpriseNetwork: network, identity: saved.identity,
                        username: saved.username, password: saved.password
                    )
                    return
                } catch {
                    lastError = error
                    log.notice("Enterprise join with saved credentials failed: \(error.localizedDescription, privacy: .public)")
                }
            }
        }
        throw WiFiError.needsCredentials(enterprise: true, reason: lastError?.localizedDescription)
    }

    // MARK: - Reading the interface

    private static func link(of interface: CWInterface) -> LinkInfo? {
        let rssi = interface.rssiValue()
        let rate = interface.transmitRate()
        // SSID/BSSID are nil without Location access, so detect association from the radio instead.
        guard rssi != 0 || rate > 0 || interface.bssid() != nil else { return nil }
        let channel = interface.wlanChannel()
        let security = SecurityMapping.describe(interface.security())
        return LinkInfo(
            ssid: interface.ssid(),
            bssid: interface.bssid().map(BSSID.normalize),
            rssi: rssi,
            noise: interface.noiseMeasurement(),
            transmitRate: rate,
            channel: channel?.channelNumber ?? 0,
            band: WiFiBand(channel?.channelBand),
            channelWidth: channel?.channelWidth.megahertz ?? 0,
            security: security.kind,
            securityLabel: security.label,
            phyMode: interface.activePHYMode().label
        )
    }

    private static func knownSSIDs(of interface: CWInterface) -> Set<String> {
        let profiles = interface.configuration()?.networkProfiles.array ?? []
        return Set(profiles.compactMap { ($0 as? CWNetworkProfile)?.ssid })
    }

    private static func savedPassword(for ssid: Data) -> SavedPassword {
        for domain in [CWKeychainDomain.user, .system] {
            var password: NSString?
            let status = CWKeychainFindWiFiPassword(domain, ssid, &password)
            log.notice("Keychain Wi-Fi password lookup (\(domain.rawValue)): status \(status)")
            if status == errSecSuccess, let password { return .found(password as String) }
            if status == errSecUserCanceled || status == errSecAuthFailed { return .cancelled }
        }
        return .notFound
    }

    private static func savedEnterpriseCredentials(for ssid: Data) -> (username: String?, password: String?, identity: SecIdentity?) {
        for domain in [CWKeychainDomain.user, .system] {
            var username: NSString?
            var password: NSString?
            let status = CWKeychainFindWiFiEAPUsernameAndPassword(domain, ssid, &username, &password)
            var identity: Unmanaged<SecIdentity>?
            let identityStatus = CWKeychainCopyWiFiEAPIdentity(domain, ssid, &identity)
            log.notice("Keychain EAP lookup (\(domain.rawValue)): credentials \(status), identity \(identityStatus)")
            let found = (
                username: status == errSecSuccess ? username as String? : nil,
                password: status == errSecSuccess ? password as String? : nil,
                identity: identityStatus == errSecSuccess ? identity?.takeRetainedValue() : nil
            )
            if found.username != nil || found.password != nil || found.identity != nil { return found }
        }
        return (nil, nil, nil)
    }
}
