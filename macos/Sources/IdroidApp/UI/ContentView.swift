import SwiftUI

struct ContentView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        Group {
            if appState.pairing != nil {
                MainView()
            } else {
                PairingView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(minWidth: 720, minHeight: 480)
        .tint(.idroidIndigo)
    }
}

extension Color {
    /// The brand indigo from the app icon (and the Android app's primary color).
    static let idroidIndigo = Color(red: 0x4F / 255, green: 0x46 / 255, blue: 0xE5 / 255)
}

/// Sidebar + detail layout once this Mac is paired.
struct MainView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        NavigationSplitView {
            Sidebar()
                .navigationSplitViewColumnWidth(min: 200, ideal: 220)
        } detail: {
            Group {
                switch appState.section {
                case .inbox: InboxView()
                case .send: SendView()
                case .devices: DevicesView()
                }
            }
            .safeAreaInset(edge: .bottom) { StatusBanner() }
        }
        .sheet(isPresented: $appState.isAddDevicePresented) {
            AddDeviceSheet()
        }
    }
}

private struct Sidebar: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        List(selection: sectionBinding) {
            Section {
                Label("Inbox", systemImage: "tray.and.arrow.down")
                    .badge(appState.inbox.count)
                    .tag(AppSection.inbox)
                Label("Send", systemImage: "paperplane")
                    .tag(AppSection.send)
                Label("Devices", systemImage: "laptopcomputer.and.iphone")
                    .badge(appState.devices.count)
                    .tag(AppSection.devices)
            }

            Section("Devices") {
                ForEach(appState.devices) { device in
                    HStack(spacing: 8) {
                        Image(systemName: device.symbolName)
                            .foregroundStyle(.secondary)
                            .frame(width: 18)
                        Text(device.name).lineLimit(1)
                        Spacer()
                        OnlineDot(online: device.online)
                    }
                    .tag(AppSection.devices)
                }
                Button {
                    appState.isAddDevicePresented = true
                } label: {
                    Label("Add Device…", systemImage: "plus.circle")
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
            }
        }
        .listStyle(.sidebar)
        .safeAreaInset(edge: .bottom) { footer }
    }

    private var sectionBinding: Binding<AppSection?> {
        Binding(get: { appState.section }, set: { if let s = $0 { appState.section = s } })
    }

    private var footer: some View {
        HStack(spacing: 8) {
            OnlineDot(online: appState.isConnected)
            VStack(alignment: .leading, spacing: 1) {
                Text(appState.thisDeviceName).font(.callout).lineLimit(1)
                Text(appState.isConnected ? "Connected to relay" : "Relay offline — retrying")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            SettingsLink {
                Image(systemName: "gearshape")
            }
            .buttonStyle(.borderless)
            .help("Settings")
        }
        .padding(12)
    }
}

struct OnlineDot: View {
    let online: Bool

    var body: some View {
        Circle()
            .fill(online ? Color.green : Color.secondary.opacity(0.4))
            .frame(width: 8, height: 8)
            .help(online ? "Online" : "Offline")
    }
}

/// Transient "Sent" / "Saved" confirmation and error messages.
private struct StatusBanner: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        if let error = appState.errorMessage {
            banner(error, symbol: "exclamationmark.triangle.fill", tint: .red) { appState.errorMessage = nil }
        } else if let status = appState.statusMessage {
            banner(status, symbol: "checkmark.circle.fill", tint: .green) { appState.statusMessage = nil }
        }
    }

    private func banner(_ text: String, symbol: String, tint: Color, dismiss: @escaping () -> Void) -> some View {
        HStack(spacing: 8) {
            Image(systemName: symbol).foregroundStyle(tint)
            Text(text).lineLimit(2)
            Spacer()
            Button(action: dismiss) { Image(systemName: "xmark") }
                .buttonStyle(.borderless)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10))
        .padding(12)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }
}

extension PeerDevice {
    /// Best guess from the name; the relay doesn't know device types.
    var symbolName: String {
        let lower = name.lowercased()
        if lower.contains("ipad") { return "ipad" }
        if lower.contains("mini") { return "macmini" }
        if lower.contains("mac") || lower.contains("book") { return "laptopcomputer" }
        return "iphone"
    }
}

extension ItemMeta {
    var displayTitle: String {
        if kind == ItemKind.text { return "Text" }
        return filename ?? "File"
    }

    var isImage: Bool { contentType.hasPrefix("image/") }
    var isCopyable: Bool { kind == ItemKind.text || isImage }

    var symbolName: String {
        if kind == ItemKind.text { return "text.alignleft" }
        if contentType.hasPrefix("image/") { return "photo" }
        if contentType.hasPrefix("video/") { return "film" }
        if contentType.hasPrefix("audio/") { return "waveform" }
        if contentType == "application/pdf" { return "doc.richtext" }
        if contentType.contains("zip") || contentType.contains("compressed") { return "doc.zipper" }
        return "doc"
    }

    var createdDate: Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.date(from: createdAt) ?? ISO8601DateFormatter().date(from: createdAt)
    }
}
