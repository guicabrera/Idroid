import SwiftUI
import UserNotifications

@main
struct IdroidApp: App {
    @StateObject private var appState = AppState()

    init() {
        requestNotificationAuthorizationIfPossible()
    }

    var body: some Scene {
        // A single, reopenable window: the menu bar's "Open Idroid" and the
        // Dock icon both bring this same window back after it's closed.
        Window("Idroid", id: MainWindow.id) {
            ContentView()
                .environmentObject(appState)
        }
        .defaultSize(width: 860, height: 560)

        Settings {
            SettingsView()
                .environmentObject(appState)
                .tint(.idroidIndigo)
        }

        // Always-on presence in the menu bar, the macOS counterpart to the
        // Android foreground service's persistent notification
        // (RelaySyncService.kt) — a visible reminder that sync is live, and
        // the way back to everything once the window is closed.
        MenuBarExtra {
            MenuBarMenu()
                .environmentObject(appState)
        } label: {
            Image(systemName: appState.isConnected ? "bolt.horizontal.circle.fill" : "bolt.horizontal.circle")
        }
    }

    private func requestNotificationAuthorizationIfPossible() {
        // Skipped when running as a bare `swift run` binary with no bundle
        // identifier — see macos/README.md.
        guard Bundle.main.bundleIdentifier != nil else { return }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }
}

enum MainWindow {
    static let id = "main"
}
