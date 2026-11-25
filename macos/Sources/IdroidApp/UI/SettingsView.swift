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
            InputShareSettings()
                .tabItem { Label("Mouse", systemImage: "cursorarrow.motionlines") }
            CommandSettings()
                .tabItem { Label("Commands", systemImage: "terminal") }
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

/// What paired devices may run on this Mac (ADR-0005).
private struct CommandSettings: View {
    @EnvironmentObject var appState: AppState
    @State private var editing: CommandAction?

    var body: some View {
        Form {
            Section {
                Toggle("Allow terminal from paired devices", isOn: $appState.terminalEnabled)
            } footer: {
                Text("On by default: your paired phone can run any command as you, with autocomplete, and open an SSH terminal (needs Remote Login in System Settings → General → Sharing). Turn it off to allow only the commands below. Every run is logged to ~/Library/Logs/Idroid/commands.log.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Section("Commands") {
                ForEach(appState.commandActions) { action in
                    HStack {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(action.name)
                            Text(action.command)
                                .font(.caption.monospaced())
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                        Spacer()
                        Text(action.group).font(.caption).foregroundStyle(.secondary)
                        Button { editing = action } label: { Image(systemName: "pencil") }
                            .buttonStyle(.borderless)
                        Button {
                            appState.commandActions.removeAll { $0.id == action.id }
                        } label: { Image(systemName: "minus.circle") }
                            .buttonStyle(.borderless)
                    }
                }
                HStack {
                    Button("Add Command…") {
                        editing = CommandAction(id: UUID().uuidString, name: "", command: "", takesInput: false, group: "Mac")
                    }
                    Button("Restore Defaults") { appState.commandActions = CommandStore.presets }
                }
            }
        }
        .formStyle(.grouped)
        .sheet(item: $editing) { action in
            CommandEditor(action: action) { saved in
                if let i = appState.commandActions.firstIndex(where: { $0.id == saved.id }) {
                    appState.commandActions[i] = saved
                } else {
                    appState.commandActions.append(saved)
                }
                editing = nil
            } onCancel: { editing = nil }
        }
    }
}

private struct CommandEditor: View {
    @State var action: CommandAction
    let onSave: (CommandAction) -> Void
    let onCancel: () -> Void

    var body: some View {
        Form {
            TextField("Name", text: $action.name)
            TextField("Group", text: $action.group)
            TextField("Command (zsh)", text: $action.command, axis: .vertical)
                .font(.body.monospaced())
                .lineLimit(2...6)
            Toggle("Asks for input on the phone (available as \"$1\")", isOn: $action.takesInput)
            HStack {
                Spacer()
                Button("Cancel", action: onCancel)
                Button("Save") { onSave(action) }
                    .keyboardShortcut(.defaultAction)
                    .disabled(action.name.isEmpty || action.command.isEmpty)
            }
        }
        .formStyle(.grouped)
        .frame(width: 440)
    }
}

/// Shared mouse and keyboard with the phone (ADR-0008).
private struct InputShareSettings: View {
    @EnvironmentObject var appState: AppState
    @State private var trusted = InputShareController.hasPermission
    private let recheck = Timer.publish(every: 2, on: .main, in: .common).autoconnect()

    private var statusText: String {
        switch appState.inputShareStatus {
        case .off: return "Desligado"
        case .noPermission: return "Falta a permissão de Acessibilidade"
        case .connecting: return "Esperando o celular (app aberto e serviço de acessibilidade ligado?)"
        case .ready: return "Pronto: empurre o ponteiro pela borda"
        case .controlling: return "Controlando o celular (⌘⎋ volta)"
        }
    }

    var body: some View {
        Form {
            Toggle("Levar mouse e teclado para o celular", isOn: $appState.inputShareEnabled)
            Picker("Celular", selection: $appState.inputShareDeviceID) {
                Text("Escolha…").tag(String?.none)
                ForEach(appState.devices.filter { $0.id != appState.me?.id }) { device in
                    Text(device.name + (device.online ? "" : " (offline)")).tag(Optional(device.id))
                }
            }
            Picker("Borda da tela", selection: $appState.inputShareEdge) {
                ForEach(InputShareController.Edge.allCases) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            LabeledContent("Velocidade") {
                Slider(value: $appState.inputShareSpeed, in: 0.4...3, step: 0.1)
                Text(String(format: "%.1f×", appState.inputShareSpeed)).monospacedDigit().frame(width: 40)
            }
            LabeledContent("Status", value: statusText)
            Button(appState.isMirroring ? "Parar espelhamento" : "Espelhar a tela do celular") { appState.toggleMirror() }
                .disabled(!appState.isMirroring && appState.inputShareStatus != .ready && appState.inputShareStatus != .controlling)
            if !trusted {
                Button("Dar permissão de Acessibilidade…") { InputShareController.requestPermission() }
            }
            Text("Empurre o ponteiro além da borda escolhida para ele aparecer no celular. Clique = toque, arrastar = deslizar, clique direito = voltar, ⌘V cola o clipboard do Mac, ⌘H = início. Volte pela mesma borda do celular ou com ⌘⎋.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .formStyle(.grouped)
        .onReceive(recheck) { _ in
            let now = InputShareController.hasPermission
            if now != trusted {
                trusted = now
                appState.applyInputShare()
            }
        }
    }
}
