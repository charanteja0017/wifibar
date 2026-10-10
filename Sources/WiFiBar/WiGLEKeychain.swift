import Foundation
import Security
import WiFiCore

/// Stores the WiGLE API name and token in the login keychain.
enum WiGLEKeychain {
    private static let service = "com.charan.wifibar.wigle"

    static func load() -> WiGLECredentials? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnAttributes as String: true,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let attributes = item as? [String: Any],
              let name = attributes[kSecAttrAccount as String] as? String,
              let data = attributes[kSecValueData as String] as? Data,
              let token = String(data: data, encoding: .utf8)
        else { return nil }
        return WiGLECredentials(name: name, token: token)
    }

    static func save(_ credentials: WiGLECredentials) throws {
        delete()
        let item: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: credentials.name,
            kSecAttrLabel as String: "WiFiBar WiGLE API token",
            kSecValueData as String: Data(credentials.token.utf8),
        ]
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status), userInfo: [
                NSLocalizedDescriptionKey: SecCopyErrorMessageString(status, nil) as String? ?? "Couldn’t save to the keychain.",
            ])
        }
    }

    static func delete() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
