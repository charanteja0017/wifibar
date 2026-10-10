#if DEBUG
import AppKit
import SwiftUI
import WidgetKit
import WiFiCore
import WiFiWidgetUI

/// Saves the desktop widget's three sizes as PNGs (debug builds only) to check layout without
/// adding the widget. Links render as plain content and there's no desktop tint.
@MainActor
enum WidgetPreviewRenderer {
    static func render(_ snapshot: WiFiSnapshot) {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appending(path: "WiFiBar")
        // Sizes chronod reports on this Mac (log: "WiFiBarSignal:systemLarge::344.00/344.00/27.88").
        let sizes: [(String, WidgetFamily, CGSize)] = [
            ("small", .systemSmall, CGSize(width: 164, height: 164)),
            ("medium", .systemMedium, CGSize(width: 344, height: 164)),
            ("large", .systemLarge, CGSize(width: 344, height: 344)),
        ]
        for appearance in [NSAppearance.Name.aqua, .darkAqua] {
            for (name, family, size) in sizes {
                // Fixed outer size with clipping, so content that doesn't fit shows up as cut off, as on the desktop.
                let view = WiFiWidgetView(snapshot: snapshot, family: family, linksEnabled: false)
                    .padding(16)
                    .frame(width: size.width, height: size.height)
                    .clipped()
                    .background(Color(nsColor: .windowBackgroundColor), in: .rect(cornerRadius: 27.88))
                    .environment(\.colorScheme, appearance == .darkAqua ? .dark : .light)
                let renderer = ImageRenderer(content: view)
                renderer.scale = 2
                guard let image = renderer.nsImage, let tiff = image.tiffRepresentation,
                      let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) else { continue }
                let suffix = appearance == .darkAqua ? "dark" : "light"
                try? png.write(to: directory.appending(path: "widget-\(name)-\(suffix).png"))
            }
        }
        log.notice("Rendered widget previews to \(directory.path, privacy: .public)")
    }
}
#endif
