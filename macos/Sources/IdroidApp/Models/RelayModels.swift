import Foundation

/// Mirrors the relay's ItemMeta JSON (see relay/internal/relay/store.go) and
/// the Android client's ItemMeta (android/.../data/RelayModels.kt) — kept
/// field-for-field identical so neither client needs to translate between
/// two shapes of the same concept. Timestamps stay as strings: neither
/// client needs to do date arithmetic on them, only display/sort.
struct ItemMeta: Codable, Identifiable, Equatable {
    let id: String
    let senderID: String
    let recipientID: String
    let kind: String
    let filename: String?
    let contentType: String
    let size: Int64
    let createdAt: String
    let expiresAt: String

    enum CodingKeys: String, CodingKey {
        case id
        case senderID = "sender_id"
        case recipientID = "recipient_id"
        case kind
        case filename
        case contentType = "content_type"
        case size
        case createdAt = "created_at"
        case expiresAt = "expires_at"
    }
}

struct NotifyEvent: Codable {
    let event: String
    let itemID: String

    enum CodingKeys: String, CodingKey {
        case event
        case itemID = "item_id"
    }
}

enum ItemKind {
    static let file = "file"
    static let text = "text"
    static let clipboard = "clipboard"
}

/// PIN pairing (relay/README.md#api): one device calls /pair/start and
/// shows the resulting code, the other calls /pair/claim with it. Mirrors
/// the relay's JSON field-for-field, same as the Android client's
/// equivalent types in RelayModels.kt.
struct DeviceInfo: Codable {
    let id: String
    let name: String
    let token: String
    let createdAt: String

    enum CodingKeys: String, CodingKey {
        case id, name, token
        case createdAt = "created_at"
    }
}

struct PairingInfo: Codable {
    let sessionID: String
    let code: String
    let expiresAt: String

    enum CodingKeys: String, CodingKey {
        case sessionID = "session_id"
        case code
        case expiresAt = "expires_at"
    }
}

struct PairStartResponse: Codable {
    let device: DeviceInfo
    let pairing: PairingInfo
}

struct PairPeer: Codable {
    let id: String
    let name: String
}

struct PairClaimResponse: Codable {
    let device: DeviceInfo
    let peer: PairPeer
}

struct PairStatusResponse: Codable {
    let claimed: Bool
    let peer: PairPeer?
}
