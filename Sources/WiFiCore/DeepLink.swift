import Foundation

/// `wifibar://` URLs the widget uses to drive the app.
///
/// Joining a network is a side effect any web page could otherwise trigger by opening a URL,
/// so `connect` links must carry the token the app published in its snapshot.
public enum DeepLink: Equatable, Sendable {
    /// Show the Wi-Fi panel. Harmless, so it needs no token.
    case open
    /// Join `ssid`, on the specific access point `bssid` when given (otherwise the strongest one).
    case connect(ssid: String, bssid: String?)

    public static let scheme = "wifibar"

    public enum ParseError: Error, Equatable {
        case wrongScheme
        case unknownAction(String)
        case missingSSID
        case badToken
    }

    public func url(token: String?) -> URL {
        var components = URLComponents()
        components.scheme = Self.scheme
        switch self {
        case .open:
            components.host = "open"
        case let .connect(ssid, bssid):
            components.host = "connect"
            var items = [URLQueryItem(name: "ssid", value: ssid)]
            if let bssid { items.append(URLQueryItem(name: "bssid", value: bssid)) }
            if let token { items.append(URLQueryItem(name: "token", value: token)) }
            components.queryItems = items
        }
        return components.url!
    }

    public static func parse(_ url: URL, expectedToken: String) -> Result<DeepLink, ParseError> {
        guard url.scheme?.lowercased() == scheme else { return .failure(.wrongScheme) }
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let query = components?.queryItems ?? []
        func value(_ name: String) -> String? {
            query.first { $0.name == name }?.value.flatMap { $0.isEmpty ? nil : $0 }
        }

        switch url.host?.lowercased() {
        case "open":
            return .success(.open)
        case "connect":
            guard let token = value("token"), constantTimeEquals(token, expectedToken) else {
                return .failure(.badToken)
            }
            guard let ssid = value("ssid") else { return .failure(.missingSSID) }
            return .success(.connect(ssid: ssid, bssid: value("bssid")))
        case let other:
            return .failure(.unknownAction(other ?? ""))
        }
    }

    public static func makeToken() -> String {
        var generator = SystemRandomNumberGenerator()
        return (0..<4).map { _ in String(generator.next() as UInt64, radix: 16) }.joined()
    }

    private static func constantTimeEquals(_ a: String, _ b: String) -> Bool {
        let lhs = Array(a.utf8), rhs = Array(b.utf8)
        guard lhs.count == rhs.count, !rhs.isEmpty else { return false }
        return zip(lhs, rhs).reduce(0) { $0 | ($1.0 ^ $1.1) } == 0
    }
}
