import Foundation

/// Reads and writes a `WiFiSnapshot` as JSON. The app writes it; the widget reads it.
public struct SnapshotStore: Sendable {
    public let url: URL

    public init(url: URL) {
        self.url = url
    }

    /// `~/Library/Application Support/WiFiBar/snapshot.json` in the user's real home folder.
    /// The app writes it; the sandboxed widget reads it through a read-only entitlement for that folder.
    /// (Inside a sandbox, `FileManager`'s Application Support points into the container, so use the
    /// account's home directory instead.)
    public static var applicationSupport: SnapshotStore {
        let home = getpwuid(getuid()).flatMap { $0.pointee.pw_dir.map { String(cString: $0) } } ?? NSHomeDirectory()
        return SnapshotStore(url: URL(filePath: home).appending(path: "Library/Application Support/WiFiBar/snapshot.json"))
    }

    /// The shared App Group container, when the process has that entitlement.
    public static func appGroup(_ identifier: String) -> SnapshotStore? {
        FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: identifier)
            .map { SnapshotStore(url: $0.appending(path: "snapshot.json")) }
    }

    public func write(_ snapshot: WiFiSnapshot) throws {
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try Self.encoder.encode(snapshot).write(to: url, options: .atomic)
    }

    public func read() throws -> WiFiSnapshot {
        try Self.decoder.decode(WiFiSnapshot.self, from: Data(contentsOf: url))
    }

    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return encoder
    }()

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }()
}
