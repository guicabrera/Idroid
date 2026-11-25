import SwiftUI

/// Remote terminal for another Mac (ADR-0005), like the phone's: free
/// commands with Tab completion and history, the other Mac's named commands,
/// and Claude Code running there (`claude -p`, conversations resumed).
struct RemoteTerminalView: View {
    let device: PeerDevice
    let pairing: PairingConfig
    @EnvironmentObject var appState: AppState
    @StateObject private var model = RemoteTerminalModel()
    @FocusState private var inputFocused: Bool
    @AppStorage("remote.claudeAccess") private var claudeAccess = ClaudeAccess.edit.rawValue
    @State private var actionInput: CommandAction?
    @State private var actionValue = ""

    var body: some View {
        VStack(spacing: 0) {
            transcript
            Divider()
            if let error = model.catalogError {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(8)
                    .background(Color.red.opacity(0.8))
            }
            if model.mode == .claude { claudeBar }
            inputBar
        }
        .frame(minWidth: 560, minHeight: 380)
        .navigationTitle("\(device.name) — \(model.mode == .claude ? "Claude Code" : "Terminal")")
        .toolbar { toolbar }
        .task {
            model.configure(api: appState.apiClient, pairing: pairing, device: device)
            await model.loadCatalog()
            inputFocused = true
        }
        .sheet(item: $actionInput) { action in
            VStack(alignment: .leading, spacing: 12) {
                Text(action.name).font(.headline)
                TextField("Input", text: $actionValue, axis: .vertical)
                    .lineLimit(1...6)
                    .frame(width: 380)
                HStack {
                    Spacer()
                    Button("Cancel") { actionInput = nil }.keyboardShortcut(.cancelAction)
                    Button("Run") {
                        model.runAction(action, value: actionValue, access: access)
                        actionInput = nil
                    }
                    .keyboardShortcut(.defaultAction)
                    .disabled(actionValue.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .padding(20)
        }
        .sheet(isPresented: $model.showConversations) { conversationsSheet }
    }

    private var access: ClaudeAccess { ClaudeAccess(rawValue: claudeAccess) ?? .edit }

    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    if model.entries.isEmpty {
                        Text(model.catalog == nil ? "Connecting to \(device.name)…" :
                                "Commands run on \(device.name) as \(model.catalog?.user ?? "you"). Tab completes, ↑/↓ browse history.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(model.entries) { entry in
                        EntryView(entry: entry).id(entry.id)
                    }
                    Color.clear.frame(height: 1).id("end")
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(Color(nsColor: .textBackgroundColor))
            .onChange(of: model.scrollTick) { proxy.scrollTo("end", anchor: .bottom) }
        }
    }

    private var claudeBar: some View {
        HStack(spacing: 8) {
            Image(systemName: "sparkles")
            Text(model.claudeTitle.map { "Continuing: \($0)" }
                 ?? (model.claudeSession == nil ? "New conversation in \(model.vaultName)" : "Continuing the conversation"))
                .lineLimit(1)
            Spacer()
            Picker("", selection: $claudeAccess) {
                ForEach(ClaudeAccess.allCases, id: \.rawValue) { Text($0.label).tag($0.rawValue) }
            }
            .labelsHidden()
            .fixedSize()
            .help("How much Claude Code may do on \(device.name) without approval")
            Button("New") { model.claudeSession = nil; model.claudeTitle = nil }
                .disabled(model.claudeSession == nil)
            Button("Conversations…") { model.loadConversations() }
                .disabled(model.catalog?.vault == nil)
        }
        .font(.caption)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Color.accentColor.opacity(0.08))
    }

    private var inputBar: some View {
        HStack(spacing: 8) {
            Picker("", selection: $model.mode) {
                Text("Shell").tag(RemoteTerminalModel.Mode.shell)
                Text("Claude Code").tag(RemoteTerminalModel.Mode.claude)
            }
            .labelsHidden()
            .fixedSize()
            if model.mode == .shell {
                Text(model.cwd).font(.system(.caption, design: .monospaced)).foregroundStyle(.secondary).lineLimit(1)
            }
            TextField(model.mode == .shell ? "Command" : "Message Claude Code…", text: $model.input, axis: .vertical)
                .textFieldStyle(.plain)
                .font(.system(.body, design: .monospaced))
                .lineLimit(1...6)
                .focused($inputFocused)
                .onSubmit { model.submit(access: access) }
                .onKeyPress(.tab) { model.complete(); return .handled }
                .onKeyPress(.upArrow) { model.historyStep(-1) ? .handled : .ignored }
                .onKeyPress(.downArrow) { model.historyStep(1) ? .handled : .ignored }
            if model.running != nil {
                Button { model.stop() } label: { Image(systemName: "stop.fill") }
                    .help("Stop")
            }
            Button { model.submit(access: access) } label: { Image(systemName: "paperplane.fill") }
                .disabled(model.input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.running != nil)
        }
        .padding(10)
        .overlay(alignment: .topLeading) {
            if !model.suggestions.isEmpty {
                ScrollView(.horizontal) {
                    HStack {
                        ForEach(model.suggestions, id: \.self) { s in
                            Button(s) { model.input = s; model.suggestions = [] }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                        }
                    }
                }
                .padding(.horizontal, 10)
                .offset(y: -30)
            }
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItemGroup {
            Menu {
                let actions = model.catalog?.actions ?? []
                if actions.isEmpty { Text("No commands on \(device.name)") }
                ForEach(actions) { action in
                    Button(action.name) {
                        if action.takesInput { actionValue = ""; actionInput = action }
                        else { model.runAction(action, value: nil, access: access) }
                    }
                }
            } label: {
                Label("Commands", systemImage: "bolt")
            }
            .disabled(model.running != nil)
            Button { model.clear() } label: { Label("Clear", systemImage: "trash") }
        }
    }

    private var conversationsSheet: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Claude Code conversations on \(device.name)").font(.headline).padding()
            Divider()
            if let list = model.conversations {
                if list.isEmpty {
                    Text("No conversations in the vault.").foregroundStyle(.secondary).padding()
                } else {
                    List(list) { c in
                        Button {
                            model.claudeSession = c.id
                            model.claudeTitle = c.title
                            model.mode = .claude
                            model.showConversations = false
                        } label: {
                            VStack(alignment: .leading) {
                                Text(c.title).lineLimit(2)
                                Text(Date(timeIntervalSince1970: TimeInterval(c.modified)), style: .relative)
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity).padding(30)
            }
            Divider()
            HStack { Spacer(); Button("Close") { model.showConversations = false }.keyboardShortcut(.cancelAction) }.padding(10)
        }
        .frame(width: 460, height: 420)
    }
}

/// How much Claude Code may do without anyone at the other Mac to approve it.
enum ClaudeAccess: String, CaseIterable {
    case read, edit, all
    var label: String {
        switch self {
        case .read: return "Read only"
        case .edit: return "Edit notes"
        case .all: return "Everything"
        }
    }
    var flags: String {
        switch self {
        case .read: return "--permission-mode default"
        case .edit: return "--permission-mode acceptEdits"
        case .all: return "--dangerously-skip-permissions"
        }
    }
}

struct ClaudeConversation: Decodable, Identifiable {
    let id: String
    let title: String
    let modified: Int
}

private struct EntryView: View {
    @ObservedObject var entry: TerminalEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            switch entry.kind {
            case .run(let label, let cwd):
                HStack(spacing: 6) {
                    Text("\(cwd) $").foregroundStyle(.secondary)
                    Text(label).bold()
                    if let exit = entry.exit, exit != 0 {
                        Text("exit \(exit)").font(.caption).foregroundStyle(.red)
                    } else if entry.exit == nil && entry.error == nil {
                        ProgressView().controlSize(.mini)
                    }
                }
                .font(.system(.body, design: .monospaced))
                if !entry.text.isEmpty {
                    Text(entry.text).font(.system(.callout, design: .monospaced)).textSelection(.enabled)
                }
            case .prompt:
                Text("› " + entry.text).bold().textSelection(.enabled)
            case .claude:
                if entry.pending {
                    HStack { ProgressView().controlSize(.small); Text("Claude Code is working…").foregroundStyle(.secondary) }
                } else {
                    Text((try? AttributedString(markdown: entry.text, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(entry.text))
                        .textSelection(.enabled)
                        .padding(10)
                        .background(RoundedRectangle(cornerRadius: 10).fill(Color.secondary.opacity(0.1)))
                }
            }
            if let error = entry.error {
                Text(error).font(.caption).foregroundStyle(.red)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

@MainActor
final class TerminalEntry: ObservableObject, Identifiable {
    enum Kind { case run(String, String), prompt, claude }
    let id = UUID()
    let kind: Kind
    @Published var text = ""
    @Published var exit: Int?
    @Published var error: String?
    @Published var pending = false

    init(_ kind: Kind, text: String = "") {
        self.kind = kind
        self.text = text
    }
}

/// Vault conventions the Obsidian Copilot gives its Claude sessions, so
/// Claude Code started from here works the same way. No single quotes.
private let vaultPrompt = "You are working in the Obsidian vault of markdown notes (the current folder), " +
    "reached remotely from another of their devices. For questions about their notes, search the vault first. Cite notes as [[note title]], " +
    "use dollar-sign math, embed images as ![[file.png]]. Edit notes with your file tools, keep frontmatter valid, and " +
    "keep scratch files out of the vault. Follow AGENTS.md and CLAUDE.md."

/// Lists the vault's Claude Code conversations as JSON; the vault is "$1".
private let listConversations = #"""
python3 - "$1" <<'PY'
import json, os, re, sys, glob
vault = sys.argv[1]
d = os.path.expanduser("~/.claude/projects/" + re.sub(r"[^A-Za-z0-9]", "-", vault))
out = []
for f in sorted(glob.glob(d + "/*.jsonl"), key=os.path.getmtime, reverse=True)[:40]:
    title = first = None
    with open(f, errors="ignore") as fh:
        for line in fh:
            if '"ai-title"' in line:
                try: title = json.loads(line).get("aiTitle") or title
                except Exception: pass
            elif first is None and '"user-message' in line or first is None and '"type":"user"' in line:
                m = re.search(r"<user-message>\\n?(.*?)\\n?</user-message>", line)
                if m: first = m.group(1).replace("\\n", " ")[:80]
    if not (title or first): continue
    out.append({"id": os.path.basename(f)[:-6], "title": title or first, "modified": int(os.path.getmtime(f))})
print(json.dumps(out[:30], ensure_ascii=False))
PY
"""#

@MainActor
final class RemoteTerminalModel: ObservableObject {
    enum Mode: Hashable { case shell, claude }

    @Published var entries: [TerminalEntry] = []
    @Published var catalog: RemoteCatalog?
    @Published var catalogError: String?
    @Published var mode: Mode = .shell
    @Published var input = ""
    @Published var cwd = "~"
    @Published var running: Task<Void, Never>?
    @Published var suggestions: [String] = []
    @Published var scrollTick = 0
    @Published var claudeSession: String?
    @Published var claudeTitle: String?
    @Published var conversations: [ClaudeConversation]?
    @Published var showConversations = false

    private var api: RelayAPIClient!
    private var pairing: PairingConfig!
    private var device: PeerDevice!
    private var history: [String] = []
    private var historyIndex: Int?

    var vaultName: String { catalog?.vault.map { ($0 as NSString).lastPathComponent } ?? cwd }

    func configure(api: RelayAPIClient, pairing: PairingConfig, device: PeerDevice) {
        self.api = api
        self.pairing = pairing
        self.device = device
    }

    func loadCatalog() async {
        catalogError = nil
        do {
            catalog = try await api.commandCatalog(pairing, deviceID: device.id)
            if catalog?.terminalEnabled == false {
                catalogError = "The terminal is off on \(device.name) (Settings → Commands there); only its named commands run."
            }
        } catch {
            catalogError = "Could not reach \(device.name): \(error.localizedDescription)"
        }
    }

    private func append(_ entry: TerminalEntry) {
        entries.append(entry)
        scrollTick += 1
    }

    /// Runs on the other Mac, streaming into a new transcript block.
    private func exec(_ request: CommandResponder.ExecRequest, label: String) async {
        let entry = TerminalEntry(.run(label, cwd))
        append(entry)
        var request = request
        request.cwd = request.cwd ?? expand(cwd)
        do {
            let result = try await api.execCommand(pairing, deviceID: device.id, exec: request) { [weak self] chunk in
                entry.text += chunk
                self?.scrollTick += 1
            }
            entry.exit = result.exit
            if let newCwd = result.cwd, !newCwd.isEmpty, request.actionID == nil {
                cwd = abbreviate(newCwd)
            }
        } catch is CancellationError {
            entry.error = "Stopped."
        } catch {
            entry.error = Task.isCancelled ? "Stopped." : error.localizedDescription
        }
        scrollTick += 1
    }

    private func expand(_ path: String) -> String {
        guard let home = catalog?.home else { return path == "~" ? "" : path }
        return path == "~" ? home : path.hasPrefix("~/") ? home + path.dropFirst(1) : path
    }

    private func abbreviate(_ path: String) -> String {
        guard let home = catalog?.home, path.hasPrefix(home) else { return path }
        return "~" + path.dropFirst(home.count)
    }

    func submit(access: ClaudeAccess) {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, running == nil else { return }
        input = ""
        suggestions = []
        historyIndex = nil
        switch mode {
        case .shell:
            history.removeAll { $0 == text }
            history.append(text)
            running = Task { await exec(.init(command: text, actionID: nil, input: nil, cwd: nil), label: text); running = nil }
        case .claude:
            askClaude(text, access: access)
        }
    }

    /// One turn with Claude Code on the other Mac, in its vault; the session
    /// id of each reply is resumed on the next, so follow-ups continue it.
    func askClaude(_ prompt: String, access: ClaudeAccess) {
        append(TerminalEntry(.prompt, text: prompt))
        let reply = TerminalEntry(.claude)
        reply.pending = true
        append(reply)
        let resume = claudeSession.flatMap { $0.range(of: "^[0-9a-fA-F-]{8,64}$", options: .regularExpression) != nil ? " --resume \($0)" : nil } ?? ""
        let folder = catalog?.vault ?? expand(cwd)
        let command = "claude -p --output-format json \(access.flags) --append-system-prompt '\(vaultPrompt)'\(resume) \"$1\""
        running = Task {
            do {
                let (out, result) = try await api.execCollect(pairing, deviceID: device.id,
                                                              exec: .init(command: command, actionID: nil, input: prompt, cwd: folder))
                let line = out.split(separator: "\n").last { $0.trimmingCharacters(in: .whitespaces).hasPrefix("{") }
                let obj = line.flatMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: Any] }
                if let id = obj?["session_id"] as? String { claudeSession = id }
                reply.text = (obj?["result"] as? String)
                    ?? (out.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Claude Code exited with \(result.exit)" : out)
            } catch {
                reply.text = Task.isCancelled ? "Stopped." : "Claude Code error: \(error.localizedDescription)"
            }
            reply.pending = false
            running = nil
            scrollTick += 1
        }
    }

    func runAction(_ action: CommandAction, value: String?, access: ClaudeAccess) {
        // The Claude Code command starts a conversation: keep talking to it in Claude mode.
        if action.id == "claude", let value, !value.isEmpty {
            mode = .claude
            claudeSession = nil
            claudeTitle = nil
            askClaude(value, access: access)
            return
        }
        running = Task {
            await exec(.init(command: nil, actionID: action.id, input: value, cwd: nil),
                       label: action.name + (value.map { " · \($0)" } ?? ""))
            running = nil
        }
    }

    func loadConversations() {
        guard let vault = catalog?.vault else { return }
        conversations = nil
        showConversations = true
        Task {
            let list: [ClaudeConversation]
            do {
                let (out, _) = try await api.execCollect(pairing, deviceID: device.id,
                                                         exec: .init(command: listConversations, actionID: nil, input: vault, cwd: nil))
                let line = out.split(separator: "\n").last { $0.trimmingCharacters(in: .whitespaces).hasPrefix("[") }
                list = try line.map { try JSONDecoder().decode([ClaudeConversation].self, from: Data($0.utf8)) } ?? []
            } catch {
                list = []
            }
            conversations = list
        }
    }

    func stop() {
        running?.cancel()
    }

    func clear() {
        entries.removeAll()
    }

    /// Tab: completes the last word on the other Mac (one match fills it in).
    func complete() {
        guard mode == .shell, !input.isEmpty, catalog?.terminalEnabled == true else { return }
        let line = input
        Task {
            guard let found = try? await api.completeCommand(pairing, deviceID: device.id, line: line, cwd: expand(cwd)),
                  input == line else { return }
            let head = line.range(of: "\\S*$", options: .regularExpression).map { String(line[..<$0.lowerBound]) } ?? line
            let options = found.map { head + $0 + ($0.hasSuffix("/") ? "" : " ") }
            if options.count == 1 {
                input = options[0]
                suggestions = []
            } else {
                suggestions = Array(options.prefix(30))
            }
        }
    }

    /// ↑/↓ in the shell: walks the command history. Returns whether it moved.
    func historyStep(_ delta: Int) -> Bool {
        guard mode == .shell, !history.isEmpty else { return false }
        if input.contains("\n") { return false }
        let current = historyIndex ?? history.count
        let next = min(max(current + delta, 0), history.count)
        guard next != current else { return true }
        historyIndex = next
        input = next == history.count ? "" : history[next]
        return true
    }
}
