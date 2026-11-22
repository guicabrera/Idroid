import Foundation

/// A folder on this Mac that paired devices may browse (ADR-0004), shown to
/// them under `name`.
struct SharedFolder: Identifiable, Equatable {
    let name: String
    let url: URL

    var id: String { url.path }
}

/// Persists the folders this Mac shares, in UserDefaults like
/// `SaveLocationStore`: the app isn't sandboxed, so plain paths are enough.
final class SharedFoldersStore {

    private let defaults = UserDefaults.standard
    private let key = "sharing.folderPaths"

    func load() -> [SharedFolder] {
        Self.named(paths: defaults.stringArray(forKey: key) ?? [])
    }

    func save(_ folders: [SharedFolder]) {
        defaults.set(folders.map(\.url.path), forKey: key)
    }

    /// Names are the folder's own name, suffixed " 2", " 3"… when two shared
    /// folders have the same name, so each is one unambiguous path segment.
    static func named(paths: [String]) -> [SharedFolder] {
        var used = Set<String>()
        return paths.map { path in
            let url = URL(fileURLWithPath: path, isDirectory: true)
            let base = url.lastPathComponent.isEmpty ? "Mac" : url.lastPathComponent
            var name = base
            var n = 2
            while used.contains(name) {
                name = "\(base) \(n)"
                n += 1
            }
            used.insert(name)
            return SharedFolder(name: name, url: url)
        }
    }
}
