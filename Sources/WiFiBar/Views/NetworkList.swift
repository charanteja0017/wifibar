import SwiftUI
import WiFiCore

/// "Known Networks" and "Other Networks", one row per name, expandable to one row per access point.
struct NetworkListSection: View {
    /// Scrolls only beyond this; the panel passes what's left of the screen.
    var maxHeight: CGFloat
    @Environment(AppModel.self) private var model
    @ViewState private var contentHeight: CGFloat = 0

    var body: some View {
        let groups = model.snapshot.groups
        let known = groups.filter(\.isKnown)
        let other = groups.filter { !$0.isKnown }

        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if !known.isEmpty {
                    SectionHeader(title: "Known Networks")
                    ForEach(known) { NetworkGroupView(group: $0) }
                }
                if !other.isEmpty {
                    SectionHeader(title: "Other Networks")
                    ForEach(other) { NetworkGroupView(group: $0) }
                }
                if groups.isEmpty {
                    Text(model.isScanning ? "Scanning…" : "No networks found.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, MenuMetrics.textInset)
                        .padding(.vertical, 6)
                }
                if model.snapshot.hiddenCount > 0 {
                    Text(model.snapshot.hiddenCount == 1 ? "1 hidden network not shown" : "\(model.snapshot.hiddenCount) hidden networks not shown")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                        .padding(.horizontal, MenuMetrics.textInset)
                        .padding(.top, 4)
                }
            }
            .padding(.bottom, 2)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
        }
        .frame(height: min(contentHeight, maxHeight))
        .scrollBounceBehavior(.basedOnSize)
        .scrollIndicators(contentHeight > maxHeight ? .automatic : .hidden)
    }
}

/// A network name plus, when expanded, each access point that broadcasts it.
private struct NetworkGroupView: View {
    let group: NetworkGroup
    @Environment(AppModel.self) private var model

    private var isExpanded: Bool { model.expandedGroups.contains(group.ssid) }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            NetworkRow(group: group, isExpanded: isExpanded)
            feedback(for: JoinTarget(ssid: group.ssid, bssid: nil))

            if isExpanded {
                ForEach(group.accessPoints) { ap in
                    AccessPointRow(group: group, accessPoint: ap)
                    feedback(for: JoinTarget(ssid: group.ssid, bssid: ap.bssid))
                }
                Text("Choose an access point to join it. macOS may later roam to another one.")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.leading, AccessPointRow.leadingIndent + MenuMetrics.textInset)
                    .padding(.trailing, MenuMetrics.textInset)
                    .padding(.vertical, 3)
            }
        }
    }

    /// Password form or error shown right under the row that started the join.
    @ViewBuilder
    private func feedback(for target: JoinTarget) -> some View {
        if let prompt = model.credentialPrompt, prompt.target == target {
            CredentialForm(prompt: prompt)
        } else if let failure = model.joinFailure, failure.target == target {
            InlineError(message: failure.message)
        }
    }
}

private struct NetworkRow: View {
    let group: NetworkGroup
    let isExpanded: Bool
    @Environment(AppModel.self) private var model

    private var target: JoinTarget { JoinTarget(ssid: group.ssid, bssid: nil) }

    var body: some View {
        HStack(spacing: 4) {
            Button(action: primaryAction) {
                HStack(spacing: MenuMetrics.glyphSpacing) {
                    WiFiGlyph(quality: group.strongest.quality, isActive: group.isCurrent)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(group.ssid)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    if model.joining == target {
                        ProgressView()
                            .controlSize(.small)
                    }
                    if group.security.isSecured {
                        Image(systemName: "lock.fill")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Text("\(group.strongest.rssi) dBm")
                        .font(.caption)
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .help(group.isCurrent ? "Show access points" : "Join “\(group.ssid)” on its strongest access point")

            Button {
                withAnimation(.snappy(duration: 0.2)) { model.toggleExpanded(group.ssid) }
            } label: {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .rotationEffect(.degrees(isExpanded ? 90 : 0))
                    .frame(width: 18, height: MenuMetrics.glyphSize)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isExpanded ? "Hide access points" : "Show access points")
        }
        .modifier(MenuHighlight(verticalPadding: 3))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(accessibilityLabel)
    }

    private func primaryAction() {
        if group.isCurrent {
            withAnimation(.snappy(duration: 0.2)) { model.toggleExpanded(group.ssid) }
        } else {
            model.join(target)
        }
    }

    private var subtitle: String {
        let count = group.accessPoints.count
        if count == 1 { return group.strongest.channelSummary }
        // "2.4 & 5 GHz" rather than "2.4 GHz, 5 GHz" so the line fits beside the signal column.
        var numbers = group.bands.filter { $0 != .unknown }.map { $0.rawValue.replacingOccurrences(of: " GHz", with: "") }
        guard let last = numbers.popLast() else { return "\(count) access points" }
        let bands = numbers.isEmpty ? last : numbers.joined(separator: ", ") + " & " + last
        return "\(count) access points · \(bands) GHz"
    }

    private var accessibilityLabel: String {
        var parts = [group.ssid, "\(group.strongest.quality.label) signal, \(group.strongest.rssi) dBm"]
        if group.isCurrent { parts.append("connected") }
        if group.security.isSecured { parts.append("secured") }
        parts.append(group.accessPoints.count == 1 ? "1 access point" : "\(group.accessPoints.count) access points")
        return parts.joined(separator: ", ")
    }
}

private struct AccessPointRow: View {
    let group: NetworkGroup
    let accessPoint: AccessPoint
    @Environment(AppModel.self) private var model

    /// Lines the checkmark up under the network glyph.
    static let leadingIndent: CGFloat = 12

    private var isCurrent: Bool { group.isCurrent(accessPoint) }
    private var target: JoinTarget { JoinTarget(ssid: group.ssid, bssid: accessPoint.bssid) }

    private var canJoin: Bool { !isCurrent && accessPoint.bssid != nil }

    var body: some View {
        HStack(spacing: 4) {
            Button {
                if canJoin { model.join(target) }
            } label: {
                rowContent
            }
            .buttonStyle(.plain)
            .help(isCurrent ? "Connected to this access point" : "Join this access point")
            .accessibilityLabel(accessibilityLabel)

            Button(action: locate) {
                Image(systemName: "location.viewfinder")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .frame(width: 22, height: 22)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .disabled(accessPoint.bssid == nil)
            .help("Find this access point by its signal")
            .accessibilityLabel("Locate access point")
        }
        .modifier(MenuHighlight())
        .contextMenu {
            Button("Join This Access Point") { model.join(target) }
                .disabled(!canJoin)
            Button("Locate…", action: locate)
                .disabled(accessPoint.bssid == nil)
            Button("Copy BSSID") {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(accessPoint.bssid ?? "", forType: .string)
            }
            .disabled(accessPoint.bssid == nil)
        }
    }

    private func locate() {
        model.locate(LocateTarget(ssid: group.ssid, accessPoint: accessPoint))
    }

    private var rowContent: some View {
        HStack(spacing: 8) {
            Image(systemName: "checkmark")
                .font(.caption.weight(.bold))
                .foregroundStyle(.tint)
                .opacity(isCurrent ? 1 : 0)
                .frame(width: 14)
            VStack(alignment: .leading, spacing: 1) {
                Text(accessPoint.channelSummary)
                    .font(.callout)
                Text(accessPoint.bssid ?? "BSSID unavailable")
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 4)
            if model.joining == target {
                ProgressView()
                    .controlSize(.small)
            }
            Text("\(accessPoint.rssi) dBm")
                .font(.caption)
                .monospacedDigit()
                .foregroundStyle(.secondary)
            Image(systemName: "wifi", variableValue: accessPoint.quality.symbolVariableValue)
                .font(.callout)
                .foregroundStyle(.secondary)
                .frame(width: 20)
        }
        .padding(.leading, Self.leadingIndent)
        .contentShape(.rect)
    }

    private var accessibilityLabel: String {
        var parts = [
            "Access point \(accessPoint.bssid ?? "")",
            accessPoint.channelSummary,
            "\(accessPoint.quality.label) signal, \(accessPoint.rssi) dBm",
        ]
        if isCurrent { parts.append("connected") }
        return parts.joined(separator: ", ")
    }
}

/// Inline password (and username, for enterprise networks) form.
private struct CredentialForm: View {
    let prompt: CredentialPrompt
    @Environment(AppModel.self) private var model
    @ViewState private var username = ""
    @ViewState private var password = ""
    @FocusState private var focus: Field?

    private enum Field { case username, password }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(prompt.enterprise
                ? "Enter your username and password for “\(prompt.target.ssid)”."
                : "Enter the password for “\(prompt.target.ssid)”.")
                .font(.callout)
                .fixedSize(horizontal: false, vertical: true)
            if let reason = prompt.reason {
                Text(reason)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if prompt.enterprise {
                TextField("Username", text: $username)
                    .textContentType(.username)
                    .focused($focus, equals: .username)
            }
            SecureField("Password", text: $password)
                .textContentType(.password)
                .focused($focus, equals: .password)
                .onSubmit(submit)
            HStack {
                Spacer()
                Button("Cancel", action: model.cancelCredentials)
                    .keyboardShortcut(.cancelAction)
                Button("Join", action: submit)
                    .keyboardShortcut(.defaultAction)
                    .disabled(!canSubmit)
            }
        }
        .textFieldStyle(.roundedBorder)
        .controlSize(.small)
        .padding(.horizontal, MenuMetrics.textInset)
        .padding(.vertical, 6)
        .onAppear { focus = prompt.enterprise ? .username : .password }
    }

    private var canSubmit: Bool {
        !password.isEmpty && (!prompt.enterprise || !username.isEmpty)
    }

    private func submit() {
        guard canSubmit else { return }
        model.join(prompt.target, credentials: Credentials(username: prompt.enterprise ? username : nil, password: password))
    }
}
