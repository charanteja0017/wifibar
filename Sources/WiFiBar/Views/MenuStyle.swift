import SwiftUI
import WiFiCore

/// The SDK 27 `@State` attribute is a macro whose plugin ships only with Xcode, so Command Line Tools
/// builds fail on it. This alias reaches the plain `State` property wrapper, which behaves the same.
typealias ViewState<Value> = SwiftUICore.State<Value>

/// Shared metrics so every row lines up like a native menu.
enum MenuMetrics {
    /// Inset of the highlight from the panel edge.
    static let rowInset: CGFloat = 5
    /// Padding inside the highlight.
    static let rowPadding: CGFloat = 9
    static let highlightRadius: CGFloat = 8
    static let glyphSize: CGFloat = 26
    static let glyphSpacing: CGFloat = 10
    /// Leading edge of section headers and loose text (rowInset + rowPadding).
    static let textInset: CGFloat = rowInset + rowPadding
}

/// Rounded hover highlight used by every clickable row, like the rows in Apple's menu bar menus.
struct MenuHighlight: ViewModifier {
    var verticalPadding: CGFloat = 4
    @ViewState private var isHovering = false
    @Environment(\.isEnabled) private var isEnabled

    func body(content: Content) -> some View {
        content
            .padding(.horizontal, MenuMetrics.rowPadding)
            .padding(.vertical, verticalPadding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(.rect)
            .background(
                RoundedRectangle(cornerRadius: MenuMetrics.highlightRadius, style: .continuous)
                    .fill(.quaternary)
                    .opacity(isHovering && isEnabled ? 1 : 0)
            )
            .onHover { isHovering = $0 }
            .padding(.horizontal, MenuMetrics.rowInset)
    }
}

/// A plain menu-like button row.
struct MenuRowButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.6 : 1)
            .modifier(MenuHighlight())
    }
}

extension ButtonStyle where Self == MenuRowButtonStyle {
    static var menuRow: MenuRowButtonStyle { MenuRowButtonStyle() }
}

struct MenuDivider: View {
    var body: some View {
        Divider()
            .padding(.horizontal, MenuMetrics.textInset)
            .padding(.vertical, 5)
    }
}

struct SectionHeader: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.caption.weight(.semibold))
            .foregroundStyle(.secondary)
            .padding(.horizontal, MenuMetrics.textInset)
            .padding(.top, 6)
            .padding(.bottom, 2)
            .accessibilityAddTraits(.isHeader)
    }
}

/// The Wi-Fi glyph in a circle, filled with the accent colour for the network you're on.
struct WiFiGlyph: View {
    let quality: SignalQuality
    var isActive = false
    var size: CGFloat = MenuMetrics.glyphSize

    var body: some View {
        Image(systemName: "wifi", variableValue: quality.symbolVariableValue)
            .font(.system(size: size * 0.46, weight: .semibold))
            .foregroundStyle(isActive ? AnyShapeStyle(.white) : AnyShapeStyle(.primary))
            .frame(width: size, height: size)
            .background(Circle().fill(isActive ? AnyShapeStyle(.tint) : AnyShapeStyle(.quaternary)))
            .accessibilityHidden(true)
    }
}

/// Slim signal meter on a −90…−30 dBm scale, tinted by quality.
struct SignalMeter: View {
    let rssi: Int

    var body: some View {
        ProgressView(value: SignalQuality.meterFraction(rssi: rssi))
            .progressViewStyle(.linear)
            .tint(SignalQuality(rssi: rssi).color)
            .accessibilityHidden(true)
    }
}

extension SignalQuality {
    var color: Color {
        switch self {
        case .excellent, .good: .green
        case .fair: .yellow
        case .weak: .red
        }
    }
}

struct InlineError: View {
    let message: String

    var body: some View {
        Label {
            Text(message)
                .fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: "exclamationmark.triangle.fill")
                .symbolRenderingMode(.multicolor)
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .padding(.horizontal, MenuMetrics.textInset)
        .padding(.vertical, 4)
    }
}
