import SwiftUI

struct ContentView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        Group {
            if appState.pairing != nil {
                HomeView()
            } else {
                PairingView()
            }
        }
    }
}
