import Foundation

/// API name and token from https://wigle.net/account, sent with HTTP Basic auth.
public struct WiGLECredentials: Sendable, Equatable {
    public var name: String
    public var token: String

    public init(name: String, token: String) {
        self.name = name
        self.token = token
    }

    var authorizationHeader: String {
        "Basic " + Data("\(name):\(token)".utf8).base64EncodedString()
    }
}

/// One network record from WiGLE's `GET /api/v2/network/search` (`WiFiNetwork` in its API spec).
public struct WiGLENetwork: Decodable, Sendable, Equatable {
    public let trilat: Double?
    public let trilong: Double?
    public let ssid: String?
    public let netid: String?
    public let lastupdt: String?
    public let housenumber: String?
    public let road: String?
    public let city: String?
    public let region: String?
    public let country: String?

    public var hasLocation: Bool { trilat != nil && trilong != nil }

    /// "12 Main St, Springfield, IL, US" from whatever parts WiGLE has.
    public var address: String? {
        let street = [housenumber, road].compactMap { $0?.isEmpty == false ? $0 : nil }.joined(separator: " ")
        let parts = [street, city, region, country].compactMap { $0?.isEmpty == false ? $0 : nil }
        return parts.isEmpty ? nil : parts.joined(separator: ", ")
    }

    /// The date part of `lastupdt`, e.g. "2024-05-12".
    public var lastUpdated: String? {
        lastupdt.map { String($0.prefix(10)) }
    }
}

public enum WiGLEError: LocalizedError, Equatable {
    case unauthorized
    case rateLimited
    case failed(String)

    public var errorDescription: String? {
        switch self {
        case .unauthorized: "WiGLE didn’t accept the API name and token. Check them at wigle.net/account."
        case .rateLimited: "You’ve reached WiGLE’s query limit for today. Try again tomorrow."
        case let .failed(message): message
        }
    }
}

public struct WiGLEClient: Sendable {
    public let credentials: WiGLECredentials
    private let session: URLSession

    public init(credentials: WiGLECredentials, session: URLSession = .shared) {
        self.credentials = credentials
        self.session = session
    }

    public static func searchURL(bssid: String) -> URL {
        var components = URLComponents(string: "https://api.wigle.net/api/v2/network/search")!
        components.queryItems = [URLQueryItem(name: "netid", value: BSSID.normalize(bssid))]
        return components.url!
    }

    /// The best located match for this BSSID, or nil when nobody has mapped it.
    public func lookUp(bssid: String) async throws -> WiGLENetwork? {
        var request = URLRequest(url: Self.searchURL(bssid: bssid))
        request.setValue(credentials.authorizationHeader, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        return try Self.bestMatch(in: Self.decode(data, status: status), bssid: bssid)
    }

    static func decode(_ data: Data, status: Int) throws -> [WiGLENetwork] {
        switch status {
        case 401, 403: throw WiGLEError.unauthorized
        case 429: throw WiGLEError.rateLimited
        case 200..<300: break
        default:
            throw WiGLEError.failed("WiGLE returned HTTP \(status).")
        }
        struct Response: Decodable {
            let success: Bool?
            let results: [WiGLENetwork]?
            let message: String?
        }
        let response: Response
        do {
            response = try JSONDecoder().decode(Response.self, from: data)
        } catch {
            throw WiGLEError.failed("WiGLE sent a response this app couldn’t read.")
        }
        if response.success == false {
            let message = response.message ?? "WiGLE couldn’t complete the search."
            if message.localizedCaseInsensitiveContains("too many") { throw WiGLEError.rateLimited }
            throw WiGLEError.failed(message)
        }
        return response.results ?? []
    }

    static func bestMatch(in results: [WiGLENetwork], bssid: String) -> WiGLENetwork? {
        // Only an exact BSSID match counts; a neighbour's record would put the pin in the wrong place.
        let wanted = BSSID.normalize(bssid)
        return results.first { $0.hasLocation && $0.netid.map(BSSID.normalize) == wanted }
    }
}
