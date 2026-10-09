import AppKit
import CryptoKit

/// Polls `NSPasteboard.general` for changes and pushes new text/link
/// content to the paired device automatically — the macOS counterpart to
/// `ClipboardAccessibilityService.kt` (ADR-0002). No special permission is
/// needed here, unlike Android: any app can poll
/// `NSPasteboard.changeCount` at any time, which is exactly the platform
/// asymmetry ADR-0002 is about.
final class ClipboardSyncController {

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

    /// Call right after writing remote content to the pasteboard, so the
    /// bump that write just caused isn't mistaken for a new local copy on
    /// the next poll tick.
    func noteAppliedRemoteContent(_ text: String) {
        lastAppliedHash = Self.hash(text)
        lastSeenChangeCount = NSPasteboard.general.changeCount
    }

    private func pollLocalClipboard() {
        let pasteboard = NSPasteboard.general
        guard pasteboard.changeCount != lastSeenChangeCount else { return }
        lastSeenChangeCount = pasteboard.changeCount

        guard !isConcealed(pasteboard), let text = pasteboard.string(forType: .string), !text.isEmpty else { return }
        guard Self.hash(text) != lastAppliedHash else { return } // our own echo, belt-and-suspenders with the changeCount check above

        guard let config, let apiClient else { return }
        Task {
            _ = try? await apiClient.uploadText(config, kind: ItemKind.clipboard, text: text)
        }
    }

    /// Respects the de facto "concealed" marker type
    /// (`org.nspasteboard.ConcealedType`) several password managers
    /// (1Password, Bitwarden) tag sensitive copies with — the macOS
    /// equivalent of Android's `ClipDescription.EXTRA_IS_SENSITIVE`, cited
    /// in ADR-0002 as a free, OS-level signal worth respecting.
    private func isConcealed(_ pasteboard: NSPasteboard) -> Bool {
        pasteboard.types?.contains(NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")) ?? false
    }

    private static func hash(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
