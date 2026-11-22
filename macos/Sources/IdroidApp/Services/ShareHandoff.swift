import AppKit

/// The main app's side of the Share menu extension (ShareExtension/ShareViewController.swift):
/// the extension leaves what was shared on a named pasteboard and opens
/// `idroid://share`, which lands here via AppDelegate.
enum ShareHandoff {
    static let url = URL(string: "idroid://share")!
    static let requested = Notification.Name("IdroidShareRequested")

    private static let pasteboard = NSPasteboard(name: .init("com.idroid.mac.share"))
    private static let filenameType = NSPasteboard.PasteboardType("com.idroid.share.filename")
    private static let sharedAtType = NSPasteboard.PasteboardType("com.idroid.share.date")
    /// Leftovers older than this (e.g. the app was quit before handling them) are dropped.
    private static let maxAge: TimeInterval = 120

    struct Payload {
        var files: [URL] = []
        var texts: [String] = []
        var isEmpty: Bool { files.isEmpty && texts.isEmpty }
    }

    /// Reads and clears whatever the extension left. In-memory items (images
    /// from Photos, Preview…) are written to temporary files so they go out
    /// like any other file.
    static func take() -> Payload? {
        guard let items = pasteboard.pasteboardItems, !items.isEmpty else { return nil }
        defer { pasteboard.clearContents() }

        let sharedAt = items.lazy.compactMap { $0.string(forType: sharedAtType) }.first.flatMap(TimeInterval.init)
        guard let sharedAt, Date().timeIntervalSince1970 - sharedAt < maxAge else { return nil }

        var payload = Payload()
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("Idroid-share/\(UUID().uuidString)", isDirectory: true)
        for item in items where item.string(forType: sharedAtType) == nil {
            if let string = item.string(forType: .fileURL), let url = URL(string: string) {
                payload.files.append(url)
            } else if let text = item.string(forType: .string) {
                payload.texts.append(text)
            } else if let filename = item.string(forType: filenameType),
                      let type = item.types.first(where: { $0 != filenameType }),
                      let data = item.data(forType: type) {
                try? FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
                let file = tempDir.appendingPathComponent(filename)
                if (try? data.write(to: file)) != nil { payload.files.append(file) }
            }
        }
        return payload.isEmpty ? nil : payload
    }
}

/// Receives `idroid://share`. Handling the URL here (rather than with
/// `onOpenURL`) keeps SwiftUI from popping the main window open for it.
final class AppDelegate: NSObject, NSApplicationDelegate {
    func application(_ application: NSApplication, open urls: [URL]) {
        guard urls.contains(where: { $0.scheme == ShareHandoff.url.scheme && $0.host == ShareHandoff.url.host }) else { return }
        NotificationCenter.default.post(name: ShareHandoff.requested, object: nil)
    }
}
