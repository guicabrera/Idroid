import Foundation

/// Keeps a websocket open to the relay so new items and device changes
/// surface immediately — the macOS counterpart to RelaySyncService.kt's
/// Android foreground service. Reconnects with a fixed backoff on any drop,
/// same reasoning as the Android side: this Mac moves between Tailscale
/// re-handshakes too (sleep/wake, network changes), not just phones.
final class RelaySyncController {

    var onNewItem: (() -> Void)?
    var onDevicesChanged: (() -> Void)?
    /// Called on the main queue whenever the connection goes up or down.
    var onConnectionChange: ((Bool) -> Void)?

    private var task: URLSessionWebSocketTask?
    private var apiClient: RelayAPIClient?
    private var config: PairingConfig?
    private var shouldReconnect = false
    private var pingTimer: Timer?

    func connect(_ config: PairingConfig, apiClient: RelayAPIClient) {
        self.config = config
        self.apiClient = apiClient
        shouldReconnect = true
        task?.cancel(with: .goingAway, reason: nil)
        open()
    }

    func disconnect() {
        shouldReconnect = false
        pingTimer?.invalidate()
        pingTimer = nil
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        setConnected(false)
    }

    private func open() {
        guard let config, let apiClient else { return }
        let newTask = apiClient.openWebSocket(config)
        task = newTask
        newTask.resume()
        listen(newTask)
        ping()

        // Keeps NATs from dropping an idle connection and doubles as the
        // liveness check behind the connected/offline indicator.
        pingTimer?.invalidate()
        pingTimer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
            self?.ping()
        }
    }

    private func ping() {
        guard let current = task else { return }
        current.sendPing { [weak self] error in
            guard let self, current === self.task else { return }
            self.setConnected(error == nil)
        }
    }

    private func listen(_ current: URLSessionWebSocketTask) {
        current.receive { [weak self] result in
            guard let self, current === self.task else { return }
            switch result {
            case .success(let message):
                if case .string(let text) = message {
                    self.handle(text)
                }
                self.listen(current)
            case .failure:
                self.setConnected(false)
                self.scheduleReconnect()
            }
        }
    }

    private func handle(_ text: String) {
        guard let data = text.data(using: .utf8),
              let event = try? JSONDecoder().decode(NotifyEvent.self, from: data) else { return }
        DispatchQueue.main.async {
            switch event.event {
            case "new_item": self.onNewItem?()
            case "devices_changed": self.onDevicesChanged?()
            default: break
            }
        }
    }

    private func setConnected(_ connected: Bool) {
        DispatchQueue.main.async { self.onConnectionChange?(connected) }
    }

    private func scheduleReconnect() {
        task = nil
        guard shouldReconnect else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
            guard let self, self.shouldReconnect, self.task == nil else { return }
            self.open()
        }
    }
}
