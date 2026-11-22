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

/// Pushed over the websocket: "new_item" (refetch GET /inbox),
/// "devices_changed" (refetch GET /devices), or "fs_request" (another
/// device is browsing this Mac's shared folders; answer with
/// POST /fs/respond/{request_id}, see ADR-0004).
struct NotifyEvent: Codable {
    let event: String
    let itemID: String?
    let requestID: String?
    let op: String?
    let path: String?
    let senderID: String?

    enum CodingKeys: String, CodingKey {
        case event
        case itemID = "item_id"
        case requestID = "request_id"
        case op, path
        case senderID = "sender_id"
    }
}

/// One entry of a remote folder listing (GET /fs/{device}/list), the same
/// JSON both apps produce when answering an fs_request. `path` is absolute
/// within the serving device's shares: "/" lists the shared folders
/// themselves, "/Documents/a.pdf" is a file inside the share "Documents".
struct FSEntry: Codable, Identifiable, Hashable {
    let name: String
    let path: String
    let isDir: Bool
    let size: Int64
    let modified: String?
    let mime: String?

    var id: String { path }

    enum CodingKeys: String, CodingKey {
        case name, path, size, modified, mime
        case isDir = "is_dir"
    }
}

struct FSListing: Codable {
    let entries: [FSEntry]
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

/// One device in this device's group (GET /devices) — everything it can send to.
struct PeerDevice: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let createdAt: String
    let online: Bool

    enum CodingKeys: String, CodingKey {
        case id, name, online
        case createdAt = "created_at"
    }
}

struct DevicesResponse: Codable {
    let me: PeerDevice
    let devices: [PeerDevice]

    enum CodingKeys: String, CodingKey {
        case me = "self"
        case devices
    }
}
