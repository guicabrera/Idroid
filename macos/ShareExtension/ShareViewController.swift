import AppKit
import UniformTypeIdentifiers

/// "Idroid" in the macOS Share menu, next to AirDrop.
///
/// App extensions must be sandboxed, so this one doesn't talk to the relay
/// itself (the pairing lives in the main app's Keychain). It copies what was
/// shared onto a named pasteboard and wakes the main app with `idroid://share`;
/// the app picks the device and uploads (ShareHandoff.swift, AppState.receiveShare).
/// Keep the pasteboard name and types in sync with ShareHandoff.swift.
@objc(IdroidShareViewController)
final class ShareViewController: NSViewController {
    private static let pasteboard = NSPasteboard(name: .init("com.idroid.mac.share"))
    private static let filenameType = NSPasteboard.PasteboardType("com.idroid.share.filename")
    private static let sharedAtType = NSPasteboard.PasteboardType("com.idroid.share.date")

    override func loadView() {
        // No UI of our own: the main app does the choosing.
        view = NSView(frame: NSRect(x: 0, y: 0, width: 1, height: 1))
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            .flatMap { $0.attachments ?? [] }
        Task { @MainActor in
            var items: [NSPasteboardItem] = []
            for provider in providers {
                if let item = await Self.pasteboardItem(for: provider) { items.append(item) }
            }
            if !items.isEmpty {
                let stamp = NSPasteboardItem()
                stamp.setString(String(Date().timeIntervalSince1970), forType: Self.sharedAtType)
                Self.pasteboard.clearContents()
                Self.pasteboard.writeObjects([stamp] + items)
                NSWorkspace.shared.open(URL(string: "idroid://share")!)
            }
            extensionContext?.completeRequest(returningItems: nil)
        }
    }

    private static func pasteboardItem(for provider: NSItemProvider) async -> NSPasteboardItem? {
        let item = NSPasteboardItem()
        if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
            guard let url = await loadURL(provider, type: .fileURL) else { return nil }
            item.setString(url.absoluteString, forType: .fileURL)
        } else if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
            guard let url = await loadURL(provider, type: .url) else { return nil }
            item.setString(url.absoluteString, forType: .string)
        } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            guard let text = await loadItem(provider, type: .plainText) as? String else { return nil }
            item.setString(text, forType: .string)
        } else if let type = provider.registeredTypeIdentifiers.compactMap(UTType.init).first(where: { $0.conforms(to: .data) }) {
            // Images and other in-memory content (e.g. from Photos or Preview).
            guard let data = await loadData(provider, type: type) else { return nil }
            let name = provider.suggestedName ?? "Shared"
            let ext = type.preferredFilenameExtension.map { "." + $0 } ?? ""
            item.setData(data, forType: .init(type.identifier))
            item.setString(name.hasSuffix(ext) ? name : name + ext, forType: filenameType)
        } else {
            return nil
        }
        return item
    }

    private static func loadItem(_ provider: NSItemProvider, type: UTType) async -> NSSecureCoding? {
        await withCheckedContinuation { continuation in
            provider.loadItem(forTypeIdentifier: type.identifier) { item, _ in
                continuation.resume(returning: item)
            }
        }
    }

    /// URLs arrive either as URL objects or as their data representation.
    private static func loadURL(_ provider: NSItemProvider, type: UTType) async -> URL? {
        switch await loadItem(provider, type: type) {
        case let url as URL: return url
        case let data as Data: return URL(dataRepresentation: data, relativeTo: nil)
        case let string as String: return URL(string: string)
        default: return nil
        }
    }

    private static func loadData(_ provider: NSItemProvider, type: UTType) async -> Data? {
        await withCheckedContinuation { continuation in
            _ = provider.loadDataRepresentation(forTypeIdentifier: type.identifier) { data, _ in
                continuation.resume(returning: data)
            }
        }
    }
}
