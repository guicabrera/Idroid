import Foundation

/// Answers other devices' requests to browse this Mac's shared folders
/// (ADR-0004). The relay forwards each request over the websocket as an
/// "fs_request" event and pipes our POST /fs/respond back to the caller, so
/// this Mac never accepts inbound connections.
///
/// Only paths inside a shared folder resolve: ".." segments are rejected
/// and symlinks are followed before checking the result is still inside.
enum FileShareResponder {

    static func handle(_ event: NotifyEvent, folders: [SharedFolder], config: PairingConfig, apiClient: RelayAPIClient) async {
        guard let requestID = event.requestID, let op = event.op else { return }
        let path = event.path ?? "/"
        do {
            // Only reads are served from this Mac; "upload" is phone-side for now.
            guard op == "list" || op == "content" else { throw FSError.badRequest("\(op) is not supported by this device") }
            switch try resolve(path, folders: folders) {
            case .roots:
                guard op == "list" else { throw FSError.badRequest("not a file") }
                let entries = folders.map { FSEntry(name: $0.name, path: "/" + $0.name, isDir: true, size: 0, modified: nil, mime: nil) }
                try await respondJSON(entries, requestID: requestID, config: config, apiClient: apiClient)
            case .item(let url, let virtualPath):
                var isDir: ObjCBool = false
                guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir) else { throw FSError.notFound }
                if op == "list" {
                    guard isDir.boolValue else { throw FSError.badRequest("not a folder") }
                    let entries = try list(url, virtualPath: virtualPath)
                    try await respondJSON(entries, requestID: requestID, config: config, apiClient: apiClient)
                } else {
                    guard !isDir.boolValue else { throw FSError.badRequest("is a folder") }
                    try await apiClient.respondFS(config, requestID: requestID, contentType: RelayAPIClient.mimeType(for: url), fileURL: url)
                }
            }
        } catch let error as FSError {
            try? await apiClient.respondFS(config, requestID: requestID, status: error.status, contentType: "text/plain", body: Data(error.message.utf8))
        } catch {
            try? await apiClient.respondFS(config, requestID: requestID, status: 500, contentType: "text/plain", body: Data(error.localizedDescription.utf8))
        }
    }

    enum Resolved: Equatable {
        case roots
        case item(URL, virtualPath: String)
    }

    enum FSError: Error, Equatable {
        case notFound
        case badRequest(String)

        var status: Int {
            switch self {
            case .notFound: return 404
            case .badRequest: return 400
            }
        }

        var message: String {
            switch self {
            case .notFound: return "not found"
            case .badRequest(let message): return message
            }
        }
    }

    static func resolve(_ path: String, folders: [SharedFolder]) throws -> Resolved {
        let segments = path.split(separator: "/").map(String.init)
        guard let first = segments.first else { return .roots }
        if segments.contains(where: { $0 == "." || $0 == ".." }) { throw FSError.badRequest("invalid path") }
        guard let folder = folders.first(where: { $0.name == first }) else { throw FSError.notFound }

        let root = folder.url.resolvingSymlinksInPath().standardizedFileURL
        var url = root
        for segment in segments.dropFirst() { url.appendPathComponent(segment) }
        url = url.resolvingSymlinksInPath().standardizedFileURL
        guard url.path == root.path || url.path.hasPrefix(root.path + "/") else { throw FSError.notFound }
        return .item(url, virtualPath: "/" + segments.joined(separator: "/"))
    }

    private static func list(_ dir: URL, virtualPath: String) throws -> [FSEntry] {
        let keys: [URLResourceKey] = [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey]
        let urls = try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles])
        let formatter = ISO8601DateFormatter()
        return urls.compactMap { url -> FSEntry? in
            guard let values = try? url.resourceValues(forKeys: Set(keys)) else { return nil }
            let isDir = values.isDirectory ?? false
            return FSEntry(
                name: url.lastPathComponent,
                path: virtualPath + "/" + url.lastPathComponent,
                isDir: isDir,
                size: Int64(values.fileSize ?? 0),
                modified: values.contentModificationDate.map(formatter.string(from:)),
                mime: isDir ? nil : RelayAPIClient.mimeType(for: url)
            )
        }
        .sorted { a, b in
            if a.isDir != b.isDir { return a.isDir }
            return a.name.localizedStandardCompare(b.name) == .orderedAscending
        }
    }

    private static func respondJSON(_ entries: [FSEntry], requestID: String, config: PairingConfig, apiClient: RelayAPIClient) async throws {
        let body = try JSONEncoder().encode(FSListing(entries: entries))
        try await apiClient.respondFS(config, requestID: requestID, status: 200, contentType: "application/json", body: body)
    }
}
