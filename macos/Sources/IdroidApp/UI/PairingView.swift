import SwiftUI

private enum PairingMode: String, CaseIterable, Identifiable {
    case start = "Show code"
    case join = "Enter code"
    case manual = "Manual"
    var id: String { rawValue }
}

/// The relay this app pairs through by default (the Mac mini on the tailnet).
let defaultRelayBaseURL = "http://guilhermes-mac-mini-1.tail56ad8e.ts.net:8443"

/// PIN/QR pairing (relay/README.md#api): one device calls /pair/start and
/// shows the resulting 6-digit code (also as a QR code, see
/// PairingQRCode.swift), the other calls /pair/claim with it. Manual entry
/// (pasting a token from `POST /admin/devices`) stays as a fallback for
/// scripting.
///
/// Used both for first-time setup and, once this Mac is paired, to add
/// another device to its group (`onPaired` is then called instead of a new
/// pairing being saved — the relay reuses this Mac's identity).
struct PairingView: View {
    @EnvironmentObject var appState: AppState
    var onPaired: (() -> Void)?
    @State private var mode: PairingMode = .start

    private var isAdding: Bool { appState.pairing != nil }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 12) {
                Image(nsImage: NSApp.applicationIconImage)
                    .resizable()
                    .frame(width: 56, height: 56)
                VStack(alignment: .leading, spacing: 2) {
                    Text(isAdding ? "Add a device" : "Welcome to Idroid")
                        .font(.title2).bold()
                    Text(isAdding
                         ? "Pair your phone, another Mac or an iPad with this Mac."
                         : "Pair this Mac with your phone to share files, text and the clipboard.")
                        .foregroundStyle(.secondary)
                }
            }

            Picker("", selection: $mode) {
                ForEach(PairingMode.allCases.filter { !isAdding || $0 != .manual }) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()

            switch mode {
            case .start: StartPairingSection(onPaired: finish)
            case .join: JoinPairingSection(onPaired: finish)
            case .manual: ManualPairingSection()
            }
        }
        .padding(24)
        .frame(width: 480)
    }

    /// Called with the token to save on first-time setup; when adding, this
    /// Mac's existing pairing already covers the new device.
    private func finish(relayBaseURL: String, token: String) {
        if isAdding {
            onPaired?()
        } else {
            appState.savePairing(PairingConfig(relayBaseURL: relayBaseURL, deviceToken: token))
        }
    }
}

/// Device A: generate a code, show it as a QR code, and poll until the other device claims it.
private struct StartPairingSection: View {
    @EnvironmentObject var appState: AppState
    let onPaired: (String, String) -> Void

    @State private var relayBaseURL = defaultRelayBaseURL
    @State private var deviceName = Host.current().localizedName ?? "Mac"
    @State private var started: PairStartResponse?
    @State private var errorMessage: String?
    @State private var isWorking = false
    @State private var pollTask: Task<Void, Never>?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let started {
                VStack(spacing: 10) {
                    PairingQRCode(payload: pairingLink(relayBaseURL: effectiveRelayURL, code: started.pairing.code))
                    Text(started.pairing.code)
                        .font(.system(size: 36, weight: .bold, design: .monospaced))
                        .tracking(6)
                        .textSelection(.enabled)
                    Text("Scan with the Idroid app (or the phone's camera), or type the code on the other device.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("Waiting for the other device…").foregroundStyle(.secondary)
                    }
                    Button("Cancel") {
                        pollTask?.cancel()
                        self.started = nil
                    }
                    .buttonStyle(.link)
                }
                .frame(maxWidth: .infinity)
            } else {
                Text("Shows a QR code and a 6-digit code for the other device to scan or type.")
                    .font(.caption).foregroundStyle(.secondary)
                if appState.pairing == nil {
                    TextField("Relay base URL", text: $relayBaseURL)
                    TextField("This Mac's name", text: $deviceName)
                }
                Button(isWorking ? "Generating…" : "Show Pairing Code") { generate() }
                    .disabled(isWorking || effectiveRelayURL.isEmpty || deviceName.isEmpty)
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
            }

            if let error = errorMessage {
                Text(error).foregroundStyle(.red).font(.caption)
            }
        }
        .textFieldStyle(.roundedBorder)
        .onDisappear { pollTask?.cancel() }
    }

    private var effectiveRelayURL: String { appState.pairing?.relayBaseURL ?? relayBaseURL }

    private func generate() {
        errorMessage = nil
        isWorking = true
        Task {
            do {
                let response = try await appState.apiClient.startPairing(
                    relayBaseURL: effectiveRelayURL, name: deviceName, token: appState.pairing?.deviceToken
                )
                started = response
                poll(response)
            } catch {
                errorMessage = error.localizedDescription
            }
            isWorking = false
        }
    }

    private func poll(_ session: PairStartResponse) {
        let relay = effectiveRelayURL
        pollTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                do {
                    let status = try await appState.apiClient.pollPairingStatus(
                        relayBaseURL: relay, sessionID: session.pairing.sessionID
                    )
                    if status.claimed {
                        onPaired(relay, session.device.token)
                        return
                    }
                } catch {
                    errorMessage = error.localizedDescription
                }
            }
        }
    }
}

/// Device B: enter the code shown by the other device.
private struct JoinPairingSection: View {
    @EnvironmentObject var appState: AppState
    let onPaired: (String, String) -> Void

    @State private var relayBaseURL = defaultRelayBaseURL
    @State private var deviceName = Host.current().localizedName ?? "Mac"
    @State private var code = ""
    @State private var errorMessage: String?
    @State private var isWorking = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Enter the 6-digit code shown on the other device.")
                .font(.caption).foregroundStyle(.secondary)

            if appState.pairing == nil {
                TextField("Relay base URL", text: $relayBaseURL)
                TextField("This Mac's name", text: $deviceName)
            }
            TextField("Pairing code", text: $code)
                .font(.system(.title3, design: .monospaced))
                .onSubmit(join)

            if let error = errorMessage {
                Text(error).foregroundStyle(.red).font(.caption)
            }

            Button(isWorking ? "Pairing…" : "Pair") { join() }
                .disabled(isWorking || effectiveRelayURL.isEmpty || deviceName.isEmpty || code.isEmpty)
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
        }
        .textFieldStyle(.roundedBorder)
    }

    private var effectiveRelayURL: String { appState.pairing?.relayBaseURL ?? relayBaseURL }

    private func join() {
        guard !code.isEmpty else { return }
        errorMessage = nil
        isWorking = true
        Task {
            do {
                let claim = try await appState.apiClient.claimPairing(
                    relayBaseURL: effectiveRelayURL,
                    code: code.trimmingCharacters(in: .whitespaces),
                    name: deviceName,
                    token: appState.pairing?.deviceToken
                )
                onPaired(effectiveRelayURL, claim.device.token)
            } catch RelayError.http(410) {
                errorMessage = "That code is wrong or expired — generate a new one."
            } catch {
                errorMessage = error.localizedDescription
            }
            isWorking = false
        }
    }
}

/// Fallback: paste a token minted directly via `POST /admin/devices` (relay/README.md#api).
private struct ManualPairingSection: View {
    @EnvironmentObject var appState: AppState

    @State private var relayBaseURL = defaultRelayBaseURL
    @State private var deviceToken = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Paste a device token minted via POST /admin/devices. This Mac then pairs with others from the Devices screen.")
                .font(.caption).foregroundStyle(.secondary)

            TextField("Relay base URL", text: $relayBaseURL)
            TextField("This device's token", text: $deviceToken)

            Button("Save") {
                appState.savePairing(PairingConfig(relayBaseURL: relayBaseURL, deviceToken: deviceToken))
            }
            .disabled(relayBaseURL.isEmpty || deviceToken.isEmpty)
            .buttonStyle(.borderedProminent)
        }
        .textFieldStyle(.roundedBorder)
    }
}
