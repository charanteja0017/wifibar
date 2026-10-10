import AppKit
import SwiftUI

/// The standalone "Locate" window. One at a time; opening another access point replaces it.
@MainActor
final class LocatorWindowController: NSObject, NSWindowDelegate {
    private let service: WiFiService
    private var window: NSWindow?
    private var model: LocatorModel?

    init(service: WiFiService) {
        self.service = service
    }

    func show(_ target: LocateTarget) {
        if let window, let model, model.target == target {
            NSApp.activate()
            window.makeKeyAndOrderFront(nil)
            return
        }
        model?.stop()
        let model = LocatorModel(target: target, service: service)
        self.model = model

        let window = self.window ?? makeWindow()
        window.contentView = NSHostingView(rootView: LocatorView(model: model))
        window.title = "Locate “\(target.ssid)”"
        window.subtitle = target.bssid
        if self.window == nil, !window.setFrameUsingName(Self.autosaveName) { window.center() }
        self.window = window

        model.start()
        NSApp.activate()
        window.makeKeyAndOrderFront(nil)
    }

    private static let autosaveName = "LocatorWindow"

    private func makeWindow() -> NSWindow {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 440, height: 760),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.minSize = NSSize(width: 400, height: 480)
        window.setFrameAutosaveName(Self.autosaveName)
        return window
    }

    #if DEBUG
    /// Saves what the window draws (debug builds only; no Screen Recording permission needed).
    func render(to url: URL, height: CGFloat? = nil) {
        guard let window else { return }
        if let height {
            // Tall enough to show every section without scrolling.
            window.setContentSize(NSSize(width: window.contentLayoutRect.width, height: height))
            window.contentView?.layoutSubtreeIfNeeded()
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            guard let view = window.contentView, let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { return }
            view.cacheDisplay(in: view.bounds, to: rep)
            try? rep.representation(using: .png, properties: [:])?.write(to: url)
            log.notice("Rendered locator to \(url.path, privacy: .public)")
        }
    }

    func close() { window?.close() }
    func startSweep() { model?.startSweep() }
    func finishSweep() { model?.finishSweep() }
    var sweepReport: String {
        guard let model else { return "no locator" }
        return "phase \(model.sweepPhase) readings \(model.sweep.readings.count) yaw \(model.motion.yaw) running \(model.motion.isRunning)"
    }
    #endif

    func windowWillClose(_ notification: Notification) {
        // Stop scanning as soon as the window goes away; scans slow the Wi-Fi connection.
        model?.stop()
        model = nil
    }
}
