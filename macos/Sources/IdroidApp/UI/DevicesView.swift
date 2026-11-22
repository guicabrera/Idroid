import SwiftUI

/// This Mac plus every device in its group; add or remove devices here.
struct DevicesView: View {
    @EnvironmentObject var appState: AppState

    private let columns = [GridItem(.adaptive(minimum: 220), spacing: 16)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                if let me = appState.me {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("This Mac").font(.headline)
                        DeviceCard(device: me, isSelf: true)
                            .frame(maxWidth: 320)
                    }
                }

                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("Paired devices").font(.headline)
                        Spacer()
                        Button {
                            appState.isAddDevicePresented = true
                        } label: {
                            Label("Add Device", systemImage: "plus")
                        }
                    }
                    if appState.devices.isEmpty {
                        NoDevicesView().frame(height: 220)
                    } else {
                        LazyVGrid(columns: columns, alignment: .leading, spacing: 16) {
                            ForEach(appState.devices) { device in
                                DeviceCard(device: device, isSelf: false)
                            }
                        }
                        Text("Drop files on a device to send them to it. Clipboard copies go to every device.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .padding(24)
        }
        .navigationTitle("Devices")
        .toolbar {
            Button {
                appState.refreshDevices()
            } label: {
                Label("Refresh", systemImage: "arrow.clockwise")
            }
        }
    }
}

private struct DeviceCard: View {
    @EnvironmentObject var appState: AppState
    let device: PeerDevice
    let isSelf: Bool
    @State private var confirmRemove = false
    @State private var dropTargeted = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top) {
                Image(systemName: isSelf ? "desktopcomputer" : device.symbolName)
                    .font(.system(size: 28, weight: .light))
                    .foregroundStyle(Color.idroidIndigo)
                    .frame(width: 44, height: 44)
                Spacer()
                if !isSelf {
                    Menu {
                        Button("Send Files…") { appState.pickAndSendFiles(to: .device(device.id)) }
                        Button("Send Clipboard") { appState.sendClipboard(to: .device(device.id)) }
                        Button("Browse Files") { appState.browse(device) }
                            .disabled(!device.online)
                        Divider()
                        Button("Remove Device…", role: .destructive) { confirmRemove = true }
                    } label: {
                        Image(systemName: "ellipsis.circle").font(.title3)
                    }
                    .menuStyle(.borderlessButton)
                    .menuIndicator(.hidden)
                    .fixedSize()
                }
            }

            VStack(alignment: .leading, spacing: 3) {
                Text(device.name).font(.headline).lineLimit(1)
                HStack(spacing: 6) {
                    OnlineDot(online: isSelf ? appState.isConnected : device.online)
                    Text(statusText).font(.caption).foregroundStyle(.secondary)
                }
            }

            if !isSelf {
                Button {
                    appState.pickAndSendFiles(to: .device(device.id))
                } label: {
                    Label("Send Files…", systemImage: "paperplane")
                        .frame(maxWidth: .infinity)
                }
                .controlSize(.large)
            }
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 14)
                .fill(dropTargeted ? Color.idroidIndigo.opacity(0.12) : Color.secondary.opacity(0.07))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .strokeBorder(dropTargeted ? Color.idroidIndigo : Color.secondary.opacity(0.15), lineWidth: dropTargeted ? 2 : 1)
        )
        .onDrop(of: [.fileURL], isTargeted: isSelf ? nil : $dropTargeted) { providers in
            guard !isSelf else { return false }
            loadFileURLs(providers) { appState.sendFiles($0, to: .device(device.id)) }
            return true
        }
        .confirmationDialog("Remove \(device.name)?", isPresented: $confirmRemove) {
            Button("Remove", role: .destructive) { appState.removeDevice(device) }
        } message: {
            Text("It stops receiving from and sending to your devices, and has to be paired again to come back.")
        }
    }

    private var statusText: String {
        if isSelf { return appState.isConnected ? "Connected" : "Offline" }
        var text = device.online ? "Online" : "Offline"
        if let date = ISO8601DateFormatter.relay.date(from: device.createdAt) {
            text += " · paired \(date.formatted(date: .abbreviated, time: .omitted))"
        }
        return text
    }
}

/// Pairs another device into this Mac's group, from the Devices screen or the menu bar.
struct AddDeviceSheet: View {
    @EnvironmentObject var appState: AppState
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            PairingView(onPaired: {
                appState.refreshDevices()
                appState.showStatus("Device added")
                dismiss()
            })
            Divider()
            HStack {
                Spacer()
                Button("Done") { dismiss() }
                    .keyboardShortcut(.cancelAction)
            }
            .padding(12)
        }
        .frame(width: 480)
    }
}

extension ISO8601DateFormatter {
    /// Go's time.Time JSON uses RFC 3339 with optional fractional seconds.
    static let relay: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()
}
