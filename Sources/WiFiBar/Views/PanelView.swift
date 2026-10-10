import SwiftUI
import WiFiCore

/// Everything inside the menu bar panel.
struct PanelView: View {
    static let width: CGFloat = 340

    var onSizeChange: (CGSize) -> Void
    @Environment(AppModel.self) private var model
    @ViewState private var totalHeight: CGFloat = 0
    @ViewState private var listHeight: CGFloat = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            content
            MenuDivider()
            FooterSection()
        }
        .padding(.vertical, 6)
        .frame(width: Self.width)
        .fixedSize(horizontal: false, vertical: true)
        .onGeometryChange(for: CGSize.self) { $0.size } action: { size in
            totalHeight = size.height
            onSizeChange(size)
        }
        // If the window is briefly taller than the content, keep the content at the top.
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    /// The list gets whatever the screen has left after the header, card and footer.
    private var maxListHeight: CGFloat {
        StatusItemController.availableListHeight(otherContent: max(0, totalHeight - listHeight))
    }

    private var header: some View {
        HStack {
            Text("Wi-Fi")
                .font(.headline)
            Spacer()
            Toggle("Wi-Fi", isOn: Binding(
                get: { model.snapshot.powerOn },
                set: { model.setPower($0) }
            ))
            .toggleStyle(.switch)
            .labelsHidden()
            .disabled(model.hasLoaded && model.snapshot.interfaceName == nil)
        }
        .padding(.horizontal, MenuMetrics.textInset)
        .padding(.vertical, 4)
    }

    @ViewBuilder private var content: some View {
        let snapshot = model.snapshot
        if !model.hasLoaded {
            ProgressView()
                .controlSize(.small)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
        } else if snapshot.interfaceName == nil {
            MenuDivider()
            message("This Mac has no Wi-Fi hardware.")
        } else if snapshot.powerOn {
            if let link = snapshot.link {
                CurrentNetworkCard(link: link)
            }
            if model.location.isAuthorized {
                NetworkListSection(maxHeight: maxListHeight)
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { listHeight = $0 }
            } else {
                LocationPrompt()
            }
        }
    }

    private func message(_ text: String) -> some View {
        Text(text)
            .font(.callout)
            .foregroundStyle(.secondary)
            .padding(.horizontal, MenuMetrics.textInset)
            .padding(.vertical, 4)
    }
}

/// Explains why macOS needs Location access before it reveals network names.
private struct LocationPrompt: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label {
                Text("Allow Location Access")
                    .font(.headline)
            } icon: {
                Image(systemName: "location.circle.fill")
                    .foregroundStyle(.tint)
            }
            Text("macOS shows Wi-Fi network names and access points only to apps with Location access. WiFiBar never reads your location.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Button(model.location.canAsk ? "Allow Location Access…" : "Open Privacy & Security…") {
                model.requestLocation()
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.small)
        }
        .padding(.horizontal, MenuMetrics.textInset)
        .padding(.vertical, 8)
    }
}

private struct FooterSection: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: model.rescan) {
                HStack {
                    Text("Rescan")
                    Spacer()
                    if model.isScanning {
                        ProgressView()
                            .controlSize(.mini)
                    } else if model.hasLoaded {
                        Text("Updated \(Text(model.snapshot.date, style: .relative)) ago")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                }
            }
            .buttonStyle(.menuRow)
            .disabled(!model.snapshot.powerOn || !model.location.isAuthorized)

            Toggle(isOn: Binding(get: { model.launchAtLogin }, set: { model.setLaunchAtLogin($0) })) {
                Text("Launch at Login")
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .toggleStyle(.switch)
            .controlSize(.mini)
            .modifier(MenuHighlight())
            if let error = model.launchAtLoginError {
                InlineError(message: error)
            }

            Button("Wi-Fi Settings…", action: model.openWiFiSettings)
                .buttonStyle(.menuRow)

            MenuDivider()

            // The standard About window; it shows Credits.rtf from the app bundle.
            Button("About WiFiBar") {
                NSApp.activate()
                NSApp.orderFrontStandardAboutPanel(nil)
            }
            .buttonStyle(.menuRow)

            Button {
                NSApp.terminate(nil)
            } label: {
                HStack {
                    Text("Quit WiFiBar")
                    Spacer()
                    Text("⌘Q")
                        .foregroundStyle(.secondary)
                }
            }
            .buttonStyle(.menuRow)
            .keyboardShortcut("q")
        }
    }
}
