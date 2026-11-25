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
    /// Named commands paired devices may run (ADR-0005).
    @Published var commandActions: [CommandAction] {
        didSet { commandStore.saveActions(commandActions) }
    }
    /// Lets paired devices run any command and complete paths; off by default.
    @Published var terminalEnabled: Bool {
        didSet { commandStore.terminalEnabled = terminalEnabled }
    }

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
    // Shared mouse and keyboard (ADR-0008).
    @Published var inputShareEnabled: Bool {
        didSet { defaults.set(inputShareEnabled, forKey: Keys.inputShare); applyInputShare() }
    }
    @Published var inputShareDeviceID: String? {
        didSet { defaults.set(inputShareDeviceID, forKey: Keys.inputShareDevice); applyInputShare() }
    }
    @Published var inputShareEdge: InputShareController.Edge {
        didSet { defaults.set(inputShareEdge.rawValue, forKey: Keys.inputShareEdge); applyInputShare() }
    }
    @Published private(set) var inputShareStatus: InputShareController.Status = .off
    private let inputShareController = InputShareController()

    func applyInputShare() {
        inputShareController.configure(
            enabled: inputShareEnabled, deviceID: inputShareDeviceID, edge: inputShareEdge,
            config: pairing, apiClient: apiClient
        )
    }

    @Published var notificationsEnabled: Bool {
        didSet { defaults.set(notificationsEnabled, forKey: Keys.notifications) }
    }

    let apiClient = RelayAPIClient()

    private let defaults = UserDefaults.standard
    private let pairingStore = PairingStore()
    private let saveLocationStore = SaveLocationStore()
    private let sharedFoldersStore = SharedFoldersStore()
    private let commandStore = CommandStore()
    private let syncController = RelaySyncController()
    private let clipboardSyncController = ClipboardSyncController()
    /// Inbox ids already seen, so only genuinely new arrivals notify.
    private var knownItemIDs: Set<String>?
    private var statusClearTask: Task<Void, Never>?

    private enum Keys {
        static let clipboardSync = "clipboardSync.enabled"
        static let clipboardImages = "clipboardSync.images"
        static let notifications = "notifications.enabled"
        static let inputShare = "inputShare.enabled"
        static let inputShareDevice = "inputShare.device"
        static let inputShareEdge = "inputShare.edge"
    }

    init() {
        saveLocation = saveLocationStore.load()
        sharedFolders = sharedFoldersStore.load()
        commandActions = commandStore.loadActions()
        terminalEnabled = commandStore.terminalEnabled
        clipboardSyncEnabled = defaults.object(forKey: Keys.clipboardSync) as? Bool ?? true
        clipboardSyncImages = defaults.object(forKey: Keys.clipboardImages) as? Bool ?? true
        notificationsEnabled = defaults.object(forKey: Keys.notifications) as? Bool ?? true
        inputShareEnabled = defaults.bool(forKey: Keys.inputShare)
        inputShareDeviceID = defaults.string(forKey: Keys.inputShareDevice)
        inputShareEdge = InputShareController.Edge(rawValue: defaults.string(forKey: Keys.inputShareEdge) ?? "") ?? .right
        inputShareController.onStatusChange = { [weak self] status in self?.inputShareStatus = status }
        clipboardSyncController.isEnabled = clipboardSyncEnabled
        clipboardSyncController.syncImages = clipboardSyncImages

        syncController.onNewItem = { [weak self] in self?.refreshInbox() }
        syncController.onDevicesChanged = { [weak self] in self?.refreshDevices() }
        syncController.onFSRequest = { [weak self] event in
            guard let self, let pairing = self.pairing else { return }
            let folders = self.sharedFolders
            let apiClient = self.apiClient
            if ["commands", "exec", "complete"].contains(event.op ?? "") {
                let actions = self.commandActions
                let terminal = self.terminalEnabled
                let sender = event.senderID.map(self.deviceName(for:)) ?? "Unknown device"
                Task.detached {
                    await CommandResponder.handle(event, actions: actions, terminalEnabled: terminal, deviceName: sender, config: pairing, apiClient: apiClient)
                }
                return
            }
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

        NotificationCenter.default.addObserver(forName: ShareHandoff.requested, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.receiveShare() }
        }
        // Launched by the Share menu: the URL may have arrived before this observer existed.
        receiveShare()
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
        applyInputShare()
    }

    func refreshDevices() {
        Task { await loadDevices() }
    }

    private func loadDevices() async {
        guard let pairing else { return }
        do {
            let response = try await apiClient.listDevices(pairing)
            me = response.me
            devices = response.devices
            probeControllable()
        } catch RelayError.http(401) {
            // This Mac was removed from another device: back to pairing.
            forgetPairing()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Devices that answer remote commands (Macs running Idroid); only those
    /// get Terminal/Copilot/VS Code/SSH. The relay doesn't know device types,
    /// so each online device is asked once; a phone doesn't answer.
    @Published private(set) var controllableIDs: Set<String> = []
    private var probedIDs: Set<String> = []

    private func probeControllable() {
        guard let pairing else { return }
        for device in devices where device.online && !probedIDs.contains(device.id) {
            probedIDs.insert(device.id)
            Task {
                if (try? await apiClient.commandCatalog(pairing, deviceID: device.id)) != nil {
                    controllableIDs.insert(device.id)
                } else {
                    probedIDs.remove(device.id) // ask again next refresh
                }
            }
        }
    }

    func isControllable(_ device: PeerDevice) -> Bool { controllableIDs.contains(device.id) }

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
                // A folder per open, so two files with the same name (or one still open) aren't overwritten.
                let dir = FileManager.default.temporaryDirectory
                    .appendingPathComponent("Idroid", isDirectory: true)
                    .appendingPathComponent(UUID().uuidString, isDirectory: true)
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let destination = dir.appendingPathComponent(entry.name)
                try FileManager.default.moveItem(at: tempURL, to: destination)
                NSWorkspace.shared.open(destination)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    /// Uploads files dropped from Finder into `folder` on another device.
    /// Doesn't touch the save location: this writes on the other side only.
    /// Returns once every file is done (or the first one failed).
    func uploadRemote(_ urls: [URL], deviceID: String, folder: String) async {
        guard let pairing, !urls.isEmpty else { return }
        var uploaded = 0
        var skippedFolders = 0
        for url in urls {
            if (try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true {
                skippedFolders += 1
                continue
            }
            let target = (folder == "/" ? "" : folder) + "/" + url.lastPathComponent
            do {
                _ = try await apiClient.uploadRemote(pairing, deviceID: deviceID, path: target, fileURL: url)
                uploaded += 1
            } catch {
                errorMessage = "Couldn't copy \(url.lastPathComponent): \(error.localizedDescription)"
                return
            }
        }
        if skippedFolders > 0 {
            errorMessage = skippedFolders == 1 ? "Folders can't be copied yet; drop the files inside it instead." : "\(skippedFolders) folders skipped: folders can't be copied yet."
        }
        if uploaded > 0 {
            let what = uploaded == 1 ? urls.first { !$0.hasDirectoryPath }?.lastPathComponent ?? "1 file" : "\(uploaded) files"
            let folderName = folder.split(separator: "/").last.map(String.init) ?? folder
            showStatus("Copied \(what) to \(folderName) on \(deviceName(for: deviceID))")
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

    /// `notify` also reports the outcome as a system notification, for sends
    /// started outside the main window (the Share menu).
    func sendText(_ text: String, to target: SendTarget, notify: Bool = false) {
        guard let pairing, !text.isEmpty else { return }
        let peers = recipients(target)
        guard !peers.isEmpty else { errorMessage = "No device to send to — add one first"; return }
        Task {
            do {
                for peer in peers {
                    _ = try await apiClient.uploadText(pairing, to: peer.id, kind: ItemKind.text, text: text)
                }
                showStatus("Sent to \(targetName(target))", notify: notify)
            } catch {
                showError(error.localizedDescription, notify: notify)
            }
        }
    }

    func sendFiles(_ urls: [URL], to target: SendTarget, notify: Bool = false) {
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
                showStatus("Sent \(what) to \(targetName(target))", notify: notify)
            } catch {
                showError(error.localizedDescription, notify: notify)
            }
        }
    }

    /// Something was sent to Idroid from another app's Share menu. With one
    /// device it goes straight there; with more, a menu at the pointer asks where.
    func receiveShare() {
        guard let payload = ShareHandoff.take() else { return }
        guard pairing != nil else {
            showError("Pair a device before sharing to Idroid", notify: true)
            return
        }
        Task {
            if devices.isEmpty { await loadDevices() }
            guard !devices.isEmpty else {
                showError("No device to send to — add one first", notify: true)
                return
            }
            guard let target = devices.count == 1 ? .device(devices[0].id) : chooseShareTarget() else { return }
            sendFiles(payload.files, to: target, notify: true)
            for text in payload.texts { sendText(text, to: target, notify: true) }
        }
    }

    private func chooseShareTarget() -> SendTarget? {
        let menu = NSMenu()
        menu.addItem(withTitle: "Send with Idroid to", action: nil, keyEquivalent: "").isEnabled = false
        var choice: SendTarget?
        func add(_ title: String, _ target: SendTarget) {
            let item = ClosureMenuItem(title: title) { choice = target }
            menu.addItem(item)
        }
        for device in devices { add(device.name, .device(device.id)) }
        menu.addItem(.separator())
        add("All Devices", .all)
        NSApp.activate(ignoringOtherApps: true)
        menu.popUp(positioning: nil, at: NSEvent.mouseLocation, in: nil)
        return choice
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

    func showStatus(_ message: String, notify: Bool = false) {
        if notify { postNotification(title: "Idroid", body: message) }
        errorMessage = nil
        statusMessage = message
        statusClearTask?.cancel()
        statusClearTask = Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if !Task.isCancelled { statusMessage = nil }
        }
    }

    private func showError(_ message: String, notify: Bool) {
        errorMessage = message
        if notify { postNotification(title: "Idroid couldn't send", body: message) }
    }

    private func postNotification(title: String, body: String) {
        guard Bundle.main.bundleIdentifier != nil else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        )
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

/// An NSMenuItem that runs a closure, for menus built in code.
private final class ClosureMenuItem: NSMenuItem {
    private let handler: () -> Void

    init(title: String, handler: @escaping () -> Void) {
        self.handler = handler
        super.init(title: title, action: #selector(run), keyEquivalent: "")
        target = self
    }

    required init(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    @objc private func run() { handler() }
}
