import Foundation
import UniformTypeIdentifiers

enum RelayError: Error, LocalizedError {
    case network(String)
    case http(Int)
    case emptyBody
    case invalidResponse

    var errorDescription: String? {
        switch self {
        case .network(let message): return "network error: \(message)"
        case .http(let code): return "request failed (\(code))"
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
    func startPairing(relayBaseURL: String, name: String) async throws -> PairStartResponse {
        let request = jsonRequest(relayBaseURL: relayBaseURL, path: "/pair/start", body: ["name": name])
        let data = try await execute(request)
        return try JSONDecoder().decode(PairStartResponse.self, from: data)
    }

    /// Claims a pairing code shown by another device's `startPairing` call.
    func claimPairing(relayBaseURL: String, code: String, name: String) async throws -> PairClaimResponse {
        let request = jsonRequest(relayBaseURL: relayBaseURL, path: "/pair/claim", body: ["code": code, "name": name])
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

    private func jsonRequest(relayBaseURL: String, path: String, body: [String: String]) -> URLRequest {
        let base = relayBaseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        var request = URLRequest(url: URL(string: base + path)!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        return request
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

    func uploadText(_ config: PairingConfig, kind: String, text: String) async throws -> ItemMeta {
        let boundary = "idroid-\(UUID().uuidString)"
        var body = Data()
        appendFormField(&body, boundary: boundary, name: "recipient", value: config.recipientDeviceID)
        appendFormField(&body, boundary: boundary, name: "kind", value: kind)
        appendFormField(&body, boundary: boundary, name: "filename", value: "shared-text.txt")
        appendFormField(&body, boundary: boundary, name: "content_type", value: "text/plain")
        appendFilePartHeader(&body, boundary: boundary, name: "payload", filename: "shared-text.txt", contentType: "text/plain")
        body.append(Data(text.utf8))
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
    func uploadFile(_ config: PairingConfig, fileURL: URL) async throws -> ItemMeta {
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
        appendFormField(&preamble, boundary: boundary, name: "recipient", value: config.recipientDeviceID)
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

    func openWebSocket(_ config: PairingConfig) -> URLSessionWebSocketTask {
        let wsURL = Self.toWebSocketURL(config.relayBaseURL) + "/ws?token=" + config.deviceToken
        return session.webSocketTask(with: URLRequest(url: URL(string: wsURL)!))
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

    private static func validate(_ response: URLResponse) throws {
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

    private static func mimeType(for url: URL) -> String {
        if let type = UTType(filenameExtension: url.pathExtension), let mime = type.preferredMIMEType {
            return mime
        }
        return "application/octet-stream"
    }
}
