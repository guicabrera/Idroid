import SwiftUI

/// The menu bar item's menu: everything the main window can do, so the app
/// stays fully usable after its window is closed.
struct MenuBarMenu: View {
    @EnvironmentObject var appState: AppState
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        if appState.pairing == nil {
            Text("Not paired")
            Button("Pair a Device…") { show(.devices) }
        } else {
            Text(statusLine)

            Button("Open Idroid") { show(nil) }
                .keyboardShortcut("o")

            Divider()

            Menu("Control") {
                let macs = appState.devices.filter(appState.isControllable)
                if macs.isEmpty { Text("No Mac to control") }
                ForEach(macs) { device in
                    Menu("\(device.online ? "●" : "○") \(device.name)") {
                        Button("Terminal") { openWindow(id: RemoteWindow.id, value: RemoteTarget(deviceID: device.id, kind: .terminal)); NSApp.activate() }
                        Button("Copilot") { openWindow(id: RemoteWindow.id, value: RemoteTarget(deviceID: device.id, kind: .copilot)); NSApp.activate() }
                        Button("VS Code") { appState.openRemoteVSCode(device) }
                        Button("SSH in Terminal") { appState.openRemoteSSH(device) }
                    }
                    .disabled(!device.online)
                }
            }

            targetMenu("Send Files") { appState.pickAndSendFiles(to: $0) }
            targetMenu("Send Clipboard") { appState.sendClipboard(to: $0) }

            Menu(appState.inbox.isEmpty ? "Inbox" : "Inbox (\(appState.inbox.count))") {
                if appState.inbox.isEmpty {
                    Text("Nothing received")
                } else {
                    ForEach(appState.inbox.prefix(8)) { item in
                        Menu(item.displayTitle) {
                            Button("Save") { appState.save(item) }
                            Button("Save As…") { appState.saveAs(item) }
                            Button("Open") { appState.open(item) }
                            if item.isCopyable {
                                Button("Copy") { appState.copyToClipboard(item) }
                            }
                            Divider()
                            Button("Delete") { appState.deleteItem(item) }
                        }
                    }
                    Divider()
                    Button("Save All") { appState.saveAll() }
                    Button("Show Inbox…") { show(.inbox) }
                }
            }

            Divider()

            Toggle("Sync Clipboard", isOn: $appState.clipboardSyncEnabled)

            Menu("Devices") {
                ForEach(appState.devices) { device in
                    Text("\(device.online ? "●" : "○") \(device.name)")
                }
                if !appState.devices.isEmpty { Divider() }
                Button("Add Device…") {
                    show(.devices)
                    appState.isAddDevicePresented = true
                }
                Button("Manage Devices…") { show(.devices) }
            }

            Button("Refresh") {
                appState.refreshInbox()
                appState.refreshDevices()
            }
            .keyboardShortcut("r")
        }

        Divider()

        SettingsLink { Text("Settings…") }
            .keyboardShortcut(",")

        Button("Quit Idroid") { NSApplication.shared.terminate(nil) }
            .keyboardShortcut("q")
    }

    private var statusLine: String {
        let connection = appState.isConnected ? "Connected" : "Offline"
        let count = appState.devices.count
        return "\(connection) · \(count) device\(count == 1 ? "" : "s")"
    }

    @ViewBuilder
    private func targetMenu(_ title: String, action: @escaping (SendTarget) -> Void) -> some View {
        Menu(title) {
            if appState.devices.isEmpty {
                Text("No devices yet")
            } else {
                ForEach(appState.devices) { device in
                    Button(device.name) { action(.device(device.id)) }
                }
                if appState.devices.count > 1 {
                    Divider()
                    Button("All Devices") { action(.all) }
                }
            }
        }
        .disabled(appState.devices.isEmpty)
    }

    private func show(_ section: AppSection?) {
        appState.showMainWindow(section) { openWindow(id: MainWindow.id) }
    }
}
