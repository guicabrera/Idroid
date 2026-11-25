import AppKit
import SwiftUI

/// A window that drives another Mac: what the phone does in its tabs.
/// Several can be open at once; macOS can merge them into tabs.
struct RemoteTarget: Codable, Hashable {
    enum Kind: String, Codable { case terminal, copilot, copilotNew }
    var deviceID: String
    var kind: Kind
    /// Distinguishes several windows of the same kind.
    var instance = UUID()
}

enum RemoteWindow {
    static let id = "remote"
}

extension AppState {
    /// Opens the other Mac's VS Code (ADR-0007) in the default browser: the
    /// bridge publishes it on the tailnet, which this Mac reaches too.
    func openRemoteVSCode(_ device: PeerDevice, folder: String? = nil) {
        guard let pairing else { return }
        showStatus("Opening VS Code on \(device.name)…")
        Task {
            do {
                let bridge = BridgeClient(api: apiClient, config: pairing, deviceID: device.id)
                let data = try await bridge.call("POST", "/vscode", body: ["folder": folder])
                struct Answer: Decodable { let url: String }
                let answer = try JSONDecoder().decode(Answer.self, from: data)
                guard let url = URL(string: answer.url) else { throw RelayError.invalidResponse }
                NSWorkspace.shared.open(url)
                showStatus("VS Code on \(device.name) opened in the browser")
            } catch {
                errorMessage = "VS Code on \(device.name): \(error.localizedDescription)"
            }
        }
    }

    /// Opens Terminal.app with ssh to the other Mac over the tailnet (its
    /// Remote Login must be on), using this Mac's own ssh keys.
    func openRemoteSSH(_ device: PeerDevice) {
        guard let pairing else { return }
        showStatus("Connecting to \(device.name) over SSH…")
        Task {
            do {
                let catalog = try await apiClient.commandCatalog(pairing, deviceID: device.id)
                guard catalog.remoteLogin else {
                    errorMessage = "\(device.name) has Remote Login off: turn it on in System Settings → General → Sharing."
                    return
                }
                let safe = { (s: String) in s.allSatisfy { $0.isLetter || $0.isNumber || "._-".contains($0) } }
                guard safe(catalog.user), safe(catalog.sshHost), let url = URL(string: "ssh://\(catalog.user)@\(catalog.sshHost)") else {
                    throw RelayError.invalidResponse
                }
                NSWorkspace.shared.open(url)
                showStatus("SSH to \(device.name) opened in Terminal")
            } catch {
                errorMessage = "SSH to \(device.name): \(error.localizedDescription)"
            }
        }
    }
}

/// The window content: picks the view for the target's kind.
struct RemoteWindowView: View {
    @EnvironmentObject var appState: AppState
    let target: RemoteTarget?

    var body: some View {
        if let target, let pairing = appState.pairing,
           let device = appState.devices.first(where: { $0.id == target.deviceID }) {
            switch target.kind {
            case .terminal:
                RemoteTerminalView(device: device, pairing: pairing)
            case .copilot, .copilotNew:
                RemoteCopilotView(device: device, pairing: pairing, startNew: target.kind == .copilotNew)
            }
        } else {
            ContentUnavailableView("Device not available", systemImage: "desktopcomputer.trianglebadge.exclamationmark",
                                   description: Text("It was removed from this group, or Idroid isn't paired."))
                .frame(minWidth: 420, minHeight: 300)
        }
    }
}
