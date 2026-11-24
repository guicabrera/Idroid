import Foundation

/// Runs commands for paired devices (ADR-0005), answering the "commands",
/// "exec" and "complete" fs_requests the relay tunnels to this Mac.
///
/// Named actions always run; free commands and completion only when the
/// terminal is enabled in Settings. Every run is logged to
/// ~/Library/Logs/Idroid/commands.log. Output streams back while the command
/// runs and ends with a trailer line: 0x1E + {"exit":N,"cwd":"..."}.
enum CommandResponder {

    struct Catalog: Codable {
        let terminalEnabled: Bool
        let home: String
        let actions: [CommandAction]
        /// For the phone's interactive SSH terminal: this Mac's tailnet name,
        /// the login user, and whether Remote Login (sshd) is listening.
        let sshHost: String
        let user: String
        let remoteLogin: Bool
        /// The Obsidian vault open on this Mac, where the phone's Claude Code mode works.
        let vault: String?
        enum CodingKeys: String, CodingKey {
            case home, actions, user, vault
            case terminalEnabled = "terminal_enabled"
            case sshHost = "ssh_host"
            case remoteLogin = "remote_login"
        }
    }

    struct ExecRequest: Codable {
        var command: String?
        var actionID: String?
        var input: String?
        var cwd: String?
        enum CodingKeys: String, CodingKey {
            case command, input, cwd
            case actionID = "action_id"
        }
    }

    struct CompleteRequest: Codable {
        let line: String
        let cwd: String?
    }

    static let timeout: TimeInterval = 15 * 60

    static func handle(_ event: NotifyEvent, actions: [CommandAction], terminalEnabled: Bool, deviceName: String, config: PairingConfig, apiClient: RelayAPIClient) async {
        guard let requestID = event.requestID, let op = event.op else { return }
        do {
            switch op {
            case "commands":
                let catalog = Catalog(terminalEnabled: terminalEnabled, home: NSHomeDirectory(), actions: actions,
                                      sshHost: tailnetName() ?? ProcessInfo.processInfo.hostName,
                                      user: NSUserName(), remoteLogin: sshdListening(), vault: obsidianVault())
                try await apiClient.respondFS(config, requestID: requestID, status: 200, contentType: "application/json", body: JSONEncoder().encode(catalog))
            case "complete":
                guard terminalEnabled else { throw Refusal("the terminal is turned off on this Mac") }
                let request = try JSONDecoder().decode(CompleteRequest.self, from: await apiClient.pullFS(config, requestID: requestID))
                let body = try JSONEncoder().encode(["suggestions": complete(request.line, cwd: request.cwd)])
                try await apiClient.respondFS(config, requestID: requestID, status: 200, contentType: "application/json", body: body)
            case "exec":
                let request = try JSONDecoder().decode(ExecRequest.self, from: await apiClient.pullFS(config, requestID: requestID))
                let script: String
                let label: String
                if let id = request.actionID {
                    guard let action = actions.first(where: { $0.id == id }) else { throw Refusal("no such command on this Mac") }
                    script = action.command
                    label = "action \(action.name)" + (request.input.map { " [\($0)]" } ?? "")
                } else if let command = request.command, !command.trimmingCharacters(in: .whitespaces).isEmpty {
                    guard terminalEnabled else { throw Refusal("the terminal is turned off on this Mac (Settings → Commands)") }
                    script = command
                    label = command
                } else {
                    throw Refusal("empty command")
                }
                audit("\(deviceName): \(label) (cwd \(request.cwd ?? "~"))")
                try await run(script, input: request.input ?? "", cwd: request.cwd, requestID: requestID, config: config, apiClient: apiClient)
            default:
                throw Refusal("\(op) is not supported by this device")
            }
        } catch let refusal as Refusal {
            try? await apiClient.respondFS(config, requestID: requestID, status: 403, contentType: "text/plain", body: Data(refusal.message.utf8))
        } catch {
            try? await apiClient.respondFS(config, requestID: requestID, status: 500, contentType: "text/plain", body: Data(error.localizedDescription.utf8))
        }
    }

    struct Refusal: Error {
        let message: String
        init(_ message: String) { self.message = message }
    }

    // MARK: - Running

    private static func run(_ script: String, input: String, cwd: String?, requestID: String, config: PairingConfig, apiClient: RelayAPIClient) async throws {
        let cwdFile = FileManager.default.temporaryDirectory.appendingPathComponent("idroid-cwd-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: cwdFile) }

        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/zsh")
        // The script travels in the environment, so nothing the phone sends
        // is ever spliced into a command line; input is "$1".
        process.arguments = ["-l", "-c", """
            cd -- "${IDROID_CWD:-$HOME}" 2>/dev/null || cd ~
            trap 'pwd > "$IDROID_CWD_FILE"' EXIT
            eval "$IDROID_SCRIPT"
            """, "idroid", input]
        var env = ProcessInfo.processInfo.environment
        env["IDROID_SCRIPT"] = script
        env["IDROID_CWD"] = expand(cwd)
        env["IDROID_CWD_FILE"] = cwdFile.path
        env["TERM"] = "dumb"
        env["NO_COLOR"] = "1"
        env["PAGER"] = "cat"
        env["GIT_PAGER"] = "cat"
        process.environment = env
        process.standardInput = FileHandle.nullDevice
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe

        var input: InputStream?
        var output: OutputStream?
        Stream.getBoundStreams(withBufferSize: 64 * 1024, inputStream: &input, outputStream: &output)
        guard let input, let output else { throw Refusal("could not open output stream") }
        output.open()
        let writer = StreamWriter(output)

        pipe.fileHandleForReading.readabilityHandler = { handle in
            let data = handle.availableData
            if data.isEmpty { handle.readabilityHandler = nil } else { writer.write(data) }
        }
        process.terminationHandler = { process in
            pipe.fileHandleForReading.readabilityHandler = nil
            let rest = pipe.fileHandleForReading.readDataToEndOfFile()
            if !rest.isEmpty { writer.write(rest) }
            let finalCwd = (try? String(contentsOf: cwdFile, encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let trailer = (try? JSONSerialization.data(withJSONObject: ["exit": Int(process.terminationStatus), "cwd": finalCwd])) ?? Data("{}".utf8)
            writer.write(Data("\n\u{1E}".utf8) + trailer + Data("\n".utf8))
            writer.close()
        }

        try process.run()
        let deadline = DispatchWorkItem { if process.isRunning { process.terminate() } }
        DispatchQueue.global().asyncAfter(deadline: .now() + timeout, execute: deadline)
        defer { deadline.cancel() }
        do {
            try await apiClient.respondFS(config, requestID: requestID, contentType: "text/plain; charset=utf-8", stream: input)
        } catch {
            // The phone went away (or the relay dropped us): don't leave it running.
            if process.isRunning { process.terminate() }
            writer.close()
            throw error
        }
    }

    /// Serializes blocking writes to the bound stream off the pipe's callback queue.
    private final class StreamWriter {
        private let stream: OutputStream
        private let queue = DispatchQueue(label: "idroid.command.output")
        private var closed = false

        init(_ stream: OutputStream) { self.stream = stream }

        func write(_ data: Data) {
            queue.async {
                guard !self.closed else { return }
                data.withUnsafeBytes { raw in
                    guard var pointer = raw.bindMemory(to: UInt8.self).baseAddress else { return }
                    var left = data.count
                    while left > 0 {
                        let n = self.stream.write(pointer, maxLength: left)
                        if n <= 0 { self.closed = true; return }
                        left -= n
                        pointer += n
                    }
                }
            }
        }

        func close() {
            queue.async {
                guard !self.closed else { return }
                self.closed = true
                self.stream.close()
            }
        }
    }

    // MARK: - Obsidian

    /// The vault Obsidian has open (or used last), from its own vault list.
    private static func obsidianVault() -> String? {
        let file = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/obsidian/obsidian.json")
        guard let data = try? Data(contentsOf: file),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let vaults = (json["vaults"] as? [String: [String: Any]])?.values else { return nil }
        let sorted = vaults.sorted { a, b in
            let ao = a["open"] as? Bool ?? false, bo = b["open"] as? Bool ?? false
            if ao != bo { return ao }
            return (a["ts"] as? Double ?? 0) > (b["ts"] as? Double ?? 0)
        }
        return sorted.first?["path"] as? String
    }

    // MARK: - SSH details

    /// MagicDNS name from the Tailscale CLI (app or Homebrew), without the trailing dot.
    private static func tailnetName() -> String? {
        let candidates = ["/Applications/Tailscale.app/Contents/MacOS/Tailscale", "/opt/homebrew/bin/tailscale", "/usr/local/bin/tailscale"]
        guard let cli = candidates.first(where: { FileManager.default.isExecutableFile(atPath: $0) }) else { return nil }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: cli)
        process.arguments = ["status", "--json"]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return nil }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let me = json["Self"] as? [String: Any],
              let name = me["DNSName"] as? String, !name.isEmpty else { return nil }
        return name.hasSuffix(".") ? String(name.dropLast()) : name
    }

    /// Remote Login is on when something accepts connections on port 22.
    private static func sshdListening() -> Bool {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return false }
        defer { close(fd) }
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = in_port_t(22).bigEndian
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        return withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) == 0 }
        }
    }

    // MARK: - Completion

    /// Commands for the first word, paths after it; zsh's own completion
    /// needs a TTY, so this uses bash's compgen, which doesn't.
    static func complete(_ line: String, cwd: String?) -> [String] {
        let words = line.split(separator: " ", omittingEmptySubsequences: false)
        let prefix = words.last.map(String.init) ?? ""
        let isFirst = words.count <= 1
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/bash")
        process.arguments = ["-c", isFirst ? "compgen -c -- \"$1\"" : "compgen -f -- \"$1\" | while read -r f; do [ -d \"$f\" ] && echo \"$f/\" || echo \"$f\"; done", "idroid", (prefix as NSString).expandingTildeInPath]
        process.currentDirectoryURL = URL(fileURLWithPath: expand(cwd) ?? NSHomeDirectory())
        var env = ProcessInfo.processInfo.environment
        env["PATH"] = "/opt/homebrew/bin:/usr/local/bin:" + (env["PATH"] ?? "/usr/bin:/bin")
        process.environment = env
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return [] }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        var seen = Set<String>()
        return String(decoding: data, as: UTF8.self)
            .split(separator: "\n").map(String.init)
            .filter { seen.insert($0).inserted }
            .sorted { $0.count == $1.count ? $0 < $1 : $0.count < $1.count }
            .prefix(40).map { $0 }
    }

    private static func expand(_ path: String?) -> String? {
        guard let path, !path.isEmpty else { return nil }
        return (path as NSString).expandingTildeInPath
    }

    // MARK: - Audit

    private static func audit(_ line: String) {
        let dir = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/Idroid", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("commands.log")
        let entry = Data("\(ISO8601DateFormatter().string(from: Date())) \(line)\n".utf8)
        if let handle = try? FileHandle(forWritingTo: file) {
            handle.seekToEndOfFile(); handle.write(entry); try? handle.close()
        } else {
            try? entry.write(to: file)
        }
    }
}
