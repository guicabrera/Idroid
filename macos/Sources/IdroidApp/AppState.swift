import AppKit
import Foundation
import Combine

/// App-wide state, the macOS counterpart to IdroidViewModel.kt. Owns the
/// API client, the pairing store, and the always-on sync controller.
@MainActor
final class AppState: ObservableObject {

    @Published var pairing: PairingConfig?
    @Published var inbox: [ItemMeta] = []
    @Published var isRefreshing = false
    @Published var errorMessage: String?
    @Published var statusMessage: String?

    let apiClient = RelayAPIClient()

    private let pairingStore = PairingStore()
    private let syncController = RelaySyncController()
    private let clipboardSyncController = ClipboardSyncController()

    init() {
        syncController.onNewItem = { [weak self] in
            self?.refreshInbox()
        }

        pairing = pairingStore.load()
        if let pairing {
            refreshInbox()
            syncController.connect(pairing, apiClient: apiClient)
            clipboardSyncController.start(pairing, apiClient: apiClient)
        }
    }

    func savePairing(_ config: PairingConfig) {
        pairingStore.save(config)
        pairing = config
        errorMessage = nil
        refreshInbox()
        syncController.connect(config, apiClient: apiClient)
        clipboardSyncController.start(config, apiClient: apiClient)
    }

    func forgetPairing() {
        pairingStore.clear()
        syncController.disconnect()
        clipboardSyncController.stop()
        pairing = nil
        inbox = []
    }

    func refreshInbox() {
        guard let pairing else { return }
        isRefreshing = true
        errorMessage = nil
        Task {
            do {
                let items = try await apiClient.listInbox(pairing)
                // Clipboard pushes (ADR-0002) are applied and deleted as
                // they arrive, not shown as inbox entries.
                inbox = items.filter { $0.kind != ItemKind.clipboard }
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
                let text = try String(contentsOf: result.fileURL, encoding: .utf8)
                try? FileManager.default.removeItem(at: result.fileURL)

                let pasteboard = NSPasteboard.general
                pasteboard.clearContents()
                pasteboard.setString(text, forType: .string)
                clipboardSyncController.noteAppliedRemoteContent(text)

                try await apiClient.deleteItem(pairing, itemID: item.id)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    func deleteItem(_ item: ItemMeta) {
        guard let pairing else { return }
        Task {
            do {
                try await apiClient.deleteItem(pairing, itemID: item.id)
                refreshInbox()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    func sendText(_ text: String) {
        guard let pairing, !text.isEmpty else { return }
        Task {
            do {
                _ = try await apiClient.uploadText(pairing, kind: ItemKind.text, text: text)
                statusMessage = "Sent"
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    func sendFile(_ url: URL) {
        guard let pairing else { return }
        Task {
            do {
                _ = try await apiClient.uploadFile(pairing, fileURL: url)
                statusMessage = "Sent"
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    func downloadItem(_ item: ItemMeta, to destinationDir: URL) {
        guard let pairing else { return }
        Task {
            do {
                let result = try await apiClient.downloadItem(pairing, itemID: item.id)
                let filename = result.filename ?? item.filename ?? "idroid-\(item.id)"
                let destination = destinationDir.appendingPathComponent(filename)
                if FileManager.default.fileExists(atPath: destination.path) {
                    try FileManager.default.removeItem(at: destination)
                }
                try FileManager.default.moveItem(at: result.fileURL, to: destination)
                statusMessage = "Saved to \(destination.lastPathComponent)"
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}
