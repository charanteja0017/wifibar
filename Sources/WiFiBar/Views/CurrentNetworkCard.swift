import SwiftUI
import WiFiCore

/// Details of the network this Mac is joined to, like the extra lines in an Option-click Wi-Fi menu.
struct CurrentNetworkCard: View {
    let link: LinkInfo
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: MenuMetrics.glyphSpacing) {
                WiFiGlyph(quality: link.quality, isActive: true, size: 32)
                VStack(alignment: .leading, spacing: 1) {
                    Text(link.ssid ?? "Connected")
                        .font(.headline)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Text("\(link.quality.label) · \(link.rssi) dBm")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
                Spacer(minLength: 0)
                if let target = LocateTarget(link: link) {
                    Button("Locate", systemImage: "location.viewfinder") { model.locate(target) }
                        .labelStyle(.titleAndIcon)
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .help("Find this router by walking toward the strongest signal")
                }
            }

            SignalMeter(rssi: link.rssi)

            Grid(alignment: .leading, horizontalSpacing: 8, verticalSpacing: 3) {
                GridRow {
                    detail("Noise", link.noise < 0 ? "\(link.noise) dBm" : "—")
                    detail("SNR", link.snr.map { "\($0) dB" } ?? "—")
                }
                GridRow {
                    detail("Tx Rate", link.transmitRate > 0 ? "\(Int(link.transmitRate)) Mbps" : "—")
                    detail("PHY Mode", link.phyMode)
                }
                GridRow {
                    detail("Channel", "\(link.channel) · \(link.band.rawValue)")
                    detail("Width", link.channelWidth > 0 ? "\(link.channelWidth) MHz" : "—")
                }
                GridRow {
                    Text("Security").foregroundStyle(.secondary)
                    Text(link.securityLabel).gridCellColumns(3)
                }
                GridRow {
                    Text("BSSID").foregroundStyle(.secondary)
                    Text(link.bssid ?? "Hidden").monospaced().textSelection(.enabled).gridCellColumns(3)
                }
            }
            .font(.caption)
            .monospacedDigit()
        }
        .padding(12)
        .background(.fill.quaternary, in: .rect(cornerRadius: 12, style: .continuous))
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel)
    }

    @ViewBuilder
    private func detail(_ label: String, _ value: String) -> some View {
        Text(label).foregroundStyle(.secondary)
        Text(value)
    }

    private var accessibilityLabel: String {
        let name = link.ssid ?? "Wi-Fi"
        return "Connected to \(name). Signal \(link.quality.bars) of 3, \(link.quality.label), \(link.rssi) dBm. \(link.band.rawValue), channel \(link.channel)."
    }
}
