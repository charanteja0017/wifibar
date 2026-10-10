import SwiftUI
import WidgetKit
import WiFiCore

public enum WiFiWidgetKind {
    public static let signal = "WiFiBarSignal"
}

/// Desktop widget content. Shared with the app so debug builds can render it.
public struct WiFiWidgetView: View {
    let snapshot: WiFiSnapshot?
    let family: WidgetFamily
    let linksEnabled: Bool

    /// `linksEnabled: false` draws rows without `Link`, which `ImageRenderer` can't draw (debug previews).
    public init(snapshot: WiFiSnapshot?, family: WidgetFamily, linksEnabled: Bool = true) {
        self.snapshot = snapshot
        self.family = family
        self.linksEnabled = linksEnabled
    }

    public var body: some View {
        Group {
            if let snapshot {
                switch family {
                case .systemSmall: SmallLayout(snapshot: snapshot)
                case .systemMedium: MediumLayout(snapshot: snapshot)
                default: LargeLayout(snapshot: snapshot)
                }
            } else {
                MessageView(symbol: "wifi", title: "Open WiFiBar", detail: "The widget shows what the WiFiBar menu bar app sees.")
            }
        }
        .widgetURL(DeepLink.open.url(token: nil))
        .environment(\.widgetLinksEnabled, linksEnabled)
    }
}

// Written out by hand: the `@Entry` macro's plugin ships only with Xcode.
private struct WidgetLinksEnabledKey: EnvironmentKey {
    static let defaultValue = true
}

extension EnvironmentValues {
    fileprivate var widgetLinksEnabled: Bool {
        get { self[WidgetLinksEnabledKey.self] }
        set { self[WidgetLinksEnabledKey.self] = newValue }
    }
}

/// A `Link` in the widget; plain content in debug previews.
private struct WidgetLink<Label: View>: View {
    let destination: URL
    @ViewBuilder let label: Label
    @Environment(\.widgetLinksEnabled) private var enabled

    var body: some View {
        if enabled {
            Link(destination: destination) { label }
        } else {
            label
        }
    }
}

// MARK: - Layouts

private struct SmallLayout: View {
    let snapshot: WiFiSnapshot

    var body: some View {
        if let message = StatusMessage(snapshot) {
            MessageView(symbol: message.symbol, title: message.title, detail: message.detail)
        } else if let link = snapshot.link {
            CurrentLink(link: link, snapshot: snapshot, compact: true)
        }
    }
}

private struct MediumLayout: View {
    let snapshot: WiFiSnapshot

    var body: some View {
        if let message = StatusMessage(snapshot) {
            MessageView(symbol: message.symbol, title: message.title, detail: message.detail)
        } else {
            HStack(alignment: .top, spacing: 14) {
                if let link = snapshot.link {
                    CurrentLink(link: link, snapshot: snapshot, compact: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text("Nearby")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    ForEach(snapshot.groups.filter { !$0.isCurrent }.prefix(4)) { group in
                        NetworkLine(group: group, token: snapshot.linkToken)
                    }
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}

private struct LargeLayout: View {
    let snapshot: WiFiSnapshot

    /// A large widget is 344 × 344 pt (about 312 pt inside the margins): one header, then this many rows.
    private static let rowBudget = 14

    /// Filled group by group: the network, then up to three of its access points.
    private var rows: [(group: NetworkGroup, accessPoints: [AccessPoint])] {
        var remaining = Self.rowBudget
        var rows: [(NetworkGroup, [AccessPoint])] = []
        for group in snapshot.groups where remaining > 1 {
            let accessPoints = Array(group.accessPoints.prefix(min(3, remaining - 1)))
            rows.append((group, accessPoints))
            remaining -= 1 + accessPoints.count
        }
        return rows
    }

    var body: some View {
        if let message = StatusMessage(snapshot) {
            MessageView(symbol: message.symbol, title: message.title, detail: message.detail)
        } else {
            let rows = rows
            VStack(alignment: .leading, spacing: 8) {
                if let link = snapshot.link {
                    LargeHeader(link: link)
                }
                Divider()
                VStack(alignment: .leading, spacing: 3) {
                    ForEach(rows, id: \.group.id) { row in
                        NetworkLine(group: row.group, token: snapshot.linkToken)
                        ForEach(row.accessPoints) { ap in
                            AccessPointLine(group: row.group, accessPoint: ap, token: snapshot.linkToken)
                        }
                    }
                }
                Spacer(minLength: 0)
                HStack {
                    Text("Updated \(Text(snapshot.date, style: .relative)) ago")
                    Spacer()
                    let hidden = snapshot.groups.count - rows.count
                    if hidden > 0 {
                        Text(hidden == 1 ? "1 more network" : "\(hidden) more networks")
                    }
                }
                .font(.caption2)
                .foregroundStyle(.tertiary)
                .lineLimit(1)
            }
        }
    }
}

/// Current network on two lines, so the list below gets the room.
private struct LargeHeader: View {
    let link: LinkInfo

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            Image(systemName: "wifi", variableValue: link.quality.symbolVariableValue)
                .font(.title2.weight(.semibold))
                .foregroundStyle(.tint)
                .widgetAccentable()
            VStack(alignment: .leading, spacing: 1) {
                Text(link.ssid ?? "Wi-Fi")
                    .font(.headline)
                    .lineLimit(1)
                Text("\(link.band.rawValue) · Ch \(link.channel) · \(link.securityLabel)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            VStack(alignment: .trailing, spacing: 0) {
                HStack(alignment: .firstTextBaseline, spacing: 2) {
                    Text("\(link.rssi)")
                        .font(.system(.title2, design: .rounded).weight(.semibold))
                        .monospacedDigit()
                        .widgetAccentable()
                    Text("dBm")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Text(link.quality.label)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Pieces

private struct CurrentLink: View {
    let link: LinkInfo
    let snapshot: WiFiSnapshot
    let compact: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: compact ? 2 : 4) {
            HStack(alignment: .top) {
                Image(systemName: "wifi", variableValue: link.quality.symbolVariableValue)
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(.tint)
                    .widgetAccentable()
                Spacer()
                Text(link.quality.label)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
            }
            if compact { Spacer(minLength: 0) }
            Text(link.ssid ?? "Wi-Fi")
                .font(.headline)
                .lineLimit(1)
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text("\(link.rssi)")
                    .font(.system(compact ? .title : .largeTitle, design: .rounded).weight(.semibold))
                    .monospacedDigit()
                Text("dBm")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text("\(link.band.rawValue) · Ch \(link.channel)" + (compact ? "" : " · \(link.securityLabel)"))
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            Text("Updated \(Text(snapshot.date, style: .relative)) ago")
                .font(.caption2)
                .foregroundStyle(.tertiary)
                .lineLimit(1)
        }
        .accessibilityElement(children: .combine)
    }
}

/// A network: tapping joins its strongest access point (the app checks the link's token).
private struct NetworkLine: View {
    let group: NetworkGroup
    let token: String?

    var body: some View {
        WidgetLink(destination: DeepLink.connect(ssid: group.ssid, bssid: nil).url(token: token)) {
            HStack(spacing: 6) {
                Image(systemName: "wifi", variableValue: group.strongest.quality.symbolVariableValue)
                    .font(.caption)
                    .foregroundStyle(group.isCurrent ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
                    .widgetAccentable(group.isCurrent)
                    .frame(width: 16)
                Text(group.ssid)
                    .font(.caption)
                    .lineLimit(1)
                if group.accessPoints.count > 1 {
                    Text("\(group.accessPoints.count)")
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 4)
                        .background(.quaternary, in: .capsule)
                }
                Spacer(minLength: 4)
                Text("\(group.strongest.rssi)")
                    .font(.caption)
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityLabel("\(group.ssid), \(group.strongest.quality.label) signal, \(group.strongest.rssi) dBm")
    }
}

/// One access point of a network: tapping joins that BSSID.
private struct AccessPointLine: View {
    let group: NetworkGroup
    let accessPoint: AccessPoint
    let token: String?

    var body: some View {
        WidgetLink(destination: DeepLink.connect(ssid: group.ssid, bssid: accessPoint.bssid).url(token: token)) {
            HStack(spacing: 6) {
                Image(systemName: "checkmark")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.tint)
                    .opacity(group.isCurrent(accessPoint) ? 1 : 0)
                    .frame(width: 16)
                Text(accessPoint.channelSummary)
                    .font(.caption2)
                    .lineLimit(1)
                Text(accessPoint.bssid.map { String($0.suffix(8)) } ?? "")
                    .font(.caption2.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Spacer(minLength: 4)
                Text("\(accessPoint.rssi)")
                    .font(.caption2)
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityLabel("Access point \(accessPoint.bssid ?? ""), \(accessPoint.channelSummary), \(accessPoint.rssi) dBm")
    }
}

private struct StatusMessage {
    let symbol: String
    let title: String
    let detail: String

    init?(_ snapshot: WiFiSnapshot) {
        if snapshot.interfaceName == nil {
            (symbol, title, detail) = ("wifi.slash", "No Wi-Fi", "This Mac has no Wi-Fi hardware.")
        } else if !snapshot.powerOn {
            (symbol, title, detail) = ("wifi.slash", "Wi-Fi Off", "Turn Wi-Fi on from the menu bar.")
        } else if !snapshot.locationAuthorized {
            (symbol, title, detail) = ("location.slash", "Allow Location", "Open WiFiBar and allow Location access to see network names.")
        } else if snapshot.link == nil {
            (symbol, title, detail) = ("wifi.exclamationmark", "Not Connected", "\(snapshot.groups.count) networks nearby.")
        } else {
            return nil
        }
    }
}

private struct MessageView: View {
    let symbol: String
    let title: String
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Image(systemName: symbol)
                .font(.title2)
                .foregroundStyle(.secondary)
                .widgetAccentable()
            Spacer(minLength: 0)
            Text(title)
                .font(.headline)
            Text(detail)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }
}

// MARK: - Sample data for the widget gallery

extension WiFiSnapshot {
    public static var preview: WiFiSnapshot {
        func ap(_ ssid: String, _ bssid: String, _ rssi: Int, _ channel: Int, _ band: WiFiBand) -> AccessPoint {
            AccessPoint(ssid: ssid, bssid: bssid, rssi: rssi, noise: -95, channel: channel, band: band,
                        channelWidth: band == .ghz2_4 ? 20 : 80, security: .personal, securityLabel: "WPA2/WPA3 Personal")
        }
        let aps = [
            ap("Home", "a4:83:e7:10:20:01", -48, 36, .ghz5), ap("Home", "a4:83:e7:10:20:02", -61, 6, .ghz2_4),
            ap("Home", "a4:83:e7:10:20:03", -70, 149, .ghz5), ap("Office", "3c:22:fb:00:11:01", -66, 44, .ghz5),
            ap("Office", "3c:22:fb:00:11:02", -79, 1, .ghz2_4), ap("Café Guest", "f0:9f:c2:aa:bb:01", -74, 11, .ghz2_4),
        ]
        let link = LinkInfo(ssid: "Home", bssid: "a4:83:e7:10:20:01", rssi: -48, noise: -95, transmitRate: 1201,
                            channel: 36, band: .ghz5, channelWidth: 80, security: .personal,
                            securityLabel: "WPA2/WPA3 Personal", phyMode: "802.11ax")
        return WiFiSnapshot(
            date: .now, interfaceName: "en0", powerOn: true, locationAuthorized: true, link: link,
            groups: NetworkGrouping.group(aps, currentSSID: "Home", currentBSSID: link.bssid, knownSSIDs: ["Home"]),
            hiddenCount: 0, linkToken: nil
        )
    }
}
