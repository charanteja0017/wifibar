import AppKit
import Observation
import ServiceManagement
import WidgetKit
import WiFiCore
import WiFiWidgetUI

/// Which row a join was started from: a whole network (bssid nil = strongest AP) or one access point.
struct JoinTarget: Hashable, Sendable {
    var ssid: String
    var bssid: String?
}

struct JoinFailure: Equatable {
    var target: JoinTarget
    var message: String
}

struct CredentialPrompt: Equatable {
    var target: JoinTarget
    var enterprise: Bool
    var reason: String?
}

@MainActor @Observable
final class AppModel {
    private(set) var snapshot = WiFiSnapshot.empty
    private(set) var hasLoaded = false
    private(set) var isScanning = false
    private(set) var joining: JoinTarget?
    var joinFailure: JoinFailure?
    var credentialPrompt: CredentialPrompt?
    var expandedGroups: Set<String> = []
    private(set) var launchAtLogin = SMAppService.mainApp.status == .enabled
    private(set) var launchAtLoginError: String?

    /// Set by the panel controller; scanning speeds up while the panel is open.
    var isPanelVisible = false {
        didSet { if isPanelVisible != oldValue { restartCadence() } }
    }

    let location = LocationAuthorizer()
    @ObservationIgnored var onSnapshotChange: ((WiFiSnapshot) -> Void)?
    /// Asks the panel controller to show the panel (used by deep links).
    @ObservationIgnored var showPanel: (() -> Void)?
    /// Opens the window for finding an access point by its signal.
    @ObservationIgnored var openLocator: ((LocateTarget) -> Void)?

    /// Shared with the locator window so all CoreWLAN calls stay on one queue.
    @ObservationIgnored let service = WiFiService()
    @ObservationIgnored private let events = WiFiEvents()
    @ObservationIgnored private let store = SnapshotStore.applicationSupport
    @ObservationIgnored private var lastFullScan: Date?
    @ObservationIgnored private var cadenceTask: Task<Void, Never>?
    @ObservationIgnored private var eventTask: Task<Void, Never>?
    @ObservationIgnored private var debounceTask: Task<Void, Never>?
    @ObservationIgnored let linkToken: String
    @ObservationIgnored private var widgetSignature: [String] = []
    @ObservationIgnored private var lastWidgetReload = Date.distantPast

    private static let openCadence = Duration.seconds(15)
    private static let backgroundCadence = Duration.seconds(30)
    private static let backgroundFullScanInterval: TimeInterval = 5 * 60

    init() {
        let defaults = UserDefaults.standard
        if let token = defaults.string(forKey: "linkToken") {
            linkToken = token
        } else {
            linkToken = DeepLink.makeToken()
            defaults.set(linkToken, forKey: "linkToken")
        }
    }

    func start() {
        location.onChange = { [weak self] in
            Task {
                // Names appear in the cached results right away; the full scan takes seconds.
                await self?.refresh(fullScan: false)
                await self?.refresh(fullScan: true)
            }
        }
        if location.canAsk { location.request() }
        events.start()
        eventTask = Task { [events, weak self] in
            for await event in events.stream {
                self?.scheduleEventRefresh(event)
            }
        }
        restartCadence()
    }

    // MARK: - Refreshing

    func refresh(fullScan: Bool) async {
        // Several triggers (timer, Location change, Rescan) can land together; scans briefly take the radio off-channel.
        let recentlyScanned = lastFullScan.map { Date.now.timeIntervalSince($0) < 5 } ?? false
        let scan = fullScan && location.isAuthorized && !isScanning && !recentlyScanned
        if scan { isScanning = true }
        let reading = await service.read(fullScan: scan)
        if scan {
            isScanning = false
            if reading.didFullScan { lastFullScan = .now }
        }
        apply(reading)
    }

    func rescan() {
        Task { await refresh(fullScan: true) }
    }

    private func apply(_ reading: WiFiReading) {
        let link = reading.link
        let groups = NetworkGrouping.group(
            reading.accessPoints,
            currentSSID: link?.ssid,
            currentBSSID: link?.bssid,
            knownSSIDs: reading.knownSSIDs
        )
        snapshot = WiFiSnapshot(
            date: .now,
            interfaceName: reading.interfaceName,
            powerOn: reading.powerOn,
            locationAuthorized: location.isAuthorized,
            link: link,
            groups: groups,
            hiddenCount: NetworkGrouping.hiddenCount(reading.accessPoints),
            linkToken: linkToken
        )
        hasLoaded = true
        onSnapshotChange?(snapshot)
        do {
            try store.write(snapshot)
            reloadWidgetIfNeeded()
        } catch {
            log.error("Couldn't save snapshot: \(error.localizedDescription, privacy: .public)")
        }
    }

    /// Widget reloads are rationed by macOS, so ask only when what the widget shows has changed:
    /// right away for a different network or access point, otherwise at most once a minute.
    private func reloadWidgetIfNeeded() {
        let link = snapshot.link
        let network = [
            "\(snapshot.powerOn)", "\(snapshot.locationAuthorized)", link?.ssid ?? "-", link?.bssid ?? "-",
        ]
        let details = [
            "\((link?.rssi ?? 0) / 3)",
            snapshot.groups.prefix(8).map { "\($0.ssid):\($0.accessPoints.count):\($0.strongest.rssi / 5)" }.joined(separator: ","),
        ]
        let signature = network + details
        guard signature != widgetSignature else { return }
        let networkChanged = Array(widgetSignature.prefix(network.count)) != network
        guard networkChanged || Date.now.timeIntervalSince(lastWidgetReload) > 60 else { return }
        widgetSignature = signature
        lastWidgetReload = .now
        WidgetCenter.shared.reloadTimelines(ofKind: WiFiWidgetKind.signal)
    }

    private func restartCadence() {
        cadenceTask?.cancel()
        cadenceTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                let open = isPanelVisible
                let fullScanDue = lastFullScan.map { Date.now.timeIntervalSince($0) > Self.backgroundFullScanInterval } ?? true
                await refresh(fullScan: open || fullScanDue)
                try? await Task.sleep(for: open ? Self.openCadence : Self.backgroundCadence)
            }
        }
    }

    /// Link-quality events arrive every few seconds; coalesce them into one cheap (cached) read.
    private func scheduleEventRefresh(_ event: WiFiEvent) {
        debounceTask?.cancel()
        debounceTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(event == .linkQuality ? 1000 : 300))
            guard !Task.isCancelled else { return }
            await self?.refresh(fullScan: false)
        }
    }

    // MARK: - Actions

    func setPower(_ on: Bool) {
        Task {
            do {
                try await service.setPower(on)
            } catch {
                log.error("Couldn't turn Wi-Fi \(on ? "on" : "off"): \(error.localizedDescription, privacy: .public)")
            }
            await refresh(fullScan: on)
        }
    }

    func join(_ target: JoinTarget, credentials: Credentials? = nil) {
        guard joining == nil else { return }
        joining = target
        joinFailure = nil
        credentialPrompt = nil
        Task {
            do {
                let joined = try await service.join(ssid: target.ssid, bssid: target.bssid, credentials: credentials)
                // The driver may settle on another radio of the same network, usually when the chosen one is weak.
                if let wanted = target.bssid, let joined, BSSID.normalize(joined) != BSSID.normalize(wanted) {
                    joinFailure = JoinFailure(
                        target: target,
                        message: "macOS joined a different access point (\(BSSID.normalize(joined))). The one you chose may be too weak to hold a connection."
                    )
                }
            } catch let WiFiError.needsCredentials(enterprise, reason) {
                credentialPrompt = CredentialPrompt(target: target, enterprise: enterprise, reason: reason)
            } catch {
                log.error("Join failed: \(error.localizedDescription, privacy: .public)")
                joinFailure = JoinFailure(target: target, message: error.localizedDescription)
            }
            joining = nil
            await refresh(fullScan: false)
        }
    }

    func locate(_ target: LocateTarget) {
        openLocator?(target)
    }

    func cancelCredentials() {
        credentialPrompt = nil
    }

    func toggleExpanded(_ ssid: String) {
        if expandedGroups.contains(ssid) {
            expandedGroups.remove(ssid)
        } else {
            expandedGroups.insert(ssid)
        }
    }

    func setLaunchAtLogin(_ enabled: Bool) {
        do {
            if enabled {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
            launchAtLoginError = nil
        } catch {
            launchAtLoginError = error.localizedDescription
            log.error("Launch at login: \(error.localizedDescription, privacy: .public)")
        }
        launchAtLogin = SMAppService.mainApp.status == .enabled
    }

    func requestLocation() {
        if location.canAsk {
            location.request()
        } else {
            openSettings("x-apple.systempreferences:com.apple.preference.security?Privacy_LocationServices")
        }
    }

    func openWiFiSettings() {
        openSettings("x-apple.systempreferences:com.apple.wifi-settings-extension")
    }

    private func openSettings(_ string: String) {
        guard let url = URL(string: string) else { return }
        NSWorkspace.shared.open(url)
    }

    // MARK: - Deep links

    func handle(_ url: URL) {
        switch DeepLink.parse(url, expectedToken: linkToken) {
        case .success(.open):
            showPanel?()
        case let .success(.connect(ssid, bssid)):
            showPanel?()
            expandedGroups.insert(ssid)
            // Never let a link silently put this Mac on an unsecured network it hasn't joined before.
            guard let group = snapshot.groups.first(where: { $0.ssid == ssid }),
                  group.security.isSecured || group.isKnown
            else {
                log.notice("Deep link to unknown or open network \(ssid, privacy: .public): showing it instead of joining")
                return
            }
            join(JoinTarget(ssid: ssid, bssid: bssid))
        case let .failure(error):
            log.error("Rejected URL \(url.absoluteString, privacy: .public): \(String(describing: error), privacy: .public)")
        }
    }
}
