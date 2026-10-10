import SwiftUI
import WiFiCore

/// Turn on the spot while holding the Mac; the gyroscope tracks the angle and the signal peaks facing the router.
struct DirectionSection: View {
    let model: LocatorModel

    var body: some View {
        Section {
            switch model.sweepPhase {
            case .idle:
                idle
            case .calibrating:
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("Hold still for a moment…")
                }
            case .turning:
                turning
            case let .done(result):
                done(result)
            case let .failed(message):
                Label {
                    Text(message)
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill").symbolRenderingMode(.multicolor)
                }
                Button("Try Again", action: model.startSweep)
                    .disabled(!model.isLinkReading)
            }
        } header: {
            Text("Direction")
        } footer: {
            Text("Uses your Mac’s built-in motion sensor to track how far you turn. Hold the Mac in front of you as if typing: your body blocks signal from behind, so the reading is strongest when you face the router.")
        }
    }

    @ViewBuilder private var idle: some View {
        if model.isLinkReading {
            Text("Stand in one spot, hold your Mac in front of you, then turn slowly in a full circle.")
            Button("Start Direction Sweep", systemImage: "location.north.line", action: model.startSweep)
        } else {
            Text("Direction finding needs several readings a second, and only the network you’re connected to gives that. Join “\(model.target.ssid)” on this access point first.")
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder private var turning: some View {
        Text("Turn slowly to your left on the spot, taking about 30 seconds for the circle. Click Done when you face where you started.")
        PolarPlot(readings: model.sweep.readings, result: nil)
            .frame(height: 200)
            .frame(maxWidth: .infinity)
        LabeledContent("Turned") {
            Text("\(Int(abs(model.turned)))°  ·  \(model.sweep.readings.count) readings")
                .monospacedDigit()
        }
        HStack {
            Button("Cancel", role: .cancel, action: model.cancelSweep)
            Spacer()
            Button("Done", action: model.finishSweep)
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
        }
    }

    @ViewBuilder private func done(_ result: DirectionSweep.Result) -> some View {
        if result.isClear {
            TimelineView(.animation(minimumInterval: 1.0 / 30)) { _ in
                TurnPointer(turn: model.turnToStrongest(result))
            }
            .frame(maxWidth: .infinity)
        }
        Text(summary(result))
            .fixedSize(horizontal: false, vertical: true)
        PolarPlot(readings: [], result: result)
            .frame(height: 180)
            .frame(maxWidth: .infinity)
        LabeledContent("Measured") {
            Text("\(model.sweep.readings.count) readings over \(Int((360 / result.scale).rounded()))° turned, \(String(format: "%.1f", result.spread)) dB spread")
                .monospacedDigit()
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        HStack {
            Button("Sweep Again", action: model.startSweep)
            Spacer()
            Button("Stop", action: model.cancelSweep)
        }
    }

    private func summary(_ result: DirectionSweep.Result) -> String {
        let spread = String(format: "%.0f", result.spread)
        guard result.isClear else {
            return "No clear direction: only \(spread) dB between the strongest and weakest directions. Hold the Mac closer to your body, or move a few metres and try again."
        }
        let bearing = Int(result.bearing.rounded())
        let place = switch bearing {
        case 0..<15, 346...360: "straight ahead of where you started"
        case 166...195: "behind where you started"
        case 15...165: "about \(bearing)° to the left of where you started"
        default: "about \(360 - bearing)° to the right of where you started"
        }
        return "Strongest \(place), \(spread) dB above the weakest direction. Follow the arrow, walk that way, and sweep again as you get closer."
    }
}

/// Big arrow showing which way to turn from where you're facing now.
private struct TurnPointer: View {
    /// Degrees to turn; positive = left.
    let turn: Double

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: abs(turn) < 15 ? "arrow.up.circle.fill" : "arrow.up")
                .font(.system(size: 54, weight: .semibold))
                .foregroundStyle(abs(turn) < 15 ? AnyShapeStyle(.green) : AnyShapeStyle(.tint))
                .rotationEffect(.degrees(-turn))
            Text(instruction)
                .font(.headline)
                .monospacedDigit()
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(instruction)
    }

    private var instruction: String {
        let degrees = Int(abs(turn).rounded())
        if degrees < 15 { return "Straight ahead. Walk forward." }
        return turn > 0 ? "Turn \(degrees)° left" : "Turn \(degrees)° right"
    }
}

/// Signal around the circle, viewed from above, with the start direction at the top and left on the left.
private struct PolarPlot: View {
    let readings: [DirectionSweep.Reading]
    let result: DirectionSweep.Result?

    var body: some View {
        Canvas { context, size in
            let radius = min(size.width, size.height) / 2 - 18
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            // Angle measured left of "start" (top): left turns plot on the left.
            func point(_ degrees: Double, _ r: Double) -> CGPoint {
                let radians = degrees * .pi / 180
                return CGPoint(x: center.x - r * sin(radians), y: center.y - r * cos(radians))
            }

            for fraction in [1.0, 0.66, 0.33] {
                let r = radius * fraction
                context.stroke(Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: 2 * r, height: 2 * r)),
                               with: .style(.separator), lineWidth: 1)
            }
            for (degrees, label) in [(0.0, "Start"), (90, "Left"), (180, "Behind"), (270, "Right")] {
                var spoke = Path()
                spoke.move(to: center)
                spoke.addLine(to: point(degrees, radius))
                context.stroke(spoke, with: .style(.separator), lineWidth: 1)
                context.draw(Text(label).font(.caption2).foregroundStyle(.secondary), at: point(degrees, radius + 10))
            }

            let values = result?.profile ?? readings.map { Double($0.rssi) }
            guard let low = values.min(), let high = values.max() else { return }
            // Weakest at 25% of the radius, strongest at the rim.
            func r(_ rssi: Double) -> Double {
                radius * (0.25 + 0.75 * (high > low ? (rssi - low) / (high - low) : 0.5))
            }

            if let result {
                var shape = Path()
                for (index, value) in result.profile.enumerated() {
                    let p = point(Double(index) * result.profileStep, r(value))
                    index == 0 ? shape.move(to: p) : shape.addLine(to: p)
                }
                shape.closeSubpath()
                context.fill(shape, with: .style(.tint.opacity(0.18)))
                context.stroke(shape, with: .style(.tint), lineWidth: 2)
                if result.isClear {
                    var line = Path()
                    line.move(to: center)
                    line.addLine(to: point(result.bearing, radius))
                    context.stroke(line, with: .style(.tint), style: StrokeStyle(lineWidth: 2, lineCap: .round, dash: [5, 4]))
                    let tip = point(result.bearing, radius)
                    context.fill(Path(ellipseIn: CGRect(x: tip.x - 5, y: tip.y - 5, width: 10, height: 10)), with: .style(.tint))
                }
            } else {
                for reading in readings {
                    let p = point(DirectionSweep.normalized(reading.heading), r(Double(reading.rssi)))
                    context.fill(Path(ellipseIn: CGRect(x: p.x - 3, y: p.y - 3, width: 6, height: 6)), with: .style(.tint))
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityLabel("Signal around you, seen from above")
    }
}
