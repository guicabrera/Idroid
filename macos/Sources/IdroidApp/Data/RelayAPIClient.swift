import Foundation
import UniformTypeIdentifiers

enum RelayError: Error, LocalizedError {
    case network(String)
    case http(Int)
    /// An error status with a message meant for the user (e.g. from the device answering an upload).
    case rejected(Int, String)
    case emptyBody
    case invalidResponse

    var errorDescription: String? {
        switch self {
        case .network(let message): return "network error: \(message)"
        case .http(let code): return "request failed (\(code))"
        case .rejected(_, let message): return message
        case .emptyBody: return "empty response body"
        case .invalidResponse: return "invalid response"
        }
    }
}

struct DownloadResult {
    let fileURL: URL
    let contentType: String
    let filename: String?
}

/// Talks to the relay API described in relay/README.md — the macOS
/// counterpart to RelayApiClient.kt on Android. One instance is shared
/// app-wide (see AppState); it's stateless beyond URLSession's own
/// connection pool.
final class RelayAPIClient {

    private let session: URLSession

    init() {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 30
        session = URLSession(configuration: configuration)
    }

    /// Begins a pairing session on `relayBaseURL`; no device token exists
    /// yet, so this call is unauthenticated.
    /// Pass `token` when this Mac is already paired, so it brings the other
    /// device into its group under the same identity instead of minting a new one.
    func startPairing(relayBaseURL: String, name: String, token: String? = nil) async throws -> PairStartResponse {
        let request = jsonRequest(relayBaseURL: relayBaseURL, path: "/pair/start", body: ["name": name], token: token)
        let data = try await execute(request)
        return try JSONDecoder().decode(PairStartResponse.self, from: data)
    }

    /// Claims a pairing code shown by another device's `startPairing` call.
    func claimPairing(relayBaseURL: String, code: String, name: String, token: String? = nil) async throws -> PairClaimResponse {
        let request = jsonRequest(relayBaseURL: relayBaseURL, path: "/pair/claim", body: ["code": code, "name": name], token: token)
        let data = try await execute(request)
        return try JSONDecoder().decode(PairClaimResponse.self, from: data)
    }

    /// Polled by the device that called `startPairing` to learn when the
    /// other side has claimed the code.
    func pollPairingStatus(relayBaseURL: String, sessionID: String) async throws -> PairStatusResponse {
        let base = relayBaseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let request = URLRequest(url: URL(string: base + "/pair/status/" + sessionID)!)
        let data = try await execute(request)
        return try JSONDecoder().decode(PairStatusResponse.self, from: data)
    }

    private func jsonRequest(relayBaseURL: String, path: String, body: [String: String], token: String? = nil) -> URLRequest {
        let base = relayBaseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        var request = URLRequest(url: URL(string: base + path)!)
        request.httpMethod = "POST"
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        return request
    }

    /// This Mac plus every other device in its group.
    func listDevices(_ config: PairingConfig) async throws -> DevicesResponse {
        let data = try await execute(authedRequest(config, path: "/devices"))
        return try JSONDecoder().decode(DevicesResponse.self, from: data)
    }

    /// Unpairs a device in this Mac's group (or this Mac itself).
    func removeDevice(_ config: PairingConfig, deviceID: String) async throws {
        var request = authedRequest(config, path: "/devices/\(deviceID)")
        request.httpMethod = "DELETE"
        _ = try await execute(request, allowEmptyBody: true)
    }

    func listInbox(_ config: PairingConfig) async throws -> [ItemMeta] {
        let data = try await execute(authedRequest(config, path: "/inbox"))
        return try JSONDecoder().decode([ItemMeta].self, from: data)
    }

    func deleteItem(_ config: PairingConfig, itemID: String) async throws {
        var request = authedRequest(config, path: "/inbox/\(itemID)")
        request.httpMethod = "DELETE"
        _ = try await execute(request, allowEmptyBody: true)
    }

    /// Downloads straight to a file on disk (never buffers the payload
    /// fully in memory) and hands back a stable temp URL — the caller is
    /// responsible for moving it to its final destination.
    func downloadItem(_ config: PairingConfig, itemID: String) async throws -> DownloadResult {
        let request = authedRequest(config, path: "/inbox/\(itemID)/content")
        let (tempURL, response): (URL, URLResponse)
        do {
            (tempURL, response) = try await session.download(for: request)
        } catch {
            throw RelayError.network(error.localizedDescription)
        }
        guard let http = response as? HTTPURLResponse else { throw RelayError.invalidResponse }
        guard (200...299).contains(http.statusCode) else { throw RelayError.http(http.statusCode) }

        // URLSession deletes its own temp file once this call returns, so
        // move it to a stable location we control before handing it back.
        let stableURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.moveItem(at: tempURL, to: stableURL)

        let contentType = http.value(forHTTPHeaderField: "Content-Type") ?? "application/octet-stream"
        let filename = Self.parseFilename(http.value(forHTTPHeaderField: "Content-Disposition"))
        return DownloadResult(fileURL: stableURL, contentType: contentType, filename: filename)
    }

    func uploadText(_ config: PairingConfig, to recipientID: String, kind: String, text: String) async throws -> ItemMeta {
        try await uploadData(config, to: recipientID, kind: kind, data: Data(text.utf8), filename: "shared-text.txt", contentType: "text/plain")
    }

    /// In-memory upload for small payloads (text, clipboard images); files go through `uploadFile`.
    func uploadData(_ config: PairingConfig, to recipientID: String, kind: String, data: Data, filename: String, contentType: String) async throws -> ItemMeta {
        let boundary = "idroid-\(UUID().uuidString)"
        var body = Data()
        appendFormField(&body, boundary: boundary, name: "recipient", value: recipientID)
        appendFormField(&body, boundary: boundary, name: "kind", value: kind)
        appendFormField(&body, boundary: boundary, name: "filename", value: filename)
        appendFormField(&body, boundary: boundary, name: "content_type", value: contentType)
        appendFilePartHeader(&body, boundary: boundary, name: "payload", filename: filename, contentType: contentType)
        body.append(data)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))

        var request = authedRequest(config, path: "/upload")
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        let data = try await execute(request)
        return try JSONDecoder().decode(ItemMeta.self, from: data)
    }

    /// Streams the source file into a composed multipart body on disk, then
    /// uploads that file — avoids loading the whole file into memory,
    /// mirroring the streaming `ContentUriRequestBody` on the Android side.
    func uploadFile(_ config: PairingConfig, to recipientID: String, fileURL: URL) async throws -> ItemMeta {
        let filename = fileURL.lastPathComponent
        let contentType = Self.mimeType(for: fileURL)
        let boundary = "idroid-\(UUID().uuidString)"

        let composedURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        guard FileManager.default.createFile(atPath: composedURL.path, contents: nil),
              let output = try? FileHandle(forWritingTo: composedURL) else {
            throw RelayError.network("could not create upload buffer")
        }
        defer {
            try? output.close()
            try? FileManager.default.removeItem(at: composedURL)
        }

        var preamble = Data()
        appendFormField(&preamble, boundary: boundary, name: "recipient", value: recipientID)
        appendFormField(&preamble, boundary: boundary, name: "kind", value: ItemKind.file)
        appendFormField(&preamble, boundary: boundary, name: "filename", value: filename)
        appendFormField(&preamble, boundary: boundary, name: "content_type", value: contentType)
        appendFilePartHeader(&preamble, boundary: boundary, name: "payload", filename: filename, contentType: contentType)
        try output.write(contentsOf: preamble)

        guard let input = try? FileHandle(forReadingFrom: fileURL) else {
            throw RelayError.network("cannot open \(fileURL.path)")
        }
        defer { try? input.close() }
        while true {
            let chunk = try input.read(upToCount: 1 << 20) ?? Data()
            if chunk.isEmpty { break }
            try output.write(contentsOf: chunk)
        }
        try output.write(contentsOf: Data("\r\n--\(boundary)--\r\n".utf8))
        try output.close()

        var request = authedRequest(config, path: "/upload")
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.upload(for: request, fromFile: composedURL)
        } catch {
            throw RelayError.network(error.localizedDescription)
        }
        try Self.validate(response)
        return try JSONDecoder().decode(ItemMeta.self, from: data)
    }

    // MARK: - Live file browsing (ADR-0004)

    /// Lists a folder shared by another device; "/" lists its shared folders.
    func listRemote(_ config: PairingConfig, deviceID: String, path: String) async throws -> [FSEntry] {
        let data = try await execute(fsRequest(config, deviceID: deviceID, op: "list", path: path))
        return try JSONDecoder().decode(FSListing.self, from: data).entries
    }

    /// Downloads a file from another device's shared folder to a temp file
    /// the caller moves to its final destination.
    func downloadRemote(_ config: PairingConfig, deviceID: String, path: String) async throws -> URL {
        let request = fsRequest(config, deviceID: deviceID, op: "content", path: path)
        let (tempURL, response): (URL, URLResponse)
        do {
            (tempURL, response) = try await session.download(for: request)
        } catch {
            throw RelayError.network(error.localizedDescription)
        }
        try Self.validate(response)
        let stableURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.moveItem(at: tempURL, to: stableURL)
        return stableURL
    }

    /// Uploads a file into another device's shared folder as `path` (its
    /// folder plus the file name); the device may rename it if the name is
    /// taken, and returns the entry it created.
    func uploadRemote(_ config: PairingConfig, deviceID: String, path: String, fileURL: URL) async throws -> FSEntry {
        var request = fsRequest(config, deviceID: deviceID, op: "upload", path: path)
        request.httpMethod = "PUT"
        request.setValue(Self.mimeType(for: fileURL), forHTTPHeaderField: "Content-Type")
        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await session.upload(for: request, fromFile: fileURL)
        } catch {
            throw RelayError.network(error.localizedDescription)
        }
        guard let http = response as? HTTPURLResponse else { throw RelayError.invalidResponse }
        guard (200...299).contains(http.statusCode) else {
            let message = String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
            if (400...499).contains(http.statusCode) && http.statusCode != 404 && !message.isEmpty {
                throw RelayError.rejected(http.statusCode, message)
            }
            throw RelayError.http(http.statusCode)
        }
        return try JSONDecoder().decode(FSEntry.self, from: data)
    }

    /// Answers an fs_request with an in-memory body (listings, errors).
    func respondFS(_ config: PairingConfig, requestID: String, status: Int, contentType: String, body: Data) async throws {
        var request = fsResponse(config, requestID: requestID, status: status, contentType: contentType)
        request.httpBody = body
        _ = try await execute(request, allowEmptyBody: true)
    }

    /// Answers an fs_request with a file streamed from disk.
    func respondFS(_ config: PairingConfig, requestID: String, contentType: String, fileURL: URL) async throws {
        let request = fsResponse(config, requestID: requestID, status: 200, contentType: contentType)
        let response: URLResponse
        do {
            (_, response) = try await session.upload(for: request, fromFile: fileURL)
        } catch {
            throw RelayError.network(error.localizedDescription)
        }
        try Self.validate(response)
    }

    /// Reads the body a caller sent with a PUT fs request (exec/complete JSON,
    /// see ADR-0005); the relay hands it over once.
    func pullFS(_ config: PairingConfig, requestID: String) async throws -> Data {
        try await execute(authedRequest(config, path: "/fs/pull/\(requestID)"), allowEmptyBody: true)
    }

    /// Answers an fs_request with a body produced while the request is open
    /// (a running command's output). Uses its own session: a quiet command
    /// must not trip the 30 s idle timeout of the shared one.
    func respondFS(_ config: PairingConfig, requestID: String, contentType: String, stream: InputStream) async throws {
        var request = fsResponse(config, requestID: requestID, status: 200, contentType: contentType)
        request.httpBodyStream = stream
        let response: URLResponse
        do {
            (_, response) = try await streamingSession.data(for: request)
        } catch {
            throw RelayError.network(error.localizedDescription)
        }
        try Self.validate(response)
    }

    lazy var streamingSession: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 3600
        configuration.timeoutIntervalForResource = 3600
        return URLSession(configuration: configuration)
    }()

    func fsRequest(_ config: PairingConfig, deviceID: String, op: String, path: String) -> URLRequest {
        let base = config.relayBaseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        var components = URLComponents(string: base + "/fs/\(deviceID)/\(op)")!
        components.queryItems = [URLQueryItem(name: "path", value: path)]
        var request = URLRequest(url: components.url!)
        request.setValue("Bearer \(config.deviceToken)", forHTTPHeaderField: "Authorization")
        return request
    }

    private func fsResponse(_ config: PairingConfig, requestID: String, status: Int, contentType: String) -> URLRequest {
        var request = authedRequest(config, path: "/fs/respond/\(requestID)")
        request.httpMethod = "POST"
        request.setValue(String(status), forHTTPHeaderField: "X-Idroid-Status")
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        return request
    }

    func openWebSocket(_ config: PairingConfig) -> URLSessionWebSocketTask {
        let wsURL = Self.toWebSocketURL(config.relayBaseURL) + "/ws?token=" + config.deviceToken
        return session.webSocketTask(with: URLRequest(url: URL(string: wsURL)!))
    }

    /// Live mouse/keyboard pipe to `deviceID` (ADR-0008).
    func openInputSocket(_ config: PairingConfig, deviceID: String) -> URLSessionWebSocketTask {
        let wsURL = Self.toWebSocketURL(config.relayBaseURL) + "/input/" + deviceID + "?token=" + config.deviceToken
        return streamingSession.webSocketTask(with: URLRequest(url: URL(string: wsURL)!))
    }

    // MARK: - Helpers

    private func authedRequest(_ config: PairingConfig, path: String) -> URLRequest {
        let base = config.relayBaseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        var request = URLRequest(url: URL(string: base + path)!)
        request.setValue("Bearer \(config.deviceToken)", forHTTPHeaderField: "Authorization")
        return request
    }

    private func execute(_ request: URLRequest, allowEmptyBody: Bool = false) async throws -> Data {
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw RelayError.network(error.localizedDescription)
        }
        try Self.validate(response)
        if data.isEmpty && !allowEmptyBody { throw RelayError.emptyBody }
        return data
    }

    static func validate(_ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else { throw RelayError.invalidResponse }
        guard (200...299).contains(http.statusCode) else { throw RelayError.http(http.statusCode) }
    }

    private func appendFormField(_ body: inout Data, boundary: String, name: String, value: String) {
        body.append(Data("--\(boundary)\r\n".utf8))
        body.append(Data("Content-Disposition: form-data; name=\"\(name)\"\r\n\r\n".utf8))
        body.append(Data("\(value)\r\n".utf8))
    }

    private func appendFilePartHeader(_ body: inout Data, boundary: String, name: String, filename: String, contentType: String) {
        body.append(Data("--\(boundary)\r\n".utf8))
        body.append(Data("Content-Disposition: form-data; name=\"\(name)\"; filename=\"\(filename)\"\r\n".utf8))
        body.append(Data("Content-Type: \(contentType)\r\n\r\n".utf8))
    }

    private static func toWebSocketURL(_ httpURL: String) -> String {
        var url = httpURL
        if url.hasPrefix("https://") {
            url = "wss://" + url.dropFirst("https://".count)
        } else if url.hasPrefix("http://") {
            url = "ws://" + url.dropFirst("http://".count)
        }
        while url.hasSuffix("/") { url.removeLast() }
        return url
    }

    private static func parseFilename(_ contentDisposition: String?) -> String? {
        guard let header = contentDisposition,
              let start = header.range(of: "filename=\"") else { return nil }
        let rest = header[start.upperBound...]
        guard let end = rest.range(of: "\"") else { return nil }
        return String(rest[..<end.lowerBound])
    }

    static func mimeType(for url: URL) -> String {
        if let type = UTType(filenameExtension: url.pathExtension), let mime = type.preferredMIMEType {
            return mime
        }
        return "application/octet-stream"
    }
}
