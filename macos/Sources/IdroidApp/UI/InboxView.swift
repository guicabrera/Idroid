import SwiftUI

/// Everything other devices sent to this Mac that's still on the relay.
struct InboxView: View {
    @EnvironmentObject var appState: AppState
    @State private var confirmClear = false

    var body: some View {
        Group {
            if appState.inbox.isEmpty {
                ContentUnavailableView {
                    Label("Nothing received", systemImage: "tray")
                } description: {
                    Text("Files and text sent from your other devices show up here.")
                } actions: {
                    Button("Send Something") { appState.section = .send }
                }
            } else {
                List {
                    ForEach(appState.inbox) { item in
                        InboxRow(item: item)
                    }
                }
                .listStyle(.inset(alternatesRowBackgrounds: false))
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            HStack {
                SaveLocationMenu()
                Spacer()
                Text("Items are kept on the relay for 7 days")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(.bar)
        }
        .navigationTitle("Inbox")
        .navigationSubtitle(appState.inbox.isEmpty ? "" : "\(appState.inbox.count) item\(appState.inbox.count == 1 ? "" : "s")")
        .toolbar {
            ToolbarItemGroup {
                Button {
                    appState.refreshInbox()
                } label: {
                    if appState.isRefreshing {
                        ProgressView().controlSize(.small)
                    } else {
                        Label("Refresh", systemImage: "arrow.clockwise")
                    }
                }
                .help("Refresh")

                Button { appState.saveAll() } label: { Label("Save All", systemImage: "square.and.arrow.down.on.square") }
                    .help("Save all items")
                    .disabled(appState.inbox.isEmpty)

                Button { confirmClear = true } label: { Label("Clear", systemImage: "trash") }
                    .help("Delete all items")
                    .disabled(appState.inbox.isEmpty)
            }
        }
        .confirmationDialog("Delete all \(appState.inbox.count) items from the inbox?", isPresented: $confirmClear) {
            Button("Delete All", role: .destructive) { appState.clearInbox() }
        }
    }
}

private struct InboxRow: View {
    @EnvironmentObject var appState: AppState
    let item: ItemMeta
    @State private var hovering = false

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: item.symbolName)
                .font(.title3)
                .foregroundStyle(Color.idroidIndigo)
                .frame(width: 36, height: 36)
                .background(Color.idroidIndigo.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 2) {
                Text(item.displayTitle)
                    .font(.body.weight(.medium))
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()

            HStack(spacing: 4) {
                if item.isCopyable {
                    iconButton("doc.on.doc", "Copy to clipboard") { appState.copyToClipboard(item) }
                }
                iconButton("arrow.up.forward.app", "Open") { appState.open(item) }
                iconButton("square.and.arrow.down", saveHelp) { appState.save(item) }
                Menu {
                    menuItems
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
            }
            .opacity(hovering ? 1 : 0.55)
        }
        .padding(.vertical, 6)
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
        .onTapGesture(count: 2) { appState.open(item) }
        .contextMenu { menuItems }
    }

    @ViewBuilder
    private var menuItems: some View {
        Button("Open") { appState.open(item) }
        Button("Save") { appState.save(item) }
        Button("Save As…") { appState.saveAs(item) }
        if item.isCopyable {
            Button("Copy to Clipboard") { appState.copyToClipboard(item) }
        }
        Divider()
        Button("Delete", role: .destructive) { appState.deleteItem(item) }
    }

    private var saveHelp: String {
        appState.saveLocation.resolvedFolder.map { "Save to \($0.lastPathComponent)" } ?? "Save…"
    }

    private var subtitle: String {
        var parts = ["from \(appState.deviceName(for: item.senderID))"]
        if item.kind != ItemKind.text {
            parts.append(ByteCountFormatter.string(fromByteCount: item.size, countStyle: .file))
        }
        if let date = item.createdDate {
            parts.append(date.formatted(.relative(presentation: .named)))
        }
        return parts.joined(separator: " · ")
    }

    private func iconButton(_ symbol: String, _ help: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).frame(width: 22, height: 22)
        }
        .buttonStyle(.borderless)
        .help(help)
    }
}
