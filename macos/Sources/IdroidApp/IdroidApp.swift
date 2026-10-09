import SwiftUI
import UserNotifications

@main
struct IdroidApp: App {
    @StateObject private var appState = AppState()

    init() {
        requestNotificationAuthorizationIfPossible()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(appState)
        }
        .windowResizability(.contentSize)

        // Always-on presence in the menu bar, the macOS counterpart to the
        // Android foreground service's persistent notification
        // (RelaySyncService.kt) — a visible reminder that sync is live.
        MenuBarExtra("Idroid", systemImage: "bolt.horizontal.circle") {
            Button("Refresh inbox") { appState.refreshInbox() }
            Divider()
            Button("Quit") { NSApplication.shared.terminate(nil) }
        }
    }

    private func requestNotificationAuthorizationIfPossible() {
        // Skipped when running as a bare `swift run` binary with no bundle
        // identifier — see macos/README.md.
        guard Bundle.main.bundleIdentifier != nil else { return }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }
}
