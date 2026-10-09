import Foundation

/// Where received files land when the user hits "Save" on an inbox item —
/// the macOS counterpart to SaveLocationRepository.kt. Downloads unless the
/// user picks otherwise; "Save As…" always asks regardless of this setting.
enum SaveLocation: Equatable {
    case downloads
    case folder(URL)
    case askEachTime

    static var downloadsURL: URL {
        FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Downloads")
    }

    /// The folder to save into without asking, or nil when the user must pick one.
    var resolvedFolder: URL? {
        switch self {
        case .downloads: return Self.downloadsURL
        case .folder(let url): return url
        case .askEachTime: return nil
        }
    }

    var displayName: String {
        switch self {
        case .downloads: return "Downloads"
        case .folder(let url): return url.lastPathComponent
        case .askEachTime: return "Ask each time"
        }
    }
}

/// Persists `SaveLocation` in UserDefaults (not a secret, unlike the
/// pairing). The app isn't sandboxed, so a plain path is enough — no
/// security-scoped bookmark needed.
final class SaveLocationStore {

    private let defaults = UserDefaults.standard
    private let modeKey = "saveLocation.mode"
    private let pathKey = "saveLocation.folderPath"

    func load() -> SaveLocation {
        switch defaults.string(forKey: modeKey) {
        case "folder":
            if let path = defaults.string(forKey: pathKey) {
                return .folder(URL(fileURLWithPath: path, isDirectory: true))
            }
            return .downloads
        case "ask":
            return .askEachTime
        default:
            return .downloads
        }
    }

    func save(_ location: SaveLocation) {
        switch location {
        case .downloads:
            defaults.set("downloads", forKey: modeKey)
        case .folder(let url):
            defaults.set("folder", forKey: modeKey)
            defaults.set(url.path, forKey: pathKey)
        case .askEachTime:
            defaults.set("ask", forKey: modeKey)
        }
    }

    /// The last custom folder picked, kept even after switching back to
    /// Downloads so the user can return to it in one click.
    var lastCustomFolder: URL? {
        defaults.string(forKey: pathKey).map { URL(fileURLWithPath: $0, isDirectory: true) }
    }
}
