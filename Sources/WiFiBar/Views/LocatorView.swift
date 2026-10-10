import Charts
import MapKit
import SwiftUI
import WiFiCore

/// Live signal, distance estimate, history and map for one access point.
struct LocatorView: View {
    @Bindable var model: LocatorModel

    var body: some View {
        Form {
            Section {
                SignalReadout(model: model)
            } footer: {
                Text("Walk slowly and pause for a few seconds at each spot, holding your Mac the same way. The access point is where the reading peaks.")
            }

            DirectionSection(model: model)

            Section {
                LabeledContent("Distance") {
                    if let estimate = model.estimate {
                        Text([DistanceEstimator.format(estimate.meters), DistanceEstimator.formatRange(estimate).map { "(\($0))" }]
                            .compactMap { $0 }.joined(separator: "  "))
                            .monospacedDigit()
                    } else {
                        Text("—")
                    }
                }
                Picker("Surroundings", selection: $model.environment) {
                    ForEach(SignalEnvironment.allCases, id: \.self) { environment in
                        Text(environment.label).tag(environment)
                    }
                }
            } header: {
                Text("Estimated Distance")
            } footer: {
                Text("A rough guide worked out from signal strength. Walls, people and the router’s antennas can put it off by several metres.")
            }

            Section("Last 2 Minutes") {
                SignalHistoryChart(samples: model.tracker.samples, peak: model.tracker.peak)
                LabeledContent("Best reading") {
                    if let peak = model.tracker.peak {
                        Text("\(peak.rssi) dBm, \(Text(peak.date, style: .relative)) ago")
                            .monospacedDigit()
                    } else {
                        Text("—")
                    }
                }
                Toggle("Play a sound at each new best reading", isOn: $model.playsSound)
                Button("Reset History", action: model.resetHistory)
                    .disabled(model.tracker.samples.isEmpty)
            }

            WiGLESection(model: model)
        }
        .formStyle(.grouped)
        .frame(minWidth: 400, minHeight: 480)
    }
}

private struct SignalReadout: View {
    let model: LocatorModel

    private var quality: SignalQuality? { model.latestRSSI.map(SignalQuality.init(rssi:)) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 14) {
                WiFiGlyph(quality: quality ?? .weak, isActive: true, size: 54)
                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text(model.latestRSSI.map(String.init) ?? "—")
                            .font(.system(size: 44, weight: .semibold, design: .rounded))
                            .monospacedDigit()
                            .contentTransition(.numericText())
                        Text("dBm")
                            .font(.title3)
                            .foregroundStyle(.secondary)
                    }
                    Text(quality?.label ?? "Measuring…")
                        .font(.headline)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                TrendLabel(trend: model.isMissing ? .unknown : model.tracker.trend)
            }
            if let rssi = model.latestRSSI {
                SignalMeter(rssi: rssi)
            }
            if !model.isLinkReading, let latest = model.tracker.latest {
                Text("Last reading \(Text(latest.date, style: .relative)) ago")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            Text(status)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 4)
        .animation(.default, value: model.latestRSSI)
        .accessibilityElement(children: .combine)
    }

    private var status: String {
        let target = model.target
        let source = model.isLinkReading
            ? "Live from your connection, every second."
            : "From Wi-Fi scans, which macOS refreshes about every 10–15 seconds, so move slowly. Join this network for a reading every second."
        if let error = model.errorMessage { return error }
        if model.isMissing { return "Not found in the last scan. You may be out of range. \(target.channelSummary)." }
        return "\(target.channelSummary). \(source)"
    }
}

private struct TrendLabel: View {
    let trend: SignalTrend

    var body: some View {
        Label {
            Text(text)
        } icon: {
            Image(systemName: symbol)
                .foregroundStyle(color)
        }
        .font(.callout.weight(.medium))
        .labelStyle(.titleAndIcon)
    }

    private var text: String {
        switch trend {
        case .stronger: "Getting stronger"
        case .weaker: "Getting weaker"
        case .steady: "Steady"
        case .unknown: "Measuring…"
        }
    }

    private var symbol: String {
        switch trend {
        case .stronger: "arrow.up.circle.fill"
        case .weaker: "arrow.down.circle.fill"
        case .steady: "equal.circle.fill"
        case .unknown: "ellipsis.circle"
        }
    }

    private var color: Color {
        switch trend {
        case .stronger: .green
        case .weaker: .orange
        case .steady, .unknown: .secondary
        }
    }
}

/// One series, so no legend; the section title names it. A dashed rule marks the best reading.
private struct SignalHistoryChart: View {
    let samples: [SignalSample]
    let peak: SignalSample?
    @ViewState private var selectedDate: Date?

    private var now: Date { samples.last?.date ?? .now }

    private var selected: SignalSample? {
        guard let selectedDate else { return nil }
        return samples.min { abs($0.date.timeIntervalSince(selectedDate)) < abs($1.date.timeIntervalSince(selectedDate)) }
    }

    var body: some View {
        Chart {
            ForEach(samples) { sample in
                LineMark(x: .value("Time", sample.date), y: .value("Signal (dBm)", sample.rssi))
                    .interpolationMethod(.monotone)
                    .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round))
            }
            if let peak {
                RuleMark(y: .value("Best", peak.rssi))
                    .foregroundStyle(.secondary)
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
                    .annotation(position: .top, alignment: .leading, spacing: 2) {
                        Text("Best \(peak.rssi) dBm")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
            }
            if let selected {
                RuleMark(x: .value("Time", selected.date))
                    .foregroundStyle(.tertiary)
                PointMark(x: .value("Time", selected.date), y: .value("Signal (dBm)", selected.rssi))
                    .symbolSize(48)
                    .annotation(position: .top, overflowResolution: .init(x: .fit(to: .chart), y: .disabled)) {
                        Text("\(selected.rssi) dBm, \(Int(now.timeIntervalSince(selected.date))) s ago")
                            .font(.caption.monospacedDigit())
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(.regularMaterial, in: .rect(cornerRadius: 6))
                    }
            }
        }
        .chartYScale(domain: -95 ... -25)
        .chartXScale(domain: now.addingTimeInterval(-120) ... now)
        .chartXAxis {
            // Ticks at fixed offsets from now, not wall-clock half-minutes, so labels read −90 s rather than −91 s.
            AxisMarks(values: [120.0, 90, 60, 30, 0].map { now.addingTimeInterval(-$0) }) { value in
                AxisGridLine()
                AxisValueLabel(anchor: value.index == 4 ? .topTrailing : value.index == 0 ? .topLeading : .top) {
                    if let date = value.as(Date.self) {
                        let seconds = Int(now.timeIntervalSince(date).rounded())
                        Text(seconds == 0 ? "now" : seconds == 120 ? "−2 min" : "−\(seconds) s")
                    }
                }
            }
        }
        .chartYAxis {
            AxisMarks(position: .leading, values: [-90, -70, -50, -30]) { value in
                AxisGridLine()
                AxisValueLabel { Text("\(value.as(Int.self) ?? 0)") }
            }
        }
        .chartPlotStyle { $0.clipped() }
        .chartXSelection(value: $selectedDate)
        .frame(height: 150)
        .padding(.vertical, 6)
        .accessibilityLabel("Signal over the last 2 minutes")
        .accessibilityValue(samples.last.map { "Latest \($0.rssi) dBm" } ?? "No readings yet")
    }
}

/// Where WiGLE's volunteers have mapped this access point.
private struct WiGLESection: View {
    @Bindable var model: LocatorModel
    @ViewState private var name = ""
    @ViewState private var token = ""
    @ViewState private var saveError: String?

    var body: some View {
        Section {
            if model.credentials == nil {
                Text("Look up where this access point has been mapped. You need a free WiGLE account and its API token.")
                    .foregroundStyle(.secondary)
                TextField("API Name", text: $name)
                    .textContentType(.username)
                SecureField("API Token", text: $token)
                    .textContentType(.password)
                if let saveError {
                    Text(saveError).foregroundStyle(.secondary)
                }
                HStack {
                    Link("Get an API token at wigle.net…", destination: URL(string: "https://wigle.net/account")!)
                    Spacer()
                    Button("Save") {
                        do {
                            try model.saveCredentials(name: name, token: token)
                            saveError = nil
                        } catch {
                            saveError = error.localizedDescription
                        }
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || token.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            } else {
                result
                HStack {
                    Button(model.mapState == .idle ? "Look Up on WiGLE" : "Look Up Again", action: model.lookUpOnWiGLE)
                        .disabled(model.mapState == .loading)
                    if model.mapState == .loading {
                        ProgressView().controlSize(.small)
                    }
                    Spacer()
                    Button("Remove API Token", role: .destructive, action: model.forgetCredentials)
                        .buttonStyle(.borderless)
                }
            }
        } header: {
            Text("Map")
        } footer: {
            Text("Looking it up sends this BSSID to wigle.net. WiGLE’s positions are approximate: they come from where volunteers’ phones heard the network.")
        }
    }

    @ViewBuilder private var result: some View {
        switch model.mapState {
        case .idle, .loading:
            EmptyView()
        case let .found(network):
            if let latitude = network.trilat, let longitude = network.trilong {
                let coordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
                Map(initialPosition: .region(MKCoordinateRegion(center: coordinate, latitudinalMeters: 600, longitudinalMeters: 600))) {
                    Marker(network.ssid ?? model.target.ssid, systemImage: "wifi.router", coordinate: coordinate)
                }
                .frame(height: 220)
                .clipShape(.rect(cornerRadius: 8))
                .id(network.netid)
                LabeledContent("Address", value: network.address ?? "Not available")
                LabeledContent("Last updated on WiGLE", value: network.lastUpdated ?? "Unknown")
                LabeledContent("Coordinates", value: String(format: "%.5f, %.5f", latitude, longitude))
                    .textSelection(.enabled)
                Button("Open in Maps") {
                    openInMaps(latitude: latitude, longitude: longitude, name: network.ssid ?? model.target.ssid)
                }
            }
        case .notFound:
            Text("WiGLE has no location for this access point. Nobody has mapped it yet.")
                .foregroundStyle(.secondary)
        case let .failed(message):
            Label {
                Text(message)
            } icon: {
                Image(systemName: "exclamationmark.triangle.fill").symbolRenderingMode(.multicolor)
            }
        }
    }

    private func openInMaps(latitude: Double, longitude: Double, name: String) {
        var components = URLComponents(string: "https://maps.apple.com/")!
        components.queryItems = [
            URLQueryItem(name: "ll", value: "\(latitude),\(longitude)"),
            URLQueryItem(name: "q", value: name),
        ]
        if let url = components.url { NSWorkspace.shared.open(url) }
    }
}
