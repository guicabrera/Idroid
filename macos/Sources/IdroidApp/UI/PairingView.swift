import SwiftUI

private enum PairingMode: String, CaseIterable, Identifiable {
    case start = "Start pairing"
    case join = "Join with code"
    case manual = "Manual"
    var id: String { rawValue }
}

/// PIN pairing (relay/README.md#api): one device calls /pair/start and
/// shows the resulting 6-digit code, the other calls /pair/claim with it.
/// Manual entry (pasting values from `POST /admin/devices`) stays as a
/// fallback for scripting. QR code pairing is still open — see the relay's
/// README "What's deliberately out of scope here".
struct PairingView: View {
    @EnvironmentObject var appState: AppState
    @State private var mode: PairingMode = .start

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Pair with another device")
                .font(.title2).bold()

            Picker("", selection: $mode) {
                ForEach(PairingMode.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)

            switch mode {
            case .start: StartPairingSection()
            case .join: JoinPairingSection()
            case .manual: ManualPairingSection()
            }
        }
        .padding(24)
        .frame(width: 440)
    }
}

/// Device A: generate a code, show it, and poll until the other device claims it.
private struct StartPairingSection: View {
    @EnvironmentObject var appState: AppState

    @State private var relayBaseURL = "http://macmini.tailnet.ts.net:8443"
    @State private var deviceName = "mac"
    @State private var started: PairStartResponse?
    @State private var errorMessage: String?
    @State private var isWorking = false
    @State private var pollTask: Task<Void, Never>?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Generates a code on this device; enter it on the other device's \"Join with code\" screen.")
                .font(.caption).foregroundStyle(.secondary)

            TextField("Relay base URL", text: $relayBaseURL)
                .disabled(started != nil)
            TextField("This device's name", text: $deviceName)
                .disabled(started != nil)

            if let error = errorMessage {
                Text(error).foregroundStyle(.red).font(.caption)
            }

            if let started {
                Text(started.pairing.code)
                    .font(.system(size: 40, weight: .bold, design: .monospaced))
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("Waiting for the other device…").foregroundStyle(.secondary)
                }
            } else {
                Button(isWorking ? "Generating…" : "Generate code") { generate() }
                    .disabled(isWorking || relayBaseURL.isEmpty || deviceName.isEmpty)
                    .buttonStyle(.borderedProminent)
            }
        }
        .textFieldStyle(.roundedBorder)
        .onDisappear { pollTask?.cancel() }
    }

    private func generate() {
        errorMessage = nil
        isWorking = true
        Task {
            do {
                let response = try await appState.apiClient.startPairing(relayBaseURL: relayBaseURL, name: deviceName)
                started = response
                poll(response)
            } catch {
                errorMessage = error.localizedDescription
            }
            isWorking = false
        }
    }

    private func poll(_ session: PairStartResponse) {
        pollTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                do {
                    let status = try await appState.apiClient.pollPairingStatus(
                        relayBaseURL: relayBaseURL, sessionID: session.pairing.sessionID
                    )
                    if status.claimed, let peer = status.peer {
                        appState.savePairing(
                            PairingConfig(
                                relayBaseURL: relayBaseURL,
                                deviceToken: session.device.token,
                                recipientDeviceID: peer.id,
                                recipientName: peer.name
                            )
                        )
                        return
                    }
                } catch {
                    errorMessage = error.localizedDescription
                }
            }
        }
    }
}

/// Device B: enter the code shown by the other device's "Start pairing" screen.
private struct JoinPairingSection: View {
    @EnvironmentObject var appState: AppState

    @State private var relayBaseURL = "http://macmini.tailnet.ts.net:8443"
    @State private var deviceName = "mac"
    @State private var code = ""
    @State private var errorMessage: String?
    @State private var isWorking = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Enter the 6-digit code shown on the other device's \"Start pairing\" screen.")
                .font(.caption).foregroundStyle(.secondary)

            TextField("Relay base URL", text: $relayBaseURL)
            TextField("This device's name", text: $deviceName)
            TextField("Pairing code", text: $code)

            if let error = errorMessage {
                Text(error).foregroundStyle(.red).font(.caption)
            }

            Button(isWorking ? "Joining…" : "Join") { join() }
                .disabled(isWorking || relayBaseURL.isEmpty || deviceName.isEmpty || code.isEmpty)
                .buttonStyle(.borderedProminent)
        }
        .textFieldStyle(.roundedBorder)
    }

    private func join() {
        errorMessage = nil
        isWorking = true
        Task {
            do {
                let claim = try await appState.apiClient.claimPairing(relayBaseURL: relayBaseURL, code: code, name: deviceName)
                appState.savePairing(
                    PairingConfig(
                        relayBaseURL: relayBaseURL,
                        deviceToken: claim.device.token,
                        recipientDeviceID: claim.peer.id,
                        recipientName: claim.peer.name
                    )
                )
            } catch {
                errorMessage = error.localizedDescription
            }
            isWorking = false
        }
    }
}

/// Fallback: paste values minted directly via `POST /admin/devices` (relay/README.md#api).
private struct ManualPairingSection: View {
    @EnvironmentObject var appState: AppState

    @State private var relayBaseURL = "http://macmini.tailnet.ts.net:8443"
    @State private var deviceToken = ""
    @State private var recipientDeviceID = ""
    @State private var recipientName = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Paste values minted directly via POST /admin/devices, bypassing the code flow.")
                .font(.caption).foregroundStyle(.secondary)

            TextField("Relay base URL", text: $relayBaseURL)
            TextField("This device's token", text: $deviceToken)
            TextField("Recipient device id", text: $recipientDeviceID)
            TextField("Recipient display name", text: $recipientName)

            if let error = appState.errorMessage {
                Text(error).foregroundStyle(.red).font(.caption)
            }

            Button("Save") {
                appState.savePairing(
                    PairingConfig(
                        relayBaseURL: relayBaseURL,
                        deviceToken: deviceToken,
                        recipientDeviceID: recipientDeviceID,
                        recipientName: recipientName
                    )
                )
            }
            .disabled(relayBaseURL.isEmpty || deviceToken.isEmpty || recipientDeviceID.isEmpty)
            .buttonStyle(.borderedProminent)
        }
        .textFieldStyle(.roundedBorder)
    }
}
