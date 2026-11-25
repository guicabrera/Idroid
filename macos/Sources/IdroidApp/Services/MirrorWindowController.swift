import AppKit
import AVFoundation
import CoreMedia
import Network

/// Phone screen mirror window (ADR-0009). Connects straight to the phone's
/// MirrorService over TCP, decodes its H.264 stream through
/// AVSampleBufferDisplayLayer (hardware decoding), and turns clicks, drags,
/// scrolls and keys inside the window into the ADR-0008 input messages.
final class MirrorWindowController: NSObject, NSWindowDelegate {

    private var window: NSWindow?
    private var view: MirrorView?
    private var connection: NWConnection?
    private var buffer = Data()
    private var format: CMVideoFormatDescription?
    private let send: ([String: Any]) -> Void
    var onClose: (() -> Void)?

    init(send: @escaping ([String: Any]) -> Void) {
        self.send = send
    }

    /// Opens the window right away, waiting for the phone's consent.
    func showWaiting(title: String) {
        if window != nil { return }
        makeWindow(width: 720, height: 1600, title: title)
        view?.showMessage("Aguardando o celular…\nAceite \"Começar a gravar/transmitir\" na tela do celular.")
    }

    func open(addrs: [String], port: Int, key: String, width: Int, height: Int, title: String) {
        connection?.cancel()
        buffer = Data()
        format = nil
        if let window {
            window.contentAspectRatio = NSSize(width: width, height: height)
            let h = window.frame.height
            window.setContentSize(NSSize(width: h * CGFloat(width) / CGFloat(height), height: window.contentLayoutRect.height))
        } else {
            makeWindow(width: width, height: height, title: title)
        }
        view?.showMessage("Conectando ao celular…")
        connect(addrs: addrs, port: port, key: key)
    }

    private func makeWindow(width: Int, height: Int, title: String) {
        let view = MirrorView(frame: NSRect(x: 0, y: 0, width: width, height: height))
        view.send = send
        self.view = view

        // Fit the phone on screen: at most ~85% of the visible height.
        let visible = NSScreen.main?.visibleFrame.height ?? 900
        let scale = min(1, visible * 0.85 / CGFloat(height))
        let size = NSSize(width: CGFloat(width) * scale, height: CGFloat(height) * scale)
        let window = NSWindow(contentRect: NSRect(origin: .zero, size: size),
                              styleMask: [.titled, .closable, .resizable, .miniaturizable], backing: .buffered, defer: false)
        window.title = title
        window.contentView = view
        window.contentAspectRatio = NSSize(width: width, height: height)
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.center()
        window.makeKeyAndOrderFront(nil)
        window.makeFirstResponder(view)
        NSApp.activate(ignoringOtherApps: true)
        self.window = window
    }

    func close(notify: Bool = true) {
        connection?.cancel()
        connection = nil
        buffer = Data()
        format = nil
        if let window {
            window.delegate = nil
            window.close()
        }
        window = nil
        view = nil
        if notify { onClose?() }
    }

    var isOpen: Bool { window != nil }

    func windowWillClose(_ notification: Notification) {
        window = nil
        close(notify: true)
    }

    // MARK: - Network

    /// Tries every address the phone advertised; the first to connect wins.
    private func connect(addrs: [String], port: Int, key: String) {
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(port)) else { return }
        var won = false
        for addr in addrs {
            let params = NWParameters.tcp
            if let tcp = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options { tcp.noDelay = true }
            params.serviceClass = .interactiveVideo
            let conn = NWConnection(host: NWEndpoint.Host(addr), port: nwPort, using: params)
            conn.stateUpdateHandler = { [weak self, weak conn] state in
                guard let self, let conn else { return }
                switch state {
                case .ready:
                    if won { conn.cancel(); return }
                    won = true
                    self.connection = conn
                    conn.send(content: Data((key + "\n").utf8), completion: .contentProcessed { _ in })
                    self.receive(conn)
                case .failed, .cancelled:
                    if conn === self.connection { self.view?.showMessage("Conexão com o celular caiu") }
                default: break
                }
            }
            conn.start(queue: .main)
            DispatchQueue.main.asyncAfter(deadline: .now() + 5) { if !won { conn.cancel() } else if conn !== self.connection { conn.cancel() } }
        }
    }

    private func receive(_ conn: NWConnection) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { [weak self] data, _, done, error in
            guard let self, conn === self.connection else { return }
            if let data { self.buffer.append(data); self.parse() }
            if done || error != nil { self.view?.showMessage("Espelhamento encerrado"); return }
            self.receive(conn)
        }
    }

    /// Frames: [int32 len][int64 pts][u8 flags][Annex B].
    private func parse() {
        while buffer.count >= 13 {
            let len = Int(buffer.readUInt32(at: 0))
            guard buffer.count >= 13 + len else { return }
            let flags = buffer[buffer.startIndex + 12]
            let payload = buffer.subdata(in: (buffer.startIndex + 13)..<(buffer.startIndex + 13 + len))
            buffer.removeSubrange(buffer.startIndex..<(buffer.startIndex + 13 + len))
            handle(payload, isConfig: flags & 1 != 0)
        }
    }

    // MARK: - H.264

    private func handle(_ annexB: Data, isConfig: Bool) {
        let nalus = Self.splitAnnexB(annexB)
        var sps: Data?, pps: Data?
        var frameNALUs: [Data] = []
        for nalu in nalus where !nalu.isEmpty {
            switch nalu[nalu.startIndex] & 0x1F {
            case 7: sps = nalu
            case 8: pps = nalu
            default: frameNALUs.append(nalu)
            }
        }
        if let sps, let pps { makeFormat(sps: sps, pps: pps) }
        guard !frameNALUs.isEmpty, let format else { return }

        // AVCC: each NALU prefixed by its 4-byte big-endian length.
        var avcc = Data()
        for nalu in frameNALUs {
            var len = UInt32(nalu.count).bigEndian
            avcc.append(Data(bytes: &len, count: 4))
            avcc.append(nalu)
        }
        var block: CMBlockBuffer?
        let count = avcc.count
        guard CMBlockBufferCreateWithMemoryBlock(allocator: nil, memoryBlock: nil, blockLength: count, blockAllocator: nil,
                                                 customBlockSource: nil, offsetToData: 0, dataLength: count, flags: 0,
                                                 blockBufferOut: &block) == noErr, let block else { return }
        avcc.withUnsafeBytes { _ = CMBlockBufferReplaceDataBytes(with: $0.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: count) }
        var sample: CMSampleBuffer?
        var size = count
        guard CMSampleBufferCreateReady(allocator: nil, dataBuffer: block, formatDescription: format, sampleCount: 1,
                                        sampleTimingEntryCount: 0, sampleTimingArray: nil, sampleSizeEntryCount: 1,
                                        sampleSizeArray: &size, sampleBufferOut: &sample) == noErr, let sample else { return }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true),
           CFArrayGetCount(attachments) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        view?.enqueue(sample)
    }

    private func makeFormat(sps: Data, pps: Data) {
        var newFormat: CMVideoFormatDescription?
        sps.withUnsafeBytes { spsPtr in
            pps.withUnsafeBytes { ppsPtr in
                let pointers = [spsPtr.bindMemory(to: UInt8.self).baseAddress!, ppsPtr.bindMemory(to: UInt8.self).baseAddress!]
                let sizes = [sps.count, pps.count]
                _ = CMVideoFormatDescriptionCreateFromH264ParameterSets(allocator: nil, parameterSetCount: 2,
                    parameterSetPointers: pointers, parameterSetSizes: sizes, nalUnitHeaderLength: 4, formatDescriptionOut: &newFormat)
            }
        }
        if let newFormat { format = newFormat }
    }

    static func splitAnnexB(_ data: Data) -> [Data] {
        let bytes = [UInt8](data)
        var starts: [(Int, Int)] = [] // (start code index, payload index)
        var i = 0
        while i + 3 <= bytes.count {
            if bytes[i] == 0, bytes[i + 1] == 0 {
                if bytes[i + 2] == 1 { starts.append((i, i + 3)); i += 3; continue }
                if i + 4 <= bytes.count, bytes[i + 2] == 0, bytes[i + 3] == 1 { starts.append((i, i + 4)); i += 4; continue }
            }
            i += 1
        }
        var out: [Data] = []
        for (n, s) in starts.enumerated() {
            let end = n + 1 < starts.count ? starts[n + 1].0 : bytes.count
            out.append(Data(bytes[s.1..<end]))
        }
        return out
    }
}

private extension Data {
    func readUInt32(at offset: Int) -> UInt32 {
        let i = startIndex + offset
        return UInt32(self[i]) << 24 | UInt32(self[i + 1]) << 16 | UInt32(self[i + 2]) << 8 | UInt32(self[i + 3])
    }
}

/// Shows the video and forwards input as normalized phone coordinates.
final class MirrorView: NSView {
    var send: (([String: Any]) -> Void)?
    private let videoLayer = AVSampleBufferDisplayLayer()
    private let label = NSTextField(labelWithString: "Aguardando o celular… (aceite a gravação de tela no celular)")
    private var scrollAccum = CGSize.zero

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
        videoLayer.videoGravity = .resizeAspect
        videoLayer.frame = bounds
        videoLayer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        layer?.addSublayer(videoLayer)
        label.textColor = .white
        label.alignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: centerXAnchor),
            label.centerYAnchor.constraint(equalTo: centerYAnchor),
            label.widthAnchor.constraint(lessThanOrEqualTo: widthAnchor, constant: -24),
        ])
        label.lineBreakMode = .byWordWrapping
        label.maximumNumberOfLines = 0
    }

    required init?(coder: NSCoder) { fatalError() }

    override var acceptsFirstResponder: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    func enqueue(_ sample: CMSampleBuffer) {
        label.isHidden = true
        if videoLayer.status == .failed { videoLayer.flush() }
        videoLayer.enqueue(sample)
    }

    func showMessage(_ text: String) {
        label.stringValue = text
        label.isHidden = false
    }

    private func sendPos(_ event: NSEvent) {
        let p = convert(event.locationInWindow, from: nil)
        let x = max(0, min(1, p.x / bounds.width))
        let y = max(0, min(1, 1 - p.y / bounds.height)) // AppKit's y grows upward
        send?(["t": "pos", "x": Double(x), "y": Double(y)])
    }

    override func mouseDown(with event: NSEvent) { sendPos(event); send?(["t": "down"]) }
    override func mouseDragged(with event: NSEvent) { sendPos(event) }
    override func mouseUp(with event: NSEvent) { sendPos(event); send?(["t": "up"]) }
    override func rightMouseDown(with event: NSEvent) { send?(["t": "rclick"]) }

    override func scrollWheel(with event: NSEvent) {
        sendPos(event)
        // Same convention as the event tap path: point deltas, phone swipes along them.
        send?(["t": "scroll", "dx": Double(event.scrollingDeltaX), "dy": Double(event.scrollingDeltaY)])
    }

    override func keyDown(with event: NSEvent) {
        let cmd = event.modifierFlags.contains(.command)
        if cmd {
            switch event.keyCode {
            case 0: send?(["t": "key", "k": "selectAll"])
            case 9: if let s = NSPasteboard.general.string(forType: .string) { send?(["t": "text", "s": s]) }
            case 4: send?(["t": "key", "k": "home"])
            case 13: window?.performClose(nil) // ⌘W
            default: super.keyDown(with: event)
            }
            return
        }
        let named: [UInt16: String] = [
            51: "backspace", 117: "delete", 36: "enter", 76: "enter", 48: "tab", 53: "esc",
            123: "left", 124: "right", 125: "down", 126: "up",
        ]
        if let k = named[event.keyCode] { send?(["t": "key", "k": k]); return }
        if let chars = event.characters, !chars.isEmpty,
           chars.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }) {
            send?(["t": "text", "s": chars])
        }
    }
}
