import Foundation
import Security

/// What this Mac needs to talk to the relay and to one paired recipient
/// (per ADR-0001, pairing itself is manual for now: an admin mints both
/// device tokens via `POST /admin/devices` and hands them over out of band;
/// there's no in-app QR/PIN flow yet — same gap as the Android client).
struct PairingConfig: Codable, Equatable {
    var relayBaseURL: String
    var deviceToken: String
    var recipientDeviceID: String
    var recipientName: String
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
