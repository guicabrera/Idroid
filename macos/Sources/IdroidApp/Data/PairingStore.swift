import Foundation
import Security

/// This Mac's identity on the relay. Who it can send to isn't stored here:
/// that's every other device in its group, fetched from GET /devices, so
/// pairing more devices never needs a new config. (Configs saved by older
/// builds also carry a recipient id/name; the decoder just ignores them.)
struct PairingConfig: Codable, Equatable {
    var relayBaseURL: String
    var deviceToken: String
}

/// Persists `PairingConfig` in the macOS Keychain, mirroring the Android
/// client's use of EncryptedSharedPreferences (PairingRepository.kt) — the
/// device token is a long-lived credential equivalent to a password.
final class PairingStore {

    private let service = "com.idroid.app.pairing"
    private let account = "default"

    func load() -> PairingConfig? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return try? JSONDecoder().decode(PairingConfig.self, from: data)
    }

    func save(_ config: PairingConfig) {
        guard let data = try? JSONEncoder().encode(config) else { return }
        let baseQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(baseQuery as CFDictionary)

        var attributes = baseQuery
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(attributes as CFDictionary, nil)
    }

    func clear() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
