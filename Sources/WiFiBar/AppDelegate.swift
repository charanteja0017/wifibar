import AppKit

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var model: AppModel?
    private var statusController: StatusItemController?
    private var locator: LocatorWindowController?
    /// URLs that arrive before launch finishes (when a widget link launches the app).
    private var pendingURLs: [URL] = []

    func applicationDidFinishLaunching(_ notification: Notification) {
        let model = AppModel()
        self.model = model
        statusController = StatusItemController(model: model)
        let locator = LocatorWindowController(service: model.service)
        self.locator = locator
        model.openLocator = { [weak self] target in
            self?.statusController?.close()
            locator.show(target)
        }
        model.start()
        pendingURLs.forEach(model.handle)
        pendingURLs = []
    }

    func application(_ application: NSApplication, open urls: [URL]) {
        guard let model else {
            pendingURLs += urls
            return
        }
        #if DEBUG
        if let url = urls.first, url.host == "debug-render" {
            let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appending(path: "WiFiBar")
            if url.query()?.contains("expand") == true {
                model.expandedGroups = Set(model.snapshot.groups.filter { $0.accessPoints.count > 1 }.prefix(2).map(\.ssid))
            }
            statusController?.renderPanel(into: directory)
            return
        }
        if let url = urls.first, let host = url.host, host.hasPrefix("debug-") {
            switch host {
            case "debug-open": statusController?.show()
            case "debug-close": statusController?.close()
            case "debug-shrink": statusController?.debugShrink()
            case "debug-locate":
                // Locate the joined AP, or the strongest AP of the first group.
                let other = model.snapshot.groups.first { !$0.isCurrent }.map { LocateTarget(ssid: $0.ssid, accessPoint: $0.strongest) }
                let target = url.query()?.contains("other") == true ? other
                    : model.snapshot.link.flatMap(LocateTarget.init(link:)) ?? other
                if let target { model.locate(target) }
            case "debug-locate-render":
                let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                let tall = url.query()?.contains("tall") == true
                locator?.render(to: directory.appending(path: "WiFiBar/locator.png"), height: tall ? 1250 : nil)
            case "debug-locate-close": locator?.close()
            case "debug-sweep-start": locator?.startSweep()
            case "debug-sweep-done": locator?.finishSweep()
            case "debug-widget-render":
                WidgetPreviewRenderer.render(model.snapshot)
            case "debug-sweep-report": log.notice("sweep: \(self.locator?.sweepReport ?? "-", privacy: .public)")
            case "debug-toggle":
                let ssid = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "ssid" }?.value
                    ?? model.snapshot.groups.first { $0.accessPoints.count > 1 }?.ssid ?? ""
                statusController?.debugToggle(ssid)
            default: break
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
                log.notice("after \(host, privacy: .public): \(self?.statusController?.frameReport() ?? "-", privacy: .public)")
            }
            return
        }
        #endif
        urls.forEach(model.handle)
    }
}
