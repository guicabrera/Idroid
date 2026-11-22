import SwiftUI

/// Browses the folders another device shares (ADR-0004): live, through the
/// relay, while that device is online.
struct FilesView: View {
    @EnvironmentObject var appState: AppState
    @State private var path = "/"
    @State private var entries: [FSEntry] = []
    @State private var selection = Set<FSEntry.ID>()
    @State private var isLoading = false
    @State private var loadError: String?
    @State private var reloadToken = 0
    @State private var isSharingPresented = false

    private var device: PeerDevice? {
        appState.devices.first { $0.id == appState.browsingDeviceID }
    }

    var body: some View {
        content
            .navigationTitle(device?.name ?? "Files")
            .navigationSubtitle(path == "/" ? "" : path)
            .toolbar { toolbar }
            .onAppear(perform: pickDefaultDevice)
            .onChange(of: appState.devices) { pickDefaultDevice() }
            .onChange(of: appState.browsingDeviceID) { navigate(to: "/") }
            .sheet(isPresented: $isSharingPresented) { SharingSheet() }
            .task(id: LoadKey(deviceID: device?.id, online: device?.online ?? false, path: path, token: reloadToken)) {
                await load()
            }
    }

    @ViewBuilder
    private var content: some View {
        if appState.devices.isEmpty {
            ContentUnavailableView("No devices", systemImage: "laptopcomputer.and.iphone", description: Text("Pair a device to browse its files."))
        } else if let device, !device.online {
            ContentUnavailableView("\(device.name) is offline", systemImage: "wifi.slash", description: Text("Open Idroid on it to browse its files."))
        } else if let loadError {
            ContentUnavailableView {
                Label("Couldn't load this folder", systemImage: "exclamationmark.triangle")
            } description: {
                Text(loadError)
            } actions: {
                Button("Try Again") { reloadToken += 1 }
            }
        } else if entries.isEmpty && !isLoading {
            if path == "/" {
                ContentUnavailableView("Nothing shared", systemImage: "folder.badge.questionmark", description: Text("On \(device?.name ?? "the device"), open Idroid and add a folder under Shared folders."))
            } else {
                ContentUnavailableView("Empty folder", systemImage: "folder")
            }
        } else {
            table
        }
    }

    private var table: some View {
        Table(entries, selection: $selection) {
            TableColumn("Name") { entry in
                Label {
                    Text(entry.name).lineLimit(1)
                } icon: {
                    Image(systemName: entry.symbolName).foregroundStyle(entry.isDir ? Color.idroidIndigo : .secondary)
                }
            }
            TableColumn("Size") { entry in
                Text(entry.isDir ? "—" : ByteCountFormatter.string(fromByteCount: entry.size, countStyle: .file))
                    .foregroundStyle(.secondary)
            }
            .width(min: 70, ideal: 90, max: 120)
            TableColumn("Modified") { entry in
                Text(entry.modifiedDate?.formatted(date: .abbreviated, time: .shortened) ?? "")
                    .foregroundStyle(.secondary)
            }
            .width(min: 120, ideal: 160, max: 200)
        }
        .contextMenu(forSelectionType: FSEntry.ID.self) { ids in
            let files = selectedFiles(ids)
            if ids.count == 1, let entry = entries.first(where: { $0.id == ids.first }), entry.isDir {
                Button("Open") { navigate(to: entry.path) }
            } else if !files.isEmpty {
                if files.count == 1 {
                    Button("Open") { open(files[0]) }
                }
                Button("Save") { save(files) }
                Button("Save To…") { save(files, ask: true) }
            }
        } primaryAction: { ids in
            guard ids.count == 1, let entry = entries.first(where: { $0.id == ids.first }) else { return }
            if entry.isDir { navigate(to: entry.path) } else { open(entry) }
        }
        .overlay {
            if isLoading && entries.isEmpty { ProgressView() }
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .navigation) {
            Button {
                navigate(to: Self.parent(of: path))
            } label: {
                Label("Back", systemImage: "chevron.left")
            }
            .disabled(path == "/")
            .help("Enclosing folder")
        }
        ToolbarItemGroup {
            Button {
                isSharingPresented = true
            } label: {
                Label("Shared Folders", systemImage: "folder.badge.person.crop")
            }
            .help("Choose which folders on this Mac your devices can browse")

            Picker("Device", selection: $appState.browsingDeviceID) {
                ForEach(appState.devices) { device in
                    Text(device.name + (device.online ? "" : " (offline)")).tag(Optional(device.id))
                }
            }
            .frame(minWidth: 140)
            .help("Device to browse")

            Button {
                save(selectedFiles(selection))
            } label: {
                Label("Save", systemImage: "square.and.arrow.down")
            }
            .disabled(selectedFiles(selection).isEmpty)
            .help("Save selected files")

            Button {
                reloadToken += 1
            } label: {
                if isLoading {
                    ProgressView().controlSize(.small)
                } else {
                    Label("Refresh", systemImage: "arrow.clockwise")
                }
            }
            .help("Refresh")
        }
    }

    // MARK: - Actions

    private struct LoadKey: Equatable {
        let deviceID: String?
        let online: Bool
        let path: String
        let token: Int
    }

    private func pickDefaultDevice() {
        if device == nil {
            appState.browsingDeviceID = appState.devices.first { $0.online }?.id ?? appState.devices.first?.id
        }
    }

    private func navigate(to newPath: String) {
        selection = []
        entries = []
        loadError = nil
        path = newPath
    }

    private func load() async {
        guard let device, device.online else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            let result = try await appState.listRemote(deviceID: device.id, path: path)
            guard !Task.isCancelled else { return }
            entries = result
            loadError = nil
        } catch {
            guard !Task.isCancelled else { return }
            if case RelayError.http(404) = error, path != "/" {
                // The folder is gone or no longer shared.
                navigate(to: "/")
                return
            }
            loadError = Self.describe(error)
        }
    }

    private func selectedFiles(_ ids: Set<FSEntry.ID>) -> [FSEntry] {
        entries.filter { ids.contains($0.id) && !$0.isDir }
    }

    private func open(_ entry: FSEntry) {
        guard let id = device?.id else { return }
        appState.openRemote(entry, deviceID: id)
    }

    private func save(_ files: [FSEntry], ask: Bool = false) {
        guard let id = device?.id else { return }
        appState.saveRemote(files, deviceID: id, askForFolder: ask)
    }

    static func parent(of path: String) -> String {
        var segments = path.split(separator: "/")
        guard !segments.isEmpty else { return "/" }
        segments.removeLast()
        return "/" + segments.joined(separator: "/")
    }

    private static func describe(_ error: Error) -> String {
        switch error {
        case RelayError.http(503): return "The device went offline."
        case RelayError.http(504): return "The device didn't answer. Make sure Idroid is running on it."
        default: return error.localizedDescription
        }
    }
}

/// This Mac's shared folders, reachable from the Files toolbar as well as Settings → Sharing.
private struct SharingSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            SharingSettings()
            Divider()
            HStack {
                Spacer()
                Button("Done") { dismiss() }
                    .keyboardShortcut(.defaultAction)
            }
            .padding(12)
        }
        .frame(width: 480, height: 360)
    }
}

extension FSEntry {
    var symbolName: String {
        if isDir { return "folder.fill" }
        let type = mime ?? ""
        if type.hasPrefix("image/") { return "photo" }
        if type.hasPrefix("video/") { return "film" }
        if type.hasPrefix("audio/") { return "waveform" }
        if type == "application/pdf" { return "doc.richtext" }
        if type.contains("zip") || type.contains("compressed") { return "doc.zipper" }
        if type.hasPrefix("text/") { return "doc.text" }
        return "doc"
    }

    var modifiedDate: Date? {
        guard let modified else { return nil }
        return ISO8601DateFormatter.relay.date(from: modified) ?? ISO8601DateFormatter().date(from: modified)
    }
}
