import SwiftUI

/// The Settings window (⌘,).
struct SettingsView: View {
    var body: some View {
        TabView {
            GeneralSettings()
                .tabItem { Label("General", systemImage: "gearshape") }
            ClipboardSettings()
                .tabItem { Label("Clipboard", systemImage: "doc.on.clipboard") }
            SaveLocationSettingsView()
                .tabItem { Label("Files", systemImage: "folder") }
            SharingSettings()
                .tabItem { Label("Sharing", systemImage: "folder.badge.person.crop") }
            AccountSettings()
                .tabItem { Label("Account", systemImage: "person.crop.circle") }
        }
        .frame(width: 500)
    }
}

private struct GeneralSettings: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        Form {
            Toggle("Open Idroid at login", isOn: Binding(
                get: { appState.launchAtLogin },
                set: { appState.launchAtLogin = $0 }
            ))
            Toggle("Notify me when something arrives", isOn: $appState.notificationsEnabled)
            Text("Closing the window keeps Idroid running in the menu bar, so clipboard sync and incoming items keep working.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .formStyle(.grouped)
    }
}

private struct ClipboardSettings: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        Form {
            Toggle("Sync clipboard automatically", isOn: $appState.clipboardSyncEnabled)
            Toggle("Include images", isOn: $appState.clipboardSyncImages)
                .disabled(!appState.clipboardSyncEnabled)
            Section {
                Text("Copies are sent to every paired device. Passwords copied from password managers that mark them as concealed (1Password, Bitwarden…) are never sent, and items over 20 MB are skipped — send those as files.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }
}

/// Folders paired devices can browse and download from (ADR-0004).
struct SharingSettings: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        Form {
            Section {
                if appState.sharedFolders.isEmpty {
                    Text("No folders shared yet.")
                        .foregroundStyle(.secondary)
                }
                ForEach(appState.sharedFolders) { folder in
                    HStack {
                        Image(systemName: "folder.fill").foregroundStyle(Color.idroidIndigo)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(folder.name)
                            Text(folder.url.path)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                        Spacer()
                        Button {
                            appState.removeSharedFolder(folder)
                        } label: {
                            Image(systemName: "minus.circle")
                        }
                        .buttonStyle(.borderless)
                        .help("Stop sharing")
                    }
                }
                Button("Share a Folder…") { appState.addSharedFolders() }
            } header: {
                Text("Shared folders")
            } footer: {
                Text("Your paired devices can browse these folders and download files from them while this Mac is online. They can't change or delete anything.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }
}

private struct AccountSettings: View {
    @EnvironmentObject var appState: AppState
    @State private var confirmLeave = false

    var body: some View {
        Form {
            if let pairing = appState.pairing {
                LabeledContent("This Mac", value: appState.thisDeviceName)
                LabeledContent("Relay", value: pairing.relayBaseURL)
                LabeledContent("Status", value: appState.isConnected ? "Connected" : "Offline")
                LabeledContent("Paired devices", value: "\(appState.devices.count)")
                Section {
                    Button("Unpair This Mac…", role: .destructive) { confirmLeave = true }
                }
            } else {
                Text("This Mac isn't paired yet.")
            }
        }
        .formStyle(.grouped)
        .confirmationDialog("Unpair this Mac from all your devices?", isPresented: $confirmLeave) {
            Button("Unpair", role: .destructive) { appState.leave() }
        } message: {
            Text("You'll need to pair it again to keep sharing.")
        }
    }
}
