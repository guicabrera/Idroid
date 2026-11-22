import AppKit
import Combine
import Foundation
import ServiceManagement
import UserNotifications

/// Where the main window is showing; lives here (not in a view) so the menu
/// bar can jump straight to a section.
enum AppSection: String, Hashable, CaseIterable {
    case inbox, send, files, devices
}

/// Where a send goes: one device, or every device in the group.
enum SendTarget: Hashable {
    case all
    case device(String)
}

/// App-wide state, the macOS counterpart to IdroidViewModel.kt. Owns the
/// API client, the pairing store, and the always-on sync controllers.
@MainActor
final class AppState: ObservableObject {

    @Published var pairing: PairingConfig?
    @Published private(set) var me: PeerDevice?
    @Published private(set) var devices: [PeerDevice] = []
    @Published private(set) var isConnected = false
    @Published var inbox: [ItemMeta] = []
    @Published var isRefreshing = false
    @Published var errorMessage: String?
    @Published var statusMessage: String?
    @Published private(set) var saveLocation: SaveLocation
    /// Folders on this Mac that paired devices can browse (ADR-0004).
    @Published private(set) var sharedFolders: [SharedFolder]

    @Published var section: AppSection = .inbox
    @Published var isAddDevicePresented = false
    /// The device shown in the Files section.
    @Published var browsingDeviceID: String?

    @Published var clipboardSyncEnabled: Bool {
        didSet {
            defaults.set(clipboardSyncEnabled, forKey: Keys.clipboardSync)
            clipboardSyncController.isEnabled = clipboardSyncEnabled
        }
    }
    @Published var clipboardSyncImages: Bool {
        didSet {
            defaults.set(clipboardSyncImages, forKey: Keys.clipboardImages)
            clipboardSyncController.syncImages = clipboardSyncImages
        }
    }
    @Published var notificationsEnabled: Bool {
        didSet { defaults.set(notificationsEnabled, forKey: Keys.notifications) }
    }

    let apiClient = RelayAPIClient()

    private let defaults = UserDefaults.standard
    private let pairingStore = PairingStore()
    private let saveLocationStore = SaveLocationStore()
    private let sharedFoldersStore = SharedFoldersStore()
    private let syncController = RelaySyncController()
    private let clipboardSyncController = ClipboardSyncController()
    /// Inbox ids already seen, so only genuinely new arrivals notify.
    private var knownItemIDs: Set<String>?
    private var statusClearTask: Task<Void, Never>?

    private enum Keys {
        static let clipboardSync = "clipboardSync.enabled"
        static let clipboardImages = "clipboardSync.images"
        static let notifications = "notifications.enabled"
    }

    init() {
        saveLocation = saveLocationStore.load()
        sharedFolders = sharedFoldersStore.load()
        clipboardSyncEnabled = defaults.object(forKey: Keys.clipboardSync) as? Bool ?? true
        clipboardSyncImages = defaults.object(forKey: Keys.clipboardImages) as? Bool ?? true
        notificationsEnabled = defaults.object(forKey: Keys.notifications) as? Bool ?? true
        clipboardSyncController.isEnabled = clipboardSyncEnabled
        clipboardSyncController.syncImages = clipboardSyncImages

        syncController.onNewItem = { [weak self] in self?.refreshInbox() }
        syncController.onDevicesChanged = { [weak self] in self?.refreshDevices() }
        syncController.onFSRequest = { [weak self] event in
            guard let self, let pairing = self.pairing else { return }
            let folders = self.sharedFolders
            let apiClient = self.apiClient
            Task.detached {
                await FileShareResponder.handle(event, folders: folders, config: pairing, apiClient: apiClient)
            }
        }
        syncController.onConnectionChange = { [weak self] connected in
            guard let self, self.isConnected != connected else { return }
            self.isConnected = connected
            // Catch up on anything that arrived (and on online/offline
            // changes) while the connection was down.
            if connected {
                self.refreshInbox()
                self.refreshDevices()
            }
        }

        pairing = pairingStore.load()
        if let pairing { start(pairing) }
    }

    // MARK: - Pairing and devices

    var thisDeviceName: String { me?.name ?? Host.current().localizedName ?? "Mac" }

    func savePairing(_ config: PairingConfig) {
        pairingStore.save(config)
        pairing = config
        errorMessage = nil
        start(config)
    }

    private func start(_ config: PairingConfig) {
        refreshInbox()
        refreshDevices()
        syncController.connect(config, apiClient: apiClient)
        clipboardSyncController.start(config, apiClient: apiClient)
    }

    func refreshDevices() {
        guard let pairing else { return }
        Task {
            do {
                let response = try await apiClient.listDevices(pairing)
                me = response.me
                devices = response.devices
            } catch RelayError.http(401) {
                // This Mac was removed from another device: back to pairing.
                forgetPairing()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    func deviceName(for id: String) -> String {
        devices.first { $0.id == id }?.name ?? "Unknown device"
    }

    func removeDevice(_ device: PeerDevice) {
        guard let pairing else { return }
        Task {
            do {
                try await apiClient.removeDevice(pairing, deviceID: device.id)
                showStatus("Removed \(device.name)")
                refreshDevices()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    /// Unpairs this Mac from every device and returns to the pairing screen.
    func leave() {
        guard let pairing else { return }
        Task {
            if let id = me?.id {
                try? await apiClient.removeDevice(pairing, deviceID: id)
            }
            forgetPairing()
        }
    }

    func forgetPairing() {
        pairingStore.clear()
        syncController.disconnect()
        clipboardSyncController.stop()
        pairing = nil
        me = nil
        devices = []
        inbox = []
        knownItemIDs = nil
        isConnected = false
    }

    // MARK: - Inbox

    func refreshInbox() {
        guard let pairing else { return }
        isRefreshing = true
        Task {
            do {
                let items = try await apiClient.listInbox(pairing)
                // Clipboard pushes (ADR-0002) are applied and deleted as
                // they arrive, not shown as inbox entries.
                let visible = items.filter { $0.kind != ItemKind.clipboard }
                    .sorted { $0.createdAt > $1.createdAt }
                notifyArrivals(visible)
                inbox = visible
                isRefreshing = false
                let clipboardItems = items.filter { $0.kind == ItemKind.clipboard }
                if !clipboardItems.isEmpty {
                    await applyIncomingClipboardItems(clipboardItems, pairing: pairing)
                }
            } catch {
                errorMessage = error.localizedDescription
                isRefreshing = false
            }
        }
    }

    private func applyIncomingClipboardItems(_ items: [ItemMeta], pairing: PairingConfig) async {
        for item in items {
            do {
                let result = try await apiClient.downloadItem(pairing, itemID: item.id)
                let data = try Data(contentsOf: result.fileURL)
                try? FileManager.default.removeItem(at: result.fileURL)
                clipboardSyncController.applyRemoteContent(data, contentType: item.contentType)

                try await apiClient.deleteItem(pairing, itemID: item.id)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    func deleteItem(_ item: ItemMeta) {
        guard let pairing else { return }
        inbox.removeAll { $0.id == item.id }
        Task {
            do {
                try await apiClient.deleteItem(pairing, itemID: item.id)
            } catch {
                errorMessage = error.localizedDescription
                refreshInbox()
            }
        }
    }

    func clearInbox() {
        for item in inbox { deleteItem(item) }
    }

    /// Saves to the default location, or asks when that's "Ask each time".
    func save(_ item: ItemMeta) {
        if let dir = saveLocation.resolvedFolder {
            downloadItem(item, to: dir)
        } else {
            saveAs(item)
        }
    }

    func saveAs(_ item: ItemMeta) {
        let start = saveLocation.resolvedFolder ?? SaveLocation.downloadsURL
        NSApp.activate(ignoringOtherApps: true)
        if let dir = FolderPicker.pick(prompt: "Save Here", startingAt: start) {
            downloadItem(item, to: dir)
        }
    }

    func saveAll() {
        guard let dir = saveLocation.resolvedFolder ?? FolderPicker.pick(prompt: "Save Here", startingAt: SaveLocation.downloadsURL) else { return }
        for item in inbox { downloadItem(item, to: dir) }
    }

    /// Downloads to a temporary folder and opens it with the default app.
    func open(_ item: ItemMeta) {
        guard let pairing else { return }
        Task {
            do {
                let result = try await apiClient.downloadItem(pairing, itemID: item.id)
                let dir = FileManager.default.temporaryDirectory.appendingPathComponent("Idroid", isDirectory: true)
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let destination = dir.appendingPathComponent(result.filename ?? item.filename ?? "idroid-\(item.id)")
                try? FileManager.default.removeItem(at: destination)
                try FileManager.default.moveItem(at: result.fileURL, to: destination)
                NSWorkspace.shared.open(destination)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    /// Puts a received text item (or image) on this Mac's clipboard.
    func copyToClipboard(_ item: ItemMeta) {
        guard let pairing else { return }
        Task {
            do {
                let result = try await apiClient.downloadItem(pairing, itemID: item.id)
                let data = try Data(contentsOf: result.fileURL)
                try? FileManager.default.removeItem(at: result.fileURL)
                clipboardSyncController.applyRemoteContent(data, contentType: item.contentType)
                showStatus("Copied to clipboard")
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    var lastCustomSaveFolder: URL? { saveLocationStore.lastCustomFolder }

    func setSaveLocation(_ location: SaveLocation) {
        saveLocationStore.save(location)
        saveLocation = location
    }

    func downloadItem(_ item: ItemMeta, to destinationDir: URL) {
        guard let pairing else { return }
        Task {
            do {
                let result = try await apiClient.downloadItem(pairing, itemID: item.id)
                let filename = result.filename ?? item.filename ?? "idroid-\(item.id)"
                try FileManager.default.createDirectory(at: destinationDir, withIntermediateDirectories: true)
                let destination = Self.uniqueDestination(for: filename, in: destinationDir)
                try FileManager.default.moveItem(at: result.fileURL, to: destination)
                showStatus("Saved to \(destinationDir.lastPathComponent)/\(destination.lastPathComponent)")
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    /// Finder-style "name 2.ext" suffixing, so saving into a shared folder
    /// like Downloads never silently replaces an existing file.
    private static func uniqueDestination(for filename: String, in dir: URL) -> URL {
        let candidate = dir.appendingPathComponent(filename)
        guard FileManager.default.fileExists(atPath: candidate.path) else { return candidate }

        let base = (filename as NSString).deletingPathExtension
        let ext = (filename as NSString).pathExtension
        var n = 2
        while true {
            let name = ext.isEmpty ? "\(base) \(n)" : "\(base) \(n).\(ext)"
            let url = dir.appendingPathComponent(name)
            if !FileManager.default.fileExists(atPath: url.path) { return url }
            n += 1
        }
    }

    // MARK: - Shared folders and remote browsing (ADR-0004)

    func addSharedFolders() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = true
        panel.prompt = "Share"
        panel.message = "Your paired devices will be able to browse and download from these folders."
        NSApp.activate(ignoringOtherApps: true)
        guard panel.runModal() == .OK else { return }
        let paths = sharedFolders.map(\.url.path) + panel.urls.map(\.path).filter { path in
            !sharedFolders.contains { $0.url.path == path }
        }
        setSharedFolders(paths)
    }

    func removeSharedFolder(_ folder: SharedFolder) {
        setSharedFolders(sharedFolders.filter { $0 != folder }.map(\.url.path))
    }

    private func setSharedFolders(_ paths: [String]) {
        sharedFolders = SharedFoldersStore.named(paths: paths)
        sharedFoldersStore.save(sharedFolders)
    }

    func browse(_ device: PeerDevice) {
        browsingDeviceID = device.id
        section = .files
    }

    func listRemote(deviceID: String, path: String) async throws -> [FSEntry] {
        guard let pairing else { return [] }
        return try await apiClient.listRemote(pairing, deviceID: deviceID, path: path)
    }

    /// Downloads a remote file to a temporary folder and opens it with the default app.
    func openRemote(_ entry: FSEntry, deviceID: String) {
        guard let pairing else { return }
        Task {
            do {
                let tempURL = try await apiClient.downloadRemote(pairing, deviceID: deviceID, path: entry.path)
                let dir = FileManager.default.temporaryDirectory.appendingPathComponent("Idroid", isDirectory: true)
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let destination = dir.appendingPathComponent(entry.name)
                try? FileManager.default.removeItem(at: destination)
                try FileManager.default.moveItem(at: tempURL, to: destination)
                NSWorkspace.shared.open(destination)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    /// Saves remote files to the default save location (asking when that's "Ask each time").
    func saveRemote(_ entries: [FSEntry], deviceID: String, askForFolder: Bool = false) {
        guard let pairing, !entries.isEmpty else { return }
        let folder: URL?
        if askForFolder || saveLocation.resolvedFolder == nil {
            NSApp.activate(ignoringOtherApps: true)
            folder = FolderPicker.pick(prompt: "Save Here", startingAt: saveLocation.resolvedFolder ?? SaveLocation.downloadsURL)
        } else {
            folder = saveLocation.resolvedFolder
        }
        guard let folder else { return }
        Task {
            do {
                for entry in entries {
                    let tempURL = try await apiClient.downloadRemote(pairing, deviceID: deviceID, path: entry.path)
                    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                    let destination = Self.uniqueDestination(for: entry.name, in: folder)
                    try FileManager.default.moveItem(at: tempURL, to: destination)
                }
                let what = entries.count == 1 ? entries[0].name : "\(entries.count) files"
                showStatus("Saved \(what) to \(folder.lastPathComponent)")
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    // MARK: - Sending

    private func recipients(_ target: SendTarget) -> [PeerDevice] {
        switch target {
        case .all: return devices
        case .device(let id): return devices.filter { $0.id == id }
        }
    }

    private func targetName(_ target: SendTarget) -> String {
        switch target {
        case .all: return devices.count == 1 ? devices[0].name : "all devices"
        case .device(let id): return deviceName(for: id)
        }
    }

    func sendText(_ text: String, to target: SendTarget) {
        guard let pairing, !text.isEmpty else { return }
        let peers = recipients(target)
        guard !peers.isEmpty else { errorMessage = "No device to send to — add one first"; return }
        Task {
            do {
                for peer in peers {
                    _ = try await apiClient.uploadText(pairing, to: peer.id, kind: ItemKind.text, text: text)
                }
                showStatus("Sent to \(targetName(target))")
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    func sendFiles(_ urls: [URL], to target: SendTarget) {
        guard let pairing, !urls.isEmpty else { return }
        let peers = recipients(target)
        guard !peers.isEmpty else { errorMessage = "No device to send to — add one first"; return }
        Task {
            do {
                for url in urls {
                    for peer in peers {
                        _ = try await apiClient.uploadFile(pairing, to: peer.id, fileURL: url)
                    }
                }
                let what = urls.count == 1 ? urls[0].lastPathComponent : "\(urls.count) files"
                showStatus("Sent \(what) to \(targetName(target))")
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    func pickAndSendFiles(to target: SendTarget) {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.prompt = "Send"
        NSApp.activate(ignoringOtherApps: true)
        if panel.runModal() == .OK {
            sendFiles(panel.urls, to: target)
        }
    }

    /// Sends whatever is on this Mac's clipboard right now, on demand —
    /// useful while automatic sync is paused.
    func sendClipboard(to target: SendTarget) {
        guard let pairing else { return }
        guard let (data, contentType) = clipboardSyncController.currentPayload() else {
            errorMessage = "Nothing on the clipboard that can be sent"
            return
        }
        let peers = recipients(target)
        Task {
            do {
                for peer in peers {
                    _ = try await ClipboardSyncController.upload(data, contentType: contentType, to: peer.id, config: pairing, apiClient: apiClient)
                }
                showStatus("Clipboard sent to \(targetName(target))")
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    // MARK: - Window, status, notifications, login item

    func showMainWindow(_ section: AppSection? = nil, openWindow: () -> Void) {
        if let section { self.section = section }
        openWindow()
        NSApp.activate(ignoringOtherApps: true)
    }

    func showStatus(_ message: String) {
        errorMessage = nil
        statusMessage = message
        statusClearTask?.cancel()
        statusClearTask = Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if !Task.isCancelled { statusMessage = nil }
        }
    }

    private func notifyArrivals(_ items: [ItemMeta]) {
        let ids = Set(items.map(\.id))
        defer { knownItemIDs = ids }
        // The first fetch after launch is a backlog, not new arrivals.
        guard let known = knownItemIDs, notificationsEnabled, Bundle.main.bundleIdentifier != nil else { return }
        for item in items where !known.contains(item.id) {
            let content = UNMutableNotificationContent()
            content.title = deviceName(for: item.senderID)
            content.body = item.kind == ItemKind.text ? "Sent you text" : "Sent \(item.filename ?? "a file")"
            content.sound = .default
            UNUserNotificationCenter.current().add(
                UNNotificationRequest(identifier: item.id, content: content, trigger: nil)
            )
        }
    }

    var launchAtLogin: Bool {
        get { SMAppService.mainApp.status == .enabled }
        set {
            do {
                if newValue { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            } catch {
                errorMessage = "Couldn't change Open at Login: \(error.localizedDescription)"
            }
            objectWillChange.send()
        }
    }
}
