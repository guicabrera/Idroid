import SwiftUI
import AppKit

/// Send text/files and browse the inbox — the macOS counterpart to
/// HomeScreen.kt. No Share/Services menu integration yet (see
/// macos/README.md); this is the in-app path only.
struct HomeView: View {
    @EnvironmentObject var appState: AppState
    @State private var textToSend = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header

            if let error = appState.errorMessage {
                Text(error).foregroundStyle(.red).font(.caption)
            }
            if let status = appState.statusMessage {
                Text(status).foregroundStyle(.green).font(.caption)
            }

            HStack {
                TextField("Share text or a link…", text: $textToSend)
                    .textFieldStyle(.roundedBorder)
                Button("Send") {
                    appState.sendText(textToSend)
                    textToSend = ""
                }
                .disabled(textToSend.isEmpty)
                Button("Send File…") { pickAndSendFile() }
            }

            Divider()

            if appState.inbox.isEmpty {
                Text("Inbox is empty").foregroundStyle(.secondary)
                Spacer()
            } else {
                List(appState.inbox) { item in
                    InboxRow(item: item)
                }
                .listStyle(.inset)
            }
        }
        .padding(16)
        .frame(minWidth: 480, minHeight: 420)
        .onAppear { appState.refreshInbox() }
    }

    private var header: some View {
        HStack {
            Text("Idroid").font(.title2).bold()
            if let name = appState.pairing?.recipientName {
                Text("· paired with \(name)").font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if appState.isRefreshing { ProgressView().controlSize(.small) }
            Button("Refresh") { appState.refreshInbox() }
            Button("Forget pairing", role: .destructive) { appState.forgetPairing() }
        }
    }

    private func pickAndSendFile() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        if panel.runModal() == .OK, let url = panel.url {
            appState.sendFile(url)
        }
    }
}

private struct InboxRow: View {
    @EnvironmentObject var appState: AppState
    let item: ItemMeta

    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                Text(item.filename ?? item.kind).font(.body)
                Text("\(item.kind) · \(ByteCountFormatter.string(fromByteCount: item.size, countStyle: .file))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button("Save…") { saveWithPanel() }
            Button("Delete", role: .destructive) { appState.deleteItem(item) }
        }
        .padding(.vertical, 4)
    }

    private func saveWithPanel() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.canCreateDirectories = true
        panel.prompt = "Save Here"
        if panel.runModal() == .OK, let dir = panel.url {
            appState.downloadItem(item, to: dir)
        }
    }
}
