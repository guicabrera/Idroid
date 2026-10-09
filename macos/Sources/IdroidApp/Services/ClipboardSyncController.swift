import AppKit
import CryptoKit
import UniformTypeIdentifiers

/// Polls `NSPasteboard.general` for changes and pushes new text/links and
/// images to every other device in this Mac's group automatically — the macOS counterpart to
/// `ClipboardAccessibilityService.kt` (ADR-0002). No special permission is
/// needed here, unlike Android: any app can poll
/// `NSPasteboard.changeCount` at any time, which is exactly the platform
/// asymmetry ADR-0002 is about.
final class ClipboardSyncController {

    /// Clipboard pushes are applied in memory on the other side; anything
    /// bigger belongs in a regular file send, not the clipboard.
    static let maxPayloadBytes = 20 << 20

    /// Settings toggles (AppState keeps them in UserDefaults). Pausing
    /// still applies incoming pushes; it only stops sending local copies.
    var isEnabled = true
    var syncImages = true

    private var timer: Timer?
    private var lastSeenChangeCount = NSPasteboard.general.changeCount
    private var lastAppliedHash: String?

    private var config: PairingConfig?
    private var apiClient: RelayAPIClient?

    func start(_ config: PairingConfig, apiClient: RelayAPIClient) {
        self.config = config
        self.apiClient = apiClient
        lastSeenChangeCount = NSPasteboard.general.changeCount
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.pollLocalClipboard()
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        config = nil
        apiClient = nil
    }

    /// Writes a remote clipboard push to the pasteboard and records it, so
    /// the bump that write causes isn't mistaken for a new local copy (and
    /// re-uploaded) on the next poll tick.
    func applyRemoteContent(_ data: Data, contentType: String) {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        if contentType.hasPrefix("image/") {
            if contentType == "image/png" {
                pasteboard.setData(data, forType: .png)
            } else if let image = NSImage(data: data) {
                pasteboard.writeObjects([image])
            } else {
                return
            }
        } else {
            pasteboard.setString(String(decoding: data, as: UTF8.self), forType: .string)
        }
        lastAppliedHash = Self.hash(data)
        lastSeenChangeCount = pasteboard.changeCount
    }

    private func pollLocalClipboard() {
        let pasteboard = NSPasteboard.general
        guard pasteboard.changeCount != lastSeenChangeCount else { return }
        lastSeenChangeCount = pasteboard.changeCount

        guard isEnabled, let (data, contentType) = currentPayload() else { return }
        if contentType.hasPrefix("image/") && !syncImages { return }
        guard Self.hash(data) != lastAppliedHash else { return } // our own echo, belt-and-suspenders with the changeCount check above

        guard let config, let apiClient else { return }
        Task {
            // Fetched per copy rather than cached, so a device paired a
            // moment ago gets the very next copy.
            guard let peers = try? await apiClient.listDevices(config).devices else { return }
            for peer in peers {
                _ = try? await Self.upload(data, contentType: contentType, to: peer.id, config: config, apiClient: apiClient)
            }
        }
    }

    /// What a copy right now would sync, or nil if there's nothing
    /// syncable (empty, concealed by a password manager, or too big).
    func currentPayload() -> (Data, String)? {
        let pasteboard = NSPasteboard.general
        guard !isConcealed(pasteboard), let (data, contentType) = readPayload(pasteboard) else { return nil }
        guard data.count <= Self.maxPayloadBytes else { return nil }
        return (data, contentType)
    }

    static func upload(_ data: Data, contentType: String, to recipientID: String, config: PairingConfig, apiClient: RelayAPIClient) async throws -> ItemMeta {
        try await apiClient.uploadData(
            config, to: recipientID, kind: ItemKind.clipboard, data: data,
            filename: contentType.hasPrefix("image/") ? "clipboard.png" : "shared-text.txt",
            contentType: contentType
        )
    }

    /// Picks what to sync from the pasteboard, in this order:
    /// 1. An image file copied in Finder (Finder also puts the filename as
    ///    text and the file's icon as an image, which must not win).
    /// 2. Text — apps like Office and Pages add an image rendering of a
    ///    text selection, so text beats a bare image.
    /// 3. Image data (screenshots, "Copy Image" in a browser, Preview).
    private func readPayload(_ pasteboard: NSPasteboard) -> (Data, String)? {
        if let url = (pasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL])?.first,
           let type = UTType(filenameExtension: url.pathExtension), type.conforms(to: .image) {
            guard let data = try? Data(contentsOf: url) else { return nil }
            return (data, type.preferredMIMEType ?? "application/octet-stream")
        }
        if let text = pasteboard.string(forType: .string), !text.isEmpty {
            return (Data(text.utf8), "text/plain")
        }
        if let png = pasteboard.data(forType: .png) {
            return (png, "image/png")
        }
        if let image = NSImage(pasteboard: pasteboard),
           let tiff = image.tiffRepresentation,
           let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) {
            return (png, "image/png")
        }
        return nil
    }

    /// Respects the de facto "concealed" marker type
    /// (`org.nspasteboard.ConcealedType`) several password managers
    /// (1Password, Bitwarden) tag sensitive copies with — the macOS
    /// equivalent of Android's `ClipDescription.EXTRA_IS_SENSITIVE`, cited
    /// in ADR-0002 as a free, OS-level signal worth respecting.
    private func isConcealed(_ pasteboard: NSPasteboard) -> Bool {
        pasteboard.types?.contains(NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")) ?? false
    }

    private static func hash(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}
