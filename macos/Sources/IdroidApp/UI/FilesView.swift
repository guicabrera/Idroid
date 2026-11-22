import SwiftUI

/// Browses the folders another device shares (ADR-0004): live, through the
/// relay, while that device is online. Folders already opened are shown
/// straight from memory and refreshed in the background.
struct FilesView: View {
    @EnvironmentObject var appState: AppState
    @State private var path = "/"
    @State private var listings: [String: [FSEntry]] = [:]
    @State private var selection = Set<FSEntry.ID>()
    @State private var sortOrder = [KeyPathComparator(\FSEntry.name, comparator: .localizedStandard)]
    @State private var backStack: [String] = []
    @State private var forwardStack: [String] = []
    @State private var isLoading = false
    @State private var loadError: String?
    @State private var refreshError: String?
    @State private var isMissing = false
    @State private var reloadToken = 0
    @State private var scrollTarget: FSEntry.ID?
    @State private var isSharingPresented = false
    @State private var isDropTargeted = false
    @State private var uploadingCount = 0

    private var device: PeerDevice? {
        appState.devices.first { $0.id == appState.browsingDeviceID }
    }

    /// Folders first, then the table's sort order within each group.
    private var entries: [FSEntry] {
        let all = listings[path] ?? []
        return all.filter(\.isDir).sorted(using: sortOrder) + all.filter { !$0.isDir }.sorted(using: sortOrder)
    }

    var body: some View {
        content
            .navigationTitle(device?.name ?? "Files")
            .toolbar { toolbar }
            .onAppear(perform: pickDefaultDevice)
            .onChange(of: appState.devices) { pickDefaultDevice() }
            .onChange(of: appState.browsingDeviceID) {
                listings = [:]
                backStack = []
                forwardStack = []
                show("/")
            }
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
        } else {
            VStack(spacing: 0) {
                pathBar
                Divider()
                folderContent
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .overlay { dropHighlight }
                    .onDrop(of: [.fileURL], isTargeted: $isDropTargeted, perform: dropFiles)
            }
        }
    }

    @ViewBuilder
    private var folderContent: some View {
        if isMissing {
            ContentUnavailableView {
                Label("Folder not available", systemImage: "folder.badge.questionmark")
            } description: {
                Text("It may have been moved, deleted or is no longer shared on \(device?.name ?? "the device").")
            } actions: {
                Button("Go to Shared Folders") { navigate(to: "/") }
            }
        } else if let loadError, listings[path] == nil {
            ContentUnavailableView {
                Label("Couldn't load this folder", systemImage: "exclamationmark.triangle")
            } description: {
                Text(loadError)
            } actions: {
                Button("Try Again") { reloadToken += 1 }
            }
        } else if let entries = listings[path], entries.isEmpty {
            if path == "/" {
                ContentUnavailableView("Nothing shared", systemImage: "folder.badge.questionmark", description: Text("On \(device?.name ?? "the device"), open Idroid and add a folder under Shared folders."))
            } else {
                ContentUnavailableView("Empty folder", systemImage: "folder")
            }
        } else if listings[path] == nil {
            ProgressView()
        } else {
            table
        }
    }

    /// The current path as clickable segments, so any enclosing folder is one click away.
    private var pathBar: some View {
        let segments = path.split(separator: "/").map(String.init)
        let crumbs = [("Shared", "/")] + segments.indices.map { i in
            (segments[i], "/" + segments[...i].joined(separator: "/"))
        }
        return HStack(spacing: 2) {
            Button {
                navigate(to: Self.parent(of: path))
            } label: {
                Image(systemName: "arrow.up")
            }
            .buttonStyle(.borderless)
            .disabled(path == "/")
            .keyboardShortcut(.upArrow, modifiers: .command)
            .help("Enclosing folder (⌘↑)")
            .padding(.trailing, 6)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 2) {
                    ForEach(Array(crumbs.enumerated()), id: \.offset) { index, crumb in
                        if index > 0 {
                            Image(systemName: "chevron.right")
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                        }
                        let isCurrent = index == crumbs.count - 1
                        Button(crumb.0) { navigate(to: crumb.1) }
                            .buttonStyle(.borderless)
                            .foregroundStyle(isCurrent ? Color.primary : Color.secondary)
                            .fontWeight(isCurrent ? .semibold : .regular)
                            .disabled(isCurrent)
                            .lineLimit(1)
                    }
                }
            }
            .defaultScrollAnchor(.trailing)

            Spacer(minLength: 8)
            if uploadingCount > 0 {
                ProgressView().controlSize(.small)
                Text("Copying \(uploadingCount) file\(uploadingCount == 1 ? "" : "s")…")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let refreshError {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
                    .help("Showing an earlier listing — couldn't refresh: \(refreshError)")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
    }

    /// Files can be dropped into any folder inside a share, not on the list of shares itself.
    private var canDrop: Bool {
        path != "/" && !isMissing && listings[path] != nil
    }

    @ViewBuilder
    private var dropHighlight: some View {
        if isDropTargeted {
            let name = path.split(separator: "/").last.map(String.init) ?? ""
            RoundedRectangle(cornerRadius: 10)
                .strokeBorder(canDrop ? Color.idroidIndigo : Color.secondary, style: StrokeStyle(lineWidth: 2, dash: [7, 5]))
                .background(RoundedRectangle(cornerRadius: 10).fill((canDrop ? Color.idroidIndigo : Color.secondary).opacity(0.06)))
                .overlay(alignment: .bottom) {
                    Text(canDrop ? "Copy to \(name) on \(device?.name ?? "the device")" : "Open a shared folder to copy files into it")
                        .font(.callout.weight(.medium))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(.regularMaterial, in: Capsule())
                        .padding(.bottom, 16)
                }
                .padding(6)
                .allowsHitTesting(false)
        }
    }

    /// Copies files dropped from Finder into the folder being shown — whichever that is when they're dropped.
    private func dropFiles(_ providers: [NSItemProvider]) -> Bool {
        guard canDrop, let deviceID = device?.id else { return false }
        let folder = path
        loadFileURLs(providers) { urls in
            guard !urls.isEmpty else { return }
            uploadingCount += urls.count
            Task {
                await appState.uploadRemote(urls, deviceID: deviceID, folder: folder)
                uploadingCount -= urls.count
                if folder == path { reloadToken += 1 }
            }
        }
        return true
    }

    private var table: some View {
        ScrollViewReader { proxy in
            Table(entries, selection: $selection, sortOrder: $sortOrder) {
                TableColumn("Name", value: \.name, comparator: .localizedStandard) { entry in
                    Label {
                        Text(entry.name).lineLimit(1)
                    } icon: {
                        Image(systemName: entry.symbolName).foregroundStyle(entry.isDir ? Color.idroidIndigo : .secondary)
                    }
                }
                TableColumn("Size", value: \.size) { entry in
                    Text(entry.isDir ? "—" : ByteCountFormatter.string(fromByteCount: entry.size, countStyle: .file))
                        .foregroundStyle(.secondary)
                }
                .width(min: 70, ideal: 90, max: 120)
                TableColumn("Modified", value: \.sortDate) { entry in
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
                openSelection(ids)
            }
            .onChange(of: scrollTarget) {
                guard let scrollTarget else { return }
                proxy.scrollTo(scrollTarget, anchor: .center)
                self.scrollTarget = nil
            }
            .onAppear {
                if let scrollTarget { proxy.scrollTo(scrollTarget, anchor: .center) }
                scrollTarget = nil
            }
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .navigation) {
            ControlGroup {
                Button {
                    goBack()
                } label: {
                    Label("Back", systemImage: "chevron.left")
                }
                .disabled(backStack.isEmpty)
                .keyboardShortcut("[", modifiers: .command)
                .help("Back (⌘[)")

                Button {
                    goForward()
                } label: {
                    Label("Forward", systemImage: "chevron.right")
                }
                .disabled(forwardStack.isEmpty)
                .keyboardShortcut("]", modifiers: .command)
                .help("Forward (⌘])")
            }
            .controlGroupStyle(.navigation)
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
                openSelection(selection)
            } label: {
                Label("Open", systemImage: "arrow.down.forward.square")
            }
            .disabled(selection.count != 1)
            .keyboardShortcut(.downArrow, modifiers: .command)
            .help("Open (⌘↓)")

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
            .keyboardShortcut("r", modifiers: .command)
            .help("Refresh (⌘R)")
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

    /// Goes to [newPath] as a new step in the back/forward history.
    private func navigate(to newPath: String) {
        guard newPath != path else { return }
        backStack.append(path)
        forwardStack = []
        show(newPath)
    }

    private func goBack() {
        guard let previous = backStack.popLast() else { return }
        forwardStack.append(path)
        show(previous)
    }

    private func goForward() {
        guard let next = forwardStack.popLast() else { return }
        backStack.append(path)
        show(next)
    }

    /// When moving up, the folder we came out of is selected, like in Finder.
    private func show(_ newPath: String) {
        let cameFrom = newPath == "/" ? path : (path.hasPrefix(newPath + "/") ? path : nil)
        let child = cameFrom.flatMap { from -> String? in
            let rest = from.dropFirst(newPath == "/" ? 1 : newPath.count + 1)
            guard let first = rest.split(separator: "/").first else { return nil }
            return (newPath == "/" ? "/" : newPath + "/") + first
        }
        loadError = nil
        refreshError = nil
        isMissing = false
        path = newPath
        selection = child.map { [$0] } ?? []
        scrollTarget = child
    }

    private func load() async {
        guard let device, device.online else { return }
        let requested = path
        isLoading = true
        defer { isLoading = false }
        do {
            let result = try await appState.listRemote(deviceID: device.id, path: requested)
            guard !Task.isCancelled else { return }
            listings[requested] = result
            loadError = nil
            refreshError = nil
        } catch {
            guard !Task.isCancelled else { return }
            if case RelayError.http(404) = error, requested != "/" {
                // Moved, deleted or no longer shared: say so instead of silently jumping elsewhere.
                listings = listings.filter { $0.key != requested && !$0.key.hasPrefix(requested + "/") }
                isMissing = true
            } else if listings[requested] != nil {
                refreshError = Self.describe(error)
            } else {
                loadError = Self.describe(error)
            }
        }
    }

    private func openSelection(_ ids: Set<FSEntry.ID>) {
        guard ids.count == 1, let entry = entries.first(where: { $0.id == ids.first }) else { return }
        if entry.isDir { navigate(to: entry.path) } else { open(entry) }
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

    /// For sorting by the Modified column; undated entries sort first.
    var sortDate: Date { modifiedDate ?? .distantPast }

    var modifiedDate: Date? {
        guard let modified else { return nil }
        return ISO8601DateFormatter.relay.date(from: modified) ?? ISO8601DateFormatter().date(from: modified)
    }
}
