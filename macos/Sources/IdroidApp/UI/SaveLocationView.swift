import SwiftUI
import AppKit

/// Folder picker shared by "Save As…", "Ask each time" and choosing a
/// custom default folder.
enum FolderPicker {
    @MainActor
    static func pick(prompt: String, startingAt directory: URL? = nil) -> URL? {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.canCreateDirectories = true
        panel.allowsMultipleSelection = false
        panel.prompt = prompt
        panel.directoryURL = directory
        return panel.runModal() == .OK ? panel.url : nil
    }
}

/// Settings pane (⌘,) for where received files are saved.
struct SaveLocationSettingsView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        Form {
            Picker("Save received files to:", selection: modeBinding) {
                Text("Downloads").tag(Mode.downloads)
                Text(customFolderLabel).tag(Mode.folder)
                Text("Ask each time").tag(Mode.ask)
            }
            .pickerStyle(.radioGroup)

            HStack {
                Button("Choose Folder…") { chooseCustomFolder() }
                if case .folder(let url) = appState.saveLocation {
                    Text(url.path)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            }

            Text("“Save As…” on an inbox item always asks, whatever is set here.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(20)
        .frame(width: 460)
    }

    private enum Mode { case downloads, folder, ask }

    private var customFolderLabel: String {
        if let folder = appState.lastCustomSaveFolder {
            return "Custom folder (\(folder.lastPathComponent))"
        }
        return "Custom folder…"
    }

    private var modeBinding: Binding<Mode> {
        Binding(
            get: {
                switch appState.saveLocation {
                case .downloads: return .downloads
                case .folder: return .folder
                case .askEachTime: return .ask
                }
            },
            set: { mode in
                switch mode {
                case .downloads:
                    appState.setSaveLocation(.downloads)
                case .ask:
                    appState.setSaveLocation(.askEachTime)
                case .folder:
                    if let folder = appState.lastCustomSaveFolder {
                        appState.setSaveLocation(.folder(folder))
                    } else {
                        chooseCustomFolder()
                    }
                }
            }
        )
    }

    private func chooseCustomFolder() {
        if let url = FolderPicker.pick(prompt: "Use This Folder", startingAt: appState.lastCustomSaveFolder) {
            appState.setSaveLocation(.folder(url))
        }
    }
}

/// Compact "Saving to: …" control for the main window footer.
struct SaveLocationMenu: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        HStack(spacing: 6) {
            Text("Saving to:").font(.caption).foregroundStyle(.secondary)
            Menu(appState.saveLocation.displayName) {
                Button("Downloads") { appState.setSaveLocation(.downloads) }
                if let folder = appState.lastCustomSaveFolder {
                    Button(folder.lastPathComponent) { appState.setSaveLocation(.folder(folder)) }
                }
                Button("Choose Folder…") {
                    if let url = FolderPicker.pick(prompt: "Use This Folder", startingAt: appState.lastCustomSaveFolder) {
                        appState.setSaveLocation(.folder(url))
                    }
                }
                Divider()
                Button("Ask each time") { appState.setSaveLocation(.askEachTime) }
                if let folder = appState.saveLocation.resolvedFolder {
                    Divider()
                    Button("Show in Finder") { NSWorkspace.shared.open(folder) }
                }
            }
            .menuStyle(.borderlessButton)
            .fixedSize()
        }
    }
}
