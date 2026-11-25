import Foundation

/// What another Mac lets this one run (its "commands" answer, ADR-0005).
typealias RemoteCatalog = CommandResponder.Catalog

struct RemoteExecResult: Decodable {
    let exit: Int
    let cwd: String?
}

/// Client side of remote commands, the Mac counterpart of the phone's
/// RelayApiClient.execCommand: the same fs "commands", "complete" and
/// "exec" requests, so a Mac can drive another Mac like the phone does.
extension RelayAPIClient {

    func commandCatalog(_ config: PairingConfig, deviceID: String) async throws -> RemoteCatalog {
        let request = fsRequest(config, deviceID: deviceID, op: "commands", path: "/")
        let (data, response) = try await sessionData(request)
        try Self.check(response, data)
        return try JSONDecoder().decode(RemoteCatalog.self, from: data)
    }

    func completeCommand(_ config: PairingConfig, deviceID: String, line: String, cwd: String?) async throws -> [String] {
        var request = fsRequest(config, deviceID: deviceID, op: "complete", path: "/")
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(CommandResponder.CompleteRequest(line: line, cwd: cwd))
        let (data, response) = try await sessionData(request)
        try Self.check(response, data)
        struct Answer: Decodable { let suggestions: [String] }
        return try JSONDecoder().decode(Answer.self, from: data).suggestions
    }

    /// Runs a command on another Mac, handing output to `onOutput` as it
    /// arrives. Cancelling the task closes the connection, which stops the
    /// command there. The answer ends with a trailer line: 0x1E + JSON.
    func execCommand(
        _ config: PairingConfig,
        deviceID: String,
        exec: CommandResponder.ExecRequest,
        onOutput: @escaping @MainActor (String) -> Void
    ) async throws -> RemoteExecResult {
        var request = fsRequest(config, deviceID: deviceID, op: "exec", path: "/")
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(exec)
        let (bytes, response): (URLSession.AsyncBytes, URLResponse)
        do {
            (bytes, response) = try await streamingSession.bytes(for: request)
        } catch {
            throw RelayError.network(error.localizedDescription)
        }
        guard let http = response as? HTTPURLResponse else { throw RelayError.invalidResponse }
        guard (200...299).contains(http.statusCode) else {
            var body = Data()
            for try await b in bytes { body.append(b); if body.count > 4096 { break } }
            let message = String(decoding: body, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
            throw message.isEmpty ? RelayError.http(http.statusCode) : RelayError.rejected(http.statusCode, message)
        }
        var line = Data()
        var trailer: Data?
        // Lines go out once complete, without their newline until the next
        // line starts: "\n" + 0x1E opens the trailer and isn't output.
        var heldNewline = false
        for try await byte in bytes {
            if trailer != nil { trailer!.append(byte); continue }
            if line.isEmpty && heldNewline && byte == 0x1E {
                trailer = Data()
                heldNewline = false
                continue
            }
            if line.isEmpty && heldNewline {
                await onOutput("\n")
                heldNewline = false
            }
            if byte == 0x0A {
                if !line.isEmpty { await onOutput(String(decoding: line, as: UTF8.self)) }
                line.removeAll(keepingCapacity: true)
                heldNewline = true
            } else {
                line.append(byte)
                if line.count >= 4096 {
                    await onOutput(String(decoding: line, as: UTF8.self))
                    line.removeAll(keepingCapacity: true)
                }
            }
        }
        if heldNewline { await onOutput("\n") }
        if !line.isEmpty { await onOutput(String(decoding: line, as: UTF8.self)) }
        if let trailer, let result = try? JSONDecoder().decode(RemoteExecResult.self, from: trailer) {
            return result
        }
        if let trailer, !trailer.isEmpty { await onOutput(String(decoding: trailer, as: UTF8.self)) }
        return RemoteExecResult(exit: -1, cwd: exec.cwd)
    }

    /// Runs a command and returns all of its output (for small JSON answers).
    @MainActor
    func execCollect(_ config: PairingConfig, deviceID: String, exec: CommandResponder.ExecRequest) async throws -> (String, RemoteExecResult) {
        let box = OutputBox()
        let result = try await execCommand(config, deviceID: deviceID, exec: exec) { box.text += $0 }
        return (await box.read(), result)
    }

    private func sessionData(_ request: URLRequest) async throws -> (Data, URLResponse) {
        do {
            return try await streamingSession.data(for: request)
        } catch {
            throw RelayError.network(error.localizedDescription)
        }
    }

    private static func check(_ response: URLResponse, _ data: Data) throws {
        guard let http = response as? HTTPURLResponse else { throw RelayError.invalidResponse }
        guard (200...299).contains(http.statusCode) else {
            let message = String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
            throw message.isEmpty ? RelayError.http(http.statusCode) : RelayError.rejected(http.statusCode, message)
        }
    }
}

@MainActor
final class OutputBox {
    var text = ""
    func read() -> String { text }
}

/// Calls the Idroid Bridge plugin in Obsidian on another Mac through its
/// helper script (`~/.idroid/copilot METHOD PATH [JSON]`), like the phone.
struct BridgeClient {
    let api: RelayAPIClient
    let config: PairingConfig
    let deviceID: String

    func call(_ method: String, _ path: String, body: [String: String?]? = nil) async throws -> Data {
        var command = "\"$HOME/.idroid/copilot\" \(method) '\(path.replacingOccurrences(of: "'", with: ""))'"
        var input: String?
        if let body {
            command += " \"$1\""
            let clean = body.compactMapValues { $0 }
            input = String(decoding: try JSONSerialization.data(withJSONObject: clean), as: UTF8.self)
        }
        let (out, result) = try await api.execCollect(config, deviceID: deviceID, exec: .init(command: command, actionID: nil, input: input, cwd: nil))
        let text = out.trimmingCharacters(in: .whitespacesAndNewlines)
        if result.exit != 0 {
            throw RelayError.rejected(result.exit, text.isEmpty ? "bridge exited with \(result.exit)" : String(text.prefix(300)))
        }
        guard let line = text.split(separator: "\n").last(where: { $0.hasPrefix("{") }) else {
            throw RelayError.rejected(0, text.isEmpty ? "No answer from the Idroid Bridge plugin in Obsidian on that Mac" : String(text.prefix(300)))
        }
        return Data(line.utf8)
    }
}
