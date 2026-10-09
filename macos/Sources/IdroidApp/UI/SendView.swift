import SwiftUI
import UniformTypeIdentifiers

/// Send files, text/links, or the clipboard to one device or all of them.
struct SendView: View {
    @EnvironmentObject var appState: AppState
    @State private var target: SendTarget = .all
    @State private var text = ""
    @State private var dropTargeted = false

    var body: some View {
        if appState.devices.isEmpty {
            NoDevicesView()
                .navigationTitle("Send")
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    destinationPicker
                    dropZone
                    composer
                }
                .padding(24)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
            }
            .navigationTitle("Send")
            .onAppear(perform: fixTarget)
            .onChange(of: appState.devices) { fixTarget() }
        }
    }

    private var destinationPicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Send to").font(.headline)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    if appState.devices.count > 1 {
                        chip("All devices", symbol: "square.grid.2x2", online: nil, value: .all)
                    }
                    ForEach(appState.devices) { device in
                        chip(device.name, symbol: device.symbolName, online: device.online, value: .device(device.id))
                    }
                }
            }
        }
    }

    private func chip(_ title: String, symbol: String, online: Bool?, value: SendTarget) -> some View {
        let selected = target == value
        return Button {
            target = value
        } label: {
            HStack(spacing: 6) {
                Image(systemName: symbol)
                Text(title)
                if let online { OnlineDot(online: online) }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(selected ? Color.idroidIndigo : Color.secondary.opacity(0.12), in: Capsule())
            .foregroundStyle(selected ? Color.white : Color.primary)
        }
        .buttonStyle(.plain)
    }

    private var dropZone: some View {
        VStack(spacing: 10) {
            Image(systemName: "arrow.down.doc")
                .font(.system(size: 34, weight: .light))
                .foregroundStyle(dropTargeted ? Color.idroidIndigo : .secondary)
            Text("Drop files here").font(.title3.weight(.medium))
            Button("Choose Files…") { appState.pickAndSendFiles(to: target) }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 180)
        .background(
            RoundedRectangle(cornerRadius: 14)
                .fill(dropTargeted ? Color.idroidIndigo.opacity(0.08) : Color.secondary.opacity(0.05))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .strokeBorder(style: StrokeStyle(lineWidth: 2, dash: [7, 5]))
                .foregroundStyle(dropTargeted ? Color.idroidIndigo : Color.secondary.opacity(0.4))
        )
        .onDrop(of: [.fileURL], isTargeted: $dropTargeted) { providers in
            loadFileURLs(providers) { appState.sendFiles($0, to: target) }
            return true
        }
    }

    private var composer: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Text or link").font(.headline)
            TextEditor(text: $text)
                .font(.body)
                .scrollContentBackground(.hidden)
                .padding(8)
                .frame(minHeight: 90, maxHeight: 160)
                .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
                .overlay(alignment: .topLeading) {
                    if text.isEmpty {
                        Text("Type or paste something to send…")
                            .foregroundStyle(.tertiary)
                            .padding(.horizontal, 13)
                            .padding(.vertical, 8)
                            .allowsHitTesting(false)
                    }
                }
            HStack {
                Button {
                    appState.sendClipboard(to: target)
                } label: {
                    Label("Send Clipboard", systemImage: "doc.on.clipboard")
                }
                Spacer()
                Button {
                    appState.sendText(text, to: target)
                    text = ""
                } label: {
                    Label("Send", systemImage: "paperplane.fill")
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.return, modifiers: .command)
                .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
    }

    /// "All devices" only makes sense with more than one; otherwise pick the one there is.
    private func fixTarget() {
        if case .device(let id) = target, appState.devices.contains(where: { $0.id == id }) { return }
        target = appState.devices.count == 1 ? .device(appState.devices[0].id) : .all
    }
}

struct NoDevicesView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        ContentUnavailableView {
            Label("No devices yet", systemImage: "laptopcomputer.and.iphone")
        } description: {
            Text("Pair your phone, another Mac or an iPad to start sharing.")
        } actions: {
            Button("Add Device…") { appState.isAddDevicePresented = true }
                .buttonStyle(.borderedProminent)
        }
    }
}

/// Resolves dropped Finder items to file URLs, calling back on the main actor.
func loadFileURLs(_ providers: [NSItemProvider], completion: @escaping @MainActor ([URL]) -> Void) {
    let group = DispatchGroup()
    var urls: [URL] = []
    let lock = NSLock()
    for provider in providers where provider.canLoadObject(ofClass: URL.self) {
        group.enter()
        _ = provider.loadObject(ofClass: URL.self) { url, _ in
            if let url, url.isFileURL {
                lock.lock(); urls.append(url); lock.unlock()
            }
            group.leave()
        }
    }
    group.notify(queue: .main) {
        MainActor.assumeIsolated { completion(urls) }
    }
}
