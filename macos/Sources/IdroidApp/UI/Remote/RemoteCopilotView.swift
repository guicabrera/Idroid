import SwiftUI

// The Idroid Bridge's answers (docs/07-api-reference/copilot-bridge-api.md).
let copilotGlobal = "__global__"

struct CopilotState: Decodable {
    var version: Int = 0
    var sessions: [CopilotSession] = []
    var session: CopilotChat?
    var projects: [CopilotProject] = []
    var project: String = copilotGlobal

    enum CodingKeys: String, CodingKey { case version, sessions, session, projects, project }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = try c.decodeIfPresent(Int.self, forKey: .version) ?? 0
        sessions = try c.decodeIfPresent([CopilotSession].self, forKey: .sessions) ?? []
        session = try c.decodeIfPresent(CopilotChat.self, forKey: .session)
        projects = try c.decodeIfPresent([CopilotProject].self, forKey: .projects) ?? []
        project = try c.decodeIfPresent(String.self, forKey: .project) ?? copilotGlobal
    }
}

struct CopilotProject: Decodable, Identifiable, Hashable { let id: String; let name: String }

struct CopilotSession: Decodable, Identifiable {
    let id: String
    let title: String
    let status: String
    let active: Bool?
    let project: String?
}

struct CopilotChat: Decodable {
    let id: String
    let status: String
    let messages: [CopilotMessage]
    let permissions: [CopilotPermission]
}

struct CopilotMessage: Decodable, Identifiable {
    let id: String
    let sender: String
    let text: String
    let tools: [CopilotTool]
    let error: Bool
}

struct CopilotTool: Decodable, Hashable { let title: String; let status: String; let detail: String }

struct CopilotPermission: Decodable, Identifiable {
    let toolCallId: String
    let title: String
    let detail: String
    let options: [CopilotOption]
    var id: String { toolCallId }
}

struct CopilotOption: Decodable, Identifiable { let id: String; let name: String; let kind: String }

struct CopilotSavedChat: Decodable, Identifiable {
    let path: String
    let title: String
    let lastUsed: Double
    var id: String { path }
}

/// Obsidian Copilot's agent chat on another Mac (ADR-0006), like the phone's
/// Copilot tab: its live chats, projects, saved history and permission
/// prompts, through the Idroid Bridge plugin there. Long-polls for changes.
struct RemoteCopilotView: View {
    let device: PeerDevice
    let pairing: PairingConfig
    let startNew: Bool
    @EnvironmentObject var appState: AppState

    @State private var state: CopilotState?
    @State private var error: String?
    @State private var sessionID: String?
    @State private var newChat = false
    @State private var project: String?
    @State private var input = ""
    @State private var sending = false
    @State private var reload = 0
    @State private var history: [CopilotSavedChat]?
    @State private var showHistory = false
    @State private var resuming = false
    @FocusState private var inputFocused: Bool

    private var bridge: BridgeClient { BridgeClient(api: appState.apiClient, config: pairing, deviceID: device.id) }
    private static let idPattern = "^[A-Za-z0-9_-]{1,80}$"
    private func safeID(_ s: String?) -> String? { s.flatMap { $0.range(of: Self.idPattern, options: .regularExpression) != nil ? $0 : nil } }

    private var chat: CopilotChat? { state?.session }
    private var busy: Bool { ["running", "awaiting_permission", "starting"].contains(chat?.status ?? "") }
    private var projectName: String? {
        guard let project, project != copilotGlobal else { return nil }
        return state?.projects.first { $0.id == project }?.name
    }

    var body: some View {
        VStack(spacing: 0) {
            if let error {
                Text(error.contains("isn't running") ? "Open Obsidian on \(device.name): the Idroid Bridge plugin runs inside it." : error)
                    .font(.caption).foregroundStyle(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(8).background(Color.red.opacity(0.8))
            }
            messages
            Divider()
            composer
        }
        .frame(minWidth: 520, minHeight: 420)
        .navigationTitle("\(device.name) — Copilot" + (projectName.map { " · \($0)" } ?? ""))
        .navigationSubtitle(state?.sessions.first { $0.id == sessionID }?.title ?? "New chat")
        .toolbar { toolbar }
        .onAppear { newChat = startNew; inputFocused = true }
        .task(id: "\(sessionID ?? "-")#\(reload)") { await poll() }
        .sheet(isPresented: $showHistory) { historySheet }
    }

    // MARK: - Polling

    private func poll() async {
        var since = -1
        while !Task.isCancelled {
            let sid = safeID(sessionID)
            let path = "/state?since=\(since)&wait=\(since < 0 ? 0 : 25)" + (sid.map { "&session=\($0)" } ?? (newChat ? "&session=new" : ""))
            do {
                let data = try await bridge.call("GET", path)
                guard !Task.isCancelled else { return }
                let s = try JSONDecoder().decode(CopilotState.self, from: data)
                error = nil
                state = s
                since = s.version
                if project == nil { project = s.project }
                if sessionID == nil && !newChat { sessionID = s.session?.id }
            } catch {
                if Task.isCancelled { return }
                self.error = error.localizedDescription
                try? await Task.sleep(for: .seconds(3))
            }
        }
    }

    // MARK: - Views

    private var messages: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    if state == nil && error == nil {
                        ProgressView().frame(maxWidth: .infinity).padding(30)
                    } else if chat == nil {
                        Text("New conversation with Copilot on \(device.name)" + (projectName.map { " in \($0)" } ?? "") +
                             ". It works in that vault with Copilot's tools, like the sidebar there.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(chat?.messages ?? []) { MessageBubble(message: $0) }
                    ForEach(chat?.permissions ?? []) { p in
                        PermissionCard(permission: p) { option in
                            Task {
                                do { _ = try await bridge.call("POST", "/permission", body: ["session": chat?.id, "toolCallId": p.toolCallId, "optionId": option]) }
                                catch { self.error = error.localizedDescription }
                            }
                        }
                    }
                    if busy && (chat?.permissions.isEmpty ?? true) {
                        HStack { ProgressView().controlSize(.small); Text("Working…").font(.caption).foregroundStyle(.secondary) }
                    }
                    Color.clear.frame(height: 1).id("end")
                }
                .padding(12)
            }
            .onChange(of: (chat?.messages.count ?? 0) * 100_000 + (chat?.messages.last?.text.count ?? 0) + (chat?.permissions.count ?? 0)) {
                proxy.scrollTo("end", anchor: .bottom)
            }
        }
    }

    private var composer: some View {
        HStack(alignment: .bottom, spacing: 8) {
            TextField("Message Copilot…", text: $input, axis: .vertical)
                .textFieldStyle(.plain)
                .lineLimit(1...8)
                .focused($inputFocused)
                .onSubmit(send)
            if busy {
                Button {
                    Task { _ = try? await bridge.call("POST", "/cancel", body: ["session": chat?.id]) }
                } label: { Image(systemName: "stop.fill") }
                .help("Stop")
            }
            Button(action: send) {
                if sending { ProgressView().controlSize(.small) } else { Image(systemName: "paperplane.fill") }
            }
            .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || sending)
        }
        .padding(10)
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItemGroup {
            Menu {
                Button { pickProject(copilotGlobal) } label: {
                    if project == copilotGlobal { Label("No project", systemImage: "checkmark") } else { Text("No project") }
                }
                Divider()
                ForEach(state?.projects ?? []) { p in
                    Button { pickProject(p.id) } label: {
                        if project == p.id { Label(p.name, systemImage: "checkmark") } else { Text(p.name) }
                    }
                }
            } label: {
                Label(projectName ?? "No project", systemImage: "folder")
            }
            .help("Copilot project")

            Menu {
                Button("Saved chats…", action: openHistory)
                Divider()
                ForEach((state?.sessions ?? []).filter { project == nil || ($0.project ?? copilotGlobal) == project }) { s in
                    Button {
                        newChat = false
                        sessionID = s.id
                    } label: {
                        Text(s.title + " — " + s.status + ((s.active ?? false) ? " · open on \(device.name)" : ""))
                    }
                }
            } label: {
                Label("Conversations", systemImage: "bubble.left.and.bubble.right")
            }

            Button(action: startNewChat) { Label("New chat", systemImage: "square.and.pencil") }
                .help("New chat")
        }
    }

    private var historySheet: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Saved Copilot chats" + (projectName.map { " · \($0)" } ?? "")).font(.headline).padding()
            Divider()
            if let list = history, !resuming {
                if list.isEmpty {
                    Text("No saved agent chats.").foregroundStyle(.secondary).padding()
                    Spacer()
                } else {
                    List(list) { c in
                        Button { resume(c) } label: {
                            VStack(alignment: .leading) {
                                Text(c.title).lineLimit(2)
                                Text(Date(timeIntervalSince1970: c.lastUsed / 1000), style: .relative)
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity).padding(30)
                Spacer()
            }
            Divider()
            HStack { Spacer(); Button("Close") { showHistory = false }.keyboardShortcut(.cancelAction).disabled(resuming) }.padding(10)
        }
        .frame(width: 480, height: 460)
    }

    // MARK: - Actions

    private func startNewChat() {
        newChat = true
        sessionID = nil
        state?.session = nil
        reload += 1
        inputFocused = true
    }

    private func pickProject(_ id: String) {
        guard id != project else { return }
        project = id
        // A chat belongs to one project: start fresh in the new one.
        startNewChat()
    }

    private func openHistory() {
        history = nil
        showHistory = true
        let query = safeID(project).map { "?project=\($0)" } ?? ""
        Task {
            do {
                struct Answer: Decodable { let chats: [CopilotSavedChat] }
                history = try JSONDecoder().decode(Answer.self, from: try await bridge.call("GET", "/history" + query)).chats
            } catch {
                self.error = error.localizedDescription
                history = []
            }
        }
    }

    private func resume(_ chat: CopilotSavedChat) {
        resuming = true
        Task {
            do {
                let data = try await bridge.call("POST", "/resume", body: ["path": chat.path])
                if let id = (try? JSONDecoder().decode([String: String].self, from: data))?["session"] {
                    newChat = false
                    sessionID = id
                    reload += 1
                }
                showHistory = false
            } catch {
                self.error = error.localizedDescription
            }
            resuming = false
        }
    }

    private func send() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !sending else { return }
        input = ""
        sending = true
        Task {
            do {
                let data = try await bridge.call("POST", "/send", body: ["session": sessionID, "text": text, "project": project])
                if let id = (try? JSONDecoder().decode([String: String].self, from: data))?["session"] {
                    newChat = false
                    if id != sessionID { sessionID = id } else { reload += 1 }
                }
            } catch {
                self.error = error.localizedDescription
                input = text
            }
            sending = false
        }
    }
}

private struct MessageBubble: View {
    let message: CopilotMessage
    private var mine: Bool { message.sender == "user" }

    var body: some View {
        HStack {
            if mine { Spacer(minLength: 60) }
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(message.tools.enumerated()), id: \.offset) { _, t in
                    Text((t.status == "completed" ? "✓ " : t.status == "failed" ? "✗ " : "… ") + t.title + (t.detail.isEmpty ? "" : " · \(t.detail)"))
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                if !message.text.isEmpty {
                    Text((try? AttributedString(markdown: message.text, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(message.text))
                        .textSelection(.enabled)
                }
            }
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 12).fill(
                message.error ? Color.red.opacity(0.15) : mine ? Color.accentColor.opacity(0.18) : Color.secondary.opacity(0.1)))
            if !mine { Spacer(minLength: 60) }
        }
    }
}

private struct PermissionCard: View {
    let permission: CopilotPermission
    let onChoose: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Copilot wants to use \(permission.title)").font(.headline)
            if !permission.detail.isEmpty {
                Text(permission.detail).font(.system(.caption, design: .monospaced)).lineLimit(6).textSelection(.enabled)
            }
            HStack {
                ForEach(permission.options) { o in
                    if o.kind.hasPrefix("allow") {
                        Button(o.name) { onChoose(o.id) }.buttonStyle(.borderedProminent)
                    } else {
                        Button(o.name) { onChoose(o.id) }.buttonStyle(.bordered)
                    }
                }
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color.orange.opacity(0.15)))
    }
}
