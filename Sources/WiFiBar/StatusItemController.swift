import AppKit
import SwiftUI
import WiFiCore

/// Borderless panel that can take keyboard focus (for password fields) without activating the app,
/// like the panels behind Apple's own menu bar items.
private final class MenuPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }

    override func cancelOperation(_ sender: Any?) {
        orderOut(sender)
        onClose?()
    }

    var onClose: (() -> Void)?
}

/// The menu bar item and the panel it opens.
@MainActor
final class StatusItemController {
    private let model: AppModel
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
    private let panel: MenuPanel
    private let glass = NSGlassEffectView()
    private var contentSize = CGSize(width: PanelView.width, height: 200)
    private var outsideClickMonitor: Any?
    private var spaceObserver: NSObjectProtocol?
    private var frameObservers: [NSObjectProtocol] = []
    private var repositionScheduled = false

    private static let gapBelowMenuBar: CGFloat = 6
    private static let screenMargin: CGFloat = 8
    static let cornerRadius: CGFloat = 16

    init(model: AppModel) {
        self.model = model
        panel = MenuPanel(
            contentRect: NSRect(origin: .zero, size: contentSize),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: true
        )
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.level = .popUpMenu
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .transient, .ignoresCycle]
        panel.isReleasedWhenClosed = false
        panel.hidesOnDeactivate = false
        panel.animationBehavior = .utilityWindow
        panel.onClose = { [weak self] in self?.didClose() }

        glass.cornerRadius = Self.cornerRadius
        let root = PanelView(onSizeChange: { [weak self] size in self?.resize(to: size) })
            .environment(model)
        let hosting = NSHostingView(rootView: root)
        hosting.sizingOptions = []
        // Track the panel's size; otherwise the content keeps its old frame and gets cropped.
        hosting.frame = glass.bounds
        hosting.autoresizingMask = [.width, .height]
        glass.contentView = hosting
        panel.contentView = glass

        if let button = statusItem.button {
            button.target = self
            button.action = #selector(togglePanel)
            button.sendAction(on: [.leftMouseUp, .rightMouseUp])
        }
        model.onSnapshotChange = { [weak self] snapshot in self?.updateIcon(snapshot) }
        model.showPanel = { [weak self] in self?.show() }
        updateIcon(model.snapshot)
        // Anything else that moves or resizes the panel (AppKit, a display change) gets undone.
        for name in [NSWindow.didResizeNotification, NSWindow.didMoveNotification] {
            frameObservers.append(NotificationCenter.default.addObserver(forName: name, object: panel, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated {
                    guard let self, self.panel.isVisible, let target = self.targetFrame(), self.panel.frame != target else { return }
                    self.scheduleReposition()
                }
            })
        }
    }

    #if DEBUG
    func frameReport() -> String {
        "visible \(panel.isVisible) frame \(panel.frame) glass \(glass.frame) hosting \(glass.contentView?.frame ?? .zero) content \(contentSize)"
    }

    func debugToggle(_ ssid: String) { model.toggleExpanded(ssid) }

    /// Simulates something outside our code shrinking the panel while keeping its bottom edge.
    func debugShrink() {
        var frame = panel.frame
        frame.size.height = 315
        panel.setFrame(frame, display: true)
    }
    #endif

    // MARK: - Icon

    private func updateIcon(_ snapshot: WiFiSnapshot) {
        guard let button = statusItem.button else { return }
        let image: NSImage?
        let description: String
        if !model.hasLoaded {
            image = NSImage(systemSymbolName: "wifi", accessibilityDescription: nil)
            description = "Wi-Fi"
        } else if !snapshot.powerOn {
            image = NSImage(systemSymbolName: "wifi.slash", accessibilityDescription: nil)
            description = "Wi-Fi, off"
        } else if let link = snapshot.link {
            image = NSImage(
                systemSymbolName: "wifi",
                variableValue: link.quality.symbolVariableValue,
                accessibilityDescription: nil
            )
            description = "Wi-Fi, \(link.ssid ?? "connected"), \(link.quality.label) signal, \(link.rssi) dBm"
        } else {
            image = NSImage(systemSymbolName: "wifi.exclamationmark", accessibilityDescription: nil)
            description = "Wi-Fi, not connected"
        }
        image?.isTemplate = true
        button.image = image
        button.setAccessibilityLabel(description)
        button.toolTip = snapshot.link.map { "\($0.ssid ?? "Wi-Fi") · \($0.rssi) dBm" }
    }

    // MARK: - Panel

    @objc private func togglePanel() {
        if panel.isVisible { close() } else { show() }
    }

    func show() {
        guard !panel.isVisible else {
            panel.makeKey()
            return
        }
        // Apply SwiftUI changes made while hidden (e.g. a group expanded by a link) so the size is current.
        glass.contentView?.layoutSubtreeIfNeeded()
        position()
        panel.alphaValue = 1
        panel.makeKeyAndOrderFront(nil)
        // Setting the frame can itself trigger a SwiftUI layout that reports a new size while the panel
        // was still hidden; position again now that it's visible.
        position()
        statusItem.button?.highlight(true)
        model.isPanelVisible = true

        outsideClickMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown, .otherMouseDown]) { [weak self] _ in
            MainActor.assumeIsolated { self?.close() }
        }
        spaceObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.activeSpaceDidChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.close() }
        }
    }

    func close() {
        guard panel.isVisible else { return }
        panel.orderOut(nil)
        didClose()
    }

    private func didClose() {
        statusItem.button?.highlight(false)
        model.isPanelVisible = false
        model.credentialPrompt = nil
        if let outsideClickMonitor { NSEvent.removeMonitor(outsideClickMonitor) }
        outsideClickMonitor = nil
        if let spaceObserver { NSWorkspace.shared.notificationCenter.removeObserver(spaceObserver) }
        spaceObserver = nil
    }

    /// SwiftUI reports its ideal size; keep the panel's top edge pinned under the menu bar while it grows.
    private func resize(to size: CGSize) {
        let size = CGSize(width: ceil(size.width), height: ceil(size.height))
        guard size != contentSize, size.width > 0, size.height > 0 else { return }
        contentSize = size
        // This runs inside the hosting view's layout pass; changing the window frame there is re-entrant
        // and can leave the window and its content out of step. While hidden, `show()` applies the size.
        scheduleReposition()
    }

    private func scheduleReposition() {
        guard !repositionScheduled else { return }
        repositionScheduled = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            repositionScheduled = false
            if panel.isVisible { position() }
        }
    }

    #if DEBUG
    /// Opens the panel and saves what it draws as PNGs, in light and dark appearance. Debug builds only;
    /// used to check layout without Screen Recording permission. Glass doesn't render this way.
    func renderPanel(into directory: URL) {
        show()
        Task { @MainActor in
            for (name, appearance) in [("light", NSAppearance.Name.aqua), ("dark", .darkAqua)] {
                panel.appearance = NSAppearance(named: appearance)
                try? await Task.sleep(for: .milliseconds(600))
                let view = glass
                guard let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { continue }
                view.cacheDisplay(in: view.bounds, to: rep)
                let url = directory.appending(path: "panel-\(name).png")
                try? rep.representation(using: .png, properties: [:])?.write(to: url)
                log.info("Rendered panel to \(url.path, privacy: .public)")
            }
            panel.appearance = nil
        }
    }
    #endif

    private func position() {
        guard let target = targetFrame(), panel.frame != target else { return }
        panel.setFrame(target, display: panel.isVisible)
    }

    /// Under the status item, top pinned below the menu bar, never past the bottom of the screen.
    private func targetFrame() -> NSRect? {
        guard let button = statusItem.button, let buttonWindow = button.window else { return nil }
        let buttonFrame = buttonWindow.convertToScreen(button.convert(button.bounds, to: nil))
        let visible = (buttonWindow.screen ?? NSScreen.main)?.visibleFrame ?? .zero
        let top = min(buttonFrame.minY, visible.maxY) - Self.gapBelowMenuBar
        // The network list scrolls instead of running off the screen.
        let height = min(contentSize.height, top - visible.minY - Self.screenMargin)
        var x = buttonFrame.minX
        x = min(x, visible.maxX - contentSize.width - Self.screenMargin)
        x = max(x, visible.minX + Self.screenMargin)
        return NSRect(x: x, y: top - height, width: contentSize.width, height: height)
    }

    /// Room the network list may use: the screen under the menu bar, minus the rest of the panel.
    static func availableListHeight(otherContent: CGFloat) -> CGFloat {
        let visible = NSScreen.main?.visibleFrame.height ?? 800
        return max(200, visible - otherContent - gapBelowMenuBar - 2 * screenMargin)
    }
}
