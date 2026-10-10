import os
import SwiftUI
import WidgetKit
import WiFiCore
import WiFiWidgetUI

private let log = Logger(subsystem: "com.charan.wifibar", category: "widget")

@main
struct WiFiBarWidgets: WidgetBundle {
    var body: some Widget {
        WiFiSignalWidget()
    }
}

struct WiFiSignalWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WiFiWidgetKind.signal, provider: SnapshotProvider()) { entry in
            FamilyView(snapshot: entry.snapshot)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Wi-Fi Signal")
        .description("Signal strength of your network and the access points around you.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

private struct FamilyView: View {
    let snapshot: WiFiSnapshot?
    @Environment(\.widgetFamily) private var family

    var body: some View {
        WiFiWidgetView(snapshot: snapshot, family: family)
    }
}

struct SnapshotEntry: TimelineEntry {
    let date: Date
    let snapshot: WiFiSnapshot?
}

/// The menu bar app does the scanning and writes `snapshot.json`; the widget only reads it.
/// The app asks WidgetKit to reload when something changes; this fallback refresh keeps the age honest.
struct SnapshotProvider: TimelineProvider {
    func placeholder(in context: Context) -> SnapshotEntry {
        SnapshotEntry(date: .now, snapshot: .preview)
    }

    func getSnapshot(in context: Context, completion: @escaping (SnapshotEntry) -> Void) {
        let snapshot = load()
        completion(SnapshotEntry(date: .now, snapshot: context.isPreview ? (snapshot ?? .preview) : snapshot))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SnapshotEntry>) -> Void) {
        let entry = SnapshotEntry(date: .now, snapshot: load())
        completion(Timeline(entries: [entry], policy: .after(.now.addingTimeInterval(15 * 60))))
    }

    private func load() -> WiFiSnapshot? {
        let store = SnapshotStore.applicationSupport
        do {
            let snapshot = try store.read()
            log.notice("Widget loaded snapshot from \(snapshot.date.formatted(.iso8601), privacy: .public)")
            return snapshot
        } catch {
            log.error("Widget can't read \(store.url.path, privacy: .public): \(error.localizedDescription, privacy: .public)")
            return nil
        }
    }
}
