import Foundation
import UserNotifications

/// Keeps a websocket open to the relay so new items surface immediately —
/// the macOS counterpart to RelaySyncService.kt's Android foreground
/// service. Reconnects with a fixed backoff on any drop, same reasoning as
/// the Android side: this Mac moves between Tailscale re-handshakes too
/// (sleep/wake, network changes), not just phones.
///
/// Also the natural home for the macOS side of automatic clipboard sync
/// (ADR-0002) once that's implemented: it would poll
/// `NSPasteboard.general.changeCount` and push onto the same connection
/// this controller already keeps alive.
final class RelaySyncController {

    var onNewItem: (() -> Void)?

    private var task: URLSessionWebSocketTask?
    private var apiClient: RelayAPIClient?
    private var config: PairingConfig?
    private var shouldReconnect = false

    func connect(_ config: PairingConfig, apiClient: RelayAPIClient) {
        self.config = config
        self.apiClient = apiClient
        shouldReconnect = true
        open()
    }

    func disconnect() {
        shouldReconnect = false
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
    }

    private func open() {
        guard let config, let apiClient else { return }
        let newTask = apiClient.openWebSocket(config)
        task = newTask
        newTask.resume()
        listen()
    }

    private func listen() {
        task?.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let message):
                if case .string(let text) = message {
                    self.handle(text)
                }
                self.listen()
            case .failure:
                self.scheduleReconnect()
            }
        }
    }

    private func handle(_ text: String) {
        guard let data = text.data(using: .utf8),
              let event = try? JSONDecoder().decode(NotifyEvent.self, from: data),
              event.event == "new_item" else { return }
        notifyNewItem()
        onNewItem?()
    }

    private func scheduleReconnect() {
        task = nil
        guard shouldReconnect else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
            self?.open()
        }
    }

    private func notifyNewItem() {
        // UNUserNotificationCenter requires a proper app bundle identifier;
        // running via `swift run` (see macos/README.md) doesn't have one,
        // so skip rather than crash — new items still refresh the inbox
        // list via `onNewItem`.
        guard Bundle.main.bundleIdentifier != nil else { return }
        let content = UNMutableNotificationContent()
        content.title = "Idroid"
        content.body = "New item in your inbox"
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }
}
